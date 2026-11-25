package com.jay.fxi

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.AlertSettingsAccess
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.dto.SourceAlertHistoryItem
import com.jay.fxi.data.repository.BankHistoryAccess
import com.jay.fxi.data.repository.BankHistoryUse
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.ui.alert.BankAlertSectionSource
import com.jay.fxi.ui.alert.BankAlertSectionSourceFactory
import com.jay.fxi.ui.alert.BankHistoryRead
import com.jay.fxi.ui.alert.BankHistoryTags
import com.jay.fxi.ui.alert.BankHistoryWiring
import com.jay.fxi.ui.alert.BankHistoryWiringFactory
import com.jay.fxi.ui.alert.CountingSourceAlertSection
import com.jay.fxi.ui.alert.CountingSourceHistoryWiring
import com.jay.fxi.ui.alert.SettingsSourceHistoryAccess
import com.jay.fxi.ui.alert.SourceAlertSectionTags
import com.jay.fxi.ui.alert.SourceHistoryTags
import com.jay.fxi.ui.alert.symbol
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.premium.view.PremiumTopicTags
import com.jay.fxi.ui.viewmodel.NewsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * S8-4b (`R4c/S8/s8_4b_contract.agreed.r1.md` §3 androidTest) on the emulator: a [CountingSourceHistoryWiring] through
 * PremiumTopicRoute's new last parameter, under the real route over a real PremiumTopicConsumer, with the production
 * [SettingsSourceHistoryAccess] over a real [AlertSettingsAccess]. The tether tab alone draws the entry, above its section;
 * nothing is made or read before a click; a double click is one session and one read; the sheet draws the tether rows with the
 * rate that fired them and pulls; leaving the tab or the owner (also within one Main task, before a recomposition), settings,
 * the route's inactivity or disposal, closing the sheet or the system Back while it reads, or a revision under a refused use
 * end the session — no sheet, no listener and no revision collection left — and a late answer, which the fake read still
 * delivers, draws nothing; a revision under a valid use keeps the sheet; a click kept past the tab reads nothing.
 */
class SourceHistoryEntryRouteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val o2 = TopicDisplayOwner(a1, 2L)
    private val grant = TopicGrantToken(1L)
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
    private class Bank : BankAlertSectionSourceFactory {
        override fun create(context: Context): BankAlertSectionSource = object : BankAlertSectionSource {
            override val settings = MutableStateFlow(BankAlertSettingsState(AlertLoadPhase.Idle, null, null))
            override val manualRefresh = MutableStateFlow(ManualRefreshState())
            override fun ensure() {}
            override fun authoritative() {}
            override fun manual() {}
            override fun setEnabled(id: Int, enabled: Boolean) {}
            override fun add(draft: com.jay.fxi.data.alerts.BankAlertDraft): com.jay.fxi.data.alerts.BankAlertSaveRequest = error("S8-4b: not used here")
            override fun edit(id: Int, edit: com.jay.fxi.data.alerts.BankAlertEdit): com.jay.fxi.data.alerts.BankAlertSaveRequest = error("S8-4b: not used here")
            override val bankSelection = MutableStateFlow<com.jay.fxi.data.local.BankSelectionSnapshot>(com.jay.fxi.data.local.BankSelectionSnapshot.None)
            override suspend fun prepareBankSelection(identity: AuthIdentityFence) {
                bankSelection.value = com.jay.fxi.data.local.BankSelectionSnapshot.Ready(identity, 1, null)
            }
            override fun delete(id: Int) {}
        }
    }

    // The access the production adapter checks: A's grant 1, premium, release open, no deletion.
    private val premium = MutableStateFlow(OwnedPremiumAccess("A", 1L, PremiumAccessState.PremiumConfirmed))
    private val deletions = DeletionAdmissionStore()
    private val snapshot = TopicAccessSnapshot.INITIAL.copy(
        revision = 1L, facts = TopicAccessFacts.NONE.copy(token = grant, tokenStanding = true, userBlocks = emptySet()), userInvalidations = 0L
    )
    private val base = AlertSettingsAccess({ a1 }, { TopicSessionFence(a1, "e1", grant) }, SnapshotTopicUseAuthority { snapshot },
        { premium.value }, { true }, GraphProtectedAdmission({ a1 }, deletions))
    private val history = CountingSourceHistoryWiring { displayOwner -> SettingsSourceHistoryAccess(base, displayOwner) }

    private val display = MutableStateFlow(TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.TETHER))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consumer = PremiumTopicConsumer(display, focus, { a1 }, MemoryRows(),
        { o, tab -> focus.value = OwnedTopicFocus(o, tab) }, { }, { _, _ -> }, scope)
    private var active by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private val bankHistory = BankHistoryWiringFactory { _, _ ->
        BankHistoryWiring(object : BankHistoryAccess {
            override fun acquire(owner: TopicDisplayOwner): BankHistoryUse? = null
            override fun admits(use: BankHistoryUse) = false
        }, BankHistoryRead { _, _, _ -> awaitCancellation() }, MutableStateFlow(0L), MutableStateFlow(0L))
    }
    @After fun tearDown() {
        history.reads.forEach { it.release() }
        scope.cancel()
    }

    private fun show(newsItems: List<NewsItem> = emptyList()) {
        val vm = NewsViewModel(NoNews(newsItems))
        rule.setContent {
            if (mounted) {
                PremiumTopicRoute(consumer = consumer, identity = a1, isActive = active, newsViewModel = vm,
                    userInfo = UserInfo("A", null, null, null, AuthProvider.entries.first()), onSignOut = {},
                    settingsContent = { _, _, _ -> }, bankHistoryWiring = bankHistory,
                    bankAlertSectionSourceFactory = Bank(), sourceAlertSectionSourceFactory = CountingSourceAlertSection(),
                    sourceHistoryWiring = history,
                    comparisonAlertSectionSourceFactory = comparisonTestFactory(consumer))
            }
        }
        rule.waitForIdle()
    }

    // ---- fixtures and reads ----------------------------------------------------------------------------------------------

    private fun item(id: Int, source: String = "upbit", asset: String = "usdt-krw") =
        SourceAlertHistoryItem(id, null, source, asset, AlertCondition.ABOVE, 1400.5, 1401.25, t)
    private fun ok(vararg items: SourceAlertHistoryItem) = Result.success(items.toList())

    private fun select(tab: FreeTab) { focus.value = OwnedTopicFocus(a1, tab); rule.waitForIdle() }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun tags(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun texts(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size
    private fun underRow(text: String) =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(SourceHistoryTags.ROW)), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun inSlot(tag: String) =
        rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(PremiumTopicTags.BELOW_RATES))).fetchSemanticsNodes().size
    private fun sheets() = tags(SourceHistoryTags.SHEET)
    private fun reads() = history.reads.size
    /** The collectors of the two revision flows: an open session's controller collects each once. */
    private fun collectors() = listOf(history.accessRevisions.subscriptionCount.value, history.deletionRevisions.subscriptionCount.value)
    private fun <T> onMain(read: () -> T): T {
        var result: Result<T>? = null
        rule.runOnUiThread { result = runCatching(read) }
        return checkNotNull(result).getOrThrow()
    }
    /** The consumer's screen listeners now: an open session's controller holds one. */
    private fun listeners(): Int = onMain {
        (PremiumTopicConsumer::class.java.getDeclaredField("screenListeners").apply { isAccessible = true }.get(consumer) as List<*>).size
    }
    /** S9-2b: every composed comparison card (`comparison-absolute`, `comparison-signed`) holds one screen listener of its
     *  own (ComparisonAlertSection). The route's tether session and the history session's listeners are the rest. */
    private fun comparisonCards(): Int =
        listOf("comparison-absolute", "comparison-signed").sumOf { rule.onAllNodesWithTag(it).fetchSemanticsNodes().size }
    private fun sessionListeners(): Int = listeners() - comparisonCards()
    private fun device() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun center(node: SemanticsNode) = node.positionOnScreen.let { (it.x + node.size.width / 2f).toInt() to (it.y + node.size.height / 2f).toInt() }

    private fun await(label: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(5_000) { condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("timed out: $label", e)
        }
    }
    /** The entry's click action, run [times] times in one Main task. */
    private fun click(times: Int = 1) {
        val action = rule.onNodeWithTag(SourceHistoryTags.ENTRY).fetchSemanticsNode().config[SemanticsActions.OnClick].action
        assertNotNull("premise: the entry has a click action", action)
        rule.runOnUiThread { repeat(times) { action!!.invoke() } }
        rule.waitForIdle()
    }
    private fun answer(read: CountingSourceHistoryWiring.Read, result: Result<List<SourceAlertHistoryItem>>) {
        read.answer(result)
        rule.waitForIdle()
        await("the read came back into the holder") { read.finished }
        rule.waitForIdle()
    }
    /** Open the session and answer its first read with one tether row. */
    private fun openWithRows(label: String) {
        click()
        await("$label: the sheet is open") { sheets() == 1 }
        answer(history.reads.last(), ok(item(1)))
        await("$label: its row is drawn") { tags(SourceHistoryTags.ROW) == 1 }
    }
    private fun assertNothingShown(label: String) {
        assertEquals("$label: no sheet", 0, sheets())
        assertEquals("$label: no row", 0, tags(SourceHistoryTags.ROW))
        assertEquals("$label: no spinner", 0, texts("히스토리 로드 중..."))
        assertEquals("$label: no error or retry", 0, texts("다시 시도"))
    }

    // ---- placement -----------------------------------------------------------------------------------------------------

    /**
     * S8_4B_T01: the tether tab draws one entry in its slot, above its section; USD, JPY, EUR and NEWS draw none; settings, the
     * rate fullscreen and a news detail over the tether tab draw none. Nothing is made or read without a click.
     */
    @Test fun s84b_t01_oneEntryOnTheTetherTabOnly_aboveItsSection_andNothingMadeWithoutAClick() {
        show(listOf(NewsItem("n1", "fixture news", "https://example.invalid/n1", "fixture", "external_link", t)))
        await("tether: the entry is drawn in the slot") { inSlot(SourceHistoryTags.ENTRY) == 1 }
        await("tether: and the section") { inSlot(SourceAlertSectionTags.ROOT) == 1 }
        val entry = rule.onNodeWithTag(SourceHistoryTags.ENTRY).fetchSemanticsNode().boundsInRoot
        val section = rule.onNodeWithTag(SourceAlertSectionTags.ROOT).fetchSemanticsNode().boundsInRoot
        assertTrue("the section ($section) is below the entry ($entry)", section.top >= entry.bottom - 0.5f)
        for (tab in listOf(FreeTab.USD, FreeTab.JPY, FreeTab.EUR, FreeTab.NEWS)) {
            select(tab)
            assertEquals("$tab: no tether history entry", 0, tags(SourceHistoryTags.ENTRY))
            if (tab != FreeTab.NEWS) assertEquals("$tab: the bank entry", 1, tags(BankHistoryTags.ENTRY))
        }
        select(FreeTab.TETHER)
        for (layer in listOf(PremiumTopicTags.SETTINGS, PremiumTopicTags.FULLSCREEN)) {
            rule.onNodeWithTag(layer).performClick(); rule.waitForIdle()
            assertEquals("under $layer: no entry", 0, tags(SourceHistoryTags.ENTRY))
            if (layer == PremiumTopicTags.SETTINGS) back() else { rule.onNodeWithTag(PremiumTopicTags.FULLSCREEN_CLOSE).performClick(); rule.waitForIdle() }
            await("left $layer: the entry is back") { tags(SourceHistoryTags.ENTRY) == 1 }
        }
        select(FreeTab.NEWS)
        rule.onNodeWithText("fixture news").performClick(); rule.waitForIdle()
        select(FreeTab.TETHER)
        assertEquals("under the news detail: no entry", 0, tags(SourceHistoryTags.ENTRY))
        back()
        assertEquals("nothing was made or read without a click", listOf(0, 0), listOf(history.made.get(), reads()))
    }

    // ---- the session ---------------------------------------------------------------------------------------------------

    /**
     * S8_4B_T02: a double click is one session and one read of the tether asset; the sheet draws the tether rows only, with the
     * condition's threshold and the rate that fired it; a pull reads once more and keeps the rows; closing removes the sheet,
     * its listener and its revision collection, and a new click is a new session with a new wiring and a new read.
     */
    @Test fun s84b_t02_aDoubleClickIsOneReadTheSheetDrawsTheTriggeredRateAPullReadsAndReopeningIsNew() {
        show()
        val baseline = sessionListeners()
        assertEquals("premise: nothing open", listOf(0, 0, 0), listOf(sheets(), history.made.get(), reads()))
        click(times = 2)
        await("the sheet is open") { sheets() == 1 }
        assertEquals("a double click: one wiring, one read", listOf(1, 1), listOf(history.made.get(), reads()))
        assertEquals("the read is the tether asset", "usdt-krw", history.reads.single().asset)
        assertEquals("the session listens once", baseline + 1, sessionListeners())
        await("the session collects both revisions once") { collectors() == listOf(1, 1) }
        answer(history.reads.single(), ok(item(1), item(2, "krx", "usd-krw-futures"), item(3, "binance")))
        await("the tether row is drawn") { tags(SourceHistoryTags.ROW) == 1 }
        assertEquals("its rate that fired it", 1, underRow("1401.25"))
        assertEquals("its threshold in the condition", 1, underRow("1400.50 ${AlertCondition.ABOVE.symbol} ${AlertCondition.ABOVE.displayText}"))
        val (x, y) = center(rule.onNodeWithText("1401.25", useUnmergedTree = true).fetchSemanticsNode())
        device().swipe(x, y, x, y + 700, 60)
        rule.waitForIdle()
        await("the pull read once more") { reads() == 2 }
        assertEquals("the row stays while it pulls", 1, tags(SourceHistoryTags.ROW))
        answer(history.reads.last(), ok(item(4)))
        rule.onNodeWithText("닫기").performClick(); rule.waitForIdle()
        await("closed: no sheet") { sheets() == 0 }
        assertEquals("closed: the listener is gone", baseline, sessionListeners())
        await("closed: the revision collection is gone") { collectors() == listOf(0, 0) }
        click()
        await("reopened") { sheets() == 1 }
        assertEquals("a new session: a new wiring and a new read", listOf(2, 3), listOf(history.made.get(), reads()))
    }

    /**
     * S8_4B_T03: leaving ends the session at once — the tab left, the owner replaced, settings opened, the route inactive, the
     * route disposed, the tab or the owner left and re-entered within one Main task before a recomposition, the sheet closed or
     * dismissed by the system Back while its read is open — no sheet, no listener and no revision collection left, the open
     * read's guard refuses, a late answer reaches the holder and draws nothing, and nothing more is read.
     */
    @Test fun s84b_t03_leavingEndsTheSessionAtOnce_andALateAnswerDrawsNothing() {
        show()
        val baseline = sessionListeners()
        // S8-5b: the route's own tether-session listener lives while an active route draws this owner's tether tab.
        assertEquals("premise: on the tether tab the route's tether session listens once", 1, baseline)
        for ((label, away, home) in listOf<Triple<String, () -> Unit, () -> Unit>>(
                Triple("tab left", { select(FreeTab.USD) }, { select(FreeTab.TETHER) }),
                Triple("left and back in one Main task", {
                    var between: FreeTab? = null
                    rule.runOnUiThread {
                        focus.value = OwnedTopicFocus(a1, FreeTab.USD)
                        between = consumer.state.value.ui.selectedTab
                        focus.value = OwnedTopicFocus(a1, FreeTab.TETHER)
                    }
                    rule.waitForIdle()
                    assertEquals("premise: USD was published within the task", FreeTab.USD, between)
                }, {}),
                Triple("settings", { rule.onNodeWithTag(PremiumTopicTags.SETTINGS).performClick(); rule.waitForIdle() }, { back() }),
                Triple("inactive", { active = false; rule.waitForIdle() }, { active = true; rule.waitForIdle() }),
                Triple("disposed", { mounted = false; rule.waitForIdle() }, { mounted = true; rule.waitForIdle() }),
                Triple("owner replaced", { rule.runOnUiThread { display.value = display.value.copy(owner = o2) }; rule.waitForIdle() },
                    { rule.runOnUiThread { display.value = display.value.copy(owner = o1) }; rule.waitForIdle() }),
                Triple("owner away and back in one Main task", {
                    var between: TopicDisplayOwner? = null
                    rule.runOnUiThread {
                        display.value = display.value.copy(owner = o2)
                        between = consumer.state.value.ui.owner
                        display.value = display.value.copy(owner = o1)
                    }
                    rule.waitForIdle()
                    assertEquals("premise: the other owner was published within the task", o2, between)
                }, {}),
                Triple("closed while reading", { rule.onNodeWithText("닫기").performClick(); rule.waitForIdle() }, {}),
                Triple("system Back while reading", { device().pressBack(); rule.waitForIdle() }, {}))) {
            await("premise($label): the entry is drawn") { tags(SourceHistoryTags.ENTRY) == 1 }
            click()
            await("$label: the sheet is open") { sheets() == 1 }
            val read = history.reads.last()
            val before = reads()
            away()
            await("$label: the sheet is gone") { sheets() == 0 }
            val routeListens = label !in setOf("tab left", "inactive", "disposed")
            assertEquals("$label: no listener left", if (routeListens) baseline else baseline - 1, sessionListeners())
            await("$label: no revision collection left") { collectors() == listOf(0, 0) }
            assertTrue("$label: the open read's guard refuses", !read.guard())
            home()
            await("$label, then back: the entry is drawn") { tags(SourceHistoryTags.ENTRY) == 1 }
            assertEquals("$label: premise, the read has no answer yet", 0, read.folded.get())
            answer(read, ok(item(1)))
            assertEquals("$label: the late answer reached the holder", 1, read.folded.get())
            assertNothingShown("$label, then a late answer")
            assertEquals("$label: nothing more is read", before, reads())
        }
    }

    /**
     * S8_4B_T04: an access or deletion revision re-checks the open session: under a valid use the sheet stays; once the use is
     * refused, the revision ends it — no sheet, no listener, no revision collection — with its rows shown or with its read in
     * flight (whose guard then refuses, also once the access is back, and whose late answer draws nothing); nothing more is read.
     */
    @Test fun s84b_t04_aRevisionUnderAValidUseKeepsTheSheet_andUnderARefusedUseEndsIt() {
        show()
        val baseline = sessionListeners()
        openWithRows("access")
        rule.runOnUiThread { history.accessRevisions.value += 1 }; rule.waitForIdle()
        assertEquals("a revision under a valid use keeps the sheet", listOf(1, 1), listOf(sheets(), tags(SourceHistoryTags.ROW)))
        rule.runOnUiThread {
            premium.value = OwnedPremiumAccess("A", 1L, PremiumAccessState.FreeConfirmed)
            history.accessRevisions.value += 1
        }
        await("premium lost: the sheet is gone") { sheets() == 0 }
        assertEquals("premium lost: no listener left", baseline, sessionListeners())
        assertNothingShown("premium lost")
        rule.runOnUiThread { premium.value = OwnedPremiumAccess("A", 1L, PremiumAccessState.PremiumConfirmed) }
        rule.waitForIdle()
        assertEquals("a recovered access reopens nothing", 0, sheets())

        for ((label, revoke, restore) in listOf<Triple<String, () -> Unit, () -> Unit>>(
                Triple("premium lost while reading", {
                    premium.value = OwnedPremiumAccess("A", 1L, PremiumAccessState.FreeConfirmed)
                    history.accessRevisions.value += 1
                }, { premium.value = OwnedPremiumAccess("A", 1L, PremiumAccessState.PremiumConfirmed) }),
                Triple("deletion begun while reading", {
                    deletions.begin(a1, "op-reading")
                    history.deletionRevisions.value += 1
                }, { deletions.releaseUnsent("op-reading") }))) {
            click()
            await("$label: the sheet is open") { sheets() == 1 }
            val read = history.reads.last()
            val before = reads()
            rule.runOnUiThread { revoke() }
            await("$label: the sheet is gone") { sheets() == 0 }
            assertEquals("$label: no listener left", baseline, sessionListeners())
            await("$label: no revision collection left") { collectors() == listOf(0, 0) }
            assertTrue("$label: the open read's guard refuses", !read.guard())
            rule.runOnUiThread { restore() }
            rule.waitForIdle()
            assertTrue("$label: with the access back, the old guard still refuses", !read.guard())
            assertEquals("$label: premise, the read has no answer yet", 0, read.folded.get())
            answer(read, ok(item(1)))
            assertEquals("$label: the late answer reached the holder", 1, read.folded.get())
            assertNothingShown("$label, then a late answer")
            assertEquals("$label: nothing more is read", before, reads())
        }

        openWithRows("deletion")
        val reads = reads()
        rule.runOnUiThread {
            deletions.begin(a1, "op")
            history.deletionRevisions.value += 1
        }
        await("deletion begun: the sheet is gone") { sheets() == 0 }
        assertEquals("deletion begun: no listener left", baseline, sessionListeners())
        await("deletion begun: no revision collection left") { collectors() == listOf(0, 0) }
        assertEquals("deletion begun: nothing more is read", reads, reads())
    }

    /**
     * S8_4B_T05: the entry's click action kept from the tether tab and run after the tab is left makes its wiring but reads
     * nothing and draws no sheet; the session ends during its controller's registration and leaves no listener and no
     * revision collection.
     */
    @Test fun s84b_t05_aKeptClickAfterTheTabIsLeftReadsNothingAndLeavesNoListener() {
        show()
        val baseline = sessionListeners()
        assertEquals("premise: on the tether tab the route's tether session listens once", 1, baseline)
        val action = rule.onNodeWithTag(SourceHistoryTags.ENTRY).fetchSemanticsNode().config[SemanticsActions.OnClick].action
        assertNotNull("premise: the entry has a click action", action)
        select(FreeTab.USD)
        assertEquals("premise: the entry is gone", 0, tags(SourceHistoryTags.ENTRY))
        rule.runOnUiThread { action!!.invoke() }
        rule.waitForIdle()
        assertEquals("kept click: its wiring is made, nothing is read", listOf(1, 0), listOf(history.made.get(), reads()))
        assertEquals("kept click: no sheet", 0, sheets())
        assertEquals("kept click: no listener left, and the route's tether session does not listen on USD", baseline - 1, sessionListeners())
        await("kept click: no revision collection") { collectors() == listOf(0, 0) }
    }
}
