package com.jay.fxi.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<AlertEvent> = _events.asSharedFlow()

    fun emit(event: AlertEvent) {
        _events.tryEmit(event)
    }
}

sealed class AlertEvent {
    data class SettingTriggered(val settingId: Int) : AlertEvent()
    data object RefreshNeeded : AlertEvent()
}
