package com.jay.fxi.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plot rectangle, which two callers have to agree on exactly.
 *
 * Worth its own tests despite being arithmetic: the numbers here decide where a finger lands in
 * time, and the left edge is conditional — a mistake there would move the pinch anchor only when
 * an index series happens to be switched on.
 */
class GraphPlotGeometryTest {

    private val density = 3f   // a typical phone; every gutter below is dp × 3

    /**
     * Literal numbers on purpose.
     *
     * Written as `VALUE_GUTTER_DP * density` these would follow the constant wherever it went — 44
     * could become 35 and the assertion would still hold, which is a test that only checks the code
     * agrees with itself. The four margins are a visual contract, so they are spelled out: 44, 36,
     * 16 and 6 dp at ×3.
     */
    @Test
    fun theLabelsTakeTheirMarginsFromTheEdges() {
        val area = GraphPlotGeometry.area(1000f, 600f, hasIndex = false, density = 3f)
        assertEquals(0f, area.left, 0f)
        assertEquals(18f, area.top, 0f)          // 6dp
        assertEquals(868f, area.right, 0f)       // 1000 − 44dp
        assertEquals(552f, area.bottom, 0f)      // 600 − 16dp
        assertEquals(868f, area.width, 0f)
        assertEquals(534f, area.height, 0f)
    }

    @Test
    fun theIndexGutterIsThirtySixDp() {
        val area = GraphPlotGeometry.area(1000f, 600f, hasIndex = true, density = 3f)
        assertEquals(108f, area.left, 0f)        // 36dp
        assertEquals(760f, area.width, 0f)       // 1000 − 36dp − 44dp
    }

    /**
     * The one that moves under the user: turning 달러지수 on claims a gutter on the left, so the
     * same pixel is a different instant before and after. Both callers must see the same edge.
     */
    @Test
    fun anIndexOnScreenClaimsTheLeftGutter() {
        val without = GraphPlotGeometry.area(1000f, 600f, hasIndex = false, density = density)
        val with = GraphPlotGeometry.area(1000f, 600f, hasIndex = true, density = density)
        assertEquals(0f, without.left, 0f)
        assertEquals(108f, with.left, 0f)
        assertEquals("the right edge is not the index's business", without.right, with.right, 0f)
        assertEquals(108f, without.width - with.width, 1e-3f)
    }

    @Test
    fun densityScalesEveryMargin() {
        val one = GraphPlotGeometry.area(1000f, 600f, hasIndex = true, density = 1f)
        val three = GraphPlotGeometry.area(1000f, 600f, hasIndex = true, density = 3f)
        assertEquals(one.left * 3f, three.left, 1e-3f)
        assertEquals(one.top * 3f, three.top, 1e-3f)
        assertEquals(1000f - (1000f - one.right) * 3f, three.right, 1e-3f)
        assertEquals(600f - (600f - one.bottom) * 3f, three.bottom, 1e-3f)
    }

    /**
     * Before layout, and on a chart squeezed smaller than its own margins, there is no plot at all.
     * Callers have to notice rather than divide by a negative width.
     */
    @Test
    fun aChartSmallerThanItsMarginsHasNoPlot() {
        assertTrue(GraphPlotGeometry.area(0f, 0f, hasIndex = false, density = density).isEmpty)
        // 44dp + 36dp of gutters at ×3 is 240px, so 200px of width leaves nothing.
        assertTrue(GraphPlotGeometry.area(200f, 600f, hasIndex = true, density = density).isEmpty)
        assertTrue(GraphPlotGeometry.area(1000f, 40f, hasIndex = false, density = density).isEmpty)
        assertTrue(!GraphPlotGeometry.area(1000f, 600f, hasIndex = true, density = density).isEmpty)
    }

    /**
     * Exactly zero, not merely negative — that is the boundary the zoom math divides by.
     *
     * A chart laid out at precisely its own margins has a plot of width 0, and `locationToInstant`
     * and `panTranslated` both guard on `> 0`. Testing only the negative case leaves the guard here
     * free to be `< 0` without anything noticing.
     */
    @Test
    fun aPlotOfExactlyZeroExtentIsEmpty() {
        // 44dp of value gutter at ×3 is 132px, so 132px of width leaves exactly nothing.
        val noWidth = GraphPlotGeometry.area(132f, 600f, hasIndex = false, density = density)
        assertEquals(0f, noWidth.width, 0f)
        assertTrue(noWidth.isEmpty)

        // 6dp top + 16dp bottom at ×3 is 66px.
        val noHeight = GraphPlotGeometry.area(1000f, 66f, hasIndex = false, density = density)
        assertEquals(0f, noHeight.height, 0f)
        assertTrue(noHeight.isEmpty)
    }

    @Test
    fun widthAndHeightAreTheSpanBetweenTheEdges() {
        val area = GraphPlotGeometry.area(1000f, 600f, hasIndex = true, density = density)
        assertEquals(area.right - area.left, area.width, 0f)
        assertEquals(area.bottom - area.top, area.height, 0f)
    }

    /**
     * The gutters are not part of the plot, and a tap that lands on them is a tap on the labels.
     *
     * iOS lays its gesture overlay over the plot alone, so a finger on the axis is a finger on
     * nothing; the double tap here has to reach the same conclusion from a rectangle. All four
     * edges are checked because `contains` is four comparisons and any one of them can be the
     * wrong way round without the other three noticing.
     */
    @Test
    fun theGuttersAreOutsideThePlot() {
        val area = GraphPlotGeometry.area(1000f, 600f, hasIndex = true, density = density)

        assertTrue(area.contains(area.left + 1f, area.top + 1f))
        assertTrue(area.contains(area.right - 1f, area.bottom - 1f))
        // The index gutter on the left, the value gutter on the right, the top inset, the time
        // labels underneath.
        assertTrue(!area.contains(area.left - 1f, area.top + 1f))
        assertTrue(!area.contains(area.right + 1f, area.top + 1f))
        assertTrue(!area.contains(area.left + 1f, area.top - 1f))
        assertTrue(!area.contains(area.left + 1f, area.bottom + 1f))
    }

    /** The edges belong to the plot: a finger exactly on the boundary drew on the last pixel. */
    @Test
    fun theEdgesOfThePlotAreInsideIt() {
        val area = GraphPlotGeometry.area(1000f, 600f, hasIndex = false, density = density)
        assertTrue(area.contains(area.left, area.top))
        assertTrue(area.contains(area.right, area.bottom))
    }

    /**
     * Exactly zero extent is the case the range check alone gets wrong.
     *
     * Inverted bounds fall out for free — `left..right` is empty — but at exactly its own margins
     * the plot has `left == right`, and that is a singleton range containing a point. The Canvas
     * draws nothing there, so a double tap must find nothing there either. Without the `isEmpty`
     * guard this passes for the negative case and fails only here.
     */
    @Test
    fun aPlotOfZeroExtentContainsNothingEither() {
        // 36dp + 44dp of gutters at ×3 is 240px, so 240px of width leaves left == right == 108.
        val flat = GraphPlotGeometry.area(240f, 600f, hasIndex = true, density = density)
        assertEquals(flat.left, flat.right, 1e-3f)
        assertTrue(flat.isEmpty)
        assertTrue(!flat.contains(flat.left, (flat.top + flat.bottom) / 2f))
    }

    /**
     * A chart with no plot takes no taps.
     *
     * `left..right` is an empty range once the two are inverted, so this falls out of Kotlin rather
     * than out of a guard — which is exactly why it is worth pinning. Written the other way round
     * (`x >= left && x <= right` against an inverted pair) it would answer true for nothing, and
     * with `min`/`max` "helpfully" applied it would answer true for the whole gutter.
     */
    @Test
    fun aChartWithNoPlotContainsNothing() {
        val none = GraphPlotGeometry.area(200f, 600f, hasIndex = true, density = density)
        assertTrue(none.isEmpty)
        assertTrue(!none.contains(none.left, none.top))
        assertTrue(!none.contains(100f, 300f))
        assertTrue(!none.contains(0f, 0f))
    }

}
