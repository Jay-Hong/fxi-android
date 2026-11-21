package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseTag
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.SourceAlertSettingRequest
import com.jay.fxi.data.remote.dto.SourceAlertSettingUpdateRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AlertCondition
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
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

/**
 * S8-2 wire contract (`R4c/S8/s8_2_contract` agreed, design `s8_2_3_design.agreed.r1.md` §1, §5): the five source alert calls —
 * the settings GET, create, update, delete and the history GET — through the production client chain (application auth
 * interceptor, one-shot body, then the network use check, as NetworkModule orders them), against a real HTTP server.
 *
 * The transport's own rules (the read's single 401 replay, a mutation's single send, no follow-up to 408, 421 or 503) are the
 * bank wire tests' (`S7E5A2MutationWireContractTest`, `AlertSettingsWireContractTest`) on the same transport. What this file
 * fixes is that each source call is wired into it as the bank calls are: a given use check is asked before the credential
 * capture, again at the block entry and on every send; a read is a read and a mutation a mutation; the answer's evidence reaches
 * the result. Omitted or null, a call is the unchecked path it was. No production caller passes a use check yet (S8-3, S8-4 and
 * S8-5 connect them); the rate-limit floor and stale adoption are the owners' and are tested with them.
 */
class SourceAlertUseWireContractTest {
    private lateinit var server: MockWebServer
    private lateinit var source: TokenSource
    private lateinit var provider: AuthTokenProvider
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
        override fun currentIdentity() = AuthIdentity("user-a", 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetches.incrementAndGet()
            started.incrementAndGet()
            gate?.await()
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
        server = MockWebServer().apply { start() }
        fresh()
    }

    @After fun tearDown() = server.shutdown()

    /** A new server, token source and chain: one case's answers and counts stay out of the next. */
    private fun fresh() {
        server.shutdown()
        server = MockWebServer().apply { start() }
        source = TokenSource()
        provider = AuthTokenProvider(source, orders = AccessOrderSequence())
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

    private val row = """{"id":11,"user_id":"user-a","source":"upbit","asset":"usdt-krw","condition":"above","threshold":1400.5,""" +
        """"is_enabled":true,"triggered":false,"repeat_interval_sec":null,"created_at":"2026-09-01T09:00:00+09:00",""" +
        """"updated_at":"2026-09-02T09:00:00+09:00","triggered_at":null}"""
    private val log = """{"id":7,"setting_id":11,"source":"upbit","asset":"usdt-krw","condition":"above","threshold":1400.5,""" +
        """"triggered_rate":1401.2,"sent_at":"2026-09-03T10:00:00+09:00"}"""
    private val create = SourceAlertSettingRequest("upbit", "usdt-krw", AlertCondition.ABOVE, 1400.5, true)
    private val toggle = SourceAlertSettingUpdateRequest(isEnabled = false)

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /** A use check that answers [first] times admitted, then refused; counts every question. */
    private class Supplier(private val first: Int = Int.MAX_VALUE) {
        val asked = AtomicInteger()
        val check: () -> Boolean = { asked.incrementAndGet() <= first }
    }

    /**
     * One source call: what it sends ([method], [path] with its query, [body]), a success answer, and the call through the
     * repository with a use check ([run]) or without the argument at all ([omitted]).
     */
    private class Call(
        val label: String, val read: Boolean, val method: String, val path: String, val body: String?, val ok: () -> MockResponse,
        val run: suspend (AlertRepository, AuthIdentityFence, (() -> Boolean)?) -> AuthBoundResult<*>,
        val omitted: suspend (AlertRepository, AuthIdentityFence) -> AuthBoundResult<*>
    )

    private val calls: List<Call> get() = listOf(
        Call("settings GET", true, "GET", "/api/source-notification-settings?asset=usdt-krw", null,
            { ok("""{"settings":[$row],"total_count":1}""") },
            { r, o, u -> r.getSourceSettings(o, "usdt-krw", u) }, { r, o -> r.getSourceSettings(o, "usdt-krw") }),
        Call("create", false, "POST", "/api/source-notification-settings",
            """{"source":"upbit","asset":"usdt-krw","condition":"above","threshold":1400.5,"is_enabled":true}""", { ok(row) },
            { r, o, u -> r.createSourceSetting(o, create, u) }, { r, o -> r.createSourceSetting(o, create) }),
        Call("update", false, "PUT", "/api/source-notification-settings/11", """{"is_enabled":false}""", { ok(row) },
            { r, o, u -> r.updateSourceSetting(o, 11, toggle, u) }, { r, o -> r.updateSourceSetting(o, 11, toggle) }),
        Call("delete", false, "DELETE", "/api/source-notification-settings/11", null,
            { ok("arbitrary non-JSON delete response") },
            { r, o, u -> r.deleteSourceSetting(o, 11, u) }, { r, o -> r.deleteSourceSetting(o, 11) }),
        Call("history GET", true, "GET", "/api/source-notification-logs?asset=usdt-krw&limit=50", null,
            { ok("""{"logs":[$log],"total_count":1}""") },
            { r, o, u -> r.getSourceHistory(o, "usdt-krw", 50, u) }, { r, o -> r.getSourceHistory(o, "usdt-krw", 50) }))

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

    @Test fun `S8_2_U01 an admitted use sends each source call once as it was given and the wire asks after the repository's two checks`() = runBlocking {
        for (c in calls) {
            fresh()
            server.enqueue(c.ok())
            val supplier = Supplier()
            val result = c.run(repository, owner, supplier.check)
            assertTrue("${c.label}: success", succeeded(result))
            assertEquals("${c.label}: one send", 1, server.requestCount)
            val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("${c.label}: the request", listOf(c.method, c.path), listOf(sent.method, sent.path))
            assertEquals("${c.label}: the body", c.body.orEmpty(), sent.body.readUtf8())
            assertTrue("${c.label}: the repository's two checks and the wire's, at least", supplier.asked.get() >= 3)
            assertEquals("${c.label}: the use check is on the wire", listOf(Sent("Bearer credential", true)), wire.toList())
        }
    }

    @Test fun `S8_2_U02 a use revoked right before the last network check sends nothing and is a withheld refusal`() = runBlocking {
        for (c in calls) {
            fresh()
            server.enqueue(c.ok())
            val admitted = AtomicBoolean(true)
            val reached = AtomicInteger()
            val denials = AtomicInteger()
            beforeUseCheck = { exchange -> if (exchange == 1) { reached.incrementAndGet(); admitted.set(false) } }
            val refused = withheld(c.label) {
                c.run(repository, owner) {
                    val allowed = admitted.get()
                    if (!allowed) denials.incrementAndGet()
                    allowed
                }
            }
            assertEquals("${c.label}: positive premise, the exchange reached the last check once", 1, reached.get())
            assertEquals("${c.label}: the original guard refused at the wire", 1, denials.get())
            assertEquals("${c.label}: no exchange was observed", emptyList<HttpExchangeEvidence>(), refused.exchanges)
            assertEquals("${c.label}: nothing reached the server", 0, server.requestCount)
            assertEquals("${c.label}: positive premise, it entered the client", 1, clientEntries.get())
        }
    }

    @Test fun `S8_2_U03 a use refused before the credential capture fetches no token sends nothing and asks once`() = runBlocking {
        for (c in calls) {
            fresh()
            val supplier = Supplier(first = 0)
            val refused = withheld(c.label) { c.run(repository, owner, supplier.check) }
            assertEquals("${c.label}: no exchange", emptyList<HttpExchangeEvidence>(), refused.exchanges)
            assertEquals("${c.label}: no send, one question", listOf(0, 1), listOf(server.requestCount, supplier.asked.get()))
            assertEquals("${c.label}: no token fetched", 0, source.fetches.get())
            assertEquals("${c.label}: never entered the client", 0, clientEntries.get())
        }
    }

    @Test fun `S8_2_U04 a use withdrawn while the credential is fetched is refused at the block entry before the client`() = runBlocking {
        for (c in calls) {
            fresh()
            val gate = CompletableDeferred<Unit>().also { source.gate = it }
            val admitted = AtomicBoolean(true)
            val asked = AtomicInteger()
            val supplier: () -> Boolean = { asked.incrementAndGet(); admitted.get() }
            val call = async(Dispatchers.Default) { runCatching { c.run(repository, owner, supplier) } }
            withTimeout(5_000) { while (source.started.get() == 0) delay(5) }
            assertEquals("${c.label}: one question before the capture", 1, asked.get())
            admitted.set(false)
            gate.complete(Unit)
            val outcome = call.await()
            val refused = outcome.exceptionOrNull() as? TopicUseWithheldException
            assertNotNull("${c.label}: withheld, not ${outcome.exceptionOrNull()}", refused)
            assertEquals("${c.label}: no exchange", emptyList<HttpExchangeEvidence>(), refused!!.exchanges)
            assertEquals("${c.label}: refused at the block entry", 2, asked.get())
            assertEquals("${c.label}: never entered the client", 0, clientEntries.get())
            assertEquals("${c.label}: nothing sent", 0, server.requestCount)
        }
    }

    @Test fun `S8_2_U05 an omitted or null use is the unchecked path one send and no use check on the wire`() = runBlocking {
        for (c in calls) for (explicitNull in listOf(false, true)) {
            fresh()
            server.enqueue(c.ok())
            val result = if (explicitNull) c.run(repository, owner, null) else c.omitted(repository, owner)
            assertTrue("${c.label} null=$explicitNull: success", succeeded(result))
            assertEquals("${c.label} null=$explicitNull: one unchecked send", listOf(Sent("Bearer credential", false)), wire.toList())
            assertEquals("${c.label} null=$explicitNull: one server request", 1, server.requestCount)
        }
        fresh()
        val snapshot = client.captureSnapshot(owner)
        val direct: List<Pair<String, suspend () -> Unit>> = listOf(
            "client settings GET" to { client.getSourceNotificationSettings(snapshot, "usdt-krw") },
            "client create" to { client.createSourceNotificationSetting(snapshot, create) },
            "client update" to { client.updateSourceNotificationSetting(snapshot, 11, toggle) },
            "client delete" to { client.deleteSourceNotificationSetting(snapshot, 11) },
            "client history GET" to { client.getSourceNotificationLogs(snapshot, "usdt-krw", 50) })
        for ((index, entry) in direct.withIndex()) {
            server.enqueue(calls[index].ok())
            wire.clear()
            entry.second()
            assertEquals("${entry.first} omitted: no use check on the wire", listOf(false), wire.map { it.useChecked })
        }
    }

    @Test fun `S8_2_U06 a read's 401 is replayed once with the use check and a use withdrawn before the replay sends no replay`() = runBlocking {
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

            // The client's own follow-up to 503 with Retry-After 0 carries the use check as well: control, then withdrawn.
            fresh()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(c.ok())
            assertTrue("${c.label} 503 control: the follow-up succeeded", succeeded(c.run(repository, owner, Supplier().check)))
            assertEquals("${c.label} 503 control: two sends, both use-checked", listOf(true, true), wire.map { it.useChecked })
            fresh()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(c.ok())
            val open = AtomicBoolean(true)
            beforeUseCheck = { exchange -> if (exchange == 2) open.set(false) }
            val stopped = withheld("${c.label} 503 withdrawn") { c.run(repository, owner) { open.get() } }
            assertEquals("${c.label} 503 withdrawn: the first answer only",
                listOf(HttpExchangeEvidence(1, 1, 503, "0")), stopped.exchanges)
            assertEquals("${c.label} 503 withdrawn: no follow-up sent", 1, server.requestCount)
        }
    }

    @Test fun `S8_2_U07 a mutation's 401 408 421 and 503 are answered as they came with no refresh and no second send`() = runBlocking {
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

    @Test fun `S8_2_U08 status and Retry-After reach the result for a mutation's success HTTP failure and undecodable 2xx and a read's HTTP failure`() = runBlocking {
        val delete = mutations.single { it.method == "DELETE" }
        fresh()
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Retry-After", "3"))
        val empty = delete.run(repository, owner, Supplier().check)
        assertEquals("delete 204: its evidence", AlertRateLimitResponseEvidence(204, "3"), empty.rateLimitResponseEvidence)
        assertEquals("delete 204: Unit", Unit, empty.fold({ it }, { it }))
        for ((status, kind) in listOf(403 to com.jay.fxi.data.remote.AuthenticatedFailureKind.KNOWN_AUTHORIZATION,
                500 to com.jay.fxi.data.remote.AuthenticatedFailureKind.OTHER_HTTP)) {
            fresh()
            val answer = MockResponse().setResponseCode(status).setHeader("Retry-After", "9")
                .setBody(if (status == 403) """{"detail":"Premium subscription required"}""" else "")
            server.enqueue(answer)
            val result = delete.run(repository, owner, Supplier().check)
            assertEquals("delete $status: its evidence", AlertRateLimitResponseEvidence(status, "9"), result.rateLimitResponseEvidence)
            val refused = failureOf(result)
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

    @Test fun `S8_2_U09 the release gate refuses every source call before any send with or without a use check`() = runBlocking {
        val snapshot = client.captureSnapshot(owner)
        val runs: List<Pair<String, suspend ((() -> Boolean)?) -> Unit>> = listOf(
            "settings GET" to { u ->
                if (u == null) client.getSourceNotificationSettings(snapshot, "usdt-krw")
                else client.getSourceNotificationSettings(snapshot, "usdt-krw", u) },
            "create" to { u ->
                if (u == null) client.createSourceNotificationSetting(snapshot, create)
                else client.createSourceNotificationSetting(snapshot, create, u) },
            "update" to { u ->
                if (u == null) client.updateSourceNotificationSetting(snapshot, 11, toggle)
                else client.updateSourceNotificationSetting(snapshot, 11, toggle, u) },
            "delete" to { u ->
                if (u == null) client.deleteSourceNotificationSetting(snapshot, 11)
                else client.deleteSourceNotificationSetting(snapshot, 11, u) },
            "history GET" to { u ->
                if (u == null) client.getSourceNotificationLogs(snapshot, "usdt-krw", 50)
                else client.getSourceNotificationLogs(snapshot, "usdt-krw", 50, u) })
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
}
