package com.jay.fxi.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 네트워크 연결 타입
 */
enum class ConnectionType {
    WIFI,
    CELLULAR,
    ETHERNET,
    UNKNOWN
}

/**
 * 네트워크 상태 모니터
 * ConnectivityManager를 사용하여 실시간 네트워크 상태 감지
 *
 * 주요 특징:
 * - init에서 콜백 등록 → StateFlow가 항상 최신 상태 유지
 * - INTERNET ∧ (VALIDATED ∨ ¬CAPTIVE_PORTAL)로 사용 가능 여부 판단
 * - 기본 네트워크 콜백 인자로 상태 갱신 → 이전 네트워크의 늦은 이벤트 무시
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val defaultNetworkState = DefaultNetworkState(
        initialConnected = checkCurrentConnectivity(),
        initialType = getCurrentConnectionType()
    )
    val isConnected: StateFlow<Boolean> = defaultNetworkState.connected
    val connectionType: StateFlow<ConnectionType> = defaultNetworkState.type

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetworkState.onAvailable(network.networkHandle)
        }

        override fun onLost(network: Network) {
            defaultNetworkState.onLost(network.networkHandle)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            defaultNetworkState.onCapabilitiesChanged(network.networkHandle, toNetworkFacts(networkCapabilities))
        }
    }

    init {
        // 앱 시작 시 즉시 콜백 등록 → StateFlow가 항상 최신 상태 유지
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    /**
     * 현재 네트워크 연결 상태 확인 (동기)
     * 현재 기본 네트워크의 capabilities에 사용 가능 정책 적용
     */
    fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isNetworkUsable(capabilities)
    }

    /**
     * 현재 연결 타입 조회
     */
    private fun getCurrentConnectionType(): ConnectionType {
        val network = connectivityManager.activeNetwork ?: return ConnectionType.UNKNOWN
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return ConnectionType.UNKNOWN
        return getConnectionType(capabilities)
    }

    /**
     * NetworkCapabilities에서 연결 타입 추출
     */
    private fun getConnectionType(capabilities: NetworkCapabilities): ConnectionType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            else -> ConnectionType.UNKNOWN
        }
    }

    /**
     * 네트워크 사용 가능 여부 판단
     *
     * VALIDATED가 없더라도 일부 환경(기업/학교망, 보안 DNS 등)에서는 실제 인터넷이 가능함.
     * VALIDATED가 없고 캡티브 포털인 경우는 제외.
     */
    private fun isNetworkUsable(capabilities: NetworkCapabilities): Boolean {
        return toNetworkFacts(capabilities).usable
    }

    private fun toNetworkFacts(capabilities: NetworkCapabilities): NetworkFacts {
        return NetworkFacts(
            internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            type = getConnectionType(capabilities)
        )
    }
}
