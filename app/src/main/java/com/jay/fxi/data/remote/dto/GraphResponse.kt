package com.jay.fxi.data.remote.dto

import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphDataResult
import com.jay.fxi.domain.model.GraphPeriod
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/graph/{currency} 응답
 * REST API는 배열 형식 [ts, max, min, close]로 반환
 */
@Serializable
data class GraphResponse(
    val pair: String,
    val period: String = GraphPeriod.ONE_DAY.code,
    @SerialName("bucket_size")
    val bucketSize: String = "10m",
    val sources: Map<String, List<List<Double>>>,  // source -> [[ts, max, min, close], ...]
    @SerialName("as_of")
    val asOfRaw: String? = null
) {
    /**
     * REST 배열 형식을 GraphBucket으로 변환
     */
    fun toGraphBuckets(): Map<String, List<GraphBucket>> {
        return sources.mapValues { (_, arrays) ->
            arrays.mapNotNull { arr -> GraphBucket.fromArray(arr) }
        }
    }

    fun toGraphDataResult(): GraphDataResult {
        return GraphDataResult(
            period = GraphPeriod.fromCode(period) ?: GraphPeriod.ONE_DAY,
            bucketSize = bucketSize,
            sources = toGraphBuckets(),
            asOf = asOfRaw?.let(Instant::parse)
        )
    }
}
