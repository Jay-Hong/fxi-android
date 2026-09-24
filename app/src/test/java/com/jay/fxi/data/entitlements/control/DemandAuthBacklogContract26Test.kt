package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, twenty-sixth file: the receipt of a confirmed DemandAuth write observes the written candidate.
 * DemandAuthTransition.kt:47–49 reads the candidate back and builds the receipt from it (ControlLifecycle.kt:277–285);
 * In this Initialize fixture, the created guard is PresentExact; REMOVE targets are Absent (ControlLifecycle.kt:147·160–164).
 */
class DemandAuthBacklogContract26Test {
    // W.receipt — Initialize on an empty record creates one guard; the confirmed receipt observes it exactly.

    @Test fun W_receipt_candidate() {
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life, 21), "g-new", "r-new")
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val raw = F.raw(); F.schema(raw)
        assertTrue("fixture: the created guard is absent before the write", F.read(raw).locations("g-new").isEmpty())
        val c = F.command(p)
        val d = ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(raw), F.context(), false, false)
        assertTrue("positive twin: the write confirms", d is RecordTransactionDecision.Confirm)
        assertEquals(F.atomic("W.receipt.candidate"), listOf("g-new" to LifecycleTargetObservation.PresentExact),
            ((d.value as? ControlRecordStore.Outcome.Positive)?.receipt as? ControlLifecycleReceipt)?.targets?.map { it.target.id to it.observation })
    }
}
