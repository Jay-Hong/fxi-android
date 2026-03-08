package com.jay.fxi.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.local.CacheService
import com.jay.fxi.data.remote.WebSocketService
import com.jay.fxi.data.remote.dto.WebSocketGraphBucket
import com.jay.fxi.data.remote.dto.WebSocketGraphBuckets
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.domain.model.MutableGraphCache
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.domain.repository.ExchangeRateRepository
import com.jay.fxi.util.GraphConfig
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 그래프 데이터 ViewModel
 *
 * 주요 기능:
 * - REST API로 그래프 데이터 로드
 * - WebSocket 실시간 버킷 업데이트
 * - 갭 감지 및 REST 복구
 * - 메모리 + 디스크 캐시
 * - 주기적 갭 체크 (5분)
 */
@HiltViewModel
class GraphViewModel @Inject constructor(
    private val repository: ExchangeRateRepository,
    private val webSocketService: WebSocketService,
    private val cacheService: CacheService
) : ViewModel() {

    // ============ 상태 ============

    /**
     * 그래프 캐시: [currency: [source: [GraphBucket]]]
     * Mutex로 동시 접근 보호 (Default 스레드 업데이트 + UI 읽기)
     */
    private val graphCache: MutableGraphCache = mutableMapOf()
    private val cacheMutex = Mutex()

    private val _activeCurrency = MutableStateFlow(SupportedCurrency.USD_KRW)
    val activeCurrency: StateFlow<SupportedCurrency> = _activeCurrency.asStateFlow()

    private val _selectedSources = MutableStateFlow(GraphSource.entries.toSet())
    val selectedSources: StateFlow<Set<GraphSource>> = _selectedSources.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _graphVersion = MutableStateFlow(0L)
    val graphVersion: StateFlow<Long> = _graphVersion.asStateFlow()

    private val _isGraphFullscreen = MutableStateFlow(false)
    val isGraphFullscreen: StateFlow<Boolean> = _isGraphFullscreen.asStateFlow()

    private val _isRatesFullscreen = MutableStateFlow(false)
    val isRatesFullscreen: StateFlow<Boolean> = _isRatesFullscreen.asStateFlow()

    fun setGraphFullscreen(value: Boolean) { _isGraphFullscreen.value = value }
    fun setRatesFullscreen(value: Boolean) { _isRatesFullscreen.value = value }

    /**
     * 서비스 활성화 상태 (구독자만 true)
     */
    private var isActive = false

    /**
     * 상태 변수 보호용 Mutex (inFlightCurrencies, currenciesNeedingRefresh)
     */
    private val stateMutex = Mutex()

    /**
     * 갭으로 인해 REST 복구가 필요한 통화들
     * stateMutex로 보호
     */
    private val currenciesNeedingRefresh = mutableSetOf<String>()

    /**
     * 현재 REST 요청 중인 통화 (중복 방지)
     * stateMutex로 보호
     */
    private val inFlightCurrencies = mutableSetOf<String>()

    /**
     * 통화별 마지막 REST 호출 시간 (쿨다운 관리)
     */
    private val lastFullFetchTimeMap = mutableMapOf<String, Long>()

    private var periodicRefreshJob: Job? = null

    init {
        // 앱 시작 시 디스크 캐시 로드
        viewModelScope.launch {
            val diskCache = cacheService.loadAllGraphData()
            cacheMutex.withLock {
                diskCache.forEach { (currency, sourceData) ->
                    graphCache[currency] = sourceData.mapValues { it.value.toMutableList() }.toMutableMap()
                }
            }
            if (diskCache.isNotEmpty()) {
                markGraphUpdated()
            }
        }

        setupWebSocketCallback()
    }

    // ============ Public API ============

    /**
     * 서비스 활성화 (구독자 전용)
     */
    fun start() {
        if (isActive) return
        isActive = true
        setupPeriodicRefresh()
    }

    /**
     * 서비스 비활성화
     */
    fun stop() {
        isActive = false
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * 활성 통화 변경
     */
    fun setActiveCurrency(currency: SupportedCurrency) {
        _activeCurrency.value = currency

        // 갭 플래그가 있으면 REST 복구
        viewModelScope.launch {
            val needsRefresh = stateMutex.withLock {
                currenciesNeedingRefresh.remove(currency.code)
            }
            if (needsRefresh) {
                loadGraph(currency)
            }
        }
    }

    /**
     * 그래프 소스 토글
     */
    fun toggleSource(source: GraphSource) {
        val current = _selectedSources.value.toMutableSet()
        if (current.contains(source)) {
            if (current.size > 1) {
                current.remove(source)
            }
        } else {
            current.add(source)
        }
        _selectedSources.value = current
    }

    /**
     * 그래프 데이터 로드
     */
    suspend fun loadGraph(currency: SupportedCurrency) {
        if (!isActive) return

        val currencyCode = currency.code

        // 캐시 유효성 검사 (실제 버킷 시간 기준)
        val shouldSkip = cacheMutex.withLock {
            val cached = graphCache[currencyCode]
            if (cached != null && cacheService.hasValidCache(cached)) {
                // 마지막 버킷이 15분 이내면 스킵 (WebSocket으로 실시간 업데이트 중)
                val lastBucketTs = getLastBucketTimestampUnsafe(currencyCode, GraphSource.INVESTING.code)
                if (lastBucketTs != null) {
                    val elapsed = System.currentTimeMillis() - (lastBucketTs * 1000L)
                    elapsed < GraphConfig.GAP_THRESHOLD_SEC * 1000L
                } else false
            } else false
        }
        if (shouldSkip) return

        // 쿨다운 체크 (2분, 통화별)
        val lastFetch = stateMutex.withLock {
            lastFullFetchTimeMap[currencyCode] ?: 0L
        }
        val cooldownElapsed = System.currentTimeMillis() - lastFetch
        if (cooldownElapsed < GraphConfig.FULL_FETCH_COOLDOWN_SEC * 1000L) {
            return
        }

        fetchFullGraphData(currencyCode)
    }

    /**
     * 특정 통화의 그래프 포인트 조회 (UI용)
     * Note: suspend 함수로 변경하여 Mutex 사용
     */
    suspend fun getGraphBuckets(currency: String, source: GraphSource): List<GraphBucket> {
        return cacheMutex.withLock {
            graphCache[currency]?.get(source.code)?.toList() ?: emptyList()
        }
    }

    /**
     * 캐시를 디스크에 저장 (백그라운드 진입 시)
     */
    fun saveCache() {
        viewModelScope.launch {
            val snapshot = cacheMutex.withLock {
                // 스냅샷 생성 (깊은 복사)
                graphCache.mapValues { (_, sourceMap) ->
                    sourceMap.mapValues { it.value.toList() }
                }
            }
            cacheService.saveAllGraphData(snapshot)
        }
    }

    // ============ Private Methods ============

    private fun setupWebSocketCallback() {
        webSocketService.onGraphBucketsReceived = { buckets ->
            handleWebSocketBuckets(buckets)
        }
    }

    private fun handleWebSocketBuckets(buckets: WebSocketGraphBuckets) {
        // 캐시 정렬/삭제 작업이 Main에서 수행되면 UI 버벅임 가능
        // Dispatchers.Default에서 처리
        viewModelScope.launch(Dispatchers.Default) {
            // REST 복구가 필요한 통화 수집 (중복 제거)
            val currenciesToFetch = mutableSetOf<String>()
            // 갭 플래그 설정이 필요한 통화 수집
            val currenciesToFlag = mutableListOf<String>()
            var didUpdate = false

            cacheMutex.withLock {
                for ((currency, sources) in buckets) {
                    for ((source, bucket) in sources) {
                        // 갭 감지
                        val lastTs = getLastBucketTimestampUnsafe(currency, source)
                        if (lastTs != null) {
                            val gap = bucket.bucketTs - lastTs
                            if (gap >= GraphConfig.GAP_THRESHOLD_SEC) {
                                if (currency == _activeCurrency.value.code) {
                                    // 활성 통화 → REST 복구 예약
                                    currenciesToFetch.add(currency)
                                } else {
                                    // 비활성 통화 → 플래그 예약
                                    currenciesToFlag.add(currency)
                                }
                                continue
                            }
                        }

                        // 버킷 추가 (Mutex 내부에서 호출)
                        updateGraphCacheUnsafe(bucket, currency, source)
                        didUpdate = true
                    }
                }
            }

            if (didUpdate) {
                markGraphUpdated()
            }

            // 갭 플래그 설정 (stateMutex 보호)
            if (currenciesToFlag.isNotEmpty()) {
                stateMutex.withLock {
                    currenciesNeedingRefresh.addAll(currenciesToFlag)
                }
            }

            // REST 복구 실행
            for (currency in currenciesToFetch) {
                fetchFullGraphData(currency)
            }
        }
    }

    /**
     * 캐시 업데이트 (Mutex 내부에서 호출 - Unsafe)
     */
    private fun updateGraphCacheUnsafe(wsBucket: WebSocketGraphBucket, currency: String, source: String) {
        val bucket = wsBucket.toGraphBucket()

        val sourceMap = graphCache.getOrPut(currency) { mutableMapOf() }
        val buckets = sourceMap.getOrPut(source) { mutableListOf() }

        // 중복 제거 후 추가
        buckets.removeAll { it.bucketTs == bucket.bucketTs }
        buckets.add(bucket)

        // 정렬 및 개수 제한
        buckets.sortBy { it.bucketTs }
        while (buckets.size > GraphConfig.MAX_BUCKETS) {
            buckets.removeAt(0)
        }
    }

    /**
     * 마지막 버킷 타임스탬프 조회 (Mutex 내부에서 호출 - Unsafe)
     */
    private fun getLastBucketTimestampUnsafe(currency: String, source: String): Int? {
        return graphCache[currency]?.get(source)?.maxByOrNull { it.bucketTs }?.bucketTs
    }

    private suspend fun fetchFullGraphData(currency: String) {
        // 중복 요청 방지 (stateMutex 보호)
        val alreadyInFlight = stateMutex.withLock {
            if (inFlightCurrencies.contains(currency)) {
                true
            } else {
                inFlightCurrencies.add(currency)
                false
            }
        }
        if (alreadyInFlight) return

        // MutableStateFlow는 thread-safe이므로 withContext(Main) 불필요
        _isLoading.value = true

        try {
            repository.getGraph(currency)
                .onSuccess { sourceData ->
                    // 메모리 캐시 업데이트 (cacheMutex 보호)
                    cacheMutex.withLock {
                        graphCache[currency] = sourceData.mapValues { it.value.toMutableList() }.toMutableMap()
                    }
                    stateMutex.withLock {
                        lastFullFetchTimeMap[currency] = System.currentTimeMillis()
                    }

                    // 디스크 캐시 저장
                    cacheService.saveGraphData(sourceData, currency)
                    markGraphUpdated()
                }
                .onFailure { error ->
                    val hasCache = cacheMutex.withLock {
                        graphCache[currency]?.isNotEmpty() == true
                    }
                    val isActiveCurrency = currency == _activeCurrency.value.code
                    Log.e(TAG, "fetchFullGraphData 실패: currency=$currency, " +
                        "isActiveCurrency=$isActiveCurrency, hasCache=$hasCache, " +
                        "error=${error.javaClass.simpleName}: ${error.message}")
                }
        } finally {
            val stillLoading = stateMutex.withLock {
                inFlightCurrencies.remove(currency)
                inFlightCurrencies.isNotEmpty()
            }
            _isLoading.value = stillLoading
        }
    }

    private fun setupPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(GraphConfig.REFRESH_INTERVAL_SEC * 1000L)
                if (isActive) {
                    // 활성 통화만 갭 체크
                    loadGraph(_activeCurrency.value)
                }
            }
        }
    }

    private fun markGraphUpdated() {
        _graphVersion.update { it + 1 }
    }

    override fun onCleared() {
        super.onCleared()
        webSocketService.onGraphBucketsReceived = null
        periodicRefreshJob?.cancel()
    }

    companion object {
        private const val TAG = "GraphViewModel"
    }
}
