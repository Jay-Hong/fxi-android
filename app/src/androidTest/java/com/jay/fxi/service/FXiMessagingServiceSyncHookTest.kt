package com.jay.fxi.service

import com.google.firebase.messaging.RemoteMessage
import com.jay.fxi.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Claude-owned S7 N1 N1-P (device part, `R4c/S7/s7n1` API r1–r3 §7): the real FCM service of this D24-OFF debug build returns
 * before anything app-owned — its new alert-family Provider is resolved by no message, a `sync_alerts` included, and so is no
 * other Provider (the emit is 0 too). The admitted path (`sync_alerts` → registry → owner) is checked in parts elsewhere:
 * `AlertFamilyRefresherRegistryContractTest` (deliverPush, every type × gate), `AlertN1StructureTest` (the exact call after the
 * emit) and `AlertFamilyLifecycleDeviceTest` (registry → owner → client); an admitted service on a device is not observed
 * here (an unverified limit, recorded). The implementation thread reads but does not edit this file.
 */
class FXiMessagingServiceSyncHookTest {

    private fun <T> rejecting(reads: AtomicInteger) = Provider<T> {
        reads.incrementAndGet()
        error("resolved in a D24-OFF process")
    }

    private fun message(vararg data: Pair<String, String>) = RemoteMessage.Builder("fixture-sender").setData(mapOf(*data)).build()

    @Test
    fun s7n1_p01_theOffServiceResolvesTheAlertFamiliesForNoMessage_syncAlertsIncluded() {
        assertFalse("premise: this build is D24 OFF", BuildConfig.TOPIC_V2_RELEASE_ON)
        val service = FXiMessagingService()
        val familyReads = AtomicInteger()
        val otherReads = AtomicInteger()
        service.alertFamiliesProvider = rejecting(familyReads)
        service.pushNotificationManagerProvider = rejecting(otherReads)
        service.subscriptionManagerProvider = rejecting(otherReads)
        service.alertEventBusProvider = rejecting(otherReads)

        for (type in listOf("sync_alerts", "rate_alert", "source_rate_alert", "comparison_alert", "unknown")) {
            service.onMessageReceived(message("type" to type, "setting_id" to "7"))
        }
        service.onMessageReceived(message("setting_id" to "7"))
        service.onMessageReceived(message("type" to "rate_alert", "setting_id" to "not-a-number"))

        assertEquals("S7N1_P01 no message resolved the alert families", 0, familyReads.get())
        assertEquals("S7N1_P01 and nothing else was resolved either (no emit, no banner)", 0, otherReads.get())
    }
}
