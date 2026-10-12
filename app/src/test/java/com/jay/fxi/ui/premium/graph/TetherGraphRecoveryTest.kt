package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphRecoveryTrigger
import com.jay.fxi.domain.model.GraphTabAdmission
import com.jay.fxi.data.graph.GraphV2Domain
import com.jay.fxi.data.graph.GraphV2GeneralKey
import com.jay.fxi.data.graph.GraphV2Validation
import com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec
import com.jay.fxi.data.graph.splitGraphV2ServerTab
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
import com.jay.fxi.data.remote.dto.GraphV2InProgressSeed
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.premium.graph.TetherGraphData.B
import com.jay.fxi.ui.premium.graph.TetherGraphData.DAY0
import com.jay.fxi.ui.premium.graph.TetherGraphData.EXCHANGES
import com.jay.fxi.ui.premium.graph.TetherGraphData.FENCE_A
import com.jay.fxi.ui.premium.graph.TetherGraphData.LONG_TETHER
import com.jay.fxi.ui.premium.graph.TetherGraphData.TETHER_1D
import com.jay.fxi.ui.premium.graph.TetherGraphData.USD_1D
import com.jay.fxi.ui.premium.graph.TetherGraphData.catalog
import com.jay.fxi.ui.premium.graph.TetherGraphData.day
import com.jay.fxi.ui.premium.graph.TetherGraphData.dayPoints
import com.jay.fxi.ui.premium.graph.TetherGraphData.long
import com.jay.fxi.ui.premium.graph.TetherGraphData.ok
import com.jay.fxi.ui.premium.graph.TetherGraphData.series
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S5-2b-2 (JVM): T11 for the tether tab - after a return, a cache hit, a cooldown, a request in flight, a failure or a
 * cancellation, an exchange's recovery demand does not disappear before an actual response supplying its bucket is applied,
 * and the next active owner carries it on. Through the tether holder over the real request coordinator, graph recorder and
 * recovery-event producer.
 *
 * Agreed plan: R4c/S5 s5_2b2_plan.r2.md (Codex draft s5_2b2_plan_codex.r1.md, review s5_2b2_plan.r1.codex.md). Oracle: iOS
 * 89e866d GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md T11. The rows whose subject is exchange-specific mapping - a request in
 * flight (I), a partial answer (P) and the hand-over (H) - run for each of the five exchanges; the common policies (cache,
 * time conditions, floor, failure ladder, withdrawal) run once for gopax, outside the default selection. The budget is per
 * (data scope, tab) and one tether request carries every exchange (GraphV2RequestCoordinator.kt:147-155); its ladder is
 * tab-independent (:1873-1874).
 *
 * Inputs: the fixture starts at 12:01 KST, inside B = 12:00-12:10, and every row ends before 12:10 unless it crosses on
 * purpose. The exchange's series is made by a normal quote in B (its INITIAL_SYNC demand on B is the current bucket and counts
 * for no round); refused quotes then leave HANDOVER_LOSS demands on the closed buckets A1 = 11:40 and A2 = 11:50
 * (GraphRecorderReducer.kt:196-201). A response releases a demand only for a closed point it supplies, of a generation no
 * newer than its capture (GraphRecoveryReducer.kt:140-148).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherGraphRecoveryTest {

    @get:Rule val folder = TemporaryFolder()

    private val opened = mutableListOf<TetherGraphFixture>()

    private fun recoveryTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private fun fixture(test: TestScope, sharedPermit: Boolean = false) =
        TetherGraphFixture(test, folder, start = B + 1.minutes, sharedPermit = sharedPermit).also { opened += it }

    private val oneDay = GraphKey("tether", GraphPeriod.ONE_DAY)
    private val A1 = B - 20.minutes
    private val A2 = B - 10.minutes
    private fun id(exchange: String) = "$exchange.usdt-krw"

    private fun point(ts: Instant, rate: Double = 1341.0) = GraphV2Point(ts, rate, "x", rate + 0.5, rate - 0.5)

    /** The tether 1d response; [points] replace a series' default points by closed points at the given starts. */
    private fun dayWith(points: Map<String, List<Instant>> = emptyMap(), seeds: Map<String, GraphV2InProgressSeed> = emptyMap()) =
        ok(GraphV2TabResponse(
            tab = "tether", period = "1d",
            series = TETHER_1D.map { sid -> series(sid, points[sid]?.map { point(it) } ?: dayPoints(sid)) },
            metadata = GraphV2Metadata(B - 1.minutes, "10min", GraphV2Range("2026-10-05", "2026-10-05"),
                domainStartAt = DAY0, domainEndAt = DAY0 + 1440.minutes, liveDomainMode = "rolling"),
            inProgress = seeds
        ))

    private fun supplying(exchange: String, vararg starts: Instant) = dayWith(mapOf(id(exchange) to starts.toList()))

    private fun <T> status(code: Int, retryAfter: String? = null): AuthenticatedHttpResponse<T> {
        val headers = if (retryAfter == null) Headers.headersOf() else Headers.headersOf("Retry-After", retryAfter)
        return AuthenticatedHttpResponse(
            code, headers, null,
            AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP),
            byteArrayOf(1)
        )
    }

    private fun TetherGraphFixture.key(exchange: String) = GraphObservationSeriesKey(GraphDataScope("u1", "e1"), id(exchange))
    private fun TetherGraphFixture.pending(exchange: String) = recorder.state.value.series.getValue(key(exchange)).pending
    /** The exchange's demands on closed buckets (before B). */
    private fun TetherGraphFixture.owed(exchange: String) = pending(exchange).keys.filter { it < B }.toSet()
    private fun TetherGraphFixture.tabSends() = sent.filter { it.key == oneDay }
    private fun TetherGraphFixture.waiting() = sent.filter { it.key == oneDay && !it.answer.isCompleted }
    private fun TetherGraphFixture.sendTimes() = tabSends().map { it.atMs }

    private fun TetherGraphFixture.selectOnly(exchange: String) {
        val h = holders.getValue("tether")
        val visible = checkNotNull(state("tether").selection).visibleSeriesIds
        for (other in visible - id(exchange)) { h.toggleSeries(token("tether"), other); run() }
        if (id(exchange) !in checkNotNull(state("tether").selection).visibleSeriesIds) { h.toggleSeries(token("tether"), id(exchange)); run() }
        assertEquals("premise: only $exchange", setOf(id(exchange)), state("tether").selection!!.visibleSeriesIds)
    }

    /** Starts, collects, activates tether, answers 1d (an online entry) and shows [exchange] alone in the foreground. */
    private fun TetherGraphFixture.open(exchange: String) {
        start()
        collectPublications()
        activate("tether")
        answer(oneDay, dayWith())
        selectOnly(exchange)
        show("tether")
        events.onForeground(true)
    }

    /** [open], the exchange's series in B, then refused quotes in A1 and A2: both closed buckets owed, no budget yet. */
    private fun TetherGraphFixture.ready(exchange: String) {
        open(exchange)
        exchange(exchange, 1340.0, B + 30.seconds)
        lose(exchange, A1 + 30.seconds)
        lose(exchange, A2 + 30.seconds)
        assertEquals("premise: A1 and A2 owed", setOf(A1, A2), owed(exchange))
        assertEquals("premise: only the opening request", 1, tabSends().size)
    }

    /** Hides, goes to the background for [ms] and comes back with a real return, then shows again. */
    private fun TetherGraphFixture.awayAndBack(ms: Long) {
        hide("tether"); events.onForeground(false)
        advance(ms)
        events.onForeground(true); show("tether")
    }

    private fun TetherGraphFixture.returns() = triggers.filter { it.kind == GraphRecoveryTrigger.Kind.FOREGROUND_RETURN }

    /** The waiting tether 1d request, the only one, answered with [response]. */
    private fun TetherGraphFixture.complete(label: String, response: AuthenticatedHttpResponse<GraphV2TabResponse>) {
        val pending = waiting()
        assertEquals("$label premise: one tether 1d request waits", 1, pending.size)
        pending.single().answer.complete(response)
        run()

        val body = response.body ?: return
        val checked = state("tether")
        assertEquals("$label: checked holder is on 1d", GraphPeriod.ONE_DAY, checked.activePeriod)
        val chart = checkNotNull(checked.chart) { "$label: checked holder has a chart" }

        for (supplied in body.series.filter { it.id in EXCHANGES.map(::id) }) {
            val displayed = chart.prepared.bySeries.getValue(supplied.id)
            val held = recorder.state.value.series[
                GraphObservationSeriesKey(GraphDataScope("u1", "e1"), supplied.id)
            ]
            for (p in supplied.data.filter { it.ts == A1 || it.ts == A2 }) {
                assertEquals("$label ${supplied.id} ${p.ts}: displayed close",
                    p.rate, displayed.linePoints.firstOrNull { it.ts == p.ts }?.rate)
                assertEquals("$label ${supplied.id} ${p.ts}: displayed band",
                    p.low to p.high,
                    displayed.bandPoints.firstOrNull { it.ts == p.ts }?.let { it.low to it.high })

                if (held != null) {
                    val stored = held.data.serverBuckets[p.ts] as?
                        com.jay.fxi.data.graph.GraphServerBucket.Closed
                    assertNotNull("$label ${supplied.id} ${p.ts}: recorder stored the closed bucket", stored)
                    assertEquals("$label: stored close", p.rate, stored?.point?.rate)
                    assertEquals("$label: stored band",
                        p.low to p.high, stored?.point?.let { it.low to it.high })
                }
            }
        }
    }

    // --- R: the real return ------------------------------------------------------------------------------------------

    /**
     * R (gopax): the series exists, the surface hides and the app goes to the background; the tether topic's delivery resumes
     * (a RECEIVE_GAP over the window); 61 s later a real return retains, issues FOREGROUND_RETURN, publishes and opens a round.
     * The demands survive the retain, the trigger and the first publications; only the answer releases what it supplies.
     */
    @Test fun R_aReturnIsNotARecovery() = recoveryTest {
        val e = "gopax"
        val f = fixture(this)
        f.open(e)
        f.exchange(e, 1340.0, B + 30.seconds)
        f.hide("tether"); f.events.onForeground(false)
        f.resumed()
        val owedBefore = f.owed(e)
        assertTrue("premise: the gap owes A1 and A2: $owedBefore", A1 in owedBefore && A2 in owedBefore)
        val mark = f.published.getValue("tether").size
        f.advance(61_000)
        f.events.onForeground(true); f.show("tether")
        assertEquals("one return trigger", 1, f.returns().size)
        assertTrue("premise: a publication after the return", f.published.getValue("tether").size > mark)
        assertEquals("retain, trigger and publication release nothing", owedBefore, f.owed(e))
        f.complete("R", supplying(e, A1, A2))
        assertEquals("the answer releases exactly what it supplies", owedBefore - setOf(A1, A2), f.owed(e))
    }

    // --- M: a memory cache hit ------------------------------------------------------------------------------------------

    /**
     * M (gopax): the online entry is fresh and A1/A2 are owed after it. Re-activating tether sends no unforced request and
     * keeps the demands; a valid return trigger still sends a recovery request while the entry is fresh, and its answer
     * releases them.
     */
    @Test fun M_aCacheHitKeepsTheDemand() = recoveryTest {
        val e = "gopax"
        val f = fixture(this)
        f.ready(e)
        f.activate("usd")
        f.sent.single { it.key == GraphKey("usd", GraphPeriod.ONE_DAY) }.answer.complete(day("usd", USD_1D)); f.run()
        f.activate("tether")
        assertNotNull("premise: the entry is fresh", f.coordinator.state.value.entries[oneDay]?.online200At)
        assertEquals("a fresh entry asks nothing unforced", 1, f.tabSends().size)
        assertEquals("the cache hit releases nothing", setOf(A1, A2), f.owed(e))
        f.awayAndBack(61_000)
        assertEquals(1, f.returns().size)
        assertEquals("the trigger sends one recovery request despite the fresh entry", 2, f.tabSends().size)
        assertEquals(setOf(A1, A2), f.owed(e))
        f.complete("M", supplying(e, A1, A2))
        assertEquals(emptySet<Instant>(), f.owed(e))
    }

    // --- D: a disk seed ------------------------------------------------------------------------------------------------

    /** Writes the tether 1d answer [response] where the fixture's disk store reads it (u1, e1, tether, 1d). */
    private fun TetherGraphFixture.putStored(response: AuthenticatedHttpResponse<GraphV2TabResponse>) {
        val domainCatalog = GraphV2Domain.catalog(catalog())
        val tab = (GraphV2Domain.admit(checkNotNull(response.body), "tether", GraphPeriod.ONE_DAY, domainCatalog) as GraphTabAdmission.Accepted).tab
        val key = GraphV2GeneralKey("u1", "e1", "tether", GraphPeriod.ONE_DAY.code)
        val components = (splitGraphV2ServerTab(tab, key, "K1", "stored", domainCatalog) as GraphV2Validation.Valid).value
        val bytes = (JsonGraphV2EnvelopeCodec().encodeGeneral(components.general) as GraphV2Validation.Valid).value
        fun hex(text: String) = text.encodeToByteArray().joinToString("") { "%02x".format(it) }
        val file = File(root, "general/${hex(key.uid)}/${hex(key.userAccessEpoch)}/${hex(key.tab)}/${key.period}.json")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    /**
     * D (gopax): a stored tether 1d answer that holds A1 and A2. Its GENERAL read is held while the catalog arrives and the
     * demands are made; the seed then fills the empty slot without an online stamp and releases nothing, before or after the
     * opening request fails with 503; the budget that 503 opened sends a recovery request whose answer releases them.
     */
    @Test fun D_aDiskSeedIsNotARecovery() = recoveryTest {
        val e = "gopax"
        for (order in listOf("seed-503", "503-seed")) {
            val f = fixture(this)
            f.putStored(supplying(e, A1, A2))
            f.store.hold[1] = CompletableDeferred()
            f.start(); f.collectPublications(); f.activate("tether")
            assertEquals("$order premise: the seed read is out", 1, f.store.generalReads.size)
            f.events.onForeground(true)
            f.exchange(e, 1340.0, B + 30.seconds)
            f.lose(e, A1 + 30.seconds)
            f.lose(e, A2 + 30.seconds)
            assertEquals("$order premise", setOf(A1, A2), f.owed(e))
            val seed = { f.store.hold.getValue(1).complete(Unit); f.run() }
            val fail = { f.complete("$order opening", status(503)) }
            if (order == "seed-503") { seed(); fail() } else { fail(); seed() }
            val entry = f.coordinator.state.value.entries[oneDay]
            assertNotNull("$order: the seed filled the slot", entry)
            assertNull("$order: without an online stamp", entry?.online200At)
            assertEquals("$order: the seed releases nothing", setOf(A1, A2), f.owed(e))
            f.advance(3_000)
            assertEquals("$order: the budget's recovery request", 2, f.tabSends().size)
            f.complete("$order recovery", supplying(e, A1, A2))
            assertEquals("$order", emptySet<Instant>(), f.owed(e))
            f.close()
        }
    }

    // --- C1: the return condition ----------------------------------------------------------------------------------

    /**
     * C1 (gopax): within B, 59.999 s away is no return trigger and sends nothing, with the demands kept; 60 s away is one,
     * and its recovery answer releases them. A 30 s background that crosses 12:10 still processes the demand by the
     * boundary trigger.
     */
    @Test fun C1_onlySixtySecondsAwayIsAReturnButABoundaryStillActs() = recoveryTest {
        val e = "gopax"
        for ((away, returns) in listOf(59_999L to 0, 60_000L to 1)) {
            val f = fixture(this)
            f.ready(e)
            f.awayAndBack(away)
            assertEquals("${away}ms", returns, f.returns().size)
            assertEquals("${away}ms sends", 1 + returns, f.tabSends().size)
            assertEquals("${away}ms keeps the demands", setOf(A1, A2), f.owed(e))
            if (returns == 1) {
                f.complete("${away}ms", supplying(e, A1, A2))
                assertEquals(emptySet<Instant>(), f.owed(e))
            }
            f.close()
        }
        val f = fixture(this)
        f.ready(e)
        f.advance((B + 9.minutes + 50.seconds - f.clock.now()).inWholeMilliseconds)
        assertEquals("premise: nothing before the crossing", 1, f.tabSends().size)
        f.awayAndBack(30_000)
        assertEquals("a short return is no return trigger", 0, f.returns().size)
        assertTrue("the crossing is a boundary trigger", f.triggers.any { it.kind == GraphRecoveryTrigger.Kind.BOUNDARY_600S })
        assertEquals("the boundary sends one recovery request", 2, f.tabSends().size)
        f.complete("boundary", supplying(e, A1, A2))
        assertTrue("A1 and A2 released", f.owed(e).none { it == A1 || it == A2 })
    }

    // --- C2: the reconnect cooldown ----------------------------------------------------------------------------------

    private val session = Any()

    /** An automatic permit of the current fence naming Connection [generation] (not a re-approval). */
    private fun permit(generation: Long, lifetime: com.jay.fxi.data.remote.TopicUseLifetime) =
        TopicGraphRecoveryPermit(session, generation, FENCE_A, 1L, false, generation, lifetime, true)

    /**
     * C2 (gopax, shared permit): the first Connection is the baseline. A return trigger is issued before any demand; A1/A2
     * are owed after it. A newer Connection 29.999 s after that trigger is suppressed - no trigger, no send, demands kept, not
     * replayed later - and a still newer one after the cooldown reconnects and is processed. At exactly 30 s it is processed.
     */
    @Test fun C2_returnsAndReconnectsShareTheCooldown() = recoveryTest {
        val e = "gopax"
        for (gap in listOf(29_999L, 30_000L)) {
            val label = "${gap}ms"
            val f = fixture(this, sharedPermit = true)
            f.permitValue = permit(1L, f.lifetime)
            f.open(e)
            f.events.onPermitChanged()
            assertEquals("$label: the first Connection is the baseline", 0, f.triggers.size)
            f.exchange(e, 1340.0, B + 30.seconds)
            f.awayAndBack(60_000)
            assertEquals("$label premise: the return trigger", 1, f.returns().size)
            val returnedAt = f.elapsedMs
            assertEquals("$label premise: no demand, no send", 1, f.tabSends().size)
            f.lose(e, A1 + 30.seconds)
            f.lose(e, A2 + 30.seconds)
            f.advance(returnedAt + gap - f.elapsedMs)
            f.permitValue = permit(2L, f.lifetime); f.events.onPermitChanged(); f.run()
            val reconnects = { f.triggers.count { it.kind == GraphRecoveryTrigger.Kind.RECONNECT } }
            if (gap < 30_000L) {
                assertEquals("$label: suppressed", 0, reconnects())
                assertEquals("$label: no send", 1, f.tabSends().size)
                assertEquals("$label: demands kept", setOf(A1, A2), f.owed(e))
                f.advance(20_000)
                assertEquals("$label: not replayed", 0, reconnects())
                assertEquals("$label: still no send", 1, f.tabSends().size)
                f.permitValue = permit(3L, f.lifetime); f.events.onPermitChanged(); f.run()
            }
            assertEquals("$label: one reconnect", 1, reconnects())
            assertEquals("$label: one recovery request", 2, f.tabSends().size)
            f.complete(label, supplying(e, A1, A2))
            assertEquals(label, emptySet<Instant>(), f.owed(e))
            f.close()
        }
    }

    // --- L: the Retry-After floor -----------------------------------------------------------------------------------

    /**
     * L (gopax): a forced request carrying the demands fails 503 with Retry-After 10. Forced refreshes (at 2 s, 5 s and 1 ms
     * before the floor) and the budget's 3 s deadline inside the floor send nothing and keep the demands; at the floor exactly
     * one tether 1d request goes, and its answer releases them.
     */
    @Test fun L_theFloorHoldsEverySendAndKeepsTheDemand() = recoveryTest {
        val e = "gopax"
        val f = fixture(this)
        f.ready(e)
        f.coordinator.onRefreshRequested(force = true); f.run()
        val failedAt = f.elapsedMs
        f.complete("L", status(503, "10"))
        f.advance(2_000)
        f.coordinator.onRefreshRequested(force = true); f.run()
        f.advance(3_000)
        f.coordinator.onRefreshRequested(force = true); f.run()
        f.advance(failedAt + 9_999 - f.elapsedMs)
        // A forced refresh one millisecond before the floor is still held.
        f.coordinator.onRefreshRequested(force = true); f.run()
        assertEquals("nothing inside the floor", 2, f.tabSends().size)
        assertEquals(setOf(A1, A2), f.owed(e))
        f.advance(1)
        assertEquals("one request at the floor", 3, f.tabSends().size)
        assertEquals(failedAt + 10_000, f.sendTimes().last())
        f.complete("L floor", supplying(e, A1, A2))
        assertEquals(emptySet<Instant>(), f.owed(e))
    }

    // --- I: a request in flight ------------------------------------------------------------------------------------

    /**
     * I (each exchange): a forced request captures A1/A2 and waits. A new loss on A1 makes a newer generation; a forced refresh
     * and a return trigger meanwhile join the request in flight (no second send). The old answer supplying A1 and A2 releases
     * A2 only; the newer A1 waits for the next capture, whose answer releases it.
     */
    @Test fun I_aRequestInFlightReleasesOnlyWhatItCaptured() = recoveryTest {
        for (e in EXCHANGES) {
            val f = fixture(this)
            f.ready(e)
            f.coordinator.onRefreshRequested(force = true); f.run()
            assertEquals("$e premise: one request waits", 1, f.waiting().size)
            val before = f.pending(e).getValue(A1).generation
            f.lose(e, A1 + 45.seconds)
            assertTrue("$e premise: a newer A1 generation", f.pending(e).getValue(A1).generation > before)
            val capturedVersion = f.recorder.state.value.nextVersion
            val demandsAfterLoss = f.pending(e).toMap()
            val waitingId = f.waiting().single().id

            f.coordinator.onRefreshRequested(force = true); f.run()
            assertEquals("$e: refresh does not recapture", capturedVersion, f.recorder.state.value.nextVersion)
            assertEquals("$e: refresh preserves generations", demandsAfterLoss, f.pending(e))

            f.awayAndBack(61_000)
            assertEquals("$e premise: one return trigger", 1, f.returns().size)
            assertEquals("$e: return does not recapture", capturedVersion, f.recorder.state.value.nextVersion)
            assertEquals("$e: return preserves generations", demandsAfterLoss, f.pending(e))
            assertEquals("$e: the same request waits", waitingId, f.waiting().single().id)
            assertEquals("$e: joined, not sent again", 2, f.tabSends().size)
            f.complete("$e old", supplying(e, A1, A2))
            assertEquals("$e: the old answer releases only what it captured", setOf(A1), f.owed(e))
            f.advance(3_000)
            assertEquals("$e: the next capture", 3, f.tabSends().size)
            f.complete("$e new", supplying(e, A1))
            assertEquals(e, emptySet<Instant>(), f.owed(e))
            f.close()
        }
    }

    // --- F: the failure ladder ---------------------------------------------------------------------------------------

    /**
     * F (gopax): a return opens a cycle; every request fails 503 at once. Requests go at 0/3/9/21/45/93 s and stop with the
     * demands kept (EXHAUSTED is not a recovery); a later valid return opens a new cycle whose answer releases them.
     */
    @Test fun F_failuresKeepTheDemandUntilAnAnswer() = recoveryTest {
        val e = "gopax"
        val f = fixture(this)
        f.ready(e)
        f.autoTab = { if (it.key == oneDay) it.answer.complete(status(503)) }
        f.awayAndBack(61_000)
        val first = f.sendTimes().last()
        f.advance(200_000)
        assertEquals(listOf(0L, 3_000L, 9_000L, 21_000L, 45_000L, 93_000L), f.sendTimes().drop(1).map { it - first })
        assertEquals("kept after exhaustion", setOf(A1, A2), f.owed(e))
        f.autoTab = null
        f.awayAndBack(61_000)
        assertEquals("a new cycle", 8, f.tabSends().size)
        f.complete("F", supplying(e, A1, A2))
        assertEquals(emptySet<Instant>(), f.owed(e))
    }

    // --- P: a partial answer and the positive control ------------------------------------------------------------

    /**
     * P (each exchange): a forced request captures A1/A2. An answer supplying only A2 releases A2 and keeps A1; empty points,
     * a current-bucket seed or another exchange's A1 release nothing. The next capture's A1 answer releases A1, and then no
     * closed demand is left, so no further request follows.
     */
    @Test fun P_onlySuppliedClosedBucketsAreReleased() = recoveryTest {
        for (e in EXCHANGES) {
            val other = EXCHANGES.first { it != e }
            for ((label, response) in listOf(
                "empty points" to dayWith(mapOf(id(e) to emptyList())),
                "a current-bucket seed" to dayWith(mapOf(id(e) to emptyList()),
                    seeds = mapOf(id(e) to GraphV2InProgressSeed(B, 1342.0, 1340.0, 1341.0, B + 30.seconds))),
                "another exchange's A1" to dayWith(mapOf(id(e) to emptyList(), id(other) to listOf(A1)))
            )) {
                val f = fixture(this)
                f.ready(e)
                f.coordinator.onRefreshRequested(force = true); f.run()
                f.complete("$e $label", response)
                assertEquals("$e $label releases nothing", setOf(A1, A2), f.owed(e))
                f.close()
            }
            val f = fixture(this)
            f.ready(e)
            f.coordinator.onRefreshRequested(force = true); f.run()
            f.complete("$e partial", supplying(e, A2))
            assertEquals("$e: A2 only", setOf(A1), f.owed(e))
            f.advance(3_000)
            assertEquals("$e: the next capture", 3, f.tabSends().size)
            f.complete("$e A1", supplying(e, A1))
            assertEquals(e, emptySet<Instant>(), f.owed(e))
            f.advance(300_000)
            assertEquals("$e: nothing more without a closed demand", 3, f.tabSends().size)
            f.close()
        }
    }

    // --- W: withdrawal ----------------------------------------------------------------------------------------------

    /**
     * W (gopax): a 503 opens the budget. (1) The round's credential capture fails with AuthUnavailableException; (2) the round
     * is sent and its fetch ends with CancellationException. Either way the demands stay, nothing repeats in the same
     * activation, and the next activation captures anew and is answered.
     */
    @Test fun W_aWithdrawalHoldsUntilTheNextActivation() = recoveryTest {
        val e = "gopax"
        for (path in listOf("capture", "fetch")) {
            val f = fixture(this)
            f.ready(e)
            f.coordinator.onRefreshRequested(force = true); f.run()
            f.complete("$path 503", status(503))
            val capturesBefore = f.captures.size
            if (path == "capture") {
                f.captureHook = { n -> if (n == capturesBefore + 1) throw AuthUnavailableException("offline") }
                f.advance(3_000)
                assertEquals("$path: the round captured once", capturesBefore + 1, f.captures.size)
                assertEquals("$path: nothing sent", 2, f.tabSends().size)
            } else {
                f.advance(3_000)
                assertEquals("$path: the round was sent", 3, f.tabSends().size)
                f.waiting().single().answer.completeExceptionally(CancellationException("cancelled")); f.run()
            }
            val sends = f.tabSends().size
            val captures = f.captures.size
            f.advance(120_000)
            assertEquals("$path: held, no capture repeats", captures, f.captures.size)
            assertEquals("$path: held, no send repeats", sends, f.tabSends().size)
            assertEquals("$path: the demands stay", setOf(A1, A2), f.owed(e))
            f.captureHook = null
            f.activate("usd")
            f.sent.filter { it.key == GraphKey("usd", GraphPeriod.ONE_DAY) && !it.answer.isCompleted }
                .forEach { it.answer.complete(day("usd", USD_1D)) }
            f.run()
            f.activate("tether")
            assertEquals("$path: the next activation sends the round", sends + 1, f.tabSends().size)
            f.complete(path, supplying(e, A1, A2))
            assertEquals(path, emptySet<Instant>(), f.owed(e))
            f.close()
        }
    }

    // --- H: the next active owner ----------------------------------------------------------------------------------

    /**
     * H (each exchange): a 503 leaves the demands and a retry deadline. Tether moves to 1w and the app to usd past that
     * deadline: no tether 1d request. The tether holder closes and a new one over the same coordinator and recorder activates
     * on 1d: the past deadline sends one request at once, and its answer releases the demands.
     */
    @Test fun H_theNextActiveOwnerCarriesTheDemand() = recoveryTest {
        for (e in EXCHANGES) {
            val f = fixture(this)
            f.ready(e)
            f.coordinator.onRefreshRequested(force = true); f.run()
            f.complete("$e 503", status(503))
            val week = GraphKey("tether", GraphPeriod.ONE_WEEK)
            f.holders.getValue("tether").selectPeriod(f.token("tether"), GraphPeriod.ONE_WEEK); f.run()
            f.sent.filter { it.key == week && !it.answer.isCompleted }.forEach { it.answer.complete(long("tether", GraphPeriod.ONE_WEEK, LONG_TETHER)) }
            f.run()
            f.advance(10_000)
            assertEquals("$e: no tether 1d request on 1w", 2, f.tabSends().size)
            f.activate("usd")
            f.sent.filter { it.key == GraphKey("usd", GraphPeriod.ONE_DAY) && !it.answer.isCompleted }
                .forEach { it.answer.complete(day("usd", USD_1D)) }
            f.run()
            f.advance(10_000)
            assertEquals("$e: none on usd either", 2, f.tabSends().size)
            assertEquals("$e: the demands are kept", setOf(A1, A2), f.owed(e))
            val next = f.replaceTether()
            f.activate("tether")
            assertEquals("$e premise: the new holder is on 1d", GraphPeriod.ONE_DAY, next.currentState().activePeriod)
            assertEquals("$e: one request at once", 3, f.tabSends().size)
            assertEquals("$e: sent on activation", f.elapsedMs, f.sendTimes().last())
            f.complete(e, supplying(e, A1, A2))
            assertEquals(e, emptySet<Instant>(), f.owed(e))
            f.close()
        }
    }
}
