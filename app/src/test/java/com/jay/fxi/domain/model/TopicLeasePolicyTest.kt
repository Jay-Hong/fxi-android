package com.jay.fxi.domain.model

import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Being early, about the right lease.
 *
 * A lapsed lease raises nothing — the topic just stops arriving — so every rule here is about not
 * being late, and the failures it guards against all look like silence rather than an error.
 */
class TopicLeasePolicyTest {

    /** Three minutes early, less the draw. */
    @Test
    fun renewalIsAheadOfExpiryByTheLeadTime() {
        assertEquals((900 - 180).seconds, TopicLeasePolicy.renewAfter(listOf(900), jitter = 0))
        assertEquals((900 - 180 - 45).seconds, TopicLeasePolicy.renewAfter(listOf(900), jitter = 45))
    }

    /**
     * The shortest lease decides, not the longest.
     *
     * One timer covers the whole set. Pacing it by the longest would let whichever topic expires
     * first go quiet while the client waits — having renewed nothing — for someone else's deadline.
     */
    @Test
    fun theShortestLeaseSetsThePace() {
        assertEquals(
            (600 - 180).seconds,
            TopicLeasePolicy.renewAfter(listOf(3600, 600, 1800), jitter = 0)
        )
    }

    /**
     * A lease already inside the lead time renews now, rather than in the past.
     *
     * `duration 0` means the server wants re-authentication immediately, and anything under the
     * lead time means the same thing by arithmetic.
     */
    @Test
    fun aLeaseInsideTheLeadTimeRenewsImmediately() {
        listOf(0L, 1L, 179L, 180L).forEach {
            assertEquals("$it", 0.seconds, TopicLeasePolicy.renewAfter(listOf(it), jitter = 0))
        }
        assertEquals(0.seconds, TopicLeasePolicy.renewAfter(listOf(200), jitter = 60))
    }

    /**
     * The draw only ever subtracts.
     *
     * Spreading clients apart is worth doing; spending the lead time to do it is not. Adding a draw
     * of at most 60 to a lead of 180 would not land after expiry — the margin would shrink to 120 —
     * but the margin is the contract, and the client that needed all of it is the one whose renewal
     * is slow. A negative draw is refused for that reason and one worse: past −180 the subtraction
     * becomes an addition big enough to land after expiry, where the topic simply stops arriving.
     */
    @Test
    fun theDrawOnlySubtracts() {
        val waits = (0L..60L step 10).map { requireNotNull(TopicLeasePolicy.renewAfter(listOf(900), it)) }
        assertEquals(waits.sortedDescending(), waits)
        assertTrue(waits.first() > waits.last())
        assertNull("음수 draw 는 상향 jitter 와 같은 실수다", TopicLeasePolicy.renewAfter(listOf(900), -1))
        assertNull(TopicLeasePolicy.renewAfter(listOf(900), TopicLeasePolicy.JITTER_UPPER_BOUND + 1))
    }

    /**
     * The ceiling is exactly where signed `Long` nanoseconds stop counting.
     *
     * Asserted rather than described, because the reason is easy to state wrongly: a `Duration`
     * holds far longer spans than this in coarser units, so the limit is not what can be *held* but
     * what can be *converted*. One second past the ceiling, every lease converts to the same
     * saturated number, and a duration that no longer means what it says is the thing being
     * refused. Found by review.
     */
    @Test
    fun theCeilingIsWhereNanosecondsStopCounting() {
        assertEquals(
            TopicLeasePolicy.MAX_DURATION_SECONDS * 1_000_000_000,
            TopicLeasePolicy.MAX_DURATION_SECONDS.seconds.inWholeNanoseconds
        )
        assertEquals(
            "상한을 넘으면 포화된다 — 더 큰 lease 가 전부 같은 수가 된다",
            Long.MAX_VALUE,
            (TopicLeasePolicy.MAX_DURATION_SECONDS + 1).seconds.inWholeNanoseconds
        )
        assertEquals(
            "그런데도 Duration 자체는 그 값을 담는다 — 담을 수 있느냐가 아니라 셀 수 있느냐가 기준이다",
            (TopicLeasePolicy.MAX_DURATION_SECONDS + 1),
            (TopicLeasePolicy.MAX_DURATION_SECONDS + 1).seconds.inWholeSeconds
        )
    }

    /**
     * Nothing to pace, or something that cannot be counted, answers nothing.
     *
     * A duration past the ceiling saturates when converted, so it can no longer be told from any
     * larger one — see `theCeilingIsWhereNanosecondsStopCounting`.
     */
    @Test
    fun anythingOutsideTheContractIsRefused() {
        assertNull("lease 가 없으면 잴 것이 없다", TopicLeasePolicy.renewAfter(emptyList(), 0))
        assertNull(TopicLeasePolicy.renewAfter(listOf(-1), 0))
        assertNull(TopicLeasePolicy.renewAfter(listOf(900, TopicLeasePolicy.MAX_DURATION_SECONDS + 1), 0))
        assertTrue(TopicLeasePolicy.isCountable(TopicLeasePolicy.MAX_DURATION_SECONDS))
        assertTrue(!TopicLeasePolicy.isCountable(-1))
    }
}
