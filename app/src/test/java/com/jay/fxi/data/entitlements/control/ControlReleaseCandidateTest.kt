package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class ControlReleaseCandidateTest {
    private val tracked = ControlReleaseFixtures.fixture()
    private val c = tracked.command
    private val row = ControlReleaseFixtures.wire(ControlReleaseFixtures.row(c))
    private fun read(evidence: String = "[$row]") = ControlReleaseFixtures.read(evidence) as ControlRecordRead.Supported
    @Test fun B24a_codecRejectionProducesNoReadyCandidate() {
        // Pure boundary: supplied read was classified with the default codec. Tight encoder rejects [].
        val result = ControlReleaseCandidate.build(read(), c, ControlPayloadCodec(maxPayloadBytes = 1))
        assertEquals(RejectionReason.TooLarge(ControlPayloadKey.COMMAND_EVIDENCE, 2, 1), (result as ControlReleaseCandidate.Result.Rejected).reason)
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState); assertNull(tracked.releaseDescriptor)
    }
    @Test fun B23_candidatePreservesSiblingValuesAndOrder() {
        val sibling = ReclamationFixtures.mutation()
        val before = read("[$sibling,$row,${ReclamationFixtures.rotation()}]")
        val built = ControlReleaseCandidate.build(before, c, ControlPayloadCodec())
        assertTrue("valid candidate must be ready", built is ControlReleaseCandidate.Result.Ready)
        val result = built as ControlReleaseCandidate.Result.Ready
        val after = ControlRecordReader().read(result.snapshot) as ControlRecordRead.Supported
        assertEquals(listOf("m", "op"),
            (after.metadata as ControlMetadataRead.V2).evidence.entries.map { (it as ControlEvidenceEntryRead.Interpreted).value.commandId })
        assertEquals(before.original.toMutablePreferences().apply { remove(ReclamationFixtures.evidenceKey) },
            result.snapshot.toMutablePreferences().apply { remove(ReclamationFixtures.evidenceKey) })
    }
    @Test fun B24a_codecEnvelopeExceptionIsRejectedBeforePublication() {
        val before = read("[${ReclamationFixtures.mutation()},$row]")
        val result = ControlReleaseCandidate.build(before, c, ControlPayloadCodec(maxDepth = 1))
        assertTrue(result is ControlReleaseCandidate.Result.Rejected)
        assertTrue((result as ControlReleaseCandidate.Result.Rejected).reason is RejectionReason.InvalidRequest)
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState); assertNull(tracked.releaseDescriptor)
    }
    @Test fun B24b_completeCandidateIsReclassifiedBeforeReady() {
        // Deliberately inconsistent pure input: metadata came from a healthy classification while
        // original has an opaque obligation. The builder must validate its actual candidate again.
        val healthy = read()
        val original = healthy.original.toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[17]" }
        val inconsistent = ControlRecordRead.Supported(original, healthy.arrays, healthy.schemaVersion, healthy.metadata)
        assertEquals(ControlReleaseCandidate.Result.Inconsistent, ControlReleaseCandidate.build(inconsistent, c, ControlPayloadCodec()))
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState); assertNull(tracked.releaseDescriptor)
    }
    @Test fun B23_candidateRechecksRemainingEvidenceValuesAndOrder() {
        val healthy = read("[${ReclamationFixtures.mutation()}]")
        val original = read("[${ReclamationFixtures.rotation()}]").original
        val inconsistent = ControlRecordRead.Supported(original, healthy.arrays, healthy.schemaVersion, healthy.metadata)
        assertEquals(ControlReleaseCandidate.Result.Inconsistent, ControlReleaseCandidate.build(inconsistent, c, ControlPayloadCodec()))
    }
    @Test fun B24b_absencePostconditionRejectsUnreadable() {
        assertFalse(ControlReleaseCandidate.hasAbsencePostcondition(ControlRecordReader().read(mutablePreferencesOf()), c))
    }
    @Test fun B24b_absencePostconditionRejectsSchemaOne() {
        assertFalse(ControlReleaseCandidate.hasAbsencePostcondition(ControlRecordReader().read(ReclamationFixtures.raw(schema = 1)), c))
    }
    @Test fun B24b_absencePostconditionRejectsOpaqueMetadata() {
        assertFalse(ControlReleaseCandidate.hasAbsencePostcondition(read("[17]"), c))
    }
    @Test fun B24b_absencePostconditionRejectsOpaqueObligation() {
        val raw = read("[]").original.toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[17]" }
        assertFalse(ControlReleaseCandidate.hasAbsencePostcondition(ControlRecordReader().read(raw), c))
    }
    @Test fun B24b_absencePostconditionRejectsRemainingOwnRow() {
        assertFalse(ControlReleaseCandidate.hasAbsencePostcondition(read(), c))
    }
}
