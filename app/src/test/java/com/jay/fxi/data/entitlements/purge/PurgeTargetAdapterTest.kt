package com.jay.fxi.data.entitlements.purge

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The two deletions this slice can actually perform, over real files. */
class PurgeTargetAdapterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun store(name: String): DataStore<Preferences> {
        val opened = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = opened
        return PreferenceDataStoreFactory.create(scope = opened) { File(folder.root, name) }
    }

    private val ownerKey = stringPreferencesKey("owner_uid")
    private val payloadKey = stringPreferencesKey("payload")

    private fun request(owner: String?) = PurgeRequest(
        target = PurgeManifest.byId("datastore:fxi_user_intent")!!,
        scope = PurgeScope.USER,
        cause = PurgeCause.ACCOUNT_DELETION,
        namespace = PurgeNamespace(
            ownerUid = owner,
            currentUserAccessEpoch = "live",
            currentKrxCapabilityEpoch = null,
            pending = PendingPurge(owner, "old", null, setOf(PurgeScope.USER))
        )
    )

    /** The retired owner's stamp is there, so the content is theirs and it goes. */
    @Test
    fun `an owner-stamped store is cleared for its own owner`() = runBlocking {
        val data = store("intent.preferences_pb")
        data.edit { it[ownerKey] = "u1"; it[payloadKey] = "order" }
        val adapter = OwnerStampedPreferencesAdapter(data, "owner_uid")

        assertEquals(TargetOutcome.Removed, adapter.purge(request("u1")))
        assertTrue(data.data.first().asMap().isEmpty())
    }

    /**
     * Somebody else's stamp means the retired owner's data is not here.
     *
     * This is A's cleanup arriving while B owns the store. A *later session of A* carries the same
     * UID stamp and is not told apart here — that separation is the lifetime and writer-exclusion
     * contract P3 brings.
     */
    @Test
    fun `another owner's content is left alone`() = runBlocking {
        val data = store("intent.preferences_pb")
        data.edit { it[ownerKey] = "u2"; it[payloadKey] = "order" }
        val adapter = OwnerStampedPreferencesAdapter(data, "owner_uid")

        assertEquals(TargetOutcome.NothingToRemove, adapter.purge(request("u1")))
        assertEquals("다른 소유자의 선호를 지웠다", "order", data.data.first()[payloadKey])
    }

    /** An empty store is the ordinary state after the first run, not a failure. */
    @Test
    fun `an empty store has nothing to remove`() = runBlocking {
        val adapter = OwnerStampedPreferencesAdapter(store("intent.preferences_pb"), "owner_uid")

        assertEquals(TargetOutcome.NothingToRemove, adapter.purge(request("u1")))
    }

    /** An entry whose owner could not be narrowed may not clear a store that belongs to somebody. */
    @Test
    fun `an unnarrowed owner refuses`() = runBlocking {
        val data = store("intent.preferences_pb")
        data.edit { it[ownerKey] = "u1" }
        val adapter = OwnerStampedPreferencesAdapter(data, "owner_uid")

        val outcome = adapter.purge(request(null))

        assertTrue(outcome is TargetOutcome.Failed)
        assertEquals("u1", data.data.first()[ownerKey])
    }

    /** A store with no stamp cannot prove whose content it is, so it refuses rather than guesses. */
    @Test
    fun `a store without an owner stamp refuses`() = runBlocking {
        val adapter = OwnerStampedPreferencesAdapter(store("intent.preferences_pb"), null)

        assertTrue(adapter.purge(request("u1")) is TargetOutcome.Failed)
    }

    /** Content with no stamp proves nothing about whose it is, so it refuses rather than reports done. */
    @Test
    fun `a non-empty store missing the configured owner stamp refuses`() = runBlocking {
        val data = store("intent.preferences_pb")
        data.edit { it[payloadKey] = "order" }
        val adapter = OwnerStampedPreferencesAdapter(data, "owner_uid")

        assertTrue(adapter.purge(request("u1")) is TargetOutcome.Failed)
        assertEquals("도장 없는 내용을 지웠다", "order", data.data.first()[payloadKey])
    }

    /**
     * The stamp is read in the same edit that clears, so a write that lands between cannot be lost.
     *
     * Reading first and clearing second would delete the content this store gained in between —
     * here B's — while believing it was still A's.
     */
    @Test
    fun `an owner change before the edit preserves the new owner's content`() = runBlocking {
        val backing = store("intent.preferences_pb")
        backing.edit {
            it[ownerKey] = "u1"
            it[payloadKey] = "old"
        }
        val switching = object : DataStore<Preferences> {
            override val data = backing.data

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences
            ): Preferences {
                backing.edit {
                    it[ownerKey] = "u2"
                    it[payloadKey] = "new"
                }
                return backing.updateData(transform)
            }
        }
        val adapter = OwnerStampedPreferencesAdapter(switching, "owner_uid")

        assertEquals(TargetOutcome.NothingToRemove, adapter.purge(request("u1")))
        assertEquals("u2", backing.data.first()[ownerKey])
        assertEquals("새 소유자의 내용을 지웠다", "new", backing.data.first()[payloadKey])
    }

    /** Files go, and an absent file is not a failure. */
    @Test
    fun `files are deleted and absence is fine`() = runBlocking {
        val present = File(folder.root, "graph_cache_v1_usd.json").apply { writeText("{}") }
        val absent = File(folder.root, "graph_cache_v1_jpy.json")
        val adapter = FileTargetAdapter { listOf(present, absent) }

        assertEquals(TargetOutcome.Removed, adapter.purge(request("u1")))
        assertTrue(!present.exists())

        assertEquals(TargetOutcome.NothingToRemove, adapter.purge(request("u1")))
    }

    /**
     * One file going does not buy off the one that stayed.
     *
     * The sweep names several files, and reporting `Removed` because any of them went would drop
     * the journal entry while a file the user asked to be gone is still readable on the phone.
     */
    @Test
    fun `a refusal is not hidden by a file that did go`() = runBlocking {
        val gone = File(folder.root, "graph_cache_v1_usd.json").apply { writeText("{}") }
        val stubborn = File(folder.root, "graph_cache_v1_eur.json").apply { mkdir() }
        File(stubborn, "child").writeText("x")
        val adapter = FileTargetAdapter { listOf(gone, stubborn) }

        val outcome = adapter.purge(request("u1"))

        assertTrue("일부 성공이 거부를 가렸다", outcome is TargetOutcome.Failed)
        assertEquals(
            "not deleted: graph_cache_v1_eur.json",
            (outcome as TargetOutcome.Failed).reason
        )
        assertTrue("지워진 파일은 지워진 채로 남아야 한다", !gone.exists())
    }

    /**
     * A refusal is a failure, because `File.delete()` answers `false` without throwing.
     *
     * A non-empty directory is how that is produced here; the point is the report, not the cause.
     */
    @Test
    fun `a refused delete is a failure`() = runBlocking {
        val stubborn = File(folder.root, "graph_cache_v1_eur.json").apply { mkdir() }
        File(stubborn, "child").writeText("x")
        val adapter = FileTargetAdapter { listOf(stubborn) }

        val outcome = adapter.purge(request("u1"))

        assertTrue("지우지 못한 파일을 성공으로 보고했다", outcome is TargetOutcome.Failed)
        assertTrue(stubborn.exists())
    }
}
