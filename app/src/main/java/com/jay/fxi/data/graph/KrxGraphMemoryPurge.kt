package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.purge.KrxMemoryCause
import com.jay.fxi.data.entitlements.purge.krxMemorySelections
import com.jay.fxi.data.remote.TopicGraphAuthority
import com.jay.fxi.data.remote.TopicGraphEventPayload
import com.jay.fxi.data.remote.TopicGraphInput
import java.util.Collections

internal enum class KrxGraphPurgeDecision { REMOVE, KEEP, UNRESOLVED }

internal data class KrxGraphPurgeOutcome(val removed: Boolean, val unresolved: Boolean) {
    operator fun plus(other: KrxGraphPurgeOutcome) =
        KrxGraphPurgeOutcome(removed || other.removed, unresolved || other.unresolved)
}

internal data class KrxGraphPurgeState(
    val accessRevision: Long,
    val cutoffRevision: Long,
    val outcome: KrxGraphPurgeOutcome?,
    val failure: Throwable?
)

/** An owned, immutable view; neither construction nor classification acquires a new use. */
internal class KrxGraphCutoffSnapshot(revision: Long, cutoffs: Map<KrxGraphNamespace, Long>) {
    val revision: Long = revision
    val cutoffs: Map<KrxGraphNamespace, Long> = Collections.unmodifiableMap(cutoffs.toMap())
}

internal class KrxGraphPurgePlan private constructor(
    private val snapshot: TopicAccessSnapshot?,
    private val cutoffs: Map<KrxGraphNamespace, Long>,
    private val capabilityOwner: String?,
    private val currentCapability: String?,
    val accessRevision: Long,
    val cutoffRevision: Long
) {
    companion object {
        fun forSnapshot(snapshot: TopicAccessSnapshot, cutoffs: KrxGraphCutoffSnapshot): KrxGraphPurgePlan {
            val facts = snapshot.facts
            val frozen = snapshot.copy(facts = facts.copy(
                userBlocks = Collections.unmodifiableSet(facts.userBlocks.toSet()),
                capabilityBlocks = Collections.unmodifiableSet(facts.capabilityBlocks.toSet())
            ))
            return KrxGraphPurgePlan(frozen, Collections.unmodifiableMap(cutoffs.cutoffs.toMap()),
                null, null, snapshot.revision, cutoffs.revision)
        }

        fun forCapability(namespace: PurgeNamespace, live: TopicAccessSnapshot): KrxGraphPurgePlan? {
            val pending = namespace.pending
            val uid = pending.ownerUid?.takeIf { it.isNotBlank() } ?: return null
            if (namespace.ownerUid != uid || PurgeScope.CAPABILITY !in pending.scopes) return null
            val facts = live.facts
            val record = facts.recordFence ?: return null
            val epoch = record.krxCapabilityEpoch?.takeIf { it.isNotBlank() } ?: return null
            if (record.ownerUid != uid || facts.recordUnconfirmed || facts.capabilityContextUncertain ||
                namespace.currentKrxCapabilityEpoch != epoch || pending.krxCapabilityEpoch == epoch) return null
            return KrxGraphPurgePlan(null, emptyMap(), uid, epoch, live.revision, 0L)
        }

        fun forCutoff(cutoff: KrxGraphTopicCutoff): KrxGraphPurgePlan? {
            val namespace = cutoff.namespace ?: return null
            if (cutoff.result != KrxGraphTopicResult.INSTALLED || namespace.identity.uid.isBlank() ||
                namespace.userAccessEpoch.isBlank() || namespace.krxCapabilityEpoch.isBlank() ||
                cutoff.throughIssueSequence < 0L) return null
            return KrxGraphPurgePlan(null, mapOf(namespace to cutoff.throughIssueSequence),
                null, null, 0L, cutoff.revision)
        }
    }

    private fun relevant(uid: String?): Boolean {
        val owners = buildSet {
            capabilityOwner?.let(::add)
            addAll(cutoffs.keys.map { it.identity.uid })
            snapshot?.let { access ->
                access.lastUserEnd?.ownerUid?.let(::add)
                access.lastCapabilityEnd?.ownerUid?.let(::add)
                val facts = access.facts
                val record = facts.recordFence
                if (!facts.recordUnconfirmed &&
                    (!facts.userContextUncertain && !record?.userAccessEpoch.isNullOrBlank() ||
                        !facts.capabilityContextUncertain && !record?.krxCapabilityEpoch.isNullOrBlank())) {
                    facts.recordFence?.ownerUid?.let(::add)
                }
            }
        }
        if (snapshot?.let { listOfNotNull(it.lastUserEnd, it.lastCapabilityEnd).any { end ->
                end.ownerUid.isNullOrBlank()
            } } == true) return true
        return if (uid.isNullOrBlank()) owners.isNotEmpty() else uid in owners
    }

    fun classify(stamp: KrxGraphStamp?, ownerUid: String?): KrxGraphPurgeDecision {
        if (!relevant(ownerUid)) {
            return if (stamp != null && relevant(stamp.scope.owner.identity.uid)) KrxGraphPurgeDecision.UNRESOLVED
            else KrxGraphPurgeDecision.KEEP
        }
        if (stamp == null || ownerUid.isNullOrBlank() || stamp.scope.owner.identity.uid != ownerUid ||
            stamp.issueSequence <= 0L || krxGraphNamespace(stamp.scope) == null) {
            return KrxGraphPurgeDecision.UNRESOLVED
        }
        val original = stamp.scope
        val namespace = checkNotNull(krxGraphNamespace(original))
        if (stamp.issueSequence <= (cutoffs[namespace] ?: 0L)) return KrxGraphPurgeDecision.REMOVE
        // A frozen publication cannot retire a source acquired at or after that publication.
        if (accessRevision <= original.acquiredRevision) return KrxGraphPurgeDecision.KEEP
        if (capabilityOwner == ownerUid) {
            return if (namespace.krxCapabilityEpoch != currentCapability) KrxGraphPurgeDecision.REMOVE
            else KrxGraphPurgeDecision.KEEP
        }
        val access = snapshot ?: return KrxGraphPurgeDecision.KEEP
        val selections = krxMemorySelections(access, setOf(original), 0L, 0L, false)
        if (selections.any { it.cause != KrxMemoryCause.WITHHELD && original in it.scopes }) {
            return KrxGraphPurgeDecision.REMOVE
        }
        return if (selections.any { it.unresolved }) KrxGraphPurgeDecision.UNRESOLVED else KrxGraphPurgeDecision.KEEP
    }

    /** A legacy topic-only loss has no original that could authorize deletion. */
    fun unresolvedTopics(topics: Set<String>, ownerUid: String?): Boolean =
        topics.any(::isKrxGraphTopic) && classify(null, ownerUid) == KrxGraphPurgeDecision.UNRESOLVED

    /** Only identical evidence is deduplicated; no old proof is replaced by a newer unknown. */
    internal fun sameProof(other: KrxGraphPurgePlan): Boolean = snapshot == other.snapshot &&
        cutoffs == other.cutoffs && capabilityOwner == other.capabilityOwner &&
        currentCapability == other.currentCapability && accessRevision == other.accessRevision &&
        cutoffRevision == other.cutoffRevision
}

internal class KrxGraphPurgeSeal private constructor(private val proofs: List<KrxGraphPurgePlan>) {
    companion object {
        val EMPTY = KrxGraphPurgeSeal(emptyList())
    }

    fun merge(plan: KrxGraphPurgePlan): KrxGraphPurgeSeal =
        if (proofs.any { it.sameProof(plan) }) this else KrxGraphPurgeSeal(proofs + plan)

    fun classify(stamp: KrxGraphStamp?, ownerUid: String?): KrxGraphPurgeDecision {
        var unresolved = false
        for (proof in proofs) {
            when (proof.classify(stamp, ownerUid)) {
                KrxGraphPurgeDecision.REMOVE -> return KrxGraphPurgeDecision.REMOVE
                KrxGraphPurgeDecision.UNRESOLVED -> unresolved = true
                KrxGraphPurgeDecision.KEEP -> Unit
            }
        }
        return if (unresolved) KrxGraphPurgeDecision.UNRESOLVED else KrxGraphPurgeDecision.KEEP
    }
}

internal data class KrxGraphInputProjection(val input: TopicGraphInput?, val outcome: KrxGraphPurgeOutcome)

internal fun projectKrxGraphInput(input: TopicGraphInput, plan: KrxGraphPurgePlan): KrxGraphInputProjection =
    projectInput(input, plan::classify)

internal fun projectKrxGraphInput(input: TopicGraphInput, seal: KrxGraphPurgeSeal): KrxGraphInputProjection =
    projectInput(input, seal::classify)

private fun projectInput(
    input: TopicGraphInput,
    classify: (KrxGraphStamp?, String?) -> KrxGraphPurgeDecision
): KrxGraphInputProjection {
    val stamp: KrxGraphStamp?
    val uid: String?
    val hasTopic: Boolean
    val matches: Boolean
    when (input) {
        is TopicGraphInput.Observations -> {
            stamp = input.krxGraphStamp
            uid = input.attribution.owner.identity.uid
            hasTopic = isKrxGraphTopic(input.topic)
            matches = stamp?.scope?.let { it.owner == input.attribution.owner && it.use.lifetime == input.attribution.lifetime } == true
        }
        is TopicGraphInput.Continuity -> {
            stamp = input.authority.krxGraphStamp
            uid = input.authority.owner?.identity?.uid
            hasTopic = input.topics.any(::isKrxGraphTopic)
            matches = stamp?.scope?.let { it.owner == input.authority.owner && it.use.lifetime == input.authority.lifetime } == true
        }
    }
    if (!hasTopic && stamp == null) return KrxGraphInputProjection(input, KrxGraphPurgeOutcome(false, false))
    val decision = if (matches) classify(stamp, uid) else {
        // A contradictory sidecar is evidence of uncertainty, never permission to delete its GENERAL owner.
        if (classify(null, uid) == KrxGraphPurgeDecision.UNRESOLVED ||
            classify(stamp, uid) != KrxGraphPurgeDecision.KEEP) KrxGraphPurgeDecision.UNRESOLVED
        else KrxGraphPurgeDecision.KEEP
    }
    if (decision != KrxGraphPurgeDecision.REMOVE) {
        return KrxGraphInputProjection(input, KrxGraphPurgeOutcome(false, decision == KrxGraphPurgeDecision.UNRESOLVED))
    }
    val kept = when (input) {
        is TopicGraphInput.Observations -> if (hasTopic) null else TopicGraphInput.Observations(
            input.sequence, input.topic, input.path, input.attribution, input.connectionGeneration, input.candidates, null
        )
        is TopicGraphInput.Continuity -> {
            val topics = input.topics.filterNotTo(linkedSetOf(), ::isKrxGraphTopic)
            if (topics.isEmpty()) null else {
                val original = input.authority
                val payload = when (val held = input.payload) {
                    is TopicGraphEventPayload.AckApplied -> TopicGraphEventPayload.AckApplied(
                        held.confirmedTopics.filterNotTo(linkedSetOf(), ::isKrxGraphTopic),
                        held.rejected.filterKeys { !isKrxGraphTopic(it) }
                    )
                    else -> held
                }
                TopicGraphInput.Continuity(input.sequence, input.kind, input.reason, topics, input.paths,
                    TopicGraphAuthority(original.sessionKey, original.owner, original.grantEpoch, original.lifetime, null),
                    input.connectionGeneration, input.occurredAtEpochMillis, payload)
            }
        }
    }
    return KrxGraphInputProjection(kept, KrxGraphPurgeOutcome(true, false))
}
