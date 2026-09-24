package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirty-eighth file — writer connections (eligibility layer) part 2, for the C36 boundaries evaluated in
 * the event-specific writers:
 *  - initialization (DT:111 UPDATE_AUTH Initialize · DT:138 END replacement), A12a·A12identity·A12b·A08·A12owner (replacement: A12b·A08)
 *  - caller (DT:117 origin · DT:119 scope · DT:120 order), A04 · A01a–d · Q12a/Q12b-type grant relation
 *  - recovery (DT:126 scope · DT:127 recovery), A01a–d · A05a · A05c–h · A06
 *  - closure (DT:133 END), A07a–c
 *  - order origin of an issued REQUEST order (DT:107) at REBIND_REQUESTS and at the SETTLE_QUERY retry
 * Same method as C37 (twin with empty TV vectors reaching Confirm → exactly the target TV item false → decide ≠ Confirm).
 * Not here because another check necessarily rejects the same input (masked, recorded in the request): A12c (a runtime
 * binding different from the plan's also fails the decide gates lc.contextBinding·dt.runtimeBinding), A05b (a binding
 * without a bound identity also fails the earlier recovery scope generation, DT:126), A12d (an existing
 * AUTH also fails DT:145 authChange), the caller order's afterLower (the new state then does not advance — DT:145),
 * and the builder-guaranteed order relations issued/afterStart/afterLower of REQUEST and stop grants.
 * The caller order's afterStart is independently falsifiable; its writer connection remains OPEN.
 */
class DemandAuthBacklogContract38Test {
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val other = LifetimeId("other")
    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(runtime), false, false)
    }
    private fun vector(plan: DemandAuthPlan, raw: Preferences, rt: DemandAuthRuntime, eligibilityFalse: String?) {
        F.schema(raw)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("truth vector: writer gates", emptySet<String>(), V.falses(V.decideGates(plan, F.context(rt), raw)))
        assertEquals("truth vector: eligibility", setOfNotNull(eligibilityFalse), V.falses(V.eligibility(plan, rt, raw)))
        assertEquals("truth vector: common premises", emptySet<String>(), V.falses(V.commonPremises(plan, raw, "command")))
    }
    private class Case(val plan: DemandAuthPlan, val raw: Preferences, val rt: DemandAuthRuntime)
    private fun check(id: String, only: String, twin: Case, bad: Case) {
        vector(twin.plan, twin.raw, twin.rt, null)
        assertTrue("positive twin: the input differing only in the target reaches Confirm", decide(twin.plan, twin.raw, twin.rt) is RecordTransactionDecision.Confirm)
        vector(bad.plan, bad.raw, bad.rt, only)
        assertFalse(F.eligible(id), decide(bad.plan, bad.raw, bad.rt) is RecordTransactionDecision.Confirm)
    }
    private val orders get() = LifecycleOrderSource(F.life, 21)

    // initialization at UPDATE_AUTH Initialize — plan and runtime share the binding unless the target is the runtime binding.
    private fun init(b: LifecycleBinding = F.binding, rt: DemandAuthRuntime = F.runtime(binding = b)) =
        Case(DemandAuthPlan.auth(null, null, b, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new"), F.raw(), rt)
    @Test fun A12a_init() = check("Z.wr.A12a.init", "init.startEvent", init(), init(b = F.binding.copy(startEventId = null)))
    @Test fun A12identity_init() = check("Z.wr.A12identity.init", "init.identity", init(),
        init(b = F.binding.copy(identity = null), rt = F.runtime(binding = F.binding.copy(identity = null), live = null)))
    @Test fun A12b_init() = check("Z.wr.A12b.init", "init.live", init(), init(rt = F.runtime(live = IdentityV1("A", 7))))
    @Test fun A08_init() = check("Z.wr.A08.init", "init.noAcceptedEvent", init(), init(b = F.binding.copy(acceptedAuthOrder = 10)))
    @Test fun A12owner_init() = check("Z.wr.A12owner.init", "init.owner", init(),
        init(b = F.binding.copy(identity = IdentityV1("B", 2)), rt = F.runtime(binding = F.binding.copy(identity = IdentityV1("B", 2)), live = IdentityV1("B", 2))))

    // initialization of the END replacement (DT:138)
    private fun closure(entriesClosed: Boolean = true, joined: Set<String> = setOf("w"), scope: AuthSnapshotV1 = oldAuth) =
        LifecycleBindingClosure(scope, entriesClosed, setOf("w"), joined, 5)
    private fun end(c: LifecycleBindingClosure = closure(), replacement: LifecycleBinding? = null, rt: DemandAuthRuntime = F.runtime(closure = c),
        binding: LifecycleBinding = F.binding) =
        F.guard(auth = oldAuth).let { g -> Case(DemandAuthPlan.end(g, emptyList(), binding, c, replacement, orders), F.raw(g), rt) }
    @Test fun A12b_replacement() = check("Z.wr.A12b.replacement", "end.replacement.live", end(replacement = F.binding),
        end(replacement = F.binding, rt = F.runtime(live = IdentityV1("A", 7), closure = closure())))
    @Test fun A08_replacement() = check("Z.wr.A08.replacement", "end.replacement.noAcceptedEvent", end(replacement = F.binding),
        F.binding.copy(acceptedAuthOrder = 10).let { b -> end(replacement = b, rt = F.runtime(binding = b, closure = closure()), binding = b) })

    // closure at END (DT:133)
    @Test fun A07a_end() = check("Z.wr.A07a.end", "end.entriesClosed", end(), closure(entriesClosed = false).let { end(it) })
    @Test fun A07b_end() = check("Z.wr.A07b.end", "end.joined", end(), closure(joined = emptySet()).let { end(it) })
    @Test fun A07c_end() = check("Z.wr.A07c.end", "end.scope", end(), closure(scope = oldAuth.copy(binding = 1)).let { end(it) })

    // caller (DT:117·119·120)
    private fun callerFact(origin: LifecycleCallerOrigin = LifecycleCallerOrigin.CALLER, grant: LifecycleOrderGrant = LifecycleOrderGrant(F.life, 20, 1, 0, 21)) =
        LifecycleCaller("caller", origin, F.binding, grant, RefreshIntent.FORCE_PREMIUM, F.now)
    private fun caller(f: LifecycleCaller = callerFact(), g: ControlNode = F.guard()) =
        Case(DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(f), orders, "g-new", "r-new"), F.raw(g), F.runtime(caller = f))
    @Test fun A04_caller() = check("Z.wr.A04.caller", "caller.origin", caller(), caller(callerFact(origin = LifecycleCallerOrigin.SCHEDULED)))
    @Test fun A01a_caller() = check("Z.wr.A01a.caller", "caller.scopeOwner", caller(), caller(g = F.guard(auth = F.auth.copy(ownerUid = "B"))))
    @Test fun A01b_caller() = check("Z.wr.A01b.caller", "caller.scopeGeneration", caller(), caller(g = F.guard(auth = F.auth.copy(authGeneration = 3))))
    @Test fun A01c_caller() = check("Z.wr.A01c.caller", "caller.scopeBinding", caller(), caller(g = F.guard(auth = F.auth.copy(binding = 4))))
    @Test fun A01d_caller() = check("Z.wr.A01d.caller", "caller.scopeOrigin", caller(), caller(g = F.guard(auth = F.auth.copy(originLifetimeId = LifetimeId("old")))))
    @Test fun Q12a_caller() = check("Z.wr.Q12a.caller", "caller.orderOrigin", caller(), caller(callerFact(grant = LifecycleOrderGrant(other, 20, 1, 0, 21))))
    @Test fun Q12b_caller() = check("Z.wr.Q12b.caller", "caller.orderIssued", caller(), caller(callerFact(grant = LifecycleOrderGrant(F.life, 20, 1, 0, 22))))

    // recovery (DT:126·127)
    private fun rec(identity: IdentityV1 = F.identity, fetch: Long = 11, recovered: Long = 21, episode: Long = 1) =
        LifecycleRecovery("recovery", identity, F.life, fetch, recovered, episode)
    private fun recovery(f: LifecycleRecovery = rec(), g: ControlNode = F.guard(), rt: DemandAuthRuntime = F.runtime(recovery = f)) =
        Case(DemandAuthPlan.auth(g, null, rt.binding, LifecycleAuthEvent.Recovery(f), orders, "g-new", "r-new"), F.raw(g), rt)
    @Test fun A01a_recovery() = check("Z.wr.A01a.recovery", "recovery.scopeOwner", recovery(), recovery(g = F.guard(auth = F.auth.copy(ownerUid = "B"))))
    @Test fun A01b_recovery() = check("Z.wr.A01b.recovery", "recovery.scopeGeneration", recovery(), recovery(g = F.guard(auth = F.auth.copy(authGeneration = 3))))
    @Test fun A01c_recovery() = check("Z.wr.A01c.recovery", "recovery.scopeBinding", recovery(), recovery(g = F.guard(auth = F.auth.copy(binding = 4))))
    @Test fun A01d_recovery() = check("Z.wr.A01d.recovery", "recovery.scopeOrigin", recovery(), recovery(g = F.guard(auth = F.auth.copy(originLifetimeId = LifetimeId("old")))))
    @Test fun A05a_recovery() = check("Z.wr.A05a.recovery", "recovery.stopped", recovery(), recovery(g = F.guard(auth = F.auth.copy(authStopped = false))))
    @Test fun A05c_recovery() = check("Z.wr.A05c.recovery", "recovery.uid", recovery(),
        rec(identity = F.identity.copy(ownerUid = "B")).let { f -> recovery(f, rt = F.runtime(live = f.identity, recovery = f)) })
    @Test fun A05d_recovery() = check("Z.wr.A05d.recovery", "recovery.generation", recovery(),
        rec(identity = F.identity.copy(authGeneration = 3)).let { f -> recovery(f, rt = F.runtime(live = f.identity, recovery = f)) })
    @Test fun A05e_recovery() = check("Z.wr.A05e.recovery", "recovery.live", recovery(),
        rec().let { f -> recovery(f, rt = F.runtime(live = F.identity.copy(authGeneration = 3), recovery = f)) })
    @Test fun A05f_recovery() = check("Z.wr.A05f.recovery", "recovery.fetchAfterStart", recovery(),
        rec().let { f -> recovery(f, rt = F.runtime(binding = F.binding.copy(startedOrder = 12), recovery = f)) })
    @Test fun A05g_recovery() = check("Z.wr.A05g.recovery", "recovery.fetchAfterState", recovery(), recovery(rec(fetch = 10)))
    @Test fun A05h_recovery() = check("Z.wr.A05h.recovery", "recovery.afterStopApplied", recovery(), recovery(rec(recovered = 20)))
    @Test fun A06_recovery() = check("Z.wr.A06.recovery", "recovery.episode", recovery(), rec().let { f -> recovery(f, rt = F.runtime(recovery = f, consumed = 1)) })

    // order origin of an issued REQUEST order (DT:107) — the order source's origin is not the executor's
    @Test fun Q12a_rebind() { val old = F.request(binding = 2, order = 50)
        check("Z.wr.Q12a.rebind", "request[r].orderOrigin", Case(DemandAuthPlan.rebind(listOf(old), F.binding, orders), F.raw(old), F.runtime()),
            Case(DemandAuthPlan.rebind(listOf(old), F.binding, LifecycleOrderSource(other, 21)), F.raw(old), F.runtime())) }
    @Test fun Q12a_settleRetry() { val g = F.guard(F.auth.copy(authStopped = false)); val r = F.request()
        val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        check("Z.wr.Q12a.settleRetry", "request[r-new].orderOrigin",
            Case(DemandAuthPlan.settle(listOf(r), g, null, F.binding, d, orders, "g-new", "r-new"), F.raw(g, r), F.runtime()),
            Case(DemandAuthPlan.settle(listOf(r), g, null, F.binding, d, LifecycleOrderSource(other, 21), "g-new", "r-new"), F.raw(g, r), F.runtime())) }
}
