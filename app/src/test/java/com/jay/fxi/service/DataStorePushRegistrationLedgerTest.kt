package com.jay.fxi.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.service.PushRegistrationState.MAY_EXIST
import com.jay.fxi.service.PushRegistrationState.OWED
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The production store and codec over a temporary preferences file.
 *
 * Reopen cases cancel and join the previous scope before creating another DataStore for the same
 * file, as `DataStoreAccessEpochStoreTest` does; they run within one JVM process.
 */
class DataStorePushRegistrationLedgerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "push_registration_ledger.preferences_pb") }
    private var scope: CoroutineScope? = null
    private var dataStore: DataStore<Preferences>? = null

    @After
    fun tearDown() = runBlocking { close() }

    /** A new store instance over the file. Closes the previous one first: DataStore refuses two. */
    private suspend fun open(): DataStorePushRegistrationLedger {
        close()
        val opened = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = opened) { file }
        scope = opened
        dataStore = store
        return DataStorePushRegistrationLedger(store, file::exists)
    }

    private suspend fun close() {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        scope = null
        dataStore = null
    }

    private suspend fun raw(): Preferences = checkNotNull(dataStore).data.first()

    private suspend fun rawEdit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        checkNotNull(dataStore).edit { block(it) }
    }

    @Test
    fun aLedgerNeverWrittenIsEmpty() = runBlocking {
        assertEquals(emptyList<PushLedgerEntry>(), open().entries())
        assertFalse("읽기가 파일을 만들었다", file.exists())
    }

    @Test
    fun entriesAndTheCounterSurviveAReopen() = runBlocking {
        val store = open()
        store.recordMayExist("a", "t1")
        store.recordMayExist("b", "t2")
        store.markOwed("a")

        val reopened = open()

        assertEquals(
            listOf(PushLedgerEntry(1, "a", "t1", OWED), PushLedgerEntry(2, "b", "t2", MAY_EXIST)),
            reopened.entries()
        )
        assertEquals(3L, reopened.recordMayExist("c", "t3")?.id)
    }

    @Test
    fun theCounterSurvivesAnEmptiedLedgerAndAReopen() = runBlocking {
        val store = open()
        val entry = checkNotNull(store.recordMayExist("a", "t1"))
        store.complete(entry)
        // The key by name: a renamed key passes every reopen test here, and loses the counter
        // written by a released build.
        assertEquals(2L, raw()[longPreferencesKey("next_id")])

        val reopened = open()

        assertEquals(emptyList<PushLedgerEntry>(), reopened.entries())
        assertEquals("비운 장부 뒤 id 가 재사용됐다", 2L, reopened.recordMayExist("a", "t1")?.id)
    }

    @Test
    fun theEntryFieldsAreStoredUnderTheirNames() = runBlocking {
        open().recordMayExist("a", "t1")

        val stored = raw()

        assertEquals("a", stored[stringPreferencesKey("entry.1.uid")])
        assertEquals("t1", stored[stringPreferencesKey("entry.1.token")])
        assertEquals("MAY_EXIST", stored[stringPreferencesKey("entry.1.state")])
    }

    @Test
    fun aStaleCompletionDoesNotRemoveAKeyRecordedAgain() = runBlocking {
        val store = open()
        val older = checkNotNull(store.recordMayExist("a", "t1"))
        store.recordMayExist("a", "t1")

        assertFalse(store.complete(older))

        assertEquals(listOf(PushLedgerEntry(2, "a", "t1", MAY_EXIST)), open().entries())
    }

    @Test
    fun anOwedKeyRefusesARecordThroughTheStore() = runBlocking {
        val store = open()
        store.recordOwed("a", "t1")

        assertEquals(null, store.recordMayExist("a", "t1"))
        assertEquals(listOf(PushLedgerEntry(1, "a", "t1", OWED)), open().entries())
    }

    @Test
    fun aFileDataStoreCannotParseThrowsRatherThanReadingEmpty() = runBlocking<Unit> {
        // Continuation bits with nothing after them: a varint that never ends.
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

        val store = open()

        assertThrows(IOException::class.java) { runBlocking { store.entries() } }
    }

    @Test
    fun aFileThatExistsButHoldsNothingIsCorrupt() = runBlocking<Unit> {
        // Zero bytes parse as an empty message. Every write here leaves next_id behind.
        file.writeBytes(ByteArray(0))

        val store = open()

        assertThrows(PushLedgerCorruptedException::class.java) { runBlocking { store.entries() } }
    }

    @Test
    fun aDecodableButBrokenLedgerRefusesReadsAndWritesAndKeepsItsBytes() = runBlocking {
        val cases: List<suspend () -> Unit> = listOf(
            { rawEdit { it.remove(stringPreferencesKey("entry.1.token")) } },
            { rawEdit { it[stringPreferencesKey("entry.1.state")] = "GONE" } },
            { rawEdit { it.remove(longPreferencesKey("next_id")) } },
            { rawEdit { it[longPreferencesKey("next_id")] = 1L } },
            { rawEdit { it[stringPreferencesKey("entry.1.extra")] = "x" } },
            { rawEdit { it[stringPreferencesKey("owner")] = "a" } },
            // Keys match by name, so this replaces the counter with text.
            { rawEdit { it.remove(longPreferencesKey("next_id")); it[stringPreferencesKey("next_id")] = "3" } },
            // A leading zero beside the real entry: read as id 1 too, it would merge into it.
            { rawEdit { leadingZeroEntry(it, "t9") } },
            // And on its own.
            {
                rawEdit {
                    listOf("uid", "token", "state").forEach { name -> it.remove(stringPreferencesKey("entry.1.$name")) }
                    leadingZeroEntry(it, "t1")
                }
            }
        )
        cases.forEachIndexed { index, damage ->
            file.delete()
            open().recordMayExist("a", "t1")
            damage()
            close()
            val before = file.readBytes()

            val store = open()

            assertThrows("손상 $index 이 읽혔다", PushLedgerCorruptedException::class.java) {
                runBlocking { store.entries() }
            }
            assertThrows("손상 $index 에 썼다", PushLedgerCorruptedException::class.java) {
                runBlocking { store.recordOwed("b", "t2") }
            }
            close()
            assertArrayEquals("손상 $index 의 파일이 바뀌었다", before, file.readBytes())
        }
    }

    private fun leadingZeroEntry(prefs: androidx.datastore.preferences.core.MutablePreferences, token: String) {
        prefs[stringPreferencesKey("entry.01.uid")] = "a"
        prefs[stringPreferencesKey("entry.01.token")] = token
        prefs[stringPreferencesKey("entry.01.state")] = "OWED"
    }

    /**
     * A read that takes its snapshot before the first write must not be judged against the file
     * that write then creates.
     *
     * The read is held inside its file check. A write started meanwhile either lands — possible only
     * when the read sits outside the write's lock, and then the file exists by the time the check
     * returns — or queues behind the read, which is what the store has to do.
     */
    @Test
    fun aReadRacingTheFirstWriteIsNotJudgedAgainstTheFileItCreates() = runBlocking {
        close()
        val opened = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val created = PreferenceDataStoreFactory.create(scope = opened) { file }
        scope = opened
        dataStore = created
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val store = DataStorePushRegistrationLedger(created) {
            if (first.getAndSet(false)) {
                checking.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            file.exists()
        }

        val read = async(Dispatchers.IO) { runCatching { store.entries() } }
        checking.await(10, TimeUnit.SECONDS)
        val write = async(Dispatchers.IO) { store.recordMayExist("a", "t1") }
        withTimeoutOrNull(1_000) { write.join() }
        release.countDown()

        assertEquals(emptyList<PushLedgerEntry>(), read.await().getOrThrow())
        assertEquals(1L, write.await()?.id)
    }
}
