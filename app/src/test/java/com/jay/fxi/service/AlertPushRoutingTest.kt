package com.jay.fxi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Claude-owned FCM receiver contract (release audit: the receiver stayed v1.2.2 and ignored source_rate_alert and
 * comparison_alert). Oracle: iOS a36682f PushNotificationService.handleRateAlertPush and willPresent — each family
 * posts its own trigger (id) with a banner; an unreadable id refreshes every family for bank and its own family otherwise;
 * sync_alerts refreshes silently; any other type is not routed (the server sends only these four). The implementation thread reads but does not edit this file.
 */
class AlertPushRoutingTest {
    private val families = listOf("rate_alert" to AlertFamily.BANK, "source_rate_alert" to AlertFamily.SOURCE,
        "comparison_alert" to AlertFamily.COMPARISON)

    @Test fun F01_eachFamilyWithAReadableId_triggersThatSetting_withABanner() {
        for ((type, family) in families) assertEquals(type,
            AlertPushRoute(AlertEvent.SettingTriggered(42, family), showBanner = true), AlertPushRouting.route(type, "42"))
    }

    /** iOS: an unreadable bank id posts alertSettingsNeedsRefresh, which reloads every family (FXiApp.swift); the other two reload their own. */
    @Test fun F02_withoutAReadableId_bankRefreshesEveryFamily_theOthersTheirOwn_withABanner() {
        for ((type, family) in families) for (id in listOf(null, "", "x", "4.2")) assertEquals("$type $id",
            AlertPushRoute(if (family == AlertFamily.BANK) AlertEvent.RefreshNeeded else AlertEvent.FamilyRefreshNeeded(family), showBanner = true),
            AlertPushRouting.route(type, id))
    }

    @Test fun F03_syncAlerts_refreshesEveryFamily_silently_whateverTheId() {
        for (id in listOf(null, "7")) assertEquals("$id",
            AlertPushRoute(AlertEvent.RefreshNeeded, showBanner = false), AlertPushRouting.route("sync_alerts", id))
    }

    @Test fun F04_anyOtherType_isIgnored() {
        for (type in listOf(null, "", "RATE_ALERT", "rate_alerts", "unknown", " rate_alert")) assertNull("$type", AlertPushRouting.route(type, "1"))
    }

    @Test fun F05_theBankScreen_actsOnBankEventsAndSyncOnly() {
        assertEquals(BankAlertAction.Trigger(3), AlertPushRouting.bankAction(AlertEvent.SettingTriggered(3, AlertFamily.BANK)))
        assertEquals(BankAlertAction.Refresh, AlertPushRouting.bankAction(AlertEvent.FamilyRefreshNeeded(AlertFamily.BANK)))
        assertEquals(BankAlertAction.Refresh, AlertPushRouting.bankAction(AlertEvent.RefreshNeeded))
        for (family in listOf(AlertFamily.SOURCE, AlertFamily.COMPARISON)) {
            assertNull("$family trigger", AlertPushRouting.bankAction(AlertEvent.SettingTriggered(3, family)))
            assertNull("$family refresh", AlertPushRouting.bankAction(AlertEvent.FamilyRefreshNeeded(family)))
        }
    }
}
