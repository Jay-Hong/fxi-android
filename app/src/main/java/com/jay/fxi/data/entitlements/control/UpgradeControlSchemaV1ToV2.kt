package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.intPreferencesKey
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import java.io.IOException

/** Explicit management outcome; no prepared business command or Applied row is issued. */
internal sealed interface ControlSchemaUpgradeResult {
    data class Confirmed(val snapshot: ConfirmedControlSnapshot, val proof: ConfirmationProof) : ControlSchemaUpgradeResult
    data class RecoveryRequired(val reason: RecoveryReason, val observation: ControlRecordRead) : ControlSchemaUpgradeResult
    data class Unconfirmed(val observation: ControlRecordRead?, val failure: IOException) : ControlSchemaUpgradeResult
}

/** Called only after the facade has counted the actual snapshot inside the owner's decision. */
internal object UpgradeControlSchemaV1ToV2 {
    fun decide(read: ControlRecordRead): RecordTransactionDecision<RecoveryReason?> {
        if (read !is ControlRecordRead.Supported) return RecordTransactionDecision.Observe(
            if (read is ControlRecordRead.MigrationOrRecoveryRequired) RecoveryReason.MigrationOrRecovery else RecoveryReason.UnreadableRecord)
        if (read.schemaVersion == 2) return RecordTransactionDecision.Confirm(read.original, null)
        val candidate = read.original.toMutablePreferences()
        candidate[intPreferencesKey(ControlRecordKeys.SCHEMA)] = 2
        candidate[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = "[]"
        candidate[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = "[]"
        return RecordTransactionDecision.Confirm(candidate, null)
    }
}
