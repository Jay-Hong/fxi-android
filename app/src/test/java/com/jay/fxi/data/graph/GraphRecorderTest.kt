package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphCandidate
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
 * Claude-owned S4 F2a contract r3 (JVM; r2 plus F2c-1: the state's `untransferredSeries` in every whole-state comparison, and
 * O12 no longer pins a held input that F2c-1 now replays): the graph recorder's owner around the F1 reducer - one published state, mutations
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
 * Not here: continuity, replay, session acceptance, sending, timers, purge (F2b-F2f), and binding a request to the recorder
 * instance that issued it (F2d). The implementation thread reads but does not edit this file.
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

        private fun readFence(): TopicSessionFence? {
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
    }

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

    /** O11: with no current fence and no new end, an input from the held scope is neither adopted nor lost; the sync is kept. */
    @Test fun O11_aMissingFenceAdoptsAndLosesNothing() = recorderTest {
        val rig = Rig(this).withKb()
        val held = whole(rig.state())
        rig.fence = null
        rig.snapshot = snap(2)
        rig.recorder.observe(batch(1343.0, kst("20:01:40")))
        val now = whole(rig.state())
        assertEquals(held.copy(seenRevision = 2L), now)
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
}
