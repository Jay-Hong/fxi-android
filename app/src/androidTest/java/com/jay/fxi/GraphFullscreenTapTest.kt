package com.jay.fxi

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.GraphChart
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphZoomState
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Who owns a lone tap on the chart, and when it is allowed to mean "leave".
 *
 * Fullscreen is the only place a tap leaves, and on 1일 the same finger might be opening a zoom, so
 * the answer has to wait out the double-tap window. iOS arranges exactly this with
 * `singleTap.require(toFail: doubleTap)` and records why: a recogniser that fires on the first tap
 * turned a fullscreen double-tap zoom into a dismissal on iPadOS 17.
 *
 * The waiting is the part that needs a device. It is driven by `mainClock`, not by the injector's
 * event clock — measured: `waitForIdle()` alone never expires it, `advanceTimeBy(window + margin)`
 * does. Which also means the double-tap test below must not advance the clock **past the window**
 * between its two taps; a smaller step would be harmless.
 */
class GraphFullscreenTapTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val midnight = Instant.parse("2026-09-08T00:00:00Z")

    private fun graph(period: GraphPeriod) = GraphPreparedBuilder.build(
        FreeGraph(
            bucketSize = null,
            series = listOf(
                FreeGraphSeries(
                    seriesId = "investing.usd",
                    label = "인베스팅",
                    axisGroup = "krw",
                    points = (0..143).map {
                        FreeGraphPoint(midnight + (it * 10).minutes, 1400.0 + (it % 12), null, null)
                    }
                )
            ),
            domainStartAt = midnight,
            domainEndAt = midnight + 24.hours,
            liveDomainMode = "rolling"
        ),
        period
    )

    private class Harness(
        val leaves: () -> Int,
        val zoom: () -> GraphZoomState,
        val timeoutMillis: () -> Long
    )

    private fun mount(
        period: GraphPeriod = GraphPeriod.ONE_DAY,
        visible: Set<String> = setOf("investing.usd"),
        dismissible: Boolean = true,
        zoomable: Boolean = true
    ): Harness {
        var left = 0
        var zoom by mutableStateOf(GraphZoomState())
        var timeout = 0L
        val prepared = graph(period)
        composeRule.setContent {
            timeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
            GraphChart(
                prepared = prepared,
                visibleIds = visible,
                modifier = Modifier.size(360.dp, 220.dp).testTag(CHART),
                zoom = zoom,
                onZoom = if (zoomable) ({ z: GraphZoomState -> zoom = z }) else null,
                onSingleTap = if (dismissible) ({ left++ }) else null
            )
        }
        return Harness({ left }, { zoom }, { timeout })
    }

    private fun tap(x: Float = 0.5f) {
        composeRule.onNodeWithTag(CHART).performTouchInput {
            val p = Offset(width * x, height / 2f)
            down(0, p); up(0)
        }
    }

    /** One tap leaves — but only after the window in which it might have been half of a pair. */
    @Test
    fun aSingleTapLeavesOnceTheDoubleTapWindowCloses() {
        val h = mount()
        tap()
        composeRule.waitForIdle()
        assertEquals("the tap left before the double-tap window closed", 0, h.leaves())

        composeRule.mainClock.advanceTimeBy(h.timeoutMillis() + 200)
        composeRule.waitForIdle()
        assertEquals("the tap never resolved into a dismissal", 1, h.leaves())
    }

    /**
     * Two taps zoom and stay.
     *
     * This is the regression iOS measured on iPadOS 17. No clock advance between the taps — that is
     * not a convenience, it is the condition: advancing past the window there would close it and
     * turn the first tap into a dismissal, which is exactly the defect.
     */
    @Test
    fun aDoubleTapZoomsAndDoesNotLeave() {
        val h = mount()
        composeRule.onNodeWithTag(CHART).performTouchInput {
            val p = Offset(width * 0.5f, height / 2f)
            down(0, p); up(0)
            advanceEventTime(100)
            down(0, p); up(0)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(h.timeoutMillis() + 200)
        composeRule.waitForIdle()

        assertNotNull("the double tap opened nothing", h.zoom().visible)
        assertEquals("the double tap also left fullscreen", 0, h.leaves())
    }

    /**
     * Every other period leaves at once.
     *
     * There is no zoom on those, so nothing to wait for — and iOS keeps its plain SwiftUI tap there
     * for the same reason (the gesture overlay is mounted on 1일 only). Asserting *before* any clock
     * advance is what separates this from the 1일 path.
     */
    @Test
    fun onOtherPeriodsTheTapLeavesAtOnce() {
        val h = mount(period = GraphPeriod.ONE_WEEK)
        tap()
        composeRule.waitForIdle()
        assertEquals("a tap on 1주 did not leave", 1, h.leaves())
    }

    /**
     * A chart with nothing to draw still lets you out.
     *
     * Two early returns draw a message instead of a chart, and neither attaches the zoom loop. An
     * earlier version of this slice put the dismissal on the caller's modifier and lost it on both
     * paths — a fullscreen with no data that only the 닫기 button could leave.
     */
    @Test
    fun aChartWithNothingToDrawStillLeaves() {
        val h = mount(visible = emptySet())
        tap()
        composeRule.waitForIdle()
        assertEquals("a tap on the empty state did not leave", 1, h.leaves())
    }

    /** The inline card has no exit, so its taps mean nothing but zoom. */
    @Test
    fun theInlineCardDoesNotLeaveOnATap() {
        val h = mount(dismissible = false)
        tap()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(h.timeoutMillis() + 200)
        composeRule.waitForIdle()
        assertEquals("the inline chart reported a dismissal", 0, h.leaves())
    }

    /**
     * Asking for a dismissal gets you one, whatever else was or was not wired.
     *
     * The loop is the one that defers, and it is only attached when zoom is. A condition written as
     * "1일 means the loop owns the tap" is true only while every 1일 caller also wires zoom — and
     * the failure if that stops being true is silent: a chart with no way out looks fine.
     */
    @Test
    fun aDismissalWithNoZoomWiredStillLeaves() {
        val h = mount(zoomable = false)
        tap()
        composeRule.waitForIdle()
        assertEquals("a chart asked for a dismissal and gave none", 1, h.leaves())
    }

    /**
     * The axis labels are part of "anywhere".
     *
     * Every period but 1일 gets a plain `clickable` over the whole chart, gutters included. If the
     * 1일 path reused the zoom loop's own idea of a tap — which requires the plot, because a double
     * tap needs an instant to centre on — the same pixel would leave on 1주 and do nothing on 1일.
     * 44dp of rate labels on a 360dp chart is not a corner case.
     */
    @Test
    fun aTapOnTheAxisLabelsLeavesToo() {
        val h = mount()
        // Well inside the 44dp value gutter on the right.
        tap(x = 0.96f)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(h.timeoutMillis() + 200)
        composeRule.waitForIdle()
        assertEquals("a tap on the rate labels did not leave", 1, h.leaves())
    }

    /** …but two of them do not zoom: there is no instant under the labels to centre on. */
    @Test
    fun aDoubleTapOnTheAxisLabelsDoesNotZoom() {
        val h = mount()
        composeRule.onNodeWithTag(CHART).performTouchInput {
            val p = Offset(width * 0.96f, height / 2f)
            down(0, p); up(0)
            advanceEventTime(100)
            down(0, p); up(0)
        }
        composeRule.waitForIdle()
        assertEquals("the labels opened a zoom", null, h.zoom().visible)
    }

    /**
     * A pair needs both taps on the plot, not just the first.
     *
     * The rate gutter is 44dp and the pairing distance is 100dp, so "just inside the plot, then just
     * outside it" is an ordinary miss, not a corner case. Recording the first tap is already gated
     * on the plot; this is the other half, and without it the second tap would centre a zoom on an
     * instant the label column does not have.
     */
    @Test
    fun aSecondTapOnTheLabelsDoesNotCompleteAPair() {
        val h = mount()
        composeRule.onNodeWithTag(CHART).performTouchInput {
            val y = height / 2f
            // Inside the plot, then into the gutter — about 20dp apart, well within the 100dp pair.
            down(0, Offset(width * 0.86f, y)); up(0)
            advanceEventTime(100)
            down(0, Offset(width * 0.93f, y)); up(0)
        }
        composeRule.waitForIdle()
        assertEquals("a tap on the labels completed a zoom", null, h.zoom().visible)
    }

    /**
     * A held finger leaves too, because it does on every other period.
     *
     * `clickable` has no time limit: press, wait, lift, and the other periods close. If the 1일 path
     * kept the zoom recogniser's short-tap rule for leaving as well, the same hold would work on 1주
     * and do nothing on 1일 — the asymmetry this arrangement exists to avoid, in a second place.
     */
    @Test
    fun aHeldFingerLeavesAsWell() {
        val h = mount()
        composeRule.onNodeWithTag(CHART).performTouchInput {
            val p = Offset(width * 0.5f, height / 2f)
            down(0, p)
            advanceEventTime(700)   // past longPressTimeout, which is 500ms
            up(0)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(h.timeoutMillis() + 200)
        composeRule.waitForIdle()
        assertEquals("a held finger did not leave", 1, h.leaves())
    }

    /**
     * …and it still cannot start a zoom.
     *
     * The two rules part company here: leaving does not care how long the finger stayed, a pair
     * does. `UITapGestureRecognizer` draws the same line.
     */
    @Test
    fun aHeldFingerCannotStartAZoomPair() {
        val h = mount()
        composeRule.onNodeWithTag(CHART).performTouchInput {
            val p = Offset(width * 0.5f, height / 2f)
            down(0, p); advanceEventTime(700); up(0)
            advanceEventTime(100)
            down(0, p); up(0)
        }
        composeRule.waitForIdle()
        assertEquals("a long press paired into a zoom", null, h.zoom().visible)
    }

    /**
     * The one period that zooms is not the one period a screen reader cannot close.
     *
     * On every other period `clickable` contributes an activation action to the semantics tree for
     * free. The 1일 loop reads raw pointer events and contributes nothing, so the action has to be
     * declared. Nothing about that shows up in a touch test.
     */
    @Test
    fun theChartOffersAnActivationActionOn1일Too() {
        mount()
        composeRule.onNodeWithTag(CHART).assertHasClickAction()
    }

    private companion object {
        const val CHART = "chart"
    }
}
