package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S7 E5b4b (`s7e5b4_api_agreed.r1.md` §2.6): the last-bank target resumes from the stage it last recorded, a failing step
 * records nothing and the next run repeats it, and after LEGACY_DELETED every run sweeps again without writing the journal.
 */
class LastBankSelectionMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }

    private fun store(name: String): DataStore<Preferences> = storeIn(name).first

    /** A DataStore over [name]'s file and the scope it lives in, so a test can close it and open the same file again. */
    private fun storeIn(name: String): Pair<DataStore<Preferences>, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") } to scope
    }

    private val key = stringPreferencesKey(LegacyMigrationTarget.LAST_BANK_SELECTION.key)

    private fun recorded(stage: LegacyMigrationStage?): Preferences =
        stage?.let { preferencesOf(key to it.name) } ?: emptyPreferences()

    /**
     * An in-memory journal file. Each collection of [data] takes the next [script] answer, and once the script runs out the last
     * answer is kept; with no script, [data] answers what [updateData] last wrote. Nothing in it suspends, so a test scheduler
     * alone orders it.
     */
    private inner class MemoryStore(start: LegacyMigrationStage?) : DataStore<Preferences> {
        private var current = recorded(start)
        private var kept: Preferences? = null
        val script = ArrayDeque<LegacyMigrationStage?>()
        var updates = 0

        override val data: Flow<Preferences> = flow {
            if (script.isNotEmpty()) kept = recorded(script.removeFirst())
            emit(kept ?: current)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(current)
            current = next
            updates++
            return next
        }
    }

    /** A real journal file whose write of [failOn] fails once, before it commits. */
    private class FailingRecord(private val inner: DataStore<Preferences>, private var failOn: LegacyMigrationStage?) : DataStore<Preferences> {
        private val key = stringPreferencesKey(LegacyMigrationTarget.LAST_BANK_SELECTION.key)
        val thrown = mutableListOf<IOException>()

        override val data: Flow<Preferences> get() = inner.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            inner.updateData { current ->
                val next = transform(current)
                val failing = failOn
                if (failing != null && next[key] == failing.name) {
                    failOn = null
                    throw IOException("record $failing").also { thrown += it }
                }
                next
            }
    }

    private val target = LegacyMigrationTarget.LAST_BANK_SELECTION
    private var fixtures = 0

    private inner class Fixture {
        /** One journal file per fixture: DataStore refuses two live instances over one file. */
        val journalName = "journal${fixtures++}"
        val journalFile get() = File(folder.root, "$journalName.preferences_pb")
        val journalStore = store(journalName)
        val journal = LocalMigrationJournal(journalStore)
        val steps = mutableListOf<String>()
        var failReady = 0
        var failDeletes = 0
        val migration = LastBankSelectionMigration(
            journal,
            requireReady = {
                steps += "ready"
                if (failReady > 0) { failReady -= 1; throw IllegalStateException("not bound") }
            },
            deleteLegacy = {
                steps += "delete"
                if (failDeletes > 0) { failDeletes -= 1; throw IOException("disk") }
            }
        )

        suspend fun record(stage: LegacyMigrationStage) = journalStore.edit { it[stringPreferencesKey(target.key)] = stage.name }
    }

    @Test
    fun fromNothingItDetectsCutsOverAndDeletes() = runBlocking {
        val f = Fixture()
        f.migration.run()
        assertEquals(listOf("ready", "delete"), f.steps)
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.journal.stage(target))
    }

    @Test
    fun eachRecordedStageResumesFromThere() = runBlocking {
        val cases = mapOf(
            LegacyMigrationStage.DETECTED to listOf("ready", "delete"),
            LegacyMigrationStage.CONSUMER_CUTOVER to listOf("delete")
        )
        for ((stage, expected) in cases) {
            val f = Fixture()
            f.record(stage)
            f.migration.run()
            assertEquals("$stage", expected, f.steps)
            assertEquals("$stage", LegacyMigrationStage.LEGACY_DELETED, f.journal.stage(target))
        }
    }

    /** A downgrade, an old backup or a retired writer can bring the keys back: every run sweeps, and writes no stage. */
    @Test
    fun afterLegacyDeletedEveryRunSweepsAgainAndRecordsNothing() = runBlocking {
        val f = Fixture()
        f.record(LegacyMigrationStage.LEGACY_DELETED)
        val before = f.journalFile.readBytes()
        f.migration.run()
        f.migration.run()
        assertEquals(listOf("delete", "delete"), f.steps)
        assertEquals("journal 을 다시 썼다", before.toList(), f.journalFile.readBytes().toList())
    }

    @Test
    fun aFailingReadinessRecordsNoCutover_andTheNextRunRepeatsIt() = runBlocking {
        val f = Fixture()
        f.failReady = 1
        assertThrows(IllegalStateException::class.java) { runBlocking { f.migration.run() } }
        assertEquals(LegacyMigrationStage.DETECTED, f.journal.stage(target))
        f.migration.run()
        assertEquals(listOf("ready", "ready", "delete"), f.steps)
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.journal.stage(target))
    }

    @Test
    fun aFailingDeletionRecordsNoDeletion_andTheNextRunRepeatsIt() = runBlocking {
        val f = Fixture()
        f.failDeletes = 1
        assertThrows(IOException::class.java) { runBlocking { f.migration.run() } }
        assertEquals(LegacyMigrationStage.CONSUMER_CUTOVER, f.journal.stage(target))
        f.migration.run()
        assertEquals(listOf("ready", "delete", "delete"), f.steps)
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.journal.stage(target))
    }

    /** A sweep that fails after completion is reported to the caller; the journal keeps LEGACY_DELETED. */
    @Test
    fun aFailingSweepAfterCompletionIsThrownAndLeavesTheStage() = runBlocking {
        val f = Fixture()
        f.record(LegacyMigrationStage.LEGACY_DELETED)
        f.failDeletes = 1
        assertThrows(IOException::class.java) { runBlocking { f.migration.run() } }
        assertEquals(LegacyMigrationStage.LEGACY_DELETED, f.journal.stage(target))
    }

    @Test
    fun theCutoverLaunchesOnce_reportsAFailure_andDoesNotReportItsCancellation() = runBlocking {
        val f = Fixture()
        f.failDeletes = 1
        val reports = mutableListOf<Throwable>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val cutover = LastBankSelectionCutover(f.migration, scope) { reports += it }
        val job = cutover.launch()
        assertEquals("두 번째 launch 는 같은 job", job, cutover.launch())
        job.join()
        assertEquals(1, reports.size)
        assertEquals(IOException::class.java, reports.single().javaClass)

        // Cancelled once it is really inside the readiness step: not reported, and the journal does not move past DETECTED.
        val idle = Fixture()
        val entered = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val blocked = LastBankSelectionMigration(idle.journal, requireReady = { entered.complete(Unit); held.await() }, deleteLegacy = {})
        val quiet = mutableListOf<Throwable>()
        val cancelled = LastBankSelectionCutover(blocked, CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }) { quiet += it }
        val running = cancelled.launch()
        entered.await()
        running.cancelAndJoin()
        assertTrue("취소로 끝나지 않았다", running.isCancelled)
        assertEquals("취소를 report 했다", emptyList<Throwable>(), quiet)
        assertEquals("취소된 준비가 journal 을 전진시켰다", LegacyMigrationStage.DETECTED, idle.journal.stage(target))
    }

    /**
     * The failure that crossed DataStore's own coroutine: stack-trace recovery may hand back a copy, so class and message
     * identify it.
     */
    private fun assertSameFailure(label: String, expected: Throwable, actual: Throwable?) {
        assertEquals(label, expected.javaClass, actual?.javaClass)
        assertEquals(label, expected.message, actual?.message)
    }

    /** One run at a time, start to end: a second run waits for the whole first one, then sweeps the completed stage (G09). */
    @Test
    fun overlappingRunsAreSerialisedWhole() = runTest {
        for (start in listOf(LegacyMigrationStage.DETECTED, LegacyMigrationStage.LEGACY_DELETED)) {
            val store = MemoryStore(start)
            val gate = CompletableDeferred<Unit>()
            val log = mutableListOf<String>()
            var active = 0
            var maxActive = 0
            var ready = 0
            var deletes = 0
            suspend fun step(name: String, hold: Boolean) {
                log += "start $name"
                active++
                maxActive = maxOf(maxActive, active)
                try {
                    if (hold) gate.await()
                } finally {
                    active--
                    log += "end $name"
                }
            }
            val held = if (start == LegacyMigrationStage.DETECTED) "ready" else "delete"
            val migration = LastBankSelectionMigration(
                LocalMigrationJournal(store),
                requireReady = { ready++; step("ready", held == "ready" && ready == 1) },
                deleteLegacy = { deletes++; step("delete", held == "delete" && deletes == 1) }
            )
            val first = launch { migration.run() }
            val second = launch { migration.run() }
            runCurrent()
            assertEquals("$start: 둘째 실행이 첫 실행 안으로 들어왔다", listOf("start $held"), log)
            gate.complete(Unit)
            advanceUntilIdle()
            first.join()
            second.join()
            val expected = if (start == LegacyMigrationStage.DETECTED) {
                listOf("start ready", "end ready", "start delete", "end delete", "start delete", "end delete")
            } else {
                listOf("start delete", "end delete", "start delete", "end delete")
            }
            assertEquals("$start", expected, log)
            assertEquals("$start: 동시에 둘이 돌았다", 1, maxActive)
            assertEquals("$start: 준비 횟수", if (start == LegacyMigrationStage.DETECTED) 1 else 0, ready)
            assertEquals("$start: 삭제 횟수", 2, deletes)
            assertEquals("$start", LegacyMigrationStage.LEGACY_DELETED, LocalMigrationJournal(store).stage(target))
        }
    }

    /**
     * A stage that moves under the migration is not normal: the run throws, the failing step does no work and records nothing,
     * the steps before it keep their effect, and through the cutover it is reported once (G10). AlreadyThere is normal.
     */
    @Test
    fun anUnexpectedAdvanceIsAFailure_andTheFailingStepDoesNothing() = runTest {
        class Case(val name: String, val script: List<LegacyMigrationStage?>, val updates: Int, val ready: Int, val deletes: Int)
        val cases = listOf(
            Case("detect 안 Backward", listOf(null, LegacyMigrationStage.LEGACY_DELETED), 0, 0, 0),
            Case("cutover 안 Skipped", listOf(LegacyMigrationStage.DETECTED, null), 0, 0, 0),
            Case("deleteLegacy 안 DETECTED", listOf(null, null, LegacyMigrationStage.DETECTED), 2, 1, 0)
        )
        for (c in cases) {
            val direct = MemoryStore(null).apply { script += c.script }
            var ready = 0
            var deletes = 0
            val thrown = runCatching {
                LastBankSelectionMigration(LocalMigrationJournal(direct), { ready++ }, { deletes++ }).run()
            }.exceptionOrNull()
            assertTrue("${c.name}: $thrown", thrown is IllegalStateException)
            assertEquals(c.name, listOf(c.updates, c.ready, c.deletes), listOf(direct.updates, ready, deletes))

            val viaCutover = MemoryStore(null).apply { script += c.script }
            var cutoverReady = 0
            var cutoverDeletes = 0
            val reports = mutableListOf<Throwable>()
            val migration = LastBankSelectionMigration(LocalMigrationJournal(viaCutover), { cutoverReady++ }, { cutoverDeletes++ })
            LastBankSelectionCutover(migration, this) { reports += it }.launch().join()
            assertEquals("${c.name}: report", 1, reports.size)
            assertTrue("${c.name}: ${reports.single()}", reports.single() is IllegalStateException)
            assertEquals(c.name, listOf(c.updates, c.ready, c.deletes), listOf(viaCutover.updates, cutoverReady, cutoverDeletes))
        }

        val control = MemoryStore(null).apply {
            script += listOf(null, LegacyMigrationStage.DETECTED, LegacyMigrationStage.DETECTED, LegacyMigrationStage.CONSUMER_CUTOVER)
        }
        var ready = 0
        var deletes = 0
        LastBankSelectionMigration(LocalMigrationJournal(control), { ready++ }, { deletes++ }).run()
        assertEquals("AlreadyThere 는 정상이다", listOf(2, 1, 1), listOf(control.updates, ready, deletes))
    }

    /**
     * The step's work succeeded but its record failed before it committed: the run throws that failure, the journal keeps the
     * stage before, the effects so far are counted apart from the record, and a new runner over the same file, reopened,
     * completes (G11). Through the cutover the same failure is reported once.
     */
    @Test
    fun aRecordThatFailsAfterItsWorkLeavesTheStageBefore_andANewRunnerCompletes() = runBlocking {
        class Case(val failOn: LegacyMigrationStage, val stage: LegacyMigrationStage?, val ready: Int, val deletes: Int, val rerunReady: Int, val rerunDeletes: Int)
        val cases = listOf(
            Case(LegacyMigrationStage.DETECTED, null, 0, 0, 1, 1),
            Case(LegacyMigrationStage.CONSUMER_CUTOVER, LegacyMigrationStage.DETECTED, 1, 0, 1, 1),
            Case(LegacyMigrationStage.LEGACY_DELETED, LegacyMigrationStage.CONSUMER_CUTOVER, 1, 1, 0, 1)
        )
        for (c in cases) {
            for (viaCutover in listOf(false, true)) {
                val label = "${c.failOn} cutover=$viaCutover"
                val name = "record${fixtures++}"
                val (inner, scope) = storeIn(name)
                val store = FailingRecord(inner, c.failOn)
                var ready = 0
                var deletes = 0
                val migration = LastBankSelectionMigration(LocalMigrationJournal(store), { ready++ }, { deletes++ })
                if (viaCutover) {
                    val reports = mutableListOf<Throwable>()
                    val runner = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
                    LastBankSelectionCutover(migration, runner) { reports += it }.launch().join()
                    assertEquals("$label: report", 1, reports.size)
                    assertSameFailure("$label: 다른 실패를 report 했다", store.thrown.single(), reports.single())
                } else {
                    val thrown = runCatching { migration.run() }.exceptionOrNull()
                    assertSameFailure("$label: 다른 실패", store.thrown.single(), thrown)
                }
                assertEquals("$label: 기록 실패 횟수", 1, store.thrown.size)
                assertEquals("$label: 효과", listOf(c.ready, c.deletes), listOf(ready, deletes))
                assertEquals("$label: 기록 실패 뒤 stage", c.stage, LocalMigrationJournal(inner).stage(target))

                scope.coroutineContext[Job]!!.cancelAndJoin()
                val reopened = storeIn(name).first
                assertEquals("$label: 다시 연 파일의 stage", c.stage, LocalMigrationJournal(reopened).stage(target))
                var rerunReady = 0
                var rerunDeletes = 0
                LastBankSelectionMigration(LocalMigrationJournal(reopened), { rerunReady++ }, { rerunDeletes++ }).run()
                assertEquals("$label: 재실행 효과", listOf(c.rerunReady, c.rerunDeletes), listOf(rerunReady, rerunDeletes))
                assertEquals("$label", LegacyMigrationStage.LEGACY_DELETED, LocalMigrationJournal(reopened).stage(target))
            }
        }
    }

    @Test
    fun anUnreadableStageIsAFailureNotAbsence() = runBlocking {
        val f = Fixture()
        f.journalStore.edit { it[stringPreferencesKey(target.key)] = "GARBLED" }
        assertThrows(IllegalStateException::class.java) { runBlocking { f.migration.run() } }
        assertEquals(emptyList<String>(), f.steps)
    }
}
