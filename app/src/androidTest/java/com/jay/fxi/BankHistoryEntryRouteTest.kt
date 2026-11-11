package com.jay.fxi

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRuntimeAssembly
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.data.repository.AlertRepositoryException
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.time.SystemAppClock
import com.jay.fxi.ui.alert.BANK_HISTORY_GENERIC_FAILURE
import com.jay.fxi.ui.alert.BankHistoryRead
import com.jay.fxi.ui.alert.BankHistoryTags
import com.jay.fxi.ui.alert.BankHistoryWiring
import com.jay.fxi.ui.alert.BankHistoryWiringFactory
import com.jay.fxi.ui.alert.bankHistoryRow
import com.jay.fxi.ui.alert.productionBankHistoryRead
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.graph.GraphScreenHost
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import com.jay.fxi.ui.premium.graph.GraphV2Surface
import com.jay.fxi.ui.premium.graph.GraphV2UiTags
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import com.jay.fxi.ui.viewmodel.NewsViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit

/**
 * Claude-owned S7 step (c) Route contract on a device (`R4c/S7/s7c_api_proposal.claude.r4.md` §4 S7C_U01·U03·U04·U06·U08·
 * L01·L03·L04·L05·N02·N03·W01): the real PremiumTopicRoute over a real PremiumTopicConsumer and a fake bank history wiring
 * factory that counts its makings, acquisitions and reads. Its read ignores the cancellation of its job, as a transport might:
 * a request the route ended can still complete late, and that completion reaches the holder. The entry is under the usd, jpy and
 * eur rates only; nothing is made or read before the user opens it; every loss of owner or tab, a close, Back, a drag, a scrim
 * touch through the real window, a covering layer (the graph host's own fullscreen included) and the route's inactivity end the
 * sheet, and nothing a late completion brings comes back; the route's own events and a recreation read and restore nothing. The
 * implementation thread reads but does not edit this file.
 */
class BankHistoryEntryRouteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val o2 = TopicDisplayOwner(a1, 2L)
    private fun rates() = TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1390.0, t), TopicQuote("kb", "jpy-krw", 930.0, t),
        TopicQuote("kb", "eur-krw", 1600.0, t), TopicQuote("upbit", "usdt-krw", 1400.0, t)))

    private class NoNews(val items: List<NewsItem> = emptyList()) : NewsRepository {
        val fetches = AtomicInteger()
        override suspend fun fetchNews(limit: Int, hours: Double): List<NewsItem> = items.also { fetches.incrementAndGet() }
        override suspend fun saveCache(items: List<NewsItem>) = Unit
        override suspend fun loadCache(): List<NewsItem>? = null
    }
    /** Counts the consumer's own row preference I/O, apart from the bank history's ports. */
    private class MemoryRows : RateRowPreferenceStore {
        val io = AtomicInteger()
        override suspend fun preferences(uid: String) = emptyMap<RateRowList, RateRowPreference>().also { io.incrementAndGet() }
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) { io.incrementAndGet() }
    }

    /** One read: it ends only when the test answers it, whatever happened to its job meanwhile. */
    private class Call(val currency: String, val guard: () -> Boolean) {
        @Volatile var continuation: Continuation<AuthBoundResult<List<AlertHistoryItem>>>? = null
        /** The holder folded what this read returned: the completion reached the holder. */
        val folded = AtomicInteger()
        /** The read returned or threw into the holder's request. */
        @Volatile var finished = false

        fun complete(result: Result<List<AlertHistoryItem>>) = resume(Result.success(AuthBoundResult({ folded.incrementAndGet() }, result)))
        fun throwing(error: Throwable) = resume(Result.failure(error))
        private fun resume(with: Result<AuthBoundResult<List<AlertHistoryItem>>>) {
            val waiting = checkNotNull(continuation) { "the read is not waiting" }
            continuation = null
            waiting.resumeWith(with)
        }
    }

    /** Counts makings and acquisitions; its access admits a use while the route's published owner is the use's. */
    private class Factory : BankHistoryWiringFactory {
        var made = 0
        val acquired = AtomicInteger()
        val calls: MutableList<Call> = java.util.Collections.synchronizedList(mutableListOf())
        val accessRevisions = MutableStateFlow(0L)
        val deletionRevisions = MutableStateFlow(0L)
        override fun create(context: android.content.Context, displayOwner: () -> TopicDisplayOwner?): BankHistoryWiring {
            made += 1
            return BankHistoryWiring(object : BankHistoryAccess {
                private var n = 0L
                override fun acquire(owner: TopicDisplayOwner): BankHistoryUse? {
                    acquired.incrementAndGet()
                    return TopicGrantToken(++n).let {
                        BankHistoryUse(owner, TopicSessionFence(owner.identity, "epoch", it), TopicUseLifetime(it, 0))
                    }.takeIf { displayOwner() == owner }
                }
                override fun admits(use: BankHistoryUse) = displayOwner() == use.owner
            }, BankHistoryRead { _, currency, guard ->
                val call = Call(currency, guard).also { calls += it }
                try {
                    suspendCoroutine { call.continuation = it }
                } finally {
                    call.finished = true
                }
            }, accessRevisions, deletionRevisions)
        }
    }

    private val display = MutableStateFlow(TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.USD))
    private val selects = mutableListOf<FreeTab>()
    private val connectionRetries = AtomicInteger()
    private val topicRetries = AtomicInteger()
    private val rows = MemoryRows()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consumer = PremiumTopicConsumer(display, focus, { a1 }, rows,
        { o, tab -> selects += tab; focus.value = OwnedTopicFocus(o, tab) }, { connectionRetries.incrementAndGet() },
        { _, _ -> topicRetries.incrementAndGet() }, scope)
    private var active by mutableStateOf(true)
    private var host by mutableStateOf<GraphScreenHost?>(null)
    private var settingsShown = 0
    private val factory = Factory()
    private var news = NoNews()
    private val hostScopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() {
        scope.cancel()
        hostScopes.forEach { it.cancel() }
    }

    private var tester: androidx.compose.ui.test.junit4.StateRestorationTester? = null

    private fun show(newsItems: List<NewsItem> = emptyList(), restorable: Boolean = false, wiring: BankHistoryWiringFactory = factory) {
        news = NoNews(newsItems)
        val vm = NewsViewModel(news)
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            PremiumTopicRoute(consumer = consumer, identity = a1, isActive = active, newsViewModel = vm,
                userInfo = UserInfo("A", null, null, null, AuthProvider.entries.first()), onSignOut = {},
                settingsContent = { _, _, _ -> settingsShown += 1 }, graphHost = host, bankHistoryWiring = wiring)
        }
        if (restorable) androidx.compose.ui.test.junit4.StateRestorationTester(rule).also { tester = it }.setContent(content)
        else rule.setContent(content)
        rule.waitForIdle()
    }

    private fun device() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun entries() = rule.onAllNodesWithTag(BankHistoryTags.ENTRY).fetchSemanticsNodes().size
    private fun sheets() = rule.onAllNodesWithText("알림 히스토리").fetchSemanticsNodes().size - entries()
    private fun item(currency: String, bank: String = "kb") = AlertHistoryItem(1, 1, bank, currency, AlertCondition.ABOVE, 1400.5, 1401.2, t)
    private fun rowText(row: AlertHistoryItem) = bankHistoryRow(row).let { "${it.bankName} · ${it.currencyLabel}" }
    private fun rowsShown(row: AlertHistoryItem) = rule.onAllNodesWithText(rowText(row)).fetchSemanticsNodes().size
    private fun texts(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size
    private fun select(tab: FreeTab) { focus.value = OwnedTopicFocus(a1, tab); rule.waitForIdle() }
    private fun open() { rule.onNodeWithTag(BankHistoryTags.ENTRY).performClick(); rule.waitForIdle() }
    private fun center(node: SemanticsNode) = node.positionOnScreen.let { (it.x + node.size.width / 2f).toInt() to (it.y + node.size.height / 2f).toInt() }
    private fun <T> onMain(read: () -> T): T {
        var result: Result<T>? = null
        rule.runOnUiThread { result = runCatching(read) }
        return checkNotNull(result).getOrThrow()
    }
    /** The consumer's screen listeners now: each open controller holds one. */
    private fun listeners(): Int = onMain {
        (PremiumTopicConsumer::class.java.getDeclaredField("screenListeners").apply { isAccessible = true }.get(consumer) as List<*>).size
    }

    private val serverMessage = "fixture server failure"

    /** A late completion of [call] of one [kind]: it reaches the holder (the fold, or the throw into the request). */
    private fun lateComplete(call: Call, kind: String) {
        when (kind) {
            "success" -> call.complete(Result.success(listOf(item(call.currency))))
            "error" -> call.complete(Result.failure(AlertRepositoryException(serverMessage)))
            "withheld" -> call.throwing(TopicUseWithheldException(emptyList()))
            "thrown" -> call.throwing(java.io.IOException(serverMessage))
            else -> error(kind)
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { call.finished }
        if (kind == "success" || kind == "error") {
            rule.waitUntil(5_000) { call.folded.get() == 1 }
            assertEquals("late $kind: the holder folded it", 1, call.folded.get())
        }
        assertTrue("late $kind: it came back into the holder's request", call.finished)
    }

    /** No sheet, row, spinner or error message is in the semantics. */
    private fun assertNothingShown(label: String, currency: String) {
        assertEquals("$label: no sheet", 0, sheets())
        assertEquals("$label: no row", 0, rowsShown(item(currency)))
        assertEquals("$label: no spinner", 0, texts("히스토리 로드 중..."))
        assertEquals("$label: no error", 0, texts(serverMessage) + texts(BANK_HISTORY_GENERIC_FAILURE))
    }

    /** A pull on the first row of the loaded list, through the real window: the sheet's refresh. */
    private fun pullToRefresh(currency: String) {
        val (x, y) = center(rule.onNodeWithText(rowText(item(currency))).fetchSemanticsNode())
        device().swipe(x, y, x, y + 700, 60)
        rule.waitForIdle()
    }

    /** A drag of the open sheet down by its title, through the real window. */
    private fun dragSheetDown() {
        val title = rule.onAllNodesWithText("알림 히스토리").fetchSemanticsNodes()
            .first { it.config.getOrNull(SemanticsProperties.TestTag) != BankHistoryTags.ENTRY }
        val (x, y) = center(title)
        device().swipe(x, y, x, device().displayHeight - 20, 30)
    }

    /** S7C_U01·N01: the entry under usd, jpy and eur only; nothing made before the first open; each read is its tab's currency. */
    @Test fun s7c_u01_theEntryIsUnderTheThreeBankTabsOnly_andReadsItsCurrency() {
        show()
        for ((tab, want) in listOf(FreeTab.TETHER to 0, FreeTab.NEWS to 0)) {
            select(tab)
            assertEquals("$tab: no entry", want, entries())
            assertEquals("$tab: no slot", 0, rule.onAllNodesWithTag(PremiumTopicTags.BELOW_RATES).fetchSemanticsNodes().size)
        }
        assertEquals("nothing made before an open", 0, factory.made)
        for ((n, pair) in listOf(FreeTab.USD to "usd-krw", FreeTab.JPY to "jpy-krw", FreeTab.EUR to "eur-krw").withIndex()) {
            val (tab, currency) = pair
            select(tab)
            assertEquals("$tab: one entry", 1, entries())
            assertEquals("$tab: still nothing read", n, factory.calls.size)
            open()
            assertEquals("$tab: one read of its currency", currency, factory.calls.last().currency)
            assertEquals("$tab: the sheet opened", 1, sheets())
            factory.calls.last().complete(Result.success(listOf(item(currency))))
            rule.waitForIdle()
            rule.onNodeWithText("닫기").performClick(); rule.waitForIdle()
            assertEquals("$tab: closed", 0, sheets())
        }
    }

    /**
     * S7C_U03·U04: an external tab or owner publication ends the open sheet; the old read completing late reaches the holder and
     * brings nothing back; the return opens and reads nothing.
     */
    @Test fun s7c_u03_u04_aTabOrOwnerPublicationEndsTheSheet_andTheReturnReadsNothing() {
        show()
        for ((label, away, back) in listOf<Triple<String, () -> Unit, () -> Unit>>(
                Triple("tab", { focus.value = OwnedTopicFocus(a1, FreeTab.JPY) }, { focus.value = OwnedTopicFocus(a1, FreeTab.USD) }),
                Triple("owner", { display.value = display.value.copy(owner = o2) }, { display.value = display.value.copy(owner = o1) }))) {
            open()
            val call = factory.calls.last()
            assertTrue("premise($label): loading under a standing guard", call.guard() && sheets() == 1)
            rule.runOnUiThread { away() }
            rule.waitForIdle()
            assertFalse("$label: the guard fell", call.guard())
            assertEquals("$label: the sheet is gone", 0, sheets())
            val reads = factory.calls.size
            rule.runOnUiThread { back() }
            rule.waitForIdle()
            lateComplete(call, "success")
            assertNothingShown("$label: nothing came back", "usd-krw")
            assertFalse("$label: the old guard stays down", call.guard())
            assertEquals("$label: the return read nothing", reads, factory.calls.size)
        }
    }

    /** S7C_L01: a touch through the real window where a tab sits lands on the open sheet's scrim: the tab is not selected. */
    @Test fun s7c_l01_aScrimTouchOverATabOrSettingsDismissesTheSheet_andRunsNothingUnderneath() {
        show()
        for (target in listOf(PremiumTopicTags.tab(FreeTab.TETHER), PremiumTopicTags.SETTINGS)) {
            val node = rule.onNodeWithTag(target).fetchSemanticsNode()
            val at = node.positionOnScreen.let { it.x + node.size.width / 2f to it.y + node.size.height / 2f }
            open()
            val call = factory.calls.last()
            assertEquals("premise($target): open", 1, sheets())
            device().click(at.first.toInt(), at.second.toInt())
            rule.waitForIdle()
            rule.waitUntil(5_000) { sheets() == 0 }
            assertEquals("$target: the tab underneath was not selected", emptyList<FreeTab>(), selects)
            assertEquals("$target: no settings opened", 0, settingsShown)
            assertFalse("$target: the dismissal ended the request", call.guard())
            lateComplete(call, "success")
            assertNothingShown("$target: late", "usd-krw")
        }
    }

    /**
     * S7C_L03·U06: close, Back, a drag of the sheet down through the real window and the route's inactivity each end the sheet —
     * loading, loaded or refreshing — once its dismissal completed (the start of an animation is not counted as the end). A read
     * then still in flight completes late — a success, a server error, a withheld use or a thrown failure: it reaches the holder
     * (positive) and nothing comes back, no sheet, row, spinner or error. Reopening reads once more.
     */
    @Test fun s7c_l03_closeBackDragAndInactivityEndTheSheet_andALateCompletionRestoresNothing() {
        show()
        for (end in listOf("close", "back", "drag", "inactive")) for (state in listOf("loading", "loaded", "refreshing")) {
            for (kind in if (state == "loaded") listOf("none") else listOf("success", "error", "withheld", "thrown")) {
                val label = "$end/$state/$kind"
                if (!active) { active = true; rule.waitForIdle() }
                open()
                var inFlight: Call? = factory.calls.last()
                if (state != "loading") {
                    checkNotNull(inFlight).complete(Result.success(listOf(item("usd-krw"))))
                    rule.waitForIdle()
                    rule.waitUntil(5_000) { rowsShown(item("usd-krw")) == 1 }
                    inFlight = null
                }
                if (state == "refreshing") {
                    val before = factory.calls.size
                    pullToRefresh("usd-krw")
                    rule.waitUntil(5_000) { factory.calls.size == before + 1 }
                    inFlight = factory.calls.last()
                    assertTrue("premise($label): refreshing under a standing guard with the list shown",
                        inFlight.guard() && rowsShown(item("usd-krw")) == 1)
                    assertEquals("$label: the pull read its tab's currency", "usd-krw", inFlight.currency)
                }
                val last = factory.calls.last()
                when (end) {
                    "close" -> rule.onNodeWithText("닫기").performClick()
                    "back" -> device().pressBack()
                    "drag" -> dragSheetDown()
                    else -> active = false
                }
                rule.waitForIdle()
                rule.waitUntil(5_000) { sheets() == 0 }
                assertFalse("$label: the request ended", last.guard())
                assertNothingShown("$label: ended", "usd-krw")
                // Leaving the composition closes the controller itself, not only the holder's scope.
                if (end == "inactive") assertEquals("$label: no screen listener is left", 0, listeners())
                inFlight?.let { call ->
                    lateComplete(call, kind)
                    assertNothingShown("$label: after the late completion", "usd-krw")
                }
            }
        }
        active = true
        rule.waitForIdle()
        val reads = factory.calls.size
        open()
        assertEquals("reopening reads once more", reads + 1, factory.calls.size)
    }

    /**
     * S7C_L04: a layer covering the screen — the rate fullscreen, settings — removes the entry where it shows and ends the open
     * sheet; leaving it reads nothing. Opened by the semantics action on purpose: this is the composition boundary, not the input
     * block (L01).
     */
    @Test fun s7c_l04_aCoveringLayerRemovesTheEntryAndEndsTheSheet() {
        show()
        for (layer in listOf("rate fullscreen", "settings")) {
            open()
            val call = factory.calls.last()
            assertTrue("premise($layer): open", sheets() == 1 && call.guard())
            rule.onNodeWithTag(if (layer == "settings") PremiumTopicTags.SETTINGS else PremiumTopicTags.FULLSCREEN).performClick()
            rule.waitForIdle()
            assertTrue("premise($layer): the layer shows", if (layer == "settings") settingsShown > 0 else
                rule.onAllNodesWithTag(PremiumTopicTags.FULLSCREEN_LAYER).fetchSemanticsNodes().isNotEmpty())
            assertEquals("$layer: no entry", 0, entries())
            assertEquals("$layer: the sheet is gone", 0, sheets())
            assertFalse("$layer: the request ended", call.guard())
            val reads = factory.calls.size
            if (layer == "settings") device().pressBack()
            else rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick()
            rule.waitForIdle()
            assertEquals("$layer: the entry is back", 1, entries())
            assertEquals("$layer: leaving read nothing", reads, factory.calls.size)
        }
    }

    // ---- S7C_L04 (graph): the real graph host's fullscreen ---------------------------------------------------------------

    private val graphFence = TopicSessionFence(a1, "e1", TopicGrantToken(1L))

    /** The issuer's published access for [graphFence]: it admits A's grant 1 in user epoch e1, so a holder can take its context. */
    @Volatile private var snapshot: TopicAccessSnapshot = run {
        val binding = com.jay.fxi.data.entitlements.EntitlementsIdentity("A", 1L)
        val record = com.jay.fxi.data.entitlements.AccessFence("A", "e1", "K1")
        TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = com.jay.fxi.data.entitlements.TopicAccessFacts.NONE.copy(
                token = graphFence.grant,
                issuedFor = com.jay.fxi.data.entitlements.TopicGrantContext(binding, record, 10L),
                binding = binding,
                recordFence = record,
                decisionGeneration = 10L,
                tokenStanding = true,
                userBlocks = emptySet(),
                capabilityBlocks = emptySet()
            ),
            userInvalidations = 3L
        )
    }
    private val built: MutableList<GraphV2ScreenStateHolder> = java.util.Collections.synchronizedList(mutableListOf())

    /** A host over a fresh assembly, started on Main (its requests never answer), whose access admits [graphFence]. */
    private fun newHost(): GraphScreenHost {
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { hostScopes += it }
        val authority: TopicUseAuthority = com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority { snapshot }
        val selections = object : GraphSelectionStore {
            override suspend fun readGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
            override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection) = GraphSelectionWriteResult.Committed
            override suspend fun confirmGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
        }
        val assembly = GraphRuntimeAssembly(
            accessSnapshot = { snapshot },
            accessRevisions = MutableStateFlow(0L),
            liveIdentity = { a1 },
            uses = authority,
            protectedAdmission = { true },
            fetcher = object : GraphV2Fetching {
                override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = awaitCancellation()
                override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean) = awaitCancellation()
            },
            owners = object : GraphOwnerSource {
                override fun currentIdentity(): AuthIdentityFence = a1
                override suspend fun capture(expected: AuthIdentityFence) = AuthSnapshot(expected.uid, expected.authGeneration, "token")
            },
            cachePorts = { _ -> null },
            main = Dispatchers.Main,
            parent = Job(hostScope.coroutineContext[Job]),
            clock = SystemAppClock,
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { }
        )
        assembly.fences.setAccess(true, graphFence, com.jay.fxi.data.remote.TopicGrantOrigin.NewContext)
        rule.runOnUiThread { assembly.start() }
        return GraphScreenHost(hostScope, { tab ->
            val session = GraphSeriesSelectionSession(selections, GraphSelectionAudience.PREMIUM, tab, hostScope, Dispatchers.Main)
            GraphV2ScreenStateHolder(tab, assembly.coordinator, session, { a1 }, display, focus, assembly.fences.current, authority,
                assembly.gate, MutableStateFlow(0L), hostScope, Dispatchers.Main, recorder = assembly.recorder).also { built += it }
        }, { })
    }

    private fun GraphV2ScreenStateHolder.field(name: String): Any? =
        GraphV2ScreenStateHolder::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * S7C_L04 (graph): the real graph host's holder publishes its fullscreen from outside the screen; once the covering
     * composition applied there is no entry and no sheet, the old guard is down and no screen listener is left. Leaving the
     * fullscreen brings the entry back and makes, acquires, listens and reads nothing; the old read completing late reaches the
     * holder and shows nothing.
     */
    @Test fun s7c_l04g_theGraphHostsOwnFullscreenRemovesTheEntryAndEndsTheSheet() {
        host = newHost()
        show()
        val usd = { onMain { built.toList().single { it.field("tab") == "usd" && it.field("closed") == false } } }
        rule.waitUntil(5_000) { runCatching { onMain { usd().currentState().inlineToken } != null }.getOrDefault(false) }
        open()
        val call = factory.calls.last()
        assertTrue("premise: open under a standing guard, listening", sheets() == 1 && call.guard() && listeners() == 1)
        val token = onMain { checkNotNull(usd().currentState().inlineToken) { "premise: an inline token" } }
        rule.runOnUiThread { usd().enterFullscreen(token) }
        rule.waitForIdle()
        rule.waitUntil(5_000) { exists(GraphV2UiTags.root(GraphV2Surface.FULLSCREEN)) }
        assertTrue("premise: the holder's fullscreen is drawn", exists(GraphV2UiTags.root(GraphV2Surface.FULLSCREEN)))
        assertEquals("no entry under the graph fullscreen", 0, entries())
        assertEquals("the sheet is gone", 0, sheets())
        assertFalse("the old guard fell", call.guard())
        assertEquals("no screen listener is left", 0, listeners())
        val counts = listOf(factory.made, factory.acquired.get(), factory.calls.size)
        val full = onMain { checkNotNull(usd().currentState().fullscreenToken) { "premise: a fullscreen token" } }
        rule.runOnUiThread { usd().exitFullscreen(full) }
        rule.waitForIdle()
        rule.waitUntil(5_000) { entries() == 1 }
        assertEquals("leaving the fullscreen made, acquired and read nothing", counts,
            listOf(factory.made, factory.acquired.get(), factory.calls.size))
        assertEquals("and listens to nothing", 0, listeners())
        lateComplete(call, "success")
        assertNothingShown("the late read", "usd-krw")
    }

    /** S7C_L04b: a news detail left open over the news tab keeps the entry away when the accepted tab moves to a bank tab. */
    @Test fun s7c_l04b_anOpenNewsDetailKeepsTheEntryAway() {
        show(listOf(NewsItem("n1", "fixture news", "https://example.invalid/n1", "fixture", "external_link", t)))
        select(FreeTab.NEWS)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("fixture news").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("fixture news").performClick()
        rule.waitForIdle()
        select(FreeTab.USD)
        assertEquals("no entry under the detail", 0, entries())
        assertEquals("nothing made or read", 0 to 0, factory.made to factory.calls.size)
    }

    /**
     * S7C_L05 (independent review, high): edge to edge, as MainActivity draws, the slot keeps the bottom navigation inset below the
     * entry, so at the end of the page the entry is not under the system navigation bar; a touch through the real window on the
     * entry, with the page scrolled to its end, opens the sheet.
     */
    @Test fun s7c_l05_theEntryStaysAboveTheNavigationBar() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        show()
        val inset = rule.activity.window.decorView.let { view ->
            androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        }
        assertTrue("premise: the emulator has a bottom navigation inset ($inset px)", inset > 0)
        val slot = rule.onNodeWithTag(PremiumTopicTags.BELOW_RATES).fetchSemanticsNode().boundsInRoot
        val entry = rule.onNodeWithTag(BankHistoryTags.ENTRY).fetchSemanticsNode().boundsInRoot
        assertTrue("the inset ($inset px) lies below the entry inside the slot: entry ${entry.bottom}, slot ${slot.bottom}",
            slot.bottom - entry.bottom >= inset - 1f)
        repeat(4) { rule.onNodeWithTag(PremiumTopicTags.PAGE_SCROLL).performTouchInput { swipeUp() }; rule.waitForIdle() }
        val node = rule.onNodeWithTag(BankHistoryTags.ENTRY).fetchSemanticsNode()
        val at = node.positionOnScreen.let { it.x + node.size.width / 2f to it.y + node.size.height / 2f }
        device().click(at.first.toInt(), at.second.toInt())
        rule.waitForIdle()
        assertEquals("a real touch on the entry opened it", 1, factory.calls.size)
        assertEquals(1, sheets())
    }

    private var syncs = 0

    /** The process foreground, a same-owner sync, a reconnection, an issuer and a deletion revision, and time. */
    private fun routeEvents() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        syncs += 1
        rule.runOnUiThread {
            display.value = display.value.copy(rates = display.value.rates.merge(
                listOf(TopicQuote("kb", "usd-krw", 1390.0 + syncs, t + syncs.seconds))))
        }
        rule.waitForIdle()
        rule.runOnUiThread { display.value = display.value.copy(connection = TopicConnectionDisplay.DISCONNECTED) }
        rule.waitForIdle()
        rule.runOnUiThread { display.value = display.value.copy(connection = TopicConnectionDisplay.OPEN) }
        rule.waitForIdle()
        rule.runOnUiThread {
            factory.accessRevisions.value += 1
            factory.deletionRevisions.value += 1
        }
        rule.waitForIdle()
        android.os.SystemClock.sleep(1_500)
        rule.waitForIdle()
    }

    /**
     * S7C_N02: on the real route, with the sheet closed, loading, loaded, failed or refreshing, the process foreground, a same-owner
     * sync publication, a reconnection (the connection lost, then open again), an issuer and a deletion revision and time passing
     * exchange no history — no making, acquisition or read — and an open sheet stays as it was. The route's other ports (tab
     * selection, connection and topic retries, settings, news) and, counted on its own, the consumer's row preference I/O move no
     * more with a sheet in any state than with none. The route has no permission, push, last-bank, history disk, migration or
     * saved-state port; the entry's references are locked by `BankHistoryWiringStructureTest` N02s.
     */
    @Test fun s7c_n02_theRoutesEventsExchangeNoHistoryInAnyState() {
        show()
        fun ports() = listOf(selects.size, connectionRetries.get(), topicRetries.get(), settingsShown, news.fetches.get())
        fun shown() = listOf(sheets(), rowsShown(item("usd-krw")), texts("히스토리 로드 중..."), texts(serverMessage))
        var baseline: Pair<List<Int>, Int>? = null
        for (state in listOf("closed", "loading", "loaded", "failed", "refreshing")) {
            if (state != "closed") open()
            when (state) {
                "loaded", "refreshing" -> {
                    factory.calls.last().complete(Result.success(listOf(item("usd-krw"))))
                    rule.waitForIdle()
                    rule.waitUntil(5_000) { rowsShown(item("usd-krw")) == 1 }
                }
                "failed" -> {
                    factory.calls.last().complete(Result.failure(AlertRepositoryException(serverMessage)))
                    rule.waitForIdle()
                    rule.waitUntil(5_000) { texts(serverMessage) == 1 }
                }
            }
            if (state == "refreshing") {
                val before = factory.calls.size
                pullToRefresh("usd-krw")
                rule.waitUntil(5_000) { factory.calls.size == before + 1 }
            }
            val history = listOf(factory.made, factory.acquired.get(), factory.calls.size)
            val before = ports()
            val rowIo = rows.io.get()
            val sheet = shown()
            routeEvents()
            assertEquals("$state: no history made, acquired or read", history, listOf(factory.made, factory.acquired.get(), factory.calls.size))
            assertEquals("$state: the sheet is as it was", sheet, shown())
            val delta = ports().zip(before) { now, then -> now - then } to (rows.io.get() - rowIo)
            if (baseline == null) baseline = delta
            else assertEquals("$state: the route's other ports and the row I/O moved as with no sheet", baseline, delta)
            if (state != "closed") {
                rule.onNodeWithText("닫기").performClick()
                rule.waitForIdle()
                rule.waitUntil(5_000) { sheets() == 0 }
            }
        }
    }

    /** S7C_N03·U08: the foreground reads nothing and keeps the sheet; a recreation restores nothing. */
    @Test fun s7c_n03_u08_theForegroundReadsNothing_andARecreationRestoresNothing() {
        show(restorable = true)
        for (loaded in listOf(false, true)) {
            open()
            if (loaded) factory.calls.last().complete(Result.success(listOf(item("usd-krw"))))
            rule.waitForIdle()
            val reads = factory.calls.size
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitForIdle()
            assertEquals("loaded=$loaded: the foreground read nothing", reads, factory.calls.size)
            assertEquals("loaded=$loaded: the sheet stays", 1, sheets())
            if (!loaded) { rule.onNodeWithText("닫기").performClick(); rule.waitForIdle() }
        }
        val reads = factory.calls.size
        assertEquals("premise: the open sheet listens", 1, listeners())
        checkNotNull(tester).emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("a saved-state restore brings no sheet back", 0, sheets())
        assertEquals("and the disposed composition left no screen listener", 0, listeners())
        assertEquals("and reads nothing", reads, factory.calls.size)
    }

    // ---- S7C_W01 (screen): the production read's answer, drawn by the real sheet -------------------------------------------

    /**
     * The production read over the production repository and client, whose transport answers every request with [json] at the
     * HTTP boundary: nothing leaves the device.
     */
    private fun productionRead(json: String): BankHistoryRead {
        val source = object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity("A", 1L)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean) = "credential"
        }
        val provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        val http = OkHttpClient.Builder()
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            }
            .build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/").client(http)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        val repository = AlertRepository(AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), wireJson))
        return productionBankHistoryRead(repository)
    }

    /**
     * S7C_W01 (screen): the production read's mixed answer — a usd, a jpy and a eur row from three banks — drawn by the real sheet
     * under each bank tab keeps only that tab's currency's row in the semantics.
     */
    @Test fun s7c_w01_theRealSheetDrawsOnlyTheSelectedCurrencysRowsOfAMixedAnswer() {
        val mixed = listOf(item("usd-krw", "hana"), item("jpy-krw", "shinhan"), item("eur-krw", "woori"))
        val json = buildString {
            append("""{"logs":[""")
            mixed.forEachIndexed { i, row ->
                if (i > 0) append(",")
                append("""{"id":${10 + i},"setting_id":${10 + i},"bank":"${row.bank}","currency":"${row.currency}","condition":"above",""")
                append(""""threshold":1400.5,"rate":1401.2,"sent_at":"2026-10-01T10:0$i:00+09:00"}""")
            }
            append("""],"total_count":${mixed.size}}""")
        }
        val read = productionRead(json)
        val reads = AtomicInteger()
        show(wiring = BankHistoryWiringFactory { _, displayOwner ->
            BankHistoryWiring(object : BankHistoryAccess {
                private var n = 0L
                override fun acquire(owner: TopicDisplayOwner) = TopicGrantToken(++n).let {
                    BankHistoryUse(owner, TopicSessionFence(owner.identity, "epoch", it), TopicUseLifetime(it, 0))
                }.takeIf { displayOwner() == owner }
                override fun admits(use: BankHistoryUse) = displayOwner() == use.owner
            }, BankHistoryRead { owner, currency, guard -> reads.incrementAndGet(); read.read(owner, currency, guard) },
                MutableStateFlow(0L), MutableStateFlow(0L))
        })
        for ((n, pair) in listOf(FreeTab.USD to "usd-krw", FreeTab.JPY to "jpy-krw", FreeTab.EUR to "eur-krw").withIndex()) {
            val (tab, currency) = pair
            select(tab)
            open()
            val own = mixed.single { it.currency == currency }
            rule.waitUntil(5_000) { rowsShown(own) == 1 }
            assertEquals("$tab: one production read", n + 1, reads.get())
            for (row in mixed) {
                assertEquals("$tab: the ${row.currency} row", if (row === own) 1 else 0, rowsShown(row))
            }
            rule.onNodeWithText("닫기").performClick()
            rule.waitForIdle()
            rule.waitUntil(5_000) { sheets() == 0 }
        }
    }
}
