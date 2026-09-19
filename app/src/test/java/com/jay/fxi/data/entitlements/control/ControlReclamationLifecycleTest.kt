package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SCHEMA
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.evidenceKey
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.fenceKey
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.mutation
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.raw
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.rotation
import java.io.File
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.buffer
import okio.source

/** Reopening always terminates the earlier DataStore scope; callers are joined before reopen. */
class ControlReclamationLifecycleTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "reclamation.preferences_pb") }
    private var opened: ControlStoreTestStorage? = null
    @After fun close() = runBlocking { opened?.close(); Unit }
    private suspend fun open(): ControlStoreTestStorage {
        opened?.close()
        return ControlStoreTestStorage(file).also { opened = it }
    }
    private fun tracker(o: ControlStoreTestStorage) = ControlCommandTracking.forOwner(o.owner)
    private suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private fun confirmed(result: ControlEvidenceReclamationResult): ControlEvidenceReclamationResult.Confirmed {
        assertTrue("$result", result is ControlEvidenceReclamationResult.Confirmed)
        return result as ControlEvidenceReclamationResult.Confirmed
    }
    private suspend fun refused(o: ControlStoreTestStorage, reason: RecoveryReason) {
        val before = o.raw(); val writes = o.storage.writes
        val result = o.control.reclaimPreviousLifetimeEvidence()
        assertTrue("$result", result is ControlEvidenceReclamationResult.RecoveryRequired)
        assertEquals(reason, (result as ControlEvidenceReclamationResult.RecoveryRequired).reason)
        assertEquals(before, result.observation.original)
        assertEquals(before, o.raw()); assertEquals(before, disk()); assertEquals(writes, o.storage.writes)
    }
    private fun add(o: ControlStoreTestStorage) = o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT) { id ->
        literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
    })
    private fun removed(source: Preferences) = source.toMutablePreferences().apply { this[SEAL] = "[]"; this[evidenceKey] = "[]" }

    @Test fun realRestartReclaimsLandedRotationAndPreviousConfirmationCannotReapply() = runBlocking {
        val o = open(); o.data.updateData { NamespaceSettlementFixtures.raw() }
        val c = o.control.prepareRotation(listOf(node(NamespaceSettlementFixtures.user)), NamespaceSettlementFixtures.fence,
            NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand)
        assertTrue(o.control.execute(c, NamespaceSettlementFixtures.context) is ControlStoreResult.Confirmed)
        val source = disk(); val next = open()
        assertNotEquals(c.ownerTrackingLifetimeId.value, tracker(next).lifetimeId.value)
        val result = confirmed(next.control.reclaimPreviousLifetimeEvidence())
        assertEquals(removed(source), result.snapshot.record.original); assertEquals(removed(source), disk())
        assertNull(tracker(next).findPrepared(c))
        NamespaceSettlementFixtures.negative(next.control.confirmPrevious(c), ConflictReason.TargetMissing)
        val retry = next.control.execute(c)
        assertTrue(retry is ControlStoreResult.Unconfirmed)
        assertEquals(UnconfirmedReason.HistoryUnavailable, (retry as ControlStoreResult.Unconfirmed).reason)
        assertEquals(removed(source), disk())
    }

    @Test fun realRestartReclaimsOrdinaryEvidenceButPreviousTargetStillConfirms() = runBlocking {
        val o = open(); o.seed(); val c = add(o)
        assertTrue(o.control.execute(c) is ControlStoreResult.Confirmed)
        val checkpoint = o.control.checkpoint(c)!!; val source = disk(); val next = open()
        confirmed(next.control.reclaimPreviousLifetimeEvidence())
        val expected = source.toMutablePreferences().apply { this[evidenceKey] = "[]" }
        assertEquals(expected, disk())
        val result = next.control.confirmPrevious(c, checkpoint)
        assertTrue(result is ControlStoreResult.Confirmed)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, (result as ControlStoreResult.Confirmed).effect)
        assertNull(tracker(next).findPrepared(c)); assertEquals(expected, disk())
    }

    @Test fun landedReturnFailureRemovesBothPayloadsAndSameOwnerRetryOnlyConfirmsAbsence() = runBlocking {
        val o = open(); val source = raw(); o.data.updateData { source }; o.storage.afterScope = true
        val result = o.control.reclaimPreviousLifetimeEvidence()
        assertTrue(result is ControlEvidenceReclamationResult.Unconfirmed)
        assertEquals(source, (result as ControlEvidenceReclamationResult.Unconfirmed).observation!!.original)
        assertEquals(removed(source), disk())
        val writes = o.storage.writes
        val retry = confirmed(o.control.reclaimPreviousLifetimeEvidence())
        val expected = removed(source).apply { this[BARRIER] = 1L }
        assertEquals(expected, retry.snapshot.record.original); assertEquals(expected, disk())
        assertEquals(writes + 1, o.storage.writes) // the owner confirms its outstanding read-back barrier
        confirmed(o.control.reclaimPreviousLifetimeEvidence())
        assertEquals(writes + 1, o.storage.writes); assertEquals(expected, disk())
    }

    @Test fun landedReturnFailureThenReopenOnlyConfirmsCompleteAbsence() = runBlocking {
        val o = open(); val source = raw(); o.data.updateData { source }; o.storage.afterScope = true
        assertTrue(o.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Unconfirmed)
        assertEquals(removed(source), disk())
        val next = open(); val before = next.raw(); val writes = next.storage.writes
        confirmed(next.control.reclaimPreviousLifetimeEvidence())
        assertEquals(before, disk()); assertEquals(writes, next.storage.writes)
        assertNotEquals(tracker(o).lifetimeId.value, tracker(next).lifetimeId.value)
    }

    @Test fun cancellationAfterLandingThenReopenKeepsTheAtomicRemoval() = runBlocking {
        val o = open(); val source = raw(); o.data.updateData { source }
        val pause = ControlStoreTestStorage.Pause(); o.storage.pauseAfterScope = pause
        val caller = async { o.control.reclaimPreviousLifetimeEvidence() }
        try {
            withTimeout(10_000) { pause.reached.await() }
            assertEquals(removed(source), disk())
            caller.cancelAndJoin(); assertTrue(caller.isCancelled)
            assertEquals(BigInteger.ZERO, tracker(o).evidenceDiscontinuityCount)
        } finally { pause.release.complete(Unit); caller.cancelAndJoin() }
        val next = open(); val writes = next.storage.writes
        confirmed(next.control.reclaimPreviousLifetimeEvidence())
        assertEquals(removed(source), disk()); assertEquals(writes, next.storage.writes)
    }

    @Test fun failureBeforeWritePreservesBothPayloadsAndRetryReclaimsTogether() = runBlocking {
        val o = open(); val source = raw(); o.data.updateData { source }; o.storage.before = true
        assertTrue(o.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Unconfirmed)
        assertEquals(source, disk())
        confirmed(o.control.reclaimPreviousLifetimeEvidence())
        assertEquals(removed(source).apply { this[BARRIER] = 1L }, disk())
    }

    @Test fun rollbackBeforeFileScopeCompletesPreservesBothPayloads() = runBlocking {
        val o = open(); val source = raw(); o.data.updateData { source }; o.storage.after = true
        assertTrue(o.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Unconfirmed)
        assertEquals(source, disk())
        val next = open(); assertEquals(source, next.raw())
        confirmed(next.control.reclaimPreviousLifetimeEvidence()); assertEquals(removed(source), disk())
    }

    @Test fun reopenedPartialSealAbsenceRequiresRecoveryWithoutDeletingOtherRows() = runBlocking {
        val o = open(); o.data.updateData { raw(evidence = "[${mutation()},${rotation(ids = listOf("s", "c"))}]") }
        val next = open()
        refused(next, RecoveryReason.InconsistentReclamation)
    }

    @Test fun reopenedAllSealsMissingButEvidencePresentRequiresRecovery() = runBlocking {
        val o = open(); o.data.updateData { raw(seals = "[]") }; val next = open()
        refused(next, RecoveryReason.InconsistentReclamation)
    }

    @Test fun reopenedEvidenceAbsentLeavesLegacySettledSealsUntouched() = runBlocking {
        val o = open(); val source = raw(evidence = "[]"); o.data.updateData { source }; val next = open()
        val writes = next.storage.writes; confirmed(next.control.reclaimPreviousLifetimeEvidence())
        assertEquals(source, disk()); assertEquals(writes, next.storage.writes)
    }

    @Test fun currentConfirmedRowSurvivesReclamationAndFacadeRecreation() = runBlocking {
        val o = open(); o.seed(); val c = add(o); assertTrue(o.control.execute(c) is ControlStoreResult.Confirmed)
        o.data.edit { it[evidenceKey] = it[evidenceKey]!!.dropLast(1) + ",${mutation()}]" }
        val current = tracker(o).findPrepared(c)!!; val before = o.raw(); val life = tracker(o).lifetimeId
        assertTrue(current.confirmed.get())
        val facade = ControlRecordStore(o.owner); facade.newLifetimeId()
        confirmed(facade.reclaimPreviousLifetimeEvidence())
        assertEquals(listOf(c.id), ReclamationFixtures.ids(o.raw()[evidenceKey]!!, "commandId"))
        assertEquals(before.asMap().filterKeys { it != evidenceKey }, o.raw().asMap().filterKeys { it != evidenceKey })
        assertSame(life, tracker(o).lifetimeId); assertTrue(tracker(o).snapshot().isEmpty())
        assertEquals(BigInteger.ZERO, current.firstConfirmDiscontinuityCount)
    }

    @Test fun currentUnconfirmedLandedRowAndUnresolvedMembershipSurviveReclamation() = runBlocking {
        val o = open(); o.seed(); val c = add(o); o.storage.afterScope = true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        val source = disk(); val tracked = tracker(o).findPrepared(c)!!
        assertFalse(tracked.confirmed.get()); assertEquals(setOf(c), tracker(o).snapshot())
        confirmed(o.control.reclaimPreviousLifetimeEvidence())
        assertEquals(source.toMutablePreferences().apply { this[BARRIER] = 1L }, disk())
        assertEquals(setOf(c), tracker(o).snapshot()); assertFalse(tracked.confirmed.get())
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
    }

    @Test fun currentRotationEvidenceAndItsSettledSealsSurvive() = runBlocking {
        val o = open(); o.data.updateData { NamespaceSettlementFixtures.raw() }
        val c = NamespaceSettlementFixtures.command(o, NamespaceSettlementFixtures.input())
        assertTrue(o.control.execute(c, NamespaceSettlementFixtures.context) is ControlStoreResult.Confirmed)
        val source = disk(); val writes = o.storage.writes
        confirmed(o.control.reclaimPreviousLifetimeEvidence())
        assertEquals(source, disk()); assertEquals(writes, o.storage.writes)
    }

    @Test fun latestSnapshotIsReReadAfterInterveningOwnerTransaction() = runBlocking {
        val o = open(); o.seed(); confirmed(o.control.reclaimPreviousLifetimeEvidence())
        val latest = raw(evidence = "[${rotation()},${mutation()}]").toMutablePreferences().apply {
            this[ControlStoreTestStorage.EXTRA] = "newer-external-value"
        }
        o.owner.transactRecord { RecordTransactionDecision.Confirm(latest, Unit) }
        val writes = o.storage.writes
        confirmed(o.control.reclaimPreviousLifetimeEvidence())
        assertEquals(removed(latest), disk()); assertEquals(writes + 1, o.storage.writes)
    }

    @Test fun everyNonTargetRecordKeyAndObligationIsPreservedOnDisk() = runBlocking {
        val o = open(); val source = raw().toMutablePreferences().apply {
            this[DataStoreAccessEpochStore.PURGE_JOURNAL] = "pending-original-journal"
            this[DataStoreAccessEpochStore.TEARDOWN_OWED_FOR] = "departed-owner"
            this[fenceKey] = " [ ] "
        }
        o.data.updateData { source }; val writes = o.storage.writes
        val result = confirmed(o.control.reclaimPreviousLifetimeEvidence())
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.proof.storage)
        assertEquals(removed(source), result.snapshot.record.original); assertEquals(removed(source), disk())
        assertEquals(writes + 1, o.storage.writes)
    }

    private suspend fun discontinuity(source: Preferences, reason: RecoveryReason) {
        val o = open(); o.data.updateData { source }; val c = add(o)
        val tracking = tracker(o); assertEquals(BigInteger.ZERO, tracking.evidenceDiscontinuityCount)
        refused(o, reason); assertEquals(BigInteger.ONE, tracking.evidenceDiscontinuityCount)
        refused(o, reason); assertEquals(BigInteger.valueOf(2), tracking.evidenceDiscontinuityCount)
        assertNull(tracking.findPrepared(c)!!.firstConfirmDiscontinuityCount)
        assertTrue(tracking.snapshot().isEmpty())
    }

    @Test fun schemaOneObservationCountsBeforeRefusal() = runBlocking { discontinuity(raw(schema = 1), RecoveryReason.ControlSchemaMigrationRequired) }
    @Test fun absentControlObservationCountsBeforeRefusal() = runBlocking {
        discontinuity(mutablePreferencesOf(ControlStoreTestStorage.EXTRA to "legacy"), RecoveryReason.MigrationOrRecovery)
    }
    @Test fun futureSchemaObservationCountsBeforeRefusal() = runBlocking {
        discontinuity(raw().toMutablePreferences().apply { this[SCHEMA] = 3 }, RecoveryReason.UnreadableRecord)
    }
    @Test fun malformedEnvelopeObservationCountsBeforeRefusal() = runBlocking {
        discontinuity(raw().toMutablePreferences().apply { this[DEMAND] = "broken" }, RecoveryReason.UnreadableRecord)
    }
    @Test fun missingMetadataEnvelopeObservationCountsBeforeRefusal() = runBlocking {
        discontinuity(raw().toMutablePreferences().apply { remove(evidenceKey) }, RecoveryReason.UnreadableRecord)
    }

    @Test fun supportedOpaqueRecordDoesNotCountAsEvidenceDiscontinuity() = runBlocking {
        val o = open(); o.data.updateData { raw().toMutablePreferences().apply { this[fenceKey] = "[null]" } }
        refused(o, RecoveryReason.UninterpretableMetadata)
        assertEquals(BigInteger.ZERO, tracker(o).evidenceDiscontinuityCount)
    }

    @Test fun readFailureWithoutSnapshotDoesNotIncrementCountOrConfirm() = runBlocking {
        val failure = IOException("snapshot unavailable")
        val data = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
        }
        val owner = DataStoreAccessEpochStore(data, EpochIdGenerator { "unused" })
        val result = ControlRecordStore(owner).reclaimPreviousLifetimeEvidence()
        assertTrue(result is ControlEvidenceReclamationResult.Unconfirmed)
        assertNull((result as ControlEvidenceReclamationResult.Unconfirmed).observation)
        assertSame(failure, result.failure)
        assertEquals(BigInteger.ZERO, ControlCommandTracking.forOwner(owner).evidenceDiscontinuityCount)
    }

    @Test fun reclamationObservationBlocksOldUnconfirmedCommandAfterRawRestoration() = runBlocking {
        val o = open(); o.seed(); val saved = o.raw(); val c = add(o); o.storage.before = true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        o.data.updateData { mutablePreferencesOf() }
        refused(o, RecoveryReason.MigrationOrRecovery)
        o.data.updateData { saved } // Defensive restored record, not a production bootstrap.
        NamespaceSettlementFixtures.negative(o.control.execute(c), RecoveryReason.CommandEvidenceContinuityLost)
        assertEquals(BigInteger.ZERO, tracker(o).findPrepared(c)!!.firstConfirmDiscontinuityCount)
        assertEquals(saved, disk())
    }

    @Test fun schemaTwoReclamationDoesNotInvalidateUnlandedBusinessRetry() = runBlocking {
        val o = open(); o.data.updateData { raw() }; val c = add(o); o.storage.before = true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        confirmed(o.control.reclaimPreviousLifetimeEvidence())
        assertEquals(BigInteger.ZERO, tracker(o).evidenceDiscontinuityCount)
        assertTrue(o.control.execute(c) is ControlStoreResult.Confirmed)
    }

    @Test fun reclaimedWitnessStopsReservingOnlyEpochsNoLongerRetainedAnywhere() = runBlocking {
        val o = open(); o.data.updateData { raw() }
        val input = NamespaceSettlementFixtures.input(u = "next-u", k = null)
        val transition = NamespaceSettlementFixtures.transition
        val before = ControlRecordReader().read(o.raw()) as ControlRecordRead.Supported
        assertFalse(transition.epochsAreUnused(input, before, emptyList()))
        val after = confirmed(o.control.reclaimPreviousLifetimeEvidence()).snapshot.record
        assertTrue(transition.epochsAreUnused(input, after, emptyList()))
    }
}
