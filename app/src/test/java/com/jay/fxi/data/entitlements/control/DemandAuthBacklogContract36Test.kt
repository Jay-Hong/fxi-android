package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirty-sixth file — the closure method (C34 pilot, STEP3ZJ r6) on the remaining pure DemandAuthBoundary
 * functions' **direct boundaries** (DemandAuthFacts.kt:104–215; design §9.2–9.3 r3:564–570·574 Q06·Q07·Q08·Q09·Q11·Q12·Q16a/d,
 * r3:589–603 A01–A08·A12·A15). Writer connections are a separate contract.
 * Each method: the normal fixture's truth vector (written here, in the production predicate order, with the production's
 * own null/state guards mirrored so that guarded predicates are vacuously true when their guard does not apply) is all
 * true and the production literal result is true (positive twin); then the negative fixture's vector is false exactly at
 * the target before the role. Fixtures are the DemandAuthBoundaryTest ones, now with the target named.
 */
class DemandAuthBacklogContract36Test {
    private val binding = F.binding; private val auth = F.auth; private val life = F.life
    private fun neg(id: String, normal: List<Pair<String, Boolean>>, twin: Boolean, bad: List<Pair<String, Boolean>>, only: String, result: Boolean) {
        assertEquals("truth vector: normal fixture", emptySet<String>(), V.falses(normal))
        assertTrue("positive twin: the normal fixture passes the boundary", twin)
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(bad))
        assertFalse(F.eligible(id), result)
    }

    // order — DF:108–114 (Q12a · Q12b · Q16a · Q16d)
    private fun ord(g: LifecycleOrderGrant, b: LifecycleBinding, after: Long) = listOf(
        "origin" to (g.origin == b.executor.originLifetimeId), "fresh" to (g.value == maxOf(g.previous, g.bindingStart, g.after) + 1),
        "start" to (g.value > b.startedOrder), "after" to (g.value > after))
    private val g0 = LifecycleOrderGrant(life, 21, 1, 21, 22)
    private fun ordNo(id: String, only: String, g: LifecycleOrderGrant, after: Long) =
        neg(id, ord(g0, binding, 21), DemandAuthBoundary.order(g0, binding, 21), ord(g, binding, after), only, DemandAuthBoundary.order(g, binding, after))
    @Test fun Q12a() = ordNo("Z.bd.Q12a", "origin", LifecycleOrderGrant(LifetimeId("old"), 21, 1, 21, 22), 21)
    @Test fun Q12b() = ordNo("Z.bd.Q12b", "fresh", LifecycleOrderGrant(life, 21, 1, 21, 23), 21)
    @Test fun Q16a() = ordNo("Z.bd.Q16a", "start", LifecycleOrderGrant(life, 0, 0, 0, 1), 0)
    @Test fun Q16d() = ordNo("Z.bd.Q16d", "after", LifecycleOrderGrant(life, 20, 1, 20, 21), 21)

    // rebind — DF:115–120 (Q11a · Q11b · Q11c)
    private fun reb(old: DemandV1, b: LifecycleBinding, intent: RefreshIntent) = listOf(
        "owner" to (old.ownerUid == b.executor.ownerUid),
        "needed" to !(old.binding == b.executor.binding && old.raisedAt.origin == b.executor.originLifetimeId),
        "intent" to (intent >= old.intent))
    private val old0 get() = demand(F.request(binding = 2))!!
    private fun rebNo(id: String, only: String, old: DemandV1, intent: RefreshIntent) =
        neg(id, reb(old0, binding, old0.intent), DemandAuthBoundary.rebind(old0, binding, old0.intent), reb(old, binding, intent), only,
            DemandAuthBoundary.rebind(old, binding, intent))
    @Test fun Q11a() = rebNo("Z.bd.Q11a", "owner", old0.copy(ownerUid = "B"), RefreshIntent.FORCE_PREMIUM)
    @Test fun Q11b() = rebNo("Z.bd.Q11b", "needed", demand(F.request())!!, RefreshIntent.FORCE_PREMIUM)
    @Test fun Q11c() = rebNo("Z.bd.Q11c", "intent", old0, RefreshIntent.FORCE_ENTITLEMENTS)

    // consumes — DF:129–140 (Q07 · Q08a–c · Q09); the outcome branch is re-derived here from the design table (§4.2).
    private fun settles(o: EntitlementsOutcome, i: RefreshIntent) = when (o) {
        is EntitlementsOutcome.Pending -> false; is EntitlementsOutcome.Indeterminate -> false
        EntitlementsOutcome.KrxEntitlementRequired -> i != RefreshIntent.FORCE_PREMIUM; else -> true }
    private fun con(o: EntitlementsOutcome, i: RefreshIntent, r: LifecycleReapproval) = listOf(
        "notBlocked" to (r != LifecycleReapproval.BLOCKED), "settles" to settles(o, i))
    private val fp = RefreshIntent.FORCE_PREMIUM; private val nr = LifecycleReapproval.NOT_REQUIRED
    private fun conNo(id: String, only: String, o: EntitlementsOutcome, r: LifecycleReapproval = nr) =
        neg(id, con(EntitlementsOutcome.StableActive(true), fp, nr), DemandAuthBoundary.consumes(EntitlementsOutcome.StableActive(true), fp, nr),
            con(o, fp, r), only, DemandAuthBoundary.consumes(o, fp, r))
    @Test fun Q07() = conNo("Z.bd.Q07", "notBlocked", EntitlementsOutcome.StableActive(true), LifecycleReapproval.BLOCKED)
    @Test fun Q08a() = conNo("Z.bd.Q08a", "settles", EntitlementsOutcome.Pending(false, 30))
    @Test fun Q08b() = conNo("Z.bd.Q08b", "settles", EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION))
    @Test fun Q08c() = conNo("Z.bd.Q08c", "settles", EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT))
    @Test fun Q09() = conNo("Z.bd.Q09", "settles", EntitlementsOutcome.KrxEntitlementRequired)

    // after — DF:152–156 (Q06a · Q06b)
    private fun aft(d: AcceptedQueryDecision, gen: Long, fence: FenceV1) = listOf(
        "generation" to (gen == d.expectedAfterGeneration), "fence" to (fence == d.confirmedAfterFence))
    private fun aftNo(id: String, only: String, gen: Long, fence: FenceV1) = F.decision().let { d ->
        neg(id, aft(d, d.expectedAfterGeneration, d.confirmedAfterFence), DemandAuthBoundary.after(d, d.expectedAfterGeneration, d.confirmedAfterFence),
            aft(d, gen, fence), only, DemandAuthBoundary.after(d, gen, fence)) }
    @Test fun Q06a() = aftNo("Z.bd.Q06a", "generation", 6, F.fence)
    @Test fun Q06b() = aftNo("Z.bd.Q06b", "fence", 5, F.fence.copy(krxCapabilityEpoch = "other"))

    // scope — DF:157–163 (A01a–d)
    private fun sco(a: AuthSnapshotV1, b: LifecycleBinding) = listOf(
        "owner" to (a.ownerUid == b.executor.ownerUid), "generation" to (a.authGeneration == b.identity?.authGeneration),
        "binding" to (a.binding == b.executor.binding), "origin" to (a.originLifetimeId == b.executor.originLifetimeId))
    private fun scoNo(id: String, only: String, a: AuthSnapshotV1) =
        neg(id, sco(auth, binding), DemandAuthBoundary.scope(auth, binding), sco(a, binding), only, DemandAuthBoundary.scope(a, binding))
    @Test fun A01a() = scoNo("Z.bd.A01a", "owner", auth.copy(ownerUid = "B"))
    @Test fun A01b() = scoNo("Z.bd.A01b", "generation", auth.copy(authGeneration = 3))
    @Test fun A01c() = scoNo("Z.bd.A01c", "binding", auth.copy(binding = 4))
    @Test fun A01d() = scoNo("Z.bd.A01d", "origin", auth.copy(originLifetimeId = LifetimeId("old")))

    // answerOrder — DF:164–167 (A02a · A02b)
    private fun ao(a: AuthSnapshotV1, order: Long) = listOf("order" to (order > a.authStateOrder))
    @Test fun A02a() = neg("Z.bd.A02a", ao(auth, 11), DemandAuthBoundary.answerOrder(auth, 11), ao(auth, 10), "order", DemandAuthBoundary.answerOrder(auth, 10))
    @Test fun A02b() = neg("Z.bd.A02b", ao(auth, 11), DemandAuthBoundary.answerOrder(auth, 11), ao(auth, 9), "order", DemandAuthBoundary.answerOrder(auth, 9))

    // authChange — DF:168–173 (A03a–c); b applies only when stopped, c only when resumed.
    private fun ac(b: AuthSnapshotV1, a: AuthSnapshotV1) = listOf(
        "state" to (a.authStateOrder > b.authStateOrder), "stopApplied" to (!a.authStopped || a.authStopAppliedOrder > b.authStopAppliedOrder),
        "resumeKeeps" to (a.authStopped || a.authStopAppliedOrder == b.authStopAppliedOrder))
    private val resumed get() = auth.copy(authStopped = false, authStateOrder = 21)
    private fun acNo(id: String, only: String, a: AuthSnapshotV1) =
        neg(id, ac(auth, resumed), DemandAuthBoundary.authChange(auth, resumed), ac(auth, a), only, DemandAuthBoundary.authChange(auth, a))
    @Test fun A03a() = acNo("Z.bd.A03a", "state", auth.copy(authStopped = false))
    @Test fun A03b() = acNo("Z.bd.A03b", "stopApplied", auth.copy(authStateOrder = 11))
    @Test fun A03c() = acNo("Z.bd.A03c", "resumeKeeps", auth.copy(authStopped = false, authStateOrder = 21, authStopAppliedOrder = 22))

    // caller — DF:174–177 (A04)
    private fun callerFact(o: LifecycleCallerOrigin) = LifecycleCaller("c", o, binding, LifecycleOrderGrant(life, 21, 1, 21, 22), fp, F.now)
    private fun cal(f: LifecycleCaller) = listOf("origin" to (f.origin == LifecycleCallerOrigin.CALLER))
    @Test fun A04() = neg("Z.bd.A04", cal(callerFact(LifecycleCallerOrigin.CALLER)), DemandAuthBoundary.caller(callerFact(LifecycleCallerOrigin.CALLER)),
        cal(callerFact(LifecycleCallerOrigin.SCHEDULED)), "origin", DemandAuthBoundary.caller(callerFact(LifecycleCallerOrigin.SCHEDULED)))

    // recovery — DF:178–190 (A05a–h · A06); c·d are defined on a nonnull bound identity (production `bound != null &&`).
    private val rec get() = LifecycleRecovery("recovery", F.identity, life, 11, 22, 1)
    private fun rcv(a: AuthSnapshotV1, f: LifecycleRecovery, rt: DemandAuthRuntime) = rt.binding.identity.let { bound -> listOf(
        "stopped" to a.authStopped, "bound" to (bound != null),
        "owner" to (bound == null || f.identity.ownerUid == bound.ownerUid), "generation" to (bound == null || f.identity.authGeneration == bound.authGeneration),
        "live" to (rt.liveIdentity == f.identity), "fetchAfterStart" to (f.fetchStartedOrder > rt.binding.startedOrder),
        "fetchAfterState" to (f.fetchStartedOrder > a.authStateOrder), "recoveredAfterStop" to (f.recoveredOrder > a.authStopAppliedOrder),
        "episode" to (f.episode > rt.consumedRecoveryEpisode)) }
    private fun rcvNo(id: String, only: String, a: AuthSnapshotV1 = auth, f: LifecycleRecovery = rec, rt: DemandAuthRuntime = F.runtime(recovery = f)) =
        neg(id, rcv(auth, rec, F.runtime(recovery = rec)), DemandAuthBoundary.recovery(auth, rec, F.runtime(recovery = rec)), rcv(a, f, rt), only,
            DemandAuthBoundary.recovery(a, f, rt))
    @Test fun A05a() = rcvNo("Z.bd.A05a", "stopped", a = auth.copy(authStopped = false))
    @Test fun A05b() = rcvNo("Z.bd.A05b", "bound", rt = F.runtime(binding = binding.copy(identity = null)))
    @Test fun A05c() { val f = rec.copy(identity = F.identity.copy(ownerUid = "B")); rcvNo("Z.bd.A05c", "owner", f = f, rt = F.runtime(live = f.identity)) }
    @Test fun A05d() { val f = rec.copy(identity = F.identity.copy(authGeneration = 3)); rcvNo("Z.bd.A05d", "generation", f = f, rt = F.runtime(live = f.identity)) }
    @Test fun A05e() = rcvNo("Z.bd.A05e", "live", rt = F.runtime(live = F.identity.copy(authGeneration = 3)))
    @Test fun A05f() = rcvNo("Z.bd.A05f", "fetchAfterStart", rt = F.runtime(binding = binding.copy(startedOrder = 12)))
    @Test fun A05g() = rcvNo("Z.bd.A05g", "fetchAfterState", f = rec.copy(fetchStartedOrder = 10))
    @Test fun A05h() = rcvNo("Z.bd.A05h", "recoveredAfterStop", f = rec.copy(recoveredOrder = 20))
    @Test fun A06() = rcvNo("Z.bd.A06", "episode", rt = F.runtime(consumed = 1))

    // closure — DF:191–196 (A07a–c)
    private val clo get() = LifecycleBindingClosure(auth, true, setOf("q"), setOf("q"), 5)
    private fun cl(a: AuthSnapshotV1, c: LifecycleBindingClosure) = listOf(
        "closed" to c.entriesClosed, "joined" to (c.joinedWork == c.capturedWork), "scope" to (c.scope == a))
    private fun clNo(id: String, only: String, c: LifecycleBindingClosure) =
        neg(id, cl(auth, clo), DemandAuthBoundary.closure(auth, clo), cl(auth, c), only, DemandAuthBoundary.closure(auth, c))
    @Test fun A07a() = clNo("Z.bd.A07a", "closed", clo.copy(entriesClosed = false))
    @Test fun A07b() = clNo("Z.bd.A07b", "joined", clo.copy(joinedWork = emptySet()))
    @Test fun A07c() = clNo("Z.bd.A07c", "scope", clo.copy(scope = auth.copy(binding = 2)))

    // initialization — DF:197–206 (A12a–d · A12identity · A08 · A12owner); owner is defined on a nonnull identity.
    private fun ini(b: LifecycleBinding, rt: DemandAuthRuntime, old: AuthSnapshotV1?) = listOf(
        "startEvent" to (b.startEventId != null), "identity" to (b.identity != null), "live" to (b.identity == rt.liveIdentity),
        "runtime" to (b == rt.binding), "noExisting" to (old == null), "fresh" to (b.acceptedAuthOrder == 0L),
        "owner" to (b.identity == null || b.identity.ownerUid == b.executor.ownerUid))
    private fun iniNo(id: String, only: String, b: LifecycleBinding = binding, rt: DemandAuthRuntime = F.runtime(binding = b), old: AuthSnapshotV1? = null) =
        neg(id, ini(binding, F.runtime(binding = binding), null), DemandAuthBoundary.initialization(binding, F.runtime(binding = binding), null),
            ini(b, rt, old), only, DemandAuthBoundary.initialization(b, rt, old))
    @Test fun A12a() = iniNo("Z.bd.A12a", "startEvent", b = binding.copy(startEventId = null))
    @Test fun A12identity() = iniNo("Z.bd.A12identity", "identity", b = binding.copy(identity = null), rt = F.runtime(binding = binding.copy(identity = null), live = null))
    @Test fun A12b() = iniNo("Z.bd.A12b", "live", rt = F.runtime(live = F.identity.copy(authGeneration = 3)))
    @Test fun A12c() = iniNo("Z.bd.A12c", "runtime", rt = F.runtime(binding = binding.copy(executor = binding.executor.copy(binding = 4))))
    @Test fun A12d() = iniNo("Z.bd.A12d", "noExisting", old = auth)
    @Test fun A08() = iniNo("Z.bd.A08", "fresh", b = binding.copy(acceptedAuthOrder = 10))
    @Test fun A12owner() = iniNo("Z.bd.A12owner", "owner", b = binding.copy(executor = binding.executor.copy(ownerUid = "B")))

    // seconds · floorOrigin — DF:207–215 (A15negative · A15a · A15b)
    private fun sec(s: Long?) = listOf("nonNegative" to (s == null || s >= 0), "noOverflow" to (s == null || s <= Long.MAX_VALUE / 1000))
    @Test fun A15negative() = neg("Z.bd.A15negative", sec(30), DemandAuthBoundary.seconds(30), sec(-1), "nonNegative", DemandAuthBoundary.seconds(-1))
    @Test fun A15a() = neg("Z.bd.A15a", sec(Long.MAX_VALUE / 1000), DemandAuthBoundary.seconds(Long.MAX_VALUE / 1000), sec(Long.MAX_VALUE / 1000 + 1),
        "noOverflow", DemandAuthBoundary.seconds(Long.MAX_VALUE / 1000 + 1))
    private fun fo(d: AcceptedQueryDecision) = listOf("origin" to (d.floorOrigin == d.query.order.origin))
    @Test fun A15b() = neg("Z.bd.A15b", fo(F.decision()), DemandAuthBoundary.floorOrigin(F.decision()),
        fo(F.decision(origin = LifetimeId("old"))), "origin", DemandAuthBoundary.floorOrigin(F.decision(origin = LifetimeId("old"))))
}
