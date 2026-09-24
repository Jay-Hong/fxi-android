package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, thirty-ninth file — writer connections at the **builder preparation layer** (DemandAuthPlan Builder),
 * following STEP3ZK 판정 ⑤: a role method (normal input's independent vector + preparation success + Confirm twin →
 * negative input's independent vector → decide ≠ Confirm) and a **separate** classification method for the literal
 * preparation failure (CLASSIFICATION_ONLY), so the classification never masks the role.
 *  - floorOrigin (DP:155 `RequiredDecisionEffectMissing`), A15b — SETTLE_QUERY and UPDATE_AUTH Answer
 *  - order issuance (DP:120–124, refusal DP:122 `OrderExhausted`), Q16b at REBIND_REQUESTS · Q16c at the SETTLE_QUERY successor
 *  - rebind (DP:129 `InvalidRebind`), Q11a–c at REBIND_REQUESTS — the eligibility layer re-checks the same predicate
 *    (DT:102); a bypass of either layer alone is rejected by the other (pair, recorded in the request), so this role is a
 *    connection of the shared predicate, not of one layer.
 * seconds (DP:154, A15negative·A15a) is not here: with the guard bypassed, DP:160 refuses the same input with the same
 * literal and requiredEffects re-checks it (DT:181) — masked; its direct boundary is C36.
 */
class DemandAuthBacklogContract39Test {
    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(runtime), false, false)
    }
    private fun twin(plan: DemandAuthPlan, raw: Preferences, rt: DemandAuthRuntime) {
        F.schema(raw)
        assertNull("positive twin must be prepared", plan.preparationFailure)
        assertEquals("positive twin truth vector: eligibility", emptySet<String>(), V.falses(V.eligibility(plan, rt, raw)))
        assertTrue("positive twin: the input differing only in the target reaches Confirm", decide(plan, raw, rt) is RecordTransactionDecision.Confirm)
    }

    // floorOrigin — the decision's floor origin differs from its query origin (answer-builder inputs otherwise valid)
    private fun answerInputs(d: AcceptedQueryDecision) = listOf(
        "seconds" to (DemandAuthBoundaryLiteral.secondsOk(d.outcome)), "floorOrigin" to (d.floorOrigin == d.query.order.origin),
        "minDelay" to (d.decisionMinDelayMillis >= 0), "mergeBoot" to (d.mergeNow.bootId != "" && d.mergeNow.elapsedMillis >= 0))
    private object DemandAuthBoundaryLiteral {
        fun secondsOk(o: EntitlementsOutcome): Boolean {
            val s = when (o) { is EntitlementsOutcome.Pending -> o.retryAfterSeconds; is EntitlementsOutcome.Indeterminate -> o.retryAfterSeconds; else -> null }
            return s == null || (s >= 0 && s <= Long.MAX_VALUE / 1000)
        }
    }
    private enum class W { SETTLE, ANSWER }
    private fun plan(w: W, d: AcceptedQueryDecision, g: ControlNode, r: ControlNode) =
        if (w == W.SETTLE) F.plan(d, g, retry = null, settle = true, removes = listOf(r)) else F.plan(d, g, retry = null)
    private fun raw(w: W, g: ControlNode, r: ControlNode) = if (w == W.SETTLE) F.raw(g, r) else F.raw(g)
    private fun floorOrigin(w: W, id: String) {
        val g = F.guard(); val r = F.request(); val ok = F.decision(); val bad = F.decision(origin = LifetimeId("old"))
        assertEquals("truth vector: normal answer inputs", emptySet<String>(), V.falses(answerInputs(ok)))
        twin(plan(w, ok, g, r), raw(w, g, r), F.runtime())
        assertEquals("truth vector: only the floor origin differs", setOf("floorOrigin"), V.falses(answerInputs(bad)))
        assertFalse(F.eligible(id), decide(plan(w, bad, g, r), raw(w, g, r), F.runtime()) is RecordTransactionDecision.Confirm)
    }
    @Test fun A15b_settle() = floorOrigin(W.SETTLE, "Z.bl.A15b.settle")
    @Test fun A15b_answer() = floorOrigin(W.ANSWER, "Z.bl.A15b.answer")
    @Test fun A15b_classification() = assertEquals("classification: A15b preparation failure", "RequiredDecisionEffectMissing",
        F.plan(F.decision(origin = LifetimeId("old")), F.guard(), retry = null).preparationFailure)

    // order issuance exhaustion — the source already issued Long.MAX_VALUE (literal initial order)
    @Test fun Q16b_rebind() {
        val old = F.request(binding = 2, order = 50)
        twin(DemandAuthPlan.rebind(listOf(old), F.binding, LifecycleOrderSource(F.life, 21)), F.raw(old), F.runtime())
        val exhausted = LifecycleOrderSource(F.life, Long.MAX_VALUE)
        assertEquals("truth vector: only the source is exhausted (rebind predicates hold)", listOf(true, true, true),
            demand(old)!!.let { listOf(it.ownerUid == F.binding.executor.ownerUid, it.binding != F.binding.executor.binding, RefreshIntent.FORCE_PREMIUM >= it.intent) })
        assertFalse(F.eligible("Z.bl.Q16b.rebind"), decide(DemandAuthPlan.rebind(listOf(old), F.binding, exhausted), F.raw(old), F.runtime()) is RecordTransactionDecision.Confirm)
    }
    @Test fun Q16b_classification() = assertEquals("classification: Q16b preparation failure", "OrderExhausted",
        DemandAuthPlan.rebind(listOf(F.request(binding = 2, order = 50)), F.binding, LifecycleOrderSource(F.life, Long.MAX_VALUE)).preparationFailure)
    private fun settleRetry(source: LifecycleOrderSource): Pair<DemandAuthPlan, Preferences> {
        val g = F.guard(F.auth.copy(authStopped = false)); val r = F.request()
        val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        return DemandAuthPlan.settle(listOf(r), g, null, F.binding, d, source, "g-new", "r-new") to F.raw(g, r)
    }
    @Test fun Q16c_settle() {
        val (ok, okRaw) = settleRetry(LifecycleOrderSource(F.life, 21))
        twin(ok, okRaw, F.runtime())
        val (bad, badRaw) = settleRetry(LifecycleOrderSource(F.life, Long.MAX_VALUE))
        assertEquals("truth vector: the settle inputs are the twin's except the source", okRaw, badRaw)
        assertFalse(F.eligible("Z.bl.Q16c.settle"), decide(bad, badRaw, F.runtime()) is RecordTransactionDecision.Confirm)
    }
    @Test fun Q16c_classification() = assertEquals("classification: Q16c preparation failure", "OrderExhausted",
        settleRetry(LifecycleOrderSource(F.life, Long.MAX_VALUE)).first.preparationFailure)

    // rebind at REBIND_REQUESTS — shared predicate of the builder (DP:129) and eligibility (DT:102)
    private fun reb(old: DemandV1, intent: RefreshIntent) = listOf(
        "owner" to (old.ownerUid == F.binding.executor.ownerUid),
        "needed" to !(old.binding == F.binding.executor.binding && old.raisedAt.origin == F.binding.executor.originLifetimeId),
        "intent" to (intent >= old.intent))
    private fun rebind(id: String, only: String, bad: ControlNode, intent: RefreshIntent?) {
        val ok = F.request(binding = 2, order = 50)
        assertEquals("truth vector: normal rebind input", emptySet<String>(), V.falses(reb(demand(ok)!!, demand(ok)!!.intent)))
        twin(DemandAuthPlan.rebind(listOf(ok), F.binding, LifecycleOrderSource(F.life, 21)), F.raw(ok), F.runtime())
        val b = demand(bad)!!
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(reb(b, intent ?: b.intent)))
        val plan = DemandAuthPlan.rebind(listOf(bad), F.binding, LifecycleOrderSource(F.life, 21), intent?.let { mapOf(b.id to it) } ?: emptyMap())
        assertFalse(F.eligible(id), decide(plan, F.raw(bad), F.runtime()) is RecordTransactionDecision.Confirm)
    }
    @Test fun Q11a_rebind() = rebind("Z.bl.Q11a.rebind", "owner", F.request(binding = 2, order = 50, owner = "B"), null)
    @Test fun Q11b_rebind() = rebind("Z.bl.Q11b.rebind", "needed", F.request(order = 50), null)
    @Test fun Q11c_rebind() = rebind("Z.bl.Q11c.rebind", "intent", F.request(binding = 2, order = 50), RefreshIntent.FORCE_ENTITLEMENTS)
    @Test fun Q11_classification() = assertEquals("classification: Q11 preparation failure", "InvalidRebind",
        DemandAuthPlan.rebind(listOf(F.request(order = 50)), F.binding, LifecycleOrderSource(F.life, 21)).preparationFailure)
}
