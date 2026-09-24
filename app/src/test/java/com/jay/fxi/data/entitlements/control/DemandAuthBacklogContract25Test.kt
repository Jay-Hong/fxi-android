package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, twenty-fifth file (design scope under review): preparation outputs that the issuance and re-bind
 * design fixes.
 *  - P.grants.issuedOnly — this Pending-answer fixture with no existing retry contains only its one new retry grant.
 *    This is not a complete issuance log; CALLER can retain a pre-issued event grant (DemandAuthPlan.kt:68).
 *  - P.rebind.intentKept — re-binding without an explicit intent keeps the REQUEST's own intent
 *    (DemandAuthPlan.kt:38–41·128 `intents[id] ?: old.intent`; §4.4 permits only an intent at least the original).
 * Expected grants and intents are fixed constants; the production allocator is never used to compute them.
 */
class DemandAuthBacklogContract25Test {
    // P.grants — DemandAuthPlan.kt:120–124 and :145. Pending answer with no REQUEST: one new retry r-new is issued.
    // Source last 21, binding start 1, query order 21 ⇒ lower max(21, 1, 21) = 21 and value 22.

    @Test fun P_grants_issuedOnly() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), null)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: the new retry REQUEST", "r-new", demand(p.retryAfter)?.id)
        assertEquals(F.atomic("P.grants.issuedOnly"), mapOf("r-new" to LifecycleOrderGrant(F.life, 21, 1, 21, 22)), p.grants)
    }

    // P.rebind — DemandAuthPlan.kt:125–133. REQUEST "r6b" on the old binding 2 with IF_STALE, re-bound without intents.

    @Test fun P_rebind_intentKept() {
        val old = F.request(id = "r6b", binding = 2, intent = RefreshIntent.IF_STALE)
        assertEquals("fixture: the REQUEST's own intent", RefreshIntent.IF_STALE, demand(old)?.intent)
        val p = DemandAuthPlan.rebind(listOf(old), F.binding, LifecycleOrderSource(F.life, 21))
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: one REQUEST target", listOf("r6b"), p.targets.map { it.target.id })
        assertEquals(F.atomic("P.rebind.intentKept"), RefreshIntent.IF_STALE, demand(p.targets.single().after)?.intent)
    }
}
