package com.jay.fxi.di

import android.content.Context
import com.jay.fxi.data.local.LegacyStorePurge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object LegacyStoreModule {

    @Provides
    @Singleton
    fun provideLegacyStorePurge(
        @ApplicationContext context: Context
    ): LegacyStorePurge = LegacyStorePurge(
        context = context,
        // `IO` rather than the `Default` the schedulers use: this scope does one thing, and that
        // thing is a handful of blocking filesystem calls.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )
}
