package com.jay.fxi.data.alerts

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.remote.AuthenticatedEndpoint
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.repository.AlertRateLimitResponseEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 N1 contract (R4c/S7/s7n1 API r1-r3): the endpoint Retry-After floor and the evidence it is fed — API r2 §2
 * (replacing r1 §3) and the floor parts of scope rows N1-I and N1-K. Only 408, 429 and 5xx answers whose header the agreed
 * grammar reads make a floor, whatever 401, 403, 2xx or any other status carries; a non-empty exchange list is the evidence and
 * the fallback status/header pair is then ignored, an empty list hands over the pair; the longest valid entry is applied once
 * as ceil(seconds × 1000) ms, never capped; deadlines merge by maximum on the injected monotonic clock, saturate instead of
 * overflowing (deadline and remaining alike, including a clock near the ends of Long), never leave a negative remaining, are
 * kept per endpoint and take no identity input, so a stale-auth or use-withheld attempt's evidence counts and an account change
 * does not pass under it. The injected clock starts far from the wall clock and from 0, so a floor that read System time or
 * android.os.SystemClock (a throwing stub on the JVM) cannot pass. The implementation thread reads but does not edit this file.
 */
class AlertRateLimitFloorContractTest {

    private class Clock(var now: Long) {
        fun read(): Long = now
    }

    private fun floorOn(clock: Clock) = AlertRateLimitFloor(clock::read)

    private fun ex(status: Int, header: String?, exchange: Int = 1) =
        HttpExchangeEvidence(send = 1, exchange = exchange, statusCode = status, retryAfter = header)

    /** An attempt that observed no exchange list: only the fallback pair is evidence. */
    private fun fallback(status: Int?, header: String?) = AlertRateLimitEvidence.of(emptyList(), status, header)

    /** An attempt whose exchange list is the evidence, with no fallback pair. */
    private fun listed(vararg entries: HttpExchangeEvidence) = AlertRateLimitEvidence.of(entries.toList(), null, null)

    /** A normally returned failure (AuthBoundResult.rateLimitResponseEvidence). */
    private fun response(status: Int, header: String?) =
        AlertRateLimitEvidence.of(AlertRateLimitResponseEvidence(status, header))

    /**
     * The delay [evidence] leaves on a fresh floor whose clock reads [T0], in ms; null when it leaves none. Either way the
     * remaining time must agree with it at the recording instant.
     */
    private fun delayOf(evidence: AlertRateLimitEvidence, row: String): Long? {
        val floor = floorOn(Clock(T0))
        floor.record(E, evidence)
        val notBefore = floor.notBefore(E)
        if (notBefore == Long.MIN_VALUE) {
            assertEquals("$row: no floor leaves nothing to wait", 0L, floor.remainingMillis(E))
            return null
        }
        assertEquals("$row: remaining equals the recorded delay at the recording instant", notBefore - T0, floor.remainingMillis(E))
        return notBefore - T0
    }

    @Test
    fun `S7N1_I01 only 408, 429 and 5xx with a valid header make a floor, from every evidence shape`() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 599)) {
            assertEquals("N1-I $status fallback pair makes a 5 s floor", 5_000L, delayOf(fallback(status, "5"), "N1-I $status fallback"))
            assertEquals("N1-I $status exchange entry makes a 5 s floor", 5_000L, delayOf(listed(ex(status, "5")), "N1-I $status listed"))
            assertEquals("N1-I $status response evidence makes a 5 s floor", 5_000L, delayOf(response(status, "5"), "N1-I $status response"))
        }
    }

    @Test
    fun `S7N1_I02 401, 403, 2xx and every other status make no floor even with a header`() {
        assertEquals("N1-I premise: 429 with the same header makes a 5 s floor", 5_000L, delayOf(fallback(429, "5"), "N1-I premise"))
        for (status in listOf(100, 200, 201, 204, 299, 301, 302, 304, 400, 401, 403, 404, 407, 409, 421, 428, 430, 499, 600)) {
            assertNull("N1-I $status fallback pair makes no floor", delayOf(fallback(status, "5"), "N1-I $status fallback"))
            assertNull("N1-I $status exchange entry makes no floor", delayOf(listed(ex(status, "5")), "N1-I $status listed"))
            assertNull("N1-I $status response evidence makes no floor", delayOf(response(status, "5"), "N1-I $status response"))
        }
    }

    @Test
    fun `S7N1_I03 a retryable status without a header the agreed grammar reads makes no floor`() {
        assertEquals("N1-I premise: a trimmed valid header makes a floor", 5_000L, delayOf(fallback(429, " 5 "), "N1-I premise"))
        val invalid = listOf(
            null, "", "   ", "0", "0.0", "-5", "+5", "abc", "5s", "1e3", "0x10", "inf", "Infinity", "NaN", ".5", "5.", "1 0",
            "５", "Wed, 21 Oct 2015 07:28:00 GMT",
            "0." + "0".repeat(400) + "1", // reads as Double 0: no header (API r2 §3)
            "1" + "0".repeat(400) // reads as Double infinity: no header (API r1 §2 table)
        )
        for (header in invalid) {
            for (status in listOf(429, 503)) {
                assertNull("N1-I $status with header <$header> makes no floor", delayOf(fallback(status, header), "N1-I $status <$header>"))
                assertNull("N1-I $status entry with header <$header> makes no floor", delayOf(listed(ex(status, header)), "N1-I listed <$header>"))
                assertNull("N1-I $status response with header <$header> makes no floor", delayOf(response(status, header), "N1-I response <$header>"))
            }
        }
    }

    @Test
    fun `S7N1_I04 a non-empty exchange list is the evidence and the fallback pair is ignored`() {
        assertEquals("N1-I premise: the fallback pair alone is a 100 s floor", 100_000L, delayOf(fallback(429, "100"), "N1-I premise"))
        assertEquals(
            "N1-I the list's 4 s is used, not the fallback's 100 s",
            4_000L, delayOf(AlertRateLimitEvidence.of(listOf(ex(503, "4")), 429, "100"), "N1-I list over fallback")
        )
        assertNull(
            "N1-I a list holding only a 401 makes no floor; the fallback is not consulted",
            delayOf(AlertRateLimitEvidence.of(listOf(ex(401, "100")), 429, "100"), "N1-I 401 list")
        )
        assertNull(
            "N1-I a list holding only a 200 makes no floor; the fallback is not consulted",
            delayOf(AlertRateLimitEvidence.of(listOf(ex(200, null)), 503, "30"), "N1-I 200 list")
        )
        assertNull(
            "N1-I a list whose 429 has no header makes no floor; the fallback is not consulted",
            delayOf(AlertRateLimitEvidence.of(listOf(ex(429, null)), 429, "30"), "N1-I headerless list")
        )
        assertNull(
            "N1-I a list whose 429 header is unreadable makes no floor; the fallback is not consulted",
            delayOf(AlertRateLimitEvidence.of(listOf(ex(429, "abc")), 429, "30"), "N1-I unreadable list")
        )
    }

    @Test
    fun `S7N1_I05 an empty exchange list hands over the fallback pair`() {
        assertEquals("N1-I empty list, 503 7 s", 7_000L, delayOf(fallback(503, "7"), "N1-I 503"))
        assertEquals("N1-I empty list, 429 0.5 s", 500L, delayOf(fallback(429, "0.5"), "N1-I 429"))
        assertEquals("N1-I empty list, 408 2 s", 2_000L, delayOf(fallback(408, "2"), "N1-I 408"))
        assertNull("N1-I empty list, no status makes no floor", delayOf(fallback(null, "7"), "N1-I no status"))
        assertNull("N1-I empty list, 401 makes no floor", delayOf(fallback(401, "7"), "N1-I 401"))
        assertNull("N1-I empty list, 503 without a header makes no floor", delayOf(fallback(503, null), "N1-I 503 null"))
        assertNull("N1-I empty list and no pair makes no floor", delayOf(listed(), "N1-I nothing"))
    }

    @Test
    fun `S7N1_I06 the longest valid entry is applied once, never summed, whatever the order`() {
        val a = ex(429, "3", 1)
        val b = ex(503, "9", 2)
        val c = ex(408, "5.5", 3)
        for (order in listOf(listOf(a, b, c), listOf(a, c, b), listOf(b, a, c), listOf(b, c, a), listOf(c, a, b), listOf(c, b, a))) {
            assertEquals("N1-I max of 3, 9 and 5.5 s in order $order is 9 s, not a sum", 9_000L, delayOf(listed(*order.toTypedArray()), "N1-I order"))
        }
        assertEquals(
            "N1-I a longer 401 header is no candidate", 4_000L,
            delayOf(listed(ex(401, "100", 1), ex(429, "4", 2)), "N1-I 401 excluded")
        )
        assertEquals(
            "N1-I longer 403 and 200 headers are no candidates", 2_000L,
            delayOf(listed(ex(403, "100", 1), ex(503, "2", 2), ex(200, "50", 3)), "N1-I 403 and 200 excluded")
        )
        assertEquals(
            "N1-I an unreadable larger header is no candidate", 2_000L,
            delayOf(listed(ex(429, "1e9", 1), ex(503, "2", 2)), "N1-I unreadable excluded")
        )
        assertEquals(
            "N1-I OkHttp's 503 + Retry-After 0 repeats are no candidates", 6_000L,
            delayOf(listed(ex(503, "0", 1), ex(503, "0", 2), ex(503, "6", 3)), "N1-I zero repeats")
        )
        assertEquals(
            "N1-I two sub-ms entries round up once to 1 ms, not 2", 1L,
            delayOf(listed(ex(429, "0.0004", 1), ex(503, "0.0004", 2)), "N1-I sub-ms once")
        )
    }

    @Test
    fun `S7N1_I07 a shorter later header never brings the floor forward`() {
        val clock = Clock(1_000)
        val floor = floorOn(clock)
        floor.record(E, fallback(429, "60"))
        assertEquals("N1-I premise: a 60 s floor from t=1000", 61_000L, floor.notBefore(E))

        clock.now = 11_000
        floor.record(E, fallback(429, "5"))
        assertEquals("N1-I a later 5 s header leaves the deadline", 61_000L, floor.notBefore(E))
        assertEquals("N1-I and the wait", 50_000L, floor.remainingMillis(E))

        floor.record(E, fallback(401, "1000"))
        floor.record(E, fallback(null, null))
        floor.record(E, listed())
        floor.record(E, response(200, "1"))
        assertEquals("N1-I evidence that makes no floor leaves the deadline", 61_000L, floor.notBefore(E))

        clock.now = 30_000
        floor.record(E, response(503, "30"))
        assertEquals("N1-I a deadline 1 s earlier leaves the deadline", 61_000L, floor.notBefore(E))
        clock.now = 31_000
        floor.record(E, listed(ex(429, "30")))
        assertEquals("N1-I an equal deadline leaves the deadline", 61_000L, floor.notBefore(E))
        assertEquals("N1-I and the wait", 30_000L, floor.remainingMillis(E))
    }

    @Test
    fun `S7N1_I08 a longer header or a later deadline extends the floor`() {
        val clock = Clock(1_000)
        val floor = floorOn(clock)
        floor.record(E, fallback(429, "60"))
        assertEquals("N1-I premise: a 60 s floor from t=1000", 61_000L, floor.notBefore(E))

        clock.now = 2_000
        floor.record(E, listed(ex(503, "120")))
        assertEquals("N1-I a longer header extends", 122_000L, floor.notBefore(E))

        clock.now = 100_000
        floor.record(E, response(429, "30"))
        assertEquals("N1-I a shorter header whose deadline is later still extends (max of deadlines)", 130_000L, floor.notBefore(E))
        assertEquals("N1-I and the wait follows it", 30_000L, floor.remainingMillis(E))
    }

    @Test
    fun `S7N1_I09 a header of 7200 s and longer is never capped`() {
        val expected = listOf(
            "3600" to 3_600_000L,
            "3601" to 3_601_000L,
            "7200" to 7_200_000L,
            "7200.5" to 7_200_500L,
            "86400" to 86_400_000L,
            "31536000" to 31_536_000_000L,
            "1000000000000" to 1_000_000_000_000_000L
        )
        for ((header, millis) in expected) {
            assertEquals("N1-I $header s is $millis ms, never cut down", millis, delayOf(fallback(429, header), "N1-I $header"))
        }
    }

    @Test
    fun `S7N1_I10 sub-millisecond seconds round up and whole milliseconds stay exact`() {
        val expected = listOf(
            "0.0001" to 1L,
            "0.000000000000000000001" to 1L,
            "0.0011" to 2L,
            "1.0001" to 1_001L,
            "0.25" to 250L,
            "0.5" to 500L,
            "1.5" to 1_500L,
            "120" to 120_000L
        )
        for ((header, millis) in expected) {
            assertEquals("N1-I ceil($header × 1000) is $millis ms", millis, delayOf(response(503, header), "N1-I $header"))
        }
    }

    @Test
    fun `S7N1_I11 a delay beyond Long saturates and the deadline never wraps, even with the clock near Long max`() {
        val early = floorOn(Clock(1_000))
        early.record(E, fallback(429, HUGE))
        assertNotEquals("N1-I premise: a huge header is a floor, not none", Long.MIN_VALUE, early.notBefore(E))
        assertEquals("N1-I a huge header saturates the deadline", Long.MAX_VALUE, early.notBefore(E))
        assertEquals("N1-I and the wait is exact up to the saturated deadline", Long.MAX_VALUE - 1_000, early.remainingMillis(E))
        early.record(E, fallback(429, "5"))
        assertEquals("N1-I a saturated floor is not brought forward", Long.MAX_VALUE, early.notBefore(E))

        val justBeyond = floorOn(Clock(1_000))
        justBeyond.record(E, listed(ex(503, "9223372036854776")))
        assertEquals("N1-I seconds whose ms just pass Long saturate", Long.MAX_VALUE, justBeyond.notBefore(E))

        val nearEnd = Clock(Long.MAX_VALUE - 10)
        val late = floorOn(nearEnd)
        late.record(E, response(429, "60"))
        assertEquals("N1-I now + 60 s past Long max saturates instead of wrapping negative", Long.MAX_VALUE, late.notBefore(E))
        assertEquals("N1-I and the wait is the 10 ms left to the end", 10L, late.remainingMillis(E))
        nearEnd.now = Long.MAX_VALUE
        assertEquals("N1-I at Long max nothing is left, never negative", 0L, late.remainingMillis(E))

        val atEnd = floorOn(Clock(Long.MAX_VALUE))
        atEnd.record(E, fallback(503, HUGE))
        assertEquals("N1-I a huge header at Long max still saturates", Long.MAX_VALUE, atEnd.notBefore(E))
        assertEquals("N1-I and leaves no negative wait", 0L, atEnd.remainingMillis(E))
    }

    @Test
    fun `S7N1_I12 deadline and remaining are exact and overflow-free for a negative clock origin and a clock behind`() {
        val origin = floorOn(Clock(Long.MIN_VALUE))
        origin.record(E, fallback(429, HUGE))
        assertEquals("N1-I Long min + Long max is exactly -1, no saturation needed", -1L, origin.notBefore(E))
        assertEquals("N1-I and the wait is the full saturated delay", Long.MAX_VALUE, origin.remainingMillis(E))

        val negative = floorOn(Clock(-1_000))
        negative.record(E, fallback(429, "5"))
        assertEquals("N1-I a negative origin keeps exact arithmetic", 4_000L, negative.notBefore(E))
        assertEquals("N1-I and its wait", 5_000L, negative.remainingMillis(E))

        val behind = Clock(1_000)
        val exact = floorOn(behind)
        exact.record(E, fallback(429, "5"))
        behind.now = 0
        assertEquals("N1-I a clock read behind the recording gets the exact wait when it fits", 6_000L, exact.remainingMillis(E))

        val wrapping = Clock(0)
        val saturated = floorOn(wrapping)
        saturated.record(E, fallback(429, HUGE))
        assertEquals("N1-I premise: 0 + Long max is the deadline", Long.MAX_VALUE, saturated.notBefore(E))
        wrapping.now = -1
        assertEquals("N1-I a wait past Long range saturates instead of wrapping negative", Long.MAX_VALUE, saturated.remainingMillis(E))
        wrapping.now = Long.MIN_VALUE
        assertEquals("N1-I even from Long min", Long.MAX_VALUE, saturated.remainingMillis(E))
    }

    @Test
    fun `S7N1_I13 remainingMillis is never negative and reaches zero exactly at the deadline`() {
        val clock = Clock(1_000)
        val floor = floorOn(clock)
        assertEquals("N1-I premise: no floor yet", Long.MIN_VALUE, floor.notBefore(E))
        assertEquals("N1-I no floor, nothing to wait", 0L, floor.remainingMillis(E))

        floor.record(E, fallback(429, "5"))
        assertEquals("N1-I premise: deadline 6000", 6_000L, floor.notBefore(E))
        val expected = listOf(1_000L to 5_000L, 5_999L to 1L, 6_000L to 0L, 6_001L to 0L, 1_000_000_000L to 0L, Long.MAX_VALUE to 0L)
        for ((now, remaining) in expected) {
            clock.now = now
            assertEquals("N1-I remaining at t=$now", remaining, floor.remainingMillis(E))
        }
    }

    @Test
    fun `S7N1_I14 the floor runs on the injected monotonic clock read at recording, never the wall clock`() {
        val clock = Clock(T0)
        val floor = floorOn(clock)
        floor.record(E, fallback(429, "5"))
        assertEquals("N1-I the deadline is in the injected clock's domain", T0 + 5_000, floor.notBefore(E))
        assertEquals("N1-I premise: 5 s to wait", 5_000L, floor.remainingMillis(E))
        Thread.sleep(25)
        assertEquals("N1-I the wall clock moving does not move the wait", 5_000L, floor.remainingMillis(E))
        clock.now = T0 + 1_234
        assertEquals("N1-I only the injected clock moves it", 3_766L, floor.remainingMillis(E))

        val elsewhere = Clock(9_000_000_000_000L)
        val other = floorOn(elsewhere)
        other.record(E, fallback(429, "5"))
        assertEquals("N1-I another origin gives the same wait", 5_000L, other.remainingMillis(E))

        val laterRecord = Clock(0)
        val built = floorOn(laterRecord)
        laterRecord.now = 50_000
        built.record(E, fallback(429, "5"))
        assertEquals("N1-I the clock is read when the evidence arrives, not when the floor was built", 55_000L, built.notBefore(E))
    }

    @Test
    fun `S7N1_I15 each endpoint keeps its own floor`() {
        assertTrue("N1-I premise: more than one endpoint", AuthenticatedEndpoint.entries.size > 1)
        val clock = Clock(1_000)
        val floor = floorOn(clock)
        floor.record(E, fallback(429, "60"))
        assertEquals("N1-I premise: the settings endpoint has a floor", 61_000L, floor.notBefore(E))
        for (endpoint in AuthenticatedEndpoint.entries - E) {
            assertEquals("N1-I $endpoint has no floor of its own", Long.MIN_VALUE, floor.notBefore(endpoint))
            assertEquals("N1-I $endpoint has nothing to wait", 0L, floor.remainingMillis(endpoint))
        }

        val source = AuthenticatedEndpoint.SOURCE_NOTIFICATION_SETTINGS
        floor.record(source, fallback(503, "600"))
        assertEquals("N1-I the other endpoint's longer floor is its own", 601_000L, floor.notBefore(source))
        assertEquals("N1-I and does not extend the settings floor", 61_000L, floor.notBefore(E))
        floor.record(source, fallback(503, "1"))
        floor.record(E, fallback(503, "1"))
        assertEquals("N1-I shorter headers move neither", 601_000L, floor.notBefore(source))
        assertEquals("N1-I shorter headers move neither", 61_000L, floor.notBefore(E))
        val update = AuthenticatedEndpoint.UPDATE_NOTIFICATION_SETTING
        assertEquals("N1-I a third endpoint stays without a floor", Long.MIN_VALUE, floor.notBefore(update))
    }

    @Test
    fun `S7N1_I16 a normally returned failure's response evidence makes the same floor and merges with the rest`() {
        assertEquals("N1-I/N1-K 429 12 s", 12_000L, delayOf(response(429, "12"), "N1-I response 429"))
        assertEquals("N1-I/N1-K 503 trimmed 3 s", 3_000L, delayOf(response(503, " 3 "), "N1-I response 503"))
        assertEquals("N1-I/N1-K 408 sub-ms", 1L, delayOf(response(408, "0.0001"), "N1-I response 408"))
        for ((status, header) in listOf(401 to "12", 403 to "12", 200 to "12", 404 to "12", 503 to null, 503 to "abc")) {
            assertNull("N1-I/N1-K response $status <$header> makes no floor", delayOf(response(status, header), "N1-I response $status"))
        }

        val clock = Clock(1_000)
        val floor = floorOn(clock)
        floor.record(E, listed(ex(503, "30")))
        clock.now = 2_000
        floor.record(E, response(429, "5"))
        assertEquals("N1-I/N1-K a shorter response header does not shorten an exception's floor", 31_000L, floor.notBefore(E))
        floor.record(E, response(429, "60"))
        assertEquals("N1-I/N1-K a longer response header extends it", 62_000L, floor.notBefore(E))
    }

    @Test
    fun `S7N1_K01 a stale-auth exception's exchanges are the evidence and its repeated last pair is not counted again`() {
        val e = AuthIdentityChangedException(statusCode = 429, retryAfter = "10", exchanges = listOf(ex(503, "4", 1), ex(429, "10", 2)))
        val evidence = AlertRateLimitEvidence.of(e.exchanges, e.statusCode, e.retryAfter)
        assertEquals("N1-K the longest exchange, 10 s, not 10 + 10 or 4 + 10", 10_000L, delayOf(evidence, "N1-K repeat"))

        val clock = Clock(T0)
        val floor = floorOn(clock)
        floor.record(E, evidence)
        floor.record(E, evidence)
        assertEquals("N1-K the same evidence twice at one instant does not accumulate", T0 + 10_000, floor.notBefore(E))

        assertEquals("N1-K premise: that fallback pair alone is a 999 s floor", 999_000L, delayOf(fallback(503, "999"), "N1-K premise"))
        val disagreeing = AuthIdentityChangedException(statusCode = 503, retryAfter = "999", exchanges = listOf(ex(429, "10")))
        assertEquals(
            "N1-K a non-empty list is never joined by the separate fields",
            10_000L, delayOf(AlertRateLimitEvidence.of(disagreeing.exchanges, disagreeing.statusCode, disagreeing.retryAfter), "N1-K list")
        )
    }

    @Test
    fun `S7N1_K02 a stale-auth exception without exchanges hands over its status and header`() {
        val withPair = AuthIdentityChangedException(statusCode = 503, retryAfter = "9")
        assertEquals(
            "N1-K the pair of a response that arrived as the session moved is a 9 s floor",
            9_000L, delayOf(AlertRateLimitEvidence.of(withPair.exchanges, withPair.statusCode, withPair.retryAfter), "N1-K pair")
        )
        val bare = AuthIdentityChangedException()
        assertNull(
            "N1-K a move with no response makes no floor",
            delayOf(AlertRateLimitEvidence.of(bare.exchanges, bare.statusCode, bare.retryAfter), "N1-K bare")
        )
        val unauthorized = AuthIdentityChangedException(statusCode = 401, retryAfter = "9")
        assertNull(
            "N1-K a 401 pair makes no floor",
            delayOf(AlertRateLimitEvidence.of(unauthorized.exchanges, unauthorized.statusCode, unauthorized.retryAfter), "N1-K 401")
        )
        val refusedSend = AuthIdentityChangedException(exchanges = listOf(ex(429, "6")))
        assertEquals(
            "N1-K a send refused before its response still hands over the list",
            6_000L, delayOf(AlertRateLimitEvidence.of(refusedSend.exchanges, refusedSend.statusCode, refusedSend.retryAfter), "N1-K refused")
        )
    }

    @Test
    fun `S7N1_K03 a use-withheld exception's exchanges are the evidence`() {
        val withheld = TopicUseWithheldException(listOf(ex(503, "0", 1), ex(503, "12", 2), ex(401, "99", 3)))
        assertEquals(
            "N1-K the longest retryable exchange of a withheld use, 12 s",
            12_000L, delayOf(AlertRateLimitEvidence.of(withheld.exchanges, null, null), "N1-K withheld")
        )
        val empty = TopicUseWithheldException(emptyList())
        assertNull("N1-K a withheld use with no exchange makes no floor", delayOf(AlertRateLimitEvidence.of(empty.exchanges, null, null), "N1-K empty"))
    }

    @Test
    fun `S7N1_K04 the floor takes no identity and nothing but the process ends it, so an account change does not pass under it`() {
        val methods = AlertRateLimitFloor::class.java.declaredMethods.filterNot { it.isSynthetic }
        val names = methods.map { it.name.substringBefore('$') }.toSet()
        assertTrue("N1-K premise: the floor's members are visible by name, got $names", names.containsAll(setOf("record", "notBefore")))
        val ending = Regex("clear|reset|remove|forget|invalidate|evict|drop|purge|expire", RegexOption.IGNORE_CASE)
        assertEquals("N1-K no member ends a floor before the process does", emptyList<String>(), names.filter { ending.containsMatchIn(it) })

        val parameterTypes = methods.flatMap { it.parameterTypes.toList() } +
            AlertRateLimitFloor::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }
        assertTrue(
            "N1-K premise: record's parameter types are visible",
            parameterTypes.containsAll(listOf(AuthenticatedEndpoint::class.java, AlertRateLimitEvidence::class.java))
        )
        val identity = Regex("Identity|Fence|Owner|AuthSnapshot|Uid|Account|Session", RegexOption.IGNORE_CASE)
        assertEquals(
            "N1-K the floor has no identity input",
            emptyList<String>(), parameterTypes.map { it.name }.filter { identity.containsMatchIn(it) }
        )

        val clock = Clock(1_000)
        val floor = floorOn(clock)
        val old = AuthIdentityChangedException(statusCode = 429, retryAfter = "30")
        floor.record(E, AlertRateLimitEvidence.of(old.exchanges, old.statusCode, old.retryAfter))
        assertEquals("N1-K premise: the old account's answer left a 30 s floor", 31_000L, floor.notBefore(E))
        clock.now = 2_000
        floor.record(E, response(429, "1"))
        assertEquals("N1-K the next account's shorter answer does not pass under it", 31_000L, floor.notBefore(E))
        assertEquals("N1-K and the wait stands", 29_000L, floor.remainingMillis(E))
    }

    private companion object {
        /** Far from the wall clock and from 0 (what an unmocked android stub would hand back if it did not throw). */
        const val T0 = 7_777L
        const val HUGE = "99999999999999999999"
        val E = AuthenticatedEndpoint.NOTIFICATION_SETTINGS
    }
}
