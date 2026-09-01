package com.jay.fxi.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jay.fxi.MainActivity
import com.jay.fxi.R
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.subscription.SubscriptionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class FXiMessagingService : FirebaseMessagingService() {

    @Inject lateinit var pushNotificationManagerProvider: Provider<PushNotificationManager>
    @Inject lateinit var subscriptionManagerProvider: Provider<SubscriptionManager>
    @Inject lateinit var alertEventBusProvider: Provider<AlertEventBus>

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    override fun onNewToken(token: String) {
        if (!ReleaseAdmission.isOpen) return
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received")
        serviceScope.launch {
            pushNotificationManagerProvider.get().onNewToken(
                token,
                subscriptionManagerProvider.get().isPremium.value
            )
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!ReleaseAdmission.isOpen) return
        super.onMessageReceived(message)
        Log.d(TAG, "Message received: type=${message.data["type"]}")

        when (message.data["type"]) {
            "rate_alert" -> {
                // 환율 알림: UI 업데이트 + 사용자 알림 표시
                val settingId = message.data["setting_id"]?.toIntOrNull()
                if (settingId != null) {
                    alertEventBusProvider.get().emit(AlertEvent.SettingTriggered(settingId))
                } else {
                    alertEventBusProvider.get().emit(AlertEvent.RefreshNeeded)
                }
                showRateAlertNotification(message)
            }
            "sync_alerts" -> {
                // 다중 기기 동기화: 사일런트 새로고침 (알림 표시 없음)
                Log.d(TAG, "Sync alerts requested from another device")
                alertEventBusProvider.get().emit(AlertEvent.RefreshNeeded)
            }
        }
    }

    /**
     * 알림 메시지에서 title/body 추출 (notification → data → default 폴백)
     */
    private fun parseNotificationContent(message: RemoteMessage): Pair<String, String> {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "환율 알림"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "목표 환율에 도달했습니다"
        return title to body
    }

    private fun showRateAlertNotification(message: RemoteMessage) {
        if (!ReleaseAdmission.isOpen) return
        val (title, body) = parseNotificationContent(message)

        val settingId = message.data["setting_id"]?.toIntOrNull()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, "rate_alert")
            if (settingId != null) {
                putExtra(EXTRA_SETTING_ID, settingId)
            }
        }
        // 고유 notificationId를 requestCode로 사용 (동일 알림 업데이트 보장)
        val notificationId = System.currentTimeMillis().toInt()
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_RATE_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    companion object {
        private const val TAG = "FXiMessaging"
        const val CHANNEL_RATE_ALERTS = "rate_alerts"
        const val EXTRA_NOTIFICATION_TYPE = "notification_type"
        const val EXTRA_SETTING_ID = "setting_id"
    }
}
