package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicGraphEventKind
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
    abstract val untransferredSeries: Set<String>
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
        untransferredSeries: Set<String>,
        override val seenUserEnd: Long,
        override val seenRevision: Long,
        override val nextVersion: Long,
        override val versionFloor: Long
    ) : GraphRecorderState() {
        override val series: Map<GraphObservationSeriesKey, GraphRecoverableState> =
            Collections.unmodifiableMap(series.toMap())
        override val untransferredSeries: Set<String> = Collections.unmodifiableSet(untransferredSeries.toSet())
    }

    fun empty(): GraphRecorderState = State(null, emptyMap(), GraphPendingInputs.EMPTY, emptySet(), 0L, 0L, 0L, 0L)

    private fun updated(
        state: GraphRecorderState,
        scope: GraphDataScope? = state.scope,
        series: Map<GraphObservationSeriesKey, GraphRecoverableState> = state.series,
        pending: GraphPendingInputs = state.pending,
        untransferredSeries: Set<String> = state.untransferredSeries,
        seenUserEnd: Long = state.seenUserEnd,
        seenRevision: Long = state.seenRevision,
        nextVersion: Long = state.nextVersion,
        versionFloor: Long = state.versionFloor
    ): GraphRecorderState = State(
        scope, series, pending, untransferredSeries, seenUserEnd, seenRevision, nextVersion, versionFloor
    )

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
            untransferredSeries = if (discard) emptySet() else state.untransferredSeries,
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

    private fun seriesIds(topics: Set<String>, catalog: GraphCatalog): Set<String> = topics.flatMapTo(linkedSetOf()) {
        fxGraphSeriesIds(it, catalog) + dxyGraphSeriesIds(it, catalog)
    }

    /** Held inputs must finish before their time-free losses are handed over. */
    private fun transferPendingLoss(
        state: GraphRecorderState,
        catalog: GraphCatalog,
        now: Instant
    ): GraphRecorderState {
        if (state.pending.inputs.isNotEmpty() || state.pending.lostTopics.isEmpty()) return state
        val scope = state.scope ?: return state
        val series = state.series.toMutableMap()
        val untransferred = state.untransferredSeries.toMutableSet()
        for (id in seriesIds(state.pending.lostTopics, catalog)) {
            val key = GraphObservationSeriesKey(scope, id)
            val previous = series[key]
            if (previous == null) {
                untransferred.add(id)
            } else {
                series[key] = requireGraphRecoveryWindow(previous, GraphRecoveryReason.HANDOVER_LOSS, now)
            }
        }
        return updated(state, series = series, pending = GraphPendingInputs.EMPTY, untransferredSeries = untransferred)
    }

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

        val ready = transferPendingLoss(synced, catalog, now)
        val observations = fxGraphObservations(scope, input, catalog) + dxyGraphObservations(scope, input, catalog)
        val series = ready.series.toMutableMap()
        val untransferred = ready.untransferredSeries.toMutableSet()
        for ((key, batch) in observations.groupBy { it.seriesKey }) {
            val previous = series[key]
            if (allowed) {
                val initial = previous ?: requireGraphRecovery(
                    GraphRecoverableState.empty(key, if (key.seriesId == "dxy") DXY_GRAPH_OBSERVATION_ORDER else fxOrder),
                    now, now, GraphRecoveryReason.INITIAL_SYNC, now
                )
                val observed = observeRecoverable(initial, batch, now).state
                series[key] = if (previous == null && untransferred.remove(key.seriesId)) {
                    requireGraphRecoveryWindow(observed, GraphRecoveryReason.HANDOVER_LOSS, now)
                } else observed
            } else if (previous != null) {
                // One loss span per series gives all affected buckets the same new D3 generation.
                series[key] = requireGraphRecovery(
                    previous, batch.minOf { it.id.observedAt }, batch.maxOf { it.id.observedAt },
                    GraphRecoveryReason.HANDOVER_LOSS, now
                )
            }
        }
        return updated(ready, series = series, untransferredSeries = untransferred)
    }

    fun continuity(
        state: GraphRecorderState,
        input: TopicGraphInput.Continuity,
        catalog: GraphCatalog?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        now: Instant
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision) return synced
        val ready = if (catalog != null && scopeContinues(synced, fence)) transferPendingLoss(synced, catalog, now)
        else synced
        val scope = ready.scope ?: return ready
        if (input.kind != TopicGraphEventKind.DELIVERY_RESUMED || scopeOf(input.authority.owner) != scope) return ready
        if (catalog == null || !scopeContinues(ready, fence) || ready.pending.inputs.isNotEmpty()) {
            return updated(ready, pending = offerGraphPendingInput(ready.pending, input))
        }

        val series = ready.series.toMutableMap()
        for (id in seriesIds(input.topics, catalog)) {
            val key = GraphObservationSeriesKey(scope, id)
            val previous = series[key] ?: continue
            series[key] = requireGraphRecoveryWindow(previous, GraphRecoveryReason.RECEIVE_GAP, now)
        }
        return updated(ready, series = series)
    }

    /** Admissions correspond only to the Observations subsequence of the original holding. */
    fun replayPending(
        state: GraphRecorderState,
        catalog: GraphCatalog,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        observationAdmissions: List<Boolean>,
        now: Instant
    ): GraphRecorderState {
        val synced = syncAccess(state, snapshot, fence)
        if (snapshot.revision < state.seenRevision || !scopeContinues(synced, fence)) return synced
        if (synced.pending.inputs.isEmpty() && synced.pending.lostTopics.isEmpty()) return synced
        val inputs = synced.pending.inputs
        require(observationAdmissions.size == inputs.count { it is TopicGraphInput.Observations })
        val lostTopics = synced.pending.lostTopics
        var next = updated(synced, pending = GraphPendingInputs.EMPTY)
        var observationIndex = 0
        for (input in inputs) {
            next = when (input) {
                is TopicGraphInput.Observations -> observe(
                    next, input, catalog, snapshot, fence, observationAdmissions[observationIndex++], now
                )
                is TopicGraphInput.Continuity -> continuity(next, input, catalog, snapshot, fence, now)
            }
        }
        return transferPendingLoss(updated(next, pending = markGraphPendingLoss(next.pending, lostTopics)), catalog, now)
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
