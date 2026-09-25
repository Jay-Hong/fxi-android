package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.ControlRecordStore.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5e-2 supplement contract after M1 sensitivity (analysis r2; review tables 2, 3 and 5):
 * the writer's own validDescriptor, decide and the common validDescriptor called directly, each case
 * one single fault from the literal descriptor of RecoverIntentDescriptorContractTest (skeleton
 * design v2 §4). decide cases check the whole decision (Confirm vs the named rejection), because a
 * validDescriptor-only test cannot see a bypassed call site. The prepareCall case only fixes the
 * rejection reason and is CLASSIFICATION_ONLY. The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementDescriptorContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private val codec = ControlPayloadCodec()
    private val writer = RecoverIntentTransition(codec)
    private val common = ControlLifecycleConfirmation(codec)
    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, krxFresh))
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, target: String?) =
        ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":${q(owner)},"axis":"CAPABILITY","targetEpoch":${q(target)}}""")
    private val source = intent("A", "k")
    private fun closure(src: ControlNode) = HoldRecoveryClosure.AfterRestart(src, executor, "previous-tracker", true, true)
    private fun input(src: ControlNode = source) = RecoverIntentInput(src, FenceV1("A", "u", "k"), binding, closure(src))
    private fun plan(src: ControlNode = source, x: RecoverIntentIds = ids, orders: LifecycleOrderSource = LifecycleOrderSource(newLife, 21)) =
        RecoverIntentPlan.prepare(input(src), x, orders)
    private val newRequest = DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife,
        intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
    private fun sourceTarget(before: ControlNode? = source, after: ControlNode? = null, role: LifecycleRole = LifecycleRole.RECOVERY_INTENT) =
        LifecycleFixedTarget(LifecycleTarget(ControlKind.RECOVERY_INTENT, "r", LifecycleEffect.REMOVE), role, before, after)
    private fun requestTarget(before: ControlNode? = null, after: ControlNode? = newRequest) =
        LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "new-request", LifecycleEffect.CREATE), LifecycleRole.REQUEST, before, after)
    private val journal = listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY)))
    private fun namespace() = LifecycleNamespacePostcondition(FenceV1("A", "u", "k"), FenceV1("A", "u", krxFresh), journal, null, false)
    private fun descriptor(p: RecoverIntentPlan, transition: LifecycleTransition = LifecycleTransition.RECOVER_INTENT,
        targets: List<LifecycleFixedTarget> = listOf(sourceTarget(), requestTarget()), exec: SettlementExecutor? = executor,
        ns: LifecycleNamespacePostcondition? = namespace(), demand: DemandAuthPlan? = null, empty: RemoveEmptyGuardPlan? = null,
        hold: RecoverHoldPlan? = null, named: RecoverIntentPlan? = p) =
        ControlLifecycleDescriptor(ids.operationId, transition, targets, exec, ns, emptyList(), demand, empty, hold, named)
    private val otherDemand get() = DemandAuthPlan.rebind(listOf(DemandAuthFixtures.request(binding = 2)), DemandAuthFixtures.binding,
        LifecycleOrderSource(DemandAuthFixtures.binding.executor.originLifetimeId, 21))
    private val otherEmpty get() = RemoveEmptyGuardPlan.prepare(DemandAuthFixtures.guard(auth = null))

    // ---- D: writer validDescriptor direct negatives (RI.descriptorRemoved/RequestBefore/OtherWriter×3/Transition/namedPlan/Role) ----

    @Test fun D0_literalAccepted() {
        val p = plan()
        assertTrue(atomic("D0_writer"), writer.validDescriptor(p, descriptor(p)))
        assertTrue(atomic("D0_common"), common.validDescriptor(descriptor(p)))
    }
    @Test fun D1_sourceAfterPresent() { val p = plan(); assertFalse(atomic("D1_descriptorRemoved"), writer.validDescriptor(p, descriptor(p, targets = listOf(sourceTarget(after = source), requestTarget())))) }
    @Test fun D2_requestBeforePresent() { val p = plan(); assertFalse(atomic("D2_descriptorRequestBefore"), writer.validDescriptor(p, descriptor(p, targets = listOf(sourceTarget(), requestTarget(before = newRequest))))) }
    @Test fun D3_otherWriterDemand() { val p = plan(); assertFalse(atomic("D3_otherDemand"), writer.validDescriptor(p, descriptor(p, demand = otherDemand))) }
    @Test fun D4_otherWriterEmptyGuard() { val p = plan(); assertFalse(atomic("D4_otherEmptyGuard"), writer.validDescriptor(p, descriptor(p, empty = otherEmpty))) }
    @Test fun D5_otherWriterHold() { val p = plan(); assertFalse(atomic("D5_otherHold"), writer.validDescriptor(p, descriptor(p, hold = HoldRecoveryFixtures.plan()))) }
    @Test fun D6_transition() { val p = plan(); assertFalse(atomic("D6_descriptorTransition"), writer.validDescriptor(p, descriptor(p, transition = LifecycleTransition.RECOVER_HOLD))) }
    @Test fun D7_namedPlanIdentity() {
        val p = plan(); val twin = plan()
        assertTrue(p !== twin)
        assertFalse(atomic("D7_namedPlan"), writer.validDescriptor(p, descriptor(p, named = twin)))
    }
    @Test fun D8_roleOfSourceRow() {
        val p = plan() // source row declared as REQUEST, with the REQUEST branch itself satisfied (before null, valid after)
        assertFalse(atomic("D8_descriptorRole"), writer.validDescriptor(p,
            descriptor(p, targets = listOf(sourceTarget(before = null, after = newRequest, role = LifecycleRole.REQUEST), requestTarget()))))
    }

    // ---- D9: a failed plan (departed owner, nonnull target == current) with a preserve-shaped namespace (RI.descriptorRetirementInput) ----

    @Test fun D9_retirementInputOnFailedPlan() {
        val src = intent("B", "k")
        val p = plan(src, ids.copy(epochs = RecoveryFreshEpochs(null, null)))
        assertEquals(HoldRecoveryProblem.Conflict(ConflictReason.TargetChanged), p.preparationProblem) // premise
        val preserve = LifecycleNamespacePostcondition(FenceV1("A", "u", "k"), FenceV1("A", "u", "k"),
            listOf(PendingPurge("B", null, "k", setOf(PurgeScope.CAPABILITY))), null, null)
        val d = ControlLifecycleDescriptor(ids.operationId, LifecycleTransition.RECOVER_INTENT, listOf(sourceTarget(before = src)), executor,
            preserve, emptyList(), recoverIntent = p)
        assertFalse(atomic("D9_descriptorRetirementInput"), writer.validDescriptor(p, d))
    }

    // ---- C: common validDescriptor with a writer-only violation (RI.lifecycleDescriptor) ----

    @Test fun C1_commonRejectsWriterOnlyViolation() {
        val p = plan()
        assertFalse(atomic("C1_lifecycleDescriptor"), common.validDescriptor(descriptor(p, exec = null)))
    }

    // ---- X: writer decide call sites (RI.descriptorCall, RI.candidateCall; prepareCall CLASSIFICATION_ONLY) ----

    private fun raw(opaqueHold: Boolean = false): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = "[${source.toPayloadEntry().fields}]"
        if (opaqueHold) this[ControlRecordKeys.payload(ControlKind.HOLD)] = """[{"future":true}]"""
    }.toPreferences()
    private fun context() = AttemptContext("A", 3, newLife, false, false,
        intentRecovery = HoldRecoveryRuntime(binding, 5, true, emptySet(), closure(source)))
    private fun rejectedWith(detail: String, d: RecordTransactionDecision<Outcome>): Boolean {
        val result = ((d as? RecordTransactionDecision.Observe)?.value as? Outcome.Negative)?.result as? ControlStoreResult.Rejected
        return result?.reason == RejectionReason.InvalidRequest(detail)
    }

    @Test fun X0_decidePositive() {
        val p = plan(); val c = ControlLifecycleEvidenceFixtures.command(descriptor(p))
        assertTrue(atomic("X0_confirm"), writer.decide(c, descriptor(p), ControlLifecycleEvidenceFixtures.read(raw()), context()) is RecordTransactionDecision.Confirm)
    }
    @Test fun X1_decideChecksDescriptor() {
        val p = plan(); val d = descriptor(p, exec = executor.copy(ownerUid = "B"))
        val c = ControlLifecycleEvidenceFixtures.command(d)
        assertTrue(atomic("X1_descriptorCall"), rejectedWith("InvalidLifecycleDescriptor", writer.decide(c, d, ControlLifecycleEvidenceFixtures.read(raw()), context())))
    }
    @Test fun X2_decideChecksCandidate() {
        val p = plan(); val d = descriptor(p); val c = ControlLifecycleEvidenceFixtures.command(d)
        val read = ControlLifecycleEvidenceFixtures.read(raw(opaqueHold = true))
        assertTrue(read.hasUninterpretable) // premise: an opaque non-target HOLD row the builder preserves
        assertTrue(atomic("X2_candidateCall"), rejectedWith("RequiredDecisionEffectMissing", writer.decide(c, d, read, context())))
    }
    @Test fun X3_decideKeepsPreparationReason() {
        // CLASSIFICATION_ONLY: a failed plan must be rejected with its own preparation reason.
        val p = plan(orders = LifecycleOrderSource(newLife, Long.MAX_VALUE))
        assertEquals(HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest("OrderExhausted")), p.preparationProblem)
        val d = ControlLifecycleDescriptor(ids.operationId, LifecycleTransition.RECOVER_INTENT, listOf(sourceTarget()), executor, namespace(), emptyList(), recoverIntent = p)
        val c = ControlLifecycleEvidenceFixtures.command(d)
        assertTrue(atomic("X3_prepareCall"), rejectedWith("OrderExhausted", writer.decide(c, d, ControlLifecycleEvidenceFixtures.read(raw()), context())))
    }
}
