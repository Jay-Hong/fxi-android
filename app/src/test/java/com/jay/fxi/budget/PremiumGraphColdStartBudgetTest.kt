package com.jay.fxi.budget

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.admission.ReleaseAdmissionInterceptor
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.AccessEpochRecord
import com.jay.fxi.data.entitlements.AccessEpochStore
import com.jay.fxi.data.entitlements.AccessEpochTransitions
import com.jay.fxi.data.entitlements.AuthenticatedEntitlementsSource
import com.jay.fxi.data.entitlements.CapabilityScopePurger
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.LossObligation
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.PremiumAccessTopicGrantIssuer
import com.jay.fxi.data.entitlements.ProbeJitter
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.UserScopePurger
import com.jay.fxi.data.free.FreeSnapshotSchedulePolicy
import com.jay.fxi.data.graph.AuthenticatedGraphOwnerSource
import com.jay.fxi.data.graph.AuthenticatedGraphV2Fetcher
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphProtectedAdmission
import com.jay.fxi.data.graph.GraphRuntimeAssembly
import com.jay.fxi.data.graph.GraphTabRecoveryDemand
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthTokenTopicCommandCredentials
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.TopicBootstrapRetryFloor
import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicCommandClock
import com.jay.fxi.data.remote.TopicForegroundStream
import com.jay.fxi.data.remote.TopicFrameDecoder
import com.jay.fxi.data.remote.TopicGrantOrigin
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicRuntimeFactory
import com.jay.fxi.data.remote.TopicRuntimeOwner
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicSnapshotBootstrapService
import com.jay.fxi.data.remote.TopicUseAttribution
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.time.SystemAppClock
import java.io.File
import java.io.IOException
import java.time.Instant as JavaInstant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * S4 INT-b (`int_api_agreed.r3.md` §3–§5, design `int_b_design.r3.md`): **preparation evidence for CUT-C02, not its acceptance.**
 *
 * The premium cold start of [PremiumColdStartBudgetTest] (real issuer, process owner, topic runtime, real clock) with the graph
 * runtime assembled on top of the same issuer, authority and protected REST client: the client is the production provider's,
 * with only its D24 interceptor replaced by an open one and a recorder added as the last network interceptor (S01 checks the
 * rest is the same). The graph fence is handed to the assembly's bridge directly (fixture), not by a deliverer fan-out; there is
 * no permit publisher, no RT05 producer and no disk ports. The current tab is usd 1d. Each row sums the graph's physical sends
 * with entitlement and topic sends and judges in flight by body lifetime.
 *
 * Not met here, left to the cutover candidate: repeated re-approval, the whole retry ladder, the production permit publisher,
 * the production factory, an assembly with write ports, and the deliverer fan-out (this bridge is a fixture).
 */
class PremiumGraphColdStartBudgetTest {

    private companion object {
        val DESIRED = TopicCatalogue.DESIRED
        const val TETHER = TopicCatalogue.TETHER
        val USD_1D = GraphKey("usd", GraphPeriod.ONE_DAY)
        /** Chosen so that the production jitter formula gives usd its minimum, 10 seconds; asserted in J00. */
        const val SEED = "int-b-seed-4"
        const val ROW_DEADLINE_MS = 20_000L
        val GRAPH = File("src/test/resources/contracts/v2/graph")
        val UNMET = listOf(
            "repeated-reapproval", "full-retry-ladder", "production-permit-publisher", "production-factory",
            "write-port-assembly", "deliverer-fan-out(fixture bridge)"
        )
        const val NOTICE = "body 기준 수치는 클라이언트가 관측한 body 수명의 계량이며, close/실패 뒤 서버 작업까지 끝났다는 증거는 " +
            "아닙니다. per-host 5는 동일 Dispatcher의 비동기 호출 제한으로 표시하고, body 기준 in-flight ≤5의 증명으로 사용하지 않습니다."
        const val PREMIUM_HIDDEN = """{"krx_visible":false,"premium_active":true}"""
        const val PREMIUM_VISIBLE = """{"krx_visible":true,"premium_active":true}"""
        val KST: ZoneOffset = ZoneOffset.ofHours(9)
        val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }

    private val origin = System.nanoTime()
    private fun nowMillis() = (System.nanoTime() - origin) / 1_000_000
    private val sends = SendRecorder(::nowMillis)
    private val accessOrders = AccessOrderSequence()
    private lateinit var server: MockWebServer
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // --- server scripts -------------------------------------------------------------------------------------------------------

    /** Entitlement answers in order; once spent, a premium (KRX hidden) 200. */
    private val entitlements = ConcurrentLinkedQueue<MockResponse>()
    /** Graph answers by path; each takes the request so a script can count, hold or build. */
    @Volatile private var onCatalog: (RecordedRequest) -> MockResponse = { ok(fixture("catalog-krx-hidden.json")) }
    @Volatile private var onTab: (RecordedRequest) -> MockResponse = { ok(fixture("usd-1d-krx-hidden.json")) }
    private val tetherCalls = AtomicInteger()

    private fun fixture(name: String) = File(GRAPH, name).readText()
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun unauthorized() = MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                received += SendRecorder.describe(url.encodedPath, url.queryParameterNames.associateWith { url.queryParameter(it) })
                return when (url.encodedPath) {
                    "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})
                    "/api/entitlements" -> entitlements.poll() ?: ok(PREMIUM_HIDDEN)
                    "/api/v2/topics/snapshot" ->
                        if (url.queryParameter("topic") == TETHER && tetherCalls.getAndIncrement() == 0) unauthorized()
                        else MockResponse().setResponseCode(404).setBody("""{"error":"topic_unavailable"}""")
                    "/api/v2/graph/catalog" -> onCatalog(request)
                    "/api/v2/graph/tab" -> onTab(request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    // --- the rig ---------------------------------------------------------------------------------------------------------------

    private class TokenSource(@Volatile var identity: AuthIdentity?) : AuthTokenSource {
        @Volatile private var token = "old-token"
        override fun currentIdentity(): AuthIdentity? = identity
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            if (forceRefresh) token = "fresh-token-${System.nanoTime()}"
            return token
        }
    }

    private class Tabs(private val tab: FreeTab) : FreeTabStore {
        override suspend fun lastTab(uid: String): FreeTab = tab
        override suspend fun remember(uid: String, tab: FreeTab) = Unit
    }

    /** The production provider's client with its D24 interceptor opened and the recorder last (design §2, S01). */
    private fun harnessClient(provider: AuthTokenProvider): OkHttpClient =
        NetworkModule.provideProtectedOkHttpClient(AuthSnapshotInterceptor(provider), TopicUseNetworkInterceptor(provider))
            .newBuilder()
            .apply {
                check(interceptors()[0] is ReleaseAdmissionInterceptor) { "premise: D24 is the first application interceptor" }
                interceptors()[0] = ReleaseAdmissionInterceptor(admitted = { true })
            }
            .addNetworkInterceptor(sends.interceptor)
            .build()

    private inner class Rig(identity: AuthIdentity? = AuthIdentity("user-a", 1)) {
        val source = TokenSource(identity)
        val provider = AuthTokenProvider(source, orders = accessOrders)
        private val wireJson = NetworkModule.provideWireJson()
        val api = AuthenticatedApiClient(
            Retrofit.Builder().baseUrl(server.url("/")).client(harnessClient(provider))
                .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            wireJson
        )
        val main = Dispatchers.Default.limitedParallelism(1)
        val mainJob = SupervisorJob()
        val runtimeJob = SupervisorJob()
        val graphParent = Job()
        val coordinator = budgetCoordinator(api, provider, CoroutineScope(mainJob + main))
        val uses = SnapshotTopicUseAuthority { coordinator.accessSnapshot }
        val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val assembly = GraphRuntimeAssembly(
            accessSnapshot = { coordinator.accessSnapshot },
            accessRevisions = coordinator.accessRevisions,
            liveIdentity = provider::currentIdentityFence,
            uses = uses,
            protectedAdmission = GraphProtectedAdmission(provider::currentIdentityFence, DeletionAdmissionStore()),
            fetcher = AuthenticatedGraphV2Fetcher(api),
            owners = AuthenticatedGraphOwnerSource(provider, api),
            cachePorts = { null },
            main = main,
            parent = graphParent,
            clock = SystemAppClock,
            rateLimitJitter = { FreeSnapshotSchedulePolicy.jitterFor(SEED, it) },
            onEventFailure = { failures += it }
        )
        private var topics: TopicRuntimeOwner? = null

        /** S1 order: the issuer approves first (entitlements over this client). */
        suspend fun approve(intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM) {
            coordinator.onIdentityChanged(checkNotNull(provider.currentIdentityFence()))
            coordinator.refresh(intent)
            assertEquals("premise: a fresh premium approval", PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        }

        /** The process owner starts the topic runtime on Main, as C4-J-BUDGET does. */
        suspend fun startTopics() {
            val owner = checkNotNull(provider.currentIdentityFence())
            val wireJson = NetworkModule.provideWireJson()
            val service = TopicSnapshotBootstrapService(api, TopicFrameDecoder(wireJson), 10.seconds)
            val clock = object : TopicCommandClock {
                override fun nowMillis(): Long = this@PremiumGraphColdStartBudgetTest.nowMillis()
                override suspend fun sleep(duration: Duration) = delay(duration)
            }
            val ws = OkHttpClient.Builder().addInterceptor(sends.interceptor).build()
            val factory = TopicRuntimeFactory(
                webSocketFactory = { ws },
                webSocketUrl = server.url("/ws").toString(),
                decode = TopicFrameDecoder(wireJson)::decode,
                bootstrap = { fence, topic, useAdmitted -> service.bootstrap(fence, topic, useAdmitted) },
                issuer = PremiumAccessTopicGrantIssuer(coordinator),
                fences = AuthFenceStream { it(owner) },
                liveFence = { owner },
                recoveries = AuthCredentialRecoveryStream { },
                tabs = Tabs(FreeTab.TETHER),
                credentials = AuthTokenTopicCommandCredentials(provider),
                orders = accessOrders,
                authority = uses,
                clock = clock,
                newBootstrapFloor = { TopicBootstrapRetryFloor(it) { Clock.System.now() } },
                newScope = { CoroutineScope(runtimeJob + Dispatchers.Default.limitedParallelism(1)) },
                encode = { wireJson.encodeToString(TopicSubscribeRequest.serializer(), it) },
                newRequestId = { java.util.UUID.randomUUID().toString() },
                jitter = { 0.0 }
            )
            val owned = TopicRuntimeOwner(
                factory = factory,
                online = MutableStateFlow(true),
                foreground = TopicForegroundStream { onForeground -> onForeground(true) },
                fences = AuthFenceStream { it(owner) },
                liveIdentity = { owner },
                rowPreferenceStore = object : RateRowPreferenceStore {
                    override suspend fun preferences(uid: String): Map<RateRowList, RateRowPreference> = emptyMap()
                    override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
                },
                main = CoroutineScope(mainJob + main)
            )
            topics = owned
            withContext(main) { owned.start() }
        }

        /** Starts the assembly on Main and hands its bridge [fence] directly (the fixture bridge); activates usd 1d if asked. */
        suspend fun startGraph(fence: TopicSessionFence?, activate: Boolean) = withContext(main) {
            assembly.start()
            assembly.fences.setAccess(fence != null, fence, TopicGrantOrigin.NewContext)
            if (activate) assembly.coordinator.onActivated(USD_1D)
        }

        suspend fun issued(): TopicSessionFence = checkNotNull(coordinator.topicGrantResult().fence) { "no grant was issued" }

        suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }

        /** Ends everything this rig started, then waits for every REST body to end. */
        suspend fun finish() {
            withContext(main) { assembly.close() }
            assertTrue("the assembly's children ended", graphParent.children.none { it.isActive })
            runtimeJob.cancelAndJoin()
            mainJob.cancelAndJoin()
            graphParent.cancelAndJoin()
            awaitTrue("every REST body ended", nowMillis() + 3_000) {
                sends.all().filter { it.zone == "api" }.all { it.bodyEnd != null }
            }
        }
    }

    private fun budgetCoordinator(api: AuthenticatedApiClient, provider: AuthTokenProvider, scope: CoroutineScope): PremiumAccessCoordinator {
        var n = 0
        val ids = EpochIdGenerator { "budget-${n++}" }
        var record = AccessEpochRecord()
        val store = object : AccessEpochStore {
            override suspend fun load(): AccessEpochRecord = record
            override suspend fun bindOwner(uid: String) =
                AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
            override suspend fun signOut() =
                AccessEpochTransitions.signOut(record, ids).also { record = it }
            override suspend fun retireUnverifiedStart() =
                AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
            override suspend fun beginSignOut(uid: String) =
                AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
            override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
                AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
            override suspend fun completePurges(completed: Collection<PendingPurge>) =
                AccessEpochTransitions.completePurges(record, completed).also { record = it }
            override suspend fun journalRetired(obligation: LossObligation) =
                AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
            override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
                AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
        }
        val purger = object : UserScopePurger, CapabilityScopePurger {
            override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Completed
            override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Completed
        }
        return PremiumAccessCoordinator(
            source = AuthenticatedEntitlementsSource(api),
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = scope,
            clock = { nowMillis() },
            jitter = ProbeJitter.None,
            liveFence = provider::currentIdentityFence,
            orders = accessOrders
        )
    }

    // --- helpers ---------------------------------------------------------------------------------------------------------------

    private suspend fun awaitTrue(label: String, deadline: Long, condition: suspend () -> Boolean) {
        while (!condition()) {
            if (nowMillis() > deadline) fail("timed out: $label\n${sends.timeline()}")
            delay(20)
        }
    }

    /** A scripted response held until the test releases it; a timeout is recorded, never silent (a broken premise). */
    private class Held {
        private val latch = CountDownLatch(1)
        @Volatile var timedOut = false
        fun await() {
            if (!latch.await(10, TimeUnit.SECONDS)) timedOut = true
        }
        fun release() = latch.countDown()
    }

    /**
     * Runs a row. On failure it releases the row's held responses and keeps the row's own failure, the cleanup's as suppressed;
     * on success it finishes the rig and asserts every held response was released by the test, not by its timeout.
     */
    private suspend fun Rig.row(vararg held: Held, body: suspend () -> Unit) {
        try {
            body()
        } catch (failure: Throwable) {
            held.forEach { it.release() }
            runCatching { finish() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        finish()
        held.forEach { assertTrue("premise: a held response was released by the test, not by its timeout", !it.timedOut) }
    }

    private fun graph(): List<SendRecorder.Exchange> = sends.all().filter { it.path.startsWith("/api/v2/graph/") }
    private fun catalogs() = graph().filter { it.path == "/api/v2/graph/catalog" }
    private fun tabs() = graph().filter { it.path == "/api/v2/graph/tab" }
    private fun snapshotTopics(): Set<String> = sends.all().mapNotNull { it.param("topic") }.toSet()
    private fun statuses(list: List<SendRecorder.Exchange>) = list.map { it.status }

    /** Holds the graph sends steady for [millis]; any new graph exchange fails the row. */
    private suspend fun quiet(label: String, millis: Long) {
        val before = graph().size
        val until = nowMillis() + millis
        while (nowMillis() < until) {
            assertEquals("$label: no further graph send\n${sends.timeline()}", before, graph().size)
            delay(50)
        }
    }

    /** The topic side every authenticated row expects: both entitlement sends, every desired topic, one handshake. */
    private fun assertTopicSide(label: String, entitlementStatuses: List<Int?> = listOf(401, 200)) {
        val all = sends.all()
        assertEquals("$label: entitlements", entitlementStatuses, statuses(all.filter { it.path == "/api/entitlements" }))
        assertEquals("$label: every desired topic", DESIRED, snapshotTopics())
        assertTrue("$label: a handshake", all.any { it.zone == "ws" && it.status == 101 })
    }

    /** Recorder and server agree path by path, and the budget holds on body lifetime; prints the row's report. */
    private fun judgeRow(label: String): ColdStartBudget.Verdict {
        val all = sends.all()
        assertEquals("$label: the recorder and the server disagree", synchronized(received) { received.toList() }.sorted(),
            all.map { it.key }.sorted())
        val verdict = ColdStartBudget.judge(all, bodyInFlight = true)
        println("INT-b $label timeline: ${sends.timeline()}")
        println("INT-b $label verdict: $verdict")
        println("INT-b $label: 준비 증거, CUT-C02 인수 아님. 미충족: $UNMET")
        println("INT-b $label: $NOTICE")
        println("INT-b $label differences from production: client D24 lambda, transport D24 lambda, the recorder (last network interceptor)")
        return verdict
    }

    /** A cold row up to the graph's first sends: approval, topics, then the assembly on usd 1d. */
    private suspend fun Rig.coldStart(activate: Boolean = true): TopicSessionFence {
        approve()
        startTopics()
        val fence = issued()
        startGraph(fence, activate)
        return fence
    }

    private fun Rig.catalogAdopted(): Boolean = assembly.coordinator.state.value.catalog != null

    private suspend fun Rig.awaitTopics(deadline: Long) =
        awaitTrue("topics and handshake", deadline) { snapshotTopics() == DESIRED && sends.all().any { it.zone == "ws" } }

    // --- J00, S01, S02 ---------------------------------------------------------------------------------------------------------

    /** J00: the seed premise B04's window rests on. */
    @Test
    fun `J00 the seed gives usd the minimum production jitter`() {
        assertEquals(10.seconds, FreeSnapshotSchedulePolicy.jitterFor(SEED, "usd"))
    }

    /** S01: the harness client is the production provider's but for its D24 lambda and the recorder. */
    @Test
    fun `S01 the harness client is the production protected client but for D24 and the recorder`() {
        val provider = AuthTokenProvider(TokenSource(AuthIdentity("user-a", 1)), orders = accessOrders)
        val production = NetworkModule.provideProtectedOkHttpClient(AuthSnapshotInterceptor(provider), TopicUseNetworkInterceptor(provider))
        val harness = harnessClient(provider)
        assertEquals(production.interceptors.map { it::class }, harness.interceptors.map { it::class })
        assertEquals(production.networkInterceptors.map { it::class }, harness.networkInterceptors.dropLast(1).map { it::class })
        assertTrue("the recorder is last", harness.networkInterceptors.last() === sends.interceptor)
        assertEquals(production.retryOnConnectionFailure, harness.retryOnConnectionFailure)
        assertEquals(production.followRedirects, harness.followRedirects)
        assertEquals(production.followSslRedirects, harness.followSslRedirects)
        assertTrue(production.authenticator === harness.authenticator && production.proxyAuthenticator === harness.proxyAuthenticator)
        assertEquals(
            listOf(production.connectTimeoutMillis, production.readTimeoutMillis, production.writeTimeoutMillis),
            listOf(harness.connectTimeoutMillis, harness.readTimeoutMillis, harness.writeTimeoutMillis)
        )
        assertEquals(production.dispatcher.maxRequests to production.dispatcher.maxRequestsPerHost,
            harness.dispatcher.maxRequests to harness.dispatcher.maxRequestsPerHost)
        println("INT-b S01 differences from production: client D24 lambda, transport D24 lambda, the recorder (last network interceptor)")
    }

    /** S02: with D24 compiled OFF, the production client's own D24 refuses a graph read before anything is sent. */
    @Test
    fun `S02 with D24 off the production client sends no graph read`() = runBlocking {
        assumeFalse("D24 is compiled ON in this variant", ReleaseAdmission.isOpen)
        val provider = AuthTokenProvider(TokenSource(AuthIdentity("user-a", 1)), orders = accessOrders)
        val control = SendRecorder(::nowMillis)
        val client = NetworkModule.provideProtectedOkHttpClient(AuthSnapshotInterceptor(provider), TopicUseNetworkInterceptor(provider))
            .newBuilder().addNetworkInterceptor(control.interceptor).build()
        val wireJson = NetworkModule.provideWireJson()
        val api = AuthenticatedApiClient(
            Retrofit.Builder().baseUrl(server.url("/")).client(client)
                .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            wireJson
        )
        val thrown = try {
            AuthenticatedGraphV2Fetcher(api).catalog(AuthSnapshot("user-a", 1, "token")) { true }
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue("refused by D24: $thrown", thrown is IOException)
        assertEquals("refused by the client's D24 (the transport's is open)", "D24 release admission is OFF", thrown?.message)
        assertEquals("nothing reached the server", 0, server.requestCount)
        assertEquals("nothing was recorded", emptyList<String>(), control.all().map { it.key })
    }

    // --- B01 -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `B01 cold start with a tab 401 and its replay stays inside the budget`() = runBlocking {
        val start = nowMillis()
        entitlements += unauthorized()
        val catalogDone = Held()
        onCatalog = { ok(fixture("catalog-krx-hidden.json")) }
        val tabCalls = AtomicInteger()
        onTab = {
            if (tabCalls.getAndIncrement() == 0) unauthorized()
            else {
                catalogDone.await()
                ok(fixture("usd-1d-krx-hidden.json"))
            }
        }
        val rig = Rig()
        rig.row(catalogDone) {
            rig.coldStart()
            awaitTrue("catalog adopted", start + ROW_DEADLINE_MS) { rig.catalogAdopted() }
            catalogDone.release()
            awaitTrue("the tab applied", start + ROW_DEADLINE_MS) {
                rig.assembly.coordinator.state.value.entries.containsKey(USD_1D)
            }
            rig.awaitTopics(start + ROW_DEADLINE_MS)
            quiet("B01", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        assertEquals(listOf(200), statuses(catalogs()))
        assertEquals("a 401 and its replay are two sends", listOf(401, 200), statuses(tabs()))
        assertTopicSide("B01")
        assertEquals(emptyList<Throwable>(), rig.failures)
        ColdStartBudget.assertWithin(judgeRow("B01"))
    }

    @Test
    fun `B01c cold start with a catalog 401 and its replay stays inside the budget`() = runBlocking {
        val start = nowMillis()
        entitlements += unauthorized()
        val catalogCalls = AtomicInteger()
        val catalogDone = Held()
        onCatalog = { if (catalogCalls.getAndIncrement() == 0) unauthorized() else ok(fixture("catalog-krx-hidden.json")) }
        onTab = {
            catalogDone.await()
            ok(fixture("usd-1d-krx-hidden.json"))
        }
        val rig = Rig()
        rig.row(catalogDone) {
            rig.coldStart()
            awaitTrue("catalog adopted", start + ROW_DEADLINE_MS) { rig.catalogAdopted() }
            catalogDone.release()
            awaitTrue("the tab applied", start + ROW_DEADLINE_MS) {
                rig.assembly.coordinator.state.value.entries.containsKey(USD_1D)
            }
            rig.awaitTopics(start + ROW_DEADLINE_MS)
            quiet("B01c", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        assertEquals("a 401 and its replay are two sends", listOf(401, 200), statuses(catalogs()))
        assertEquals(listOf(200), statuses(tabs()))
        val (firstCatalog, replay) = catalogs()
        assertTrue("the replay went before the held tab completed\n${sends.timeline()}", replay.start < checkNotNull(tabs().single().bodyEnd))
        assertTrue("the replay is immediate\n${sends.timeline()}", replay.start - checkNotNull(firstCatalog.end) < 1_000)
        assertTopicSide("B01c")
        assertEquals(emptyList<Throwable>(), rig.failures)
        ColdStartBudget.assertWithin(judgeRow("B01c"))
    }

    // --- B02 -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `B02 a failed catalog lets the tab go and the round asks for its catalog three seconds later`() = runBlocking {
        val start = nowMillis()
        entitlements += unauthorized()
        val catalogCalls = AtomicInteger()
        onCatalog = {
            if (catalogCalls.getAndIncrement() == 0) MockResponse().setResponseCode(503).setBody("""{"detail":"unavailable"}""")
            else ok(fixture("catalog-krx-hidden.json"))
        }
        onTab = { ok(fixture("usd-1d-krx-hidden.json")) }
        val rig = Rig()
        rig.row {
            rig.coldStart()
            awaitTrue("the round's catalog", start + ROW_DEADLINE_MS) { catalogs().size == 2 && catalogs()[1].bodyEnd != null }
            awaitTrue("catalog adopted", start + ROW_DEADLINE_MS) { rig.catalogAdopted() }
            rig.awaitTopics(start + ROW_DEADLINE_MS)
            quiet("B02", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        assertEquals(listOf(503, 200), statuses(catalogs()))
        assertEquals("no second tab: the round's demand is None once the catalog is adopted", listOf(200), statuses(tabs()))
        val tab = tabs().single()
        assertTrue("the round goes at least 3 s after the tab completed\n${sends.timeline()}",
            catalogs()[1].start >= checkNotNull(tab.bodyEnd) + 3_000 - 50)
        assertTopicSide("B02")
        assertEquals(emptyList<Throwable>(), rig.failures)
        ColdStartBudget.assertWithin(judgeRow("B02"))
    }

    // --- B03 -------------------------------------------------------------------------------------------------------------------

    /** A usd 1d tab from the fixture whose kb.usd holds a point at every closed 10-minute start of the 24 hours before now. */
    private fun satisfyingTab(): String {
        val json = NetworkModule.provideWireJson()
        val base = json.parseToJsonElement(fixture("usd-1d-krx-hidden.json")).jsonObject
        val nowEpoch = System.currentTimeMillis() / 1000
        val current = nowEpoch - nowEpoch % 600
        val starts = (1..144).map { current - it * 600L }.reversed()
        fun ts(epoch: Long) = TS.format(JavaInstant.ofEpochSecond(epoch).atOffset(KST))
        val points = JsonArray(starts.map { s ->
            buildJsonObject { put("high", 1390.0); put("low", 1390.0); put("rate", 1390.0); put("source", "kb"); put("ts", ts(s)) }
        })
        val series = JsonArray(base.getValue("series").jsonArray.map { element ->
            val obj = element.jsonObject
            if (obj.getValue("id").jsonPrimitive.content == "kb.usd") JsonObject(obj + ("data" to points)) else obj
        })
        val metadata = JsonObject(base.getValue("metadata").jsonObject + mapOf(
            "domain_start_at" to JsonPrimitive(ts(current - 86_400)),
            "domain_end_at" to JsonPrimitive(ts(current)),
            "fetched_at" to JsonPrimitive(ts(nowEpoch))
        ))
        return JsonObject(base + mapOf("series" to series, "metadata" to metadata)).toString()
    }

    private fun bucketOf(epochMillis: Long) = epochMillis / 600_000

    @Test
    fun `B03 a closed demand survives a partial 200 and the next round satisfies it`() = runBlocking {
        // Start with more than the row deadline to the next 10-minute boundary; the wait is outside the deadline.
        while (600_000 - System.currentTimeMillis() % 600_000 <= ROW_DEADLINE_MS + 5_000) delay(500)
        val bucket = bucketOf(System.currentTimeMillis())
        val start = nowMillis()
        entitlements += unauthorized()
        onCatalog = { ok(fixture("catalog-krx-hidden.json")) }
        val firstTabReleased = Held()
        val tabCalls = AtomicInteger()
        onTab = {
            when (tabCalls.getAndIncrement()) {
                0 -> {
                    firstTabReleased.await()
                    ok(fixture("usd-1d-krx-hidden.json"))
                }
                1 -> ok(fixture("usd-1d-krx-hidden.json")) // partial: no kb.usd point inside the retained window
                else -> ok(satisfyingTab())
            }
        }
        val rig = Rig()
        rig.row(firstTabReleased) {
            val fence = rig.coldStart()
            val lifetime = checkNotNull(rig.uses.acquire(fence))
            awaitTrue("catalog adopted", start + ROW_DEADLINE_MS) { rig.catalogAdopted() }
            val session = Any()
            fun quote(sequence: Long, at: Instant, use: TopicUseLifetime) = TopicGraphInput.Observations(
                sequence, "fx:usd-krw", TopicGraphPath.WS, TopicUseAttribution(session, fence, 1L, use), 1L,
                listOf(TopicGraphCandidate.Quote("kb", "usd-krw", 1390.0, at, null))
            )
            val now = Clock.System.now()
            rig.onMain { rig.assembly.recorder.observe(quote(1L, now, lifetime)) }
            // Refused at the gate (a lifetime the snapshot does not admit): the existing series keeps a loss in that past bucket.
            rig.onMain {
                rig.assembly.recorder.observe(quote(2L, now - 20.seconds * 60, lifetime.copy(invalidations = lifetime.invalidations + 1)))
            }
            val demand = rig.onMain { rig.assembly.recorder.recoveryDemand("usd", fence, lifetime, Clock.System.now()) }
            assertEquals("premise: a closed demand before the first tab completes",
                GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false), demand)
            firstTabReleased.release()
            awaitTrue("the partial applied", start + ROW_DEADLINE_MS) {
                tabs().size == 2 && tabs()[1].bodyEnd != null && USD_1D !in rig.assembly.coordinator.state.value.inFlight
            }
            assertEquals("the partial 200 is applied, not failed", null, rig.assembly.coordinator.state.value.failures[USD_1D])
            assertEquals("the closed demand survives the partial 200", GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false),
                rig.onMain { rig.assembly.recorder.recoveryDemand("usd", fence, lifetime, Clock.System.now()) })
            awaitTrue("three tabs", start + ROW_DEADLINE_MS) { tabs().size == 3 && tabs()[2].bodyEnd != null }
            awaitTrue("the demand is met", start + ROW_DEADLINE_MS) {
                rig.onMain { rig.assembly.recorder.recoveryDemand("usd", fence, lifetime, Clock.System.now()) } ==
                    GraphTabRecoveryDemand.None
            }
            rig.awaitTopics(start + ROW_DEADLINE_MS)
            quiet("B03", 5_000)
        }
        assertEquals("premise: the row stayed in one 10-minute bucket", bucket, bucketOf(System.currentTimeMillis()))
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        assertEquals(listOf(200), statuses(catalogs()))
        assertEquals(listOf(200, 200, 200), statuses(tabs()))
        val (first, partial, met) = tabs()
        assertTrue("the first round goes 3 s after the first tab completed\n${sends.timeline()}",
            partial.start >= checkNotNull(first.bodyEnd) + 3_000 - 50)
        assertTrue("the next round goes 6 s after the partial completed\n${sends.timeline()}",
            met.start >= checkNotNull(partial.bodyEnd) + 6_000 - 50)
        assertTopicSide("B03")
        assertEquals(emptyList<Throwable>(), rig.failures)
        ColdStartBudget.assertWithin(judgeRow("B03"))
    }

    // --- B04 -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `B04 a tab 429 with a long Retry-After holds every graph send through the window (floor preparation)`() = runBlocking {
        assertEquals("premise: J = 10 s, so a retry without the floor would land inside the window", 10.seconds,
            FreeSnapshotSchedulePolicy.jitterFor(SEED, "usd"))
        val start = nowMillis()
        entitlements += unauthorized()
        onCatalog = { ok(fixture("catalog-krx-hidden.json")) }
        val catalogDone = Held()
        onTab = {
            catalogDone.await()
            MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("""{"detail":"rate limited"}""")
        }
        val rig = Rig()
        rig.row(catalogDone) {
            rig.coldStart()
            awaitTrue("catalog adopted", start + ROW_DEADLINE_MS) { rig.catalogAdopted() }
            catalogDone.release()
            awaitTrue("the 429 answered", start + ROW_DEADLINE_MS) { tabs().singleOrNull()?.bodyEnd != null }
            val windowEnd = checkNotNull(tabs().single().bodyEnd) + 15_000
            rig.awaitTopics(start + ROW_DEADLINE_MS)
            quiet("B04", windowEnd - nowMillis())
        }
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        assertEquals(listOf(200), statuses(catalogs()))
        assertEquals("the injected 429 exactly once, and no retry inside the window", listOf(429), statuses(tabs()))
        assertTopicSide("B04")
        assertEquals(emptyList<Throwable>(), rig.failures)
        val verdict = judgeRow("B04 (floor preparation, not a budget pass)")
        // Floor preparation: the common 429 rule is not relaxed; the injected tab 429 is counted apart, the rest must be 0.
        assertEquals("only the injected tab answered 429", listOf("/api/v2/graph/tab"),
            sends.all().filter { it.status == 429 }.map { it.path })
        assertTrue("api excess ${verdict.apiExcessMilli}", verdict.apiExcessMilli <= ColdStartBudget.MAX_EXCESS_MILLI)
        assertTrue("ws excess ${verdict.wsExcessMilli}", verdict.wsExcessMilli <= ColdStartBudget.MAX_EXCESS_MILLI)
        assertEquals("the model refused a send", emptyList<Int>(), verdict.refused)
        assertTrue("api in flight ${verdict.apiInFlight}", verdict.apiInFlight <= ColdStartBudget.MAX_API_IN_FLIGHT)
    }

    // --- B05 -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `B05 a same-scope context change overlaps the old tab's body with the new tab`() = runBlocking {
        val start = nowMillis()
        entitlements += ok(PREMIUM_VISIBLE)
        val catalogCalls = AtomicInteger()
        onCatalog = {
            if (catalogCalls.getAndIncrement() == 0) ok(fixture("catalog-krx-visible.json")) else ok(fixture("catalog-krx-hidden.json"))
        }
        val tabCalls = AtomicInteger()
        onTab = {
            if (tabCalls.getAndIncrement() == 0) ok(fixture("usd-1d-krx-visible.json")).setBodyDelay(3, TimeUnit.SECONDS)
            else ok(fixture("usd-1d-krx-hidden.json"))
        }
        var f2At = Long.MAX_VALUE
        val rig = Rig()
        rig.row {
            val first = rig.coldStart()
            awaitTrue("catalog adopted", start + ROW_DEADLINE_MS) { rig.catalogAdopted() }
            awaitTrue("tab1's headers", start + ROW_DEADLINE_MS) { tabs().firstOrNull()?.end != null }
            // KRX rotation: the same user epoch, a new grant. The graph bridge is told nothing until F2.
            entitlements += ok(PREMIUM_HIDDEN)
            rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            val second = rig.issued()
            assertEquals("premise: the same user", first.identity.uid, second.identity.uid)
            assertEquals("premise: the same user epoch", first.userAccessEpoch, second.userAccessEpoch)
            assertNotEquals("premise: a new grant", first.grant, second.grant)
            f2At = nowMillis()
            rig.onMain { rig.assembly.fences.setAccess(true, second, TopicGrantOrigin.NewContext) }
            awaitTrue("tab2 sent", start + ROW_DEADLINE_MS) { tabs().size >= 2 }
            awaitTrue("tab1's body ended", start + ROW_DEADLINE_MS) { tabs()[0].bodyEnd != null }
            awaitTrue("the round's catalog", start + ROW_DEADLINE_MS) { catalogs().size == 2 && catalogs()[1].bodyEnd != null }
            rig.awaitTopics(start + ROW_DEADLINE_MS)
            quiet("B05", 5_000)
            val state = rig.assembly.coordinator.state.value
            assertEquals("the F2 source holds", second, state.source?.fence)
            val applied = checkNotNull(state.entries[USD_1D]) { "no usd 1d entry" }.tab.graph.series.map { it.seriesId }.toSet()
            val hidden = NetworkModule.provideWireJson().parseToJsonElement(fixture("usd-1d-krx-hidden.json")).jsonObject
                .getValue("series").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
            assertEquals("tab2's payload applied, tab1's never", hidden, applied)
        }
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        val (tab1, tab2) = tabs()
        assertTrue("tab1's headers came before tab2 went\n${sends.timeline()}", checkNotNull(tab1.end) <= tab2.start)
        assertTrue("tab2 went while tab1's body was still open\n${sends.timeline()}", tab2.start < checkNotNull(tab1.bodyEnd))
        assertTrue("tab2 went after F2 was handed over\n${sends.timeline()}", tab2.start >= f2At)
        assertEquals(listOf(200, 200), statuses(tabs()))
        assertEquals(listOf(200, 200), statuses(catalogs()))
        assertTrue("the round's catalog goes 3 s after tab2 completed\n${sends.timeline()}",
            catalogs()[1].start >= checkNotNull(tab2.bodyEnd) + 3_000 - 50)
        assertEquals(listOf(200, 200), statuses(sends.all().filter { it.path == "/api/entitlements" }))
        assertTrue("every desired topic", snapshotTopics().containsAll(DESIRED))
        assertTrue("a handshake", sends.all().any { it.zone == "ws" && it.status == 101 })
        assertEquals(emptyList<Throwable>(), rig.failures)
        ColdStartBudget.assertWithin(judgeRow("B05"))
    }

    // --- B06 -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `B06a an inactive graph sends nothing`() = runBlocking {
        val start = nowMillis()
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.coldStart(activate = false)
            rig.awaitTopics(start + ROW_DEADLINE_MS)
            quiet("B06a", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        assertEquals(emptyList<String>(), graph().map { it.key })
        assertTopicSide("B06a")
        assertEquals(emptyList<Throwable>(), rig.failures)
        ColdStartBudget.assertWithin(judgeRow("B06a"))
    }

    @Test
    fun `B06b without an owner an activated graph sends nothing`() = runBlocking {
        val start = nowMillis()
        val rig = Rig()
        rig.row {
            // An approved grant; then the token source loses its user before the graph starts: only the owner is missing.
            rig.approve()
            val fence = rig.issued()
            rig.source.identity = null
            assertEquals("premise: no owner", null, rig.provider.currentIdentityFence())
            assertTrue("premise: the grant's use is still admitted", rig.uses.acquire(fence) != null)
            rig.startGraph(fence, activate = true)
            assertEquals("premise: the bridge publishes the grant", fence, rig.assembly.fences.current())
            quiet("B06b", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        assertEquals("only the approval's entitlement reads", listOf("/api/entitlements?fresh_premium=true"),
            sends.all().map { it.key }.distinct())
        assertEquals(emptyList<String>(), graph().map { it.key })
        assertEquals(emptyList<Throwable>(), rig.failures)
        ColdStartBudget.assertWithin(judgeRow("B06b"))
    }

    @Test
    fun `B06c only the active usd key is fetched`() = runBlocking {
        val start = nowMillis()
        entitlements += unauthorized()
        onCatalog = { ok(fixture("catalog-krx-hidden.json")) }
        val catalogDone = Held()
        onTab = {
            catalogDone.await()
            ok(fixture("usd-1d-krx-hidden.json"))
        }
        val rig = Rig()
        rig.row(catalogDone) {
            rig.coldStart()
            awaitTrue("catalog adopted", start + ROW_DEADLINE_MS) { rig.catalogAdopted() }
            catalogDone.release()
            awaitTrue("the tab applied", start + ROW_DEADLINE_MS) {
                rig.assembly.coordinator.state.value.entries.containsKey(USD_1D)
            }
            rig.awaitTopics(start + ROW_DEADLINE_MS)
            quiet("B06c", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= ROW_DEADLINE_MS)
        assertEquals("the catalog is one shared call", 1, catalogs().size)
        assertEquals("only usd 1d", listOf("usd" to "1d"), tabs().map { it.param("tab") to it.param("period") })
        assertTopicSide("B06c")
        assertEquals(emptyList<Throwable>(), rig.failures)
        ColdStartBudget.assertWithin(judgeRow("B06c"))
    }
}
