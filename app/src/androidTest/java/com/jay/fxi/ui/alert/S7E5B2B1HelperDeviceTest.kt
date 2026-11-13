package com.jay.fxi.ui.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.service.FXiMessagingService
import com.jay.fxi.service.NotificationCapability
import com.jay.fxi.service.readNotificationCapability
import kotlinx.coroutines.flow.MutableStateFlow
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
 * S7 E5b2b1 device evidence (s7e5b2_api_agreed.r1.md §3.4, s7e5b2b_start_agreed.r1.md D2·D6·D9): the real helper, real
 * OS reads, real permission dialog and real settings screens, under a test environment that only opens the gate and
 * supplies an owner with confirmed premium. That seam is not production's release gate (agreed §4-6).
 *
 * Runs only on the dedicated AVDs, named by the instrumentation argument `fxiPermissionAvd` (api32 = Pixel_2_API_32,
 * api35 = S5_API_35). Elsewhere every case is skipped by assumption so the regular emulator's settings never change.
 * On a selected AVD nothing is skipped: a selector, precondition or timeout failure fails the case and leaves a UI dump.
 * A case that cannot restore the app and channel switches marks the AVD contaminated and every later case fails until
 * the initial snapshot is restored (D9).
 */
@RunWith(AndroidJUnit4::class)
class S7E5B2B1HelperDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val avd: String? = InstrumentationRegistry.getArguments().getString("fxiPermissionAvd")

    private class Rig(private val context: Context) {
        val premium = MutableStateFlow(grantFor(A))
        val displayed = MutableStateFlow<NotificationCapability?>(null)
        val results = mutableListOf<AuthIdentityFence?>()
        val already = mutableListOf<AuthIdentityFence>()
        var granted = 0

        private fun answer(owner: AuthIdentityFence?) = owner == A && readNotificationCapability(context) == NotificationCapability.GRANTED

        val env = NotificationPermissionEnvironment(
            admitted = { true },
            liveIdentity = { A },
            premium = premium,
            displayed = displayed,
            onRequestResult = { results += it; answer(it) },
            onAlreadyGranted = { already += it; answer(it) }
        )
        lateinit var action: NotificationPermissionAction
    }

    private lateinit var rig: Rig

    private fun mount(): Rig {
        val r = Rig(context)
        compose.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides r.env) {
                r.action = rememberNotificationPermissionAction()
            }
        }
        compose.waitForIdle()
        rig = r
        return r
    }

    private fun run(r: Rig) {
        compose.runOnUiThread { r.action.run { r.granted += 1 } }
        compose.waitForIdle()
    }

    @Before fun selectAvd() {
        assumeTrue("dedicated AVD only (fxiPermissionAvd)", avd == "api32" || avd == "api35")
        assertTrue("the AVD argument matches the device: $avd / ${Build.VERSION.SDK_INT}",
            (avd == "api32" && Build.VERSION.SDK_INT == 32) || (avd == "api35" && Build.VERSION.SDK_INT == 35))
        assertTrue("a previous case left the AVD contaminated (marker on /sdcard); restore the initial snapshot",
            shell("ls /sdcard/").lines().none { it.trim() == CONTAMINATED })
        assertEquals("fixed locale (D9)", "en-US", context.resources.configuration.locales[0].toLanguageTag())
    }

    @After fun backHome() {
        if (avd == "api32" || avd == "api35") returnToApp()
    }

    // ---- shared device helpers -------------------------------------------------------------------------------

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }

    private fun dump(label: String) {
        shell("uiautomator dump /sdcard/e5b2b1_$label.xml")
    }

    private fun <T> need(label: String, value: T?): T {
        if (value == null) dump(label)
        assertNotNull("$label (UI dump /sdcard/e5b2b1_$label.xml)", value)
        return value!!
    }

    private fun returnToApp() {
        repeat(4) {
            if (device.currentPackageName == context.packageName) return
            device.pressBack()
            device.waitForIdle()
        }
    }

    private fun waitForPackage(pkg: String, label: String) {
        need(label, if (device.wait(Until.hasObject(By.pkg(pkg).depth(0)), TIMEOUT)) true else null)
    }

    private fun appLabel(): String = context.applicationInfo.loadLabel(context.packageManager).toString()

    private fun channel(): NotificationChannel? =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(FXiMessagingService.CHANNEL_RATE_ALERTS)

    /** The production channel (FXiApplication.kt:155-173), created here because the closed debug gate never creates it. */
    private fun ensureChannel() {
        if (channel() != null) return
        val created = NotificationChannel(FXiMessagingService.CHANNEL_RATE_ALERTS, "환율 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "환율 목표 도달 시 알림"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 200, 250)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(created)
    }

    private fun appEnabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    private fun channelBlocked() = channel()?.importance == NotificationManager.IMPORTANCE_NONE

    /** Opens a settings screen by intent from the shell, verifies its target, flips its main switch and goes back. */
    private fun flip(screen: String, extras: String, header: String, want: Boolean, label: String) {
        shell("am start -a $screen --es android.provider.extra.APP_PACKAGE ${context.packageName} $extras")
        waitForPackage(SETTINGS, "$label-open")
        need("$label-header", device.wait(Until.findObject(By.res(SETTINGS, "entity_header_title").text(header)), TIMEOUT))
        val bar = need("$label-bar", device.findObject(By.res(SETTINGS, "main_switch_bar")))
        val switch: UiObject2 = need("$label-switch", bar.findObject(By.res("android", "switch_widget")))
        if (switch.isChecked != want) {
            bar.click()
            need("$label-flipped", if (device.wait(Until.findObject(By.res("android", "switch_widget").checked(want)), TIMEOUT) != null) true else null)
        }
        returnToApp()
    }

    private fun setApp(on: Boolean) = flip("android.settings.APP_NOTIFICATION_SETTINGS", "", appLabel(), on, "app-$on")

    private fun setChannel(on: Boolean) = flip(
        "android.settings.CHANNEL_NOTIFICATION_SETTINGS", "--es android.provider.extra.CHANNEL_ID ${FXiMessagingService.CHANNEL_RATE_ALERTS}",
        "환율 알림", on, "channel-$on")

    /** Restores app ON and the channel not blocked, then proves it with the OS read; failure marks the AVD. */
    private fun restore() {
        try {
            if (!appEnabled()) setApp(true)
            if (channel() != null && channelBlocked()) setChannel(true)
            assertEquals("restored", NotificationCapability.GRANTED, readNotificationCapability(context))
            record("restored")
        } catch (e: Throwable) {
            // Outside the process, so a crash or process death cannot forget it; only a snapshot restore clears it.
            shell("touch /sdcard/$CONTAMINATED")
            throw e
        }
    }

    /** G15: what each case starts from, logged for the host run and asserted as the known clean state. */
    private fun record(label: String) {
        val ch = channel()
        android.util.Log.i("E5B2B1", "$label avd=$avd sdk=${Build.VERSION.SDK_INT} locale=${context.resources.configuration.locales[0].toLanguageTag()} " +
            "top=${device.currentPackageName} app=${appEnabled()} channel=${ch?.id}:${ch?.importance} read=${readNotificationCapability(context)}")
    }

    private fun preflight(label: String) {
        record("preflight $label")
        assertTrue("$label precondition: app on", appEnabled())
        assertTrue("$label precondition: channel absent or not blocked", !channelBlocked())
        assertEquals("$label precondition: granted", NotificationCapability.GRANTED, readNotificationCapability(context))
    }

    private fun waitUntil(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < end) {
            compose.waitForIdle()
            if (condition()) return
            SystemClock.sleep(100)
        }
        dump(label)
        assertTrue("$label (UI dump /sdcard/e5b2b1_$label.xml)", condition())
    }

    // ---- API 32 (Pixel_2_API_32) -------------------------------------------------------------------------------

    @Test fun S7E5B2B1_D32_01_grantedContinuesOnceAndOpensNothing() {
        assumeTrue(avd == "api32")
        preflight("D32_01")
        val r = mount()
        run(r)
        assertEquals(listOf(A), r.already)
        assertEquals(1, r.granted)
        assertEquals("nothing opened", context.packageName, device.currentPackageName)
    }

    @Test fun S7E5B2B1_D32_02_anAppBlockOpensTheAppsNotificationSettingsAndItsResultFreesTheLease() {
        assumeTrue(avd == "api32")
        preflight("D32_02")
        try {
            setApp(false)
            assertEquals(NotificationCapability.APP_DISABLED, readNotificationCapability(context))
            val r = mount()
            run(r)
            waitForPackage(SETTINGS, "app-launched")
            need("app-target", device.wait(Until.findObject(By.res(SETTINGS, "entity_header_title").text(appLabel())), TIMEOUT))
            returnToApp()
            waitUntil("app-lease-freed") { device.currentPackageName == context.packageName }
            run(r)
            waitForPackage(SETTINGS, "app-launched-again")
            returnToApp()
            assertEquals("settings paths never continue or ask the monitor", 0, r.granted)
            assertEquals(emptyList<AuthIdentityFence?>(), r.results)
            assertEquals(emptyList<AuthIdentityFence>(), r.already)
        } finally {
            restore()
        }
    }

    @Test fun S7E5B2B1_D32_03_aChannelBlockOpensTheRateAlertsChannelSettings() {
        assumeTrue(avd == "api32")
        ensureChannel()
        preflight("D32_03")
        try {
            setChannel(false)
            assertEquals(NotificationCapability.CHANNEL_DISABLED, readNotificationCapability(context))
            val r = mount()
            run(r)
            waitForPackage(SETTINGS, "channel-launched")
            need("channel-target", device.wait(Until.findObject(By.res(SETTINGS, "entity_header_title").text("환율 알림")), TIMEOUT))
            returnToApp()
            run(r)
            waitForPackage(SETTINGS, "channel-launched-again")
            returnToApp()
            assertEquals(0, r.granted)
        } finally {
            restore()
        }
    }

    @Test fun S7E5B2B1_D32_04_enablingInSettingsIsSeenByTheNextFreshRead() {
        assumeTrue(avd == "api32")
        ensureChannel()
        preflight("D32_04")
        try {
            setChannel(false)
            val r = mount()
            run(r)
            waitForPackage(SETTINGS, "return-launched")
            val bar = need("return-bar", device.findObject(By.res(SETTINGS, "main_switch_bar")))
            bar.click()
            need("return-on", device.wait(Until.findObject(By.res("android", "switch_widget").checked(true)), TIMEOUT))
            returnToApp()
            assertEquals("the fresh read after the return", NotificationCapability.GRANTED, readNotificationCapability(context))
            run(r)
            assertEquals("now granted: the action continues once", 1, r.granted)
        } finally {
            restore()
        }
    }

    @Test fun S7E5B2B1_D32_05_aSecondHelperIsBusyWhileTheSettingsScreenIsOpenUntilItsRealResult() {
        assumeTrue(avd == "api32")
        preflight("D32_05")
        try {
            setApp(false)
            val r = Rig(context)
            lateinit var second: NotificationPermissionAction
            compose.setContent {
                CompositionLocalProvider(LocalNotificationPermissionEnvironment provides r.env) {
                    r.action = rememberNotificationPermissionAction()
                    second = rememberNotificationPermissionAction()
                }
            }
            compose.waitForIdle()
            compose.runOnUiThread { r.action.run { r.granted += 1 } }
            waitForPackage(SETTINGS, "busy-settings")
            compose.runOnUiThread { second.run() }
            SystemClock.sleep(1_000)
            device.pressBack()
            waitUntil("busy-one-back") { device.currentPackageName == context.packageName }
            record("D32_05 one back returned: one settings screen, so the second helper was Busy")
            compose.runOnUiThread { second.run() }
            waitForPackage(SETTINGS, "busy-freed-by-result")
            returnToApp()
            assertEquals(0, r.granted)
        } finally {
            restore()
        }
    }

    // ---- API 35 (S5_API_35) ------------------------------------------------------------------------------------
    // Process death is not observed here: the instrumentation shares the app's process, so killing it ends the run.
    // For the same reason a granted permission is never revoked in-process (revoking kills the process). Each API 35
    // case runs as its own instrumentation run on a fresh install, which is the first-request state asserted below.

    private fun freshRuntime(record: Boolean = false) {
        context.getSharedPreferences("alert_prefs", Context.MODE_PRIVATE).edit().clear().apply {
            if (record) putBoolean("has_requested_notification_permission", true)
        }.commit()
        assertEquals("precondition: runtime missing", NotificationCapability.RUNTIME_MISSING, readNotificationCapability(context))
    }

    private fun systemButton(name: String): UiObject2? =
        device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*permissioncontroller:id/$name"))), TIMEOUT)

    private fun recorded() = context.getSharedPreferences("alert_prefs", Context.MODE_PRIVATE)
        .getBoolean("has_requested_notification_permission", false)

    private fun denied(r: Rig) = waitUntil("denied-dialog") { r.action.dialog is NotificationPermissionDialog.Denied }

    @Test fun S7E5B2B1_D35_01_aFirstRequestShowsTheSystemDialogAndAnAllowContinuesOnce() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val r = mount()
        run(r)
        need("allow", systemButton("permission_allow_button")).click()
        waitUntil("allow-result") { r.results.isNotEmpty() }
        assertEquals(listOf<AuthIdentityFence?>(A), r.results)
        assertEquals(1, r.granted)
        assertTrue("the accepted launch was recorded", recorded())
        assertEquals(NotificationCapability.GRANTED, readNotificationCapability(context))
    }

    @Test fun S7E5B2B1_D35_02_aDenyShowsTheGuidanceWithItsThreeChoicesAndNoSettings() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val r = mount()
        run(r)
        need("deny", systemButton("permission_deny_button")).click()
        denied(r)
        assertEquals(NotificationPermissionDialog.Denied(NotificationCapability.RUNTIME_MISSING), r.action.dialog)
        for (tag in listOf(NotificationPermissionTags.DENIED_SETTINGS, NotificationPermissionTags.DENIED_RETRY, NotificationPermissionTags.DENIED_CANCEL)) {
            compose.onNodeWithTag(tag).assertExists()
        }
        assertEquals("no settings opened by itself", context.packageName, device.currentPackageName)
        compose.onNodeWithTag(NotificationPermissionTags.DENIED_CANCEL).performClick()
        compose.waitForIdle()
        assertEquals(null, r.action.dialog)
        assertEquals(0, r.granted)
    }

    @Test fun S7E5B2B1_D35_03_afterOneDenyTheRationaleIsExplainedThenARequestWithoutADialogStillComesBackToTheGuidance() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val r = mount()
        run(r)
        need("first-deny", systemButton("permission_deny_button")).click()
        denied(r)
        compose.onNodeWithTag(NotificationPermissionTags.DENIED_CANCEL).performClick()
        compose.waitForIdle()
        run(r)
        waitUntil("explain") { r.action.dialog == NotificationPermissionDialog.Explain }
        compose.onNodeWithTag(NotificationPermissionTags.EXPLAIN_CONTINUE).performClick()
        need("second-deny", systemButton("permission_deny_and_dont_ask_again_button") ?: systemButton("permission_deny_button")).click()
        denied(r)
        val before = r.results.size
        compose.onNodeWithTag(NotificationPermissionTags.DENIED_RETRY).performClick()
        waitUntil("no-dialog-result") { r.results.size > before }
        assertEquals("the system answered without a dialog", null, device.findObject(By.res(java.util.regex.Pattern.compile(".*permissioncontroller:id/permission_allow_button"))))
        denied(r)
        assertEquals(0, r.granted)
    }

    @Test fun S7E5B2B1_D35_04_aDismissIsNoDecisionAndOpensNoSettings() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val r = mount()
        run(r)
        need("dismiss-dialog", systemButton("permission_allow_button"))
        device.pressBack()
        waitUntil("dismiss-result") { r.results.isNotEmpty() }
        denied(r)
        assertEquals(context.packageName, device.currentPackageName)
        assertEquals(0, r.granted)
    }

    @Test fun S7E5B2B1_D35_05_aRestoredRecordDoesNotStopTheRequest() {
        assumeTrue(avd == "api35")
        freshRuntime(record = true)
        val r = mount()
        run(r)
        need("restored-allow", systemButton("permission_allow_button")).click()
        waitUntil("restored-result") { r.results.isNotEmpty() }
        assertEquals(1, r.granted)
    }

    @Test fun S7E5B2B1_D35_06_aResultAfterARecreationIsAnOrphanThatOnlyRefreshes() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val r = mount()
        run(r)
        need("recreate-dialog", systemButton("permission_allow_button"))
        var old: ComponentActivity? = null
        compose.activityRule.scenario.onActivity { old = it }
        try {
            device.setOrientationLeft()
            var current: ComponentActivity? = null
            waitUntil("recreated") {
                compose.activityRule.scenario.onActivity { current = it }
                current != null && current !== old
            }
            val again = Rig(context)
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalNotificationPermissionEnvironment provides again.env) {
                        again.action = rememberNotificationPermissionAction()
                    }
                }
            }
            need("recreate-allow", systemButton("permission_allow_button")).click()
            waitUntil("orphan") { again.results.isNotEmpty() }
            assertEquals("an orphan asks for no owner", listOf<AuthIdentityFence?>(null), again.results)
            assertEquals("the old action's attempt never continues", emptyList<AuthIdentityFence?>(), r.results)
            assertEquals(0, r.granted + again.granted)
            assertEquals(null, again.action.dialog)
            compose.runOnUiThread { again.action.run { again.granted += 1 } }
            compose.waitForIdle()
            assertEquals("after the orphan the lane is free: a new explicit action continues", 1, again.granted)
            assertEquals(listOf(A), again.already)
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }

    @Test fun S7E5B2B1_D35_07_twoHelpersShareOneLeaseAndOnlyTheFirstRequests() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val first = Rig(context)
        lateinit var second: NotificationPermissionAction
        compose.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides first.env) {
                first.action = rememberNotificationPermissionAction()
                second = rememberNotificationPermissionAction()
            }
        }
        compose.waitForIdle()
        var secondGranted = 0
        compose.runOnUiThread {
            first.action.run { first.granted += 1 }
            second.run { secondGranted += 1 }
        }
        need("shared-allow", systemButton("permission_allow_button")).click()
        waitUntil("shared-result") { first.results.isNotEmpty() }
        assertEquals("one request, one result", listOf<AuthIdentityFence?>(A), first.results)
        assertEquals(1, first.granted)
        assertEquals("Busy did nothing", 0, secondGranted)
        compose.runOnUiThread { second.run { secondGranted += 1 } }
        compose.waitForIdle()
        assertEquals("free again: granted now continues", 1, secondGranted)
    }

    @Test fun S7E5B2B1_D35_08_aPremiumLossSeenDuringTheRequestNeverRevivesItsContinuation() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val r = mount()
        run(r)
        need("loss-dialog", systemButton("permission_allow_button"))
        compose.runOnUiThread { r.premium.value = grantFor(A, PremiumAccessState.FreeConfirmed) }
        compose.waitForIdle()
        compose.runOnUiThread { r.premium.value = grantFor(A) }
        compose.waitForIdle()
        need("loss-allow", systemButton("permission_allow_button")).click()
        waitUntil("loss-result") { r.results.isNotEmpty() }
        assertEquals(listOf<AuthIdentityFence?>(A), r.results)
        assertEquals("the publication reached the action: no revival", 0, r.granted)
        assertEquals(null, r.action.dialog)
        compose.runOnUiThread { r.action.run { r.granted += 10 } }
        compose.waitForIdle()
        assertEquals("a new action after the result continues", 10, r.granted)
    }

    @Test fun S7E5B2B1_D35_09_aHelperReplacedInTheSameFrameDuringARequestKeepsTheLaneUntilTheResult() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val r = Rig(context)
        var showFirst by mutableStateOf(true)
        lateinit var second: NotificationPermissionAction
        compose.setContent {
            CompositionLocalProvider(LocalNotificationPermissionEnvironment provides r.env) {
                if (showFirst) r.action = rememberNotificationPermissionAction() else second = rememberNotificationPermissionAction()
            }
        }
        compose.waitForIdle()
        run(r)
        need("swap-dialog", systemButton("permission_allow_button"))
        compose.runOnUiThread { showFirst = false }
        compose.waitForIdle()
        var secondGranted = 0
        compose.runOnUiThread { second.run { secondGranted += 1 } }
        compose.waitForIdle()
        assertEquals("the new helper is Busy: nothing asked", emptyList<AuthIdentityFence>(), r.already)
        need("swap-allow", systemButton("permission_allow_button")).click()
        waitUntil("swap-result") { r.results.isNotEmpty() }
        assertEquals(listOf<AuthIdentityFence?>(A), r.results)
        assertEquals("the departed helper's continuation never runs", 0, r.granted)
        compose.runOnUiThread { second.run { secondGranted += 1 } }
        compose.waitForIdle()
        assertEquals("the real result freed the lane", 1, secondGranted)
    }

    @Test fun S7E5B2B1_D35_10_twoActivitiesHoldIndependentLeases() {
        assumeTrue(avd == "api35")
        freshRuntime()
        val r = mount()
        run(r)
        need("two-first-deny", systemButton("permission_deny_button")).click()
        denied(r)
        val other = Rig(context)
        val first = compose.activity
        // A second, simultaneously alive Activity in its own task (ActivityScenario would clear the first one's task).
        val second = instrumentation.startActivitySync(
            android.content.Intent(context, ComponentActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        ) as ComponentActivity
        try {
            instrumentation.runOnMainSync {
                second.setContent {
                    CompositionLocalProvider(LocalNotificationPermissionEnvironment provides other.env) {
                        other.action = rememberNotificationPermissionAction()
                    }
                }
            }
            instrumentation.waitForIdleSync()
            assertTrue("premise: the first Activity is still alive", !first.isDestroyed && !first.isFinishing)
            assertEquals("premise: the first Activity's guidance holds its lease",
                NotificationPermissionDialog.Denied(NotificationCapability.RUNTIME_MISSING), r.action.dialog)
            instrumentation.runOnMainSync { other.action.run { other.granted += 1 } }
            waitUntil("two-second-explain") { other.action.dialog == NotificationPermissionDialog.Explain }
            assertEquals("the first Activity's guidance still holds its own lease",
                NotificationPermissionDialog.Denied(NotificationCapability.RUNTIME_MISSING), r.action.dialog)
            instrumentation.runOnMainSync { other.action.onDialogDismiss() }
        } finally {
            instrumentation.runOnMainSync { second.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
        const val TIMEOUT = 10_000L
        const val CONTAMINATED = "e5b2b1_contaminated"
        val A = AuthIdentityFence("user-a", 1L)

        fun grantFor(owner: AuthIdentityFence, state: PremiumAccessState = PremiumAccessState.PremiumConfirmed) =
            OwnedPremiumAccess(owner.uid, owner.authGeneration, state)
    }
}
