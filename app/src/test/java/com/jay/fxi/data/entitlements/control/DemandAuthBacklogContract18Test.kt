package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, eighteenth file: floor facts that depend on the boot reading.
 *  - U.planInit.keepsFloor — Initialize keeps an existing floor (DemandAuthPlan.kt:60).
 *  - F.retrySel.callerExpired — a CALLER whose floor has expired on the same boot needs no retry (DT:194–195).
 *  - F.reqNull.partialElapsedKept — a candidate that keeps a partially elapsed old floor below the required wait is
 *    not a valid effect even though its full wait is large enough (DT:188–189).
 *  - F.reqNull.oldRemaining — the old floor is compared by its remaining time on the same boot, so a planned floor
 *    between that remaining time and the old full wait is accepted (DT:190–191).
 * Floors, boot readings and remaining times are fixed constants; same-boot remaining time is written as arithmetic
 * on those constants, not computed by FloorV1.remainingAt.
 */
class DemandAuthBacklogContract18Test {
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun decide(p: DemandAuthPlan, raw: Preferences, runtime: DemandAuthRuntime = F.runtime()): RecordTransactionDecision<*> {
        val c = F.command(p)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(raw), F.context(runtime), false, false)
    }

    // U.planInit — DemandAuthPlan.kt:59–61. A guard with a floor and no AUTH; Initialize installs AUTH only.

    @Test fun U_planInit_keepsFloor() {
        val g = F.guard(auth = null, wait = 30000)
        val floor = FloorV1("boot", 10000, 30000, F.life)
        assertEquals("fixture: existing floor", floor, guard(g)!!.floor)
        assertNull("fixture: no existing AUTH", guard(g)!!.auth)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals(F.atomic("U.planInit.keepsFloor"), floor, guard(p.guardAfter)?.floor)
    }

    // F.retrySel — DemandAuthTransition.kt:194–195 and DemandAuthPlan.kt:70–73. The floor anchored at boot 10000
    // with wait 30000 has expired at the caller's reading boot 50000 on the same boot (50000 − 10000 ≥ 30000).

    @Test fun F_retrySel_callerExpired() {
        val g = F.guard(wait = 30000)
        val now = BootReading("boot", 50000)
        val c = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding, LifecycleOrderGrant(F.life, 20, 1, 0, 21), RefreshIntent.FORCE_PREMIUM, now)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(c), orders, "g-new", "r-new")
        val raw = F.raw(g); val rt = F.runtime(caller = c)
        F.schema(raw)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: existing floor", FloorV1("boot", 10000, 30000, F.life), guard(g)!!.floor)
        assertEquals("fixture: same boot", "boot", now.bootId)
        assertTrue("fixture: the floor has expired", now.elapsedMillis - 10000 >= 30000)
        assertNull("fixture: no retry is prepared", p.retryAfter)
        assertEquals("fixture: eligibility", emptySet<String>(), V.falses(V.eligibility(p, rt, raw)))
        assertEquals("fixture: writer gates", emptySet<String>(), V.falses(V.decideGates(p, F.context(rt), raw)))
        assertEquals("fixture: common premises", emptySet<String>(), V.falses(V.commonPremises(p, raw, F.command(p).id)))
        assertTrue(F.atomic("F.retrySel.callerExpired"), decide(p, raw, rt) is RecordTransactionDecision.Confirm)
    }

    // F.reqNull — DemandAuthTransition.kt:184–191. Old floor anchored at 10000 with wait 30000; the answer merges at
    // 30000 on the same boot, so the old floor has 30000 − (30000 − 10000) = 10000 left. The decision requires 20000.

    private val merge = BootReading("boot", 30000)
    private val oldFloor = FloorV1("boot", 10000, 30000, F.life)
    private fun partialPlan(g: ControlNode): DemandAuthPlan {
        val p = F.plan(F.decision(merge = merge, minDelay = 20000), g, null)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: old floor", oldFloor, guard(g)!!.floor)
        assertEquals("fixture: same boot", "boot", merge.bootId)
        assertEquals("fixture: old remaining on the same boot", 10000L, 30000 - (merge.elapsedMillis - 10000))
        assertEquals("fixture: planned floor", FloorV1("boot", 30000, 20000, F.life), guard(p.guardAfter)!!.floor)
        return p
    }

    @Test fun F_reqNull_partialElapsedKept() {
        val g = F.guard(wait = 30000)
        val p = partialPlan(g)
        val (_, raw) = F.apply(p, F.raw(g))
        assertTrue("positive twin: the planned floor satisfies the effects", F.transition.requiredEffects(p, F.read(raw)))
        val oldJson = g.toPayloadEntry().fields["floor"]!!
        val kept = F.patch(raw, "g") { JsonObject(it + ("floor" to oldJson)) }
        F.schema(kept)
        val before = F.read(raw).locations("g").single().second as ControlEntryRead.Interpreted
        val after = F.read(kept).locations("g").single().second as ControlEntryRead.Interpreted
        assertEquals("fixture: only the guard floor changed", JsonObject(before.original.toPayloadEntry().fields - "floor"),
            JsonObject(after.original.toPayloadEntry().fields - "floor"))
        assertEquals("fixture: the candidate keeps the old floor", oldFloor, (after.value as ScheduleGuardV1).floor)
        assertFalse(F.atomic("F.reqNull.partialElapsedKept"), F.transition.requiredEffects(p, F.read(kept)))
    }

    @Test fun F_reqNull_oldRemaining() {
        val g = F.guard(wait = 30000)
        val p = partialPlan(g)
        val raw = F.raw(g)
        F.schema(raw)
        assertEquals("fixture: eligibility", emptySet<String>(), V.falses(V.eligibility(p, F.runtime(), raw)))
        assertEquals("fixture: writer gates", emptySet<String>(), V.falses(V.decideGates(p, F.context(), raw)))
        assertEquals("fixture: common premises", emptySet<String>(), V.falses(V.commonPremises(p, raw, F.command(p).id)))
        assertTrue(F.atomic("F.reqNull.oldRemaining"), decide(p, raw) is RecordTransactionDecision.Confirm)
    }
}
