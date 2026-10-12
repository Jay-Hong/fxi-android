package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservation
import com.jay.fxi.data.graph.GraphObservationId
import com.jay.fxi.data.graph.GraphObservationOrder
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphRecoverableState
import com.jay.fxi.data.graph.observeRecoverable
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.GraphProjection
import com.jay.fxi.ui.graph.LinePoint
import com.jay.fxi.ui.graph.PreparedSeries
import com.jay.fxi.ui.premium.graph.TetherGraphData.B
import com.jay.fxi.ui.premium.graph.TetherGraphData.DAY0
import com.jay.fxi.ui.premium.graph.TetherGraphData.EXCHANGES
import com.jay.fxi.ui.premium.graph.TetherGraphData.LONG_TETHER
import com.jay.fxi.ui.premium.graph.TetherGraphData.TETHER_1D
import com.jay.fxi.ui.premium.graph.TetherGraphData.dayPoints
import com.jay.fxi.ui.premium.graph.TetherGraphData.longPoints
import com.jay.fxi.ui.premium.graph.TetherGraphData.ok
import com.jay.fxi.ui.premium.graph.TetherGraphData.seed
import com.jay.fxi.ui.premium.graph.TetherGraphData.series
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S5-2b-1 (JVM): the tether holder's publication and projection rows for each of the five exchanges, through the real request
 * coordinator, graph recorder and recovery-event producer.
 *
 * Agreed plan: R4c/S5 s5_2b1_plan.r2.md (Codex s5_2b1_plan_codex.r1.md). Oracles: iOS 89e866d GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md
 * :253 (T05), :257 (T09), :264 (T16), :269 (T21) and the low rows :281-286, with :132/:136/:138 (freshness never deletes an
 * extreme, validated observations are kept, only source-less accumulation and tips are rebuilt); ANDROID_V2_PLAN.md :1364;
 * GraphRecoveryEvents.kt:85 (a return retains, triggers and emits one time event); GraphV2LiveProjection.kt:21/:69/:80/:94
 * (600 s exchange freshness, strict; the zero-width close of the last unreplaced bucket).
 *
 * T05's "no app-derived old value in any publication after the return" is judged as agreed (plan §1): (a) no line or band
 * point at or after B and no now end point carries the old value, and a timer alone opens no B range; (b) the validated old
 * observation stays in its own 09:00 bucket; (c) a source-less value fed only to the holder's live feed never appears. This is
 * narrower than reading the original as "the number appears nowhere"; (c) is not a reproduction of an earlier exposed
 * source-less tip being invalidated on return.
 *
 * Times: B = 12:00-12:10 KST stands for the original 20:00-20:10 (8 h earlier throughout): 17:02 -> 09:02, 19:40 -> 11:40,
 * 19:50 -> 11:50, 20:02 -> 12:02.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherGraphPublishTest {

    @get:Rule val folder = TemporaryFolder()

    private val opened = mutableListOf<TetherGraphFixture>()

    private fun publishTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private fun fixture(test: TestScope, start: Instant = B + 1.minutes) =
        TetherGraphFixture(test, folder, start = start).also { opened += it }

    private val oneDay = GraphKey("tether", GraphPeriod.ONE_DAY)
    private fun id(exchange: String) = "$exchange.usdt-krw"

    /** The tether 1d response, with [exchange]'s series replaced by [points] when given. */
    private fun dayWith(exchange: String? = null, points: List<GraphV2Point>? = null, seeds: Map<String, com.jay.fxi.data.remote.dto.GraphV2InProgressSeed> = emptyMap()) =
        ok(GraphV2TabResponse(
            tab = "tether", period = "1d",
            series = TETHER_1D.map { sid -> series(sid, if (exchange != null && sid == id(exchange) && points != null) points else dayPoints(sid)) },
            metadata = GraphV2Metadata(B - 1.minutes, "10min", GraphV2Range("2026-10-05", "2026-10-05"),
                domainStartAt = DAY0, domainEndAt = DAY0 + 24.hours, liveDomainMode = "rolling"),
            inProgress = seeds
        ))

    /** Starts, collects, activates tether with [exchange] the only visible series, answers 1d and shows it. */
    private fun TetherGraphFixture.openOn(exchange: String, response: com.jay.fxi.data.remote.AuthenticatedHttpResponse<GraphV2TabResponse> = dayWith()) {
        start()
        collectPublications()
        activate("tether")
        answer(oneDay, response)
        selectOnly(exchange)
        show("tether")
    }

    private fun TetherGraphFixture.selectOnly(exchange: String) {
        val h = holders.getValue("tether")
        val visible = checkNotNull(state("tether").selection).visibleSeriesIds
        for (other in visible - id(exchange)) { h.toggleSeries(token("tether"), other); run() }
        if (id(exchange) !in checkNotNull(state("tether").selection).visibleSeriesIds) { h.toggleSeries(token("tether"), id(exchange)); run() }
        assertEquals("premise: only $exchange", setOf(id(exchange)), state("tether").selection!!.visibleSeriesIds)
    }

    private fun TetherGraphFixture.series(exchange: String): PreparedSeries = seriesOf("tether", id(exchange))
    private fun PreparedSeries.values() = linePoints.map { it.rate } + bandPoints.flatMap { listOf(it.low, it.high) }
    private fun PreparedSeries.bandFrom(start: Instant) = bandPoints.filter { it.ts > start }
    private fun TetherGraphFixture.tetherPublications(from: Int) =
        published.getValue("tether").drop(from).mapNotNull { it.chart }

    /**
     * The new live projections among the tether states from [from] on, each with the recorder state read when it was first
     * observed. A state that re-shows the previous prepared graph at the same right edge is an emission, not a new projection;
     * a chart without a right edge is the server-only chart a hidden surface keeps (no live projection off screen).
     */
    private fun TetherGraphFixture.newProjections(
        from: Int,
        until: Int = published.getValue("tether").size
    ): List<Pair<GraphV2ChartModel, com.jay.fxi.data.graph.GraphRecorderState>> {
        val all = published.getValue("tether")
        val recorded = recorderAtPublication.getValue("tether")
        var previous = all.getOrNull(from - 1)?.chart
        val result = mutableListOf<Pair<GraphV2ChartModel, com.jay.fxi.data.graph.GraphRecorderState>>()
        for (i in from until until) {
            val chart = all[i].chart ?: continue
            if (chart.rightEdgeNow != null && (chart.prepared !== previous?.prepared || chart.rightEdgeNow != previous?.rightEdgeNow)) {
                result += chart to recorded[i]
            }
            previous = chart
        }
        return result
    }

    private fun TetherGraphFixture.seriesKey(exchange: String) = GraphObservationSeriesKey(GraphDataScope("u1", "e1"), id(exchange))

    /** Records [rate] at [observedAt], asserting the recorder adopted it. */
    private fun TetherGraphFixture.adoptApp(exchange: String, rate: Double, observedAt: Instant) {
        exchange(exchange, rate, observedAt)
        val held = recorder.state.value.series.getValue(seriesKey(exchange)).data.app.observations
        assertTrue("premise: the recorder adopted $rate", GraphObservationId(exchange, "usdt-krw", observedAt, rate) in held)
    }

    /** Completes every pending tether 1d request with the seed, asserting the recorder adopted it. */
    private fun TetherGraphFixture.adoptSeed(exchange: String) {
        val pending = sent.filter { it.key == oneDay && !it.answer.isCompleted }
        assertTrue("premise: a request waits for the seed", pending.isNotEmpty())
        pending.forEach { it.answer.complete(dayWith(seeds = mapOf(id(exchange) to seed(1341.7, 1341.9, 1341.8)))) }
        run()
        val bucket = recorder.state.value.series.getValue(seriesKey(exchange)).data.serverBuckets[B]
        assertTrue("premise: the recorder adopted the seed: $bucket", bucket is com.jay.fxi.data.graph.GraphServerBucket.Seeds)
    }

    /**
     * Each new projection from [from] on shows at B exactly the union of the evidence its own recorder snapshot holds - the app
     * record [app] at [appAt] and the seed [1341.7, 1341.9] - with no B band before any, and otherwise at both B's start and now
     * the union's low and high.
     */
    private fun TetherGraphFixture.assertBandIsTheAdoptedUnion(label: String, exchange: String, app: Double, appAt: Instant, from: Int) {
        val projections = newProjections(from)
        assertTrue("$label premise: new projections", projections.isNotEmpty())
        for ((chart, recorded) in projections) {
            val edge = checkNotNull(chart.rightEdgeNow)
            val s = chart.prepared.bySeries.getValue(id(exchange))
            val held = recorded.series[seriesKey(exchange)]?.data
            val evidence = buildList {
                if (held != null && GraphObservationId(exchange, "usdt-krw", appAt, app) in held.app.observations) add(app to app)
                if (held?.serverBuckets?.get(B) is com.jay.fxi.data.graph.GraphServerBucket.Seeds) add(1341.7 to 1341.9)
            }
            if (evidence.isEmpty()) {
                assertTrue("$label @$edge: no B band before any evidence: ${s.bandPoints}", s.bandPoints.none { it.ts > B })
                continue
            }
            val union = evidence.minOf { it.first } to evidence.maxOf { it.second }
            val atB = s.bandPoints.filter { it.ts == B || it.ts == edge }
            assertTrue("$label @$edge: a band at B's start and at now: ${s.bandPoints}", atB.any { it.ts == B } && atB.any { it.ts == edge })
            for (band in atB) assertEquals("$label @$edge ${band.ts}", union, band.low to band.high)
        }
    }

    /** Hides, goes to the background for [seconds] and comes back with a real return, then shows again. */
    private fun TetherGraphFixture.awayAndBack(seconds: Long = 70) {
        hide("tether"); events.onForeground(false)
        advance(seconds * 1000)
        events.onForeground(true); show("tether")
    }

    /** Every tether publication from [from] on: no line or band point at or after B and no now end point carries [old]. */
    private fun TetherGraphFixture.assertNoOldInB(label: String, exchange: String, old: Double, from: Int) {
        assertTrue("$label premise: publications from the return on", tetherPublications(from).any { id(exchange) in it.prepared.bySeries })
        for (chart in tetherPublications(from)) {
            val s = chart.prepared.bySeries[id(exchange)] ?: continue
            assertTrue("$label: no old value on a line point from B on: ${s.linePoints}", s.linePoints.none { it.ts >= B && it.rate == old })
            assertTrue("$label: no old value in a band from B on: ${s.bandPoints}", s.bandPoints.none { it.ts >= B && (it.low == old || it.high == old) })
        }
    }

    // --- T05 / T05-L ---------------------------------------------------------------------------------------------------

    /**
     * T05 and T05-L for each exchange: a validated 09:02 record of 1345.7 (or 1337.7), shown fresh, then three hours in the
     * background and a real return at 12:02. From the return on (a) no publication carries it at or after B nor as the now end
     * point, and no new projection before the new record has any B band (the return's time events and a 30 s timer alone open
     * none); (b) it stays in its own 09:00 bucket; after a new 12:02 record B is [1341.7, 1341.7]; (c) a source-less value in the
     * holder's live feed never appears.
     */
    @Test fun T05_T05L_anOldValueStaysInItsOwnBucket() = publishTest {
        for (exchange in EXCHANGES) for (old in listOf(1345.7, 1337.7)) {
            val label = "$exchange $old"
            val start = B - 3.hours + 2.minutes
            val f = fixture(this, start)
            f.openOn(exchange)
            f.events.onForeground(true)
            f.exchange(exchange, old, start)
            f.advance(400)
            assertTrue("$label premise: shown fresh", old in f.series(exchange).values())
            val poison = 1399.9
            val key = f.seriesKey(exchange)
            f.live.value = mapOf(key to observeRecoverable(GraphRecoverableState.empty(key, GraphObservationOrder(emptyList())),
                listOf(GraphObservation(key, GraphObservationId(exchange, "usdt-krw", start, poison))), start).state)
            f.run()
            f.hide("tether")
            f.events.onForeground(false)
            f.advance(3.hours.inWholeMilliseconds)
            val mark = f.published.getValue("tether").size
            f.events.onForeground(true)
            f.show("tether")
            f.advance(30_000)
            val beforeNew = f.published.getValue("tether").size
            assertTrue("$label premise: projections from the return on", f.newProjections(mark).isNotEmpty())
            for ((chart, _) in f.newProjections(mark)) {
                val s = chart.prepared.bySeries.getValue(id(exchange))
                assertTrue("$label @${chart.rightEdgeNow}: a timer alone opens no B range: ${s.bandPoints}", s.bandPoints.none { it.ts > B })
            }
            assertTrue("$label: a timer alone opens no B range", f.series(exchange).bandFrom(B).isEmpty())
            assertTrue("$label (b): kept in its own 09:00 bucket", LinePoint(B - 3.hours, old) in f.series(exchange).linePoints)
            f.exchange(exchange, 1341.7, B + 2.minutes)
            f.advance(400)
            assertTrue("$label premise: a projection after the new record", f.newProjections(beforeNew).isNotEmpty())
            assertEquals("$label", 1341.7 to 1341.7, f.series(exchange).bandPoints.last().let { it.low to it.high })
            f.assertNoOldInB(label, exchange, old, mark)
            assertTrue("$label (c): the live feed never appears",
                f.published.getValue("tether").mapNotNull { it.chart?.prepared?.bySeries?.get(id(exchange)) }.none { poison in it.values() })
            f.close()
        }
    }

    // --- T09 ---------------------------------------------------------------------------------------------------------------

    /**
     * T09 for each exchange: the server's last bucket 11:40, the app's range [1341.5, 1342.1] in 11:50, now 12:02, nothing in B.
     * The 11:50 band closes with a zero-width point at 12:00 and nothing after it; with the last record stale (11:51, 660 s) the
     * line has no now end point, with it fresh (11:55, 420 s) it ends at (now, 1341.5); the bands are the same.
     */
    @Test fun T09_thePreviousBandIsNotStretchedToNow() = publishTest {
        val rest = List(6) { i -> GraphV2Point(B - 70.minutes + 10.minutes * i, 1340.0 + i, "x", 1341.0 + i, 1339.0 + i) }
        for (exchange in EXCHANGES) {
            val bands = mutableListOf<List<com.jay.fxi.ui.graph.BandPoint>>()
            for ((case, lastAt) in listOf("stale" to (B - 9.minutes), "fresh" to (B - 5.minutes))) {
                val label = "$exchange $case"
                val f = fixture(this, B + 2.minutes)
                f.openOn(exchange, dayWith(exchange, rest))
                f.exchange(exchange, 1342.1, B - 10.minutes + 30.seconds)
                f.exchange(exchange, 1341.5, lastAt)
                f.holders.getValue("tether").onTimeEvent(); f.run()
                val chart = checkNotNull(f.state("tether").chart)
                val now = checkNotNull(chart.rightEdgeNow)
                val s = chart.prepared.bySeries.getValue(id(exchange))
                assertTrue("$label: the 11:50 range", s.bandPoints.any { it.ts == B - 10.minutes && it.low == 1341.5 && it.high == 1342.1 })
                assertEquals("$label: closed at 12:00 with zero width", com.jay.fxi.ui.graph.BandPoint(B, 1341.5, 1341.5), s.bandPoints.last())
                assertTrue("$label: nothing after 12:00", s.bandFrom(B).isEmpty())
                if (case == "stale") assertTrue("$label: no now end point", s.linePoints.none { it.ts == now })
                else assertEquals("$label: the fresh end point", LinePoint(now, 1341.5), s.linePoints.last())
                bands += s.bandPoints
                f.close()
            }
            assertEquals("$exchange: the same bands", bands[0], bands[1])
        }
    }

    // --- T16 / T16-L -------------------------------------------------------------------------------------------------------

    /**
     * T16/T16-L, T05 branch, for each exchange: the T05 inputs with the return (R), a timer (T), the new frame (F) and the
     * completion of a pending REST request (S) in several orders - S before and after the timer, the frame before and after
     * the return. In every publication from the return on the old value is absent at or after B, and B ends [1341.7, 1341.7].
     */
    @Test fun T16_T05Branch_noOrderBringsTheOldValueBack() = publishTest {
        for (exchange in EXCHANGES) for (old in listOf(1345.7, 1337.7)) for (order in listOf("RTF", "RFT", "FRT", "RSTF", "RTSF", "FRST", "STRF")) {
            val label = "$exchange $old $order"
            val start = B - 3.hours + 2.minutes
            val f = fixture(this, start)
            f.openOn(exchange)
            f.events.onForeground(true)
            f.exchange(exchange, old, start)
            f.advance(400)
            f.hide("tether")
            f.events.onForeground(false)
            f.advance(3.hours.inWholeMilliseconds)
            val mark = f.published.getValue("tether").size
            var returnedAt: Int? = null
            for (step in order) when (step) {
                'R' -> { returnedAt = f.published.getValue("tether").size; f.events.onForeground(true); f.show("tether"); f.advance(1) }
                'T' -> { f.holders.getValue("tether").onTimeEvent(); f.run(); f.advance(1) }
                'F' -> {
                    // From the return to the new frame, no new projection has a B band.
                    returnedAt?.let { r ->
                        val window = f.newProjections(r, f.published.getValue("tether").size)
                        assertTrue("$label premise: projections between the return and the frame", window.isNotEmpty())
                        for ((chart, _) in window) {
                            assertTrue("$label: no B band between the return and the frame: ${chart.prepared.bySeries.getValue(id(exchange)).bandPoints}",
                                chart.prepared.bySeries.getValue(id(exchange)).bandPoints.none { it.ts > B })
                        }
                    }
                    f.exchange(exchange, 1341.7, B + 2.minutes); f.advance(400)
                }
                'S' -> {
                    f.coordinator.onRefreshRequested(force = true); f.run()
                    val pending = f.sent.filter { it.key == oneDay && !it.answer.isCompleted }
                    assertTrue("$label premise: a request to complete", pending.isNotEmpty())
                    pending.forEach { it.answer.complete(dayWith()) }
                    f.run(); f.advance(1)
                }
            }
            f.advance(400)
            assertEquals(label, 1341.7 to 1341.7, f.series(exchange).bandPoints.last().let { it.low to it.high })
            f.assertNoOldInB(label, exchange, old, mark)
            f.close()
        }
    }

    /**
     * T16/T16-L, T06 branch, for each exchange: B's app record 1342.1 (or 1341.5) (A), the seed [1341.7, 1341.9] by a REST
     * completion (S), a real return (R) and a timer (T) in orders that put both inputs before the return, both after it, one on
     * each side, and the completion before and after the timer. Every new projection shows at B exactly the union of the
     * evidence the recorder had adopted by its right edge; B ends [1341.7, 1342.1] (or [1341.5, 1341.9]).
     */
    @Test fun T16_T06Branch_eachProjectionIsTheAdoptedUnion() = publishTest {
        for (exchange in EXCHANGES) for (low in listOf(false, true)) for (order in listOf("SART", "RTSA", "RSTA", "ARTS", "SRAT")) {
            val label = "$exchange low=$low $order"
            val app = if (low) 1341.5 else 1342.1
            val expected = if (low) 1341.5 to 1341.9 else 1341.7 to 1342.1
            val f = fixture(this, B + 1.minutes)
            f.openOn(exchange)
            f.events.onForeground(true)
            // The series exists with a demand, so a forced request captures it and its seed reaches the recorder.
            f.exchange(exchange, 1340.0, B - 5.minutes)
            f.advance(400)
            val from = f.published.getValue("tether").size
            val appAt = B + 1.minutes + 30.seconds
            for (step in order) {
                when (step) {
                    'A' -> f.adoptApp(exchange, app, appAt)
                    'S' -> { f.coordinator.onRefreshRequested(force = true); f.run(); f.adoptSeed(exchange) }
                    'R' -> f.awayAndBack()
                    'T' -> { f.holders.getValue("tether").onTimeEvent(); f.run() }
                }
                f.advance(400)
            }
            assertEquals(label, expected, f.series(exchange).bandPoints.last().let { it.low to it.high })
            f.assertBandIsTheAdoptedUnion(label, exchange, app, appAt, from)
            f.close()
        }
    }

    /**
     * T16/T16-L, T08 branch, for each exchange: with a request pending (P), B's record 1342.2 (or 1341.4) is adopted and
     * published while it waits (A); a real return (R), a timer (T) and the seed's completion (S) follow in three orders. Every
     * new projection is the adopted union; B ends [1341.7, 1342.2] (or [1341.4, 1341.9]).
     */
    @Test fun T16_T08Branch_anObservationWhileWaitingIsKept() = publishTest {
        for (exchange in EXCHANGES) for (low in listOf(false, true)) for (order in listOf("PARTS", "PATRS", "PARST", "PASTR")) {
            val label = "$exchange low=$low $order"
            val app = if (low) 1341.4 else 1342.2
            val expected = if (low) 1341.4 to 1341.9 else 1341.7 to 1342.2
            val f = fixture(this, B + 1.minutes)
            f.openOn(exchange)
            f.events.onForeground(true)
            f.exchange(exchange, 1340.0, B - 5.minutes)
            f.advance(400)
            val from = f.published.getValue("tether").size
            val appAt = B + 1.minutes + 30.seconds
            for (step in order) {
                when (step) {
                    'P' -> {
                        f.coordinator.onRefreshRequested(force = true); f.run()
                        assertTrue("$label premise: a request waits", f.sent.any { it.key == oneDay && !it.answer.isCompleted })
                    }
                    'A' -> {
                        f.adoptApp(exchange, app, appAt)
                        f.advance(400)
                        assertEquals("$label: published while waiting", app to app, bandOf(checkNotNull(f.state("tether").chart), exchange))
                        assertTrue("$label premise: still waiting", f.sent.any { it.key == oneDay && !it.answer.isCompleted })
                    }
                    'R' -> f.awayAndBack()
                    'T' -> { f.holders.getValue("tether").onTimeEvent(); f.run() }
                    'S' -> f.adoptSeed(exchange)
                }
                f.advance(400)
            }
            assertEquals(label, expected, f.series(exchange).bandPoints.last().let { it.low to it.high })
            f.assertBandIsTheAdoptedUnion(label, exchange, app, appAt, from)
            f.close()
        }
    }

    /** The band of [exchange] at now in a chart, or null when the chart has none after B. */
    private fun bandOf(chart: GraphV2ChartModel, exchange: String) =
        chart.prepared.bySeries[id(exchange)]?.bandPoints?.lastOrNull { it.ts > B }?.let { it.low to it.high }

    // --- T21 ---------------------------------------------------------------------------------------------------------------

    /** bithumb's long server points, ending at 1341.5. */
    private fun bithumbLongPoints(period: GraphPeriod) =
        longPoints("bithumb.usdt-krw", period).let { pts -> pts.dropLast(1) + pts.last().copy(rate = 1341.5, high = 1342.0, low = 1341.0) }

    /** The line and band the server points alone make, taken from the input rather than from any projection. */
    private fun serverLine(period: GraphPeriod) = bithumbLongPoints(period).map { LinePoint(it.ts, it.rate) }
    private fun serverBand(period: GraphPeriod) = bithumbLongPoints(period).map { com.jay.fxi.ui.graph.BandPoint(it.ts, checkNotNull(it.low), checkNotNull(it.high)) }

    /** bithumb's long response with its last server value 1341.5. */
    private fun longEndingAt1341_5(period: GraphPeriod) = ok(GraphV2TabResponse(
        tab = "tether", period = period.code,
        series = LONG_TETHER.map { sid ->
            if (sid == "bithumb.usdt-krw") series(sid, bithumbLongPoints(period))
            else series(sid, longPoints(sid, period))
        },
        metadata = GraphV2Metadata(B - 1.minutes, if (period == GraphPeriod.ONE_WEEK) "1h" else "1d", GraphV2Range("2026-07-05", "2026-10-05"))
    ))

    /**
     * The server trailing rule on the final plot when no app tip is used: on 1w the last server point (within its hold budget)
     * is held to now; on 3m and 1y (beyond the budget in this fixture) the line ends at that point (GraphFrame.kt:97).
     */
    private fun serverPlotEnd(period: GraphPeriod, serverLast: LinePoint, now: Instant) =
        if (period == GraphPeriod.ONE_WEEK) LinePoint(now, serverLast.rate) else serverLast

    private fun plotEnd(chart: GraphV2ChartModel, exchange: String) =
        GraphProjection.plot(chart.prepared, setOf(id(exchange)), rightEdgeNow = chart.rightEdgeNow)!!.lines.single().points.last()

    /**
     * T21 for bithumb, the only exchange the long-term catalog carries, on 1w, 3m and 1y: (i) a source-less three-hour-old tip
     * 1345.7 (or 1337.7) fed only to the live feed, (ii) the same value as a validated record three hours old. After a return and
     * a failed request (503) the prepared series keeps the server points and band with no app end point and the plot follows the
     * server trailing rule; a valid 1341.7 then ends the line and the plot at now; back on 1d the record sits in B; back on the
     * long period the server points and band are unchanged. No long publication from the return on carries the old value.
     */
    @Test fun T21_theLongRightEdgeTakesOnlyAValidCurrentPrice() = publishTest {
        val exchange = "bithumb"
        for (period in listOf(GraphPeriod.ONE_WEEK, GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR)) for (old in listOf(1345.7, 1337.7)) for (case in listOf("i", "ii")) {
            val label = "$period $old ($case)"
            val start = B - 3.hours + 2.minutes
            val f = fixture(this, start)
            f.openOn(exchange)
            f.events.onForeground(true)
            val longKey = GraphKey("tether", period)
            f.holders.getValue("tether").selectPeriod(f.token("tether"), period); f.run()
            f.answer(longKey, longEndingAt1341_5(period))
            val serverPoints = serverLine(period)
            val serverBand = serverBand(period)
            val shown = checkNotNull(f.state("tether").chart).prepared.bySeries.getValue(id(exchange))
            assertEquals("$label premise: the server points shown", serverPoints, shown.linePoints)
            assertEquals("$label premise: the server band shown", serverBand, shown.bandPoints)
            val key = f.seriesKey(exchange)
            if (case == "i") {
                f.live.value = mapOf(key to observeRecoverable(GraphRecoverableState.empty(key, GraphObservationOrder(emptyList())),
                    listOf(GraphObservation(key, GraphObservationId(exchange, "usdt-krw", start, old))), start).state)
                f.run()
            } else {
                f.exchange(exchange, old, start)
            }
            f.hide("tether"); f.events.onForeground(false)
            f.advance(3.hours.inWholeMilliseconds)
            val mark = f.published.getValue("tether").size
            f.events.onForeground(true); f.show("tether")
            f.coordinator.onRefreshRequested(force = true); f.run()
            val failing = f.sent.filter { it.key == longKey && !it.answer.isCompleted }
            assertTrue("$label premise: a long request to fail", failing.isNotEmpty())
            failing.forEach { it.answer.complete(TetherGraphData.status(503)) }
            f.run(); f.advance(400)
            val before = checkNotNull(f.state("tether").chart)
            val beforeSeries = before.prepared.bySeries.getValue(id(exchange))
            assertEquals("$label: the server points, no app end point", serverPoints, beforeSeries.linePoints)
            assertEquals("$label: the server band", serverBand, beforeSeries.bandPoints)
            assertEquals("$label: the server trailing rule", serverPlotEnd(period, serverPoints.last(), checkNotNull(before.rightEdgeNow)), plotEnd(before, exchange))

            f.exchange(exchange, 1341.7, B + 2.minutes)
            f.advance(400)
            val after = checkNotNull(f.state("tether").chart)
            val now = checkNotNull(after.rightEdgeNow)
            val afterSeries = after.prepared.bySeries.getValue(id(exchange))
            assertEquals("$label: the right edge", LinePoint(now, 1341.7), afterSeries.linePoints.last())
            assertEquals("$label: the server points kept", serverPoints, afterSeries.linePoints.dropLast(1))
            assertEquals("$label: the server band kept", serverBand, afterSeries.bandPoints)
            assertEquals("$label: the plot ends at now", LinePoint(now, 1341.7), plotEnd(after, exchange))

            f.holders.getValue("tether").selectPeriod(f.token("tether"), GraphPeriod.ONE_DAY); f.run()
            f.sent.filter { it.key == oneDay && !it.answer.isCompleted }.forEach { it.answer.complete(dayWith()) }
            f.run(); f.advance(400)
            assertEquals("$label: back on 1d the record is in B", 1341.7 to 1341.7, f.series(exchange).bandPoints.last().let { it.low to it.high })
            f.holders.getValue("tether").selectPeriod(f.token("tether"), period); f.run()
            f.sent.filter { it.key == longKey && !it.answer.isCompleted }.forEach { it.answer.complete(longEndingAt1341_5(period)) }
            f.run(); f.advance(400)
            val backChart = checkNotNull(f.state("tether").chart)
            val back = backChart.prepared.bySeries.getValue(id(exchange))
            assertEquals("$label: back on $period the server points", serverPoints, back.linePoints.filter { it.ts < B - 1.hours })
            assertEquals("$label: back on $period the server band", serverBand, back.bandPoints)
            val backEnd = LinePoint(checkNotNull(backChart.rightEdgeNow), 1341.7)
            assertEquals("$label: back on $period the right edge", backEnd, back.linePoints.last())
            assertEquals("$label: back on $period the plot end", backEnd, plotEnd(backChart, exchange))

            val longPublications = f.tetherPublications(mark).filter { it.prepared.period == period }
            assertTrue("$label premise: long publications from the return on", longPublications.size >= 2)
            for (chart in longPublications) {
                val s = chart.prepared.bySeries.getValue(id(exchange))
                assertFalse("$label: the old value in no long publication", old in s.values())
                assertEquals("$label: the server band in every long publication", serverBand, s.bandPoints)
                assertEquals("$label: the server points in every long publication", serverPoints, s.linePoints.filter { it.ts < B - 1.hours })
            }
            f.close()
        }
    }

    /**
     * T21 freshness boundary for each exchange on 1d (the tip in the previous bucket, nothing in the current one): with 11:51
     * 1341.9 and 11:59 1341.3 in the 11:50 bucket, a tip 599.999 s old ends the line at now, one exactly 600 s old or older does
     * not; the 11:50 bucket keeps its close 1341.3 and its band [1341.3, 1341.9] either way.
     */
    @Test fun T21_oneDayFreshnessIsStrictAt600Seconds() = publishTest {
        val tipAt = B - 1.minutes
        for (exchange in EXCHANGES) for ((ageMs, used) in listOf(599_999L to true, 600_000L to false, 601_000L to false)) {
            val label = "$exchange ${ageMs}ms"
            val f = fixture(this, tipAt)
            f.openOn(exchange)
            f.exchange(exchange, 1341.9, B - 9.minutes)
            f.exchange(exchange, 1341.3, tipAt)
            f.advance(ageMs)
            f.holders.getValue("tether").onTimeEvent(); f.run()
            val chart = checkNotNull(f.state("tether").chart)
            val now = checkNotNull(chart.rightEdgeNow)
            assertEquals("$label: published at that age", tipAt + ageMs.milliseconds, now)
            val s = chart.prepared.bySeries.getValue(id(exchange))
            assertEquals("$label", used, s.linePoints.lastOrNull() == LinePoint(now, 1341.3))
            assertTrue("$label: the 11:50 close stays", s.linePoints.any { it.ts == B - 10.minutes && it.rate == 1341.3 })
            assertTrue("$label: the 11:50 band stays", s.bandPoints.any { it.ts == B - 10.minutes && it.low == 1341.3 && it.high == 1341.9 })
            f.close()
        }
    }

    /**
     * T21 freshness boundary for bithumb on each long period: the same strict 600 s for the right edge, published at the exact
     * age; the server points and band stay, the plot follows the server trailing rule when the tip is not used, and back on 1d
     * the 11:50 bucket keeps its close and band.
     */
    @Test fun T21_longFreshnessIsStrictAt600Seconds() = publishTest {
        val exchange = "bithumb"
        val tipAt = B - 1.minutes
        for (period in listOf(GraphPeriod.ONE_WEEK, GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR)) for ((ageMs, used) in listOf(599_999L to true, 600_000L to false, 601_000L to false)) {
            val label = "$period ${ageMs}ms"
            val f = fixture(this, tipAt)
            f.openOn(exchange)
            f.holders.getValue("tether").selectPeriod(f.token("tether"), period); f.run()
            f.answer(GraphKey("tether", period), longEndingAt1341_5(period))
            val serverPoints = serverLine(period)
            val serverBand = serverBand(period)
            f.exchange(exchange, 1341.9, B - 9.minutes)
            f.exchange(exchange, 1341.3, tipAt)
            f.advance(ageMs)
            f.holders.getValue("tether").onTimeEvent(); f.run()
            val chart = checkNotNull(f.state("tether").chart)
            val now = checkNotNull(chart.rightEdgeNow)
            assertEquals("$label: published at that age", tipAt + ageMs.milliseconds, now)
            val s = chart.prepared.bySeries.getValue(id(exchange))
            assertEquals("$label", used, s.linePoints.lastOrNull() == LinePoint(now, 1341.3))
            assertEquals("$label: the server points", serverPoints, if (used) s.linePoints.dropLast(1) else s.linePoints)
            assertEquals("$label: the server band", serverBand, s.bandPoints)
            if (!used) assertEquals("$label: the server trailing rule", serverPlotEnd(period, serverPoints.last(), now), plotEnd(chart, exchange))
            f.holders.getValue("tether").selectPeriod(f.token("tether"), GraphPeriod.ONE_DAY); f.run()
            f.sent.filter { it.key == oneDay && !it.answer.isCompleted }.forEach { it.answer.complete(dayWith()) }
            f.run()
            val day = f.series(exchange)
            assertTrue("$label: back on 1d the 11:50 close stays", day.linePoints.any { it.ts == B - 10.minutes && it.rate == 1341.3 })
            assertTrue("$label: back on 1d the 11:50 band stays", day.bandPoints.any { it.ts == B - 10.minutes && it.low == 1341.3 && it.high == 1341.9 })
            f.close()
        }
    }
}
