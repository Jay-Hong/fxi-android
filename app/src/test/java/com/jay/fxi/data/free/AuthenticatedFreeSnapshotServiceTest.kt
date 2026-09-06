package com.jay.fxi.data.free

import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiException
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedBodyDecodingException
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.dto.FreeSnapshotFixtures as F
import com.jay.fxi.data.remote.dto.with
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class AuthenticatedFreeSnapshotServiceTest {
    private val responses = java.util.concurrent.ConcurrentLinkedQueue<ResponseSpec>()
    private val requests = CopyOnWriteArrayList<Request>()
    private lateinit var http: OkHttpClient
    private lateinit var source: TokenSource
    private lateinit var service: FreeSnapshotFetching
    private var afterResponse: () -> Unit = {}

    @Before
    fun setUp() {
        source = TokenSource()
        val provider = AuthTokenProvider(source)
        http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .callTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            // Exercise real Retrofit + auth transport without a listening socket. This final
            // application interceptor is the scripted HTTP peer, after credential injection.
            .addInterceptor { chain ->
                requests += chain.request()
                val next = responses.poll() ?: throw java.io.IOException("Unexpected extra HTTP request")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(next.code).message("Scripted response")
                    .body(next.body.toResponseBody())
                    .header("Cache-Control", "no-store")
                    .apply { next.retryAfter?.let { header("Retry-After", it) } }
                    .build().also { afterResponse() }
            }
            .build()
        val api = AuthenticatedApiClient(
            Retrofit.Builder().baseUrl("https://free-snapshot.test/").client(http).build()
                .create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }), F.json
        )
        service = AuthenticatedFreeSnapshotService(api, FreeSnapshotSanitizer())
    }

    @After
    fun tearDown() {
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdownNow()
    }

    @Test
    fun fetchUsesAuthenticatedFreeEndpoint_exactTabPeriod_andReturnsSanitizedDomain() = runTest {
        // Kills wrong route/query/token wiring and bypassing the sanitizer on the production path.
        val wire = F.snapshot("tether", "3m")
        val graph = (wire.getValue("graph") as JsonObject).with("series", JsonArray(listOf(
            F.series("dxy"), F.series("krx.usd-krw-futures"),
            F.series("hana.usd", listOf(F.point(), F.point("krx")))
        )))
        enqueue(wire.with("graph", graph).toString())
        val result = service.fetch("tether", GraphPeriod.THREE_MONTHS)
        assertEquals("tether", result.tab)
        assertEquals(GraphPeriod.THREE_MONTHS, result.period)
        assertEquals(listOf("upbit"), result.testRates.map { it.source })
        assertEquals(listOf("dxy"), result.graph.series.map { it.seriesId })
        assertEquals(F.decode(wire).refreshNotBefore, result.refreshNotBefore)
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/v2/free/snapshot?tab=tether&period=3m", request.url.encodedPath + "?" + request.url.encodedQuery)
        assertEquals("Bearer initial-token", request.header("Authorization"))
        assertEquals(1, requests.size)
    }

    @Test
    fun httpFailures_keepStatusBodyRetryAfter_andNeverBecomeEmptySnapshots() = runTest {
        // Kills swallowed errors and treating this Firebase-only route as a premium policy endpoint.
        enqueue()
        assertEquals(1, service.fetch("usd", GraphPeriod.ONE_DAY).testRates.size)
        listOf(400, 403, 404, 429, 503).forEach { code ->
            val raw = if (code == 403) """{"detail":"Premium subscription required"}""" else """{"error":"snapshot_unavailable"}"""
            responses.add(ResponseSpec(code, raw, "7"))
            val error = runCatching { service.fetch("usd", GraphPeriod.ONE_DAY) }.exceptionOrNull()
            assertTrue("HTTP $code", error is AuthenticatedApiException)
            val failure = (error as AuthenticatedApiException).failure
            assertEquals(code, failure.statusCode)
            assertEquals(raw, failure.rawBodyText)
            assertEquals("7", failure.retryAfter)
            if (code == 403) assertEquals(AuthenticatedFailureKind.UNKNOWN_AUTHORIZATION, failure.kind)
        }
        assertEquals(6, requests.size)
    }

    @Test
    fun unauthorizedGet_refreshesOnce_thenEitherSucceedsOrPreservesSecond401() = runTest {
        // Kills disabling safe GET replay, unbounded replay, and collapsing terminal 401 to success.
        responses.add(ResponseSpec(401, "expired"))
        enqueue()
        assertEquals("usd", service.fetch("usd", GraphPeriod.ONE_DAY).tab)
        assertEquals(1, source.refreshes)
        assertEquals("Bearer initial-token", requests[0].header("Authorization"))
        assertEquals("Bearer refreshed-token", requests[1].header("Authorization"))
        responses.add(ResponseSpec(401, "expired again"))
        responses.add(ResponseSpec(401, "rejected"))
        val error = runCatching { service.fetch("usd", GraphPeriod.ONE_DAY) }.exceptionOrNull()
        assertTrue(error is AuthenticatedApiException)
        assertEquals(401, (error as AuthenticatedApiException).failure.statusCode)
        assertEquals("rejected", error.failure.rawBodyText)
        assertEquals(2, source.refreshes)
        assertEquals(4, requests.size)
    }

    @Test
    fun malformedSuccessAndEchoMismatch_throwWithAcceptedControl() = runTest {
        // Kills decoding fallback and failure to bind an otherwise valid response to the requested key.
        enqueue()
        assertEquals("usd", service.fetch("usd", GraphPeriod.ONE_DAY).tab)
        enqueue("{broken")
        val decodeError = runCatching { service.fetch("usd", GraphPeriod.ONE_DAY) }.exceptionOrNull()
        assertTrue(decodeError is AuthenticatedBodyDecodingException)
        assertEquals("{broken", (decodeError as AuthenticatedBodyDecodingException).response.rawBodyText)
        enqueue(F.snapshot("jpy").toString())
        assertTrue(runCatching { service.fetch("usd", GraphPeriod.ONE_DAY) }.exceptionOrNull() is FreeSnapshotValidationException)
        enqueue(F.snapshot("usd", "1w").toString())
        assertTrue(runCatching { service.fetch("usd", GraphPeriod.ONE_DAY) }.exceptionOrNull() is FreeSnapshotValidationException)
        val wire = F.snapshot()
        enqueue(wire.with("rate", (wire.getValue("rate") as JsonObject).with("asset", JsonPrimitive("jpy-krw"))).toString())
        assertTrue(runCatching { service.fetch("usd", GraphPeriod.ONE_DAY) }.exceptionOrNull() is FreeSnapshotValidationException)
    }

    @Test
    fun rateContaminationAndHybridBodies_neverEscapeTheFetchBoundary() = runTest {
        // Kills bypassing rate inspection or decoding hybrids with the global ignoreUnknownKeys setting.
        listOf("usd", "tether").forEach { tab ->
            val wire = F.snapshot(tab)
            enqueue(wire.toString())
            assertEquals(1, service.fetch(tab, GraphPeriod.ONE_DAY).testRates.size)
            val rate = wire.getValue("rate") as JsonObject
            val ownKey = if (tab == "usd") "entries" else "usdt_krw"
            val contaminated = rate.with(ownKey, JsonArray(listOf(F.rate("krx"))))
            enqueue(wire.with("rate", contaminated).toString())
            assertTrue(runCatching { service.fetch(tab, GraphPeriod.ONE_DAY) }.exceptionOrNull() is FreeSnapshotValidationException)
            val otherKey = if (tab == "usd") "usdt_krw" else "entries"
            enqueue(wire.with("rate", rate.with(otherKey, JsonArray(emptyList()))).toString())
            assertTrue(runCatching { service.fetch(tab, GraphPeriod.ONE_DAY) }.exceptionOrNull() is AuthenticatedBodyDecodingException)
        }
    }

    @Test
    fun signedOutAndChangedIdentity_cannotReturnOrSendAnUnownedSnapshot() = runTest {
        // Kills unauthenticated network fallback and admission of late responses from a previous owner.
        enqueue()
        assertEquals("usd", service.fetch("usd", GraphPeriod.ONE_DAY).tab)
        source.identity = null
        assertTrue(runCatching { service.fetch("usd", GraphPeriod.ONE_DAY) }.exceptionOrNull() is AuthUnavailableException)
        assertEquals(1, requests.size)
        source.identity = AuthIdentity("user-a", 2)
        enqueue()
        afterResponse = { source.identity = AuthIdentity("user-a", 3) }
        assertTrue(runCatching { service.fetch("usd", GraphPeriod.ONE_DAY) }.exceptionOrNull() is AuthIdentityChangedException)
        assertEquals(2, requests.size)
    }

    @Test
    fun cancellationDuringCredentialAcquisition_isPropagatedWithoutHttp() = runTest {
        // Kills catching cancellation as a retryable failure or fabricated successful snapshot.
        enqueue()
        assertEquals("usd", service.fetch("usd", GraphPeriod.ONE_DAY).tab)
        source.identity = AuthIdentity("user-a", 2)
        val entered = CompletableDeferred<Unit>()
        source.beforeToken = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        val request = async { service.fetch("usd", GraphPeriod.ONE_DAY) }
        entered.await()
        request.cancel()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, requests.size)
    }

    private fun enqueue(body: String = F.snapshot().toString()) {
        responses.add(ResponseSpec(200, body))
    }

    private data class ResponseSpec(val code: Int, val body: String, val retryAfter: String? = null)

    private class TokenSource : AuthTokenSource {
        @Volatile var identity: AuthIdentity? = AuthIdentity("user-a", 1)
        var refreshes = 0
        var beforeToken: suspend () -> Unit = {}
        override fun currentIdentity(): AuthIdentity? = identity
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            beforeToken()
            if (forceRefresh) refreshes++
            return when (refreshes) {
                0 -> "initial-token"
                1 -> "refreshed-token"
                else -> "refreshed-token-$refreshes"
            }
        }
    }
}
