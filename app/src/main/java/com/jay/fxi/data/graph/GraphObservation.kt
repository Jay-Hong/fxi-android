package com.jay.fxi.data.graph

import java.util.Collections
import kotlinx.datetime.Instant

/** A data namespace, not proof that the caller currently has access to it. */
internal data class GraphObservationSeriesKey(
    val scope: GraphDataScope,
    val seriesId: String
)

internal data class GraphObservationId(
    val source: String,
    val asset: String,
    val observedAt: Instant,
    val rate: Double
)

internal data class GraphObservation(
    val seriesKey: GraphObservationSeriesKey,
    val id: GraphObservationId
)

/** Earlier listed sources win a time tie; all unlisted sources share the last rank. */
internal class GraphObservationOrder(sourcePriority: List<String>) {
    val sourcePriority: List<String> = Collections.unmodifiableList(sourcePriority.toList())

    internal fun rank(source: String): Int = sourcePriority.indexOf(source).let {
        if (it < 0) sourcePriority.size else it
    }
}

/** Each extreme and close retains the actual observation that established it. */
internal data class GraphObservedBucket(
    val start: Instant,
    val high: GraphObservationId,
    val low: GraphObservationId,
    val close: GraphObservationId
)

/** App observations only. Seeds, retention limits and recovery belong to later units. */
internal class GraphObservationState private constructor(
    val seriesKey: GraphObservationSeriesKey,
    val order: GraphObservationOrder,
    buckets: Map<Instant, GraphObservedBucket>,
    val tip: GraphObservationId?,
    observations: Set<GraphObservationId>
) {
    val buckets: Map<Instant, GraphObservedBucket> = Collections.unmodifiableMap(buckets.toMap())
    val observations: Set<GraphObservationId> = Collections.unmodifiableSet(observations.toSet())

    companion object {
        fun empty(seriesKey: GraphObservationSeriesKey, order: GraphObservationOrder): GraphObservationState =
            GraphObservationState(seriesKey, order, emptyMap(), null, emptySet())

        internal fun updated(
            previous: GraphObservationState,
            buckets: Map<Instant, GraphObservedBucket>,
            tip: GraphObservationId?,
            observations: Set<GraphObservationId>
        ): GraphObservationState = GraphObservationState(previous.seriesKey, previous.order, buckets, tip, observations)
    }
}

internal enum class GraphObservationOutcome {
    ADDED,
    DUPLICATE,
    SAME_TIME_CONFLICT,
    REJECTED
}

internal data class GraphObservationReduction(
    val state: GraphObservationState,
    val outcomes: List<GraphObservationOutcome>
)

private const val OBSERVATION_BUCKET_SECONDS = 600L

/** epochSeconds already floors subsecond Instants, including those before epoch zero. */
internal fun graphObservationBucketStart(observedAt: Instant): Instant =
    Instant.fromEpochSeconds(Math.floorDiv(observedAt.epochSeconds, OBSERVATION_BUCKET_SECONDS) * OBSERVATION_BUCKET_SECONDS)
