package com.jay.fxi

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.ui.free.heading
import com.jay.fxi.ui.premium.PremiumTopicPresenter
import com.jay.fxi.ui.premium.PremiumTopicScreenBanner
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import com.jay.fxi.ui.premium.PremiumTopicUiState
import com.jay.fxi.ui.premium.TopicBannerReason
import com.jay.fxi.ui.premium.view.PremiumTopicScreen
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import com.jay.fxi.ui.rates.RateDisplay
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned R4-c C3c-2 screen contract (R4c/C3c2/design_codex.r1.md + r2 agreed), run on a device. The screen draws the state it
 * is given and reports only confirmed user actions: no pager before a tab is accepted, five tabs in iOS order, one report per
 * click or settled swipe and none for its own moves, the selected tab's rows only, the status line as given, the sheet's answer
 * with its owner and tab, a rate fullscreen with the same rows, and nothing of an earlier owner after the owner changes. Policy
 * already decided on the JVM (banner order, preferences, selection de-duplication) is not repeated. The implementation reads but
 * does not edit this file.
 */
class PremiumTopicScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val o2 = TopicDisplayOwner(a1, 2L)
    private fun q(source: String, asset: String, rate: Double) = TopicQuote(source, asset, rate, t)
    private fun rates(kb: Double = 1390.0) = TopicRates(dollarIndex = TopicDollarIndex(98.5, t, "investing")).merge(listOf(
        q("kb", "usd-krw", kb), q("hana", "usd-krw", 1391.0), q("kb", "jpy-krw", 950.12), q("kb", "eur-krw", 1600.0),
        q("upbit", "usdt-krw", 1400.0)))
    private fun screen(tab: FreeTab?, owner: TopicDisplayOwner = o1, r: TopicRates = rates(),
                       banner: PremiumTopicScreenBanner? = null, time: String? = null): PremiumTopicScreenState {
        val ui = if (tab == null) PremiumTopicUiState.NONE else
            PremiumTopicPresenter.present(TopicDisplayState(owner, r, false, TopicConnectionDisplay.OPEN), OwnedTopicFocus(owner.identity, tab), owner.identity)
        return PremiumTopicScreenState(ui, banner, time)
    }

    private var state by mutableStateOf(PremiumTopicScreenState.NONE)
    private val selections = mutableListOf<Pair<TopicDisplayOwner, FreeTab>>()
    private val retries = mutableListOf<TopicDisplayOwner>()
    private val topicRetries = mutableListOf<Pair<TopicDisplayOwner, FreeTab>>()
    private val applied = mutableListOf<List<Any>>()
    private var settingsOpened = 0

    private val content: @androidx.compose.runtime.Composable () -> Unit = {
        PremiumTopicScreen(
            state = state,
            onUserTabSelected = { o, tab -> selections += o to tab },
            onRetryConnection = { retries += it },
            onRetryTopics = { o, tab -> topicRetries += o to tab },
            onApplyRows = { o, tab, list, seeded, order, hidden -> applied += listOf(o, tab, list, seeded, order, hidden) },
            onOpenSettings = { settingsOpened += 1 },
            newsContent = { visible -> Text(if (visible) "뉴스 보임" else "뉴스 숨김") }
        )
    }
    private fun show(initial: PremiumTopicScreenState) {
        state = initial
        rule.setContent(content)
        rule.waitForIdle()
    }
    /** [tab]'s state with its first section's first row repeated as 24 rows, so the page list can scroll. */
    private fun tall(tab: FreeTab): PremiumTopicScreenState {
        val base = screen(tab)
        val section = base.ui.rateSections.first { it.rows.isNotEmpty() }
        val rows = (0 until 24).map { section.rows.first().copy(id = "scroll_$it") }
        return base.copy(ui = base.ui.copy(rateSections = listOf(section.copy(rows = rows))))
    }
    private fun scrollPosition(): Float = rule.onNodeWithTag(PremiumTopicTags.RATES).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
    private fun scrollDown() {
        rule.onNodeWithTag(PremiumTopicTags.RATES).performTouchInput { swipeUp() }
        rule.waitForIdle()
        check(scrollPosition() > 0f) { "fixture: the page list scrolled" }
    }

    private fun rowsIn(region: String, id: String) =
        rule.onAllNodesWithTag(PremiumTopicTags.row(id), useUnmergedTree = true).fetchSemanticsNodes()
            .count { node -> generateSequence(node) { it.parent }.any { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } == region } }

    @Test fun c3c2_01a_noPagerBeforeATabIsAccepted_thenFiveTabsInOrder_andNoReportForTheInitialPage() {
        show(screen(null))
        rule.onNodeWithTag(PremiumTopicTags.PAGER).assertDoesNotExist()
        state = screen(FreeTab.TETHER); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.PAGER).assertExists()
        val lefts = FreeTab.entries.map { rule.onNodeWithTag(PremiumTopicTags.tab(it)).fetchSemanticsNode().boundsInRoot.left }
        assertEquals("C3c2-01 tab order 뉴스·테더·달러·엔화·유로", lefts.sorted(), lefts)
        assertEquals("C3c2-01 order", listOf(FreeTab.NEWS, FreeTab.TETHER, FreeTab.USD, FreeTab.JPY, FreeTab.EUR), FreeTab.entries)
        rule.onNodeWithTag(PremiumTopicTags.HEADING).assertExists()
        assertTrue("C3c2-01 no report for the initial page", selections.isEmpty())
    }

    @Test fun c3c2_01b_aClickReportsOnce_aFarClickReportsNoPageBetween_andTheScreensOwnMovesReportNothing() {
        show(screen(FreeTab.NEWS))
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.EUR)).performClick(); rule.waitForIdle()
        assertEquals("C3c2-01 far click", listOf(o1 to FreeTab.EUR), selections)
        state = screen(FreeTab.EUR); rule.waitForIdle()
        state = screen(FreeTab.JPY); rule.waitForIdle()
        assertEquals("C3c2-01 accepted focus moves report nothing", listOf(o1 to FreeTab.EUR), selections)
        assertEquals("C3c2-01 the page follows the accepted tab", FreeTab.JPY.heading,
            rule.onNodeWithTag(PremiumTopicTags.HEADING, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString("") { it.text })
    }

    @Test fun c3c2_01c_aQuickReverseClickIsReportedEvenBeforeFocusAnswered() {
        show(screen(FreeTab.USD))
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.TETHER)).performClick()
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.USD)).performClick()
        rule.waitForIdle()
        assertEquals("C3c2-01 both clicks", listOf(o1 to FreeTab.TETHER, o1 to FreeTab.USD), selections)
    }

    @Test fun c3c2_01d_aSettledSwipeReportsItsPageOnce() {
        show(screen(FreeTab.USD))
        rule.onNodeWithTag(PremiumTopicTags.PAGER).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals("C3c2-01 swipe", listOf(o1 to FreeTab.JPY), selections)
    }

    @Test fun c3c2_02_theSelectedTabsRowsOnly_theYenUnit_andTheDollarIndexOnDollarAndTether() {
        show(screen(FreeTab.USD))
        assertEquals("C3c2-02 kb once, in the selected page", 1, rowsIn(PremiumTopicTags.RATES, "kb"))
        rule.onNodeWithTag(PremiumTopicTags.DXY, useUnmergedTree = true).assertExists()
        state = screen(FreeTab.JPY); rule.waitForIdle()
        rule.onNode(hasTestTag(PremiumTopicTags.HEADING) and hasText("100엔당", substring = true), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(RateDisplay.format(950.12), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(PremiumTopicTags.DXY, useUnmergedTree = true).assertDoesNotExist()
        assertEquals("C3c2-02 no dollar row leaks into the yen page", 1, rowsIn(PremiumTopicTags.RATES, "kb"))
        state = screen(FreeTab.TETHER); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.DXY, useUnmergedTree = true).assertExists()
        state = screen(FreeTab.EUR); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.DXY, useUnmergedTree = true).assertDoesNotExist()
        state = screen(FreeTab.NEWS); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.DXY, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun c3c2_03_theSheetAnswersWithItsOwnerTabAndList_andCancelAnswersNothing() {
        show(screen(FreeTab.USD))
        rule.onNodeWithTag(PremiumTopicTags.customize(RateRowList.FX_BANKS)).performClick(); rule.waitForIdle()
        rule.onNodeWithText("취소").performClick(); rule.waitForIdle()
        assertTrue("C3c2-03 cancel answers nothing", applied.isEmpty())
        rule.onNodeWithTag(PremiumTopicTags.customize(RateRowList.FX_BANKS)).performClick(); rule.waitForIdle()
        rule.onNodeWithContentDescription("하나은행 숨기기").performClick()
        rule.onNodeWithText("완료").performClick(); rule.waitForIdle()
        assertEquals("C3c2-03 one answer", 1, applied.size)
        val codes = state.ui.rowEditors.single { it.list == RateRowList.FX_BANKS }.entries.map { it.code }
        val answer = applied.single()
        assertEquals("C3c2-03 owner", o1, answer[0])
        assertEquals("C3c2-03 tab", FreeTab.USD, answer[1])
        assertEquals("C3c2-03 list", RateRowList.FX_BANKS, answer[2])
        assertEquals("C3c2-03 seeded is what the sheet opened with", codes, answer[3])
        assertEquals("C3c2-03 order unchanged", codes, answer[4])
        assertEquals("C3c2-03 hidden", setOf("hana"), answer[5])
    }

    @Test fun c3c2_04_theStatusLineAsGiven_andItsRetryReportsTheOwner() {
        show(screen(FreeTab.USD, banner = PremiumTopicScreenBanner.Offline, time = "마지막 업데이트: 00:05"))
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER) and hasText("오프라인 모드", substring = true), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER_TIME) and hasText("마지막 업데이트: 00:05"), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(PremiumTopicTags.BANNER_ACTION).assertDoesNotExist()
        state = screen(FreeTab.USD, banner = PremiumTopicScreenBanner.Failed); rule.waitForIdle()
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER) and hasText("연결할 수 없습니다", substring = true), useUnmergedTree = true).assertExists()
        // The action is a button: its label is merged into the tagged node.
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER_ACTION) and hasText("재연결")).performClick()
        rule.waitForIdle()
        assertEquals("C3c2-04 retry with its owner", listOf(o1), retries)
        state = screen(FreeTab.USD); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.BANNER).assertDoesNotExist()
    }

    // R4-c C2+b-3 (R4c/C2b/b3_design_codex.r2.md B3-09, adjusted by b3_review_claude.r1.md; Claude-owned): a topic line is drawn
    // as given with no time; its "다시 시도" reports the owner and the selected tab to the topic retry, never to the connection's; a
    // line without a retry has no button at all.
    @Test fun b3_09_theTopicLineAsGiven_itsRetryReportsTheOwnerAndTab_andOnlyTheFailureReconnects() {
        show(screen(FreeTab.USD, banner = PremiumTopicScreenBanner.Topic(TopicBannerReason.TOPICS_DISABLED, canRetry = true)))
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER) and hasText("실시간 시세를 일시적으로 제공할 수 없습니다"), useUnmergedTree = true)
            .assertExists()
        rule.onNodeWithTag(PremiumTopicTags.BANNER_TIME).assertDoesNotExist()
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER_ACTION) and hasText("다시 시도")).performClick()
        rule.waitForIdle()
        assertEquals("B3-09 the topic retry with its owner and tab", listOf(o1 to FreeTab.USD), topicRetries)
        assertTrue("B3-09 not the connection retry", retries.isEmpty())

        for ((reason, text) in listOf(
            TopicBannerReason.AUTH_FAILED to "로그인 상태를 다시 확인해 주세요",
            TopicBannerReason.TOPICS_DISABLED to "실시간 시세를 일시적으로 제공할 수 없습니다",
            TopicBannerReason.TOPIC_UNAVAILABLE to "일부 실시간 시세를 사용할 수 없습니다",
            TopicBannerReason.DELIVERY_DELAYED to "실시간 시세 수신이 지연되고 있습니다"
        )) {
            state = screen(FreeTab.USD, banner = PremiumTopicScreenBanner.Topic(reason, canRetry = false)); rule.waitForIdle()
            rule.onNode(hasTestTag(PremiumTopicTags.BANNER) and hasText(text), useUnmergedTree = true).assertExists()
            rule.onNodeWithTag(PremiumTopicTags.BANNER_ACTION).assertDoesNotExist()
            rule.onNodeWithText("다시 시도").assertDoesNotExist()
        }

        state = screen(FreeTab.TETHER, banner = PremiumTopicScreenBanner.Topic(TopicBannerReason.DELIVERY_DELAYED, canRetry = true))
        rule.waitForIdle()
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER_ACTION) and hasText("다시 시도")).performClick()
        rule.waitForIdle()
        assertEquals("B3-09 the selected tab", listOf(o1 to FreeTab.USD, o1 to FreeTab.TETHER), topicRetries)

        state = screen(FreeTab.USD, banner = PremiumTopicScreenBanner.Failed); rule.waitForIdle()
        rule.onNode(hasTestTag(PremiumTopicTags.BANNER_ACTION) and hasText("재연결")).performClick()
        rule.waitForIdle()
        assertEquals("B3-09 the failure reconnects", listOf(o1), retries)
        assertEquals("B3-09 and is no topic retry", 2, topicRetries.size)
    }

    @Test fun c3c2_05_theNewsSlotIsToldWhetherItsPageIsShown() {
        show(screen(FreeTab.NEWS))
        rule.onNodeWithText("뉴스 보임").assertExists()
        state = screen(FreeTab.EUR); rule.waitForIdle()
        rule.onNodeWithText("뉴스 보임").assertDoesNotExist()
        assertTrue("C3c2-05 no rate selection from the news page", selections.isEmpty())
    }

    @Test fun c3c2_06_settingsAndTheRateFullscreen_withTheSameRows_closedByButtonAndBack() {
        show(screen(FreeTab.USD))
        rule.onNodeWithTag(PremiumTopicTags.SETTINGS).performClick(); rule.waitForIdle()
        assertEquals("C3c2-06 settings", 1, settingsOpened)
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle()
        assertEquals("C3c2-06 same rows in the fullscreen", 1, rowsIn(PremiumTopicTags.FULLSCREEN_LAYER, "kb"))
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).assertDoesNotExist()
        rule.onNodeWithTag(PremiumTopicTags.RATES).performTouchInput { doubleClick() }; rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).assertExists()
        Espresso.pressBack(); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).assertDoesNotExist()
        rule.onNodeWithTag(PremiumTopicTags.HEADING).assertExists()
        // The fullscreen offers the same row sheet, and a double tap inside it returns.
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle()
        rule.onNode(hasTestTag(PremiumTopicTags.customize(RateRowList.FX_BANKS)) and
            androidx.compose.ui.test.hasAnyAncestor(hasTestTag(PremiumTopicTags.FULLSCREEN_LAYER))).performClick(); rule.waitForIdle()
        rule.onNodeWithContentDescription("하나은행 숨기기").performClick()
        rule.onNodeWithText("완료").performClick(); rule.waitForIdle()
        assertEquals("C3c2-06 the fullscreen sheet answers for the same owner and tab", listOf(o1, FreeTab.USD, RateRowList.FX_BANKS),
            applied.single().take(3))
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).performTouchInput { doubleClick() }; rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).assertDoesNotExist()
        rule.onNodeWithTag(PremiumTopicTags.HEADING).assertExists()
        assertTrue("C3c2-06 no selection from overlays", selections.isEmpty())
    }

    @Test fun c3c2_06b_aFullscreenRoundTripKeepsTheTabAndTheListPosition() {
        show(tall(FreeTab.USD))
        scrollDown()
        val position = scrollPosition()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick(); rule.waitForIdle()
        rule.onNode(hasTestTag(PremiumTopicTags.HEADING) and hasText(FreeTab.USD.heading), useUnmergedTree = true).assertExists()
        assertEquals("C3c2-06 same position", position, scrollPosition(), 0.5f)
        assertTrue("C3c2-06 no selection", selections.isEmpty())
    }

    @Test fun c3c2_07a_aNewOwnerClosesTheSheetAndTheFullscreen_andNeitherComesBackAfterARestoreOrForTheEarlierOwner() {
        val tester = StateRestorationTester(rule)
        state = screen(FreeTab.USD)
        tester.setContent(content); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.customize(RateRowList.FX_BANKS)).performClick(); rule.waitForIdle()
        rule.onNodeWithText("완료").assertExists()
        state = screen(FreeTab.USD, owner = o2); rule.waitForIdle()
        rule.onNodeWithText("완료").assertDoesNotExist()
        tester.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        rule.onNodeWithText("완료").assertDoesNotExist()
        state = screen(FreeTab.USD, owner = o1); rule.waitForIdle()
        rule.onNodeWithText("완료").assertDoesNotExist()

        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).assertExists()
        state = screen(FreeTab.USD, owner = o2); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).assertDoesNotExist()
        tester.emulateSavedInstanceStateRestore(); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).assertDoesNotExist()
        state = screen(FreeTab.USD, owner = o1); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_LAYER).assertDoesNotExist()
        assertTrue("C3c2-07 nothing applied", applied.isEmpty())
    }

    @Test fun c3c2_07b_aNewOwnersFirstFrameShowsItsOwnPrice_notOneTweenedFromTheLastOwner() {
        show(screen(FreeTab.USD))
        rule.mainClock.autoAdvance = false
        state = screen(FreeTab.USD, owner = o2, r = rates(kb = 1500.0))
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText(RateDisplay.format(1500.0), useUnmergedTree = true).assertExists()
        rule.mainClock.autoAdvance = true
    }

    @Test fun c3c2_07c_aRestoredScreenKeepsItsTabAndListPosition_andReportsNothing() {
        val tester = StateRestorationTester(rule)
        state = tall(FreeTab.JPY)
        tester.setContent(content)
        rule.waitForIdle()
        scrollDown()
        val position = scrollPosition()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNode(hasTestTag(PremiumTopicTags.HEADING) and hasText("100엔당", substring = true), useUnmergedTree = true).assertExists()
        assertEquals("C3c2-07 same position after restore", position, scrollPosition(), 0.5f)
        assertTrue("C3c2-07 restore reports nothing", selections.isEmpty())
    }

    @Test fun c3c2_08_aSmallScreenWithLargeTextKeepsEveryTabAndSettingsReachable() {
        state = screen(FreeTab.USD)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.size(320.dp, 560.dp)) { content() }
            }
        }
        rule.waitForIdle()
        FreeTab.entries.forEach { rule.onNodeWithTag(PremiumTopicTags.tab(it)).assertIsDisplayed() }
        rule.onNodeWithTag(PremiumTopicTags.SETTINGS).assertIsDisplayed()
        rule.onNodeWithTag(PremiumTopicTags.HEADING).assertIsDisplayed()
        rule.onNode(hasTestTag(PremiumTopicTags.row("kb")) and
            androidx.compose.ui.test.hasAnyAncestor(hasTestTag(PremiumTopicTags.RATES)), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).assertIsDisplayed().performClick(); rule.waitForIdle()
        state = screen(FreeTab.JPY); rule.waitForIdle()
        rule.onNode(hasTestTag(PremiumTopicTags.HEADING) and hasText("100엔당", substring = true), useUnmergedTree = true).assertIsDisplayed()
    }
}
