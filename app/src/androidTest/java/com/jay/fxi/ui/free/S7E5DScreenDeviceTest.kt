package com.jay.fxi.ui.free

import android.util.Log
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.toSize
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jay.fxi.data.free.FreeSnapshotEntry
import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.alert.AlertAddSheetTags
import com.jay.fxi.ui.alert.BankAlertEditor
import com.jay.fxi.ui.components.SubscribeCTATags
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5d gap hunt r1 G16·G17 (`e5d_gaphunt.claude.r2.md`) on the real [FreeSnapshotScreen], with states made by the real
 * [FreeSnapshotUiState.from] over cached snapshots.
 *
 * - G17: each of the three bank tabs in each availability carries one collapsed teaser with its two header words, expands to the
 *   guide and one entry, opens on that tab's quotes or on nothing, and sits after the rates and before the premium button.
 *   테더 and 뉴스 never carry it, directly or on the way back; leaving and coming back, or a save-and-restore, collapses it.
 * - G16: while open, a new snapshot, another period or the 24h expiry changes neither the frozen quote nor the edited input;
 *   the next opening takes the then-current pair, or nothing, and starts from KB·이하·한번만.
 */
@RunWith(AndroidJUnit4::class)
class S7E5DScreenDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val basis = Instant.parse("2026-09-06T02:30:00Z")
    private val servers = mapOf(FreeTab.USD to "usd", FreeTab.JPY to "jpy", FreeTab.EUR to "eur")
    private var paywalls = 0

    private fun snapshot(tab: FreeTab, period: GraphPeriod, kb: Double, hana: Double, at: Instant): FreeSnapshot {
        val server = servers.getValue(tab)
        val currency = "$server-krw"
        return FreeSnapshot(
            tab = server,
            period = period,
            asOf = at,
            generatedAt = at + 7.minutes,
            refreshNotBefore = at + 48.hours,
            rate = FreeRate.Flat(currency, listOf(ExchangeRate(currency, "kb", kb, at - 1.hours), ExchangeRate(currency, "hana", hana, at - 1.hours))),
            graph = FreeGraph(null, listOf(FreeGraphSeries("investing.$server", listOf(FreeGraphPoint(at - 2.hours, 1.0, null, null), FreeGraphPoint(at, 2.0, null, null)))))
        )
    }

    private fun stateFor(tab: FreeTab, snapshot: FreeSnapshot?, freshness: FreeSnapshotFreshness, period: GraphPeriod = FreeSnapshotUiState.DEFAULT_PERIOD): FreeSnapshotUiState {
        val entries = if (snapshot == null) emptyMap() else mapOf(FreeSnapshotKey(snapshot.tab, snapshot.period) to FreeSnapshotEntry(snapshot, freshness))
        return FreeSnapshotUiState.from("u1", tab, period, FreeSnapshotReadState("u1", entries), emptySet())
    }

    private val state = mutableStateOf(FreeSnapshotUiState(uid = "u1"))
    /** Off and on again takes the whole screen out of the composition, so its pages and their teasers are new. */
    private val mounted = mutableStateOf(true)

    private fun screen() = @androidx.compose.runtime.Composable {
        if (mounted.value) FreeSnapshotScreen(
            state = state.value,
            onSelectTab = { tab -> state.value = stateFor(tab, servers[tab]?.let { snapshot(tab, FreeSnapshotUiState.DEFAULT_PERIOD, 1400.0, 1500.0, basis) }, FreeSnapshotFreshness.FRESH) },
            onSelectPeriod = {},
            onToggleSeries = {},
            onApplyRowPreference = { _, _, _, _ -> },
            onSignOut = {},
            onSubscribe = { paywalls += 1 }
        )
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }
    /** The node's own words; when it has none (a button whose words are its child's), the same tag in the merged tree. */
    private fun textOf(tag: String): String {
        fun SemanticsNode.own() = (config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
        return compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().own()
            .ifEmpty { compose.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode().own() }
    }
    private fun sheetOpen() = count(FreeAlertPreviewTags.SHEET) == 1
    private fun imeShown(): Boolean {
        var shown = false
        compose.activityRule.scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return shown
    }

    // A tab's content is a LazyColumn: an item below the fold is not composed at all, so it must be scrolled to, not waited for.
    private val verticalList = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    /**
     * On screen (`e5d_devfix_plan.claude.r3.md` X2): placed in the last layout, with a size, inside the root across. A page the
     * pager still holds off to the side reports a zero box; an item kept composed after it left the viewport may report where
     * it was last placed.
     */
    private fun onScreen(node: SemanticsNode): Boolean {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val box = node.boundsInRoot
        return node.layoutInfo.isPlaced && box.width > 0f && box.height > 0f && box.left >= root.left && box.right <= root.right
    }
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> =
        generateSequence(listOf(node)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten()
    /** A placed node's own box, not cut down by the list's clip. */
    private fun SemanticsNode.box(): Rect = Rect(positionInRoot, size.toSize())

    /** The settled page's list: exactly one vertical list on screen (the pager itself scrolls sideways). */
    private fun list(): SemanticsNodeInteraction {
        val all = compose.onAllNodes(verticalList, useUnmergedTree = true)
        val nodes = all.fetchSemanticsNodes()
        val shown = nodes.indices.filter { onScreen(nodes[it]) }
        assertEquals("premise: 보이는 세로 목록이 하나다 ${nodes.map { it.boundsInRoot }}", 1, shown.size)
        return all[shown.single()]
    }
    /** Inside that list: what [matcher] picks, composed. */
    private fun inList(matcher: SemanticsMatcher): List<SemanticsNode> = descendants(list().fetchSemanticsNode()).filter { matcher.matches(it) }.toList()
    /** Inside that list: what [matcher] picks, on screen. */
    private fun placed(matcher: SemanticsMatcher): List<SemanticsNode> = inList(matcher).filter { onScreen(it) }
    private fun toInList(matcher: SemanticsMatcher) { list().performScrollToNode(matcher); compose.waitForIdle() }
    private fun toTeaser() {
        toInList(hasTestTag(FreeAlertPreviewTags.SECTION))
        compose.waitUntil(5_000) { placed(hasTestTag(FreeAlertPreviewTags.SECTION)).size == 1 }
    }
    /** The one teaser on screen in the settled page's list. */
    private fun shownTeaser(label: String): SemanticsNode = placed(hasTestTag(FreeAlertPreviewTags.SECTION)).let {
        assertEquals("$label: premise: 목록 안 화면에 놓인 미리보기 하나 ${it.map { n -> n.boundsInRoot }}", 1, it.size)
        it.single()
    }
    /** Opens composed inside the settled page's list: a page held off to the side may keep its own. */
    private fun opens(): Int = inList(hasTestTag(FreeAlertPreviewTags.OPEN)).size
    /** Scrolled to, then — with the list and the candidates looked up again — the one on screen inside the settled list. */
    private fun tapInList(tag: String) {
        toInList(hasTestTag(tag))
        val ids = placed(hasTestTag(tag)).map { it.id }
        assertEquals("premise: 목록 안 화면에 놓인 $tag 하나 $ids", 1, ids.size)
        val all = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
        all[all.fetchSemanticsNodes().indexOfFirst { it.id == ids.single() }].performClick()
        compose.waitForIdle()
    }
    /** For the first run's record (X2): every candidate [matcher] picks, with what decides whether it counts. */
    private fun logCandidates(label: String, what: String, matcher: SemanticsMatcher) {
        val listId = list().fetchSemanticsNode().id
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
            val owner = generateSequence(n.parent) { it.parent }.firstOrNull { verticalList.matches(it) }?.id
            Log.i("E5dCandidates", "$label $what id=${n.id} placed=${n.layoutInfo.isPlaced} clip=${n.boundsInRoot} pos=${n.positionInRoot} " +
                "size=${n.size} list=$owner settledList=$listId counts=${owner == listId && onScreen(n)}")
        }
    }
    /** The sheet's header, footer and menu items: no list around them. */
    private fun tapSheet(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    /** A field in the sheet's scrolling body, brought into view; one hidden behind the keyboard would take the click elsewhere. */
    private fun tapField(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        node.performScrollTo()
        compose.waitForIdle()
        val shown = node.fetchSemanticsNode().boundsInWindow
        assertTrue("premise: $tag 가 보인다 $shown ime=${imeShown()}", shown.width > 0f && shown.height > 0f)
        assertTrue("premise: 시트가 열려 있다", sheetOpen())
        node.performClick()
        compose.waitForIdle()
    }
    private fun typeInto(tag: String, text: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(text)
        compose.waitForIdle()
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { !imeShown() }
    }
    private fun closeSheet() { tapSheet(FreeAlertPreviewTags.CLOSE); compose.waitUntil(5_000) { !sheetOpen() } }
    private fun sectionText(): String = descendants(shownTeaser("sectionText"))
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }.joinToString(" ")

    // ---- G17 -------------------------------------------------------------------------------------------------------------

    @Test fun S7E5D_S01_eachBankTabInEachAvailabilityCarriesOneCollapsedTeaser() {
        compose.setContent(screen())
        for (tab in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR)) {
            for (freshness in listOf(FreeSnapshotFreshness.FRESH, FreeSnapshotFreshness.DELAYED, null, FreeSnapshotFreshness.UNAVAILABLE)) {
                val label = "$tab/${freshness ?: "AWAITING"}"
                val quotesHere = freshness == FreeSnapshotFreshness.FRESH || freshness == FreeSnapshotFreshness.DELAYED
                // Out of the composition and back, so the bank page is new and its teaser starts collapsed again.
                update { mounted.value = false }
                assertEquals("premise: $label 화면이 composition 을 떠났다", 0, count(FreeAlertPreviewTags.SECTION))
                update {
                    state.value = stateFor(tab, freshness?.let { snapshot(tab, FreeSnapshotUiState.DEFAULT_PERIOD, 1400.0, 1500.0, basis) }, freshness ?: FreeSnapshotFreshness.FRESH)
                    mounted.value = true
                }
                toTeaser()
                assertTrue("$label: 머리말", sectionText().let { "환율 알림" in it && "미리보기" in it })
                assertEquals("$label: 접힌 채", 0, opens())
                tapInList(FreeAlertPreviewTags.SECTION_TOGGLE)
                assertTrue("$label: 안내", "구독하면 목표 환율 알림을 받을 수 있어요" in sectionText())
                assertEquals("$label: 진입 하나", 1, opens())
                assertOrder(label, hasRates = quotesHere)
                tapInList(FreeAlertPreviewTags.OPEN)
                assertTrue("$label: 열린다", sheetOpen())
                assertEquals("$label: 임계값", if (quotesHere) BankAlertEditor.formatThreshold(1400.0) else "", textOf(AlertAddSheetTags.THRESHOLD))
                closeSheet()
            }
        }
        assertEquals(0, paywalls)
    }

    /**
     * The teaser comes after the last rate row (when there are rates) and before the premium button (X3). For each comparison the
     * list is moved by the teaser's own box against the list's viewport, and both ends are looked up afresh, on screen.
     */
    private fun assertOrder(label: String, hasRates: Boolean) {
        toTeaser()
        if (hasRates) {
            // Only the reference row prints its source; every other row carries its name on its mark (`RateRowGlyph`).
            val lastRow = state.value.rateSections.last().rows.last().label
            val named = hasText(lastRow, substring = true) or hasContentDescription(lastRow, substring = true)
            // The teaser's bottom down to the list's bottom: the row right above it comes into view with it.
            val rows = alignUntil(label, "마지막 시세 행 $lastRow", named) { teaser, view -> teaser.bottom - view.bottom }
            assertTrue("$label: 시세 → 미리보기", rows.maxOf { it.box().bottom } <= shownTeaser(label).box().top)
        }
        // The teaser's top up to the list's top: the premium button right below it comes into view with it.
        val premium = alignUntil(label, "프리미엄 구독", hasText("프리미엄 구독")) { teaser, view -> teaser.top - view.top }
        assertTrue("$label: 미리보기 → 프리미엄 구독", shownTeaser(label).box().bottom <= premium.minOf { it.box().top })
    }

    /**
     * Moves the list by [by] — from the one teaser's unclipped box and the list's viewport — and looks again, up to three times,
     * until [target] has a node on screen in the settled list; those. Failing that, a premise failure: the comparison is never
     * skipped, not even where the list's end stops the move.
     */
    private fun alignUntil(label: String, what: String, target: SemanticsMatcher, by: (teaser: Rect, view: Rect) -> Float): List<SemanticsNode> {
        repeat(3) {
            val dy = by(shownTeaser(label).box(), list().fetchSemanticsNode().boundsInRoot)
            if (dy != 0f) list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
            compose.waitForIdle()
            logCandidates(label, what, target)
            val found = placed(target)
            if (found.isNotEmpty()) {
                shownTeaser(label)
                return found
            }
        }
        throw AssertionError("$label: premise: 정렬 뒤에도 $what 이(가) 목록 안 화면에 없다")
    }

    /**
     * 테더 opened directly — no pager move — carries no teaser anywhere down its list.
     *
     * 뉴스, and the way back through either, are not here: this rule cannot compose the 뉴스 page (its view model comes from
     * Hilt, and the pager prefetches it next to 테더). They are E5d-J journey cases on the installed app and stay PENDING until
     * they run (`e5d_devfix_plan.claude.r2.md` P6); the source gate itself is locked by `S7E5DStructureTest`.
     */
    @Test fun S7E5D_S02_tetherOpenedDirectlyCarriesNoTeaser() {
        state.value = stateFor(FreeTab.TETHER, null, FreeSnapshotFreshness.FRESH)
        compose.setContent(screen())
        compose.waitForIdle()
        // Down to the last item: a teaser would sit right before it.
        toInList(hasText("프리미엄 구독"))
        assertTrue("premise: 목록 끝까지 왔다", compose.onAllNodes(hasText("프리미엄 구독"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals("테더 직접", 0, count(FreeAlertPreviewTags.SECTION))
        assertEquals("premise: 테더 page 그대로", FreeTab.TETHER, state.value.selectedTab)
    }

    @Test fun S7E5D_S03_aSaveAndRestoreCollapsesIt() {
        val restoration = StateRestorationTester(compose)
        state.value = stateFor(FreeTab.USD, snapshot(FreeTab.USD, FreeSnapshotUiState.DEFAULT_PERIOD, 1400.0, 1500.0, basis), FreeSnapshotFreshness.FRESH)
        restoration.setContent(screen())
        toTeaser()
        tapInList(FreeAlertPreviewTags.SECTION_TOGGLE)
        assertEquals(1, opens())
        restoration.emulateSavedInstanceStateRestore()
        toTeaser()
        assertEquals("복원 뒤 접힘", 0, opens())
    }

    /**
     * Scrolled away and back, the teaser is as it was (X1·X5): its page holds the flag, as iOS's view holds its `@State`. Same
     * mounted page, tab and state throughout; no sheet, no fullscreen, no tab change; the toggle is pressed once and never again.
     */
    @Test fun S7E5D_S07_scrollingItAwayAndBackKeepsItOpen() {
        state.value = stateFor(FreeTab.USD, snapshot(FreeTab.USD, FreeSnapshotUiState.DEFAULT_PERIOD, 1400.0, 1500.0, basis), FreeSnapshotFreshness.FRESH)
        compose.setContent(screen())
        compose.waitForIdle()
        toTeaser()
        tapInList(FreeAlertPreviewTags.SECTION_TOGGLE)
        toTeaser()
        assertEquals("premise: 펼쳐 화면 안 OPEN 하나", 1, placed(hasTestTag(FreeAlertPreviewTags.OPEN)).size)
        // Up until no teaser is left anywhere — on screen or composed — the same exposure and virtualisation premise as probe 3 C.
        list().performScrollToIndex(0)
        compose.waitForIdle()
        var tries = 0
        while ((placed(hasTestTag(FreeAlertPreviewTags.SECTION)).isNotEmpty() || count(FreeAlertPreviewTags.SECTION) > 0) && tries < 3) {
            list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -list().fetchSemanticsNode().boundsInRoot.height) }
            compose.waitForIdle()
            tries++
        }
        assertEquals("premise: 화면 안 미리보기 0", 0, placed(hasTestTag(FreeAlertPreviewTags.SECTION)).size)
        assertEquals("premise: 전역 미리보기 0", 0, count(FreeAlertPreviewTags.SECTION))
        assertEquals("premise: 같은 USD", FreeTab.USD, state.value.selectedTab)
        toTeaser()
        assertEquals("돌아와 화면 안 미리보기 하나", 1, placed(hasTestTag(FreeAlertPreviewTags.SECTION)).size)
        assertEquals("돌아와도 펼친 채 — 화면 안 OPEN 하나", 1, placed(hasTestTag(FreeAlertPreviewTags.OPEN)).size)
        assertEquals(0, paywalls)
    }

    // ---- G16 -------------------------------------------------------------------------------------------------------------

    @Test fun S7E5D_S04_whileOpenNothingUpstreamMovesThePreview_andTheNextOpeningTakesTheNewPair() {
        compose.setContent(screen())
        val first = snapshot(FreeTab.USD, FreeSnapshotUiState.DEFAULT_PERIOD, 1400.0, 1500.0, basis)
        update { state.value = stateFor(FreeTab.USD, first, FreeSnapshotFreshness.FRESH) }
        toTeaser()
        tapInList(FreeAlertPreviewTags.SECTION_TOGGLE)
        for ((label, next, expectedThreshold) in listOf(
            Triple("새 snapshot", stateFor(FreeTab.USD, snapshot(FreeTab.USD, FreeSnapshotUiState.DEFAULT_PERIOD, 1410.0, 1510.0, basis + 10.minutes), FreeSnapshotFreshness.FRESH), "1410.00"),
            Triple("다른 기간", stateFor(FreeTab.USD, snapshot(FreeTab.USD, GraphPeriod.ONE_WEEK, 1420.0, 1520.0, basis + 20.minutes), FreeSnapshotFreshness.FRESH, GraphPeriod.ONE_WEEK), "1420.00"),
            Triple("24h 만료", stateFor(FreeTab.USD, first, FreeSnapshotFreshness.UNAVAILABLE), "")
        )) {
            tapInList(FreeAlertPreviewTags.OPEN)
            val quoteShown = textOf(AlertAddSheetTags.CURRENT_RATE)
            tapField(AlertAddSheetTags.bank("hana"))
            typeInto(AlertAddSheetTags.THRESHOLD, "1490")
            tapField(AlertAddSheetTags.CONDITION)
            tapField(AlertAddSheetTags.REPEAT)
            compose.waitUntil(5_000) { count(AlertAddSheetTags.repeatOption(1)) == 1 }
            tapSheet(AlertAddSheetTags.repeatOption(1))
            val edited = listOf(textOf(AlertAddSheetTags.HEADER_BANK), textOf(AlertAddSheetTags.THRESHOLD), textOf(AlertAddSheetTags.CONDITION), textOf(AlertAddSheetTags.REPEAT_LABEL))
            val currentRateBeforeHana = quoteShown
            update { state.value = next }
            compose.waitForIdle()
            assertTrue("$label: 열린 채", sheetOpen())
            assertEquals("$label: 편집값 넷 그대로", edited,
                listOf(textOf(AlertAddSheetTags.HEADER_BANK), textOf(AlertAddSheetTags.THRESHOLD), textOf(AlertAddSheetTags.CONDITION), textOf(AlertAddSheetTags.REPEAT_LABEL)))
            tapField(AlertAddSheetTags.bank("kb"))
            assertEquals("$label: 연 순간의 KB 시세", currentRateBeforeHana, textOf(AlertAddSheetTags.CURRENT_RATE))
            closeSheet()
            tapInList(FreeAlertPreviewTags.OPEN)
            assertEquals("$label: 재open 초기값", listOf("국민은행", expectedThreshold, "한번만"),
                listOf(textOf(AlertAddSheetTags.HEADER_BANK), textOf(AlertAddSheetTags.THRESHOLD), textOf(AlertAddSheetTags.REPEAT_LABEL)))
            closeSheet()
        }
        assertEquals(0, paywalls)
    }

    // ---- G15 (기기 몫) ------------------------------------------------------------------------------------------------------

    /**
     * The neighbouring bank page held half into view by a drag that has not let go: its teaser is there, but it is not the
     * settled tab, so its opener opens nothing. The page's controls are driven by semantics actions so the held drag stays put.
     */
    @Test fun S7E5D_S06_aPageHeldIntoViewByADragCannotOpenThePreview() {
        compose.setContent(screen())
        update { state.value = stateFor(FreeTab.USD, snapshot(FreeTab.USD, FreeSnapshotUiState.DEFAULT_PERIOD, 1400.0, 1500.0, basis), FreeSnapshotFreshness.FRESH) }
        toTeaser()
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val teaser = compose.onNodeWithTag(FreeAlertPreviewTags.SECTION, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("premise: 활성 USD teaser 가 보인다 $teaser", teaser.width > 0f && teaser.height > 0f)
        // Root coordinates throughout: the drag starts on the teaser's left half, away from the chart.
        val start = androidx.compose.ui.geometry.Offset(teaser.left + teaser.width * 0.25f, teaser.center.y)
        fun rightHalf(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).let { all ->
            val nodes = all.fetchSemanticsNodes()
            nodes.indices.filter { nodes[it].boundsInRoot.left > width / 2 }.map { all[it] }
        }
        fun toggles() = compose.onAllNodes(hasTestTag(FreeAlertPreviewTags.SECTION_TOGGLE), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        var held = false
        try {
            compose.onRoot().performTouchInput {
                down(start)
                moveBy(androidx.compose.ui.geometry.Offset(-width * 0.4f, 0f), delayMillis = 400)
            }
            held = true
            val cameIn = runCatching { compose.waitUntil(5_000) { rightHalf(FreeAlertPreviewTags.SECTION_TOGGLE).isNotEmpty() } }.isSuccess
            assertTrue("premise: 끌기가 이웃 JPY page 를 오른쪽에 들였다 start=$start toggles=${toggles()} tab=${state.value.selectedTab}", cameIn)
            assertEquals("premise: 놓기 전 settled tab 은 아직 USD", FreeTab.USD, state.value.selectedTab)
            rightHalf(FreeAlertPreviewTags.SECTION_TOGGLE).single().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            rightHalf(FreeAlertPreviewTags.OPEN).single().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals("끌어 보인 이웃 page 가 열었다", 0, count(FreeAlertPreviewTags.SHEET))
            compose.onRoot().performTouchInput { advanceEventTime(400); up() }
            held = false
            compose.waitForIdle()
            assertEquals("놓은 뒤에도", 0, count(FreeAlertPreviewTags.SHEET))
            assertEquals(0, paywalls)
        } finally {
            // Release a pointer a failed check left down; a failure here does not replace the original one.
            if (held) runCatching { compose.onRoot().performTouchInput { up() } }
        }
    }

    @Test fun S7E5D_S05_theCtaFromTheScreenReachesOnSubscribeOnce() {
        compose.setContent(screen())
        update { state.value = stateFor(FreeTab.JPY, snapshot(FreeTab.JPY, FreeSnapshotUiState.DEFAULT_PERIOD, 950.0, 951.0, basis), FreeSnapshotFreshness.DELAYED) }
        toTeaser()
        tapInList(FreeAlertPreviewTags.SECTION_TOGGLE)
        tapInList(FreeAlertPreviewTags.OPEN)
        tapSheet(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { !sheetOpen() }
        compose.waitForIdle()
        assertEquals(1, paywalls)
    }
}
