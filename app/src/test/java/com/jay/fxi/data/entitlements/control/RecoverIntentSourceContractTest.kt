package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5e-2 source contract (plan v1 row 1): the verified RECOVERY_INTENT source and its
 * exact link into the shared §7.2 planner. Design `d2b5_demand_auth_design_r3_codex.md` L408 (only
 * source-extracted owner/axis/targetEpoch), L436 step 1 (intent subject = owner/axis/targetEpoch,
 * never the current B), §9.4 I01a–c; skeleton design v2 §1 (closed source, require on axes).
 * Expected plans are written as literals here, never read back from the planner under test.
 */
class RecoverIntentSourceContractTest {
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)
    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun intent(owner: String?, axis: String, target: String?, id: String = "r", session: String = "session") =
        ControlObligationFixtures.node("""{"id":"$id","sessionId":"$session","ownerUid":${q(owner)},"axis":"$axis","targetEpoch":${q(target)}}""")
    private fun source(owner: String?, axis: String, target: String?) = checkNotNull(IntentRecoverySource.from(intent(owner, axis, target)))
    private fun throwsIllegalArgument(block: () -> Unit) = runCatching(block).exceptionOrNull() is IllegalArgumentException

    // ---- S1: exact extraction, null owner and null target are values (ControlFacts.kt KDoc, L436 step 1) ----

    @Test fun S1_exactExtraction() {
        val s = source("A", "CAPABILITY", "k")
        assertEquals(atomic("S1_owner"), "A", s.ownerUid)
        assertEquals(atomic("S1_axes"), setOf(PurgeScope.CAPABILITY), s.axes)
        assertEquals(atomic("S1_target"), "k", s.targetEpoch(PurgeScope.CAPABILITY))
        assertEquals(atomic("S1_original"), intent("A", "CAPABILITY", "k").toPayloadEntry(), s.original.toPayloadEntry())
        val n = source(null, "USER", null)
        assertNull(atomic("S1_nullOwner"), n.ownerUid)
        assertEquals(atomic("S1_userAxis"), setOf(PurgeScope.USER), n.axes)
        assertNull(atomic("S1_nullTarget"), n.targetEpoch(PurgeScope.USER))
    }

    // ---- S2: only a schema-valid RECOVERY_INTENT yields a source (skeleton v2 §1: no arbitrary tuple) ----

    @Test fun S2_nonIntentNodesAreNotSources() {
        assertNull(atomic("S2_hold"), IntentRecoverySource.from(HoldRecoveryFixtures.hold()))
        assertNull(atomic("S2_request"), IntentRecoverySource.from(DemandAuthFixtures.request()))
        assertNull(atomic("S2_badAxis"), IntentRecoverySource.from(
            ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"BOTH","targetEpoch":null}""")))
        assertTrue(atomic("S2_positive"), IntentRecoverySource.from(intent("A", "CAPABILITY", null)) != null)
    }

    // ---- S3: targetEpoch outside the source axes is a programming error for both sources (skeleton v2 §1) ----

    @Test fun S3_outOfAxisTargetEpochThrows() {
        val s = source("A", "CAPABILITY", null)
        assertTrue(atomic("S3_intentThrows"), throwsIllegalArgument { s.targetEpoch(PurgeScope.USER) })
        val hold = checkNotNull(HoldRecoverySource.from(HoldRecoveryFixtures.hold()))
        assertEquals(setOf(PurgeScope.CAPABILITY), hold.axes)
        assertTrue(atomic("S3_holdThrows"), throwsIllegalArgument { hold.targetEpoch(PurgeScope.USER) })
        // HOLD keeps reading the archived subject: Query.started.fence (A, u, k) in the fixture.
        assertEquals(atomic("S3_holdOwner"), "A", hold.ownerUid)
        assertEquals(atomic("S3_holdTarget"), "k", hold.targetEpoch(PurgeScope.CAPABILITY))
    }

    // ---- S4: out-of-axis input to the boundary is an explicit negative, not an exception (skeleton v2 §1) ----

    @Test fun S4_axisProblemRejectsNonMemberAxis() {
        val s = source("A", "CAPABILITY", "k")
        val problem = RecoveryRetirementBoundary.axisProblem(s, PurgeScope.USER, FenceV1("A", "u", "k"))
        assertEquals(atomic("S4_mismatch"), HoldRecoveryProblem.Rejected(RejectionReason.InvalidRequest("SourceAxisMismatch")), problem)
        assertNull(atomic("S4_memberOk"), RecoveryRetirementBoundary.axisProblem(s, PurgeScope.CAPABILITY, FenceV1("A", "u", "k")))
    }

    // ---- S5: I01a–c — planner/validator use the exact intent subject, never current values (L408, L436, §9.4) ----

    @Test fun S5_I01_exactSubjectLink() {
        // Departed owner B, CAPABILITY target k0 ≠ current k (L414): preserve, exact source journal, no REQUEST.
        val s = source("B", "CAPABILITY", "k0")
        val before = FenceV1("A", "u", "k")
        val fresh = RecoveryFreshEpochs(null, null)
        fun plan(target: JournalTargetV1) = RecoveryRetirementPlan(before, before,
            listOf(RecoveryAxisRetirement(target, RecoveryAxisAction.Preserve)),
            listOf(PendingPurge("B", null, "k0", setOf(PurgeScope.CAPABILITY))),
            RecoveryRequestRequirement.NotRequiredDepartedOwner)
        val exact = JournalTargetV1("B", PurgeScope.CAPABILITY, "k0")
        assertTrue(atomic("S5_exactPositive"), RecoveryRetirementBoundary.validPlan(s, before, fresh, plan(exact)))
        assertFalse(atomic("I01a_owner"), RecoveryRetirementBoundary.validPlan(s, before, fresh, plan(exact.copy(ownerUid = "A"))))
        assertFalse(atomic("I01b_epoch"), RecoveryRetirementBoundary.validPlan(s, before, fresh, plan(exact.copy(epoch = "k"))))
        assertFalse(atomic("I01c_axis"), RecoveryRetirementBoundary.validPlan(s, before, fresh, plan(exact.copy(axis = PurgeScope.USER))))
        val planned = planRecoveryRetirement(s, before, fresh)
        assertTrue(atomic("S5_planned"), planned is RecoveryRetirementResult.Planned)
        val p = (planned as RecoveryRetirementResult.Planned).plan
        assertEquals(atomic("S5_plannedTarget"), listOf(exact), p.axes.map { it.target })
        assertEquals(atomic("S5_plannedAction"), listOf<RecoveryAxisAction>(RecoveryAxisAction.Preserve), p.axes.map { it.action })
        assertEquals(atomic("S5_plannedJournal"), listOf(PendingPurge("B", null, "k0", setOf(PurgeScope.CAPABILITY))), p.journal)
        assertEquals(atomic("S5_plannedAfter"), before, p.after)
        assertEquals(atomic("S5_plannedRequest"), RecoveryRequestRequirement.NotRequiredDepartedOwner, p.requestRequirement)
    }

    // ---- S6: C0b with both target and current null rotates on the fixed fresh UUID (L413 "현재 epoch가 null이든") ----

    @Test fun S6_nullTargetNullCurrentRotates() {
        val s = source("A", "CAPABILITY", null)
        val before = FenceV1("A", "u", null)
        val freshKrx = "00000000-0000-0000-0000-000000000012"
        val planned = planRecoveryRetirement(s, before, RecoveryFreshEpochs(null, freshKrx))
        assertTrue(atomic("S6_planned"), planned is RecoveryRetirementResult.Planned)
        val p = (planned as RecoveryRetirementResult.Planned).plan
        assertEquals(atomic("S6_rotate"), listOf<RecoveryAxisAction>(RecoveryAxisAction.Rotate(freshKrx)), p.axes.map { it.action })
        assertEquals(atomic("S6_after"), FenceV1("A", "u", freshKrx), p.after)
        assertEquals(atomic("S6_nullJournal"), listOf(PendingPurge("A", null, null, setOf(PurgeScope.CAPABILITY))), p.journal)
        assertEquals(atomic("S6_request"), RecoveryRequestRequirement.RequiredCurrentOwner, p.requestRequirement)
    }
}
