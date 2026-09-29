package com.jay.fxi.data.remote

import kotlin.time.Duration
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned ②a contract for the bootstrap retry floor (S2/decl_2a_codex.r1.md). The floor is what the server's `Retry-After`
 * asked for, on the session clock's timeline. It only ever moves later, never invents a wait, and never shortens one. The
 * implementation thread reads but does not edit this file.
 */
class TopicBootstrapRetryFloorTest {

    private class Clocks(var now: Long = 1_000L, var wall: Instant = Instant.parse("2026-09-29T00:00:00Z")) {
        val clock = object : TopicCommandClock {
            override fun nowMillis(): Long = now
            override suspend fun sleep(duration: Duration) = error("the floor never waits")
        }
    }

    private fun floor(c: Clocks = Clocks()) = c to TopicBootstrapRetryFloor(c.clock) { c.wall }

    @Test
    fun R01_withNothingRecorded_nothingIsHeldBack() {
        val (c, f) = floor()
        assertTrue(f.notBeforeMillis() <= c.now)
        c.now = 50_000L
        assertTrue(f.notBeforeMillis() <= c.now)
    }

    @Test
    fun R02_delaySeconds_holdBackFromNow() {
        val (_, f) = floor()
        f.record(429, "5")
        assertEquals(6_000L, f.notBeforeMillis())
    }

    @Test
    fun R03_aShorterLaterValue_doesNotShortenTheFloor_aLongerOneExtendsIt() {
        val (c, f) = floor()
        f.record(429, "10")
        c.now = 2_000L
        f.record(429, "1")
        assertEquals("a later, shorter Retry-After shortened the wait", 11_000L, f.notBeforeMillis())
        f.record(503, "20")
        assertEquals(22_000L, f.notBeforeMillis())
    }

    @Test
    fun R04_absentOrUnusableValues_holdNothingBack() {
        val (c, f) = floor()
        listOf(null, "", "   ", "abc", "0", "-3", "1.5").forEach { f.record(429, it) }
        assertTrue(f.notBeforeMillis() <= c.now)
    }

    @Test
    fun R05_aStatusWithoutRetryAfter_inventsNoWait() {
        val (c, f) = floor()
        f.record(429, null)
        f.record(503, null)
        assertTrue(f.notBeforeMillis() <= c.now)
    }

    @Test
    fun R06_aValueTooLargeToRepresent_saturatesInsteadOfWrapping() {
        val (c, f) = floor()
        f.record(429, "99999999999999999999")
        assertEquals(Long.MAX_VALUE, f.notBeforeMillis())
        c.now = 5_000L
        f.record(429, "9223372036854775")
        assertEquals("a later record wrapped a saturated floor", Long.MAX_VALUE, f.notBeforeMillis())
    }

    @Test
    fun R07_anHttpDate_isTranslatedToTheSessionTimeline_andAPastDateHoldsNothingBack() {
        val (c, f) = floor()
        f.record(429, "Tue, 29 Sep 2026 00:00:07 GMT")
        assertEquals(c.now + 7_000L, f.notBeforeMillis())
        val (d, g) = floor()
        g.record(429, "Mon, 28 Sep 2026 23:59:00 GMT")
        assertTrue(g.notBeforeMillis() <= d.now)
    }

    @Test
    fun R08_aRemainderShorterThanAMillisecond_roundsUp_neverShortensTheWait() {
        val (c, f) = floor(Clocks(wall = Instant.parse("2026-09-29T00:00:00.000500Z")))
        f.record(429, "Tue, 29 Sep 2026 00:00:07 GMT")
        assertEquals("6999.5 ms of wait became 6999", c.now + 7_000L, f.notBeforeMillis())
    }
}
