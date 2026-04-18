package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.RatesMetadata
import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WebSocket 메시지 타입
 */
object WebSocketMessageType {
    const val RATES = "rates"
    const val PONG = "pong"
}

/**
 * WebSocket Ping 메시지 (Plain text)
 */
object WebSocketPing {
    const val MESSAGE = "ping"
}

/**
 * WebSocket 수신 메시지 (환율 데이터)
 *
 * 서버에서 10초마다 브로드캐스트:
 * {
 *   "type": "rates",
 *   "data": { "rates": [...], "metadata": {...} },
 *   "graph_buckets": { "usd-krw": { "investing": {...}, ... } }
 * }
 */
@Serializable
data class WebSocketRatesMessage(
    val type: String,
    val data: RatesData? = null,
    @SerialName("graph_buckets")
    val graphBuckets: Map<String, Map<String, WebSocketGraphBucket>>? = null
)

@Serializable
data class RatesData(
    val rates: List<ExchangeRate>,
    val metadata: RatesMetadata,
    /** WebSocket 전용, REST 응답에는 없음. 구 서버 응답에도 부재 가능해 optional. */
    val indices: IndicesPayload? = null
)

/**
 * WebSocket payload의 indices 섹션 (현재는 dxy만).
 */
@Serializable
data class IndicesPayload(
    val dxy: DxyLiveTick? = null
)

/**
 * DXY realtime tick (investing 우선, yahoo 폴백).
 * 10초 해상도 live 값. 기존 graph_buckets.dxy(분당 갱신)와 경로 분리.
 */
@Serializable
data class DxyLiveTick(
    val rate: Double,
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    val source: String   // "investing" | "yahoo"
)

/**
 * WebSocket 그래프 버킷 (객체 형식)
 * REST는 배열 [ts, max, min, close]이지만, WebSocket은 객체
 */
@Serializable
data class WebSocketGraphBucket(
    @SerialName("bucket_ts")
    val bucketTs: Int,
    val max: Double,
    val min: Double,
    val close: Double
) {
    fun toGraphBucket(): GraphBucket = GraphBucket(
        bucketTs = bucketTs,
        max = max,
        min = min,
        close = close
    )
}

/**
 * WebSocket 그래프 버킷 타입 별칭
 * Map<currency, Map<source, WebSocketGraphBucket>>
 */
typealias WebSocketGraphBuckets = Map<String, Map<String, WebSocketGraphBucket>>
