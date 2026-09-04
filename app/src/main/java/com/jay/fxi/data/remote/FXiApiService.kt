package com.jay.fxi.data.remote

import com.jay.fxi.data.remote.dto.GraphResponse
import com.jay.fxi.domain.model.NewsResponse
import com.jay.fxi.domain.model.RatesResult
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * FXi REST API 서비스
 */
interface FXiApiService {

    @GET("api/rates")
    suspend fun getRates(): RatesResult

    @GET("api/graph/{currency}")
    suspend fun getGraph(
        @Path("currency") currency: String,
        @Query("range") range: String? = null
    ): GraphResponse

    /**
     * 뉴스 조회 (인증 불필요, 비구독자 서버 요청 차단 정책의 예외)
     */
    @GET("api/news")
    suspend fun getNews(
        @Query("limit") limit: Int = 100,
        @Query("hours") hours: Double = 24.0
    ): NewsResponse

}
