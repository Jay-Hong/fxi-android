package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixty-first file — classification of the missing attempt context (ControlLifecycle.kt:221,
 * T.contextMissing.class). The C59 role (not Confirm) is also held by the dispatch at LC:224, which needs a context for
 * the DemandAuth writer (a compiling bypass of LC:221 yields Rejected LifecycleWriterUnavailable); this method states the
 * C59 premises (only `context` false) and asserts the gate's own literal classification.
 */
class DemandAuthBacklogContract61Test {
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    @Test fun T_contextMissing_classification() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        val raw: Preferences = F.raw(g, r); F.schema(raw)
        assertNull("prepared", p.preparationFailure)
        assertEquals("premise: only the context is missing", setOf("context"), V.falses(V.decideGates(p, F.context(), raw)) +
            V.falses(V.eligibility(p, F.runtime(), raw)) + V.falses(V.commonPremises(p, raw, "command")) + V.falses(listOf("context" to false)))
        val c = F.command(p)
        val result = negative(ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(raw), null, false, false))
        assertTrue("classification: the missing context is an invalid request", result is ControlStoreResult.Rejected &&
            result.reason == RejectionReason.InvalidRequest("AttemptContextRequired"))
    }
}
