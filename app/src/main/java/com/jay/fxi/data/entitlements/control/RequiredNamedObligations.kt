package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import java.util.Collections

private fun namedLocation(root: FixedInputRoot, index: Int? = null, facet: FixedInputFacet = FixedInputFacet.WHOLE) =
    FixedInputLocation(root, index, facet)

private fun unavailable(reason: RequiredObligationsUnavailable, location: FixedInputLocation) =
    RequirementDerivation.Unavailable(reason, location)

private fun source(root: FixedInputRoot, index: Int, node: ControlNode) =
    FixedSourceEvidence(namedLocation(root, index, FixedInputFacet.SOURCE), FixedSourceFact.Node(ControlKind.SEAL, node))

private fun fact(root: FixedInputRoot, facet: FixedInputFacet, value: FixedSourceFact) =
    FixedSourceEvidence(namedLocation(root, facet = facet), value)

private fun originalSeal(node: ControlNode): SealV1? =
    (ControlObligations.read(ControlKind.SEAL, node) as? ControlEntryRead.Interpreted)?.value as? SealV1

/** Slot order and provenance are fixed by the command input, independently of storage observations. */
private class NamedSlots(
    private val binding: ExactCommandBinding,
    private val role: ObligationRole
) {
    private val slots = mutableListOf<RequiredSlot>()
    private val keys = mutableSetOf<RequiredObligationKey>()
    private var duplicate: FixedInputLocation? = null

    private fun pair(
        subject: ObligationSubject,
        component: ObligationComponent,
        landing: RequiredLowerBound?,
        nonLanding: RequiredLowerBound?,
        fixed: List<FixedSourceEvidence>,
        location: FixedInputLocation
    ) {
        val sources = Collections.unmodifiableList(fixed.toList())
        for ((branch, bound) in listOf(LandingBranch.L to landing, LandingBranch.N to nonLanding)) {
            val key = RequiredObligationKey(role, subject, component, branch)
            if (!keys.add(key)) {
                if (duplicate == null) duplicate = location
                continue
            }
            val requirement = if (bound == null) SlotRequirement.NotRequiredByContract(sources)
                else SlotRequirement.Required(bound, AllowedSlotDisposition.EITHER, sources)
            slots += RequiredSlot(key, requirement)
        }
    }

    fun sealAndJournal(seal: SealV1, witness: SettlementEvidence, journal: JournalTargetV1,
                       fixed: List<FixedSourceEvidence>, location: FixedInputLocation) {
        pair(ObligationSubject.Seal(seal.id, seal.kind, seal.key), ObligationComponent.SEAL,
            RequiredLowerBound.Seal(seal, witness), RequiredLowerBound.Seal(seal, null), fixed, location)
        pair(ObligationSubject.Journal(seal.id, journal), ObligationComponent.JOURNAL,
            RequiredLowerBound.Journal(journal), RequiredLowerBound.Journal(journal), fixed, location)
    }

    fun retirement(scope: RetirementScope, fixed: List<FixedSourceEvidence>, location: FixedInputLocation) {
        val bound = RequiredLowerBound.NamespaceRetirement(scope)
        pair(scope, ObligationComponent.NAMESPACE_RETIREMENT, bound, bound, fixed, location)
    }

    fun request(id: String?, demand: SettlementDemand?, fixed: List<FixedSourceEvidence>,
                location: FixedInputLocation) {
        val bound = if (id == null || demand == null) null else RequiredLowerBound.Request(
            null, id, demand.ownerUid, demand.binding, demand.intent, demand.raisedAt)
        pair(ObligationSubject.NamedRequest(id), ObligationComponent.REQUEST, bound, bound, fixed, location)
    }

    fun result(): RequirementDerivation = duplicate?.let {
        unavailable(RequiredObligationsUnavailable.DUPLICATE_REQUIRED_KEY, it)
    } ?: RequirementDerivation.Available(binding, Collections.unmodifiableList(slots.toList()))
}

internal fun deriveRotationObligations(input: RequirementInput.Rotation): RequirementDerivation {
    val body = input.exactCommand.captureStateAndBody().body
        ?: return unavailable(RequiredObligationsUnavailable.BODY_UNAVAILABLE, namedLocation(FixedInputRoot.COMMAND))
    if (body !== input.body || body !is ControlCommandBody.RotateAndSettle)
        return unavailable(RequiredObligationsUnavailable.BODY_MISMATCH, namedLocation(FixedInputRoot.COMMAND))

    val fixed = body.input
    for ((index, node) in fixed.targets.withIndex()) {
        if (originalSeal(node) == null) return unavailable(RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE,
            namedLocation(FixedInputRoot.ROTATION_TARGET, index, FixedInputFacet.SOURCE))
    }
    if (fixed.invalidInput() != null) return unavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT,
        namedLocation(FixedInputRoot.ROTATION_INPUT))

    val group = listOf(
        fact(FixedInputRoot.ROTATION_INPUT, FixedInputFacet.WHOLE, FixedSourceFact.RotationInput(fixed)),
        fact(FixedInputRoot.ROTATION_INPUT, FixedInputFacet.BEFORE, FixedSourceFact.Fence(fixed.before)),
        fact(FixedInputRoot.ROTATION_INPUT, FixedInputFacet.AFTER, FixedSourceFact.Fence(fixed.after)),
        fact(FixedInputRoot.ROTATION_INPUT, FixedInputFacet.DEMAND, FixedSourceFact.Demand(fixed.demand)))
    val builder = NamedSlots(ExactCommandBinding(input.exactCommand, body, FixedCommandKind.Rotation), ObligationRole.Rotation)
    val originals = fixed.sealTargets.mapIndexed { index, target -> source(FixedInputRoot.ROTATION_TARGET, index, target.original) }
    for ((index, target) in fixed.sealTargets.withIndex()) {
        val seal = target.seal
        val journal = JournalTargetV1(seal.key.ownerUid, seal.key.axis, seal.key.epoch)
        val evidence = group + originals[index]
        builder.sealAndJournal(seal, fixed.witness(seal), journal, evidence, originals[index].location)
        builder.retirement(RetirementScope(seal.id, fixed.before, fixed.after, journal), evidence, originals[index].location)
    }
    builder.request(fixed.demandId, fixed.demand, group + originals,
        namedLocation(FixedInputRoot.ROTATION_INPUT, facet = FixedInputFacet.DEMAND))
    return builder.result()
}

internal fun deriveSettlementObligations(input: RequirementInput.Settlement): RequirementDerivation {
    val body = input.exactCommand.captureStateAndBody().body
        ?: return unavailable(RequiredObligationsUnavailable.BODY_UNAVAILABLE, namedLocation(FixedInputRoot.COMMAND))
    if (body !== input.body)
        return unavailable(RequiredObligationsUnavailable.BODY_MISMATCH, namedLocation(FixedInputRoot.COMMAND))

    val raw = when (body) {
        is ControlCommandBody.SettleRetiredNamespace -> listOf(body.input.target)
        is ControlCommandBody.RotateAndSettleCurrentNull -> body.input.nullTargets + body.input.companions
        is ControlCommandBody.SettleRetiredNull -> body.input.targets
    }
    for ((index, node) in raw.withIndex()) {
        if (originalSeal(node) == null) return unavailable(RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE,
            namedLocation(FixedInputRoot.SETTLEMENT_TARGET, index, FixedInputFacet.SOURCE))
    }

    val codec = ControlPayloadCodec()
    val invalid = when (body) {
        is ControlCommandBody.SettleRetiredNamespace -> RetiredNamespaceSettlementTransition(codec).invalidInput(body.input)
        is ControlCommandBody.RotateAndSettleCurrentNull -> CurrentNullSettlementTransition(codec).invalidInput(body.input)
        is ControlCommandBody.SettleRetiredNull -> RetiredNullSettlementTransition(codec).invalidInput(body.input)
    }
    if (invalid != null) return unavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT,
        namedLocation(FixedInputRoot.SETTLEMENT_INPUT))

    return when (body) {
        is ControlCommandBody.SettleRetiredNamespace -> retiredNamespaceSlots(input.exactCommand, body, codec)
        is ControlCommandBody.RotateAndSettleCurrentNull -> currentNullSlots(input.exactCommand, body, codec)
        is ControlCommandBody.SettleRetiredNull -> retiredNullSlots(input.exactCommand, body, codec)
    }
}

private fun settlementGroup(fixed: HandoverSettlementInput, after: FenceV1? = null,
                            demand: SettlementDemand? = null): List<FixedSourceEvidence> = buildList {
    add(fact(FixedInputRoot.SETTLEMENT_INPUT, FixedInputFacet.WHOLE, FixedSourceFact.SettlementInput(fixed)))
    add(fact(FixedInputRoot.SETTLEMENT_INPUT, FixedInputFacet.BEFORE, FixedSourceFact.Fence(fixed.before)))
    if (after != null) add(fact(FixedInputRoot.SETTLEMENT_INPUT, FixedInputFacet.AFTER, FixedSourceFact.Fence(after)))
    add(fact(FixedInputRoot.SETTLEMENT_INPUT, FixedInputFacet.SOURCE, FixedSourceFact.Executor(fixed.executor)))
    if (demand != null) add(fact(FixedInputRoot.SETTLEMENT_INPUT, FixedInputFacet.DEMAND, FixedSourceFact.Demand(demand)))
}

private fun retiredNamespaceSlots(ref: CommandRef, body: ControlCommandBody.SettleRetiredNamespace,
                                  codec: ControlPayloadCodec): RequirementDerivation {
    val fixed = body.input
    val writer = RetiredNamespaceSettlementTransition(codec)
    val seal = checkNotNull(originalSeal(fixed.target))
    val journal = JournalTargetV1(seal.key.ownerUid, seal.key.axis, seal.key.epoch)
    val original = source(FixedInputRoot.SETTLEMENT_TARGET, 0, fixed.target)
    val evidence = settlementGroup(fixed, demand = fixed.demand) + original
    val role = ObligationRole.Settlement(HandoverSettlementTransition.RETIRED_NAMESPACE)
    val builder = NamedSlots(ExactCommandBinding(ref, body, FixedCommandKind.Settlement(HandoverSettlementTransition.RETIRED_NAMESPACE)), role)
    builder.sealAndJournal(seal, writer.witness(fixed), journal, evidence, original.location)
    builder.request(fixed.demandId, fixed.demand, evidence,
        namedLocation(FixedInputRoot.SETTLEMENT_INPUT, facet = FixedInputFacet.DEMAND))
    return builder.result()
}

private fun currentNullSlots(ref: CommandRef, body: ControlCommandBody.RotateAndSettleCurrentNull,
                             codec: ControlPayloadCodec): RequirementDerivation {
    val fixed = body.input
    val writer = CurrentNullSettlementTransition(codec)
    val group = settlementGroup(fixed, fixed.after, fixed.demand)
    val originals = fixed.targets.mapIndexed { index, target -> source(FixedInputRoot.SETTLEMENT_TARGET, index, target.original) }
    val role = ObligationRole.Settlement(HandoverSettlementTransition.CURRENT_NULL)
    val builder = NamedSlots(ExactCommandBinding(ref, body, FixedCommandKind.Settlement(HandoverSettlementTransition.CURRENT_NULL)), role)
    for (axis in listOf(PurgeScope.USER, PurgeScope.CAPABILITY).filter { it in fixed.axes }) {
        val members = fixed.targets.withIndex().filter { it.value.seal.key.axis == axis }
        val axisSources = members.map { originals[it.index] }
        val journal = JournalTargetV1(fixed.before.ownerUid, axis, null)
        for ((index, target) in members) {
            val evidence = group + originals[index]
            builder.sealAndJournal(target.seal, writer.witness(fixed, target.seal), journal,
                evidence, originals[index].location)
        }
        val representative = members.first()
        builder.retirement(RetirementScope(representative.value.seal.id, fixed.before, fixed.after, journal),
            group + axisSources, originals[representative.index].location)
    }
    builder.request(fixed.demandId, fixed.demand, group + originals,
        namedLocation(FixedInputRoot.SETTLEMENT_INPUT, facet = FixedInputFacet.DEMAND))
    return builder.result()
}

private fun retiredNullSlots(ref: CommandRef, body: ControlCommandBody.SettleRetiredNull,
                             codec: ControlPayloadCodec): RequirementDerivation {
    val fixed = body.input
    val writer = RetiredNullSettlementTransition(codec)
    val group = settlementGroup(fixed)
    val originals = fixed.ordered.mapIndexed { index, target -> source(FixedInputRoot.SETTLEMENT_TARGET, index, target.original) }
    val role = ObligationRole.Settlement(HandoverSettlementTransition.RETIRED_NULL)
    val builder = NamedSlots(ExactCommandBinding(ref, body, FixedCommandKind.Settlement(HandoverSettlementTransition.RETIRED_NULL)), role)
    for ((index, target) in fixed.ordered.withIndex()) {
        val seal = target.seal
        val journal = JournalTargetV1(seal.key.ownerUid, seal.key.axis, null)
        builder.sealAndJournal(seal, writer.witness(fixed, seal), journal,
            group + originals[index], originals[index].location)
    }
    builder.request(null, null, group + originals,
        namedLocation(FixedInputRoot.SETTLEMENT_INPUT, facet = FixedInputFacet.DEMAND))
    return builder.result()
}
