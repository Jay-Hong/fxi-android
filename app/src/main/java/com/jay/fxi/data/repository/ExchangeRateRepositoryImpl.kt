package com.jay.fxi.data.repository

import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.domain.model.GraphDataResult
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RatesResult
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.domain.repository.ExchangeRateRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 환율 데이터 Repository 구현체
 */
@Singleton
class ExchangeRateRepositoryImpl @Inject constructor(
    private val apiService: FXiApiService
) : ExchangeRateRepository {

    override suspend fun getRates(): Result<RatesResult> {
        return runCatching {
            apiService.getRates()
        }
    }

    override suspend fun getGraph(
        currency: SupportedCurrency,
        period: GraphPeriod
    ): Result<GraphDataResult> {
        return getGraph(currency.code, period)
    }

    override suspend fun getGraph(
        currency: String,
        period: GraphPeriod
    ): Result<GraphDataResult> {
        return runCatching {
            val response = apiService.getGraph(
                currency = currency,
                range = period.code.takeUnless { period == GraphPeriod.ONE_DAY }
            )
            response.toGraphDataResult()
        }
    }
}
