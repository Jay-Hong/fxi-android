package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.RecordTransactionResult
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
}

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
            outputTarget: LifecycleTarget
        ): LifecycleOutputConfirmationResult {
            fun reject(reason: LifecycleConfirmationFailure) = LifecycleOutputConfirmationResult.Rejected(reason)
            val body = exactCommand.captureStateAndBody().body as? ControlCommandBody.Lifecycle
            if (confirmed.command !== exactCommand || body == null || body.input !== fixed ||
                exactCommand.id != fixed.operationId)
                return reject(LifecycleConfirmationFailure.REF_OR_DESCRIPTOR_MISMATCH)

            val outputFixed = fixed.targets.singleOrNull { it.target == outputTarget }
            val supported = outputFixed != null && when (fixed.transition) {
                LifecycleTransition.REBIND_REQUESTS, LifecycleTransition.END_AUTH_BINDING ->
                    outputFixed.role == LifecycleRole.REQUEST && outputTarget.kind == ControlKind.DEMAND &&
                        outputTarget.effect == LifecycleEffect.REPLACE && outputFixed.before != null
                LifecycleTransition.RECOVER_HOLD ->
                    outputFixed.role == LifecycleRole.GUARD && outputTarget.kind == ControlKind.DEMAND &&
                        outputTarget.effect == LifecycleEffect.REPLACE && outputFixed.before != null
                else -> false
            }
            if (!supported) return reject(LifecycleConfirmationFailure.UNSUPPORTED_TRANSITION_OR_TARGET)
            val selected = checkNotNull(outputFixed)
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
            val output = when (fixed.transition) {
                LifecycleTransition.REBIND_REQUESTS, LifecycleTransition.END_AUTH_BINDING -> {
                    val parsed = entry.value as? DemandV1
                        ?: return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
                    TypedDestinationTuple.Request(DestinationLocator.Payload(outputTarget.kind, outputTarget.id),
                        entry.original, parsed)
                }
                LifecycleTransition.RECOVER_HOLD -> {
                    val parsed = entry.value as? ScheduleGuardV1
                        ?: return reject(LifecycleConfirmationFailure.SNAPSHOT_OUTPUT_MISMATCH)
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

        private fun confirmRetained(slot: RequiredSlot, destination: DestinationLocator,
            read: ControlRecordRead.Supported, evidence: RecordTransactionEvidence): RetainedSourceConfirmationResult {
            fun reject(reason: RetainedConfirmationFailure) = RetainedSourceConfirmationResult.Rejected(reason)
            if (!retainedSourceRequired(slot))
                return reject(RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
            val requirement = slot.requirement as SlotRequirement.Required
            val bound = requirement.lowerBound
            val subject = slot.key.subject
            val subjectMatches = when (bound) {
                is RequiredLowerBound.Hold -> subject == ObligationSubject.Hold(bound.source.id,
                    bound.source.originLifetimeId, bound.source.binding, bound.source.provenance, bound.source.axes) &&
                    destination == DestinationLocator.Payload(ControlKind.HOLD, bound.source.id) &&
                    originalSourceRow(requirement, ControlKind.HOLD, bound.source.id)?.let { original ->
                        val fixed = (ControlObligations.read(ControlKind.HOLD, original)
                            as? ControlEntryRead.Interpreted)?.value as? RestoredHold
                        fixed != null && compareHold(bound.source, fixed) == TypedComparison.Matches
                    } == true
                is RequiredLowerBound.Floor -> subject == ObligationSubject.Floor(bound.sourceKind,
                    bound.sourceId, bound.captured.originLifetimeId) &&
                    originalSourceRow(requirement, bound.sourceKind, bound.sourceId)?.let { original ->
                        when (val value = (ControlObligations.read(bound.sourceKind, original)
                            as? ControlEntryRead.Interpreted)?.value) {
                            is RestoredHold -> value.id == bound.sourceId && value.floor == bound.captured
                            is ScheduleGuardV1 -> value.id == bound.sourceId && value.floor == bound.captured
                            else -> false
                        }
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
                    originalSourceRow(requirement, ControlKind.RECOVERY_INTENT, bound.source.id)?.let {
                        (ControlObligations.read(ControlKind.RECOVERY_INTENT, it) as? ControlEntryRead.Interpreted)
                            ?.value == bound.source
                    } == true
                is RequiredLowerBound.Seal -> subject == ObligationSubject.Seal(bound.source.id,
                    bound.source.kind, bound.source.key) &&
                    destination == DestinationLocator.Payload(ControlKind.SEAL, bound.source.id) &&
                    originalSourceRow(requirement, ControlKind.SEAL, bound.source.id)?.let {
                        (ControlObligations.read(ControlKind.SEAL, it) as? ControlEntryRead.Interpreted)
                            ?.value == bound.source
                    } == true
                is RequiredLowerBound.Request -> bound.source?.let { source ->
                    subject == ObligationSubject.Request(source.id, source.ownerUid, source.binding, source.raisedAt) &&
                        destination == DestinationLocator.Payload(ControlKind.DEMAND, bound.requiredId) &&
                        source.id == bound.requiredId && source.ownerUid == bound.ownerUid &&
                        originalSourceRow(requirement, ControlKind.DEMAND, source.id)?.let {
                            (ControlObligations.read(ControlKind.DEMAND, it) as? ControlEntryRead.Interpreted)
                                ?.value == source
                        } == true
                } == true
                else -> false
            }
            if (!subjectMatches) return reject(RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
            val result = when (destination) {
                is DestinationLocator.Payload -> {
                    val found = read.locations(destination.id).singleOrNull()
                    val entry = found?.second as? ControlEntryRead.Interpreted
                    val original = originalSourceRow(requirement, destination.kind, destination.id)
                    if (entry == null ||
                        (bound !is RequiredLowerBound.Intent && !retainedRowMatches(bound, entry)) ||
                        (bound is RequiredLowerBound.Hold || bound is RequiredLowerBound.Floor ||
                            bound is RequiredLowerBound.Intent || bound is RequiredLowerBound.Seal ||
                            bound is RequiredLowerBound.Request) &&
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
                    else RetainedDestinationTuple.Journal(destination)
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
}

internal sealed interface NamedTransferResult {
    data class Issued(val value: NamedTransferLink) : NamedTransferResult
    data class Rejected(val reason: NamedTransferFailure) : NamedTransferResult
}

internal class NamedTransferLink private constructor(
    val transition: LifecycleTransition,
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

            val binding = confirmation.binding as? ConfirmationBinding.LifecycleOutput
                ?: return invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)
            val fixed = binding.fixed
            val selected = fixed.targets.singleOrNull { it.target == binding.outputTarget }
            val supported = selected != null && when (fixed.transition) {
                LifecycleTransition.REBIND_REQUESTS, LifecycleTransition.END_AUTH_BINDING ->
                    fixedSource is TypedSourceTuple.Request && destination is TypedDestinationTuple.Request
                LifecycleTransition.RECOVER_HOLD ->
                    (fixedSource is TypedSourceTuple.HoldFloor || fixedSource is TypedSourceTuple.GuardFloor) &&
                        destination is TypedDestinationTuple.GuardFloor
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
                is TypedSourceTuple.GuardFloor ->
                    recoverySourceMatches && row.target.kind == ControlKind.DEMAND &&
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
            }
            if (!destinationMatches) return invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)
            if (fixedSource.responsibilityOwner.trackingLifetime !== binding.command.ownerTrackingLifetimeId ||
                fixedSource.responsibilityOwner.ownerKey.isBlank())
                return invalid(NamedTransferFailureKind.OWNER_MISMATCH)

            val confirmed = binding.output
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
            if (fixedSource is TypedSourceTuple.GuardFloor && destination is TypedDestinationTuple.GuardFloor) {
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
                val destination = inspectDestination(owned.destination, requirement, latest, now)
                if (!destination.exists)
                    failures += failure(G05Id.DESTINATION, slot, entry.index, destination.actualAt)
                // Named chains are checked in A3b2b; only the unchanged source can use an empty chain.
                val confirmation = if (owned.linkChain.isEmpty())
                    inspectRetainedConfirmation(slot, owned, latest) else ConfirmationFinding(false)
                if (!confirmation.valid)
                    failures += failure(G05Id.CONFIRMATION, slot, entry.index, confirmation.actualAt)
                if (destination.exists && destination.lowerBound != true)
                    failures += failure(G05Id.LOWER_BOUND, slot, entry.index, destination.actualAt)
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
                for (actual in completionConflicts(requirement.lowerBound, slot.key, latest, available.orderedSlots))
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
    val lowerBound: Boolean? = null
)

private data class ConfirmationFinding(
    val valid: Boolean,
    val actualAt: G05Location? = null
)

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
    }
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
    latest: ControlRecordRead, now: BootReading): DestinationFinding {
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
                            compareRequest(RequestNeed(source, bound.minimumIntent, bound.minimumOrder), value as DemandV1) ==
                                TypedComparison.Matches
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
                    val exists = kind == ControlKind.DEMAND && guard != null && when (locator.part) {
                        GuardPart.FLOOR -> bound is RequiredLowerBound.Floor && bound.sourceKind == ControlKind.DEMAND &&
                            locator.id == bound.sourceId && guard.floor != null
                        GuardPart.AUTH -> bound is RequiredLowerBound.Auth &&
                            originalAuthGuardId(requirement) == locator.id && guard.auth?.let {
                                compareAuth(bound.required, it) != TypedComparison.Mismatch(AuthField.SCOPE)
                            } == true
                    }
                    val comparison = if (!exists) null else when (bound) {
                        is RequiredLowerBound.Floor -> bound.captured.remainingAt(now)?.let {
                            compareFloor(it, guard?.floor, now) == TypedComparison.Matches
                        } ?: false
                        is RequiredLowerBound.Auth -> compareAuth(bound.required, checkNotNull(guard?.auth)) ==
                            TypedComparison.Matches
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
            DestinationFinding(at, exists, if (exists) true else null)
        }
    }
}

private fun completionConflicts(
    bound: RequiredLowerBound,
    key: RequiredObligationKey,
    latest: ControlRecordRead,
    allSlots: List<RequiredSlot>
): List<G05Location> {
    if (latest !is ControlRecordRead.Supported) return listOf(G05Location.ActualMetadata)
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
                sameId(checkNotNull(sourceKind), scope.sourceId)
            }
            journal(scope.journalKey)
            fence(scope.before, scope.after)
        }
        is RequiredLowerBound.Query, is RequiredLowerBound.Binding, is RequiredLowerBound.Receipt -> Unit
    }
    return result.toList()
}
