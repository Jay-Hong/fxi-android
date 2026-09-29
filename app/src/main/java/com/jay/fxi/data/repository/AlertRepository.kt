package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiException
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.data.remote.dto.ComparisonAlertRequest
import com.jay.fxi.data.remote.dto.ComparisonAlertUpdateRequest
import com.jay.fxi.data.remote.dto.SourceAlertHistoryItem
import com.jay.fxi.data.remote.dto.SourceAlertSettingRequest
import com.jay.fxi.data.remote.dto.SourceAlertSettingUpdateRequest
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.SourceAlertSetting
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class AlertRepository @Inject constructor(
    private val apiService: AuthenticatedApiClient
) {
    fun captureOwnerOrNull(): AuthIdentityFence? = try {
        apiService.captureIdentityFence()
    } catch (_: AuthUnavailableException) {
        null
    } catch (_: IOException) {
        null
    }

    suspend fun getSettings(owner: AuthIdentityFence): AuthBoundResult<List<AlertSetting>> =
        safeApiCall(owner) { snapshot ->
            apiService.getNotificationSettings(snapshot)
                .requireBody("GET /api/notification-settings")
                .settings
        }

    /** Source (tether exchange / KRX) alerts; [asset] null is every asset. */
    suspend fun getSourceSettings(owner: AuthIdentityFence, asset: String? = null): AuthBoundResult<List<SourceAlertSetting>> =
        safeApiCall(owner) { snapshot ->
            apiService.getSourceNotificationSettings(snapshot, asset)
                .requireBody("GET /api/source-notification-settings")
                .settings
        }

    suspend fun createSourceSetting(owner: AuthIdentityFence, request: SourceAlertSettingRequest): AuthBoundResult<SourceAlertSetting> =
        safeApiCall(owner) { snapshot ->
            apiService.createSourceNotificationSetting(snapshot, request)
                .requireBody("POST /api/source-notification-settings")
        }

    /** Partial update: only the request's non-null fields change. */
    suspend fun updateSourceSetting(owner: AuthIdentityFence, id: Int, request: SourceAlertSettingUpdateRequest): AuthBoundResult<SourceAlertSetting> =
        safeApiCall(owner) { snapshot ->
            apiService.updateSourceNotificationSetting(snapshot, id, request)
                .requireBody("PUT /api/source-notification-settings/{id}")
        }

    /** Idempotent on the server: an already deleted setting also succeeds. */
    suspend fun deleteSourceSetting(owner: AuthIdentityFence, id: Int): AuthBoundResult<Unit> =
        safeApiCall(owner) { snapshot ->
            val response = apiService.deleteSourceNotificationSetting(snapshot, id)
            response.failure?.let { throw AuthenticatedApiException(it) }
        }

    /** Delivered source alerts, newest first; [asset] null is every asset, [limit] 1..200 (server caps). */
    suspend fun getSourceHistory(
        owner: AuthIdentityFence,
        asset: String? = null,
        limit: Int = 100
    ): AuthBoundResult<List<SourceAlertHistoryItem>> = safeApiCall(owner) { snapshot ->
        apiService.getSourceNotificationLogs(snapshot, asset, limit)
            .requireBody("GET /api/source-notification-logs")
            .logs
    }

    /** Comparison / kimchi-premium alerts; [tab] null is every tab. */
    suspend fun getComparisonAlerts(owner: AuthIdentityFence, tab: String? = null): AuthBoundResult<List<ComparisonAlertSetting>> =
        safeApiCall(owner) { snapshot ->
            apiService.getComparisonAlerts(snapshot, tab)
                .requireBody("GET /api/comparison-alerts")
                .alerts
        }

    suspend fun createComparisonAlert(owner: AuthIdentityFence, request: ComparisonAlertRequest): AuthBoundResult<ComparisonAlertSetting> =
        safeApiCall(owner) { snapshot ->
            apiService.createComparisonAlert(snapshot, request)
                .requireBody("POST /api/comparison-alerts")
        }

    /** Partial update of enabled / repeat / threshold / operator. */
    suspend fun updateComparisonAlert(owner: AuthIdentityFence, id: Int, request: ComparisonAlertUpdateRequest): AuthBoundResult<ComparisonAlertSetting> =
        safeApiCall(owner) { snapshot ->
            apiService.updateComparisonAlert(snapshot, id, request)
                .requireBody("PUT /api/comparison-alerts/{id}")
        }

    /** Idempotent on the server: an already deleted alert also succeeds. */
    suspend fun deleteComparisonAlert(owner: AuthIdentityFence, id: Int): AuthBoundResult<Unit> =
        safeApiCall(owner) { snapshot ->
            val response = apiService.deleteComparisonAlert(snapshot, id)
            response.failure?.let { throw AuthenticatedApiException(it) }
        }

    /** Delivered bank alerts, newest first; [currency] null is every currency, [limit] 1..200 (server caps). */
    suspend fun getHistory(
        owner: AuthIdentityFence,
        currency: String? = null,
        limit: Int = 100
    ): AuthBoundResult<List<AlertHistoryItem>> = safeApiCall(owner) { snapshot ->
        apiService.getNotificationLogs(snapshot, currency, limit)
            .requireBody("GET /api/notification-logs")
            .logs
    }

    suspend fun createSetting(
        owner: AuthIdentityFence,
        request: AlertSettingRequest
    ): AuthBoundResult<AlertSetting> = safeApiCall(owner) { snapshot ->
            apiService.createNotificationSetting(snapshot, request)
                .requireBody("POST /api/notification-settings")
        }

    suspend fun updateSetting(
        owner: AuthIdentityFence,
        id: Int,
        request: AlertSettingRequest
    ): AuthBoundResult<AlertSetting> = safeApiCall(owner) { snapshot ->
        apiService.updateNotificationSetting(snapshot, id, request)
            .requireBody("PUT /api/notification-settings/{id}")
    }

    /**
     * 부분 업데이트 (변경된 필드만 전송)
     */
    suspend fun updateSettingPartial(
        owner: AuthIdentityFence,
        id: Int,
        request: AlertSettingUpdateRequest
    ): AuthBoundResult<AlertSetting> = safeApiCall(owner) { snapshot ->
        apiService.updateNotificationSettingPartial(snapshot, id, request)
            .requireBody("PUT /api/notification-settings/{id}")
    }

    suspend fun deleteSetting(
        owner: AuthIdentityFence,
        id: Int
    ): AuthBoundResult<Unit> = safeApiCall(owner) { snapshot ->
        val response = apiService.deleteNotificationSetting(snapshot, id)
        response.failure?.let { throw AuthenticatedApiException(it) }
    }

    private suspend fun <T> safeApiCall(
        owner: AuthIdentityFence,
        block: suspend (AuthSnapshot) -> T
    ): AuthBoundResult<T> {
        val snapshot = try {
            apiService.captureSnapshot(owner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: AuthUnavailableException) {
            return AuthBoundResult(
                requireCurrent = { apiService.requireCurrent(owner) },
                result = Result.failure(AlertRepositoryException("인증 오류"))
            )
        }

        val result = try {
            Result.success(block(snapshot))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: AuthenticatedApiException) {
            Result.failure(mapAlertHttpFailure(e.failure))
        } catch (e: AuthenticatedBodyDecodingException) {
            Result.failure(
                AlertRepositoryException(
                    message = "서버 응답 형식 오류",
                    responseEvidence = e.response,
                    cause = e
                )
            )
        } catch (e: IOException) {
            Result.failure(AlertRepositoryException("네트워크 오류"))
        } catch (e: Exception) {
            Result.failure(AlertRepositoryException("서버 오류"))
        }
        return AuthBoundResult(
            requireCurrent = { apiService.requireCurrent(owner) },
            result = result
        )
    }

}

/** A repository result that validates its auth generation at the UI application boundary. */
class AuthBoundResult<T> internal constructor(
    private val requireCurrent: () -> Unit,
    private val result: Result<T>
) {
    fun <R> fold(
        onSuccess: (value: T) -> R,
        onFailure: (exception: Throwable) -> R
    ): R {
        requireCurrent()
        return result.fold(onSuccess, onFailure)
    }

    fun onFailure(action: (exception: Throwable) -> Unit): AuthBoundResult<T> {
        requireCurrent()
        result.onFailure(action)
        return this
    }
}

internal fun mapAlertHttpFailure(failure: AuthenticatedHttpFailure): AlertRepositoryException =
    when (failure.kind) {
        AuthenticatedFailureKind.KNOWN_NOT_FOUND ->
            AlertNotFoundException("다른 기기에서 삭제됨", failure)
        AuthenticatedFailureKind.UNKNOWN_NOT_FOUND ->
            AlertRepositoryException("알 수 없는 404 응답", failure)
        AuthenticatedFailureKind.AUTHENTICATION,
        AuthenticatedFailureKind.KNOWN_AUTHORIZATION ->
            AlertRepositoryException("인증 오류", failure)
        AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION ->
            AlertRepositoryException("알 수 없는 권한 응답", failure)
        AuthenticatedFailureKind.OTHER_HTTP -> when (failure.statusCode) {
            429 -> AlertRepositoryException("요청이 너무 많습니다", failure)
            503 -> AlertRepositoryException("구독 확인 중", failure)
            else -> AlertRepositoryException("서버 오류 (${failure.statusCode})", failure)
        }
    }

open class AlertRepositoryException(
    message: String,
    val httpFailure: AuthenticatedHttpFailure? = null,
    val responseEvidence: AuthenticatedHttpResponse<*>? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/** 404: 다른 기기에서 이미 삭제된 경우 */
class AlertNotFoundException(
    message: String,
    httpFailure: AuthenticatedHttpFailure
) : AlertRepositoryException(message, httpFailure)
