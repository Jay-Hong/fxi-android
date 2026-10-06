package com.jay.fxi.ui.premium.graph

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 C1-2b-1 contract r1 (instrumented): the premium fullscreen graph drawn from a holder state over the inline
 * section, and who owns each zoom.
 *
 * Oracles: iOS a36682f GraphV2Section.swift :441-470 (a separate fullscreen chart instance over the inline one, its own toggle
 * header with a close button and its own period bar; the close button, a single tap on the chart and a tap on the status area
 * dismiss; a period change does not), :1111/:1157 (each chart instance resets its zoom on a period change), :287
 * (fullScreenCover - the inline chart and its zoom stay alive underneath); FreeSnapshotScreen.kt :264/:516 (Android keeps each
 * zoom across activity recreation with rememberSaveable(GraphZoomStateSaver)) and FreeFullscreenZoomTest (zoom observed through
 * the chart's spoken extremes, GraphChart.kt :267). Design: R4c/S4 c12b_api_codex.r1 §1-§3 as reviewed by
 * c12b_review_claude.r1 (no observer seam - the spoken extremes are the observation; the zoom is remembered under key(token)
 * and saveable; GraphV2Fullscreen(state, actions, modifier) draws exactly when the state has a fullscreen token - the holder
 * publishes one only while the fullscreen is open (H08p/H08r), and a fullscreen without a token could not report its exit;
 * tags graph_v2:fullscreen:*). r2 (Codex's r1 review): F07 names what it does - emulated saved-state restoration, not an
 * activity recreation.
 *
 * Every chart here is a monotonic rate, so a narrower window speaks different extremes and the full window speaks the same
 * extremes on either surface. What this observes is the visible window only - not the follow flag or a gesture baseline - and
 * only on 1d: off 1d no window applies (GraphZoomState.windowFor), so every reset is checked back on 1d. The exact window
 * arithmetic (1h floor, double-tap span) stays with GraphZoomMathTest and GraphZoomGesturesTest. The holder's half (a period
 * change keeps the fullscreen open, retire/deactivation/block remove it) is the JVM contract's H08p/H08r; here the state is
 * supplied by the test, as the holder would publish it.
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphV2FullscreenTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        val A = AuthIdentityFence("u1", 1L)
        val FENCE = TopicSessionFence(A, "e1", TopicGrantToken(7L))
        val OWNER = TopicDisplayOwner(A, 1L)
        val LIFETIME = TopicUseLifetime(TopicGrantToken(7L), 3L)
        val BINDING = GraphSelectionBinding(A, GraphSelectionKey("u1", GraphSelectionAudience.PREMIUM, "usd"), 1L)
        fun inline(period: GraphPeriod = GraphPeriod.ONE_DAY, screen: Long = 1L) =
            GraphV2UiToken(OWNER, FENCE, LIFETIME, BINDING, "usd", period, screen, GraphV2Surface.INLINE, 0L)
        fun full(period: GraphPeriod = GraphPeriod.ONE_DAY, screen: Long = 1L, gen: Long = 1L) =
            GraphV2UiToken(OWNER, FENCE, LIFETIME, BINDING, "usd", period, screen, GraphV2Surface.FULLSCREEN, gen)

        const val X = "hana.usd"
        const val Y = "kb.usd"
        const val Z = "investing.usd"
        val MIDNIGHT: Instant = Instant.parse("2026-10-05T00:00:00Z")

        const val RECHECKING = "그래프 표시 설정을 다시 확인하고 있습니다."
        const val NOT_SAVED = "그래프 표시 설정을 저장하지 못했습니다."

        fun tag(surface: GraphV2Surface, name: String) =
            "graph_v2:${if (surface == GraphV2Surface.INLINE) "inline" else "fullscreen"}:$name"
        val IN = GraphV2Surface.INLINE
        val FS = GraphV2Surface.FULLSCREEN
    }

    // --- fixture ------------------------------------------------------------------------------------------------------

    private fun daySeries(id: String, base: Double) = FreeGraphSeries(
        seriesId = id, label = "label:$id", axisGroup = "krw",
        points = (0 until 144).map { FreeGraphPoint(MIDNIGHT + (it * 10).minutes, base + it * 0.5, null, null) }
    )

    private fun quarterSeries(id: String, base: Double) = FreeGraphSeries(
        seriesId = id, label = "label:$id", axisGroup = "krw",
        points = (0 until 90).map { FreeGraphPoint(MIDNIGHT - (90 - it).days, base + it * 0.5, null, null) }
    )

    private fun dayChart(rendered: Set<String> = setOf(X, Z)) = GraphV2ChartModel(
        GraphPreparedBuilder.build(
            FreeGraph(null, listOf(daySeries(X, 1400.0), daySeries(Y, 1380.0), daySeries(Z, 1390.0)),
                domainStartAt = MIDNIGHT, domainEndAt = MIDNIGHT + 24.hours, liveDomainMode = "rolling"),
            GraphPeriod.ONE_DAY
        ),
        rendered
    )

    private fun quarterChart(rendered: Set<String> = setOf(X, Z)) = GraphV2ChartModel(
        GraphPreparedBuilder.build(
            FreeGraph(null, listOf(quarterSeries(X, 1400.0), quarterSeries(Y, 1380.0), quarterSeries(Z, 1390.0)),
                domainStartAt = MIDNIGHT - 90.days, domainEndAt = MIDNIGHT, liveDomainMode = "fixed_start"),
            GraphPeriod.THREE_MONTHS
        ),
        rendered
    )

    private fun toggle(id: String, selected: Boolean, enabled: Boolean = true) =
        GraphV2SeriesToggle(id, GraphSeriesStyles.of(id, "label:$id"), selected, enabled, "krw")

    private fun state(
        content: GraphV2Content = GraphV2Content.READY,
        period: GraphPeriod = GraphPeriod.ONE_DAY,
        visible: Set<String> = setOf(X, Z),
        chart: GraphV2ChartModel? = if (content == GraphV2Content.READY) {
            if (period == GraphPeriod.ONE_DAY) dayChart(visible) else quarterChart(visible)
        } else null,
        status: GraphV2SelectionStatus = GraphV2SelectionStatus.READY,
        open: Boolean = false,
        screen: Long = 1L,
        gen: Long = 1L,
        fullscreenToken: GraphV2UiToken? = if (open) full(period, screen, gen) else null,
        inlineToken: GraphV2UiToken? = inline(period, screen),
        notice: GraphV2Notice? = null,
        refreshing: Boolean = false
    ) = GraphV2ScreenState(
        "usd", period, listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), content, status,
        if (status == GraphV2SelectionStatus.READY) GraphSeriesSelection(visible, setOf(X, Y, Z)) else null,
        listOf(X, Y, Z).map { toggle(it, status == GraphV2SelectionStatus.READY && it in visible, status == GraphV2SelectionStatus.READY) },
        chart, refreshing, null, notice, open, inlineToken, fullscreenToken
    )

    private class Recorder {
        val events = mutableListOf<Pair<String, GraphV2UiToken>>()
        val actions = GraphV2UiActions(
            selectPeriod = { t, p -> events += "period:${p.code}" to t },
            toggleSeries = { t, id -> events += "toggle:$id" to t },
            enterFullscreen = { t -> events += "expand" to t },
            exitFullscreen = { t -> events += "exit" to t },
            retrySelection = { t -> events += "retry" to t }
        )
    }

    /** The inline section and the fullscreen over it, as the screen host will place them; the fullscreen decides if it draws. */
    @Composable
    private fun Host(state: GraphV2ScreenState, actions: GraphV2UiActions) {
        Box(Modifier.fillMaxSize()) {
            GraphV2Section(state, actions)
            GraphV2Fullscreen(state, actions, Modifier.fillMaxSize())
        }
    }

    private inner class Mounted(initial: GraphV2ScreenState, restoration: StateRestorationTester? = null) {
        val recorder = Recorder()
        var current by mutableStateOf(initial)
        init {
            if (restoration != null) restoration.setContent { Host(current, recorder.actions) }
            else rule.setContent { Host(current, recorder.actions) }
            rule.waitForIdle()
        }
        fun show(next: GraphV2ScreenState) { current = next; rule.waitForIdle() }
        fun since(mark: Int) = recorder.events.drop(mark)
    }

    private fun node(surface: GraphV2Surface, name: String) = rule.onNode(hasTestTag(tag(surface, name)), useUnmergedTree = true)

    private fun exists(surface: GraphV2Surface, name: String) =
        rule.onAllNodes(hasTestTag(tag(surface, name)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** What the chart on [surface] says: its visible lines and observed extremes (GraphChart.kt :267). */
    private fun spoken(surface: GraphV2Surface): String =
        node(surface, "chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    private fun zoomIn(surface: GraphV2Surface) {
        node(surface, "chart").performTouchInput {
            val y = height / 2f
            pinch(
                start0 = Offset(width * 0.40f, y), end0 = Offset(width * 0.05f, y),
                start1 = Offset(width * 0.60f, y), end1 = Offset(width * 0.95f, y)
            )
        }
        rule.waitForIdle()
    }

    private fun periodTab(surface: GraphV2Surface, p: GraphPeriod) = rule.onNode(
        hasAnyAncestor(hasTestTag(tag(surface, "periods"))) and hasTestTag("period_tab:${p.code}"), useUnmergedTree = true
    )

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun backHeld(): Boolean {
        var held = false
        rule.runOnUiThread { held = rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        return held
    }

    private val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate)

    // --- 08: one selection, two charts --------------------------------------------------------------------------------

    /**
     * F01: both surfaces show the one supplied selection; a fullscreen click is reported with the fullscreen token and flips
     * nothing by itself; the next state changes both; closing reports the fullscreen token and the closed state removes it.
     */
    @Test fun F01_theSelectionIsSharedAndOnlyTheStateChangesIt() {
        val m = Mounted(state(open = true))
        for (s in listOf(IN, FS)) {
            node(s, "toggle:$X").assertIsOn()
            node(s, "toggle:$Y").assertIsOff()
        }
        val mark = m.recorder.events.size
        node(FS, "toggle:$Y").performClick()
        rule.waitForIdle()
        assertEquals(listOf("toggle:$Y" to full()), m.since(mark))
        node(FS, "toggle:$Y").assertIsOff()
        node(IN, "toggle:$Y").assertIsOff()
        m.show(state(open = true, visible = setOf(X, Y, Z)))
        node(FS, "toggle:$Y").assertIsOn()
        node(IN, "toggle:$Y").assertIsOn()
        node(FS, "close").performClick()
        rule.waitForIdle()
        assertEquals("exit", m.recorder.events.last().first)
        assertEquals(full(), m.recorder.events.last().second)
        m.show(state(visible = setOf(X, Y, Z)))
        assertFalse("the closed state removes the fullscreen", exists(FS, "root"))
        node(IN, "toggle:$Y").assertIsOn()
    }

    /**
     * F02: each chart has its own zoom. The fullscreen opens at the full window whatever the inline one shows, a pinch in it
     * leaves the inline zoom alone, the inline zoom is there after the trip, and a reopened fullscreen starts at the full
     * window again. A refresh, a notice, a rebuilt chart of the same answer and a pass through NO_SELECTION keep the zoom.
     */
    @Test fun F02_eachSurfaceOwnsItsZoom() {
        val m = Mounted(state())
        val fullWindow = spoken(IN)
        zoomIn(IN)
        val inlineZoomed = spoken(IN)
        assertNotEquals("premise: the pinch changed the window", fullWindow, inlineZoomed)
        m.show(state(open = true, gen = 1L))
        assertEquals("the fullscreen opens at the full window", fullWindow, spoken(FS))
        zoomIn(FS)
        val fullZoomed = spoken(FS)
        assertNotEquals("premise: the fullscreen pinch changed its window", fullWindow, fullZoomed)
        assertEquals("the fullscreen pinch left the inline zoom", inlineZoomed, spoken(IN))

        m.show(state(open = true, gen = 1L, refreshing = true, notice = GraphV2Notice.SAVE_NOT_COMMITTED))
        assertEquals("a refresh and a notice keep the zoom", fullZoomed, spoken(FS))
        m.show(state(open = true, gen = 1L, chart = dayChart()))
        assertEquals("a rebuilt chart of the same answer keeps the zoom", fullZoomed, spoken(FS))
        m.show(state(GraphV2Content.NO_SELECTION, open = true, gen = 1L, visible = emptySet()))
        m.show(state(open = true, gen = 1L))
        assertEquals("a pass through NO_SELECTION keeps the zoom", fullZoomed, spoken(FS))
        assertEquals(inlineZoomed, spoken(IN))

        m.show(state())
        assertFalse(exists(FS, "root"))
        assertEquals("the inline zoom survived the trip", inlineZoomed, spoken(IN))
        m.show(state(open = true, gen = 2L))
        assertEquals("a reopened fullscreen starts at the full window", fullWindow, spoken(FS))

        m.show(state())
        zoomIn(IN)
        assertNotEquals("premise", fullWindow, spoken(IN))
        m.show(state(GraphV2Content.BLOCKED, inlineToken = null))
        m.show(state())
        assertEquals("a token that went away and came back starts a fresh window", fullWindow, spoken(IN))
    }

    /**
     * F03: a period picked in the fullscreen is reported with the fullscreen token and does not close it; the new period's
     * state keeps the fullscreen; coming back to 1d, both charts start at the full window - neither surface kept a zoom per
     * period, and the hidden inline chart was reset too.
     */
    @Test fun F03_aPeriodChangeKeepsTheFullscreenAndResetsBothZooms() {
        val m = Mounted(state())
        val fullWindow = spoken(IN)
        zoomIn(IN)
        m.show(state(open = true, gen = 1L))
        zoomIn(FS)
        assertNotEquals("premise", fullWindow, spoken(FS))
        assertNotEquals("premise", fullWindow, spoken(IN))

        val mark = m.recorder.events.size
        periodTab(FS, GraphPeriod.THREE_MONTHS).performClick()
        rule.waitForIdle()
        assertEquals(listOf("period:3m" to full()), m.since(mark))
        m.show(state(period = GraphPeriod.THREE_MONTHS, open = true, screen = 2L, gen = 2L))
        assertTrue("the fullscreen stays", exists(FS, "root"))
        periodTab(FS, GraphPeriod.THREE_MONTHS).assertIsSelected()
        periodTab(FS, GraphPeriod.ONE_DAY).performClick()
        rule.waitForIdle()
        assertEquals(listOf("period:3m" to full(), "period:1d" to full(GraphPeriod.THREE_MONTHS, 2L, 2L)), m.since(mark))
        m.show(state(open = true, screen = 3L, gen = 3L))
        assertEquals("the fullscreen is back at the full window", fullWindow, spoken(FS))
        assertEquals("the inline chart under it was reset too", fullWindow, spoken(IN))
        assertTrue("no exit was reported", m.since(mark).none { it.first == "exit" })
    }

    /**
     * F04: in every content the close button, Back and a tap on the centre each report the open fullscreen's token exactly
     * once - the status area for a message, the chart itself on a period without zoom (no double-tap wait), and the chart's
     * accessibility activation on 1d. A toggle, a period or a retry is reported as itself and is never an exit.
     */
    @Test fun F04_everyWayOutReportsTheOpenFullscreenOnce() {
        val cases = listOf(
            state(GraphV2Content.LOADING, open = true),
            state(GraphV2Content.ERROR, open = true),
            state(GraphV2Content.NO_DATA, open = true),
            state(GraphV2Content.NO_SELECTION, open = true, visible = emptySet()),
            state(GraphV2Content.UNSUPPORTED, open = true),
            state(GraphV2Content.SELECTION_PENDING, status = GraphV2SelectionStatus.CONFIRMING, open = true),
            state(period = GraphPeriod.THREE_MONTHS, open = true),
            state(open = true)
        )
        val m = Mounted(cases.first())
        var gen = 10L
        for (case in cases) {
            fun open(): GraphV2ScreenState {
                gen += 1
                val next = case.copy(fullscreenToken = case.fullscreenToken!!.copy(surfaceGeneration = gen))
                m.show(next)
                assertTrue("${case.content} ${case.activePeriod}: drawn", exists(FS, "root"))
                return next
            }
            fun exitOnce(label: String, act: () -> Unit) {
                val shown = open()
                val mark = m.recorder.events.size
                act()
                rule.waitForIdle()
                assertEquals("${case.content} ${case.activePeriod} $label", listOf("exit" to shown.fullscreenToken!!), m.since(mark))
            }
            exitOnce("close") { node(FS, "close").performClick() }
            exitOnce("back") { back() }
            when {
                case.content != GraphV2Content.READY -> exitOnce("status tap") { node(FS, "status").performClick() }
                case.activePeriod != GraphPeriod.ONE_DAY -> exitOnce("chart tap") { node(FS, "chart").performTouchInput { click() } }
                else -> exitOnce("chart activation") { node(FS, "chart").performSemanticsAction(SemanticsActions.OnClick) }
            }
        }

        val readyShown = state(open = true, gen = 99L)
        m.show(readyShown)
        val mark = m.recorder.events.size
        node(FS, "toggle:$Y").performClick()
        periodTab(FS, GraphPeriod.THREE_MONTHS).performClick()
        rule.waitForIdle()
        assertEquals(listOf("toggle:$Y" to readyShown.fullscreenToken!!, "period:3m" to readyShown.fullscreenToken!!), m.since(mark))
        val unreadable = state(GraphV2Content.SELECTION_PENDING, status = GraphV2SelectionStatus.UNREADABLE, open = true, gen = 100L)
        m.show(unreadable)
        val retryMark = m.recorder.events.size
        node(FS, "selection_retry").performClick()
        rule.waitForIdle()
        assertEquals(listOf("retry" to unreadable.fullscreenToken!!), m.since(retryMark))
    }

    /**
     * F05: the fullscreen draws and holds Back exactly while the state has a fullscreen token; without one it draws nothing
     * and Back is left to the screen.
     */
    @Test fun F05_withoutAnOpenFullscreenNothingIsDrawnOrHeld() {
        val m = Mounted(state())
        assertFalse(exists(FS, "root"))
        assertFalse("closed: Back is not held", backHeld())
        m.show(state(open = true, fullscreenToken = null))
        assertFalse("open without a token: nothing", exists(FS, "root"))
        assertFalse(backHeld())
        m.show(state(GraphV2Content.INACTIVE, open = false, inlineToken = null))
        assertFalse(exists(FS, "root"))
        m.show(state(open = true))
        assertTrue(exists(FS, "root"))
        assertTrue("open: Back is held", backHeld())
        m.show(state())
        assertFalse(exists(FS, "root"))
        assertFalse("closed again: Back is released", backHeld())
    }

    /** F06: the fullscreen follows the inline display rules - pending toggles, the centre's words, the notice, the periods. */
    @Test fun F06_theFullscreenFollowsTheInlineDisplayRules() {
        val m = Mounted(state(GraphV2Content.SELECTION_PENDING, status = GraphV2SelectionStatus.CONFIRMING, open = true,
            notice = GraphV2Notice.SAVE_NOT_COMMITTED))
        node(FS, "toggle:$X").assert(indeterminate).assertIsNotEnabled()
        node(FS, "status_text").assertTextEquals(RECHECKING)
        node(FS, "notice").assertTextEquals(NOT_SAVED)
        periodTab(FS, GraphPeriod.ONE_DAY).assertIsSelected()
        val mark = m.recorder.events.size
        node(FS, "toggle:$X").performClick()
        rule.waitForIdle()
        assertEquals("a pending toggle reports nothing", emptyList<Pair<String, GraphV2UiToken>>(), m.since(mark))
    }

    /**
     * F07: emulated saved-state restoration keeps both zooms (the state, and so both tokens, are the same); a period change after it still
     * resets them.
     */
    @Test fun F07_savedStateRestorationKeepsBothZooms() {
        val restoration = StateRestorationTester(rule)
        val m = Mounted(state(), restoration)
        val fullWindow = spoken(IN)
        zoomIn(IN)
        val inlineZoomed = spoken(IN)
        m.show(state(open = true, gen = 1L))
        zoomIn(FS)
        val fullZoomed = spoken(FS)
        assertNotEquals("premise", fullWindow, inlineZoomed)
        assertNotEquals("premise", fullWindow, fullZoomed)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("the inline zoom survives saved-state restoration", inlineZoomed, spoken(IN))
        assertEquals("the fullscreen zoom survives saved-state restoration", fullZoomed, spoken(FS))
        m.show(state(period = GraphPeriod.THREE_MONTHS, open = true, screen = 2L, gen = 2L))
        m.show(state(open = true, screen = 3L, gen = 3L))
        assertEquals(fullWindow, spoken(FS))
        assertEquals(fullWindow, spoken(IN))
    }
}
