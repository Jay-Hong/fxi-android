package com.jay.fxi.data.free

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.AuthenticatedApiException
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import okhttp3.Headers
import com.jay.fxi.data.entitlements.AuthUidStream
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event loop's boundaries.
 *
 * Every advance here is **bounded**. The loop re-arms itself after each completion, so a stale
 * answer produces an endless retry ladder and `advanceUntilIdle()` would never return — a hang, not
 * a failure, which is the worst way for a guard to be missing.
 */
class FreeSnapshotSchedulerTest {

    private companion object {
        const val TAB = "usd"
        const val INSTALL = "install-a"
        val PERIOD = GraphPeriod.THREE_MONTHS
        val KEY = FreeSnapshotKey(TAB, PERIOD)

        /** 11:31 KST — one minute past a publish slot. */
        val T0: Instant = Instant.parse("2026-09-06T02:31:00Z")
        val CURRENT_BASIS: Instant = Instant.parse("2026-09-06T02:30:00Z")
        val PREVIOUS_BASIS: Instant = Instant.parse("2026-09-06T01:30:00Z")
        val TWO_BACK: Instant = Instant.parse("2026-09-06T00:30:00Z")

        val JITTER_MS = FreeSnapshotSchedulePolicy.jitterFor(INSTALL, TAB).inWholeMilliseconds
    }

    // --- fixture ----------------------------------------------------------------------------------

    /** [nth] is the attempt number *for this key*, so a responder is not thrown off by warm-ups. */
    data class Call(val index: Int, val nth: Int, val atMs: Long, val key: FreeSnapshotKey)

    private class FakeFetcher(private val nowMs: () -> Long) : FreeSnapshotFetching {
        val calls = mutableListOf<Call>()
        val callTimes: List<Long> get() = calls.map { it.atMs }
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var parkAll = false
        var parkWhen: (Call) -> Boolean = { false }
        val parked = mutableListOf<Call>()
        var respond: (Call) -> Result<FreeSnapshot> = { Result.failure(IllegalStateException("no responder")) }

        fun gateFor(call: Int): CompletableDeferred<Unit> = gates.getOrPut(call) { CompletableDeferred() }
        fun countFor(period: GraphPeriod) = calls.count { it.key.period == period }
        fun timesFor(period: GraphPeriod) = calls.filter { it.key.period == period }.map { it.atMs }
        fun releaseParked() = parked.toList().forEach { gateFor(it.index).complete(Unit) }

        override suspend fun fetch(tab: String, period: GraphPeriod): FreeSnapshot {
            val key = FreeSnapshotKey(tab, period)
            val call = Call(calls.size + 1, calls.count { it.key == key } + 1, nowMs(), key)
            calls += call
            if (parkAll || parkWhen(call) || gates.containsKey(call.index)) {
                parked += call
                gateFor(call.index).await()
            }
            return respond(call).getOrThrow()
        }
    }

    private class Fixture(test: TestScope) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val fetcher = FakeFetcher { test.testScheduler.currentTime }

        /** The live credential session, as `AuthTokenProvider.currentIdentityFence()` reports it. */
        var fence: AuthIdentityFence? = AuthIdentityFence("u1", 1L)

        /** Set to make the loop's own clock throw, to prove one bad event does not end the loop. */
        var clockThrows = false

        /** Collects what the loop could not process. */
        var onFailure: (Throwable) -> Unit = {}

        /** Throw on exactly one clock read, so the failing event can be a timer wake. */
        var throwOnClockRead: Int? = null
        var clockReads = 0
        val scheduler = FreeSnapshotScheduler(
            fetcher = fetcher,
            // Identity is driven explicitly below; the production stream is bound in DI.
            uidStream = AuthUidStream { },
            scope = scope,
            authFence = { fence },
            onEventFailure = { onFailure(it) },
            clock = {
                clockReads++
                if (clockThrows) throw IllegalStateException("poisoned event")
                if (clockReads == throwOnClockRead) {
                    throwOnClockRead = null
                    throw IllegalStateException("poisoned timer event")
                }
                T0 + test.testScheduler.currentTime.milliseconds
            },
            installId = { INSTALL }
        )

        fun startSignedIn(uid: String = "u1") {
            scheduler.start()
            scheduler.onIdentityChanged(uid)
            scheduler.onActivated(TAB, PERIOD)
        }

        fun close() = scope.cancel()
    }

    private fun snap(
        asOf: Instant,
        refreshNotBefore: Instant?,
        key: FreeSnapshotKey = KEY
    ) = FreeSnapshot(
        tab = key.tab,
        period = key.period,
        asOf = asOf,
        generatedAt = asOf + 19.seconds,
        refreshNotBefore = refreshNotBefore,
        rate = FreeRate.Flat(asset = "usd-krw", entries = emptyList()),
        graph = FreeGraph(bucketSize = null, series = emptyList())
    )

    /** Answer every key with the same basis, so a test that is not about content stays quiet. */
    private fun always(asOf: Instant, refreshNotBefore: Instant?): (Call) -> Result<FreeSnapshot> =
        { c -> Result.success(snap(asOf, refreshNotBefore, c.key)) }

    // --- activation ---------------------------------------------------------------------------------

    @Test
    fun readStatePublishesOwnerAndEntriesTogether_onUidChangeAndSignOut() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 1.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals("u1", f.scheduler.readState.value.uid)
            assertEquals(f.scheduler.readState.value.entries, f.scheduler.readState.value.entries)
            assertTrue(f.scheduler.readState.value.entries.isNotEmpty())

            f.fetcher.parkAll = true
            f.fence = AuthIdentityFence("u2", 2L)
            f.scheduler.onIdentityChanged("u2")
            testScheduler.runCurrent()
            assertEquals(FreeSnapshotReadState("u2"), f.scheduler.readState.value)

            f.fence = null
            f.scheduler.onIdentityChanged(null)
            testScheduler.runCurrent()
            assertEquals(FreeSnapshotReadState(), f.scheduler.readState.value)
            f.fetcher.releaseParked()
            testScheduler.runCurrent()
            assertEquals(FreeSnapshotReadState(), f.scheduler.readState.value)
        } finally {
            f.close()
        }
    }

    @Test
    fun activatingWithNothingCached_fetchesOnceAndPublishes() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 1.hours)
            f.startSignedIn()
            testScheduler.runCurrent()

            assertEquals(listOf(0L), f.fetcher.timesFor(PERIOD))
            val entry = f.scheduler.readState.value.entries.getValue(KEY)
            assertEquals(CURRENT_BASIS, entry.snapshot.asOf)
            assertEquals(FreeSnapshotFreshness.FRESH, entry.freshness)
        } finally {
            f.close()
        }
    }

    /**
     * The sign-out direction of the same rule, and the one the plan's DoD is actually about
     * ("로그아웃 상태 API 호출 0"). The case below covers a surface that never had an identity;
     * this covers one that had a live schedule and lost it.
     *
     * The credential session is left **stale on purpose**. The identity stream and
     * `currentIdentityFence()` are different sources and the second lags, so there is a window in
     * which the app is signed out and a usable fence for the departed user is still on hand. With
     * the fence merely required to exist, that window mints a request from a signed-out app; it is
     * matching the fence *to the current identity* that closes it. Written with the fence already
     * null, this test passes no matter what the loop does — which is what it did on the first
     * attempt, and no mutant noticed.
     */
    @Test
    fun signingOutStopsEveryRequest_evenWhileTheCredentialSessionStillLags() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 1.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            val whileSignedIn = f.fetcher.calls.size
            assertTrue("nothing was scheduled to begin with", whileSignedIn > 0)

            f.scheduler.onIdentityChanged(null)
            testScheduler.runCurrent()
            assertEquals(FreeSnapshotReadState(), f.scheduler.readState.value)
            assertNotNull("the lag this test is about was not set up", f.fence)

            // Re-activation is what the surface does on every foreground and every settled tab, so
            // the loop is driven rather than merely left alone — an assertion that held only
            // because no wake ever arrived would prove nothing.
            f.scheduler.onActivated(TAB, PERIOD)
            testScheduler.runCurrent()
            // Past the refresh hint, the whole stale ladder and the 24h expiry wake.
            testScheduler.advanceTimeBy(48.hours.inWholeMilliseconds)
            testScheduler.runCurrent()
            assertEquals("a request was made with no identity", whileSignedIn, f.fetcher.calls.size)

            // Positive control: the machinery still works, so the count above is a refusal and not
            // a loop that quietly stopped.
            f.fence = AuthIdentityFence("u2", 1L)
            f.scheduler.onIdentityChanged("u2")
            testScheduler.runCurrent()
            assertTrue("signing back in did not resume", f.fetcher.calls.size > whileSignedIn)
        } finally {
            f.close()
        }
    }

    /** No identity, no request: the free endpoint is authenticated (ADR-039 Stage A). */
    @Test
    fun activatingBeforeSignIn_doesNotFetch_andTheFirstIdentityStartsIt() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 1.hours)
            f.scheduler.start()
            f.scheduler.onActivated(TAB, PERIOD)
            testScheduler.runCurrent()
            assertEquals(emptyList<Long>(), f.fetcher.timesFor(PERIOD))

            f.scheduler.onIdentityChanged("u1")
            testScheduler.runCurrent()
            assertEquals(listOf(0L), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    // --- the three-way apply -------------------------------------------------------------------------

    /**
     * `ANDROID_V2_PLAN.md:823` — "동일 `as_of`라도 새 `refresh_not_before` 반영", and iOS carries the
     * same rule as a Blocker comment: the data may be reused but **the response is replaced**,
     * because `refresh_not_before` is derived from the server's serve-time clock and moves even when
     * the snapshot does not. Keeping the old response would schedule from a hint already in the
     * past, which the `MIN_DELAY` clamp then turns into a five-second re-fetch loop.
     *
     * The short hints here are fixture values, chosen so both fetches land inside one basis hour —
     * that is the only window in which the same `as_of` comes back while still current.
     */
    @Test
    fun theSameAsOfReServed_reusesTheDataButTakesTheNewRefreshNotBefore() = runTest {
        val f = Fixture(this)
        try {
            val firstHint = T0 + 120.seconds
            val secondHint = T0 + 600.seconds
            f.fetcher.respond = { c ->
                Result.success(snap(CURRENT_BASIS, if (c.nth == 1) firstHint else secondHint, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(599_000)
            assertEquals(
                "the second fetch did not fire at the first hint",
                listOf(0L, 120_000 + JITTER_MS),
                f.fetcher.timesFor(PERIOD)
            )
            assertEquals(secondHint, f.scheduler.readState.value.entries.getValue(KEY).snapshot.refreshNotBefore)

            testScheduler.advanceTimeBy(2_000 + JITTER_MS)
            assertEquals(3, f.fetcher.timesFor(PERIOD).size)
            assertEquals(600_000 + JITTER_MS, f.fetcher.timesFor(PERIOD)[2])
        } finally {
            f.close()
        }
    }

    /** A late, older reply must not rewind what is on screen. */
    @Test
    fun anOlderAnswer_isDropped() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth >= 2) Result.success(snap(PREVIOUS_BASIS, T0 + 10.hours, c.key))
                else Result.success(snap(CURRENT_BASIS, T0 + 120.seconds, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(400_000)

            assertEquals(CURRENT_BASIS, f.scheduler.readState.value.entries.getValue(KEY).snapshot.asOf)

            // An answer we could not use is no progress, so the ladder runs — 20/40/80/300 from the
            // moment of each rejection. The alternative is scheduling from the kept snapshot, whose
            // hint is already behind us: the policy's positive minimum would make that a five-second
            // re-request, repeated for as long as the server keeps answering with the past.
            assertEquals(
                listOf(0L, 120_000 + JITTER_MS, 140_000 + JITTER_MS, 180_000 + JITTER_MS, 260_000 + JITTER_MS),
                f.fetcher.timesFor(PERIOD)
            )
        } finally {
            f.close()
        }
    }

    // --- the stale ladder ------------------------------------------------------------------------------

    @Test
    fun aStaleAnswerThatNeverProgresses_climbsTwentyFortyEightyThenSettles() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(PREVIOUS_BASIS, T0 + 1.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(500_000)

            assertEquals(
                listOf(0L, 20_000L, 60_000L, 140_000L, 440_000L),
                f.fetcher.timesFor(PERIOD)
            )
        } finally {
            f.close()
        }
    }

    /** Movement that is still behind restarts the ladder rather than continuing to climb it. */
    @Test
    fun aStaleAnswerThatAdvanced_restartsTheLadder() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                Result.success(snap(if (c.key.period == PERIOD && c.nth <= 2) TWO_BACK else PREVIOUS_BASIS, T0 + 1.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(100_000)

            // 80_000 is the restart. 140_000 would be the ladder continuing to its third step.
            assertEquals(listOf(0L, 20_000L, 60_000L, 80_000L), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /** A current answer takes the one-shot path — the ladder must not engage at all. */
    @Test
    fun aCurrentAnswer_waitsForTheHint_notForTheLadder() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 1.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(60_000)

            assertEquals(listOf(0L), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /** A failed fetch is not a stale success: recovery, not the ladder's first rung. */
    @Test
    fun aFailedFetch_waitsTheRecoveryDelay() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(IOException("boom"))
                else Result.success(snap(CURRENT_BASIS, T0 + 10.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(400_000)

            assertEquals(listOf(0L, 300_000L + JITTER_MS), f.fetcher.timesFor(PERIOD))
            assertNotEquals(20_000L, f.fetcher.timesFor(PERIOD)[1])
            assertEquals(CURRENT_BASIS, f.scheduler.readState.value.entries.getValue(KEY).snapshot.asOf)
        } finally {
            f.close()
        }
    }

    // --- in-flight ownership ---------------------------------------------------------------------------

    /**
     * Parking the *first* request would prove nothing: with no cached entry and no deadline there is
     * no timer to fire and nothing to deduplicate against, so the guard could be deleted and the
     * count would still be one. This parks a **due refresh** over a warm cache, then delivers
     * repeated activations and crosses the freshness boundary on top of it.
     */
    @Test
    fun whileADueRefreshIsInFlight_nothingElseStartsForThatKey() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkWhen = { it.key.period == PERIOD && it.nth == 2 }  // the refresh parks
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 120.seconds)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(1, f.fetcher.timesFor(PERIOD).size)

            testScheduler.advanceTimeBy(130_000 + JITTER_MS)
            assertEquals("the refresh never became due", 2, f.fetcher.timesFor(PERIOD).size)

            // Tab round-trips while it is genuinely still out. The window stays inside the fetch
            // budget on purpose: past it the watchdog bounds the call, and "in flight" — the thing
            // being tested — stops being true. The badge boundary is
            // [theDelayedBadgeTurnsOnWithoutAnyFetch]'s job.
            repeat(3) {
                f.scheduler.onDeactivated()
                f.scheduler.onActivated(TAB, PERIOD)
                testScheduler.runCurrent()
            }
            testScheduler.advanceTimeBy(20_000)

            assertEquals(2, f.fetcher.timesFor(PERIOD).size)
        } finally {
            f.close()
        }
    }

    /**
     * A withdrawn fetch is not a failed one — but nothing else resumes it. `AuthIdentityChangedException`
     * is a `CancellationException`, and a credential-generation change leaves the uid untouched, so no
     * identity event arrives to restart the work.
     */
    @Test
    fun aCancelledFetch_retriesWithoutEarningTheRecoveryDelay() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(AuthIdentityChangedException())
                else Result.success(snap(CURRENT_BASIS, T0 + 10.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(listOf(0L), f.fetcher.timesFor(PERIOD))

            testScheduler.advanceTimeBy(10_000)
            assertEquals("a cancelled fetch stranded the key", listOf(0L, 5_000L), f.fetcher.timesFor(PERIOD))
            assertNotEquals("cancellation was charged the recovery delay", 300_000L, f.fetcher.timesFor(PERIOD)[1])
            assertEquals(CURRENT_BASIS, f.scheduler.readState.value.entries.getValue(KEY).snapshot.asOf)
        } finally {
            f.close()
        }
    }

    /**
     * An identity change discards the answer in flight — but the request must still release **its
     * own** registration, or the key is wedged for the life of the process, and it must not release
     * the registration the replacement request now owns.
     */
    @Test
    fun anIdentityChangeMidFlight_discardsTheAnswerWithoutWedgingTheKey() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkAll = true
            f.fetcher.respond = { c ->
                Result.success(snap(if (c.index == 1) PREVIOUS_BASIS else CURRENT_BASIS, T0 + 10.hours, c.key))
            }
            f.startSignedIn("u1")
            testScheduler.runCurrent()
            assertEquals(1, f.fetcher.timesFor(PERIOD).size)

            // Firebase moves the user, so the live session moves with the uid — the loop is never
            // asked to mint a fence for a uid it does not believe in.
            f.fence = AuthIdentityFence("u2", 1L)
            f.scheduler.onIdentityChanged("u2")
            testScheduler.runCurrent()
            assertEquals("the replacement request never started", 2, f.fetcher.timesFor(PERIOD).size)

            // The first user's answer lands late. It is discarded, and it does not free the slot
            // the second request holds.
            f.fetcher.gateFor(1).complete(Unit)
            testScheduler.runCurrent()
            assertTrue("u1's answer was applied to u2", f.scheduler.readState.value.entries.isEmpty())

            f.scheduler.onActivated(TAB, PERIOD)
            testScheduler.runCurrent()
            assertEquals(
                "the late answer released the registration the live request owns",
                2,
                f.fetcher.timesFor(PERIOD).size
            )

            f.fetcher.gateFor(3).complete(Unit)   // u2's own request for the selected period
            testScheduler.runCurrent()
            assertEquals(CURRENT_BASIS, f.scheduler.readState.value.entries.getValue(KEY).snapshot.asOf)
        } finally {
            f.close()
        }
    }

    /** Firebase replays the signed-in user on subscribe; that must not throw away a good snapshot. */
    @Test
    fun theSameIdentityReplayed_keepsTheCacheAndStartsNoFetch() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 10.hours)
            f.startSignedIn("u1")
            testScheduler.runCurrent()
            assertEquals(1, f.fetcher.timesFor(PERIOD).size)

            f.scheduler.onIdentityChanged("u1")
            testScheduler.runCurrent()

            assertEquals(1, f.fetcher.timesFor(PERIOD).size)
            assertEquals(CURRENT_BASIS, f.scheduler.readState.value.entries.getValue(KEY).snapshot.asOf)
        } finally {
            f.close()
        }
    }

    // --- deactivation and the freshness clock ------------------------------------------------------------

    @Test
    fun whileDeactivated_noFetchRuns_andActivatingAgainRunsTheDueOne() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 120.seconds)
            f.startSignedIn()
            testScheduler.runCurrent()

            f.scheduler.onDeactivated()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(200_000)
            assertEquals("a fetch ran with nothing on screen", listOf(0L), f.fetcher.timesFor(PERIOD))

            f.scheduler.onActivated(TAB, PERIOD)
            testScheduler.runCurrent()
            assertEquals(listOf(0L, 200_000L), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /**
     * Nothing in the app emits merely because time passed, so the badge has to be woken for. The
     * snapshot below is never re-fetched — only its appearance changes.
     */
    @Test
    fun theDelayedBadgeTurnsOnWithoutAnyFetch() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 10.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(FreeSnapshotFreshness.FRESH, f.scheduler.readState.value.entries.getValue(KEY).freshness)

            // as_of is one minute behind T0, so the two-hour boundary is at T0 + 1h59m.
            testScheduler.advanceTimeBy((1.hours + 58.minutes).inWholeMilliseconds)
            assertEquals(FreeSnapshotFreshness.FRESH, f.scheduler.readState.value.entries.getValue(KEY).freshness)

            testScheduler.advanceTimeBy(2.minutes.inWholeMilliseconds)
            assertEquals(FreeSnapshotFreshness.DELAYED, f.scheduler.readState.value.entries.getValue(KEY).freshness)
            assertEquals(listOf(0L), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    @Test
    fun anExpiredSnapshotIsStillHeld_butReadsUnavailable() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 100.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(24.hours.inWholeMilliseconds)

            val entry = f.scheduler.readState.value.entries.getValue(KEY)
            assertEquals(FreeSnapshotFreshness.UNAVAILABLE, entry.freshness)
            assertNull(FreeSnapshotSchedulePolicy.nextFreshnessDeadline(entry.snapshot.asOf, T0 + 24.hours))
        } finally {
            f.close()
        }
    }

    // --- warming the other periods (ANDROID_V2_PLAN.md:820) --------------------------------------

    /**
     * The selected period first, then its siblings one at a time in enum order. Sequential matters:
     * four concurrent requests on a metered connection is what the plan's "순차 preload" rules out.
     */
    @Test
    fun activatingWarmsTheOtherPeriods_oneAtATime_afterTheSelectedOne() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 10.hours)
            f.startSignedIn()
            testScheduler.runCurrent()

            assertEquals(
                listOf(
                    GraphPeriod.THREE_MONTHS,   // selected
                    GraphPeriod.ONE_DAY,
                    GraphPeriod.ONE_WEEK,
                    GraphPeriod.ONE_YEAR
                ),
                f.fetcher.calls.map { it.key.period }
            )
            assertEquals(4, f.scheduler.readState.value.entries.size)
            // Never more than one warm-up outstanding: each sibling started only once the previous
            // one had reported, so no two share a start instant beyond the selected period's.
            assertEquals(1, f.fetcher.calls.count { it.key.period == GraphPeriod.ONE_WEEK })
        } finally {
            f.close()
        }
    }

    /** A warm-up fills a gap; only the selected period is on a refresh schedule. */
    @Test
    fun aWarmedPeriodIsNeverRefreshed_onlyTheSelectedOne() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 120.seconds)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(4, f.fetcher.calls.size)

            testScheduler.advanceTimeBy(400_000)

            assertEquals(1, f.fetcher.countFor(GraphPeriod.ONE_DAY))
            assertEquals(1, f.fetcher.countFor(GraphPeriod.ONE_WEEK))
            assertEquals(1, f.fetcher.countFor(GraphPeriod.ONE_YEAR))
            assertTrue(
                "the selected period stopped refreshing",
                f.fetcher.countFor(GraphPeriod.THREE_MONTHS) > 1
            )
        } finally {
            f.close()
        }
    }

    /** Only a real failure cools a warm-up down, and it cools down for the recovery delay. */
    @Test
    fun aFailedWarmUp_waitsTheRecoveryDelayBeforeItIsTriedAgain() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == GraphPeriod.ONE_DAY && c.nth == 1) Result.failure(IOException("boom"))
                else Result.success(snap(CURRENT_BASIS, T0 + 120.seconds, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(1, f.fetcher.countFor(GraphPeriod.ONE_DAY))

            // Activations and a selected-period refresh both re-evaluate — neither may shorten it.
            testScheduler.advanceTimeBy(290_000)
            repeat(3) { f.scheduler.onActivated(TAB, PERIOD); testScheduler.runCurrent() }
            assertEquals(
                "an activation pulled the warm-up cooldown in",
                1,
                f.fetcher.countFor(GraphPeriod.ONE_DAY)
            )

            testScheduler.advanceTimeBy(120_000)
            assertTrue(
                "the warm-up was never retried after its cooldown",
                f.fetcher.countFor(GraphPeriod.ONE_DAY) > 1
            )
        } finally {
            f.close()
        }
    }

    /**
     * Cancellation is not failure. Putting a withdrawn warm-up on the 300s cooldown would block a
     * period that was never actually attempted.
     *
     * A sibling carries no timer of its own, so the retry rides the next event rather than a wake
     * of its own — 20s later an activation finds it eligible. The contrast is
     * [aFailedWarmUp_waitsTheRecoveryDelay], where the same activation at 290s finds it is not.
     */
    @Test
    fun aCancelledWarmUp_isNotPutOnTheFailureCooldown() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == GraphPeriod.ONE_DAY && c.nth == 1) {
                    Result.failure(AuthIdentityChangedException())
                } else {
                    Result.success(snap(CURRENT_BASIS, T0 + 10.hours, c.key))
                }
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(1, f.fetcher.countFor(GraphPeriod.ONE_DAY))

            testScheduler.advanceTimeBy(20_000)
            f.scheduler.onActivated(TAB, PERIOD)
            testScheduler.runCurrent()
            assertTrue(
                "a withdrawn warm-up was charged the failure cooldown",
                f.fetcher.countFor(GraphPeriod.ONE_DAY) > 1
            )
        } finally {
            f.close()
        }
    }

    /** Leaving a tab stops warming the rest of it — "선택 data 탭만 활성". */
    @Test
    fun switchingTabs_stopsWarmingTheOneLeftBehind() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkWhen = { it.key.period == GraphPeriod.ONE_DAY }  // park mid-pass
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 10.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(2, f.fetcher.calls.size)

            f.scheduler.onActivated("jpy", PERIOD)
            testScheduler.runCurrent()
            f.fetcher.releaseParked()
            testScheduler.runCurrent()

            val leftBehind = f.fetcher.calls.filter { it.key.tab == TAB && it.index > 2 }
            assertEquals("the abandoned tab kept warming: $leftBehind", emptyList<Call>(), leftBehind)
            assertTrue(f.fetcher.calls.any { it.key.tab == "jpy" })
        } finally {
            f.close()
        }
    }

    /**
     * A tab the user has left may not hold the tab the user is on.
     *
     * The limit is one warm-up per **tab**, because the tab is the unit the plan gives a cache and
     * a schedule to, and iOS reaches the same shape by giving each tab its own view model with its
     * own `preloadTask` — it has no cross-tab limit to mirror. A global limit let a request left
     * behind by a tab switch stall warming on the new tab for that request's whole lifetime, and
     * with no `callTimeout` on the client nobody owns that duration. The selected period was never
     * blocked, so the symptom is a period switch that spins instead of swapping.
     *
     * Both halves are asserted: the abandoned tab's request no longer blocks, and the limit is
     * scoped rather than lifted — jpy's own warm-up still holds jpy's next one.
     */
    @Test
    fun aRequestLeftBehindByATabSwitch_doesNotBlockTheNewTabsWarmUps() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkWhen = { it.key.period == GraphPeriod.ONE_DAY }   // one stuck per tab
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 10.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            // usd: the selected period answered, its first warm-up is out and never returns.
            assertEquals(2, f.fetcher.calls.size)

            f.scheduler.onActivated("jpy", PERIOD)
            testScheduler.runCurrent()

            // The stranded usd request does not gate jpy: the selected period *and* a warm-up ran.
            assertEquals(
                "jpy warming waited on a request the user walked away from",
                listOf(PERIOD, GraphPeriod.ONE_DAY),
                f.fetcher.calls.filter { it.key.tab == "jpy" }.map { it.key.period }
            )

            // Scoped, not lifted: jpy's own outstanding warm-up still holds the next one, and
            // releasing it lets the rest of the pass follow in order.
            f.fetcher.gateFor(f.fetcher.parked.single { it.key.tab == "jpy" }.index).complete(Unit)
            testScheduler.runCurrent()
            assertEquals(
                listOf(PERIOD, GraphPeriod.ONE_DAY, GraphPeriod.ONE_WEEK, GraphPeriod.ONE_YEAR),
                f.fetcher.calls.filter { it.key.tab == "jpy" }.map { it.key.period }
            )

            // And the tab left behind still never resumes: only the one request already out.
            assertEquals(
                "the abandoned tab kept warming",
                listOf(PERIOD, GraphPeriod.ONE_DAY),
                f.fetcher.calls.filter { it.key.tab == TAB }.map { it.key.period }
            )
        } finally {
            f.close()
        }
    }

    /** The period on screen is never queued behind a warm-up. */
    @Test
    fun aStuckWarmUp_doesNotBlockTheSelectedPeriodsRefresh() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkWhen = { it.key.period == GraphPeriod.ONE_DAY }  // never returns
            // The refresh falls due inside the fetch budget, so the warm-up is provably still in
            // flight when it runs. Past the budget the watchdog would have freed it and the test
            // would prove nothing.
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 20.seconds)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(2, f.fetcher.calls.size)

            testScheduler.advanceTimeBy(30_000 + JITTER_MS)

            assertTrue(
                "the selected period was blocked behind a stuck warm-up",
                f.fetcher.countFor(GraphPeriod.THREE_MONTHS) > 1
            )
            // …and the pass does not run ahead: warming stays one at a time even as the selected
            // period's own refreshes keep completing and re-evaluating.
            assertEquals(
                "a second warm-up started while the first was still out",
                0,
                f.fetcher.countFor(GraphPeriod.ONE_WEEK)
            )
        } finally {
            f.close()
        }
    }

    // --- boundaries the actor cannot serialize on its own -----------------------------------------

    private fun http(status: Int, retryAfter: String?) = AuthenticatedApiException(
        AuthenticatedHttpFailure(
            statusCode = status,
            headers = Headers.Builder().apply { retryAfter?.let { add("Retry-After", it) } }.build(),
            rawBody = ByteArray(0),
            error = null,
            detail = null,
            kind = AuthenticatedFailureKind.OTHER_HTTP
        )
    )

    /**
     * A same-uid credential rotation emits no identity event — `AuthUidStream` carries a bare uid and
     * the loop dedups equal ones — so the registration check cannot see it. The rotation here happens
     * at the fetch's return boundary, which is the real window: after the service's own
     * `requireCurrent` has already passed, before the loop dequeues the answer.
     */
    @Test
    fun anAnswerFromASupersededCredentialSession_isNotPublished() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) {
                    f.fence = AuthIdentityFence("u1", 2L)   // rotation, same human being
                    Result.success(snap(PREVIOUS_BASIS, T0 + 10.hours, c.key))
                } else {
                    Result.success(snap(CURRENT_BASIS, T0 + 10.hours, c.key))
                }
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            assertNull(
                "an answer authorised under a superseded session was published",
                f.scheduler.readState.value.entries[KEY]
            )

            // Withdrawn, not failed: the 5s floor, then the live session's own answer lands.
            testScheduler.advanceTimeBy(10_000)
            assertEquals(listOf(0L, 5_000L), f.fetcher.timesFor(PERIOD))
            assertEquals(CURRENT_BASIS, f.scheduler.readState.value.entries.getValue(KEY).snapshot.asOf)
        } finally {
            f.close()
        }
    }

    /** D12: a `Retry-After` that is present is a lower bound, and is never shortened. */
    @Test
    fun aServerStatedRetryAfter_isHonouredAsAFloor() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(429, "900"))
                else Result.success(snap(CURRENT_BASIS, T0 + 10.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(300_000 + JITTER_MS + 1_000)
            assertEquals(
                "the server's floor was undercut by the local recovery delay",
                listOf(0L),
                f.fetcher.timesFor(PERIOD)
            )

            testScheduler.advanceTimeBy(700_000)
            assertEquals(listOf(0L, 900_000L + JITTER_MS), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /** D12's other half: an absent header is not terminal, it just leaves the recovery delay. */
    @Test
    fun anAbsentRetryAfter_leavesTheRecoveryDelayStanding() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(429, null))
                else Result.success(snap(CURRENT_BASIS, T0 + 10.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(400_000)

            assertEquals(listOf(0L, 300_000L + JITTER_MS), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /**
     * Even an implausible floor is obeyed rather than quietly shortened. Two ceilings were tried and
     * both shortened a *valid* wait; what the screen shows is `now - as_of`, but this governs
     * whether a request goes out, and the server is who the floor protects. Failing by going quiet
     * is the safe direction — the other way round hits a server that asked for nothing.
     */
    @Test
    fun anAbsurdRetryAfter_isObeyedRatherThanQuietlyShortened() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(503, "999999999"))
                else Result.success(snap(CURRENT_BASIS, T0 + 10.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(25.hours.inWholeMilliseconds)

            assertEquals(
                "an implausible floor was capped instead of obeyed",
                listOf(0L),
                f.fetcher.timesFor(PERIOD)
            )
        } finally {
            f.close()
        }
    }

    /**
     * The HTTP client sets no `callTimeout`, and `rearmTimer` excludes an in-flight key by design —
     * so without a deadline of its own a hung call freezes the selected period for the life of the
     * process. The timeout is ours, not a withdrawal, so it earns the recovery delay and not the 5s
     * cancel floor: a slow server must not be re-hit every five seconds.
     */
    @Test
    fun aHungFetch_isBoundedAndChargedTheRecoveryDelay() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkWhen = { it.key.period == PERIOD && it.nth == 1 }
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 10.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(1, f.fetcher.countFor(PERIOD))

            testScheduler.advanceTimeBy(400_000)
            assertEquals(
                "a hung call was never bounded, or was charged the 5s cancel floor",
                listOf(0L, 60_000L + 300_000L + JITTER_MS),
                f.fetcher.timesFor(PERIOD)
            )
        } finally {
            f.close()
        }
    }

    /**
     * Single consumer: an escaping throw does not fail one operation, it ends the loop and leaves the
     * free tier dark for the life of the process while the channel still accepts sends.
     */
    @Test
    fun oneThrowingEvent_doesNotEndTheLoop() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 10.hours)
            f.scheduler.start()
            f.scheduler.onIdentityChanged("u1")
            testScheduler.runCurrent()

            f.clockThrows = true
            f.scheduler.onActivated(TAB, PERIOD)
            testScheduler.runCurrent()
            assertEquals(emptyList<Long>(), f.fetcher.timesFor(PERIOD))

            f.clockThrows = false
            f.scheduler.onActivated(TAB, PERIOD)
            testScheduler.runCurrent()

            assertEquals(
                "the loop died on a poisoned event",
                1,
                f.fetcher.countFor(PERIOD)
            )
            assertEquals(CURRENT_BASIS, f.scheduler.readState.value.entries.getValue(KEY).snapshot.asOf)
        } finally {
            f.close()
        }
    }

    // --- what a refused answer still owes the server ------------------------------------------------

    /**
     * A ceiling at one publish cycle would have shortened this to ~1h. A rate limit is levied on the
     * transport and a new publish slot does not lift it, so shortening a plausible floor is the very
     * thing D12's "하한으로 존중" forbids.
     */
    @Test
    fun aRetryAfterBeyondOnePublishCycle_isStillHonouredInFull() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(429, "7200"))
                else Result.success(snap(CURRENT_BASIS, T0 + 100.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(3_700_000)   // past a one-hour ceiling
            assertEquals("a plausible floor was shortened", listOf(0L), f.fetcher.timesFor(PERIOD))

            testScheduler.advanceTimeBy(4_000_000)
            // Only the first retry is this test's business; past it the answer is a basis behind and
            // the stale ladder legitimately takes over.
            assertEquals(
                listOf(0L, 7_200_000L + JITTER_MS),
                f.fetcher.timesFor(PERIOD).take(2)
            )
        } finally {
            f.close()
        }
    }

    /**
     * Refusing the *answer* is not a reason to refuse the *rate limit*. The 429 here arrives on a
     * response the fence rejects, and the 5s cancel floor must not be what schedules the next one.
     */
    @Test
    fun aRateLimitSurvivesTheCredentialRotationThatRefusedItsAnswer() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) {
                    f.fence = AuthIdentityFence("u1", 2L)
                    Result.failure(http(429, "900"))
                } else {
                    Result.success(snap(CURRENT_BASIS, T0 + 100.hours, c.key))
                }
            }
            f.startSignedIn()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(60_000)
            assertEquals(
                "the cancel floor undercut a live rate limit",
                listOf(0L),
                f.fetcher.timesFor(PERIOD)
            )

            testScheduler.advanceTimeBy(900_000)
            // Jitter rides on top of the floor here too: a rate limiter hands every client the same
            // value, so honouring it exactly would release the whole cohort on one second.
            assertEquals(listOf(0L, 900_000L + JITTER_MS), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /**
     * `null == null` is two unrelated absences, not a match — the monotonic-generation argument that
     * makes the fence comparison sound says nothing across them. So a request is never minted
     * without a session, and the answer to one cannot be admitted by a signed-out loop.
     */
    @Test
    fun noRequestIsMintedWithoutACredentialSession() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 100.hours)
            f.fence = null                      // uid event has landed; the transport has no session
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(emptyList<Long>(), f.fetcher.timesFor(PERIOD))

            // Re-armed rather than stranded: the race resolves and the next wake picks it up.
            f.fence = AuthIdentityFence("u1", 1L)
            testScheduler.advanceTimeBy(10_000)
            assertEquals(listOf(5_000L), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /**
     * Containment is not enough on its own. `handle` arms the timer on its way out, so an event that
     * throws before that point leaves the consumer alive with **nothing scheduled** — and the failing
     * event can itself be the timer wake, which is the case no externally-driven test would reach.
     */
    @Test
    fun aTimerEventThatThrows_doesNotSilentlyEndTheSchedule() = runTest {
        val f = Fixture(this)
        try {
            val seen = mutableListOf<Throwable>()
            f.onFailure = { seen += it }
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 100.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(FreeSnapshotFreshness.FRESH, f.scheduler.readState.value.entries.getValue(KEY).freshness)

            // Poison the next event, which is the 2h freshness wake — nothing external follows it.
            f.throwOnClockRead = f.clockReads + 1
            testScheduler.advanceTimeBy(25.hours.inWholeMilliseconds)

            assertEquals(
                "the schedule died with the consumer still alive",
                FreeSnapshotFreshness.UNAVAILABLE,
                f.scheduler.readState.value.entries.getValue(KEY).freshness
            )
            assertEquals("the dropped event was never reported", 1, seen.size)
        } finally {
            f.close()
        }
    }

    /** A 48-hour hold-off is a valid wait, and a ceiling anywhere below it shortens a valid floor. */
    @Test
    fun aRetryAfterBeyondADay_isStillHonouredInFull() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(429, "172800"))
                else Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(25.hours.inWholeMilliseconds)
            assertEquals(
                "a 48h floor was shortened to a day",
                listOf(0L),
                f.fetcher.timesFor(PERIOD)
            )

            testScheduler.advanceTimeBy(24.hours.inWholeMilliseconds)
            assertEquals(
                listOf(0L, 48.hours.inWholeMilliseconds + JITTER_MS),
                f.fetcher.timesFor(PERIOD).take(2)
            )
        } finally {
            f.close()
        }
    }

    /**
     * `rearmTimer` returning normally is not the same as having armed something — with no cache
     * there is no freshness deadline, and a deadline already past is not a candidate either. That is
     * exactly the state a failed event leaves behind, so treating the return as success is how the
     * consumer stays alive with nothing to wake it.
     */
    @Test
    fun aLostWakeWithNothingLeftToScheduleFrom_isStillRecovered() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { Result.failure(IOException("cold start, server down")) }
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(listOf(0L), f.fetcher.timesFor(PERIOD))

            // Poison the retry wake itself. Nothing is cached, so nothing else can re-arm.
            f.throwOnClockRead = f.clockReads + 1
            testScheduler.advanceTimeBy(25.hours.inWholeMilliseconds)

            assertTrue(
                "the only wake was lost and never replaced",
                f.fetcher.countFor(PERIOD) > 1
            )
        } finally {
            f.close()
        }
    }

    /**
     * A completion that dies part-way is the only thing that could have released its registration,
     * and a claimed key never fetches again — not even on re-activation.
     */
    @Test
    fun aCompletionThatThrows_stillReleasesItsRegistration() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkWhen = { it.key.period == PERIOD && it.nth == 1 }
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 500.hours)
            f.startSignedIn()
            testScheduler.runCurrent()
            assertEquals(1, f.fetcher.countFor(PERIOD))

            // The parked answer lands, and handling it dies before the registration is released.
            f.throwOnClockRead = f.clockReads + 1
            f.fetcher.releaseParked()
            testScheduler.runCurrent()

            f.scheduler.onActivated(TAB, PERIOD)
            testScheduler.advanceTimeBy(10_000)
            assertTrue(
                "the key stayed claimed by a completion that never finished",
                f.fetcher.countFor(PERIOD) > 1
            )
        } finally {
            f.close()
        }
    }

    /**
     * The fence has to be the session the loop believes in, not merely *a* session. `authFence()` is
     * live while the uid callback lags, so in that gap the transport is already somebody else — and
     * a fence minted then matches itself on the way back while belonging to a user this loop has
     * never heard of.
     */
    @Test
    fun aSessionForAnotherUid_mintsNoRequestEvenThoughItWouldMatchItself() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 500.hours)
            f.fence = AuthIdentityFence("u2", 1L)      // Firebase has moved; the uid event has not
            f.startSignedIn("u1")
            testScheduler.runCurrent()

            assertEquals(
                "a request was authorised as a user the loop does not know about",
                emptyList<Long>(),
                f.fetcher.timesFor(PERIOD)
            )
            assertTrue(f.scheduler.readState.value.entries.isEmpty())

            f.fence = AuthIdentityFence("u1", 1L)
            testScheduler.advanceTimeBy(10_000)
            assertEquals(listOf(5_000L), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    // --- what the transport carries out of a refused answer -----------------------------------------

    /**
     * The transport refuses the answer on a session change, and it used to drop the whole response
     * with it — so a `429` that arrived during a rotation came back as a bare cancellation and the
     * 5s floor scheduled the next request inside a window the server had just closed.
     */
    @Test
    fun aRateLimitCarriedOutOfARefusedAnswer_isStillHonoured() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) {
                    Result.failure(AuthIdentityChangedException(statusCode = 429, retryAfter = "900"))
                } else {
                    Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
                }
            }
            f.startSignedIn()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(60_000)
            assertEquals(
                "a withdrawal discarded the rate limit it was carrying",
                listOf(0L),
                f.fetcher.timesFor(PERIOD)
            )

            testScheduler.advanceTimeBy(900_000)
            assertEquals(listOf(0L, 900_000L + JITTER_MS), f.fetcher.timesFor(PERIOD).take(2))
        } finally {
            f.close()
        }
    }

    /** A withdrawal carrying nothing keeps the plain 5s floor — jitter belongs to the herd. */
    @Test
    fun aWithdrawalCarryingNoRateLimit_keepsThePlainFloor() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(AuthIdentityChangedException())
                else Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(10_000)

            assertEquals(listOf(0L, 5_000L), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /**
     * `delay-seconds` is an arbitrarily long integer, so a valid header can exceed `Long`. Treating
     * that as "no header" re-requests *early* — the one outcome the header exists to prevent.
     */
    @Test
    fun aRetryAfterTooLargeToRepresent_blocksRatherThanFallingThrough() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) {
                    Result.failure(http(503, "9223372036854775808"))   // Long.MAX_VALUE + 1
                } else {
                    Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
                }
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(25.hours.inWholeMilliseconds)

            assertEquals(
                "an unrepresentable but valid floor fell through to the recovery delay",
                listOf(0L),
                f.fetcher.timesFor(PERIOD)
            )
        } finally {
            f.close()
        }
    }

    /** RFC 9110 §10.2.3: a date is the other legal form, and dropping it loses a real floor. */
    @Test
    fun aRetryAfterAsAnHttpDate_isHonoured() = runTest {
        val f = Fixture(this)
        try {
            // T0 is 2026-09-06T02:31:00Z; this is two hours later, in IMF-fixdate.
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) {
                    Result.failure(http(503, "Sun, 06 Sep 2026 04:31:00 GMT"))
                } else {
                    Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
                }
            }
            f.startSignedIn()
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(2.hours.inWholeMilliseconds - 1_000)
            assertEquals("a dated floor was ignored", listOf(0L), f.fetcher.timesFor(PERIOD))

            testScheduler.advanceTimeBy(60_000)
            assertEquals(
                listOf(0L, 2.hours.inWholeMilliseconds + JITTER_MS),
                f.fetcher.timesFor(PERIOD).take(2)
            )
        } finally {
            f.close()
        }
    }

    /** Garbage is not a floor. D12: an absent one is not terminal, it leaves recovery standing. */
    @Test
    fun aMalformedRetryAfter_leavesTheRecoveryDelayStanding() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(503, "soon-ish"))
                else Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(400_000)

            assertEquals(listOf(0L, 300_000L + JITTER_MS), f.fetcher.timesFor(PERIOD))
        } finally {
            f.close()
        }
    }

    /**
     * The registration check is **not** redundant with the auth fence, and this is the counterexample
     * that shows it. Release and apply are one authorisation: if a superseded completion releases
     * first and is refused afterwards, the fence never puts the registration back — so the live
     * request's key is free while that request is still out, and the next pump duplicates it.
     *
     * The earlier version of this scenario re-activated at the same instant, where the 5s floor hid
     * the duplicate. Crossing that floor with the live request still in flight is what makes it show.
     */
    @Test
    fun aSupersededCompletion_doesNotFreeTheLiveRequestsKey() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkWhen = { it.key.period == PERIOD }
            f.fetcher.respond = always(CURRENT_BASIS, T0 + 500.hours)
            f.startSignedIn("u1")
            testScheduler.runCurrent()
            assertEquals(1, f.fetcher.countFor(PERIOD))

            f.fence = AuthIdentityFence("u2", 1L)
            f.scheduler.onIdentityChanged("u2")
            testScheduler.runCurrent()
            assertEquals("the replacement never started", 2, f.fetcher.countFor(PERIOD))

            // u1's answer lands late. It must not release the registration u2's request holds.
            f.fetcher.gateFor(1).complete(Unit)
            testScheduler.runCurrent()

            // Cross the cancel floor while u2's request is still parked. Under the split check the
            // key now looks free and the pump issues a third request for a live key.
            testScheduler.advanceTimeBy(60_000)
            assertEquals(
                "a superseded completion freed the live request's key",
                2,
                f.fetcher.countFor(PERIOD)
            )
        } finally {
            f.close()
        }
    }

    // --- the rate limit belongs to the client, not to a slot -----------------------------------------

    /**
     * nginx limits by address, so a `Retry-After` earned on one period binds every other one. Held
     * per slot, a sibling warm-up walks straight through it at the same instant.
     */
    @Test
    fun aRateLimitEarnedOnOnePeriod_alsoHoldsTheOthers() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(429, "900"))
                else Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
            }
            f.startSignedIn()
            testScheduler.runCurrent()

            // The selected fetch and the first warm-up leave together, before any answer exists —
            // the floor can only start once one comes back. Everything after that is the test.
            val whenTheLimitLanded = f.fetcher.calls.size

            testScheduler.advanceTimeBy(900_000 + JITTER_MS - 1_000)
            assertEquals(
                "a sibling period walked through the client-wide floor",
                whenTheLimitLanded,
                f.fetcher.calls.size
            )

            testScheduler.advanceTimeBy(5_000)
            assertTrue(
                "nothing resumed after the floor lapsed",
                f.fetcher.calls.size > whenTheLimitLanded
            )
        } finally {
            f.close()
        }
    }

    /** The limit was levied on this device, so signing out does not clear it with the cache. */
    @Test
    fun aRateLimitSurvivesAnIdentityChange() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(429, "900"))
                else Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
            }
            f.startSignedIn("u1")
            testScheduler.runCurrent()
            val whenTheLimitLanded = f.fetcher.calls.size

            f.fence = AuthIdentityFence("u2", 1L)
            f.scheduler.onIdentityChanged("u2")
            testScheduler.advanceTimeBy(60_000)

            assertEquals(
                "clearing the cache cleared the server's floor with it",
                whenTheLimitLanded,
                f.fetcher.calls.size
            )
        } finally {
            f.close()
        }
    }

    /**
     * A superseded completion is refused, but the limit it carries was never the session's to lose —
     * and the registration check returns before the answer is ever looked at.
     */
    @Test
    fun aRateLimitOnASupersededCompletion_isStillRecorded() = runTest {
        val f = Fixture(this)
        try {
            f.fetcher.parkWhen = { it.key.period == PERIOD && it.nth == 1 }
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) {
                    Result.failure(AuthIdentityChangedException(statusCode = 429, retryAfter = "900"))
                } else {
                    Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
                }
            }
            f.startSignedIn("u1")
            testScheduler.runCurrent()

            f.fence = AuthIdentityFence("u2", 1L)
            f.scheduler.onIdentityChanged("u2")
            testScheduler.runCurrent()
            val beforeLateAnswer = f.fetcher.calls.size

            f.fetcher.releaseParked()          // u1's answer lands, carrying the limit
            testScheduler.runCurrent()

            // Real demand is what makes this discriminating: an uncached tab has four periods to
            // warm, so without the recorded limit it would issue them immediately. The earlier
            // version watched an already-warm tab that had no reason to fetch either way.
            f.scheduler.onActivated("jpy", PERIOD)
            testScheduler.advanceTimeBy(120_000)
            assertEquals(
                "a refused completion dropped the limit it was carrying",
                beforeLateAnswer,
                f.fetcher.calls.size
            )

            testScheduler.advanceTimeBy(900_000)
            assertTrue(
                "the client never resumed after the limit lapsed",
                f.fetcher.calls.size > beforeLateAnswer
            )
        } finally {
            f.close()
        }
    }

    /**
     * RFC 9110 §5.6.7 obliges a recipient to accept all three date forms. Each form gets its own
     * test on purpose: one `runTest` shares a virtual clock, so a second case would start hours
     * after `T0` and its floor would already be in the past — the test would fail for a reason that
     * has nothing to do with parsing.
     */
    @Test
    fun anRfc850RetryAfter_isHonoured() = runTest {
        assertDatedFloorIsHonoured(this, "Sunday, 06-Sep-26 04:31:00 GMT")
    }

    @Test
    fun anAsctimeRetryAfter_isHonoured() = runTest {
        assertDatedFloorIsHonoured(this, "Sun Sep  6 04:31:00 2026")
    }

    /** Both forms name `T0 + 2h`; the floor must hold to it and then release. */
    private fun assertDatedFloorIsHonoured(scope: TestScope, header: String) {
        val f = Fixture(scope)
        try {
            f.fetcher.respond = { c ->
                if (c.key.period == PERIOD && c.nth == 1) Result.failure(http(503, header))
                else Result.success(snap(CURRENT_BASIS, T0 + 500.hours, c.key))
            }
            f.startSignedIn()
            scope.testScheduler.runCurrent()
            val whenTheLimitLanded = f.fetcher.calls.size

            scope.testScheduler.advanceTimeBy(2.hours.inWholeMilliseconds - 1_000)
            assertEquals("dropped a legal floor", whenTheLimitLanded, f.fetcher.calls.size)

            scope.testScheduler.advanceTimeBy(120_000)
            assertTrue("never resumed", f.fetcher.calls.size > whenTheLimitLanded)
        } finally {
            f.close()
        }
    }

    /**
     * RFC 9110 §5.6.7's 50-year rule is about the reconstructed instant, not a year range. A fixed
     * `currentYear - 50` window is wrong by up to a year at the edge — in 2026 it refuses a date in
     * 2076, which is only 49 years away and therefore a perfectly ordinary future floor.
     */
    @Test
    fun theRfc850FiftyYearBoundary_isJudgedOnTheInstantNotTheYear() {
        val now = Instant.parse("2026-09-06T02:31:00Z")

        // 2076-01 is inside the horizon, so the two digits read as the future year.
        assertEquals(
            Instant.parse("2076-01-01T00:00:00Z"),
            FreeSnapshotSchedulePolicy.retryFloorAfter(now, "Wednesday, 01-Jan-76 00:00:00 GMT")
        )
        // 2077-01 is past it, so the same digits read as the most recent past year instead — and a
        // past instant is no floor at all.
        assertNull(FreeSnapshotSchedulePolicy.retryFloorAfter(now, "Friday, 01-Jan-77 00:00:00 GMT"))
    }
}
