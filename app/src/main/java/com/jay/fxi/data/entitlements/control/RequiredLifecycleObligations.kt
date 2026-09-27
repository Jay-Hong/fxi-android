package com.jay.fxi.data.entitlements.control

import java.util.Collections

private fun lifecycleLocation(root: FixedInputRoot, index: Int? = null,
                              facet: FixedInputFacet = FixedInputFacet.WHOLE) = FixedInputLocation(root, index, facet)

private fun lifecycleUnavailable(reason: RequiredObligationsUnavailable, root: FixedInputRoot,
                                 index: Int? = null, facet: FixedInputFacet = FixedInputFacet.WHOLE) =
    RequirementDerivation.Unavailable(reason, lifecycleLocation(root, index, facet))

private fun fixedEvidence(root: FixedInputRoot, index: Int?, facet: FixedInputFacet, fact: FixedSourceFact) =
    FixedSourceEvidence(lifecycleLocation(root, index, facet), fact)

private fun sameFixedRow(left: LifecycleFixedTarget, right: LifecycleFixedTarget): Boolean =
    left.target == right.target && left.role == right.role &&
        left.before?.toPayloadEntry() == right.before?.toPayloadEntry() &&
        left.after?.toPayloadEntry() == right.after?.toPayloadEntry()

/** The named plan is fixed before observation; no current record or candidate is read here. */
internal fun deriveLifecycleObligations(input: RequirementInput.Lifecycle): RequirementDerivation {
    val body = input.exactCommand.captureStateAndBody().body
        ?: return lifecycleUnavailable(RequiredObligationsUnavailable.BODY_UNAVAILABLE, FixedInputRoot.COMMAND)
    if (body !== input.body || body !is ControlCommandBody.Lifecycle)
        return lifecycleUnavailable(RequiredObligationsUnavailable.BODY_MISMATCH, FixedInputRoot.COMMAND)
    val descriptor = body.input
    val demandAuth = descriptor.transition in setOf(LifecycleTransition.REBIND_REQUESTS, LifecycleTransition.SETTLE_QUERY,
        LifecycleTransition.UPDATE_AUTH, LifecycleTransition.END_AUTH_BINDING)
    val planPresent = when (descriptor.transition) {
        LifecycleTransition.REMOVE_EMPTY_GUARD -> descriptor.removeEmptyGuard != null
        LifecycleTransition.RECOVER_HOLD -> descriptor.recoverHold != null
        LifecycleTransition.RECOVER_INTENT -> descriptor.recoverIntent != null
        else -> descriptor.demandAuth != null
    }
    if (!planPresent) return lifecycleUnavailable(RequiredObligationsUnavailable.PLAN_MISSING, FixedInputRoot.LIFECYCLE_PLAN)
    val writers = listOf(descriptor.demandAuth, descriptor.removeEmptyGuard, descriptor.recoverHold, descriptor.recoverIntent)
    if (writers.count { it != null } != 1)
        return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, FixedInputRoot.LIFECYCLE_PLAN)
    if (descriptor.demandAuth?.preparationFailure != null || descriptor.recoverHold?.preparationProblem != null ||
        descriptor.recoverIntent?.preparationProblem != null)
        return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, FixedInputRoot.LIFECYCLE_PLAN)

    // Interpret original rows before a shape failure can hide the more precise source location.
    for ((root, rows) in listOf(FixedInputRoot.LIFECYCLE_TARGET to descriptor.targets,
            FixedInputRoot.LIFECYCLE_UNCHANGED to descriptor.requiredUnchanged)) {
        for ((index, row) in rows.withIndex()) {
            val source = row.before ?: row.after
            if (source != null && ControlObligations.read(row.target.kind, source) !is ControlEntryRead.Interpreted)
                return lifecycleUnavailable(RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE,
                    root, index, FixedInputFacet.SOURCE)
            if (row.after != null && ControlObligations.read(row.target.kind, row.after) !is ControlEntryRead.Interpreted)
                return lifecycleUnavailable(RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE,
                    root, index, FixedInputFacet.AFTER)
        }
    }
    if (!ControlLifecycleConfirmation(ControlPayloadCodec()).validDescriptor(descriptor))
        return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, FixedInputRoot.LIFECYCLE_PLAN)

    val binding = ExactCommandBinding(input.exactCommand, body, FixedCommandKind.Lifecycle(descriptor.transition))
    if (!demandAuth) return when (descriptor.transition) {
        LifecycleTransition.REMOVE_EMPTY_GUARD -> deriveRemoveEmptyGuard(binding, descriptor)
        LifecycleTransition.RECOVER_HOLD -> deriveRecoverHold(binding, descriptor)
        LifecycleTransition.RECOVER_INTENT -> deriveRecoverIntent(binding, descriptor)
        else -> error("Unexpected demand/auth transition")
    }
    val plan = checkNotNull(descriptor.demandAuth)

    val named = plan.descriptor(descriptor.operationId)
    if (descriptor.transition != named.transition || descriptor.namespace != null)
        return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, FixedInputRoot.LIFECYCLE_PLAN)
    if (descriptor.executor != named.executor)
        return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT,
            FixedInputRoot.LIFECYCLE_PLAN, facet = FixedInputFacet.BINDING)
    for ((root, actual, expected) in listOf(
            Triple(FixedInputRoot.LIFECYCLE_TARGET, descriptor.targets, named.targets),
            Triple(FixedInputRoot.LIFECYCLE_UNCHANGED, descriptor.requiredUnchanged, named.requiredUnchanged))) {
        for (index in 0 until maxOf(actual.size, expected.size)) {
            if (index >= actual.size || index >= expected.size || !sameFixedRow(actual[index], expected[index]))
                return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, root, index)
        }
    }

    // Only fixed facts that can make a required AUTH disappear are additional transition gates in this unit.
    if (descriptor.transition == LifecycleTransition.UPDATE_AUTH) {
        if (plan.event is LifecycleAuthEvent.Answer && guard(plan.guardBefore)?.auth == null)
            return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT,
                FixedInputRoot.LIFECYCLE_PLAN, facet = FixedInputFacet.EVENT)
        if (plan.event == LifecycleAuthEvent.Initialize && plan.binding.identity == null)
            return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT,
                FixedInputRoot.LIFECYCLE_PLAN, facet = FixedInputFacet.BINDING)
    }

    return LifecycleSlots(binding, descriptor, plan).build()
}

private fun deriveRemoveEmptyGuard(binding: ExactCommandBinding, descriptor: ControlLifecycleDescriptor): RequirementDerivation {
    val plan = checkNotNull(descriptor.removeEmptyGuard)
    val named = plan.descriptor(descriptor.operationId)
    if (descriptor.executor != named.executor || descriptor.namespace != named.namespace)
        return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, FixedInputRoot.LIFECYCLE_PLAN)
    for ((root, actual, expected) in listOf(
            Triple(FixedInputRoot.LIFECYCLE_TARGET, descriptor.targets, named.targets),
            Triple(FixedInputRoot.LIFECYCLE_UNCHANGED, descriptor.requiredUnchanged, named.requiredUnchanged))) {
        for (index in 0 until maxOf(actual.size, expected.size)) {
            if (index >= actual.size || index >= expected.size || !sameFixedRow(actual[index], expected[index]))
                return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, root, index)
        }
    }
    if (!RemoveEmptyGuardTransition(ControlPayloadCodec()).empty(checkNotNull(descriptor.targets[0].before)))
        return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT,
            FixedInputRoot.LIFECYCLE_TARGET, 0, FixedInputFacet.SOURCE)
    return LifecycleRecoverySlots(binding, descriptor, listOf(fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN,
        null, FixedInputFacet.WHOLE, FixedSourceFact.LifecyclePlan(descriptor)))).remove()
}

private fun recoveryBase(descriptor: ControlLifecycleDescriptor, binding: LifecycleBinding,
                         closure: HoldRecoveryClosure, mergeNow: BootReading?): List<FixedSourceEvidence> = buildList {
    add(fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE, FixedSourceFact.LifecyclePlan(descriptor)))
    add(fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.BINDING, FixedSourceFact.Binding(binding)))
    add(fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.CLOSURE, FixedSourceFact.RecoveryClosure(closure)))
    mergeNow?.let { add(fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.FLOOR, FixedSourceFact.Boot(it))) }
    val namespace = checkNotNull(descriptor.namespace)
    add(fixedEvidence(FixedInputRoot.LIFECYCLE_NAMESPACE, null, FixedInputFacet.WHOLE, FixedSourceFact.Namespace(namespace)))
    add(fixedEvidence(FixedInputRoot.LIFECYCLE_NAMESPACE, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(namespace.before)))
    add(fixedEvidence(FixedInputRoot.LIFECYCLE_NAMESPACE, null, FixedInputFacet.AFTER, FixedSourceFact.Fence(namespace.after)))
}

private fun deriveRecoverHold(binding: ExactCommandBinding, descriptor: ControlLifecycleDescriptor): RequirementDerivation {
    val plan = checkNotNull(descriptor.recoverHold)
    if (!RecoverHoldTransition(ControlPayloadCodec()).validDescriptor(plan, descriptor))
        return lifecycleUnavailable(RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, FixedInputRoot.LIFECYCLE_PLAN)
    val base = recoveryBase(descriptor, plan.input.binding, plan.input.closure, plan.input.mergeNow)
    return LifecycleRecoverySlots(binding, descriptor, base).hold(plan)
}

private fun deriveRecoverIntent(binding: ExactCommandBinding, descriptor: ControlLifecycleDescriptor): RequirementDerivation {
    val plan = checkNotNull(descriptor.recoverIntent)
    val base = recoveryBase(descriptor, plan.input.binding, plan.input.closure, null)
    return LifecycleRecoverySlots(binding, descriptor, base).intent(plan)
}

/** Additional lifecycle transitions use their fixed source rows, never a current record or candidate. */
private class LifecycleRecoverySlots(
    private val binding: ExactCommandBinding,
    private val descriptor: ControlLifecycleDescriptor,
    private val base: List<FixedSourceEvidence>
) {
    private val slots = mutableListOf<RequiredSlot>()
    private val keys = mutableSetOf<RequiredObligationKey>()
    private var duplicate: FixedInputLocation? = null
    private val commandRole = ObligationRole.LifecycleCommand(descriptor.transition)

    private fun rowSources(root: FixedInputRoot, index: Int, row: LifecycleFixedTarget): List<FixedSourceEvidence> = buildList {
        add(fixedEvidence(root, index, FixedInputFacet.WHOLE, FixedSourceFact.LifecycleTarget(row)))
        (row.before ?: row.after)?.let { add(fixedEvidence(root, index, FixedInputFacet.SOURCE, FixedSourceFact.Node(row.target.kind, it))) }
        row.after?.let { add(fixedEvidence(root, index, FixedInputFacet.AFTER, FixedSourceFact.Node(row.target.kind, it))) }
    }

    private fun pair(role: ObligationRole, subject: ObligationSubject, component: ObligationComponent,
                     landing: RequiredLowerBound?, nonLanding: RequiredLowerBound?,
                     landingAllowed: AllowedSlotDisposition = AllowedSlotDisposition.EITHER,
                     nonLandingAllowed: AllowedSlotDisposition = AllowedSlotDisposition.EITHER,
                     fixed: List<FixedSourceEvidence>, location: FixedInputLocation) {
        val sources = Collections.unmodifiableList(fixed.toList())
        for ((branch, bound, allowed) in listOf(
                Triple(LandingBranch.L, landing, landingAllowed), Triple(LandingBranch.N, nonLanding, nonLandingAllowed))) {
            val key = RequiredObligationKey(role, subject, component, branch)
            if (!keys.add(key) && duplicate == null) duplicate = location
            slots += RequiredSlot(key, if (bound == null) SlotRequirement.NotRequiredByContract(sources)
                else SlotRequirement.Required(bound, allowed, sources))
        }
    }

    private fun requiredPair(role: ObligationRole, subject: ObligationSubject, component: ObligationComponent,
                             bound: RequiredLowerBound, fixed: List<FixedSourceEvidence>, location: FixedInputLocation,
                             allowed: AllowedSlotDisposition = AllowedSlotDisposition.EITHER) =
        pair(role, subject, component, bound, bound, allowed, allowed, fixed, location)

    private fun sourceRow() = descriptor.targets[0]
    private fun sourceFixed() = base + rowSources(FixedInputRoot.LIFECYCLE_TARGET, 0, sourceRow())

    fun remove(): RequirementDerivation {
        val row = sourceRow()
        val source = checkNotNull(row.before)
        val scope = ObligationSubject.ExactTarget(ControlKind.DEMAND, row.target.id)
        pair(ObligationRole.Lifecycle(descriptor.transition, LifecycleRole.GUARD), scope, ObligationComponent.SOURCE,
            RequiredLowerBound.ExactSource(ControlKind.DEMAND, row.target.id, source), null,
            AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY, fixed = sourceFixed(),
            location = lifecycleLocation(FixedInputRoot.LIFECYCLE_TARGET, 0))
        return finish()
    }

    private fun retirement(source: RecoveryRetirementSource) {
        val namespace = checkNotNull(descriptor.namespace)
        val fixed = sourceFixed()
        for (axis in source.axes.sortedBy { it.ordinal }) {
            val key = JournalTargetV1(source.ownerUid, axis, source.targetEpoch(axis))
            requiredPair(commandRole, ObligationSubject.Journal(sourceRow().target.id, key), ObligationComponent.JOURNAL,
                RequiredLowerBound.Journal(key), fixed, lifecycleLocation(FixedInputRoot.LIFECYCLE_TARGET, 0))
            val scope = RetirementScope(sourceRow().target.id, namespace.before, namespace.after, key)
            requiredPair(commandRole, scope, ObligationComponent.NAMESPACE_RETIREMENT,
                RequiredLowerBound.NamespaceRetirement(scope), fixed, lifecycleLocation(FixedInputRoot.LIFECYCLE_TARGET, 0))
        }
    }

    private fun request(order: LifecycleOrderGrant?) {
        val index = descriptor.targets.indexOfFirst { it.role == LifecycleRole.REQUEST }
        val role = ObligationRole.Lifecycle(descriptor.transition, LifecycleRole.REQUEST)
        if (index < 0) {
            pair(role, ObligationSubject.NamedRequest(null), ObligationComponent.REQUEST, null, null,
                fixed = sourceFixed(), location = lifecycleLocation(FixedInputRoot.LIFECYCLE_TARGET, 0))
            return
        }
        val row = descriptor.targets[index]
        val after = checkNotNull(demand(row.after))
        val bound = RequiredLowerBound.Request(after, after.id, after.ownerUid, after.binding, after.intent, after.raisedAt)
        val fixed = sourceFixed() + rowSources(FixedInputRoot.LIFECYCLE_TARGET, index, row) +
            fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, index, FixedInputFacet.GRANT,
                FixedSourceFact.OrderGrant(checkNotNull(order)))
        requiredPair(role, ObligationSubject.Request(after.id, after.ownerUid, after.binding, after.raisedAt),
            ObligationComponent.REQUEST, bound, fixed, lifecycleLocation(FixedInputRoot.LIFECYCLE_TARGET, index))
    }

    private fun guardRow(root: FixedInputRoot, index: Int, row: LifecycleFixedTarget) {
        val old = guard(row.before)
        val after = checkNotNull(guard(row.after))
        val role = ObligationRole.Lifecycle(descriptor.transition, LifecycleRole.GUARD)
        val fixed = sourceFixed() + rowSources(root, index, row)
        val location = lifecycleLocation(root, index)
        after.floor?.let { floor ->
            requiredPair(role, ObligationSubject.Floor(ControlKind.DEMAND, row.target.id,
                (old?.floor ?: floor).originLifetimeId), ObligationComponent.FLOOR,
                RequiredLowerBound.Floor(ControlKind.DEMAND, row.target.id, floor), fixed, location,
                AllowedSlotDisposition.DURABLY_OWNED_ONLY)
        }
        old?.auth?.let { auth ->
            val required = checkNotNull(after.auth)
            pair(role, ObligationSubject.Auth(auth.ownerUid, auth.binding, auth.originLifetimeId, auth.authGeneration),
                ObligationComponent.AUTH, RequiredLowerBound.Auth(required), RequiredLowerBound.Auth(auth),
                fixed = fixed, location = location)
        }
        if (root == FixedInputRoot.LIFECYCLE_UNCHANGED && after.floor == null && after.auth == null) {
            requiredPair(role, ObligationSubject.ExactTarget(ControlKind.DEMAND, row.target.id), ObligationComponent.SOURCE,
                RequiredLowerBound.ExactSource(ControlKind.DEMAND, row.target.id, checkNotNull(row.before)), fixed, location)
        }
    }

    fun hold(plan: RecoverHoldPlan): RequirementDerivation {
        val source = checkNotNull(HoldRecoverySource.from(plan.input.source))
        val hold = source.hold
        val role = ObligationRole.Lifecycle(descriptor.transition, LifecycleRole.HOLD)
        val fixed = sourceFixed()
        val location = lifecycleLocation(FixedInputRoot.LIFECYCLE_TARGET, 0)
        requiredPair(role, ObligationSubject.Hold(hold.id, hold.originLifetimeId, hold.binding, hold.provenance, hold.axes),
            ObligationComponent.SOURCE, RequiredLowerBound.Hold(hold), fixed, location)
        hold.floor?.let { floor ->
            requiredPair(role, ObligationSubject.Floor(ControlKind.HOLD, hold.id, floor.originLifetimeId),
                ObligationComponent.FLOOR, RequiredLowerBound.Floor(ControlKind.HOLD, hold.id, floor), fixed, location,
                AllowedSlotDisposition.DURABLY_OWNED_ONLY)
        }
        retirement(source)
        request(plan.requestOrder)
        descriptor.targets.forEachIndexed { index, row ->
            if (row.role == LifecycleRole.GUARD) guardRow(FixedInputRoot.LIFECYCLE_TARGET, index, row)
        }
        descriptor.requiredUnchanged.forEachIndexed { index, row ->
            guardRow(FixedInputRoot.LIFECYCLE_UNCHANGED, index, row)
        }
        return finish()
    }

    fun intent(plan: RecoverIntentPlan): RequirementDerivation {
        val source = checkNotNull(IntentRecoverySource.from(plan.input.source))
        val intent = source.intent
        val role = ObligationRole.Lifecycle(descriptor.transition, LifecycleRole.RECOVERY_INTENT)
        requiredPair(role, ObligationSubject.Intent(intent.id, intent.sessionId, intent.ownerUid, intent.axis, intent.targetEpoch),
            ObligationComponent.SOURCE, RequiredLowerBound.Intent(intent), sourceFixed(),
            lifecycleLocation(FixedInputRoot.LIFECYCLE_TARGET, 0))
        retirement(source)
        request(plan.requestOrder)
        return finish()
    }

    private fun finish(): RequirementDerivation {
        val scope = ReceiptScope(descriptor.operationId, descriptor.transition, null, null)
        val fixed = base + descriptor.targets.flatMapIndexed { index, row ->
            rowSources(FixedInputRoot.LIFECYCLE_TARGET, index, row)
        } + descriptor.requiredUnchanged.flatMapIndexed { index, row ->
            rowSources(FixedInputRoot.LIFECYCLE_UNCHANGED, index, row)
        }
        pair(commandRole, scope, ObligationComponent.RECEIPT, RequiredLowerBound.Receipt(scope), null,
            AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY, fixed = fixed,
            location = lifecycleLocation(FixedInputRoot.LIFECYCLE_PLAN))
        return duplicate?.let {
            RequirementDerivation.Unavailable(RequiredObligationsUnavailable.DUPLICATE_REQUIRED_KEY, it)
        } ?: RequirementDerivation.Available(binding, Collections.unmodifiableList(slots.toList()))
    }
}

private class LifecycleSlots(
    private val binding: ExactCommandBinding,
    private val descriptor: ControlLifecycleDescriptor,
    private val plan: DemandAuthPlan
) {
    private val slots = mutableListOf<RequiredSlot>()
    private val keys = mutableSetOf<RequiredObligationKey>()
    private var duplicate: FixedInputLocation? = null
    private val commandRole = ObligationRole.LifecycleCommand(descriptor.transition)
    private val whole = listOf(fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE,
        FixedSourceFact.LifecyclePlan(descriptor)))
    private val bound = whole + fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.BINDING,
        FixedSourceFact.Binding(plan.binding))

    private fun decisionSources(decision: AcceptedQueryDecision) = bound + listOf(
        fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.DECISION, FixedSourceFact.Decision(decision)),
        fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.QUERY, FixedSourceFact.Query(decision.registration)))

    private fun baseSources(): List<FixedSourceEvidence> = when (descriptor.transition) {
        LifecycleTransition.REBIND_REQUESTS -> bound
        LifecycleTransition.SETTLE_QUERY -> decisionSources(checkNotNull(plan.decision))
        LifecycleTransition.UPDATE_AUTH -> {
            val event = checkNotNull(plan.event)
            val leading = if (event is LifecycleAuthEvent.Answer) decisionSources(event.decision) else bound
            leading + fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.EVENT, FixedSourceFact.AuthEvent(event))
        }
        LifecycleTransition.END_AUTH_BINDING -> bound + fixedEvidence(FixedInputRoot.LIFECYCLE_PLAN, null,
            FixedInputFacet.CLOSURE, FixedSourceFact.BindingClosure(checkNotNull(plan.closure)))
        else -> error("Unsupported Lifecycle transition")
    }

    private fun rowSources(root: FixedInputRoot, index: Int, row: LifecycleFixedTarget): List<FixedSourceEvidence> = buildList {
        add(fixedEvidence(root, index, FixedInputFacet.WHOLE, FixedSourceFact.LifecycleTarget(row)))
        (row.before ?: row.after)?.let {
            add(fixedEvidence(root, index, FixedInputFacet.SOURCE, FixedSourceFact.Node(row.target.kind, it)))
        }
        row.after?.let { add(fixedEvidence(root, index, FixedInputFacet.AFTER, FixedSourceFact.Node(row.target.kind, it))) }
    }

    private fun grant(index: Int, value: LifecycleOrderGrant) = fixedEvidence(
        FixedInputRoot.LIFECYCLE_PLAN, index, FixedInputFacet.GRANT, FixedSourceFact.OrderGrant(value))

    private fun pair(role: ObligationRole, subject: ObligationSubject, component: ObligationComponent,
                     landing: RequiredLowerBound, nonLanding: RequiredLowerBound = landing,
                     landingAllowed: AllowedSlotDisposition = AllowedSlotDisposition.EITHER,
                     nonLandingAllowed: AllowedSlotDisposition = AllowedSlotDisposition.EITHER,
                     fixed: List<FixedSourceEvidence>, location: FixedInputLocation) {
        val sources = Collections.unmodifiableList(fixed.toList())
        for ((branch, requirement) in listOf(
                LandingBranch.L to SlotRequirement.Required(landing, landingAllowed, sources),
                LandingBranch.N to SlotRequirement.Required(nonLanding, nonLandingAllowed, sources))) {
            val key = RequiredObligationKey(role, subject, component, branch)
            if (!keys.add(key) && duplicate == null) duplicate = location
            slots += RequiredSlot(key, requirement)
        }
    }

    private fun bindingSlots(base: List<FixedSourceEvidence>) {
        val scope = BindingScope(plan.binding, plan.closure)
        pair(commandRole, scope, ObligationComponent.BINDING, RequiredLowerBound.Binding(scope),
            landingAllowed = if (descriptor.transition == LifecycleTransition.END_AUTH_BINDING)
                AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY else AllowedSlotDisposition.EITHER,
            fixed = if (descriptor.transition == LifecycleTransition.END_AUTH_BINDING) base else bound,
            location = lifecycleLocation(FixedInputRoot.LIFECYCLE_PLAN))
    }

    private fun decisionSlots(decision: AcceptedQueryDecision) {
        val sources = decisionSources(decision)
        val query = QueryScope(decision.registration, decision.source)
        pair(commandRole, query, ObligationComponent.QUERY, RequiredLowerBound.Query(query),
            landingAllowed = AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY,
            fixed = sources, location = lifecycleLocation(FixedInputRoot.LIFECYCLE_PLAN, facet = FixedInputFacet.QUERY))
        for ((index, effect) in decision.effects.withIndex()) {
            val scope = DecisionEffectScope(descriptor.operationId, decision.registration.id, index, effect.kind, effect.node)
            pair(commandRole, scope, ObligationComponent.DURABLE_EFFECT, RequiredLowerBound.DecisionEffect(effect),
                fixed = sources + fixedEvidence(FixedInputRoot.DECISION_EFFECT, index, FixedInputFacet.EFFECT,
                    FixedSourceFact.DecisionEffect(effect)),
                location = lifecycleLocation(FixedInputRoot.DECISION_EFFECT, index, FixedInputFacet.EFFECT))
        }
        if (decision.acceptedBeforeFence != decision.confirmedAfterFence) {
            val scope = DecisionNamespaceScope(descriptor.operationId, decision.registration.id,
                decision.acceptedBeforeFence, decision.confirmedAfterFence)
            pair(commandRole, scope, ObligationComponent.DURABLE_EFFECT,
                RequiredLowerBound.DecisionNamespace(scope.before, scope.after), fixed = sources,
                location = lifecycleLocation(FixedInputRoot.LIFECYCLE_PLAN, facet = FixedInputFacet.DECISION))
        }
    }

    private fun requestSlots(row: LifecycleFixedTarget, index: Int, root: FixedInputRoot,
                             sources: List<FixedSourceEvidence>) {
        val old = demand(row.before)
        val after = demand(row.after)
        val source = checkNotNull(old ?: after)
        val subject = ObligationSubject.Request(source.id, source.ownerUid, source.binding, source.raisedAt)
        val role = ObligationRole.Lifecycle(descriptor.transition, LifecycleRole.REQUEST)
        val location = lifecycleLocation(root, index)
        if (row.target.effect == LifecycleEffect.REMOVE) {
            val required = checkNotNull(old)
            val floor = RequiredLowerBound.Request(required, required.id, required.ownerUid, required.binding,
                required.intent, required.raisedAt)
            pair(role, subject, ObligationComponent.REQUEST, floor,
                landingAllowed = AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY,
                fixed = sources, location = location)
        } else {
            val required = checkNotNull(after)
            val issued = if (root == FixedInputRoot.LIFECYCLE_TARGET) plan.grants[required.id] else null
            val fixed = if (issued == null) sources else sources + grant(index, issued)
            pair(role, subject, ObligationComponent.REQUEST,
                RequiredLowerBound.Request(required, required.id, required.ownerUid, required.binding,
                    required.intent, required.raisedAt),
                RequiredLowerBound.Request(source, required.id, required.ownerUid, required.binding,
                    required.intent, required.raisedAt),
                fixed = fixed, location = location)
        }
    }

    private fun guardSlots(row: LifecycleFixedTarget, index: Int, root: FixedInputRoot,
                           sources: List<FixedSourceEvidence>) {
        val old = guard(row.before)
        val after = guard(row.after)
        val role = ObligationRole.Lifecycle(descriptor.transition, LifecycleRole.GUARD)
        val location = lifecycleLocation(root, index)
        val floor = after?.floor
        if (floor != null) {
            val origin = old?.floor?.originLifetimeId ?: floor.originLifetimeId
            pair(role, ObligationSubject.Floor(ControlKind.DEMAND, row.target.id, origin), ObligationComponent.FLOOR,
                RequiredLowerBound.Floor(ControlKind.DEMAND, row.target.id, floor),
                landingAllowed = AllowedSlotDisposition.DURABLY_OWNED_ONLY,
                nonLandingAllowed = AllowedSlotDisposition.DURABLY_OWNED_ONLY,
                fixed = sources, location = location)
        }
        val originalAuth = old?.auth
        val afterAuth = after?.auth
        val sourceAuth = originalAuth ?: afterAuth
        if (sourceAuth != null) {
            val authSources = if (descriptor.transition == LifecycleTransition.UPDATE_AUTH) {
                val issued = when (val event = plan.event) {
                    is LifecycleAuthEvent.Caller -> event.fact.order
                    is LifecycleAuthEvent.Answer -> plan.authStopGrant
                    else -> null
                }
                if (issued == null) sources else sources + grant(index, issued)
            } else sources
            val ending = afterAuth == null
            pair(role, ObligationSubject.Auth(sourceAuth.ownerUid, sourceAuth.binding,
                sourceAuth.originLifetimeId, sourceAuth.authGeneration), ObligationComponent.AUTH,
                RequiredLowerBound.Auth(if (ending) checkNotNull(originalAuth) else checkNotNull(afterAuth)),
                RequiredLowerBound.Auth(sourceAuth),
                landingAllowed = if (ending) AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY
                    else AllowedSlotDisposition.EITHER,
                fixed = authSources, location = location)
        } else if (floor == null && descriptor.transition == LifecycleTransition.SETTLE_QUERY &&
            root == FixedInputRoot.LIFECYCLE_UNCHANGED) {
            val node = checkNotNull(row.before)
            pair(role, ObligationSubject.ExactTarget(ControlKind.DEMAND, row.target.id), ObligationComponent.SOURCE,
                RequiredLowerBound.ExactSource(ControlKind.DEMAND, row.target.id, node),
                fixed = sources, location = location)
        }
    }

    fun build(): RequirementDerivation {
        val base = baseSources()
        bindingSlots(base)
        plan.decision?.let(::decisionSlots)
        for ((root, rows) in listOf(FixedInputRoot.LIFECYCLE_TARGET to descriptor.targets,
                FixedInputRoot.LIFECYCLE_UNCHANGED to descriptor.requiredUnchanged)) {
            for ((index, row) in rows.withIndex()) {
                val sources = base + rowSources(root, index, row)
                when (row.role) {
                    LifecycleRole.REQUEST -> requestSlots(row, index, root, sources)
                    LifecycleRole.GUARD -> guardSlots(row, index, root, sources)
                    else -> error("DemandAuth row has unsupported role")
                }
            }
        }
        val scope = ReceiptScope(descriptor.operationId, descriptor.transition, plan.decision?.registration?.id, null)
        val receiptSources = base + descriptor.targets.flatMapIndexed { index, row ->
            rowSources(FixedInputRoot.LIFECYCLE_TARGET, index, row)
        } + descriptor.requiredUnchanged.flatMapIndexed { index, row ->
            rowSources(FixedInputRoot.LIFECYCLE_UNCHANGED, index, row)
        }
        val immutableSources = Collections.unmodifiableList(receiptSources.toList())
        val landingKey = RequiredObligationKey(commandRole, scope, ObligationComponent.RECEIPT, LandingBranch.L)
        val nonLandingKey = landingKey.copy(branch = LandingBranch.N)
        if (!keys.add(landingKey) && duplicate == null) duplicate = lifecycleLocation(FixedInputRoot.LIFECYCLE_PLAN)
        if (!keys.add(nonLandingKey) && duplicate == null) duplicate = lifecycleLocation(FixedInputRoot.LIFECYCLE_PLAN)
        slots += RequiredSlot(landingKey, SlotRequirement.Required(RequiredLowerBound.Receipt(scope),
            AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY, immutableSources))
        slots += RequiredSlot(nonLandingKey, SlotRequirement.NotRequiredByContract(immutableSources))
        return duplicate?.let {
            RequirementDerivation.Unavailable(RequiredObligationsUnavailable.DUPLICATE_REQUIRED_KEY, it)
        } ?: RequirementDerivation.Available(binding, Collections.unmodifiableList(slots.toList()))
    }
}
