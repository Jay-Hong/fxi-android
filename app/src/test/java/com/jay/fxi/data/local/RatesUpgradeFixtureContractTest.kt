package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.control.ControlRecordRead
import com.jay.fxi.data.entitlements.control.ControlRestartModel
import com.jay.fxi.data.entitlements.control.RestartIdentity
import com.jay.fxi.data.entitlements.control.RestartRead
import com.jay.fxi.data.entitlements.control.RestartStep
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotReader
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned R4-c C0 contract (계획 S3 "업그레이드 fixture 먼저", R4c/design_codex.r1.md C0, capture plan r2 approved).
 * Input: `fixtures/v122/fxi_cache.v122.preferences_pb` — a source-reproduced synthetic input written by the v1.2.2 (6cea639)
 * CacheService on an Android runtime (see its manifest); not a release build. It verifies the input format and the R3
 * migration's key handling only; release-binary, UI-path and full upgrade verification stay open (개정 17, before P3-i).
 * The raw file is copied byte for byte into a temp file and never re-encoded in the resource. A restart closes every
 * DataStore and reopens the same files (Codex C0 commit review r1: C0-4 restart, C0-5 seven control keys absent).
 */
class RatesUpgradeFixtureContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }

    private val raw: ByteArray = checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/v122/fxi_cache.v122.preferences_pb")).readBytes()
    private val lastUsd = stringPreferencesKey("last_bank_usd-krw")
    private val lastJpy = stringPreferencesKey("last_bank_jpy-krw")

    private fun store(name: String, bytes: ByteArray?, scope: CoroutineScope): DataStore<Preferences> {
        val file = File(folder.root, "$name.preferences_pb")
        if (bytes != null) file.writeBytes(bytes)
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    /** A journal DataStore whose next write recording [failOn] throws after the step's work ran: the interrupted record. */
    private class JournalInterrupt(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        var failOn: String? = null
        override val data: Flow<Preferences> get() = real.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = real.updateData { t ->
            val next = transform(t)
            val f = failOn
            if (f != null && next.asMap().values.any { it == f }) { failOn = null; throw IOException("interrupted") }
            next
        }
    }

    /** One process lifetime over the upgrade's files; [bytes] null reopens what the previous lifetime left. */
    private inner class Upgrade(bytes: ByteArray? = raw) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val cache = store("fxi_cache", bytes, scope)
        val journalStore = JournalInterrupt(store("journal", null, scope))
        val journal = LocalMigrationJournal(journalStore)
        var failDeletes = 0
        var deletes = 0
        val migration = RatesCacheMigration(journal) {
            deletes += 1
            if (failDeletes > 0) { failDeletes -= 1; throw IOException("disk") }
            deleteLegacyRateKeys(cache)
        }
        suspend fun keys() = cache.data.first().asMap().mapKeys { it.key.name }
        suspend fun stage() = journal.stage(LegacyMigrationTarget.RATES_CACHE)
        /** Ends this lifetime (every DataStore closed) and opens the next one over the same files. */
        suspend fun restart(): Upgrade { scope.coroutineContext[Job]!!.cancelAndJoin(); return Upgrade(null) }
    }

    @Test fun C0_1_theCapturedInputIsTheManifestsFile_andReadsAsV122Wrote() = runBlocking {
        val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        assertEquals("C0-1 provenance", "d91a05a9f16aaf63f35b8474859315cd03d6443e7aeaa77b8bb20ebc5ed78bb2", sha)
        val keys = Upgrade().keys()
        assertEquals("C0-1 key set", setOf("rates", "rates_timestamp", "last_bank_usd-krw", "last_bank_jpy-krw"), keys.keys)
        assertTrue("C0-1 rates is JSON text", keys["rates"] is String)
        assertEquals("C0-1 three v1 rows", 3, Json.parseToJsonElement(keys["rates"] as String).jsonArray.size)
        assertEquals("C0-1 v1 row fields", setOf("currency", "bank", "rate", "timestamp"),
            Json.parseToJsonElement(keys["rates"] as String).jsonArray.first().jsonObject.keys)
        assertTrue("C0-1 rates_timestamp is Long", keys["rates_timestamp"] is Long)
        assertEquals("C0-1 last_bank", "kb" to "hana", keys["last_bank_usd-krw"] to keys["last_bank_jpy-krw"])
    }

    @Test fun C0_2_aFailedCutover_keepsEveryKey_andRecordsNoProgressPastDetected() = runBlocking {
        val u = Upgrade(); val before = u.keys()
        assertThrows(IOException::class.java) { runBlocking { u.migration.run { throw IOException("cutover") } } }
        assertEquals("C0-2 keys", before, u.keys())
        assertEquals("C0-2 stage", LegacyMigrationStage.DETECTED, u.stage())
    }

    @Test fun C0_3_aSuccessfulCutover_removesTheTwoRateKeysOnly() = runBlocking {
        val u = Upgrade(); val before = u.keys()
        u.migration.run { }
        assertEquals("C0-3 keys", before - setOf("rates", "rates_timestamp"), u.keys())
        assertEquals("C0-3 stage", LegacyMigrationStage.LEGACY_DELETED, u.stage())
    }

    @Test fun C0_4_aFailedDeletion_isRepeatedAloneAfterARestart_andLastBankStays() = runBlocking {
        val u = Upgrade(); u.failDeletes = 1; var cutovers = 0
        assertThrows(IOException::class.java) { runBlocking { u.migration.run { cutovers += 1 } } }
        assertEquals("C0-4 stage after failure", LegacyMigrationStage.CONSUMER_CUTOVER, u.stage())
        assertTrue("C0-4 rate keys still there", "rates" in u.keys())
        val next = u.restart()
        next.migration.run { cutovers += 1 }
        assertEquals("C0-4 cutover ran once", 1, cutovers)
        assertEquals("C0-4 last_bank kept", setOf("last_bank_usd-krw", "last_bank_jpy-krw"), next.keys().keys)
        assertEquals("C0-4 stage", LegacyMigrationStage.LEGACY_DELETED, next.stage())
    }

    @Test fun C0_4b_aDeletionInterruptedBeforeItsRecord_isRepeatedAloneAfterARestart() = runBlocking {
        val u = Upgrade(); var cutovers = 0
        u.journalStore.failOn = LegacyMigrationStage.LEGACY_DELETED.name
        assertThrows(IOException::class.java) { runBlocking { u.migration.run { cutovers += 1 } } }
        assertEquals("C0-4b keys already gone", setOf("last_bank_usd-krw", "last_bank_jpy-krw"), u.keys().keys)
        assertEquals("C0-4b not reported done", LegacyMigrationStage.CONSUMER_CUTOVER, u.stage())
        val next = u.restart()
        assertEquals("C0-4b the record did not survive", LegacyMigrationStage.CONSUMER_CUTOVER, next.stage())
        next.migration.run { cutovers += 1 }
        assertEquals("C0-4b cutover ran once", 1, cutovers)
        assertEquals("C0-4b deletion repeated", 1, next.deletes)
        assertEquals("C0-4b last_bank kept", mapOf<String, Any>("last_bank_usd-krw" to "kb", "last_bank_jpy-krw" to "hana"), next.keys())
        assertEquals("C0-4b stage", LegacyMigrationStage.LEGACY_DELETED, next.stage())
        next.migration.run { cutovers += 1 }
        assertEquals("C0-4b a finished target runs nothing", 1 to 1, cutovers to next.deletes)
    }

    /**
     * The captured v1.2.2 install had no access-epoch store and no migration journal (manifest files_dir). Opening that
     * absent store reads all seven control keys absent; the restart decision treats it as legacy recovery for every
     * identity, never as a clean record, and the journal records nothing as already migrated.
     */
    @Test fun C0_5_thePureV1Install_hasNoControlKeys_andThatIsNotACleanRecord() = runBlocking {
        val manifest = Json.parseToJsonElement(checkNotNull(javaClass.classLoader!!
            .getResourceAsStream("fixtures/v122/fxi_cache.v122.manifest.json")).reader().readText()).jsonObject
        val files = manifest.getValue("files_dir").jsonArray.map { it.jsonPrimitive.content }.filter { it.startsWith("-") }
        assertEquals("C0-5 only fxi_cache was on disk", listOf("fxi_cache.preferences_pb"), files.map { it.substringAfterLast(' ') })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val absent = store("fxi_access_epoch", null, scope).data.first()
        assertTrue("C0-5 nothing in the absent store", absent.asMap().isEmpty())
        val snapshot = PurgeControlSnapshotReader().read(absent)
        assertTrue("C0-5 control needs migration or recovery, got ${snapshot.control}",
            snapshot.control is ControlRecordRead.MigrationOrRecoveryRequired)
        val expected = mapOf(
            RestartIdentity.SAME_UID to RestartStep.LegacySchemaAbsentRecovery,
            RestartIdentity.NO_UID to RestartStep.IdentityWithLegacyRecovery(RestartIdentity.NO_UID),
            RestartIdentity.UID_CHANGED to RestartStep.IdentityWithLegacyRecovery(RestartIdentity.UID_CHANGED),
            RestartIdentity.DELETION_PENDING to RestartStep.IdentityFirst(RestartIdentity.DELETION_PENDING))
        for (id in RestartIdentity.entries)
            assertEquals("C0-5 $id: legacy recovery, never a clean record", expected.getValue(id),
                ControlRestartModel.decide(RestartRead.Confirmed(snapshot), id))
        val u = Upgrade()
        for (target in LegacyMigrationTarget.entries) assertEquals("C0-5 $target not recorded", null, u.journal.stage(target))
    }

    /** C0-3 derived input (raw + one unrelated key, written through DataStore): an unrelated key survives the migration too. */
    @Test fun C0_3b_derived_anUnrelatedKeySurvives() = runBlocking {
        val u = Upgrade()
        u.cache.edit { it[stringPreferencesKey("unrelated_key")] = "kept" }
        u.migration.run { }
        assertEquals("C0-3b", "kept", u.keys()["unrelated_key"])
    }
}
