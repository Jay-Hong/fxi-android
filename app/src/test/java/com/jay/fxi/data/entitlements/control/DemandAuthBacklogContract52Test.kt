package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fifty-second file — requiredEffects (DT:171–210) residue of the re-judgment (group ③), method of C40:
 * the writer's own candidate is the normal candidate; each negative changes one field; the truth vector compares the
 * candidate with literal expectations derived here from the fixture (production guards for an absent floor/REQUEST are
 * mirrored). Fixtures from the design rows the re-judgment kept OPEN:
 *  - Q17a/Q17b (r3:575–577): SETTLE_QUERY with no REQUEST removed and the AUTH subtree kept (AUTH not stopped), answer
 *    Pending 60 s (required 60 000, the existing sufficient REQUEST kept for Q17a; successor r-new for Q17b) and the
 *    blocked re-approval variant (StableActive + BLOCKED: default floor 5 000, retry FORCE_PREMIUM).
 *  - A13b partial elapse (C18 F.reqNull.partialElapsedKept): old floor anchored at 10 000 with wait 30 000, merge at 30 000
 *    on the same boot (old remaining 10 000), decision minimum delay 20 000 — the candidate keeps the old floor.
 *  - A13a default delay (C3 F.reqRequired_delay): Indeterminate TRANSIENT without stated seconds (default floor 5 000).
 *  - A14a settle successor (CandidateTest C03): SETTLE consuming r with follow-up FORCE_PREMIUM; the successor is missing.
 */
class DemandAuthBacklogContract52Test {
    private data class Expect(val required: Long?, val oldRemaining: Long, val retryId: String?, val intent: RefreshIntent?,
        val order: Long?, val auth: AuthSnapshotV1)
    private fun remaining(f: FloorV1?, now: BootReading): Long? = f?.let {
        if (it.anchorBootId == now.bootId && now.elapsedMillis >= it.anchorElapsedMillis) it.waitMillis - minOf(it.waitMillis, now.elapsedMillis - it.anchorElapsedMillis)
        else it.waitMillis }
    private fun vec(raw: Preferences, e: Expect, merge: BootReading): List<Pair<String, Boolean>> {
        val read = F.read(raw)
        val g = (read.locations("g").singleOrNull()?.second as? ControlEntryRead.Interpreted)?.value as? ScheduleGuardV1
        val r = e.retryId?.let { (read.locations(it).singleOrNull()?.second as? ControlEntryRead.Interpreted)?.value as? DemandV1 }
        val rem = remaining(g?.floor, merge)
        return listOf(
            "floorPresent" to (e.required == null || g?.floor != null),
            "floorRequired" to (e.required == null || rem == null || rem >= e.required),
            "floorOld" to (e.required == null || rem == null || rem >= e.oldRemaining),
            "retryPresent" to (e.retryId == null || r != null), "retryIntent" to (r == null || r.intent >= e.intent!!),
            "retryOwner" to (r == null || r.ownerUid == "A"), "retryBinding" to (r == null || r.binding == 3L),
            "retryOrigin" to (r == null || r.raisedAt.origin == F.life), "retryOrder" to (r == null || r.raisedAt.value == e.order),
            "auth" to (g?.auth == e.auth))
    }
    private fun check(id: String, only: String, p: DemandAuthPlan, source: Preferences, e: Expect, merge: BootReading, edit: (Preferences) -> Preferences) {
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val (_, raw) = F.apply(p, source)
        assertEquals("truth vector: normal candidate", emptySet<String>(), V.falses(vec(raw, e, merge)))
        assertTrue("positive twin: the writer's own candidate has the required effects", F.transition.requiredEffects(p, F.read(raw)))
        val altered = edit(raw)
        F.schema(altered)
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vec(altered, e, merge)))
        assertFalse(F.atomic(id), F.transition.requiredEffects(p, F.read(altered)))
    }
    private val noFloor: (Preferences) -> Preferences = { F.patch(it, "g") { g -> JsonObject(g - "floor") } }

    // Q17 — SETTLE_QUERY zero-remove, AUTH kept (not stopped)
    private val kept = F.auth.copy(authStopped = false)
    private fun q17(blocked: Boolean, retry: ControlNode?): Pair<DemandAuthPlan, Preferences> {
        val d = F.decision(outcome = if (blocked) EntitlementsOutcome.StableActive(true) else EntitlementsOutcome.Pending(false, 60),
            reapproval = if (blocked) LifecycleReapproval.BLOCKED else LifecycleReapproval.NOT_REQUIRED)
        val g = F.guard(kept)
        val p = F.plan(d, g, retry, settle = true)
        assertTrue("fixture: zero-remove settle", p.transition == LifecycleTransition.SETTLE_QUERY && p.targets.none { it.target.effect == LifecycleEffect.REMOVE })
        return p to F.raw(*listOfNotNull(g, retry).toTypedArray())
    }
    @Test fun Q17a() = q17(false, F.request()).let { (p, raw) -> check("Z.ve.Q17a", "floorPresent", p, raw, Expect(60000, 0, "r", RefreshIntent.FORCE_PREMIUM, 4, kept), F.now, noFloor) }
    @Test fun Q17a_blocked() = q17(true, F.request()).let { (p, raw) -> check("Z.ve.Q17a.blocked", "floorPresent", p, raw, Expect(5000, 0, "r", RefreshIntent.FORCE_PREMIUM, 4, kept), F.now, noFloor) }
    @Test fun Q17b() = q17(false, null).let { (p, raw) -> check("Z.ve.Q17b", "retryPresent", p, raw, Expect(60000, 0, "r-new", RefreshIntent.FORCE_PREMIUM, 22, kept), F.now) { F.patch(it, "r-new") { null } } }
    @Test fun Q17b_blocked() = q17(true, null).let { (p, raw) -> check("Z.ve.Q17b.blocked", "retryPresent", p, raw, Expect(5000, 0, "r-new", RefreshIntent.FORCE_PREMIUM, 22, kept), F.now) { F.patch(it, "r-new") { null } } }

    // A13b partial elapse — UPDATE_AUTH Answer, StableInactive (no retry), AUTH resumed at 21
    private val merge = BootReading("boot", 30000)
    @Test fun A13b_partialElapsed() {
        val g = F.guard(wait = 30000)
        assertEquals("fixture: old floor anchored at 10000 with wait 30000", FloorV1("boot", 10000, 30000, F.life), guard(g)!!.floor)
        val p = F.plan(F.decision(merge = merge, minDelay = 20000), g, null)
        val oldJson = g.toPayloadEntry().fields["floor"]!!
        check("Z.ve.A13b.partialElapsed", "floorRequired", p, F.raw(g), Expect(20000, 10000, null, null, null, F.auth.copy(authStopped = false, authStateOrder = 21)), merge) {
            F.patch(it, "g") { gg -> JsonObject(gg + ("floor" to oldJson)) } }
    }

    // A13a default delay — Indeterminate TRANSIENT without stated seconds (default 5000); existing sufficient REQUEST kept
    @Test fun A13a_defaultDelay() {
        val g = F.guard(); val r = F.request()
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)), g, r)
        check("Z.ve.A13a.defaultDelay", "floorPresent", p, F.raw(g, r), Expect(5000, 0, "r", RefreshIntent.FORCE_PREMIUM, 4, F.auth.copy(authStopped = false, authStateOrder = 21)), F.now, noFloor)
    }

    // A14a settle successor — SETTLE consuming r with follow-up; StableInactive answer resumes the stopped AUTH
    @Test fun A14a_settleSuccessor() {
        val r = F.request(); val g = F.guard()
        val p = F.plan(F.decision(followUp = RefreshIntent.FORCE_PREMIUM), g, retry = null, settle = true, removes = listOf(r))
        check("Z.ve.A14a.settle", "retryPresent", p, F.raw(r, g), Expect(null, 0, "r-new", RefreshIntent.FORCE_PREMIUM, 22, F.auth.copy(authStopped = false, authStateOrder = 21)), F.now) {
            F.patch(it, "r-new") { null } }
    }
}
