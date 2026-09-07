package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphProjectionTest {

    private val day0 = Instant.parse("2026-09-01T00:00:00Z")

    private fun point(day: Int, rate: Double, high: Double? = null, low: Double? = null) =
        FreeGraphPoint(day0 + day.days, rate, high, low)

    private fun series(id: String, axis: String?, points: List<FreeGraphPoint>) =
        FreeGraphSeries(seriesId = id, points = points, label = id, axisGroup = axis)

    private fun prepared(vararg series: FreeGraphSeries) = GraphPreparedBuilder.build(
        FreeGraph(
            bucketSize = null, series = series.toList(),
            domainStartAt = day0, domainEndAt = day0 + 10.days, liveDomainMode = "fixed_start"
        ),
        GraphPeriod.THREE_MONTHS
    )

    private val rates = series("investing.usd", "krw", listOf(point(1, 1390.0), point(9, 1410.0)))
    private val index = series("dxy", "index", listOf(point(1, 98.0), point(9, 100.0)))

    /**
     * The time axis spans every series the answer carries; the value axis spans only the visible
     * ones. Hiding a line frees the scale it was stretching without sliding the chart sideways.
     */
    @Test
    fun hidingASeriesMovesTheValueAxisButNotTheTimeAxis() {
        val model = prepared(rates, series("hana.usd", "krw", listOf(point(2, 1500.0), point(3, 1502.0))))
        val both = GraphProjection.plot(model, setOf("investing.usd", "hana.usd"))!!
        val one = GraphProjection.plot(model, setOf("investing.usd"))!!

        assertEquals(both.data, one.data)
        assertEquals(both.display, one.display)
        assertTrue("the axis did not shrink when a series was hidden",
            one.valueRange!!.endInclusive < both.valueRange!!.endInclusive)
    }

    /**
     * An index drawn beside rates is folded onto the rate scale, and keeps its own numbers for the
     * second column of labels.
     */
    @Test
    fun anIndexBesideRatesIsFoldedOntoTheRateScale() {
        val plot = GraphProjection.plot(prepared(rates, index), setOf("investing.usd", "dxy"))!!
        assertNotNull("the index kept no scale of its own", plot.indexRange)
        assertTrue(plot.indexTicks.isNotEmpty())

        val folded = plot.lines.single { it.isIndex }.points
        // Folded values live in the rate range, not near 99.
        assertTrue("the index was not folded", folded.all { it.rate > 1000.0 })
        // Its shape survives: the higher index reading is still the higher point.
        assertTrue(folded.first().rate < folded.last().rate)
    }

    /**
     * With no rates on screen the index is the chart — and it keeps its own labels.
     *
     * Handing it to the rate ladder was the defect: that ladder prints one decimal, so an index
     * spanning a tenth would label every gridline "99.0".
     */
    @Test
    fun anIndexAloneKeepsItsOwnNumbers() {
        val plot = GraphProjection.plot(prepared(rates, index), setOf("dxy"))!!
        val points = plot.lines.single { it.isIndex }.points
        assertTrue("the index was folded with nothing to fold onto", points.all { it.rate < 200.0 })
        assertTrue(plot.valueRange!!.start < 98.0)

        // Its own column, at its own precision — and no rate ladder, which is not its scale.
        assertEquals(plot.valueRange, plot.indexRange)
        assertTrue(plot.indexTicks.isNotEmpty())
        assertTrue("the rate ladder was applied to an index", plot.valueTicks.isEmpty())
        assertEquals(plot.indexTicks.map { it.label }, plot.indexTicks.map { it.label }.distinct())
    }

    /**
     * The shaded band is for a lone rate series only.
     *
     * With two lines, one's shading sits under the other and reads as a third series; with an
     * index folded in, the band would be measured against a scale it does not belong to.
     */
    @Test
    fun onlyALoneRateSeriesIsShaded() {
        val banded = series("investing.usd", "krw", listOf(
            point(1, 1390.0, high = 1395.0, low = 1385.0), point(9, 1410.0, high = 1415.0, low = 1405.0)
        ))
        val other = series("hana.usd", "krw", listOf(point(1, 1400.0), point(9, 1402.0)))

        val alone = GraphProjection.plot(prepared(banded), setOf("investing.usd"))!!
        assertTrue("a lone rate series lost its band", alone.lines.single().band.isNotEmpty())

        val withSibling = GraphProjection.plot(prepared(banded, other), setOf("investing.usd", "hana.usd"))!!
        assertTrue(withSibling.lines.all { it.band.isEmpty() })

        val withIndex = GraphProjection.plot(prepared(banded, index), setOf("investing.usd", "dxy"))!!
        assertTrue(withIndex.lines.all { it.band.isEmpty() })
    }

    /**
     * What a screen reader is told is what the series reached, not what the axis spans.
     *
     * The axis is padded by five per cent on each side by design, so reading it out reports values
     * no series ever hit.
     */
    @Test
    fun theSpokenRangeIsObserved_notTheAxis() {
        val plot = GraphProjection.plot(prepared(rates), setOf("investing.usd"))!!
        assertEquals(1390.0, plot.observed!!.start, 1e-9)
        assertEquals(1410.0, plot.observed!!.endInclusive, 1e-9)
        assertTrue("the axis is not padded, so this proves nothing", plot.valueRange!!.start < plot.observed!!.start)
        val spoken = plot.describe()
        assertTrue("spoken range was the padded axis: $spoken", spoken.contains("최저 1390.00 최고 1410.00"))
    }

    /** Nothing on a rate axis: there is no observed rate range to speak of. */
    @Test
    fun anIndexAloneHasNoSpokenRateRange() {
        val plot = GraphProjection.plot(prepared(rates, index), setOf("dxy"))!!
        assertNull(plot.observed)
    }

    /** An index has no band to show — a shaded spread on a folded overlay means nothing. */
    @Test
    fun anIndexCarriesNoBand() {
        val banded = series("dxy", "index", listOf(point(1, 98.0, high = 99.0, low = 97.0)))
        val plot = GraphProjection.plot(prepared(rates, banded), setOf("investing.usd", "dxy"))!!
        assertTrue(plot.lines.single { it.isIndex }.band.isEmpty())
    }

    /**
     * Zooming changes what the x scale maps onto; it does not change where the data is.
     *
     * Over a realistic quarter, not the ten-day window the other cases use — a fortnightly tick
     * interval needs a span long enough to place more than one line, or the comparison below is
     * between two empty lists.
     */
    @Test
    fun zoomingMovesTheDisplayWindowOnly() {
        val quarter = GraphPreparedBuilder.build(
            FreeGraph(
                bucketSize = null,
                series = listOf(series("investing.usd", "krw", listOf(point(1, 1390.0), point(89, 1410.0)))),
                domainStartAt = day0, domainEndAt = day0 + 90.days, liveDomainMode = "fixed_start"
            ),
            GraphPeriod.THREE_MONTHS
        )
        val full = GraphProjection.plot(quarter, setOf("investing.usd"))!!
        val zoomed = GraphProjection.plot(
            quarter, setOf("investing.usd"), visibleDomain = (day0 + 20.days)..(day0 + 30.days)
        )!!
        assertEquals(full.data, zoomed.data)
        assertEquals(day0 + 20.days, zoomed.display.start)
        assertEquals(day0 + 30.days, zoomed.display.end)
        // `display` stops being the padded frame the moment a window exists, but the gestures still
        // need it — it is what "zoomed all the way out" is measured against. Carried, not recomputed.
        assertEquals(full.display, zoomed.rendered)
        assertEquals(full.rendered, zoomed.rendered)
        assertTrue("padding must sit outside the data on both sides",
            zoomed.rendered.start < zoomed.data.start && zoomed.rendered.end > zoomed.data.end)
        assertTrue("the full view drew no gridlines to compare against", full.xTicks.isNotEmpty())
        assertTrue(
            "full=${full.xTicks.size} zoomed=${zoomed.xTicks.size}",
            zoomed.xTicks.size < full.xTicks.size
        )
    }

    /** A backwards or empty zoom window is ignored rather than throwing mid-draw. */
    @Test
    fun aDegenerateZoomWindowFallsBackToTheFullFrame() {
        val model = prepared(rates)
        val full = GraphProjection.plot(model, setOf("investing.usd"))!!
        listOf((day0 + 4.days)..(day0 + 2.days), (day0 + 2.days)..(day0 + 2.days)).forEach { window ->
            assertEquals(full.display, GraphProjection.plot(model, setOf("investing.usd"), window)!!.display)
        }
    }

    @Test
    fun nothingVisibleIsAnEmptyPlotRatherThanNoPlot() {
        val plot = GraphProjection.plot(prepared(rates), emptySet())!!
        assertTrue(plot.isEmpty)
        assertNull(plot.valueRange)
        assertTrue(plot.valueTicks.isEmpty())
        assertNull(plot.observed)
        // The time axis still exists, so the chart keeps its shape while everything is switched off.
        assertEquals(day0, plot.data.start)
    }

    /**
     * A server that declared a window but sent no readings still gets a chart — an empty one.
     *
     * The frame is real, so the axes are real; there is simply nothing on them. Refusing to plot
     * would collapse the card and make an empty answer look like a failed one.
     */
    @Test
    fun aDeclaredWindowWithNoReadingsIsAnEmptyChart_notNoChart() {
        val plot = GraphProjection.plot(
            prepared(series("investing.usd", "krw", emptyList())), setOf("investing.usd")
        )!!
        assertTrue(plot.isEmpty)
        assertNull(plot.valueRange)
        assertEquals(day0, plot.data.start)
    }

    /** Neither a window nor any readings: there is nothing to draw and nothing to invent. */
    @Test
    fun neitherAWindowNorReadingsHasNoPlot() {
        val nothing = GraphPreparedBuilder.build(
            FreeGraph(
                bucketSize = null,
                series = listOf(series("investing.usd", "krw", emptyList())),
                domainStartAt = null, domainEndAt = null, liveDomainMode = null
            ),
            GraphPeriod.THREE_MONTHS
        )
        assertNull(GraphProjection.plot(nothing, setOf("investing.usd")))
    }

    // --- projection ---------------------------------------------------------------------------------

    @Test
    fun timeAndValueMapOntoTheUnitSquare_withTheLargestValueOnTop() {
        val display = TimeFrame(day0, day0 + 10.days)
        assertEquals(0f, GraphProjection.xOf(day0, display), 1e-6f)
        assertEquals(0.5f, GraphProjection.xOf(day0 + 5.days, display), 1e-6f)
        assertEquals(1f, GraphProjection.xOf(day0 + 10.days, display), 1e-6f)

        assertEquals(1f, GraphProjection.yOf(1390.0, 1390.0..1410.0), 1e-6f)
        assertEquals(0f, GraphProjection.yOf(1410.0, 1390.0..1410.0), 1e-6f)
        // A spanless axis draws down the middle rather than dividing by zero.
        assertEquals(0.5f, GraphProjection.yOf(1400.0, 1400.0..1400.0), 0f)
    }

    /**
     * A line entering the window from off-screen must still enter it.
     *
     * Keeping only the points inside would start the stroke at the first visible reading, leaving a
     * gap at the edge where the line should already have been.
     */
    @Test
    fun theSegmentEnteringTheWindowIsKept() {
        val points = (0..10).map { LinePoint(day0 + it.days, 1400.0 + it) }
        val span = GraphProjection.visibleSpan(points, TimeFrame(day0 + 3.days, day0 + 5.days))
        assertEquals(day0 + 3.days, span.first().ts)
        assertEquals(day0 + 5.days, span.last().ts)

        val offset = GraphProjection.visibleSpan(
            points, TimeFrame(day0 + 3.days + 1.days / 2, day0 + 5.days + 1.days / 2)
        )
        assertEquals("the entering segment was clipped away", day0 + 3.days, offset.first().ts)
        assertEquals("the leaving segment was clipped away", day0 + 6.days, offset.last().ts)
        assertEquals(emptyList<LinePoint>(), GraphProjection.visibleSpan(emptyList(), TimeFrame(day0, day0 + 1.days)))
    }
}
