package com.jay.fxi.data.free

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduling contract, away from the loop that enforces it.
 *
 * Every case here is one the plan or the server states outright — the ones that were paraphrased
 * into something weaker on the first attempt are named individually.
 */
class FreeSnapshotSchedulePolicyTest {

    // 2026-09-06 10:30 KST, the basis slot the server pins an hourly view to.
    private val asOf = Instant.parse("2026-09-06T01:30:00Z")

    private fun snapshot(
        refreshNotBefore: Instant? = null,
        tab: String = "usd",
        period: GraphPeriod = GraphPeriod.THREE_MONTHS
    ) = FreeSnapshot(
        tab = tab,
        period = period,
        asOf = asOf,
        generatedAt = asOf + 19.seconds,
        refreshNotBefore = refreshNotBefore,
        rate = FreeRate.Flat(asset = "usd-krw", entries = emptyList()),
        graph = FreeGraph(bucketSize = null, series = emptyList())
    )

    // --- stale ladder --------------------------------------------------------------------------

    /**
     * The server computes `refresh_not_before` from its own serve-time clock, so a *stale*
     * canonical still carries the next hour's hint. Honouring it on the stale path turns a
     * 20-second retry into an hour's wait — which is what the first version of this policy did.
     */
    @Test
    fun aStaleAnswer_retriesOnItsOwnLadder_notAtTheServersNextHourHint() {
        val now = asOf + 61.minutes + 15.seconds
        val nextHourHint = asOf + 2.hours + 1.minutes
        val snap = snapshot(refreshNotBefore = nextHourHint)

        val first = FreeSnapshotSchedulePolicy.nextEligibleAt(snap, now, "install-a", staleAttempt = 1)

        assertEquals(now + 20.seconds, first)
        assertTrue("the ladder was overridden by the hour hint", first < nextHourHint)
    }

    @Test
    fun staleLadder_climbs_thenSettlesOnRecovery() {
        val now = asOf + 61.minutes
        val snap = snapshot(refreshNotBefore = asOf + 2.hours)
        val delays = (1..5).map {
            FreeSnapshotSchedulePolicy.nextEligibleAt(snap, now, "install-a", staleAttempt = it) - now
        }
        assertEquals(
            listOf(20.seconds, 40.seconds, 80.seconds, 300.seconds, 300.seconds),
            delays
        )
    }

    /** A failed fetch is not a stale success: the plan sends it straight to the recovery delay. */
    @Test
    fun aFailedFetch_goesStraightToRecovery_notToTheFirstLadderStep() {
        val now = asOf + 1.hours
        assertEquals(now + 300.seconds, FreeSnapshotSchedulePolicy.afterFailure(now))
        assertNotEquals(
            FreeSnapshotSchedulePolicy.afterFailure(now),
            now + FreeSnapshotSchedulePolicy.backoffFor(1)
        )
    }

    // --- fresh path ----------------------------------------------------------------------------

    @Test
    fun aFreshAnswer_waitsForTheServerHintPlusThisInstallsJitter() {
        val hint = asOf + 1.hours + 1.minutes
        val now = asOf + 1.minutes
        val jitter = FreeSnapshotSchedulePolicy.jitterFor("install-a", "usd")

        val next = FreeSnapshotSchedulePolicy.nextEligibleAt(
            snapshot(refreshNotBefore = hint), now, "install-a", staleAttempt = 0
        )

        assertEquals(hint + jitter, next)
        assertTrue(jitter >= 10.seconds && jitter <= 30.seconds)
    }

    /**
     * `as_of` is the `HH:30` basis and the server's hint is the *next* `HH:31:00`. A bare `+1h`
     * would aim at `HH:30` — before the job that produces what is being asked for has run.
     */
    @Test
    fun withNoServerHint_theFallbackClearsTheNextPublishByAMinute() {
        val now = asOf + 1.minutes
        val jitter = FreeSnapshotSchedulePolicy.jitterFor("install-a", "usd")

        val next = FreeSnapshotSchedulePolicy.nextEligibleAt(
            snapshot(refreshNotBefore = null), now, "install-a", staleAttempt = 0
        )

        assertEquals(asOf + 1.hours + 60.seconds + jitter, next)
        assertTrue("the fallback aimed at the publish slot itself", next > asOf + 1.hours + jitter)
    }

    /** A hint already in the past must still be a wait — clamping to `now` allows a 0s re-fetch. */
    @Test
    fun aHintInThePast_stillLeavesAPositiveDelay() {
        val now = asOf + 3.hours
        val next = FreeSnapshotSchedulePolicy.nextEligibleAt(
            snapshot(refreshNotBefore = asOf + 1.hours), now, "install-a", staleAttempt = 0
        )
        assertEquals(now + 5.seconds, next)
        assertTrue(next > now)
    }

    /** Same input, same answer — a fresh draw each time would walk the deadline earlier. */
    @Test
    fun jitterIsStableForOneInstallAndTab() {
        val a = FreeSnapshotSchedulePolicy.jitterFor("install-a", "usd")
        assertEquals(a, FreeSnapshotSchedulePolicy.jitterFor("install-a", "usd"))
        // Keyed without the period on purpose: all four periods of a tab share one offset.
        val snapA = snapshot(refreshNotBefore = asOf + 1.hours, period = GraphPeriod.ONE_DAY)
        val snapB = snapshot(refreshNotBefore = asOf + 1.hours, period = GraphPeriod.ONE_YEAR)
        val now = asOf
        assertEquals(
            FreeSnapshotSchedulePolicy.nextEligibleAt(snapA, now, "install-a", 0),
            FreeSnapshotSchedulePolicy.nextEligibleAt(snapB, now, "install-a", 0)
        )
    }

    @Test
    fun differentInstallsAreSpreadApart() {
        val offsets = (1..40).map { FreeSnapshotSchedulePolicy.jitterFor("install-$it", "usd") }
        assertTrue("every install landed on the same second", offsets.toSet().size > 1)
        assertTrue(offsets.all { it >= 10.seconds && it <= 30.seconds })
    }

    // --- freshness -------------------------------------------------------------------------------

    @Test
    fun freshnessCrossesAtTwoHoursAndAtTwentyFour() {
        assertEquals(FreeSnapshotFreshness.FRESH, FreeSnapshotSchedulePolicy.freshnessOf(asOf, asOf + 119.minutes))
        assertEquals(FreeSnapshotFreshness.DELAYED, FreeSnapshotSchedulePolicy.freshnessOf(asOf, asOf + 2.hours))
        assertEquals(FreeSnapshotFreshness.DELAYED, FreeSnapshotSchedulePolicy.freshnessOf(asOf, asOf + 23.hours))
        assertEquals(FreeSnapshotFreshness.UNAVAILABLE, FreeSnapshotSchedulePolicy.freshnessOf(asOf, asOf + 24.hours))
    }

    /**
     * A device clock behind the server's makes a correct `as_of` look like the future. Expiring on
     * that would blank the screen for good data; the server already refuses to publish a real one.
     */
    @Test
    fun aFutureAsOf_isFresh_notExpired() {
        val now = asOf - 5.minutes
        assertEquals(FreeSnapshotFreshness.FRESH, FreeSnapshotSchedulePolicy.freshnessOf(asOf, now))
        assertTrue(!FreeSnapshotSchedulePolicy.isExpired(asOf, now))
    }

    /**
     * Nothing in the app emits merely because time passed, so the loop has to be told when the
     * badge turns on — not only when the value disappears.
     */
    @Test
    fun theNextDeadline_isTheBadgeBeforeItIsTheExpiry() {
        assertEquals(asOf + 2.hours, FreeSnapshotSchedulePolicy.nextFreshnessDeadline(asOf, asOf + 1.hours))
        assertEquals(asOf + 24.hours, FreeSnapshotSchedulePolicy.nextFreshnessDeadline(asOf, asOf + 3.hours))
        assertNull(FreeSnapshotSchedulePolicy.nextFreshnessDeadline(asOf, asOf + 25.hours))
    }

    // --- three judgments that must not be conflated --------------------------------------------

    /**
     * `ANDROID_V2_PLAN.md` and iOS both keep these apart, and each of the three cases below breaks
     * if one stands in for another. All three can read FRESH to the user at the same time.
     */
    @Test
    fun theSameSlotWhileItIsStillCurrent_isNotAQueryStale() {
        // 10:45 KST, holding the 10:30 slot — that *is* the slot the server should have.
        val now = asOf + 15.minutes
        assertTrue(!FreeSnapshotSchedulePolicy.isQueryStale(asOf, now))
        // …and repeating it is not progress, which is a different question with a different use.
        assertTrue(!FreeSnapshotSchedulePolicy.hasAdvanced(asOf, asOf))
    }

    @Test
    fun aFirstEverAnswerBehindTheCurrentBasis_isAQueryStale() {
        // 11:31 KST, nothing cached before, answer carries 10:30.
        val now = asOf + 61.minutes
        assertTrue(FreeSnapshotSchedulePolicy.isQueryStale(asOf, now))
        // Nothing to compare against, so it counts as movement — the ladder starts, not resumes.
        assertTrue(FreeSnapshotSchedulePolicy.hasAdvanced(null, asOf))
    }

    @Test
    fun anAnswerThatAdvancedButIsStillBehind_isBothAdvancedAndStale() {
        // 11:31 KST, cached 09:30, answer carries 10:30.
        val now = asOf + 61.minutes
        val previous = asOf - 1.hours
        assertTrue(FreeSnapshotSchedulePolicy.hasAdvanced(previous, asOf))
        assertTrue(FreeSnapshotSchedulePolicy.isQueryStale(asOf, now))
        assertEquals(FreeSnapshotFreshness.FRESH, FreeSnapshotSchedulePolicy.freshnessOf(asOf, now))
    }

    @Test
    fun aLateOlderAnswer_isNotProgress() {
        assertTrue(!FreeSnapshotSchedulePolicy.hasAdvanced(asOf, asOf - 1.hours))
        assertTrue(FreeSnapshotSchedulePolicy.hasAdvanced(asOf, asOf + 1.hours))
    }

    @Test
    fun basisSnapsToTheHalfHourGridInSeoulTime() {
        // 10:29:59 KST still belongs to the 09:30 slot; 10:30:00 opens the new one.
        assertEquals(asOf - 1.hours, FreeSnapshotSchedulePolicy.basisOf(asOf - 1.seconds))
        assertEquals(asOf, FreeSnapshotSchedulePolicy.basisOf(asOf))
        assertEquals(asOf, FreeSnapshotSchedulePolicy.basisOf(asOf + 59.minutes + 59.seconds))
        assertEquals(asOf + 1.hours, FreeSnapshotSchedulePolicy.basisOf(asOf + 1.hours))
    }
}
