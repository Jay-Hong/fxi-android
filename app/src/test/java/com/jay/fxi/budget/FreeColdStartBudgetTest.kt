package com.jay.fxi.budget

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.AuthUidStream
import com.jay.fxi.data.entitlements.AuthenticatedEntitlementsSource
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.EntitlementsResult
import com.jay.fxi.data.free.AuthenticatedFreeSnapshotService
import com.jay.fxi.data.free.FreeSnapshotSanitizer
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.dto.FreeSnapshotFixtures
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.GraphPeriod
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * ②b-3: the free cold start's physical sends, judged by [ColdStartBudget] (S2b3/decl_codex.r1.md).
 *
 * What is measured: the app's own free start (`FXiApplication` starts the access binder and the free scheduler) reduced to its
 * two senders — the entitlement query the binder issues for a new sign-in (`fresh_premium=true`), **concurrently** with the free
 * scheduler activated on the shown tab and warming the other three periods — over the real REST stack to MockWebServer, on the
 * real clock. The scheduler's clock is pinned just after the fixtures' basis, so no refresh falls due inside the window. The shown
 * period's first query answers 401 once, so its replay is counted as a send of its own.
 *
 * **Not measured here:** Firebase's own token traffic, the WebSocket and topic bootstrap (the free journey starts none, but this
 * reduced start cannot show the whole app sends none), alerts and push registration, Graph and history, the app's full start, and
 * anything through nginx.
 */
class FreeColdStartBudgetTest {

    private companion object {
        const val TAB = "usd"
        val PERIODS = GraphPeriod.entries.map { it.code }.toSet()
        val UNMEASURED = listOf(
            "firebase-token", "websocket", "topic-bootstrap", "alerts", "push-registration", "graph", "history",
            "app-full-start", "through-nginx"
        )
    }

    private val origin = System.nanoTime()
    private fun nowMillis() = (System.nanoTime() - origin) / 1_000_000
    private val sends = SendRecorder(::nowMillis)
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private lateinit var server: MockWebServer
    private lateinit var provider: AuthTokenProvider
    private lateinit var api: AuthenticatedApiClient

    private class TokenSource : AuthTokenSource {
        private var token = "old-token"
        override fun currentIdentity(): AuthIdentity = AuthIdentity("user-a", 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            if (forceRefresh) token = "fresh-token-${System.nanoTime()}"
            return token
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        val oneDayCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                received += SendRecorder.describe(url.encodedPath, url.queryParameterNames.associateWith { url.queryParameter(it) })
                return when (url.encodedPath) {
                    "/api/entitlements" ->
                        MockResponse().setResponseCode(200).setBody("""{"krx_visible":false,"premium_active":false}""")
                    "/api/v2/free/snapshot" ->
                        if (url.queryParameter("period") == "1d" && oneDayCalls.getAndIncrement() == 0) {
                            MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                        } else {
                            val body = FreeSnapshotFixtures.snapshot(url.queryParameter("tab")!!, url.queryParameter("period")!!)
                            MockResponse().setResponseCode(200).setBody(body.toString())
                        }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        provider = AuthTokenProvider(TokenSource(), orders = AccessOrderSequence())
        val http = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .addInterceptor(AuthSnapshotInterceptor(provider))
            .addInterceptor(MutationOneShotInterceptor())
            .addNetworkInterceptor(TopicUseNetworkInterceptor(provider))
            .addNetworkInterceptor(sends.interceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(http)
            .addConverterFactory(NetworkModule.provideWireJson().asConverterFactory("application/json".toMediaType()))
            .build()
        api = AuthenticatedApiClient(
            retrofit.create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            NetworkModule.provideWireJson()
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun F_freeColdStart_staysInsideTheNginxBudget_andCountsEveryPhysicalSend() = runBlocking {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val scheduler = FreeSnapshotScheduler(
            fetcher = AuthenticatedFreeSnapshotService(api, FreeSnapshotSanitizer()),
            uidStream = AuthUidStream { it("user-a") },
            authFence = provider::currentIdentityFence,
            onEventFailure = { failures += it },
            scope = scope,
            clock = { Instant.parse("2026-09-06T10:31:00+09:00") },
            installId = { "install-1" }
        )
        val entitlement = try {
            // The binder's query and the shown tab start together, as they do at app start.
            val query = async(Dispatchers.IO) { AuthenticatedEntitlementsSource(api).fetch(true) }
            scheduler.start()
            scheduler.onActivated(TAB, GraphPeriod.ONE_DAY)
            withTimeout(20.seconds) { while (snapshotPeriods() != PERIODS) delay(50) }
            delay(1_000) // anything owed right behind the last one
            query.await()
        } finally {
            scope.cancel()
            println("②b-3 timeline: " + sends.timeline())
        }
        println("②b-3 not measured: $UNMEASURED")

        assertEquals(
            "the start was not a free answer",
            EntitlementsResult.Answered(EntitlementsIdentity("user-a", 1L), EntitlementsOutcome.StableInactive(krxVisible = false)),
            entitlement
        )
        assertEquals("the scheduler reported a failure", emptyList<Throwable>(), failures.toList())
        val all = sends.all()
        assertEquals(
            "the recorder and the server disagree, path by path",
            synchronized(received) { received.toList() }.sorted(),
            all.map { it.key }.sorted()
        )
        assertEquals(
            "the sends were not exactly the entitlement query and one query per period, the shown one replayed once",
            listOf(
                "/api/entitlements?fresh_premium=true -> 200",
                "/api/v2/free/snapshot?period=1d&tab=usd -> 200",
                "/api/v2/free/snapshot?period=1d&tab=usd -> 401",
                "/api/v2/free/snapshot?period=1w&tab=usd -> 200",
                "/api/v2/free/snapshot?period=1y&tab=usd -> 200",
                "/api/v2/free/snapshot?period=3m&tab=usd -> 200"
            ),
            all.map { "${it.key} -> ${it.status}" }.sorted()
        )
        val snapshots = all.filter { it.path == "/api/v2/free/snapshot" }
        assertEquals("the shown period's 401 and its replay are not two sends", listOf(401, 200), snapshots.filter { it.param("period") == "1d" }.map { it.status })
        (PERIODS - "1d").forEach { period ->
            assertEquals("period $period was not fetched exactly once", listOf(200), snapshots.filter { it.param("period") == period }.map { it.status })
        }
        assertEquals("the measurement did not cover every period", PERIODS, snapshotPeriods())

        val verdict = ColdStartBudget.judge(all)
        println("②b-3 verdict: $verdict")
        ColdStartBudget.assertWithin(verdict)
    }

    private fun snapshotPeriods(): Set<String> =
        sends.all().filter { it.path == "/api/v2/free/snapshot" && it.status == 200 }.mapNotNull { it.param("period") }.toSet()
}
