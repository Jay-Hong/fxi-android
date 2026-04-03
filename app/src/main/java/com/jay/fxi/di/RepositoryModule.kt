package com.jay.fxi.di

import com.jay.fxi.data.repository.ExchangeRateRepositoryImpl
import com.jay.fxi.data.repository.NewsRepositoryImpl
import com.jay.fxi.domain.repository.ExchangeRateRepository
import com.jay.fxi.domain.repository.NewsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindExchangeRateRepository(
        impl: ExchangeRateRepositoryImpl
    ): ExchangeRateRepository

    @Binds
    @Singleton
    abstract fun bindNewsRepository(
        impl: NewsRepositoryImpl
    ): NewsRepository
}
