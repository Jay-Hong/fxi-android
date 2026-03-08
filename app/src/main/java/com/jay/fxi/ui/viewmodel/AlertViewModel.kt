package com.jay.fxi.ui.viewmodel

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.local.CacheService
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.data.repository.AlertNotFoundException
import com.jay.fxi.data.repository.AlertRepository
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.sortedByCreatedAt
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertEventBus
import com.jay.fxi.service.FXiMessagingService
import com.jay.fxi.service.PushNotificationManager
import com.jay.fxi.subscription.SubscriptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AlertState {
    data object Idle : AlertState()
    data object Loading : AlertState()
    data class Loaded(val alertSettings: List<AlertSetting>) : AlertState()
    data class Error(val message: String) : AlertState()

    val settings: List<AlertSetting>
        get() = (this as? Loaded)?.alertSettings ?: emptyList()
}

@HiltViewModel
class AlertViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlertRepository,
    private val alertEventBus: AlertEventBus,
    private val pushNotificationManager: PushNotificationManager,
    private val subscriptionManager: SubscriptionManager,
    private val cacheService: CacheService
) : ViewModel() {

    private val _state = MutableStateFlow<AlertState>(AlertState.Idle)
    val state: StateFlow<AlertState> = _state.asStateFlow()

    private val _isOperationInProgress = MutableStateFlow(false)
    val isOperationInProgress: StateFlow<Boolean> = _isOperationInProgress.asStateFlow()

    private var lastRefreshAt: Long = 0L
    private val refreshDebounceMs = 30_000L
    private var needsRefresh = false  // iOS 패리티: 로딩 중 새로고침 요청 대기
    private var isLoadingSettings = false

    // ============ 수동 새로고침 상태 ============

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _canManualRefresh = MutableStateFlow(true)
    val canManualRefresh: StateFlow<Boolean> = _canManualRefresh.asStateFlow()

    // 쿨다운/백오프 상태
    private var nextManualRefreshAllowedAt: Long = 0L
    private var consecutiveFailures: Int = 0
    private val baseCooldownMs = 3_000L  // 3초

    // 수동 요청 체인 추적 (큐잉 재시도 컨텍스트 유지)
    private var manualOrigin = false

    // 쿨다운 타이머 Job (취소 가능하게 관리)
    private var cooldownJob: Job? = null

    private fun getNextCooldownMs(): Long {
        // 연속 실패 시 백오프: 5s → 10s → 20s (최대)
        return when (consecutiveFailures) {
            0 -> baseCooldownMs
            1 -> 5_000L
            2 -> 10_000L
            else -> 20_000L
        }
    }

    val canAddMore: Boolean
        get() = _state.value.settings.size < MAX_COUNT

    val remainingCount: Int
        get() = MAX_COUNT - _state.value.settings.size

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alert_prefs", Context.MODE_PRIVATE)

    private val _hasNotificationPermission = MutableStateFlow(checkNotificationPermission())
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

    private val _isPermissionDenied = MutableStateFlow(computeIsPermissionDenied())
    val isPermissionDenied: StateFlow<Boolean> = _isPermissionDenied.asStateFlow()

    private val hasRequestedPermission: Boolean
        get() = prefs.getBoolean(KEY_HAS_REQUESTED_PERMISSION, false)

    fun refreshPermissionState() {
        val previous = _hasNotificationPermission.value
        val current = checkNotificationPermission()
        _hasNotificationPermission.value = current
        _isPermissionDenied.value = computeIsPermissionDenied()

        // 권한 회수 시 푸시 등록 의도 플래그 초기화 (iOS 동작과 일치)
        // Android 12 이하에서도 시스템 설정으로 알림 끈 경우 처리
        if (!current) {
            pushNotificationManager.shouldRegisterForPush = false
        }

        // 권한 복구 시 토큰 재등록 (capability 등록: 알림 설정 유무와 무관)
        if (!previous && current) {
            viewModelScope.launch {
                pushNotificationManager.shouldRegisterForPush = true
                pushNotificationManager.registerIfNeeded(subscriptionManager.isPremium.value)
            }
        }
    }

    fun markPermissionRequested() {
        prefs.edit().putBoolean(KEY_HAS_REQUESTED_PERMISSION, true).apply()
        _isPermissionDenied.value = computeIsPermissionDenied()
    }

    private fun computeIsPermissionDenied(): Boolean =
        !checkNotificationPermission() && hasRequestedPermission

    private fun checkNotificationPermission(): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!runtimeGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

        val channelImportance = context
            .getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(FXiMessagingService.CHANNEL_RATE_ALERTS)
            ?.importance

        return channelImportance == null || channelImportance != NotificationManager.IMPORTANCE_NONE
    }

    init {
        viewModelScope.launch {
            alertEventBus.events.collect { event ->
                when (event) {
                    is AlertEvent.SettingTriggered -> handleSettingTriggered(event.settingId)
                    is AlertEvent.RefreshNeeded -> requestRefresh()
                }
            }
        }
    }

    /**
     * 새로고침 요청 (로딩 중이면 대기 큐에 추가)
     * AlertEvent.RefreshNeeded 등 외부 이벤트용
     */
    private fun requestRefresh() {
        if (isLoadingSettings) {
            needsRefresh = true
        } else {
            loadSettings()
        }
    }

    fun loadSettings(isManualRefresh: Boolean = false) {
        if (isLoadingSettings) {
            needsRefresh = true
            // 연타 시에도 스피너 유지 + 수동 컨텍스트 기록
            if (isManualRefresh) {
                manualOrigin = true
                _isRefreshing.value = true
            }
            return
        }

        // 새 요청 시작 시 수동 컨텍스트 설정
        if (isManualRefresh) {
            manualOrigin = true
        }

        val hadLoadedState = _state.value is AlertState.Loaded
        isLoadingSettings = true

        // 수동 새로고침일 때만 isRefreshing 활성화
        if (isManualRefresh) {
            _isRefreshing.value = true
        }

        // 이미 목록이 보이는 상태에서는 로딩 UI로 전환하지 않아 스크롤 점프를 방지한다.
        if (!hadLoadedState) {
            _state.value = AlertState.Loading
        }

        viewModelScope.launch {
            var shouldRegisterPush = false
            var shouldRetry = false
            var isSuccess = false
            try {
                repository.getSettings().fold(
                    onSuccess = { settings ->
                        val newState = AlertState.Loaded(alertSettings = settings.sortedByCreatedAt())
                        if (_state.value != newState) {
                            _state.value = newState
                        }
                        lastRefreshAt = System.currentTimeMillis()
                        shouldRegisterPush = checkNotificationPermission()
                        isSuccess = true
                    },
                    onFailure = { e ->
                        // 기존 목록이 있으면 유지하고, 최초 로드 실패일 때만 에러 화면 표시
                        if (!hadLoadedState) {
                            _state.value = AlertState.Error(e.message ?: "알 수 없는 오류")
                        }
                    }
                )
                if (shouldRegisterPush) {
                    pushNotificationManager.shouldRegisterForPush = true
                    pushNotificationManager.registerIfNeeded(subscriptionManager.isPremium.value)
                }

                // iOS 패리티: 로딩 중 대기한 새로고침 요청 1회 재시도
                shouldRetry = needsRefresh
            } finally {
                needsRefresh = false
                isLoadingSettings = false
                // 재시도 예정이면 스피너 유지 (큐잉 UX 버그 수정)
                if (!shouldRetry) {
                    _isRefreshing.value = false
                }
            }

            // 수동 요청 체인이면 쿨다운/백오프 업데이트 (체인 종료 시에만)
            if (manualOrigin && !shouldRetry) {
                if (isSuccess) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                }
                // 성공/실패 모두에서 다음 허용 시각 갱신 (실패 백오프 동작 보장)
                val cooldownMs = getNextCooldownMs()
                nextManualRefreshAllowedAt = System.currentTimeMillis() + cooldownMs
                manualOrigin = false  // 체인 종료, 컨텍스트 리셋

                // 버튼 비활성화 → 쿨다운 후 자동 복구
                _canManualRefresh.value = false
                cooldownJob?.cancel()  // 이전 타이머 취소
                cooldownJob = viewModelScope.launch {
                    delay(cooldownMs)
                    _canManualRefresh.value = true
                }
            }

            if (shouldRetry) loadSettings()  // manualOrigin 유지됨 (재시도 컨텍스트 전달)
        }
    }

    /**
     * 수동 새로고침 (UI에서 호출)
     */
    fun refreshNow() {
        // 2중 안전장치: canManualRefresh + 시간 기반 체크 (타이머 누락/상태 불일치 방어)
        if (!_canManualRefresh.value) return
        if (System.currentTimeMillis() < nextManualRefreshAllowedAt) return
        loadSettings(isManualRefresh = true)
    }

    fun loadSettingsIfNeeded() {
        val elapsed = System.currentTimeMillis() - lastRefreshAt
        if (_state.value is AlertState.Idle || elapsed > refreshDebounceMs) {
            loadSettings()
        }
    }

    /**
     * 포그라운드 복귀 시 새로고침 (iOS refreshOnForeground와 동일)
     * - loading 상태: 플래그 설정 후 완료 시 1회 재시도
     * - idle/error 상태: 즉시 로드 (디바운싱 무시)
     * - loaded 상태: 30초 디바운싱 적용
     */
    fun refreshOnForeground() {
        if (isLoadingSettings) {
            // iOS 패리티: 로딩 완료 후 1회 재시도
            needsRefresh = true
            return
        }
        when (_state.value) {
            is AlertState.Loading -> {
                // iOS 패리티: 로딩 완료 후 1회 재시도
                needsRefresh = true
            }
            is AlertState.Idle, is AlertState.Error -> loadSettings() // 즉시 로드
            is AlertState.Loaded -> {
                // 30초 디바운싱 적용
                val elapsed = System.currentTimeMillis() - lastRefreshAt
                if (elapsed > refreshDebounceMs) {
                    loadSettings()
                }
            }
        }
    }

    fun createSetting(
        bank: String,
        currency: String,
        condition: AlertCondition,
        threshold: Double,
        isPremium: Boolean,
        onPermissionNeeded: () -> Unit,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!canAddMore) {
            onError("최대 ${MAX_COUNT}개까지 설정할 수 있습니다")
            return
        }

        // 첫 알림 생성 시 권한 확인
        if (!_hasNotificationPermission.value) {
            onPermissionNeeded()
            return
        }

        viewModelScope.launch {
            _isOperationInProgress.value = true
            // 권한 승인됨 → 푸시 등록
            pushNotificationManager.shouldRegisterForPush = true
            pushNotificationManager.registerIfNeeded(isPremium)

            val request = AlertSettingRequest(
                bank = bank,
                currency = currency,
                condition = condition,
                threshold = threshold,
                isEnabled = true
            )
            repository.createSetting(request).fold(
                onSuccess = { created ->
                    val loaded = _state.value as? AlertState.Loaded
                    if (loaded != null) {
                        // sync_alerts 경쟁 상황에서도 ID 기준 upsert로 중복 표시 방지
                        val merged = loaded.alertSettings
                            .filterNot { it.id == created.id } + created
                        _state.value = AlertState.Loaded(alertSettings = merged.sortedByCreatedAt())
                    } else {
                        // 로딩/에러 상태에서는 서버 정본으로 재동기화
                        requestRefresh()
                    }
                    onSuccess()
                },
                onFailure = { e ->
                    onError(e.message ?: "생성 실패")
                }
            )
            _isOperationInProgress.value = false
        }
    }

    fun onPermissionResult(
        granted: Boolean,
        isPremium: Boolean,
        pendingCreate: (() -> Unit)?
    ) {
        markPermissionRequested()
        refreshPermissionState()
        if (granted) {
            viewModelScope.launch {
                pushNotificationManager.shouldRegisterForPush = true
                pushNotificationManager.registerIfNeeded(isPremium)
            }
            pendingCreate?.invoke()
        }
    }

    /**
     * 알림 설정 토글
     * - Note: 404는 "다른 기기에서 삭제됨"으로 처리 (로컬 제거 + 동기화)
     */
    fun toggleSetting(setting: AlertSetting, onError: (String) -> Unit = {}) {
        val newEnabled = !setting.isEnabled
        updateSettingLocally(setting.id) {
            it.isEnabled = newEnabled
            if (newEnabled) it.triggered = false
        }

        viewModelScope.launch {
            // partial update: is_enabled만 전송
            val request = AlertSettingUpdateRequest(isEnabled = newEnabled)
            repository.updateSettingPartial(setting.id, request).fold(
                onSuccess = { updated ->
                    // 서버 응답 전체 객체로 교체
                    replaceSettingLocally(updated)
                },
                onFailure = { e ->
                    // 404: 다른 기기에서 삭제됨 → 로컬 제거 + 동기화
                    if (e is AlertNotFoundException) {
                        removeSettingLocally(setting.id)
                        loadSettings() // 백그라운드 동기화
                        return@fold
                    }
                    // Rollback
                    updateSettingLocally(setting.id) {
                        it.isEnabled = setting.isEnabled
                        it.triggered = setting.triggered
                    }
                    onError(e.message ?: "변경 실패")
                }
            )
        }
    }

    /**
     * 알림 설정 수정
     * - Note: 404는 "다른 기기에서 삭제됨"으로 처리 (로컬 제거 + 동기화)
     */
    fun updateSetting(
        id: Int,
        bank: String,
        condition: AlertCondition,
        threshold: Double,
        isEnabled: Boolean,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        // 원본 설정 찾기
        val original = _state.value.settings.find { it.id == id }

        viewModelScope.launch {
            _isOperationInProgress.value = true

            // partial update: 변경된 필드만 전송 (currency는 서버에서 미지원)
            val request = if (original != null) {
                AlertSettingUpdateRequest(
                    bank = if (bank != original.bank) bank else null,
                    condition = if (condition != original.condition) condition else null,
                    threshold = if (kotlin.math.abs(threshold - original.threshold) >= 0.005) threshold else null,
                    isEnabled = if (isEnabled != original.isEnabled) isEnabled else null
                )
            } else {
                // 원본이 없으면 모든 필드 전송
                AlertSettingUpdateRequest(
                    bank = bank,
                    condition = condition,
                    threshold = threshold,
                    isEnabled = isEnabled
                )
            }

            repository.updateSettingPartial(id, request).fold(
                onSuccess = { updated ->
                    // 서버 응답의 전체 객체로 교체 (iOS와 동일)
                    replaceSettingLocally(updated)
                    onSuccess()
                },
                onFailure = { e ->
                    // 404: 다른 기기에서 삭제됨 → 로컬 제거 + 동기화
                    if (e is AlertNotFoundException) {
                        removeSettingLocally(id)
                        loadSettings() // 백그라운드 동기화
                        onError("다른 기기에서 삭제되었습니다")
                    } else {
                        onError(e.message ?: "수정 실패")
                    }
                }
            )
            _isOperationInProgress.value = false
        }
    }

    fun deleteSetting(setting: AlertSetting, onError: (String) -> Unit = {}) {
        val current = _state.value.settings.toMutableList()
        val index = current.indexOfFirst { it.id == setting.id }
        if (index < 0) return

        current.removeAt(index)
        _state.value = AlertState.Loaded(alertSettings = current)

        viewModelScope.launch {
            repository.deleteSetting(setting.id).onFailure { e ->
                // Rollback
                val rollback = _state.value.settings.toMutableList()
                rollback.add(index.coerceAtMost(rollback.size), setting)
                _state.value = AlertState.Loaded(alertSettings = rollback)
                onError(e.message ?: "삭제 실패")
            }
        }
    }

    fun reset() {
        _state.value = AlertState.Idle
        lastRefreshAt = 0L
        needsRefresh = false
        isLoadingSettings = false
        resetManualRefreshState()
    }

    /**
     * 수동 새로고침 상태 초기화 (reset/로그아웃/onCleared에서 호출)
     */
    private fun resetManualRefreshState() {
        manualOrigin = false
        consecutiveFailures = 0
        nextManualRefreshAllowedAt = 0L
        _canManualRefresh.value = true
        _isRefreshing.value = false
        cooldownJob?.cancel()
        cooldownJob = null
    }

    override fun onCleared() {
        super.onCleared()
        resetManualRefreshState()
    }

    /**
     * 특정 알림이 트리거됨 (푸시 수신 시 호출)
     * iOS handleTriggeredSetting과 동일:
     * - Loaded 상태가 아니면 전체 새로고침
     * - 해당 ID가 로컬에 없으면 전체 새로고침 (다른 기기에서 생성된 경우)
     * - 있으면 로컬 상태 업데이트
     */
    private fun handleSettingTriggered(settingId: Int) {
        // Loaded 상태가 아니면 전체 새로고침
        val loaded = _state.value as? AlertState.Loaded
        if (loaded == null) {
            requestRefresh()
            return
        }

        // 해당 ID가 로컬에 없으면 전체 새로고침 (다른 기기에서 생성된 알림)
        val exists = loaded.alertSettings.any { it.id == settingId }
        if (!exists) {
            requestRefresh()
            return
        }

        // 로컬 상태 업데이트
        updateSettingLocally(settingId) {
            it.triggered = true
            it.isEnabled = false
        }
    }

    private fun updateSettingLocally(id: Int, update: (AlertSetting) -> Unit) {
        val loaded = _state.value as? AlertState.Loaded ?: return
        val updated = loaded.settings.map { setting ->
            if (setting.id == id) setting.copy().also(update) else setting
        }
        _state.value = AlertState.Loaded(alertSettings = updated)
    }

    /**
     * 서버 응답 객체로 전체 교체 (iOS와 동일)
     */
    private fun replaceSettingLocally(newSetting: AlertSetting) {
        val loaded = _state.value as? AlertState.Loaded ?: return
        val updated = loaded.settings.map { setting ->
            if (setting.id == newSetting.id) newSetting else setting
        }
        _state.value = AlertState.Loaded(alertSettings = updated)
    }

    /**
     * 로컬에서 설정 제거 (다른 기기에서 삭제된 경우)
     */
    private fun removeSettingLocally(id: Int) {
        val loaded = _state.value as? AlertState.Loaded ?: return
        val filtered = loaded.settings.filterNot { it.id == id }
        _state.value = AlertState.Loaded(alertSettings = filtered)
    }

    fun hasDuplicate(
        bank: String,
        currency: String,
        condition: AlertCondition,
        threshold: Double,
        excludeId: Int? = null
    ): Boolean {
        return _state.value.settings.any { s ->
            s.bank == bank &&
                s.currency == currency &&
                s.condition == condition &&
                kotlin.math.abs(s.threshold - threshold) < 0.005 &&
                s.id != excludeId
        }
    }

    // ============ 마지막 선택 은행 (알림 추가용) ============

    /**
     * 통화별 마지막 선택 은행 로드
     */
    suspend fun loadLastSelectedBank(currency: String): Bank? {
        val bankCode = cacheService.loadLastSelectedBank(currency) ?: return null
        return Bank.fromCode(bankCode)
    }

    /**
     * 통화별 마지막 선택 은행 저장
     */
    fun saveLastSelectedBank(currency: String, bankCode: String) {
        viewModelScope.launch {
            cacheService.saveLastSelectedBank(currency, bankCode)
        }
    }

    companion object {
        private const val TAG = "AlertViewModel"
        private const val KEY_HAS_REQUESTED_PERMISSION = "has_requested_notification_permission"
        const val MAX_COUNT = 30
    }
}
