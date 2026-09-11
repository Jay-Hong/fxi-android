package com.jay.fxi.di

import com.google.firebase.auth.FirebaseAuth
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

    @Provides
    @Singleton
    internal fun provideAuthTokenProvider(source: AuthTokenSource): AuthTokenProvider =
        AuthTokenProvider(
            source = source,
            processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
}
