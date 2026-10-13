package com.jay.fxi.di

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUserObservation
import com.jay.fxi.data.auth.FirebaseAuthTokenSource
import com.jay.fxi.data.auth.FirebaseAuthUserObservation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * S5-4 slice 3: the production identity bindings, for the debug, release, ciMinified and benchmark build types. A fixture build
 * type replaces all three with one identity source of its own.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AuthBindingsModule {

    /** The user the auth screen state shows, observed on the same FirebaseAuth the token source tracks. */
    @Provides
    fun provideAuthUserObservation(observation: FirebaseAuthUserObservation): AuthUserObservation = observation

    @Provides
    @Singleton
    fun provideAuthTokenSource(source: FirebaseAuthTokenSource): AuthTokenSource = source

    /**
     * The **same** singleton as the token source, deliberately.
     *
     * Two bindings over two instances would be two trackers, and two trackers are two answers to
     * "which generation is this" — the shape this stream exists to collapse.
     */
    @Provides
    @Singleton
    fun provideAuthFenceStream(source: FirebaseAuthTokenSource): AuthFenceStream = source
}
