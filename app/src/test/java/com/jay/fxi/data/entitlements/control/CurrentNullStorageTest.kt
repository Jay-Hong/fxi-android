package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.READ_BARRIER
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.spec
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.both
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.raw
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.before
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.executor
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.context
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.request
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.life
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.target
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.transition
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.command
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.witness
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.read
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.landed
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.decide
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.negative
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.blob
import org.junit.Assert.*
import org.junit.Test

import androidx.datastore.core.DataStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async

class CurrentNullStorageTest : CurrentNullOwnerBase() {
    private suspend fun failure(s: CurrentNullSettlement, boundary: String, restart: Boolean = false) {
        val source = raw(s); seedN(s, source); val c = registerN(s)
        when (boundary) {
            "before" -> o.storage.before = true
            "block" -> o.storage.after = true
            "landed" -> o.storage.afterScope = true
            else -> error(boundary)
        }
        val ctx = context.copy(ownerUid = s.executor.ownerUid)
        val result = executeN(c, ctx)
        assertTrue(result is ControlStoreResult.Unconfirmed)
        result as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.StorageFailure, result.reason)
        assertEquals(ControlAttemptPhase.ConfirmingStorage, result.phase)
        assertTrue(result.failure is IOException)
        assertEquals("A15: failed confirmation must retain unresolved command", setOf(c), result.localUnresolvedCommands)
        assertTrue(history(c).confirmationRequested.get()); assertFalse("A15: failed confirmation must not set confirmed", history(c).confirmed.get())
        assertFalse("A15: failed confirmation must not mark Applied observed", history(c).observedApplied.get()); assertFalse(c in tracking.executing)
        if (boundary == "landed") assertLanded(c, s, source) else assertEquals(source, disk())
        if (restart) {
            o.close()
            val next = open(file); val nt = ControlCommandTracking.forOwner(next.owner)
            assertNotSame(tracking.lifetimeId, nt.lifetimeId)
            val refused = controlTestTimeout("old execute refused") { next.control.execute(c, ctx) }
            assertEquals(UnconfirmedReason.HistoryUnavailable, (refused as? ControlStoreResult.Unconfirmed)?.reason)
            val saved = disk(); val writes = next.storage.writes
            val previous = controlTestTimeout("previous R witness") { next.control.confirmPrevious(c) }
            assertNull(nt.findPrepared(c)); assertFalse(c in nt.executing)
            if (boundary == "landed") {
                assertTrue("A10: previous landed N must be confirmed by name", previous is ControlStoreResult.Confirmed)
                assertEquals(ConfirmedEffect.PostconditionConfirmed, (previous as ControlStoreResult.Confirmed).effect)
                assertEquals(s.operationId, previous.handoverSettlement!!.operationId)
                assertEquals(saved, disk()); assertEquals(writes, next.storage.writes)
            } else {
                NamespaceSettlementFixtures.negative(previous, ConflictReason.TargetChanged)
                assertEquals(setOf(c), previous.localUnresolvedCommands)
                assertEquals(saved, disk()); assertEquals(writes, next.storage.writes)
                val fresh = next.control.prepareCurrentNullSettlement(s.nullTargets, read(raw(s)), s.before, s.executor, s.demand)
                val recovered = controlTestTimeout("new recovery ref") { next.control.execute(fresh, ctx) }
                assertTrue(recovered is ControlStoreResult.Confirmed)
                assertEquals(setOf(c), recovered.localUnresolvedCommands)
            }
        } else {
            val expected = if (boundary == "landed") ConfirmedEffect.PostconditionConfirmed else ConfirmedEffect.AppliedThisAttempt
            // The file is authoritative after a failed write scope; retry must confirm a complete candidate.
            successN(executeN(c, ctx), expected)
            assertLanded(c, s, source)
            assertEquals(1L, disk()[READ_BARRIER])
        }
    }
    @Test fun A15_currentBeforeWrite() = runReleaseTest { failure(spec(), "before") }
    @Test fun A15_bothBeforeWrite() = runReleaseTest { failure(both(), "before") }
    @Test fun A15_currentAfterBlockBeforeLanding() = runReleaseTest { failure(spec(), "block") }
    @Test fun A15_bothAfterBlockBeforeLanding() = runReleaseTest { failure(both(), "block") }
    @Test fun A15_currentAfterLanding() = runReleaseTest { failure(spec(), "landed") }
    @Test fun A15_bothAfterLanding() = runReleaseTest { failure(both(), "landed") }
    @Test fun A16_currentUnlandedRestart() = runReleaseTest { failure(spec(), "before", true) }
    @Test fun A16_bothUnlandedRestart() = runReleaseTest { failure(both(), "before", true) }
    @Test fun A16_currentLandedRestart() = runReleaseTest { failure(spec(), "landed", true) }
    @Test fun A16_bothLandedRestart() = runReleaseTest { failure(both(), "landed", true) }

    private suspend fun cancelAt(s: CurrentNullSettlement, landed: Boolean) = kotlinx.coroutines.coroutineScope {
        val source = raw(s); seedN(s, source); val c = registerN(s); val pause = ControlStoreTestStorage.Pause()
        if (landed) o.storage.pauseAfterScope = pause else o.storage.pause = pause
        val caller = async { executeN(c, context.copy(ownerUid = s.executor.ownerUid)) }
        try {
            controlTestTimeout("N cancellation gate") { pause.reached.await() }
            if (landed) assertLanded(c, s, source) else assertEquals(source, disk())
            if (!landed) o.storage.after = true // Force abort when the independent DataStore writer resumes.
            caller.cancelAndJoinForTest()
            assertTrue(caller.isCancelled)
            assertEquals(setOf(c), tracking.snapshot()); assertFalse(history(c).confirmed.get())
            assertFalse(history(c).observedApplied.get()); assertFalse(c in tracking.executing)
        } finally {
            pause.release.complete(Unit); caller.cancelAndJoinForTest()
        }
        o.close() // caller joined, then DataStore scope cancelled and joined before reopen.
        val next = open(file)
        val result = controlTestTimeout("cancelled previous R") { next.control.confirmPrevious(c) }
        if (landed) {
            assertTrue(result is ControlStoreResult.Confirmed)
            assertEquals(ConfirmedEffect.PostconditionConfirmed, (result as ControlStoreResult.Confirmed).effect)
            assertLanded(c, s, source)
        } else {
            NamespaceSettlementFixtures.negative(result, ConflictReason.TargetChanged)
            assertEquals(source, disk())
        }
    }
    @Test fun A15_currentCancelBeforeLanding() = runReleaseTest { cancelAt(spec(), false) }
    @Test fun A15_bothCancelBeforeLanding() = runReleaseTest { cancelAt(both(), false) }
    @Test fun A15_currentCancelAfterLanding() = runReleaseTest { cancelAt(spec(), true) }
    @Test fun A15_bothCancelAfterLanding() = runReleaseTest { cancelAt(both(), true) }

    private class BeforeRead(private val delegate: DataStore<Preferences>) : DataStore<Preferences> by delegate {
        var fail = false
        var throwCancellation = false
        var pause: ControlStoreTestStorage.Pause? = null
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (fail) { fail = false; throw IOException("before actual snapshot") }
            if (throwCancellation) { throwCancellation = false; throw CancellationException("explicit storage cancellation") }
            pause?.let { gate -> pause = null; gate.reached.complete(Unit); controlTestTimeout("before snapshot gate") { gate.release.await() } }
            return delegate.updateData(transform)
        }
    }
    private suspend fun beforeRead(s: CurrentNullSettlement, cancel: Boolean) = kotlinx.coroutines.coroutineScope {
        lateinit var faults: BeforeRead
        val store = ControlStoreTestStorage(file) { BeforeRead(it).also { wrapper -> faults = wrapper } }.also { opened += it }
        controlTestTimeout("before read seed") { store.data.updateData { raw(s) } }
        val c = store.control.prepareCurrentNullSettlement(s.nullTargets, read(raw(s)), s.before, s.executor, s.demand)
        val t = ControlCommandTracking.forOwner(store.owner); val tracked = t.findPrepared(c)!!
        val source = disk(); val writes = store.storage.writes
        if (cancel) {
            val gate = ControlStoreTestStorage.Pause(); faults.pause = gate
            val caller = async { store.control.execute(c, context.copy(ownerUid = s.executor.ownerUid)) }
            try { controlTestTimeout("before read reached") { gate.reached.await() }; caller.cancelAndJoinForTest(); assertTrue(caller.isCancelled) }
            finally { gate.release.complete(Unit); caller.cancelAndJoinForTest() }
        } else {
            faults.fail = true
            val result = controlTestTimeout("before read failure") { store.control.execute(c, context.copy(ownerUid = s.executor.ownerUid)) }
            assertTrue(result is ControlStoreResult.Unconfirmed)
            result as ControlStoreResult.Unconfirmed
            assertEquals(ControlAttemptPhase.ReadingSnapshot, result.phase); assertNull(result.lastObservation)
        }
        assertEquals(source, disk()); assertEquals(writes, store.storage.writes)
        assertNull(tracked.firstConfirmDiscontinuityCount); assertFalse(tracked.confirmationRequested.get())
        assertFalse(tracked.observedApplied.get()); assertFalse(tracked.confirmed.get())
        assertEquals(setOf(c), t.snapshot()); assertFalse(c in t.executing)
        assertEquals(java.math.BigInteger.ZERO, t.evidenceDiscontinuityCount)
        assertTrue(controlTestTimeout("before read retry") { store.control.execute(c, context.copy(ownerUid = s.executor.ownerUid)) } is ControlStoreResult.Confirmed)
    }
    @Test fun A15_currentIOExceptionBeforeRead() = runReleaseTest { beforeRead(spec(), false) }
    @Test fun A15_bothIOExceptionBeforeRead() = runReleaseTest { beforeRead(both(), false) }
    @Test fun A15_currentCancellationBeforeRead() = runReleaseTest { beforeRead(spec(), true) }
    @Test fun A15_bothCancellationBeforeRead() = runReleaseTest { beforeRead(both(), true) }
    private suspend fun thrownCancellation(s: CurrentNullSettlement) {
        lateinit var faults: BeforeRead
        val store = ControlStoreTestStorage(file) { BeforeRead(it).also { wrapper -> faults = wrapper } }.also { opened += it }
        controlTestTimeout("throw cancellation seed") { store.data.updateData { raw(s) } }
        val c = store.control.prepareCurrentNullSettlement(s.nullTargets, read(raw(s)), s.before, s.executor, s.demand)
        val tracker = ControlCommandTracking.forOwner(store.owner)
        val saved = disk(); val writes = store.storage.writes
        faults.throwCancellation = true
        val failure = runCatching { controlTestTimeout("explicit cancellation") { store.control.execute(c, context.copy(ownerUid = s.executor.ownerUid)) } }.exceptionOrNull()
        assertTrue("CancellationException must propagate even while caller Job remains active", failure is CancellationException)
        assertEquals("explicit storage cancellation", failure!!.message)
        assertEquals(saved, disk()); assertEquals(writes, store.storage.writes)
        assertEquals(setOf(c), tracker.snapshot()); assertFalse(c in tracker.executing)
        assertNull(tracker.findPrepared(c)!!.firstConfirmDiscontinuityCount)
    }
    @Test fun A15_currentThrownCancellationPropagates() = runReleaseTest { thrownCancellation(spec()) }
    @Test fun A15_bothThrownCancellationPropagates() = runReleaseTest { thrownCancellation(both()) }
    @Test fun A10_previousLifetimeCannotRebindApplied() = runReleaseTest {
        val s = both(); seedN(s); val c = registerN(s)
        successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        o.close(); val next = open(file); val nt = ControlCommandTracking.forOwner(next.owner)
        controlTestTimeout("N replace tracker linkage") { next.data.edit { it[evidenceKey] = it[evidenceKey]!!.replace(c.ownerTrackingLifetimeId.value, nt.lifetimeId.value) } }
        val saved = disk(); val writes = next.storage.writes
        val result = controlTestTimeout("old N tracker mismatch") { next.control.confirmPrevious(c) }
        NamespaceSettlementFixtures.negative(result, ConflictReason.CommandEvidenceMismatch)
        assertNull(nt.findPrepared(c)); assertEquals(saved, disk()); assertEquals(writes, next.storage.writes)
    }
    @Test fun A10_previousWitnessWithoutApplied() = runReleaseTest {
        val s = both(); seedN(s); val c = registerN(s)
        successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        controlTestTimeout("remove old N evidence") { o.data.edit { it[evidenceKey] = "[]" } }
        o.close(); val next = open(file); val saved = disk(); val writes = next.storage.writes
        val result = controlTestTimeout("old N fixed witness") { next.control.confirmPrevious(c) }
        assertTrue(result is ControlStoreResult.Confirmed)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, (result as ControlStoreResult.Confirmed).effect)
        assertEquals(saved, disk()); assertEquals(writes, next.storage.writes)
        assertNull(ControlCommandTracking.forOwner(next.owner).findPrepared(c))
    }
}
