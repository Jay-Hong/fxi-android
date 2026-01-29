package com.jay.fxi.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.data.remote.dto.DeviceRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: FXiApiService,
    private val auth: FirebaseAuth
) {
    private val prefs = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)

    private var savedToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    var shouldRegisterForPush: Boolean
        get() = prefs.getBoolean(KEY_SHOULD_REGISTER, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOULD_REGISTER, value).apply()

    /**
     * FCM 토큰 갱신 시 호출 (FXiMessagingService.onNewToken)
     */
    suspend fun onNewToken(token: String, isPremium: Boolean) {
        withContext(Dispatchers.IO) {
            val oldToken = savedToken
            savedToken = token

            if (auth.currentUser == null || !isPremium || !shouldRegisterForPush) return@withContext

            // 이전 토큰 해제
            if (oldToken != null && oldToken != token) {
                try {
                    val response = apiService.unregisterDevice(oldToken)
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Old token unregister failed: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Old token unregister failed: ${e.message}")
                }
            }

            // 새 토큰 등록
            try {
                val response = apiService.registerDevice(
                    DeviceRequest(deviceToken = token, platform = PLATFORM_ANDROID)
                )
                if (response.isSuccessful) {
                    Log.d(TAG, "Device registered with new token")
                } else {
                    Log.e(TAG, "Device registration failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Device registration failed: ${e.message}")
            }
        }
    }

    /**
     * 3중 가드 확인 후 서버 등록
     */
    suspend fun registerIfNeeded(isPremium: Boolean) {
        withContext(Dispatchers.IO) {
            if (auth.currentUser == null || !isPremium || !shouldRegisterForPush) return@withContext

            // 저장된 토큰이 있더라도 최신 토큰으로 갱신 시도 (토큰 회전/프로젝트 변경 복구)
            val latestToken = try {
                Tasks.await(FirebaseMessaging.getInstance().token)
            } catch (e: Exception) {
                Log.w(TAG, "FCM token refresh failed, fallback to saved token: ${e.message}")
                null
            }

            val oldToken = savedToken
            val token = when {
                !latestToken.isNullOrBlank() -> {
                    savedToken = latestToken
                    latestToken
                }
                !oldToken.isNullOrBlank() -> oldToken
                else -> {
                    Log.e(TAG, "FCM token unavailable")
                    return@withContext
                }
            }

            // 토큰이 바뀌면 이전 토큰 해제 (best-effort)
            if (!oldToken.isNullOrBlank() && oldToken != token) {
                try {
                    val response = apiService.unregisterDevice(oldToken)
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Old token unregister failed: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Old token unregister failed: ${e.message}")
                }
            }

            try {
                val response = apiService.registerDevice(
                    DeviceRequest(deviceToken = token, platform = PLATFORM_ANDROID)
                )
                if (response.isSuccessful) {
                    Log.d(TAG, "Device registered")
                } else {
                    Log.e(TAG, "Device registration failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Device registration failed: ${e.message}")
            }
        }
    }

    /**
     * 서버에서 기기 등록 해제 (로그아웃 시)
     */
    suspend fun unregisterDeviceFromServer() {
        withContext(Dispatchers.IO) {
            val token = savedToken
            if (token != null) {
                try {
                    val response = apiService.unregisterDevice(token)
                    if (response.isSuccessful) {
                        Log.d(TAG, "Device unregistered")
                    } else {
                        Log.w(TAG, "Device unregister failed: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Device unregister failed: ${e.message}")
                }
            }
            savedToken = null
            shouldRegisterForPush = false
        }
    }

    /**
     * 프리미엄 전환 시 토큰 재등록 (SubscriptionManager.onAuthCompleted에서만 호출)
     * 알림 권한이 이미 승인된 상태면 shouldRegisterForPush 복원
     */
    suspend fun rehydratePushTokenIfNeeded(isPremium: Boolean) {
        // 알림 권한이 승인된 상태면 shouldRegisterForPush 복원
        if (hasNotificationPermission()) {
            shouldRegisterForPush = true
        }
        registerIfNeeded(isPremium)
    }

    /**
     * 알림 권한 확인 (Android 13+ POST_NOTIFICATIONS)
     */
    private fun hasNotificationPermission(): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!runtimeGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

        val channelImportance = context
            .getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(FXiMessagingService.CHANNEL_RATE_ALERTS)
            ?.importance

        return channelImportance == null || channelImportance != NotificationManager.IMPORTANCE_NONE
    }

    companion object {
        private const val TAG = "PushNotification"
        private const val PLATFORM_ANDROID = "android"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_SHOULD_REGISTER = "should_register"
    }
}
