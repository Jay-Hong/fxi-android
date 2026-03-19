package com.jay.fxi.data.remote

import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.data.remote.dto.DeviceRequest
import com.jay.fxi.data.remote.dto.GraphResponse
import com.jay.fxi.data.remote.dto.NotificationSettingsResponse
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.RatesResult
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
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

    @POST("api/register-device")
    suspend fun registerDevice(@Body request: DeviceRequest): Response<Unit>

    @DELETE("api/register-device")
    suspend fun unregisterDevice(@Query("device_token") deviceToken: String): Response<Unit>

    @GET("api/notification-settings")
    suspend fun getNotificationSettings(): NotificationSettingsResponse

    @POST("api/notification-settings")
    suspend fun createNotificationSetting(@Body request: AlertSettingRequest): AlertSetting

    @PUT("api/notification-settings/{id}")
    suspend fun updateNotificationSetting(
        @Path("id") id: Int,
        @Body request: AlertSettingRequest
    ): AlertSetting

    /**
     * 부분 업데이트 (변경된 필드만 전송)
     */
    @PUT("api/notification-settings/{id}")
    suspend fun updateNotificationSettingPartial(
        @Path("id") id: Int,
        @Body request: AlertSettingUpdateRequest
    ): AlertSetting

    @DELETE("api/notification-settings/{id}")
    suspend fun deleteNotificationSetting(@Path("id") id: Int): Response<Unit>

    /**
     * 계정 삭제 (비구독자 서버 요청 차단 정책의 예외)
     */
    @DELETE("api/user/me")
    suspend fun deleteUser(): Response<Unit>
}
