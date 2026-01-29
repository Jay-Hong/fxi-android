package com.jay.fxi.domain.repository

import com.jay.fxi.domain.model.RatesResult
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.SupportedCurrency

/**
 * 환율 데이터 Repository 인터페이스
 * Domain 레이어에 위치하여 Data 레이어에 대한 추상화 제공
 */
interface ExchangeRateRepository {

    /**
     * 전체 환율 조회
     * @return 환율 응답 (rates + metadata)
     */
    suspend fun getRates(): Result<RatesResult>

    /**
     * 통화별 24시간 그래프 데이터 조회
     * @param currency 통화쌍
     * @return 소스별 그래프 버킷 맵 (source -> buckets)
     */
    suspend fun getGraph(currency: SupportedCurrency): Result<Map<String, List<GraphBucket>>>

    /**
     * 통화별 24시간 그래프 데이터 조회 (문자열 버전)
     * @param currency 통화쌍 코드 (예: "usd-krw")
     * @return 소스별 그래프 버킷 맵 (source -> buckets)
     */
    suspend fun getGraph(currency: String): Result<Map<String, List<GraphBucket>>>
}
