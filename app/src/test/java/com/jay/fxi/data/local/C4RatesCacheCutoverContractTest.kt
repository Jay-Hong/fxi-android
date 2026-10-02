package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.jay.fxi.data.remote.C4OwnerHarness
import com.jay.fxi.data.remote.TopicRuntimeOwner
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned R4-c C4 contract for the rate cache cutover (R4c/C4/design_codex.r3.md: CUTOVER-BLOCKED, CUTOVER-SUCCESS,
 * DELETE-FAILURE, DELETE-RECORD-INTERRUPT). Input: the C0 source-reproduced synthetic `fxi_cache` (its SHA and its own contract
 * are `RatesUpgradeFixtureContractTest`'s; this file only copies the bytes into temp files). The journal, the migration and the
 * cutover are production; the owner is the production owner from [C4OwnerHarness]. A restart closes every DataStore and reopens
 * the same files. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class C4RatesCacheCutoverContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }

    private val raw: ByteArray = checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/v122/fxi_cache.v122.preferences_pb")).readBytes()
    private val lastBanks = mapOf<String, Any>("last_bank_usd-krw" to "kb", "last_bank_jpy-krw" to "hana")

    /** A journal DataStore whose next write recording [failOn] throws after the step's work ran: the interrupted record. */
    private class JournalInterrupt(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var failOn: String? = null
        override val data: Flow<Preferences> get() = real.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = real.updateData { t ->
            val next = transform(t)
            val f = failOn
            if (f != null && next.asMap().values.any { it == f }) { failOn = null; throw IOException("interrupted") }
            next
        }
    }

    /** One process lifetime over the files in [dir]; [bytes] null reopens what the previous lifetime left. */
    private inner class Lifetime(private val dir: File, bytes: ByteArray?) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val cache: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "fxi_cache.preferences_pb").also { if (bytes != null) it.writeBytes(bytes) }
        }
        val journalStore = JournalInterrupt(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "journal.preferences_pb") })
        val journal = LocalMigrationJournal(journalStore)
        @Volatile var failDeletes = 0
        @Volatile var deleteGate: CompletableDeferred<Unit>? = null
        val deletes = AtomicInteger()
        val migration = RatesCacheMigration(journal) {
            deletes.incrementAndGet()
            deleteGate?.await()
            if (failDeletes > 0) { failDeletes -= 1; throw IOException("disk") }
            deleteLegacyRateKeys(cache)
        }
        val reports: MutableList<Throwable> = java.util.Collections.synchronizedList(mutableListOf())
        val launchScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        fun cutover(owner: TopicRuntimeOwner) = RatesCacheCutover(migration, owner, launchScope) { reports += it }
        suspend fun keys() = cache.data.first().asMap().mapKeys { it.key.name }
        suspend fun stage() = journal.stage(LegacyMigrationTarget.RATES_CACHE)
        /** Ends this lifetime (every DataStore closed) and opens the next one over the same files. */
        suspend fun restart(): Lifetime { scope.coroutineContext[Job]!!.cancelAndJoin(); return Lifetime(dir, null) }
    }

    private fun lifetime(name: String) = Lifetime(folder.newFolder(name), raw)

    private fun TestScope.started(h: C4OwnerHarness): C4OwnerHarness {
        h.owner.start()
        h.settle(1_000)
        return h
    }

    @Test
    fun `C4-J-CUTOVER-BLOCKED an owner that is not ready keeps DETECTED, deletes nothing and records no false success`() = runTest {
        val notStarted = C4OwnerHarness(this)
        val u = lifetime("not-started")
        val before = u.keys()
        val job = u.cutover(notStarted.owner).launch()
        job.join()
        assertFalse("not started: the failure escaped instead of being reported", job.isCancelled)
        assertEquals("not started: stage", LegacyMigrationStage.DETECTED, u.stage())
        assertEquals("not started: cache", before, u.keys())
        assertEquals("not started: deletions", 0, u.deletes.get())
        assertTrue("not started: report ${u.reports}", u.reports.single() is IllegalStateException)

        val failed = C4OwnerHarness(this, failFirstCreate = true)
        assertThrows("fixture: the start fails", IllegalStateException::class.java) { failed.owner.start() }
        val v = lifetime("start-failed")
        v.cutover(failed.owner).launch().join()
        assertEquals("start failed: stage", LegacyMigrationStage.DETECTED, v.stage())
        assertEquals("start failed: cache", before, v.keys())
        assertEquals("start failed: deletions", 0, v.deletes.get())
        assertTrue("start failed: report ${v.reports}", v.reports.single() is IllegalStateException)
    }

    @Test
    fun `C4-J-CUTOVER-SUCCESS a ready owner, signed out, free or offline, deletes the two rate keys only`() = runTest {
        val cases = listOf(
            "signed-out" to C4OwnerHarness(this, live = null, withLastKnown = true),
            "free" to C4OwnerHarness(this, online = true, withLastKnown = true).also { it.fakeIssuer.grant = null },
            "offline" to C4OwnerHarness(this, online = false, withLastKnown = true)
        )
        for ((name, harness) in cases) {
            val h = started(harness)
            val u = lifetime(name)
            u.cache.edit {
                it[intPreferencesKey("unrelated_int")] = 7
                it[booleanPreferencesKey("unrelated_flag")] = true
                it[doublePreferencesKey("unrelated_double")] = 1.5
                it[stringSetPreferencesKey("unrelated_set")] = setOf("a", "b")
            }
            val before = u.keys()
            val job = u.cutover(h.owner).launch()
            job.join()
            assertFalse("$name: the job failed", job.isCancelled)
            assertEquals("$name: stage", LegacyMigrationStage.LEGACY_DELETED, u.stage())
            assertEquals("$name: every other key, its type and its value", before - setOf("rates", "rates_timestamp"), u.keys())
            assertEquals("$name: reports", emptyList<Throwable>(), u.reports.toList())
            assertEquals("$name: the topic seed was written", 0, h.memory.writes)
            assertEquals("$name: fixture: no live reception", 0, h.wire.requests.size)
        }
    }

    @Test
    fun `C4-J-DELETE-FAILURE a failed deletion stays at CONSUMER_CUTOVER, and the next process repeats the deletion alone`() = runTest {
        val first = started(C4OwnerHarness(this))
        val u = lifetime("failure")
        u.failDeletes = 1
        val job = u.cutover(first.owner).launch()
        job.join()
        assertFalse("the failure escaped instead of being reported", job.isCancelled)
        assertEquals("stage after the failure", LegacyMigrationStage.CONSUMER_CUTOVER, u.stage())
        assertTrue("the rate keys after the failure", u.keys().keys.containsAll(setOf("rates", "rates_timestamp")))
        assertTrue("report ${u.reports}", u.reports.single() is IOException)

        // A new process: every DataStore closed and reopened over the same files, with its own owner installed and started.
        val next = u.restart()
        val second = started(C4OwnerHarness(this))
        next.cutover(second.owner).launch().join()
        assertEquals("stage", LegacyMigrationStage.LEGACY_DELETED, next.stage())
        assertEquals("deletions in the new process", 1, next.deletes.get())
        assertEquals("cache", lastBanks, next.keys())
        assertEquals("reports", emptyList<Throwable>(), next.reports.toList())
    }

    @Test
    fun `C4-J-DELETE-FAILURE a cancellation is not reported and leaves the deletion to repeat`() = runTest {
        val h = started(C4OwnerHarness(this))
        val u = lifetime("cancelled")
        u.deleteGate = CompletableDeferred()
        val job = u.cutover(h.owner).launch()
        withContext(Dispatchers.Default) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (u.deletes.get() == 0) {
                check(System.nanoTime() < deadline) { "fixture: the deletion never started" }
                Thread.sleep(5)
            }
        }
        u.launchScope.cancel()
        job.join()
        assertTrue("the cancellation did not propagate", job.isCancelled)
        assertEquals("a cancellation was reported", emptyList<Throwable>(), u.reports.toList())
        assertEquals("stage", LegacyMigrationStage.CONSUMER_CUTOVER, u.stage())
    }

    @Test
    fun `C4-J-DELETE-RECORD-INTERRUPT an interrupted record repeats the deletion only, without the cutover check`() = runTest {
        val first = started(C4OwnerHarness(this))
        val u = lifetime("interrupt")
        u.journalStore.failOn = LegacyMigrationStage.LEGACY_DELETED.name
        u.cutover(first.owner).launch().join()
        assertEquals("the keys are already gone", lastBanks.keys, u.keys().keys)
        assertEquals("not reported done", LegacyMigrationStage.CONSUMER_CUTOVER, u.stage())
        assertTrue("report ${u.reports}", u.reports.single() is IOException)

        // Not started: were the cutover step run again, its readiness check would fail and keep CONSUMER_CUTOVER.
        val next = u.restart()
        val unready = C4OwnerHarness(this)
        val cutover = next.cutover(unready.owner)
        val job = cutover.launch()
        job.join()
        assertSame("a second launch in the process is another migration", job, cutover.launch())
        assertEquals("stage", LegacyMigrationStage.LEGACY_DELETED, next.stage())
        assertEquals("the deletion was not repeated once", 1, next.deletes.get())
        assertEquals("reports", emptyList<Throwable>(), next.reports.toList())
        assertEquals("cache", lastBanks, next.keys())

        val last = next.restart()
        last.cutover(unready.owner).launch().join()
        assertEquals("a finished migration ran work", 0, last.deletes.get())
        assertEquals("a finished migration reported", emptyList<Throwable>(), last.reports.toList())
        assertEquals("stage after completion", LegacyMigrationStage.LEGACY_DELETED, last.stage())
    }
}
