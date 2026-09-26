package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope

internal sealed interface DependencyAtom {
    data class ControlRow(val kind: ControlKind, val id: String) : DependencyAtom
    data class AppliedRow(val commandId: String, val lifetimeId: String) : DependencyAtom
    data class SealWitness(val sealId: String, val operationId: String) : DependencyAtom
    data class Journal(val key: JournalTargetV1) : DependencyAtom
}
internal sealed interface GapValue<out T> {
    data class Exact<T>(val value: T) : GapValue<T>
    data object Wildcard : GapValue<Nothing>
}

internal sealed interface DependencyAtomFootprint {
    data class ControlRow(val kinds: Set<ControlKind>, val id: GapValue<String>) : DependencyAtomFootprint {
        init { require(kinds.isNotEmpty()) }
    }
    data class AppliedRow(val commandId: GapValue<String>, val lifetimeId: GapValue<String>) : DependencyAtomFootprint
    data class SealWitness(val sealId: GapValue<String>, val operationId: GapValue<String>) : DependencyAtomFootprint
    data class Journal(val key: GapValue<JournalTargetV1>) : DependencyAtomFootprint
}

internal enum class DependencyGapCause {
    EmptyTargetId, UnreadableSeal, UnreadableMutationBefore, UnreadableMutationAfter,
    AdoptionUnavailable, PartialAdoption, ExcessAdoption, UnreadableLifecycleEffect,
    UnreadableRotationTarget, InvalidRotationInput, InvalidRetiredNamespaceInput,
    UnreadableSettlementTarget, InvalidCurrentNullInput, InvalidRetiredNullInput,
    OpenViewWithoutBody, ReleaseIdentityMismatch, ReleaseShapeMismatch,
    ReleaseTargetMismatch, ReleaseDescriptorMissing, UnexpectedReleaseDescriptor,
    TerminationDescriptorMissing, UnexpectedTerminationDescriptor,
    TerminationDescriptorMismatch, Unclassified
}

internal sealed interface DependencyGapSource {
    data class Mutation(val index: Int, val slot: MutationSlot) : DependencyGapSource
    data class Adoption(val index: Int?) : DependencyGapSource
    data class LifecycleTarget(val index: Int, val requiredUnchanged: Boolean) : DependencyGapSource
    data class LifecycleEffect(val index: Int) : DependencyGapSource
    data class FixedSeal(val body: FixedBodyKind, val index: Int?) : DependencyGapSource
    data class FixedInput(val body: FixedBodyKind) : DependencyGapSource
    data class ReleaseDescriptor(val targetIndex: Int?) : DependencyGapSource
    data object TerminationDescriptor : DependencyGapSource
    data object Body : DependencyGapSource
    data object Unclassified : DependencyGapSource
}
internal enum class MutationSlot { Proposed, Before, After }
internal enum class FixedBodyKind { Rotation, RetiredNamespace, CurrentNull, RetiredNull }

internal data class DependencyGap(
    val cause: DependencyGapCause,
    val source: DependencyGapSource,
    val possibleAtoms: Set<DependencyAtomFootprint>,
    val detail: String? = null
) {
    init { require(possibleAtoms.isNotEmpty()) }
}

private val broadFootprint: Set<DependencyAtomFootprint> = setOf(
    DependencyAtomFootprint.AppliedRow(GapValue.Wildcard, GapValue.Wildcard),
    DependencyAtomFootprint.ControlRow(ControlKind.entries.toSet(), GapValue.Wildcard),
    DependencyAtomFootprint.SealWitness(GapValue.Wildcard, GapValue.Wildcard)
)
private val sealFootprint: Set<DependencyAtomFootprint> = setOf(
    DependencyAtomFootprint.ControlRow(setOf(ControlKind.SEAL), GapValue.Wildcard),
    DependencyAtomFootprint.SealWitness(GapValue.Wildcard, GapValue.Wildcard)
)
private fun rowFootprint(kind: ControlKind): Set<DependencyAtomFootprint> =
    setOf(DependencyAtomFootprint.ControlRow(setOf(kind), GapValue.Wildcard))

internal sealed interface DependencyIntersection {
    data object Clear : DependencyIntersection
    data class Present(val atom: DependencyAtom) : DependencyIntersection
    data class Unknown(val gap: DependencyGap?) : DependencyIntersection
}

private fun <T> GapValue<T>.matches(value: T): Boolean = when (this) {
    is GapValue.Exact<*> -> this.value == value
    GapValue.Wildcard -> true
}

internal fun possibleIntersection(gap: DependencyGap, protectedAtoms: Set<DependencyAtom>): Boolean =
    gap.possibleAtoms.any { footprint ->
        protectedAtoms.any { atom ->
            when {
                footprint is DependencyAtomFootprint.ControlRow && atom is DependencyAtom.ControlRow ->
                    atom.kind in footprint.kinds && footprint.id.matches(atom.id)
                footprint is DependencyAtomFootprint.AppliedRow && atom is DependencyAtom.AppliedRow ->
                    footprint.commandId.matches(atom.commandId) && footprint.lifetimeId.matches(atom.lifetimeId)
                footprint is DependencyAtomFootprint.SealWitness && atom is DependencyAtom.SealWitness ->
                    footprint.sealId.matches(atom.sealId) && footprint.operationId.matches(atom.operationId)
                footprint is DependencyAtomFootprint.Journal && atom is DependencyAtom.Journal ->
                    footprint.key.matches(atom.key)
                else -> false
            }
        }
    }

internal fun classifyDependency(
    projection: DependencyProjection,
    protectedAtoms: Set<DependencyAtom>
): DependencyIntersection {
    val known = when (projection) {
        is DependencyProjection.Known -> projection.dependencies
        is DependencyProjection.Unknown -> projection.knownDependencies
    }
    known.firstOrNull { it in protectedAtoms }?.let { return DependencyIntersection.Present(it) }
    if (projection is DependencyProjection.Known) return DependencyIntersection.Clear
    projection as DependencyProjection.Unknown
    if (projection.gaps.isEmpty() || projection.gaps.any {
            it.possibleAtoms.isEmpty() || it.cause == DependencyGapCause.Unclassified ||
                it.source == DependencyGapSource.Unclassified
        }) return DependencyIntersection.Unknown(null)
    return projection.gaps.firstOrNull { possibleIntersection(it, protectedAtoms) }
        ?.let { DependencyIntersection.Unknown(it) } ?: DependencyIntersection.Clear
}
internal data class DependencyProjectionInput(
    val commandId: String, val lifetimeId: String, val view: RefView,
    val adoptedTargets: List<ControlCommandTarget?>?,
    val releaseDescriptor: ReleasePendingDescriptor?,
    val terminationDescriptor: TerminationPendingDescriptor?
)
internal sealed interface DependencyProjection {
    data class Known(val dependencies: Set<DependencyAtom>) : DependencyProjection
    data class Unknown(
        val reason: String,
        val knownDependencies: Set<DependencyAtom>,
        val gaps: Set<DependencyGap>
    ) : DependencyProjection {
        init { require(gaps.isNotEmpty()) }
    }
}

/** Project one captured ref. Unknown always retains every dependency that could be read. */
internal fun projectDependency(input: DependencyProjectionInput): DependencyProjection {
    if (input.view.state == ControlCommandLifecycle.RELEASED ||
        input.view.state == ControlCommandLifecycle.TERMINATED) return DependencyProjection.Known(emptySet())

    val dependencies = linkedSetOf<DependencyAtom>()
    val gaps = linkedSetOf<DependencyGap>()
    var unknown: String? = null
    fun mark(reason: String, cause: DependencyGapCause, source: DependencyGapSource,
             possibleAtoms: Set<DependencyAtomFootprint>, detail: String? = null) {
        if (unknown == null) unknown = reason
        gaps += if (possibleAtoms.isEmpty()) {
            DependencyGap(DependencyGapCause.Unclassified, DependencyGapSource.Unclassified, broadFootprint, detail)
        } else DependencyGap(cause, source, possibleAtoms, detail)
    }
    fun row(kind: ControlKind, id: String, source: DependencyGapSource) {
        if (id.isEmpty()) mark("EmptyTargetId", DependencyGapCause.EmptyTargetId, source,
            if (kind == ControlKind.SEAL && source is DependencyGapSource.FixedSeal) sealFootprint else rowFootprint(kind))
        else dependencies += DependencyAtom.ControlRow(kind, id)
    }
    fun seal(seal: SealV1, source: DependencyGapSource) {
        row(ControlKind.SEAL, seal.id, source)
        dependencies += DependencyAtom.Journal(JournalTargetV1(seal.key.ownerUid, seal.key.axis, seal.key.epoch))
        seal.settlement?.let { witness ->
            dependencies += DependencyAtom.SealWitness(seal.id, witness.operationId)
            dependencies += DependencyAtom.Journal(witness.journal)
        }
    }
    fun fixedSeal(node: ControlNode, source: DependencyGapSource.FixedSeal) {
        val parsed = ControlSchema.read(ControlKind.SEAL, node) as? SealV1
        if (parsed == null) mark("UnreadableSeal", DependencyGapCause.UnreadableSeal, source, sealFootprint)
        else seal(parsed, source)
    }
    val body = input.view.body
    when (body) {
        is ControlCommandBody.Mutations -> {
            val actions = body.actions
            actions.forEachIndexed { index, action -> when (action) {
                is ControlMutation.Add -> row(action.kind, action.proposedId,
                    DependencyGapSource.Mutation(index, MutationSlot.Proposed))
                is ControlMutation.Edit -> {
                    val before = ControlSchema.read(action.kind, action.before)
                    if (before == null) mark("UnreadableMutationBefore", DependencyGapCause.UnreadableMutationBefore,
                        DependencyGapSource.Mutation(index, MutationSlot.Before), rowFootprint(action.kind))
                    else row(action.kind, before.id, DependencyGapSource.Mutation(index, MutationSlot.Before))
                    val after = (action.changed as? ControlWriteResult.Written)?.node
                    if (after != null) {
                        val parsed = ControlSchema.read(action.kind, after)
                        if (parsed == null) mark("UnreadableMutationAfter", DependencyGapCause.UnreadableMutationAfter,
                            DependencyGapSource.Mutation(index, MutationSlot.After), rowFootprint(action.kind))
                        else row(action.kind, parsed.id, DependencyGapSource.Mutation(index, MutationSlot.After))
                    }
                }
            } }
            val adopted = input.adoptedTargets
            if (adopted == null || adopted.size != actions.size) mark("AdoptionUnavailable",
                DependencyGapCause.AdoptionUnavailable, DependencyGapSource.Adoption(null),
                actions.map { DependencyAtomFootprint.ControlRow(setOf(it.kind), GapValue.Wildcard) }.toSet())
            if (adopted != null) {
                if (adopted.size == actions.size && adopted.any { it == null } && adopted.any { it != null }) {
                    adopted.forEachIndexed { index, target ->
                        if (target == null) mark("PartialAdoption", DependencyGapCause.PartialAdoption,
                            DependencyGapSource.Adoption(index), rowFootprint(actions[index].kind))
                    }
                }
                adopted.forEachIndexed { index, target ->
                    if (target != null) {
                        val kind = actions.getOrNull(index)?.kind
                        if (kind == null) mark("ExcessAdoption", DependencyGapCause.ExcessAdoption,
                            DependencyGapSource.Adoption(index), broadFootprint)
                        else row(kind, target.id, DependencyGapSource.Adoption(index))
                    }
                }
            }
        }
        is ControlCommandBody.Lifecycle -> {
            val descriptor = body.input
            descriptor.targets.forEachIndexed { index, fixed ->
                row(fixed.target.kind, fixed.target.id, DependencyGapSource.LifecycleTarget(index, false))
            }
            descriptor.requiredUnchanged.forEachIndexed { index, fixed ->
                row(fixed.target.kind, fixed.target.id, DependencyGapSource.LifecycleTarget(index, true))
            }
            descriptor.demandAuth?.decision?.effects?.forEachIndexed { index, effect ->
                val parsed = ControlSchema.read(effect.kind, effect.node)
                if (parsed == null) {
                    when (val id = effect.node.text("id")) {
                        is FieldRead.Present -> row(effect.kind, id.value, DependencyGapSource.LifecycleEffect(index))
                        else -> Unit
                    }
                    mark("UnreadableLifecycleEffect", DependencyGapCause.UnreadableLifecycleEffect,
                        DependencyGapSource.LifecycleEffect(index), rowFootprint(effect.kind))
                } else row(effect.kind, parsed.id, DependencyGapSource.LifecycleEffect(index))
            }
            descriptor.namespace?.journal?.forEach { pending ->
                for (axis in pending.scopes) dependencies += DependencyAtom.Journal(
                    JournalTargetV1(pending.ownerUid, axis, when (axis) {
                        PurgeScope.USER -> pending.userAccessEpoch
                        PurgeScope.CAPABILITY -> pending.krxCapabilityEpoch
                    }))
            }
        }
        is ControlCommandBody.RotateAndSettle -> {
            val fixed = body.input
            if (fixed.sealTargets.size != fixed.targets.size) mark("UnreadableRotationTarget",
                DependencyGapCause.UnreadableRotationTarget, DependencyGapSource.FixedInput(FixedBodyKind.Rotation), sealFootprint)
            fixed.sealTargets.forEachIndexed { index, target ->
                seal(target.seal, DependencyGapSource.FixedSeal(FixedBodyKind.Rotation, index))
                dependencies += DependencyAtom.SealWitness(target.seal.id, fixed.operationId)
            }
            row(ControlKind.DEMAND, fixed.demandId, DependencyGapSource.FixedInput(FixedBodyKind.Rotation))
            for (axis in fixed.axes) dependencies += DependencyAtom.Journal(
                JournalTargetV1(fixed.before.ownerUid, axis, fixed.before.epoch(axis)))
            fixed.invalidInput()?.let { mark("InvalidRotationInput:$it", DependencyGapCause.InvalidRotationInput,
                DependencyGapSource.FixedInput(FixedBodyKind.Rotation), sealFootprint, it) }
        }
        is ControlCommandBody.SettleRetiredNamespace -> {
            val fixed = body.input
            fixedSeal(fixed.target, DependencyGapSource.FixedSeal(FixedBodyKind.RetiredNamespace, 0))
            val target = ControlSchema.read(ControlKind.SEAL, fixed.target) as? SealV1
            if (target != null) {
                dependencies += DependencyAtom.SealWitness(target.id, fixed.operationId)
                dependencies += DependencyAtom.Journal(JournalTargetV1(target.key.ownerUid, target.key.axis, target.key.epoch))
            }
            fixed.demandId?.let { row(ControlKind.DEMAND, it, DependencyGapSource.FixedInput(FixedBodyKind.RetiredNamespace)) }
            RetiredNamespaceSettlementTransition(ControlPayloadCodec()).invalidInput(fixed)
                ?.let { mark("InvalidRetiredNamespaceInput:$it", DependencyGapCause.InvalidRetiredNamespaceInput,
                    DependencyGapSource.FixedInput(FixedBodyKind.RetiredNamespace), sealFootprint, it) }
        }
        is ControlCommandBody.RotateAndSettleCurrentNull -> {
            val fixed = body.input
            if (fixed.nulls.size != fixed.nullTargets.size || fixed.accompanying.size != fixed.companions.size)
                mark("UnreadableSettlementTarget", DependencyGapCause.UnreadableSettlementTarget,
                    DependencyGapSource.FixedInput(FixedBodyKind.CurrentNull), sealFootprint)
            fixed.targets.forEachIndexed { index, target ->
                seal(target.seal, DependencyGapSource.FixedSeal(FixedBodyKind.CurrentNull, index))
                dependencies += DependencyAtom.SealWitness(target.seal.id, fixed.operationId)
            }
            row(ControlKind.DEMAND, fixed.demandId, DependencyGapSource.FixedInput(FixedBodyKind.CurrentNull))
            for (axis in fixed.axes) dependencies += DependencyAtom.Journal(JournalTargetV1(fixed.before.ownerUid, axis, null))
            CurrentNullSettlementTransition(ControlPayloadCodec()).invalidInput(fixed)
                ?.let { mark("InvalidCurrentNullInput:$it", DependencyGapCause.InvalidCurrentNullInput,
                    DependencyGapSource.FixedInput(FixedBodyKind.CurrentNull), sealFootprint, it) }
        }
        is ControlCommandBody.SettleRetiredNull -> {
            val fixed = body.input
            if (fixed.ordered.size != fixed.targets.size) mark("UnreadableSettlementTarget",
                DependencyGapCause.UnreadableSettlementTarget,
                DependencyGapSource.FixedInput(FixedBodyKind.RetiredNull), sealFootprint)
            fixed.ordered.forEachIndexed { index, target ->
                seal(target.seal, DependencyGapSource.FixedSeal(FixedBodyKind.RetiredNull, index))
                dependencies += DependencyAtom.SealWitness(target.seal.id, fixed.operationId)
                dependencies += DependencyAtom.Journal(JournalTargetV1(target.seal.key.ownerUid, target.seal.key.axis, null))
            }
            RetiredNullSettlementTransition(ControlPayloadCodec()).invalidInput(fixed)
                ?.let { mark("InvalidRetiredNullInput:$it", DependencyGapCause.InvalidRetiredNullInput,
                    DependencyGapSource.FixedInput(FixedBodyKind.RetiredNull), sealFootprint, it) }
        }
        null -> mark("OpenViewWithoutBody", DependencyGapCause.OpenViewWithoutBody,
            DependencyGapSource.Body, broadFootprint)
    }

    // Descriptor evidence belongs to this ref even when absent from the body target list.
    when (val release = input.releaseDescriptor) {
        is ReleasePendingDescriptor.ExactMutations -> {
            val evidence = release.row
            dependencies += DependencyAtom.AppliedRow(evidence.commandId, evidence.ownerTrackingLifetimeId)
            evidence.targets.forEach { row(it.kind, it.id, DependencyGapSource.ReleaseDescriptor(it.index)) }
            if (evidence.commandId != input.commandId || evidence.ownerTrackingLifetimeId != input.lifetimeId)
                mark("ReleaseIdentityMismatch", DependencyGapCause.ReleaseIdentityMismatch,
                    DependencyGapSource.ReleaseDescriptor(null), broadFootprint)
            val mutationBody = body as? ControlCommandBody.Mutations
            if (mutationBody == null || evidence.targets.size != mutationBody.actions.size)
                mark("ReleaseShapeMismatch", DependencyGapCause.ReleaseShapeMismatch,
                    DependencyGapSource.ReleaseDescriptor(null), broadFootprint)
            else evidence.targets.forEach { target ->
                val action = mutationBody.actions.getOrNull(target.index)
                val adopted = input.adoptedTargets?.getOrNull(target.index)
                if (action == null || action.kind != target.kind || (adopted != null && adopted.id != target.id))
                    mark("ReleaseTargetMismatch", DependencyGapCause.ReleaseTargetMismatch,
                        DependencyGapSource.ReleaseDescriptor(target.index), broadFootprint)
            }
        }
        ReleasePendingDescriptor.ConfirmedWithoutApplied, null -> Unit
    }
    if (input.view.state == ControlCommandLifecycle.RELEASE_PENDING && input.releaseDescriptor == null)
        mark("ReleaseDescriptorMissing", DependencyGapCause.ReleaseDescriptorMissing,
            DependencyGapSource.ReleaseDescriptor(null), broadFootprint)
    if (input.view.state != ControlCommandLifecycle.RELEASE_PENDING && input.releaseDescriptor != null)
        mark("UnexpectedReleaseDescriptor", DependencyGapCause.UnexpectedReleaseDescriptor,
            DependencyGapSource.ReleaseDescriptor(null), broadFootprint)
    if (input.terminationDescriptor is TerminationPendingDescriptor.ExactEvidenceAndSeals) {
        val descriptor = input.terminationDescriptor
        val expected = descriptor.expectedRotation
        dependencies += DependencyAtom.AppliedRow(expected.commandId, expected.ownerTrackingLifetimeId)
        descriptor.orderedSealIds.forEach { id ->
            row(ControlKind.SEAL, id, DependencyGapSource.TerminationDescriptor)
            dependencies += DependencyAtom.SealWitness(id, descriptor.operationId)
        }
        expected.sealIds.forEach { id ->
            row(ControlKind.SEAL, id, DependencyGapSource.TerminationDescriptor)
            dependencies += DependencyAtom.SealWitness(id, expected.commandId)
        }
        val rotation = body as? ControlCommandBody.RotateAndSettle
        val binding = descriptor.closureBinding
        if (descriptor.mode != CompletionMode.Consumed || descriptor.entry != TerminationEntry.ConsumedRotation ||
            expected.commandId != input.commandId || expected.ownerTrackingLifetimeId != input.lifetimeId ||
            descriptor.operationId != input.commandId || descriptor.orderedSealIds != expected.sealIds ||
            binding.command.id != input.commandId || binding.ownerTrackingLifetimeId.value != input.lifetimeId ||
            binding.relatedScope != input.commandId || rotation == null ||
            rotation.input.operationId != descriptor.operationId ||
            rotation.input.seals.map { it.id } != descriptor.orderedSealIds ||
            rotation.input.demandId != expected.demandId
        ) mark("TerminationDescriptorMismatch", DependencyGapCause.TerminationDescriptorMismatch,
            DependencyGapSource.TerminationDescriptor, broadFootprint)
    }
    if (input.view.state == ControlCommandLifecycle.TERMINATION_PENDING && input.terminationDescriptor == null)
        mark("TerminationDescriptorMissing", DependencyGapCause.TerminationDescriptorMissing,
            DependencyGapSource.TerminationDescriptor, broadFootprint)
    if (input.view.state != ControlCommandLifecycle.TERMINATION_PENDING && input.terminationDescriptor != null)
        mark("UnexpectedTerminationDescriptor", DependencyGapCause.UnexpectedTerminationDescriptor,
            DependencyGapSource.TerminationDescriptor, broadFootprint)
    // EvidenceAbsent deliberately contributes no invented Applied row.
    return unknown?.let { DependencyProjection.Unknown(it, dependencies, gaps) }
        ?: DependencyProjection.Known(dependencies)
}
