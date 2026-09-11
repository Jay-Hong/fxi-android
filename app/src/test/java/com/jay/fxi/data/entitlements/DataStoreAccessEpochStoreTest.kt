package com.jay.fxi.data.entitlements

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the production store and codec using a temporary preferences file.
 *
 * The record fakes bypass this codec. These tests check returned records, named preferences
 * and persistence across store recreation. Reopen cases cancel and join the previous scope
 * before creating another DataStore for the same file; they run within one JVM process.
 */
class DataStoreAccessEpochStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0
    private val ids = EpochIdGenerator { "epoch-${counter++}" }

    private val file by lazy { File(folder.root, "access_epoch.preferences_pb") }
    private var scope: CoroutineScope? = null
    private var dataStore: DataStore<Preferences>? = null

    @After
    fun tearDown() = runBlocking { close() }

    /** A new store instance over the file. Closes the previous one first: DataStore refuses two. */
    private suspend fun open(): DataStoreAccessEpochStore {
        close()
        val opened = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = opened) { file }
        scope = opened
        dataStore = store
        return DataStoreAccessEpochStore(store, ids)
    }

    private suspend fun close() {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        scope = null
        dataStore = null
    }

    private suspend fun raw(): Preferences = checkNotNull(dataStore).data.first()

    @Test
    fun anOwedTeardownSurvivesAReopen() = runBlocking {
        val store = open()
        store.bindOwner("u1")
        val decided = store.beginSignOut("u1")
        // The key by name: the other tests only check its absence, which a renamed key also passes.
        assertEquals("u1", raw()[TEARDOWN_OWED_FOR])

        val reopened = open().load()

        assertEquals("u1", reopened.teardownOwedFor)
        assertEquals(decided, reopened)
    }

    /** The reason the marker is persisted at all. */
    @Test
    fun aSameUidRebindAfterAReopenPaysTheOwedTeardown() = runBlocking {
        val before = open().run {
            bindOwner("u1")
            markMayContainData(premium = true, krx = true)
            beginSignOut("u1")
        }

        val rebound = open().bindOwner("u1")

        assertNotEquals(
            "같은 uid 가 떠나려던 namespace 를 물려받았다",
            before.userAccessEpoch,
            rebound.userAccessEpoch
        )
        assertNotEquals(before.krxCapabilityEpoch, rebound.krxCapabilityEpoch)
        assertEquals(
            listOf(
                PendingPurge(
                    ownerUid = "u1",
                    userAccessEpoch = before.userAccessEpoch,
                    krxCapabilityEpoch = before.krxCapabilityEpoch,
                    scopes = setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
                )
            ),
            rebound.pendingPurges
        )
        assertNull(rebound.teardownOwedFor)
        assertEquals(rebound, open().load())
    }

    @Test
    fun aLandedSignOutRemovesTheMarkerFromTheFile() = runBlocking {
        val store = open()
        store.bindOwner("u1")
        store.beginSignOut("u1")

        store.signOut()

        assertNull("착지한 로그아웃의 표식이 파일에 남았다", raw()[TEARDOWN_OWED_FOR])
        assertNull(open().load().teardownOwedFor)
    }

    @Test
    fun aNonOwnerRequestWritesNoMarker() = runBlocking {
        val store = open()
        val bound = store.bindOwner("u1")

        val returned = store.beginSignOut("u2")

        assertEquals(bound, returned)
        assertNull(raw()[TEARDOWN_OWED_FOR])
    }

    /** Includes a KRX-only journal entry, whose user epoch is encoded as an empty field. */
    @Test
    fun everyExistingFieldStillRoundTrips() = runBlocking {
        val written = open().run {
            bindOwner("u1")
            markMayContainData(premium = true, krx = true)
            beginRotation(rotateUser = false, rotateKrx = true)
        }
        assertEquals(setOf(PurgeScope.CAPABILITY), written.pendingPurges.single().scopes)
        assertNull(written.pendingPurges.single().userAccessEpoch)

        assertEquals(written, open().load())
    }

    /**
     * Every key but the marker's is spelled out here rather than borrowed from the store; the
     * marker's is pinned in [anOwedTeardownSurvivesAReopen]. Renaming one would orphan what is
     * already on disk, and that should fail here rather than on a device.
     */
    @Test
    fun aFileWrittenBeforeTheMarkerExistedOwesNothing() = runBlocking {
        open()
        checkNotNull(dataStore).edit {
            it[stringPreferencesKey("owner_uid")] = "u1"
            it[stringPreferencesKey("user_access_epoch")] = "e-user"
            it[stringPreferencesKey("krx_capability_epoch")] = "e-krx"
            it[booleanPreferencesKey("may_contain_premium_data")] = true
            it[booleanPreferencesKey("may_contain_krx_data")] = true
            it[stringPreferencesKey("pending_purge_journal")] = "u0|e-old-user|e-old-krx|CAPABILITY,USER"
        }

        assertEquals(
            AccessEpochRecord(
                ownerUid = "u1",
                userAccessEpoch = "e-user",
                krxCapabilityEpoch = "e-krx",
                mayContainPremiumData = true,
                mayContainKrxData = true,
                pendingPurges = listOf(
                    PendingPurge(
                        ownerUid = "u0",
                        userAccessEpoch = "e-old-user",
                        krxCapabilityEpoch = "e-old-krx",
                        scopes = setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
                    )
                )
            ),
            open().load()
        )
    }

    private companion object {
        val TEARDOWN_OWED_FOR = stringPreferencesKey("teardown_owed_for")
    }
}
