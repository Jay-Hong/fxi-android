package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirty-fourth file — pilot of the Z.fixturePremises closure method (STEP3ZJ r5 판정 ⑧) on one boundary:
 * the request–query relation (DemandAuthBoundary.request, DemandAuthFacts.kt:121–128; design §9.2 r3:560–562 Q02a–c ·
 * Q03a–b · Q04a–b and r3:571 Q13). Each design sub-condition gets its own fixture at two places:
 *  - direct boundary: an independent truth vector written here (no production call) is all true for the normal fixture and
 *    false exactly at the target for the negative one; the same method also asserts the production literal result of the
 *    normal fixture (positive twin) before the role;
 *  - settle writer connection (DemandAuthTransition.kt:78–84 W.request inside decide): the TV writer gates, eligibility and
 *    common premises (DemandAuthTruthVector) name exactly `remove[r].<target>` as false; the twin is the same plan with
 *    the normal REQUEST reaching Confirm.
 * Q13 runs the rebind handover first, then settles the rebound REQUEST with the query started before the handover
 * (negative) and with a query started after it (positive contrast).
 */
class DemandAuthBacklogContract34Test {
    private val r0 get() = demand(F.request())!!
    private val q0 get() = F.query
    private fun vec(r: DemandV1, q: StartedQueryV1) = listOf(
        "owner" to (r.ownerUid == q.fence.ownerUid), "binding" to (r.binding == q.binding),
        "origin" to (r.raisedAt.origin == q.order.origin), "start" to (q.order.value > r.raisedAt.value),
        "intent" to (q.intent >= r.intent))

    private fun direct(id: String, only: String, r: DemandV1, q: StartedQueryV1) {
        assertEquals("truth vector: normal request–query fixture", emptySet<String>(), V.falses(vec(r0, q0)))
        assertTrue("positive twin: the normal REQUEST is settled by the normal query", DemandAuthBoundary.request(r0, q0))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vec(r, q)))
        assertFalse(F.eligible(id), DemandAuthBoundary.request(r, q))
    }
    @Test fun Q02a_direct() = direct("Z.req.Q02a", "owner", r0.copy(ownerUid = "B"), q0)
    @Test fun Q02b_direct() = direct("Z.req.Q02b", "binding", r0.copy(binding = 4), q0)
    @Test fun Q02c_direct() = direct("Z.req.Q02c", "origin", r0.copy(raisedAt = EventOrderV1(LifetimeId("old"), 4)), q0)
    @Test fun Q03a_direct() = direct("Z.req.Q03a", "start", r0.copy(raisedAt = EventOrderV1(F.life, 21)), q0)
    @Test fun Q03b_direct() = direct("Z.req.Q03b", "start", r0.copy(raisedAt = EventOrderV1(F.life, 22)), q0)
    @Test fun Q04a_direct() = direct("Z.req.Q04a", "intent", r0.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS),
        q0.copy(intent = RefreshIntent.IF_STALE))
    @Test fun Q04b_direct() = direct("Z.req.Q04b", "intent", r0, q0.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS))

    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(runtime), false, false)
    }
    private fun settleVector(plan: DemandAuthPlan, raw: Preferences, rt: DemandAuthRuntime, eligibilityFalse: String?) {
        F.schema(raw)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("truth vector: writer gates", emptySet<String>(), V.falses(V.decideGates(plan, F.context(rt), raw)))
        assertEquals("truth vector: eligibility", setOfNotNull(eligibilityFalse), V.falses(V.eligibility(plan, rt, raw)))
        assertEquals("truth vector: common premises", emptySet<String>(), V.falses(V.commonPremises(plan, raw, "command")))
    }
    /** [twin] is the REQUEST that differs from [bad] only in the target (default: the fixture REQUEST). */
    private fun writer(id: String, only: String, bad: ControlNode, q: StartedQueryV1 = q0, twin: ControlNode = F.request()) {
        val d = F.decision(q = q); val rt = F.runtime(registrations = listOf(d.registration)); val g = F.guard()
        val good = F.plan(d, g, retry = null, settle = true, removes = listOf(twin))
        settleVector(good, F.raw(g, twin), rt, null)
        assertTrue("positive twin: the REQUEST differing only in the target is settled by the writer", decide(good, F.raw(g, twin), rt) is RecordTransactionDecision.Confirm)
        val plan = F.plan(d, g, retry = null, settle = true, removes = listOf(bad))
        settleVector(plan, F.raw(g, bad), rt, "remove[r].$only")
        assertFalse(F.eligible(id), decide(plan, F.raw(g, bad), rt) is RecordTransactionDecision.Confirm)
    }
    @Test fun Q02a_writer() = writer("Z.req.Q02a.writer", "owner", F.request(owner = "B"))
    @Test fun Q02b_writer() = writer("Z.req.Q02b.writer", "binding", F.request(binding = 4))
    @Test fun Q02c_writer() = writer("Z.req.Q02c.writer", "origin", F.request(origin = LifetimeId("old")))
    @Test fun Q03a_writer() = writer("Z.req.Q03a.writer", "start", F.request(order = 21))
    @Test fun Q03b_writer() = writer("Z.req.Q03b.writer", "start", F.request(order = 22))
    @Test fun Q04a_writer() = writer("Z.req.Q04a.writer", "intent", F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS),
        q0.copy(intent = RefreshIntent.IF_STALE), twin = F.request(intent = RefreshIntent.IF_STALE))
    @Test fun Q04b_writer() = writer("Z.req.Q04b.writer", "intent", F.request(), q0.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS),
        twin = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS))

    /** Q13 — the rebind stores the REQUEST with a fresh order; a query started before that store cannot settle it. */
    @Test fun Q13_writer() {
        val old = F.request(binding = 2, order = 4)
        val (_, rebound) = F.apply(DemandAuthPlan.rebind(listOf(old), F.binding, LifecycleOrderSource(F.life, 21)), F.raw(old))
        val node = (F.read(rebound).locations("r").single().second as ControlEntryRead.Interpreted).original
        val raised = demand(node)!!.raisedAt.value
        assertTrue("fixture: the old query started before the handover order", q0.order.value < raised)
        val later = q0.copy(order = EventOrderV1(F.life, raised + 1))
        val dNew = F.decision(q = later); val rtNew = F.runtime(registrations = listOf(dNew.registration)); val g = F.guard()
        val fresh = F.plan(dNew, g, retry = null, settle = true, removes = listOf(node))
        val freshRaw = F.raw(g, node)
        settleVector(fresh, freshRaw, rtNew, null)
        assertTrue("positive twin: a query started after the handover settles it", decide(fresh, freshRaw, rtNew) is RecordTransactionDecision.Confirm)
        val d = F.decision(q = q0); val rt = F.runtime(registrations = listOf(d.registration))
        val stale = F.plan(d, g, retry = null, settle = true, removes = listOf(node))
        settleVector(stale, freshRaw, rt, "remove[r].start")
        assertFalse(F.eligible("Z.req.Q13.writer"), decide(stale, freshRaw, rt) is RecordTransactionDecision.Confirm)
    }
}
