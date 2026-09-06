package com.jay.fxi.di

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.AuthUidStream
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Clock

@Module
@InstallIn(SingletonComponent::class)
object FreeSnapshotSchedulerModule {

    @Provides
    @Singleton
    fun provideFreeSnapshotScheduler(
        fetcher: FreeSnapshotFetching,
        uidStream: AuthUidStream,
        tokenProvider: AuthTokenProvider,
        @ApplicationContext context: Context
    ): FreeSnapshotScheduler = FreeSnapshotScheduler(
        fetcher = fetcher,
        uidStream = uidStream,
        authFence = tokenProvider::currentIdentityFence,
        // A dropped event costs a refresh cycle and says nothing on screen, so it has to be
        // reportable somewhere. Crashlytics is already configured by the application, and its
        // collection flag already gates whether anything leaves the device.
        onEventFailure = FirebaseCrashlytics.getInstance()::recordException,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        clock = Clock.System::now,
        // `lazy` rather than a value: this provider is resolved from `Application.onCreate`, and
        // the seed comes off disk. The scheduler asks for it from its own coroutine, after the
        // first fetch completes — never on the main thread.
        installId = lazy { installSeed(context) }::value
    )

    /**
     * A stable random value, drawn once per install.
     *
     * It only ever feeds the jitter that spreads clients across the refresh window, so its single
     * requirement is that it survive restarts — a value redrawn on every launch would still spread,
     * but would move this install's slot around and make a herd harder to reason about. It is
     * deliberately not a device identifier: nothing derives it from the hardware and it does not
     * leave the process. iOS keeps the same value under `free_snapshot_install_seed` in
     * `UserDefaults`; this is the same idea in `SharedPreferences`. A device-transfer restore can
     * copy it, so "per install" is not strict — uniqueness is not a correctness requirement here,
     * only spread.
     */
    private fun installSeed(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_SEED, null)?.let { return it }
        val seed = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_SEED, seed).apply()
        return seed
    }

    private const val PREFS = "free_snapshot"
    private const val KEY_INSTALL_SEED = "install_seed"
}
