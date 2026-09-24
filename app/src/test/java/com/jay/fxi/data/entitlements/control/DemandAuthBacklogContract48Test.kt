package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, forty-eighth file — the Q05c writer connection (STEP3ZK r6 판정 ②, static counterexample): the answer,
 * the live identity and the query's bound identity stay A (so Q05d/Q05e hold without changing the three identities together,
 * r3:581), while every owner-side fact the writer compares moves to B — executor/binding identity (B, generation 2), the raw
 * owner, the stored AUTH owner, the consumed REQUEST owner, and the query and decision before/after fences. The answered
 * owner A then differs from the query fence owner B (DemandAuthFacts.kt:147) and, if the counterexample holds, nothing else
 * the writer checks. TV writer gates, eligibility and common premises must name exactly `q.answeredOwner`; the twin is the
 * normal all-A fixture reaching Confirm (C35 method).
 */
class DemandAuthBacklogContract48Test {
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
    private enum class Writer { SETTLE, ANSWER }
    private fun plan(w: Writer, d: AcceptedQueryDecision, g: ControlNode, r: ControlNode, b: LifecycleBinding) =
        if (w == Writer.SETTLE) DemandAuthPlan.settle(listOf(r), g, null, b, d, LifecycleOrderSource(F.life, 21), "g-new", "r-new")
        else DemandAuthPlan.auth(g, null, b, LifecycleAuthEvent.Answer(d), LifecycleOrderSource(F.life, 21), "g-new", "r-new")
    private fun raw(w: Writer, g: ControlNode, r: ControlNode, owner: String): Preferences =
        (if (w == Writer.SETTLE) F.raw(g, r) else F.raw(g)).toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = owner }.toPreferences()

    // owner B facts, identities A
    private val fenceB = F.fence.copy(ownerUid = "B")
    private val bindingB = F.binding.copy(executor = F.binding.executor.copy(ownerUid = "B"), identity = IdentityV1("B", 2))
    private val qB = F.query.copy(fence = fenceB)
    private fun rtB() = F.runtime(binding = bindingB, live = F.identity, registrations = listOf(LifecycleQueryRegistration("query-21", qB)))

    private fun writer(w: Writer) {
        val g = F.guard(); val r = F.request()
        val twin = plan(w, F.decision(), g, r, F.binding)
        vector(twin, raw(w, g, r, "A"), F.runtime(), null)
        assertTrue("positive twin: the normal all-A fixture reaches Confirm in the ${w.name} writer", decide(twin, raw(w, g, r, "A"), F.runtime()) is RecordTransactionDecision.Confirm)
        val gB = F.guard(F.auth.copy(ownerUid = "B")); val rB = F.request(owner = "B")
        val d = F.decision(q = qB, answered = F.identity, before = fenceB, after = fenceB)
        assertEquals("fixture: answer, live and bound identities are A", listOf(F.identity, F.identity, F.identity), listOf(d.answeredAs, rtB().liveIdentity, qB.boundIdentity))
        val p = plan(w, d, gB, rB, bindingB)
        vector(p, raw(w, gB, rB, "B"), rtB(), "q.answeredOwner")
        assertFalse(F.eligible("Z.acc.Q05c.owners.${w.name.lowercase()}"), decide(p, raw(w, gB, rB, "B"), rtB()) is RecordTransactionDecision.Confirm)
    }
    @Test fun Q05c_owners_settle() = writer(Writer.SETTLE)
    @Test fun Q05c_owners_answer() = writer(Writer.ANSWER)
}
