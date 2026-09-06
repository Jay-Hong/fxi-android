package com.jay.fxi.di

import com.jay.fxi.data.free.AuthenticatedFreeSnapshotService
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FreeSnapshotDataModule {
    @Binds
    @Singleton
    abstract fun bindFreeSnapshotFetching(service: AuthenticatedFreeSnapshotService): FreeSnapshotFetching
}
