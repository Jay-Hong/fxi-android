package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DemandAuthCandidateTest {
    private fun pending(settle: Boolean = false, wait: Long? = null, retry: ControlNode? = F.request(), seconds: Long = 30,
        blocked: Boolean = false): DemandAuthPlan {
        val a = if (settle) F.auth.copy(authStopped = false) else F.auth
        val d = F.decision(outcome = if (blocked) EntitlementsOutcome.StableActive(true) else EntitlementsOutcome.Pending(false, seconds),
            reapproval = if (blocked) LifecycleReapproval.BLOCKED else LifecycleReapproval.NOT_REQUIRED)
        return F.plan(d, F.guard(a, wait), retry, settle)
    }
    private fun landed(p: DemandAuthPlan): Triple<CommandRef, Preferences, Preferences> {
        val source = F.raw(*listOfNotNull(p.guardBefore, p.retryBefore).toTypedArray())
        val (c, raw) = F.apply(p, source)
        F.schema(raw)
        assertEquals(p.targets.map { it.target }, (ControlAppliedEvidence.own(F.read(raw), c) as AppliedEvidence.Lifecycle).targets)
        return Triple(c, source, raw)
    }
    private fun floorNo(id: String, p: DemandAuthPlan, patch: (JsonObject) -> JsonObject) {
        val (_, _, raw) = landed(p)
        zeroRemoveAuthControl(p, raw)
        val candidate = F.patch(raw, "g", patch)
        F.schema(candidate)
        val g = F.read(candidate).locations("g").single().second as ControlEntryRead.Interpreted
        assertEquals(guard(p.guardAfter)!!.auth, (g.value as ScheduleGuardV1).auth)
        assertEquals(p.retryAfter!!.toPayloadEntry(), (F.read(candidate).locations(demand(p.retryAfter)!!.id).single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        assertFalse(F.atomic(id), F.transition.requiredEffects(p, F.read(candidate)))
    }
    @Test fun A13a() = floorNo("A13a", pending()) { JsonObject(it - "floor") }
    @Test fun A13b() = floorNo("A13b", pending()) { F.mutateChild(it, "floor", "waitMillis", JsonPrimitive(29999)) }
    @Test fun A13c() = floorNo("A13c", pending(wait = 60000)) { F.mutateChild(it, "floor", "waitMillis", JsonPrimitive(30000)) }
    @Test fun Q17a() = floorNo("Q17a", pending(settle = true, seconds = 60)) { JsonObject(it - "floor") }
    @Test fun Q17a_blocked() = floorNo("Q17a_blocked", pending(settle = true, blocked = true)) { JsonObject(it - "floor") }
    private fun retryNo(id: String, p: DemandAuthPlan, patch: (JsonObject) -> JsonObject?) {
        val (_, _, raw) = landed(p)
        zeroRemoveAuthControl(p, raw)
        val candidate = F.patch(raw, demand(p.retryAfter)!!.id, patch)
        F.schema(candidate)
        assertEquals((F.read(raw).locations("g").single().second as ControlEntryRead.Interpreted).original.toPayloadEntry(),
            (F.read(candidate).locations("g").single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
        assertFalse(F.atomic(id), F.transition.requiredEffects(p, F.read(candidate)))
    }
    private fun zeroRemoveAuthControl(p: DemandAuthPlan, full: Preferences) {
        if (p.transition != LifecycleTransition.SETTLE_QUERY) return
        assertTrue("zero-remove fixture", p.targets.none { it.target.effect == LifecycleEffect.REMOVE })
        val actual = (F.read(full).locations("g").single().second as ControlEntryRead.Interpreted).original
        assertEquals("zero-remove fixture preserves the original AUTH subtree",
            p.guardBefore!!.toPayloadEntry().fields["auth"], actual.toPayloadEntry().fields["auth"])
    }
    @Test fun A14a() = retryNo("A14a", pending(retry = null)) { null }
    @Test fun A14b() = retryNo("A14b", pending(retry = null)) { JsonObject(it + ("intent" to JsonPrimitive("FORCE_ENTITLEMENTS"))) }
    @Test fun C04intent() = retryNo("C04intent", pending(retry = null)) { JsonObject(it + ("intent" to JsonPrimitive("FORCE_ENTITLEMENTS"))) }
    @Test fun C03() {
        val r = F.request(); val d = F.decision(followUp = RefreshIntent.FORCE_PREMIUM)
        val p = F.plan(d, retry = null, settle = true, removes = listOf(r))
        val (_, full) = F.apply(p, F.raw(r, p.guardBefore!!))
        val partial = F.patch(full, demand(p.retryAfter)!!.id) { null }
        F.schema(partial)
        assertTrue(F.read(partial).locations("r").isEmpty())
        assertEquals(guard(p.guardAfter)!!.auth, (F.read(partial).locations("g").single().second as ControlEntryRead.Interpreted).value.let { (it as ScheduleGuardV1).auth })
        assertFalse(F.atomic("C03"), F.transition.requiredEffects(p, F.read(partial)))
    }
    @Test fun Q17b() = retryNo("Q17b", pending(settle = true, seconds = 60, retry = null)) { null }
    @Test fun Q17b_blocked() = retryNo("Q17b_blocked", pending(settle = true, blocked = true, retry = null)) { null }
    @Test fun C04owner() = retryNo("C04owner", pending(retry = null)) { JsonObject(it + ("ownerUid" to JsonPrimitive("B"))) }
    @Test fun C04binding() = retryNo("C04binding", pending(retry = null)) { JsonObject(it + ("binding" to JsonPrimitive(4))) }
    @Test fun C04origin() = retryNo("C04origin", pending(retry = null)) { JsonObject(it + ("originLifetimeId" to JsonPrimitive("other"))) }
    @Test fun C04order() = retryNo("C04order", pending(retry = null)) { JsonObject(it + ("raisedAt" to JsonPrimitive(23))) }
    @Test fun Q17d_requestOnly() {
        val p = pending(settle = true, wait = 90000, retry = null, seconds = 60)
        val (_, _, raw) = landed(p)
        assertEquals(listOf(LifecycleRole.REQUEST), p.targets.map { it.role })
        assertEquals(listOf(LifecycleRole.GUARD), p.unchanged.map { it.role })
        assertEquals(p.guardBefore!!.toPayloadEntry(), (F.read(raw).locations("g").single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
    }
    @Test fun Q17d_guardOnly() {
        val p = pending(settle = true, seconds = 60)
        landed(p)
        assertEquals(listOf(LifecycleRole.GUARD), p.targets.map { it.role })
        assertEquals(listOf(LifecycleRole.REQUEST), p.unchanged.map { it.role })
    }
    @Test fun Q17d_blockedRequestOnly() {
        val p = pending(settle = true, wait = 90000, retry = null, blocked = true)
        val (_, _, raw) = landed(p)
        zeroRemoveAuthControl(p, raw)
        assertEquals(listOf(LifecycleRole.REQUEST), p.targets.map { it.role })
        assertEquals(listOf(LifecycleRole.GUARD), p.unchanged.map { it.role })
        assertEquals(p.guardBefore!!.toPayloadEntry(), (F.read(raw).locations("g").single().second as ControlEntryRead.Interpreted).original.toPayloadEntry())
    }
    @Test fun Q17d_blockedGuardOnly() {
        val p = pending(settle = true, blocked = true)
        val (_, _, raw) = landed(p)
        zeroRemoveAuthControl(p, raw)
        assertEquals(listOf(LifecycleRole.GUARD), p.targets.map { it.role })
        assertEquals(listOf(LifecycleRole.REQUEST), p.unchanged.map { it.role })
        assertEquals(5000L, guard(p.guardAfter)!!.floor!!.waitMillis)
    }
    @Test fun noChangesCannotCreateApplied() {
        val p = pending(settle = true, wait = 90000, seconds = 60)
        assertTrue(p.targets.isEmpty())
        val c = F.command(p)
        val result = ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input,
            F.read(F.raw(p.guardBefore!!, p.retryBefore!!)), F.context(), false, false)
        assertTrue(result is RecordTransactionDecision.Observe)
    }
    private fun candidateNo(id: String, mutate: (Preferences, DemandAuthPlan) -> Preferences) {
        val p = pending(retry = null)
        val (c, source, raw) = landed(p)
        val altered = mutate(raw, p)
        assertFalse(F.atomic(id), F.transition.validCandidate(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(source), altered))
    }
    @Test fun C08auth() {
        val p = pending()
        val expected = F.auth.copy(authStopped = false, authStateOrder = 21)
        assertFalse(F.atomic("C08auth"), F.transition.authPostcondition(p, expected.copy(authStateOrder = 22)))
    }
    @Test fun C08_unchangedIntegerLiterals() {
        val old = F.node(F.guard(F.auth.copy(authGeneration = 0, binding = 0, authStopped = false, authStopAppliedOrder = 0)).toPayloadEntry().fields.toString()
            .replace("\"authGeneration\":0", "\"authGeneration\":-0").replace("\"binding\":0", "\"binding\":-0").replace("\"authStopAppliedOrder\":0", "\"authStopAppliedOrder\":-0"))
        val after = guardNode(old, "g", guard(old)!!.auth!!.copy(authStateOrder = 21), null)
        val beforeFields = old.toPayloadEntry().fields["auth"] as JsonObject
        val afterFields = after.toPayloadEntry().fields["auth"] as JsonObject
        assertEquals(F.atomic("C08.literals"), JsonObject(beforeFields - "authStateOrder"), JsonObject(afterFields - "authStateOrder"))
    }
    @Test fun C04_bindingLiteral() {
        val request = F.node(F.request(binding = 0).toPayloadEntry().fields.toString().replace("\"binding\":0", "\"binding\":-0"))
        val changed = requestNode("r", F.binding.copy(executor = F.binding.executor.copy(binding = 0)), RefreshIntent.FORCE_PREMIUM, 22, request)
        assertEquals(F.atomic("C04.bindingLiteral"), request.toPayloadEntry().fields["binding"], changed.toPayloadEntry().fields["binding"])
    }
    @Test fun C09floorOrigin() = candidateNo("C09floorOrigin") { raw, _ -> F.patch(raw, "g") { F.mutateChild(it, "floor", "originLifetimeId", JsonPrimitive("other")) } }
    @Test fun C11external() = candidateNo("C11external") { raw, _ -> raw.toMutablePreferences().apply { this[byteArrayPreferencesKey("lifecycle-external")] = byteArrayOf(8, 9) } }
    @Test fun C11payload() = candidateNo("C11payload") { raw, _ -> raw.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = "[ ]" } }
    @Test fun C10missing() = candidateNo("C10missing") { raw, _ -> raw.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]" } }
    @Test fun C10transition() = candidateNo("C10transition") { raw, _ -> raw.toMutablePreferences().apply {
        val key = ControlLifecycleEvidenceFixtures.evidenceKey; this[key] = this[key]!!.replace("UPDATE_AUTH", "SETTLE_QUERY") } }
    @Test fun C10effect() = candidateNo("C10effect") { raw, _ -> raw.toMutablePreferences().apply {
        val key = ControlLifecycleEvidenceFixtures.evidenceKey; this[key] = this[key]!!.replace("REPLACE", "CREATE") } }
    @Test fun C10order() = candidateNo("C10order") { raw, _ -> raw.toMutablePreferences().apply {
        val key = ControlLifecycleEvidenceFixtures.evidenceKey
        val row = (Json.parseToJsonElement(this[key]!!) as JsonArray).single() as JsonObject
        this[key] = JsonArray(listOf(JsonObject(row + ("targets" to JsonArray((row["targets"] as JsonArray).reversed()))))).toString()
    } }
    @Test fun C12schema() = candidateNo("C12schema") { raw, _ -> F.patch(raw, "g") { JsonObject(it + ("future" to JsonPrimitive(true))) } }
    private fun floorBoundary(id: String, change: (ControlNode) -> ControlNode) {
        val old = F.guard(wait = 60000)
        val result = ControlObligations.recordFloor(old, F.now, 30000, F.life) as ControlWriteResult.Written
        val altered = change(result.node)
        val after = guard(altered)!!
        assertFalse(F.atomic(id), ControlObligations.validFloorResult(old, altered, after, FloorV1("boot", 10000, 60000, F.life)))
    }
    @Test fun A11a() = floorBoundary("A11a") { F.node(F.mutateChild(it.toPayloadEntry().fields, "auth", "authStateOrder", JsonPrimitive(11)).toString()) }
    @Test fun A11b() = floorBoundary("A11b") { F.node(F.mutateChild(it.toPayloadEntry().fields, "floor", "waitMillis", JsonPrimitive(30000)).toString()) }
}
