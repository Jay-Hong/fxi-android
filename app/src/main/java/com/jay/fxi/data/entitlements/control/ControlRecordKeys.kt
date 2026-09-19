package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.stringPreferencesKey

/** Wire identities, including metadata which is not an obligation kind. */
enum class ControlPayloadKey(val wireName: String) {
    SEAL("seal_v1"),
    DEMAND("demand_v1"),
    HOLD("hold_v1"),
    RECOVERY_INTENT("recovery_intent_v1"),
    COMMAND_EVIDENCE("command_evidence_v2"),
    SCOPE_FENCE("scope_fence_v2");

    companion object {
        fun forKind(kind: ControlKind): ControlPayloadKey = valueOf(kind.name)
    }
}

/** Shared wire names for the reader and the unwired record transitions. */
internal object ControlRecordKeys {
    const val SCHEMA = "control_schema"
    val payloads = ControlKind.entries.associateWith { ControlPayloadKey.forKind(it).wireName }
    val allPayloads = ControlPayloadKey.entries
    fun required(schema: Int): List<ControlPayloadKey> = when (schema) {
        1 -> allPayloads.take(4)
        2 -> allPayloads
        else -> error("unsupported control schema: $schema")
    }
    fun forbidden(schema: Int): List<ControlPayloadKey> = when (schema) {
        1 -> allPayloads.drop(4)
        2 -> emptyList()
        else -> error("unsupported control schema: $schema")
    }
    fun payload(kind: ControlKind) = payload(ControlPayloadKey.forKind(kind))
    fun payload(key: ControlPayloadKey) = stringPreferencesKey(key.wireName)
}
