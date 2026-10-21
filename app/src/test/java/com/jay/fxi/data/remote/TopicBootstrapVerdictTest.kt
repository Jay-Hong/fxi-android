package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.remote.TopicBootstrapVerdict.Deliver
import com.jay.fxi.data.remote.TopicBootstrapVerdict.Retryable
import com.jay.fxi.data.remote.TopicBootstrapVerdict.Terminal
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Response

/**
 * S6-2a pure-policy contract, §4.2 of `s6_2a_contracts.r2.md` (RV01-RV09): S6C3 classification of one bootstrap outcome, and the
 * S6C2 enumeration of the three topic 404s. Rows whose input is `wire(...)` go through the real wire -> outcome classifier
 * (`TopicSnapshotOutcome.kt:102-110`) before the verdict, so the policy is pinned on top of that classifier, never beside it.
 *
 * Grounds: GUIDE:372-384, :390-399, :492; `scope.r5.md:717-731`; OUT:102-110; TSC:508-510; IVM:877-901; server `main.py:50`,
 * :108-113, :3302, :3322-3324, :3333-3334, :3343, :3348-3349, :3352-3353, :3498, :3641, :3648.
 *
 * Unless a row says otherwise, `adm = true`. The helpers of §4.0 are shared verbatim with the ladder and decision classes.
 * The implementation thread reads but does not edit this file.
 */
class TopicBootstrapVerdictTest {

    // §4.0 common helpers — the same definitions in all three S6-2a contract classes.

    private val P = TopicBootstrapRetryPolicy

    private val u1 = AuthIdentityFence("u1", 1L)
    private val u1g2 = AuthIdentityFence("u1", 2L)
    private val u2 = AuthIdentityFence("u2", 1L)

    private fun L(a: Int, i: Long?, d: Long, r: Long = 0L, o: AuthIdentityFence = u1) = TopicBootstrapLadder(o, r, a, i, d)

    /** Same shape as `TopicSnapshotOutcomeTest.kt:167-186`: Retrofit `Response.error` -> `preserve(TOPIC_SNAPSHOT)`. */
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

    private fun wire(code: Int, body: String, retryAfter: List<String> = emptyList()): TopicSnapshotOutcome =
        failure(code, body, retryAfter).toTopicSnapshotOutcome()

    private val ok = TopicSnapshotOutcome.Delivered(DecodedTopicFrame.Pong)
    private val net = TopicSnapshotOutcome.Unreachable(IOException("down"))
    private val tok = TopicSnapshotOutcome.Unreachable(AuthUnavailableException("mint"))
    private val pend = wire(503, """{"detail":"Subscription status pending. Retry later."}""", listOf("5"))
    private val unk = wire(
        404,
        """{"error":"unknown_topic","detail":"topic 'krx:usd-krw-futures' not supported",""" +
            """"supported_topics":["fx:usd-krw","usdt:krw"]}"""
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

    private fun classify(o: TopicSnapshotOutcome, adm: Boolean = true): TopicBootstrapVerdict = P.classify(o, adm)

    /**
     * RV01 (S6C2): the three topic 404s are three reasons, and every other 404 is a fourth that is not a revocation.
     *
     * Catches MR-V01 (one reason keyed on the status), MR-V02 (an unrecognised 404 read as `UNKNOWN_TOPIC` — a fake revoke, ④⑤⑥),
     * MR-V03 (404 retried) and a case-insensitive comparison (⑤).
     */
    @Test
    fun RV01_theThreeTopic404s_areEnumeratedApart_andAnUnrecognised404IsNotAnUnknownTopic() {
        val v1 = classify(wire(404, """{"error":"topics_disabled"}"""))
        val v2 = classify(unk)
        val v2b = classify(wire(404, """{"error":"unknown_topic"}"""))
        val v3 = classify(wire(404, """{"error":"topic_unavailable","topic":"krx:usd-krw-futures"}"""))
        val v4 = classify(wire(404, """{"detail":"Not Found"}"""))
        val v5 = classify(wire(404, """{"error":"UNKNOWN_TOPIC"}"""))
        val v6 = classify(wire(404, ""))

        assertEquals("①", Terminal(TopicBootstrapStop.TOPICS_DISABLED), v1)
        assertEquals("②", Terminal(TopicBootstrapStop.UNKNOWN_TOPIC), v2)
        assertEquals("②'", Terminal(TopicBootstrapStop.UNKNOWN_TOPIC), v2b)
        assertEquals("③", Terminal(TopicBootstrapStop.TOPIC_UNAVAILABLE), v3)
        assertEquals("④", Terminal(TopicBootstrapStop.NOT_FOUND_UNRECOGNIZED), v4)
        assertEquals("⑤ 대소문자가 다른 코드는 미인식", Terminal(TopicBootstrapStop.NOT_FOUND_UNRECOGNIZED), v5)
        assertEquals("⑥ 빈 본문", Terminal(TopicBootstrapStop.NOT_FOUND_UNRECOGNIZED), v6)
        assertEquals("①②③④가 네 사유로 갈리지 않았다", 4, setOf(v1, v2, v3, v4).size)
        listOf(v1, v2, v2b, v3, v4, v5, v6).forEachIndexed { i, v ->
            assertFalse("404 #$i 를 재시도했다", v is Retryable)
        }
    }

    /**
     * RV02: the verdict carries no supported-topic list, because that list is an entitlement (TSC:508-510).
     *
     * Catches MR-V04 (the verdict holds `supportedTopics`).
     */
    @Test
    fun RV02_anUnknownTopicVerdict_doesNotCarryTheSupportedList() {
        val v = classify(TopicSnapshotOutcome.Unsupported(listOf("fx:usd-krw", "usdt:krw", "krx:usd-krw-futures")), true)

        assertEquals(Terminal(TopicBootstrapStop.UNKNOWN_TOPIC), v)
        val text = v.toString()
        listOf("fx:usd-krw", "usdt:krw", "krx:usd-krw-futures").forEach { topic ->
            assertFalse("판정의 toString()에 지원 목록 '$topic'이 드러났다", text.contains(topic))
        }
    }

    /**
     * RV03: 401 ends as AUTHENTICATION, 403 as FORBIDDEN whatever its kind.
     *
     * Catches MR-V05 (401·403 retried, or 403 ended only for one kind). ③④ are both UNKNOWN_AUTHORIZATION at TOPIC_SNAPSHOT
     * (`AuthenticatedHttpResponse.kt:208-216`, `TopicSnapshotOutcomeTest.kt:164`), so ⑤ is built directly as KNOWN_AUTHORIZATION
     * and kills "end only when UNKNOWN".
     */
    @Test
    fun RV03_401IsAuthentication_and403IsForbiddenWhateverItsKind() {
        val forbiddenPremium = wire(403, """{"detail":"Premium subscription required"}""")
        val forbiddenPlain = wire(403, """{"detail":"Forbidden"}""")
        val forbiddenKnown = TopicSnapshotOutcome.Refused(
            AuthenticatedHttpFailure(
                statusCode = 403,
                headers = emptyMap<String, String>().toHeaders(),
                rawBody = """{"detail":"Premium subscription required"}""".toByteArray(),
                error = null,
                detail = JsonPrimitive("Premium subscription required"),
                kind = AuthenticatedFailureKind.KNOWN_AUTHORIZATION
            )
        )

        // precondition for ⑤: ③④ reach the policy as UNKNOWN_AUTHORIZATION (AuthenticatedHttpResponse.kt:208-216)
        assertEquals(AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION, (forbiddenPremium as TopicSnapshotOutcome.Refused).failure.kind)
        assertEquals(AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION, (forbiddenPlain as TopicSnapshotOutcome.Refused).failure.kind)

        assertEquals("①", Terminal(TopicBootstrapStop.AUTHENTICATION), classify(wire(401, """{"detail":"Token expired"}""")))
        assertEquals(
            "②",
            Terminal(TopicBootstrapStop.AUTHENTICATION),
            classify(wire(401, """{"detail":"Missing or invalid Authorization header"}"""))
        )
        assertEquals("③", Terminal(TopicBootstrapStop.FORBIDDEN), classify(forbiddenPremium))
        assertEquals("④", Terminal(TopicBootstrapStop.FORBIDDEN), classify(forbiddenPlain))
        assertEquals("⑤ KNOWN_AUTHORIZATION", Terminal(TopicBootstrapStop.FORBIDDEN), classify(forbiddenKnown))
    }

    /**
     * RV04: every retryable HTTP answer, the three 503 causes included (GUIDE:372-379, IVM:891).
     *
     * Catches MR-V06 (PENDING or auth-infrastructure 503 ended as an auth failure, against GUIDE:373), MR-V07 (408·429 not retried),
     * MR-V08 (④ merged into `HTTP_5XX`, losing what the classifier told apart).
     */
    @Test
    fun RV04_retryableHttp_keepsItsKind() {
        assertEquals("① PENDING", Retryable(TopicBootstrapRetryKind.HTTP_5XX), classify(pend))
        assertEquals(
            "② 인증 인프라",
            Retryable(TopicBootstrapRetryKind.HTTP_5XX),
            classify(wire(503, """{"detail":"Firebase auth unavailable"}"""))
        )
        assertEquals(
            "③ 인증 인프라",
            Retryable(TopicBootstrapRetryKind.HTTP_5XX),
            classify(wire(503, """{"detail":"Firebase not initialized"}"""))
        )
        assertEquals(
            "④",
            Retryable(TopicBootstrapRetryKind.TEMPORARILY_UNAVAILABLE),
            classify(wire(503, """{"error":"temporarily_unavailable"}"""))
        )
        assertEquals("⑤", Retryable(TopicBootstrapRetryKind.HTTP_408), classify(wire(408, "")))
        assertEquals(
            "⑥",
            Retryable(TopicBootstrapRetryKind.HTTP_429),
            classify(wire(429, "<html>Too Many Requests</html>"))
        )
        assertEquals(
            "⑦",
            Retryable(TopicBootstrapRetryKind.HTTP_5XX),
            classify(wire(500, """{"detail":"Internal Server Error"}"""))
        )
        listOf(502, 504, 599).forEach { c ->
            assertEquals("⑧ $c", Retryable(TopicBootstrapRetryKind.HTTP_5XX), classify(wire(c, "")))
        }
    }

    /**
     * RV05: other statuses do not retry, an unknown code fails closed (O-5), and a topic code on the wrong status is not a
     * topic verdict (`TopicSnapshotOutcomeTest.kt:143`).
     *
     * Catches MR-V09 (`>= 500`: ② retried), MR-V10 (4xx retried wholesale), MR-V11 (the `error` string read regardless of
     * status: ③ becomes `UNKNOWN_TOPIC`).
     */
    @Test
    fun RV05_nonRetryableAndUnknownStatuses_endWithoutRetry() {
        listOf(400, 405, 409, 410, 418, 422, 499).forEach { c ->
            assertEquals("① $c", Terminal(TopicBootstrapStop.HTTP_NOT_RETRYABLE), classify(wire(c, "")))
        }
        assertEquals("② 600", Terminal(TopicBootstrapStop.HTTP_NOT_RETRYABLE), classify(wire(600, "")))
        assertEquals(
            "③ 503의 unknown_topic",
            Retryable(TopicBootstrapRetryKind.HTTP_5XX),
            classify(wire(503, """{"error":"unknown_topic"}"""))
        )
        assertEquals(
            "④ 404의 temporarily_unavailable",
            Terminal(TopicBootstrapStop.NOT_FOUND_UNRECOGNIZED),
            classify(wire(404, """{"error":"temporarily_unavailable"}"""))
        )
    }

    /**
     * RV06: a 2xx that is not a usable snapshot and a spent attempt budget both end; a snapshot delivers.
     *
     * Catches MR-V12 (Malformed·TimedOut retried).
     */
    @Test
    fun RV06_malformedAndTimedOutEnd_andDeliveredDelivers() {
        assertEquals(
            "①",
            Terminal(TopicBootstrapStop.MALFORMED),
            classify(TopicSnapshotOutcome.Malformed("successful response carried no body"))
        )
        assertEquals(
            "②",
            Terminal(TopicBootstrapStop.MALFORMED),
            classify(TopicSnapshotOutcome.Malformed("asked for a and was answered b"))
        )
        assertEquals("③", Terminal(TopicBootstrapStop.TIMED_OUT), classify(TopicSnapshotOutcome.TimedOut))
        assertEquals("④", Deliver, classify(ok))
    }

    /**
     * RV07: unreachable is NETWORK, and a token failure — any `AuthUnavailableException`, subtypes included because it is an
     * `open class` (`AuthSnapshot.kt:51`) — is TOKEN.
     *
     * Catches MR-V13 (a token failure ended statically, against GUIDE:399).
     */
    @Test
    fun RV07_unreachableIsNetwork_andATokenFailureIsToken() {
        assertEquals("①", Retryable(TopicBootstrapRetryKind.NETWORK), classify(net))
        assertEquals(
            "②",
            Retryable(TopicBootstrapRetryKind.NETWORK),
            classify(TopicSnapshotOutcome.Unreachable(SocketTimeoutException()))
        )
        assertEquals("③", Retryable(TopicBootstrapRetryKind.TOKEN), classify(tok))
        assertEquals(
            "④",
            Retryable(TopicBootstrapRetryKind.TOKEN),
            classify(TopicSnapshotOutcome.Unreachable(AuthUnavailableException("firebase", IOException("x"))))
        )
        assertEquals(
            "⑤ 하위 타입",
            Retryable(TopicBootstrapRetryKind.TOKEN),
            classify(TopicSnapshotOutcome.Unreachable(object : AuthUnavailableException("sub") {}))
        )
    }

    /**
     * RV08 ①-⑥: a cancellation anywhere in the cause chain is CANCELLED, never a retry.
     *
     * Catches MR-V14 (a cancellation retried as NETWORK — the iOS `URLError.cancelled` exclusion, IVM:880-882), MR-V15 (only the
     * top level checked: ③), MR-V16 (④ read as TOKEN).
     */
    @Test
    fun RV08_aCancellationAnywhereInTheCauseChain_isCancelled() {
        assertEquals(
            "①",
            Terminal(TopicBootstrapStop.CANCELLED),
            classify(TopicSnapshotOutcome.Unreachable(CancellationException("x")))
        )
        assertEquals(
            "②",
            Terminal(TopicBootstrapStop.CANCELLED),
            classify(TopicSnapshotOutcome.Unreachable(IOException("c", CancellationException())))
        )
        assertEquals(
            "③ 두 단계 아래",
            Terminal(TopicBootstrapStop.CANCELLED),
            classify(TopicSnapshotOutcome.Unreachable(IOException("a", IOException("b", CancellationException()))))
        )
        assertEquals(
            "④",
            Terminal(TopicBootstrapStop.CANCELLED),
            classify(TopicSnapshotOutcome.Unreachable(AuthIdentityChangedException()))
        )
        assertEquals(
            "⑤",
            Terminal(TopicBootstrapStop.CANCELLED),
            classify(TopicSnapshotOutcome.Unreachable(TopicUseWithheldException(emptyList())))
        )
        assertEquals(
            "⑥",
            Terminal(TopicBootstrapStop.CANCELLED),
            classify(TopicSnapshotOutcome.Unreachable(AuthUnavailableException("m", CancellationException())))
        )
    }

    /**
     * RV08 ⑦: a cyclic cause chain still ends, as NETWORK.
     *
     * Catches MR-V17 (the cause chain walked without end: the test times out).
     */
    @Test(timeout = 2_000)
    fun RV08_aCyclicCauseChain_endsAsNetwork() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)

        assertEquals("⑦", Retryable(TopicBootstrapRetryKind.NETWORK), classify(TopicSnapshotOutcome.Unreachable(a)))
    }

    /**
     * RV09: admission is an input, not a message (`ReleaseAdmission.kt:5-14`, O-2); when it is closed every outcome ends.
     *
     * Catches MR-V18 (admission ignored: ① retried), MR-V19 (decided by message match: ② becomes ADMISSION_CLOSED), MR-V20
     * (applied to Unreachable only: ③ `ok` delivers).
     */
    @Test
    fun RV09_closedAdmission_endsEveryOutcome_andIsNotReadFromTheMessage() {
        val offMessage = TopicSnapshotOutcome.Unreachable(IOException("D24 release admission is OFF"))

        assertEquals("①", Terminal(TopicBootstrapStop.ADMISSION_CLOSED), classify(offMessage, adm = false))
        assertEquals("② 메시지는 보지 않는다", Retryable(TopicBootstrapRetryKind.NETWORK), classify(offMessage, adm = true))
        listOf(
            ok,
            TopicSnapshotOutcome.Dormant,
            TopicSnapshotOutcome.Unsupported(emptyList()),
            TopicSnapshotOutcome.Degraded,
            TopicSnapshotOutcome.TemporarilyUnavailable,
            pend,
            TopicSnapshotOutcome.Malformed("m"),
            TopicSnapshotOutcome.TimedOut,
            tok,
            TopicSnapshotOutcome.Unreachable(CancellationException())
        ).forEachIndexed { i, o ->
            assertEquals("③ #$i", Terminal(TopicBootstrapStop.ADMISSION_CLOSED), classify(o, adm = false))
        }
    }
}
