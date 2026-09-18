package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.stringPreferencesKey

/** Shared wire names for the D1 reader and the unwired record transitions. */
internal object ControlRecordKeys {
    const val SCHEMA = "control_schema"
    val payloads = mapOf(
        ControlKind.SEAL to "seal_v1",
        ControlKind.DEMAND to "demand_v1",
        ControlKind.HOLD to "hold_v1",
        ControlKind.RECOVERY_INTENT to "recovery_intent_v1"
    )
    fun payload(kind: ControlKind) = stringPreferencesKey(payloads.getValue(kind))
}
