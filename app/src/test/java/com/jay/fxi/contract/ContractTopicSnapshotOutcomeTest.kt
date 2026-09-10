package com.jay.fxi.contract

import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.DecodedTopicFrame
import com.jay.fxi.data.remote.TopicFrameDecoder
import com.jay.fxi.data.remote.TopicSnapshotOutcome
import com.jay.fxi.data.remote.preserve
import com.jay.fxi.data.remote.toTopicSnapshotOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The captured bytes, put through the code that will read them.
 *
 * The other contract harness replays fixtures over a bare client and inspects the raw response, so
 * nothing it does ever reaches the failure classifier. These fixtures exist to pin what the client
 * *concludes* from one topic bootstrap answer, and only this path computes that — recorded bytes and
 * recorded status through `preserve` and into the outcome. Without it the corpus would be decoration.
 *
 * **What a fixture can and cannot prove about headers.** A recorded header proves the server sent
 * it. An absent one proves nothing here: the capture writes only the headers it asked for, so
 * "absent" and "never requested" are the same bytes on disk. Absence is asserted on the producing
 * side instead (`forbidden_headers` in the exporter, against the live response). What is checked
 * here is the other half — that classifying an answer with no retry input does not invent one, and
 * that a real one still travels.
 */
class ContractTopicSnapshotOutcomeTest {

    private val corpus by lazy { ContractCorpusVerifier.verify(ContractResourceLoader.load()) }
    private val wireJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
        isLenient = false
        explicitNulls = true
    }
    private val decoder = TopicFrameDecoder(wireJson)

    /** Every refusal this route can give, told apart by the body rather than the status. */
    @Test
    fun `each captured refusal reaches its own verdict`() {
        val dormant = outcomeOf("http-topic-snapshot-topics-disabled-404")
        val unsupported = outcomeOf("http-topic-snapshot-unknown-topic-404")
        val degraded = outcomeOf("http-topic-snapshot-topic-unavailable-404")
        val outage = outcomeOf("http-topic-snapshot-temporarily-unavailable-503")

        assertEquals(TopicSnapshotOutcome.Dormant, dormant)
        assertTrue("미지원 topic 이 미지원 판정이 아니다", unsupported is TopicSnapshotOutcome.Unsupported)
        assertEquals(TopicSnapshotOutcome.Degraded, degraded)
        assertEquals(TopicSnapshotOutcome.TemporarilyUnavailable, outage)
    }

    /**
     * Three of those share a status code, and the captures prove it is not enough.
     *
     * Two 404s with different meanings and a 503 that is a topic outage — while another captured
     * 503 on a different route is a subscription still being decided. A classifier keyed on the
     * status would collapse each pair.
     */
    @Test
    fun `the captured statuses alone do not separate the verdicts`() {
        val statuses = listOf(
            "http-topic-snapshot-topics-disabled-404",
            "http-topic-snapshot-unknown-topic-404",
            "http-topic-snapshot-topic-unavailable-404"
        ).map { corpus.entry(it).httpStatus() }

        assertEquals("세 404 가 같은 상태가 아니게 됐다", listOf(404, 404, 404), statuses)
        assertEquals(503, corpus.entry("http-topic-snapshot-temporarily-unavailable-503").httpStatus())
        assertEquals(503, corpus.entry("http-premium-pending-503").httpStatus())
    }

    /**
     * The capability is hidden by the **per-user** judgment, and the pair is what shows it.
     *
     * Both captures ask for the same topic with the global distribution gates open and premium
     * active. The only difference is whether the user holds the entitlement — and the answer's
     * `error` changes with it: without it the topic is one the server does not have, with it the
     * topic is acknowledged and merely has nothing to serve right now. A filter that stopped
     * consulting the user would make these two identical, and that is the failure this pins.
     *
     * Recorded as such: both carry `g2=on, g3=on`, and only `g1` differs. An earlier version of
     * this test read the plain unsupported-topic capture, where the global gates were shut — that
     * proved distribution was off and would have passed with the per-user filter broken. Found by
     * review, which measured that the entitlement term was never evaluated.
     */
    @Test
    fun `the entitled topic is hidden by the per-user judgment, not by the global gate`() {
        val hidden = outcomeOf("http-topic-snapshot-krx-hidden-404")
        val entitled = outcomeOf("http-topic-snapshot-krx-entitled-404")

        assertTrue("자격 없는 사용자에게 KRX 가 미지원으로 숨겨지지 않았다", hidden is TopicSnapshotOutcome.Unsupported)
        val supported = (hidden as TopicSnapshotOutcome.Unsupported).supportedTopics
        assertTrue("목록을 읽지 못했다", supported.isNotEmpty())
        assertFalse("자격 없는 사용자에게 KRX 가 노출됐다", "krx:usd-krw-futures" in supported)
        assertTrue("일반 topic 이 목록에서 함께 사라졌다", "usdt:krw" in supported)

        assertEquals(
            "자격이 있는데도 존재를 숨겼다 — per-user 판정이 결과를 바꾸지 않는다",
            TopicSnapshotOutcome.Degraded,
            entitled
        )
        assertEquals(
            "g1 이 두 캡처에서 같다면 이 쌍은 아무것도 가르지 않는다",
            listOf("off", "on"),
            listOf("krx-hidden-404", "krx-entitled-404").map { suffix ->
                corpus.entry("http-topic-snapshot-$suffix").config.g1.name.lowercase()
            }
        )
    }

    /** A topic string the server does not serve at all is refused, list attached. */
    @Test
    fun `an unsupported topic string is refused with the list`() {
        val outcome = outcomeOf("http-topic-snapshot-unknown-topic-404")

        assertTrue(outcome is TopicSnapshotOutcome.Unsupported)
        assertTrue((outcome as TopicSnapshotOutcome.Unsupported).supportedTopics.isNotEmpty())
    }

    /**
     * No retry floor is invented here, and a real one still travels.
     *
     * The negative half is only as strong as the capture that produced it — the exporter is what
     * refuses a `Retry-After` on that response. The positive half is the control, and its scope is
     * narrow: the captured `Retry-After: 5` comes from the pending-subscription 503 on **another
     * route**, put through this same Android classifier. It says the pipeline carries a real header
     * through — not that the topic route was ever observed sending one.
     */
    @Test
    fun `a retry floor is neither invented nor lost`() {
        val outage = failureOf("http-topic-snapshot-temporarily-unavailable-503")
        val pending = failureOf("http-premium-pending-503")

        assertEquals(TopicSnapshotOutcome.TemporarilyUnavailable, outage.toTopicSnapshotOutcome())
        assertNull("장애 응답에 없던 재시도 하한이 생겼다", outage.retryAfter)
        assertEquals("실제로 보낸 재시도 하한을 흘렸다", "5", pending.retryAfter)
        assertTrue(
            "구독 판정 대기를 topic 장애로 읽었다",
            pending.toTopicSnapshotOutcome() is TopicSnapshotOutcome.Refused
        )
    }

    /**
     * The route hands back the builder's payload and adds nothing.
     *
     * The captured 200 is compared with the WS golden the same builder produces: same tree, and it
     * decodes through the production decoder to the tether frame. A wrapper, a rename or a re-keyed
     * envelope on the REST side would show up as a difference here rather than in the field.
     */
    @Test
    fun `the captured success is the socket's payload untouched`() {
        val rest = Json.parseToJsonElement(corpus.text("http-topic-snapshot-tether-200")).jsonObject
        val socket = Json.parseToJsonElement(corpus.text("topic-snapshot-tether")).jsonObject

        assertEquals("REST 200 이 WS golden 과 다른 트리다", socket, rest)
        val frame = decoder.decode(corpus.text("http-topic-snapshot-tether-200"))
        assertTrue(frame is DecodedTopicFrame.Tether)
        assertEquals("usdt:krw", (frame as DecodedTopicFrame.Tether).value.topic)
    }

    /**
     * The shared classifier stays coarse for this endpoint, and the corpus is what says so.
     *
     * Its verdicts come from the body's `error`. This test locks the existing shared kinds
     * without adding endpoint-specific rules to `classifyFailure`.
     */
    @Test
    fun `the captured refusals keep the shared kinds coarse`() {
        listOf(
            "http-topic-snapshot-topics-disabled-404",
            "http-topic-snapshot-unknown-topic-404",
            "http-topic-snapshot-topic-unavailable-404"
        ).forEach { id ->
            assertEquals(id, AuthenticatedFailureKind.UNKNOWN_NOT_FOUND, failureOf(id).kind)
        }
        assertEquals(
            AuthenticatedFailureKind.OTHER_HTTP,
            failureOf("http-topic-snapshot-temporarily-unavailable-503").kind
        )
    }

    /** Every capture is a real route traversal, not a builder's output relabelled. */
    @Test
    fun `the topic snapshot captures came off the route`() {
        val ids = corpus.entriesById.keys.filter { it.startsWith("http-topic-snapshot-") }

        assertEquals(
            setOf(
                "http-topic-snapshot-topics-disabled-404",
                "http-topic-snapshot-unknown-topic-404",
                "http-topic-snapshot-krx-hidden-404",
                "http-topic-snapshot-krx-entitled-404",
                "http-topic-snapshot-topic-unavailable-404",
                "http-topic-snapshot-temporarily-unavailable-503",
                "http-topic-snapshot-tether-200"
            ),
            ids.toSet()
        )
        ids.forEach { id ->
            val entry = corpus.entry(id)
            assertEquals(id, ContractOrigin.ROUTE, entry.origin)
            assertTrue(id, entry.source["routeTraversed"]!!.jsonPrimitive.content.toBoolean())
            val http = entry.source["http"] as JsonObject
            assertEquals(id, "GET", http["method"]!!.jsonPrimitive.content)
            assertTrue(id, http["path"]!!.jsonPrimitive.content.startsWith("/api/v2/topics/snapshot"))
            assertNotNull(id, http["headers"])
        }
    }

    private fun ContractFixtureEntry.httpStatus(): Int =
        (source["http"] as JsonObject)["status"]!!.jsonPrimitive.content.toInt()

    /** The captured bytes and status, put back together as the response the client would see. */
    private fun failureOf(id: String) = responseOf(id)
        .preserve(AuthenticatedEndpoint.TOPIC_SNAPSHOT)
        .failure!!

    private fun outcomeOf(id: String) = failureOf(id).toTopicSnapshotOutcome()

    private fun responseOf(id: String): Response<ResponseBody> {
        val entry = corpus.entry(id)
        val http = entry.source["http"] as JsonObject
        val status = http["status"]!!.jsonPrimitive.content.toInt()
        val headers = (http["headers"] as JsonObject)
            .mapValues { (_, value) -> value.jsonPrimitive.content }
            .toHeaders()
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.invalid${http["path"]!!.jsonPrimitive.content}").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(status)
            .message("captured")
            .headers(headers)
            .build()
        return Response.error(corpus.bytes(id).toResponseBody(), raw)
    }
}
