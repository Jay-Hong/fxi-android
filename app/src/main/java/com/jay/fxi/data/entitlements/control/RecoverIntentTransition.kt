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
internal class RecoverIntentTransition(private val codec: ControlPayloadCodec) {
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
        val plan = input.recoverIntent ?: return fail(invalid("InvalidLifecycleDescriptor")) // RI.planRequired
        plan.preparationProblem?.let { return fail(it) } // RI.prepareCall
        if (!validDescriptor(plan, input)) return fail(invalid("InvalidLifecycleDescriptor")) // RI.descriptorCall
        eligibility(plan, context, read, command.ownerTrackingLifetimeId.value)?.let { return fail(it) } // RI.eligibilityCall
        val candidate = when (val built = buildCandidate(command, plan, read)) {
            is RecoverIntentCandidateBuild.Failed -> return fail(built.problem)
            is RecoverIntentCandidateBuild.Built -> built.candidate
        }
        if (!validCandidate(command, plan, read, candidate)) return fail(invalid("RequiredDecisionEffectMissing")) // RI.candidateCall
        val after = reader.read(candidate) as ControlRecordRead.Supported
        return RecordTransactionDecision.Confirm(candidate, Outcome.Positive(ConfirmedEffect.AppliedThisAttempt,
            input.targets.map { it.target.id }, ControlLifecycleConfirmation(codec).receipt(input, after)))
    }

    /** Does not compare a descriptor with plan.descriptor(): that would share builder mistakes. */
    internal fun validDescriptor(plan: RecoverIntentPlan, input: ControlLifecycleDescriptor): Boolean {
        val fixed = plan.input
        if (input.demandAuth != null || input.removeEmptyGuard != null || input.recoverHold != null) return false // RI.descriptorOtherWriter
        if (input.recoverIntent !== plan) return false // RI.namedPlan
        if (input.operationId != plan.ids.operationId) return false // RI.descriptorId
        if (input.transition != LifecycleTransition.RECOVER_INTENT) return false // RI.descriptorTransition
        if (input.executor != fixed.binding.executor) return false // RI.descriptorExecutor
        val expected = expectedTargets(fixed, plan.ids) ?: return false // RI.descriptorExpectedTargets
        if (input.targets.map { it.target } != expected) return false // RI.descriptorTargets
        for (row in input.targets) {
            when (row.role) {
                LifecycleRole.RECOVERY_INTENT -> {
                    if (row.before?.toPayloadEntry() != fixed.source.toPayloadEntry()) return false // RI.descriptorSource
                    if (row.after != null) return false // RI.descriptorRemoved
                }
                LifecycleRole.REQUEST -> {
                    if (row.before != null) return false // RI.descriptorRequestBefore
                    if (!requestValid(fixed, plan.ids, plan.requestOrder, row.after)) return false // RI.descriptorRequestCall
                }
                LifecycleRole.GUARD, LifecycleRole.HOLD -> return false // RI.descriptorUnexpectedRole
            }
            val role = if (row.target.kind == ControlKind.RECOVERY_INTENT) LifecycleRole.RECOVERY_INTENT else LifecycleRole.REQUEST
            if (row.role != role) return false // RI.descriptorRole
        }
        if (input.requiredUnchanged.isNotEmpty()) return false // RI.descriptorUnchanged
        val namespace = input.namespace ?: return false // RI.descriptorNamespace
        if (namespace.before != fixed.before) return false // RI.descriptorBefore
        val source = IntentRecoverySource.from(fixed.source) ?: return false // RI.descriptorSourceSchema
        if (RecoveryRetirementBoundary.inputProblem(source, fixed.before, plan.ids.epochs) != null) return false // RI.descriptorRetirementInput
        // Pure validator takes the descriptor values, independently of cached plan.retirement.
        val axisRows = source.axes.sortedBy { it.ordinal }.map { axis ->
            val rotate = RecoveryRetirementBoundary.rotationRequired(source, axis, fixed.before)
            RecoveryAxisRetirement(JournalTargetV1(source.ownerUid, axis, source.targetEpoch(axis)),
                if (rotate) RecoveryAxisAction.Rotate(checkNotNull(if (axis == PurgeScope.USER) plan.ids.epochs.user else plan.ids.epochs.capability))
                else RecoveryAxisAction.Preserve)
        }
        val proposed = RecoveryRetirementPlan(namespace.before, namespace.after, axisRows, namespace.journal,
            if (source.ownerUid == fixed.before.ownerUid) RecoveryRequestRequirement.RequiredCurrentOwner
            else RecoveryRequestRequirement.NotRequiredDepartedOwner)
        if (!RecoveryRetirementBoundary.validPlan(source, fixed.before, plan.ids.epochs, proposed)) return false // RI.namespacePlanCall
        val user = axisRows.any { it.target.axis == PurgeScope.USER && it.action is RecoveryAxisAction.Rotate }
        val krx = axisRows.any { it.target.axis == PurgeScope.CAPABILITY && it.action is RecoveryAxisAction.Rotate }
        if (namespace.userMayContain != false.takeIf { user }) return false // RI.descriptorUserMarker
        if (namespace.krxMayContain != false.takeIf { krx }) return false // RI.descriptorKrxMarker
        return true
    }

    internal fun eligibility(plan: RecoverIntentPlan, context: LifecycleAttemptContext,
        read: ControlRecordRead.Supported, currentTrackingLifetimeId: String): HoldRecoveryProblem? {
        val runtime = context.intentRecovery ?: return invalid("AttemptContextRequired") // RI.runtime
        val fixed = plan.input
        if (runtime.binding != fixed.binding) return conflict() // RI.binding
        if (read.original[OWNER_UID] != fixed.binding.executor.ownerUid) return conflict() // RI.rawOwner
        ControlLifecycleBoundary.current(fixed.binding.executor, context, read.original)?.let { return HoldRecoveryProblem.Conflict(it) } // RI.currentCall
        if (!ControlLifecycleBoundary.fence(read.original, fixed.before)) return conflict() // RI.fenceCall
        plan.preimageProblem(read)?.let { return HoldRecoveryProblem.Conflict(it) } // RI.preimageCall
        HoldRecoveryBoundary.closureProblem(fixed.source, fixed.closure, runtime, currentTrackingLifetimeId)?.let { return it } // RI.closureCall
        val source = IntentRecoverySource.from(fixed.source) ?: return invalid("InvalidIntentSource") // RI.eligibilitySource
        RecoveryRetirementBoundary.freshEpochsProblem(source, fixed.before, plan.ids.epochs, read)?.let { return it } // RI.freshCall
        if (source.ownerUid == fixed.before.ownerUid) { // RI.currentOwnerRequest
            if (!requestValid(fixed, plan.ids, plan.requestOrder, plan.requestAfter)) return invalid("OrderExhausted") // RI.requestCall
            if (!ControlLifecycleBoundary.createIdAvailable(read, plan.ids.requestId)) return HoldRecoveryProblem.Conflict(ConflictReason.IdCollision) // RI.requestIdCall
        }
        if (!ControlLifecycleBoundary.commandIdAvailable(read, plan.ids.operationId))
            return HoldRecoveryProblem.Conflict(ConflictReason.OperationIdCollision) // RI.operationIdCall
        return null
    }

    internal fun buildCandidate(command: CommandRef, plan: RecoverIntentPlan, before: ControlRecordRead.Supported): RecoverIntentCandidateBuild {
        val retirement = plan.retirement ?: return RecoverIntentCandidateBuild.Failed(invalid("RequiredDecisionEffectMissing")) // RI.buildRetirement
        val candidate = before.original.toMutablePreferences()
        for (axis in retirement.axes) {
            val action = axis.action
            if (action is RecoveryAxisAction.Rotate) { // RI.buildRotate
                if (axis.target.axis == PurgeScope.USER) { // RI.buildAxis
                    candidate[USER_EPOCH] = action.freshEpoch
                    candidate[MAY_CONTAIN_PREMIUM] = false
                } else {
                    candidate[KRX_EPOCH] = action.freshEpoch
                    candidate[MAY_CONTAIN_KRX] = false
                }
            }
        }
        val existing = shared.canonicalJournal(before.original)
            ?: return RecoverIntentCandidateBuild.Failed(HoldRecoveryProblem.RecoveryRequired(RecoveryReason.JournalMigrationRequired)) // RI.buildJournal
        val missing = retirement.journal.filter { it !in existing }
        if (missing.isNotEmpty()) candidate[PURGE_JOURNAL] =
            (listOfNotNull(before.original[PURGE_JOURNAL]) + missing.map { it.encode() }).joinToString("\n") // RI.buildMissingJournal
        for (kind in listOf(ControlKind.RECOVERY_INTENT, ControlKind.DEMAND)) {
            val changes = plan.targets.filter { it.target.kind == kind }
            if (changes.isEmpty()) continue // RI.buildUnchangedArray
            val rows = before.arrays.getValue(kind).entries.mapNotNull { entry ->
                val id = (entry as? ControlEntryRead.Interpreted)?.value?.id
                val change = changes.singleOrNull { it.target.id == id }
                if (change == null) entry.payload() else change.after?.toPayloadEntry()
            } + changes.filter { it.target.effect == LifecycleEffect.CREATE }.map { checkNotNull(it.after).toPayloadEntry() }
            when (val encoded = shared.encodeChanged(kind, rows)) {
                is NamespaceSettlementTransition.ChangedPayload.Rejected -> return RecoverIntentCandidateBuild.Failed(HoldRecoveryProblem.Rejected(encoded.reason)) // RI.buildPayloadLimit
                is NamespaceSettlementTransition.ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(kind)] = encoded.text
            }
        }
        val evidence = AppliedEvidence.Lifecycle(command.id, command.ownerTrackingLifetimeId.value,
            LifecycleTransition.RECOVER_INTENT, plan.targets.map { it.target })
        ControlAppliedEvidence.append(candidate, before, evidence, codec)?.let {
            return RecoverIntentCandidateBuild.Failed(HoldRecoveryProblem.Rejected(it)) // RI.buildEvidenceLimit
        }
        return RecoverIntentCandidateBuild.Built(candidate.toPreferences())
    }

    /** Whole effects from external facts, never from proposed retirement/request/target rows. */
    internal fun requiredEffects(input: RecoverIntentInput, ids: RecoverIntentIds, order: LifecycleOrderGrant?,
        before: ControlRecordRead.Supported, after: ControlRecordRead.Supported): Boolean {
        val source = IntentRecoverySource.from(input.source) ?: return false // RI.effectsSource
        val raw = after.original
        if (after.locations(source.intent.id).isNotEmpty()) return false // RI.C01
        if (raw[OWNER_UID] != before.original[OWNER_UID]) return false // RI.C07owner
        if (raw[TEARDOWN_OWED_FOR] != before.original[TEARDOWN_OWED_FOR]) return false // RI.C07teardown
        val journal = shared.canonicalJournal(raw) ?: return false // RI.C05canonical
        for (axis in PurgeScope.entries) {
            val included = axis in source.axes
            val rotate = included && RecoveryRetirementBoundary.rotationRequired(source, axis, input.before)
            val epochKey = if (axis == PurgeScope.USER) USER_EPOCH else KRX_EPOCH
            val markerKey = if (axis == PurgeScope.USER) MAY_CONTAIN_PREMIUM else MAY_CONTAIN_KRX
            val expectedEpoch = if (rotate) (if (axis == PurgeScope.USER) ids.epochs.user else ids.epochs.capability) else before.original[epochKey]
            if (raw[epochKey] != expectedEpoch) return false // RI.C06epoch
            val expectedMarker = if (rotate) false else before.original[markerKey]
            if (raw[markerKey] != expectedMarker) return false // RI.C06marker
            if (included) {
                val exact = PendingPurge(source.ownerUid,
                    source.targetEpoch(axis).takeIf { axis == PurgeScope.USER },
                    source.targetEpoch(axis).takeIf { axis == PurgeScope.CAPABILITY }, setOf(axis))
                if (exact !in journal) return false // RI.C05exact
            }
        }
        if (source.ownerUid == input.before.ownerUid) { // RI.effectsCurrentOwner
            val request = node(after, ids.requestId, ControlKind.DEMAND)
            if (!requestValid(input, ids, order, request)) return false // RI.C04call
        } else {
            // No row is created for a departed owner, even when an existing row uses ids.requestId.
            val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
            if (raw[demandKey] != before.original[demandKey]) return false // RI.departedRequestsPreserved
        }
        return true
    }

    internal fun requestValid(input: RecoverIntentInput, ids: RecoverIntentIds, order: LifecycleOrderGrant?, candidate: ControlNode?): Boolean {
        val request = demand(candidate) ?: return false // RI.requestMissing
        val source = IntentRecoverySource.from(input.source) ?: return false // RI.requestSource
        if (request.id != ids.requestId) return false // RI.requestIdLink
        if (request.ownerUid != input.binding.executor.ownerUid) return false // RI.requestOwner
        if (request.binding != input.binding.executor.binding) return false // RI.requestBinding
        if (request.raisedAt.origin != input.binding.executor.originLifetimeId) return false // RI.requestOrigin
        if (!RecoveryIntentLowerBound.intentSatisfies(source.intent, request.intent)) return false // RI.requestIntentCall
        if (order == null) return false // RI.orderMissing
        if (!DemandAuthBoundary.order(order, input.binding, 0)) return false // RI.orderCall
        if (!DemandAuthBoundary.orderValueMatches(request, order)) return false // RI.orderValueCall
        return true
    }

    internal fun validCandidate(command: CommandRef, plan: RecoverIntentPlan, before: ControlRecordRead.Supported, candidate: Preferences): Boolean {
        val after = reader.read(candidate) as? ControlRecordRead.Supported ?: return false // RI.C12read
        if (ControlLifecycleBoundary.recordProblem(after) != null) return false // RI.C12record
        if (ControlLifecycleBoundary.rawProblem(candidate) != null) return false // RI.C12raw
        val source = IntentRecoverySource.from(plan.input.source) ?: return false // RI.candidateSource
        if (!ControlLifecycleBoundary.fence(before.original, plan.input.before)) return false // RI.candidateBefore
        if (plan.preimageProblem(before) != null) return false // RI.candidatePreimage
        if (RecoveryRetirementBoundary.freshEpochsProblem(source, plan.input.before, plan.ids.epochs, before) != null) return false // RI.candidateFresh
        if (!ControlLifecycleBoundary.commandIdAvailable(before, command.id)) return false // RI.candidateOperationId
        if (!requiredEffects(plan.input, plan.ids, plan.requestOrder, before, after)) return false // RI.effectsCall
        val expected = expectedTargets(plan.input, plan.ids) ?: return false // RI.candidateExpectedTargets
        if (!survivorsPreserved(before, after, expected)) return false // RI.survivorsCall
        // A CREATE id must have been absent even if a malformed direct candidate replaces that row.
        for (target in expected.filter { it.effect == LifecycleEffect.CREATE }) {
            if (before.locations(target.id).isNotEmpty()) return false // RI.C05create
        }
        val oldJournal = shared.canonicalJournal(before.original) ?: return false // RI.candidateOldJournal
        val actualJournal = shared.canonicalJournal(candidate) ?: return false // RI.candidateJournal
        val requiredJournal = source.axes.sortedBy { it.ordinal }.map { axis -> PendingPurge(source.ownerUid,
            source.targetEpoch(axis).takeIf { axis == PurgeScope.USER },
            source.targetEpoch(axis).takeIf { axis == PurgeScope.CAPABILITY }, setOf(axis)) }
        if (actualJournal != oldJournal + requiredJournal.filter { it !in oldJournal }) return false // RI.C05preserve
        if (!evidencePreserved(command, before, after, expected)) return false // RI.evidenceCall
        val allowed = before.original.toMutablePreferences()
        val keys = mutableSetOf(ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT), ControlLifecycleEvidenceKey)
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
        if (allowed != candidate) return false // RI.C11
        return true
    }

    /** Raw payload comparison is separately callable even when an outer schema gate rejects. */
    internal fun survivorsPreserved(before: ControlRecordRead.Supported, after: ControlRecordRead.Supported,
        expected: List<LifecycleTarget>): Boolean {
        for (kind in listOf(ControlKind.RECOVERY_INTENT, ControlKind.DEMAND)) {
            val changed = expected.filter { it.kind == kind }.map { it.id }.toSet()
            val oldRows = before.arrays.getValue(kind).entries.filterNot { entryId(it) in changed }.map { it.payload().toString() }
            val newRows = after.arrays.getValue(kind).entries.filterNot { entryId(it) in changed }.map { it.payload().toString() }
            if (oldRows != newRows) return false // RI.C02survivors
        }
        return true
    }

    internal fun evidencePreserved(command: CommandRef, before: ControlRecordRead.Supported,
        after: ControlRecordRead.Supported, expected: List<LifecycleTarget>): Boolean {
        val previousEvidence = (before.metadata as ControlMetadataRead.V2).evidence.entries.map { evidencePayload(it) }
        val actualEvidence = (after.metadata as ControlMetadataRead.V2).evidence.entries.map { evidencePayload(it) }
        val expectedEvidence = AppliedEvidence.Lifecycle(command.id, command.ownerTrackingLifetimeId.value, LifecycleTransition.RECOVER_INTENT, expected)
        if (actualEvidence != previousEvidence + PayloadEntry.Obj(ControlAppliedEvidence.node(expectedEvidence)).toString()) return false // RI.C10
        return true
    }

    /** Independent wire shape from source and fixed owner; no guard or unchanged targets. */
    internal fun expectedTargets(input: RecoverIntentInput, ids: RecoverIntentIds): List<LifecycleTarget>? {
        val source = IntentRecoverySource.from(input.source) ?: return null // RI.targetsSource
        return buildList {
            add(LifecycleTarget(ControlKind.RECOVERY_INTENT, source.intent.id, LifecycleEffect.REMOVE))
            if (source.ownerUid == input.before.ownerUid) // RI.targetsCurrentOwner
                add(LifecycleTarget(ControlKind.DEMAND, ids.requestId, LifecycleEffect.CREATE))
        }
    }

    private fun node(read: ControlRecordRead.Supported, id: String, kind: ControlKind): ControlNode? =
        (read.locations(id).singleOrNull()?.takeIf { it.first == kind }?.second as? ControlEntryRead.Interpreted)?.original
    private fun entryId(entry: ControlEntryRead?): String? = (entry as? ControlEntryRead.Interpreted)?.value?.id
    private fun evidencePayload(entry: ControlEvidenceEntryRead): String = when (entry) {
        is ControlEvidenceEntryRead.Interpreted -> entry.original.toPayloadEntry().toString()
        is ControlEvidenceEntryRead.Uninterpretable -> entry.original.toString()
    }
    private fun invalid(detail: String) = HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail))
    private fun conflict() = HoldRecoveryProblem.Conflict(ConflictReason.TargetChanged)
    private val ControlLifecycleEvidenceKey get() = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
}

internal sealed interface RecoverIntentCandidateBuild {
    data class Built(val candidate: Preferences) : RecoverIntentCandidateBuild
    data class Failed(val problem: HoldRecoveryProblem) : RecoverIntentCandidateBuild
}
