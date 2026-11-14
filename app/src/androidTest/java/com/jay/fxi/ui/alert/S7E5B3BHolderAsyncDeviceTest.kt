package com.jay.fxi.ui.alert

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
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
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.readNotificationCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b3b (s7e5b3b_gaphunt_plan.codex.r1·r2, errata r2): the real holder with the real helper on the dedicated API 35 AVD, where the
 * first save asks the system and the continuation comes back later. While the system dialog is up nothing is saving and nothing is
 * sent; what changes meanwhile (the input, a second save, the tab, the editor, the source, a price) is injected through the host
 * state or a node's semantics action on the main thread, never by touching through the dialog.
 *
 * Runs only with `fxiPermissionAvd=api35` (S5_API_35); elsewhere every case is skipped. Each case needs the first-request state, so
 * each runs as its own instrumentation run on a fresh install (the runner installs and uninstalls around one case). The helper's
 * own live identity, premium and admission stay A, confirmed and open throughout, so a refusal here is the holder's.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B3BHolderAsyncDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val avd: String? = InstrumentationRegistry.getArguments().getString("fxiPermissionAvd")

    private inner class Rig {
        val premium = MutableStateFlow(OwnedPremiumAccess(A.uid, A.authGeneration, PremiumAccessState.PremiumConfirmed))
        val displayed = MutableStateFlow<NotificationCapability?>(NotificationCapability.RUNTIME_MISSING)
        val results = mutableListOf<AuthIdentityFence?>()
        private fun answer(owner: AuthIdentityFence?) = owner == A && readNotificationCapability(context) == NotificationCapability.GRANTED
        val env = NotificationPermissionEnvironment(
            admitted = { true },
            liveIdentity = { A },
            premium = premium,
            displayed = displayed,
            onRequestResult = { results += it; answer(it) },
            onAlreadyGranted = { answer(it) }
        )
    }

    private class FakeSource(state: BankAlertSettingsState) : BankAlertSectionSource {
        override val settings = MutableStateFlow(state)
        override val manualRefresh = MutableStateFlow(ManualRefreshState())
        val adds = mutableListOf<BankAlertDraft>()
        val edits = mutableListOf<Pair<Int, BankAlertEdit>>()
        val handles = mutableListOf<BankAlertSaveRequest>()
        var ensures = 0
        var authoritatives = 0
        var manuals = 0
        override fun ensure() { ensures += 1 }
        override fun authoritative() { authoritatives += 1 }
        override fun manual() { manuals += 1 }
        override fun setEnabled(id: Int, enabled: Boolean) = Unit
        override fun delete(id: Int) = Unit
        override fun add(draft: BankAlertDraft) = BankAlertSaveRequest().also { adds += draft; handles += it }
        override fun edit(id: Int, edit: BankAlertEdit) = BankAlertSaveRequest().also { edits += id to edit; handles += it }
        fun calls() = listOf(ensures, authoritatives, manuals)
    }

    private val tab = mutableStateOf(FreeTab.USD)
    private val quotes = mutableStateOf(mapOf("kb" to 1400.0, "hana" to 1500.0))

    private fun factory(source: FakeSource) = object : BankAlertSectionSourceFactory {
        override fun create(context: Context): BankAlertSectionSource = source
    }

    private fun mount(rig: Rig, current: androidx.compose.runtime.MutableState<FakeSource>) {
        compose.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides rig.env) {
                BankAlertSection(tab = tab.value, alertQuotes = quotes.value, sourceFactory = factory(current.value))
            }
        }
        compose.waitForIdle()
    }

    @Before fun selectAvd() {
        assumeTrue("dedicated AVD only (fxiPermissionAvd=api35)", avd == "api35")
        assertEquals("the AVD argument matches the device", 35, Build.VERSION.SDK_INT)
        assertTrue("a previous case left the AVD contaminated (marker on /sdcard); restore the initial snapshot",
            shell("ls /sdcard/").lines().none { it.trim() == CONTAMINATED })
        assertEquals("fixed locale", "en-US", context.resources.configuration.locales[0].toLanguageTag())
        assertEquals("precondition: a fresh install, first request", NotificationCapability.RUNTIME_MISSING, readNotificationCapability(context))
    }

    @After fun backHome() {
        if (avd == "api35") returnToApp()
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }

    private fun dump(label: String) { shell("uiautomator dump /sdcard/e5b3b_$label.xml") }

    private fun <T> need(label: String, value: T?): T {
        if (value == null) dump(label)
        assertNotNull("$label (UI dump /sdcard/e5b3b_$label.xml)", value)
        return value!!
    }

    private fun returnToApp() {
        repeat(4) {
            if (device.currentPackageName == context.packageName) return
            device.pressBack()
            device.waitForIdle()
        }
    }

    private fun systemButton(name: String): UiObject2? =
        device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*permissioncontroller:id/$name"))), TIMEOUT)

    private fun waitUntil(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < end) {
            compose.waitForIdle()
            if (condition()) return
            SystemClock.sleep(100)
        }
        dump(label)
        assertTrue("$label (UI dump /sdcard/e5b3b_$label.xml)", condition())
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun texts(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun textOf(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
        (n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
    }
    private fun sheetOpen() = count(AlertAddSheetTags.SAVE) == 1

    /**
     * While the system dialog is in front the test finds no compose root of this app, so nothing is read or fetched then. What a case
     * changes meanwhile is fetched before the save (a node's own action) or is host state, and is called on the main thread.
     */
    private fun heldClick(tag: String): () -> Unit {
        val action = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        return { action() }
    }

    private fun heldSetText(tag: String): (String) -> Unit {
        val action = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        return { action(AnnotatedString(it)) }
    }

    private fun onMain(block: () -> Unit) { compose.runOnUiThread(block); compose.waitForIdle() }

    /** Row 81's editor (no helper) with a real change: condition below → above. Returns the condition text before the change. */
    private fun openEditAndChange(): String {
        tap(BankAlertSectionTags.rowOpen(81))
        val before = textOf(AlertAddSheetTags.CONDITION)
        tap(AlertAddSheetTags.CONDITION)
        return before
    }

    /** Saves and waits for the system's own dialog. Saving is the request's pending state, so "no request" is "not saving". */
    private fun saveUntilTheSystemAsks(rig: Rig, source: FakeSource): UiObject2 {
        tap(AlertAddSheetTags.SAVE)
        val allow = need("allow", systemButton("permission_allow_button"))
        assertEquals("while the system asks there is no request, so nothing is saving or sent", 0, source.handles.size)
        assertEquals("and nothing has been decided yet", emptyList<AuthIdentityFence?>(), rig.results)
        return allow
    }

    private fun allowAndWait(rig: Rig, allow: UiObject2) {
        allow.click()
        waitUntil("allow-result") { rig.results.isNotEmpty() }
        returnToApp()
        compose.waitForIdle()
        assertEquals("the helper got its answer for A", listOf<AuthIdentityFence?>(A), rig.results)
        assertEquals(NotificationCapability.GRANTED, readNotificationCapability(context))
    }

    private val conditionEdit = BankAlertEdit(condition = AlertCondition.ABOVE)

    // ---- cases -------------------------------------------------------------------------------------------------

    @Test fun S7E5B3B_A0_aSaveWaitsForTheSystemAndThenSendsOnce() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        mount(rig, mutableStateOf(source))
        openEditAndChange()
        val allow = saveUntilTheSystemAsks(rig, source)
        allowAndWait(rig, allow)
        waitUntil("a0-sent") { source.edits.isNotEmpty() }
        assertEquals(listOf(81 to conditionEdit), source.edits)
        assertEquals("then it is saving", 1, count(AlertAddSheetTags.SAVING))
    }

    @Test fun S7E5B3B_A1_theWaitingSaveCarriesTheInputOfItsClick() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        mount(rig, mutableStateOf(source))
        openEditAndChange()
        val setThreshold = heldSetText(AlertAddSheetTags.THRESHOLD)
        val allow = saveUntilTheSystemAsks(rig, source)
        onMain { setThreshold("1450") }
        allowAndWait(rig, allow)
        waitUntil("a1-sent") { source.edits.isNotEmpty() }
        assertEquals("the clicked input, not the later one", listOf(81 to conditionEdit), source.edits)
        assertEquals("premise, read afterwards: the input did change while waiting", "1450", textOf(AlertAddSheetTags.THRESHOLD))
    }

    @Test fun S7E5B3B_A1b_aSecondSaveWhileWaitingChangesNothing() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        mount(rig, mutableStateOf(source))
        val below = openEditAndChange()
        val setThreshold = heldSetText(AlertAddSheetTags.THRESHOLD)
        val condition = heldClick(AlertAddSheetTags.CONDITION)
        val save = heldClick(AlertAddSheetTags.SAVE)
        val allow = saveUntilTheSystemAsks(rig, source)
        // B: another threshold and the condition back to below, then a second save while the first still waits.
        onMain { setThreshold("1450") }
        onMain { condition() }
        onMain { save() }
        assertEquals("the second save sent nothing while waiting", 0, source.handles.size)
        allowAndWait(rig, allow)
        waitUntil("a1b-sent") { source.edits.isNotEmpty() }
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertEquals("only the first click's input, once", listOf(81 to conditionEdit), source.edits)
        assertEquals("premise, read afterwards: B did land", listOf("1450", below),
            listOf(textOf(AlertAddSheetTags.THRESHOLD), textOf(AlertAddSheetTags.CONDITION)))
    }

    @Test fun S7E5B3B_A2_aDenySendsNothingAndNothingReplays() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        mount(rig, mutableStateOf(source))
        openEditAndChange()
        saveUntilTheSystemAsks(rig, source)
        need("deny", systemButton("permission_deny_button")).click()
        waitUntil("denied") { count(NotificationPermissionTags.DENIED_DIALOG) == 1 }
        tap(NotificationPermissionTags.DENIED_CANCEL)
        assertEquals("nothing sent, the draft stays, nothing saving", listOf(0, true, 0, 0, "알림 수정"),
            listOf(source.edits.size, sheetOpen(), count(AlertAddSheetTags.SAVING), count(AlertAddSheetTags.ERROR_DIALOG), textOf(AlertAddSheetTags.TITLE)))
        onMain { source.settings.value = ready(row(81), row(82)); quotes.value = mapOf("kb" to 1401.0, "hana" to 1501.0) }
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertEquals("a later change replays nothing", 0, source.edits.size)
        tap(AlertAddSheetTags.SAVE)
        waitUntil("explain") { count(NotificationPermissionTags.EXPLAIN_DIALOG) == 1 }
        assertEquals("a new click reaches the helper and still sends nothing", 0, source.edits.size)
        tap(NotificationPermissionTags.EXPLAIN_CANCEL)
    }

    @Test fun S7E5B3B_A3_anotherTabWhileWaitingEndsTheSave() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        mount(rig, mutableStateOf(source))
        openEditAndChange()
        val allow = saveUntilTheSystemAsks(rig, source)
        onMain { tab.value = FreeTab.JPY }
        allowAndWait(rig, allow)
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertEquals("nothing sent from the old session, no editor", listOf(0, 0, false), listOf(source.edits.size, source.adds.size, sheetOpen()))
    }

    @Test fun S7E5B3B_A4_aNewEditorWhileWaitingIsLeftAlone() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        mount(rig, mutableStateOf(source))
        val below = openEditAndChange()
        val cancel = heldClick(AlertAddSheetTags.CANCEL)
        val reopen = heldClick(BankAlertSectionTags.rowOpen(81))
        val allow = saveUntilTheSystemAsks(rig, source)
        onMain { cancel() }
        onMain { reopen() }
        allowAndWait(rig, allow)
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertEquals("the first editor's save is dropped", 0, source.edits.size)
        assertEquals("the second editor is open and untouched", listOf(true, "알림 수정", below, 0),
            listOf(sheetOpen(), textOf(AlertAddSheetTags.TITLE), textOf(AlertAddSheetTags.CONDITION), count(AlertAddSheetTags.SAVING)))
    }

    @Test fun S7E5B3B_A5_thePromptAddsAfterTheSystemAllows() {
        val rig = Rig()
        val source = FakeSource(ready())
        mount(rig, mutableStateOf(source))
        tap(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)
        val allow = need("allow", systemButton("permission_allow_button"))
        allowAndWait(rig, allow)
        waitUntil("a5-open") { sheetOpen() }
        assertEquals("알림 추가", textOf(AlertAddSheetTags.TITLE))
        assertEquals(emptyList<BankAlertDraft>(), source.adds)
    }

    @Test fun S7E5B3B_A5b_anotherTabWhileThePromptWaitsOpensNothing() {
        val rig = Rig()
        val source = FakeSource(ready())
        mount(rig, mutableStateOf(source))
        tap(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)
        val allow = need("allow", systemButton("permission_allow_button"))
        onMain { tab.value = FreeTab.JPY }
        allowAndWait(rig, allow)
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertTrue("the old session opens no editor", !sheetOpen())
    }

    @Test fun S7E5B3B_A6_aPriceThatArrivesWhileWaitingIsCheckedAtTheEnd() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        mount(rig, mutableStateOf(source))
        openEditAndChange()
        val draft = listOf(AlertAddSheetTags.HEADER_BANK, AlertAddSheetTags.THRESHOLD, AlertAddSheetTags.CONDITION, AlertAddSheetTags.REPEAT_LABEL).map(::textOf)
        val allow = saveUntilTheSystemAsks(rig, source)
        onMain { quotes.value = mapOf("kb" to 4000.0, "hana" to 1500.0) }
        allowAndWait(rig, allow)
        waitUntil("a6-copy") { texts(BankAlertEditorCopy.NOT_SENT) == 1 }
        assertEquals("nothing sent", 0, source.edits.size)
        assertEquals("every field of the draft stays (bank, threshold, condition, repeat)", draft,
            listOf(AlertAddSheetTags.HEADER_BANK, AlertAddSheetTags.THRESHOLD, AlertAddSheetTags.CONDITION, AlertAddSheetTags.REPEAT_LABEL).map(::textOf))
        assertEquals("premise, read afterwards: the new price puts the input out of range", 1, count(AlertAddSheetTags.OUT_OF_RANGE))
    }

    @Test fun S7E5B3B_A7_anotherSourceWhileWaitingEndsTheSave() {
        val rig = Rig()
        val first = FakeSource(ready(row(81)))
        val second = FakeSource(ready(row(81)))
        val current = mutableStateOf(first)
        mount(rig, current)
        openEditAndChange()
        val allow = saveUntilTheSystemAsks(rig, first)
        onMain { current.value = second }
        allowAndWait(rig, allow)
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertEquals("neither source got the save", listOf(0, 0, 0, 0), listOf(first.edits.size, second.edits.size, first.adds.size, second.adds.size))
        assertTrue("and no editor came back", !sheetOpen())
    }

    @Test fun S7E5B3B_A8_comingBackFromSettingsReplaysNothing() {
        val rig = Rig()
        val source = FakeSource(ready(row(81)))
        mount(rig, mutableStateOf(source))
        val base = source.calls()
        openEditAndChange()
        saveUntilTheSystemAsks(rig, source)
        need("deny", systemButton("permission_deny_button")).click()
        waitUntil("denied") { count(NotificationPermissionTags.DENIED_DIALOG) == 1 }
        tap(NotificationPermissionTags.DENIED_SETTINGS)
        need("settings", if (device.wait(Until.hasObject(By.pkg(SETTINGS).depth(0)), TIMEOUT)) true else null)
        returnToApp()
        waitUntil("back") { device.currentPackageName == context.packageName && count(NotificationPermissionTags.DENIED_DIALOG) == 0 }
        assertEquals("back from settings: nothing sent, the draft stays, nothing saving", listOf(0, true, 0),
            listOf(source.edits.size, sheetOpen(), count(AlertAddSheetTags.SAVING)))
        onMain { source.settings.value = ready(row(81), row(82)) }
        onMain { quotes.value = mapOf("kb" to 1402.0, "hana" to 1502.0) }
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertEquals("a new list or price after coming back replays nothing", 0, source.edits.size)
        assertEquals("and asks the source for nothing", base, source.calls())
        tap(AlertAddSheetTags.SAVE)
        waitUntil("explain-after-settings") { count(NotificationPermissionTags.EXPLAIN_DIALOG) == 1 }
        assertEquals("only a new click reaches the helper; nothing sent yet", 0, source.edits.size)
        // The new click goes on to the system and is allowed: exactly that one save is sent, once.
        tap(NotificationPermissionTags.EXPLAIN_CONTINUE)
        need("allow-after-settings", systemButton("permission_allow_button")).click()
        waitUntil("a8-second-result") { rig.results.size == 2 }
        returnToApp()
        compose.waitForIdle()
        assertEquals("the deny, then this allow, both for A", listOf<AuthIdentityFence?>(A, A), rig.results)
        assertEquals(NotificationCapability.GRANTED, readNotificationCapability(context))
        waitUntil("a8-sent") { source.edits.isNotEmpty() }
        SystemClock.sleep(500)
        compose.waitForIdle()
        assertEquals("the new click's save, exactly once", listOf(81 to conditionEdit), source.edits)
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
        const val TIMEOUT = 10_000L
        const val CONTAMINATED = "e5b2b1_contaminated"
        val A = AuthIdentityFence("A", 1L)

        fun ready(vararg rows: AlertSetting) = BankAlertSettingsState(AlertLoadPhase.Ready, rows.toList(), A)
        fun row(id: Int, currency: String = "usd-krw", threshold: Double = 1300.0 + id) = AlertSetting(
            id = id, userId = "A", bank = "kb", currency = currency, condition = AlertCondition.BELOW, threshold = threshold,
            isEnabled = true, triggered = false, createdAt = Instant.fromEpochSeconds(1_000L + id), updatedAt = Instant.fromEpochSeconds(0),
            triggeredAt = null, repeatIntervalSec = null
        )
    }
}
