package com.jay.fxi.ui.free

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.jay.fxi.ui.theme.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

@RunWith(AndroidJUnit4::class)
class S96PreviewDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var kind = "absolute"
    private fun tag(part: String) = comparisonPreviewTag(kind,part)
    private var reference: Double? = 1400.0
    private fun awaitObservation(test: () -> Boolean) {
        var frames = 0
        while (!test() && frames++ < 150) compose.mainClock.advanceTimeByFrame()
        if (!test()) throw AssertionError("마지막 관측: sheet=${sheetOpen()} paywalls=$paywalls")
    }
    private val basis = Instant.parse("2026-11-23T01:30:00Z")
    private var paywalls = 0
    private val state = mutableStateOf(FreeSnapshotUiState(uid = "u1"))
    private val mounted = mutableStateOf(true)
    private var tetherQuotes = mapOf("upbit" to 1402.0, "bithumb" to 1390.0, "korbit" to 1433.4)
    private var tetherFreshness = FreeSnapshotFreshness.FRESH
    private var tetherPreferences: Map<RateRowList, RateRowPreference> = emptyMap()

    private fun tetherSnapshot(period: GraphPeriod, at: Instant) = FreeSnapshot(
        tab = "tether", period = period, asOf = at, generatedAt = at + 7.minutes, refreshNotBefore = at + 48.hours,
        rate = FreeRate.Grouped("usdt-krw", tetherQuotes.map { (s, r) -> SourceRate(s, "usdt-krw", r, at - 1.hours) },
            listOf(ExchangeRate("usd-krw", "hana", 1400.0, at - 1.hours)), reference?.let { ExchangeRate("usd-krw","investing",it,at) }),
        graph = FreeGraph(null, listOf(FreeGraphSeries("bithumb.usdt-krw", listOf(FreeGraphPoint(at - 2.hours, 1.0, null, null), FreeGraphPoint(at, 2.0, null, null)))))
    )

    private fun fxSnapshot(tab: FreeTab, period: GraphPeriod, at: Instant) = FreeSnapshot(
        tab = tab.serverTab!!, period = period, asOf = at, generatedAt = at + 7.minutes, refreshNotBefore = at + 48.hours,
        rate = FreeRate.Flat("${tab.serverTab}-krw", listOf(ExchangeRate("${tab.serverTab}-krw", "kb", 1401.5, at),ExchangeRate("${tab.serverTab}-krw","investing",1400.0,at))),
        graph = FreeGraph(null, listOf(FreeGraphSeries("investing.usd", listOf(FreeGraphPoint(at - 2.hours, 1.0, null, null), FreeGraphPoint(at, 2.0, null, null)))))
    )

    private fun stateFor(tab: FreeTab, period: GraphPeriod = FreeSnapshotUiState.DEFAULT_PERIOD, at: Instant = basis): FreeSnapshotUiState {
        val snapshot = when (tab) {
            FreeTab.TETHER -> tetherSnapshot(period, at)
            FreeTab.NEWS -> null
            else -> fxSnapshot(tab,period,at)
        }
        val freshness = if (tab == FreeTab.TETHER) tetherFreshness else FreeSnapshotFreshness.FRESH
        val entries = snapshot?.let { mapOf(FreeSnapshotKey(it.tab, it.period) to FreeSnapshotEntry(it, freshness)) }.orEmpty()
        return FreeSnapshotUiState.from("u1", tab, period, FreeSnapshotReadState("u1", entries), emptySet(),
            if (tab == FreeTab.TETHER) tetherPreferences else emptyMap())
    }

    private var rendered: FreeSnapshotUiState? = null
    private fun screen() = @androidx.compose.runtime.Composable {
        val frame = state.value
        val visible = mounted.value
        androidx.compose.runtime.SideEffect { rendered = frame.takeIf { visible } }
        if (visible) FreeSnapshotScreen(
            state = frame,
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
    private fun sheetOpen() = count(tag("sheet")) == 1
    private fun SemanticsNode.own() = (config.getOrNull(SemanticsProperties.EditableText)?.text
        ?: config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
    private fun check(message: String, ok: Boolean) {
        if (!ok) throw AssertionError("$message\npage=${state.value.selectedTab} kind=$kind paywalls=$paywalls")
    }

    private fun inSheet(part: String) = hasTestTag(part) and hasAnyAncestor(hasTestTag(tag("sheet")))

    private fun sheetNode(tag: String, message: String): SemanticsNode {
        val inside = compose.onAllNodes(inSheet(tag), useUnmergedTree = true).fetchSemanticsNodes()
        if (inside.size != 1 || count(tag) != 1) throw AssertionError(message)
        return inside.single()
    }

    private fun sheetText(tag: String, message: String): String = sheetNode(tag, message).own()
        .ifEmpty { compose.onAllNodes(inSheet(tag), useUnmergedTree = false).fetchSemanticsNodes().singleOrNull()?.own() ?: throw AssertionError(message) }

    private fun sheetLabels(tag: String, message: String): List<String> =
        generateSequence(listOf(sheetNode(tag, message))) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
            .map { it.own() }.filter { it.isNotEmpty() }.toList()

    private fun pressInSheet(tag: String, message: String) {
        if (sheetNode(tag, message).config.getOrNull(SemanticsActions.OnClick) == null) throw AssertionError(message)
        compose.onNode(inSheet(tag), useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        compose.onNode(inSheet(tag), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun descendants(node: SemanticsNode) =
        generateSequence(node.children) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()

    private fun inList(matcher: SemanticsMatcher) =
        descendants(list().fetchSemanticsNode()).filter { matcher.matches(it) }.toList()

    private fun placed(matcher: SemanticsMatcher) = inList(matcher).filter(::onScreen)

    private fun inListCount(tag: String) = inList(hasTestTag(tag)).size

    private fun scrollTo(tag: String): Boolean {
        val matcher = hasTestTag(tag)
        list().performScrollToNode(matcher)
        compose.waitForIdle()
        return placed(matcher).size == 1
    }

    private fun tapInList(tag: String) {
        check("premise: 선택 페이지의 $tag", scrollTo(tag))
        val id = placed(hasTestTag(tag)).single().id
        val all = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
        all[all.fetchSemanticsNodes().indexOfFirst { it.id == id }].performClick()
        compose.waitForIdle()
    }

    private val verticalList = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun onScreen(node: SemanticsNode): Boolean {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val box = node.boundsInRoot
        return node.layoutInfo.isPlaced && box.width > 0f && box.height > 0f && box.left >= root.left && box.right <= root.right
    }

    private fun list(): SemanticsNodeInteraction {
        val all = compose.onAllNodes(verticalList, useUnmergedTree = true)
        val nodes = all.fetchSemanticsNodes()
        val shown = nodes.indices.filter { onScreen(nodes[it]) }
        if (shown.size != 1) throw AssertionError("premise: 보이는 세로 목록이 하나다 ${nodes.map { it.boundsInRoot }}")
        return all[shown.single()]
    }

    private fun tapScrim() {
        val sheet = compose.onNodeWithTag(tag("sheet"), useUnmergedTree = true).fetchSemanticsNode()
        val window = sheet.boundsInRoot
        // Wrappers as tall as the window are not where the sheet is drawn.
        val placed = generateSequence(sheet.children) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
            .filter { it.layoutInfo.isPlaced && it.size.height > 0 && it.size.height < window.height * 0.9f }.toList()
        val inner = placed.minOfOrNull { it.positionInRoot.y } ?: throw AssertionError("premise: 시트 안에 놓인 node 가 있다")
        val inset = with(compose.density) { 2.dp.toPx() }
        check("premise: 시트 위의 scrim",inner > window.top + inset)
        compose.onNodeWithTag(tag("sheet"), useUnmergedTree = true)
            .performTouchInput { click(Offset(width / 2f, inset)) }
        compose.waitForIdle()
    }

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
        if (!scrollTo(tag("section"))) throw AssertionError(message)
        if (inListCount(tag("open")) == 0) {
            if (inListCount(tag("toggle")) != 1) throw AssertionError(message)
            tapInList(tag("toggle"))
        }
        if (inListCount(tag("open")) != 1) throw AssertionError(message)
        tapInList(tag("open"))
        awaitObservation { sheetOpen() }
    }

    private fun closeBy(part: String = tag("close")) { tap(part); awaitObservation { !sheetOpen() } }

    private fun modalTexts(): List<String> = compose.onAllNodes(hasTestTag(tag("sheet")), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { root -> generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten().toList() }
        .map { it.own() }.filter { it.isNotEmpty() }

    private fun labels(tag: String) = sheetLabels(tag,"$tag 본문")
    private fun sectionWords() = placed(hasTestTag(tag("section"))).single().let { node ->
        generateSequence(listOf(node)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten().map { it.own() }.toList()
    }
    private fun visit(page: FreeTab) {
        if (state.value.selectedTab != page) update { state.value = stateFor(page) }
    }

    private fun noPreviewSheet() = listOf(
        FreeAlertPreviewTags.SHEET, FreeSourcePreviewTags.SHEET,
        comparisonPreviewTag("signed", "sheet"), comparisonPreviewTag("absolute", "sheet")
    ).all { count(it) == 0 }

    @Test fun D01() {
        showTether()
        for (page in listOf(FreeTab.TETHER,FreeTab.USD,FreeTab.JPY,FreeTab.EUR)) {
            visit(page)
            val sections = if (page == FreeTab.TETHER) listOf("free-source-preview-section","free-kimchi-preview-section","free-comparison-preview-section")
                else listOf("free-alert-preview-section","free-comparison-preview-section")
            val order = sections.map { hasTestTag(it) } + hasText("프리미엄 구독")
            val total = state.value.rateSections.sumOf { it.rows.size.coerceAtLeast(1) + 1 }
            val first = 4 + (if (state.value.seriesToggles.isNotEmpty()) 1 else 0) + total
            for ((i, pair) in order.zipWithNext().withIndex()) {
                list().performScrollToIndex(first + i)
                compose.waitForIdle()
                val before = placed(pair.first).singleOrNull()
                val after = placed(pair.second).singleOrNull()
                check("직접 item 순서", before != null && after != null &&
                    before.positionInRoot.y + before.size.height <= after.positionInRoot.y + 1f)
            }
            for (mode in (if (page == FreeTab.TETHER) listOf("signed","absolute") else listOf("absolute"))) {
                kind = mode
                check("섹션",scrollTo(tag("section")))
                val title = if (kind == "signed") "김프 알림" else "비교 알림"
                check("제목·배지", sectionWords().count { it == title } == 1 && sectionWords().count { it == "미리보기" } == 1)
                tapInList(tag("toggle"))
                val guidance = when {
                    mode == "signed" -> "구독하면 거래소와 환율 간 김치프리미엄 알림을 받을 수 있어요"
                    page == FreeTab.TETHER -> "구독하면 거래소 간 시세 차이 알림을 받을 수 있어요"
                    else -> "구독하면 은행 간 환율 차이 알림을 받을 수 있어요"
                }
                val words = sectionWords()
                check("안내·진입", words.count { it == guidance } == 1 &&
                    words.count { it == "$title 추가 (미리보기)" } == 1 && inListCount(tag("open")) == 1 && noPreviewSheet())
                tapInList(tag("chevron"))
                check("독립 접힘", inListCount(tag("open")) == 0 && noPreviewSheet())
                openTrial()
                closeBy()
                if (page == FreeTab.TETHER && mode == "signed") {
                    list().performScrollToNode(hasTestTag("free-comparison-preview-section"))
                    check("김프와 비교의 독립 펼침", inListCount("free-comparison-preview-open") == 0)
                    list().performScrollToIndex(0)
                    check("premise: item 폐기", inListCount(tag("section")) == 0)
                    scrollTo(tag("section"))
                    check("페이지 수명의 펼침 상태", inListCount(tag("open")) == 1)
                    tetherQuotes = mapOf("upbit" to 1402.0)
                    update { state.value = stateFor(page) }
                    scrollTo(tag("section"))
                    check("행 재배치 뒤 펼침", inListCount(tag("open")) == 1)
                    update { mounted.value = false }
                    check("premise: screen dispose",count(tag("section")) == 0)
                    update { mounted.value = true }
                    check("재부착 섹션", scrollTo(tag("section")) && inListCount(tag("section")) == 1)
                    check("재부착 기본 접힘", inListCount(tag("open")) == 0)
                }
            }
            if (page == FreeTab.TETHER || page == FreeTab.USD) {
                val next = if (page == FreeTab.TETHER) FreeTab.USD else FreeTab.JPY
                val leaving = list().fetchSemanticsNode().id
                compose.mainClock.autoAdvance = false
                try {
                    compose.runOnIdle { state.value = stateFor(next) }
                    val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
                    fun departing() = compose.onAllNodes(verticalList, true).fetchSemanticsNodes().singleOrNull {
                        it.id == leaving && it.layoutInfo.isPlaced && it.positionInRoot.x < 0f &&
                            it.positionInRoot.x + it.size.width > width * 0.2f
                    }
                    var frames = 0
                    while (departing() == null && frames++ < 40) compose.mainClock.advanceTimeByFrame()
                    val oldList = departing() ?: throw AssertionError("premise: 이전 페이지가 화면 왼쪽에 걸쳐 있다")
                    val tags = descendants(oldList).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.toList()
                    if (page == FreeTab.TETHER) {
                        check("비활성 테더", "free-kimchi-preview-section" !in tags && "free-comparison-preview-section" !in tags)
                    } else {
                        check("비활성 FX의 은행과 비교",tags.count { it == "free-alert-preview-section" } == 1 &&
                            tags.count { it == tag("section") } == 1)
                        val off = descendants(oldList).filter { hasTestTag(tag("open")).matches(it) }.toList()
                        check("premise: 비활성 FX 버튼",off.size == 1)
                        compose.runOnUiThread { off.single().config[SemanticsActions.OnClick].action?.invoke() }
                        compose.mainClock.advanceTimeByFrame()
                        check("비활성 FX 열기",!sheetOpen())
                    }
                } finally { compose.mainClock.autoAdvance = true }
                compose.waitForIdle()
            }
        }
    }

    private fun press(part: String) = pressInSheet("comparison-$part","조작 $part")
    private fun field() = sheetText("comparison-value","입력")
    private fun color(label: String, expected: androidx.compose.ui.graphics.Color, parent: String? = null) {
        var match = hasText(label) and hasAnyAncestor(hasTestTag(tag("sheet")))
        if (parent != null) match = match and hasAnyAncestor(hasTestTag(parent))
        val node = compose.onAllNodes(match,true).fetchSemanticsNodes().singleOrNull() ?: throw AssertionError("색상 대상 $label")
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.runOnIdle { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) }
        check("$label 색상",results.singleOrNull()?.layoutInput?.style?.color == expected)
    }

    private fun footerY(part: String): Float {
        val node = sheetNode(part, "고정 footer $part 수")
        check("고정 footer $part 표시", compose.onNode(inSheet(part), useUnmergedTree = true).isDisplayed())
        return node.positionInRoot.y
    }

    @Test fun D02() {
        showTether()
        for (page in listOf(FreeTab.TETHER,FreeTab.USD,FreeTab.JPY,FreeTab.EUR)) {
            visit(page)
            for (mode in (if (page == FreeTab.TETHER) listOf("signed","absolute") else listOf("absolute"))) {
                kind = mode
                openTrial()
                val ids = if (page == FreeTab.TETHER) listOf("upbit","bithumb","coinone","korbit","gopax")
                    else listOf("investing","kb","hana","shinhan","woori","ibk","nh","sc","bs")
                fun picked(id: String) = sheetNode("comparison-pick-$id","picker").config.getOrNull(SemanticsProperties.Selected) == true
                fun optionIds(group: String) = sheetNode("comparison-$group","후보").let { node ->
                    generateSequence(listOf(node)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
                        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.filter { it.startsWith("comparison-pick-") }
                        .map { it.removePrefix("comparison-pick-") }.toList()
                }
                check("후보 순서",optionIds("left") == ids)
                val footer = listOf(SubscribeCTATags.BUTTON, SubscribeCTATags.NOTE).associateWith(::footerY)
                val tolerance = with(compose.density) { 2.dp.toPx() }
                val order = listOf("headline","left","current","heading-row","value","repeat-row")
                    .map { sheetNode("comparison-$it","본문 순서") }
                check("본문 순서·겹침",order.zipWithNext().all { (before,after) ->
                    before.positionInRoot.y + before.size.height <= after.positionInRoot.y + tolerance
                })
                val headline = order.first()
                check("headline 잘림",headline.size.height > 0 && headline.size.width > 0)
                check("제목과 CTA",sheetText(tag("title"),"시트 제목") == (if (mode == "signed") "김프 알림" else "비교 알림") + " 추가 (미리보기)" &&
                    sheetLabels(SubscribeCTATags.BUTTON,"구독 버튼") == listOf("구독 혜택 보기"))
                val texts = modalTexts()
                check("미리보기 외곽",texts.count { it == "닫기" } == 1 && count("comparison-message") == 0 &&
                    listOf("저장","삭제","알림 만들기","알림 내역").none { it in texts } &&
                    count("comparison-headline") == 1)
                if (mode == "absolute") {
                    val guide = if (page == FreeTab.TETHER) "비교할 거래소 2곳을 선택해주세요" else "비교할 은행 2곳을 선택해주세요"
                    check("안내",labels("comparison-headline") == listOf(guide))
                    for (id in ids.take(3)) press("pick-$id")
                    check("세 번째 선택",!picked(ids[0]) && picked(ids[1]) && picked(ids[2]) && labels("comparison-headline").single().startsWith("\""))
                    press("pick-${ids[1]}")
                    check("선택 해제",!picked(ids[1]) && labels("comparison-headline") == listOf(guide))
                } else {
                    check("기준과 비교",optionIds("right") == listOf("investing","kb","hana") && picked("bithumb") && picked("investing"))
                    check("넓히기 전 배지", "넓은 범위 ±1000" !in modalTexts())
                    press("wide")
                    compose.onNode(hasAnyAncestor(isPopup()) and hasText("넓은 범위 설정")).performClick()
                    check("넓힘 배지",modalTexts().count { it == "넓은 범위 ±1000" } == 1)
                    press("wide")
                    compose.onNode(hasAnyAncestor(isPopup()) and hasText("넓은 범위 사용 중 (±1000)")).assertIsNotEnabled()
                    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                    compose.waitForIdle()
                    compose.onAllNodes(isPopup(), true).assertCountEquals(0)
                    check("메뉴 닫힌 뒤 시트", sheetOpen())
                }
                for ((index,above) in listOf(true,false,true).withIndex()) {
                    val label = if (above) "△ 이상" else "▽ 이하"
                    check("단일 조건",labels("comparison-condition") == listOf(label) && sheetNode("comparison-condition","조건").config[SemanticsProperties.Role] == androidx.compose.ui.semantics.Role.Button)
                    color(label,if (above) PositiveColor else NegativeColor)
                    if (index < 2) press("condition")
                }
                val before = field().toBigDecimal()
                val step = (if (page == FreeTab.TETHER) "1.0" else "0.1").toBigDecimal()
                check("premise: 단타 전 입력이 격자 위", before.remainder(step).signum() == 0)
                press("step-＋")
                check("단타 증가", field().toBigDecimal().compareTo(before + step) == 0)
                press("step-−")
                check("단타 감소", field().toBigDecimal().compareTo(before) == 0)
                val button = compose.onNode(inSheet("comparison-step-＋"),true)
                button.performScrollTo()
                button.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(1550)
                button.performTouchInput { up() }
                check("길게 누름",field().toDouble() > before.toDouble() + (if (page == FreeTab.TETHER) 10 else 1))
                val options = listOf("한번만") + com.jay.fxi.domain.model.RepeatInterval.entries.map { it.label }
                var current = "한번만"
                for (next in options.drop(1) + "한번만") {
                    press("repeat")
                    val menu = hasAnyAncestor(isPopup()) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
                    val entries = compose.onAllNodes(menu).fetchSemanticsNodes()
                    check("반복 선택",entries.map { it.own() } == options && entries.filter { it.config[SemanticsProperties.Selected] }.map { it.own() } == listOf(current))
                    compose.onNode(menu and hasText(next)).performScrollTo().performClick()
                    current = next
                }
                for ((part, beforeY) in footer) {
                    val afterY = footerY(part)
                    check("고정 footer $part 위치", kotlin.math.abs(afterY - beforeY) <= tolerance)
                }
                closeBy()
                openTrial()
                tap(SubscribeCTATags.BUTTON)
                awaitObservation { !sheetOpen() }
            }
        }
        check("선택 전 CTA",paywalls == 5)
    }

    @Test fun D03() {
        showTether()
        kind = "signed"
        openTrial()
        fun current() = labels("comparison-current")
        check("음수 차이",current().takeLast(2) == listOf("-10.0원","-0.71%"))
        color("-10.0원",StatusError,"comparison-current")
        color("-0.71%",StatusError.copy(alpha = 0.65f),"comparison-current")
        press("step-＋")
        fun observation() = listOf(field()) + current() +
            labels("comparison-pick-bithumb") + labels("comparison-pick-investing")
        val t1 = observation()
        color(field(),StatusError)
        reference = 1410.0
        tetherQuotes = mapOf("bithumb" to 1402.0,"upbit" to 1414.0)
        update { state.value = stateFor(FreeTab.TETHER,at = basis + 1.hours) }
        check("premise: page T2",rendered?.comparisonPreviewAsOf == basis + 1.hours &&
            rendered?.comparisonPreviewQuotes?.get("bithumb" to "usdt-krw") == 1402.0 &&
            rendered?.comparisonPreviewQuotes?.get("investing" to "usd-krw") == 1410.0)
        check("T2 도착 직후 열린 시트 수",count(tag("sheet")) == 1)
        check("T2 도착 직후 고정값", observation() == t1)
        tetherFreshness = FreeSnapshotFreshness.UNAVAILABLE
        tetherPreferences = mapOf(RateRowList.TETHER_EXCHANGES to RateRowPreference(hidden = setOf("bithumb")))
        update { state.value = stateFor(FreeTab.TETHER,GraphPeriod.entries.last()) }
        check("열 때 고정", observation() == t1)
        closeBy()
        for ((left,right) in listOf(null to null,1390.0 to null,null to 1400.0,1400.0 to 1400.0,1402.0 to 1400.0)) {
            reference = right
            tetherQuotes = if (left == null) emptyMap() else mapOf("bithumb" to left)
            tetherFreshness = FreeSnapshotFreshness.DELAYED
            update { state.value = stateFor(FreeTab.TETHER) }
            openTrial()
            val words = current()
            check("편측 캡션",labels("comparison-pick-bithumb").size == if (left == null) 1 else 2)
            check("참조 캡션",labels("comparison-pick-investing").size == if (right == null) 1 else 2)
            if (left == null || right == null) check("빈 현재 줄",words.isEmpty())
            else {
                val amount = if (left == right) "+0.0원" else "+2.0원"
                check("양수 또는 동가",words.size == 3 && words[1] == amount)
                color(amount,StatusOnline,"comparison-current")
                color(field(),StatusOnline)
            }
            val oldValue = field()
            press("step-＋")
            check("결측 시세에서도 편집",field() != oldValue)
            press("pick-upbit")
            check("결측 시세에서도 선택",sheetNode("comparison-pick-upbit","선택").config[SemanticsProperties.Selected])
            closeBy()
        }
        kind = "absolute"
        tetherQuotes = mapOf("upbit" to 1402.0,"bithumb" to 1390.0)
        update { state.value = stateFor(FreeTab.TETHER) }
        for (page in listOf(FreeTab.TETHER, FreeTab.USD)) {
            visit(page)
            openTrial()
            for (id in if (page == FreeTab.TETHER) listOf("upbit","bithumb") else listOf("investing","kb")) press("pick-$id")
            check("절대 차이",current().size == 2 && current()[1] == if (page == FreeTab.TETHER) "12원" else "1.5원")
            closeBy()
        }
    }

    private data class Observation(val prompt: Boolean, val permission: Int, val posted: Set<String>, val stores: Map<String, String>)

    @Test fun D04() {
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
            Log.i("S96D04", "point=$point api=${Build.VERSION.SDK_INT} prompt=${now.prompt} " +
                "permission=${if (Build.VERSION.SDK_INT >= 33) now.permission.toString() else "constant<33"} posted=${now.posted.sorted()} " +
                "files=${now.stores.size} storesDigest=$digest")
            now.stores.toSortedMap().forEach { (path, hash) ->
                val record = if (point == "start") "file $path $hash" else "snapshot=$point file=$path sha256=$hash"
                Log.i("S96D04", record)
            }
            return now
        }
        val start = observe("start")
        if (start.prompt) throw AssertionError("premise: 시작 시 권한 UI 없음")
        showTether()
        val discards = mutableListOf<Boolean>()
        for (way in listOf("close", "back", "scrim", "swipe")) {
            openTrial()
            pressInSheet("comparison-step-＋",message)
            check(message,sheetText("comparison-value",message) == "3")
            when (way) {
                "close" -> closeBy()
                "back" -> { Espresso.pressBack(); awaitObservation { !sheetOpen() } }
                "scrim" -> { tapScrim(); awaitObservation { !sheetOpen() } }
                "swipe" -> {
                    val sheet = compose.onNodeWithTag(tag("sheet"), useUnmergedTree = true)
                    val box = sheet.fetchSemanticsNode().boundsInRoot
                    val dragStart = sheetNode(tag("title"), "premise: 시트 본문 제목").boundsInRoot.center - box.topLeft
                    sheet.performTouchInput { swipe(dragStart, Offset(dragStart.x, height - 1f), durationMillis = 220) }
                    awaitObservation { !sheetOpen() }
                }
            }
            check("일반 닫기의 구독 호출", paywalls == 0)
            discards += observe("after $way") == start
            openTrial(message)
            discards += sheetText("comparison-value",message) == "2" && paywalls == 0
            closeBy()
        }
        openTrial()
        tap(SubscribeCTATags.BUTTON)
        awaitObservation { paywalls == 1 }
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
