package com.jay.fxi.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.local.CacheService
import com.jay.fxi.data.remote.WebSocketService
import com.jay.fxi.data.remote.dto.WebSocketGraphBucket
import com.jay.fxi.data.remote.dto.WebSocketGraphBuckets
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.domain.model.GraphSourceData
import com.jay.fxi.domain.model.MutableGraphCache
import com.jay.fxi.domain.model.MutablePeriodGraphCache
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.domain.repository.ExchangeRateRepository
import com.jay.fxi.util.GraphConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * 그래프 데이터 ViewModel
 *
 * 1d는 WebSocket 실시간 + REST 갭 복구를 사용하고,
 * 1w/3m/1y는 기간별 REST + 캐시 freshness로 관리한다.
 */
@HiltViewModel
class GraphViewModel @Inject constructor(
    private val repository: ExchangeRateRepository,
    private val webSocketService: WebSocketService,
    private val cacheService: CacheService
) : ViewModel() {

    // ============ 상태 ============

    /**
     * 1d 캐시: [currency: [source: [GraphBucket]]]
     */
    private val graphCache: MutableGraphCache = mutableMapOf()

    /**
     * 장기 구간 캐시: [period: [currency: [source: [GraphBucket]]]]
     */
    private val periodCache: MutablePeriodGraphCache = mutableMapOf()

    private val cacheMutex = Mutex()
    private val stateMutex = Mutex()

    private val _activeCurrency = MutableStateFlow(SupportedCurrency.USD_KRW)
    val activeCurrency: StateFlow<SupportedCurrency> = _activeCurrency.asStateFlow()

    private val _activePeriod = MutableStateFlow(GraphPeriod.ONE_DAY)
    val activePeriod: StateFlow<GraphPeriod> = _activePeriod.asStateFlow()

    private val _selectedSources = MutableStateFlow(setOf(GraphSource.INVESTING))
    val selectedSources: StateFlow<Set<GraphSource>> = _selectedSources.asStateFlow()

    private val _dxyVisible = MutableStateFlow(false)
    val dxyVisible: StateFlow<Boolean> = _dxyVisible.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _graphVersion = MutableStateFlow(0L)
    val graphVersion: StateFlow<Long> = _graphVersion.asStateFlow()

    private val _isGraphFullscreen = MutableStateFlow(false)
    val isGraphFullscreen: StateFlow<Boolean> = _isGraphFullscreen.asStateFlow()

    private val _isRatesFullscreen = MutableStateFlow(false)
    val isRatesFullscreen: StateFlow<Boolean> = _isRatesFullscreen.asStateFlow()

    fun setGraphFullscreen(value: Boolean) {
        _isGraphFullscreen.value = value
    }

    fun setRatesFullscreen(value: Boolean) {
        _isRatesFullscreen.value = value
    }

    private var isActive = false
    private var periodicRefreshJob: Job? = null

    /**
     * 1d 강제 새로고침이 필요한 통화
     */
    private val currenciesNeedingRefresh = mutableSetOf<String>()

    /**
     * 중복 요청 방지 키
     * - 1d: "1d:usd-krw"
     * - 장기: "1w:usd-krw"
     */
    private val inFlightKeys = mutableSetOf<String>()

    /**
     * 1d 쿨다운 관리
     */
    private val lastFullFetchTimeMap = mutableMapOf<String, Long>()

    /**
     * 장기 구간 freshness
     * key = "{period}:{currency}"
     */
    private val periodCacheFreshnessAt = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch {
            val diskCache = cacheService.loadAllGraphData()
            cacheMutex.withLock {
                diskCache.forEach { (currency, sourceData) ->
                    graphCache[currency] = sourceData
                        .mapValues { (_, buckets) -> buckets.toMutableList() }
                        .toMutableMap()
                }
            }
            if (diskCache.isNotEmpty()) {
                markGraphUpdated()
            }
        }

        setupWebSocketCallback()
    }

    // ============ Public API ============

    fun start() {
        if (isActive) return
        isActive = true
        setupPeriodicRefresh()
    }

    fun stop() {
        isActive = false
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    fun setActiveCurrency(currency: SupportedCurrency) {
        _activeCurrency.value = currency

        viewModelScope.launch {
            val shouldForceRefresh = stateMutex.withLock {
                if (_activePeriod.value == GraphPeriod.ONE_DAY) {
                    currenciesNeedingRefresh.remove(currency.code)
                } else {
                    false
                }
            }
            if (shouldForceRefresh) {
                loadGraphForUserSelection(currency, forceRefresh = true)
            }
        }
    }

    fun setActivePeriod(period: GraphPeriod) {
        val previousPeriod = _activePeriod.value
        _activePeriod.value = period
        if (previousPeriod != period) {
            viewModelScope.launch {
                val hasCachedData = cacheMutex.withLock {
                    hasCachedDataUnsafe(_activeCurrency.value.code, period)
                }
                if (!hasCachedData) {
                    _isLoading.value = true
                }
            }
        }
    }

    fun toggleSource(source: GraphSource) {
        if (source !in GraphSource.realtimeSources) return

        val current = _selectedSources.value.toMutableSet()
        if (source in current) {
            if (current.size > 1) {
                current.remove(source)
            }
        } else {
            current.add(source)
        }
        _selectedSources.value = current
    }

    fun toggleDxy() {
        _dxyVisible.update { !it }
    }

    suspend fun loadGraph(currency: SupportedCurrency) {
        loadGraphForUserSelection(currency)
    }

    suspend fun loadGraphForUserSelection(
        currency: SupportedCurrency,
        period: GraphPeriod = _activePeriod.value,
        forceRefresh: Boolean = false
    ) {
        if (!isActive) return
        if (period == GraphPeriod.ONE_DAY) {
            loadRealtimeGraph(currency.code, forceRefresh)
        } else {
            loadPeriodGraph(currency.code, period, forceRefresh)
        }
    }

    suspend fun getGraphBuckets(currency: String, source: GraphSource): List<GraphBucket> {
        return cacheMutex.withLock {
            when (_activePeriod.value) {
                GraphPeriod.ONE_DAY -> graphCache[currency]?.get(source.code)?.toList() ?: emptyList()
                else -> periodCache[_activePeriod.value.code]
                    ?.get(currency)
                    ?.get(source.code)
                    ?.toList()
                    ?: emptyList()
            }
        }
    }

    fun latestDxyRate(): Double? {
        val oneDayDxy = graphCache[SupportedCurrency.USD_KRW.code]
            ?.get(GraphSource.DXY.code)
            ?.maxByOrNull { it.bucketTs }
            ?.close
        if (oneDayDxy != null) return oneDayDxy

        val period = _activePeriod.value
        if (period == GraphPeriod.ONE_DAY) return null
        if (!isPeriodGraphFresh(SupportedCurrency.USD_KRW, period)) return null

        return periodCache[period.code]
            ?.get(SupportedCurrency.USD_KRW.code)
            ?.get(GraphSource.DXY.code)
            ?.maxByOrNull { it.bucketTs }
            ?.close
    }

    fun isPeriodGraphFresh(
        currency: SupportedCurrency,
        period: GraphPeriod = _activePeriod.value
    ): Boolean {
        if (period == GraphPeriod.ONE_DAY) return true

        val cacheKey = periodCacheKey(period, currency.code)
        val freshnessMs = periodCacheFreshnessAt[cacheKey] ?: return false
        val hasData = periodCache[period.code]?.containsKey(currency.code) == true
        return hasData && (System.currentTimeMillis() - freshnessMs) < GraphConfig.cacheTtlMs(period)
    }

    fun saveCache() {
        viewModelScope.launch {
            val snapshot = cacheMutex.withLock {
                graphCache.mapValues { (_, sourceMap) ->
                    sourceMap.mapValues { (_, buckets) -> buckets.toList() }
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
        viewModelScope.launch(Dispatchers.Default) {
            val currenciesToFetch = mutableSetOf<String>()
            val currenciesToFlag = mutableSetOf<String>()
            var didUpdate = false

            cacheMutex.withLock {
                for ((currency, sources) in buckets) {
                    for ((source, bucket) in sources) {
                        val lastTs = getLastBucketTimestampUnsafe(currency, source)
                        if (lastTs != null) {
                            val gap = bucket.bucketTs - lastTs
                            if (gap >= GraphConfig.gapThresholdSec(GraphPeriod.ONE_DAY)) {
                                if (currency == _activeCurrency.value.code) {
                                    currenciesToFetch += currency
                                } else {
                                    currenciesToFlag += currency
                                }
                                continue
                            }
                        }

                        updateRealtimeGraphCacheUnsafe(bucket, currency, source)
                        didUpdate = true
                    }
                }
            }

            if (didUpdate) {
                markGraphUpdated()
            }

            if (currenciesToFlag.isNotEmpty()) {
                stateMutex.withLock {
                    currenciesNeedingRefresh.addAll(currenciesToFlag)
                }
            }

            currenciesToFetch.forEach { currency ->
                fetchRealtimeGraphData(currency, silent = true)
            }
        }
    }

    private suspend fun loadRealtimeGraph(currency: String, forceRefresh: Boolean) {
        val shouldSkip = cacheMutex.withLock {
            val cached = graphCache[currency]
            if (cached != null && cacheService.hasValidCache(cached)) {
                val lastBucketTs = getLastBucketTimestampUnsafe(currency, GraphSource.INVESTING.code)
                if (lastBucketTs != null) {
                    val elapsed = System.currentTimeMillis() - (lastBucketTs * 1_000L)
                    !forceRefresh && elapsed < GraphConfig.gapThresholdSec(GraphPeriod.ONE_DAY) * 1_000L
                } else {
                    false
                }
            } else {
                false
            }
        }
        if (shouldSkip) return

        if (!forceRefresh) {
            val lastFetch = stateMutex.withLock {
                lastFullFetchTimeMap[currency] ?: 0L
            }
            if (System.currentTimeMillis() - lastFetch < GraphConfig.FULL_FETCH_COOLDOWN_SEC * 1_000L) {
                return
            }
        }

        fetchRealtimeGraphData(currency, silent = false)
    }

    private suspend fun loadPeriodGraph(
        currency: String,
        period: GraphPeriod,
        forceRefresh: Boolean
    ) {
        val cacheKey = periodCacheKey(period, currency)

        if (!forceRefresh) {
            val hasFreshMemoryCache = cacheMutex.withLock {
                val freshnessMs = periodCacheFreshnessAt[cacheKey]
                val cached = periodCache[period.code]?.get(currency)
                cached != null &&
                    freshnessMs != null &&
                    (System.currentTimeMillis() - freshnessMs) < GraphConfig.cacheTtlMs(period)
            }
            if (hasFreshMemoryCache) {
                _isLoading.value = false
                return
            }
        }

        val diskEntry = cacheService.loadPeriodGraphData(currency, period)
        if (!forceRefresh && diskEntry != null) {
            cacheMutex.withLock {
                periodCache.getOrPut(period.code) { mutableMapOf() }[currency] = diskEntry.sources
                periodCacheFreshnessAt[cacheKey] = diskEntry.freshnessDate.toEpochMilliseconds()
            }
            _isLoading.value = false
            markGraphUpdated()
            return
        }

        fetchPeriodGraphData(currency, period)
    }

    private fun updateRealtimeGraphCacheUnsafe(
        wsBucket: WebSocketGraphBucket,
        currency: String,
        source: String
    ) {
        val bucket = wsBucket.toGraphBucket()
        val sourceMap = graphCache.getOrPut(currency) { mutableMapOf() }
        val buckets = sourceMap.getOrPut(source) { mutableListOf() }

        buckets.removeAll { it.bucketTs == bucket.bucketTs }
        buckets.add(bucket)
        buckets.sortBy { it.bucketTs }

        while (buckets.size > GraphConfig.MAX_BUCKETS) {
            buckets.removeAt(0)
        }
    }

    private fun getLastBucketTimestampUnsafe(currency: String, source: String): Int? {
        return graphCache[currency]?.get(source)?.maxByOrNull { it.bucketTs }?.bucketTs
    }

    private suspend fun fetchRealtimeGraphData(currency: String, silent: Boolean) {
        val key = periodCacheKey(GraphPeriod.ONE_DAY, currency)
        val alreadyInFlight = stateMutex.withLock {
            if (key in inFlightKeys) {
                true
            } else {
                inFlightKeys += key
                false
            }
        }
        if (alreadyInFlight) return

        if (!silent) {
            _isLoading.value = true
        }

        try {
            repository.getGraph(currency, GraphPeriod.ONE_DAY)
                .onSuccess { result ->
                    cacheMutex.withLock {
                        graphCache[currency] = result.sources
                            .mapValues { (_, buckets) -> buckets.toMutableList() }
                            .toMutableMap()
                    }
                    stateMutex.withLock {
                        lastFullFetchTimeMap[currency] = System.currentTimeMillis()
                        currenciesNeedingRefresh.remove(currency)
                    }

                    cacheService.saveGraphData(
                        data = result.sources,
                        currency = currency,
                        period = GraphPeriod.ONE_DAY
                    )
                    markGraphUpdated()
                }
                .onFailure { error ->
                    val hasCache = cacheMutex.withLock { graphCache[currency]?.isNotEmpty() == true }
                    Log.e(
                        TAG,
                        "fetchRealtimeGraphData failed: currency=$currency, hasCache=$hasCache, error=${error.message}"
                    )
                }
        } finally {
            stateMutex.withLock {
                inFlightKeys.remove(key)
            }
            _isLoading.value = false
        }
    }

    private suspend fun fetchPeriodGraphData(currency: String, period: GraphPeriod) {
        val key = periodCacheKey(period, currency)
        val alreadyInFlight = stateMutex.withLock {
            if (key in inFlightKeys) {
                true
            } else {
                inFlightKeys += key
                false
            }
        }
        if (alreadyInFlight) return

        _isLoading.value = true

        try {
            repository.getGraph(currency, period)
                .onSuccess { result ->
                    val freshnessDate = result.asOf ?: Clock.System.now()
                    cacheMutex.withLock {
                        periodCache.getOrPut(period.code) { mutableMapOf() }[currency] = result.sources
                        periodCacheFreshnessAt[key] = freshnessDate.toEpochMilliseconds()
                    }
                    cacheService.savePeriodGraphData(
                        data = result.sources,
                        currency = currency,
                        period = period,
                        freshnessDate = freshnessDate
                    )
                    markGraphUpdated()
                }
                .onFailure { error ->
                    Log.e(
                        TAG,
                        "fetchPeriodGraphData failed: currency=$currency, period=${period.code}, error=${error.message}"
                    )
                }
        } finally {
            stateMutex.withLock {
                inFlightKeys.remove(key)
            }
            _isLoading.value = false
        }
    }

    private fun setupPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(GraphConfig.REFRESH_INTERVAL_SEC * 1_000L)
                if (isActive && _activePeriod.value == GraphPeriod.ONE_DAY) {
                    loadRealtimeGraph(_activeCurrency.value.code, forceRefresh = false)
                }
            }
        }
    }

    private fun hasCachedDataUnsafe(currency: String, period: GraphPeriod): Boolean {
        return if (period == GraphPeriod.ONE_DAY) {
            graphCache[currency] != null
        } else {
            periodCache[period.code]?.get(currency) != null
        }
    }

    private fun periodCacheKey(period: GraphPeriod, currency: String): String {
        return "${period.code}:$currency"
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
