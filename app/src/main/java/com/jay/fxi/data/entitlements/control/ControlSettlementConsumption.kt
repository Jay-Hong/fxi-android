package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore

/** Pure consumption of one fixed R/N/L settlement bundle from an interpretable schema-2 record. */
internal object ControlSettlementConsumption {
    sealed interface Decision {
        data class Ready(val candidate: Preferences) : Decision
        data class Recovery(val reason: RecoveryReason) : Decision
        data object Conflict : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    private data class Target(val original: ControlNode, val seal: SealV1)
    private data class Fixed(
        val transition: HandoverSettlementTransition,
        val targets: List<Target>,
        val demandId: String?
    ) {
        val ids: List<String> get() = targets.map { it.seal.id }
    }

    fun decide(
        read: ControlRecordRead.Supported,
        command: CommandRef,
        input: HandoverSettlementInput,
        expected: AppliedEvidence.Settlement?,
        retry: Boolean,
        codec: ControlPayloadCodec
    ): Decision {
        if (expected == null) return Decision.Recovery(RecoveryReason.ExpectedSettlementEvidenceUnavailable)
        val fixed = fixed(input) ?: return Decision.Rejected(RejectionReason.InvalidRequest("invalid settlement targets"))
        val evidence = (read.metadata as ControlMetadataRead.V2).evidence.entries
            .map { it as ControlEvidenceEntryRead.Interpreted }
        val seals = read.arrays.getValue(ControlKind.SEAL).entries
            .map { it as ControlEntryRead.Interpreted }
        val own = evidence.singleOrNull { it.value.commandId == command.id }
        val operationSeals = seals.filter { (it.value as SealV1).settlement?.operationId == command.id }

        if (own == null) {
            if (!retry) return Decision.Recovery(RecoveryReason.CommandEvidenceLost)
            // A prepared or replaced seal at a fixed id is not an all-absent retry.
            if (operationSeals.isNotEmpty() || seals.any { it.value.id in fixed.ids })
                return Decision.Recovery(RecoveryReason.InconsistentReclamation)
            return Decision.Ready(read.original)
        }
        val row = own.value as? AppliedEvidence.Settlement ?: return Decision.Conflict
        // This ordered comparison precedes every row/expected comparison.
        if (row.sealIds != fixed.ids) return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        if (expected.commandId != command.id || row.commandId != expected.commandId ||
            expected.ownerTrackingLifetimeId != command.ownerTrackingLifetimeId.value ||
            row.ownerTrackingLifetimeId != expected.ownerTrackingLifetimeId ||
            expected.transition != fixed.transition || row.transition != expected.transition ||
            expected.sealIds != fixed.ids || expected.demandId != fixed.demandId ||
            row.demandId != expected.demandId
        ) return Decision.Conflict

        val selected = fixed.ids.map { id -> seals.singleOrNull { it.value.id == id }
            ?: return Decision.Recovery(RecoveryReason.InconsistentReclamation) }
        if (selected.any { (it.value as SealV1).settlement?.operationId != command.id } ||
            operationSeals.map { it.value.id }.toSet() != fixed.ids.toSet()
        ) return Decision.Recovery(RecoveryReason.InconsistentReclamation)

        val shared = NamespaceSettlementTransition(codec)
        val retiredNull = RetiredNullSettlementTransition(codec)
        for ((index, entry) in selected.withIndex()) {
            val target = fixed.targets[index]
            val seal = entry.value as SealV1
            val actualWitness = checkNotNull(seal.settlement)
            val matches = when (input) {
                is RetiredNamespaceSettlement -> shared.witnessMatches(
                    actualWitness, RetiredNamespaceSettlementTransition(codec).witness(input))
                is CurrentNullSettlement -> shared.witnessMatches(
                    actualWitness, CurrentNullSettlementTransition(codec).witness(input, target.seal))
                is RetiredNullSettlement -> retiredNull.witnessMatches(
                    actualWitness, retiredNull.witness(input, target.seal))
            }
            if (!shared.immutableSealMatches(entry.original, target.original) || !matches)
                return Decision.Recovery(RecoveryReason.InconsistentReclamation)
        }

        val survivingEvidence = evidence.filterNot { it === own }.map { it.original.toPayloadEntry() }
        val survivingSeals = seals.filterNot { it.value.id in fixed.ids }.map { it.original.toPayloadEntry() }
        val candidate = read.original.toMutablePreferences()
        for ((key, entries) in listOf(
            ControlPayloadKey.COMMAND_EVIDENCE to survivingEvidence,
            ControlPayloadKey.SEAL to survivingSeals
        )) {
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
        if (evidenceAfter != survivingEvidence || sealsAfter != survivingSeals ||
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

    private fun fixed(input: HandoverSettlementInput): Fixed? = when (input) {
        is RetiredNamespaceSettlement -> {
            val seal = (ControlObligations.read(ControlKind.SEAL, input.target) as? ControlEntryRead.Interpreted)
                ?.value as? SealV1
            seal?.let { Fixed(HandoverSettlementTransition.RETIRED_NAMESPACE, listOf(Target(input.target, it)), input.demandId) }
        }
        is CurrentNullSettlement -> input.targets.takeIf { it.isNotEmpty() && it.size == input.nullTargets.size + input.companions.size }
            ?.let { Fixed(HandoverSettlementTransition.CURRENT_NULL, it.map { target -> Target(target.original, target.seal) }, input.demandId) }
        is RetiredNullSettlement -> input.ordered.takeIf { it.isNotEmpty() && it.size == input.targets.size }
            ?.let { Fixed(HandoverSettlementTransition.RETIRED_NULL, it.map { target -> Target(target.original, target.seal) }, null) }
    }

    private fun withoutChangedPayloads(value: Preferences): Preferences = value.toMutablePreferences().apply {
        remove(ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE))
        remove(ControlRecordKeys.payload(ControlPayloadKey.SEAL))
    }.toPreferences()

    private fun withoutBarrier(value: Preferences): Preferences = value.toMutablePreferences().apply {
        remove(DataStoreAccessEpochStore.READ_BARRIER)
    }.toPreferences()
}
