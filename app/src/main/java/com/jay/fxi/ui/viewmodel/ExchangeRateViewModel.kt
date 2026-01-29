package com.jay.fxi.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.local.CacheService
import com.jay.fxi.data.remote.WebSocketService
import com.jay.fxi.domain.model.AppState
import com.jay.fxi.domain.model.ConnectionState
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.repository.ExchangeRateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

    init {
        setupWebSocketCallbacks()
    }

    // ============ Public API ============

    /**
     * 서비스 시작 및 초기 데이터 로드
     * ContentView 진입 시 호출 (구독자 전용)
     */
    fun start() {
        viewModelScope.launch {
            loadInitialRates()
        }
    }

    /**
     * 서비스 종료
     * 로그아웃 또는 구독 해지 시 호출
     */
    fun stop() {
        webSocketService.stop()
        _appState.value = AppState.Loading
        _lastUpdated.value = null
    }

    /**
     * 수동 새로고침
     */
    fun refresh() {
        viewModelScope.launch {
            loadInitialRates()
        }
    }

    // ============ Private Methods ============

    private fun setupWebSocketCallbacks() {
        webSocketService.onRatesReceived = { rates ->
            handleRatesReceived(rates)
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
        _appState.value = AppState.Loading

        repository.getRates()
            .onSuccess { result ->
                _appState.value = AppState.Connected(result.rates)
                _lastUpdated.value = result.metadata.updatedAt
                cacheService.saveRates(result.rates)

                // REST 성공 후 WebSocket 시작
                webSocketService.start()
            }
            .onFailure { error ->
                // 캐시된 데이터로 오프라인 모드
                val cachedRates = cacheService.loadCachedRates()
                if (cachedRates != null) {
                    _appState.value = AppState.Offline(cachedRates)
                    _lastUpdated.value = cacheService.cachedRatesTimestamp()

                    // 오프라인이어도 WebSocket 시작 (네트워크 복구 시 연결)
                    webSocketService.start()
                } else {
                    _appState.value = AppState.Error(error.message ?: "알 수 없는 오류")
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 소멸 시 WebSocket 콜백 정리
        webSocketService.onRatesReceived = null
    }
}
