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

    @Test
    fun `known topic with malformed payload fails closed`() {
        assertThrows(SerializationException::class.java) {
            decoder.decode("""{"type":"snapshot","version":1,"topic":"usdt:krw","data":{}}""")
        }
    }
}
