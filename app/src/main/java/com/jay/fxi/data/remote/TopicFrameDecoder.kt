package com.jay.fxi.data.remote

import com.jay.fxi.data.remote.dto.DxyTopicMessage
import com.jay.fxi.data.remote.dto.FxTopicMessage
import com.jay.fxi.data.remote.dto.KrxTopicMessage
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionError
import com.jay.fxi.data.remote.dto.TetherTopicMessage
import com.jay.fxi.data.remote.dto.TopicEnvelope
import com.jay.fxi.data.remote.dto.TopicMessageType
import com.jay.fxi.data.remote.dto.WebSocketMessageType
import kotlinx.serialization.json.Json

sealed interface DecodedTopicFrame {
    data class Acknowledgement(val value: SubscriptionAck) : DecodedTopicFrame
    data class RequestFailure(val value: SubscriptionError) : DecodedTopicFrame
    data class Tether(val value: TetherTopicMessage) : DecodedTopicFrame
    data class Krx(val value: KrxTopicMessage) : DecodedTopicFrame
    data class Fx(val value: FxTopicMessage) : DecodedTopicFrame
    data class Dxy(val value: DxyTopicMessage) : DecodedTopicFrame
    data class Unsupported(val type: String, val topic: String?) : DecodedTopicFrame
    data object NotTopic : DecodedTopicFrame
}

/** Envelope-first decoder shared by the eventual OkHttp transport and unit tests. */
class TopicFrameDecoder(private val json: Json) {
    fun decode(text: String): DecodedTopicFrame {
        val envelope = json.decodeFromString<TopicEnvelope>(text)
        return when (envelope.type) {
            TopicMessageType.SUBSCRIPTION_ACK -> DecodedTopicFrame.Acknowledgement(
                json.decodeFromString<SubscriptionAck>(text)
            )
            TopicMessageType.SUBSCRIPTION_ERROR -> DecodedTopicFrame.RequestFailure(
                json.decodeFromString<SubscriptionError>(text)
            )
            // Snapshot only. An `update` frame has no branch of its own and lands in the
            // unsupported case below, which is D9's "remove from support and ignore".
            TopicMessageType.SNAPSHOT -> decodeDataFrame(envelope, text)
            WebSocketMessageType.RATES,
            WebSocketMessageType.PONG -> DecodedTopicFrame.NotTopic
            else -> DecodedTopicFrame.Unsupported(envelope.type, envelope.topic)
        }
    }

    private fun decodeDataFrame(envelope: TopicEnvelope, text: String): DecodedTopicFrame =
        when (val topic = envelope.topic) {
            "usdt:krw" -> DecodedTopicFrame.Tether(json.decodeFromString<TetherTopicMessage>(text))
            "krx:usd-krw-futures" -> DecodedTopicFrame.Krx(json.decodeFromString<KrxTopicMessage>(text))
            "dxy:spot" -> DecodedTopicFrame.Dxy(json.decodeFromString<DxyTopicMessage>(text))
            null -> DecodedTopicFrame.Unsupported(envelope.type, null)
            else -> if (topic.startsWith("fx:")) {
                DecodedTopicFrame.Fx(json.decodeFromString<FxTopicMessage>(text))
            } else {
                DecodedTopicFrame.Unsupported(envelope.type, topic)
            }
        }
}
