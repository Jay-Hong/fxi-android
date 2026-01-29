package com.jay.fxi.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - NET_CAPABILITY_VALIDATED 체크 → 캡티브 포털 false positive 방지
 * - onLost 시 다른 네트워크 존재 여부 재확인 → WiFi→Cellular 전환 대응
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionType = MutableStateFlow(getCurrentConnectionType())
    val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 캡티브 포털 등 검증 안 된 네트워크 방지
            val actuallyConnected = checkCurrentConnectivity()
            _isConnected.value = actuallyConnected
            if (actuallyConnected) {
                _connectionType.value = getCurrentConnectionType()
            }
        }

        override fun onLost(network: Network) {
            // 다른 네트워크가 있는지 확인 (WiFi→Cellular 전환 등)
            val stillConnected = checkCurrentConnectivity()
            _isConnected.value = stillConnected
            _connectionType.value = if (stillConnected) {
                getCurrentConnectionType()
            } else {
                ConnectionType.UNKNOWN
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val connected = networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) && networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
            _isConnected.value = connected
            _connectionType.value = if (connected) {
                getConnectionType(networkCapabilities)
            } else {
                ConnectionType.UNKNOWN
            }
        }
    }

    init {
        // 앱 시작 시 즉시 콜백 등록 → StateFlow가 항상 최신 상태 유지
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    /**
     * 현재 네트워크 연결 상태 확인 (동기)
     * VALIDATED 체크로 실제 인터넷 접속 가능 여부 확인
     */
    fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
}
