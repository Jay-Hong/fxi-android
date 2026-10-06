package com.jay.fxi.data.graph

import com.jay.fxi.domain.model.RateSanity
import kotlinx.datetime.Instant

/** A different price for this identity is a conflict, not a duplicate. */
private data class ObservationTimeKey(val source: String, val asset: String, val observedAt: Instant)

private fun GraphObservationId.timeKey(): ObservationTimeKey = ObservationTimeKey(source, asset, observedAt)

/** Strict comparison preserves the first adopted observation when time and rank both tie. */
private fun GraphObservationId.precedes(candidate: GraphObservationId, order: GraphObservationOrder): Boolean =
    candidate.observedAt > observedAt ||
        (candidate.observedAt == observedAt && order.rank(candidate.source) < order.rank(source))

/** Pure reduction: no receive time, clock, seed, adapter, I/O or publication side effects. */
internal fun reduceGraphObservations(
    state: GraphObservationState,
    observations: List<GraphObservation>
): GraphObservationReduction {
    val accepted = state.observations.toMutableSet()
    val times = accepted.mapTo(mutableSetOf()) { it.timeKey() }
    val buckets = state.buckets.toMutableMap()
    var tip = state.tip
    val outcomes = observations.map { observation ->
        val id = observation.id
        when {
            observation.seriesKey != state.seriesKey || !RateSanity.isPlausible(id.rate) ->
                GraphObservationOutcome.REJECTED
            id in accepted -> GraphObservationOutcome.DUPLICATE
            else -> {
                val conflict = !times.add(id.timeKey())
                accepted.add(id)
                val start = graphObservationBucketStart(id.observedAt)
                val bucket = buckets[start]
                buckets[start] = if (bucket == null) {
                    GraphObservedBucket(start, id, id, id)
                } else {
                    GraphObservedBucket(
                        start,
                        if (id.rate > bucket.high.rate) id else bucket.high,
                        if (id.rate < bucket.low.rate) id else bucket.low,
                        if (bucket.close.precedes(id, state.order)) id else bucket.close
                    )
                }
                if (tip == null || tip.precedes(id, state.order)) tip = id
                if (conflict) GraphObservationOutcome.SAME_TIME_CONFLICT else GraphObservationOutcome.ADDED
            }
        }
    }

    val next = GraphObservationState.updated(state, buckets, tip, accepted)
    return GraphObservationReduction(next, outcomes)
}
