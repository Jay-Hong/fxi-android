package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore

/** Validates the fixed current-lifetime bundle and constructs only its two-payload deletion. */
internal object ControlRotationConsumption {
    sealed interface Decision {
        data class Ready(val candidate: Preferences) : Decision
        data class Recovery(val reason: RecoveryReason) : Decision
        data object Conflict : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    fun decide(
        read: ControlRecordRead.Supported,
        command: CommandRef,
        input: RotateAndSettleNamespaces,
        expected: AppliedEvidence.Rotation?,
        retry: Boolean,
        codec: ControlPayloadCodec
    ): Decision {
        if (expected == null) return Decision.Recovery(RecoveryReason.ExpectedRotationEvidenceUnavailable)
        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val seals = read.arrays.getValue(ControlKind.SEAL).entries
            .map { it as ControlEntryRead.Interpreted }
        val own = evidence.singleOrNull { it.value.commandId == command.id }
        val operationSeals = seals.filter { (it.value as SealV1).settlement?.operationId == command.id }
        val fixedIds = input.seals.map { it.id }

        if (own == null) {
            if (!retry) return Decision.Recovery(RecoveryReason.CommandEvidenceLost)
            // A replacement seal with a fixed id is not an all-absent bundle.
            if (operationSeals.isNotEmpty() || seals.any { it.value.id in fixedIds })
                return Decision.Recovery(RecoveryReason.InconsistentReclamation)
            return Decision.Ready(read.original)
        }
        val row = own.value as? AppliedEvidence.Rotation ?: return Decision.Conflict
        // The fixed order check precedes the expected-row comparison by contract.
        if (row.sealIds != fixedIds) return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        if (expected.commandId != command.id || expected.ownerTrackingLifetimeId != command.ownerTrackingLifetimeId.value ||
            expected.sealIds != fixedIds || expected.demandId != input.demandId ||
            ControlAppliedEvidence.node(row) != ControlAppliedEvidence.node(expected)
        ) return Decision.Conflict

        val selected = fixedIds.map { id -> seals.singleOrNull { it.value.id == id }
            ?: return Decision.Recovery(RecoveryReason.InconsistentReclamation) }
        if (selected.any {
                val seal = it.value as SealV1
                seal.kind != SealTargetKind.NAMESPACE || seal.settlement?.operationId != command.id
            }) return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        if (operationSeals.map { it.value.id }.toSet() != fixedIds.toSet())
            return Decision.Recovery(RecoveryReason.InconsistentReclamation)

        val transition = NamespaceSettlementTransition(codec)
        for ((index, entry) in selected.withIndex()) {
            val fixed = input.sealTargets[index]
            val seal = entry.value as SealV1
            if (!transition.immutableSealMatches(entry.original, fixed.original) ||
                !transition.witnessMatches(checkNotNull(seal.settlement), input.witness(fixed.seal))
            ) return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        }

        val candidate = read.original.toMutablePreferences()
        val changed = mapOf(
            ControlPayloadKey.COMMAND_EVIDENCE to evidence.filterNot { it === own }.map { it.original.toPayloadEntry() },
            ControlPayloadKey.SEAL to seals.filterNot { it.value.id in fixedIds }.map { it.original.toPayloadEntry() }
        )
        for ((key, entries) in changed) {
            val encoded = try { codec.encode(entries) } catch (_: IllegalArgumentException) {
                return Decision.Rejected(RejectionReason.InvalidRequest("consumption violates codec envelope constraints"))
            }
            when (encoded) {
                is PayloadWrite.TooLarge -> return Decision.Rejected(RejectionReason.TooLarge(key, encoded.bytes, encoded.limit))
                is PayloadWrite.Encoded -> candidate[ControlRecordKeys.payload(key)] = encoded.text
            }
        }
        val complete = ControlRecordReader(codec).read(candidate)
        if (complete !is ControlRecordRead.Supported || complete.schemaVersion != 2 || complete.blocksProtectedAdmission ||
            ControlAppliedEvidence.own(complete, command) != null ||
            complete.arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>().any {
                (it.value as SealV1).settlement?.operationId == command.id
            }
        ) return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        val evidenceAfter = (complete.metadata as ControlMetadataRead.V2).evidence.entries
            .map { (it as ControlEvidenceEntryRead.Interpreted).original.toPayloadEntry() }
        val sealsAfter = complete.arrays.getValue(ControlKind.SEAL).entries
            .map { (it as ControlEntryRead.Interpreted).original.toPayloadEntry() }
        if (evidenceAfter != changed.getValue(ControlPayloadKey.COMMAND_EVIDENCE) ||
            sealsAfter != changed.getValue(ControlPayloadKey.SEAL) ||
            withoutChangedPayloads(candidate.toPreferences()) != withoutChangedPayloads(read.original)
        ) return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        return Decision.Ready(candidate.toPreferences())
    }

    fun validateReturn(returned: ControlRecordRead, command: CommandRef, candidate: Preferences): Boolean {
        if (returned !is ControlRecordRead.Supported || returned.schemaVersion != 2 || returned.blocksProtectedAdmission)
            return false
        if (ControlAppliedEvidence.own(returned, command) != null) return false
        if (returned.arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>().any {
                (it.value as SealV1).settlement?.operationId == command.id
            }) return false
        return withoutBarrier(returned.original) == withoutBarrier(candidate)
    }

    private fun withoutChangedPayloads(value: Preferences): Preferences = value.toMutablePreferences().apply {
        remove(ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE))
        remove(ControlRecordKeys.payload(ControlPayloadKey.SEAL))
    }.toPreferences()

    private fun withoutBarrier(value: Preferences): Preferences = value.toMutablePreferences().apply {
        remove(DataStoreAccessEpochStore.READ_BARRIER)
    }.toPreferences()
}
