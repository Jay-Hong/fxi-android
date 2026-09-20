package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import java.util.Collections

/**
 * Capture under the coordinator lock on EVERY attempt and retain that serialization until return.
 * Archived booleans, counters or an empty unresolved set cannot substitute for current admission.
 * This P2 API is unwired; coordinator admission and fresh-order handover belong to D2c/P3.
 */
internal data class AttemptContext(
    val ownerUid: String?,
    val binding: Long,
    val originLifetimeId: LifetimeId,
    val signOutOpen: Boolean,
    val identityPersistencePending: Boolean,
    val demandAuth: DemandAuthRuntime? = null
)

internal data class SettlementDemand(
    val ownerUid: String?,
    val binding: Long,
    val raisedAt: EventOrderV1,
    val intent: RefreshIntent
)

/** Fixed inputs, issued once at preparation; no clock/UUID issuance or target adoption on retry. */
internal class RotateAndSettleNamespaces(
    targets: List<ControlNode>,
    val before: FenceV1,
    val originLifetimeId: LifetimeId,
    val demand: SettlementDemand,
    val operationId: String,
    val demandId: String,
    val newUserEpoch: String?,
    val newKrxEpoch: String?
) {
    val targets: List<ControlNode> = Collections.unmodifiableList(targets.toList())
    internal data class Target(val original: ControlNode, val seal: SealV1)
    val sealTargets: List<Target> = Collections.unmodifiableList(this.targets.mapNotNull { node ->
        val seal = (ControlObligations.read(ControlKind.SEAL, node) as? ControlEntryRead.Interpreted)?.value as? SealV1
        seal?.let { Target(node, it) }
    })
    val seals: List<SealV1> = Collections.unmodifiableList(sealTargets.map { it.seal })
    /** Fixed identities, including the handed-over demand, on application and reconfirmation. */
    val effectiveIds: List<String> = Collections.unmodifiableList(seals.map { it.id } + demandId)
    val axes: Set<PurgeScope> = Collections.unmodifiableSet(seals.map { it.key.axis }.toSet())
    val after: FenceV1 get() = FenceV1(before.ownerUid,
        if (PurgeScope.USER in axes) newUserEpoch else before.userAccessEpoch,
        if (PurgeScope.CAPABILITY in axes) newKrxEpoch else before.krxCapabilityEpoch)
    val journal: PendingPurge get() = PendingPurge(before.ownerUid,
        before.userAccessEpoch.takeIf { PurgeScope.USER in axes },
        before.krxCapabilityEpoch.takeIf { PurgeScope.CAPABILITY in axes }, axes)

    fun witness(seal: SealV1) = SettlementEvidenceV1(operationId, originLifetimeId, StoreOp.BEGIN_ROTATION,
        before, after, JournalTargetV1(seal.key.ownerUid, seal.key.axis, seal.key.epoch))

    fun invalidInput(): String? {
        if (targets.isEmpty()) return "EmptyTargets"
        if (seals.size != targets.size) return "UnsupportedTargetKind"
        if (seals.map { it.id }.distinct().size != seals.size) return "DuplicateTargetId"
        if (axes.size != seals.size) return "DuplicateAxis"
        if (seals.any { it.kind != SealTargetKind.NAMESPACE }) return "UnsupportedTargetKind"
        if (seals.any { it.settlement != null }) return "AlreadySettledInput"
        if (seals.any { it.key.ownerUid != before.ownerUid }) return "TargetFenceMismatch"
        if (seals.any { it.key.epoch != before.epoch(it.key.axis) }) return "TargetFenceMismatch"
        val fresh = newEpochs()
        if (fresh.any { it == null }) return "EpochNotFresh"
        if (fresh.any { it == "" }) return "EpochNotFresh"
        if (fresh.any { it?.contains('|') == true }) return "UnrepresentableJournalField"
        if (fresh.any { it?.contains('\n') == true }) return "UnrepresentableJournalField"
        if (fresh.any { it?.hasUnpairedSurrogate() == true }) return "UnrepresentableJournalField"
        if (fresh.any { it == before.userAccessEpoch }) return "EpochNotFresh"
        if (fresh.any { it == before.krxCapabilityEpoch }) return "EpochNotFresh"
        if (fresh.distinct().size != fresh.size) return "EpochNotFresh"
        if (demand.ownerUid != before.ownerUid) return "DemandScopeMismatch"
        if (demand.raisedAt.origin != originLifetimeId) return "DemandScopeMismatch"
        if (demand.binding < 0) return "InvalidDemand"
        if (demand.raisedAt.value < 0) return "InvalidDemand"
        if (PurgeScope.USER in axes && demand.intent != RefreshIntent.FORCE_PREMIUM) return "InsufficientIntent"
        if (PurgeScope.CAPABILITY in axes && demand.intent == RefreshIntent.IF_STALE) return "InsufficientIntent"
        if (!journalField(journal.ownerUid)) return "UnrepresentableJournalField"
        if (!journalField(journal.userAccessEpoch)) return "UnrepresentableJournalField"
        if (!journalField(journal.krxCapabilityEpoch)) return "UnrepresentableJournalField"
        if (originLifetimeId.value.isEmpty()) return "InvalidDemand"
        return null
    }

    internal fun newEpochs(): List<String?> = axes.map { if (it == PurgeScope.USER) newUserEpoch else newKrxEpoch }

    private fun journalField(value: String?): Boolean =
        value == null || (value.isNotEmpty() && '|' !in value && '\n' !in value && !value.hasUnpairedSurrogate())
}

/** Absent is an observation, never a claim that a purge/demand was consumed. */
internal enum class JournalObservation { Present, Covered, Absent, Uninterpretable }
internal enum class DemandObservation { Present, Changed, Absent, Uninterpretable }

/** Storage receipt only: no purge completion, server freshness, axis release or access authority. */
internal class SettlementReceipt(
    val operationId: String,
    val originLifetimeId: LifetimeId,
    val before: FenceV1,
    val after: FenceV1,
    witnesses: Map<String, SettlementEvidenceV1>,
    journal: Map<String, JournalObservation>,
    val demandId: String,
    val demand: DemandObservation,
    remainingSeals: List<SealV1>,
    val hasUninterpretable: Boolean,
    val hasUninterpretableMetadata: Boolean
) : ControlSettlementReceipt {
    val blocksProtectedAdmission: Boolean get() = hasUninterpretable || hasUninterpretableMetadata
    val witnesses: Map<String, SettlementEvidenceV1> = Collections.unmodifiableMap(witnesses.toMap())
    val journal: Map<String, JournalObservation> = Collections.unmodifiableMap(journal.toMap())
    val remainingSeals: List<SealV1> = Collections.unmodifiableList(remainingSeals.toList())
}
