package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

/**
 * Claude-owned 5e-2 reservation and journal contract (plan v1 rows 5–6). Design
 * `d2b5_demand_auth_design_r3_codex.md` L437 step 3 (fixed canonical fresh UUID, no collision with
 * before axes, the same candidate, the canonical journal or any retained epoch of the four
 * obligation arrays), L438 step 4 (canonical 4-field journal, empty/delimiter/newline/unpaired
 * surrogate rejected, null kept, exact entry never duplicated, a wider entry never replaces it),
 * §9.4 H05a/c/d and H06a–d applied to RECOVER_INTENT. H05b is NA for the single-axis intent
 * candidate (no other fresh axis in the same candidate). Expected problems follow the shared
 * boundary values (RecoveryRetirementBoundary); the writer's link is checked through eligibility
 * and prepare, not by calling the shared boundary only. The unknown 5-field journal case is a
 * classification check (CLASSIFICATION_ONLY), not a safety kill.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentReservationContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("intent reservation cleanup", 30000) { opened.reversed().forEach { it.close() } } }
    private suspend fun disk(file: File) = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)

    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val fresh = "00000000-0000-0000-0000-000000000012"
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, fresh))
    private val writer = RecoverIntentTransition(ControlPayloadCodec())
    private val tracker = "current-tracker"

    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    /** [ownerJson]/[targetJson] are raw JSON values so escapes reach the schema decoder verbatim. */
    private fun intentRaw(ownerJson: String, targetJson: String, axis: String = "CAPABILITY", id: String = "r") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"session","ownerUid":$ownerJson,"axis":"$axis","targetEpoch":$targetJson}""")
    private fun intent(owner: String?, target: String?, id: String = "r") = intentRaw(q(owner), q(target), id = id)
    private val source = intent("A", "k")
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private fun raw(intents: List<ControlNode> = listOf(source), change: (MutablePreferences) -> Unit = {}): Preferences =
        ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
            this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
            this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = payload(intents)
            change(this)
        }.toPreferences()
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)
    private fun closure(src: ControlNode) = HoldRecoveryClosure.AfterRestart(src, executor, "previous-tracker", true, true)
    private fun input(src: ControlNode = source, before: FenceV1 = FenceV1("A", "u", "k")) = RecoverIntentInput(src, before, binding, closure(src))
    private fun plan(i: RecoverIntentInput = input()) = RecoverIntentPlan.prepare(i, ids, LifecycleOrderSource(newLife, 21))
    private fun context(src: ControlNode = source) = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure(src)))
    private fun rejected(detail: String) = HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail))

    @Test fun V0_eligibleBase() {
        assertNull(plan().preparationProblem)
        assertNull(eligible("V0_positive"), writer.eligibility(plan(), context(), read(raw()), tracker))
    }

    // ---- H05a / same-candidate: prepare-time reservation against the fixed before axes (L437) ----

    @Test fun H05a_freshEqualsBeforeOppositeAxis() {
        val p = plan(input(before = FenceV1("A", fresh, "k")))
        assertEquals(atomic("H05a_beforeUser"), rejected("EpochNotFresh"), p.preparationProblem)
        val nonCanonical = RecoverIntentPlan.prepare(input(), ids.copy(epochs = RecoveryFreshEpochs(null, "not-a-uuid")), LifecycleOrderSource(newLife, 21))
        assertEquals(atomic("H05_nonCanonical"), rejected("EpochNotFresh"), nonCanonical.preparationProblem)
        val missing = RecoverIntentPlan.prepare(input(), ids.copy(epochs = RecoveryFreshEpochs(null, null)), LifecycleOrderSource(newLife, 21))
        assertEquals(atomic("H05_missingFresh"), rejected("EpochNotFresh"), missing.preparationProblem)
    }

    // ---- H05c/H05d: attempt-time reservation against the latest record, each position separately (L437) ----

    @Test fun H05c_journalFields() {
        assertEquals(eligible("H05c_journalUser"), rejected("EpochNotFresh"),
            writer.eligibility(plan(), context(), read(raw { it[PURGE_JOURNAL] = "A|$fresh||USER" }), tracker))
        assertEquals(eligible("H05c_journalKrx"), rejected("EpochNotFresh"),
            writer.eligibility(plan(), context(), read(raw { it[PURGE_JOURNAL] = "B||$fresh|CAPABILITY" }), tracker))
    }

    @Test fun H05d_retainedEpochPositions() {
        fun blocked(id: String, change: (MutablePreferences) -> Unit, intents: List<ControlNode> = listOf(source)) {
            val r = raw(intents, change)
            assertTrue(read(r).arrays.values.none { it.hasUninterpretable })
            assertEquals(eligible(id), rejected("EpochNotFresh"), writer.eligibility(plan(), context(), read(r), tracker))
        }
        val seals = ControlRecordKeys.payload(ControlKind.SEAL)
        val holds = ControlRecordKeys.payload(ControlKind.HOLD)
        blocked("H05d_seal", { it[seals] = "[" + ControlObligationFixtures.seal.replace("\"old\"", "\"$fresh\"") + "]" })
        blocked("H05d_witnessBefore", {
            it[seals] = payload(listOf(ControlObligationFixtures.replace(
                ControlObligationFixtures.settledSeal,
                listOf("settlement", "before"),
                "krxCapabilityEpoch", JsonPrimitive(fresh)
            )))
        })
        blocked("H05d_witnessAfter", {
            it[seals] = payload(listOf(ControlObligationFixtures.replace(
                ControlObligationFixtures.settledSeal,
                listOf("settlement", "after"),
                "krxCapabilityEpoch", JsonPrimitive(fresh)
            )))
        })
        blocked("H05d_queryFence", { it[holds] = "[" + ControlObligationFixtures.hold.replace("\"krxCapabilityEpoch\":\"k\"", "\"krxCapabilityEpoch\":\"$fresh\"") + "]" })
        blocked("H05d_topicFence", { it[holds] = "[" + ControlObligationFixtures.topicHold.replace("\"userAccessEpoch\":\"u\"", "\"userAccessEpoch\":\"$fresh\"") + "]" })
        blocked("H05d_intentTarget", {}, listOf(source, intent("A", fresh, id = "r9")))
    }

    // ---- H06a–d: journal representability of the source's own fields (L438 step 4) ----

    @Test fun H06_unrepresentableSourceFields() {
        fun prep(src: ControlNode) = plan(input(src = src)).preparationProblem
        // ownerUid accepts "" in the schema (nullableText); targetEpoch rejects "" (nullableId), so H06a uses owner.
        assertEquals(atomic("H06a_emptyOwner"), rejected("UnrepresentableJournalField"), prep(intentRaw("\"\"", "\"k\"")))
        assertEquals(atomic("H06b_delimiter"), rejected("UnrepresentableJournalField"), prep(intentRaw("\"A\"", "\"k|0\"")))
        assertEquals(atomic("H06c_newline"), rejected("UnrepresentableJournalField"), prep(intentRaw("\"A\"", """"k\n0"""")))
        assertEquals(atomic("H06d_surrogate"), rejected("UnrepresentableJournalField"), prep(intentRaw("\"A\"", """"k\uD800"""")))
        assertNull(atomic("H06_nullKept"), prep(intentRaw("\"A\"", "null")))
    }

    // ---- classification: unknown 5-field journal is migration, not a pass (L438; CLASSIFICATION_ONLY) ----

    @Test fun H06_unknownJournalIsMigration() {
        assertEquals(eligible("H06_fiveField"), HoldRecoveryProblem.RecoveryRequired(RecoveryReason.JournalMigrationRequired),
            writer.eligibility(plan(), context(), read(raw { it[PURGE_JOURNAL] = "A||k|CAPABILITY|extra" }), tracker))
    }

    // ---- exact journal: not duplicated when present; a wider entry does not replace it (L438 step 4) ----

    @Test fun J1_exactJournalNotDuplicated() = runReleaseTest {
        val src = intent("A", "k0") // C0c: preserve, exact journal A||k0|CAPABILITY, new REQUEST
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("J1 seed") { s.data.updateData { raw(listOf(src)) { it[PURGE_JOURNAL] = "A||k0|CAPABILITY" } } }
        val c = s.control.prepareRecoverIntent(input(src), LifecycleOrderSource(newLife, 21))
        val result = controlTestTimeout("J1 execute") { s.control.execute(c, context(src)) }
        assertTrue(atomic("J1_confirmed"), result is ControlStoreResult.Confirmed)
        val after = disk(file)
        assertEquals(atomic("J1_single"), "A||k0|CAPABILITY", after[PURGE_JOURNAL])
        val plan = checkNotNull((c.body as ControlCommandBody.Lifecycle).input.recoverIntent)
        assertEquals(atomic("J1_requestStill"), 1, FloorGuardFixtures.read(after).arrays.getValue(ControlKind.DEMAND).entries
            .filterIsInstance<ControlEntryRead.Interpreted>().count { it.value.id == plan.ids.requestId })
    }

    @Test fun J2_widerJournalDoesNotReplaceExact() = runReleaseTest {
        val src = intent("A", "k0")
        val file = folder.newFile(); val s = open(file)
        controlTestTimeout("J2 seed") { s.data.updateData { raw(listOf(src)) { it[PURGE_JOURNAL] = "A||k0|CAPABILITY,USER" } } }
        val c = s.control.prepareRecoverIntent(input(src), LifecycleOrderSource(newLife, 21))
        val result = controlTestTimeout("J2 execute") { s.control.execute(c, context(src)) }
        assertTrue(atomic("J2_confirmed"), result is ControlStoreResult.Confirmed)
        assertEquals(atomic("J2_exactAppended"), "A||k0|CAPABILITY,USER\nA||k0|CAPABILITY", disk(file)[PURGE_JOURNAL])
    }
}
