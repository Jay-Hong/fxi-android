package com.jay.fxi.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.jay.fxi.util.InstantSerializer

/**
 * 그래프 버킷 (10분 단위)
 */
@Serializable
data class GraphBucket(
    @SerialName("bucket_ts") val bucketTs: Int,   // Unix timestamp (초)
    val max: Double,
    val min: Double,
    val close: Double
) {
    val id: Int get() = bucketTs
    val date: Instant get() = Instant.fromEpochSeconds(bucketTs.toLong())

    companion object {
        /**
         * REST 배열 [ts, max, min, close]에서 변환
         */
        fun fromArray(arr: List<Double>): GraphBucket? {
            if (arr.size != 4) return null
            return GraphBucket(
                bucketTs = arr[0].toInt(),
                max = arr[1],
                min = arr[2],
                close = arr[3]
            )
        }
    }
}

/**
 * 그래프 표시용 포인트 (UI 레이어)
 */
data class GraphPoint(
    val timestamp: Long,       // Unix timestamp (초)
    val date: Instant,         // 시간
    val source: GraphSource,
    val max: Double,
    val min: Double,
    val close: Double
)

/**
 * GraphBucket → GraphPoint 변환
 */
fun GraphBucket.toGraphPoint(source: GraphSource) = GraphPoint(
    timestamp = bucketTs.toLong(),
    date = Instant.fromEpochSeconds(bucketTs.toLong()),
    source = source,
    max = max,
    min = min,
    close = close
)

/**
 * 그래프 캐시 타입
 * Map<currency, Map<source, List<GraphBucket>>>
 */
typealias GraphCache = Map<String, Map<String, List<GraphBucket>>>
typealias MutableGraphCache = MutableMap<String, MutableMap<String, MutableList<GraphBucket>>>
typealias GraphSourceData = Map<String, List<GraphBucket>>
typealias MutableGraphSourceData = MutableMap<String, MutableList<GraphBucket>>
typealias PeriodGraphCache = Map<String, Map<String, GraphSourceData>>
typealias MutablePeriodGraphCache = MutableMap<String, MutableMap<String, GraphSourceData>>

/**
 * 장기 구간 디스크 캐시 엔트리
 */
@Serializable
data class PeriodGraphCacheEntry(
    val sources: GraphSourceData,
    @Serializable(with = InstantSerializer::class)
    val freshnessDate: Instant
)

/**
 * 그래프 REST 응답의 도메인 모델
 */
data class GraphDataResult(
    val period: GraphPeriod,
    val bucketSize: String,
    val sources: GraphSourceData,
    val asOf: Instant?
)
