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

/**
 * S4 RT03b-1a: whether the recorder holds a closed-bucket demand of the retained window, or a mapping or handover wait, for
 * one tab's 1d series, read without any change. Demands on the current or a later bucket are not reported.
 */
internal sealed interface GraphTabRecoveryDemand {
    /** Access cannot be decided or is not usable for this fence and lifetime; says nothing about completion. */
    data object Unreadable : GraphTabRecoveryDemand

    /** The tab's 1d series cannot be resolved from the supplied catalog; says nothing about completion. */
    data object CatalogRequired : GraphTabRecoveryDemand

    /**
     * No closed demand in the retained window and no mapping or handover wait for the tab's 1d series. Demands on the current
     * or a later bucket may still be held, and a capture still requests them.
     */
    data object None : GraphTabRecoveryDemand

    /** At least one of [closed] and [mappingWait] is true. */
    data class Pending(val closed: Boolean, val mappingWait: Boolean) : GraphTabRecoveryDemand {
        init {
            require(closed || mappingWait) { "Pending needs a closed demand or a mapping wait" }
        }
    }
}
