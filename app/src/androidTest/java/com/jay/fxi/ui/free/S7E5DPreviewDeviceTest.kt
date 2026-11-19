package com.jay.fxi.ui.free

import android.util.Log
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.alert.AlertAddSheetTags
import com.jay.fxi.ui.alert.BankAlertEditor
import com.jay.fxi.ui.components.SubscribeCTATags
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d (`s7e5d_api_agreed.r1.md`, 제안 r2 §2.2–§2.6·§3 기기): the real [FreeAlertPreviewHost] and preview sheet over a test
 * opener. The sheet shows the editor's fields over the quotes it opened with and a subscribe footer, and no save of any kind;
 * every way out discards the input; a subscribe opens the paywall once, only after the sheet has gone, and never when the owner
 * or tab moved meanwhile; a tab change closes it; a save-and-restore brings nothing back.
 */
@RunWith(AndroidJUnit4::class)
class S7E5DPreviewDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val asOf = Instant.parse("2026-09-06T02:30:00Z")
    private val uid = mutableStateOf<String?>("u1")
    private val tab = mutableStateOf<FreeTab?>(FreeTab.USD)
    private val quotes = mutableStateOf(mapOf("kb" to 1400.0, "hana" to 1500.0))
    private var paywalls = 0
    private var opener: FreeAlertPreviewOpener? = null

    private fun content() = @androidx.compose.runtime.Composable {
        Column {
            FreeAlertPreviewHost(uid = uid.value, activeTab = tab.value, onSubscribe = { paywalls += 1 }) { open ->
                opener = open
                Button(onClick = { tab.value?.let { open.open(it, quotes.value, asOf) } }, modifier = Modifier.testTag(OPEN)) { Text("open") }
            }
        }
    }

    private fun show() {
        compose.setContent(content())
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    /** The node's own words; when it has none (a button whose words are its child's), the same tag in the merged tree. */
    private fun textOf(tag: String): String {
        fun SemanticsNode.own() = (config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
        return compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().own()
            .ifEmpty { compose.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode().own() }
    }
    private fun imeShown(): Boolean {
        var shown = false
        compose.activityRule.scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return shown
    }
    private fun sheetOpen() = count(FreeAlertPreviewTags.SHEET) == 1
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }
    private fun openPreview() { tap(OPEN); assertTrue("premise: 열렸다", sheetOpen()) }

    @Test fun S7E5D_D01_thePreviewShowsTheFieldsAndTheFooterAndNoSaveAtAll() {
        show()
        openPreview()
        assertEquals("알림 추가 (미리보기)", textOf(FreeAlertPreviewTags.TITLE))
        val close = textOf(FreeAlertPreviewTags.CLOSE)
        assertTrue("premise: 닫기 문구를 읽었다: '$close'", close.isNotEmpty())
        assertEquals("닫기", close)
        assertEquals("국민은행", textOf(AlertAddSheetTags.HEADER_BANK))
        assertEquals("1400.00", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals("한번만", textOf(AlertAddSheetTags.REPEAT_LABEL))
        assertEquals(1, count(AlertAddSheetTags.CURRENT_RATE))
        assertEquals(1, count(AlertAddSheetTags.RANGE))
        assertEquals("구독 혜택 보기", textOf(SubscribeCTATags.BUTTON))
        assertEquals("알림 저장과 발송은 구독 후 사용할 수 있어요", textOf(SubscribeCTATags.NOTE))
        for (absent in listOf(AlertAddSheetTags.SAVE, AlertAddSheetTags.CANCEL, AlertAddSheetTags.SAVING, AlertAddSheetTags.REMAINING,
                AlertAddSheetTags.DUPLICATE, AlertAddSheetTags.ERROR_DIALOG, AlertAddSheetTags.TITLE)) {
            assertEquals("preview 에 $absent", 0, count(absent))
        }
    }

    /** What opened is what stays: a new snapshot after opening changes neither the threshold nor the shown quote. */
    @Test fun S7E5D_D02_theQuotesAreFrozenAtOpening() {
        show()
        openPreview()
        val shown = textOf(AlertAddSheetTags.CURRENT_RATE)
        update { quotes.value = mapOf("kb" to 1450.0, "hana" to 1550.0) }
        assertEquals("1400.00", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals(shown, textOf(AlertAddSheetTags.CURRENT_RATE))
        tap(AlertAddSheetTags.bank("hana"))
        assertEquals("다른 은행도 연 순간의 값", "1500.00", textOf(AlertAddSheetTags.THRESHOLD))
    }

    @Test fun S7E5D_D03_noSnapshotOpensOnNothing() {
        quotes.value = emptyMap()
        show()
        openPreview()
        assertEquals("", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals(listOf(0, 0), listOf(count(AlertAddSheetTags.CURRENT_RATE), count(AlertAddSheetTags.RANGE)))
    }

    /** Close, Back and a drag each end the preview, open no paywall, and the next one starts afresh. */
    @Test fun S7E5D_D04_everyWayOutDiscardsTheInput() {
        show()
        for (way in listOf("close", "back", "drag")) {
            openPreview()
            compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1390")
            tap(AlertAddSheetTags.CONDITION)
            when (way) {
                "close" -> tap(FreeAlertPreviewTags.CLOSE)
                "back" -> {
                    // A Back while the keyboard is up only closes the keyboard; this case is the Back that reaches the sheet.
                    Espresso.closeSoftKeyboard()
                    compose.waitUntil(5_000) { !imeShown() }
                    assertTrue("premise: 키보드가 닫혔다", !imeShown())
                    Espresso.pressBack(); compose.waitForIdle()
                }
                "drag" -> { compose.onNodeWithTag(FreeAlertPreviewTags.SHEET, useUnmergedTree = true).performTouchInput { swipeDown() }; compose.waitForIdle() }
            }
            compose.waitUntil(5_000) { !sheetOpen() }
            assertEquals("$way: paywall", 0, paywalls)
            openPreview()
            assertEquals("$way: 새로 열면 처음부터", "1400.00", textOf(AlertAddSheetTags.THRESHOLD))
            tap(FreeAlertPreviewTags.CLOSE)
            compose.waitUntil(5_000) { !sheetOpen() }
        }
    }

    /** The paywall opens once, after the sheet is gone — not while it is still hiding, not twice for a double tap. */
    @Test fun S7E5D_D05_aSubscribeOpensThePaywallOnceAfterTheSheetIsGone() {
        show()
        openPreview()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(SubscribeCTATags.BUTTON, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SubscribeCTATags.BUTTON, useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("hide 완료 전에 paywall 을 열었다", 0, paywalls)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { !sheetOpen() }
        compose.waitForIdle()
        assertEquals(1, paywalls)
    }

    @Test fun S7E5D_D06_aTabOrOwnerChangeDuringTheHideOpensNoPaywall() {
        for (change in listOf("tab", "owner")) {
            uid.value = "u1"; tab.value = FreeTab.USD; paywalls = 0
            if (change == "tab") show() else update { }
            openPreview()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag(SubscribeCTATags.BUTTON, useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { if (change == "tab") tab.value = FreeTab.JPY else uid.value = "u2" }
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5_000) { !sheetOpen() }
            compose.waitForIdle()
            assertEquals("$change: paywall", 0, paywalls)
            update { uid.value = "u1"; tab.value = FreeTab.USD }
        }
    }

    /** The settled tab moving away closes an open preview; an opener for a tab that is not active opens nothing. */
    @Test fun S7E5D_D07_aTabChangeClosesIt_andAnInactiveTabCannotOpen() {
        show()
        openPreview()
        update { tab.value = FreeTab.EUR }
        compose.waitUntil(5_000) { !sheetOpen() }
        assertEquals(0, paywalls)
        update { opener!!.open(FreeTab.USD, quotes.value, asOf) }
        assertTrue("활성 아닌 탭이 열었다", !sheetOpen())
        update { opener!!.open(FreeTab.EUR, quotes.value, asOf) }
        assertTrue("활성 탭은 연다", sheetOpen())
    }

    /** A save-and-restore cycle brings back no open preview and no draft. */
    @Test fun S7E5D_D08_aSavedStateRestoreBringsNothingBack() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent(content())
        compose.waitForIdle()
        openPreview()
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1390")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("복원 뒤 preview 가 열려 있다", !sheetOpen())
        openPreview()
        assertEquals("1400.00", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals(0, paywalls)
    }

    /** The activity recreated, as a rotation does it: through the saved-instance bundle, and nothing of the preview comes back. */
    @Test fun S7E5D_D09_anActivityRecreateBringsNothingBack() {
        show()
        openPreview()
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1390")
        compose.activityRule.scenario.recreate()
        // The rule sets content once per test; the recreated activity gets the same tree directly, after its saved state.
        compose.activityRule.scenario.onActivity { it.setContent(null, content()) }
        compose.waitForIdle()
        assertTrue("recreate 뒤 preview 가 열려 있다", !sheetOpen())
        assertEquals(0, paywalls)
    }

    // ---- through the real free screen ----

    private val screenState = mutableStateOf(bankState(FreeTab.USD))

    private fun bankState(tab: FreeTab, quotes: Map<String, Double> = mapOf("kb" to 1400.0, "hana" to 1500.0)) = FreeSnapshotUiState(
        uid = "u1",
        selectedTab = tab,
        availability = if (tab in FREE_BANK_ALERT_PREVIEW_TABS) FreeSnapshotAvailability.FRESH else FreeSnapshotAvailability.AWAITING_SNAPSHOT,
        previewQuotes = if (tab in FREE_BANK_ALERT_PREVIEW_TABS) quotes else emptyMap(),
        previewAsOf = if (tab in FREE_BANK_ALERT_PREVIEW_TABS) asOf else null
    )

    private fun showScreen() {
        compose.setContent {
            FreeSnapshotScreen(
                state = screenState.value,
                // As the view model does (`FreeSnapshotViewModel.selectTab`): the tab already selected is not selected again. The pager
                // reports its settled page after a move, and a reset here would overwrite the tab's quotes with the default ones.
                onSelectTab = { if (screenState.value.selectedTab != it) screenState.value = bankState(it) },
                onSelectPeriod = {},
                onToggleSeries = {},
                onApplyRowPreference = { _, _, _, _ -> },
                onSignOut = {},
                onSubscribe = { paywalls += 1 }
            )
        }
        compose.waitForIdle()
    }

    // The same reading of the settled bank page as S7E5DScreenDeviceTest (`e5d_devfix_plan.claude.r3.md` X2·X4): after a move the
    // page left behind can stay composed off to the side, with its own teaser, so nothing here counts across the whole screen.
    private val verticalList = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun onScreen(node: SemanticsNode): Boolean {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val box = node.boundsInRoot
        return node.layoutInfo.isPlaced && box.width > 0f && box.height > 0f && box.left >= root.left && box.right <= root.right
    }
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> =
        generateSequence(listOf(node)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten()
    private fun list(): SemanticsNodeInteraction {
        val all = compose.onAllNodes(verticalList, useUnmergedTree = true)
        val nodes = all.fetchSemanticsNodes()
        val shown = nodes.indices.filter { onScreen(nodes[it]) }
        assertEquals("premise: 보이는 세로 목록이 하나다 ${nodes.map { it.boundsInRoot }}", 1, shown.size)
        return all[shown.single()]
    }
    private fun inList(matcher: SemanticsMatcher): List<SemanticsNode> = descendants(list().fetchSemanticsNode()).filter { matcher.matches(it) }.toList()
    private fun placed(matcher: SemanticsMatcher): List<SemanticsNode> = inList(matcher).filter { onScreen(it) }
    private fun toTeaser() {
        list().performScrollToNode(hasTestTag(FreeAlertPreviewTags.SECTION))
        compose.waitForIdle()
        compose.waitUntil(5_000) { placed(hasTestTag(FreeAlertPreviewTags.SECTION)).size == 1 }
    }
    private fun opens(): Int = inList(hasTestTag(FreeAlertPreviewTags.OPEN)).size
    private fun tapInList(tag: String) {
        list().performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        val ids = placed(hasTestTag(tag)).map { it.id }
        assertEquals("premise: 목록 안 화면에 놓인 $tag 하나 $ids", 1, ids.size)
        val all = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
        all[all.fetchSemanticsNodes().indexOfFirst { it.id == ids.single() }].performClick()
        compose.waitForIdle()
    }
    /** For the first run's record (X2): every vertical list after a move, with what decides whether it is the settled one. */
    private fun logLists(label: String) {
        compose.onAllNodes(verticalList, useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
            Log.i("E5dCandidates", "$label list id=${n.id} placed=${n.layoutInfo.isPlaced} clip=${n.boundsInRoot} pos=${n.positionInRoot} " +
                "size=${n.size} counts=${onScreen(n)}")
        }
    }

    /** On a bank tab the teaser starts collapsed, opens the preview on that tab's own quotes, and the CTA reaches onSubscribe once. */
    @Test fun S7E5D_D10_theTeaserOpensThePreviewOnTheTabsQuotes() {
        showScreen()
        toTeaser()
        assertEquals("접힌 채 시작", 0, opens())
        tapInList(FreeAlertPreviewTags.SECTION_TOGGLE)
        tapInList(FreeAlertPreviewTags.OPEN)
        assertTrue(sheetOpen())
        assertEquals("1400.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { !sheetOpen() }
        compose.waitForIdle()
        assertEquals(1, paywalls)
    }

    /**
     * Moving to another bank tab with the preview open closes it, opens no paywall, and the next opening there starts afresh.
     *
     * Moving to 테더 or 뉴스 with it open is not here: this rule cannot compose the 뉴스 page (its view model comes from Hilt, and
     * the pager prefetches it next to 테더). That move is an E5d-J journey case on the installed app and stays PENDING until it
     * runs (`e5d_devfix_plan.claude.r2.md` P6).
     */
    @Test fun S7E5D_D11_movingToAnotherBankTabWhileOpenClosesIt() {
        showScreen()
        toTeaser()
        tapInList(FreeAlertPreviewTags.SECTION_TOGGLE)
        tapInList(FreeAlertPreviewTags.OPEN)
        assertTrue(sheetOpen())
        compose.onNodeWithTag(AlertAddSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1390")
        compose.waitForIdle()
        update { screenState.value = bankState(FreeTab.JPY, mapOf("kb" to 950.0, "hana" to 951.0)) }
        compose.waitUntil(5_000) { !sheetOpen() }
        compose.waitForIdle()
        assertEquals("떠난 뒤 paywall", 0, paywalls)
        logLists("D11 moved")
        toTeaser()
        assertEquals("새 tab 의 teaser 는 접힌 채", 0, opens())
        tapInList(FreeAlertPreviewTags.SECTION_TOGGLE)
        tapInList(FreeAlertPreviewTags.OPEN)
        assertEquals("새로 열면 그 tab 의 처음부터", BankAlertEditor.formatThreshold(950.0), textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals(0, paywalls)
    }

    private companion object {
        const val OPEN = "s7e5d-test-open"
    }
}
