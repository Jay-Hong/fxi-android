package com.jay.fxi.budget

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.AuthenticatedEntitlementsSource
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.EntitlementsResult
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantIssuer
import com.jay.fxi.data.entitlements.TopicGrantResult
import com.jay.fxi.data.entitlements.TopicRejectionLedger
import com.jay.fxi.data.entitlements.TopicRejectionReservation
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthTokenTopicCommandCredentials
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.MutationOneShotInterceptor
import com.jay.fxi.data.remote.TopicBootstrapRetryFloor
import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicCommandClock
import com.jay.fxi.data.remote.TopicFrameDecoder
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicRuntimeFactory
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicSnapshotBootstrapService
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicRejectionReason
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * ②b-2: the premium cold start's physical sends, judged against the server's nginx limits (S2b/rule_consensus_codex.r1.md).
 *
 * What is measured: one entitlement query (`AuthenticatedEntitlementsSource`, the real REST stack) and then the assembled topic
 * runtime (`TopicRuntimeFactory` with the real `TopicSnapshotBootstrapService`) connecting and bootstrapping every desired topic,
 * over a real socket to MockWebServer. The REST client's last network interceptor records every physical exchange, so a 401 and
 * its replay are two sends; the WebSocket handshake, which OkHttp runs without network interceptors, is recorded by an
 * application interceptor on its own client. On the real clock: the bootstrap budget is elapsed time, which a virtual clock cannot drive
 * (TopicSnapshotBootstrapServiceTest explains).
 *
 * The gate: per zone, nginx's predicted excess never above 10 (half of `burst=20`); `/api/` in flight at once never above 10; no
 * 429. It is a prediction from send starts, not a measurement through nginx.
 *
 * **Not measured here** (reported, never counted as passed): Firebase's own token traffic, the free snapshot path (②b-3), alerts
 * and push registration, Graph and history, the app's real cold start (nothing starts the runtime yet), and anything through nginx.
 */
class PremiumColdStartBudgetTest {

    private companion object {
        val DESIRED = TopicCatalogue.DESIRED
        const val TETHER = TopicCatalogue.TETHER
        val UNMEASURED = listOf(
            "firebase-token", "free-snapshot(②b-3)", "alerts", "push-registration", "graph", "history",
            "app-cold-start-wiring", "through-nginx"
        )
    }

    private val origin = System.nanoTime()
    private fun nowMillis() = (System.nanoTime() - origin) / 1_000_000
    private val sends = SendRecorder(::nowMillis)

    private lateinit var server: MockWebServer
    /** Every request the server received, as `path?topic`, in arrival order. */
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
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

    private class Issuer : TopicGrantIssuer {
        val revisions = MutableStateFlow(0L)
        override val accessRevisions: StateFlow<Long> get() = revisions
        @Volatile var grant: TopicSessionFence? = null
        private val ledger = TopicRejectionLedger(AtomicLong(0L)::incrementAndGet)
        override suspend fun topicGrantResult(): TopicGrantResult {
            val fence = grant
            return TopicGrantResult(
                fence,
                TopicAccessSnapshot.INITIAL.copy(
                    revision = revisions.value,
                    facts = TopicAccessFacts.NONE.copy(token = fence?.grant, tokenStanding = fence != null, userBlocks = emptySet())
                )
            )
        }
        override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>) = ledger.reserve(grant, reasons)
        override fun abandonRejection(reservation: TopicRejectionReservation) = ledger.abandon(reservation)
        override suspend fun onTopicRejected(reservation: TopicRejectionReservation) = ledger.complete(reservation, true)
    }

    private class Tabs(private val tab: FreeTab) : FreeTabStore {
        override suspend fun lastTab(uid: String): FreeTab = tab
        override suspend fun remember(uid: String, tab: FreeTab) = Unit
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        val entitlementCalls = AtomicInteger()
        val tetherCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                received += SendRecorder.describe(url.encodedPath, url.queryParameterNames.associateWith { url.queryParameter(it) })
                return when {
                    url.encodedPath == "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})
                    url.encodedPath == "/api/entitlements" ->
                        if (entitlementCalls.getAndIncrement() == 0) unauthorized()
                        else MockResponse().setResponseCode(200).setBody("""{"krx_visible":false,"premium_active":true}""")
                    url.encodedPath == "/api/v2/topics/snapshot" ->
                        if (url.queryParameter("topic") == TETHER && tetherCalls.getAndIncrement() == 0) unauthorized()
                        else MockResponse().setResponseCode(404).setBody("""{"error":"topic_unavailable"}""")
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

    private fun unauthorized() = MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")

    @Test
    fun P_premiumColdStart_staysInsideTheNginxBudget_andCountsEveryPhysicalSend() = runBlocking {
        val owner: AuthIdentityFence = api.captureIdentityFence()
        val wireJson = NetworkModule.provideWireJson()
        val service = TopicSnapshotBootstrapService(api, TopicFrameDecoder(wireJson), 10.seconds)
        val issuer = Issuer()
        val clock = object : TopicCommandClock {
            override fun nowMillis(): Long = this@PremiumColdStartBudgetTest.nowMillis()
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        // OkHttp runs no network interceptor on a WebSocket call, so the handshake is recorded as an application interceptor.
        val wsClient = OkHttpClient.Builder().addInterceptor(sends.interceptor).build()
        val factory = TopicRuntimeFactory(
            webSocketFactory = { wsClient },
            webSocketUrl = server.url("/ws").toString(),
            decode = TopicFrameDecoder(wireJson)::decode,
            bootstrap = { fence, topic, useAdmitted -> service.bootstrap(fence, topic, useAdmitted) },
            issuer = issuer,
            fences = AuthFenceStream { it(owner) },
            liveFence = { owner },
            recoveries = AuthCredentialRecoveryStream { },
            tabs = Tabs(FreeTab.TETHER),
            credentials = AuthTokenTopicCommandCredentials(provider),
            orders = AccessOrderSequence(),
            authority = object : TopicUseAuthority {
                override fun acquire(fence: TopicSessionFence) = TopicUseLifetime(fence.grant, 0L)
                override fun admits(lifetime: TopicUseLifetime) = true
            },
            clock = clock,
            newBootstrapFloor = { TopicBootstrapRetryFloor(it) { kotlinx.datetime.Clock.System.now() } },
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)) },
            encode = { wireJson.encodeToString(TopicSubscribeRequest.serializer(), it) },
            newRequestId = { java.util.UUID.randomUUID().toString() },
            jitter = { 0.0 }
        )

        // S1 order: authentication and entitlement first, then the topics.
        val entitlement = AuthenticatedEntitlementsSource(api).fetch(false)
        assertEquals(
            "the cold start did not begin from a premium answer",
            EntitlementsResult.Answered(EntitlementsIdentity("user-a", 1L), EntitlementsOutcome.StableActive(krxVisible = false)),
            entitlement
        )
        issuer.grant = TopicSessionFence(owner, "epoch-1", TopicGrantToken(1L))
        val runtime = factory.create()
        runtime.start()
        runtime.setOnline(true)
        try {
            withTimeout(20.seconds) {
                while (snapshotTopics() != DESIRED || sends.all().none { it.zone == "ws" }) delay(50)
            }
            delay(1_000) // anything owed right behind the last one
        } finally {
            runtime.stop()
            println("②b-2 timeline: " + sends.timeline())
        }

        val all = sends.all()
        println("②b-2 not measured: $UNMEASURED")

        // The recorder saw what the server saw, and a 401 and its replay are two sends.
        assertEquals(
            "the recorder and the server disagree, path by path",
            synchronized(received) { received.toList() }.sorted(),
            all.map { it.key }.sorted()
        )
        assertEquals("the handshake was not a single upgrade", listOf(101), all.filter { it.zone == "ws" }.map { it.status })
        assertEquals(listOf(401, 200), all.filter { it.path == "/api/entitlements" }.map { it.status })
        assertEquals(listOf(401, 404), all.filter { it.param("topic") == TETHER }.map { it.status })
        assertEquals("the measurement did not cover every desired topic", DESIRED, snapshotTopics())

        val verdict = ColdStartBudget.judge(all)
        println("②b-2 verdict: $verdict")
        ColdStartBudget.assertWithin(verdict)
    }

    private fun snapshotTopics(): Set<String> = sends.all().mapNotNull { it.param("topic") }.toSet()
}
