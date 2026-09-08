package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicWholeRequestFailure
import com.jay.fxi.domain.model.RateSanity
import com.jay.fxi.domain.model.TopicDollarIndex
import com.jay.fxi.domain.model.TopicQuote
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

    /**
     * The boundary decides `rate_changed_at ?? timestamp`, once.
     *
     * `timestamp` for a Redis-served exchange entry can be a five-second `seen_at` bucket while
     * `rate_changed_at` is the moment the price actually moved. Both reach this line and only one
     * leaves it, so nothing downstream has to remember which the server meant — and nothing can
     * order two quotes by the coarser of the two by accident.
     */
    @Test
    fun `a quote leaves the boundary with one time`() {
        val bucketed = TopicSourceEntry(
            source = "upbit",
            asset = "usdt-krw",
            rate = 1485.0,
            timestamp = Instant.parse("2026-05-12T06:00:00Z"),
            rateChangedAt = Instant.parse("2026-05-12T06:00:03.123456Z")
        )
        assertEquals(
            TopicQuote("upbit", "usdt-krw", 1485.0, Instant.parse("2026-05-12T06:00:03.123456Z")),
            bucketed.toQuote()
        )

        // A bank entry carries no `rate_changed_at`; its `timestamp` is already precise.
        val precise = TopicSourceEntry(
            source = "kb",
            asset = "usd-krw",
            rate = 1400.0,
            timestamp = Instant.parse("2026-05-12T06:00:00Z")
        )
        assertEquals(Instant.parse("2026-05-12T06:00:00Z"), precise.toQuote()!!.at)
    }

    /**
     * A number that cannot be a price does not become one.
     *
     * I5 (`ANDROID_V2_PLAN.md:129`) puts value sanity at the domain conversion, because strict wire
     * decoding has no reason to object: `-1` is a valid JSON number and a valid `Double`. Past this
     * line it would be a quote, and the strictly-newer merge would let it over a real one the
     * moment its clock was newer. Rejected one entry at a time — a topic snapshot is a list of
     * independent quotes, and one bad source is not a reason to drop the others. Found by review.
     */
    @Test
    fun `an implausible rate does not become a quote`() {
        fun entry(rate: Double) = TopicSourceEntry(
            source = "kb",
            asset = "usd-krw",
            rate = rate,
            timestamp = Instant.parse("2026-05-12T06:00:00Z")
        )

        listOf(-1.0, 0.0, RateSanity.UPPER_BOUND, Double.NaN, Double.POSITIVE_INFINITY)
            .forEach { assertNull("$it 가 통과했다", entry(it).toQuote()) }

        // The bound is exclusive, so the number just under it is still a price.
        assertEquals(
            RateSanity.UPPER_BOUND - 1,
            requireNotNull(entry(RateSanity.UPPER_BOUND - 1).toQuote()).rate,
            0.0
        )
    }

    /** The index answers to the same rule; an index of zero is not a reading. */
    @Test
    fun `an implausible index does not become a reading`() {
        fun entry(rate: Double) =
            DxySpotEntry(rate, Instant.parse("2026-08-21T05:30:00Z"), "investing")

        listOf(-1.0, 0.0, Double.NaN).forEach { assertNull("$it 가 통과했다", entry(it).toDollarIndex()) }
        assertEquals(104.52, requireNotNull(entry(104.52).toDollarIndex()).rate, 0.0)
    }

    /** The index keeps its supplier, which is not an asset. */
    @Test
    fun `the dollar index maps to its own slot`() {
        assertEquals(
            TopicDollarIndex(104.52, Instant.parse("2026-08-21T05:30:00Z"), "investing"),
            DxySpotEntry(
                rate = 104.52,
                timestamp = Instant.parse("2026-08-21T05:30:00Z"),
                source = "investing"
            ).toDollarIndex()
        )
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
