package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlReleaseRecoveryWorkTest {
    @get:Rule val folder = TemporaryFolder()
    private val o by lazy { ControlStoreTestStorage(File(folder.root, "work.preferences_pb")) }
    private val tracker get() = ControlCommandTracking.forOwner(o.owner)
    @After fun close() = runBlocking { o.close() }
    private fun action() = o.control.addition(ControlKind.RECOVERY_INTENT) { id ->
        literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
    }
    private suspend fun setup(): Pair<CommandRef, CommandRef> {
        o.seed()
        val a = o.control.prepare()
        ControlReleaseFixtures.simulatePending(tracker, a)
        val b = o.control.prepare(action()); o.storage.before = true
        val failed = o.control.execute(b)
        assertTrue(failed is ControlStoreResult.Unconfirmed)
        assertEquals(setOf(b), failed.localUnresolvedCommands)
        assertEquals(setOf(a), failed.localPendingReleases)
        return a to b
    }
    private fun memberships(result: ControlStoreResult, pending: CommandRef, unresolved: CommandRef) {
        assertEquals("actual unresolved snapshot", setOf(unresolved), result.localUnresolvedCommands)
        assertEquals("actual pending snapshot", setOf(pending), result.localPendingReleases)
        assertEquals(setOf(unresolved), tracker.snapshot())
        assertEquals(setOf(pending), tracker.recoverySnapshot().pendingReleases)
    }
    @Test fun A17_confirmedCarriesPendingAndResolvesOnlyOwnBusinessWork() = runBlocking {
        val (a, b) = setup()
        val result = o.control.execute(b)
        assertTrue(result is ControlStoreResult.Confirmed)
        assertTrue(result.localUnresolvedCommands.isEmpty())
        assertEquals("business success preserves release work", setOf(a), result.localPendingReleases)
        assertTrue(tracker.snapshot().isEmpty()); assertEquals(setOf(a), tracker.recoverySnapshot().pendingReleases)
    }
    @Test fun A17_rejectedCarriesBothSets() = runBlocking {
        val (a, b) = setup(); val result = o.control.execute(o.control.prepare())
        assertTrue(result is ControlStoreResult.Rejected); memberships(result, a, b)
    }
    @Test fun A17_conflictCarriesBothSets() = runBlocking {
        val (a, b) = setup()
        val c = o.control.prepare(o.control.edit(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {})
        val result = o.control.execute(c)
        assertTrue(result is ControlStoreResult.Conflict); memberships(result, a, b)
    }
    @Test fun A17_recoveryCarriesBothSets() = runBlocking {
        val (a, b) = setup(); o.seed(schema = 1)
        val result = o.control.execute(o.control.prepare(action()))
        assertTrue(result is ControlStoreResult.RecoveryRequired); memberships(result, a, b)
    }
    @Test fun A17_ioCarriesBothSets() = runBlocking {
        val (a, b) = setup(); o.storage.before = true
        val result = o.control.execute(b)
        assertTrue(result is ControlStoreResult.Unconfirmed); memberships(result, a, b)
    }
    @Test fun A17_historyUnavailableCarriesBothSets() = runBlocking {
        val (a, b) = setup()
        val result = o.control.confirmPrevious(b)
        assertEquals(UnconfirmedReason.HistoryUnavailable, (result as? ControlStoreResult.Unconfirmed)?.reason)
        memberships(result, a, b)
    }
    @Test fun A16_addingBusinessWorkKeepsPending() = runBlocking {
        val (a, b) = setup(); val c = o.control.prepare()
        tracker.markUnresolved(c)
        assertEquals(setOf(b, c), tracker.recoverySnapshot().unresolvedCommands)
        assertEquals(setOf(a), tracker.recoverySnapshot().pendingReleases)
    }
    @Test fun A16_resolvingBusinessWorkKeepsOtherBusinessAndPending() = runBlocking {
        val (a, b) = setup(); val c = o.control.prepare(); tracker.markUnresolved(c)
        tracker.resolve(b)
        assertEquals(setOf(c), tracker.recoverySnapshot().unresolvedCommands)
        assertEquals(setOf(a), tracker.recoverySnapshot().pendingReleases)
    }
    @Test fun A17_priorResultSetsAreImmutableSnapshots() = runBlocking {
        val (a, b) = setup(); val result = o.control.execute(o.control.prepare())
        val c = o.control.prepare(); tracker.markUnresolved(c)
        assertEquals(setOf(b), result.localUnresolvedCommands); assertEquals(setOf(a), result.localPendingReleases)
        val pendingFailure = runCatching { (result.localPendingReleases as MutableSet).add(c) }.exceptionOrNull()
        val unresolvedFailure = runCatching { (result.localUnresolvedCommands as MutableSet).add(c) }.exceptionOrNull()
        assertEquals(UnsupportedOperationException::class.java, pendingFailure?.javaClass)
        assertEquals(UnsupportedOperationException::class.java, unresolvedFailure?.javaClass)
        assertEquals(setOf(b, c), tracker.snapshot()); assertEquals(setOf(a), tracker.recoverySnapshot().pendingReleases)
    }
    @Test fun A17_inputSetsCannotMutateRecoverySnapshot() {
        val a = o.control.prepare(); val b = o.control.prepare()
        val unresolved = mutableSetOf(a); val pending = mutableSetOf(b)
        val work = LocalRecoveryWork(unresolved, pending)
        unresolved.clear(); pending.clear()
        assertEquals(setOf(a), work.unresolvedCommands); assertEquals(setOf(b), work.pendingReleases)
    }
    @Test fun A17_multipleUnresolvedSnapshotIsImmutable() {
        val a = o.control.prepare(); val b = o.control.prepare(); val c = o.control.prepare()
        val work = LocalRecoveryWork(setOf(a, b), emptySet())
        assertEquals(UnsupportedOperationException::class.java,
            runCatching { (work.unresolvedCommands as MutableSet).add(c) }.exceptionOrNull()?.javaClass)
        assertEquals(setOf(a, b), work.unresolvedCommands)
    }
    @Test fun A17_multiplePendingSnapshotIsImmutable() {
        val a = o.control.prepare(); val b = o.control.prepare(); val c = o.control.prepare()
        val work = LocalRecoveryWork(emptySet(), setOf(a, b))
        assertEquals(UnsupportedOperationException::class.java,
            runCatching { (work.pendingReleases as MutableSet).add(c) }.exceptionOrNull()?.javaClass)
        assertEquals(setOf(a, b), work.pendingReleases)
    }
    @Test fun C14_reclamationPreservesMemberships() = runBlocking {
        val (a, b) = setup(); val before = tracker.recoverySnapshot()
        assertTrue(o.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertSame(before, tracker.recoverySnapshot())
        assertEquals(setOf(b), tracker.snapshot()); assertEquals(setOf(a), tracker.recoverySnapshot().pendingReleases)
    }
    @Test fun C14_upgradePreservesMemberships() = runBlocking {
        val (a, b) = setup(); o.seed(schema = 1); val before = tracker.recoverySnapshot()
        assertTrue(o.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Confirmed)
        assertSame(before, tracker.recoverySnapshot())
        assertEquals(setOf(b), tracker.snapshot()); assertEquals(setOf(a), tracker.recoverySnapshot().pendingReleases)
    }
}
