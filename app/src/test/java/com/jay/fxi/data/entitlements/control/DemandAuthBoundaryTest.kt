package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.query
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.onlyFalse
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.eligible
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.binding
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.life
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.identity
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.fence
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.auth
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.request
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.decision
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.runtime
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures.now
import org.junit.Assert.*
import org.junit.Test

class DemandAuthBoundaryTest {
    private fun requestNo(id: String, r: DemandV1, q: StartedQueryV1 = query) {
        onlyFalse(r.ownerUid == q.fence.ownerUid, r.binding == q.binding, r.raisedAt.origin == q.order.origin,
            q.order.value > r.raisedAt.value, q.intent >= r.intent)
        assertFalse(eligible(id), DemandAuthBoundary.request(r, q))
    }
    private val r get() = demand(request())!!
    private fun acceptNo(id: String, d: AcceptedQueryDecision, rt: DemandAuthRuntime = runtime()) {
        // Identity existence is its own boundary; equality implications are tested independently below.
        onlyFalse(d.source == LifecycleQuerySource.REGISTERED_QUERY, d.registration in rt.registrations,
            d.acceptedBeforeGeneration == d.query.generation, d.acceptedBeforeFence == d.query.fence,
            d.answeredAs?.ownerUid == d.query.fence.ownerUid, d.answeredAs == rt.liveIdentity,
            d.answeredAs == d.query.boundIdentity)
        assertFalse(eligible(id), DemandAuthBoundary.acceptance(d, rt))
    }
    private fun scopeNo(id: String, a: AuthSnapshotV1) {
        onlyFalse(a.ownerUid == binding.executor.ownerUid, a.authGeneration == binding.identity!!.authGeneration,
            a.binding == binding.executor.binding, a.originLifetimeId == binding.executor.originLifetimeId)
        assertFalse(eligible(id), DemandAuthBoundary.scope(a, binding))
    }
    private val rec get() = LifecycleRecovery("recovery", identity, life, 11, 22, 1)
    private fun recoveryNo(id: String, a: AuthSnapshotV1 = auth, f: LifecycleRecovery = rec,
        rt: DemandAuthRuntime = runtime(recovery = f)) {
        val bound = rt.binding.identity
        // A missing bound identity has no UID/generation relationship to evaluate. Those predicates
        // are defined on the nonnull boundary, not counted as independent false input facts here.
        onlyFalse(a.authStopped, bound != null, bound == null || f.identity.ownerUid == bound.ownerUid,
            bound == null || f.identity.authGeneration == bound.authGeneration, rt.liveIdentity == f.identity,
            f.fetchStartedOrder > rt.binding.startedOrder, f.fetchStartedOrder > a.authStateOrder,
            f.recoveredOrder > a.authStopAppliedOrder, f.episode > rt.consumedRecoveryEpisode)
        assertFalse(eligible(id), DemandAuthBoundary.recovery(a, f, rt))
    }
    private fun initNo(id: String, b: LifecycleBinding = binding, rt: DemandAuthRuntime = runtime(binding = b),
        old: AuthSnapshotV1? = null) {
        onlyFalse(b.startEventId != null, b.identity != null, b.identity == rt.liveIdentity, b == rt.binding,
            old == null, b.acceptedAuthOrder == 0L, b.identity == null || b.identity.ownerUid == b.executor.ownerUid)
        assertFalse(eligible(id), DemandAuthBoundary.initialization(b, rt, old))
    }
    @Test fun Q01() = acceptNo("Q01", decision(), runtime(registrations = emptyList()))
    @Test fun Q01_topic() = acceptNo("Q01source", decision(source = LifecycleQuerySource.TOPIC))
    @Test fun Q01_hold() = acceptNo("Q01hold", decision(source = LifecycleQuerySource.RESTORED_HOLD))
    @Test fun Q02a() = requestNo("Q02a", r.copy(ownerUid = "B"))
    @Test fun Q02b() = requestNo("Q02b", r.copy(binding = 4))
    @Test fun Q02c() = requestNo("Q02c", r.copy(raisedAt = EventOrderV1(LifetimeId("old"), 4)))
    @Test fun Q03a() = requestNo("Q03a", r.copy(raisedAt = EventOrderV1(life, 21)))
    @Test fun Q03b() = requestNo("Q03b", r.copy(raisedAt = EventOrderV1(life, 22)))
    @Test fun Q04a() = requestNo("Q04a", r.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS), query.copy(intent = RefreshIntent.IF_STALE))
    @Test fun Q04b() = requestNo("Q04b", r, query.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS))
    @Test fun Q05a() = acceptNo("Q05a", decision(beforeGeneration = 6))
    @Test fun Q05b() = acceptNo("Q05b", decision(before = fence.copy(userAccessEpoch = "other")))
    @Test fun Q05c() {
        val other = identity.copy(ownerUid = "B")
        val q = query.copy(boundIdentity = other)
        acceptNo("Q05c", decision(q = q, answered = other), runtime(live = other, registrations = listOf(LifecycleQueryRegistration("query-21", q))))
    }
    @Test fun Q05d() = acceptNo("Q05d", decision(), runtime(live = identity.copy(authGeneration = 3)))
    @Test fun Q05e() {
        val q = query.copy(boundIdentity = identity.copy(authGeneration = 3))
        acceptNo("Q05e", decision(q = q), runtime(registrations = listOf(LifecycleQueryRegistration("query-21", q))))
    }
    @Test fun Q06a() { val d = decision(); assertEquals(fence, d.confirmedAfterFence); assertFalse(eligible("Q06a"), DemandAuthBoundary.after(d, 6, fence)) }
    @Test fun Q06b() { val d = decision(); assertEquals(5L, d.expectedAfterGeneration); assertFalse(eligible("Q06b"), DemandAuthBoundary.after(d, 5, fence.copy(krxCapabilityEpoch = "other"))) }
    @Test fun Q07() { assertFalse(eligible("Q07"), DemandAuthBoundary.consumes(EntitlementsOutcome.StableActive(true), RefreshIntent.FORCE_PREMIUM, LifecycleReapproval.BLOCKED)) }
    @Test fun Q08a() { assertFalse(eligible("Q08a"), DemandAuthBoundary.consumes(EntitlementsOutcome.Pending(false, 30), RefreshIntent.FORCE_PREMIUM, LifecycleReapproval.NOT_REQUIRED)) }
    @Test fun Q08b() { assertFalse(eligible("Q08b"), DemandAuthBoundary.consumes(EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION), RefreshIntent.FORCE_PREMIUM, LifecycleReapproval.NOT_REQUIRED)) }
    @Test fun Q08c() { assertFalse(eligible("Q08c"), DemandAuthBoundary.consumes(EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT), RefreshIntent.FORCE_PREMIUM, LifecycleReapproval.NOT_REQUIRED)) }
    @Test fun Q09() { assertFalse(eligible("Q09"), DemandAuthBoundary.consumes(EntitlementsOutcome.KrxEntitlementRequired, RefreshIntent.FORCE_PREMIUM, LifecycleReapproval.NOT_REQUIRED)) }
    @Test fun Q11a() { val old = r.copy(ownerUid = "B", binding = 2); assertTrue(old.intent <= RefreshIntent.FORCE_PREMIUM); assertFalse(eligible("Q11a"), DemandAuthBoundary.rebind(old, binding, RefreshIntent.FORCE_PREMIUM)) }
    @Test fun Q11b() { assertEquals(binding.executor.ownerUid, r.ownerUid); assertFalse(eligible("Q11b"), DemandAuthBoundary.rebind(r, binding, r.intent)) }
    @Test fun Q11c() { val old = r.copy(binding = 2); assertEquals(binding.executor.ownerUid, old.ownerUid); assertFalse(eligible("Q11c"), DemandAuthBoundary.rebind(old, binding, RefreshIntent.FORCE_ENTITLEMENTS)) }
    @Test fun Q12a() { val g = LifecycleOrderGrant(LifetimeId("old"), 21, 1, 21, 22); assertFalse(eligible("Q12a"), DemandAuthBoundary.order(g, binding, 21)) }
    @Test fun Q12b() { val g = LifecycleOrderGrant(life, 21, 1, 21, 23); assertTrue(g.value > 21); assertFalse(eligible("Q12b"), DemandAuthBoundary.order(g, binding, 21)) }
    @Test fun Q13() = requestNo("Q13", r.copy(raisedAt = EventOrderV1(life, 22)))
    @Test fun Q15a() {
        val q = query.copy(boundIdentity = null, fence = fence.copy(ownerUid = null))
        val d = decision(answered = null, q = q)
        assertNull(d.answeredAs); assertEquals(d.acceptedBeforeFence, d.query.fence)
        // Direct missing-answer boundary; identity comparisons depend on existence, not extra guards.
        assertFalse(eligible("Q15a"), DemandAuthBoundary.acceptance(d, runtime(live = null, registrations = listOf(d.registration))))
    }
    @Test fun Q15b() = requestNo("Q15b", r.copy(ownerUid = null))
    @Test fun Q16a() { val g = LifecycleOrderGrant(life, 0, 0, 0, 1); assertFalse(eligible("Q16a"), DemandAuthBoundary.order(g, binding, 0)) }
    @Test fun Q16b() { assertNull(eligible("Q16b"), LifecycleOrderSource(life, Long.MAX_VALUE).issue(1)) }
    @Test fun Q16c() { assertNull(eligible("Q16c"), LifecycleOrderSource(life, Long.MAX_VALUE).issue(1, 21)) }
    @Test fun Q16d() { val g = LifecycleOrderGrant(life, 20, 1, 20, 21); assertFalse(eligible("Q16d"), DemandAuthBoundary.order(g, binding, 21)) }
    @Test fun lastOrderAndDifferentOriginPositive() {
        assertEquals(Long.MAX_VALUE, LifecycleOrderSource(life, Long.MAX_VALUE - 1).issue(1)!!.value)
        val p = DemandAuthPlan.rebind(listOf(request(origin = LifetimeId("old"), order = Long.MAX_VALUE)), binding, LifecycleOrderSource(life, 1))
        assertNull(p.preparationFailure); assertEquals(2L, demand(p.targets.single().after)!!.raisedAt.value)
    }
    @Test fun A01a() = scopeNo("A01a", auth.copy(ownerUid = "B"))
    @Test fun A01b() = scopeNo("A01b", auth.copy(authGeneration = 3))
    @Test fun A01c() = scopeNo("A01c", auth.copy(binding = 4))
    @Test fun A01d() = scopeNo("A01d", auth.copy(originLifetimeId = LifetimeId("old")))
    @Test fun A02a() { assertFalse(eligible("A02a"), DemandAuthBoundary.answerOrder(auth, 10)) }
    @Test fun A02b() { assertFalse(eligible("A02b"), DemandAuthBoundary.answerOrder(auth, 9)) }
    @Test fun A03a() { val a = auth.copy(authStopped = false); assertEquals(auth.authStopAppliedOrder, a.authStopAppliedOrder); assertFalse(eligible("A03a"), DemandAuthBoundary.authChange(auth, a)) }
    @Test fun A03b() { val a = auth.copy(authStateOrder = 11); assertTrue(a.authStateOrder > auth.authStateOrder); assertFalse(eligible("A03b"), DemandAuthBoundary.authChange(auth, a)) }
    @Test fun A03c() { val a = auth.copy(authStopped = false, authStateOrder = 21, authStopAppliedOrder = 22); assertTrue(a.authStateOrder > auth.authStateOrder); assertFalse(eligible("A03c"), DemandAuthBoundary.authChange(auth, a)) }
    @Test fun A04() { val c = LifecycleCaller("c", LifecycleCallerOrigin.SCHEDULED, binding, LifecycleOrderGrant(life, 21, 1, 21, 22), RefreshIntent.FORCE_PREMIUM, now); assertFalse(eligible("A04"), DemandAuthBoundary.caller(c)) }
    @Test fun A05a() = recoveryNo("A05a", a = auth.copy(authStopped = false))
    @Test fun A05b() = recoveryNo("A05b", rt = runtime(binding = binding.copy(identity = null)))
    @Test fun A05c() { val f = rec.copy(identity = identity.copy(ownerUid = "B")); recoveryNo("A05c", f = f, rt = runtime(live = f.identity)) }
    @Test fun A05d() { val f = rec.copy(identity = identity.copy(authGeneration = 3)); recoveryNo("A05d", f = f, rt = runtime(live = f.identity)) }
    @Test fun A05e() = recoveryNo("A05e", rt = runtime(live = identity.copy(authGeneration = 3)))
    @Test fun A05f() = recoveryNo("A05f", rt = runtime(binding = binding.copy(startedOrder = 12)))
    @Test fun A05g() = recoveryNo("A05g", f = rec.copy(fetchStartedOrder = 10))
    @Test fun A05h() = recoveryNo("A05h", f = rec.copy(recoveredOrder = 20))
    @Test fun A06() = recoveryNo("A06", rt = runtime(consumed = 1))
    private val closure get() = LifecycleBindingClosure(auth, true, setOf("q"), setOf("q"), 5)
    @Test fun A07a() { val c = closure.copy(entriesClosed = false); assertEquals(c.capturedWork, c.joinedWork); assertEquals(auth, c.scope); assertFalse(eligible("A07a"), DemandAuthBoundary.closure(auth, c)) }
    @Test fun A07b() { val c = closure.copy(joinedWork = emptySet()); assertTrue(c.entriesClosed); assertEquals(auth, c.scope); assertFalse(eligible("A07b"), DemandAuthBoundary.closure(auth, c)) }
    @Test fun A07c() { val c = closure.copy(scope = auth.copy(binding = 2)); assertTrue(c.entriesClosed); assertEquals(c.capturedWork, c.joinedWork); assertFalse(eligible("A07c"), DemandAuthBoundary.closure(auth, c)) }
    @Test fun A08() = initNo("A08", b = binding.copy(acceptedAuthOrder = 10))
    @Test fun A12a() = initNo("A12a", b = binding.copy(startEventId = null))
    @Test fun A12b() = initNo("A12b", rt = runtime(live = identity.copy(authGeneration = 3)))
    @Test fun A12c() = initNo("A12c", rt = runtime(binding = binding.copy(executor = binding.executor.copy(binding = 4))))
    @Test fun A12d() = initNo("A12d", old = auth)
    @Test fun A12identity() = initNo("A12identity", b = binding.copy(identity = null), rt = runtime(binding = binding.copy(identity = null), live = null))
    @Test fun A12owner() = initNo("A12owner", b = binding.copy(executor = binding.executor.copy(ownerUid = "B")))
    @Test fun A15negative() { assertFalse(eligible("A15negative"), DemandAuthBoundary.seconds(-1)) }
    @Test fun A15a() { assertFalse(eligible("A15a"), DemandAuthBoundary.seconds(Long.MAX_VALUE / 1000 + 1)) }
    @Test fun A15b() { assertFalse(eligible("A15b"), DemandAuthBoundary.floorOrigin(decision(origin = LifetimeId("old")))) }
    @Test fun secondsPositive() { assertTrue(DemandAuthBoundary.seconds(0)); assertTrue(DemandAuthBoundary.seconds(Long.MAX_VALUE / 1000)) }
}
