package com.jay.fxi.service

import android.content.Context
import android.util.Log
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
    private fun mayRegister(owner: AuthIdentityFence?): Boolean = pushRegistrationAllowed(
        owner, premiumAccessCoordinator.state.value, shouldRegisterForPush,
        capabilityGranted = readNotificationCapability(context) == NotificationCapability.GRANTED
    )
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

    private val reconciler = PushIntentReconciler(
        capability = { readNotificationCapability(context) },
        currentOwner = ::captureOwnerOrNull,
        premium = { premiumAccessCoordinator.state.value },
        held = { coordinator.isHeld },
        readIntent = { shouldRegisterForPush },
        writeIntent = { shouldRegisterForPush = it },
        register = { owner, token -> coordinator.register(owner, knownToken = token) }
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
        Log.d(TAG, "Registration on token rotation: ${reconciler.reconcile(owner, knownToken = token)}")
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

    /** Reconstitutes the current session's intent before evaluating its registration. */
    suspend fun rehydratePushTokenIfNeeded() {
        reconcileRegistration(captureOwnerOrNull())
    }

    suspend fun reconcileRegistration(owner: AuthIdentityFence?) {
        if (!ReleaseAdmission.isOpen) return
        Log.d(TAG, "Registration on reconcile: ${reconciler.reconcile(owner)}")
    }

    companion object {
        private const val TAG = "PushNotification"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_SHOULD_REGISTER = "should_register"
    }
}

/**
 * The push-registration gate shared by fresh admission checks for the captured session.
 * The local intent, current OS capability and that session's server premium must all agree.
 */
internal fun pushRegistrationAllowed(
    owner: AuthIdentityFence?,
    access: OwnedPremiumAccess,
    shouldRegisterForPush: Boolean,
    capabilityGranted: Boolean
): Boolean = shouldRegisterForPush && capabilityGranted && access.confirmsPremiumFor(owner)
