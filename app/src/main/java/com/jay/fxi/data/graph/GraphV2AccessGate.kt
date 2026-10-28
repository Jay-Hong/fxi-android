package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.admitsKrxUse
import com.jay.fxi.data.entitlements.admitsUse
import com.jay.fxi.data.entitlements.krxConsumerGuard
import com.jay.fxi.data.entitlements.krxUseFor
import com.jay.fxi.data.entitlements.purge.KrxMemoryScope
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The caller's session and use lifetime, with the capability epoch allowed when bound.
 *
 * [capabilityInvalidations] is the snapshot's count when bound (S6-2b-1b design r5 §3, D-27), zero while the contest is off. The
 * default keeps three-argument constructions as they are.
 */
internal data class GraphV2AccessCapture(
    val fence: TopicSessionFence,
    val lifetime: TopicUseLifetime,
    val krxCapabilityEpoch: String?,
    val capabilityInvalidations: Long = 0L,
    val krxGraphStamp: KrxGraphStamp? = null
)

internal data class GraphV2CapabilityConfiguration(val recordEpoch: String?, val allowed: Boolean)

internal data class GraphV2AccessBinding(
    val captured: GraphV2AccessCapture,
    val configuration: GraphV2CapabilityConfiguration
)

/** Memory-only access policy. Every use checks live inputs and one fresh published snapshot. */
internal class GraphV2AccessGate(
    private val currentIdentity: () -> AuthIdentityFence?,
    private val currentAccessFence: () -> TopicSessionFence?,
    private val snapshot: () -> TopicAccessSnapshot,
    private val protectedAdmission: () -> Boolean,
    private val krxGraphFence: KrxGraphFence? = null
) {
    val krxGraphRevisions: StateFlow<Long> = krxGraphFence?.revisions ?: MutableStateFlow(0L).asStateFlow()
    fun bind(fence: TopicSessionFence, lifetime: TopicUseLifetime): GraphV2AccessCapture? =
        bindWithConfiguration(fence, lifetime)?.captured

    fun bindWithConfiguration(fence: TopicSessionFence, lifetime: TopicUseLifetime): GraphV2AccessBinding? =
        binding(fence, lifetime, issue = false)

    fun captureKrxGraph(fence: TopicSessionFence, lifetime: TopicUseLifetime): GraphV2AccessBinding? =
        binding(fence, lifetime, issue = true)

    fun bindKrxGraph(
        fence: TopicSessionFence,
        lifetime: TopicUseLifetime,
        stamp: KrxGraphStamp
    ): GraphV2AccessCapture? {
        val binding = binding(fence, lifetime, issue = false, original = stamp) ?: return null
        return binding.captured.takeIf { it.krxGraphStamp != null }
    }

    private fun binding(
        fence: TopicSessionFence,
        lifetime: TopicUseLifetime,
        issue: Boolean,
        original: KrxGraphStamp? = null
    ): GraphV2AccessBinding? {
        if (lifetime.grant != fence.grant) return null
        val access = admittedSnapshot(fence, lifetime) ?: return null
        val configuration = GraphV2CapabilityConfiguration(
            access.facts.recordFence?.krxCapabilityEpoch, access.facts.capabilityAllowed
        )
        val epoch = configuration.recordEpoch.takeIf { configuration.allowed }
        val stamp = if (original != null) {
            original.takeIf { admitsOriginal(fence, lifetime, it, access) }
        } else if (issue && krxGraphFence != null && krxConsumerGuard(access.krxPermitSeq)) {
            access.krxUseFor(lifetime)?.let { use ->
                krxGraphFence.capture(KrxMemoryScope(fence, use, access.revision,
                    access.lastUserEnd?.sequence ?: 0L, access.lastCapabilityEnd?.sequence ?: 0L))
            }
        } else null
        val capturedEpoch = epoch.takeIf {
            !issue || krxGraphFence == null || !krxConsumerGuard(access.krxPermitSeq) || stamp != null
        }
        return GraphV2AccessBinding(GraphV2AccessCapture(fence, lifetime, capturedEpoch, access.capabilityInvalidations, stamp), configuration)
    }

    fun admits(captured: GraphV2AccessCapture, component: GraphV2DiskComponent): Boolean {
        val access = admittedSnapshot(captured.fence, captured.lifetime) ?: return false
        return when (component) {
            GraphV2DiskComponent.GENERAL -> true
            GraphV2DiskComponent.KRX -> admitsKrx(captured, access)
        }
    }

    /** Creating a port grants nothing; each call checks the capture again. */
    fun ioAdmission(captured: GraphV2AccessCapture): GraphV2IoAdmission =
        GraphV2IoAdmission { component -> admits(captured, component) }

    /**
     * [krxCapabilityInvalidations] is the count the KRX component is attributed to: the capture's own by default, the original
     * request's or seed's when memory is exposed under a freshly bound capture (S6-2b-1b design r5 §3, D-6).
     */
    fun joinForExposure(
        captured: GraphV2AccessCapture,
        components: GraphV2DiskComponents,
        krxCapabilityInvalidations: Long = captured.capabilityInvalidations,
        krxGraphStamp: KrxGraphStamp? = captured.krxGraphStamp
    ): GraphV2ComponentJoin? {
        val access = admittedSnapshot(captured.fence, captured.lifetime) ?: return null
        val general = components.general
        if (general.key.uid != captured.fence.identity.uid ||
            general.key.userAccessEpoch != captured.fence.userAccessEpoch
        ) return null
        val krx = components.krx?.takeIf {
            admitsKrx(captured, access, krxCapabilityInvalidations, krxGraphStamp) && it.key.krxCapabilityEpoch == captured.krxCapabilityEpoch
        }
        return joinGraphV2Components(general, krx)
    }

    /** A non-null result admits GENERAL; KRX uses this same snapshot for its additional check. */
    private fun admittedSnapshot(fence: TopicSessionFence, lifetime: TopicUseLifetime): TopicAccessSnapshot? {
        if (!protectedAdmission() || currentIdentity() != fence.identity || currentAccessFence() != fence ||
            fence.userAccessEpoch == null
        ) return null
        val access = snapshot()
        return access.takeIf { it.admitsUse(lifetime) }
    }

    private fun admitsKrx(
        captured: GraphV2AccessCapture,
        access: TopicAccessSnapshot,
        krxCapabilityInvalidations: Long = captured.capabilityInvalidations,
        stamp: KrxGraphStamp? = captured.krxGraphStamp
    ): Boolean {
        if (captured.krxCapabilityEpoch == null || !access.facts.capabilityAllowed) return false
        // An original ON capture can never become a legacy capture when the permit later disappears.
        val original = stamp ?: captured.krxGraphStamp
        if (original != null) return admitsOriginal(captured.fence, captured.lifetime, original, access) &&
            original.scope.use.krxCapabilityEpoch == captured.krxCapabilityEpoch
        if (!krxConsumerGuard(access.krxPermitSeq)) return true
        if (krxGraphFence != null) return false
        return captured.krxCapabilityEpoch == access.facts.recordFence?.krxCapabilityEpoch &&
            krxCapabilityInvalidations == access.capabilityInvalidations
    }

    private fun admitsOriginal(
        fence: TopicSessionFence,
        lifetime: TopicUseLifetime,
        stamp: KrxGraphStamp,
        access: TopicAccessSnapshot
    ): Boolean = stamp.scope.owner == fence && stamp.scope.use.lifetime == lifetime &&
        krxGraphNamespace(stamp.scope) != null && access.admitsKrxUse(stamp.scope.use) &&
        krxGraphFence?.permits(stamp) == true
}
