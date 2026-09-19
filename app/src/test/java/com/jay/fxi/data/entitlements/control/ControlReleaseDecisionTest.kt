package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.fixture
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.row
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.wire
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.read
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.raw
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.bind
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.pending
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Pure judgements never publish pending or write a candidate; storage assertions belong to unit 2. */
class ControlReleaseDecisionTest {
    private val tracked = fixture()
    private val command = tracked.command
    private val expected = row(command)
    private val own = wire(expected)
    private fun decide(read: ControlRecordRead) = ControlCommandReleaseDecision.decide(read, tracked)
    private fun recovery(expected: RecoveryReason, snapshot: ControlRecordRead) {
        assertEquals("release recovery reason", expected,
            (decide(snapshot) as? ControlCommandReleaseDecision.Decision.RecoveryRequired)?.reason)
        assertEquals(ControlCommandLifecycle.RETAINED, command.lifecycleState)
        assertNull(tracked.releaseDescriptor)
    }
    private fun conflict(snapshot: ControlRecordRead) {
        assertEquals("release evidence mismatch", ConflictReason.CommandEvidenceMismatch,
            (decide(snapshot) as? ControlCommandReleaseDecision.Decision.Conflict)?.reason)
    }
    private fun ready(snapshot: ControlRecordRead): ReleasePendingDescriptor {
        val decision = decide(snapshot)
        assertTrue("release is eligible only as a pure decision: $decision", decision is ControlCommandReleaseDecision.Decision.Ready)
        return (decision as ControlCommandReleaseDecision.Decision.Ready).descriptor
    }
    private fun rawRecovery(reason: RecoveryReason, change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) =
        recovery(reason, ControlRecordReader().read(raw("[$own]").toMutablePreferences().apply(change)))

    @Test fun B01_allKeysAbsent() { recovery(RecoveryReason.MigrationOrRecovery, ControlRecordReader().read(emptyPreferences())) }
    @Test fun B02_schemaOne() { recovery(RecoveryReason.ControlSchemaMigrationRequired, ControlRecordReader().read(ReclamationFixtures.raw(schema = 1))) }
    @Test fun B03a_futureSchema() { rawRecovery(RecoveryReason.UnreadableRecord) { it[ControlStoreTestStorage.SCHEMA] = 3 } }
    @Test fun B03b_wrongType() { rawRecovery(RecoveryReason.UnreadableRecord) { it[stringPreferencesKey("control_schema")] = "2" } }
    @Test fun B03c_missingPayload() { rawRecovery(RecoveryReason.UnreadableRecord) { it.remove(ControlStoreTestStorage.HOLD) } }
    @Test fun B03d_malformedEnvelope() { rawRecovery(RecoveryReason.UnreadableRecord) { it[ControlStoreTestStorage.HOLD] = "[" } }
    @Test fun B04_opaqueEvidenceSibling() { recovery(RecoveryReason.UninterpretableMetadata, read("[$own,17]")) }
    @Test fun B05_unsupportedFence() { rawRecovery(RecoveryReason.UninterpretableMetadata) { it[ReclamationFixtures.fenceKey] = "[{}]" } }
    @Test fun B06a_opaqueSeal() { rawRecovery(RecoveryReason.UninterpretableObligations) { it[ControlStoreTestStorage.SEAL] = "[17]" } }
    @Test fun B06b_opaqueDemand() { rawRecovery(RecoveryReason.UninterpretableObligations) { it[ControlStoreTestStorage.DEMAND] = "[17]" } }
    @Test fun B06c_opaqueHold() { rawRecovery(RecoveryReason.UninterpretableObligations) { it[ControlStoreTestStorage.HOLD] = "[17]" } }
    @Test fun B06d_opaqueRecoveryIntent() { rawRecovery(RecoveryReason.UninterpretableObligations) { it[ControlStoreTestStorage.RECOVERY] = "[17]" } }
    @Test fun B07a_duplicateInterpretedRows() { recovery(RecoveryReason.UninterpretableMetadata, read("[$own,$own]")) }
    @Test fun B07b_duplicateOpaqueOwn() { recovery(RecoveryReason.UninterpretableMetadata, read("[$own," + own.replace("\"version\":2", "\"version\":3") + "]")) }
    @Test fun B08_wrongOwnLifetime() { conflict(read("[${wire(row(command, lifetime = ReclamationFixtures.oldLife))}]")) }
    @Test fun B09_ownRotationKind() { conflict(read("[${ReclamationFixtures.rotation(command.id, command.ownerTrackingLifetimeId.value)}]")) }
    @Test fun B15a_noncontiguousIndex() { recovery(RecoveryReason.UninterpretableMetadata, read("[" + own.replace("\"index\":1", "\"index\":2") + "]")) }
    @Test fun B15b_duplicateTargetId() { recovery(RecoveryReason.UninterpretableMetadata, read("[" + own.replace("\"id\":\"r\"", "\"id\":\"d\"") + "]")) }
    @Test fun B15c_joinedAndWritten() { recovery(RecoveryReason.UninterpretableMetadata, read("[" + own.replace("\"joined\":false", "\"joined\":true") + "]")) }
    @Test fun B15d_allUnwritten() { recovery(RecoveryReason.UninterpretableMetadata, read("[" + own.replace("\"written\":true", "\"written\":false") + "]")) }
    @Test fun B16_observedLostDespiteConfirmed() { tracked.observedApplied.set(true); tracked.expectedApplied = null; recovery(RecoveryReason.CommandEvidenceLost, read()) }
    @Test fun B16_expectedButAbsentIsLost() { recovery(RecoveryReason.CommandEvidenceLost, read()) }
    @Test fun B17_unexpectedEvidenceIsNotAdopted() { tracked.expectedApplied = null; conflict(read("[$own]")) }
    @Test fun B19_confirmedWithoutApplied() { tracked.expectedApplied = null; assertSame(ReleasePendingDescriptor.ConfirmedWithoutApplied, ready(read())); assertNull(tracked.releaseDescriptor) }
    @Test fun B20_currentObligationPostconditionIrrelevant() { val changed = raw("[$own]").toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[]"; this[ControlStoreTestStorage.RECOVERY] = "[]" }; assertTrue(ready(ControlRecordReader().read(changed)) is ReleasePendingDescriptor.ExactMutations) }
    @Test fun C12_oldBusinessBaselineDoesNotBlockRetained() { tracked.bindFirstConfirm(BigInteger.ZERO); tracked.expectedApplied = null; assertSame(ReleasePendingDescriptor.ConfirmedWithoutApplied, ready(read())); assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount) }
    @Test fun C19_pendingExternalAbsence() { val descriptor = ReleasePendingDescriptor.ExactMutations(expected); bind(tracked, descriptor); pending(command); tracked.observedApplied.set(true); assertSame(descriptor, ready(read())) }
    @Test fun C09d_pendingMismatchPreservesDescriptor() { val descriptor = ReleasePendingDescriptor.ExactMutations(expected); bind(tracked, descriptor); pending(command); conflict(read("[${wire(row(command, lifetime = ReclamationFixtures.oldLife))}]")); assertSame(descriptor, tracked.releaseDescriptor) }
    @Test fun C09e_pendingDuplicate() { bind(tracked, ReleasePendingDescriptor.ExactMutations(expected)); pending(command); assertEquals(RecoveryReason.UninterpretableMetadata, (decide(read("[$own,$own]")) as? ControlCommandReleaseDecision.Decision.RecoveryRequired)?.reason) }
    @Test fun C09f_pendingOpaque() { bind(tracked, ReleasePendingDescriptor.ExactMutations(expected)); pending(command); assertEquals(RecoveryReason.UninterpretableMetadata, (decide(read("[$own,17]")) as? ControlCommandReleaseDecision.Decision.RecoveryRequired)?.reason) }
    @Test fun C19_pendingWithoutAppliedRejectsAppearingRow() { bind(tracked, ReleasePendingDescriptor.ConfirmedWithoutApplied); pending(command); conflict(read("[$own]")) }
    @Test fun C19_pendingWithoutAppliedConfirmsAbsence() { bind(tracked, ReleasePendingDescriptor.ConfirmedWithoutApplied); pending(command); assertSame(ReleasePendingDescriptor.ConfirmedWithoutApplied, ready(read())) }
    @Test fun A12_pendingMissingDescriptorIsInvariantFailure() { pending(command); assertEquals("pending release has no descriptor", runCatching { decide(read()) }.exceptionOrNull()?.message) }
    @Test fun A13_releasedCannotEnterRecordDecision() {
        pending(command); ControlReleaseFixtures.released(command)
        assertEquals("released ref needs no record decision", runCatching { decide(read()) }.exceptionOrNull()?.message)
    }
    @Test fun A09_rotationCannotEnterRecordDecision() {
        val input = RotateAndSettleNamespaces(emptyList(), NamespaceSettlementFixtures.fence,
            NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand, "op", "d", null, null)
        val rotation = CommandRef("op", ControlCommandBody.RotateAndSettle(input), command.ownerTrackingLifetimeId)
        assertEquals("release requires mutations", runCatching {
            ControlCommandReleaseDecision.decide(read(), TrackedControlCommand(rotation))
        }.exceptionOrNull()?.message)
    }
    @Test fun B18_exactReadyDoesNotCloseRefOrMutateHistory() {
        val snapshot = read("[$own]")
        val before = snapshot.original
        val fixed = tracked.expectedApplied
        val descriptor = ready(snapshot) as ReleasePendingDescriptor.ExactMutations
        assertEquals(expected.targets, descriptor.row.targets)
        assertEquals(before, snapshot.original)
        assertSame(fixed, tracked.expectedApplied)
        assertEquals(ControlCommandLifecycle.RETAINED, command.lifecycleState)
        assertFalse(tracked.observedApplied.get()) // Pure decision does not impersonate actual observation.
        assertNull(tracked.releaseDescriptor)
    }
    @Test fun C08a_pendingRecoversAfterClassification() {
        val descriptor = ReleasePendingDescriptor.ExactMutations(expected)
        bind(tracked, descriptor); pending(command); tracked.observedApplied.set(true)
        tracked.bindFirstConfirm(BigInteger.ZERO)
        assertTrue(decide(ControlRecordReader().read(ReclamationFixtures.raw(schema = 1))) is ControlCommandReleaseDecision.Decision.RecoveryRequired)
        assertSame(descriptor, ready(read()))
        assertSame(descriptor, tracked.releaseDescriptor)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, command.lifecycleState)
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
    }
    @Test fun C09a_pendingRecoversAfterClassification() {
        val descriptor = ReleasePendingDescriptor.ExactMutations(expected)
        bind(tracked, descriptor); pending(command); tracked.observedApplied.set(true)
        tracked.bindFirstConfirm(BigInteger.ZERO)
        assertTrue(decide(ControlRecordReader().read(ReclamationFixtures.raw(schema = 1))) is ControlCommandReleaseDecision.Decision.RecoveryRequired)
        assertSame(descriptor, ready(read("[$own]")))
        assertSame(descriptor, tracked.releaseDescriptor)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, command.lifecycleState)
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
    }
    @Test fun C08b_pendingRecoversAfterClassification() {
        val descriptor = ReleasePendingDescriptor.ExactMutations(expected)
        bind(tracked, descriptor); pending(command); tracked.observedApplied.set(true)
        tracked.bindFirstConfirm(BigInteger.ZERO)
        assertTrue(decide(ControlRecordReader().read(emptyPreferences())) is ControlCommandReleaseDecision.Decision.RecoveryRequired)
        assertSame(descriptor, ready(read()))
        assertSame(descriptor, tracked.releaseDescriptor)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, command.lifecycleState)
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
    }
    @Test fun C09b_pendingRecoversAfterClassification() {
        val descriptor = ReleasePendingDescriptor.ExactMutations(expected)
        bind(tracked, descriptor); pending(command); tracked.observedApplied.set(true)
        tracked.bindFirstConfirm(BigInteger.ZERO)
        assertTrue(decide(ControlRecordReader().read(emptyPreferences())) is ControlCommandReleaseDecision.Decision.RecoveryRequired)
        assertSame(descriptor, ready(read("[$own]")))
        assertSame(descriptor, tracked.releaseDescriptor)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, command.lifecycleState)
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
    }
    @Test fun C08c_pendingRecoversAfterClassification() {
        val descriptor = ReleasePendingDescriptor.ExactMutations(expected)
        bind(tracked, descriptor); pending(command); tracked.observedApplied.set(true)
        tracked.bindFirstConfirm(BigInteger.ZERO)
        assertTrue(decide(ControlRecordReader().read(raw().toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[" })) is ControlCommandReleaseDecision.Decision.RecoveryRequired)
        assertSame(descriptor, ready(read()))
        assertSame(descriptor, tracked.releaseDescriptor)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, command.lifecycleState)
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
    }
    @Test fun C09c_pendingRecoversAfterClassification() {
        val descriptor = ReleasePendingDescriptor.ExactMutations(expected)
        bind(tracked, descriptor); pending(command); tracked.observedApplied.set(true)
        tracked.bindFirstConfirm(BigInteger.ZERO)
        assertTrue(decide(ControlRecordReader().read(raw().toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[" })) is ControlCommandReleaseDecision.Decision.RecoveryRequired)
        assertSame(descriptor, ready(read("[$own]")))
        assertSame(descriptor, tracked.releaseDescriptor)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, command.lifecycleState)
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
    }
}
