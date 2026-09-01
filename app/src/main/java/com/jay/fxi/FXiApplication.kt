package com.jay.fxi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.service.FXiMessagingService
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import dagger.hilt.android.HiltAndroidApp

internal fun shouldStartAppOwnedServices(
    releaseAdmissionOpen: Boolean,
    benchmarkNoData: Boolean
): Boolean = releaseAdmissionOpen && !benchmarkNoData

internal fun shouldEnableCrashlytics(
    debug: Boolean,
    benchmarkNoData: Boolean,
    releaseAdmissionOpen: Boolean
): Boolean = !debug && shouldStartAppOwnedServices(releaseAdmissionOpen, benchmarkNoData)

@HiltAndroidApp
class FXiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val releaseAdmissionOpen = ReleaseAdmission.isOpen
        val appOwnedServicesOpen = shouldStartAppOwnedServices(
            releaseAdmissionOpen = releaseAdmissionOpen,
            benchmarkNoData = BuildConfig.BENCHMARK_NO_DATA_MODE
        )
        if (!appOwnedServicesOpen) {
            Log.i(TAG, "D24-OFF/no-data process: app-owned services remain disabled.")
            return
        }

        // Firebase's own provider/transport is outside the app data-plane zero assertion,
        // but explicit collection is still opened only for an admitted application.
        FirebaseApp.initializeApp(this)
        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(
                shouldEnableCrashlytics(
                    debug = BuildConfig.DEBUG,
                    benchmarkNoData = BuildConfig.BENCHMARK_NO_DATA_MODE,
                    releaseAdmissionOpen = releaseAdmissionOpen
                )
            )
        }

        // App-owned local notification surface.
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

        // App-owned subscription transport.
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
