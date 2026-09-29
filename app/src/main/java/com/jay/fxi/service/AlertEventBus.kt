package com.jay.fxi.service

import com.jay.fxi.admission.ReleaseAdmission
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<AlertEvent> = _events.asSharedFlow()

    fun emit(event: AlertEvent): Boolean {
        if (!ReleaseAdmission.isOpen) return false
        return _events.tryEmit(event)
    }
}

/** The three server alert families (FCM `type`: rate_alert / source_rate_alert / comparison_alert). */
enum class AlertFamily { BANK, SOURCE, COMPARISON }

sealed class AlertEvent {
    /** One setting of [family] fired. */
    data class SettingTriggered(val settingId: Int, val family: AlertFamily = AlertFamily.BANK) : AlertEvent()
    /** [family]'s settings need a reload (its push carried no readable setting id). */
    data class FamilyRefreshNeeded(val family: AlertFamily) : AlertEvent()
    /** Every family needs a reload (sync_alerts or an unreadable bank setting id). */
    data object RefreshNeeded : AlertEvent()
}
