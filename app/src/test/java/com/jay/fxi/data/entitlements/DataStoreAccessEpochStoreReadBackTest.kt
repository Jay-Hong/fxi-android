package com.jay.fxi.data.entitlements

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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.buffer
import okio.sink
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AccessEpochStore.load]'s read-back contract against the production store over a real [FileStorage].
 *
 * Faults are injected in the storage connection around DataStore's own write block: *after* it is where
 * a rename would fail once DataStore has updated its in-memory copy, *before* it is where a scratch write
 * would fail. These model those failure points; they do not make the file system refuse a rename. The
 * connection is built with the public okio serializer through a stream adapter rather than the
 * production internal one: the codec is not under test here.
 *
 * Each test starts without the barrier key, as a fresh install and the corruption replacement do.
 * Repeated-failure coverage also exercises a barrier after that key has been persisted.
 */
class DataStoreAccessEpochStoreReadBackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0
    private val ids = EpochIdGenerator { "epoch-${counter++}" }
    private val file by lazy { File(folder.root, "access_epoch.preferences_pb") }

    private var opened: Opened? = null

    @After
    fun tearDown() = runBlocking { close() }

    private class Opened(
        val store: DataStoreAccessEpochStore,
        val dataStore: DataStore<Preferences>,
        val storage: FaultyStorage,
        val scope: CoroutineScope
    )

    private suspend fun open(): Opened {
        close()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val storage = FaultyStorage(FileStorage(StreamSerializer) { file })
        val dataStore = PreferenceDataStoreFactory.create(
            storage = storage,
            corruptionHandler = null,
            migrations = emptyList(),
            scope = scope
        )
        return Opened(DataStoreAccessEpochStore(dataStore, ids), dataStore, storage, scope).also { opened = it }
    }

    private suspend fun close() {
        opened?.scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        opened = null
    }

    /** What the file holds, read by a store that has never seen a failure. */
    private suspend fun disk(): AccessEpochRecord = open().store.load()

    private suspend fun raw(o: Opened): Preferences = o.dataStore.data.first()

    @Test
    fun aWriteThatFailsAfterTheCopyUpdates_isNotReadBackAsLanded() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")

        o.storage.failAfterWrite = true
        assertTrue(runCatching { o.store.beginRotation(rotateUser = true, rotateKrx = true) }.isFailure)
        // The premise: DataStore itself now serves a rotation the file does not have.
        assertNotEquals(before.userAccessEpoch, raw(o)[USER_EPOCH])

        assertEquals(before, o.store.load())
        assertEquals(before, disk())
    }

    @Test
    fun aWriteThatFailsAfterTheCopyUpdates_leavesTheFileAsItWas() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")

        o.storage.failAfterWrite = true
        assertTrue(runCatching { o.store.beginRotation(rotateUser = true, rotateKrx = true) }.isFailure)

        // Reopened straight away, before any read of this instance could write a barrier.
        assertEquals(before, disk())
    }

    @Test
    fun aWriteThatFailsBeforeTheCopyUpdates_readsBackTheFile() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")

        o.storage.failBeforeWrite = true
        assertTrue(runCatching { o.store.beginRotation(rotateUser = true, rotateKrx = true) }.isFailure)

        assertEquals(before, o.store.load())
        assertEquals(before, disk())
    }

    @Test
    fun aBarrierThatAlsoFails_endsTheRead_andTheNextBarrierThatLandsSettlesIt() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.storage.failAfterWrite = true
        assertTrue(runCatching { o.store.beginRotation(rotateUser = true, rotateKrx = true) }.isFailure)

        o.storage.failAfterWrite = true
        assertTrue("a barrier that did not land must not answer", runCatching { o.store.load() }.isFailure)

        assertEquals(before, o.store.load())
        val barrier = raw(o)[READ_BARRIER]
        assertEquals(before, o.store.load())
        assertEquals("a settled store must read without writing", barrier, raw(o)[READ_BARRIER])
    }

    @Test
    fun aBarrierWhoseResultDoesNotDecode_endsTheReadRatherThanAnsweringWithSomething() = runBlocking {
        val o = open()
        o.store.bindOwner("u1")
        // A value of the wrong type under a record key: DataStore reads the file, the record does not decode.
        o.dataStore.edit { it[longPreferencesKey("user_access_epoch")] = 7L }
        assertTrue(runCatching { o.store.load() }.isFailure)

        assertTrue("a barrier whose result does not decode must not answer", runCatching { o.store.load() }.isFailure)
    }

    @Test
    fun aSecondFailureAfterASettledBarrier_isStillNotReadBackAsLanded() = runBlocking {
        val o = open()
        o.store.bindOwner("u1")
        o.storage.failAfterWrite = true
        runCatching { o.store.markMayContainData(premium = true) }
        val settled = o.store.load()

        o.storage.failAfterWrite = true
        assertTrue(runCatching { o.store.beginRotation(rotateUser = true, rotateKrx = true) }.isFailure)

        assertEquals(settled, o.store.load())
        // The read after the barrier is the one a barrier that wrote nothing would leave on DataStore's copy.
        assertEquals(settled, o.store.load())
        assertEquals(settled, disk())
    }

    @Test
    fun aMutationThatReturnsNormally_doesNotSettleTheReadBack() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.storage.failAfterWrite = true
        assertTrue(runCatching { o.store.beginRotation(rotateUser = true, rotateKrx = true) }.isFailure)

        // A non-owner's sign-out changes nothing, so DataStore skips the write and keeps its copy.
        assertEquals(before, o.store.beginSignOut("someone-else"))

        assertEquals(before, o.store.load())
    }

    @Test
    fun aStoreThatNeverFailed_readsWithoutWriting() = runBlocking {
        val o = open()
        val bound = o.store.bindOwner("u1")

        assertEquals(bound, o.store.load())
        assertFalse(READ_BARRIER in raw(o).asMap())
    }

    @Test
    fun aReadCannotReachTheCopyOfAWriteStillInFlight() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        val pause = Pause()
        o.storage.pauseAfterWrite = pause
        o.storage.failAfterWrite = true

        val rotating = async { runCatching { o.store.beginRotation(rotateUser = true, rotateKrx = true) } }
        withTimeout(10_000) { pause.reached.await() }
        // The window is real: DataStore already serves the copy of a write that has not landed.
        assertNotEquals(before.userAccessEpoch, raw(o)[USER_EPOCH])

        val reading = async(start = CoroutineStart.UNDISPATCHED) { o.store.load() }
        pause.release.complete(Unit)

        assertTrue(withTimeout(10_000) { rotating.await() }.isFailure)
        assertEquals(before, withTimeout(10_000) { reading.await() })
    }

    @Test
    fun aCancelledWriterWhoseWriteFails_leavesTheReadToBeVerified() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        val pause = Pause()
        o.storage.pauseAfterWrite = pause
        o.storage.failAfterWrite = true

        val writer = launch { o.store.beginRotation(rotateUser = true, rotateKrx = true) }
        withTimeout(10_000) { pause.reached.await() }
        writer.cancelAndJoin()

        val reading = async(start = CoroutineStart.UNDISPATCHED) { o.store.load() }
        pause.release.complete(Unit)

        assertEquals(before, withTimeout(10_000) { reading.await() })
        assertEquals(before, disk())
    }

    @Test
    fun aCancelledWriterWhoseWriteLands_readsBackWhatLanded() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        val pause = Pause()
        o.storage.pauseAfterWrite = pause

        val writer = launch { o.store.beginRotation(rotateUser = true, rotateKrx = true) }
        withTimeout(10_000) { pause.reached.await() }
        writer.cancelAndJoin()

        val reading = async(start = CoroutineStart.UNDISPATCHED) { o.store.load() }
        pause.release.complete(Unit)

        val read = withTimeout(10_000) { reading.await() }
        assertNotEquals(before.userAccessEpoch, read.userAccessEpoch)
        assertEquals(read, disk())
    }

    @Test
    fun theBarrierSurvivesOrdinaryWrites() = runBlocking {
        val o = open()
        o.store.bindOwner("u1")
        o.storage.failAfterWrite = true
        runCatching { o.store.markMayContainData(premium = true) }
        o.store.load()
        val barrier = raw(o)[READ_BARRIER]

        o.store.beginRotation(rotateUser = true, rotateKrx = false)

        assertEquals(barrier, raw(o)[READ_BARRIER])
        assertTrue(barrier != null)
    }

    /** Suspends the write block's caller between DataStore's copy update and the would-be rename. */
    private class Pause {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class FaultyStorage(private val delegate: Storage<Preferences>) : Storage<Preferences> {
        @Volatile var failBeforeWrite = false
        @Volatile var failAfterWrite = false
        @Volatile var pauseAfterWrite: Pause? = null

        override fun createConnection(): StorageConnection<Preferences> {
            val connection = delegate.createConnection()
            return object : StorageConnection<Preferences> {
                override suspend fun <R> readScope(
                    block: suspend ReadScope<Preferences>.(locked: Boolean) -> R
                ): R = connection.readScope(block)

                override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) =
                    connection.writeScope {
                        if (failBeforeWrite) {
                            failBeforeWrite = false
                            throw IOException("injected before the write block")
                        }
                        block()
                        pauseAfterWrite?.let { pause ->
                            pauseAfterWrite = null
                            pause.reached.complete(Unit)
                            pause.release.await()
                        }
                        if (failAfterWrite) {
                            failAfterWrite = false
                            throw IOException("injected after the write block")
                        }
                    }

                override val coordinator get() = connection.coordinator

                override fun close() = connection.close()
            }
        }
    }

    private object StreamSerializer : Serializer<Preferences> {
        override val defaultValue: Preferences get() = PreferencesSerializer.defaultValue

        override suspend fun readFrom(input: InputStream): Preferences =
            PreferencesSerializer.readFrom(input.source().buffer())

        override suspend fun writeTo(t: Preferences, output: OutputStream) {
            val sink = output.sink().buffer()
            PreferencesSerializer.writeTo(t, sink)
            sink.flush()
        }
    }

    private companion object {
        val USER_EPOCH = stringPreferencesKey("user_access_epoch")
        val READ_BARRIER = longPreferencesKey("read_barrier")
    }
}
