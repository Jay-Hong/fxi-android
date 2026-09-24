package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, forty-seventh file — Q05c (DemandAuthBoundary.acceptance DF:147 `answeredAs.ownerUid == query.fence.ownerUid`;
 * design r3:563 "c 응답 owner … 만 다름") with a fixture that does not change the live/answered/bound identities together
 * (r3:581 "live/answered/bound 세 identity를 한꺼번에 바꾼 입력은 독립 fixture가 아니다" — the C35 Q05c fixture did). Here only
 * the query fence owner is B: the answer, the live identity and the bound identity stay A (Q05d/Q05e hold), the decision's
 * before fence is the query fence (Q05b holds), so only the answered owner differs from the fence owner.
 * Direct boundary: the C35 truth vector (production order DF:142–149) — the single-condition role.
 * Writer connection: these fixtures keep executor/raw/AUTH/REQUEST owners at A while changing the query fence owner to B.
 * Their recorded false sets include q.owner (DT:73), effects.namespaceProof (DT:155–158), and, for SETTLE, remove[r].owner.
 * This is fixture-local masking, not a proof that Q05c cannot be isolated at the writer boundary. Keep the independent
 * writer connection OPEN; the two writer methods record the submitted false sets and refusal and are not role evidence.
 * Direct-boundary evidence remains limited to the Q05c acceptance predicate.
 */
class DemandAuthBacklogContract47Test {
    private fun vec(d: AcceptedQueryDecision, rt: DemandAuthRuntime) = listOf(
        "source" to (d.source == LifecycleQuerySource.REGISTERED_QUERY), "registered" to (d.registration in rt.registrations),
        "answered" to (d.answeredAs != null), "beforeGeneration" to (d.acceptedBeforeGeneration == d.query.generation),
        "beforeFence" to (d.acceptedBeforeFence == d.query.fence), "answeredOwner" to (d.answeredAs?.ownerUid == d.query.fence.ownerUid),
        "answeredLive" to (d.answeredAs == rt.liveIdentity), "answeredBound" to (d.answeredAs == d.query.boundIdentity))
    private val q get() = F.query.copy(fence = F.fence.copy(ownerUid = "B"))
    private fun rt() = F.runtime(registrations = listOf(LifecycleQueryRegistration("query-21", q)))

    @Test fun Q05c_fenceOwner_direct() {
        assertEquals("truth vector: normal acceptance fixture", emptySet<String>(), V.falses(vec(F.decision(), F.runtime())))
        assertTrue("positive twin: the normal decision is accepted", DemandAuthBoundary.acceptance(F.decision(), F.runtime()))
        val d = F.decision(q = q)
        assertEquals("fixture: the decision's before fence is the query fence", q.fence, d.acceptedBeforeFence)
        assertEquals("fixture: answer, live and bound identities are all A", listOf(F.identity, F.identity), listOf(rt().liveIdentity, q.boundIdentity))
        assertEquals("truth vector: only the target differs", setOf("answeredOwner"), V.falses(vec(d, rt())))
        assertFalse(F.eligible("Z.acc.Q05c.fenceOwner"), DemandAuthBoundary.acceptance(d, rt()))
    }

    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(runtime), false, false)
    }
    private enum class Writer { SETTLE, ANSWER }
    private fun plan(w: Writer, d: AcceptedQueryDecision, g: ControlNode, r: ControlNode) =
        if (w == Writer.SETTLE) F.plan(d, g, retry = null, settle = true, removes = listOf(r)) else F.plan(d, g, retry = null)
    private fun raw(w: Writer, g: ControlNode, r: ControlNode) = if (w == Writer.SETTLE) F.raw(g, r) else F.raw(g)
    /** Records the writer-layer falses for the fence-owner fixture (expected set written literally from the source). */
    private fun writer(w: Writer, expected: Set<String>) {
        val g = F.guard(); val r = F.request()
        val twin = plan(w, F.decision(), g, r)
        assertEquals("truth vector: twin eligibility", emptySet<String>(), V.falses(V.eligibility(twin, F.runtime(), raw(w, g, r))))
        assertTrue("positive twin: the normal decision reaches Confirm", decide(twin, raw(w, g, r), F.runtime()) is RecordTransactionDecision.Confirm)
        val p = plan(w, F.decision(q = q), g, r)
        val falses = V.falses(V.decideGates(p, F.context(rt()), raw(w, g, r))) + V.falses(V.eligibility(p, rt(), raw(w, g, r))) +
            V.falses(V.commonPremises(p, raw(w, g, r), "command"))
        assertEquals("record: writer-layer conditions false for the fence-owner fixture", expected, falses)
        assertFalse("record: the ${w.name.lowercase()} writer refuses the fence-owner fixture", decide(p, raw(w, g, r), rt()) is RecordTransactionDecision.Confirm)
    }
    @Test fun Q05c_fenceOwner_settle() = writer(Writer.SETTLE, setOf("q.answeredOwner", "q.owner", "effects.namespaceProof", "remove[r].owner"))
    @Test fun Q05c_fenceOwner_answer() = writer(Writer.ANSWER, setOf("q.answeredOwner", "q.owner", "effects.namespaceProof"))
}
