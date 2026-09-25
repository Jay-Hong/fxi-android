package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5e-2 supplement contract after M1 sensitivity (analysis r2; review tables 1, 3 and 5):
 * the whole final validator (validCandidate) with one preserved or pre-existing fault the earlier
 * contracts never injected, eligibility on failed plans (direct boundary only — a prepared writer
 * never reaches it with those plans) and buildCandidate's own journal failure. Each good candidate is
 * a literal from design L412/L443 on the fixture's before; the fault is the only difference.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementWholeContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    private val codec = ControlPayloadCodec()
    private val writer = RecoverIntentTransition(codec)
    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, krxFresh))
    private val tracker = "current-tracker"
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, target: String?, id: String = "r", session: String = "session") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"CAPABILITY","targetEpoch":${q(target)}}""")
    private val source = intent("A", "k")
    private val other = intent("A", "k", id = "r3", session = "other")
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val holdKey = ControlRecordKeys.payload(ControlKind.HOLD)
    private val newRequest = DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife,
        intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
    private fun closure(src: ControlNode, exec: SettlementExecutor = executor) = HoldRecoveryClosure.AfterRestart(src, exec, "previous-tracker", true, true)
    private fun input(src: ControlNode = source, before: FenceV1 = FenceV1("A", "u", "k"), b: LifecycleBinding = binding) =
        RecoverIntentInput(src, before, b, closure(src, b.executor))
    private fun plan(i: RecoverIntentInput = input(), x: RecoverIntentIds = ids, orders: LifecycleOrderSource = LifecycleOrderSource(newLife, 21)) =
        RecoverIntentPlan.prepare(i, x, orders)
    private fun command(p: RecoverIntentPlan) = ControlLifecycleEvidenceFixtures.command(p.descriptor())
    private fun base(change: (MutablePreferences) -> Unit = {}): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[intentKey] = payload(listOf(source, other))
        change(this)
    }.toPreferences()
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)
    private fun edit(p: Preferences, change: (MutablePreferences) -> Unit) = p.toMutablePreferences().apply(change).toPreferences()
    private fun applied(c: CommandRef, lifetime: String = c.ownerTrackingLifetimeId.value) = "[" + ControlLifecycleEvidenceFixtures.wire("RECOVER_INTENT",
        """{"kind":"RECOVERY_INTENT","id":"r","effect":"REMOVE"},{"kind":"DEMAND","id":"new-request","effect":"CREATE"}""", c.id, lifetime) + "]"
    /** L412/L443 good candidate on [raw]: rotate KRX, marker false, append exact journal, remove r, append REQUEST, Applied. */
    private fun good(raw: Preferences, c: CommandRef, journal: String = listOfNotNull(raw[PURGE_JOURNAL], "A||k|CAPABILITY").joinToString("\n"),
        demand: List<ControlNode> = listOf(newRequest)) = edit(raw) {
        it[KRX_EPOCH] = krxFresh; it[MAY_CONTAIN_KRX] = false
        it[PURGE_JOURNAL] = journal
        it[intentKey] = payload(listOf(other))
        it[demandKey] = payload(demand)
        it[ControlLifecycleEvidenceFixtures.evidenceKey] = applied(c)
    }

    @Test fun W0_goodCandidateAccepted() {
        val p = plan(); val c = command(p); val raw = base()
        assertTrue(atomic("W0_positive"), writer.validCandidate(c, p, read(raw), good(raw, c)))
    }

    // ---- WC: validCandidate faults (RI.C05create, C05preserve, evidenceCall, candidateBefore/Preimage/Fresh/OperationId, C12record, C12raw) ----

    @Test fun WC1_createIdAlreadyUsedBefore() {
        val p = plan(); val c = command(p)
        val squatter = DemandAuthFixtures.request(id = "new-request", owner = "A", intent = RefreshIntent.IF_STALE)
        val raw = base { it[demandKey] = payload(listOf(squatter)) }
        assertFalse(atomic("WC1_C05create"), writer.validCandidate(c, p, read(raw), good(raw, c, demand = listOf(newRequest))))
    }
    @Test fun WC2_oldJournalPreserved() {
        val p = plan(); val c = command(p); val raw = base { it[PURGE_JOURNAL] = "B||x|USER" }
        assertTrue(atomic("WC2_positive"), writer.validCandidate(c, p, read(raw), good(raw, c)))
        assertFalse(atomic("WC2_dropped"), writer.validCandidate(c, p, read(raw), good(raw, c, journal = "A||k|CAPABILITY")))
        assertFalse(atomic("WC2_duplicated"), writer.validCandidate(c, p, read(raw), good(raw, c, journal = "B||x|USER\nA||k|CAPABILITY\nA||k|CAPABILITY")))
        assertFalse(atomic("WC2_reordered"), writer.validCandidate(c, p, read(raw), good(raw, c, journal = "A||k|CAPABILITY\nB||x|USER")))
    }
    @Test fun WC3_appliedWithWrongLifetime() {
        val p = plan(); val c = command(p); val raw = base()
        val bad = edit(good(raw, c)) { it[ControlLifecycleEvidenceFixtures.evidenceKey] = applied(c, lifetime = "00000000-0000-0000-0000-000000000777") }
        assertFalse(read(bad).hasUninterpretableMetadata) // premise: interpretable Applied
        assertFalse(atomic("WC3_evidenceCall"), writer.validCandidate(c, p, read(raw), bad))
    }
    @Test fun WC4_beforeDiffersFromFixedBefore() {
        val p = plan(); val c = command(p); val raw = base { it[KRX_EPOCH] = "k9" } // only the rotated axis differs
        assertFalse(atomic("WC4_candidateBefore"), writer.validCandidate(c, p, read(raw), good(raw, c)))
    }
    @Test fun WC5_sourcePreimageChanged() {
        val p = plan(); val c = command(p)
        val raw = base { it[intentKey] = payload(listOf(intent("A", "k8"), other)) } // same id, different preimage
        assertFalse(atomic("WC5_candidatePreimage"), writer.validCandidate(c, p, read(raw), good(raw, c)))
    }
    @Test fun WC6_freshAlreadyReserved() {
        val p = plan(); val c = command(p); val raw = base { it[PURGE_JOURNAL] = "A|$krxFresh||USER" }
        assertFalse(atomic("WC6_candidateFresh"), writer.validCandidate(c, p, read(raw), good(raw, c)))
    }
    @Test fun WC7_operationIdCollision() {
        val p = plan(); val c = command(p)
        val settled = ControlObligationFixtures.replace(ControlObligationFixtures.settledSeal, listOf("settlement"), "operationId", JsonPrimitive(c.id))
        val raw = base { it[sealKey] = payload(listOf(settled)) }
        assertFalse(read(raw).hasUninterpretable)
        assertFalse(atomic("WC7_candidateOperationId"), writer.validCandidate(c, p, read(raw), good(raw, c)))
    }
    @Test fun WC8_opaqueRowPreserved() {
        val p = plan(); val c = command(p); val raw = base { it[holdKey] = """[{"future":true}]""" }
        assertTrue(read(raw).hasUninterpretable) // premise
        assertFalse(atomic("WC8_C12record"), writer.validCandidate(c, p, read(raw), good(raw, c)))
    }
    @Test fun WC9_rawKeyTypePreserved() {
        val p = plan(); val c = command(p); val raw = base { it[intPreferencesKey("teardown_owed_for")] = 1 }
        assertFalse(atomic("WC9_C12raw"), writer.validCandidate(c, p, read(raw), good(raw, c)))
    }

    // ---- WE: eligibility direct boundary (RI.operationIdCall; failed-plan scopes for RI.rawOwner and RI.requestCall) ----

    private fun context(src: ControlNode = source, exec: SettlementExecutor = executor, b: LifecycleBinding = binding) =
        AttemptContext(exec.ownerUid, exec.binding, exec.originLifetimeId, false, false,
            intentRecovery = HoldRecoveryRuntime(b, 5, true, emptySet(), closure(src, exec)))

    @Test fun WE1_operationIdCollision() {
        val p = plan()
        val settled = ControlObligationFixtures.replace(ControlObligationFixtures.settledSeal, listOf("settlement"), "operationId", JsonPrimitive(ids.operationId))
        assertNull(writer.eligibility(p, context(), read(base()), tracker)) // premise
        assertEquals(eligible("WE1_operationIdCall"), HoldRecoveryProblem.Conflict(ConflictReason.OperationIdCollision),
            writer.eligibility(p, context(), read(base { it[sealKey] = payload(listOf(settled)) }), tracker))
    }
    @Test fun WE2_rawOwnerOnFailedPlan() {
        // Direct boundary only: executor A with before/raw owner B fails prepare (DemandScopeMismatch).
        val src = intent("A", null)
        val i = input(src, before = FenceV1("B", "u", "k"))
        val p = plan(i, ids.copy(epochs = RecoveryFreshEpochs(null, null)))
        assertEquals(HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest("DemandScopeMismatch")), p.preparationProblem)
        val raw = base { it[OWNER_UID] = "B"; it[intentKey] = payload(listOf(src, other)) }
        assertEquals(eligible("WE2_rawOwner"), HoldRecoveryProblem.Conflict(ConflictReason.TargetChanged), writer.eligibility(p, context(src), read(raw), tracker))
    }
    @Test fun WE3_requestOnFailedPlan() {
        // Direct boundary only: order exhaustion leaves requestOrder/requestAfter null.
        val p = plan(orders = LifecycleOrderSource(newLife, Long.MAX_VALUE))
        assertEquals(HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest("OrderExhausted")), p.preparationProblem)
        assertEquals(eligible("WE3_requestCall"), HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest("OrderExhausted")),
            writer.eligibility(p, context(), read(base()), tracker))
    }

    // ---- WB: buildCandidate's own journal failure (RI.buildJournal) ----

    @Test fun WB1_buildRejectsMalformedJournal() {
        val p = plan(); val c = command(p)
        val built = writer.buildCandidate(c, p, read(base { it[PURGE_JOURNAL] = "A||k|CAPABILITY|extra" }))
        assertEquals(atomic("WB1_buildJournal"),
            RecoverIntentCandidateBuild.Failed(HoldRecoveryProblem.RecoveryRequired(RecoveryReason.JournalMigrationRequired)), built)
    }
}
