package com.jay.fxi.di

import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jay.fxi.data.free.InstallSeedPrefetch
import com.jay.fxi.data.free.InstallSeedSource
import com.jay.fxi.data.local.BackupableUserIntentStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S4 CUT-CC1 (`cut_cc1_agreed.r3.md` §3): the shared providers. Rows CC1-02..CC1-14 and CC1-D; CC1-01 is in
 * `TopicRuntimeFactoryInjectContractTest` and CC1-06 in `FXiApplicationStartTest`, and the P3a seed dormancy row is replaced in
 * `CutoverFanOutAndSeedContractTest.D01` (CC1-13). The JVM tests have neither Robolectric nor Hilt testing, so provider bodies
 * that need a Context are pinned by source checks and Context-free helpers; the real DI identity of the singletons is left to
 * the separately approved CUT-C03 device run of the final cutover candidate (CC1-10).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CutoverSharedProvidersContractTest {

    @get:Rule val folder = TemporaryFolder()

    // --- source access ----------------------------------------------------------------------------------------------------

    private val src = File("src")

    /** Every Kotlin and Java file outside the test source sets, keyed by its path under src/. */
    private fun production(): Map<String, String> = src.walkTopDown()
        .onEnter { it == src || it.parentFile != src || it.name !in setOf("test", "androidTest") }
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .associate { it.relativeTo(src).invariantSeparatorsPath to it.readText() }

    private fun stripComments(text: String) = text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    private fun main(path: String) = "main/java/com/jay/fxi/$path"

    private val graphModule = main("di/GraphRuntimeModule.kt")
    private val seedModule = main("di/InstallSeedModule.kt")
    private val schedulerModule = main("di/FreeSnapshotSchedulerModule.kt")
    private val prefetchFile = main("data/free/InstallSeedPrefetch.kt")

    /** A DI annotation, qualified or not. */
    private val diAnnotation = Regex("""@(?:[A-Za-z_][\w.]*\.)?(Inject|AssistedInject|Singleton|Module|Provides|Binds|InstallIn|EntryPoint)\b""")

    /** A function declaration with its contiguous annotation block, name and return type. */
    private data class Fn(val file: String, val annotations: Set<String>, val name: String, val returns: String)

    private fun functions(all: Map<String, String>): List<Fn> = all.flatMap { (file, raw) ->
        Regex("""(?m)^[ \t]*((?:@[\w.]+(?:\([^)]*\))?\s+)*)(?:(?:internal|private|public|protected|override|abstract|open|final|suspend|inline)\s+)*fun (\w+)\(([^)]*)\)\s*:\s*([\w.]+)""")
            .findAll(stripComments(raw)).map { m ->
                val names = Regex("""@([\w.]+)""").findAll(m.groupValues[1]).map { it.groupValues[1].substringAfterLast('.') }.toSet()
                Fn(file, names, m.groupValues[2], m.groupValues[4].substringAfterLast('.'))
            }.toList()
    }

    // --- seed fixtures ----------------------------------------------------------------------------------------------------

    /** A read failure that stack-trace recovery hands back as itself. */
    private class ReadFailure : IOException("disk"), CopyableThrowable<ReadFailure> {
        override fun createCopy(): ReadFailure? = null
    }

    private class Reads {
        val calls = AtomicInteger()
        val answers = ArrayDeque<Any>()
        var during: () -> Unit = {}
        fun read(): String {
            val n = calls.incrementAndGet()
            during()
            return when (val next = answers.removeFirstOrNull() ?: "seed-$n") {
                is Throwable -> throw next
                else -> next as String
            }
        }
    }

    /** Counts what it is handed, so a test can tell whether a coroutine started on it. */
    private class Counting(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    private class Rig(val prefetch: InstallSeedPrefetch, val seeds: InstallSeedSource, val reads: Reads, val reports: MutableList<Throwable>, val start: Counting)

    private fun TestScope.prefetchRig(): Rig {
        val io = StandardTestDispatcher(testScheduler, name = "io")
        val start = Counting(StandardTestDispatcher(testScheduler, name = "start"))
        val reads = Reads()
        val reports = mutableListOf<Throwable>()
        val seeds = InstallSeedSource(reads::read, io)
        return Rig(InstallSeedPrefetch(seeds, start) { reports += it }, seeds, reads, reports, start)
    }

    private fun InstallSeedPrefetch.scopeJob(): Job {
        val field = InstallSeedPrefetch::class.java.getDeclaredField("scope").apply { isAccessible = true }
        return checkNotNull((field.get(this) as CoroutineScope).coroutineContext[Job])
    }

    // --- CC1-02 -----------------------------------------------------------------------------------------------------------

    @Test fun CC1_02_buildingTheSeedSourceReadsNothing_andTheProviderReadsOnlyInsideItsIoRead() {
        val reads = Reads()
        InstallSeedSource(reads::read, Dispatchers.IO)
        assertEquals("construction reads nothing", 0, reads.calls.get())
        val code = stripComments(production().getValue(seedModule))
        val provider = Regex("""fun provideInstallSeedSource\([^)]*\)\s*:\s*InstallSeedSource\s*=\s*([\s\S]*?)\n\s*\n""")
            .find(code)?.groupValues?.get(1)
        assertEquals("the provider body",
            "InstallSeedSource({ installSeed(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) }, Dispatchers.IO)",
            provider?.trim()?.replace(Regex("""\s+"""), " "))
        assertEquals("getSharedPreferences appears once, inside the read lambda", 1, Regex("""getSharedPreferences\(""").findAll(code).count())
    }

    // --- CC1-03, CC1-11, CC1-12 -------------------------------------------------------------------------------------------

    @Test fun CC1_03_buildingThePrefetchStartsNothing() = runTest {
        val rig = prefetchRig()
        runCurrent()
        assertEquals("construction starts no read", 0, rig.reads.calls.get())
        assertEquals(0, rig.start.dispatches)
        assertNull(rig.seeds.current)
    }

    @Test fun CC1_03_11_theStartReadsOnceOnItsDispatcher_returnsAtOnce_andRepeatsAsTheSameJob() = runTest {
        val rig = prefetchRig()
        val job = rig.prefetch.launch()
        assertEquals("the start runs on its own dispatcher", 1, rig.start.dispatches)
        assertFalse("it returns before the read", job.isCompleted)
        assertEquals(0, rig.reads.calls.get())
        assertSame("a repeated start is the same Job", job, rig.prefetch.launch())
        runCurrent()
        assertTrue(job.isCompleted && !job.isCancelled)
        assertSame(job, rig.prefetch.launch())
        assertEquals("one read", 1, rig.reads.calls.get())
        assertEquals("seed-1", rig.seeds.current)
        assertEquals(emptyList<Throwable>(), rig.reports)
        val code = stripComments(production().getValue(seedModule))
        assertTrue("production reads on IO and reports to Crashlytics",
            Regex("""InstallSeedPrefetch\(seeds,\s*Dispatchers\.IO\)\s*\{\s*FirebaseCrashlytics\.getInstance\(\)\.recordException\(it\)\s*}""")
                .containsMatchIn(code))
    }

    @Test fun CC1_11_aFailedReadIsReportedOnce_theJobFails_theScopeLives_andTheNextGetReadsAgain() = runTest {
        val rig = prefetchRig()
        val failure = ReadFailure()
        rig.reads.answers += failure
        val job = rig.prefetch.launch()
        runCurrent()
        assertEquals("reported once, the very failure", listOf<Throwable>(failure), rig.reports)
        assertTrue("the Job ended failed", job.isCompleted && job.isCancelled)
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        assertSame("failed with the read's failure, not cancelled", failure, cause)
        assertTrue("the process scope lives on", rig.prefetch.scopeJob().isActive)
        assertNull("nothing cached", rig.seeds.current)
        assertSame("a repeated start is the same failed Job, no second read", job, rig.prefetch.launch())
        runCurrent()
        assertEquals(1, rig.reads.calls.get())
        var next: String? = null
        backgroundScope.launch { next = rig.seeds.get() }
        runCurrent()
        assertEquals("the next get reads again", "seed-2", next)
        assertEquals(1, rig.reports.size)
    }

    /** The oracle is runTest itself: an exception escaping the handler reaches its collector and fails the test. */
    @Test fun CC1_11_aReportThatThrowsDoesNotEscapeTheHandler() = runTest {
        val io = StandardTestDispatcher(testScheduler, name = "io")
        val reads = Reads().apply { answers += ReadFailure() }
        val seeds = InstallSeedSource(reads::read, io)
        val prefetch = InstallSeedPrefetch(seeds, io) { throw IllegalStateException("report") }
        prefetch.launch()
        runCurrent()
        assertTrue(prefetch.scopeJob().isActive)
    }

    @Test fun CC1_11_startsAreSerialisedOnThePrefetch() {
        val reads = Reads()
        val seeds = InstallSeedSource(reads::read, Dispatchers.Unconfined)
        val prefetch = InstallSeedPrefetch(seeds, Dispatchers.Unconfined) {}
        var started: Job? = null
        val other = Thread { started = prefetch.launch() }.apply { isDaemon = true }
        synchronized(prefetch) {
            other.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (other.state != Thread.State.BLOCKED && other.isAlive && System.nanoTime() < deadline) Thread.onSpinWait()
            assertEquals("a second start waits on the prefetch's monitor", Thread.State.BLOCKED, other.state)
        }
        other.join(10_000)
        assertNotNull(started)
        assertSame(started, prefetch.launch())
        assertEquals(1, reads.calls.get())
    }

    @Test fun CC1_12_aCancelBeforePublishReportsNothingAndCachesNothing() = runTest {
        val rig = prefetchRig()
        var job: Job? = null
        rig.reads.during = { if (rig.reads.calls.get() == 1) job?.cancel() }
        job = rig.prefetch.launch()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals("a cancellation is not reported", emptyList<Throwable>(), rig.reports)
        assertNull("nothing published", rig.seeds.current)
        var next: String? = null
        backgroundScope.launch { next = rig.seeds.get() }
        runCurrent()
        assertEquals("the next call reads again after the read ended", "seed-2", next)
    }

    /** Cancelling a start that already published leaves the seed; the in-window case is P3a S07's. */
    @Test fun CC1_12_cancellingAFinishedStartKeepsThePublishedSeed() = runTest {
        val rig = prefetchRig()
        val done = rig.prefetch.launch()
        runCurrent()
        assertEquals("seed-1", rig.seeds.current)
        done.cancelAndJoin()
        assertEquals("seed-1", rig.seeds.current)
        assertEquals(emptyList<Throwable>(), rig.reports)
    }

    // --- CC1-04 -----------------------------------------------------------------------------------------------------------

    @Test fun CC1_04_buildingTheSchedulerReaderReadsNothing_untilTheSchedulerAsks() {
        val reads = Reads()
        val seeds = InstallSeedSource(reads::read, Dispatchers.Unconfined)
        val installId = freeSchedulerInstallId(seeds)
        assertEquals("building the reader reads nothing", 0, reads.calls.get())
        assertNull(seeds.current)
        assertEquals("seed-1", installId())
        assertEquals(1, reads.calls.get())
        val all = production()
        val code = stripComments(all.getValue(schedulerModule))
        assertTrue("the provider hands the scheduler the reader itself",
            Regex("""installId\s*=\s*freeSchedulerInstallId\(seeds\)\s*\)""").containsMatchIn(code))
        assertTrue("the reader body", Regex(
            """internal fun freeSchedulerInstallId\(seeds: InstallSeedSource\): \(\) -> String\s*=\s*\{\s*seeds\.current \?: runBlocking \{ seeds\.get\(\) }\s*}"""
        ).containsMatchIn(code))
        val stripped = all.mapValues { (_, text) -> stripComments(text) }
        assertEquals("the fallback is the free scheduler's only", mapOf(schedulerModule to 2),
            stripped.mapValues { (_, text) -> Regex("""\bfreeSchedulerInstallId\b""").findAll(text).count() }.filterValues { it > 0 })
        for (path in listOf(graphModule, seedModule, prefetchFile)) {
            assertFalse("$path blocks nowhere", Regex("""\brunBlocking\b""").containsMatchIn(stripped.getValue(path)))
        }
    }

    /** An interrupted thread makes runBlocking throw before it runs anything, so only a memory read survives it. */
    @Test fun CC1_04_afterAPublishedPrefetchTheSchedulerReadsMemoryOnly() {
        val reads = Reads()
        val seeds = InstallSeedSource(reads::read, Dispatchers.Unconfined)
        val prefetch = InstallSeedPrefetch(seeds, Dispatchers.Unconfined) {}
        runBlocking { prefetch.launch().join() }
        assertEquals("seed-1", seeds.current)
        val installId = freeSchedulerInstallId(seeds)
        Thread.currentThread().interrupt()
        val value = try {
            installId()
        } finally {
            Thread.interrupted()
        }
        assertEquals("seed-1", value)
        assertEquals("no read beyond the prefetch", 1, reads.calls.get())
    }

    @Test fun CC1_04_aPrefetchAndTheSchedulerOverlappingShareOneRead() {
        val pool = Executors.newFixedThreadPool(2)
        val io = pool.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val seeds = InstallSeedSource({
            val n = calls.incrementAndGet()
            if (n == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            "seed-$n"
        }, io)
        val reports = mutableListOf<Throwable>()
        val prefetch = InstallSeedPrefetch(seeds, io) { synchronized(reports) { reports += it } }
        try {
            val job = prefetch.launch()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            var value: String? = null
            val scheduler = Thread { value = freeSchedulerInstallId(seeds)() }.apply { isDaemon = true }
            scheduler.start()
            // Wait until the scheduler is really parked in its fallback, behind the prefetch's read.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline && !(scheduler.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) &&
                    scheduler.stackTrace.any { it.methodName == "joinBlocking" })) Thread.onSpinWait()
            assertTrue("premise: the scheduler waits in its fallback", scheduler.stackTrace.any { it.methodName == "joinBlocking" })
            release.countDown()
            scheduler.join(10_000)
            runBlocking { job.join() }
            assertFalse(scheduler.isAlive)
            assertEquals("the scheduler waits for the same read", "seed-1", value)
            assertEquals("one read", 1, calls.get())
            assertEquals(emptyList<Throwable>(), reports)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun CC1_04_aPrefetchThatFailedBeforePublishLeavesTheSchedulerANextRead() {
        // Real threads: the scheduler's fallback blocks its own thread, so the shared source must read elsewhere.
        val pool = Executors.newSingleThreadExecutor()
        val io = pool.asCoroutineDispatcher()
        val reads = Reads().apply { answers += ReadFailure() }
        val seeds = InstallSeedSource(reads::read, io)
        val reports = mutableListOf<Throwable>()
        val prefetch = InstallSeedPrefetch(seeds, io) { synchronized(reports) { reports += it } }
        try {
            runBlocking { prefetch.launch().join() }
            assertNull(seeds.current)
            assertEquals(1, synchronized(reports) { reports.size })
            assertEquals("the fallback performs the next read", "seed-2", freeSchedulerInstallId(seeds)())
            assertEquals(2, reads.calls.get())
            assertEquals("seed-2", seeds.current)
        } finally {
            pool.shutdownNow()
        }
    }

    // --- CC1-05 -----------------------------------------------------------------------------------------------------------

    /** A SharedPreferences over a map; apply() and commit() both commit to it, counted apart. */
    private class Prefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var applies = 0
        var commits = 0
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { staged[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
            override fun commit(): Boolean {
                commits++
                values.putAll(staged)
                return true
            }
            override fun apply() {
                applies++
                values.putAll(staged)
            }
        }
    }

    @Test fun CC1_05_theMovedReaderKeepsAnExistingSeed_andCreatesOneRandomSeedOnce() {
        val existing = Prefs().apply { values["install_seed"] = "kept" }
        assertEquals("kept", installSeed(existing))
        assertEquals("no write for an existing seed", 0, existing.applies + existing.commits)

        val fresh = Prefs()
        val created = installSeed(fresh)
        assertTrue("a random (v4) UUID in its usual form: $created",
            Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$""").matches(created))
        assertEquals("stored under the same key", created, fresh.values["install_seed"])
        assertEquals("saved with apply", 1 to 0, fresh.applies to fresh.commits)
        assertEquals("a second call creates nothing", created, installSeed(fresh))
        assertEquals(1 to 0, fresh.applies to fresh.commits)
        assertNotEquals("two fresh installs draw two seeds", installSeed(Prefs()), installSeed(Prefs()))
        val code = stripComments(production().getValue(seedModule))
        assertTrue("the same prefs name", Regex("""PREFS\s*=\s*"free_snapshot"""").containsMatchIn(code))
        assertTrue("the same key", Regex("""KEY_INSTALL_SEED\s*=\s*"install_seed"""").containsMatchIn(code))
    }

    // --- CC1-07 -----------------------------------------------------------------------------------------------------------

    @Test fun CC1_07_theInterfaceProviderReturnsTheConcreteStoreItself_andTheClassIsASingleton() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "intent.preferences_pb") }
            val store = BackupableUserIntentStore(dataStore)
            assertSame(store, GraphRuntimeModule.provideGraphSelectionStore(store))
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        val code = stripComments(production().getValue(main("data/local/BackupableUserIntentStore.kt")))
        assertTrue("the class is a singleton", Regex("""@Singleton\s+class BackupableUserIntentStore\b""").containsMatchIn(code))
        assertTrue(Regex("""(?m)^import javax\.inject\.Singleton$""").containsMatchIn(code))
        assertEquals("one @Inject in the store's file", 1, Regex("""@(?:[\w.]+\.)?Inject\b""").findAll(code).count())
        assertTrue("on its Context constructor",
            Regex("""@Inject\s+constructor\(@ApplicationContext context: Context\)""").containsMatchIn(code))
    }

    // --- CC1-08 -----------------------------------------------------------------------------------------------------------

    @Test fun CC1_08_theDiskHelperCreatesNoDirectory_andTheProviderRootIsNoBackupGraphV2() {
        val parent = folder.newFolder("nobackup")
        val root = File(parent, "graph_v2")
        var asked = 0
        graphV2DiskStoreAt { asked += 1; root }
        assertEquals("S4 CUT-CC4a: constructing never asks for the root", 0, asked)
        assertFalse("no root created", root.exists())
        assertEquals("nothing created under the parent", emptyList<String>(), parent.list()!!.toList())
        val all = production()
        val module = stripComments(all.getValue(graphModule))
        assertTrue("the helper body", Regex(
            """fun graphV2DiskStoreAt\(root: \(\) -> File\): FileGraphV2DiskStore\s*=\s*FileGraphV2DiskStore\(root,\s*JsonGraphV2EnvelopeCodec\(\),\s*DefaultGraphV2AtomicFileIo\(\),\s*Dispatchers\.IO\)"""
        ).containsMatchIn(module))
        assertTrue("the provider root", Regex(
            """graphV2DiskStoreAt\s*\{\s*File\(context\.noBackupFilesDir,\s*"graph_v2"\)\s*}"""
        ).containsMatchIn(module))
        // The construction path: the three classes run nothing at construction beyond these known initializers.
        val disk = stripComments(all.getValue(main("data/graph/GraphV2DiskStore.kt")))
        val codec = stripComments(all.getValue(main("data/graph/GraphV2Disk.kt")))
        val allowed = Regex("""^(Any\(\)|Mutex\(\)|0L|mutable(Map|Set)Of<.*>\(\)|Json \{ encodeDefaults = true \})$""")
        for ((name, text) in listOf("FileGraphV2DiskStore" to disk, "DefaultGraphV2AtomicFileIo" to disk, "JsonGraphV2EnvelopeCodec" to codec)) {
            val body = classBody(text, name)
            assertFalse("$name has no init block", Regex("""\binit\s*\{""").containsMatchIn(body))
            val initializers = Regex("""(?m)^ {4}(?:(?:private|internal|override)\s+)*(?:val|var) \w+(?:\s*:\s*[^=\n]+)?\s*=\s*(.+)$""")
                .findAll(body).map { it.groupValues[1].trim() }.toList()
            for (initializer in initializers) {
                assertTrue("$name initializer is a known no-I/O value: $initializer", allowed.matches(initializer))
            }
        }
    }

    /** The text of class [name]'s body, from its declaration to the matching brace. */
    private fun classBody(text: String, name: String): String {
        val start = checkNotNull(Regex("""\bclass $name\b""").find(text)) { "class $name not found" }.range.first
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("unbalanced class $name")
    }

    // --- CC1-09 -----------------------------------------------------------------------------------------------------------

    @Test fun CC1_09_thePrefetchIsResolvedOnlyAfterAdmissionAndFirebase() {
        val app = production().getValue(main("FXiApplication.kt"))
        val closed = app.indexOf("if (!appOwnedServicesOpen)")
        val firebase = app.indexOf("FirebaseApp.initializeApp(this)")
        assertTrue("fixture: admission before Firebase", closed in 0 until firebase)
        val field = checkNotNull(Regex("""lateinit var (\w+): Provider<InstallSeedPrefetch>""").find(app)) { "no Provider field" }.groupValues[1]
        val reads = Regex("""\b$field\.get\(\)""").findAll(app).map { it.range.first }.toList()
        assertEquals("resolved once", 1, reads.size)
        assertTrue("after admission and Firebase", reads.single() > firebase)
        assertTrue("the start only launches", Regex("""prefetchInstallSeed\s*=\s*\{\s*$field\.get\(\)\.launch\(\)\s*}""").containsMatchIn(app))
    }

    // --- CC1-10 -----------------------------------------------------------------------------------------------------------

    @Test fun CC1_10_eachSharedTypeHasOneSingletonProviderInAnInstalledModule() {
        val all = production()
        val stripped = all.mapValues { (_, text) -> stripComments(text) }
        for (path in listOf(graphModule, seedModule)) {
            val text = stripped.getValue(path)
            assertTrue("$path is a Module installed in the SingletonComponent", Regex(
                """@Module\s+@InstallIn\(SingletonComponent::class\)\s+internal object \w+"""
            ).containsMatchIn(text))
            assertTrue(Regex("""(?m)^import javax\.inject\.Singleton$""").containsMatchIn(text))
            assertTrue(Regex("""(?m)^import dagger\.hilt\.components\.SingletonComponent$""").containsMatchIn(text))
        }
        val fns = functions(all)
        val expected = mapOf(
            "TopicUseAuthority" to graphModule,
            "DeletionAdmissionStore" to graphModule,
            "GraphSelectionStore" to graphModule,
            "FileGraphV2DiskStore" to graphModule,
            "InstallSeedSource" to seedModule,
            "InstallSeedPrefetch" to seedModule
        )
        for ((type, path) in expected) {
            val providers = fns.filter { it.returns == type && ("Provides" in it.annotations || "Binds" in it.annotations) }
            assertEquals("$type has exactly one provider, in $path", listOf(path), providers.map { it.file })
            assertEquals("$type: exactly @Provides @Singleton, no qualifier", setOf("Provides", "Singleton"), providers.single().annotations)
        }
        val bindingAnnotation = Regex("""@(?:[\w.]+\.)?(Provides|Binds)\b""")
        for ((file, text) in stripped.filterKeys { it.endsWith(".kt") }) {
            val annotated = bindingAnnotation.findAll(text).count()
            val parsed = fns.count { it.file == file && ("Provides" in it.annotations || "Binds" in it.annotations) }
            assertEquals("$file: every @Provides/@Binds is on a function whose return type the check reads", annotated, parsed)
        }
        // The checks read simple names, so an alias of a DI annotation or of a shared type would hide a binding: none is allowed.
        val relevant = setOf("Provides", "Binds", "Module", "InstallIn", "Singleton", "Inject", "Named", "Qualifier",
            "TopicUseAuthority", "SnapshotTopicUseAuthority", "DeletionAdmissionStore", "GraphSelectionStore", "BackupableUserIntentStore",
            "FileGraphV2DiskStore", "InstallSeedSource", "InstallSeedPrefetch")
        for ((file, text) in stripped) {
            for (m in Regex("""(?m)^import ([\w.]+) as (\w+)""").findAll(text)) {
                assertFalse("$file aliases ${m.groupValues[1]}", m.groupValues[1].substringAfterLast('.') in relevant)
            }
            for (m in Regex("""(?m)^\s*(?:(?:internal|private|public)\s+)?typealias \w+(?:<[^>]*>)?\s*=\s*([\w.]+)""").findAll(text)) {
                assertFalse("$file typealiases ${m.groupValues[1]}", m.groupValues[1].substringAfterLast('.') in relevant)
            }
        }
        assertEquals("no Java binding in production", emptySet<String>(),
            stripped.filter { (file, text) -> file.endsWith(".java") && bindingAnnotation.containsMatchIn(text) }.keys)
        assertEquals("the concrete selection store has no provider or binding", emptyList<Fn>(),
            fns.filter { it.returns == "BackupableUserIntentStore" && it.annotations.isNotEmpty() })
        for (path in listOf("data/entitlements/DeletionAdmissionStore.kt", "data/graph/GraphV2DiskStore.kt", "data/free/InstallSeedSource.kt",
                "data/free/InstallSeedPrefetch.kt", "data/entitlements/SnapshotTopicUseAuthority.kt")) {
            assertFalse("$path has no @Inject", Regex("""@(?:[\w.]+\.)?Inject\b""").containsMatchIn(stripped.getValue(main(path))))
        }
        assertTrue("the alias provider returns its argument", Regex(
            """fun provideGraphSelectionStore\(store: BackupableUserIntentStore\): GraphSelectionStore\s*=\s*store\b"""
        ).containsMatchIn(stripped.getValue(graphModule)))
        assertTrue("the authority reads the issuer's snapshot", Regex(
            """fun provideTopicUseAuthority\(coordinator: PremiumAccessCoordinator\): TopicUseAuthority\s*=\s*SnapshotTopicUseAuthority\s*\{\s*coordinator\.accessSnapshot\s*}"""
        ).containsMatchIn(stripped.getValue(graphModule)))
    }

    // --- CC1-D ------------------------------------------------------------------------------------------------------------

    /**
     * CC1-D: the three stores have no production consumer yet; only their declarations, dormant parameters and providers name
     * them. From CUT-CC3 the deletion store also has its one producer, the settings screen's deletion stage (CC3-W02).
     */
    @Test fun CC1_D_theThreeStoresAreProvidedButConsumedNowhere() {
        val all = production()
        assertTrue("premise: the scan reaches the benchmark source set", all.keys.any { it.startsWith("benchmark/") })
        val allowed = mapOf(
            "DeletionAdmissionStore" to setOf(main("data/entitlements/DeletionAdmissionStore.kt"), main("data/graph/GraphProtectedAdmission.kt"), graphModule,
                main("ui/settings/SettingsViewModel.kt"), main("data/graph/ProcessGraphBuilder.kt")),
            "GraphSelectionStore" to setOf(main("data/local/BackupableUserIntentStore.kt"), main("data/graph/GraphSeriesSelectionSession.kt"), graphModule),
            "BackupableUserIntentStore" to setOf(main("data/local/BackupableUserIntentStore.kt"), main("data/entitlements/purge/GraphSelectionPurgeAdapter.kt"), graphModule),
            "FileGraphV2DiskStore" to setOf(main("data/graph/GraphV2DiskStore.kt"), graphModule, main("data/graph/ProcessGraphBuilder.kt"))
        )
        for ((type, files) in allowed) {
            assertEquals("only the allowed files name $type", files,
                all.filter { (_, text) -> Regex("""\b$type\b""").containsMatchIn(text) }.keys)
        }
        val stripped = all.mapValues { (_, text) -> stripComments(text) }
        // File:symbol granularity: in each allowed file the code names the type exactly as often as its declaration, its dormant
        // parameter or its provider needs, so a further field, parameter, Provider or resolution in that file shows up.
        val counts = mapOf(
            "DeletionAdmissionStore" to mapOf(main("data/entitlements/DeletionAdmissionStore.kt") to 1, main("data/graph/GraphProtectedAdmission.kt") to 2, graphModule to 3,
                main("ui/settings/SettingsViewModel.kt") to 4, main("data/graph/ProcessGraphBuilder.kt") to 2),
            "GraphSelectionStore" to mapOf(main("data/graph/GraphSeriesSelectionSession.kt") to 2, main("data/local/BackupableUserIntentStore.kt") to 2, graphModule to 2),
            "BackupableUserIntentStore" to mapOf(main("data/entitlements/purge/GraphSelectionPurgeAdapter.kt") to 2, main("data/local/BackupableUserIntentStore.kt") to 1, graphModule to 2),
            "FileGraphV2DiskStore" to mapOf(main("data/graph/GraphV2DiskStore.kt") to 1, graphModule to 4, main("data/graph/ProcessGraphBuilder.kt") to 1)
        )
        for ((type, expected) in counts) {
            assertEquals("$type is named exactly where and as often as allowed", expected,
                stripped.mapValues { (_, text) -> Regex("""\b$type\b""").findAll(text).count() }.filterValues { it > 0 })
        }
        // The dormant providers are declared and never called or referenced; the disk helper is called by its provider only.
        for ((name, expected) in listOf("provideDeletionAdmissionStore" to 1, "provideGraphSelectionStore" to 1, "provideGraphV2DiskStore" to 1, "graphV2DiskStoreAt" to 2)) {
            assertEquals("$name is used only as declared", mapOf(graphModule to expected),
                stripped.mapValues { (_, text) -> Regex("""\b$name\b""").findAll(text).count() }.filterValues { it > 0 })
        }
        assertEquals("the stores are constructed only by the graph module, twice", mapOf(graphModule to 2),
            stripped.mapValues { (_, text) ->
                Regex("""(?<!class )\b(DeletionAdmissionStore|BackupableUserIntentStore|FileGraphV2DiskStore)\s*\(""").findAll(text).count()
            }.filterValues { it > 0 })
        // The dormant classes that take them are constructed nowhere in production, their own files included, and carry no binding.
        // S4 CUT-CC4b: the process graph builder constructs the admission and the cache ports, once each, inside build().
        for (type in listOf("GraphProtectedAdmission", "GraphSeriesSelectionSession", "GraphSelectionPurgeAdapter", "GraphV2CachePorts", "GraphV2PurgeAdapter")) {
            val expected = if (type == "GraphProtectedAdmission" || type == "GraphV2CachePorts") mapOf(main("data/graph/ProcessGraphBuilder.kt") to 1) else emptyMap()
            assertEquals("$type is constructed only where CC4b builds the graph", expected,
                stripped.mapValues { (_, text) -> Regex("""(?<!class )\b$type\s*\(""").findAll(text).count() }.filterValues { it > 0 })
        }
        for (path in listOf("data/graph/GraphProtectedAdmission.kt", "data/graph/GraphSeriesSelectionSession.kt",
                "data/entitlements/purge/GraphSelectionPurgeAdapter.kt", "data/entitlements/DeletionAdmissionStore.kt", "data/graph/GraphV2DiskStore.kt",
                "data/graph/GraphV2Cache.kt")) {
            assertFalse("$path carries no DI annotation", diAnnotation.containsMatchIn(stripped.getValue(main(path))))
        }
    }

    // --- CC1-14 -----------------------------------------------------------------------------------------------------------

    @Test fun CC1_14_theStartWiringBuildsNoGraphRuntime() {
        val all = production().mapValues { (_, text) -> stripComments(text) }
        val graphNames = listOf(
            "GraphRuntimeAssembly", "GraphScreenHost", "GraphCacheMigration", "GraphCacheCutover", "GraphConsumerReadiness",
            "GraphV2RequestCoordinator", "AuthenticatedGraphV2Fetcher", "GraphV2ScreenStateHolder", "GraphRecorder",
            "getGraphV2Tab", "getGraphV2Catalog"
        )
        val touched = listOf(
            main("FXiApplication.kt"), graphModule, seedModule, schedulerModule, prefetchFile, main("data/free/InstallSeedSource.kt"),
            main("data/free/FreeSnapshotScheduler.kt"), main("data/remote/TopicRuntime.kt"), main("data/local/BackupableUserIntentStore.kt")
        )
        for (path in touched) {
            val text = all.getValue(path)
            for (name in graphNames) assertFalse("$path names $name", Regex("""\b$name\b""").containsMatchIn(text))
        }
    }

    // --- CUT-CC3 ----------------------------------------------------------------------------------------------------------

    /**
     * CC3-W01/W03: the settings screen takes the injected deletion store through an internal @Inject constructor and hands that
     * same instance to its deletion stage; nothing in production releases a request, and the only reader of the store remains
     * the dormant graph admission, which is still constructed nowhere (CC1-D). A regression device on the source; real DI
     * identity stays with CUT-C03.
     */
    @Test fun CC3_W01_W03_theSettingsScreenPublishesThroughTheInjectedStoreAndReleasesNothing() {
        val all = production().mapValues { (_, text) -> stripComments(text) }
        val vm = all.getValue(main("ui/settings/SettingsViewModel.kt"))
        assertTrue("CC3-W01 the screen's constructor takes the store",
            Regex("""class SettingsViewModel @Inject internal constructor\([^)]*\bdeletions: DeletionAdmissionStore\s*\)""").containsMatchIn(vm))
        assertTrue("CC3-W01 and hands that store to the stage",
            vm.contains("private val accountDeletionServerStage = AccountDeletionServerStage(apiService, deletions)"))
        assertEquals("CC3-W01 the stage is built only there", mapOf(main("ui/settings/SettingsViewModel.kt") to 1),
            all.mapValues { (_, text) -> Regex("""(?<!class )\bAccountDeletionServerStage\(""").findAll(text).count() }.filterValues { it > 0 })
        // Counted on the raw text too: a string holding "//" would hide the rest of its line from the stripped copy.
        val raw = production()
        assertEquals("CC3-W03 no production call releases a request", mapOf(main("data/entitlements/DeletionAdmissionStore.kt") to 1),
            raw.mapValues { (_, text) -> Regex("""\breleaseUnsent\b""").findAll(text).count() }.filterValues { it > 0 })
        assertEquals("CC3-W02 the only blocks call is the dormant graph admission's", mapOf(
            main("data/entitlements/DeletionAdmissionStore.kt") to 1, main("data/graph/GraphProtectedAdmission.kt") to 1
        ), raw.mapValues { (_, text) -> Regex("""\bblocks\s*\(|::blocks\b""").findAll(text).count() }.filterValues { it > 0 })
        assertEquals("CC3-W02 nothing in production reads the diagnostic records", emptyMap<String, Int>(),
            raw.mapValues { (_, text) -> Regex("""\.records\b""").findAll(text).count() }.filterValues { it > 0 })
        // The screen's file names its store exactly where the wiring needs it, and touches it only through the stage's three writes.
        assertEquals("CC3-W02 the screen's file names `deletions` as often as the wiring needs", 9,
            Regex("""\bdeletions\b""").findAll(vm).count())
        assertEquals("CC3-W02 the screen's file writes the store only through the stage", listOf("begin", "serverDeleted", "serverDeleted"),
            Regex("""\bdeletions\s*(?:\.|::)\s*(\w+)""").findAll(vm).map { it.groupValues[1] }.toList())
    }
}
