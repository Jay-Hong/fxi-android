package com.jay.fxi.ui.premium.graph

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.jay.fxi.ui.premium.view.PremiumFxGraphSlot
import com.jay.fxi.ui.premium.view.PremiumTopicScreen
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 C2b-2 contract r2 (instrumented, part 2): each graph surface tells the holder whether it is actually seen,
 * under the token it was drawn with, and hands the chart's right edge to the shared chart.
 *
 * Oracles: ANDROID_V2_PLAN.md :1328 (off-screen accumulates with no publish task; re-exposure flushes at once); C2b-1
 * contract r5 (the holder aggregates the inline report and the open fullscreen's report, refuses retired tokens, and needs a
 * fresh report after a block). Design: R4c/S4 c2_design_codex.r1 section 3 (effective visibility is the allowed context,
 * foreground, and an inline surface actually exposed or the fullscreen - not mount or tab selection alone, and not an inline
 * surface under the rate overlay; reported in status states too) and c2b2_design_codex.r1 section 2, rows N03-N10 reduced:
 *  - a drawn surface with a token installs, inside key(token) and outside the chart/status branches, one
 *    LifecycleStartEffect(token, shown) with `shown = hostExposed ∧ inViewport`; the effect reports (token, shown) on start
 *    and (token, false) on stop or dispose, so lifecycle >= STARTED is the effect's own part. inViewport comes from one
 *    onGloballyPositioned on the surface root - the whole section, status or chart - and is boundsInWindow() with positive
 *    width and height; it is false before the first measurement, and a zero area (out of view, or touching the viewport's
 *    edge) is false. A null token installs nothing; a token change or a surface that stops drawing ends the old report with
 *    false under the old token. Duplicate falses are allowed; a missing false for a token that left is not. The callback is
 *    the newest one passed; the actions object is not an effect key.
 *  - GraphV2UiActions gains a last `setSurfaceVisible: (GraphV2UiToken, Boolean) -> Unit` defaulting to nothing; GraphV2Section
 *    and GraphV2Fullscreen gain a last `hostExposed: Boolean = true`. PremiumTopicScreen passes the inline surface
 *    `hostExposed = overlay == NONE`; it composes the inline surface only on the selected FX page and the fullscreen only under
 *    the graph overlay, so neither needs another host term.
 *  - The surface hands `chart.rightEdgeNow` to GraphChart; its zoom stays keyed by the token, not by the right edge.
 *  - A new prepared graph, right edge, selection, notice or refresh under the same token and exposure reports nothing new.
 *
 * Fixtures: GraphV2SectionTest's token and state builders and PremiumTopicGraphSlotTest's host with a fake slot, with an
 * actions recorder; the chart series is GraphChartRightEdgeNowTest's (1000 + index every 10 minutes over a rolling day).
 * The exact strings assume the AVD's en-US locale. r2 (two-lens completeness check,
 * c2b2_contract.r1/completeness_workflow.result.json): H04b, H09-H12, an INACTIVE step in H02, an owner step in H08 (two
 * assertions that could not fail removed), and the header now fixes the host term, the viewport measurement and the effect
 * key. The implementation thread reads but does not edit this file.
 */
class GraphV2SurfaceVisibilityTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val a1 = AuthIdentityFence("A", 1L)
    private val owner = TopicDisplayOwner(a1, 1L)
    private val fence = TopicSessionFence(a1, "e1", TopicGrantToken(7L))
    private val lifetime = TopicUseLifetime(TopicGrantToken(7L), 3L)
    private fun binding(tab: String) = GraphSelectionBinding(a1, GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, tab), 1L)
    private fun token(
        tab: String = "usd",
        surface: GraphV2Surface = GraphV2Surface.INLINE,
        period: GraphPeriod = GraphPeriod.ONE_DAY,
        screen: Long = 1L,
        gen: Long = 0L
    ) = GraphV2UiToken(owner, fence, lifetime, binding(tab), tab, period, screen, surface, gen)

    private val midnight: Instant = Instant.parse("2026-10-05T15:00:00Z")

    private fun chart(tab: String = "usd", rightEdgeNow: Instant? = null, base: Double = 1000.0): GraphV2ChartModel {
        val id = "x.$tab"
        val graph = FreeGraph(
            "10min",
            listOf(FreeGraphSeries(id, (0..144).map { FreeGraphPoint(midnight + (it * 10).minutes, base + it, null, null) },
                label = "X", axisGroup = "krw")),
            domainStartAt = midnight, domainEndAt = midnight + 24.hours, liveDomainMode = "rolling"
        )
        return GraphV2ChartModel(GraphPreparedBuilder.build(graph, GraphPeriod.ONE_DAY), setOf(id), rightEdgeNow)
    }

    private fun state(
        content: GraphV2Content = GraphV2Content.READY,
        tab: String = "usd",
        inline: GraphV2UiToken? = token(tab),
        fullscreen: GraphV2UiToken? = null,
        chart: GraphV2ChartModel? = if (content == GraphV2Content.READY) chart(tab) else null,
        notice: GraphV2Notice? = null,
        refreshing: Boolean = false,
        visible: Set<String> = setOf("x.$tab")
    ): GraphV2ScreenState {
        val ready = content == GraphV2Content.READY
        return GraphV2ScreenState(
            tab, GraphPeriod.ONE_DAY, listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), content,
            if (ready) GraphV2SelectionStatus.READY else GraphV2SelectionStatus.UNBOUND,
            if (ready) GraphSeriesSelection(visible, setOf("x.$tab")) else null,
            if (ready) listOf(GraphV2SeriesToggle("x.$tab", GraphSeriesStyles.of("x.$tab", "X"), "x.$tab" in visible, true, "krw"))
            else emptyList(),
            chart, refreshing, null, notice, fullscreen != null, inline, fullscreen
        )
    }

    private val reports = mutableListOf<Pair<GraphV2UiToken, Boolean>>()
    private val actions = GraphV2UiActions(
        selectPeriod = { _, _ -> },
        toggleSeries = { _, _ -> },
        enterFullscreen = {},
        exitFullscreen = {},
        retrySelection = {},
        setSurfaceVisible = { tk, shown -> reports += tk to shown }
    )

    private fun last(token: GraphV2UiToken): Boolean? = reports.lastOrNull { it.first == token }?.second
    private fun shows(token: GraphV2UiToken): Int = reports.count { it.first == token && it.second }

    private fun spoken(tag: String) =
        rule.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    // --- a surface on its own -------------------------------------------------------------------------------------------

    /** H01: a status screen under a token reports it shown; changing between status and chart under that token reports nothing. */
    @Test fun H01_aStatusScreenIsReportedToo() {
        var s by mutableStateOf(state(GraphV2Content.LOADING))
        rule.setContent { GraphV2Section(s, actions) }
        rule.waitForIdle()
        val t = token()
        assertEquals(true, last(t))
        val count = reports.size
        s = state(GraphV2Content.READY)
        rule.waitForIdle()
        s = state(GraphV2Content.ERROR)
        rule.waitForIdle()
        s = state(GraphV2Content.NO_DATA)
        rule.waitForIdle()
        assertEquals("no report for a change of content", count, reports.size)
    }

    /**
     * H02: a token's report ends with false under that token when it changes or disappears; a null token reports nothing; each
     * new token reports once - inline and fullscreen alike.
     */
    @Test fun H02_eachTokenReportsAndEndsItsOwn() {
        val t1 = token()
        val t2 = token(period = GraphPeriod.THREE_MONTHS, screen = 2L)
        val t3 = token(screen = 3L)
        val f1 = token(surface = GraphV2Surface.FULLSCREEN, gen = 1L)
        val f2 = token(surface = GraphV2Surface.FULLSCREEN, screen = 2L, gen = 2L)
        var s by mutableStateOf(state(inline = t1, fullscreen = f1))
        rule.setContent {
            Column {
                GraphV2Section(s, actions)
                GraphV2Fullscreen(s, actions, Modifier.height(400.dp))
            }
        }
        rule.waitForIdle()
        assertEquals(true, last(t1))
        assertEquals(true, last(f1))
        s = state(inline = t2, fullscreen = f2)
        rule.waitForIdle()
        assertEquals(false, last(t1))
        assertEquals(false, last(f1))
        assertEquals(true, last(t2))
        assertEquals(true, last(f2))
        s = state(GraphV2Content.BLOCKED, inline = null, fullscreen = null)
        rule.waitForIdle()
        assertEquals(false, last(t2))
        assertEquals(false, last(f2))
        s = state(inline = t3)
        rule.waitForIdle()
        assertEquals(true, last(t3))
        s = state(GraphV2Content.INACTIVE, inline = t3)
        rule.waitForIdle()
        assertEquals("a surface that draws nothing is not shown", false, last(t3))
        for (t in listOf(t1, t2, t3, f1, f2)) assertEquals("$t", 1, shows(t))
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** H03: shown only from STARTED: nothing while CREATED, true on START, nothing more on RESUME or PAUSE, false on STOP, true again on START. */
    @Test fun H03_onlyAStartedSurfaceIsShown() {
        val life = TestOwner()
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.CREATED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides life) { GraphV2Section(state(), actions) }
        }
        rule.waitForIdle()
        val t = token()
        assertEquals("nothing shown while CREATED", 0, shows(t))
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.STARTED }
        rule.waitForIdle()
        assertEquals(true, last(t))
        val count = reports.size
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.STARTED }
        rule.waitForIdle()
        assertEquals("RESUMED and back to STARTED report nothing", count, reports.size)
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        assertEquals(false, last(t))
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.STARTED }
        rule.waitForIdle()
        assertEquals(true, last(t))
        assertEquals(2, shows(t))
    }

    /**
     * H04: below the viewport the surface is not shown; scrolled into view it is; scrolled back until its top only touches the
     * viewport's bottom edge (a zero area) it reports false.
     */
    @Test fun H04_onlyASurfaceInTheViewportIsShown() {
        rule.setContent {
            Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.fillMaxWidth().height(600.dp).testTag("above"))
                GraphV2Section(state(), actions)
                Spacer(Modifier.fillMaxWidth().height(600.dp))
            }
        }
        rule.waitForIdle()
        val t = token()
        assertEquals("not shown below the viewport", 0, shows(t))
        rule.onNodeWithTag(GraphV2UiTags.root(GraphV2Surface.INLINE), useUnmergedTree = true).performScrollTo()
        rule.waitForIdle()
        assertEquals(true, last(t))
        rule.onNodeWithTag("above").performScrollTo()
        rule.waitForIdle()
        assertEquals(false, last(t))
    }

    /**
     * H04b: with the chart scrolled away but the rest of the section on screen, the surface is shown - the whole section is
     * measured, and partly visible counts.
     */
    @Test fun H04b_theWholeSectionIsMeasuredNotTheChart() {
        val scroll = ScrollState(0)
        rule.setContent {
            Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(scroll)) {
                GraphV2Section(state(), actions)
                Spacer(Modifier.fillMaxWidth().height(600.dp))
            }
        }
        rule.waitForIdle()
        val t = token()
        assertEquals("a section partly in view is shown", true, last(t))
        fun bounds(tag: String) = rule.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val past = bounds(GraphV2UiTags.chart(GraphV2Surface.INLINE)).bottom - bounds(GraphV2UiTags.root(GraphV2Surface.INLINE)).top + 1f
        rule.runOnUiThread { scroll.dispatchRawDelta(past) }
        rule.waitForIdle()
        assertTrue("premise: the chart is off screen", bounds(GraphV2UiTags.chart(GraphV2Surface.INLINE)).height == 0f)
        assertTrue("premise: the periods are on screen", bounds(GraphV2UiTags.periods(GraphV2Surface.INLINE)).height > 0f)
        assertEquals("the section, not its chart, decides", true, last(t))
    }

    /**
     * H05: both surfaces hand the chart's right edge to the chart - read at 25:00 the drawn window starts at 00:50 (index 5);
     * with no right edge it starts before index 0.
     */
    @Test fun H05_bothSurfacesDrawAtTheChartsRightEdge() {
        var fullscreen by mutableStateOf(false)
        var s by mutableStateOf(state())
        rule.setContent {
            if (fullscreen) GraphV2Fullscreen(s, actions) else GraphV2Section(s, actions)
        }
        rule.waitForIdle()
        assertEquals("환율 추이 그래프. X. 최저 1000.00 최고 1144.00", spoken(GraphV2UiTags.chart(GraphV2Surface.INLINE)))
        s = state(chart = chart(rightEdgeNow = midnight + 25.hours))
        rule.waitForIdle()
        assertEquals("환율 추이 그래프. X. 최저 1005.00 최고 1144.00", spoken(GraphV2UiTags.chart(GraphV2Surface.INLINE)))
        s = state(fullscreen = token(surface = GraphV2Surface.FULLSCREEN, gen = 1L), chart = chart(rightEdgeNow = midnight + 25.hours))
        fullscreen = true
        rule.waitForIdle()
        assertEquals("환율 추이 그래프. X. 최저 1005.00 최고 1144.00", spoken(GraphV2UiTags.chart(GraphV2Surface.FULLSCREEN)))
    }

    /** H06: under the same token and exposure, a new chart, right edge, selection, notice or refresh reports nothing new. */
    @Test fun H06_newDataUnderTheSameTokenReportsNothing() {
        var s by mutableStateOf(state())
        rule.setContent { GraphV2Section(s, actions) }
        rule.waitForIdle()
        assertEquals(true, last(token()))
        val count = reports.size
        s = state(chart = chart(base = 1001.0))
        rule.waitForIdle()
        s = state(chart = chart(base = 1001.0, rightEdgeNow = midnight + 25.hours))
        rule.waitForIdle()
        s = state(chart = chart(base = 1001.0, rightEdgeNow = midnight + 25.hours), visible = emptySet())
        rule.waitForIdle()
        s = state(notice = GraphV2Notice.SAVE_NOT_COMMITTED, refreshing = true)
        rule.waitForIdle()
        assertEquals(count, reports.size)
    }

    // --- in the premium host --------------------------------------------------------------------------------------------

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val rates = TopicRates(dollarIndex = TopicDollarIndex(98.5, t, "investing")).merge(listOf(
        TopicQuote("kb", "usd-krw", 1390.0, t), TopicQuote("kb", "jpy-krw", 950.12, t), TopicQuote("kb", "eur-krw", 1600.0, t)
    ))
    private fun screen(tab: FreeTab) = PremiumTopicScreenState(
        PremiumTopicPresenter.present(TopicDisplayState(owner, rates, false, TopicConnectionDisplay.OPEN),
            OwnedTopicFocus(owner.identity, tab), owner.identity), null, null)

    private var hostState by mutableStateOf(PremiumTopicScreenState.NONE)
    private var hostSlot by mutableStateOf<PremiumFxGraphSlot?>(null)
    private val published = MutableStateFlow(state())
    private var fresh: GraphV2ScreenState = state()
    private fun publish(next: GraphV2ScreenState) {
        fresh = next
        published.value = next
        rule.waitForIdle()
    }

    private fun showHost(tab: FreeTab, initial: GraphV2ScreenState) {
        fresh = initial
        published.value = initial
        hostState = screen(tab)
        hostSlot = PremiumFxGraphSlot(owner, published, { fresh }, actions)
        rule.setContent {
            PremiumTopicScreen(
                state = hostState,
                onUserTabSelected = { _, _ -> },
                onRetryConnection = {},
                onRetryTopics = { _, _ -> },
                onApplyRows = { _, _, _, _, _, _ -> },
                onOpenSettings = {},
                newsContent = { Text("뉴스") },
                graphSlot = hostSlot
            )
        }
        rule.waitForIdle()
    }

    /**
     * H07: in the host the inline surface is shown with no overlay, not under the rate overlay nor under the graph overlay;
     * the graph overlay's fullscreen is shown while open and reports false when it closes, uncovering the inline surface.
     */
    @Test fun H07_theHostsOverlaysDecideWhatIsExposed() {
        val ti = token()
        val tf = token(surface = GraphV2Surface.FULLSCREEN, gen = 1L)
        showHost(FreeTab.USD, state(inline = ti))
        assertEquals(true, last(ti))
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("premise: the rate overlay is open", 1,
            rule.onAllNodes(hasTestTag(PremiumTopicTags.FULLSCREEN_LAYER), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("under the rate overlay", false, last(ti))
        publish(state(inline = ti, fullscreen = tf))
        assertEquals(true, last(tf))
        assertEquals("under the graph overlay", false, last(ti))
        publish(state(inline = ti))
        assertEquals(false, last(tf))
        assertEquals("uncovered", true, last(ti))
    }

    /**
     * H08: leaving the tab ends the old token's report and the new tab's surface reports its own; a slot of another owner and
     * removing the slot end it too.
     */
    @Test fun H08_leavingTheTabOrTheSlotEndsTheReport() {
        val usd = token("usd")
        val jpy = token("jpy")
        showHost(FreeTab.USD, state(inline = usd))
        assertEquals(true, last(usd))
        hostState = screen(FreeTab.JPY)
        rule.waitForIdle()
        assertEquals(false, last(usd))
        publish(state(tab = "jpy", inline = jpy))
        assertEquals(true, last(jpy))
        hostState = screen(FreeTab.TETHER)
        rule.waitForIdle()
        assertEquals(false, last(jpy))
        hostState = screen(FreeTab.JPY)
        rule.waitForIdle()
        assertEquals(true, last(jpy))
        hostSlot = PremiumFxGraphSlot(TopicDisplayOwner(a1, 2L), published, { fresh }, actions)
        rule.waitForIdle()
        assertEquals("another owner's slot ends the report", false, last(jpy))
        hostSlot = PremiumFxGraphSlot(owner, published, { fresh }, actions)
        rule.waitForIdle()
        assertEquals(true, last(jpy))
        hostSlot = null
        rule.waitForIdle()
        assertEquals(false, last(jpy))
        assertEquals(1, shows(usd))
    }

    // --- r2 rows ---------------------------------------------------------------------------------------------------------

    /** H09: a pinched zoom on either surface keeps its window when only the chart's right edge moves. */
    @Test fun H09_aNewRightEdgeKeepsEachSurfacesZoom() {
        var fullscreen by mutableStateOf(false)
        var s by mutableStateOf(state())
        rule.setContent { if (fullscreen) GraphV2Fullscreen(s, actions) else GraphV2Section(s, actions) }
        rule.waitForIdle()
        for ((surface, edge) in listOf(GraphV2Surface.INLINE to 25.hours, GraphV2Surface.FULLSCREEN to 26.hours)) {
            if (surface == GraphV2Surface.FULLSCREEN) {
                s = state(fullscreen = token(surface = GraphV2Surface.FULLSCREEN, gen = 1L))
                fullscreen = true
                rule.waitForIdle()
            }
            val tag = GraphV2UiTags.chart(surface)
            rule.onNode(hasTestTag(tag), useUnmergedTree = true).performTouchInput {
                val y = height / 2f
                pinch(Offset(width * 0.40f, y), Offset(width * 0.05f, y), Offset(width * 0.60f, y), Offset(width * 0.95f, y))
            }
            rule.waitForIdle()
            val zoomed = spoken(tag)
            assertNotEquals("premise: the pinch zoomed $surface", "환율 추이 그래프. X. 최저 1000.00 최고 1144.00", zoomed)
            s = s.copy(chart = chart(rightEdgeNow = midnight + edge))
            rule.waitForIdle()
            assertEquals("$surface keeps its zoom when only the right edge moves", zoomed, spoken(tag))
        }
    }

    /** H10: new actions under the same token report nothing; the next report goes through the newest callback. */
    @Test fun H10_newActionsAreNotAKeyAndTheNewestCallbackReports() {
        val later = mutableListOf<Pair<GraphV2UiToken, Boolean>>()
        var acts by mutableStateOf(actions)
        var s by mutableStateOf(state())
        rule.setContent { GraphV2Section(s, acts) }
        rule.waitForIdle()
        assertEquals(true, last(token()))
        val count = reports.size
        acts = actions.copy(setSurfaceVisible = { tk, shown -> later += tk to shown })
        rule.waitForIdle()
        assertEquals("new actions under the same token report nothing", count, reports.size)
        assertTrue(later.isEmpty())
        s = state(inline = token(screen = 2L))
        rule.waitForIdle()
        assertEquals("nothing goes to the old callback", count, reports.size)
        assertEquals(false, later.lastOrNull { it.first == token() }?.second)
        assertEquals(true, later.lastOrNull { it.first == token(screen = 2L) }?.second)
    }

    /** H11: hostExposed=false keeps both surfaces unshown under their tokens; true shows them; false again ends them. */
    @Test fun H11_hostExposedGatesBothSurfaces() {
        val ti = token()
        val tf = token(surface = GraphV2Surface.FULLSCREEN, gen = 1L)
        var exposed by mutableStateOf(false)
        rule.setContent {
            Column {
                GraphV2Section(state(inline = ti, fullscreen = tf), actions, hostExposed = exposed)
                GraphV2Fullscreen(state(inline = ti, fullscreen = tf), actions, Modifier.height(400.dp), hostExposed = exposed)
            }
        }
        rule.waitForIdle()
        assertEquals("inline not shown while the host covers it", 0, shows(ti))
        assertEquals("fullscreen not shown while the host covers it", 0, shows(tf))
        exposed = true
        rule.waitForIdle()
        assertEquals(true, last(ti))
        assertEquals(true, last(tf))
        exposed = false
        rule.waitForIdle()
        assertEquals(false, last(ti))
        assertEquals(false, last(tf))
    }

    /** H12: a surface removed while stopped stays ended when the owner starts again. */
    @Test fun H12_aSurfaceRemovedWhileStoppedReportsNoLateTrue() {
        val life = TestOwner()
        var mounted by mutableStateOf(true)
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.STARTED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides life) { if (mounted) GraphV2Section(state(), actions) }
        }
        rule.waitForIdle()
        val t = token()
        assertEquals(true, last(t))
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        mounted = false
        rule.waitForIdle()
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.STARTED }
        rule.waitForIdle()
        assertEquals(false, last(t))
        assertEquals(1, shows(t))
    }
}
