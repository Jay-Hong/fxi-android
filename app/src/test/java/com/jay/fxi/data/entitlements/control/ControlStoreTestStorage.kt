package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.accessEpochCorruptionHandler
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import okio.buffer
import okio.sink
import okio.source

/** Real FileStorage; fault injection brackets DataStore's block, not an OS rename syscall. */
internal class ControlStoreTestStorage(private val file: File) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val storage = Faults(FileStorage(Streams) { file })
    private var epoch = 0
    private val epochIds = EpochIdGenerator { "epoch-${epoch++}" }
    val data: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        storage = storage, corruptionHandler = accessEpochCorruptionHandler(epochIds) {},
        migrations = emptyList(), scope = scope
    )
    val owner = DataStoreAccessEpochStore(data, epochIds)
    val control = ControlRecordStore(owner)

    suspend fun close() = scope.coroutineContext[Job]!!.cancelAndJoin()
    suspend fun raw(): Preferences = data.data.first()
    suspend fun seed(seal: String = "[]", demand: String = "[]", hold: String = "[]", recovery: String = "[]") {
        data.edit {
            it[SCHEMA] = 1
            it[SEAL] = seal
            it[DEMAND] = demand
            it[HOLD] = hold
            it[RECOVERY] = recovery
        }
    }

    class Pause {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    class Faults(private val delegate: Storage<Preferences>) : Storage<Preferences> {
        @Volatile var writes = 0
        @Volatile var before = false
        @Volatile var after = false
        @Volatile var unexpected = false
        @Volatile var pause: Pause? = null
        override fun createConnection(): StorageConnection<Preferences> {
            val connection = delegate.createConnection()
            return object : StorageConnection<Preferences> {
                override suspend fun <R> readScope(block: suspend ReadScope<Preferences>.(Boolean) -> R): R =
                    connection.readScope(block)
                override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) = connection.writeScope {
                    writes++
                    if (unexpected) {
                        unexpected = false
                        throw IllegalStateException("unexpected implementation failure")
                    }
                    if (before) {
                        before = false
                        throw IOException("before write block")
                    }
                    block()
                    pause?.let {
                        pause = null
                        it.reached.complete(Unit)
                        it.release.await()
                    }
                    if (after) {
                        after = false
                        throw IOException("after write block")
                    }
                }
                override val coordinator get() = connection.coordinator
                override fun close() = connection.close()
            }
        }
    }

    private object Streams : Serializer<Preferences> {
        override val defaultValue get() = PreferencesSerializer.defaultValue
        override suspend fun readFrom(input: InputStream): Preferences = PreferencesSerializer.readFrom(input.source().buffer())
        override suspend fun writeTo(t: Preferences, output: OutputStream) {
            val sink = output.sink().buffer()
            PreferencesSerializer.writeTo(t, sink)
            sink.flush()
        }
    }

    companion object {
        val SCHEMA = intPreferencesKey("control_schema")
        val SEAL = stringPreferencesKey("seal_v1")
        val DEMAND = stringPreferencesKey("demand_v1")
        val HOLD = stringPreferencesKey("hold_v1")
        val RECOVERY = stringPreferencesKey("recovery_intent_v1")
        val BARRIER = longPreferencesKey("read_barrier")
        val EXTRA = stringPreferencesKey("future_key")
    }
}
