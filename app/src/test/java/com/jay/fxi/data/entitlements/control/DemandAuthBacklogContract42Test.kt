package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, forty-second file — validator sub-conditions, part B (direct boundaries):
 *  - callerRetry (DemandAuthTransition.kt:215–224; design r3:592 A04 "실제 floor-blocked CALLER에서는 REQUEST 원자 인계"):
 *    grant · after binding start · after grant.previous · after the caller event · after the old REQUEST · intent kept.
 *    Inside requiredEffects, changing only raisedAt is first refused by C04order (DT:204, compared with the builder's successor).
 *    This does not mask callerIntent when the old REQUEST is stronger than caller.intent.
 *    These tests cover callerRetry directly; the callerIntent connection at requiredEffects remains OPEN.
 *  - authPostcondition per event (DT:226–244; §9.4 r3:632 C08): the expected AUTH of each event, derived here as a literal
 *    from the fixture — Initialize (initial snapshot) · Caller (resume at the caller order) · Recovery (resume at the
 *    recovered order) · Answer resume · Answer AUTH stop (stopApplied = the stop grant) · Answer at an old order (unchanged)
 *    · END with a replacement (initial snapshot of the replacement) · END without one (no AUTH).
 * callerRetry methods: normal vector all true with the production literal result true (twin), then exactly the target
 * false. authPostcondition methods (one predicate): the literal expectation passes (twin), the actual differs from it.
 */
class DemandAuthBacklogContract42Test {
    private fun neg(id: String, normal: List<Pair<String, Boolean>>, twin: Boolean, bad: List<Pair<String, Boolean>>, only: String, result: Boolean) {
        assertEquals("truth vector: normal fixture", emptySet<String>(), V.falses(normal))
        assertTrue("positive twin: the normal fixture passes the boundary", twin)
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(bad))
        assertFalse(F.atomic(id), result)
    }

    // callerRetry — normal: successor raisedAt 22, old REQUEST raisedAt 4 (FORCE_PREMIUM), binding start 1, caller order 21,
    // grant (previous 21, value 22)
    private fun req(raised: Long = 22, intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM) = demand(F.request(id = "r-new", order = raised, intent = intent))!!
    private val old0 get() = demand(F.request())!!
    private fun callerAt(order: Long) = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding,
        LifecycleOrderGrant(F.life, order - 1, 1, 0, order), RefreshIntent.FORCE_PREMIUM, F.now)
    private val grant0 = LifecycleOrderGrant(F.life, 21, 1, 21, 22)
    private fun cr(r: DemandV1, before: DemandV1?, b: LifecycleBinding, c: LifecycleCaller, g: LifecycleOrderGrant?) = listOf(
        "grant" to (g != null), "afterBindingStart" to (r.raisedAt.value > b.startedOrder),
        "afterPrevious" to (g == null || r.raisedAt.value > g.previous), "afterEvent" to (r.raisedAt.value > c.order.value),
        "afterOld" to (before == null || r.raisedAt.value > before.raisedAt.value), "intentKept" to (before == null || r.intent >= before.intent))
    private fun crNo(id: String, only: String, r: DemandV1 = req(), before: DemandV1? = old0, b: LifecycleBinding = F.binding,
        c: LifecycleCaller = callerAt(21), g: LifecycleOrderGrant? = grant0) =
        neg(id, cr(req(), old0, F.binding, callerAt(21), grant0), F.transition.callerRetry(req(), old0, F.binding, callerAt(21), grant0),
            cr(r, before, b, c, g), only, F.transition.callerRetry(r, before, b, c, g))
    @Test fun A04_callerGrant() = crNo("Z.ve.A04.callerGrant", "grant", g = null)
    @Test fun A04_callerBindingStart() = crNo("Z.ve.A04.callerBindingStart", "afterBindingStart", b = F.binding.copy(startedOrder = 30))
    @Test fun A04_callerPrevious() = crNo("Z.ve.A04.callerPrevious", "afterPrevious", g = LifecycleOrderGrant(F.life, 25, 1, 21, 26))
    @Test fun A04_callerEvent() = crNo("Z.ve.A04.callerEvent", "afterEvent", c = callerAt(30))
    @Test fun A04_callerRaisedAt() = crNo("Z.ve.A04.callerRaisedAt", "afterOld", before = demand(F.request(order = 22))!!)
    @Test fun A04_callerIntent() = crNo("Z.ve.A04.callerIntent", "intentKept", r = req(intent = RefreshIntent.FORCE_ENTITLEMENTS))

    // authPostcondition — expected snapshot per event as a literal; the negative changes one field of the actual snapshot
    private val orders get() = LifecycleOrderSource(F.life, 21)
    /** authPostcondition has one predicate (actual == expected), so there is no non-target condition: the twin shows the
     *  literal expectation is the production one, and the fixture shows the actual differs from it. */
    private fun ap(id: String, plan: DemandAuthPlan, expected: AuthSnapshotV1?, actual: AuthSnapshotV1?) {
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertTrue("positive twin: the literal expected AUTH satisfies the postcondition", F.transition.authPostcondition(plan, expected))
        assertNotEquals("fixture: the actual AUTH differs from the literal expectation", expected, actual)
        assertFalse(F.atomic(id), F.transition.authPostcondition(plan, actual))
    }
    private val initial = AuthSnapshotV1("A", 2, 3, F.life, false, 0, 0)
    private val resumed21 = F.auth.copy(authStopped = false, authStateOrder = 21)
    @Test fun C08_initialize() = ap("Z.ve.C08.initialize",
        DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new"), initial, initial.copy(authStateOrder = 1))
    @Test fun C08_caller() = ap("Z.ve.C08.caller",
        DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Caller(callerAt(21)), orders, "g-new", "r-new"), resumed21, resumed21.copy(authStateOrder = 22))
    @Test fun C08_recovery() = ap("Z.ve.C08.recovery",
        DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Recovery(LifecycleRecovery("recovery", F.identity, F.life, 11, 21, 1)), orders, "g-new", "r-new"),
        resumed21, resumed21.copy(authStopped = true))
    @Test fun C08_answerResume() = ap("Z.ve.C08.answerResume", F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), null),
        resumed21, resumed21.copy(authStopAppliedOrder = 21))
    @Test fun C08_answerStop() = F.auth.copy(authStopped = false).let { before ->
        val stopped = before.copy(authStopped = true, authStateOrder = 21, authStopAppliedOrder = 22)
        ap("Z.ve.C08.answerStop", F.plan(F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION)), F.guard(before), null),
            stopped, stopped.copy(authStopAppliedOrder = 21)) }
    @Test fun C08_answerOld() = F.auth.copy(authStateOrder = 30, authStopAppliedOrder = 31).let { before ->
        ap("Z.ve.C08.answerOld", F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(before), null),
            before, before.copy(authStopped = false)) }
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val closure get() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
    @Test fun C08_endReplacement() = ap("Z.ve.C08.endReplacement",
        DemandAuthPlan.end(F.guard(auth = oldAuth), emptyList(), F.binding, closure, F.binding, orders), initial, initial.copy(authStopped = true))
    @Test fun C08_endNone() = ap("Z.ve.C08.endNone",
        DemandAuthPlan.end(F.guard(auth = oldAuth), emptyList(), F.binding, closure, null, orders), null, initial)
}
