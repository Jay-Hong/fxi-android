package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.time.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Synchronous owner of the F1 reducer's state for one user session data scope.
 *
 * The caller must serialize every method and the control collector on the same executor. The [gate]
 * and this owner must share the snapshot and current fence suppliers. Revisions only signal a fresh
 * supplier read; their values do not decide access.
 *
 * Mutations work before [start]. Each reads its suppliers before binding the original input or
 * consumer, then calls the reducer even if admission is refused, and publishes only the final state
 * before returning. Observe and response application each read [clock] once.
 * Both observe paths replay held inputs in order before the new input, using the same suppliers and
 * clock value. Only observations bind their original captures; continuity facts need no admission.
 *
 * [exposed] computes a candidate before the consumer's final bind. This ordering provides no
 * atomicity inside the gate or after it returns. [close] empties the state immediately, cancels only
 * this owner's collector, and permanently ends all use without cancelling the supplied [scope].
 *
 * Response callers must retain the original request, fence and lifetime. Binding a completion to
 * the recorder instance that issued it and a public catalog-adoption replay entry belong to F2d.
 * Session acceptance, external loss consumption, sending, timers and purge APIs belong to later slices.
 */
internal class GraphRecorder(
    private val scope: CoroutineScope,
    private val accessRevisions: StateFlow<Long>,
    private val accessSnapshot: () -> TopicAccessSnapshot,
    private val currentAccessFence: () -> TopicSessionFence?,
    private val currentCatalog: () -> GraphCatalog?,
    private val gate: GraphV2AccessGate,
    private val clock: AppClock
) {
    private val mutableState = MutableStateFlow(GraphRecorderReducer.empty())

    /** Change notifications, tests and purge inspection only; consumers read data through [exposed]. */
    val state: StateFlow<GraphRecorderState> = mutableState.asStateFlow()

    private var started = false
    private var closed = false
    private var collector: Job? = null

    /** Starts only the access control collector, at most once. */
    fun start() {
        if (closed || started) return
        started = true
        collector = scope.launch {
            accessRevisions.collect {
                if (!closed) {
                    val snapshot = accessSnapshot()
                    val fence = currentAccessFence()
                    val catalog = currentCatalog()
                    mutableState.value = replayPending(mutableState.value, catalog, snapshot, fence)
                }
            }
        }
    }

    fun observe(input: TopicGraphInput.Observations) {
        if (closed) return
        val snapshot = accessSnapshot()
        val fence = currentAccessFence()
        val catalog = currentCatalog()
        val now = clock.now()
        val next = replayPending(mutableState.value, catalog, snapshot, fence, now)
        val admission = gate.bind(input.attribution.owner, input.attribution.lifetime) != null
        mutableState.value = GraphRecorderReducer.observe(
            next, input, catalog, snapshot, fence, admission, now
        )
    }

    fun observe(input: TopicGraphInput.Continuity) {
        if (closed) return
        val snapshot = accessSnapshot()
        val fence = currentAccessFence()
        val catalog = currentCatalog()
        val now = clock.now()
        val next = replayPending(mutableState.value, catalog, snapshot, fence, now)
        mutableState.value = GraphRecorderReducer.continuity(next, input, catalog, snapshot, fence, now)
    }

    /** Sync and replay remain local until the caller publishes its final transition. */
    private fun replayPending(
        state: GraphRecorderState,
        catalog: GraphCatalog?,
        snapshot: TopicAccessSnapshot,
        fence: TopicSessionFence?,
        now: Instant? = null
    ): GraphRecorderState {
        val synced = GraphRecorderReducer.syncAccess(state, snapshot, fence)
        val currentScope = fence?.userAccessEpoch?.let { GraphDataScope(fence.identity.uid, it) }
        if (snapshot.revision < state.seenRevision || catalog == null || currentScope == null ||
            currentScope != synced.scope || (synced.pending.inputs.isEmpty() && synced.pending.lostTopics.isEmpty())
        ) return synced
        val replayNow = now ?: clock.now()
        val admissions = synced.pending.inputs.filterIsInstance<TopicGraphInput.Observations>().map {
            gate.bind(it.attribution.owner, it.attribution.lifetime) != null
        }
        return GraphRecorderReducer.replayPending(synced, catalog, snapshot, fence, admissions, replayNow)
    }

    fun captureRequests(
        keys: Set<GraphObservationSeriesKey>,
        fence: TopicSessionFence,
        lifetime: TopicUseLifetime
    ): List<GraphRecoveryRequest> {
        if (closed) return emptyList()
        val snapshot = accessSnapshot()
        val currentFence = currentAccessFence()
        val admission = gate.bind(fence, lifetime) != null
        val (next, requests) = GraphRecorderReducer.captureRequests(
            mutableState.value, keys, snapshot, currentFence, admission
        )
        mutableState.value = next
        return requests
    }

    fun applyResponse(
        request: GraphRecoveryRequest,
        tab: GraphV2Tab,
        fence: TopicSessionFence,
        lifetime: TopicUseLifetime
    ) {
        if (closed) return
        val snapshot = accessSnapshot()
        val currentFence = currentAccessFence()
        val now = clock.now()
        val admission = gate.bind(fence, lifetime) != null
        mutableState.value = GraphRecorderReducer.applyResponse(
            mutableState.value, request, tab, snapshot, currentFence, admission, now
        )
    }

    fun exposed(
        fence: TopicSessionFence,
        lifetime: TopicUseLifetime
    ): Map<GraphObservationSeriesKey, GraphRecoverableState> {
        if (closed) return emptyMap()
        val snapshot = accessSnapshot()
        val currentFence = currentAccessFence()
        val candidate = GraphRecorderReducer.exposed(mutableState.value, snapshot, currentFence, admission = true)
        return if (gate.bind(fence, lifetime) != null) candidate else emptyMap()
    }

    fun close() {
        if (closed) return
        closed = true
        collector?.cancel()
        collector = null
        mutableState.value = GraphRecorderReducer.empty()
    }
}
