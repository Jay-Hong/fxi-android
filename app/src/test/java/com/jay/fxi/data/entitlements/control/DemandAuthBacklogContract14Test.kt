package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fourteenth file: the 26 candidates held OPEN in STEP3S because their only role failure was in a
 * 5b test whose premises are weaker than design §9.1 539. Each test keeps the 5b fixture and puts an independent
 * premise before the same role assertion:
 *  - part a (DemandAuthBoundaryTest one-liners): a single-false vector over every rejection condition of the
 *    production predicate, written as test-side expressions;
 *  - part b: the eligibility truth vector (W.query*), the preparation vector (P.mergeBoot, P.callerNow), the read
 *    location of the existing guard (W.guardUnique), a canonical-journal positive twin plus a one-key diff
 *    (W.journal), and a positive twin plus an exact diff of the one mutated field (C09·C10·C11, A11b).
 * No production validator produces an expected value; positive twins are recorded as twins, not as premises of
 * the negative input. The original 5b tests stay as regression evidence.
 */
class DemandAuthBacklogContract14Test {
    private val r get() = demand(F.request())!!
    // Preferences values may be ByteArray, which Map equality compares by reference; compare contents instead.
    private fun others(p: Preferences, except: Preferences.Key<*>) =
        p.asMap().filterKeys { it != except }.mapValues { (_, v) -> if (v is ByteArray) v.toList() else v }

    // ── part a ──────────────────────────────────────────────────────────────────────────────────────────────

    // consumes(outcome, intent, reapproval) — DemandAuthFacts.kt:129-140.

    private fun consumesNo(id: String, outcome: EntitlementsOutcome, intent: RefreshIntent, reapproval: LifecycleReapproval) {
        F.onlyFalse(reapproval != LifecycleReapproval.BLOCKED, outcome !is EntitlementsOutcome.Pending,
            outcome !is EntitlementsOutcome.Indeterminate,
            outcome != EntitlementsOutcome.KrxEntitlementRequired || intent != RefreshIntent.FORCE_PREMIUM)
        assertFalse(F.eligible(id), DemandAuthBoundary.consumes(outcome, intent, reapproval))
    }
    @Test fun Q07_strict() = consumesNo("Q07.strict", EntitlementsOutcome.StableActive(true), RefreshIntent.FORCE_PREMIUM,
        LifecycleReapproval.BLOCKED)
    @Test fun Q09_strict() = consumesNo("Q09.strict", EntitlementsOutcome.KrxEntitlementRequired, RefreshIntent.FORCE_PREMIUM,
        LifecycleReapproval.NOT_REQUIRED)

    // rebind(before, binding, intent) — DemandAuthFacts.kt:115-120.

    @Test fun Q11a_strict() {
        val old = r.copy(ownerUid = "B", binding = 2); val x = F.binding.executor; val intent = RefreshIntent.FORCE_PREMIUM
        F.onlyFalse(old.ownerUid == x.ownerUid, !(old.binding == x.binding && old.raisedAt.origin == x.originLifetimeId),
            intent >= old.intent)
        assertFalse(F.eligible("Q11a.strict"), DemandAuthBoundary.rebind(old, F.binding, intent))
    }

    // order(grant, binding, after) — DemandAuthFacts.kt:108-114.

    private fun orderNo(id: String, g: LifecycleOrderGrant, after: Long) {
        F.onlyFalse(g.origin == F.binding.executor.originLifetimeId, g.value == maxOf(g.previous, g.bindingStart, g.after) + 1,
            g.value > F.binding.startedOrder, g.value > after)
        assertFalse(F.eligible(id), DemandAuthBoundary.order(g, F.binding, after))
    }
    @Test fun Q12b_strict() = orderNo("Q12b.strict", LifecycleOrderGrant(F.life, 21, 1, 21, 23), 21)
    @Test fun Q16a_strict() = orderNo("Q16a.strict", LifecycleOrderGrant(F.life, 0, 0, 0, 1), 0)
    @Test fun Q16d_strict() = orderNo("Q16d.strict", LifecycleOrderGrant(F.life, 20, 1, 20, 21), 21)

    // seconds(seconds) — DemandAuthFacts.kt:207-211.

    @Test fun A15negative_strict() {
        val s = -1L
        F.onlyFalse(s >= 0, s <= Long.MAX_VALUE / 1000)
        assertFalse(F.eligible("A15negative.strict"), DemandAuthBoundary.seconds(s))
    }

    // acceptance(d, runtime) — DemandAuthFacts.kt:141-151. With the answer absent, the later identity comparisons are
    // null-to-null in this fixture; the vector states that as a fact of the input instead of leaving it implicit.

    @Test fun Q15a_strict() {
        val q = F.query.copy(boundIdentity = null, fence = F.fence.copy(ownerUid = null))
        val d = F.decision(answered = null, q = q)
        val rt = F.runtime(live = null, registrations = listOf(d.registration))
        F.onlyFalse(d.source == LifecycleQuerySource.REGISTERED_QUERY, d.registration in rt.registrations, d.answeredAs != null,
            d.acceptedBeforeGeneration == d.query.generation, d.acceptedBeforeFence == d.query.fence,
            d.answeredAs?.ownerUid == d.query.fence.ownerUid, d.answeredAs == rt.liveIdentity, d.answeredAs == d.query.boundIdentity)
        assertFalse(F.eligible("Q15a.strict"), DemandAuthBoundary.acceptance(d, rt))
    }

    // ── part b ──────────────────────────────────────────────────────────────────────────────────────────────

    // W.query* — DemandAuthLinkTest:48-65 fixtures with the C4 eligibility vector (exactly the named condition false).

    private fun ineligible(id: String, only: String, plan: DemandAuthPlan, runtime: DemandAuthRuntime, source: Preferences) {
        F.schema(source)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("truth vector: eligibility", setOf(only), V.falses(V.eligibility(plan, runtime, source)))
        assertNotNull(F.eligible(id), F.transition.eligibility(plan, runtime, F.read(source)))
    }
    @Test fun W_queryBinding_strict() {
        val d = F.decision(q = F.query.copy(binding = 4)); val g = F.guard(); val r = F.request()
        ineligible("W.queryBinding.strict", "q.binding", F.plan(d, g, r), F.runtime(registrations = listOf(d.registration)), F.raw(g, r))
    }
    @Test fun W_queryOrigin_strict() {
        val d = F.decision(q = F.query.copy(order = EventOrderV1(LifetimeId("old"), 21)), origin = LifetimeId("old"))
        val g = F.guard(); val r = F.request()
        ineligible("W.queryOrigin.strict", "q.origin", F.plan(d, g, r), F.runtime(registrations = listOf(d.registration)), F.raw(g, r))
    }
    @Test fun W_queryOwner_strict() {
        val who = F.identity.copy(ownerUid = "B")
        val q = F.query.copy(fence = F.fence.copy(ownerUid = "B"), boundIdentity = who)
        val d = F.decision(q = q, answered = who, after = q.fence)
        val g = F.guard(); val r = F.request()
        val source = F.raw(g, r).toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "B" }.toPreferences()
        ineligible("W.queryOwner.strict", "q.owner", F.plan(d, g, r), F.runtime(live = who, registrations = listOf(d.registration)), source)
    }

    // W.guardUnique — DemandAuthTransition.kt:61-64. The existing guard is asserted from the read location.

    @Test fun W_guardUnique_strict() {
        val raw = F.raw(F.guard(auth = null))
        F.schema(raw)
        val read = F.read(raw)
        val g = read.locations("g").single()
        assertEquals("fixture: g is a DEMAND row", ControlKind.DEMAND, g.first)
        assertTrue("fixture: g reads as a schedule guard", (g.second as? ControlEntryRead.Interpreted)?.value is ScheduleGuardV1)
        assertFalse(F.eligible("W.guardUnique.strict"), F.transition.guardCreationAvailable(read))
    }

    // W.journal — DemandAuthTransition.kt:33-34. The canonical journal confirms first; only the journal key differs.

    @Test fun W_journal_strict() {
        val g = F.guard(auth = null)
        val normal = F.raw(g)
        val broken = normal.toMutablePreferences().apply { this[DataStoreAccessEpochStore.PURGE_JOURNAL] = "unknown-format" }.toPreferences()
        F.schema(normal); F.schema(broken)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life), "g-new", "r-new")
        val c = F.command(p); val input = (c.body as ControlCommandBody.Lifecycle).input
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: writer gates", emptySet<String>(),
            V.falses(V.decideGates(p, F.context(), broken)))
        assertEquals("fixture: eligibility", emptySet<String>(),
            V.falses(V.eligibility(p, F.runtime(), broken)))
        assertEquals("fixture: only journal absence differs", setOf("dt.journalAbsent"),
            V.falses(V.commonPremises(p, broken, c.id)))
        assertNull("fixture: normal journal is absent", normal[DataStoreAccessEpochStore.PURGE_JOURNAL])
        assertEquals("fixture: malformed journal", "unknown-format",
            broken[DataStoreAccessEpochStore.PURGE_JOURNAL])
        assertTrue("positive twin: the canonical journal confirms",
            ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(normal), F.context(), false, false) is RecordTransactionDecision.Confirm)
        val key = DataStoreAccessEpochStore.PURGE_JOURNAL
        assertEquals("fixture: every other key is unchanged", others(normal, key), others(broken, key))
        assertNotEquals("fixture: the journal changed", normal[key], broken[key])
        val actual = ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(broken), F.context(), false, false)
        assertFalse(F.eligible("W.journal.strict"), actual is RecordTransactionDecision.Confirm)
    }

    // P.mergeBoot — DemandAuthPlan.kt:161 on the answer path; the C4 answer-preparation vector with only `merge` false.

    private fun answerFacts(d: AcceptedQueryDecision, guard: ControlNode?): List<Pair<String, Boolean>> {
        val seconds = when (val outcome = d.outcome) {
            is EntitlementsOutcome.Pending -> outcome.retryAfterSeconds
            is EntitlementsOutcome.Indeterminate -> outcome.retryAfterSeconds
            else -> null
        }
        return listOf(
            "guardParses" to (guard == null || guard(guard) != null),
            "seconds" to (seconds == null || (seconds >= 0 && seconds <= Long.MAX_VALUE / 1000)),
            "floorOrigin" to (d.floorOrigin == d.query.order.origin),
            "minDelay" to (d.decisionMinDelayMillis >= 0),
            "captureBoot" to (seconds == null || d.capture.bootId != ""),
            "captureElapsed" to (seconds == null || d.capture.elapsedMillis >= 0),
            "merge" to (d.mergeNow.bootId != "" && d.mergeNow.elapsedMillis >= 0))
    }
    @Test fun P_mergeBoot_strict() {
        val d = F.decision(merge = F.now.copy(bootId = ""), minDelay = 1000); val g = F.guard()
        assertEquals("truth vector: answer preparation", setOf("merge"), V.falses(answerFacts(d, g)))
        assertNotNull(F.eligible("P.mergeBoot.strict"), F.plan(d, g).preparationFailure)
    }

    // P.callerNow — DemandAuthPlan.kt:63-74 on the CALLER path: the guard parses and has AUTH, its floor anchor is
    // valid, and only the caller's boot reading is invalid, so `remainingAt` returns null at DP:70-71.

    @Test fun P_callerNow_strict() {
        val order = LifecycleOrderSource(F.life, 21)
        val now = F.now.copy(bootId = "")
        val caller = LifecycleCaller("c", LifecycleCallerOrigin.CALLER, F.binding, order.issue(1)!!, RefreshIntent.FORCE_PREMIUM, now)
        val g = F.guard(wait = 30000); val floor = guard(g)?.floor
        assertEquals("fixture: valid existing floor",
            FloorV1("boot", 10000, 30000, F.life), floor)
        assertEquals("fixture: valid caller elapsed", 10000L, now.elapsedMillis)
        F.onlyFalse(guard(g) != null, guard(g)?.auth != null,
            floor == null || (floor.anchorBootId != "" && floor.anchorElapsedMillis >= 0 && floor.waitMillis >= 0 &&
                floor.originLifetimeId.value.isNotEmpty()),
            now.bootId != "" && now.elapsedMillis >= 0)
        assertNotNull(F.eligible("P.callerNow.strict"),
            DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(caller), order, "g", "retry").preparationFailure)
    }

    // C09·C10·C11 — DemandAuthCandidateTest:112-116 `candidateNo` input. The unmodified candidate is a positive twin;
    // the mutated candidate differs from it only in the named key, and in that key only as stated.

    private fun candidateNo(id: String, key: Preferences.Key<*>, mutate: (Preferences) -> Preferences, onlyChange: (Preferences, Preferences) -> Unit) {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(F.auth, null), null)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val source = F.raw(p.guardBefore!!)
        val (c, raw) = F.apply(p, source)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        assertTrue("positive twin: the unmodified candidate is valid", F.transition.validCandidate(c, input, F.read(source), raw))
        val altered = mutate(raw)
        assertEquals("fixture: every other key is unchanged", others(raw, key), others(altered, key))
        onlyChange(raw, altered)
        assertFalse(F.atomic(id), F.transition.validCandidate(c, input, F.read(source), altered))
    }
    private fun demandRows(raw: Preferences) =
        (Json.parseToJsonElement(raw[ControlRecordKeys.payload(ControlKind.DEMAND)]!!) as JsonArray).map { it as JsonObject }
    @Test fun C09floorOrigin_strict() = candidateNo("C09floorOrigin.strict", ControlRecordKeys.payload(ControlKind.DEMAND),
        { raw -> F.patch(raw, "g") { F.mutateChild(it, "floor", "originLifetimeId", JsonPrimitive("other")) } }) { raw, altered ->
        val before = demandRows(raw); val after = demandRows(altered)
        assertEquals("fixture: the other DEMAND rows are unchanged", before.filter { it["id"] != JsonPrimitive("g") },
            after.filter { it["id"] != JsonPrimitive("g") })
        val b = before.single { it["id"] == JsonPrimitive("g") }; val a = after.single { it["id"] == JsonPrimitive("g") }
        assertEquals("fixture: only the floor changed in g", JsonObject(b - "floor"), JsonObject(a - "floor"))
        val bf = b["floor"] as JsonObject; val af = a["floor"] as JsonObject
        assertEquals("fixture: only the floor origin changed", JsonObject(bf - "originLifetimeId"), JsonObject(af - "originLifetimeId"))
        assertEquals("fixture: the recorded origin was the executor's", JsonPrimitive(F.life.value), bf["originLifetimeId"])
        assertEquals("fixture: changed origin is other",
            JsonPrimitive("other"), af["originLifetimeId"])
    }
    @Test fun C10effect_strict() = candidateNo("C10effect.strict", ControlLifecycleEvidenceFixtures.evidenceKey,
        { raw -> raw.toMutablePreferences().apply {
            val k = ControlLifecycleEvidenceFixtures.evidenceKey; this[k] = this[k]!!.replace("REPLACE", "CREATE") }.toPreferences() }) { raw, altered ->
        val k = ControlLifecycleEvidenceFixtures.evidenceKey
        assertEquals("fixture: the evidence text names REPLACE exactly once", 1, Regex("REPLACE").findAll(raw[k]!!).count())
        val b = (Json.parseToJsonElement(raw[k]!!) as JsonArray).single() as JsonObject
        val a = (Json.parseToJsonElement(altered[k]!!) as JsonArray).single() as JsonObject
        assertEquals("fixture: only the targets changed in the evidence row", JsonObject(b - "targets"), JsonObject(a - "targets"))
        val bt = b["targets"] as JsonArray; val at = a["targets"] as JsonArray
        assertEquals("fixture: the target count is unchanged", bt.size, at.size)
        val changed = bt.indices.filter { bt[it] != at[it] }
        assertEquals("fixture: exactly one target changed", 1, changed.size)
        val i = changed.single(); val bi = bt[i] as JsonObject; val ai = at[i] as JsonObject
        assertEquals("fixture: that target was REPLACE", JsonPrimitive("REPLACE"), bi["effect"])
        assertEquals("fixture: that target is now CREATE", JsonPrimitive("CREATE"), ai["effect"])
        assertEquals("fixture: only its effect changed", JsonObject(bi - "effect"), JsonObject(ai - "effect"))
    }
    @Test fun C11external_strict() = candidateNo("C11external.strict", byteArrayPreferencesKey("lifecycle-external"),
        { raw -> raw.toMutablePreferences().apply { this[byteArrayPreferencesKey("lifecycle-external")] = byteArrayOf(8, 9) }.toPreferences() }) { raw, altered ->
        val k = byteArrayPreferencesKey("lifecycle-external")
        assertFalse("fixture: the external value changed", raw[k]?.contentEquals(altered[k]!!) ?: false)
    }

    // A11b — DemandAuthCandidateTest:150-158 `floorBoundary` input. The recorded floor is a positive twin; the altered
    // node differs from it only in floor.waitMillis.

    @Test fun A11b_strict() {
        val old = F.guard(wait = 60000)
        val result = ControlObligations.recordFloor(old, F.now, 30000, F.life) as ControlWriteResult.Written
        val expected = FloorV1("boot", 10000, 60000, F.life)
        assertTrue("positive twin: the recorded floor is valid",
            ControlObligations.validFloorResult(old, result.node, guard(result.node)!!, expected))
        val altered = F.node(F.mutateChild(result.node.toPayloadEntry().fields, "floor", "waitMillis", JsonPrimitive(30000)).toString())
        val before = result.node.toPayloadEntry().fields; val after = altered.toPayloadEntry().fields
        assertEquals("fixture: only the floor changed", JsonObject(before - "floor"), JsonObject(after - "floor"))
        assertEquals("fixture: only waitMillis changed", JsonObject((before["floor"] as JsonObject) - "waitMillis"),
            JsonObject((after["floor"] as JsonObject) - "waitMillis"))
        assertNotEquals("fixture: the wait changed", (before["floor"] as JsonObject)["waitMillis"], (after["floor"] as JsonObject)["waitMillis"])
        assertEquals("fixture: original non-floor serializations are preserved",
            old.toPayloadEntry().fields.filterKeys { it != "floor" }
                .mapValues { (_, value) -> value.toString() },
            altered.toPayloadEntry().fields.filterKeys { it != "floor" }
                .mapValues { (_, value) -> value.toString() })
        assertEquals("fixture: altered floor",
            FloorV1("boot", 10000, 30000, F.life), guard(altered)!!.floor)
        assertNotEquals("fixture: altered floor violates expected tuple",
            expected, guard(altered)!!.floor)
        assertFalse(F.atomic("A11b.strict"), ControlObligations.validFloorResult(old, altered, guard(altered)!!, expected))
    }
}
