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
import com.jay.fxi.data.remote.TopicGraphEventPayload
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphCatalogPeriod
import com.jay.fxi.domain.model.GraphCatalogTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.TopicAuthResolution
import com.jay.fxi.time.AppClock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 F2c-1 contract r3 (JVM; r2 with the rules text corrected in review, no row changed; r2 added R10's collector
 * hand-over of a loss with nothing held, from the r1 battery's H21): what the graph recorder does with continuity facts, how inputs held before the
 * catalog are replayed, and how a loss without times reaches series that exist now or are created later.
 *
 * Oracles: ANDROID_V2_PLAN.md (HEAD 7728b60) :1329 (every bucket a reception gap spans keeps a recovery reason), :1323-1325
 * (the recorder takes allowed input before the catalog, bounds the holding and hands its losses over). Code facts behind the
 * rules: the S3 emitter keeps its own open (topic, path) gaps - an authority opening resets them to every desired topic and
 * path, and the first candidate of an open pair is preceded by DELIVERY_RESUMED (TopicSessionCoordinator.kt:1825-1829,
 * :1983-1995). Design: R4c/S4 f2c_design_codex.r1, cut down by f2c_review_claude.r1 after a three-lens verification
 * (f2c_design_verify_workflow.result.json), agreed as signatures and rows R1-R10 in f2c_design_codex.r2.
 *
 * Rules:
 *  - Every continuity call syncs access first (an older snapshot rejects the whole call). Only DELIVERY_RESUMED means
 *    anything else, and only when its owner is non-null and its owner's scope is the held one; no admission is asked of it.
 *    With a catalog, the held scope current and nothing held, the resume itself leaves RECEIVE_GAP over the whole D3 demand
 *    window [current - 24 h, current] (145 buckets, never current + 600) on each existing series its topics map to (E1 and E2);
 *    it creates no series and marks none that does not exist. Without a catalog, without a current scope, or while earlier
 *    inputs are still held, it is held as given, in order, under the existing 512-unit budget (never through the admission
 *    path that turns a refusal into a loss). Every other kind, a null owner and another scope's owner have no event-specific
 *    effect beyond access sync; the owner's preceding replay and hand-over of existing pending losses may still occur.
 *  - Replay: held inputs are processed in their original order (observations re-bound on their own capture, resumes without
 *    a bind), then the lost topics are handed over, then the new input. A loss without times reaches each existing mapped
 *    series as HANDOVER_LOSS over the whole demand window; a mapped series that does not exist yet is kept in
 *    `untransferredSeries` and receives the same right after its first allowed observation creates it. A user end, a scope
 *    change and close empty that set too. With no current scope the replay waits, and the access collector runs it once the
 *    held scope is current again.
 *  - F2a's owner rules hold on every new path: suppliers read first and original observation captures bound afterwards.
 *    Each observe reads the clock once and shares that value with replay; the collector reads it once only when replay or
 *    loss hand-over is attempted. Each call publishes at most one final state; close prevents further work and a regressed
 *    snapshot causes no state change or emission.
 *
 * Not here: consuming the sink's cumulative loss, dropping inputs on a null current fence as a loss (F2b, row N1), the public
 * replay entry for catalog adoption (F2d). The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphRecorderContinuityTest {

    private fun kst(hhmmss: String): Instant = Instant.parse("2026-10-07T$hhmmss+09:00")

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val noon = kst("20:02:00")

    private val identity = AuthIdentityFence("u1", 1L)
    private val grant = TopicGrantToken(7L)
    private val fenceN = TopicSessionFence(identity, "e1", grant)
    private val fenceN2 = TopicSessionFence(identity, "e2", grant)
    private val scopeN = GraphDataScope("u1", "e1")
    private val scopeN2 = GraphDataScope("u1", "e2")
    private val l3 = TopicUseLifetime(grant, 3L)
    private val l4 = TopicUseLifetime(grant, 4L)

    private val kb = GraphObservationSeriesKey(scopeN, "kb.usd")
    private val hana = GraphObservationSeriesKey(scopeN, "hana.usd")
    private val dxy = GraphObservationSeriesKey(scopeN, "dxy")
    private val hanaN2 = GraphObservationSeriesKey(scopeN2, "hana.usd")

    private val catalog = GraphCatalog(
        1.hours,
        mapOf("usd" to GraphCatalogTab("usd", "usd", emptyMap(), mapOf(
            GraphPeriod.ONE_DAY to GraphCatalogPeriod(listOf("investing.usd", "kb.usd", "hana.usd", "dxy"), listOf("kb.usd"))
        )))
    )

    /** [current - 24 h, current], inclusive: the demand window D3 keeps. */
    private fun window(now: Instant): Set<Instant> {
        val current = graphObservationBucketStart(now)
        return (0..144).mapTo(linkedSetOf()) { Instant.fromEpochSeconds(current.epochSeconds - it * 600L) }
    }

    private fun snap(revision: Long, userEnd: Long? = null, invalidations: Long = 3L) = TopicAccessSnapshot(
        revision = revision,
        facts = TopicAccessFacts.NONE.copy(
            token = grant,
            binding = EntitlementsIdentity("u1", 1L),
            tokenStanding = true,
            userBlocks = emptySet<TopicAccessBlock>(),
            capabilityBlocks = emptySet()
        ),
        userInvalidations = invalidations,
        lastUserEnd = userEnd?.let {
            TopicAccessEnd(it, TopicAccessEndReason.AUTHORITATIVE_LOSS, EntitlementsIdentity("u1", 1L), "u1", "e1")
        },
        lastCapabilityEnd = null
    )

    private fun quote(source: String, rate: Double, at: Instant) = TopicGraphCandidate.Quote(source, "usd-krw", rate, at, null)

    private fun fx(
        vararg candidates: TopicGraphCandidate,
        owner: TopicSessionFence = fenceN,
        lifetime: TopicUseLifetime = l3
    ) = TopicGraphInput.Observations(
        1L, "fx:usd-krw", TopicGraphPath.WS, TopicUseAttribution(Any(), owner, 1L, lifetime), 1L, candidates.toList()
    )

    private fun kbAt(rate: Double, at: String, lifetime: TopicUseLifetime = l3) = fx(quote("kb", rate, kst(at)), lifetime = lifetime)

    private fun dollar(rate: Double, at: Instant) = TopicGraphInput.Observations(
        1L, "dxy:spot", TopicGraphPath.WS, TopicUseAttribution(Any(), fenceN, 1L, l3), 1L,
        listOf(TopicGraphCandidate.DollarIndex(rate, at, "investing"))
    )

    private fun event(
        kind: TopicGraphEventKind,
        topics: Set<String> = setOf("fx:usd-krw"),
        owner: TopicSessionFence? = fenceN,
        lifetime: TopicUseLifetime? = l3,
        at: Instant = kst("20:01:30"),
        payload: TopicGraphEventPayload? = null
    ) = TopicGraphInput.Continuity(
        1L, kind, null, topics, setOf(TopicGraphPath.WS), TopicGraphAuthority(Any(), owner, 1L, lifetime), 1L,
        at.toEpochMilliseconds(), payload
    )

    private fun resumed(topics: Set<String> = setOf("fx:usd-krw"), owner: TopicSessionFence? = fenceN, lifetime: TopicUseLifetime? = l3) =
        event(TopicGraphEventKind.DELIVERY_RESUMED, topics, owner, lifetime)

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

    /** One pair of suppliers feeds both the gate and the recorder (F2a fixture). */
    private inner class Rig(val test: TestScope) {
        init { opened += this }
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        var snapshot: TopicAccessSnapshot = snap(1)
        var sequence: ArrayDeque<TopicAccessSnapshot>? = null
        var snapshotReads = 0
        var fence: TopicSessionFence? = fenceN
        var protectedOpen = true
        var catalogNow: GraphCatalog? = catalog
        var clockNow: Instant = noon
        var clockReads = 0
        val revisions = MutableStateFlow(1L)

        private fun readSnapshot(): TopicAccessSnapshot {
            snapshotReads++
            val queue = sequence ?: return snapshot
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        val gate = GraphV2AccessGate({ identity }, { fence }, { readSnapshot() }, { protectedOpen })
        val recorder = GraphRecorder(
            scope, revisions, { readSnapshot() }, { fence }, { catalogNow }, gate, AppClock { clockReads++; clockNow }
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
        fun series(key: GraphObservationSeriesKey) = state().series.getValue(key)

        fun withKb(): Rig = apply {
            recorder.observe(kbAt(1341.7, "20:01:00"))
            assertTrue("premise: kb adopted", kb in state().series)
        }

        /** kb exists, then a refused observation with no catalog leaves `fx:usd-krw` as a loss without times. */
        fun withLostTopic(): Rig = withKb().apply {
            catalogNow = null
            protectedOpen = false
            recorder.observe(kbAt(1343.0, "20:01:40"))
            protectedOpen = true
            assertEquals("premise: a loss without times", setOf("fx:usd-krw"), state().pending.lostTopics)
            catalogNow = catalog
        }
    }

    private fun prices(s: GraphRecoverableState) = listOf(s.data.app.buckets, s.data.app.tip, s.data.app.observations, s.data.serverBuckets)

    private fun Rig.feed(input: TopicGraphInput) = when (input) {
        is TopicGraphInput.Observations -> recorder.observe(input)
        is TopicGraphInput.Continuity -> recorder.observe(input)
    }

    private fun Rig.reasonsAt(key: GraphObservationSeriesKey, start: Instant) = series(key).pending[start]?.reasons.orEmpty()

    // --- R1-R3: what a continuity fact does ------------------------------------------------------------------------------

    /**
     * R1: a resume in the held scope, with a catalog and nothing held, leaves RECEIVE_GAP over the 145-bucket demand window on
     * each existing series its topics map to - FX and the dollar index each - and on nothing else.
     */
    @Test fun R1_aResumeMarksTheWholeDemandWindowOnExistingSeries() = recorderTest {
        val rig = Rig(this).withKb()
        rig.recorder.observe(dollar(105.2, kst("20:01:05")))
        val kbData = prices(rig.series(kb))
        val dxyBefore = fingerprint(rig.series(dxy))

        rig.recorder.observe(resumed())
        assertEquals(window(noon), rig.series(kb).pending.keys)
        window(noon).forEach { assertTrue("$it", GraphRecoveryReason.RECEIVE_GAP in rig.reasonsAt(kb, it)) }
        assertEquals(window(noon), rig.series(kb).gapHistory)
        assertFalse("never the bucket after the current one", Instant.fromEpochSeconds(b.epochSeconds + 600) in rig.series(kb).pending)
        assertEquals("prices untouched", kbData, prices(rig.series(kb)))
        assertEquals(dxyBefore, fingerprint(rig.series(dxy)))
        assertEquals("no series is created or marked", setOf(kb, dxy), rig.state().series.keys)
        assertTrue(rig.state().untransferredSeries.isEmpty())

        val kbAfter = fingerprint(rig.series(kb))
        rig.recorder.observe(resumed(setOf("dxy:spot")))
        assertEquals(window(noon), rig.series(dxy).pending.keys)
        window(noon).forEach { assertTrue("$it", GraphRecoveryReason.RECEIVE_GAP in rig.reasonsAt(dxy, it)) }
        assertEquals(kbAfter, fingerprint(rig.series(kb)))

        val before = whole(rig.state())
        rig.recorder.observe(resumed(setOf("usdt:krw", "fx:jpy-krw")))
        assertEquals("topics that map to nothing", before, whole(rig.state()))
    }

    /** R2: every other kind, a null owner and another scope's owner only sync - with or without a catalog, nothing is held. */
    @Test fun R2_otherFactsOnlySync() = recorderTest {
        val cases = TopicGraphEventKind.entries.filter { it != TopicGraphEventKind.DELIVERY_RESUMED }.map { kind ->
            kind.name to event(kind, payload = when (kind) {
                TopicGraphEventKind.AUTH_FAILED -> TopicGraphEventPayload.AuthFailure(TopicAuthResolution.FAILED)
                TopicGraphEventKind.ACK_ACTIVE_SET_CHANGED -> TopicGraphEventPayload.AckApplied(emptySet(), emptyMap())
                else -> null
            })
        } + listOf("null owner" to resumed(owner = null, lifetime = null), "other scope" to resumed(owner = fenceN2))
        assertEquals(12, cases.size)
        for (withCatalog in listOf(true, false)) {
            for ((name, input) in cases) {
                val rig = Rig(this).withKb()
                if (!withCatalog) rig.catalogNow = null
                val before = whole(rig.state())
                rig.recorder.observe(input)
                assertEquals("$name catalog=$withCatalog", before, whole(rig.state()))
            }
        }
    }

    /** R3: without a catalog a resume in the held scope is held as given; another scope's is not; the budget still applies. */
    @Test fun R3_aResumeWithoutACatalogIsHeldAsGiven() = recorderTest {
        val rig = Rig(this)
        rig.catalogNow = null
        val resume = resumed()
        rig.recorder.observe(resume)
        assertSame(resume, rig.state().pending.inputs.single())
        assertTrue("held, not lost", rig.state().pending.lostTopics.isEmpty())
        rig.recorder.observe(resumed(owner = fenceN2))
        assertSame("another scope's resume is not held", resume, rig.state().pending.inputs.single())

        val full = Rig(this)
        full.catalogNow = null
        val big = fx(*Array(512) { quote("kb", 1340.0 + it / 100.0, Instant.fromEpochSeconds(kst("19:00:00").epochSeconds + it)) })
        full.recorder.observe(big)
        assertSame("premise: 512 units held", big, full.state().pending.inputs.single())
        val late = resumed()
        full.recorder.observe(late)
        assertSame(late, full.state().pending.inputs.single())
        assertEquals("the evicted batch is lost, as the budget says", setOf("fx:usd-krw"), full.state().pending.lostTopics)
    }

    // --- R4-R6: losses without times, their end, a normal start -----------------------------------------------------------

    /**
     * R4: a loss without times reaches existing kb over the whole window at once; investing and hana, not created yet, wait in
     * `untransferredSeries`; hana's first allowed observation gets INITIAL_SYNC and the window; kb is not marked again.
     */
    @Test fun R4_aLossWithoutTimesReachesSeriesNowOrWhenCreated() = recorderTest {
        val rig = Rig(this).withLostTopic()
        rig.recorder.observe(kbAt(1341.9, "20:01:20"))
        window(noon).forEach { assertTrue("$it", GraphRecoveryReason.HANDOVER_LOSS in rig.reasonsAt(kb, it)) }
        assertTrue(rig.state().pending.lostTopics.isEmpty())
        assertEquals(setOf("investing.usd", "hana.usd"), rig.state().untransferredSeries)
        assertTrue(GraphObservationId("kb", "usd-krw", kst("20:01:20"), 1341.9) in rig.series(kb).data.app.observations)

        val kbPrint = fingerprint(rig.series(kb))
        rig.recorder.observe(fx(quote("hana", 1342.5, kst("20:01:45"))))
        assertEquals(window(noon), rig.series(hana).pending.keys)
        window(noon).forEach { assertTrue("$it", GraphRecoveryReason.HANDOVER_LOSS in rig.reasonsAt(hana, it)) }
        assertTrue(GraphRecoveryReason.INITIAL_SYNC in rig.reasonsAt(hana, b))
        assertEquals(setOf("investing.usd"), rig.state().untransferredSeries)
        assertEquals("kb is not marked again", kbPrint, fingerprint(rig.series(kb)))
    }

    /** R5: a user end, a scope change or close empties `untransferredSeries`; a series created afterwards gets no old loss. */
    @Test fun R5_theWaitingSetEndsWithTheScope() = recorderTest {
        fun waiting(): Rig = Rig(this).withLostTopic().also {
            it.recorder.observe(kbAt(1341.9, "20:01:20"))
            assertEquals("premise", setOf("investing.usd", "hana.usd"), it.state().untransferredSeries)
        }

        waiting().let { rig ->
            rig.snapshot = snap(2, userEnd = 1)
            rig.recorder.observe(fx(quote("hana", 1342.5, kst("20:01:45"))))
            assertTrue(rig.state().untransferredSeries.isEmpty())
            assertEquals(setOf(b), rig.series(hana).pending.keys)
            assertEquals(setOf(GraphRecoveryReason.INITIAL_SYNC), rig.reasonsAt(hana, b))
        }
        waiting().let { rig ->
            rig.fence = fenceN2
            rig.recorder.observe(fx(quote("hana", 1342.5, kst("20:01:45")), owner = fenceN2))
            assertTrue(rig.state().untransferredSeries.isEmpty())
            assertEquals(setOf(b), rig.series(hanaN2).pending.keys)
            assertEquals(setOf(GraphRecoveryReason.INITIAL_SYNC), rig.reasonsAt(hanaN2, b))
        }
        waiting().let { rig ->
            rig.recorder.close()
            assertTrue(rig.state().untransferredSeries.isEmpty())
        }
    }

    /**
     * R6: a normal start - INITIAL, the first resume, the first observation - leaves only INITIAL_SYNC, whether the catalog is
     * there already or the inputs are held and replayed.
     */
    @Test fun R6_aNormalStartIsNotALoss() = recorderTest {
        for (catalogFirst in listOf(true, false)) {
            val rig = Rig(this)
            if (!catalogFirst) rig.catalogNow = null
            rig.recorder.observe(event(TopicGraphEventKind.INITIAL, at = kst("20:00:30")))
            rig.recorder.observe(resumed())
            rig.recorder.observe(kbAt(1341.7, "20:01:00"))
            if (!catalogFirst) {
                rig.catalogNow = catalog
                rig.recorder.observe(kbAt(1341.8, "20:01:10"))
                assertTrue("$catalogFirst", rig.state().pending.inputs.isEmpty())
            }
            assertEquals("$catalogFirst", setOf(b), rig.series(kb).pending.keys)
            assertEquals("$catalogFirst", setOf(GraphRecoveryReason.INITIAL_SYNC), rig.reasonsAt(kb, b))
            assertTrue("$catalogFirst", rig.series(kb).gapHistory.isEmpty())
            assertTrue("$catalogFirst", rig.state().pending.lostTopics.isEmpty() && rig.state().untransferredSeries.isEmpty())
        }
    }

    // --- R7-R10: replay ---------------------------------------------------------------------------------------------------

    /**
     * R7: held inputs replay in their order before the new input - a resume held after kb's first observation marks kb, one held
     * before it does not; the first adopted price keeps a same-time tie; a further call replays nothing again.
     */
    @Test fun R7_heldInputsReplayInOrderBeforeTheNewOne() = recorderTest {
        val o1 = kbAt(1341.7, "20:01:00")
        val o2 = kbAt(1342.0, "20:01:10")
        val o3 = kbAt(1343.0, "20:01:10")
        val o2Id = GraphObservationId("kb", "usd-krw", kst("20:01:10"), 1342.0)

        val after = Rig(this)
        after.catalogNow = null
        listOf(o1, resumed(), o2).forEach { after.feed(it) }
        assertEquals("premise: three held", 3, after.state().pending.inputs.size)
        after.catalogNow = catalog
        after.recorder.observe(o3)
        assertTrue(after.state().pending.inputs.isEmpty())
        window(noon).forEach { assertTrue("$it", GraphRecoveryReason.RECEIVE_GAP in after.reasonsAt(kb, it)) }
        assertEquals("the held price wins the tie", o2Id, after.series(kb).data.app.tip)
        val settled = fingerprint(after.series(kb))
        after.recorder.observe(event(TopicGraphEventKind.AUTH_RECOVERED))
        assertEquals("nothing replays twice", settled, fingerprint(after.series(kb)))

        val before = Rig(this)
        before.catalogNow = null
        listOf(resumed(), o1, o2).forEach { before.feed(it) }
        before.catalogNow = catalog
        before.recorder.observe(o3)
        assertEquals("a resume before kb existed marks nothing", setOf(b), before.series(kb).pending.keys)
        assertEquals(o2Id, before.series(kb).data.app.tip)
    }

    /**
     * R8: a held observation is re-bound on its own capture - refused once its lifetime has gone, so its price is dropped and
     * kb keeps a loss for its bucket; a held resume needs no bind and still marks the window.
     */
    @Test fun R8_replayRebindsObservationsOnly() = recorderTest {
        val rig = Rig(this).withKb()
        rig.catalogNow = null
        rig.recorder.observe(kbAt(1340.0, "19:51:00"))
        rig.recorder.observe(resumed())
        assertEquals("premise: both held", 2, rig.state().pending.inputs.size)
        rig.snapshot = snap(2, invalidations = 4L)
        rig.catalogNow = catalog
        rig.recorder.observe(kbAt(1341.9, "20:01:20", lifetime = l4))
        assertTrue(rig.state().pending.inputs.isEmpty())
        val observed = rig.series(kb).data.app.observations
        assertFalse("the refused held price is dropped", observed.any { it.rate == 1340.0 })
        assertTrue(GraphObservationId("kb", "usd-krw", kst("20:01:20"), 1341.9) in observed)
        assertTrue(GraphRecoveryReason.HANDOVER_LOSS in rig.reasonsAt(kb, a))
        window(noon).forEach { assertTrue("$it", GraphRecoveryReason.RECEIVE_GAP in rig.reasonsAt(kb, it)) }
    }

    /** R9: F2a's owner rules on the continuity path and on replay. */
    @Test fun R9_ownerRulesHoldOnTheNewPaths() = recorderTest {
        Rig(this).withKb().let { rig ->
            rig.record()
            val emitted = rig.emissions.size
            val reads = rig.clockReads
            rig.recorder.observe(resumed())
            assertEquals("one clock read", reads + 1, rig.clockReads)
            assertEquals("one publication", emitted + 1, rig.emissions.size)
        }
        Rig(this).withKb().let { rig ->
            rig.recorder.close()
            val reads = rig.snapshotReads
            val closed = rig.state()
            rig.recorder.observe(resumed())
            assertEquals("nothing is read after close", reads, rig.snapshotReads)
            assertSame(closed, rig.state())
        }
        Rig(this).withKb().let { rig ->
            rig.snapshot = snap(5)
            rig.recorder.observe(kbAt(1341.8, "20:01:10"))
            assertEquals(5L, rig.state().seenRevision)
            rig.snapshot = snap(4, userEnd = 9)
            rig.record()
            val emitted = rig.emissions.size
            val before = rig.state()
            rig.recorder.observe(resumed())
            assertSame(before, rig.state())
            assertEquals(emitted, rig.emissions.size)
        }
        Rig(this).withKb().let { rig ->
            rig.catalogNow = null
            rig.recorder.observe(kbAt(1340.0, "19:51:00"))
            rig.catalogNow = catalog
            rig.sequence = ArrayDeque(listOf(snap(1), snap(2, invalidations = 4L)))
            rig.recorder.observe(event(TopicGraphEventKind.AUTH_RECOVERED))
            assertFalse("the held capture is bound after the suppliers are read", rig.series(kb).data.app.observations.any { it.rate == 1340.0 })
            assertTrue(GraphRecoveryReason.HANDOVER_LOSS in rig.reasonsAt(kb, a))
        }
    }

    /**
     * R10: a replay that waited for the scope runs from the access collector once the held scope is current again; with nothing
     * held, the collector still hands a waiting loss without times over.
     */
    @Test fun R10_aWaitingReplayRunsWhenTheScopeReturns() = recorderTest {
        val rig = Rig(this)
        rig.catalogNow = null
        val held = kbAt(1341.7, "20:01:00")
        rig.recorder.observe(held)
        rig.catalogNow = catalog
        rig.fence = null
        rig.recorder.start()
        runCurrent()
        rig.publish(snap(2))
        runCurrent()
        assertSame("waits while there is no current scope", held, rig.state().pending.inputs.single())
        assertTrue(rig.state().series.isEmpty())

        rig.fence = fenceN
        rig.record()
        val emitted = rig.emissions.size
        rig.publish(snap(3))
        runCurrent()
        assertTrue(rig.state().pending.inputs.isEmpty())
        assertEquals(setOf(kb), rig.state().series.keys)
        assertEquals("sync and replay publish once", emitted + 1, rig.emissions.size)

        val lossOnly = Rig(this).withLostTopic()
        assertTrue("premise: nothing held", lossOnly.state().pending.inputs.isEmpty())
        lossOnly.recorder.start()
        runCurrent()
        assertTrue(lossOnly.state().pending.lostTopics.isEmpty())
        window(noon).forEach { assertTrue("$it", GraphRecoveryReason.HANDOVER_LOSS in lossOnly.reasonsAt(kb, it)) }
        assertEquals(setOf("investing.usd", "hana.usd"), lossOnly.state().untransferredSeries)
    }
}
