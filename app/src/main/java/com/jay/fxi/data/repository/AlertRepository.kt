package com.jay.fxi.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.domain.model.AlertSetting
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertRepository @Inject constructor(
    private val apiService: FXiApiService,
    private val auth: FirebaseAuth
) {
    suspend fun getSettings(): Result<List<AlertSetting>> = safeApiCall {
        apiService.getNotificationSettings().settings
    }

    suspend fun createSetting(request: AlertSettingRequest): Result<AlertSetting> = safeApiCall {
        apiService.createNotificationSetting(request)
    }

    suspend fun updateSetting(id: Int, request: AlertSettingRequest): Result<AlertSetting> = safeApiCall {
        apiService.updateNotificationSetting(id, request)
    }

    /**
     * 부분 업데이트 (변경된 필드만 전송)
     */
    suspend fun updateSettingPartial(id: Int, request: AlertSettingUpdateRequest): Result<AlertSetting> = safeApiCall {
        apiService.updateNotificationSettingPartial(id, request)
    }

    suspend fun deleteSetting(id: Int): Result<Unit> = safeApiCall {
        val response = apiService.deleteNotificationSetting(id)
        if (!response.isSuccessful) {
            throw HttpException(response)
        }
    }

    private suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> {
        if (auth.currentUser == null) {
            return Result.failure(AlertRepositoryException("인증 오류"))
        }
        return try {
            Result.success(block())
        } catch (e: HttpException) {
            val message = when (e.code()) {
                401, 403 -> "인증 오류"
                503 -> "구독 확인 중"
                else -> "서버 오류 (${e.code()})"
            }
            Result.failure(AlertRepositoryException(message))
        } catch (e: IOException) {
            Result.failure(AlertRepositoryException("네트워크 오류"))
        } catch (e: Exception) {
            Result.failure(AlertRepositoryException("서버 오류"))
        }
    }
}

class AlertRepositoryException(message: String) : Exception(message)
