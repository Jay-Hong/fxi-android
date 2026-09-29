package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.local.LegacyMigrationStage.CONSUMER_CUTOVER
import com.jay.fxi.data.local.LegacyMigrationStage.DETECTED
import com.jay.fxi.data.local.LegacyMigrationStage.LEGACY_DELETED
import com.jay.fxi.data.local.LegacyMigrationTarget.GRAPH_CACHE_FILES
import com.jay.fxi.data.local.LegacyMigrationTarget.RATES_CACHE
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S1 contract for the `ANDROID_V2_PLAN.md §9.1` migration journal (next-slice consensus
 * next_slice_codex.r1.md; contract review r1): per target, `detected → consumer_cutover → legacy_deleted` persisted and
 * monotonic, one step at a time. `cutover` and `deleteLegacy` take the step's work — the new store's commit and consumer switch,
 * the legacy deletion — and record the stage only after the work returns: a failed work records nothing and the next run repeats
 * it (the plan's crash re-run). A stage already recorded neither runs the work nor writes. Targets are independent, a storage
 * failure records nothing, and the on-disk encoding is fixed. The journal does not touch legacy data outside the work it is
 * given. Backup exclusion is held by `BackupRulesLedgerTest`.
 * The implementation thread reads but does not edit this file.
 */
class LocalMigrationJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "migration_journal.preferences_pb") }
    private var scope: CoroutineScope? = null
    private var dataStore: DataStore<Preferences>? = null

    @After
    fun tearDown() = runBlocking { close() }

    /** A new journal over the file. Closes the previous DataStore first: DataStore refuses two over one file. */
    private suspend fun open(): LocalMigrationJournal {
        close()
        val opened = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = opened) { file }
        scope = opened
        dataStore = store
        return LocalMigrationJournal(store)
    }

    private suspend fun close() {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        scope = null
        dataStore = null
    }

    private suspend fun raw(): Preferences = checkNotNull(dataStore).data.first()

    private val ran = mutableListOf<String>()
    private fun work(name: String): suspend () -> Unit = { ran += name }
    private suspend fun LocalMigrationJournal.all(target: LegacyMigrationTarget) {
        detect(target); cutover(target, work("commit")); deleteLegacy(target, work("delete"))
    }

    @Test
    fun J01_neverWritten_everyTargetIsUnrecorded() = runBlocking {
        val journal = open()
        LegacyMigrationTarget.entries.forEach { assertNull("$it", journal.stage(it)) }
    }

    @Test
    fun J02_theThreeSteps_workRunsBeforeEachRecord() = runBlocking {
        val journal = open()
        assertEquals(MigrationAdvance.Advanced, journal.detect(RATES_CACHE))
        assertEquals(DETECTED, journal.stage(RATES_CACHE))
        var stageSeenByWork: LegacyMigrationStage? = null
        assertEquals(MigrationAdvance.Advanced, journal.cutover(RATES_CACHE) { stageSeenByWork = journal.stage(RATES_CACHE); ran += "commit" })
        assertEquals("the cutover is recorded after its work, not before", DETECTED, stageSeenByWork)
        assertEquals(CONSUMER_CUTOVER, journal.stage(RATES_CACHE))
        assertEquals(MigrationAdvance.Advanced, journal.deleteLegacy(RATES_CACHE) { stageSeenByWork = journal.stage(RATES_CACHE); ran += "delete" })
        assertEquals("the deletion is recorded after its work", CONSUMER_CUTOVER, stageSeenByWork)
        assertEquals(LEGACY_DELETED, journal.stage(RATES_CACHE))
        assertEquals(listOf("commit", "delete"), ran)
    }

    @Test
    fun J03_theRecordedStageAgain_alreadyThere_noWorkNoWrite() = runBlocking {
        val journal = open()
        journal.detect(RATES_CACHE)
        var bytes = file.readBytes()
        assertEquals(MigrationAdvance.AlreadyThere, journal.detect(RATES_CACHE))
        assertArrayEquals("detect again: file unchanged", bytes, file.readBytes())
        journal.cutover(RATES_CACHE, work("commit")); bytes = file.readBytes()
        assertEquals(MigrationAdvance.AlreadyThere, journal.cutover(RATES_CACHE, work("commit again")))
        assertArrayEquals("cutover again: file unchanged", bytes, file.readBytes())
        journal.deleteLegacy(RATES_CACHE, work("delete")); bytes = file.readBytes()
        assertEquals(MigrationAdvance.AlreadyThere, journal.deleteLegacy(RATES_CACHE, work("delete again")))
        assertArrayEquals("delete again: file unchanged", bytes, file.readBytes())
        assertEquals("a recorded step does not repeat its work", listOf("commit", "delete"), ran)
    }

    @Test
    fun J04_skippingAhead_skipped_noWorkNothingRecorded() = runBlocking {
        val journal = open()
        assertEquals(MigrationAdvance.Skipped(null), journal.cutover(RATES_CACHE, work("commit")))
        assertEquals(MigrationAdvance.Skipped(null), journal.deleteLegacy(RATES_CACHE, work("delete")))
        assertNull(journal.stage(RATES_CACHE))
        journal.detect(RATES_CACHE)
        assertEquals("legacy deleted before the cutover", MigrationAdvance.Skipped(DETECTED), journal.deleteLegacy(RATES_CACHE, work("delete")))
        assertEquals(DETECTED, journal.stage(RATES_CACHE))
        assertEquals("no skipped step ran its work", emptyList<String>(), ran)
    }

    @Test
    fun J05_goingBack_backward_noWorkStageKept() = runBlocking {
        val journal = open()
        journal.detect(RATES_CACHE); journal.cutover(RATES_CACHE, work("commit"))
        assertEquals(MigrationAdvance.Backward(CONSUMER_CUTOVER), journal.detect(RATES_CACHE))
        journal.deleteLegacy(RATES_CACHE, work("delete"))
        assertEquals(MigrationAdvance.Backward(LEGACY_DELETED), journal.cutover(RATES_CACHE, work("commit late")))
        assertEquals(MigrationAdvance.Backward(LEGACY_DELETED), journal.detect(RATES_CACHE))
        assertEquals(LEGACY_DELETED, journal.stage(RATES_CACHE))
        assertEquals(listOf("commit", "delete"), ran)
    }

    @Test
    fun J06_targetsAreIndependent() = runBlocking {
        val journal = open()
        journal.detect(RATES_CACHE); journal.cutover(RATES_CACHE, work("commit"))
        LegacyMigrationTarget.entries.filter { it != RATES_CACHE }.forEach { assertNull("$it", journal.stage(it)) }
        assertEquals("another target starts from nothing", MigrationAdvance.Skipped(null), journal.cutover(GRAPH_CACHE_FILES, work("other")))
        assertEquals(MigrationAdvance.Advanced, journal.detect(GRAPH_CACHE_FILES))
        assertEquals(CONSUMER_CUTOVER, journal.stage(RATES_CACHE))
        assertEquals(DETECTED, journal.stage(GRAPH_CACHE_FILES))
    }

    @Test
    fun J07_eachStageSurvivesAReopen_andTheReRunIsIdempotent() = runBlocking {
        open().detect(RATES_CACHE)
        assertEquals(DETECTED, open().stage(RATES_CACHE))
        assertEquals(MigrationAdvance.AlreadyThere, open().detect(RATES_CACHE))
        open().cutover(RATES_CACHE, work("commit"))
        assertEquals(CONSUMER_CUTOVER, open().stage(RATES_CACHE))
        assertEquals(MigrationAdvance.AlreadyThere, open().cutover(RATES_CACHE, work("commit again")))
        open().deleteLegacy(RATES_CACHE, work("delete"))
        assertEquals(LEGACY_DELETED, open().stage(RATES_CACHE))
        assertEquals(MigrationAdvance.AlreadyThere, open().deleteLegacy(RATES_CACHE, work("delete again")))
        assertEquals(listOf("commit", "delete"), ran)
    }

    @Test
    fun J08_theOnDiskEncodingIsFixed() = runBlocking {
        val journal = open()
        journal.detect(RATES_CACHE); journal.cutover(RATES_CACHE, work("commit"))
        journal.detect(GRAPH_CACHE_FILES)
        assertEquals(mapOf("rates_cache" to "CONSUMER_CUTOVER", "graph_cache_files" to "DETECTED"),
            raw().asMap().mapKeys { it.key.name }.mapValues { it.value as String })
        assertEquals(listOf("rates_cache", "last_bank_selection", "graph_cache_files", "graph_preferences"),
            LegacyMigrationTarget.entries.map { it.key })
        assertEquals("fxi_migration_journal", LocalMigrationJournal.NAME)
    }

    @Test
    fun J09_anUnreadableStage_isAnError_notAbsence_noWork_notOverwritten() = runBlocking {
        open()
        checkNotNull(dataStore).edit { it[stringPreferencesKey("rates_cache")] = "HALF_DONE" }
        val journal = open()
        assertThrows(IllegalStateException::class.java) { runBlocking { journal.stage(RATES_CACHE) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { journal.detect(RATES_CACHE) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { journal.cutover(RATES_CACHE, work("commit")) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { journal.deleteLegacy(RATES_CACHE, work("delete")) } }
        assertEquals(emptyList<String>(), ran)
        assertEquals("HALF_DONE", raw()[stringPreferencesKey("rates_cache")])
    }

    @Test
    fun J10_aFileThatCannotBeParsed_throws_ratherThanReadingEmpty() = runBlocking<Unit> {
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        val journal = open()
        assertThrows(IOException::class.java) { runBlocking { journal.stage(RATES_CACHE) } }
        assertThrows(IOException::class.java) { runBlocking { journal.detect(RATES_CACHE) } }
    }

    /** A DataStore whose writes fail: reads pass through to [delegate]. */
    private class FailingWrites(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = delegate.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("write refused")
    }

    @Test
    fun J11_aWriteFailureAfterTheWork_propagates_andRecordsNothing() = runBlocking<Unit> {
        open().detect(RATES_CACHE)
        val failing = LocalMigrationJournal(FailingWrites(checkNotNull(dataStore)))
        assertThrows(IOException::class.java) { runBlocking { failing.cutover(RATES_CACHE, work("commit")) } }
        assertEquals("the work ran; its record did not land", listOf("commit"), ran)
        assertEquals(DETECTED, open().stage(RATES_CACHE))
        // An already-recorded stage needs no write, so it does not fail — for each of the three steps.
        assertEquals(MigrationAdvance.AlreadyThere, LocalMigrationJournal(FailingWrites(checkNotNull(dataStore))).detect(RATES_CACHE))
        val journal = open(); journal.cutover(RATES_CACHE, work("commit ok"))
        assertEquals(MigrationAdvance.AlreadyThere, LocalMigrationJournal(FailingWrites(checkNotNull(dataStore))).cutover(RATES_CACHE, work("x")))
        open().deleteLegacy(RATES_CACHE, work("delete ok"))
        assertEquals(MigrationAdvance.AlreadyThere, LocalMigrationJournal(FailingWrites(checkNotNull(dataStore))).deleteLegacy(RATES_CACHE, work("y")))
        assertEquals("no work for a recorded step", listOf("commit", "commit ok", "delete ok"), ran)
    }

    @Test
    fun J12_aFailedCutoverWork_propagates_keepsDetected_andTheRetryRepeatsIt() = runBlocking<Unit> {
        val journal = open()
        journal.detect(RATES_CACHE)
        val boom = IllegalStateException("new store commit failed")
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { journal.cutover(RATES_CACHE) { throw boom } } }
        assertEquals("the work's own failure, unwrapped", boom, thrown)
        assertEquals(DETECTED, open().stage(RATES_CACHE))
        assertEquals(MigrationAdvance.Advanced, open().cutover(RATES_CACHE, work("commit retried")))
        assertEquals(listOf("commit retried"), ran)
        assertEquals(CONSUMER_CUTOVER, open().stage(RATES_CACHE))
    }

    @Test
    fun J13_aFailedDeletionWork_propagates_keepsTheCutover_andTheRetryRepeatsIt() = runBlocking<Unit> {
        val journal = open()
        journal.detect(RATES_CACHE); journal.cutover(RATES_CACHE, work("commit"))
        val boom = IOException("file refused")
        var calls = 0
        val thrown = assertThrows(IOException::class.java) { runBlocking { journal.deleteLegacy(RATES_CACHE) { calls++; throw boom } } }
        assertEquals("the deletion work ran once", 1, calls)
        assertEquals("the work's own failure, unwrapped", boom, thrown)
        assertEquals(CONSUMER_CUTOVER, open().stage(RATES_CACHE))
        assertEquals(MigrationAdvance.Advanced, open().deleteLegacy(RATES_CACHE, work("delete retried")))
        assertEquals(listOf("commit", "delete retried"), ran)
        assertEquals(LEGACY_DELETED, open().stage(RATES_CACHE))
    }

    @Test
    fun J14_theJournalTouchesNoLegacyDataOutsideItsWork() = runBlocking {
        val legacy = File(folder.root, "graph_cache_usd.json").apply { writeText("{}") }
        open().all(GRAPH_CACHE_FILES)
        assertEquals("the target's slice deletes, through its own work", "{}", legacy.readText())
        assertEquals(listOf("commit", "delete"), ran)
    }
}
