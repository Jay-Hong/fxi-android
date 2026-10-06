package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphV2Tab
import java.util.Collections
import kotlinx.datetime.Instant

/** Immutable recorder data; the reducer alone constructs its private implementation. */
internal sealed class GraphRecorderState {
    abstract val scope: GraphDataScope?
    abstract val series: Map<GraphObservationSeriesKey, GraphRecoverableState>
    abstract val pending: GraphPendingInputs
    abstract val seenUserEnd: Long
    abstract val seenRevision: Long
    abstract val nextVersion: Long
    abstract val versionFloor: Long
}

/** Pure access transitions and D3 delegation; admission belongs to each input's or consumer's GENERAL capture. */
internal object GraphRecorderReducer {
    private val fxOrder = GraphObservationOrder(emptyList())

    private class State(
        override val scope: GraphDataScope?,
        series: Map<GraphObservationSeriesKey, GraphRecoverableState>,
        override val pending: GraphPendingInputs,
        override val seenUserEnd: Long,
        override val seenRevision: Long,
        override val nextVersion: Long,
        override val versionFloor: Long
    ) : GraphRecorderState() {
        override val series: Map<GraphObservationSeriesKey, GraphRecoverableState> =
            Collections.unmodifiableMap(series.toMap())
    }

    fun empty(): GraphRecorderState = State(null, emptyMap(), GraphPendingInputs.EMPTY, 0L, 0L, 0L, 0L)

    private fun updated(
        state: GraphRecorderState,
        scope: GraphDataScope? = state.scope,
        series: Map<GraphObservationSeriesKey, GraphRecoverableState> = state.series,
        pending: GraphPendingInputs = state.pending,
        seenUserEnd: Long = state.seenUserEnd,
        seenRevision: Long = state.seenRevision,
        nextVersion: Long = state.nextVersion,
        versionFloor: Long = state.versionFloor
    ): GraphRecorderState = State(scope, series, pending, seenUserEnd, seenRevision, nextVersion, versionFloor)

    private fun scopeOf(fence: TopicSessionFence?): GraphDataScope? = fence?.let {
        it.userAccessEpoch?.let { epoch -> GraphDataScope(it.identity.uid, epoch) }
    }

    fun syncAccess(
        state: GraphRecorderState,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?
    ): GraphRecorderState {
        if (snapshot.revision < state.seenRevision) return state
        val currentScope = scopeOf(fence)
        val userEnd = snapshot.lastUserEnd?.sequence ?: 0L
        val discard = userEnd > state.seenUserEnd || (currentScope != null && currentScope != state.scope)
        return updated(
            state,
            scope = currentScope ?: state.scope,
            series = if (discard) emptyMap() else state.series,
            pending = if (discard) GraphPendingInputs.EMPTY else state.pending,
            seenUserEnd = maxOf(state.seenUserEnd, userEnd),
            seenRevision = snapshot.revision,
            versionFloor = if (discard) state.nextVersion else state.versionFloor
        )
    }

    private fun scopeContinues(state: GraphRecorderState, fence: TopicSessionFence?): Boolean =
        state.scope != null && scopeOf(fence) == state.scope

    private fun usable(
        state: GraphRecorderState,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean
    ): Boolean = snapshot.revision >= state.seenRevision &&
        (snapshot.lastUserEnd?.sequence ?: 0L) == state.seenUserEnd &&
        scopeContinues(state, fence) && snapshot.facts.userAllowed && admission

    fun observe(
        state: GraphRecorderState,
        input: TopicGraphInput.Observations,
        catalog: GraphCatalog?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean,
        now: Instant
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision || !scopeContinues(synced, fence)) return synced
        val scope = synced.scope ?: return synced
        // Attribution precedes admission and loss handling: a foreign input belongs to neither path here.
        if (scopeOf(input.attribution.owner) != scope) return synced
        val allowed = usable(synced, snapshot, fence, admission)
        if (catalog == null) {
            val pending = if (allowed) offerGraphPendingInput(synced.pending, input)
            else markGraphPendingLoss(synced.pending, setOf(input.topic))
            return updated(synced, pending = pending)
        }

        val observations = fxGraphObservations(scope, input, catalog) + dxyGraphObservations(scope, input, catalog)
        val series = synced.series.toMutableMap()
        for ((key, batch) in observations.groupBy { it.seriesKey }) {
            val previous = series[key]
            if (allowed) {
                val initial = previous ?: requireGraphRecovery(
                    GraphRecoverableState.empty(key, if (key.seriesId == "dxy") DXY_GRAPH_OBSERVATION_ORDER else fxOrder),
                    now, now, GraphRecoveryReason.INITIAL_SYNC, now
                )
                series[key] = observeRecoverable(initial, batch, now).state
            } else if (previous != null) {
                // One loss span per series gives all affected buckets the same new D3 generation.
                series[key] = requireGraphRecovery(
                    previous, batch.minOf { it.id.observedAt }, batch.maxOf { it.id.observedAt },
                    GraphRecoveryReason.HANDOVER_LOSS, now
                )
            }
        }
        return updated(synced, series = series)
    }

    fun offerPending(
        state: GraphRecorderState,
        input: TopicGraphInput,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision || !scopeContinues(synced, fence)) return synced
        val pending = if (usable(synced, snapshot, fence, admission)) offerGraphPendingInput(synced.pending, input)
        else markGraphPendingLoss(synced.pending, when (input) {
            is TopicGraphInput.Observations -> setOf(input.topic)
            is TopicGraphInput.Continuity -> input.topics
        })
        return updated(synced, pending = pending)
    }

    fun captureRequests(
        state: GraphRecorderState,
        keys: Set<GraphObservationSeriesKey>,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean
    ): Pair<GraphRecorderState, List<GraphRecoveryRequest>> {
        val synced = syncAccess(state, snapshot, fence)
        if (!usable(synced, snapshot, fence, admission)) {
            return synced to emptyList()
        }
        var nextVersion = synced.nextVersion
        val requests = mutableListOf<GraphRecoveryRequest>()
        for (key in keys) {
            val series = synced.series[key] ?: continue
            if (series.pending.isEmpty()) continue
            nextVersion = Math.incrementExact(nextVersion)
            requests.add(captureGraphRecoveryRequest(series, nextVersion))
        }
        return updated(synced, nextVersion = nextVersion) to Collections.unmodifiableList(requests)
    }

    fun applyResponse(
        state: GraphRecorderState,
        request: GraphRecoveryRequest,
        tab: GraphV2Tab,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean,
        now: Instant
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (!usable(synced, snapshot, fence, admission) || request.applicationVersion <= synced.versionFloor) return synced
        val scope = synced.scope ?: return synced
        val previous = synced.series[request.seriesKey] ?: return synced
        val applied = applyGraphRecoveryResponse(previous, scope, request, tab, now)
        return updated(synced, series = synced.series + (request.seriesKey to applied))
    }

    fun exposed(
        state: GraphRecorderState,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        admission: Boolean
    ): Map<GraphObservationSeriesKey, GraphRecoverableState> =
        if (usable(state, snapshot, fence, admission)) state.series else emptyMap()
}
