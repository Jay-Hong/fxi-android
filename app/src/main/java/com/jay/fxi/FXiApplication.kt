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
import com.jay.fxi.data.entitlements.AuthAccessBinder
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.data.local.LegacyStorePurge
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

internal fun shouldStartAppOwnedServices(
    releaseAdmissionOpen: Boolean,
    benchmarkNoData: Boolean
): Boolean = releaseAdmissionOpen && !benchmarkNoData

internal fun shouldEnableCrashlytics(
    debug: Boolean,
    benchmarkNoData: Boolean,
    releaseAdmissionOpen: Boolean
): Boolean = !debug && shouldStartAppOwnedServices(releaseAdmissionOpen, benchmarkNoData)

/**
 * Everything an admitted process starts, in one place that can be called without an `Application`.
 *
 * Written as a function taking the three starts rather than as three statements in `onCreate`,
 * because one of them is silent when it does not happen. A missing [bindAccess] or
 * [startFreeSnapshots] shows up as an app that never signs in or never refreshes; a missing
 * [purgeRetiredStores] looks exactly like a phone that had nothing left to delete. Grouping them
 * makes the quiet one fail the same way as the loud ones — all three run, or the list is wrong and
 * `FXiApplicationStartTest` says so.
 */
internal fun startAppOwnedServices(
    bindAccess: () -> Unit,
    startFreeSnapshots: () -> Unit,
    purgeRetiredStores: () -> Unit
) {
    // The single process-wide auth -> access-state funnel. Identity only: it binds the owner and
    // retries a journalled purge, and issues no entitlement query of its own.
    bindAccess()

    // The single owner of every free-snapshot refresh. Starting it only binds identity and arms
    // deadlines; nothing is fetched until a screen says which tab is on show.
    startFreeSnapshots()

    // Deletes the v1 stores this build has replaced (`ANDROID_V2_PLAN.md:801`). Nothing waits on it
    // and nothing v2 reads what it removes, so it goes last and answers to no one.
    purgeRetiredStores()
}

@HiltAndroidApp
class FXiApplication : Application() {

    /**
     * A [Provider], so the binder — and the `FirebaseAuth` it observes — is not constructed during
     * member injection, which runs before the explicit [FirebaseApp.initializeApp] below. A
     * non-admitted process never resolves it at all.
     */
    @Inject
    lateinit var authAccessBinder: Provider<AuthAccessBinder>

    /** A [Provider] for the same reason: it observes `FirebaseAuth`. */
    @Inject
    lateinit var freeSnapshotScheduler: Provider<FreeSnapshotScheduler>

    /**
     * Not a [Provider]: it holds a context and a scope and observes nothing, so constructing it
     * during member injection costs nothing and orders nothing against Firebase.
     */
    @Inject
    lateinit var legacyStorePurge: LegacyStorePurge

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

        startAppOwnedServices(
            bindAccess = { authAccessBinder.get().start() },
            startFreeSnapshots = { freeSnapshotScheduler.get().start() },
            purgeRetiredStores = { legacyStorePurge.start() }
        )

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
