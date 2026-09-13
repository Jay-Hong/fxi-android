package com.jay.fxi.data.entitlements

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 30_000L), bindingEpoch = 1L)
        advanceTimeBy(29_999L)
        assertTrue("fired before the server floor", fired.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(30_000L), fired)
        processJob.cancel()
    }

    /**
     * A settled outcome retires the floor with the request that earned it. An identity boundary
     * does not — a `Retry-After` is a rate limit on this device and endpoint, and switching accounts
     * is not something the server agreed to lift. Clearing it here let a sign-in one second after a
     * 30s floor issue its first query immediately.
     */
    @Test
    fun anIdentityBoundary_cancelsThePendingRetryButKeepsTheServerFloor() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 30_000L), bindingEpoch = 1L)
        advanceTimeBy(1_000L)
        schedule.cancel(preserveServerFloor = true)

        // The queued retry is gone…
        advanceUntilIdle()
        assertTrue("the pending retry survived the boundary", fired.isEmpty())

        // …but the floor is not, so the next caller still waits it out.
        assertTrue(
            "a new sign-in issued its first query inside the server's floor",
            !schedule.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER)
        )
        processJob.cancel()
    }

    /** A settled outcome does retire it: the request that earned the floor is finished. */
    @Test
    fun aSettledOutcome_retiresTheServerFloorWithIt() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 30_000L), bindingEpoch = 1L)
        advanceTimeBy(1_000L)
        schedule.cancel()

        assertTrue(
            "a settled outcome left a floor standing",
            schedule.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER)
        )
        processJob.cancel()
    }

    /**
     * Honouring the wait must not mean losing the request.
     *
     * An identity boundary cancels the pending retry and keeps the floor, so the new binding's first
     * query arrives with nothing to fold into — `upgradePendingIntent` does nothing without an
     * active pending, and the query was simply dropped, leaving that user on `NoGrant` with nothing
     * scheduled to ask again.
     */
    @Test
    fun aRequestRefusedByAPreservedFloor_isArmedForWhenTheFloorLapses() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 30_000L), bindingEpoch = 1L)
        advanceTimeBy(1_000L)
        schedule.cancel(preserveServerFloor = true)      // identity boundary

        // The new binding's first query is refused by the floor that survived.
        assertTrue(!schedule.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER))
        schedule.deferUntilFloor(RefreshIntent.FORCE_PREMIUM, bindingEpoch = 1L)

        advanceTimeBy(28_999L)
        assertTrue("fired inside the preserved floor", fired.isEmpty())

        advanceUntilIdle()
        assertEquals("the new owner's query was dropped rather than deferred", listOf(30_000L), fired)
        processJob.cancel()
    }

    /** With a retry already armed, a refused request strengthens it rather than adding another. */
    @Test
    fun aRequestRefusedWhileARetryIsArmed_foldsIntoIt() = runTest {
        val fired = mutableListOf<Long>()
        val firedIntents = mutableListOf<RefreshIntent>()
        val schedule = scheduleUnder(fired, firedIntents)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 30_000L), bindingEpoch = 1L)
        advanceTimeBy(1_000L)
        schedule.deferUntilFloor(RefreshIntent.FORCE_PREMIUM, bindingEpoch = 1L)

        advanceUntilIdle()
        assertEquals("a second retry was armed alongside the first", listOf(30_000L), fired)
        // Folding is only observable in *what* fires: the armed retry keeps its own timing but must
        // come back strong enough to grant, which is the whole reason a refused request folds
        // rather than being dropped.
        assertEquals(listOf(RefreshIntent.FORCE_PREMIUM), firedIntents)
        processJob.cancel()
    }

    /** Regression: a later request with a shorter delay must not pull an existing floor earlier. */
    @Test
    fun reschedulingWithAShorterDelay_doesNotMoveAnExistingFloorEarlier() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 60_000L), bindingEpoch = 1L)
        advanceTimeBy(1_000L)
        schedule.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L), bindingEpoch = 1L)

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

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 60_000L), bindingEpoch = 1L)
        schedule.cancel()
        // A settled outcome or an identity change invalidates the old server-requested delay.
        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L), bindingEpoch = 1L)
        advanceUntilIdle()

        assertEquals(listOf(0L), fired)
        processJob.cancel()
    }

    @Test
    fun backoffExtendsBeyondAShortFloor() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        repeat(3) {
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L), bindingEpoch = 1L)
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
            RecheckSchedule(testScope(), { testScheduler.currentTime }) { intent, _, _ ->
                intents += intent
            }

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L), bindingEpoch = 1L)
        schedule.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L), bindingEpoch = 1L)
        advanceUntilIdle()

        // One owner: the superseded request must not also fire.
        assertEquals(listOf(RefreshIntent.FORCE_PREMIUM), intents)
        processJob.cancel()
    }

    @Test
    fun cancel_disarmsAndResetsBackoff() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = scheduleUnder(fired)

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 60_000L), bindingEpoch = 1L)
        assertTrue(schedule.isArmed)
        schedule.cancel()
        advanceUntilIdle()

        assertFalse(schedule.isArmed)
        assertTrue("a cancelled re-query must not fire", fired.isEmpty())

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L), bindingEpoch = 1L)
        advanceUntilIdle()
        assertEquals(1, fired.size)
        processJob.cancel()
    }

    @Test
    fun ifStale_isDebouncedForTenSeconds() = runTest {
        var now = 0L
        val schedule = RecheckSchedule(testScope(), { now }) { _, _, _ -> }

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
        val schedule = RecheckSchedule(testScope(), { now }) { _, _, _ -> }
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
        val schedule = RecheckSchedule(testScope(), { now }) { _, _, _ -> }

        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 60_000L), bindingEpoch = 1L)
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
            RecheckSchedule(testScope(), { testScheduler.currentTime }) { intent, _, _ -> intents += intent }

        schedule.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 30_000L), bindingEpoch = 1L)
        schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L), bindingEpoch = 1L)
        advanceUntilIdle()

        // Running as IF_STALE would make the retry unable to grant at all, per the D23 table.
        assertEquals(listOf(RefreshIntent.FORCE_PREMIUM), intents)
        processJob.cancel()
    }

    @Test
    fun forcedIntents_bypassTheDebounce() = runTest {
        var now = 0L
        val schedule = RecheckSchedule(testScope(), { now }) { _, _, _ -> }
        schedule.recordQueryStarted()
        now = 1L

        assertFalse(schedule.shouldQuery(RefreshIntent.IF_STALE, QueryOrigin.CALLER))
        assertTrue(schedule.shouldQuery(RefreshIntent.FORCE_ENTITLEMENTS, QueryOrigin.CALLER))
        assertTrue(schedule.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER))
        processJob.cancel()
    }

    @Test
    fun foldingAt29Seconds_differsFromReschedulingInDeadlineAndNextBackoff() = runTest {
        val foldedTimes = mutableListOf<Long>()
        val rescheduledTimes = mutableListOf<Long>()
        val foldedIntents = mutableListOf<RefreshIntent>()
        val rescheduledIntents = mutableListOf<RefreshIntent>()
        val folded = scheduleUnder(foldedTimes, foldedIntents)
        val rescheduled = scheduleUnder(rescheduledTimes, rescheduledIntents)
        try {
            val first = RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 30_000L)
            folded.schedule(first, bindingEpoch = 1L)
            rescheduled.schedule(first, bindingEpoch = 1L)
            advanceTimeBy(29_000L)
            assertFalse(folded.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER))
            assertFalse(rescheduled.shouldQuery(RefreshIntent.FORCE_PREMIUM, QueryOrigin.CALLER))

            // deferUntilFloor folds into the active timer: attempts stays 1, due stays 30s.
            folded.deferUntilFloor(RefreshIntent.FORCE_PREMIUM, bindingEpoch = 1L)
            // A fresh schedule increments attempts to 2: max(1s remaining, 5s backoff) -> 34s.
            rescheduled.schedule(
                RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 1_000L),
                bindingEpoch = 1L
            )
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(listOf(30_000L), foldedTimes)
            assertTrue(rescheduledTimes.isEmpty())

            // Observe the attempt counts via the next retry's backoff, without exposing counters.
            // Folded path: attempts 1 -> 2, 30s + 5s = 35s.
            folded.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, 0L), bindingEpoch = 1L)
            advanceTimeBy(4_000L)
            runCurrent()
            assertEquals(listOf(34_000L), rescheduledTimes)
            // Rescheduled path: attempts 2 -> 3, 34s + 10s = 44s.
            rescheduled.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, 0L), bindingEpoch = 1L)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(listOf(30_000L, 35_000L), foldedTimes)
            assertEquals(listOf(34_000L), rescheduledTimes)
            advanceTimeBy(9_000L)
            runCurrent()
            assertEquals(listOf(34_000L, 44_000L), rescheduledTimes)
            assertEquals(List(2) { RefreshIntent.FORCE_PREMIUM }, foldedIntents)
            assertEquals(List(2) { RefreshIntent.FORCE_PREMIUM }, rescheduledIntents)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun aDeferredCallback_keepsItsOriginalEpochWhenDeliveryOutlivesCancellation() = runTest {
        val deliveredEpochs = mutableListOf<Long>()
        val releaseOldDelivery = CompletableDeferred<Unit>()
        var oldDeliveryStarted = false
        val schedule = RecheckSchedule(testScope(), { testScheduler.currentTime }) { _, _, epoch ->
            if (epoch == 2L) {
                oldDeliveryStarted = true
                // Model delivery that is already executing and cannot be recalled by cancel().
                withContext(NonCancellable) { releaseOldDelivery.await() }
            }
            deliveredEpochs += epoch
        }
        try {
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, 30_000L), bindingEpoch = 1L)
            advanceTimeBy(1_000L)
            schedule.cancel(preserveServerFloor = true)
            schedule.deferUntilFloor(RefreshIntent.FORCE_PREMIUM, bindingEpoch = 2L)
            advanceTimeBy(29_000L)
            runCurrent()
            assertTrue("the deferred callback never started", oldDeliveryStarted)

            schedule.cancel(preserveServerFloor = true)
            schedule.deferUntilFloor(RefreshIntent.FORCE_PREMIUM, bindingEpoch = 3L)
            runCurrent()
            assertEquals(listOf(3L), deliveredEpochs)
            releaseOldDelivery.complete(Unit)
            runCurrent()
            assertEquals("old delivery adopted the replacement epoch", listOf(3L, 2L), deliveredEpochs)
        } finally {
            releaseOldDelivery.complete(Unit)
            processJob.cancel()
        }
    }

    private fun TestScope.testScope(): CoroutineScope =
        CoroutineScope(processJob + StandardTestDispatcher(testScheduler))

    private fun TestScope.scheduleUnder(
        fired: MutableList<Long>,
        firedIntents: MutableList<RefreshIntent> = mutableListOf()
    ): RecheckSchedule =
        RecheckSchedule(testScope(), { testScheduler.currentTime }) { intent, _, _ ->
            fired += testScheduler.currentTime
            firedIntents += intent
        }

    // --- S1r-2a: fired state, completion and floor-only recording ---------------------------------------------------------

    @Test
    fun aCancellationAfterTheDelay_cannotDeliverTheOldCallbackInsideANewerFloor() = runTest {
        val reachedFireLock = CompletableDeferred<Unit>()
        val releaseFireLock = CompletableDeferred<Unit>()
        val fired = mutableListOf<Long>()
        var first = true
        val schedule = RecheckSchedule(
            testScope(),
            { testScheduler.currentTime },
            beforeFire = {
                if (first) {
                    first = false
                    // Models preemption after delay returned. Cancellation must not turn this
                    // test barrier into an extra cancellation check before the fire lock.
                    withContext(NonCancellable) {
                        reachedFireLock.complete(Unit)
                        releaseFireLock.await()
                    }
                }
            }
        ) { _, _, _ ->
            fired += testScheduler.currentTime
        }
        try {
            schedule.schedule(
                RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 1_000L),
                bindingEpoch = 7L
            )
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(reachedFireLock.isCompleted)

            schedule.recordFloorWithoutArming(minDelayMillis = 60_000L)
            releaseFireLock.complete(Unit)
            runCurrent()
            assertEquals(
                "the cancelled old callback did not cross the new floor",
                emptyList<Long>(),
                fired
            )

            val expected = testScheduler.currentTime + 60_000L
            schedule.schedule(
                RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L),
                bindingEpoch = 7L
            )
            advanceTimeBy(59_999L)
            runCurrent()
            assertEquals(emptyList<Long>(), fired)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(expected), fired)
        } finally {
            releaseFireLock.complete(Unit)
            processJob.cancel()
        }
    }

    @Test
    fun foldIntoUnfired_reachesATimerThatHasNotFired_andIsRefusedOnceItHas() = runTest {
        val fired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val intents = mutableListOf<RefreshIntent>()
        val schedule = RecheckSchedule(CoroutineScope(processJob + StandardTestDispatcher(testScheduler)), { testScheduler.currentTime }) { intent, _, _ ->
            intents += intent
            fired.complete(Unit)
            release.await()
        }
        try {
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 1_000L), bindingEpoch = 1L)
            assertTrue("not fired yet", schedule.foldIntoUnfired(RefreshIntent.FORCE_ENTITLEMENTS))
            advanceTimeBy(1_001L)
            runCurrent()
            assertTrue(fired.isCompleted)
            assertEquals(listOf(RefreshIntent.FORCE_ENTITLEMENTS), intents)
            assertFalse("a fired timer takes no fold", schedule.foldIntoUnfired(RefreshIntent.FORCE_PREMIUM))
        } finally {
            release.complete(Unit)
            processJob.cancel()
        }
    }

    @Test
    fun onSettled_reportsEachArmingOnce_withItsRevision_whetherItFired_wasReplaced_orWasCancelled() = runTest {
        val settled = mutableListOf<Pair<Long, Long>>()
        val schedule = RecheckSchedule(
            CoroutineScope(processJob + StandardTestDispatcher(testScheduler)),
            { testScheduler.currentTime },
            onSettled = { binding, revision -> settled += binding to revision }
        ) { _, _, _ -> }
        try {
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 1_000L), bindingEpoch = 7L)
            val first = schedule.revision
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 1_000L), bindingEpoch = 7L)
            val second = schedule.revision
            runCurrent()
            assertEquals("the replaced arming settled, unfired", listOf(7L to first), settled)

            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(listOf(7L to first, 7L to second), settled)
            assertEquals("a fired arming that settled is still the latest", second, schedule.revision)

            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 1_000L), bindingEpoch = 8L)
            val third = schedule.revision
            schedule.cancel()
            runCurrent()
            assertEquals(8L to third, settled.last())
            assertTrue("cancelling moved the revision past it", schedule.revision > third)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun recordFloorWithoutArming_cancelsWhatIsArmed_neverShortensTheFloor_andKeepsTheIntentForTheNextArming() = runTest {
        val fired = mutableListOf<Pair<Long, RefreshIntent>>()
        val schedule = RecheckSchedule(CoroutineScope(processJob + StandardTestDispatcher(testScheduler)), { testScheduler.currentTime }) { intent, _, _ ->
            fired += testScheduler.currentTime to intent
        }
        try {
            schedule.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 30_000L), bindingEpoch = 1L)
            schedule.recordFloorWithoutArming(minDelayMillis = 10_000L)
            advanceTimeBy(15_000L)
            runCurrent()
            assertTrue("the 30s floor was not shortened to 10s", schedule.floorBlocksNow())
            advanceTimeBy(105_000L)
            runCurrent()
            assertEquals("nothing armed any more", emptyList<Pair<Long, RefreshIntent>>(), fired)

            val now = testScheduler.currentTime
            schedule.recordFloorWithoutArming(minDelayMillis = 45_000L)
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 0L), bindingEpoch = 1L)
            advanceTimeBy(45_000L)
            runCurrent()
            assertEquals("fired at the recorded floor, with the premium intent kept", listOf(now + 45_000L to RefreshIntent.FORCE_PREMIUM), fired)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun recordFloorWithoutArming_cancelsATimerThatHasAlreadyFired() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var cancelled = false
        val schedule = RecheckSchedule(CoroutineScope(processJob + StandardTestDispatcher(testScheduler)), { testScheduler.currentTime }) { _, _, _ ->
            started.complete(Unit)
            try {
                never.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
        try {
            schedule.schedule(RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L), bindingEpoch = 1L)
            runCurrent()
            assertTrue(started.isCompleted)
            schedule.recordFloorWithoutArming(minDelayMillis = 10_000L)
            runCurrent()
            assertTrue("the running re-query does not run on inside the new floor", cancelled)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun aReplacedArmingThatSettlesLate_leavesItsSuccessorCancellable() = runTest {
        val fired = mutableListOf<Long>()
        val schedule = RecheckSchedule(CoroutineScope(processJob + StandardTestDispatcher(testScheduler)), { testScheduler.currentTime }) { _, _, _ ->
            fired += testScheduler.currentTime
        }
        try {
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 10_000L), bindingEpoch = 1L)
            schedule.schedule(RecheckRequest(RefreshIntent.IF_STALE, minDelayMillis = 10_000L), bindingEpoch = 1L)
            runCurrent()
            assertTrue(
                "late cleanup did not detach the successor: it still accepts a pending upgrade",
                schedule.foldIntoUnfired(RefreshIntent.FORCE_PREMIUM)
            )
            schedule.cancel()
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals("the successor was still the one cancel reached", emptyList<Long>(), fired)
        } finally {
            processJob.cancel()
        }
    }

    @Test
    fun anAtomicZeroDelayArmingCancelledBeforeDispatch_settlesWithoutCallingOnDue() = runTest {
        val fired = mutableListOf<Long>()
        val settled = mutableListOf<Pair<Long, Long>>()
        val schedule = RecheckSchedule(
            testScope(),
            { testScheduler.currentTime },
            onSettled = { binding, revision -> settled += binding to revision }
        ) { _, _, _ ->
            fired += testScheduler.currentTime
        }
        try {
            schedule.schedule(
                RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L),
                bindingEpoch = 7L
            )
            val revision = schedule.revision
            processJob.cancel()
            runCurrent()

            assertEquals(emptyList<Long>(), fired)
            assertEquals(listOf(7L to revision), settled)
        } finally {
            processJob.cancel()
        }
    }
}
