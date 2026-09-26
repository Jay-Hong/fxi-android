package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.StoreOp
import java.util.Collections

/** Fixed caller evidence for a later owner transaction. Nodes and both list levels are detached. */
internal class PreviousEvidenceSelection(items: List<Item>) {
    sealed interface Item {
        val commandId: String

        class Settlement(
            override val commandId: String,
            rawApplied: ControlNode,
            orderedRawSeals: List<ControlNode>
        ) : Item {
            val rawApplied: ControlNode = rawApplied.detached()
            val orderedRawSeals: List<ControlNode> =
                Collections.unmodifiableList(orderedRawSeals.map { it.detached() })
        }

        class Lifecycle(override val commandId: String, rawApplied: ControlNode) : Item {
            val rawApplied: ControlNode = rawApplied.detached()
        }
    }

    val items: List<Item> = Collections.unmodifiableList(items.map { item ->
        when (item) {
            is Item.Settlement -> Item.Settlement(item.commandId, item.rawApplied, item.orderedRawSeals)
            is Item.Lifecycle -> Item.Lifecycle(item.commandId, item.rawApplied)
        }
    })
}

private fun ControlNode.detached(): ControlNode = ControlNode.of(toPayloadEntry().fields)

/** Pure 6-3C1 decision. The owner entry, closure and dependency gate belong to 6-3C2. */
internal object PreviousSettlementEvidenceReclamation {
    sealed interface Decision {
        data class Ready(val candidate: Preferences) : Decision
        data class Recovery(val reason: RecoveryReason) : Decision
        data object Conflict : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    private val inconsistent = Decision.Recovery(RecoveryReason.InconsistentReclamation)

    fun decide(
        read: ControlRecordRead.Supported,
        selection: PreviousEvidenceSelection,
        currentLifetime: OwnerTrackingLifetimeId,
        retry: Boolean,
        codec: ControlPayloadCodec
    ): Decision {
        if (read.schemaVersion != 2 || read.blocksProtectedAdmission || read.metadata !is ControlMetadataRead.V2)
            return inconsistent
        val items = selection.items
        if (items.isEmpty() || items.any { it !is PreviousEvidenceSelection.Item.Settlement } ||
            items.map { it.commandId }.toSet().size != items.size
        ) return Decision.Rejected(RejectionReason.InvalidRequest("invalid previous settlement selection"))
        val selected = items.map { it as PreviousEvidenceSelection.Item.Settlement }
        val metadata = read.metadata as ControlMetadataRead.V2
        val evidence = metadata.evidence.entries.map { it as ControlEvidenceEntryRead.Interpreted }
        val seals = read.arrays.getValue(ControlKind.SEAL).entries.map { it as ControlEntryRead.Interpreted }
        val byCommand = evidence.associateBy { it.value.commandId }
        val bySealId = seals.associateBy { it.value.id }
        val selectedIds = selected.flatMap { item -> item.orderedRawSeals.mapNotNull { raw ->
            ((ControlObligations.read(ControlKind.SEAL, raw) as? ControlEntryRead.Interpreted)?.value as? SealV1)?.id
        } }
        if (selectedIds.size != selected.sumOf { it.orderedRawSeals.size } ||
            selectedIds.toSet().size != selectedIds.size
        ) return Decision.Rejected(RejectionReason.InvalidRequest("invalid selected seal identities"))

        val commandIds = selected.map { it.commandId }.toSet()
        val present = selected.count { byCommand[it.commandId] != null }
        if (present != selected.size) {
            if (!retry) return Decision.Conflict
            if (present != 0 || selectedIds.any { bySealId[it] != null } || seals.any {
                    ((it.value as SealV1).settlement?.operationId) in commandIds
                }) return inconsistent
            return Decision.Ready(read.original)
        }

        for (item in selected) {
            val entry = byCommand.getValue(item.commandId)
            val row = entry.value as? AppliedEvidence.Settlement ?: return Decision.Conflict
            if (row.ownerTrackingLifetimeId == currentLifetime.value ||
                entry.original.toPayloadEntry() != item.rawApplied.toPayloadEntry()
            ) return Decision.Conflict
            if (row.sealIds.size != item.orderedRawSeals.size) return Decision.Conflict
            val bundle = row.sealIds.map { id -> bySealId[id] ?: return inconsistent }
            if (bundle.map { it.original.toPayloadEntry() } != item.orderedRawSeals.map { it.toPayloadEntry() })
                return Decision.Conflict
            val typed = bundle.map { it.value as SealV1 }
            if (typed.any { it.settlement?.operationId != row.commandId } ||
                seals.filter { (it.value as SealV1).settlement?.operationId == row.commandId }
                    .map { it.value.id }.toSet() != row.sealIds.toSet()
            ) return inconsistent
            if (!validBundle(row, typed)) return inconsistent
        }

        val sealIds = selectedIds.toSet()
        val survivingEvidence = evidence.filterNot { it.value.commandId in commandIds }.map { it.original.toPayloadEntry() }
        val survivingSeals = seals.filterNot { it.value.id in sealIds }.map { it.original.toPayloadEntry() }
        val candidate = read.original.toMutablePreferences()
        for ((key, entries) in listOf(
            ControlPayloadKey.COMMAND_EVIDENCE to survivingEvidence,
            ControlPayloadKey.SEAL to survivingSeals
        )) {
            val encoded = try { codec.encode(entries) } catch (_: IllegalArgumentException) {
                return Decision.Rejected(RejectionReason.InvalidRequest("reclamation violates codec envelope constraints"))
            }
            when (encoded) {
                is PayloadWrite.TooLarge -> return Decision.Rejected(RejectionReason.TooLarge(key, encoded.bytes, encoded.limit))
                is PayloadWrite.Encoded -> candidate[ControlRecordKeys.payload(key)] = encoded.text
            }
        }
        val frozen = candidate.toPreferences()
        val complete = ControlRecordReader(codec).read(frozen)
        if (complete !is ControlRecordRead.Supported || complete.schemaVersion != 2 || complete.blocksProtectedAdmission ||
            (complete.metadata as ControlMetadataRead.V2).evidence.entries.map {
                (it as ControlEvidenceEntryRead.Interpreted).original.toPayloadEntry()
            } != survivingEvidence || complete.arrays.getValue(ControlKind.SEAL).entries.map {
                (it as ControlEntryRead.Interpreted).original.toPayloadEntry()
            } != survivingSeals
        ) return inconsistent
        return Decision.Ready(frozen)
    }

    fun validateReturn(returned: ControlRecordRead, selection: PreviousEvidenceSelection, candidate: Preferences): Boolean {
        if (returned !is ControlRecordRead.Supported || returned.schemaVersion != 2 || returned.blocksProtectedAdmission)
            return false
        val commands = selection.items.map { it.commandId }.toSet()
        val ids = selection.items.filterIsInstance<PreviousEvidenceSelection.Item.Settlement>()
            .flatMap { it.orderedRawSeals }.mapNotNull {
                ((ControlObligations.read(ControlKind.SEAL, it) as? ControlEntryRead.Interpreted)?.value as? SealV1)?.id
            }.toSet()
        if ((returned.metadata as ControlMetadataRead.V2).evidence.entries.any {
                (it as ControlEvidenceEntryRead.Interpreted).value.commandId in commands
            } || returned.arrays.getValue(ControlKind.SEAL).entries.any {
                val seal = (it as ControlEntryRead.Interpreted).value as SealV1
                seal.id in ids || seal.settlement?.operationId in commands
            }) return false
        return withoutBarrier(returned.original) == withoutBarrier(candidate)
    }

    private fun validBundle(row: AppliedEvidence.Settlement, seals: List<SealV1>): Boolean = when (row.transition) {
        HandoverSettlementTransition.RETIRED_NAMESPACE -> retiredNamespace(row, seals)
        HandoverSettlementTransition.CURRENT_NULL -> currentNull(row, seals)
        HandoverSettlementTransition.RETIRED_NULL -> retiredNull(row, seals)
    }

    private fun retiredNamespace(row: AppliedEvidence.Settlement, seals: List<SealV1>): Boolean {
        val seal = seals.singleOrNull() ?: return false
        val witness = seal.settlement as? SettlementEvidenceV1 ?: return false
        return seal.kind == SealTargetKind.NAMESPACE && witness.operation == StoreOp.JOURNAL_RETIRED &&
            witness.before == witness.after && witness.journal == JournalTargetV1(seal.key.ownerUid, seal.key.axis, seal.key.epoch) &&
            (row.demandId != null) == (seal.key.ownerUid == witness.before.ownerUid)
    }

    private fun currentNull(row: AppliedEvidence.Settlement, seals: List<SealV1>): Boolean {
        if (row.demandId == null || seals.isEmpty() || seals.size > 4) return false
        val nulls = seals.filter { it.kind == SealTargetKind.NULL_NAMESPACE }
        if (nulls.isEmpty() || nulls.size > 2 || nulls.map { it.key.axis }.toSet().size != nulls.size) return false
        val companions = seals.filter { it.kind == SealTargetKind.NAMESPACE }
        if (companions.map { it.key.axis }.toSet().size != companions.size ||
            companions.any { companion -> nulls.none { it.key.axis == companion.key.axis } }
        ) return false
        val canonical = seals.sortedWith(compareBy<SealV1> { if (it.key.axis == PurgeScope.USER) 0 else 1 }
            .thenBy { if (it.kind == SealTargetKind.NULL_NAMESPACE) 0 else 1 })
        if (seals != canonical) return false
        val first = seals.first().settlement as? SettlementEvidenceV1 ?: return false
        if (first.operation != StoreOp.BEGIN_ROTATION || first.before.ownerUid != first.after.ownerUid) return false
        if (seals.any { seal ->
                val witness = seal.settlement as? SettlementEvidenceV1 ?: return false
                witness.operation != StoreOp.BEGIN_ROTATION || witness.originLifetimeId != first.originLifetimeId ||
                    witness.before != first.before || witness.after != first.after ||
                    witness.journal != JournalTargetV1(first.before.ownerUid, seal.key.axis, null) ||
                    seal.key.ownerUid != first.before.ownerUid ||
                    (seal.kind == SealTargetKind.NAMESPACE && seal.key.epoch != first.before.epoch(seal.key.axis))
            }) return false
        return PurgeScope.entries.all { axis ->
            if (nulls.any { it.key.axis == axis }) first.after.epoch(axis) != null && first.after.epoch(axis) != first.before.epoch(axis)
            else first.after.epoch(axis) == first.before.epoch(axis)
        }
    }

    private fun retiredNull(row: AppliedEvidence.Settlement, seals: List<SealV1>): Boolean {
        if (row.demandId != null || seals.isEmpty() || seals.size > 2 ||
            seals.any { it.kind != SealTargetKind.NULL_NAMESPACE } ||
            seals.map { it.key.axis }.toSet().size != seals.size ||
            seals.map { it.key.ownerUid }.toSet().size != 1 ||
            seals != seals.sortedBy { if (it.key.axis == PurgeScope.USER) 0 else 1 }
        ) return false
        val first = seals.first().settlement as? RetiredNullSettlementEvidenceV2 ?: return false
        return seals.all { seal ->
            val witness = seal.settlement as? RetiredNullSettlementEvidenceV2 ?: return false
            seal.key.ownerUid != witness.before.ownerUid && witness.before == witness.after &&
                witness.originLifetimeId == first.originLifetimeId && witness.before == first.before &&
                witness.after == first.after &&
                witness.journal == JournalTargetV1(seal.key.ownerUid, seal.key.axis, null)
        }
    }

    private fun withoutBarrier(value: Preferences): Preferences = value.toMutablePreferences().apply {
        remove(DataStoreAccessEpochStore.READ_BARRIER)
    }.toPreferences()
}
