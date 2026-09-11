package com.jay.fxi.di

import com.jay.fxi.service.DataStorePushRegistrationLedger
import com.jay.fxi.service.PushRegistrationLedger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    @Singleton
    abstract fun bindPushRegistrationLedger(ledger: DataStorePushRegistrationLedger): PushRegistrationLedger
}
