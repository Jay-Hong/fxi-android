package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, twenty-eighth file: two input and issuance rules left open by the form-obstacle batch.
 *  - F.recordFloor.negativeWait — a floor capture whose only invalid input is a negative wait is rejected
 *    (ControlObligations.kt:126). The positive twin changes only the wait. Other input conditions of the same line
 *    are not claimed here.
 *  - P.retryExhausted.noRequest — when the retry order cannot be issued, the preparation records OrderExhausted and
 *    carries no retry REQUEST (DemandAuthPlan.kt:145). Design §4.5 forbids a fake issuance hiding the failure
 *    (r3 design:310); an unissued REQUEST must not reach the descriptor targets or the diagnostics.
 *  - W.removeEmptyGuard.nonGuard — the named REMOVE_EMPTY_GUARD factory does not check that its input is a GUARD
 *    (ControlRecordStore.kt:77–80, RemoveEmptyGuardTransition.kt:9–11): a REQUEST input yields a descriptor whose
 *    target id is empty. validDescriptor (ControlLifecycle.kt:237·247) must reject it; the positive twin is an empty
 *    guard. validShape (ControlLifecycleEvidence.kt:42–58) does not check the id.
 * Expected values are fixed constants of the fixture source.
 */
class DemandAuthBacklogContract28Test {
    // F.recordFloor — guard fixture, known boot "boot" at elapsed 0, origin "life"; only the wait differs.

    @Test fun F_recordFloor_negativeWait() {
        val ok = ControlObligations.recordFloor(F.guard(), BootReading("boot", 0), 1, F.life)
        assertTrue("positive twin: a nonnegative wait is written", ok is ControlWriteResult.Written)
        val bad = ControlObligations.recordFloor(F.guard(), BootReading("boot", 0), -1, F.life)
        assertEquals(F.atomic("F.recordFloor.negativeWait"), ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), bad)
    }

    // P.retryExhausted — source last Long.MAX_VALUE, so no order can be issued; a Pending answer needs a new retry.

    @Test fun P_retryExhausted_noRequest() {
        val positive = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), null, orders = LifecycleOrderSource(F.life, 21))
        assertNotNull("positive twin: an issuable source yields the retry REQUEST", positive.retryAfter)
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), null,
            orders = LifecycleOrderSource(F.life, Long.MAX_VALUE))
        assertEquals("fixture: the retry order cannot be issued", "OrderExhausted", p.preparationFailure)
        assertNull(F.atomic("P.retryExhausted.noRequest"), p.retryAfter)
    }

    // W.removeEmptyGuard — an empty guard (no AUTH, no floor) versus the fixture REQUEST, both through the named factory.

    @Test fun W_removeEmptyGuard_nonGuard() {
        val confirmation = ControlLifecycleConfirmation(F.codec)
        val empty = RemoveEmptyGuardPlan.prepare(F.guard(auth = null)).descriptor("cmd-e")
        assertTrue("positive twin: an empty guard descriptor is valid", confirmation.validDescriptor(empty))
        val d = RemoveEmptyGuardPlan.prepare(F.request()).descriptor("cmd-r")
        assertEquals("fixture: a non-guard input yields an empty target id", "", d.targets.single().target.id)
        assertFalse(F.eligible("W.removeEmptyGuard.nonGuard"), confirmation.validDescriptor(d))
    }
}
