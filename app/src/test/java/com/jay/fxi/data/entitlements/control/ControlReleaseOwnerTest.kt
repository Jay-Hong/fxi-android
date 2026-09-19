package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import java.math.BigInteger
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ControlReleaseOwnerTest : ReleaseOwnerTestBase() {
    @Test fun B18a_addRemovesOnlyOwnRowAndStrongHistory() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); val other = confirmed()
        val result = released(c)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.proof.storage)
        assertNotNull(history(other)); assertNotNull(own(disk(), other))
    }
    @Test fun B18b_changedEditReleases() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]")
        released(confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {
            set("raisedAt", ControlScalar.Integer(8))
        })))
    }
    @Test fun B18c_floorChangeReleasesWithoutRevertingFloor() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.emptyGuard}]")
        released(confirmed(o.control.prepare(o.control.recordFloor(node(ControlObligationFixtures.emptyGuard),
            BootReading("boot", 100), 1000, LifetimeId("origin")))))
    }
    @Test fun B18d_mixedBatchRemovesOneEvidenceRow() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]")
        val c = o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {},
            o.control.addition(ControlKind.RECOVERY_INTENT) { id -> literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
        confirmed(c); assertEquals(2, (own(disk(), c) as AppliedEvidence.Mutations).targets.size); released(c)
    }
    @Test fun B19a_confirmedJoinUsesAbsenceConfirmation() = runReleaseTest<Unit> {
        o.seed(seal = "[${ControlObligationFixtures.seal}]")
        val c = o.control.prepare(o.control.addition(ControlKind.SEAL) { id -> literal(ControlObligationFixtures.seal); set("id", ControlScalar.Text(id)) })
        val business = o.control.execute(c) as ControlStoreResult.Confirmed
        assertEquals(ConfirmedEffect.JoinedExisting, business.effect); assertNull(history(c).expectedApplied)
        val writes = o.storage.writes; val before = disk()
        assertEquals(RecordTransactionEvidence.LockedFileRead, released(c).proof.storage)
        assertEquals(writes, o.storage.writes); assertEquals(before, disk())
    }
    @Test fun B19b_noopEditUsesAbsenceConfirmation() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]")
        val c = confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {}))
        assertFalse(history(c).observedApplied.get()); val writes = o.storage.writes
        released(c); assertEquals(writes, o.storage.writes)
    }
    @Test fun B19c_noEvidenceKeepsOriginalEvidenceText() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]")
        val c = confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {}))
        assertNull(history(c).expectedApplied); assertFalse(history(c).observedApplied.get())
        o.data.edit { it[evidenceKey] = " [ ] " }
        val before = disk(); val fileBefore = file.readBytes(); val writes = o.storage.writes
        val result = o.control.releaseAfterConsumption(c)
        assertTrue(result is ControlCommandReleaseResult.Released)
        result as ControlCommandReleaseResult.Released
        assertEquals(" [ ] ", result.snapshot.record.original[evidenceKey])
        assertEquals(" [ ] ", disk()[evidenceKey]); assertEquals(before, disk())
        assertArrayEquals("entire stored file remains verbatim", fileBefore, file.readBytes())
        assertEquals("absence confirmation must not rewrite evidence", writes, o.storage.writes)
        assertEquals(RecordTransactionEvidence.LockedFileRead, result.proof.storage)
        assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState); assertNull(tracking.findPrepared(c))
        assertTrue(result.localPendingReleases.isEmpty()); assertTrue(result.localUnresolvedCommands.isEmpty())
        assertTrue(tracking.recoverySnapshot().pendingReleases.isEmpty()); assertTrue(tracking.recoverySnapshot().unresolvedCommands.isEmpty())
    }
    @Test fun B20_laterBusinessChangeDoesNotBlockOldRelease() = runReleaseTest<Unit> {
        o.seed(demand = "[${ControlObligationFixtures.request}]")
        val first = confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) { set("raisedAt", ControlScalar.Integer(7)) }))
        val latest = (read(disk()).arrays.getValue(ControlKind.DEMAND).entries.single() as ControlEntryRead.Interpreted).original
        val second = confirmed(o.control.prepare(o.control.edit(ControlKind.DEMAND, latest) { set("raisedAt", ControlScalar.Integer(9)) }))
        released(first); assertNotNull(own(disk(), second))
    }
    @Test fun B20_removedBusinessTargetDoesNotBlockRelease() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed()
        o.data.edit { it[ControlStoreTestStorage.RECOVERY] = "[]" } // Defensive model of a later lawful obligation removal.
        released(c)
    }
    @Test fun B21_rotationAndSettledAndUnsettledSealsStayUntouched() = runReleaseTest<Unit> {
        o.data.updateData { NamespaceSettlementFixtures.raw(seals = "[${NamespaceSettlementFixtures.user},${NamespaceSettlementFixtures.krx}]") }
        val rotation = NamespaceSettlementFixtures.command(o, NamespaceSettlementFixtures.input())
        assertTrue(o.control.execute(rotation, NamespaceSettlementFixtures.context) is ControlStoreResult.Confirmed)
        val seals = read(disk()).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).value as SealV1 }
        assertEquals(1, seals.count { it.settlement != null }); assertEquals(1, seals.count { it.settlement == null })
        val c = confirmed(); released(c); assertNotNull(own(disk(), rotation))
    }
    @Test fun B22_allRawNonEvidenceKeysAndWhitespaceStayUntouched() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed()
        o.data.edit {
            it[DataStoreAccessEpochStore.PURGE_JOURNAL] = "opaque retained journal"
            it[DataStoreAccessEpochStore.TEARDOWN_OWED_FOR] = "owner"
            it[DataStoreAccessEpochStore.USER_EPOCH] = "epoch"
            it[DataStoreAccessEpochStore.MAY_CONTAIN_PREMIUM] = true
            it[ControlStoreTestStorage.EXTRA] = "한글 quote\"\\"
            it[ControlStoreTestStorage.HOLD] = " [ ] "
            it[ReclamationFixtures.fenceKey] = " [ ] "
        }
        released(c)
    }
    @Test fun A02_confirmedThenBadCheckpointIsUnresolvedFirst() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed()
        assertEquals(UnconfirmedReason.HistoryUnavailable, (o.control.confirmPrevious(c) as ControlStoreResult.Unconfirmed).reason)
        rejected(c, ReleaseRejectionReason.Unresolved)
    }
    @Test fun A03_preparedOnlyIsNotConfirmed() = runReleaseTest<Unit> { o.seed(); rejected(add(), ReleaseRejectionReason.NotConfirmed) }
    @Test fun A06_unregisteredSameIdBodyLifetimeCloneIsRejected() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); rejected(CommandRef(c.id, c.body, c.ownerTrackingLifetimeId), ReleaseRejectionReason.NotRegisteredIdentity)
    }
    @Test fun A07_otherRealTrackerIsRejected() = runReleaseTest<Unit> {
        o.seed(); val next = open(); val c = add(next.control); rejected(c, ReleaseRejectionReason.WrongTrackerLifetime)
    }
    @Test fun A08_sameLifetimeTextDifferentObjectIsRejected() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed()
        rejected(CommandRef(c.id, c.body, ControlReleaseFixtures.sameLifetimeText(c.ownerTrackingLifetimeId)), ReleaseRejectionReason.WrongTrackerLifetime)
    }
    @Test fun A09_confirmedRotationIsRejectedWithoutPruning() = runReleaseTest<Unit> {
        o.data.updateData { NamespaceSettlementFixtures.raw() }
        val c = NamespaceSettlementFixtures.command(o, NamespaceSettlementFixtures.input())
        assertTrue(o.control.execute(c, NamespaceSettlementFixtures.context) is ControlStoreResult.Confirmed)
        rejected(c, ReleaseRejectionReason.UnsupportedCommandKind)
    }
    @Test fun A08_closedForeignLifetimeObjectWithSameTextIsStillRejected() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed()
        val foreign = CommandRef(c.id, c.body, ControlReleaseFixtures.sameLifetimeText(c.ownerTrackingLifetimeId))
        ControlReleaseFixtures.pending(foreign); ControlReleaseFixtures.released(foreign)
        rejected(foreign, ReleaseRejectionReason.WrongTrackerLifetime)
    }
    @Test fun A10_failedConfirmationRequestIsNotConfirmation() = runReleaseTest<Unit> {
        o.seed(); val c = add(); o.storage.before = true
        assertTrue(o.control.execute(c) is ControlStoreResult.Unconfirmed)
        assertTrue(history(c).confirmationRequested.get()); assertFalse(history(c).confirmed.get())
        rejected(c, ReleaseRejectionReason.Unresolved)
    }
    @Test fun A10_rejectionOnlyIsNotConfirmed() = runReleaseTest<Unit> {
        o.seed(); val c = o.control.prepare(); assertTrue(o.control.execute(c) is ControlStoreResult.Rejected)
        rejected(c, ReleaseRejectionReason.NotConfirmed)
    }
    @Test fun A13_alreadyReleasedDoesNotReadOrTouchSuccessor() = runReleaseTest<Unit> {
        o.seed(); val fixed = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, 191) })
        val c = confirmed(fixed.prepare(o.control.addition(ControlKind.RECOVERY_INTENT) { id -> literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) }))
        val checkpoint = checkNotNull(o.control.checkpoint(c)); released(c)
        val b = fixed.prepare(o.control.addition(ControlKind.RECOVERY_INTENT) { id -> literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
        confirmed(b); val t = history(b); val before = disk(); val writes = o.storage.writes
        assertTrue(o.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.AlreadyReleased)
        assertTrue(o.control.execute(c) is ControlStoreResult.Released)
        assertTrue(o.control.execute(c, NamespaceSettlementFixtures.context) is ControlStoreResult.Released)
        assertTrue(o.control.confirmPrevious(c, checkpoint) is ControlStoreResult.Released)
        assertNull(o.control.checkpoint(c)); assertTrue(runCatching { tracking.registerPrepared(c) }.isFailure)
        assertSame(t, history(b)); assertNotNull(own(disk(), b)); assertEquals(before, disk()); assertEquals(writes, o.storage.writes)
    }
    @Test fun A16_businessSuccessPreservesOtherPending() = runReleaseTest<Unit> {
        o.seed(); val a = confirmed(); pending(a); val b = add(); o.storage.before = true
        assertTrue(o.control.execute(b) is ControlStoreResult.Unconfirmed)
        val result = o.control.execute(b) as ControlStoreResult.Confirmed
        assertEquals(setOf(a), result.localPendingReleases); assertTrue(result.localUnresolvedCommands.isEmpty())
        assertEquals(setOf(a), tracking.recoverySnapshot().pendingReleases)
    }
    @Test fun M01_releasePreservesOtherUnresolvedAndPendingAndPrepared() = runReleaseTest<Unit> {
        o.seed(); val a = confirmed(); pending(a); val c = confirmed(); val b = add(); o.storage.before = true
        assertTrue(o.control.execute(b) is ControlStoreResult.Unconfirmed); val idle = add()
        val result = released(c, barrierAllowed = true)
        assertEquals(setOf(a), result.localPendingReleases); assertEquals(setOf(b), result.localUnresolvedCommands)
        assertEquals(setOf(a, b, idle), ControlReleaseFixtures.commands(tracking).values.map { it.command }.toSet())
    }
    @Test fun C10_facadeRecreationUsesSameTrackerForCompletion() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); pending(c); val descriptor = history(c).releaseDescriptor
        val store = ControlRecordStore(o.owner)
        assertTrue(store.releaseAfterConsumption(c) is ControlCommandReleaseResult.Released)
        assertEquals(BigInteger.ZERO, tracking.evidenceDiscontinuityCount); assertNotNull(descriptor); assertNull(tracking.findPrepared(c))
    }
}
