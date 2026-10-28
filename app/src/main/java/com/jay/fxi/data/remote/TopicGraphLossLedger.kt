package com.jay.fxi.data.remote

import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.KrxGraphPurgeDecision
import com.jay.fxi.data.graph.KrxGraphPurgeOutcome
import com.jay.fxi.data.graph.KrxGraphPurgePlan
import com.jay.fxi.data.graph.KrxGraphPurgeSeal
import com.jay.fxi.data.graph.KrxGraphStamp
import com.jay.fxi.data.graph.copyKrxGraphStamps
import com.jay.fxi.data.graph.isKrxGraphTopic
import com.jay.fxi.data.graph.krxGraphLoss
import java.util.Collections

/** Explicit cleanup boundaries; [TopicGraphLossLedger.purge] copies both collections before selecting scopes. */
internal data class GraphLossRetirement(
    val retiredScopes: Set<GraphDataScope> = emptySet(),
    val endedUseFloors: Map<GraphDataScope, Long> = emptyMap()
)

/**
 * Dormant ledger of whole-batch FULL, CLOSED and FAILED losses.
 *
 * Scoped aggregates are keyed by UID/access epoch, original lifetime invalidations, original KRX
 * stamp composition, GENERAL topics and the presence of unstamped KRX. This retains whole-batch
 * accounting when a mixed batch survives selection and a pure KRX batch is removed.
 * Missing owners or access epochs share one unscoped bucket, outside selection and cleanup boundaries.
 * Retired scopes permanently refuse all losses and take precedence over ended-use floors. Floors only
 * rise: they remove and refuse absent lifetimes and invalidations below the floor; equality passes,
 * including a fresh lifetime under the same authority key. Grant changes alone install no boundary.
 *
 * Revision increases once per accepted loss and once per purge that removes existing records. Refusals
 * and boundary-only purges leave it unchanged. No purge resets the revision or the single highest
 * accepted sequence shared by scoped and unscoped losses, not even one that removes every record (the
 * view is then null). Duplicate or older sequences are refused; rejected inputs never move this mark.
 * First/last times follow sequence order and use the supplied loss time, independently of the
 * continuity's own clock or wall-clock reversals.
 *
 * The caller must read [view] and call [record] and [purge] on the same serial executor; there is no
 * internal synchronization. A selector must not mutate this ledger or re-enter record/purge. [purge]
 * passes each distinct scope that holds losses or is named in the retirement to the selector at most
 * once (exactly once unless it throws); a scope known only from an earlier boundary is not passed.
 * [purge] evaluates the selector, validates the retirement and builds the remaining view before one
 * state assignment commits its removals and boundaries together; a selector exception propagates
 * unchanged and a failed validation throws [IllegalArgumentException], either leaving all state
 * unchanged. Input sets and retirement collections are copied into owned state. Candidates, payloads
 * and original input objects are never retained.
 *
 * 실제 topic 정리·운영 배선은 cutover. This helper does not create operational instances or register a purger.
 */
internal class TopicGraphLossLedger {
    private data class Key(
        val scope: GraphDataScope?,
        val invalidations: Long?,
        val originalKrxLost: Map<String, Set<KrxGraphStamp>>,
        val generalTopics: Set<String>,
        val hasUnstampedKrx: Boolean
    )

    private data class Aggregate(
        val count: Long,
        val firstSequence: Long,
        val lastSequence: Long,
        val firstAt: Long,
        val lastAt: Long,
        val reasons: Set<TopicGraphOffer>,
        val topics: Set<String>,
        val paths: Set<TopicGraphPath>,
        val authorities: Set<TopicGraphAuthorityKey>,
        val krxLost: Map<String, Set<KrxGraphStamp>>
    )

    private data class State(
        val aggregates: Map<Key, Aggregate> = emptyMap(),
        val retiredScopes: Set<GraphDataScope> = emptySet(),
        val endedUseFloors: Map<GraphDataScope, Long> = emptyMap(),
        val revision: Long = 0L,
        val highestSequence: Long? = null,
        val view: TopicGraphLoss? = null
    )

    private var state = State()
    private var purgeSeal = KrxGraphPurgeSeal.EMPTY

    val view: TopicGraphLoss?
        get() = state.view

    /** Returns whether this batch was recorded; every refusal leaves all state unchanged. */
    fun record(input: TopicGraphInput, offer: TopicGraphOffer, lossAtEpochMillis: Long): Boolean {
        when (offer) {
            TopicGraphOffer.ENQUEUED, TopicGraphOffer.DORMANT -> return false
            TopicGraphOffer.FULL, TopicGraphOffer.CLOSED, TopicGraphOffer.FAILED -> Unit
        }
        val held = state
        held.highestSequence?.let { if (input.sequence <= it) return false }

        val owner: TopicSessionFence?
        val lifetime: TopicUseLifetime?
        val topics: Set<String>
        val paths: Set<TopicGraphPath>
        val authority: TopicGraphAuthorityKey
        when (input) {
            is TopicGraphInput.Observations -> {
                owner = input.attribution.owner
                lifetime = input.attribution.lifetime
                topics = setOf(input.topic)
                paths = setOf(input.path)
                authority = TopicGraphAuthorityKey(owner, input.attribution.grantEpoch)
            }
            is TopicGraphInput.Continuity -> {
                owner = input.authority.owner
                lifetime = input.authority.lifetime
                topics = input.topics.toSet()
                paths = input.paths.toSet()
                authority = TopicGraphAuthorityKey(owner, input.authority.grantEpoch)
            }
        }
        val scope = owner?.userAccessEpoch?.let { GraphDataScope(owner.identity.uid, it) }
        if (scope != null) {
            if (scope in held.retiredScopes) return false
            held.endedUseFloors[scope]?.let { floor ->
                if (lifetime == null || lifetime.invalidations < floor) return false
            }
        }

        val original = lossKey(scope, if (scope == null) null else lifetime?.invalidations,
            input.krxGraphLoss(), topics.filterNotTo(linkedSetOf(), ::isKrxGraphTopic),
            topics.any { isKrxGraphTopic(it) && it !in input.krxGraphLoss() })
        val (key, _) = projectKey(original, purgeSeal::classify)
        // A refused late original does not consume a sequence or change the published revision.
        if (key == null) return false
        val retainedTopics = retainedTopics(topics, key)
        val previous = held.aggregates[key]
        val aggregate = Aggregate(
            count = (previous?.count ?: 0L) + 1L,
            firstSequence = previous?.firstSequence ?: input.sequence,
            lastSequence = input.sequence,
            firstAt = previous?.firstAt ?: lossAtEpochMillis,
            lastAt = lossAtEpochMillis,
            reasons = (previous?.reasons ?: emptySet()) + offer,
            topics = (previous?.topics ?: emptySet()) + retainedTopics,
            paths = (previous?.paths ?: emptySet()) + paths,
            authorities = (previous?.authorities ?: emptySet()) + authority,
            krxLost = mergeKrxLoss(previous?.krxLost.orEmpty(), key.originalKrxLost)
        )
        val aggregates = held.aggregates + (key to aggregate)
        val revision = held.revision + 1L
        val nextView = buildView(aggregates, revision)
        state = held.copy(
            aggregates = aggregates,
            revision = revision,
            highestSequence = input.sequence,
            view = nextView
        )
        return true
    }

    /** Seal installation and the fully rebuilt aggregates commit together on the session loop. */
    fun purgeKrxMemory(plan: KrxGraphPurgePlan): KrxGraphPurgeOutcome {
        val held = state
        val seal = purgeSeal.merge(plan)
        var outcome = KrxGraphPurgeOutcome(false, false)
        val aggregates = linkedMapOf<Key, Aggregate>()
        for ((key, aggregate) in held.aggregates) {
            val (kept, projected) = projectKey(key, plan::classify)
            outcome += projected
            if (kept == null) continue
            val remaining = if (kept == key) aggregate else aggregate.copy(
                topics = retainedTopics(aggregate.topics, kept), krxLost = kept.originalKrxLost
            )
            aggregates[kept] = aggregates[kept]?.let { mergeAggregate(it, remaining) } ?: remaining
        }
        val revision = held.revision + if (outcome.removed) 1L else 0L
        val view = if (outcome.removed) buildView(aggregates, revision) else held.view
        val next = held.copy(aggregates = aggregates, revision = revision, view = view)
        purgeSeal = seal
        state = next
        return outcome
    }

    private fun lossKey(
        scope: GraphDataScope?,
        invalidations: Long?,
        originals: Map<String, Set<KrxGraphStamp>>,
        general: Set<String>,
        unknown: Boolean
    ) = Key(scope, invalidations, copyKrxGraphStamps(originals),
        Collections.unmodifiableSet(general.toSet()), unknown)

    private fun projectKey(
        key: Key,
        classify: (KrxGraphStamp?, String?) -> KrxGraphPurgeDecision
    ): Pair<Key?, KrxGraphPurgeOutcome> {
        var outcome = KrxGraphPurgeOutcome(false, false)
        val originals = key.originalKrxLost.mapValues { (_, stamps) -> stamps.filterTo(linkedSetOf()) {
            val decision = classify(it, key.scope?.uid)
            outcome += KrxGraphPurgeOutcome(decision == KrxGraphPurgeDecision.REMOVE,
                decision == KrxGraphPurgeDecision.UNRESOLVED)
            decision != KrxGraphPurgeDecision.REMOVE
        } }.filterValues { it.isNotEmpty() }
        if (key.hasUnstampedKrx) outcome += KrxGraphPurgeOutcome(false,
            classify(null, key.scope?.uid) == KrxGraphPurgeDecision.UNRESOLVED)
        if (!outcome.removed) return key to outcome
        val kept = if (originals.isEmpty() && key.generalTopics.isEmpty() && !key.hasUnstampedKrx) null
        else lossKey(key.scope, key.invalidations, originals, key.generalTopics, key.hasUnstampedKrx)
        return kept to outcome
    }

    private fun retainedTopics(topics: Set<String>, key: Key): Set<String> =
        topics.filterTo(linkedSetOf()) { !isKrxGraphTopic(it) || key.hasUnstampedKrx || it in key.originalKrxLost }

    private fun mergeAggregate(a: Aggregate, b: Aggregate): Aggregate {
        val first = if (a.firstSequence <= b.firstSequence) a else b
        val last = if (a.lastSequence >= b.lastSequence) a else b
        return Aggregate(a.count + b.count, first.firstSequence, last.lastSequence, first.firstAt, last.lastAt,
            a.reasons + b.reasons, a.topics + b.topics, a.paths + b.paths, a.authorities + b.authorities,
            mergeKrxLoss(a.krxLost, b.krxLost))
    }

    /**
     * Returns whether records were removed; false can still mean boundaries were installed or merged.
     * Throws [IllegalArgumentException], changing nothing, when the retirement names a scope the selector
     * rejects or a selected scope that holds losses is not named in the retirement.
     */
    fun purge(selects: (GraphDataScope) -> Boolean, retirement: GraphLossRetirement): Boolean {
        val retiring = retirement.retiredScopes.toSet()
        val ended = retirement.endedUseFloors.toMap()
        val held = state
        val heldScopes = held.aggregates.keys.mapNotNull { it.scope }.toSet()
        val namedScopes = retiring + ended.keys
        val selected = (heldScopes + namedScopes).filter(selects).toSet()
        require(namedScopes.all { it in selected }) { "A retirement names an unselected scope" }
        require(heldScopes.all { it !in selected || it in namedScopes }) {
            "A selected scope holds losses but has no retirement boundary"
        }

        val retiredScopes = held.retiredScopes + retiring
        val endedUseFloors = held.endedUseFloors + ended.mapValues { (scope, floor) ->
            maxOf(floor, held.endedUseFloors[scope] ?: floor)
        }
        val aggregates = held.aggregates.filterKeys { key ->
            val scope = key.scope
            if (scope == null || scope !in selected) {
                true
            } else {
                val floor = endedUseFloors[scope]
                scope !in retiredScopes && (floor == null ||
                    (key.invalidations != null && key.invalidations >= floor))
            }
        }
        val removed = aggregates.size != held.aggregates.size
        val revision = held.revision + if (removed) 1L else 0L
        val nextView = if (removed) buildView(aggregates, revision) else held.view
        state = held.copy(
            aggregates = aggregates,
            retiredScopes = retiredScopes,
            endedUseFloors = endedUseFloors,
            revision = revision,
            view = nextView
        )
        return removed
    }

    private fun mergeKrxLoss(
        a: Map<String, Set<KrxGraphStamp>>,
        b: Map<String, Set<KrxGraphStamp>>
    ): Map<String, Set<KrxGraphStamp>> = copyKrxGraphStamps((a.keys + b.keys).associateWith {
        a[it].orEmpty() + b[it].orEmpty()
    })

    private fun buildView(aggregates: Map<Key, Aggregate>, revision: Long): TopicGraphLoss? {
        if (aggregates.isEmpty()) return null
        val values = aggregates.values
        val first = values.minBy { it.firstSequence }
        val last = values.maxBy { it.lastSequence }
        return TopicGraphLoss(
            revision = revision,
            count = values.sumOf { it.count },
            firstSequence = first.firstSequence,
            lastSequence = last.lastSequence,
            reasons = values.flatMapTo(mutableSetOf()) { it.reasons },
            topics = values.flatMapTo(mutableSetOf()) { it.topics },
            paths = values.flatMapTo(mutableSetOf()) { it.paths },
            firstOccurredAtEpochMillis = first.firstAt,
            lastOccurredAtEpochMillis = last.lastAt,
            authorities = values.flatMapTo(mutableSetOf()) { it.authorities },
            krxLost = values.fold(emptyMap()) { originals, aggregate -> mergeKrxLoss(originals, aggregate.krxLost) }
        )
    }
}
