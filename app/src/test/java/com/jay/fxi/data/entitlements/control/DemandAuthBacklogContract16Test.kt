package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixteenth file: positive preparation and eligibility contracts that narrowing mutants break.
 *  - S.consumes.krxSettle — a KRX-entitlement answer settles a request whose intent is not FORCE_PREMIUM (DF:137).
 *  - U.planAnswer.stopGrantBounds — the AUTH stop grant is issued after max(authStopAppliedOrder, query order).
 *  - H.allocator.startMax — issuance starts after binding.startedOrder when that is the largest lower bound.
 * Expected grants are fixed constants written here; the production allocator is not used to compute them.
 */
class DemandAuthBacklogContract16Test {

    // S.consumes — DemandAuthTransition.kt:84, DemandAuthFacts.kt:129–140. A SETTLE removes a FORCE_ENTITLEMENTS
    // request after a KrxEntitlementRequired answer; the rest of the settle is the C1 W_consumes positive twin.

    @Test fun S_consumes_krxSettle() {
        val g = F.guard(); val r = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS); val source = F.raw(g, r)
        val d = F.decision(outcome = EntitlementsOutcome.KrxEntitlementRequired)
        val p = F.plan(d, g, retry = null, settle = true, removes = listOf(r))
        F.schema(source)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val request = demand(r)!!
        assertEquals("fixture: KRX entitlement answer", EntitlementsOutcome.KrxEntitlementRequired, d.outcome)
        assertEquals("fixture: the removed request is not FORCE_PREMIUM", RefreshIntent.FORCE_ENTITLEMENTS, request.intent)
        assertEquals("fixture: reapproval is not blocked", LifecycleReapproval.NOT_REQUIRED, d.reapproval)
        assertEquals("fixture: eligibility truth vector", emptySet<String>(), V.falses(V.eligibility(p, F.runtime(), source)))
        assertNull(F.atomic("S.consumes.krxSettle"), F.transition.eligibility(p, F.runtime(), F.read(source)))
    }

    // U.planAnswer — DemandAuthPlan.kt:171–177. The two lower bounds differ: authStopAppliedOrder 30, query order 21.
    // The source starts at 5 and the binding starts at 1, so the grant is (life, 5, 1, 30, 31).

    @Test fun U_planAnswer_stopGrantBounds() {
        val before = F.auth.copy(authStopped = false, authStateOrder = 20, authStopAppliedOrder = 30)
        val g = F.guard(auth = before)
        val d = F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION))
        val p = F.plan(d, g, null, orders = LifecycleOrderSource(F.life, 5))
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: query order", 21L, d.query.order.value)
        assertEquals("fixture: binding start", 1L, F.binding.startedOrder)
        assertEquals("fixture: existing stop applied order", 30L, before.authStopAppliedOrder)
        assertTrue("fixture: the answer is newer than the AUTH state", d.query.order.value > before.authStateOrder)
        assertEquals(F.atomic("U.planAnswer.stopGrantBounds"), LifecycleOrderGrant(F.life, 5, 1, 30, 31), p.authStopGrant)
    }

    // H.allocator — DemandAuthPlan.kt:120–123, DemandAuthFacts.kt:15–20. A rebind whose binding starts at 50, above
    // the source's last value 21 and the request's same-origin raisedAt 4, so the grant is (life, 21, 50, 4, 51).

    @Test fun H_allocator_startMax() {
        val binding = F.binding.copy(startedOrder = 50)
        val r = F.request(binding = 2)
        val p = DemandAuthPlan.rebind(listOf(r), binding, LifecycleOrderSource(F.life, 21))
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val old = demand(r)!!
        assertEquals("fixture: same-origin raisedAt", EventOrderV1(F.life, 4), old.raisedAt)
        assertEquals("fixture: the binding starts above the source and the request", 50L, binding.startedOrder)
        assertEquals(F.atomic("H.allocator.startMax"), LifecycleOrderGrant(F.life, 21, 50, 4, 51), p.grants["r"])
    }
}
