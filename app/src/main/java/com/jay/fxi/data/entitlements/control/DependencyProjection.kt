package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope

internal sealed interface DependencyAtom {
    data class ControlRow(val kind: ControlKind, val id: String) : DependencyAtom
    data class AppliedRow(val commandId: String, val lifetimeId: String) : DependencyAtom
    data class SealWitness(val sealId: String, val operationId: String) : DependencyAtom
    data class Journal(val key: JournalTargetV1) : DependencyAtom
}
internal data class DependencyProjectionInput(
    val commandId: String, val lifetimeId: String, val view: RefView,
    val adoptedTargets: List<ControlCommandTarget?>?,
    val releaseDescriptor: ReleasePendingDescriptor?,
    val terminationDescriptor: TerminationPendingDescriptor?
)
internal sealed interface DependencyProjection {
    data class Known(val dependencies: Set<DependencyAtom>) : DependencyProjection
    data class Unknown(val reason: String, val knownDependencies: Set<DependencyAtom>) : DependencyProjection
}

/** Project one captured ref. Unknown always retains every dependency that could be read. */
internal fun projectDependency(input: DependencyProjectionInput): DependencyProjection {
    if (input.view.state == ControlCommandLifecycle.RELEASED ||
        input.view.state == ControlCommandLifecycle.TERMINATED) return DependencyProjection.Known(emptySet())

    val dependencies = linkedSetOf<DependencyAtom>()
    var unknown: String? = null
    fun mark(reason: String) { if (unknown == null) unknown = reason }
    fun row(kind: ControlKind, id: String) {
        if (id.isEmpty()) mark("EmptyTargetId") else dependencies += DependencyAtom.ControlRow(kind, id)
    }
    fun seal(seal: SealV1) {
        row(ControlKind.SEAL, seal.id)
        dependencies += DependencyAtom.Journal(JournalTargetV1(seal.key.ownerUid, seal.key.axis, seal.key.epoch))
        seal.settlement?.let { witness ->
            dependencies += DependencyAtom.SealWitness(seal.id, witness.operationId)
            dependencies += DependencyAtom.Journal(witness.journal)
        }
    }
    fun fixedSeal(node: ControlNode) {
        val parsed = ControlSchema.read(ControlKind.SEAL, node) as? SealV1
        if (parsed == null) mark("UnreadableSeal") else seal(parsed)
    }
    val body = input.view.body
    when (body) {
        is ControlCommandBody.Mutations -> {
            val actions = body.actions
            for (action in actions) when (action) {
                is ControlMutation.Add -> row(action.kind, action.proposedId)
                is ControlMutation.Edit -> {
                    val before = ControlSchema.read(action.kind, action.before)
                    if (before == null) mark("UnreadableMutationBefore") else row(action.kind, before.id)
                    val after = (action.changed as? ControlWriteResult.Written)?.node
                    if (after != null) {
                        val parsed = ControlSchema.read(action.kind, after)
                        if (parsed == null) mark("UnreadableMutationAfter") else row(action.kind, parsed.id)
                    }
                }
            }
            val adopted = input.adoptedTargets
            if (adopted == null || adopted.size != actions.size) mark("AdoptionUnavailable")
            if (adopted != null) {
                if (adopted.size == actions.size && adopted.any { it == null } && adopted.any { it != null })
                    mark("PartialAdoption")
                adopted.forEachIndexed { index, target ->
                    if (target != null) {
                        val kind = actions.getOrNull(index)?.kind
                        if (kind == null) mark("ExcessAdoption") else row(kind, target.id)
                    }
                }
            }
        }
        is ControlCommandBody.Lifecycle -> {
            val descriptor = body.input
            for (fixed in descriptor.targets + descriptor.requiredUnchanged) row(fixed.target.kind, fixed.target.id)
            descriptor.demandAuth?.decision?.effects?.forEach { effect ->
                val parsed = ControlSchema.read(effect.kind, effect.node)
                if (parsed == null) {
                    when (val id = effect.node.text("id")) {
                        is FieldRead.Present -> row(effect.kind, id.value)
                        else -> Unit
                    }
                    mark("UnreadableLifecycleEffect")
                } else row(effect.kind, parsed.id)
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
            if (fixed.sealTargets.size != fixed.targets.size) mark("UnreadableRotationTarget")
            fixed.sealTargets.forEach {
                seal(it.seal)
                dependencies += DependencyAtom.SealWitness(it.seal.id, fixed.operationId)
            }
            row(ControlKind.DEMAND, fixed.demandId)
            for (axis in fixed.axes) dependencies += DependencyAtom.Journal(
                JournalTargetV1(fixed.before.ownerUid, axis, fixed.before.epoch(axis)))
            fixed.invalidInput()?.let { mark("InvalidRotationInput:$it") }
        }
        is ControlCommandBody.SettleRetiredNamespace -> {
            val fixed = body.input
            fixedSeal(fixed.target)
            val target = ControlSchema.read(ControlKind.SEAL, fixed.target) as? SealV1
            if (target != null) {
                dependencies += DependencyAtom.SealWitness(target.id, fixed.operationId)
                dependencies += DependencyAtom.Journal(JournalTargetV1(target.key.ownerUid, target.key.axis, target.key.epoch))
            }
            fixed.demandId?.let { row(ControlKind.DEMAND, it) }
            RetiredNamespaceSettlementTransition(ControlPayloadCodec()).invalidInput(fixed)
                ?.let { mark("InvalidRetiredNamespaceInput:$it") }
        }
        is ControlCommandBody.RotateAndSettleCurrentNull -> {
            val fixed = body.input
            if (fixed.nulls.size != fixed.nullTargets.size || fixed.accompanying.size != fixed.companions.size)
                mark("UnreadableSettlementTarget")
            fixed.targets.forEach {
                seal(it.seal)
                dependencies += DependencyAtom.SealWitness(it.seal.id, fixed.operationId)
            }
            row(ControlKind.DEMAND, fixed.demandId)
            for (axis in fixed.axes) dependencies += DependencyAtom.Journal(JournalTargetV1(fixed.before.ownerUid, axis, null))
            CurrentNullSettlementTransition(ControlPayloadCodec()).invalidInput(fixed)
                ?.let { mark("InvalidCurrentNullInput:$it") }
        }
        is ControlCommandBody.SettleRetiredNull -> {
            val fixed = body.input
            if (fixed.ordered.size != fixed.targets.size) mark("UnreadableSettlementTarget")
            fixed.ordered.forEach {
                seal(it.seal)
                dependencies += DependencyAtom.SealWitness(it.seal.id, fixed.operationId)
                dependencies += DependencyAtom.Journal(JournalTargetV1(it.seal.key.ownerUid, it.seal.key.axis, null))
            }
            RetiredNullSettlementTransition(ControlPayloadCodec()).invalidInput(fixed)
                ?.let { mark("InvalidRetiredNullInput:$it") }
        }
        null -> mark("OpenViewWithoutBody")
    }

    // Descriptor evidence belongs to this ref even when absent from the body target list.
    when (val release = input.releaseDescriptor) {
        is ReleasePendingDescriptor.ExactMutations -> {
            val evidence = release.row
            dependencies += DependencyAtom.AppliedRow(evidence.commandId, evidence.ownerTrackingLifetimeId)
            evidence.targets.forEach { row(it.kind, it.id) }
            if (evidence.commandId != input.commandId || evidence.ownerTrackingLifetimeId != input.lifetimeId)
                mark("ReleaseIdentityMismatch")
            val mutationBody = body as? ControlCommandBody.Mutations
            if (mutationBody == null || evidence.targets.size != mutationBody.actions.size) mark("ReleaseShapeMismatch")
            else evidence.targets.forEach { target ->
                val action = mutationBody.actions.getOrNull(target.index)
                val adopted = input.adoptedTargets?.getOrNull(target.index)
                if (action == null || action.kind != target.kind || (adopted != null && adopted.id != target.id))
                    mark("ReleaseTargetMismatch")
            }
        }
        ReleasePendingDescriptor.ConfirmedWithoutApplied, null -> Unit
    }
    if (input.view.state == ControlCommandLifecycle.RELEASE_PENDING && input.releaseDescriptor == null)
        mark("ReleaseDescriptorMissing")
    if (input.view.state != ControlCommandLifecycle.RELEASE_PENDING && input.releaseDescriptor != null)
        mark("UnexpectedReleaseDescriptor")
    if (input.view.state == ControlCommandLifecycle.TERMINATION_PENDING && input.terminationDescriptor == null)
        mark("TerminationDescriptorMissing")
    if (input.view.state != ControlCommandLifecycle.TERMINATION_PENDING && input.terminationDescriptor != null)
        mark("UnexpectedTerminationDescriptor")
    // EvidenceAbsent deliberately contributes no invented Applied row.
    return unknown?.let { DependencyProjection.Unknown(it, dependencies) }
        ?: DependencyProjection.Known(dependencies)
}
