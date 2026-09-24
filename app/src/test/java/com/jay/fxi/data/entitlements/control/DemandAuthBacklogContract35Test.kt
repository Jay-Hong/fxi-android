package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirty-fifth file — the closure method (C34 pilot, STEP3ZJ r6) on the query acceptance boundary
 * (DemandAuthBoundary.acceptance, DemandAuthFacts.kt:141–150; design §9.2 r3:559 Q01 · r3:563 Q05a–e · r3:573 Q15a–b).
 *  - direct boundary: a truth vector written here in the production order (source · registered · answered ·
 *    beforeGeneration · beforeFence · answeredOwner · answeredLive · answeredBound; the last three compare the possibly
 *    null answer as the production does) — all true with the literal production result for the normal fixture, then
 *    exactly the target false before the role;
 *  - writer connection: acceptance is evaluated in eligibility only when the plan carries a decision (DT:71–72), i.e. for
 *    SETTLE_QUERY and the UPDATE_AUTH Answer event; REBIND_REQUESTS and END_AUTH_BINDING carry none (not applicable).
 *    TV writer gates, eligibility and common premises name exactly the target `q.*` item; the twin is the same plan with
 *    the normal decision reaching Confirm.
 * Q15a is direct only: its non-target comparisons hold for a null answer only with a null-owner query fence, which the
 * writer rejects first at W.queryOwner (DT:73). Q15b (a null-owner REQUEST) is the request boundary's owner predicate
 * with the null case (direct and settle writer).
 */
class DemandAuthBacklogContract35Test {
    private fun vec(d: AcceptedQueryDecision, rt: DemandAuthRuntime) = listOf(
        "source" to (d.source == LifecycleQuerySource.REGISTERED_QUERY), "registered" to (d.registration in rt.registrations),
        "answered" to (d.answeredAs != null), "beforeGeneration" to (d.acceptedBeforeGeneration == d.query.generation),
        "beforeFence" to (d.acceptedBeforeFence == d.query.fence), "answeredOwner" to (d.answeredAs?.ownerUid == d.query.fence.ownerUid),
        "answeredLive" to (d.answeredAs == rt.liveIdentity), "answeredBound" to (d.answeredAs == d.query.boundIdentity))
    private fun reg(q: StartedQueryV1) = listOf(LifecycleQueryRegistration("query-21", q))

    private fun direct(id: String, only: String, d: AcceptedQueryDecision, rt: DemandAuthRuntime) {
        assertEquals("truth vector: normal acceptance fixture", emptySet<String>(), V.falses(vec(F.decision(), F.runtime())))
        assertTrue("positive twin: the normal decision is accepted", DemandAuthBoundary.acceptance(F.decision(), F.runtime()))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vec(d, rt)))
        assertFalse(F.eligible(id), DemandAuthBoundary.acceptance(d, rt))
    }
    @Test fun Q01_direct() = direct("Z.acc.Q01", "registered", F.decision(), F.runtime(registrations = emptyList()))
    @Test fun Q01topic_direct() = direct("Z.acc.Q01topic", "source", F.decision(source = LifecycleQuerySource.TOPIC), F.runtime())
    @Test fun Q01hold_direct() = direct("Z.acc.Q01hold", "source", F.decision(source = LifecycleQuerySource.RESTORED_HOLD), F.runtime())
    @Test fun Q05a_direct() = direct("Z.acc.Q05a", "beforeGeneration", F.decision(beforeGeneration = 6), F.runtime())
    @Test fun Q05b_direct() = direct("Z.acc.Q05b", "beforeFence", F.decision(before = F.fence.copy(userAccessEpoch = "other")), F.runtime())
    @Test fun Q05c_direct() { val other = F.identity.copy(ownerUid = "B"); val q = F.query.copy(boundIdentity = other)
        direct("Z.acc.Q05c", "answeredOwner", F.decision(q = q, answered = other), F.runtime(live = other, registrations = reg(q))) }
    @Test fun Q05d_direct() = direct("Z.acc.Q05d", "answeredLive", F.decision(), F.runtime(live = F.identity.copy(authGeneration = 3)))
    @Test fun Q05e_direct() { val q = F.query.copy(boundIdentity = F.identity.copy(authGeneration = 3))
        direct("Z.acc.Q05e", "answeredBound", F.decision(q = q), F.runtime(registrations = reg(q))) }
    @Test fun Q15a_direct() { val q = F.query.copy(boundIdentity = null, fence = F.fence.copy(ownerUid = null))
        val d = F.decision(answered = null, q = q)
        direct("Z.acc.Q15a", "answered", d, F.runtime(live = null, registrations = listOf(d.registration))) }

    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(runtime), false, false)
    }
    private fun writerVector(plan: DemandAuthPlan, raw: Preferences, rt: DemandAuthRuntime, eligibilityFalse: String?) {
        F.schema(raw)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("truth vector: writer gates", emptySet<String>(), V.falses(V.decideGates(plan, F.context(rt), raw)))
        assertEquals("truth vector: eligibility", setOfNotNull(eligibilityFalse), V.falses(V.eligibility(plan, rt, raw)))
        assertEquals("truth vector: common premises", emptySet<String>(), V.falses(V.commonPremises(plan, raw, "command")))
    }
    private enum class Writer { SETTLE, ANSWER }
    private fun plan(w: Writer, d: AcceptedQueryDecision, g: ControlNode, r: ControlNode) =
        if (w == Writer.SETTLE) F.plan(d, g, retry = null, settle = true, removes = listOf(r)) else F.plan(d, g, retry = null)
    private fun raw(w: Writer, g: ControlNode, r: ControlNode) = if (w == Writer.SETTLE) F.raw(g, r) else F.raw(g)
    private fun writer(w: Writer, id: String, only: String, d: AcceptedQueryDecision, rt: DemandAuthRuntime) {
        val g = F.guard(); val r = F.request()
        val twin = plan(w, F.decision(), g, r); val twinRt = F.runtime()
        writerVector(twin, raw(w, g, r), twinRt, null)
        assertTrue("positive twin: the normal decision reaches Confirm in the ${w.name} writer", decide(twin, raw(w, g, r), twinRt) is RecordTransactionDecision.Confirm)
        val p = plan(w, d, g, r)
        writerVector(p, raw(w, g, r), rt, only)
        assertFalse(F.eligible(id), decide(p, raw(w, g, r), rt) is RecordTransactionDecision.Confirm)
    }
    private fun both(sub: String, only: String, d: () -> AcceptedQueryDecision, rt: () -> DemandAuthRuntime, w: Writer) =
        writer(w, "Z.acc.$sub.${w.name.lowercase()}", only, d(), rt())
    @Test fun Q01_settle() = both("Q01", "q.registered", { F.decision() }, { F.runtime(registrations = emptyList()) }, Writer.SETTLE)
    @Test fun Q01_answer() = both("Q01", "q.registered", { F.decision() }, { F.runtime(registrations = emptyList()) }, Writer.ANSWER)
    @Test fun Q01topic_settle() = both("Q01topic", "q.source", { F.decision(source = LifecycleQuerySource.TOPIC) }, { F.runtime() }, Writer.SETTLE)
    @Test fun Q01topic_answer() = both("Q01topic", "q.source", { F.decision(source = LifecycleQuerySource.TOPIC) }, { F.runtime() }, Writer.ANSWER)
    @Test fun Q01hold_settle() = both("Q01hold", "q.source", { F.decision(source = LifecycleQuerySource.RESTORED_HOLD) }, { F.runtime() }, Writer.SETTLE)
    @Test fun Q01hold_answer() = both("Q01hold", "q.source", { F.decision(source = LifecycleQuerySource.RESTORED_HOLD) }, { F.runtime() }, Writer.ANSWER)
    @Test fun Q05a_settle() = both("Q05a", "q.beforeGeneration", { F.decision(beforeGeneration = 6) }, { F.runtime() }, Writer.SETTLE)
    @Test fun Q05a_answer() = both("Q05a", "q.beforeGeneration", { F.decision(beforeGeneration = 6) }, { F.runtime() }, Writer.ANSWER)
    // At the writer the query fence (not the decision's before fence) differs, so before == after and no namespace proof is
    // required (changing `before` alone would also make effects.namespaceProof false — a second condition).
    private val fenceQ get() = F.query.copy(fence = F.fence.copy(userAccessEpoch = "other"))
    @Test fun Q05b_settle() = both("Q05b", "q.beforeFence", { F.decision(q = fenceQ, before = F.fence) }, { F.runtime(registrations = reg(fenceQ)) }, Writer.SETTLE)
    @Test fun Q05b_answer() = both("Q05b", "q.beforeFence", { F.decision(q = fenceQ, before = F.fence) }, { F.runtime(registrations = reg(fenceQ)) }, Writer.ANSWER)
    private val otherId get() = F.identity.copy(ownerUid = "B")
    @Test fun Q05c_settle() = both("Q05c", "q.answeredOwner", { F.decision(q = F.query.copy(boundIdentity = otherId), answered = otherId) },
        { F.runtime(live = otherId, registrations = reg(F.query.copy(boundIdentity = otherId))) }, Writer.SETTLE)
    @Test fun Q05c_answer() = both("Q05c", "q.answeredOwner", { F.decision(q = F.query.copy(boundIdentity = otherId), answered = otherId) },
        { F.runtime(live = otherId, registrations = reg(F.query.copy(boundIdentity = otherId))) }, Writer.ANSWER)
    @Test fun Q05d_settle() = both("Q05d", "q.answeredLive", { F.decision() }, { F.runtime(live = F.identity.copy(authGeneration = 3)) }, Writer.SETTLE)
    @Test fun Q05d_answer() = both("Q05d", "q.answeredLive", { F.decision() }, { F.runtime(live = F.identity.copy(authGeneration = 3)) }, Writer.ANSWER)
    private val boundQ get() = F.query.copy(boundIdentity = F.identity.copy(authGeneration = 3))
    @Test fun Q05e_settle() = both("Q05e", "q.answeredBound", { F.decision(q = boundQ) }, { F.runtime(registrations = reg(boundQ)) }, Writer.SETTLE)
    @Test fun Q05e_answer() = both("Q05e", "q.answeredBound", { F.decision(q = boundQ) }, { F.runtime(registrations = reg(boundQ)) }, Writer.ANSWER)

    // Q15b — a null-owner REQUEST against A's eligible answer (request boundary, owner predicate with null).
    @Test fun Q15b_direct() {
        val r0 = demand(F.request())!!; val bad = r0.copy(ownerUid = null)
        assertTrue("positive twin: the owner-A REQUEST is settled by the normal query", DemandAuthBoundary.request(r0, F.query))
        assertEquals("truth vector: only the owner differs", listOf(false, true, true, true, true), listOf(bad.ownerUid == F.query.fence.ownerUid,
            bad.binding == F.query.binding, bad.raisedAt.origin == F.query.order.origin, F.query.order.value > bad.raisedAt.value, F.query.intent >= bad.intent))
        assertFalse(F.eligible("Z.req.Q15b"), DemandAuthBoundary.request(bad, F.query))
    }
    @Test fun Q15b_settle() {
        val d = F.decision(); val rt = F.runtime(); val g = F.guard()
        val twin = F.plan(d, g, retry = null, settle = true, removes = listOf(F.request()))
        writerVector(twin, F.raw(g, F.request()), rt, null)
        assertTrue("positive twin: the owner-A REQUEST is settled by the writer", decide(twin, F.raw(g, F.request()), rt) is RecordTransactionDecision.Confirm)
        val bad = F.request(owner = null)
        val p = F.plan(d, g, retry = null, settle = true, removes = listOf(bad))
        writerVector(p, F.raw(g, bad), rt, "remove[r].owner")
        assertFalse(F.eligible("Z.req.Q15b.settle"), decide(p, F.raw(g, bad), rt) is RecordTransactionDecision.Confirm)
    }
}
