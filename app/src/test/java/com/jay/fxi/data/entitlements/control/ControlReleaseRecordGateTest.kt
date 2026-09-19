package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Each malformed or mismatched input is a separate JUnit method and changes one condition. */
class ControlReleaseRecordGateTest : ReleaseOwnerTestBase() {
    private fun AppliedEvidence.Mutations.copy(
        ownerTrackingLifetimeId: String = this.ownerTrackingLifetimeId,
        targets: List<AppliedTarget> = this.targets
    ) = AppliedEvidence.Mutations(commandId, ownerTrackingLifetimeId, targets)
    private suspend fun command(): CommandRef {
        o.seed(demand = "[${ControlObligationFixtures.request}]", recovery = "[${ControlObligationFixtures.recovery}]")
        return confirmed(o.control.prepare(
            o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) { set("raisedAt", ControlScalar.Integer(5)) },
            o.control.edit(ControlKind.RECOVERY_INTENT, node(ControlObligationFixtures.recovery)) {}))
    }
    private suspend fun gate(reason: Any, change: (MutablePreferences, AppliedEvidence.Mutations) -> Unit) {
        val c = command(); val t = history(c); val expected = t.expectedApplied as AppliedEvidence.Mutations
        o.data.edit { change(it, expected) } // Defensive input injection, not a production recovery API.
        val before = disk(); val writes = o.storage.writes; val work = tracking.recoverySnapshot()
        val result = o.control.releaseAfterConsumption(c)
        when (reason) {
            is RecoveryReason -> assertEquals(reason, (result as? ControlCommandReleaseResult.RecoveryRequired)?.reason)
            is ConflictReason -> assertEquals(reason, (result as? ControlCommandReleaseResult.Conflict)?.reason)
            else -> error("unsupported oracle")
        }
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState); assertSame(t, history(c)); assertNull(t.releaseDescriptor)
        assertEquals(work.unresolvedCommands, result.localUnresolvedCommands); assertEquals(work.pendingReleases, result.localPendingReleases)
        assertEquals(before, disk()); assertEquals(writes, o.storage.writes); assertTrue(tracking.executing.isEmpty())
        val classified = ControlRecordReader().read(before)
        assertEquals(if (classified !is ControlRecordRead.Supported || classified.schemaVersion != 2) BigInteger.ONE else BigInteger.ZERO,
            tracking.evidenceDiscontinuityCount)
    }
    private suspend fun mismatch(change: (AppliedEvidence.Mutations) -> AppliedEvidence.Mutations) =
        gate(ConflictReason.CommandEvidenceMismatch) { p, own -> p[evidenceKey] = "[${ControlReleaseFixtures.wire(change(own))}]" }
    private suspend fun opaqueRow(change: (String) -> String) = gate(RecoveryReason.UninterpretableMetadata) { p, own ->
        p[evidenceKey] = "[${change(ControlReleaseFixtures.wire(own))}]"
    }
    @Test fun B01_absentControlIsObservedBeforeRecovery() = runReleaseTest<Unit> { gate(RecoveryReason.MigrationOrRecovery) { p, _ -> p.clear() } }
    @Test fun B02_schemaOneIsObservedBeforeRecovery() = runReleaseTest<Unit> {
        gate(RecoveryReason.ControlSchemaMigrationRequired) { p, _ -> p[ControlStoreTestStorage.SCHEMA] = 1; p.remove(evidenceKey); p.remove(ReclamationFixtures.fenceKey) }
    }
    @Test fun B03a_futureSchema() = runReleaseTest<Unit> { gate(RecoveryReason.UnreadableRecord) { p, _ -> p[ControlStoreTestStorage.SCHEMA] = 3 } }
    @Test fun B03b_wrongType() = runReleaseTest<Unit> { gate(RecoveryReason.UnreadableRecord) { p, _ -> p[stringPreferencesKey("control_schema")] = "2" } }
    @Test fun B03c_missingPayload() = runReleaseTest<Unit> { gate(RecoveryReason.UnreadableRecord) { p, _ -> p.remove(ControlStoreTestStorage.HOLD) } }
    @Test fun B03d_malformedEnvelope() = runReleaseTest<Unit> { gate(RecoveryReason.UnreadableRecord) { p, _ -> p[ControlStoreTestStorage.HOLD] = "[" } }
    @Test fun B04_opaqueSibling() = runReleaseTest<Unit> { gate(RecoveryReason.UninterpretableMetadata) { p, own -> p[evidenceKey] = "[${ControlReleaseFixtures.wire(own)},17]" } }
    @Test fun B05_unsupportedFence() = runReleaseTest<Unit> { gate(RecoveryReason.UninterpretableMetadata) { p, _ -> p[ReclamationFixtures.fenceKey] = "[{}]" } }
    @Test fun B06a_opaqueSeal() = runReleaseTest<Unit> { gate(RecoveryReason.UninterpretableObligations) { p, _ -> p[ControlStoreTestStorage.SEAL] = "[17]" } }
    @Test fun B06b_opaqueDemand() = runReleaseTest<Unit> { gate(RecoveryReason.UninterpretableObligations) { p, _ -> p[ControlStoreTestStorage.DEMAND] = "[17]" } }
    @Test fun B06c_opaqueHold() = runReleaseTest<Unit> { gate(RecoveryReason.UninterpretableObligations) { p, _ -> p[ControlStoreTestStorage.HOLD] = "[17]" } }
    @Test fun B06d_opaqueRecovery() = runReleaseTest<Unit> { gate(RecoveryReason.UninterpretableObligations) { p, _ -> p[ControlStoreTestStorage.RECOVERY] = "[17]" } }
    @Test fun B07a_duplicateInterpretedOwn() = runReleaseTest<Unit> { gate(RecoveryReason.UninterpretableMetadata) { p, own -> val s = ControlReleaseFixtures.wire(own); p[evidenceKey] = "[$s,$s]" } }
    @Test fun B07b_duplicateOpaqueOwn() = runReleaseTest<Unit> { gate(RecoveryReason.UninterpretableMetadata) { p, own -> val s = ControlReleaseFixtures.wire(own); p[evidenceKey] = "[$s,${s.replace("\"version\":2", "\"version\":3")}]" } }
    @Test fun B08_wrongLifetime() = runReleaseTest<Unit> { mismatch { it.copy(ownerTrackingLifetimeId = ReclamationFixtures.oldLife) } }
    @Test fun B09_wrongEvidenceKind() = runReleaseTest<Unit> { gate(ConflictReason.CommandEvidenceMismatch) { p, own -> p[evidenceKey] = "[${ReclamationFixtures.rotation(own.commandId, own.ownerTrackingLifetimeId)}]" } }
    @Test fun B10_wrongTargetCount() = runReleaseTest<Unit> { mismatch { it.copy(targets = it.targets.dropLast(1)) } }
    @Test fun B11_wrongTargetKind() = runReleaseTest<Unit> { mismatch { it.copy(targets = listOf(it.targets[0].copy(kind = ControlKind.HOLD), it.targets[1])) } }
    @Test fun B12_wrongTargetId() = runReleaseTest<Unit> { mismatch { it.copy(targets = listOf(it.targets[0].copy(id = "other"), it.targets[1])) } }
    @Test fun B13_wrongJoinedFlag() = runReleaseTest<Unit> { mismatch { it.copy(targets = listOf(it.targets[0], it.targets[1].copy(joined = true))) } }
    @Test fun B14_wrongWrittenFlag() = runReleaseTest<Unit> { mismatch { it.copy(targets = listOf(it.targets[0], it.targets[1].copy(written = true))) } }
    @Test fun B15a_noncontiguousIndex() = runReleaseTest<Unit> { opaqueRow { it.replace("\"index\":1", "\"index\":2") } }
    @Test fun B15b_duplicateTargetId() = runReleaseTest<Unit> { opaqueRow { it.replace("\"id\":\"r\"", "\"id\":\"d\"") } }
    @Test fun B15c_joinedAndWritten() = runReleaseTest<Unit> { opaqueRow { it.replace("\"joined\":false,\"written\":true", "\"joined\":true,\"written\":true") } }
    @Test fun B15d_allUnwritten() = runReleaseTest<Unit> { opaqueRow { it.replace("\"written\":true", "\"written\":false") } }
    @Test fun B16_observedOwnAbsenceIsLost() = runReleaseTest<Unit> { gate(RecoveryReason.CommandEvidenceLost) { p, _ -> p[evidenceKey] = "[]" } }
    @Test fun B17_unexpectedRowForConfirmedNoopIsNotAdopted() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]")
        val c = confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {}))
        val t = history(c); assertNull(t.expectedApplied); assertFalse(t.observedApplied.get())
        o.data.edit { it[evidenceKey] = "[${ControlReleaseFixtures.wire(ControlReleaseFixtures.row(c))}]" }
        val before = disk(); val result = o.control.releaseAfterConsumption(c)
        assertEquals(ConflictReason.CommandEvidenceMismatch, (result as ControlCommandReleaseResult.Conflict).reason)
        assertTrue(t.observedApplied.get()); assertNull(t.releaseDescriptor); assertEquals(before, disk())
    }
    @Test fun C20_ownObservationSurvivesOpaqueSiblingRefusal() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]")
        val c = confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {}))
        val t = history(c); assertFalse(t.observedApplied.get())
        o.data.edit { it[evidenceKey] = "[${ControlReleaseFixtures.wire(ControlReleaseFixtures.row(c))},17]" }
        val before = disk(); val result = o.control.releaseAfterConsumption(c)
        assertEquals(RecoveryReason.UninterpretableMetadata, (result as ControlCommandReleaseResult.RecoveryRequired).reason)
        assertTrue("actual own observation precedes whole-record gate", t.observedApplied.get())
        assertNull(t.releaseDescriptor); assertEquals(before, disk())
        o.data.edit { it[evidenceKey] = "[]" }
        assertEquals(RecoveryReason.CommandEvidenceLost, (o.control.releaseAfterConsumption(c) as ControlCommandReleaseResult.RecoveryRequired).reason)
    }
    private suspend fun discontinuity(kind: Int, restoreOwn: Boolean) {
        val c = command(); val normal = disk(); pending(c); val t = history(c); val fixed = t.releaseDescriptor; val first = t.firstConfirmDiscontinuityCount
        o.data.updateData { when (kind) {
            0 -> normal.toMutablePreferences().apply { this[ControlStoreTestStorage.SCHEMA] = 1; remove(evidenceKey); remove(ReclamationFixtures.fenceKey) }
            1 -> mutablePreferencesOf()
            else -> normal.toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[" }
        } }
        val result = o.control.releaseAfterConsumption(c)
        assertTrue(result is ControlCommandReleaseResult.RecoveryRequired); assertEquals(BigInteger.ONE, tracking.evidenceDiscontinuityCount)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState); assertSame(fixed, t.releaseDescriptor)
        o.data.updateData { normal.toMutablePreferences().apply { if (!restoreOwn) this[evidenceKey] = "[]" } }
        released(c, barrierAllowed = true); assertEquals(first, t.firstConfirmDiscontinuityCount)
        assertSame(fixed, t.releaseDescriptor); assertEquals(BigInteger.ONE, tracking.evidenceDiscontinuityCount)
    }
    @Test fun C08a_pendingSchemaOneThenAbsenceConfirms() = runReleaseTest<Unit> { discontinuity(0, false) }
    @Test fun C08b_pendingAbsentControlThenAbsenceConfirms() = runReleaseTest<Unit> { discontinuity(1, false) }
    @Test fun C08c_pendingUnreadableThenAbsenceConfirms() = runReleaseTest<Unit> { discontinuity(2, false) }
    @Test fun C09a_pendingSchemaOneThenExactRemoves() = runReleaseTest<Unit> { discontinuity(0, true) }
    @Test fun C09b_pendingAbsentControlThenExactRemoves() = runReleaseTest<Unit> { discontinuity(1, true) }
    @Test fun C09c_pendingUnreadableThenExactRemoves() = runReleaseTest<Unit> { discontinuity(2, true) }
    private suspend fun recoveredRefusal(kind: Int) {
        val c = command(); val normal = disk(); pending(c); val t = history(c); val fixed = t.releaseDescriptor
        o.data.updateData { mutablePreferencesOf() }
        assertTrue(o.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.RecoveryRequired)
        val row = ControlReleaseFixtures.wire(t.expectedApplied as AppliedEvidence.Mutations)
        o.data.updateData { normal.toMutablePreferences().apply { this[evidenceKey] = when (kind) {
            0 -> "[${row.replace(c.ownerTrackingLifetimeId.value, ReclamationFixtures.oldLife)}]"
            1 -> "[$row,$row]"
            else -> "[$row,17]"
        } } }
        val before = disk(); val result = o.control.releaseAfterConsumption(c)
        if (kind == 0) assertEquals(ConflictReason.CommandEvidenceMismatch, (result as ControlCommandReleaseResult.Conflict).reason)
        else assertEquals(RecoveryReason.UninterpretableMetadata, (result as ControlCommandReleaseResult.RecoveryRequired).reason)
        assertSame(fixed, t.releaseDescriptor); assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
        assertEquals(before, disk()); assertEquals(setOf(c), result.localPendingReleases); assertTrue(result.localUnresolvedCommands.isEmpty())
    }
    @Test fun C09d_recoveredMismatchStillRefuses() = runReleaseTest<Unit> { recoveredRefusal(0) }
    @Test fun C09e_recoveredDuplicateStillRefuses() = runReleaseTest<Unit> { recoveredRefusal(1) }
    @Test fun C09f_recoveredOpaqueStillRefuses() = runReleaseTest<Unit> { recoveredRefusal(2) }
    @Test fun C12_retainedNoopIgnoresOldBusinessBaseline() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]")
        val c = confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {})); val saved = disk()
        o.data.updateData { mutablePreferencesOf() }; assertTrue(o.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.RecoveryRequired)
        o.data.updateData { saved }; val t = history(c); released(c)
        assertEquals(BigInteger.ZERO, t.firstConfirmDiscontinuityCount); assertEquals(BigInteger.ONE, tracking.evidenceDiscontinuityCount)
    }
    @Test fun C13_releaseObservationBlocksOtherOldUnconfirmedWork() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); val b = add(); val normal = disk(); o.storage.before = true
        assertTrue(o.control.execute(b) is ControlStoreResult.Unconfirmed)
        o.data.updateData { mutablePreferencesOf() }; assertTrue(o.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.RecoveryRequired)
        o.data.updateData { normal }; released(c, barrierAllowed = true)
        assertEquals(RecoveryReason.CommandEvidenceContinuityLost, (o.control.execute(b) as ControlStoreResult.RecoveryRequired).reason)
        assertEquals(BigInteger.ZERO, history(b).firstConfirmDiscontinuityCount); assertEquals(setOf(b), tracking.snapshot())
    }
    @Test fun C14_previousLifetimeReclaimerPreservesCurrentPendingAndUnresolved() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); pending(c); val b = add(); o.storage.afterScope = true
        assertTrue(o.control.execute(b) is ControlStoreResult.Unconfirmed); val before = disk()
        assertTrue(o.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertNotNull(own(disk(), c)); assertNotNull(own(disk(), b)); assertEquals(rows(before), rows(disk()))
        assertEquals(setOf(c), tracking.recoverySnapshot().pendingReleases); assertEquals(setOf(b), tracking.snapshot())
    }
}
