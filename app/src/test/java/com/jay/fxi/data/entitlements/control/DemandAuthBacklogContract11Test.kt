package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, eleventh file: positive directions of the Caller, Recovery and Initialize paths.
 *
 * Before C11 each of these seven mutants was observed only through a plain premise — `F.apply`'s
 * "positive fixture must reach Confirm" (C2:322), `confirms()`'s "fixture plan must be prepared" (C3:40),
 * or `confirmed()`'s storage premise (ControlRecordStoreReviewTest:48). Design §9.1 545 forbids counting
 * those as kills, and the M4 rows require the widening direction to be fixed to a candidate/result assertion.
 *
 * The five Caller candidates sit at two different production stages, so they get two assertions rather than
 * one: DP:71 records a preparation failure, while DT:219-223 narrow the writer's `callerRetry` boundary.
 *
 * Expected values are built in the test; no production validator is called to produce them (§9.1 539).
 */
class DemandAuthBacklogContract11Test {

    private val orders = LifecycleOrderSource(F.life, 21)

    // RC.caller.planPositive — DP:71. A Caller event whose boot reading matches the floor anchor prepares cleanly.

    @Test fun RC_caller_planPositive() {
        val g = F.guard(wait = 30000)
        val before = checkNotNull(guard(g))
        assertNotNull("fixture: the existing guard carries a floor", before.floor)
        assertEquals("fixture: the caller reading is on the floor's anchor boot",
            checkNotNull(before.floor).anchorBootId, F.now.bootId)
        val caller = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding,
            LifecycleOrderGrant(F.life, 20, 1, 0, 21), RefreshIntent.FORCE_ENTITLEMENTS, F.now)
        val p = DemandAuthPlan.auth(g, F.request(), F.binding, LifecycleAuthEvent.Caller(caller), orders, "g-new", "r-new")
        assertNull(F.atomic("RC.caller.planPositive"), p.preparationFailure)
    }

    // RC.caller.retryPositive — DT:217-223. A caller request that clears every ordering term is a valid retry.

    @Test fun RC_caller_retryPositive() {
        val request = checkNotNull(demand(F.request(order = 30, intent = RefreshIntent.FORCE_PREMIUM)))
        val previous = checkNotNull(demand(F.request(order = 10, intent = RefreshIntent.FORCE_ENTITLEMENTS)))
        val grant = LifecycleOrderGrant(F.life, 29, 1, 21, 30)
        val callerOrder = LifecycleOrderGrant(F.life, 20, 1, 0, 21)
        val caller = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding,
            callerOrder, RefreshIntent.FORCE_ENTITLEMENTS, F.now)
        // Issuance rule restated in the test (DF:110 Q12b): value = max(previous, bindingStart, after) + 1.
        assertEquals("fixture: the REQUEST grant follows the issuance rule",
            maxOf(grant.previous, grant.bindingStart, grant.after) + 1, grant.value)
        assertEquals("fixture: the caller order follows the issuance rule",
            maxOf(callerOrder.previous, callerOrder.bindingStart, callerOrder.after) + 1, callerOrder.value)
        assertEquals("fixture: the REQUEST raisedAt is its grant value", grant.value, request.raisedAt.value)
        assertEquals("fixture: the request order clears the binding start", true, request.raisedAt.value > F.binding.startedOrder)
        assertEquals("fixture: the request order clears the grant's previous", true, request.raisedAt.value > grant.previous)
        assertEquals("fixture: the request order clears the caller's own order", true, request.raisedAt.value > caller.order.value)
        assertEquals("fixture: the request order clears the earlier request", true, request.raisedAt.value > previous.raisedAt.value)
        assertEquals("fixture: the request intent does not weaken", true, request.intent >= previous.intent)
        assertTrue(F.atomic("RC.caller.retryPositive"),
            F.transition.callerRetry(request, previous, F.binding, caller, grant))
    }

    // RC.recovery.planPositive — DP:76-79. A Recovery event over an existing AUTH prepares and keeps that AUTH
    // with authStopped cleared and the recovered order installed.

    @Test fun RC_recovery_planPositive() {
        val g = F.guard()
        val old = checkNotNull(checkNotNull(guard(g)).auth)
        assertTrue("fixture: the existing AUTH is stopped before recovery", old.authStopped)
        val rec = LifecycleRecovery("rec", F.identity, F.life, 11, 22, 1)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Recovery(rec), orders, "g-new", "r-new")
        assertNull(F.atomic("RC.recovery.planPositive"), p.preparationFailure)
        assertEquals(F.atomic("RC.recovery.planPositive"),
            AuthSnapshotV1(old.ownerUid, old.authGeneration, old.binding, old.originLifetimeId, false, 22, old.authStopAppliedOrder),
            guard(p.guardAfter)?.auth)
    }

    // RC.initialize.planPositive — DP:59-61. Initialize keeps the existing guard's id and floor and installs
    // the initial AUTH derived from the binding.

    @Test fun RC_initialize_planPositive() {
        val g = F.guard(auth = null, wait = 30000)
        val before = checkNotNull(guard(g))
        assertNull("fixture: the existing guard has no AUTH yet", before.auth)
        assertEquals("fixture: the existing guard id is g", "g", before.id)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        assertNull(F.atomic("RC.initialize.planPositive"), p.preparationFailure)
        val after = checkNotNull(guard(p.guardAfter))
        assertEquals(F.atomic("RC.initialize.planPositive"), before.id, after.id)
        assertEquals(F.atomic("RC.initialize.planPositive"), before.floor, after.floor)
        assertEquals(F.atomic("RC.initialize.planPositive"),
            AuthSnapshotV1(checkNotNull(F.binding.identity).ownerUid, checkNotNull(F.binding.identity).authGeneration,
                F.binding.executor.binding, F.binding.executor.originLifetimeId, false, 0, 0),
            after.auth)
    }
}
