package com.jay.fxi.domain.repository

import com.jay.fxi.domain.model.RatesResult
import com.jay.fxi.domain.model.GraphDataResult
import com.jay.fxi.domain.model.GraphPeriod
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
     * 통화별 그래프 데이터 조회
     * @param currency 통화쌍
     * @param period 그래프 기간
     * @return 그래프 응답 도메인 모델
     */
    suspend fun getGraph(
        currency: SupportedCurrency,
        period: GraphPeriod = GraphPeriod.ONE_DAY
    ): Result<GraphDataResult>

    /**
     * 통화별 그래프 데이터 조회 (문자열 버전)
     * @param currency 통화쌍 코드 (예: "usd-krw")
     * @param period 그래프 기간
     * @return 그래프 응답 도메인 모델
     */
    suspend fun getGraph(
        currency: String,
        period: GraphPeriod = GraphPeriod.ONE_DAY
    ): Result<GraphDataResult>
}
