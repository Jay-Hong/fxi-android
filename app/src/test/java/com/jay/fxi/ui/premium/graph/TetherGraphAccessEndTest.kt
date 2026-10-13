package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantContext
import com.jay.fxi.data.entitlements.purge.GraphRecorderPurgeAdapter
import com.jay.fxi.data.entitlements.purge.PurgeCause
import com.jay.fxi.data.entitlements.purge.PurgeManifest
import com.jay.fxi.data.entitlements.purge.PurgeRequest
import com.jay.fxi.data.entitlements.purge.TargetOutcome
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphRecoveryTrigger
import com.jay.fxi.data.graph.GraphRuntimeRetirement
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.premium.graph.TetherGraphData.B
import com.jay.fxi.ui.premium.graph.TetherGraphData.DAY0
import com.jay.fxi.ui.premium.graph.TetherGraphData.EXCHANGES
import com.jay.fxi.ui.premium.graph.TetherGraphData.KRX
import com.jay.fxi.ui.premium.graph.TetherGraphData.TETHER_1D
import com.jay.fxi.ui.premium.graph.TetherGraphData.dayPoints
import com.jay.fxi.ui.premium.graph.TetherGraphData.ok
import com.jay.fxi.ui.premium.graph.TetherGraphData.series
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S5-2b-3 (JVM): T12 and the consumer boundary for the tether tab - after a sign-out, a UID change, a premium loss, a KRX
 * revocation or a purge, an old frame or REST completion recreates no range or price, the retry ends, and each screen
 * checks its own access - through the tether holder over the real request coordinator, graph recorder and gate.
 *
 * Agreed plan: R4c/S5 s5_2b3_plan.r1.md (Codex draft s5_2b3_plan_codex.r1.md, AGREE_PLAN s5_2b3_plan.r1.codex.md). Oracle: iOS
 * 89e866d GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md T12 and the agreed S5-2b scope (s5_2_scope_proposal.r3.md). The five
 * exchanges share one fixture (one tether request carries them all and an end acts on the whole scope,
 * GraphV2RequestCoordinator.kt:1188/:2003) and each is asserted on its own; the per-screen access row is holder-level and
 * runs once.
 *
 * "The retry ends" means no capture, send or application of the old scope or use resumes; a new scope's own request is
 * allowed. A released request may still be tracked until it completes; that is not a leak.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherGraphAccessEndTest {

    @get:Rule val folder = TemporaryFolder()

    private val opened = mutableListOf<TetherGraphFixture>()

    private fun endTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private fun fixture(test: TestScope) =
        TetherGraphFixture(test, folder, start = B + 1.minutes, recorderCollects = true).also { opened += it }

    private val oneDay = GraphKey("tether", GraphPeriod.ONE_DAY)
    private val A1 = B - 20.minutes
    private val A2 = B - 10.minutes
    private val S1 = GraphDataScope("u1", "e1")
    private val B_ID = AuthIdentityFence("u2", 1L)
    private fun id(exchange: String) = "$exchange.usdt-krw"
    /** Each exchange's own observed rate in B. */
    private fun rateOf(exchange: String) = 1340.0 + EXCHANGES.indexOf(exchange)

    private fun point(ts: Instant, rate: Double) = GraphV2Point(ts, rate, "x", rate + 0.5, rate - 0.5)

    /** The tether 1d response; every exchange gets closed A1 and A2 at [base] + its index when [base] is given. */
    private fun dayWith(base: Double? = null) = ok(GraphV2TabResponse(
        tab = "tether", period = "1d",
        series = TETHER_1D.map { sid ->
            val i = EXCHANGES.indexOf(sid.substringBefore(".usdt-krw"))
            series(sid, if (base != null && i >= 0) listOf(point(A1, base + i), point(A2, base + i)) else dayPoints(sid))
        },
        metadata = GraphV2Metadata(B - 1.minutes, "10min", GraphV2Range("2026-10-05", "2026-10-05"),
            domainStartAt = DAY0, domainEndAt = DAY0 + 1440.minutes, liveDomainMode = "rolling")
    ))

    private fun <T> status(code: Int): AuthenticatedHttpResponse<T> = AuthenticatedHttpResponse(
        code, Headers.headersOf(), null,
        AuthenticatedHttpFailure(code, Headers.headersOf(), byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP),
        byteArrayOf(1)
    )

    /** A published access like the fixture's default (revision 100, invalidations 3) with the given fields changed. */
    private fun access(
        revision: Long,
        uid: String? = "u1",
        token: Long? = 7L,
        epoch: String? = "e1",
        krx: String? = "K1",
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        invalidations: Long = 3L,
        userEnd: TopicAccessEnd? = null,
        capabilityEnd: TopicAccessEnd? = null
    ): TopicAccessSnapshot {
        val binding = uid?.let { EntitlementsIdentity(it, 1L) }
        val record = uid?.let { AccessFence(it, epoch, krx) }
        val issued = if (token != null && binding != null && record != null) TopicGrantContext(binding, record, 10L) else null
        return TopicAccessSnapshot(
            revision = revision,
            facts = TopicAccessFacts.NONE.copy(
                token = token?.let(::TopicGrantToken), issuedFor = issued, binding = binding, recordFence = record,
                decisionGeneration = 10L, tokenStanding = issued != null, userBlocks = userBlocks,
                capabilityBlocks = capabilityBlocks
            ),
            userInvalidations = invalidations,
            lastUserEnd = userEnd,
            lastCapabilityEnd = capabilityEnd,
            userEndInvalidationsFloor = if (userEnd != null) invalidations else 0L
        )
    }

    private enum class End { SIGN_OUT, UID_CHANGE, PREMIUM_LOSS }

    /**
     * Ends u1's access as published - identity, fence and snapshot together - and notifies nobody: a test reads the checked
     * state first, then [notify]s.
     */
    private fun TetherGraphFixture.endUser(end: End) {
        val ended = TopicAccessEnd(1L, when (end) {
            End.SIGN_OUT -> TopicAccessEndReason.SIGNED_OUT
            End.UID_CHANGE -> TopicAccessEndReason.IDENTITY_CHANGED
            End.PREMIUM_LOSS -> TopicAccessEndReason.AUTHORITATIVE_LOSS
        }, EntitlementsIdentity("u1", 1L), "u1", "e1")
        when (end) {
            End.SIGN_OUT -> {
                identity = null
                fence = null
                snapshot = access(101L, uid = null, token = null, epoch = null, krx = null,
                    userBlocks = setOf(TopicAccessBlock.NO_BINDING), invalidations = 4L, userEnd = ended)
            }
            End.UID_CHANGE -> {
                identity = B_ID
                fence = TopicSessionFence(B_ID, "f1", TopicGrantToken(8L))
                snapshot = access(101L, uid = "u2", token = 8L, epoch = "f1", krx = "K9", invalidations = 4L, userEnd = ended)
            }
            End.PREMIUM_LOSS -> {
                fence = null
                snapshot = access(101L, token = null, userBlocks = setOf(TopicAccessBlock.NOT_GRANTED),
                    invalidations = 4L, userEnd = ended)
            }
        }
    }

    private fun TetherGraphFixture.notify() {
        accessRevisions.value += 1
        run()
    }

    private fun TetherGraphFixture.key(exchange: String) = GraphObservationSeriesKey(S1, id(exchange))
    private fun TetherGraphFixture.u1Series() = recorder.state.value.series.keys.filter { it.scope == S1 }
    private fun TetherGraphFixture.tabSends() = sent.filter { it.key == oneDay }
    private fun TetherGraphFixture.waiting() = sent.filter { it.key == oneDay && !it.answer.isCompleted }

    /**
     * Opens tether in the foreground, answers 1d, then gives each exchange its own observation in B and closed demands on A1
     * and A2; the chart shows each exchange's rate.
     */
    private fun TetherGraphFixture.readyAll() {
        start()
        collectPublications()
        activate("tether")
        answer(oneDay, dayWith())
        show("tether")
        events.onForeground(true)
        for (e in EXCHANGES) {
            exchange(e, rateOf(e), B + 30.seconds)
            lose(e, A1 + 30.seconds)
            lose(e, A2 + 30.seconds)
        }
        advance(400)
        val chart = checkNotNull(state("tether").chart) { "premise: a chart" }
        for (e in EXCHANGES) {
            assertEquals("$e premise: A1 and A2 owed", setOf(A1, A2),
                recorder.state.value.series.getValue(key(e)).pending.keys.filter { it < B }.toSet())
            assertTrue("$e premise: its rate is shown", chart.prepared.bySeries.getValue(id(e)).linePoints.any { it.rate == rateOf(e) })
        }
    }

    // --- U: USER ends --------------------------------------------------------------------------------------------------

    private enum class Pending { RETRY, CAPTURE, FETCH }

    /**
     * U (five exchanges in one fixture): a sign-out (with a 503-scheduled retry, a held capture and a held fetch), a UID
     * change and a premium loss (each with the scheduled retry). Before the notification the checked state has no chart;
     * after it the recorder holds no u1 series, the coordinator no entry, and no publication shows a chart. An old frame of
     * each exchange, an old REST completion, an old trigger and the old token recreate nothing, and no capture or send of u1
     * resumes, before, at and after the old retry deadline. After the UID change u2's own answer draws its own prices.
     */
    @Test fun U_aUserEndRecreatesNothingAndEndsTheRetry() = endTest {
        val cases = listOf(End.SIGN_OUT to Pending.RETRY, End.SIGN_OUT to Pending.CAPTURE, End.SIGN_OUT to Pending.FETCH,
            End.UID_CHANGE to Pending.RETRY, End.PREMIUM_LOSS to Pending.RETRY)
        for ((end, pending) in cases) {
            val label = "$end $pending"
            val f = fixture(this)
            f.readyAll()
            val oldAttribution = f.attribution()
            val oldTrigger = GraphRecoveryTrigger(GraphRecoveryTrigger.Kind.FOREGROUND_RETURN, 1_000L, checkNotNull(f.fence), f.lifetime)
            val oldToken = f.token("tether")
            val h = f.holders.getValue("tether")
            h.enterFullscreen(oldToken); f.run()
            val oldFull = checkNotNull(h.currentState().fullscreenToken)
            h.setSurfaceVisible(oldFull, true); f.run()
            val capturedHold = CompletableDeferred<Unit>()
            var deadline: Long? = null
            when (pending) {
                Pending.RETRY -> {
                    f.coordinator.onRefreshRequested(force = true); f.run()
                    f.waiting().single().answer.complete(status(503)); f.run()
                    deadline = f.elapsedMs + 3_000
                }
                Pending.CAPTURE -> {
                    val before = f.captures.size
                    f.captureHook = { n -> if (n == before + 1) capturedHold.await() }
                    f.coordinator.onRefreshRequested(force = true); f.run()
                    assertEquals("$label premise: the capture is held", before + 1, f.captures.size)
                }
                Pending.FETCH -> {
                    f.coordinator.onRefreshRequested(force = true); f.run()
                    assertEquals("$label premise: the fetch waits", 1, f.waiting().size)
                }
            }
            val sendsBefore = f.tabSends().size
            val u1CapturesBefore = f.captureOwners.count { it == "u1" }
            val committed = f.selections.committed.toMap()
            // A recorder update schedules a publication without advancing its 350 ms delay.
            f.exchange(EXCHANGES.last(), 1399.0, B + 45.seconds)
            assertFalse("$label premise: the new observation is not published yet",
                checkNotNull(h.currentState().chart).prepared.bySeries.getValue(id(EXCHANGES.last()))
                    .linePoints.any { it.rate == 1399.0 })
            f.endUser(end)
            val checked = h.currentState()
            assertNull("$label: the checked state draws nothing before the notification", checked.chart)
            assertNull("$label: no inline token before the notification", checked.inlineToken)
            assertNull("$label: no fullscreen token before the notification", checked.fullscreenToken)
            assertFalse("$label: fullscreen is closed before the notification", checked.fullscreenOpen)
            val mark = f.published.getValue("tether").size
            f.notify()
            assertEquals("$label: the recorder holds no u1 series", emptyList<GraphObservationSeriesKey>(), f.u1Series())
            assertNull("$label: no entry", f.coordinator.state.value.entries[oneDay])
            assertTrue("$label: no chart after the end", f.published.getValue("tether").drop(mark).all { it.chart == null })

            when (pending) {
                Pending.CAPTURE -> { capturedHold.complete(Unit); f.run() }
                Pending.FETCH -> { f.sent.first { it.key == oneDay && !it.answer.isCompleted }.answer.complete(dayWith(1500.0)); f.run() }
                Pending.RETRY -> Unit
            }
            for (e in EXCHANGES) f.exchangeAs(oldAttribution, e, 1399.0, B + 2.minutes)
            f.coordinator.onRecoveryTrigger(oldTrigger); f.run()
            h.toggleSeries(oldToken, id(EXCHANGES.last()))
            h.toggleSeries(oldFull, id(EXCHANGES.last())); f.run()
            if (deadline != null) {
                for (at in listOf(deadline - 1, deadline, deadline + 1)) {
                    f.advance(at - f.elapsedMs)
                    assertEquals("$label @${at}: no u1 capture (${f.captureOwners})", u1CapturesBefore, f.captureOwners.count { it == "u1" })
                }
            } else {
                f.advance(10_000)
            }
            f.advance(350)
            assertNull("$label: no entry is recreated", f.coordinator.state.value.entries[oneDay])
            assertEquals("$label: no further u1 capture", u1CapturesBefore,
                f.captureOwners.count { it == "u1" })
            assertEquals("$label: no u1 send", 0, f.tabSends().drop(sendsBefore).count { it.ownerUid == "u1" })
            assertEquals("$label: still no u1 series", emptyList<GraphObservationSeriesKey>(), f.u1Series())
            assertTrue("$label: no chart", f.published.getValue("tether").drop(mark).all { it.chart == null })
            assertEquals("$label: the old token wrote nothing", committed, f.selections.committed.toMap())

            if (end == End.UID_CHANGE) {
                val ownerB = TopicDisplayOwner(B_ID, 1L)
                f.display.value = TopicDisplayState.NONE.copy(owner = ownerB)
                f.focus.value = OwnedTopicFocus(B_ID, FreeTab.TETHER)
                f.run()
                f.holders.getValue("tether").onActivated(ownerB); f.run()
                val u2 = f.sent.filter { it.key == oneDay && !it.answer.isCompleted && it.ownerUid == "u2" }
                assertEquals("$label: u2's own request", 1, u2.size)
                u2.single().answer.complete(dayWith(1600.0)); f.run()
                val chart = checkNotNull(f.state("tether").chart) { "$label: u2 draws" }
                for (e in EXCHANGES) {
                    val s = chart.prepared.bySeries.getValue(id(e))
                    assertEquals("$label $e: u2's A1", 1600.0 + EXCHANGES.indexOf(e), s.linePoints.first { it.ts == A1 }.rate, 0.0)
                    assertFalse("$label $e: no u1 value", s.linePoints.any { it.rate == rateOf(e) || it.rate == 1399.0 })
                }
            }
            f.close()
        }
    }

    // --- K: a KRX revocation ---------------------------------------------------------------------------------------

    /** Hides, goes to the background for [ms] and comes back with a real return, then shows again. */
    private fun TetherGraphFixture.awayAndBack(ms: Long) {
        hide("tether"); events.onForeground(false)
        advance(ms)
        events.onForeground(true); show("tether")
    }

    private fun TetherGraphFixture.owed(exchange: String) =
        recorder.state.value.series.getValue(key(exchange)).pending.keys.filter { it < B }.toSet()

    /**
     * K (five exchanges in one fixture): with a request of the old use in flight, KRX is revoked (K1 -> K2 not granted, the
     * old use lifetime invalidated) and the capability port runs, while a 503 has left a retry due in 3 s. Afterwards no chart
     * shows KRX or offers its toggle; every exchange keeps its observation and its A1/A2 demand; the old completion applies
     * nothing. The retry is kept: at its deadline a recovery request under the renewed use releases each exchange's A1/A2
     * (the positive control), still without KRX.
     */
    @Test fun K_aKrxRevocationKeepsTheExchanges() = endTest {
        val f = fixture(this)
        f.readyAll()
        assertTrue("premise: KRX is offered", f.state("tether").toggles.any { it.seriesId == KRX })
        // A failed request leaves the retry (a budget due in 3 s); a forced request of the old use is then in flight.
        f.coordinator.onRefreshRequested(force = true); f.run()
        f.waiting().single().answer.complete(status(503)); f.run()
        val deadline = f.elapsedMs + 3_000
        f.coordinator.onRefreshRequested(force = true); f.run()
        val old = f.waiting().single()
        val oldToken = f.token("tether")
        f.snapshot = access(101L, krx = "K2", capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L,
            capabilityEnd = TopicAccessEnd(1L, TopicAccessEndReason.CAPABILITY_REVOKED, EntitlementsIdentity("u1", 1L), "u1", null))
        val result = withContext(f.dispatcher) { f.port.retireCapabilities { it.krxCapabilityEpoch == "K1" } }
        assertEquals(GraphRuntimeRetirement.REMOVED, result)
        f.notify()
        // The retired context dropped the surface report; the screen reports its new token as visible again.
        f.show("tether")
        val renewed = checkNotNull(f.state("tether").chart) { "the renewed use draws" }
        assertFalse("no KRX drawn", KRX in renewed.renderedIds)
        assertFalse("no KRX toggle", f.state("tether").toggles.any { it.seriesId == KRX })
        for (e in EXCHANGES) {
            assertEquals("$e: the demand is kept", setOf(A1, A2), f.owed(e))
            assertTrue("$e: its observation is kept", renewed.prepared.bySeries.getValue(id(e)).linePoints.any { it.rate == rateOf(e) })
        }
        val entryBeforeOldCompletion = f.coordinator.state.value.entries[oneDay]
        old.answer.complete(dayWith(1500.0)); f.run()
        assertEquals("the old completion preserves the renewed entry", entryBeforeOldCompletion,
            f.coordinator.state.value.entries[oneDay])
        assertEquals("the old completion preserves the renewed chart", renewed,
            f.holders.getValue("tether").state.value.chart)
        val committedAfterRenewal = f.selections.committed.toMap()
        f.holders.getValue("tether").toggleSeries(oldToken, id(EXCHANGES.last())); f.run()
        assertEquals("the retired KRX use's token writes nothing",
            committedAfterRenewal, f.selections.committed.toMap())
        for (e in EXCHANGES) assertEquals("$e: the old completion applies nothing", setOf(A1, A2), f.owed(e))
        // The KRX retirement keeps the retry: nothing goes before its deadline, and at it the round (asking for the catalog
        // the same-scope change dropped) sends one recovery request under the renewed use.
        val sends = f.tabSends().size
        f.advance(deadline - 1 - f.elapsedMs)
        assertEquals("nothing before the kept deadline", sends, f.tabSends().size)
        f.advance(1)
        assertEquals("the kept retry sends at its deadline", sends + 1, f.tabSends().size)
        assertEquals("the kept retry sends at its deadline", deadline, f.tabSends().last().atMs)
        val recovery = f.waiting()
        assertEquals("the kept retry's recovery request under the renewed use", 1, recovery.size)
        recovery.single().answer.complete(dayWith(1600.0)); f.run()
        val chart = checkNotNull(f.state("tether").chart)
        for (e in EXCHANGES) {
            assertEquals("$e: released by the current answer", emptySet<Instant>(), f.owed(e))
            assertEquals("$e: its A1 is drawn", 1600.0 + EXCHANGES.indexOf(e), chart.prepared.bySeries.getValue(id(e)).linePoints.first { it.ts == A1 }.rate, 0.0)
        }
        assertFalse("still no KRX", KRX in chart.renderedIds)
        assertFalse("still no KRX toggle", f.state("tether").toggles.any { it.seriesId == KRX })
    }

    // --- C: each screen checks its own access ----------------------------------------------------------------------

    private enum class Refusal { OWNER, FOCUS, LIFETIME, ADMISSION, LATE_WITHDRAWAL }

    /**
     * C (gopax's series among the five, once): with the coordinator's entry and the recorder's series in place, the tether
     * holder's owner, focus, use lifetime or protected admission is refused, or admission is withdrawn at the last read of a
     * render (the re-read before publication). Its checked state, inline and fullscreen, draws nothing and its old tokens write nothing while the shared
     * data is still there. For the owner, a second tether holder of the new owner draws from the same data meanwhile.
     */
    @Test fun C_eachScreenChecksItsOwnAccess() = endTest {
        for (refusal in Refusal.values()) {
            val label = "$refusal"
            val f = fixture(this)
            f.readyAll()
            val h = f.holders.getValue("tether")
            h.enterFullscreen(f.token("tether")); f.run()
            val full = checkNotNull(f.state("tether").fullscreenToken) { "$label premise: fullscreen" }
            h.setSurfaceVisible(full, true); f.run()
            val inline = f.token("tether")
            val committed = f.selections.committed.toMap()
            when (refusal) {
                Refusal.OWNER -> f.display.value = TopicDisplayState.NONE.copy(owner = TopicDisplayOwner(TetherGraphData.A, 2L))
                Refusal.FOCUS -> f.focus.value = OwnedTopicFocus(TetherGraphData.A, FreeTab.USD)
                Refusal.LIFETIME -> f.snapshot = access(101L, invalidations = 4L)
                Refusal.ADMISSION -> f.protectedOpen = false
                Refusal.LATE_WITHDRAWAL -> {
                    // Count one render's admission reads, then close only the last one: the re-read before publication.
                    val before = f.admissionReads
                    h.currentState()
                    val reads = f.admissionReads - before
                    assertTrue("$label premise: a render reads admission more than once ($reads)", reads >= 2)
                    f.admissionClosesAfter = f.admissionReads + reads - 1
                }
            }
            assertNotNull("$label premise: the shared entry is there", f.coordinator.state.value.entries[oneDay])
            assertTrue("$label premise: the shared series are there", EXCHANGES.all { f.key(it) in f.recorder.state.value.series })
            val checked = h.currentState()
            assertNull("$label: the checked state draws nothing", checked.chart)
            assertNull("$label: no inline token", checked.inlineToken)
            assertNull("$label: no fullscreen token", checked.fullscreenToken)
            h.toggleSeries(inline, id("gopax")); h.toggleSeries(full, id("gopax")); f.run()
            assertEquals("$label: the old tokens write nothing", committed, f.selections.committed.toMap())
            if (refusal == Refusal.OWNER) {
                val second = f.extraHolder("tether")
                second.onActivated(TopicDisplayOwner(TetherGraphData.A, 2L)); f.run()
                second.setSurfaceVisible(checkNotNull(second.currentState().inlineToken) { "$label: the new owner's token" }, true); f.run()
                assertNull("$label: the refused holder still draws nothing", h.currentState().chart)
                val drawn = checkNotNull(second.currentState().chart) { "$label: the new owner's holder draws" }
                assertTrue("$label: from the same data", drawn.prepared.bySeries.getValue(id("gopax")).linePoints.any { it.rate == rateOf("gopax") })
            }
            f.close()
        }
    }

    // --- RT: runtime cleanup after a purge -------------------------------------------------------------------------

    private val E2 = TopicSessionFence(TetherGraphData.A, "e2", TopicGrantToken(8L))

    private fun purgeE1() = PurgeRequest(
        checkNotNull(PurgeManifest.byId(GraphRecorderPurgeAdapter.TARGET_ID)), PurgeScope.USER, PurgeCause.UNKNOWN,
        PurgeNamespace("u1", "e2", null, PendingPurge("u1", "e1", null, setOf(PurgeScope.USER)))
    )

    /**
     * RT-A (five exchanges): e1's entry, slot, budget and screen are in place when the live fence moves to e2 unannounced;
     * the recorder purge and then the runtime port run before any context event. On the port's return the coordinator holds
     * no e1 entry and the screen draws nothing; e1's old completion, frames and deadline bring nothing back; a second
     * retirement removes nothing.
     */
    @Test fun RT_A_aPurgeThenTheRuntimePortRemoveTheSelectedScope() = endTest {
        val f = fixture(this)
        f.readyAll()
        f.coordinator.onRefreshRequested(force = true); f.run()
        f.waiting().single().answer.complete(status(503)); f.run()
        val deadline = f.elapsedMs + 3_000
        f.coordinator.onRefreshRequested(force = true); f.run()
        val oldAttribution = f.attribution()
        f.fence = E2
        f.snapshot = access(101L, token = 8L, epoch = "e2", invalidations = 4L)
        // One task on the fixture's dispatcher: the purge runs inline and no collector re-renders the screen before the port.
        val (result, at) = withContext(f.dispatcher) {
            assertEquals(TargetOutcome.Removed, f.purgeAdapter.purge(purgeE1()))
            assertNotNull("premise: the screen still shows e1 before the port", f.holders.getValue("tether").state.value.chart)
            f.port.retireScopes { it == S1 } to f.holders.getValue("tether").state.value
        }
        assertEquals(GraphRuntimeRetirement.REMOVED, result)
        assertNull("the screen leaves in the port's block", at.chart)
        assertNull("no e1 entry", f.coordinator.state.value.entries[oneDay])
        assertEquals(emptyList<GraphObservationSeriesKey>(), f.u1Series())
        val mark = f.published.getValue("tether").size
        f.sent.filter { it.key == oneDay && !it.answer.isCompleted }.forEach { it.answer.complete(dayWith(1500.0)) }
        for (e in EXCHANGES) f.exchangeAs(oldAttribution, e, 1399.0, B + 2.minutes)
        f.advance(deadline + 1 - f.elapsedMs)
        assertEquals("nothing of e1 comes back", emptyList<GraphObservationSeriesKey>(), f.u1Series())
        assertTrue("no e1 chart", f.published.getValue("tether").drop(mark).none { s ->
            s.chart?.prepared?.bySeries?.values?.any { series -> series.linePoints.any { it.rate == 1399.0 || it.rate in 1500.0..1504.0 } } == true
        })
        val again = withContext(f.dispatcher) { f.port.retireScopes { it == S1 } }
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, again)
    }

    /**
     * RT-B (five exchanges): an e1 fetch is held while e2 becomes current normally; then e1 is purged and retired. The purge
     * finds nothing (it covers registered captures, not a released request, GraphV2RequestCoordinator.kt:510-516) and the
     * runtime port retires the released e1 request. Whether e1's late completion lands before or after e2's request
     * completes, e2's entry and chart stay.
     */
    @Test fun RT_B_aLateE1CompletionLeavesE2() = endTest {
        for (order in listOf("e1-after-e2", "e1-before-e2")) {
            val f = fixture(this)
            f.readyAll()
            f.coordinator.onRefreshRequested(force = true); f.run()
            val e1Fetch = f.waiting().single()
            f.fence = E2
            f.snapshot = access(101L, token = 8L, epoch = "e2", invalidations = 4L)
            f.notify()
            val e2Fetch = f.sent.last { it.key == oneDay && !it.answer.isCompleted && it !== e1Fetch }
            if (order == "e1-after-e2") { e2Fetch.answer.complete(dayWith(1600.0)); f.run() }
            val entryBefore = f.coordinator.state.value.entries[oneDay]
            val chartBefore = f.state("tether").chart
            if (order == "e1-after-e2") {
                assertNotNull("$order premise: e2's entry", entryBefore)
                assertNotNull("$order premise: e2's chart", chartBefore)
            }
            val mark = f.published.getValue("tether").size
            // The purge covers captures of registered requests only; the released e1 fetch is runtime cleanup (:510-516).
            assertEquals("$order: the purge leaves the released fetch", TargetOutcome.NothingToRemove, f.purgeAdapter.purge(purgeE1()))
            assertEquals("$order: the released e1 request is retired", GraphRuntimeRetirement.REMOVED,
                withContext(f.dispatcher) { f.port.retireScopes { it == S1 } })
            if (order == "e1-after-e2") {
                assertEquals("$order: port preserves e2's entry", entryBefore,
                    f.coordinator.state.value.entries[oneDay])
                assertEquals("$order: port preserves e2's publication", chartBefore,
                    f.holders.getValue("tether").state.value.chart)
            }
            e1Fetch.answer.complete(dayWith(1500.0)); f.run()
            assertEquals("$order: the late e1 completion preserves the entry", entryBefore,
                f.coordinator.state.value.entries[oneDay])
            assertEquals("$order: the late e1 completion preserves the publication", chartBefore,
                f.holders.getValue("tether").state.value.chart)
            if (order == "e1-before-e2") {
                assertTrue("$order: e2's registration remains in flight",
                    oneDay in f.coordinator.state.value.inFlight)
            }
            if (order == "e1-before-e2") { e2Fetch.answer.complete(dayWith(1600.0)); f.run() }
            assertTrue("$order: no publication adopts the late e1 prices",
                f.published.getValue("tether").drop(mark).none { s ->
                    s.chart?.prepared?.bySeries?.values?.any { series ->
                        series.linePoints.any { it.rate in 1500.0..1504.0 }
                    } == true
                })
            if (order == "e1-after-e2") {
                assertTrue("$order: every publication preserves e2's chart",
                    f.published.getValue("tether").drop(mark).all { it.chart == chartBefore })
            }
            assertNotNull("$order: e2's entry", f.coordinator.state.value.entries[oneDay])
            val chart = checkNotNull(f.state("tether").chart) { "$order: e2 draws" }
            for (e in EXCHANGES) {
                assertEquals("$order $e: e2's A1", 1600.0 + EXCHANGES.indexOf(e), chart.prepared.bySeries.getValue(id(e)).linePoints.first { it.ts == A1 }.rate, 0.0)
            }
            f.close()
        }
    }

    /**
     * RT-C (gopax's series among the five): a failed request leaves e1's budget; the fence goes away and the context with it,
     * so the budget is held alone. A selected retirement removes it, a second finds nothing; an unselected one keeps it, and
     * when the fence returns its deadline is unchanged.
     */
    @Test fun RT_C_aBudgetHeldAlone() = endTest {
        for (selected in listOf(true, false)) {
            val f = fixture(this)
            f.readyAll()
            f.coordinator.onRefreshRequested(force = true); f.run()
            f.waiting().single().answer.complete(status(503)); f.run()
            val deadline = f.elapsedMs + 3_000
            val sends = f.tabSends().size
            f.fence = null
            f.coordinator.onContextChanged(); f.run()
            if (selected) {
                assertEquals(GraphRuntimeRetirement.REMOVED, withContext(f.dispatcher) { f.port.retireScopes { it == S1 } })
                assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, withContext(f.dispatcher) { f.port.retireScopes { it == S1 } })
            } else {
                assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, withContext(f.dispatcher) { f.port.retireScopes { it.userAccessEpoch == "e9" } })
                f.fence = TetherGraphData.FENCE_A
                f.coordinator.onContextChanged(); f.run()
                f.advance(deadline - 1 - f.elapsedMs)
                assertEquals("the kept budget still waits", sends, f.tabSends().size)
                f.advance(1)
                assertEquals("its deadline is unchanged", sends + 1, f.tabSends().size)
            }
            f.close()
        }
    }
}
