package com.jay.fxi

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
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
import com.jay.fxi.domain.model.AlertSetting
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
import com.jay.fxi.ui.alert.BankAlertSection
import com.jay.fxi.ui.alert.BankAlertSectionSource
import com.jay.fxi.ui.alert.BankAlertSectionSourceFactory
import com.jay.fxi.ui.alert.BankAlertSectionTags
import com.jay.fxi.ui.alert.BankHistoryRead
import com.jay.fxi.ui.alert.BankHistoryTags
import com.jay.fxi.ui.alert.BankHistoryWiring
import com.jay.fxi.ui.alert.BankHistoryWiringFactory
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
import kotlin.time.Duration.Companion.seconds
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
 * Claude-owned S7 E5a1 contract (R4c/S7/s7e5 API r2): API r2 §5 row 5 on a device — a fake [BankAlertSectionSourceFactory],
 * injected through PremiumTopicRoute's new last parameter, under the real PremiumTopicRoute over a real PremiumTopicConsumer, so
 * the real route and the real BankAlertSection render together. The section is one, under the history entry, of the usd, jpy and
 * eur tabs only; its first appearance on a bank tab asks ensure once, recomposition, collapse and the process foreground ask none,
 * every new appearance (another bank tab, a return from a covering layer, the route active again, the owner back, a remount) asks
 * once more; an unsupported tab never asks the factory, ensure or the flows; what the fake's flows publish is what is drawn; the
 * header refresh reaches manual and the error retry authoritative; settings, the news detail, the rate fullscreen and the graph
 * host's own fullscreen, the route's inactivity, the owner's loss and the route's disposal leave no section and no collection; a
 * replaced factory's source is the only one used afterwards. "Collected" is read from the fake flows' own subscription counts:
 * a covering layer clears the page's semantics, so only the subscriptions tell a disposed section from a hidden one.
 * The implementation thread reads but does not edit this file.
 */
class BankAlertSectionRouteTest {
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

    /** The agreed seam, faked: two flows the test publishes into and three counted demands. */
    private class FakeSource : BankAlertSectionSource {
        override val settings: MutableStateFlow<BankAlertSettingsState> =
            MutableStateFlow(BankAlertSettingsState(AlertLoadPhase.Idle, null, null))
        override val manualRefresh: MutableStateFlow<ManualRefreshState> = MutableStateFlow(ManualRefreshState())
        val ensures = AtomicInteger()
        val authoritatives = AtomicInteger()
        val manuals = AtomicInteger()
        override fun ensure() { ensures.incrementAndGet() }
        override fun authoritative() { authoritatives.incrementAndGet() }
        override fun manual() { manuals.incrementAndGet() }
        override fun setEnabled(id: Int, enabled: Boolean) {}
        override fun add(draft: com.jay.fxi.data.alerts.BankAlertDraft): com.jay.fxi.data.alerts.BankAlertSaveRequest = error("S7 E5b3b: not used here")
        override fun edit(id: Int, edit: com.jay.fxi.data.alerts.BankAlertEdit): com.jay.fxi.data.alerts.BankAlertSaveRequest = error("S7 E5b3b: not used here")
        override fun delete(id: Int) {}
    }
    /** Counts its makings; every making hands out the same [source], as the production factory exposes the one singleton. */
    private class FakeFactory(val source: FakeSource) : BankAlertSectionSourceFactory {
        val created = AtomicInteger()
        override fun create(context: Context): BankAlertSectionSource = source.also { created.incrementAndGet() }
    }

    private val display = MutableStateFlow(TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.USD))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consumer = PremiumTopicConsumer(display, focus, { a1 }, MemoryRows(),
        { o, tab -> focus.value = OwnedTopicFocus(o, tab) }, { }, { _, _ -> }, scope)
    private val source = FakeSource()
    private val factory = FakeFactory(source)
    private var sectionFactory by mutableStateOf<BankAlertSectionSourceFactory>(factory)
    private var active by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private var host by mutableStateOf<GraphScreenHost?>(null)
    private var settingsShown = 0
    /** The history entry is never opened in this file; its wiring factory only counts. */
    private val historyMade = AtomicInteger()
    private val history = BankHistoryWiringFactory { _, _ ->
        historyMade.incrementAndGet()
        BankHistoryWiring(object : BankHistoryAccess {
            override fun acquire(owner: TopicDisplayOwner): BankHistoryUse? = null
            override fun admits(use: BankHistoryUse) = false
        }, BankHistoryRead { _, _, _ -> awaitCancellation() }, MutableStateFlow(0L), MutableStateFlow(0L))
    }
    private val hostScopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() {
        scope.cancel()
        hostScopes.forEach { it.cancel() }
    }

    private fun show(newsItems: List<NewsItem> = emptyList()) {
        val vm = NewsViewModel(NoNews(newsItems))
        rule.setContent {
            if (mounted) {
                PremiumTopicRoute(consumer = consumer, identity = a1, isActive = active, newsViewModel = vm,
                    userInfo = UserInfo("A", null, null, null, AuthProvider.entries.first()), onSignOut = {},
                    settingsContent = { _, _, _ -> settingsShown += 1 }, graphHost = host, bankHistoryWiring = history,
                    bankAlertSectionSourceFactory = sectionFactory)
            }
        }
        rule.waitForIdle()
    }

    // ---- fixtures and reads ----------------------------------------------------------------------------------------------

    private val usd = setting(11, "usd-krw")
    private val jpy = setting(12, "jpy-krw")
    private val eur = setting(13, "eur-krw")
    private fun setting(id: Int, currency: String) =
        AlertSetting(id, "A", "kb", currency, AlertCondition.ABOVE, 1400.5, true, false, t, t, null, null)
    private fun ready(vararg rows: AlertSetting) = BankAlertSettingsState(AlertLoadPhase.Ready, rows.toList(), a1)
    private fun phase(phase: AlertLoadPhase, vararg rows: AlertSetting) =
        BankAlertSettingsState(phase, if (rows.isEmpty()) null else rows.toList(), null)

    private fun publish(to: FakeSource, settings: BankAlertSettingsState) {
        rule.runOnUiThread { to.settings.value = settings }
        rule.waitForIdle()
    }
    private fun publish(to: FakeSource, manual: ManualRefreshState) {
        rule.runOnUiThread { to.manualRefresh.value = manual }
        rule.waitForIdle()
    }
    private fun select(tab: FreeTab) { focus.value = OwnedTopicFocus(a1, tab); rule.waitForIdle() }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun tap(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick(); rule.waitForIdle() }

    private fun tags(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun texts(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size
    private fun inSlot(tag: String) =
        rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(PremiumTopicTags.BELOW_RATES))).fetchSemanticsNodes().size
    private fun textUnder(text: String, tag: String) =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().size
    private fun refreshEnabled() =
        rule.onNodeWithTag(BankAlertSectionTags.REFRESH).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
    /** The section's collection of both flows of [of]: (settings subscribers, manualRefresh subscribers). */
    private fun subs(of: FakeSource) = of.settings.subscriptionCount.value to of.manualRefresh.subscriptionCount.value
    private fun collected(of: FakeSource) = subs(of).let { it.first > 0 && it.second > 0 }
    private fun released(of: FakeSource) = subs(of) == (0 to 0)
    private val contentTags = listOf(BankAlertSectionTags.LOADING, BankAlertSectionTags.ERROR, BankAlertSectionTags.EMPTY,
        BankAlertSectionTags.ROWS)

    private fun await(label: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(5_000) { condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("timed out: $label", e)
        }
    }
    /** Premise: one section is drawn and collects [of]. */
    private fun assertShown(label: String, of: FakeSource = source) {
        await("$label: the section is drawn") { tags(BankAlertSectionTags.ROOT) == 1 }
        await("$label: and collects both flows") { collected(of) }
        assertEquals("$label: one section", 1, tags(BankAlertSectionTags.ROOT))
    }
    /** Once the section left the composition: no slot, no section, no collection, no demand beyond [ensures]. */
    private fun assertGone(label: String, ensures: Int, of: FakeSource = source) {
        await("$label: the collection ended") { released(of) }
        assertEquals("$label: no slot", 0, inSlot(BankHistoryTags.ENTRY) + tags(PremiumTopicTags.BELOW_RATES))
        assertEquals("$label: no section", 0, tags(BankAlertSectionTags.ROOT))
        assertEquals("$label: no collection", 0 to 0, subs(of))
        assertEquals("$label: no ensure while gone", ensures, of.ensures.get())
        assertEquals("$label: no other demand", 0 to 0, of.manuals.get() to of.authoritatives.get())
    }

    // ---- S7E5A1 §5 row 5 ---------------------------------------------------------------------------------------------------

    /**
     * Row 5a: TETHER and NEWS draw no slot and never ask the factory, ensure or a flow; each of USD, JPY and EUR draws one section
     * in the slot, under the history entry, showing only its own currency's row of a mixed list, and its appearance asks ensure
     * once. The section does not make the history wiring.
     */
    @Test fun s7e5a1_r5a_oneSectionUnderTheHistoryEntryOfTheBankTabsOnly_andTheOtherTabsResolveNothing() {
        source.settings.value = ready(usd, jpy, eur)
        focus.value = OwnedTopicFocus(a1, FreeTab.TETHER)
        show()
        for (tab in listOf(FreeTab.TETHER, FreeTab.NEWS)) {
            select(tab)
            rule.onNodeWithTag(PremiumTopicTags.tab(tab)).assertIsSelected()
            if (tab == FreeTab.TETHER) assertEquals("premise($tab): its rates are drawn", 1, tags(PremiumTopicTags.RATES))
            assertEquals("$tab: no slot", 0, tags(PremiumTopicTags.BELOW_RATES))
            assertEquals("$tab: no section", 0, tags(BankAlertSectionTags.ROOT))
            assertEquals("$tab: the factory was never asked", 0, factory.created.get())
            assertEquals("$tab: no ensure", 0, source.ensures.get())
            assertEquals("$tab: nothing collected", 0 to 0, subs(source))
        }
        for ((n, case) in listOf(FreeTab.USD to usd, FreeTab.JPY to jpy, FreeTab.EUR to eur).withIndex()) {
            val (tab, own) = case
            select(tab)
            rule.onNodeWithTag(PremiumTopicTags.tab(tab)).assertIsSelected()
            assertShown("$tab")
            assertEquals("$tab: the history entry is in the slot", 1, inSlot(BankHistoryTags.ENTRY))
            assertEquals("$tab: the section is in the slot", 1, inSlot(BankAlertSectionTags.ROOT))
            val entry = rule.onNodeWithTag(BankHistoryTags.ENTRY).fetchSemanticsNode().boundsInRoot
            val section = rule.onNodeWithTag(BankAlertSectionTags.ROOT).fetchSemanticsNode().boundsInRoot
            assertTrue("$tab: the section ($section) is below the history entry ($entry)", section.top >= entry.bottom - 0.5f)
            await("$tab: its own row is drawn") { tags(BankAlertSectionTags.row(own.id)) == 1 }
            for (row in listOf(usd, jpy, eur)) {
                assertEquals("$tab: the ${row.currency} row", if (row === own) 1 else 0, tags(BankAlertSectionTags.row(row.id)))
            }
            assertTrue("$tab: the factory was asked", factory.created.get() > 0)
            assertEquals("$tab: its appearance asked ensure once", n + 1, source.ensures.get())
        }
        select(FreeTab.TETHER)
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.TETHER)).assertIsSelected()
        assertGone("back on TETHER", ensures = 3)
        assertEquals("the section made no history wiring", 0, historyMade.get())
    }

    /**
     * Row 5b: the first appearance asks ensure once; a route recomposition (a connection publication with its banner), the fake's
     * flow changes, collapsing and expanding, and the process foreground (no lifecycle observer of its own) ask none and make no
     * second source; leaving to TETHER asks none, and every re-entry of a bank tab — the same one, or another one directly — asks
     * ensure once more.
     */
    @Test fun s7e5a1_r5b_theFirstAppearanceEnsuresOnce_recompositionCollapseAndForegroundAddNone_reentryEnsuresAgain() {
        show()
        assertShown("first appearance")
        await("the first appearance asked ensure") { source.ensures.get() == 1 }
        assertEquals("the first appearance asked ensure once", 1, source.ensures.get())
        val made = factory.created.get()
        assertTrue("premise: the factory made the source", made > 0)

        rule.runOnUiThread { display.value = display.value.copy(connection = TopicConnectionDisplay.OFFLINE,
            rates = display.value.rates.merge(listOf(TopicQuote("kb", "usd-krw", 1391.0, t + 1.seconds)))) }
        rule.waitForIdle()
        await("premise: the route recomposed with the offline banner") { texts("오프라인 모드") == 1 }
        rule.runOnUiThread { display.value = display.value.copy(connection = TopicConnectionDisplay.OPEN) }
        rule.waitForIdle()
        await("premise: the route recomposed without it") { texts("오프라인 모드") == 0 }
        assertEquals("a route recomposition: the section stayed", 1, tags(BankAlertSectionTags.ROOT))
        assertEquals("a route recomposition asked no ensure", 1, source.ensures.get())

        publish(source, ready(usd))
        await("premise: the published row is drawn") { tags(BankAlertSectionTags.row(usd.id)) == 1 }
        publish(source, ManualRefreshState(requested = true, sending = true))
        await("premise: the published sending is drawn") { !refreshEnabled() }
        publish(source, ManualRefreshState())
        await("premise: the published idle is drawn") { refreshEnabled() }
        assertEquals("flow changes asked no ensure", 1, source.ensures.get())

        tap(BankAlertSectionTags.TOGGLE_EXPANDED)
        await("premise: collapsed") { tags(BankAlertSectionTags.ROWS) == 0 }
        assertEquals("collapsed: the section stayed", 1, tags(BankAlertSectionTags.ROOT))
        assertEquals("collapsing asked no ensure", 1, source.ensures.get())
        tap(BankAlertSectionTags.TOGGLE_EXPANDED)
        await("premise: expanded") { tags(BankAlertSectionTags.ROWS) == 1 }
        assertEquals("expanding asked no ensure", 1, source.ensures.get())

        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertShown("premise: resumed with the section")
        assertEquals("the process foreground asked no ensure", 1, source.ensures.get())
        assertEquals("no second source was made", made, factory.created.get())
        assertEquals("no other demand", 0 to 0, source.manuals.get() to source.authoritatives.get())

        select(FreeTab.TETHER)
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.TETHER)).assertIsSelected()
        assertGone("left to TETHER", ensures = 1)
        var expected = 1
        for (tab in listOf(FreeTab.USD, FreeTab.EUR, FreeTab.JPY, FreeTab.USD)) {
            select(tab)
            rule.onNodeWithTag(PremiumTopicTags.tab(tab)).assertIsSelected()
            assertShown("re-entered $tab")
            expected += 1
            await("re-entered $tab: ensure asked again") { source.ensures.get() == expected }
            assertEquals("re-entered $tab: ensure asked once more", expected, source.ensures.get())
        }
    }

    /**
     * Row 5c: what the fake's flows publish is what the real section draws — the content kind by typed phase and rows (loading only
     * while this currency has no row, the fixed error copy, another currency's rows alone are empty) and the refresh button's
     * enabled state (disabled while sending or cooling down; a demand only waiting for access is enabled).
     */
    @Test fun s7e5a1_r5c_theFakesFlowChangesAreRendered() {
        show()
        assertShown("premise")
        data class Case(val label: String, val state: BankAlertSettingsState, val drawn: String, val text: String? = null)
        for (case in listOf(
            Case("idle, none", phase(AlertLoadPhase.Idle), BankAlertSectionTags.EMPTY),
            Case("loading, none", phase(AlertLoadPhase.Loading), BankAlertSectionTags.LOADING),
            Case("network terminal", phase(AlertLoadPhase.TerminalError(AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null))),
                BankAlertSectionTags.ERROR, "네트워크 연결을 확인해 주세요."),
            Case("retrying, none", phase(AlertLoadPhase.Retrying), BankAlertSectionTags.LOADING),
            Case("forbidden terminal", phase(AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden)),
                BankAlertSectionTags.ERROR, "접근 권한이 없습니다."),
            Case("ready, mixed", ready(usd, jpy), BankAlertSectionTags.ROWS),
            Case("ready, another currency only", ready(jpy), BankAlertSectionTags.EMPTY),
            Case("loading over a held row", phase(AlertLoadPhase.Loading, usd), BankAlertSectionTags.ROWS))) {
            publish(source, case.state)
            await("${case.label}: ${case.drawn} is drawn") { tags(case.drawn) == 1 }
            for (other in contentTags - case.drawn) assertEquals("${case.label}: no $other", 0, tags(other))
            case.text?.let { assertEquals("${case.label}: its fixed copy", 1, textUnder(it, BankAlertSectionTags.ERROR)) }
            if (case.drawn == BankAlertSectionTags.ROWS) {
                assertEquals("${case.label}: the usd row", 1, tags(BankAlertSectionTags.row(usd.id)))
                assertEquals("${case.label}: no jpy row", 0, tags(BankAlertSectionTags.row(jpy.id)))
            }
        }
        rule.onNodeWithTag(BankAlertSectionTags.REFRESH).assertIsEnabled()
        for ((label, manual, enabled) in listOf(
            Triple("sending", ManualRefreshState(requested = true, sending = true), false),
            Triple("waiting for access", ManualRefreshState(requested = true, sending = false), true),
            Triple("cooling down", ManualRefreshState(coolingDown = true, cooldownUntilMillis = 60_000L), false),
            Triple("idle", ManualRefreshState(), true))) {
            publish(source, manual)
            await("$label: refresh enabled=$enabled is drawn") { refreshEnabled() == enabled }
            val button = rule.onNodeWithTag(BankAlertSectionTags.REFRESH)
            if (enabled) button.assertIsEnabled() else button.assertIsNotEnabled()
        }
        assertEquals("drawing asked one ensure and nothing else", listOf(1, 0, 0),
            listOf(source.ensures.get(), source.manuals.get(), source.authoritatives.get()))
    }

    /**
     * Row 5d: the header refresh reaches the fake's manual once per tap and the error retry its authoritative once per tap; neither
     * asks ensure or the other demand; a disabled refresh reaches nothing.
     */
    @Test fun s7e5a1_r5d_refreshReachesManual_andRetryReachesAuthoritative() {
        show()
        assertShown("premise")
        await("premise: the appearance asked ensure") { source.ensures.get() == 1 }
        rule.onNodeWithTag(BankAlertSectionTags.REFRESH).assertIsEnabled()
        tap(BankAlertSectionTags.REFRESH)
        await("a refresh tap reached manual") { source.manuals.get() == 1 }
        assertEquals("one refresh tap: one manual, no authoritative, no ensure", listOf(1, 0, 1),
            listOf(source.manuals.get(), source.authoritatives.get(), source.ensures.get()))
        tap(BankAlertSectionTags.REFRESH)
        await("a second refresh tap reached manual") { source.manuals.get() == 2 }

        publish(source, ManualRefreshState(coolingDown = true, cooldownUntilMillis = 60_000L))
        await("premise: refresh is disabled while cooling down") { !refreshEnabled() }
        rule.onNodeWithTag(BankAlertSectionTags.REFRESH).assertIsNotEnabled()
        tap(BankAlertSectionTags.REFRESH)
        assertEquals("a disabled refresh reached nothing", 2, source.manuals.get())

        publish(source, ManualRefreshState())
        publish(source, phase(AlertLoadPhase.TerminalError(AlertLoadFailure.Failed(AlertRepositoryFailureKind.HTTP, 500))))
        await("premise: the error card with its retry") { tags(BankAlertSectionTags.RETRY) == 1 }
        tap(BankAlertSectionTags.RETRY)
        await("a retry tap reached authoritative") { source.authoritatives.get() == 1 }
        assertEquals("one retry tap: one authoritative, no manual, no ensure", listOf(1, 2, 1),
            listOf(source.authoritatives.get(), source.manuals.get(), source.ensures.get()))
    }

    /**
     * Row 5e: settings, the rate fullscreen and a news detail left open over the news tab each leave no slot and no section — the
     * section left the composition, its collection ended and it asked nothing; leaving the layer brings it back with one ensure.
     */
    @Test fun s7e5a1_r5e_settingsTheRateFullscreenAndANewsDetailHideTheSlotAndTheSection() {
        show(listOf(NewsItem("n1", "fixture news", "https://example.invalid/n1", "fixture", "external_link", t)))
        assertShown("premise")
        await("premise: the appearance asked ensure") { source.ensures.get() == 1 }
        var ensures = 1
        for (layer in listOf("settings", "rate fullscreen")) {
            assertShown("premise($layer)")
            // Plain clicks: the settings button sits outside the page scroll.
            rule.onNodeWithTag(if (layer == "settings") PremiumTopicTags.SETTINGS else PremiumTopicTags.FULLSCREEN).performClick()
            rule.waitForIdle()
            assertTrue("premise($layer): the layer shows", if (layer == "settings") settingsShown > 0 else
                rule.onAllNodesWithTag(PremiumTopicTags.FULLSCREEN_LAYER).fetchSemanticsNodes().isNotEmpty())
            assertGone("under $layer", ensures)
            if (layer == "settings") back() else rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick()
            rule.waitForIdle()
            assertShown("left $layer")
            ensures += 1
            await("left $layer: the new appearance asked ensure") { source.ensures.get() == ensures }
            assertEquals("left $layer: one ensure", ensures, source.ensures.get())
        }
        select(FreeTab.NEWS)
        await("premise: the news is drawn") { texts("fixture news") > 0 }
        rule.onNodeWithText("fixture news").performClick()
        rule.waitForIdle()
        select(FreeTab.USD)
        rule.onNodeWithTag(PremiumTopicTags.tab(FreeTab.USD)).assertIsSelected()
        assertEquals("premise: the usd rates are drawn under the detail", 1, tags(PremiumTopicTags.RATES))
        assertGone("under the news detail", ensures)
        back()
        assertShown("left the news detail")
        await("left the news detail: the new appearance asked ensure") { source.ensures.get() == ensures + 1 }
        assertEquals("left the news detail: one ensure", ensures + 1, source.ensures.get())
    }

    /**
     * Row 5f: the route's inactivity, the loss of its owner and its disposal leave no section and no collection; the route active
     * again, the owner back and a remount are each a new appearance that asks ensure once.
     */
    @Test fun s7e5a1_r5f_inactivityOwnerLossAndDisposalLeaveNoSection() {
        show()
        assertShown("premise")
        await("premise: the appearance asked ensure") { source.ensures.get() == 1 }
        var ensures = 1
        for ((label, away, back) in listOf<Triple<String, () -> Unit, () -> Unit>>(
                Triple("inactive", { active = false }, { active = true }),
                Triple("owner lost", { rule.runOnUiThread { display.value = display.value.copy(owner = null) } },
                    { rule.runOnUiThread { display.value = display.value.copy(owner = o1) } }),
                Triple("disposed", { mounted = false }, { mounted = true }))) {
            assertShown("premise($label)")
            away()
            rule.waitForIdle()
            assertGone(label, ensures)
            back()
            rule.waitForIdle()
            assertShown("$label, then back")
            ensures += 1
            await("$label, then back: ensure asked again") { source.ensures.get() == ensures }
            assertEquals("$label, then back: one ensure", ensures, source.ensures.get())
        }
    }

    /**
     * Row 5g: a replaced factory's source is the only one used afterwards — it is made and ensured once, its flows are drawn and
     * collected, refresh and retry reach it; the old source is no longer collected, its publications are not drawn and it is
     * asked nothing more.
     */
    @Test fun s7e5a1_r5g_aReplacedFactorysSourceIsTheOnlyOneUsed() {
        show()
        assertShown("premise: the first source")
        await("premise: the first source was ensured") { source.ensures.get() == 1 }
        val next = FakeSource()
        val nextFactory = FakeFactory(next)
        sectionFactory = nextFactory
        rule.waitForIdle()
        assertShown("the replacement", of = next)
        await("the replacement was ensured") { next.ensures.get() == 1 }
        assertTrue("the replacement factory was asked", nextFactory.created.get() > 0)
        assertEquals("the replacement was ensured once", 1, next.ensures.get())
        await("the first source is no longer collected") { released(source) }
        publish(next, ready(setting(21, "usd-krw")))
        await("premise: the replacement's row is drawn") { tags(BankAlertSectionTags.row(21)) == 1 }
        publish(source, ready(usd))
        assertEquals("the first source's publication is not drawn", 0, tags(BankAlertSectionTags.row(usd.id)))
        assertEquals("the replacement's row stays", 1, tags(BankAlertSectionTags.row(21)))
        tap(BankAlertSectionTags.REFRESH)
        await("refresh reached the replacement") { next.manuals.get() == 1 }
        publish(next, phase(AlertLoadPhase.TerminalError(AlertLoadFailure.AuthRequired)))
        await("premise: the replacement's error is drawn") { tags(BankAlertSectionTags.RETRY) == 1 }
        tap(BankAlertSectionTags.RETRY)
        await("retry reached the replacement") { next.authoritatives.get() == 1 }
        assertEquals("the first source was asked nothing more", listOf(1, 0, 0),
            listOf(source.ensures.get(), source.manuals.get(), source.authoritatives.get()))
        assertEquals("and is not collected", 0 to 0, subs(source))
    }

    // ---- Row 5h: the real graph host's own fullscreen (harness as in BankHistoryEntryRouteTest S7C_L04 graph) -------------

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

    /**
     * Row 5h: the real graph host's holder publishes its fullscreen from outside the screen; once the covering composition applied
     * there is no section and no collection and nothing was asked; leaving the fullscreen brings the section back with one ensure.
     */
    @Test fun s7e5a1_r5h_theGraphHostsOwnFullscreenHidesTheSlotAndTheSection() {
        host = newHost()
        show()
        assertShown("premise: the section beside the graph")
        await("premise: the appearance asked ensure") { source.ensures.get() >= 1 }
        val usdHolder = { onMain { built.toList().single { it.field("tab") == "usd" && it.field("closed") == false } } }
        await("premise: the usd holder has an inline token") {
            runCatching { onMain { usdHolder().currentState().inlineToken } != null }.getOrDefault(false)
        }
        assertShown("premise: the section beside the inline graph")
        // Counted from here: row 5b locks the first appearance; this row locks the layer.
        val before = source.ensures.get()
        val token = onMain { checkNotNull(usdHolder().currentState().inlineToken) { "premise: an inline token" } }
        rule.runOnUiThread { usdHolder().enterFullscreen(token) }
        rule.waitForIdle()
        await("premise: the holder's fullscreen is drawn") { exists(GraphV2UiTags.root(GraphV2Surface.FULLSCREEN)) }
        assertGone("under the graph fullscreen", ensures = before)
        val full = onMain { checkNotNull(usdHolder().currentState().fullscreenToken) { "premise: a fullscreen token" } }
        rule.runOnUiThread { usdHolder().exitFullscreen(full) }
        rule.waitForIdle()
        assertShown("left the graph fullscreen")
        await("left the graph fullscreen: the new appearance asked ensure") { source.ensures.get() == before + 1 }
        assertEquals("left the graph fullscreen: one ensure", before + 1, source.ensures.get())
    }

    // ---- Rows 5i and 5j: the holder composed directly, without the route ---------------------------------------------------

    /**
     * Row 5i: the holder's own early return (API r2 §2: "USD/JPY/EUR 이외는 sourceFactory.create·EntryPoint 조회·ensure·flow 수집
     * 전에 반환한다"). The route never hands an unsupported tab the slot, so row 5a cannot see this guard; here BankAlertSection is
     * composed directly for every tab other than USD, JPY and EUR, each with its own counting fake whose source holds a row of every
     * currency. None draws a section, none asks its factory, none asks ensure and none collects either flow. A USD section then
     * composed beside them is drawn, made, ensured and collected, so the rig does see each of those; the unsupported ones still
     * asked nothing.
     */
    @Test fun s7e5a1_r5i_theHolderComposedForAnUnsupportedTabDrawsNothing_andNeverAsksTheFactoryEnsureOrAFlow() {
        val unsupported = FreeTab.entries - setOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR)
        assertTrue("premise: NEWS and TETHER are among the unsupported tabs ($unsupported)",
            unsupported.containsAll(listOf(FreeTab.NEWS, FreeTab.TETHER)))
        val sources = unsupported.associateWith { FakeSource().apply { settings.value = ready(usd, jpy, eur) } }
        val factories = sources.mapValues { (_, of) -> FakeFactory(of) }
        fun probe(tab: FreeTab) = "s7e5a1-r5i-holder-${tab.name}"
        fun sectionsUnder(tag: String) =
            rule.onAllNodes(hasTestTag(BankAlertSectionTags.ROOT) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().size
        var control by mutableStateOf(false)
        rule.setContent {
            Column {
                for (tab in unsupported) {
                    Box(Modifier.testTag(probe(tab))) { BankAlertSection(tab = tab, alertQuotes = emptyMap(), sourceFactory = factories.getValue(tab)) }
                }
                if (control) Box(Modifier.testTag(probe(FreeTab.USD))) { BankAlertSection(tab = FreeTab.USD, alertQuotes = emptyMap(), sourceFactory = factory) }
            }
        }
        rule.waitForIdle()

        fun assertResolvedNothing(stage: String) {
            for (tab in unsupported) {
                val of = sources.getValue(tab)
                assertEquals("$stage, $tab: premise: the holder was composed for it", 1, tags(probe(tab)))
                assertEquals("$stage, $tab: an unsupported tab draws no section", 0, sectionsUnder(probe(tab)))
                assertEquals("$stage, $tab: the holder returns before sourceFactory.create", 0, factories.getValue(tab).created.get())
                assertEquals("$stage, $tab: the holder returns before ensure", 0, of.ensures.get())
                assertEquals("$stage, $tab: the holder returns before collecting either flow", 0 to 0, subs(of))
                assertEquals("$stage, $tab: and asks no other demand", 0 to 0, of.manuals.get() to of.authoritatives.get())
            }
        }
        assertResolvedNothing("composed")
        assertEquals("composed: no section is drawn for any unsupported tab", 0, tags(BankAlertSectionTags.ROOT))

        control = true
        rule.waitForIdle()
        assertShown("the USD control beside them")
        assertEquals("the USD control: drawn in its own holder", 1, sectionsUnder(probe(FreeTab.USD)))
        assertTrue("the USD control: its factory was asked", factory.created.get() > 0)
        await("the USD control: its appearance asked ensure") { source.ensures.get() == 1 }
        assertEquals("the USD control: its appearance asked ensure once", 1, source.ensures.get())
        assertResolvedNothing("after a supported section appeared and was ensured beside them")
    }

    /**
     * Row 5j: every appearance of a bank tab's section asks ensure exactly once more, whatever the owner shows at that moment
     * (scope: "섹션 등장 = owner.ensure()"; API r2 §2: "섹션 등장은 LaunchedEffect(source, tab) { source.ensure() }") — a terminal
     * error with no rows (the error card, also on a return to the same tab), a cooling-down manual refresh, a sending one, and a
     * terminal error while cooling down. The appearance is gated on neither the settings phase nor the manual state, and leaving
     * asks none. BankAlertSection is composed directly and shown and hidden by a state flag; the fake's states are published while
     * it is hidden, so each appearance meets them.
     */
    @Test fun s7e5a1_r5j_everyAppearanceEnsuresOnceMore_alsoOverATerminalErrorACooldownOrASend() {
        var visible by mutableStateOf(false)
        var shownTab by mutableStateOf(FreeTab.USD)
        rule.setContent { if (visible) BankAlertSection(tab = shownTab, alertQuotes = emptyMap(), sourceFactory = factory) }
        rule.waitForIdle()
        assertEquals("premise: hidden, no section", 0, tags(BankAlertSectionTags.ROOT))
        assertEquals("premise: hidden, no factory making and no ensure", 0 to 0, factory.created.get() to source.ensures.get())

        val all = ready(usd, jpy, eur)
        val networkError = phase(AlertLoadPhase.TerminalError(AlertLoadFailure.Failed(AlertRepositoryFailureKind.NETWORK, null)))
        val forbidden = phase(AlertLoadPhase.TerminalError(AlertLoadFailure.Forbidden))
        val idle = ManualRefreshState()
        val cooling = ManualRefreshState(coolingDown = true, cooldownUntilMillis = 60_000L)
        val sending = ManualRefreshState(requested = true, sending = true)
        data class Case(val label: String, val tab: FreeTab, val settings: BankAlertSettingsState, val manual: ManualRefreshState,
            val drawn: String, val refreshEnabled: Boolean)
        var expected = 0
        for (case in listOf(
            Case("ready, idle", FreeTab.USD, all, idle, BankAlertSectionTags.ROWS, true),
            Case("terminal error, no rows", FreeTab.JPY, networkError, idle, BankAlertSectionTags.ERROR, true),
            Case("terminal error, no rows, back on the same tab", FreeTab.JPY, networkError, idle, BankAlertSectionTags.ERROR, true),
            Case("cooling down", FreeTab.EUR, all, cooling, BankAlertSectionTags.ROWS, false),
            Case("sending", FreeTab.USD, all, sending, BankAlertSectionTags.ROWS, false),
            Case("terminal error while cooling down", FreeTab.USD, forbidden, cooling, BankAlertSectionTags.ERROR, false))) {
            val label = "${case.label} (${case.tab})"
            assertEquals("$label: premise: hidden before it appears", 0, tags(BankAlertSectionTags.ROOT))
            assertEquals("$label: premise: nothing collected before it appears", 0 to 0, subs(source))
            publish(source, case.settings)
            publish(source, case.manual)
            shownTab = case.tab
            visible = true
            rule.waitForIdle()
            assertShown(label)
            await("$label: premise: ${case.drawn} is drawn") { tags(case.drawn) == 1 }
            await("$label: premise: refresh enabled=${case.refreshEnabled} is drawn") { refreshEnabled() == case.refreshEnabled }
            expected += 1
            await("$label: the appearance asked ensure, whatever the phase and the manual state") { source.ensures.get() == expected }
            assertEquals("$label: the appearance asked ensure exactly once more", expected, source.ensures.get())
            assertEquals("$label: the appearance asked no other demand", 0 to 0, source.manuals.get() to source.authoritatives.get())

            visible = false
            rule.waitForIdle()
            await("$label, left: the collection ended") { released(source) }
            assertEquals("$label, left: no section", 0, tags(BankAlertSectionTags.ROOT))
            assertEquals("$label, left: leaving asked no ensure", expected, source.ensures.get())
        }
    }
}
