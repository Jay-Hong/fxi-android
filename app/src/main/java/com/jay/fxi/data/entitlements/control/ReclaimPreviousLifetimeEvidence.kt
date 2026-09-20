package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RecordTransactionDecision
import java.io.IOException

/** Management confirmation only; reclamation issues neither a business ref nor its own Applied. */
internal sealed interface ControlEvidenceReclamationResult {
    data class Confirmed(val snapshot: ConfirmedControlSnapshot, val proof: ConfirmationProof) : ControlEvidenceReclamationResult
    data class RecoveryRequired(val reason: RecoveryReason, val observation: ControlRecordRead) : ControlEvidenceReclamationResult
    data class Rejected(val reason: RejectionReason, val observation: ControlRecordRead) : ControlEvidenceReclamationResult
    data class Unconfirmed(val observation: ControlRecordRead?, val failure: IOException) : ControlEvidenceReclamationResult
}

/**
 * Runs after actual-snapshot observation in the owner's decision. A different tracker lifetime
 * means an earlier process only under the file's single-owner contract. Validate every old row
 * before constructing one removal candidate; never infer a deletion scope from opaque fields.
 */
internal object ReclaimPreviousLifetimeEvidence {
    fun decide(
        read: ControlRecordRead,
        lifetime: OwnerTrackingLifetimeId,
        codec: ControlPayloadCodec
    ): RecordTransactionDecision<ControlEvidenceReclamationResult?> {
        fun recovery(reason: RecoveryReason) = RecordTransactionDecision.Observe<ControlEvidenceReclamationResult?>(
            ControlEvidenceReclamationResult.RecoveryRequired(reason, read))
        fun reject(reason: RejectionReason) = RecordTransactionDecision.Observe<ControlEvidenceReclamationResult?>(
            ControlEvidenceReclamationResult.Rejected(reason, read))

        if (read !is ControlRecordRead.Supported) return recovery(
            if (read is ControlRecordRead.MigrationOrRecoveryRequired) RecoveryReason.MigrationOrRecovery else RecoveryReason.UnreadableRecord)
        if (read.schemaVersion != 2) return recovery(RecoveryReason.ControlSchemaMigrationRequired)
        if (read.hasUninterpretableMetadata) return recovery(RecoveryReason.UninterpretableMetadata)
        if (read.hasUninterpretable) return recovery(RecoveryReason.UninterpretableObligations)

        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val previous = evidence.filter {
            it.value.ownerTrackingLifetimeId != lifetime.value && when (it.value) {
                is AppliedEvidence.Mutations, is AppliedEvidence.Rotation -> true
                is AppliedEvidence.Settlement -> false
                is AppliedEvidence.Lifecycle -> false
            }
        }
        if (previous.isEmpty()) return RecordTransactionDecision.Confirm(read.original, null)

        val seals = read.arrays.getValue(ControlKind.SEAL).entries.map { it as ControlEntryRead.Interpreted }
        val byId = seals.associate { it.value.id to (it.value as SealV1) }
        val removedSeals = mutableSetOf<String>()
        for (entry in previous) {
            val row = entry.value
            if (row is AppliedEvidence.Rotation) {
                for (id in row.sealIds) {
                    val seal = byId[id] ?: return recovery(RecoveryReason.InconsistentReclamation)
                    if (seal.kind != SealTargetKind.NAMESPACE) return recovery(RecoveryReason.InconsistentReclamation)
                    val settlement = seal.settlement ?: return recovery(RecoveryReason.InconsistentReclamation)
                    if (settlement.operationId != row.commandId) return recovery(RecoveryReason.InconsistentReclamation)
                }
                val operationSeals = byId.values.filter { it.settlement?.operationId == row.commandId }.map { it.id }.toSet()
                if (operationSeals != row.sealIds.toSet()) return recovery(RecoveryReason.InconsistentReclamation)
                removedSeals += row.sealIds
            }
        }

        val removedCommands = previous.map { it.value.commandId }.toSet()
        val changes = mutableMapOf(ControlPayloadKey.COMMAND_EVIDENCE to evidence
            .filterNot { it.value.commandId in removedCommands }.map { it.original.toPayloadEntry() })
        if (removedSeals.isNotEmpty()) changes[ControlPayloadKey.SEAL] = seals
            .filterNot { it.value.id in removedSeals }.map { it.original.toPayloadEntry() }
        val candidate = read.original.toMutablePreferences()
        for ((key, entries) in changes) {
            val encoded = try { codec.encode(entries) } catch (_: IllegalArgumentException) {
                return reject(RejectionReason.InvalidRequest("reclamation violates codec envelope constraints"))
            }
            when (encoded) {
                is PayloadWrite.TooLarge -> return reject(RejectionReason.TooLarge(key, encoded.bytes, encoded.limit))
                is PayloadWrite.Encoded -> candidate[ControlRecordKeys.payload(key)] = encoded.text
            }
        }
        val complete = ControlRecordReader(codec).read(candidate)
        if (complete !is ControlRecordRead.Supported || complete.schemaVersion != 2 || complete.blocksProtectedAdmission) {
            return recovery(RecoveryReason.InconsistentReclamation)
        }
        return RecordTransactionDecision.Confirm(candidate, null)
    }
}
