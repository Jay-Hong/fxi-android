package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicWholeRequestFailure
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicMessageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `subscribe carries token and request id while unsubscribe omits token`() {
        val subscribe = json.parseToJsonElement(
            json.encodeToString(
                TopicSubscribeRequest.subscribe("req-1", "tok-1", listOf("fx:usd-krw"))
            )
        ).jsonObject
        assertEquals("\"subscribe\"", subscribe.getValue("type").toString())
        assertEquals("\"req-1\"", subscribe.getValue("request_id").toString())
        assertEquals("\"tok-1\"", subscribe.getValue("id_token").toString())

        val unsubscribe = json.parseToJsonElement(
            json.encodeToString(
                TopicSubscribeRequest.unsubscribe("req-2", listOf("usdt:krw"))
            )
        ).jsonObject
        assertFalse(unsubscribe.containsKey("id_token"))
    }

    @Test
    fun `ack decodes object containers and mixed stage leases`() {
        val ack = json.decodeFromString<SubscriptionAck>(
            """
            {
              "type":"subscription_ack",
              "request_id":"r1",
              "operation":"subscribe",
              "accepted_topics":[{"topic":"fx:usd-krw"}],
              "rejected_topics":[{"topic":"krx:usd-krw-futures","error":"topic_unavailable"}],
              "removed_topics":[],
              "active_subscriptions":[
                {"topic":"fx:usd-krw"},
                {"topic":"usdt:krw","lease_id":"L1","lease_duration_seconds":660}
              ]
            }
            """.trimIndent()
        )

        assertEquals("r1", ack.requestId)
        assertEquals(listOf("fx:usd-krw"), ack.acceptedTopics.map { it.topic })
        assertEquals(TopicRejectionReason.TOPIC_UNAVAILABLE, ack.rejectedTopics.single().reasonOrNull())
        assertEquals(
            TopicLeaseReadResult.Valid(listOf(TopicLease("usdt:krw", "L1", 660L))),
            readTopicLeases(ack.activeSubscriptions)
        )
    }

    @Test
    fun `half lease pairs and invalid values fail closed`() {
        val malformed = listOf(
            SubscriptionAckTopic("a", leaseId = "L1"),
            SubscriptionAckTopic("a", leaseDurationSeconds = 60),
            SubscriptionAckTopic("a", leaseId = "", leaseDurationSeconds = 60),
            SubscriptionAckTopic("a", leaseId = "L1", leaseDurationSeconds = -1)
        )

        malformed.forEach { item ->
            assertEquals(TopicLeaseReadResult.Malformed, readTopicLeases(listOf(item)))
        }
    }

    @Test
    fun `whole request errors use terminal and retryable allowlists`() {
        val terminal = json.decodeFromString<SubscriptionError>(
            """{"type":"subscription_error","request_id":"r1","error":"invalid_token"}"""
        )
        assertTrue(terminal.isTerminal)
        assertFalse(terminal.isRetryable)
        assertEquals(TopicWholeRequestFailure.InvalidToken, terminal.wholeFailureOrNull())

        val transient = json.decodeFromString<SubscriptionError>(
            """{"type":"subscription_error","request_id":"r2","error":"temporarily_unavailable","retry_after_seconds":5}"""
        )
        assertFalse(transient.isTerminal)
        assertTrue(transient.isRetryable)
        assertEquals(
            TopicWholeRequestFailure.TemporarilyUnavailable(5L),
            transient.wholeFailureOrNull()
        )

        val unknown = SubscriptionError("r3", "future_server_error", 1L)
        assertFalse(unknown.isTerminal)
        assertFalse(unknown.isRetryable)
        assertNull(unknown.wholeFailureOrNull())
    }

    @Test
    fun `temporarily unavailable without a valid delay is not materialized`() {
        assertNull(SubscriptionError("r1", "temporarily_unavailable").wholeFailureOrNull())
        assertNull(SubscriptionError("r1", "temporarily_unavailable", 0L).wholeFailureOrNull())
    }

    @Test
    fun `tether payload preserves rate changed time and optional groups`() {
        val message = json.decodeFromString<TetherTopicMessage>(
            """
            {
              "type":"snapshot",
              "version":1,
              "topic":"usdt:krw",
              "data":{
                "usdt_krw":[{
                  "source":"upbit","asset":"usdt-krw","rate":1485.0,
                  "timestamp":"2026-05-12T06:00:00Z",
                  "rate_changed_at":"2026-05-12T06:00:03.123456Z"
                }],
                "usd_krw_banks":[{
                  "source":"kb","asset":"usd-krw","rate":1400.0,
                  "timestamp":"2026-05-12T06:00:00Z"
                }]
              }
            }
            """.trimIndent()
        )

        assertEquals(2, message.data.allEntries.size)
        assertEquals(
            Instant.parse("2026-05-12T06:00:03.123456Z"),
            message.data.usdtKrw.single().mergeAt
        )
        assertNull(message.data.usdKrwReference)
    }

    /**
     * A futures group on the wire never reaches the tether domain.
     *
     * D8. ADR-038 D2 moved KRX to a topic of its own, and an older server can still send the key.
     * `ignoreUnknownKeys` absorbs it only because nothing declares it — the field used to be
     * declared, parsed and folded into `allEntries`, which put a KRX quote inside the tether domain
     * on a build that has no entitlement check for one. Asserted through `allEntries` rather than
     * through the absent field, because the absent field is exactly what cannot be named here.
     */
    @Test
    fun `a futures group on the wire never reaches the tether domain`() {
        val message = json.decodeFromString<TetherTopicMessage>(
            """
            {
              "type":"snapshot","version":1,"topic":"usdt:krw",
              "data":{
                "usdt_krw":[{
                  "source":"upbit","asset":"usdt-krw","rate":1485.0,
                  "timestamp":"2026-05-12T06:00:00Z"
                }],
                "usd_krw_banks":[],
                "usd_krw_futures":{
                  "source":"krx","asset":"usd-krw-futures","rate":1380.0,
                  "timestamp":"2026-05-12T06:00:00Z"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("upbit"), message.data.allEntries.map { it.source })
    }

    /**
     * The dollar index decodes as itself, with no asset to key it by.
     *
     * `dxy:spot` is the one topic whose payload is not a list of `(source, asset)` entries, so it
     * has no `allEntries` to join: folding it into that shape would invent an asset the server
     * never sent. `source` says which supplier answered, not which instrument it is.
     */
    @Test
    fun `the dollar index carries a supplier and no asset`() {
        val message = json.decodeFromString<DxyTopicMessage>(
            """
            {
              "type":"snapshot","version":1,"topic":"dxy:spot",
              "data":{"dxy":{
                "rate":104.52,"timestamp":"2026-08-21T14:30:00+09:00","source":"investing"
              }}
            }
            """.trimIndent()
        )

        val dxy = message.data.dxy
        assertEquals(104.52, dxy.rate, 0.0)
        assertEquals("investing", dxy.source)
        assertEquals(Instant.parse("2026-08-21T05:30:00Z"), dxy.timestamp)
    }

    @Test
    fun `fx and krx payloads retain their distinct shapes`() {
        val entry = TopicSourceEntry(
            source = "krx",
            asset = "usd-krw-futures",
            rate = 1380.0,
            timestamp = Instant.parse("2026-05-12T06:00:00Z")
        )
        assertEquals(listOf(entry), KrxTopicData(entry).allEntries)
        assertEquals(listOf(entry), FxTopicData(emptyList(), entry).allEntries)
    }
}
