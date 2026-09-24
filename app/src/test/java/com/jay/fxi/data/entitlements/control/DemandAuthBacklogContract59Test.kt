package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fifty-ninth file — T.contextMissing (DemandAuthBacklogContract21Test, STEP3ZK r8 OPEN group
 * "ControlLifecycleConfirmation.decide"): a lifecycle descriptor with an executor needs an attempt context
 * (ControlLifecycle.kt:220–221, before the named writer). This is the common gate, not DemandAuthTransition's runtime
 * gate (DT:25, C56 W_runtimeMissing: context present, demandAuth null).
 * Truth vector: the writer gates · eligibility · common premises evaluated with the normal context, plus the context's
 * presence; twin = the normal context reaching Confirm; negative = the same input with no context — only `context` false.
 */
class DemandAuthBacklogContract59Test {
    private fun decide(p: DemandAuthPlan, raw: Preferences, context: AttemptContext?): RecordTransactionDecision<*> {
        val c = F.command(p)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(raw), context, false, false)
    }
    private fun vec(p: DemandAuthPlan, raw: Preferences, context: AttemptContext?) =
        V.falses(V.decideGates(p, F.context(), raw)) + V.falses(V.eligibility(p, F.runtime(), raw)) +
            V.falses(V.commonPremises(p, raw, "command")) + V.falses(listOf("context" to (context != null)))

    @Test fun T_contextMissing() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        val raw = F.raw(g, r); F.schema(raw)
        assertNull("prepared", p.preparationFailure)
        assertNotNull("fixture: the descriptor carries an executor", p.descriptor("command").executor)
        assertEquals("truth vector: twin", emptySet<String>(), vec(p, raw, F.context()))
        assertTrue("positive twin: the attempt context confirms", decide(p, raw, F.context()) is RecordTransactionDecision.Confirm)
        assertEquals("truth vector: only the context is missing", setOf("context"), vec(p, raw, null))
        assertFalse(F.eligible("Z.lc.contextMissing"), decide(p, raw, null) is RecordTransactionDecision.Confirm)
    }
}
