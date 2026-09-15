package com.jay.fxi.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.FirebaseAuthTokenSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    internal fun provideAuthTokenSource(source: FirebaseAuthTokenSource): AuthTokenSource = source

    /**
     * The **same** singleton as the token source, deliberately.
     *
     * Two bindings over two instances would be two trackers, and two trackers are two answers to
     * "which generation is this" — the shape this stream exists to collapse.
     */
    @Provides
    @Singleton
    internal fun provideAuthFenceStream(source: FirebaseAuthTokenSource): AuthFenceStream = source

    /** One order for the token provider and the premium issuer to compare (S1 recovery signal §3). */
    @Provides
    @Singleton
    fun provideAccessOrderSequence(): AccessOrderSequence = AccessOrderSequence()

    /** The provider decides recoveries for what goes through it, so it is the stream (S1 recovery signal §2.2). */
    @Provides
    @Singleton
    fun provideAuthCredentialRecoveryStream(provider: AuthTokenProvider): AuthCredentialRecoveryStream = provider

    @Provides
    @Singleton
    internal fun provideAuthTokenProvider(source: AuthTokenSource, orders: AccessOrderSequence): AuthTokenProvider =
        AuthTokenProvider(
            source = source,
            processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            orders = orders,
            // Reported, not thrown: a subscriber's failure does not undo the credential, and a crash here would take the caller's
            // result and every later recovery with it. Crashlytics' own collection flag gates whether anything leaves the device.
            onRecoverySubscriberFailure = FirebaseCrashlytics.getInstance()::recordException
        )
}
