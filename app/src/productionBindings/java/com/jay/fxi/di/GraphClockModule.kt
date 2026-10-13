package com.jay.fxi.di

import com.jay.fxi.time.AppClock
import com.jay.fxi.time.SystemAppClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * S5-4 slice 2: the production graph clock, the system wall clock, for the debug, release, ciMinified and benchmark build types.
 * Providing it hands over the object only: it calls no now(), does no I/O and starts nothing.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GraphClockModule {

    @Provides
    @GraphWallClock
    fun provideGraphWallClock(): AppClock = SystemAppClock
}
