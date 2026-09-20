package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Diagnostic-only contrasts are CLASSIFICATION_ONLY, never counted as eligibility kills. */
class ControlLifecycleEvidenceDiagnosticTest {
    private val command = F.command(F.descriptor(transition = LifecycleTransition.SETTLE_QUERY, targets = listOf(F.createRequest)))
    private val tracked = TrackedControlCommand(command)
    private fun conflict(read: ControlRecordRead.Supported, reason: ConflictReason = ConflictReason.TargetMissing) =
        ControlStoreResult.Conflict(command, emptySet(), emptySet(), reason, TargetExpectation(command, listOf("d")), read)
    private fun diagnostic(read: ControlRecordRead?, result: ControlStoreResult?) =
        checkNotNull(ControlLifecycleDiagnostics.observe(command, tracked, read, result))
    @Test fun R18a_matchingOwnMissingSuccessor() {
        val read = F.read(F.raw(evidence = "[${F.wire(command)}]"))
        tracked.confirmationRequested.set(true)
        val d = diagnostic(read, conflict(read))
        assertEquals(F.retry("R18a"), LifecycleClassification.MATCHING_APPLIED_POSTCONDITION_UNAVAILABLE, d.classification)
        assertEquals(LifecycleOwnEvidence.Matched, d.ownEvidence)
        assertEquals(LifecycleTargetObservation.Absent, d.targets.single().observation)
        assertTrue(d.confirmationRequested)
        assertFalse(d.previouslyConfirmed)
    }
    @Test fun R18b_storageFailureWithoutProof() {
        val read = F.read()
        tracked.confirmationRequested.set(true)
        val result = ControlStoreResult.Unconfirmed(command, setOf(command), emptySet(), UnconfirmedReason.StorageFailure,
            ControlAttemptPhase.ConfirmingStorage, read, IOException("injected"))
        val d = diagnostic(read, result)
        assertEquals(F.retry("R18b"), LifecycleClassification.NO_LANDING_PROOF, d.classification)
        assertEquals(LifecycleOwnEvidence.Absent, d.ownEvidence)
        assertTrue(d.confirmationRequested)
        assertFalse(d.previouslyConfirmed)
    }
    @Test fun R18c_observedOwnLost() {
        val read = F.read()
        tracked.observedApplied.set(true)
        tracked.confirmed.set(true)
        tracked.confirmationRequested.set(true)
        val d = diagnostic(read, ControlStoreResult.RecoveryRequired(command, emptySet(), emptySet(), RecoveryReason.CommandEvidenceLost, read))
        assertEquals(F.retry("R18c"), LifecycleClassification.EVIDENCE_UNAVAILABLE, d.classification)
        assertEquals(LifecycleOwnEvidence.Absent, d.ownEvidence)
        assertTrue(d.previouslyConfirmed)
        assertTrue(d.confirmationRequested)
    }
    @Test fun R18d_firstIneligibleAttempt() {
        val read = F.read()
        val result = ControlStoreResult.Rejected(command, emptySet(), emptySet(), RejectionReason.InvalidRequest("LifecycleWriterUnavailable"), read)
        val d = diagnostic(read, result)
        assertEquals(F.retry("R18d"), LifecycleClassification.NO_LANDING_PROOF, d.classification)
        assertFalse(d.confirmationRequested)
        assertFalse(d.previouslyConfirmed)
    }
    @Test fun preconditionChangedIsNotProofOfNeverLanded() {
        val read = F.read()
        val d = diagnostic(read, conflict(read))
        assertEquals(LifecycleClassification.PRECONDITION_CHANGED_WITHOUT_LANDING_PROOF, d.classification)
    }
    @Test fun noSnapshotIsUnknownAndDoesNotChangeHistory() {
        val d = diagnostic(null, null)
        assertEquals(LifecycleOwnEvidence.Unknown, d.ownEvidence)
        assertEquals(LifecycleTargetObservation.Uninterpretable, d.targets.single().observation)
        assertFalse(tracked.observedApplied.get())
        assertFalse(tracked.confirmed.get())
        assertFalse(tracked.confirmationRequested.get())
        assertNull(tracked.firstConfirmDiscontinuityCount)
    }
    @Test fun onlyLastImmutableDiagnosticIsRetainedAndAttached() {
        val read = F.read()
        val result = conflict(read)
        val first = diagnostic(read, result)
        command.observeLifecycleDiagnostic(first)
        val second = diagnostic(null, null)
        command.observeLifecycleDiagnostic(second)
        assertSame(second, command.lastLifecycleDiagnostic)
        assertSame(second, ControlLifecycleDiagnostics.attach(result, second).lifecycleDiagnostic)
        assertEquals(LifecycleOwnEvidence.Absent, first.ownEvidence)
        assertTrue(runCatching { (first.targets as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(ControlCommandLifecycle.RETAINED, command.lifecycleState)
    }
}
