package com.jay.fxi.data.repository

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.TopicUseWithheldException
import com.jay.fxi.data.remote.dto.AlertSettingRequest
import com.jay.fxi.data.remote.dto.AlertSettingUpdateRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AlertCondition
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.HttpExchangeEvidence
import com.jay.fxi.data.remote.TopicUseTag
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
 * Claude-owned S7 E5a2 wire contract (R4c/S7/s7e5 scope confirmation r1, the G1 verification duties; API r3 §1–§3): the four bank
 * mutations through the production client chain (application auth interceptor, one-shot body, then the network use check, as
 * NetworkModule orders them), against a real HTTP server.
 * - With a use check: exactly one send when admitted; a use revoked between the repository's checks and the wire sends nothing and
 *   surfaces as [TopicUseWithheldException], which proves the tag survives every request rebuild down to the network interceptor.
 * - Without one (omitted or null): one send, the supplier never asked — the old path.
 * - 401 is answered as it came: no refresh, no second send. 408, 421 and 503 with `Retry-After: 0` get no follow-up send.
 * - The partial toggle body is `is_enabled` alone. Status and Retry-After of success, HTTP failure and an undecodable 2xx reach the
 *   result for the owner's floor.
 * The implementation thread reads but does not edit this file.
 */
class S7E5A2MutationWireContractTest {
    private lateinit var server: MockWebServer
    private lateinit var source: CountingTokenSource
    private lateinit var provider: AuthTokenProvider
    private lateinit var repository: AlertRepository
    private lateinit var owner: AuthIdentityFence

    private class CountingTokenSource : AuthTokenSource {
        val fetches = AtomicInteger()
        val forcedFetches = AtomicInteger()
        /** When set, every token fetch waits for it (counted in [started] first). */
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val started = AtomicInteger()
        @Volatile var uid = "user-a"
        override fun currentIdentity() = AuthIdentity(uid, 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            fetches.incrementAndGet()
            if (forceRefresh) forcedFetches.incrementAndGet()
            started.incrementAndGet()
            gate?.await()
            return if (forceRefresh) "fresh" else "credential"
        }
    }

    private lateinit var client: AuthenticatedApiClient
    /** Requests that reached the client's chain at all (the first application interceptor). */
    private val clientEntries = AtomicInteger()
    /** For each request at the network stage, whether it carried a use check. */
    private val tagged = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    private val release = AtomicBoolean(true)
    /** When true, the identity moves right after a response arrives (an application interceptor). */
    @Volatile private var switchAfterResponse = false

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        source = CountingTokenSource()
        provider = AuthTokenProvider(source, orders = AccessOrderSequence())
        setUpAgainstCurrentServer()
        owner = checkNotNull(runBlocking { repository.captureOwnerOrNull() })
    }

    @After fun tearDown() = server.shutdown()

    private val row = """{"id":7,"user_id":"user-a","bank":"hana","currency":"usd-krw","condition":"above","threshold":1400.5,""" +
        """"is_enabled":false,"triggered":false,"created_at":"2026-11-12T01:00:00Z","updated_at":"2026-11-12T02:00:00Z",""" +
        """"triggered_at":null,"repeat_interval_sec":300}"""
    private val create = AlertSettingRequest("hana", "usd-krw", AlertCondition.ABOVE, 1400.5, true)

    /** A use check that answers [first] times admitted, then refused; counts every question. */
    private class Supplier(private val first: Int = Int.MAX_VALUE) {
        val asked = AtomicInteger()
        val check: () -> Boolean = { asked.incrementAndGet() <= first }
    }

    private class Mutation(val label: String, val method: String, val path: String, val ok: () -> MockResponse,
        val run: suspend (AlertRepository, AuthIdentityFence, (() -> Boolean)?) -> AuthBoundResult<*>)

    private val mutations: List<Mutation> get() = listOf(
        Mutation("create", "POST", "/api/notification-settings", { MockResponse().setResponseCode(200).setBody(row) }) { r, o, u -> r.createSetting(o, create, u) },
        Mutation("full update", "PUT", "/api/notification-settings/7", { MockResponse().setResponseCode(200).setBody(row) }) { r, o, u -> r.updateSetting(o, 7, create, u) },
        Mutation("toggle", "PUT", "/api/notification-settings/7", { MockResponse().setResponseCode(200).setBody(row) }) { r, o, u ->
            r.updateSettingPartial(o, 7, AlertSettingUpdateRequest(isEnabled = false), u) },
        Mutation("delete", "DELETE", "/api/notification-settings/7", { MockResponse().setResponseCode(204) }) { r, o, u -> r.deleteSetting(o, 7, u) })

    private fun drain() {
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
    }

    @Test fun `S7E5A2_W01 an admitted use sends each mutation once and the network interceptor asks after the repository's two checks`() = runBlocking {
        for (m in mutations) {
            val before = server.requestCount
            server.enqueue(m.ok())
            val supplier = Supplier()
            val result = m.run(repository, owner, supplier.check)
            assertTrue("${m.label}: success", result.fold({ true }, { false }))
            assertEquals("${m.label}: one send", 1, server.requestCount - before)
            val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("${m.label}: the request", listOf(m.method, m.path), listOf(sent.method, sent.path))
            assertTrue("${m.label}: the repository's two checks and the wire's, at least", supplier.asked.get() >= 3)
        }
    }

    @Test fun `S7E5A2_W02 a use revoked after the repository's checks sends nothing for every method and is a withheld refusal`() = runBlocking {
        for (m in mutations) {
            val before = server.requestCount
            server.enqueue(m.ok())
            val supplier = Supplier(first = 2)
            try {
                m.run(repository, owner, supplier.check)
                fail("${m.label}: a revoked use must not return a result")
            } catch (withheld: TopicUseWithheldException) {
                assertEquals("${m.label}: no exchange was observed", emptyList<Any>(), withheld.exchanges)
            }
            assertEquals("${m.label}: nothing reached the server", 0, server.requestCount - before)
            assertTrue("${m.label}: the wire asked after the repository's two checks", supplier.asked.get() >= 3)
            // A fresh server: the unused answer must not serve the next case.
            server.shutdown()
            server = MockWebServer().apply { start() }
            setUpAgainstCurrentServer()
        }
    }

    @Test fun `S7E5A2_W03 a use refused before the credential capture sends nothing and asks only once`() = runBlocking {
        for (m in mutations) {
            val before = server.requestCount
            val fetchesBefore = source.fetches.get()
            val supplier = Supplier(first = 0)
            try {
                m.run(repository, owner, supplier.check)
                fail("${m.label}: refused")
            } catch (_: TopicUseWithheldException) {
            }
            assertEquals(listOf(0, 1), listOf(server.requestCount - before, supplier.asked.get()))
            assertEquals("${m.label}: refused before any credential is captured", fetchesBefore, source.fetches.get())
        }
    }

    @Test fun `S7E5A2_W04 omitting the use check or passing null is the old path one send and no supplier`() = runBlocking {
        for (m in mutations) for (explicitNull in listOf(false, true)) {
            val before = server.requestCount
            server.enqueue(m.ok())
            val result = if (explicitNull) m.run(repository, owner, null) else when (m.label) {
                "create" -> repository.createSetting(owner, create)
                "full update" -> repository.updateSetting(owner, 7, create)
                "toggle" -> repository.updateSettingPartial(owner, 7, AlertSettingUpdateRequest(isEnabled = false))
                else -> repository.deleteSetting(owner, 7)
            }
            assertTrue("${m.label}: success", result.fold({ true }, { false }))
            assertEquals("${m.label}: one send", 1, server.requestCount - before)
            drain()
        }
    }

    @Test fun `S7E5A2_W05 a 401 is answered as it came with no refresh and no second send`() = runBlocking {
        for (m in mutations) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(m.ok())
            val result = m.run(repository, owner, Supplier().check)
            val failure = result.fold({ null }, { it }) as? AlertRepositoryException
            assertEquals("${m.label}: the 401 itself", 401, failure?.httpFailure?.statusCode)
            assertEquals("${m.label}: one send", 1, server.requestCount - before)
            assertEquals("${m.label}: no forced refresh", 0, source.forcedFetches.get())
            assertEquals("${m.label}: the evidence", AlertRateLimitResponseEvidence(401, null), result.rateLimitResponseEvidence)
            drain()
            server.shutdown()
            server = MockWebServer().apply { start() }
            setUpAgainstCurrentServer()
        }
    }

    @Test fun `S7E5A2_W06 408 421 and 503 with Retry-After 0 get no follow-up send`() = runBlocking {
        for (status in listOf(408, 421, 503)) for (m in mutations) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
            server.enqueue(m.ok())
            val result = try {
                m.run(repository, owner, Supplier().check)
            } catch (e: Exception) {
                null
            }
            assertEquals("${m.label} $status: one send", 1, server.requestCount - before)
            result?.let { assertEquals("${m.label} $status: its evidence", AlertRateLimitResponseEvidence(status, "0"), it.rateLimitResponseEvidence) }
            server.shutdown()
            server = MockWebServer().apply { start() }
            setUpAgainstCurrentServer()
        }
    }

    @Test fun `S7E5A2_W07 the toggle sends is_enabled alone`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(row))
        repository.updateSettingPartial(owner, 7, AlertSettingUpdateRequest(isEnabled = true), Supplier().check)
        assertEquals("""{"is_enabled":true}""", server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
    }

    @Test fun `S7E5A2_W08 status and Retry-After reach the result for a success an HTTP failure and an undecodable 2xx`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Retry-After", "3").setBody(row))
        val ok = repository.updateSettingPartial(owner, 7, AlertSettingUpdateRequest(isEnabled = false), Supplier().check)
        assertEquals(AlertRateLimitResponseEvidence(200, "3"), ok.rateLimitResponseEvidence)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        val limited = repository.deleteSetting(owner, 7, Supplier().check)
        assertEquals(AlertRateLimitResponseEvidence(429, "7"), limited.rateLimitResponseEvidence)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Retry-After", "5").setBody("""{ "id": """))
        val broken = repository.updateSettingPartial(owner, 7, AlertSettingUpdateRequest(isEnabled = false), Supplier().check)
        val failure = broken.fold({ null }, { it }) as? AlertRepositoryException
        assertEquals("an undecodable 2xx is a decode failure, not an HTTP one", AlertRepositoryFailureKind.DECODE, failure?.failureKind)
        assertNull(failure?.httpFailure)
        assertEquals(AlertRateLimitResponseEvidence(200, "5"), broken.rateLimitResponseEvidence)
    }

    // ---- gap hunt r1 (G01-G04, G14, G25) ----

    @Test fun `S7E5A2_W09 a use withdrawn while the credential is fetched is refused at the block entry before the client`() = runBlocking {
        for (m in mutations) {
            source = CountingTokenSource()
            provider = AuthTokenProvider(source, orders = AccessOrderSequence())
            setUpAgainstCurrentServer()
            owner = checkNotNull(repository.captureOwnerOrNull())
            val entriesBefore = clientEntries.get()
            val before = server.requestCount
            val startedBefore = source.started.get()
            val gate = CompletableDeferred<Unit>().also { source.gate = it }
            val admitted = AtomicBoolean(true)
            val asked = AtomicInteger()
            val supplier: () -> Boolean = { asked.incrementAndGet(); admitted.get() }
            val call = async(Dispatchers.Default) { runCatching { m.run(repository, owner, supplier) } }
            withTimeout(5_000) { while (source.started.get() == startedBefore) delay(5) }
            assertEquals("${m.label}: one question before the capture", 1, asked.get())
            admitted.set(false)
            gate.complete(Unit)
            val outcome = call.await()
            assertTrue("${m.label}: withheld, not ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is TopicUseWithheldException)
            assertEquals("${m.label}: refused at the block entry", 2, asked.get())
            assertEquals("${m.label}: never entered the client", entriesBefore, clientEntries.get())
            assertEquals("${m.label}: nothing sent", before, server.requestCount)
        }
    }

    @Test fun `S7E5A2_W10 each mutation sends exactly the request it was given`() = runBlocking {
        val json = NetworkModule.provideWireJson()
        val full = AlertSettingRequest("hana", "usd-krw", AlertCondition.BELOW, 1500.25, false, repeatIntervalSec = 300)
        for (use in listOf<(() -> Boolean)?>(null, Supplier().check)) {
            for (enabled in listOf(true, false)) {
                server.enqueue(MockResponse().setResponseCode(200).setBody(row))
                repository.updateSettingPartial(owner, 7, AlertSettingUpdateRequest(isEnabled = enabled), use)
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("""{"is_enabled":$enabled}""", sent.body.readUtf8())
                assertEquals(listOf("PUT", "/api/notification-settings/7", "Bearer credential"),
                    listOf(sent.method, sent.path, sent.getHeader("Authorization")))
            }
            server.enqueue(MockResponse().setResponseCode(200).setBody(row))
            repository.updateSetting(owner, 7, full, use)
            val put = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(json.encodeToString(AlertSettingRequest.serializer(), full), put.body.readUtf8())
            assertEquals(listOf("PUT", "/api/notification-settings/7", "Bearer credential"), listOf(put.method, put.path, put.getHeader("Authorization")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(row))
            repository.createSetting(owner, full, use)
            val post = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(json.encodeToString(AlertSettingRequest.serializer(), full), post.body.readUtf8())
            assertEquals(listOf("POST", "/api/notification-settings", "Bearer credential"), listOf(post.method, post.path, post.getHeader("Authorization")))
        }
    }

    @Test fun `S7E5A2_W11 an omitted or null use leaves every request untagged bank and non-bank`() = runBlocking {
        val snapshot = client.captureSnapshot(owner)
        val ok = { MockResponse().setResponseCode(200).setBody(row) }
        val none = { MockResponse().setResponseCode(204) }
        val calls: List<Triple<String, () -> MockResponse, suspend () -> Unit>> = listOf(
            Triple("repository create omitted", ok) { repository.createSetting(owner, create) },
            Triple("repository full omitted", ok) { repository.updateSetting(owner, 7, create) },
            Triple("repository partial omitted", ok) { repository.updateSettingPartial(owner, 7, AlertSettingUpdateRequest(isEnabled = false)) },
            Triple("repository delete omitted", none) { repository.deleteSetting(owner, 7) },
            Triple("repository full null", ok) { repository.updateSetting(owner, 7, create, null) },
            Triple("repository partial null", ok) { repository.updateSettingPartial(owner, 7, AlertSettingUpdateRequest(isEnabled = false), null) },
            Triple("repository delete null", none) { repository.deleteSetting(owner, 7, null) },
            Triple("client create omitted", ok) { client.createNotificationSetting(snapshot, create) },
            Triple("client full omitted", ok) { client.updateNotificationSetting(snapshot, 7, create) },
            Triple("client partial omitted", ok) { client.updateNotificationSettingPartial(snapshot, 7, AlertSettingUpdateRequest(isEnabled = false)) },
            Triple("client delete omitted", none) { client.deleteNotificationSetting(snapshot, 7) },
            Triple("client delete user", none) { client.deleteUser(snapshot) },
            Triple("client delete comparison", none) { client.deleteComparisonAlert(snapshot, 7) },
            Triple("client delete source", none) { client.deleteSourceNotificationSetting(snapshot, 7) },
            Triple("client unregister device", none) { client.unregisterDevice(snapshot, "device-token") })
        for ((label, response, call) in calls) {
            tagged.clear()
            server.enqueue(response())
            call()
            assertEquals("$label: no use check on the wire", listOf(false), tagged.toList())
        }
        tagged.clear()
        server.enqueue(ok())
        repository.updateSettingPartial(owner, 7, AlertSettingUpdateRequest(isEnabled = false), Supplier().check)
        assertEquals("control: a use check is on the wire", listOf(true), tagged.toList())
    }

    @Test fun `S7E5A2_W12 a retryable answer stays an HTTP failure with its evidence and no follow-up`() = runBlocking {
        for (status in listOf(408, 421, 503)) for (m in mutations) for (use in listOf<(() -> Boolean)?>(Supplier().check, null)) {
            server.shutdown()
            server = MockWebServer().apply { start() }
            setUpAgainstCurrentServer()
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
            server.enqueue(m.ok())
            val result = m.run(repository, owner, use)
            val failure = result.fold({ null }, { it }) as? AlertRepositoryException
            assertEquals("${m.label} $status: an HTTP failure", AlertRepositoryFailureKind.HTTP, failure?.failureKind)
            assertEquals("${m.label} $status", status, failure?.httpFailure?.statusCode)
            assertEquals("${m.label} $status: its evidence", AlertRateLimitResponseEvidence(status, "0"), result.rateLimitResponseEvidence)
            assertEquals("${m.label} $status: one send", 1, server.requestCount)
            assertEquals("${m.label} $status: no refresh", 0, source.forcedFetches.get())
        }
    }

    @Test fun `S7E5A2_W13 an identity that moves after the answer hands over that answer's evidence`() = runBlocking {
        for (m in mutations) {
            source = CountingTokenSource()
            provider = AuthTokenProvider(source, orders = AccessOrderSequence())
            server.shutdown()
            server = MockWebServer().apply { start() }
            setUpAgainstCurrentServer()
            owner = checkNotNull(repository.captureOwnerOrNull())
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
            switchAfterResponse = true
            val thrown = runCatching { m.run(repository, owner, Supplier().check) }.exceptionOrNull()
            switchAfterResponse = false
            val changed = thrown as? AuthIdentityChangedException
            assertNotNull("${m.label}: identity loss, not $thrown", changed)
            assertEquals("${m.label}: the answer", listOf<Any?>(429, "7", listOf(HttpExchangeEvidence(1, 1, 429, "7"))),
                listOf(changed!!.statusCode, changed.retryAfter, changed.exchanges))
            assertEquals("${m.label}: one send", 1, server.requestCount)
        }
    }

    @Test fun `S7E5A2_W14 the release gate refuses every mutation before any send with or without a use check`() = runBlocking {
        val snapshot = client.captureSnapshot(owner)
        val runs: List<Pair<String, suspend ((() -> Boolean)?) -> Unit>> = listOf(
            "create" to { u -> client.createNotificationSetting(snapshot, create, u) },
            "full update" to { u -> client.updateNotificationSetting(snapshot, 7, create, u) },
            "toggle" to { u -> client.updateNotificationSettingPartial(snapshot, 7, AlertSettingUpdateRequest(isEnabled = false), u) },
            "delete" to { u -> client.deleteNotificationSetting(snapshot, 7, u) })
        release.set(false)
        for ((label, run) in runs) for (use in listOf<(() -> Boolean)?>(null, { true })) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(row))
            val before = server.requestCount
            val refused = runCatching { run(use) }.exceptionOrNull()
            assertTrue("$label: refused by the release gate, not $refused",
                refused is java.io.IOException && refused.message == "D24 release admission is OFF")
            assertEquals("$label: nothing sent", before, server.requestCount)
        }
        release.set(true)
    }

    /** Rebuilds the repository against the current [server] (a fresh server keeps answers of one case out of the next). */
    private fun setUpAgainstCurrentServer() {
        val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                clientEntries.incrementAndGet()
                val response = chain.proceed(chain.request())
                if (switchAfterResponse) source.uid = "user-b"
                response
            }
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor { chain ->
                tagged += chain.request().tag(TopicUseTag::class.java) != null
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
    }
}
