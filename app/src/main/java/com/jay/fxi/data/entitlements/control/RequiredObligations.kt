package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RefreshIntent
import java.util.Collections

internal sealed interface RequirementInput {
    val exactCommand: CommandRef

    data class Mutations(
        override val exactCommand: CommandRef,
        val body: ControlCommandBody.Mutations,
        val adoption: MutationAdoption
    ) : RequirementInput

    data class Rotation(
        override val exactCommand: CommandRef,
        val body: ControlCommandBody.RotateAndSettle
    ) : RequirementInput

    data class Settlement(
        override val exactCommand: CommandRef,
        val body: ControlCommandBody.Handover
    ) : RequirementInput

    data class Lifecycle(
        override val exactCommand: CommandRef,
        val body: ControlCommandBody.Lifecycle
    ) : RequirementInput
}

internal sealed interface MutationAdoption {
    data class Current(val targets: List<ControlCommandTarget?>?) : MutationAdoption
    data class Previous(val checkpoint: ControlCommandCheckpoint?) : MutationAdoption
}

internal sealed interface FixedCommandKind {
    data object Mutations : FixedCommandKind
    data object Rotation : FixedCommandKind
    data class Settlement(val transition: HandoverSettlementTransition) : FixedCommandKind
    data class Lifecycle(val transition: LifecycleTransition) : FixedCommandKind
}

internal data class ExactCommandBinding(
    val ref: CommandRef,
    val body: ControlCommandBody,
    val kind: FixedCommandKind
) {
    val commandId: String get() = ref.id
    val trackingLifetime: OwnerTrackingLifetimeId get() = ref.ownerTrackingLifetimeId
}

internal enum class FixedInputRoot {
    COMMAND, MUTATION_ACTION, MUTATION_ADOPTION, PREVIOUS_CHECKPOINT,
    ROTATION_TARGET, ROTATION_INPUT, SETTLEMENT_TARGET, SETTLEMENT_INPUT,
    LIFECYCLE_TARGET, LIFECYCLE_UNCHANGED, LIFECYCLE_NAMESPACE,
    LIFECYCLE_PLAN, DECISION_EFFECT
}

internal enum class FixedInputFacet {
    WHOLE, BEFORE, AFTER, SOURCE, DEMAND, FENCE, JOURNAL, RETIREMENT,
    QUERY, BINDING, EVENT, DECISION, EFFECT, RECEIPT, FLOOR, GRANT, CLOSURE
}

internal data class FixedInputLocation(
    val root: FixedInputRoot,
    val index: Int?,
    val facet: FixedInputFacet
)

internal sealed interface FixedSourceFact {
    data class RotationInput(val value: RotateAndSettleNamespaces) : FixedSourceFact
    data class SettlementInput(val value: HandoverSettlementInput) : FixedSourceFact
    data class Mutation(val value: ControlMutation) : FixedSourceFact
    data class Node(val kind: ControlKind, val value: ControlNode) : FixedSourceFact
    data class Adoption(val value: ControlCommandTarget) : FixedSourceFact
    data class Checkpoint(val value: ControlCommandCheckpoint) : FixedSourceFact
    data class Fence(val value: FenceV1) : FixedSourceFact
    data class Demand(val value: SettlementDemand) : FixedSourceFact
    data class Executor(val value: SettlementExecutor) : FixedSourceFact
    data class LifecycleTarget(val value: LifecycleFixedTarget) : FixedSourceFact
    data class LifecyclePlan(val value: ControlLifecycleDescriptor) : FixedSourceFact
    data class Namespace(val value: LifecycleNamespacePostcondition) : FixedSourceFact
    data class Binding(val value: LifecycleBinding) : FixedSourceFact
    data class BindingClosure(val value: LifecycleBindingClosure) : FixedSourceFact
    data class RecoveryClosure(val value: HoldRecoveryClosure) : FixedSourceFact
    data class AuthEvent(val value: LifecycleAuthEvent) : FixedSourceFact
    data class Query(val value: LifecycleQueryRegistration) : FixedSourceFact
    data class Decision(val value: AcceptedQueryDecision) : FixedSourceFact
    data class DecisionEffect(val value: LifecycleDurableEffect) : FixedSourceFact
    data class OrderGrant(val value: LifecycleOrderGrant) : FixedSourceFact
    data class Boot(val value: BootReading) : FixedSourceFact
    data class Retirement(val value: RecoveryRetirementPlan) : FixedSourceFact
}

internal data class FixedSourceEvidence(
    val location: FixedInputLocation,
    val fact: FixedSourceFact
)

internal data class QueryScope(
    val registration: LifecycleQueryRegistration,
    val source: LifecycleQuerySource?
) : ObligationSubject

internal data class BindingScope(
    val binding: LifecycleBinding,
    val closure: LifecycleBindingClosure?
) : ObligationSubject

internal data class ReceiptScope(
    val operationId: String,
    val transition: LifecycleTransition,
    val queryId: String?,
    val target: LifecycleTarget?
) : ObligationSubject

internal data class RetirementScope(
    val sourceId: String?,
    val before: FenceV1,
    val after: FenceV1,
    val journalKey: JournalTargetV1
) : ObligationSubject

internal data class DecisionEffectScope(
    val operationId: String,
    val queryId: String,
    val effectIndex: Int,
    val kind: ControlKind,
    val node: ControlNode
) : ObligationSubject

internal data class DecisionNamespaceScope(
    val operationId: String,
    val queryId: String,
    val before: FenceV1,
    val after: FenceV1
) : ObligationSubject

internal sealed interface RequiredLowerBound {
    data class ExactSource(
        val kind: ControlKind,
        val id: String,
        val node: ControlNode
    ) : RequiredLowerBound
    data class Request(
        val source: DemandV1?,
        val requiredId: String,
        val ownerUid: String?,
        val binding: Long,
        val minimumIntent: RefreshIntent,
        val minimumOrder: EventOrderV1
    ) : RequiredLowerBound
    data class Seal(
        val source: SealV1,
        val requiredSettlement: SettlementEvidence?
    ) : RequiredLowerBound
    data class Hold(val source: RestoredHold) : RequiredLowerBound
    data class Intent(val source: RecoveryIntentV1) : RequiredLowerBound
    data class Journal(val key: JournalTargetV1) : RequiredLowerBound
    data class Floor(
        val sourceKind: ControlKind,
        val sourceId: String,
        val captured: FloorV1
    ) : RequiredLowerBound
    data class Auth(val required: AuthSnapshotV1) : RequiredLowerBound
    data class Query(val scope: QueryScope) : RequiredLowerBound
    data class Binding(val scope: BindingScope) : RequiredLowerBound
    data class Receipt(val scope: ReceiptScope) : RequiredLowerBound
    data class NamespaceRetirement(val scope: RetirementScope) : RequiredLowerBound
    data class DecisionEffect(val required: LifecycleDurableEffect) : RequiredLowerBound
    data class DecisionNamespace(val before: FenceV1, val after: FenceV1) : RequiredLowerBound
}

internal enum class AllowedSlotDisposition {
    DURABLY_OWNED_ONLY,
    COMPLETED_AND_CONSUMED_ONLY,
    EITHER
}

internal sealed interface SlotRequirement {
    data class Required(
        val lowerBound: RequiredLowerBound,
        val allowed: AllowedSlotDisposition,
        val fixedSources: List<FixedSourceEvidence>
    ) : SlotRequirement

    data class NotRequiredByContract(
        val fixedSources: List<FixedSourceEvidence>
    ) : SlotRequirement
}

internal data class RequiredSlot(
    val key: RequiredObligationKey,
    val requirement: SlotRequirement
) {
    val necessity: SlotNecessity
        get() = when (requirement) {
            is SlotRequirement.Required -> SlotNecessity.Required
            is SlotRequirement.NotRequiredByContract -> SlotNecessity.NotRequiredByContract
        }
}

internal enum class RequiredObligationsUnavailable {
    BODY_UNAVAILABLE,
    BODY_MISMATCH,
    COMMAND_ID_MISMATCH,
    ADOPTION_VECTOR_MISSING,
    ADOPTION_VECTOR_SIZE_MISMATCH,
    CHECKPOINT_MISSING,
    CHECKPOINT_INCOMPLETE,
    CHECKPOINT_MISMATCH,
    PREPARED_ACTION_UNINTERPRETABLE,
    NAMED_SOURCE_UNINTERPRETABLE,
    PLAN_MISSING,
    DECISION_MISSING,
    DECISION_EFFECT_MISSING,
    FIXED_INPUT_INCONSISTENT,
    DUPLICATE_REQUIRED_KEY,
    UNSUPPORTED_IN_THIS_UNIT // Removed when A1c implements the remaining branches.
}

internal sealed interface RequirementDerivation {
    data class Available(
        val commandBinding: ExactCommandBinding,
        val orderedSlots: List<RequiredSlot>
    ) : RequirementDerivation

    data class Unavailable(
        val reason: RequiredObligationsUnavailable,
        val location: FixedInputLocation
    ) : RequirementDerivation
}

internal fun deriveRequiredObligations(
    input: RequirementInput
): RequirementDerivation = when (input) {
    is RequirementInput.Mutations -> deriveMutationObligations(input)
    is RequirementInput.Rotation -> deriveRotationObligations(input)
    is RequirementInput.Settlement -> deriveSettlementObligations(input)
    is RequirementInput.Lifecycle -> deriveLifecycleObligations(input)
}

private fun deriveMutationObligations(input: RequirementInput.Mutations): RequirementDerivation {
    fun unavailable(reason: RequiredObligationsUnavailable, root: FixedInputRoot, index: Int?, facet: FixedInputFacet) =
        RequirementDerivation.Unavailable(reason, FixedInputLocation(root, index, facet))
    val commandRoot = FixedInputRoot.COMMAND
    val body = input.exactCommand.captureStateAndBody().body
        ?: return unavailable(RequiredObligationsUnavailable.BODY_UNAVAILABLE, commandRoot, null, FixedInputFacet.WHOLE)
    if (body !== input.body || body !is ControlCommandBody.Mutations)
        return unavailable(RequiredObligationsUnavailable.BODY_MISMATCH, commandRoot, null, FixedInputFacet.WHOLE)

    val actions = body.actions
    if (actions.isEmpty()) return unavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT,
        commandRoot, null, FixedInputFacet.WHOLE)
    val current = input.adoption as? MutationAdoption.Current
    val checkpoint = (input.adoption as? MutationAdoption.Previous)?.checkpoint
    val targets = if (current != null) {
        current.targets?.toList() ?: return unavailable(RequiredObligationsUnavailable.ADOPTION_VECTOR_MISSING,
            FixedInputRoot.MUTATION_ADOPTION, null, FixedInputFacet.WHOLE)
    } else {
        checkpoint ?: return unavailable(RequiredObligationsUnavailable.CHECKPOINT_MISSING,
            FixedInputRoot.PREVIOUS_CHECKPOINT, null, FixedInputFacet.WHOLE)
        if (checkpoint.command !== input.exactCommand)
            return unavailable(RequiredObligationsUnavailable.CHECKPOINT_MISMATCH,
                FixedInputRoot.PREVIOUS_CHECKPOINT, null, FixedInputFacet.WHOLE)
        if (!checkpoint.confirmationRequested || checkpoint.targets.size != actions.size)
            return unavailable(RequiredObligationsUnavailable.CHECKPOINT_INCOMPLETE,
                FixedInputRoot.PREVIOUS_CHECKPOINT, null, FixedInputFacet.WHOLE)
        checkpoint.targets
    }
    if (current != null && targets.size != actions.size)
        return unavailable(RequiredObligationsUnavailable.ADOPTION_VECTOR_SIZE_MISMATCH,
            FixedInputRoot.MUTATION_ADOPTION, null, FixedInputFacet.WHOLE)

    val slots = mutableListOf<RequiredSlot>()
    val keys = mutableSetOf<RequiredObligationKey>()
    for ((index, action) in actions.withIndex()) {
        val actionRoot = FixedInputRoot.MUTATION_ACTION
        val targetRoot = if (current != null) FixedInputRoot.MUTATION_ADOPTION else FixedInputRoot.PREVIOUS_CHECKPOINT
        val targetFacet = if (current != null) FixedInputFacet.WHOLE else FixedInputFacet.AFTER
        val target = targets[index] ?: return unavailable(
            if (current != null) RequiredObligationsUnavailable.ADOPTION_VECTOR_MISSING
            else RequiredObligationsUnavailable.CHECKPOINT_INCOMPLETE, targetRoot, index, targetFacet)

        val beforeNode = (action as? ControlMutation.Edit)?.before
        val before = beforeNode?.let { (ControlObligations.read(action.kind, it) as? ControlEntryRead.Interpreted)?.value }
        if (beforeNode != null && before == null)
            return unavailable(RequiredObligationsUnavailable.PREPARED_ACTION_UNINTERPRETABLE,
                actionRoot, index, FixedInputFacet.BEFORE)
        val afterNode = when (action) {
            is ControlMutation.Add -> (action.built as? ControlWriteResult.Written)?.node
            is ControlMutation.Edit -> (action.changed as? ControlWriteResult.Written)?.node
        } ?: return unavailable(RequiredObligationsUnavailable.PREPARED_ACTION_UNINTERPRETABLE,
            actionRoot, index, FixedInputFacet.AFTER)
        val after = (ControlObligations.read(action.kind, afterNode) as? ControlEntryRead.Interpreted)?.value
            ?: return unavailable(RequiredObligationsUnavailable.PREPARED_ACTION_UNINTERPRETABLE,
                actionRoot, index, FixedInputFacet.AFTER)
        val adopted = (ControlObligations.read(action.kind, target.postcondition) as? ControlEntryRead.Interpreted)?.value
        val matches = if (target.joined) {
            action is ControlMutation.Add && after is SealV1 && adopted is SealV1 &&
                target.id == adopted.id && after.key == adopted.key &&
                adopted.settlement == null
        } else {
            target.id == after.id &&
                target.postcondition.toPayloadEntry() == afterNode.toPayloadEntry()
        }
        if (!matches) return unavailable(
            if (current != null) RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT
            else RequiredObligationsUnavailable.CHECKPOINT_MISMATCH, targetRoot, index, targetFacet)

        val fixed = mutableListOf(
            FixedSourceEvidence(FixedInputLocation(actionRoot, index, FixedInputFacet.WHOLE), FixedSourceFact.Mutation(action))
        )
        if (beforeNode != null) fixed += FixedSourceEvidence(
            FixedInputLocation(actionRoot, index, FixedInputFacet.BEFORE), FixedSourceFact.Node(action.kind, beforeNode))
        fixed += FixedSourceEvidence(FixedInputLocation(actionRoot, index, FixedInputFacet.AFTER),
            FixedSourceFact.Node(action.kind, afterNode))
        if (current == null) fixed += FixedSourceEvidence(
            FixedInputLocation(targetRoot, null, FixedInputFacet.WHOLE), FixedSourceFact.Checkpoint(checkpoint!!))
        fixed += FixedSourceEvidence(FixedInputLocation(targetRoot, index, targetFacet), FixedSourceFact.Adoption(target))
        val sources = Collections.unmodifiableList(fixed.toList())

        val firstSlot = slots.size
        fun addPair(subject: ObligationSubject, component: ObligationComponent,
                    landing: RequiredLowerBound, nonLanding: RequiredLowerBound = landing,
                    allowed: AllowedSlotDisposition = AllowedSlotDisposition.EITHER) {
            for ((branch, bound) in listOf(LandingBranch.L to landing, LandingBranch.N to nonLanding)) {
                val key = RequiredObligationKey(ObligationRole.MutationAction(index), subject, component, branch)
                slots += RequiredSlot(key, SlotRequirement.Required(bound, allowed, sources))
            }
        }

        when (val source = before ?: after) {
            is DemandV1 -> {
                val required = after as DemandV1
                addPair(ObligationSubject.Request(source.id, source.ownerUid, source.binding, source.raisedAt),
                    ObligationComponent.REQUEST,
                    RequiredLowerBound.Request(required, source.id, source.ownerUid, source.binding,
                        required.intent, required.raisedAt),
                    RequiredLowerBound.Request(source, source.id, source.ownerUid, source.binding,
                        required.intent, required.raisedAt))
            }
            is SealV1 -> {
                val seal = if (target.joined) adopted as SealV1 else after as SealV1
                addPair(ObligationSubject.Seal(seal.id, seal.kind, seal.key), ObligationComponent.SEAL,
                    RequiredLowerBound.Seal(seal, seal.settlement))
            }
            is ScheduleGuardV1 -> {
                val required = after as ScheduleGuardV1
                required.floor?.let { floor ->
                    val origin = source.floor?.originLifetimeId ?: floor.originLifetimeId
                    addPair(ObligationSubject.Floor(action.kind, source.id, origin), ObligationComponent.FLOOR,
                        RequiredLowerBound.Floor(action.kind, source.id, floor),
                        allowed = AllowedSlotDisposition.DURABLY_OWNED_ONLY)
                }
                required.auth?.let { auth ->
                    addPair(ObligationSubject.Auth(auth.ownerUid, auth.binding, auth.originLifetimeId, auth.authGeneration),
                        ObligationComponent.AUTH, RequiredLowerBound.Auth(auth))
                }
            }
            is RestoredHold -> {
                val hold = source.copy(axes = Collections.unmodifiableSet(source.axes.toSet()))
                addPair(ObligationSubject.Hold(hold.id, hold.originLifetimeId, hold.binding,
                    hold.provenance, hold.axes), ObligationComponent.SOURCE, RequiredLowerBound.Hold(hold))
                hold.floor?.let { floor ->
                    addPair(ObligationSubject.Floor(action.kind, hold.id, floor.originLifetimeId),
                        ObligationComponent.FLOOR, RequiredLowerBound.Floor(action.kind, hold.id, floor),
                        allowed = AllowedSlotDisposition.DURABLY_OWNED_ONLY)
                }
            }
            is RecoveryIntentV1 -> addPair(ObligationSubject.Intent(source.id, source.sessionId, source.ownerUid,
                source.axis, source.targetEpoch), ObligationComponent.SOURCE, RequiredLowerBound.Intent(source))
        }
        if (slots.subList(firstSlot, slots.size).any { !keys.add(it.key) })
            return unavailable(RequiredObligationsUnavailable.DUPLICATE_REQUIRED_KEY,
                actionRoot, index, FixedInputFacet.WHOLE)
    }
    return RequirementDerivation.Available(ExactCommandBinding(input.exactCommand, body, FixedCommandKind.Mutations),
        Collections.unmodifiableList(slots.toList()))
}
