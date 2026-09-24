package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirtieth file: Z.fixturePremises (design §9.1 r3:539–545) for three negative boundaries whose existing
 * tests assert the role without first confirming the non-target conditions in the same method (DemandAuthBoundaryTest
 * Q08b·Q16b, DemandAuthOrderSourceTest originDomain). Each method first confirms the non-target conditions with an
 * independent expected value and a positive twin that differs only in the target condition, then asserts the role.
 *  - Q08b — AUTHENTICATION indeterminate is not consumed; twin: the same intent/reapproval with a stable outcome is.
 *  - originDomain — an empty origin is refused; twin: the same initial order with the fixture origin constructs.
 *  - Q16b — issuance from MAX is exhausted; twin: the same origin/bindingStart one below MAX issues MAX.
 */
class DemandAuthBacklogContract30Test {
    @Test fun Z_fx_Q08b_authenticationIndeterminate() {
        assertNotEquals("fixture: reapproval is not the blocking value", LifecycleReapproval.BLOCKED, LifecycleReapproval.NOT_REQUIRED)
        assertTrue("positive twin: the same intent and reapproval consume a stable outcome",
            DemandAuthBoundary.consumes(EntitlementsOutcome.StableInactive(false), RefreshIntent.FORCE_PREMIUM, LifecycleReapproval.NOT_REQUIRED))
        assertFalse(F.eligible("Z.fx.Q08b"), DemandAuthBoundary.consumes(
            EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION), RefreshIntent.FORCE_PREMIUM, LifecycleReapproval.NOT_REQUIRED))
    }

    @Test fun Z_fx_originDomain_emptyOrigin() {
        assertTrue("fixture: the fixture origin is not empty", F.life.value.isNotEmpty())
        assertTrue("positive twin: the same initial order with the fixture origin constructs",
            runCatching { LifecycleOrderSource(F.life, 0) }.isSuccess)
        assertTrue(F.eligible("Z.fx.originDomain"),
            runCatching { LifecycleOrderSource(LifetimeId(""), 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun Z_fx_Q16b_exhaustedAtMax() {
        assertTrue("fixture: the fixture origin is not empty", F.life.value.isNotEmpty())
        assertEquals("positive twin: the same origin and bindingStart one below MAX issue MAX",
            Long.MAX_VALUE, LifecycleOrderSource(F.life, Long.MAX_VALUE - 1).issue(1)?.value)
        assertNull(F.eligible("Z.fx.Q16b"), LifecycleOrderSource(F.life, Long.MAX_VALUE).issue(1))
    }
}
