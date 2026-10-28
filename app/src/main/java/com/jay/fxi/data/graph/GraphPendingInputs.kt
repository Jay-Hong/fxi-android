package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.purge.KRX_MEMORY_KEY
import com.jay.fxi.data.remote.TopicGraphInput
import java.util.Collections

private const val GRAPH_PENDING_MAX_UNITS = 512

/** Adapter-neutral holding for one recorder scope; replay and loss recovery belong to the caller. */
internal class GraphPendingInputs private constructor(
    inputs: List<TopicGraphInput>,
    lostTopics: Set<String>,
    krxLost: Map<String, Set<KrxGraphStamp>> = emptyMap()
) {
    val inputs: List<TopicGraphInput> = Collections.unmodifiableList(inputs.toList())
    val lostTopics: Set<String> = Collections.unmodifiableSet(lostTopics.toSet())

    val krxLost: Map<String, Set<KrxGraphStamp>> = copyKrxGraphStamps(krxLost)

    fun purgeKrxMemory(plan: KrxGraphPurgePlan): Pair<GraphPendingInputs, KrxGraphPurgeOutcome> {
        var outcome = KrxGraphPurgeOutcome(false, false)
        val kept = inputs.mapNotNull { input ->
            val projection: KrxGraphInputProjection = projectKrxGraphInput(input, plan)
            outcome += projection.outcome
            projection.input
        }
        val losses = krxLost.mapValues { (_, originals) -> originals.filterTo(linkedSetOf()) {
            val decision = plan.classify(it, it.scope.owner.identity.uid)
            outcome += KrxGraphPurgeOutcome(decision == KrxGraphPurgeDecision.REMOVE,
                decision == KrxGraphPurgeDecision.UNRESOLVED)
            decision != KrxGraphPurgeDecision.REMOVE
        } }.filterValues { it.isNotEmpty() }
        val topics = lostTopics.filterTo(linkedSetOf()) {
            // No original sidecar exists for a legacy topic loss. Never fill it from a new use.
            if (isKrxGraphTopic(it)) outcome += KrxGraphPurgeOutcome(false,
                plan.classify(null, null) == KrxGraphPurgeDecision.UNRESOLVED)
            true
        }
        return (if (outcome.removed) GraphPendingInputs(kept, topics, losses) else this) to outcome
    }

    companion object {
        val EMPTY = GraphPendingInputs(emptyList(), emptySet())

        internal fun markLoss(pending: GraphPendingInputs, topics: Set<String>): GraphPendingInputs =
            if (topics.isEmpty()) pending else GraphPendingInputs(pending.inputs, pending.lostTopics + topics, pending.krxLost)

        internal fun markKrxLoss(pending: GraphPendingInputs, stamps: Map<String, Set<KrxGraphStamp>>): GraphPendingInputs {
            if (stamps.isEmpty()) return pending
            val lost = pending.krxLost.toMutableMap()
            for ((topic, originals) in stamps) lost[topic] = lost[topic].orEmpty() + originals
            return GraphPendingInputs(pending.inputs, pending.lostTopics, lost)
        }

        internal fun offer(pending: GraphPendingInputs, input: TopicGraphInput): GraphPendingInputs {
            val units = input.pendingUnits()
            if (units == 0) return pending

            var losses = pending
            fun lose(evicted: TopicGraphInput) {
                val krxLoss = evicted.krxGraphLoss()
                losses = markLoss(losses, evicted.pendingTopics() - krxLoss.keys)
                losses = markKrxLoss(losses, krxLoss)
            }
            if (units > GRAPH_PENDING_MAX_UNITS) {
                lose(input)
                return GraphPendingInputs(pending.inputs, losses.lostTopics, losses.krxLost)
            }

            var retainedUnits = pending.inputs.sumOf { it.pendingUnits() }
            var firstRetained = 0
            while (retainedUnits + units > GRAPH_PENDING_MAX_UNITS) {
                val evicted = pending.inputs[firstRetained++]
                retainedUnits -= evicted.pendingUnits()
                lose(evicted)
            }
            return GraphPendingInputs(pending.inputs.drop(firstRetained) + input, losses.lostTopics, losses.krxLost)
        }
    }
}

internal fun offerGraphPendingInput(pending: GraphPendingInputs, input: TopicGraphInput): GraphPendingInputs =
    GraphPendingInputs.offer(pending, input)

/** Record loss without retaining a refused input or changing the original holding. */
internal fun markGraphPendingLoss(pending: GraphPendingInputs, topics: Set<String>): GraphPendingInputs =
    GraphPendingInputs.markLoss(pending, topics)

internal fun TopicGraphInput.pendingUnits(): Int = when (this) {
    is TopicGraphInput.Observations -> candidates.size
    is TopicGraphInput.Continuity -> 1
}

/** Copy continuity topics when recording a loss, without replacing the original held input. */
private fun TopicGraphInput.pendingTopics(): Set<String> = when (this) {
    is TopicGraphInput.Observations -> setOf(topic)
    is TopicGraphInput.Continuity -> topics.toSet()
}

internal fun markGraphPendingKrxLoss(
    pending: GraphPendingInputs,
    stamps: Map<String, Set<KrxGraphStamp>>
): GraphPendingInputs = GraphPendingInputs.markKrxLoss(pending, stamps)

/** Split only the KRX side of a mixed batch; no authority query and no new source issuance. */
internal fun TopicGraphInput.krxGraphLoss(): Map<String, Set<KrxGraphStamp>> {
    val stamp = when (this) {
        is TopicGraphInput.Observations -> krxGraphStamp
        is TopicGraphInput.Continuity -> authority.krxGraphStamp
    } ?: return emptyMap()
    val owner = when (this) {
        is TopicGraphInput.Observations -> attribution.owner
        is TopicGraphInput.Continuity -> authority.owner
    }
    val lifetime = when (this) {
        is TopicGraphInput.Observations -> attribution.lifetime
        is TopicGraphInput.Continuity -> authority.lifetime
    }
    if (stamp.scope.owner != owner || stamp.scope.use.lifetime != lifetime) return emptyMap()
    val topics = when (this) {
        is TopicGraphInput.Observations -> setOf(topic)
        is TopicGraphInput.Continuity -> this.topics
    }
    return topics.filter(::isKrxGraphTopic).associateWith { setOf(stamp) }
}

/** The existing canonical source/asset pair, without widening the session-only topic predicate. */
internal fun isKrxGraphTopic(topic: String): Boolean =
    topic == "${KRX_MEMORY_KEY.source}:${KRX_MEMORY_KEY.asset}"
