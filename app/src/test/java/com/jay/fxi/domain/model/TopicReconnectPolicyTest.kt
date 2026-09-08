package com.jay.fxi.domain.model

import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ladder, its ceiling, and the ends of its spread.
 *
 * D3 is a copy of iOS, so the numbers are asserted rather than described: two clients backing off
 * differently is the kind of difference nobody notices until a server is being hammered by one
 * platform and not the other.
 */
class TopicReconnectPolicyTest {

    /** `2s × attempt`, and 0.5 is the middle of the spread — the unjittered wait. */
    @Test
    fun theLadderIsTwoSecondsTimesTheAttempt() {
        (1..5).forEach { attempt ->
            assertEquals("$attempt", (attempt * 2).seconds, TopicReconnectPolicy.delayFor(attempt, 0.5))
        }
    }

    /**
     * The ends are ±20%, exactly.
     *
     * Asserted as whole nanoseconds because that is the unit the delay is floored to. These
     * particular draws come out exact either way; a few others do not, which is why the assertion
     * is on the number rather than on a tolerance.
     */
    @Test
    fun theSpreadIsTwentyPercentEitherWay() {
        assertEquals(1_600_000_000.nanoseconds, TopicReconnectPolicy.delayFor(1, 0.0))
        assertEquals(2_400_000_000.nanoseconds, TopicReconnectPolicy.delayFor(1, 1.0))
        assertEquals(8_000_000_000.nanoseconds, TopicReconnectPolicy.delayFor(5, 0.0))
        assertEquals(12_000_000_000.nanoseconds, TopicReconnectPolicy.delayFor(5, 1.0))
    }

    /** A draw is monotonic in the unit: a larger draw never waits less. */
    @Test
    fun aLargerDrawNeverWaitsLess() {
        val steps = (0..10).map { requireNotNull(TopicReconnectPolicy.delayFor(3, it / 10.0)) }
        assertEquals(steps.sorted(), steps)
    }

    /**
     * Outside the contract answers nothing, rather than guessing.
     *
     * An attempt of zero or a NaN draw is a caller bug; a plausible-looking delay would hide it
     * behind a socket that reconnects slightly wrong forever.
     */
    @Test
    fun anythingOutsideTheContractIsRefused() {
        listOf(0, -1).forEach { assertNull("attempt $it", TopicReconnectPolicy.delayFor(it, 0.5)) }
        listOf(-0.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertNull("jitter $it", TopicReconnectPolicy.delayFor(1, it))
        }
    }

    /**
     * Five attempts, then the automatic ladder stops.
     *
     * Stopping is not failing forever — a manual or lifecycle reconnect is a different trigger and
     * starts from zero. What stops is the client retrying on its own, which is the whole point of a
     * ceiling that a post-ACK close loop cannot keep pushing away.
     */
    @Test
    fun theBudgetIsFiveAttempts() {
        assertEquals(listOf(1, 2, 3, 4, 5), (0..4).map { TopicReconnectPolicy.nextAttempt(it) })
        assertNull(TopicReconnectPolicy.nextAttempt(5))
        assertNull(TopicReconnectPolicy.nextAttempt(6))
        assertNull("음수 attempt 는 계약 밖이다", TopicReconnectPolicy.nextAttempt(-1))
    }

    /** The window a connection has to survive before the budget reopens. */
    @Test
    fun theBudgetReopensOnlyAfterThirtySeconds() {
        assertEquals(30.seconds, TopicReconnectPolicy.STABILITY_RESET)
    }
}
