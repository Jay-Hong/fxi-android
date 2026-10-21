package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.remote.TopicBootstrapStop.ADMISSION_CLOSED
import com.jay.fxi.data.remote.TopicBootstrapStop.ATTEMPTS_EXHAUSTED
import com.jay.fxi.data.remote.TopicBootstrapStop.AUTHENTICATION
import com.jay.fxi.data.remote.TopicBootstrapStop.BUDGET_EXCEEDED
import com.jay.fxi.data.remote.TopicBootstrapStop.FORBIDDEN
import com.jay.fxi.data.remote.TopicBootstrapStop.IDENTITY_MOVED
import com.jay.fxi.data.remote.TopicBootstrapStop.INVALID_INPUT
import com.jay.fxi.data.remote.TopicBootstrapStop.MALFORMED
import com.jay.fxi.data.remote.TopicBootstrapStop.NOT_FOUND_UNRECOGNIZED
import com.jay.fxi.data.remote.TopicBootstrapStop.SNAPSHOT_ALREADY_RECEIVED
import com.jay.fxi.data.remote.TopicBootstrapStop.TIMED_OUT
import com.jay.fxi.data.remote.TopicBootstrapStop.TOPICS_DISABLED
import com.jay.fxi.data.remote.TopicBootstrapStop.TOPIC_UNAVAILABLE
import com.jay.fxi.data.remote.TopicBootstrapStop.UNKNOWN_TOPIC
import java.io.IOException
import kotlin.time.Duration
import kotlinx.datetime.Instant
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * S6C3 decisions: when a bootstrap retry is issued, retried, applied, or stopped (S6-2a design r2, 4.3).
 *
 * Grounds: GUIDE:390-399, :434-437; scope.r5.md:65, :724-731; IVM:110, :139-142, :226-230, :816-862;
 * TSC:2774-2777; TopicRuntime.kt:246-285; TopicSubscribeCommand.kt:31-45; FreeSnapshotSchedulePolicy.kt:238-268.
 *
 * The policy is pure: clock, jitter, identity, revision and floor are all arguments, so every row is a
 * table of inputs and exact outputs. The fixed decision order is input validation, the 10 s budget,
 * identity, classification, then attempts and early stop. The floor-coupling rows (RD09) use the real
 * [TopicBootstrapRetryFloor] on a fake [TopicCommandClock], the same shape as TopicBootstrapRetryFloorTest.
 */
class TopicBootstrapRetryDecisionTest {

    // ---- 4.0 shared helpers -------------------------------------------------------------------

    private val P = TopicBootstrapRetryPolicy

    private val u1 = AuthIdentityFence("u1", 1L)
    private val u1g2 = AuthIdentityFence("u1", 2L)
    private val u2 = AuthIdentityFence("u2", 1L)

    private fun L(a: Int, i: Long?, d: Long, r: Long = 0L, o: AuthIdentityFence = u1) =
        TopicBootstrapLadder(o, r, a, i, d)

    // Same failure shape as TopicSnapshotOutcomeTest.kt:167-186
    private fun wire(code: Int, body: String, retryAfter: List<String> = emptyList()): TopicSnapshotOutcome {
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
        return response.preserve(AuthenticatedEndpoint.TOPIC_SNAPSHOT).failure!!.toTopicSnapshotOutcome()
    }

    private val ok = TopicSnapshotOutcome.Delivered(DecodedTopicFrame.Pong)
    private val net = TopicSnapshotOutcome.Unreachable(IOException("down"))
    private val tok = TopicSnapshotOutcome.Unreachable(AuthUnavailableException("mint"))
    private val pend = wire(503, """{"detail":"Subscription status pending. Retry later."}""", listOf("5"))
    private val unk = wire(
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

    private fun retry(l: TopicBootstrapLadder) = TopicBootstrapNext.Retry(l)
    private val apply = TopicBootstrapNext.Apply
    private fun stopN(r: TopicBootstrapStop) = TopicBootstrapNext.Stop(r)
    private fun issue(l: TopicBootstrapLadder) = TopicBootstrapGo.Issue(l)
    private fun notYet(t: Long) = TopicBootstrapGo.NotYet(t)
    private fun stopG(r: TopicBootstrapStop) = TopicBootstrapGo.Stop(r)

    // Same clock shape as TopicBootstrapRetryFloorTest.kt:16-23
    private class Clocks(var now: Long = 1_000L, var wall: Instant = Instant.parse("2026-09-29T00:00:00Z")) {
        val clock = object : TopicCommandClock {
            override fun nowMillis(): Long = now
            override suspend fun sleep(duration: Duration) = error("the floor never waits")
        }
    }

    private fun floor(c: Clocks = Clocks()) = c to TopicBootstrapRetryFloor(c.clock) { c.wall }

    // ---- RD rows ------------------------------------------------------------------------------

    /**
     * RD01 — the whole ladder: start, first issue with no wait, 0.5 s and 1.5 s retries, and the third
     * failure exhausting it.
     *
     * Catches: MR-L01 attempt count, MR-L02 BACKOFFS order, MR-D01 due computed from issue time (500),
     * MR-D02 NotYet compared with `<=` (700 answers NotYet(700) instead of Issue), MR-D03 Issue not
     * advancing attempt / issue time, first attempt deferred behind a backoff.
     */
    @Test
    fun RD01_theWholeLadder_issuesRetriesAndExhaustsInOrder() {
        val s0 = P.start(u1, 0, 0)
        assertEquals("start", L(0, null, 0), s0)
        assertEquals("first issue", issue(L(1, 0, 0)), before(s0, 0))
        assertEquals("503 pending", retry(L(1, 0, 700)), after(L(1, 0, 0), pend, 200))
        assertEquals("before due", notYet(700), before(L(1, 0, 700), 699))
        assertEquals("at due", issue(L(2, 700, 700)), before(L(1, 0, 700), 700))
        assertEquals("network, u = 1.0", retry(L(2, 700, 2_700)), after(L(2, 700, 700), net, 900, u = 1.0))
        assertEquals("third issue", issue(L(3, 2_700, 2_700)), before(L(2, 700, 2_700), 2_700))
        assertEquals(
            "429 on the third attempt",
            stopN(ATTEMPTS_EXHAUSTED),
            after(L(3, 2_700, 2_700), wire(429, "<html>Too Many Requests</html>"), 3_000)
        )
    }

    /**
     * RD01b — start, Issue and Retry keep the owner and start revision they were given.
     *
     * Catches: start / Issue / Retry resetting owner or startRevision to defaults (u1 / 0) or ignoring
     * the argument; every other row uses o = u1 and RD01/RD06 use r = 0, so that mutation lives there.
     */
    @Test
    fun RD01b_ownerAndStartRevision_surviveStartIssueAndRetry() {
        assertEquals("①", L(0, null, 123, 7, u2), P.start(u2, 7, 123))
        assertEquals("②", issue(L(1, 123, 123, 7, u2)), before(L(0, null, 123, 7, u2), 123, live = u2, rev = 7))
        assertEquals("③", retry(L(1, 123, 700, 7, u2)), after(L(1, 123, 123, 7, u2), net, 200, live = u2, rev = 7))
    }

    /**
     * RD01c — the third attempt's own answer is kept: a delivery applies and a terminal reason stays.
     *
     * Catches: MR-D24 Deliver / Terminal branch on the last attempt overwritten by ATTEMPTS_EXHAUSTED.
     */
    @Test
    fun RD01c_theLastAttemptsOwnResult_isNotOverwrittenByExhaustion() {
        assertEquals("①", apply, after(L(3, 0, 0), ok, 100))
        assertEquals("②", stopN(UNKNOWN_TOPIC), after(L(3, 0, 0), unk, 100))
    }

    /**
     * RD02 — non-retryable answers end on the first attempt with their own reason; a closed admission
     * ends it too. iOS counterpart: 404 once, 503 three times (TetherDataPathTests.swift:2165, survey quote).
     *
     * Catches: MR-V03 / V05 / V12 on the decision path, admission dropped from the decision step.
     */
    @Test
    fun RD02_nonRetryableAnswers_stopOnTheFirstAttempt() {
        val cases = listOf(
            unk to UNKNOWN_TOPIC,
            wire(401, """{"detail":"Invalid token"}""") to AUTHENTICATION,
            wire(403, """{"detail":"Premium subscription required"}""") to FORBIDDEN,
            TopicSnapshotOutcome.Dormant to TOPICS_DISABLED,
            TopicSnapshotOutcome.Degraded to TOPIC_UNAVAILABLE,
            wire(404, "") to NOT_FOUND_UNRECOGNIZED,
            TopicSnapshotOutcome.Malformed("m") to MALFORMED,
            TopicSnapshotOutcome.TimedOut to TIMED_OUT
        )
        cases.forEachIndexed { index, (o, reason) ->
            assertEquals("case $index: $o", stopN(reason), after(L(1, 0, 0), o, 100))
        }
        assertEquals("admission closed", stopN(ADMISSION_CLOSED), after(L(1, 0, 0), net, 100, adm = false))
    }

    /**
     * RD03 — a token failure is retried a finite number of times, and only while the identity is the
     * very same fence.
     *
     * Catches: MR-V13, MR-D05 uid-only comparison (④ would Retry, O-3), MR-D06 a null live owner
     * treated as a match, unbounded token retries.
     */
    @Test
    fun RD03_tokenFailures_retryFinitely_andOnlyForTheSameFence() {
        assertEquals("① 1", retry(L(1, 0, 600)), after(L(1, 0, 0), tok, 100))
        assertEquals("① 2", retry(L(2, 600, 2_200)), after(L(2, 600, 600), tok, 700))
        assertEquals("① 3", stopN(ATTEMPTS_EXHAUSTED), after(L(3, 2_200, 2_200), tok, 2_300))
        assertEquals("② null", stopN(IDENTITY_MOVED), after(L(1, 0, 0), tok, 100, live = null))
        assertEquals("③ u2", stopN(IDENTITY_MOVED), after(L(1, 0, 0), tok, 100, live = u2))
        assertEquals("④ u1g2", stopN(IDENTITY_MOVED), after(L(1, 0, 0), tok, 100, live = u1g2))
    }

    /**
     * RD04 — identity is checked before classification, after an attempt and before one.
     *
     * Catches: MR-D04 identity checked for token failures only, MR-D07 identity checked after
     * classification (② would be UNKNOWN_TOPIC — another account's answer passed on as a withdrawal),
     * missing pre-attempt identity check; u1g2 in ④ catches a uid-only pre-attempt check.
     */
    @Test
    fun RD04_identityIsCheckedBeforeClassification() {
        assertEquals("① not Apply", stopN(IDENTITY_MOVED), after(L(1, 0, 0), ok, 100, live = u2))
        assertEquals("② not UNKNOWN_TOPIC", stopN(IDENTITY_MOVED), after(L(1, 0, 0), unk, 100, live = u2))
        assertEquals("③", stopN(IDENTITY_MOVED), after(L(1, 0, 0), net, 100, live = null))
        assertEquals("④ u2", stopG(IDENTITY_MOVED), before(L(1, 0, 700), 700, live = u2))
        assertEquals("④ null", stopG(IDENTITY_MOVED), before(L(1, 0, 700), 700, live = null))
        assertEquals("④ u1g2", stopG(IDENTITY_MOVED), before(L(1, 0, 700), 700, live = u1g2))
    }

    /**
     * RD05 — the 10 s budget: exactly 10_000 ms is allowed, one more is not, for every kind of answer.
     * ⑥ mimics the service clock not counting sleep and still returning Delivered; the policy decides
     * only from the clock value it was handed.
     *
     * Catches: MR-D08 `>=` (① would Stop), MR-D09 budget on the success path only (③④ would Retry),
     * MR-D10 budget skipped for Delivered (②⑥ would Apply), budget left to the outcome (TimedOut or not).
     */
    @Test
    fun RD05_theTenSecondBoundary_appliesToEveryOutcome() {
        assertEquals("① elapsed 10_000", apply, after(L(1, 1_000, 1_000), ok, 11_000))
        assertEquals("② ok", stopN(BUDGET_EXCEEDED), after(L(1, 1_000, 1_000), ok, 11_001))
        assertEquals("③ net", stopN(BUDGET_EXCEEDED), after(L(1, 1_000, 1_000), net, 11_001))
        assertEquals("④ pend", stopN(BUDGET_EXCEEDED), after(L(1, 1_000, 1_000), pend, 11_001))
        assertEquals("⑤ unk", stopN(BUDGET_EXCEEDED), after(L(1, 1_000, 1_000), unk, 11_001))
        assertEquals("⑥", stopN(BUDGET_EXCEEDED), after(L(1, 0, 0), ok, 45_000))
    }

    /**
     * RD06 — the budget is measured again from each attempt's own issue time.
     *
     * Catches: MR-D11 budget measured from the first issue (19_000 would be discarded), MR-D03 issue
     * time not refreshed.
     */
    @Test
    fun RD06_theBudgetRestartsWithEachAttempt() {
        assertEquals("retry", retry(L(1, 0, 9_500)), after(L(1, 0, 0), net, 9_000))
        assertEquals("issue", issue(L(2, 9_500, 9_500)), before(L(1, 0, 9_500), 9_500))
        assertEquals("apply", apply, after(L(2, 9_500, 9_500), ok, 19_000))
        assertEquals("contrast", stopN(BUDGET_EXCEEDED), after(L(2, 9_500, 9_500), ok, 19_501))
    }

    /**
     * RD07 — TimedOut stops regardless of the policy's own elapsed time; past 10 s the budget answers first.
     *
     * Catches: MR-D12 TimedOut retried while the policy clock is still inside 10 s.
     */
    @Test
    fun RD07_timedOut_stopsRegardlessOfElapsed() {
        assertEquals(stopN(TIMED_OUT), after(L(1, 0, 0), TopicSnapshotOutcome.TimedOut, 3_000))
        assertEquals(stopN(BUDGET_EXCEEDED), after(L(1, 0, 0), TopicSnapshotOutcome.TimedOut, 10_001))
    }

    /**
     * RD08 — early stop once a snapshot has arrived (revision above the start revision).
     *
     * Catches: MR-D13 `>=` (stops at r = 4), MR-D14 early stop only before an attempt or only at a
     * failure (① or ② passes), MR-D15 Delivered discarded after the revision moved, MR-D16 early stop
     * overwriting a non-retryable reason (⑤), MR-D17 NotYet checked before revision (② now 300 NotYet).
     */
    @Test
    fun RD08_earlyStop_onceASnapshotHasArrived() {
        assertEquals("① r = 4", retry(L(1, 0, 600, 4)), after(L(1, 0, 0, 4), net, 100, rev = 4))
        assertEquals("① r = 5", stopN(SNAPSHOT_ALREADY_RECEIVED), after(L(1, 0, 0, 4), net, 100, rev = 5))
        assertEquals("① r = 9", stopN(SNAPSHOT_ALREADY_RECEIVED), after(L(1, 0, 0, 4), net, 100, rev = 9))
        assertEquals("② at due", stopG(SNAPSHOT_ALREADY_RECEIVED), before(L(1, 0, 600, 4), 600, rev = 5))
        assertEquals("② before due", stopG(SNAPSHOT_ALREADY_RECEIVED), before(L(1, 0, 600, 4), 300, rev = 5))
        assertEquals("③ first attempt", stopG(SNAPSHOT_ALREADY_RECEIVED), before(P.start(u1, 4, 0), 0, rev = 5))
        assertEquals("④ O-9", apply, after(L(1, 0, 0, 4), ok, 100, rev = 5))
        assertEquals("⑤ unk", stopN(UNKNOWN_TOPIC), after(L(1, 0, 0, 4), unk, 100, rev = 5))
        assertEquals(
            "⑤ 403",
            stopN(FORBIDDEN),
            after(L(1, 0, 0, 4), wire(403, """{"detail":"Premium subscription required"}"""), 100, rev = 5)
        )
        assertEquals("⑥", stopN(ATTEMPTS_EXHAUSTED), after(L(3, 0, 0, 4), net, 100, rev = 5))
    }

    /**
     * RD09 ① — the recorded Retry-After floor lifts the due time.
     *
     * Catches: MR-D18 floor ignored (① would be 1_500).
     */
    @Test
    fun RD09_1_aRecordedFloor_liftsTheDue() {
        val (_, f) = floor()
        f.record(503, "5")
        val fl = f.notBeforeMillis()
        assertEquals("floor", 6_000L, fl)
        assertEquals(retry(L(1, 900, 6_000)), after(L(1, 900, 900), pend, 1_000, floor = fl))
    }

    /**
     * RD09 ② — the policy never reads `failure.retryAfter` itself.
     *
     * Catches: MR-D19 policy parsing the header directly (② would be 6_000).
     */
    @Test
    fun RD09_2_thePolicyDoesNotReadTheHeaderItself() {
        assertEquals(retry(L(1, 900, 1_500)), after(L(1, 900, 900), pend, 1_000, floor = 0))
    }

    /** RD09 ③ — a negative Retry-After leaves the floor at or below now (R04), so the backoff stands. */
    @Test
    fun RD09_3_aNegativeRetryAfter_leavesTheBackoff() {
        val (_, f) = floor()
        f.record(503, "-5")
        val fl = f.notBeforeMillis()
        assertTrue("floor $fl", fl <= 1_000L)
        assertEquals(retry(L(1, 900, 1_500)), after(L(1, 900, 900), pend, 1_000, floor = fl))
    }

    /**
     * RD09 ④ — a saturated floor (R06) is kept by `max` and then holds the next attempt (O-4: no cap).
     * Addition overflow (MR-D21) does not occur here; RD10 ③ catches it.
     */
    @Test
    fun RD09_4_aSaturatedFloor_isKept() {
        val (_, f) = floor()
        f.record(429, "99999999999999999999")
        val fl = f.notBeforeMillis()
        assertEquals("floor", Long.MAX_VALUE, fl)
        assertEquals(
            retry(L(1, 900, Long.MAX_VALUE)),
            after(L(1, 900, 900), wire(429, "", listOf("99999999999999999999")), 1_000, floor = fl)
        )
        assertEquals(notYet(Long.MAX_VALUE), before(L(1, 900, Long.MAX_VALUE), 1_000_000))
    }

    /**
     * RD09 ⑤ — due is `max(backoff, floor)`.
     *
     * Catches: MR-D18 `min` used (500 would give 500).
     */
    @Test
    fun RD09_5_dueIsTheLaterOfBackoffAndFloor() {
        assertEquals("500", retry(L(1, 900, 1_500)), after(L(1, 900, 900), pend, 1_000, floor = 500))
        assertEquals("1_500", retry(L(1, 900, 1_500)), after(L(1, 900, 900), pend, 1_000, floor = 1_500))
        assertEquals("1_501", retry(L(1, 900, 1_501)), after(L(1, 900, 900), pend, 1_000, floor = 1_501))
    }

    /**
     * RD09 ⑥ — a floor that rose while waiting is checked again before issuing.
     *
     * Catches: MR-D20 a risen floor not re-read (⑥ would Issue at 1_500).
     */
    @Test
    fun RD09_6_aFloorThatRoseWhileWaiting_isCheckedAgain() {
        assertEquals("1_500", notYet(3_000), before(L(1, 900, 1_500), 1_500, floor = 3_000))
        assertEquals("3_000", issue(L(2, 3_000, 3_000)), before(L(1, 900, 1_500), 3_000, floor = 3_000))
    }

    /** RD09 ⑦ — an HTTP-date Retry-After, translated by the floor (R07), lifts the due time the same way. */
    @Test
    fun RD09_7_anHttpDateFloor_liftsTheDue() {
        val (_, f) = floor()
        f.record(429, "Tue, 29 Sep 2026 00:00:07 GMT")
        val fl = f.notBeforeMillis()
        assertEquals(retry(L(1, 900, 8_000)), after(L(1, 900, 900), pend, 1_000, floor = fl))
    }

    /**
     * RD10 — moving to the ms timeline rounds up, and due saturates instead of wrapping.
     *
     * Catches: MR-D22 ms rounded down (① would be 1_599), MR-D21 overflow wrapping (③).
     */
    @Test
    fun RD10_msRoundUp_andSaturation() {
        assertEquals("①", retry(L(1, 900, 1_600)), after(L(1, 900, 900), net, 1_000, u = 0.999999))
        assertEquals("②", retry(L(2, 900, 2_650)), after(L(2, 900, 900), net, 1_000, u = 0.75))
        val M = Long.MAX_VALUE
        assertEquals("③", retry(L(1, M - 200, Long.MAX_VALUE)), after(L(1, M - 200, M - 200), net, M - 100))
    }

    /**
     * RD11 — topics are independent: jitter is drawn per topic, and one topic's snapshot does not stop
     * another; call order and repetition change nothing.
     *
     * Catches: MR-D23 mutable state inside the object (③ differs; Y03 also catches it), revision shared
     * between topics.
     */
    @Test
    fun RD11_topicsAreIndependent() {
        val A = L(1, 0, 0, 10)
        val B = L(1, 0, 0, 20)
        val expectedA = retry(L(1, 0, 500, 10))
        val expectedB = retry(L(1, 0, 700, 20))
        assertEquals("① A", expectedA, after(A, net, 100, rev = 10, u = 0.0))
        assertEquals("① B", expectedB, after(B, net, 100, rev = 20, u = 1.0))

        assertEquals("② A", stopN(SNAPSHOT_ALREADY_RECEIVED), after(A, net, 100, rev = 11, u = 0.0))
        assertEquals("② B", retry(L(1, 0, 500, 20)), after(B, net, 100, rev = 20, u = 0.0))

        val callA = { after(A, net, 100, rev = 10, u = 0.0) }
        val callB = { after(B, net, 100, rev = 20, u = 1.0) }
        val aThenB = listOf(callA(), callB(), callA(), callB())
        val bThenA = listOf(callB(), callA(), callB(), callA())
        assertEquals("③ A→B", listOf(expectedA, expectedB, expectedA, expectedB), aThenB)
        assertEquals("③ B→A", listOf(expectedB, expectedA, expectedB, expectedA), bThenA)
    }

    /**
     * RD12 ① — a clock running backwards, and a negative issue time, are invalid input; zero elapsed is
     * fine, and a fresh start (no issue time) simply waits for its due. Includes the r2 additions from
     * design 3.3 (`after(L(1,Long.MIN_VALUE,0), ok, 0)`) and 3.4 (`before(L(1,Long.MIN_VALUE,0), 0)`,
     * `before(L(0,null,-1), -1)`).
     *
     * Catches: MR-D25 negative elapsed clamped to 0 (① would Apply), negative issue time overflowing
     * `now - issuedAt` past the budget check.
     */
    @Test
    fun RD12_1_clockRunningBackwards_isInvalidInput() {
        assertEquals("after ok 999", stopN(INVALID_INPUT), after(L(1, 1_000, 1_000), ok, 999))
        assertEquals("after net 999", stopN(INVALID_INPUT), after(L(1, 1_000, 1_000), net, 999))
        assertEquals("after elapsed 0", apply, after(L(1, 1_000, 1_000), ok, 1_000))
        assertEquals("before 999", stopG(INVALID_INPUT), before(L(1, 1_000, 1_500), 999))
        assertEquals("start state", notYet(1_000), before(P.start(u1, 0, 1_000), 999))
        // design 3.3 r2 addition
        assertEquals("after issuedAt MIN", stopN(INVALID_INPUT), after(L(1, Long.MIN_VALUE, 0), ok, 0))
        // design 3.4 r2 additions
        assertEquals("before issuedAt MIN", stopG(INVALID_INPUT), before(L(1, Long.MIN_VALUE, 0), 0))
        assertEquals("before now -1", stopG(INVALID_INPUT), before(L(0, null, -1), -1))
    }

    /**
     * RD12 ② — attempt out of range, and attempt / issue-time disagreement, are invalid input. Includes
     * the r2 additions from design 3.4 (`before(L(1,null,0), 100)`, `before(L(0,50,0), 100)`).
     *
     * Catches: MR-D26 attempt 0 / 4 accepted.
     */
    @Test
    fun RD12_2_attemptOutOfRange_isInvalidInput() {
        assertEquals("after a = 0", stopN(INVALID_INPUT), after(L(0, null, 0), net, 100))
        assertEquals("after a = 4", stopN(INVALID_INPUT), after(L(4, 0, 0), net, 100))
        assertEquals("after issuedAt null", stopN(INVALID_INPUT), after(L(1, null, 0), net, 100))
        assertEquals("before a = 3", stopG(INVALID_INPUT), before(L(3, 0, 0), 100))
        assertEquals("before a = -1", stopG(INVALID_INPUT), before(L(-1, null, 0), 100))
        // design 3.4 r2 additions
        assertEquals("before a = 1 without issuedAt", stopG(INVALID_INPUT), before(L(1, null, 0), 100))
        assertEquals("before a = 0 with issuedAt", stopG(INVALID_INPUT), before(L(0, 50, 0), 100))
    }

    /**
     * RD12 ③ — a revision below the start revision is invalid input.
     *
     * Catches: MR-D27 revision going backwards ignored.
     */
    @Test
    fun RD12_3_revisionGoingBackwards_isInvalidInput() {
        assertEquals("after", stopN(INVALID_INPUT), after(L(1, 0, 0, 5), ok, 100, rev = 4))
        assertEquals("before", stopG(INVALID_INPUT), before(L(1, 0, 600, 5), 600, rev = 4))
    }

    /**
     * RD12 ④ — jitter outside [0, 1] or not finite is invalid input, checked before the outcome and
     * uniformly (O-13); −0.0 equals 0.0 and is accepted.
     *
     * Catches: MR-D28 NaN jitter not validated (NaN due or exception).
     */
    @Test
    fun RD12_4_jitterOutsideTheUnitInterval_isInvalidInput() {
        listOf(Double.NaN, 1.000001, -0.000001, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { x ->
            assertEquals("net u = $x", stopN(INVALID_INPUT), after(L(1, 0, 0), net, 100, u = x))
        }
        assertEquals("ok u = NaN", stopN(INVALID_INPUT), after(L(1, 0, 0), ok, 100, u = Double.NaN))
        assertEquals("contrast u = -0.0", retry(L(1, 0, 500)), after(L(1, 0, 0), net, 100, u = -0.0))
    }

    /**
     * RD13 — the decision order is fixed: validation > budget > identity > classification (admission)
     * > attempts. Exhaustion > early stop is RD08 ⑥; early stop > NotYet is RD08 ②.
     *
     * Catches: MR-D29 every mutation that reorders the decision steps.
     */
    @Test
    fun RD13_theDecisionOrderIsFixed() {
        assertEquals("(a)", stopN(INVALID_INPUT), after(L(1, 0, 0), ok, 20_000, u = Double.NaN))
        assertEquals("(b)", stopN(BUDGET_EXCEEDED), after(L(1, 0, 0), ok, 10_001, live = u2))
        assertEquals("(c)", stopN(IDENTITY_MOVED), after(L(1, 0, 0), net, 100, live = u2, adm = false))
        assertEquals("(d)", stopN(ADMISSION_CLOSED), after(L(3, 0, 0), net, 100, adm = false))
        assertEquals("(e)", stopG(INVALID_INPUT), before(L(3, 0, 0), 100, live = u2))
        assertEquals("(f)", stopG(IDENTITY_MOVED), before(L(1, 0, 600, 4), 600, live = u2, rev = 5))
    }
}
