package com.jay.fxi

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.SourceAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.local.SourceSelectionSnapshot
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
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.time.SystemAppClock
import com.jay.fxi.ui.alert.BankAlertSectionSource
import com.jay.fxi.ui.alert.BankAlertSectionSourceFactory
import com.jay.fxi.ui.alert.BankAlertSectionTags
import com.jay.fxi.ui.alert.BankHistoryRead
import com.jay.fxi.ui.alert.BankHistoryTags
import com.jay.fxi.ui.alert.BankHistoryWiring
import com.jay.fxi.ui.alert.BankHistoryWiringFactory
import com.jay.fxi.ui.alert.CountingSourceAlertSection
import com.jay.fxi.ui.alert.CountingSourceHistoryWiring
import com.jay.fxi.ui.alert.SourceAlertSectionTags
import com.jay.fxi.ui.alert.LocalNotificationPermissionEnvironment
import com.jay.fxi.ui.alert.NotificationPermissionEnvironment
import com.jay.fxi.ui.alert.SourceAlertEditor
import com.jay.fxi.ui.alert.SourceAlertEditorSheetTags
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.alerts.SourceAlertSaveOutcome
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.graph.GraphScreenHost
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import com.jay.fxi.ui.premium.graph.GraphV2Surface
import com.jay.fxi.ui.premium.graph.GraphV2UiTags
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import com.jay.fxi.ui.viewmodel.NewsViewModel
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * S8-4a (`R4c/S8/s8_4a_contract.agreed.r1.md` §3 androidTest) on the emulator: a counting [CountingSourceAlertSection] through
 * PremiumTopicRoute's new last parameter, under the real route over a real PremiumTopicConsumer, so the real route and the real
 * SourceAlertSection render together. The section is one, in the tether tab's slot only; the bank tabs, NEWS and every overlay
 * show none and leave its flows uncollected; its first appearance asks ensure once and a recomposition none; a first failure's
 * retry reaches authoritative once and the refresh manual once; a click kept from a mount that left — the tab left, the owner
 * replaced, the route disposed, or an overlay opened in the same frame — reaches neither; the badge counts enabled rows and is
 * absent at none. S8-5b: each row has its own open, switch and confirmed delete (T05).
 */
class SourceAlertSectionRouteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val o2 = TopicDisplayOwner(a1, 2L)
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
        val created = AtomicInteger()
        val ensures = AtomicInteger()
        override fun create(context: Context): BankAlertSectionSource {
            created.incrementAndGet()
            return object : BankAlertSectionSource {
                override val settings = MutableStateFlow(BankAlertSettingsState(AlertLoadPhase.Idle, null, null))
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

    /** S8-5b: the helper's environment seam on a device that shows notifications; each confirmed run is counted. */
    private val helperRuns = AtomicInteger()
    private val permission = NotificationPermissionEnvironment(
        admitted = { true },
        liveIdentity = { a1 },
        premium = MutableStateFlow(OwnedPremiumAccess(a1.uid, a1.authGeneration, PremiumAccessState.PremiumConfirmed)),
        displayed = MutableStateFlow(NotificationCapability.GRANTED),
        onRequestResult = { false },
        onAlreadyGranted = {
            helperRuns.incrementAndGet()
            duringGrant?.let { duringGrant = null; it() }
            true
        }
    )
    /** Null is the production default (inert in this build); the S8-5b cases provide [permission]. */
    private var permissionEnv: NotificationPermissionEnvironment? = null
    /** Run once inside the helper's grant confirmation, after its pre-check and before its continuation. */
    private var duringGrant: (() -> Unit)? = null

    /** Every editor the tether holder opens while [block] runs on Main, seen as the state write itself (no frame needed). */
    private fun editorsOpenedDuring(block: () -> Unit): Int {
        var opened = 0
        val handle = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver { written ->
            if ((written as? androidx.compose.runtime.State<*>)?.value is com.jay.fxi.ui.alert.SourceAlertOpenEditor) opened++
        }
        try {
            block()
        } finally {
            handle.dispose()
        }
        return opened
    }

    private fun show(newsItems: List<NewsItem> = emptyList()) {
        val vm = NewsViewModel(NoNews(newsItems))
        rule.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides permissionEnv) {
                if (mounted) {
                    PremiumTopicRoute(consumer = consumer, identity = a1, isActive = active, newsViewModel = vm,
                        userInfo = UserInfo("A", null, null, null, AuthProvider.entries.first()), onSignOut = {},
                        settingsContent = { _, _, _ -> settingsShown += 1 }, graphHost = host, bankHistoryWiring = history,
                        bankAlertSectionSourceFactory = bank, sourceAlertSectionSourceFactory = sources,
                        sourceHistoryWiring = sourceHistory)
                }
            }
        }
        rule.waitForIdle()
    }

    // ---- fixtures and reads ----------------------------------------------------------------------------------------------

    private fun row(id: Int, exchange: String = "upbit", enabled: Boolean = true) =
        SourceAlertSetting(id, "A", exchange, "usdt-krw", AlertCondition.ABOVE, 1400.0 + id, enabled, false, null,
            Instant.fromEpochMilliseconds(t.toEpochMilliseconds() + id * 60_000L), t, null)
    private fun ready(vararg rows: SourceAlertSetting) = SourceAlertSettingsState(AlertLoadPhase.Ready, rows.toList(), a1)
    private val firstFailure = SourceAlertSettingsState(
        AlertLoadPhase.TerminalError(AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null)), null, null)

    private fun publish(settings: SourceAlertSettingsState) {
        rule.runOnUiThread { sources.settings.value = settings }
        rule.waitForIdle()
    }
    private fun select(tab: FreeTab) { focus.value = OwnedTopicFocus(a1, tab); rule.waitForIdle() }
    /** S8-5b: [target] becomes the live account, the tether focus and the display's owner, and the presenter accepts it. */
    private fun acceptOwner(target: TopicDisplayOwner) {
        rule.runOnUiThread {
            liveAccount = target.identity
            focus.value = OwnedTopicFocus(liveAccount, FreeTab.TETHER)
            display.value = display.value.copy(owner = target)
            assertEquals("premise: $target is the accepted owner", target, consumer.currentState().ui.owner)
        }
        rule.waitForIdle()
    }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun tap(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick(); rule.waitForIdle() }
    /** S8-5b: a dialog's or the sheet's node has no scrolling parent, so it is pressed by its click action. */
    private fun press(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun tags(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun rawTags(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun texts(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size
    private fun inSlot(tag: String) =
        rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(PremiumTopicTags.BELOW_RATES))).fetchSemanticsNodes().size
    private fun demands() = listOf(sources.ensures.get(), sources.authoritatives.get(), sources.manuals.get())
    /** The click action of the node now drawn under [tag], kept to be run later on Main. */
    private fun keep(tag: String): () -> Boolean {
        val action = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action
        assertNotNull("premise: $tag has a click action", action)
        return action!!
    }
    private fun run(actions: List<() -> Boolean>) {
        rule.runOnUiThread { actions.forEach { it() } }
        rule.waitForIdle()
    }

    private fun await(label: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(5_000) { condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("timed out: $label", e)
        }
    }
    /** One tether section in the one slot, nothing of the bank's, both flows collected. */
    private fun assertShown(label: String) {
        await("$label: the section is drawn in the slot") { inSlot(SourceAlertSectionTags.ROOT) == 1 }
        await("$label: and collects both flows") { sources.subs().let { it.first > 0 && it.second > 0 } }
        assertEquals("$label: one section", 1, tags(SourceAlertSectionTags.ROOT))
        assertEquals("$label: one slot", 1, tags(PremiumTopicTags.BELOW_RATES))
        assertEquals("$label: no bank section", 0, tags(BankAlertSectionTags.ROOT))
        assertEquals("$label: no history entry", 0, tags(BankHistoryTags.ENTRY))
    }
    /** The section left the composition: none drawn, neither flow collected, no demand beyond [ensures] and [calls] each. */
    private fun assertGone(label: String, ensures: Int, calls: Int = 0) {
        await("$label: the collection ended") { sources.subs() == (0 to 0) }
        assertEquals("$label: no section", 0, tags(SourceAlertSectionTags.ROOT))
        assertEquals("$label: no ensure while gone, nothing else asked", listOf(ensures, calls, calls), demands())
    }
    /** Shown again after [label]: a new appearance asks ensure once more and nothing else. */
    private fun assertBack(label: String, ensures: Int, calls: Int = 0) {
        assertShown(label)
        await("$label: the new appearance asked ensure") { sources.ensures.get() == ensures }
        assertEquals("$label: one more ensure and nothing else", listOf(ensures, calls, calls), demands())
    }

    // ---- placement -----------------------------------------------------------------------------------------------------

    /**
     * S8_4A_T01: the tether tab draws one section in its slot and nothing of the bank's; USD, JPY and EUR draw the bank section
     * and no tether section; NEWS draws no slot; settings, the rate fullscreen and a news detail left open over the tether tab
     * each leave no section and no collection. Every return to the tether tab is a new appearance that asks ensure once.
     */
    @Test fun s84a_t01_oneSectionOnTheTetherTabOnly_andNoneOnTheBankTabsNewsOrUnderAnOverlay() {
        sources.settings.value = ready(row(1))
        show(listOf(NewsItem("n1", "fixture news", "https://example.invalid/n1", "fixture", "external_link", t)))
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.TETHER)).assertIsSelected()
        assertShown("tether")
        await("tether: the first appearance asked ensure") { sources.ensures.get() == 1 }
        assertEquals("tether: the bank factory was never asked", 0 to 0, bank.created.get() to bank.ensures.get())
        assertEquals("tether: its row is drawn", 1, tags(SourceAlertSectionTags.ROW))
        var ensures = 1
        for (tab in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR)) {
            select(tab)
            rule.onNodeWithTag(PremiumTopicTags.tab(tab)).assertIsSelected()
            await("$tab: the bank section is drawn") { tags(BankAlertSectionTags.ROOT) == 1 }
            assertGone("$tab", ensures)
            select(FreeTab.TETHER)
            ensures += 1
            assertBack("back from $tab", ensures)
        }
        select(FreeTab.NEWS)
        await("premise: the news is drawn") { texts("fixture news") > 0 }
        assertGone("NEWS", ensures)
        assertEquals("NEWS: no slot", 0, tags(PremiumTopicTags.BELOW_RATES))
        select(FreeTab.TETHER)
        ensures += 1
        assertBack("back from NEWS", ensures)

        for (layer in listOf("settings", "rate fullscreen")) {
            rule.onNodeWithTag(if (layer == "settings") PremiumTopicTags.SETTINGS else PremiumTopicTags.FULLSCREEN).performClick()
            rule.waitForIdle()
            assertTrue("premise($layer): the layer shows", if (layer == "settings") settingsShown > 0 else
                rule.onAllNodesWithTag(PremiumTopicTags.FULLSCREEN_LAYER).fetchSemanticsNodes().isNotEmpty())
            assertGone("under $layer", ensures)
            assertEquals("under $layer: no slot", 0, tags(PremiumTopicTags.BELOW_RATES))
            if (layer == "settings") back() else rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick()
            rule.waitForIdle()
            ensures += 1
            assertBack("left $layer", ensures)
        }
        select(FreeTab.NEWS)
        rule.onNodeWithText("fixture news").performClick()
        rule.waitForIdle()
        select(FreeTab.TETHER)
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.TETHER)).assertIsSelected()
        assertEquals("premise: the tether rates are drawn under the detail", 1, tags(PremiumTopicTags.RATES))
        assertGone("under the news detail", ensures)
        assertEquals("under the news detail: no slot", 0, tags(PremiumTopicTags.BELOW_RATES))
        back()
        ensures += 1
        assertBack("left the news detail", ensures)
        assertEquals("the section made no history wiring", 0, historyMade.get())
    }

    /**
     * S8_4A_T02: the first appearance asks ensure once; a route recomposition (a connection publication with its banner), the
     * fake's flow changes, collapsing and expanding, and the process foreground ask none and make no second source.
     */
    @Test fun s84a_t02_theFirstAppearanceEnsuresOnce_andARecompositionCollapseOrForegroundAddsNone() {
        show()
        assertShown("first appearance")
        await("the first appearance asked ensure") { sources.ensures.get() == 1 }
        val made = sources.created.get()
        assertTrue("premise: the factory made the source", made > 0)

        rule.runOnUiThread { display.value = display.value.copy(connection = TopicConnectionDisplay.OFFLINE) }
        rule.waitForIdle()
        await("premise: the route recomposed with the offline banner") { texts("오프라인 모드") == 1 }
        rule.runOnUiThread { display.value = display.value.copy(connection = TopicConnectionDisplay.OPEN) }
        rule.waitForIdle()
        await("premise: the route recomposed without it") { texts("오프라인 모드") == 0 }
        assertShown("after the recomposition")

        publish(ready(row(1)))
        await("premise: the published row is drawn") { tags(SourceAlertSectionTags.ROW) == 1 }
        publish(firstFailure)
        await("premise: the published failure is drawn") { tags(SourceAlertSectionTags.RETRY) == 1 }
        rule.runOnUiThread { sources.manualRefresh.value = ManualRefreshState(requested = true, sending = true) }
        rule.waitForIdle()
        rule.runOnUiThread { sources.manualRefresh.value = ManualRefreshState() }
        rule.waitForIdle()

        publish(ready(row(1)))
        tap(SourceAlertSectionTags.TOGGLE_EXPANDED)
        await("premise: collapsed") { tags(SourceAlertSectionTags.ROW) == 0 }
        tap(SourceAlertSectionTags.TOGGLE_EXPANDED)
        await("premise: expanded") { tags(SourceAlertSectionTags.ROW) == 1 }

        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertShown("resumed")
        assertEquals("no ensure beyond the first appearance, nothing else asked", listOf(1, 0, 0), demands())
        assertEquals("no second source was made", made, sources.created.get())
    }

    // ---- delegation and stale callbacks --------------------------------------------------------------------------------

    /**
     * S8_4A_T03: a first failure (no snapshot identity) draws its fixed copy and a retry; a retry tap reaches authoritative once,
     * a refresh tap manual once, and neither asks ensure or the other.
     */
    @Test fun s84a_t03_aFirstFailuresRetryReachesAuthoritativeOnce_andRefreshManualOnce() {
        sources.settings.value = firstFailure
        show()
        assertShown("premise")
        await("premise: the appearance asked ensure") { sources.ensures.get() == 1 }
        await("the first failure draws its fixed copy") { texts("네트워크 연결을 확인해 주세요.") == 1 }
        tap(SourceAlertSectionTags.RETRY)
        await("the retry reached authoritative") { sources.authoritatives.get() == 1 }
        assertEquals("one retry: one authoritative", listOf(1, 1, 0), demands())
        tap(SourceAlertSectionTags.REFRESH)
        await("the refresh reached manual") { sources.manuals.get() == 1 }
        assertEquals("one refresh: one manual", listOf(1, 1, 1), demands())
    }

    /**
     * S8_4A_T04: clicks kept from a mount reach neither authoritative nor manual once that mount left — the tab left, the rate
     * fullscreen opened and drawn, the route disposed, the owner replaced — neither while it is gone nor once a new mount is drawn
     * and shown, and not when the route's settings opened in the same frame, before the section left. (An overlay inside the
     * screen is guaranteed once it is drawn, by the slot leaving the composition: contract r2 §2.) The click of the mount drawn now still reaches each once, so a guard
     * reduced to nothing, or to the screen check alone, fails the count.
     */
    @Test fun s84a_t04_aKeptClickOfAMountThatLeftReachesNothing() {
        sources.settings.value = firstFailure
        show()
        assertShown("premise")
        await("premise: the appearance asked ensure") { sources.ensures.get() == 1 }
        var ensures = 1
        var calls = 0
        fun kept(label: String, clicks: List<() -> Boolean>) {
            run(clicks)
            assertEquals("$label: the kept clicks reached nothing", listOf(ensures, calls, calls), demands())
        }
        fun live(label: String) {
            await("$label: the retry is drawn") { tags(SourceAlertSectionTags.RETRY) == 1 }
            run(listOf(keep(SourceAlertSectionTags.RETRY), keep(SourceAlertSectionTags.REFRESH)))
            calls += 1
            assertEquals("$label: the drawn mount's clicks reach each once", listOf(ensures, calls, calls), demands())
        }
        live("premise")
        for ((label, away, home) in listOf<Triple<String, () -> Unit, (() -> Unit)?>>(
                Triple("tab left", { select(FreeTab.USD) }, { select(FreeTab.TETHER) }),
                Triple("rate fullscreen", { rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN).performClick(); rule.waitForIdle() },
                    { rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick(); rule.waitForIdle() }),
                Triple("disposed", { mounted = false; rule.waitForIdle() }, { mounted = true; rule.waitForIdle() }),
                Triple("owner replaced", { rule.runOnUiThread { display.value = display.value.copy(owner = o2) }; rule.waitForIdle() }, null))) {
            val clicks = listOf(keep(SourceAlertSectionTags.RETRY), keep(SourceAlertSectionTags.REFRESH))
            away()
            if (home != null) {
                assertGone(label, ensures, calls)
                kept("$label, while gone", clicks)
                home()
            }
            ensures += 1
            assertBack(if (home != null) "$label, then back" else "$label: the new owner's section", ensures, calls)
            kept("$label, beside the new mount", clicks)
            live(label)
        }

        // An overlay opened in the same frame: the mount is still composed, but its screen no longer shows it.
        val clicks = listOf(keep(SourceAlertSectionTags.RETRY), keep(SourceAlertSectionTags.REFRESH))
        val openSettings = keep(PremiumTopicTags.SETTINGS)
        run(listOf(openSettings) + clicks)
        assertTrue("premise: settings opened", settingsShown > 0)
        assertEquals("settings opened in the same frame: the kept clicks reached nothing", listOf(ensures, calls, calls), demands())
        assertGone("under settings", ensures, calls)
    }

    /**
     * S8_4A_T06: on the tether tab itself, the real graph host's fullscreen, the route's inactivity and the loss of its owner
     * each leave no section, no slot and no collection, make no source and ask nothing, and a click kept from before reaches
     * nothing; each return is a new appearance that asks ensure once.
     */
    @Test fun s84a_t06_theGraphFullscreenInactivityAndOwnerLossOnTheTetherTabLeaveNoSection() {
        host = newHost()
        sources.settings.value = firstFailure
        show()
        assertShown("premise")
        await("premise: the appearance asked ensure") { sources.ensures.get() == 1 }
        await("premise: the retry is drawn") { tags(SourceAlertSectionTags.RETRY) == 1 }
        var ensures = 1
        val tether = { onMain { built.toList().single { it.field("tab") == "tether" && it.field("closed") == false } } }
        await("premise: the tether holder has an inline token") {
            runCatching { onMain { tether().currentState().inlineToken } != null }.getOrDefault(false)
        }
        for ((label, away, back) in listOf<Triple<String, () -> Unit, () -> Unit>>(
                Triple("graph fullscreen", {
                    val token = onMain { checkNotNull(tether().currentState().inlineToken) { "premise: an inline token" } }
                    rule.runOnUiThread { tether().enterFullscreen(token) }
                    rule.waitForIdle()
                    await("premise: the holder's fullscreen is drawn") { exists(GraphV2UiTags.root(GraphV2Surface.FULLSCREEN)) }
                }, {
                    val full = onMain { checkNotNull(tether().currentState().fullscreenToken) { "premise: a fullscreen token" } }
                    rule.runOnUiThread { tether().exitFullscreen(full) }
                    rule.waitForIdle()
                }),
                Triple("inactive", { active = false; rule.waitForIdle() }, { active = true; rule.waitForIdle() }),
                Triple("owner lost", { rule.runOnUiThread { display.value = display.value.copy(owner = null) }; rule.waitForIdle() },
                    { rule.runOnUiThread { display.value = display.value.copy(owner = o1) }; rule.waitForIdle() }))) {
            assertShown("premise($label)")
            val clicks = listOf(keep(SourceAlertSectionTags.RETRY), keep(SourceAlertSectionTags.REFRESH))
            val made = sources.created.get()
            away()
            assertGone(label, ensures)
            assertEquals("$label: no slot", 0, tags(PremiumTopicTags.BELOW_RATES))
            assertEquals("$label: no source made while gone", made, sources.created.get())
            run(clicks)
            assertEquals("$label: the kept clicks reached nothing", listOf(ensures, 0, 0), demands())
            back()
            ensures += 1
            await("$label, then back: the retry is drawn") { tags(SourceAlertSectionTags.RETRY) == 1 }
            assertBack("$label, then back", ensures)
        }
    }

    // ---- the real graph host (harness as in BankAlertSectionRouteTest row 5h) ------------------------------------------

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
    private fun <T> onMain(read: () -> T): T {
        var result: Result<T>? = null
        rule.runOnUiThread { result = runCatching(read) }
        return checkNotNull(result).getOrThrow()
    }

    // ---- badge and read-only semantics ---------------------------------------------------------------------------------

    /**
     * S8_4A_T05, its write half replaced by S8-5b (`R4c/S8/s8_5b_contract.agreed.r1.md` §3 D4): the header badge is absent with
     * no enabled row and shows the count of enabled rows otherwise; each row now has its own open, a switch at the row's state and
     * a delete; a switch sends its target value for its id, a delete asks once, a cancel sends nothing and a
     * confirm sends the one id; drawing asks one ensure and nothing else, and no add or edit reaches the owner.
     */
    @Test fun s84a_t05_theBadgeCountsEnabledRows_andEachRowHasItsOwnControls() {
        sources.selection.value = SourceSelectionSnapshot.Ready(a1, 1, null)
        sources.settings.value = ready(row(1, enabled = false), row(2, "bithumb", enabled = false))
        show()
        assertShown("premise")
        await("premise: two rows are drawn") { tags(SourceAlertSectionTags.ROW) == 2 }
        assertEquals("no enabled row: no badge", 0, rawTags(SourceAlertSectionTags.ACTIVE_COUNT))
        publish(ready(row(1), row(2, "bithumb", enabled = false), row(3, "coinone")))
        await("premise: three rows are drawn") { tags(SourceAlertSectionTags.ROW) == 3 }
        await("two enabled rows: the badge") { rawTags(SourceAlertSectionTags.ACTIVE_COUNT) == 1 }
        for ((exchange, state) in listOf("upbit" to "켜짐", "bithumb" to "꺼짐", "coinone" to "켜짐")) {
            val name = checkNotNull(Exchange.fromCode(exchange)).displayName
            val other = if (state == "켜짐") "꺼짐" else "켜짐"
            assertEquals("$exchange's row reads $state", 1, rule.onAllNodes(hasTestTag(SourceAlertSectionTags.ROW) and
                hasAnyDescendant(hasText(name)) and hasAnyDescendant(hasText(state)), useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals("$exchange's row does not read $other", 0, rule.onAllNodes(hasTestTag(SourceAlertSectionTags.ROW) and
                hasAnyDescendant(hasText(name)) and hasAnyDescendant(hasText(other)), useUnmergedTree = true).fetchSemanticsNodes().size)
        }
        assertEquals("the badge reads 2", 1, rule.onAllNodes(hasText("2") and hasAnyAncestor(hasTestTag(SourceAlertSectionTags.ACTIVE_COUNT)),
            useUnmergedTree = true).fetchSemanticsNodes().size)

        for ((id, enabled) in listOf(1 to true, 2 to false, 3 to true)) {
            assertEquals("row $id opens", 1, rule.onAllNodes(hasTestTag(SourceAlertSectionTags.rowOpen(id)) and hasClickAction()).fetchSemanticsNodes().size)
            assertEquals("row $id's switch reads $enabled", 1,
                rule.onAllNodes(hasTestTag(SourceAlertSectionTags.toggle(id)) and isToggleable() and (if (enabled) isOn() else isOff()))
                    .fetchSemanticsNodes().size)
            assertEquals("row $id deletes", 1, rule.onAllNodes(hasTestTag(SourceAlertSectionTags.delete(id)) and hasClickAction()).fetchSemanticsNodes().size)
        }
        assertEquals("one add", 1, tags(SourceAlertSectionTags.ADD_BUTTON))

        tap(SourceAlertSectionTags.toggle(2))
        tap(SourceAlertSectionTags.toggle(1))
        assertEquals("each switch sends its target for its id", listOf(2 to true, 1 to false), sources.toggles.toList())
        tap(SourceAlertSectionTags.delete(3))
        assertEquals("the delete asks", 1, tags(SourceAlertSectionTags.DELETE_DIALOG))
        press(SourceAlertSectionTags.DELETE_CANCEL)
        assertEquals("the cancel closes", 0, tags(SourceAlertSectionTags.DELETE_DIALOG))
        assertEquals("and sends nothing", emptyList<Int>(), sources.deletes.toList())
        tap(SourceAlertSectionTags.delete(3))
        press(SourceAlertSectionTags.DELETE_CONFIRM)
        assertEquals("the confirm sends the one id", listOf(3), sources.deletes.toList())
        assertEquals("no add or edit reached the owner", 0, sources.adds.size + sources.edits.size)
        assertEquals("drawing asked one ensure and nothing else", listOf(1, 0, 0), demands())
    }

    // ---- S8-5b D3: a kept write in the same Main runnable as an overlay's entry ----------------------------------------

    private fun sheetOpen() = rawTags(SourceAlertEditorSheetTags.SAVE) == 1
    private fun keepRaw(tag: String): () -> Boolean {
        val action = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action
        assertNotNull("premise: $tag has a click action", action)
        return action!!
    }
    /** helper runs, adds, edits, toggles, deletes, remembered adds. */
    private fun writes() = listOf(helperRuns.get(), sources.adds.size, sources.edits.size, sources.toggles.size, sources.deletes.size,
        sources.accepted.size)

    /**
     * S8_5B_D3a (`s8_5b_contract.agreed` §3 D3): a kept add, edit, save, switch or delete confirmation, run in the
     * same Main runnable right after the rate fullscreen's click or the graph holder's fullscreen entry, before any frame, runs
     * no helper and reaches no write and no memory. After the overlay closes the kept action still reaches nothing (its mount
     * left) and a new click reaches its target once. An editor opened inside that runnable is counted as the holder's state write
     * itself ([editorsOpenedDuring]), before any frame can remove it.
     */
    @Test fun s85b_d3a_aKeptWriteInTheRunnableOfAnOverlaysEntryReachesNothing() {
        host = newHost()
        permissionEnv = permission
        sources.selection.value = SourceSelectionSnapshot.Ready(a1, 1, null)
        sources.settings.value = ready(row(1))
        show()
        assertShown("premise")
        val tether = { onMain { built.toList().single { it.field("tab") == "tether" && it.field("closed") == false } } }
        await("premise: the tether holder has an inline token") {
            runCatching { onMain { tether().currentState().inlineToken } != null }.getOrDefault(false)
        }
        val entries = listOf<Triple<String, () -> (() -> Unit), () -> Unit>>(
            Triple("rate fullscreen", {
                val click = keep(PremiumTopicTags.FULLSCREEN)
                ({ click() })
            }, { rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick(); rule.waitForIdle() }),
            Triple("graph fullscreen", {
                val token = onMain { checkNotNull(tether().currentState().inlineToken) { "premise: an inline token" } }
                ({ tether().enterFullscreen(token) })
            }, {
                val full = onMain { checkNotNull(tether().currentState().fullscreenToken) { "premise: a fullscreen token" } }
                rule.runOnUiThread { tether().exitFullscreen(full) }
                rule.waitForIdle()
            }))
        // Each write kept from a live session: what must be open before it is kept, and its own target.
        val actions = listOf<Triple<String, () -> Unit, String>>(
            Triple("add", { }, SourceAlertSectionTags.ADD_BUTTON),
            Triple("edit", { }, SourceAlertSectionTags.rowOpen(1)),
            Triple("save", { tap(SourceAlertSectionTags.ADD_BUTTON) }, SourceAlertEditorSheetTags.SAVE),
            Triple("switch", { }, SourceAlertSectionTags.toggle(1)),
            Triple("delete", { tap(SourceAlertSectionTags.delete(1)) }, SourceAlertSectionTags.DELETE_CONFIRM))
        for ((entry, enter, close) in entries) for ((name, open, tag) in actions) {
            val label = "$entry / $name"
            await("$label: premise: the section") { tags(SourceAlertSectionTags.ROOT) == 1 }
            open()
            val kept = keepRaw(tag)
            val enterNow = enter()
            val before = writes()
            var opened = -1
            rule.runOnUiThread { opened = editorsOpenedDuring { enterNow(); kept() } }
            rule.waitForIdle()
            assertEquals("$label: no editor opened in the runnable", 0, opened)
            assertEquals("$label: nothing reached", before, writes())
            assertEquals("$label: no section under the overlay", 0, tags(SourceAlertSectionTags.ROOT))
            close()
            await("$label: back") { tags(SourceAlertSectionTags.ROOT) == 1 }
            run(listOf(kept))
            assertEquals("$label: the kept action of the mount that left still reaches nothing", before, writes())
            assertEquals("$label: and opens no editor", false, sheetOpen())
            // A new click of the new mount reaches its target once.
            open()
            run(listOf(keepRaw(tag)))
            val after = writes()
            when (name) {
                "add" -> {
                    assertEquals("$label: a new add runs the helper once", before[0] + 1, after[0])
                    assertTrue("$label: and opens", sheetOpen())
                    press(SourceAlertEditorSheetTags.CANCEL)
                }
                "edit" -> {
                    assertTrue("$label: a new edit opens", sheetOpen())
                    press(SourceAlertEditorSheetTags.CANCEL)
                }
                "save" -> {
                    assertEquals("$label: a new save sends one add", before[1] + 1, after[1])
                    rule.runOnUiThread { sources.handles.last().finish(SourceAlertSaveOutcome.NotSent(com.jay.fxi.data.alerts.NotSentReason.RESET)) }
                    await("$label: the reset closes it") { !sheetOpen() }
                }
                "switch" -> assertEquals("$label: a new switch sends its target once", before[3] + 1, after[3])
                "delete" -> assertEquals("$label: a new confirmation deletes once", before[4] + 1, after[4])
            }
        }
        assertEquals("nothing remembered", 0, sources.accepted.size)
    }

    /**
     * S8_5B_D3d (`s8_5b_contract.agreed` §3 D3, the grant half at the route): an add-open and a save each pass the click's own
     * live check and start the real helper; while the helper confirms the grant, the rate fullscreen's click or the graph's
     * accepted fullscreen entry happens; the continuation then finds the section not shown and opens no editor, runs no write
     * and remembers nothing. After the overlay closes the new session's add opens and its save is taken once.
     */
    @Test fun s85b_d3d_anOverlayOpenedWhileTheGrantIsConfirmedStopsTheContinuation() {
        host = newHost()
        permissionEnv = permission
        sources.selection.value = SourceSelectionSnapshot.Ready(a1, 1, null)
        sources.settings.value = ready(row(1))
        show()
        assertShown("premise")
        val tether = { onMain { built.toList().single { it.field("tab") == "tether" && it.field("closed") == false } } }
        await("premise: the tether holder has an inline token") {
            runCatching { onMain { tether().currentState().inlineToken } != null }.getOrDefault(false)
        }
        val entries = listOf<Triple<String, () -> (() -> Unit), () -> Unit>>(
            Triple("rate fullscreen", {
                val click = keep(PremiumTopicTags.FULLSCREEN)
                ({ click() })
            }, { rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick(); rule.waitForIdle() }),
            Triple("graph fullscreen", {
                val token = onMain { checkNotNull(tether().currentState().inlineToken) { "premise: an inline token" } }
                ({ tether().enterFullscreen(token) })
            }, {
                val full = onMain { checkNotNull(tether().currentState().fullscreenToken) { "premise: a fullscreen token" } }
                rule.runOnUiThread { tether().exitFullscreen(full) }
                rule.waitForIdle()
            }))
        for ((entry, enter, close) in entries) for (save in listOf(false, true)) {
            val label = "$entry / ${if (save) "save" else "add-open"}"
            await("$label: premise: the section") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
            if (save) tap(SourceAlertSectionTags.ADD_BUTTON)
            val click = keepRaw(if (save) SourceAlertEditorSheetTags.SAVE else SourceAlertSectionTags.ADD_BUTTON)
            val enterNow = enter()
            val before = writes()
            var opened = -1
            rule.runOnUiThread {
                duringGrant = enterNow
                opened = editorsOpenedDuring { click() }
                check(duringGrant == null) { "$label: the helper confirmed the grant" }
            }
            rule.waitForIdle()
            assertEquals("$label: the live click started the helper once", before[0] + 1, writes()[0])
            assertEquals("$label: the continuation opened no editor", 0, opened)
            assertEquals("$label: and took no write and no memory", before.drop(1), writes().drop(1))
            assertEquals("$label: the overlay replaced the section", 0, tags(SourceAlertSectionTags.ROOT))
            close()
            await("$label: back") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
            tap(SourceAlertSectionTags.ADD_BUTTON)
            assertTrue("$label: the new session's add opens", sheetOpen())
            if (save) {
                run(listOf(keepRaw(SourceAlertEditorSheetTags.SAVE)))
                assertEquals("$label: the new session's save is taken once", before[1] + 1, writes()[1])
                rule.runOnUiThread { sources.handles.last().finish(SourceAlertSaveOutcome.NotSent(com.jay.fxi.data.alerts.NotSentReason.RESET)) }
                await("$label: settled") { !sheetOpen() }
            } else {
                press(SourceAlertEditorSheetTags.CANCEL)
            }
        }
        assertEquals("nothing remembered", 0, sources.accepted.size)
    }

    /**
     * S8_5B_D3e (`s8_5b_contract.agreed` §3 D3, one stop at a time): in each runnable below only one of the screen's three
     * overlay stops can stop a kept add. The rate entry's own end of the session: the rate fullscreen's click, then a close kept
     * from an earlier rate fullscreen, then the kept add, which finds no overlay. The read of the rate fullscreen at the click: a
     * rate fullscreen click kept from the USD page, which ends no tether session, then the kept add. The graph entry's own end
     * of the session: the inline graph's expand click, then the holder's exit, then the kept add. Each runs no helper and opens
     * no editor; after a frame the kept add still reaches nothing and a new add opens once. The rate and graph entries also run
     * with no click between them and the frame: no check then sees the overlay, so only the slot's key can end the session, and
     * after the frame the add kept before the entry still reaches nothing while a new add opens once.
     */
    @Test fun s85b_d3e_eachOverlayStopAloneStopsAKeptAdd() {
        host = newHost()
        permissionEnv = permission
        sources.selection.value = SourceSelectionSnapshot.Ready(a1, 1, null)
        sources.settings.value = ready(row(1))
        show()
        assertShown("premise")
        val tether = { onMain { built.toList().single { it.field("tab") == "tether" && it.field("closed") == false } } }
        await("premise: the tether holder has an inline token") {
            runCatching { onMain { tether().currentState().inlineToken } != null }.getOrDefault(false)
        }
        // A close kept from an earlier rate fullscreen.
        run(listOf(keep(PremiumTopicTags.FULLSCREEN)))
        await("premise: the rate fullscreen is drawn") { tags(PremiumTopicTags.FULLSCREEN_LAYER) == 1 }
        val staleClose = keep(PremiumTopicTags.FULLSCREEN_CLOSE)
        rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick()
        rule.waitForIdle()
        // A rate fullscreen click kept from the USD page.
        await("premise: back on tether") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
        select(FreeTab.USD)
        await("premise: the USD page's fullscreen") { tags(PremiumTopicTags.FULLSCREEN) == 1 && tags(SourceAlertSectionTags.ROOT) == 0 }
        val usdFullscreen = keep(PremiumTopicTags.FULLSCREEN)
        select(FreeTab.TETHER)

        fun case(label: String, prepare: () -> (() -> Unit), overlayStays: Boolean, clickInRunnable: Boolean = true) {
            await("$label: premise: the section") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
            val stop = prepare()
            val kept = keepRaw(SourceAlertSectionTags.ADD_BUTTON)
            val before = writes()
            var opened = -1
            rule.runOnUiThread { opened = editorsOpenedDuring { stop(); if (clickInRunnable) kept() } }
            rule.waitForIdle()
            assertEquals("$label: no editor opened in the runnable", 0, opened)
            assertEquals("$label: nothing reached", before, writes())
            if (overlayStays) {
                assertEquals("$label: the rate fullscreen is drawn over the tether tab", 1, tags(PremiumTopicTags.FULLSCREEN_LAYER))
                assertEquals("$label: and the section left", 0, tags(SourceAlertSectionTags.ROOT))
                rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick()
                rule.waitForIdle()
            } else {
                assertEquals("$label: no rate fullscreen is left", 0, tags(PremiumTopicTags.FULLSCREEN_LAYER))
                assertEquals("$label: no graph fullscreen is left", null, onMain { tether().currentState().fullscreenToken })
            }
            await("$label: back") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
            run(listOf(kept))
            assertEquals("$label: the kept add still reaches nothing", before, writes())
            assertEquals("$label: and opens no editor", false, sheetOpen())
            run(listOf(keepRaw(SourceAlertSectionTags.ADD_BUTTON)))
            assertEquals("$label: a new add runs the helper once", before[0] + 1, writes()[0])
            assertTrue("$label: and opens", sheetOpen())
            press(SourceAlertEditorSheetTags.CANCEL)
        }
        case("the rate entry, then a close kept from an earlier rate fullscreen", {
            val enter = keep(PremiumTopicTags.FULLSCREEN)
            ({ enter(); staleClose() })
        }, overlayStays = false)
        case("the rate entry, then a close kept from an earlier rate fullscreen, with no click between", {
            val enter = keep(PremiumTopicTags.FULLSCREEN)
            ({ enter(); staleClose() })
        }, overlayStays = false, clickInRunnable = false)
        case("a rate fullscreen click kept from the USD page", { ({ usdFullscreen() }) }, overlayStays = true)
        case("the graph's expand click, then the holder's exit", {
            val expand = keepRaw(GraphV2UiTags.expand())
            ({
                expand()
                val full = checkNotNull(tether().currentState().fullscreenToken) { "premise: the expand click's entry was accepted" }
                tether().exitFullscreen(full)
            })
        }, overlayStays = false)
        case("the graph's expand click, then the holder's exit, with no click between", {
            val expand = keepRaw(GraphV2UiTags.expand())
            ({
                expand()
                val full = checkNotNull(tether().currentState().fullscreenToken) { "premise: the expand click's entry was accepted" }
                tether().exitFullscreen(full)
            })
        }, overlayStays = false, clickInRunnable = false)
        assertEquals("nothing remembered", 0, sources.accepted.size)
    }

    /**
     * S8_5B_D3b: a kept write of a session that ended — the tab left, the owner replaced, another account accepted (A→B→A) or
     * only displayed (an identity mismatch), a news detail
     * opened over the tether tab, settings opened in the same frame, the route made inactive or disposed — reaches nothing while away or after the return, and the new session's own click reaches
     * its target once. An editor replaced while its request was pending ignores that request's late Removed.
     */
    @Test fun s85b_d3b_aKeptWriteOfASessionThatEndedReachesNothing_andAReplacedEditorIgnoresItsOldAnswer() {
        permissionEnv = permission
        sources.selection.value = SourceSelectionSnapshot.Ready(a1, 1, null)
        sources.settings.value = ready(row(1), row(2, "bithumb"))
        show(listOf(NewsItem("n1", "fixture news", "https://example.invalid/n1", "fixture", "external_link", t)))
        assertShown("premise")
        fun kept() = listOf(keepRaw(SourceAlertSectionTags.ADD_BUTTON), keepRaw(SourceAlertSectionTags.toggle(1)))
        // Out and back in one runnable, before any frame: only the screen listener's synchronous end can stop the old clicks.
        for (other in listOf(TopicDisplayOwner(AuthIdentityFence("B", 1L), 1L), TopicDisplayOwner(AuthIdentityFence("A", 2L), 1L), o2)) {
            val old = kept()
            val before = writes()
            rule.runOnUiThread {
                fun publishOwner(target: TopicDisplayOwner) {
                    liveAccount = target.identity
                    focus.value = OwnedTopicFocus(liveAccount, FreeTab.TETHER)
                    display.value = display.value.copy(owner = target)
                    assertEquals("premise: $target is the accepted owner", target, consumer.currentState().ui.owner)
                }
                publishOwner(other)
                publishOwner(o1)
                old.forEach { it() }
                assertEquals("$other and back before a frame: the old clicks reached nothing", before, writes())
            }
            rule.waitForIdle()
            await("back with $other gone") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
            run(listOf(keepRaw(SourceAlertSectionTags.toggle(1))))
            assertEquals("$other: the new session's switch once", before[3] + 1, writes()[3])
        }
        val oldTab = kept()
        val tabCounts = writes()
        rule.runOnUiThread {
            for (tab in listOf(FreeTab.USD, FreeTab.TETHER)) {
                focus.value = OwnedTopicFocus(a1, tab)
                assertEquals("premise: $tab is accepted", tab, consumer.currentState().ui.selectedTab)
            }
            oldTab.forEach { it() }
            assertEquals("the tab out and back before a frame: the old clicks reached nothing", tabCounts, writes())
        }
        rule.waitForIdle()
        await("back on tether") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
        run(listOf(keepRaw(SourceAlertSectionTags.toggle(1))))
        assertEquals("the new session's switch once", tabCounts[3] + 1, writes()[3])
        for ((label, away, home) in listOf<Triple<String, () -> Unit, () -> Unit>>(
                Triple("tab left", { select(FreeTab.USD) }, { select(FreeTab.TETHER) }),
                Triple("inactive", { active = false; rule.waitForIdle() }, { active = true; rule.waitForIdle() }),
                Triple("disposed", { mounted = false; rule.waitForIdle() }, { mounted = true; rule.waitForIdle() }),
                Triple("owner replaced", { rule.runOnUiThread { display.value = display.value.copy(owner = o2) }; rule.waitForIdle() },
                    { rule.runOnUiThread { display.value = display.value.copy(owner = o1) }; rule.waitForIdle() }),
                // Another account accepted across frames, then A again: A→B→A.
                Triple("another account accepted", { acceptOwner(TopicDisplayOwner(AuthIdentityFence("B", 1L), 1L)) }, { acceptOwner(o1) }),
                // Another account's display while A is live: the presenter accepts no owner (an identity mismatch), then A again.
                Triple("another account's display while A is live (identity mismatch)", { rule.runOnUiThread { display.value = display.value.copy(owner = TopicDisplayOwner(AuthIdentityFence("B", 1L), 1L)) }; rule.waitForIdle() },
                    { rule.runOnUiThread { display.value = display.value.copy(owner = o1) }; rule.waitForIdle() }),
                Triple("a news detail over the tether tab", {
                    select(FreeTab.NEWS)
                    await("premise: the news is drawn") { texts("fixture news") > 0 }
                    rule.onNodeWithText("fixture news").performClick()
                    rule.waitForIdle()
                    select(FreeTab.TETHER)
                }, { back() }))) {
            await("$label: premise") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
            val clicks = kept()
            val before = writes()
            away()
            run(clicks)
            assertEquals("$label: away, the kept writes reached nothing", before, writes())
            home()
            await("$label: back") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }
            run(clicks)
            assertEquals("$label: back, still nothing", before, writes())
            run(listOf(keepRaw(SourceAlertSectionTags.toggle(1))))
            assertEquals("$label: the new switch once", before[3] + 1, writes()[3])
        }
        val clicks = kept()
        val before = writes()
        run(listOf(keep(PremiumTopicTags.SETTINGS)) + clicks)
        assertTrue("premise: settings opened", settingsShown > 0)
        assertEquals("settings opened in the same frame: nothing reached", before, writes())
        back()
        await("settings closed: back") { tags(SourceAlertSectionTags.ADD_BUTTON) == 1 }

        tap(SourceAlertSectionTags.rowOpen(1))
        rule.onNodeWithTag(SourceAlertEditorSheetTags.THRESHOLD, useUnmergedTree = true).performTextReplacement("1450")
        rule.waitForIdle()
        rule.onNodeWithTag(SourceAlertEditorSheetTags.SAVE, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val old = sources.handles.last()
        press(SourceAlertEditorSheetTags.CANCEL)
        tap(SourceAlertSectionTags.rowOpen(2))
        assertTrue("premise: the second editor", sheetOpen())
        rule.runOnUiThread { old.finish(SourceAlertSaveOutcome.Removed) }
        rule.waitForIdle()
        assertTrue("the replaced editor's Removed leaves the new one open", sheetOpen())
        assertEquals("and shows no notice", 0, rawTags(SourceAlertSectionTags.NOTICE))
        assertEquals("and no error", 0, rawTags(SourceAlertEditorSheetTags.ERROR_DIALOG))
        assertEquals(0, texts(SourceAlertEditor.Copy.REMOVED))
    }
}
