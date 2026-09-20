package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlRecordStore.Outcome

/** Four named writers share one atomic demand/evidence commit. No runtime publication occurs here. */
internal class DemandAuthTransition(private val codec: ControlPayloadCodec) {
    private val shared = NamespaceSettlementTransition(codec)

    fun decide(command: CommandRef, input: ControlLifecycleDescriptor, read: ControlRecordRead.Supported,
        context: AttemptContext): RecordTransactionDecision<Outcome> {
        fun reject(detail: String) = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(
            ControlStoreResult.Rejected(command, emptySet(), emptySet(), RejectionReason.InvalidRequest(detail), read)))
        fun conflict() = RecordTransactionDecision.Observe<Outcome>(Outcome.Negative(
            ControlStoreResult.Conflict(command, emptySet(), emptySet(), ConflictReason.TargetChanged,
                TargetExpectation(command, input.targets.map { it.target.id }), read)))
        val plan = checkNotNull(input.demandAuth)
        plan.preparationFailure?.let { return reject(it) }
        val runtime = context.demandAuth ?: return reject("AttemptContextRequired")
        val raw = read.original
        if (!current(plan.binding, runtime, raw)) return conflict()
        for (target in input.requiredUnchanged) {
            ControlLifecycleBoundary.preimage(read, target)?.let { return conflict() }
        }
        // Creating a guard must not adopt or overwrite an independently installed guard.
        if (plan.guardBefore == null && plan.guardAfter != null && !guardCreationAvailable(read)) return conflict()
        if (shared.canonicalJournal(raw) == null) return RecordTransactionDecision.Observe(Outcome.Negative(
            ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), RecoveryReason.JournalMigrationRequired, read))) // W.journal
        eligibility(plan, runtime, read)?.let { return if (it == "CurrentnessChanged") conflict() else reject(it) }
        val candidate = raw.toMutablePreferences()
        val rows = expectedRows(read, input)
        when (val encoded = shared.encodeChanged(ControlKind.DEMAND, rows)) {
            is NamespaceSettlementTransition.ChangedPayload.Rejected -> return RecordTransactionDecision.Observe(Outcome.Negative(
                ControlStoreResult.Rejected(command, emptySet(), emptySet(), encoded.reason, read)))
            is NamespaceSettlementTransition.ChangedPayload.Encoded -> candidate[ControlRecordKeys.payload(ControlKind.DEMAND)] = encoded.text
        }
        ControlAppliedEvidence.append(candidate, read, evidence(command, input), codec)?.let {
            return RecordTransactionDecision.Observe(Outcome.Negative(ControlStoreResult.Rejected(command, emptySet(), emptySet(), it, read)))
        }
        if (!validCandidate(command, input, read, candidate)) return reject("RequiredDecisionEffectMissing") // W.candidate
        val after = ControlRecordReader(codec).read(candidate) as ControlRecordRead.Supported
        return RecordTransactionDecision.Confirm(candidate, Outcome.Positive(ConfirmedEffect.AppliedThisAttempt,
            input.targets.map { it.target.id }, ControlLifecycleConfirmation(codec).receipt(input, after)))
    }

    internal fun current(binding: LifecycleBinding, runtime: DemandAuthRuntime, raw: Preferences): Boolean {
        if (raw[OWNER_UID] != binding.executor.ownerUid) return false // W.owner
        if (runtime.binding != binding) return false // W.binding
        if (binding.executor.originLifetimeId.value.isEmpty()) return false // W.origin
        if (binding.startedOrder < 0) return false // W.start
        if (binding.executor.binding < 0) return false // W.bindingDomain
        return true
    }

    internal fun guardCreationAvailable(read: ControlRecordRead.Supported): Boolean {
        if (guards(read).isNotEmpty()) return false // W.guardUnique
        return true
    }

    internal fun eligibility(plan: DemandAuthPlan, runtime: DemandAuthRuntime, read: ControlRecordRead.Supported): String? {
        val beforeAuth = guard(plan.guardBefore)?.auth
        val afterAuth = guard(plan.guardAfter)?.auth
        val binding = plan.binding
        val d = plan.decision
        if (d != null) {
            if (!DemandAuthBoundary.acceptance(d, runtime)) return "FreshQueryRequired" // W.acceptance
            if (d.query.fence.ownerUid != binding.executor.ownerUid) return "FreshQueryRequired" // W.queryOwner
            if (d.query.binding != binding.executor.binding) return "FreshQueryRequired" // W.queryBinding
            if (d.query.order.origin != binding.executor.originLifetimeId) return "FreshQueryRequired" // W.queryOrigin
            if (!DemandAuthBoundary.after(d, runtime.generation, rawFence(read.original))) return "CurrentnessChanged" // W.after
            if (!durableEffects(d, read)) return "DecisionEffectsUnconfirmed" // W.effects
            for (target in plan.targets.filter { it.target.effect == LifecycleEffect.REMOVE }) {
                val request = demand(target.before) ?: return "InvalidRequestTarget"
                if (!DemandAuthBoundary.request(request, d.query)) return when {
                    d.query.intent < request.intent -> "DemandIntentNotSatisfied"
                    else -> "DemandOrderNotSatisfied"
                } // W.request
                if (!DemandAuthBoundary.consumes(d.outcome, request.intent, d.reapproval)) return "DemandOutcomeNotSettled" // W.consumes
            }
            if (beforeAuth != null && !DemandAuthBoundary.scope(beforeAuth, binding)) return "AuthEventIneligible" // W.answerScope
            if (plan.transition == LifecycleTransition.UPDATE_AUTH) {
                if (beforeAuth == null) return "AuthEventIneligible" // W.answerAuth
                if (beforeAuth != null) {
                    if (!DemandAuthBoundary.answerOrder(beforeAuth, d.query.order.value)) return "AuthEventIneligible" // W.answerOrder
                    if (beforeAuth == afterAuth) return "AuthEventIneligible" // W.answerChange
                }
            }
            if (plan.transition == LifecycleTransition.SETTLE_QUERY && plan.targets.none { it.target.effect == LifecycleEffect.REMOVE } &&
                beforeAuth != afterAuth) return "AuthEventIneligible" // W.zeroRemoveRoute
        }
        for (target in plan.targets.filter { it.role == LifecycleRole.REQUEST && it.target.effect != LifecycleEffect.REMOVE }) {
            val old = demand(target.before)
            val after = demand(target.after) ?: return "InvalidRequestTarget"
            if (plan.transition == LifecycleTransition.REBIND_REQUESTS || plan.transition == LifecycleTransition.END_AUTH_BINDING) {
                if (old == null) return "InvalidRebind"
                if (!DemandAuthBoundary.rebind(old, binding, after.intent)) return "InvalidRebind" // W.rebind
            }
            val grant = plan.grants[after.id] ?: return "OrderExhausted"
            val lower = maxOf(d?.query?.order?.value ?: 0,
                old?.raisedAt?.takeIf { it.origin == binding.executor.originLifetimeId }?.value ?: 0)
            if (!DemandAuthBoundary.order(grant, binding, lower)) return "OrderExhausted" // W.order
            if (!DemandAuthBoundary.orderValueMatches(after, grant)) return "OrderExhausted"
        }
        when (val event = plan.event) {
            LifecycleAuthEvent.Initialize -> if (!DemandAuthBoundary.initialization(binding, runtime, beforeAuth)) return "AuthInitializationIneligible" // W.initialize
            is LifecycleAuthEvent.Answer, null -> Unit
            is LifecycleAuthEvent.Caller -> {
                if (runtime.caller != event.fact) return "AuthEventIneligible" // W.callerEvent
                if (event.fact.binding != binding) return "AuthEventIneligible" // W.callerPin
                if (runtime.liveIdentity != binding.identity) return "AuthEventIneligible" // W.callerIdentity
                if (!DemandAuthBoundary.caller(event.fact)) return "AuthEventIneligible" // W.caller
                if (beforeAuth == null) return "AuthEventIneligible"
                if (!DemandAuthBoundary.scope(beforeAuth, binding)) return "AuthEventIneligible" // W.callerScope
                if (!DemandAuthBoundary.order(event.fact.order, binding, beforeAuth.authStateOrder)) return "AuthEventIneligible" // W.callerOrder
            }
            is LifecycleAuthEvent.Recovery -> {
                if (runtime.recovery != event.fact) return "AuthEventIneligible" // W.recoveryEvent
                if (event.fact.origin != binding.executor.originLifetimeId) return "AuthEventIneligible" // W.recoveryOrigin
                if (beforeAuth == null) return "AuthEventIneligible"
                if (!DemandAuthBoundary.scope(beforeAuth, binding)) return "AuthEventIneligible" // W.recoveryScope
                if (!DemandAuthBoundary.recovery(beforeAuth, event.fact, runtime)) return "AuthEventIneligible" // W.recovery
            }
        }
        if (plan.transition == LifecycleTransition.END_AUTH_BINDING) {
            val closure = plan.closure ?: return "BindingNotClosed"
            if (beforeAuth == null) return "BindingNotClosed"
            if (!DemandAuthBoundary.closure(beforeAuth, closure)) return "BindingNotClosed" // W.closure
            if (runtime.closure != closure) return "RelatedWorkNotQuiescent" // W.closureCurrent
            if (runtime.generation != closure.generation) return "RelatedWorkNotQuiescent" // W.closureGeneration
            if (beforeAuth.ownerUid == binding.executor.ownerUid && beforeAuth.binding == binding.executor.binding &&
                beforeAuth.originLifetimeId == binding.executor.originLifetimeId) return "BindingNotClosed" // W.oldScope
            if (plan.replacement != null && !DemandAuthBoundary.initialization(plan.replacement, runtime, null)) return "AuthInitializationIneligible" // W.replacement
            // The caller must capture all same-owner requests needing this handover, not select a subset.
            val owed = read.arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>()
                .mapNotNull { it.value as? DemandV1 }.filter { it.ownerUid == binding.executor.ownerUid &&
                    (it.binding != binding.executor.binding || it.raisedAt.origin != binding.executor.originLifetimeId) }.map { it.id }.toSet()
            if (owed != plan.targets.filter { it.role == LifecycleRole.REQUEST }.map { it.target.id }.toSet()) return "InvalidRebind" // W.endRequests
        } else if (beforeAuth != null && afterAuth != null && beforeAuth != afterAuth) {
            if (!DemandAuthBoundary.authChange(beforeAuth, afterAuth)) return "AuthEventIneligible" // W.authChange
            if (afterAuth.authStopped) {
                val grant = plan.grants["auth"] ?: return "OrderExhausted"
                if (!DemandAuthBoundary.order(grant, binding, maxOf(beforeAuth.authStopAppliedOrder, afterAuth.authStateOrder))) return "OrderExhausted" // W.stopOrder
            }
        }
        return null
    }

    internal fun durableEffects(d: AcceptedQueryDecision, read: ControlRecordRead.Supported): Boolean {
        if (d.confirmedAfterFence != d.acceptedBeforeFence) {
            val confirmation = d.namespaceConfirmation
            if (confirmation == null) return false // Q10namespace
            if (confirmation != null && rawFence(confirmation.record.original) != d.confirmedAfterFence) return false // Q10fence
        }
        for (effect in d.effects) {
            val proof = effect.confirmation
            if (proof == null) return false // Q10
            val value = ControlSchema.read(effect.kind, effect.node) ?: return false
            if (proof != null && !exactRow(proof.record, effect.kind, value.id, effect.node)) return false // Q10proof
            if (!exactRow(read, effect.kind, value.id, effect.node)) return false // Q10current
        }
        return true
    }

    /** Independent business-effect boundary: schema-valid partial candidates must fail here. */
    internal fun requiredEffects(plan: DemandAuthPlan, after: ControlRecordRead.Supported): Boolean {
        val actualGuard = plan.guardAfter?.let { expected ->
            val id = guard(expected)?.id ?: return false
            after.locations(id).singleOrNull()?.second?.let { (it as? ControlEntryRead.Interpreted)?.value as? ScheduleGuardV1 }
        }
        // Re-derive obligations from the fixed event, independently of the builder's proposed rows
        // and cached merge values. A schema-valid partial builder output cannot opt out of A3.
        val d = plan.decision
        val seconds = d?.let { DemandAuthBoundary.statedSeconds(it.outcome) }
        val stated = if (seconds != null) {
            if (!DemandAuthBoundary.seconds(seconds)) return false
            FloorV1(d.capture.bootId, d.capture.elapsedMillis, seconds * 1000, d.floorOrigin).remainingAt(d.mergeNow) ?: return false
        } else null
        val delay = d?.let(DemandAuthBoundary::minimumDelay) ?: 0
        val required = if (stated != null || delay > 0) maxOf(stated ?: 0, delay) else null
        if (required != null) {
            if (actualGuard?.floor == null) return false // A13a Q17a
            val remaining = actualGuard?.floor?.remainingAt(checkNotNull(plan.mergeNow))
            if (remaining != null && remaining < required) return false // A13b
            val oldRemaining = guard(plan.guardBefore)?.floor?.remainingAt(checkNotNull(plan.mergeNow)) ?: 0
            if (remaining != null && remaining < oldRemaining) return false // A13c
        }
        val caller = (plan.event as? LifecycleAuthEvent.Caller)?.fact
        val intent = if (d != null) DemandAuthBoundary.retryIntent(d) else if (caller != null &&
            (guard(plan.guardBefore)?.floor?.remainingAt(caller.now) ?: 0) > 0) caller.intent else null
        if (intent != null) {
            val expected = demand(plan.retryAfter)
            val request = expected?.let { after.locations(it.id).singleOrNull()?.second as? ControlEntryRead.Interpreted }?.value as? DemandV1
            if (request == null) return false // A14a Q17b
            if (request != null && request.intent < intent) return false // A14b
            if (request != null && request.ownerUid != plan.binding.executor.ownerUid) return false // C04owner
            if (request != null && request.binding != plan.binding.executor.binding) return false // C04binding
            if (request != null && request.raisedAt.origin != plan.binding.executor.originLifetimeId) return false // C04origin
            if (request != null && request.raisedAt.value != expected!!.raisedAt.value) return false // C04order
            if (caller != null && request != null && !callerRetry(request, demand(plan.retryBefore), plan.binding,
                    caller, plan.grants[request.id])) return false
        }
        if (!authPostcondition(plan, actualGuard?.auth)) return false // C08event
        return true
    }

    /** A delayed CALLER is an independent demand, including when it reuses a stronger REQUEST.
     * Check the candidate against the fixed issuance/event facts, not the builder's proposed row.
     */
    internal fun callerRetry(request: DemandV1, before: DemandV1?, binding: LifecycleBinding,
        caller: LifecycleCaller, grant: LifecycleOrderGrant?): Boolean {
        if (grant == null) return false // A04.callerGrant
        if (request.raisedAt.value <= binding.startedOrder) return false // A04.callerBindingStart
        if (grant != null && request.raisedAt.value <= grant.previous) return false // A04.callerPrevious
        if (request.raisedAt.value <= caller.order.value) return false // A04.callerEvent
        if (before != null && request.raisedAt.value <= before.raisedAt.value) return false // A04.callerRaisedAt
        if (before != null && request.intent < before.intent) return false // A04.callerIntent
        return true
    }

    internal fun authPostcondition(plan: DemandAuthPlan, actual: AuthSnapshotV1?): Boolean {
        val before = guard(plan.guardBefore)?.auth
        val d = plan.decision
        val expected = when {
            plan.transition == LifecycleTransition.END_AUTH_BINDING -> plan.replacement?.let(::initialAuth)
            plan.event == LifecycleAuthEvent.Initialize -> initialAuth(plan.binding)
            plan.event is LifecycleAuthEvent.Caller -> before?.copy(authStopped = false, authStateOrder = plan.event.fact.order.value)
            plan.event is LifecycleAuthEvent.Recovery -> before?.copy(authStopped = false, authStateOrder = plan.event.fact.recoveredOrder)
            d != null && before != null && d.query.order.value > before.authStateOrder -> {
                if ((d.outcome as? EntitlementsOutcome.Indeterminate)?.reason == IndeterminateReason.AUTHENTICATION)
                    before.copy(authStopped = true, authStateOrder = d.query.order.value,
                        authStopAppliedOrder = plan.grants["auth"]?.value ?: return false)
                else if (before.authStopped) before.copy(authStopped = false, authStateOrder = d.query.order.value) else before
            }
            else -> before
        }
        if (actual != expected) return false // C08auth
        return true
    }

    internal fun validCandidate(command: CommandRef, input: ControlLifecycleDescriptor,
        before: ControlRecordRead.Supported, candidate: Preferences): Boolean {
        val after = ControlRecordReader(codec).read(candidate) as? ControlRecordRead.Supported ?: return false
        if (ControlLifecycleBoundary.recordProblem(after) != null) return false // C12schema
        if (!requiredEffects(checkNotNull(input.demandAuth), after)) return false // C09effects
        if (after.arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() } != expectedRows(before, input)) return false // C.rows
        val expected = before.original.toMutablePreferences()
        expected[ControlRecordKeys.payload(ControlKind.DEMAND)] = candidate[ControlRecordKeys.payload(ControlKind.DEMAND)]!!
        val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        expected[evidenceKey] = candidate[evidenceKey]!!
        if (expected != candidate) return false // C11external
        val oldRows = (before.metadata as ControlMetadataRead.V2).evidence.entries.map { when (it) { is ControlEvidenceEntryRead.Interpreted -> it.original.toPayloadEntry(); is ControlEvidenceEntryRead.Uninterpretable -> it.original } }
        val actualRows = (after.metadata as ControlMetadataRead.V2).evidence.entries.map { when (it) { is ControlEvidenceEntryRead.Interpreted -> it.original.toPayloadEntry(); is ControlEvidenceEntryRead.Uninterpretable -> it.original } }
        if (actualRows != oldRows + PayloadEntry.Obj(ControlAppliedEvidence.node(evidence(command, input)))) return false // C10evidence
        return true
    }
    private fun expectedRows(read: ControlRecordRead.Supported, input: ControlLifecycleDescriptor): List<PayloadEntry> {
        val byId = input.targets.associateBy { it.target.id }
        val rows = read.arrays.getValue(ControlKind.DEMAND).entries.mapNotNull { entry ->
            val target = byId[(entry as? ControlEntryRead.Interpreted)?.value?.id]
            if (target == null) entry.payload() else target.after?.toPayloadEntry()
        }
        return rows + input.targets.filter { it.target.effect == LifecycleEffect.CREATE }.map { checkNotNull(it.after).toPayloadEntry() }
    }
    private fun evidence(command: CommandRef, input: ControlLifecycleDescriptor) = AppliedEvidence.Lifecycle(
        command.id, command.ownerTrackingLifetimeId.value, input.transition, input.targets.map { it.target })
    private fun guards(read: ControlRecordRead.Supported) = read.arrays.getValue(ControlKind.DEMAND).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().filter { it.value is ScheduleGuardV1 }
    private fun exactRow(read: ControlRecordRead.Supported, kind: ControlKind, id: String, node: ControlNode): Boolean {
        val location = read.locations(id).singleOrNull() ?: return false
        return location.first == kind && (location.second as? ControlEntryRead.Interpreted)?.original?.toPayloadEntry() == node.toPayloadEntry()
    }
    private fun ControlEntryRead.payload(): PayloadEntry = when (this) {
        is ControlEntryRead.Interpreted -> original.toPayloadEntry()
        is ControlEntryRead.Uninterpretable -> original
    }
    private fun rawFence(raw: Preferences) = FenceV1(raw[OWNER_UID], raw[USER_EPOCH], raw[KRX_EPOCH])
}
