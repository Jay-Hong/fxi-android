package com.jay.fxi.contract

import com.jay.fxi.data.remote.ClientMetadataInterceptor
import com.jay.fxi.time.AppClock
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * S0 transport-harness plumbing only: deterministic response order, HTTP metadata headers and
 * independently injected wall/virtual clocks. Retrofit/auth/domain DTO integration belongs to S1.
 */
class ContractHttpSequenceTest {
    private lateinit var server: MockWebServer
    private lateinit var corpus: VerifiedContractCorpus
    private lateinit var clock: MutableContractClock
    private lateinit var client: ContractHttpSequenceClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        corpus = ContractCorpusVerifier.verify(ContractResourceLoader.load())
        clock = MutableContractClock(Instant.parse("2026-08-31T01:20:00Z"))
        client = ContractHttpSequenceClient(
            server,
            corpus,
            clock,
            OkHttpClient.Builder().addInterceptor(ClientMetadataInterceptor()).build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `body retry and HTTP Retry-After remain separate typed sources`() {
        client.scriptResponses(
            "entitlements-pending",
            "http-premium-pending-503",
            "http-auth-infrastructure-503"
        )

        val bodyPending = client.executeRequest("entitlements-pending")
        assertEquals(200, bodyPending.status)
        assertEquals(5L, bodyPending.bodyRetryAfterSeconds)
        assertNull(bodyPending.headerRetryAfterSeconds)
        assertEquals(clock.now(), bodyPending.observedAt)
        assertEquals(corpus.text("entitlements-pending"), bodyPending.body)

        clock.advance(5.seconds)
        val headerPending = client.executeRequest("http-premium-pending-503")
        assertEquals(503, headerPending.status)
        assertNull(headerPending.bodyRetryAfterSeconds)
        assertEquals(5L, headerPending.headerRetryAfterSeconds)
        assertEquals(clock.now(), headerPending.observedAt)
        assertEquals(corpus.text("http-premium-pending-503"), headerPending.body)

        clock.advance(1.seconds)
        val infrastructure = client.executeRequest("http-auth-infrastructure-503")
        assertEquals(503, infrastructure.status)
        assertNull(infrastructure.bodyRetryAfterSeconds)
        assertNull(infrastructure.headerRetryAfterSeconds)

        val first = server.takeRequest(2, TimeUnit.SECONDS)
        val second = server.takeRequest(2, TimeUnit.SECONDS)
        val third = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(third)
        assertEquals("/api/entitlements", first!!.path)
        assertEquals("/api/notification-settings", second!!.path)
        assertEquals("/api/entitlements", third!!.path)
        assertEquals("GET", first.method)
        assertEquals("POST", second.method)
        assertEquals("GET", third.method)
        listOf(first, second, third).forEach { request ->
            assertEquals("android", request.getHeader("X-Client-Platform"))
            assertNotNull(request.getHeader("X-Client-Version"))
            assertNotNull(request.getHeader("X-Client-Build"))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `one entitlement operation consumes a pre-scripted pending then active sequence`() = runTest {
        val sequenceClock = SchedulerContractClock(
            testScheduler,
            Instant.parse("2026-08-31T01:20:00Z")
        )
        val sequenceClient = ContractHttpSequenceClient(
            server,
            corpus,
            sequenceClock,
            OkHttpClient.Builder().addInterceptor(ClientMetadataInterceptor()).build()
        )
        sequenceClient.scriptResponses(
            "entitlements-pending",
            "entitlements-active-krx-visible"
        )
        val gate = ContractRetryGate(sequenceClock)

        val operation = async {
            val pending = sequenceClient.executeRequest("entitlements-pending")
            val retryAfter = requireNotNull(pending.bodyRetryAfterSeconds)
            gate.await(gate.schedule(retryAfter.seconds))
            val active = sequenceClient.executeRequest("entitlements-pending")
            pending to active
        }

        runCurrent()
        assertFalse(operation.isCompleted)
        assertEquals(1, server.requestCount)

        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(operation.isCompleted)
        assertEquals(1, server.requestCount)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(operation.isCompleted)
        assertEquals(2, server.requestCount)

        val (pending, active) = operation.await()
        assertEquals(200, pending.status)
        assertEquals(5L, pending.bodyRetryAfterSeconds)
        assertEquals(corpus.text("entitlements-pending"), pending.body)
        assertEquals(200, active.status)
        assertNull(active.bodyRetryAfterSeconds)
        assertEquals(corpus.text("entitlements-active-krx-visible"), active.body)
        assertEquals(pending.observedAt + 5.seconds, active.observedAt)

        val first = server.takeRequest(2, TimeUnit.SECONDS)
        val second = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("GET", first!!.method)
        assertEquals("GET", second!!.method)
        assertEquals("/api/entitlements", first.path)
        assertEquals("/api/entitlements", second.path)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `virtual coroutine delay does not advance injected wall clock`() = runTest {
        val gate = ContractRetryGate(clock)
        assertThrows(IllegalArgumentException::class.java) {
            client.scriptResponses()
        }
        assertThrows(IllegalArgumentException::class.java) {
            gate.schedule((-1).seconds)
        }
        val ticket = gate.schedule(5.seconds)
        val start = clock.now()
        val waiting = async { gate.await(ticket) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertEquals(start, clock.now())

        clock.advance(5.seconds)
        assertEquals(ticket.notBefore, clock.now())
    }
}

private data class ContractHttpSpec(
    val method: String,
    val path: String,
    val status: Int,
    val headers: Map<String, String>
)

private data class ContractHttpObservation(
    val status: Int,
    val body: String,
    val headerRetryAfterSeconds: Long?,
    val bodyRetryAfterSeconds: Long?,
    val observedAt: Instant
)

private class ContractHttpSequenceClient(
    private val server: MockWebServer,
    private val corpus: VerifiedContractCorpus,
    private val clock: AppClock,
    private val httpClient: OkHttpClient
) {
    fun scriptResponses(vararg fixtureIds: String) {
        require(fixtureIds.isNotEmpty()) { "response script must not be empty" }
        fixtureIds.forEach { fixtureId ->
            val entry = corpus.entry(fixtureId)
            val spec = entry.httpSpec()
            val response = MockResponse()
                .setResponseCode(spec.status)
                .setBody(Buffer().write(corpus.bytes(fixtureId)))
            spec.headers.forEach(response::addHeader)
            server.enqueue(response)
        }
    }

    fun executeRequest(fixtureId: String): ContractHttpObservation {
        val entry = corpus.entry(fixtureId)
        val spec = entry.httpSpec()

        val body = if (spec.method == "GET" || spec.method == "HEAD") {
            null
        } else {
            "{}".toRequestBody("application/json".toMediaType())
        }
        val request = Request.Builder()
            .url(server.url(spec.path))
            .method(spec.method, body)
            .build()
        val observedAt = clock.now()
        httpClient.newCall(request).execute().use { actual ->
            val actualBody = actual.body?.string().orEmpty()
            val bodyRetry = runCatching {
                Json.parseToJsonElement(actualBody).jsonObject["retry_after_seconds"]
                    ?.jsonPrimitive?.longOrNull
            }.getOrNull()
            return ContractHttpObservation(
                status = actual.code,
                body = actualBody,
                headerRetryAfterSeconds = actual.header("Retry-After")?.toLongOrNull(),
                bodyRetryAfterSeconds = bodyRetry,
                observedAt = observedAt
            )
        }
    }

    private fun ContractFixtureEntry.httpSpec(): ContractHttpSpec {
        val http = requireNotNull(source["http"]?.jsonObject) {
            "fixture has no HTTP metadata: $id"
        }
        val headers = http["headers"]!!.jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.content
        }
        return ContractHttpSpec(
            method = http["method"]!!.jsonPrimitive.content,
            path = http["path"]!!.jsonPrimitive.content,
            status = http["status"]!!.jsonPrimitive.int,
            headers = headers
        )
    }
}

private class MutableContractClock(private var value: Instant) : AppClock {
    override fun now(): Instant = value

    fun advance(duration: Duration) {
        value += duration
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class SchedulerContractClock(
    private val scheduler: TestCoroutineScheduler,
    private val start: Instant
) : AppClock {
    override fun now(): Instant = start + scheduler.currentTime.milliseconds
}

private data class ContractRetryTicket(
    val scheduledAt: Instant,
    val notBefore: Instant
)

private class ContractRetryGate(private val clock: AppClock) {
    fun schedule(delay: Duration): ContractRetryTicket {
        require(!delay.isNegative()) { "retry delay must not be negative" }
        val now = clock.now()
        return ContractRetryTicket(now, now + delay)
    }

    suspend fun await(ticket: ContractRetryTicket) {
        val remaining = ticket.notBefore - clock.now()
        if (remaining.isPositive()) {
            delay(remaining.inWholeMilliseconds.milliseconds)
        }
    }
}
