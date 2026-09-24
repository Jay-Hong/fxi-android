package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fortieth file — validator sub-conditions, part A: the required-effects boundary
 * (DemandAuthTransition.requiredEffects DT:171–210 and authPostcondition DT:226–244; design §9.3 r3:601–602 A13a–c·A14a–b,
 * §9.4 r3:632 C04 fields · C08).
 * Fixtures follow the design: A13 — a stopped→resumed Pending(FORCE_PREMIUM, 30 s) answer with an existing sufficient
 * REQUEST that is kept (r3:609); A14/C04 — the same answer without a REQUEST, so the builder creates the successor `r-new`.
 * The normal candidate is the writer's own Confirm candidate; each negative changes one field of it.
 * Before the role: an independent truth vector over the candidate, against literal expectations derived here from the
 * fixture (required 30 000 ms, old remaining, successor id/intent/owner/binding/origin/order, resumed AUTH) — the
 * production guards for an absent floor/REQUEST are mirrored (later comparisons are vacuously true) — is all true for the
 * normal candidate with the literal production result true (positive twin), and false exactly at the target.
 */
class DemandAuthBacklogContract40Test {
    private data class Expect(val required: Long, val oldRemaining: Long, val retryId: String, val intent: RefreshIntent,
        val order: Long, val auth: AuthSnapshotV1)
    private val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
    private fun remaining(f: FloorV1?, now: BootReading): Long? = f?.let {
        if (it.anchorBootId == now.bootId && now.elapsedMillis >= it.anchorElapsedMillis) it.waitMillis - minOf(it.waitMillis, now.elapsedMillis - it.anchorElapsedMillis)
        else it.waitMillis }
    private fun vec(raw: Preferences, e: Expect): List<Pair<String, Boolean>> {
        val read = F.read(raw)
        val g = (read.locations("g").singleOrNull()?.second as? ControlEntryRead.Interpreted)?.value as? ScheduleGuardV1
        val r = (read.locations(e.retryId).singleOrNull()?.second as? ControlEntryRead.Interpreted)?.value as? DemandV1
        val rem = remaining(g?.floor, F.now)
        return listOf(
            "floorPresent" to (g?.floor != null), "floorRequired" to (rem == null || rem >= e.required), "floorOld" to (rem == null || rem >= e.oldRemaining),
            "retryPresent" to (r != null), "retryIntent" to (r == null || r.intent >= e.intent), "retryOwner" to (r == null || r.ownerUid == "A"),
            "retryBinding" to (r == null || r.binding == 3L), "retryOrigin" to (r == null || r.raisedAt.origin == F.life),
            "retryOrder" to (r == null || r.raisedAt.value == e.order), "auth" to (g?.auth == e.auth))
    }
    private fun check(id: String, only: String, p: DemandAuthPlan, source: Preferences, e: Expect, edit: (Preferences) -> Preferences) {
        val (_, raw) = F.apply(p, source)
        assertEquals("truth vector: normal candidate", emptySet<String>(), V.falses(vec(raw, e)))
        assertTrue("positive twin: the writer's own candidate has the required effects", F.transition.requiredEffects(p, F.read(raw)))
        val altered = edit(raw)
        F.schema(altered)
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vec(altered, e)))
        assertFalse(F.atomic(id), F.transition.requiredEffects(p, F.read(altered)))
    }
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))

    // A13 — existing sufficient REQUEST kept (successor = r, order 4)
    private fun a13(id: String, only: String, wait: Long?, old: Long, edit: (Preferences) -> Preferences) {
        val g = F.guard(wait = wait); val r = F.request()
        check(id, only, F.plan(pending, g, r), F.raw(g, r), Expect(30000, old, "r", RefreshIntent.FORCE_PREMIUM, 4, resumed), edit)
    }
    @Test fun A13a() = a13("Z.ve.A13a", "floorPresent", null, 0) { F.patch(it, "g") { g -> JsonObject(g - "floor") } }
    @Test fun A13b() = a13("Z.ve.A13b", "floorRequired", null, 0) { F.patch(it, "g") { g -> F.mutateChild(g, "floor", "waitMillis", JsonPrimitive(29999)) } }
    @Test fun A13c() = a13("Z.ve.A13c", "floorOld", 60000, 60000) { F.patch(it, "g") { g -> F.mutateChild(g, "floor", "waitMillis", JsonPrimitive(30000)) } }

    // A14 · C04 — no REQUEST before; the builder creates the successor r-new (order 22)
    private fun a14(id: String, only: String, edit: (Preferences) -> Preferences) {
        val g = F.guard()
        check(id, only, F.plan(pending, g, retry = null), F.raw(g), Expect(30000, 0, "r-new", RefreshIntent.FORCE_PREMIUM, 22, resumed), edit)
    }
    private fun succ(field: String, v: JsonElement): (Preferences) -> Preferences = { raw -> F.patch(raw, "r-new") { r -> JsonObject(r + (field to v)) } }
    @Test fun A14a() = a14("Z.ve.A14a", "retryPresent") { F.patch(it, "r-new") { null } }
    @Test fun A14b() = a14("Z.ve.A14b", "retryIntent", succ("intent", JsonPrimitive("FORCE_ENTITLEMENTS")))
    @Test fun C04owner() = a14("Z.ve.C04owner", "retryOwner", succ("ownerUid", JsonPrimitive("B")))
    @Test fun C04binding() = a14("Z.ve.C04binding", "retryBinding", succ("binding", JsonPrimitive(4)))
    @Test fun C04origin() = a14("Z.ve.C04origin", "retryOrigin", succ("originLifetimeId", JsonPrimitive("other")))
    @Test fun C04order() = a14("Z.ve.C04order", "retryOrder", succ("raisedAt", JsonPrimitive(23)))
    @Test fun C08auth() = a14("Z.ve.C08auth", "auth") { F.patch(it, "g") { g -> F.mutateChild(g, "auth", "authStateOrder", JsonPrimitive(22)) } }
}
