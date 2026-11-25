package com.jay.fxi

import android.content.Context
import androidx.activity.ComponentActivity
import android.content.ContextWrapper
import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jay.fxi.data.alerts.*
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.mapAlertHttpFailure
import com.jay.fxi.domain.model.*
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.ui.alert.*
import com.jay.fxi.ui.free.FreeSnapshotScreen
import com.jay.fxi.ui.free.FreeSnapshotUiState
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import com.jay.fxi.ui.rates.view.RATE_ROW_SHEET_TAG
import com.jay.fxi.ui.theme.*
import com.jay.fxi.ui.viewmodel.NewsViewModel
import dagger.hilt.android.EntryPointAccessors
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComparisonAlertHistoryRouteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private fun rates() = TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1390.0, t), TopicQuote("kb", "jpy-krw", 930.0, t),
        TopicQuote("kb", "eur-krw", 1600.0, t), TopicQuote("upbit", "usdt-krw", 1400.0, t)))

    private class NoNews(val items: List<NewsItem> = emptyList()) : NewsRepository {
        override suspend fun fetchNews(limit: Int, hours: Double): List<NewsItem> = items
        override suspend fun saveCache(items: List<NewsItem>) = Unit
        override suspend fun loadCache(): List<NewsItem>? = null
    }
    private class MemoryRows : RateRowPreferenceStore {
        override suspend fun preferences(uid: String) = emptyMap<RateRowList, RateRowPreference>()
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
    }
    /** The bank tabs' section reads an idle fake that counts its makings and ensures. */
    private class Bank : BankAlertSectionSourceFactory {
        val settingsFlow = MutableStateFlow(BankAlertSettingsState(AlertLoadPhase.Idle, null, null))
        val created = AtomicInteger()
        val ensures = AtomicInteger()
        override fun create(context: Context): BankAlertSectionSource {
            created.incrementAndGet()
            return object : BankAlertSectionSource {
                override val settings = settingsFlow
                override val manualRefresh = MutableStateFlow(ManualRefreshState())
                override fun ensure() { ensures.incrementAndGet() }
                override fun authoritative() {}
                override fun manual() {}
                override fun setEnabled(id: Int, enabled: Boolean) {}
                override fun add(draft: com.jay.fxi.data.alerts.BankAlertDraft): com.jay.fxi.data.alerts.BankAlertSaveRequest = error("S8-4a: not used here")
                override fun edit(id: Int, edit: com.jay.fxi.data.alerts.BankAlertEdit): com.jay.fxi.data.alerts.BankAlertSaveRequest = error("S8-4a: not used here")
                override val bankSelection = MutableStateFlow<com.jay.fxi.data.local.BankSelectionSnapshot>(com.jay.fxi.data.local.BankSelectionSnapshot.None)
                override suspend fun prepareBankSelection(identity: AuthIdentityFence) {
                    bankSelection.value = com.jay.fxi.data.local.BankSelectionSnapshot.Ready(identity, 1, null)
                }
                override fun delete(id: Int) {}
            }
        }
    }

    private val display = MutableStateFlow(TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.TETHER))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** S8-5b: the consumer's live identity, so another account's screen can be an accepted one. */
    private var liveAccount = a1
    private val consumer = PremiumTopicConsumer(display, focus, { liveAccount }, MemoryRows(),
        { o, tab -> focus.value = OwnedTopicFocus(o, tab) }, { }, { _, _ -> }, scope)
    private class Call(val owner: TopicDisplayOwner, val tab: String, val kind: String, val guard: () -> Boolean,
        val continuation: Continuation<AuthBoundResult<List<ComparisonAlertHistoryItem>>>) {
        var folds = 0
        var answered = false
        fun finish(result: Result<List<ComparisonAlertHistoryItem>>) {
            check(!answered)
            answered = true
            continuation.resume(AuthBoundResult({ folds++ }, result))
        }
    }

    private val calls = CopyOnWriteArrayList<Call>()
    private val comparison = CountingComparisonSource(ComparisonAlertSettingsState())
    private val sources = CountingSourceAlertSection()
    private val sourceHistory = CountingSourceHistoryWiring()
    private val bank = Bank()
    private val historyMade = AtomicInteger()
    private val bankHistory = BankHistoryWiringFactory { _, _ -> error("unexpected bank history demand") }
    private val access = run {
        val fence = TopicSessionFence(a1, "epoch", TopicGrantToken(11))
        val snapshot = TopicAccessSnapshot.INITIAL.copy(revision = 1,
            facts = TopicAccessFacts.NONE.copy(token = fence.grant, tokenStanding = true, userBlocks = emptySet()))
        AlertSettingsAccess({ a1 }, { fence }, SnapshotTopicUseAuthority { snapshot },
            { OwnedPremiumAccess(a1.uid, a1.authGeneration, PremiumAccessState.PremiumConfirmed) }, { true }, { true })
    }
    private val wiring = ComparisonAlertHistoryWiringFactory {
        historyMade.incrementAndGet()
        ComparisonHistoryWiring(access, ComparisonHistoryRead { o, t, k, g ->
            suspendCoroutine { calls += Call(o, t, k, g, it) }
        }, MutableStateFlow(0L), MutableStateFlow(0L))
    }
    private var active by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private var free by mutableStateOf(false)
    private var settingsShown = 0
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val news = NewsViewModel(NoNews())
    private fun show(factory: ComparisonAlertHistoryWiringFactory = wiring) {
        rule.setContent {
            if (free) FreeSnapshotScreen(FreeSnapshotUiState(uid = a1.uid, selectedTab = FreeTab.USD), {}, {}, {},
                { _, _, _, _ -> }, {}, {})
            else if (mounted) PremiumTopicRoute(consumer, liveAccount, active, news, null, {},
                settingsContent = { _, _, _ -> settingsShown++ }, bankHistoryWiring = bankHistory,
                bankAlertSectionSourceFactory = bank, sourceAlertSectionSourceFactory = sources,
                sourceHistoryWiring = sourceHistory, comparisonAlertSectionSourceFactory = comparison,
                comparisonHistoryWiring = factory)
        }
        rule.waitForIdle()
    }

    @After fun cleanup() {
        rule.runOnUiThread {
            calls.filterNot { it.answered }.forEach { it.finish(Result.failure(CancellationException())) }
            scope.cancel()
        }
    }

    private fun change(block: () -> Unit) { rule.runOnUiThread(block); rule.waitForIdle() }
    private fun select(tab: FreeTab) = change { focus.value = OwnedTopicFocus(liveAccount, tab) }
    private fun node(tag: String) = rule.onNodeWithTag(tag, true)
    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes()
    private fun sheetText(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("comparison-history-sheet")), true)
    private fun saved(tag: String) = checkNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun open(kind: String): Call {
        val n = calls.size
        val action = saved("comparison-$kind-history")
        change { action() }
        rule.waitUntil(5_000) { calls.size == n + 1 }
        return calls.last()
    }
    private fun close() {
        sheetText("닫기").performClick()
        rule.waitUntil(5_000) { nodes("comparison-history-sheet").isEmpty() }
    }
    private fun siblings() = comparison.counts() + listOf(bank.ensures.get(), sources.ensures.get(), sourceHistory.made.get())
    private fun title(kind: String) = if (kind == "signed") "김프 알림" else "비교 알림"
    private fun buttons(n: Int) = assertEquals(n, nodes("comparison-absolute-history").size + nodes("comparison-signed-history").size)
    private fun result(call: Call, rows: List<ComparisonAlertHistoryItem>) = change { call.finish(Result.success(rows)) }
    private fun item(kind: String, spread: Double, left: Double) = ComparisonAlertHistoryItem(91, null, "tether",
        "upbit", "usdt-krw", if (kind == "signed") "kb" else "bithumb", if (kind == "signed") "usd-krw" else "usdt-krw",
        kind, "gte", 4.0, left, 1500.0, spread, null, null, true, Instant.parse("2026-07-01T15:01:00Z"))

    @Test fun HA01_headers() {
        free = true
        show(); buttons(0)
        change { free = false; active = false }; buttons(0)
        change { active = true; display.value = display.value.copy(owner = null) }; buttons(0)
        select(FreeTab.NEWS)
        change { display.value = display.value.copy(owner = o1) }; buttons(0)
        assertEquals(0, historyMade.get())
        var count = 0
        for (tab in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR, FreeTab.TETHER)) {
            select(tab)
            val kinds = if (tab == FreeTab.TETHER) listOf("signed", "absolute") else listOf("absolute")
            buttons(kinds.size)
            val sections = listOf(if (tab == FreeTab.TETHER) SourceAlertSectionTags.ROOT else BankAlertSectionTags.ROOT) +
                kinds.map { "comparison-$it" }
            val y = sections.map { node(it).fetchSemanticsNode().positionInRoot.y }
            assertEquals(y.sorted(), y)
            for (kind in kinds) {
                val serverTab = checkNotNull(tab.serverTab)
                val asset = if (tab == FreeTab.TETHER) "usdt-krw" else "$serverTab-krw"
                val disabled = ComparisonAlertSetting(1, a1.uid, serverTab, if (tab == FreeTab.TETHER) "upbit" else "hana",
                    asset, if (tab == FreeTab.TETHER && kind == "absolute") "bithumb" else "kb",
                    if (kind == "signed") "usd-krw" else asset, kind, "gte", 4.0, false, false, 60, null, t, t)
                val refreshSettings = saved("comparison-$kind-refresh")
                change { refreshSettings() }
                assertEquals(count, historyMade.get())
                val counts = siblings()
                val expand = saved("comparison-$kind-expand")
                for ((phase, rows) in listOf(AlertLoadPhase.Loading to emptyList(), AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden) to emptyList(),
                    AlertLoadPhase.Ready to emptyList(), AlertLoadPhase.Ready to listOf(disabled))) {
                    change {
                        comparison.settings.value = ComparisonAlertSettingsState(phase, a1,
                            if (phase == AlertLoadPhase.Ready) ComparisonAlertRowPolicy.adopt(a1.uid, rows) else null)
                        expand()
                    }
                    assertEquals(count, historyMade.get())
                    rule.onNodeWithTag("comparison-$kind-history").assertContentDescriptionEquals("${title(kind)} 히스토리")
                    val call = open(kind)
                    assertEquals(o1, call.owner)
                    assertEquals(tab.serverTab to kind, call.tab to call.kind)
                    sheetText("${title(kind)} 히스토리").assertExists()
                    close()
                    assertFalse(call.guard())
                    result(call, emptyList())
                    assertEquals(++count, historyMade.get())
                    assertEquals(counts, siblings())
                }
            }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun HA02_sheet() {
        show()
        for (kind in listOf("signed", "absolute")) {
            val empty = open(kind)
            sheetText("히스토리 로드 중...").assertExists()
            result(empty, emptyList())
            sheetText("발송된 ${title(kind)}이 없습니다").assertExists()
            node("comparison-history-list").assertDoesNotExist()
            sheetText("다시 시도").assertDoesNotExist()
            close()
            val first = open(kind)
            change {
                val failure = AuthenticatedHttpFailure(503, headersOf(), byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP)
                first.finish(Result.failure(mapAlertHttpFailure(failure)))
            }
            sheetText("구독 확인 중입니다. 잠시 후 다시 시도해주세요.").assertExists()
            sheetText("다시 시도").performClick()
            sheetText("히스토리 로드 중...").assertExists()
            val rows = listOf(item(kind, if (kind == "signed") -11.5 else -3.0, 1390.0), item(kind, 12.0, 1391.0))
            result(calls.last(), rows)
            val width = (320 * rule.density.density).toInt()
            rule.runOnUiThread {
                val decor = android.view.inspector.WindowInspector.getGlobalWindowViews().single {
                    it.hasWindowFocus() && it !== rule.activity.window.decorView
                }
                val params = decor.layoutParams as android.view.WindowManager.LayoutParams
                params.width = minOf(decor.width, width)
                val manager = decor.context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                manager.updateViewLayout(decor, params)
            }
            rule.waitUntil(5_000) { node("comparison-history-sheet").fetchSemanticsNode().size.width <= width }
            val signed = kind == "signed"
            val centers = if (signed) listOf("-11.5", "+12.0") else listOf("3", "12")
            val rowNodes = nodes("comparison-history-row")
            assertEquals(2, rowNodes.size)
            assertTrue(rowNodes[0].positionOnScreen.y < rowNodes[1].positionOnScreen.y)
            for ((index, x) in rowNodes.withIndex()) {
                val desc = hasAnyAncestor(SemanticsMatcher("this delivery") { it.id == x.id })
                rule.onNode(desc and hasContentDescription("왼쪽 업비트 ${1390 + index}")).assertExists()
                rule.onNode(desc and hasContentDescription(if (signed) "오른쪽 국민 1500.00" else "오른쪽 빗썸 1500")).assertExists()
                for (field in listOf(centers[index], if (signed) "+4" else "4", "△ 이상", "07-02", "00:01",
                    if (!signed) "↔" else if (index == 0) "-0.77%" else "+0.80%")) {
                    rule.onNode(desc and hasText(field), true).assertIsDisplayed()
                }
                val firstLeg = rule.onNode(desc and hasContentDescription("왼쪽 업비트 ${1390 + index}")).fetchSemanticsNode()
                val lastLeg = rule.onNode(desc and hasContentDescription(if (signed) "오른쪽 국민 1500.00" else "오른쪽 빗썸 1500")).fetchSemanticsNode()
                assertTrue(firstLeg.positionOnScreen.x < lastLeg.positionOnScreen.x)
                val date = rule.onNode(desc and hasText("07-02"), true).fetchSemanticsNode()
                val time = rule.onNode(desc and hasText("00:01"), true).fetchSemanticsNode()
                assertTrue(date.positionOnScreen.y < time.positionOnScreen.y)
                assertEquals(date.positionOnScreen.x + date.size.width, time.positionOnScreen.x + time.size.width, 1f)
            }
            fun color(text: String, expected: Color) {
                val image = rule.onAllNodes(hasAnyAncestor(hasTestTag("comparison-history-row")) and hasText(text), true)
                    .onFirst().captureToImage().toPixelMap()
                assertTrue("$text uses $expected", (0 until image.height).any { y -> (0 until image.width).any { x ->
                    val pixel = image[x, y]
                    kotlin.math.abs(pixel.red - expected.red) + kotlin.math.abs(pixel.green - expected.green) +
                        kotlin.math.abs(pixel.blue - expected.blue) < 0.12f
                } })
            }
            color(centers.first(), if (signed) StatusError else StatusOnline)
            if (signed) color("+0.80%", StatusOnline.copy(alpha = 0.65f).compositeOver(Background))
            color("△ 이상", PositiveColor)
            sheetText("반복").assertDoesNotExist()
            sheetText("다시 시도").assertDoesNotExist()
            val before = calls.size
            node("comparison-history-list").performTouchInput { swipeDown() }
            rule.waitUntil(5_000) { calls.size == before + 1 }
            assertEquals(2, nodes("comparison-history-row").size)
            rule.onNode(hasAnyAncestor(hasTestTag("comparison-history-list")) and
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo), true).assertExists()
            change { calls.last().finish(Result.failure(IOException("private detail"))) }
            sheetText("히스토리를 불러오지 못했습니다.").assertExists()
            assertEquals(0, nodes("comparison-history-row").size)
            sheetText("다시 시도").performClick()
            result(calls.last(), rows)
            if (signed) device.pressBack() else {
                val titleNode = sheetText("비교 알림 히스토리").fetchSemanticsNode()
                val x = (titleNode.positionOnScreen.x + titleNode.size.width / 2).toInt()
                val y = (titleNode.positionOnScreen.y + titleNode.size.height / 2).toInt()
                device.swipe(x, y, x, device.displayHeight - 20, 30)
            }
            rule.waitUntil(5_000) { nodes("comparison-history-sheet").isEmpty() }
            assertEquals(before + 2, calls.size)
        }
    }

    @Test fun HA03_modalLifetime() {
        show()
        for (tab in listOf(FreeTab.USD, FreeTab.TETHER)) {
            select(tab)
            val counts = siblings()
            val editor = rule.onAllNodesWithText("조정").onFirst().fetchSemanticsNode()
            val targets = listOf(editor, node(PremiumTopicTags.FULLSCREEN).fetchSemanticsNode(),
                node(PremiumTopicTags.SETTINGS).fetchSemanticsNode())
            var scrim = 0
            for (target in targets) {
                val at = target.positionOnScreen
                val x = (at.x + target.size.width / 2).toInt()
                val y = (at.y + target.size.height / 2).toInt()
                val call = open("absolute")
                val sheet = node("comparison-history-sheet").fetchSemanticsNode()
                val top = sheet.positionOnScreen.y
                android.util.Log.i("HA03", "tab=$tab target=($x,$y) sheetTop=$top size=${sheet.size}")
                assertTrue(device.click(x, y))
                rule.waitForIdle()
                if (y < top - 120 * rule.density.density) {
                    scrim++
                    rule.waitUntil(5_000) { nodes("comparison-history-sheet").isEmpty() }
                } else if (nodes("comparison-history-sheet").isNotEmpty()) close()
                node(PremiumTopicTags.FULLSCREEN_LAYER).assertDoesNotExist()
                node(RATE_ROW_SHEET_TAG).assertDoesNotExist()
                assertEquals(0, settingsShown)
                assertFalse(call.guard())
                result(call, emptyList())
                assertEquals(0, call.folds)
            }
            assertTrue("observed scrim dismiss", scrim > 0)
            assertEquals(counts, siblings())
        }
        val first = open("absolute")
        change { first.finish(Result.failure(IOException())) }
        fun savedButton(label: String) = checkNotNull(rule.onNode(hasClickAction() and hasText(label) and
            hasAnyAncestor(hasTestTag("comparison-history-sheet"))).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val oldRetry = savedButton("다시 시도")
        val oldDismiss = savedButton("닫기")
        change { oldRetry() }
        val held = calls.last()
        close()
        val fresh = open("absolute")
        val beforeLate = calls.size
        change { oldRetry(); oldDismiss(); held.finish(Result.failure(IOException())) }
        sheetText("히스토리 로드 중...").assertExists()
        assertEquals(beforeLate, calls.size)
        assertEquals(0, held.folds)
        assertTrue(fresh.guard())
        result(fresh, emptyList()); close()

        for (loss in listOf("back", "tab", "owner", "inactive", "slot", "dispose")) {
            val openOverlay = saved(PremiumTopicTags.FULLSCREEN)
            val oldOpen = saved("comparison-absolute-history")
            val call = open("absolute")
            val n = calls.size
            val made = historyMade.get()
            when (loss) {
                "back" -> device.pressBack()
                "tab" -> change {
                    focus.value = OwnedTopicFocus(a1, FreeTab.JPY); consumer.currentState()
                    focus.value = OwnedTopicFocus(a1, FreeTab.TETHER); consumer.currentState()
                }
                "owner" -> change {
                    display.value = display.value.copy(owner = TopicDisplayOwner(a1, 2)); consumer.currentState()
                    display.value = display.value.copy(owner = o1); consumer.currentState()
                }
                "inactive" -> change { active = false }
                "slot" -> change { openOverlay() }
                else -> change { mounted = false }
            }
            rule.waitUntil(5_000) { nodes("comparison-history-sheet").isEmpty() }
            assertFalse(call.guard())
            change { call.finish(Result.failure(IOException())) }
            assertEquals(0, call.folds)
            if (loss != "back") change { oldOpen() }
            assertEquals(made, historyMade.get())
            if (loss == "slot") node(PremiumTopicTags.FULLSCREEN_CLOSE).performClick()
            change { active = true; mounted = true }
            assertEquals(n, calls.size)
            node("comparison-absolute-history").assertExists()
        }
    }

    @Test fun HA04_component() {
        var resolved = 0
        show(ComparisonAlertHistoryWiringFactory { context ->
            resolved++
            val own = EntryPointAccessors.fromApplication(context, ComparisonAlertHistoryEntryPoint::class.java)
            val sibling = EntryPointAccessors.fromApplication(context, SourceHistoryEntryPoint::class.java)
            val actual = ProductionComparisonAlertHistoryWiring.create(context)
            assertSame(sibling.alertSettingsAccess(), actual.access)
            assertSame(sibling.alertRepository(), own.alertRepository())
            assertSame(sibling.premiumAccessCoordinator().accessRevisions, actual.accessRevisions)
            assertSame(sibling.deletionAdmissionRevisions().revisions, actual.deletionRevisions)
            actual
        })
        assertEquals(0, resolved)
        val action = saved("comparison-signed-history")
        change { action() }
        assertEquals(1, resolved)
        val missingComponent = object : ContextWrapper(rule.activity) {
            override fun getApplicationContext(): Context = Application()
        }
        assertTrue(runCatching { ProductionComparisonAlertHistoryWiring.create(missingComponent) }.isFailure)
        node("comparison-history-sheet").assertDoesNotExist()
    }
}
