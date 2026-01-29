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

        // 권한 복구 시 토큰 재등록
        if (!previous && current && _state.value.settings.isNotEmpty()) {
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
        if (_state.value is AlertState.Loading) {
            needsRefresh = true
        } else {
            loadSettings()
        }
    }

    fun loadSettings() {
        if (_state.value is AlertState.Loading) return
        // 레이스 컨디션 방지: 코루틴 시작 전 즉시 Loading 상태로 전환
        _state.value = AlertState.Loading
        viewModelScope.launch {
            var shouldRegisterPush = false
            repository.getSettings().fold(
                onSuccess = { settings ->
                    _state.value = AlertState.Loaded(alertSettings = settings.sortedByCreatedAt())
                    lastRefreshAt = System.currentTimeMillis()
                    shouldRegisterPush = settings.isNotEmpty() && checkNotificationPermission()
                },
                onFailure = { e ->
                    _state.value = AlertState.Error(e.message ?: "알 수 없는 오류")
                }
            )
            if (shouldRegisterPush) {
                pushNotificationManager.shouldRegisterForPush = true
                pushNotificationManager.registerIfNeeded(subscriptionManager.isPremium.value)
            }
            // iOS 패리티: 로딩 중 대기한 새로고침 요청 1회 재시도
            if (needsRefresh) {
                needsRefresh = false
                loadSettings()
            }
        }
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
                    val current = _state.value.settings.toMutableList()
                    current.add(0, created)
                    _state.value = AlertState.Loaded(alertSettings = current)
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
                    onError(e.message ?: "수정 실패")
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
