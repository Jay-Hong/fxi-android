package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5e-2 atomic candidate contract (plan v1 row 8). Design
 * `d2b5_demand_auth_design_r3_codex.md` L443 (RECOVER_INTENT candidate = required epoch/marker +
 * exact journal + required new REQUEST + intent removal + Applied; the validator re-checks the
 * source lower bound, each axis handover, full target linkage and non-target key preservation; no
 * partial save), L449–451 (one source row, other intents of the same session stay), §9.4 C01–C07,
 * C10–C12 and I05a–b; C08/C09 guard-change and floor-handover effects are NA for the intent writer,
 * but the existing guard's AUTH and floor originals must be preserved (plan v1 row 8).
 * The good candidate is a literal written from those lines, never from buildCandidate. Negatives
 * start from a literal good candidate; single-effect cases are distinguished from the I05a
 * composite counterexample (source kept and sibling lost together), which is not counted as a
 * single-predicate kill for C01/C02. C10 transition/effect/order variants also break the Applied
 * shape, so they are not exact-comparison evidence for an interpretable Applied. C12 (payload size
 * and depth) is assigned to RecoverIntentCandidateLimitsContractTest. The named boundary is the
 * skeleton v2 §4 signature (validCandidate / requiredEffects / survivorsPreserved /
 * evidencePreserved / expectedTargets). Direct boundary only: a production path that stores an
 * arbitrary candidate is not required and must not exist.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentCandidateContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private val codec = ControlPayloadCodec()
    private val writer = RecoverIntentTransition(codec)

    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, krxFresh))
    private val grant = LifecycleOrderGrant(newLife, 21, 1, 0, 22)
    private val external = byteArrayPreferencesKey("lifecycle-external")

    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, axis: String, target: String?, id: String, session: String) =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"$axis","targetEpoch":${q(target)}}""")
    private val source = intent("A", "CAPABILITY", "k", "r", "session")
    private val sibling = intent("A", "USER", "u", "r2", "session")
    private val otherSession = intent("A", "CAPABILITY", "k", "r3", "other")
    private val departed = intent("B", "CAPABILITY", null, "r", "session")
    private val guard = ControlObligationFixtures.node(ControlObligationFixtures.guard) // AUTH + floor, preserved verbatim
    private val old = DemandAuthFixtures.request(id = "old", owner = "A", intent = com.jay.fxi.data.entitlements.RefreshIntent.IF_STALE)
    private val foreign = DemandAuthFixtures.request(id = "rb", owner = "B")
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)

    private fun before(src: ControlNode = source, krxMarker: Boolean = true): Preferences = ControlLifecycleEvidenceFixtures.raw(demand = payload(listOf(guard, old, foreign)))
        .toMutablePreferences().apply {
            this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = krxMarker
            this[intentKey] = payload(listOf(src, sibling, otherSession))
            this[sealKey] = "[" + ControlObligationFixtures.seal + "]"
        }.toPreferences()
    private fun closure(src: ControlNode) = HoldRecoveryClosure.AfterRestart(src, executor, "previous-tracker", true, true)
    private fun input(src: ControlNode = source) = RecoverIntentInput(src, FenceV1("A", "u", "k"), binding, closure(src))
    private fun plan(src: ControlNode = source) = RecoverIntentPlan.prepare(input(src), ids, LifecycleOrderSource(newLife, 21))
    private fun command(p: RecoverIntentPlan) = ControlLifecycleEvidenceFixtures.command(p.descriptor())
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)

    /** L412 C0a CAPABILITY: rotate KRX to the fixed UUID, marker false, exact journal, one new REQUEST, source removed, Applied. */
    private val newRequest = DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife,
        intent = com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
    private val targets = listOf(
        LifecycleTarget(ControlKind.RECOVERY_INTENT, "r", LifecycleEffect.REMOVE),
        LifecycleTarget(ControlKind.DEMAND, "new-request", LifecycleEffect.CREATE))
    private fun applied(c: CommandRef, transition: String = "RECOVER_INTENT",
        wireTargets: String = """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"},{"kind":"DEMAND","id":"new-request","effect":"CREATE"}""") =
        "[" + ControlLifecycleEvidenceFixtures.wire(transition, wireTargets, c.id, c.ownerTrackingLifetimeId.value) + "]"
    private fun good(c: CommandRef): Preferences = before().toMutablePreferences().apply {
        this[KRX_EPOCH] = krxFresh; this[MAY_CONTAIN_KRX] = false
        this[PURGE_JOURNAL] = "A||k|CAPABILITY"
        this[intentKey] = payload(listOf(sibling, otherSession))
        this[demandKey] = payload(listOf(guard, old, foreign, newRequest))
        this[ControlLifecycleEvidenceFixtures.evidenceKey] = applied(c)
    }.toPreferences()
    private fun edit(p: Preferences, change: (MutablePreferences) -> Unit) = p.toMutablePreferences().apply(change).toPreferences()
    private fun demandWith(vararg rows: ControlNode) = payload(rows.toList())

    private data class Case(val p: RecoverIntentPlan, val c: CommandRef, val raw: Preferences, val ok: Preferences)
    private fun case(): Case {
        val p = plan(); val c = command(p)
        assertEquals(grant, p.requestOrder) // issuance fact only
        val ok = good(c)
        // Fixture premises, independent of the writer under test.
        assertFalse(read(before()).hasUninterpretable); assertFalse(read(ok).hasUninterpretable)
        assertTrue(ControlLifecycleEvidence.validShape(LifecycleTransition.RECOVER_INTENT, targets))
        assertEquals(listOf(com.jay.fxi.data.entitlements.PendingPurge("A", null, "k", setOf(com.jay.fxi.data.entitlements.PurgeScope.CAPABILITY))),
            NamespaceSettlementTransition(codec).canonicalJournal(ok))
        return Case(p, c, before(), ok)
    }
    private fun rejectsWhole(id: String, k: Case, bad: Preferences) {
        assertFalse(read(bad).hasUninterpretable)
        assertFalse(atomic(id), writer.validCandidate(k.c, k.p, read(k.raw), bad))
    }

    // ---- K0: the literal candidate is accepted by every named boundary; targets are derived independently ----

    @Test fun K0_goodCandidateAccepted() {
        val k = case()
        assertEquals(atomic("K0_expectedTargets"), targets, writer.expectedTargets(input(), ids))
        assertTrue(atomic("K0_requiredEffects"), writer.requiredEffects(input(), ids, grant, read(k.raw), read(k.ok)))
        assertTrue(atomic("K0_survivors"), writer.survivorsPreserved(read(k.raw), read(k.ok), targets))
        assertTrue(atomic("K0_evidence"), writer.evidencePreserved(k.c, read(k.raw), read(k.ok), targets))
        assertTrue(atomic("K0_validCandidate"), writer.validCandidate(k.c, k.p, read(k.raw), k.ok))
    }

    // ---- C01/C03/C05/C06: required effects each omitted once (L443) ----

    @Test fun C01_C03_C05_C06_requiredEffects() {
        val k = case(); val r = read(k.raw)
        fun missing(id: String, change: (MutablePreferences) -> Unit) {
            val bad = edit(k.ok, change)
            assertFalse(read(bad).hasUninterpretable)
            assertFalse(atomic(id), writer.requiredEffects(input(), ids, grant, r, read(bad)))
        }
        missing("C01_sourceKept") { it[intentKey] = payload(listOf(source, sibling, otherSession)) }
        missing("C03_requestMissing") { it[demandKey] = demandWith(guard, old, foreign) }
        missing("C05_journalMissing") { it.remove(PURGE_JOURNAL) }
        missing("C05_journalCurrentNotTarget") { it[PURGE_JOURNAL] = "A||$krxFresh|CAPABILITY" }
        missing("C06_epochNotRotated") { it[KRX_EPOCH] = "k" }
        missing("C06_markerNotCleared") { it[MAY_CONTAIN_KRX] = true }
    }

    // ---- C04: successor REQUEST fields, one at a time (L439 step 5, L421) ----

    @Test fun C04_successorFields() {
        val k = case()
        fun bad(id: String, row: ControlNode) = rejectsWhole(id, k, edit(k.ok) { it[demandKey] = demandWith(guard, old, foreign, row) })
        bad("C04_intent", DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife,
            intent = com.jay.fxi.data.entitlements.RefreshIntent.IF_STALE, order = 22))
        bad("C04_owner", DemandAuthFixtures.request(id = "new-request", owner = "B", binding = 3, origin = newLife,
            intent = com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS, order = 22))
        bad("C04_binding", DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 4, origin = newLife,
            intent = com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS, order = 22))
        bad("C04_origin", DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = LifetimeId("life"),
            intent = com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS, order = 22))
        bad("C04_order", DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife,
            intent = com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS, order = 23))
    }

    // ---- C02 / A2 / B4 / I05a: one source row only; other intents (same and other session) and DEMAND rows survive ----

    @Test fun C02_I05_survivors() {
        val k = case(); val r = read(k.raw)
        fun lost(id: String, change: (MutablePreferences) -> Unit) {
            val bad = edit(k.ok, change)
            assertFalse(read(bad).hasUninterpretable)
            assertFalse(atomic(id), writer.survivorsPreserved(r, read(bad), targets))
        }
        lost("C02_sameSessionSibling") { it[intentKey] = payload(listOf(otherSession)) }
        lost("C02_otherSession") { it[intentKey] = payload(listOf(sibling)) }
        lost("C02_existingRequest") { it[demandKey] = demandWith(guard, foreign, newRequest) }
        // I05a: the same session's other row removed instead of the fixed source (sessionId is not clean evidence).
        rejectsWhole("I05a_sessionOnlyRemoval", k, edit(k.ok) { it[intentKey] = payload(listOf(source, otherSession)) })
    }

    // ---- C07 / existing guard: non-target axis, owner, teardown, SEAL and the guard's AUTH/floor originals (L443) ----

    @Test fun C07_nonTargetPreserved() {
        val k = case()
        rejectsWhole("C07_userEpoch", k, edit(k.ok) { it[USER_EPOCH] = "u9" })
        rejectsWhole("C07_userMarker", k, edit(k.ok) { it[MAY_CONTAIN_PREMIUM] = false })
        rejectsWhole("C07_owner", k, edit(k.ok) { it[OWNER_UID] = "B" })
        rejectsWhole("C07_teardown", k, edit(k.ok) { it[TEARDOWN_OWED_FOR] = "A" })
        rejectsWhole("C07_seal", k, edit(k.ok) { it[sealKey] = "[" + ControlObligationFixtures.seal.replace("\"old\"", "\"old2\"") + "]" })
        val authChanged = ControlObligationFixtures.node(ControlObligationFixtures.guard.replace("\"authStateOrder\":8", "\"authStateOrder\":9"))
        rejectsWhole("C07_guardAuth", k, edit(k.ok) { it[demandKey] = demandWith(authChanged, old, foreign, newRequest) })
        val floorChanged = ControlObligationFixtures.node(ControlObligationFixtures.guard.replace("\"waitMillis\":30000", "\"waitMillis\":29000"))
        rejectsWhole("C07_guardFloor", k, edit(k.ok) { it[demandKey] = demandWith(floorChanged, old, foreign, newRequest) })
    }

    // ---- C10: exactly this command's Applied, appended after existing evidence (L443, §9.4 C10) ----

    @Test fun C10_applied() {
        val k = case(); val r = read(k.raw)
        fun wrong(id: String, evidence: String) {
            val bad = edit(k.ok) { it[ControlLifecycleEvidenceFixtures.evidenceKey] = evidence }
            assertFalse(atomic(id), writer.evidencePreserved(k.c, r, read(bad), targets))
        }
        wrong("C10_missing", "[]")
        wrong("C10_transition", applied(k.c, transition = "RECOVER_HOLD"))
        wrong("C10_effect", applied(k.c, wireTargets = """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"},{"kind":"DEMAND","id":"new-request","effect":"REPLACE"}"""))
        wrong("C10_order", applied(k.c, wireTargets = """{"kind":"DEMAND","id":"new-request","effect":"CREATE"},{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"}"""))
        wrong("C10_sourceTargetOnly", applied(k.c, wireTargets = """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"}"""))
    }

    // ---- C11: unrelated payload strings and external ByteArray are preserved (§9.4 C11) ----

    @Test fun C11_unrelatedPayload() {
        val k = case()
        rejectsWhole("C11_byteArray", k, edit(k.ok) { it[external] = byteArrayOf(0, 1, 2) })
        rejectsWhole("C11_holdArray", k, edit(k.ok) { it[ControlRecordKeys.payload(ControlKind.HOLD)] = "[" + ControlObligationFixtures.hold + "]" })
    }

    // ---- C0d candidate: departed owner — no new REQUEST, no rotation; an added REQUEST is an extra effect (L415, L440) ----

    @Test fun D1_departedCandidate() {
        val p = plan(departed); val c = command(p)
        val raw = before(departed)
        val ok = edit(raw) {
            it[PURGE_JOURNAL] = "B|||CAPABILITY"
            it[intentKey] = payload(listOf(sibling, otherSession))
            it[ControlLifecycleEvidenceFixtures.evidenceKey] = applied(c, wireTargets = """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"}""")
        }
        val onlySource = listOf(LifecycleTarget(ControlKind.RECOVERY_INTENT, "r", LifecycleEffect.REMOVE))
        assertEquals(atomic("D1_expectedTargets"), onlySource, writer.expectedTargets(input(departed), ids))
        assertTrue(atomic("D1_validCandidate"), writer.validCandidate(c, p, read(raw), ok))
        val extra = edit(ok) { it[demandKey] = demandWith(guard, old, foreign, newRequest) }
        assertFalse(read(extra).hasUninterpretable)
        assertFalse(atomic("D1_extraRequest"), writer.validCandidate(c, p, read(raw), extra))
        val rotated = edit(ok) { it[KRX_EPOCH] = krxFresh }
        assertFalse(atomic("D1_extraRotation"), writer.validCandidate(c, p, read(raw), rotated))
        val markerChanged = edit(ok) { it[MAY_CONTAIN_KRX] = false }
        assertFalse(atomic("D1_extraMarker"), writer.validCandidate(c, p, read(raw), markerChanged))
    }

    // ---- I05b: marker=false or target≠current never licenses skipping the handover (§9.4 I05b, L412/L414) ----

    @Test fun I05b_markerFalseStillHandsOver() {
        val p = plan(); val c = command(p)
        val raw = before(krxMarker = false)
        val ok = edit(raw) {
            it[KRX_EPOCH] = krxFresh; it[MAY_CONTAIN_KRX] = false
            it[PURGE_JOURNAL] = "A||k|CAPABILITY"
            it[intentKey] = payload(listOf(sibling, otherSession))
            it[demandKey] = demandWith(guard, old, foreign, newRequest)
            it[ControlLifecycleEvidenceFixtures.evidenceKey] = applied(c)
        }
        assertFalse(read(ok).hasUninterpretable)
        assertTrue(atomic("I05b_markerRequiredEffects"), writer.requiredEffects(input(), ids, grant, read(raw), read(ok)))
        assertTrue(atomic("I05b_markerValid"), writer.validCandidate(c, p, read(raw), ok))
        fun missing(id: String, change: (MutablePreferences) -> Unit) {
            val bad = edit(ok, change)
            assertFalse(read(bad).hasUninterpretable)
            assertFalse(atomic(id), writer.requiredEffects(input(), ids, grant, read(raw), read(bad)))
        }
        missing("I05b_markerJournalMissing") { it.remove(PURGE_JOURNAL) }
        missing("I05b_markerRequestMissing") { it[demandKey] = demandWith(guard, old, foreign) }
        missing("I05b_markerRotationMissing") { it[KRX_EPOCH] = "k" }
    }

    @Test fun I05b_differentTargetStillHandsOver() {
        val src = intent("A", "CAPABILITY", "k0", "r", "session")
        val noFresh = ids.copy(epochs = RecoveryFreshEpochs(null, null))
        val i = input(src)
        val p = RecoverIntentPlan.prepare(i, noFresh, LifecycleOrderSource(newLife, 21)); val c = command(p)
        val raw = before(src)
        val ok = edit(raw) {
            it[PURGE_JOURNAL] = "A||k0|CAPABILITY"
            it[intentKey] = payload(listOf(sibling, otherSession))
            it[demandKey] = demandWith(guard, old, foreign, newRequest)
            it[ControlLifecycleEvidenceFixtures.evidenceKey] = applied(c)
        }
        assertFalse(read(ok).hasUninterpretable)
        assertTrue(atomic("I05b_targetRequiredEffects"), writer.requiredEffects(i, noFresh, grant, read(raw), read(ok)))
        assertTrue(atomic("I05b_targetValid"), writer.validCandidate(c, p, read(raw), ok))
        fun missing(id: String, change: (MutablePreferences) -> Unit) {
            val bad = edit(ok, change)
            assertFalse(read(bad).hasUninterpretable)
            assertFalse(atomic(id), writer.requiredEffects(i, noFresh, grant, read(raw), read(bad)))
        }
        missing("I05b_targetJournalMissing") { it.remove(PURGE_JOURNAL) }
        missing("I05b_targetRequestMissing") { it[demandKey] = demandWith(guard, old, foreign) }
    }
}
