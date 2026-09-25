package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.RefreshIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5e-2 supplement contract after M1 sensitivity (analysis r2 = r1 + review
 * m1_analysis_r1_review_codex.log tables 1 and 4): direct boundaries the first contracts left open.
 * P: RecoverIntentPlan.prepare input gates, each one single fault from an eligible base, with the
 * exact preparation problem (the origin case uses a departed-owner source so the supplier-origin gate
 * is not reached). E: requiredEffects single-effect negatives on the USER axis, owner/teardown,
 * departed-owner REQUEST and a malformed journal. H: helpers called directly with an invalid source
 * (only reachable outside the prepared writer path — a direct-boundary scope, not a writer claim).
 * Candidates are literals from design L412–L443; never builder output.
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementPrepareEffectsContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    private val writer = RecoverIntentTransition(ControlPayloadCodec())

    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val userFresh = "00000000-0000-0000-0000-000000000011"
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val grant = LifecycleOrderGrant(newLife, 21, 1, 0, 22)
    private fun ids(user: String? = null, krx: String? = krxFresh) =
        RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(user, krx))

    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, axis: String, target: String?, id: String = "r", session: String = "session") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"$axis","targetEpoch":${q(target)}}""")
    private val capSource = intent("A", "CAPABILITY", "k")
    private val other = intent("A", "CAPABILITY", "k", id = "r3", session = "other")
    private fun payload(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private val intentKey = ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private fun closure(src: ControlNode) = HoldRecoveryClosure.AfterRestart(src, executor, "previous-tracker", true, true)
    private fun input(src: ControlNode, b: LifecycleBinding = binding, before: FenceV1 = FenceV1("A", "u", "k")) =
        RecoverIntentInput(src, before, b, closure(src))
    private fun before(src: ControlNode): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[intentKey] = payload(listOf(src, other))
    }.toPreferences()
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)
    private fun edit(p: Preferences, change: (MutablePreferences) -> Unit) = p.toMutablePreferences().apply(change).toPreferences()
    private fun request(intent: RefreshIntent) = DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife, intent = intent, order = 22)
    private fun rejected(detail: String) = HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest(detail))

    // ---- P: prepare input gates (RecoverIntentPlan.prepare; RI.operationEmpty … RI.supplierOrigin) ----

    private fun prep(i: RecoverIntentInput = input(capSource), x: RecoverIntentIds = ids(), orders: LifecycleOrderSource = LifecycleOrderSource(newLife, 21)) =
        RecoverIntentPlan.prepare(i, x, orders).preparationProblem

    @Test fun P0_eligibleBase() {
        assertNull(atomic("P0_currentOwner"), prep())
        assertNull(atomic("P0_departedOwner"), prep(input(intent("B", "CAPABILITY", null))))
    }
    @Test fun P1_operationEmpty() = assertEquals(eligible("P1_operationEmpty"), rejected("InvalidOperationId"), prep(x = ids().copy(operationId = "")))
    @Test fun P2_prepareOwner() = assertEquals(eligible("P2_prepareOwner"), rejected("DemandScopeMismatch"),
        prep(input(capSource, binding.copy(executor = executor.copy(ownerUid = "B")))))
    @Test fun P3_prepareBinding() = assertEquals(eligible("P3_prepareBinding"), rejected("InvalidDemand"),
        prep(input(capSource, binding.copy(executor = executor.copy(binding = -1)))))
    @Test fun P4_prepareOrigin() = assertEquals(eligible("P4_prepareOrigin"), rejected("InvalidDemand"), // departed: no supplier check
        prep(input(intent("B", "CAPABILITY", null), binding.copy(executor = executor.copy(originLifetimeId = LifetimeId(""))))))
    @Test fun P5_prepareStart() = assertEquals(eligible("P5_prepareStart"), rejected("InvalidDemand"), prep(input(capSource, binding.copy(startedOrder = -1))))
    @Test fun P6_requestId() = assertEquals(eligible("P6_requestId"), rejected("InvalidRequestTarget"), prep(x = ids().copy(requestId = "")))
    @Test fun P7_supplierOrigin() = assertEquals(eligible("P7_supplierOrigin"), rejected("OrderExhausted"),
        prep(orders = LifecycleOrderSource(LifetimeId("other-origin"), 21)))

    // ---- E: requiredEffects single effects (L412–L415, L439–L443; RI.C06*/C05exact USER, C07*, departed, C05canonical) ----

    private val userSource = intent("A", "USER", "u")
    private fun userGood(c: Preferences) = edit(c) {
        it[USER_EPOCH] = userFresh; it[MAY_CONTAIN_PREMIUM] = false
        it[PURGE_JOURNAL] = "A|u||USER"
        it[intentKey] = payload(listOf(other))
        it[demandKey] = payload(listOf(request(RefreshIntent.FORCE_PREMIUM)))
    }
    private fun capGood(c: Preferences) = edit(c) {
        it[KRX_EPOCH] = krxFresh; it[MAY_CONTAIN_KRX] = false
        it[PURGE_JOURNAL] = "A||k|CAPABILITY"
        it[intentKey] = payload(listOf(other))
        it[demandKey] = payload(listOf(request(RefreshIntent.FORCE_ENTITLEMENTS)))
    }

    @Test fun E1_userAxisEffects() {
        val i = input(userSource); val x = ids(user = userFresh, krx = null); val raw = before(userSource); val ok = userGood(raw)
        assertTrue(atomic("E1_userPositive"), writer.requiredEffects(i, x, grant, read(raw), read(ok)))
        assertFalse(atomic("E1_userEpoch"), writer.requiredEffects(i, x, grant, read(raw), read(edit(ok) { it[USER_EPOCH] = "u" })))
        assertFalse(atomic("E1_userMarker"), writer.requiredEffects(i, x, grant, read(raw), read(edit(ok) { it[MAY_CONTAIN_PREMIUM] = true })))
        assertFalse(atomic("E1_userJournal"), writer.requiredEffects(i, x, grant, read(raw), read(edit(ok) { it.remove(PURGE_JOURNAL) })))
    }

    @Test fun E2_ownerAndTeardown() {
        val i = input(capSource); val raw = before(capSource); val ok = capGood(raw)
        assertTrue(atomic("E2_positive"), writer.requiredEffects(i, ids(), grant, read(raw), read(ok)))
        assertFalse(atomic("E2_owner"), writer.requiredEffects(i, ids(), grant, read(raw), read(edit(ok) { it[OWNER_UID] = "B" })))
        assertFalse(atomic("E2_teardown"), writer.requiredEffects(i, ids(), grant, read(raw), read(edit(ok) { it[TEARDOWN_OWED_FOR] = "A" })))
    }

    @Test fun E3_departedOwnerAddsNoRequest() {
        val src = intent("B", "CAPABILITY", null); val i = input(src); val x = ids(krx = null); val raw = before(src)
        val ok = edit(raw) { it[PURGE_JOURNAL] = "B|||CAPABILITY"; it[intentKey] = payload(listOf(other)) }
        assertTrue(atomic("E3_positive"), writer.requiredEffects(i, x, null, read(raw), read(ok)))
        val extra = edit(ok) { it[demandKey] = payload(listOf(request(RefreshIntent.FORCE_ENTITLEMENTS))) }
        assertFalse(read(extra).hasUninterpretable)
        assertFalse(atomic("E3_departedRequest"), writer.requiredEffects(i, x, null, read(raw), read(extra)))
    }

    @Test fun E4_malformedJournal() {
        val i = input(capSource); val raw = before(capSource)
        val bad = edit(capGood(raw)) { it[PURGE_JOURNAL] = "A||k|CAPABILITY|extra" }
        assertNull(NamespaceSettlementTransition(ControlPayloadCodec()).canonicalJournal(bad)) // premise: not canonical (not an absent key)
        assertFalse(atomic("E4_malformedJournal"), writer.requiredEffects(i, ids(), grant, read(raw), read(bad)))
    }

    // ---- H: helpers with an invalid source (direct-boundary scope only; RI.effectsSource/requestSource/targetsSource) ----

    private val notIntent get() = HoldRecoveryFixtures.hold()

    @Test fun H1_helpersRejectInvalidSource() {
        val raw = before(capSource); val ok = capGood(raw)
        assertNull(IntentRecoverySource.from(notIntent)) // premise
        val bad = input(capSource).copy(source = notIntent)
        assertFalse(atomic("H1_effectsSource"), writer.requiredEffects(bad, ids(), grant, read(raw), read(ok)))
        assertFalse(atomic("H1_requestSource"), writer.requestValid(bad, ids(), grant, request(RefreshIntent.FORCE_ENTITLEMENTS)))
        assertNull(atomic("H1_targetsSource"), writer.expectedTargets(bad, ids()))
        assertTrue(atomic("H1_requestPositive"), writer.requestValid(input(capSource), ids(), grant, request(RefreshIntent.FORCE_ENTITLEMENTS)))
    }
}
