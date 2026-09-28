package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.RecordTransactionResult
import com.jay.fxi.data.entitlements.StoreOp
import java.util.Collections

internal class CompletionHandoff(
    val command: ExactCommandBinding,
    val responsibilityOwner: ResponsibilityOwner,
    slots: List<SlotHandoff>
) {
    val slots: List<SlotHandoff> =
        Collections.unmodifiableList(slots.toList())
}

internal data class ResponsibilityOwner(
    val trackingLifetime: OwnerTrackingLifetimeId,
    val ownerKey: String
)

internal data class SlotHandoff(
    val key: RequiredObligationKey,
    val disposition: HandoffDisposition
)

internal sealed interface DestinationLocator {
    data class Payload(val kind: ControlKind, val id: String) : DestinationLocator
    data class Journal(val key: JournalTargetV1) : DestinationLocator
    data class Guard(val id: String, val part: GuardPart) : DestinationLocator
}

internal enum class GuardPart { FLOOR, AUTH }

internal sealed interface HandoffDisposition {
    class DurablyOwned(
        val destination: DestinationLocator,
        linkChain: List<NamedTransferLink>,
        val priorWrite: PriorStorageConfirmation?
    ) : HandoffDisposition {
        val linkChain: List<NamedTransferLink> =
            Collections.unmodifiableList(linkChain.toList())
    }

    class CompletedAndConsumed(
        val completion: ComponentCompletion,
        consumedReceipts: List<ReceiptIdentity>
    ) : HandoffDisposition {
        val consumedReceipts: List<ReceiptIdentity> =
            Collections.unmodifiableList(consumedReceipts.toList())
    }
}

internal data class ComponentCompletion(
    val sourceSubject: ObligationSubject
)

internal sealed interface ReceiptIdentity {
    data class Lifecycle(
        val commandId: String,
        val transition: LifecycleTransition,
        val queryId: String?,
        val target: LifecycleTarget?
    ) : ReceiptIdentity

    data class Rotation(val operationId: String) : ReceiptIdentity

    data class Settlement(
        val operationId: String,
        val transition: HandoverSettlementTransition
    ) : ReceiptIdentity
}

internal sealed interface TypedSourceTuple {
    val responsibilityOwner: ResponsibilityOwner

    data class Request(
        override val responsibilityOwner: ResponsibilityOwner,
        val preimage: ControlNode,
        val parsed: DemandV1,
        val minimumIntent: com.jay.fxi.data.entitlements.RefreshIntent,
        val minimumOrder: EventOrderV1
    ) : TypedSourceTuple

    data class HoldFloor(
        override val responsibilityOwner: ResponsibilityOwner,
        val source: FloorSource,
        val oldGuard: ControlNode?,
        val mergeNow: BootReading,
        val executorOrigin: LifetimeId
    ) : TypedSourceTuple

    data class GuardFloor(
        override val responsibilityOwner: ResponsibilityOwner,
        val preimage: ControlNode,
        val parsed: ScheduleGuardV1
    ) : TypedSourceTuple

    /** 6-4bC1d-4b: the AUTH part of a stored guard row [preimage]. */
    data class GuardAuth(
        override val responsibilityOwner: ResponsibilityOwner,
        val preimage: ControlNode,
        val parsed: ScheduleGuardV1
    ) : TypedSourceTuple
}

internal sealed interface TypedDestinationTuple {
    val locator: DestinationLocator
    val row: ControlNode

    data class Request(
        override val locator: DestinationLocator.Payload,
        override val row: ControlNode,
        val parsed: DemandV1
    ) : TypedDestinationTuple

    data class GuardFloor(
        override val locator: DestinationLocator.Guard,
        override val row: ControlNode,
        val parsed: ScheduleGuardV1
    ) : TypedDestinationTuple

    data class GuardAuth(
        override val locator: DestinationLocator.Guard,
        override val row: ControlNode,
        val parsed: ScheduleGuardV1
    ) : TypedDestinationTuple
}

/** A Caller/Recovery auth rewrite can carry an existing floor only when its stored literal is untouched. */
private fun guardAuthRewriteKeepsFloor(before: ControlNode, after: ControlNode): Boolean {
    val old = guard(before) ?: return false
    val next = guard(after) ?: return false
    val oldFields = before.toPayloadEntry().fields
    val nextFields = after.toPayloadEntry().fields
    return old.id == next.id && old.auth != next.auth && old.floor != null && old.floor == next.floor &&
        oldFields["floor"] == nextFields["floor"] &&
        oldFields.filterKeys { it != "auth" && it != "floor" } ==
            nextFields.filterKeys { it != "auth" && it != "floor" }
}

private fun sameAuthScope(before: AuthSnapshotV1, after: AuthSnapshotV1): Boolean =
    before.ownerUid == after.ownerUid && before.binding == after.binding &&
        before.originLifetimeId == after.originLifetimeId &&
        before.authGeneration == after.authGeneration

internal sealed interface RetainedDestinationTuple {
    val locator: DestinationLocator

    data class Payload(
        override val locator: DestinationLocator.Payload,
        val row: ControlNode
    ) : RetainedDestinationTuple

    data class Guard(
        override val locator: DestinationLocator.Guard,
        val row: ControlNode
    ) : RetainedDestinationTuple

    data class Journal(
        override val locator: DestinationLocator.Journal
    ) : RetainedDestinationTuple

    class RetirementJournal(
        override val locator: DestinationLocator.Journal,
        val scope: RetirementScope,
        val observedAfter: FenceV1,
        settledSeals: List<ControlNode>
    ) : RetainedDestinationTuple {
        val settledSeals: List<ControlNode> = Collections.unmodifiableList(settledSeals.toList())
    }
}

private data class RetirementSealSource(
    val original: ControlNode,
    val seal: SealV1,
    val witness: SettlementEvidenceV1
)

/** Rebuild the axis and its indexed SOURCE facts from the fixed writer input. */
private fun retirementSealSources(requirement: SlotRequirement.Required,
    scope: RetirementScope, role: ObligationRole): List<RetirementSealSource>? {
    val sources = requirement.fixedSources
    val rotation = sources.mapNotNull { (it.fact as? FixedSourceFact.RotationInput)?.value }
    val settlement = sources.mapNotNull { (it.fact as? FixedSourceFact.SettlementInput)?.value }
    if (sources.any { fixed ->
            (fixed.fact is FixedSourceFact.RotationInput &&
                fixed.location != FixedInputLocation(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.WHOLE)) ||
                (fixed.fact is FixedSourceFact.SettlementInput &&
                    fixed.location != FixedInputLocation(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE))
        }) return null
    val root: FixedInputRoot
    val selected: List<Pair<Int, RetirementSealSource>>
    when {
        role == ObligationRole.Rotation && rotation.size == 1 && settlement.isEmpty() -> {
            val fixed = rotation.single()
            if (scope.before != fixed.before || scope.after != fixed.after) return null
            root = FixedInputRoot.ROTATION_TARGET
            selected = fixed.sealTargets.withIndex().filter { it.value.seal.key.axis == scope.journalKey.axis }
                .map { (index, target) -> index to RetirementSealSource(target.original, target.seal,
                    fixed.witness(target.seal)) }
        }
        role == ObligationRole.Settlement(HandoverSettlementTransition.CURRENT_NULL) &&
            settlement.size == 1 && rotation.isEmpty() -> {
            val fixed = settlement.single() as? CurrentNullSettlement ?: return null
            if (scope.before != fixed.before || scope.after != fixed.after ||
                scope.journalKey.axis !in fixed.axes) return null
            root = FixedInputRoot.SETTLEMENT_TARGET
            val writer = CurrentNullSettlementTransition(ControlPayloadCodec())
            selected = fixed.targets.withIndex().filter { it.value.seal.key.axis == scope.journalKey.axis }
                .map { (index, target) -> index to RetirementSealSource(target.original, target.seal,
                    writer.witness(fixed, target.seal)) }
        }
        else -> return null
    }
    if (selected.isEmpty() || scope.sourceId != selected.first().second.seal.id) return null
    val representative = selected.first().second.seal
    val epoch = if (root == FixedInputRoot.ROTATION_TARGET) representative.key.epoch else null
    if (scope.journalKey != JournalTargetV1(representative.key.ownerUid, representative.key.axis, epoch) ||
        selected.map { it.second.seal.id }.distinct().size != selected.size) return null
    val fixedRows = sources.filter { it.location.root == root }
    if (selected.any { it.second.seal.settlement != null } ||
        sources.any { it.location.root in setOf(FixedInputRoot.ROTATION_TARGET, FixedInputRoot.SETTLEMENT_TARGET) &&
            it.location.root != root } ||
        fixedRows.size != selected.size || fixedRows.zip(selected).any { (fixed, indexed) ->
            val (index, expected) = indexed
            val fact = fixed.fact as? FixedSourceFact.Node
            fixed.location != FixedInputLocation(root, index, FixedInputFacet.SOURCE) ||
                fact == null || fact.kind != ControlKind.SEAL ||
                fact.value.toPayloadEntry() != expected.original.toPayloadEntry() ||
                ((ControlObligations.read(ControlKind.SEAL, fact.value) as? ControlEntryRead.Interpreted)
                    ?.value as? SealV1)?.id != expected.seal.id
        }) return null
    return selected.map { it.second }
}

internal sealed interface ConfirmationBinding {
    class LifecycleOutput(
        val command: CommandRef,
        val fixed: ControlLifecycleDescriptor,
        val outputTarget: LifecycleTarget,
        val output: TypedDestinationTuple,
        val effect: ConfirmedEffect,
        val observation: LifecycleTargetObservation,
        val storageEvidence: RecordTransactionEvidence
    ) : ConfirmationBinding

    class RetainedSource(
        val slot: RequiredSlot,
        val observed: RetainedDestinationTuple,
        val storageEvidence: RecordTransactionEvidence
    ) : ConfirmationBinding

    /** 6-4bC1d-1(b): in-memory confirmation of a Mutations recordFloor action's stored guard output (never written to the wire). */
    class MutationFloorOutput(
        val command: CommandRef,
        val actionIndex: Int,
        val before: ControlNode,
        val output: TypedDestinationTuple.GuardFloor,
        val effect: ConfirmedEffect,
        val storageEvidence: RecordTransactionEvidence
    ) : ConfirmationBinding
}

internal enum class LifecycleConfirmationFailure {
    REF_OR_DESCRIPTOR_MISMATCH,
    UNSUPPORTED_TRANSITION_OR_TARGET,
    EFFECT_NOT_ELIGIBLE,
    RECEIPT_MISSING,
    RECEIPT_MISMATCH,
    APPLIED_MISSING_OR_MISMATCH,
    SNAPSHOT_UNINTERPRETABLE,
    SNAPSHOT_OUTPUT_MISMATCH,
    NAMED_EFFECT_MISMATCH
}

internal sealed interface LifecycleOutputConfirmationResult {
    data class Issued(val value: PriorStorageConfirmation) : LifecycleOutputConfirmationResult
    data class Rejected(val reason: LifecycleConfirmationFailure) : LifecycleOutputConfirmationResult
}

internal enum class MutationFloorConfirmationFailure {
    REF_OR_ACTION_MISMATCH,
    UNSUPPORTED_ACTION,
    EFFECT_NOT_ELIGIBLE,
    APPLIED_MISSING_OR_MISMATCH,
    SNAPSHOT_UNINTERPRETABLE,
    SNAPSHOT_OUTPUT_MISMATCH,
    FLOOR_RULE_MISMATCH
}

internal sealed interface MutationFloorOutputConfirmationResult {
    data class Issued(val value: PriorStorageConfirmation) : MutationFloorOutputConfirmationResult
    data class Rejected(val reason: MutationFloorConfirmationFailure) : MutationFloorOutputConfirmationResult
}

internal enum class RetainedConfirmationFailure {
    NOT_A_REQUIRED_SOURCE,
    NO_EXACT_RETAINED_ROW,
    SUBJECT_OR_BOUND_MISMATCH,
    OBSERVATION_RECORD_MISMATCH
}

internal sealed interface RetainedSourceConfirmationResult {
    data class Issued(val value: PriorStorageConfirmation) : RetainedSourceConfirmationResult
    data class Rejected(val reason: RetainedConfirmationFailure) : RetainedSourceConfirmationResult
}

internal class PriorStorageConfirmation private constructor(val binding: ConfirmationBinding) {
    companion object {
        fun confirmLifecycleOutput(
            exactCommand: CommandRef,
            fixed: ControlLifecycleDescriptor,
            confirmed: ControlStoreResult.Confirmed,
            outputTarget: LifecycleTarget,
            authPart: Boolean = false
        ): LifecycleOutputConfirmationResult =
            confirmOutput(exactCommand, fixed, confirmed, outputTarget, false, authPart = authPart)

        /** The sole unchanged RECOVER_HOLD guard is a real, observed output of that command. */
        fun confirmRecoverHoldUnchangedGuardOutput(
            exactCommand: CommandRef,
            fixed: ControlLifecycleDescriptor,
            confirmed: ControlStoreResult.Confirmed,
            outputTarget: LifecycleTarget,
            authPart: Boolean = false
        ): LifecycleOutputConfirmationResult =
            confirmOutput(exactCommand, fixed, confirmed, outputTarget, true, authPart = authPart)

        private fun confirmOutput(exactCommand: CommandRef, fixed: ControlLifecycleDescriptor,
            confirmed: ControlStoreResult.Confirmed, outputTarget: LifecycleTarget,
            unchangedGuard: Boolean, floorGuard: Boolean = false,
            authPart: Boolean = false): LifecycleOutputConfirmationResult {
            fun reject(reason: LifecycleConfirmationFailure) = LifecycleOutputConfirmationResult.Rejected(reason)
            val body = exactCommand.captureStateAndBody().body as? ControlCommandBody.Lifecycle
            if (confirmed.command !== exactCommand || body == null || body.input !== fixed ||
                exactCommand.id != fixed.operationId)
                return reject(LifecycleConfirmationFailure.REF_OR_DESCRIPTOR_MISMATCH)

            val selectedUnchanged = unchangedGuard || (floorGuard &&
                fixed.transition == LifecycleTransition.SETTLE_QUERY &&
                fixed.targets.none { it.target == outputTarget } &&
                fixed.requiredUnchanged.any { it.role == LifecycleRole.GUARD && it.target == outputTarget })
            val outputFixed = if (selectedUnchanged) fixed.requiredUnchanged.singleOrNull {
                it.role == LifecycleRole.GUARD && it.target == outputTarget
            } else fixed.targets.singleOrNull { it.target == outputTarget }
            val supported = outputFixed != null && if (floorGuard) {
                outputFixed.role == LifecycleRole.GUARD && outputTarget.kind == ControlKind.DEMAND &&
                    outputTarget.effect == LifecycleEffect.REPLACE &&
                    outputFixed.before != null && outputFixed.after != null &&
                    when (fixed.transition) {
                        LifecycleTransition.SETTLE_QUERY -> !selectedUnchanged ||
                            (fixed.requiredUnchanged.count { it.role == LifecycleRole.GUARD } == 1 &&
                                fixed.targets.none { it.role == LifecycleRole.GUARD } &&
                                outputFixed.before.toPayloadEntry() == outputFixed.after.toPayloadEntry())
                        LifecycleTransition.END_AUTH_BINDING -> !selectedUnchanged
                        else -> false
                    }
            } else if (unchangedGuard) {
                fixed.transition == LifecycleTransition.RECOVER_HOLD &&
                    fixed.requiredUnchanged.count { it.role == LifecycleRole.GUARD } == 1 &&
                    fixed.targets.none { it.role == LifecycleRole.GUARD || it.target == outputTarget } &&
                    outputTarget.kind == ControlKind.DEMAND && outputTarget.effect == LifecycleEffect.REPLACE &&
                    outputFixed.before != null && outputFixed.after != null &&
                    outputFixed.before.toPayloadEntry() == outputFixed.after.toPayloadEntry()
            } else when (fixed.transition) {
                LifecycleTransition.REBIND_REQUESTS ->
                    outputFixed.role == LifecycleRole.REQUEST && outputTarget.kind == ControlKind.DEMAND &&
                        outputTarget.effect == LifecycleEffect.REPLACE && outputFixed.before != null
                LifecycleTransition.END_AUTH_BINDING ->
                    (outputFixed.role == LifecycleRole.REQUEST || outputFixed.role == LifecycleRole.GUARD) &&
                        outputTarget.kind == ControlKind.DEMAND &&
                        outputTarget.effect == LifecycleEffect.REPLACE && outputFixed.before != null
                LifecycleTransition.RECOVER_HOLD ->
                    outputFixed.role == LifecycleRole.GUARD && outputTarget.kind == ControlKind.DEMAND &&
                        ((outputTarget.effect == LifecycleEffect.REPLACE && outputFixed.before != null) ||
                            (outputTarget.effect == LifecycleEffect.CREATE && outputFixed.before == null))
                LifecycleTransition.UPDATE_AUTH ->
                    outputFixed.role == LifecycleRole.GUARD && outputTarget.kind == ControlKind.DEMAND &&
                        outputTarget.effect == LifecycleEffect.REPLACE && outputFixed.before != null &&
                        when (fixed.demandAuth?.event) {
                            is LifecycleAuthEvent.Answer -> true
                            is LifecycleAuthEvent.Caller, is LifecycleAuthEvent.Recovery ->
                                outputFixed.after != null &&
                                    guardAuthRewriteKeepsFloor(outputFixed.before, outputFixed.after)
                            else -> false
                        }
                else -> false
            }
            if (!supported) return reject(LifecycleConfirmationFailure.UNSUPPORTED_TRANSITION_OR_TARGET)
            val selected = checkNotNull(outputFixed)
            if (authPart && selected.role != LifecycleRole.GUARD)
                return reject(LifecycleConfirmationFailure.UNSUPPORTED_TRANSITION_OR_TARGET)
            if (confirmed.effect == ConfirmedEffect.JoinedExisting ||
                (confirmed.effect == ConfirmedEffect.AppliedThisAttempt &&
                    confirmed.proof.storage != RecordTransactionEvidence.CompletedWriteScope))
                return reject(LifecycleConfirmationFailure.EFFECT_NOT_ELIGIBLE)

            val receipt = confirmed.lifecycleReceipt ?: return reject(LifecycleConfirmationFailure.RECEIPT_MISSING)
            val namespace = fixed.namespace
            val expectedObservations = fixed.targets.map { target ->
                LifecycleObservedTarget(target.target, if (target.target.effect == LifecycleEffect.REMOVE)
                    LifecycleTargetObservation.Absent else LifecycleTargetObservation.PresentExact)
            }
            val expectedUnchanged = fixed.requiredUnchanged.map {
                LifecycleObservedTarget(it.target, LifecycleTargetObservation.PresentExact)
            }
            if (receipt.commandId != exactCommand.id || receipt.transition != fixed.transition ||
                receipt.targets != expectedObservations || receipt.requiredUnchanged != expectedUnchanged ||
                receipt.before != namespace?.before || receipt.after != namespace?.after ||
                receipt.journal != namespace?.journal.orEmpty().associateWith { JournalObservation.Present } ||
                receipt.hasUninterpretable || receipt.hasUninterpretableMetadata)
                return reject(LifecycleConfirmationFailure.RECEIPT_MISMATCH)

            val read = confirmed.snapshot.record
            val own = ControlAppliedEvidence.own(read, exactCommand)
            if (own !is AppliedEvidence.Lifecycle ||
                !ControlAppliedEvidence.matches(exactCommand, body, null, own))
                return reject(LifecycleConfirmationFailure.APPLIED_MISSING_OR_MISMATCH)
            if (read.hasUninterpretable || read.hasUninterpretableMetadata)
                return reject(LifecycleConfirmationFailure.SNAPSHOT_UNINTERPRETABLE)
            val found = read.locations(outputTarget.id).singleOrNull()
            val entry = found?.second as? ControlEntryRead.Interpreted
            if (entry == null ||
                entry.original.toPayloadEntry() != selected.after?.toPayloadEntry())
                return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
            val output = when {
                selected.role == LifecycleRole.REQUEST -> {
                    val parsed = entry.value as? DemandV1
                        ?: return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    TypedDestinationTuple.Request(DestinationLocator.Payload(outputTarget.kind, outputTarget.id),
                        entry.original, parsed)
                }
                authPart -> {
                    val parsed = entry.value as? ScheduleGuardV1
                        ?: return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    if (parsed.auth == null ||
                        (fixed.transition in setOf(LifecycleTransition.RECOVER_HOLD, LifecycleTransition.UPDATE_AUTH) &&
                            parsed.floor == null))
                        return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    TypedDestinationTuple.GuardAuth(DestinationLocator.Guard(outputTarget.id, GuardPart.AUTH),
                        entry.original, parsed)
                }
                floorGuard -> {
                    val parsed = entry.value as? ScheduleGuardV1
                        ?: return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    if (parsed.floor == null)
                        return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    TypedDestinationTuple.GuardFloor(DestinationLocator.Guard(outputTarget.id, GuardPart.FLOOR),
                        entry.original, parsed)
                }
                fixed.transition == LifecycleTransition.END_AUTH_BINDING -> {
                    val parsed = entry.value as? ScheduleGuardV1
                        ?: return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    if (parsed.auth == null)
                        return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    TypedDestinationTuple.GuardAuth(DestinationLocator.Guard(outputTarget.id, GuardPart.AUTH),
                        entry.original, parsed)
                }
                fixed.transition == LifecycleTransition.RECOVER_HOLD ||
                    fixed.transition == LifecycleTransition.UPDATE_AUTH -> {
                    val parsed = entry.value as? ScheduleGuardV1
                        ?: return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    if (parsed.floor == null)
                        return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    TypedDestinationTuple.GuardFloor(DestinationLocator.Guard(outputTarget.id, GuardPart.FLOOR),
                        entry.original, parsed)
                }
                else -> error("Unsupported named output")
            }
            // The selected row is checked separately so its failure keeps a distinct reason.
            if ((fixed.targets - selected + fixed.requiredUnchanged).any {
                    ControlLifecycleBoundary.postcondition(read, it) != null
                }) return reject(LifecycleConfirmationFailure.NAMED_EFFECT_MISMATCH)
            if (namespace != null) {
                val journal = NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(read.original)
                if (!ControlLifecycleBoundary.fence(read.original, namespace.after) ||
                    (namespace.userMayContain != null &&
                        read.original[MAY_CONTAIN_PREMIUM] != namespace.userMayContain) ||
                    (namespace.krxMayContain != null &&
                        read.original[MAY_CONTAIN_KRX] != namespace.krxMayContain) ||
                    journal == null || !journal.containsAll(namespace.journal))
                    return reject(LifecycleConfirmationFailure.NAMED_EFFECT_MISMATCH)
            }
            val fixedCopy = ControlLifecycleDescriptor(fixed.operationId, fixed.transition, fixed.targets,
                fixed.executor, fixed.namespace?.let {
                    LifecycleNamespacePostcondition(it.before, it.after, it.journal,
                        it.userMayContain, it.krxMayContain)
                }, fixed.requiredUnchanged, fixed.demandAuth, fixed.removeEmptyGuard,
                fixed.recoverHold, fixed.recoverIntent)
            return LifecycleOutputConfirmationResult.Issued(PriorStorageConfirmation(
                ConfirmationBinding.LifecycleOutput(exactCommand, fixedCopy, outputTarget, output,
                    confirmed.effect, LifecycleTargetObservation.PresentExact, confirmed.proof.storage)))
        }

        /** 6-4bC1d-4a: the GUARD output of a SETTLE_QUERY (REPLACE or sole requiredUnchanged) or END_AUTH_BINDING (REPLACE), as GuardFloor. */
        fun confirmLifecycleGuardOutput(
            exactCommand: CommandRef,
            fixed: ControlLifecycleDescriptor,
            confirmed: ControlStoreResult.Confirmed,
            outputTarget: LifecycleTarget,
            authPart: Boolean = false
        ): LifecycleOutputConfirmationResult =
            confirmOutput(exactCommand, fixed, confirmed, outputTarget, unchangedGuard = false,
                floorGuard = true, authPart = authPart)

        /** 6-4bC1d-1(b): the guard output of Mutations action [actionIndex] (an Edit.floor recapture) of [exactCommand]. */
        fun confirmMutationFloorOutput(
            exactCommand: CommandRef,
            actionIndex: Int,
            confirmed: ControlStoreResult.Confirmed
        ): MutationFloorOutputConfirmationResult {
            fun reject(reason: MutationFloorConfirmationFailure) = MutationFloorOutputConfirmationResult.Rejected(reason)
            val body = exactCommand.captureStateAndBody().body as? ControlCommandBody.Mutations
            if (confirmed.command !== exactCommand || body == null || actionIndex !in body.actions.indices)
                return reject(MutationFloorConfirmationFailure.REF_OR_ACTION_MISMATCH)
            val action = body.actions[actionIndex] as? ControlMutation.Edit
                ?: return reject(MutationFloorConfirmationFailure.UNSUPPORTED_ACTION)
            val after = (action.changed as? ControlWriteResult.Written)?.node
            val before = guard(action.before)
            val output = after?.let(::guard)
            if (action.kind != ControlKind.DEMAND || after == null || before == null || output == null ||
                before.id != output.id || before.floor == null || output.floor == null ||
                action.before.toPayloadEntry() == after.toPayloadEntry())
                return reject(MutationFloorConfirmationFailure.UNSUPPORTED_ACTION)
            if (confirmed.effect == ConfirmedEffect.JoinedExisting ||
                (confirmed.effect == ConfirmedEffect.AppliedThisAttempt &&
                    confirmed.proof.storage != RecordTransactionEvidence.CompletedWriteScope))
                return reject(MutationFloorConfirmationFailure.EFFECT_NOT_ELIGIBLE)
            val read = confirmed.snapshot.record
            val own = ControlAppliedEvidence.own(read, exactCommand) as? AppliedEvidence.Mutations
            val applied = own?.targets?.getOrNull(actionIndex)
            if (own == null || own.commandId != exactCommand.id ||
                own.ownerTrackingLifetimeId != exactCommand.ownerTrackingLifetimeId.value ||
                own.targets.size != body.actions.size || applied == null || applied.index != actionIndex ||
                applied.kind != ControlKind.DEMAND || applied.id != output.id ||
                applied.joined || !applied.written || own.targets.withIndex().any { (index, target) ->
                    val fixedAction = body.actions[index]
                    val id = when (fixedAction) {
                        is ControlMutation.Add -> fixedAction.proposedId
                        is ControlMutation.Edit ->
                            (ControlObligations.read(fixedAction.kind, fixedAction.before) as?
                                ControlEntryRead.Interpreted)?.value?.id
                    }
                    target.index != index || target.kind != fixedAction.kind || target.id != id
                })
                return reject(MutationFloorConfirmationFailure.APPLIED_MISSING_OR_MISMATCH)
            if (read.hasUninterpretable || read.hasUninterpretableMetadata)
                return reject(MutationFloorConfirmationFailure.SNAPSHOT_UNINTERPRETABLE)
            val found = read.locations(output.id).singleOrNull()
            val entry = found?.second as? ControlEntryRead.Interpreted
            if (found?.first != ControlKind.DEMAND || entry == null ||
                entry.original.toPayloadEntry() != after.toPayloadEntry() ||
                entry.value != output)
                return reject(MutationFloorConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
            val floor = output.floor
            val at = BootReading(floor.anchorBootId, floor.anchorElapsedMillis)
            val remaining = before.floor.remainingAt(at)
            if (at.bootId == "" || at.elapsedMillis < 0 || remaining == null ||
                floor.remainingAt(at) == null || floor.waitMillis < remaining ||
                action.before.toPayloadEntry().fields.filterKeys { it != "floor" } !=
                    after.toPayloadEntry().fields.filterKeys { it != "floor" })
                return reject(MutationFloorConfirmationFailure.FLOOR_RULE_MISMATCH)
            val destination = TypedDestinationTuple.GuardFloor(
                DestinationLocator.Guard(output.id, GuardPart.FLOOR), entry.original, output)
            return MutationFloorOutputConfirmationResult.Issued(PriorStorageConfirmation(
                ConfirmationBinding.MutationFloorOutput(exactCommand, actionIndex, action.before,
                    destination, confirmed.effect, confirmed.proof.storage)))
        }

        fun confirmRetainedSource(
            slot: RequiredSlot,
            destination: DestinationLocator,
            confirmed: ControlStoreResult.Confirmed
        ): RetainedSourceConfirmationResult = confirmRetained(slot, destination, confirmed.snapshot.record,
            confirmed.proof.storage)

        fun confirmRetainedSource(
            slot: RequiredSlot,
            destination: DestinationLocator,
            observed: RecordTransactionResult<ControlRecordRead.Supported>
        ): RetainedSourceConfirmationResult {
            if (!retainedSourceRequired(slot))
                return RetainedSourceConfirmationResult.Rejected(RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
            if (observed.value.original != observed.snapshot)
                return RetainedSourceConfirmationResult.Rejected(RetainedConfirmationFailure.OBSERVATION_RECORD_MISMATCH)
            return confirmRetained(slot, destination, observed.value, observed.evidence)
        }

        private fun retainedSourceRequired(slot: RequiredSlot): Boolean {
            val requirement = slot.requirement as? SlotRequirement.Required ?: return false
            if (slot.key.role is ObligationRole.MutationAction && when (requirement.lowerBound) {
                    is RequiredLowerBound.Request -> slot.key.component == ObligationComponent.REQUEST
                    is RequiredLowerBound.Seal -> slot.key.component == ObligationComponent.SEAL
                    is RequiredLowerBound.Hold, is RequiredLowerBound.Intent ->
                        slot.key.component == ObligationComponent.SOURCE
                    else -> false
                }) return true
            return when (val bound = requirement.lowerBound) {
                is RequiredLowerBound.Hold, is RequiredLowerBound.ExactSource ->
                    slot.key.component == ObligationComponent.SOURCE
                is RequiredLowerBound.Floor -> slot.key.component == ObligationComponent.FLOOR
                is RequiredLowerBound.Auth -> slot.key.component == ObligationComponent.AUTH
                is RequiredLowerBound.Journal -> slot.key.component == ObligationComponent.JOURNAL
                is RequiredLowerBound.Intent -> slot.key.component == ObligationComponent.SOURCE &&
                    hasOriginalSourceRow(requirement, ControlKind.RECOVERY_INTENT)
                is RequiredLowerBound.Seal -> slot.key.component == ObligationComponent.SEAL &&
                    hasOriginalSourceRow(requirement, ControlKind.SEAL)
                is RequiredLowerBound.Request -> slot.key.component == ObligationComponent.REQUEST &&
                    bound.source != null && hasOriginalSourceRow(requirement, ControlKind.DEMAND)
                is RequiredLowerBound.NamespaceRetirement ->
                    slot.key.component == ObligationComponent.NAMESPACE_RETIREMENT &&
                        hasOriginalSourceRow(requirement, ControlKind.SEAL)
                else -> false
            }
        }

        private fun hasOriginalSourceRow(requirement: SlotRequirement.Required, kind: ControlKind): Boolean =
            requirement.fixedSources.any { fixed ->
                (fixed.location.facet == FixedInputFacet.SOURCE || fixed.location.facet == FixedInputFacet.BEFORE) &&
                    (fixed.fact as? FixedSourceFact.Node)?.let { node ->
                        node.kind == kind && ControlObligations.read(kind, node.value) is ControlEntryRead.Interpreted
                    } == true
            }

        private fun originalSourceRow(requirement: SlotRequirement.Required, kind: ControlKind,
            id: String): ControlNode? = requirement.fixedSources.mapNotNull { fixed ->
            if (fixed.location.facet != FixedInputFacet.SOURCE &&
                fixed.location.facet != FixedInputFacet.BEFORE) return@mapNotNull null
            val fact = fixed.fact as? FixedSourceFact.Node ?: return@mapNotNull null
            if (fact.kind != kind) return@mapNotNull null
            val parsed = (ControlObligations.read(kind, fact.value) as? ControlEntryRead.Interpreted)?.value
            fact.value.takeIf { parsed?.id == id }
        }.distinctBy { it.toPayloadEntry() }.singleOrNull()

        private data class MutationRetainedRow(
            val kind: ControlKind,
            val source: ControlObligationV1,
            val subjectSource: ControlObligationV1,
            val adopted: ControlObligationV1
        )

        /** Select only this slot's prepared action and fixed adoption; never search another action's sources. */
        private fun mutationRetainedRow(slot: RequiredSlot,
            requirement: SlotRequirement.Required): MutationRetainedRow? {
            val index = (slot.key.role as? ObligationRole.MutationAction)?.index ?: return null
            fun fixed(root: FixedInputRoot, facet: FixedInputFacet): FixedSourceFact? =
                requirement.fixedSources.filter { it.location == FixedInputLocation(root, index, facet) }
                    .singleOrNull()?.fact
            val action = (fixed(FixedInputRoot.MUTATION_ACTION, FixedInputFacet.WHOLE)
                as? FixedSourceFact.Mutation)?.value ?: return null
            fun node(facet: FixedInputFacet): ControlNode? {
                val fact = fixed(FixedInputRoot.MUTATION_ACTION, facet) as? FixedSourceFact.Node
                return fact?.value?.takeIf { fact.kind == action.kind }
            }
            val afterNode = node(FixedInputFacet.AFTER) ?: return null
            val beforeNode = node(FixedInputFacet.BEFORE)
            val preparedAfter = when (action) {
                is ControlMutation.Add -> (action.built as? ControlWriteResult.Written)?.node
                is ControlMutation.Edit -> (action.changed as? ControlWriteResult.Written)?.node
            } ?: return null
            if (afterNode.toPayloadEntry() != preparedAfter.toPayloadEntry() ||
                (action is ControlMutation.Add && beforeNode != null) ||
                (action is ControlMutation.Edit && beforeNode?.toPayloadEntry() != action.before.toPayloadEntry()))
                return null
            val adoption = listOfNotNull(
                fixed(FixedInputRoot.MUTATION_ADOPTION, FixedInputFacet.WHOLE),
                fixed(FixedInputRoot.PREVIOUS_CHECKPOINT, FixedInputFacet.AFTER)
            ).singleOrNull() as? FixedSourceFact.Adoption ?: return null
            val target = adoption.value
            fun parsed(node: ControlNode?) = node?.let {
                (ControlObligations.read(action.kind, it) as? ControlEntryRead.Interpreted)?.value
            }
            val after = parsed(afterNode) ?: return null
            val before = parsed(beforeNode)
            val adopted = parsed(target.postcondition) ?: return null
            if (target.id != adopted.id || when {
                    target.joined -> action !is ControlMutation.Add || action.kind != ControlKind.SEAL ||
                        after !is SealV1 || adopted !is SealV1 || after.key != adopted.key ||
                        adopted.settlement != null
                    else -> target.postcondition.toPayloadEntry() != afterNode.toPayloadEntry()
                }) return null
            val source = when (action) {
                is ControlMutation.Add -> after
                is ControlMutation.Edit -> if (slot.key.branch == LandingBranch.L) after else before ?: return null
            }
            return MutationRetainedRow(action.kind, source, before ?: after, adopted)
        }

        private fun confirmRetained(slot: RequiredSlot, destination: DestinationLocator,
            read: ControlRecordRead.Supported, evidence: RecordTransactionEvidence): RetainedSourceConfirmationResult {
            fun reject(reason: RetainedConfirmationFailure) = RetainedSourceConfirmationResult.Rejected(reason)
            if (!retainedSourceRequired(slot))
                return reject(RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
            val requirement = slot.requirement as SlotRequirement.Required
            val bound = requirement.lowerBound
            val subject = slot.key.subject
            val floorFixed = (bound as? RequiredLowerBound.Floor)?.let { floorFixedRow(slot, requirement, it) }
            val mutationSlot = slot.key.role is ObligationRole.MutationAction &&
                bound !is RequiredLowerBound.Floor && bound !is RequiredLowerBound.Auth
            val mutation = if (mutationSlot) mutationRetainedRow(slot, requirement) else null
            val subjectMatches = when (bound) {
                is RequiredLowerBound.Hold -> subject == ObligationSubject.Hold(bound.source.id,
                    bound.source.originLifetimeId, bound.source.binding, bound.source.provenance, bound.source.axes) &&
                    destination == DestinationLocator.Payload(ControlKind.HOLD, bound.source.id) &&
                    if (mutationSlot) mutation?.let { it.kind == ControlKind.HOLD &&
                        it.source == bound.source && it.adopted.id == bound.source.id } == true
                    else originalSourceRow(requirement, ControlKind.HOLD, bound.source.id)?.let { original ->
                        val fixed = (ControlObligations.read(ControlKind.HOLD, original)
                            as? ControlEntryRead.Interpreted)?.value as? RestoredHold
                        fixed != null && compareHold(bound.source, fixed) == TypedComparison.Matches
                    } == true
                is RequiredLowerBound.Floor -> floorFixed?.let { fixed ->
                    subject == ObligationSubject.Floor(bound.sourceKind, bound.sourceId, fixed.subjectOrigin)
                } == true && when (destination) {
                    is DestinationLocator.Payload -> destination == DestinationLocator.Payload(bound.sourceKind, bound.sourceId)
                    is DestinationLocator.Guard -> bound.sourceKind == ControlKind.DEMAND &&
                        destination == DestinationLocator.Guard(bound.sourceId, GuardPart.FLOOR)
                    else -> false
                }
                is RequiredLowerBound.Auth -> subject == ObligationSubject.Auth(bound.required.ownerUid,
                    bound.required.binding, bound.required.originLifetimeId, bound.required.authGeneration) &&
                    destination is DestinationLocator.Guard && destination.part == GuardPart.AUTH &&
                    destination.id == originalAuthGuardId(requirement)
                is RequiredLowerBound.Journal -> subject is ObligationSubject.Journal && subject.key == bound.key &&
                    requirement.fixedSources.any { fixed ->
                        val source = fixed.fact as? FixedSourceFact.Node
                        fixed.location.facet == FixedInputFacet.SOURCE && source != null &&
                            (ControlObligations.read(source.kind, source.value) as? ControlEntryRead.Interpreted)
                                ?.value?.id == subject.sourceSealId
                    } && destination == DestinationLocator.Journal(bound.key)
                is RequiredLowerBound.ExactSource -> subject == ObligationSubject.ExactTarget(bound.kind, bound.id) &&
                    destination == DestinationLocator.Payload(bound.kind, bound.id) &&
                    originalSourceRow(requirement, bound.kind, bound.id)?.toPayloadEntry() ==
                        bound.node.toPayloadEntry()
                is RequiredLowerBound.Intent -> subject == ObligationSubject.Intent(bound.source.id,
                    bound.source.sessionId, bound.source.ownerUid, bound.source.axis, bound.source.targetEpoch) &&
                    destination == DestinationLocator.Payload(ControlKind.RECOVERY_INTENT, bound.source.id) &&
                    if (mutationSlot) mutation?.let { it.kind == ControlKind.RECOVERY_INTENT &&
                        it.source == bound.source && it.adopted.id == bound.source.id } == true
                    else originalSourceRow(requirement, ControlKind.RECOVERY_INTENT, bound.source.id)?.let {
                        (ControlObligations.read(ControlKind.RECOVERY_INTENT, it) as? ControlEntryRead.Interpreted)
                            ?.value == bound.source
                    } == true
                is RequiredLowerBound.Seal -> subject == ObligationSubject.Seal(bound.source.id,
                    bound.source.kind, bound.source.key) &&
                    destination == DestinationLocator.Payload(ControlKind.SEAL, bound.source.id) &&
                    if (mutationSlot) mutation?.let { it.kind == ControlKind.SEAL &&
                        it.adopted == bound.source &&
                        (it.source as? SealV1)?.key == bound.source.key } == true
                    else originalSourceRow(requirement, ControlKind.SEAL, bound.source.id)?.let {
                        (ControlObligations.read(ControlKind.SEAL, it) as? ControlEntryRead.Interpreted)
                            ?.value == bound.source
                    } == true
                is RequiredLowerBound.Request -> bound.source?.let { source ->
                    val mutationSubject = mutation?.subjectSource as? DemandV1
                    subject == ObligationSubject.Request(
                        mutationSubject?.id ?: source.id, mutationSubject?.ownerUid ?: source.ownerUid,
                        mutationSubject?.binding ?: source.binding, mutationSubject?.raisedAt ?: source.raisedAt) &&
                        destination == DestinationLocator.Payload(ControlKind.DEMAND, bound.requiredId) &&
                        source.id == bound.requiredId && source.ownerUid == bound.ownerUid &&
                        if (mutationSlot) mutation?.let { it.kind == ControlKind.DEMAND &&
                            it.source == source && it.adopted.id == bound.requiredId &&
                            (it.adopted as? DemandV1)?.let { adopted ->
                                adopted.ownerUid == bound.ownerUid && adopted.binding == bound.binding &&
                                    bound.minimumIntent == adopted.intent &&
                                    bound.minimumOrder == adopted.raisedAt
                            } == true } == true
                        else originalSourceRow(requirement, ControlKind.DEMAND, source.id)?.let {
                            (ControlObligations.read(ControlKind.DEMAND, it) as? ControlEntryRead.Interpreted)
                                ?.value == source
                        } == true
                } == true
                is RequiredLowerBound.NamespaceRetirement -> subject == bound.scope &&
                    destination == DestinationLocator.Journal(bound.scope.journalKey) &&
                    retirementSealSources(requirement, bound.scope, slot.key.role) != null
                else -> false
            }
            if (!subjectMatches) return reject(RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
            val result = when (destination) {
                is DestinationLocator.Payload -> {
                    val found = read.locations(destination.id).singleOrNull()
                    val entry = found?.second as? ControlEntryRead.Interpreted
                    val original = if (bound is RequiredLowerBound.Floor)
                        floorFixed?.node
                    else if (mutationSlot) null
                    else originalSourceRow(requirement, destination.kind, destination.id)
                    if (found?.first != destination.kind || entry == null ||
                        (bound !is RequiredLowerBound.Intent && !retainedRowMatches(bound, entry)) ||
                        (bound is RequiredLowerBound.Intent && mutationSlot &&
                            (entry.value as? RecoveryIntentV1)?.let {
                                compareRecoveryIntent(bound.source, it) != TypedComparison.Matches
                            } != false) ||
                        (bound is RequiredLowerBound.Hold || bound is RequiredLowerBound.Floor ||
                            bound is RequiredLowerBound.Intent || bound is RequiredLowerBound.Seal ||
                            bound is RequiredLowerBound.Request) && !mutationSlot &&
                        entry.original.toPayloadEntry() != original?.toPayloadEntry()) null
                    else RetainedDestinationTuple.Payload(destination, entry.original)
                }
                is DestinationLocator.Guard -> {
                    val found = read.locations(destination.id).singleOrNull()
                    val entry = found?.second as? ControlEntryRead.Interpreted
                    val guard = entry?.value as? ScheduleGuardV1
                    val same = when (bound) {
                        is RequiredLowerBound.Floor -> guard?.floor == bound.captured
                        is RequiredLowerBound.Auth -> guard?.auth == bound.required
                        else -> false
                    }
                    if (found?.first != ControlKind.DEMAND || entry == null || same != true) null
                    else RetainedDestinationTuple.Guard(destination, entry.original)
                }
                is DestinationLocator.Journal -> {
                    val canonical = NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(read.original)
                    if (canonical == null || compareJournal(destination.key, canonical) != TypedComparison.Matches) null
                    else if (bound is RequiredLowerBound.NamespaceRetirement) {
                        val sources = retirementSealSources(requirement, bound.scope, slot.key.role)
                        val raw = read.original
                        if (sources == null || !raw.validType<String>(OWNER_UID) ||
                            !raw.validType<String>(USER_EPOCH) || !raw.validType<String>(KRX_EPOCH) ||
                            !ControlLifecycleBoundary.fence(raw, bound.scope.after) ||
                            read.arrays.getValue(ControlKind.SEAL).entries.any {
                                it is ControlEntryRead.Uninterpretable
                            }) null
                        else {
                            val writer = NamespaceSettlementTransition(ControlPayloadCodec())
                            val rows = sources.map { source ->
                                val found = read.locations(source.seal.id).singleOrNull()
                                val entry = found?.second as? ControlEntryRead.Interpreted
                                val actual = entry?.value as? SealV1
                                if (found?.first != ControlKind.SEAL || entry == null || actual == null ||
                                    !writer.immutableSealMatches(entry.original, source.original) ||
                                    actual.settlement == null ||
                                    !writer.witnessMatches(actual.settlement, source.witness)) null
                                else entry.original
                            }
                            if (rows.any { it == null }) null
                            else RetainedDestinationTuple.RetirementJournal(destination, bound.scope,
                                bound.scope.after, rows.filterNotNull())
                        }
                    } else RetainedDestinationTuple.Journal(destination)
                }
            } ?: return reject(RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
            return RetainedSourceConfirmationResult.Issued(PriorStorageConfirmation(
                ConfirmationBinding.RetainedSource(slot, result, evidence)))
        }

        private fun retainedRowMatches(bound: RequiredLowerBound, entry: ControlEntryRead.Interpreted): Boolean =
            when (bound) {
                is RequiredLowerBound.Hold -> (entry.value as? RestoredHold)?.let {
                    compareHold(bound.source, it) == TypedComparison.Matches
                } == true
                is RequiredLowerBound.Floor -> when (val value = entry.value) {
                    is RestoredHold -> value.floor == bound.captured
                    is ScheduleGuardV1 -> value.floor == bound.captured
                    else -> false
                }
                is RequiredLowerBound.ExactSource -> entry.original.toPayloadEntry() == bound.node.toPayloadEntry()
                is RequiredLowerBound.Seal -> (entry.value as? SealV1)?.let {
                    compareSeal(bound.source.copy(settlement = bound.requiredSettlement), it) == TypedComparison.Matches
                } == true
                is RequiredLowerBound.Request -> (entry.value as? DemandV1)?.let { actual ->
                    val source = bound.source
                    source != null && actual.binding == bound.binding && actual.ownerUid == bound.ownerUid &&
                        actual.id == bound.requiredId &&
                        compareRequest(RequestNeed(source, bound.minimumIntent, bound.minimumOrder), actual) ==
                        TypedComparison.Matches
                } == true
                else -> false
            }
    }
}

internal enum class NamedTransferFailureKind {
    UNSUPPORTED_TRANSITION_OR_REPLACEMENT,
    SOURCE_MISMATCH,
    DESTINATION_MISMATCH,
    OWNER_MISMATCH,
    CONFIRMATION_MISMATCH,
    REQUEST_INTENT_OR_GRANT
}

internal sealed interface NamedTransferFailure {
    data class Invalid(val kind: NamedTransferFailureKind) : NamedTransferFailure
    data class Reanchor(val field: ReanchorField) : NamedTransferFailure
    /** 6-4bC1d-4a: a preservation link whose component (the guard FLOOR) changed. */
    data object PreservedComponentChanged : NamedTransferFailure
}

internal sealed interface NamedTransferResult {
    data class Issued(val value: NamedTransferLink) : NamedTransferResult
    data class Rejected(val reason: NamedTransferFailure) : NamedTransferResult
}

internal class NamedTransferLink private constructor(
    val transition: LifecycleTransition?,
    val source: TypedSourceTuple,
    val destination: TypedDestinationTuple,
    val responsibilityOwner: ResponsibilityOwner,
    val confirmation: PriorStorageConfirmation
) {
    companion object {
        fun linkNamedTransfer(
            fixedSource: TypedSourceTuple,
            destination: TypedDestinationTuple,
            confirmation: PriorStorageConfirmation
        ): NamedTransferResult {
            fun invalid(kind: NamedTransferFailureKind) =
                NamedTransferResult.Rejected(NamedTransferFailure.Invalid(kind))
            fun sameRow(a: ControlNode?, b: ControlNode?): Boolean =
                a?.toPayloadEntry() == b?.toPayloadEntry()

            // Only the 6-4bC1d-4b UPDATE_AUTH GuardAuth → GuardAuth pair reaches the token checks with an AUTH destination.
            if (destination is TypedDestinationTuple.GuardAuth && fixedSource !is TypedSourceTuple.GuardAuth)
                return invalid(NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)
            val binding = confirmation.binding as? ConfirmationBinding.LifecycleOutput
                ?: return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
            val fixed = binding.fixed
            val selected = if (fixed.transition == LifecycleTransition.RECOVER_HOLD &&
                fixed.requiredUnchanged.singleOrNull { it.role == LifecycleRole.GUARD }?.target == binding.outputTarget &&
                fixed.targets.none { it.target == binding.outputTarget })
                fixed.requiredUnchanged.singleOrNull { it.role == LifecycleRole.GUARD && it.target == binding.outputTarget }
            else fixed.targets.singleOrNull { it.target == binding.outputTarget }
            val supported = selected != null && when (fixed.transition) {
                LifecycleTransition.REBIND_REQUESTS, LifecycleTransition.END_AUTH_BINDING ->
                    fixedSource is TypedSourceTuple.Request && destination is TypedDestinationTuple.Request
                LifecycleTransition.RECOVER_HOLD ->
                    (fixedSource is TypedSourceTuple.HoldFloor || fixedSource is TypedSourceTuple.GuardFloor) &&
                        destination is TypedDestinationTuple.GuardFloor
                LifecycleTransition.UPDATE_AUTH ->
                    selected.role == LifecycleRole.GUARD && selected.target.effect == LifecycleEffect.REPLACE &&
                        ((fixedSource is TypedSourceTuple.GuardFloor && destination is TypedDestinationTuple.GuardFloor) ||
                            (fixedSource is TypedSourceTuple.GuardAuth && destination is TypedDestinationTuple.GuardAuth))
                else -> false
            }
            if (!supported) return invalid(NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)
            val row = checkNotNull(selected)
            val recovery = fixed.recoverHold
            val hold = fixed.targets.singleOrNull {
                it.role == LifecycleRole.HOLD && it.target.effect == LifecycleEffect.REMOVE
            }
            val recoverySourceMatches = recovery != null && hold != null &&
                hold.target.kind == ControlKind.HOLD &&
                sameRow(recovery.input.source, hold.before) && hold.after == null &&
                sameRow(recovery.input.guard, row.before) &&
                fixed.executor == recovery.input.binding.executor

            val sourceMatches = when (fixedSource) {
                is TypedSourceTuple.Request -> {
                    val before = demand(fixedSource.preimage)
                    val after = demand(row.after)
                    row.target.kind == ControlKind.DEMAND &&
                        before == fixedSource.parsed && sameRow(fixedSource.preimage, row.before) &&
                        after != null && after.id == before.id && after.ownerUid == before.ownerUid
                }
                is TypedSourceTuple.HoldFloor -> {
                    recoverySourceMatches &&
                        hold.target.id == fixedSource.source.sourceId &&
                        sameRow(fixedSource.source.originalHold, hold.before) &&
                        sameRow(fixedSource.oldGuard, row.before) &&
                        fixedSource.mergeNow == recovery.input.mergeNow &&
                        fixedSource.executorOrigin == recovery.input.binding.executor.originLifetimeId
                }
                is TypedSourceTuple.GuardAuth ->
                    row.role == LifecycleRole.GUARD && row.target.effect == LifecycleEffect.REPLACE &&
                        row.target.kind == ControlKind.DEMAND &&
                        sameRow(fixedSource.preimage, row.before) &&
                        fixedSource.parsed.auth != null &&
                        guard(fixedSource.preimage) == fixedSource.parsed &&
                        (destination as? TypedDestinationTuple.GuardAuth)?.parsed?.id == fixedSource.parsed.id
                is TypedSourceTuple.GuardFloor ->
                    (if (fixed.transition == LifecycleTransition.UPDATE_AUTH)
                        row.role == LifecycleRole.GUARD && row.target.effect == LifecycleEffect.REPLACE
                    else recoverySourceMatches) && row.target.kind == ControlKind.DEMAND &&
                        sameRow(fixedSource.preimage, row.before) &&
                        fixedSource.parsed.floor != null &&
                        guard(fixedSource.preimage) == fixedSource.parsed
            }
            if (!sourceMatches) return invalid(NamedTransferFailureKind.SOURCE_MISMATCH)

            val destinationMatches = when (destination) {
                is TypedDestinationTuple.Request ->
                    destination.locator.kind == ControlKind.DEMAND &&
                        destination.locator.id == destination.parsed.id &&
                        demand(destination.row) == destination.parsed
                is TypedDestinationTuple.GuardFloor ->
                    destination.locator.part == GuardPart.FLOOR &&
                        destination.locator.id == destination.parsed.id &&
                        destination.parsed.floor != null &&
                        guard(destination.row) == destination.parsed
                is TypedDestinationTuple.GuardAuth ->
                    destination.locator.part == GuardPart.AUTH &&
                        destination.locator.id == destination.parsed.id &&
                        destination.parsed.auth != null &&
                        guard(destination.row) == destination.parsed
            }
            if (!destinationMatches) return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
            if (fixedSource is TypedSourceTuple.GuardAuth && destination is TypedDestinationTuple.GuardAuth &&
                !sameAuthScope(checkNotNull(fixedSource.parsed.auth), checkNotNull(destination.parsed.auth)))
                return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
            if (fixed.transition == LifecycleTransition.UPDATE_AUTH &&
                fixedSource is TypedSourceTuple.GuardFloor && destination is TypedDestinationTuple.GuardFloor) {
                val plan = fixed.demandAuth
                    ?: return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
                when (val event = plan.event) {
                    is LifecycleAuthEvent.Answer -> {
                        val decision = event.decision
                        val before = fixedSource.parsed
                        val after = destination.parsed
                        val mergeNow = decision.mergeNow
                        val oldRemaining = before.floor?.remainingAt(mergeNow)
                        val seconds = DemandAuthBoundary.statedSeconds(decision.outcome)
                        if (plan.decision !== decision || !DemandAuthBoundary.floorOrigin(decision) ||
                            !DemandAuthBoundary.seconds(seconds) || oldRemaining == null ||
                            mergeNow.bootId == "" || mergeNow.elapsedMillis < 0)
                            return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
                        val stated = if (seconds != null)
                            FloorV1(decision.capture.bootId, decision.capture.elapsedMillis,
                                seconds * 1000, decision.floorOrigin).remainingAt(mergeNow)
                        else null
                        if (seconds != null && stated == null)
                            return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
                        val delay = DemandAuthBoundary.minimumDelay(decision)
                        val required = if (seconds != null || delay > 0) maxOf(stated ?: 0, delay) else null
                        val expected = if (required != null && oldRemaining < required)
                            FloorV1(mergeNow.bootId, mergeNow.elapsedMillis, maxOf(oldRemaining, required),
                                plan.binding.executor.originLifetimeId)
                        else before.floor
                        if (before.id != after.id || after.auth == before.auth || after.floor != expected ||
                            fixedSource.preimage.toPayloadEntry().fields.filterKeys { it != "auth" && it != "floor" } !=
                                destination.row.toPayloadEntry().fields.filterKeys { it != "auth" && it != "floor" } ||
                            (expected == before.floor && fixedSource.preimage.toPayloadEntry().fields["floor"] !=
                                destination.row.toPayloadEntry().fields["floor"]))
                            return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
                    }
                    is LifecycleAuthEvent.Caller, is LifecycleAuthEvent.Recovery ->
                        if (!guardAuthRewriteKeepsFloor(fixedSource.preimage, destination.row))
                            return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
                    else -> return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
                }
            }
            if (fixedSource.responsibilityOwner.trackingLifetime !== binding.command.ownerTrackingLifetimeId ||
                fixedSource.responsibilityOwner.ownerKey.isBlank())
                return invalid(NamedTransferFailureKind.OWNER_MISMATCH)

            val confirmed = binding.output
            if (fixedSource is TypedSourceTuple.GuardAuth &&
                (confirmed !is TypedDestinationTuple.GuardAuth || confirmed.locator != destination.locator))
                return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
            if (!sameRow(confirmed.row, destination.row))
                return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)

            if (fixedSource is TypedSourceTuple.Request && destination is TypedDestinationTuple.Request) {
                val source = fixedSource.parsed
                val actual = destination.parsed
                val executor = fixed.executor
                    ?: return invalid(NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
                val grant = fixed.demandAuth?.grants?.get(source.id)
                    ?: return invalid(NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
                val minimum = fixedSource.minimumOrder
                val minOrderMet = when (minimum.origin) {
                    source.raisedAt.origin -> source.raisedAt.value >= minimum.value
                    actual.raisedAt.origin -> actual.raisedAt.value >= minimum.value
                    else -> false
                }
                if (source.raisedAt.value < 0 || minimum.value < 0 ||
                    actual.ownerUid != executor.ownerUid ||
                    actual.binding != executor.binding ||
                    actual.raisedAt.origin != executor.originLifetimeId ||
                    actual.raisedAt.origin != grant.origin || actual.raisedAt.value != grant.value ||
                    actual.intent.strength() < source.intent.strength() ||
                    actual.intent.strength() < fixedSource.minimumIntent.strength() || !minOrderMet)
                    return invalid(NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
            }
            if (fixedSource is TypedSourceTuple.HoldFloor && destination is TypedDestinationTuple.GuardFloor) {
                when (val result = validHoldReanchor(fixedSource.source, fixedSource.oldGuard,
                    fixedSource.mergeNow, fixedSource.executorOrigin, destination.locator.id, destination.row)) {
                    is ReanchorValidation.Invalid ->
                        return NamedTransferResult.Rejected(NamedTransferFailure.Reanchor(result.field))
                    is ReanchorValidation.Valid -> Unit
                }
            }
            if (fixed.transition == LifecycleTransition.RECOVER_HOLD &&
                fixedSource is TypedSourceTuple.GuardFloor && destination is TypedDestinationTuple.GuardFloor) {
                val input = checkNotNull(recovery).input
                val parsedHold = HoldRecoverySource.from(input.source)?.hold
                    ?: return invalid(NamedTransferFailureKind.SOURCE_MISMATCH)
                val floor = parsedHold.floor ?: return invalid(NamedTransferFailureKind.SOURCE_MISMATCH)
                val source = FloorSource(parsedHold.id, input.source, parsedHold, floor, input.guard)
                when (val result = validHoldReanchor(source, input.guard, input.mergeNow,
                    input.binding.executor.originLifetimeId, destination.locator.id, destination.row)) {
                    is ReanchorValidation.Invalid ->
                        return NamedTransferResult.Rejected(NamedTransferFailure.Reanchor(result.field))
                    is ReanchorValidation.Valid -> Unit
                }
            }
            return NamedTransferResult.Issued(NamedTransferLink(fixed.transition, fixedSource, destination,
                fixedSource.responsibilityOwner, confirmation))
        }

        /** 6-4bC1d-4a: a guard FLOOR carried unchanged across a confirmed guard write of a closed writer list. */
        fun linkPreservedGuardTransfer(
            source: TypedSourceTuple,
            destination: TypedDestinationTuple,
            confirmation: PriorStorageConfirmation
        ): NamedTransferResult {
            fun invalid(kind: NamedTransferFailureKind) =
                NamedTransferResult.Rejected(NamedTransferFailure.Invalid(kind))
            if (source is TypedSourceTuple.GuardAuth && destination is TypedDestinationTuple.GuardAuth) {
                val binding = confirmation.binding
                val command: CommandRef
                val before: ControlNode
                val outputRow: ControlNode
                val outputId: String
                val transition: LifecycleTransition?
                when (binding) {
                    is ConfirmationBinding.LifecycleOutput -> {
                        val selected = (binding.fixed.targets + binding.fixed.requiredUnchanged).singleOrNull {
                            it.role == LifecycleRole.GUARD && it.target == binding.outputTarget
                        } ?: return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
                        if (binding.fixed.transition !in setOf(LifecycleTransition.RECOVER_HOLD,
                                LifecycleTransition.UPDATE_AUTH, LifecycleTransition.SETTLE_QUERY) ||
                            selected.target.effect != LifecycleEffect.REPLACE)
                            return invalid(NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)
                        val output = binding.output as? TypedDestinationTuple.GuardAuth
                            ?: return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
                        command = binding.command
                        before = selected.before ?: return invalid(NamedTransferFailureKind.SOURCE_MISMATCH)
                        outputRow = output.row
                        outputId = output.locator.id
                        transition = binding.fixed.transition
                    }
                    is ConfirmationBinding.MutationFloorOutput -> {
                        command = binding.command
                        before = binding.before
                        outputRow = binding.output.row
                        outputId = binding.output.locator.id
                        transition = null
                    }
                    is ConfirmationBinding.RetainedSource ->
                        return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
                }
                if (destination.locator.part != GuardPart.AUTH ||
                    destination.locator.id != destination.parsed.id || destination.parsed.auth == null ||
                    guard(destination.row) != destination.parsed)
                    return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
                if (outputId != destination.locator.id ||
                    outputRow.toPayloadEntry() != destination.row.toPayloadEntry())
                    return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
                if (source.preimage.toPayloadEntry() != before.toPayloadEntry() ||
                    guard(source.preimage) != source.parsed || source.parsed.auth == null ||
                    source.parsed.id != destination.parsed.id)
                    return invalid(NamedTransferFailureKind.SOURCE_MISMATCH)
                if (source.responsibilityOwner.trackingLifetime !== command.ownerTrackingLifetimeId ||
                    source.responsibilityOwner.ownerKey.isBlank())
                    return invalid(NamedTransferFailureKind.OWNER_MISMATCH)
                if (source.preimage.toPayloadEntry().fields["auth"] !=
                    destination.row.toPayloadEntry().fields["auth"])
                    return NamedTransferResult.Rejected(NamedTransferFailure.PreservedComponentChanged)
                return NamedTransferResult.Issued(NamedTransferLink(transition, source, destination,
                    source.responsibilityOwner, confirmation))
            }
            if (source !is TypedSourceTuple.GuardFloor || destination !is TypedDestinationTuple.GuardFloor)
                return invalid(NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)
            val binding = confirmation.binding
            val command: CommandRef
            val before: ControlNode
            val output: TypedDestinationTuple.GuardFloor
            val transition: LifecycleTransition?
            when (binding) {
                is ConfirmationBinding.LifecycleOutput -> {
                    val selected = (binding.fixed.targets + binding.fixed.requiredUnchanged).singleOrNull {
                        it.role == LifecycleRole.GUARD && it.target == binding.outputTarget
                    } ?: return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
                    if (binding.fixed.transition !in setOf(LifecycleTransition.RECOVER_HOLD,
                            LifecycleTransition.UPDATE_AUTH, LifecycleTransition.SETTLE_QUERY,
                            LifecycleTransition.END_AUTH_BINDING) ||
                        selected.target.effect != LifecycleEffect.REPLACE)
                        return invalid(NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)
                    command = binding.command
                    before = selected.before ?: return invalid(NamedTransferFailureKind.SOURCE_MISMATCH)
                    output = binding.output as? TypedDestinationTuple.GuardFloor
                        ?: return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
                    transition = binding.fixed.transition
                }
                is ConfirmationBinding.MutationFloorOutput -> {
                    command = binding.command
                    before = binding.before
                    output = binding.output
                    transition = null
                }
                is ConfirmationBinding.RetainedSource ->
                    return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
            }
            if (destination.locator.part != GuardPart.FLOOR ||
                destination.locator.id != destination.parsed.id ||
                destination.parsed.floor == null || guard(destination.row) != destination.parsed)
                return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
            if (output.locator != destination.locator ||
                output.row.toPayloadEntry() != destination.row.toPayloadEntry())
                return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
            if (source.preimage.toPayloadEntry() != before.toPayloadEntry() ||
                guard(source.preimage) != source.parsed || source.parsed.floor == null ||
                source.parsed.id != destination.parsed.id)
                return invalid(NamedTransferFailureKind.SOURCE_MISMATCH)
            if (source.responsibilityOwner.trackingLifetime !== command.ownerTrackingLifetimeId ||
                source.responsibilityOwner.ownerKey.isBlank())
                return invalid(NamedTransferFailureKind.OWNER_MISMATCH)
            if (source.preimage.toPayloadEntry().fields["floor"] !=
                destination.row.toPayloadEntry().fields["floor"])
                return NamedTransferResult.Rejected(NamedTransferFailure.PreservedComponentChanged)
            return NamedTransferResult.Issued(NamedTransferLink(transition, source, destination,
                source.responsibilityOwner, confirmation))
        }

        /** 6-4bC1d-1(b): a guard FLOOR rewritten by a Mutations recordFloor, confirmed by its MutationFloorOutput. */
        fun linkRecordedFloorTransfer(
            source: TypedSourceTuple.GuardFloor,
            destination: TypedDestinationTuple.GuardFloor,
            confirmation: PriorStorageConfirmation
        ): NamedTransferResult {
            fun invalid(kind: NamedTransferFailureKind) =
                NamedTransferResult.Rejected(NamedTransferFailure.Invalid(kind))
            val binding = confirmation.binding as? ConfirmationBinding.MutationFloorOutput
                ?: return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
            val sourceFloor = source.parsed.floor
            if (source.preimage.toPayloadEntry() != binding.before.toPayloadEntry() ||
                guard(source.preimage) != source.parsed || sourceFloor == null)
                return invalid(NamedTransferFailureKind.SOURCE_MISMATCH)
            val after = guard(destination.row)
            val floor = destination.parsed.floor
            if (destination.locator.part != GuardPart.FLOOR ||
                destination.locator.id != source.parsed.id || after != destination.parsed || floor == null ||
                destination.parsed.id != source.parsed.id ||
                source.preimage.toPayloadEntry().fields.filterKeys { it != "floor" } !=
                    destination.row.toPayloadEntry().fields.filterKeys { it != "floor" })
                return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
            val at = BootReading(floor.anchorBootId, floor.anchorElapsedMillis)
            val oldRemaining = sourceFloor.remainingAt(at)
            if (floor.remainingAt(at) == null || oldRemaining == null || floor.waitMillis < oldRemaining)
                return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
            if (source.responsibilityOwner.trackingLifetime !== binding.command.ownerTrackingLifetimeId ||
                source.responsibilityOwner.ownerKey.isBlank())
                return invalid(NamedTransferFailureKind.OWNER_MISMATCH)
            if (binding.output.locator != destination.locator ||
                binding.output.row.toPayloadEntry() != destination.row.toPayloadEntry())
                return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
            return NamedTransferResult.Issued(NamedTransferLink(null, source, destination,
                source.responsibilityOwner, confirmation))
        }
    }
}

internal enum class G05Id(val wire: String) {
    REF("G05.ref"),
    REQUIREMENTS("G05.requirements"),
    DUPLICATE("G05.duplicate"),
    COVERAGE_L("G05.coverageL"),
    COVERAGE_N("G05.coverageN"),
    EXTRA("G05.extra"),
    SUBJECT("G05.subject"),
    DESTINATION("G05.destination"),
    CONFIRMATION("G05.confirmation"),
    LOWER_BOUND("G05.lowerBound"),
    COMPLETED_CONFLICT("G05.completedConflict"),
    OWNER("G05.owner")
}

internal sealed interface G05Location {
    data object Command : G05Location
    data object Closure : G05Location
    data class Fixed(val location: FixedInputLocation) : G05Location
    data class Submitted(val index: Int) : G05Location
    data class ActualPayload(val kind: ControlKind, val index: Int) : G05Location
    data class ActualJournal(val index: Int) : G05Location
    data object ActualMetadata : G05Location
}

internal data class G05Failure(
    val id: G05Id,
    val key: RequiredObligationKey?,
    val expectedAt: G05Location?,
    val submittedAt: G05Location?,
    val actualAt: G05Location?
)

internal sealed interface G05Result {
    data object Accepted : G05Result

    class Rejected(failures: List<G05Failure>) : G05Result {
        val failures: List<G05Failure> =
            Collections.unmodifiableList(failures.toList())
    }
}

internal fun assessG05(
    exactCommand: CommandRef,
    derivation: RequirementDerivation,
    handoff: CompletionHandoff,
    closure: TerminationClosure,
    latest: ControlRecordRead,
    now: BootReading
): G05Result {
    val failures = mutableListOf<G05Failure>()
    val binding = (derivation as? RequirementDerivation.Available)?.commandBinding
    val currentBody = exactCommand.captureStateAndBody().body
    val refValid = handoff.command.ref === exactCommand &&
        handoff.command.body === currentBody &&
        (binding == null || binding == handoff.command)
    if (!refValid) {
        failures += G05Failure(G05Id.REF, null, G05Location.Command, null, null)
        return G05Result.Rejected(failures)
    }

    if (derivation is RequirementDerivation.Unavailable) {
        val id = if (derivation.reason == RequiredObligationsUnavailable.DUPLICATE_REQUIRED_KEY)
            G05Id.DUPLICATE else G05Id.REQUIREMENTS
        failures += G05Failure(id, null, G05Location.Fixed(derivation.location), null, null)
    } else {
        val available = derivation as RequirementDerivation.Available
        val required = available.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val byKey = available.orderedSlots.associateBy { it.key }
        val submitted = handoff.slots.withIndex().groupBy { it.value.key }
        fun fixed(slot: RequiredSlot): G05Location.Fixed = G05Location.Fixed(
            (slot.requirement as SlotRequirement.Required).fixedSources.first().location)
        fun failure(id: G05Id, slot: RequiredSlot, index: Int?, actual: G05Location? = null) =
            G05Failure(id, slot.key, fixed(slot), index?.let(G05Location::Submitted), actual)

        // The existing coverage checker owns the structural policy. Granular failures below retain
        // the A1 and submission positions that its aggregate result intentionally does not expose.
        val coverage = checkSlotCoverage(available.orderedSlots.map { ExpectedSlot(it.key, it.necessity) },
            handoff.slots.map { SubmittedSlot(it.key, when (it.disposition) {
                is HandoffDisposition.DurablyOwned -> ObligationDisposition.DurablyOwned
                is HandoffDisposition.CompletedAndConsumed -> ObligationDisposition.CompletedAndConsumed
            }) })
        if (coverage is CoverageResult.Rejected) {
            val seen = mutableSetOf<RequiredObligationKey>()
            for ((index, item) in handoff.slots.withIndex()) {
                if (!seen.add(item.key)) {
                    val requirement = byKey[item.key]?.requirement as? SlotRequirement.Required
                    failures += G05Failure(G05Id.DUPLICATE, item.key,
                        requirement?.fixedSources?.firstOrNull()?.location?.let(G05Location::Fixed),
                        G05Location.Submitted(index), null)
                }
            }
            for (slot in required) {
                val matches = submitted[slot.key].orEmpty()
                if (matches.isEmpty()) failures += failure(
                    if (slot.key.branch == LandingBranch.L) G05Id.COVERAGE_L else G05Id.COVERAGE_N, slot, null)
            }
            for ((index, item) in handoff.slots.withIndex()) {
                if (byKey[item.key]?.requirement !is SlotRequirement.Required)
                    failures += G05Failure(G05Id.EXTRA, item.key, null, G05Location.Submitted(index), null)
            }
        }

        for (slot in required) {
            for (entry in submitted[slot.key].orEmpty()) {
                val item = entry.value
                val completion = item.disposition as? HandoffDisposition.CompletedAndConsumed ?: continue
                if (completion.completion.sourceSubject != slot.key.subject)
                    failures += failure(G05Id.SUBJECT, slot, entry.index)
            }
        }

        for (slot in required) {
            for (entry in submitted[slot.key].orEmpty()) {
                val owned = entry.value.disposition as? HandoffDisposition.DurablyOwned ?: continue
                val requirement = slot.requirement as SlotRequirement.Required
                val namedStructure = owned.linkChain.isNotEmpty() &&
                    namedChainStructurallyValid(slot, owned, exactCommand)
                val terminal = owned.linkChain.lastOrNull().takeIf { namedStructure }
                val destination = inspectDestination(owned.destination, requirement, slot.key.branch, slot.key.role,
                    terminal, latest, now)
                if (!destination.exists)
                    failures += failure(G05Id.DESTINATION, slot, entry.index, destination.actualAt)
                val confirmation = if (owned.linkChain.isEmpty())
                    if (owned.priorWrite?.binding is ConfirmationBinding.LifecycleOutput)
                        inspectEndAuthOutputConfirmation(slot, owned, exactCommand, latest)
                    else inspectRetainedConfirmation(slot, owned, latest)
                else {
                    val ownerMatches = namedChainOwnerValid(slot, owned, handoff.responsibilityOwner)
                    if (namedStructure && !ownerMatches) failures += failure(G05Id.OWNER, slot, entry.index)
                    inspectNamedConfirmation(owned, namedStructure && ownerMatches, latest)
                }
                if (!confirmation.valid)
                    failures += failure(G05Id.CONFIRMATION, slot, entry.index, confirmation.actualAt)
                if (destination.exists && destination.lowerBound != true)
                    failures += failure(G05Id.LOWER_BOUND, slot, entry.index,
                        destination.lowerBoundAt ?: destination.actualAt)
                if (requirement.allowed == AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY)
                    failures += failure(G05Id.COMPLETED_CONFLICT, slot, entry.index)
            }
        }

        for (slot in required) {
            val requirement = slot.requirement as SlotRequirement.Required
            for (entry in submitted[slot.key].orEmpty()) {
                if (entry.value.disposition !is HandoffDisposition.CompletedAndConsumed) continue
                if (requirement.allowed == AllowedSlotDisposition.DURABLY_OWNED_ONLY)
                    failures += failure(G05Id.COMPLETED_CONFLICT, slot, entry.index)
                for (actual in completionConflicts(slot, latest, available.orderedSlots))
                    failures += failure(G05Id.COMPLETED_CONFLICT, slot, entry.index, actual)
            }
        }
    }

    if (handoff.responsibilityOwner.trackingLifetime !== exactCommand.ownerTrackingLifetimeId)
        failures += G05Failure(G05Id.OWNER, null, G05Location.Command, null, null)
    if (handoff.responsibilityOwner.ownerKey.isBlank())
        failures += G05Failure(G05Id.OWNER, null, null, null, null)
    if (closure.command !== exactCommand || closure.ownerTrackingLifetimeId !== exactCommand.ownerTrackingLifetimeId ||
        closure.owner != handoff.responsibilityOwner.ownerKey)
        failures += G05Failure(G05Id.OWNER, null, null, null, G05Location.Closure)

    if (failures.isEmpty()) return G05Result.Accepted
    val positions = (derivation as? RequirementDerivation.Available)?.orderedSlots
        ?.mapIndexed { index, slot -> slot.key to index }?.toMap().orEmpty()
    return G05Result.Rejected(failures.sortedWith(compareBy<G05Failure> { it.id.ordinal }
        .thenBy { positions[it.key] ?: Int.MAX_VALUE }
        .thenBy { (it.submittedAt as? G05Location.Submitted)?.index ?: Int.MAX_VALUE }
        .thenBy { if (it.id == G05Id.COMPLETED_CONFLICT && it.actualAt == null) 0 else 1 }))
}

private data class DestinationFinding(
    val actualAt: G05Location?,
    val exists: Boolean,
    val lowerBound: Boolean? = null,
    val lowerBoundAt: G05Location? = null
)

private data class ConfirmationFinding(
    val valid: Boolean,
    val actualAt: G05Location? = null
)

private fun sameG05Row(left: ControlNode, right: ControlNode): Boolean =
    left.toPayloadEntry() == right.toPayloadEntry()

private data class FloorFixedRow(
    val node: ControlNode,
    val before: ControlNode?,
    val subjectOrigin: LifetimeId
)

private fun floorFixedRow(slot: RequiredSlot, requirement: SlotRequirement.Required,
    bound: RequiredLowerBound.Floor): FloorFixedRow? {
    fun node(root: FixedInputRoot, index: Int, facet: FixedInputFacet): ControlNode? =
        requirement.fixedSources.filter { it.location == FixedInputLocation(root, index, facet) }
            .mapNotNull { (it.fact as? FixedSourceFact.Node)?.value }
            .singleOrNull()

    val selected: ControlNode
    val before: ControlNode?
    when (val role = slot.key.role) {
        is ObligationRole.MutationAction -> {
            val root = FixedInputRoot.MUTATION_ACTION
            // A HOLD edit is a raw no-op, so its AFTER is also the original floor row.
            selected = node(root, role.index, FixedInputFacet.AFTER) ?: return null
            before = node(root, role.index, FixedInputFacet.BEFORE)
        }
        is ObligationRole.Lifecycle -> {
            val hold = role.role == LifecycleRole.HOLD
            // Descriptor validation makes target IDs unique across target and unchanged rows.
            val rows = requirement.fixedSources.filter { fixed ->
                fixed.location.facet == FixedInputFacet.WHOLE &&
                    (fixed.fact as? FixedSourceFact.LifecycleTarget)?.value?.let { row ->
                        row.target.id == bound.sourceId
                    } == true
            }
            val fixed = rows.singleOrNull() ?: return null
            val row = (fixed.fact as FixedSourceFact.LifecycleTarget).value
            val root = fixed.location.root
            val index = fixed.location.index ?: return null
            val facet = if (hold) FixedInputFacet.SOURCE else FixedInputFacet.AFTER
            selected = node(root, index, facet) ?: return null
            before = row.before
        }
        else -> return null
    }
    val parsed = (ControlObligations.read(bound.sourceKind, selected) as? ControlEntryRead.Interpreted)?.value
        ?: return null
    val floor = when (parsed) {
        is RestoredHold -> parsed.floor
        is ScheduleGuardV1 -> parsed.floor
        else -> null
    } ?: return null
    if (parsed.id != bound.sourceId || floor != bound.captured) return null
    // A recaptured guard keeps the previous floor's origin in its subject, while its bound is the AFTER floor.
    val origin = if (parsed is ScheduleGuardV1) {
        val old = before?.let { guard(it) ?: return null }
        old?.floor?.originLifetimeId ?: floor.originLifetimeId
    } else floor.originLifetimeId
    return FloorFixedRow(selected, before, origin)
}

private fun fixedNamedSource(slot: RequiredSlot, kind: ControlKind, id: String): ControlNode? =
    (slot.requirement as SlotRequirement.Required).fixedSources.mapNotNull { fixed ->
        if (fixed.location.facet != FixedInputFacet.SOURCE) return@mapNotNull null
        val node = fixed.fact as? FixedSourceFact.Node ?: return@mapNotNull null
        if (node.kind != kind) return@mapNotNull null
        val value = (ControlObligations.read(kind, node.value) as? ControlEntryRead.Interpreted)?.value
        node.value.takeIf { value?.id == id }
    }.distinctBy { it.toPayloadEntry() }.singleOrNull()

internal fun namedChainOwnerValid(slot: RequiredSlot, owned: HandoffDisposition.DurablyOwned,
    owner: ResponsibilityOwner): Boolean = owned.linkChain.all { link ->
    if ((slot.requirement as? SlotRequirement.Required)?.lowerBound.let {
            it is RequiredLowerBound.Floor || it is RequiredLowerBound.Auth
        })
        link.responsibilityOwner.ownerKey == owner.ownerKey
    else link.responsibilityOwner == owner
}

/** B1 Mutations guard successor recording and issuance predicate; G05 checks structure and owner separately. */
internal fun mutationGuardChainValid(slot: RequiredSlot, owned: HandoffDisposition.DurablyOwned,
    exactCommand: CommandRef, owner: ResponsibilityOwner): Boolean {
    val bound = (slot.requirement as? SlotRequirement.Required)?.lowerBound
    val last = owned.linkChain.lastOrNull() ?: return false
    val successorCommand = when (val confirmation = last.confirmation.binding) {
        is ConfirmationBinding.LifecycleOutput -> confirmation.command
        is ConfirmationBinding.MutationFloorOutput -> confirmation.command
        is ConfirmationBinding.RetainedSource -> return false
    }
    val chainMatches = when {
        slot.key.component == ObligationComponent.FLOOR && bound is RequiredLowerBound.Floor -> {
            val firstSourceMatches = when (bound.sourceKind) {
                ControlKind.HOLD -> owned.linkChain.first().source is TypedSourceTuple.HoldFloor
                ControlKind.DEMAND -> owned.linkChain.first().source is TypedSourceTuple.GuardFloor
                else -> false
            }
            firstSourceMatches && owned.linkChain.drop(1).all { it.source is TypedSourceTuple.GuardFloor } &&
                last.destination is TypedDestinationTuple.GuardFloor
        }
        slot.key.component == ObligationComponent.AUTH && bound is RequiredLowerBound.Auth ->
            owned.linkChain.all { it.source is TypedSourceTuple.GuardAuth &&
                it.destination is TypedDestinationTuple.GuardAuth }
        else -> false
    }
    return slot.key.role is ObligationRole.MutationAction &&
        chainMatches &&
        successorCommand !== exactCommand &&
        owned.priorWrite === last.confirmation && last.destination.locator == owned.destination &&
        namedChainStructurallyValid(slot, owned, exactCommand) &&
        namedChainOwnerValid(slot, owned, owner)
}

internal fun namedChainStructurallyValid(slot: RequiredSlot, owned: HandoffDisposition.DurablyOwned,
    exactCommand: CommandRef): Boolean {
    val links = owned.linkChain
    val first = links.firstOrNull() ?: return false
    val requirement = slot.requirement as SlotRequirement.Required
    val bound = requirement.lowerBound
    val firstMatches = when (val source = first.source) {
        is TypedSourceTuple.Request -> bound is RequiredLowerBound.Request &&
            fixedNamedSource(slot, ControlKind.DEMAND, source.parsed.id) != null
        is TypedSourceTuple.HoldFloor -> {
            val fixed = (bound as? RequiredLowerBound.Floor)?.let { floorFixedRow(slot, requirement, it) }
            // The issuer already binds this source's ID, parsed hold, and floor to originalHold.
            fixed != null && sameG05Row(fixed.node, source.source.originalHold)
        }
        is TypedSourceTuple.GuardAuth -> {
            val role = slot.key.role as? ObligationRole.MutationAction
            val after = role?.let { action ->
                requirement.fixedSources.filter {
                    it.location == FixedInputLocation(FixedInputRoot.MUTATION_ACTION, action.index,
                        FixedInputFacet.AFTER)
                }.mapNotNull { (it.fact as? FixedSourceFact.Node)?.value }.singleOrNull()
            }
            bound is RequiredLowerBound.Auth && after != null &&
                guard(after)?.auth == bound.required && sameG05Row(after, source.preimage)
        }
        is TypedSourceTuple.GuardFloor -> {
            val fixed = (bound as? RequiredLowerBound.Floor)?.let { floorFixedRow(slot, requirement, it) }
            val after = fixed?.node?.let(::guard)
            val fromAfter = fixed != null && after != null &&
                sameG05Row(fixed.node, source.preimage)
            val role = slot.key.role as? ObligationRole.Lifecycle
            val output = first.destination as? TypedDestinationTuple.GuardFloor
            // RECOVER_HOLD may first transfer its own guard BEFORE floor into its AFTER row.
            val fromOwnBefore = role?.transition == LifecycleTransition.RECOVER_HOLD &&
                role?.role == LifecycleRole.GUARD &&
                (first.confirmation.binding as? ConfirmationBinding.LifecycleOutput)?.command === exactCommand &&
                fixed?.before?.let { before ->
                    sameG05Row(before, source.preimage)
                } == true && output != null && fixed != null && after != null &&
                sameG05Row(output.row, fixed.node)
            fromAfter || fromOwnBefore
        }
    }
    if (!firstMatches || bound is RequiredLowerBound.Request && links.any {
            (it.confirmation.binding as? ConfirmationBinding.LifecycleOutput)?.command !== exactCommand
        }) return false
    val continuous = links.zipWithNext().all { (earlier, later) ->
        when (val output = earlier.destination) {
            is TypedDestinationTuple.Request -> {
                val source = later.source as? TypedSourceTuple.Request
                source != null && sameG05Row(output.row, source.preimage)
            }
            is TypedDestinationTuple.GuardFloor -> {
                val source = later.source as? TypedSourceTuple.GuardFloor
                // Issuance binds both parsed guards and the output locator to their raw rows.
                source != null && sameG05Row(output.row, source.preimage)
            }
            is TypedDestinationTuple.GuardAuth -> {
                val source = later.source as? TypedSourceTuple.GuardAuth
                source != null && sameG05Row(output.row, source.preimage)
            }
        }
    }
    return continuous && links.last().destination.locator == owned.destination
}

private fun inspectOutputRow(output: TypedDestinationTuple, latest: ControlRecordRead): ConfirmationFinding {
    val read = latest as? ControlRecordRead.Supported ?: return ConfirmationFinding(false)
    val id = when (val locator = output.locator) {
        is DestinationLocator.Payload -> locator.id
        is DestinationLocator.Guard -> locator.id
        is DestinationLocator.Journal -> return ConfirmationFinding(false)
    }
    val found = read.locations(id).singleOrNull() ?: return ConfirmationFinding(false)
    val (kind, entry) = found
    val at = G05Location.ActualPayload(kind, read.arrays.getValue(kind).entries.indexOfFirst { it === entry })
    val matches = (entry as? ControlEntryRead.Interpreted)?.original?.let { sameG05Row(it, output.row) } == true
    return ConfirmationFinding(matches, if (matches) null else at)
}

private fun inspectNamedConfirmation(owned: HandoffDisposition.DurablyOwned, structureAndOwner: Boolean,
    latest: ControlRecordRead): ConfirmationFinding {
    if (!structureAndOwner) return ConfirmationFinding(false)
    val last = owned.linkChain.last()
    if (owned.priorWrite !== last.confirmation) return ConfirmationFinding(false)
    return inspectOutputRow(last.destination, latest)
}

private fun inspectEndAuthOutputConfirmation(slot: RequiredSlot, owned: HandoffDisposition.DurablyOwned,
    exactCommand: CommandRef, latest: ControlRecordRead): ConfirmationFinding {
    if (slot.key.branch != LandingBranch.L || slot.key.component != ObligationComponent.AUTH)
        return ConfirmationFinding(false)
    val binding = owned.priorWrite?.binding as? ConfirmationBinding.LifecycleOutput
        ?: return ConfirmationFinding(false)
    val output = binding.output as? TypedDestinationTuple.GuardAuth
        ?: return ConfirmationFinding(false)
    if (binding.command !== exactCommand || output.locator != owned.destination)
        return ConfirmationFinding(false)
    return inspectOutputRow(output, latest)
}

private fun inspectRetainedConfirmation(slot: RequiredSlot, owned: HandoffDisposition.DurablyOwned,
    latest: ControlRecordRead): ConfirmationFinding {
    val retained = owned.priorWrite?.binding as? ConfirmationBinding.RetainedSource
        ?: return ConfirmationFinding(false)
    if (retained.slot != slot || retained.observed.locator != owned.destination)
        return ConfirmationFinding(false)
    val read = latest as? ControlRecordRead.Supported ?: return ConfirmationFinding(false)
    return when (val observed = retained.observed) {
        is RetainedDestinationTuple.Payload, is RetainedDestinationTuple.Guard -> {
            val id = when (observed) {
                is RetainedDestinationTuple.Payload -> observed.locator.id
                is RetainedDestinationTuple.Guard -> observed.locator.id
                else -> error("Unreachable retained destination")
            }
            val found = read.locations(id).singleOrNull() ?: return ConfirmationFinding(false)
            val (kind, entry) = found
            val at = G05Location.ActualPayload(kind,
                read.arrays.getValue(kind).entries.indexOfFirst { it === entry })
            val original = (entry as? ControlEntryRead.Interpreted)?.original
            val matches = when (observed) {
                is RetainedDestinationTuple.Payload ->
                    original?.toPayloadEntry() == observed.row.toPayloadEntry()
                is RetainedDestinationTuple.Guard ->
                    original?.toPayloadEntry() == observed.row.toPayloadEntry()
                else -> false
            }
            ConfirmationFinding(matches, if (matches) null else at)
        }
        is RetainedDestinationTuple.Journal -> {
            val canonical = NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(read.original)
                ?: return ConfirmationFinding(false)
            ConfirmationFinding(canonical.any {
                compareJournal(observed.locator.key, listOf(it)) == TypedComparison.Matches
            })
        }
        is RetainedDestinationTuple.RetirementJournal -> {
            val requirement = slot.requirement as? SlotRequirement.Required
                ?: return ConfirmationFinding(false)
            val bound = requirement.lowerBound as? RequiredLowerBound.NamespaceRetirement
                ?: return ConfirmationFinding(false)
            val sources = retirementSealSources(requirement, bound.scope, slot.key.role)
                ?: return ConfirmationFinding(false)
            val canonical = NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(read.original)
                ?: return ConfirmationFinding(false)
            if (canonical.none {
                    compareJournal(observed.locator.key, listOf(it)) == TypedComparison.Matches
                }) return ConfirmationFinding(false)
            inspectRetirementSeals(read, sources, bound.scope.after)
        }
    }
}

private fun retirementSealMatches(row: ControlNode, seal: SealV1?, source: RetirementSealSource): Boolean {
    val writer = NamespaceSettlementTransition(ControlPayloadCodec())
    return seal != null && seal.id == source.seal.id &&
        writer.immutableSealMatches(row, source.original) &&
        seal.settlement?.let { writer.witnessMatches(it, source.witness) } == true
}

private fun inspectRetirementSeals(read: ControlRecordRead.Supported,
    sources: List<RetirementSealSource>, after: FenceV1): ConfirmationFinding {
    val raw = read.original
    if (!raw.validType<String>(OWNER_UID) || !raw.validType<String>(USER_EPOCH) ||
        !raw.validType<String>(KRX_EPOCH) || !ControlLifecycleBoundary.fence(raw, after))
        return ConfirmationFinding(false, G05Location.ActualMetadata)
    val seals = read.arrays.getValue(ControlKind.SEAL).entries
    seals.forEachIndexed { index, entry ->
        if (entry is ControlEntryRead.Uninterpretable)
            return ConfirmationFinding(false, G05Location.ActualPayload(ControlKind.SEAL, index))
    }
    for (source in sources) {
        val matches = read.locations(source.seal.id)
        if (matches.isEmpty()) continue // A fixed original seal may have been consumed after issuance.
        val (kind, entry) = matches.first()
        val at = G05Location.ActualPayload(kind,
            read.arrays.getValue(kind).entries.indexOfFirst { it === entry })
        val original = (entry as? ControlEntryRead.Interpreted)?.original
        val seal = (entry as? ControlEntryRead.Interpreted)?.value as? SealV1
        if (matches.size != 1 || kind != ControlKind.SEAL || original == null ||
            !retirementSealMatches(original, seal, source))
            return ConfirmationFinding(false, at)
    }
    return ConfirmationFinding(true)
}

private fun originalAuthGuardId(requirement: SlotRequirement.Required): String? {
    val bound = requirement.lowerBound as? RequiredLowerBound.Auth ?: return null
    return requirement.fixedSources.mapNotNull { (it.fact as? FixedSourceFact.Node)?.let { source ->
        if (source.kind != ControlKind.DEMAND) return@mapNotNull null
        val guard = (ControlObligations.read(source.kind, source.value) as? ControlEntryRead.Interpreted)
            ?.value as? ScheduleGuardV1 ?: return@mapNotNull null
        if (guard.auth?.let { compareAuth(bound.required, it) != TypedComparison.Mismatch(AuthField.SCOPE) } == true)
            guard.id else null
    } }.distinct().singleOrNull()
}

private fun inspectDestination(locator: DestinationLocator, requirement: SlotRequirement.Required,
    branch: LandingBranch, requirementRole: ObligationRole, namedTerminal: NamedTransferLink?, latest: ControlRecordRead,
    now: BootReading): DestinationFinding {
    val read = latest as? ControlRecordRead.Supported ?: return DestinationFinding(null, false)
    val bound = requirement.lowerBound
    return when (locator) {
        is DestinationLocator.Payload, is DestinationLocator.Guard -> {
            val id = when (locator) {
                is DestinationLocator.Payload -> locator.id
                is DestinationLocator.Guard -> locator.id
                else -> error("Unreachable destination locator")
            }
            val found = read.locations(id).singleOrNull() ?: return DestinationFinding(null, false)
            val (kind, entry) = found
            val index = read.arrays.getValue(kind).entries.indexOfFirst { it === entry }
            val at = G05Location.ActualPayload(kind, index)
            val value = (entry as? ControlEntryRead.Interpreted)?.value
            when (locator) {
                is DestinationLocator.Payload -> {
                    val expected = when (bound) {
                        is RequiredLowerBound.Request -> ControlKind.DEMAND to bound.requiredId
                        is RequiredLowerBound.Seal -> ControlKind.SEAL to bound.source.id
                        is RequiredLowerBound.Hold -> ControlKind.HOLD to bound.source.id
                        is RequiredLowerBound.Intent -> ControlKind.RECOVERY_INTENT to bound.source.id
                        is RequiredLowerBound.ExactSource -> bound.kind to bound.id
                        is RequiredLowerBound.Floor -> bound.sourceKind to bound.sourceId
                        is RequiredLowerBound.DecisionEffect -> {
                            val effect = bound.required
                            val source = (ControlObligations.read(effect.kind, effect.node) as? ControlEntryRead.Interpreted)?.value
                            source?.let { effect.kind to it.id }
                        }
                        else -> null
                    }
                    val exists = expected == (kind to id) && kind == locator.kind && value != null
                    val comparison = if (!exists) null else when (bound) {
                        is RequiredLowerBound.Seal -> compareSeal(
                            bound.source.copy(settlement = bound.requiredSettlement), value as SealV1) == TypedComparison.Matches
                        is RequiredLowerBound.Hold -> compareHold(bound.source, value as RestoredHold) == TypedComparison.Matches
                        is RequiredLowerBound.Intent -> compareRecoveryIntent(bound.source, value as RecoveryIntentV1) == TypedComparison.Matches
                        is RequiredLowerBound.Floor -> {
                            val floor = (value as? RestoredHold)?.floor
                            val minimum = bound.captured.remainingAt(now)
                            minimum != null && compareFloor(minimum, floor, now) == TypedComparison.Matches
                        }
                        is RequiredLowerBound.Request -> {
                            val source = bound.source ?: DemandV1(bound.requiredId, bound.ownerUid,
                                bound.binding, bound.minimumIntent, bound.minimumOrder)
                            val need = RequestNeed(source, bound.minimumIntent, bound.minimumOrder)
                            val comparison = if (branch == LandingBranch.N && namedTerminal != null)
                                compareNamedRequest(need, namedTerminal, value as DemandV1)
                            else compareRequest(need, value as DemandV1)
                            comparison == TypedComparison.Matches
                        }
                        is RequiredLowerBound.ExactSource ->
                            (entry as ControlEntryRead.Interpreted).original.toPayloadEntry() == bound.node.toPayloadEntry()
                        is RequiredLowerBound.DecisionEffect ->
                            (entry as ControlEntryRead.Interpreted).original.toPayloadEntry() == bound.required.node.toPayloadEntry()
                        else -> false
                    }
                    DestinationFinding(at, exists, comparison)
                }
                is DestinationLocator.Guard -> {
                    val guard = value as? ScheduleGuardV1
                    val namedFloor = namedTerminal?.destination as? TypedDestinationTuple.GuardFloor
                    val namedAuth = namedTerminal?.destination as? TypedDestinationTuple.GuardAuth
                    val transferredHold = namedFloor != null
                    val exists = kind == ControlKind.DEMAND && guard != null && when (locator.part) {
                        GuardPart.FLOOR -> bound is RequiredLowerBound.Floor && guard.floor != null &&
                            (transferredHold || bound.sourceKind == ControlKind.DEMAND && locator.id == bound.sourceId)
                        GuardPart.AUTH -> bound is RequiredLowerBound.Auth &&
                            originalAuthGuardId(requirement) == locator.id && guard.auth?.let {
                                compareAuth(bound.required, it) != TypedComparison.Mismatch(AuthField.SCOPE)
                            } == true
                    }
                    val comparison = if (!exists) null else when (bound) {
                        is RequiredLowerBound.Floor -> {
                            val representative = if (transferredHold) namedFloor?.parsed?.floor
                            else bound.captured
                            representative?.remainingAt(now)?.let {
                                compareFloor(it, guard?.floor, now) == TypedComparison.Matches
                            } ?: false
                        }
                        is RequiredLowerBound.Auth -> {
                            val requiredAuth = namedAuth?.parsed?.auth ?: bound.required
                            compareAuth(requiredAuth, checkNotNull(guard?.auth)) == TypedComparison.Matches
                        }
                        else -> false
                    }
                    DestinationFinding(at, exists, comparison)
                }
                else -> error("Unreachable destination locator")
            }
        }
        is DestinationLocator.Journal -> {
            val canonical = NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(read.original)
                ?: return DestinationFinding(null, false)
            val index = canonical.indexOfFirst { compareJournal(locator.key, listOf(it)) == TypedComparison.Matches }
            if (index < 0) return DestinationFinding(null, false)
            val at = G05Location.ActualJournal(index)
            val expected = when (bound) {
                is RequiredLowerBound.Journal -> bound.key
                is RequiredLowerBound.NamespaceRetirement -> bound.scope.journalKey
                else -> null
            }
            val exists = expected == locator.key
            val sources = (bound as? RequiredLowerBound.NamespaceRetirement)?.let {
                retirementSealSources(requirement, it.scope, requirementRole)
            }
            val lower = if (exists && sources != null)
                inspectRetirementSeals(read, sources, (bound as RequiredLowerBound.NamespaceRetirement).scope.after)
            else null
            DestinationFinding(at, exists, if (exists) (lower?.valid ?: true) else null, lower?.actualAt)
        }
    }
}

private fun completionConflicts(
    slot: RequiredSlot,
    latest: ControlRecordRead,
    allSlots: List<RequiredSlot>
): List<G05Location> {
    if (latest !is ControlRecordRead.Supported) return listOf(G05Location.ActualMetadata)
    val requirement = slot.requirement as SlotRequirement.Required
    val bound = requirement.lowerBound
    val result = linkedSetOf<G05Location>()

    fun rows(kind: ControlKind, matching: (ControlObligationV1) -> Boolean) {
        latest.arrays.getValue(kind).entries.forEachIndexed { index, entry ->
            val at = G05Location.ActualPayload(kind, index)
            when (entry) {
                is ControlEntryRead.Interpreted -> if (matching(entry.value)) result += at
                is ControlEntryRead.Uninterpretable -> result += at // Its id or scope may be unreadable.
            }
        }
    }
    fun sameId(kind: ControlKind, id: String) = rows(kind) { it.id == id }
    fun journal(target: JournalTargetV1) {
        val canonical = NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(latest.original)
        if (canonical == null) {
            result += G05Location.ActualMetadata
        } else {
            canonical.forEachIndexed { index, entry ->
                if (compareJournal(target, listOf(entry)) != TypedComparison.Mismatch(JournalField.ABSENT))
                    result += G05Location.ActualJournal(index)
            }
        }
    }
    fun fence(before: FenceV1, after: FenceV1) {
        val raw = latest.original
        if (!raw.validType<String>(OWNER_UID) || !raw.validType<String>(USER_EPOCH) ||
            !raw.validType<String>(KRX_EPOCH)) {
            result += G05Location.ActualMetadata
            return
        }
        val current = FenceV1(raw[OWNER_UID], raw[USER_EPOCH], raw[KRX_EPOCH])
        if ((before.ownerUid != after.ownerUid && current.ownerUid == before.ownerUid) ||
            (before.userAccessEpoch != after.userAccessEpoch && current.userAccessEpoch == before.userAccessEpoch) ||
            (before.krxCapabilityEpoch != after.krxCapabilityEpoch && current.krxCapabilityEpoch == before.krxCapabilityEpoch))
            result += G05Location.ActualMetadata
    }
    fun seal(source: SealV1, expected: SettlementEvidence?) {
        rows(ControlKind.SEAL) { value ->
            val actual = value as SealV1
            actual.id == source.id && (actual.kind != source.kind || actual.key != source.key ||
                actual.settlement == null || (expected != null && actual.settlement != expected))
        }
    }
    fun guard(id: String) {
        rows(ControlKind.DEMAND) { value ->
            value.id == id && (value !is ScheduleGuardV1 || value.floor != null || value.auth != null)
        }
    }
    fun auth(required: AuthSnapshotV1) {
        rows(ControlKind.DEMAND) { value ->
            val actual = (value as? ScheduleGuardV1)?.auth
            actual != null && actual.ownerUid == required.ownerUid && actual.binding == required.binding &&
                actual.originLifetimeId == required.originLifetimeId && actual.authGeneration == required.authGeneration
        }
    }

    when (bound) {
        is RequiredLowerBound.Request -> sameId(ControlKind.DEMAND, bound.requiredId)
        is RequiredLowerBound.Journal -> journal(bound.key)
        is RequiredLowerBound.Hold -> sameId(ControlKind.HOLD, bound.source.id)
        is RequiredLowerBound.Intent -> sameId(ControlKind.RECOVERY_INTENT, bound.source.id)
        is RequiredLowerBound.Seal -> seal(bound.source, bound.requiredSettlement)
        is RequiredLowerBound.ExactSource -> {
            require(bound.kind == ControlKind.DEMAND)
            guard(bound.id)
        }
        is RequiredLowerBound.Floor -> rows(bound.sourceKind) { false }
        is RequiredLowerBound.Auth -> auth(bound.required)
        is RequiredLowerBound.DecisionEffect -> {
            val effect = bound.required
            when (val value = (ControlObligations.read(effect.kind, effect.node) as? ControlEntryRead.Interpreted)?.value) {
                is DemandV1 -> sameId(ControlKind.DEMAND, value.id)
                is ScheduleGuardV1 -> guard(value.id)
                is RestoredHold -> sameId(ControlKind.HOLD, value.id)
                is RecoveryIntentV1 -> sameId(ControlKind.RECOVERY_INTENT, value.id)
                is SealV1 -> seal(value, null)
                else -> rows(effect.kind) { false }
            }
        }
        is RequiredLowerBound.DecisionNamespace -> fence(bound.before, bound.after)
        is RequiredLowerBound.NamespaceRetirement -> {
            val scope = bound.scope
            if (scope.sourceId != null) {
                val sourceKind = allSlots.asSequence().mapNotNull { (it.requirement as? SlotRequirement.Required)?.lowerBound }
                    .firstNotNullOfOrNull { candidate -> when (candidate) {
                        is RequiredLowerBound.Hold -> ControlKind.HOLD.takeIf { candidate.source.id == scope.sourceId }
                        is RequiredLowerBound.Intent -> ControlKind.RECOVERY_INTENT.takeIf { candidate.source.id == scope.sourceId }
                        is RequiredLowerBound.Seal -> ControlKind.SEAL.takeIf { candidate.source.id == scope.sourceId }
                        else -> null
                    } }
                if (sourceKind == ControlKind.SEAL) {
                    val sources = retirementSealSources(requirement, scope, slot.key.role)
                    if (sources == null) result += G05Location.ActualMetadata
                    else {
                        // An opaque SEAL can hide any fixed id, including one it cannot expose.
                        rows(ControlKind.SEAL) { false }
                        for (source in sources) {
                            val matches = latest.locations(source.seal.id)
                            for ((kind, entry) in matches) {
                                val at = G05Location.ActualPayload(kind,
                                    latest.arrays.getValue(kind).entries.indexOfFirst { it === entry })
                                val actual = (entry as? ControlEntryRead.Interpreted)?.value as? SealV1
                                val witness = actual?.settlement
                                val eligible = matches.size == 1 && kind == ControlKind.SEAL && actual != null &&
                                    actual.kind == source.seal.kind && actual.key == source.seal.key &&
                                    witness is SettlementEvidenceV1 && when (slot.key.branch) {
                                        LandingBranch.L -> witness == source.witness
                                        LandingBranch.N -> witness.operation == StoreOp.BEGIN_ROTATION &&
                                            witness.before == scope.before && witness.after == scope.after &&
                                            witness.journal == scope.journalKey
                                    }
                                if (!eligible) result += at
                            }
                        }
                    }
                } else sameId(checkNotNull(sourceKind), scope.sourceId)
            }
            journal(scope.journalKey)
            fence(scope.before, scope.after)
        }
        is RequiredLowerBound.Query, is RequiredLowerBound.Binding, is RequiredLowerBound.Receipt -> Unit
    }
    return result.toList()
}
