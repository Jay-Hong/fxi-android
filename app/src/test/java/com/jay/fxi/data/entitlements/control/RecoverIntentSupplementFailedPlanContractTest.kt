package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Claude-owned 5e-2 supplement (measure ledger r1 review, OPEN 1): direct-boundary behaviour of the null branches that a
 * prepared writer never reaches but a failed plan does — T:55 (validDescriptor, expectedTargets null), T:211
 * (validCandidate, source null), P:46 (preimageProblem, source null) with an invalid-source plan, and T:117
 * (buildCandidate, retirement null) with a planner-failed plan. These are behaviour checks of the direct boundary; they are
 * not bypass-mutant kills or storage-preservation evidence. T:105 is closed by reasoning (T:103 preimage returns first).
 * The implementation thread reads but does not edit this file.
 */
class RecoverIntentSupplementFailedPlanContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private val codec = ControlPayloadCodec()
    private val writer = RecoverIntentTransition(codec)
    private val newLife = LifetimeId("new-life")
    private val executor = SettlementExecutor("A", 3, newLife)
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), 1, "binding-start")
    private val ids = RecoverIntentIds("00000000-0000-0000-0000-000000000101", "new-request", RecoveryFreshEpochs(null, "00000000-0000-0000-0000-000000000012"))
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, target: String?) =
        ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":${q(owner)},"axis":"CAPABILITY","targetEpoch":${q(target)}}""")
    private fun input(src: ControlNode) = RecoverIntentInput(src, FenceV1("A", "u", "k"), binding, HoldRecoveryClosure.AfterRestart(src, executor, "previous-tracker", true, true))
    private fun raw(intents: String): Preferences = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[OWNER_UID] = "A"; this[USER_EPOCH] = "u"; this[KRX_EPOCH] = "k"
        this[MAY_CONTAIN_PREMIUM] = true; this[MAY_CONTAIN_KRX] = true
        this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = intents
    }.toPreferences()
    private fun read(p: Preferences) = ControlLifecycleEvidenceFixtures.read(p)

    private val invalidSourcePlan get() = RecoverIntentPlan.prepare(input(HoldRecoveryFixtures.hold()), ids, LifecycleOrderSource(newLife, 21))

    @Test fun F0_invalidSourcePlanPremise() {
        val p = invalidSourcePlan
        assertEquals(HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest("InvalidIntentSource")), p.preparationProblem)
        assertNull(p.source)
    }
    @Test fun F1_validDescriptorRejectsInvalidSource() {
        val p = invalidSourcePlan
        val d = ControlLifecycleDescriptor(ids.operationId, LifecycleTransition.RECOVER_INTENT, emptyList(), executor, null, emptyList(), recoverIntent = p)
        assertFalse(atomic("F1_descriptorExpectedTargets"), writer.validDescriptor(p, d))
    }
    @Test fun F2_validCandidateRejectsInvalidSource() {
        val p = invalidSourcePlan; val c = ControlLifecycleEvidenceFixtures.command(p.descriptor())
        val r = raw("[]")
        assertFalse(atomic("F2_candidateSource"), writer.validCandidate(c, p, read(r), r))
    }
    @Test fun F3_preimageOfInvalidSource() {
        assertEquals(atomic("F3_preimageSource"), ConflictReason.UninterpretableTarget, invalidSourcePlan.preimageProblem(read(raw("[]"))))
    }
    @Test fun F4_buildCandidateWithoutRetirement() {
        val src = intent("B", "k") // departed owner, target == current → planner conflict, retirement null
        val p = RecoverIntentPlan.prepare(input(src), ids.copy(epochs = RecoveryFreshEpochs(null, null)), LifecycleOrderSource(newLife, 21))
        assertEquals(HoldRecoveryProblem.Conflict(ConflictReason.TargetChanged), p.preparationProblem)
        assertNull(p.retirement)
        val c = ControlLifecycleEvidenceFixtures.command(p.descriptor())
        assertEquals(atomic("F4_buildRetirement"), RecoverIntentCandidateBuild.Failed(HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest("RequiredDecisionEffectMissing"))),
            writer.buildCandidate(c, p, read(raw("[${src.toPayloadEntry().fields}]"))))
    }
}
