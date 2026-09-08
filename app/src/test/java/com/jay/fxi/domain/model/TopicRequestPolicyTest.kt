package com.jay.fxi.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one send is given, and how many sends there are.
 *
 * The pair of deadlines and the attempt budget are different kinds of limit and are asserted
 * apart: a slow failure runs out of time, a fast one runs out of tries, and conflating them was
 * the reading this contract had to be revised to settle.
 */
class TopicRequestPolicyTest {

    private val sentAt = 10_000L

    private fun deadlines(at: Long = sentAt) = requireNotNull(TopicRequestPolicy.deadlinesFor(at))

    @Test
    fun bothDeadlinesAreAnchoredAtTheSameSend() {
        val d = deadlines()
        assertEquals(sentAt + 20_000, d.ackByMillis)
        assertEquals(sentAt + 45_000, d.deliverByMillis)
        assertEquals(sentAt, d.sentAtMillis)
    }

    /** Overdue is at the deadline, not after it — a deadline reached is a deadline missed. */
    @Test
    fun aDeadlineIsMissedTheInstantItArrives() {
        val d = deadlines()
        assertFalse(d.ackOverdue(sentAt + 19_999))
        assertTrue(d.ackOverdue(sentAt + 20_000))
        assertFalse(d.deliveryOverdue(sentAt + 44_999))
        assertTrue(d.deliveryOverdue(sentAt + 45_000))
    }

    /**
     * An ACK cannot buy more time to deliver in.
     *
     * There is no method here that would let it: both deadlines come from one send instant and
     * nothing rewrites them. Asserted as the absence it is — the delivery deadline of a send is the
     * same value however long after that send you ask.
     */
    @Test
    fun anAcknowledgementDoesNotMoveTheDeliveryDeadline() {
        val d = deadlines()
        assertEquals(sentAt + 45_000, d.deliverByMillis)
        // …and asking again from inside the ACK window answers the same thing.
        assertFalse(d.deliveryOverdue(sentAt + 19_000))
        assertEquals(sentAt + 45_000, d.deliverByMillis)
    }

    /**
     * A retry anchors at its own send, which is the whole point of the revision.
     *
     * Sent at 0, an ACK timeout at 20, a retry at 25: the second attempt's delivery deadline is 70,
     * not 45. There is no window spanning both.
     */
    @Test
    fun aRetryAnchorsAtItsOwnSend() {
        val first = deadlines(0)
        val retry = deadlines(25_000)
        assertEquals(45_000, first.deliverByMillis)
        assertEquals(70_000, retry.deliverByMillis)
        assertTrue("첫 시도의 창은 이미 지났다", first.deliveryOverdue(70_000))
        assertFalse("재시도의 창은 아직이다", retry.deliveryOverdue(69_999))
    }

    /**
     * Three attempts, the first one included, counted from "no attempt started".
     *
     * Zero is not the first attempt — it is the state before one, and an attempt is spent entering
     * it, before the token is acquired. So a failure in the preparation before a send has already
     * cost one and does not put the count back: three token failures in a row leave no send behind
     * them and no budget left.
     */
    @Test
    fun theBudgetIsThreeAttemptsIncludingTheFirst() {
        assertEquals(listOf(1, 2, 3), (0..2).map { TopicRequestPolicy.nextAttempt(it) })
        assertNull(TopicRequestPolicy.nextAttempt(3))
        assertNull(TopicRequestPolicy.nextAttempt(4))
        assertNull("음수는 계약 밖이다", TopicRequestPolicy.nextAttempt(-1))
    }

    /**
     * A send instant outside the contract answers nothing, and there is no way around the check.
     *
     * Unreachable from a boot-relative clock and checked anyway: the clock is injected, and an
     * injected clock is a place a test or a later refactor can put a number the platform never
     * would. Without this the addition wraps and both deadlines land in the past, where
     * `ackBy < deliverBy` is still true and everything looks fine.
     *
     * The constructor is private, so this factory is the only entrance — an `internal` one would
     * have been a second, unchecked way in from anywhere in the module.
     */
    @Test
    fun aSendInstantOutsideTheContractIsRefused() {
        assertNull(TopicRequestPolicy.deadlinesFor(-1))
        assertNull(TopicRequestPolicy.deadlinesFor(Long.MAX_VALUE))
        assertNull(TopicRequestPolicy.deadlinesFor(Long.MAX_VALUE - 44_999))
        // The last instant that still fits.
        val latest = requireNotNull(TopicRequestPolicy.deadlinesFor(Long.MAX_VALUE - 45_000))
        assertEquals(Long.MAX_VALUE, latest.deliverByMillis)
        assertTrue(latest.ackByMillis < latest.deliverByMillis)
    }
}
