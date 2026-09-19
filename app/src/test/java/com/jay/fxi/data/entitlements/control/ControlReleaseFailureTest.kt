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

class ControlReleaseFailureTest : ReleaseOwnerTestBase() {
    @Test fun C02_beforeWriteKeepsExactRowAndPendingThenRetries() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); pending(c); val descriptor = history(c).releaseDescriptor
        val saved = history(c); released(c, barrierAllowed = true); assertSame(descriptor, saved.releaseDescriptor)
        assertEquals(1L, disk()[ControlStoreTestStorage.BARRIER])
    }
    @Test fun C03_blockFailureIsNotFileScopeCompletion() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); val before = disk(); o.storage.after = true
        val result = o.control.releaseAfterConsumption(c) as ControlCommandReleaseResult.Unconfirmed
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, result.state); assertEquals(before, disk())
        assertNull("cache exposes the uncommitted candidate", own(o.raw(), c))
        assertNotEquals("cache is not disk confirmation", disk(), o.raw())
        val retry = released(c, barrierAllowed = true)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, retry.proof.storage)
    }
    @Test fun C04_landedIOExceptionLeavesPendingAndRetryConfirmsAbsence() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); val before = disk(); val tracked = history(c); o.storage.afterScope = true
        val result = o.control.releaseAfterConsumption(c) as ControlCommandReleaseResult.Unconfirmed
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, result.state); assertSame(tracked, history(c))
        assertTrue(c in result.localPendingReleases); assertTrue(result.localUnresolvedCommands.isEmpty())
        assertPreserved(before, disk(), c); assertNull(own(disk(), c))
        assertNull("even an absent cache row cannot complete pending release", own(o.raw(), c))
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
        val writes = o.storage.writes; released(c, barrierAllowed = true)
        assertEquals(writes + 1, o.storage.writes); assertEquals(1L, disk()[ControlStoreTestStorage.BARRIER])
    }
    @Test fun C05_cancelAfterLandingPropagatesAndKeepsPending() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); val before = disk(); val pause = ControlStoreTestStorage.Pause()
        o.storage.pauseAfterScope = pause
        val caller = async { o.control.releaseAfterConsumption(c) }
        try {
            withTimeout(10_000) { pause.reached.await() }; assertPreserved(before, disk(), c)
            caller.cancelAndJoinForTest(); assertTrue(caller.isCancelled)
            assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
            assertNotNull(history(c).releaseDescriptor); assertEquals(setOf(c), tracking.recoverySnapshot().pendingReleases)
            assertTrue(tracking.snapshot().isEmpty()); assertTrue(tracking.executing.isEmpty())
        } finally { pause.release.complete(Unit); caller.cancelAndJoinForTest() }
        released(c, barrierAllowed = true)
    }
    @Test fun C06a_pendingRetryIOExceptionKeepsFirstDescriptor() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); pending(c); val t = history(c); val descriptor = t.releaseDescriptor
        o.storage.before = true
        assertTrue(o.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.Unconfirmed)
        assertSame(descriptor, t.releaseDescriptor); assertSame(t, history(c))
        assertEquals(setOf(c), tracking.recoverySnapshot().pendingReleases); assertTrue(tracking.snapshot().isEmpty())
        released(c, barrierAllowed = true)
    }
    @Test fun C06b_pendingRetryCancellationKeepsFirstDescriptor() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); pending(c); val t = history(c); val descriptor = t.releaseDescriptor
        val pause = ControlStoreTestStorage.Pause(); o.storage.pause = pause
        val caller = async { o.control.releaseAfterConsumption(c) }
        try {
            withTimeout(10_000) { pause.reached.await() }; caller.cancelAndJoinForTest(); assertTrue(caller.isCancelled)
            assertSame(descriptor, t.releaseDescriptor); assertSame(t, history(c))
            assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
        } finally { pause.release.complete(Unit); caller.cancelAndJoinForTest() }
        released(c, barrierAllowed = true)
    }
    @Test fun C02_unexpectedWriteExceptionPropagatesWithoutCleanup() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); val before = disk(); o.storage.unexpected = true
        val failure = runCatching { o.control.releaseAfterConsumption(c) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState); assertNotNull(history(c))
        assertEquals(before, disk()); assertTrue(c in tracking.recoverySnapshot().pendingReleases); assertFalse(c in tracking.executing)
        released(c, barrierAllowed = true)
    }
    private suspend fun restart(landed: Boolean) {
        o.seed(); val c = confirmed(); val checkpoint = checkNotNull(o.control.checkpoint(c)); val before = disk()
        if (landed) o.storage.afterScope = true else o.storage.before = true
        assertTrue(o.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.Unconfirmed)
        val atRestart = disk(); if (landed) assertPreserved(before, atRestart, c) else assertEquals(before, atRestart)
        o.close() // Call above ended; no paused work remains. Old scope is joined before a new owner.
        val next = open(file); val nt = ControlCommandTracking.forOwner(next.owner)
        assertNotSame(tracking.lifetimeId, nt.lifetimeId)
        assertTrue(next.control.execute(c) is ControlStoreResult.ReleasePending)
        assertTrue(next.control.confirmPrevious(c, checkpoint) is ControlStoreResult.ReleasePending)
        val wrong = next.control.releaseAfterConsumption(c) as ControlCommandReleaseResult.Rejected
        assertEquals(ReleaseRejectionReason.WrongTrackerLifetime, wrong.reason)
        assertTrue(wrong.localUnresolvedCommands.isEmpty()); assertTrue(wrong.localPendingReleases.isEmpty())
        assertTrue(next.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertPreserved(before, disk(), c); assertNull(nt.findPrepared(c)); assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
    }
    @Test fun C15_restartAfterUnlandedReleaseReclaimsPreviousRow() = runReleaseTest<Unit> { restart(false) }
    @Test fun C16_restartAfterLandedReleaseKeepsAbsence() = runReleaseTest<Unit> { restart(true) }
    @Test fun C17_releasedRefOnNewOwnerIsTerminalButReleaseRejectsLifetime() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); released(c); o.close(); val next = open(file)
        assertTrue(next.control.execute(c) is ControlStoreResult.Released)
        assertTrue(next.control.execute(c, NamespaceSettlementFixtures.context) is ControlStoreResult.Released)
        assertTrue(next.control.confirmPrevious(c) is ControlStoreResult.Released)
        assertEquals(ReleaseRejectionReason.WrongTrackerLifetime, (next.control.releaseAfterConsumption(c) as ControlCommandReleaseResult.Rejected).reason)
    }
    @Test fun C18_unreleasedOldRefStillUsesPreviousConfirmation() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); val checkpoint = checkNotNull(o.control.checkpoint(c)); o.close(); val next = open(file)
        assertTrue(next.control.reclaimPreviousLifetimeEvidence() is ControlEvidenceReclamationResult.Confirmed)
        assertTrue(next.control.confirmPrevious(c, checkpoint) is ControlStoreResult.Confirmed)
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertTrue(next.control.execute(c) is ControlStoreResult.Unconfirmed)
    }
    @Test fun C19_externalAbsenceIsConfirmedWithoutHistoricalRemovalClaim() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); pending(c)
        o.data.edit { it[evidenceKey] = "[]" } // Defensive external removal; no claimed release causation.
        val t = history(c); val descriptor = t.releaseDescriptor
        released(c, barrierAllowed = true); assertSame(descriptor, t.releaseDescriptor)
        assertEquals(BigInteger.ZERO, tracking.evidenceDiscontinuityCount)
    }
}
