package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.dto.AlertHistoryResponse
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.data.remote.dto.DeviceRequest
import com.jay.fxi.data.remote.dto.EntitlementsResponse
import com.jay.fxi.data.remote.dto.FreeSnapshotResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.data.remote.dto.NotificationSettingsResponse
import com.jay.fxi.data.remote.dto.SourceAlertHistoryResponse
import com.jay.fxi.data.remote.dto.SourceAlertSettingRequest
import com.jay.fxi.data.remote.dto.SourceAlertSettingUpdateRequest
import com.jay.fxi.data.remote.dto.SourceAlertSettingsResponse
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.SourceAlertSetting
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
    @GET("api/v2/free/snapshot")
    suspend fun getFreeSnapshot(
        @Tag auth: AuthRequestTag,
        @Query("tab") tab: String,
        @Query("period") period: String
    ): Response<ResponseBody>

    /**
     * One topic per call: the server declares `topic` as a required scalar, answers 422 when it is
     * absent, and keeps only the last value if it is repeated. A list here would silently ask for
     * one topic and look like it asked for several.
     */
    @GET("api/v2/topics/snapshot")
    suspend fun getTopicSnapshot(
        @Tag auth: AuthRequestTag,
        @Query("topic") topic: String
    ): Response<ResponseBody>

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

    @GET("api/source-notification-settings")
    suspend fun getSourceNotificationSettings(
        @Tag auth: AuthRequestTag,
        @Query("asset") asset: String?
    ): Response<ResponseBody>

    @POST("api/source-notification-settings")
    suspend fun createSourceNotificationSetting(
        @Tag auth: AuthRequestTag,
        @Body request: SourceAlertSettingRequest
    ): Response<ResponseBody>

    @PUT("api/source-notification-settings/{id}")
    suspend fun updateSourceNotificationSetting(
        @Tag auth: AuthRequestTag,
        @Path("id") id: Int,
        @Body request: SourceAlertSettingUpdateRequest
    ): Response<ResponseBody>

    @DELETE("api/source-notification-settings/{id}")
    suspend fun deleteSourceNotificationSetting(
        @Tag auth: AuthRequestTag,
        @Path("id") id: Int
    ): Response<ResponseBody>

    @GET("api/source-notification-logs")
    suspend fun getSourceNotificationLogs(
        @Tag auth: AuthRequestTag,
        @Query("asset") asset: String?,
        @Query("limit") limit: Int
    ): Response<ResponseBody>

    @GET("api/v2/graph/catalog")
    suspend fun getGraphV2Catalog(
        @Tag auth: AuthRequestTag
    ): Response<ResponseBody>

    @GET("api/v2/graph/tab")
    suspend fun getGraphV2Tab(
        @Tag auth: AuthRequestTag,
        @Query("tab") tab: String,
        @Query("period") period: String
    ): Response<ResponseBody>

    @GET("api/notification-logs")
    suspend fun getNotificationLogs(
        @Tag auth: AuthRequestTag,
        @Query("currency") currency: String?,
        @Query("limit") limit: Int
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

    /**
     * [freshPremium] is omitted unless true so an ordinary query keeps the server's default
     * availability cache; only a recovery query pays the cache-free cost.
     */
    @GET("api/entitlements")
    suspend fun getEntitlements(
        @Tag auth: AuthRequestTag,
        @Query("fresh_premium") freshPremium: Boolean? = null
    ): Response<ResponseBody>
}

/** App-facing protected API; callers cannot omit or replace the captured credential. */
@Singleton
class AuthenticatedApiClient internal constructor(
    private val service: AuthenticatedApiService,
    private val transport: AuthenticatedTransport,
    private val wireJson: Json
) {
    suspend fun getFreeSnapshot(
        owner: AuthSnapshot,
        tab: String,
        period: String
    ): AuthenticatedHttpResponse<FreeSnapshotResponse> = transport.executeRead(owner) {
        service.getFreeSnapshot(it, tab, period)
    }.preserve(AuthenticatedEndpoint.FREE_SNAPSHOT).decodeSuccess(wireJson)

    /**
     * The bytes of one topic snapshot, undecoded.
     *
     * A read, so the transport's single 401 replay applies and one logical call can be two sends.
     * Left undecoded here because the frame's concrete type is chosen at runtime from the envelope
     * and `decodeSuccess` needs a reified one — and because the REST-side guards that follow
     * (echo check, non-answer frames) are policy, not transport.
     */
    suspend fun getTopicSnapshot(
        owner: AuthSnapshot,
        topic: String,
        /** When given, every send is refused once it answers false; see [AuthenticatedTransport.executeRead] (L-4e E2a). */
        useAdmitted: (() -> Boolean)? = null
    ): AuthenticatedHttpResponse<ByteArray> = transport.executeRead(owner, useAdmitted) {
        service.getTopicSnapshot(it, topic)
    }.preserve(AuthenticatedEndpoint.TOPIC_SNAPSHOT)

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

    suspend fun getSourceNotificationSettings(
        owner: AuthSnapshot,
        asset: String?
    ): AuthenticatedHttpResponse<SourceAlertSettingsResponse> =
        transport.executeRead(owner) { service.getSourceNotificationSettings(it, asset) }
            .preserve(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS)
            .decodeSuccess(wireJson)

    suspend fun createSourceNotificationSetting(
        owner: AuthSnapshot,
        request: SourceAlertSettingRequest
    ): AuthenticatedHttpResponse<SourceAlertSetting> =
        transport.executeMutation(owner) { service.createSourceNotificationSetting(it, request) }
            .preserve(AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS)
            .decodeSuccess(wireJson)

    suspend fun updateSourceNotificationSetting(
        owner: AuthSnapshot,
        id: Int,
        request: SourceAlertSettingUpdateRequest
    ): AuthenticatedHttpResponse<SourceAlertSetting> =
        transport.executeMutation(owner) { service.updateSourceNotificationSetting(it, id, request) }
            .preserve(AuthenticatedEndpoint.UPDATE_SOURCE_NOTIFICATION_SETTING)
            .decodeSuccess(wireJson)

    suspend fun deleteSourceNotificationSetting(
        owner: AuthSnapshot,
        id: Int
    ): AuthenticatedHttpResponse<Unit> =
        transport.executeMutation(owner) { service.deleteSourceNotificationSetting(it, id) }
            .preserve(AuthenticatedEndpoint.DELETE_SOURCE_NOTIFICATION_SETTING)
            .asUnit()

    /** GET /api/source-notification-logs (delivered source alerts). */
    suspend fun getSourceNotificationLogs(
        owner: AuthSnapshot,
        asset: String?,
        limit: Int
    ): AuthenticatedHttpResponse<SourceAlertHistoryResponse> =
        transport.executeRead(owner) { service.getSourceNotificationLogs(it, asset, limit) }
            .preserve(AuthenticatedEndpoint.SOURCE_NOTIFICATION_LOGS)
            .decodeSuccess(wireJson)

    /** GET /api/v2/graph/catalog (premium; KRX series only when the user may see them). */
    suspend fun getGraphV2Catalog(
        owner: AuthSnapshot
    ): AuthenticatedHttpResponse<GraphV2CatalogResponse> =
        transport.executeRead(owner) { service.getGraphV2Catalog(it) }
            .preserve(AuthenticatedEndpoint.GRAPH_V2_CATALOG)
            .decodeSuccess(wireJson)

    /** GET /api/v2/graph/tab?tab=&period= (premium; period "1d" / "1w" / "3m" / "1y"). */
    suspend fun getGraphV2Tab(
        owner: AuthSnapshot,
        tab: String,
        period: String
    ): AuthenticatedHttpResponse<GraphV2TabResponse> =
        transport.executeRead(owner) { service.getGraphV2Tab(it, tab, period) }
            .preserve(AuthenticatedEndpoint.GRAPH_V2_TAB)
            .decodeSuccess(wireJson)

    /** GET /api/notification-logs (delivered bank alerts). */
    suspend fun getNotificationLogs(
        owner: AuthSnapshot,
        currency: String?,
        limit: Int
    ): AuthenticatedHttpResponse<AlertHistoryResponse> =
        transport.executeRead(owner) { service.getNotificationLogs(it, currency, limit) }
            .preserve(AuthenticatedEndpoint.NOTIFICATION_LOGS)
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

    /**
     * Reads the entitlement envelope.
     *
     * A read, so the transport's GET replay applies: a 401 is retried once against a forced token
     * refresh before it reaches the caller as an authentication failure.
     */
    suspend fun getEntitlements(
        owner: AuthSnapshot,
        freshPremium: Boolean
    ): AuthenticatedHttpResponse<EntitlementsResponse> = transport.executeRead(owner) {
        service.getEntitlements(it, freshPremium.takeIf { requested -> requested })
    }
        .preserve(AuthenticatedEndpoint.ENTITLEMENTS)
        .decodeSuccess(wireJson)
}
