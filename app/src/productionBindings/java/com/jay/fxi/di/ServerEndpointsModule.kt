package com.jay.fxi.di

import com.jay.fxi.util.ApiConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * S5-4 slice 4a: the production servers, for the debug, release, ciMinified and benchmark build types. Providing them hands over
 * the constants only: no I/O.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ServerEndpointsModule {

    @Provides
    fun provideServerEndpoints(): ServerEndpoints = ServerEndpoints(ApiConfig.BASE_URL, ApiConfig.WS_URL)
}
