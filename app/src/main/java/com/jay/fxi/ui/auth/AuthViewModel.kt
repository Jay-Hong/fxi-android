package com.jay.fxi.ui.auth

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.jay.fxi.BuildConfig
import com.jay.fxi.R
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.service.PushNotificationManager
import com.jay.fxi.subscription.SubscriptionManager
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.LogInCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val subscriptionManager: SubscriptionManager,
    private val pushNotificationManager: PushNotificationManager
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        viewModelScope.launch {
            val user = firebaseAuth.currentUser
            if (user != null) {
                if (BuildConfig.REVENUECAT_API_KEY.isNotBlank()) {
                    val session = subscriptionManager.beginAuthSession(user.uid)
                    try {
                        Purchases.sharedInstance.logIn(
                            user.uid,
                            object : LogInCallback {
                                override fun onReceived(customerInfo: com.revenuecat.purchases.CustomerInfo, created: Boolean) {
                                    Log.d(TAG, "RevenueCat login success, created=$created")
                                    viewModelScope.launch { subscriptionManager.onAuthCompleted(customerInfo, session) }
                                }

                                override fun onError(error: PurchasesError) {
                                    Log.e(TAG, "RevenueCat login failed: $error")
                                    viewModelScope.launch { subscriptionManager.onAuthCompleted(session = session) }
                                }
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "RevenueCat login failed", e)
                        subscriptionManager.onAuthCompleted(session = session)
                    }
                } else {
                    subscriptionManager.onAuthCompleted()
                }

                val providerId = user.providerData.firstOrNull { it.providerId != "firebase" }?.providerId
                val provider = when (providerId) {
                    "apple.com" -> AuthProvider.APPLE
                    else -> AuthProvider.GOOGLE
                }

                _authState.value = AuthState.SignedIn(
                    UserInfo(
                        uid = user.uid,
                        email = user.email,
                        displayName = user.displayName,
                        photoUrl = user.photoUrl?.toString(),
                        provider = provider
                    )
                )
            } else {
                if (BuildConfig.REVENUECAT_API_KEY.isNotBlank()) {
                    if (!Purchases.sharedInstance.isAnonymous) {
                        try {
                            Purchases.sharedInstance.logOut(
                                object : ReceiveCustomerInfoCallback {
                                    override fun onReceived(customerInfo: com.revenuecat.purchases.CustomerInfo) {
                                        Log.d(TAG, "RevenueCat logout success")
                                    }

                                    override fun onError(error: PurchasesError) {
                                        Log.e(TAG, "RevenueCat logout failed: $error")
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "RevenueCat logout failed", e)
                        }
                    }
                }

                subscriptionManager.onAuthSignedOut()
                _authState.value = AuthState.SignedOut
            }
        }
    }

    init {
        auth.addAuthStateListener(authListener)
    }

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val serverClientId = activity.getString(R.string.default_web_client_id)
                if (serverClientId.isBlank()) {
                    _errorMessage.value = "Google 로그인 설정이 필요합니다"
                    return@launch
                }

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
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
                    auth.signInWithCredential(firebaseCredential).await()
                } else {
                    _errorMessage.value = "지원하지 않는 로그인 방식입니다"
                }
            } catch (e: Exception) {
                _errorMessage.value = mapAuthError(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signInWithApple(activity: Activity) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val provider = OAuthProvider.newBuilder("apple.com")
                provider.scopes = listOf("email", "name")
                provider.addCustomParameter("locale", Locale.getDefault().language)

                val pending = auth.pendingAuthResult
                if (pending != null) {
                    pending.await()
                } else {
                    auth.startActivityForSignInWithProvider(activity, provider.build()).await()
                }
            } catch (e: Exception) {
                _errorMessage.value = mapAuthError(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun signOut() {
        viewModelScope.launch {
            pushNotificationManager.unregisterDeviceFromServer()
            auth.signOut()
        }
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
        super.onCleared()
    }

    private fun mapAuthError(error: Exception): String? {
        return when (error) {
            is GetCredentialCancellationException -> null
            is GetCredentialException -> "로그인에 실패했습니다. 다시 시도해주세요"
            is GoogleIdTokenParsingException -> "Google 토큰 파싱에 실패했습니다"
            else -> "로그인 중 문제가 발생했습니다"
        }
    }

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { result -> cont.resume(result) }
            addOnFailureListener { exception -> cont.resumeWithException(exception) }
        }

    companion object {
        private const val TAG = "Auth"
    }
}
