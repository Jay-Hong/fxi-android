package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.graph.DXY_GRAPH_OBSERVATION_ORDER
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphObservation
import com.jay.fxi.data.graph.GraphObservationId
import com.jay.fxi.data.graph.GraphObservationOrder
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphRecoverableState
import com.jay.fxi.data.graph.GraphRecoveryReason
import com.jay.fxi.data.graph.applyGraphRecoveryResponse
import com.jay.fxi.data.graph.captureGraphRecoveryRequest
import com.jay.fxi.data.graph.observeRecoverable
import com.jay.fxi.data.graph.requireGraphRecovery
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphCarryIn
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphPeriod.ONE_DAY
import com.jay.fxi.domain.model.GraphPeriod.ONE_WEEK
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.ui.graph.BandPoint
import com.jay.fxi.ui.graph.GraphFrame
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.LinePoint
import com.jay.fxi.ui.graph.PreparedGraph
import com.jay.fxi.ui.graph.PreparedSeries
import com.jay.fxi.ui.graph.TimeFrame
import com.jay.fxi.ui.graph.TimeRange
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 C2a contract r3 (JVM): the pure live projection - the exposed REST graph and the recorder's D3 states
 * turned into one PreparedGraph, with no clock read, no publishing and no new display types.
 *
 * Oracles: ANDROID_V2_PLAN.md :1305-1316 (server and app ranges kept apart and combined only for display, one merge rule; a
 * closed bucket the server replaced is not recomposed with app observations; another bucket's absence is not a replacement;
 * close = latest app observation, else the seed close), :1317-1319 (timers move the axis and decide line use only; freshness
 * 600 s / hana 1200 s is a line-use rule and never removes extremes), :1320-1321 (24 h window plus one hour of retention;
 * rendered in 1d only), :1349-1350 (only the current bucket reaches now; no re-widening from a timeless value; no invalid
 * stretch bridged by a neighbour's band; the line's last price never flows into the band; band shape unchanged; no DXY band),
 * :1351-1352 (long periods: only a verified, line-eligible latest value at the right edge; ten-minute app buckets never enter
 * the long series), :1365 (no source-less old tip), :1367 (23:59 -> 00:00); iOS 89e866d
 * GRAPH_LIVE_BAND_IMPLEMENTATION_PLAN.md :132, :191-195, :206-208, :253-269 (T05, T05-L, T09, T16, T16-L, T21) and
 * GraphV2Section.swift :153-159, :1454, :1459 (freshness: hana 1200 s, else 600 s, strict `<`).
 * Design: R4c/S4 c2_design_codex.r1, revised by c2_review_claude.r1 after a five-lens verification
 * (c2_design_verify_workflow.result.json) and agreed in c2_review_codex.r1; r2 adds the decisions a four-agent completeness
 * workflow showed were open (c2a_contract.r1/completeness_workflow.result.json); r3 (battery c2a_r1 survivor C28) adds to
 * L18 a fresh tip from an earlier unreplaced slot and from a later slot beside a REST point at b - neither ends the line:
 *  - graphLineFreshness(source) = 1200 s for "hana", 600 s otherwise. A tip is line-eligible iff now - observedAt < it
 *    (a negative age is fresh, the threshold itself is stale). The tip keeps its own time; freshness never edits buckets.
 *  - projectGraphV2Live(graph, prepared, period, scope, live, now): for each exposed series, in the graph's order, the state
 *    is live[(scope, seriesId)] exactly (both scope fields); nothing else is read and no series is invented. pending and
 *    gapHistory are never read. The tip is each series' own.
 *  - 1d: b = graphObservationBucketStart(now). The series' points are its REST points (as sent) plus, for every D3 slot s
 *    with b - 24 h <= s <= b and no REST point at s, composeGraphBucket(state.data, s) as FreeGraphPoint(start, close, high,
 *    low) - a D3 Closed record as is, a seed-only slot from its seed. The retention hour before b - 24 h and slots after b
 *    are not shown. The merged series is the REST series with these points (label, axis, history flags and carry-in kept),
 *    rebuilt with GraphPreparedBuilder: gap hold, band collapse, extrema, dataBounds and lastObservation are the builder's.
 *  - Then, only while now is later than the series' last line point (a REST point after now adds nothing):
 *    an unreplaced (app or seed) bucket at b reaches now - BandPoint(now, low, high) and LinePoint(now, close) - when now > b,
 *    nothing at now == b; a REST or Closed point at b is a finished bucket and adds nothing, not even a tip; with no merged
 *    point at b, a line-eligible tip whose slot is not a REST or Closed point of the merged series appends LinePoint(now,
 *    tip.rate) and no band - provided the series already has a line point. When the last merged point is an unreplaced
 *    bucket before b with high and low (zero width included), BandPoint(s + 600 s, close, close) is appended with that
 *    bucket's close - a lone bucket stays visible and nothing widens past its own end. A REST or Closed last point gets none.
 *  - Long periods (1w, 3m, 1y): start from `prepared`; with the same order and non-empty conditions, a line-eligible tip
 *    appends LinePoint(now, tip.rate), becomes lastObservation and widens extrema by its rate only (no bucket range, no
 *    seed close, no rebuild); everything else - bands, domain, dataBounds, carry-in - is `prepared`'s. Otherwise the series
 *    is `prepared`'s, and the existing trailing hold stays the fallback.
 *  - Index series (axisGroup "index"): bandPoints are empty in every period and path, with no band extension or shoulder;
 *    extrema stay rate-only; a long fresh tip still becomes lastObservation.
 *  - Extrema include an appended end point's rate. With no live state a series equals its REST-only result (1d rebuilt,
 *    long `prepared`), except that an index series' bandPoints are empty - the renderer never draws them either.
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphV2LiveProjectionTest {

    private val scope = GraphDataScope("u1", "e1")
    private val otherScope = GraphDataScope("u2", "e2")

    private fun at(day: Int, hhmmss: String): Instant = Instant.parse("2026-10-${day.toString().padStart(2, '0')}T$hhmmss+09:00")
    private fun kst(hhmmss: String): Instant = at(7, hhmmss)

    private val a = kst("19:50:00")
    private val b = kst("20:00:00")
    private val c = kst("20:10:00")

    private fun key(id: String, s: GraphDataScope = scope) = GraphObservationSeriesKey(s, id)
    private fun empty(id: String, s: GraphDataScope = scope, order: GraphObservationOrder = GraphObservationOrder(emptyList())) =
        GraphRecoverableState.empty(key(id, s), order)
    private fun observe(
        state: GraphRecoverableState,
        now: Instant,
        vararg values: Pair<Instant, Double>,
        source: String = "kb",
        asset: String = "usd-krw"
    ): GraphRecoverableState = observeRecoverable(
        state, values.map { (t, rate) -> GraphObservation(state.data.app.seriesKey, GraphObservationId(source, asset, t, rate)) }, now
    ).state

    private fun point(t: Instant, rate: Double, low: Double? = rate, high: Double? = rate) = FreeGraphPoint(t, rate, high, low)
    private fun series(id: String, points: List<FreeGraphPoint>, axis: String = "krw") = FreeGraphSeries(id, points, axisGroup = axis)
    private fun day(vararg s: FreeGraphSeries, end: Instant = kst("20:00:12")) =
        FreeGraph("10min", s.toList(), domainStartAt = end - 24.hours, domainEndAt = end, liveDomainMode = "rolling")
    private fun week(vararg s: FreeGraphSeries) =
        FreeGraph("1h", s.toList(), domainStartAt = at(1, "00:00:00"), domainEndAt = kst("19:00:00"), liveDomainMode = "fixed_start")

    private fun responseTab(id: String, now: Instant, points: List<FreeGraphPoint> = emptyList(), seed: GraphV2InProgress? = null) =
        GraphV2Tab(
            "usd", ONE_DAY, "10min", now, FreeGraph("10min", listOf(FreeGraphSeries(id, points))),
            if (seed != null) mapOf(id to seed) else emptyMap()
        )
    private fun respond(state: GraphRecoverableState, now: Instant, points: List<FreeGraphPoint> = emptyList(), seed: GraphV2InProgress? = null) =
        applyGraphRecoveryResponse(state, scope, captureGraphRecoveryRequest(state, 0), responseTab(state.data.app.seriesKey.seriesId, now, points, seed), now)

    private fun project(graph: FreeGraph, period: GraphPeriod, now: Instant, vararg live: GraphRecoverableState): PreparedGraph =
        projectGraphV2Live(graph, GraphPreparedBuilder.build(graph, period), period, scope, live.associateBy { it.data.app.seriesKey }, now)
    private fun PreparedGraph.of(id: String): PreparedSeries = bySeries.getValue(id)
    private fun PreparedSeries.values(): List<Double> =
        linePoints.map { it.rate } + bandPoints.flatMap { listOf(it.low, it.high) } + listOfNotNull(extrema?.start, extrema?.endInclusive)

    private val restDay = day(series("kb.usd", listOf(point(kst("19:30:00"), 1341.0, 1340.8, 1341.2), point(kst("19:40:00"), 1341.2, 1341.0, 1341.4))))

    // --- 1d ---------------------------------------------------------------------------------------------------------

    /**
     * L01 (T05, T05-L): a real 17:02 observation stays in its own bucket and, three hours old, is no line end point - before
     * and after a timer moves now. A value that lived only in an earlier published snapshot is not in the inputs, and the
     * projection keeps nothing between calls: with a fresh state the old value appears nowhere, and B shows the new record.
     */
    @Test fun L01_T05_theProjectionHoldsNoValueBeyondItsInputs() {
        val now = kst("20:02:00")
        for (old in listOf(1345.7, 1337.7)) {
            val withOld = observe(empty("kb.usd"), kst("17:05:00"), kst("17:02:00") to old)
            for (moment in listOf(now, kst("20:05:00"))) {
                val s = project(restDay, ONE_DAY, moment, withOld).of("kb.usd")
                assertEquals("$old $moment", BandPoint(kst("17:00:00"), old, old), s.bandPoints.first())
                assertEquals("$old $moment", kst("19:40:00"), s.linePoints.last().ts)
                assertTrue("$old $moment", s.bandPoints.none { it.ts >= b })
            }
            val fresh = observe(empty("kb.usd"), now, kst("20:01:30") to 1341.7)
            val s = project(restDay, ONE_DAY, now, fresh).of("kb.usd")
            assertFalse("$old", old in s.values())
            assertTrue("$old", BandPoint(b, 1341.7, 1341.7) in s.bandPoints)
            assertEquals("$old", BandPoint(now, 1341.7, 1341.7), s.bandPoints.last())
            assertEquals("$old", LinePoint(now, 1341.7), s.linePoints.last())
        }
    }

    /**
     * L02 (T09): REST ends at 19:40, A has app range [1341.5, 1342.1] with close 1341.5, B has nothing, now 20:02. A's band
     * collapses at its own end (20:00) and B has none. A stale tip (19:51) adds no end point; a fresh one (19:55) adds a line
     * end point at now but no band. Nothing widens the range.
     */
    @Test fun L02_T09_onlyTheCurrentBucketReachesNow() {
        val now = kst("20:02:00")
        val stale = observe(empty("kb.usd"), kst("19:52:00"), kst("19:50:30") to 1342.1, kst("19:51:00") to 1341.5)
        val s = project(restDay, ONE_DAY, now, stale).of("kb.usd")
        assertEquals(
            listOf(
                BandPoint(kst("19:30:00"), 1340.8, 1341.2),
                BandPoint(kst("19:40:00"), 1341.0, 1341.4),
                BandPoint(a, 1341.5, 1342.1),
                BandPoint(b, 1341.5, 1341.5)
            ),
            s.bandPoints
        )
        assertEquals(LinePoint(a, 1341.5), s.linePoints.last())
        assertEquals(1340.8..1342.1, s.extrema)

        val fresh = observe(empty("kb.usd"), kst("19:56:00"), kst("19:50:30") to 1342.1, kst("19:55:00") to 1341.5)
        val f = project(restDay, ONE_DAY, now, fresh).of("kb.usd")
        assertEquals(BandPoint(b, 1341.5, 1341.5), f.bandPoints.last())
        assertEquals(LinePoint(now, 1341.5), f.linePoints.last())
        assertEquals(1340.8..1342.1, f.extrema)
    }

    /**
     * L03 (T16 final values, T16-L): a current-bucket seed [1341.7, 1341.9] with an app high 1342.1, a later 1342.2, an app
     * low 1341.5, a later 1341.4 - B carries the composed range, the band reaches now with it and the line ends at the close.
     */
    @Test fun L03_T16_theCurrentBucketCarriesTheComposedRange() {
        val now = kst("20:05:00")
        val seed = GraphV2InProgress(b, 1341.9, 1341.7, 1341.8, kst("20:04:00"))
        for ((obsAt, rate, low, high) in listOf(
            Quad(kst("20:01:00"), 1342.1, 1341.7, 1342.1),
            Quad(kst("20:03:00"), 1342.2, 1341.7, 1342.2),
            Quad(kst("20:01:00"), 1341.5, 1341.5, 1341.9),
            Quad(kst("20:03:00"), 1341.4, 1341.4, 1341.9)
        )) {
            val state = observe(respond(empty("kb.usd"), now, seed = seed), now, obsAt to rate)
            val s = project(restDay, ONE_DAY, now, state).of("kb.usd")
            assertTrue("$rate", BandPoint(b, low, high) in s.bandPoints)
            assertEquals("$rate", BandPoint(now, low, high), s.bandPoints.last())
            assertEquals("$rate", LinePoint(now, rate), s.linePoints.last())
        }
    }

    private data class Quad(val t: Instant, val rate: Double, val low: Double, val high: Double)

    /** L04: a slot with a REST closed point shows that point, not the wider D3 app range; a D3 Closed fills a slot REST lacks. */
    @Test fun L04_aRestClosedPointWinsItsSlot() {
        val now = kst("20:15:00")
        var state = observe(empty("kb.usd"), kst("20:03:00"), kst("20:01:00") to 1341.0, kst("20:02:00") to 1343.0)
        state = respond(state, now, points = listOf(point(a, 1341.6, 1341.5, 1341.7)))
        val graph = day(series("kb.usd", listOf(point(b, 1341.8, 1341.7, 1341.9))))
        val s = project(graph, ONE_DAY, now, state).of("kb.usd")
        assertEquals(listOf(BandPoint(a, 1341.5, 1341.7), BandPoint(b, 1341.7, 1341.9)), s.bandPoints)
        assertEquals(listOf(LinePoint(a, 1341.6), LinePoint(b, 1341.8)), s.linePoints)
        assertEquals(1341.5..1341.9, s.extrema)
    }

    /**
     * L05: a slot after b (an observation one slot ahead, admitted by D3) is not shown; the line end uses B's close, not that
     * later tip.
     */
    @Test fun L05_aSlotAfterTheCurrentOneIsNotShown() {
        val now = kst("20:07:00")
        val state = observe(empty("kb.usd"), now, kst("20:01:00") to 1341.7, kst("20:10:30") to 1342.0)
        val s = project(restDay, ONE_DAY, now, state).of("kb.usd")
        assertTrue(s.bandPoints.none { it.ts == c } && s.linePoints.none { it.ts == c })
        assertEquals(BandPoint(now, 1341.7, 1341.7), s.bandPoints.last())
        assertEquals(LinePoint(now, 1341.7), s.linePoints.last())
        assertFalse(1342.0 in s.values())
    }

    /** L06: across a three-hour gap after a 17:00 bucket the line holds at 19:50 and the band collapses at 17:10 and 19:50. */
    @Test fun L06_aGapIsHeldNotDrawnAsADrift() {
        val now = kst("20:02:00")
        var state = observe(empty("kb.usd"), kst("17:02:00"), kst("17:01:00") to 1341.9)
        state = observe(state, now, kst("20:01:00") to 1341.2)
        val graph = day(series("kb.usd", listOf(point(kst("16:40:00"), 1341.0), point(kst("16:50:00"), 1341.1))))
        val s = project(graph, ONE_DAY, now, state).of("kb.usd")
        assertTrue(LinePoint(kst("19:50:00"), 1341.9) in s.linePoints)
        assertTrue(BandPoint(kst("17:10:00"), 1341.9, 1341.9) in s.bandPoints)
        assertTrue(BandPoint(kst("19:50:00"), 1341.9, 1341.9) in s.bandPoints)
        assertEquals(LinePoint(now, 1341.2), s.linePoints.last())
        assertEquals(BandPoint(now, 1341.2, 1341.2), s.bandPoints.last())
    }

    /**
     * L07: a lone app bucket B [1341.7, 1342.1] (close 1341.9 at 20:09) with no REST points: while current it reaches now; once
     * C is current it collapses at its own end 20:10, with a line end point only while its tip is fresh. A lone D3 Closed
     * record gets no shoulder, like a REST point.
     */
    @Test fun L07_aLoneBucketStaysVisibleAndEndsAtItsOwnEnd() {
        val graph = day(series("kb.usd", emptyList()))
        val state = observe(empty("kb.usd"), kst("20:09:30"), kst("20:00:30") to 1342.1, kst("20:01:00") to 1341.7, kst("20:09:00") to 1341.9)

        val current = project(graph, ONE_DAY, kst("20:09:59"), state).of("kb.usd")
        assertEquals(listOf(BandPoint(b, 1341.7, 1342.1), BandPoint(kst("20:09:59"), 1341.7, 1342.1)), current.bandPoints)
        assertEquals(listOf(LinePoint(b, 1341.9), LinePoint(kst("20:09:59"), 1341.9)), current.linePoints)

        val next = project(graph, ONE_DAY, c, state).of("kb.usd")
        assertEquals(listOf(BandPoint(b, 1341.7, 1342.1), BandPoint(c, 1341.9, 1341.9)), next.bandPoints)
        assertEquals(listOf(LinePoint(b, 1341.9), LinePoint(c, 1341.9)), next.linePoints)

        val late = project(graph, ONE_DAY, kst("20:30:00"), state).of("kb.usd")
        assertEquals(listOf(BandPoint(b, 1341.7, 1342.1), BandPoint(c, 1341.9, 1341.9)), late.bandPoints)
        assertEquals(listOf(LinePoint(b, 1341.9)), late.linePoints)

        val closedOnly = respond(empty("kb.usd"), kst("20:05:00"), points = listOf(point(a, 1341.6, 1341.5, 1341.7)))
        val closed = project(graph, ONE_DAY, kst("20:05:00"), closedOnly).of("kb.usd")
        assertEquals(listOf(BandPoint(a, 1341.5, 1341.7)), closed.bandPoints)
        assertEquals(listOf(LinePoint(a, 1341.6)), closed.linePoints)
    }

    /** L08: at now == b exactly the current bucket adds no extension point - width zero, no duplicate time. */
    @Test fun L08_atTheBucketStartNothingIsExtended() {
        val state = observe(empty("kb.usd"), b, b to 1341.7)
        val s = project(day(series("kb.usd", emptyList())), ONE_DAY, b, state).of("kb.usd")
        assertEquals(listOf(BandPoint(b, 1341.7, 1341.7)), s.bandPoints)
        assertEquals(listOf(LinePoint(b, 1341.7)), s.linePoints)
    }

    /**
     * L09 (23:59 -> 00:00): at 23:59:55 KST the 23:50 bucket reaches now; at 00:00:05 the 00:00 KST bucket is current and
     * reaches now while 23:50 needs no shoulder; the rolling frame keeps its 24 h length at the new right edge. A fixed_start
     * long frame ends at max(domain end, now).
     */
    @Test fun L09_theDayRollsOverAtMidnight() {
        val graph = day(series("kb.usd", emptyList()), end = at(7, "23:59:12"))
        var state = observe(empty("kb.usd"), at(7, "23:52:00"), at(7, "23:51:00") to 1341.5)
        val before = project(graph, ONE_DAY, at(7, "23:59:55"), state).of("kb.usd")
        assertEquals(BandPoint(at(7, "23:59:55"), 1341.5, 1341.5), before.bandPoints.last())

        state = observe(state, at(8, "00:00:03"), at(8, "00:00:02") to 1341.6)
        val after = project(graph, ONE_DAY, at(8, "00:00:05"), state)
        assertEquals(
            listOf(
                BandPoint(at(7, "23:50:00"), 1341.5, 1341.5),
                BandPoint(at(8, "00:00:00"), 1341.6, 1341.6),
                BandPoint(at(8, "00:00:05"), 1341.6, 1341.6)
            ),
            after.of("kb.usd").bandPoints
        )
        assertEquals(TimeFrame(at(7, "00:00:05"), at(8, "00:00:05")), GraphFrame.resolve(after, at(8, "00:00:05")))

        val long = week(series("kb.usd", listOf(point(kst("18:00:00"), 1341.3), point(kst("19:00:00"), 1341.5))))
        assertEquals(kst("20:02:00"), GraphFrame.resolve(project(long, ONE_WEEK, kst("20:02:00")), kst("20:02:00"))?.end)
    }

    /** L10: a series with no REST points but live buckets is drawable - it has a last observation, a line and extrema. */
    @Test fun L10_aLiveOnlySeriesIsDrawable() {
        val state = observe(empty("kb.usd"), kst("20:02:00"), kst("20:01:00") to 1341.7)
        val s = project(day(series("kb.usd", emptyList())), ONE_DAY, kst("20:02:00"), state).of("kb.usd")
        assertEquals(LinePoint(b, 1341.7), s.lastObservation)
        assertTrue(s.linePoints.isNotEmpty())
        assertEquals(1341.7..1341.7, s.extrema)
    }

    // --- long periods, freshness, DXY, independence -------------------------------------------------------------------

    /**
     * L11 (T21): on 1w a fresh tip becomes the right-edge end point, the last observation and part of the extrema, while the
     * REST band stays as it was and the trailing hold adds nothing; a stale tip leaves the REST series as it was, and the
     * trailing hold supplies the server's last value. A dollar index works the same with its band empty.
     */
    @Test fun L11_T21_theLongRightEdgeTakesOnlyAFreshTip() {
        val now = kst("20:02:00")
        val dxy = series("dxy", listOf(point(kst("18:00:00"), 105.4, 105.2, 105.6), point(kst("19:00:00"), 105.5, 105.3, 105.7)), axis = "index")
        val graph = week(series("kb.usd", listOf(point(kst("18:00:00"), 1341.3), point(kst("19:00:00"), 1341.5))), dxy)
        val base = GraphPreparedBuilder.build(graph, ONE_WEEK)

        for ((rate, top) in listOf(1341.7 to 1341.7, 1343.0 to 1343.0)) {
            val fresh = observe(empty("kb.usd"), kst("20:01:30"), kst("20:01:00") to rate)
            val out = project(graph, ONE_WEEK, now, fresh)
            val s = out.of("kb.usd")
            assertEquals("$rate", base.of("kb.usd").linePoints + LinePoint(now, rate), s.linePoints)
            assertEquals("$rate", base.of("kb.usd").bandPoints, s.bandPoints)
            assertEquals("$rate", LinePoint(now, rate), s.lastObservation)
            assertEquals("$rate", 1341.3..top, s.extrema)
            assertNull("$rate", GraphFrame.trailingHold(s, ONE_WEEK, GraphFrame.resolve(out, now)!!))
        }

        val stale = observe(empty("kb.usd"), kst("19:51:00"), kst("19:50:00") to 1341.7)
        val out = project(graph, ONE_WEEK, now, stale)
        assertEquals(base.of("kb.usd"), out.of("kb.usd"))
        assertEquals(LinePoint(now, 1341.5), GraphFrame.trailingHold(out.of("kb.usd"), ONE_WEEK, GraphFrame.resolve(out, now)!!))

        val dxyState = observe(empty("dxy", order = DXY_GRAPH_OBSERVATION_ORDER), kst("20:01:30"), kst("20:01:00") to 105.1, source = "investing", asset = "dxy")
        val d = project(graph, ONE_WEEK, now, dxyState).of("dxy")
        assertTrue(d.bandPoints.isEmpty())
        assertEquals(LinePoint(now, 105.1), d.linePoints.last())
        assertEquals(105.1..105.5, d.extrema)
    }

    /**
     * L12: freshness is 600 s, 1200 s for hana, strict - a tip one nanosecond younger than the threshold is an end point, one
     * exactly at it is not, and a tip from a second in the future is. Buckets are untouched either way.
     */
    @Test fun L12_freshnessIsStrictAndPerSource() {
        assertEquals(1200.seconds, graphLineFreshness("hana"))
        for (source in listOf("kb", "investing", "woori", "cnbc", "yahoo")) assertEquals(source, 600.seconds, graphLineFreshness(source))

        val now = kst("20:02:00")
        val graph = week(
            series("kb.usd", listOf(point(kst("19:00:00"), 1341.5))),
            series("hana.usd", listOf(point(kst("19:00:00"), 1341.6)))
        )
        val base = GraphPreparedBuilder.build(graph, ONE_WEEK)
        val cases = listOf(
            Triple("kb", now - (600.seconds - 1.nanoseconds), true),
            Triple("kb", now - 600.seconds, false),
            Triple("kb", now + 1.seconds, true),
            Triple("hana", now - (1200.seconds - 1.nanoseconds), true),
            Triple("hana", now - 1200.seconds, false)
        )
        for ((source, tipAt, fresh) in cases) {
            val id = "$source.usd"
            val state = observe(empty(id), now, tipAt to 1341.9, source = source)
            val s = project(graph, ONE_WEEK, now, state).of(id)
            if (fresh) assertEquals("$source $tipAt", LinePoint(now, 1341.9), s.linePoints.last())
            else assertEquals("$source $tipAt", base.of(id), s)
        }
    }

    /**
     * L13 (DXY, 1d): the index series gets no band in any form, its line runs through the composed close to now, and its
     * extrema stay rate-only (105.1, a low only, is not in them); the KRW series beside it is untouched.
     */
    @Test fun L13_theDollarIndexHasALineAndNoBand() {
        val now = kst("20:03:00")
        val graph = day(
            series("kb.usd", listOf(point(kst("19:40:00"), 1341.2, 1341.0, 1341.4))),
            series("dxy", listOf(point(kst("19:40:00"), 105.4, 105.3, 105.5)), axis = "index")
        )
        val dxyState = observe(
            empty("dxy", order = DXY_GRAPH_OBSERVATION_ORDER), now,
            kst("20:01:00") to 105.1, kst("20:02:00") to 105.3, source = "investing", asset = "dxy"
        )
        val out = project(graph, ONE_DAY, now, dxyState)
        val d = out.of("dxy")
        assertTrue(d.bandPoints.isEmpty())
        assertEquals(
            listOf(LinePoint(kst("19:40:00"), 105.4), LinePoint(a, 105.4), LinePoint(b, 105.3), LinePoint(now, 105.3)),
            d.linePoints
        )
        assertEquals(105.3..105.4, d.extrema)
        assertEquals(GraphPreparedBuilder.build(graph, ONE_DAY).of("kb.usd"), out.of("kb.usd"))
    }

    /** L14: pending demands and gap history - even a whole-window HANDOVER_LOSS - change nothing the projection shows. */
    @Test fun L14_recoveryBookkeepingIsNotDisplayInput() {
        val now = kst("20:02:00")
        val s0 = observe(empty("kb.usd"), now, kst("20:01:00") to 1341.7)
        var s1 = requireGraphRecovery(s0, kst("19:58:00"), kst("20:02:00"), GraphRecoveryReason.RECEIVE_GAP, now)
        s1 = requireGraphRecovery(s1, at(6, "20:00:00"), c, GraphRecoveryReason.HANDOVER_LOSS, now)
        assertTrue(s1.pending.isNotEmpty() && s1.gapHistory.size > 100)
        assertEquals(project(restDay, ONE_DAY, now, s0), project(restDay, ONE_DAY, now, s1))
    }

    /**
     * L15: only (scope, exposed series id) is read - a state of another scope or of a series the graph does not expose changes
     * nothing, the graph's order is kept, no series is added, and with no matching live state the output is the REST-only
     * result (1d rebuilt, long `prepared`). The same input twice gives the same output.
     */
    @Test fun L15_onlyTheExposedSeriesOfThisScopeIsRead() {
        val now = kst("20:02:00")
        val graph = day(
            series("kb.usd", listOf(point(kst("19:40:00"), 1341.2))),
            series("hana.usd", listOf(point(kst("19:40:00"), 1341.3)))
        )
        val foreign = observe(empty("kb.usd", s = otherScope), now, kst("20:01:00") to 1350.0)
        val unexposed = observe(empty("woori.usd"), now, kst("20:01:00") to 1349.0, source = "woori")
        val out = project(graph, ONE_DAY, now, foreign, unexposed)
        assertEquals(listOf("kb.usd", "hana.usd"), out.order)
        assertEquals(setOf("kb.usd", "hana.usd"), out.bySeries.keys)
        assertEquals(GraphPreparedBuilder.build(graph, ONE_DAY), out)
        assertEquals(out, project(graph, ONE_DAY, now, foreign, unexposed))

        val long = week(series("kb.usd", listOf(point(kst("19:00:00"), 1341.5))))
        assertEquals(GraphPreparedBuilder.build(long, ONE_WEEK), project(long, ONE_WEEK, now))
    }

    /** L16: the projection keeps nothing between calls - a state with the 1345.7 bucket, then one without it. */
    @Test fun L16_noValueOutlivesTheCallThatHadIt() {
        val now = kst("20:02:00")
        val first = observe(empty("kb.usd"), now, kst("20:01:00") to 1345.7)
        assertTrue(1345.7 in project(restDay, ONE_DAY, now, first).of("kb.usd").values())
        val second = observe(empty("kb.usd"), now, kst("20:01:30") to 1341.7)
        val s = project(restDay, ONE_DAY, now, second).of("kb.usd")
        assertFalse(1345.7 in s.values())
        assertNotNull(s.lastObservation)
    }

    // --- r2: decisions and completeness rows ---------------------------------------------------------------------------

    /**
     * L17: a seed-only bucket is a merged point: while current it reaches now with the seed range and close; once past, it
     * collapses at its own end with its own close - not an earlier bucket's tip - and, with no app tip, adds no line end.
     */
    @Test fun L17_aSeedOnlyBucketIsShownAndCollapsesWithItsOwnClose() {
        val graph = day(series("kb.usd", emptyList()))
        val seed = GraphV2InProgress(b, 1341.9, 1341.7, 1341.8, kst("20:04:00"))
        val st = respond(empty("kb.usd"), kst("20:05:00"), seed = seed)
        val current = project(graph, ONE_DAY, kst("20:05:00"), st).of("kb.usd")
        assertEquals(listOf(BandPoint(b, 1341.7, 1341.9), BandPoint(kst("20:05:00"), 1341.7, 1341.9)), current.bandPoints)
        assertEquals(listOf(LinePoint(b, 1341.8), LinePoint(kst("20:05:00"), 1341.8)), current.linePoints)
        val past = project(graph, ONE_DAY, kst("20:12:00"), st).of("kb.usd")
        assertEquals(listOf(BandPoint(b, 1341.7, 1341.9), BandPoint(c, 1341.8, 1341.8)), past.bandPoints)
        assertEquals(listOf(LinePoint(b, 1341.8)), past.linePoints)

        val withEarlier = respond(observe(empty("kb.usd"), kst("19:52:00"), kst("19:51:00") to 1341.0), kst("20:05:00"), seed = seed)
        assertEquals(BandPoint(c, 1341.8, 1341.8), project(graph, ONE_DAY, kst("20:12:00"), withEarlier).of("kb.usd").bandPoints.last())
    }

    /**
     * L18: a REST or Closed point is the server's word for its slot. At b it is a finished bucket - no extension and no tip,
     * even with a wider or fresher app value behind it, or a fresh tip from an earlier unreplaced slot or a later one; a
     * band-less one draws its line only. REST also wins over a D3 Closed
     * record in the same slot, and a band-less REST point is not filled in from D3.
     */
    @Test fun L18_aServerPointIsTheServersWordForItsSlot() {
        val now = kst("20:05:00")
        val app = observe(empty("kb.usd"), kst("20:03:00"), kst("20:01:00") to 1343.0)
        val s = project(day(series("kb.usd", listOf(point(b, 1341.8, 1341.7, 1341.9)))), ONE_DAY, now, app).of("kb.usd")
        assertEquals(listOf(BandPoint(b, 1341.7, 1341.9)), s.bandPoints)
        assertEquals(listOf(LinePoint(b, 1341.8)), s.linePoints)
        assertEquals(1341.7..1341.9, s.extrema)
        assertFalse(1343.0 in s.values())

        val restAtB = day(series("kb.usd", listOf(point(b, 1341.8, 1341.7, 1341.9))))
        val earlierTip = observe(empty("kb.usd"), kst("19:59:30"), kst("19:59:00") to 1342.0)
        val early = project(restAtB, ONE_DAY, now, earlierTip).of("kb.usd")
        assertEquals(listOf(LinePoint(a, 1342.0), LinePoint(b, 1341.8)), early.linePoints)
        assertEquals(listOf(BandPoint(a, 1342.0, 1342.0), BandPoint(b, 1341.7, 1341.9)), early.bandPoints)
        val laterTip = observe(empty("kb.usd"), kst("20:07:00"), kst("20:10:30") to 1345.0)
        assertEquals(listOf(LinePoint(b, 1341.8)), project(restAtB, ONE_DAY, kst("20:07:00"), laterTip).of("kb.usd").linePoints)

        val bandless = respond(empty("kb.usd"), now, points = listOf(point(b, 1341.6, null, null)))
        val bl = project(day(series("kb.usd", emptyList())), ONE_DAY, now, bandless).of("kb.usd")
        assertTrue(bl.bandPoints.isEmpty())
        assertEquals(listOf(LinePoint(b, 1341.6)), bl.linePoints)

        val closedAtA = respond(empty("kb.usd"), now, points = listOf(point(a, 1341.6, 1341.5, 1341.7)))
        val r = project(day(series("kb.usd", listOf(point(a, 1341.4, 1341.3, 1341.5)))), ONE_DAY, now, closedAtA).of("kb.usd")
        assertEquals(listOf(BandPoint(a, 1341.3, 1341.5)), r.bandPoints)
        assertEquals(listOf(LinePoint(a, 1341.4)), r.linePoints)
        assertFalse(1341.6 in r.values())

        val appAtA = observe(empty("kb.usd"), kst("19:59:30"), kst("19:51:00") to 1342.0)
        val nb = project(day(series("kb.usd", listOf(point(a, 1341.4, null, null)))), ONE_DAY, kst("20:02:00"), appAtA).of("kb.usd")
        assertTrue(nb.bandPoints.isEmpty())
        assertEquals(listOf(LinePoint(a, 1341.4)), nb.linePoints)
    }

    /**
     * L19: the 1d tip end point - its rate widens the extrema while lastObservation stays the builder's; a tip from a slot after
     * b still counts as the latest price; a tip whose slot the server already closed is not used, the server's close being the
     * newer evidence for that slot; a series with no line point gets no tip end.
     */
    @Test fun L19_theTipEndPointFollowsTheServersClosedSlots() {
        val now = kst("20:07:00")
        val ahead = observe(empty("kb.usd"), now, kst("20:10:30") to 1345.0)
        val s = project(day(series("kb.usd", listOf(point(kst("19:40:00"), 1341.2)))), ONE_DAY, now, ahead).of("kb.usd")
        assertEquals(listOf(LinePoint(kst("19:40:00"), 1341.2), LinePoint(now, 1345.0)), s.linePoints)
        assertEquals(listOf(BandPoint(kst("19:40:00"), 1341.2, 1341.2)), s.bandPoints)
        assertEquals(1341.2..1345.0, s.extrema)
        assertEquals(LinePoint(kst("19:40:00"), 1341.2), s.lastObservation)

        val replaced = observe(empty("kb.usd"), kst("19:53:00"), kst("19:51:00") to 1341.0, kst("19:52:00") to 1343.0)
        val r = project(day(series("kb.usd", listOf(point(a, 1341.6, 1341.5, 1341.7)))), ONE_DAY, kst("20:01:00"), replaced).of("kb.usd")
        assertEquals(listOf(LinePoint(a, 1341.6)), r.linePoints)
        assertEquals(1341.5..1341.7, r.extrema)
        assertFalse(1343.0 in r.values())

        val lone = project(day(series("kb.usd", emptyList())), ONE_DAY, now, ahead).of("kb.usd")
        assertTrue(lone.linePoints.isEmpty())
        assertNull(lone.extrema)
        assertNull(lone.lastObservation)
    }

    /** L20: dataBounds come from the merged points - with no server domain the frame still runs from the first bucket to now. */
    @Test fun L20_dataBoundsComeFromTheMergedPoints() {
        val now = kst("20:02:00")
        val stale = observe(empty("kb.usd"), kst("19:52:00"), kst("19:50:30") to 1342.1, kst("19:51:00") to 1341.5)
        assertEquals(TimeRange(kst("19:30:00"), a), project(restDay, ONE_DAY, now, stale).dataBounds)

        val noDomain = FreeGraph("10min", listOf(series("kb.usd", emptyList())))
        val st = observe(empty("kb.usd"), now, kst("19:51:00") to 1341.5, kst("20:01:00") to 1341.7)
        val out = project(noDomain, ONE_DAY, now, st)
        assertEquals(TimeRange(a, b), out.dataBounds)
        assertEquals(TimeFrame(a, now), GraphFrame.resolve(out, now))
    }

    /**
     * L21: a D3 Closed record after b is not shown; a REST point after now (a slow device clock) keeps its place and no end
     * point is appended before it, so the line stays in time order.
     */
    @Test fun L21_nothingIsAppendedOutOfTimeOrder() {
        val now = kst("20:07:00")
        val closedAhead = respond(empty("kb.usd"), now, points = listOf(point(c, 1342.0, 1341.9, 1342.1)))
        val s = project(day(series("kb.usd", emptyList())), ONE_DAY, now, closedAhead).of("kb.usd")
        assertTrue(s.linePoints.isEmpty() && s.bandPoints.isEmpty())

        val restAhead = day(series("kb.usd", listOf(point(kst("19:40:00"), 1341.2), point(c, 1342.0))))
        val st = observe(empty("kb.usd"), now, kst("20:01:00") to 1341.7)
        val r = project(restAhead, ONE_DAY, now, st).of("kb.usd")
        assertTrue(r.linePoints.none { it.ts == now } && r.bandPoints.none { it.ts == now })
        assertEquals(r.linePoints.sortedBy { it.ts }, r.linePoints)
        assertEquals(LinePoint(c, 1342.0), r.linePoints.last())
    }

    /** L22: D3 slots in the retention hour before b - 24 h are not shown; the slot at b - 24 h is. */
    @Test fun L22_theRetentionHourIsNotShown() {
        val now = kst("20:02:00")
        var st = observe(empty("kb.usd"), now, at(6, "19:15:00") to 1350.0, at(6, "20:05:00") to 1341.0)
        st = observe(st, now, kst("20:01:00") to 1341.7)
        val s = project(restDay, ONE_DAY, now, st).of("kb.usd")
        assertFalse(1350.0 in s.values())
        assertTrue(BandPoint(at(6, "20:00:00"), 1341.0, 1341.0) in s.bandPoints)
        assertEquals(LinePoint(at(6, "20:00:00"), 1341.0), s.linePoints.first())
    }

    /** L23: a zero-width last bucket also gets its shoulder; its fresh tip still ends the line at now. */
    @Test fun L23_aZeroWidthBucketAlsoGetsItsShoulder() {
        val st = observe(empty("kb.usd"), kst("20:02:00"), kst("20:01:00") to 1341.7)
        val s = project(day(series("kb.usd", emptyList())), ONE_DAY, c, st).of("kb.usd")
        assertEquals(listOf(BandPoint(b, 1341.7, 1341.7), BandPoint(c, 1341.7, 1341.7)), s.bandPoints)
        assertEquals(listOf(LinePoint(b, 1341.7), LinePoint(c, 1341.7)), s.linePoints)
    }

    /** L24: the rebuilt series keeps the REST series' label, axis and history flag. */
    @Test fun L24_theRebuiltSeriesKeepsItsMetadata() {
        val graph = day(FreeGraphSeries("kb.usd", listOf(point(kst("19:40:00"), 1341.2)), label = "KB국민", axisGroup = "krw", insufficientHistory = true))
        val st = observe(empty("kb.usd"), kst("20:02:00"), kst("20:01:00") to 1341.7)
        val s = project(graph, ONE_DAY, kst("20:02:00"), st).of("kb.usd")
        assertEquals("KB국민", s.label)
        assertEquals("krw", s.axisGroup)
        assertTrue(s.insufficientHistory)
        assertTrue(BandPoint(b, 1341.7, 1341.7) in s.bandPoints)
    }

    /**
     * L25 (long periods): only the fields the tip touches change - the rest of the series and graph are `prepared`'s, carry-in
     * included; the right edge is the app tip only (no seed close, no bucket range, no hold before it); an empty series gets no
     * tip end; a tip in the future within D3's window is fresh; 3m and 1y behave like 1w; a REST point after now blocks the end
     * point; another scope's fresh tip changes nothing.
     */
    @Test fun L25_longPeriodsTouchOnlyTheRightEdge() {
        val now = kst("20:02:00")
        val withCarry = FreeGraph(
            "1h",
            listOf(
                FreeGraphSeries(
                    "kb.usd",
                    listOf(point(kst("18:00:00"), 1341.3, 1341.0, 1341.6), point(kst("19:00:00"), 1341.5, 1341.4, 1341.8)),
                    axisGroup = "krw",
                    carryIn = FreeGraphCarryIn(1340.9, at(1, "00:00:00") - 1.hours)
                )
            ),
            domainStartAt = at(1, "00:00:00"), domainEndAt = kst("19:00:00"), liveDomainMode = "fixed_start"
        )
        val base = GraphPreparedBuilder.build(withCarry, ONE_WEEK)
        assertTrue(base.of("kb.usd").carryInApplied)
        for ((rate, top) in listOf(1341.7 to 1341.8, 1343.0 to 1343.0)) {
            val fresh = observe(empty("kb.usd"), kst("20:01:30"), kst("20:01:00") to rate)
            val out = project(withCarry, ONE_WEEK, now, fresh)
            val expected = base.of("kb.usd").copy(
                linePoints = base.of("kb.usd").linePoints + LinePoint(now, rate),
                lastObservation = LinePoint(now, rate),
                extrema = 1340.9..top
            )
            assertEquals("$rate", expected, out.of("kb.usd"))
            assertEquals("$rate", base.copy(bySeries = out.bySeries), out)
        }

        val simple = week(series("kb.usd", listOf(point(kst("18:00:00"), 1341.3), point(kst("19:00:00"), 1341.5))))
        val simpleBase = GraphPreparedBuilder.build(simple, ONE_WEEK).of("kb.usd")
        val seed = GraphV2InProgress(b, 1341.9, 1341.7, 1341.8, kst("20:01:00"))
        val seededTip = observe(respond(empty("kb.usd"), now, seed = seed), now, kst("19:55:00") to 1341.2)
        val st = project(simple, ONE_WEEK, now, seededTip).of("kb.usd")
        assertEquals(simpleBase.linePoints + LinePoint(now, 1341.2), st.linePoints)
        assertEquals(1341.2..1341.5, st.extrema)
        assertEquals(simpleBase, project(simple, ONE_WEEK, now, respond(empty("kb.usd"), now, seed = seed)).of("kb.usd"))

        val ranged = observe(empty("kb.usd"), kst("20:01:30"), kst("20:00:30") to 1344.0, kst("20:01:00") to 1341.7)
        val rg = project(simple, ONE_WEEK, now, ranged).of("kb.usd")
        assertEquals(1341.3..1341.7, rg.extrema)
        assertFalse(1344.0 in rg.values())

        val gappy = week(series("kb.usd", listOf(point(kst("17:00:00"), 1341.3), point(kst("18:00:00"), 1341.5))))
        val gb = GraphPreparedBuilder.build(gappy, ONE_WEEK)
        val freshTip = observe(empty("kb.usd"), kst("20:01:30"), kst("20:01:00") to 1341.7)
        val go = project(gappy, ONE_WEEK, now, freshTip)
        assertEquals(
            listOf(LinePoint(kst("17:00:00"), 1341.3), LinePoint(kst("18:00:00"), 1341.5), LinePoint(now, 1341.7)),
            go.of("kb.usd").linePoints
        )
        assertEquals(gb.of("kb.usd").bandPoints, go.of("kb.usd").bandPoints)
        assertEquals(gb.dataBounds, go.dataBounds)

        val emptyLong = week(series("kb.usd", emptyList()))
        assertEquals(GraphPreparedBuilder.build(emptyLong, ONE_WEEK), project(emptyLong, ONE_WEEK, now, freshTip))

        val ahead = observe(empty("kb.usd"), now, kst("20:13:40") to 1341.9)
        assertEquals(LinePoint(now, 1341.9), project(simple, ONE_WEEK, now, ahead).of("kb.usd").lastObservation)

        val staleTip = observe(empty("kb.usd"), kst("19:51:00"), kst("19:50:00") to 1341.7)
        for (period in listOf(GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR)) {
            val daily = FreeGraph(
                "1d", listOf(series("kb.usd", listOf(point(at(5, "00:00:00"), 1341.3), point(at(6, "00:00:00"), 1341.5)))),
                domainStartAt = at(1, "00:00:00"), domainEndAt = at(6, "00:00:00"), liveDomainMode = "fixed_start"
            )
            val db = GraphPreparedBuilder.build(daily, period).of("kb.usd")
            assertEquals("$period", db.linePoints + LinePoint(now, 1341.7), project(daily, period, now, freshTip).of("kb.usd").linePoints)
            assertEquals("$period", db, project(daily, period, now, staleTip).of("kb.usd"))
        }

        val restAfter = week(series("kb.usd", listOf(point(kst("19:00:00"), 1341.5), point(kst("20:30:00"), 1341.6))))
        assertEquals(GraphPreparedBuilder.build(restAfter, ONE_WEEK).of("kb.usd"), project(restAfter, ONE_WEEK, now, freshTip).of("kb.usd"))

        val foreign = observe(empty("kb.usd", s = otherScope), now, kst("20:01:00") to 1350.0)
        assertEquals(GraphPreparedBuilder.build(simple, ONE_WEEK), project(simple, ONE_WEEK, now, foreign))
    }

    /**
     * L26 (index series, every path): no band with or without a live state, in 1d and long, for dxy and any other index series;
     * no shoulder after a previous index bucket; a long fresh tip becomes lastObservation and blocks the trailing hold.
     */
    @Test fun L26_indexSeriesNeverCarryABand() {
        val dayGraph = day(
            series("kb.usd", listOf(point(kst("19:40:00"), 1341.2, 1341.0, 1341.4))),
            series("dxy", listOf(point(kst("19:40:00"), 105.4, 105.3, 105.5)), axis = "index"),
            series("dxy_futures", listOf(point(kst("19:40:00"), 105.6, 105.5, 105.7)), axis = "index")
        )
        val none = project(dayGraph, ONE_DAY, kst("20:03:00"))
        assertTrue(none.of("dxy").bandPoints.isEmpty() && none.of("dxy_futures").bandPoints.isEmpty())
        assertEquals(GraphPreparedBuilder.build(dayGraph, ONE_DAY).of("dxy").linePoints, none.of("dxy").linePoints)
        assertEquals(listOf("kb.usd", "dxy", "dxy_futures"), none.order)

        val prev = observe(
            empty("dxy", order = DXY_GRAPH_OBSERVATION_ORDER), kst("19:56:00"),
            kst("19:51:00") to 105.2, kst("19:55:00") to 105.3, source = "investing", asset = "dxy"
        )
        val fresh = project(dayGraph, ONE_DAY, kst("20:02:00"), prev).of("dxy")
        assertTrue(fresh.bandPoints.isEmpty())
        assertEquals(listOf(LinePoint(kst("19:40:00"), 105.4), LinePoint(a, 105.3), LinePoint(kst("20:02:00"), 105.3)), fresh.linePoints)
        assertEquals(105.3..105.4, fresh.extrema)
        assertEquals(LinePoint(a, 105.3), fresh.lastObservation)
        val stale = project(dayGraph, ONE_DAY, kst("20:06:00"), prev).of("dxy")
        assertTrue(stale.bandPoints.isEmpty())
        assertEquals(listOf(LinePoint(kst("19:40:00"), 105.4), LinePoint(a, 105.3)), stale.linePoints)

        val longGraph = week(
            series("dxy", listOf(point(kst("18:00:00"), 105.4, 105.2, 105.6), point(kst("19:00:00"), 105.5, 105.3, 105.7)), axis = "index")
        )
        val lb = GraphPreparedBuilder.build(longGraph, ONE_WEEK).of("dxy")
        val now = kst("20:02:00")
        val tip = observe(empty("dxy", order = DXY_GRAPH_OBSERVATION_ORDER), kst("20:01:30"), kst("20:01:00") to 105.1, source = "investing", asset = "dxy")
        val lo = project(longGraph, ONE_WEEK, now, tip)
        assertEquals(LinePoint(now, 105.1), lo.of("dxy").lastObservation)
        assertNull(GraphFrame.trailingHold(lo.of("dxy"), ONE_WEEK, GraphFrame.resolve(lo, now)!!))
        val oldTip = observe(empty("dxy", order = DXY_GRAPH_OBSERVATION_ORDER), kst("19:51:00"), kst("19:50:00") to 105.1, source = "investing", asset = "dxy")
        assertEquals(lb.copy(bandPoints = emptyList()), project(longGraph, ONE_WEEK, now, oldTip).of("dxy"))
        assertEquals(lb.copy(bandPoints = emptyList()), project(longGraph, ONE_WEEK, now).of("dxy"))
    }

    /** L27: pending and gap history - INITIAL_SYNC and TIME_ANOMALY on b included - gate neither the extension nor the shoulder. */
    @Test fun L27_recoveryBookkeepingDoesNotGateEndPoints() {
        val now = kst("20:02:00")
        val s0 = observe(empty("kb.usd"), now, kst("19:51:00") to 1341.5, kst("19:55:00") to 1341.9, kst("20:01:00") to 1341.7)
        var s1 = requireGraphRecovery(s0, a, b, GraphRecoveryReason.RECEIVE_GAP, now)
        s1 = requireGraphRecovery(s1, b, b, GraphRecoveryReason.INITIAL_SYNC, now)
        s1 = observe(s1, now, kst("20:30:00") to 1342.0)
        assertTrue(GraphRecoveryReason.INITIAL_SYNC in s1.pending.getValue(b).reasons)
        assertTrue(GraphRecoveryReason.TIME_ANOMALY in s1.pending.getValue(b).reasons)
        assertTrue(a in s1.gapHistory)
        for (t in listOf(now, kst("20:12:00"))) assertEquals("$t", project(restDay, ONE_DAY, t, s0), project(restDay, ONE_DAY, t, s1))
    }

    /** L28: the live state is matched on both scope fields - another epoch or another user with the same id is not read. */
    @Test fun L28_bothScopeFieldsMustMatch() {
        val now = kst("20:02:00")
        val epoch = observe(empty("kb.usd", s = GraphDataScope("u1", "e2")), now, kst("20:01:00") to 1350.0)
        val user = observe(empty("kb.usd", s = GraphDataScope("u2", "e1")), now, kst("20:01:00") to 1350.0)
        assertEquals(GraphPreparedBuilder.build(restDay, ONE_DAY), project(restDay, ONE_DAY, now, epoch, user))
    }

    /** L29: each series uses its own tip - a fresh index tip does not end a stale KRW series - and the order stays the graph's. */
    @Test fun L29_eachSeriesUsesItsOwnTip() {
        val now = kst("20:03:00")
        val graph = day(
            series("kb.usd", listOf(point(kst("19:40:00"), 1341.2, 1341.0, 1341.4))),
            series("dxy", listOf(point(kst("19:40:00"), 105.4, 105.3, 105.5)), axis = "index")
        )
        val kbState = observe(empty("kb.usd"), kst("19:53:00"), kst("19:51:00") to 1341.8, kst("19:52:00") to 1341.6)
        val dxyState = observe(
            empty("dxy", order = DXY_GRAPH_OBSERVATION_ORDER), now,
            kst("20:01:00") to 105.1, kst("20:02:00") to 105.3, source = "investing", asset = "dxy"
        )
        val out = project(graph, ONE_DAY, now, dxyState, kbState)
        assertEquals(listOf("kb.usd", "dxy"), out.order)
        val kb = out.of("kb.usd")
        assertEquals(listOf(LinePoint(kst("19:40:00"), 1341.2), LinePoint(a, 1341.6)), kb.linePoints)
        assertEquals(
            listOf(BandPoint(kst("19:40:00"), 1341.0, 1341.4), BandPoint(a, 1341.6, 1341.8), BandPoint(b, 1341.6, 1341.6)),
            kb.bandPoints
        )
        assertEquals(1341.0..1341.8, kb.extrema)
        assertEquals(project(graph, ONE_DAY, now, dxyState).of("dxy"), out.of("dxy"))
    }

    /** L30: a fresh tip is not fed through the builder - no hold appears before it, and the band ends at its bucket's shoulder. */
    @Test fun L30_theTipIsNotFedThroughTheBuilder() {
        val now = kst("20:08:59")
        val st = observe(empty("kb.usd"), kst("19:59:30"), kst("19:51:00") to 1341.0, kst("19:59:00") to 1341.6)
        val s = project(restDay, ONE_DAY, now, st).of("kb.usd")
        assertEquals(
            listOf(LinePoint(kst("19:30:00"), 1341.0), LinePoint(kst("19:40:00"), 1341.2), LinePoint(a, 1341.6), LinePoint(now, 1341.6)),
            s.linePoints
        )
        assertEquals(
            listOf(
                BandPoint(kst("19:30:00"), 1340.8, 1341.2),
                BandPoint(kst("19:40:00"), 1341.0, 1341.4),
                BandPoint(a, 1341.0, 1341.6),
                BandPoint(b, 1341.6, 1341.6)
            ),
            s.bandPoints
        )
        assertEquals(LinePoint(a, 1341.6), s.lastObservation)
    }

    /** L31 (1d freshness): hana's 1200 s and the strict thresholds apply to the 1d tip end point too. */
    @Test fun L31_oneDayFreshnessIsPerSourceAndStrict() {
        val hana = observe(empty("hana.usd"), kst("19:46:00"), kst("19:45:00") to 1341.6, source = "hana")
        val hanaGraph = day(series("hana.usd", emptyList()))
        assertEquals(LinePoint(kst("20:02:00"), 1341.6), project(hanaGraph, ONE_DAY, kst("20:02:00"), hana).of("hana.usd").linePoints.last())
        assertEquals(LinePoint(kst("19:40:00"), 1341.6), project(hanaGraph, ONE_DAY, kst("20:05:00"), hana).of("hana.usd").linePoints.last())

        val kbGraph = day(series("kb.usd", emptyList()))
        val atThreshold = observe(empty("kb.usd"), kst("19:53:00"), kst("19:52:00") to 1341.6)
        assertEquals(LinePoint(kst("19:50:00"), 1341.6), project(kbGraph, ONE_DAY, kst("20:02:00"), atThreshold).of("kb.usd").linePoints.last())
        val justFresh = observe(empty("kb.usd"), kst("19:53:00"), kst("19:52:00") + 1.nanoseconds to 1341.6)
        assertEquals(LinePoint(kst("20:02:00"), 1341.6), project(kbGraph, ONE_DAY, kst("20:02:00"), justFresh).of("kb.usd").linePoints.last())
    }
}
