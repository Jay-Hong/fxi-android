package com.jay.fxi.data.remote

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.admission.ReleaseAdmissionInterceptor
import com.jay.fxi.data.network.NetworkMonitor
import com.jay.fxi.data.remote.dto.IndicesPayload
import com.jay.fxi.data.remote.dto.WebSocketGraphBuckets
import com.jay.fxi.data.remote.dto.WebSocketMessageType
import com.jay.fxi.data.remote.dto.WebSocketPing
import com.jay.fxi.data.remote.dto.WebSocketRatesMessage
import com.jay.fxi.di.WireJson
import com.jay.fxi.domain.model.ConnectionState
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.util.ApiConfig
import com.jay.fxi.util.WebSocketConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "WebSocketService"

internal fun buildWebSocketClient(): OkHttpClient = OkHttpClient.Builder()
    // D24 must run before metadata, DNS, or socket work on the independent WS stack.
    .addInterceptor(ReleaseAdmissionInterceptor())
    .addInterceptor(ClientMetadataInterceptor())
    .connectTimeout(WebSocketConfig.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(WebSocketConfig.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()

/**
 * WebSocket 서비스
 *
 * 주요 기능:
 * - 실시간 환율 데이터 수신 (10초마다)
 * - Ping/Pong 연결 상태 확인
 * - 자동 재연결 (지수 백오프 + 지터)
 * - 앱 라이프사이클 연동 (백그라운드 30초 이상 시 재연결)
 *
 * 구독자 전용:
 * - isActive=true일 때만 연결 가능
 * - start() 호출 전까지 모든 자동 연결 차단
 */
@Singleton
class WebSocketService @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    @WireJson private val json: Json
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ============ 연결 상태 ============

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * 서비스 활성화 상태 (구독자만 true)
     * 비구독자는 isActive=false이므로 모든 자동 연결 경로가 차단됨
     */
    private var isActive = false

    /**
     * 의도적 연결 종료 플래그
     * true일 때 자동 재연결 방지
     */
    private var isIntentionalDisconnect = true

    // ============ WebSocket ============

    private var webSocket: WebSocket? = null
    private val client = buildWebSocketClient()

    // ============ Ping/Pong ============

    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null

    // ============ 재연결 ============

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    // ============ 백그라운드 체류 시간 ============

    private var backgroundEnteredAt: Long? = null

    // ============ 콜백 ============

    var onRatesReceived: ((List<ExchangeRate>) -> Unit)? = null
    var onGraphBucketsReceived: ((WebSocketGraphBuckets) -> Unit)? = null
    var onIndicesReceived: ((IndicesPayload?) -> Unit)? = null

    init {
        if (ReleaseAdmission.isOpen) {
            // 앱 라이프사이클 관찰
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)

            // 네트워크 상태 변경 감지
            scope.launch {
                networkMonitor.isConnected.collect { isConnected ->
                    handleNetworkStateChange(isConnected)
                }
            }
        }
    }

    // ============ Public API ============

    /**
     * 서비스 활성화 및 연결 시작 (구독자 전용)
     * 이미 활성 상태라도 Failed/Disconnected면 재연결 시도
     */
    fun start() {
        if (!ReleaseAdmission.isOpen) return
        if (isActive) {
            // 이미 활성 상태지만 실패/끊김 상태면 재연결
            val state = _connectionState.value
            if (state is ConnectionState.Failed || state == ConnectionState.Disconnected) {
                Log.d(TAG, "start() - 활성 상태에서 재연결 시도")
                isIntentionalDisconnect = false
                reconnectAttempts = 0
                cleanup()  // 이전 상태 정리 후 연결
                connect()
            }
            return
        }
        Log.d(TAG, "start() - 서비스 활성화")
        isActive = true
        isIntentionalDisconnect = false
        connect()
    }

    /**
     * 서비스 비활성화 및 연결 종료
     */
    fun stop() {
        Log.d(TAG, "stop() - 서비스 비활성화")
        isActive = false
        disconnect()
    }

    // ============ 연결 관리 ============

    private fun connect() {
        if (!ReleaseAdmission.isOpen) return
        // 비활성 상태면 연결 차단
        if (!isActive) {
            Log.d(TAG, "connect() 차단 - isActive=false")
            return
        }

        // 이미 연결 중이거나 연결됨
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Connected
        ) {
            return
        }

        // 네트워크 없으면 실패
        if (!networkMonitor.isConnected.value) {
            _connectionState.value = ConnectionState.Failed("네트워크 연결 없음")
            return
        }

        Log.d(TAG, "connect() - 연결 시작")
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(ApiConfig.WS_URL)
            .build()

        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    private fun disconnect() {
        Log.d(TAG, "disconnect()")
        isIntentionalDisconnect = true
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun reconnect() {
        if (!ReleaseAdmission.isOpen) return
        // 비활성 상태 또는 의도적 종료 시 재연결 차단
        if (!isActive || isIntentionalDisconnect) {
            Log.d(TAG, "reconnect() 차단 - isActive=$isActive, intentional=$isIntentionalDisconnect")
            return
        }

        cleanup()

        // 네트워크 없으면 즉시 실패
        if (!networkMonitor.isConnected.value) {
            _connectionState.value = ConnectionState.Failed("네트워크 연결 없음")
            return
        }

        if (reconnectAttempts < WebSocketConfig.MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts)
            Log.d(TAG, "reconnect() - 시도 $reconnectAttempts/${WebSocketConfig.MAX_RECONNECT_ATTEMPTS}")

            // 지수 백오프: 2초 × 시도횟수 + 지터 (±20%)
            val baseDelay = WebSocketConfig.BASE_RECONNECT_DELAY_MS * reconnectAttempts
            val jitter = (baseDelay * Random.nextDouble(-0.2, 0.2)).toLong()
            val delay = baseDelay + jitter

            reconnectJob = scope.launch {
                delay(delay)
                if (isActive && !isIntentionalDisconnect) {
                    // 재연결 전 네트워크 다시 확인
                    if (networkMonitor.isConnected.value) {
                        connect()
                    } else {
                        _connectionState.value = ConnectionState.Failed("네트워크 연결 없음")
                    }
                }
            }
        } else {
            Log.d(TAG, "reconnect() - 최대 시도 횟수 초과")
            _connectionState.value = ConnectionState.Failed("연결할 수 없습니다")
        }
    }

    private fun cleanup() {
        pingJob?.cancel()
        pingJob = null
        pongTimeoutJob?.cancel()
        pongTimeoutJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.cancel()
        webSocket = null
    }

    // ============ WebSocket Listener ============

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "onOpen()")
            scope.launch {
                _connectionState.value = ConnectionState.Connected
                reconnectAttempts = 0
                startPingTimer()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // JSON 파싱은 Default 스레드에서 수행 (UI 버벅임 방지)
            scope.launch(Dispatchers.Default) {
                parseMessage(text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosing() - ${classifyCloseCode(code)} code=$code, reason=$reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosed() - ${classifyCloseCode(code)} code=$code, reason=$reason")
            scope.launch {
                handleConnectionError()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val errorType = when (t) {
                is java.net.UnknownHostException -> "DNS_RESOLVE"
                is java.net.ConnectException -> "CONNECTION_REFUSED"
                is java.net.SocketTimeoutException -> "TIMEOUT"
                is javax.net.ssl.SSLException -> "SSL"
                else -> t.javaClass.simpleName
            }
            val httpCode = response?.code?.let { " http=$it" } ?: ""
            Log.e(TAG, "onFailure() - type=$errorType$httpCode, ${t.message}")
            scope.launch {
                handleConnectionError()
            }
        }
    }

    // ============ 메시지 처리 ============

    private suspend fun parseMessage(text: String) {
        // 메시지 수신 = 연결 살아있음 → pong 타임아웃 취소
        // Job.cancel()은 thread-safe하므로 Main 스레드 불필요
        pongTimeoutJob?.cancel()
        pongTimeoutJob = null

        // Fast-path: pong은 JSON 파싱 없이 문자열 검사로 즉시 처리
        if (isPongMessage(text)) {
            Log.d(TAG, "Pong received (fast-path)")
            return
        }

        try {
            val message = json.decodeFromString<WebSocketRatesMessage>(text)

            when (message.type) {
                WebSocketMessageType.RATES -> {
                    // 콜백은 Main 스레드에서 호출
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        message.data?.let { data ->
                            onRatesReceived?.invoke(data.rates)
                            onIndicesReceived?.invoke(data.indices)
                        }
                        message.graphBuckets?.let { buckets ->
                            onGraphBucketsReceived?.invoke(buckets)
                        }
                    }
                }
                WebSocketMessageType.PONG -> {
                    // Pong 수신 - 이미 위에서 타임아웃 취소됨
                    Log.d(TAG, "Pong received")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseMessage() 실패: ${e.message}")
        }
    }

    private fun isPongMessage(rawText: String): Boolean {
        val text = rawText.trim()
        return text.equals(WebSocketMessageType.PONG, ignoreCase = true)
    }

    private fun classifyCloseCode(code: Int): String = when (code) {
        1000 -> "NORMAL"
        1001 -> "GOING_AWAY"
        1002 -> "PROTOCOL_ERROR"
        1003 -> "UNSUPPORTED"
        1006 -> "ABNORMAL"
        1011 -> "SERVER_ERROR"
        1012 -> "SERVICE_RESTART"
        else -> "CODE_$code"
    }

    // ============ Ping/Pong ============

    private fun startPingTimer() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.Connected) {
                delay(WebSocketConfig.PING_INTERVAL_MS)
                sendPing()
            }
        }
    }

    private fun sendPing() {
        val ws = webSocket ?: return
        try {
            val sent = ws.send(WebSocketPing.MESSAGE)
            if (sent) {
                startPongTimeout()
            } else {
                Log.e(TAG, "sendPing() 실패")
                handleConnectionError()
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendPing() 예외: ${e.message}")
            handleConnectionError()
        }
    }

    private fun startPongTimeout() {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = scope.launch {
            delay(WebSocketConfig.PONG_TIMEOUT_MS)
            Log.e(TAG, "Pong 타임아웃 - 연결 끊김 판단")
            handleConnectionError()
        }
    }

    // ============ 에러 처리 ============

    private fun handleConnectionError() {
        // 비활성 상태 또는 의도적 종료 시 재연결 차단
        if (!isActive || isIntentionalDisconnect) {
            return
        }
        reconnect()
    }

    // ============ 네트워크 상태 변경 ============

    private fun handleNetworkStateChange(isConnected: Boolean) {
        if (isConnected) {
            // 네트워크 복구 → 활성 상태이고 실패 상태면 재연결
            if (isActive && !isIntentionalDisconnect &&
                _connectionState.value is ConnectionState.Failed
            ) {
                Log.d(TAG, "네트워크 복구 → 재연결")
                reconnectAttempts = 0  // 재연결 횟수 초기화
                reconnect()
            }
        } else {
            // 네트워크 끊김 → 활성 상태면 즉시 실패
            if (isActive && _connectionState.value != ConnectionState.Disconnected) {
                Log.d(TAG, "네트워크 끊김 → 실패 상태")
                reconnectJob?.cancel()
                reconnectJob = null
                cleanup()
                _connectionState.value = ConnectionState.Failed("네트워크 연결 없음")
            }
        }
    }

    // ============ 앱 라이프사이클 ============

    override fun onStart(owner: LifecycleOwner) {
        // Foreground 복귀
        val duration = backgroundEnteredAt?.let { System.currentTimeMillis() - it } ?: 0
        backgroundEnteredAt = null

        // 비활성 상태 또는 의도적 종료 시 자동 연결 차단
        if (!isActive || isIntentionalDisconnect) {
            return
        }

        Log.d(TAG, "Foreground 복귀 - 백그라운드 체류: ${duration}ms")

        when {
            duration > 30_000 -> {
                // 30초 이상 → 무조건 재연결
                Log.d(TAG, "30초 이상 백그라운드 → 재연결")
                reconnectAttempts = 0
                reconnect()
            }
            _connectionState.value != ConnectionState.Connected -> {
                // 연결 끊김 → 재연결
                Log.d(TAG, "연결 끊김 상태 → 재연결")
                reconnectAttempts = 0
                reconnect()
            }
            else -> {
                // 연결 상태 → ping으로 확인
                Log.d(TAG, "연결 상태 → ping 검증")
                verifyConnectionWithPing()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // Background 진입 - 시간만 기록 (연결 유지 시도)
        backgroundEnteredAt = System.currentTimeMillis()
        Log.d(TAG, "Background 진입")
    }

    private fun verifyConnectionWithPing() {
        if (!isActive) return
        sendPing()
    }
}
