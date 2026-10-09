package com.jay.fxi.data.local

import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 CUT-P3c (`cut_p3c_agreed.r3.md` §4): the graph migration readiness alone - R01, R02, R03 and R05. R04 and D are in
 * `GraphCacheMigrationContractTest`. R06's absence-as-default contracts are pinned where their consumers are:
 * `GraphSeriesSelectionPolicyTest` B2_01, B2_02 and B2_03, `GraphV2ScreenStateHolderTest` H06a, H06c and H07e,
 * `BackupableUserIntentStoreTest` B2_10f and B2_10h, and `GraphV2RequestCoordinatorCacheTest` C05 against C01. R07 is CC5's.
 */
class GraphConsumerReadinessContractTest {

    private val message = "Graph runtime and screen host are not ready"

    /** Starts requireReady() with no dispatcher: its result and thread if it completed before the start returned, else nulls. */
    private fun GraphConsumerReadiness.requireNow(): Pair<Result<Unit>?, Thread?> {
        var outcome: Result<Unit>? = null
        var thread: Thread? = null
        val block: suspend () -> Unit = { requireReady() }
        block.startCoroutine(Continuation(EmptyCoroutineContext) {
            outcome = it
            thread = Thread.currentThread()
        })
        return outcome to thread
    }

    @Test fun R01_notReadyFailsAtOnce_andStaysNotReady() {
        val readiness = GraphConsumerReadiness()
        repeat(3) { attempt ->
            val (outcome, thread) = readiness.requireNow()
            assertNotNull("$attempt: completed before the start returned", outcome)
            assertSame("$attempt: on the caller's thread", Thread.currentThread(), thread)
            val failure = outcome!!.exceptionOrNull()
            assertTrue("$attempt: IllegalStateException, was $failure", failure is IllegalStateException)
            assertEquals(message, failure!!.message)
        }
    }

    @Test fun R02_R05_aMarkedReadinessSucceedsAtOnce_staysReady_andOnlyForItself() {
        val readiness = GraphConsumerReadiness()
        readiness.markReady()
        repeat(3) { attempt ->
            val (outcome, thread) = readiness.requireNow()
            assertEquals("$attempt", Result.success(Unit), outcome)
            assertSame("$attempt: on the caller's thread", Thread.currentThread(), thread)
            readiness.markReady()
        }
        val fresh = GraphConsumerReadiness()
        assertTrue("a new instance is not ready", fresh.requireNow().first?.exceptionOrNull() is IllegalStateException)
        assertEquals("the ready one stays ready", Result.success(Unit), readiness.requireNow().first)
    }

    /** R05: memory only - no constructor input to reach a file, journal or service, and nothing imported to do so. */
    @Test fun R05_itHoldsOnlyMemory() {
        assertEquals(0, GraphConsumerReadiness::class.java.declaredConstructors.single().parameterCount)
        val text = File("src/main/java/com/jay/fxi/data/local/GraphConsumerReadiness.kt").readText()
        assertFalse("imports nothing", Regex("""(?m)^import\s""").containsMatchIn(text))
    }

    private class Payload {
        var value = 0
    }

    /**
     * R03: the ready flag is volatile, and a reader started first sees, once it sees ready, what the marking thread wrote before
     * marking. The reader learns nothing from the writer but the flag; the joins come after the observation.
     */
    @Test fun R03_theFlagIsVolatile_andPublishesWhatWasWrittenBeforeIt() {
        val booleans = GraphConsumerReadiness::class.java.declaredFields.filter { it.type == java.lang.Boolean.TYPE }
        assertEquals("premise: one boolean field holds readiness", 1, booleans.size)
        assertTrue("volatile", Modifier.isVolatile(booleans.single().modifiers))

        val readiness = GraphConsumerReadiness()
        val payload = Payload()
        var readySeen = false
        var valueSeen: Int? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val reader = Thread {
            while (System.nanoTime() < deadline) {
                if (readiness.requireNow().first?.isSuccess == true) {
                    readySeen = true
                    valueSeen = payload.value
                    return@Thread
                }
            }
        }
        val writer = Thread {
            payload.value = 42
            readiness.markReady()
        }
        reader.start()
        writer.start()
        try {
            writer.join(15_000)
            reader.join(15_000)
            assertFalse("the reader ended", reader.isAlive)
            assertTrue("the reader saw ready", readySeen)
            assertEquals("and what was written before it", 42, valueSeen)
        } finally {
            reader.interrupt()
            writer.interrupt()
        }
    }
}
