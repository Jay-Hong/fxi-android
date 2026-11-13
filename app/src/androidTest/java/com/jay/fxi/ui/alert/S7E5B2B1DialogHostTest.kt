package com.jay.fxi.ui.alert

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.service.FXiMessagingService
import com.jay.fxi.service.NotificationCapability
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S7 E5b2b1 (gap hunt G05·G12): the app's own dialogs drawn by the real host over a fake OS port, with the agreed text
 * and the choices each capability allows; and the real settings intents and launch-outcome mapping.
 */
@RunWith(AndroidJUnit4::class)
class S7E5B2B1DialogHostTest {

    @get:Rule val compose = createComposeRule()

    private class Os : NotificationPermissionOs {
        var capability = NotificationCapability.RUNTIME_MISSING
        var rationale = false
        val log = mutableListOf<String>()
        override fun read(): NotificationCapability { log += "read"; return capability }
        override fun shouldShowRationale(): Boolean { log += "rationale"; return rationale }
        override fun launchRequest(): NotificationLaunchOutcome { log += "request"; return NotificationLaunchOutcome.ACCEPTED }
        override fun launchSettings(target: NotificationSettingsTarget): NotificationLaunchOutcome { log += "settings:$target"; return NotificationLaunchOutcome.ACCEPTED }
        override fun markRequested() { log += "mark" }
    }

    private class Rig {
        val os = Os()
        var granted = 0
        val env = NotificationPermissionEnvironment(
            admitted = { true },
            liveIdentity = { A },
            premium = MutableStateFlow(OwnedPremiumAccess(A.uid, A.authGeneration, PremiumAccessState.PremiumConfirmed)),
            displayed = MutableStateFlow(NotificationCapability.RUNTIME_MISSING),
            onRequestResult = { false },
            onAlreadyGranted = { false }
        )
        val lane = NotificationPermissionLane(orphanResult = {})
        val action = NotificationPermissionAction(env, os, lane)
    }

    private fun host(r: Rig) {
        compose.setContent { NotificationPermissionDialogHost(r.action) }
        compose.waitForIdle()
    }

    /**
     * UiAutomator's back on a drawn dialog (Espresso's waits for the base window's focus, which a shown dialog holds),
     * then the dismissal it causes.
     */
    private fun backOn(r: Rig, dialogTag: String) {
        compose.waitUntil(5_000) { exists(dialogTag) }
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(5_000) { r.action.dialog == null }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun denied(r: Rig, fresh: NotificationCapability) {
        compose.runOnIdle { r.action.run { r.granted += 1 } }
        r.os.capability = fresh
        compose.runOnIdle { r.lane.onRequestResult() }
        assertEquals(NotificationPermissionDialog.Denied(fresh), r.action.dialog)
    }

    @Test fun S7E5B2B1_G05a_theExplanationShowsTheAgreedTextAndContinueRequests() {
        val r = Rig().apply { os.rationale = true }
        host(r)
        compose.runOnIdle { r.action.run() }
        compose.onNodeWithText("환율 알림 권한").assertIsDisplayed()
        compose.onNodeWithText("원하는 환율에 도달하면 푸시 알림으로 알려드립니다. 알림을 받으려면 알림 권한을 허용해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("계속").assertIsDisplayed()
        compose.onNodeWithText("취소").assertIsDisplayed()
        assertEquals("nothing asked before the consent", listOf("read", "rationale"), r.os.log)
        compose.onNodeWithTag(NotificationPermissionTags.EXPLAIN_CONTINUE).performClick()
        compose.waitForIdle()
        assertEquals(listOf("read", "rationale", "read", "request", "mark"), r.os.log)
        assertNull(r.action.dialog)
    }

    @Test fun S7E5B2B1_G05b_cancelAndBackOnTheExplanationDoNothing() {
        val r = Rig().apply { os.rationale = true }
        host(r)
        compose.runOnIdle { r.action.run() }
        compose.onNodeWithTag(NotificationPermissionTags.EXPLAIN_CANCEL).performClick()
        compose.waitForIdle()
        assertNull(r.action.dialog)
        compose.runOnIdle { r.action.run() }
        backOn(r, NotificationPermissionTags.EXPLAIN_DIALOG)
        assertNull(r.action.dialog)
        assertEquals(listOf("read", "rationale", "read", "rationale"), r.os.log)
    }

    @Test fun S7E5B2B1_G05c_aRuntimeRefusalOffersSettingsRetryAndCancelWithTheAddedSentence() {
        val r = Rig()
        host(r)
        denied(r, NotificationCapability.RUNTIME_MISSING)
        compose.onNodeWithText("알림 권한이 꺼져 있습니다").assertIsDisplayed()
        compose.onNodeWithText("알림을 받으려면 알림 권한을 허용해 주세요. 다시 요청해도 시스템 요청 창이 표시되지 않을 수 있습니다. 이 경우 설정에서 허용해 주세요.").assertIsDisplayed()
        for (label in listOf("설정 열기", "다시 요청", "취소")) compose.onNodeWithText(label).assertIsDisplayed()
        r.os.log.clear()
        compose.onNodeWithTag(NotificationPermissionTags.DENIED_SETTINGS).performClick()
        compose.waitForIdle()
        assertEquals("settings fit the fresh capability", listOf("read", "settings:APP"), r.os.log)
        assertEquals(0, r.granted)
    }

    @Test fun S7E5B2B1_G05d_retryRunsOneFreshRequestWithoutTheContinuation() {
        val r = Rig()
        host(r)
        denied(r, NotificationCapability.RUNTIME_MISSING)
        r.os.log.clear()
        compose.onNodeWithTag(NotificationPermissionTags.DENIED_RETRY).performClick()
        compose.waitForIdle()
        assertEquals(listOf("read", "rationale", "request", "mark"), r.os.log)
        assertNull(r.action.dialog)
        assertEquals(0, r.granted)
    }

    @Test fun S7E5B2B1_G05e_anAppRefusalOffersSettingsAndCancelOnly() {
        val r = Rig()
        host(r)
        denied(r, NotificationCapability.APP_DISABLED)
        compose.onNodeWithText("알림 권한이 꺼져 있습니다").assertIsDisplayed()
        compose.onNodeWithText("알림을 받으려면 알림 권한을 허용해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("설정 열기").assertIsDisplayed()
        compose.onNodeWithText("취소").assertIsDisplayed()
        assertEquals("no retry for an app block", false, exists(NotificationPermissionTags.DENIED_RETRY))
        r.os.log.clear()
        compose.onNodeWithTag(NotificationPermissionTags.DENIED_SETTINGS).performClick()
        compose.waitForIdle()
        assertEquals(listOf("read", "settings:APP"), r.os.log)
    }

    @Test fun S7E5B2B1_G05f_aChannelRefusalOpensTheChannelSettings() {
        val r = Rig()
        host(r)
        denied(r, NotificationCapability.CHANNEL_DISABLED)
        assertEquals(false, exists(NotificationPermissionTags.DENIED_RETRY))
        r.os.log.clear()
        compose.onNodeWithTag(NotificationPermissionTags.DENIED_SETTINGS).performClick()
        compose.waitForIdle()
        assertEquals(listOf("read", "settings:CHANNEL"), r.os.log)
    }

    @Test fun S7E5B2B1_G05g_cancelAndBackOnTheRefusalDoNothing() {
        val r = Rig()
        host(r)
        denied(r, NotificationCapability.RUNTIME_MISSING)
        r.os.log.clear()
        compose.onNodeWithTag(NotificationPermissionTags.DENIED_CANCEL).performClick()
        compose.waitForIdle()
        assertNull(r.action.dialog)
        denied(r, NotificationCapability.RUNTIME_MISSING)
        r.os.log.clear()
        backOn(r, NotificationPermissionTags.DENIED_DIALOG)
        assertNull(r.action.dialog)
        assertEquals(emptyList<String>(), r.os.log)
        assertEquals(0, r.granted)
    }

    @Test fun S7E5B2B1_G12a_theSettingsIntentsCarryTheAgreedActionsAndExtras() {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val app = notificationSettingsIntent(NotificationSettingsTarget.APP, pkg)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, app.action)
        assertEquals(pkg, app.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(setOf(Settings.EXTRA_APP_PACKAGE), app.extras!!.keySet())
        val channel = notificationSettingsIntent(NotificationSettingsTarget.CHANNEL, pkg)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
        assertEquals(pkg, channel.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(FXiMessagingService.CHANNEL_RATE_ALERTS, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(setOf(Settings.EXTRA_APP_PACKAGE, Settings.EXTRA_CHANNEL_ID), channel.extras!!.keySet())
        val details = notificationSettingsIntent(NotificationSettingsTarget.DETAILS, pkg)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details.action)
        assertEquals(Uri.fromParts("package", pkg, null), details.data)
        assertNull(details.extras)
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        for (intent in listOf(app, channel, details)) assertNotNull("resolves on this device: ${intent.action}", intent.resolveActivity(pm))
    }

    @Test fun S7E5B2B1_G12b_onlyAnUnhandledIntentIsUnavailableAndEveryOtherLaunchExceptionFails() {
        assertEquals(NotificationLaunchOutcome.ACCEPTED, notificationLaunchOutcome { })
        assertEquals(NotificationLaunchOutcome.UNAVAILABLE, notificationLaunchOutcome { throw ActivityNotFoundException("none") })
        assertEquals(NotificationLaunchOutcome.FAILED, notificationLaunchOutcome { throw SecurityException("denied") })
        assertEquals(NotificationLaunchOutcome.FAILED, notificationLaunchOutcome { throw IllegalStateException("unregistered") })
        assertEquals(NotificationLaunchOutcome.UNAVAILABLE, notificationLaunchOutcome { Intent("com.jay.fxi.none.NOTHING").let { throw ActivityNotFoundException(it.action) } })
    }

    private companion object {
        val A = AuthIdentityFence("user-a", 1L)
    }
}
