package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixty-fifth file — writer units STEP3ZK r9 kept OPEN, on the original fixture shape:
 *  - G.unchanged (DemandAuthTransition.kt:28–30): SETTLE consuming r keeps the GUARD as a required-unchanged row; a stored
 *    GUARD whose preimage changed (a floor) is refused. Role = decide not Confirm; classification separate (Conflict
 *    TargetChanged). C56's W_unchangedPreimage used an unchanged REQUEST — a different row.
 *  - T.runtimeMissing · G.context (DT:25): the DemandAuth runtime is required. UPDATE_AUTH Initialize (no decision, no
 *    registrations) so that a runtime is the only missing premise. Role = decide not Confirm; classification separate.
 * Truth vector: V.decideGates · V.eligibility · V.commonPremises name exactly the target item; twin = the same plan with the
 * normal record / context reaching Confirm.
 */
class DemandAuthBacklogContract65Test {
    private fun decide(p: DemandAuthPlan, raw: Preferences, ctx: AttemptContext): RecordTransactionDecision<*> {
        val c = F.command(p)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(raw), ctx, false, false)
    }
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun falses(p: DemandAuthPlan, raw: Preferences, ctx: AttemptContext) = V.falses(V.decideGates(p, ctx, raw)) +
        V.falses(V.eligibility(p, F.runtime(), raw)) + V.falses(V.commonPremises(p, raw, "command"))

    // G.unchanged — unchanged GUARD
    private val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
    private val r = F.request()
    private fun settlePlan() = F.plan(F.decision(), F.guard(auth = resumed), retry = null, settle = true, removes = listOf(r))
    private val changedGuardRaw get() = F.raw(F.guard(auth = resumed, wait = 60000), r)
    @Test fun G_unchangedGuard() {
        val p = settlePlan(); val twinRaw = F.raw(F.guard(auth = resumed), r)
        assertNull("prepared", p.preparationFailure)
        assertEquals("fixture: the guard is the required-unchanged row", listOf("g"), p.unchanged.map { it.target.id })
        F.schema(twinRaw); F.schema(changedGuardRaw)
        assertEquals("truth vector: twin", emptySet<String>(), falses(p, twinRaw, F.context()))
        assertTrue("positive twin: the unchanged guard confirms", decide(p, twinRaw, F.context()) is RecordTransactionDecision.Confirm)
        assertEquals("truth vector: only the guard preimage differs", setOf("unchanged[g].preimage"), falses(p, changedGuardRaw, F.context()))
        assertFalse(F.eligible("Z.dt.unchangedGuard"), decide(p, changedGuardRaw, F.context()) is RecordTransactionDecision.Confirm)
    }
    @Test fun G_unchangedGuard_classification() {
        val p = settlePlan()
        assertEquals("premise: only the guard preimage differs", setOf("unchanged[g].preimage"), falses(p, changedGuardRaw, F.context()))
        val result = negative(decide(p, changedGuardRaw, F.context()))
        assertTrue("classification: a changed unchanged-guard conflicts", result is ControlStoreResult.Conflict && result.reason == ConflictReason.TargetChanged)
    }

    // T.runtimeMissing — UPDATE_AUTH Initialize
    private fun initPlan() = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life, 21), "g-new", "r-new")
    private val noRuntime get() = F.context().copy(demandAuth = null)
    @Test fun T_runtimeMissing_init() {
        val p = initPlan(); val raw = F.raw()
        assertNull("prepared", p.preparationFailure)
        assertEquals("truth vector: twin", emptySet<String>(), falses(p, raw, F.context()))
        assertTrue("positive twin: the runtime confirms", decide(p, raw, F.context()) is RecordTransactionDecision.Confirm)
        assertEquals("truth vector: only the runtime is missing", setOf("dt.runtime"), falses(p, raw, noRuntime))
        assertFalse(F.eligible("Z.dt.runtimeInit"), decide(p, raw, noRuntime) is RecordTransactionDecision.Confirm)
    }
    @Test fun T_runtimeMissing_classification() {
        val p = initPlan(); val raw = F.raw()
        assertEquals("premise: only the runtime is missing", setOf("dt.runtime"), falses(p, raw, noRuntime))
        val result = negative(decide(p, raw, noRuntime))
        assertTrue("classification: a missing runtime is an invalid request", result is ControlStoreResult.Rejected &&
            result.reason == RejectionReason.InvalidRequest("AttemptContextRequired"))
    }
}
