package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.data.remote.dto.DeviceRequest
import com.jay.fxi.data.remote.dto.NotificationSettingsResponse
import com.jay.fxi.domain.model.AlertSetting
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/** Protected REST surface. Every method requires a captured [AuthRequestTag]. */
internal interface AuthenticatedApiService {
    @POST("api/register-device")
    suspend fun registerDevice(
        @Tag auth: AuthRequestTag,
        @Body request: DeviceRequest
    ): Response<ResponseBody>

    @DELETE("api/register-device")
    suspend fun unregisterDevice(
        @Tag auth: AuthRequestTag,
        @Query("device_token") deviceToken: String
    ): Response<ResponseBody>

    @GET("api/notification-settings")
    suspend fun getNotificationSettings(
        @Tag auth: AuthRequestTag
    ): Response<ResponseBody>

    @POST("api/notification-settings")
    suspend fun createNotificationSetting(
        @Tag auth: AuthRequestTag,
        @Body request: AlertSettingRequest
    ): Response<ResponseBody>

    @PUT("api/notification-settings/{id}")
    suspend fun updateNotificationSetting(
        @Tag auth: AuthRequestTag,
        @Path("id") id: Int,
        @Body request: AlertSettingRequest
    ): Response<ResponseBody>

    @PUT("api/notification-settings/{id}")
    suspend fun updateNotificationSettingPartial(
        @Tag auth: AuthRequestTag,
        @Path("id") id: Int,
        @Body request: AlertSettingUpdateRequest
    ): Response<ResponseBody>

    @DELETE("api/notification-settings/{id}")
    suspend fun deleteNotificationSetting(
        @Tag auth: AuthRequestTag,
        @Path("id") id: Int
    ): Response<ResponseBody>

    @DELETE("api/user/me")
    suspend fun deleteUser(
        @Tag auth: AuthRequestTag
    ): Response<ResponseBody>
}

/** App-facing protected API; callers cannot omit or replace the captured credential. */
@Singleton
class AuthenticatedApiClient internal constructor(
    private val service: AuthenticatedApiService,
    private val transport: AuthenticatedTransport,
    private val wireJson: Json
) {
    /** Captures the exact owner credential for a multi-step destructive flow. */
    suspend fun captureSnapshot(): AuthSnapshot = transport.captureSnapshot()

    suspend fun captureSnapshot(owner: AuthIdentityFence): AuthSnapshot =
        transport.captureSnapshot(owner)

    fun captureIdentityFence(): AuthIdentityFence = transport.captureIdentityFence()

    /** Re-validates a captured owner at an application boundary after another await. */
    fun requireCurrent(owner: AuthSnapshot) = transport.requireCurrent(owner)

    fun requireCurrent(owner: AuthIdentityFence) = transport.requireCurrent(owner)

    suspend fun registerDevice(
        owner: AuthSnapshot,
        request: DeviceRequest
    ): AuthenticatedHttpResponse<Unit> =
        transport.executeMutation(owner) { service.registerDevice(it, request) }
            .preserve(AuthenticatedEndpoint.REGISTER_DEVICE)
            .asUnit()

    suspend fun unregisterDevice(
        owner: AuthSnapshot,
        deviceToken: String
    ): AuthenticatedHttpResponse<Unit> =
        transport.executeMutation(owner) { service.unregisterDevice(it, deviceToken) }
            .preserve(AuthenticatedEndpoint.UNREGISTER_DEVICE)
            .asUnit()

    suspend fun getNotificationSettings(
        owner: AuthSnapshot
    ): AuthenticatedHttpResponse<NotificationSettingsResponse> =
        transport.executeRead(owner) { service.getNotificationSettings(it) }
            .preserve(AuthenticatedEndpoint.NOTIFICATION_SETTINGS)
            .decodeSuccess(wireJson)

    suspend fun createNotificationSetting(
        owner: AuthSnapshot,
        request: AlertSettingRequest
    ): AuthenticatedHttpResponse<AlertSetting> = transport.executeMutation(owner) {
        service.createNotificationSetting(it, request)
    }
            .preserve(AuthenticatedEndpoint.NOTIFICATION_SETTINGS)
            .decodeSuccess(wireJson)

    suspend fun updateNotificationSetting(
        owner: AuthSnapshot,
        id: Int,
        request: AlertSettingRequest
    ): AuthenticatedHttpResponse<AlertSetting> = transport.executeMutation(owner) {
        service.updateNotificationSetting(it, id, request)
    }
            .preserve(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING)
            .decodeSuccess(wireJson)

    suspend fun updateNotificationSettingPartial(
        owner: AuthSnapshot,
        id: Int,
        request: AlertSettingUpdateRequest
    ): AuthenticatedHttpResponse<AlertSetting> = transport.executeMutation(owner) {
        service.updateNotificationSettingPartial(it, id, request)
    }.preserve(AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING)
        .decodeSuccess(wireJson)

    suspend fun deleteNotificationSetting(
        owner: AuthSnapshot,
        id: Int
    ): AuthenticatedHttpResponse<Unit> =
        transport.executeMutation(owner) { service.deleteNotificationSetting(it, id) }
            .preserve(AuthenticatedEndpoint.DELETE_NOTIFICATION_SETTING)
            .asUnit()

    suspend fun deleteUser(owner: AuthSnapshot): AuthenticatedHttpResponse<Unit> =
        transport.executeMutation(owner) { service.deleteUser(it) }
            .preserve(AuthenticatedEndpoint.DELETE_USER)
            .asUnit()
}
