package com.jay.fxi.domain.model

import com.jay.fxi.util.WebSocketConfig

/**
 * 앱 전체 상태
 */
sealed class AppState {
    data object Loading : AppState()
    data class Connected(val currentRates: List<ExchangeRate>) : AppState()
    data class Error(val message: String) : AppState()
    data class Offline(val cachedRates: List<ExchangeRate>?) : AppState()

    val rates: List<ExchangeRate>?
        get() = when (this) {
            is Connected -> currentRates
            is Offline -> cachedRates
            else -> null
        }

    val hasData: Boolean get() = !rates.isNullOrEmpty()
    val isLoading: Boolean get() = this is Loading
    val isError: Boolean get() = this is Error
    val isOffline: Boolean get() = this is Offline
    val isConnected: Boolean get() = this is Connected
    val errorMessage: String? get() = (this as? Error)?.message
}

/**
 * WebSocket 연결 상태
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()

    val isConnecting: Boolean get() = this is Connecting || this is Reconnecting
    val isConnected: Boolean get() = this is Connected
    val isFailed: Boolean get() = this is Failed

    val statusText: String
        get() = when (this) {
            Disconnected -> "연결 끊김"
            Connecting -> "연결 중..."
            Connected -> "실시간 연결"
            is Reconnecting -> if (attempt == 1) "연결 중..." else "재연결 중 ($attempt/${WebSocketConfig.MAX_RECONNECT_ATTEMPTS})"
            is Failed -> message
        }
}

/**
 * 인증 상태
 */
sealed class AuthState {
    data object Unknown : AuthState()
    data object SignedOut : AuthState()
    data class SignedIn(val user: UserInfo) : AuthState()

    val isSignedIn: Boolean get() = this is SignedIn
    val isSignedOut: Boolean get() = this is SignedOut
    val isUnknown: Boolean get() = this is Unknown
}

/**
 * 사용자 정보
 */
data class UserInfo(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val provider: AuthProvider
)

/**
 * 인증 제공자
 */
enum class AuthProvider {
    GOOGLE,
    APPLE
}

/**
 * 알림 설정 상태
 */
sealed class AlertSettingsState {
    data object Idle : AlertSettingsState()
    data object Loading : AlertSettingsState()
    data class Loaded(val alertSettings: List<AlertSetting>) : AlertSettingsState()
    data class Error(val message: String) : AlertSettingsState()

    val isLoading: Boolean get() = this is Loading
    val settings: List<AlertSetting>? get() = (this as? Loaded)?.alertSettings
}
