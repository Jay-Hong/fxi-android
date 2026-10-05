package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.admitsUse
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime

/** The caller's session and use lifetime, with the capability epoch allowed when bound. */
internal data class GraphV2AccessCapture(
    val fence: TopicSessionFence,
    val lifetime: TopicUseLifetime,
    val krxCapabilityEpoch: String?
)

/** Memory-only access policy. Every use checks live inputs and one fresh published snapshot. */
internal class GraphV2AccessGate(
    private val currentIdentity: () -> AuthIdentityFence?,
    private val currentAccessFence: () -> TopicSessionFence?,
    private val snapshot: () -> TopicAccessSnapshot,
    private val protectedAdmission: () -> Boolean
) {
    fun bind(fence: TopicSessionFence, lifetime: TopicUseLifetime): GraphV2AccessCapture? {
        if (lifetime.grant != fence.grant) return null
        val access = admittedSnapshot(fence, lifetime) ?: return null
        val epoch = if (access.facts.capabilityAllowed) access.facts.recordFence?.krxCapabilityEpoch else null
        return GraphV2AccessCapture(fence, lifetime, epoch)
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

    fun joinForExposure(
        captured: GraphV2AccessCapture,
        components: GraphV2DiskComponents
    ): GraphV2ComponentJoin? {
        val access = admittedSnapshot(captured.fence, captured.lifetime) ?: return null
        val general = components.general
        if (general.key.uid != captured.fence.identity.uid ||
            general.key.userAccessEpoch != captured.fence.userAccessEpoch
        ) return null
        val krx = components.krx?.takeIf {
            admitsKrx(captured, access) && it.key.krxCapabilityEpoch == captured.krxCapabilityEpoch
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

    private fun admitsKrx(captured: GraphV2AccessCapture, access: TopicAccessSnapshot): Boolean =
        captured.krxCapabilityEpoch != null && access.facts.capabilityAllowed
}
