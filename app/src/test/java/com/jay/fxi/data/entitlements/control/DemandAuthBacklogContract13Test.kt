package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirteenth file: U.stopGrant.missing — the eligibility gate at DT:147 that reads the
 * `authStopGrant` introduced by the 5e-1 production fix.
 *
 * `R6.DT.147.51` (`?: return "OrderExhausted"` → `?: return null`) was NOT_CAUGHT with C7 and C8 installed:
 * no test drove an AUTH-stop plan whose `authStopGrant` is absent. A normal factory does not produce that state
 * (an exhausted source records a preparation failure first), so the negative input is made by removing only that
 * field from an otherwise complete plan, using the same reflective injection DemandAuthCallerTest:183 already uses
 * for `retryAfter`.
 *
 * Scope is the direct eligibility boundary. DT:237 has a later grant-absence check, so a kill here is not evidence
 * that the full writer would land an ineligible AUTH stop.
 */
class DemandAuthBacklogContract13Test {

    // ControlNode compares by identity; independently built plans are compared by payload.
    private fun shape(p: DemandAuthPlan) = p.targets.map {
        listOf(it.target, it.role, it.before?.toPayloadEntry(), it.after?.toPayloadEntry())
    }

    private fun authStopPlan(): DemandAuthPlan = F.plan(
        F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION),
            followUp = RefreshIntent.FORCE_PREMIUM),
        F.guard(), F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS))

    @Test fun U_stopGrant_missing() {
        val raw = F.raw(F.guard(), F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS))
        val twin = authStopPlan()
        assertNull("fixture plan must be prepared", twin.preparationFailure)
        assertNotNull("fixture: the complete plan carries an AUTH stop grant", twin.authStopGrant)
        assertEquals("fixture: the plan stops AUTH", true, guard(twin.guardAfter)?.auth?.authStopped)
        assertNull("positive twin: the complete AUTH stop plan is eligible",
            F.transition.eligibility(twin, F.runtime(), F.read(raw)))

        val missing = authStopPlan()
        DemandAuthPlan::class.java.getDeclaredField("authStopGrant").apply { isAccessible = true }.set(missing, null)
        assertNull("fixture: only authStopGrant is removed", missing.authStopGrant)
        assertEquals("fixture: guardAfter is unchanged", twin.guardAfter?.toPayloadEntry(), missing.guardAfter?.toPayloadEntry())
        assertEquals("fixture: targets are unchanged", shape(twin), shape(missing))
        assertEquals("fixture: REQUEST grants are unchanged", twin.grants, missing.grants)

        val reason = F.transition.eligibility(missing, F.runtime(), F.read(raw))
        assertNotNull(F.eligible("U.stopGrant.missing"), reason)
        assertEquals("classification: U.stopGrant.missing reason", "OrderExhausted", reason)
    }
}
