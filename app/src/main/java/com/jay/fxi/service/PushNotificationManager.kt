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
import com.google.firebase.messaging.FirebaseMessaging
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.dto.DeviceRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class PushNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: AuthenticatedApiClient,
    private val premiumAccessCoordinator: PremiumAccessCoordinator
) {
    /**
     * Whether the session we are about to register as may be registered.
     *
     * Asked next to the capture rather than handed in as a boolean. Callers used to decide for one
     * identity while this class captured another a moment later, so a grant belonging to A could
     * authorise a registration sent as B — the captured owner is the only identity the request can
     * actually go out under, so it is the only one worth asking about.
     */
    private fun mayRegister(owner: AuthIdentityFence?): Boolean =
        pushRegistrationAllowed(owner, premiumAccessCoordinator.state.value, shouldRegisterForPush)
    private val prefs = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)

    private var savedToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    var shouldRegisterForPush: Boolean
        get() = ReleaseAdmission.isOpen && prefs.getBoolean(KEY_SHOULD_REGISTER, false)
        set(value) {
            if (!ReleaseAdmission.isOpen) return
            prefs.edit().putBoolean(KEY_SHOULD_REGISTER, value).apply()
        }

    /**
     * FCM 토큰 갱신 시 호출 (FXiMessagingService.onNewToken)
     *
     * old token cleanup과 new token registration을 분리:
     * - 이전 토큰 해제: captured auth owner만 사용 (FCM이 무효화한 토큰은 서버에서 정리)
     * - 새 토큰 등록: 3중 가드 (captured auth owner + premium + shouldRegisterForPush)
     */
    suspend fun onNewToken(token: String) {
        if (!ReleaseAdmission.isOpen) return
        val owner = captureOwnerOrNull()
        withContext(Dispatchers.IO) {
            val oldToken = savedToken
            savedToken = token
            val snapshot = capturePushSnapshotOrNull(
                owner = owner,
                capture = apiService::captureSnapshot,
                onFailure = { error ->
                    Log.w(TAG, "Push auth snapshot unavailable: ${error.message}")
                }
            )

            // 이전 토큰 해제 — captured owner만 사용 (FCM 토큰 회전 = 이전 토큰 무효)
            if (snapshot != null && oldToken != null && oldToken != token) {
                try {
                    val response = apiService.unregisterDevice(snapshot, oldToken)
                    if (response.isSuccessful) {
                        Log.d(TAG, "Old token unregistered")
                    } else {
                        Log.w(TAG, "Old token unregister failed: ${response.code()}")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "Old token unregister failed: ${e.message}")
                }
            }

            // 새 토큰 등록 — 3중 가드
            if (snapshot == null || !mayRegister(owner)) return@withContext

            try {
                val response = apiService.registerDevice(
                    snapshot,
                    DeviceRequest(deviceToken = token, platform = PLATFORM_ANDROID)
                )
                if (response.isSuccessful) {
                    Log.d(TAG, "Device registered with new token")
                } else {
                    Log.e(TAG, "Device registration failed: ${response.code()}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Device registration failed: ${e.message}")
            }
        }
    }

    /**
     * 3중 가드 확인 후 서버 등록
     */
    suspend fun registerIfNeeded() {
        if (!ReleaseAdmission.isOpen) return
        val owner = captureOwnerOrNull() ?: return
        registerIfNeeded(owner)
    }

    suspend fun registerIfNeeded(owner: AuthIdentityFence) {
        if (!ReleaseAdmission.isOpen) return
        withContext(Dispatchers.IO) {
            if (!mayRegister(owner)) return@withContext

            // 저장된 토큰이 있더라도 최신 토큰으로 갱신 시도 (토큰 회전/프로젝트 변경 복구)
            val latestToken = try {
                Tasks.await(FirebaseMessaging.getInstance().token)
            } catch (cancelled: CancellationException) {
                throw cancelled
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
            val snapshot = capturePushSnapshotOrNull(
                owner = owner,
                capture = apiService::captureSnapshot,
                onFailure = { error ->
                    Log.w(TAG, "Push auth snapshot unavailable: ${error.message}")
                }
            ) ?: return@withContext

            // 토큰이 바뀌면 이전 토큰 해제 (best-effort)
            if (!oldToken.isNullOrBlank() && oldToken != token) {
                try {
                    val response = apiService.unregisterDevice(snapshot, oldToken)
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Old token unregister failed: ${response.code()}")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "Old token unregister failed: ${e.message}")
                }
            }

            try {
                val response = apiService.registerDevice(
                    snapshot,
                    DeviceRequest(deviceToken = token, platform = PLATFORM_ANDROID)
                )
                if (response.isSuccessful) {
                    Log.d(TAG, "Device registered")
                } else {
                    Log.e(TAG, "Device registration failed: ${response.code()}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Device registration failed: ${e.message}")
            }
        }
    }

    /**
     * 서버에서 기기 등록 해제 (로그아웃 시)
     */
    suspend fun unregisterDeviceFromServer() =
        unregisterDeviceFromServer(captureOwnerOrNull())

    suspend fun unregisterDeviceFromServer(owner: AuthIdentityFence?) {
        if (!ReleaseAdmission.isOpen) return
        withContext(Dispatchers.IO) {
            val token = savedToken
            if (token != null && owner != null) {
                try {
                    val snapshot = apiService.captureSnapshot(owner)
                    val response = apiService.unregisterDevice(snapshot, token)
                    if (response.isSuccessful) {
                        Log.d(TAG, "Device unregistered")
                    } else {
                        Log.w(TAG, "Device unregister failed: ${response.code()}")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "Device unregister failed: ${e.message}")
                }
            }
            clearLocalRegistrationState()
        }
    }

    /** Clears app-owned registration state without issuing an authenticated server mutation. */
    fun clearLocalRegistrationState() {
        prefs.edit()
            .remove(KEY_FCM_TOKEN)
            .putBoolean(KEY_SHOULD_REGISTER, false)
            .apply()
    }

    private fun captureOwnerOrNull(): AuthIdentityFence? = try {
        apiService.captureIdentityFence()
    } catch (_: IOException) {
        null
    }

    /**
     * 프리미엄 전환 시 토큰 재등록 (SubscriptionManager.onAuthCompleted에서만 호출)
     * 알림 권한이 이미 승인된 상태면 shouldRegisterForPush 복원
     */
    suspend fun rehydratePushTokenIfNeeded() {
        if (!ReleaseAdmission.isOpen) return
        // 알림 권한이 승인된 상태면 shouldRegisterForPush 복원
        if (hasNotificationPermission()) {
            shouldRegisterForPush = true
        }
        registerIfNeeded()
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

/** Push registration is best-effort for ordinary credential lookup failures. */
internal suspend fun capturePushSnapshotOrNull(
    owner: AuthIdentityFence?,
    capture: suspend (AuthIdentityFence) -> AuthSnapshot,
    onFailure: (Exception) -> Unit = {}
): AuthSnapshot? {
    if (owner == null) return null
    return try {
        capture(owner)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
        null
    }
}

/**
 * The push-registration gate, outside the Android class so it can be judged on the JVM.
 *
 * Same reason `capturePushSnapshotOrNull` lives out here. Two conditions: the user has actually
 * asked for notifications, and the server has confirmed premium **for that exact session** — not
 * merely for that uid, and not for whoever happened to be signed in when some caller made up its
 * mind. A null owner needs no separate check; [confirmsPremiumFor] already refuses one, and a
 * second guard for the same thing would be untestable in isolation.
 */
internal fun pushRegistrationAllowed(
    owner: AuthIdentityFence?,
    access: OwnedPremiumAccess,
    shouldRegisterForPush: Boolean
): Boolean = shouldRegisterForPush && access.confirmsPremiumFor(owner)
