package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5e-2 supplement for the M4 overlap proofs (measure plan v1 M4; supplement review 3): the common
 * validDescriptor with one other writer plan attached, for the two plans RecoverIntentDescriptorContractTest P2 does
 * not cover (DemandAuth, RemoveEmptyGuard; the RecoverHold case is P2_otherWriterPlan). Under a bypass of the common
 * other-writer term, these tests are expected to keep passing because the writer's own validDescriptor rejects the same
 * input — that is the overlap evidence, measured in DEFENSE mode together with the writer-side direct KILL (D3/D4).
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementOverlapContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private val common = ControlLifecycleConfirmation(ControlPayloadCodec())
    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val krxFresh = "00000000-0000-0000-0000-000000000012"
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, krxFresh))
    private val source = ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k"}""")
    private val closure = HoldRecoveryClosure.AfterRestart(source, executor, "previous-tracker", true, true)
    private fun plan() = RecoverIntentPlan.prepare(RecoverIntentInput(source, FenceV1("A", "u", "k"), binding, closure), ids, LifecycleOrderSource(newLife, 21))
    private val newRequest = DemandAuthFixtures.request(id = "new-request", owner = "A", binding = 3, origin = newLife,
        intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 22)
    private fun descriptor(p: RecoverIntentPlan, demand: DemandAuthPlan? = null, empty: RemoveEmptyGuardPlan? = null) =
        ControlLifecycleDescriptor(ids.operationId, LifecycleTransition.RECOVER_INTENT, listOf(
            LifecycleFixedTarget(LifecycleTarget(ControlKind.RECOVERY_INTENT, "r", LifecycleEffect.REMOVE), LifecycleRole.RECOVERY_INTENT, source, null),
            LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "new-request", LifecycleEffect.CREATE), LifecycleRole.REQUEST, null, newRequest)),
            executor, LifecycleNamespacePostcondition(FenceV1("A", "u", "k"), FenceV1("A", "u", krxFresh),
                listOf(PendingPurge("A", null, "k", setOf(PurgeScope.CAPABILITY))), null, false),
            emptyList(), demand, empty, null, p)

    @Test fun O0_literalAccepted() { val p = plan(); assertTrue(atomic("O0_common"), common.validDescriptor(descriptor(p))) }
    @Test fun O1_commonRejectsDemandPlan() {
        val p = plan()
        val demand = DemandAuthPlan.rebind(listOf(DemandAuthFixtures.request(binding = 2)), DemandAuthFixtures.binding,
            LifecycleOrderSource(DemandAuthFixtures.binding.executor.originLifetimeId, 21))
        assertFalse(atomic("O1_commonOtherDemand"), common.validDescriptor(descriptor(p, demand = demand)))
    }
    @Test fun O2_commonRejectsEmptyGuardPlan() {
        val p = plan()
        assertFalse(atomic("O2_commonOtherEmptyGuard"), common.validDescriptor(descriptor(p, empty = RemoveEmptyGuardPlan.prepare(DemandAuthFixtures.guard(auth = null)))))
    }
}
