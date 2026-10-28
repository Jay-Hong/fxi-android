package com.jay.fxi.data.graph

import com.jay.fxi.data.entitlements.PurgeNamespace
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

internal enum class GraphRecorderPurge { REMOVED, NOTHING_TO_REMOVE, LIVE_SCOPE_SELECTED }

/**
 * Synchronous process-lifetime owner of the graph recorder reducer's state. The reducer discards
 * session data on user ends and data scope changes.
 *
 * The caller must serialize every method and the control collector on the same executor. The [gate]
 * and this owner must share the snapshot and current fence suppliers. Revisions only signal a fresh
 * supplier read; their values do not decide access.
 *
 * Mutations work before [start]. Each reads its suppliers before binding the original input or
 * consumer, then calls the reducer even if admission is refused, and publishes only the final state
 * before returning. Observe, response application and [loseTopics] each read [clock] once.
 * [retain] reads only the clock, once, and syncs nothing.
 * Both observe paths and [loseTopics] replay held inputs in order before the new input or loss,
 * using the same suppliers and clock value. Only observations bind their original captures;
 * GENERAL continuity facts need no admission. KRX observations, facts and losses retain their original
 * source stamp and must pass the live original-use/cutoff gate at consumption.
 *
 * An observation from the held data scope dropped for a missing current scope records a loss
 * without adopting prices. Earlier pending losses wait until that scope is current again. The
 * snapshot's user-end invalidation floor excludes pre-end observation losses, resumes and topic
 * losses; equality passes. Price adoption relies on the original capture's admission. [loseTopics]
 * takes each topic's maximum original lifetime invalidation count, preserving its owner scope.
 *
 * [exposed] computes a candidate before the consumer's final bind. This ordering provides no
 * atomicity inside the gate or after it returns. [close] empties the state immediately, cancels only
 * this owner's collector, and permanently ends all use without cancelling the supplied [scope].
 *
 * Response callers must retain the original request, fence and lifetime and apply the completion
 * to the recorder instance that issued it. After publishing an adopted catalog, callers use
 * [replayPending] to replay held inputs with the current suppliers. Sending and timers belong to later slices.
 *
 * [purge] deletes selected held data without closing the recorder or deciding access. It reads only
 * the current fence, once and before any change, and refuses a live selected scope untouched.
 * Apart from LIVE_SCOPE_SELECTED, which reflects only the fence, its result describes data held
 * before the call; after [close], it reads nothing.
 */
internal class GraphRecorder(
    private val scope: CoroutineScope,
    private val accessRevisions: StateFlow<Long>,
    private val accessSnapshot: () -> TopicAccessSnapshot,
    private val currentAccessFence: () -> TopicSessionFence?,
    private val currentCatalog: () -> GraphCatalog?,
    private val gate: GraphV2AccessGate,
    private val clock: AppClock
) : GraphTopicInputConsumer {
    private val mutableState = MutableStateFlow(GraphRecorderReducer.empty())

    /** Change notifications, tests and purge inspection only; consumers read data through [exposed]. */
    val state: StateFlow<GraphRecorderState> = mutableState.asStateFlow()

    private var started = false
    private var closed = false
    private var collector: Job? = null
    private var purgeSeal = KrxGraphPurgeSeal.EMPTY

    /** Starts only the access control collector, at most once. */
    fun start() {
        if (closed || started) return
        started = true
        collector = scope.launch {
            accessRevisions.collect {
                replayPending()
            }
        }
    }

    /** Replays held inputs using current suppliers and publishes once; after [close], reads nothing. */
    fun replayPending() {
        if (closed) return
        val snapshot = accessSnapshot()
        val fence = currentAccessFence()
        val catalog = currentCatalog()
        mutableState.value = replayPending(mutableState.value, catalog, snapshot, fence)
    }

    private fun admitsKrx(stamp: KrxGraphStamp): Boolean =
        gate.bindKrxGraph(stamp.scope.owner, stamp.scope.use.lifetime, stamp) != null

    private fun observationAdmission(input: TopicGraphInput.Observations): Boolean =
        if (isKrxGraphTopic(input.topic)) input.krxGraphStamp?.let {
            gate.bindKrxGraph(input.attribution.owner, input.attribution.lifetime, it) != null
        } == true
        else gate.bind(input.attribution.owner, input.attribution.lifetime) != null

    override fun observe(input: TopicGraphInput.Observations) {
        if (closed) return
        val snapshot = accessSnapshot()
        val fence = currentAccessFence()
        val catalog = currentCatalog()
        val now = clock.now()
        val next = replayPending(mutableState.value, catalog, snapshot, fence, now)
        val admission = observationAdmission(input)
        mutableState.value = GraphRecorderReducer.observe(
            next, input, catalog, snapshot, fence, admission, now, ::admitsKrx
        )
    }

    override fun observe(input: TopicGraphInput.Continuity) {
        if (closed) return
        val snapshot = accessSnapshot()
        val fence = currentAccessFence()
        val catalog = currentCatalog()
        val now = clock.now()
        val next = replayPending(mutableState.value, catalog, snapshot, fence, now)
        val projection: KrxGraphInputProjection = projectKrxGraphInput(input, purgeSeal)
        val retained = projection.input as TopicGraphInput.Continuity?
        mutableState.value = if (retained == null) next else
            GraphRecorderReducer.continuity(next, retained, catalog, snapshot, fence, now, ::admitsKrx)
    }

    /**
     * Replays held inputs before recording topic losses under the same supplier reads and clock
     * value, then publishes once. The reducer checks the owner scope and user-end invalidation floor.
     * After [close], no suppliers are read and no work is performed.
     */
    override fun loseTopics(ownerScope: GraphDataScope, maxInvalidationsByTopic: Map<String, Long>) {
        if (closed) return
        val snapshot = accessSnapshot()
        val fence = currentAccessFence()
        val catalog = currentCatalog()
        val now = clock.now()
        val next = replayPending(mutableState.value, catalog, snapshot, fence, now)
        mutableState.value = GraphRecorderReducer.loseTopics(
            next, ownerScope, maxInvalidationsByTopic, catalog, snapshot, fence, now, ::admitsKrx
        )
    }

    override fun loseKrxTopics(stampsByTopic: Map<String, Set<KrxGraphStamp>>) {
        if (closed) return
        val snapshot = accessSnapshot()
        val fence = currentAccessFence()
        val catalog = currentCatalog()
        val now = clock.now()
        val next = replayPending(mutableState.value, catalog, snapshot, fence, now)
        mutableState.value = GraphRecorderReducer.loseKrxTopics(
            next, stampsByTopic, catalog, snapshot, fence, now, ::admitsKrx
        )
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
            currentScope != synced.scope || (synced.pending.inputs.isEmpty() && synced.pending.lostTopics.isEmpty() && synced.pending.krxLost.isEmpty())
        ) return synced
        val replayNow = now ?: clock.now()
        val admissions = synced.pending.inputs.filterIsInstance<TopicGraphInput.Observations>().map {
            observationAdmission(it)
        }
        return GraphRecorderReducer.replayPending(synced, catalog, snapshot, fence, admissions, replayNow, ::admitsKrx)
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
            mutableState.value, keys, snapshot, currentFence, admission, ::admitsKrx
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
            mutableState.value, request, tab, snapshot, currentFence, admission, now, ::admitsKrx
        )
    }

    /**
     * S4 RT03b-1a: the closed-bucket demands and mapping or handover waits held for [tab]'s 1d series (current- and
     * later-bucket demands are not reported), judged on the current suppliers with admission from [fence] and [lifetime],
     * and never stored: no replay, retention, version or publication. A closed recorder is unreadable.
     */
    fun recoveryDemand(
        tab: String,
        fence: TopicSessionFence,
        lifetime: TopicUseLifetime,
        now: Instant
    ): GraphTabRecoveryDemand {
        if (closed) return GraphTabRecoveryDemand.Unreadable
        val snapshot = accessSnapshot()
        val currentFence = currentAccessFence()
        val catalog = currentCatalog()
        val admission = gate.bind(fence, lifetime) != null
        return GraphRecorderReducer.recoveryDemand(mutableState.value, tab, catalog, snapshot, currentFence, admission, now, ::admitsKrx)
    }

    fun exposed(
        fence: TopicSessionFence,
        lifetime: TopicUseLifetime
    ): Map<GraphObservationSeriesKey, GraphRecoverableState> {
        if (closed) return emptyMap()
        val snapshot = accessSnapshot()
        val currentFence = currentAccessFence()
        val held = mutableState.value
        val candidate = GraphRecorderReducer.exposed(held, snapshot, currentFence, admission = true, krxAdmission = { true })
        val general = candidate.filterKeys { !it.seriesId.startsWith("krx.") }
        if (general.size == candidate.size) {
            return if (gate.bind(fence, lifetime) != null) candidate else emptyMap()
        }
        // USD and tether share one KRX series/anchor. The final bind admits both axes from one fresh snapshot.
        val owners = candidate.keys.filter { it.seriesId.startsWith("krx.") }.map { held.krxSeriesOwners[it.seriesId] }
        val original = owners.distinct().singleOrNull()
        if (original != null && gate.bindKrxGraph(fence, lifetime, original) != null) return candidate
        // A refused KRX part grants no GENERAL access; judge that surviving candidate last as well.
        return if (gate.bind(fence, lifetime) != null) general else emptyMap()
    }

    /**
     * S4 RT05a: retention at one clock read for every series, published once - an empty recorder too. It adds no observation,
     * syncs no access and replays nothing; after [close], it reads nothing.
     */
    fun retain() {
        if (closed) return
        mutableState.value = GraphRecorderReducer.retain(mutableState.value, clock.now())
    }

    /** Deletes without access synchronization; even an empty selected scope is cleared and published. */
    fun purge(selects: (GraphDataScope) -> Boolean): GraphRecorderPurge {
        if (closed) return GraphRecorderPurge.NOTHING_TO_REMOVE
        val fence = currentAccessFence()
        val live = fence?.userAccessEpoch?.let { GraphDataScope(fence.identity.uid, it) }
        if (live != null && selects(live)) return GraphRecorderPurge.LIVE_SCOPE_SELECTED
        val before = mutableState.value
        val next = GraphRecorderReducer.purge(before, selects)
        if (next === before) return GraphRecorderPurge.NOTHING_TO_REMOVE
        val removed = before.series.isNotEmpty() || before.pending.inputs.isNotEmpty() ||
            before.pending.lostTopics.isNotEmpty() || before.untransferredSeries.isNotEmpty() ||
            before.pending.krxLost.isNotEmpty() || before.krxUntransferred.isNotEmpty()
        mutableState.value = next
        return if (removed) GraphRecorderPurge.REMOVED else GraphRecorderPurge.NOTHING_TO_REMOVE
    }

    fun captureKrxMemoryPurgePlan(namespace: PurgeNamespace): KrxGraphPurgePlan? =
        if (closed) null else KrxGraphPurgePlan.forCapability(namespace, accessSnapshot())

    fun purgeKrxMemory(plan: KrxGraphPurgePlan): KrxGraphPurgeOutcome {
        if (closed) return KrxGraphPurgeOutcome(false, false)
        val seal = purgeSeal.merge(plan)
        val (next, outcome) = GraphRecorderReducer.purgeKrxMemory(mutableState.value, plan)
        purgeSeal = seal
        mutableState.value = next
        return outcome
    }

    override fun close() {
        if (closed) return
        closed = true
        collector?.cancel()
        collector = null
        purgeSeal = KrxGraphPurgeSeal.EMPTY
        mutableState.value = GraphRecorderReducer.empty()
    }
}
