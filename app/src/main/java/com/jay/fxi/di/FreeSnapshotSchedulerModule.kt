package com.jay.fxi.di

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.entitlements.AuthUidStream
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.data.free.InstallSeedSource
import com.jay.fxi.data.local.DataStoreFreeTabStore
import com.jay.fxi.data.local.DataStoreFreeVisibleSeriesStore
import com.jay.fxi.data.local.DataStoreRateRowPreferenceStore
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.FreeVisibleSeriesStore
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock

@Module
@InstallIn(SingletonComponent::class)
object FreeSnapshotSchedulerModule {

    @Provides
    @Singleton
    internal fun provideFreeSnapshotScheduler(
        fetcher: FreeSnapshotFetching,
        uidStream: AuthUidStream,
        tokenProvider: AuthTokenProvider,
        seeds: InstallSeedSource
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
        // The scheduler asks for the seed from its own coroutine, after the first fetch completes — never on the main
        // thread. By then the app start has usually read it ahead on IO (S4 CUT-CC1).
        installId = freeSchedulerInstallId(seeds)
    )

    @Provides
    @Singleton
    fun provideFreeTabStore(store: DataStoreFreeTabStore): FreeTabStore = store

    @Provides
    @Singleton
    fun provideFreeVisibleSeriesStore(
        store: DataStoreFreeVisibleSeriesStore
    ): FreeVisibleSeriesStore = store

    @Provides
    @Singleton
    fun provideRateRowPreferenceStore(
        store: DataStoreRateRowPreferenceStore
    ): RateRowPreferenceStore = store

}

/**
 * S4 CUT-CC1: the free scheduler's synchronous seed. Memory only once the seed is published; otherwise this blocks the
 * scheduler's Default event coroutine until the shared source has read it on IO, serialised with every other call to that
 * source. The loop handles no other event meanwhile, and a bare runBlocking does not inherit the caller's Job. Only the free
 * scheduler uses this fallback; Main, the graph jitter and graph initialization never do.
 */
internal fun freeSchedulerInstallId(seeds: InstallSeedSource): () -> String = { seeds.current ?: runBlocking { seeds.get() } }
