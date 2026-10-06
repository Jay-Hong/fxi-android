package com.jay.fxi.data.graph

import com.jay.fxi.data.remote.TopicGraphInput
import java.util.Collections

private const val GRAPH_PENDING_MAX_UNITS = 512

/** Adapter-neutral holding for one recorder scope; replay and loss recovery belong to the caller. */
internal class GraphPendingInputs private constructor(
    inputs: List<TopicGraphInput>,
    lostTopics: Set<String>
) {
    val inputs: List<TopicGraphInput> = Collections.unmodifiableList(inputs.toList())
    val lostTopics: Set<String> = Collections.unmodifiableSet(lostTopics.toSet())

    companion object {
        val EMPTY = GraphPendingInputs(emptyList(), emptySet())

        internal fun markLoss(pending: GraphPendingInputs, topics: Set<String>): GraphPendingInputs =
            if (topics.isEmpty()) pending else GraphPendingInputs(pending.inputs, pending.lostTopics + topics)

        internal fun offer(pending: GraphPendingInputs, input: TopicGraphInput): GraphPendingInputs {
            val units = input.pendingUnits()
            if (units == 0) return pending

            val lostTopics = pending.lostTopics.toMutableSet()
            if (units > GRAPH_PENDING_MAX_UNITS) {
                lostTopics.addAll(input.pendingTopics())
                return GraphPendingInputs(pending.inputs, lostTopics)
            }

            var retainedUnits = pending.inputs.sumOf { it.pendingUnits() }
            var firstRetained = 0
            while (retainedUnits + units > GRAPH_PENDING_MAX_UNITS) {
                val evicted = pending.inputs[firstRetained++]
                retainedUnits -= evicted.pendingUnits()
                lostTopics.addAll(evicted.pendingTopics())
            }
            return GraphPendingInputs(pending.inputs.drop(firstRetained) + input, lostTopics)
        }
    }
}

internal fun offerGraphPendingInput(pending: GraphPendingInputs, input: TopicGraphInput): GraphPendingInputs =
    GraphPendingInputs.offer(pending, input)

/** Record loss without retaining a refused input or changing the original holding. */
internal fun markGraphPendingLoss(pending: GraphPendingInputs, topics: Set<String>): GraphPendingInputs =
    GraphPendingInputs.markLoss(pending, topics)

private fun TopicGraphInput.pendingUnits(): Int = when (this) {
    is TopicGraphInput.Observations -> candidates.size
    is TopicGraphInput.Continuity -> 1
}

/** Copy continuity topics when recording a loss, without replacing the original held input. */
private fun TopicGraphInput.pendingTopics(): Set<String> = when (this) {
    is TopicGraphInput.Observations -> setOf(topic)
    is TopicGraphInput.Continuity -> topics.toSet()
}
