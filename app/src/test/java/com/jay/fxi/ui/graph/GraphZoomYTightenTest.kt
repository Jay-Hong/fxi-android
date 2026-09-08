package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the chart says it is showing, once the window is narrower than the day.
 *
 * Two separate decisions live here and they are gated differently, which is the whole reason this
 * file exists. **What the chart reports** is always the window: the accessibility description says
 * "on screen", and while zoomed the old answer named values the user could not see. **What the axis
 * does** is narrower — iOS tightens only for a lone rate series on 1일, because two lines rescaled
 * independently would drift apart for no reason the data gives.
 */
class GraphZoomYTightenTest {

    private val midnight = Instant.parse("2026-09-08T00:00:00Z")

    /** Rises 0.1 per ten minutes: any window has extremes of its own. */
    private fun rising(id: String, axis: String?, base: Double) = FreeGraphSeries(
        seriesId = id,
        label = id,
        axisGroup = axis,
        points = (0..143).map { FreeGraphPoint(midnight + (it * 10).minutes, base + it * 0.1, null, null) }
    )

    private fun prepared(vararg series: FreeGraphSeries) = GraphPreparedBuilder.build(
        FreeGraph(
            bucketSize = null,
            series = series.toList(),
            domainStartAt = midnight,
            domainEndAt = midnight + 24.hours,
            liveDomainMode = "rolling"
        ),
        GraphPeriod.ONE_DAY
    )

    private val everything = setOf("investing.usd", "kb.usd", "dxy")

    // 00:00–06:00 of a series that rises 0.6/hour: about 1400.0 … 1403.5.
    private val firstSixHours = midnight..(midnight + 6.hours)

    @Test
    fun theReadingFollowsTheWindow() {
        val p = prepared(rising("investing.usd", "krw", 1400.0))
        val whole = GraphProjection.plot(p, everything)!!
        val zoomed = GraphProjection.plot(p, everything, firstSixHours)!!

        assertEquals(1400.0, whole.observed!!.start, 1e-6)
        assertEquals(1414.3, whole.observed!!.endInclusive, 1e-6)
        // Six hours in, the day's later values are off screen and must not be read out.
        assertEquals(1400.0, zoomed.observed!!.start, 1e-6)
        assertTrue("the reading kept the whole day: ${zoomed.observed}", zoomed.observed!!.endInclusive < 1404.0)
    }

    /** Unzoomed, the window *is* the frame — so nothing changes, and no gate is needed to say so. */
    @Test
    fun withNoWindowTheReadingIsTheWholeDay() {
        val p = prepared(rising("investing.usd", "krw", 1400.0))
        val implicit = GraphProjection.plot(p, everything)!!
        val explicit = GraphProjection.plot(p, everything, GraphFrame.rendered(GraphFrame.resolve(p)!!, GraphPeriod.ONE_DAY).let { it.start..it.end })!!
        assertEquals(implicit.observed, explicit.observed)
    }

    /**
     * Unzoomed, the axis is the day's — margin and all.
     *
     * The tighten gate reads `visibleDomain != null` as well as the period and the series count.
     * Dropping that one condition looks harmless, because with no window the extremes are the same
     * either way — but the two paths add their margin differently, and the unzoomed chart would
     * quietly change shape. Named here so the condition is not "covered" only by a test about gaps.
     */
    @Test
    fun theUnzoomedAxisIsTheDaysAxis() {
        val p = prepared(rising("investing.usd", "krw", 1400.0))
        val whole = GraphProjection.plot(p, everything)!!
        val expected = GraphAxis.composeAxisRange(
            listOf(whole.observed!!), GraphAxis.KRW_MARGIN_FLOOR
        )!!
        assertEquals(expected.start, whole.valueRange!!.start, 1e-9)
        assertEquals(expected.endInclusive, whole.valueRange!!.endInclusive, 1e-9)
    }

    /**
     * A quiet day is not widened to the zoom floor.
     *
     * The 2.0 floor exists to stop a *zoom* from magnifying a blip; it is not a statement about how
     * wide a day must be. Unzoomed, `composeAxisRange`'s own 0.5-per-side floor already governs, and
     * a flat day gets an axis of 1.0. This is the only place the two paths disagree once both apply
     * the same margin — which is why dropping the zoom gate is invisible to every other test here.
     */
    @Test
    fun aQuietDayUnzoomedIsNotWidenedToTheZoomFloor() {
        val flat = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = (0..143).map { FreeGraphPoint(midnight + (it * 10).minutes, 1400.0, null, null) }
        )
        val whole = GraphProjection.plot(prepared(flat), everything)!!
        val span = whole.valueRange!!.endInclusive - whole.valueRange!!.start
        assertEquals("the unzoomed axis borrowed the zoom floor", 1.0, span, 1e-9)
    }

    /**
     * The axis holds everything the chart paints, worked out independently of the code that decides.
     *
     * `xOf` puts the window's edges on the plot's edges and every series is drawn inside
     * `clipRect(area…)`, so what a viewer sees is each segment **clamped to the window**. This
     * reproduces that clamp from scratch — take the renderer's own point selection, clip every
     * segment to the window, and evaluate it at both clipped ends (a straight segment has its
     * extremes there) — rather than asking the production helper whether it agrees with itself.
     */
    private fun paintedValues(plot: GraphPlot): List<Double> {
        val start = plot.display.start
        val end = plot.display.end
        fun clip(samples: List<Pair<Instant, List<Double>>>): List<Double> = buildList {
            samples.zipWithNext { (t0, v0), (t1, v1) ->
                val from = maxOf(t0, start)
                val to = minOf(t1, end)
                if (from > to || t1 <= t0) return@zipWithNext
                for (at in listOf(from, to)) {
                    val f = (at - t0) / (t1 - t0)
                    v0.forEachIndexed { k, v -> add(v + (v1[k] - v) * f) }
                }
            }
        }
        return plot.lines.flatMap { line ->
            // One selected point is a dot for the line and nothing at all for the band, which needs
            // two to enclose an area. `drawLine` and `drawBand` part company exactly here.
            val selectedLine = GraphProjection.visibleSpan(line.points, plot.display)
            val lineValues = if (selectedLine.size == 1) {
                selectedLine.filter { it.ts >= start && it.ts <= end }.map { it.rate }
            } else {
                clip(selectedLine.map { it.ts to listOf(it.rate) })
            }
            val selectedBand = GraphProjection.visibleBandSpan(line.band, plot.display)
            val bandValues =
                if (selectedBand.size < 2) emptyList()
                else clip(selectedBand.map { it.ts to listOf(it.low, it.high) })
            lineValues + bandValues
        }
    }

    private fun assertAxisHoldsWhatIsPainted(plot: GraphPlot) {
        val painted = paintedValues(plot)
        val axis = plot.valueRange!!
        assertTrue("nothing was painted, so the check proves nothing", painted.isNotEmpty())
        assertTrue("the axis top ${axis.endInclusive} clips ${painted.max()}", axis.endInclusive >= painted.max())
        assertTrue("the axis bottom ${axis.start} clips ${painted.min()}", axis.start <= painted.min())
    }

    /**
     * A segment crossing the edge fits — and the point beyond the edge does not stretch the axis.
     *
     * Both halves matter. An axis measured from the points strictly inside the window would cut this
     * segment off at the very edge the user zoomed into; an axis widened to the neighbour's own
     * value — what iOS's `visibleYValues` does, and what an earlier version of this did — would
     * squash the whole visible chart to make room for a number nobody can see. The painted geometry
     * is the segment clamped to the window, and that is what the axis is measured from.
     *
     * The window ends at 06:05, between two points, so 06:10 really is the drawn neighbour. An
     * earlier version ended it exactly on 06:00, where the spike was never selected at all and the
     * test passed on a rule instead of on a clipping it had reproduced.
     */
    @Test
    fun aSegmentCrossingTheEdgeFitsWithoutStretchingTheAxis() {
        val spikeAt = 37   // 06:10
        val withSpike = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = (0..143).map {
                FreeGraphPoint(midnight + (it * 10).minutes, if (it == spikeAt) 1450.0 else 1400.0, null, null)
            }
        )
        val zoomed = GraphProjection.plot(
            prepared(withSpike), everything, midnight + 5.minutes..(midnight + 6.hours + 5.minutes)
        )!!
        assertAxisHoldsWhatIsPainted(zoomed)
        // Halfway from 1400.0 to 1450.0 over the ten minutes 06:00–06:10, the edge sits at 1425.0.
        assertEquals(1425.0, paintedValues(zoomed).max(), 1e-6)
        assertTrue(
            "the axis made room for the 1450.0 that is drawn off the plot: ${zoomed.valueRange}",
            zoomed.valueRange!!.endInclusive < 1450.0
        )
    }

    /**
     * A band drawn right across the window, from two observations far outside it.
     *
     * The band's gaps are measured on the *server's* points, not on the band's own — so a dense line
     * whose high/low arrive only twice produces a two-point band with no collapse shoulders between
     * them, and `drawBand` shades the whole span. Both of its points are an hour and a half outside
     * a window in the middle, which is why "expand the window by one bucket" cannot find them: the
     * distance from the edge to the neighbour has no bound, and here it is nine buckets.
     */
    @Test
    fun aBandDrawnRightAcrossTheWindowIsNotClipped() {
        // Points every 12 minutes — inside the 1.5-bucket gap rule, so the line has no holds and
        // the band gets no shoulders. Only 00:00 and 02:00 carry a high/low.
        val sparseBand = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = (0..119).map {
                val wide = it == 0 || it == 10
                FreeGraphPoint(
                    midnight + (it * 12).minutes, 1400.0,
                    if (wide) 1425.0 else null,
                    if (wide) 1375.0 else null
                )
            }
        )
        val p = prepared(sparseBand)
        val band = p.bySeries["investing.usd"]!!.bandPoints
        assertEquals("the fixture stopped being the sparse band it is named for", 2, band.size)

        val zoomed = GraphProjection.plot(p, everything, (midnight + 1.hours)..(midnight + 90.minutes))!!
        assertAxisHoldsWhatIsPainted(zoomed)
        val axis = zoomed.valueRange!!
        assertTrue("the shading above the window was clipped: $axis", axis.endInclusive >= 1425.0)
        assertTrue("the shading below the window was clipped: $axis", axis.start <= 1375.0)

        // …and the reading follows the shading, because it really is on screen the whole way across.
        val seen = zoomed.observed!!
        assertEquals(1425.0, seen.endInclusive, 1e-6)
        assertEquals(1375.0, seen.start, 1e-6)
    }

    /**
     * A band that is not drawn is not described.
     *
     * The chart shades the high/low for a lone rate series only — with two lines one's shading sits
     * under the other and reads as a third series. Whatever the reason, nothing is painted, so a
     * description that named the shading's extremes would be describing a screen nobody has.
     */
    @Test
    fun aBandIsNotCountedWhenTheChartDoesNotDrawIt() {
        fun banded(id: String, base: Double) = FreeGraphSeries(
            seriesId = id, label = id, axisGroup = "krw",
            points = (0..143).map {
                val wide = it in 6..12
                FreeGraphPoint(
                    midnight + (it * 10).minutes, base,
                    if (wide) base + 9.0 else null,
                    if (wide) base - 9.0 else null
                )
            }
        )
        val alone = GraphProjection.plot(prepared(banded("investing.usd", 1400.0)), everything, firstSixHours)!!
        assertTrue("the lone series' band should count", alone.observed!!.endInclusive >= 1409.0 - 1e-6)

        val pair = GraphProjection.plot(
            prepared(banded("investing.usd", 1400.0), banded("kb.usd", 1402.0)), everything, firstSixHours
        )!!
        assertTrue("no band is drawn beside a second line", pair.lines.all { it.band.isEmpty() })
        assertTrue(
            "the reading named shading the chart does not draw: ${pair.observed}",
            pair.observed!!.endInclusive < 1409.0
        )
    }

    /**
     * One band point is no band at all.
     *
     * `drawBand` needs two points to have an area and returns early with one — reachable whenever a
     * single observation carries a high/low and its neighbour, close enough to raise no gap, does
     * not. Counting that lone pair of extremes would stretch the axis around shading that was never
     * painted, which is the same mistake as reaching past the window, in a smaller place.
     */
    @Test
    fun aOnePointBandDrawsNothingSoItIsNotCounted() {
        val single = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = (0..143).map {
                FreeGraphPoint(
                    midnight + (it * 10).minutes, 1400.0,
                    if (it == 6) 1409.0 else null,
                    if (it == 6) 1391.0 else null
                )
            }
        )
        val p = prepared(single)
        assertEquals("the fixture stopped being a one-point band", 1, p.bySeries["investing.usd"]!!.bandPoints.size)

        val zoomed = GraphProjection.plot(p, everything, firstSixHours)!!
        assertTrue("a one-point band was drawn after all", zoomed.lines.all { it.band.size < 2 })
        assertEquals("the axis made room for shading nobody drew", 1400.0, zoomed.observed!!.endInclusive, 1e-6)
        assertEquals(1400.0, zoomed.observed!!.start, 1e-6)
    }

    /**
     * A band whose window catches only one of its two points is not shading anything.
     *
     * `drawBand` gives up on a *selected* span of one — a two-point band ending on the window's left
     * edge has no width left inside it. Counting the whole list's extremes here put a 100원 spread
     * into an axis and a description for a chart showing a flat line, which is how this was found.
     */
    @Test
    fun aBandCaughtAtOneEndOfTheWindowIsNotCounted() {
        val edgeBand = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = (0..143).map {
                val wide = it == 0 || it == 6   // 00:00 and 01:00
                FreeGraphPoint(
                    midnight + (it * 10).minutes, 1400.0,
                    if (wide) 1450.0 else null,
                    if (wide) 1350.0 else null
                )
            }
        )
        val p = prepared(edgeBand)
        assertEquals("the fixture stopped being a two-point band", 2, p.bySeries["investing.usd"]!!.bandPoints.size)

        // The window opens exactly on the band's second point, so only its far end is selected.
        val zoomed = GraphProjection.plot(p, everything, (midnight + 1.hours)..(midnight + 7.hours))!!
        assertEquals(
            "the window caught more of the band than one point",
            1, GraphProjection.visibleBandSpan(zoomed.lines.first().band, zoomed.display).size
        )
        assertAxisHoldsWhatIsPainted(zoomed)
        assertEquals("shading nobody drew was read out", 1400.0, zoomed.observed!!.endInclusive, 1e-6)
        assertEquals(1400.0, zoomed.observed!!.start, 1e-6)
        assertTrue("the axis made room for shading nobody drew", zoomed.valueRange!!.endInclusive < 1450.0)
    }

    /**
     * Two points at one instant: the renderer draws the later one, so only the later one counts.
     *
     * [GraphProjection.visibleSpan] resolves the left edge with `indexOfLast`, so of two points
     * sharing that instant the earlier is never drawn. Nothing upstream forbids the pair —
     * `FreeSnapshotSanitizer.readSeries` checks each point's own shape and the builder only sorts —
     * so a filter written as "inside the window" reads out a value the chart does not contain.
     */
    @Test
    fun aDuplicateInstantTheRendererSkipsIsNotCounted() {
        val oneHour = midnight + 1.hours
        val duplicated = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = buildList {
                (0..5).forEach { add(FreeGraphPoint(midnight + (it * 10).minutes, 1400.0, null, null)) }
                add(FreeGraphPoint(oneHour, 1500.0, null, null))   // drawn only up to here
                add(FreeGraphPoint(oneHour, 1400.0, null, null))   // …the window starts on this one
                (7..143).forEach { add(FreeGraphPoint(midnight + (it * 10).minutes, 1400.0, null, null)) }
            }
        )
        val zoomed = GraphProjection.plot(prepared(duplicated), everything, oneHour..(midnight + 7.hours))!!
        assertEquals(
            "the renderer picked the wrong duplicate, so the fixture proves nothing",
            1400.0, GraphProjection.visibleSpan(zoomed.lines.first().points, zoomed.display).first().rate, 1e-9
        )
        assertAxisHoldsWhatIsPainted(zoomed)
        assertEquals("a point the chart never draws was read out", 1400.0, zoomed.observed!!.endInclusive, 1e-6)
        assertTrue("the axis made room for an undrawn duplicate", zoomed.valueRange!!.endInclusive < 1500.0)
    }

    /**
     * The reading names what crosses the edge, not what lies beyond it.
     *
     * The axis has to make room for the neighbour's own value; the description must not repeat it,
     * because that value is drawn outside the plot and clipped away. What a viewer can see at the
     * edge is where the segment crosses it, so that is the number — 1400.0 climbing towards 1450.0
     * over ten minutes reaches 1425.0 at the five-minute mark.
     */
    @Test
    fun theReadingNamesTheEdgeCrossingNotTheNeighbourBeyondIt() {
        val spikeAt = 37   // 06:10, five minutes past a window that ends at 06:05
        val withSpike = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = (0..143).map {
                FreeGraphPoint(midnight + (it * 10).minutes, if (it == spikeAt) 1450.0 else 1400.0, null, null)
            }
        )
        val zoomed = GraphProjection.plot(
            prepared(withSpike), everything, midnight + 5.minutes..(midnight + 6.hours + 5.minutes)
        )!!
        val seen = zoomed.observed!!
        assertEquals("the reading named the point beyond the edge", 1425.0, seen.endInclusive, 1e-6)
        assertEquals(1400.0, seen.start, 1e-6)
    }

    @Test
    fun aLoneSeriesZoomedGetsATighterAxis() {
        val p = prepared(rising("investing.usd", "krw", 1400.0))
        val whole = GraphProjection.plot(p, everything)!!
        val zoomed = GraphProjection.plot(p, everything, firstSixHours)!!
        val wholeSpan = whole.valueRange!!.endInclusive - whole.valueRange!!.start
        val zoomSpan = zoomed.valueRange!!.endInclusive - zoomed.valueRange!!.start
        assertTrue("the axis did not tighten: $wholeSpan -> $zoomSpan", zoomSpan < wholeSpan)
    }

    /**
     * Two lines keep the day's axis.
     *
     * Rescaling each to its own window would pull them apart on screen while the numbers behind them
     * did nothing of the sort. iOS gates on exactly this.
     */
    @Test
    fun twoSeriesZoomedKeepTheDaysAxis() {
        val p = prepared(rising("investing.usd", "krw", 1400.0), rising("kb.usd", "krw", 1402.0))
        val whole = GraphProjection.plot(p, everything)!!
        val zoomed = GraphProjection.plot(p, everything, firstSixHours)!!
        assertEquals(whole.valueRange, zoomed.valueRange)
        // …but the reading still follows the window, because that is a different question.
        assertTrue("the reading did not follow the window", zoomed.observed!!.endInclusive < whole.observed!!.endInclusive)
    }

    /** An index on screen means the rate axis is shared, so it is not one series' to tighten. */
    @Test
    fun anIndexOnScreenKeepsTheDaysAxis() {
        val p = prepared(rising("investing.usd", "krw", 1400.0), rising("dxy", "index", 99.0))
        val whole = GraphProjection.plot(p, everything)!!
        val zoomed = GraphProjection.plot(p, everything, firstSixHours)!!
        assertEquals(whole.valueRange, zoomed.valueRange)
    }

    /** Zoom is 1일 only, so nothing tightens anywhere else even when a window is handed in. */
    @Test
    fun otherPeriodsNeverTighten() {
        val p = GraphPreparedBuilder.build(
            FreeGraph(
                bucketSize = null,
                series = listOf(rising("investing.usd", "krw", 1400.0)),
                domainStartAt = midnight,
                domainEndAt = midnight + 24.hours,
                liveDomainMode = "rolling"
            ),
            GraphPeriod.ONE_WEEK
        )
        val whole = GraphProjection.plot(p, everything)!!
        val zoomed = GraphProjection.plot(p, everything, firstSixHours)!!
        assertEquals(whole.valueRange, zoomed.valueRange)
    }

    /**
     * A window with nothing in it falls back to the day rather than inventing an axis.
     *
     * Reachable: a window restored from yesterday, or a zoom into a gap.
     */
    @Test
    fun aWindowWithNoPointsFallsBackToTheDay() {
        val p = prepared(rising("investing.usd", "krw", 1400.0))
        val whole = GraphProjection.plot(p, everything)!!
        val empty = midnight - 10.hours..(midnight - 9.hours)
        val zoomed = GraphProjection.plot(p, everything, empty)
        // Nothing to draw at all is the honest answer for the lines…
        if (zoomed != null) {
            assertEquals("the axis was invented from an empty window", whole.valueRange, zoomed.valueRange)
            assertNull("the reading claimed values that are not on screen", zoomed.observed)
        }
    }

    @Test
    fun theAxisNeverGetsNarrowerThanTheFloor() {
        // Flat data: the window's own span is zero, so the floor is the only thing setting the axis.
        val flat = FreeGraphSeries(
            seriesId = "investing.usd", label = "investing.usd", axisGroup = "krw",
            points = (0..143).map { FreeGraphPoint(midnight + (it * 10).minutes, 1400.0, null, null) }
        )
        val p = prepared(flat)
        val zoomed = GraphProjection.plot(p, everything, firstSixHours)!!
        val span = zoomed.valueRange!!.endInclusive - zoomed.valueRange!!.start
        assertTrue("a flat window produced an axis of $span", span >= GraphZoomMath.TIGHTEN_MIN_SPAN - 1e-6)
    }

    /**
     * The shaded band counts, because it is drawn.
     *
     * An axis measured from the line alone would clip the high/low shading the chart puts around it —
     * widest exactly where the reading matters. `GraphPrepared.extremaOf` folds the band in for the
     * whole series; the window has to fold it in the same way or zooming would lose it.
     */
    @Test
    fun theBandCountsTowardTheWindowsExtremes() {
        val banded = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = (0..143).map {
                // A flat line with a band that only opens inside the first six hours.
                val wide = it in 6..12
                FreeGraphPoint(
                    midnight + (it * 10).minutes, 1400.0,
                    if (wide) 1409.0 else null,
                    if (wide) 1391.0 else null
                )
            }
        )
        val p = prepared(banded)
        val zoomed = GraphProjection.plot(p, everything, firstSixHours)!!
        val seen = zoomed.observed!!
        assertTrue("the band's high was left out: $seen", seen.endInclusive >= 1409.0 - 1e-6)
        assertTrue("the band's low was left out: $seen", seen.start <= 1391.0 + 1e-6)
    }

    /**
     * Margin first, then the floor on the total — the order iOS fixes and the order that matters.
     *
     * 1400.0…1400.4 has a 0.4 span, so the margin is the 0.5 floor on each side: 1399.5…1400.9,
     * span 1.4. That is under the 2.0 total floor, so both ends grow by 0.3.
     */
    @Test
    fun theMarginComesFirstAndTheFloorBoundsTheTotal() {
        val got = GraphZoomMath.tightenedYDomain(listOf(1400.0, 1400.4), minSpan = 2.0)
        assertNotNull(got)
        assertEquals(1399.2, got!!.start, 1e-9)
        assertEquals(1401.2, got.endInclusive, 1e-9)
        assertEquals(2.0, got.endInclusive - got.start, 1e-9)
    }

    /**
     * A window wide enough for the floor still gets its margin.
     *
     * The first version of this returned the raw extremes here, which draws the highest and lowest
     * points exactly on the border — half-clipped, and no more readable inside a zoom than outside
     * one. `GraphAxis.composeAxisRange` uses the same 5%/floor formula for the unzoomed axis.
     */
    @Test
    fun aWideWindowStillGetsItsMargin() {
        val wide = GraphZoomMath.tightenedYDomain(listOf(1400.0, 1405.0), minSpan = 2.0)!!
        // 5% of 5.0 is 0.25, under the 0.5 floor, so the floor wins on each side.
        assertEquals(1399.5, wide.start, 1e-9)
        assertEquals(1405.5, wide.endInclusive, 1e-9)
        // …and a span big enough for 5% to beat the floor uses the percentage.
        val huge = GraphZoomMath.tightenedYDomain(listOf(1400.0, 1440.0), minSpan = 2.0)!!
        assertEquals(1398.0, huge.start, 1e-9)
        assertEquals(1442.0, huge.endInclusive, 1e-9)
    }

    @Test
    fun anEmptyWindowHasNoAxisOfItsOwn() {
        assertNull(GraphZoomMath.tightenedYDomain(emptyList()))
    }

    /** The zoomed axis is never tighter than the values it must show. */
    @Test
    fun theTightenedAxisAlwaysContainsItsValues() {
        val values = listOf(1400.0, 1400.4, 1400.2)
        val got = GraphZoomMath.tightenedYDomain(values)!!
        assertTrue("the axis clipped its own values: $got", got.start < values.min())
        assertTrue("the axis clipped its own values: $got", got.endInclusive > values.max())
    }
    /**
     * A tightened axis still has readable gridlines.
     *
     * The floor and the 1/2/5/10 ladder were written for different reasons and have never met. Labels
     * are formatted to one decimal, so a step the ladder made too small would print the same number
     * twice — gridlines that look like a rendering fault. Nothing else checks the two together.
     */
    @Test
    fun aTightenedAxisStillHasDistinctGridlines() {
        val flat = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = (0..143).map { FreeGraphPoint(midnight + (it * 10).minutes, 1400.0, null, null) }
        )
        val zoomed = GraphProjection.plot(prepared(flat), everything, firstSixHours)!!
        val labels = zoomed.valueTicks.map { it.label }
        assertEquals("gridlines repeated a label: $labels", labels.size, labels.toSet().size)
        assertTrue("a tightened axis drew no gridlines at all", labels.size >= 2)
    }

}
