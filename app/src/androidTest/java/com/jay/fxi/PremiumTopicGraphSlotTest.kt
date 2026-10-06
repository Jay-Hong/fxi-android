package com.jay.fxi

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 C1-3a contract r1 (instrumented): the premium topic screen with a premium FX graph slot - where the graph is
 * drawn, which state it draws, and that only one fullscreen is ever open over a screen nobody can reach under it. r2 (Codex's
 * r1 review): S6 taps the first tab's margin (USD is the third tab, inside the graph root) and asserts that point is over a
 * tab, inside the layer and outside the graph root before tapping. S5 is the adapter's answer to a graph fullscreen published
 * over the rate fullscreen; it does not stand in for the expand guard. r4: S6 reads readability from the merged tree - the
 * unmerged tree keeps the descendants clearAndSetSemantics replaces (measured: the r3 count was 1 under an open layer). r5
 * (battery survivor X02): S2 also hands the tether tab a graph of its own tab, which the tab match alone would accept. r6
 * (Codex's r5 review of survivor X09): S7 - a pager drag already under way when the graph fullscreen opens keeps its first
 * hit path, so the layer cannot stop it; only the disabled pager does.
 *
 * Oracles: iOS a36682f CurrencyTabView.swift :51-57 (the graph above the rates on the FX tabs only; tether and news have none),
 * GraphV2Section.swift :287 (fullScreenCover - the inline chart and its zoom stay alive under the cover, which nothing under it
 * can be touched or read through). Design: R4c/S4 c1_design_codex.r1 §4 C1-3 (rows 11·12; no PremiumTopicRoute wiring),
 * c13_design_codex.r1 §1-§3 as reviewed by c13_review_claude.r1 (PremiumFxGraphSlot(owner, state, currentState, actions); the
 * screen observes state and draws what currentState() returns at render; accepted only for the screen's owner, an explicit FX
 * tab whose serverTab is the graph's tab, and tokens of that owner and tab; overlay NONE/RATES/GRAPH with GRAPH from the fresh
 * fullscreen token; the graph overlay inside a non-clickable Material3 Surface tagged PremiumTopicTags.GRAPH_LAYER; the body
 * under any fullscreen cleared from semantics; the pager scrolls only with no overlay and no sheet). The holder's own access,
 * late-result and KRX contracts are the JVM GraphV2ScreenStateHolderTest's; here the slot is a fake whose state the test sets.
 *
 * The implementation thread reads but does not edit this file.
 */
class PremiumTopicGraphSlotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val o2 = TopicDisplayOwner(a1, 2L)
    private fun q(source: String, asset: String, rate: Double) = TopicQuote(source, asset, rate, t)
    private val rates = TopicRates(dollarIndex = TopicDollarIndex(98.5, t, "investing")).merge(listOf(
        q("kb", "usd-krw", 1390.0), q("hana", "usd-krw", 1391.0), q("kb", "jpy-krw", 950.12), q("kb", "eur-krw", 1600.0),
        q("upbit", "usdt-krw", 1400.0)))
    private fun screen(tab: FreeTab, owner: TopicDisplayOwner = o1) = PremiumTopicScreenState(
        PremiumTopicPresenter.present(TopicDisplayState(owner, rates, false, TopicConnectionDisplay.OPEN),
            OwnedTopicFocus(owner.identity, tab), owner.identity), null, null)

    // --- the graph fixture ----------------------------------------------------------------------------------------------

    private val midnight: Instant = Instant.parse("2026-10-05T00:00:00Z")
    private val fence = TopicSessionFence(a1, "e1", TopicGrantToken(7L))
    private val lifetime = TopicUseLifetime(TopicGrantToken(7L), 3L)
    private fun binding(tab: String) = GraphSelectionBinding(a1, GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, tab), 1L)
    private fun token(tab: String, surface: GraphV2Surface, owner: TopicDisplayOwner = o1, gen: Long = 0L) =
        GraphV2UiToken(owner, fence, lifetime, binding(tab), tab, GraphPeriod.ONE_DAY, 1L, surface, gen)

    private fun series(id: String, base: Double) = FreeGraphSeries(
        seriesId = id, label = "label:$id", axisGroup = "krw",
        points = (0 until 144).map { FreeGraphPoint(midnight + (it * 10).minutes, base + it * 0.5, null, null) }
    )

    private fun graph(
        tab: String = "usd",
        content: GraphV2Content = GraphV2Content.READY,
        owner: TopicDisplayOwner = o1,
        open: Boolean = false,
        gen: Long = 1L,
        inlineToken: GraphV2UiToken? = token(tab, GraphV2Surface.INLINE, owner)
    ): GraphV2ScreenState {
        val ids = listOf("hana.$tab", "kb.$tab")
        val chart = if (content == GraphV2Content.READY) GraphV2ChartModel(
            GraphPreparedBuilder.build(FreeGraph(null, ids.mapIndexed { i, id -> series(id, 1400.0 - i * 10) },
                domainStartAt = midnight, domainEndAt = midnight + 24.hours, liveDomainMode = "rolling"), GraphPeriod.ONE_DAY),
            ids.toSet()
        ) else null
        val ready = content == GraphV2Content.READY
        return GraphV2ScreenState(
            tab, GraphPeriod.ONE_DAY, listOf(GraphPeriod.ONE_DAY), content,
            if (ready) GraphV2SelectionStatus.READY else GraphV2SelectionStatus.UNBOUND,
            if (ready) GraphSeriesSelection(ids.toSet(), ids.toSet()) else null,
            if (ready) ids.map { GraphV2SeriesToggle(it, GraphSeriesStyles.of(it, "label:$it"), true, true, "krw") } else emptyList(),
            chart, false, null, null, open && inlineToken != null, inlineToken,
            if (open && inlineToken != null) token(tab, GraphV2Surface.FULLSCREEN, owner, gen) else null
        )
    }

    /** A fake slot: [published] is what the screen observes, [fresh] is what currentState() answers at render. */
    private inner class FakeSlot(initial: GraphV2ScreenState, val owner: TopicDisplayOwner = o1) {
        val published = MutableStateFlow(initial)
        var fresh: GraphV2ScreenState = initial
        val events = mutableListOf<Pair<String, GraphV2UiToken>>()
        val slot = PremiumFxGraphSlot(
            owner = owner,
            state = published,
            currentState = { fresh },
            actions = GraphV2UiActions(
                selectPeriod = { tk, p -> events += "period:${p.code}" to tk },
                toggleSeries = { tk, id -> events += "toggle:$id" to tk },
                enterFullscreen = { tk -> events += "expand" to tk },
                exitFullscreen = { tk -> events += "exit" to tk },
                retrySelection = { tk -> events += "retry" to tk }
            )
        )
        /** Publishes [next] as both the observed and the fresh state, as the holder does. */
        fun publish(next: GraphV2ScreenState) { fresh = next; published.value = next; rule.waitForIdle() }
    }

    private var state by mutableStateOf(PremiumTopicScreenState.NONE)
    private var slot by mutableStateOf<PremiumFxGraphSlot?>(null)
    private val selections = mutableListOf<Pair<TopicDisplayOwner, FreeTab>>()

    private fun show(initial: PremiumTopicScreenState, initialSlot: PremiumFxGraphSlot?) {
        state = initial
        slot = initialSlot
        rule.setContent {
            PremiumTopicScreen(
                state = state,
                onUserTabSelected = { o, tab -> selections += o to tab },
                onRetryConnection = {},
                onRetryTopics = { _, _ -> },
                onApplyRows = { _, _, _, _, _, _ -> },
                onOpenSettings = {},
                newsContent = { visible -> Text(if (visible) "뉴스 보임" else "뉴스 숨김") },
                graphSlot = slot
            )
        }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    /** What an accessibility service can reach: the merged tree, where clearAndSetSemantics leaves nothing beneath it. */
    private fun readable(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun countUnder(tag: String, ancestor: String) =
        rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(ancestor)), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun node(tag: String) = rule.onNode(hasTestTag(tag), useUnmergedTree = true)
    private fun spoken(tag: String) = node(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    private val inlineRoot = "graph_v2:inline:root"
    private val fullRoot = "graph_v2:fullscreen:root"
    private val inlineChart = "graph_v2:inline:chart"

    // --- 11a / 11b: where the graph is drawn -------------------------------------------------------------------------

    /** S1: on each FX tab the graph is drawn once, above the tab's rates; the rate fullscreen carries no graph. */
    @Test fun S1_onEachFxTabTheGraphIsAboveTheRatesAndNotInTheRateFullscreen() {
        val fake = FakeSlot(graph("usd"))
        show(screen(FreeTab.USD), fake.slot)
        for ((tab, code) in listOf(FreeTab.USD to "usd", FreeTab.JPY to "jpy", FreeTab.EUR to "eur")) {
            fake.publish(graph(code))
            state = screen(tab)
            rule.waitForIdle()
            assertEquals("$tab: one inline graph", 1, count(inlineRoot))
            val graphTop = node(inlineRoot).fetchSemanticsNode().boundsInRoot.top
            val ratesTop = node(PremiumTopicTags.HEADING).fetchSemanticsNode().boundsInRoot.top
            assertTrue("$tab: the graph is above the rates", graphTop < ratesTop)
        }
        node(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle()
        assertEquals("one rate fullscreen", 1, count(PremiumTopicTags.FULLSCREEN_LAYER))
        assertEquals("no graph inside the rate fullscreen", 0, countUnder(inlineRoot, PremiumTopicTags.FULLSCREEN_LAYER))
        assertEquals(0, countUnder(fullRoot, PremiumTopicTags.FULLSCREEN_LAYER))
    }

    /** S2: tether and news draw no graph even with a slot; a null slot leaves the screen as it was on every tab. */
    @Test fun S2_tetherNewsAndANullSlotDrawNoGraph() {
        val fake = FakeSlot(graph("usd"))
        show(screen(FreeTab.TETHER), fake.slot)
        assertEquals("tether", 0, count(inlineRoot))
        // Even a graph of the tether tab itself: this slot is the FX graph's, not a tether graph.
        fake.publish(graph("tether"))
        assertEquals("tether with a tether graph", 0, count(inlineRoot))
        state = screen(FreeTab.NEWS); rule.waitForIdle()
        assertEquals("news", 0, count(inlineRoot))
        slot = null
        for (tab in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR, FreeTab.TETHER)) {
            state = screen(tab); rule.waitForIdle()
            assertEquals("$tab with no slot", 0, count(inlineRoot))
            node(PremiumTopicTags.HEADING).assertExists()
        }
        assertTrue(fake.events.isEmpty())
    }

    // --- 11c / 11d: which state is drawn -------------------------------------------------------------------------------

    /**
     * S3: the slot is drawn only for the screen's owner, on the FX tab its graph belongs to, with tokens of that owner and tab;
     * otherwise nothing of it is drawn.
     */
    @Test fun S3_onlyTheScreensOwnerAndTabAndMatchingTokensAreDrawn() {
        val fake = FakeSlot(graph("usd"), owner = o2)
        show(screen(FreeTab.USD), fake.slot)
        assertEquals("another owner's slot", 0, count(inlineRoot))
        val own = FakeSlot(graph("jpy"))
        slot = own.slot; rule.waitForIdle()
        assertEquals("a graph of another tab", 0, count(inlineRoot))
        own.publish(graph("usd", owner = o2))
        assertEquals("tokens of another owner", 0, count(inlineRoot))
        own.publish(graph("usd", inlineToken = token("jpy", GraphV2Surface.INLINE)))
        assertEquals("a token of another tab", 0, count(inlineRoot))
        own.publish(graph("usd"))
        assertEquals("premise: the matching graph is drawn", 1, count(inlineRoot))
    }

    /** S4: the screen draws what currentState() answers at render, not the value it observed. */
    @Test fun S4_theFreshStateIsDrawnNotTheObservedOne() {
        val fake = FakeSlot(graph("usd"))
        show(screen(FreeTab.USD), fake.slot)
        assertEquals("premise", 1, count(inlineChart))
        fake.fresh = graph("usd", GraphV2Content.BLOCKED, inlineToken = null)
        fake.published.value = graph("usd").copy(refreshing = true) // a distinct value, so the screen recomposes
        rule.waitForIdle()
        assertEquals("a blocked answer at render draws no chart", 0, count(inlineChart))
        assertTrue(fake.events.isEmpty())
    }

    // --- 11g / 11h: one fullscreen over a screen nobody can reach -----------------------------------------------------

    /**
     * S5: a graph fullscreen that opens over the rate fullscreen is the only layer, and closing it does not uncover the rate
     * fullscreen it replaced; while it is open the pager does not move and no tab is reported.
     */
    @Test fun S5_onlyOneFullscreenAndClosingItUncoversNothing() {
        val fake = FakeSlot(graph("usd"))
        show(screen(FreeTab.USD), fake.slot)
        node(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle()
        assertEquals("premise: the rate fullscreen is open", 1, count(PremiumTopicTags.FULLSCREEN_LAYER))
        fake.publish(graph("usd", open = true))
        assertEquals("the graph layer", 1, count(PremiumTopicTags.GRAPH_LAYER))
        assertEquals("not both", 0, count(PremiumTopicTags.FULLSCREEN_LAYER))
        node(PremiumTopicTags.GRAPH_LAYER).performTouchInput { swipeLeft() }; rule.waitForIdle()
        assertTrue("no tab while the graph fullscreen is open", selections.isEmpty())
        fake.publish(graph("usd", open = false))
        assertEquals(0, count(PremiumTopicTags.GRAPH_LAYER))
        assertEquals("the replaced rate fullscreen does not come back", 0, count(PremiumTopicTags.FULLSCREEN_LAYER))
        node(PremiumTopicTags.HEADING).assertExists()
    }

    /**
     * S6: nothing under the graph fullscreen can be touched or read: a tap on the layer's margin over the tab row reports no
     * tab, and the body under it leaves the semantics tree until it closes; the inline zoom is still there after the trip.
     */
    @Test fun S6_nothingUnderTheGraphFullscreenIsReachableAndTheInlineZoomSurvives() {
        val fake = FakeSlot(graph("usd"))
        show(screen(FreeTab.USD), fake.slot)
        // The first tab (뉴스): its left edge lies in the layer's margin, outside the graph root's 16dp padding.
        val tabRow = node(PremiumTopicTags.tab(FreeTab.NEWS)).fetchSemanticsNode().boundsInRoot
        val fullWindow = spoken(inlineChart)
        node(inlineChart).performTouchInput {
            val y = height / 2f
            pinch(Offset(width * 0.40f, y), Offset(width * 0.05f, y), Offset(width * 0.60f, y), Offset(width * 0.95f, y))
        }
        rule.waitForIdle()
        val zoomed = spoken(inlineChart)
        assertNotEquals("premise: the inline chart zoomed", fullWindow, zoomed)

        fake.publish(graph("usd", open = true))
        val marginTap = Offset(tabRow.left + 4f, tabRow.center.y)
        val layerBounds = node(PremiumTopicTags.GRAPH_LAYER).fetchSemanticsNode().boundsInRoot
        val graphBounds = node(fullRoot).fetchSemanticsNode().boundsInRoot
        assertTrue("premise: over an underlying tab", tabRow.contains(marginTap))
        assertTrue("premise: inside the overlay Surface", layerBounds.contains(marginTap))
        assertTrue("premise: outside the graph root", !graphBounds.contains(marginTap))
        rule.onRoot().performTouchInput { click(marginTap) }
        rule.waitForIdle()
        assertTrue("a tap on the layer reached the tab under it", selections.isEmpty())
        assertEquals("the tabs under the layer are not readable", 0, readable(PremiumTopicTags.tab(FreeTab.USD)))
        assertEquals(0, readable(PremiumTopicTags.SETTINGS))

        fake.publish(graph("usd", open = false))
        assertEquals("the body is readable again", 1, readable(PremiumTopicTags.tab(FreeTab.USD)))
        assertEquals("the inline zoom survived the trip", zoomed, spoken(inlineChart))

        node(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle()
        assertEquals("under the rate fullscreen too", 0, readable(PremiumTopicTags.tab(FreeTab.USD)))
    }

    /**
     * S7: a pager drag already under way when the graph fullscreen opens turns no page and reports no tab - later events of a
     * pointer keep its first hit path, so the layer that appeared under the finger cannot stop it. The same drag with no
     * overlay turns the page (the positive control).
     */
    @Test fun S7_aDragUnderWayWhenTheGraphFullscreenOpensReportsNoTab() {
        val fake = FakeSlot(graph("usd"))
        show(screen(FreeTab.USD), fake.slot)
        val pager = node(PremiumTopicTags.PAGER)
        pager.performTouchInput { down(Offset(width / 2f, height * 0.85f)); moveBy(Offset(-width * 0.15f, 0f)) }
        rule.waitForIdle()
        fake.publish(graph("usd", open = true))
        assertEquals("premise: the graph layer opened during the drag", 1, count(PremiumTopicTags.GRAPH_LAYER))
        pager.performTouchInput { moveBy(Offset(-width * 0.5f, 0f)); up() }
        rule.waitForIdle()
        assertTrue("a drag under way turned the page", selections.isEmpty())

        fake.publish(graph("usd", open = false))
        pager.performTouchInput {
            down(Offset(width / 2f, height * 0.85f)); moveBy(Offset(-width * 0.15f, 0f)); moveBy(Offset(-width * 0.5f, 0f)); up()
        }
        rule.waitForIdle()
        assertEquals("premise: the same drag turns the page with no overlay", listOf(o1 to FreeTab.JPY), selections)
    }
}
