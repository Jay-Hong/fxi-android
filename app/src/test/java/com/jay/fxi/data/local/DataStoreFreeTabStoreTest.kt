package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.domain.model.FreeTab
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned L-4f contract for the last-tab store (L4f/decl_codex.r1.md): the last confirmed tab is kept **per UID** in the
 * same `fxi_free_tab` file — A→B→A reopens A on A's tab — one value per UID for free and subscriber surfaces alike, 달러 when
 * nothing is stored or the value is unknown. The v1-of-this-store pair (`owner_uid`, `last_tab`) is read for its owner and moved
 * on the first write of any UID. The implementation thread reads but does not edit this file.
 */
class DataStoreFreeTabStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "free_tab.preferences_pb") }
    private var scope: CoroutineScope? = null
    private var dataStore: DataStore<Preferences>? = null

    @After
    fun tearDown() = runBlocking { close() }

    private suspend fun open(): DataStoreFreeTabStore {
        close()
        val opened = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = opened) { file }
        scope = opened
        dataStore = store
        return DataStoreFreeTabStore(store)
    }

    private suspend fun close() {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        scope = null
        dataStore = null
    }

    private suspend fun raw(): Preferences = checkNotNull(dataStore).data.first()
    private val ownerKey = stringPreferencesKey("owner_uid")
    private val tabKey = stringPreferencesKey("last_tab")

    /** The pre-L-4f single pair, as a device that ran the earlier build holds it. */
    private suspend fun legacyPair(uid: String, value: String) {
        open()
        checkNotNull(dataStore).edit { it[ownerKey] = uid; it[tabKey] = value }
    }

    @Test
    fun S01_empty_readsDollar_andWritesNothing() = runBlocking {
        val store = open()
        assertEquals(FreeTab.USD, store.lastTab("A"))
        assertFalse("reading wrote the file", file.exists() && file.length() > 0)
    }

    @Test
    fun S02_eachUidKeepsItsOwnTab_acrossAReopen() = runBlocking {
        val store = open()
        store.remember("A", FreeTab.TETHER)
        store.remember("B", FreeTab.EUR)
        val reopened = open()
        assertEquals(FreeTab.TETHER, reopened.lastTab("A"))
        assertEquals(FreeTab.EUR, reopened.lastTab("B"))
    }

    @Test
    fun S03_rememberingOneUid_leavesTheOther() = runBlocking {
        val store = open()
        store.remember("A", FreeTab.TETHER); store.remember("B", FreeTab.EUR)
        store.remember("A", FreeTab.NEWS)
        val reopened = open()
        assertEquals(FreeTab.NEWS, reopened.lastTab("A"))
        assertEquals(FreeTab.EUR, reopened.lastTab("B"))
    }

    @Test
    fun S04_theLegacyPair_isReadForItsOwnerOnly() = runBlocking {
        legacyPair("A", "JPY")
        val store = open()
        assertEquals(FreeTab.JPY, store.lastTab("A"))
        assertEquals(FreeTab.USD, store.lastTab("B"))
    }

    @Test
    fun S05_anotherUidsFirstWrite_movesTheLegacyPair_forItsOwner() = runBlocking {
        legacyPair("A", "JPY")
        open().remember("B", FreeTab.EUR)
        val reopened = open()
        assertEquals("the legacy owner's choice survives another UID's first write", FreeTab.JPY, reopened.lastTab("A"))
        assertEquals(FreeTab.EUR, reopened.lastTab("B"))
        assertFalse("the legacy owner key is gone", raw().contains(ownerKey))
        assertFalse("the legacy tab key is gone", raw().contains(tabKey))
    }

    @Test
    fun S06_anUnknownStoredValue_readsDollar_andOthersKeepTheirs() = runBlocking {
        open().remember("B", FreeTab.EUR)
        checkNotNull(dataStore).edit { it[ownerKey] = "A"; it[tabKey] = "SOMETHING_ELSE" }
        val store = open()
        assertEquals(FreeTab.USD, store.lastTab("A"))
        assertEquals(FreeTab.EUR, store.lastTab("B"))
        // The same for an unknown value under A's own per-UID key, found by its value so the key name stays unfixed.
        store.remember("A", FreeTab.JPY)
        val aKey = raw().asMap().entries.single { it.key != ownerKey && it.key != tabKey && it.value == "JPY" }.key
        @Suppress("UNCHECKED_CAST")
        checkNotNull(dataStore).edit { it[aKey as Preferences.Key<String>] = "SOMETHING_ELSE" }
        val reopened = open()
        assertEquals("an unknown per-UID value reads as 달러", FreeTab.USD, reopened.lastTab("A"))
        assertEquals(FreeTab.EUR, reopened.lastTab("B"))
    }

    @Test
    fun S07_aBlankUid_isRefused_andNothingChanges() = runBlocking<Unit> {
        val store = open()
        store.remember("A", FreeTab.TETHER)
        val bytes = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.lastTab(" ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.remember("", FreeTab.EUR) } }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun S08_oneValuePerUid_forFreeAndSubscriberAlike() = runBlocking {
        // The free surface remembers through this store; the subscriber restore reads the same value (no per-tier key).
        open().remember("A", FreeTab.TETHER)
        assertEquals(FreeTab.TETHER, open().lastTab("A"))
    }
}
