package com.jay.fxi.data.entitlements

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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Both entry points use one owner over real DataStore 1.1.7 and FileStorage. The connection injects
 * faults before/after DataStore's write block; it does not force an OS rename failure. Kept separate
 * from the existing read-back suite so the legacy regression tests remain byte-for-byte unchanged.
 */
class DataStoreAccessEpochStoreTransactionTest {
    @get:Rule val folder = TemporaryFolder()
    private val file by lazy { File(folder.root, "access_epoch.preferences_pb") }
    private var counter = 0
    private val ids = EpochIdGenerator { "epoch-${counter++}" }
    private var opened: Opened? = null

    @After fun tearDown() = runBlocking { close() }

    @Test fun aFailedControlWriteSharesTheLegacyReadBackObligation() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.storage.failAfterWrite = true

        assertTrue(runCatching { o.confirmControl() }.isFailure)
        assertEquals("candidate", o.raw()[CONTROL]) // Cache changed, file did not.
        assertEquals(before, o.store.load())
        assertNull(o.raw()[CONTROL])
        assertNotNull(o.raw()[BARRIER])
        assertEquals(before, disk())
    }

    @Test fun aControlWriteFailureBeforeTheCopyUpdatesAlsoRequiresVerification() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.storage.failBeforeWrite = true

        assertTrue(runCatching { o.confirmControl() }.isFailure)
        assertNull(o.raw()[CONTROL])
        assertEquals(before, o.store.load())
        assertNotNull(o.raw()[BARRIER])
    }

    @Test fun aControlTriggeredReplacementFailureCannotReturnItsCachedEpochs() = runBlocking {
        val o = open()
        o.store.bindOwner("u1")
        file.writeBytes(byteArrayOf(0x0f)) // Invalid protobuf wire type, after initialization.
        o.storage.failAfterWrite = true
        var decided = false

        assertTrue(runCatching {
            o.store.transactRecord {
                decided = true
                RecordTransactionDecision.Observe(Unit)
            }
        }.isFailure)
        assertFalse("replacement failed before the caller's decision", decided)
        val failedReplacementEpoch = o.raw()[USER_EPOCH]
        assertNotNull(failedReplacementEpoch)

        val recovered = o.store.load()
        assertNotEquals(failedReplacementEpoch, recovered.userAccessEpoch)
        assertEquals(1, recovered.pendingPurges.size)
        assertNotNull(o.raw()[BARRIER])
        assertEquals(recovered, disk())
    }

    @Test fun aFailedLegacyWriteIsStillUnverifiedAfterAnInternalObservation() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.failRotation()

        val result = o.store.transactRecord { RecordTransactionDecision.Observe(it[USER_EPOCH]) }
        assertEquals(before.userAccessEpoch, result.value)
        assertEquals(RecordTransactionEvidence.LockedFileRead, result.evidence)
        assertNull(result.snapshot[BARRIER])
        assertNotEquals(before.userAccessEpoch, o.raw()[USER_EPOCH])
        assertEquals(before, o.store.load())
        assertNotNull(o.raw()[BARRIER])
    }

    @Test fun aPositiveUnchangedCandidateRepairsTheCacheWithTheSharedBarrier() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.failRotation()

        val result = o.store.transactRecord { RecordTransactionDecision.Confirm(it, "joined") }
        assertEquals("joined", result.value)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.evidence)
        assertEquals(before.userAccessEpoch, result.snapshot[USER_EPOCH])
        assertEquals(1L, result.snapshot[BARRIER])
        val writes = o.storage.writes
        repeat(2) { assertEquals(before, o.store.load()) }
        assertEquals("confirmed loads must not write again", writes, o.storage.writes)
        assertEquals(before, disk())
    }

    @Test fun aPositiveChangedCandidateConfirmsInTheSameWriteScope() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.failRotation()
        val writes = o.storage.writes

        val result = o.confirmControl()
        assertEquals(writes + 1, o.storage.writes)
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.evidence)
        assertEquals("candidate", result.snapshot[CONTROL])
        assertEquals(1L, result.snapshot[BARRIER])
        assertEquals(before, o.store.load())
        assertEquals(writes + 1, o.storage.writes)
    }

    @Test fun aFailedConfirmationBarrierReturnsNoResultAndDoesNotSettleTheFlag() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.failRotation()
        o.storage.failAfterWrite = true

        assertTrue(runCatching {
            o.store.transactRecord { RecordTransactionDecision.Confirm(it, Unit) }
        }.isFailure)
        val writes = o.storage.writes
        assertEquals(before, o.store.load())
        assertEquals(writes + 1, o.storage.writes)
        val settledWrites = o.storage.writes
        assertEquals(before, o.store.load())
        assertEquals(settledWrites, o.storage.writes)
    }

    @Test fun anObservationInACleanStoreDoesNotWriteOrInitializeTheRecord() = runBlocking {
        val o = open()
        val result = o.store.transactRecord { RecordTransactionDecision.Observe<String?>(null) }

        assertNull(result.value)
        assertTrue(result.snapshot.asMap().isEmpty())
        assertEquals(RecordTransactionEvidence.LockedFileRead, result.evidence)
        assertEquals(0, o.storage.writes)
        assertFalse(file.exists())
    }

    @Test fun anUnchangedPositiveCandidateInACleanStoreNeedsNoBarrier() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        val writes = o.storage.writes

        val result = o.store.transactRecord { RecordTransactionDecision.Confirm(it, Unit) }
        assertEquals(RecordTransactionEvidence.LockedFileRead, result.evidence)
        assertNull(result.snapshot[BARRIER])
        assertEquals(before, o.store.load())
        assertEquals(writes, o.storage.writes)
    }

    @Test fun aChangedCandidatePreservesUnrelatedKeysAndReturnsOnlyAfterPersistence() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.dataStore.edit { it[UNRELATED] = "opaque" }

        val result = o.confirmControl()
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.evidence)
        assertEquals("opaque", result.snapshot[UNRELATED])
        assertNull(result.snapshot[BARRIER])
        assertEquals(before, o.store.load())
        val reopened = open()
        assertEquals(result.snapshot, reopened.raw())
    }

    @Test fun normalLegacyMutationsDoNotClearAnInternalFailure() = runBlocking {
        val o = open()
        o.store.bindOwner("u1")
        o.storage.failAfterWrite = true
        assertTrue(runCatching { o.confirmControl() }.isFailure)
        o.store.beginSignOut("someone-else") // No change.
        val changed = o.store.markMayContainData(premium = true) // Actual write, still no confirmation.
        assertNull(o.raw()[BARRIER])
        val writes = o.storage.writes

        assertEquals(changed, o.store.load())
        assertEquals(writes + 1, o.storage.writes)
    }

    @Test fun aDecisionThatThrowsMarksTheSameReadBackFlag() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        val failure = IllegalStateException("decision failed")

        val result = runCatching { o.store.transactRecord<Unit> { throw failure } }
        val propagated = result.exceptionOrNull()
        assertTrue(propagated is IllegalStateException)
        // Coroutine stack-trace recovery may copy the exception, keeping the original as a cause.
        assertTrue(
            "the original decision failure must survive the coroutine boundary",
            generateSequence(propagated) { it.cause }.any { it === failure }
        )
        assertEquals(before, o.store.load())
        assertNotNull(o.raw()[BARRIER])
    }

    @Test fun callersCannotAddRemoveOrRetypeTheOwnersBarrier() = runBlocking {
        val o = open()
        o.store.bindOwner("u1")
        assertTrue(runCatching {
            o.store.transactRecord {
                RecordTransactionDecision.Confirm(it.toMutablePreferences().apply { this[BARRIER] = 4L }, Unit)
            }
        }.exceptionOrNull() is IllegalArgumentException)
        o.store.load() // Establish the owner-controlled key.
        for (retype in listOf(false, true)) {
            val barrier = o.raw()[BARRIER]
            assertTrue(runCatching {
                o.store.transactRecord {
                    val candidate = it.toMutablePreferences().apply {
                        if (retype) this[stringPreferencesKey("read_barrier")] = "wrong"
                        else remove(BARRIER)
                    }
                    RecordTransactionDecision.Confirm(candidate, Unit)
                }
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(barrier, o.raw()[BARRIER])
            o.store.load()
        }
    }

    @Test fun theSharedBarrierStillChangesAtLongOverflow() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        o.dataStore.edit { it[BARRIER] = Long.MAX_VALUE }
        o.failRotation()

        val result = o.store.transactRecord { RecordTransactionDecision.Confirm(it, Unit) }
        assertEquals(Long.MIN_VALUE, result.snapshot[BARRIER])
        assertEquals(RecordTransactionEvidence.CompletedWriteScope, result.evidence)
        assertEquals(before, o.store.load())
        assertEquals(Long.MIN_VALUE, o.raw()[BARRIER])
    }

    @Test fun snapshotsAreFrozenAndDetachedFromTheCallersCandidate() = runBlocking {
        val o = open()
        o.store.bindOwner("u1")
        lateinit var input: Preferences
        lateinit var candidate: MutablePreferences
        val result = o.store.transactRecord {
            input = it
            candidate = it.toMutablePreferences().apply { this[CONTROL] = "candidate" }
            RecordTransactionDecision.Confirm(candidate, Unit)
        }
        candidate[CONTROL] = "changed afterwards"

        assertNull(input[CONTROL])
        assertEquals("candidate", result.snapshot[CONTROL])
        assertEquals("candidate", o.raw()[CONTROL])
        assertTrue(runCatching { (input as MutablePreferences)[CONTROL] = "bad" }.isFailure)
        assertTrue(runCatching { (result.snapshot as MutablePreferences)[CONTROL] = "bad" }.isFailure)
    }

    @Test fun aLegacyReadWaitsForTheInternalWriteAndThenVerifiesItsFailure() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        val pause = Pause()
        o.storage.pauseAfterWrite = pause
        o.storage.failAfterWrite = true
        val writer = async { runCatching { o.confirmControl() } }
        withTimeout(10_000) { pause.reached.await() }
        assertEquals("candidate", o.raw()[CONTROL])

        val reader = async(start = CoroutineStart.UNDISPATCHED) { o.store.load() }
        try {
            assertFalse("load must share the internal writer's lock", reader.isCompleted)
        } finally {
            pause.release.complete(Unit)
        }
        assertTrue(withTimeout(10_000) { writer.await() }.isFailure)
        assertEquals(before, withTimeout(10_000) { reader.await() })
        assertNull(o.raw()[CONTROL])
    }

    @Test fun anInternalTransactionWaitsForALegacyWriterAndReadsTheFile() = runBlocking {
        val o = open()
        val before = o.store.bindOwner("u1")
        val pause = Pause()
        o.storage.pauseAfterWrite = pause
        o.storage.failAfterWrite = true
        val writer = async { runCatching { o.store.beginRotation(true, true) } }
        withTimeout(10_000) { pause.reached.await() }
        val observer = async(start = CoroutineStart.UNDISPATCHED) {
            o.store.transactRecord { RecordTransactionDecision.Observe(it[USER_EPOCH]) }
        }
        try {
            assertFalse(observer.isCompleted)
        } finally {
            pause.release.complete(Unit)
        }
        assertTrue(withTimeout(10_000) { writer.await() }.isFailure)
        assertEquals(before.userAccessEpoch, withTimeout(10_000) { observer.await() }.value)
        assertEquals(before, o.store.load())
    }

    @Test fun aCancelledInternalWriterWhoseWriteFailsLeavesVerificationOwed() = runBlocking {
        checkCancelledWriter(failWrite = true)
    }

    @Test fun aCancelledInternalWriterWhoseWriteLandsReadsBackWhatLanded() = runBlocking {
        checkCancelledWriter(failWrite = false)
    }

    private suspend fun checkCancelledWriter(failWrite: Boolean) = kotlinx.coroutines.coroutineScope {
        val o = open()
        val before = o.store.bindOwner("u1")
        val pause = Pause()
        o.storage.pauseAfterWrite = pause
        o.storage.failAfterWrite = failWrite
        var returned = false
        val writer = launch {
            o.confirmControl()
            returned = true
        }
        withTimeout(10_000) { pause.reached.await() }
        writer.cancelAndJoin()
        assertTrue(writer.isCancelled)
        assertFalse(returned)
        val reader = async(start = CoroutineStart.UNDISPATCHED) { o.store.load() }
        pause.release.complete(Unit)

        assertEquals(before, withTimeout(10_000) { reader.await() })
        assertEquals(if (failWrite) null else "candidate", o.raw()[CONTROL])
        assertNotNull(o.raw()[BARRIER])
        assertEquals(before, disk())
    }

    private class Opened(
        val store: DataStoreAccessEpochStore,
        val dataStore: DataStore<Preferences>,
        val storage: FaultyStorage,
        val scope: CoroutineScope
    ) {
        suspend fun raw(): Preferences = dataStore.data.first()
        suspend fun confirmControl(): RecordTransactionResult<Unit> = store.transactRecord {
            RecordTransactionDecision.Confirm(it.toMutablePreferences().apply { this[CONTROL] = "candidate" }, Unit)
        }
        suspend fun failRotation() {
            storage.failAfterWrite = true
            assertTrue(runCatching { store.beginRotation(true, true) }.isFailure)
        }
    }

    private suspend fun open(): Opened {
        close()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val storage = FaultyStorage(FileStorage(StreamSerializer) { file })
        val dataStore = PreferenceDataStoreFactory.create(
            storage = storage,
            corruptionHandler = accessEpochCorruptionHandler(ids) {},
            migrations = emptyList(),
            scope = scope
        )
        return Opened(DataStoreAccessEpochStore(dataStore, ids), dataStore, storage, scope).also { opened = it }
    }

    private suspend fun close() {
        opened?.scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        opened = null
    }

    private suspend fun disk(): AccessEpochRecord = open().store.load()

    private class Pause {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class FaultyStorage(private val delegate: Storage<Preferences>) : Storage<Preferences> {
        @Volatile var writes = 0
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
                        writes += 1
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
        val CONTROL = stringPreferencesKey("seal_v1")
        val UNRELATED = stringPreferencesKey("unrelated_future_key")
        val USER_EPOCH = stringPreferencesKey("user_access_epoch")
        val BARRIER = longPreferencesKey("read_barrier")
    }
}
