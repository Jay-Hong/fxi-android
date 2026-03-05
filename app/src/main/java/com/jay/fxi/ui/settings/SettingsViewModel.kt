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
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.jay.fxi.BuildConfig
import com.jay.fxi.R
import com.jay.fxi.data.local.CacheService
import com.jay.fxi.data.remote.FXiApiService
import com.jay.fxi.service.PushNotificationManager
import com.jay.fxi.util.await
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

enum class DeletionStep {
    REAUTH, SERVER_DELETE, REVENUECAT_LOGOUT, FIREBASE_DELETE, LOCAL_CLEANUP
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val apiService: FXiApiService,
    private val cacheService: CacheService,
    private val pushNotificationManager: PushNotificationManager
) : ViewModel() {

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _deletionStep = MutableStateFlow<DeletionStep?>(null)
    val deletionStep: StateFlow<DeletionStep?> = _deletionStep.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 서버 삭제 성공 후 Firebase 삭제 실패 시 재시도용
    private val _serverDeletedButFirebaseFailed = MutableStateFlow(false)
    val serverDeletedButFirebaseFailed: StateFlow<Boolean> = _serverDeletedButFirebaseFailed.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 계정 삭제 5단계 플로우 (CLAUDE.md 순서)
     * Activity는 재인증에 필요 → ViewModel에 보관하지 않고 파라미터로 전달
     */
    fun deleteAccount(activity: Activity) {
        val user = auth.currentUser ?: run {
            _errorMessage.value = "로그인 상태가 아닙니다"
            return
        }

        viewModelScope.launch {
            _isDeleting.value = true
            _errorMessage.value = null
            _serverDeletedButFirebaseFailed.value = false

            try {
                // 1. 재인증
                _deletionStep.value = DeletionStep.REAUTH
                val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId
                try {
                    when (providerId) {
                        "google.com" -> reauthenticateWithGoogle(activity)
                        "apple.com" -> reauthenticateWithApple(activity)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reauthentication skipped or failed: ${e.message}")
                    // 최근 로그인이면 재인증 불필요할 수 있음 — 계속 진행
                }

                // 2. 서버 삭제 (실패 시 즉시 중단)
                _deletionStep.value = DeletionStep.SERVER_DELETE
                val response = apiService.deleteUser()
                if (!response.isSuccessful) {
                    val code = response.code()
                    _errorMessage.value = when (code) {
                        401 -> "인증이 만료되었습니다. 다시 시도해주세요"
                        503 -> "서버가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요"
                        else -> "서버 오류가 발생했습니다 ($code)"
                    }
                    return@launch
                }

                // 3. RevenueCat 로그아웃 (best-effort)
                _deletionStep.value = DeletionStep.REVENUECAT_LOGOUT
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
                    } catch (e: Exception) {
                        Log.w(TAG, "RevenueCat logout failed", e)
                    }
                }

                // 4. Firebase 계정 삭제
                _deletionStep.value = DeletionStep.FIREBASE_DELETE
                try {
                    user.delete().await()
                } catch (e: FirebaseAuthRecentLoginRequiredException) {
                    // 재인증 후 재시도
                    try {
                        when (providerId) {
                            "google.com" -> reauthenticateWithGoogle(activity)
                            "apple.com" -> reauthenticateWithApple(activity)
                        }
                        user.delete().await()
                    } catch (retryError: Exception) {
                        _serverDeletedButFirebaseFailed.value = true
                        _errorMessage.value = "서버 데이터는 삭제되었으나 계정 삭제가 완료되지 않았습니다. 재인증 후 다시 시도해주세요."
                        return@launch
                    }
                } catch (e: Exception) {
                    _serverDeletedButFirebaseFailed.value = true
                    _errorMessage.value = "서버 데이터는 삭제되었으나 계정 삭제가 완료되지 않았습니다. 재인증 후 다시 시도해주세요."
                    return@launch
                }

                // 5. 로컬 정리
                // Note: unregisterDeviceFromServer()의 서버 API 호출은 user.delete() 후 토큰 부재로 실패하지만,
                // 서버 디바이스 등록은 step 2(deleteUser)에서 이미 삭제됨. 여기서는 로컬 상태(savedToken, shouldRegisterForPush) 정리가 목적.
                _deletionStep.value = DeletionStep.LOCAL_CLEANUP
                cacheService.clearAllCache()
                pushNotificationManager.unregisterDeviceFromServer()

            } catch (e: Exception) {
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
        val user = auth.currentUser ?: return
        val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId

        viewModelScope.launch {
            _isDeleting.value = true
            _errorMessage.value = null
            try {
                when (providerId) {
                    "google.com" -> reauthenticateWithGoogle(activity)
                    "apple.com" -> reauthenticateWithApple(activity)
                }
                user.delete().await()
                _serverDeletedButFirebaseFailed.value = false

                cacheService.clearAllCache()
                pushNotificationManager.unregisterDeviceFromServer()
            } catch (e: Exception) {
                _errorMessage.value = "계정 삭제가 완료되지 않았습니다. 다시 시도해주세요."
            } finally {
                _isDeleting.value = false
            }
        }
    }

    private suspend fun reauthenticateWithGoogle(activity: Activity) {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in")
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

    private suspend fun reauthenticateWithApple(activity: Activity) {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in")
        val provider = OAuthProvider.newBuilder("apple.com")
        provider.scopes = listOf("email", "name")
        provider.addCustomParameter("locale", Locale.getDefault().language)

        user.startActivityForReauthenticateWithProvider(activity, provider.build()).await()
    }

    companion object {
        private const val TAG = "Settings"
    }
}
