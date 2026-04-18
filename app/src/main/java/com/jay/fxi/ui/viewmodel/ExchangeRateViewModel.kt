package com.jay.fxi.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.local.CacheService
import com.jay.fxi.data.remote.WebSocketService
import com.jay.fxi.data.remote.dto.DxyLiveTick
import com.jay.fxi.data.remote.dto.IndicesPayload
import com.jay.fxi.domain.model.AppState
import com.jay.fxi.domain.model.ConnectionState
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.repository.ExchangeRateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject

/**
 * 환율 데이터 ViewModel
 *
 * 주요 기능:
 * - REST API로 초기 환율 로드
 * - WebSocket 실시간 업데이트
 * - 오프라인 캐시 지원
 * - 연결 상태 관리
 */
@HiltViewModel
class ExchangeRateViewModel @Inject constructor(
    private val repository: ExchangeRateRepository,
    private val webSocketService: WebSocketService,
    private val cacheService: CacheService
) : ViewModel() {

    private val _appState = MutableStateFlow<AppState>(AppState.Loading)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = webSocketService.connectionState

    private val _lastUpdated = MutableStateFlow<Instant?>(null)
    val lastUpdated: StateFlow<Instant?> = _lastUpdated.asStateFlow()

    /**
     * DXY live tick (WebSocket indices.dxy, 10초 realtime).
     * null이면 graphCache fallback 사용. iOS dxyLive와 parity.
     */
    private val _dxyLive = MutableStateFlow<DxyLiveTick?>(null)
    val dxyLive: StateFlow<DxyLiveTick?> = _dxyLive.asStateFlow()

    /**
     * 진행 중인 초기 로드 작업.
     * in-flight 중 start()/refresh() 재호출 시 중복 launch 방지 + reset()/onCleared()
     * 에서 명시적 cancel로 late response가 state를 덮는 race를 차단.
     */
    private var initialLoadJob: Job? = null

    init {
        setupWebSocketCallbacks()
    }

    // ============ Public API ============

    /**
     * 서비스 시작 및 초기 데이터 로드
     * ContentView 진입 시 호출 (구독자 전용)
     *
     * 회전 등 MainScreen 재진입 시 중복 REST 요청 방지 가드.
     * WebSocket이 이미 연결 중이고 사용 가능한 데이터를 보유한 경우,
     * 실시간 stream으로 이미 업데이트되고 있으므로 REST 초기 로드 생략.
     * cold start / reset 후 재진입 / 연결 끊김 상태에서는 기존 경로로 fetch.
     * initial load 진행 중 회전 시에는 initialLoadJob가 in-flight라 중복 launch 차단.
     */
    fun start() {
        val hasUsableData = _appState.value is AppState.Connected ||
            _appState.value is AppState.Offline
        if (hasUsableData && connectionState.value == ConnectionState.Connected) return
        launchInitialLoadIfNotActive()
    }

    /**
     * transport 정리 (WebSocket만 끊고 UI state는 유지).
     * MainScreen composable dispose 시 호출 — 회전 등 configuration change 포함.
     * UI state 유지 덕분에 Activity 재생성 후 start()에서 flash 없이 이어짐.
     */
    fun stop() {
        webSocketService.stop()
    }

    /**
     * 세션 완전 teardown (transport 정리 + UI state 초기화 + in-flight 취소).
     * 로그아웃 또는 구독 해지 시 RootScreen에서 호출.
     *
     * initialLoadJob cancel은 로그아웃 직후 늦게 도착한 응답이 Loading으로
     * 초기화된 state를 덮어 Connected로 되돌리는 race 방지.
     */
    fun reset() {
        initialLoadJob?.cancel()
        initialLoadJob = null
        stop()
        _appState.value = AppState.Loading
        _lastUpdated.value = null
        _dxyLive.value = null
    }

    /**
     * 수동 새로고침
     * in-flight 중 재호출 시 중복 launch 차단.
     */
    fun refresh() {
        launchInitialLoadIfNotActive()
    }

    // ============ Private Methods ============

    /**
     * initialLoadJob이 in-flight가 아닐 때만 loadInitialRates를 새로 launch.
     * completion 시 invokeOnCompletion으로 self-reference면 null 정리 (stale reference 방지).
     */
    private fun launchInitialLoadIfNotActive() {
        if (initialLoadJob?.isActive == true) return
        val job = viewModelScope.launch { loadInitialRates() }
        initialLoadJob = job
        job.invokeOnCompletion {
            if (initialLoadJob === job) initialLoadJob = null
        }
    }

    private fun setupWebSocketCallbacks() {
        webSocketService.onRatesReceived = { rates ->
            handleRatesReceived(rates)
        }
        webSocketService.onIndicesReceived = { indices ->
            _dxyLive.value = indices?.dxy
        }
    }

    private fun handleRatesReceived(rates: List<ExchangeRate>) {
        _appState.value = AppState.Connected(rates)
        _lastUpdated.value = Clock.System.now()

        // 캐시 저장 (백그라운드)
        viewModelScope.launch {
            cacheService.saveRates(rates)
        }
    }

    private suspend fun loadInitialRates() {
        // 이미 데이터가 있는 상태(회전 후 재진입 등)에서는 Loading 플래시 생략,
        // 백그라운드 refresh로 처리하여 iOS와 같은 자연스러운 전환 보장.
        val hasExistingData = _appState.value is AppState.Connected ||
            _appState.value is AppState.Offline

        if (!hasExistingData) {
            _appState.value = AppState.Loading
        }

        repository.getRates()
            .onSuccess { result ->
                _appState.value = AppState.Connected(result.rates)
                _lastUpdated.value = result.metadata.updatedAt
                cacheService.saveRates(result.rates)

                // REST 성공 후 WebSocket 시작
                webSocketService.start()
            }
            .onFailure { error ->
                if (hasExistingData) {
                    // 회전 후 fetch 실패: 기존 Connected/Offline 상태 그대로 유지,
                    // WebSocket만 재시작해 네트워크 복구 시 자동 회복
                    webSocketService.start()
                } else {
                    // 콜드 스타트 fetch 실패: 캐시로 오프라인 fallback
                    val cachedRates = cacheService.loadCachedRates()
                    if (cachedRates != null) {
                        _appState.value = AppState.Offline(cachedRates)
                        _lastUpdated.value = cacheService.cachedRatesTimestamp()
                        webSocketService.start()
                    } else {
                        _appState.value = AppState.Error(error.message ?: "알 수 없는 오류")
                    }
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 소멸 시 WebSocket 연결 및 콜백 정리.
        // 서비스 lifecycle이 MainScreen이 아닌 session scope로 이동했으므로
        // 앱 종료/프로세스 kill 시의 transport cleanup은 여기서 보장.
        // initialLoadJob cancel은 viewModelScope 취소로 이미 커버되지만 명시적
        // cleanup 의도를 drive하여 discipline 일관성 유지.
        initialLoadJob?.cancel()
        initialLoadJob = null
        webSocketService.stop()
        webSocketService.onRatesReceived = null
        webSocketService.onIndicesReceived = null
    }
}
