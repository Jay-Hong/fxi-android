package com.jay.fxi.data.alerts

import androidx.lifecycle.Lifecycle
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertPushRouting
import com.jay.fxi.service.AlertTapCodec
import com.jay.fxi.service.AlertTapExtraKind
import com.jay.fxi.service.AlertTapExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * S8-6 candidate entry (`R4c/S8/user_decision_s8_6.r1.md`): the candidate's production order, mirrored for the shared contract
 * test `S86SourceDeliveryContractTest`. Of the two shared-contract test sources, only the entry is replaced for RED (`s8_6_impl/pre_r1/S86Entry.base.kt`).
 * - push: `FXiMessagingService.onMessageReceived` — the route, then for `source_rate_alert` only the gated `deliverSourcePush`
 *   whose publication callback stands for `showAlertNotification(message, type, admitted)`, and a return before the bus emit; any
 *   other type keeps the base order (emit, gated raw hand-over, banner).
 * - tap: `MainActivity.parseAlertEventFromIntent` — `consumeSourceTap` first; a consumed tap returns null, so nothing goes to the
 *   cold slot or the warm bus; any other tap keeps the base parser and clear.
 */
internal object S86Entry {
    fun registry(
        families: List<() -> AlertLifecycleAdapter>,
        lifecycle: () -> Lifecycle,
        postToMain: (() -> Unit) -> Unit,
        premium: StateFlow<OwnedPremiumAccess>,
        liveIdentity: () -> AuthIdentityFence?,
        scope: CoroutineScope,
        reportFailure: () -> Unit,
        captureSourceUse: () -> AlertSettingsUse?
    ): AlertFamilyRefresherRegistry =
        AlertFamilyRefresherRegistry(families, lifecycle, postToMain, premium, liveIdentity, scope, reportFailure,
            captureSourceUse = captureSourceUse)

    /** Returns the raw route for a recognized type, including when the source branch declines delivery, or null for a missing or unrecognized type. */
    fun push(
        data: Map<String, String>,
        gateOpen: Boolean,
        resolve: () -> AlertFamilyRefresherRegistry,
        emit: (AlertEvent) -> Unit,
        publish: (() -> Boolean) -> Unit
    ): Any? {
        val type = data["type"] ?: return null
        val route = AlertPushRouting.route(type, data["setting_id"]) ?: return null
        if (type == AlertFamilyRefresherRegistry.SOURCE_RATE_ALERT) {
            if (gateOpen) AlertFamilyRefresherRegistry.deliverSourcePush(data, resolve) { admitted -> publish(admitted) }
            return route
        }
        emit(route.event)
        AlertFamilyRefresherRegistry.deliverPush(type, data["setting_id"], data["is_repeat"], gateOpen, resolve = resolve)
        if (route.showBanner) publish { true }
        return route
    }

    /** Returns the event the Activity puts in its cold slot or on the warm bus, or null. */
    fun tap(extras: AlertTapExtras, gateOpen: Boolean, resolve: () -> AlertFamilyRefresherRegistry): AlertEvent? {
        if (AlertFamilyRefresherRegistry.consumeSourceTap(extras, gateOpen, resolve)) return null
        fun string(key: String) = if (extras.kind(key) == AlertTapExtraKind.STRING) extras.readString(key) else null
        val type = string(AlertTapCodec.CUSTOM_TYPE) ?: string(AlertTapCodec.SERVER_TYPE)
        val id = extras.readInt(AlertTapCodec.CUSTOM_ID, -1).takeIf { it != -1 }?.toString() ?: string(AlertTapCodec.CUSTOM_ID)
        val route = AlertPushRouting.route(type, id) ?: return null
        AlertFamilyRefresherRegistry.deliverTap(route.event, string("is_repeat"), gateOpen, resolve = resolve)
        for (key in listOf(AlertTapCodec.CUSTOM_TYPE, AlertTapCodec.CUSTOM_ID, AlertTapCodec.SERVER_TYPE, "is_repeat")) extras.remove(key)
        return route.event
    }
}
