package com.jay.fxi.data.alerts

import com.jay.fxi.data.free.FreeSnapshotSchedulePolicy
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import java.lang.reflect.Modifier
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S7 N1 contract (R4c/S7/s7n1 API r1-r3): the alert families' retry policy and Retry-After grammar. It locks the
 * decision table of API r1 §2 (Withdrawn silent; 401 and 403 terminal before any Retry-After and before exhaustion; 408, 429
 * and 5xx retried on the Retry-After with no down-cap, or on backoff without one; five retries for those and for NETWORK, two
 * for an unavailable credential, which then ends AuthRequired; DECODE, UNEXPECTED and every other HTTP answer terminal), the
 * backoff bounds (2/4/8/16/32 s, 60 s cap, ±20 % jitter clamped to 1–60 s, no overflow at any attempt), the seconds to
 * milliseconds conversion (ceil, so a sub-millisecond floor is 1 ms; saturation at Long.MAX_VALUE), and every row of the parser
 * grammar table of API r1 §2 with the r2 §3 additions, plus the free snapshot parser left as it was (N1-J). The owner's own
 * attempt bookkeeping and the transport's separate 401 replay belong to the owner and wire contracts. The implementation thread
 * reads but does not edit this file.
 */
class AlertRetryPolicyContractTest {

    private val policy = AlertRetryPolicy()
    private val noJitter: () -> Double = { 0.0 }
    private val lowJitter: () -> Double = { -0.2 }
    private val highJitter: () -> Double = { 0.2 }

    /** API r1 §2 and r2 §2: the retryable answers are exactly 408, 429 and 500..599. Both ends of the 5xx range are here. */
    private val retryableStatuses = listOf(408, 429, 500, 502, 503, 504, 599)

    /** Attempt numbers at which five retries are already spent (the first failure is attempt 0). */
    private val exhaustedAttempts = listOf(5, 6, 7, 100, Int.MAX_VALUE)

    /** The backoff ladder of API r1 §2 with no jitter: 2 s doubling, the attempt-th retry after the attempt-th failure. */
    private val ladderMillis = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L)

    // region helpers

    private fun http(status: Int, retryAfterSeconds: Double? = null) = AlertFetchFailure.Http(status, retryAfterSeconds)

    private fun repo(kind: AlertRepositoryFailureKind) = AlertFetchFailure.Repository(kind)

    private fun failed(kind: AlertRepositoryFailureKind, status: Int?) =
        AlertRetryDecision.Terminal(AlertLoadFailure.Failed(kind, status))

    private fun retry(delayMillis: Long, byRetryAfter: Boolean) = AlertRetryDecision.Retry(delayMillis, byRetryAfter)

    private val authRequired = AlertRetryDecision.Terminal(AlertLoadFailure.AuthRequired)
    private val forbidden = AlertRetryDecision.Terminal(AlertLoadFailure.Forbidden)

    private fun assertDecision(
        row: String,
        expected: AlertRetryDecision,
        failure: AlertFetchFailure,
        attempt: Int,
        jitter: () -> Double = noJitter
    ) {
        assertEquals(
            "$row: ${label(failure)} at attempt $attempt",
            expected,
            policy.decide(failure, attempt, jitter)
        )
    }

    /** Exhaustive over the sealed types: a new case fails to compile here before it can slip past the table. */
    private fun label(failure: AlertFetchFailure): String = when (failure) {
        is AlertFetchFailure.Http -> "Http(${failure.statusCode}, retryAfter=${failure.retryAfterSeconds})"
        is AlertFetchFailure.Repository -> "Repository(${failure.kind})"
        AlertFetchFailure.Withdrawn -> "Withdrawn"
    }

    private fun label(failure: AlertLoadFailure): String = when (failure) {
        AlertLoadFailure.AuthRequired -> "AuthRequired"
        AlertLoadFailure.Forbidden -> "Forbidden"
        is AlertLoadFailure.Failed -> "Failed(${failure.kind}, ${failure.statusCode})"
    }

    private fun label(decision: AlertRetryDecision): String = when (decision) {
        AlertRetryDecision.Silent -> "Silent"
        is AlertRetryDecision.Terminal -> "Terminal(${label(decision.failure)})"
        is AlertRetryDecision.Retry -> "Retry(${decision.delayMillis} ms, byRetryAfter=${decision.byRetryAfter})"
    }

    private class Chain(val attempts: Int, val delays: List<Long>, val end: AlertRetryDecision)

    /** Drives one failure the way an owner would: attempt 0 first, one more after each Retry, until something else ends it. */
    private fun chain(failure: AlertFetchFailure, jitter: () -> Double = noJitter): Chain {
        val delays = mutableListOf<Long>()
        for (attempt in 0..MAX_CHAIN) {
            val decision = policy.decide(failure, attempt, jitter)
            if (decision is AlertRetryDecision.Retry) delays.add(decision.delayMillis)
            else return Chain(attempt + 1, delays, decision)
        }
        throw AssertionError("N1-L: ${label(failure)} still retried after ${MAX_CHAIN + 1} logical attempts")
    }

    private fun visible(header: String?): String = when {
        header == null -> "null"
        header.length > 24 -> "${header.take(12)}…(${header.length} chars)"
        else -> "'" + header.replace("\t", "\\t") + "'"
    }

    private fun read(row: String, header: String): Double {
        val seconds = AlertRetryAfter.parseSeconds(header)
        assertNotNull("$row: ${visible(header)} must be read", seconds)
        return seconds!!
    }

    private fun assertReads(row: String, header: String, expectedSeconds: Double) {
        assertEquals("$row: ${visible(header)} reads as $expectedSeconds s", expectedSeconds, read(row, header), 0.0)
    }

    /** A rejected header next to its nearest accepted twin, so no rejection passes because the parser reads nothing at all. */
    private fun assertNoHeader(row: String, header: String?, twin: String, twinSeconds: Double) {
        assertReads("$row premise", twin, twinSeconds)
        assertNull("$row: ${visible(header)} is no header", AlertRetryAfter.parseSeconds(header))
    }

    private fun assertMillisNear(message: String, expected: Long, actual: Long) {
        // The contract fixes the formula, not the rounding of b + b·j; one millisecond either way is the same backoff.
        assertEquals(message, expected.toDouble(), actual.toDouble(), 1.0)
    }

    // endregion

    // region N1-J — Retry-After grammar (API r1 §2 table, r2 §3 additions)

    @Test
    fun `S7N1_J01 plain and decimal ASCII seconds are read as written`() {
        assertReads("N1-J", "5", 5.0)
        assertReads("N1-J", "1.5", 1.5)
        assertReads("N1-J", "0.5", 0.5)
        assertReads("N1-J", "120.25", 120.25)
        assertReads("N1-J", "1.50", 1.5)
        assertReads("N1-J", "007", 7.0)
        assertReads("N1-J", "7200", 7_200.0)
    }

    @Test
    fun `S7N1_J02 surrounding whitespace is trimmed`() {
        assertReads("N1-J ' 120 '", " 120 ", 120.0)
        assertReads("N1-J", " 1.5 ", 1.5)
        assertReads("N1-J", "\t7\t", 7.0)
    }

    @Test
    fun `S7N1_J03 absent, empty and blank are no header`() {
        assertNoHeader("N1-J absent", null, "5", 5.0)
        assertNoHeader("N1-J empty", "", "5", 5.0)
        assertNoHeader("N1-J blank", " ", "5", 5.0)
        assertNoHeader("N1-J blank", "   ", "5", 5.0)
        assertNoHeader("N1-J blank", "\t", "5", 5.0)
    }

    @Test
    fun `S7N1_J04 zero in any spelling is no header`() {
        assertNoHeader("N1-J zero", "0", "1", 1.0)
        assertNoHeader("N1-J zero", "0.0", "0.1", 0.1)
        assertNoHeader("N1-J zero", "00", "01", 1.0)
        assertNoHeader("N1-J zero", "0.000", "0.001", 0.001)
        assertNoHeader("N1-J zero", " 0 ", " 1 ", 1.0)
    }

    @Test
    fun `S7N1_J05 a sign is no header`() {
        assertNoHeader("N1-J negative", "-5", "5", 5.0)
        assertNoHeader("N1-J negative", "-1.5", "1.5", 1.5)
        assertNoHeader("N1-J negative", "-0", "5", 5.0)
        assertNoHeader("N1-J plus", "+5", "5", 5.0)
        assertNoHeader("N1-J plus", "+1.5", "1.5", 1.5)
    }

    @Test
    fun `S7N1_J06 exponents, hex and trailing characters are no header`() {
        assertNoHeader("N1-J exponent", "1e3", "1000", 1_000.0)
        assertNoHeader("N1-J exponent", "1E3", "1000", 1_000.0)
        assertNoHeader("N1-J exponent", "1e-3", "0.001", 0.001)
        assertNoHeader("N1-J hex", "0x10", "16", 16.0)
        assertNoHeader("N1-J hex", "0X1A", "26", 26.0)
        assertNoHeader("N1-J trailing", "5s", "5", 5.0)
        // The JVM's own number reader takes these type suffixes; the grammar does not.
        assertNoHeader("N1-J trailing", "5d", "5", 5.0)
        assertNoHeader("N1-J trailing", "5f", "5", 5.0)
        assertNoHeader("N1-J malformed", "1,5", "1.5", 1.5)
        assertNoHeader("N1-J malformed", "1.5.0", "1.5", 1.5)
        assertNoHeader("N1-J malformed", "1_000", "1000", 1_000.0)
    }

    @Test
    fun `S7N1_J07 infinity and NaN spellings are no header`() {
        for (header in listOf("inf", "Inf", "INF", "Infinity", "+Infinity", "-Infinity", "NaN", "nan", "-NaN")) {
            assertNoHeader("N1-J inf/NaN", header, "5", 5.0)
        }
    }

    @Test
    fun `S7N1_J08 an HTTP-date is no header and the caller falls back to backoff`() {
        val dates = listOf(
            "Wed, 21 Oct 2026 07:28:00 GMT", // IMF-fixdate
            "Wednesday, 21-Oct-26 07:28:00 GMT", // RFC 850
            "Wed Oct 21 07:28:00 2026" // asctime
        )
        for (date in dates) {
            assertNoHeader("N1-J HTTP-date", date, "120", 120.0)
            assertDecision(
                "N1-J HTTP-date is backoff",
                retry(2_000, byRetryAfter = false),
                http(503, AlertRetryAfter.parseSeconds(date)),
                attempt = 0
            )
        }
    }

    @Test
    fun `S7N1_J09 a half-written decimal or an inner space is no header`() {
        assertNoHeader("N1-J '.5'", ".5", "0.5", 0.5)
        assertNoHeader("N1-J '5.'", "5.", "5", 5.0)
        assertNoHeader("N1-J inner space", "1 0", "10", 10.0)
        assertNoHeader("N1-J inner space", "1. 5", "1.5", 1.5)
        assertNoHeader("N1-J inner space", "1 .5", "1.5", 1.5)
    }

    @Test
    fun `S7N1_J10 non-ASCII digits are no header`() {
        assertNoHeader("N1-J full-width", "５", "5", 5.0)
        assertNoHeader("N1-J full-width", "１２０", "120", 120.0)
        assertNoHeader("N1-J mixed", "1５", "15", 15.0)
        assertNoHeader("N1-J Arabic-Indic", "٥", "5", 5.0)
        assertNoHeader("N1-J Extended Arabic-Indic", "۵", "5", 5.0)
        assertNoHeader("N1-J Devanagari", "१", "1", 1.0)
    }

    @Test
    fun `S7N1_J11 a huge integer is a large finite value that saturates the delay, and only an infinite one is no header`() {
        val huge = read("N1-J huge integer", "99999999999999999999")
        assertEquals("N1-J huge integer: 99999999999999999999 is the nearest Double", 1.0e20, huge, 0.0)
        assertEquals("N1-J huge integer saturates", Long.MAX_VALUE, AlertRetryPolicy.secondsToMillis(huge))
        assertDecision(
            "N1-J huge integer is a Retry-After, saturated",
            retry(Long.MAX_VALUE, byRetryAfter = true),
            http(429, huge),
            attempt = 0
        )

        val stillFinite = "1" + "0".repeat(300)
        assertTrue("N1-J premise: 1e300 is still read", read("N1-J huge integer", stillFinite).isFinite())

        val tooBigForDouble = "1" + "0".repeat(400)
        assertNull("N1-J: an integer that reads as Infinity is no header", AlertRetryAfter.parseSeconds(tooBigForDouble))
    }

    @Test
    fun `S7N1_J12 a decimal that underflows to zero is no header`() {
        assertNoHeader("N1-J underflow", "0." + "0".repeat(400) + "1", "0.5", 0.5)
        assertNoHeader("N1-J underflow", "0." + "0".repeat(400), "0.5", 0.5)
    }

    @Test
    fun `S7N1_J13 a tiny positive decimal is read and floors at 1 ms`() {
        val micro = read("N1-J tiny", "0.000001")
        assertEquals("N1-J tiny: 0.000001 s", 1.0e-6, micro, 0.0)
        assertEquals("N1-J tiny: 1 µs is a 1 ms floor", 1L, AlertRetryPolicy.secondsToMillis(micro))
        assertDecision("N1-J tiny Retry-After", retry(1, byRetryAfter = true), http(503, micro), attempt = 0)

        // Smaller than any normal Double, still not zero once read.
        val subnormal = read("N1-J tiny", "0." + "0".repeat(320) + "1")
        assertTrue("N1-J tiny: a subnormal value is positive", subnormal > 0.0)
        assertEquals("N1-J tiny: a subnormal value is a 1 ms floor", 1L, AlertRetryPolicy.secondsToMillis(subnormal))
        assertDecision("N1-J subnormal Retry-After", retry(1, byRetryAfter = true), http(429, subnormal), attempt = 0)
    }

    @Test
    fun `S7N1_J14 the free snapshot parser keeps its own wider grammar`() {
        val now = Instant.parse("2026-09-06T01:30:00Z")
        val imfDate = "Wed, 21 Oct 2026 07:28:00 GMT"

        assertEquals(
            "N1-J: the free parser still reads an HTTP-date",
            Instant.parse("2026-10-21T07:28:00Z"),
            FreeSnapshotSchedulePolicy.retryFloorAfter(now, imfDate)
        )
        assertNull("N1-J: the alert grammar reads no HTTP-date", AlertRetryAfter.parseSeconds(imfDate))

        assertEquals(
            "N1-J: the free parser still reads delay-seconds",
            now + 120.seconds,
            FreeSnapshotSchedulePolicy.retryFloorAfter(now, "120")
        )
        assertEquals(
            "N1-J: the free parser still saturates a huge integer to its own distant future",
            Instant.DISTANT_FUTURE,
            FreeSnapshotSchedulePolicy.retryFloorAfter(now, "99999999999999999999")
        )
        assertNull("N1-J: the free parser still reads no fraction", FreeSnapshotSchedulePolicy.retryFloorAfter(now, "1.5"))
        assertReads("N1-J: the alert grammar reads the fraction", "1.5", 1.5)
    }

    // endregion

    // region N1-L — decision table (API r1 §2)

    @Test
    fun `S7N1_L01 the policy defaults are five retries, two token retries, a 2 s base and a 60 s cap`() {
        assertEquals("N1-L maxRetries", 5, policy.maxRetries)
        assertEquals("N1-L tokenMaxRetries", 2, policy.tokenMaxRetries)
        assertEquals("N1-L baseDelayMillis", 2_000L, policy.baseDelayMillis)
        assertEquals("N1-L maxBackoffMillis", 60_000L, policy.maxBackoffMillis)
    }

    @Test
    fun `S7N1_L02 Withdrawn is silent at every attempt`() {
        assertDecision("N1-L premise", retry(2_000, byRetryAfter = false), repo(AlertRepositoryFailureKind.NETWORK), 0)
        for (attempt in listOf(0, 1, 2, 4, 5, 6, Int.MAX_VALUE)) {
            for (jitter in listOf(noJitter, lowJitter, highJitter)) {
                assertDecision("N1-L Withdrawn", AlertRetryDecision.Silent, AlertFetchFailure.Withdrawn, attempt, jitter)
            }
        }
    }

    @Test
    fun `S7N1_L03 401 ends AuthRequired before any Retry-After and before exhaustion`() {
        assertDecision("N1-L premise: the same header retries a 429", retry(30_000, true), http(429, 30.0), 0)
        for (retryAfter in listOf(null, 0.5, 30.0, 7_200.0)) {
            for (attempt in listOf(0, 1, 4, 5, Int.MAX_VALUE)) {
                assertDecision("N1-L 401", authRequired, http(401, retryAfter), attempt)
            }
        }
    }

    @Test
    fun `S7N1_L04 403 ends Forbidden before any Retry-After and before exhaustion`() {
        assertDecision("N1-L premise: the same header retries a 503", retry(30_000, true), http(503, 30.0), 0)
        for (retryAfter in listOf(null, 0.5, 30.0, 7_200.0)) {
            for (attempt in listOf(0, 1, 4, 5, Int.MAX_VALUE)) {
                assertDecision("N1-L 403", forbidden, http(403, retryAfter), attempt)
            }
        }
    }

    @Test
    fun `S7N1_L05 a retryable answer with a Retry-After retries on exactly that delay, never capped and never jittered`() {
        for (status in retryableStatuses) {
            for (attempt in 0..4) {
                for (jitter in listOf(noJitter, lowJitter, highJitter)) {
                    // A longer Retry-After never shortens: two hours stays two hours although backoff caps at 60 s.
                    assertDecision("N1-L no down-cap", retry(7_200_000, true), http(status, 7_200.0), attempt, jitter)
                    assertDecision("N1-L Retry-After", retry(1_500, true), http(status, 1.5), attempt, jitter)
                    // The 1–60 s bounds belong to backoff; a shorter Retry-After is the delay as the server gave it.
                    assertDecision("N1-L below 1 s", retry(500, true), http(status, 0.5), attempt, jitter)
                }
            }
        }
        assertDecision("N1-L a year", retry(31_536_000_000, true), http(503, 365.0 * 86_400.0), 0)
        // Shorter than the backoff it replaces (32 s at attempt 4) and still the delay.
        assertDecision("N1-L shorter than backoff", retry(1_000, true), http(429, 1.0), 4)
    }

    @Test
    fun `S7N1_L06 a retryable answer without a usable Retry-After retries on backoff`() {
        // API r1 §2 takes a Retry-After only when it is above zero; anything else is no header.
        for (retryAfter in listOf(null, 0.0, -0.0, -1.0, Double.NaN)) {
            for (status in retryableStatuses) {
                for (attempt in 0..4) {
                    assertDecision(
                        "N1-L backoff",
                        retry(ladderMillis[attempt], byRetryAfter = false),
                        http(status, retryAfter),
                        attempt
                    )
                    for (jitter in listOf(lowJitter, highJitter)) {
                        assertDecision(
                            "N1-L backoff is the policy's backoff",
                            retry(policy.backoffMillis(attempt, jitter), byRetryAfter = false),
                            http(status, retryAfter),
                            attempt,
                            jitter
                        )
                    }
                }
            }
        }
        // The scope row by name: a 429 without the header is retried.
        assertDecision("N1-L 429 without a header", retry(2_000, byRetryAfter = false), http(429), 0)
    }

    @Test
    fun `S7N1_L07 a retryable answer ends Failed HTTP with its status once five retries are spent, header or not`() {
        for (status in retryableStatuses) {
            assertDecision("N1-L premise: attempt 4 still retries", retry(32_000, false), http(status), 4)
            for (retryAfter in listOf(null, 1.5, 30.0, 7_200.0)) {
                for (attempt in exhaustedAttempts) {
                    assertDecision(
                        "N1-L exhausted",
                        failed(AlertRepositoryFailureKind.HTTP, status),
                        http(status, retryAfter),
                        attempt
                    )
                }
            }
        }
    }

    @Test
    fun `S7N1_L08 every other HTTP answer ends at once with its status, a Retry-After notwithstanding`() {
        for (status in listOf(408, 429, 500, 599)) {
            assertDecision("N1-L premise: the boundary neighbour retries", retry(2_000, false), http(status), 0)
        }
        val others = listOf(100, 200, 204, 301, 304, 400, 402, 404, 407, 409, 410, 422, 428, 430, 451, 499, 600, 999)
        for (status in others) {
            for (retryAfter in listOf(null, 30.0, 7_200.0)) {
                for (attempt in listOf(0, 1, 4, 5)) {
                    assertDecision(
                        "N1-L non-retryable",
                        failed(AlertRepositoryFailureKind.HTTP, status),
                        http(status, retryAfter),
                        attempt
                    )
                }
            }
        }
    }

    @Test
    fun `S7N1_L09 NETWORK retries on the 2 4 8 16 32 s backoff and then ends Failed NETWORK`() {
        val network = repo(AlertRepositoryFailureKind.NETWORK)
        for (attempt in 0..4) {
            assertDecision("N1-L NETWORK", retry(ladderMillis[attempt], byRetryAfter = false), network, attempt)
            for (jitter in listOf(lowJitter, highJitter)) {
                assertDecision(
                    "N1-L NETWORK jittered",
                    retry(policy.backoffMillis(attempt, jitter), byRetryAfter = false),
                    network,
                    attempt,
                    jitter
                )
            }
        }
        for (attempt in exhaustedAttempts) {
            assertDecision("N1-L NETWORK exhausted", failed(AlertRepositoryFailureKind.NETWORK, null), network, attempt)
        }
    }

    @Test
    fun `S7N1_L10 AUTH_UNAVAILABLE retries twice on backoff and then ends AuthRequired`() {
        val credential = repo(AlertRepositoryFailureKind.AUTH_UNAVAILABLE)
        assertDecision("N1-L AUTH_UNAVAILABLE", retry(2_000, byRetryAfter = false), credential, 0)
        assertDecision("N1-L AUTH_UNAVAILABLE", retry(4_000, byRetryAfter = false), credential, 1)
        for (attempt in listOf(2, 3, 4, 5, 6, Int.MAX_VALUE)) {
            assertDecision("N1-L AUTH_UNAVAILABLE exhausted", authRequired, credential, attempt)
        }
    }

    /** API r1 §2: the credential row's Retry(backoff) is the jittered backoff NETWORK uses, so clients do not retry in step. */
    @Test
    fun `S7N1_L10b AUTH_UNAVAILABLE retries on the same jittered backoff as NETWORK`() {
        val credential = repo(AlertRepositoryFailureKind.AUTH_UNAVAILABLE)
        val network = repo(AlertRepositoryFailureKind.NETWORK)
        // Window: attempts 0 and 1 (the only ones below tokenMaxRetries) under a jitter end, where an unjittered credential
        // retry reads 2 s / 4 s instead of 1.6 s / 3.2 s (-0.2) and 2.4 s / 4.8 s (+0.2).
        val jittered = listOf(
            lowJitter to listOf(1_600L, 3_200L),
            highJitter to listOf(2_400L, 4_800L)
        )
        for ((jitter, expectedMillis) in jittered) {
            for (attempt in 0..1) {
                assertDecision(
                    "N1-L AUTH_UNAVAILABLE premise: attempt $attempt still retries",
                    retry(ladderMillis[attempt], byRetryAfter = false),
                    credential,
                    attempt
                )
                val networkDecision = policy.decide(network, attempt, jitter)
                assertTrue(
                    "N1-L AUTH_UNAVAILABLE premise: NETWORK at attempt $attempt is a backoff Retry, ${label(networkDecision)}",
                    networkDecision is AlertRetryDecision.Retry && !networkDecision.byRetryAfter
                )
                assertMillisNear(
                    "N1-L AUTH_UNAVAILABLE premise: the jitter end moves NETWORK's backoff at attempt $attempt",
                    expectedMillis[attempt],
                    (networkDecision as AlertRetryDecision.Retry).delayMillis
                )
                val decision = policy.decide(credential, attempt, jitter)
                assertTrue(
                    "N1-L AUTH_UNAVAILABLE jittered at attempt $attempt is a backoff Retry, ${label(decision)}",
                    decision is AlertRetryDecision.Retry && !decision.byRetryAfter
                )
                assertMillisNear(
                    "N1-L AUTH_UNAVAILABLE jittered at attempt $attempt: ${label(decision)}",
                    expectedMillis[attempt],
                    (decision as AlertRetryDecision.Retry).delayMillis
                )
                assertEquals("N1-L AUTH_UNAVAILABLE at attempt $attempt: the same backoff as NETWORK", networkDecision, decision)
            }
            val chained = chain(credential, jitter)
            assertEquals("N1-L AUTH_UNAVAILABLE jittered chain: three logical attempts", 3, chained.attempts)
            assertEquals("N1-L AUTH_UNAVAILABLE jittered chain: two delays", 2, chained.delays.size)
            for ((index, delay) in chained.delays.withIndex()) {
                assertMillisNear("N1-L AUTH_UNAVAILABLE jittered chain delay $index", expectedMillis[index], delay)
            }
            assertEquals("N1-L AUTH_UNAVAILABLE jittered chain end", authRequired, chained.end)
        }
    }

    @Test
    fun `S7N1_L11 DECODE, UNEXPECTED and an HTTP kind without a status end at once`() {
        assertDecision("N1-L premise", retry(2_000, byRetryAfter = false), repo(AlertRepositoryFailureKind.NETWORK), 0)
        val terminalKinds = listOf(
            AlertRepositoryFailureKind.DECODE,
            AlertRepositoryFailureKind.UNEXPECTED,
            // A typed HTTP failure that reached the policy without its answer has no status to retry on.
            AlertRepositoryFailureKind.HTTP
        )
        for (kind in terminalKinds) {
            for (attempt in listOf(0, 1, 4, 5, Int.MAX_VALUE)) {
                for (jitter in listOf(noJitter, highJitter)) {
                    assertDecision("N1-L terminal kind", failed(kind, null), repo(kind), attempt, jitter)
                }
            }
        }
    }

    @Test
    fun `S7N1_L12 every repository failure kind has its row in the table`() {
        assertEquals(
            "N1-L: the typed kinds of API r1 §1",
            setOf(
                AlertRepositoryFailureKind.HTTP,
                AlertRepositoryFailureKind.DECODE,
                AlertRepositoryFailureKind.NETWORK,
                AlertRepositoryFailureKind.AUTH_UNAVAILABLE,
                AlertRepositoryFailureKind.UNEXPECTED
            ),
            AlertRepositoryFailureKind.values().toSet()
        )
        for (kind in AlertRepositoryFailureKind.values()) {
            // Exhaustive: a new kind fails to compile here until the table gives it a row.
            val first: AlertRetryDecision = when (kind) {
                AlertRepositoryFailureKind.NETWORK, AlertRepositoryFailureKind.AUTH_UNAVAILABLE -> retry(2_000, false)
                AlertRepositoryFailureKind.HTTP, AlertRepositoryFailureKind.DECODE, AlertRepositoryFailureKind.UNEXPECTED ->
                    failed(kind, null)
            }
            assertDecision("N1-L first failure", first, repo(kind), 0)
        }
    }

    @Test
    fun `S7N1_L13 logical attempts are six for retryable failures and three for a missing credential`() {
        val network = chain(repo(AlertRepositoryFailureKind.NETWORK))
        assertEquals("N1-L NETWORK: six logical attempts", 6, network.attempts)
        assertEquals("N1-L NETWORK delays", ladderMillis, network.delays)
        assertEquals("N1-L NETWORK end", failed(AlertRepositoryFailureKind.NETWORK, null), network.end)

        for (status in retryableStatuses) {
            val answer = chain(http(status))
            assertEquals("N1-L $status: six logical attempts", 6, answer.attempts)
            assertEquals("N1-L $status delays", ladderMillis, answer.delays)
            assertEquals("N1-L $status end", failed(AlertRepositoryFailureKind.HTTP, status), answer.end)
        }

        val limited = chain(http(429, 7_200.0))
        assertEquals("N1-L 429 with a header: six logical attempts", 6, limited.attempts)
        assertEquals("N1-L 429 with a header: every delay is the header's", List(5) { 7_200_000L }, limited.delays)
        assertEquals("N1-L 429 with a header: end", failed(AlertRepositoryFailureKind.HTTP, 429), limited.end)

        val credential = chain(repo(AlertRepositoryFailureKind.AUTH_UNAVAILABLE))
        assertEquals("N1-L AUTH_UNAVAILABLE: three logical attempts", 3, credential.attempts)
        assertEquals("N1-L AUTH_UNAVAILABLE delays", listOf(2_000L, 4_000L), credential.delays)
        assertEquals("N1-L AUTH_UNAVAILABLE end", authRequired, credential.end)

        val once = listOf(
            http(401, 5.0) to authRequired,
            http(403, 5.0) to forbidden,
            http(404, 5.0) to failed(AlertRepositoryFailureKind.HTTP, 404),
            repo(AlertRepositoryFailureKind.DECODE) to failed(AlertRepositoryFailureKind.DECODE, null),
            repo(AlertRepositoryFailureKind.UNEXPECTED) to failed(AlertRepositoryFailureKind.UNEXPECTED, null),
            AlertFetchFailure.Withdrawn to AlertRetryDecision.Silent
        )
        for ((failure, end) in once) {
            val single = chain(failure)
            assertEquals("N1-L ${label(failure)}: one logical attempt", 1, single.attempts)
            assertEquals("N1-L ${label(failure)}: end ${label(end)}", end, single.end)
        }
    }

    @Test
    fun `S7N1_L14 the failure handed to the policy carries no message, only typed values`() {
        val typed = listOf(
            AlertFetchFailure.Http::class.java,
            AlertFetchFailure.Repository::class.java,
            AlertLoadFailure.Failed::class.java
        )
        for (type in typed) {
            val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            assertTrue("N1-L premise: ${type.simpleName} has instance fields to inspect", fields.isNotEmpty())
            for (field in fields) {
                val fieldType = field.type
                val isTyped = fieldType.isPrimitive || fieldType.isEnum ||
                    Number::class.javaObjectType.isAssignableFrom(fieldType) ||
                    fieldType == Boolean::class.javaObjectType
                assertTrue(
                    "N1-L: ${type.simpleName}.${field.name} is ${fieldType.name}; a classification reads no string or exception",
                    isTyped
                )
            }
        }
        val withdrawn = AlertFetchFailure.Withdrawn::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        assertTrue("N1-L: Withdrawn carries nothing", withdrawn.isEmpty())
    }

    @Test
    fun `S7N1_L15 backoff doubles 2 4 8 16 32 s and caps at 60 s`() {
        for ((attempt, expected) in ladderMillis.withIndex()) {
            assertEquals("N1-L backoff at attempt $attempt", expected, policy.backoffMillis(attempt, noJitter))
        }
        assertEquals("N1-L backoff cap at attempt 5 (64 s)", 60_000L, policy.backoffMillis(5, noJitter))
        assertEquals("N1-L backoff cap at attempt 6", 60_000L, policy.backoffMillis(6, noJitter))
    }

    @Test
    fun `S7N1_L16 the jitter ends move backoff by twenty percent and the result stays within 1 to 60 s`() {
        val low = listOf(1_600L, 3_200L, 6_400L, 12_800L, 25_600L)
        val high = listOf(2_400L, 4_800L, 9_600L, 19_200L, 38_400L)
        for (attempt in 0..4) {
            assertMillisNear("N1-L jitter -0.2 at attempt $attempt", low[attempt], policy.backoffMillis(attempt, lowJitter))
            assertMillisNear("N1-L jitter +0.2 at attempt $attempt", high[attempt], policy.backoffMillis(attempt, highJitter))
        }
        for (attempt in listOf(5, 6, 10)) {
            assertMillisNear("N1-L jitter -0.2 under the cap", 48_000L, policy.backoffMillis(attempt, lowJitter))
            assertEquals("N1-L jitter +0.2 clamps to 60 s", 60_000L, policy.backoffMillis(attempt, highJitter))
        }
    }

    @Test
    fun `S7N1_L17 backoff never goes below 1 s`() {
        val oneSecond = AlertRetryPolicy(baseDelayMillis = 1_000)
        assertEquals("N1-L premise: a 1 s base reads 1 s", 1_000L, oneSecond.backoffMillis(0, noJitter))
        assertMillisNear("N1-L premise: +0.2 still moves it", 1_200L, oneSecond.backoffMillis(0, highJitter))
        assertEquals("N1-L: -0.2 below 1 s clamps to 1 s", 1_000L, oneSecond.backoffMillis(0, lowJitter))

        val halfSecond = AlertRetryPolicy(baseDelayMillis = 500)
        for (jitter in listOf(lowJitter, noJitter, highJitter)) {
            assertEquals("N1-L: a 0.5 s base clamps to 1 s", 1_000L, halfSecond.backoffMillis(0, jitter))
        }
        assertMillisNear("N1-L premise: doubling leaves the floor", 2_400L, halfSecond.backoffMillis(2, highJitter))
    }

    @Test
    fun `S7N1_L18 a huge attempt saturates at the cap without overflow`() {
        for (attempt in listOf(29, 30, 31, 32, 33, 62, 63, 64, 65, 1_000, 1_000_000, Int.MAX_VALUE)) {
            assertEquals("N1-L cap at attempt $attempt", 60_000L, policy.backoffMillis(attempt, noJitter))
            assertEquals("N1-L cap at attempt $attempt, +0.2", 60_000L, policy.backoffMillis(attempt, highJitter))
            assertMillisNear("N1-L cap at attempt $attempt, -0.2", 48_000L, policy.backoffMillis(attempt, lowJitter))
        }
    }

    @Test
    fun `S7N1_L18b every attempt and jitter stays within its band, non-decreasing in the attempt`() {
        val jitters = listOf(-0.2, -0.1, 0.0, 0.1, 0.19999999999999998, 0.2)
        val attempts = (0..200).toList() + Int.MAX_VALUE
        for (j in jitters) {
            var previous = Long.MIN_VALUE
            for (attempt in attempts) {
                val actual = policy.backoffMillis(attempt, { j })
                // b = min(2 s · 2^attempt, 60 s); 2 s · 2^5 = 64 s is the first past the cap.
                val base = if (attempt >= 5) 60_000.0 else 2_000.0 * (1L shl attempt)
                val expected = (base + base * j).coerceIn(1_000.0, 60_000.0)
                assertTrue("N1-L attempt $attempt, jitter $j: $actual is within 1–60 s", actual in 1_000L..60_000L)
                assertEquals("N1-L attempt $attempt, jitter $j: min(60 s, max(1 s, b + b·j))", expected, actual.toDouble(), 1.0)
                assertTrue("N1-L attempt $attempt, jitter $j: never shorter than the attempt before", actual >= previous)
                previous = actual
            }
        }
    }

    @Test
    fun `S7N1_L19 seconds to milliseconds rounds up, so a sub-millisecond floor is 1 ms`() {
        assertEquals("N1-L 1 s", 1_000L, AlertRetryPolicy.secondsToMillis(1.0))
        assertEquals("N1-L 1.5 s", 1_500L, AlertRetryPolicy.secondsToMillis(1.5))
        assertEquals("N1-L 7200 s", 7_200_000L, AlertRetryPolicy.secondsToMillis(7_200.0))
        assertEquals("N1-L 0.1 ms rounds up", 1L, AlertRetryPolicy.secondsToMillis(0.0001))
        assertEquals("N1-L 1.5 ms rounds up", 2L, AlertRetryPolicy.secondsToMillis(0.0015))
        assertEquals("N1-L 1000.1 ms rounds up", 1_001L, AlertRetryPolicy.secondsToMillis(1.0001))
        assertEquals("N1-L the smallest Double is 1 ms", 1L, AlertRetryPolicy.secondsToMillis(Double.MIN_VALUE))
        assertDecision("N1-L a 0.1 ms Retry-After is a 1 ms delay", retry(1, true), http(503, 0.0001), 0)
    }

    @Test
    fun `S7N1_L20 seconds to milliseconds saturates at Long MAX_VALUE`() {
        assertEquals(
            "N1-L premise: just under the range is exact",
            9_200_000_000_000_000_000L,
            AlertRetryPolicy.secondsToMillis(9.2e15)
        )
        for (seconds in listOf(9.3e15, 9.223372036854775807e15, 1.0e20, Double.MAX_VALUE, Double.POSITIVE_INFINITY)) {
            assertEquals("N1-L $seconds s saturates", Long.MAX_VALUE, AlertRetryPolicy.secondsToMillis(seconds))
        }
        assertDecision("N1-L a huge Retry-After is a saturated delay", retry(Long.MAX_VALUE, true), http(503, 1.0e20), 0)
    }

    // endregion

    private companion object {
        const val MAX_CHAIN = 50
    }
}
