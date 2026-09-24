package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, forty-ninth file — writer-only gates left OPEN by the re-judgment r1 (group ①), method of C37/C38:
 *  - current() (DemandAuthTransition.kt:52–57, W.owner · W.binding · W.origin · W.start · W.bindingDomain): direct boundary
 *    with a truth vector written here in production order (twin = the normal binding/runtime/raw, literal result true), and
 *    the writer connection through decide (conflict).
 *  - eligibility-only gates (DT:73–75 query owner/binding/origin, DT:88/91 UPDATE_AUTH auth/change, DT:94 settle
 *    zero-remove route, DT:114–116 caller event/pin/identity, DT:123–124 recovery event/origin, DT:134–136 END closure
 *    current/generation/old scope, DT:143 END owed requests): the TV writer gates · eligibility · common premises name
 *    exactly the target item; the twin is the same writer with the normal input reaching Confirm.
 * The combined false set is asserted literally, so a fixture that makes a second condition false fails before the role.
 * Records (not roles): END with a guard without AUTH also fails the descriptor target order. Not here: END closure null
 * (DT:132) and the AUTH stop grant null (DT:147) — the END factory takes a non-null closure and a prepared plan with an
 * AUTH stop always has its grant (DemandAuthPlan.kt:173, preparation failure otherwise). current() domain gates at a
 * writer: start < 0 is a role; origin empty and binding < 0 are records (see W_*_writer_record).
 */
class DemandAuthBacklogContract49Test {
    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(runtime), false, false)
    }
    private class Case(val plan: DemandAuthPlan, val raw: Preferences, val rt: DemandAuthRuntime)
    private fun falses(k: Case): Set<String> {
        F.schema(k.raw)
        assertNull("fixture plan must be prepared", k.plan.preparationFailure)
        return V.falses(V.decideGates(k.plan, F.context(k.rt), k.raw)) + V.falses(V.eligibility(k.plan, k.rt, k.raw)) +
            V.falses(V.commonPremises(k.plan, k.raw, "command"))
    }
    private fun check(id: String, only: String, twin: Case, bad: Case) {
        assertEquals("truth vector: twin", emptySet<String>(), falses(twin))
        assertTrue("positive twin: the input differing only in the target reaches Confirm", decide(twin.plan, twin.raw, twin.rt) is RecordTransactionDecision.Confirm)
        assertEquals("truth vector: only the target differs", setOf(only), falses(bad))
        assertFalse(F.eligible(id), decide(bad.plan, bad.raw, bad.rt) is RecordTransactionDecision.Confirm)
    }
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun owner(raw: Preferences, o: String): Preferences = raw.toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = o }.toPreferences()
    private fun reg(q: StartedQueryV1) = listOf(LifecycleQueryRegistration("query-21", q))

    // current() — direct boundary (DT:52–57)
    private fun cur(b: LifecycleBinding, rt: DemandAuthRuntime, raw: Preferences) = listOf(
        "owner" to (raw[DataStoreAccessEpochStore.OWNER_UID] == b.executor.ownerUid), "binding" to (rt.binding == b),
        "origin" to b.executor.originLifetimeId.value.isNotEmpty(), "start" to (b.startedOrder >= 0), "bindingDomain" to (b.executor.binding >= 0))
    private fun direct(id: String, only: String, b: LifecycleBinding, rt: DemandAuthRuntime, raw: Preferences) {
        val raw0 = F.raw(F.guard())
        assertEquals("truth vector: normal current()", emptySet<String>(), V.falses(cur(F.binding, F.runtime(), raw0)))
        assertTrue("positive twin: the normal binding is current", F.transition.current(F.binding, F.runtime(), raw0))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(cur(b, rt, raw)))
        assertFalse(F.eligible(id), F.transition.current(b, rt, raw))
    }
    private fun bx(f: (SettlementExecutor) -> SettlementExecutor) = F.binding.copy(executor = f(F.binding.executor))
    @Test fun W_owner_direct() = direct("Z.cur.owner", "owner", F.binding, F.runtime(), owner(F.raw(F.guard()), "B"))
    @Test fun W_binding_direct() = direct("Z.cur.binding", "binding", F.binding, F.runtime(binding = F.binding.copy(startEventId = "other")), F.raw(F.guard()))
    @Test fun W_origin_direct() = bx { it.copy(originLifetimeId = LifetimeId("")) }.let { b -> direct("Z.cur.origin", "origin", b, F.runtime(binding = b), F.raw(F.guard())) }
    @Test fun W_start_direct() = F.binding.copy(startedOrder = -1).let { b -> direct("Z.cur.start", "start", b, F.runtime(binding = b), F.raw(F.guard())) }
    @Test fun W_bindingDomain_direct() = bx { it.copy(binding = -1) }.let { b -> direct("Z.cur.bindingDomain", "bindingDomain", b, F.runtime(binding = b), F.raw(F.guard())) }

    // current() — writer connection (UPDATE_AUTH Answer; decide returns conflict)
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    private fun answer(raw: Preferences = F.raw(F.guard()), rt: DemandAuthRuntime = F.runtime()) = Case(F.plan(pending, F.guard(), null), raw, rt)
    // raw owner at a writer with a decision also changes the raw fence the decision's after fence is compared with
    // (after.fence) — shown by the first draft's baseline — so the owner cell uses the Caller writer, which has no decision.
    @Test fun W_owner_writer() = caller().let { k -> check("Z.cur.owner.caller", "dt.rawOwner", k, Case(k.plan, owner(k.raw, "B"), k.rt)) }
    /** current() domain gates at a writer (Caller; plan = runtime = fact binding b). start < 0 is isolable (role);
     *  an empty origin and binding < 0 also break the AUTH scope/order relations built on the same values — records with the
     *  false sets observed by the probe run of this draft (not roles). */
    private fun callerAt(b: LifecycleBinding): Case = callerFact(binding = b).let { f -> F.guard().let { g ->
        Case(DemandAuthPlan.auth(g, null, b, LifecycleAuthEvent.Caller(f), orders, "g-new", "r-new"), F.raw(g), F.runtime(binding = b, caller = f)) } }
    @Test fun W_start_writer() = check("Z.cur.start.caller", "dt.start", caller(), callerAt(F.binding.copy(startedOrder = -1)))
    private fun domainRecord(expected: Set<String>, b: LifecycleBinding) {
        val k = callerAt(b)
        assertEquals("record: current() domain gate at the Caller writer", expected, falses(k))
        assertFalse("record: the writer refuses it", decide(k.plan, k.raw, k.rt) is RecordTransactionDecision.Confirm)
    }
    @Test fun W_origin_writer_record() = domainRecord(setOf("dt.origin", "caller.scopeOrigin", "caller.orderOrigin"),
        F.binding.copy(executor = F.binding.executor.copy(originLifetimeId = LifetimeId(""))))
    @Test fun W_bindingDomain_writer_record() = domainRecord(setOf("dt.bindingDomain", "caller.scopeBinding"),
        F.binding.copy(executor = F.binding.executor.copy(binding = -1)))
    @Test fun W_binding_writer() = check("Z.cur.binding.answer", "dt.runtimeBinding", answer(), answer(rt = F.runtime(binding = F.binding.copy(startEventId = "other"))))

    // query owner/binding/origin (DT:73–75), Answer writer
    private fun qAnswer(q: StartedQueryV1, d: AcceptedQueryDecision = F.decision(q = q, outcome = EntitlementsOutcome.Pending(false, 30))) =
        Case(F.plan(d, F.guard(), null), F.raw(F.guard()), F.runtime(registrations = reg(q)))
    @Test fun Q_binding_answer() = check("Z.wr.queryBinding.answer", "q.binding", answer(), qAnswer(F.query.copy(binding = 4)))
    @Test fun Q_origin_answer() = F.query.copy(order = EventOrderV1(LifetimeId("other"), 21)).let { q ->
        check("Z.wr.queryOrigin.answer", "q.origin", answer(), qAnswer(q, F.decision(q = q, origin = LifetimeId("other"), outcome = EntitlementsOutcome.Pending(false, 30)))) }

    // query owner (DT:73): executor/raw/AUTH owner B, the query fence (and answer/live/bound identities) A; the after fence
    // is the raw fence B, so the before≠after namespace proof is supplied from the same raw record
    @Test fun Q_owner_answer() {
        val bB = F.binding.copy(executor = F.binding.executor.copy(ownerUid = "B"), identity = IdentityV1("B", 2))
        val gB = F.guard(F.auth.copy(ownerUid = "B")); val rawB = owner(F.raw(gB), "B")
        val fenceB = F.fence.copy(ownerUid = "B")
        val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 30), after = fenceB, namespace = ConfirmedControlSnapshot(F.read(rawB)))
        check("Z.wr.queryOwner.answer", "q.owner", answer(),
            Case(DemandAuthPlan.auth(gB, null, bB, LifecycleAuthEvent.Answer(d), orders, "g-new", "r-new"), rawB, F.runtime(binding = bB)))
    }

    // UPDATE_AUTH auth/change (DT:88·91)
    @Test fun U_answerAuth() = F.guard(auth = null).let { g -> check("Z.wr.update.auth", "update.auth", answer(), Case(F.plan(pending, g, null), F.raw(g), F.runtime())) }
    @Test fun U_answerChange() = F.guard(F.auth.copy(authStopped = false)).let { g ->
        check("Z.wr.update.change", "update.change", answer(), Case(F.plan(pending, g, null), F.raw(g), F.runtime())) }

    // settle zero-remove route (DT:94): no REQUEST removed while AUTH changes
    private fun settle0(g: ControlNode) = Case(DemandAuthPlan.settle(emptyList(), g, null, F.binding, pending, orders, "g-new", "r-new"), F.raw(g), F.runtime())
    @Test fun S_zeroRemoveRoute() = check("Z.wr.settle.zeroRemove", "settle.zeroRemoveKeepsAuth", settle0(F.guard(F.auth.copy(authStopped = false))), settle0(F.guard()))

    // caller event/pin/identity (DT:114–116)
    private fun callerFact(id: String = "caller", binding: LifecycleBinding = F.binding) =
        LifecycleCaller(id, LifecycleCallerOrigin.CALLER, binding, LifecycleOrderGrant(F.life, 20, 1, 0, 21), RefreshIntent.FORCE_PREMIUM, F.now)
    private fun caller(f: LifecycleCaller = callerFact(), rt: DemandAuthRuntime = F.runtime(caller = f)) = F.guard().let { g ->
        Case(DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(f), orders, "g-new", "r-new"), F.raw(g), rt) }
    @Test fun U_callerEvent() = check("Z.wr.caller.event", "caller.event", caller(), caller(rt = F.runtime(caller = callerFact(id = "caller-2"))))
    @Test fun U_callerPin() = callerFact(binding = F.binding.copy(startEventId = "other")).let { f -> check("Z.wr.caller.pin", "caller.pin", caller(), caller(f)) }
    @Test fun U_callerIdentity() = check("Z.wr.caller.identity", "caller.identity", caller(), caller(rt = F.runtime(caller = callerFact(), live = IdentityV1("A", 7))))

    // recovery event/origin (DT:123–124)
    private fun rec(id: String = "recovery", origin: LifetimeId = F.life) = LifecycleRecovery(id, F.identity, origin, 11, 21, 1)
    private fun recovery(f: LifecycleRecovery = rec(), rt: DemandAuthRuntime = F.runtime(recovery = f)) = F.guard().let { g ->
        Case(DemandAuthPlan.auth(g, null, rt.binding, LifecycleAuthEvent.Recovery(f), orders, "g-new", "r-new"), F.raw(g), rt) }
    @Test fun U_recoveryEvent() = check("Z.wr.recovery.event", "recovery.event", recovery(), recovery(rt = F.runtime(recovery = rec(id = "recovery-2"))))
    @Test fun U_recoveryOrigin() = rec(origin = LifetimeId("other")).let { f -> check("Z.wr.recovery.origin", "recovery.origin", recovery(), recovery(f)) }

    // END closure current/generation/old scope/owed (DT:134–136·143)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private fun closure(scope: AuthSnapshotV1 = oldAuth) = LifecycleBindingClosure(scope, true, setOf("w"), setOf("w"), 5)
    private fun end(auth: AuthSnapshotV1 = oldAuth, c: LifecycleBindingClosure = closure(auth), rt: DemandAuthRuntime = F.runtime(closure = c),
        extra: List<ControlNode> = emptyList()) = F.guard(auth = auth).let { g ->
        Case(DemandAuthPlan.end(g, emptyList(), F.binding, c, null, orders), F.raw(g, *extra.toTypedArray()), rt) }
    @Test fun N_closureCurrent() = check("Z.wr.end.closureCurrent", "end.closureCurrent", end(),
        end(rt = F.runtime(closure = LifecycleBindingClosure(oldAuth, true, setOf("w", "v"), setOf("w", "v"), 5))))
    @Test fun N_closureGeneration() = check("Z.wr.end.closureGeneration", "end.closureGeneration", end(), end(rt = F.runtime(closure = closure(), generation = 6)))
    @Test fun N_oldScope() = oldAuth.copy(binding = 3).let { a -> check("Z.wr.end.oldScope", "end.notCurrentScope", end(), end(auth = a)) }
    /** Record, not a role: through the END factory a guard without AUTH also fails the descriptor target order (the
     *  first draft's baseline showed {end.auth, descriptor.order}); the false set is asserted literally. */
    @Test fun N_authMissing_record() = F.guard(auth = null).let { g ->
        val k = Case(DemandAuthPlan.end(g, emptyList(), F.binding, closure(), null, orders), F.raw(g), F.runtime(closure = closure()))
        assertEquals("record: END with a guard without AUTH", setOf("end.auth", "descriptor.order"), falses(k))
        assertFalse("record: the END writer refuses it", decide(k.plan, k.raw, k.rt) is RecordTransactionDecision.Confirm) }
    @Test fun N_owedMissing() = check("Z.wr.end.owed", "end.owed", end(), end(extra = listOf(F.request(binding = 2))))
}
