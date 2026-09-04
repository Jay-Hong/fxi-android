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
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.domain.model.AlertSetting
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
