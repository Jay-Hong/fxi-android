package com.jay.fxi.service

/** What one alert push does: the event it emits and whether it shows a banner. */
data class AlertPushRoute(val event: AlertEvent, val showBanner: Boolean)

/** What the bank alert screen does with an [AlertEvent]. */
sealed class BankAlertAction {
    data class Trigger(val settingId: Int) : BankAlertAction()
    data object Refresh : BankAlertAction()
}

/**
 * Routes an alert push by its FCM `type` and `setting_id` (iOS a36682f PushNotificationService.handleRateAlertPush):
 * each family fires its own setting with a banner; an unreadable id reloads every family for bank (as iOS
 * alertSettingsNeedsRefresh) and its own family for source and comparison;
 * sync_alerts reloads every family silently; any other type is ignored.
 */
object AlertPushRouting {
    fun route(type: String?, settingId: String?): AlertPushRoute? {
        val family = when (type) {
            "rate_alert" -> AlertFamily.BANK
            "source_rate_alert" -> AlertFamily.SOURCE
            "comparison_alert" -> AlertFamily.COMPARISON
            "sync_alerts" -> return AlertPushRoute(AlertEvent.RefreshNeeded, showBanner = false)
            else -> return null
        }
        val id = settingId?.toIntOrNull()
        val event = if (id != null) {
            AlertEvent.SettingTriggered(id, family)
        } else if (family == AlertFamily.BANK) {
            AlertEvent.RefreshNeeded
        } else {
            AlertEvent.FamilyRefreshNeeded(family)
        }
        return AlertPushRoute(event, showBanner = true)
    }

    /** The bank alert screen acts on bank events and on all-family refreshes. */
    fun bankAction(event: AlertEvent): BankAlertAction? = when (event) {
        is AlertEvent.SettingTriggered ->
            if (event.family == AlertFamily.BANK) BankAlertAction.Trigger(event.settingId) else null
        is AlertEvent.FamilyRefreshNeeded ->
            if (event.family == AlertFamily.BANK) BankAlertAction.Refresh else null
        AlertEvent.RefreshNeeded -> BankAlertAction.Refresh
    }
}
