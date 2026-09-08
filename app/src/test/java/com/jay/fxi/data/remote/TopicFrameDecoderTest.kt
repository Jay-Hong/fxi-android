package com.jay.fxi.data.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicFrameDecoderTest {
    private val decoder = TopicFrameDecoder(Json { ignoreUnknownKeys = true })

    @Test
    fun `legacy rates remain outside the topic decoder`() {
        assertEquals(
            DecodedTopicFrame.NotTopic,
            decoder.decode("""{"type":"rates","data":{},"graph_buckets":{}}""")
        )
    }

    @Test
    fun `subscription ack is decoded through the correlated object schema`() {
        val frame = decoder.decode(
            """
            {
              "type":"subscription_ack","request_id":"r1","operation":"subscribe",
              "accepted_topics":[],"rejected_topics":[],"removed_topics":[],
              "active_subscriptions":[]
            }
            """.trimIndent()
        )

        assertTrue(frame is DecodedTopicFrame.Acknowledgement)
        assertEquals("r1", (frame as DecodedTopicFrame.Acknowledgement).value.requestId)
    }

    @Test
    fun `snapshot routes to the topic-specific payload`() {
        val frame = decoder.decode(
            """
            {
              "type":"snapshot","version":1,"topic":"krx:usd-krw-futures",
              "data":{"usd_krw_futures":null}
            }
            """.trimIndent()
        )

        assertTrue(frame is DecodedTopicFrame.Krx)
        assertEquals("krx:usd-krw-futures", (frame as DecodedTopicFrame.Krx).value.topic)
    }

    @Test
    fun `future topic is explicit unsupported instead of legacy`() {
        assertEquals(
            DecodedTopicFrame.Unsupported("snapshot", "future:asset"),
            decoder.decode(
                """{"type":"snapshot","version":1,"topic":"future:asset","data":{}}"""
            )
        )
    }

    /** The dollar index gets a branch of its own — it is not a `(source, asset)` topic. */
    @Test
    fun `the dollar index snapshot routes to its own frame`() {
        val frame = decoder.decode(
            """
            {
              "type":"snapshot","version":1,"topic":"dxy:spot",
              "data":{"dxy":{
                "rate":104.52,"timestamp":"2026-08-21T14:30:00+09:00","source":"investing"
              }}
            }
            """.trimIndent()
        )

        assertTrue(frame is DecodedTopicFrame.Dxy)
        assertEquals(104.52, (frame as DecodedTopicFrame.Dxy).value.data.dxy.rate, 0.0)
    }

    /**
     * An `update` frame is ignored rather than decoded (D9).
     *
     * The server is snapshot-only. `update` used to share the snapshot branch, which meant a frame
     * nobody sends had a live parsing path — and, for a known topic, would have reached the domain
     * as though it were a snapshot. It now lands in the unsupported case, which is where the
     * transport drops it.
     */
    @Test
    fun `an update frame is unsupported rather than decoded`() {
        assertEquals(
            DecodedTopicFrame.Unsupported("update", "usdt:krw"),
            decoder.decode(
                """{"type":"update","version":1,"topic":"usdt:krw","data":{"usdt_krw":[]}}"""
            )
        )
    }

    /** …and a dollar-index snapshot with no index in it is malformed, not empty. */
    @Test
    fun `a dollar index snapshot without the index fails closed`() {
        assertThrows(SerializationException::class.java) {
            decoder.decode("""{"type":"snapshot","version":1,"topic":"dxy:spot","data":{}}""")
        }
    }

    @Test
    fun `known topic with malformed payload fails closed`() {
        assertThrows(SerializationException::class.java) {
            decoder.decode("""{"type":"snapshot","version":1,"topic":"usdt:krw","data":{}}""")
        }
    }
}
