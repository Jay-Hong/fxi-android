package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okio.buffer
import okio.sink
import okio.source

/**
 * Claude-owned S4 B2b-1 contract r1 fixture, shared by the store and the purge-adapter contracts.
 *
 * The real Preferences DataStore over one temporary file, with no corruption handler and no migration (b2b_api_codex.r1 §1),
 * behind two decorators. [FaultyIntentDataStore] fails before the delegate is called - nothing reached DataStore - or after it
 * returned normally, and counts every call, read or write. [FaultyIntentStorage] counts physical write blocks and fails a file
 * read, or the step after the write block and before the rename. [open] cancels the previous scope before it reopens, so a
 * reopen reads the file rather than a cache. The pattern is DataStoreAccessEpochStoreReadBackTest's; its objects are private
 * there, so they are restated here.
 */
internal class IntentStoreHarness(val file: File) {

    class Opened(
        val store: BackupableUserIntentStore,
        val dataStore: FaultyIntentDataStore,
        val storage: FaultyIntentStorage,
        val scope: CoroutineScope
    ) {
        suspend fun raw(): Preferences = dataStore.delegate.data.first()

        suspend fun putRaw(block: (MutablePreferences) -> Unit) {
            dataStore.delegate.edit { block(it) }
        }
    }

    private var opened: Opened? = null

    suspend fun open(): Opened {
        close()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val storage = FaultyIntentStorage(FileStorage(IntentStreamSerializer) { file })
        val dataStore = FaultyIntentDataStore(
            PreferenceDataStoreFactory.create(storage = storage, corruptionHandler = null, migrations = emptyList(), scope = scope)
        )
        return Opened(BackupableUserIntentStore(dataStore), dataStore, storage, scope).also { opened = it }
    }

    suspend fun close() {
        opened?.scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        opened = null
    }
}

internal class FaultyIntentDataStore(val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    @Volatile var updates = 0
    @Volatile var failBeforeUpdate = false
    @Volatile var failAfterUpdate = false
    @Volatile var cancelBeforeUpdate = false

    override val data: Flow<Preferences> get() = delegate.data

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updates += 1
        if (cancelBeforeUpdate) {
            cancelBeforeUpdate = false
            throw CancellationException("injected cancellation before the delegate")
        }
        if (failBeforeUpdate) {
            failBeforeUpdate = false
            throw IOException("injected before the delegate")
        }
        val result = delegate.updateData(transform)
        if (failAfterUpdate) {
            failAfterUpdate = false
            throw IOException("injected after the delegate returned")
        }
        return result
    }
}

internal class FaultyIntentStorage(private val delegate: Storage<Preferences>) : Storage<Preferences> {
    @Volatile var writes = 0
    @Volatile var failAfterWrite = false
    @Volatile var failRead = false

    override fun createConnection(): StorageConnection<Preferences> {
        val connection = delegate.createConnection()
        return object : StorageConnection<Preferences> {
            override suspend fun <R> readScope(block: suspend ReadScope<Preferences>.(locked: Boolean) -> R): R {
                if (failRead) throw IOException("injected read failure")
                return connection.readScope(block)
            }

            override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) =
                connection.writeScope {
                    writes += 1
                    block()
                    if (failAfterWrite) {
                        failAfterWrite = false
                        throw IOException("injected after the write block, before the rename")
                    }
                }

            override val coordinator get() = connection.coordinator

            override fun close() = connection.close()
        }
    }
}

internal object IntentStreamSerializer : Serializer<Preferences> {
    override val defaultValue: Preferences get() = PreferencesSerializer.defaultValue

    override suspend fun readFrom(input: InputStream): Preferences =
        PreferencesSerializer.readFrom(input.source().buffer())

    override suspend fun writeTo(t: Preferences, output: OutputStream) {
        val sink = output.sink().buffer()
        PreferencesSerializer.writeTo(t, sink)
        sink.flush()
    }
}
