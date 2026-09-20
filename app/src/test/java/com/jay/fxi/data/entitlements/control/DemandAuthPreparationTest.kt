package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/** Preparation boundaries are checked before descriptor/schema validation can mask a rejection. */
class DemandAuthPreparationTest {
    private fun denied(id: String, plan: DemandAuthPlan) {
        assertNotNull(F.eligible(id), plan.preparationFailure)
    }
    @Test fun P_guardKind() {
        val request = F.request()
        assertNotNull(demand(request))
        denied("P.guardKind", F.plan(guard = request, settle = true))
    }
    @Test fun P_authGuardKind() {
        denied("P.authGuardKind", DemandAuthPlan.auth(F.request(), null, F.binding, LifecycleAuthEvent.Initialize,
            LifecycleOrderSource(F.life), "g", "retry"))
    }
    @Test fun P_endGuardKind() {
        val closure = LifecycleBindingClosure(F.auth, true, emptySet(), emptySet(), 5)
        denied("P.endGuardKind", DemandAuthPlan.end(F.request(), emptyList(), F.binding, closure, null, LifecycleOrderSource(F.life)))
    }
    @Test fun P_requestKind() {
        val g = F.guard()
        denied("P.requestKind", DemandAuthPlan.settle(listOf(g), null, null, F.binding, F.decision(), LifecycleOrderSource(F.life), "new", "retry"))
    }
    @Test fun P_rebindKind() {
        denied("P.rebindKind", DemandAuthPlan.rebind(listOf(F.guard()), F.binding, LifecycleOrderSource(F.life)))
    }
    @Test fun P_callerAuth() {
        val order = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("c", LifecycleCallerOrigin.CALLER, F.binding, order.issue(1)!!, RefreshIntent.FORCE_PREMIUM, F.now)
        denied("P.callerAuth", DemandAuthPlan.auth(F.guard(auth = null), null, F.binding, LifecycleAuthEvent.Caller(caller), order, "g", "retry"))
    }
    @Test fun P_recoveryAuth() {
        val rec = LifecycleRecovery("r", F.identity, F.life, 11, 22, 1)
        denied("P.recoveryAuth", DemandAuthPlan.auth(F.guard(auth = null), null, F.binding, LifecycleAuthEvent.Recovery(rec), LifecycleOrderSource(F.life), "g", "retry"))
    }
    @Test fun P_callerNow() {
        val order = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("c", LifecycleCallerOrigin.CALLER, F.binding, order.issue(1)!!, RefreshIntent.FORCE_PREMIUM, F.now.copy(bootId = ""))
        denied("P.callerNow", DemandAuthPlan.auth(F.guard(wait = 30000), null, F.binding, LifecycleAuthEvent.Caller(caller), order, "g", "retry"))
    }
    @Test fun P_retryKind() {
        val retry = F.guard(auth = null, id = "retry")
        assertNotNull(guard(retry))
        denied("P.retryKind", F.plan(retry = retry))
    }
    @Test fun P_successorMustBeNew() {
        val r = F.request(); val retry = F.request(id = "existing")
        denied("P.successorMustBeNew", F.plan(F.decision(followUp = RefreshIntent.FORCE_PREMIUM), retry = retry, settle = true, removes = listOf(r)))
    }
    @Test fun P_retryOwner() {
        val r = F.request(owner = "B"); val v = demand(r)!!
        assertEquals(3L, v.binding); assertEquals(F.life, v.raisedAt.origin)
        denied("P.retryOwner", F.plan(retry = r))
    }
    @Test fun P_retryBinding() {
        val r = F.request(binding = 4); val v = demand(r)!!
        assertEquals("A", v.ownerUid); assertEquals(F.life, v.raisedAt.origin)
        denied("P.retryBinding", F.plan(retry = r))
    }
    @Test fun P_retryOrigin() {
        val r = F.request(origin = LifetimeId("old")); val v = demand(r)!!
        assertEquals("A", v.ownerUid); assertEquals(3L, v.binding)
        denied("P.retryOrigin", F.plan(retry = r))
    }
    @Test fun P_negativeDelay() = denied("P.negativeDelay", F.plan(F.decision(minDelay = -1)))
    @Test fun P_mergeBoot() = denied("P.mergeBoot", F.plan(F.decision(merge = F.now.copy(bootId = ""), minDelay = 1000)))
    @Test fun P_mergeElapsed() = denied("P.mergeElapsed", F.plan(F.decision(merge = F.now.copy(elapsedMillis = -1), minDelay = 1000)))
    @Test fun P_captureBoot() = denied("P.captureBoot", F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30), capture = F.now.copy(bootId = ""))))
    @Test fun P_captureElapsed() = denied("P.captureElapsed", F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30), capture = F.now.copy(elapsedMillis = -1))))
    @Test fun P_replacementPin() {
        val g = F.guard(F.auth.copy(binding = 2)); val closed = LifecycleBindingClosure(guard(g)!!.auth!!, true, emptySet(), emptySet(), 5)
        denied("P.replacementPin", DemandAuthPlan.end(g, emptyList(), F.binding, closed, F.binding.copy(startEventId = "other"), LifecycleOrderSource(F.life, 21)))
    }
    @Test fun retryStrengtheningAndFloorControls() {
        val p = F.plan(retry = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS))
        assertNull(p.preparationFailure)
        assertEquals(RefreshIntent.FORCE_PREMIUM, demand(p.retryAfter)!!.intent)
        assertEquals(22L, demand(p.retryAfter)!!.raisedAt.value)
        assertEquals(30000L, guard(p.guardAfter)!!.floor!!.waitMillis)
    }
}
