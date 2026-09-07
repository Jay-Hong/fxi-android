package com.jay.fxi

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
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

    /**
     * Two taps open six hours around the finger.
     *
     * The window is checked for **where** it sits, not only how long it is: a double tap that
     * centred on the frame instead of on the finger would still be six hours and would still look
     * plausible in a screenshot. 40% across the plot puts the answer away from the middle.
     */
    @Test
    fun aDoubleTapOnTheUnzoomedChartOpensSixHoursAroundTheFinger() {
        var zoom by mutableStateOf(GraphZoomState())
        var calls = 0
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it; calls++ }
            )
        }

        val node = composeRule.onNodeWithTag(CHART).fetchSemanticsNode()
        val area = GraphPlotGeometry.area(
            widthPx = node.size.width.toFloat(),
            heightPx = node.size.height.toFloat(),
            hasIndex = false,
            density = composeRule.density.density
        )
        val rendered = GraphFrame.rendered(GraphFrame.resolve(prepared)!!, GraphPeriod.ONE_DAY)
        val whole = rendered.end - rendered.start
        val x = area.left + area.width * 0.40f
        val expected = rendered.start + whole * 0.40

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            down(0, Offset(x, y)); up(0)
            // Comfortably inside doubleTapMinTime(40ms)..doubleTapTimeout(300ms). Without this the
            // gap is one event period, which is *below* the minimum and would not pair.
            advanceEventTime(100)
            down(0, Offset(x, y)); up(0)
        }
        composeRule.waitForIdle()

        val window = zoom.visible
        assertNotNull(
            "the double tap opened nothing — node=${node.size} area=$area x=$x calls=$calls zoom=$zoom",
            window
        )
        val length = window!!.endInclusive - window.start
        assertEquals(6.hours, length)
        val centre = window.start + length / 2
        val drift = (centre - expected).absoluteValue
        assertTrue("the window centred $drift away from the finger", drift < 10.minutes)
    }

    /**
     * …and two more put the whole chart back.
     *
     * This is also the only test of the pan slop, and it is the reason the slop exists: without it
     * the first pressed frame on a zoomed chart begins a pan, the touch is never a tap, and the
     * double tap that undoes the zoom is out of reach for any finger that reports movement at all.
     * A down with no move between it and the up still slips through — that hole is why the taps
     * below carry a 1px move, and why the first version of this test caught nothing. iOS spells the
     * same rule `pan.require(toFail: doubleTap)`.
     */
    @Test
    fun aDoubleTapOnAZoomedChartPutsTheWholeChartBack() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
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
        assertNotNull("this test needs a zoomed chart to start from", zoom.visible)

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val x = width * 0.5f
            // A pressed frame between the down and the up, which `up()` does not insert by itself
            // (`InputDispatcher.enqueueTouchUp`). Without one the loop breaks on the very next
            // event, the pan branch is never reached, and the slop this test exists to check goes
            // unexercised — measured: deleting the slop left this test green.
            //
            // One pixel, not zero. A `moveTo` to the position the finger is already at never
            // arrives at all — also measured, and it looks identical in the test source, which is
            // how the first version of this line failed to catch anything. One pixel is far under
            // the slop (~24px here), so a working chart still reads it as a tap.
            down(0, Offset(x, y)); moveTo(0, Offset(x + 1f, y)); up(0)
            advanceEventTime(100)
            down(0, Offset(x, y)); moveTo(0, Offset(x + 1f, y)); up(0)
        }
        composeRule.waitForIdle()

        assertNull("the chart stayed zoomed after a double tap", zoom.visible)
    }

    /** A second tap a second later is a second tap, not the other half of the first. */
    @Test
    fun twoTapsTooFarApartInTimeAreNotAPair() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val x = width * 0.5f
            down(0, Offset(x, y)); up(0)
            advanceEventTime(1000)
            down(0, Offset(x, y)); up(0)
        }
        composeRule.waitForIdle()

        assertNull("two separate taps zoomed the chart", zoom.visible)
    }

    /**
     * Two taps at opposite ends of the chart are two taps.
     *
     * 150dp apart, against the 100dp this chart allows. Compose's own detector has **no** limit
     * here — `awaitSecondDown` only enforces the time window — so this distance check exists only
     * because we wrote it, and nothing else would notice if it were dropped.
     */
    @Test
    fun twoTapsTooFarApartOnScreenAreNotAPair() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val first = Offset(10.dp.toPx(), y)
            down(0, first); up(0)
            advanceEventTime(100)
            down(0, Offset(first.x + 150.dp.toPx(), y)); up(0)
        }
        composeRule.waitForIdle()

        assertNull("taps 150dp apart were treated as a pair", zoom.visible)
    }

    /**
     * The axis labels are not the chart.
     *
     * iOS lays its recognisers over the plot alone, so a finger on the rate labels reaches nothing.
     * Here the handler covers the whole node — gutters included — and has to decide for itself.
     */
    @Test
    fun aDoubleTapOnTheRateLabelsDoesNothing() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        val node = composeRule.onNodeWithTag(CHART).fetchSemanticsNode()
        val area = GraphPlotGeometry.area(
            widthPx = node.size.width.toFloat(),
            heightPx = node.size.height.toFloat(),
            hasIndex = false,
            density = composeRule.density.density
        )
        // Halfway into the value gutter, which is 44dp of the 360dp width.
        val x = (area.right + node.size.width) / 2f

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            down(0, Offset(x, y)); up(0)
            advanceEventTime(100)
            down(0, Offset(x, y)); up(0)
        }
        composeRule.waitForIdle()

        assertNull("a double tap on the labels zoomed the chart", zoom.visible)
    }

    /**
     * A window restored from a day ago covers everything, and covering everything is not a zoom.
     *
     * The clamp that decides this lives in `GraphZoomState.windowFor`, and JVM tests already lock
     * it. What they cannot see is whether `GraphChart` **passes the clamped window to the gestures**
     * rather than only to the Canvas — an earlier version did exactly that, and the chart then
     * swallowed one-finger drags while showing the whole frame. There is nothing to look at on
     * screen, so the observable is the arbitration: an unzoomed chart takes no drag at all.
     */
    @Test
    fun aRestoredWindowThatCoversEverythingTakesNoDrag() {
        val prepared = dayGraph()
        val rendered = GraphFrame.rendered(GraphFrame.resolve(prepared)!!, GraphPeriod.ONE_DAY)
        val restored = GraphZoomState(
            visible = rendered.start..rendered.end,
            isFollowing = false
        )
        var zoom by mutableStateOf(restored)
        var calls = 0
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it; calls++ }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals("the chart panned on a window that covers the whole frame", 0, calls)
        assertEquals("and so it should have left the state alone", restored, zoom)
    }

    /**
     * A pinch the system takes away still has to end.
     *
     * Changing the density, not removing the node. Removal looks like the right lever and is not:
     * Compose runs `detachedListener` before `onDetach` (`Modifier.kt`), the listener drops the node
     * from `HitPathTracker`, and that **dispatches a synthetic cancel** — an ordinary event with no
     * pointers pressed, which leaves by the `break`. A test built on removal therefore passes with
     * the terminal gesture moved out of `finally` altogether, and locks nothing.
     *
     * `onDensityChange` goes straight to `resetPointerInputHandler`
     * (`SuspendingPointerInputFilter`), which cancels the coroutine with no event at all. Then only
     * `finally` can send `PinchEnded`, and only `PinchEnded` re-clamps the window `PinchBegan`
     * filled with the whole frame. Without it the stored state keeps that window and a baseline
     * with no finger on it. The screen survives — `GraphChart` clamps the window before anything
     * reads it — so this asserts the state directly, which is the only place the difference shows.
     */
    @Test
    fun aPinchTakenAwayMidGestureStillEnds() {
        var zoom by mutableStateOf(GraphZoomState())
        var density by mutableStateOf(3f)
        val prepared = dayGraph()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                GraphChart(
                    prepared = prepared,
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
        }
        composeRule.waitForIdle()
        assertNotNull("the pinch never began, so there is nothing to abandon", zoom.pinchBaseline)
        assertNotNull("PinchBegan should have filled the window", zoom.visible)

        composeRule.runOnIdle { density = 2f }
        composeRule.waitForIdle()

        assertNull("an abandoned pinch left the whole frame in the window", zoom.visible)
        assertNull("an abandoned pinch left its baseline behind", zoom.pinchBaseline)
    }

    /**
     * A finger held down and let go is not the first half of anything.
     *
     * Nothing else here holds a finger still for long enough, and the duration test is the one
     * condition a tap can fail while satisfying all the others — same pointer, same place, no
     * movement at all.
     */
    @Test
    fun aLongPressIsNotTheFirstHalfOfADoubleTap() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val x = width * 0.5f
            down(0, Offset(x, y))
            advanceEventTime(700)   // past longPressTimeout, which is 500ms
            up(0)
            advanceEventTime(100)
            down(0, Offset(x, y)); up(0)
        }
        composeRule.waitForIdle()

        assertNull("a long press paired with the tap after it", zoom.visible)
    }

    /**
     * Three taps are a pair and then a single, not a pair and then another pair.
     *
     * The third tap must not close a second pair with the second — on an already zoomed chart that
     * would undo the zoom the first pair just made, so the chart would flicker out to the whole
     * frame on the tap the user thought was harmless. `UITapGestureRecognizer` with
     * `numberOfTapsRequired = 2` starts counting again after it fires, and so does this.
     */
    @Test
    fun aThirdTapDoesNotUndoTheZoomTheFirstTwoMade() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val x = width * 0.5f
            down(0, Offset(x, y)); up(0)
            advanceEventTime(100)
            down(0, Offset(x, y)); up(0)
            advanceEventTime(100)
            down(0, Offset(x, y)); up(0)
        }
        composeRule.waitForIdle()

        val window = zoom.visible
        assertNotNull("the first pair opened nothing, so the third tap proves nothing", window)
        assertEquals(6.hours, window!!.endInclusive - window.start)
    }

    /**
     * A touch the system takes back is not the second half of anything.
     *
     * Android cancellation does not arrive as a cancelled *gesture*; Compose turns it into ordinary
     * changes with `pressed = false` and `isInitiallyConsumed = true`
     * (`SuspendingPointerInputFilter.onCancelPointerInput`). Those satisfy every other condition of
     * a tap — one pointer, no travel, no time, inside the plot — so without the consumption check
     * the finger the user never lifted closes the pair and the chart zooms under their hand.
     */
    @Test
    fun aTouchTheSystemCancelsIsNotTheSecondHalfOfAPair() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val x = width * 0.5f
            down(0, Offset(x, y)); up(0)
            advanceEventTime(100)
            down(0, Offset(x, y)); cancel()
        }
        composeRule.waitForIdle()

        assertNull("a cancelled touch completed a double tap", zoom.visible)
    }

    /**
     * A finger that only moves on the way up has still moved.
     *
     * The travel that decides a tap is followed by pointer id, not by "whichever pointer is
     * pressed", and this is the difference: the up carries `pressed = false`, so a rule written the
     * other way never measures the position the finger actually left from. `updatePointerTo` moves
     * the pointer without sending an event, so the whole movement lands on the up.
     */
    @Test
    fun aFlickWhoseMovementLandsOnTheUpIsNotATap() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val x = width * 0.4f
            down(0, Offset(x, y)); up(0)
            advanceEventTime(100)
            down(0, Offset(x, y))
            updatePointerTo(0, Offset(x + 200f, y))   // far past any touch slop
            up(0)
        }
        composeRule.waitForIdle()

        assertNull("a flick counted as the second half of a double tap", zoom.visible)
    }

    /**
     * Two taps in the same instant are one contact bouncing, not a pair.
     *
     * The upper bound has its own test; this is the other end. `doubleTapMinTimeMillis` is the
     * reason a rule written as "within the timeout" alone is wrong, and only a gap this short can
     * tell the two apart.
     */
    @Test
    fun twoTapsTooCloseInTimeAreNotAPair() {
        var zoom by mutableStateOf(GraphZoomState())
        val prepared = dayGraph()
        composeRule.setContent {
            GraphChart(
                prepared = prepared,
                visibleIds = setOf("investing.usd"),
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = { zoom = it }
            )
        }

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val x = width * 0.5f
            down(0, Offset(x, y)); up(0)
            advanceEventTime(10)   // under doubleTapMinTime, which is 40ms
            down(0, Offset(x, y)); up(0)
        }
        composeRule.waitForIdle()

        assertNull("two taps 10ms apart were treated as a pair", zoom.visible)
    }

    /**
     * The frame that lands *exactly* on touch slop belongs to the chart, not to the pager.
     *
     * Foundation crosses its threshold at `inDirection >= touchSlop`
     * (`DragGestureDetector`'s `TouchSlopDetector`). A chart written with `>` therefore declines the
     * one frame the pager accepts: the pager consumes, the chart sees that in the Final pass, and
     * `PASS_THROUGH` is permanent — so a zoomed chart loses its pan for the rest of the touch and
     * the page turns underneath it instead.
     *
     * Driven as raw pointers with the slop read out of the composition, because the whole test is
     * one exact number; `swipeLeft()` jumps straight past it and proves nothing.
     */
    @Test
    fun aDragLandingExactlyOnTouchSlopStaysWithTheChart() {
        var zoom by mutableStateOf(GraphZoomState())
        var slop = 0f
        composeRule.setContent {
            slop = LocalViewConfiguration.current.touchSlop
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
        assertTrue("the slop never came out of the composition", slop > 0f)

        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            val x = width * 0.7f
            down(0, Offset(x, y))
            // Purely horizontal and exactly the slop: the one frame where `>` and `>=` disagree.
            moveTo(0, Offset(x - slop, y))
            moveTo(0, Offset(x - slop - 100f, y))
            up(0)
        }
        composeRule.waitForIdle()

        assertEquals("the pager took the drag on the slop frame", 0, settledPage)
        assertTrue("the chart never panned, so the pager owned the touch", zoom.visible != afterPinch)
    }

    private var settledPage: Int = -1

    private companion object {
        const val CHART = "chart"
        const val PAGER = "pager"
    }
}
