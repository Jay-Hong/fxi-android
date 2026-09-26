package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.BARRIER
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.evidenceKey
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-2C3 contract: real restart around a current-lifetime Rotation consumption termination (6-2 skeleton
 * r3 §5 and T8; revision 06 §5.2 last paragraphs, §11 restart group). Every case really closes the old DataStore scope
 * (the old caller cancelled/joined) and reopens the SAME FileStorage file with a new tracker lifetime. Not landed → the
 * previous Rotation Applied row and its full settled seal bundle are reclaimed by the existing 2c path in one atomic
 * candidate; landed (return failure or cancellation after landing) → the removal survives and 2c finds nothing more to
 * remove. The old ref is never registered in the new tracker and cannot re-apply. 2c does
 * not take a Rotation of the new tracker's own lifetime. The old ref, left TERMINATION_PENDING by the failed consumption,
 * is refused by its own state gate (ControlStoreResult.TerminationPending) — it neither executes nor re-applies. No new consumption condition is added to 2c. The implementation
 * thread reads but does not edit this file.
 */
class ControlRotationRestartContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "rotation-restart.preferences_pb") }
    private var opened: ControlStoreTestStorage? = null
    @After fun close() = runBlocking { opened?.close(); Unit }
    private suspend fun open(): ControlStoreTestStorage {
        opened?.close() // cancels and joins the old scope before the same file is reopened
        return ControlStoreTestStorage(file).also { opened = it }
    }
    private fun tracker(o: ControlStoreTestStorage) = ControlCommandTracking.forOwner(o.owner)
    private suspend fun disk(): Preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
    private val declared = RotationConsumption(resultConsumed = true, followUpCompletedOrDurablyOwned = true)
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(BARRIER) }.toPreferences()
    /** The single-seal rotation's own row and seal removed; every other key (REQUEST, epochs, …) unchanged. */
    private fun removed(p: Preferences) = p.toMutablePreferences().apply { this[SEAL] = "[]"; this[evidenceKey] = "[]"; remove(BARRIER) }.toPreferences()

    private suspend fun confirmedRotation(o: ControlStoreTestStorage): CommandRef {
        o.data.updateData { NamespaceSettlementFixtures.raw() }
        val c = o.control.prepareRotation(listOf(node(NamespaceSettlementFixtures.user)), NamespaceSettlementFixtures.fence,
            NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand)
        check(o.control.execute(c, NamespaceSettlementFixtures.context) is ControlStoreResult.Confirmed)
        check(disk()[evidenceKey] != "[]" && disk()[SEAL]?.contains("\"settlement\"") == true) { "fixture: applied bundle" }
        return c
    }
    private suspend fun assertOldRefCannotReapply(next: ControlStoreTestStorage, c: CommandRef, expected: Preferences) {
        assertNull("D2B6/6-2C3: oldRefNotRegistered", tracker(next).findPrepared(c))
        // The old ref stayed TERMINATION_PENDING: its own state gate refuses execution before any history lookup (6-1A G19).
        assertEquals("D2B6/6-2C3: oldRefPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        val retry = next.control.execute(c)
        assertTrue("D2B6/6-2C3: terminationPending $retry", retry is ControlStoreResult.TerminationPending)
        assertEquals("D2B6/6-2C3: nothingReapplied", expected, withoutBarrier(disk()))
    }

    @Test fun T8_80_notLandedConsumptionThenRestart2cReclaimsTheWholeBundle() = runBlocking {
        val o = open(); val c = confirmedRotation(o); val source = disk()
        o.storage.before = true
        val r = o.control.completeAfterConsumption(c, closure(c), declared)
        assertTrue("D2B6/6-2C3.80: unconfirmed $r", r is ControlCompletionResult.Unconfirmed)
        assertEquals("D2B6/6-2C3.80: nothingLanded", source, disk())
        val next = open()
        assertNotEquals("D2B6/6-2C3.80: newLifetime", c.ownerTrackingLifetimeId.value, tracker(next).lifetimeId.value)
        val reclaimed = next.control.reclaimPreviousLifetimeEvidence()
        assertTrue("D2B6/6-2C3.80: reclaimed $reclaimed", reclaimed is ControlEvidenceReclamationResult.Confirmed)
        assertEquals("D2B6/6-2C3.80: atomicBundleRemoval", removed(source), withoutBarrier(disk()))
        assertOldRefCannotReapply(next, c, removed(source))
    }
    @Test fun T8_81_landedConsumptionReturnFailureThenRestartKeepsTheRemoval() = runBlocking {
        val o = open(); val c = confirmedRotation(o); val source = disk()
        o.storage.afterScope = true
        assertTrue(o.control.completeAfterConsumption(c, closure(c), declared) is ControlCompletionResult.Unconfirmed)
        assertEquals("D2B6/6-2C3.81: landed", removed(source), withoutBarrier(disk()))
        val next = open(); val before = disk(); val writes = next.storage.writes
        assertTrue(next.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertEquals("D2B6/6-2C3.81: nothingMoreRemoved", before, disk())
        assertEquals("D2B6/6-2C3.81: noWrite", writes, next.storage.writes)
        assertOldRefCannotReapply(next, c, removed(source))
    }
    @Test fun T8_82_cancellationAfterLandingThenRestartKeepsTheRemoval() = runBlocking {
        val o = open(); val c = confirmedRotation(o); val source = disk()
        val pause = ControlStoreTestStorage.Pause(); o.storage.pauseAfterScope = pause
        val caller = async { o.control.completeAfterConsumption(c, closure(c), declared) }
        try {
            withTimeout(10_000) { pause.reached.await() }
            assertEquals("D2B6/6-2C3.82: landedBeforeCancel", removed(source), withoutBarrier(disk()))
            caller.cancelAndJoin()
            assertTrue("D2B6/6-2C3.82: cancelled", caller.isCancelled)
        } finally { pause.release.complete(Unit); caller.cancelAndJoin() }
        val next = open()
        assertTrue(next.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertEquals("D2B6/6-2C3.82: removalKept", removed(source), withoutBarrier(disk()))
        assertOldRefCannotReapply(next, c, removed(source))
    }
    @Test fun T8_83_2cDoesNotTakeARotationOfTheCurrentLifetime() = runBlocking {
        val next = open() // the rotation below belongs to this storage's own tracker lifetime
        val c = confirmedRotation(next); val source = disk()
        assertTrue(next.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertEquals("D2B6/6-2C3.83: currentBundleKept", withoutBarrier(source), withoutBarrier(disk()))
        assertTrue("D2B6/6-2C3.83: stillRegistered", tracker(next).findPrepared(c) != null)
    }
    @Test fun T8_84_restart2cHoldsAPreviousBundleWithAnExtraSealOfItsOperation() = runBlocking {
        // 2c keeps its own full-operation-set check: an extra seal settled by the previous rotation's operation (not in its
        // Applied row) makes the whole previous bundle inconsistent → RecoveryRequired, nothing removed.
        val o = open(); val c = confirmedRotation(o)
        val N = NamespaceSettlementFixtures
        val extra = N.settled(N.input(targets = listOf(node(N.krx)), op = c.id, did = "00000000-0000-0000-0000-000000000099"),
            N.raw("[${N.krx}]"), N.trackerLife)[SEAL]!!.removePrefix("[").removeSuffix("]")
        check(extra.contains(c.id)) { "fixture: extra seal of this operation" }
        o.data.updateData { p -> p.toMutablePreferences().apply { this[SEAL] = this[SEAL]!!.removeSuffix("]") + "," + extra + "]" }.toPreferences() }
        val next = open(); val before = disk(); val writes = next.storage.writes
        val r = next.control.reclaimPreviousLifetimeEvidence()
        assertTrue("D2B6/6-2C3.84: recovery $r", r is ControlEvidenceReclamationResult.RecoveryRequired &&
            r.reason == RecoveryReason.InconsistentReclamation)
        assertEquals("D2B6/6-2C3.84: untouched", before, disk())
        assertEquals("D2B6/6-2C3.84: noWrite", writes, next.storage.writes)
    }
}
