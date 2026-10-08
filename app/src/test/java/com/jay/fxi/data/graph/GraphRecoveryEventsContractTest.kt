package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.time.AppClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 RT05a contract r1 (JVM): the time path of the recovery event adapter `GraphRecoveryEvents`, rows E01, E04,
 * E05, E09, E11 and E12 of the agreed design R4c/S4 rt05_api_agreed.r2 §6, with recording ports. Rows E02/E13 (the recorder's
 * retention) are in GraphRecorderTest, E03/E06 (the holder) in GraphV2LivePublishTest, and E07/E08/E10 (the real coordinator)
 * in GraphRecoveryBudgetContractTest. Oracles: ANDROID_V2_PLAN.md :1328-1330 (the 10 s rollover and 30 s freshness timers
 * only move the time axis, detect a bucket boundary and judge line use; they add no observation), :1355 (a return
 * reconstructs before the first publication), :1358 (the 10-minute boundary refetch is for an active 1d only, through the
 * coordinator) and RT01 design r1 RT05-T01, T02, T04.
 *
 * Rules (rt05_api_agreed.r2 §4-a, §4-b):
 *  - Only a change of the foreground state acts; a repeated value reads nothing. The first true sets the bucket baseline and
 *    starts both ticks and nothing else; a first false sets the baseline and starts nothing, and the true after it is a return.
 *  - A rollover tick every 10 s (dispatcher time) reads the clock once; when the 600 s bucket changed - forward or back,
 *    however far - it retains, issues BOUNDARY_600S for the published context and emits a time event, in that order. A
 *    freshness tick every 30 s emits a time event only. The two are independent: at a common instant both may emit.
 *  - A return reads the clock once, retains once, issues BOUNDARY_600S at most once if the bucket changed, emits one time
 *    event and starts the ticks again from that moment. The background has no tick.
 *  - The context is read only to issue a trigger. With no published context there is no trigger and no sequence is spent;
 *    the baseline still moves and retention and the time event still happen. A return takes the same boundary rules. One
 *    strictly increasing sequence from 1 goes on across contexts. The background and close cancel both ticks; after close
 *    nothing reads a supplier or the clock or calls a port, also for a tick that was due next or already due.
 *
 * Rig: 2026-10-05 KST; the clock is the dispatcher's virtual time from the row's start plus a wall offset the row sets.
 * Steps are runCurrent and advanceTimeBy, never advanceUntilIdle. The implementation thread reads but does not edit this
 * file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphRecoveryEventsContractTest {

    private companion object {
        /** 2026-10-05 12:00 KST, a bucket start. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val FENCE = TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", TopicGrantToken(7L))
        val SOURCE = GraphRequestSource(FENCE, TopicUseLifetime(TopicGrantToken(7L), 3L))
        val OTHER = GraphRequestSource(
            TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", TopicGrantToken(8L)), TopicUseLifetime(TopicGrantToken(8L), 3L)
        )
    }

    private val opened = mutableListOf<Rig>()

    private fun eventsTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.scope.cancel() }
            opened.clear()
        }
    }

    private inner class Rig(val test: TestScope, start: Instant) {
        init { opened += this }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val base = test.testScheduler.currentTime
        /** Wall time moved apart from the dispatcher's virtual time. */
        var offset: Duration = Duration.ZERO
        var clockReads = 0
        val clock = AppClock {
            clockReads++
            start + (test.testScheduler.currentTime - base).milliseconds + offset
        }
        var source: GraphRequestSource? = SOURCE
        var contextReads = 0
        private val log = mutableListOf<String>()
        val triggers = mutableListOf<GraphRecoveryTrigger>()
        val events = GraphRecoveryEvents(
            scope = scope,
            clock = clock,
            context = { contextReads++; source },
            trigger = { triggers += it; log += "boundary${it.sequence}" },
            retain = { log += "retain" },
            timeEvent = { log += "time" }
        )

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        /** The port calls since the last take, in order. */
        fun take(): List<String> = log.toList().also { log.clear() }
    }

    private val none = emptyList<String>()

    /**
     * E01 (RT05-T01): a rollover in the same bucket emits nothing but reads the clock; the freshness beat emits a time event
     * alone. Wall time moving alone acts only at the next rollover; dispatcher time alone within a bucket acts on nothing.
     * When a boundary rollover and the freshness beat fall due together, both emit.
     */
    @Test fun E01_ticksActOnlyOnABucketChangeOrTheFreshnessBeat() = eventsTest {
        val r = Rig(this, NOON + 5.seconds)
        r.events.onForeground(true); runCurrent()
        assertEquals("the start emits nothing", none, r.take())
        r.advance(9_999)
        assertEquals("9.999 s", none, r.take())
        val reads = r.clockReads
        r.advance(1)
        assertEquals("a rollover in the same bucket emits nothing", none, r.take())
        assertEquals("but reads the clock once", reads + 1, r.clockReads)
        assertEquals("no boundary, no context read", 0, r.contextReads)
        r.advance(19_999)
        assertEquals("29.999 s", none, r.take())
        r.advance(1)
        assertEquals("30 s: the freshness beat alone", listOf("time"), r.take())

        r.offset = 10.minutes
        runCurrent()
        r.advance(9_999)
        assertEquals("wall time alone acts only at a rollover", none, r.take())
        r.advance(1)
        assertEquals("40 s: the rollover finds 12:10", listOf("retain", "boundary1", "time"), r.take())
        assertEquals("one context read for the boundary", 1, r.contextReads)
        r.advance(10_000)
        assertEquals("dispatcher time alone within 12:10", none, r.take())

        val s = Rig(this, NOON - 30.seconds)
        s.events.onForeground(true); runCurrent()
        s.advance(29_999)
        assertEquals("premise: no boundary before 12:00", none, s.take())
        s.advance(1)
        val both = s.take()
        assertEquals("two time events at 12:00", 2, both.count { it == "time" })
        assertEquals(listOf("retain", "boundary1"), both.filter { it != "time" })
        assertTrue("the rollover's own order", both.indexOf("boundary1") < both.lastIndexOf("time"))
    }

    /**
     * E04 (RT05-T02): only a change acts. A repeated true neither restarts nor duplicates the ticks and a repeated false reads
     * nothing; the background cancels both ticks and reads no clock. A first false sets the baseline - a return within its
     * bucket finds no boundary, one past it finds one - and starts no tick until that return.
     */
    @Test fun E04_onlyAChangeActsAndTheBackgroundHasNoTicks() = eventsTest {
        val r = Rig(this, NOON + 5.seconds)
        r.events.onForeground(true); runCurrent()
        val reads = r.clockReads
        r.advance(5_000)
        r.events.onForeground(true); runCurrent()
        assertEquals("a repeated true reads nothing", reads, r.clockReads)
        r.advance(25_000)
        assertEquals("30 s: the first true's beat", listOf("time"), r.take())
        r.advance(5_000)
        assertEquals("35 s: no restarted or duplicated beat", none, r.take())
        r.events.onForeground(false); runCurrent()
        assertEquals("the background cancelled both ticks", 0, r.scope.coroutineContext[Job]!!.children.count())
        val background = r.clockReads
        r.events.onForeground(false); runCurrent()
        r.advance(30 * 60_000L)
        assertEquals("no tick in the background", none, r.take())
        assertEquals("a repeated false and the background read nothing", background, r.clockReads)
        assertTrue(r.triggers.isEmpty())

        val s = Rig(this, NOON + 5.seconds)
        s.events.onForeground(false); runCurrent()
        s.advance(5 * 60_000L)
        assertEquals("no tick after a first false", none, s.take())
        s.events.onForeground(true); runCurrent()
        assertEquals("a return within the first false's bucket", listOf("retain", "time"), s.take())
        s.advance(30_000)
        assertEquals("the return started the ticks", listOf("time"), s.take())

        val t = Rig(this, NOON + 5.seconds)
        t.events.onForeground(false); runCurrent()
        t.offset = 10.minutes
        t.events.onForeground(true); runCurrent()
        assertEquals("a first false's baseline also finds a change", listOf("retain", "boundary1", "time"), t.take())
    }

    /**
     * E05 (RT05-T02): a return across several boundaries reads the clock once, retains once, asks once and emits one time
     * event, then restarts the ticks from that moment; a return without a change still retains and emits.
     */
    @Test fun E05_aReturnRetainsOnceAndAsksAtMostOnceBeforeItsTimeEvent() = eventsTest {
        val r = Rig(this, NOON + 5.seconds)
        r.events.onForeground(true); runCurrent()
        r.advance(10_000)
        r.events.onForeground(false); runCurrent()
        r.advance(35 * 60_000L)
        assertEquals("premise: nothing until the return", none, r.take())
        val reads = r.clockReads
        r.events.onForeground(true); runCurrent()
        assertEquals("12:00 to 12:35 in one return", listOf("retain", "boundary1", "time"), r.take())
        assertEquals("one clock read", reads + 1, r.clockReads)
        r.advance(29_999)
        assertEquals("no beat on the old 30 s phase, which would fall at +20 s", none, r.take())
        r.advance(1)
        assertEquals("the ticks run from the return", listOf("time"), r.take())

        r.events.onForeground(false); runCurrent()
        r.advance(60_000)
        r.events.onForeground(true); runCurrent()
        assertEquals("a return within the bucket", listOf("retain", "time"), r.take())
        assertEquals(listOf(1L), r.triggers.map { it.sequence })
    }

    /**
     * E09 (RT05-T04): a clock jump forward by hours is one boundary, a jump back across buckets is one boundary, and a step back
     * within the bucket is none.
     */
    @Test fun E09_aClockJumpIsOneBoundaryAndAReversalWithinTheBucketIsNone() = eventsTest {
        val r = Rig(this, NOON + 5.minutes)
        r.events.onForeground(true); runCurrent()
        r.offset = 3.hours
        r.advance(10_000)
        assertEquals("15:05: one boundary", listOf("retain", "boundary1", "time"), r.take())
        r.offset = (-2).hours
        r.advance(10_000)
        assertEquals("back to 10:05: one boundary", listOf("retain", "boundary2", "time"), r.take())
        r.offset = (-2).hours - 3.minutes
        r.advance(20_000)
        assertEquals("10:02 within 10:00: only the freshness beat at 30 s", listOf("time"), r.take())
    }

    /**
     * E11: with no published context a boundary moves the baseline, retains and emits but issues nothing and spends no sequence,
     * and a later context gets no deferred boundary; a return takes the same rules, also backwards. A close - twice, just before
     * a due tick or with one already due - cancels the ticks and ends everything: later ticks and calls read no supplier and no
     * clock and call no port.
     */
    @Test fun E11_noContextSpendsNoSequenceAndCloseEndsEverything() = eventsTest {
        val r = Rig(this, NOON + 5.minutes)
        r.events.onForeground(true); runCurrent()
        r.source = null
        r.offset = 10.minutes
        r.advance(10_000)
        assertEquals("no context", listOf("retain", "time"), r.take())
        r.advance(10_000)
        assertEquals("the baseline moved without a context: nothing again at 20 s", none, r.take())
        r.source = SOURCE
        r.advance(10_000)
        assertEquals("30 s: only the freshness beat, no deferred boundary", listOf("time"), r.take())
        r.offset = 20.minutes
        r.advance(10_000)
        assertEquals("the sequence was not spent", listOf("retain", "boundary1", "time"), r.take())

        r.advance(9_999)
        r.offset = 30.minutes
        val reads = r.clockReads
        val contexts = r.contextReads
        r.events.close()
        r.events.close()
        r.advance(60_000)
        r.events.onForeground(false)
        r.events.onForeground(true)
        runCurrent()
        assertEquals("nothing after close", none, r.take())
        assertEquals(reads, r.clockReads)
        assertEquals(contexts, r.contextReads)
        assertEquals("close cancelled both ticks", 0, r.scope.coroutineContext[Job]!!.children.count())

        // A return takes the same boundary rules: no context spends nothing, a step back across buckets is one boundary.
        val s = Rig(this, NOON + 5.minutes)
        s.events.onForeground(true); runCurrent()
        s.events.onForeground(false); runCurrent()
        s.source = null
        s.offset = 10.minutes
        s.events.onForeground(true); runCurrent()
        assertEquals("a return without a context", listOf("retain", "time"), s.take())
        s.events.onForeground(false); runCurrent()
        s.source = SOURCE
        s.offset = (-2).hours
        s.events.onForeground(true); runCurrent()
        assertEquals("a backward return: one boundary, the sequence unspent", listOf("retain", "boundary1", "time"), s.take())

        val d = Rig(this, NOON + 5.minutes)
        d.events.onForeground(true); runCurrent()
        d.offset = 10.minutes
        // The 10 s tick is due at the current time and has not run.
        advanceTimeBy(10_000)
        val due = d.clockReads
        d.events.close()
        runCurrent()
        assertEquals("a tick already due does not run after close", none, d.take())
        assertEquals(due, d.clockReads)
    }

    /**
     * E12: one sequence from 1 goes on across contexts and returns; each trigger carries the context published when it is
     * issued.
     */
    @Test fun E12_oneSequenceGoesOnAcrossContexts() = eventsTest {
        val r = Rig(this, NOON + 5.minutes)
        r.events.onForeground(true); runCurrent()
        r.offset = 10.minutes
        r.advance(10_000)
        r.source = OTHER
        r.offset = 20.minutes
        r.advance(10_000)
        r.events.onForeground(false); runCurrent()
        r.offset = 40.minutes
        r.events.onForeground(true); runCurrent()
        assertEquals(listOf(1L, 2L, 3L), r.triggers.map { it.sequence })
        assertEquals(List(3) { GraphRecoveryTrigger.Kind.BOUNDARY_600S }, r.triggers.map { it.kind })
        assertEquals(listOf(SOURCE.fence, OTHER.fence, OTHER.fence), r.triggers.map { it.fence })
        assertEquals(listOf(SOURCE.lifetime, OTHER.lifetime, OTHER.lifetime), r.triggers.map { it.lifetime })
    }
}
