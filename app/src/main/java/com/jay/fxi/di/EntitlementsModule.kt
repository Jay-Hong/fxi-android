package com.jay.fxi.di

import android.os.SystemClock
import com.google.firebase.auth.FirebaseAuth
import com.jay.fxi.data.entitlements.AccessEpochStore
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.entitlements.AuthAccessBinder
import com.jay.fxi.data.entitlements.AuthUidStream
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
 * [AuthAccessBinder] is the only production consumer, and it binds identity only. The current premium
 * The premium *decision* still flows from RevenueCat through `SubscriptionManager` into
 * `RootScreen` and push registration. Moving those onto the server-confirmed entitlement changes
 * the app's permission boundary and is separate, reviewed work — S2 step 3.
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

    /**
     * The uid half of [AuthFenceStream], for consumers that have no use for the generation.
     *
     * **An adapter, not a second listener.** This used to register its own
     * `FirebaseAuth.AuthStateListener`, which meant the access funnel and the identity tracker
     * watched Firebase independently and could disagree about what had happened. Both now read one
     * tracker.
     *
     * Two properties the mapping must keep, and neither is free:
     *
     * **The first value is always delivered, `null` included.** A subscriber has to be able to tell
     * "nobody is signed in" from "nothing has arrived yet", so "not delivered yet" is tracked apart
     * from the value.
     *
     * **Consecutive duplicate uids are suppressed, but `A → null → A` is not.** A generation-only
     * transition carries the same uid, and passing it on is not harmless: `FreeSnapshotScheduler`
     * skips only the cache wipe for a repeated uid and still runs `pump`, freshness, publish and
     * timer re-arm for every event. Collapsing the sign-out in the middle, on the other hand,
     * would hide a real one.
     *
     * The two variables are captured per `observe` call, so each subscriber gets its own first
     * value and its own history.
     */
    @Provides
    @Singleton
    fun provideAuthUidStream(fenceStream: AuthFenceStream): AuthUidStream = AuthUidStream { emit ->
        var delivered = false
        var lastUid: String? = null
        fenceStream.observe { fence ->
            val uid = fence?.uid
            if (!delivered || uid != lastUid) {
                delivered = true
                lastUid = uid
                emit(uid)
            }
        }
    }

    @Provides
    @Singleton
    fun provideAuthAccessBinder(
        coordinator: PremiumAccessCoordinator,
        fenceStream: AuthFenceStream
    ): AuthAccessBinder = AuthAccessBinder(
        coordinator = coordinator,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        fenceStream = fenceStream
    )
}
