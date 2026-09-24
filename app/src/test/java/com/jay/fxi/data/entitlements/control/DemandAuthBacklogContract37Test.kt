package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirty-seventh file — writer connections (eligibility layer, DemandAuthTransition.eligibility) for the
 * boundaries whose direct boundaries are in C36, part 1: the decision-bearing writers (SETTLE_QUERY · UPDATE_AUTH Answer).
 *  - after (DT:76, Q06a·Q06b) — both writers
 *  - consumes (DT:84, Q07·Q08a–c·Q09) — SETTLE_QUERY only (evaluated per removed REQUEST; UPDATE_AUTH removes none)
 *  - answer scope (DT:86, A01a–d) — both writers, evaluated when the guard has an AUTH
 * Each method: the twin (same writer, input differing only in the target) has empty TV writer gates, eligibility and common
 * premises and reaches Confirm; the negative input names exactly the target TV item as false; the role is decide ≠ Confirm.
 * A02 (answerOrder) and A03 (authChange) are not here: at the writer the builder already leaves the AUTH unchanged when
 * the answer order does not advance (DP:171), so W.answerChange rejects too, and the after-AUTH comes only from the builder.
 */
class DemandAuthBacklogContract37Test {
    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(runtime), false, false)
    }
    private fun vector(plan: DemandAuthPlan, raw: Preferences, rt: DemandAuthRuntime, eligibilityFalse: String?) {
        F.schema(raw)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("truth vector: writer gates", emptySet<String>(), V.falses(V.decideGates(plan, F.context(rt), raw)))
        assertEquals("truth vector: eligibility", setOfNotNull(eligibilityFalse), V.falses(V.eligibility(plan, rt, raw)))
        assertEquals("truth vector: common premises", emptySet<String>(), V.falses(V.commonPremises(plan, raw, "command")))
    }
    private class Case(val plan: DemandAuthPlan, val raw: Preferences, val rt: DemandAuthRuntime)
    private fun check(id: String, only: String, twin: Case, bad: Case) {
        vector(twin.plan, twin.raw, twin.rt, null)
        assertTrue("positive twin: the input differing only in the target reaches Confirm", decide(twin.plan, twin.raw, twin.rt) is RecordTransactionDecision.Confirm)
        vector(bad.plan, bad.raw, bad.rt, only)
        assertFalse(F.eligible(id), decide(bad.plan, bad.raw, bad.rt) is RecordTransactionDecision.Confirm)
    }
    private enum class W { SETTLE, ANSWER }
    private fun case(w: W, d: AcceptedQueryDecision = F.decision(), g: ControlNode = F.guard(), r: ControlNode = F.request(),
        rt: DemandAuthRuntime = F.runtime(registrations = listOf(d.registration))) =
        if (w == W.SETTLE) Case(F.plan(d, g, retry = null, settle = true, removes = listOf(r)), F.raw(g, r), rt)
        else Case(F.plan(d, g, retry = null), F.raw(g), rt)

    // after — Q06a generation · Q06b fence (the decision's before and after fences stay equal, so no namespace proof is needed)
    private val otherFence get() = F.fence.copy(krxCapabilityEpoch = "other")
    private fun fenceDecision() = F.query.copy(fence = otherFence).let { q -> F.decision(q = q, before = otherFence, after = otherFence) }
    @Test fun Q06a_settle() = check("Z.wr.Q06a.settle", "after.generation", case(W.SETTLE), case(W.SETTLE, rt = F.runtime(generation = 6)))
    @Test fun Q06a_answer() = check("Z.wr.Q06a.answer", "after.generation", case(W.ANSWER), case(W.ANSWER, rt = F.runtime(generation = 6)))
    @Test fun Q06b_settle() = check("Z.wr.Q06b.settle", "after.fence", case(W.SETTLE), case(W.SETTLE, d = fenceDecision()))
    @Test fun Q06b_answer() = check("Z.wr.Q06b.answer", "after.fence", case(W.ANSWER), case(W.ANSWER, d = fenceDecision()))

    // consumes — SETTLE_QUERY (the twin is the stable consuming outcome with the same REQUEST)
    private fun settleOutcome(id: String, only: String, o: EntitlementsOutcome, r: LifecycleReapproval = LifecycleReapproval.NOT_REQUIRED) =
        check(id, only, case(W.SETTLE), case(W.SETTLE, d = F.decision(outcome = o, reapproval = r)))
    @Test fun Q07_settle() = settleOutcome("Z.wr.Q07.settle", "remove[r].reapproval", EntitlementsOutcome.StableInactive(false), LifecycleReapproval.BLOCKED)
    @Test fun Q08a_settle() = settleOutcome("Z.wr.Q08a.settle", "remove[r].settles", EntitlementsOutcome.Pending(false, 30))
    @Test fun Q08b_settle() = settleOutcome("Z.wr.Q08b.settle", "remove[r].settles", EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION))
    @Test fun Q08c_settle() = settleOutcome("Z.wr.Q08c.settle", "remove[r].settles", EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT))
    @Test fun Q09_settle() = settleOutcome("Z.wr.Q09.settle", "remove[r].settles", EntitlementsOutcome.KrxEntitlementRequired)

    // answer scope — A01a–d (the guard's AUTH scope differs from the binding in one field)
    private fun scope(w: W, id: String, only: String, a: AuthSnapshotV1) = check(id, only, case(w), case(w, g = F.guard(auth = a)))
    @Test fun A01a_settle() = scope(W.SETTLE, "Z.wr.A01a.settle", "answer.scopeOwner", F.auth.copy(ownerUid = "B"))
    @Test fun A01b_settle() = scope(W.SETTLE, "Z.wr.A01b.settle", "answer.scopeGeneration", F.auth.copy(authGeneration = 3))
    @Test fun A01c_settle() = scope(W.SETTLE, "Z.wr.A01c.settle", "answer.scopeBinding", F.auth.copy(binding = 4))
    @Test fun A01d_settle() = scope(W.SETTLE, "Z.wr.A01d.settle", "answer.scopeOrigin", F.auth.copy(originLifetimeId = LifetimeId("old")))
    @Test fun A01a_answer() = scope(W.ANSWER, "Z.wr.A01a.answer", "answer.scopeOwner", F.auth.copy(ownerUid = "B"))
    @Test fun A01b_answer() = scope(W.ANSWER, "Z.wr.A01b.answer", "answer.scopeGeneration", F.auth.copy(authGeneration = 3))
    @Test fun A01c_answer() = scope(W.ANSWER, "Z.wr.A01c.answer", "answer.scopeBinding", F.auth.copy(binding = 4))
    @Test fun A01d_answer() = scope(W.ANSWER, "Z.wr.A01d.answer", "answer.scopeOrigin", F.auth.copy(originLifetimeId = LifetimeId("old")))
}
