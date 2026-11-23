package com.jay.fxi.ui.free

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
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
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.SourceRate
import com.jay.fxi.ui.components.SubscribeCTATags
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S8-7 (`R4c/S8/s8_7_contract.codex.r2.md` §4 D01–D07) on the real [FreeSnapshotScreen], with states made by the real
 * [FreeSnapshotUiState.from] over cached snapshots: one collapsed tether teaser on the selected tether page only; a modal with the
 * preview's fields and footer and nothing else; the five policy exchanges; a draft frozen against every upstream change; a trial
 * without quotes; the teaser's flag owned by the page; every way out discards, and the CTA opens the paywall once with no OS
 * permission prompt, no posted notification and no change of the notification permission.
 */
@RunWith(AndroidJUnit4::class)
class S87PreviewDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val basis = Instant.parse("2026-11-23T01:30:00Z")
    private var paywalls = 0
    private val state = mutableStateOf(FreeSnapshotUiState(uid = "u1"))
    private val mounted = mutableStateOf(true)
    private var tetherQuotes = mapOf("upbit" to 1432.5, "bithumb" to 1431.0, "korbit" to 1433.4)
    private var tetherFreshness = FreeSnapshotFreshness.FRESH
    private var tetherPreferences: Map<RateRowList, RateRowPreference> = emptyMap()

    private fun tetherSnapshot(period: GraphPeriod, at: Instant) = FreeSnapshot(
        tab = "tether", period = period, asOf = at, generatedAt = at + 7.minutes, refreshNotBefore = at + 48.hours,
        rate = FreeRate.Grouped("usdt-krw", tetherQuotes.map { (s, r) -> SourceRate(s, "usdt-krw", r, at - 1.hours) },
            listOf(ExchangeRate("usd-krw", "hana", 1400.0, at - 1.hours)), null),
        graph = FreeGraph(null, listOf(FreeGraphSeries("bithumb.usdt-krw", listOf(FreeGraphPoint(at - 2.hours, 1.0, null, null), FreeGraphPoint(at, 2.0, null, null)))))
    )

    private fun usdSnapshot(period: GraphPeriod, at: Instant) = FreeSnapshot(
        tab = "usd", period = period, asOf = at, generatedAt = at + 7.minutes, refreshNotBefore = at + 48.hours,
        rate = FreeRate.Flat("usd-krw", listOf(ExchangeRate("usd-krw", "kb", 1400.0, at - 1.hours))),
        graph = FreeGraph(null, listOf(FreeGraphSeries("investing.usd", listOf(FreeGraphPoint(at - 2.hours, 1.0, null, null), FreeGraphPoint(at, 2.0, null, null)))))
    )

    private fun stateFor(tab: FreeTab, period: GraphPeriod = FreeSnapshotUiState.DEFAULT_PERIOD, at: Instant = basis): FreeSnapshotUiState {
        val snapshot = when (tab) {
            FreeTab.TETHER -> tetherSnapshot(period, at)
            FreeTab.NEWS -> null
            else -> usdSnapshot(period, at).copy(tab = tab.serverTab!!)
        }
        val freshness = if (tab == FreeTab.TETHER) tetherFreshness else FreeSnapshotFreshness.FRESH
        val entries = snapshot?.let { mapOf(FreeSnapshotKey(it.tab, it.period) to FreeSnapshotEntry(it, freshness)) }.orEmpty()
        return FreeSnapshotUiState.from("u1", tab, period, FreeSnapshotReadState("u1", entries), emptySet(),
            if (tab == FreeTab.TETHER) tetherPreferences else emptyMap())
    }

    private fun screen() = @androidx.compose.runtime.Composable {
        if (mounted.value) FreeSnapshotScreen(
            state = state.value,
            onSelectTab = { tab -> state.value = stateFor(tab) },
            onSelectPeriod = { period -> state.value = stateFor(state.value.selectedTab!!, period) },
            onToggleSeries = {},
            onApplyRowPreference = { _, _, _, _ -> },
            onSignOut = {},
            onSubscribe = { paywalls += 1 }
        )
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun sheetOpen() = count(FreeSourcePreviewTags.SHEET) == 1
    private fun SemanticsNode.own() = (config.getOrNull(SemanticsProperties.EditableText)?.text
        ?: config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
    private fun textOf(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().own()
        .ifEmpty { compose.onNodeWithTag(tag, useUnmergedTree = false).fetchSemanticsNode().own() }
    private fun check(message: String, ok: Boolean) {
        if (!ok) throw AssertionError(message)
    }

    /** [tag] inside the open sheet. */
    private fun inSheet(tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag(FreeSourcePreviewTags.SHEET))

    /** The one node with [tag]: inside the open sheet, and nowhere else in the tree; otherwise the case's [message]. */
    private fun sheetNode(tag: String, message: String): SemanticsNode {
        val inside = compose.onAllNodes(inSheet(tag), useUnmergedTree = true).fetchSemanticsNodes()
        if (inside.size != 1 || count(tag) != 1) throw AssertionError(message)
        return inside.single()
    }

    /** The sheet's one editable field with [tag]: its edited text, and nothing else counts as one. */
    private fun sheetEditable(tag: String, message: String): String =
        sheetNode(tag, message).config.getOrNull(SemanticsProperties.EditableText)?.text ?: throw AssertionError(message)

    /** The sheet's one node with [tag]: its own words, or its merged words when it has none of its own. */
    private fun sheetText(tag: String, message: String): String = sheetNode(tag, message).own()
        .ifEmpty { compose.onAllNodes(inSheet(tag), useUnmergedTree = false).fetchSemanticsNodes().singleOrNull()?.own() ?: throw AssertionError(message) }

    /** Every word under the sheet's one node with [tag]. */
    private fun sheetLabels(tag: String, message: String): List<String> =
        generateSequence(listOf(sheetNode(tag, message))) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
            .map { it.own() }.filter { it.isNotEmpty() }.toList()

    /** Presses the sheet's one node with [tag], once it is known to be that node and clickable, scrolled into the sheet's view first. */
    private fun pressInSheet(tag: String, message: String) {
        if (sheetNode(tag, message).config.getOrNull(SemanticsActions.OnClick) == null) throw AssertionError(message)
        compose.onNode(inSheet(tag), useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        compose.onNode(inSheet(tag), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /**
     * Scrolls whichever vertical list holds [tag] to it; true when it is then composed. Only vertical lists: scrolling the pager
     * would compose 뉴스, whose view model needs a Hilt activity this rig does not have.
     */
    private fun scrollTo(tag: String): Boolean {
        val lists = compose.onAllNodes(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true)
        for (i in 0 until lists.fetchSemanticsNodes().size) {
            try { lists[i].performScrollToNode(hasTestTag(tag)); compose.waitForIdle(); if (count(tag) > 0) return true } catch (_: AssertionError) { }
        }
        return count(tag) > 0
    }

    private val verticalList = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    /** Placed in the last layout, with a size, inside the root across: a page the pager holds off to the side reports a zero box. */
    private fun onScreen(node: SemanticsNode): Boolean {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val box = node.boundsInRoot
        return node.layoutInfo.isPlaced && box.width > 0f && box.height > 0f && box.left >= root.left && box.right <= root.right
    }

    /** The settled page's list: exactly one vertical list on screen (the pager itself scrolls sideways). */
    private fun list(): SemanticsNodeInteraction {
        val all = compose.onAllNodes(verticalList, useUnmergedTree = true)
        val nodes = all.fetchSemanticsNodes()
        val shown = nodes.indices.filter { onScreen(nodes[it]) }
        if (shown.size != 1) throw AssertionError("premise: 보이는 세로 목록이 하나다 ${nodes.map { it.boundsInRoot }}")
        return all[shown.single()]
    }

    private fun imeShown(): Boolean {
        var shown = false
        compose.activityRule.scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return shown
    }

    /** A Back while the keyboard is up only closes the keyboard, so every way out starts with it closed. */
    private fun closeKeyboard() {
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { !imeShown() }
        if (imeShown()) throw AssertionError("premise: 키보드가 닫혔다")
    }

    /**
     * A tap on the scrim above the sheet. The tagged node's box is the sheet's whole window, not where the sheet is drawn, so the
     * sheet's top is estimated from its topmost placed descendant, the header row: the sheet's own handle above it is a plain Box
     * (8dp + 4dp + 8dp, no semantics), and 22dp leaves 2dp to spare. The case is judged by the sheet actually closing and the draft
     * being gone, not by this estimate.
     */
    private fun tapScrim() {
        val sheet = compose.onNodeWithTag(FreeSourcePreviewTags.SHEET, useUnmergedTree = true).fetchSemanticsNode()
        val window = sheet.boundsInRoot
        // Wrappers as tall as the window are not where the sheet is drawn.
        val placed = generateSequence(sheet.children) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
            .filter { it.layoutInfo.isPlaced && it.size.height > 0 && it.size.height < window.height * 0.9f }.toList()
        val inner = placed.minOfOrNull { it.positionInRoot.y } ?: throw AssertionError("premise: 시트 안에 놓인 node 가 있다")
        val handle = with(compose.density) { 22.dp.toPx() }
        val margin = with(compose.density) { 48.dp.toPx() }
        val sheetTop = inner - handle
        if (sheetTop < margin) throw AssertionError("premise: 시트 위에 scrim 이 보인다 window=$window inner=$inner sheetTop=$sheetTop " +
            "top=${placed.sortedBy { it.positionInRoot.y }.take(5).map { "${it.positionInRoot.y}/${it.size.height}" }}")
        compose.onNodeWithTag(FreeSourcePreviewTags.SHEET, useUnmergedTree = true)
            .performTouchInput { click(Offset(width / 2f, sheetTop / 2f - sheet.boundsInRoot.top)) }
        compose.waitForIdle()
    }

    /** Every app-local store file — DataStore and SharedPreferences — by path, with its SHA-256. The remembered source lives here. */
    private fun stores(context: Context): Map<String, String> {
        val data = File(context.applicationInfo.dataDir)
        return listOf(File(context.filesDir, "datastore"), File(data, "shared_prefs")).flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            .associate { f -> f.relativeTo(data).path to MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) } }
    }

    private fun showTether() {
        state.value = stateFor(FreeTab.TETHER)
        compose.setContent(screen())
        compose.waitForIdle()
    }

    private fun openTrial(message: String = "premise: 테더 체험 시트를 연다") {
        if (!scrollTo(FreeSourcePreviewTags.SECTION)) throw AssertionError(message)
        if (count(FreeSourcePreviewTags.OPEN) == 0) {
            if (count(FreeSourcePreviewTags.TOGGLE) != 1) throw AssertionError(message)
            tap(FreeSourcePreviewTags.TOGGLE)
        }
        if (count(FreeSourcePreviewTags.OPEN) != 1) throw AssertionError(message)
        val open = compose.onNodeWithTag(FreeSourcePreviewTags.OPEN, useUnmergedTree = true)
        open.performScrollTo()
        compose.waitForIdle()
        if (!open.isDisplayed()) throw AssertionError(message)
        open.performClick()
        try {
            compose.waitUntil(5_000) { sheetOpen() }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(message, failure)
        }
    }

    private fun closeBy(tag: String = FreeSourcePreviewTags.CLOSE) { tap(tag); compose.waitUntil(5_000) { !sheetOpen() } }

    /** Every text and every tag in the modal, unmerged, wherever it sits in the sheet's scroll. */
    private fun modalTexts(): List<String> = compose.onAllNodes(hasTestTag(FreeSourcePreviewTags.SHEET), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { root -> generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten().toList() }
        .map { it.own() }.filter { it.isNotEmpty() }

    @Test fun D01_selectedTetherOwnsOneSection() {
        showTether()
        val tether = scrollTo(FreeSourcePreviewTags.SECTION) && count(FreeSourcePreviewTags.SECTION) == 1 && count(FreeSourcePreviewTags.OPEN) == 0
        val header = tether && modalLessText(FreeSourcePreviewTags.SECTION).containsAll(listOf("테더 알림", "미리보기"))
        tap(FreeSourcePreviewTags.TOGGLE)
        val expanded = count(FreeSourcePreviewTags.OPEN) == 1 && modalLessText(FreeSourcePreviewTags.SECTION)
            .containsAll(listOf("구독하면 거래소별 목표 시세 알림을 받을 수 있어요", "알림 추가 (미리보기)"))
        // 테더 → 달러 with the clock held: the tether page still lies across the screen's left edge but is no longer the selected
        // page, and it has no section. This direction prefetches 엔, not 뉴스.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = stateFor(FreeTab.USD) }
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        fun straddling() = compose.onAllNodes(verticalList, useUnmergedTree = true).fetchSemanticsNodes()
            .any { it.layoutInfo.isPlaced && it.positionInRoot.x < 0f && it.positionInRoot.x + it.size.width > width * 0.2f }
        var frames = 0
        while (!straddling() && frames < 30) { compose.mainClock.advanceTimeByFrame(); frames++ }
        if (!straddling()) throw AssertionError("premise: 테더 페이지가 화면 왼쪽에 걸쳐 있다 frames=$frames")
        val inactive = count(FreeSourcePreviewTags.SECTION) == 0 && count(FreeSourcePreviewTags.OPEN) == 0
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // 뉴스 is not composed here (its view model needs Hilt); its page never renders FreeSnapshotTabContent, and R01/W01 lock
        // the tether guard.
        val others = listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR).map { t ->
            update { state.value = stateFor(t) }
            scrollTo(FreeSourcePreviewTags.SECTION)
            count(FreeSourcePreviewTags.SECTION) == 0
        }
        check("선택 테더의 섹션", tether && header && expanded && inactive && others.all { it })
    }

    private fun modalLessText(tag: String): List<String> = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { root -> generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten().toList() }
        .map { it.own() }.filter { it.isNotEmpty() }

    @Test fun D02_modalHasOnlyPreviewContent() {
        showTether()
        openTrial()
        val message = "무료 모달의 허용 문구와 동작"
        val field = hasTestTag(FreeSourcePreviewTags.THRESHOLD) and hasAnyAncestor(hasTestTag(FreeSourcePreviewTags.SHEET))
        val fields = compose.onAllNodes(field, useUnmergedTree = true).fetchSemanticsNodes()
        check(message, fields.size == 1 && count(FreeSourcePreviewTags.THRESHOLD) == 1 &&
            fields.single().config.getOrNull(SemanticsActions.SetText) != null)
        compose.onNode(field, useUnmergedTree = true).performTextReplacement("99999999")
        compose.waitForIdle()
        closeKeyboard()
        val texts = modalTexts().toSet()
        val required = setOf("테더 알림 추가 (미리보기)", "닫기", "거래소", "업비트", "빗썸", "코인원", "코빗", "고팍스", "현재 시세 1433원",
            "설정 가능 범위 720 ~ 2150원", "알림 받을 시세", "99999999", "원", "▽ 이하", "알림 방식", "조건 충족 시 1회만 발송", "한번만",
            "1분", "5분", "10분", "30분", "1시간", "2시간", "4시간", "6시간", "12시간", "1일", "구독 혜택 보기", "알림 저장과 발송은 구독 후 사용할 수 있어요")
        val forbiddenWords = listOf("저장", "중복", "최대", "추가할 수", "범위를 벗어", "발송됨", "기록", "KRX", "달러선물")
        val unexpected = texts.filter { it !in required }
        val forbidden = texts.filter { t -> t != "알림 저장과 발송은 구독 후 사용할 수 있어요" && forbiddenWords.any { it in t } }
        check(message, texts.containsAll(required) && unexpected.isEmpty() && forbidden.isEmpty())
        val intervals = listOf(60 to "1분", 300 to "5분", 600 to "10분", 1800 to "30분", 3600 to "1시간",
            7200 to "2시간", 14400 to "4시간", 21600 to "6시간", 43200 to "12시간", 86400 to "1일")
        val pickers = listOf("upbit", "bithumb", "coinone", "korbit", "gopax").map(FreeSourcePreviewTags::source)
        val allowed = pickers + intervals.map { FreeSourcePreviewTags.repeat(it.first) } + listOf(
            FreeSourcePreviewTags.REPEAT_ONCE, FreeSourcePreviewTags.CONDITION, FreeSourcePreviewTags.THRESHOLD,
            FreeSourcePreviewTags.CLOSE, SubscribeCTATags.BUTTON)
        fun inspect(line: String) {
            val root = compose.onAllNodes(hasTestTag(FreeSourcePreviewTags.SHEET), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
                ?: throw AssertionError(message)
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            val nodes = walk(root)
            val words = nodes.flatMap { n -> listOf(n.own()).filter(String::isNotEmpty) + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            val want = required - "조건 충족 시 1회만 발송" + line
            check(message, want.all { w -> words.count { it == w } == 1 } && words.all { it in want })
            val title = nodes.singleOrNull { it.own() == "알림 방식" } ?: throw AssertionError(message)
            val desc = nodes.singleOrNull { it.own() == line } ?: throw AssertionError(message)
            val once = nodes.singleOrNull { it.own() == "한번만" } ?: throw AssertionError(message)
            check(message, title.positionInRoot.y < desc.positionInRoot.y && desc.positionInRoot.y < once.positionInRoot.y)
            val actions = nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            check(message, actions.map { it.config.getOrNull(SemanticsProperties.TestTag) }.groupingBy { it }.eachCount() ==
                allowed.associateWith { 1 } && actions.none { SemanticsProperties.Disabled in it.config })
            check(message, count(SubscribeCTATags.NOTE) == 1 && count(FreeSourcePreviewTags.RANGE) == 1)
        }
        inspect("조건 충족 시 1회만 발송")
        for ((seconds, label) in intervals) {
            val tag = FreeSourcePreviewTags.repeat(seconds)
            check(message, count(tag) == 1)
            compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            inspect("조건 충족 동안 ${label}마다 발송")
        }
        compose.onNodeWithTag(FreeSourcePreviewTags.REPEAT_ONCE, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        inspect("조건 충족 시 1회만 발송")
    }

    @Test fun D03_pickerUsesFivePolicySources() {
        showTether()
        openTrial()
        val message = "거래소 선택지 다섯 개"
        val codes = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")
        val prefix = FreeSourcePreviewTags.source("")
        fun pickers(): Boolean {
            val nodes = compose.onAllNodes(SemanticsMatcher("source picker") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
            } and hasAnyAncestor(hasTestTag(FreeSourcePreviewTags.SHEET)), useUnmergedTree = true).fetchSemanticsNodes()
            val tags = nodes.map { it.config.getOrNull(SemanticsProperties.TestTag) }
            return tags.size == codes.size && tags.toSet() == codes.map(FreeSourcePreviewTags::source).toSet() &&
                nodes.all { it.config.getOrNull(SemanticsActions.OnClick) != null }
        }
        val present = pickers()
        check(message, present)
        val names = listOf("업비트", "빗썸", "코인원", "코빗", "고팍스")
        check(message, codes.zip(names).all { (code, name) -> sheetLabels(FreeSourcePreviewTags.source(code), message) == listOf(name) })
        val positions = codes.map { sheetNode(FreeSourcePreviewTags.source(it), message).positionInRoot }
        val ordered = positions.zipWithNext().all { (a, b) -> a.y < b.y || (a.y == b.y && a.x < b.x) }
        fun price() = sheetEditable(FreeSourcePreviewTags.THRESHOLD, message)
        fun quote() = sheetText(FreeSourcePreviewTags.QUOTE, message)
        pressInSheet(FreeSourcePreviewTags.source("bithumb"), message)
        val bithumb = price() == "1431" && quote() == "현재 시세 1431원"
        pressInSheet(FreeSourcePreviewTags.source("coinone"), message)
        val coinone = price() == "1431" && quote() == "현재 시세를 확인할 수 없습니다" && count(FreeSourcePreviewTags.RANGE) == 0
        pressInSheet(FreeSourcePreviewTags.source("korbit"), message)
        val korbit = price() == "1433"
        pressInSheet(FreeSourcePreviewTags.source("gopax"), message)
        val gopax = quote() == "현재 시세를 확인할 수 없습니다" && count(FreeSourcePreviewTags.RANGE) == 0
        pressInSheet(FreeSourcePreviewTags.source("upbit"), message)
        check(message, present && ordered && bithumb && coinone && korbit && gopax && quote() == "현재 시세 1433원")
        closeBy()
        tetherQuotes = mapOf("upbit" to 1401.0, "bithumb" to 1402.0, "coinone" to 1403.0, "korbit" to 1404.0, "gopax" to 1405.0)
        update { state.value = stateFor(FreeTab.TETHER, at = basis + 1.hours) }
        openTrial()
        check(message, pickers())
        val wired = codes.zip(listOf("1401", "1402", "1403", "1404", "1405")).map { (code, value) ->
            pressInSheet(FreeSourcePreviewTags.source(code), message)
            quote() == "현재 시세 ${value}원" && price() == value
        }
        check(message, wired.size == 5 && wired.all { it })
    }

    @Test fun D04_openDraftSurvivesUpdates() {
        showTether()
        openTrial()
        val message = "premise: 갱신 전 초안 설정"
        pressInSheet(FreeSourcePreviewTags.source("bithumb"), message)
        pressInSheet(FreeSourcePreviewTags.CONDITION, message)
        compose.onNode(inSheet(FreeSourcePreviewTags.THRESHOLD), useUnmergedTree = true).performTextReplacement("1500")
        compose.waitForIdle()
        closeKeyboard()
        pressInSheet(FreeSourcePreviewTags.repeat(300), message)
        fun same() = sheetOpen() && count(FreeSourcePreviewTags.THRESHOLD) == 1 && count(FreeSourcePreviewTags.QUOTE) == 1 &&
            count(FreeSourcePreviewTags.CONDITION) == 1 && count(FreeSourcePreviewTags.RANGE) == 1 &&
            textOf(FreeSourcePreviewTags.RANGE) == "설정 가능 범위 720 ~ 2150원" && textOf(FreeSourcePreviewTags.THRESHOLD) == "1500" &&
            textOf(FreeSourcePreviewTags.QUOTE) == "현재 시세 1431원" &&
            textOf(FreeSourcePreviewTags.CONDITION) == "△ 이상" && modalTexts().contains("조건 충족 동안 5분마다 발송")
        check(message, same())
        update { state.value = stateFor(FreeTab.TETHER, GraphPeriod.entries.first { it != FreeSnapshotUiState.DEFAULT_PERIOD }) }
        check("열린 입력과 시세 고정", same())
        tetherQuotes = mapOf("upbit" to 1500.0, "bithumb" to 1499.0)
        update { state.value = stateFor(FreeTab.TETHER, at = basis + 1.hours) }
        check("열린 입력과 시세 고정", same())
        tetherPreferences = mapOf(RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = setOf("bithumb")))
        update { state.value = stateFor(FreeTab.TETHER, at = basis + 1.hours) }
        check("열린 입력과 시세 고정", same())
        tetherFreshness = FreeSnapshotFreshness.UNAVAILABLE
        update { state.value = stateFor(FreeTab.TETHER, at = basis + 1.hours) }
        check("열린 입력과 시세 고정", same())
        closeBy()
        tetherFreshness = FreeSnapshotFreshness.FRESH
        update { state.value = stateFor(FreeTab.TETHER, at = basis + 2.hours) }
        openTrial("열린 입력과 시세 고정")
        val fresh = textOf(FreeSourcePreviewTags.THRESHOLD) == "1500" && textOf(FreeSourcePreviewTags.QUOTE) == "현재 시세 1500원" &&
            textOf(FreeSourcePreviewTags.CONDITION) == "▽ 이하" && modalTexts().contains("조건 충족 시 1회만 발송")
        check("열린 입력과 시세 고정", fresh)
    }

    @Test fun D05_missingQuoteStillAllowsTrial() {
        showTether()
        for (waiting in listOf(true, false)) {
        if (waiting) update { state.value = FreeSnapshotUiState(uid = "u1", selectedTab = FreeTab.TETHER) }
        else {
            tetherFreshness = FreeSnapshotFreshness.UNAVAILABLE
            update { state.value = stateFor(FreeTab.TETHER) }
        }
        paywalls = 0
        openTrial("무시세 체험 가능")
        val blank = textOf(FreeSourcePreviewTags.THRESHOLD) == "" && textOf(FreeSourcePreviewTags.QUOTE) == "현재 시세를 확인할 수 없습니다" &&
            count(FreeSourcePreviewTags.RANGE) == 0
        tap(FreeSourcePreviewTags.source("gopax"))
        compose.onNodeWithTag(FreeSourcePreviewTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1400")
        compose.waitForIdle()
        closeKeyboard()
        val typed = textOf(FreeSourcePreviewTags.THRESHOLD) == "1400"
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { paywalls == 1 }
        compose.waitForIdle()
        openTrial("무시세 체험 가능")
        val again = textOf(FreeSourcePreviewTags.THRESHOLD) == ""
        check("무시세 체험 가능", blank && typed && paywalls == 1 && again)
        closeBy()
        }
    }

    /**
     * The teaser's flag belongs to the page: scrolled out of the list and back it is still open, and so after the rows change; the
     * page disposed and mounted again, or a save-and-restore, collapse it. 테더 left for another tab and taken back is not here:
     * the pager going onto 테더 prefetches 뉴스, whose view model needs a Hilt activity this rig does not have.
     */
    @Test fun D06_expansionBelongsToPage() {
        val restoration = StateRestorationTester(compose)
        state.value = stateFor(FreeTab.TETHER)
        restoration.setContent(screen())
        compose.waitForIdle()
        scrollTo(FreeSourcePreviewTags.SECTION)
        tap(FreeSourcePreviewTags.TOGGLE)
        val open = count(FreeSourcePreviewTags.OPEN) == 1
        list().performScrollToIndex(0)
        compose.waitForIdle()
        var tries = 0
        while (count(FreeSourcePreviewTags.SECTION) > 0 && tries < 3) {
            list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -list().fetchSemanticsNode().boundsInRoot.height) }
            compose.waitForIdle()
            tries++
        }
        if (count(FreeSourcePreviewTags.SECTION) != 0) throw AssertionError("premise: section item 이 목록에서 폐기됐다")
        scrollTo(FreeSourcePreviewTags.SECTION)
        val keptAfterScroll = count(FreeSourcePreviewTags.OPEN) == 1
        tetherQuotes = mapOf("upbit" to 1432.5)
        update { state.value = stateFor(FreeTab.TETHER, at = basis + 1.hours) }
        scrollTo(FreeSourcePreviewTags.SECTION)
        val keptOnRows = count(FreeSourcePreviewTags.OPEN) == 1
        update { mounted.value = false }
        if (count(FreeSourcePreviewTags.SECTION) != 0) throw AssertionError("premise: 페이지가 폐기됐다")
        update { mounted.value = true }
        val collapsedAfterRemount = scrollTo(FreeSourcePreviewTags.SECTION) && count(FreeSourcePreviewTags.SECTION) == 1 &&
            count(FreeSourcePreviewTags.OPEN) == 0
        check("페이지 수명의 펼침 상태", collapsedAfterRemount)
        tap(FreeSourcePreviewTags.TOGGLE)
        if (count(FreeSourcePreviewTags.OPEN) != 1) throw AssertionError("premise: 다시 펼쳤다")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        val collapsedAfterRestore = scrollTo(FreeSourcePreviewTags.SECTION) && count(FreeSourcePreviewTags.SECTION) == 1 &&
            count(FreeSourcePreviewTags.OPEN) == 0
        check("페이지 수명의 펼침 상태", open && keptAfterScroll && keptOnRows && collapsedAfterRemount && collapsedAfterRestore)
    }

    /** What D07 compares at each point: the permission UI, the permission, the posted notifications by identity, the store files. */
    private data class Observation(val prompt: Boolean, val permission: Int, val posted: Set<String>, val stores: Map<String, String>)

    @Test fun D07_closePathsDiscardWithoutEffects() {
        val message = "닫기 경로의 폐기와 무발송"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val manager = context.getSystemService(NotificationManager::class.java)
        val permissionBefore = permissionState(context)
        if (Build.VERSION.SDK_INT >= 33 && permissionBefore != PackageManager.PERMISSION_DENIED)
            throw AssertionError("premise: POST_NOTIFICATIONS 미허용")
        fun posted(): Set<String> = manager.activeNotifications.map { "${it.id}|${it.tag}|${it.key}" }.toSet()
        fun observe(point: String): Observation {
            val now = Observation(promptShown(device), permissionState(context), posted(), stores(context))
            // One short line per point (a logcat line is cut near 4 KB): the store files as their count and one digest.
            val digest = MessageDigest.getInstance("SHA-256").digest(now.stores.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
                .toByteArray()).joinToString("") { "%02x".format(it) }
            Log.i("S87D07", "point=$point api=${Build.VERSION.SDK_INT} prompt=${now.prompt} " +
                "permission=${if (Build.VERSION.SDK_INT >= 33) now.permission.toString() else "constant<33"} posted=${now.posted.sorted()} " +
                "files=${now.stores.size} storesDigest=$digest")
            now.stores.toSortedMap().forEach { (path, hash) ->
                val record = if (point == "start") "file $path $hash" else "snapshot=$point file=$path sha256=$hash"
                Log.i("S87D07", record)
            }
            return now
        }
        val start = observe("start")
        if (start.prompt) throw AssertionError("premise: 시작 시 권한 UI 없음")
        showTether()
        val discards = mutableListOf<Boolean>()
        for (way in listOf("close", "back", "scrim", "swipe")) {
            openTrial()
            if (sheetNode(FreeSourcePreviewTags.THRESHOLD, message).config.getOrNull(SemanticsActions.SetText) == null) throw AssertionError(message)
            compose.onNode(inSheet(FreeSourcePreviewTags.THRESHOLD), useUnmergedTree = true).performTextReplacement("1999")
            compose.waitForIdle()
            closeKeyboard()
            if (sheetEditable(FreeSourcePreviewTags.THRESHOLD, message) != "1999") throw AssertionError(message)
            when (way) {
                "close" -> closeBy()
                "back" -> { Espresso.pressBack(); compose.waitUntil(5_000) { !sheetOpen() } }
                "scrim" -> { tapScrim(); compose.waitUntil(5_000) { !sheetOpen() } }
                "swipe" -> {
                    compose.onNodeWithTag(FreeSourcePreviewTags.SHEET, useUnmergedTree = true).performTouchInput { swipeDown() }
                    compose.waitUntil(5_000) { !sheetOpen() }
                }
            }
            discards += observe("after $way") == start
            openTrial(message)
            discards += sheetEditable(FreeSourcePreviewTags.THRESHOLD, message) == "1433" && paywalls == 0
            closeBy()
        }
        openTrial()
        tap(SubscribeCTATags.BUTTON)
        compose.waitUntil(5_000) { paywalls == 1 }
        compose.waitForIdle()
        val afterCta = observe("after cta")
        check(message, discards.size == 8 && discards.all { it } && paywalls == 1 && afterCta == start)
    }

    private fun promptShown(device: UiDevice): Boolean =
        device.findObject(By.pkg("com.google.android.permissioncontroller")) != null ||
            device.findObject(By.pkg("com.android.permissioncontroller")) != null

    private fun permissionState(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 33) context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) else PackageManager.PERMISSION_GRANTED
}
