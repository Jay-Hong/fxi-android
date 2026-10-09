package com.jay.fxi

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
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
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogPeriod
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogTab
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Provenance
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2Series
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.time.SystemAppClock
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.graph.GraphScreenHost
import com.jay.fxi.ui.premium.graph.GraphV2Surface
import com.jay.fxi.ui.premium.graph.GraphV2UiTags
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.viewmodel.NewsViewModel
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 CUT-CC5-3 route contract (`cut_cc5_agreed.r1.md`): with a graph host the route opens one mount in an effect,
 * activates exactly the selected usd/jpy/eur tab's holder for the fresh screen's owner while active, releases it on TETHER, NEWS,
 * inactive and dispose, moves it with the owner and the tab, and closes the mount when the host changes, becomes null or the
 * route leaves. Run on a device with a real consumer, a real host over a real assembly (never started: no request is made) and
 * real holders. Each holder's last activation request is read from the holder itself, as the route's effect left it.
 */
class PremiumTopicRouteGraphTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val o2 = TopicDisplayOwner(a1, 2L)
    private val fence = TopicSessionFence(a1, "e1", com.jay.fxi.data.remote.TopicGrantToken(1L))
    private fun rates() = TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1390.0, t), TopicQuote("upbit", "usdt-krw", 1400.0, t)))

    private val display = MutableStateFlow(TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.USD))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var live: AuthIdentityFence = a1
    private var identity by mutableStateOf(a1)
    private val consumer = PremiumTopicConsumer(display, focus, { live }, object : RateRowPreferenceStore {
        override suspend fun preferences(uid: String) = emptyMap<RateRowList, RateRowPreference>()
        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
    }, { o, tab -> focus.value = OwnedTopicFocus(o, tab) }, { }, { _, _ -> }, scope)
    private val hostScopes = mutableListOf<CoroutineScope>()
    private var host by mutableStateOf<GraphScreenHost?>(null)
    private var active by mutableStateOf(true)
    private var shown by mutableStateOf(true)
    /** A recomposition of the route that neither the consumer nor any holder observes. */
    private var tick by mutableStateOf(0)
    private val cacheRoots = java.util.Collections.synchronizedList(mutableListOf<java.io.File>())
    @After fun tearDown() {
        scope.cancel()
        hostScopes.forEach { it.cancel() }
        cacheRoots.forEach { it.deleteRecursively() }
    }

    private val selections = object : GraphSelectionStore {
        override suspend fun readGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection) = GraphSelectionWriteResult.Committed
        override suspend fun confirmGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
    }

    /** The issuer's published access for [fence]: it admits A's grant 1 in user epoch e1, so a holder can take its context. */
    @Volatile private var snapshot: TopicAccessSnapshot = run {
        val binding = com.jay.fxi.data.entitlements.EntitlementsIdentity("A", 1L)
        val record = com.jay.fxi.data.entitlements.AccessFence("A", "e1", "K1")
        TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = com.jay.fxi.data.entitlements.TopicAccessFacts.NONE.copy(
                token = fence.grant,
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

    /**
     * A host over a fresh assembly, started on Main (its requests never answer), whose access admits [fence]; every holder it
     * builds is listed in [built]. The coordinator is listed in [coordinators].
     */
    private val coordinators = java.util.Collections.synchronizedList(mutableListOf<com.jay.fxi.data.graph.GraphV2RequestCoordinator>())
    private val silent = object : GraphV2Fetching {
        override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = awaitCancellation()
        override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean) = awaitCancellation()
    }

    private fun newHost(
        built: MutableList<GraphV2ScreenStateHolder>,
        fetcher: GraphV2Fetching = silent,
        store: GraphSelectionStore = selections,
        cached: Boolean = false
    ): GraphScreenHost {
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { hostScopes += it }
        val authority: TopicUseAuthority = com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority { snapshot }
        val assembly = GraphRuntimeAssembly(
            accessSnapshot = { snapshot },
            accessRevisions = MutableStateFlow(0L),
            liveIdentity = { a1 },
            uses = authority,
            protectedAdmission = { true },
            fetcher = fetcher,
            owners = object : GraphOwnerSource {
                override fun currentIdentity(): AuthIdentityFence = a1
                override suspend fun capture(expected: AuthIdentityFence) = AuthSnapshot(expected.uid, expected.authGeneration, "token")
            },
            // Exposure reads the protected publication, which needs cache ports; an empty disk root under the test's cache.
            cachePorts = if (cached) { gate ->
                val root = java.io.File(rule.activity.cacheDir, "route-graph-${java.util.UUID.randomUUID()}").also { cacheRoots += it }
                com.jay.fxi.data.graph.GraphV2CachePorts(
                    store = com.jay.fxi.data.graph.FileGraphV2DiskStore({ root }, com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec(),
                        com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo(), Dispatchers.IO),
                    gate = gate,
                    onSeedDiagnostic = { }
                )
            } else { _ -> null },
            main = Dispatchers.Main,
            parent = Job(hostScope.coroutineContext[Job]),
            clock = SystemAppClock,
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { }
        )
        assembly.fences.setAccess(true, fence, com.jay.fxi.data.remote.TopicGrantOrigin.NewContext)
        rule.runOnUiThread { assembly.start() }
        coordinators += assembly.coordinator
        return GraphScreenHost(hostScope, { tab ->
            val session = GraphSeriesSelectionSession(store, GraphSelectionAudience.PREMIUM, tab, hostScope, Dispatchers.Main)
            GraphV2ScreenStateHolder(tab, assembly.coordinator, session, { live }, display, focus, assembly.fences.current, authority,
                assembly.gate, MutableStateFlow(0L), hostScope, Dispatchers.Main, recorder = assembly.recorder).also { built += it }
        }, { })
    }

    private fun GraphV2ScreenStateHolder.field(name: String): Any? =
        GraphV2ScreenStateHolder::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun GraphV2ScreenStateHolder.tab(): String = field("tab") as String
    private fun GraphV2ScreenStateHolder.requested(): Any? = field("requestedOwner")
    private fun GraphV2ScreenStateHolder.closed(): Boolean = field("closed") as Boolean

    /** Holder tab → the owner whose activation it holds, for the open holders of [built]; read on Main. */
    private fun requests(built: List<GraphV2ScreenStateHolder>): Map<String, Any?> = onMain {
        built.toList().filterNot { it.closed() }.associate { it.tab() to it.requested() }
    }

    private fun <T> onMain(read: () -> T): T {
        var result: Result<T>? = null
        rule.runOnUiThread { result = runCatching(read) }
        return checkNotNull(result).getOrThrow()
    }

    private fun newBuilt() = java.util.Collections.synchronizedList(mutableListOf<GraphV2ScreenStateHolder>())

    private fun show() {
        val vm = NewsViewModel(object : NewsRepository {
            override suspend fun fetchNews(limit: Int, hours: Double): List<NewsItem> = emptyList()
            override suspend fun saveCache(items: List<NewsItem>) = Unit
            override suspend fun loadCache(): List<NewsItem>? = null
        })
        rule.setContent {
            if (shown) {
                PremiumTopicRoute(consumer = consumer, identity = identity, isActive = active, newsViewModel = vm, userInfo = null,
                    onSignOut = { }, modifier = androidx.compose.ui.Modifier.testTag("route:$tick"), graphHost = host)
            }
        }
        rule.waitForIdle()
    }

    private fun until(label: String, condition: () -> Boolean) = rule.waitUntil(5_000) { condition() }.also {
        assertTrue(label, condition())
    }

    private fun GraphV2ScreenStateHolder.activation(): Any? = onMain { field("activation") }
    private fun activeKey(index: Int = 0): Any? = onMain {
        val c = coordinators[index]
        c.javaClass.getDeclaredField("activeKey").apply { isAccessible = true }.get(c)
    }

    /**
     * CC5-3-R01: the holders the host mounts after the route opened its mount — they arrive later — get the activation: only
     * the selected tab's, for the fresh owner; moving the tab moves it, the earlier holder released.
     */
    @Test fun cc53_R01_theSelectedTabsHolderIsActivatedWhenItArrivesAndMovesWithTheTab() {
        val built = newBuilt()
        host = newHost(built)
        show()
        until("CC5-3-R01 three holders mounted") { onMain { built.toList().count { !it.closed() } } == 3 }
        until("CC5-3-R01 only the usd holder, for o1") { requests(built) == mapOf("usd" to o1, "jpy" to null, "eur" to null) }
        focus.value = OwnedTopicFocus(a1, FreeTab.JPY); rule.waitForIdle()
        until("CC5-3-R01 moved to jpy, usd released") { requests(built) == mapOf("usd" to null, "jpy" to o1, "eur" to null) }
        focus.value = OwnedTopicFocus(a1, FreeTab.EUR); rule.waitForIdle()
        until("CC5-3-R01 moved to eur") { requests(built) == mapOf("usd" to null, "jpy" to null, "eur" to o1) }
    }

    /** CC5-3-R02: TETHER and NEWS release the activation; coming back to usd activates it again. */
    @Test fun cc53_R02_tetherAndNewsReleaseTheActivation() {
        val built = newBuilt()
        host = newHost(built)
        show()
        until("CC5-3-R02 premise: usd active") { requests(built)["usd"] == o1 }
        for (tab in listOf(FreeTab.TETHER, FreeTab.NEWS)) {
            focus.value = OwnedTopicFocus(a1, tab); rule.waitForIdle()
            until("CC5-3-R02 $tab releases it") { requests(built).values.all { it == null } }
            focus.value = OwnedTopicFocus(a1, FreeTab.USD); rule.waitForIdle()
            until("CC5-3-R02 back on usd after $tab") { requests(built)["usd"] == o1 }
        }
    }

    /** CC5-3-R03: an inactive route releases the activation and an active one takes it back; a new owner moves it to that owner. */
    @Test fun cc53_R03_inactiveReleasesAndANewOwnerMovesIt() {
        val built = newBuilt()
        host = newHost(built)
        show()
        until("CC5-3-R03 premise: usd active for o1") { requests(built)["usd"] == o1 }
        active = false; rule.waitForIdle()
        until("CC5-3-R03 inactive: released") { requests(built).values.all { it == null } }
        active = true; rule.waitForIdle()
        until("CC5-3-R03 active again") { requests(built)["usd"] == o1 }
        display.value = TopicDisplayState(o2, rates(), false, TopicConnectionDisplay.OPEN); rule.waitForIdle()
        until("CC5-3-R03 the new owner") { requests(built)["usd"] == o2 }
    }

    /** CC5-3-R04: when the route leaves, the mount is closed: every holder closes. */
    @Test fun cc53_R04_leavingTheRouteClosesTheMount() {
        val built = newBuilt()
        host = newHost(built)
        show()
        until("CC5-3-R04 premise: usd active") { requests(built)["usd"] == o1 }
        shown = false; rule.waitForIdle()
        until("CC5-3-R04 every holder closed") { onMain { built.size == 3 && built.toList().all { it.closed() } } }
    }

    /**
     * CC5-3-R06: a new identity is enqueued to every mounted holder as a context change: each rebinds to the new live identity
     * with no other input moving.
     */
    @Test fun cc53_R06_aNewIdentityReachesEveryHolder() {
        val built = newBuilt()
        host = newHost(built)
        show()
        until("CC5-3-R06 premise: three holders bound to A") { onMain { built.size == 3 && built.toList().all { it.field("boundIdentity") == a1 } } }
        val b1 = AuthIdentityFence("B", 1L)
        live = b1
        identity = b1; rule.waitForIdle()
        until("CC5-3-R06 every holder rebound to B") { onMain { built.toList().all { it.field("boundIdentity") == b1 } } }
    }

    /**
     * CC5-3-R07: once active, a same-owner display update, a recomposition and a fullscreen round trip keep the activation: the
     * holder's activation and context are the very ones from before.
     */
    @Test fun cc53_R07_updatesAndFullscreenKeepTheActivation() {
        val built = newBuilt()
        host = newHost(built)
        show()
        val usd = { onMain { built.toList().single { it.tab() == "usd" && !it.closed() } } }
        until("CC5-3-R07 premise: usd activated with a context") { runCatching { usd().activation() != null }.getOrDefault(false) }
        val activation = usd().activation()
        val generation = onMain { usd().field("contextGeneration") }
        val published = onMain { consumer.state.value }
        display.value = TopicDisplayState(o1, rates().merge(listOf(TopicQuote("kb", "usd-krw", 1395.0, t.plus(kotlin.time.Duration.parse("1s"))))), false, TopicConnectionDisplay.OPEN)
        rule.waitForIdle()
        until("CC5-3-R07 premise: the consumer published the update") { onMain { consumer.state.value != published } }
        val token = onMain { checkNotNull(usd().currentState().inlineToken) { "premise: an inline token" } }
        rule.runOnUiThread { usd().enterFullscreen(token) }; rule.waitForIdle()
        val full = onMain { usd().currentState().fullscreenToken }
        if (full != null) { rule.runOnUiThread { usd().exitFullscreen(full) }; rule.waitForIdle() }
        assertTrue("CC5-3-R07 the same activation", activation === usd().activation())
        assertEquals("CC5-3-R07 the same context", generation, onMain { usd().field("contextGeneration") })
    }

    /**
     * CC5-3-R08: moving usd → jpy releases the usd holder before activating the jpy one: the shared coordinator ends with the
     * jpy key active, not with none.
     */
    @Test fun cc53_R08_aTabMoveDeactivatesBeforeActivating() {
        val built = newBuilt()
        host = newHost(built)
        show()
        until("CC5-3-R08 premise: usd active in the coordinator") { (activeKey() as? GraphKey)?.tab == "usd" }
        focus.value = OwnedTopicFocus(a1, FreeTab.JPY); rule.waitForIdle()
        until("CC5-3-R08 the coordinator's active key is jpy") { (activeKey() as? GraphKey)?.tab == "jpy" }
    }

    /**
     * CC5-3-R09: leaving and coming straight back with the same host opens a new mount whose holders come only after the old
     * ones have closed, and the new usd holder is activated.
     */
    @Test fun cc53_R09_aQuickRemountRestoresAfterTheOldHoldersClose() {
        val built = newBuilt()
        host = newHost(built)
        show()
        until("CC5-3-R09 premise: usd active") { requests(built)["usd"] == o1 }
        shown = false; rule.waitForIdle()
        shown = true; rule.waitForIdle()
        until("CC5-3-R09 a new set of three holders") { onMain { built.size == 6 && built.toList().take(3).all { it.closed() } } }
        until("CC5-3-R09 the new usd holder is active") { requests(built)["usd"] == o1 }
    }

    /**
     * CC5-3-R05: a new host closes the old mount — its holders close — and opens one on the new host, whose usd holder is
     * activated; a null host closes the mount and mounts nothing.
     */
    @Test fun cc53_R05_aNewOrNullHostReplacesTheMount() {
        val first = newBuilt()
        host = newHost(first)
        show()
        until("CC5-3-R05 premise: the first host's usd active") { requests(first)["usd"] == o1 }
        val second = newBuilt()
        host = newHost(second); rule.waitForIdle()
        until("CC5-3-R05 the first host's holders closed") { onMain { first.toList().all { it.closed() } } }
        until("CC5-3-R05 the second host's usd active") { requests(second)["usd"] == o1 }
        host = null; rule.waitForIdle()
        until("CC5-3-R05 a null host: the mount closed") { onMain { second.toList().all { it.closed() } } }
        assertEquals("CC5-3-R05 nothing more mounted", 3, second.size)
    }

    /** Answers at once: a catalog of two KRW series per FX tab and period (only [x] shown by default) and their data. */
    private fun answering(): GraphV2Fetching = object : GraphV2Fetching {
        private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, okhttp3.Headers.headersOf(), body, null, byteArrayOf(1))
        override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = ok(GraphV2CatalogResponse(
            tabs = listOf("usd", "jpy", "eur").map { tab ->
                GraphV2CatalogTab(tab, tab, emptyMap(), GraphPeriod.entries.associate {
                    it.code to GraphV2CatalogPeriod(listOf(x(tab), y(tab)), listOf(x(tab)))
                })
            },
            version = "2026-05-27",
            supportedPeriods = listOf("1w", "3m", "1y"),
            cacheTtlSeconds = 172800
        ))
        override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2TabResponse> {
            val now = SystemAppClock.now()
            val day = kotlin.time.Duration.parse("1d")
            val hour = kotlin.time.Duration.parse("1h")
            val points = listOf(GraphV2Point(now - hour - hour, 1390.0, "x"), GraphV2Point(now - hour, 1391.0, "x"))
            return ok(GraphV2TabResponse(
                tab = key.tab,
                period = key.period.code,
                series = listOf(x(key.tab), y(key.tab)).map { id ->
                    GraphV2Series(id, id, "krw", "KRW", 2, points, GraphV2Provenance(false, emptyList()), null)
                },
                metadata = GraphV2Metadata(
                    now, if (key.period == GraphPeriod.ONE_DAY) "10min" else "1d",
                    GraphV2Range((now - day * 400).toString().take(10), now.toString().take(10))
                )
            ))
        }
    }

    private fun x(tab: String) = "investing.$tab-krw"
    private fun y(tab: String) = "kb.$tab-krw"

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * CC5-3-R10: the real route hands the selected holder's state, fresh reads and six actions to the screen's graph slot, on
     * jpy so that a slot bound to another tab's holder is told apart. The graph's publications reach the drawn surface, and each
     * action, made through the drawn UI, reaches that holder and is drawn back: retrying an unreadable selection confirms it,
     * the surface reports itself shown, a series toggle and a period change land in the holder and on screen, and a fullscreen
     * round trip opens and closes the holder's fullscreen. Last, a withdrawal no revision announces is drawn at the route's
     * next recomposition: the slot reads the holder fresh rather than its last publication.
     */
    @Test fun cc53_R10_theRouteHandsTheSelectedHoldersStateAndActionsToTheSlot() {
        val built = newBuilt()
        val confirms = java.util.concurrent.atomic.AtomicInteger()
        val store = object : GraphSelectionStore {
            override suspend fun readGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
            override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection) = GraphSelectionWriteResult.Committed
            // jpy's first confirmation is unreadable; every later one, and every other tab's, finds nothing stored.
            override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult =
                if (key.tab == "jpy" && confirms.getAndIncrement() == 0) {
                    GraphSelectionReadResult.Unreadable(com.jay.fxi.data.local.GraphSelectionUnreadableReason.IO)
                } else GraphSelectionReadResult.Absent
        }
        focus.value = OwnedTopicFocus(a1, FreeTab.JPY)
        host = newHost(built, answering(), store, cached = true)
        show()
        val jpy = { onMain { built.toList().single { it.tab() == "jpy" && !it.closed() } } }
        val inline = GraphV2Surface.INLINE
        val full = GraphV2Surface.FULLSCREEN

        until("CC5-3-R10 premise: the unreadable selection offers a retry") { exists(GraphV2UiTags.selectionRetry(inline)) }
        assertEquals("CC5-3-R10 premise: one confirmation before the click", 1, confirms.get())
        rule.onNodeWithTag(GraphV2UiTags.selectionRetry(inline), useUnmergedTree = true).performClick()
        until("CC5-3-R10 retrySelection reached the holder") { confirms.get() == 2 }

        until("CC5-3-R10 the holder's publication is drawn") { exists(GraphV2UiTags.chart(inline)) }
        until("CC5-3-R10 setSurfaceVisible reached the holder") { onMain { jpy().field("inlineVisible") as Boolean } }

        val yId = y("jpy")
        until("CC5-3-R10 premise: the second series is drawn unselected") {
            exists(GraphV2UiTags.toggle(inline, yId)) && !exists(GraphV2UiTags.check(inline, yId)) &&
                onMain { jpy().currentState().toggles.single { it.seriesId == yId }.selected } == false
        }
        rule.onNodeWithTag(GraphV2UiTags.toggle(inline, yId), useUnmergedTree = true).performClick()
        until("CC5-3-R10 toggleSeries reached the holder and is drawn") {
            onMain { jpy().currentState().toggles.single { it.seriesId == yId }.selected } && exists(GraphV2UiTags.check(inline, yId))
        }

        val target = onMain { jpy().currentState().let { s -> s.periods.first { it != s.activePeriod } } }
        rule.onNodeWithTag("period_tab:${target.code}", useUnmergedTree = true).performClick()
        until("CC5-3-R10 selectPeriod reached the holder and is drawn") {
            onMain { jpy().currentState().activePeriod } == target &&
                runCatching { rule.onNodeWithTag("period_tab:${target.code}", useUnmergedTree = true).assertIsSelected() }.isSuccess
        }

        rule.onNodeWithTag(GraphV2UiTags.expand(), useUnmergedTree = true).performClick()
        until("CC5-3-R10 enterFullscreen reached the holder and the fullscreen is drawn") {
            onMain { jpy().currentState().fullscreenOpen } && exists(GraphV2UiTags.root(full))
        }
        rule.onNodeWithTag(GraphV2UiTags.close(), useUnmergedTree = true).performClick()
        until("CC5-3-R10 exitFullscreen reached the holder and the fullscreen is gone") {
            !onMain { jpy().currentState().fullscreenOpen } && !exists(GraphV2UiTags.root(full))
        }

        until("CC5-3-R10 premise: the chart is drawn again") { exists(GraphV2UiTags.chart(inline)) }
        val published = onMain { jpy().state.value }
        // The grant stops standing with no access revision: no holder or consumer input moves.
        snapshot = snapshot.copy(facts = snapshot.facts.copy(tokenStanding = false))
        rule.waitForIdle()
        assertTrue("CC5-3-R10 premise: nothing republished the holder", published === onMain { jpy().state.value })
        assertTrue("CC5-3-R10 premise: the chart is still drawn before the recomposition", exists(GraphV2UiTags.chart(inline)))
        tick += 1
        until("CC5-3-R10 the route's next recomposition reads the holder fresh") { !exists(GraphV2UiTags.chart(inline)) }
    }
}
