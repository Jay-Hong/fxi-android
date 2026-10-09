package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.free.InstallSeedSource
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 CUT-P3a (`cut_p3a_agreed.r2.md`): the grant fan-out (session, then graph bridge) and the install seed source, both dormant.
 * The fan-out over the real deliverer is `TopicGrantDelivererTest.aFanOutSink_handsBothDelegatesTheDeliverersCalls_firstThenSecond`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CutoverFanOutAndSeedContractTest {

    // --- fan-out ----------------------------------------------------------------------------------------------------------

    private val fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "epoch-1", TopicGrantToken(1L))

    private class Recording(private val name: String, private val log: MutableList<String>) : TopicGrantSink {
        val threads = mutableListOf<Thread>()
        val fences = mutableListOf<TopicSessionFence?>()
        var throwOnAccess: Throwable? = null
        var throwOnRevised: Throwable? = null
        override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
            log += "$name.setAccess:$allowed:${fence?.grant?.value}:$origin"
            threads += Thread.currentThread()
            fences += fence
            throwOnAccess?.let { throw it }
        }
        override fun accessRevised() {
            log += "$name.accessRevised"
            threads += Thread.currentThread()
            throwOnRevised?.let { throw it }
        }
    }

    private fun call(sink: TopicGrantSink, method: String): Throwable? = try {
        if (method == "setAccess") sink.setAccess(true, fence, TopicGrantOrigin.NewContext) else sink.accessRevised()
        null
    } catch (thrown: Throwable) {
        thrown
    }

    @Test fun F01_bothCallsGoFirstThenSecond_withTheSameArguments_onTheCallersThread() {
        val log = mutableListOf<String>()
        val first = Recording("first", log)
        val second = Recording("second", log)
        val sink = FanOutTopicGrantSink(first, second)
        val origin = TopicGrantOrigin.Reapproval(TopicGrantToken(9L))
        sink.setAccess(true, fence, origin)
        sink.accessRevised()
        sink.setAccess(false, null, TopicGrantOrigin.NewContext)
        assertEquals(
            listOf(
                "first.setAccess:true:1:$origin", "second.setAccess:true:1:$origin",
                "first.accessRevised", "second.accessRevised",
                "first.setAccess:false:null:${TopicGrantOrigin.NewContext}", "second.setAccess:false:null:${TopicGrantOrigin.NewContext}"
            ),
            log
        )
        for (recording in listOf(first, second)) {
            assertSame("the very fence object", fence, recording.fences[0])
            assertNull(recording.fences[1])
        }
        val caller = Thread.currentThread()
        assertTrue("on the caller's thread", (first.threads + second.threads).all { it === caller })
    }

    @Test fun F02_aThrowFromFirstPropagatesAndSecondIsNotCalled_cancellationIncluded() {
        for (method in listOf("setAccess", "accessRevised")) for (boom in listOf(IllegalStateException(method), CancellationException(method))) {
            val log = mutableListOf<String>()
            val first = Recording("first", log)
            val second = Recording("second", log)
            if (method == "setAccess") first.throwOnAccess = boom else first.throwOnRevised = boom
            val thrown = call(FanOutTopicGrantSink(first, second), method)
            assertSame("$method ${boom.javaClass.simpleName}: the very throw", boom, thrown)
            assertTrue("$method: second not called", log.none { it.startsWith("second.") })
            assertEquals("$method: first called once", 1, log.count { it.startsWith("first.") })
        }
    }

    @Test fun F03_aThrowFromSecondPropagatesOnceWithoutRetry_cancellationIncluded() {
        for (method in listOf("setAccess", "accessRevised")) for (boom in listOf(IllegalStateException(method), CancellationException(method))) {
            val log = mutableListOf<String>()
            val first = Recording("first", log)
            val second = Recording("second", log)
            if (method == "setAccess") second.throwOnAccess = boom else second.throwOnRevised = boom
            val thrown = call(FanOutTopicGrantSink(first, second), method)
            assertSame("$method ${boom.javaClass.simpleName}: the very throw", boom, thrown)
            assertEquals("$method: first once", 1, log.count { it.startsWith("first.") })
            assertEquals("$method: second once, no retry", 1, log.count { it.startsWith("second.") })
        }
    }

    // --- seed -------------------------------------------------------------------------------------------------------------

    /** Marks the coroutines it runs, so a read can tell whether it is on this dispatcher. */
    private class Io(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val marked: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, Runnable {
            marked.set(true)
            try {
                block.run()
            } finally {
                marked.set(false)
            }
        })
    }

    /** A failure that stack-trace recovery hands back as itself, so its identity can be asserted across a dispatch. */
    private class ReadFailure : IOException("disk"), CopyableThrowable<ReadFailure> {
        override fun createCopy(): ReadFailure? = null
    }

    private class Reads(private val io: Io) {
        val calls = AtomicInteger()
        private val inFlight = AtomicInteger()
        var maxInFlight = 0
        val onIo = mutableListOf<Boolean>()
        /** Each read answers the next of these; a Throwable is thrown, a String returned. */
        val answers = ArrayDeque<Any>()
        /** Runs inside the read, after it counted and before it answers: where a cancellation arrives mid-read. */
        var during: () -> Unit = {}

        fun read(): String {
            calls.incrementAndGet()
            maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
            try {
                onIo += io.marked.get()
                during()
                return when (val next = answers.removeFirstOrNull() ?: "seed-${calls.get()}") {
                    is Throwable -> throw next
                    else -> next as String
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun TestScope.seed(): Triple<InstallSeedSource, Reads, Io> {
        val io = Io(StandardTestDispatcher(testScheduler, name = "io"))
        val reads = Reads(io)
        return Triple(InstallSeedSource(reads::read, io), reads, io)
    }

    @Test fun S01_constructionAndCurrentReadNothing() = runTest {
        val (source, reads, _) = seed()
        repeat(3) { assertNull(source.current) }
        assertEquals(0, reads.calls.get())
    }

    @Test fun S02_getReadsOnceOnIo_publishesOnlyAfterTheRead_andLaterCallsReadNothing() = runTest {
        val (source, reads, _) = seed()
        val duringRead = mutableListOf<String?>()
        reads.during = { duringRead += source.current }
        var value: String? = null
        launch { value = source.get() }
        runCurrent()
        assertEquals("seed-1", value)
        assertEquals(listOf(true), reads.onIo)
        assertEquals("nothing published while the read runs", listOf<String?>(null), duringRead)
        assertEquals("seed-1", source.current)
        assertEquals("seed-1", source.get())
        assertEquals("no second read", 1, reads.calls.get())
    }

    @Test fun S03_concurrentCallsShareOneRead() = runTest {
        val (source, reads, _) = seed()
        var started = 0
        val startedDuringRead = mutableListOf<Int>()
        reads.during = {
            startedDuringRead += started
            // Everything runnable runs now, while the read is on the stack: a second read started here overlaps it.
            testScheduler.runCurrent()
        }
        val values = mutableListOf<String>()
        repeat(3) {
            launch {
                started += 1
                values += source.get()
            }
        }
        assertNull("nothing published before the read ran", source.current)
        runCurrent()
        assertEquals("premise: all three were waiting while the read ran", listOf(3), startedDuringRead)
        assertEquals(listOf("seed-1", "seed-1", "seed-1"), values)
        assertEquals(1, reads.calls.get())
        assertEquals(1, reads.maxInFlight)
    }

    @Test fun S04_aFailedReadReachesOnlyItsCaller_andAWaitingCallTriesAgain() = runTest {
        val (source, reads, _) = seed()
        val failure = ReadFailure()
        reads.answers += failure
        var waiterStarted = false
        val waiterWaitingAtFailure = mutableListOf<Boolean>()
        val currentAtRetry = mutableListOf<String?>()
        reads.during = {
            if (reads.calls.get() == 1) waiterWaitingAtFailure += waiterStarted else currentAtRetry += source.current
        }
        var firstThrown: Throwable? = null
        var second: String? = null
        launch { firstThrown = runCatching { source.get() }.exceptionOrNull() }
        launch {
            waiterStarted = true
            second = source.get()
        }
        runCurrent()
        assertEquals("premise: the second call was waiting when the read failed", listOf(true), waiterWaitingAtFailure)
        assertSame("the read's own failure", failure, firstThrown)
        assertEquals("nothing cached by the failure", listOf<String?>(null), currentAtRetry)
        assertEquals("the waiting call read again", "seed-2", second)
        assertEquals(2, reads.calls.get())
        assertEquals("both reads on io", listOf(true, true), reads.onIo)
        assertEquals("seed-2", source.current)
    }

    @Test fun S04b_aFailedReadLeavesNothing_andAFreshCallReadsAgain() = runTest {
        val (source, reads, _) = seed()
        val failure = ReadFailure()
        reads.answers += failure
        var thrown: Throwable? = null
        launch { thrown = runCatching { source.get() }.exceptionOrNull() }
        runCurrent()
        assertSame(failure, thrown)
        assertNull(source.current)
        assertEquals("seed-2", source.get())
        assertEquals(2, reads.calls.get())
        assertEquals("seed-2", source.current)
    }

    @Test fun S05_aWaitingCallersCancellationDoesNotCancelTheRead() = runTest {
        val (source, reads, _) = seed()
        var reader: String? = null
        var waiterThrown: Throwable? = null
        var waiterStarted = false
        launch { reader = source.get() }
        val waiter = launch {
            waiterStarted = true
            waiterThrown = runCatching { source.get() }.exceptionOrNull()
        }
        // The waiter is suspended on the attempt while the reader's read runs: cancel it there.
        reads.during = {
            assertTrue("premise: the waiter is already waiting", waiterStarted && waiter.isActive)
            waiter.cancel()
        }
        runCurrent()
        assertTrue("the waiter ended cancelled: $waiterThrown", waiterThrown is CancellationException && waiter.isCancelled)
        assertEquals("the reader still published", "seed-1", reader)
        assertEquals(1, reads.calls.get())
        assertEquals("seed-1", source.current)
    }

    @Test fun S06_aReaderCancelledMidReadCachesNothing_andTheNextAttemptFollowsTheReadsEnd() = runTest {
        val (source, reads, _) = seed()
        var readerJob: Job? = null
        var waiterStarted = false
        var waiterWasWaiting = false
        val callsWhileFirstReadOnStack = mutableListOf<Int>()
        // Recorded here and asserted below: a mutant's read can run in a coroutine nobody awaits, where a throw is lost.
        reads.during = {
            if (reads.calls.get() == 1) {
                waiterWasWaiting = waiterStarted
                readerJob?.cancel()
                // The first read is still on the stack: run everything runnable now. A next attempt started here overlaps it.
                testScheduler.runCurrent()
                callsWhileFirstReadOnStack += reads.calls.get()
            }
        }
        var readerThrown: Throwable? = null
        readerJob = launch { readerThrown = runCatching { source.get() }.exceptionOrNull() }
        var waiter: String? = null
        launch {
            waiterStarted = true
            waiter = source.get()
        }
        runCurrent()
        assertTrue("premise: the waiter was waiting", waiterWasWaiting)
        assertEquals("no next attempt while the cancelled reader's read runs", listOf(1), callsWhileFirstReadOnStack)
        assertTrue("the reader ended cancelled: $readerThrown", readerThrown is CancellationException)
        assertEquals("the waiter read again after the first read ended", "seed-2", waiter)
        assertEquals(2, reads.calls.get())
        assertEquals("never two reads at once", 1, reads.maxInFlight)
        assertEquals("seed-2", source.current)
    }

    @Test fun S07_aCancelledCallerOnTheCachedPathIsCancelled_andTheCacheStays() = runTest {
        val (source, reads, _) = seed()
        launch { source.get() }
        runCurrent()
        assertEquals("premise: cached", "seed-1", source.current)
        var thrown: Throwable? = null
        val job = launch {
            coroutineContext[Job]!!.cancel()
            thrown = runCatching { source.get() }.exceptionOrNull()
        }
        runCurrent()
        assertTrue("cancelled: $thrown", thrown is CancellationException && job.isCancelled)
        assertEquals("seed-1", source.current)
        assertEquals(1, reads.calls.get())
    }

    /** S08: the published seed sits in volatile memory, read without the lock. */
    @Test fun S08_thePublishedSeedSitsInVolatileMemory() {
        val strings = InstallSeedSource::class.java.declaredFields.filter { it.type == String::class.java }
        assertTrue("premise: a String field holds the seed", strings.isNotEmpty())
        assertTrue("every String field is volatile: $strings", strings.all { Modifier.isVolatile(it.modifiers) })
    }

    /** S09: a waiting call sees the value published before it resumes, and [InstallSeedSource.current] already shows it. */
    @Test fun S09_currentIsPublishedBeforeAWaitingCallResumes() = runTest {
        val (source, _, _) = seed()
        val seen = mutableListOf<String?>()
        val inner = StandardTestDispatcher(testScheduler)
        val probe = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                seen += source.current
                inner.dispatch(context, block)
            }
        }
        launch { source.get() }
        var waited: String? = null
        launch(probe) { waited = source.get() }
        runCurrent()
        assertEquals("launch, then the lock handed over after publication", listOf(null, "seed-1"), seen)
        assertEquals("seed-1", waited)
    }

    // --- dormancy ---------------------------------------------------------------------------------------------------------

    /**
     * D01: nothing outside the test source sets constructs the fan-out or wires it into DI, and its file names it only in its
     * declaration. Since S4 CUT-CC1-13 the seed has one production binding: the seed module provides it, the free scheduler
     * module hands it to the scheduler through its one reader, and the prefetch start reads it ahead; the old lazy reader is
     * gone.
     */
    @Test fun D01_dormant() {
        val src = File("src")
        val all = src.walkTopDown()
            .onEnter { it == src || it.parentFile != src || it.name !in setOf("test", "androidTest") }
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(src).invariantSeparatorsPath to it.readText() }
        val code = all.mapValues { (_, text) -> text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "") }
        val fanOut = "main/java/com/jay/fxi/data/remote/FanOutTopicGrantSink.kt"
        val seed = "main/java/com/jay/fxi/data/free/InstallSeedSource.kt"
        val module = "main/java/com/jay/fxi/di/FreeSnapshotSchedulerModule.kt"
        assertTrue("premise: the scan sees the files", fanOut in all && seed in all && module in all && all.size > 100)
        assertTrue("premise: the scan reaches the benchmark source set", all.keys.any { it.startsWith("benchmark/") })
        assertTrue("premise: the scan skips the test source sets", all.keys.none { it.startsWith("test/") || it.startsWith("androidTest/") })
        for ((path, name) in listOf(fanOut to "FanOutTopicGrantSink")) {
            assertEquals("only its own file names $name", setOf(path),
                code.filter { (_, text) -> Regex("""\b$name\b""").containsMatchIn(text) }.keys)
            val text = code.getValue(path)
            assertEquals("$path: $name is named only by its declaration", 1, Regex("""\b$name\b""").findAll(text).count())
            assertTrue("$path: declares $name", Regex("""\bclass\s+$name\s*\(""").containsMatchIn(text))
            assertTrue("$path: no DI annotation", !Regex("""@(Inject|AssistedInject|Singleton|Module|Provides|Binds|InstallIn|EntryPoint)\b""").containsMatchIn(text))
        }
        val seedModule = "main/java/com/jay/fxi/di/InstallSeedModule.kt"
        val prefetch = "main/java/com/jay/fxi/data/free/InstallSeedPrefetch.kt"
        // S4 CUT-CC4a: the dormant graph starter reads the seed; nothing in production constructs the starter yet (CC4b).
        val starter = "main/java/com/jay/fxi/data/graph/ProcessGraphStarter.kt"
        assertEquals("the seed is named by its declaration, its module, the prefetch, the scheduler module and the graph starter only",
            setOf(seed, seedModule, prefetch, module, starter),
            code.filter { (_, t) -> Regex("""\bInstallSeedSource\b""").containsMatchIn(t) }.keys)
        assertEquals("CC4a: only its own file names ProcessGraphStarter", setOf(starter),
            code.filter { (_, t) -> Regex("""\bProcessGraphStarter\b""").containsMatchIn(t) }.keys)
        val seedText = code.getValue(seed)
        assertEquals("$seed names its class only in its declaration", 1, Regex("""\bInstallSeedSource\b""").findAll(seedText).count())
        assertFalse("$seed carries no DI annotation", Regex("""@(?:[A-Za-z_][\w.]*\.)?(Inject|AssistedInject|Singleton|Module|Provides|Binds|InstallIn|EntryPoint)\b""").containsMatchIn(seedText))
        assertEquals("one seed provider", 1, Regex("""fun \w+\([^)]*\)\s*:\s*InstallSeedSource\s*=""").findAll(code.getValue(seedModule)).count())
        val scheduler = code.getValue(module)
        assertTrue("the scheduler gets the shared source through its one reader",
            Regex("""installId\s*=\s*freeSchedulerInstallId\(seeds\)""").containsMatchIn(scheduler))
        assertFalse("the old lazy reader is gone", Regex("""\blazy\s*\{|\binstallSeed\(""").containsMatchIn(scheduler))
    }
}
