package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphRecoveryPermit
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
 * Claude-owned S4 RT05 contract (JVM; RT05a r2, RT05b r1): the recovery event adapter `GraphRecoveryEvents` with recording
 * ports - the time path, rows E01, E04, E05, E09, E11 and E12 of the agreed design R4c/S4 rt05_api_agreed.r2 §6, and the
 * resync path, rows F01-F03 and F05-F07 of §7. Rows E02/E13 (the recorder's retention) are in GraphRecorderTest, E03/E06 (the
 * holder) in GraphV2LivePublishTest, and E07/E08/E10, F04 and F06's kept demand (the real coordinator) in
 * GraphRecoveryBudgetContractTest. Oracles: ANDROID_V2_PLAN.md :1328-1330 (the 10 s rollover and 30 s freshness timers
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
 * Rules (rt05_api_agreed.r2 §4-c, §4-d; RT05b):
 *  - A return at least 60 s of wall time after the background began issues FOREGROUND_RETURN after any BOUNDARY_600S and
 *    before the time event, when the cooldown passes and a context is published; a negative difference is short.
 *  - The permit is read once per notice. Only a publication with both Connection fields names a Connection,
 *    (sessionKey, connectionGeneration). The first one seen - also before any foreground state - is the baseline; the same
 *    or an older generation of the same session is nothing; a newer one, or any one of another session, moves the baseline
 *    and, in the foreground past the cooldown, issues a context change and then RECONNECT for the permit's fence and the
 *    Connection's lifetime. A change seen in the background or held back by the cooldown is not replayed.
 *  - FOREGROUND_RETURN and RECONNECT share one 30 s wall-time cooldown that only an issued one of them spends; a negative
 *    difference fails it. BOUNDARY_600S neither spends it nor is held back by it.
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
        val SESSION = Any()
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
        var permit: TopicGraphRecoveryPermit? = null
        var permitReads = 0
        private val log = mutableListOf<String>()
        val triggers = mutableListOf<GraphRecoveryTrigger>()
        val events = GraphRecoveryEvents(
            scope = scope,
            clock = clock,
            context = { contextReads++; source },
            trigger = {
                triggers += it
                log += when (it.kind) {
                    GraphRecoveryTrigger.Kind.BOUNDARY_600S -> "boundary"
                    GraphRecoveryTrigger.Kind.FOREGROUND_RETURN -> "return"
                    GraphRecoveryTrigger.Kind.RECONNECT -> "reconnect"
                } + it.sequence
            },
            retain = { log += "retain" },
            timeEvent = { log += "time" },
            permit = { permitReads++; permit },
            contextChanged = { log += "context" }
        )

        /** Publishes [p] as the session's permit and notifies the producer. */
        fun publish(p: TopicGraphRecoveryPermit?) {
            permit = p
            events.onPermitChanged()
            test.runCurrent()
        }

        /** Publishes a permit naming Connection [generation] of [sessionKey] under [fence]. */
        fun connect(
            generation: Long,
            sessionKey: Any = SESSION,
            fence: TopicSessionFence = FENCE,
            lifetime: TopicUseLifetime = SOURCE.lifetime,
            revision: Long = generation
        ) = publish(TopicGraphRecoveryPermit(sessionKey, revision, fence, 1L, false, generation, lifetime, true))

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
        s.advance(30_000)
        assertEquals("no tick after a first false", none, s.take())
        s.events.onForeground(true); runCurrent()
        assertEquals("a return within the first false's bucket, 30 s away", listOf("retain", "time"), s.take())
        s.advance(30_000)
        assertEquals("the return started the ticks", listOf("time"), s.take())

        val t = Rig(this, NOON - 20.seconds)
        t.events.onForeground(false); runCurrent()
        t.offset = 30.seconds
        t.events.onForeground(true); runCurrent()
        assertEquals("a first false's baseline also finds a change, 30 s away", listOf("retain", "boundary1", "time"), t.take())
    }

    /**
     * E05 (RT05-T02): a return across several boundaries reads the clock once, retains once, issues BOUNDARY_600S once and -
     * 35 minutes away - FOREGROUND_RETURN after it, and emits one time event, then restarts the ticks from that moment; a short
     * return without a change still retains and emits.
     */
    @Test fun E05_aReturnRetainsOnceAndIssuesEachTriggerAtMostOnceBeforeItsTimeEvent() = eventsTest {
        val r = Rig(this, NOON + 5.seconds)
        r.events.onForeground(true); runCurrent()
        r.advance(10_000)
        r.events.onForeground(false); runCurrent()
        r.advance(35 * 60_000L)
        assertEquals("premise: nothing until the return", none, r.take())
        val reads = r.clockReads
        r.events.onForeground(true); runCurrent()
        assertEquals("12:00 to 12:35 in one return (35 minutes away: RT05b's return)", listOf("retain", "boundary1", "return2", "time"),
            r.take())
        assertEquals("one clock read", reads + 1, r.clockReads)
        r.advance(29_999)
        assertEquals("no beat on the old 30 s phase, which would fall at +20 s", none, r.take())
        r.advance(1)
        assertEquals("the ticks run from the return", listOf("time"), r.take())

        r.events.onForeground(false); runCurrent()
        r.advance(50_000)
        r.events.onForeground(true); runCurrent()
        assertEquals("a return within the bucket, 50 s away", listOf("retain", "time"), r.take())
        assertEquals(listOf(1L, 2L), r.triggers.map { it.sequence })
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

        r.connect(1)
        r.advance(9_999)
        r.offset = 30.minutes
        val reads = r.clockReads
        val contexts = r.contextReads
        val permits = r.permitReads
        r.events.close()
        r.events.close()
        r.advance(60_000)
        r.events.onForeground(false)
        r.events.onForeground(true)
        r.connect(2)
        runCurrent()
        assertEquals("nothing after close", none, r.take())
        assertEquals(reads, r.clockReads)
        assertEquals(contexts, r.contextReads)
        assertEquals("no permit read after close", permits, r.permitReads)
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
     * E12: one sequence from 1 goes on across the three kinds, contexts and returns; a boundary or a return carries the context
     * published when it is issued, a reconnect its permit's fence and Connection lifetime.
     */
    @Test fun E12_oneSequenceGoesOnAcrossKindsAndContexts() = eventsTest {
        val r = Rig(this, NOON + 5.minutes)
        r.events.onForeground(true); runCurrent()
        r.connect(1)
        r.offset = 10.minutes
        r.advance(10_000)
        r.source = OTHER
        r.offset = 20.minutes
        r.advance(10_000)
        r.events.onForeground(false); runCurrent()
        r.offset = 40.minutes
        r.events.onForeground(true); runCurrent()
        r.advance(30_000)
        val connection = TopicUseLifetime(TopicGrantToken(8L), 4L)
        r.connect(2, fence = OTHER.fence, lifetime = connection)
        val b = GraphRecoveryTrigger.Kind.BOUNDARY_600S
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), r.triggers.map { it.sequence })
        assertEquals(listOf(b, b, b, GraphRecoveryTrigger.Kind.FOREGROUND_RETURN, GraphRecoveryTrigger.Kind.RECONNECT),
            r.triggers.map { it.kind })
        assertEquals(listOf(SOURCE.fence, OTHER.fence, OTHER.fence, OTHER.fence, OTHER.fence), r.triggers.map { it.fence })
        assertEquals(listOf(SOURCE.lifetime, OTHER.lifetime, OTHER.lifetime, OTHER.lifetime, connection),
            r.triggers.map { it.lifetime })
    }

    // --- RT05b: the resync path (rows F01-F03, F05-F07, rt05_api_agreed.r2 §7) --------------------------------------------

    /**
     * F01 (RT05-T02): a return asks only after at least 60 s of wall time away; a shorter one, or one the wall clock was set back
     * across, still retains and emits. A return with no context asks nothing and spends no cooldown.
     */
    @Test fun F01_aReturnAsksOnlyAfterSixtySecondsAway() = eventsTest {
        val r = Rig(this, NOON + 5.seconds)
        r.events.onForeground(true); runCurrent()
        r.events.onForeground(false); runCurrent()
        r.advance(59_999)
        r.events.onForeground(true); runCurrent()
        assertEquals("59.999 s away", listOf("retain", "time"), r.take())
        r.events.onForeground(false); runCurrent()
        r.advance(60_000)
        r.events.onForeground(true); runCurrent()
        assertEquals("60 s away", listOf("retain", "return1", "time"), r.take())

        val s = Rig(this, NOON + 5.minutes)
        s.events.onForeground(true); runCurrent()
        s.events.onForeground(false); runCurrent()
        s.offset = (-2).minutes
        s.advance(90_000)
        s.events.onForeground(true); runCurrent()
        assertEquals("90 s of dispatcher time, -30 s on the wall", listOf("retain", "time"), s.take())

        val u = Rig(this, NOON + 5.seconds)
        u.events.onForeground(false); runCurrent()
        u.advance(60_000)
        u.events.onForeground(true); runCurrent()
        assertEquals("a first false starts the time away too", listOf("retain", "return1", "time"), u.take())

        val t = Rig(this, NOON + 5.minutes)
        t.events.onForeground(true); runCurrent()
        t.connect(1)
        t.events.onForeground(false); runCurrent()
        t.advance(2 * 60_000L)
        t.source = null
        t.events.onForeground(true); runCurrent()
        assertEquals("no context: no return", listOf("retain", "time"), t.take())
        t.source = SOURCE
        t.advance(10_000)
        t.connect(2)
        assertEquals("it spent no cooldown: a reconnect 10 s later goes", listOf("context", "reconnect1"), t.take())
    }

    /**
     * F02 (RT05-T02, T03): returns and reconnects share one 30 s cooldown that only an issued trigger spends. A boundary neither
     * spends it nor is held back by it, and a wall clock set back fails it.
     */
    @Test fun F02_returnsAndReconnectsShareACooldownThatOnlyAnIssuedTriggerSpends() = eventsTest {
        val r = Rig(this, NOON + 5.seconds)
        r.events.onForeground(true); runCurrent()
        r.connect(1)
        r.events.onForeground(false); runCurrent()
        r.advance(60_000)
        r.events.onForeground(true); runCurrent()
        assertEquals("premise: a return at T", listOf("retain", "return1", "time"), r.take())
        r.advance(20_000)
        r.connect(2)
        assertEquals("T + 20 s", none, r.take())
        r.advance(9_999)
        r.connect(3)
        assertEquals("T + 29.999 s", none, r.take())
        r.advance(1)
        assertEquals("premise: the freshness beat at T + 30 s", listOf("time"), r.take())
        r.connect(4)
        assertEquals("T + 30 s: the held-back ones spent nothing", listOf("context", "reconnect2"), r.take())

        val s = Rig(this, NOON - 5.seconds)
        s.events.onForeground(true); runCurrent()
        s.connect(1)
        s.advance(10_000)
        assertEquals(listOf("retain", "boundary1", "time"), s.take())
        s.connect(2)
        assertEquals("a reconnect right after a boundary", listOf("context", "reconnect2"), s.take())

        val u = Rig(this, NOON + 9.minutes + 45.seconds)
        u.events.onForeground(true); runCurrent()
        u.connect(1)
        u.connect(2)
        assertEquals("premise: a reconnect at 12:09:45", listOf("context", "reconnect1"), u.take())
        u.advance(20_000)
        assertEquals("a boundary 20 s after a reconnect on the wall", listOf("retain", "boundary2", "time"), u.take())

        val t = Rig(this, NOON + 5.minutes)
        t.events.onForeground(true); runCurrent()
        t.connect(1)
        t.connect(2)
        assertEquals("premise: a reconnect at 12:05:00", listOf("context", "reconnect1"), t.take())
        t.offset = (-100).seconds
        t.events.onForeground(false); runCurrent()
        t.offset = (-40).seconds
        t.events.onForeground(true); runCurrent()
        assertEquals("60 s away but 40 s before the reconnect on the wall: held back", listOf("retain", "time"), t.take())
    }

    /**
     * F03 (RT05-T03): the first Connection seen is the baseline - after a failed connect that named none, and when it was
     * already there before the producer's first foreground state. A publication with only one Connection field names none.
     * The permit is read once per notice.
     */
    @Test fun F03_theFirstConnectionSeenIsTheBaseline() = eventsTest {
        val r = Rig(this, NOON + 5.minutes)
        r.events.onForeground(true); runCurrent()
        val reads = r.clockReads
        r.publish(TopicGraphRecoveryPermit(SESSION, 1L, FENCE, 1L, false, null, null, true))
        r.publish(TopicGraphRecoveryPermit(SESSION, 2L, FENCE, 1L, false, 2L, null, true))
        r.publish(TopicGraphRecoveryPermit(SESSION, 3L, FENCE, 1L, false, null, SOURCE.lifetime, true))
        r.publish(null)
        assertEquals("a failed connect, half a Connection and no permit name nothing", none, r.take())
        r.connect(3)
        assertEquals("the first Connection seen is the baseline", none, r.take())
        r.connect(4)
        assertEquals(listOf("context", "reconnect1"), r.take())
        assertEquals("one permit read per notice", 6, r.permitReads)
        assertEquals("the clock is read only to issue", reads + 1, r.clockReads)

        val s = Rig(this, NOON + 5.minutes)
        s.connect(5)
        s.connect(6)
        assertEquals("before any foreground state: the baseline moves and nothing is issued", none, s.take())
        s.events.onForeground(true); runCurrent()
        s.connect(7)
        assertEquals("the Connection seen before the first foreground state is the baseline", listOf("context", "reconnect1"),
            s.take())
    }

    /**
     * F05 (RT05-T03): the same Connection again, a revision alone and an older generation are nothing and keep the baseline. A
     * grant change that ended its Connection and a missing permit keep it too; the new grant's Connection - a generation
     * skipped - is a reconnect for its own fence and lifetime, with no published context needed. Another session's Connection
     * is a reconnect even at a lower generation, and then the new session's own order applies.
     */
    @Test fun F05_onlyANewerConnectionOrAnotherSessionIsAReconnect() = eventsTest {
        val r = Rig(this, NOON + 5.minutes)
        r.events.onForeground(true); runCurrent()
        r.connect(3)
        r.connect(3, revision = 9)
        r.connect(2)
        r.connect(3, revision = 10)
        assertEquals("the same Connection, a revision alone, an older one, and the kept baseline again", none, r.take())
        r.publish(TopicGraphRecoveryPermit(SESSION, 11L, OTHER.fence, 2L, false, null, null, true))
        r.publish(null)
        assertEquals("the grant changed, its Connection ended, then no permit: the baseline stays", none, r.take())
        r.source = null
        r.connect(5, fence = OTHER.fence, lifetime = OTHER.lifetime, revision = 12)
        assertEquals("a reconnect needs no published context, and a skipped generation is new", listOf("context", "reconnect1"),
            r.take())
        assertEquals(OTHER.fence, r.triggers.last().fence)
        assertEquals(OTHER.lifetime, r.triggers.last().lifetime)

        r.advance(30_000)
        r.take()
        val next = Any()
        r.connect(1, sessionKey = next)
        assertEquals("another session's lower generation", listOf("context", "reconnect2"), r.take())
        r.advance(30_000)
        r.take()
        r.connect(1, sessionKey = next)
        r.connect(4, sessionKey = SESSION)
        assertEquals("then that session's order: its same Connection, and the old session's is another session again",
            listOf("context", "reconnect3"), r.take())
    }

    /**
     * F06 (RT05-T02, T03): a Connection seen in the background only moves the baseline (before any foreground state too: F03)
     * and is not replayed on the return; a short return within the bucket asks nothing.
     */
    @Test fun F06_aConnectionSeenInTheBackgroundOnlyMovesTheBaseline() = eventsTest {
        val r = Rig(this, NOON + 5.minutes)
        r.events.onForeground(true); runCurrent()
        r.connect(1)
        r.events.onForeground(false); runCurrent()
        r.connect(2)
        assertEquals("no trigger in the background", none, r.take())
        r.advance(30_000)
        r.events.onForeground(true); runCurrent()
        assertEquals("a short return asks nothing and replays no reconnect", listOf("retain", "time"), r.take())
        r.connect(2)
        assertEquals("the background's Connection is the baseline", none, r.take())
        r.connect(3)
        assertEquals(listOf("context", "reconnect1"), r.take())
    }

    /**
     * F07 (RT05-T03, T04): a reconnect held back by the cooldown is not replayed by the same publication again; the next
     * boundary is the next trigger.
     */
    @Test fun F07_aHeldBackReconnectIsNotReplayed() = eventsTest {
        val r = Rig(this, NOON + 9.minutes)
        r.events.onForeground(true); runCurrent()
        r.connect(1)
        r.connect(2)
        assertEquals(listOf("context", "reconnect1"), r.take())
        r.advance(5_000)
        r.connect(3)
        assertEquals("held back 5 s after the reconnect", none, r.take())
        r.advance(25_000)
        assertEquals("premise: the freshness beat at 30 s", listOf("time"), r.take())
        r.connect(3)
        assertEquals("past the cooldown, the same publication again is nothing", none, r.take())
        r.advance(30_000)
        assertEquals("12:10: the boundary is the next trigger", listOf("retain", "boundary2", "time", "time"),
            r.take().sortedBy { it == "time" })
    }
}
