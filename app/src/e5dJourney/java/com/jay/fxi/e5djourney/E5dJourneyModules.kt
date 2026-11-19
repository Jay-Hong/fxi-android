package com.jay.fxi.e5djourney

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUserObservation
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.local.DataStoreLastSelectedBankStore
import com.jay.fxi.di.GraphWallClock
import com.jay.fxi.di.ServerEndpoints
import com.jay.fxi.subscription.SubscriptionManager
import com.jay.fxi.time.AppClock
import com.jay.fxi.time.SystemAppClock
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * S7 E5d-J (`e5dj_design.claude.r3.md` §3.2, `e5dj_j2_plan.claude.r3.md` §2): the journey build type's bindings for the five
 * points every other build type takes from src/productionBindings — one identity behind the three auth bindings, the loopback
 * server's endpoints, and the wall clock the free scheduler also reads. Same component and scopes as the production modules.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object E5dJourneyBindingsModule {

    @Provides
    @Singleton
    fun provideE5dJourneyIdentity(): E5dJourneyIdentity {
        val main = Handler(Looper.getMainLooper())
        return E5dJourneyIdentity { main.post(it) }
    }

    @Provides
    fun provideAuthUserObservation(identity: E5dJourneyIdentity): AuthUserObservation = identity

    @Provides
    @Singleton
    fun provideAuthTokenSource(identity: E5dJourneyIdentity): AuthTokenSource = identity

    @Provides
    @Singleton
    fun provideAuthFenceStream(identity: E5dJourneyIdentity): AuthFenceStream = identity

    @Provides
    @GraphWallClock
    fun provideGraphWallClock(): AppClock = SystemAppClock
}

@Module
@InstallIn(SingletonComponent::class)
internal object E5dJourneyEndpointsModule {

    @Provides
    @Singleton
    fun provideE5dJourneyServer(@ApplicationContext context: Context): E5dJourneyServer =
        E5dJourneyServer { name -> context.assets.open(name).use { it.readBytes() } }.also { it.start() }

    @Provides
    fun provideServerEndpoints(server: E5dJourneyServer): ServerEndpoints = server.endpoints
}

/** What the control receiver reads from the very graph the production consumers use (R7): never a second instance. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface E5dJourneyEntryPoint {
    fun identity(): E5dJourneyIdentity
    fun server(): E5dJourneyServer
    fun authUserObservation(): AuthUserObservation
    fun authTokenSource(): AuthTokenSource
    fun authFenceStream(): AuthFenceStream
    fun serverEndpoints(): ServerEndpoints
    fun subscriptionManager(): SubscriptionManager
    fun premiumAccessCoordinator(): PremiumAccessCoordinator
    fun lastSelectedBankStore(): DataStoreLastSelectedBankStore

    @GraphWallClock
    fun graphWallClock(): AppClock
}
