package com.jay.fxi.data.repository

import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.domain.model.RatesResult
import com.jay.fxi.domain.model.GraphBucket
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

    override suspend fun getGraph(currency: SupportedCurrency): Result<Map<String, List<GraphBucket>>> {
        return getGraph(currency.code)
    }

    override suspend fun getGraph(currency: String): Result<Map<String, List<GraphBucket>>> {
        return runCatching {
            val response = apiService.getGraph(currency)
            response.toGraphBuckets()
        }
    }
}
