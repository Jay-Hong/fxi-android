package com.jay.fxi.data.graph

import com.jay.fxi.domain.model.FreeGraphPoint
import java.util.Collections
import kotlinx.datetime.Instant

internal data class GraphBucketRange(val high: Double, val low: Double)

internal sealed interface GraphServerBucket {
    data class Seeds(val range: GraphBucketRange, val close: Double) : GraphServerBucket

    data class Closed(val point: FreeGraphPoint) : GraphServerBucket
}

/** Server records stay apart from D1 observations; composition never feeds back into either. */
internal class GraphSeededObservationState private constructor(
    val app: GraphObservationState,
    serverBuckets: Map<Instant, GraphServerBucket>
) {
    val serverBuckets: Map<Instant, GraphServerBucket> = Collections.unmodifiableMap(serverBuckets.toMap())

    companion object {
        fun empty(seriesKey: GraphObservationSeriesKey, order: GraphObservationOrder): GraphSeededObservationState =
            GraphSeededObservationState(GraphObservationState.empty(seriesKey, order), emptyMap())

        internal fun updated(
            app: GraphObservationState,
            serverBuckets: Map<Instant, GraphServerBucket>
        ): GraphSeededObservationState = GraphSeededObservationState(app, serverBuckets)
    }
}

internal sealed interface GraphComposedBucket {
    data class Unreplaced(val start: Instant, val range: GraphBucketRange, val close: Double) : GraphComposedBucket

    data class Closed(val point: FreeGraphPoint) : GraphComposedBucket
}

internal data class GraphSeededObservationReduction(
    val state: GraphSeededObservationState,
    val outcomes: List<GraphObservationOutcome>
)
