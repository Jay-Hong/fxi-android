package com.jay.fxi.data.remote

import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * One route, six non-2xx meanings, and only the body tells them apart.
 *
 * Numbers are chosen to **discriminate**: every case here shares a status code with at least one
 * other case, so a mapper keyed on the status alone passes none of them.
 */
class TopicSnapshotOutcomeTest {

    /**
     * The dormant endpoint is not a verdict about a topic.
     *
     * It is answered before authentication runs, so it says nothing about this user or this topic —
     * and a two-way 404 split with a catch-all would file a pre-release server as a revoked grant.
     */
    @Test
    fun `a disabled endpoint reads as dormant, not as a topic verdict`() {
        val outcome = failure(404, """{"error":"topics_disabled"}""").toTopicSnapshotOutcome()

        assertEquals(TopicSnapshotOutcome.Dormant, outcome)
    }

    /**
     * The two post-auth 404s stay apart, and the list comes with the first one.
     *
     * S6 reads the `error` value to tell a revoke from an outage; collapsing them would make a
     * temporary outage look like a capability being taken away.
     */
    @Test
    fun `the two post-auth 404s stay distinct and carry their evidence`() {
        val unknown = failure(
            404,
            """{"error":"unknown_topic","detail":"topic 'krx:usd-krw-futures' not supported",""" +
                """"supported_topics":["fx:usd-krw","usdt:krw"]}"""
        ).toTopicSnapshotOutcome()
        val unavailable = failure(
            404,
            """{"error":"topic_unavailable","topic":"fx:usd-krw"}"""
        ).toTopicSnapshotOutcome()

        assertEquals(
            "존재 은닉 목록을 잃었다",
            TopicSnapshotOutcome.Unsupported(listOf("fx:usd-krw", "usdt:krw")),
            unknown
        )
        assertEquals(TopicSnapshotOutcome.Degraded, unavailable)
    }

    /**
     * `supported_topics` is read from the raw body, so a body without one is an empty list.
     *
     * The shared transport parses `error` and `detail` and nothing else, and widening that would
     * change every endpoint — so this list has to be re-read, and re-reading must never throw.
     */
    @Test
    fun `a hidden-topic 404 with no list is still an unsupported verdict`() {
        val outcome = failure(404, """{"error":"unknown_topic"}""").toTopicSnapshotOutcome()

        assertEquals(TopicSnapshotOutcome.Unsupported(emptyList()), outcome)
    }

    /**
     * The 503 that is a topic outage, and the two that are not.
     *
     * A subscription still being decided answers 503 as well — with a `Retry-After` the server does
     * mean — and so does an authentication backend that is down. Reading either as an outage spends
     * a topic verdict on something that was never about the topic, and throws away the one retry
     * floor the server actually sent.
     */
    @Test
    fun `only the outage 503 is a topic verdict`() {
        val outage = failure(503, """{"error":"temporarily_unavailable"}""")
            .toTopicSnapshotOutcome()
        val pending = failure(
            503,
            """{"detail":"Subscription status pending. Retry later."}""",
            retryAfter = listOf("5")
        ).toTopicSnapshotOutcome()
        val authInfra = failure(503, """{"detail":"Firebase auth unavailable"}""")
            .toTopicSnapshotOutcome()

        assertEquals(TopicSnapshotOutcome.TemporarilyUnavailable, outage)
        assertTrue("구독 판정 대기를 topic 장애로 읽었다", pending is TopicSnapshotOutcome.Refused)
        assertEquals("서버가 준 재시도 하한을 버렸다", "5", (pending as TopicSnapshotOutcome.Refused).failure.retryAfter)
        assertTrue("인증 인프라 장애를 topic 장애로 읽었다", authInfra is TopicSnapshotOutcome.Refused)
    }

    /** The outage code has no retry input at all, and inventing one is the storm it avoids. */
    @Test
    fun `the outage 503 carries no retry floor`() {
        val outcome = failure(503, """{"error":"temporarily_unavailable"}""")

        assertNull("REST 에 없는 재시도 하한이 생겼다", outcome.retryAfter)
        assertEquals(TopicSnapshotOutcome.TemporarilyUnavailable, outcome.toTopicSnapshotOutcome())
    }

    /**
     * The proxy's refusal is not JSON at all, and it must survive as bytes.
     *
     * Rate limiting is levied before the application, so the body is an HTML page: a mapper that
     * assumes a JSON object here throws inside the failure path, and one that guesses lets a proxy
     * page masquerade as a topic verdict.
     */
    @Test
    fun `an html rate limit stays untyped with its bytes intact`() {
        val html = "<html><head><title>429</title></head></html>"
        val refused = failure(429, html)

        val outcome = refused.toTopicSnapshotOutcome()

        assertTrue(outcome is TopicSnapshotOutcome.Refused)
        assertNull(refused.error)
        assertArrayEquals(html.toByteArray(), (outcome as TopicSnapshotOutcome.Refused).failure.rawBodyBytes())
    }

    /** A plain server fault has no `error` at all, and must not become a terminal topic verdict. */
    @Test
    fun `an untyped 500 is refused rather than judged`() {
        val outcome = failure(500, """{"detail":"Internal Server Error"}""").toTopicSnapshotOutcome()

        assertTrue(outcome is TopicSnapshotOutcome.Refused)
        assertEquals(500, (outcome as TopicSnapshotOutcome.Refused).failure.statusCode)
    }

    /**
     * The code follows the status it was sent with, and not the status alone.
     *
     * A verdict string that turns up on the wrong status is a server this client does not know, and
     * guessing its meaning is how a matrix drifts away from the contract it was written against.
     */
    @Test
    fun `a topic code on the wrong status is not a topic verdict`() {
        val outcome = failure(400, """{"error":"unknown_topic"}""").toTopicSnapshotOutcome()

        assertTrue(outcome is TopicSnapshotOutcome.Refused)
    }

    /**
     * The shared kinds stay coarse here, deliberately.
     *
     * Nothing at this endpoint is registered with `classifyFailure`, because its known-404 rule
     * requires `error == null` and an exact `detail` literal — the inverse of this endpoint's shape
     * — and because a new kind would have to be answered by every unrelated `when` over the enum.
     * If someone adds a branch for this endpoint, this is what says so.
     */
    @Test
    fun `the shared classifier is left alone for this endpoint`() {
        val notFound = failure(404, """{"error":"unknown_topic"}""")
        val forbidden = failure(403, """{"detail":"Premium subscription required"}""")

        assertEquals(AuthenticatedFailureKind.UNKNOWN_NOT_FOUND, notFound.kind)
        assertEquals(AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION, forbidden.kind)
    }

    private fun failure(
        code: Int,
        body: String,
        retryAfter: List<String> = emptyList()
    ): AuthenticatedHttpFailure {
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
}
