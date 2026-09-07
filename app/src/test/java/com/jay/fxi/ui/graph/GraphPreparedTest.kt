package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphCarryIn
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock-free half of the graph: what the wire says, turned into geometry.
 *
 * Every rule here is iOS Graph V2's, which is the specification — this app's own legacy graph is a
 * parts bin and its behaviour proves nothing about what v2 owes.
 */
class GraphPreparedTest {

    private val day0 = Instant.parse("2026-09-01T00:00:00Z")

    private fun point(offsetDays: Int, rate: Double, high: Double? = null, low: Double? = null) =
        FreeGraphPoint(day0 + offsetDays.days, rate, high, low)

    private fun series(
        id: String = "investing.usd",
        axis: String? = "krw",
        points: List<FreeGraphPoint> = emptyList(),
        carryIn: FreeGraphCarryIn? = null,
        insufficient: Boolean = false
    ) = FreeGraphSeries(
        seriesId = id, points = points, label = id, axisGroup = axis,
        insufficientHistory = insufficient, carryIn = carryIn
    )

    private fun graph(
        series: List<FreeGraphSeries>,
        start: Instant? = day0,
        end: Instant? = day0 + 10.days,
        mode: String? = "fixed_start"
    ) = FreeGraph(
        bucketSize = null, series = series,
        domainStartAt = start, domainEndAt = end, liveDomainMode = mode
    )

    // --- the server's declared window ------------------------------------------------------------

    /**
     * A domain is taken whole or not at all. Half of one would place the left edge somewhere the
     * data never was, which is worse than falling back to where the data is.
     */
    @Test
    fun aDomainIsAcceptedOnlyWhenEveryPartOfItAgrees() {
        val ok = graph(listOf(series()), mode = "fixed_start")
        assertEquals(
            DisplayDomain(day0, day0 + 10.days, LiveDomainMode.FIXED_START),
            GraphPreparedBuilder.validatedDomain(ok, GraphPeriod.THREE_MONTHS)
        )
        // Any missing field, and there is no domain.
        assertNull(GraphPreparedBuilder.validatedDomain(graph(listOf(series()), start = null), GraphPeriod.THREE_MONTHS))
        assertNull(GraphPreparedBuilder.validatedDomain(graph(listOf(series()), end = null), GraphPeriod.THREE_MONTHS))
        assertNull(GraphPreparedBuilder.validatedDomain(graph(listOf(series()), mode = null), GraphPeriod.THREE_MONTHS))
        assertNull(GraphPreparedBuilder.validatedDomain(graph(listOf(series()), mode = "sideways"), GraphPeriod.THREE_MONTHS))
        // Backwards or empty.
        assertNull(GraphPreparedBuilder.validatedDomain(
            graph(listOf(series()), start = day0 + 10.days, end = day0), GraphPeriod.THREE_MONTHS))
        assertNull(GraphPreparedBuilder.validatedDomain(
            graph(listOf(series()), start = day0, end = day0), GraphPeriod.THREE_MONTHS))
    }

    /** `rolling` belongs to 1일 and to nothing else; the long periods pin their left edge. */
    @Test
    fun theModeMustMatchThePeriod() {
        val rolling = graph(listOf(series()), mode = "rolling")
        val fixed = graph(listOf(series()), mode = "fixed_start")
        assertEquals(LiveDomainMode.ROLLING, GraphPreparedBuilder.validatedDomain(rolling, GraphPeriod.ONE_DAY)?.mode)
        assertNull(GraphPreparedBuilder.validatedDomain(rolling, GraphPeriod.THREE_MONTHS))
        assertNull(GraphPreparedBuilder.validatedDomain(fixed, GraphPeriod.ONE_DAY))
        GraphPeriod.entries.filter { it != GraphPeriod.ONE_DAY }.forEach {
            assertEquals(LiveDomainMode.FIXED_START, GraphPreparedBuilder.validatedDomain(fixed, it)?.mode)
        }
    }

    /**
     * The fallback measures the KRW series alone. A dollar index reaching further back would
     * otherwise stretch the axis to cover a series that is only ever an overlay.
     */
    @Test
    fun theFallbackSpanIgnoresAnIndexOnlyOverlay() {
        val krw = series("investing.usd", "krw", listOf(point(2, 1400.0), point(4, 1402.0)))
        val index = series("dxy", "index", listOf(point(0, 99.0), point(9, 98.0)))
        assertEquals(
            TimeRange(day0 + 2.days, day0 + 4.days),
            GraphPreparedBuilder.dataBoundsOf(listOf(krw, index))
        )
        // With nothing on the KRW axis, an index-only answer still measures itself.
        assertEquals(
            TimeRange(day0, day0 + 9.days),
            GraphPreparedBuilder.dataBoundsOf(listOf(index))
        )
        // …but the fallback is the index axis specifically, not "whatever else is here". An axis
        // nobody has defined a meaning for cannot be the thing that sets the time span.
        assertNull(
            GraphPreparedBuilder.dataBoundsOf(listOf(
                series("mystery", "something-else", listOf(point(0, 5.0), point(9, 6.0)))
            ))
        )
        assertNull(GraphPreparedBuilder.dataBoundsOf(listOf(series(points = emptyList()))))
        // A rate tab whose rates came back empty has no span. Falling through to the index would
        // invent one out of a series that is only ever drawn against those rates.
        assertNull(
            GraphPreparedBuilder.dataBoundsOf(listOf(
                series("investing.usd", "krw", emptyList()),
                series("dxy", "index", listOf(point(0, 99.0), point(9, 98.0)))
            ))
        )
        // A single point spans nothing, so there is no usable fallback.
        assertNull(GraphPreparedBuilder.dataBoundsOf(listOf(series(points = listOf(point(1, 1400.0))))))
    }

    // --- gaps -------------------------------------------------------------------------------------

    /**
     * A weekend of no trading is not a slow drift from Friday to Monday. The line holds flat and
     * then steps, and the step lands one bucket before the next reading.
     */
    @Test
    fun aGapIsHeldFlatAndThenStepped_notInterpolatedThrough() {
        val friday = LinePoint(day0, 1400.0)
        val monday = LinePoint(day0 + 3.days, 1420.0)
        val filled = GraphPreparedBuilder.forwardFilled(listOf(friday, monday), 1.days)
        assertEquals(
            listOf(day0, day0 + 2.days, day0 + 3.days),
            filled.map { it.ts }
        )
        assertEquals(listOf(1400.0, 1400.0, 1420.0), filled.map { it.rate })
    }

    /** Ordinary spacing, and spacing merely jittery, must not be mistaken for a gap. */
    @Test
    fun onlyGapsWiderThanOneAndAHalfBucketsAreHeld() {
        val bucket = 1.days
        fun spanOf(gapHours: Int) = GraphPreparedBuilder.forwardFilled(
            listOf(LinePoint(day0, 1400.0), LinePoint(day0 + gapHours.hours, 1401.0)), bucket
        ).size
        assertEquals("a single bucket was treated as a gap", 2, spanOf(24))
        assertEquals("exactly 1.5 buckets is not yet a gap", 2, spanOf(36))
        assertEquals(3, spanOf(37))
    }

    /** Nothing to hold between: a lone point, or none, comes back untouched. */
    @Test
    fun tooFewPointsToHaveAGap() {
        assertEquals(emptyList<LinePoint>(), GraphPreparedBuilder.forwardFilled(emptyList(), 1.days))
        val one = listOf(LinePoint(day0, 1400.0))
        assertEquals(one, GraphPreparedBuilder.forwardFilled(one, 1.days))
    }

    /**
     * The band goes to zero width across a gap rather than carrying a spread forward — it would
     * otherwise be widest exactly where there is no evidence at all.
     */
    @Test
    fun theBandPinchesShutAcrossAGap() {
        val points = listOf(
            point(0, 1400.0, high = 1405.0, low = 1395.0),
            point(3, 1420.0, high = 1425.0, low = 1415.0)
        )
        val band = GraphPreparedBuilder.forwardFilledBand(points, 1.days)
        // Shoulders one bucket inside each end of the gap — not on the readings themselves.
        assertEquals(listOf(day0, day0 + 1.days, day0 + 2.days, day0 + 3.days), band.map { it.ts })
        // Both shoulders use the previous close, keeping the zero-width segment horizontal.
        // Using the next close at the far shoulder would introduce a slope.
        assertEquals(BandPoint(day0 + 1.days, 1400.0, 1400.0), band[1])
        assertEquals(BandPoint(day0 + 2.days, 1400.0, 1400.0), band[2])
        assertEquals(BandPoint(day0, 1395.0, 1405.0), band[0])
        assertEquals(BandPoint(day0 + 3.days, 1415.0, 1425.0), band[3])
    }

    /** Too narrow for two shoulders: one closing point, rather than a pair out of order. */
    @Test
    fun aNarrowGapGetsASingleShoulder() {
        val points = listOf(
            point(0, 1400.0, high = 1405.0, low = 1395.0),
            point(2, 1420.0, high = 1425.0, low = 1415.0)
        )
        val band = GraphPreparedBuilder.forwardFilledBand(points, 1.days)
        assertEquals(listOf(day0, day0 + 1.days, day0 + 2.days), band.map { it.ts })
        assertEquals(BandPoint(day0 + 1.days, 1400.0, 1400.0), band[1])
    }

    /**
     * A reading the server sent no extremes for still counts as this one's neighbour.
     *
     * Skipping over it when looking for the next point would merge two ordinary intervals into one
     * apparent gap and pinch a band that should have run straight through.
     */
    @Test
    fun aBandlessReadingIsStillTheNeighbourForGapDetection() {
        val points = listOf(
            point(0, 1400.0, high = 1405.0, low = 1395.0),
            point(1, 1410.0),
            point(2, 1420.0, high = 1425.0, low = 1415.0)
        )
        val band = GraphPreparedBuilder.forwardFilledBand(points, 1.days)
        assertEquals(listOf(day0, day0 + 2.days), band.map { it.ts })
    }

    /** A point the server sent no extremes for contributes no band at all. */
    @Test
    fun pointsWithoutExtremesContributeNoBand() {
        assertEquals(
            emptyList<BandPoint>(),
            GraphPreparedBuilder.forwardFilledBand(listOf(point(0, 1400.0), point(1, 1401.0)), 1.days)
        )
    }

    // --- carry-in ----------------------------------------------------------------------------------

    /**
     * The last reading from before the window, planted on its left edge, so the empty stretch
     * before the first real point reads as "unchanged since before" rather than as missing.
     *
     * It goes into the forward-fill's INPUT: as an input the gap rule can hold from it, which is
     * the entire purpose. Appended to the output it would be a lone vertex with a diagonal running
     * away from it.
     */
    @Test
    fun anAdmittedCarryInSeedsTheLeftEdgeAndIsThenHeldAcross() {
        val prepared = GraphPreparedBuilder.build(
            graph(listOf(series(
                points = listOf(point(5, 1400.0)),
                carryIn = FreeGraphCarryIn(1380.0, day0 - 2.days)
            ))),
            GraphPeriod.THREE_MONTHS
        )
        val line = prepared.bySeries.getValue("investing.usd")
        assertTrue(line.carryInApplied)
        assertEquals(day0, line.linePoints.first().ts)
        assertEquals(1380.0, line.linePoints.first().rate, 0.0)
        // Held flat across the four empty days, then stepping to the real reading.
        assertEquals(listOf(day0, day0 + 4.days, day0 + 5.days), line.linePoints.map { it.ts })
        assertEquals(listOf(1380.0, 1380.0, 1400.0), line.linePoints.map { it.rate })
        // The seed is a real observation, so it widens the range the series claims.
        assertEquals(1380.0, line.extrema!!.start, 0.0)
    }

    /** Five guards, each of which alone refuses the seed. */
    @Test
    fun eachCarryInGuardRefusesOnItsOwn() {
        val carry = FreeGraphCarryIn(1380.0, day0 - 2.days)
        val points = listOf(point(5, 1400.0))
        fun seed(
            period: GraphPeriod = GraphPeriod.THREE_MONTHS,
            insufficient: Boolean = false,
            domain: DisplayDomain? = DisplayDomain(day0, day0 + 10.days, LiveDomainMode.FIXED_START),
            carryIn: FreeGraphCarryIn? = carry,
            pts: List<FreeGraphPoint> = points
        ) = GraphPreparedBuilder.carryInSeed(
            series(points = pts, carryIn = carryIn, insufficient = insufficient), period, domain, pts
        )
        assertEquals(LinePoint(day0, 1380.0), seed())
        // 1일's window is short enough that a stale seed would dominate it.
        assertNull(seed(period = GraphPeriod.ONE_DAY))
        // The server already said this series does not reach back.
        assertNull(seed(insufficient = true))
        // No validated domain means no left edge to plant it on.
        assertNull(seed(domain = null))
        assertNull(seed(carryIn = null))
        // A first point already at the edge would be sat on top of.
        assertNull(seed(pts = listOf(point(0, 1400.0))))
        assertNull(seed(pts = emptyList()))
    }

    // --- extrema -----------------------------------------------------------------------------------

    /** Clipping to closes would cut the wicks off the very buckets the server sent. */
    @Test
    fun aRateSeriesRangeCoversItsWicks() {
        val range = GraphPreparedBuilder.extremaOf(
            listOf(point(0, 1400.0, high = 1420.0, low = 1385.0)), seed = null, axisGroup = "krw"
        )!!
        assertEquals(1385.0, range.start, 0.0)
        assertEquals(1420.0, range.endInclusive, 0.0)
        assertNull(GraphPreparedBuilder.extremaOf(emptyList(), seed = null, axisGroup = "krw"))
    }

    /**
     * KRW extrema include rate/high/low; index extrema include rate only.
     *
     * The index range is separate from the KRW range; including index wicks would compress the
     * normalized index line's movement.
     */
    @Test
    fun theIndexOverlayContributesOnlyItsCloses() {
        val points = listOf(point(0, 99.0, high = 101.0, low = 97.0))
        val index = GraphPreparedBuilder.extremaOf(points, seed = null, axisGroup = "index")!!
        assertEquals(99.0, index.start, 0.0)
        assertEquals(99.0, index.endInclusive, 0.0)
        // The same points on a rate axis do carry their wicks.
        val rate = GraphPreparedBuilder.extremaOf(points, seed = null, axisGroup = "krw")!!
        assertEquals(97.0, rate.start, 0.0)
        assertEquals(101.0, rate.endInclusive, 0.0)
    }

    // --- the whole model -----------------------------------------------------------------------------

    /**
     * Hidden series are kept.
     *
     * The time axis is measured across everything the answer carries, so the model cannot be
     * filtered down to what is on screen — filtering it would let a toggle slide the x axis
     * sideways.
     */
    @Test
    fun everySeriesSurvivesIntoTheModel_visibleOrNot() {
        val prepared = GraphPreparedBuilder.build(
            graph(listOf(
                series("investing.usd", "krw", listOf(point(1, 1400.0))),
                series("dxy", "index", listOf(point(1, 99.0))),
                series("hana.usd", "krw", emptyList())
            )),
            GraphPeriod.THREE_MONTHS
        )
        assertEquals(listOf("investing.usd", "dxy", "hana.usd"), prepared.order)
        assertEquals(3, prepared.bySeries.size)
        // A series with no points is present but claims nothing.
        assertNull(prepared.bySeries.getValue("hana.usd").extrema)
        assertTrue(prepared.bySeries.getValue("hana.usd").linePoints.isEmpty())
    }

    /** The last real reading is kept apart, because the trailing hold is drawn from it later. */
    @Test
    fun theLastRealObservationIsKeptSeparately() {
        val prepared = GraphPreparedBuilder.build(
            graph(listOf(series(points = listOf(point(1, 1400.0), point(2, 1402.0))))),
            GraphPeriod.THREE_MONTHS
        )
        assertEquals(LinePoint(day0 + 2.days, 1402.0), prepared.bySeries.getValue("investing.usd").lastObservation)
    }

    /** Nothing here may read a clock: the same input must build the same model, always. */
    @Test
    fun buildingIsDeterministic() {
        val input = graph(listOf(series(points = listOf(point(1, 1400.0), point(5, 1410.0)))))
        assertEquals(
            GraphPreparedBuilder.build(input, GraphPeriod.THREE_MONTHS),
            GraphPreparedBuilder.build(input, GraphPeriod.THREE_MONTHS)
        )
    }

    @Test
    fun bucketsAndTrailingBudgetsAreThePeriodsOwn() {
        assertEquals(10.minutes, GraphPreparedBuilder.bucketFor(GraphPeriod.ONE_DAY))
        assertEquals(1.hours, GraphPreparedBuilder.bucketFor(GraphPeriod.ONE_WEEK))
        assertEquals(1.days, GraphPreparedBuilder.bucketFor(GraphPeriod.THREE_MONTHS))
        assertEquals(1.days, GraphPreparedBuilder.bucketFor(GraphPeriod.ONE_YEAR))
        // 1일 never holds out to its right edge, so it has no budget at all.
        assertNull(GraphPreparedBuilder.maxTrailingGap(GraphPeriod.ONE_DAY))
        assertEquals(4.days, GraphPreparedBuilder.maxTrailingGap(GraphPeriod.ONE_WEEK))
        assertEquals(5.days, GraphPreparedBuilder.maxTrailingGap(GraphPeriod.THREE_MONTHS))
        assertFalse(GraphPreparedBuilder.maxTrailingGap(GraphPeriod.ONE_YEAR) == null)
    }
}
