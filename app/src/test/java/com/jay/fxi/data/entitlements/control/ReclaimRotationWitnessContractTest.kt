package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.StoreOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned P3-i-1a contract (purger 설계 v3 final 개정 01 "해석 불가·불완전·모순된 증거로 봉인을 해제하지 않는다",
 * P3i1/design_codex.r1.md i-1a). Automatic reclamation of a previous lifetime's rotation removes its Applied row and its
 * settled seals only when every seal's settlement witness says the same rotation: BEGIN_ROTATION, one before/after/origin
 * across the seals, the seal's own owner and pre-rotation epoch, the journal target naming exactly that owner, axis and
 * epoch, and an after epoch that actually rotated the axis. Anything readable but contradictory is
 * InconsistentReclamation with no candidate; contradictions the reader already refuses stay UninterpretableObligations (X08). This is not a proof of identity with the original command, whose fixed input
 * is gone after a restart. The implementation reads but does not edit this file.
 */
class ReclaimRotationWitnessContractTest {
    private val lifetime = OwnerTrackingLifetimeId.issue()
    private val before = FenceV1("A", "u", "k")
    private val after = FenceV1("A", "next-u", "next-k")

    private fun witness(axis: PurgeScope, op: StoreOp = StoreOp.BEGIN_ROTATION, b: FenceV1 = before, a: FenceV1 = after,
                        origin: String = "origin", journal: JournalTargetV1 = JournalTargetV1("A", axis, before.epoch(axis))) =
        SettlementEvidenceV1("op", LifetimeId(origin), op, b, a, journal)

    private fun seal(id: String, axis: PurgeScope, epoch: String? = before.epoch(axis), owner: String = "A",
                     w: SettlementEvidenceV1 = witness(axis)): String {
        val e = epoch?.let { "\"$it\"" } ?: "null"
        return NamespaceSettlementFixtures.withWitness("""{"id":"$id","kind":"NAMESPACE","ownerUid":"$owner","axis":"${axis.name}","epoch":$e}""", w)
            .toPayloadEntry().fields.toString()
    }
    private fun raw(vararg seals: Pair<String, String>): Preferences = ReclamationFixtures.raw(
        seals = "[${seals.joinToString(",") { it.second }}]", evidence = "[${ReclamationFixtures.rotation(ids = seals.map { it.first })}]")
    private fun decide(p: Preferences) = ReclaimPreviousLifetimeEvidence.decide(ControlRecordReader().read(p), lifetime, ControlPayloadCodec())

    private fun removes(id: String, p: Preferences) {
        val d = decide(p)
        assertTrue("$id: Confirm, got $d", d is RecordTransactionDecision.Confirm)
        val next = ControlRecordReader().read((d as RecordTransactionDecision.Confirm).candidate) as ControlRecordRead.Supported
        assertEquals("$id: seals gone", 0, next.arrays.getValue(ControlKind.SEAL).entries.size)
    }
    private fun inconsistent(id: String, p: Preferences) {
        val d = decide(p)
        assertTrue("$id: Observe(RecoveryRequired(InconsistentReclamation)), got $d", d is RecordTransactionDecision.Observe &&
            (d.value as? ControlEvidenceReclamationResult.RecoveryRequired)?.reason == RecoveryReason.InconsistentReclamation)
    }

    /** Contradictions the control reader already refuses to interpret: the reclamation must stay refused for them too. */
    private fun readerRefuses(id: String, p: Preferences) {
        val d = decide(p)
        assertTrue("$id: Observe(RecoveryRequired(UninterpretableObligations)), got $d", d is RecordTransactionDecision.Observe &&
            (d.value as? ControlEvidenceReclamationResult.RecoveryRequired)?.reason == RecoveryReason.UninterpretableObligations)
    }

    @Test fun X01_aCoherentRotation_isReclaimed_withOneOrBothAxes() {
        removes("X01 user", raw("s" to seal("s", PurgeScope.USER)))
        removes("X01 both", raw("s" to seal("s", PurgeScope.USER), "t" to seal("t", PurgeScope.CAPABILITY)))
    }

    @Test fun X02_aWitnessThatIsNotARotation_isInconsistent() {
        inconsistent("X02", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, op = StoreOp.SIGN_OUT))))
    }

    @Test fun X04_aSealEpochThatIsNotTheWitnessBefore_isInconsistent() {
        inconsistent("X04", raw("s" to seal("s", PurgeScope.USER, epoch = "zzz",
            w = witness(PurgeScope.USER, journal = JournalTargetV1("A", PurgeScope.USER, "zzz")))))
    }


    @Test fun X06_sealsOfOneRotationThatDisagree_areInconsistent() {
        val user = "s" to seal("s", PurgeScope.USER)
        inconsistent("X06 before", raw(user, "t" to seal("t", PurgeScope.CAPABILITY, w = witness(PurgeScope.CAPABILITY, b = FenceV1("A", "u2", "k")))))
        inconsistent("X06 after", raw(user, "t" to seal("t", PurgeScope.CAPABILITY, w = witness(PurgeScope.CAPABILITY, a = FenceV1("A", "next-u2", "next-k")))))
        inconsistent("X06 origin", raw(user, "t" to seal("t", PurgeScope.CAPABILITY, w = witness(PurgeScope.CAPABILITY, origin = "other-origin"))))
    }

    @Test fun X07_aWitnessForAnotherOwner_isInconsistent() {
        inconsistent("X07", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER,
            b = FenceV1("B", "u", "k"), a = FenceV1("B", "next-u", "next-k")))))
    }

    // Pre-fix probe (P3i1): the reader already rejects a non-null journal target that is not the seal and an axis the witness
    // did not rotate, so these were never reclaimable; the row pins that the fix keeps them refused (null wildcards: X12).
    @Test fun X08_contradictionsTheReaderAlreadyRejects_stayRefused() {
        readerRefuses("X08 journal axis", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, journal = JournalTargetV1("A", PurgeScope.CAPABILITY, "u")))))
        readerRefuses("X08 journal epoch", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, journal = JournalTargetV1("A", PurgeScope.USER, "other")))))
        readerRefuses("X08 journal owner", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, journal = JournalTargetV1("B", PurgeScope.USER, "u")))))
        readerRefuses("X08 not rotated", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, a = FenceV1("A", "u", "next-k")))))
    }

    // Battery r1 (YM2·YM3·YM5·YM8): each witness condition alone must refuse, not only in combination.
    @Test fun X09_eachOwnerSideAlone_isInconsistent() {
        inconsistent("X09 before", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, b = FenceV1("B", "u", "k")))))
        inconsistent("X09 after", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, a = FenceV1("B", "next-u", "next-k")))))
    }

    @Test fun X10_anAfterWithoutAnEpochForTheAxis_isInconsistent() {
        inconsistent("X10", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, a = FenceV1("A", null, "next-k")))))
    }

    @Test fun X11_oneRotationRetiringTheSameAxisTwice_isInconsistent() {
        inconsistent("X11", raw("s" to seal("s", PurgeScope.USER), "t" to seal("t", PurgeScope.USER)))
    }

    // Codex r1 review: the reader accepts a null journal owner or epoch as a wildcard for a NAMESPACE seal, so only this
    // reclamation can hold the journal to the seal's exact target.
    @Test fun X12_aWildcardJournalTarget_isInconsistent() {
        inconsistent("X12 both", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, journal = JournalTargetV1(null, PurgeScope.USER, null)))))
        inconsistent("X12 owner", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, journal = JournalTargetV1(null, PurgeScope.USER, "u")))))
        inconsistent("X12 epoch", raw("s" to seal("s", PurgeScope.USER, w = witness(PurgeScope.USER, journal = JournalTargetV1("A", PurgeScope.USER, null)))))
    }
}
