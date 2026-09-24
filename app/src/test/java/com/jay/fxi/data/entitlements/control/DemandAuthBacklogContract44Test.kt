package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, forty-fourth file — the callerIntent connection at requiredEffects (DT:193–205 → callerRetry DT:222;
 * design r3:592 A04 "실제 floor-blocked CALLER에서는 REQUEST 원자 인계"), the OPEN item of STEP3ZK r4 판정 ②.
 * Fixture (DemandAuthCallerTest:37 shape): resumed AUTH with a 60 s floor, an existing REQUEST r (FORCE_PREMIUM, raisedAt 4),
 * a floor-blocked CALLER22 with the weaker intent FORCE_ENTITLEMENTS. The builder keeps r as the successor with
 * FORCE_PREMIUM, raisedAt 23 (grant previous 22, value 23). The negative lowers only the candidate's intent to
 * FORCE_ENTITLEMENTS: A14b (≥ caller.intent) and C04 still hold, and only callerRetry's intentKept (≥ the old REQUEST) fails.
 * Before the role: an independent truth vector against literal expectations derived here from the fixture (no required
 * floor — no decision, so the floor items are vacuously true as in production; successor r · owner A · binding 3 ·
 * origin life · order 23 · grant (22, 23) · binding start 1 · caller order 22 · old REQUEST raisedAt 4 / FORCE_PREMIUM ·
 * AUTH resumed at the caller order (A,2,3,life,false,22,20)) is all true for the writer's own candidate with the literal
 * requiredEffects result true (twin), and false exactly at intentKept.
 */
class DemandAuthBacklogContract44Test {
    /** One order source shared by the caller issuance and the plan (as in DemandAuthCallerTest), so the successor's
     *  grant follows the caller's order. */
    private val orders = LifecycleOrderSource(F.life, 21)
    private val caller = LifecycleCaller("caller-22", LifecycleCallerOrigin.CALLER, F.binding,
        orders.issue(F.binding.startedOrder)!!, RefreshIntent.FORCE_ENTITLEMENTS, F.now)
    private val g get() = F.guard(F.auth.copy(authStopped = false), 60000)
    private val old get() = F.request(intent = RefreshIntent.FORCE_PREMIUM, order = 4)
    private fun plan(c: LifecycleCaller) = DemandAuthPlan.auth(g, old, F.binding, LifecycleAuthEvent.Caller(c), orders, "g", "unused-new-id")
    private val resumed = AuthSnapshotV1("A", 2, 3, F.life, false, 22, 20)

    private fun vec(raw: Preferences): List<Pair<String, Boolean>> {
        val read = F.read(raw)
        val gd = (read.locations("g").singleOrNull()?.second as? ControlEntryRead.Interpreted)?.value as? ScheduleGuardV1
        val r = (read.locations("r").singleOrNull()?.second as? ControlEntryRead.Interpreted)?.value as? DemandV1
        return listOf(
            "retryPresent" to (r != null), "retryIntent" to (r == null || r.intent >= RefreshIntent.FORCE_ENTITLEMENTS),
            "retryOwner" to (r == null || r.ownerUid == "A"), "retryBinding" to (r == null || r.binding == 3L),
            "retryOrigin" to (r == null || r.raisedAt.origin == F.life), "retryOrder" to (r == null || r.raisedAt.value == 23L),
            "callerAfterBindingStart" to (r == null || r.raisedAt.value > 1L), "callerAfterPrevious" to (r == null || r.raisedAt.value > 22L),
            "callerAfterEvent" to (r == null || r.raisedAt.value > 22L), "callerAfterOld" to (r == null || r.raisedAt.value > 4L),
            "callerIntentKept" to (r == null || r.intent >= RefreshIntent.FORCE_PREMIUM), "auth" to (gd?.auth == resumed))
    }

    @Test fun A04_callerIntent_requiredEffects() {
        val c = caller
        assertEquals("fixture: CALLER order 22", 22L, c.order.value)
        assertTrue("fixture: the floor blocks the caller", guard(g)!!.floor!!.remainingAt(c.now)!! > 0)
        val p = plan(c)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals("fixture: grant of the successor (previous 22, value 23)", listOf(22L, 23L), p.grants["r"]?.let { listOf(it.previous, it.value) })
        val (_, raw) = F.apply(p, F.raw(g, old), F.runtime(caller = c))
        assertEquals("truth vector: normal candidate", emptySet<String>(), V.falses(vec(raw)))
        assertTrue("positive twin: the writer's own candidate has the required effects", F.transition.requiredEffects(p, F.read(raw)))
        val altered = F.patch(raw, "r") { r -> JsonObject(r + ("intent" to JsonPrimitive("FORCE_ENTITLEMENTS"))) }
        F.schema(altered)
        assertEquals("truth vector: only the target differs", setOf("callerIntentKept"), V.falses(vec(altered)))
        assertFalse(F.atomic("Z.ve.A04.callerIntent.requiredEffects"), F.transition.requiredEffects(p, F.read(altered)))
    }
}
