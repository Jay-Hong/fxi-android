package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences

/** One own Applied row may be consumed after the current owner validates the handoff. */
internal object ControlMutationsHandoffCandidate {
    sealed interface Decision {
        data class Ready(
            val candidate: Preferences,
            val expectedOwn: AppliedEvidence.Mutations?,
            val deletionIdentity: DependencyAtom.AppliedRow
        ) : Decision
        data class Recovery(val reason: RecoveryReason) : Decision
        data object Conflict : Decision
    }

    fun decide(
        read: ControlRecordRead.Supported,
        command: CommandRef,
        body: ControlCommandBody.Mutations,
        adoptedTargets: List<ControlCommandTarget?>,
        expected: AppliedEvidence?,
        fixed: TerminationPendingDescriptor.MutationsHandoff?,
        codec: ControlPayloadCodec
    ): Decision {
        val identity = DependencyAtom.AppliedRow(command.id, command.ownerTrackingLifetimeId.value)
        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val ownEntries = evidence.filter { it.value.commandId == command.id }
        val own = ownEntries.singleOrNull()
        if (own == null) return Decision.Ready(read.original, null, identity)

        val row = own.value as? AppliedEvidence.Mutations ?: return Decision.Conflict
        val exact = if (fixed != null) fixed.expectedOwn else expected as? AppliedEvidence.Mutations
        if (exact == null) return if (fixed == null)
            Decision.Recovery(RecoveryReason.ExpectedMutationsEvidenceUnavailable) else Decision.Conflict
        if (row.ownerTrackingLifetimeId != identity.lifetimeId ||
            row.targets.size != body.actions.size
        ) return Decision.Conflict
        for ((index, action) in body.actions.withIndex()) {
            val adopted = checkNotNull(adoptedTargets[index]) { "own evidence requires an adopted target" }
            val target = row.targets[index]
            if (target.kind != action.kind || target.id != adopted.id ||
                target.joined != adopted.joined || target.written != exact.targets[index].written
            ) return Decision.Conflict
        }

        val survivors = evidence.filterNot { it === own }.map { it.original.toPayloadEntry() }
        val encoded = codec.encode(survivors)
        val candidate = read.original.toMutablePreferences()
        when (encoded) {
            is PayloadWrite.TooLarge -> error("removing evidence exceeded the existing codec envelope")
            is PayloadWrite.Encoded -> candidate[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = encoded.text
        }
        return Decision.Ready(candidate.toPreferences(), exact, identity)
    }
}
