package com.jay.fxi

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.graph.GraphSelectionBinding
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphSeriesStyles
import com.jay.fxi.ui.premium.PremiumTopicPresenter
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import com.jay.fxi.ui.premium.graph.GraphV2ChartModel
import com.jay.fxi.ui.premium.graph.GraphV2Content
import com.jay.fxi.ui.premium.graph.GraphV2ScreenState
import com.jay.fxi.ui.premium.graph.GraphV2SelectionStatus
import com.jay.fxi.ui.premium.graph.GraphV2SeriesToggle
import com.jay.fxi.ui.premium.graph.GraphV2Surface
import com.jay.fxi.ui.premium.graph.GraphV2UiActions
import com.jay.fxi.ui.premium.graph.GraphV2UiToken
import com.jay.fxi.ui.premium.view.PremiumFxGraphSlot
import com.jay.fxi.ui.premium.view.PremiumTopicScreen
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * S5-4 C (instrumented): the resource-id bridge the tether benchmark's UIAutomator selectors rely on, over the real premium
 * topic screen with a fake graph slot (agreed plan R4c/S5 s5_4c_plan.r2.md). The host root turns testTagsAsResourceId on, as
 * the benchmark fixture root will (S5-4 E); the production screen itself does not.
 *
 * Selectors are the benchmark judge's (tools/performance/benchmark_gate.py EXPECTED_BEHAVIOR). This proves the selectors and the
 * page scroll on an independent host; it does not prove the ON MainActivity entry or performance.
 */
class PremiumTopicResourceBridgeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val page = PremiumTopicTags.PAGE_SCROLL
    private val tetherTab = PremiumTopicTags.tab(FreeTab.TETHER)
    private val chart = "graph_v2:inline:chart"
    private val periods = "graph_v2:inline:periods"

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val rates = TopicRates(dollarIndex = TopicDollarIndex(98.5, t, "investing")).merge(listOf(
        TopicQuote("kb", "usd-krw", 1390.0, t), TopicQuote("hana", "usd-krw", 1391.0, t), TopicQuote("upbit", "usdt-krw", 1400.0, t)))
    private fun screen(tab: FreeTab) = PremiumTopicScreenState(
        PremiumTopicPresenter.present(TopicDisplayState(o1, rates, false, TopicConnectionDisplay.OPEN),
            OwnedTopicFocus(a1, tab), a1), null, null)

    private val midnight: Instant = Instant.parse("2026-10-05T00:00:00Z")
    private val fence = TopicSessionFence(a1, "e1", TopicGrantToken(7L))
    private val lifetime = TopicUseLifetime(TopicGrantToken(7L), 3L)
    private val inlineToken = GraphV2UiToken(o1, fence, lifetime,
        GraphSelectionBinding(a1, GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, "tether"), 1L),
        "tether", GraphPeriod.ONE_DAY, 1L, GraphV2Surface.INLINE, 0L)

    /** A ready tether graph offering one day and one week, with two series. */
    private fun graph(): GraphV2ScreenState {
        val ids = listOf("upbit.usdt-krw", "bithumb.usdt-krw")
        val series = ids.mapIndexed { i, id -> FreeGraphSeries(seriesId = id, label = "label:$id", axisGroup = "krw",
            points = (0 until 144).map { FreeGraphPoint(midnight + (it * 10).minutes, 1400.0 - i * 10 + it * 0.5, null, null) }) }
        val chartModel = GraphV2ChartModel(GraphPreparedBuilder.build(FreeGraph(null, series, domainStartAt = midnight,
            domainEndAt = midnight + 24.hours, liveDomainMode = "rolling"), GraphPeriod.ONE_DAY), ids.toSet())
        return GraphV2ScreenState("tether", GraphPeriod.ONE_DAY, listOf(GraphPeriod.ONE_DAY, GraphPeriod.ONE_WEEK),
            GraphV2Content.READY, GraphV2SelectionStatus.READY, GraphSeriesSelection(ids.toSet(), ids.toSet()),
            ids.map { GraphV2SeriesToggle(it, GraphSeriesStyles.of(it, "label:$it"), true, true, "krw") },
            chartModel, false, null, null, false, inlineToken, null)
    }

    private val events = mutableListOf<Pair<String, GraphV2UiToken>>()
    private fun slot(state: GraphV2ScreenState) = PremiumFxGraphSlot(
        owner = o1, state = MutableStateFlow(state), currentState = { state },
        actions = GraphV2UiActions(
            selectPeriod = { tk, p -> events += "period:${p.code}" to tk },
            toggleSeries = { _, _ -> }, enterFullscreen = {}, exitFullscreen = {}, retrySelection = {}
        )
    )

    /** Room below the rates so the page has somewhere to scroll. */
    private val filler: @Composable (FreeTab) -> Unit = { _ ->
        Column { Spacer(Modifier.height(1500.dp)); Text("end") }
    }

    private var graphSlot by mutableStateOf<PremiumFxGraphSlot?>(null)
    private var below by mutableStateOf<(@Composable (FreeTab) -> Unit)?>(null)

    /** Sets the content once (a compose rule allows one setContent per test); [update] changes the slot and the filler after. */
    private fun show(initialSlot: PremiumFxGraphSlot?, initialBelow: (@Composable (FreeTab) -> Unit)?) {
        graphSlot = initialSlot
        below = initialBelow
        rule.setContent {
            Box(Modifier.semantics { testTagsAsResourceId = true }) {
                PremiumTopicScreen(
                    state = screen(FreeTab.TETHER),
                    onUserTabSelected = { _, _ -> },
                    onRetryConnection = {},
                    onRetryTopics = { _, _ -> },
                    onApplyRows = { _, _, _, _, _, _ -> },
                    onOpenSettings = {},
                    newsContent = { _ -> },
                    graphSlot = graphSlot,
                    belowRates = below
                )
            }
        }
        rule.waitForIdle()
        assertTrue("the screen reached UIAutomator", device.wait(Until.hasObject(By.res(tetherTab)), 5_000))
    }

    private fun update(nextSlot: PremiumFxGraphSlot?, nextBelow: (@Composable (FreeTab) -> Unit)?) {
        graphSlot = nextSlot
        below = nextBelow
        rule.waitForIdle()
    }

    private fun found(tag: String) = device.findObjects(By.res(tag))
    private fun headingY() = rule.onNode(hasTestTag(PremiumTopicTags.HEADING), useUnmergedTree = true)
        .fetchSemanticsNode().positionInRoot.y

    /**
     * R1: the page container, the tether tab and the inline chart are each found exactly once by resource id; the chart is
     * inside the page container; the one-day period tab is found once under the inline periods and once on the whole screen.
     */
    @Test fun R1_eachSelectorIsFoundOnceAndThePageHoldsTheChart() {
        show(slot(graph()), filler)
        for (tag in listOf(page, tetherTab, chart)) assertEquals("$tag lookups", 1, found(tag).size)
        assertEquals("the chart inside the page container", 1, found(page).single().findObjects(By.res(chart)).size)
        assertEquals("one day under the inline periods", 1, found(periods).single().findObjects(By.res("period_tab:1d")).size)
        assertEquals("one day on the whole screen", 1, found("period_tab:1d").size)
    }

    /** R2: a UIAutomator click on the inline one-week tab selects one week once, with the inline token. */
    @Test fun R2_theInlinePeriodTabTakesAUiAutomatorClick() {
        show(slot(graph()), filler)
        found(periods).single().findObject(By.res("period_tab:1w")).click()
        rule.waitForIdle()
        assertEquals(listOf("period:1w" to inlineToken), events)
    }

    /**
     * R3: a UIAutomator scroll on the page container moves the page - the heading inside it moves up (unclipped position) - and
     * scrolling back returns it to where it started.
     */
    @Test fun R3_thePageScrollsDownAndBackThroughItsContainer() {
        show(slot(graph()), filler)
        val top = headingY()
        found(page).single().scroll(Direction.DOWN, 0.8f)
        rule.waitForIdle()
        val moved = headingY()
        assertTrue("the page moved down: heading $top -> $moved", moved < top)
        var gestures = 0
        while (found(page).single().scroll(Direction.UP, 1.0f) && gestures < 5) gestures++
        rule.waitForIdle()
        assertEquals("the page came back", top, headingY(), 1f)
    }

    /** R4: without a graph or a slot below the rates the page does not scroll as one and has no page tag; nor has the rate fullscreen. */
    @Test fun R4_noPageTagWithoutAPageScrollOrInTheRateFullscreen() {
        show(null, null)
        assertEquals("no page scroll, no tag", 0, found(page).size)

        update(slot(graph()), filler)
        assertTrue("premise: the page tag with a graph", device.wait(Until.hasObject(By.res(page)), 5_000))
        assertEquals("premise: once", 1, found(page).size)
        rule.onNode(hasTestTag(PremiumTopicTags.FULLSCREEN), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("none in the rate fullscreen layer", 0, rule.onAllNodes(
            hasTestTag(page) and hasAnyAncestor(hasTestTag(PremiumTopicTags.FULLSCREEN_LAYER)), useUnmergedTree = true
        ).fetchSemanticsNodes().size)
    }
}
