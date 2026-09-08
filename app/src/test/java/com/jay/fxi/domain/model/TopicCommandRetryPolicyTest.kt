package com.jay.fxi.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape of the spread, which is the contract; the draw belongs to the caller. */
class TopicCommandRetryPolicyTest {

    @Test
    fun `the draw runs from the base to twice it`() {
        assertEquals(5.seconds, TopicCommandRetryPolicy.waitFor(5.seconds, 0.0))
        assertEquals(7500.milliseconds, TopicCommandRetryPolicy.waitFor(5.seconds, 0.5))
        assertEquals(10.seconds, TopicCommandRetryPolicy.waitFor(5.seconds, 1.0))
    }

    /**
     * **No draw lands before the base**, which is the whole reason this jitter is one-sided.
     *
     * The base is a floor in the case that matters: `retry_after_seconds` is the server saying
     * *not before this*. A symmetric spread would put half the draws inside the window the server
     * asked the client to stay out of.
     */
    @Test
    fun `no draw is shorter than the base`() {
        val base = 3.seconds
        val shortest = (0..100).mapNotNull {
            TopicCommandRetryPolicy.waitFor(base, it / 100.0)
        }.min()
        assertEquals(101, (0..100).mapNotNull { TopicCommandRetryPolicy.waitFor(base, it / 100.0) }.size)
        assertTrue("서버가 말한 하한보다 이른 draw 가 있다", shortest >= base)
    }

    /**
     * The floor holds at the top of the allowed range too, where a `Double` cannot count.
     *
     * `TopicWholeRequestMatrix` accepts a `retry_after_seconds` anywhere up to
     * [TopicLeasePolicy.MAX_DURATION_SECONDS], and past 2^53 nanoseconds a `Double` no longer
     * holds every integer — scaling the base through one came back a millisecond short of the
     * time the server asked the client to wait. Found by review, with this exact base.
     */
    @Test
    fun `a base too large for a Double still waits at least the base`() {
        val base = 4_700_000_001.seconds
        assertEquals(base, TopicCommandRetryPolicy.waitFor(base, 0.0))
        assertTrue(TopicCommandRetryPolicy.waitFor(base, 0.5)!! >= base)
        assertTrue(TopicCommandRetryPolicy.waitFor(base, 1.0)!! >= base)
    }

    @Test
    fun `a later draw never waits less than an earlier one`() {
        val waits = (0..20).map { TopicCommandRetryPolicy.waitFor(2.seconds, it / 20.0)!! }
        assertEquals(waits.sorted(), waits)
    }

    /** The silent path has no server floor, so the client's own number is the base. */
    @Test
    fun `the silent retry wait is built on the client's own cooldown`() {
        assertEquals(5.seconds, TopicCommandRetryPolicy.SILENT_RETRY_COOLDOWN)
        assertEquals(
            TopicCommandRetryPolicy.waitFor(5.seconds, 0.25),
            TopicCommandRetryPolicy.silentRetryWait(0.25)
        )
    }

    /**
     * A caller bug answers `null` rather than a plausible-looking wait.
     *
     * A clamped guess here would be a wait that looks like policy and is not — the sort of value
     * that gets read back out of a log years later as evidence of what the policy was.
     */
    @Test
    fun `anything outside the contract is refused`() {
        assertNull("0 을 기다릴 수는 없다", TopicCommandRetryPolicy.waitFor(Duration.ZERO, 0.5))
        assertNull(TopicCommandRetryPolicy.waitFor((-1).seconds, 0.5))
        assertNull(TopicCommandRetryPolicy.waitFor(Duration.INFINITE, 0.5))
        assertNull(TopicCommandRetryPolicy.waitFor(1.seconds, Double.NaN))
        assertNull(TopicCommandRetryPolicy.waitFor(1.seconds, -0.001))
        assertNull(TopicCommandRetryPolicy.waitFor(1.seconds, 1.001))
    }

    /**
     * A base whose *jitter* cannot be counted is refused; the base itself is still honoured.
     *
     * The two are different failures now that the base is carried through untouched and the
     * jitter added to it. A base of nearly three hundred years can be jittered and returned; one
     * whose nanosecond count has already saturated cannot, and saying so is better than returning
     * a spread that is not the one the arithmetic describes.
     */
    @Test
    fun `a base whose nanoseconds have saturated is refused, at every draw`() {
        val largest = TopicLeasePolicy.MAX_DURATION_SECONDS.seconds
        assertEquals(largest, TopicCommandRetryPolicy.waitFor(largest, 0.0))
        assertTrue(TopicCommandRetryPolicy.waitFor(largest, 1.0)!! >= largest)

        // Past the ceiling `inWholeNanoseconds` saturates, and **the middle of the range is where
        // that hides**: `Long.MAX_VALUE` halved is a perfectly ordinary-looking number that passes
        // every check downstream and describes no base at all. A draw of 1.0 alone would not have
        // caught it. Found by review.
        listOf(0.0, 0.5, 1.0).forEach {
            assertNull("draw=$it 에서 포화한 base 가 통과했다", TopicCommandRetryPolicy.waitFor(1_000_000.days, it))
        }
    }
}
