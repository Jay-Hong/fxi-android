package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, twenty-seventh file: issuance rules of design §4.5 (r3 design:309–310) on failed preparations.
 *  - P.retryScope.foreignOrder — a retry REQUEST of another origin is refused by the scope check (DP:141–142) before
 *    any issuance (DP:145), so its raisedAt never moves the shared source; design §4.5 forbids comparing with an old
 *    number of a changed origin, and the re-bind bound drops such numbers too (DP:130). This covers the retry REQUEST
 *    only: the query order's origin is checked later by eligibility (DT:75), not here.
 *  - P.authExhausted.noFakeGrant — when the AUTH stop cannot be issued (bound Long.MAX_VALUE), the preparation records
 *    OrderExhausted and carries no stop grant ("0 reset … 오류를 숨기기 위한 가짜 origin 발급은 금지"; DP:173).
 * Expected order values are fixed constants of the fixture source; the production allocator only issues them.
 */
class DemandAuthBacklogContract27Test {
    // P.retryScope — source last 21, binding start 1; the retry REQUEST has origin "other" and raisedAt MAX − 1.
    // After the refused preparation, the next issue(1, 0) is max(21, 1, 0) + 1 = 22.

    @Test fun P_retryScope_foreignOrder() {
        val source = LifecycleOrderSource(F.life, 21)
        val foreign = F.request(origin = LifetimeId("other"), intent = RefreshIntent.IF_STALE, order = Long.MAX_VALUE - 1)
        assertEquals("fixture: the retry REQUEST is of another origin", LifetimeId("other"), demand(foreign)!!.raisedAt.origin)
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), foreign, orders = source)
        assertEquals("fixture: the scope check refuses the retry", "RetryScopeMismatch", p.preparationFailure)
        assertEquals(F.atomic("P.retryScope.foreignOrder"), 22L, source.issue(1, 0)?.value)
    }

    // P.authExhausted — the guard's AUTH stop was applied at Long.MAX_VALUE; an AUTHENTICATION answer at order 21 needs
    // a stop grant above it, which cannot be issued.

    @Test fun P_authExhausted_noFakeGrant() {
        val g = F.guard(auth = F.auth.copy(authStopAppliedOrder = Long.MAX_VALUE))
        assertEquals("fixture: the stop bound is exhausted", Long.MAX_VALUE, guard(g)!!.auth!!.authStopAppliedOrder)
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION)), g, null)
        assertEquals("fixture: the stop cannot be issued", "OrderExhausted", p.preparationFailure)
        assertNull(F.atomic("P.authExhausted.noFakeGrant"), p.authStopGrant)
    }
}
