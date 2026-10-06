package com.jay.fxi.data.graph

import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2Tab
import kotlinx.datetime.Instant

/** The same union rule stores repeated seeds and composes server/app ranges. */
private fun unionGraphBucketRanges(existing: GraphBucketRange?, incoming: GraphBucketRange): GraphBucketRange =
    if (existing == null) incoming else GraphBucketRange(
        high = maxOf(existing.high, incoming.high),
        low = minOf(existing.low, incoming.low)
    )

/**
 * Applies an admitted tab in call order. The caller owns access and response-version checks.
 * Server points are closed records; only seed folding uses the device clock.
 */
internal fun applyGraphServerTab(
    state: GraphSeededObservationState,
    scope: GraphDataScope,
    tab: GraphV2Tab,
    now: Instant
): GraphSeededObservationState {
    if (scope != state.app.seriesKey.scope || tab.period != GraphPeriod.ONE_DAY || tab.bucketSize != "10min") {
        return state
    }
    val seriesId = state.app.seriesKey.seriesId
    val series = tab.graph.series.firstOrNull { it.seriesId == seriesId } ?: return state
    val buckets = state.serverBuckets.toMutableMap()
    for (point in series.points) {
        if (graphObservationBucketStart(point.timestamp) == point.timestamp) {
            buckets[point.timestamp] = GraphServerBucket.Closed(point)
        }
    }
    val seed = tab.inProgress[seriesId]
    if (seed != null && seed.bucketStart == graphObservationBucketStart(now)) {
        val existing = buckets[seed.bucketStart]
        // A supplied closed point wins over a seed, including after a device-clock reversal.
        if (existing !is GraphServerBucket.Closed) {
            buckets[seed.bucketStart] = GraphServerBucket.Seeds(
                range = unionGraphBucketRanges(
                    (existing as? GraphServerBucket.Seeds)?.range,
                    GraphBucketRange(seed.high, seed.low)
                ),
                close = seed.close
            )
        }
    }
    return GraphSeededObservationState.updated(state.app, buckets)
}

/** Late observations still reach D1; closed records take precedence only at composition. */
internal fun reduceSeededGraphObservations(
    state: GraphSeededObservationState,
    observations: List<GraphObservation>
): GraphSeededObservationReduction {
    val reduction = reduceGraphObservations(state.app, observations)
    return GraphSeededObservationReduction(
        GraphSeededObservationState.updated(reduction.state, state.serverBuckets),
        reduction.outcomes
    )
}

/** No clock: passing a boundary cannot close or discard an unreplaced bucket. */
internal fun composeGraphBucket(state: GraphSeededObservationState, start: Instant): GraphComposedBucket? {
    val server = state.serverBuckets[start]
    if (server is GraphServerBucket.Closed) return GraphComposedBucket.Closed(server.point)
    val seed = server as? GraphServerBucket.Seeds
    val app = state.app.buckets[start]
    if (app != null) {
        return GraphComposedBucket.Unreplaced(
            start,
            unionGraphBucketRanges(seed?.range, GraphBucketRange(app.high.rate, app.low.rate)),
            app.close.rate
        )
    }
    return seed?.let { GraphComposedBucket.Unreplaced(start, it.range, it.close) }
}
