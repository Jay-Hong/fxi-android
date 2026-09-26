package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore

/** Pure consumption of one current-lifetime Lifecycle evidence row from an interpretable schema-2 record. */
internal object ControlLifecycleConsumption {
    sealed interface Decision {
        data class Ready(val candidate: Preferences) : Decision
        data class Recovery(val reason: RecoveryReason) : Decision
        data object Conflict : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    fun decide(
        read: ControlRecordRead.Supported,
        command: CommandRef,
        input: ControlLifecycleDescriptor,
        expected: AppliedEvidence.Lifecycle?,
        retry: Boolean,
        codec: ControlPayloadCodec
    ): Decision {
        if (expected == null) return Decision.Recovery(RecoveryReason.ExpectedLifecycleEvidenceUnavailable)
        if (input.operationId != command.id || expected.commandId != command.id ||
            expected.ownerTrackingLifetimeId != command.ownerTrackingLifetimeId.value ||
            !ControlLifecycleEvidence.matches(input, expected)
        ) return Decision.Conflict

        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val own = evidence.singleOrNull { it.value.commandId == command.id }
        if (own == null) {
            return if (retry) Decision.Ready(read.original)
            else Decision.Recovery(RecoveryReason.CommandEvidenceLost)
        }
        val ownRow = own.value as? AppliedEvidence.Lifecycle ?: return Decision.Conflict
        if (ControlAppliedEvidence.node(ownRow) != ControlAppliedEvidence.node(expected)) return Decision.Conflict

        val survivors = evidence.filterNot { it === own }.map { it.original.toPayloadEntry() }
        val key = ControlPayloadKey.COMMAND_EVIDENCE
        val encoded = try { codec.encode(survivors) } catch (_: IllegalArgumentException) {
            return Decision.Rejected(RejectionReason.InvalidRequest("consumption violates codec envelope constraints"))
        }
        val candidate = read.original.toMutablePreferences()
        when (encoded) {
            is PayloadWrite.TooLarge -> return Decision.Rejected(RejectionReason.TooLarge(key, encoded.bytes, encoded.limit))
            is PayloadWrite.Encoded -> candidate[ControlRecordKeys.payload(key)] = encoded.text
        }

        val complete = ControlRecordReader(codec).read(candidate)
        if (complete !is ControlRecordRead.Supported || complete.schemaVersion != 2 || complete.blocksProtectedAdmission ||
            ControlAppliedEvidence.own(complete, command) != null
        ) return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        val evidenceAfter = (complete.metadata as ControlMetadataRead.V2).evidence.entries
            .map { (it as ControlEvidenceEntryRead.Interpreted).original.toPayloadEntry() }
        val frozenCandidate = candidate.toPreferences()
        if (evidenceAfter != survivors || withoutEvidence(frozenCandidate) != withoutEvidence(read.original))
            return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        return Decision.Ready(frozenCandidate)
    }

    fun validateReturn(returned: ControlRecordRead, command: CommandRef, candidate: Preferences): Boolean {
        if (returned !is ControlRecordRead.Supported || returned.schemaVersion != 2 || returned.blocksProtectedAdmission)
            return false
        if (ControlAppliedEvidence.own(returned, command) != null) return false
        return withoutBarrier(returned.original) == withoutBarrier(candidate)
    }

    private fun withoutEvidence(value: Preferences): Preferences = value.toMutablePreferences().apply {
        remove(ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE))
    }.toPreferences()

    private fun withoutBarrier(value: Preferences): Preferences = value.toMutablePreferences().apply {
        remove(DataStoreAccessEpochStore.READ_BARRIER)
    }.toPreferences()
}
