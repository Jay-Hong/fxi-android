package com.jay.fxi

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.GraphChart
import com.jay.fxi.ui.graph.GraphFrame
import com.jay.fxi.ui.graph.GraphPlotGeometry
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphZoomState
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The pointer half of the free graph's zoom, which no JVM test can reach.
 *
 * `GraphZoomReducer` already locks where the window goes; nothing here re-checks that. What is only
 * true on a device is **who the touch belongs to** — a pinch has to reach the chart, and a single
 * finger has to reach the pager until something is zoomed. Driving that through `adb` is not
 * possible (`input` has no multi-touch), so it is driven through real synthesised pointers here.
 */
class GraphZoomGesturesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val midnight = Instant.parse("2026-09-07T00:00:00Z")

    private fun points(base: Double) = (0..143).map {
        FreeGraphPoint(
            timestamp = midnight + (it * 10).minutes,
            rate = base + (it % 12),
            high = null,
            low = null
        )
    }

    /**
     * A day of ten-minute points, which is the shape the 1일 snapshot actually has.
     *
     * [withIndex] adds a 달러지수 series, which is not decoration: an index on screen claims a 36dp
     * gutter on the left, so the plot starts somewhere else and every touch maps to a different
     * instant. The anchor has to survive that.
     */
    private fun dayGraph(withIndex: Boolean = false) = GraphPreparedBuilder.build(
        FreeGraph(
            bucketSize = null,
            series = listOfNotNull(
                FreeGraphSeries(
                    seriesId = "investing.usd",
                    label = "인베스팅",
                    axisGroup = "krw",
                    points = points(1400.0)
                ),
                if (withIndex) FreeGraphSeries(
                    seriesId = "dxy",
                    label = "달러지수",
                    axisGroup = "index",
                    points = points(99.0)
                ) else null
            ),
            domainStartAt = midnight,
            domainEndAt = midnight + 24.hours,
            liveDomainMode = "rolling"
        ),
        GraphPeriod.ONE_DAY
    )

    @Test
    fun aPinchNarrowsTheWindow() {
        var zoom by mutableStateOf(GraphZoomState())
        composeRule.setContent {
            GraphChart(
                prepared = dayGraph(),
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            pinch(
                start0 = Offset(width * 0.40f, y), end0 = Offset(width * 0.05f, y),
                start1 = Offset(width * 0.60f, y), end1 = Offset(width * 0.95f, y)
            )
        }
        composeRule.waitForIdle()

        assertNotNull("the pinch produced no window at all", zoom.visible)
        val length = zoom.visible!!.let { it.endInclusive - it.start }
        // Well under the frame, not merely under it. `< 24.hours` would have been satisfied by a
        // window 0.37% narrower than the whole chart — which is what a broken pinch produces, and
        // is indistinguishable from `assertNotNull`.
        assertTrue("the window barely moved: $length", length < 12.hours)
        assertNull("a finished gesture must leave no baseline", zoom.pinchBaseline)
    }

    /**
     * The instant between the fingers stays between them.
     *
     * This is the one property a pinch has, and nothing else here checks it: the other tests only
     * ask whether the window got smaller, which stays true however far the anchor drifts. It caught
     * a real defect — `calculateCentroid` counts only pointers that were already down last frame,
     * so on the frame the second finger lands it returns the *first finger's* position, and the
     * chart pivoted around one finger instead of between them (measured: 1h 13m off).
     */
    @Test
    fun theInstantBetweenTheFingersStaysBetweenThem() = assertAnchorHoldsMidpoint(withIndex = false)

    /**
     * …and it still does with 달러지수 on screen, which is not the same test.
     *
     * An index claims a 36dp gutter, so the plot's left edge moves and every touch maps to a
     * different instant. A gesture measuring from the node rather than the plot would be wrong
     * only in this case. Separate `@Test` because `setContent` may be called once per test — the
     * first version looped over both and the second pass threw, so the index case never ran while
     * appearing to.
     */
    @Test
    fun theAnchorHoldsWithAnIndexOnScreenToo() = assertAnchorHoldsMidpoint(withIndex = true)

    private fun assertAnchorHoldsMidpoint(withIndex: Boolean) {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph(withIndex = withIndex)
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = if (withIndex) setOf("investing.usd", "dxy") else setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        val node = composeRule.onNodeWithTag(CHART).fetchSemanticsNode()
        val area = GraphPlotGeometry.area(
            widthPx = node.size.width.toFloat(),
            heightPx = node.size.height.toFloat(),
            hasIndex = withIndex,
            density = composeRule.density.density
        )
        val rendered = GraphFrame.rendered(GraphFrame.resolve(prepared)!!, GraphPeriod.ONE_DAY)
        val whole = rendered.end - rendered.start
        // Fingers at 20% and 40% of the *plot*, so the midpoint is 30% — far enough from both the
        // centre and from either finger that anchoring on the wrong one is unmistakable.
        val left = area.left + area.width * 0.20f
        val right = area.left + area.width * 0.40f
        val expected = rendered.start + whole * 0.30

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            pinch(
                start0 = Offset(left, y), end0 = Offset(left - area.width * 0.10f, y),
                start1 = Offset(right, y), end1 = Offset(right + area.width * 0.10f, y)
            )
        }
        composeRule.waitForIdle()

        val window = zoom.visible
        assertNotNull("index=$withIndex produced no window", window)
        val got = window!!.start + (window.endInclusive - window.start) * 0.30
        val drift = (got - expected).absoluteValue
        assertTrue(
            "index=$withIndex anchored $drift away from the midpoint of the fingers",
            drift < 5.minutes
        )
    }

    /**
     * The arbitration contract, stated the way the user meets it: on the unzoomed chart a sideways
     * drag turns the page. The chart fills the page, so a chart that swallowed this would strand
     * the user on one tab with no way out.
     */
    @Test
    fun aSidewaysDragOnTheUnzoomedChartTurnsThePage() {
        composeRule.setContent {
            val pager = rememberPagerState(initialPage = 0) { 2 }
            settledPage = pager.settledPage
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag(PAGER)) { page ->
                if (page == 0) {
                    GraphChart(
                        prepared = dayGraph(),
                        visibleIds = setOf("investing.usd"),
                        modifier = Modifier.fillMaxSize().testTag(CHART),
                        zoom = GraphZoomState(),
                        onZoom = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag(CHART).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals("the chart swallowed a swipe that belonged to the pager", 1, settledPage)
    }

    /** …and once zoomed the same drag is the chart's, because it is how you look around. */
    @Test
    fun aSidewaysDragOnAZoomedChartDoesNotTurnThePage() {
        var zoom by mutableStateOf(GraphZoomState())
        composeRule.setContent {
            val pager = rememberPagerState(initialPage = 0) { 2 }
            settledPage = pager.settledPage
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag(PAGER)) { page ->
                if (page == 0) {
                    GraphChart(
                        prepared = remember { dayGraph() },
                        visibleIds = setOf("investing.usd"),
                        modifier = Modifier.fillMaxSize().testTag(CHART),
                        zoom = zoom,
                        onZoom = { zoom = it }
                    )
                }
            }
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            pinch(
                start0 = Offset(width * 0.40f, y), end0 = Offset(width * 0.05f, y),
                start1 = Offset(width * 0.60f, y), end1 = Offset(width * 0.95f, y)
            )
        }
        composeRule.waitForIdle()
        assertNotNull("this test needs a zoomed chart to be meaningful", zoom.visible)
        val afterPinch = zoom.visible

        composeRule.onNodeWithTag(CHART).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals("the pager took a drag that belonged to the zoomed chart", 0, settledPage)
        assertTrue("the drag did not move the window", zoom.visible != afterPinch)
        // Direction, not just movement: dragging left walks the window forward in time. Asserting
        // only "it changed" leaves a sign flip alive at this layer, where no JVM test can see it.
        assertTrue(
            "dragging left moved the window backwards",
            zoom.visible!!.start > afterPinch!!.start
        )
    }

    /**
     * A second finger must not steal a swipe the pager has already taken.
     *
     * Consuming at that point is not a polite decline — the parent's drag ends with
     * `onDragStopped(Velocity.Zero)`, and a pager already dragged past its threshold settles onto
     * the *next* page. The user would see the tab change while both fingers are still down, having
     * meant to zoom.
     *
     * Driven as raw pointers rather than `pinch()` because the order is the whole point: drag far
     * enough for the pager to claim the gesture, and only then put the second finger down.
     */
    @Test
    fun aSecondFingerDoesNotStealASwipeThePagerAlreadyOwns() {
        var zoom by mutableStateOf(GraphZoomState())
        composeRule.setContent {
            val pager = rememberPagerState(initialPage = 0) { 2 }
            settledPage = pager.settledPage
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag(PAGER)) { page ->
                if (page == 0) {
                    GraphChart(
                        prepared = remember { dayGraph() },
                        visibleIds = setOf("investing.usd"),
                        modifier = Modifier.fillMaxSize().testTag(CHART),
                        zoom = zoom,
                        onZoom = { zoom = it }
                    )
                }
            }
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            down(0, Offset(width * 0.55f, y))
            // Past the pager's slop so it owns the drag, but far short of the half-page it would
            // need to turn — the first attempt dragged 50% of the width, which turns the page on
            // its own merits, so the test failed with and without the fix and proved nothing.
            moveTo(0, Offset(width * 0.45f, y))
            // Now the second finger, as if the user changed their mind and went to zoom.
            down(1, Offset(width * 0.65f, y))
            moveTo(0, Offset(width * 0.35f, y))
            moveTo(1, Offset(width * 0.80f, y))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        // The zoom is the whole assertion, and deliberately the only one. Where the pager ends up
        // is the pager's business — it may well flick to the next page off a 20% drag, and twice
        // now a version of this test failed by asserting that it would not. The contract this
        // slice owns is narrower: the chart must not help itself to a drag already taken.
        assertNull("the chart zoomed on a drag the pager owned", zoom.visible)
    }

    /**
     * A finger that moves a little before its partner arrives must still be able to pinch.
     *
     * This is the ordinary way a pinch begins, and an earlier attempt at the ownership rule broke
     * it: reading "one finger on an unzoomed chart" as "the pager's" committed before the pager had
     * claimed anything, so any wobble ahead of the second finger killed the zoom. Both `pinch()`
     * tests miss it because that helper puts the fingers down together.
     */
    @Test
    fun aFingerThatMovesBeforeItsPartnerArrivesCanStillPinch() {
        var zoom by mutableStateOf(GraphZoomState())
        composeRule.setContent {
            GraphChart(
                prepared = remember { dayGraph() },
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            down(0, Offset(width * 0.45f, y))
            moveTo(0, Offset(width * 0.44f, y))   // a wobble, nobody has claimed anything
            down(1, Offset(width * 0.55f, y))
            moveTo(0, Offset(width * 0.20f, y))
            moveTo(1, Offset(width * 0.80f, y))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertNotNull("a pinch that began with one finger already moving did nothing", zoom.visible)
    }

    /**
     * A touch that was already someone else's before it reached us is not ours to take.
     *
     * The case that motivates it is a pager still coasting from a fling: a scrollable in motion
     * consumes the `down` immediately (`startDragImmediately`), and two fingers landing on it with
     * no movement in between would otherwise walk straight into a pinch. The loop never saw that
     * event — `awaitFirstDown` returns after the Main pass and the loop waits for the next one.
     *
     * Driven by a parent that consumes in the Initial pass rather than by a real fling: the rule
     * being checked is "already consumed → hands off", and pinning it to the pager's fling timing
     * would test Compose's scheduling more than it tests this.
     */
    @Test
    fun aTouchAlreadyTakenByAnAncestorIsNotStolen() {
        var zoom by mutableStateOf(GraphZoomState())
        composeRule.setContent {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.forEach { it.consume() }
                            if (e.changes.none { it.pressed }) break
                        }
                    }
                }
            ) {
                GraphChart(
                    prepared = remember { dayGraph() },
                    visibleIds = setOf("investing.usd"),
                    modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                    zoom = zoom,
                    onZoom = { zoom = it }
                )
            }
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            down(0, Offset(width * 0.45f, y))
            down(1, Offset(width * 0.55f, y))
            moveTo(0, Offset(width * 0.15f, y))
            moveTo(1, Offset(width * 0.85f, y))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertNull("the chart pinched on a touch an ancestor had already taken", zoom.visible)
    }

    private var settledPage: Int = -1

    private companion object {
        const val CHART = "chart"
        const val PAGER = "pager"
    }
}
