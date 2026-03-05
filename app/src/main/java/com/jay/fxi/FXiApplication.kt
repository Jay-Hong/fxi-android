package com.jay.fxi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.jay.fxi.service.FXiMessagingService
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FXiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Firebase 초기화
        FirebaseApp.initializeApp(this)

        // 2. Crashlytics 설정
        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        }

        // 3. 알림 채널 생성
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FXiMessagingService.CHANNEL_RATE_ALERTS,
                "환율 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "환율 목표 도달 시 알림"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 200, 250)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        // 4. RevenueCat 초기화
        val revenueCatApiKey = BuildConfig.REVENUECAT_API_KEY
        if (revenueCatApiKey.isNotBlank()) {
            if (BuildConfig.DEBUG) {
                Purchases.logLevel = LogLevel.DEBUG
            }
            Purchases.configure(
                PurchasesConfiguration.Builder(this, revenueCatApiKey).build()
            )
        } else {
            Log.w(TAG, "RevenueCat API key is missing. Subscription features will be disabled.")
        }
    }

    companion object {
        private const val TAG = "FXiApplication"
    }
}
