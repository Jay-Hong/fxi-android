package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import java.util.Collections
import java.util.UUID

/** UUIDs issued once at preparation. Null is allowed only for an axis that does not rotate. */
internal data class RecoveryFreshEpochs(val user: String?, val capability: String?)

/** Null target epochs are source facts; they do not encode the choice to preserve an axis. */
internal sealed interface RecoveryAxisAction {
    data object Preserve : RecoveryAxisAction
    data class Rotate(val freshEpoch: String) : RecoveryAxisAction
}

internal data class RecoveryAxisRetirement(val target: JournalTargetV1, val action: RecoveryAxisAction)

/** NotRequiredDepartedOwner grants no demand completion, re-entry or protected access. */
internal enum class RecoveryRequestRequirement { RequiredCurrentOwner, NotRequiredDepartedOwner }

/** Pure output, not a storage certificate. Axis order is USER then CAPABILITY, restricted to source. */
internal class RecoveryRetirementPlan(
    val before: FenceV1,
    val after: FenceV1,
    axes: List<RecoveryAxisRetirement>,
    journal: List<PendingPurge>,
    val requestRequirement: RecoveryRequestRequirement
) {
    val axes: List<RecoveryAxisRetirement> = Collections.unmodifiableList(axes.toList())
    /** One exact singleton-axis journal obligation per source axis, in the same order as axes. */
    val journal: List<PendingPurge> = Collections.unmodifiableList(journal.map {
        it.copy(scopes = Collections.unmodifiableSet(it.scopes.toSet()))
    })
}

internal sealed interface RecoveryRetirementResult {
    data class Planned(val plan: RecoveryRetirementPlan) : RecoveryRetirementResult
    data class Failed(val problem: HoldRecoveryProblem) : RecoveryRetirementResult
}

/**
 * Source-bound §7.2 table only. This cannot retire an arbitrary owner/axis/epoch tuple.
 * Reservation against the latest journal/obligation arrays is a separate attempt-time boundary.
 * HOLD and RECOVERY_INTENT share only verified owner/axis/epoch source facts.
 */
internal fun planRecoveryRetirement(
    source: RecoveryRetirementSource,
    before: FenceV1,
    fresh: RecoveryFreshEpochs
): RecoveryRetirementResult {
    RecoveryRetirementBoundary.inputProblem(source, before, fresh)?.let { return RecoveryRetirementResult.Failed(it) } // HR.planInput
    var after = before
    val axes = source.axes.sortedBy { it.ordinal }.map { axis ->
        val epoch = source.targetEpoch(axis)
        val action = if (RecoveryRetirementBoundary.rotationRequired(source, axis, before)) {
            val next = checkNotNull(if (axis == PurgeScope.USER) fresh.user else fresh.capability)
            after = if (axis == PurgeScope.USER) after.copy(userAccessEpoch = next) else after.copy(krxCapabilityEpoch = next)
            RecoveryAxisAction.Rotate(next)
        } else RecoveryAxisAction.Preserve
        RecoveryAxisRetirement(JournalTargetV1(source.ownerUid, axis, epoch), action)
    }
    val journal = axes.map { PendingPurge(it.target.ownerUid,
        it.target.epoch.takeIf { _ -> it.target.axis == PurgeScope.USER },
        it.target.epoch.takeIf { _ -> it.target.axis == PurgeScope.CAPABILITY }, setOf(it.target.axis)) }
    val plan = RecoveryRetirementPlan(before, after, axes, journal,
        if (source.ownerUid == before.ownerUid) RecoveryRequestRequirement.RequiredCurrentOwner
        else RecoveryRequestRequirement.NotRequiredDepartedOwner)
    if (!RecoveryRetirementBoundary.validPlan(source, before, fresh, plan)) return RecoveryRetirementResult.Failed(
        HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest("RequiredDecisionEffectMissing"))) // HR.planCandidate
    return RecoveryRetirementResult.Planned(plan)
}

internal object RecoveryRetirementBoundary {
    /** Axis must belong to source. Distinguishes departed/current-equal from unreadable current. */
    fun axisProblem(source: RecoveryRetirementSource, axis: PurgeScope, before: FenceV1): HoldRecoveryProblem? {
        if (axis !in source.axes) return invalid("SourceAxisMismatch") // HR.axisMember
        val target = source.targetEpoch(axis)
        val current = before.epoch(axis)
        if (target != null && current == null) return HoldRecoveryProblem.RecoveryRequired(RecoveryReason.UnreadableEpochState) // HR.unreadableEpoch
        if (source.ownerUid != before.ownerUid && target != null && target == current)
            return HoldRecoveryProblem.Conflict(ConflictReason.TargetChanged) // HR.departedEqual
        return null
    }

    internal fun rotationRequired(source: RecoveryRetirementSource, axis: PurgeScope, before: FenceV1): Boolean =
        source.ownerUid == before.ownerUid &&
            (source.targetEpoch(axis) == null || source.targetEpoch(axis) == before.epoch(axis))

    internal fun inputProblem(source: RecoveryRetirementSource, before: FenceV1, fresh: RecoveryFreshEpochs): HoldRecoveryProblem? {
        if (before.userAccessEpoch == "") return HoldRecoveryProblem.RecoveryRequired(RecoveryReason.UnreadableEpochState) // HR.emptyUser
        if (before.krxCapabilityEpoch == "") return HoldRecoveryProblem.RecoveryRequired(RecoveryReason.UnreadableEpochState) // HR.emptyKrx
        if (!journalFieldRepresentable(source.ownerUid)) return invalid("UnrepresentableJournalField") // HR.journalOwnerCall
        val issued = mutableListOf<String>()
        for (axis in source.axes) {
            axisProblem(source, axis, before)?.let { return it } // HR.axisCall
            if (!journalFieldRepresentable(source.targetEpoch(axis))) return invalid("UnrepresentableJournalField") // HR.journalEpochCall
            if (rotationRequired(source, axis, before)) {
                val next = (if (axis == PurgeScope.USER) fresh.user else fresh.capability) ?: return invalid("EpochNotFresh")
                if (!canonicalUuid(next)) return invalid("EpochNotFresh") // HR.uuid
                if (next == before.userAccessEpoch) return invalid("EpochNotFresh") // HR.beforeUser
                if (next == before.krxCapabilityEpoch) return invalid("EpochNotFresh") // HR.beforeKrx
                if (next in issued) return invalid("EpochNotFresh") // HR.otherFresh
                issued += next
            }
        }
        return null
    }

    /** Before axes, other fresh axis, canonical journal and all retained nested epochs reserve IDs. */
    fun freshEpochsProblem(
        source: RecoveryRetirementSource,
        before: FenceV1,
        fresh: RecoveryFreshEpochs,
        read: ControlRecordRead.Supported
    ): HoldRecoveryProblem? {
        inputProblem(source, before, fresh)?.let { return it } // HR.freshInputCall
        val shared = NamespaceSettlementTransition(ControlPayloadCodec())
        val journal = shared.canonicalJournal(read.original)
            ?: return HoldRecoveryProblem.RecoveryRequired(RecoveryReason.JournalMigrationRequired)
        val values = source.axes.filter { rotationRequired(source, it, before) }.map {
            checkNotNull(if (it == PurgeScope.USER) fresh.user else fresh.capability)
        }
        if (!shared.epochsAreUnused(values, read, journal)) return invalid("EpochNotFresh") // HR.reservationsCall
        return null
    }

    /** Null preserved; empty/delimiter/newline/unpaired surrogate rejected. */
    fun journalFieldRepresentable(value: String?): Boolean {
        if (value == null) return true
        if (value.isEmpty()) return false // HR.journalEmpty
        if ('|' in value) return false // HR.journalDelimiter
        if ('\n' in value) return false // HR.journalNewline
        if (value.hasUnpairedSurrogate()) return false // HR.journalSurrogate
        return true
    }

    /** Re-derive from the archived source and fixed before/UUIDs, not the proposed plan's fields. */
    fun validPlan(
        source: RecoveryRetirementSource,
        before: FenceV1,
        fresh: RecoveryFreshEpochs,
        candidate: RecoveryRetirementPlan
    ): Boolean {
        if (candidate.before != before) return false // HR.planBefore
        if (candidate.after.ownerUid != before.ownerUid) return false // HR.planOwner
        val axes = source.axes.sortedBy { it.ordinal }
        if (candidate.axes.map { it.target.axis } != axes) return false // HR.planAxes
        val expectedJournal = mutableListOf<PendingPurge>()
        for (axis in PurgeScope.entries) {
            val included = axis in source.axes
            val rotate = included && rotationRequired(source, axis, before)
            val expectedEpoch = if (rotate) (if (axis == PurgeScope.USER) fresh.user else fresh.capability) else before.epoch(axis)
            if (candidate.after.epoch(axis) != expectedEpoch) return false // HR.planEpoch
            if (included) {
                val actual = candidate.axes.single { it.target.axis == axis }
                if (actual.target.ownerUid != source.ownerUid) return false // HR.planTargetOwner
                if (actual.target.epoch != source.targetEpoch(axis)) return false // HR.planTargetEpoch
                val action = if (rotate) RecoveryAxisAction.Rotate(expectedEpoch ?: return false) else RecoveryAxisAction.Preserve
                if (actual.action != action) return false // HR.planAction
                expectedJournal += PendingPurge(source.ownerUid,
                    source.targetEpoch(axis).takeIf { axis == PurgeScope.USER },
                    source.targetEpoch(axis).takeIf { axis == PurgeScope.CAPABILITY }, setOf(axis))
            }
        }
        if (candidate.journal != expectedJournal) return false // HR.planJournal
        val requirement = if (source.ownerUid == before.ownerUid) RecoveryRequestRequirement.RequiredCurrentOwner
            else RecoveryRequestRequirement.NotRequiredDepartedOwner
        if (candidate.requestRequirement != requirement) return false // HR.planRequest
        return true
    }

    internal fun canonicalUuid(value: String): Boolean = try { UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }
    private fun invalid(detail: String) = HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail))
}
