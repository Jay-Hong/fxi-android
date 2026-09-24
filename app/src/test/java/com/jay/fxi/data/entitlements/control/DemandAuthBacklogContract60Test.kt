package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixtieth file — classification of writer common gates whose refusal role is masked downstream.
 * Evidence (probe/mask-probe-c56/run.r1.log): with the decide-level gate bypassed — DemandAuthTransition.kt:29 (required
 * unchanged preimage), ControlLifecycle.kt:211 (command id), LC:214 (CREATE id) — the same negative input is still refused,
 * by the candidate validator (DT:44, Rejected InvalidRequest(RequiredDecisionEffectMissing)) instead of the gate's Conflict.
 * The C56 roles (not Confirm) cannot tell the two apart; these methods state the same premises (the TV names only the
 * target item) and assert the literal classification of the gate itself. (DT:32 guard creation: C58 C_guardCreate_classification.)
 */
class DemandAuthBacklogContract60Test {
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun decide(plan: DemandAuthPlan, before: Preferences): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(), false, false)
    }
    private fun cls(only: String, plan: DemandAuthPlan, raw: Preferences, reason: ConflictReason) {
        F.schema(raw); assertNull("prepared", plan.preparationFailure)
        assertEquals("premise: only the target differs", setOf(only), V.falses(V.decideGates(plan, F.context(), raw)) +
            V.falses(V.eligibility(plan, F.runtime(), raw)) + V.falses(V.commonPremises(plan, raw, "command")))
        val result = negative(decide(plan, raw))
        assertTrue("classification: the gate's own conflict", result is ControlStoreResult.Conflict && result.reason == reason)
    }
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    private fun answer(retry: ControlNode? = null) = F.plan(pending, F.guard(), retry)

    @Test fun W_unchangedPreimage_classification() = cls("unchanged[r].preimage", answer(F.request()),
        F.raw(F.guard(), F.request(order = 6)), ConflictReason.TargetChanged)
    @Test fun W_commandIdUsed_classification() = cls("lc.commandIdFree", answer(),
        F.raw(F.guard()).toMutablePreferences().apply {
            this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[" + ControlLifecycleEvidenceFixtures.wire(command = "command") + "]" }.toPreferences(),
        ConflictReason.OperationIdCollision)
    @Test fun W_createIdTaken_classification() = cls("target[r-new].absent", answer(),
        F.raw(F.guard(), F.request(id = "r-new", owner = "B")), ConflictReason.IdCollision)
}
