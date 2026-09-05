package com.jay.fxi.data.entitlements

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single re-query owner.
 *
 * Two DoD properties are only checkable because there is exactly one owner: a server `Retry-After`
 * is never shortened, and pending/failure paths from an active grant and from a no-grant state
 * merge rather than race.
 *
 * These tests deliberately use an explicit `CoroutineScope(SupervisorJob() +
 * StandardTestDispatcher(testScheduler))` rather than `backgroundScope`, matching
 * `AuthTokenProviderTest`. On kotlinx-coroutines-test 1.9.0 `advanceUntilIdle()` does not advance
 * virtual time for work that only exists in the background scope, so a background-scoped timer
 * never fires — which would make any "nothing happened" assertion pass for the wrong reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecheckScheduleTest {

    private val processJob = SupervisorJob()

    @Test
    fun retryAfterFloor_isNeverShortenedByBackoff() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        // Attempt 1 has zero backoff; the server floor must still be honoured in full.
        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 30_000L))
        advanceTimeBy(29_999L)
        assertTrue("fired before the server floor", fired.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(30_000L), fired)
        processJob.cancel()
    }

    /** Regression: a later request with a shorter delay must not pull an existing floor earlier. */
    @Test
    fun reschedulingWithAShorterDelay_doesNotMoveAnExistingFloorEarlier() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 60_000L))
        advanceTimeBy(1_000L)
        schedule.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L))

        advanceTimeBy(58_999L)
        assertTrue("undercut the 60s floor at ${testScheduler.currentTime}", fired.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(60_000L), fired)
        processJob.cancel()
    }

    @Test
    fun cancel_releasesTheFloorSoTheNextRequestStartsClean() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 60_000L))
        schedule.cancel()
        // A settled outcome or an identity change invalidates the old server-requested delay.
        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L))
        advanceUntilIdle()

        assertEquals(listOf(0L), fired)
        processJob.cancel()
    }

    @Test
    fun backoffExtendsBeyondAShortFloor() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        repeat(3) {
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L))
            advanceUntilIdle()
        }

        assertEquals(3, fired.size)
        assertEquals(0L, fired[0])
        assertEquals(RecheckSchedule.BASE_BACKOFF_MILLIS, fired[1] - fired[0])
        assertEquals(RecheckSchedule.BASE_BACKOFF_MILLIS * 2, fired[2] - fired[1])
        processJob.cancel()
    }

    @Test
    fun schedulingAgain_replacesTheSinglePendingRequery() = runTest {
        val intents = mutableListOf<RefreshIntent>()
        val schedule =
            RecheckSchedule(testScope(), { testScheduler.currentTime }) { intent, _ ->
                intents += intent
            }

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L))
        schedule.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L))
        advanceUntilIdle()

        // One owner: the superseded request must not also fire.
        assertEquals(listOf(RefreshIntent.FORCE_PREMIUM), intents)
        processJob.cancel()
    }

    @Test
    fun cancel_disarmsAndResetsBackoff() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 60_000L))
        assertTrue(schedule.isArmed)
        schedule.cancel()
        advanceUntilIdle()

        assertFalse(schedule.isArmed)
        assertTrue("a cancelled re-query must not fire", fired.isEmpty())

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L))
        advanceUntilIdle()
        assertEquals(1, fired.size)
        processJob.cancel()
    }

    @Test
    fun ifStale_isDebouncedForTenSeconds() = runTest {
        var now = 0L
        val schedule = RecheckSchedule(testScope(), { now }) { _, _ -> }

        assertTrue(schedule.shouldQuery(RefreshIntent.IF_STALE, QueryOrigin.CALLER))
        schedule.recordQueryStarted()

        now = RecheckSchedule.IF_STALE_DEBOUNCE_MILLIS - 1
        assertFalse(schedule.shouldQuery(RefreshIntent.IF_STALE, QueryOrigin.CALLER))

        now = RecheckSchedule.IF_STALE_DEBOUNCE_MILLIS
        assertTrue(schedule.shouldQuery(RefreshIntent.IF_STALE, QueryOrigin.CALLER))
        processJob.cancel()
    }

    /**
     * Regression: an ordinary query that gets `premium_pending` with a 5 s retry used to schedule
     * an `IF_STALE` re-query that the 10 s debounce then swallowed, leaving nothing armed.
     */
    @Test
    fun aScheduledRetry_isNotSuppressedByTheOrdinaryDebounce() = runTest {
        var now = 0L
        val schedule = RecheckSchedule(testScope(), { now }) { _, _ -> }
        schedule.recordQueryStarted()
        now = 5_000L

        assertFalse(
            "the ordinary path is still debounced",
            schedule.shouldQuery(RefreshIntent.IF_STALE, QueryOrigin.CALLER)
        )
        assertTrue(
            "the server asked for this retry; the debounce must not eat it",
            schedule.shouldQuery(RefreshIntent.IF_STALE, QueryOrigin.SCHEDULED)
        )
        processJob.cancel()
    }

    /** Regression: the floor gated only the timer, so a direct forced call walked past it. */
    @Test
    fun theAbsoluteFloor_alsoGatesADirectCallerQuery() = runTest {
        var now = 0L
        val schedule = RecheckSchedule(testScope(), { now }) { _, _ -> }

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 60_000L))
        now = 1_000L

        // A Retry-After is a server rate limit; `.forcePremium` bypasses the client debounce and
        // the server premium cache, not this.
        assertFalse(schedule.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER))
        assertFalse(schedule.shouldQuery(RefreshIntent.FORCE_ENTITLEMENTS, QueryOrigin.CALLER))

        now = 60_000L
        assertTrue(schedule.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER))
        processJob.cancel()
    }

    /** Regression: an ordinary join used to weaken a queued `.forcePremium` to `IF_STALE`. */
    @Test
    fun joiningAWeakerRequest_doesNotWeakenTheQueuedMode() = runTest {
        val intents = mutableListOf<RefreshIntent>()
        val schedule =
            RecheckSchedule(testScope(), { testScheduler.currentTime }) { intent, _ -> intents += intent }

        schedule.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 30_000L))
        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L))
        advanceUntilIdle()

        // Running as IF_STALE would make the retry unable to grant at all, per the D23 table.
        assertEquals(listOf(RefreshIntent.FORCE_PREMIUM), intents)
        processJob.cancel()
    }

    @Test
    fun forcedIntents_bypassTheDebounce() = runTest {
        var now = 0L
        val schedule = RecheckSchedule(testScope(), { now }) { _, _ -> }
        schedule.recordQueryStarted()
        now = 1L

        assertFalse(schedule.shouldQuery(RefreshIntent.IF_STALE, QueryOrigin.CALLER))
        assertTrue(schedule.shouldQuery(RefreshIntent.FORCE_ENTITLEMENTS, QueryOrigin.CALLER))
        assertTrue(schedule.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER))
        processJob.cancel()
    }

    private fun TestScope.testScope(): CoroutineScope =
        CoroutineScope(processJob + StandardTestDispatcher(testScheduler))

    private fun TestScope.scheduleUnder(fired: MutableList<Long>): RecheckSchedule =
        RecheckSchedule(testScope(), { testScheduler.currentTime }) { _, _ ->
            fired += testScheduler.currentTime
        }
}
