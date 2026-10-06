package com.jay.fxi.ui.premium.graph

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphSelectionBinding
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphSeriesStyles
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 C1-2b-2 contract r1 (instrumented): the chart's gestures reach it through the real premium section and
 * fullscreen - nothing the surfaces wrap around the chart takes, blocks or reverses them.
 *
 * Oracles: iOS a36682f GraphV2Section.swift :452-466 (fullscreen 1d: the single tap waits for the double tap to fail, a double
 * tap zooms and does not dismiss; other periods dismiss at once), :601-606 (inline zoom on, no tap dismissal); the shared
 * chart's arbitration already locked at chart level by GraphZoomGesturesTest :213-271 (unzoomed sideways drag turns the page,
 * zoomed it pans forward) and GraphFullscreenTapTest :111-150 (single tap after the double-tap window, double tap zooms).
 * Design: R4c/S4 c12b_api_codex.r1 §3 as reviewed by c12b_review_claude.r1 §2/§6/§7 (observed through the chart's spoken
 * extremes on monotonic rates; the window arithmetic, gutters, 1h floor and the off-1d gating stay with the chart-level
 * tests - no surface code decides them; small private input helpers here, the existing gesture tests untouched).
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphV2SurfaceGesturesTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        val A = AuthIdentityFence("u1", 1L)
        val FENCE = TopicSessionFence(A, "e1", TopicGrantToken(7L))
        val OWNER = TopicDisplayOwner(A, 1L)
        val LIFETIME = TopicUseLifetime(TopicGrantToken(7L), 3L)
        val BINDING = GraphSelectionBinding(A, GraphSelectionKey("u1", GraphSelectionAudience.PREMIUM, "usd"), 1L)
        fun inline(period: GraphPeriod) =
            GraphV2UiToken(OWNER, FENCE, LIFETIME, BINDING, "usd", period, 1L, GraphV2Surface.INLINE, 0L)
        fun full(period: GraphPeriod) =
            GraphV2UiToken(OWNER, FENCE, LIFETIME, BINDING, "usd", period, 1L, GraphV2Surface.FULLSCREEN, 1L)

        const val X = "hana.usd"
        const val Z = "investing.usd"
        val MIDNIGHT: Instant = Instant.parse("2026-10-05T00:00:00Z")
        const val INLINE_CHART = "graph_v2:inline:chart"
        const val FULL_CHART = "graph_v2:fullscreen:chart"
        val EXTREMES = Regex("최저 ([0-9.]+) 최고 ([0-9.]+)")
    }

    // --- fixture ------------------------------------------------------------------------------------------------------

    private fun series(id: String, base: Double, period: GraphPeriod) = FreeGraphSeries(
        seriesId = id, label = "label:$id", axisGroup = "krw",
        points = if (period == GraphPeriod.ONE_DAY) {
            (0 until 144).map { FreeGraphPoint(MIDNIGHT + (it * 10).minutes, base + it * 0.5, null, null) }
        } else {
            (0 until 90).map { FreeGraphPoint(MIDNIGHT - (90 - it).days, base + it * 0.5, null, null) }
        }
    )

    private fun state(period: GraphPeriod, open: Boolean) = GraphV2ScreenState(
        "usd", period, listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), GraphV2Content.READY, GraphV2SelectionStatus.READY,
        GraphSeriesSelection(setOf(X, Z), setOf(X, Z)),
        listOf(X, Z).map { GraphV2SeriesToggle(it, GraphSeriesStyles.of(it, "label:$it"), true, true, "krw") },
        GraphV2ChartModel(
            GraphPreparedBuilder.build(
                if (period == GraphPeriod.ONE_DAY) {
                    FreeGraph(null, listOf(series(X, 1400.0, period), series(Z, 1390.0, period)),
                        domainStartAt = MIDNIGHT, domainEndAt = MIDNIGHT + 24.hours, liveDomainMode = "rolling")
                } else {
                    FreeGraph(null, listOf(series(X, 1400.0, period), series(Z, 1390.0, period)),
                        domainStartAt = MIDNIGHT - 90.days, domainEndAt = MIDNIGHT, liveDomainMode = "fixed_start")
                },
                period
            ),
            setOf(X, Z)
        ),
        false, null, null, open, inline(period), if (open) full(period) else null
    )

    private val events = mutableListOf<Pair<String, GraphV2UiToken>>()
    private val actions = GraphV2UiActions(
        selectPeriod = { t, p -> events += "period:${p.code}" to t },
        toggleSeries = { t, id -> events += "toggle:$id" to t },
        enterFullscreen = { t -> events += "expand" to t },
        exitFullscreen = { t -> events += "exit" to t },
        retrySelection = { t -> events += "retry" to t }
    )
    private var settledPage = -1
    private var doubleTapTimeout = 0L

    /** The section on the first page of a real pager - the place the screen will put it. */
    private fun mountInPager(state: GraphV2ScreenState) {
        rule.setContent {
            doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
            val pager = rememberPagerState(initialPage = 0) { 2 }
            settledPage = pager.settledPage
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                if (page == 0) GraphV2Section(state, actions)
            }
        }
        rule.waitForIdle()
    }

    private fun mountWithFullscreen(state: GraphV2ScreenState) {
        rule.setContent { Host(state) }
        rule.waitForIdle()
    }

    @Composable
    private fun Host(state: GraphV2ScreenState) {
        doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
        Box(Modifier.fillMaxSize()) {
            GraphV2Section(state, actions)
            GraphV2Fullscreen(state, actions, Modifier.fillMaxSize())
        }
    }

    private fun chart(tag: String) = rule.onNode(hasTestTag(tag), useUnmergedTree = true)

    private fun spoken(tag: String): String =
        chart(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    private fun extremes(tag: String): Pair<Double, Double> {
        val m = checkNotNull(EXTREMES.find(spoken(tag))) { "no extremes in '${spoken(tag)}'" }
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }

    private fun zoomIn(tag: String) {
        chart(tag).performTouchInput {
            val y = height / 2f
            pinch(
                start0 = Offset(width * 0.40f, y), end0 = Offset(width * 0.05f, y),
                start1 = Offset(width * 0.60f, y), end1 = Offset(width * 0.95f, y)
            )
        }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        chart(tag).performTouchInput { val p = Offset(width * 0.5f, height / 2f); down(0, p); up(0) }
        rule.waitForIdle()
    }

    /**
     * Two taps 100ms apart. With [jitter] each tap carries the 1px pressed move a finger makes (GraphZoomGesturesTest :514 -
     * a move to the same position never arrives, so the jitter is one pixel, far under the slop).
     */
    private fun doubleTap(tag: String, jitter: Boolean = false) {
        chart(tag).performTouchInput {
            val p = Offset(width * 0.5f, height / 2f)
            repeat(2) { i ->
                if (i == 1) advanceEventTime(100)
                down(0, p)
                if (jitter) moveTo(0, p + Offset(1f, 0f))
                up(0)
            }
        }
        rule.waitForIdle()
    }

    private fun passTheDoubleTapWindow() {
        rule.mainClock.advanceTimeBy(doubleTapTimeout + 200)
        rule.waitForIdle()
    }

    // --- 09: the pager ------------------------------------------------------------------------------------------------

    /** G01: on the unzoomed inline chart a sideways drag turns the page and reports nothing. */
    @Test fun G01_anUnzoomedSidewaysDragTurnsThePage() {
        mountInPager(state(GraphPeriod.ONE_DAY, open = false))
        chart(INLINE_CHART).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals("the section swallowed a swipe that belonged to the pager", 1, settledPage)
        assertTrue(events.isEmpty())
    }

    /** G02: once zoomed, the same drag stays on the page and walks the window forward in time. */
    @Test fun G02_aZoomedSidewaysDragPansForward() {
        mountInPager(state(GraphPeriod.ONE_DAY, open = false))
        val fullWindow = spoken(INLINE_CHART)
        zoomIn(INLINE_CHART)
        val zoomed = extremes(INLINE_CHART)
        assertNotEquals("premise: the pinch reached the chart", fullWindow, spoken(INLINE_CHART))
        chart(INLINE_CHART).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals("the pager took a drag that belonged to the zoomed chart", 0, settledPage)
        assertTrue("dragging left walks the window forward (a monotonic rate rises)", extremes(INLINE_CHART).first > zoomed.first)
        assertTrue(events.isEmpty())
    }

    // --- 09: taps ----------------------------------------------------------------------------------------------------

    /** G03: a single tap on the fullscreen 1d chart leaves only after the double-tap window, once, with the fullscreen token. */
    @Test fun G03_aFullscreenSingleTapLeavesAfterTheDoubleTapWindow() {
        mountWithFullscreen(state(GraphPeriod.ONE_DAY, open = true))
        tap(FULL_CHART)
        assertTrue("the tap left before the double-tap window closed", events.isEmpty())
        passTheDoubleTapWindow()
        assertEquals(listOf("exit" to full(GraphPeriod.ONE_DAY)), events)
    }

    /**
     * G04: a double tap on the fullscreen 1d chart zooms and does not leave, even after the window; a double tap on the zoomed
     * chart (with the 1px movement a finger makes) goes back to the full window and does not leave either.
     */
    @Test fun G04_aFullscreenDoubleTapZoomsAndResetsWithoutLeaving() {
        mountWithFullscreen(state(GraphPeriod.ONE_DAY, open = true))
        val fullWindow = spoken(FULL_CHART)
        doubleTap(FULL_CHART)
        passTheDoubleTapWindow()
        assertNotEquals("the double tap zoomed", fullWindow, spoken(FULL_CHART))
        doubleTap(FULL_CHART, jitter = true)
        passTheDoubleTapWindow()
        assertEquals("a double tap on the zoomed chart resets it", fullWindow, spoken(FULL_CHART))
        assertTrue("no double tap left the fullscreen", events.isEmpty())
    }

    /** G05: the inline chart has no tap dismissal: a single tap reports nothing, even after the window. */
    @Test fun G05_anInlineTapReportsNothing() {
        mountWithFullscreen(state(GraphPeriod.ONE_DAY, open = false))
        tap(INLINE_CHART)
        passTheDoubleTapWindow()
        assertTrue(events.isEmpty())
    }
}
