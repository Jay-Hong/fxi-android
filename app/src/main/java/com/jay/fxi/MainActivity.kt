package com.jay.fxi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertEventBus
import com.jay.fxi.service.FXiMessagingService
import com.jay.fxi.subscription.SubscriptionManager
import com.jay.fxi.ui.screen.RootScreen
import com.jay.fxi.ui.theme.FXiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var subscriptionManagerProvider: Provider<SubscriptionManager>

    @Inject
    lateinit var alertEventBusProvider: Provider<AlertEventBus>

    // 새 Activity 인스턴스에서 pending 이벤트 저장 (Compose 준비 후 처리)
    private val _pendingAlertEvent = MutableStateFlow<AlertEvent?>(null)
    val pendingAlertEvent: StateFlow<AlertEvent?> = _pendingAlertEvent.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // 알림 탭으로 새 Activity를 열 때 pending 이벤트 저장 (회전/복원 제외)
        if (ReleaseAdmission.isOpen && savedInstanceState == null) {
            parsePendingAlertEvent(intent)
        }

        setContent {
            FXiTheme {
                RootScreen(
                    subscriptionManagerProvider = subscriptionManagerProvider,
                    pendingAlertEvent = pendingAlertEvent,
                    onPendingAlertEventConsumed = { _pendingAlertEvent.value = null },
                    alertEventBusProvider = alertEventBusProvider
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!ReleaseAdmission.isOpen) return
        // 앱이 이미 실행 중일 때 알림 탭 시 즉시 emit (구독자 준비됨)
        handleNotificationIntent(intent)
    }

    /**
     * 새 Activity 생성 시 Intent에서 AlertEvent 파싱 (Compose 준비 후 처리)
     */
    private fun parsePendingAlertEvent(intent: Intent?) {
        val event = parseAlertEventFromIntent(intent) ?: return
        _pendingAlertEvent.value = event
        clearNotificationExtras(intent)
    }

    /**
     * 앱 실행 중 알림 탭 Intent 처리 (즉시 emit)
     */
    private fun handleNotificationIntent(intent: Intent?) {
        val event = parseAlertEventFromIntent(intent) ?: return
        alertEventBusProvider.get().emit(event)
        clearNotificationExtras(intent)
    }

    private fun parseAlertEventFromIntent(intent: Intent?): AlertEvent? {
        if (intent == null) return null

        // 1. FXiMessagingService가 설정한 커스텀 extra (포그라운드 알림)
        // 2. 서버 FCM data payload 직접 전달 (백그라운드/종료 상태 시스템 알림)
        val type = intent.getStringExtra(FXiMessagingService.EXTRA_NOTIFICATION_TYPE)
            ?: intent.getStringExtra("type")
        if (type != "rate_alert") return null

        // Int extra (커스텀) 또는 String extra (서버 payload) 모두 처리
        val settingId = intent.getIntExtra(FXiMessagingService.EXTRA_SETTING_ID, -1)
            .takeIf { it != -1 }
            ?: intent.getStringExtra(FXiMessagingService.EXTRA_SETTING_ID)?.toIntOrNull()
            ?: intent.getStringExtra("setting_id")?.toIntOrNull()

        return if (settingId != null) {
            AlertEvent.SettingTriggered(settingId)
        } else {
            AlertEvent.RefreshNeeded
        }
    }

    private fun clearNotificationExtras(intent: Intent?) {
        // 커스텀 extras (FXiMessagingService)
        intent?.removeExtra(FXiMessagingService.EXTRA_NOTIFICATION_TYPE)
        intent?.removeExtra(FXiMessagingService.EXTRA_SETTING_ID)
        // 서버 FCM data payload 키
        intent?.removeExtra("type")
        intent?.removeExtra("setting_id")
    }
}
