package com.jay.fxi

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import com.jay.fxi.data.alerts.*
import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.graph.GraphRuntimeAssembly
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.AuthBoundResult
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.time.SystemAppClock
import com.jay.fxi.ui.alert.BankAlertSectionSource
import com.jay.fxi.ui.alert.BankAlertSectionSourceFactory
import com.jay.fxi.ui.alert.BankAlertSectionTags
import com.jay.fxi.ui.alert.BankHistoryRead
import com.jay.fxi.ui.alert.BankHistoryWiring
import com.jay.fxi.ui.alert.BankHistoryWiringFactory
import com.jay.fxi.ui.alert.ComparisonAlertSectionSource
import com.jay.fxi.ui.alert.ComparisonAlertSectionSourceFactory
import com.jay.fxi.ui.alert.CountingSourceAlertSection
import com.jay.fxi.ui.alert.CountingSourceHistoryWiring
import com.jay.fxi.ui.alert.OwnerComparisonSectionSource
import com.jay.fxi.ui.alert.SourceAlertSectionTags
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.graph.GraphScreenHost
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import com.jay.fxi.ui.premium.graph.GraphV2UiTags
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import com.jay.fxi.ui.theme.*
import com.jay.fxi.ui.viewmodel.NewsViewModel
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

internal class CountingComparisonSource(initial: ComparisonAlertSettingsState,
    private val identityNow: (() -> AuthIdentityFence?)? = null) : ComparisonAlertSectionSourceFactory {
    val settings = MutableStateFlow(initial)
    private val creates = AtomicInteger()
    private val starts = AtomicInteger()
    val ensures = AtomicInteger()
    val authoritatives = AtomicInteger()
    var reader: ComparisonAlertSectionSource? = null
    fun counts() = listOf(creates.get(), ensures.get(), starts.get())
    override fun create(context: Context): ComparisonAlertSectionSource {
        creates.incrementAndGet()
        identityNow?.let { current ->
            val identity = current()
            val adopted = identity?.let { ComparisonAlertRowPolicy.adopt(it.uid, emptyList()) }
            settings.value = ComparisonAlertSettingsState(AlertLoadPhase.Ready, identity, adopted)
        }
        val real = reader
        val flow = real?.state ?: settings
        return object : ComparisonAlertSectionSource {
            override val state = object : StateFlow<ComparisonAlertSettingsState> by flow {
                @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
                override suspend fun collect(collector: FlowCollector<ComparisonAlertSettingsState>): Nothing {
                    starts.incrementAndGet()
                    flow.collect(collector)
                }
            }
            override fun ensure() { ensures.incrementAndGet(); real?.ensure() }
            override fun authoritative() { authoritatives.incrementAndGet(); real?.authoritative() }
        }
    }
}

@Composable
internal fun comparisonTestFactory(consumer: PremiumTopicConsumer) = remember(consumer) {
    CountingComparisonSource(ComparisonAlertSettingsState()) { consumer.state.value.ui.owner?.identity }
}

class ComparisonAlertSectionRouteTest {
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
    private val sources = CountingSourceAlertSection()
    /** S8-4b: the entry above the section reads this counting fake, never the production wiring. */
    private val sourceHistory = CountingSourceHistoryWiring()
    private val bank = Bank()
    private var active by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private var host by mutableStateOf<GraphScreenHost?>(null)
    private val hostScopes = mutableListOf<CoroutineScope>()
    private var settingsShown = 0
    private val historyMade = AtomicInteger()
    private val history = BankHistoryWiringFactory { _, _ ->
        historyMade.incrementAndGet()
        BankHistoryWiring(object : BankHistoryAccess {
            override fun acquire(owner: TopicDisplayOwner): BankHistoryUse? = null
            override fun admits(use: BankHistoryUse) = false
        }, BankHistoryRead { _, _, _ -> awaitCancellation() }, MutableStateFlow(0L), MutableStateFlow(0L))
    }
    @After fun tearDown() {
        scope.cancel()
        hostScopes.forEach { it.cancel() }
    }

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
    private val stamp = Instant.parse("2026-10-01T01:00:00Z")
    private fun row(id: Int, tab: String = "usd", kind: String = "absolute"): ComparisonAlertSetting {
        val tether = tab == "tether"
        val asset = if (tether) "usdt-krw" else "$tab-krw"
        return ComparisonAlertSetting(id, a1.uid, tab, if (tether) "bithumb" else "kb", asset,
            if (tether && kind == "absolute") "upbit" else "hana", if (kind == "signed") "usd-krw" else asset,
            kind, "gte", 4.25, true, false, 60, null, Instant.fromEpochMilliseconds(stamp.toEpochMilliseconds() + (id % 7) * 1000L), stamp)
    }
    private fun mixed() = listOf(row(81, "tether", "signed"), row(62), row(17, "jpy"), row(51, "tether"), row(14, "eur"),
        row(12, "tether", "signed").copy(isEnabled = false, triggered = true), row(3), row(8, "tether"),
        row(90).copy(leftSource = "krx"), row(91).copy(rightSource = "krx"), row(92).copy(userId = "other"),
        row(93), row(93).copy(userId = "other"))
    private fun ready(rows: List<ComparisonAlertSetting>) = ComparisonAlertSettingsState(AlertLoadPhase.Ready, a1,
        ComparisonAlertRowPolicy.adopt(a1.uid, rows))

    private var factory by mutableStateOf(CountingComparisonSource(ready(mixed())))
    private val news = NewsViewModel(NoNews(listOf(NewsItem("n1", "fixture news", "https://example.invalid/n1", "fixture", "external_link", t))))

    private var restoration: androidx.compose.ui.test.junit4.StateRestorationTester? = null
    private fun show(restorable: Boolean = false) {
        val content: @Composable () -> Unit = {
            if (mounted) PremiumTopicRoute(consumer, liveAccount, active, news, null, {},
                settingsContent = { _, _, _ -> settingsShown++ }, graphHost = host, bankHistoryWiring = history,
                bankAlertSectionSourceFactory = bank, sourceAlertSectionSourceFactory = sources,
                sourceHistoryWiring = sourceHistory, comparisonAlertSectionSourceFactory = factory)
        }
        if (restorable) {
            val tester = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
            restoration = tester
            tester.setContent(content)
        } else rule.setContent(content)
        rule.waitForIdle()
    }

    private fun select(tab: FreeTab) {
        rule.runOnUiThread { focus.value = OwnedTopicFocus(liveAccount, tab) }
        rule.waitForIdle()
    }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun keep(tag: String) = checkNotNull(rule.onNodeWithTag(tag, true).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun click(tag: String) { val action = keep(tag); rule.runOnUiThread { action() }; rule.waitForIdle() }
    private fun graph(tab: FreeTab) = built.single { it.field("tab") == tab.serverTab && it.field("closed") == false }
    private fun closeGraph(tab: FreeTab = FreeTab.TETHER) { rule.runOnUiThread { graph(tab).let { it.exitFullscreen(checkNotNull(it.currentState().fullscreenToken)) } }; rule.waitForIdle() }
    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes()
    private fun texts(value: String) = rule.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun publish(state: ComparisonAlertSettingsState) { rule.runOnUiThread { factory.settings.value = state }; rule.waitForIdle() }
    private fun checkRows(kind: String, ids: List<Int>, enabled: Int) {
        val parent = hasAnyAncestor(hasTestTag("comparison-$kind"))
        val rows = rule.onAllNodes(parent and SemanticsMatcher("comparison row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("comparison-row-") == true
        }, true).fetchSemanticsNodes()
        assertEquals(ids, rows.map { it.config[SemanticsProperties.TestTag].substringAfterLast('-').toInt() })
        assertEquals((ids.size - 1).coerceAtLeast(0), nodes("comparison-$kind-divider").size)
        if (enabled > 0) rule.onNodeWithTag("comparison-$kind-count", true).assertTextEquals(enabled.toString())
        else assertTrue(nodes("comparison-$kind-count").isEmpty())
        ids.lastOrNull()?.let { rule.onNodeWithTag("comparison-row-$it", true).performScrollTo() }
    }
    private fun activeCount(n: Int) {
        rule.waitUntil(5000) { factory.settings.subscriptionCount.value == n }
        assertEquals(n, nodes("comparison-signed").size + nodes("comparison-absolute").size)
    }

    @Test fun U01_routeSlotsAndDemand() {
        active = false
        show(); activeCount(0); assertEquals(listOf(0, 0, 0), factory.counts())
        rule.runOnUiThread { display.value = display.value.copy(owner = null); active = true }
        rule.waitForIdle(); activeCount(0); assertEquals(listOf(0, 0, 0), factory.counts())
        select(FreeTab.NEWS)
        rule.runOnUiThread { display.value = display.value.copy(owner = o1) }
        rule.waitForIdle(); activeCount(0); assertEquals(listOf(0, 0, 0), factory.counts())
        rule.onNodeWithText("fixture news").performClick()
        select(FreeTab.TETHER); activeCount(0); assertEquals(listOf(0, 0, 0), factory.counts())
        back(); activeCount(2); assertEquals(listOf(2, 2, 2), factory.counts())
        checkRows("signed", listOf(81, 12), 1); checkRows("absolute", listOf(51, 8), 2)
        val sections = listOf(SourceAlertSectionTags.ROOT, "comparison-signed", "comparison-absolute").map {
            nodes(it).single().positionInRoot.y
        }
        assertEquals(sections.sorted(), sections)
        for ((tab, ids) in listOf(FreeTab.USD to listOf(62, 3), FreeTab.JPY to listOf(17), FreeTab.EUR to listOf(14))) {
            select(tab); activeCount(1); checkRows("absolute", ids, ids.size)
            assertTrue(nodes(BankAlertSectionTags.ROOT).single().positionInRoot.y < nodes("comparison-absolute").single().positionInRoot.y)
        }
        val many = (1..32).map { row(200 - it, "eur").copy(isEnabled = it % 3 == 0) }
        publish(ready(many)); checkRows("absolute", many.map { it.id }, 10)
        for (tab in listOf(FreeTab.USD, FreeTab.TETHER)) {
            select(tab)
            val sibling = if (tab == FreeTab.TETHER) sources.ensures else bank.ensures
            fun siblingSubscriptions() = if (tab == FreeTab.TETHER) sources.subs() else
                bank.created.get() to bank.settingsFlow.subscriptionCount.value
            val subscriptions = siblingSubscriptions()
            assertTrue(subscriptions.first > 0 && subscriptions.second > 0)
            val n = sibling.get()
            val old = factory
            factory = CountingComparisonSource(ready(mixed()))
            rule.waitForIdle()
            val expected = if (tab == FreeTab.TETHER) 2 else 1
            activeCount(expected)
            assertEquals(List(3) { expected }, factory.counts()); assertEquals(0, old.settings.subscriptionCount.value)
            assertEquals(n, sibling.get())
            assertEquals(subscriptions, siblingSubscriptions())
        }
        host = newHost(); rule.waitForIdle()
        for (tag in listOf(PremiumTopicTags.SETTINGS, PremiumTopicTags.FULLSCREEN, GraphV2UiTags.expand())) {
            if (tag == GraphV2UiTags.expand()) rule.waitUntil(5000) { nodes(tag).size == 1 }
            val old = factory.counts()
            click(tag); activeCount(0)
            assertTrue(nodes(PremiumTopicTags.BELOW_RATES).isEmpty())
            assertEquals(old, factory.counts())
            when (tag) {
                PremiumTopicTags.SETTINGS -> back()
                PremiumTopicTags.FULLSCREEN -> click(PremiumTopicTags.FULLSCREEN_CLOSE)
                else -> closeGraph()
            }
            activeCount(2); assertEquals(old.map { it+2 }, factory.counts())
        }
    }

    @Test fun U02_readOnlyBodyLabelsErrorsAndIndependentExpansion() {
        val signed = row(81, "tether", "signed")
        val absolute = row(8, "tether").copy(leftSource = "upbit", rightSource = "bithumb", operator = "lte", threshold = 10000.0)
        val rows = listOf(signed.copy(threshold = 30.0), signed.copy(id=12, threshold=0.0),
            signed.copy(id=13, threshold=-4.125, operator="lte", triggered=true, isEnabled=false), absolute)
        factory.settings.value = ready(rows)
        show(restorable = true)
        assertEquals(1, nodes("comparison-won-icon").size)
        assertEquals(1, nodes("comparison-swap-icon").size)
        for (label in listOf("빗썸 vs 하나 김프 +30원 이상", "빗썸 vs 하나 김프 +0원 이상", "빗썸 vs 하나 김프 -4.12원 이하",
            "업비트−빗썸 차이 10000원 이하 좁혀지면")) rule.onNodeWithContentDescription(label).assertExists()
        for (text in listOf("빗썸-하나", "+30", "0", "-4.12", "10000", "업비트↔빗썸", "△ 이상", "▽ 이하", "1분마다", "알림 발송됨"))
            assertTrue(text, texts(text) > 0)
        for (r in rows) {
            val subtree = hasAnyAncestor(hasTestTag("comparison-row-${r.id}"))
            rule.onNode(subtree and isToggleable(), true).let { if (r.isEnabled) it.assertIsOn() else it.assertIsOff() }
            rule.onNode(subtree and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (r.isEnabled) "켜짐" else "꺼짐"), true).assertExists()
            assertTrue(rule.onAllNodes((subtree or hasTestTag("comparison-row-${r.id}")) and hasClickAction(), true).fetchSemanticsNodes().isEmpty())
        }
        for (kind in listOf("signed", "absolute")) {
            val actions = rule.onAllNodes(hasAnyAncestor(hasTestTag("comparison-$kind")) and hasClickAction(), true)
                .fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) }
            assertEquals(listOf("comparison-$kind-history", "comparison-$kind-refresh", "comparison-$kind-expand"), actions)
        }
        val n = factory.counts()
        click("comparison-signed-expand"); assertTrue(nodes("comparison-row-81").isEmpty()); checkRows("absolute", listOf(8), 1)
        click("comparison-signed-expand"); click("comparison-absolute-expand"); checkRows("signed", listOf(81, 12, 13), 2)
        assertTrue(nodes("comparison-row-8").isEmpty()); assertEquals(n, factory.counts())
        checkNotNull(restoration).emulateSavedInstanceStateRestore(); rule.waitForIdle()
        checkRows("absolute", listOf(8), 1)
        click("comparison-absolute-expand")
        select(FreeTab.USD); select(FreeTab.TETHER); checkRows("signed", listOf(81, 12, 13), 2); checkRows("absolute", listOf(8), 1)
        click("comparison-signed-expand"); click("comparison-absolute-expand")
        click(PremiumTopicTags.FULLSCREEN); click(PremiumTopicTags.FULLSCREEN_CLOSE)
        checkRows("signed", listOf(81, 12, 13), 2); checkRows("absolute", listOf(8), 1)
        select(FreeTab.USD)
        publish(ready(listOf(row(62))))
        rule.onNodeWithContentDescription("국민−하나 차이 4.2원 이상 벌어지면").assertExists()
        for (value in listOf("국민↔하나", "4.2", "△ 이상")) assertTrue(texts(value)>0)
        for (empty in listOf(emptyList(), listOf(absolute))) {
            publish(ready(empty)); assertEquals(1, texts("소스 간 가격 차이 알림")); checkRows("absolute", emptyList(), 0)
        }
        val failures = listOf(AlertLoadFailure.AuthRequired to "로그인이 필요합니다.", AlertLoadFailure.Forbidden to "접근 권한이 없습니다.",
            AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null) to "네트워크 연결을 확인해 주세요.",
            AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, 500) to "알림 설정을 불러오지 못했습니다.")
        for ((failure, message) in failures) {
            val phase = AlertLoadPhase.TerminalError(failure)
            publish(ComparisonAlertSettingsState(phase, a1))
            for ((_, word) in failures) assertEquals(if (word == message) 1 else 0, texts(word))
            val before = factory.authoritatives.get()
            click("comparison-absolute-retry"); assertEquals(before+1, factory.authoritatives.get())
            for (rowsAfter in listOf(emptyList(), listOf(row(62)))) {
                publish(ready(rowsAfter).copy(phase = phase))
                assertEquals(0, texts(message)); checkRows("absolute", rowsAfter.map { it.id }, rowsAfter.size)
                assertTrue(nodes("comparison-absolute-retry").isEmpty())
            }
        }
        for (phase in listOf(AlertLoadPhase.Loading, AlertLoadPhase.Retrying, AlertLoadPhase.Idle)) {
            publish(ComparisonAlertSettingsState(phase)); assertTrue(texts("불러오는 중...")>0)
            assertTrue(nodes("comparison-absolute-retry").isEmpty())
        }
        publish(ready(listOf(row(62))).copy(identity = AuthIdentityFence(a1.uid, 2)))
        assertTrue(nodes("comparison-row-62").isEmpty()); assertTrue(nodes("comparison-absolute-count").isEmpty())
    }

    @Test fun U02_headerRefreshAfterRevokedFirstUse() {
        val revisions = MutableStateFlow(0L)
        val premium = MutableStateFlow(OwnedPremiumAccess(a1.uid, a1.authGeneration, PremiumAccessState.PremiumConfirmed))
        val access = AlertSettingsAccess({ a1 }, { graphFence }, SnapshotTopicUseAuthority { snapshot }, { premium.value }, { true },
            GraphProtectedAdmission({ a1 }, DeletionAdmissionStore()))
        val held = CompletableDeferred<Unit>()
        val gets = AtomicInteger()
        lateinit var reader: ComparisonAlertSettingsOwner
        rule.runOnUiThread {
            reader = ComparisonAlertSettingsOwner({ _, _ ->
                val index = gets.incrementAndGet()
                if (index == 1) withContext(NonCancellable) { held.await() }
                AuthBoundResult({}, Result.success(listOf(row(index, "tether"))))
            }, access, { a1 }, premium, revisions, scope, { delay(it) }, { 0.0 })
            factory.reader = OwnerComparisonSectionSource(reader)
            reader.onForeground(false)
        }
        try {
            show(); rule.waitUntil(5000) { gets.get() == 1 && factory.ensures.get() == 2 }
            val refresh = keep("comparison-absolute-refresh")
            rule.runOnUiThread {
                snapshot = snapshot.copy(userInvalidations=snapshot.userInvalidations+1,
                    facts=snapshot.facts.copy(userBlocks=setOf(TopicAccessBlock.PERSISTENCE_HOLD)))
                revisions.value++
            }
            rule.waitUntil(5000) { reader.state.value == ComparisonAlertSettingsState() }
            rule.runOnUiThread {
                held.complete(Unit)
                snapshot = snapshot.copy(facts=snapshot.facts.copy(userBlocks=emptySet()))
                revisions.value++
            }
            rule.waitForIdle()
            assertEquals(1, gets.get())
            assertTrue(nodes("comparison-row-1").isEmpty())
            for (kind in listOf("signed", "absolute")) {
                rule.onNode(hasText("불러오는 중...") and hasAnyAncestor(hasTestTag("comparison-$kind")), true).assertExists()
            }
            rule.runOnUiThread { refresh() }
            rule.waitUntil(5000) { nodes("comparison-row-2").size == 1 }
            assertEquals(1, factory.authoritatives.get())
            assertEquals(2, gets.get()); assertEquals(2, factory.ensures.get())
            assertTrue(nodes("comparison-row-1").isEmpty())
        } finally {
            held.complete(Unit)
        }
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test fun U02_rowColorsFollowDirectionAndStatus() {
        val first = row(1, "tether", "signed").copy(threshold = 30.0)
        val second = first.copy(id = 2, threshold = -4.125, operator = "lte", triggered = true)
        factory.settings.value = ready(listOf(first, second, second.copy(id = 3, isEnabled = false)))
        show()
        fun color(id: Int, text: String, expected: Color) {
            val node = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("comparison-row-$id")), true)
            node.performScrollTo()
            val pixels = node.captureToImage().toPixelMap()
            assertTrue("$id $text: expected $expected", (0 until pixels.height).any { y ->
                (0 until pixels.width).any { x ->
                    val c = pixels[x, y]
                    kotlin.math.abs(c.red - expected.red) + kotlin.math.abs(c.green - expected.green) +
                        kotlin.math.abs(c.blue - expected.blue) < 0.12f
                }
            })
        }
        color(1, "+30", PrimaryText)
        color(1, "△ 이상", PositiveColor)
        color(1, "1분마다", Primary)
        color(2, "▽ 이하", NegativeColor)
        color(2, "알림 발송됨", StatusConnecting)
        color(3, "-4.12", PrimaryText.copy(alpha = 0.5f).compositeOver(CardBackground))
    }

    @Test fun U02_thresholdsUseFixedDigitsAndNoGrouping() {
        val original = Locale.getDefault()
        try {
            show()
            for (locale in listOf(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(locale)
                for (tab in listOf(FreeTab.USD, FreeTab.TETHER)) {
                    select(tab)
                    for ((index, n) in listOf(4.0, 4.125, 4.25, 1000.0, 10000.0).withIndex()) {
                        val row = row(index+1, checkNotNull(tab.serverTab)).copy(threshold=n)
                        publish(ready(listOf(row)))
                        val expected = if (tab == FreeTab.USD) listOf("4.0", "4.1", "4.2", "1000.0", "10000.0")
                            else listOf("4", "4.12", "4.25", "1000", "10000")
                        rule.onNode(hasText(expected[index]) and hasAnyAncestor(hasTestTag("comparison-row-${row.id}")), true).assertExists()
                    }
                }
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun U03_retainedRefreshAndRetryAreBoundToOneAppearance() {
        val failure = ComparisonAlertSettingsState(AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden), a1)
        factory.settings.value = failure
        host = newHost(); show()
        for (tab in listOf(FreeTab.TETHER, FreeTab.USD)) {
            select(tab)
            for (away in listOf("tab", "generation", "grant", "dispose", "inactive", "settings", "rates", "graph", "detail", "factory")) {
                val oldFactory = factory
                val kinds = if (tab == FreeTab.TETHER) listOf("signed", "absolute") else listOf("absolute")
                val stale = kinds.flatMap { listOf(keep("comparison-$it-refresh"), keep("comparison-$it-retry")) }
                val n = oldFactory.authoritatives.get()
                fun staleNow() { stale.forEach { it() }; assertEquals(n, oldFactory.authoritatives.get()) }
                when (away) {
                    "tab" -> rule.runOnUiThread {
                        for (next in listOf(FreeTab.NEWS, tab)) {
                            focus.value = OwnedTopicFocus(a1, next); assertEquals(next, consumer.currentState().ui.selectedTab)
                        }
                        staleNow()
                    }
                    "generation", "grant" -> rule.runOnUiThread {
                        val identity = if (away == "generation") AuthIdentityFence(a1.uid, 2) else a1
                        for (next in listOf(TopicDisplayOwner(identity, 2L), o1)) {
                            liveAccount = next.identity; focus.value = OwnedTopicFocus(liveAccount, tab)
                            display.value = display.value.copy(owner=next); assertEquals(next, consumer.currentState().ui.owner)
                        }
                        staleNow()
                    }
                    "dispose", "inactive" -> {
                        if (away == "dispose") mounted=false else active=false
                        rule.waitForIdle(); activeCount(0); rule.runOnUiThread { staleNow() }
                        mounted=true; active=true
                    }
                    "factory" -> { factory=CountingComparisonSource(failure); rule.waitForIdle(); assertEquals(0, oldFactory.settings.subscriptionCount.value) }
                    "detail" -> {
                        select(FreeTab.NEWS); rule.onNodeWithText("fixture news").performClick(); select(tab); activeCount(0)
                        rule.runOnUiThread { staleNow() }; back()
                    }
                    else -> {
                        val open = keep(when (away) {
                            "settings" -> PremiumTopicTags.SETTINGS
                            "rates" -> PremiumTopicTags.FULLSCREEN
                            else -> GraphV2UiTags.expand()
                        })
                        rule.runOnUiThread { open(); staleNow() }
                        rule.waitForIdle(); activeCount(0)
                        when (away) {
                            "settings" -> back()
                            "rates" -> click(PremiumTopicTags.FULLSCREEN_CLOSE)
                            else -> closeGraph(tab)
                        }
                    }
                }
                rule.waitForIdle()
                val before = factory.authoritatives.get()
                rule.runOnUiThread { staleNow() }
                assertEquals(before, factory.authoritatives.get())
                click("comparison-absolute-refresh"); click("comparison-absolute-retry")
                assertEquals(before+2, factory.authoritatives.get())
            }
        }
    }
}
