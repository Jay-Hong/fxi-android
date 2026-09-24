package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, fifty-third file — two units the STEP3ZK r8 판정 moved back to OPEN because their original role is not a
 * decide-level refusal:
 *  - Q03 at the store (DemandAuthCallerTest Q03_callerSame): a query that started at 21 must not consume a REQUEST raised
 *    at 23; the store refuses AND the stored record is unchanged. Negative: stored REQUEST raisedAt 23 — the writer truth
 *    vector (decideGates · eligibility · common premises) names only `remove[r].start` (DemandAuthFacts.kt:125); twin: the
 *    same store and settle with the REQUEST raised at 4 is Confirmed.
 *  - the targets of a refused rebind plan (C23 P_rebindRefused_targets): the builder output of a plan refused by the rebind
 *    predicate has no targets (a `return` removed after `fail()` at DemandAuthPlan.kt:129 would keep the refusal and still
 *    add the target). Vector: rebind predicate owner (Q11a) false only; twin: the owner-A plan has the one REPLACE target.
 */
class DemandAuthBacklogContract53Test {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun store(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c53 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c53 cleanup", 30000) { opened.forEach { it.close() } } }
    private val orders get() = LifecycleOrderSource(F.life, 21)

    private fun settlePlan(r: ControlNode, g: ControlNode) = DemandAuthPlan.settle(listOf(r), g, null, F.binding, F.decision(), orders, "g-new", "r-new")
    private fun falses(p: DemandAuthPlan, raw: Preferences) = V.falses(V.decideGates(p, F.context(F.runtime()), raw)) +
        V.falses(V.eligibility(p, F.runtime(), raw)) + V.falses(V.commonPremises(p, raw, "command"))

    @Test fun Q03_store() = runReleaseTest {
        val g = F.guard(F.auth.copy(authStopped = false))
        val early = F.request(order = 4); val late = F.request(order = 23)
        assertEquals("fixture: the query started at 21", 21L, F.query.order.value)
        val twinPlan = settlePlan(early, g); val twinRaw = F.raw(g, early)
        assertNull("twin prepared", twinPlan.preparationFailure)
        assertEquals("truth vector: twin", emptySet<String>(), falses(twinPlan, twinRaw))
        val a = store(twinRaw)
        val ok = a.control.execute(a.control.prepareSettleQuery(listOf(early), g, null, F.binding, F.decision(), orders), F.context())
        assertTrue("positive twin: the earlier REQUEST is consumed through the store, got $ok", ok is ControlStoreResult.Confirmed)
        val badPlan = settlePlan(late, g); val badRaw = F.raw(g, late)
        assertEquals("truth vector: only the query start differs", setOf("remove[r].start"), falses(badPlan, badRaw))
        val o = store(badRaw)
        val saved = o.raw()
        val result = o.control.execute(o.control.prepareSettleQuery(listOf(late), g, null, F.binding, F.decision(), orders), F.context())
        assertTrue(F.eligible("Z.st.Q03.store"), result !is ControlStoreResult.Confirmed && o.raw() == saved)
    }

    private fun reb(old: DemandV1) = listOf("owner" to (old.ownerUid == F.binding.executor.ownerUid),
        "needed" to !(old.binding == F.binding.executor.binding && old.raisedAt.origin == F.binding.executor.originLifetimeId))
    @Test fun rebindRefused_targets() {
        val ok = F.request(binding = 2, order = 50); val bad = F.request(binding = 2, order = 50, owner = "B")
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(reb(demand(ok)!!)))
        val twin = DemandAuthPlan.rebind(listOf(ok), F.binding, orders)
        assertEquals("positive twin: the prepared rebind plan has the one REPLACE target", listOf(LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)),
            twin.targets.map { it.target })
        assertEquals("truth vector: only the owner differs", setOf("owner"), V.falses(reb(demand(bad)!!)))
        val refused = DemandAuthPlan.rebind(listOf(bad), F.binding, orders)
        assertEquals("fixture: the plan is refused by the rebind predicate", "InvalidRebind", refused.preparationFailure)
        assertTrue(F.atomic("Z.bl.rebindRefused.targets"), refused.targets.isEmpty())
    }
}
