package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, forty-first file — the caller order's afterStart writer connection (DT:120 → DemandAuthBoundary.order
 * DF:111 Q16a-type relation), which STEP3ZK r2 판정 ③ showed is independently falsifiable: with a resumed AUTH at state 0,
 * a caller grant (previous 0, bindingStart 0, after 0, value 1) is issued-consistent and after the AUTH state but not after
 * the binding start (startedOrder 1); the new AUTH (state 1) still advances, so DT:145 authChange does not mask it.
 * Same method as C38 (twin with empty TV vectors reaching Confirm → only `caller.orderAfterStart` false → decide ≠ Confirm).
 */
class DemandAuthBacklogContract41Test {
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
    private val g get() = F.guard(auth = F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0))
    private fun caller(grant: LifecycleOrderGrant) = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding, grant, RefreshIntent.FORCE_PREMIUM, F.now)
    private fun plan(c: LifecycleCaller) = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(c), LifecycleOrderSource(F.life, 21), "g-new", "r-new")

    @Test fun Q16a_callerAfterStart() {
        assertEquals("fixture: the binding starts at order 1", 1L, F.binding.startedOrder)
        val ok = caller(LifecycleOrderGrant(F.life, 1, 0, 0, 2))
        vector(plan(ok), F.raw(g), F.runtime(caller = ok), null)
        assertTrue("positive twin: a caller order after the binding start reaches Confirm", decide(plan(ok), F.raw(g), F.runtime(caller = ok)) is RecordTransactionDecision.Confirm)
        val bad = caller(LifecycleOrderGrant(F.life, 0, 0, 0, 1))
        vector(plan(bad), F.raw(g), F.runtime(caller = bad), "caller.orderAfterStart")
        assertFalse(F.eligible("Z.wr.Q16a.caller"), decide(plan(bad), F.raw(g), F.runtime(caller = bad)) is RecordTransactionDecision.Confirm)
    }
}
