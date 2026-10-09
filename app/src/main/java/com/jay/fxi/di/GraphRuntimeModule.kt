package com.jay.fxi.di

import android.content.Context
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo
import com.jay.fxi.data.graph.FileGraphV2DiskStore
import com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec
import com.jay.fxi.data.local.BackupableUserIntentStore
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.remote.TopicUseAuthority
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/**
 * S4 CUT-CC1: the process-wide providers the graph runtime will share with the topic runtime (`cut_cc1_agreed.r3.md`).
 * Only the topic factory consumes one of them yet, the use authority. The deletion admission, the selection store and the
 * disk store have no production consumer until CC3-CC5. Resolving the disk store is memory-only: `noBackupFilesDir`, which
 * may create that directory, is first read inside the store's I/O (S4 CUT-CC4a); nothing here creates `graph_v2` or touches
 * a graph file.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GraphRuntimeModule {

    /** The one use authority of the topic runtime and, from CC4, of the graph runtime. Stateless: it reads the snapshot. */
    @Provides
    @Singleton
    fun provideTopicUseAuthority(coordinator: PremiumAccessCoordinator): TopicUseAuthority =
        SnapshotTopicUseAuthority { coordinator.accessSnapshot }

    /** Deletion publication (CC3) and graph admission (CC4) share this one memory-only store. */
    @Provides
    @Singleton
    fun provideDeletionAdmissionStore(): DeletionAdmissionStore = DeletionAdmissionStore()

    /** The interface over the one selection store: the concrete singleton itself, so there is one store mutex. */
    @Provides
    @Singleton
    fun provideGraphSelectionStore(store: BackupableUserIntentStore): GraphSelectionStore = store

    /**
     * The writer and the purge must share this instance; its root is excluded from backup and from device transfer. Resolving
     * it is memory-only: the root directory is first asked for inside the store's I/O (S4 CUT-CC4a).
     */
    @Provides
    @Singleton
    fun provideGraphV2DiskStore(@ApplicationContext context: Context): FileGraphV2DiskStore =
        graphV2DiskStoreAt { File(context.noBackupFilesDir, "graph_v2") }
}

/** The graph v2 disk store at [root], asked for on first I/O; constructing it touches no file. */
internal fun graphV2DiskStoreAt(root: () -> File): FileGraphV2DiskStore =
    FileGraphV2DiskStore(root, JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), Dispatchers.IO)
