package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.settledSeal
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SCHEMA
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.DEMAND
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.HOLD
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.RECOVERY
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.EXTRA
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import java.io.File
import java.math.BigInteger
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.buffer
import okio.source

/** Real file scopes and independent wire oracles; raw restoration is an injected defensive case. */
class ControlAppliedLifecycleTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "lifecycle.preferences_pb") }
    private var opened: ControlStoreTestStorage? = null
    @After fun close() = runBlocking { opened?.close(); Unit }
    private suspend fun open(): ControlStoreTestStorage {
        opened?.close()
        return ControlStoreTestStorage(file).also { opened = it }
    }
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val fenceKey = ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)
    private fun tracker(o: ControlStoreTestStorage) = ControlCommandTracking.forOwner(o.owner)
    private fun tracked(o: ControlStoreTestStorage, c: CommandRef) = tracker(o).findPrepared(c)!!
    private fun add(o: ControlStoreTestStorage) = o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT) { id ->
        literal(recovery); set("id", ControlScalar.Text(id))
    })
    private fun noop(o: ControlStoreTestStorage) = o.control.prepare(o.control.edit(ControlKind.DEMAND, node(request)) {})
    private fun floor(o: ControlStoreTestStorage) = o.control.prepare(o.control.recordFloor(node(emptyGuard),
        BootReading("boot", 50), 100, LifetimeId("life")))
    private fun confirmed(result: ControlStoreResult, effect: ConfirmedEffect = ConfirmedEffect.AppliedThisAttempt): ControlStoreResult.Confirmed {
        assertEquals("$result", ControlStoreResult.Confirmed::class.java, result.javaClass)
        return (result as ControlStoreResult.Confirmed).also { assertEquals(effect, it.effect) }
    }
    private suspend fun refused(o: ControlStoreTestStorage, c: CommandRef, reason: Any) {
        val before = o.raw(); val writes = o.storage.writes; val unresolved = tracker(o).snapshot()
        val result = o.control.execute(c)
        NamespaceSettlementFixtures.negative(result, reason)
        assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        assertEquals(unresolved, result.localUnresolvedCommands)
    }
    private fun row(c: CommandRef, targets: String) = """{"version":2,"commandId":"${c.id}","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[$targets]}"""
    private fun target(index: Int, kind: String, id: String, joined: Boolean = false, written: Boolean = true) =
        """{"index":$index,"kind":"$kind","id":"$id","joined":$joined,"written":$written}"""
    private suspend fun assertEvidence(o: ControlStoreTestStorage, expected: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(o.raw()[evidenceKey]!!))
        assertFalse((ControlRecordReader().read(o.raw()) as ControlRecordRead.Supported).hasUninterpretableMetadata)
    }
    private suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }

    @Test fun lifetimeIsIssuedOncePerOwnerAndRegistrationRejectsForeignLifetime() = runBlocking {
        val o = open(); o.seed()
        val t = tracker(o); val c = add(o)
        assertSame(t.lifetimeId, c.ownerTrackingLifetimeId)
        val id = UUID.fromString(t.lifetimeId.value)
        assertEquals(id.toString(), t.lifetimeId.value); assertEquals(4, id.version())
        val facade = ControlRecordStore(o.owner)
        facade.newLifetimeId(); confirmed(o.control.execute(c))
        assertTrue(t.snapshot().isEmpty())
        assertSame(t.lifetimeId, facade.prepare().ownerTrackingLifetimeId)
        val foreign = CommandRef("new-id", c.body, OwnerTrackingLifetimeId.issue())
        val failure = runCatching { t.registerPrepared(foreign) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertNull(t.findPrepared(foreign))
        val next = open()
        assertNotEquals(t.lifetimeId.value, tracker(next).lifetimeId.value)
        assertNull(tracker(next).findPrepared(c))
        assertEquals(UnconfirmedReason.HistoryUnavailable, (next.control.execute(c) as ControlStoreResult.Unconfirmed).reason)
    }

    @Test fun mixedActualChangesHaveOneExactAppliedRow() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]", demand = "[$request,$emptyGuard]")
        val addition = o.control.addition(ControlKind.RECOVERY_INTENT) { id -> literal(recovery); set("id", ControlScalar.Text(id)) }
        val c = o.control.prepare(o.control.addition(ControlKind.SEAL) { id -> literal(seal); set("id", ControlScalar.Text(id)) },
            addition, o.control.edit(ControlKind.DEMAND, node(request)) {},
            o.control.recordFloor(node(emptyGuard), BootReading("boot", 50), 100, LifetimeId("life")))
        val before = o.raw(); val writes = o.storage.writes
        confirmed(o.control.execute(c)); assertEquals(writes + 1, o.storage.writes)
        val id = (addition as ControlMutation.Add).proposedId
        assertEvidence(o, "[${row(c, listOf(target(0,"SEAL","s",true,false), target(1,"RECOVERY_INTENT",id),
            target(2,"DEMAND","d",false,false), target(3,"DEMAND","g")).joinToString(","))}]")
        assertEquals(before[SEAL], o.raw()[SEAL]); assertEquals(before[HOLD], o.raw()[HOLD])
        assertEquals(before[fenceKey], o.raw()[fenceKey]); assertEquals(o.raw(), disk())
        val saved = o.raw(); confirmed(o.control.execute(c), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(saved, o.raw())
    }

    @Test fun addAndChangedEditWriteExactEvidence() = runBlocking {
        val o = open(); o.seed(demand = "[$request]")
        val a = add(o); val addition = a.actions.single() as ControlMutation.Add
        confirmed(o.control.execute(a))
        val e = o.control.prepare(o.control.edit(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) })
        confirmed(o.control.execute(e))
        assertEvidence(o, "[${row(a,target(0,"RECOVERY_INTENT",addition.proposedId))},${row(e,target(0,"DEMAND","d"))}]")
    }

    @Test fun joinNoopConfirmationAndBarrierNeverAppendEvidence() = runBlocking {
        val o = open(); o.seed(seal = "[$seal]", demand = "[$request]")
        val c = o.control.prepare(o.control.addition(ControlKind.SEAL) { id -> literal(seal); set("id",ControlScalar.Text(id)) })
        confirmed(o.control.execute(c), ConfirmedEffect.JoinedExisting)
        val n = noop(o); confirmed(o.control.execute(n), ConfirmedEffect.PostconditionConfirmed)
        assertEvidence(o, "[]")
        // A failed independent owner write requires a barrier even though this command is a no-op.
        o.storage.before = true
        assertTrue(runCatching { o.owner.bindOwner("barrier-owner") }.isFailure)
        val writes = o.storage.writes
        confirmed(o.control.execute(n), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(writes + 1, o.storage.writes); assertNotNull(o.raw()[BARRIER]); assertEvidence(o,"[]")
        assertEquals(BigInteger.ZERO, tracked(o,n).firstConfirmDiscontinuityCount)
        assertEquals(BigInteger.ZERO, tracker(o).evidenceDiscontinuityCount)
    }

    @Test fun evidenceOverflowRejectsWholeGenericCandidate() = runBlocking {
        val o = open(); o.seed()
        val c = add(o); val id = (c.actions.single() as ControlMutation.Add).proposedId
        val text = "[${row(c,target(0,"RECOVERY_INTENT",id))}]"
        val limit = text.toByteArray().size
        val before = o.raw(); val writes = o.storage.writes
        val small = ControlRecordStore(o.owner, codec = ControlPayloadCodec(maxPayloadBytes = limit - 1))
        val result = small.execute(c)
        assertEquals(ControlStoreResult.Rejected::class.java,result.javaClass)
        val rejected = result as ControlStoreResult.Rejected
        assertEquals(RejectionReason.TooLarge(ControlPayloadKey.COMMAND_EVIDENCE,limit,limit-1), rejected.reason)
        assertEquals(before,o.raw()); assertEquals(writes,o.storage.writes)
        assertNull(tracked(o,c).firstConfirmDiscontinuityCount)
        confirmed(ControlRecordStore(o.owner,codec=ControlPayloadCodec(maxPayloadBytes=limit)).execute(c))
        assertEvidence(o,text)
    }

    @Test fun evidenceOverflowRejectsWholeRotationCandidate() = runBlocking {
        val o = open(); o.data.updateData { NamespaceSettlementFixtures.raw() }
        val c = NamespaceSettlementFixtures.command(o, NamespaceSettlementFixtures.input())
        val old = """{"version":2,"commandId":"old","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"DEMAND","id":"${"d".repeat(550)}","joined":false,"written":true}]}"""
        o.data.edit { it[evidenceKey] = "[$old]" }
        val before=o.raw(); val writes=o.storage.writes
        val result=ControlRecordStore(o.owner,codec=ControlPayloadCodec(maxPayloadBytes=900)).execute(c,NamespaceSettlementFixtures.context)
        assertTrue("$result",result is ControlStoreResult.Rejected)
        assertEquals(ControlPayloadKey.COMMAND_EVIDENCE,((result as ControlStoreResult.Rejected).reason as RejectionReason.TooLarge).payloadKey)
        assertEquals(before,o.raw());assertEquals(writes,o.storage.writes)
    }

    @Test fun unconfirmedLandedFloorABAUsesEvidenceBeforePreimage() = runBlocking {
        val before = """{"id":"g","kind":"SCHEDULE_GUARD","floor":{"anchorBootId":"old-boot","anchorElapsedMillis":50,"waitMillis":100,"originLifetimeId":"life"}}"""
        val o=open();o.seed(demand="[$before]")
        val c=o.control.prepare(o.control.recordFloor(node(before),BootReading("new-boot",50),100,LifetimeId("life")))
        o.storage.afterScope=true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        assertFalse(tracked(o,c).confirmed.get()); assertFalse(tracked(o,c).observedApplied.get())
        assertEquals(o.raw(),disk()); assertNotEquals("[$before]",o.raw()[DEMAND])
        val landed=(ControlRecordReader().read(o.raw()) as ControlRecordRead.Supported)
            .arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted
        // A second legitimate floor recapture recreates the first command's exact preimage.
        val restore=o.control.prepare(o.control.recordFloor(landed.original,BootReading("old-boot",50),100,LifetimeId("life")))
        confirmed(o.control.execute(restore))
        assertEquals(Json.parseToJsonElement("[$before]"),Json.parseToJsonElement(o.raw()[DEMAND]!!))
        refused(o,c,ConflictReason.TargetChanged)
        assertTrue(tracked(o,c).observedApplied.get())
    }

    @Test fun unconfirmedLandedAddRawRemovalNeverReapplies() = runBlocking {
        val o=open();o.seed();val c=add(o);o.storage.afterScope=true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        o.data.edit { it[RECOVERY]="[]" } // Defensive injection: no normal Add removal exists in this slice.
        refused(o,c,ConflictReason.TargetMissing)
    }

    @Test fun prewriteFailureBindsButDoesNotObserveAppliedAndRetriesWithoutDiscontinuity() = runBlocking {
        val o=open();o.seed();val c=add(o);val before=o.raw();o.storage.before=true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        assertEquals(before,o.raw());assertEquals(BigInteger.ZERO,tracked(o,c).firstConfirmDiscontinuityCount)
        assertFalse(tracked(o,c).observedApplied.get());assertEquals(BigInteger.ZERO,tracker(o).evidenceDiscontinuityCount)
        confirmed(o.control.execute(c));assertTrue(tracked(o,c).observedApplied.get())
    }

    @Test fun appliedSurvivesFileReopenAndPreviousConfirmationUsesPreparedLifetime() = runBlocking {
        val o=open();o.seed();val c=add(o);o.storage.afterScope=true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        val checkpoint=o.control.checkpoint(c)!!;val saved=disk();val next=open()
        assertEquals(saved,next.raw());assertNotEquals(c.ownerTrackingLifetimeId.value,tracker(next).lifetimeId.value)
        confirmed(next.control.confirmPrevious(c,checkpoint),ConfirmedEffect.PostconditionConfirmed)
        assertNull(tracker(next).findPrepared(c));assertEquals(saved,next.raw())
    }

    @Test fun observedAppliedLossWinsOverConfirmedAndDiscontinuity() = runBlocking {
        val o=open();o.seed();val c=add(o);confirmed(o.control.execute(c))
        o.seed(schema=1);o.control.upgradeControlSchemaV1ToV2()
        refused(o,c,RecoveryReason.CommandEvidenceLost)
    }

    @Test fun observedUnconfirmedAppliedLossWinsOverDiscontinuity() = runBlocking {
        val o=open();o.seed();val c=add(o);o.storage.afterScope=true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        o.data.edit { it[RECOVERY]="[]" };refused(o,c,ConflictReason.TargetMissing)
        assertTrue(tracked(o,c).observedApplied.get());assertFalse(tracked(o,c).confirmed.get())
        o.seed(schema=1);o.control.upgradeControlSchemaV1ToV2()
        refused(o,c,RecoveryReason.CommandEvidenceLost)
    }

    @Test fun matchedAppliedWinsOverDiscontinuityAndDoesNotResetBaseline() = runBlocking {
        val o=open();o.seed();val c=add(o);o.storage.afterScope=true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        val saved=o.raw();o.seed(schema=1)
        o.control.execute(o.control.prepare()) // actual schema 1 observation, before restoring the whole v2 snapshot
        o.data.updateData { saved }
        assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
        confirmed(o.control.execute(c),ConfirmedEffect.PostconditionConfirmed)
        assertEquals(BigInteger.ZERO,tracked(o,c).firstConfirmDiscontinuityCount)
    }

    @Test fun confirmedNoEvidenceConfirmsAfterDiscontinuityAndNeverReapplies() = runBlocking {
        val o=open();o.seed(demand="[$request]");val c=noop(o)
        confirmed(o.control.execute(c),ConfirmedEffect.PostconditionConfirmed)
        o.seed(demand="[$request]",schema=1);o.control.upgradeControlSchemaV1ToV2()
        confirmed(o.control.execute(c),ConfirmedEffect.PostconditionConfirmed)
        assertEquals(BigInteger.ZERO,tracked(o,c).firstConfirmDiscontinuityCount);assertEvidence(o,"[]")
        o.data.edit { it[DEMAND]="[]" };refused(o,c,ConflictReason.TargetMissing)
    }

    @Test fun schemaOneStillAllowsNoopJoinAndPreviousWitnessConfirmation() = runBlocking {
        val o=open();o.seed(seal="[$seal]",demand="[$request]",schema=1)
        confirmed(o.control.execute(noop(o)),ConfirmedEffect.PostconditionConfirmed)
        assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
        val c=o.control.prepare(o.control.addition(ControlKind.SEAL){id->literal(seal);set("id",ControlScalar.Text(id))})
        confirmed(o.control.execute(c),ConfirmedEffect.JoinedExisting)
        val checkpoint=o.control.checkpoint(c)!!
        confirmed(o.control.confirmPrevious(c,checkpoint),ConfirmedEffect.PostconditionConfirmed)
        assertNull(o.raw()[evidenceKey]);assertEquals(1,o.raw()[SCHEMA])
        val spec=NamespaceSettlementFixtures.input()
        val source=NamespaceSettlementFixtures.settled(spec).toMutablePreferences().apply {this[SCHEMA]=1;remove(evidenceKey);remove(fenceKey)}
        o.data.updateData {source}; val r=NamespaceSettlementFixtures.command(o,spec)
        confirmed(o.control.confirmPrevious(r),ConfirmedEffect.PostconditionConfirmed)
        assertEquals(source,o.raw())
    }

    @Test fun restorationAllowsFirstAttemptsButBlocksOldUnconfirmedCommand() = runBlocking {
        val o=open();o.seed();val old=add(o);val prepared=add(o)
        o.storage.before=true;assertTrue(o.control.execute(old) is ControlStoreResult.Unconfirmed)
        o.seed(schema=1)
        assertTrue(o.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Confirmed)
        assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
        refused(o,old,RecoveryReason.CommandEvidenceContinuityLost)
        assertEquals(BigInteger.ZERO,tracked(o,old).firstConfirmDiscontinuityCount)
        confirmed(o.control.execute(prepared));confirmed(o.control.execute(add(o)))
        assertEquals(BigInteger.ONE,tracked(o,prepared).firstConfirmDiscontinuityCount)
        assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
        ControlRecordStore(o.owner).newLifetimeId()
        assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
        refused(o,old,RecoveryReason.CommandEvidenceContinuityLost)
    }

    @Test fun cancelledRestorationCountsBeforeReturnAndKeepsOldCommandBlocked() = runBlocking {
        val o=open();o.seed();val old=add(o);o.storage.before=true
        assertTrue(o.control.execute(old) is ControlStoreResult.Unconfirmed);o.seed(schema=1)
        val pause=ControlStoreTestStorage.Pause();o.storage.pauseAfterScope=pause
        val job=async {o.control.upgradeControlSchemaV1ToV2()}
        withTimeout(5000){pause.reached.await()}
        try {
            assertEquals(2,disk()[SCHEMA]);assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
            job.cancelAndJoin()
            assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
        } finally {pause.release.complete(Unit);job.cancelAndJoin()}
        assertTrue(o.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Confirmed)
        refused(o,old,RecoveryReason.CommandEvidenceContinuityLost)
        confirmed(o.control.execute(add(o))); Unit
    }

    @Test fun cancelledFirstBusinessConfirmAlreadyHasBaselineButNoAppliedObservation() = runBlocking {
        val o=open();o.seed();val c=add(o);val pause=ControlStoreTestStorage.Pause();o.storage.pauseAfterScope=pause
        val job=async {o.control.execute(c)};withTimeout(5000){pause.reached.await()}
        try {
            assertEquals(BigInteger.ZERO,tracked(o,c).firstConfirmDiscontinuityCount)
            assertFalse(tracked(o,c).observedApplied.get());assertFalse(tracked(o,c).confirmed.get())
            assertNotEquals("[]",disk()[evidenceKey]);job.cancelAndJoin()
        } finally {pause.release.complete(Unit);job.cancelAndJoin()}
        o.seed(schema=1);o.control.upgradeControlSchemaV1ToV2()
        refused(o,c,RecoveryReason.CommandEvidenceContinuityLost)
    }

    @Test fun unsupportedSnapshotKindsIncrementOnEveryObservationBeforeNegativeReturn() = runBlocking {
        val o=open();o.seed();val c=add(o)
        suspend fun observeNegative(expectedCount: Long) {
            assertTrue(o.control.execute(c) is ControlStoreResult.RecoveryRequired)
            assertEquals(BigInteger.valueOf(expectedCount),tracker(o).evidenceDiscontinuityCount)
            assertNull(tracked(o,c).firstConfirmDiscontinuityCount)
        }
        // One ordered recovery history on the same tracker. Per-kind independent cases live in
        // ControlDiscontinuityObservationTest, where JUnit constructs each input separately.
        o.seed(schema=1); observeNegative(1)
        o.data.updateData {androidx.datastore.preferences.core.emptyPreferences()}; observeNegative(2)
        o.seed(schema=3); observeNegative(3)
        o.seed(schema=1); observeNegative(4)
        o.control.upgradeControlSchemaV1ToV2()
        assertEquals(BigInteger.valueOf(5),tracker(o).evidenceDiscontinuityCount)
        confirmed(o.control.execute(c));assertEquals(BigInteger.valueOf(5),tracked(o,c).firstConfirmDiscontinuityCount)
    }

    @Test fun ioBeforeSnapshotDoesNotCountOrBind() = runBlocking {
        val o=open();o.seed();file.writeBytes(byteArrayOf(0x0f));o.storage.after=true
        val c=add(o);val failed=o.control.execute(c) as ControlStoreResult.Unconfirmed
        assertNull(failed.lastObservation);assertEquals(BigInteger.ZERO,tracker(o).evidenceDiscontinuityCount)
        assertNull(tracked(o,c).firstConfirmDiscontinuityCount)
    }

    @Test fun refusedCheckpointStillRetainsActualAppliedObservation() = runBlocking {
        val o=open();o.seed();val c=add(o);o.storage.afterScope=true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        val invalid=ControlCommandCheckpoint(c,emptyList(),confirmationRequested=true)
        val result=o.control.confirmPrevious(c,invalid)
        assertTrue(result is ControlStoreResult.Unconfirmed)
        assertEquals(UnconfirmedReason.HistoryUnavailable,(result as ControlStoreResult.Unconfirmed).reason)
        assertTrue(tracked(o,c).observedApplied.get());assertFalse(tracked(o,c).confirmed.get())
        o.data.edit {it[evidenceKey]="[]";it[RECOVERY]="[]"}
        refused(o,c,RecoveryReason.CommandEvidenceLost)
    }

    @Test fun cancelledObservationRetainsAppliedMarkBeforeReturn() = runBlocking {
        val o=open();o.seed();val c=add(o);o.storage.afterScope=true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        val pause=ControlStoreTestStorage.Pause();o.storage.pauseAfterScope=pause
        val job=async {o.control.execute(c)};withTimeout(5000){pause.reached.await()}
        try {
            assertTrue(tracked(o,c).observedApplied.get());assertFalse(tracked(o,c).confirmed.get())
            job.cancelAndJoin()
        } finally {pause.release.complete(Unit);job.cancelAndJoin()}
        o.data.edit {it[evidenceKey]="[]"}
        refused(o,c,RecoveryReason.CommandEvidenceLost)
    }

    @Test fun migrationReadsTheLatestSnapshotAfterWaitingForOwner() = runBlocking {
        val o=open();o.seed(schema=1)
        val pause=ControlStoreTestStorage.Pause();o.storage.pauseAfterScope=pause
        val writer=async {
            o.owner.transactRecord {snapshot ->
                com.jay.fxi.data.entitlements.RecordTransactionDecision.Confirm(
                    snapshot.toMutablePreferences().apply {this[EXTRA]="latest under owner lock"},Unit)
            }
        }
        withTimeout(5000){pause.reached.await()}
        val upgrade=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED){o.control.upgradeControlSchemaV1ToV2()}
        assertFalse(upgrade.isCompleted)
        pause.release.complete(Unit);writer.await()
        val result=upgrade.await() as ControlSchemaUpgradeResult.Confirmed
        assertEquals("latest under owner lock",result.snapshot.record.original[EXTRA])
        assertEquals(2,result.snapshot.record.schemaVersion)
    }

    @Test fun migrationDoesNotReuseEarlierSnapshotAfterAnotherWriter() = runBlocking {
        val o = open()
        o.seed(schema = 1)
        o.data.edit { it[EXTRA] = "earlier committed snapshot" }
        val first = o.control.upgradeControlSchemaV1ToV2()
        assertTrue(first is ControlSchemaUpgradeResult.Confirmed)
        assertEquals("earlier committed snapshot", (first as ControlSchemaUpgradeResult.Confirmed).snapshot.record.original[EXTRA])

        // A distinct completed owner transaction changes the file between two management reads.
        o.owner.transactRecord { snapshot ->
            com.jay.fxi.data.entitlements.RecordTransactionDecision.Confirm(
                snapshot.toMutablePreferences().apply { this[EXTRA] = "newer committed snapshot" }, Unit)
        }
        val latest = o.raw()
        assertEquals("newer committed snapshot", latest[EXTRA])
        assertEquals(latest, disk())
        val writes = o.storage.writes
        val second = o.control.upgradeControlSchemaV1ToV2()
        assertTrue(second is ControlSchemaUpgradeResult.Confirmed)
        // A stale-snapshot mutant returns the OLD STRING here, not a missing key.
        assertEquals("newer committed snapshot", (second as ControlSchemaUpgradeResult.Confirmed).snapshot.record.original[EXTRA])
        assertEquals(latest, second.snapshot.record.original)
        assertEquals(latest, o.raw()); assertEquals(latest, disk())
        assertEquals(writes, o.storage.writes)
    }

    @Test fun migrationPreservesEveryRawValueAndHasNoApplied() = runBlocking {
        val o=open();o.seed(seal="[  $settledSeal  ]",demand="[ $request ]",hold="[null]",schema=1)
        o.data.edit {it[EXTRA]="원문\n보존";it[DataStoreAccessEpochStore.PURGE_JOURNAL]="opaque legacy";it[DataStoreAccessEpochStore.TEARDOWN_OWED_FOR]="A"}
        val before=o.raw();val writes=o.storage.writes
        val expected=before.toMutablePreferences().apply {this[SCHEMA]=2;this[evidenceKey]="[]";this[fenceKey]="[]"}
        val result=o.control.upgradeControlSchemaV1ToV2() as ControlSchemaUpgradeResult.Confirmed
        assertEquals(expected,result.snapshot.record.original);assertEquals(expected,disk());assertEquals(writes+1,o.storage.writes)
        assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
        val next=open();assertEquals(expected,next.raw());assertTrue(next.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Confirmed)
        assertEquals(BigInteger.ZERO,tracker(next).evidenceDiscontinuityCount)
    }

    @Test fun migrationRetryPreservesNonemptyMetadataVerbatim() = runBlocking {
        val o=open();o.seed();confirmed(o.control.execute(add(o)))
        o.data.edit {it[evidenceKey]="  ${it[evidenceKey]}  ";it[fenceKey]="[ {\"future\":1} ]"}
        val before=o.raw();val writes=o.storage.writes
        assertTrue(o.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Confirmed)
        assertEquals(before,o.raw());assertEquals(writes,o.storage.writes);assertEquals(BigInteger.ZERO,tracker(o).evidenceDiscontinuityCount)
    }

    @Test fun migrationDoesNotBootstrapAbsentRecord() = assertMigrationDoesNotBootstrap("absent")
    @Test fun migrationDoesNotBootstrapFutureRecord() = assertMigrationDoesNotBootstrap("future")
    @Test fun migrationDoesNotBootstrapMissingPayload() = assertMigrationDoesNotBootstrap("payload-missing")
    @Test fun migrationDoesNotBootstrapMalformedPayload() = assertMigrationDoesNotBootstrap("malformed")
    @Test fun migrationDoesNotBootstrapMissingSchema() = assertMigrationDoesNotBootstrap("schema-missing")

    private fun assertMigrationDoesNotBootstrap(variant: String) = runBlocking {
        val o=open()
        o.seed(schema=1)
        o.data.edit {when(variant){
            "absent"->it.clear();"future"->it[SCHEMA]=3;"payload-missing"->it.remove(HOLD)
            "malformed"->it[DEMAND]="broken";else->it.remove(SCHEMA)
        }}
        val before=o.raw();val writes=o.storage.writes
        val observed=o.control.upgradeControlSchemaV1ToV2()
        assertEquals(variant,ControlSchemaUpgradeResult.RecoveryRequired::class.java,observed.javaClass)
        val result=observed as ControlSchemaUpgradeResult.RecoveryRequired
        assertEquals(if(variant=="absent") RecoveryReason.MigrationOrRecovery else RecoveryReason.UnreadableRecord,result.reason)
        assertEquals(before,result.observation.original);assertEquals(before,o.raw());assertEquals(writes,o.storage.writes)
    }

    @Test fun migrationLandedFailureIsUnconfirmedUntilNextLockedRead() = runBlocking {
        val o=open();o.seed(schema=1);o.storage.afterScope=true
        assertTrue(o.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Unconfirmed)
        assertEquals(2,disk()[SCHEMA]);assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount)
        assertTrue(o.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Confirmed)
        assertEquals(BigInteger.ONE,tracker(o).evidenceDiscontinuityCount);assertEvidence(o,"[]")
    }
}
