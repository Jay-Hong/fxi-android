package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, eighth file: the receipt reports the transition of the command it was built for. The r6 probe of
 * `SYN.LC.279.16` (receipt transition argument fixed to END_AUTH_BINDING) failed only a RemoveEmptyGuard receipt check;
 * C7 observes the receipt's id lists and required-unchanged observations but never its transition.
 *
 * Design basis: §3.1 the descriptor fixes the transition of the one command, and §3.3 the receipt reports current
 * storage observations for that command. Each named DemandAuth factory names its own transition; the expected value
 * here is the factory's fixed transition, not a value read back from the receipt builder.
 *
 * Boundary: the confirmation receipt boundary (`ControlLifecycleConfirmation.receipt`) with a stored snapshot, as in
 * C5 `DP_descriptor` for `commandId`. New-application and confirm-only decisions are covered by C6 and C7.
 */
class DemandAuthBacklogContract8Test {
    private val g = F.guard()
    private val r = F.request()
    private val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun closure() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)

    @Test fun RC_receipt_transitionPerFactory() {
        val cases = listOf(
            Triple("auth", F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak), LifecycleTransition.UPDATE_AUTH),
            Triple("settle", F.plan(F.decision(followUp = RefreshIntent.FORCE_PREMIUM), g, null, settle = true, removes = listOf(r)),
                LifecycleTransition.SETTLE_QUERY),
            Triple("rebind", DemandAuthPlan.rebind(listOf(F.request(binding = 2)), F.binding, orders), LifecycleTransition.REBIND_REQUESTS),
            Triple("end", DemandAuthPlan.end(F.guard(auth = oldAuth), listOf(F.request(binding = 2)), F.binding, closure(), F.binding, orders),
                LifecycleTransition.END_AUTH_BINDING))
        for ((name, plan, transition) in cases) {
            assertNull("fixture plan must be prepared: $name", plan.preparationFailure)
            val d = plan.descriptor("cmd-$name")
            assertEquals("fixture: the $name descriptor names its own transition", transition, d.transition)
            val receipt = ControlLifecycleConfirmation(F.codec).receipt(d, F.read(F.raw(g, r)))
            assertEquals(F.retry("RC.receipt.transition.$name"), transition, receipt.transition)
        }
    }
}
