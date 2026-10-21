package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthUnavailableException
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Claude-owned S6-2a contract for the S6C3 bootstrap retry ladder (`s6_2a/s6_2a_contracts.r2.md` §4.1). The implementation
 * thread reads but does not edit this file.
 *
 * Three attempts including the first; after the n-th failure wait `BACKOFFS[min(n-1, size-1)]` (0.5s, then 1.5s, the last
 * value reused), spread ±20% symmetrically (GUIDE:392-393, IVM:75-77, :858-860). Computed in nanoseconds and floored, the
 * same formula as `TopicReconnectPolicy.kt:70-79`. The per-attempt budget is the service's own constant, not a second copy
 * of it (SVC:126). Expected nanoseconds were computed with IEEE-754 doubles in the same order of operations.
 */
class TopicBootstrapRetryLadderTest {

    // --- §4.0 shared helpers (same definitions in the three S6-2a policy test classes) ---

    private val P = TopicBootstrapRetryPolicy

    private val u1 = AuthIdentityFence("u1", 1L)
    private val u1g2 = AuthIdentityFence("u1", 2L)
    private val u2 = AuthIdentityFence("u2", 1L)

    private fun L(a: Int, i: Long?, d: Long, r: Long = 0L, o: AuthIdentityFence = u1) = TopicBootstrapLadder(o, r, a, i, d)

    private fun wire(code: Int, body: String, retryAfter: List<String> = emptyList()): TopicSnapshotOutcome =
        failure(code, body, retryAfter).toTopicSnapshotOutcome()

    /** Same shape as `TopicSnapshotOutcomeTest.kt:167-186`. */
    private fun failure(code: Int, body: String, retryAfter: List<String> = emptyList()): AuthenticatedHttpFailure {
        val headers = emptyMap<String, String>().toHeaders().newBuilder().apply {
            retryAfter.forEach { add("Retry-After", it) }
        }.build()
        val response: Response<ResponseBody> = Response.error(
            body.toByteArray().toResponseBody(),
            okhttp3.Response.Builder()
                .request(Request.Builder().url("https://example.invalid/api/v2/topics/snapshot").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("synthetic")
                .headers(headers)
                .build()
        )
        return response.preserve(AuthenticatedEndpoint.TOPIC_SNAPSHOT).failure!!
    }

    private val ok: TopicSnapshotOutcome = TopicSnapshotOutcome.Delivered(DecodedTopicFrame.Pong)
    private val net: TopicSnapshotOutcome = TopicSnapshotOutcome.Unreachable(IOException("down"))
    private val tok: TopicSnapshotOutcome = TopicSnapshotOutcome.Unreachable(AuthUnavailableException("mint"))
    private val pend: TopicSnapshotOutcome =
        wire(503, """{"detail":"Subscription status pending. Retry later."}""", listOf("5"))
    private val unk: TopicSnapshotOutcome = wire(
        404,
        """{"error":"unknown_topic","detail":"topic 'krx:usd-krw-futures' not supported","supported_topics":["fx:usd-krw","usdt:krw"]}"""
    )

    private fun after(
        l: TopicBootstrapLadder,
        o: TopicSnapshotOutcome,
        now: Long,
        live: AuthIdentityFence? = u1,
        rev: Long = l.startRevision,
        floor: Long = 0L,
        adm: Boolean = true,
        u: Double = 0.5
    ): TopicBootstrapNext = P.afterAttempt(l, o, now, live, rev, floor, adm, u)

    private fun before(
        l: TopicBootstrapLadder,
        now: Long,
        live: AuthIdentityFence? = u1,
        rev: Long = l.startRevision,
        floor: Long = 0L
    ): TopicBootstrapGo = P.beforeAttempt(l, now, live, rev, floor)

    // --- §4.1 rows ---

    /**
     * RL01: the constants, asserted as numbers. A budget of another value fails here; the same value re-written as a literal is Y05's.
     *
     * Catches MR-L01 (3→4, 3→2 attempts), MR-L02 (BACKOFFS reordered), MR-L09 (budget re-defined to another value).
     */
    @Test
    fun RL01_constants_areThreeAttempts_halfThenOneAndAHalfSeconds_twentyPercent_andTheServiceBudget() {
        assertEquals(3, P.MAX_ATTEMPTS)
        assertEquals(listOf(500.milliseconds, 1_500.milliseconds), P.BACKOFFS)
        assertEquals(0.2, P.JITTER_FRACTION, 0.0)
        assertEquals(10_000L, P.ATTEMPT_BUDGET_MILLIS)
        // SVC:126
        assertEquals(TopicSnapshotBootstrapService.ATTEMPT_BUDGET.inWholeMilliseconds, P.ATTEMPT_BUDGET_MILLIS)
    }

    /**
     * RL02: the delay table, both ends and the middle of the spread for each step.
     *
     * Catches MR-L03 (upward-only spread `base·(1+0.2u)`: (1,0.0) becomes 500ms), MR-L04 (spread 0.1 or 0.3), MR-L05 (index
     * `min(n, size-1)`: the first failure waits 1.5s).
     */
    @Test
    fun RL02_delayTable_isSymmetricTwentyPercentAroundEachStep_flooredToNanoseconds() {
        val cases = listOf(
            Triple(1, 0.0, 400.milliseconds),
            Triple(1, 0.25, 450.milliseconds),
            Triple(1, 0.5, 500.milliseconds),
            Triple(1, 1.0, 600.milliseconds),
            Triple(1, 0.999999, 599_999_800.nanoseconds),
            Triple(2, 0.0, 1_200.milliseconds),
            Triple(2, 0.5, 1_500.milliseconds),
            Triple(2, 0.75, 1_650.milliseconds),
            Triple(2, 1.0, 1_800.milliseconds)
        )
        cases.forEach { (n, u, expected) ->
            assertEquals("delayFor($n, $u)", expected, P.delayFor(n, u))
        }
    }

    /**
     * RL03: past the table the last step is reused, up to the largest attempt number.
     *
     * Catches MR-L08 (null or an exception from 3 upward — index out of range).
     */
    @Test
    fun RL03_pastTheTable_theLastStepIsReused() {
        assertEquals("delayFor(3, 0.5)", 1_500.milliseconds, P.delayFor(3, 0.5))
        assertEquals("delayFor(Int.MAX_VALUE, 0.5)", 1_500.milliseconds, P.delayFor(Int.MAX_VALUE, 0.5))
    }

    /**
     * RL04: outside the contract answers null, not a clamped guess; -0.0 is the same value as 0.0 and is inside.
     *
     * Catches MR-L06 (out-of-range values clamped and accepted), MR-L07 (`jitterUnit <= 0.0` rejected: -0.0 and 0.0 both null).
     */
    @Test
    fun RL04_outsideTheContract_isNull_butNegativeZeroJitterIsZero() {
        val refused = listOf(
            0 to 0.5,
            -1 to 0.5,
            Int.MIN_VALUE to 0.5,
            1 to Double.NaN,
            1 to Double.POSITIVE_INFINITY,
            1 to Double.NEGATIVE_INFINITY,
            1 to -0.000001,
            1 to 1.000001
        )
        assertEquals(8, refused.size)
        refused.forEach { (n, u) -> assertNull("delayFor($n, $u)", P.delayFor(n, u)) }

        val negativeZero = -0.0
        // the control input really is negative zero, not a folded 0.0
        assertTrue(1.0 / negativeZero == Double.NEGATIVE_INFINITY)
        assertEquals("delayFor(1, -0.0)", 400.milliseconds, P.delayFor(1, negativeZero))
    }
}
