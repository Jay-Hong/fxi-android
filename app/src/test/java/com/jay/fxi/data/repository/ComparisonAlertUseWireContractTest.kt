package com.jay.fxi.data.repository

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import com.jay.fxi.data.remote.dto.ComparisonAlertRequest
import com.jay.fxi.data.remote.dto.ComparisonAlertUpdateRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.ComparisonAlertSetting
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class ComparisonAlertUseWireContractTest {
    private lateinit var server: MockWebServer
    private lateinit var source: TokenSource
    private lateinit var client: AuthenticatedApiClient
    private lateinit var repository: AlertRepository
    private lateinit var owner: AuthIdentityFence

    /** Counts token fetches; [gate] holds every fetch, [forcedGate] only a forced refresh. */
    private class TokenSource : AuthTokenSource {
        val fetches = AtomicInteger()
        val forcedFetches = AtomicInteger()
        val started = AtomicInteger()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var forcedGate: CompletableDeferred<Unit>? = null
        val forcedStarted = CompletableDeferred<Unit>()
        @Volatile var identity = AuthIdentity("user-a", 1)
        @Volatile var unavailable = false
        override fun currentIdentity() = identity
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetches.incrementAndGet()
            started.incrementAndGet()
            gate?.await()
            if (unavailable) throw AuthUnavailableException("capture unavailable")
            if (forceRefresh) {
                forcedFetches.incrementAndGet()
                forcedStarted.complete(Unit)
                forcedGate?.await()
            }
            return if (forceRefresh) "fresh" else "credential"
        }
    }

    /** Requests that reached the client's chain at all (the first application interceptor). */
    private val clientEntries = AtomicInteger()

    /** For each request at the network stage: its credential and whether it carried a use check. */
    private data class Sent(val authorization: String?, val useChecked: Boolean)

    private val wire = java.util.Collections.synchronizedList(mutableListOf<Sent>())
    private val release = AtomicBoolean(true)

    /** Runs at the network stage right before the use-checking interceptor, with the exchange's 1-based number in the call. */
    @Volatile private var beforeUseCheck: (Int) -> Unit = {}

    @Before fun setUp() {
        fresh()
    }

    @After fun tearDown() = server.shutdown()

    /** A new server, token source and chain: one case's answers and counts stay out of the next. */
    private fun fresh() {
        if (::server.isInitialized) server.shutdown()
        server = MockWebServer().apply { start() }
        source = TokenSource()
        val provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        clientEntries.set(0)
        wire.clear()
        beforeUseCheck = {}
        val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                clientEntries.incrementAndGet()
                chain.proceed(chain.request())
            }
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor { chain ->
                wire += Sent(chain.request().header("Authorization"), chain.request().tag(TopicUseTag::class.java) != null)
                beforeUseCheck(wire.size)
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .build()
        val wireJson = NetworkModule.provideWireJson()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/")).client(http)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
        client = AuthenticatedApiClient(retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { release.get() }), wireJson)
        repository = AlertRepository(client)
        owner = checkNotNull(repository.captureOwnerOrNull())
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/contracts/v2/alerts/comparison-$name.json")).readText()

    private val json = NetworkModule.provideWireJson()
    private val row = fixture("setting-response")
    private val setting = json.decodeFromString<ComparisonAlertSetting>(row)
    private val log = json.decodeFromString<ComparisonAlertHistoryItem>(fixture("history-item"))
    private val createBody = fixture("signed-create-request")
    private val create = json.decodeFromString<ComparisonAlertRequest>(createBody)
    private val toggle = ComparisonAlertUpdateRequest(isEnabled = false)

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private class Supplier(private val first: Int = Int.MAX_VALUE) {
        val asked = AtomicInteger()
        val check: () -> Boolean = { asked.incrementAndGet() <= first }
    }

    private class Call(
        val label: String, val read: Boolean, val method: String, val path: String,
        val body: String?, val expected: Any, val ok: () -> MockResponse,
        val run: suspend (AlertRepository, AuthIdentityFence, (() -> Boolean)?) -> AuthBoundResult<*>,
        val omitted: suspend (AlertRepository, AuthIdentityFence) -> AuthBoundResult<*>
    )

    private val calls = listOf(
        Call("settings GET", true, "GET", "/api/comparison-alerts?tab=usd", null, listOf(setting),
            { ok(fixture("settings-list")) }, { r, o, u -> r.getComparisonAlerts(o, "usd", u) },
            { r, o -> r.getComparisonAlerts(o, "usd") }),
        Call("history GET", true, "GET", "/api/comparison-notification-logs?tab=usd&diff_type=absolute&limit=50",
            null, listOf(log), { ok(fixture("history-list")) },
            { r, o, u -> r.getComparisonHistory(o, "usd", "absolute", 50, u) },
            { r, o -> r.getComparisonHistory(o, "usd", "absolute", 50) }),
        Call("create", false, "POST", "/api/comparison-alerts", createBody, setting, { ok(row) },
            { r, o, u -> r.createComparisonAlert(o, create, u) }, { r, o -> r.createComparisonAlert(o, create) }),
        Call("update", false, "PUT", "/api/comparison-alerts/103", """{"is_enabled":false}""", setting, { ok(row) },
            { r, o, u -> r.updateComparisonAlert(o, 103, toggle, u) }, { r, o -> r.updateComparisonAlert(o, 103, toggle) }),
        Call("delete", false, "DELETE", "/api/comparison-alerts/103", null, Unit, { ok("arbitrary delete response") },
            { r, o, u -> r.deleteComparisonAlert(o, 103, u) }, { r, o -> r.deleteComparisonAlert(o, 103) })
    )

    private fun assertRequest(c: Call) {
        assertEquals(c.label, 1, server.requestCount)
        val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(c.label, c.method to c.path, request.method to request.path)
        val body = request.body.readUtf8()
        if (c.body == null) assertEquals(c.label, "", body)
        else assertEquals(c.label, json.parseToJsonElement(c.body), json.parseToJsonElement(body))
    }

    private val reads get() = calls.filter { it.read }
    private val mutations get() = calls.filterNot { it.read }

    private fun succeeded(result: AuthBoundResult<*>) = result.fold({ true }, { false })

    private fun failureOf(result: AuthBoundResult<*>) = result.fold({ null }, { it }) as? AlertRepositoryException

    private suspend fun withheld(label: String, block: suspend () -> Unit): TopicUseWithheldException {
        try {
            block()
        } catch (refused: TopicUseWithheldException) {
            return refused
        }
        fail("$label: a refused use must not return a result")
        throw AssertionError()
    }

    @Test fun `CU01 admitted calls preserve the request and response and recheck at fold`() = runBlocking {
        for (c in calls) {
            fresh()
            server.enqueue(c.ok())
            val supplier = Supplier()
            val result = c.run(repository, owner, supplier.check)
            val beforeFold = supplier.asked.get()
            assertTrue("${c.label}: checked before applying", beforeFold >= 3)
            assertEquals(c.label, c.expected, result.fold({ it }, { throw it }))
            assertEquals(c.label, beforeFold + 1, supplier.asked.get())
            assertRequest(c)
            assertEquals("${c.label}: the use check is on the wire", listOf(Sent("Bearer credential", true)), wire.toList())
        }
    }

    @Test fun `CU02 revocation at the last wire check withholds the first send`() = runBlocking {
        for (c in calls) {
            fresh()
            server.enqueue(c.ok())
            val admitted = AtomicBoolean(true)
            val reached = AtomicInteger()
            val asked = AtomicInteger()
            val questionsAtHook = AtomicInteger()
            val denials = AtomicInteger()
            beforeUseCheck = { exchange ->
                if (exchange == 1) {
                    reached.incrementAndGet()
                    questionsAtHook.set(asked.get())
                    admitted.set(false)
                }
            }
            val refused = withheld(c.label) {
                c.run(repository, owner) {
                    asked.incrementAndGet()
                    val allowed = admitted.get()
                    if (!allowed) denials.incrementAndGet()
                    allowed
                }
            }
            assertEquals("${c.label}: positive premise, the exchange reached the last check once", 1, reached.get())
            assertEquals(c.label, 2, questionsAtHook.get())
            assertEquals(c.label, listOf(true), wire.map { it.useChecked })
            assertEquals("${c.label}: the original guard refused at the wire", 1, denials.get())
            assertEquals("${c.label}: no exchange was observed", emptyList<HttpExchangeEvidence>(), refused.exchanges)
            assertEquals("${c.label}: nothing reached the server", 0, server.requestCount)
            assertEquals("${c.label}: positive premise, it entered the client", 1, clientEntries.get())
        }
    }

    @Test fun `CU03 refusal before capture fetches no credential`() = runBlocking {
        for (c in calls) {
            fresh()
            server.enqueue(c.ok())
            val supplier = Supplier(first = 0)
            val refused = withheld(c.label) { c.run(repository, owner, supplier.check) }
            assertEquals("${c.label}: no exchange", emptyList<HttpExchangeEvidence>(), refused.exchanges)
            assertEquals("${c.label}: no send, one question", listOf(0, 1), listOf(server.requestCount, supplier.asked.get()))
            assertEquals("${c.label}: no token fetched", 0, source.fetches.get())
            assertEquals("${c.label}: never entered the client", 0, clientEntries.get())
        }
    }

    @Test fun `CU04 revocation during capture is observed before client entry`() = runBlocking {
        for (c in calls) {
            fresh()
            server.enqueue(c.ok())
            val gate = CompletableDeferred<Unit>().also { source.gate = it }
            val admitted = AtomicBoolean(true)
            val asked = AtomicInteger()
            val supplier: () -> Boolean = { asked.incrementAndGet(); admitted.get() }
            val call = async(Dispatchers.Default) { runCatching { c.run(repository, owner, supplier) } }
            withTimeout(5_000) { while (source.started.get() == 0) delay(5) }
            val beforeCaptureReturns = asked.get()
            admitted.set(false)
            gate.complete(Unit)
            val outcome = call.await()
            assertEquals("${c.label}: one question before the capture", 1, beforeCaptureReturns)
            val refused = outcome.exceptionOrNull() as? TopicUseWithheldException
            assertNotNull("${c.label}: withheld, not ${outcome.exceptionOrNull()}", refused)
            assertEquals("${c.label}: no exchange", emptyList<HttpExchangeEvidence>(), refused!!.exchanges)
            assertEquals("${c.label}: refused at the block entry", 2, asked.get())
            assertEquals("${c.label}: never entered the client", 0, clientEntries.get())
            assertEquals("${c.label}: nothing sent", 0, server.requestCount)
        }
    }

    @Test fun `CU05 omitted and null guards preserve the untagged request and result`() = runBlocking {
        for (c in calls) for (explicitNull in listOf(false, true)) {
            fresh()
            server.enqueue(c.ok())
            val result = if (explicitNull) c.run(repository, owner, null) else c.omitted(repository, owner)
            assertEquals(c.label, c.expected, result.fold({ it }, { throw it }))
            assertRequest(c)
            assertEquals("${c.label} null=$explicitNull: one unchecked send", listOf(Sent("Bearer credential", false)), wire.toList())
        }
    }

    @Test fun `CU06 allowed read replays once with the refreshed credential`() = runBlocking {
        for (c in reads) {
            fresh()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(c.ok())
            val result = c.run(repository, owner, Supplier().check)
            assertTrue("${c.label}: the replay succeeded", succeeded(result))
            assertEquals("${c.label}: two exchanges", 2, server.requestCount)
            assertEquals("${c.label}: the replay on the refreshed credential, both use-checked",
                listOf(Sent("Bearer credential", true), Sent("Bearer fresh", true)), wire.toList())
            assertEquals("${c.label}: one forced refresh", 1, source.forcedFetches.get())
        }
    }

    @Test fun `CU06 second 401 is final`() = runBlocking {
        for (c in reads) {
            fresh()
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Retry-After", "4"))
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Retry-After", "9"))
            server.enqueue(c.ok())
            val second = failureOf(c.run(repository, owner, Supplier().check))?.httpFailure
            assertEquals(c.label, 401 to "9", second?.statusCode to second?.retryAfter)
            assertEquals(c.label, 2, server.requestCount)
            assertEquals(c.label, 1, source.forcedFetches.get())
            assertEquals(c.label, listOf(Sent("Bearer credential", true), Sent("Bearer fresh", true)), wire.toList())
        }
    }

    @Test fun `CU06 revocation during refresh preserves the first 401`() = runBlocking {
        for (c in reads) {
            fresh()
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Retry-After", "5"))
            server.enqueue(c.ok())
            val held = CompletableDeferred<Unit>().also { source.forcedGate = it }
            val admitted = AtomicBoolean(true)
            val call = async(Dispatchers.Default) { runCatching { c.run(repository, owner) { admitted.get() } } }
            withTimeout(5_000) { source.forcedStarted.await() }
            assertEquals("${c.label} withdrawn: premise, the first send reached the server", 1, server.requestCount)
            admitted.set(false)
            held.complete(Unit)
            val refused = call.await().exceptionOrNull() as? TopicUseWithheldException
            assertEquals("${c.label} withdrawn: the first 401 is handed over", listOf(HttpExchangeEvidence(1, 1, 401, "5")), refused?.exchanges)
            assertEquals("${c.label} withdrawn: no replay sent", 1, server.requestCount)
        }
    }

    @Test fun `CU06 revocation immediately before replay preserves the first 401`() = runBlocking {
        for (c in reads) {
            fresh()
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Retry-After", "5"))
            server.enqueue(c.ok())
            val replayAllowed = AtomicBoolean(true)
            beforeUseCheck = { if (it == 2) replayAllowed.set(false) }
            val replayRefusal = withheld(c.label) { c.run(repository, owner) { replayAllowed.get() } }
            assertEquals(c.label, listOf(Sent("Bearer credential", true), Sent("Bearer fresh", true)), wire.toList())
            assertEquals(c.label, 1, source.forcedFetches.get())
            assertEquals(c.label, 1, server.requestCount)
            assertEquals(c.label, listOf(HttpExchangeEvidence(1, 1, 401, "5")), replayRefusal.exchanges)
        }
    }

    @Test fun `CU06 allowed 503 follow-up succeeds`() = runBlocking {
        for (c in reads) {
            fresh()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(c.ok())
            assertTrue("${c.label} 503 control: the follow-up succeeded", succeeded(c.run(repository, owner, Supplier().check)))
            assertEquals(c.label, 2, server.requestCount)
            assertEquals("${c.label} 503 control: two sends, both use-checked", listOf(true, true), wire.map { it.useChecked })
        }
    }

    @Test fun `CU06 revocation immediately before follow-up preserves the first 503`() = runBlocking {
        for (c in reads) {
            fresh()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(c.ok())
            val open = AtomicBoolean(true)
            beforeUseCheck = { exchange -> if (exchange == 2) open.set(false) }
            val stopped = withheld("${c.label} 503 withdrawn") { c.run(repository, owner) { open.get() } }
            assertEquals(c.label, listOf(true, true), wire.map { it.useChecked })
            assertEquals("${c.label} 503 withdrawn: the first answer only",
                listOf(HttpExchangeEvidence(1, 1, 503, "0")), stopped.exchanges)
            assertEquals("${c.label} 503 withdrawn: no follow-up sent", 1, server.requestCount)
        }
    }

    @Test fun `CU07 mutations preserve the first outcome and never replay`() = runBlocking {
        for (status in listOf(401, 408, 421, 503)) for (c in mutations) {
            fresh()
            val retryAfter = if (status == 401) "5" else "0"
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", retryAfter))
            server.enqueue(c.ok())
            val result = c.run(repository, owner, Supplier().check)
            val failure = failureOf(result)
            assertEquals("${c.label} $status: sends and forced refreshes", 1 to 0,
                server.requestCount to source.forcedFetches.get())
            assertEquals("${c.label} $status: an HTTP failure", AlertRepositoryFailureKind.HTTP, failure?.failureKind)
            assertEquals("${c.label} $status: the answer itself", status, failure?.httpFailure?.statusCode)
            assertEquals("${c.label} $status: original HTTP header", retryAfter, failure?.httpFailure?.retryAfter)
            assertEquals("${c.label} $status: its evidence", AlertRateLimitResponseEvidence(status, retryAfter), result.rateLimitResponseEvidence)
        }
        for (c in mutations) {
            fresh()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(c.ok())
            val result = c.run(repository, owner, Supplier().check)
            assertEquals("${c.label} dropped: a network failure", AlertRepositoryFailureKind.NETWORK, failureOf(result)?.failureKind)
            assertEquals("${c.label} dropped: one send, no reissue", 1, server.requestCount)
        }
    }

    @Test fun `CU08 evidence is readable before applying the result`() = runBlocking {
        val delete = mutations.single { it.method == "DELETE" }
        fresh()
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Retry-After", "3"))
        val empty = delete.run(repository, owner, Supplier().check)
        assertEquals("delete 204: its evidence", AlertRateLimitResponseEvidence(204, "3"), empty.rateLimitResponseEvidence)
        assertEquals("delete 204: Unit", Unit, empty.fold({ it }, { it }))
        for ((status, kind) in listOf(403 to AuthenticatedFailureKind.KNOWN_AUTHORIZATION,
                404 to AuthenticatedFailureKind.UNKNOWN_NOT_FOUND,
                500 to AuthenticatedFailureKind.OTHER_HTTP)) {
            fresh()
            val body = when (status) {
                403 -> """{"detail":"Premium subscription required"}"""
                404 -> """{"detail":"Comparison alert not found"}"""
                else -> ""
            }
            val answer = MockResponse().setResponseCode(status).setHeader("Retry-After", "9").setBody(body)
            server.enqueue(answer)
            val result = delete.run(repository, owner, Supplier().check)
            assertEquals("delete $status: its evidence", AlertRateLimitResponseEvidence(status, "9"), result.rateLimitResponseEvidence)
            val refused = failureOf(result)
            assertEquals("delete $status", AlertRepositoryFailureKind.HTTP, refused?.failureKind)
            assertEquals("delete $status: a typed HTTP failure", listOf<Any?>(status, kind), listOf(refused?.httpFailure?.statusCode, refused?.httpFailure?.kind))
        }
        for (c in mutations) {
            fresh()
            server.enqueue(c.ok().setHeader("Retry-After", "3"))
            val done = c.run(repository, owner, Supplier().check)
            assertEquals("${c.label}: a success's evidence", AlertRateLimitResponseEvidence(200, "3"), done.rateLimitResponseEvidence)
            if (c.method == "DELETE") assertEquals("delete 200: Unit", Unit, done.fold({ it }, { it }))
            else assertTrue("${c.label}: a success", succeeded(done))

            fresh()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
            val limited = c.run(repository, owner, Supplier().check)
            assertEquals("${c.label}: a 429's evidence", AlertRateLimitResponseEvidence(429, "7"), limited.rateLimitResponseEvidence)
            val failure = failureOf(limited)
            assertEquals("${c.label}: a typed HTTP failure", AlertRepositoryFailureKind.HTTP to 429,
                failure?.failureKind to failure?.httpFailure?.statusCode)
        }
        for (c in mutations.filter { it.method != "DELETE" }) {
            fresh()
            server.enqueue(ok("""{ "id": """).setHeader("Retry-After", "5"))
            val broken = c.run(repository, owner, Supplier().check)
            assertEquals("${c.label}: its evidence", AlertRateLimitResponseEvidence(200, "5"), broken.rateLimitResponseEvidence)
            val failure = failureOf(broken)
            assertEquals("${c.label}: an undecodable 2xx is a decode failure", AlertRepositoryFailureKind.DECODE, failure?.failureKind)
            assertNull("${c.label}: not an HTTP one", failure?.httpFailure)
        }
        for (c in reads) {
            fresh()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
            val limited = c.run(repository, owner, Supplier().check)
            assertEquals("${c.label}: a 429's evidence", AlertRateLimitResponseEvidence(429, "7"), limited.rateLimitResponseEvidence)
            val failure = failureOf(limited)
            assertEquals("${c.label}: a typed HTTP failure", AlertRepositoryFailureKind.HTTP to 429,
                failure?.failureKind to failure?.httpFailure?.statusCode)
            fresh()
            server.enqueue(c.ok().setHeader("Retry-After", "11"))
            assertNull("${c.label}: no evidence for a read's success", c.run(repository, owner, Supplier().check).rateLimitResponseEvidence)
        }
    }

    @Test fun `CU09 release OFF refuses repository and direct client calls`() = runBlocking {
        val snapshot = client.captureSnapshot(owner)
        val runs: List<Pair<String, suspend ((() -> Boolean)?) -> Unit>> = listOf(
            "settings GET" to { u ->
                if (u == null) client.getComparisonAlerts(snapshot, "usd")
                else client.getComparisonAlerts(snapshot, "usd", u) },
            "create" to { u ->
                if (u == null) client.createComparisonAlert(snapshot, create)
                else client.createComparisonAlert(snapshot, create, u) },
            "update" to { u ->
                if (u == null) client.updateComparisonAlert(snapshot, 103, toggle)
                else client.updateComparisonAlert(snapshot, 103, toggle, u) },
            "delete" to { u ->
                if (u == null) client.deleteComparisonAlert(snapshot, 103)
                else client.deleteComparisonAlert(snapshot, 103, u) },
            "history GET" to { u ->
                if (u == null) client.getComparisonNotificationLogs(snapshot, "usd", "absolute", 50)
                else client.getComparisonNotificationLogs(snapshot, "usd", "absolute", 50, u) })
        release.set(false)
        try {
            val beforeFence = source.fetches.get()
            assertNull("OFF: no new owner fence", repository.captureOwnerOrNull())
            assertEquals("OFF: acquiring a fence fetches no token", beforeFence, source.fetches.get())
            assertEquals("OFF: acquiring a fence sends nothing", 0, server.requestCount)
            for ((label, run) in runs) for (use in listOf<(() -> Boolean)?>(null, { true })) {
                server.enqueue(ok("{}"))
                val before = server.requestCount
                val refused = runCatching { run(use) }.exceptionOrNull()
                assertTrue("$label: refused by the release gate, not $refused",
                    refused is java.io.IOException && refused.message == "D24 release admission is OFF")
                assertEquals("$label: nothing sent", before, server.requestCount)
            }
            release.set(true)
            fresh()
            release.set(false)
            assertEquals("the repository starts with a fence and no fetched token", 0, source.fetches.get())
            for (c in calls) for (use in listOf<(() -> Boolean)?>(null, { true })) {
                val fetches = source.fetches.get()
                val before = server.requestCount
                val refused = runCatching {
                    if (use == null) c.omitted(repository, owner) else c.run(repository, owner, use)
                }.exceptionOrNull()
                assertTrue("repository ${c.label}: refused by the release gate, got $refused",
                    refused is java.io.IOException && refused.message == "D24 release admission is OFF")
                assertEquals("repository ${c.label}: no token fetched", fetches, source.fetches.get())
                assertEquals("repository ${c.label}: nothing sent", before, server.requestCount)
            }
        } finally {
            release.set(true)
        }
    }

    /** Counts the calls to each consumer independently, including when the consumer should be skipped. */
    private fun assertCallbacks(
        result: AuthBoundResult<*>,
        success: Boolean,
        asked: AtomicInteger,
        questionsPerApply: Int
    ) {
        var successes = 0
        var failures = 0
        val before = asked.get()
        result.fold({ successes++ }, { failures++ })
        assertEquals(questionsPerApply, asked.get() - before)
        assertEquals(if (success) 1 to 0 else 0 to 1, successes to failures)
        var actions = 0
        val beforeAction = asked.get()
        result.onFailure { actions++ }
        assertEquals(questionsPerApply, asked.get() - beforeAction)
        assertEquals(if (success) 0 else 1, actions)
    }

    private fun assertRejected(result: AuthBoundResult<*>, asked: AtomicInteger, identityChanged: Boolean = false) {
        var callbacks = 0
        val applications: List<() -> Unit> = listOf(
            { result.fold({ callbacks++ }, { callbacks++ }) },
            { result.onFailure { callbacks++ } }
        )
        for (apply in applications) {
            val before = asked.get()
            val error = runCatching { apply() }.exceptionOrNull()
            if (identityChanged) {
                assertTrue("identity must win over a refused use, got $error", error is AuthIdentityChangedException)
                assertEquals(before, asked.get())
            } else {
                assertTrue("expected use refusal, got $error", error is TopicUseWithheldException)
                assertEquals(emptyList<HttpExchangeEvidence>(), (error as TopicUseWithheldException).exchanges)
                assertEquals(before + 1, asked.get())
            }
            assertEquals(0, callbacks)
        }
    }

    @Test fun `CU10 each application checks the original identity then the original use`() = runBlocking {
        for (c in calls) for (success in listOf(true, false)) {
            fresh()
            fun answer() = if (success) c.ok() else MockResponse().setResponseCode(429)
            server.enqueue(answer())
            val grant = AtomicInteger(1)
            val oldQuestions = AtomicInteger()
            val old = c.run(repository, owner) { oldQuestions.incrementAndGet(); grant.get() == 1 }
            assertRequest(c)
            assertCallbacks(old, success, oldQuestions, 1)
            grant.set(0)
            assertRejected(old, oldQuestions)
            // A replacement grant is live for the same identity. The old answer still belongs to the first grant.
            grant.set(2)
            val replacementQuestions = AtomicInteger()
            val replacementUse = { replacementQuestions.incrementAndGet(); grant.get() == 2 }
            assertRejected(old, oldQuestions)
            assertEquals("old call plus reapplications in the same generation", 1, server.requestCount)
            server.enqueue(answer())
            val replacement = c.run(repository, owner, replacementUse)
            assertCallbacks(replacement, success, replacementQuestions, 1)
            assertEquals("one explicit call using the replacement grant", 2, server.requestCount)

            source.identity = AuthIdentity("user-b", 2)
            source.identity = AuthIdentity("user-a", 3)
            assertRejected(old, oldQuestions, identityChanged = true)
            assertEquals("stale application sends nothing", 2, server.requestCount)

            server.enqueue(answer())
            val newOwner = checkNotNull(repository.captureOwnerOrNull())
            grant.set(3)
            val newQuestions = AtomicInteger()
            val current = c.run(repository, newOwner) {
                newQuestions.incrementAndGet()
                grant.get() == 3
            }
            assertCallbacks(current, success, newQuestions, 1)
            assertEquals("one explicit call in the new generation", 3, server.requestCount)
        }
    }

    @Test fun `CU10 credential capture failure retains its application guard`() = runBlocking {
        for (c in calls) {
            fresh()
            source.unavailable = true
            val open = AtomicBoolean(true)
            val asked = AtomicInteger()
            val failed = c.run(repository, owner) { asked.incrementAndGet(); open.get() }
            assertEquals(c.label, 1, source.fetches.get())
            assertEquals(c.label, 0, server.requestCount)
            val before = asked.get()
            assertEquals(c.label, AlertRepositoryFailureKind.AUTH_UNAVAILABLE, failureOf(failed)?.failureKind)
            assertEquals(c.label, before + 1, asked.get())
            open.set(false)
            assertRejected(failed, asked)
        }
    }

    @Test fun `CU10 bank and source send guards do not govern application`() = runBlocking {
        val controls: List<Pair<String, suspend (() -> Boolean) -> AuthBoundResult<*>>> = listOf(
            """{"settings":[],"total_count":0}""" to { use -> repository.getSettings(owner, use) },
            """{"settings":[],"total_count":0}""" to { use -> repository.getSourceSettings(owner, useAdmitted = use) },
            """{"logs":[],"total_count":0}""" to { use -> repository.getHistory(owner, useAdmitted = use) },
            """{"logs":[],"total_count":0}""" to { use -> repository.getSourceHistory(owner, useAdmitted = use) },
            "" to { use -> repository.deleteSetting(owner, 1, use) },
            "" to { use -> repository.deleteSourceSetting(owner, 1, use) }
        )
        for ((body, call) in controls) for (success in listOf(true, false)) {
            fresh()
            val open = AtomicBoolean(true)
            val asked = AtomicInteger()
            server.enqueue(if (success) ok(body) else MockResponse().setResponseCode(429))
            val result = call { asked.incrementAndGet(); open.get() }
            assertTrue(asked.get() >= 3)
            assertEquals(listOf(true), wire.map { it.useChecked })
            open.set(false)
            assertCallbacks(result, success, asked, 0)
        }
    }
}
