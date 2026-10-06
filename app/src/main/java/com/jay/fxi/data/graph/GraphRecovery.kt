package com.jay.fxi.data.graph

import java.util.Collections
import kotlinx.datetime.Instant

internal enum class GraphRecoveryReason { INITIAL_SYNC, RECEIVE_GAP, HANDOVER_LOSS, TIME_ANOMALY }

internal data class GraphRecoveryDemand(val generation: Long, val reasons: Set<GraphRecoveryReason>)

/** Recovery bookkeeping stays separate from server records and app observations. */
internal class GraphRecoverableState private constructor(
    val data: GraphSeededObservationState,
    pending: Map<Instant, GraphRecoveryDemand>,
    gapHistory: Set<Instant>,
    val generation: Long,
    val lastAppliedVersion: Long?,
    val lastCutoff: Instant?
) {
    val pending: Map<Instant, GraphRecoveryDemand> = Collections.unmodifiableMap(
        pending.mapValues { (_, demand) ->
            demand.copy(reasons = Collections.unmodifiableSet(demand.reasons.toSet()))
        }
    )
    val gapHistory: Set<Instant> = Collections.unmodifiableSet(gapHistory.toSet())

    companion object {
        fun empty(seriesKey: GraphObservationSeriesKey, order: GraphObservationOrder): GraphRecoverableState =
            GraphRecoverableState(GraphSeededObservationState.empty(seriesKey, order), emptyMap(), emptySet(), 0, null, null)

        internal fun updated(
            data: GraphSeededObservationState,
            pending: Map<Instant, GraphRecoveryDemand>,
            gapHistory: Set<Instant>,
            generation: Long,
            lastAppliedVersion: Long?,
            lastCutoff: Instant
        ): GraphRecoverableState =
            GraphRecoverableState(data, pending, gapHistory, generation, lastAppliedVersion, lastCutoff)
    }
}

internal data class GraphRecoveryRequest(
    val seriesKey: GraphObservationSeriesKey,
    val applicationVersion: Long,
    val capturedGeneration: Long
)

internal data class GraphRecoverableReduction(val state: GraphRecoverableState, val outcomes: List<GraphObservationOutcome>)
