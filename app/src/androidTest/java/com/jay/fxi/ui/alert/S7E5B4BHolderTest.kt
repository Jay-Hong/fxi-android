package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertDraft
import com.jay.fxi.data.alerts.BankAlertEdit
import com.jay.fxi.data.alerts.BankAlertSaveOutcome
import com.jay.fxi.data.alerts.BankAlertSaveRequest
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.local.BankSelectionSnapshot
import com.jay.fxi.data.local.LastSelectedBankMemory
import com.jay.fxi.data.local.LastSelectedBankStore
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.readNotificationCapability
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b4b (`s7e5b4_api_agreed.r1.md` §2.2–§2.4, §3 기기): the real [BankAlertSection] over a fake source whose bank selection is
 * a real [LastSelectedBankMemory] in front of a scripted store. The Add area waits for this identity's remembered bank; Add
 * opens on it, or on KB when there is none or the read failed; a later success reaches the next Add in any currency; an Edit
 * never uses it. The notification check takes the granted path, as in E5b3b.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B4BHolderTest {

    @get:Rule val compose = createComposeRule()

    private class ScriptedStore : LastSelectedBankStore {
        val values = mutableMapOf<String, String>()
        val reads = AtomicInteger()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failNext = false
        override suspend fun read(uid: String): String? {
            reads.incrementAndGet()
            gate?.await()
            if (failNext) { failNext = false; throw IOException("unreadable") }
            return values[uid]
        }
        override suspend fun remember(uid: String, bankCode: String) { values[uid] = bankCode }
        override suspend fun verifyReadable() = Unit
    }

    private val store = ScriptedStore()
    private val worker = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val memory = LastSelectedBankMemory(store, Dispatchers.Main.immediate, worker) { }

    /** [scripted], when given, is a selection the test sets itself; prepare then only records the identity. */
    private inner class FakeSource(
        state: BankAlertSettingsState,
        private val scripted: MutableStateFlow<BankSelectionSnapshot>? = null
    ) : BankAlertSectionSource {
        override val settings = MutableStateFlow(state)
        override val manualRefresh = MutableStateFlow(ManualRefreshState())
        val adds = mutableListOf<BankAlertDraft>()
        val edits = mutableListOf<Pair<Int, BankAlertEdit>>()
        val handles = mutableListOf<BankAlertSaveRequest>()
        val prepares = mutableListOf<AuthIdentityFence>()
        override fun ensure() = Unit
        override fun authoritative() = Unit
        override fun manual() = Unit
        override fun setEnabled(id: Int, enabled: Boolean) = Unit
        override fun delete(id: Int) = Unit
        override fun add(draft: BankAlertDraft) = BankAlertSaveRequest().also { adds += draft; handles += it }
        override fun edit(id: Int, edit: BankAlertEdit) = BankAlertSaveRequest().also { edits += id to edit; handles += it }
        override val bankSelection: kotlinx.coroutines.flow.StateFlow<BankSelectionSnapshot> = scripted ?: memory.selection
        override suspend fun prepareBankSelection(identity: AuthIdentityFence) {
            prepares += identity
            if (scripted == null) memory.prepare(identity)
        }
    }

    private val env = NotificationPermissionEnvironment(
        admitted = { true },
        liveIdentity = { A },
        premium = MutableStateFlow(OwnedPremiumAccess(A.uid, A.authGeneration, PremiumAccessState.PremiumConfirmed)),
        displayed = MutableStateFlow<NotificationCapability?>(NotificationCapability.GRANTED),
        onRequestResult = { false },
        onAlreadyGranted = { true }
    )

    private val tab = mutableStateOf(FreeTab.USD)
    private val quotes = mapOf("kb" to 1400.0, "hana" to 1500.0, "woori" to 1450.0, "sc" to 1480.0)

    private fun show(source: FakeSource) = compose.setContent {
        CompositionLocalProvider(LocalNotificationPermissionEnvironment provides env) {
            BankAlertSection(tab = tab.value, alertQuotes = quotes, sourceFactory = object : BankAlertSectionSourceFactory {
                override fun create(context: Context): BankAlertSectionSource = source
            })
        }
    }.also { compose.waitForIdle() }

    private fun ready(vararg rows: AlertSetting) = BankAlertSettingsState(AlertLoadPhase.Ready, rows.toList(), A)
    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun textOf(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
        (n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
    }
    private fun save() {
        compose.onNodeWithTag(AlertAddSheetTags.SAVE, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }
    private fun update(block: () -> Unit) { compose.runOnIdle(block); compose.waitForIdle() }
    private fun sheetOpen() = count(AlertAddSheetTags.SAVE) == 1
    /** The worker is real IO: wait for this identity's preparation to settle as expected before touching anything. */
    private fun settled(expected: BankSelectionSnapshot.() -> Boolean) {
        compose.waitUntil(5_000) { memory.selection.value.expected() }
        compose.waitForIdle()
    }
    private fun readyFor(identity: AuthIdentityFence, code: String?) = settled { this is BankSelectionSnapshot.Ready && this.identity == identity && this.code == code }
    /** The Add entry's own click action, taken while it is drawn, to deliver later as a click that raced a change. */
    private fun heldClick(tag: String): () -> Unit {
        val action = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        return { action() }
    }
    /** What the owner does on a validated Add success: the hand-over on Main, then Saved. */
    private fun succeed(source: FakeSource, bank: String) = update {
        memory.acceptAddedBank(A, bank)
        source.handles.last().finish(BankAlertSaveOutcome.Saved(row(90, bank = bank)))
    }

    @Before fun grantedDevice() {
        assertEquals(NotificationCapability.GRANTED, readNotificationCapability(InstrumentationRegistry.getInstrumentation().targetContext))
    }

    @After fun stopWorker() = worker.cancel()

    @Test fun S7E5B4B_K01_theRememberedBankIsTheFirstBank() {
        store.values["A"] = "hana"
        val source = FakeSource(ready())
        show(source)
        readyFor(A, "hana")
        assertEquals("준비는 이 identity 로 한 번", listOf(A), source.prepares)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        save()
        assertEquals("hana", source.adds.single().bank)
    }

    /** While the read is open the Add area is not drawn, and the read finishing opens nothing by itself. */
    @Test fun S7E5B4B_K02_theAddAreaWaitsForTheRead_andNothingReplays() {
        store.values["A"] = "hana"
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val source = FakeSource(ready())
        show(source)
        assertEquals("Loading 중 추가 버튼이 그려졌다", 0, count(BankAlertSectionTags.ADD_BUTTON))
        gate.complete(Unit)
        compose.waitUntil(5_000) { count(BankAlertSectionTags.ADD_BUTTON) == 1 }
        assertTrue("준비 완료가 sheet 를 열었다", !sheetOpen())
    }

    /** A failed read lets Add open on KB; the next session reads again. */
    @Test fun S7E5B4B_K03_aFailedReadOpensOnKb_andTheNextSessionReadsAgain() {
        store.values["A"] = "hana"
        store.failNext = true
        val source = FakeSource(ready())
        show(source)
        settled { this is BankSelectionSnapshot.Failed && identity == A }
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("1400.00", textOf(AlertAddSheetTags.THRESHOLD))
        save()
        assertEquals("kb", source.adds.single().bank)
        val before = store.reads.get()
        update { tab.value = FreeTab.JPY }
        update { tab.value = FreeTab.USD }
        assertTrue("다음 session 에서 다시 읽지 않았다", store.reads.get() > before)
    }

    /** A value written for another UID is nobody's for this one: KB. */
    @Test fun S7E5B4B_K04_anotherUidsValueOpensOnKb() {
        store.values["B"] = "hana"
        val source = FakeSource(ready())
        show(source)
        readyFor(A, null)
        tap(BankAlertSectionTags.ADD_BUTTON)
        save()
        assertEquals("kb", source.adds.single().bank)
    }

    /** A success in one currency's session is the next Add's first bank in another currency's session. */
    @Test fun S7E5B4B_K05_aSuccessReachesTheNextAddInAnyCurrency() {
        val source = FakeSource(ready())
        show(source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        save()
        succeed(source, "woori")
        assertTrue(!sheetOpen())
        update { tab.value = FreeTab.JPY }
        tap(BankAlertSectionTags.ADD_BUTTON)
        save()
        assertEquals("woori", source.adds.last().bank)
    }

    /** A success that arrives after the sheet was dismissed still reaches the next Add. */
    @Test fun S7E5B4B_K06_aSuccessAfterDismissStillCounts() {
        val source = FakeSource(ready())
        show(source)
        tap(BankAlertSectionTags.ADD_BUTTON)
        save()
        tap(AlertAddSheetTags.CANCEL)
        assertTrue(!sheetOpen())
        succeed(source, "woori")
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("1450.00", textOf(AlertAddSheetTags.THRESHOLD))
    }

    /** An Edit opens on its row, never on the remembered bank, and sends only the change. */
    @Test fun S7E5B4B_K07_anEditNeverUsesTheRememberedBank() {
        store.values["A"] = "hana"
        val source = FakeSource(ready(row(81, bank = "kb")))
        show(source)
        readyFor(A, "hana")
        tap(BankAlertSectionTags.rowOpen(81))
        assertEquals("1381.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.CONDITION)
        save()
        assertEquals(listOf(81 to BankAlertEdit(condition = AlertCondition.ABOVE)), source.edits)
        assertEquals(emptyList<BankAlertDraft>(), source.adds)
        runBlocking { assertEquals("Edit 이 기억을 바꿨다", "hana", store.values["A"]) }
    }

    /** A remembered code that is one of the choices but not the default reaches the draft as it is, with its own quote (G06). */
    @Test fun S7E5B4B_K08_aRememberedScIsTheFirstBankWithItsQuote() {
        store.values["A"] = "sc"
        val source = FakeSource(ready())
        show(source)
        readyFor(A, "sc")
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("1480.00", textOf(AlertAddSheetTags.THRESHOLD))
        save()
        assertEquals("sc", source.adds.single().bank)
        assertEquals(1480.0, source.adds.single().threshold, 0.0)
    }

    /** A success accepted while an Add is open leaves that open input alone; only the next entry opens on it (G07). */
    @Test fun S7E5B4B_K09_anOpenAddKeepsItsInput_andOnlyTheNextEntryTakesTheNewBank() {
        store.values["A"] = "hana"
        val source = FakeSource(ready())
        show(source)
        readyFor(A, "hana")
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        update { memory.acceptAddedBank(A, "woori") }
        assertEquals("열린 입력이 바뀌었다", "1500.00", textOf(AlertAddSheetTags.THRESHOLD))
        tap(AlertAddSheetTags.CANCEL)
        tap(BankAlertSectionTags.ADD_BUTTON)
        assertEquals("새 entry 가 새 은행으로 열리지 않았다", "1450.00", textOf(AlertAddSheetTags.THRESHOLD))
    }

    /**
     * A click taken while this identity's selection was settled but delivered after it became None, Loading, or another full
     * identity's Ready or Failed — the same UID's next auth generation included — installs nothing; settling again installs
     * nothing by itself; a new click opens on the current seed. The device is granted, so the continuation runs at once.
     */
    @Test fun S7E5B4B_K10_aClickDeliveredAfterTheSelectionMovedInstallsNothing() {
        val a2 = AuthIdentityFence("A", 2L)
        val b = AuthIdentityFence("B", 1L)
        val moved = listOf(
            BankSelectionSnapshot.None,
            BankSelectionSnapshot.Loading(A, 50),
            BankSelectionSnapshot.Ready(b, 51, "woori"),
            BankSelectionSnapshot.Failed(b, 52),
            BankSelectionSnapshot.Ready(a2, 53, "woori"),
            BankSelectionSnapshot.Failed(a2, 54)
        )
        val selection = MutableStateFlow<BankSelectionSnapshot>(BankSelectionSnapshot.Ready(A, 1, "hana"))
        val source = FakeSource(ready(), selection)
        show(source)
        var revision = 100L
        for (state in moved) {
            update { selection.value = BankSelectionSnapshot.Ready(A, revision++, "hana") }
            val click = heldClick(BankAlertSectionTags.ADD_BUTTON)
            update { selection.value = state }
            update(click)
            assertTrue("$state: 늦게 닿은 click 이 설치했다", !sheetOpen())
            update { selection.value = BankSelectionSnapshot.Ready(A, revision++, "woori") }
            assertTrue("$state: 준비 완료가 저절로 설치했다", !sheetOpen())
            tap(BankAlertSectionTags.ADD_BUTTON)
            assertEquals("$state: 새 click 이 현재 seed 로 열리지 않았다", "1450.00", textOf(AlertAddSheetTags.THRESHOLD))
            tap(AlertAddSheetTags.CANCEL)
            assertTrue("$state", !sheetOpen())
        }
        assertEquals("추가가 보내졌다", emptyList<BankAlertDraft>(), source.adds)
    }

    private companion object {
        val A = AuthIdentityFence("A", 1L)
        fun row(id: Int, currency: String = "usd-krw", bank: String = "kb") = AlertSetting(
            id = id, userId = "A", bank = bank, currency = currency, condition = AlertCondition.BELOW, threshold = 1300.0 + id,
            isEnabled = true, triggered = false, createdAt = Instant.fromEpochSeconds(1_000L + id), updatedAt = Instant.fromEpochSeconds(0),
            triggeredAt = null, repeatIntervalSec = null
        )
    }
}
