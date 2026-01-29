package com.jay.fxi.domain.model

import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/rates 응답
 * Domain 레이어에서 직접 사용하는 환율 조회 결과
 */
@Serializable
data class RatesResult(
    val rates: List<ExchangeRate>,
    val metadata: RatesMetadata
)

/**
 * 환율 메타데이터
 */
@Serializable
data class RatesMetadata(
    @SerialName("updated_at")
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
    val currencies: List<String>,
    val banks: List<String>,
    @SerialName("total_count")
    val totalCount: Int
)
