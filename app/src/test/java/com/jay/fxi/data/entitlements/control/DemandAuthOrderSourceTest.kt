package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

class DemandAuthOrderSourceTest {
    @Test fun Q16_lastValid() {
        val source = LifecycleOrderSource(F.life, Long.MAX_VALUE - 1)
        val last = source.issue(1)
        assertTrue(F.atomic("Q16.lastValid"), last?.value == Long.MAX_VALUE)
        assertNull("the request after MAX must be exhausted", source.issue(1))
    }
    @Test fun initialDomain() {
        assertTrue(F.life.value.isNotEmpty())
        assertTrue(F.eligible("P.orderInitial"), runCatching { LifecycleOrderSource(F.life, -1) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun originDomain() {
        assertTrue(F.eligible("P.orderOrigin"), runCatching { LifecycleOrderSource(LifetimeId(""), 0) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun exhaustedPreparation() {
        val before = F.request(binding = 2)
        assertTrue(DemandAuthBoundary.rebind(demand(before)!!, F.binding, demand(before)!!.intent))
        val plan = DemandAuthPlan.rebind(listOf(before), F.binding, LifecycleOrderSource(F.life, Long.MAX_VALUE))
        assertNotNull(F.eligible("P.orderExhausted"), plan.preparationFailure)
    }
}
