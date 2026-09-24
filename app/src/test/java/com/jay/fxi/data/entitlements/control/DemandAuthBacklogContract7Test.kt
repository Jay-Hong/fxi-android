package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, seventh file: complements existing DemandAuth contracts at explicit output boundaries.
 * Before C7, the LC:74–76 receipt-ID mutants had role failures only on RecoverHold/RemoveEmptyGuard paths;
 * the LC:284 observation mutant and DT:22 conflict-ID mutant were NOT_CAUGHT;
 * the DT:49 application-ID mutant produced only an exception.
 * C5 DP_descriptor:348 already directly asserts the three null descriptor fields with a plain message.
 * The LC:46.55 and LC:50.41 default-value mutants also had existing DemandAuth-path role failures;
 * LC:49.51 had none. C7 adds a direct role assertion for these descriptor fields.
 *
 * Design basis: §3.3 the receipt carries current storage observations of the fixed targets and required-unchanged
 * effects (no grant, no tombstone); §3.1 the descriptor fixes the operation, transition, targets and the one executing
 * writer; a DemandAuth descriptor names no namespace postcondition and no other writer. Expected values come from the
 * fixture's fixed facts, not from the code under test.
 */
class DemandAuthBacklogContract7Test {
    private val g = F.guard()
    private val r = F.request()
    private val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
    private val orders get() = LifecycleOrderSource(F.life, 21)

    private fun decide(p: DemandAuthPlan, raw: Preferences, context: AttemptContext? = F.context()): RecordTransactionDecision<*> {
        val c = F.command(p)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        return ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(raw), context, false, false)
    }
    private fun positive(d: RecordTransactionDecision<*>) =
        ((d as? RecordTransactionDecision.Confirm<*>)?.value as? ControlRecordStore.Outcome.Positive)

    // RC.settle — LC:74–76. A SETTLE that consumes r, creates the follow-up REQUEST r-new and replaces guard g.

    @Test fun RC_settle_receiptIds() {
        val p = F.plan(F.decision(followUp = RefreshIntent.FORCE_PREMIUM), g, null, settle = true, removes = listOf(r))
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val o = positive(decide(p, F.raw(g, r)))
        assertTrue(F.atomic("RC.settle"), o != null && o.receipt is ControlLifecycleReceipt)
        val receipt = checkNotNull(o).receipt as ControlLifecycleReceipt
        assertEquals(F.atomic("RC.settle.removedIds"), listOf("r"), receipt.removedIds)
        assertEquals(F.atomic("RC.settle.createdIds"), listOf("r-new"), receipt.createdIds)
        assertEquals(F.atomic("RC.settle.replacedIds"), listOf("g"), receipt.replacedIds)
    }

    // RC.ids — DT:49. A new application reports the fixed target ids once each, in descriptor order. C6
    // LC_confirm_output checks the confirm-only path; this is the application path.

    @Test fun RC_newApplication_ids() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals("fixture: guard g and REQUEST r are the fixed targets", listOf("g", "r"), p.descriptor("x").targets.map { it.target.id })
        val o = positive(decide(p, F.raw(g, weak)))
        assertTrue(F.atomic("RC.ids"), o != null && o.effect == ConfirmedEffect.AppliedThisAttempt)
        assertEquals(F.atomic("RC.ids"), listOf("g", "r"), checkNotNull(o).ids)
    }

    // RC.unchanged — LC:284. The fixed unchanged REQUEST r of an UPDATE_AUTH, observed in four independent snapshots.

    @Test fun RC_requiredUnchanged_observations() {
        val strong = F.request()
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, strong)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val d = p.descriptor("cmd-unchanged")
        val target = LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)
        assertEquals("fixture: r is the one required-unchanged target", listOf(target), d.requiredUnchanged.map { it.target })
        fun observed(raw: Preferences) = ControlLifecycleConfirmation(F.codec).receipt(d, F.read(raw)).requiredUnchanged
        assertEquals(F.retry("RC.unchanged.presentExact"),
            listOf(LifecycleObservedTarget(target, LifecycleTargetObservation.PresentExact)), observed(F.raw(g, strong)))
        assertEquals(F.retry("RC.unchanged.changed"),
            listOf(LifecycleObservedTarget(target, LifecycleTargetObservation.Changed)), observed(F.raw(g, F.request(order = 5))))
        assertEquals(F.retry("RC.unchanged.absent"),
            listOf(LifecycleObservedTarget(target, LifecycleTargetObservation.Absent)), observed(F.raw(g)))
        val opaque = F.node("""{"id":"r","kind":"NOT_A_KIND"}""")
        assertTrue("fixture: r is present but uninterpretable", F.read(F.raw(g, opaque)).hasUninterpretable)
        assertEquals(F.retry("RC.unchanged.uninterpretable"),
            listOf(LifecycleObservedTarget(target, LifecycleTargetObservation.Uninterpretable)), observed(F.raw(g, opaque)))
    }

    // RC.conflict — DT:22. A current-binding change detected by DemandAuth (DT:27) names the fixed targets in order.

    @Test fun RC_conflict_expectedIds() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val raw = F.raw(g, weak)
        assertTrue("positive twin must reach Confirm", decide(p, raw) is RecordTransactionDecision.Confirm)
        val moved = F.runtime(binding = F.binding.copy(identity = IdentityV1("A", 3)))
        assertEquals("fixture: the attempt context still matches the executor", F.context().ownerUid, F.context(moved).ownerUid)
        val d = decide(p, raw, F.context(moved))
        assertFalse(F.eligible("RC.conflict"), d is RecordTransactionDecision.Confirm)
        val result = ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
        assertTrue("classification: RC.conflict.kind", result is ControlStoreResult.Conflict)
        assertEquals("classification: RC.conflict.expectedIds", listOf("g", "r"),
            (result as ControlStoreResult.Conflict).expected.effectiveIds)
    }

    // RC.order — DF:12 `initial` default. Direct allocator boundary only; D2c construction and wiring are checked
    // separately. `after` is explicit so the `issue` default (DF:15, covered by DemandAuthOrderSourceTest) stays apart.

    @Test fun RC_order_initialOmitted() {
        val source = LifecycleOrderSource(F.life)
        val first = source.issue(3, 5)
        assertTrue(F.atomic("RC.order.initialOmitted"), first != null && first.origin == F.life && first.value > 3 && first.value > 5)
        val second = source.issue(3, 5)
        assertTrue(F.atomic("RC.order.initialOmitted"), second != null && second.value > checkNotNull(first).value)
    }

    // RC.descriptor — LC:46/49/50. Every DemandAuth factory names no namespace postcondition and no other writer.
    // C5 DP_descriptor:348 asserts the same facts for one factory with a plain message; this is the role assertion.

    @Test fun RC_descriptor_otherWriters() {
        val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
        val closure = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
        val plans = mapOf(
            "auth" to F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak),
            "settle" to F.plan(F.decision(followUp = RefreshIntent.FORCE_PREMIUM), g, null, settle = true, removes = listOf(r)),
            "rebind" to DemandAuthPlan.rebind(listOf(F.request(binding = 2)), F.binding, orders),
            "end" to DemandAuthPlan.end(F.guard(auth = oldAuth), listOf(F.request(binding = 2)), F.binding, closure, F.binding, orders))
        for ((name, p) in plans) {
            val d = p.descriptor("cmd-$name")
            assertSame("fixture: $name descriptor is a DemandAuth descriptor", p, d.demandAuth)
            assertTrue(F.atomic("RC.descriptor.otherWriters"), d.namespace == null && d.removeEmptyGuard == null && d.recoverHold == null)
        }
    }
}
