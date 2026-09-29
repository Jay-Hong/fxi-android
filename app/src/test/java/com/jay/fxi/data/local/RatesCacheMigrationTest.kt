package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S3-R3 contract (S3/decl_codex.r1.md): the rate cache moves `detected → consumer_cutover → legacy_deleted` in order;
 * a failed step records nothing and the next run repeats it; the deletion touches the two rate keys alone. The implementation
 * thread reads but does not edit this file.
 */
class RatesCacheMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }

    private fun store(name: String): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") }
    }

    private val lastBank = stringPreferencesKey("last_bank_usd-krw")
    private val other = stringPreferencesKey("something_else")

    private inner class Fixture {
        val journalStore = store("journal")
        val journal = LocalMigrationJournal(journalStore)
        val cache = store("fxi_cache")
        var failDeletes = 0
        var deletes = 0
        val migration = RatesCacheMigration(journal) {
            deletes += 1
            if (failDeletes > 0) { failDeletes -= 1; throw IOException("disk") }
            deleteLegacyRateKeys(cache)
        }
        suspend fun seed(withRates: Boolean = true) = cache.edit {
            if (withRates) {
                it[LegacyRateCacheKeys.RATES] = "[]"
                it[LegacyRateCacheKeys.RATES_TIMESTAMP] = 1L
            }
            it[lastBank] = "kb"
            it[other] = "kept"
        }
        suspend fun keys(): Set<String> = cache.data.first().asMap().keys.map { it.name }.toSet()
        suspend fun stage() = journal.stage(LegacyMigrationTarget.RATES_CACHE)
    }

    @Test
    fun M01_aRun_cutsOverOnce_thenDeletesOnlyTheRateKeys() = runBlocking {
        val f = Fixture(); f.seed()
        var cutovers = 0
        f.migration.run { cutovers += 1 }
        assertEquals(1, cutovers)
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.stage())
        assertEquals(setOf("last_bank_usd-krw", "something_else"), f.keys())
    }

    @Test
    fun M02_aFailedCutover_recordsNothingPastDetected_deletesNothing_andTheNextRunRepeatsIt() = runBlocking {
        val f = Fixture(); f.seed()
        assertThrows(IOException::class.java) { runBlocking { f.migration.run { throw IOException("commit failed") } } }
        assertEquals(LegacyMigrationStage.DETECTED, f.stage())
        assertEquals(0, f.deletes)
        assertTrue("the rate keys went before the consumer moved", f.keys().containsAll(setOf("rates", "rates_timestamp")))
        var cutovers = 0
        f.migration.run { cutovers += 1 }
        assertEquals(1, cutovers)
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.stage())
    }

    @Test
    fun M03_aFailedDeletion_keepsTheCutover_andTheNextRunOnlyDeletes() = runBlocking {
        val f = Fixture(); f.seed(); f.failDeletes = 1
        var cutovers = 0
        assertThrows(IOException::class.java) { runBlocking { f.migration.run { cutovers += 1 } } }
        assertEquals(LegacyMigrationStage.CONSUMER_CUTOVER, f.stage())
        assertTrue(f.keys().containsAll(setOf("rates", "rates_timestamp")))
        f.migration.run { cutovers += 1 }
        assertEquals("the cutover ran again after it had returned", 1, cutovers)
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.stage())
        assertEquals(setOf("last_bank_usd-krw", "something_else"), f.keys())
    }

    @Test
    fun M04_aFinishedMigration_runsNothingAgain() = runBlocking {
        val f = Fixture(); f.seed()
        f.migration.run { }
        val deletes = f.deletes
        var cutovers = 0
        f.migration.run { cutovers += 1 }
        assertEquals(0, cutovers)
        assertEquals(deletes, f.deletes)
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.stage())
    }

    @Test
    fun M05_noLegacyRates_stillFinishes_andKeepsTheRest() = runBlocking {
        val f = Fixture(); f.seed(withRates = false)
        f.migration.run { }
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.stage())
        assertEquals(setOf("last_bank_usd-krw", "something_else"), f.keys())
    }

    @Test
    fun M06_concurrentRuns_cutOverOnce() = runBlocking {
        val f = Fixture(); f.seed()
        val gate = CompletableDeferred<Unit>()
        var cutovers = 0
        val runs = List(3) { async(Dispatchers.Default) { f.migration.run { cutovers += 1; gate.await() } } }
        Thread.sleep(200)
        gate.complete(Unit)
        runs.awaitAll()
        assertEquals(1, cutovers)
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.stage())
    }

    @Test
    fun M07_theDeletion_removesExactlyTheTwoRateKeys() = runBlocking {
        val f = Fixture(); f.seed()
        deleteLegacyRateKeys(f.cache)
        assertFalse(f.keys().contains("rates"))
        assertFalse(f.keys().contains("rates_timestamp"))
        assertEquals(setOf("last_bank_usd-krw", "something_else"), f.keys())
        deleteLegacyRateKeys(f.cache)
        assertEquals("a second deletion changed something", setOf("last_bank_usd-krw", "something_else"), f.keys())
    }
}
