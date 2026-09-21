package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.encode
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlRecordStore.Outcome

/** Synchronous candidate construction in the owner transaction; no runtime publication or join. */
internal class RecoverHoldTransition(private val codec: ControlPayloadCodec) {
    private val reader = ControlRecordReader(codec)
    private val shared = NamespaceSettlementTransition(codec)

    fun decide(command: CommandRef, input: ControlLifecycleDescriptor, read: ControlRecordRead.Supported,
        context: LifecycleAttemptContext): RecordTransactionDecision<Outcome> {
        fun fail(problem: HoldRecoveryProblem): RecordTransactionDecision<Outcome> {
            val result = when (problem) {
                is HoldRecoveryProblem.Rejected -> ControlStoreResult.Rejected(command, emptySet(), emptySet(), problem.reason, read)
                is HoldRecoveryProblem.Conflict -> ControlStoreResult.Conflict(command, emptySet(), emptySet(), problem.reason,
                    TargetExpectation(command, input.targets.map { it.target.id }), read)
                is HoldRecoveryProblem.RecoveryRequired -> ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), problem.reason, read)
            }
            return RecordTransactionDecision.Observe(Outcome.Negative(result))
        }
        val plan = input.recoverHold ?: return fail(invalid("InvalidLifecycleDescriptor"))
        plan.preparationProblem?.let { return fail(it) } // RH.prepareCall
        if (!validDescriptor(plan, input)) return fail(invalid("InvalidLifecycleDescriptor")) // RH.descriptorCall
        eligibility(plan, context, read, command.ownerTrackingLifetimeId.value)?.let { return fail(it) } // RH.eligibilityCall
        val candidate = when (val built = buildCandidate(command, plan, read)) {
            is RecoverHoldCandidateBuild.Failed -> return fail(built.problem)
            is RecoverHoldCandidateBuild.Built -> built.candidate
        }
        if (!validCandidate(command, plan, read, candidate)) return fail(invalid("RequiredDecisionEffectMissing")) // RH.candidateCall
        val after = reader.read(candidate) as ControlRecordRead.Supported
        return RecordTransactionDecision.Confirm(candidate, Outcome.Positive(ConfirmedEffect.AppliedThisAttempt,
            input.targets.map { it.target.id }, ControlLifecycleConfirmation(codec).receipt(input, after)))
    }

    /** Does not compare a descriptor with plan.descriptor(): that would share builder mistakes. */
    internal fun validDescriptor(plan: RecoverHoldPlan, input: ControlLifecycleDescriptor): Boolean {
        val fixed = plan.input
        if (input.recoverHold !== plan) return false // RH.namedPlan
        if (input.operationId != plan.ids.operationId) return false // RH.descriptorId
        if (input.transition != LifecycleTransition.RECOVER_HOLD) return false // RH.descriptorTransition
        if (input.executor != fixed.binding.executor) return false // RH.descriptorExecutor
        val expected = expectedTargets(fixed, plan.ids) ?: return false
        if (input.targets.map { it.target } != expected) return false // RH.descriptorTargets
        for (row in input.targets) {
            when (row.role) {
                LifecycleRole.HOLD -> {
                    if (row.before?.toPayloadEntry() != fixed.source.toPayloadEntry()) return false // RH.descriptorSource
                    if (row.after != null) return false // RH.descriptorRemoved
                }
                LifecycleRole.REQUEST -> {
                    if (row.before != null) return false // RH.descriptorRequestBefore
                    if (!requestValid(fixed, plan.ids, plan.requestOrder, row.after)) return false // RH.descriptorRequestCall
                }
                LifecycleRole.GUARD -> {
                    if (row.before?.toPayloadEntry() != fixed.guard?.toPayloadEntry()) return false // RH.descriptorGuardBefore
                    if (!HoldFloorPlan.validCandidate(floorInput(fixed, plan.ids), row.after)) return false // RH.descriptorFloorCall
                }
                LifecycleRole.RECOVERY_INTENT -> return false
            }
            val role = if (row.target.kind == ControlKind.HOLD) LifecycleRole.HOLD
                else if (row.target.id == plan.ids.requestId) LifecycleRole.REQUEST else LifecycleRole.GUARD
            if (row.role != role) return false // RH.descriptorRole
        }
        val unchanged = fixed.guard?.takeIf { old -> expected.none { it.id == guard(old)?.id } }
        val expectedUnchanged = unchanged?.let { listOf(LifecycleFixedTarget(
            LifecycleTarget(ControlKind.DEMAND, checkNotNull(guard(it)).id, LifecycleEffect.REPLACE), LifecycleRole.GUARD, it, it)) }.orEmpty()
        if (input.requiredUnchanged != expectedUnchanged) return false // RH.descriptorUnchanged
        val namespace = input.namespace ?: return false // RH.descriptorNamespace
        if (namespace.before != fixed.before) return false // RH.descriptorBefore
        val source = HoldRecoverySource.from(fixed.source) ?: return false
        // Pure validator takes the descriptor values, independently of cached plan.retirement.
        val axisRows = source.axes.sortedBy { it.ordinal }.map { axis ->
            val rotate = RecoveryRetirementBoundary.rotationRequired(source, axis, fixed.before)
            RecoveryAxisRetirement(JournalTargetV1(source.subject.ownerUid, axis, source.subject.epoch(axis)),
                if (rotate) RecoveryAxisAction.Rotate(checkNotNull(if (axis == PurgeScope.USER) plan.ids.epochs.user else plan.ids.epochs.capability))
                else RecoveryAxisAction.Preserve)
        }
        val proposed = RecoveryRetirementPlan(namespace.before, namespace.after, axisRows, namespace.journal,
            if (source.subject.ownerUid == fixed.before.ownerUid) RecoveryRequestRequirement.RequiredCurrentOwner
            else RecoveryRequestRequirement.NotRequiredDepartedOwner)
        if (!RecoveryRetirementBoundary.validPlan(source, fixed.before, plan.ids.epochs, proposed)) return false // RH.namespacePlanCall
        val user = axisRows.any { it.target.axis == PurgeScope.USER && it.action is RecoveryAxisAction.Rotate }
        val krx = axisRows.any { it.target.axis == PurgeScope.CAPABILITY && it.action is RecoveryAxisAction.Rotate }
        if (namespace.userMayContain != false.takeIf { user }) return false // RH.descriptorUserMarker
        if (namespace.krxMayContain != false.takeIf { krx }) return false // RH.descriptorKrxMarker
        return true
    }

    internal fun eligibility(plan: RecoverHoldPlan, context: LifecycleAttemptContext,
        read: ControlRecordRead.Supported, currentTrackingLifetimeId: String): HoldRecoveryProblem? {
        val runtime = context.holdRecovery ?: return invalid("AttemptContextRequired") // RH.runtime
        val fixed = plan.input
        if (runtime.binding != fixed.binding) return conflict() // RH.binding
        if (read.original[OWNER_UID] != fixed.binding.executor.ownerUid) return conflict() // RH.rawOwner
        ControlLifecycleBoundary.current(fixed.binding.executor, context, read.original)?.let { return HoldRecoveryProblem.Conflict(it) } // RH.currentCall
        if (!ControlLifecycleBoundary.fence(read.original, fixed.before)) return conflict() // RH.fenceCall
        plan.preimageProblem(read)?.let { return HoldRecoveryProblem.Conflict(it) } // RH.preimageCall
        HoldRecoveryBoundary.closureProblem(fixed.source, fixed.closure, runtime, currentTrackingLifetimeId)?.let { return it } // RH.closureCall
        val source = HoldRecoverySource.from(fixed.source) ?: return invalid("InvalidHoldSource")
        RecoveryRetirementBoundary.freshEpochsProblem(source, fixed.before, plan.ids.epochs, read)?.let { return it } // RH.freshCall
        if (source.subject.ownerUid == fixed.before.ownerUid) {
            if (!requestValid(fixed, plan.ids, plan.requestOrder, plan.requestAfter)) return invalid("OrderExhausted") // RH.requestCall
            if (!ControlLifecycleBoundary.createIdAvailable(read, plan.ids.requestId)) return HoldRecoveryProblem.Conflict(ConflictReason.IdCollision) // RH.requestIdCall
        }
        return null
    }

    internal fun buildCandidate(command: CommandRef, plan: RecoverHoldPlan, before: ControlRecordRead.Supported): RecoverHoldCandidateBuild {
        val retirement = plan.retirement ?: return RecoverHoldCandidateBuild.Failed(invalid("RequiredDecisionEffectMissing"))
        val candidate = before.original.toMutablePreferences()
        for (axis in retirement.axes) {
            val action = axis.action
            if (action is RecoveryAxisAction.Rotate) {
                if (axis.target.axis == PurgeScope.USER) {
                    candidate[USER_EPOCH] = action.freshEpoch
                    candidate[MAY_CONTAIN_PREMIUM] = false
                } else {
                    candidate[KRX_EPOCH] = action.freshEpoch
                    candidate[MAY_CONTAIN_KRX] = false
                }
            }
        }
        val existing = shared.canonicalJournal(before.original)
            ?: return RecoverHoldCandidateBuild.Failed(HoldRecoveryProblem.RecoveryRequired(RecoveryReason.JournalMigrationRequired))
        val missing = retirement.journal.filter { it !in existing }
        if (missing.isNotEmpty()) candidate[PURGE_JOURNAL] =
            (listOfNotNull(before.original[PURGE_JOURNAL]) + missing.map { it.encode() }).joinToString("\n")
        for (kind in listOf(ControlKind.HOLD, ControlKind.DEMAND)) {
            val changes = plan.targets.filter { it.target.kind == kind }
            if (changes.isEmpty()) continue
            val rows = before.arrays.getValue(kind).entries.mapNotNull { entry ->
                val id = (entry as? ControlEntryRead.Interpreted)?.value?.id
                val change = changes.singleOrNull { it.target.id == id }
                if (change == null) entry.payload() else change.after?.toPayloadEntry()
            } + changes.filter { it.target.effect == LifecycleEffect.CREATE }.map { checkNotNull(it.after).toPayloadEntry() }
            when (val encoded = shared.encodeChanged(kind, rows)) {
                is NamespaceSettlementTransition.ChangedPayload.Rejected -> return RecoverHoldCandidateBuild.Failed(HoldRecoveryProblem.Rejected(encoded.reason))
                is NamespaceSettlementTransition.ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(kind)] = encoded.text
            }
        }
        val evidence = AppliedEvidence.Lifecycle(command.id, command.ownerTrackingLifetimeId.value,
            LifecycleTransition.RECOVER_HOLD, plan.targets.map { it.target })
        ControlAppliedEvidence.append(candidate, before, evidence, codec)?.let {
            return RecoverHoldCandidateBuild.Failed(HoldRecoveryProblem.Rejected(it))
        }
        return RecoverHoldCandidateBuild.Built(candidate.toPreferences())
    }

    /** Whole effects from external facts, never from proposed retirement/request/floor/target rows. */
    internal fun requiredEffects(input: RecoverHoldInput, ids: RecoverHoldIds, order: LifecycleOrderGrant?,
        before: ControlRecordRead.Supported, after: ControlRecordRead.Supported): Boolean {
        val source = HoldRecoverySource.from(input.source) ?: return false
        val raw = after.original
        if (after.locations(source.hold.id).isNotEmpty()) return false // RH.C01
        if (raw[OWNER_UID] != before.original[OWNER_UID]) return false // RH.C07owner
        if (raw[TEARDOWN_OWED_FOR] != before.original[TEARDOWN_OWED_FOR]) return false // RH.C07teardown
        val journal = shared.canonicalJournal(raw) ?: return false // RH.C05canonical
        for (axis in PurgeScope.entries) {
            val included = axis in source.axes
            val rotate = included && RecoveryRetirementBoundary.rotationRequired(source, axis, input.before)
            val epochKey = if (axis == PurgeScope.USER) USER_EPOCH else KRX_EPOCH
            val markerKey = if (axis == PurgeScope.USER) MAY_CONTAIN_PREMIUM else MAY_CONTAIN_KRX
            val expectedEpoch = if (rotate) (if (axis == PurgeScope.USER) ids.epochs.user else ids.epochs.capability) else before.original[epochKey]
            if (raw[epochKey] != expectedEpoch) return false // RH.C06epoch
            val expectedMarker = if (rotate) false else before.original[markerKey]
            if (raw[markerKey] != expectedMarker) return false // RH.C06marker
            if (included) {
                val exact = PendingPurge(source.subject.ownerUid,
                    source.subject.epoch(axis).takeIf { axis == PurgeScope.USER },
                    source.subject.epoch(axis).takeIf { axis == PurgeScope.CAPABILITY }, setOf(axis))
                if (exact !in journal) return false // RH.C05exact
            }
        }
        if (source.subject.ownerUid == input.before.ownerUid) {
            val request = node(after, ids.requestId, ControlKind.DEMAND)
            if (!requestValid(input, ids, order, request)) return false // RH.C04call
        } else {
            if (after.locations(ids.requestId).isNotEmpty()) return false // RH.departedRequest
        }
        val guardId = input.guard?.let { guard(it)?.id } ?: ids.guardId
        val actualGuard = node(after, guardId, ControlKind.DEMAND)
        if (!HoldFloorPlan.validCandidate(floorInput(input, ids), actualGuard)) return false // RH.C09call
        return true
    }

    internal fun requestValid(input: RecoverHoldInput, ids: RecoverHoldIds, order: LifecycleOrderGrant?, candidate: ControlNode?): Boolean {
        val request = demand(candidate) ?: return false // RH.requestMissing
        val hold = ControlSchema.read(ControlKind.HOLD, input.source) as? RestoredHold ?: return false
        if (request.id != ids.requestId) return false // RH.requestIdLink
        if (request.ownerUid != input.binding.executor.ownerUid) return false // RH.requestOwner
        if (request.binding != input.binding.executor.binding) return false // RH.requestBinding
        if (request.raisedAt.origin != input.binding.executor.originLifetimeId) return false // RH.requestOrigin
        if (!HoldRecoveryBoundary.intentSatisfies(hold, request.intent)) return false // RH.requestIntentCall
        if (order == null) return false // RH.orderMissing
        if (!DemandAuthBoundary.order(order, input.binding, 0)) return false // RH.orderCall
        if (!DemandAuthBoundary.orderValueMatches(request, order)) return false // RH.orderValueCall
        return true
    }

    internal fun validCandidate(command: CommandRef, plan: RecoverHoldPlan, before: ControlRecordRead.Supported, candidate: Preferences): Boolean {
        val after = reader.read(candidate) as? ControlRecordRead.Supported ?: return false // RH.C12read
        if (ControlLifecycleBoundary.recordProblem(after) != null) return false // RH.C12record
        if (ControlLifecycleBoundary.rawProblem(candidate) != null) return false // RH.C12raw
        if (!requiredEffects(plan.input, plan.ids, plan.requestOrder, before, after)) return false // RH.effectsCall
        val expected = expectedTargets(plan.input, plan.ids) ?: return false
        if (!survivorsPreserved(before, after, expected)) return false // RH.survivorsCall
        // A CREATE id must have been absent even if a malformed direct candidate replaces that row.
        for (target in expected.filter { it.effect == LifecycleEffect.CREATE }) {
            if (before.locations(target.id).isNotEmpty()) return false // RH.C05create
        }
        val oldJournal = shared.canonicalJournal(before.original) ?: return false
        val actualJournal = shared.canonicalJournal(candidate) ?: return false
        val source = checkNotNull(HoldRecoverySource.from(plan.input.source))
        val requiredJournal = source.axes.sortedBy { it.ordinal }.map { axis -> PendingPurge(source.subject.ownerUid,
            source.subject.epoch(axis).takeIf { axis == PurgeScope.USER },
            source.subject.epoch(axis).takeIf { axis == PurgeScope.CAPABILITY }, setOf(axis)) }
        if (actualJournal != oldJournal + requiredJournal.filter { it !in oldJournal }) return false // RH.C05preserve
        if (!evidencePreserved(command, before, after, expected)) return false // RH.evidenceCall
        val allowed = before.original.toMutablePreferences()
        val keys = mutableSetOf(ControlRecordKeys.payload(ControlKind.HOLD), ControlLifecycleEvidenceKey)
        if (expected.any { it.kind == ControlKind.DEMAND }) keys += ControlRecordKeys.payload(ControlKind.DEMAND)
        keys += PURGE_JOURNAL
        for (axis in source.axes) if (RecoveryRetirementBoundary.rotationRequired(source, axis, plan.input.before)) {
            keys += if (axis == PurgeScope.USER) USER_EPOCH else KRX_EPOCH
        }
        for (key in keys) {
            val value = candidate[key]
            if (value == null) allowed.remove(key) else allowed[key] = value
        }
        for (axis in source.axes) if (RecoveryRetirementBoundary.rotationRequired(source, axis, plan.input.before)) {
            val key = if (axis == PurgeScope.USER) MAY_CONTAIN_PREMIUM else MAY_CONTAIN_KRX
            val value = candidate[key]
            if (value == null) allowed.remove(key) else allowed[key] = value
        }
        if (allowed != candidate) return false // RH.C11
        return true
    }

    /** Raw payload comparison is separately callable even when an outer schema gate rejects. */
    internal fun survivorsPreserved(before: ControlRecordRead.Supported, after: ControlRecordRead.Supported,
        expected: List<LifecycleTarget>): Boolean {
        for (kind in listOf(ControlKind.HOLD, ControlKind.DEMAND)) {
            val changed = expected.filter { it.kind == kind }.map { it.id }.toSet()
            val oldRows = before.arrays.getValue(kind).entries.filterNot { entryId(it) in changed }.map { it.payload().toString() }
            val newRows = after.arrays.getValue(kind).entries.filterNot { entryId(it) in changed }.map { it.payload().toString() }
            if (oldRows != newRows) return false // RH.C02survivors
        }
        return true
    }

    internal fun evidencePreserved(command: CommandRef, before: ControlRecordRead.Supported,
        after: ControlRecordRead.Supported, expected: List<LifecycleTarget>): Boolean {
        val previousEvidence = (before.metadata as ControlMetadataRead.V2).evidence.entries.map { evidencePayload(it) }
        val actualEvidence = (after.metadata as ControlMetadataRead.V2).evidence.entries.map { evidencePayload(it) }
        val expectedEvidence = AppliedEvidence.Lifecycle(command.id, command.ownerTrackingLifetimeId.value, LifecycleTransition.RECOVER_HOLD, expected)
        if (actualEvidence != previousEvidence + PayloadEntry.Obj(ControlAppliedEvidence.node(expectedEvidence)).toString()) return false // RH.C10
        return true
    }

    /** Independent wire shape from source and fixed facts; unchanged guards are not fake targets. */
    internal fun expectedTargets(input: RecoverHoldInput, ids: RecoverHoldIds): List<LifecycleTarget>? {
        val hold = ControlSchema.read(ControlKind.HOLD, input.source) as? RestoredHold ?: return null
        val subject = when (val p = hold.provenance) {
            is HoldProvenanceV1.Query -> p.started.fence
            is HoldProvenanceV1.Topic -> p.context.access
        }
        val targets = mutableListOf(LifecycleTarget(ControlKind.HOLD, hold.id, LifecycleEffect.REMOVE))
        if (subject.ownerUid == input.before.ownerUid) targets += LifecycleTarget(ControlKind.DEMAND, ids.requestId, LifecycleEffect.CREATE)
        if (hold.floor != null) {
            val old = input.guard?.let { guard(it) ?: return null }
            val left = old?.floor?.remainingAt(input.mergeNow) ?: 0
            val right = hold.floor.remainingAt(input.mergeNow) ?: return null
            val next = FloorV1(input.mergeNow.bootId, input.mergeNow.elapsedMillis, maxOf(left, right), input.binding.executor.originLifetimeId)
            if (old?.floor != next) targets += LifecycleTarget(ControlKind.DEMAND, old?.id ?: ids.guardId,
                if (old == null) LifecycleEffect.CREATE else LifecycleEffect.REPLACE)
        }
        return targets
    }

    private fun floorInput(input: RecoverHoldInput, ids: RecoverHoldIds) = HoldFloorInput(input.source, input.guard,
        input.mergeNow, input.binding.executor.originLifetimeId, ids.guardId)
    private fun node(read: ControlRecordRead.Supported, id: String, kind: ControlKind): ControlNode? =
        (read.locations(id).singleOrNull()?.takeIf { it.first == kind }?.second as? ControlEntryRead.Interpreted)?.original
    private fun entryId(entry: ControlEntryRead): String? = (entry as? ControlEntryRead.Interpreted)?.value?.id
    private fun evidencePayload(entry: ControlEvidenceEntryRead): String = when (entry) {
        is ControlEvidenceEntryRead.Interpreted -> entry.original.toPayloadEntry().toString()
        is ControlEvidenceEntryRead.Uninterpretable -> entry.original.toString()
    }
    private fun invalid(detail: String) = HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail))
    private fun conflict() = HoldRecoveryProblem.Conflict(ConflictReason.TargetChanged)
    private val ControlLifecycleEvidenceKey get() = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
}

internal sealed interface RecoverHoldCandidateBuild {
    data class Built(val candidate: Preferences) : RecoverHoldCandidateBuild
    data class Failed(val problem: HoldRecoveryProblem) : RecoverHoldCandidateBuild
}
