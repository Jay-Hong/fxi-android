package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, forty-third file — validator sub-conditions, part C: the final validator
 * DemandAuthTransition.validCandidate (DT:246–261) per named writer (design §9.4 r3:632 C01–C04·C09–C12, r3:634
 * "변조 후보를 직접 주입한 boundary", r3:683 "각 네 writer"). Rebuilds the C33 representative sample: each cell starts from the
 * writer's own Confirm candidate (F.apply) and changes it so that **exactly one** validCandidate condition is false.
 *
 * Independent truth vector over (before, candidate), computed on the test side in production order:
 *  - size      each changed payload (DEMAND, evidence) is at most 65 536 UTF-8 bytes — computed here as a literal, not
 *              through the codec (ControlPayloadCodec.kt:196 enforces it inside the reader DT:248 calls)
 *  - depth     each changed payload nests at most 64 levels (root array = 1) — literal (codec :275 text scan, :307 tree scan)
 *  - read      candidate reads as Supported (DT:248)
 *  - schema    schemaVersion 2, no uninterpretable row, no uninterpretable metadata (DT:249 → ControlLifecycle.kt:82–86)
 *  - effects   the values requiredEffects derives (DT:171–210) are identical to the twin candidate's: when a floor is
 *              required (DT:185–186: stated seconds or a minimum delay — settle minDelay 30 s, auth Pending 30 s), the
 *              floor's presence and its remaining time at the plan's merge time (DT:187–191 compare only these); when a
 *              retry intent exists (DT:193–197: settle followUp, auth Pending), the successor's intent · owner · binding ·
 *              origin · order (DT:199–204); when the plan has a guard after, the whole AUTH (DT:208). Equal derived values
 *              suffice for the twin's result (a sufficient condition; unequal values need not make requiredEffects false).
 *  - rows      DEMAND entries = before entries with each fixed target replaced by its after (or dropped) + CREATE afters,
 *              derived from the descriptor's targets (DT:251, 263–270)
 *  - external  every preference other than the DEMAND and evidence payloads equals before (DT:252–256)
 *  - evidence  evidence entries = before entries + this command's Applied node, the node written from the descriptor with
 *              the wire format of ControlLifecycleEvidenceFixtures.wire (DT:257–259)
 * The read guard is mirrored: when size or depth is violated, or the candidate does not read, the later conditions are
 * vacuously true (production returns at DT:248). Twin: the writer's own candidate — vector all true and the literal
 * validCandidate result true. Then the altered pair — only the target false — and the role.
 *
 * effects cells are direct validator-boundary injections under r3:634: the descriptor's target after and the candidate
 * carry the same missing/wrong effect, so rows, external and evidence hold and only effects is false.
 * They do not execute a production builder mutant and are not evidence of its rejection before storage.
 * For the submitted missing-floor, wrong-AUTH and wrong-owner cases, changing only the candidate also makes rows false.
 * Equal effect inputs suffice to preserve the twin's result; unequal inputs do not generally imply requiredEffects=false.
 * In particular, changing only a settle/auth candidate's floor.originLifetimeId to another non-empty value preserves
 * requiredEffects and makes only rows false (STEP3ZK r5 판정); those cells are C09floorOrigin_settle/auth below.
 * Rebind reads no effect input. With these schema-2 before states, changing schemaVersion also changes the external SCHEMA
 * preference; schema 1 forbids the evidence payload. For the submitted target lists, changing settle's evidence transition
 * or a rebind/end target effect has no other shape-valid value (ControlLifecycleEvidence.validShape), so schema also fails.
 * Every writer's before state carries one Applied row of another command, so the evidence condition compares a non-empty
 * prefix (the kept old rows) as well as this command's node.
 */
class DemandAuthBacklogContract43Test {
    private class W(val name: String, val plan: DemandAuthPlan, val source: Preferences, val runtime: DemandAuthRuntime,
        val successor: String, val guard: String?, val floorRead: Boolean, val retryRead: Boolean, val authRead: Boolean)
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val sibling = F.request(id = "sibling", owner = "B")
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val holdKey = ControlRecordKeys.payload(ControlKind.HOLD)
    private val evidenceKey = ControlLifecycleEvidenceFixtures.evidenceKey
    private val externalKey = byteArrayPreferencesKey("lifecycle-external")

    private val prior = ControlLifecycleEvidenceFixtures.wire(command = "prior")
    private fun src(vararg nodes: ControlNode): Preferences = put(F.raw(*nodes), evidenceKey, "[$prior]")
    private fun rebind(): W { val old = F.request(binding = 2, order = 50)
        return W("rebind", DemandAuthPlan.rebind(listOf(old), F.binding, orders), src(old, sibling), F.runtime(), "r", null, false, false, false) }
    private fun rebind2(): W { val old = F.request(binding = 2, order = 50); val old2 = F.request(id = "r2", binding = 2, order = 51)
        return W("rebind2", DemandAuthPlan.rebind(listOf(old, old2), F.binding, orders), src(old, old2, sibling), F.runtime(), "r", null, false, false, false) }
    private fun settle(): W { val g = F.guard(F.auth.copy(authStopped = false)); val r = F.request()
        val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        return W("settle", DemandAuthPlan.settle(listOf(r), g, null, F.binding, d, orders, "g-new", "r-new"),
            src(g, r, sibling), F.runtime(), "r-new", "g", true, true, true) }
    private fun auth(): W { val g = F.guard()
        return W("auth", F.plan(pending, g, null), src(g, sibling), F.runtime(), "r-new", "g", true, true, true) }
    private fun end(): W { val g = F.guard(auth = oldAuth, wait = 30000); val old = F.request(binding = 2)
        val closure = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
        return W("end", DemandAuthPlan.end(g, listOf(old), F.binding, closure, F.binding, orders), src(g, old, sibling),
            F.runtime(closure = closure), "r", "g", false, false, true) }

    private fun arr(raw: Preferences, key: Preferences.Key<String>) = Json.parseToJsonElement(raw[key]!!) as JsonArray
    private fun same(a: Any?, b: Any?) = if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
    private fun effectInputs(raw: Preferences, w: W, r: ControlRecordRead.Supported): List<Any?> {
        fun at(id: String): Any? = r.locations(id).let { l -> if (l.size == 1) (l[0].second as? ControlEntryRead.Interpreted)?.value else "locations=${l.size}" }
        val g = w.guard?.let { at(it) }; val sg = g as? ScheduleGuardV1
        val floor = if (w.floorRead) sg?.floor.let { f -> listOf(f != null, f?.remainingAt(checkNotNull(w.plan.mergeNow))) } else null
        val retry = if (w.retryRead) at(w.successor).let { r -> if (r is DemandV1) listOf(r.intent, r.ownerUid, r.binding, r.raisedAt.origin, r.raisedAt.value) else r } else null
        return listOf(if (g != null && sg == null) "guard=$g" else null, floor, if (w.authRead) sg?.auth else null, retry)
    }
    private fun expectedRows(before: Preferences, input: ControlLifecycleDescriptor): List<JsonElement> {
        val byId = input.targets.associateBy { it.target.id }
        val kept = arr(before, demandKey).mapNotNull { e ->
            val t = ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.let { byId[it] }
            if (t == null) e else t.after?.toPayloadEntry()?.fields }
        return kept + input.targets.filter { it.target.effect == LifecycleEffect.CREATE }.map { it.after!!.toPayloadEntry().fields }
    }
    private fun vec(w: W, c: CommandRef, input: ControlLifecycleDescriptor, before: Preferences, cand: Preferences, twin: Preferences): List<Pair<String, Boolean>> {
        val payloads = listOf(cand[demandKey]!!, cand[evidenceKey]!!)
        val size = payloads.all { it.toByteArray(Charsets.UTF_8).size <= 65536 }
        val depth = payloads.all { depthOf(Json.parseToJsonElement(it)) <= 64 }
        val r = if (size && depth) ControlRecordReader().read(cand) as? ControlRecordRead.Supported else null
        if (r == null) return listOf("size" to size, "depth" to depth, "read" to !(size && depth),
            "schema" to true, "effects" to true, "rows" to true, "external" to true, "evidence" to true)
        val keys = (before.asMap().keys + cand.asMap().keys) - setOf(demandKey, evidenceKey)
        return listOf("size" to true, "depth" to true, "read" to true,
            "schema" to (r.schemaVersion == 2 && !r.hasUninterpretable && !r.hasUninterpretableMetadata),
            "effects" to (effectInputs(cand, w, r) == effectInputs(twin, w, F.read(twin))),
            "rows" to (arr(cand, demandKey).toList() == expectedRows(before, input)),
            "external" to keys.all { same(before.asMap()[it], cand.asMap()[it]) },
            "evidence" to (arr(cand, evidenceKey).toList() == arr(before, evidenceKey).toList() +
                Json.parseToJsonElement(ControlLifecycleEvidenceFixtures.wire(c))))
    }
    /** [prefix] changes the before state and the candidate together (schema cells); [edit] changes only the candidate. */
    private fun no(item: String, w: W, only: String, prefix: ((Preferences) -> Preferences)? = null,
        inputEdit: ((ControlLifecycleDescriptor) -> ControlLifecycleDescriptor)? = null, edit: (Preferences) -> Preferences = { it }) {
        assertNull("fixture plan must be prepared", w.plan.preparationFailure)
        assertEquals("fixture: the effect-input flags follow the plan", listOf(w.floorRead, w.retryRead, w.authRead),
            listOf(w.plan.decision != null, w.plan.decision != null, w.plan.guardAfter != null))
        val (c, raw) = F.apply(w.plan, w.source, w.runtime)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        assertEquals("truth vector: normal candidate", emptySet<String>(), V.falses(vec(w, c, input, w.source, raw, raw)))
        assertTrue("positive twin: the ${w.name} writer's own candidate is valid", F.transition.validCandidate(c, input, F.read(w.source), raw))
        val before = prefix?.invoke(w.source) ?: w.source
        val altered = edit(prefix?.invoke(raw) ?: raw)
        val alteredInput = inputEdit?.invoke(input) ?: input
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vec(w, c, alteredInput, before, altered, raw)))
        assertFalse(F.atomic("Z.vc.$item.${w.name}"), F.transition.validCandidate(c, alteredInput, F.read(before), altered))
    }
    private fun depthOf(e: JsonElement): Int = 1 + when (e) {
        is JsonArray -> e.maxOfOrNull { depthOf(it) } ?: 0
        is JsonObject -> e.values.maxOfOrNull { depthOf(it) } ?: 0
        else -> 0 }  // a scalar element counts as one level, as in the codec's tree scan (:306–313)
    private fun put(raw: Preferences, key: Preferences.Key<String>, v: String) = raw.toMutablePreferences().apply { this[key] = v }.toPreferences()
    private fun deep(n: Int): JsonElement = (1..n).fold(JsonArray(emptyList()) as JsonElement) { e, _ -> JsonArray(listOf(e)) }

    // read — a changed payload over the size or depth limit (codec defaults 65 536 bytes · depth 64)
    private fun size(w: W, key: Preferences.Key<String>, item: String) = no(item, w, "size") { put(it, key, it[key]!! + " ".repeat(65536)) }
    private fun depth(w: W, key: Preferences.Key<String>, item: String) = no(item, w, "depth") { put(it, key, JsonArray(listOf(deep(70)) + arr(it, key)).toString()) }
    @Test fun C12sizeDemand_rebind() = size(rebind(), demandKey, "C12sizeDemand")
    @Test fun C12sizeDemand_settle() = size(settle(), demandKey, "C12sizeDemand")
    @Test fun C12sizeDemand_auth() = size(auth(), demandKey, "C12sizeDemand")
    @Test fun C12sizeDemand_end() = size(end(), demandKey, "C12sizeDemand")
    @Test fun C12depthDemand_rebind() = depth(rebind(), demandKey, "C12depthDemand")
    @Test fun C12depthDemand_settle() = depth(settle(), demandKey, "C12depthDemand")
    @Test fun C12depthDemand_auth() = depth(auth(), demandKey, "C12depthDemand")
    @Test fun C12depthDemand_end() = depth(end(), demandKey, "C12depthDemand")
    @Test fun C12sizeEvidence_rebind() = size(rebind(), evidenceKey, "C12sizeEvidence")
    @Test fun C12sizeEvidence_settle() = size(settle(), evidenceKey, "C12sizeEvidence")
    @Test fun C12sizeEvidence_auth() = size(auth(), evidenceKey, "C12sizeEvidence")
    @Test fun C12sizeEvidence_end() = size(end(), evidenceKey, "C12sizeEvidence")
    @Test fun C12depthEvidence_rebind() = depth(rebind(), evidenceKey, "C12depthEvidence")
    @Test fun C12depthEvidence_settle() = depth(settle(), evidenceKey, "C12depthEvidence")
    @Test fun C12depthEvidence_auth() = depth(auth(), evidenceKey, "C12depthEvidence")
    @Test fun C12depthEvidence_end() = depth(end(), evidenceKey, "C12depthEvidence")

    // schema — an uninterpretable non-target row / evidence entry present in the before state and kept in the candidate
    private fun lead(key: Preferences.Key<String>): (Preferences) -> Preferences = { put(it, key, JsonArray(listOf(JsonPrimitive(7)) + arr(it, key)).toString()) }
    private fun opaqueRow(w: W) = no("C12opaqueRow", w, "schema", lead(demandKey))
    private fun opaqueEvidence(w: W) = no("C12opaqueEvidence", w, "schema", lead(evidenceKey))
    @Test fun C12opaqueRow_rebind() = opaqueRow(rebind())
    @Test fun C12opaqueRow_settle() = opaqueRow(settle())
    @Test fun C12opaqueRow_auth() = opaqueRow(auth())
    @Test fun C12opaqueRow_end() = opaqueRow(end())
    @Test fun C12opaqueEvidence_rebind() = opaqueEvidence(rebind())
    @Test fun C12opaqueEvidence_settle() = opaqueEvidence(settle())
    @Test fun C12opaqueEvidence_auth() = opaqueEvidence(auth())
    @Test fun C12opaqueEvidence_end() = opaqueEvidence(end())

    // rows — DEMAND rows that requiredEffects does not read
    private fun row(id: String, change: (JsonObject) -> JsonObject?): (Preferences) -> Preferences = { F.patch(it, id, change) }
    private fun c02(w: W) = no("C02", w, "rows", edit = row("sibling") { null })
    @Test fun C02_rebind() = c02(rebind())
    @Test fun C02_settle() = c02(settle())
    @Test fun C02_auth() = c02(auth())
    @Test fun C02_end() = c02(end())
    @Test fun C01_settle() = no("C01", settle(), "rows") { put(it, demandKey, JsonArray(arr(it, demandKey) + F.request().toPayloadEntry().fields).toString()) }
    @Test fun C03_rebind() = no("C03", rebind(), "rows", edit = row("r") { null })
    @Test fun C03_end() = no("C03", end(), "rows", edit = row("r") { null })
    private fun c04(w: W, field: String, v: JsonElement) = no("C04$field", w, "rows", edit = row(w.successor) { r -> JsonObject(r + (field to v)) })
    @Test fun C04ownerUid_rebind() = c04(rebind(), "ownerUid", JsonPrimitive("B"))
    @Test fun C04intent_rebind() = c04(rebind(), "intent", JsonPrimitive("IF_STALE"))
    @Test fun C04binding_rebind() = c04(rebind(), "binding", JsonPrimitive(4))
    @Test fun C04originLifetimeId_rebind() = c04(rebind(), "originLifetimeId", JsonPrimitive("other"))
    @Test fun C04raisedAt_rebind() = c04(rebind(), "raisedAt", JsonPrimitive(99))
    @Test fun C04ownerUid_end() = c04(end(), "ownerUid", JsonPrimitive("B"))
    @Test fun C04intent_end() = c04(end(), "intent", JsonPrimitive("IF_STALE"))
    @Test fun C04binding_end() = c04(end(), "binding", JsonPrimitive(4))
    @Test fun C04originLifetimeId_end() = c04(end(), "originLifetimeId", JsonPrimitive("other"))
    @Test fun C04raisedAt_end() = c04(end(), "raisedAt", JsonPrimitive(99))
    @Test fun C09floor_end() = no("C09floor", end(), "rows", edit = row("g") { g -> JsonObject(g - "floor") })
    // floor origin only (remainingAt reads the origin only for being non-empty — ControlFacts.kt:62–68): effects hold, rows fail
    private fun c09origin(w: W) = no("C09floorOrigin", w, "rows", edit = row("g") { g -> F.mutateChild(g, "floor", "originLifetimeId", JsonPrimitive("other")) })
    @Test fun C09floorOrigin_settle() = c09origin(settle())
    @Test fun C09floorOrigin_auth() = c09origin(auth())

    // effects — the same missing/wrong effect in the descriptor's target after and in the candidate (partial landing)
    private fun partial(item: String, w: W, id: String, change: (JsonObject) -> JsonObject) = no(item, w, "effects",
        edit = row(id) { change(it) }, inputEdit = { input ->
            assertEquals("fixture: exactly one target to change", 1, input.targets.count { it.target.id == id && it.after != null })
            ControlLifecycleDescriptor(input.operationId, input.transition, input.targets.map { t ->
                if (t.target.id == id) t.copy(after = ControlNode.of(change(t.after!!.toPayloadEntry().fields))) else t },
                input.executor, input.namespace, input.requiredUnchanged, input.demandAuth, input.removeEmptyGuard, input.recoverHold) })
    private val noFloor: (JsonObject) -> JsonObject = { g -> JsonObject(g - "floor") }
    private val badAuth: (JsonObject) -> JsonObject = { g -> F.mutateChild(g, "auth", "authStateOrder", JsonPrimitive(999)) }
    private val badOwner: (JsonObject) -> JsonObject = { r -> JsonObject(r + ("ownerUid" to JsonPrimitive("B"))) }
    @Test fun C09effects_settle() = partial("C09effects", settle(), "g", noFloor)
    @Test fun C09effects_auth() = partial("C09effects", auth(), "g", noFloor)
    @Test fun C08effects_settle() = partial("C08effects", settle(), "g", badAuth)
    @Test fun C08effects_auth() = partial("C08effects", auth(), "g", badAuth)
    @Test fun C08effects_end() = partial("C08effects", end(), "g", badAuth)
    @Test fun C04effects_settle() = partial("C04effects", settle(), "r-new", badOwner)
    @Test fun C04effects_auth() = partial("C04effects", auth(), "r-new", badOwner)

    // external — an unrelated payload string / an external ByteArray
    private fun c11p(w: W) = no("C11payload", w, "external") { put(it, holdKey, "[ ]") }
    private fun c11x(w: W) = no("C11external", w, "external") { it.toMutablePreferences().apply { this[externalKey] = byteArrayOf(8, 9) }.toPreferences() }
    @Test fun C11payload_rebind() = c11p(rebind())
    @Test fun C11payload_settle() = c11p(settle())
    @Test fun C11payload_auth() = c11p(auth())
    @Test fun C11payload_end() = c11p(end())
    @Test fun C11external_rebind() = c11x(rebind())
    @Test fun C11external_settle() = c11x(settle())
    @Test fun C11external_auth() = c11x(auth())
    @Test fun C11external_end() = c11x(end())

    // evidence — own Applied missing / transition / one target effect / target order (shape-valid values only)
    private fun own(change: (JsonObject) -> JsonObject): (Preferences) -> Preferences = { raw ->
        val a = arr(raw, evidenceKey); put(raw, evidenceKey, JsonArray(a.dropLast(1) + change(a.last() as JsonObject)).toString()) }
    private fun targets(o: JsonObject) = o["targets"] as JsonArray
    private fun c10missing(w: W) = no("C10missing", w, "evidence") { raw -> put(raw, evidenceKey, JsonArray(arr(raw, evidenceKey).dropLast(1)).toString()) }
    @Test fun C10missing_rebind() = c10missing(rebind())
    @Test fun C10missing_settle() = c10missing(settle())
    @Test fun C10missing_auth() = c10missing(auth())
    @Test fun C10missing_end() = c10missing(end())
    private fun c10transition(w: W, to: LifecycleTransition) = no("C10transition", w, "evidence", edit = own { JsonObject(it + ("transition" to JsonPrimitive(to.name))) })
    @Test fun C10transition_rebind() = c10transition(rebind(), LifecycleTransition.END_AUTH_BINDING)
    @Test fun C10transition_auth() = c10transition(auth(), LifecycleTransition.SETTLE_QUERY)
    @Test fun C10transition_end() = c10transition(end(), LifecycleTransition.REBIND_REQUESTS)
    private fun c10effect(w: W, id: String, to: LifecycleEffect) = no("C10effect", w, "evidence", edit = own { o ->
        JsonObject(o + ("targets" to JsonArray(targets(o).map { t -> t as JsonObject
            if ((t["id"] as JsonPrimitive).content == id) JsonObject(t + ("effect" to JsonPrimitive(to.name))) else t }))) })
    @Test fun C10effect_settle() = c10effect(settle(), "r-new", LifecycleEffect.REPLACE)
    @Test fun C10effect_auth() = c10effect(auth(), "g", LifecycleEffect.CREATE)
    private fun c10order(w: W, i: Int, j: Int) = no("C10order", w, "evidence", edit = own { o ->
        val t = targets(o).toMutableList(); val x = t[i]; t[i] = t[j]; t[j] = x; JsonObject(o + ("targets" to JsonArray(t))) })
    @Test fun C10order_rebind() = c10order(rebind2(), 0, 1)
    @Test fun C10order_settle() = c10order(settle(), 1, 2)
    @Test fun C10order_auth() = c10order(auth(), 0, 1)
    @Test fun C10order_end() = c10order(end(), 0, 1)
}
