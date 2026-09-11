package com.jay.fxi.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.confirmsPremiumFor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.util.await
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class PushNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: AuthenticatedApiClient,
    private val premiumAccessCoordinator: PremiumAccessCoordinator,
    ledger: PushRegistrationLedger
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

    var shouldRegisterForPush: Boolean
        get() = ReleaseAdmission.isOpen && prefs.getBoolean(KEY_SHOULD_REGISTER, false)
        set(value) {
            if (!ReleaseAdmission.isOpen) return
            prefs.edit().putBoolean(KEY_SHOULD_REGISTER, value).apply()
        }

    /** Registration and sign-out teardown, serialised over the ledger. */
    private val coordinator = PushRegistrationCoordinator(
        ledger = ledger,
        server = ApiPushDeviceServer(apiService),
        currentFence = ::captureOwnerOrNull,
        eligible = { mayRegister(it) },
        deviceToken = ::currentDeviceToken,
        clearLegacyLocalState = ::clearLocalRegistrationState
    )

    /**
     * FCM 토큰 갱신 시 호출 (FXiMessagingService.onNewToken)
     *
     * 새 토큰으로 등록을 평가한다. 장부가 아는 이전 토큰은 coordinator 의 회전 정리가 해제한다.
     * 캐시된 토큰을 이전 토큰으로 보고 지우지 않는다 — 백업으로 복원된 다른 기기의 토큰일 수 있다.
     */
    suspend fun onNewToken(token: String) {
        if (!ReleaseAdmission.isOpen) return
        val owner = captureOwnerOrNull() ?: return
        Log.d(TAG, "Registration on token rotation: ${coordinator.register(owner, knownToken = token)}")
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
        Log.d(TAG, "Registration: ${coordinator.register(owner)}")
    }

    /**
     * 서버에서 기기 등록 해제 (로그아웃 시). [owner] 는 로그아웃을 시작한 세션이다.
     *
     * coordinator 가 첫 suspension 전에 등록 입장을 닫으므로 여기서 다른 문맥으로 옮기지 않는다.
     */
    suspend fun unregisterDeviceFromServer(owner: AuthIdentityFence) {
        if (!ReleaseAdmission.isOpen) return
        coordinator.unregister(owner)
    }

    /**
     * Clears app-owned registration state without issuing an authenticated server mutation.
     *
     * `fcm_token` is no longer written; removing it still clears the value earlier builds left.
     */
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
     * This install's current FCM token, or null when it cannot be had. Never a cached one: a cache
     * restored from another device's backup would register — and later delete — that device's token.
     * Cancellation is checked before asking: cancelling the await does not withdraw a request made.
     */
    private suspend fun currentDeviceToken(): String? = try {
        currentCoroutineContext().ensureActive()
        FirebaseMessaging.getInstance().token.await().takeUnless { it.isNullOrBlank() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Log.w(TAG, "FCM token unavailable: ${e.message}")
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
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_SHOULD_REGISTER = "should_register"
    }
}

/**
 * The push-registration gate, outside the Android class so it can be judged on the JVM.
 *
 * Same reason `ApiPushDeviceServer` lives outside this class. Two conditions: the user has actually
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
