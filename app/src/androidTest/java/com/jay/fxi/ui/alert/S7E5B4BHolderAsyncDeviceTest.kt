package com.jay.fxi.ui.alert

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.BankAlertDraft
import com.jay.fxi.data.alerts.BankAlertEdit
import com.jay.fxi.data.alerts.BankAlertSaveRequest
import com.jay.fxi.data.alerts.BankAlertSettingsState
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.local.BankSelectionSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.readNotificationCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b4b (계획 r2 §2 기기, R6): the real holder and the real notification helper on the dedicated API 35 AVD, with the bank
 * selection set by the test. The empty list's notification prompt is drawn whatever the selection is, so it can be clicked
 * while this identity's selection is None, Loading, or another full identity's Ready or Failed — the same UID's next auth
 * generation included. Whether the permission was never asked or was denied once, allowing it then installs no editor; this
 * identity's selection settling afterwards installs none by itself; a new click opens on the current seed. Also: the selection
 * moving while the system asks, and the tab changing while it is being prepared.
 *
 * Runs only with `fxiPermissionAvd=api35` (S5_API_35); elsewhere every case is skipped. Each case needs the first-request state,
 * so each runs as its own instrumentation run on a fresh install, as for [S7E5B3BHolderAsyncDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class S7E5B4BHolderAsyncDeviceTest {

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

    /** The selection is the test's; prepare only records who asked. */
    private class FakeSource(state: BankAlertSettingsState, val selection: MutableStateFlow<BankSelectionSnapshot>) : BankAlertSectionSource {
        override val settings = MutableStateFlow(state)
        override val manualRefresh = MutableStateFlow(ManualRefreshState())
        val adds = mutableListOf<BankAlertDraft>()
        val prepares = mutableListOf<AuthIdentityFence>()
        override fun ensure() = Unit
        override fun authoritative() = Unit
        override fun manual() = Unit
        override fun setEnabled(id: Int, enabled: Boolean) = Unit
        override fun delete(id: Int) = Unit
        override fun add(draft: BankAlertDraft) = BankAlertSaveRequest().also { adds += draft }
        override fun edit(id: Int, edit: BankAlertEdit) = BankAlertSaveRequest()
        override val bankSelection: StateFlow<BankSelectionSnapshot> = selection
        override suspend fun prepareBankSelection(identity: AuthIdentityFence) { prepares += identity }
    }

    private val tab = mutableStateOf(FreeTab.USD)
    private val quotes = mapOf("kb" to 1400.0, "hana" to 1500.0, "woori" to 1450.0)

    private fun mount(rig: Rig, source: FakeSource) {
        compose.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides rig.env) {
                BankAlertSection(tab = tab.value, alertQuotes = quotes, sourceFactory = object : BankAlertSectionSourceFactory {
                    override fun create(context: Context): BankAlertSectionSource = source
                })
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

    private fun dump(label: String) { shell("uiautomator dump /sdcard/e5b4b_$label.xml") }

    private fun <T> need(label: String, value: T?): T {
        if (value == null) dump(label)
        assertNotNull("$label (UI dump /sdcard/e5b4b_$label.xml)", value)
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
            val ready = try {
                condition()
            } catch (e: IllegalStateException) {
                // A system dialog still over the app leaves no compose root to query for a while (device run 1, D06).
                if (e.message?.startsWith("No compose hierarchies found") == true) false else throw e
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        dump(label)
        assertTrue("$label (UI dump /sdcard/e5b4b_$label.xml)", condition())
    }

    /**
     * Clicks the system dialog's [name] button and waits for that button to go. A click the busy emulator did not apply leaves it
     * up; it is found again and clicked again, three clicks at most in all (device run 1, D06: `clickedButton=0`). The button
     * going is not the decision: the callers still assert the callback, its owner and the capability.
     */
    private fun clickSystem(label: String, name: String) {
        val selector = By.res(java.util.regex.Pattern.compile(".*permissioncontroller:id/$name"))
        for (clicks in 1..3) {
            need(if (clicks == 1) label else "$label-retry$clicks", systemButton(name)).click()
            if (device.wait(Until.gone(selector), TIMEOUT)) {
                Log.i(RIG_TAG, "$label: $name gone after $clicks click(s)")
                return
            }
        }
        dump("$label-stuck")
        fail("$label: $name is still up after 3 clicks (UI dump /sdcard/e5b4b_$label-stuck.xml)")
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    private fun textOf(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
        (n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }).orEmpty()
    }
    private fun sheetOpen() = count(AlertAddSheetTags.SAVE) == 1
    private fun onMain(block: () -> Unit) { compose.runOnUiThread(block); compose.waitForIdle() }

    /** A later look: an editor a stale continuation would have installed has had time to appear. */
    private fun settleAWhile() {
        SystemClock.sleep(500)
        compose.waitForIdle()
    }

    /** Clicks the prompt and waits for the system's own dialog; nothing has been decided yet. */
    private fun promptUntilTheSystemAsks(rig: Rig): UiObject2 {
        val decided = rig.results.size
        tap(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)
        val allow = need("allow", systemButton("permission_allow_button"))
        assertEquals("while the system asks nothing has been decided", decided, rig.results.size)
        return allow
    }

    private fun allowAndWait(rig: Rig, @Suppress("UNUSED_PARAMETER") allow: UiObject2) {
        val decided = rig.results.size
        clickSystem("allow-click", "permission_allow_button")
        waitUntil("allow-result") { rig.results.size == decided + 1 }
        returnToApp()
        compose.waitForIdle()
        assertEquals("the helper got its answer for A", A, rig.results.last())
        assertEquals(NotificationCapability.GRANTED, readNotificationCapability(context))
    }

    /** Asks once and is denied: the next request explains first. Nothing is installed. */
    private fun denyOnce(rig: Rig) {
        tap(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)
        clickSystem("deny", "permission_deny_button")
        waitUntil("denied") { count(NotificationPermissionTags.DENIED_DIALOG) == 1 }
        tap(NotificationPermissionTags.DENIED_CANCEL)
        assertTrue("a denied click installed an editor", !sheetOpen())
        assertEquals("the deny, for A", listOf<AuthIdentityFence?>(A), rig.results)
    }

    /** After a deny: the prompt explains, continues to the system, and waits for its dialog. */
    private fun promptAgainUntilTheSystemAsks(rig: Rig): UiObject2 {
        val decided = rig.results.size
        tap(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON)
        waitUntil("explain") { count(NotificationPermissionTags.EXPLAIN_DIALOG) == 1 }
        tap(NotificationPermissionTags.EXPLAIN_CONTINUE)
        val allow = need("allow-again", systemButton("permission_allow_button"))
        assertEquals("while the system asks nothing has been decided", decided, rig.results.size)
        return allow
    }

    /** This identity settles afterwards: no editor by itself; a new click opens on the seed it settled with. */
    private fun settlesThenANewClickOpensOnTheCurrentSeed(source: FakeSource, label: String) {
        assertTrue("$label: 권한 승인 뒤 설치했다", !sheetOpen())
        onMain { source.selection.value = BankSelectionSnapshot.Ready(A, 900, "woori") }
        settleAWhile()
        assertTrue("$label: 준비 완료가 저절로 설치했다", !sheetOpen())
        val entry = if (count(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON) == 1) BankAlertSectionTags.NOTIFY_PROMPT_BUTTON else BankAlertSectionTags.ADD_BUTTON
        tap(entry)
        waitUntil("$label-open") { sheetOpen() }
        assertEquals("$label: 새 click 이 현재 seed 로 열리지 않았다", "1450.00", textOf(AlertAddSheetTags.THRESHOLD))
        assertEquals("$label: 보낸 것이 있다", emptyList<BankAlertDraft>(), source.adds)
    }

    private fun neverAsked(state: BankSelectionSnapshot, label: String) {
        val rig = Rig()
        val source = FakeSource(ready(), MutableStateFlow(state))
        mount(rig, source)
        allowAndWait(rig, promptUntilTheSystemAsks(rig))
        settleAWhile()
        settlesThenANewClickOpensOnTheCurrentSeed(source, label)
    }

    private fun deniedOnce(state: BankSelectionSnapshot, label: String) {
        val rig = Rig()
        val source = FakeSource(ready(), MutableStateFlow(BankSelectionSnapshot.Ready(A, 1, "hana")))
        mount(rig, source)
        denyOnce(rig)
        onMain { source.selection.value = state }
        allowAndWait(rig, promptAgainUntilTheSystemAsks(rig))
        settleAWhile()
        settlesThenANewClickOpensOnTheCurrentSeed(source, label)
    }

    // ---- never asked: the prompt clicked while the selection is not this identity's settled one -----------------

    @Test fun S7E5B4B_P01_neverAsked_none() = neverAsked(BankSelectionSnapshot.None, "P01")
    @Test fun S7E5B4B_P02_neverAsked_loading() = neverAsked(BankSelectionSnapshot.Loading(A, 2), "P02")
    @Test fun S7E5B4B_P03_neverAsked_anotherUidReady() = neverAsked(BankSelectionSnapshot.Ready(B, 3, "woori"), "P03")
    @Test fun S7E5B4B_P04_neverAsked_anotherUidFailed() = neverAsked(BankSelectionSnapshot.Failed(B, 4), "P04")
    @Test fun S7E5B4B_P05_neverAsked_nextGenerationReady() = neverAsked(BankSelectionSnapshot.Ready(A2, 5, "woori"), "P05")
    @Test fun S7E5B4B_P06_neverAsked_nextGenerationFailed() = neverAsked(BankSelectionSnapshot.Failed(A2, 6), "P06")

    // ---- denied once, then allowed --------------------------------------------------------------------------

    @Test fun S7E5B4B_D01_deniedOnce_none() = deniedOnce(BankSelectionSnapshot.None, "D01")
    @Test fun S7E5B4B_D02_deniedOnce_loading() = deniedOnce(BankSelectionSnapshot.Loading(A, 2), "D02")
    @Test fun S7E5B4B_D03_deniedOnce_anotherUidReady() = deniedOnce(BankSelectionSnapshot.Ready(B, 3, "woori"), "D03")
    @Test fun S7E5B4B_D04_deniedOnce_anotherUidFailed() = deniedOnce(BankSelectionSnapshot.Failed(B, 4), "D04")
    @Test fun S7E5B4B_D05_deniedOnce_nextGenerationReady() = deniedOnce(BankSelectionSnapshot.Ready(A2, 5, "woori"), "D05")
    @Test fun S7E5B4B_D06_deniedOnce_nextGenerationFailed() = deniedOnce(BankSelectionSnapshot.Failed(A2, 6), "D06")

    // ---- the selection or the tab moving while the system asks ----------------------------------------------

    /** Settled when clicked, Loading again by the time it is allowed: nothing installed, then the usual. */
    @Test fun S7E5B4B_W01_theSelectionLeavesWhileTheSystemAsks() {
        val rig = Rig()
        val source = FakeSource(ready(), MutableStateFlow(BankSelectionSnapshot.Ready(A, 1, "hana")))
        mount(rig, source)
        val allow = promptUntilTheSystemAsks(rig)
        onMain { source.selection.value = BankSelectionSnapshot.Loading(A, 2) }
        allowAndWait(rig, allow)
        settleAWhile()
        settlesThenANewClickOpensOnTheCurrentSeed(source, "W01")
    }

    /** A newer Ready of this identity arrives while the system asks: the editor opens on the seed of the moment it installs. */
    @Test fun S7E5B4B_W02_aNewerReadyWhileTheSystemAsksIsTheSeed() {
        val rig = Rig()
        val source = FakeSource(ready(), MutableStateFlow(BankSelectionSnapshot.Ready(A, 1, "hana")))
        mount(rig, source)
        val allow = promptUntilTheSystemAsks(rig)
        onMain { source.selection.value = BankSelectionSnapshot.Ready(A, 2, "woori") }
        allowAndWait(rig, allow)
        waitUntil("w02-open") { sheetOpen() }
        assertEquals("설치 시점의 seed 가 아니다", "1450.00", textOf(AlertAddSheetTags.THRESHOLD))
    }

    /** The tab changes while the selection is being prepared and the system asks: the old session installs nothing. */
    @Test fun S7E5B4B_W03_anotherTabWhileItIsPreparedAndTheSystemAsks() {
        val rig = Rig()
        val source = FakeSource(ready(), MutableStateFlow(BankSelectionSnapshot.Loading(A, 1)))
        mount(rig, source)
        val allow = promptUntilTheSystemAsks(rig)
        onMain { tab.value = FreeTab.JPY }
        allowAndWait(rig, allow)
        settleAWhile()
        assertEquals("새 session 이 다시 준비하지 않았다", listOf(A, A), source.prepares)
        settlesThenANewClickOpensOnTheCurrentSeed(source, "W03")
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val RIG_TAG = "E5b4bRig"
        const val CONTAMINATED = "e5b2b1_contaminated"
        val A = AuthIdentityFence("A", 1L)
        val A2 = AuthIdentityFence("A", 2L)
        val B = AuthIdentityFence("B", 1L)

        fun ready() = BankAlertSettingsState(AlertLoadPhase.Ready, emptyList(), A)
    }
}
