package com.jay.fxi.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.util.NewsConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import javax.inject.Inject

/**
 * 뉴스 데이터 관리 (iOS NewsViewModel 패리티)
 *
 * - 구독자: 캐시 즉시 표시 + SWR + adaptive polling (2분/5분)
 * - 비구독자: 캐시 즉시 표시 + 탭 진입 시 1회 fetch (5분 쿨다운)
 */
@HiltViewModel
class NewsViewModel @Inject constructor(
    private val newsRepository: NewsRepository
) : ViewModel() {

    private val _newsItems = MutableStateFlow<List<NewsItem>>(emptyList())
    val newsItems: StateFlow<List<NewsItem>> = _newsItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger.asStateFlow()

    private var isNewsTabActive = false
    private var lastFetchAt: Long? = null
    private var lastNewestArticleDate: Instant? = null
    private var adaptiveIntervalMs: Long = NewsConfig.POLLING_INTERVAL_LONG_MS

    private var pollingJob: Job? = null
    private var timeRefreshJob: Job? = null

    companion object {
        private const val TAG = "NewsViewModel"
    }

    init {
        loadCache()
    }

    // ── Tab Lifecycle ──

    fun onTabAppear(isPremium: Boolean) {
        isNewsTabActive = true

        if (_newsItems.value.isEmpty()) {
            loadCache()
        }
        if (_newsItems.value.isEmpty()) {
            _isLoading.value = true
        }

        startTimeRefreshTimer()

        viewModelScope.launch {
            fetchIfCooldownPassed(isPremium)
            if (isPremium) startPolling()
        }
    }

    fun onTabDisappear() {
        isNewsTabActive = false
        stopPolling()
        stopTimeRefreshTimer()
    }

    /**
     * 수동 재시도 (error 상태에서 "다시 시도" 버튼)
     */
    fun retry(isPremium: Boolean) {
        val cooldown = if (isPremium) NewsConfig.SUBSCRIBER_COOLDOWN_MS else NewsConfig.NON_SUBSCRIBER_COOLDOWN_MS
        if (!hasCooldownPassed(cooldown)) return
        viewModelScope.launch { fetchAndUpdate() }
    }

    /**
     * 포그라운드 복귀 시 호출 (구독자 전용)
     */
    fun onForegroundResume(isPremium: Boolean) {
        if (!isNewsTabActive || !isPremium) return
        viewModelScope.launch { fetchIfCooldownPassed(isPremium = true) }
    }

    /**
     * 상태 초기화 (로그아웃/구독만료 시)
     */
    fun reset() {
        _newsItems.value = emptyList()
        _error.value = null
        _isLoading.value = false
        lastFetchAt = null
        lastNewestArticleDate = null
        stopPolling()
        stopTimeRefreshTimer()
    }

    // ── Polling (구독자 전용) ──

    private fun startPolling() {
        stopPolling()
        pollingJob = viewModelScope.launch {
            while (isNewsTabActive) {
                delay(adaptiveIntervalMs)
                if (!isNewsTabActive) break
                fetchAndUpdate()
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ── Time Refresh Timer ──

    private fun startTimeRefreshTimer() {
        stopTimeRefreshTimer()
        timeRefreshJob = viewModelScope.launch {
            while (isNewsTabActive) {
                delay(NewsConfig.TIME_REFRESH_INTERVAL_MS)
                if (!isNewsTabActive) break
                _refreshTrigger.value += 1
            }
        }
    }

    private fun stopTimeRefreshTimer() {
        timeRefreshJob?.cancel()
        timeRefreshJob = null
    }

    // ── Fetch Logic ──

    private suspend fun fetchIfCooldownPassed(isPremium: Boolean) {
        val cooldown = if (isPremium) NewsConfig.SUBSCRIBER_COOLDOWN_MS else NewsConfig.NON_SUBSCRIBER_COOLDOWN_MS
        if (!hasCooldownPassed(cooldown)) {
            _isLoading.value = false
            return
        }
        fetchAndUpdate()
    }

    private suspend fun fetchAndUpdate() {
        try {
            val items = newsRepository.fetchNews()
            lastFetchAt = System.currentTimeMillis()
            _error.value = null

            // adaptive interval: 최신 기사 published_at 비교
            val newestDate = items.firstOrNull()?.publishedAt
            adaptiveIntervalMs = if (newestDate != null && newestDate != lastNewestArticleDate) {
                NewsConfig.POLLING_INTERVAL_SHORT_MS
            } else {
                NewsConfig.POLLING_INTERVAL_LONG_MS
            }
            lastNewestArticleDate = newestDate

            _newsItems.value = items
            _isLoading.value = false
            newsRepository.saveCache(items)
        } catch (e: Exception) {
            Log.w(TAG, "News fetch failed", e)
            _isLoading.value = false
            if (_newsItems.value.isEmpty()) {
                _error.value = e.message ?: "뉴스를 불러올 수 없습니다"
            }
        }
    }

    // ── Cache ──

    private fun loadCache() {
        viewModelScope.launch {
            newsRepository.loadCache()?.let { cached ->
                if (_newsItems.value.isEmpty()) {
                    _newsItems.value = cached
                }
            }
        }
    }

    // ── Helpers ──

    private fun hasCooldownPassed(cooldownMs: Long): Boolean {
        val last = lastFetchAt ?: return true
        return System.currentTimeMillis() - last >= cooldownMs
    }
}
