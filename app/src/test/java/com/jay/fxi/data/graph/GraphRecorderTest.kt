package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphAuthority
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphEventKind
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphCatalogPeriod
import com.jay.fxi.domain.model.GraphCatalogTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.time.AppClock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 F2a contract r4 (JVM; r2 plus F2c-1: the state's `untransferredSeries` in every whole-state comparison, and
 * O12 no longer pins a held input that F2c-1 now replays; r4: F2b-2a turns O11 into row N1's loss): the graph recorder's owner around the F1 reducer - one published state, mutations
 * applied and published before they return, a control collector, a checked read, and close. Only faults the owner itself can
 * make are targeted here; the reducer's own rules stay locked by GraphRecorderReducerTest (F1).
 *
 * Oracles: ANDROID_V2_PLAN.md (HEAD 884aebc) :1321 (the recorder lives for the user session data scope, not a screen), :1336
 * (control, observations and responses are taken at the consuming boundary, in a checked order), :1366 (shared data is checked
 * per consumer). Design: R4c/S4 f2_design_codex.r1 (F2a), f2a_review_claude.r1 with its three-lens verification
 * (f2a_design_verify_workflow.result.json), agreed as the final signature and rows O1-O15 in f2_design_codex.r2.
 *
 * Fixed rules:
 *  - The reducer always gets the call-time `accessSnapshot()` and `currentAccessFence()`. The consumer's or the input's own
 *    (fence, lifetime) go to the gate only, as `gate.bind(fence, lifetime) != null` (observe: the attribution's owner and
 *    lifetime). No capture is built by hand for `admits`.
 *  - Each mutation reads its suppliers first, judges the original capture's bind last, then calls the reducer - even when bind
 *    refuses, so access sync and refused-loss handling still happen - and publishes the final state once, before returning.
 *  - `now` is one `clock.now()` per observe and per applyResponse, never an observation's or a response's time.
 *  - `exposed` changes nothing: it computes the reducer's candidate from the current getters, then returns it only if the
 *    consumer's bind, judged last, succeeds. This narrows nothing inside the gate itself (no atomicity is claimed there).
 *  - Mutations work from construction until close; `start` only launches the control collector, once. `close` is idempotent,
 *    empties the state at once, cancels only its own collector and leaves the supplied scope alive; after it mutations do
 *    nothing, capture returns nothing, exposed returns nothing and start does not restart.
 *  - The catalog is read from its supplier on every observe.
 *  - Serial execution is the caller's duty; the gate and the recorder share the snapshot and fence suppliers.
 *
 * Not here: continuity, replay, session acceptance, sending, timers, and binding a request to the recorder instance that issued
 * it (F2d). The implementation thread reads but does not edit this file.
 *
 * r5 adds the S4 F2e purge rows C1 and C2 (design R4c/S4 f2e_design_codex.r1, cut down by f2e_review_claude.r1 after a five-lens
 * verification and agreed in f2e_design_codex.r2; oracles ANDROID_V2_PLAN.md :1025 (the user purge covers the session-memory
 * graph recorder) and ScopePurger.kt (Completed means gone or proven absent)):
 *  - `purge(selects)` is a deletion, not a close and not an access decision. It reads no snapshot, no catalog and no clock and
 *    does not sync. After a close it reads nothing and answers NOTHING_TO_REMOVE.
 *  - It reads the current fence once, first: when that fence's (uid, epoch) is selected it changes nothing and answers
 *    LIVE_SCOPE_SELECTED - the next sync would adopt that scope again, so clearing it would be a false completion. A null fence
 *    or a null epoch goes on.
 *  - A selected held scope loses its series, held inputs, lost topics and untransferred markers and becomes null; seenRevision,
 *    seenUserEnd and nextVersion stay and versionFloor becomes nextVersion (the discard branch's rule; `empty()` is not used).
 *    The new state is published once. The answer is judged on the state before the call: REMOVED when any of those four held
 *    something, otherwise NOTHING_TO_REMOVE - a selected scope with nothing left is still cleared. An unselected or null scope
 *    is the same instance, unpublished.
 *
 * S4 RT03b-1a (rt03b1a_api_agreed.r2) adds Q1a01: `recoveryDemand(tab, fence, lifetime, now)` hands the reducer the current
 * fence read and binds the gate with the consumer's own fence and lifetime; it stores and publishes nothing - also on a
 * discard - judges without a replay, and does not read the clock; a refused admission and a closed recorder are Unreadable.
 * The reducer's judgement is GraphTabRecoveryDemandTest's.
 *
 * S4 RT05a (rt05_api_agreed.r2 §4-e) adds R01: `retain()` reads the clock once and replaces every series with
 * `retainGraphRecoverable` at that one time - the retention rules themselves stay GraphRecoverableStateTest's F10-F30 - and
 * keeps the scope, held inputs, lost topics, untransferred markers, seen values and versions. It publishes once, also with
 * nothing held, adds no observation and replays nothing; after close it reads nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphRecorderTest {

    private fun kst(hhmmss: String): Instant = Instant.parse("2026-10-07T$hhmmss+09:00")

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val noon = kst("20:02:00")

    private val identity = AuthIdentityFence("u1", 1L)
    private val identity2 = AuthIdentityFence("u1", 2L)
    private val grant = TopicGrantToken(7L)
    private val fenceN = TopicSessionFence(identity, "e1", grant)
    private val fenceN2 = TopicSessionFence(identity, "e2", grant)
    private val scopeN = GraphDataScope("u1", "e1")
    private val scopeN2 = GraphDataScope("u1", "e2")
    private val l3 = TopicUseLifetime(grant, 3L)
    private val l4 = TopicUseLifetime(grant, 4L)

    private val kb = GraphObservationSeriesKey(scopeN, "kb.usd")
    private val kbN2 = GraphObservationSeriesKey(scopeN2, "kb.usd")

    private val catalog = GraphCatalog(
        1.hours,
        mapOf("usd" to GraphCatalogTab("usd", "usd", emptyMap(), mapOf(
            GraphPeriod.ONE_DAY to GraphCatalogPeriod(listOf("investing.usd", "kb.usd", "hana.usd", "dxy"), listOf("kb.usd"))
        )))
    )

    private fun snap(
        revision: Long,
        userEnd: Long? = null,
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        invalidations: Long = 3L,
        token: Long = 7L
    ) = TopicAccessSnapshot(
        revision = revision,
        facts = TopicAccessFacts.NONE.copy(
            token = TopicGrantToken(token),
            binding = EntitlementsIdentity("u1", 1L),
            tokenStanding = true,
            userBlocks = userBlocks,
            capabilityBlocks = emptySet()
        ),
        userInvalidations = invalidations,
        lastUserEnd = userEnd?.let {
            TopicAccessEnd(it, TopicAccessEndReason.AUTHORITATIVE_LOSS, EntitlementsIdentity("u1", 1L), "u1", "e1")
        },
        lastCapabilityEnd = null
    )

    private fun attribution(owner: TopicSessionFence, lifetime: TopicUseLifetime) =
        TopicUseAttribution(Any(), owner, 1L, lifetime)

    private fun batch(
        rate: Double,
        at: Instant,
        owner: TopicSessionFence = fenceN,
        lifetime: TopicUseLifetime = l3
    ) = TopicGraphInput.Observations(
        1L, "fx:usd-krw", TopicGraphPath.WS, attribution(owner, lifetime), 1L,
        listOf(TopicGraphCandidate.Quote("kb", "usd-krw", rate, at, null))
    )

    private fun closed(t: Instant, rate: Double) = FreeGraphPoint(t, rate, rate, rate)

    private fun tab(
        points: List<FreeGraphPoint>,
        fetchedAt: Instant = kst("20:05:00"),
        inProgress: Map<String, GraphV2InProgress> = emptyMap()
    ) = GraphV2Tab("usd", GraphPeriod.ONE_DAY, "10min", fetchedAt, FreeGraph("10min", listOf(FreeGraphSeries("kb.usd", points))),
        inProgress)

    private data class Print(
        val buckets: Map<Instant, GraphObservedBucket>,
        val tip: GraphObservationId?,
        val observations: Set<GraphObservationId>,
        val server: Map<Instant, GraphServerBucket>,
        val demands: Map<Instant, GraphRecoveryDemand>,
        val gaps: Set<Instant>,
        val generation: Long,
        val applied: Long?,
        val cutoff: Instant?
    )

    private fun fingerprint(s: GraphRecoverableState) = Print(
        s.data.app.buckets, s.data.app.tip, s.data.app.observations, s.data.serverBuckets, s.pending, s.gapHistory,
        s.generation, s.lastAppliedVersion, s.lastCutoff
    )

    /** Everything an owner fault could move in the recorder state. */
    private data class Whole(
        val scope: GraphDataScope?,
        val series: Map<GraphObservationSeriesKey, Print>,
        val pendingInputs: List<TopicGraphInput>,
        val lostTopics: Set<String>,
        val untransferred: Set<String>,
        val seenUserEnd: Long,
        val seenRevision: Long,
        val nextVersion: Long,
        val versionFloor: Long
    )

    private fun whole(s: GraphRecorderState) = Whole(
        s.scope, s.series.mapValues { fingerprint(it.value) }, s.pending.inputs, s.pending.lostTopics, s.untransferredSeries,
        s.seenUserEnd, s.seenRevision, s.nextVersion, s.versionFloor
    )

    private val opened = mutableListOf<Rig>()

    private fun recorderTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.scope.cancel() }
            opened.clear()
        }
    }

    /** One pair of suppliers feeds both the gate and the recorder. */
    private inner class Rig(val test: TestScope) {
        init { opened += this }
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        var snapshot: TopicAccessSnapshot = snap(1)
        /** When set, the next reads return these in order and the last one repeats. */
        var sequence: ArrayDeque<TopicAccessSnapshot>? = null
        var snapshotReads = 0
        var fence: TopicSessionFence? = fenceN
        /** When set, the next fence reads return these in order and the last one repeats. */
        var fenceSequence: ArrayDeque<TopicSessionFence?>? = null
        var live: AuthIdentityFence? = identity
        var protectedOpen = true
        var catalogNow: GraphCatalog? = catalog
        var catalogReads = 0
        var clockNow: Instant = noon
        var clockReads = 0
        val revisions = MutableStateFlow(1L)

        private fun readSnapshot(): TopicAccessSnapshot {
            snapshotReads++
            val queue = sequence ?: return snapshot
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        var fenceReads = 0

        private fun readFence(): TopicSessionFence? {
            fenceReads++
            val queue = fenceSequence ?: return fence
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        val gate = GraphV2AccessGate({ live }, { readFence() }, { readSnapshot() }, { protectedOpen })
        val recorder = GraphRecorder(
            scope, revisions, { readSnapshot() }, { readFence() }, { catalogReads++; catalogNow }, gate,
            AppClock { clockReads++; clockNow }
        )

        val emissions = mutableListOf<GraphRecorderState>()
        fun record() {
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { recorder.state.collect { emissions += it } }
        }

        fun publish(s: TopicAccessSnapshot) {
            snapshot = s
            revisions.value = s.revision
        }

        fun state() = recorder.state.value

        /** A recorder holding scope N with kb adopted from a 20:01 quote under L3. */
        fun withKb(): Rig = apply {
            recorder.observe(batch(1341.7, kst("20:01:00")))
            assertTrue("premise: kb adopted", kb in state().series)
        }

        /**
         * Scope N holding all four kinds of data: kb with an issued request, untransferred markers from a topic loss, an input
         * held without a catalog and a topic lost without one. The catalog supplier is left absent.
         */
        fun loaded(): Rig = apply {
            withKb()
            assertEquals(1, recorder.captureRequests(setOf(kb), fenceN, l3).size)
            recorder.loseTopics(scopeN, mapOf("fx:usd-krw" to 3L))
            catalogNow = null
            recorder.observe(batch(1341.8, kst("20:01:10")))
            protectedOpen = false
            recorder.observe(batch(1341.9, kst("20:01:20")))
            protectedOpen = true
            val s = state()
            assertTrue("premise: every kind held", s.series.isNotEmpty() && s.pending.inputs.isNotEmpty() &&
                s.pending.lostTopics.isNotEmpty() && s.untransferredSeries.isNotEmpty())
            assertTrue("premise: a version issued", s.nextVersion > s.versionFloor)
        }
    }

    /** A DELIVERY_RESUMED fact for usd from [owner] under [lifetime]. */
    private fun resumed(owner: TopicSessionFence, lifetime: TopicUseLifetime = l3) = TopicGraphInput.Continuity(
        1L, TopicGraphEventKind.DELIVERY_RESUMED, null, setOf("fx:usd-krw"), setOf(TopicGraphPath.WS),
        TopicGraphAuthority(Any(), owner, 1L, lifetime), 1L, kst("20:01:30").toEpochMilliseconds()
    )

    /** The purge of user u1 keeping epoch e2. */
    private val retired: (GraphDataScope) -> Boolean = { it.uid == "u1" && it.userAccessEpoch != "e2" }

    private fun Rig.children() = scope.coroutineContext[kotlinx.coroutines.Job]!!.children.count()

    // --- O1-O3: lifecycle, publication, call-time access ---------------------------------------------------------------

    /**
     * O1: construction starts nothing; mutations already work before start; start twice subscribes once; a revision alone then
     * discards on a new user end (floor moves, counter stays) and keeps data on a hold.
     */
    @Test fun O1_constructionStartsNothingAndTheCollectorSyncsAccess() = recorderTest {
        val rig = Rig(this)
        assertEquals(0, rig.children())
        assertEquals(0, rig.revisions.subscriptionCount.value)
        rig.withKb()
        val requests = rig.recorder.captureRequests(setOf(kb), fenceN, l3)
        assertEquals(1, requests.size)
        rig.recorder.applyResponse(requests.single(), tab(listOf(closed(b, 1341.5))), fenceN, l3)
        assertEquals("mutations work before start", 1L, rig.state().series.getValue(kb).lastAppliedVersion)
        rig.recorder.start()
        rig.recorder.start()
        runCurrent()
        assertEquals("one collector", 1, rig.revisions.subscriptionCount.value)
        rig.publish(snap(2, userEnd = 1))
        runCurrent()
        assertTrue(rig.state().series.isEmpty())
        assertTrue(rig.state().pending.inputs.isEmpty())
        assertEquals(1L, rig.state().versionFloor)
        assertEquals(1L, rig.state().nextVersion)

        val held = Rig(this).withKb()
        held.recorder.start()
        runCurrent()
        val before = fingerprint(held.state().series.getValue(kb))
        held.publish(snap(2, userBlocks = setOf(TopicAccessBlock.PERSISTENCE_HOLD)))
        runCurrent()
        assertEquals("a hold keeps the data", before, fingerprint(held.state().series.getValue(kb)))
        assertEquals(2L, held.state().seenRevision)
    }

    /** O2: in one turn each mutation's final state is published before it returns, once; exposed changes nothing. */
    @Test fun O2_eachMutationPublishesItsFinalStateOnceBeforeReturning() = recorderTest {
        val rig = Rig(this)
        rig.record()
        val initial = rig.emissions.size
        rig.recorder.observe(batch(1341.7, kst("20:01:00")))
        assertTrue(kb in rig.state().series)
        assertEquals(initial + 1, rig.emissions.size)
        val first = rig.recorder.captureRequests(setOf(kb), fenceN, l3)
        assertEquals(1L, rig.state().nextVersion)
        val second = rig.recorder.captureRequests(setOf(kb), fenceN, l3)
        assertEquals(listOf(1L, 2L), (first + second).map { it.applicationVersion })
        assertEquals(2L, rig.state().nextVersion)
        assertEquals(initial + 3, rig.emissions.size)
        rig.recorder.applyResponse(second.single(), tab(listOf(closed(b, 1341.5))), fenceN, l3)
        assertEquals(2L, rig.state().series.getValue(kb).lastAppliedVersion)
        assertEquals(initial + 4, rig.emissions.size)
        val beforeRead = rig.state()
        assertEquals(setOf(kb), rig.recorder.exposed(fenceN, l3).keys)
        assertSame("exposed changes nothing", beforeRead, rig.state())
        assertEquals(initial + 4, rig.emissions.size)
    }

    /**
     * O3: an end or a block published while the collector has not run is still what each mutation judges by; exposed returns
     * nothing and changes nothing.
     */
    @Test fun O3_mutationsJudgeByTheCallTimeSnapshot() = recorderTest {
        fun started(): Rig = Rig(this).withKb().also { it.recorder.start() }

        val old = GraphObservationId("kb", "usd-krw", kst("20:01:00"), 1341.7)
        started().let { rig ->
            rig.publish(snap(2, userEnd = 1))
            rig.recorder.observe(batch(1341.9, kst("20:01:30")))
            assertFalse("the end discarded first", old in rig.state().series.getValue(kb).data.app.observations)
            assertEquals(1L, rig.state().seenUserEnd)
        }
        started().let { rig ->
            rig.publish(snap(2, userEnd = 1))
            assertEquals(emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kb), fenceN, l3))
            assertTrue(rig.state().series.isEmpty())
        }
        started().let { rig ->
            val request = rig.recorder.captureRequests(setOf(kb), fenceN, l3).single()
            rig.publish(snap(2, userEnd = 1))
            rig.recorder.applyResponse(request, tab(listOf(closed(b, 1341.5))), fenceN, l3)
            assertTrue(rig.state().series.isEmpty())
            assertEquals(1L, rig.state().versionFloor)
        }
        started().let { rig ->
            rig.publish(snap(2, userEnd = 1))
            val before = rig.state()
            assertTrue(rig.recorder.exposed(fenceN, l3).isEmpty())
            assertSame(before, rig.state())
        }
        started().let { rig ->
            rig.publish(snap(2, userBlocks = setOf(TopicAccessBlock.NOT_GRANTED)))
            rig.recorder.observe(batch(1343.0, kst("20:01:40")))
            val kbState = rig.state().series.getValue(kb)
            assertTrue("refused, so a loss is recorded", GraphRecoveryReason.HANDOVER_LOSS in kbState.pending.getValue(b).reasons)
            assertFalse(kbState.data.app.observations.any { it.rate == 1343.0 })
            assertEquals(emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kb), fenceN, l3))
            assertTrue(rig.recorder.exposed(fenceN, l3).isEmpty())
        }
    }

    // --- O4-O8: which fence, which clock, which gate inputs, per consumer, gate last --------------------------------------

    /**
     * O4: the reducer gets the current fence, never a consumer's or an input's. A late input or an old consumer from another
     * scope moves nothing in the held scope; an unconsumed scope change exposes nothing to either and the next mutation syncs.
     * Within one exposed call, a current fence read as absent and then as the consumer's makes the candidate empty.
     */
    @Test fun O4_theReducerIsGivenTheCurrentFenceOnly() = recorderTest {
        val rig = Rig(this)
        rig.fence = fenceN2
        rig.recorder.observe(batch(1341.7, kst("20:01:00"), owner = fenceN2))
        assertTrue("premise", kbN2 in rig.state().series)
        val request = rig.recorder.captureRequests(setOf(kbN2), fenceN2, l3).single()
        val held = whole(rig.state())
        rig.recorder.observe(batch(1343.0, kst("20:01:40"), owner = fenceN))
        assertEquals(emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kb), fenceN, l3))
        assertEquals(emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kbN2), fenceN, l3))
        rig.recorder.applyResponse(request, tab(listOf(closed(b, 1341.5))), fenceN, l3)
        assertTrue(rig.recorder.exposed(fenceN, l3).isEmpty())
        assertEquals(held, whole(rig.state()))

        val reverse = Rig(this).withKb()
        val heldN = whole(reverse.state())
        reverse.recorder.observe(batch(1343.0, kst("20:01:40"), owner = fenceN2))
        assertEquals(heldN, whole(reverse.state()))

        val moved = Rig(this).withKb()
        moved.fence = fenceN2
        assertTrue(moved.recorder.exposed(fenceN, l3).isEmpty())
        assertTrue(moved.recorder.exposed(fenceN2, l3).isEmpty())
        moved.recorder.captureRequests(setOf(kb), fenceN2, l3)
        assertEquals(scopeN2, moved.state().scope)
        assertTrue(moved.state().series.isEmpty())

        val gap = Rig(this).withKb()
        gap.record()
        val before = gap.state()
        val emitted = gap.emissions.size
        gap.fenceSequence = ArrayDeque(listOf(null, fenceN))
        assertTrue("the candidate uses the current fence read, not the consumer's", gap.recorder.exposed(fenceN, l3).isEmpty())
        assertSame(before, gap.state())
        assertEquals(emitted, gap.emissions.size)
        gap.fenceSequence = null
        assertEquals("premise: the same consumer is exposed once the fence is steady", setOf(kb), gap.recorder.exposed(fenceN, l3).keys)
    }

    /** O5: observe and applyResponse each read the clock once and give the reducer that `now`. */
    @Test fun O5_nowIsOneClockReadPerCall() = recorderTest {
        val rig = Rig(this)
        val reads = rig.clockReads
        rig.recorder.observe(batch(1341.0, kst("19:51:00")))
        assertEquals(reads + 1, rig.clockReads)
        val initialSync = requireGraphRecovery(
            GraphRecoverableState.empty(kb, GraphObservationOrder(emptyList())), noon, noon, GraphRecoveryReason.INITIAL_SYNC, noon
        )
        val expected = observeRecoverable(
            initialSync, listOf(GraphObservation(kb, GraphObservationId("kb", "usd-krw", kst("19:51:00"), 1341.0))), noon
        ).state
        assertEquals(fingerprint(expected), fingerprint(rig.state().series.getValue(kb)))
        assertTrue("INITIAL_SYNC on the current bucket", b in rig.state().series.getValue(kb).pending.keys)
        assertFalse(a in rig.state().series.getValue(kb).pending.keys)

        val request = rig.recorder.captureRequests(setOf(kb), fenceN, l3).single()
        val seeded = tab(
            listOf(closed(a, 1341.2)), fetchedAt = kst("20:15:00"),
            inProgress = mapOf("kb.usd" to GraphV2InProgress(b, 1342.0, 1340.0, 1341.5, kst("20:01:30")))
        )
        val beforeApply = rig.state().series.getValue(kb)
        val clock = rig.clockReads
        rig.recorder.applyResponse(request, seeded, fenceN, l3)
        assertEquals(clock + 1, rig.clockReads)
        assertEquals(
            fingerprint(applyGraphRecoveryResponse(beforeApply, scopeN, request, seeded, noon)),
            fingerprint(rig.state().series.getValue(kb))
        )
        assertTrue("the seed for the clock's bucket was kept", rig.state().series.getValue(kb).data.serverBuckets[b] is GraphServerBucket.Seeds)
    }

    /**
     * O6: admission is the gate's bind on the original capture: a closed protected admission, a live identity that moved, or an
     * owner fence of another auth generation refuse even though the snapshot admits the lifetime.
     */
    @Test fun O6_admissionIsTheGateOnTheOriginalCapture() = recorderTest {
        for (case in listOf("protected", "identity")) {
            val rig = Rig(this).withKb()
            val request = rig.recorder.captureRequests(setOf(kb), fenceN, l3).single()
            if (case == "protected") rig.protectedOpen = false else rig.live = identity2
            rig.recorder.observe(batch(1343.0, kst("20:01:40")))
            val kbState = rig.state().series.getValue(kb)
            assertFalse(case, kbState.data.app.observations.any { it.rate == 1343.0 })
            assertTrue(case, GraphRecoveryReason.HANDOVER_LOSS in kbState.pending.getValue(b).reasons)
            assertEquals(case, emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kb), fenceN, l3))
            rig.recorder.applyResponse(request, tab(listOf(closed(b, 1341.5))), fenceN, l3)
            assertNull(case, rig.state().series.getValue(kb).lastAppliedVersion)
            assertTrue(case, rig.recorder.exposed(fenceN, l3).isEmpty())
            if (case == "protected") rig.protectedOpen = true else rig.live = identity
            assertEquals(case, setOf(kb), rig.recorder.exposed(fenceN, l3).keys)
        }
        val rig = Rig(this).withKb()
        val otherGeneration = TopicSessionFence(identity2, "e1", grant)
        rig.recorder.observe(batch(1343.0, kst("20:01:40"), owner = otherGeneration))
        val kbState = rig.state().series.getValue(kb)
        assertFalse(kbState.data.app.observations.any { it.rate == 1343.0 })
        assertTrue(GraphRecoveryReason.HANDOVER_LOSS in kbState.pending.getValue(b).reasons)
        assertEquals(scopeN, rig.state().scope)
        assertTrue(rig.recorder.exposed(otherGeneration, l3).isEmpty())
    }

    /** O7: the same state is exposed to a consumer whose lifetime stands and not to an older one. */
    @Test fun O7_exposureIsJudgedPerConsumer() = recorderTest {
        val rig = Rig(this).withKb()
        rig.snapshot = snap(2, invalidations = 4L)
        assertEquals(setOf(kb), rig.recorder.exposed(fenceN, l4).keys)
        assertTrue(rig.recorder.exposed(fenceN, l3).isEmpty())
        assertEquals(setOf(kb), rig.recorder.exposed(fenceN, l4).keys)
    }

    /** O8: exposed judges the consumer's bind after computing its candidate - a snapshot that moves in between refuses it. */
    @Test fun O8_exposedJudgesTheGateLast() = recorderTest {
        val rig = Rig(this).withKb()
        val before = rig.state()
        rig.sequence = ArrayDeque(listOf(snap(1), snap(2, invalidations = 4L)))
        assertTrue(rig.recorder.exposed(fenceN, l3).isEmpty())
        assertSame(before, rig.state())
    }

    // --- O9-O15 ----------------------------------------------------------------------------------------------------------

    /** O9: close is idempotent, empties at once, unsubscribes, keeps the supplied scope, and nothing revives the recorder. */
    @Test fun O9_closeEndsTheRecorder() = recorderTest {
        Rig(this).let { rig ->
            rig.recorder.close()
            rig.recorder.start()
            runCurrent()
            assertEquals(0, rig.revisions.subscriptionCount.value)
            rig.recorder.observe(batch(1341.7, kst("20:01:00")))
            assertTrue(rig.state().series.isEmpty())
            assertEquals(emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kb), fenceN, l3))
            assertTrue(rig.recorder.exposed(fenceN, l3).isEmpty())
        }
        Rig(this).withKb().let { rig ->
            val request = rig.recorder.captureRequests(setOf(kb), fenceN, l3).single()
            rig.recorder.start()
            runCurrent()
            rig.record()
            rig.recorder.close()
            assertTrue(rig.state().series.isEmpty())
            assertTrue(rig.state().pending.inputs.isEmpty() && rig.state().pending.lostTopics.isEmpty())
            assertNull(rig.state().scope)
            val afterClose = rig.emissions.size
            val closed = rig.state()
            rig.recorder.close()
            runCurrent()
            assertEquals(0, rig.revisions.subscriptionCount.value)
            assertTrue("the supplied scope stays", rig.scope.isActive)
            rig.recorder.observe(batch(1341.9, kst("20:01:30")))
            assertEquals(emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kb), fenceN, l3))
            rig.recorder.applyResponse(request, tab(listOf(closed(b, 1341.5))), fenceN, l3)
            assertTrue(rig.recorder.exposed(fenceN, l3).isEmpty())
            rig.recorder.start()
            runCurrent()
            assertEquals(0, rig.revisions.subscriptionCount.value)
            assertSame(closed, rig.state())
            assertEquals(afterClose, rig.emissions.size)
        }
        Rig(this).withKb().let { rig ->
            rig.recorder.start()
            runCurrent()
            rig.publish(snap(2, userEnd = 1))
            rig.recorder.close()
            val closed = rig.state()
            runCurrent()
            assertSame("a scheduled collection does not revive it", closed, rig.state())
            assertTrue(rig.scope.isActive)
        }
    }

    /** O10: a regressed snapshot reached through the collector is rejected without a new state; the next normal one applies. */
    @Test fun O10_aRegressedSnapshotIsRejectedWithoutPublishing() = recorderTest {
        val rig = Rig(this).withKb()
        rig.recorder.start()
        runCurrent()
        rig.publish(snap(5))
        runCurrent()
        assertEquals(5L, rig.state().seenRevision)
        rig.record()
        val before = rig.state()
        val count = rig.emissions.size
        rig.snapshot = snap(4, userEnd = 9)
        rig.revisions.value = 6L
        runCurrent()
        assertSame(before, rig.state())
        assertEquals(count, rig.emissions.size)
        rig.publish(snap(7, userEnd = 1))
        runCurrent()
        assertTrue(rig.state().series.isEmpty())
        assertEquals(7L, rig.state().seenRevision)
    }

    /**
     * O11 (amended by F2b-2a for row N1): with no current fence and no new end, an input from the held scope is not adopted but
     * lost like a refusal - HANDOVER_LOSS over its span on existing kb; the rest of the state, the sync included, is kept.
     */
    @Test fun O11_aMissingFenceAdoptsNothingAndLosesTheInput() = recorderTest {
        val rig = Rig(this).withKb()
        val held = whole(rig.state())
        rig.fence = null
        rig.snapshot = snap(2)
        rig.recorder.observe(batch(1343.0, kst("20:01:40")))
        val now = whole(rig.state())
        assertEquals(held.copy(seenRevision = 2L, series = emptyMap()), now.copy(series = emptyMap()))
        assertEquals(setOf(kb), now.series.keys)
        val kbHeld = held.series.getValue(kb)
        val kbNow = now.series.getValue(kb)
        assertEquals(
            "not adopted",
            kbHeld.copy(demands = kbNow.demands, gaps = kbNow.gaps, generation = kbNow.generation),
            kbNow
        )
        assertTrue(GraphRecoveryReason.HANDOVER_LOSS in kbNow.demands.getValue(b).reasons)
    }

    /** O12: every observe reads the catalog supplier: held while absent, mapped once present (the replay order is F2c-1's R7). */
    @Test fun O12_theCatalogIsReadOnEveryObserve() = recorderTest {
        val rig = Rig(this)
        rig.catalogNow = null
        val first = batch(1341.7, kst("20:01:00"))
        rig.recorder.observe(first)
        assertSame(first, rig.state().pending.inputs.single())
        assertTrue(rig.state().series.isEmpty())
        rig.catalogNow = catalog
        rig.recorder.observe(batch(1341.9, kst("20:01:20")))
        assertEquals(setOf(kb), rig.state().series.keys)
    }

    /** O13: a lifetime of another grant than the fence's is refused even when the snapshot admits it - bind checks the grant. */
    @Test fun O13_bindChecksTheGrant() = recorderTest {
        val rig = Rig(this).withKb()
        val other = TopicUseLifetime(TopicGrantToken(8L), 3L)
        rig.snapshot = snap(2, token = 8L)
        rig.recorder.observe(batch(1343.0, kst("20:01:40"), lifetime = other))
        val kbState = rig.state().series.getValue(kb)
        assertFalse(kbState.data.app.observations.any { it.rate == 1343.0 })
        assertTrue(GraphRecoveryReason.HANDOVER_LOSS in kbState.pending.getValue(b).reasons)
        assertEquals(emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kb), fenceN, other))
        assertTrue(rig.recorder.exposed(fenceN, other).isEmpty())
    }

    /** O14: applyResponse uses the request it is given - a demand made after that capture is not released by it. */
    @Test fun O14_theGivenRequestIsApplied() = recorderTest {
        val rig = Rig(this).withKb()
        val request = rig.recorder.captureRequests(setOf(kb), fenceN, l3).single()
        rig.protectedOpen = false
        rig.recorder.observe(batch(1343.0, kst("20:01:50")))
        rig.protectedOpen = true
        val beforeApply = rig.state().series.getValue(kb)
        assertNotEquals("premise: a newer generation", request.capturedGeneration, beforeApply.generation)
        val response = tab(listOf(closed(b, 1341.5)))
        rig.recorder.applyResponse(request, response, fenceN, l3)
        assertEquals(
            fingerprint(applyGraphRecoveryResponse(beforeApply, scopeN, request, response, noon)),
            fingerprint(rig.state().series.getValue(kb))
        )
        assertTrue("the newer demand stays", b in rig.state().series.getValue(kb).pending.keys)
    }

    /** O15: each mutation also judges the bind after reading its snapshot - a snapshot that moves in between refuses it. */
    @Test fun O15_mutationsJudgeTheGateLast() = recorderTest {
        fun moving(): Rig = Rig(this).withKb().also {
            it.sequence = ArrayDeque(listOf(snap(1), snap(2, invalidations = 4L)))
        }

        moving().let { rig ->
            rig.recorder.observe(batch(1343.0, kst("20:01:40")))
            val kbState = rig.state().series.getValue(kb)
            assertFalse(kbState.data.app.observations.any { it.rate == 1343.0 })
            assertTrue(GraphRecoveryReason.HANDOVER_LOSS in kbState.pending.getValue(b).reasons)
        }
        moving().let { rig ->
            assertEquals(emptyList<GraphRecoveryRequest>(), rig.recorder.captureRequests(setOf(kb), fenceN, l3))
        }
        Rig(this).withKb().let { rig ->
            val request = rig.recorder.captureRequests(setOf(kb), fenceN, l3).single()
            rig.sequence = ArrayDeque(listOf(snap(1), snap(2, invalidations = 4L)))
            rig.recorder.applyResponse(request, tab(listOf(closed(b, 1341.5))), fenceN, l3)
            assertNull(rig.state().series.getValue(kb).lastAppliedVersion)
        }
    }

    // --- F2e C1-C2: purge ------------------------------------------------------------------------------------------------

    /**
     * C1: a selected held scope is cleared - all four kinds of data and the scope itself - while the control values stay and the
     * response floor reaches the last issued version, published once and judged on the state before the call. The current fence
     * is read once and first; nothing else is read, so a stale snapshot does not stop it and nothing is synced. A selected scope
     * that is still current is refused untouched; unselected, absent and closed scopes are left alone; a selected scope already
     * emptied by an end is still cleared.
     */
    @Test fun C1_purgeClearsTheSelectedHeldScope() = recorderTest {
        Rig(this).loaded().let { rig ->
            rig.fence = fenceN2
            rig.snapshot = snap(0)
            rig.record()
            val held = whole(rig.state())
            val count = rig.emissions.size
            val snapshotReads = rig.snapshotReads
            val catalogReads = rig.catalogReads
            val clockReads = rig.clockReads
            val fenceReads = rig.fenceReads
            assertEquals(GraphRecorderPurge.REMOVED, rig.recorder.purge(retired))
            assertEquals(
                held.copy(
                    scope = null, series = emptyMap(), pendingInputs = emptyList(), lostTopics = emptySet(),
                    untransferred = emptySet(), versionFloor = held.nextVersion
                ),
                whole(rig.state())
            )
            assertEquals("published once", count + 1, rig.emissions.size)
            assertEquals("no snapshot read", snapshotReads, rig.snapshotReads)
            assertEquals("no catalog read", catalogReads, rig.catalogReads)
            assertEquals("no clock read", clockReads, rig.clockReads)
            assertEquals("the fence read once", fenceReads + 1, rig.fenceReads)
        }
        Rig(this).loaded().let { rig ->
            rig.record()
            val before = rig.state()
            val count = rig.emissions.size
            assertEquals("the held scope is still current", GraphRecorderPurge.LIVE_SCOPE_SELECTED, rig.recorder.purge(retired))
            assertSame(before, rig.state())
            assertEquals(count, rig.emissions.size)
        }
        Rig(this).loaded().let { rig ->
            rig.fence = TopicSessionFence(identity, null, grant)
            assertEquals("a fence without an epoch names no scope", GraphRecorderPurge.REMOVED, rig.recorder.purge(retired))
        }
        Rig(this).let { rig ->
            rig.fence = fenceN2
            rig.recorder.observe(batch(1341.7, kst("20:01:00"), owner = fenceN2))
            assertTrue("premise: the kept scope holds kb", kbN2 in rig.state().series)
            rig.record()
            val before = rig.state()
            val count = rig.emissions.size
            assertEquals(GraphRecorderPurge.NOTHING_TO_REMOVE, rig.recorder.purge(retired))
            assertSame("the kept scope", before, rig.state())
            assertEquals(count, rig.emissions.size)
        }
        Rig(this).loaded().let { rig ->
            rig.fence = null
            val before = rig.state()
            assertEquals(GraphRecorderPurge.NOTHING_TO_REMOVE, rig.recorder.purge { it.uid == "u2" })
            assertSame("another user's purge", before, rig.state())
        }
        Rig(this).let { rig ->
            rig.fence = null
            val before = rig.state()
            assertEquals(GraphRecorderPurge.NOTHING_TO_REMOVE, rig.recorder.purge(retired))
            assertSame("no scope", before, rig.state())
        }
        Rig(this).withKb().let { rig ->
            rig.snapshot = snap(2, userEnd = 1)
            rig.recorder.replayPending()
            assertEquals("premise: the end emptied the held scope", scopeN, rig.state().scope)
            assertTrue(rig.state().series.isEmpty())
            rig.fence = null
            rig.record()
            val held = whole(rig.state())
            val count = rig.emissions.size
            assertEquals(GraphRecorderPurge.NOTHING_TO_REMOVE, rig.recorder.purge(retired))
            assertEquals("cleared all the same", held.copy(scope = null), whole(rig.state()))
            assertEquals(count + 1, rig.emissions.size)
        }
        Rig(this).loaded().let { rig ->
            rig.recorder.close()
            val closed = rig.state()
            val fenceReads = rig.fenceReads
            assertEquals(GraphRecorderPurge.NOTHING_TO_REMOVE, rig.recorder.purge(retired))
            assertEquals("nothing read after close", fenceReads, rig.fenceReads)
            assertSame(closed, rig.state())
        }
    }

    /**
     * C2: with no current fence and no new end, late inputs from a purged scope at or above the floor - an observation without a
     * catalog, a DELIVERY_RESUMED and a topic loss - leave nothing, although the same inputs leave a loss, a held resume and a
     * lost topic on a recorder that was not purged; a later scope is then recorded as usual.
     */
    @Test fun C2_aPurgedScopeStaysSealedAndTheRecorderStaysInUse() = recorderTest {
        Rig(this).withKb().let { control ->
            control.fence = null
            control.catalogNow = null
            control.recorder.observe(batch(1342.0, kst("20:01:40")))
            assertEquals("control: the observation is lost", setOf("fx:usd-krw"), control.state().pending.lostTopics)
            control.recorder.observe(resumed(fenceN))
            assertEquals("control: the resume is held", 1, control.state().pending.inputs.size)
            control.recorder.loseTopics(scopeN, mapOf("fx:jpy-krw" to 3L))
            assertTrue("control: the topic is lost", "fx:jpy-krw" in control.state().pending.lostTopics)
        }
        Rig(this).loaded().let { rig ->
            rig.fence = null
            assertEquals(GraphRecorderPurge.REMOVED, rig.recorder.purge(retired))
            // syncAccess builds a new state on every call, so these compare fields rather than instances.
            fun assertSealed(label: String) {
                val s = rig.state()
                assertNull(label, s.scope)
                assertTrue(label, s.series.isEmpty())
                assertTrue(label, s.pending.inputs.isEmpty())
                assertTrue(label, s.pending.lostTopics.isEmpty())
                assertTrue(label, s.untransferredSeries.isEmpty())
            }
            rig.recorder.observe(batch(1342.0, kst("20:01:40")))
            assertSealed("observation")
            rig.recorder.observe(resumed(fenceN))
            assertSealed("resume")
            rig.recorder.loseTopics(scopeN, mapOf("fx:jpy-krw" to 3L))
            assertSealed("topic loss")

            rig.fence = fenceN2
            rig.catalogNow = catalog
            rig.recorder.observe(batch(1342.5, kst("20:01:50"), owner = fenceN2))
            assertEquals(scopeN2, rig.state().scope)
            assertTrue("a later scope is recorded", kbN2 in rig.state().series)
        }
    }

    /** Q1a01 (S4 RT03b-1a): the recorder's query changes nothing it holds and needs a usable admission and an open recorder. */
    @Test
    fun Q1a01_theQueryStoresNothing() = recorderTest {
        val rig = Rig(this).loaded()
        rig.catalogNow = catalog
        rig.record()
        runCurrent()
        val before = rig.state()
        val emitted = rig.emissions.size
        val clock = rig.clockReads
        val answer = rig.recorder.recoveryDemand("usd", fenceN, l3, noon)
        assertEquals("kb's handover window is closed demand; held inputs, lost topics and untransferred series wait",
            GraphTabRecoveryDemand.Pending(closed = true, mappingWait = true), answer)
        assertSame("nothing stored", before, rig.state())
        assertEquals("nothing published", emitted, rig.emissions.size)
        assertTrue("no replay with a catalog present", rig.state().pending.inputs.isNotEmpty())
        assertEquals("no clock read", clock, rig.clockReads)
        assertEquals("the same answer again", answer, rig.recorder.recoveryDemand("usd", fenceN, l3, noon))
        assertSame(before, rig.state())

        rig.protectedOpen = false
        assertEquals("a refused admission", GraphTabRecoveryDemand.Unreadable, rig.recorder.recoveryDemand("usd", fenceN, l3, noon))
        rig.protectedOpen = true
        rig.fenceSequence = ArrayDeque(listOf(null, fenceN))
        assertEquals("the reducer gets the current fence read, not the consumer's", GraphTabRecoveryDemand.Unreadable,
            rig.recorder.recoveryDemand("usd", fenceN, l3, noon))
        rig.fenceSequence = ArrayDeque(listOf(fenceN, null))
        assertEquals("the current fence is read, not taken from the consumer", GraphTabRecoveryDemand.Unreadable,
            rig.recorder.recoveryDemand("usd", fenceN, l3, noon))
        rig.fenceSequence = null
        assertEquals("the gate binds the consumer's own fence", GraphTabRecoveryDemand.Unreadable,
            rig.recorder.recoveryDemand("usd", TopicSessionFence(identity2, "e1", grant), l3, noon))
        assertSame("an Unreadable answer stores nothing either", before, rig.state())
        assertEquals(emitted, rig.emissions.size)
        rig.fence = fenceN2
        rig.snapshot = snap(2)
        assertEquals("a new scope's discard is judged, not stored", GraphTabRecoveryDemand.None,
            rig.recorder.recoveryDemand("usd", fenceN2, l3, noon))
        assertSame(before, rig.state())
        rig.fence = fenceN
        rig.snapshot = snap(1)

        val heldOnly = Rig(this)
        heldOnly.catalogNow = null
        heldOnly.recorder.observe(batch(1341.7, kst("20:01:00")))
        assertTrue("premise: only an input is held", heldOnly.state().series.isEmpty() &&
            heldOnly.state().pending.inputs.isNotEmpty() && heldOnly.state().untransferredSeries.isEmpty())
        heldOnly.catalogNow = catalog
        assertEquals("the held input still waits: the answer is not computed on a replay",
            GraphTabRecoveryDemand.Pending(closed = false, mappingWait = true),
            heldOnly.recorder.recoveryDemand("usd", fenceN, l3, noon))
        rig.recorder.close()
        assertEquals("a closed recorder", GraphTabRecoveryDemand.Unreadable, rig.recorder.recoveryDemand("usd", fenceN, l3, noon))
    }

    /** A hana quote at [at] from scope N under L3. */
    private fun hanaBatch(rate: Double, at: Instant) = TopicGraphInput.Observations(
        1L, "fx:usd-krw", TopicGraphPath.WS, attribution(fenceN, l3), 1L,
        listOf(TopicGraphCandidate.Quote("hana", "usd-krw", rate, at, null))
    )

    /**
     * R01 (RT05a E02/E09/E13): retain() reads the clock once and no other supplier, and replaces every series with
     * retainGraphRecoverable at that time; every other field stays. It publishes once, also when nothing is held. A reversal
     * raises a generation once and a second retain at that same time raises none; after close it reads nothing.
     */
    @Test fun R01_retainAppliesRetentionToEverySeriesAtOneClockRead() = recorderTest {
        val rig = Rig(this)
        rig.recorder.observe(hanaBatch(1351.2, kst("19:55:00")))
        rig.loaded()
        val hana = GraphObservationSeriesKey(scopeN, "hana.usd")
        val before = rig.state()
        assertEquals("premise: two series", setOf(kb, hana), before.series.keys)
        rig.record()
        runCurrent()
        val emitted = rig.emissions.size
        val reads = rig.clockReads
        // 26 hours on, both series' buckets are outside the 25 h window.
        val later = noon + 26.hours
        rig.clockNow = later
        val supplierReads = Triple(rig.snapshotReads, rig.fenceReads, rig.catalogReads)
        rig.recorder.retain()
        assertEquals("one clock read", reads + 1, rig.clockReads)
        assertEquals("no access sync, no admission, no replay: no supplier read", supplierReads,
            Triple(rig.snapshotReads, rig.fenceReads, rig.catalogReads))
        assertEquals("published once", emitted + 1, rig.emissions.size)
        val after = rig.state()
        assertEquals(before.series.keys, after.series.keys)
        for ((key, series) in before.series) {
            assertEquals("$key at the one read time", fingerprint(retainGraphRecoverable(series, later)),
                fingerprint(after.series.getValue(key)))
            assertNotEquals("premise: retention changed $key", fingerprint(series), fingerprint(after.series.getValue(key)))
        }
        assertEquals("every other field kept", whole(before).copy(series = emptyMap()), whole(after).copy(series = emptyMap()))
        rig.clockNow = later - 2.hours
        rig.recorder.retain()
        val reversed = rig.state()
        assertEquals("premise: the reversal raised kb's generation", after.series.getValue(kb).generation + 1,
            reversed.series.getValue(kb).generation)
        rig.recorder.retain()
        assertEquals("again at the reversed time: no further generation", reversed.series.mapValues { fingerprint(it.value) },
            rig.state().series.mapValues { fingerprint(it.value) })

        val empty = Rig(this)
        empty.record()
        runCurrent()
        val first = empty.emissions.size
        empty.recorder.retain()
        assertEquals("an empty recorder publishes once", first + 1, empty.emissions.size)
        assertTrue(empty.state().series.isEmpty())

        rig.recorder.close()
        val closedReads = rig.clockReads
        val closedState = rig.state()
        rig.recorder.retain()
        assertEquals("closed: no clock read", closedReads, rig.clockReads)
        assertSame(closedState, rig.state())
    }
}
