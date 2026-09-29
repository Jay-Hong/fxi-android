package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences

/** Fixes the first owner decision for a Lifecycle handoff. */
internal object ControlLifecycleHandoffCandidate {
    sealed interface Decision {
        data class Ready(
            val candidate: Preferences,
            val expectedOwn: AppliedEvidence.Lifecycle?,
            val deletionIdentity: DependencyAtom.AppliedRow
        ) : Decision
        data class Recovery(val reason: RecoveryReason) : Decision
        data object Conflict : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    fun decide(
        read: ControlRecordRead.Supported,
        command: CommandRef,
        input: ControlLifecycleDescriptor,
        expected: AppliedEvidence?,
        fixed: TerminationPendingDescriptor.LifecycleHandoff?,
        codec: ControlPayloadCodec
    ): Decision {
        if (input.operationId != command.id) return Decision.Conflict
        val identity = DependencyAtom.AppliedRow(command.id, command.ownerTrackingLifetimeId.value)
        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val own = evidence.singleOrNull { it.value.commandId == command.id }?.value

        if (own != null && (own !is AppliedEvidence.Lifecycle ||
                own.ownerTrackingLifetimeId != identity.lifetimeId)) return Decision.Conflict
        if (fixed != null && fixed.expectedOwn == null && own != null)
            return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        if (own == null && (fixed == null || fixed.expectedOwn == null))
            return Decision.Ready(read.original, null, identity)

        // An exact first read fixes the only row that a retry may remove. Its later absence
        // is a successful confirmation; a first-read absence never authorizes a new row.
        val exact = fixed?.expectedOwn ?: expected as? AppliedEvidence.Lifecycle
        return when (val result = ControlLifecycleConsumption.decide(read, command, input, exact,
            fixed != null, codec)) {
            is ControlLifecycleConsumption.Decision.Ready -> Decision.Ready(result.candidate, exact, identity)
            is ControlLifecycleConsumption.Decision.Recovery -> Decision.Recovery(result.reason)
            ControlLifecycleConsumption.Decision.Conflict -> Decision.Conflict
            is ControlLifecycleConsumption.Decision.Rejected -> Decision.Rejected(result.reason)
        }
    }
}
