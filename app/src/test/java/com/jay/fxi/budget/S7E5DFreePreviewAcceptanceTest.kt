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
import com.jay.fxi.data.remote.dto.with
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.alert.BankAlertEditorInput
import com.jay.fxi.ui.free.FreeAlertPreviewSession
import com.jay.fxi.ui.free.FreeSnapshotUiState
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * S7 E5d integration (제안 r2 §3 cutover, R14): the free journey's physical sends around the bank alert preview, over the real REST
 * stack to MockWebServer on the real clock.
 *
 * What runs: the free scheduler on the real service and sanitizer, the real projection (`FreeSnapshotUiState.from`) and the real
 * preview session — open on that answer's quotes and as_of, edit, subscribe, the confirmed hide, the paywall signal — and then the
 * server answering a fresh premium query as a purchase would. What is judged: from the moment the free answers are in until the
 * paywall signal is delivered, **no send of any kind**; the premium query that follows is exactly one entitlements send; and at no
 * point a WebSocket, topic, Graph V2, alert, history or device request, nor a second snapshot fetch.
 *
 * **Not measured here** (see the device tests and the structure lock): the Compose host and sheet, the Root and its paywall overlay,
 * the push and alert owners (this rig does not build them, so their zero is structural, not observed), Firebase, the app's full start.
 */
class S7E5DFreePreviewAcceptanceTest {

    private companion object {
        const val UID = "user-a"
        val PERIODS = GraphPeriod.entries.map { it.code }.toSet()
        val UNMEASURED = listOf("compose-host", "root-paywall", "push-owner", "alert-owner", "firebase-token", "app-full-start", "through-nginx")
        val FORBIDDEN_PATHS = listOf("/ws", "/api/v2/topics", "/api/v2/graph", "/api/notification", "/api/register-device", "/api/source-notification",
            "/api/comparison")
    }

    private val origin = System.nanoTime()
    private fun nowMillis() = (System.nanoTime() - origin) / 1_000_000
    private val sends = SendRecorder(::nowMillis)
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val premium = AtomicBoolean(false)

    private lateinit var server: MockWebServer
    private lateinit var provider: AuthTokenProvider
    private lateinit var api: AuthenticatedApiClient

    private class TokenSource : AuthTokenSource {
        override fun currentIdentity(): AuthIdentity = AuthIdentity(UID, 1)
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String = "token"
    }

    /** The fixture's usd answer with two editor banks and Citi beside its Investing row. */
    private fun usdSnapshot(period: String): JsonObject {
        val base = FreeSnapshotFixtures.snapshot("usd", period)
        val rate = JsonObject(mapOf(
            "asset" to JsonPrimitive("usd-krw"),
            "entries" to JsonArray(listOf(
                FreeSnapshotFixtures.rate("investing", "usd-krw", 1400.0),
                FreeSnapshotFixtures.rate("kb", "usd-krw", 1401.5),
                FreeSnapshotFixtures.rate("citi", "usd-krw", 1399.0)
            ))
        ))
        return base.with("rate", rate)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                received += SendRecorder.describe(url.encodedPath, url.queryParameterNames.associateWith { url.queryParameter(it) })
                return when (url.encodedPath) {
                    "/api/entitlements" ->
                        MockResponse().setResponseCode(200).setBody("""{"krx_visible":false,"premium_active":${premium.get()}}""")
                    "/api/v2/free/snapshot" ->
                        MockResponse().setResponseCode(200).setBody(usdSnapshot(url.queryParameter("period")!!).toString())
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
    fun E5D_thePreviewJourneySendsNothing_andThePurchaseThatFollowsIsOneEntitlementsQuery() = runBlocking {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val scheduler = FreeSnapshotScheduler(
            fetcher = AuthenticatedFreeSnapshotService(api, FreeSnapshotSanitizer()),
            uidStream = AuthUidStream { it(UID) },
            authFence = provider::currentIdentityFence,
            onEventFailure = { failures += it },
            scope = scope,
            clock = { Instant.parse("2026-09-06T10:31:00+09:00") },
            installId = { "install-1" }
        )
        try {
            // The free answers come in, as on the shown 달러 tab.
            scheduler.start()
            scheduler.onActivated("usd", GraphPeriod.ONE_DAY)
            withTimeout(20.seconds) { while (snapshotPeriods() != PERIODS) delay(50) }
            delay(1_000)
            val state = FreeSnapshotUiState.from(UID, FreeTab.USD, GraphPeriod.ONE_DAY, scheduler.readState.value, emptySet())
            assertEquals("premise: the answer projects its editor banks, Citi out", mapOf("investing" to 1400.0, "kb" to 1401.5), state.previewQuotes)
            assertNotNull("premise: with its as_of", state.previewAsOf)
            val before = sends.all().size

            // The preview, through its own session: open, edit, subscribe, the confirmed hide, the signal delivered.
            val session = FreeAlertPreviewSession()
            session.invalidate(UID, FreeTab.USD)
            val token = checkNotNull(session.open(UID, FreeTab.USD, state.previewQuotes, state.previewAsOf))
            assertEquals("the preview opened on KB's frozen quote", "1401.50", (session.state as FreeAlertPreviewSession.State.Open).input.thresholdText)
            session.edit(token, BankAlertEditorInput(Bank.HANA, AlertCondition.ABOVE, "1450", RepeatIntervalChange.Once))
            assertTrue(session.subscribe(token))
            val signal = checkNotNull(session.dismissed(token))
            assertTrue("the paywall signal is deliverable", session.deliverable(signal))
            delay(2_000)
            assertEquals("the preview journey sent something: ${sends.all().drop(before)}", before, sends.all().size)

            // The purchase: the server now answers a fresh premium query as active. The preview's input goes nowhere.
            premium.set(true)
            session.dispose()
            val after = AuthenticatedEntitlementsSource(api).fetch(true)
            assertEquals(
                EntitlementsResult.Answered(EntitlementsIdentity(UID, 1L), EntitlementsOutcome.StableActive(krxVisible = false)),
                after
            )
            delay(1_000)
            val tail = sends.all().drop(before)
            assertEquals("after the purchase, one entitlements query and nothing else", listOf("/api/entitlements?fresh_premium=true -> 200"),
                tail.map { "${it.key} -> ${it.status}" })
        } finally {
            scope.cancel()
            println("E5D timeline: " + sends.timeline())
        }
        println("E5D not measured: $UNMEASURED")
        assertEquals("the scheduler reported a failure", emptyList<Throwable>(), failures.toList())
        val all = sends.all()
        assertEquals("the recorder and the server disagree", synchronized(received) { received.toList() }.sorted(), all.map { it.key }.sorted())
        assertTrue("a forbidden request: ${all.map { it.key }}", all.none { s -> FORBIDDEN_PATHS.any { s.path.startsWith(it) } })
        val snapshots = all.filter { it.path == "/api/v2/free/snapshot" }
        PERIODS.forEach { period ->
            assertEquals("period $period was fetched more than once", 1, snapshots.count { it.param("period") == period })
        }
    }

    private fun snapshotPeriods(): Set<String> =
        sends.all().filter { it.path == "/api/v2/free/snapshot" && it.status == 200 }.mapNotNull { it.param("period") }.toSet()
}
