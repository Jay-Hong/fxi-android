package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.purge.KrxMemoryScope
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class KrxGraphStamp(val scope: KrxMemoryScope, val issueSequence: Long)
internal data class KrxGraphNamespace(
    val identity: AuthIdentityFence,
    val userAccessEpoch: String,
    val krxCapabilityEpoch: String
)

/** Structural provenance only. Admission always belongs to the caller's original-use check. */
internal fun krxGraphNamespace(scope: KrxMemoryScope): KrxGraphNamespace? {
    val owner = scope.owner
    val user = owner.userAccessEpoch?.takeIf { it.isNotBlank() } ?: return null
    val capability = scope.use.krxCapabilityEpoch?.takeIf { it.isNotBlank() } ?: return null
    if (owner.identity.uid.isBlank() || owner.grant != scope.use.lifetime.grant) return null
    return KrxGraphNamespace(owner.identity, user, capability)
}

internal enum class KrxGraphTopicResult { INSTALLED, NO_EFFECT, UNRESOLVED }
internal data class KrxGraphTopicCutoff(
    val namespace: KrxGraphNamespace?,
    val throughIssueSequence: Long,
    val revision: Long,
    val result: KrxGraphTopicResult
)

internal interface KrxGraphIngress {
    fun capture(scope: KrxMemoryScope): KrxGraphStamp?
    fun topicUnavailable(original: KrxMemoryScope?): KrxGraphTopicCutoff

    companion object {
        val NONE: KrxGraphIngress = object : KrxGraphIngress {
            override fun capture(scope: KrxMemoryScope): KrxGraphStamp? = null
            override fun topicUnavailable(original: KrxMemoryScope?) =
                KrxGraphTopicCutoff(null, 0L, 0L, KrxGraphTopicResult.NO_EFFECT)
        }
    }
}

/** One memory-only sequence and namespace cutoff, independent of the bounded data queue. */
internal class KrxGraphFence : KrxGraphIngress {
    private val lock = Any()
    private var nextIssueSequence = 0L
    private val cutoffs = mutableMapOf<KrxGraphNamespace, Long>()
    private val mutableRevisions = MutableStateFlow(0L)
    val revisions: StateFlow<Long> = mutableRevisions.asStateFlow()
    private var closed = false

    val hasIssuedCaptures: Boolean get() = synchronized(lock) { nextIssueSequence != 0L }

    fun memoryCutoffs(): KrxGraphCutoffSnapshot = synchronized(lock) {
        KrxGraphCutoffSnapshot(mutableRevisions.value, cutoffs)
    }

    override fun capture(scope: KrxMemoryScope): KrxGraphStamp? = synchronized(lock) {
        if (closed || krxGraphNamespace(scope) == null) return null
        nextIssueSequence = Math.incrementExact(nextIssueSequence)
        KrxGraphStamp(scope, nextIssueSequence)
    }

    override fun topicUnavailable(original: KrxMemoryScope?): KrxGraphTopicCutoff = synchronized(lock) {
        if (closed) return KrxGraphTopicCutoff(null, 0L, mutableRevisions.value, KrxGraphTopicResult.NO_EFFECT)
        val namespace = original?.let(::krxGraphNamespace)
            ?: return KrxGraphTopicCutoff(null, 0L, mutableRevisions.value, KrxGraphTopicResult.UNRESOLVED)
        val revision = Math.incrementExact(mutableRevisions.value)
        cutoffs[namespace] = nextIssueSequence
        mutableRevisions.value = revision
        KrxGraphTopicCutoff(namespace, nextIssueSequence, revision, KrxGraphTopicResult.INSTALLED)
    }

    fun permits(stamp: KrxGraphStamp): Boolean = synchronized(lock) {
        val namespace = krxGraphNamespace(stamp.scope) ?: return false
        !closed && stamp.issueSequence > 0L && stamp.issueSequence <= nextIssueSequence &&
            stamp.issueSequence > (cutoffs[namespace] ?: 0L)
    }

    fun close() {
        synchronized(lock) { closed = true }
    }
}

/** Own both collection levels: a delayed loss never aliases the caller's mutable ledger. */
internal fun copyKrxGraphStamps(source: Map<String, Set<KrxGraphStamp>>): Map<String, Set<KrxGraphStamp>> =
    Collections.unmodifiableMap(source.mapValues { (_, stamps) -> Collections.unmodifiableSet(stamps.toSet()) })
