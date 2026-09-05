package com.jay.fxi.di

import android.os.SystemClock
import com.jay.fxi.data.entitlements.AccessEpochStore
import com.jay.fxi.data.entitlements.CapabilityScopePurger
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.AuthenticatedEntitlementsSource
import com.jay.fxi.data.entitlements.EntitlementsSource
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.RecheckClock
import com.jay.fxi.data.entitlements.UnimplementedScopePurger
import com.jay.fxi.data.entitlements.UserScopePurger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * D23 access-state graph.
 *
 * Nothing injects [PremiumAccessCoordinator] yet, and that is deliberate. The current premium
 * decision still flows from RevenueCat through `SubscriptionManager` into `RootScreen` and push
 * registration; moving those onto the server-confirmed entitlement changes the app's permission
 * boundary and is separate, reviewed work. This module makes the component constructible so that
 * change is a wiring step rather than a rewrite.
 */
@Module
@InstallIn(SingletonComponent::class)
object EntitlementsModule {

    /** I4: epochs are opaque UUIDs, never reused and never reset to a sentinel. */
    @Provides
    @Singleton
    fun provideEpochIdGenerator(): EpochIdGenerator = EpochIdGenerator.Random

    @Provides
    @Singleton
    fun provideAccessEpochStore(store: DataStoreAccessEpochStore): AccessEpochStore = store

    /** Monotonic and unaffected by wall-clock changes, so a debounce cannot be skipped. */
    @Provides
    @Singleton
    fun provideRecheckClock(): RecheckClock = RecheckClock { SystemClock.elapsedRealtime() }

    /**
     * Placeholder purgers.
     *
     * They report [com.jay.fxi.data.entitlements.PurgeResult.Deferred], which keeps the
     * pending-purge journal intact. The real targets are the legacy rate and graph stores, which
     * `ANDROID_V2_PLAN.md` §5.1 says this stage does not remove.
     */
    @Provides
    @Singleton
    fun provideUnimplementedScopePurger(): UnimplementedScopePurger = UnimplementedScopePurger()

    @Provides
    @Singleton
    fun provideUserScopePurger(purger: UnimplementedScopePurger): UserScopePurger = purger

    @Provides
    @Singleton
    fun provideCapabilityScopePurger(
        purger: UnimplementedScopePurger
    ): CapabilityScopePurger = purger

    @Provides
    @Singleton
    fun provideEntitlementsSource(
        source: AuthenticatedEntitlementsSource
    ): EntitlementsSource = source

    @Provides
    @Singleton
    fun providePremiumAccessCoordinator(
        source: EntitlementsSource,
        store: AccessEpochStore,
        userPurger: UserScopePurger,
        capabilityPurger: CapabilityScopePurger,
        clock: RecheckClock
    ): PremiumAccessCoordinator = PremiumAccessCoordinator(
        source = source,
        store = store,
        userPurger = userPurger,
        capabilityPurger = capabilityPurger,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        clock = clock
    )
}
