package com.jay.fxi.ui.settings

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.jay.fxi.BuildConfig
import com.jay.fxi.R
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.local.CacheService
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.service.PushNotificationManager
import com.jay.fxi.util.await
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DeletionStep {
    REAUTH, SERVER_DELETE, REVENUECAT_LOGOUT, FIREBASE_DELETE, LOCAL_CLEANUP
}

/** Keeps account-delete response application in the same owner-bound call stack. */
internal class AccountDeletionServerStage(
    private val captureSnapshot: suspend (AuthIdentityFence) -> AuthSnapshot,
    private val deleteUser: suspend (AuthSnapshot) -> AuthenticatedHttpResponse<Unit>,
    private val requireCurrent: (AuthIdentityFence) -> Unit
) {
    constructor(api: AuthenticatedApiClient) : this(
        captureSnapshot = { owner -> api.captureSnapshot(owner) },
        deleteUser = api::deleteUser,
        requireCurrent = api::requireCurrent
    )

    suspend fun <T> execute(
        owner: AuthIdentityFence,
        apply: (AuthenticatedHttpResponse<Unit>) -> T
    ): T {
        val snapshot = captureSnapshot(owner)
        val response = deleteUser(snapshot)
        requireCurrent(owner)
        return apply(response)
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val apiService: AuthenticatedApiClient,
    private val cacheService: CacheService,
    private val pushNotificationManager: PushNotificationManager
) : ViewModel() {
    private val accountDeletionServerStage = AccountDeletionServerStage(apiService)

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _deletionStep = MutableStateFlow<DeletionStep?>(null)
    val deletionStep: StateFlow<DeletionStep?> = _deletionStep.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 서버 삭제 성공 후 Firebase 삭제 실패 시 재시도용
    private val _serverDeletedButFirebaseFailed = MutableStateFlow(false)
    val serverDeletedButFirebaseFailed: StateFlow<Boolean> = _serverDeletedButFirebaseFailed.asStateFlow()
    private var pendingFirebaseDeletionOwner: AuthIdentityFence? = null

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 계정 삭제 5단계 플로우 (CLAUDE.md 순서)
     * Activity는 재인증에 필요 → ViewModel에 보관하지 않고 파라미터로 전달
     */
    fun deleteAccount(activity: Activity) {
        val deletionIntent = captureDeletionIntentOrNull() ?: return
        val user = auth.currentUser ?: run {
            _errorMessage.value = "로그인 상태가 아닙니다"
            return
        }
        if (deletionIntent.uid != user.uid || !isCurrent(deletionIntent)) {
            _errorMessage.value = "로그인 계정이 변경되었습니다. 다시 시도해주세요"
            return
        }
        val pendingOwner = pendingFirebaseDeletionOwner
        if (pendingOwner != null && pendingOwner != deletionIntent) {
            _errorMessage.value = "이전 계정의 삭제 재시도가 남아 있습니다"
            return
        }

        viewModelScope.launch {
            apiService.requireCurrent(deletionIntent)
            _isDeleting.value = true
            _errorMessage.value = null
            if (pendingOwner == null) _serverDeletedButFirebaseFailed.value = false

            try {
                // 1. 재인증
                _deletionStep.value = DeletionStep.REAUTH
                val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId
                apiService.requireCurrent(deletionIntent)
                try {
                    when (providerId) {
                        "google.com" -> reauthenticateWithGoogle(activity, user)
                        "apple.com" -> reauthenticateWithApple(activity, user)
                    }
                    apiService.requireCurrent(deletionIntent)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    apiService.requireCurrent(deletionIntent)
                    Log.w(TAG, "Reauthentication skipped or failed: ${e.message}")
                    // 최근 로그인이면 재인증 불필요할 수 있음 — 계속 진행
                }

                // 2. 서버 삭제 (실패 시 즉시 중단)
                _deletionStep.value = DeletionStep.SERVER_DELETE
                val serverDeleted = accountDeletionServerStage.execute(deletionIntent) { response ->
                    if (!response.isSuccessful) {
                        val code = response.code()
                        _errorMessage.value = when (code) {
                            401 -> "인증이 만료되었습니다. 다시 시도해주세요"
                            503 -> "서버가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요"
                            else -> "서버 오류가 발생했습니다 ($code)"
                        }
                        false
                    } else {
                        pendingFirebaseDeletionOwner = deletionIntent
                        _serverDeletedButFirebaseFailed.value = true
                        true
                    }
                }
                if (!serverDeleted) return@launch

                // 3. RevenueCat 로그아웃 (best-effort)
                _deletionStep.value = DeletionStep.REVENUECAT_LOGOUT
                apiService.requireCurrent(deletionIntent)
                if (BuildConfig.REVENUECAT_API_KEY.isNotBlank()) {
                    try {
                        if (!Purchases.sharedInstance.isAnonymous) {
                            Purchases.sharedInstance.logOut(
                                object : ReceiveCustomerInfoCallback {
                                    override fun onReceived(customerInfo: com.revenuecat.purchases.CustomerInfo) {
                                        Log.d(TAG, "RevenueCat logout success")
                                    }
                                    override fun onError(error: PurchasesError) {
                                        Log.w(TAG, "RevenueCat logout failed: $error")
                                    }
                                }
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        apiService.requireCurrent(deletionIntent)
                        Log.w(TAG, "RevenueCat logout failed", e)
                    }
                }

                // 4. Firebase 계정 삭제
                _deletionStep.value = DeletionStep.FIREBASE_DELETE
                apiService.requireCurrent(deletionIntent)
                try {
                    user.delete().await()
                    requireOwnerUnlessSignedOut(deletionIntent)
                } catch (e: FirebaseAuthRecentLoginRequiredException) {
                    // 재인증 후 재시도
                    try {
                        apiService.requireCurrent(deletionIntent)
                        when (providerId) {
                            "google.com" -> reauthenticateWithGoogle(activity, user)
                            "apple.com" -> reauthenticateWithApple(activity, user)
                        }
                        apiService.requireCurrent(deletionIntent)
                        user.delete().await()
                        requireOwnerUnlessSignedOut(deletionIntent)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (retryError: Exception) {
                        requireOwnerUnlessSignedOut(deletionIntent)
                        _serverDeletedButFirebaseFailed.value = true
                        _errorMessage.value = "서버 데이터는 삭제되었으나 계정 삭제가 완료되지 않았습니다. 재인증 후 다시 시도해주세요."
                        return@launch
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    requireOwnerUnlessSignedOut(deletionIntent)
                    _serverDeletedButFirebaseFailed.value = true
                    _errorMessage.value = "서버 데이터는 삭제되었으나 계정 삭제가 완료되지 않았습니다. 재인증 후 다시 시도해주세요."
                    return@launch
                }

                // 5. 로컬 정리
                // 서버 디바이스 등록은 step 2(deleteUser)에서 이미 삭제됐다. 여기서 인증 DELETE를
                // 다시 보내면 늦은 A 정리가 B 등록을 지울 수 있으므로 로컬 상태만 먼저 비운다.
                _deletionStep.value = DeletionStep.LOCAL_CLEANUP
                requireOwnerUnlessSignedOut(deletionIntent)
                pendingFirebaseDeletionOwner = null
                _serverDeletedButFirebaseFailed.value = false
                pushNotificationManager.clearLocalRegistrationState()
                cacheService.clearAllCache()

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (!isCurrentOrSignedOut(deletionIntent)) return@launch
                Log.e(TAG, "Account deletion failed", e)
                _errorMessage.value = "계정 삭제 중 오류가 발생했습니다"
            } finally {
                _isDeleting.value = false
                _deletionStep.value = null
            }
        }
    }

    /**
     * Firebase 삭제 재시도 (서버 삭제 성공 후 Firebase 실패 케이스)
     */
    fun retryFirebaseDelete(activity: Activity) {
        val deletionIntent = pendingFirebaseDeletionOwner ?: run {
            _errorMessage.value = "재시도할 계정 삭제가 없습니다"
            return
        }
        val user = auth.currentUser ?: run {
            _errorMessage.value = "삭제를 시작한 계정으로 다시 로그인해주세요"
            return
        }
        if (deletionIntent.uid != user.uid || !isCurrent(deletionIntent)) {
            _errorMessage.value = "삭제를 시작한 동일 로그인 세션에서만 재시도할 수 있습니다"
            return
        }
        val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId

        viewModelScope.launch {
            apiService.requireCurrent(deletionIntent)
            _isDeleting.value = true
            _errorMessage.value = null
            try {
                apiService.requireCurrent(deletionIntent)
                when (providerId) {
                    "google.com" -> reauthenticateWithGoogle(activity, user)
                    "apple.com" -> reauthenticateWithApple(activity, user)
                }
                apiService.requireCurrent(deletionIntent)
                user.delete().await()
                requireOwnerUnlessSignedOut(deletionIntent)
                pendingFirebaseDeletionOwner = null
                _serverDeletedButFirebaseFailed.value = false

                requireOwnerUnlessSignedOut(deletionIntent)
                pushNotificationManager.clearLocalRegistrationState()
                cacheService.clearAllCache()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (!isCurrentOrSignedOut(deletionIntent)) return@launch
                _errorMessage.value = "계정 삭제가 완료되지 않았습니다. 다시 시도해주세요."
            } finally {
                _isDeleting.value = false
            }
        }
    }

    private fun captureDeletionIntentOrNull(): AuthIdentityFence? = try {
        apiService.captureIdentityFence()
    } catch (_: IOException) {
        _errorMessage.value = "로그인 상태가 아닙니다"
        null
    }

    private fun isCurrent(owner: AuthIdentityFence): Boolean = try {
        apiService.requireCurrent(owner)
        true
    } catch (_: CancellationException) {
        false
    }

    private fun isCurrentOrSignedOut(owner: AuthIdentityFence): Boolean =
        auth.currentUser == null || isCurrent(owner)

    private fun requireOwnerUnlessSignedOut(owner: AuthIdentityFence) {
        if (auth.currentUser != null) apiService.requireCurrent(owner)
    }

    private suspend fun reauthenticateWithGoogle(
        activity: Activity,
        user: FirebaseUser
    ) {
        val serverClientId = activity.getString(R.string.default_web_client_id)
        if (serverClientId.isBlank()) {
            throw IllegalStateException("Google 로그인 설정이 필요합니다")
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val credentialManager = CredentialManager.create(activity)
        val result = credentialManager.getCredential(activity, request)
        val credential = result.credential

        if (credential is androidx.credentials.CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(
                googleIdTokenCredential.idToken,
                null
            )
            user.reauthenticate(firebaseCredential).await()
        }
    }

    private suspend fun reauthenticateWithApple(
        activity: Activity,
        user: FirebaseUser
    ) {
        val provider = OAuthProvider.newBuilder("apple.com")
        provider.scopes = listOf("email", "name")
        provider.addCustomParameter("locale", Locale.getDefault().language)

        user.startActivityForReauthenticateWithProvider(activity, provider.build()).await()
    }

    companion object {
        private const val TAG = "Settings"
    }
}
