package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DemandAuthLinkTest {
    private fun effectsNo(id: String, effect: LifecycleDurableEffect, current: Preferences = F.raw(F.request())) {
        val d = F.decision(effects = listOf(effect))
        assertEquals(d.acceptedBeforeFence, d.confirmedAfterFence)
        assertFalse(F.eligible(id), F.transition.durableEffects(d, F.read(current)))
    }
    @Test fun Q10() = effectsNo("Q10", LifecycleDurableEffect(ControlKind.DEMAND, F.request(), null))
    @Test fun Q10proof() = effectsNo("Q10proof", LifecycleDurableEffect(ControlKind.DEMAND, F.request(), ConfirmedControlSnapshot(F.read(F.raw(F.request(order = 5))))))
    @Test fun Q10current() = effectsNo("Q10current", LifecycleDurableEffect(ControlKind.DEMAND, F.request(), ConfirmedControlSnapshot(F.read(F.raw(F.request())))), F.raw(F.request(order = 5)))
    @Test fun Q10namespace() {
        val d = F.decision(after = F.fence.copy(userAccessEpoch = "new"))
        assertTrue(d.effects.isEmpty())
        assertFalse(F.eligible("Q10namespace"), F.transition.durableEffects(d, F.read(F.raw())))
    }
    @Test fun Q10fence() {
        val d = F.decision(after = F.fence.copy(userAccessEpoch = "new"), namespace = ConfirmedControlSnapshot(F.read(F.raw())))
        assertTrue(d.effects.isEmpty()); assertNotNull(d.namespaceConfirmation)
        assertFalse(F.eligible("Q10fence"), F.transition.durableEffects(d, F.read(F.raw())))
    }
    private fun removalCandidate(id: String, edit: (Preferences) -> Preferences) {
        val r = F.request(); val sibling = F.request(id = "sibling", owner = "B")
        val p = DemandAuthPlan.settle(listOf(r), null, null, F.binding, F.decision(), LifecycleOrderSource(F.life, 21), "g", "next")
        val source = F.raw(r, sibling)
        val (c, raw) = F.apply(p, source)
        val altered = edit(raw); F.schema(altered)
        assertFalse(F.atomic(id), F.transition.validCandidate(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(source), altered))
    }
    @Test fun C01() = removalCandidate("C01") { raw -> raw.toMutablePreferences().apply {
        val key = ControlRecordKeys.payload(ControlKind.DEMAND)
        this[key] = JsonArray((Json.parseToJsonElement(this[key]!!) as JsonArray) + F.request().toPayloadEntry().fields).toString()
    } }
    @Test fun C02() = removalCandidate("C02") { F.patch(it, "sibling") { null } }
    @Test fun Q14() = removalCandidate("Q14") { F.patch(it, "sibling") { null } }
    private fun invalidEligibility(id: String, p: DemandAuthPlan, runtime: DemandAuthRuntime, source: Preferences) {
        F.schema(source)
        assertNull(p.preparationFailure)
        assertNotNull(F.eligible(id), F.transition.eligibility(p, runtime, F.read(source)))
    }
    @Test fun W_queryBinding() {
        val d = F.decision(q = F.query.copy(binding = 4)); val g = F.guard(); val r = F.request()
        val p = F.plan(d, g, r)
        invalidEligibility("W.queryBinding", p, F.runtime(registrations = listOf(d.registration)), F.raw(g, r))
    }
    @Test fun W_queryOrigin() {
        val d = F.decision(q = F.query.copy(order = EventOrderV1(LifetimeId("old"), 21)), origin = LifetimeId("old"))
        val g = F.guard(); val r = F.request(); val p = F.plan(d, g, r)
        invalidEligibility("W.queryOrigin", p, F.runtime(registrations = listOf(d.registration)), F.raw(g, r))
    }
    @Test fun W_queryOwner() {
        val who = F.identity.copy(ownerUid = "B")
        val q = F.query.copy(fence = F.fence.copy(ownerUid = "B"), boundIdentity = who)
        val d = F.decision(q = q, answered = who, after = q.fence)
        val g = F.guard(); val r = F.request(); val p = F.plan(d, g, r)
        val source = F.raw(g, r).toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "B" }
        invalidEligibility("W.queryOwner", p, F.runtime(live = who, registrations = listOf(d.registration)), source)
    }
    @Test fun W_zeroRemoveRoute() {
        val g = F.guard(); val r = F.request(); val p = F.plan(settle = true, guard = g, retry = r)
        invalidEligibility("W.zeroRemoveRoute", p, F.runtime(), F.raw(g, r))
    }
    @Test fun W_answerAuth() {
        val g = F.guard(auth = null); val r = F.request(); val p = F.plan(guard = g, retry = r)
        invalidEligibility("W.answerAuth", p, F.runtime(), F.raw(g, r))
    }
    @Test fun W_answerChange() {
        val g = F.guard(F.auth.copy(authStopped = false)); val r = F.request(); val p = F.plan(guard = g, retry = r)
        invalidEligibility("W.answerChange", p, F.runtime(), F.raw(g, r))
    }
    @Test fun W_callerEvent() {
        val g = F.guard(wait = 60000)
        val orders = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("c", LifecycleCallerOrigin.CALLER, F.binding, orders.issue(1)!!, RefreshIntent.FORCE_PREMIUM, F.now)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(caller), orders, "g", "r")
        invalidEligibility("W.callerEvent", p, F.runtime(), F.raw(g))
    }
    @Test fun W_callerPin() {
        val g = F.guard(wait = 60000); val orders = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("c", LifecycleCallerOrigin.CALLER, F.binding.copy(startEventId = "other"), orders.issue(1)!!, RefreshIntent.FORCE_PREMIUM, F.now)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(caller), orders, "g", "r")
        invalidEligibility("W.callerPin", p, F.runtime(caller = caller), F.raw(g))
    }
    @Test fun W_recoveryEvent() {
        val g = F.guard(); val rec = LifecycleRecovery("rec", F.identity, F.life, 11, 22, 1)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Recovery(rec), LifecycleOrderSource(F.life, 22), "g", "r")
        invalidEligibility("W.recoveryEvent", p, F.runtime(), F.raw(g))
    }
    @Test fun W_recoveryOrigin() {
        val g = F.guard(); val rec = LifecycleRecovery("rec", F.identity, LifetimeId("old"), 11, 22, 1)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Recovery(rec), LifecycleOrderSource(F.life, 22), "g", "r")
        invalidEligibility("W.recoveryOrigin", p, F.runtime(recovery = rec), F.raw(g))
    }
    @Test fun W_closureCurrent() {
        val g = F.guard(F.auth.copy(binding = 2)); val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, emptySet(), emptySet(), 5)
        val p = DemandAuthPlan.end(g, emptyList(), F.binding, closed, F.binding, LifecycleOrderSource(F.life, 21))
        invalidEligibility("W.closureCurrent", p, F.runtime(closure = closed.copy(generation = 6)), F.raw(g))
    }
    @Test fun W_oldScope() {
        val g = F.guard(); val closed = LifecycleBindingClosure(F.auth, true, emptySet(), emptySet(), 5)
        val p = DemandAuthPlan.end(g, emptyList(), F.binding, closed, null, LifecycleOrderSource(F.life, 21))
        invalidEligibility("W.oldScope", p, F.runtime(closure = closed), F.raw(g))
    }
    @Test fun W_endRequests() {
        val g = F.guard(F.auth.copy(binding = 2)); val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, emptySet(), emptySet(), 5)
        val p = DemandAuthPlan.end(g, emptyList(), F.binding, closed, F.binding, LifecycleOrderSource(F.life, 21))
        invalidEligibility("W.endRequests", p, F.runtime(closure = closed), F.raw(g, F.request(binding = 2)))
    }
    private fun currentNo(id: String, binding: LifecycleBinding = F.binding,
        runtime: DemandAuthRuntime = F.runtime(binding = binding), raw: Preferences = F.raw()) {
        F.onlyFalse(raw[DataStoreAccessEpochStore.OWNER_UID] == binding.executor.ownerUid,
            runtime.binding == binding, binding.executor.originLifetimeId.value.isNotEmpty(),
            binding.startedOrder >= 0, binding.executor.binding >= 0)
        assertFalse(F.eligible(id), F.transition.current(binding, runtime, raw))
    }
    @Test fun W_owner() = currentNo("W.owner", raw = F.raw().toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "B" })
    @Test fun W_binding() = currentNo("W.binding", runtime = F.runtime(binding = F.binding.copy(startedOrder = 2)))
    @Test fun W_origin() = currentNo("W.origin", binding = F.binding.copy(executor = F.binding.executor.copy(originLifetimeId = LifetimeId(""))))
    @Test fun W_start() = currentNo("W.start", binding = F.binding.copy(startedOrder = -1))
    @Test fun W_bindingDomain() = currentNo("W.bindingDomain", binding = F.binding.copy(executor = F.binding.executor.copy(binding = -1)))
    @Test fun W_callerIdentity() {
        val g = F.guard(wait = 60000); val orders = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("c", LifecycleCallerOrigin.CALLER, F.binding, orders.issue(1)!!, RefreshIntent.FORCE_PREMIUM, F.now)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(caller), orders, "g", "r")
        invalidEligibility("W.callerIdentity", p, F.runtime(caller = caller, live = F.identity.copy(authGeneration = 3)), F.raw(g))
    }
    @Test fun W_closureGeneration() {
        val g = F.guard(F.auth.copy(binding = 2)); val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, emptySet(), emptySet(), 5)
        val p = DemandAuthPlan.end(g, emptyList(), F.binding, closed, F.binding, LifecycleOrderSource(F.life, 21))
        invalidEligibility("W.closureGeneration", p, F.runtime(closure = closed, generation = 6), F.raw(g))
    }

    @Test fun W_orderLink() {
        val request = demand(F.request(order = 23))!!
        val grant = LifecycleOrderGrant(F.life, 21, 1, 21, 22)
        assertEquals(request.raisedAt.origin, grant.origin)
        assertFalse(F.eligible("W.orderLink"), DemandAuthBoundary.orderValueMatches(request, grant))
    }
    @Test fun W_guardUnique() {
        val raw = F.raw(F.guard(auth = null))
        F.schema(raw)
        assertFalse(F.eligible("W.guardUnique"), F.transition.guardCreationAvailable(F.read(raw)))
    }
    @Test fun W_journal() {
        val g = F.guard(auth = null)
        val raw = F.raw(g).toMutablePreferences().apply { this[DataStoreAccessEpochStore.PURGE_JOURNAL] = "unknown-format" }
        F.schema(raw)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life), "g-new", "r-new")
        val c = F.command(p)
        val actual = ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input,
            F.read(raw), F.context(), false, false)
        assertFalse(F.eligible("W.journal"), actual is RecordTransactionDecision.Confirm)
        assertEquals(RecoveryReason.JournalMigrationRequired,
            ((actual.value as ControlRecordStore.Outcome.Negative).result as ControlStoreResult.RecoveryRequired).reason)
    }

}
