package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, twenty-third file: single OPEN candidates whose recorded route names a contract.
 *  - U.planRecovery.auth — Recovery resumes the existing AUTH at the recovered order (DemandAuthPlan.kt:76–79).
 *  - U.requestNode.fields — a new REQUEST row is exactly the seven schema fields (DemandAuthPlan.kt:207–215).
 *  - P.rebindRefused.targets — a refused re-bind is not a target (DemandAuthPlan.kt:125–133).
 *  - W.createId.unrelated — CREATE checks its own id, not an unrelated row (ControlLifecycle.kt:212–214).
 *  - V.effectsApply.floorOnly — with every other fixed fact kept, a candidate missing only the decision's floor is not
 *    valid (DemandAuthTransition.kt:250); the C6 floorPlan injection with independent premises.
 *  - R.duplicateTarget.observation — the store's diagnostic observes a duplicated target as Uninterpretable
 *    (ControlLifecycle.kt:143–154 via ControlRecordStore.kt:469–471).
 * Expected values are fixed constants or the fixture's original nodes; production output is an observed value only.
 */
class DemandAuthBacklogContract23Test {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun close() = runBlocking { controlTestTimeout("5e-1 cleanup", 30000) { opened.reversed().forEach { it.close() } } }

    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun decide(c: CommandRef, input: ControlLifecycleDescriptor, raw: Preferences) =
        ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(raw), F.context(), false, false)
    private fun decide(p: DemandAuthPlan, raw: Preferences): RecordTransactionDecision<*> {
        val c = F.command(p); return decide(c, (c.body as ControlCommandBody.Lifecycle).input, raw)
    }
    private fun others(p: Preferences, except: Preferences.Key<*>) =
        p.asMap().filterKeys { it != except }.mapValues { (_, v) -> if (v is ByteArray) v.toList() else v }

    // U.planRecovery — DemandAuthPlan.kt:76–79. The guard has a stopped AUTH; Recovery resumes it at order 30.

    @Test fun U_planRecovery_auth() {
        val g = F.guard()
        assertEquals("fixture: the existing AUTH", AuthSnapshotV1("A", 2, 3, F.life, true, 10, 20), guard(g)!!.auth)
        val recovery = LifecycleRecovery("recovery", F.identity, F.life, 25, 30, 1)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Recovery(recovery), orders, "g-new", "r-new")
        assertEquals(F.atomic("U.planRecovery.auth"), AuthSnapshotV1("A", 2, 3, F.life, false, 30, 20), guard(p.guardAfter)?.auth)
    }

    // U.requestNode — DemandAuthPlan.kt:207–215, a new REQUEST without an original row.

    @Test fun U_requestNode_fields() {
        val expected = buildJsonObject {
            put("id", "r-new"); put("kind", "REQUEST"); put("ownerUid", "A"); put("binding", 3)
            put("intent", "FORCE_PREMIUM"); put("originLifetimeId", "life"); put("raisedAt", 22)
        }
        val node = requestNode("r-new", F.binding, RefreshIntent.FORCE_PREMIUM, 22)
        assertEquals(F.atomic("U.requestNode.fields"), expected, JsonObject(node.toPayloadEntry().fields))
    }

    // P.rebindRefused — DemandAuthPlan.kt:125–133. The REQUEST is already on the current binding and origin.

    @Test fun P_rebindRefused_targets() {
        val current = F.request()
        assertFalse("fixture: the REQUEST cannot be re-bound", DemandAuthBoundary.rebind(demand(current)!!, F.binding, RefreshIntent.FORCE_PREMIUM))
        val p = DemandAuthPlan.rebind(listOf(current), F.binding, orders)
        assertEquals("fixture: the planner refuses the re-bind", "InvalidRebind", p.preparationFailure)
        assertEquals(F.atomic("P.rebindRefused.targets"), emptyList<String>(), p.targets.map { it.target.id })
    }

    // W.createId — ControlLifecycle.kt:212–214. An unrelated REQUEST "r6b" is present; the created guard id is free.

    @Test fun W_createId_unrelated() {
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: the created id", listOf("g-new"), p.targets.filter { it.target.effect == LifecycleEffect.CREATE }.map { it.target.id })
        val raw = F.raw(F.request(id = "r6b", owner = "B", binding = 1))
        F.schema(raw)
        assertTrue("fixture: the created id is free", F.read(raw).locations("g-new").isEmpty())
        assertEquals("fixture: an unrelated row exists", 1, F.read(raw).locations("r6b").size)
        assertTrue(F.atomic("W.createId.unrelated"), decide(p, raw) is RecordTransactionDecision.Confirm)
    }

    // V.effectsApply — DemandAuthTransition.kt:246–261. C6 floorPlan: the injected descriptor and candidate differ from
    // the plan and its confirmed candidate only in the guard's floor.

    @Test fun V_effectsApply_floorOnly() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), F.request())
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val t = p.targets.single()
        assertEquals("fixture: one guard target", LifecycleRole.GUARD to LifecycleEffect.REPLACE, t.role to t.target.effect)
        val planned = t.after!!.toPayloadEntry().fields
        assertNotNull("fixture: the plan requires a floor", planned["floor"])
        val noFloor = F.node(JsonObject(planned - "floor").toString())
        assertEquals("fixture: the injected row keeps every other field", JsonObject(planned - "floor"), JsonObject(noFloor.toPayloadEntry().fields))
        val injected = ControlLifecycleDescriptor("command", p.transition, listOf(t.copy(after = noFloor)), p.binding.executor,
            requiredUnchanged = p.unchanged, demandAuth = p)
        val original = p.descriptor("command")
        assertEquals("fixture: the injected descriptor keeps target, role and preimage",
            original.targets.map { Triple(it.target, it.role, it.before?.toPayloadEntry()) },
            injected.targets.map { Triple(it.target, it.role, it.before?.toPayloadEntry()) })
        assertEquals("fixture: the injected descriptor keeps the unchanged rows", original.requiredUnchanged, injected.requiredUnchanged)
        assertEquals("fixture: the injected descriptor keeps transition and executor",
            original.transition to original.executor, injected.transition to injected.executor)
        val raw = F.raw(F.guard(), F.request())
        val c0 = F.command(p)
        val d0 = decide(c0, (c0.body as ControlCommandBody.Lifecycle).input, raw)
        assertTrue("positive twin: the plan confirms", d0 is RecordTransactionDecision.Confirm)
        val full = (d0 as RecordTransactionDecision.Confirm<*>).candidate
        assertTrue("positive twin: the full candidate is valid", F.transition.validCandidate(c0, (c0.body as ControlCommandBody.Lifecycle).input, F.read(raw), full))
        val candidate = F.patch(full, "g") { JsonObject(it - "floor") }
        val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
        assertEquals("fixture: only DEMAND changed", others(full, demandKey), others(candidate, demandKey))
        val fullRows = F.read(full).arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() }
        val rows = F.read(candidate).arrays.getValue(ControlKind.DEMAND).entries.map { it.payload() }
        assertEquals("fixture: the candidate has the injected guard row", noFloor.toPayloadEntry(), rows.first())
        assertEquals("fixture: every other DEMAND row is kept", fullRows.drop(1), rows.drop(1))
        val c = CommandRef(c0.id, ControlCommandBody.Lifecycle(injected), c0.ownerTrackingLifetimeId)
        assertFalse(F.atomic("V.effectsApply.floorOnly"), F.transition.validCandidate(c, injected, F.read(raw), candidate))
    }

    // R.duplicateTarget — ControlLifecycle.kt:143–154. Two rows carry the target id "g"; the reader makes both
    // uninterpretable (ControlRecordReader.kt:147–157) and the store still attaches its diagnostic observation.

    @Test fun R_duplicateTarget_observation() = runBlocking {
        val g = F.guard(); val r = F.request()
        val source = F.raw(g, g, r)
        val read = F.read(source)
        assertEquals("fixture: two rows carry the target id", 2, read.locations("g").size)
        val store = ControlStoreTestStorage(folder.newFile()).also { opened += it }
        controlTestTimeout("seed 5e-1") { store.data.updateData { source } }
        var issued = 0
        val facade = ControlRecordStore(store.owner, ControlIdGenerator { UUID(0, (++issued).toLong()) }, F.codec)
        val c = facade.prepareUpdateAuth(g, r, F.binding, LifecycleAuthEvent.Answer(F.decision(outcome = EntitlementsOutcome.Pending(false, 30))), orders)
        assertNull("fixture: plan is prepared", ((c.body as ControlCommandBody.Lifecycle).input.demandAuth)!!.preparationFailure)
        val result = controlTestTimeout("5e-1 facade") { facade.execute(c, F.context()) }
        assertEquals(F.retry("R.duplicateTarget.observation"), LifecycleTargetObservation.Uninterpretable,
            result.lifecycleDiagnostic?.targets?.singleOrNull { it.target.id == "g" }?.observation)
    }
}
