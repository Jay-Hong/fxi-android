package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.purge.ManifestScopePurger
import com.jay.fxi.data.entitlements.purge.PurgeCause
import com.jay.fxi.data.entitlements.purge.PurgeDecision
import com.jay.fxi.data.entitlements.purge.PurgeManifest
import com.jay.fxi.data.entitlements.purge.PurgeTargetAdapter
import com.jay.fxi.data.entitlements.purge.TargetDisposition
import com.jay.fxi.data.entitlements.purge.TargetOutcome
import com.jay.fxi.data.local.LegacyMigrationStage.CONSUMER_CUTOVER
import com.jay.fxi.data.local.LegacyMigrationStage.DETECTED
import com.jay.fxi.data.local.LegacyMigrationStage.LEGACY_DELETED
import com.jay.fxi.data.local.LegacyMigrationTarget.GRAPH_CACHE_FILES
import com.jay.fxi.data.local.LegacyMigrationTarget.GRAPH_PREFERENCES
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S4 RT07 contract r1 (JVM): the dormant graph migration runner `GraphCacheMigration` and its launcher
 * `GraphCacheCutover`, rows R01-R12 and D of the agreed design R4c/S4 rt07_api_agreed.r2 §5 (G04-G15 of g_design_codex.r1
 * §6), plus R13 (a step's CancellationException while the run is active is a failure), R14 (an unexpected transition
 * result fails its target) and R15 (a cancellation during a failing last sweep stays a cancellation) from the contract review. Oracles: ANDROID_V2_PLAN.md :1368-1369 (S4 deletes `graph_cache_*.json` and `fxi_graph_preferences` and makes the v2
 * namespace authoritative), :1786-1787 (deleted without a v1 decode; the selection is reset), :1796-1798 (the journal records
 * a step only after its work), :325 (a committed journal is not purge completion).
 *
 * Rules (rt07_api_agreed.r2 §3):
 *  - Per target, in the order cache files then preferences, the run resumes from the recorded stage: none -> detect, cut over
 *    (the readiness), delete; DETECTED -> cut over, delete; CONSUMER_CUTOVER -> delete; LEGACY_DELETED -> sweep again without
 *    writing the journal. An unreadable stage is that target's failure, never absence or completion.
 *  - A failed readiness records no cutover and deletes nothing. A sweep counts only when a second listing shows no legacy name
 *    left; until then the target stays at CONSUMER_CUTOVER (or LEGACY_DELETED on a re-sweep) and fails.
 *  - The cache sweep takes top-level `filesDir` names starting `graph_cache_` and ending `.json`; the preference sweep takes
 *    `datastore/fxi_graph_preferences.preferences_pb` and its dot siblings. No decode, no DataStore, no recursion: a legacy
 *    name on a directory fails without a delete call. An absent directory is clean and is not created.
 *  - Both targets are attempted; failures are thrown together with their causes. Only the run's own cancellation propagates,
 *    at once and unaggregated; a CancellationException a step throws while the run is active (a timeout) is that target's
 *    failure. A transition answering anything but Advanced or AlreadyThere fails its target. One mutex serializes runs. The
 *    launcher starts one run per instance and keeps its Job; a failure is reported once, a cancellation never.
 *
 * Fixture: synthetic inputs derived from the v1.2.2 source (6cea639, versionCode 15), written here without production
 * helpers: versioned names are that version's writer names (CacheService.kt:350-356), unversioned names its legacy lookup
 * and cleanup names (:359-366), over SupportedCurrency.kt:11-13 and GraphPeriod.kt:10-13. They are not captured device data;
 * an in-place upgrade stays device evidence. Bytes are arbitrary because nothing is decoded. The journal is a real
 * Preferences DataStore outside the fixture's filesDir. The implementation thread reads but does not edit this file.
 */
class GraphCacheMigrationContractTest {

    @get:Rule val folder = TemporaryFolder()

    private var scope: CoroutineScope? = null
    private var store: DataStore<Preferences>? = null

    @After fun tearDown() = runBlocking { close() }

    private fun journalFile(name: String) = File(folder.root, "journal/$name.preferences_pb").also { it.parentFile!!.mkdirs() }

    /** A journal over [name]'s file, closing the previous DataStore first; [wrap] may stand in front of it. */
    private suspend fun open(
        name: String = "j",
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it }
    ): LocalMigrationJournal {
        close()
        val opened = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val created = PreferenceDataStoreFactory.create(scope = opened) { journalFile(name) }
        scope = opened
        store = created
        return LocalMigrationJournal(wrap(created))
    }

    private suspend fun close() {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        scope = null
        store = null
    }

    private suspend fun stages(name: String = "j"): Pair<LegacyMigrationStage?, LegacyMigrationStage?> =
        open(name).let { it.stage(GRAPH_CACHE_FILES) to it.stage(GRAPH_PREFERENCES) }

    private suspend fun rawValue(key: String): String? = checkNotNull(store).data.first()[stringPreferencesKey(key)]

    private fun filesDir(name: String) = File(folder.root, name).also { it.mkdirs() }

    private fun put(dir: File, name: String, bytes: ByteArray = name.toByteArray()): File {
        dir.mkdirs()
        return File(dir, name).also { it.writeBytes(bytes) }
    }

    private fun names(dir: File): List<String> = dir.list()?.sorted().orEmpty()

    /** The readiness: counts calls, runs [onCall] inside each, and fails while [failures] lasts. */
    private class Ready(var failures: Int = 0, var onCall: suspend (Int) -> Unit = {}) : GraphMigrationReadiness {
        @Volatile var calls = 0
        override suspend fun requireReady() {
            val n = ++calls
            onCall(n)
            if (failures > 0) {
                failures--
                throw IllegalStateException("not ready ($n)")
            }
        }
    }

    /** A readiness that must not be asked: it records the call, which each row checks after the run, and fails. */
    private var neverAsked = 0
    private val never = GraphMigrationReadiness {
        neverAsked++
        throw IllegalStateException("the readiness was asked")
    }

    private val deleteCalls = mutableListOf<String>()

    /** The delete seam: records each call; refuses [refuse], answers true but keeps [keep], runs [after] on a real delete. */
    private fun deleter(
        refuse: Set<String> = emptySet(),
        keep: Set<String> = emptySet(),
        after: (File) -> Unit = {}
    ): (File) -> Boolean = { file ->
        deleteCalls += file.name
        when (file.name) {
            in refuse -> false
            in keep -> true
            else -> file.delete().also { after(file) }
        }
    }

    private suspend fun failureOf(block: suspend () -> Unit): GraphCacheMigrationFailure? = try {
        block()
        null
    } catch (failure: GraphCacheMigrationFailure) {
        failure
    }

    // --- the synthetic v1.2.2 inputs (6cea639) ---------------------------------------------------------------------------

    private val currencies = listOf("usd-krw", "jpy-krw", "eur-krw")
    private val periods = listOf("1d", "1w", "3m", "1y")
    private fun suffix(period: String) = if (period == "1d") "" else "_$period"
    private val versioned = currencies.flatMap { c -> periods.map { p -> "graph_cache_v1_$c${suffix(p)}.json" } }
    private val unversioned = currencies.flatMap { c -> periods.map { p -> "graph_cache_$c${suffix(p)}.json" } }
    /** Not v1.2.2 names, but names the glob takes (an unknown currency or period, a non-v1 version, an empty middle). */
    private val unknown = listOf(
        "graph_cache_v1_krw-usd.json", "graph_cache_v1_usd-krw_6m.json", "graph_cache_v2_usd-krw.json",
        "graph_cache_v1_usd-krw_1d.json", "graph_cache_.json"
    )
    /**
     * Names the cache sweep must leave: other suffixes, other case, another prefix. The case controls have no case twin among
     * the inputs or each other, so they stay distinct files on a case-insensitive host filesystem as well.
     */
    private val cacheKeeps = listOf(
        "graph_cache_v1_usd-krw.json.tmp", "graph_cache_v1_usd-krw.json.bak", "GRAPH_CACHE_v1_aaa.json",
        "graph_cache_v1_bbb.JSON", "xgraph_cache_v1_usd-krw.json"
    )
    private val prefs = "fxi_graph_preferences.preferences_pb"
    /** Longer names, other stores, other case (`.old` is no input, so it has no case twin on a case-insensitive host). */
    private val prefKeeps = listOf(
        "fxi_graph_preferences.preferences_pb2", "fxi_graph_preferences_v2.preferences_pb", "fxi_free_graph.preferences_pb",
        "FXI_GRAPH_PREFERENCES.preferences_pb.old"
    )
    private val corrupt = byteArrayOf(0, -1, 0x7b, 0x22, -128, 10)

    private fun migration(
        journal: LocalMigrationJournal,
        files: File,
        readiness: GraphMigrationReadiness = Ready(),
        delete: (File) -> Boolean = File::delete
    ) = GraphCacheMigration(journal, files, readiness, delete)

    // --- R01-R12 ---------------------------------------------------------------------------------------------------------

    /**
     * R01 (G04): a failed readiness records no cutover and deletes nothing; inside it the target is DETECTED and nothing is
     * deleted yet. Each target asks it: when only the first call fails, the other target goes on. A later run with a ready
     * readiness finishes.
     */
    @Test fun R01_aFailedReadinessCutsNothingOverAndDeletesNothing() = runBlocking {
        val files = filesDir("files")
        put(files, versioned[0])
        put(File(files, "datastore"), prefs)
        val inside = mutableListOf<String>()
        val deletedInside = mutableListOf<String>()
        val ready = Ready(failures = 2, onCall = { n ->
            // Calls 1-4 alternate cache, preferences; the fifth is the cache's.
            val cache = n % 2 == 1
            val key = if (cache) GRAPH_CACHE_FILES.key else GRAPH_PREFERENCES.key
            inside += "$key=${rawValue(key)}"
            val legacy = if (cache) File(files, versioned[0]) else File(files, "datastore/$prefs")
            if (!legacy.exists()) deletedInside += "$n:$key"
        })
        val first = failureOf { migration(open(), files, ready).run() }
        assertEquals(setOf(GRAPH_CACHE_FILES, GRAPH_PREFERENCES), first?.failures?.keys)
        assertEquals(listOf("graph_cache_files=DETECTED", "graph_preferences=DETECTED"), inside.take(2))
        assertEquals(DETECTED to DETECTED, stages())
        assertTrue(File(files, versioned[0]).exists() && File(files, "datastore/$prefs").exists())

        ready.failures = 1
        val second = failureOf { migration(open(), files, ready).run() }
        assertEquals("only the cache target failed", setOf(GRAPH_CACHE_FILES), second?.failures?.keys)
        assertEquals(DETECTED to LEGACY_DELETED, stages())
        assertTrue(File(files, versioned[0]).exists())
        assertFalse(File(files, "datastore/$prefs").exists())

        migration(open(), files, ready).run()
        assertEquals(LEGACY_DELETED to LEGACY_DELETED, stages())
        assertFalse(File(files, versioned[0]).exists())
        assertEquals("2 + 2 + 1 calls: the finished target is not asked again", 5, ready.calls)
        assertEquals("nothing was deleted inside the readiness", emptyList<String>(), deletedInside)
    }

    /**
     * R02 (G05): every top-level `graph_cache_*.json` goes undecoded - the 24 v1.2.2 names, corrupt bytes, unknown currencies
     * and periods, a non-v1 version and a 1d name with a suffix; other suffixes, case, prefixes and a nested file stay.
     */
    @Test fun R02_everyTopLevelGraphCacheJsonGoesUndecoded() = runBlocking {
        val files = filesDir("files")
        (versioned + unversioned).forEachIndexed { i, name -> put(files, name, if (i % 3 == 0) corrupt else name.toByteArray()) }
        unknown.forEach { put(files, it, corrupt) }
        cacheKeeps.forEach { put(files, it) }
        val nested = put(File(files, "sub"), versioned[0])
        assertEquals("premise: 24 v1.2.2 names", 24, (versioned + unversioned).toSet().size)
        migration(open(), files).run()
        assertEquals((cacheKeeps + "sub").sorted(), names(files))
        cacheKeeps.forEach { assertEquals(it, it, File(files, it).readText()) }
        assertTrue("no recursion", nested.exists())
        assertEquals(LEGACY_DELETED, stages().first)
    }

    /** R03a (G06): the preference file and its dot siblings go; longer names and other stores stay. */
    @Test fun R03a_thePreferenceFileAndItsDotSiblingsGo() = runBlocking {
        val files = filesDir("files")
        val datastore = File(files, "datastore")
        listOf(prefs, "$prefs.tmp", "$prefs.bak").forEach { put(datastore, it, corrupt) }
        prefKeeps.forEach { put(datastore, it) }
        migration(open(), files).run()
        assertEquals(prefKeeps.sorted(), names(datastore))
        prefKeeps.forEach { assertEquals(it, it, File(datastore, it).readText()) }
        assertEquals(LEGACY_DELETED, stages().second)
    }

    /** R03b (G06): siblings with no preference file still go. */
    @Test fun R03b_siblingsWithoutTheFileStillGo() = runBlocking {
        val files = filesDir("files")
        val datastore = File(files, "datastore")
        put(datastore, "$prefs.tmp")
        put(datastore, "$prefs.bak")
        migration(open(), files).run()
        assertEquals(emptyList<String>(), names(datastore))
        assertEquals(LEGACY_DELETED, stages().second)
    }

    /**
     * R04 (G07): a missing filesDir, an empty one and a missing `datastore` finish both targets without creating a directory,
     * and the readiness is still asked for each.
     */
    @Test fun R04_nothingToDeleteStillFinishesAndCreatesNothing() = runBlocking {
        val absent = File(folder.root, "absent")
        val ready = Ready()
        migration(open("a"), absent, ready).run()
        assertEquals(LEGACY_DELETED to LEGACY_DELETED, stages("a"))
        assertFalse("no filesDir created", absent.exists())
        assertEquals(2, ready.calls)

        val empty = filesDir("empty")
        val again = Ready()
        migration(open("b"), empty, again).run()
        assertEquals(LEGACY_DELETED to LEGACY_DELETED, stages("b"))
        assertEquals("no datastore directory created", emptyList<String>(), names(empty))
        assertEquals(2, again.calls)
    }

    /**
     * R05 (G08), for each target: a legacy name on an empty directory or on one with a child fails without a delete call; a
     * refusal fails; a delete that answers true but leaves the file fails at the second listing; a directory that stops
     * listing during the sweep fails; one that does not list at all fails. Each stays at CONSUMER_CUTOVER.
     */
    @Test fun R05_aSweepThatCannotFinishFailsAndKeepsTheCutover() = runBlocking {
        suspend fun case(
            name: String,
            target: LegacyMigrationTarget,
            setup: (files: File, dir: File) -> Unit,
            delete: (File) -> Boolean = File::delete,
            check: (files: File, dir: File) -> Unit = { _, _ -> }
        ) {
            deleteCalls.clear()
            val files = File(folder.root, name)
            val dir = if (target == GRAPH_CACHE_FILES) files else File(files, "datastore")
            setup(files, dir)
            val failure = failureOf { migration(open(name), files, delete = delete).run() }
            assertEquals(name, setOf(target), failure?.failures?.keys)
            val (cache, pref) = stages(name)
            assertEquals(name, CONSUMER_CUTOVER, if (target == GRAPH_CACHE_FILES) cache else pref)
            assertEquals("$name: the other target", LEGACY_DELETED, if (target == GRAPH_CACHE_FILES) pref else cache)
            check(files, dir)
        }
        for (target in LegacyMigrationTarget.entries.filter { it == GRAPH_CACHE_FILES || it == GRAPH_PREFERENCES }) {
            val legacy = if (target == GRAPH_CACHE_FILES) "graph_cache_v1_usd-krw.json" else prefs
            val t = target.key
            case("$t-empty-dir", target, { _, dir -> File(dir, legacy).mkdirs() }, deleter(), { _, dir ->
                assertTrue("$t: the empty directory stays", File(dir, legacy).isDirectory)
                assertFalse("$t: no delete call for a directory", legacy in deleteCalls)
            })
            case("$t-dir-with-child", target, { _, dir -> put(File(dir, legacy), "child") }, deleter(), { _, dir ->
                assertTrue("$t: no recursion", File(dir, "$legacy/child").exists())
                assertFalse(legacy in deleteCalls)
            })
            case("$t-refused", target, { _, dir -> put(dir, legacy) }, deleter(refuse = setOf(legacy)), { _, dir ->
                assertTrue(File(dir, legacy).exists())
            })
            case("$t-true-but-kept", target, { _, dir -> put(dir, legacy) }, deleter(keep = setOf(legacy)), { _, dir ->
                assertTrue("$t: the second listing finds it", File(dir, legacy).exists())
            })
            case("$t-stops-listing", target, { _, dir -> put(dir, legacy) }, deleter(after = { file ->
                val dir = file.parentFile!!
                dir.deleteRecursively()
                dir.writeText("no longer a directory")
            }))
            case("$t-not-a-directory", target, { files, dir ->
                files.mkdirs()
                if (target == GRAPH_CACHE_FILES) {
                    files.delete()
                    files.writeText("a file")
                } else {
                    dir.writeText("a file")
                }
            })
        }
    }

    /**
     * R06 (G09): within a target, a refusal leaves the rest deleted and the target at CONSUMER_CUTOVER while the other target
     * finishes; with a refusal in each, both fail with their causes and the launcher reports once; the next run without
     * refusals finishes what is left.
     */
    @Test fun R06_partialProgressIsKeptAndRetried() = runBlocking {
        val files = filesDir("files")
        val datastore = File(files, "datastore")
        versioned.forEach { put(files, it) }
        put(datastore, prefs)
        // Refuse whichever file the sweep offers first, so that the other eleven come after the refusal in any listing order.
        var refused: String? = null
        val refuseFirst: (File) -> Boolean = { file ->
            deleteCalls += file.name
            if (refused == null) {
                refused = file.name
                false
            } else {
                file.delete()
            }
        }
        val one = failureOf { migration(open(), files, delete = refuseFirst).run() }
        assertEquals(setOf(GRAPH_CACHE_FILES), one?.failures?.keys)
        assertEquals("only the refused one is left", listOf(checkNotNull(refused)),
            names(files).filter { it.startsWith("graph_cache_") })
        assertEquals(CONSUMER_CUTOVER to LEGACY_DELETED, stages())

        val both = filesDir("both")
        put(both, versioned[0])
        put(File(both, "datastore"), prefs)
        val reports = mutableListOf<Throwable>()
        val cutover = GraphCacheCutover(
            migration(open("both"), both, delete = deleter(refuse = setOf(versioned[0], prefs))),
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
            { reports += it }
        )
        withTimeout(10_000) { cutover.launch().join() }
        assertEquals("reported once", 1, reports.size)
        val failure = reports.single() as GraphCacheMigrationFailure
        assertEquals(setOf(GRAPH_CACHE_FILES, GRAPH_PREFERENCES), failure.failures.keys)
        assertTrue(failure.failures.values.all { it is IllegalStateException })
        assertSame("the first target's cause", failure.failures.getValue(GRAPH_CACHE_FILES), failure.cause)
        assertEquals("the other as suppressed", listOf(failure.failures.getValue(GRAPH_PREFERENCES)), failure.suppressed.toList())
        try {
            GraphCacheMigrationFailure(emptyMap())
            fail("a failure without a target")
        } catch (expected: IllegalArgumentException) {
        }

        migration(open(), files).run()
        assertEquals(LEGACY_DELETED to LEGACY_DELETED, stages())
        assertEquals(emptyList<String>(), names(files).filter { it.startsWith("graph_cache_") })
    }

    /** Refuses only the write that records LEGACY_DELETED for [key]. */
    private class RefuseDeletedRecord(private val delegate: DataStore<Preferences>, private val key: String) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = delegate.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            delegate.updateData { current ->
                transform(current).also { next ->
                    val k = stringPreferencesKey(key)
                    if (next[k] == LEGACY_DELETED.name && current[k] != LEGACY_DELETED.name) throw IOException("record refused")
                }
            }
    }

    /**
     * R07 (G10): for each target, the deletion lands but its LEGACY_DELETED record does not; a new runner over the reopened
     * journal checks absence and records it without asking the readiness again.
     */
    @Test fun R07_aLostDeletionRecordIsRecoveredWithoutAnotherCutover() = runBlocking {
        for (target in listOf(GRAPH_CACHE_FILES, GRAPH_PREFERENCES)) {
            val name = target.key
            val files = filesDir(name)
            put(files, versioned[0])
            put(File(files, "datastore"), prefs)
            val failure = failureOf { migration(open(name) { RefuseDeletedRecord(it, target.key) }, files).run() }
            assertEquals(name, setOf(target), failure?.failures?.keys)
            assertTrue(name, failure!!.failures.getValue(target) is IOException)
            assertFalse("$name: the deletion landed", File(files, versioned[0]).exists() || File(files, "datastore/$prefs").exists())
            val (cache, pref) = stages(name)
            assertEquals(name, CONSUMER_CUTOVER, if (target == GRAPH_CACHE_FILES) cache else pref)

            migration(open(name), files, never).run()
            assertEquals(name, LEGACY_DELETED to LEGACY_DELETED, stages(name))
        }
        assertEquals("the readiness was not asked again", 0, neverAsked)
    }

    /**
     * R08 (G11): after LEGACY_DELETED, legacy data written back is deleted again with the journal bytes unchanged and no
     * readiness; a refusal or a directory that does not list fails and leaves LEGACY_DELETED.
     */
    @Test fun R08_legacyDataWrittenBackIsDeletedAgain() = runBlocking {
        val files = filesDir("files")
        val datastore = File(files, "datastore")
        migration(open(), files).run()
        close()
        val journal = journalFile("j").readBytes()
        put(files, versioned[1])
        put(files, unversioned[2])
        put(datastore, prefs)
        migration(open(), files, never).run()
        close()
        assertEquals("no legacy left", emptyList<String>(), names(files).filter { it != "datastore" } + names(datastore))
        assertTrue("the journal is not written", journal.contentEquals(journalFile("j").readBytes()))

        put(files, versioned[1])
        val refused = failureOf { migration(open(), files, never, deleter(refuse = setOf(versioned[1]))).run() }
        assertEquals(setOf(GRAPH_CACHE_FILES), refused?.failures?.keys)
        datastore.deleteRecursively()
        datastore.writeText("a file")
        File(files, versioned[1]).delete()
        val unlisted = failureOf { migration(open(), files, never).run() }
        assertEquals(setOf(GRAPH_PREFERENCES), unlisted?.failures?.keys)
        assertEquals(LEGACY_DELETED to LEGACY_DELETED, stages())
        assertEquals("no readiness on a re-sweep", 0, neverAsked)
    }

    /**
     * R09 (G12): while a run is parked in the readiness, a second run does not enter it. The launcher keeps one Job per
     * instance after success, failure and cancellation, and `launch` is synchronized; a failure is reported once, a
     * cancellation never. A cancelled run ends in a CancellationException, not an aggregated failure, and reads no further
     * stage, so the other target is not attempted.
     */
    @Test fun R09_runsAreSerialAndTheLauncherStartsOne() = runBlocking {
        val files = filesDir("files")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ready = Ready(onCall = { n -> if (n == 1) { entered.complete(Unit); release.await() } })
        val m = migration(open(), files, ready)
        val first = async(Dispatchers.Default) { m.run() }
        withTimeout(10_000) { entered.await() }
        val second = async(Dispatchers.Default) { m.run() }
        val deadline = System.currentTimeMillis() + 500
        while (ready.calls == 1 && System.currentTimeMillis() < deadline) delay(10)
        assertEquals("the second run waits for the first", 1, ready.calls)
        release.complete(Unit)
        withTimeout(10_000) { first.await(); second.await() }
        assertEquals("the second run found both finished", 2, ready.calls)

        val reports = mutableListOf<Throwable>()
        val launched = Ready()
        val ok = GraphCacheCutover(migration(open("ok"), filesDir("ok"), launched), CoroutineScope(Dispatchers.IO + SupervisorJob())) { reports += it }
        val job = ok.launch()
        assertSame(job, ok.launch())
        withTimeout(10_000) { job.join() }
        assertSame("after success", job, ok.launch())
        assertEquals(2, launched.calls)
        assertEquals(emptyList<Throwable>(), reports)

        val failing = GraphCacheCutover(migration(open("fail"), filesDir("fail"), Ready(failures = 2)), CoroutineScope(Dispatchers.IO + SupervisorJob())) { reports += it }
        val failed = failing.launch()
        withTimeout(10_000) { failed.join() }
        assertSame("after failure", failed, failing.launch())
        assertEquals(1, reports.size)

        val parked = CompletableDeferred<Unit>()
        val hold = Ready(onCall = { parked.complete(Unit); CompletableDeferred<Unit>().await() })
        val cancelling = GraphCacheCutover(migration(open("cancel"), filesDir("cancel"), hold), CoroutineScope(Dispatchers.IO + SupervisorJob())) { reports += it }
        val cancelled = cancelling.launch()
        withTimeout(10_000) { parked.await() }
        cancelled.cancelAndJoin()
        assertSame("after cancellation", cancelled, cancelling.launch())
        assertEquals("a cancellation is not reported", 1, reports.size)
        assertEquals(DETECTED to null, stages("cancel"))
        assertEquals(1, hold.calls)

        // The run itself: its cancellation comes out as a cancellation, and the other target's stage is never read.
        val reads = CountingReads()
        val direct = CompletableDeferred<Unit>()
        val stuck = Ready(onCall = { direct.complete(Unit); CompletableDeferred<Unit>().await() })
        val r = migration(open("direct") { reads.over(it) }, filesDir("direct"), stuck)
        val running = async(Dispatchers.Default) { r.run() }
        withTimeout(10_000) { direct.await() }
        val before = reads.count
        running.cancel()
        val thrown = runCatching { running.await() }.exceptionOrNull()
        assertTrue("a cancellation, not an aggregated failure: $thrown", thrown is CancellationException)
        assertEquals("no read after the cancellation: the other target was not attempted", before, reads.count)

        assertTrue("launch is synchronized", Modifier.isSynchronized(GraphCacheCutover::class.java.getDeclaredMethod("launch").modifiers))
    }

    /** Counts reads of the journal's data; [over] wraps a store. */
    private class CountingReads {
        @Volatile var count = 0
        fun over(delegate: DataStore<Preferences>): DataStore<Preferences> = object : DataStore<Preferences> {
            override val data: Flow<Preferences> get() = delegate.data.also { count++ }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                delegate.updateData(transform)
        }
    }

    /**
     * R13 (G12, rt05-style timeout): a CancellationException the readiness throws while the run is active is that target's
     * failure - aggregated with its cause, the other target still goes, and the launcher reports it once.
     */
    @Test fun R13_aTimeoutIsAFailureNotACancellation() = runBlocking {
        val files = filesDir("files")
        put(files, versioned[0])
        put(File(files, "datastore"), prefs)
        val timeout = CancellationException("readiness timed out")
        var asked = 0
        val readiness = GraphMigrationReadiness { if (++asked == 1) throw timeout }
        val reports = mutableListOf<Throwable>()
        val cutover = GraphCacheCutover(migration(open(), files, readiness), CoroutineScope(Dispatchers.IO + SupervisorJob())) { reports += it }
        val job = cutover.launch()
        withTimeout(10_000) { job.join() }
        assertFalse("the job was not cancelled", job.isCancelled)
        val failure = reports.single() as GraphCacheMigrationFailure
        assertEquals(setOf(GRAPH_CACHE_FILES), failure.failures.keys)
        assertSame(timeout, failure.failures.getValue(GRAPH_CACHE_FILES))
        assertEquals(DETECTED to LEGACY_DELETED, stages())
        assertTrue(File(files, versioned[0]).exists())
    }

    /**
     * R15 (G12): a cancellation that arrives during the last target's failing sweep - no suspension point in between - still
     * ends the run as a cancellation: nothing is reported and the failed target keeps CONSUMER_CUTOVER.
     */
    @Test fun R15_aCancellationDuringAFailingLastSweepIsStillACancellation() = runBlocking {
        val files = filesDir("files")
        put(File(files, "datastore"), prefs)
        val reports = mutableListOf<Throwable>()
        val job = AtomicReference<Job>()
        val gate = CompletableDeferred<Unit>()
        var asked = 0
        // The second readiness call is the preference target's; it waits until the job is known.
        val readiness = GraphMigrationReadiness { if (++asked == 2) gate.await() }
        val cancelThenRefuse: (File) -> Boolean = { file ->
            deleteCalls += file.name
            checkNotNull(job.get()).cancel()
            false
        }
        val cutover = GraphCacheCutover(
            migration(open(), files, readiness, cancelThenRefuse), CoroutineScope(Dispatchers.IO + SupervisorJob())
        ) { reports += it }
        val launched = cutover.launch()
        job.set(launched)
        gate.complete(Unit)
        withTimeout(10_000) { launched.join() }
        assertEquals("premise: the preference sweep ran and was refused", listOf(prefs), deleteCalls)
        assertTrue(launched.isCancelled)
        assertEquals("a cancellation is not reported", emptyList<Throwable>(), reports)
        assertEquals(LEGACY_DELETED to CONSUMER_CUTOVER, stages())
    }

    /** The first read of the journal's data sees nothing; later reads see the store. */
    private class FirstReadEmpty(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        private var first = true
        override val data: Flow<Preferences>
            get() = if (first) {
                first = false
                kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                delegate.data
            }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            delegate.updateData(transform)
    }

    /**
     * R14 (§3-a): a transition answering anything but Advanced or AlreadyThere fails its target. The cache target's first stage
     * read sees nothing, but its stage is already CONSUMER_CUTOVER when detect runs, which answers Backward - as a concurrent
     * writer would make it: the target fails and nothing is deleted; the other target goes on.
     */
    @Test fun R14_anUnexpectedTransitionFailsItsTarget() = runBlocking {
        val files = filesDir("files")
        put(files, versioned[0])
        val journal = open()
        journal.detect(GRAPH_CACHE_FILES)
        journal.cutover(GRAPH_CACHE_FILES) {}
        val failure = failureOf { migration(open { FirstReadEmpty(it) }, files).run() }
        assertEquals(setOf(GRAPH_CACHE_FILES), failure?.failures?.keys)
        assertTrue(failure!!.failures.getValue(GRAPH_CACHE_FILES) is IllegalStateException)
        assertTrue("nothing deleted", File(files, versioned[0]).exists())
        assertEquals(CONSUMER_CUTOVER to LEGACY_DELETED, stages())
    }

    /**
     * R10 (G13): v2 graph files, other stores, the news file, a store named like the journal and the journal's other keys stay
     * byte for byte. The run takes no UID; this is no substitute for the cutover's UID A/B runtime acceptance.
     */
    @Test fun R10_everythingElseStaysByteForByte() = runBlocking {
        val files = filesDir("files")
        val datastore = File(files, "datastore")
        val keeps = listOf(
            put(File(files, "general/6162/6531/757364"), "1d.json", corrupt),
            put(File(files, "general/6162/6531/757364"), ".graph-v2-abc.tmp"),
            put(File(files, "krx/6162/6531/6b31/757364"), "3m.json"),
            put(datastore, "fxi_backupable_user_intent.preferences_pb"),
            put(datastore, "fxi_free_graph.preferences_pb"),
            put(datastore, "fxi_cache.preferences_pb"),
            put(datastore, "fxi_migration_journal.preferences_pb"),
            put(files, "news_cache.json")
        ).associateWith { it.readBytes() }
        (versioned + unversioned).forEach { put(files, it) }
        put(datastore, prefs)
        val journal = open()
        journal.detect(LegacyMigrationTarget.RATES_CACHE)
        journal.cutover(LegacyMigrationTarget.RATES_CACHE) {}
        migration(journal, files).run()
        keeps.forEach { (file, bytes) -> assertTrue(file.path, file.exists() && bytes.contentEquals(file.readBytes())) }
        assertEquals(listOf("datastore", "general", "krx", "news_cache.json"), names(files))
        assertEquals("the rate target is untouched", CONSUMER_CUTOVER, open().stage(LegacyMigrationTarget.RATES_CACHE))
    }

    /**
     * R11 (G14): with both targets at LEGACY_DELETED the two graph obligations are still owed: USER is OUTSTANDING and
     * CAPABILITY NOT_APPLICABLE for every cause; a purger over just the two answers Deferred naming both, and adapters
     * registered for them are not called.
     */
    @Test fun R11_aFinishedMigrationIsNotAFinishedPurge() = runBlocking {
        migration(open(), filesDir("files")).run()
        assertEquals("premise", LEGACY_DELETED to LEGACY_DELETED, stages())
        val cache = checkNotNull(PurgeManifest.byId("file:graph_cache"))
        val preferences = checkNotNull(PurgeManifest.byId("datastore:fxi_graph_preferences"))
        val namespace = PurgeNamespace("uid-a", "epoch-2", null, PendingPurge("uid-a", "epoch-1", null, setOf(PurgeScope.USER)))
        for (target in listOf(cache, preferences)) for (cause in PurgeCause.entries) {
            assertEquals("${target.id} $cause", TargetDisposition.OUTSTANDING,
                PurgeDecision.disposition(target, PurgeScope.USER, cause, namespace, null))
            assertEquals("${target.id} $cause", TargetDisposition.NOT_APPLICABLE,
                PurgeDecision.disposition(target, PurgeScope.CAPABILITY, cause, namespace, null))
        }
        val called = mutableListOf<String>()
        val adapter = PurgeTargetAdapter { called += it.target.id; TargetOutcome.Removed }
        val result = ManifestScopePurger(mapOf(cache.id to adapter, preferences.id to adapter), listOf(cache, preferences))
            .purgeUserScope(namespace)
        assertTrue("Deferred, got $result", result is PurgeResult.Deferred)
        val reason = (result as PurgeResult.Deferred).reason
        assertTrue(reason, "file:graph_cache(S4)" in reason && "datastore:fxi_graph_preferences(S4)" in reason)
        assertEquals(emptyList<String>(), called)
    }

    /**
     * R12 (G15): an unknown stage value fails its own target - value kept, no readiness, nothing deleted - while the other
     * goes on; a corrupt journal fails both with their causes and deletes nothing; each recorded stage resumes as agreed.
     */
    @Test fun R12_anUnreadableJournalIsAFailureAndEachStageResumes() = runBlocking {
        for (bad in listOf(GRAPH_CACHE_FILES, GRAPH_PREFERENCES)) {
            val name = "bogus-${bad.key}"
            val files = filesDir(name)
            put(files, versioned[0])
            put(File(files, "datastore"), prefs)
            open(name)
            checkNotNull(store).edit { it[stringPreferencesKey(bad.key)] = "BOGUS" }
            val ready = Ready()
            val failure = failureOf { migration(open(name), files, ready).run() }
            assertEquals(name, setOf(bad), failure?.failures?.keys)
            assertTrue(name, failure!!.failures.getValue(bad) is IllegalStateException)
            assertEquals("$name: the value is kept", "BOGUS", rawValue(bad.key))
            assertEquals("$name: only the other target asked", 1, ready.calls)
            assertEquals("$name: its legacy stays", true,
                if (bad == GRAPH_CACHE_FILES) File(files, versioned[0]).exists() else File(files, "datastore/$prefs").exists())
            assertEquals("$name: the other is gone", false,
                if (bad == GRAPH_CACHE_FILES) File(files, "datastore/$prefs").exists() else File(files, versioned[0]).exists())
        }

        val files = filesDir("corrupt")
        put(files, versioned[0])
        put(File(files, "datastore"), prefs)
        close()
        journalFile("corrupt").writeBytes(corrupt)
        val ready = Ready()
        val failure = failureOf { migration(open("corrupt"), files, ready).run() }
        assertEquals(setOf(GRAPH_CACHE_FILES, GRAPH_PREFERENCES), failure?.failures?.keys)
        assertTrue(failure!!.failures.values.all { it is IOException })
        assertEquals(0, ready.calls)
        assertTrue(File(files, versioned[0]).exists() && File(files, "datastore/$prefs").exists())

        val expectedCalls = mapOf<LegacyMigrationStage?, Int>(null to 2, DETECTED to 2, CONSUMER_CUTOVER to 0, LEGACY_DELETED to 0)
        for ((start, calls) in expectedCalls) {
            val name = "from-${start ?: "none"}"
            val dir = filesDir(name)
            put(dir, versioned[0])
            put(File(dir, "datastore"), prefs)
            val journal = open(name)
            for (target in listOf(GRAPH_CACHE_FILES, GRAPH_PREFERENCES)) {
                if (start == null) continue
                journal.detect(target)
                if (start >= CONSUMER_CUTOVER) journal.cutover(target) {}
                if (start == LEGACY_DELETED) journal.deleteLegacy(target) {}
            }
            val ready = Ready()
            migration(journal, dir, ready).run()
            assertEquals(name, calls, ready.calls)
            assertEquals(name, LEGACY_DELETED to LEGACY_DELETED, stages(name))
            assertFalse(name, File(dir, versioned[0]).exists() || File(dir, "datastore/$prefs").exists())
        }
    }

    // --- P3c R04: the real readiness -------------------------------------------------------------------------------------

    /**
     * P3c R04 (`cut_p3c_agreed.r3.md` §4): the real [GraphConsumerReadiness] before and after markReady, for every pair of start
     * stages. Before: a target at none or DETECTED fails with the readiness's IllegalStateException, ends DETECTED and keeps its
     * legacy bytes with no delete call; a target at CONSUMER_CUTOVER or LEGACY_DELETED does not ask and deletes or sweeps again.
     * After markReady, running the same migration again finishes both.
     */
    @Test fun P3cR04_theRealReadinessHoldsTheCutoverUntilMarked() = runBlocking {
        val starts = listOf(null, DETECTED, CONSUMER_CUTOVER, LEGACY_DELETED)
        fun asks(start: LegacyMigrationStage?) = start == null || start == DETECTED
        for (cacheStart in starts) for (prefStart in starts) {
            val name = "p3c-${cacheStart ?: "none"}-${prefStart ?: "none"}"
            val files = filesDir(name)
            val cache = put(files, versioned[0])
            val pref = put(File(files, "datastore"), prefs)
            val journal = open(name)
            for ((target, start) in listOf(GRAPH_CACHE_FILES to cacheStart, GRAPH_PREFERENCES to prefStart)) {
                if (start == null) continue
                journal.detect(target)
                if (start >= CONSUMER_CUTOVER) journal.cutover(target) {}
                if (start == LEGACY_DELETED) journal.deleteLegacy(target) {}
            }
            val readiness = GraphConsumerReadiness()
            deleteCalls.clear()
            val failure = failureOf { migration(open(name), files, readiness, deleter()).run() }
            val asking = buildSet {
                if (asks(cacheStart)) add(GRAPH_CACHE_FILES)
                if (asks(prefStart)) add(GRAPH_PREFERENCES)
            }
            assertEquals(name, asking, failure?.failures?.keys.orEmpty())
            for (cause in failure?.failures?.values.orEmpty()) {
                assertTrue("$name: $cause", cause is IllegalStateException && cause.message == "Graph runtime and screen host are not ready")
            }
            assertEquals(name, (if (asks(cacheStart)) DETECTED else LEGACY_DELETED) to (if (asks(prefStart)) DETECTED else LEGACY_DELETED),
                stages(name))
            if (asks(cacheStart)) {
                assertTrue("$name: cache bytes kept", cache.readBytes().contentEquals(versioned[0].toByteArray()))
                assertFalse("$name: no cache delete call", versioned[0] in deleteCalls)
            } else {
                assertFalse("$name: cache swept", cache.exists())
            }
            if (asks(prefStart)) {
                assertTrue("$name: preference bytes kept", pref.readBytes().contentEquals(prefs.toByteArray()))
                assertFalse("$name: no preference delete call", prefs in deleteCalls)
            } else {
                assertFalse("$name: preference swept", pref.exists())
            }

            readiness.markReady()
            migration(open(name), files, readiness, deleter()).run()
            assertEquals(name, LEGACY_DELETED to LEGACY_DELETED, stages(name))
            assertFalse(name, cache.exists() || pref.exists())
        }
    }

    // --- D: dormancy -----------------------------------------------------------------------------------------------------

    private val main = File("src/main/java")
    private fun sources(): Map<String, String> = main.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(main).invariantSeparatorsPath to it.readText() }

    /**
     * D (P3c `cut_p3c_agreed.r3.md` §2 and §4; S4 CUT-CC5-4 `cut_cc5_agreed.r1.md`): only the process's AppGraphCacheCutover
     * constructs the runner, the launcher and the readiness, and only the topic owner names it; the three carry no DI
     * annotation and the readiness names neither the runner nor the launcher; the Application and the other source sets name
     * none of them; and constructing them with the real readiness touches no file and no journal.
     */
    @Test fun D_dormantUntilTheCutover() = runBlocking {
        val all = sources()
        val migrationPath = "com/jay/fxi/data/local/GraphCacheMigration.kt"
        val cutoverPath = "com/jay/fxi/data/local/GraphCacheCutover.kt"
        val readinessPath = "com/jay/fxi/data/local/GraphConsumerReadiness.kt"
        val appPath = "com/jay/fxi/data/local/AppGraphCacheCutover.kt"
        val ownerPath = "com/jay/fxi/data/remote/TopicRuntimeOwner.kt"
        val three = setOf(migrationPath, cutoverPath, readinessPath)
        assertTrue("premise: the scan sees the four files", (three + appPath).all { it in all } && all.size > 100)
        val referencing = all.filter { (path, text) ->
            path !in three && Regex("""\bGraph(CacheMigration|CacheCutover|MigrationReadiness)\b""").containsMatchIn(text)
        }.keys
        assertEquals("only the process cutover names them outside their files", setOf(appPath), referencing)
        assertFalse("the readiness names neither the runner nor the launcher",
            Regex("""\bGraphCache(Migration|Cutover)\b""").containsMatchIn(all.getValue(readinessPath)))
        assertEquals("the readiness is named by its file, the process cutover and the runner's KDoc", setOf(readinessPath, appPath, migrationPath),
            all.filter { (_, text) -> Regex("""\bGraphConsumerReadiness\b""").containsMatchIn(text) }.keys)
        for ((type, path) in listOf("GraphCacheMigration" to migrationPath, "GraphCacheCutover" to cutoverPath,
                "GraphConsumerReadiness" to readinessPath)) {
            assertEquals("only the process cutover constructs $type", setOf(appPath),
                all.filter { (p, text) -> p != path && Regex("""\b$type\s*\(""").containsMatchIn(text) }.keys)
        }
        assertEquals("beside the three it serves, only the owner names the process cutover", setOf(appPath, ownerPath),
            all.filter { (path, text) -> path !in three && Regex("""\bAppGraphCacheCutover\b""").containsMatchIn(text) }.keys)
        for (path in three) {
            // A qualified annotation (@javax.inject.Singleton) counts too.
            assertFalse(path, Regex("""@(?:[A-Za-z_][\w.]*\.)?(Inject|AssistedInject|Singleton|Module|InstallIn|EntryPoint|Provides|Binds|HiltViewModel)\b""")
                .containsMatchIn(all.getValue(path)))
        }
        assertEquals("the v1 file prefix appears once, as the sweep's constant", 1,
            Regex("graph_cache_").findAll(all.getValue(migrationPath)).count())
        assertFalse(Regex("""GraphCache(Migration|Cutover)|Graph(Migration|Consumer)Readiness|AppGraphCacheCutover""")
            .containsMatchIn(all.getValue("com/jay/fxi/FXiApplication.kt")))
        val others = File("src").walkTopDown()
            .onEnter { it.parentFile?.name != "src" || it.name !in setOf("main", "test", "androidTest") }
            .filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("premise: the other source sets hold Kotlin (benchmark)", others.isNotEmpty())
        assertEquals("no other source set names them", emptyList<String>(), others.filter {
            Regex("""\b(Graph(CacheMigration|CacheCutover|MigrationReadiness|ConsumerReadiness)|AppGraphCacheCutover)\b""").containsMatchIn(it.readText())
        }.map { it.path })

        val files = filesDir("files")
        val datastore = File(files, "datastore")
        val legacy = listOf(
            put(files, versioned[0], corrupt),
            put(datastore, prefs, corrupt)
        ).associateWith { it.readBytes() }
        val journal = open()
        val m = migration(journal, files, GraphConsumerReadiness(), deleter())
        GraphCacheCutover(m, CoroutineScope(Dispatchers.IO + SupervisorJob())) { fail("reported") }
        AppGraphCacheCutover(GraphConsumerReadiness(), journal, files, CoroutineScope(Dispatchers.IO + SupervisorJob()), { fail("reported") }, deleter())
        assertEquals(listOf("datastore", versioned[0]).sorted(), names(files))
        assertEquals(listOf(prefs), names(datastore))
        legacy.forEach { (file, bytes) ->
            assertTrue("${file.path}: legacy bytes unchanged",
                file.isFile && bytes.contentEquals(file.readBytes()))
        }
        assertEquals(emptyList<String>(), deleteCalls)
        assertEquals(null to null, open().let { it.stage(GRAPH_CACHE_FILES) to it.stage(GRAPH_PREFERENCES) })
    }

    // --- S4 CUT-CC5-4: the process's graph cache migration (`cut_cc5_agreed.r1.md`) ------------------------------------

    private fun appCutover(
        readiness: GraphConsumerReadiness,
        journal: LocalMigrationJournal,
        files: File,
        reports: MutableList<Throwable>
    ) = AppGraphCacheCutover(readiness, journal, files, CoroutineScope(Dispatchers.IO + SupervisorJob()), { reports += it }, deleter())

    /**
     * CC5-4-C01: marking and launching runs the migration over the very readiness it marked — another readiness would refuse
     * the cutover — so both v1 graph targets are deleted and journalled, reporting nothing; a repeat returns the same job and
     * deletes nothing more.
     */
    @Test fun `CC5-4-C01 markReadyAndLaunch marks the migration's own readiness, then launches it once`() = runBlocking {
        val files = filesDir("files")
        put(files, versioned[0], corrupt)
        put(File(files, "datastore"), prefs, corrupt)
        val readiness = GraphConsumerReadiness()
        val reports = mutableListOf<Throwable>()
        val app = appCutover(readiness, open(), files, reports)
        val job = app.markReadyAndLaunch()
        job.join()
        assertEquals("CC5-4-C01 nothing reported", emptyList<Throwable>(), reports)
        readiness.requireReady()
        assertEquals("CC5-4-C01 both targets deleted", listOf("datastore"), names(files))
        assertEquals(emptyList<String>(), names(File(files, "datastore")))
        val deletes = deleteCalls.toList()
        assertSame("CC5-4-C01 a repeat returns the same job", job, app.markReadyAndLaunch())
        assertEquals("CC5-4-C01 and deletes nothing more", deletes, deleteCalls)
        close()
        assertEquals(LEGACY_DELETED to LEGACY_DELETED, stages())
    }

    /**
     * CC5-4-C02: a journal past its cutover resumes at the deletion; an unreadable stage of one target is reported once and
     * keeps that target's value, while the other target is still migrated.
     */
    @Test fun `CC5-4-C02 resumes from the journal and reports a failed target once`() = runBlocking {
        val files = filesDir("files")
        put(files, versioned[0], corrupt)
        put(File(files, "datastore"), prefs, corrupt)
        val journal = open()
        checkNotNull(store).edit { it[stringPreferencesKey(GRAPH_CACHE_FILES.key)] = "BOGUS" }
        journal.detect(GRAPH_PREFERENCES)
        journal.cutover(GRAPH_PREFERENCES) {}
        val reports = mutableListOf<Throwable>()
        appCutover(GraphConsumerReadiness(), journal, files, reports).markReadyAndLaunch().join()
        val failure = reports.single() as GraphCacheMigrationFailure
        assertEquals("CC5-4-C02 the unreadable target alone failed", setOf(GRAPH_CACHE_FILES), failure.failures.keys)
        assertEquals("CC5-4-C02 its value kept", "BOGUS", rawValue(GRAPH_CACHE_FILES.key))
        assertEquals("CC5-4-C02 the cache file stays", listOf("datastore", versioned[0]).sorted(), names(files))
        assertEquals("CC5-4-C02 the preferences resumed at the deletion", emptyList<String>(), names(File(files, "datastore")))
        assertEquals(LEGACY_DELETED, journal.stage(GRAPH_PREFERENCES))
    }

    /**
     * CC5-4-C03: the readiness is marked before the migration is launched: when the launch reaches its dispatcher, the
     * readiness already answers.
     */
    @Test fun `CC5-4-C03 the readiness is marked before the launch is dispatched`() = runBlocking {
        val readiness = GraphConsumerReadiness()
        val seen = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val recording = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                seen += runCatching { runBlocking { readiness.requireReady() } }.isSuccess
                Dispatchers.IO.dispatch(context, block)
            }
        }
        val app = AppGraphCacheCutover(readiness, open(), filesDir("files"), CoroutineScope(recording + SupervisorJob()),
            { fail("reported: $it") }, deleter())
        app.markReadyAndLaunch().join()
        assertEquals("CC5-4-C03 the launch's first dispatch finds the readiness marked", true, seen.firstOrNull())
    }
}
