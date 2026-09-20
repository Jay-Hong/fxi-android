package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import org.junit.Assert.*
import org.junit.Test

class ControlLifecycleEvidenceReclamationTest {
    @Test fun R13a_mixedKindsPreserveCurrentAndPreviousLifecycle() {
        val life = OwnerTrackingLifetimeId.issue()
        val kept = HandoverFormatFixtures.applied() + "," + F.wire() + "," + F.wire(command = "current", lifetime = life.value)
        val raw = ReclamationFixtures.raw(evidence = "[${ReclamationFixtures.mutation()},${ReclamationFixtures.rotation()},$kept]")
        val read = F.read(raw)
        assertFalse(read.blocksProtectedAdmission)
        val result = ReclaimPreviousLifetimeEvidence.decide(read, life, ControlPayloadCodec())
        assertTrue(result is RecordTransactionDecision.Confirm)
        val candidate = (result as RecordTransactionDecision.Confirm).candidate
        val expected = raw.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)] = "[]"
            this[F.evidenceKey] = "[$kept]"
        }.toPreferences()
        assertEquals(F.atomic("R13a"), expected, candidate)
        assertEquals(raw, read.original)
        assertNull(result.value)
    }
    @Test fun onlyLifecycleKeepsExactPayloadWhitespace() {
        val raw = F.raw(evidence = " [ ${F.wire()} ] ")
        val read = F.read(raw)
        val result = ReclaimPreviousLifetimeEvidence.decide(read, OwnerTrackingLifetimeId.issue(), ControlPayloadCodec())
        assertSame(read.original, (result as RecordTransactionDecision.Confirm).candidate)
    }
    @Test fun R13c_releaseEligibilityBoundary() {
        val c = F.command()
        val tracked = TrackedControlCommand(c).apply { confirmed.set(true) }
        val result = ControlCommandReleaseEligibility.decide(c, c.ownerTrackingLifetimeId, tracked, false, false)
        assertFalse(F.eligible("R13c"), result is ControlCommandReleaseEligibility.Decision.Eligible)
        assertEquals(ReleaseRejectionReason.UnsupportedCommandKind, (result as ControlCommandReleaseEligibility.Decision.Rejected).reason)
    }
}
