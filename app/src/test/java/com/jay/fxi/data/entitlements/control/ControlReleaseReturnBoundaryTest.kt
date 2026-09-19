package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** One owner wraps a real DataStore from construction, allowing faults before decide or on return. */
internal class ReleaseBoundaryData(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    var before: Throwable? = null
    var after: ((Preferences) -> Preferences)? = null
    var calls = 0
    override val data get() = delegate.data
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        calls++
        before?.let { before = null; throw it }
        val result = delegate.updateData(transform)
        return after?.let { after = null; it(result) } ?: result
    }
}

class ControlReleaseReturnBoundaryTest : ReleaseOwnerTestBase() {
    private lateinit var boundary: ReleaseBoundaryData
    private fun wrapped() = ControlStoreTestStorage(file) { ReleaseBoundaryData(it).also { boundary = it } }.also { opened += it }
    private suspend fun beforeFault(failure: Throwable) {
        val store = wrapped(); store.seed(); val c = add(store.control)
        assertTrue(store.control.execute(c) is ControlStoreResult.Confirmed)
        val tracker = ControlCommandTracking.forOwner(store.owner); val t = tracker.findPrepared(c)!!; val before = disk()
        boundary.before = failure
        val attempt = runCatching { store.control.releaseAfterConsumption(c) }
        if (failure is IOException) {
            val result = attempt.getOrThrow() as ControlCommandReleaseResult.Unconfirmed
            assertSame(failure, result.failure); assertNull(result.lastObservation)
            assertEquals(ControlAttemptPhase.ReadingSnapshot, result.phase); assertEquals(ControlCommandLifecycle.RETAINED, result.state)
        } else assertSame(failure, attempt.exceptionOrNull())
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState); assertNull(t.releaseDescriptor)
        assertEquals(BigInteger.ZERO, tracker.evidenceDiscontinuityCount); assertTrue(tracker.recoverySnapshot().pendingReleases.isEmpty())
        assertTrue(tracker.snapshot().isEmpty()); assertSame(t, tracker.findPrepared(c)); assertEquals(before, disk())
        assertTrue(tracker.executing.isEmpty())
    }
    @Test fun C01_readIOExceptionDoesNotCloseOrObserve() = runReleaseTest<Unit> { beforeFault(IOException("read failed")) }
    @Test fun C01_cancelBeforeDecidePropagatesWithoutPending() = runReleaseTest<Unit> { beforeFault(CancellationException("cancel read")) }
    @Test fun C01_invariantBeforeDecidePropagatesWithoutPending() = runReleaseTest<Unit> { beforeFault(IllegalStateException("bad read")) }
    private suspend fun badReturn(change: (Preferences, Preferences) -> Preferences) {
        val store = wrapped(); store.seed(); val c = add(store.control)
        assertTrue(store.control.execute(c) is ControlStoreResult.Confirmed)
        val tracker = ControlCommandTracking.forOwner(store.owner); val before = disk()
        boundary.after = { change(before, it) }
        assertEquals(IllegalStateException::class.java, runCatching { store.control.releaseAfterConsumption(c) }.exceptionOrNull()?.javaClass)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState); assertNotNull(tracker.findPrepared(c))
        assertEquals(setOf(c), tracker.recoverySnapshot().pendingReleases); assertTrue(tracker.snapshot().isEmpty())
        assertFalse(c in tracker.executing); assertPreserved(before, disk(), c)
    }
    @Test fun C04_returnedOwnRowCannotBeReportedReleased() = runReleaseTest<Unit> { badReturn { before, _ -> before } }
    @Test fun C04_returnedUnsupportedRecordCannotBeReportedReleased() = runReleaseTest<Unit> { badReturn { _, _ -> mutablePreferencesOf() } }
    @Test fun B23_returnedUnrelatedChangeCannotBeReportedReleased() = runReleaseTest<Unit> {
        badReturn { _, actual -> actual.toMutablePreferences().apply { this[ControlStoreTestStorage.EXTRA] = "unexpected" } }
    }
    @Test fun A13_alreadyReleasedDoesNotEvenEnterOwner() = runReleaseTest<Unit> {
        val store = wrapped(); store.seed(); val c = add(store.control)
        assertTrue(store.control.execute(c) is ControlStoreResult.Confirmed)
        assertTrue(store.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.Released)
        val calls = boundary.calls; boundary.before = IOException("must not read")
        assertTrue(store.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.AlreadyReleased)
        assertEquals(calls, boundary.calls); assertNotNull(boundary.before)
    }
    @Test fun C19_pendingNoopConfirmsAbsenceAfterReturnFailure() = runReleaseTest<Unit> {
        val store = wrapped(); store.seed(demand = "[${ControlObligationFixtures.request}]")
        val c = store.control.prepare(store.control.edit(ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.request)) {})
        assertTrue(store.control.execute(c) is ControlStoreResult.Confirmed)
        val tracker = ControlCommandTracking.forOwner(store.owner); val t = tracker.findPrepared(c)!!
        boundary.after = { throw IOException("after unchanged confirmation") }
        assertTrue(store.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.Unconfirmed)
        assertSame(ReleasePendingDescriptor.ConfirmedWithoutApplied, t.releaseDescriptor)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
        assertTrue(store.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.Released)
        assertSame(ReleasePendingDescriptor.ConfirmedWithoutApplied, t.releaseDescriptor)
    }
    @Test fun C19_pendingNoopRejectsNewlyAppearingOwnEvidence() = runReleaseTest<Unit> {
        val store = wrapped(); store.seed(demand = "[${ControlObligationFixtures.request}]")
        val c = store.control.prepare(store.control.edit(ControlKind.DEMAND, ControlObligationFixtures.node(ControlObligationFixtures.request)) {})
        assertTrue(store.control.execute(c) is ControlStoreResult.Confirmed)
        val tracker = ControlCommandTracking.forOwner(store.owner); val t = tracker.findPrepared(c)!!
        boundary.after = { throw IOException("after unchanged confirmation") }
        assertTrue(store.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.Unconfirmed)
        store.data.edit { it[evidenceKey] = "[${ControlReleaseFixtures.wire(ControlReleaseFixtures.row(c))}]" }
        val before = disk(); val result = store.control.releaseAfterConsumption(c)
        assertEquals(ConflictReason.CommandEvidenceMismatch, (result as ControlCommandReleaseResult.Conflict).reason)
        assertSame(ReleasePendingDescriptor.ConfirmedWithoutApplied, t.releaseDescriptor)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState); assertEquals(before, disk())
    }

}
