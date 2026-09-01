package com.jay.fxi

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.messaging.RemoteMessage
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertEventBus
import com.jay.fxi.service.FXiMessagingService
import com.jay.fxi.ui.screen.RELEASE_UNAVAILABLE_TEST_TAG
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseAdmissionOffEntrypointTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun freshActivityRecreationAndWarmNotificationIntentRemainUnavailable() {
        assertFalse(BuildConfig.TOPIC_V2_RELEASE_ON)
        val coldIntent = alertIntent(settingId = "41")

        // ActivityScenario proves a fresh Activity instance, not a fresh app process.
        // The force-stop -> launcher process-cold leg remains host-side device evidence.
        ActivityScenario.launch<MainActivity>(coldIntent).use { scenario ->
            composeRule.onNodeWithTag(RELEASE_UNAVAILABLE_TEST_TAG).assertIsDisplayed()
            scenario.onActivity { activity ->
                assertNull(activity.pendingAlertEvent.value)
                assertEquals("rate_alert", coldIntent.getStringExtra("type"))
            }

            scenario.recreate()
            composeRule.onNodeWithTag(RELEASE_UNAVAILABLE_TEST_TAG).assertIsDisplayed()
            scenario.onActivity { activity ->
                assertNull(activity.pendingAlertEvent.value)

                val warmIntent = Intent(activity, MainActivity::class.java).apply {
                    putExtra(FXiMessagingService.EXTRA_NOTIFICATION_TYPE, "rate_alert")
                    putExtra(FXiMessagingService.EXTRA_SETTING_ID, 42)
                }
                val callback = MainActivity::class.java.getDeclaredMethod(
                    "onNewIntent",
                    Intent::class.java
                )
                callback.isAccessible = true
                callback.invoke(activity, warmIntent)

                // The OFF callback returns before resolving AlertEventBus or consuming extras.
                assertEquals(
                    "rate_alert",
                    warmIntent.getStringExtra(FXiMessagingService.EXTRA_NOTIFICATION_TYPE)
                )
                assertEquals(42, warmIntent.getIntExtra(FXiMessagingService.EXTRA_SETTING_ID, -1))
                assertNull(activity.pendingAlertEvent.value)
            }
        }
    }

    @Test
    fun FCMCallbacksAndLocalEventBusHaveNoAppOwnedSideEffects() {
        assertFalse(BuildConfig.TOPIC_V2_RELEASE_ON)
        val service = FXiMessagingService()
        val providerReads = AtomicInteger(0)
        service.pushNotificationManagerProvider = rejectingProvider(providerReads)
        service.subscriptionManagerProvider = rejectingProvider(providerReads)
        service.alertEventBusProvider = rejectingProvider(providerReads)

        // These calls happen before Android attaches a Context or Hilt injects Providers.
        // Any operation before the D24 guard therefore fails this test immediately.
        service.onNewToken("fixture-unregistered-token")
        service.onMessageReceived(
            RemoteMessage.Builder("fixture-sender")
                .setData(
                    mapOf(
                        "type" to "rate_alert",
                        "setting_id" to "99",
                        "title" to "must-not-render",
                        "body" to "must-not-render"
                    )
                )
                .build()
        )
        service.onMessageReceived(
            RemoteMessage.Builder("fixture-sender")
                .setData(mapOf("type" to "sync_alerts"))
                .build()
        )

        assertFalse(AlertEventBus().emit(AlertEvent.RefreshNeeded))
        assertEquals(0, providerReads.get())
    }

    private fun alertIntent(settingId: String): Intent = Intent(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        MainActivity::class.java
    ).apply {
        putExtra("type", "rate_alert")
        putExtra("setting_id", settingId)
    }

    private fun <T> rejectingProvider(reads: AtomicInteger): Provider<T> = Provider {
        reads.incrementAndGet()
        error("D24-OFF resolved an FCM runtime dependency")
    }
}
