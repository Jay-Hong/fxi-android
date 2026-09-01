package com.jay.fxi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.jay.fxi.service.AlertEventBus
import com.jay.fxi.service.PushNotificationManager
import com.jay.fxi.subscription.SubscriptionManager
import com.jay.fxi.ui.screen.RELEASE_UNAVAILABLE_TEST_TAG
import com.jay.fxi.ui.screen.RootScreen
import com.jay.fxi.ui.theme.FXiTheme
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ReleaseAdmissionOffUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun OFF_rendersOnlyUnavailableWithoutResolvingRuntimeProviders() {
        assertFalse(BuildConfig.TOPIC_V2_RELEASE_ON)
        val providerReads = AtomicInteger(0)

        composeRule.setContent {
            FXiTheme {
                RootScreen(
                    subscriptionManagerProvider = rejectingProvider(providerReads),
                    pushNotificationManagerProvider = rejectingProvider(providerReads),
                    alertEventBusProvider = rejectingProvider(providerReads)
                )
            }
        }

        composeRule.onNodeWithTag(RELEASE_UNAVAILABLE_TEST_TAG).assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
        assertEquals(0, providerReads.get())
    }

    private fun <T> rejectingProvider(reads: AtomicInteger): Provider<T> = Provider {
        reads.incrementAndGet()
        error("D24-OFF resolved an armed runtime dependency")
    }
}
