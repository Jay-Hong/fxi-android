package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences

/** Fixes the first owner decision, then reuses rotation consumption for an exact bundle. */
internal object ControlRotationHandoffCandidate {
    sealed interface Decision {
        data class Ready(
            val candidate: Preferences,
            val expectedOwn: AppliedEvidence.Rotation?,
            val deletionIdentity: DependencyAtom.AppliedRow
        ) : Decision
        data class Recovery(val reason: RecoveryReason) : Decision
        data object Conflict : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    fun decide(
        read: ControlRecordRead.Supported,
        command: CommandRef,
        input: RotateAndSettleNamespaces,
        expected: AppliedEvidence?,
        fixed: TerminationPendingDescriptor.RotationHandoff?,
        codec: ControlPayloadCodec
    ): Decision {
        val identity = DependencyAtom.AppliedRow(command.id, command.ownerTrackingLifetimeId.value)
        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val own = evidence.singleOrNull { it.value.commandId == command.id }?.value
        val seals = read.arrays.getValue(ControlKind.SEAL).entries
            .map { it as ControlEntryRead.Interpreted }
        val fixedIds = input.seals.map { it.id }
        val operationSeals = seals.filter { (it.value as SealV1).settlement?.operationId == command.id }

        if (own != null && (own !is AppliedEvidence.Rotation || own.ownerTrackingLifetimeId != identity.lifetimeId))
            return Decision.Conflict
        if (fixed != null && fixed.expectedOwn == null && own != null)
            return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        if (own == null && (operationSeals.isNotEmpty() || seals.any { it.value.id in fixedIds }))
            return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        if (own == null && fixed?.expectedOwn == null)
            return Decision.Ready(read.original, null, identity)

        // A null exact is refused by ControlRotationConsumption.decide as ExpectedRotationEvidenceUnavailable.
        val exact = fixed?.expectedOwn ?: expected as? AppliedEvidence.Rotation
        return when (val result = ControlRotationConsumption.decide(read, command, input, exact,
            fixed != null, codec)) {
            is ControlRotationConsumption.Decision.Ready -> Decision.Ready(result.candidate, exact, identity)
            is ControlRotationConsumption.Decision.Recovery -> Decision.Recovery(result.reason)
            ControlRotationConsumption.Decision.Conflict -> Decision.Conflict
            is ControlRotationConsumption.Decision.Rejected -> Decision.Rejected(result.reason)
        }
    }
}
