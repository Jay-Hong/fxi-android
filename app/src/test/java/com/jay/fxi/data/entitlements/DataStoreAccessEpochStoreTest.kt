package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AccessOrderSequence
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    /** The production handler with its log swapped for a counter: `android.util.Log` throws here. */
    private var corruptionsDetected = 0

    private val file by lazy { File(folder.root, "access_epoch.preferences_pb") }
    private var scope: CoroutineScope? = null
    private var dataStore: DataStore<Preferences>? = null

    @After
    fun tearDown() = runBlocking { close() }

    /** A new store instance over the file. Closes the previous one first: DataStore refuses two. */
    private suspend fun open(): DataStoreAccessEpochStore {
        close()
        val opened = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // The production handler's own record-building logic, with its log replaced by a counter.
        // That the app installs a handler at all is [AccessEpochStoreWiringTest]'s job.
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = accessEpochCorruptionHandler(ids) { corruptionsDetected += 1 },
            scope = opened
        ) { file }
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

    /**
     * Slice 5 across a real reopen: the landing takes the owner off the file, and the journal the
     * next process reads still names who is owed a purge.
     */
    @Test
    fun aLandedSignOutTakesTheOwnerOffTheFile_andTheJournalKeepsIt() = runBlocking {
        val store = open()
        val bound = store.bindOwner("u1")

        store.signOut()

        assertNull("착지한 로그아웃 뒤에도 owner 가 파일에 남았다", raw()[OWNER_UID])
        val reopened = open().load()
        assertNull(reopened.ownerUid)
        assertNull(reopened.teardownOwedFor)
        val entry = reopened.pendingPurges.single()
        assertEquals("u1", entry.ownerUid)
        assertEquals(bound.userAccessEpoch, entry.userAccessEpoch)
        assertEquals(bound.krxCapabilityEpoch, entry.krxCapabilityEpoch)
        assertNotEquals(bound.userAccessEpoch, reopened.userAccessEpoch)
    }

    /**
     * Slice 6: a journal line this build cannot read still owes a purge. Dropping it would forget
     * an obligation the last process recorded, and nothing else remembers it.
     */
    @Test
    fun anUnreadableJournalEntry_survivesAsAnObligationWithAnUnknownTarget() = runBlocking {
        open()
        checkNotNull(dataStore).edit {
            it[stringPreferencesKey("pending_purge_journal")] =
                "u0|e-old-user|e-old-krx|USER\nthis-line-is-not-an-entry"
        }

        val loaded = open().load().pendingPurges

        assertEquals("both lines are obligations", 2, loaded.size)
        assertEquals(
            PendingPurge("u0", "e-old-user", "e-old-krx", setOf(PurgeScope.USER)),
            loaded[0]
        )
        assertEquals(
            PendingPurge(null, null, null, setOf(PurgeScope.USER, PurgeScope.CAPABILITY)),
            loaded[1]
        )
    }

    /** A scope name this build does not know widens the entry; narrowing it would drop that axis. */
    @Test
    fun aJournalEntryNamingAnUnknownScope_widensRatherThanDropsIt() = runBlocking {
        open()
        checkNotNull(dataStore).edit {
            it[stringPreferencesKey("pending_purge_journal")] = "u0|e-old-user|e-old-krx|USER,SOMETHING_NEW"
        }

        assertEquals(
            listOf(PendingPurge(null, null, null, setOf(PurgeScope.USER, PurgeScope.CAPABILITY))),
            open().load().pendingPurges
        )
    }

    /**
     * The writer removes the key when nothing is owed, so a key that is present but says nothing is
     * damage. Only an absent key means an empty journal.
     */
    @Test
    fun aJournalKeyHoldingNothing_isDamageRatherThanAnEmptyJournal() = runBlocking {
        val unknown = PendingPurge(null, null, null, setOf(PurgeScope.USER, PurgeScope.CAPABILITY))
        val cases = mapOf(
            "빈 문자열" to ("" to listOf(unknown)),
            "줄바꿈만" to ("\n" to listOf(unknown, unknown)),
            "정상 항목과 빈 줄" to ("u0|e1|e2|USER\n" to listOf(PendingPurge("u0", "e1", "e2", setOf(PurgeScope.USER)), unknown)),
            "빈 scope 이름 혼합" to ("u0|e1|e2|USER," to listOf(unknown))
        )
        for ((name, case) in cases) {
            val (raw, expected) = case
            open()
            checkNotNull(dataStore).edit { it[stringPreferencesKey("pending_purge_journal")] = raw }

            assertEquals(name, expected, open().load().pendingPurges)
        }
    }

    /** Control: no key at all is the ordinary "nothing owed" state, not damage. */
    @Test
    fun anAbsentJournalKey_owesNothing() = runBlocking {
        open()
        checkNotNull(dataStore).edit { it[stringPreferencesKey("owner_uid")] = "u1" }

        assertTrue(open().load().pendingPurges.isEmpty())
    }

    /**
     * Slice 6: a file DataStore cannot parse is replaced by the obligation the lost journal stood
     * for, not by an empty record — the data those entries named is still on disk.
     */
    @Test
    fun anUnparseableFile_isReplacedByAnUnknownTargetObligation() = runBlocking {
        open().bindOwner("u1")
        close()
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))

        val recovered = open().load()

        assertEquals("the handler ran exactly once", 1, corruptionsDetected)
        assertNull("recovery leaves nobody bound", recovered.ownerUid)
        assertNull(recovered.teardownOwedFor)
        assertEquals(
            "the obligation names neither an owner nor a past epoch, because neither survived",
            listOf(PendingPurge(null, null, null, setOf(PurgeScope.USER, PurgeScope.CAPABILITY))),
            recovered.pendingPurges
        )
        assertNotNull("a namespace to use from here", recovered.userAccessEpoch)
        assertNotNull(recovered.krxCapabilityEpoch)
        assertNotEquals(recovered.userAccessEpoch, recovered.krxCapabilityEpoch)
        assertFalse("the new namespace holds nothing yet", recovered.mayContainPremiumData)
        assertFalse(recovered.mayContainKrxData)
    }

    /** Recovery has to be persisted, not recomputed: a restart before any bind keeps what it wrote. */
    @Test
    fun aRecoveredFile_survivesAReopenWithNoBindInBetween() = runBlocking {
        open()
        close()
        file.writeBytes(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f))
        val recovered = open().load()

        val reopened = open().load()

        assertEquals(recovered, reopened)
        assertEquals(1, reopened.pendingPurges.size)
    }

    /** And the bind that eventually happens adds no second teardown. */
    @Test
    fun bindingAfterRecovery_takesTheNewNamespaceWithoutRotatingAgain() = runBlocking {
        open()
        close()
        file.writeBytes(byteArrayOf(0x41, 0x42, 0x43))
        val recovered = open().load()

        val bound = open().bindOwner("u2")

        assertEquals("u2", bound.ownerUid)
        assertEquals(recovered.userAccessEpoch, bound.userAccessEpoch)
        assertEquals(recovered.krxCapabilityEpoch, bound.krxCapabilityEpoch)
        assertEquals(recovered.pendingPurges, bound.pendingPurges)
    }

    /** Control: an intact file is not touched by the handler. */
    @Test
    fun anIntactFile_isLeftAlone() = runBlocking {
        val bound = open().bindOwner("u1")

        assertEquals(bound, open().load())
        assertTrue(open().load().pendingPurges.isEmpty())
        assertEquals("an intact file is not corruption", 0, corruptionsDetected)
    }

    /** Control: a first install has no file at all, and that is not corruption. */
    @Test
    fun aMissingFile_isAFreshInstallRatherThanARecovery() = runBlocking {
        assertEquals(AccessEpochRecord(), open().load())
        assertEquals("a missing file is not corruption", 0, corruptionsDetected)
    }

    /** Plan amendment 6 across a real reopen: both retirements, the journal's order and its unknown-epoch entries persist. */
    @Test
    fun anUnverifiedStartsRetirementSurvivesAReopen() = runBlocking {
        val store = open()
        store.bindOwner("u1")
        store.signOut()
        // No owner now; a marker then stands in the namespace the landing left, so the owner is unknown.
        store.markMayContainData(krx = true)
        val retired = store.retireUnverifiedStart()

        assertNull(retired.ownerUid)
        assertEquals(2, retired.pendingPurges.size)
        assertEquals("u1", retired.pendingPurges.first().ownerUid)
        assertNull(retired.pendingPurges.last().ownerUid)
        assertEquals(retired, open().load())

        // And the owner branch from a file with no epochs at all: the entry keeps them unknown.
        close()
        file.delete()
        val fresh = open()
        checkNotNull(dataStore).edit { it[OWNER_UID] = "u2" }
        val ownerRetired = fresh.retireUnverifiedStart()
        assertEquals(
            listOf(PendingPurge("u2", null, null, AccessEpochTransitions.ALL_SCOPES)),
            ownerRetired.pendingPurges
        )
        assertEquals(ownerRetired, open().load())
    }

    /** Parks the retirement just before it reaches the real store, so something can land first. */
    private class GatedStore(private val real: AccessEpochStore) : AccessEpochStore by real {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun retireUnverifiedStart(): AccessEpochRecord {
            reached.complete(Unit)
            release.await()
            return real.retireUnverifiedStart()
        }
    }

    /**
     * The coordinator reads `b` (no owner, no marker, an intent owed) and plans a drop; a marker write
     * completes before its edit runs. The real store decides the edit on the record inside its own
     * atomic edit, so the marker turns the drop into a rotation. A store or coordinator that used the
     * record read before the marker would drop the intent and rotate nothing, failing here.
     */
    @Test
    fun aMarkerWrittenAfterTheReadDecidesTheRetirementInsideTheEdit() = runBlocking {
        val real = open()
        checkNotNull(dataStore).edit {
            it[stringPreferencesKey("user_access_epoch")] = "u0"
            it[stringPreferencesKey("krx_capability_epoch")] = "k0"
            it[TEARDOWN_OWED_FOR] = "user-a"
        }
        val before = real.load()
        val gated = GatedStore(real)
        val deferred = object : UserScopePurger, CapabilityScopePurger {
            override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Deferred("not this slice")
            override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Deferred("not this slice")
        }
        val coordinatorScope = CoroutineScope(SupervisorJob())
        val coordinator = PremiumAccessCoordinator(
            source = object : EntitlementsSource {
                override suspend fun fetch(freshPremium: Boolean): EntitlementsResult = error("no query at a signed-out start")
                override suspend fun currentIdentity(): EntitlementsIdentity? = null
            },
            store = gated,
            userPurger = deferred,
            capabilityPurger = deferred,
            scope = coordinatorScope,
            clock = { 0L },
            jitter = ProbeJitter.None,
            liveFence = { null },
            orders = AccessOrderSequence()
        )
        try {
            val settling = async { coordinator.onUnverifiedStart() }
            // Bounded, so a coordinator that never reaches the store fails here instead of hanging the run.
            withTimeout(10_000) { gated.reached.await() }
            real.markMayContainData(premium = true)
            gated.release.complete(Unit)
            withTimeout(10_000) { settling.await() }.applied("경합 뒤 정산")

            val after = real.load()
            assertNull(after.teardownOwedFor)
            assertNotEquals(before.userAccessEpoch, after.userAccessEpoch)
            assertNotEquals(before.krxCapabilityEpoch, after.krxCapabilityEpoch)
            assertEquals(
                before.pendingPurges + PendingPurge(null, "u0", "k0", AccessEpochTransitions.ALL_SCOPES),
                after.pendingPurges
            )
        } finally {
            coordinatorScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private companion object {
        val TEARDOWN_OWED_FOR = stringPreferencesKey("teardown_owed_for")
        val OWNER_UID = stringPreferencesKey("owner_uid")
    }
}
