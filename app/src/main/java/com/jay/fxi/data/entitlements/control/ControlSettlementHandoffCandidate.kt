package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences

/** Fixes the first owner decision, then reuses settlement consumption for an exact bundle. */
internal object ControlSettlementHandoffCandidate {
    sealed interface Decision {
        data class Ready(
            val candidate: Preferences,
            val expectedOwn: AppliedEvidence.Settlement?,
            val deletionIdentity: DependencyAtom.AppliedRow
        ) : Decision
        data class Recovery(val reason: RecoveryReason) : Decision
        data object Conflict : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    fun decide(
        read: ControlRecordRead.Supported,
        command: CommandRef,
        input: HandoverSettlementInput,
        expected: AppliedEvidence?,
        fixed: TerminationPendingDescriptor.SettlementHandoff?,
        codec: ControlPayloadCodec
    ): Decision {
        val identity = DependencyAtom.AppliedRow(command.id, command.ownerTrackingLifetimeId.value)
        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val own = evidence.singleOrNull { it.value.commandId == command.id }?.value
        val seals = read.arrays.getValue(ControlKind.SEAL).entries
            .map { it as ControlEntryRead.Interpreted }
        val authority = ControlSettlementConsumption.authority(input)
            ?: return Decision.Rejected(RejectionReason.InvalidRequest("invalid settlement targets"))
        val operationSeals = seals.filter { (it.value as SealV1).settlement?.operationId == command.id }

        if (own != null && (own !is AppliedEvidence.Settlement || own.ownerTrackingLifetimeId != identity.lifetimeId))
            return Decision.Conflict
        if (fixed != null && fixed.expectedOwn == null && own != null)
            return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        if (own == null && (operationSeals.isNotEmpty() || seals.any { it.value.id in authority.orderedSealIds }))
            return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        if (own == null && (fixed == null || fixed.expectedOwn == null))
            return Decision.Ready(read.original, null, identity)

        // An exact descriptor can consume an all-absent retry through the settlement consumer.
        val exact = fixed?.expectedOwn ?: expected as? AppliedEvidence.Settlement
        return when (val result = ControlSettlementConsumption.decide(read, command, input, exact,
            fixed != null, codec)) {
            is ControlSettlementConsumption.Decision.Ready -> Decision.Ready(result.candidate, exact, identity)
            is ControlSettlementConsumption.Decision.Recovery -> Decision.Recovery(result.reason)
            ControlSettlementConsumption.Decision.Conflict -> Decision.Conflict
            is ControlSettlementConsumption.Decision.Rejected -> Decision.Rejected(result.reason)
        }
    }
}
