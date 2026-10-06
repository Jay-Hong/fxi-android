package com.jay.fxi.ui.premium.graph

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 C1-2a contract r2 (instrumented half): the inline premium graph section, drawn from a holder state and
 * reporting each click with the token of the state it drew.
 *
 * Oracles: iOS a36682f GraphV2Section.swift :308-380 (index column at the left, centred reference flow, the expand button kept
 * clear), :504-529 (centre states), :547 (period bar); Android S4 plan :1286 (catalog periods only). Design: R4c/S4
 * c12_design_codex.r1 §1-§2, c12a_api_codex.r1 §1-§3 (GraphV2Section(state, actions); tags graph_v2:inline:*; toggles are
 * Role.Checkbox with On/Off when the selection is READY and Indeterminate, disabled and without a check while it is not;
 * a toggle is enabled only for READY, its own enabled flag and a token; periods and expand are enabled by the token alone; the
 * UI never changes a selection itself - a click is reported and the next state decides; the centre and secondary areas follow
 * resolveGraphV2Status). The holder's counters (requests, saves, read-backs) are the JVM contract's; here mount and
 * recomposition are checked to report nothing. r2 (Codex's r1 review): I07a mounts once and changes the width through state,
 * under Density(1f) so both 320 and 600 fit the 1080px AVD, and asserts the width it measured. r3 (battery r1 survivors U05,
 * U13, U17, U18/U26, U21, U24, and U25 from Codex's review): I07a also keeps the expand button at its full 48dp inside the
 * section with an 80-character index label (iOS reserves the button's width, :304-314; Android measures the index column
 * first, so its width cap is what keeps the button); I08 - INACTIVE draws nothing; I07b - a READY toggle whose own flag is off is disabled, and a
 * confirmed toggle carries no custom state description; I07d - a pending toggle says "설정 확인 중" and shows no check even
 * when its own flag says selected; I07f - the retry is disabled without a token too.
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphV2SectionTest {

    @get:Rule val rule = createComposeRule()

    private companion object {
        val A = AuthIdentityFence("u1", 1L)
        val FENCE = TopicSessionFence(A, "e1", TopicGrantToken(7L))
        val OWNER = TopicDisplayOwner(A, 1L)
        val LIFETIME = TopicUseLifetime(TopicGrantToken(7L), 3L)
        val BINDING = GraphSelectionBinding(A, GraphSelectionKey("u1", GraphSelectionAudience.PREMIUM, "usd"), 1L)
        fun token(period: GraphPeriod = GraphPeriod.ONE_DAY, screen: Long = 1L) =
            GraphV2UiToken(OWNER, FENCE, LIFETIME, BINDING, "usd", period, screen, GraphV2Surface.INLINE, 0L)
        val T = token()

        const val X = "hana.usd"
        const val Y = "kb.usd"
        val MIDNIGHT: Instant = Instant.parse("2026-10-05T00:00:00Z")

        const val NO_DATA = "표시할 그래프 데이터가 없습니다"
        const val LOADING_FAILED = "그래프를 불러오지 못했습니다"
        const val NO_SELECTION = "표시할 항목을 선택해 주세요."
        const val UNSUPPORTED = "이 기간은 지원하지 않습니다."
        const val RECHECKING = "그래프 표시 설정을 다시 확인하고 있습니다."
        const val UNREADABLE = "그래프 표시 설정을 확인할 수 없습니다."
        const val NOT_SAVED = "그래프 표시 설정을 저장하지 못했습니다."

        fun tag(name: String) = "graph_v2:inline:$name"
        fun toggleTag(id: String) = tag("toggle:$id")
    }

    // --- fixture ------------------------------------------------------------------------------------------------------

    private fun series(id: String, base: Double, axis: String = "krw", points: Int = 30) = FreeGraphSeries(
        seriesId = id, label = "label:$id", axisGroup = axis,
        points = (0 until points).map { FreeGraphPoint(MIDNIGHT + (it * 10).minutes, base + (it % 7), null, null) }
    )

    private fun chart(vararg series: FreeGraphSeries, rendered: Set<String>) = GraphV2ChartModel(
        GraphPreparedBuilder.build(
            FreeGraph(null, series.toList(), domainStartAt = MIDNIGHT, domainEndAt = MIDNIGHT + 24.hours, liveDomainMode = "rolling"),
            GraphPeriod.ONE_DAY
        ),
        rendered
    )

    private fun toggle(id: String, selected: Boolean, enabled: Boolean = true, axis: String = "krw") =
        GraphV2SeriesToggle(id, GraphSeriesStyles.of(id, "label:$id"), selected, enabled, axis)

    private fun state(
        content: GraphV2Content,
        period: GraphPeriod = GraphPeriod.ONE_DAY,
        periods: List<GraphPeriod> = listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS),
        status: GraphV2SelectionStatus = GraphV2SelectionStatus.READY,
        selection: GraphSeriesSelection? = GraphSeriesSelection(setOf(X), setOf(X, Y)),
        toggles: List<GraphV2SeriesToggle> = listOf(toggle(X, true), toggle(Y, false)),
        chart: GraphV2ChartModel? = null,
        refreshing: Boolean = false,
        failure: Throwable? = null,
        notice: GraphV2Notice? = null,
        inline: GraphV2UiToken? = T
    ) = GraphV2ScreenState("usd", period, periods, content, status, selection, toggles, chart, refreshing, failure, notice,
        false, inline, null)

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

    private inner class Mounted(initial: GraphV2ScreenState, widthDp: Int = 360) {
        val recorder = Recorder()
        var current by mutableStateOf(initial)
        init {
            rule.setContent { GraphV2Section(current, recorder.actions, Modifier.width(widthDp.dp)) }
            rule.waitForIdle()
        }
        fun show(next: GraphV2ScreenState) { current = next; rule.waitForIdle() }
        fun click(tag: String) { rule.onNodeWithTag(tag, useUnmergedTree = true).performClick(); rule.waitForIdle() }
    }

    private fun node(tagName: String) = rule.onNodeWithTag(tagName, useUnmergedTree = true)

    private fun periodTags() = rule.onAllNodes(
        hasAnyAncestor(hasTestTag(tag("periods"))) and SemanticsMatcher("a period tab") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("period_tab:") == true
        }, useUnmergedTree = true
    ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    private fun period(p: GraphPeriod) = rule.onNode(
        hasAnyAncestor(hasTestTag(tag("periods"))) and hasTestTag("period_tab:${p.code}"), useUnmergedTree = true
    )

    private fun toggleTagsUnder(container: String) = rule.onAllNodes(
        hasAnyAncestor(hasTestTag(tag(container))) and SemanticsMatcher("a toggle") {
            val t = it.config.getOrNull(SemanticsProperties.TestTag) ?: return@SemanticsMatcher false
            t.startsWith(tag("toggle:")) && !t.endsWith(":check")
        }, useUnmergedTree = true
    ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix(tag("toggle:")) }

    private val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate)

    // --- 01 / 02: periods and what the centre shows -------------------------------------------------------------------

    /** I01: the state's periods only; the active one is selected and enabled; a click on it reports nothing, on another once. */
    @Test fun I01_theStatesPeriodsAreDrawnAndAClickIsReported() {
        val m = Mounted(state(GraphV2Content.READY, chart = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(X))))
        assertEquals(listOf("period_tab:1d", "period_tab:3m"), periodTags())
        period(GraphPeriod.ONE_DAY).assertIsSelected()
        period(GraphPeriod.ONE_DAY).assertIsEnabled()
        assertEquals("mount reports nothing", emptyList<Pair<String, GraphV2UiToken>>(), m.recorder.events)
        period(GraphPeriod.ONE_DAY).performClick(); rule.waitForIdle()
        assertEquals(emptyList<Pair<String, GraphV2UiToken>>(), m.recorder.events)
        period(GraphPeriod.THREE_MONTHS).performClick(); rule.waitForIdle()
        assertEquals(listOf("period:3m" to T), m.recorder.events)
    }

    /**
     * I01b: four periods when the state has four, none when it has none; an unsupported active period is not reselected - its
     * message shows, no other period is selected and no chart is drawn. Recomposition reports nothing.
     */
    @Test fun I01b_thePeriodListIsTakenAsGiven() {
        val m = Mounted(state(GraphV2Content.LOADING, periods = GraphPeriod.entries.toList(), toggles = emptyList()))
        assertEquals(GraphPeriod.entries.map { "period_tab:${it.code}" }, periodTags())
        m.show(state(GraphV2Content.UNSUPPORTED, periods = emptyList(), toggles = emptyList()))
        assertEquals(emptyList<String>(), periodTags())
        m.show(state(GraphV2Content.UNSUPPORTED, period = GraphPeriod.THREE_MONTHS, periods = listOf(GraphPeriod.ONE_DAY),
            toggles = emptyList(), inline = token(GraphPeriod.THREE_MONTHS)))
        node(tag("status_text")).assertTextEquals(UNSUPPORTED)
        period(GraphPeriod.ONE_DAY).assertIsNotSelected()
        node(tag("chart")).assertDoesNotExist()
        assertEquals(emptyList<Pair<String, GraphV2UiToken>>(), m.recorder.events)
    }

    /** I02: LOADING is a bare spinner and no chart; READY draws the chart; ERROR says so with no chart. */
    @Test fun I02_theCentreFollowsTheContent() {
        val m = Mounted(state(GraphV2Content.LOADING, period = GraphPeriod.THREE_MONTHS, toggles = emptyList(),
            inline = token(GraphPeriod.THREE_MONTHS)))
        node(tag("status_spinner")).assertExists()
        node(tag("status_text")).assertDoesNotExist()
        node(tag("chart")).assertDoesNotExist()
        period(GraphPeriod.THREE_MONTHS).assertIsSelected()
        m.show(state(GraphV2Content.READY, chart = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(X))))
        node(tag("chart")).assertExists()
        node(tag("status")).assertDoesNotExist()
        m.show(state(GraphV2Content.ERROR, toggles = emptyList(), failure = IOException("HTTP 500")))
        node(tag("status_text")).assertTextEquals(LOADING_FAILED)
        node(tag("chart")).assertDoesNotExist()
    }

    /** I02r: a refresh over a chart keeps the chart and shows the secondary spinner and failure - together when both are set. */
    @Test fun I02r_aRefreshKeepsTheChartAndSaysSoBeside() {
        val drawn = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(X))
        val m = Mounted(state(GraphV2Content.READY, chart = drawn, refreshing = true))
        node(tag("chart")).assertExists()
        node(tag("refreshing")).assertExists()
        node(tag("request_failure")).assertDoesNotExist()
        node(tag("status")).assertDoesNotExist()
        m.show(state(GraphV2Content.READY, chart = drawn, failure = IOException("HTTP 500 body")))
        node(tag("chart")).assertExists()
        node(tag("request_failure")).assertTextEquals(LOADING_FAILED)
        node(tag("refreshing")).assertDoesNotExist()
        m.show(state(GraphV2Content.READY, chart = drawn, failure = IOException("again"), refreshing = true))
        node(tag("request_failure")).assertExists()
        node(tag("refreshing")).assertExists()
    }

    // --- 05: the empty states -----------------------------------------------------------------------------------------

    /** I05: NO_DATA says so and draws nothing even with a chart model; two points draw; NO_SELECTION asks for a choice. */
    @Test fun I05_eachEmptyStateHasItsOwnWords() {
        val empty = chart(series(X, 1400.0, points = 0), series(Y, 1380.0), rendered = setOf(X))
        val m = Mounted(state(GraphV2Content.NO_DATA, chart = empty))
        node(tag("status_text")).assertTextEquals(NO_DATA)
        node(tag("chart")).assertDoesNotExist()
        m.show(state(GraphV2Content.READY, chart = chart(series(X, 1400.0, points = 2), rendered = setOf(X)),
            toggles = listOf(toggle(X, true))))
        node(tag("chart")).assertExists()
        m.show(state(GraphV2Content.NO_SELECTION, selection = GraphSeriesSelection(emptySet(), setOf(X, Y)),
            chart = chart(series(X, 1400.0), series(Y, 1380.0), rendered = emptySet()),
            toggles = listOf(toggle(X, false), toggle(Y, false))))
        node(tag("status_text")).assertTextEquals(NO_SELECTION)
        node(tag("chart")).assertDoesNotExist()
    }

    // --- 06: the UI never decides a selection --------------------------------------------------------------------------

    /**
     * I06: a toggle click is reported with the drawn token and changes nothing on screen; only the next state turns it off;
     * a confirmed all-off is enabled and Off, never Indeterminate; swapping states reports nothing.
     */
    @Test fun I06_aClickIsReportedAndTheStateDecides() {
        val drawn = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(X))
        val m = Mounted(state(GraphV2Content.READY, chart = drawn))
        node(toggleTag(X)).assertIsOn()
        m.click(toggleTag(X))
        assertEquals(listOf("toggle:$X" to T), m.recorder.events)
        node(toggleTag(X)).assertIsOn()
        m.show(state(GraphV2Content.NO_SELECTION, selection = GraphSeriesSelection(emptySet(), setOf(X, Y)),
            chart = chart(series(X, 1400.0), series(Y, 1380.0), rendered = emptySet()),
            toggles = listOf(toggle(X, false), toggle(Y, false))))
        node(toggleTag(X)).assertIsOff()
        node(toggleTag(X)).assertIsEnabled()
        node(toggleTag(Y)).assertIsOff()
        node(tag("status_text")).assertTextEquals(NO_SELECTION)
        assertEquals("the swap reported nothing", 1, m.recorder.events.size)
    }

    // --- 07: toggles ------------------------------------------------------------------------------------------------------

    /**
     * I07a: every toggle is drawn once - index ones in the left column in input order, the rest in the reference flow by the
     * exact-id priority then input order - at a narrow and a wide width, with the expand button clear of every toggle.
     */
    @Test fun I07a_togglesAreLaidOutByAxisAndPriorityAndClearOfTheButton() {
        val toggles = listOf(
            toggle("newbank.usd", false), toggle("hana.usd", true),
            // A long index label: the index column is measured before the button, so only its own cap keeps the button.
            toggle("vix", false, axis = "index").copy(style = GraphSeriesStyles.of("vix", "V".repeat(80))),
            toggle("kb.usd", false), toggle("dxy", true, axis = "index"), toggle("krx.usd-krw-futures", false),
            toggle("investing.usd", true)
        )
        val shown = state(GraphV2Content.LOADING, toggles = toggles, selection = GraphSeriesSelection(
            setOf("hana.usd", "dxy", "investing.usd"), toggles.map { it.seriesId }.toSet()))
        val recorder = Recorder()
        var widthDp by mutableStateOf(320)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                GraphV2Section(shown, recorder.actions, Modifier.width(widthDp.dp))
            }
        }
        for (width in listOf(320, 600)) {
            widthDp = width
            rule.waitForIdle()
            assertEquals("$width actual width", width.toFloat(),
                node(tag("root")).fetchSemanticsNode().boundsInRoot.width, 1f)
            assertEquals("$width index", listOf("vix", "dxy"), toggleTagsUnder("indices"))
            assertEquals("$width references",
                listOf("krx.usd-krw-futures", "investing.usd", "kb.usd", "hana.usd", "newbank.usd"), toggleTagsUnder("references"))
            val button = node(tag("expand")).fetchSemanticsNode().boundsInRoot
            val root = node(tag("root")).fetchSemanticsNode().boundsInRoot
            assertEquals("$width: the expand button keeps its width", 48f, button.width, 1f)
            assertTrue("$width: the expand button stays inside the section", button.right <= root.right + 1f && button.left >= root.left)
            toggles.forEach {
                val bounds = node(toggleTag(it.seriesId)).fetchSemanticsNode().boundsInRoot
                assertFalse("$width: ${it.seriesId} overlaps the expand button", bounds.overlaps(button))
            }
            assertEquals(emptyList<Pair<String, GraphV2UiToken>>(), recorder.events)
        }
    }

    /** I07b: two clicks while a save is pending are both reported in order; the screen keeps the confirmed choice and stays enabled. */
    @Test fun I07b_clicksDuringASaveAreReportedInOrder() {
        val drawn = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(X))
        val m = Mounted(state(GraphV2Content.READY, chart = drawn))
        m.click(toggleTag(X))
        m.click(toggleTag(Y))
        assertEquals(listOf("toggle:$X" to T, "toggle:$Y" to T), m.recorder.events)
        node(toggleTag(X)).assertIsOn()
        node(toggleTag(Y)).assertIsOff()
        node(toggleTag(X)).assertIsEnabled()
        node(toggleTag(Y)).assertIsEnabled()
        node(toggleTag(X)).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        m.show(state(GraphV2Content.READY, chart = drawn, toggles = listOf(toggle(X, true), toggle(Y, false, enabled = false))))
        node(toggleTag(Y)).assertIsNotEnabled()
        m.click(toggleTag(Y))
        assertEquals("a toggle whose own flag is off reports nothing", listOf("toggle:$X" to T, "toggle:$Y" to T), m.recorder.events)
        m.show(state(GraphV2Content.READY, selection = GraphSeriesSelection(setOf(Y), setOf(X, Y)),
            chart = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(Y)),
            toggles = listOf(toggle(X, false), toggle(Y, true))))
        node(toggleTag(X)).assertIsOff()
        node(toggleTag(Y)).assertIsOn()
    }

    /** I07c: a save that did not commit shows the confirmed choice and the notice; the notice goes when the state drops it. */
    @Test fun I07c_aSaveThatDidNotCommitSaysSo() {
        val drawn = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(X))
        val m = Mounted(state(GraphV2Content.READY, chart = drawn, notice = GraphV2Notice.SAVE_NOT_COMMITTED))
        node(toggleTag(X)).assertIsOn()
        node(tag("notice")).assertTextEquals(NOT_SAVED)
        m.show(state(GraphV2Content.READY, chart = drawn))
        node(tag("notice")).assertDoesNotExist()
        assertEquals(emptyList<Pair<String, GraphV2UiToken>>(), m.recorder.events)
    }

    /**
     * I07d: while the selection is being confirmed the toggles are Indeterminate, disabled and unchecked and a click reports
     * nothing; unreadable offers a retry that reports once with the drawn token; expand still reports, separately.
     */
    @Test fun I07d_aPendingSelectionIsUnknownAndOffersOnlyARetry() {
        val pendingToggles = listOf(toggle(X, false, enabled = false), toggle(Y, false, enabled = false))
        val m = Mounted(state(GraphV2Content.SELECTION_PENDING, status = GraphV2SelectionStatus.CONFIRMING, selection = null,
            toggles = listOf(toggle(X, true, enabled = false), toggle(Y, false, enabled = false))))
        listOf(X, Y).forEach {
            node(toggleTag(it)).assert(indeterminate)
            node(toggleTag(it)).assertIsNotEnabled()
            node(toggleTag(it)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "설정 확인 중"))
            node("${toggleTag(it)}:check").assertDoesNotExist()
        }
        node(tag("status_text")).assertTextEquals(RECHECKING)
        node(tag("status_spinner")).assertExists()
        node(tag("chart")).assertDoesNotExist()
        m.click(toggleTag(X))
        assertEquals(emptyList<Pair<String, GraphV2UiToken>>(), m.recorder.events)
        m.show(state(GraphV2Content.SELECTION_PENDING, status = GraphV2SelectionStatus.UNREADABLE, selection = null,
            toggles = pendingToggles))
        node(tag("status_text")).assertTextEquals(UNREADABLE)
        m.click(tag("selection_retry"))
        assertEquals(listOf("retry" to T), m.recorder.events)
        m.click(tag("expand"))
        assertEquals(listOf("retry" to T, "expand" to T), m.recorder.events)
    }

    /** I07e: an initialize that did not commit is a pending selection that says the save failed and offers the retry. */
    @Test fun I07e_aFailedInitializeOffersTheRetry() {
        val m = Mounted(state(GraphV2Content.SELECTION_PENDING, status = GraphV2SelectionStatus.INITIALIZING, selection = null,
            toggles = listOf(toggle(X, false, enabled = false)), notice = GraphV2Notice.SAVE_NOT_COMMITTED))
        node(tag("status_text")).assertTextEquals(NOT_SAVED)
        node(tag("status_spinner")).assertDoesNotExist()
        node(tag("notice")).assertDoesNotExist()
        m.click(tag("selection_retry"))
        assertEquals(listOf("retry" to T), m.recorder.events)
    }

    /** I07f: without a token nothing is enabled - periods, toggles, expand - and nothing is reported. */
    @Test fun I07f_withoutATokenNothingReports() {
        val m = Mounted(state(GraphV2Content.READY, chart = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(X)),
            inline = null))
        period(GraphPeriod.THREE_MONTHS).assertIsNotEnabled()
        node(toggleTag(X)).assertIsNotEnabled()
        node(tag("expand")).assertIsNotEnabled()
        period(GraphPeriod.THREE_MONTHS).performClick()
        node(toggleTag(Y)).performClick()
        rule.waitForIdle()
        assertTrue(m.recorder.events.isEmpty())
        m.show(state(GraphV2Content.SELECTION_PENDING, status = GraphV2SelectionStatus.UNREADABLE, selection = null,
            toggles = listOf(toggle(X, false, enabled = false)), inline = null))
        node(tag("selection_retry")).assertIsNotEnabled()
        node(tag("selection_retry")).performClick()
        rule.waitForIdle()
        assertTrue("the retry without a token reports nothing", m.recorder.events.isEmpty())
    }

    // --- 08: an inactive holder -----------------------------------------------------------------------------------------

    /** I08: INACTIVE draws no section at all - no toggles, periods or expand - and reports nothing. */
    @Test fun I08_anInactiveStateDrawsNothing() {
        val m = Mounted(state(GraphV2Content.INACTIVE, periods = emptyList(), status = GraphV2SelectionStatus.UNBOUND,
            selection = null, toggles = emptyList(), inline = null))
        node(tag("root")).assertDoesNotExist()
        node(tag("expand")).assertDoesNotExist()
        node(tag("periods")).assertDoesNotExist()
        m.show(state(GraphV2Content.READY, chart = chart(series(X, 1400.0), series(Y, 1380.0), rendered = setOf(X))))
        node(tag("root")).assertExists()
        assertTrue(m.recorder.events.isEmpty())
    }
}
