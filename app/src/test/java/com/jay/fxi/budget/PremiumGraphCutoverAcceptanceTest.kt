package com.jay.fxi.budget

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jay.fxi.admission.ReleaseAdmissionInterceptor
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.ACCESS_EPOCH_STORE_NAME
import com.jay.fxi.data.entitlements.AccessEpochStore
import com.jay.fxi.data.entitlements.AuthenticatedEntitlementsSource
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.DeletionAdmissionStore
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.PremiumAccessTopicGrantIssuer
import com.jay.fxi.data.entitlements.ProbeJitter
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.UnimplementedScopePurger
import com.jay.fxi.data.entitlements.accessEpochCorruptionHandler
import com.jay.fxi.data.free.FreeSnapshotSchedulePolicy
import com.jay.fxi.data.free.InstallSeedSource
import com.jay.fxi.data.graph.AppProcessGraphBuilder
import com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo
import com.jay.fxi.data.graph.FileGraphV2DiskStore
import com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec
import com.jay.fxi.data.local.AppGraphCacheCutover
import com.jay.fxi.data.local.BackupableUserIntentStore
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.GraphConsumerReadiness
import com.jay.fxi.data.local.LocalMigrationJournal
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthTokenTopicCommandCredentials
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.C4OwnerHarness
import com.jay.fxi.data.remote.TopicBootstrapRetryFloor
import com.jay.fxi.data.remote.TopicCatalogue
import com.jay.fxi.data.remote.TopicCommandClock
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicForegroundStream
import com.jay.fxi.data.remote.TopicFrameDecoder
import com.jay.fxi.data.remote.TopicRuntimeFactory
import com.jay.fxi.data.remote.TopicRuntimeOwner
import com.jay.fxi.data.remote.TopicSnapshotBootstrapService
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.ui.premium.graph.GraphScreenMount
import com.jay.fxi.ui.premium.graph.GraphV2Content
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit

/**
 * S4 CUT-C01·C02 final acceptance (`cut_c0102_agreed.r1.md`): the premium cold start of the INT-b budget with the graph started
 * by the production path — the process topic owner with the production graph builder, screen host and graph cache migration,
 * the production runtime factory over the real issuer, deliverer fan-out, permit publisher, recovery events and write ports.
 * Test boundaries include Firebase, MockWebServer, temporary file locations, last-tab and row-preference substitutes,
 * and platform inputs (foreground, online, Main). Client and transport D24 admission lambdas are opened; a final network
 * recorder observes sends. The install seed and issuer/runtime jitter use fixed test values. The test
 * makes the calls the premium route makes: open the host, activate the selected usd holder for the screen's owner, report
 * its surface shown. Real clock throughout.
 *
 * Every row sums the graph's physical sends with the entitlement and topic sends and judges in-flight by body lifetime
 * (`ColdStartBudget.judge(bodyInFlight = true)`), with the INT-b notice. A row passes only inside its agreed deadline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumGraphCutoverAcceptanceTest {

    private companion object {
        val DESIRED = TopicCatalogue.DESIRED
        const val TETHER = TopicCatalogue.TETHER
        /** The INT-b seed: the production jitter formula gives usd its minimum, 10 seconds (INT-b J00). */
        const val SEED = "int-b-seed-4"
        val GRAPH = File("src/test/resources/contracts/v2/graph")
        const val NOTICE = "body 기준 수치는 클라이언트가 관측한 body 수명의 계량이며, close/실패 뒤 서버 작업까지 끝났다는 증거는 " +
            "아닙니다. per-host 5는 동일 Dispatcher의 비동기 호출 제한으로 표시하고, body 기준 in-flight ≤5의 증명으로 사용하지 않습니다."
        const val PREMIUM_HIDDEN = """{"krx_visible":false,"premium_active":true}"""
    }

    @get:Rule val folder = TemporaryFolder()

    private val origin = System.nanoTime()
    private fun nowMillis() = (System.nanoTime() - origin) / 1_000_000
    private val sends = SendRecorder(::nowMillis)
    private val accessOrders = AccessOrderSequence()
    private lateinit var server: MockWebServer
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "acceptance-main") }
    /** The platform's Main for this process: one real-time thread, always dispatching. */
    private val main = mainExecutor.asCoroutineDispatcher()

    // --- server scripts -------------------------------------------------------------------------------------------------------

    private val entitlements = ConcurrentLinkedQueue<MockResponse>()
    @Volatile private var onCatalog: (RecordedRequest) -> MockResponse = { ok(fixture("catalog-krx-hidden.json")) }
    @Volatile private var onTab: (RecordedRequest) -> MockResponse = { ok(fixture("usd-1d-krx-hidden.json")) }
    private val tetherCalls = AtomicInteger()
    private val wire = Json { ignoreUnknownKeys = true }
    /** While set, the usd topic's REST bootstrap answers a current kb quote (a real delivery); otherwise 404 as before. */
    @Volatile private var usdSnapshotLive = false

    private fun fixture(name: String) = File(GRAPH, name).readText()
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun unauthorized() = MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")

    /** RT01-A10: run on the same socket right after a refusal's acknowledgement was sent (before the client can act on it). */
    @Volatile private var afterRefusal: ((WebSocket) -> Unit)? = null
    /** While set, each subscription naming the usd topic is acknowledged with that topic refused, after this hook runs. */
    @Volatile private var refuseUsd: (() -> Unit)? = null
    /** When each WebSocket request reached the server (the client's Connection exists by then), in order. */
    private val wsArrivals = Collections.synchronizedList(mutableListOf<Long>())
    /** The next handshake's 101 is held this long once (0: none). */
    private val holdHandshakeMillis = java.util.concurrent.atomic.AtomicLong(0L)
    /** Every server-side socket opened, in order; the last is the live one. */
    private val openSockets = Collections.synchronizedList(mutableListOf<WebSocket>())
    private val refusals = AtomicInteger()
    /** While set, each entitlements answer waits for this gate (B1); [entitlementsHeld] counts the requests that waited. */
    @Volatile private var entitlementsGate: java.util.concurrent.CountDownLatch? = null
    private val entitlementsHeld = AtomicInteger()
    /** The account deletion route (B1 DeletionPending): 404 until a row scripts it. */
    @Volatile private var onDelete: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    /** Every topic named by a subscription the server received, in order (B06b). */
    private val subscribed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The server side of the topic socket: answers pings and acknowledges every subscription with all its topics active. */
    private val socketServer = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            openSockets += webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "ping") {
                webSocket.send("""{"type":"pong"}""")
                return
            }
            val request = runCatching { wire.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val id = request["request_id"]?.jsonPrimitive?.content ?: return
            val topics = request["topics"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            subscribed += topics
            val refuse = refuseUsd
            val usd = "fx:usd-krw"
            if (refuse != null && usd in topics) {
                refuse()
                refusals.incrementAndGet()
                webSocket.send(C4OwnerHarness.ack(id, topics - usd, mapOf(usd to "premium_required")))
                afterRefusal?.invoke(webSocket)
            } else {
                webSocket.send(C4OwnerHarness.ack(id, topics, emptyMap()))
            }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                received += SendRecorder.describe(url.encodedPath, url.queryParameterNames.associateWith { url.queryParameter(it) })
                return when (url.encodedPath) {
                    "/ws" -> {
                        wsArrivals += nowMillis()
                        holdHandshakeMillis.getAndSet(0L).takeIf { it > 0 }?.let { Thread.sleep(it) }
                        MockResponse().withWebSocketUpgrade(socketServer)
                    }
                    "/api/entitlements" -> {
                        // A held answer waits here, on the server's thread, until the test opens the gate (B1).
                        entitlementsGate?.let { gate -> entitlementsHeld.incrementAndGet(); gate.await(30, java.util.concurrent.TimeUnit.SECONDS) }
                        entitlements.poll() ?: ok(PREMIUM_HIDDEN)
                    }
                    "/api/user/me" -> onDelete(request)
                    "/api/v2/topics/snapshot" ->
                        if (url.queryParameter("topic") == "fx:usd-krw" && usdSnapshotLive) ok(usdSnapshot())
                        else if (url.queryParameter("topic") == TETHER && tetherCalls.getAndIncrement() == 0) unauthorized()
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
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    // --- the rig ---------------------------------------------------------------------------------------------------------------

    /** Firebase: the token source and the fence stream it drives. */
    private class Firebase(@Volatile var identity: AuthIdentity?) : AuthTokenSource, AuthFenceStream {
        @Volatile private var token = "old-token"
        private val observers = Collections.synchronizedList(mutableListOf<(AuthIdentityFence?) -> Unit>())
        /** B06d: once armed, the one identity read made inside the sink worker's recorder observe throws this instance. */
        val fault = java.util.concurrent.atomic.AtomicReference<RuntimeException?>(null)
        /** Where the armed fault was thrown: the thread and the top of its stack, one entry per throw. */
        val faultHits: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** Each instance the armed fault threw. */
        val faultThrown: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        override fun currentIdentity(): AuthIdentity? {
            fault.get()?.let { armed ->
                val frames = Thread.currentThread().stackTrace
                val inObserve = frames.any { it.className == "com.jay.fxi.data.graph.GraphRecorder" && it.methodName == "observe" }
                val inWorker = frames.any { it.className.startsWith("com.jay.fxi.data.graph.GraphRecorderTopicSink") }
                if (inObserve && inWorker && fault.compareAndSet(armed, null)) {
                    faultHits += Thread.currentThread().name + ": " +
                        frames.take(16).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                    faultThrown += armed
                    throw armed
                }
            }
            return identity
        }
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            if (forceRefresh) token = "fresh-token-${System.nanoTime()}"
            return token
        }
        override fun observe(onFence: (AuthIdentityFence?) -> Unit) {
            observers += onFence
            onFence(identity?.let { AuthIdentityFence(it.uid, it.authGeneration) })
        }

        /** The same user signed in again: the live identity moves to the next auth generation and every observer is told. */
        fun nextGeneration(): AuthIdentityFence {
            val next = checkNotNull(identity).let { it.copy(authGeneration = it.authGeneration + 1) }
            identity = next
            val fence = AuthIdentityFence(next.uid, next.authGeneration)
            synchronized(observers) { observers.toList() }.forEach { it(fence) }
            return fence
        }
    }

    /**
     * B1: a one-shot fault for one call path. [arm] takes the exception and the frame that must be on the caller's stack; the
     * first matching call takes it by compare-and-set and throws it, recording where; every other call goes through.
     */
    private class TargetedFault {
        private val armed = java.util.concurrent.atomic.AtomicReference<Pair<Exception, (StackTraceElement) -> Boolean>?>(null)
        /** B1 seal: while true, every matching call fails (until [disarm]); otherwise the first one only. */
        @Volatile private var persistent = false
        val hits: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val thrown: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val spent: Boolean get() = armed.get() == null

        fun arm(failure: Exception, frame: (StackTraceElement) -> Boolean) {
            check(armed.compareAndSet(null, failure to frame)) { "already armed" }
        }

        fun armPersistent(failure: Exception, frame: (StackTraceElement) -> Boolean) {
            persistent = true
            arm(failure, frame)
        }

        fun disarm() {
            persistent = false
            armed.set(null)
        }

        fun throwIfTargeted() {
            val current = armed.get() ?: return
            val frames = Thread.currentThread().stackTrace
            if (frames.any(current.second) && (persistent || armed.compareAndSet(current, null))) {
                hits += Thread.currentThread().name + ": " +
                    frames.take(18).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                thrown += current.first
                throw current.first
            }
        }
    }

    /** The stored last tab: usd, so the focus provider restores usd for the live identity. */
    private class Tabs(@Volatile var tab: FreeTab) : FreeTabStore {
        override suspend fun lastTab(uid: String): FreeTab = tab
        override suspend fun remember(uid: String, tab: FreeTab) {
            this.tab = tab
        }
    }

    /** The production provider's client with its D24 interceptor opened and the recorder last (INT-b S01). */
    private fun harnessClient(provider: AuthTokenProvider): OkHttpClient =
        NetworkModule.provideProtectedOkHttpClient(AuthSnapshotInterceptor(provider), TopicUseNetworkInterceptor(provider))
            .newBuilder()
            .apply {
                check(interceptors()[0] is ReleaseAdmissionInterceptor) { "premise: D24 is the first application interceptor" }
                interceptors()[0] = ReleaseAdmissionInterceptor(admitted = { true })
            }
            .addNetworkInterceptor(sends.interceptor)
            .build()

    private inner class Rig(identity: AuthIdentity? = AuthIdentity("user-a", 1), blockDisk: Boolean = false) {
        val firebase = Firebase(identity)
        val provider = AuthTokenProvider(firebase, orders = accessOrders)
        private val wireJson = NetworkModule.provideWireJson()
        val api = AuthenticatedApiClient(
            Retrofit.Builder().baseUrl(server.url("/")).client(harnessClient(provider))
                .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build()
                .create(AuthenticatedApiService::class.java),
            AuthenticatedTransport(provider, admitted = { true }),
            wireJson
        )
        val issuerJob = SupervisorJob()
        val runtimeJob = SupervisorJob()
        val ownerJob = SupervisorJob()
        val ioJob = SupervisorJob()
        /** Each time the issuer's store marks that graph data may exist (the write ports' preparation). */
        val marks = AtomicInteger()
        /** Whether the issuer can read the identity now (A03b holds it unreadable while a refusal is taken over). */
        @Volatile var issuerIdentityReadable = true
        private val io = CoroutineScope(ioJob + Dispatchers.IO)
        private val root = folder.newFolder()
        /**
         * The production epoch store over a temporary Preferences DataStore with its production corruption handler; the marking
         * is observed after the real call returns.
         */
        val epochStore: AccessEpochStore = DataStoreAccessEpochStore(
            PreferenceDataStoreFactory.create(corruptionHandler = accessEpochCorruptionHandler(EpochIdGenerator.Random), scope = io) {
                File(root, "$ACCESS_EPOCH_STORE_NAME.preferences_pb")
            },
            EpochIdGenerator.Random
        ).let { real ->
            object : AccessEpochStore by real {
                override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
                    real.markMayContainData(premium, krx).also { marks.incrementAndGet() }
                override suspend fun load(): com.jay.fxi.data.entitlements.AccessEpochRecord {
                    storeLoadFault.throwIfTargeted()
                    return real.load()
                }
                override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean): com.jay.fxi.data.entitlements.AccessEpochRecord {
                    storeRotationFault.throwIfTargeted()
                    return real.beginRotation(rotateUser, rotateKrx)
                }
            }
        }
        /** B1: the store's read and rotation write, each failing once on the call path a row arms; every other call is real. */
        val storeLoadFault = TargetedFault()
        val storeRotationFault = TargetedFault()
        /** B1: while false, only the issuer's candidate recovery reads no live identity; its answer decisions read it as usual. */
        @Volatile var recoveryIdentityReadable = true
        /** B1: how many candidate recovery identity reads that fault answered unreadable (the recovery's IdentityUnknown). */
        val recoveryIdentityDenials = AtomicInteger()
        /** The issuer on its production scope: a SupervisorJob on Default, independent of Main. */
        val coordinator = budgetCoordinator(api, provider, CoroutineScope(issuerJob + Dispatchers.Default), epochStore,
            { issuerIdentityReadable }, { recoveryIdentityReadable }, { recoveryIdentityDenials.incrementAndGet() })
        val uses = SnapshotTopicUseAuthority { coordinator.accessSnapshot }
        val tabs = Tabs(FreeTab.USD)
        val reports: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val migrationReports: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val foreground = MutableStateFlow(true)
        /** The platform's network input the owner forwards to the runtime. */
        val online = MutableStateFlow(true)
        /** The graph's disk root; a regular file in its place when [blockDisk], so every write fails. */
        val diskRoot = File(root, "graph_v2").also { if (blockDisk) it.writeText("not a directory") }
        val disk = FileGraphV2DiskStore({ diskRoot }, JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), Dispatchers.IO)
        val selections = BackupableUserIntentStore(PreferenceDataStoreFactory.create(scope = io) { File(root, "intent.preferences_pb") })
        val filesDir = File(root, "files").also { it.mkdirs() }
        val cutover = AppGraphCacheCutover(
            GraphConsumerReadiness(),
            LocalMigrationJournal(PreferenceDataStoreFactory.create(scope = io) { File(root, "journal.preferences_pb") }),
            filesDir, io, { migrationReports += it }
        )
        /** What the production builder built, kept for observation only; the builder itself is the production one. */
        @Volatile var parts: com.jay.fxi.data.graph.ProcessGraphParts? = null
        /** The process's one deletion admission store: the graph's protected admission and the account deletion stage share it (B1). */
        val deletions = DeletionAdmissionStore()
        /** Every grant result the deliverer pulled from the production issuer, in order, kept for observation only (B1). */
        val pulls: MutableList<com.jay.fxi.data.entitlements.TopicGrantResult> = Collections.synchronizedList(mutableListOf())
        /** The runtime's serial scope, from the factory's newScope, kept so a row can hold its executor (B1). */
        @Volatile var runtimeScope: CoroutineScope? = null
        /** How many times the graph boundary built; the production owner builds once per process. */
        val builds = AtomicInteger()
        /** The assembly's SupervisorJob: the parent's one new child across the production build, kept for observation only. */
        @Volatile var assemblyJob: Job? = null
        /** The runtime display flow the owner hands every new holder, kept for observation only (read off Main). */
        @Volatile var runtimeDisplay: StateFlow<TopicDisplayState>? = null
        /** What the production install seed source reads: the platform's seed file, replaced (B06a·b). */
        @Volatile var readSeed: () -> String = { SEED }
        /** The route's owner-following collector from [openScreen]; a recreated route ends it first (A01b). */
        var routeJob: Job? = null
        /** The permit slot the production builder was handed, kept for observation only. */
        @Volatile var permitSlot: com.jay.fxi.data.graph.LateBound<() -> com.jay.fxi.data.remote.TopicGraphRecoveryPermit?>? = null
        val production = AppProcessGraphBuilder(
            disk = Provider { disk },
            deletions = Provider { deletions },
            seeds = Provider { InstallSeedSource({ readSeed() }, Dispatchers.IO) },
            api = Provider { api },
            uses = Provider { uses },
            selections = Provider { selections },
            coordinator = coordinator,
            tokens = provider
        )
        val builder = com.jay.fxi.data.graph.ProcessGraphBuilder { permit, seed, timeEvent, parent ->
            permitSlot = permit
            builds.incrementAndGet()
            val before = parent.children.toSet()
            val built = production.build(permit, seed, timeEvent, parent)
            assemblyJob = (parent.children.toSet() - before).singleOrNull()
            // The holder factory stays the production one; the wrapper only keeps the display flow it is handed.
            com.jay.fxi.data.graph.ProcessGraphParts(built.assembly, built.seeds) { tab, display, focus, scope ->
                runtimeDisplay = display
                built.newHolder(tab, display, focus, scope)
            }.also { parts = it }
        }
        val assembly get() = checkNotNull(parts) { "no graph built" }.assembly
        lateinit var owner: TopicRuntimeOwner
        var mount: GraphScreenMount? = null

        /** S1 order: the issuer approves first (entitlements over this client). */
        suspend fun approve(intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM) {
            coordinator.onIdentityChanged(checkNotNull(provider.currentIdentityFence()))
            coordinator.refresh(intent)
            assertEquals("premise: a fresh premium approval", PremiumAccessState.PremiumConfirmed, coordinator.state.value.state)
        }

        /** The process owner, as the Application starts it once admitted, on Main. */
        suspend fun startOwner() {
            val service = TopicSnapshotBootstrapService(api, TopicFrameDecoder(wireJson), 10.seconds)
            val clock = object : TopicCommandClock {
                override fun nowMillis(): Long = this@PremiumGraphCutoverAcceptanceTest.nowMillis()
                override suspend fun sleep(duration: Duration) = delay(duration)
            }
            val ws = OkHttpClient.Builder().addInterceptor(sends.interceptor).build()
            val factory = TopicRuntimeFactory(
                webSocketFactory = { ws },
                webSocketUrl = server.url("/ws").toString(),
                decode = TopicFrameDecoder(wireJson)::decode,
                bootstrap = { fence, topic, useAdmitted -> service.bootstrap(fence, topic, useAdmitted) },
                issuer = PremiumAccessTopicGrantIssuer(coordinator).let { real ->
                    object : com.jay.fxi.data.entitlements.TopicGrantIssuer by real {
                        override suspend fun topicGrantResult() = real.topicGrantResult().also { pulls += it }
                    }
                },
                fences = firebase,
                liveFence = provider::currentIdentityFence,
                recoveries = provider,
                tabs = tabs,
                credentials = AuthTokenTopicCommandCredentials(provider),
                orders = accessOrders,
                authority = uses,
                clock = clock,
                newBootstrapFloor = { TopicBootstrapRetryFloor(it) { Clock.System.now() } },
                newScope = { CoroutineScope(runtimeJob + Dispatchers.Default.limitedParallelism(1)).also { runtimeScope = it } },
                encode = { wireJson.encodeToString(TopicSubscribeRequest.serializer(), it) },
                newRequestId = { java.util.UUID.randomUUID().toString() },
                jitter = { 0.0 }
            )
            owner = TopicRuntimeOwner(
                factory = factory,
                online = online,
                foreground = TopicForegroundStream { onForeground -> /* the process lifecycle */
                    CoroutineScope(ownerJob + main).launch { foreground.collect { onForeground(it) } }
                    onForeground(foreground.value)
                },
                fences = firebase,
                liveIdentity = provider::currentIdentityFence,
                rowPreferenceStore = object : RateRowPreferenceStore {
                    override suspend fun preferences(uid: String): Map<RateRowList, RateRowPreference> = emptyMap()
                    override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
                },
                main = CoroutineScope(ownerJob + main),
                graphBuilder = builder,
                reportGraph = { reports += it },
                graphCutover = { cutover }
            )
            withContext(main) { owner.start() }
        }

        /**
         * What the premium route does once the host is published: opens one mount, and once the usd holder is mounted and the
         * screen shows the usd tab for an owner, activates that holder for that owner.
         */
        suspend fun openScreen(deadline: Long): GraphV2ScreenStateHolder {
            awaitTrue("the host is published", deadline) { onMain { owner.graphHost.value != null } }
            onMain { mount = checkNotNull(owner.graphHost.value).open() }
            awaitTrue("the usd holder is mounted", deadline) { onMain { checkNotNull(mount).holders.value.containsKey("usd") } }
            awaitTrue("the screen shows usd for an owner", deadline) {
                onMain { owner.consumer.currentState().ui.let { it.owner != null && it.selectedTab == FreeTab.USD } }
            }
            return onMain {
                val holder = checkNotNull(mount).holders.value.getValue("usd")
                var active = checkNotNull(owner.consumer.currentState().ui.owner)
                holder.onActivated(active)
                // As the route does: a new screen owner moves the activation to it (deactivate, then activate).
                routeJob = CoroutineScope(ownerJob + main).launch {
                    owner.consumer.state.collect {
                        val next = owner.consumer.currentState().ui.owner ?: return@collect
                        if (next != active) {
                            holder.onDeactivated()
                            active = next
                            holder.onActivated(next)
                        }
                    }
                }
                holder
            }
        }

        /** The inline surface reports itself shown, as its lifecycle start effect does, once the holder has a token. */
        suspend fun showSurface(holder: GraphV2ScreenStateHolder, deadline: Long) {
            awaitTrue("the holder has an inline token", deadline) { onMain { holder.currentState().inlineToken != null } }
            onMain { holder.setSurfaceVisible(checkNotNull(holder.currentState().inlineToken), true) }
        }

        suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }

        /**
         * Ends everything this rig started, in order: the mount closed and each mounted holder's close completed, the graph's
         * assembly closed on Main from a live caller (not by cancelling its parent), then the runtime, the issuer and the
         * stores, and last every REST body.
         */
        suspend fun finish() {
            val mounted = onMain { mount?.holders?.value?.values?.toList().orEmpty().also { mount?.close() } }
            withContext(main) { mounted.forEach { it.close() } }
            parts?.let { built -> withContext(main) { built.assembly.close() } }
            ioJob.children.forEach { it.join() }
            ownerJob.cancelAndJoin()
            runtimeJob.cancelAndJoin()
            issuerJob.cancelAndJoin()
            ioJob.cancelAndJoin()
            awaitTrue("every REST body ended", nowMillis() + 3_000) {
                sends.all().filter { it.zone == "api" }.all { it.bodyEnd != null }
            }
        }
    }

    private fun budgetCoordinator(
        api: AuthenticatedApiClient,
        provider: AuthTokenProvider,
        scope: CoroutineScope,
        store: AccessEpochStore,
        identityReadable: () -> Boolean,
        recoveryIdentityReadable: () -> Boolean = { true },
        onRecoveryDenied: () -> Unit = {}
    ): PremiumAccessCoordinator {
        // The production binding for both purgers: it answers Deferred, so every journal entry stays owed.
        val purger = UnimplementedScopePurger()
        return PremiumAccessCoordinator(
            // The production source; only its identity read can be held unreadable, as Firebase can be for the issuer alone.
            source = AuthenticatedEntitlementsSource(api).let { real ->
                object : com.jay.fxi.data.entitlements.EntitlementsSource {
                    override suspend fun fetch(freshPremium: Boolean) = real.fetch(freshPremium)
                    override suspend fun currentIdentity() = when {
                        !identityReadable() -> null
                        // Read before any suspension, so the stack is the caller's: the candidate recovery's own check only.
                        !recoveryIdentityReadable() &&
                            Thread.currentThread().stackTrace.any { it.methodName == "checkCandidateLocked" } -> null.also { onRecoveryDenied() }
                        else -> real.currentIdentity()
                    }
                }
            },
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

    private suspend fun Rig.row(body: suspend () -> Unit) {
        try {
            body()
        } catch (failure: Throwable) {
            runCatching { finish() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        finish()
    }

    private fun graph(): List<SendRecorder.Exchange> = sends.all().filter { it.path.startsWith("/api/v2/graph/") }
    private fun catalogs() = graph().filter { it.path == "/api/v2/graph/catalog" }
    private fun tabs() = graph().filter { it.path == "/api/v2/graph/tab" }
    private fun snapshotTopics(): Set<String> = sends.all().mapNotNull { it.param("topic") }.toSet()
    private fun statuses(list: List<SendRecorder.Exchange>) = list.map { it.status }

    private suspend fun quiet(label: String, millis: Long) {
        val before = graph().size
        val until = nowMillis() + millis
        while (nowMillis() < until) {
            assertEquals("$label: no further graph send\n${sends.timeline()}", before, graph().size)
            delay(50)
        }
    }

    private fun judgeRow(label: String): ColdStartBudget.Verdict {
        val all = sends.all()
        assertEquals("$label: the recorder and the server disagree", synchronized(received) { received.toList() }.sorted(),
            all.map { it.key }.sorted())
        val verdict = ColdStartBudget.judge(all, bodyInFlight = true)
        println("CUT-C02 $label timeline: ${sends.timeline()}")
        println("CUT-C02 $label verdict: $verdict")
        println("CUT-C02 $label: $NOTICE")
        println("CUT-C02 $label differences from production: client D24 lambda, transport D24 lambda, the recorder (last network interceptor)")
        return verdict
    }

    /** A cold row up to the graph's first sends: approval, the owner, the screen on usd 1d. */
    private suspend fun Rig.coldStart(deadline: Long, activate: Boolean = true): GraphV2ScreenStateHolder? {
        approve()
        startOwner()
        if (!activate) return null
        val holder = openScreen(deadline)
        showSurface(holder, deadline)
        return holder
    }

    private suspend fun Rig.awaitTopics(deadline: Long) =
        awaitTrue("topics and handshake", deadline) { snapshotTopics() == DESIRED && sends.all().any { it.zone == "ws" } }

    private fun Rig.catalogAdopted(): Boolean = assembly.coordinator.state.value.catalog != null
    private fun Rig.usdApplied(): Boolean = assembly.coordinator.state.value.entries.keys.any { it.tab == "usd" && it.period.code == "1d" }

    /** The topic side every authenticated row expects: the entitlement sends, every desired topic, one handshake. */
    private fun assertTopicSide(label: String, entitlementStatuses: List<Int?> = listOf(401, 200)) {
        val all = sends.all()
        assertEquals("$label: entitlements", entitlementStatuses, statuses(all.filter { it.path == "/api/entitlements" }))
        assertEquals("$label: every desired topic", DESIRED, snapshotTopics())
        assertTrue("$label: a handshake", all.any { it.zone == "ws" && it.status == 101 })
    }

    // --- A01 -------------------------------------------------------------------------------------------------------------------

    /**
     * A01: the production cold start with a first tab 401 and its replay, through the deliverer's fence, the restored usd focus
     * and the screen's activation, stays inside the budget; the chart is published and nothing more is sent for 5 s.
     */
    @Test
    fun `A01 the production cold start with a tab 401 and its replay stays inside the budget`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val tabCalls = AtomicInteger()
        onTab = { if (tabCalls.getAndIncrement() == 0) unauthorized() else ok(fixture("usd-1d-krx-hidden.json")) }
        val rig = Rig()
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            awaitTrue("the chart is published", deadline) { rig.onMain { holder.currentState().content == GraphV2Content.READY } }
            rig.awaitTopics(deadline)
            quiet("A01", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        assertEquals(listOf(200), statuses(catalogs()))
        assertEquals("a 401 and its replay are two sends", listOf(401, 200), statuses(tabs()))
        assertEquals("no graph failure reported", emptyList<Throwable>(), rig.reports)
        ColdStartBudget.assertWithin(judgeRow("A01"))
    }

    /** A01c: the same cold start with a catalog 401 and its immediate replay. */
    @Test
    fun `A01c the production cold start with a catalog 401 and its replay stays inside the budget`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val catalogCalls = AtomicInteger()
        onCatalog = { if (catalogCalls.getAndIncrement() == 0) unauthorized() else ok(fixture("catalog-krx-hidden.json")) }
        val rig = Rig()
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            awaitTrue("the chart is published", deadline) { rig.onMain { holder.currentState().content == GraphV2Content.READY } }
            rig.awaitTopics(deadline)
            quiet("A01c", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        assertEquals("a 401 and its replay are two sends", listOf(401, 200), statuses(catalogs()))
        assertEquals(listOf(200), statuses(tabs()))
        val (first, replay) = catalogs()
        assertTrue("the replay is immediate\n${sends.timeline()}", replay.start - checkNotNull(first.end) < 1_000)
        assertTopicSide("A01c")
        assertEquals(emptyList<Throwable>(), rig.reports)
        ColdStartBudget.assertWithin(judgeRow("A01c"))
    }

    /** A06: a tab 429 with Retry-After 120 holds every graph send for 15 s after its body ended (floor preparation). */
    @Test
    fun `A06 a tab 429 with a long Retry-After holds every graph send through the window (floor preparation)`() = runBlocking {
        assertEquals("premise: J = 10 s, so a retry without the floor would land inside the window", 10.seconds,
            FreeSnapshotSchedulePolicy.jitterFor(SEED, "usd"))
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        onTab = { MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("""{"detail":"rate limited"}""") }
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            awaitTrue("the 429 answered", deadline) { tabs().singleOrNull()?.bodyEnd != null }
            val windowEnd = checkNotNull(tabs().single().bodyEnd) + 15_000
            rig.awaitTopics(deadline)
            quiet("A06", windowEnd - nowMillis())
        }
        assertEquals(listOf(200), statuses(catalogs()))
        assertEquals("the injected 429 exactly once, and no retry inside the window", listOf(429), statuses(tabs()))
        assertTopicSide("A06")
        assertEquals(emptyList<Throwable>(), rig.reports)
        val verdict = judgeRow("A06 (floor preparation, not a budget pass)")
        assertEquals("only the injected tab answered 429", listOf("/api/v2/graph/tab"), sends.all().filter { it.status == 429 }.map { it.path })
        assertTrue("api excess ${verdict.apiExcessMilli}", verdict.apiExcessMilli <= ColdStartBudget.MAX_EXCESS_MILLI)
        assertTrue("ws excess ${verdict.wsExcessMilli}", verdict.wsExcessMilli <= ColdStartBudget.MAX_EXCESS_MILLI)
        assertEquals("the model refused a send", emptyList<Int>(), verdict.refused)
        assertTrue("api in flight ${verdict.apiInFlight}", verdict.apiInFlight <= ColdStartBudget.MAX_API_IN_FLIGHT)
    }

    /** A07a: the host published with no screen opened sends no graph read. */
    @Test
    fun `A07a no screen opened sends nothing`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline, activate = false)
            awaitTrue("the host is published", deadline) { rig.onMain { rig.owner.graphHost.value != null } }
            rig.awaitTopics(deadline)
            quiet("A07a", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        assertEquals(emptyList<String>(), graph().map { it.key })
        assertTopicSide("A07a")
        ColdStartBudget.assertWithin(judgeRow("A07a"))
    }

    /** A07b: the screen open on News (restored tab) activates no holder and sends no graph read. */
    @Test
    fun `A07b the screen on News sends nothing`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.tabs.tab = FreeTab.NEWS
        rig.row {
            rig.coldStart(deadline, activate = false)
            awaitTrue("the host is published", deadline) { rig.onMain { rig.owner.graphHost.value != null } }
            rig.onMain { rig.mount = checkNotNull(rig.owner.graphHost.value).open() }
            awaitTrue("the screen shows News for an owner", deadline) {
                rig.onMain { rig.owner.consumer.currentState().ui.let { it.owner != null && it.selectedTab == FreeTab.NEWS } }
            }
            rig.awaitTopics(deadline)
            quiet("A07b", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        assertEquals(emptyList<String>(), graph().map { it.key })
        assertTopicSide("A07b")
        ColdStartBudget.assertWithin(judgeRow("A07b"))
    }

    /** A07c: with every holder mounted and usd active, only the usd key is fetched; the catalog is one shared call. */
    @Test
    fun `A07c only the active usd key is fetched`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            assertEquals("premise: every FX holder mounted", setOf("usd", "jpy", "eur"), rig.onMain { checkNotNull(rig.mount).holders.value.keys })
            awaitTrue("the tab applied", deadline) { rig.usdApplied() }
            rig.awaitTopics(deadline)
            quiet("A07c", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        assertEquals("the catalog is one shared call", 1, catalogs().size)
        assertEquals("only usd 1d", listOf("usd" to "1d"), tabs().map { it.param("tab") to it.param("period") })
        assertTopicSide("A07c")
        ColdStartBudget.assertWithin(judgeRow("A07c"))
    }

    /** A08: a failed first catalog lets the tab go; the round asks for its catalog 3 s later and sends no needless tab. */
    @Test
    fun `A08 a failed catalog lets the tab go and the round adopts its catalog with no needless tab`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val catalogCalls = AtomicInteger()
        onCatalog = {
            if (catalogCalls.getAndIncrement() == 0) MockResponse().setResponseCode(503).setBody("""{"detail":"unavailable"}""")
            else ok(fixture("catalog-krx-hidden.json"))
        }
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            awaitTrue("the round's catalog", deadline) { catalogs().size == 2 && catalogs()[1].bodyEnd != null }
            awaitTrue("catalog adopted", deadline) { rig.catalogAdopted() }
            rig.awaitTopics(deadline)
            quiet("A08", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        assertEquals(listOf(503, 200), statuses(catalogs()))
        assertEquals("no second tab: the round's demand is None once the catalog is adopted", listOf(200), statuses(tabs()))
        assertTrue("the round goes at least 3 s after the tab completed\n${sends.timeline()}",
            catalogs()[1].start >= checkNotNull(tabs().single().bodyEnd) + 3_000 - 50)
        assertTopicSide("A08")
        ColdStartBudget.assertWithin(judgeRow("A08"))
    }

    /**
     * A10: a same-user-epoch grant rotation by the real issuer while the first tab's body is held: the deliverer hands the
     * new context over, the new tab goes while the old body is still open, both are counted, and only the new one applies.
     */
    @Test
    fun `A10 a same-scope context change from the issuer overlaps the old tab's body with the new tab`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += ok("""{"krx_visible":true,"premium_active":true}""")
        val catalogCalls = AtomicInteger()
        onCatalog = { if (catalogCalls.getAndIncrement() == 0) ok(fixture("catalog-krx-visible.json")) else ok(fixture("catalog-krx-hidden.json")) }
        val tabCalls = AtomicInteger()
        onTab = {
            if (tabCalls.getAndIncrement() == 0) ok(fixture("usd-1d-krx-visible.json")).setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
            else ok(fixture("usd-1d-krx-hidden.json"))
        }
        var rotatedAt = Long.MAX_VALUE
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            val first = checkNotNull(rig.coordinator.topicGrantResult().fence)
            awaitTrue("tab1's headers", deadline) { tabs().firstOrNull()?.end != null }
            entitlements += ok(PREMIUM_HIDDEN)
            val recordBefore = rig.epochStore.load()
            rotatedAt = nowMillis()
            rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
            val second = checkNotNull(rig.coordinator.topicGrantResult().fence)
            assertEquals("premise: the same user epoch", first.userAccessEpoch, second.userAccessEpoch)
            assertTrue("premise: a new grant", first.grant != second.grant)
            awaitTrue("tab2 sent", deadline) { tabs().size >= 2 }
            awaitTrue("tab1's body ended", deadline) { tabs()[0].bodyEnd != null }
            awaitTrue("the new context's tab applied", deadline) {
                rig.assembly.coordinator.state.value.source?.fence == second && rig.usdApplied() && tabs().all { it.bodyEnd != null }
            }
            rig.awaitTopics(deadline)
            quiet("A10", 5_000)
            val applied = rig.assembly.coordinator.state.value.entries.values.single().tab.graph.series.map { it.seriesId }.toSet()
            val hidden = NetworkModule.provideWireJson().parseToJsonElement(fixture("usd-1d-krx-hidden.json")).jsonObject
                .getValue("series").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
            assertEquals("tab2's payload applied, tab1's never", hidden, applied)
            // The production purger defers, so the capability rotation's journal entry is still owed at the end of the row.
            val recordAfter = rig.epochStore.load()
            val owed = { record: com.jay.fxi.data.entitlements.AccessEpochRecord ->
                record.pendingPurges.filter { PurgeScope.CAPABILITY in it.scopes && it.krxCapabilityEpoch == recordBefore.krxCapabilityEpoch }
            }
            assertTrue("the capability epoch rotated (before $recordBefore, after $recordAfter)",
                recordAfter.krxCapabilityEpoch != recordBefore.krxCapabilityEpoch)
            assertEquals("the rotation's capability entry was not owed before it (before $recordBefore)", 0, owed(recordBefore).size)
            assertEquals("the rotation's capability entry is still owed (after $recordAfter)", 1, owed(recordAfter).size)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        val (tab1, tab2) = tabs()
        assertTrue("tab2 went while tab1's body was still open\n${sends.timeline()}", tab2.start < checkNotNull(tab1.bodyEnd))
        assertTrue("tab2 went after the rotation\n${sends.timeline()}", tab2.start >= rotatedAt)
        assertEquals(listOf(200, 200), statuses(tabs()))
        ColdStartBudget.assertWithin(judgeRow("A10"))
    }

    // --- CUT-C01 precedence: A01b, A02, B06a-d (cut_c01_pre_agreed.r1.md) ----------------------------------------

    /**
     * A01b (RT01-A01, cut_c01_pre_agreed.r1.md P01): one process graph. A repeated owner start, a recreated route (the old
     * route's collector ended, its mount and holders closed) and tab moves through the real selection path (usd → jpy → usd)
     * keep one build, one assembly child and the same host, parts, recorder, sink and coordinator; closing the screen leaves
     * the recorder's data, readability and the catalog in place, and the reopened screen's recorder takes the next WS value.
     */
    @Test
    fun `A01b a repeated start, a recreated route and tab moves keep one process graph and the closed screen leaves the recorder open`() = runBlocking {
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 A01b: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 45_000
        onTab = { request ->
            if (request.requestUrl!!.queryParameter("tab") == "jpy") ok(jpyTab()) else ok(fixture("usd-1d-krx-hidden.json"))
        }
        val rig = Rig()
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            rig.awaitTopics(deadline)
            awaitTrue("catalog and the usd tab applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
            awaitTrue("an acknowledged socket", deadline) { openSockets.isNotEmpty() }
            openSockets.last().send(usdSnapshot(1390.0))
            awaitTrue("the WS delivery recorded kb.usd", deadline) { rig.hasKbSeries() }
            awaitTrue("the usd chart is shown", deadline) { rig.onMain { holder.currentState().chart != null } }
            val first = rig.instances()
            assertEquals("one build", 1, rig.builds.get())
            val assemblyJob = checkNotNull(rig.assemblyJob) { "the build added exactly one child to the owner's job" }

            // (a) a repeated start does nothing.
            rig.onMain { rig.owner.start() }
            assertEquals("a repeated start builds nothing", 1, rig.builds.get())
            assertEquals("a repeated start keeps every instance", first, rig.instances())

            // (b) a recreated route: the old route's collector ended, its mount closed and each holder's close completed.
            checkNotNull(rig.routeJob).cancelAndJoin()
            val closing = rig.onMain { checkNotNull(rig.mount).let { m -> m.holders.value.values.toList().also { m.close() } } }
            // The mount's close itself takes its holders out of the host and closes them. The test waits until the host has
            // started each close, then joins it (close() on a closed holder only waits for its completion).
            val stillMounted = rig.onMain { checkNotNull(rig.owner.graphHost.value).holders() }
            assertTrue("the mount's close took its holders out of the host", closing.none { it in stillMounted })
            awaitTrue("the host closed each old holder", deadline) { rig.onMain { closing.all { privateFlag(it, "closed") } } }
            withContext(main) { closing.forEach { it.close() } }
            val fence = checkNotNull(rig.coordinator.topicGrantResult().fence)
            val lifetime = checkNotNull(rig.uses.acquire(fence))
            rig.onMain {
                val recorder = rig.assembly.recorder
                assertTrue("the closed screen left kb.usd in the recorder", recorder.state.value.series.keys.any { it.seriesId == "kb.usd" })
                assertNotEquals("the recorder is still readable", com.jay.fxi.data.graph.GraphTabRecoveryDemand.Unreadable,
                    recorder.recoveryDemand("usd", fence, lifetime, kotlinx.datetime.Clock.System.now()))
                assertTrue("the recorder still exposes its data", recorder.exposed(fence, lifetime).isNotEmpty())
                assertNotNull("the catalog is kept", rig.assembly.coordinator.state.value.catalog)
            }
            val reopened = rig.openScreen(deadline)
            rig.showSurface(reopened, deadline)
            assertNotSame("the recreated route has a new holder", holder, reopened)
            assertEquals("premise: kb.usd's tip is the first snapshot", 1390.0, rig.kbTipRate())
            delay(1_100) // the next snapshot's second-resolution timestamp is later than the first
            openSockets.last().send(usdSnapshot(1391.0))
            awaitTrue("the process recorder took the next WS value", deadline) { rig.kbTipRate() == 1391.0 }

            // (c) tab moves through the real selection path.
            checkNotNull(rig.routeJob).cancelAndJoin()
            val jpy = rig.moveTo(FreeTab.JPY, from = reopened, deadline)
            awaitTrue("the jpy tab applied", deadline) {
                rig.assembly.coordinator.state.value.entries.keys.any { it.tab == "jpy" } && tabs().all { it.bodyEnd != null }
            }
            rig.moveTo(FreeTab.USD, from = jpy, deadline)
            assertEquals("tab moves build nothing", 1, rig.builds.get())
            assertEquals("tab moves keep every instance", first, rig.instances())
            assertTrue("the assembly is still running", assemblyJob.isActive)
            quiet("A01b", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 45_000)
        assertEquals(emptyList<Throwable>(), rig.reports)
        ColdStartBudget.assertWithin(judgeRow("A01b"))
    }

    private data class Instances(val host: Any?, val parts: Any?, val assembly: Any, val recorder: Any, val sink: Any, val coordinator: Any) {
        override fun equals(other: Any?) = other is Instances && host === other.host && parts === other.parts &&
            assembly === other.assembly && recorder === other.recorder && sink === other.sink && coordinator === other.coordinator
        override fun hashCode() = System.identityHashCode(assembly)
    }
    private suspend fun Rig.instances() = onMain {
        Instances(owner.graphHost.value, parts, assembly, assembly.recorder, assembly.sink, assembly.coordinator)
    }

    /**
     * A02 (RT01-A02, P02 — PARTIAL by agreement: purge waits for the production purge wiring). Observed publications of the
     * recorder, the graph coordinator and the usd holder after real inputs (a WS snapshot, a series toggle) all come from
     * Main; fresh reads on Main read the holder and the recorder; and a WS snapshot the runtime has taken off Main — its quote
     * already in the runtime's own display — leaves the recorder unchanged while Main is held, and lands once Main is free.
     */
    @Test
    fun `A02 graph publications and fresh reads run on Main and the topic reaches the recorder only through the sink queue`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 30_000
        onTab = { request ->
            if (request.requestUrl!!.queryParameter("period") == "3m") ok(fixture("usd-3m-krx-hidden.json")) else ok(fixture("usd-1d-krx-hidden.json"))
        }
        val rig = Rig()
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            rig.awaitTopics(deadline)
            awaitTrue("catalog and the usd tab applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
            awaitTrue("an acknowledged socket", deadline) { openSockets.isNotEmpty() }
            openSockets.last().send(usdSnapshot(1390.0))
            awaitTrue("the WS delivery recorded kb.usd", deadline) { rig.hasKbSeries() }
            awaitTrue("the usd chart is shown", deadline) { rig.onMain { holder.currentState().chart != null } }
            val display = checkNotNull(rig.runtimeDisplay) { "the holder factory was handed the runtime display" }

            // Each collector records, without suspending, the thread a publication resumed it on and the value it carried; the
            // initial replay is dropped. Inputs are paired with the publications carrying their effect, by value.
            val recorderSeen = Collections.synchronizedList(mutableListOf<Pair<String, Double?>>())
            val coordinatorSeen = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
            val holderSeen = Collections.synchronizedList(mutableListOf<Pair<String, Boolean?>>())
            val kbSelected = { state: com.jay.fxi.ui.premium.graph.GraphV2ScreenState -> state.toggles.firstOrNull { it.seriesId == "kb.usd" }?.selected }
            val has3m = { rig.assembly.coordinator.state.value.entries.keys.any { it.tab == "usd" && it.period.code == "3m" } }
            val observers = rig.onMain {
                val scope = CoroutineScope(rig.ownerJob + Dispatchers.Unconfined)
                listOf(
                    scope.launch { rig.assembly.recorder.state.drop(1).collect { recorderSeen += threadName() to kbTipRate(it) } },
                    scope.launch {
                        rig.assembly.coordinator.state.drop(1).collect { c ->
                            coordinatorSeen += threadName() to c.entries.keys.any { it.tab == "usd" && it.period.code == "3m" }
                        }
                    },
                    scope.launch { holder.state.drop(1).collect { holderSeen += threadName() to kbSelected(it) } }
                )
            }
            try {
                // A WS snapshot: the recorder publication that carries its value.
                delay(1_100)
                openSockets.last().send(usdSnapshot(1391.0))
                awaitTrue("the snapshot's recorder publication", deadline) { recorderSeen.any { it.second == 1391.0 } }

                // A series toggle: a real selection change, then the holder publication that carries it. Read from the published
                // state off Main: currentState() would itself publish.
                val published = holder.state.value
                val token = checkNotNull(published.inlineToken)
                val selected = checkNotNull(kbSelected(published))
                rig.onMain { holder.toggleSeries(token, "kb.usd") }
                awaitTrue("the holder published the changed selection", deadline) { holderSeen.any { it.second == !selected } }

                // A period change: the graph coordinator's request and the publication that carries it.
                rig.onMain { holder.selectPeriod(token, GraphPeriod.THREE_MONTHS) }
                awaitTrue("the 3m tab applied", deadline) { has3m() && tabs().all { it.bodyEnd != null } }
                awaitTrue("the coordinator published it", deadline) { coordinatorSeen.any { it.second } }

                // Fresh reads on Main.
                val fence = checkNotNull(rig.coordinator.topicGrantResult().fence)
                val lifetime = checkNotNull(rig.uses.acquire(fence))
                rig.onMain {
                    assertEquals("fresh reads run on Main", "acceptance-main", threadName())
                    assertNotNull("the holder's fresh state has a chart", holder.currentState().chart)
                    assertTrue("the recorder exposes kb.usd", rig.assembly.recorder.exposed(fence, lifetime).keys.any { it.seriesId == "kb.usd" })
                    assertNotEquals("the recorder is readable", com.jay.fxi.data.graph.GraphTabRecoveryDemand.Unreadable,
                        rig.assembly.recorder.recoveryDemand("usd", fence, lifetime, kotlinx.datetime.Clock.System.now()))
                }

                // The queue boundary: Main held, the runtime takes the snapshot, the recorder waits for Main.
                val entered = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                delay(1_100)
                mainExecutor.execute { entered.countDown(); release.await() }
                try {
                    assertTrue("Main is held", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val recorderBefore = rig.assembly.recorder.state.value
                    assertEquals("premise: the recorder holds the earlier snapshot", 1391.0, kbTipRate(recorderBefore))
                    openSockets.last().send(usdSnapshot(1392.0))
                    awaitTrue("the runtime display shows the new quote while Main is held", deadline) { kbUsdShown(display, 1392.0) }
                    assertSame("the recorder state is unchanged while Main is held",
                        recorderBefore, rig.assembly.recorder.state.value)
                    assertEquals("the recorder has not taken it while Main is held", 1391.0, rig.kbTipRate())
                } finally {
                    release.countDown()
                }
                awaitTrue("the recorder takes it once Main is free", deadline) { recorderSeen.any { it.second == 1392.0 } }
            } finally {
                observers.forEach { it.cancelAndJoin() }
            }
            for ((name, seen) in listOf("recorder" to recorderSeen.map { it.first }, "coordinator" to coordinatorSeen.map { it.first },
                "holder" to holderSeen.map { it.first })) {
                assertEquals("every observed $name publication came from Main: $seen", setOf("acceptance-main"), seen.toSet())
            }
            quiet("A02", 3_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 30_000)
        ColdStartBudget.assertWithin(judgeRow("A02"))
    }

    /**
     * B06a (RT01-B06, P03): a cancellation before the graph starts. The owner's Main scope is cancelled while the production
     * install seed source's read is held on IO (before the assembly starts); the read is released by the live test caller and
     * really ends. The starter's cleanup closes the assembly: no host, no report (a cancellation), the recorder unreadable, no
     * graph send.
     */
    @Test
    fun `B06a a cancellation before the graph starts closes the assembly with no host, no report and no graph send`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        val rig = Rig()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val ended = java.util.concurrent.CountDownLatch(1)
        rig.readSeed = {
            entered.countDown()
            try { release.await(15, java.util.concurrent.TimeUnit.SECONDS) } finally { ended.countDown() }
            SEED
        }
        rig.row {
            rig.coldStart(deadline, activate = false)
            try {
                awaitTrue("the seed read is held", deadline) { entered.count == 0L }
                val assemblyJob = checkNotNull(rig.assemblyJob)
                assertNull("no host before the start", rig.onMain { rig.owner.graphHost.value })
                rig.ownerJob.cancel()
            } finally {
                release.countDown()
            }
            awaitTrue("the seed read ended", deadline) { ended.count == 0L }
            rig.ownerJob.join()
            val assemblyJob = checkNotNull(rig.assemblyJob)
            awaitTrue("the assembly's job completed", deadline) { assemblyJob.isCompleted }
            assertNull("no host", rig.onMain { rig.owner.graphHost.value })
            assertEquals("a cancellation is not reported", emptyList<Throwable>(), rig.reports)
            rig.assertRecorderClosed()
            assertEquals("no graph send", emptyList<SendRecorder.Exchange>(), graph())
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        ColdStartBudget.assertWithin(judgeRow("B06a"))
    }

    /**
     * B06b (P03, the start-failure auxiliary row — not RT01-B06's worker failure): the production install seed source's read
     * throws one IOException. It is reported once, as that instance; no host ever; the assembly closed; no graph send; the
     * topic goes on (every desired topic, a handshake).
     */
    @Test
    fun `B06b a seed read failure gives up the graph once, reported, and the topic goes on`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        val failure = java.io.IOException("install seed unreadable")
        val rig = Rig()
        rig.readSeed = { throw failure }
        rig.row {
            rig.coldStart(deadline, activate = false)
            awaitTrue("the graph failure reported", deadline) { rig.reports.isNotEmpty() }
            val assemblyJob = checkNotNull(rig.assemblyJob)
            awaitTrue("the assembly's job completed", deadline) { assemblyJob.isCompleted }
            rig.awaitTopics(deadline)
            // After the failure the topic still delivers: a new WS snapshot reaches the runtime display (read from the consumer,
            // since no holder was ever made).
            awaitTrue("an acknowledged socket", deadline) { openSockets.isNotEmpty() }
            @Suppress("UNCHECKED_CAST")
            val runtimeDisplay = rig.onMain { privateField(rig.owner.consumer, "display") } as StateFlow<TopicDisplayState>
            delay(1_100)
            openSockets.last().send(usdSnapshot(1391.0))
            awaitTrue("after the failure the topic still delivers", deadline) { kbUsdShown(runtimeDisplay, 1391.0) }
            quiet("B06b", 3_000)
            assertEquals("one connection", 1, handshakes().size)
            assertTrue("a usd subscription was sent: $subscribed", "fx:usd-krw" in subscribed)
            assertEquals("reported once", 1, rig.reports.size)
            // kotlinx.coroutines' debug-mode stack-trace recovery (on in this test JVM, off in a release build) may hand over a copy
            // whose cause chain holds the read's own instance.
            val reported = rig.reports.single()
            val chain = generateSequence(reported) { it.cause }.toList()
            assertTrue("the read's own failure, as itself or in the cause chain $chain", chain.any { it === failure })
            assertEquals("of the read's type and message", failure.javaClass to failure.message, reported.javaClass to reported.message)
            assertNull("no host", rig.onMain { rig.owner.graphHost.value })
            rig.assertRecorderClosed()
            assertEquals("no graph send", emptyList<SendRecorder.Exchange>(), graph())
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        ColdStartBudget.assertWithin(judgeRow("B06b"))
    }

    /**
     * The recorder after its own close: its closed flag set and its state emptied (GraphRecorder.close), then nothing exposed
     * and every demand unreadable. The last two alone would also follow from the assembly's bridge closing first. Read on Main.
     */
    private suspend fun Rig.assertRecorderClosed() {
        val fence = checkNotNull(coordinator.topicGrantResult().fence)
        val lifetime = checkNotNull(uses.acquire(fence))
        onMain {
            val recorder = assembly.recorder
            assertTrue("the recorder itself is closed", privateFlag(recorder, "closed"))
            assertTrue("the recorder's state was emptied", recorder.state.value.series.isEmpty())
            assertEquals("the recorder is unreadable", com.jay.fxi.data.graph.GraphTabRecoveryDemand.Unreadable,
                recorder.recoveryDemand("usd", fence, lifetime, kotlinx.datetime.Clock.System.now()))
            assertTrue("the recorder exposes nothing", recorder.exposed(fence, lifetime).isEmpty())
        }
    }

    /**
     * B06c (P03): a duplicate close and nothing after it. With a usable usd UI token and recorder data in hand, two closes
     * started together by a live caller on Main both return normally and the assembly's job completes. After that baseline,
     * the token's period selection and retry, a real WS snapshot (seen by the runtime display) and a foreground return add no
     * graph send, no recorder change and no report for 5 s.
     */
    @Test
    fun `B06c two concurrent closes both return and nothing after them sends, records or reports`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 30_000
        onTab = { request ->
            if (request.requestUrl!!.queryParameter("period") == "3m") ok(fixture("usd-3m-krx-hidden.json")) else ok(fixture("usd-1d-krx-hidden.json"))
        }
        val rig = Rig()
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            rig.awaitTopics(deadline)
            awaitTrue("catalog and the usd tab applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
            awaitTrue("an acknowledged socket", deadline) { openSockets.isNotEmpty() }
            openSockets.last().send(usdSnapshot(1390.0))
            awaitTrue("the WS delivery recorded kb.usd", deadline) { rig.hasKbSeries() }
            val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })
            // The token is usable before the close: its period selection sends and applies.
            rig.onMain { holder.selectPeriod(token, GraphPeriod.THREE_MONTHS) }
            awaitTrue("the token's 3m selection applied", deadline) {
                rig.assembly.coordinator.state.value.entries.keys.any { it.tab == "usd" && it.period.code == "3m" } && tabs().all { it.bodyEnd != null }
            }
            val display = checkNotNull(rig.runtimeDisplay)
            val assemblyJob = checkNotNull(rig.assemblyJob)

            // Each close reports whether the assembly's job had completed at the moment it returned.
            val completedAtReturn = withContext(main) {
                val first = async { rig.assembly.close(); assemblyJob.isCompleted }
                val second = async { rig.assembly.close(); assemblyJob.isCompleted }
                listOf(first.await(), second.await())
            }
            assertEquals("each close returned only after the assembly's job completed", listOf(true, true), completedAtReturn)
            rig.assertRecorderClosed()
            val graphBefore = graph().size
            val recorderBefore = rig.onMain { rig.assembly.recorder.state.value }
            val reportsBefore = rig.reports.size

            // A period this holder never fetched: an open coordinator would have to send for it.
            rig.onMain {
                holder.selectPeriod(token, GraphPeriod.ONE_WEEK)
                holder.retrySelection(token)
            }
            delay(1_100)
            openSockets.last().send(usdSnapshot(1391.0))
            awaitTrue("the runtime took the snapshot", deadline) { kbUsdShown(display, 1391.0) }
            rig.foreground.value = false
            delay(200)
            rig.foreground.value = true
            val until = nowMillis() + 5_000
            while (nowMillis() < until) {
                assertEquals("no graph send after the close\n${sends.timeline()}", graphBefore, graph().size)
                assertSame("no recorder change after the close", recorderBefore, rig.onMain { rig.assembly.recorder.state.value })
                assertEquals("no report after the close", reportsBefore, rig.reports.size)
                delay(100)
            }
        }
        assertTrue("within the row deadline", nowMillis() - start <= 30_000)
        ColdStartBudget.assertWithin(judgeRow("B06c"))
    }

    /**
     * B06d (RT01-B06's worker failure, P03 + agreed question 1): after real WS data is recorded and the start has settled, one
     * Firebase identity read — only the one made inside the sink worker's recorder observe, once, as one instance — throws.
     * The sink closes its pipeline: the recorder unreadable and empty, the sink answering CLOSED, the worker and the recorder's
     * collector (their jobs held before) completed, the other assembly children still running. The topic goes on (the next
     * snapshot reaches the runtime display, the recorder stays closed) and the coordinator still serves a period change. An
     * explicit close from a live Main caller then returns and every assembly child completes.
     */
    @Test
    fun `B06d a worker failure closes the sink pipeline and nothing else`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 30_000
        onTab = { request ->
            if (request.requestUrl!!.queryParameter("period") == "3m") ok(fixture("usd-3m-krx-hidden.json")) else ok(fixture("usd-1d-krx-hidden.json"))
        }
        val failure = RuntimeException("firebase identity read failed")
        val rig = Rig()
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            rig.awaitTopics(deadline)
            awaitTrue("catalog and the usd tab applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
            awaitTrue("an acknowledged socket", deadline) { openSockets.isNotEmpty() }
            openSockets.last().send(usdSnapshot(1390.0))
            awaitTrue("the WS delivery recorded kb.usd", deadline) { rig.hasKbSeries() }
            quiet("B06d settle", 1_000)
            val display = checkNotNull(rig.runtimeDisplay)
            val assemblyJob = checkNotNull(rig.assemblyJob)
            val (worker, collector) = rig.onMain { rig.privateJob(rig.assembly.sink, "worker") to rig.privateJob(rig.assembly.recorder, "collector") }
            val children = assemblyJob.children.toList()
            assertTrue("the worker and the recorder collector are assembly children", worker in children && collector in children)
            assertTrue("both run before the fault", worker.isActive && collector.isActive)

            rig.firebase.fault.set(failure)
            delay(1_100)
            openSockets.last().send(usdSnapshot(1391.0))
            awaitTrue("the fault was taken", deadline) { rig.firebase.faultHits.isNotEmpty() }
            awaitTrue("the worker and the collector completed", deadline) { worker.isCompleted && collector.isCompleted }
            assertNull("the fault is spent", rig.firebase.fault.get())
            assertEquals("taken exactly once: ${rig.firebase.faultHits}", 1, rig.firebase.faultHits.size)
            assertSame("the armed instance was thrown", failure, rig.firebase.faultThrown.single())
            assertTrue("inside the recorder's observe", "GraphRecorder.observe" in rig.firebase.faultHits.single())
            rig.assertRecorderClosed()
            val closedState = rig.assembly.recorder.state.value
            val offer = rig.onMain { rig.assembly.sink.tryOffer(rig.anyGraphInput()) }
            assertEquals("the sink answers closed", com.jay.fxi.data.remote.TopicGraphOffer.CLOSED, offer)
            // The coordinator's loop and the recovery events' ticks, identified by reflection, still run; the events still take
            // input: a background and foreground return replaces their ticks.
            val loop = rig.onMain { privateField(rig.assembly.coordinator, "loop") as Job }
            assertTrue("the coordinator's loop still runs", loop.isActive)
            @Suppress("UNCHECKED_CAST")
            val ticksBefore = rig.onMain { (privateField(rig.assembly.events, "ticks") as List<Job>).toList() }
            assertTrue("the events' ticks still run", ticksBefore.isNotEmpty() && ticksBefore.all { it.isActive })
            rig.foreground.value = false
            delay(200)
            rig.foreground.value = true
            awaitTrue("the events took the foreground return", deadline) {
                @Suppress("UNCHECKED_CAST")
                val now = rig.onMain { (privateField(rig.assembly.events, "ticks") as List<Job>).toList() }
                now.isNotEmpty() && now.none { it in ticksBefore } && now.all { it.isActive }
            }

            delay(1_100)
            openSockets.last().send(usdSnapshot(1392.0))
            awaitTrue("the topic goes on", deadline) { kbUsdShown(display, 1392.0) }
            assertSame("the closed recorder took nothing more", closedState, rig.assembly.recorder.state.value)
            val tabsBefore = tabs().size
            val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })
            rig.onMain { holder.selectPeriod(token, GraphPeriod.THREE_MONTHS) }
            awaitTrue("the coordinator still serves a period change", deadline) {
                tabs().size > tabsBefore && tabs().all { it.bodyEnd != null } &&
                    rig.assembly.coordinator.state.value.entries.keys.any { it.period.code == "3m" }
            }
            withContext(main) { rig.assembly.close() }
            assertTrue("every assembly child completed", assemblyJob.isCompleted && assemblyJob.children.none())
        }
        assertTrue("within the row deadline", nowMillis() - start <= 30_000)
        ColdStartBudget.assertWithin(judgeRow("B06d"))
    }

    /** A private Job field of a production component, read by reflection for observation only (B06d). */
    private fun Rig.privateJob(owner: Any, name: String): Job =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as Job

    /** Any graph input: a closed sink answers CLOSED before reading one (GraphRecorderTopicSink.tryOffer). */
    private fun Rig.anyGraphInput(): com.jay.fxi.data.remote.TopicGraphInput = com.jay.fxi.data.remote.TopicGraphInput.Continuity(
        sequence = 0, kind = com.jay.fxi.data.remote.TopicGraphEventKind.INITIAL, reason = null, topics = emptySet(), paths = emptySet(),
        authority = com.jay.fxi.data.remote.TopicGraphAuthority(Any(), null, 0L, null), connectionGeneration = null, occurredAtEpochMillis = 0L
    )

    /**
     * A jpy 1d tab derived from the usd contract fixture by one declared mapping — tab `jpy`, every series id and in-progress
     * key `.usd` → `.jpy`, `dxy` removed — which yields exactly the corpus's jpy 1d ids (registry/allowed-identifiers.json).
     */
    private fun jpyTab(): String {
        val base = wire.parseToJsonElement(fixture("usd-1d-krx-hidden.json")).jsonObject
        fun jpy(id: String) = id.removeSuffix(".usd") + ".jpy"
        val series = kotlinx.serialization.json.JsonArray(base.getValue("series").jsonArray
            .filter { it.jsonObject.getValue("id").jsonPrimitive.content != "dxy" }
            .map { element ->
                val obj = element.jsonObject
                kotlinx.serialization.json.JsonObject(obj + ("id" to kotlinx.serialization.json.JsonPrimitive(jpy(obj.getValue("id").jsonPrimitive.content))))
            })
        val inProgress = kotlinx.serialization.json.JsonObject(base.getValue("in_progress").jsonObject
            .filterKeys { it != "dxy" }.mapKeys { jpy(it.key) })
        return kotlinx.serialization.json.JsonObject(base + mapOf(
            "tab" to kotlinx.serialization.json.JsonPrimitive("jpy"), "series" to series, "in_progress" to inProgress
        )).toString()
    }

    /** kb.usd's latest recorded observation rate, or null; a StateFlow read, safe off Main. */
    private fun Rig.kbTipRate(): Double? = kbTipRate(assembly.recorder.state.value)

    private fun kbTipRate(state: com.jay.fxi.data.graph.GraphRecorderState): Double? =
        state.series.entries.firstOrNull { it.key.seriesId == "kb.usd" }?.value?.data?.app?.tip?.rate

    /** A private Boolean field of a production component, read by reflection for observation only. */
    private fun privateFlag(owner: Any, name: String): Boolean =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as Boolean

    /** A private field of a production component, read by reflection for observation only. */
    private fun privateField(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    /**
     * B1: one blocking task holding an executor. [holdOn] returns once the task has entered; [release] opens it and waits for
     * the task to end. A suspend wait would not hold the executor's thread.
     */
    private class ExecutorHold(
        private val gate: java.util.concurrent.CountDownLatch,
        private val ended: java.util.concurrent.CountDownLatch,
        private val expired: java.util.concurrent.atomic.AtomicBoolean
    ) {
        fun release() {
            gate.countDown()
            check(ended.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "the held executor did not finish its hold" }
            // A hold that gave up on its own (a forgotten release) did not hold for as long as the row assumed.
            check(!expired.get()) { "the hold expired before it was released" }
        }
    }

    /** Runs [block] under [hold] and always releases it; a release failure is attached to the block's own failure, never hides it. */
    private suspend fun <T> holding(hold: ExecutorHold, block: suspend () -> T): T {
        var failure: Throwable? = null
        try {
            return block()
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            try {
                hold.release()
            } catch (released: Throwable) {
                failure?.addSuppressed(released) ?: throw released
            }
        }
    }

    private fun holdOn(submit: (Runnable) -> Unit): ExecutorHold {
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val ended = java.util.concurrent.CountDownLatch(1)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            submit(Runnable {
                entered.countDown()
                // Bounded so a lost release cannot wedge the executor for good; the release reports the expiry.
                try { if (!gate.await(60, java.util.concurrent.TimeUnit.SECONDS)) expired.set(true) } finally { ended.countDown() }
            })
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "the executor did not take the hold" }
        } catch (failure: Throwable) {
            // A task that starts late must not hold the executor for good.
            gate.countDown()
            throw failure
        }
        return ExecutorHold(gate, ended, expired)
    }

    /** Holds the runtime's serial executor (the session and the grant deliverer). */
    private fun Rig.holdRuntime(): ExecutorHold = holdOn { task -> checkNotNull(runtimeScope).launch { task.run() } }

    /** Holds the process Main. */
    private fun holdMain(): ExecutorHold = holdOn { mainExecutor.execute(it) }

    /** The current thread's name without the " @coroutine#n" suffix kotlinx.coroutines' debug mode appends. */
    private fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")

    private fun kbUsdShown(display: StateFlow<TopicDisplayState>, rate: Double) =
        display.value.rates.quotes[com.jay.fxi.domain.model.TopicQuoteKey("kb", "usd-krw")]?.rate == rate

    /**
     * As the route does on a tab tap: select it through the consumer, then — once the screen shows it for an owner and its
     * holder is mounted — hide the old surface, move the activation to that tab's holder and report its surface shown.
     */
    private suspend fun Rig.moveTo(tab: FreeTab, from: GraphV2ScreenStateHolder, deadline: Long): GraphV2ScreenStateHolder {
        val key = checkNotNull(tab.serverTab)
        val screenOwner = onMain { checkNotNull(owner.consumer.currentState().ui.owner) }
        onMain { owner.consumer.onUserTabSelected(screenOwner, tab) }
        awaitTrue("the screen shows $key for an owner", deadline) {
            onMain { owner.consumer.currentState().ui.let { it.selectedTab == tab && it.owner != null } }
        }
        awaitTrue("the $key holder is mounted", deadline) { onMain { checkNotNull(mount).holders.value.containsKey(key) } }
        val target = onMain {
            from.currentState().inlineToken?.let { from.setSurfaceVisible(it, false) }
            from.onDeactivated()
            checkNotNull(mount).holders.value.getValue(key).also { it.onActivated(checkNotNull(owner.consumer.currentState().ui.owner)) }
        }
        showSurface(target, deadline)
        return target
    }

    // --- CUT-C01 batch 1a: RT01-A03, RT01-A05 (cut_c01_b1_agreed.r1.md) -------------------------------------------------

    private fun Rig.bridge(): com.jay.fxi.data.remote.TopicSessionFence? = assembly.fences.current()
    private fun Rig.permitFence(): com.jay.fxi.data.remote.TopicSessionFence? = permitSlot?.get()?.invoke()?.fence

    /** Samples the bridge every 5 ms off Main (auxiliary evidence only: a change between two samples is not seen). */
    private class BridgeSampler(scope: CoroutineScope, read: () -> Any?) {
        val seen: MutableList<Any?> = Collections.synchronizedList(mutableListOf())
        private val job = scope.launch(Dispatchers.Default) {
            while (true) {
                val value = read()
                synchronized(seen) { if (seen.isEmpty() || seen.last() != value) seen += value }
                delay(5)
            }
        }
        suspend fun stop(): List<Any?> { job.cancelAndJoin(); return synchronized(seen) { seen.toList() } }
    }

    /**
     * RT01-A03 (PARTIAL by agreement): the published fence against the actual delivery. Before any issuance the bridge
     * publishes nothing and the graph sends nothing; the first approval's grant F1 is published once delivered; a grant F2 the
     * issuer issues while the runtime executor is held is not published and nothing is sent for it until the hold ends, then
     * it is; an end delivered alone withdraws it and nothing is sent after. "No synthesized snapshot over the whole run" stays
     * unverified: the 5 ms samples are auxiliary.
     */
    @Test
    fun `RT01-A03 the published fence follows the delivery from before issuance through a held delivery to an end delivered alone`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += ok("""{"krx_visible":true,"premium_active":true}""")
        val catalogCalls = AtomicInteger()
        onCatalog = { if (catalogCalls.getAndIncrement() == 0) ok(fixture("catalog-krx-visible.json")) else ok(fixture("catalog-krx-hidden.json")) }
        val tabCalls = AtomicInteger()
        onTab = { if (tabCalls.getAndIncrement() == 0) ok(fixture("usd-1d-krx-visible.json")) else ok(fixture("usd-1d-krx-hidden.json")) }
        val rig = Rig()
        rig.row {
            // (i) Before issuance: the owner and its graph run, nothing is issued.
            rig.startOwner()
            awaitTrue("the host is published", deadline) { rig.onMain { rig.owner.graphHost.value != null } }
            val sampler = BridgeSampler(CoroutineScope(rig.ownerJob)) { rig.bridge() }
            // Auxiliary: the request sources the graph coordinator consumed and published (it re-reads the bridge when it runs).
            val sources = Collections.synchronizedList(mutableListOf<Any?>())
            val sourceCollector = rig.onMain {
                CoroutineScope(rig.ownerJob + Dispatchers.Unconfined).launch {
                    rig.assembly.coordinator.state.collect { sources += it.source?.fence }
                }
            }
            quiet("RT01-A03 before issuance", 2_000)
            assertNull("nothing issued, nothing published", rig.bridge())
            assertEquals("no graph send before issuance", 0, graph().size)

            // Issued and delivered: F1.
            rig.approve()
            val f1 = checkNotNull(rig.coordinator.topicGrantResult().fence)
            val holder = rig.openScreen(deadline)
            rig.showSurface(holder, deadline)
            awaitTrue("F1 delivered and published", deadline) { rig.bridge() == f1 && rig.permitFence() == f1 }
            rig.awaitTopics(deadline)
            awaitTrue("the usd tab applied under F1", deadline) { rig.usdApplied() && tabs().all { it.bodyEnd != null } }

            // (ii) Issued but not delivered: F2 issued while the runtime executor is held.
            val graphBefore = graph().size
            val f2 = holding(rig.holdRuntime()) {
                entitlements += ok(PREMIUM_HIDDEN)
                // Bounded: a deliverer that took the issuer's lock just before the hold would keep it until the hold ends.
                val issued = kotlinx.coroutines.withTimeout(10_000) {
                    rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
                    checkNotNull(rig.coordinator.topicGrantResult().fence)
                }
                assertNotEquals("premise: a new grant was issued", f1, issued)
                val until = nowMillis() + 1_000
                while (nowMillis() < until) {
                    assertEquals("held: the bridge still publishes F1", f1, rig.bridge())
                    assertEquals("held: no graph send for the undelivered grant\n${sends.timeline()}", graphBefore, graph().size)
                    delay(20)
                }
                issued
            }

            // (iii) Delivered: F2 published, the same fence the session holds.
            awaitTrue("F2 delivered and published", deadline) { rig.bridge() == f2 && rig.permitFence() == f2 }
            awaitTrue("the new context's tab applied", deadline) {
                rig.assembly.coordinator.state.value.source?.fence == f2 && rig.usdApplied() && tabs().all { it.bodyEnd != null }
            }

            // (iv) An end delivered alone: the same user's next auth generation and its approval query, whose answer is held.
            val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })
            entitlementsGate = java.util.concurrent.CountDownLatch(1)
            val approval = CoroutineScope(rig.issuerJob + Dispatchers.Default).launch {
                val next = rig.firebase.nextGeneration()
                rig.coordinator.onIdentityChanged(next)
                rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
            }
            val sampled: List<Any?>
            val consumed: List<Any?>
            try {
                awaitTrue("the new approval's answer is held", deadline) { entitlementsHeld.get() >= 1 }
                awaitTrue("the end alone was delivered", deadline) { rig.bridge() == null }
                assertTrue("the session's permit names no grant use", rig.onMain { checkNotNull(rig.permitSlot).require()() }?.automatic != true)
                val graphAtEnd = graph().size
                // A stimulus: the screen's own action with its earlier token.
                rig.onMain { holder.selectPeriod(token, GraphPeriod.THREE_MONTHS) }
                val until = nowMillis() + 3_000
                while (nowMillis() < until) {
                    assertNull("after the end nothing is published", rig.bridge())
                    assertEquals("after the end no graph send\n${sends.timeline()}", graphAtEnd, graph().size)
                    delay(50)
                }
                // The records end here: the held answer, released next, issues a third grant outside this row's question.
                sampled = sampler.stop()
                sourceCollector.cancelAndJoin()
                consumed = synchronized(sources) { sources.toList() }
            } finally {
                entitlementsGate?.countDown()
                entitlementsGate = null
            }
            approval.join()
            // The released answer issues a third grant; its context settles before the row ends, so every send it makes has
            // reached the server when the budget is judged.
            val f3 = checkNotNull(rig.coordinator.topicGrantResult().fence)
            awaitTrue("the third grant's context settled", deadline) {
                rig.bridge() == f3 && rig.assembly.coordinator.state.value.source?.fence == f3 && rig.usdApplied() &&
                    tabs().all { it.bodyEnd != null } && sends.all().filter { it.zone == "api" }.all { it.bodyEnd != null }
            }
            quiet("RT01-A03 settle", 2_000)
            assertTrue("auxiliary: every sampled publication was a delivered grant or nothing: $sampled",
                sampled.all { it == null || it == f1 || it == f2 })
            assertTrue("auxiliary: every request source the coordinator published was a delivered grant or none: $consumed",
                consumed.all { it == null || it == f1 || it == f2 })
        }
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow("RT01-A03"))
    }

    /**
     * What survives of the old context after a same-user end, read on Main once the new grant's context is consumed and before
     * any graph answer of it: old entry, protected slot and request instances (by identity), the catalog, failures, writes. A
     * seed from the kept disk namespace may already have refilled entries; those are counted apart, never as survivors.
     */
    private data class DiscardView(
        val oldEntriesKept: Int, val oldSlotsKept: Int, val oldRequestsKept: Int, val catalog: Boolean, val failures: Int, val writes: Int
    )

    private class OldContext(val entries: List<Any>, val slots: List<Any>, val requests: List<Any>)

    private suspend fun Rig.oldContext(): OldContext = onMain {
        val c = assembly.coordinator
        @Suppress("UNCHECKED_CAST")
        OldContext(
            entries = c.state.value.entries.values.toList(),
            slots = (privateField(c, "protectedSlots") as Map<Any, Any>).values.toList(),
            requests = (privateField(c, "tabRequests") as Map<Any, Any>).values.toList()
        )
    }

    private suspend fun Rig.discardView(f2: Any, old: OldContext): Pair<DiscardView, String> = onMain {
        val c = assembly.coordinator
        val state = c.state.value
        assertEquals("the coordinator consumed the new grant's context", f2, state.source?.fence)
        @Suppress("UNCHECKED_CAST")
        val slots = (privateField(c, "protectedSlots") as Map<Any, Any>).values
        @Suppress("UNCHECKED_CAST")
        val requests = (privateField(c, "tabRequests") as Map<Any, Any>).values
        val view = DiscardView(
            oldEntriesKept = state.entries.values.count { e -> old.entries.any { it === e } },
            oldSlotsKept = slots.count { s -> old.slots.any { it === s } },
            oldRequestsKept = requests.count { r -> old.requests.any { it === r } },
            catalog = state.catalog != null,
            failures = state.failures.size,
            writes = (privateField(c, "writeTasks") as Map<*, *>).size
        )
        view to "refilled entries ${state.entries.values.map { it.online200At }} (online200At; null = not an online 200), slots ${slots.size}"
    }

    /**
     * RT01-A05: the same user's real IDENTITY_CHANGED end with the same user epoch, delivered [merged] or not. Before the
     * end a 3m tab is in flight with a delayed body. After the new grant's context is consumed, with its own graph answers
     * held, entries, the catalog, failures and protected slots are gone and the old request is no longer owned; the old 3m
     * body then ends and is never applied; released, the new context refills.
     */
    private fun identityChangedRow(label: String, merged: Boolean) = runBlocking {
        val start = nowMillis()
        val deadline = start + 40_000
        val holdNewTabs = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch?>(null)
        onCatalog = {
            holdNewTabs.get()?.await(20, java.util.concurrent.TimeUnit.SECONDS)
            ok(fixture("catalog-krx-hidden.json"))
        }
        onTab = { request ->
            holdNewTabs.get()?.await(20, java.util.concurrent.TimeUnit.SECONDS)
            if (request.requestUrl!!.queryParameter("period") == "3m") ok(fixture("usd-3m-krx-hidden.json")).setBodyDelay(8, java.util.concurrent.TimeUnit.SECONDS)
            else ok(fixture("usd-1d-krx-hidden.json"))
        }
        val rig = Rig()
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            rig.awaitTopics(deadline)
            awaitTrue("catalog and the usd tab applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
            val f1 = checkNotNull(rig.bridge())
            // The cold start's burst settles first: an identity change 1.6 s after it exceeds the budget on its own
            // (cut_c0102_observations/RT01-A05_merged_identity_change_1600ms_after_cold_start.md), which is not this row's contract.
            quiet("$label settle", 3_500)
            val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })
            rig.onMain { holder.selectPeriod(token, GraphPeriod.THREE_MONTHS) }
            awaitTrue("the 3m tab is in flight", deadline) { tabs().any { it.param("period") == "3m" && it.bodyEnd == null } }
            val old = rig.oldContext()
            assertTrue("premise: the old context has entries, slots and the 3m request",
                old.entries.isNotEmpty() && old.slots.isNotEmpty() && old.requests.isNotEmpty())
            val epochBefore = f1.userAccessEpoch

            lateinit var f2: com.jay.fxi.data.remote.TopicSessionFence
            try {
            f2 = if (!merged) {
                // Separate: the end is delivered alone (the answer held), then the new grant.
                entitlementsGate = java.util.concurrent.CountDownLatch(1)
                val approval = CoroutineScope(rig.issuerJob + Dispatchers.Default).launch {
                    val next = rig.firebase.nextGeneration()
                    rig.coordinator.onIdentityChanged(next)
                    rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
                }
                try {
                    awaitTrue("$label: the end alone was delivered", deadline) { rig.bridge() == null }
                    holdNewTabs.set(java.util.concurrent.CountDownLatch(1))
                } finally {
                    entitlementsGate?.countDown()
                    entitlementsGate = null
                }
                approval.join()
                checkNotNull(rig.coordinator.topicGrantResult().fence).also { f ->
                    awaitTrue("$label: F2 delivered", deadline) { rig.bridge() == f }
                }
            } else {
                // Merged: the end, the new approval and the new grant all happen while the topic executor and Main are held.
                val pullsBefore = rig.pulls.size
                holding(holdMain()) {
                    val issued = holding(rig.holdRuntime()) {
                        // Bounded: a deliverer that took the issuer's lock just before the hold would keep it until the hold ends.
                        kotlinx.coroutines.withTimeout(10_000) {
                            val next = rig.firebase.nextGeneration()
                            rig.coordinator.onIdentityChanged(next)
                            rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
                            checkNotNull(rig.coordinator.topicGrantResult().fence)
                        }
                    }
                    awaitTrue("$label: F2 delivered while Main is held", deadline) { rig.bridge() == issued }
                    holdNewTabs.set(java.util.concurrent.CountDownLatch(1))
                    // Auxiliary (pulls are not the delivery sequence): after the hold the deliverer pulled the new grant, no end-only result.
                    val seen = synchronized(rig.pulls) { rig.pulls.drop(pullsBefore) }
                    println("CUT-C01 $label pulls after the hold: ${seen.map { it.fence?.grant }}")
                    assertTrue("$label: auxiliary: no pull after the hold returned an end without a grant: ${seen.map { it.fence?.grant }}",
                        seen.isNotEmpty() && seen.all { it.fence != null })
                    issued
                }
            }
                assertEquals("$label: premise: the same user epoch", epochBefore, f2.userAccessEpoch)
                awaitTrue("$label: the new context is consumed", deadline) { rig.assembly.coordinator.state.value.source?.fence == f2 }
                val (view, refill) = rig.discardView(f2, old)
                println("CUT-C01 $label discard: $view; $refill")
                assertEquals("$label: nothing of the old context survives before any graph answer of the new one ($refill)",
                    DiscardView(oldEntriesKept = 0, oldSlotsKept = 0, oldRequestsKept = 0, catalog = false, failures = 0, writes = 0), view)
                awaitTrue("$label: the old 3m body ended", deadline) { tabs().first { it.param("period") == "3m" }.bodyEnd != null }
                val until = nowMillis() + 1_000
                while (nowMillis() < until) {
                    assertTrue("$label: the old 3m payload is never applied",
                        rig.assembly.coordinator.state.value.entries.keys.none { it.period.code == "3m" })
                    delay(20)
                }
            } finally {
                holdNewTabs.getAndSet(null)?.countDown()
            }
            awaitTrue("$label: the new context refills", deadline) {
                rig.assembly.coordinator.state.value.source?.fence == f2 && rig.usdApplied() && tabs().all { it.bodyEnd != null }
            }
        }
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    @Test
    fun `RT01-A05 separate a same-user end delivered apart from its new grant discards the old context`() = identityChangedRow("RT01-A05 separate", merged = false)

    @Test
    fun `RT01-A05 merged a same-user end merged into its new grant discards the old context the same way`() = identityChangedRow("RT01-A05 merged", merged = true)

    /** The jpy topic snapshot (contract fixture envelope) with one kb quote stamped now. */
    private fun jpySnapshot(rate: Double): String {
        val base = wire.parseToJsonElement(File("src/test/resources/contracts/v2/topic/snapshot-fx-jpy-krw.json").readText()).jsonObject
        val data = base.getValue("data").jsonObject
        val kb = kotlinx.serialization.json.buildJsonObject {
            put("asset", kotlinx.serialization.json.JsonPrimitive("jpy-krw"))
            put("rate", kotlinx.serialization.json.JsonPrimitive(rate))
            put("source", kotlinx.serialization.json.JsonPrimitive("kb"))
            put("timestamp", kotlinx.serialization.json.JsonPrimitive(isoAt(System.currentTimeMillis() / 1000)))
        }
        val newData = kotlinx.serialization.json.JsonObject(
            data.filterKeys { it != "reference" } + ("banks" to kotlinx.serialization.json.JsonArray(listOf(kb)))
        )
        return kotlinx.serialization.json.JsonObject(base + ("data" to newData)).toString()
    }

    private fun kbJpy(display: StateFlow<TopicDisplayState>): Double? =
        display.value.rates.quotes[com.jay.fxi.domain.model.TopicQuoteKey("kb", "jpy-krw")]?.rate

    /**
     * RT01-A10 (cut_c01_b1a_contract.r1/codex_contract.r1.md F1 (b)·F2): a WS refusal the issuer cannot decide (its identity read
     * fails) parks the issuer, which owes a FORCE_PREMIUM re-check whose answer is held. Two tabs are in flight across it, their
     * answers held at the server: a usd 3m released inside the window before the publication, and a jpy 1d released after it.
     * The refusal boundary is where the session settles it: the permit stays on F1 with no automatic use and no Connection, and
     * the display and screen owners are gone. In that window the issuer still allows (token, standing, USER and CAPABILITY), the
     * graph's protected reads give GENERAL and KRX data and the released 3m's GENERAL and KRX components are applied, while the
     * active jpy holder exposes no chart or token and a screen action with its pre-refusal token sends nothing. After the issuer
     * publishes the StableInactive USER end nothing is adopted or sent, the old fence and lifetime read nothing, and the jpy answer
     * is not applied. Not verified here: that an old-connection input after the refusal was settled reaches the processing
     * boundary and adds nothing (the refusal closes the connection); a frame taken before it is settled is allowed and recorded.
     */
    @Test
    fun `RT01-A10 a parked refusal leaves the graph allowed until the issuer publishes the user end and nothing after it`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 60_000
        entitlements += ok("""{"krx_visible":true,"premium_active":true}""")
        onCatalog = { ok(fixture("catalog-krx-visible.json")) }
        // The derived jpy answer was accepted under the krx-hidden catalog (A01b); the krx-visible catalog's jpy entry is the same.
        val jpyIn = { name: String -> wire.parseToJsonElement(fixture(name)).jsonObject.getValue("tabs").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == "jpy" }.toString() }
        assertEquals("premise: the jpy catalog entry does not depend on KRX", jpyIn("catalog-krx-hidden.json"), jpyIn("catalog-krx-visible.json"))
        val hold3m = java.util.concurrent.CountDownLatch(1)
        val holdJpy = java.util.concurrent.CountDownLatch(1)
        onTab = { request ->
            when {
                request.requestUrl!!.queryParameter("tab") == "jpy" -> { holdJpy.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(jpyTab()) }
                request.requestUrl!!.queryParameter("period") == "3m" -> { hold3m.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("usd-3m-krx-visible.json")) }
                else -> ok(fixture("usd-1d-krx-visible.json"))
            }
        }
        val rig = Rig()
        try {
            rig.row {
                val holder = checkNotNull(rig.coldStart(deadline))
                rig.awaitTopics(deadline)
                awaitTrue("catalog and the usd tab applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
                awaitTrue("an acknowledged socket", deadline) { openSockets.isNotEmpty() }
                openSockets.last().send(usdSnapshot(1390.0))
                awaitTrue("the WS delivery recorded kb.usd", deadline) { rig.hasKbSeries() }
                val display = checkNotNull(rig.runtimeDisplay)
                val endsBefore = rig.coordinator.accessSnapshot.lastUserEnd?.sequence ?: 0L
                lateinit var fenceBefore: com.jay.fxi.data.remote.TopicSessionFence
                lateinit var lifetimeBefore: com.jay.fxi.data.remote.TopicUseLifetime

                // Two tabs in flight across the refusal, their answers held.
                val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })
                rig.onMain { holder.selectPeriod(token, GraphPeriod.THREE_MONTHS) }
                awaitTrue("the 3m tab is in flight", deadline) { tabs().any { it.param("period") == "3m" && it.end == null } }
                checkNotNull(rig.routeJob).cancelAndJoin()
                val jpy = rig.moveTo(FreeTab.JPY, from = holder, deadline)
                awaitTrue("the jpy tab is in flight", deadline) { tabs().any { it.param("tab") == "jpy" && it.end == null } }
                val jpyToken = checkNotNull(rig.onMain { jpy.currentState().inlineToken }) { "the active jpy holder has a token" }
                val usd1d = rig.onMain { rig.assembly.coordinator.state.value.entries.keys.single { it.tab == "usd" && it.period.code == "1d" } }

                // The refusal under a held runtime, a late usd frame right after its acknowledgement on the same socket, and the
                // owed re-check's answer held at the server with an inactive answer queued.
                entitlementsGate = java.util.concurrent.CountDownLatch(1)
                try {
                    entitlements += ok("""{"krx_visible":true,"premium_active":false,"premium_pending":false}""")
                    val heldAtRefusal = java.util.concurrent.atomic.AtomicReference<ExecutorHold?>(null)
                    val lateFrameSent = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
                    refuseUsd = {
                        // One refusal only: a second subscription inside the window must not take a second hold.
                        refuseUsd = null
                        rig.issuerIdentityReadable = false
                        heldAtRefusal.set(rig.holdRuntime())
                    }
                    afterRefusal = { socket ->
                        afterRefusal = null
                        lateFrameSent.set(socket.send(usdSnapshot(1395.0)))
                    }
                    val socketsBefore = openSockets.size
                    openSockets.last().close(1011, "server restart")
                    try {
                        awaitTrue("the usd subscription was refused", deadline) {
                            refusals.get() >= 1 && openSockets.size > socketsBefore && lateFrameSent.get() != null
                        }
                        assertEquals("the late usd frame was handed to the refusing connection's open socket", true, lateFrameSent.get())
                    } finally {
                        refuseUsd = null
                        afterRefusal = null
                        heldAtRefusal.getAndSet(null)?.release()
                    }
                    awaitTrue("the issuer owes a FORCE_PREMIUM re-check", deadline) {
                        rig.coordinator.recheckDiagnostics().owedIntent == RefreshIntent.FORCE_PREMIUM
                    }
                    assertTrue("premise: the issuer still allows the user", rig.coordinator.accessSnapshot.facts.userAllowed)
                    delay(500)
                    // A frame taken before the refusal was settled is allowed (F1 (b)); recorded, not asserted.
                    println("CUT-C01 RT01-A10 F1 late frame after the refusal on the wire: kb.usd tip ${rig.kbTipRate()} (1390 = not taken, 1395 = taken)")

                    // The refusal boundary: settled by the session.
                    val fence = checkNotNull(rig.bridge()) { "the delivered fence stands" }
                    awaitTrue("the session settled the refusal", deadline) {
                        val permit = rig.permitFence()
                        val published = rig.onMain { checkNotNull(rig.permitSlot).require()() }
                        permit == fence && published?.automatic == false && published.connectionGeneration == null &&
                            display.value.owner == null
                    }
                    val facts = rig.coordinator.accessSnapshot.facts
                    assertTrue("the issuer still allows: token, standing, USER and CAPABILITY ($facts)",
                        facts.token == fence.grant && facts.tokenStanding && facts.userAllowed && facts.capabilityAllowed)
                    val lifetime = checkNotNull(rig.uses.acquire(fence)) { "the issuer still admits a use under it" }

                    // In the window: protected reads give GENERAL and KRX; the screen exposes nothing and its action sends nothing.
                    val graphInWindow = graph().size
                    val handshakesInWindow = handshakes().size
                    val writtenBefore = rig.writtenFiles()
                    rig.onMain {
                        assertNull("no screen owner", rig.owner.consumer.currentState().ui.owner)
                        val read = checkNotNull(rig.assembly.coordinator.protectedEntry(usd1d)) { "the protected usd 1d read is allowed" }
                        val ids = read.tab.graph.series.map { it.seriesId }
                        assertTrue("GENERAL protected read: $ids", "kb.usd" in ids)
                        assertTrue("KRX protected read: $ids", "krx.usd-krw-futures" in ids)
                        val st = jpy.currentState()
                        assertTrue("the active jpy holder exposes no chart or token: $st",
                            st.chart == null && st.inlineToken == null && st.fullscreenToken == null)
                        jpy.selectPeriod(jpyToken, GraphPeriod.THREE_MONTHS)
                        jpy.retrySelection(jpyToken)
                    }
                    delay(1_000)
                    assertEquals("a screen action with the pre-refusal token sends nothing\n${sends.timeline()}", graphInWindow, graph().size)
                    hold3m.countDown()
                    awaitTrue("the released 3m answer was applied before the publication", deadline) {
                        rig.assembly.coordinator.state.value.entries.keys.any { it.tab == "usd" && it.period.code == "3m" }
                    }
                    rig.onMain {
                        val key = rig.assembly.coordinator.state.value.entries.keys.single { it.tab == "usd" && it.period.code == "3m" }
                        val ids = checkNotNull(rig.assembly.coordinator.protectedEntry(key)).tab.graph.series.map { it.seriesId }
                        assertTrue("the 3m answer's GENERAL and KRX components applied: $ids", "hana.usd" in ids && "krx.usd-krw-futures" in ids)
                    }
                    assertEquals("no new connection in the window", handshakesInWindow, handshakes().size)
                    println("CUT-C01 RT01-A10 F2 disk files before/after the 3m applied in the window: $writtenBefore / ${rig.writtenFiles()}")
                    awaitTrue("the re-check reached the server", deadline) { entitlementsHeld.get() >= 1 }
                    fenceBefore = fence
                    lifetimeBefore = lifetime
                    rig.issuerIdentityReadable = true
                } finally {
                    entitlementsGate?.countDown()
                    entitlementsGate = null
                }
                awaitTrue("the issuer published the user end", deadline) {
                    val snapshot = rig.coordinator.accessSnapshot
                    !snapshot.facts.userAllowed && (snapshot.lastUserEnd?.sequence ?: 0L) > endsBefore
                }

                // After the publication: no read, render, send or old completion. No connection exists after the refusal, so a new
                // price arriving after the end cannot be put to the session here: price adoption after the end stays unverified.
                val graphAfter = graph().size
                val recorderAfter = rig.assembly.recorder.state.value
                holdJpy.countDown()
                awaitTrue("the held jpy answer ended", deadline) { tabs().first { it.param("tab") == "jpy" }.bodyEnd != null }
                rig.onMain { jpy.selectPeriod(jpyToken, GraphPeriod.ONE_WEEK) }
                val until = nowMillis() + 3_000
                while (nowMillis() < until) {
                    assertEquals("after the end no graph send\n${sends.timeline()}", graphAfter, graph().size)
                    assertSame("after the end the recorder takes nothing", recorderAfter, rig.assembly.recorder.state.value)
                    delay(50)
                }
                assertTrue("the jpy answer released after the end is never applied",
                    rig.assembly.coordinator.state.value.entries.keys.none { it.tab == "jpy" })
                rig.onMain {
                    assertNull("after the end nothing renders", holder.currentState().chart)
                    assertNull("after the end nothing renders on jpy", jpy.currentState().chart)
                    assertTrue("after the end the old fence and lifetime read nothing from the recorder",
                        rig.assembly.recorder.exposed(fenceBefore, lifetimeBefore).isEmpty())
                    assertNull("after the end the gate admits nothing under the old fence", rig.assembly.gate.bind(fenceBefore, lifetimeBefore))
                    val keys = rig.assembly.coordinator.state.value.entries.keys
                    assertTrue("after the end no protected entry is read", (keys + usd1d).all { rig.assembly.coordinator.protectedEntry(it) == null })
                }
                assertNull("after the end no use can start under the old fence", rig.uses.acquire(fenceBefore))
            }
        } finally {
            hold3m.countDown()
            holdJpy.countDown()
        }
        assertTrue("within the row deadline", nowMillis() - start <= 60_000)
        ColdStartBudget.assertWithin(judgeRow("RT01-A10"))
    }

    // --- CUT-C01 batch 1b: RT01-A04 (cut_c01_b1_agreed.r1.md) ---------------------------------------------------------------

    private val premiumKrxVisible = """{"krx_visible":true,"premium_active":true}"""

    /** The issuer's own answer decision, by name or as its resumed continuation. */
    private fun isIssuerDecision(frame: StackTraceElement) =
        frame.className.startsWith("com.jay.fxi.data.entitlements.PremiumAccessCoordinator") &&
            (frame.methodName == "apply" || frame.className.contains("\$apply\$"))

    /**
     * RT01-A04 (cut_c01_b1_agreed.r1.md A04): a USER P4 hold and its release without a USER end. From KRX VISIBLE with data and
     * an unmet closed recovery demand, a USER loss answer is decided with the issuer's identity unreadable and the decision's
     * store read failing once: the loss is held as a candidate (LOSS_CANDIDATE). Only the candidate recovery's identity read
     * then stays unreadable, so the hold stands while answers decide again; a FORCE_PREMIUM answer that keeps premium and drops
     * KRX rotates the capability epoch, and the candidate, whose record fence moved, is released without a USER end. The
     * release is reported as one that came with a capability rotation, never as a pure round trip on the same fence and token.
     * [observed]: the consumers see the hold (no use, data and demand kept, a late answer not applied). Not [observed]: Main and
     * the topic executor are held from the hold's start to its release, and the consumers never process a hold revision.
     * Both: after it, a new lifetime only (the old one refused, the invalidations raised), GENERAL kept and KRX removed, the
     * old 3m answer never applied.
     */
    private fun userHoldRow(label: String, observed: Boolean) = runBlocking {
        val prepared = clearOfBoundary(70_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 60_000
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        val hold3m = java.util.concurrent.CountDownLatch(1)
        // From the capture before the hold until the preservation is compared after the release, every new catalog, 1d and
        // 3m answer (a refill, the holder's active period re-requested, a recovery) waits here; the old 3m answer apart.
        val gateRefill = java.util.concurrent.atomic.AtomicBoolean(false)
        val refillGate = java.util.concurrent.CountDownLatch(1)
        val threeMonthCalls = AtomicInteger()
        val krxVisible = java.util.concurrent.atomic.AtomicBoolean(true)
        val rig = Rig()
        try {
            rig.row {
                rig.prepareClosedDemand(deadline, krx = true)
                onCatalog = {
                    if (gateRefill.get()) refillGate.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    ok(fixture(if (krxVisible.get()) "catalog-krx-visible.json" else "catalog-krx-hidden.json"))
                }
                onTab = { request ->
                    if (request.requestUrl!!.queryParameter("period") == "3m" && threeMonthCalls.getAndIncrement() == 0) {
                        hold3m.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("usd-3m-krx-visible.json"))
                    } else {
                        if (gateRefill.get()) refillGate.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        val period = if (request.requestUrl!!.queryParameter("period") == "3m") "3m" else "1d"
                        ok(fixture("usd-$period-krx-${if (krxVisible.get()) "visible" else "hidden"}.json"))
                    }
                }
                val holder = checkNotNull(rig.onMain { rig.mount?.holders?.value?.get("usd") })
                val f1 = checkNotNull(rig.bridge())
                val lifetime1 = checkNotNull(rig.uses.acquire(f1))
                val pendingBefore = rig.kbClosedPending()
                assertTrue("premise: an unmet closed demand", pendingBefore.isNotEmpty())
                val recordBefore = rig.epochStore.load()
                val endsBefore = rig.coordinator.accessSnapshot.lastUserEnd?.sequence ?: 0L
                val capEndsBefore = rig.coordinator.accessSnapshot.lastCapabilityEnd?.sequence ?: 0L
                val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })
                rig.onMain { holder.selectPeriod(token, GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the old lifetime's 3m tab is in flight", deadline) { tabs().any { it.param("period") == "3m" && it.end == null } }
                // The screen action under the hold uses the 3m token the screen offers just before it (a period change renews it).
                awaitTrue("$label: the screen offers a 3m token", deadline) {
                    rig.onMain { holder.currentState().inlineToken?.period == GraphPeriod.THREE_MONTHS }
                }
                // From here no new answer is applied until the comparison after the release.
                gateRefill.set(true)
                val token3m = checkNotNull(rig.onMain { holder.currentState().inlineToken })
                val usd1d = rig.onMain { rig.assembly.coordinator.state.value.entries.keys.single { it.tab == "usd" && it.period.code == "1d" } }
                val generalBefore = rig.onMain {
                    checkNotNull(rig.assembly.coordinator.protectedEntry(usd1d)).tab.graph.series.filterNot { it.seriesId.startsWith("krx.") }
                }
                assertTrue("$label: premise: GENERAL content with kb.usd before the hold", generalBefore.any { it.seriesId == "kb.usd" })
                val slotBefore = checkNotNull(rig.onMain { rig.slotComponents(usd1d) })
                assertNotNull("$label: premise: the 1d slot holds KRX before the hold", slotBefore.krx)
                val pendingAtHold = rig.kbClosedPending()
                assertEquals("$label: premise: the closed demand is the prepared one", pendingBefore, pendingAtHold)
                // Answers completed apart from the old 3m: none may complete between this capture and the comparison.
                val answersCompleted = {
                    tabs().count { it.bodyEnd != null } - (if (tabs().first { it.param("period") == "3m" }.bodyEnd != null) 1 else 0) +
                        catalogs().count { it.bodyEnd != null }
                }
                val answersAtCapture = answersCompleted()
                val graphBeforeHold = graph().size
                // Every request source the graph coordinator publishes from here: a consumer that processes the hold publishes
                // none (no use can be acquired under it).
                val sourcesSinceHold = Collections.synchronizedList(mutableListOf<Any?>())
                val sourceCollector = rig.onMain {
                    CoroutineScope(rig.ownerJob + Dispatchers.Unconfined).launch {
                        rig.assembly.coordinator.state.drop(1).collect { sourcesSinceHold += it.source?.fence }
                    }
                }

                assertEquals("$label: premise: the 3m token is the screen's just before the hold", token3m,
                    rig.onMain { holder.currentState().inlineToken })
                // Unobserved: the consumers are held from before the hold to after its release.
                val mainHold = if (observed) null else holdMain()
                val runtimeHold = if (observed) null else try { rig.holdRuntime() } catch (failure: Throwable) { mainHold?.release(); throw failure }
                try {
                    // The hold: a USER loss decided while the identity cannot be read, its decision's store read failing once.
                    val failure = java.io.IOException("access record unreadable")
                    rig.storeLoadFault.arm(failure) { isIssuerDecision(it) }
                    rig.issuerIdentityReadable = false
                    entitlements += ok("""{"krx_visible":true,"premium_active":false,"premium_pending":false}""")
                    kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                    assertEquals("$label: the decision's read failed once, as that instance: ${rig.storeLoadFault.hits}",
                        listOf<Throwable>(failure), rig.storeLoadFault.thrown.toList())
                    assertTrue("$label: inside the issuer's decision", rig.storeLoadFault.hits.single().contains("apply"))
                    assertEquals("$label: one candidate held", 1, rig.coordinator.heldLossCandidateCount())
                    assertTrue("$label: the snapshot holds the user", com.jay.fxi.data.entitlements.TopicAccessBlock.LOSS_CANDIDATE in
                        rig.coordinator.accessSnapshot.facts.userBlocks)
                    // Only the candidate recovery stays blind; answers decide with the identity again. A recovery round must
                    // actually meet the unreadable identity (its IdentityUnknown) and leave the hold standing.
                    val deniedBefore = rig.recoveryIdentityDenials.get()
                    rig.recoveryIdentityReadable = false
                    rig.issuerIdentityReadable = true
                    awaitTrue("$label: a candidate recovery round met the unreadable identity", deadline) {
                        rig.recoveryIdentityDenials.get() > deniedBefore
                    }
                    assertEquals("$label: the hold stands after the recovery's IdentityUnknown", 1, rig.coordinator.heldLossCandidateCount())
                    assertTrue("$label: the snapshot still holds the user", com.jay.fxi.data.entitlements.TopicAccessBlock.LOSS_CANDIDATE in
                        rig.coordinator.accessSnapshot.facts.userBlocks)

                    if (observed) {
                        // The consumers see the hold: no use, no render, nothing applied; data and demand kept.
                        rig.onMain {
                            assertNull("$label: no protected read under the hold",
                                rig.assembly.coordinator.state.value.entries.keys.firstNotNullOfOrNull { rig.assembly.coordinator.protectedEntry(it) })
                            assertNull("$label: nothing renders under the hold", holder.currentState().chart)
                            assertNull("$label: the screen offers no token under the hold", holder.currentState().inlineToken)
                            holder.selectPeriod(token3m, GraphPeriod.ONE_WEEK)
                        }
                        hold3m.countDown()
                        awaitTrue("$label: the old 3m answer ended", deadline) { tabs().first { it.param("period") == "3m" }.bodyEnd != null }
                        val until = nowMillis() + 1_500
                        while (nowMillis() < until) {
                            assertEquals("$label: no graph send under the hold\n${sends.timeline()}", graphBeforeHold, graph().size)
                            assertTrue("$label: the old 3m answer is not applied under the hold",
                                rig.assembly.coordinator.state.value.entries.keys.none { it.period.code == "3m" })
                            delay(50)
                        }
                        assertEquals("$label: the closed demand is kept under the hold", pendingBefore, rig.kbClosedPending())
                        // The positive control, taken before the release: the consumer processed the hold itself.
                        val duringHold = synchronized(sourcesSinceHold) { sourcesSinceHold.toList() }
                        assertTrue("$label: the consumer saw the hold before the release (a source-less publication): $duringHold",
                            null in duringHold)
                    }

                    // The release: premium kept, KRX dropped — the capability epoch rotates and the candidate goes stale.
                    krxVisible.set(false)
                    entitlements += ok(PREMIUM_HIDDEN)
                    kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
                    awaitTrue("$label: the candidate was released", deadline) {
                        rig.coordinator.heldLossCandidateCount() == 0 &&
                            com.jay.fxi.data.entitlements.TopicAccessBlock.LOSS_CANDIDATE !in rig.coordinator.accessSnapshot.facts.userBlocks
                    }
                    if (!observed) {
                        // The topic executor first, until the final grant is delivered; only then Main, so the consumers see the
                        // final state alone.
                        checkNotNull(runtimeHold).release()
                        val final = checkNotNull(rig.coordinator.topicGrantResult().fence)
                        awaitTrue("$label: the final grant is delivered while Main is held", deadline) { rig.bridge() == final }
                        checkNotNull(mainHold).release()
                    }
                } finally {
                    rig.recoveryIdentityReadable = true
                    rig.issuerIdentityReadable = true
                    try {
                        runtimeHold?.release()
                    } finally {
                        mainHold?.release()
                        hold3m.countDown()
                    }
                }
                val snapshot = rig.coordinator.accessSnapshot
                val recordAfter = rig.epochStore.load()
                assertEquals("$label: no USER end", endsBefore, snapshot.lastUserEnd?.sequence ?: 0L)
                assertEquals("$label: the USER epoch is unchanged", recordBefore.userAccessEpoch, recordAfter.userAccessEpoch)
                assertNotEquals("$label: the capability epoch rotated", recordBefore.krxCapabilityEpoch, recordAfter.krxCapabilityEpoch)
                assertTrue("$label: a capability end was recorded", (snapshot.lastCapabilityEnd?.sequence ?: 0L) > capEndsBefore)

                // After it: a new lifetime only; GENERAL kept, KRX removed; the old 3m answer never applied.
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                awaitTrue("$label: the new grant's context is consumed", deadline) {
                    rig.bridge() == f2 && rig.assembly.coordinator.state.value.source?.fence == f2
                }
                val lifetime2 = checkNotNull(rig.uses.acquire(f2))
                assertNotEquals("$label: a new lifetime", lifetime1, lifetime2)
                sourceCollector.cancelAndJoin()
                val published = synchronized(sourcesSinceHold) { sourcesSinceHold.toList() }
                assertTrue("$label: the consumer published the new grant's source: $published", f2 in published)
                if (!observed) {
                    assertFalse("$label: the consumer never processed a hold revision (no source-less publication): $published", null in published)
                }
                assertTrue("$label: the invalidations rose (${lifetime1.invalidations} -> ${snapshot.userInvalidations})",
                    snapshot.userInvalidations > lifetime1.invalidations)
                assertFalse("$label: the old lifetime is refused", rig.uses.admits(lifetime1))
                awaitTrue("$label: the old 3m answer ended", deadline) { tabs().first { it.param("period") == "3m" }.bodyEnd != null }
                val until = nowMillis() + 1_000
                while (nowMillis() < until) {
                    assertTrue("$label: the old 3m answer is never applied (a new 3m request, if any, is still held)",
                        rig.assembly.coordinator.state.value.entries.keys.none { it.period.code == "3m" })
                    delay(20)
                }
                // Before any refill: the GENERAL content captured before the hold, unchanged; KRX removed; the closed demand kept.
                awaitTrue("$label: the new lifetime reads the 1d entry with KRX removed", deadline) {
                    rig.onMain {
                        rig.assembly.coordinator.protectedEntry(usd1d)?.tab?.graph?.series?.none { it.seriesId.startsWith("krx.") } == true
                    }
                }
                val generalAfter = rig.onMain { checkNotNull(rig.assembly.coordinator.protectedEntry(usd1d)).tab.graph.series }
                assertEquals("$label: GENERAL kept as it was before the hold (no refill yet)", generalBefore, generalAfter)
                val (entryKept, slotAfter) = rig.onMain { (usd1d in rig.assembly.coordinator.state.value.entries) to rig.slotComponents(usd1d) }
                assertTrue("$label: the 1d entry stays in the coordinator (no drop and reload)", entryKept)
                assertSame("$label: the in-memory GENERAL component is the one from before the hold", slotBefore.general, slotAfter?.general)
                assertNull("$label: the in-memory KRX component is removed", slotAfter?.krx)
                assertEquals("$label: the closed demand kept as it was before the hold", pendingBefore, rig.kbClosedPending())
                assertEquals("$label: no new answer completed between the capture and the comparison\n${sends.timeline()}",
                    answersAtCapture, answersCompleted())
                gateRefill.set(false)
                refillGate.countDown()
                assertTrue("$label: the recorder keeps kb.usd", rig.hasKbSeries())
                rig.awaitTopics(deadline)
                quiet("$label settle", 1_000)
            }
        } finally {
            hold3m.countDown()
            refillGate.countDown()
        }
        assertTrue("within the row deadline", nowMillis() - start <= 60_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    @Test
    fun `RT01-A04 observed a user P4 hold keeps the data and demand unused and its release resumes on a new lifetime`() =
        userHoldRow("RT01-A04 observed", observed = true)

    @Test
    fun `RT01-A04 unobserved a user P4 hold the consumers never see resumes on a new lifetime the same way`() =
        userHoldRow("RT01-A04 unobserved", observed = false)

    // --- CUT-C01 batch 1b: RT01-A07 (cut_c01_b1_agreed.r1.md A07) --------------------------------------------------------

    /**
     * What the graph does in one access state: GENERAL/KRX protected read; the screen's GENERAL and KRX render (renderedIds) and
     * whether its prepared data still holds KRX; a screen action's send; the components applied.
     */
    private data class AccessMatrix(
        val generalRead: Boolean, val krxRead: Boolean, val generalRender: Boolean, val krxRender: Boolean, val krxPrepared: Boolean,
        val send: Boolean, val generalApplied: Boolean, val krxApplied: Boolean,
        /** The recorder's exposure under the pre-state fence and lifetime (its data is kept under a hold). */
        val recorderRead: Boolean,
        /** Whether the topic use authority lets a new use start under the delivered fence (deletion is the graph's admission, not this). */
        val useStarts: Boolean,
        /** Whether the released 3m answer's KRX component reached the disk. */
        val krxWritten: Boolean,
        /** The in-state (released 3m) answer: its KRX in a protected read, and the screen moved to 3m rendering GENERAL and KRX. */
        val krxInStateRead: Boolean, val generalInStateRender: Boolean, val krxInStateRender: Boolean
    )

    private enum class AccessCase {
        RESOLVING, PENDING_NO_GRANT, KEPT_PENDING, KEPT_5XX, DELETION_REQUESTING, DELETION_DELETED, USER_P4, CAPABILITY_P4, CAPABILITY_SEAL
    }

    /** The design §4 table: blocked; kept grant allowed with its capability; capability-only blocks KRX alone. */
    private fun expected(case: AccessCase): AccessMatrix = when (case) {
        AccessCase.KEPT_PENDING, AccessCase.KEPT_5XX -> AccessMatrix(true, true, true, true, true, true, true, true,
            recorderRead = true, useStarts = true, krxWritten = true, krxInStateRead = true, generalInStateRender = true, krxInStateRender = true)
        AccessCase.CAPABILITY_P4, AccessCase.CAPABILITY_SEAL -> AccessMatrix(true, false, true, false, false, true, true, false,
            recorderRead = true, useStarts = true, krxWritten = false, krxInStateRead = false, generalInStateRender = true, krxInStateRender = false)
        AccessCase.DELETION_REQUESTING, AccessCase.DELETION_DELETED -> AccessMatrix(false, false, false, false, false, false, false, false,
            recorderRead = false, useStarts = true, krxWritten = false, krxInStateRead = false, generalInStateRender = false, krxInStateRender = false)
        else -> AccessMatrix(false, false, false, false, false, false, false, false,
            recorderRead = false, useStarts = false, krxWritten = false, krxInStateRead = false, generalInStateRender = false, krxInStateRender = false)
    }

    /**
     * RT01-A07: one access state on the production composition. From KRX VISIBLE with an applied usd 1d (GENERAL and KRX), KRX
     * selected on the screen by a real toggle and rendered, and a 3m tab in flight with its answer held, the state is entered by
     * real inputs (and, for P4 and the seal, the agreed targeted store faults); then: GENERAL and KRX protected reads of the 1d,
     * the screen's GENERAL and KRX render and its prepared KRX data, a send for a screen action with the pre-state token (a
     * never-fetched period), and — once the held 3m answer is released inside the state — whether its GENERAL entry and its KRX
     * component were applied. The result is compared with the design §4 table. The seal's rotation write fault stays armed to
     * the end; each failure is attributed to the first decision or to the seal-keeping recovery.
     */
    private fun accessStateRow(case: AccessCase) = runBlocking {
        val label = "RT01-A07 $case"
        val start = nowMillis()
        val deadline = start + 50_000
        entitlements += ok(premiumKrxVisible)
        val hold3m = java.util.concurrent.CountDownLatch(1)
        val holdDelete = java.util.concurrent.CountDownLatch(1)
        onCatalog = { ok(fixture("catalog-krx-visible.json")) }
        onTab = { request ->
            when (request.requestUrl!!.queryParameter("period")) {
                "3m" -> { hold3m.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("usd-3m-krx-visible.json")) }
                "1w" -> ok(fixture("usd-3m-krx-visible.json")) // a screen action's send: counted, its answer not judged
                else -> ok(fixture("usd-1d-krx-visible.json"))
            }
        }
        val rig = Rig()
        val deletion = java.util.concurrent.atomic.AtomicReference<Job?>(null)
        val resolving = java.util.concurrent.atomic.AtomicReference<Job?>(null)
        val sealFailure = java.io.IOException("access record write failed")
        try {
            rig.row {
                val holder = checkNotNull(rig.coldStart(deadline))
                rig.awaitTopics(deadline)
                awaitTrue("$label: catalog and the usd tab applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
                val usd1d = rig.onMain { rig.assembly.coordinator.state.value.entries.keys.single { it.tab == "usd" && it.period.code == "1d" } }
                rig.onMain {
                    val ids = checkNotNull(rig.assembly.coordinator.protectedEntry(usd1d)).tab.graph.series.map { it.seriesId }
                    assertTrue("$label: premise: GENERAL and KRX readable before the state: $ids", "kb.usd" in ids && "krx.usd-krw-futures" in ids)
                }
                // Each screen action uses the token the screen shows at that moment (a period change renews it).
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m tab is in flight", deadline) { tabs().any { it.param("period") == "3m" && it.end == null } }
                awaitTrue("$label: the screen offers a 3m token", deadline) {
                    rig.onMain { holder.currentState().inlineToken?.period == GraphPeriod.THREE_MONTHS }
                }
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.ONE_DAY) }
                awaitTrue("$label: the screen is back on the 1d chart", deadline) {
                    rig.onMain { holder.currentState().let { it.activePeriod == GraphPeriod.ONE_DAY && it.chart != null && it.inlineToken?.period == GraphPeriod.ONE_DAY } }
                }
                // KRX selected by a real screen action and rendered, so its render can be judged in the state.
                val krxId = "krx.usd-krw-futures"
                rig.onMain { holder.toggleSeries(checkNotNull(holder.currentState().inlineToken), krxId) }
                awaitTrue("$label: premise: the toggled KRX is rendered and prepared beside GENERAL", deadline) {
                    rig.onMain {
                        holder.currentState().chart?.let { chart ->
                            krxId in chart.renderedIds && chart.renderedIds.any { !it.startsWith("krx.") } && krxId in chart.prepared.bySeries
                        } == true
                    }
                }
                // The token, fence and lifetime valid just before the state; the recorder's data under them.
                val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })
                awaitTrue("$label: an acknowledged socket", deadline) { openSockets.isNotEmpty() }
                openSockets.last().send(usdSnapshot(1390.0))
                awaitTrue("$label: the WS delivery recorded kb.usd", deadline) { rig.hasKbSeries() }
                val fenceBefore = checkNotNull(rig.bridge())
                val lifetimeBefore = checkNotNull(rig.uses.acquire(fenceBefore))
                val userEndsBefore = rig.coordinator.accessSnapshot.lastUserEnd?.sequence ?: 0L
                val capEndsBefore = rig.coordinator.accessSnapshot.lastCapabilityEnd?.sequence ?: 0L

                // Enter the state.
                when (case) {
                    AccessCase.RESOLVING, AccessCase.PENDING_NO_GRANT -> {
                        entitlementsGate = java.util.concurrent.CountDownLatch(1)
                        val next = rig.firebase.nextGeneration()
                        rig.coordinator.onIdentityChanged(next)
                        if (case == AccessCase.PENDING_NO_GRANT) {
                            entitlementsGate?.countDown(); entitlementsGate = null
                            entitlements += ok("""{"krx_visible":true,"premium_pending":true,"retry_after_seconds":60}""")
                            kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
                            assertTrue("$label: premise: pending with no grant (${rig.coordinator.state.value.state})",
                                rig.coordinator.state.value.state is PremiumAccessState.Pending && rig.coordinator.topicGrantResult().fence == null)
                        } else {
                            // Resolving: the new identity's decision is in flight, its answer held at the server.
                            entitlements += ok(premiumKrxVisible)
                            resolving.set(CoroutineScope(rig.ioJob + Dispatchers.IO).launch {
                                runCatching { rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
                            })
                            awaitTrue("$label: the new identity's entitlements answer is held", deadline) { entitlementsHeld.get() >= 1 }
                            assertEquals("$label: premise: resolving", PremiumAccessState.NoGrant, rig.coordinator.state.value.state)
                        }
                    }
                    AccessCase.KEPT_PENDING, AccessCase.KEPT_5XX -> {
                        entitlements += if (case == AccessCase.KEPT_PENDING) ok("""{"krx_visible":true,"premium_pending":true,"retry_after_seconds":60}""")
                        else MockResponse().setResponseCode(503).setBody("""{"detail":"unavailable"}""")
                        kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                        assertEquals("$label: premise: the grant is kept", PremiumAccessState.PremiumConfirmed, rig.coordinator.state.value.state)
                    }
                    AccessCase.DELETION_REQUESTING, AccessCase.DELETION_DELETED -> {
                        onDelete = {
                            if (case == AccessCase.DELETION_REQUESTING) holdDelete.await(30, java.util.concurrent.TimeUnit.SECONDS)
                            MockResponse().setResponseCode(204)
                        }
                        val owner = checkNotNull(rig.provider.currentIdentityFence())
                        deletion.set(CoroutineScope(rig.ioJob + Dispatchers.IO).launch {
                            runCatching { com.jay.fxi.ui.settings.AccountDeletionServerStage(rig.api, rig.deletions).execute(owner) { it } }
                        })
                        val phase = if (case == AccessCase.DELETION_REQUESTING) com.jay.fxi.data.entitlements.DeletionAdmissionPhase.REQUESTING_SERVER
                        else com.jay.fxi.data.entitlements.DeletionAdmissionPhase.SERVER_DELETED
                        awaitTrue("$label: the deletion admission is $phase", deadline) { rig.deletions.records.any { it.phase == phase } }
                    }
                    AccessCase.USER_P4, AccessCase.CAPABILITY_P4 -> {
                        val failure = java.io.IOException("access record unreadable")
                        rig.storeLoadFault.arm(failure) { isIssuerDecision(it) }
                        rig.issuerIdentityReadable = false
                        entitlements += if (case == AccessCase.USER_P4) ok("""{"krx_visible":true,"premium_active":false,"premium_pending":false}""")
                        else ok(PREMIUM_HIDDEN)
                        kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                        assertEquals("$label: the decision's read failed once", listOf<Throwable>(failure), rig.storeLoadFault.thrown.toList())
                        assertEquals("$label: one candidate held", 1, rig.coordinator.heldLossCandidateCount())
                        val deniedBefore = rig.recoveryIdentityDenials.get()
                        rig.recoveryIdentityReadable = false
                        rig.issuerIdentityReadable = true
                        awaitTrue("$label: a candidate recovery round met the unreadable identity", deadline) {
                            rig.recoveryIdentityDenials.get() > deniedBefore
                        }
                        val facts = rig.coordinator.accessSnapshot.facts
                        val blocks = if (case == AccessCase.USER_P4) facts.userBlocks else facts.capabilityBlocks
                        assertTrue("$label: premise: the hold is published ($facts)", com.jay.fxi.data.entitlements.TopicAccessBlock.LOSS_CANDIDATE in blocks)
                    }
                    AccessCase.CAPABILITY_SEAL -> {
                        rig.storeRotationFault.armPersistent(sealFailure) { it.methodName == "beginRotation" || it.className.contains("\$beginRotation\$") }
                        entitlements += ok(PREMIUM_HIDDEN)
                        kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                        val facts = rig.coordinator.accessSnapshot.facts
                        assertEquals("$label: the first rotation write failed in the decision",
                            listOf(SealPath.DECISION), rig.storeRotationFault.hits.toList().map(::sealPath).take(1))
                        assertTrue("$label: premise: the capability is explicitly sealed ($facts)",
                            com.jay.fxi.data.entitlements.TopicAccessBlock.EXPLICIT_SEAL in facts.capabilityBlocks && facts.userAllowed)
                    }
                }

                // Measure.
                rig.onMain {
                    val st = holder.currentState()
                    println("CUT-C01 $label holder: content=${st.content} chart=${st.chart != null} inline=${st.inlineToken} preStateToken=$token period=${st.activePeriod} refreshing=${st.refreshing} owner=${rig.owner.consumer.currentState().ui.owner != null}")
                }
                val (generalRead, krxRead) = rig.onMain {
                    val ids = rig.assembly.coordinator.protectedEntry(usd1d)?.tab?.graph?.series?.map { it.seriesId }.orEmpty()
                    ("kb.usd" in ids) to ids.any { it.startsWith("krx.") }
                }
                val (generalRender, krxRender, krxPrepared) = rig.onMain {
                    val chart = holder.currentState().chart
                    Triple(chart != null && chart.renderedIds.any { !it.startsWith("krx.") }, chart?.renderedIds?.contains(krxId) == true,
                        chart?.prepared?.bySeries?.keys?.any { it.startsWith("krx.") } == true)
                }
                val recorderRead = rig.onMain { rig.assembly.recorder.exposed(fenceBefore, lifetimeBefore).isNotEmpty() }
                val useStarts = rig.bridge()?.let { rig.uses.acquire(it) } != null
                val graphBefore = graph().size
                rig.onMain { holder.selectPeriod(token, GraphPeriod.ONE_WEEK) }
                delay(1_500)
                val send = graph().drop(graphBefore).any { it.param("period") == "1w" }
                val krx3m = { rig.writtenFiles().any { it.startsWith("krx/") && it.endsWith("/757364/3m.json") } }
                assertFalse("$label: premise: no KRX 3m file before the answer", krx3m())
                hold3m.countDown()
                awaitTrue("$label: the held 3m answer ended", deadline) { tabs().first { it.param("period") == "3m" }.bodyEnd != null }
                delay(1_000)
                val (generalApplied, krxApplied, krxInStateRead) = rig.onMain {
                    val c = rig.assembly.coordinator
                    val key = c.state.value.entries.keys.firstOrNull { it.tab == "usd" && it.period.code == "3m" }
                    val components = key?.let { rig.slotComponents(it) }
                    val read = key?.let { c.protectedEntry(it) }?.tab?.graph?.series?.any { it.seriesId.startsWith("krx.") } == true
                    Triple(key != null, components?.krx != null, read)
                }
                // The in-state answer on the screen: move to 3m with the token the screen offers now, if it offers one.
                val moved = rig.onMain {
                    holder.currentState().inlineToken?.let { holder.selectPeriod(it, GraphPeriod.THREE_MONTHS); true } ?: false
                }
                val inStateChart = if (!moved) null else {
                    val until = nowMillis() + 2_000
                    var chart: com.jay.fxi.ui.premium.graph.GraphV2ChartModel? = null
                    while (chart == null && nowMillis() < until) {
                        chart = rig.onMain { holder.currentState().takeIf { it.activePeriod == GraphPeriod.THREE_MONTHS }?.chart }
                        if (chart == null) delay(20)
                    }
                    chart
                }
                val generalInStateRender = inStateChart?.renderedIds?.any { !it.startsWith("krx.") } == true
                val krxInStateRender = inStateChart?.renderedIds?.contains(krxId) == true
                val measured = AccessMatrix(generalRead, krxRead, generalRender, krxRender, krxPrepared, send, generalApplied, krxApplied,
                    recorderRead, useStarts, krx3m(), krxInStateRead, generalInStateRender, krxInStateRender)
                println("CUT-C01 $label matrix: $measured")
                assertEquals("$label: the design §4 matrix", expected(case), measured)
                if (case == AccessCase.CAPABILITY_SEAL) {
                    // To the end of the measurement, after the late answer and the write check: still sealed, the user allowed.
                    val facts = rig.coordinator.accessSnapshot.facts
                    assertTrue("$label: the seal holds to the end ($facts)",
                        com.jay.fxi.data.entitlements.TopicAccessBlock.EXPLICIT_SEAL in facts.capabilityBlocks && facts.userAllowed)
                    val hits = rig.storeRotationFault.hits.toList()
                    val thrown = rig.storeRotationFault.thrown.toList()
                    val paths = hits.map(::sealPath)
                    println("CUT-C01 $label rotation write failures: ${paths.groupingBy { it }.eachCount()} of ${hits.size}\n" + hits.joinToString("\n"))
                    assertEquals("$label: every failure recorded once with its call path", hits.size, thrown.size)
                    assertTrue("$label: every failure is the armed instance", thrown.all { it === sealFailure })
                    assertTrue("$label: every failure is on the decision or the seal-keeping recovery: $hits", SealPath.OTHER !in paths)
                    assertEquals("$label: the decision failed once, first", 1, paths.count { it == SealPath.DECISION })
                }
                if (case == AccessCase.USER_P4 || case == AccessCase.CAPABILITY_P4) {
                    // To the end of the measurement: still a held candidate, not a confirmed loss.
                    val snapshot = rig.coordinator.accessSnapshot
                    val blocks = if (case == AccessCase.USER_P4) snapshot.facts.userBlocks else snapshot.facts.capabilityBlocks
                    println("CUT-C01 $label recovery identity denials: ${rig.recoveryIdentityDenials.get()}")
                    assertEquals("$label: the candidate is still held at the end", 1, rig.coordinator.heldLossCandidateCount())
                    assertTrue("$label: the hold is still published at the end (${snapshot.facts})",
                        com.jay.fxi.data.entitlements.TopicAccessBlock.LOSS_CANDIDATE in blocks)
                    assertEquals("$label: no USER end", userEndsBefore, snapshot.lastUserEnd?.sequence ?: 0L)
                    assertEquals("$label: no capability end", capEndsBefore, snapshot.lastCapabilityEnd?.sequence ?: 0L)
                }
                // Release what the state held before the row's close joins the IO work.
                entitlementsGate?.countDown(); entitlementsGate = null
                holdDelete.countDown()
                deletion.get()?.join()
                resolving.get()?.join()
            }
        } finally {
            hold3m.countDown()
            holdDelete.countDown()
            entitlementsGate?.countDown(); entitlementsGate = null
            rig.storeRotationFault.disarm()
            deletion.get()?.join()
            resolving.get()?.join()
        }
        assertTrue("within the row deadline", nowMillis() - start <= 50_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    private enum class SealPath { DECISION, RECOVERY, OTHER }

    /** The production caller of a failed rotation write: the loss decision's first write, or a loss recovery round. */
    private fun sealPath(hit: String): SealPath = when {
        "rotateLossTargetsLocked" in hit && "lossRecoveryRoundLocked" !in hit -> SealPath.DECISION
        "lossRecoveryRoundLocked" in hit && "rotateLossTargetsLocked" !in hit -> SealPath.RECOVERY
        else -> SealPath.OTHER
    }

    @Test fun `RT01-A07 resolving blocks every graph use`() = accessStateRow(AccessCase.RESOLVING)
    @Test fun `RT01-A07 pending with no grant blocks every graph use`() = accessStateRow(AccessCase.PENDING_NO_GRANT)
    @Test fun `RT01-A07 a pending answer keeps the grant and its capability`() = accessStateRow(AccessCase.KEPT_PENDING)
    @Test fun `RT01-A07 an undecidable 5xx keeps the grant and its capability`() = accessStateRow(AccessCase.KEPT_5XX)
    @Test fun `RT01-A07 a deletion being requested blocks every graph use`() = accessStateRow(AccessCase.DELETION_REQUESTING)
    @Test fun `RT01-A07 a confirmed deletion blocks every graph use`() = accessStateRow(AccessCase.DELETION_DELETED)
    @Test fun `RT01-A07 a user P4 hold blocks every graph use`() = accessStateRow(AccessCase.USER_P4)
    @Test fun `RT01-A07 a capability P4 hold blocks KRX alone`() = accessStateRow(AccessCase.CAPABILITY_P4)
    @Test fun `RT01-A07 an explicit capability seal blocks KRX alone`() = accessStateRow(AccessCase.CAPABILITY_SEAL)

    // --- CUT-C01 batch 1b: RT01-A08 (cut_c01_b1_agreed.r1.md A08) --------------------------------------------------------

    /**
     * RT01-A08: under a USER P4 hold kept with the issuer's identity unreadable, a separate real entitlements query answers
     * pending with KRX visible and an 8 s retry floor. The answer cannot be decided, so the issuer owes a re-check at that floor;
     * the candidate recovery keeps re-checking on its own (counted apart). The scheduled entitlements send does not go before
     * the floor and does go after it, and all along the graph reads, renders, sends and applies nothing.
     */
    @Test
    fun `RT01-A08 a held user keeps its control queries and floor while the graph stays unused`() = runBlocking {
        val label = "RT01-A08"
        val start = nowMillis()
        val deadline = start + 50_000
        entitlements += ok(premiumKrxVisible)
        val hold3m = java.util.concurrent.CountDownLatch(1)
        onCatalog = { ok(fixture("catalog-krx-visible.json")) }
        onTab = { request ->
            when (request.requestUrl!!.queryParameter("period")) {
                "3m" -> { hold3m.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("usd-3m-krx-visible.json")) }
                "1w" -> ok(fixture("usd-3m-krx-visible.json"))
                else -> ok(fixture("usd-1d-krx-visible.json"))
            }
        }
        val rig = Rig()
        try {
            rig.row {
                val holder = checkNotNull(rig.coldStart(deadline))
                rig.awaitTopics(deadline)
                awaitTrue("$label: catalog and the usd tab applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
                val usd1d = rig.onMain { rig.assembly.coordinator.state.value.entries.keys.single { it.tab == "usd" && it.period.code == "1d" } }
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m tab is in flight", deadline) { tabs().any { it.param("period") == "3m" && it.end == null } }
                awaitTrue("$label: the screen offers a 3m token", deadline) { rig.onMain { holder.currentState().inlineToken?.period == GraphPeriod.THREE_MONTHS } }
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.ONE_DAY) }
                awaitTrue("$label: back on the 1d chart", deadline) {
                    rig.onMain { holder.currentState().let { it.activePeriod == GraphPeriod.ONE_DAY && it.chart != null && it.inlineToken?.period == GraphPeriod.ONE_DAY } }
                }
                val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })

                // The USER P4 hold, kept: the identity stays unreadable for every issuer read.
                val failure = java.io.IOException("access record unreadable")
                rig.storeLoadFault.arm(failure) { isIssuerDecision(it) }
                rig.issuerIdentityReadable = false
                entitlements += ok("""{"krx_visible":true,"premium_active":false,"premium_pending":false}""")
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                assertEquals("$label: the decision's read failed once", listOf<Throwable>(failure), rig.storeLoadFault.thrown.toList())
                assertEquals("$label: the user is held", 1, rig.coordinator.heldLossCandidateCount())
                val attemptsBefore = privateField(rig.coordinator, "candidateRecoveryAttempts") as Int

                // A separate real query: pending, KRX visible, an 8 s floor. Undecidable while the identity is unreadable.
                entitlements += ok("""{"krx_visible":true,"premium_pending":true,"retry_after_seconds":8}""")
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                val answeredAt = checkNotNull(sends.all().last { it.path == "/api/entitlements" }.bodyEnd)
                val sentBefore = sends.all().count { it.path == "/api/entitlements" }
                assertEquals("$label: the issuer owes a re-check of that query", RefreshIntent.FORCE_ENTITLEMENTS,
                    rig.coordinator.recheckDiagnostics().owedIntent)
                assertEquals("$label: still held", 1, rig.coordinator.heldLossCandidateCount())

                // All along: no graph read, render, send or apply.
                val graphBefore = graph().size
                rig.onMain {
                    assertNull("$label: no protected read", rig.assembly.coordinator.protectedEntry(usd1d))
                    assertNull("$label: nothing renders", holder.currentState().chart)
                    holder.selectPeriod(token, GraphPeriod.ONE_WEEK)
                }
                hold3m.countDown()
                awaitTrue("$label: the held 3m answer ended", deadline) { tabs().first { it.param("period") == "3m" }.bodyEnd != null }

                // The floor: no scheduled send before it, one after it.
                val floorAt = answeredAt + 8_000
                while (nowMillis() < floorAt - 300) {
                    assertEquals("$label: no entitlements send before the floor\n${sends.timeline()}",
                        sentBefore, sends.all().count { it.path == "/api/entitlements" })
                    assertEquals("$label: no graph send\n${sends.timeline()}", graphBefore, graph().size)
                    delay(50)
                }
                awaitTrue("$label: the scheduled re-check is sent after the floor", deadline) {
                    sends.all().count { it.path == "/api/entitlements" } > sentBefore
                }
                val scheduled = sends.all().filter { it.path == "/api/entitlements" }.drop(sentBefore).first()
                assertTrue("$label: not before the floor (${scheduled.start} >= ${floorAt - 50})", scheduled.start >= floorAt - 50)
                val attemptsAfter = privateField(rig.coordinator, "candidateRecoveryAttempts") as Int
                println("CUT-C01 $label candidate recovery attempts $attemptsBefore -> $attemptsAfter; entitlements sends ${sends.all().filter { it.path == "/api/entitlements" }.map { it.start }}")
                assertTrue("$label: the candidate recovery kept re-checking ($attemptsBefore -> $attemptsAfter)", attemptsAfter > attemptsBefore)
                assertEquals("$label: still held after the floor", 1, rig.coordinator.heldLossCandidateCount())
                assertEquals("$label: no graph send all along\n${sends.timeline()}", graphBefore, graph().size)
                assertTrue("$label: the held 3m answer is never applied",
                    rig.assembly.coordinator.state.value.entries.keys.none { it.period.code == "3m" })
                rig.onMain { assertNull("$label: still no protected read", rig.assembly.coordinator.protectedEntry(usd1d)) }
            }
        } finally {
            hold3m.countDown()
        }
        assertTrue("within the row deadline", nowMillis() - start <= 50_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /** Waits, before anything starts, until the next 600-second bucket boundary is more than [millis] away. */
    private suspend fun clearOfBoundary(millis: Long): Long {
        val before = nowMillis()
        while (600_000 - System.currentTimeMillis() % 600_000 <= millis) delay(500)
        return nowMillis() - before
    }

    private val ladder = listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L)

    /**
     * The whole recovery ladder of one (data scope, tab) cycle on the active usd 1d, on the real clock: six rounds at most,
     * the first included, each next round [ladder] after the previous tab completed; after the sixth, 49 s with no automatic
     * graph send. [catalogFails] also fails every catalog.
     */
    private fun ladderRow(label: String, catalogFails: Boolean) = runBlocking {
        val prepared = clearOfBoundary(190_000)
        println("CUT-C02 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 180_000
        entitlements += unauthorized()
        val unavailable = { MockResponse().setResponseCode(503).setBody("""{"detail":"unavailable"}""") }
        if (catalogFails) onCatalog = { unavailable() }
        onTab = { unavailable() }
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            awaitTrue("six tab rounds completed", deadline) { tabs().size == 6 && tabs().all { it.bodyEnd != null } }
            rig.awaitTopics(deadline)
            quiet(label, 49_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 180_000)
        assertEquals("six rounds, the first included", List(6) { 503 }, statuses(tabs()))
        val t = tabs()
        for (i in 0 until 5) {
            val gap = t[i + 1].start - checkNotNull(t[i].bodyEnd)
            assertTrue("$label: round ${i + 2} goes ${ladder[i]} ms after round ${i + 1} completed, not before (gap $gap)\n${sends.timeline()}",
                gap >= ladder[i] - 50)
            assertTrue("$label: and without a needless delay (gap $gap)\n${sends.timeline()}", gap <= ladder[i] + 2_000)
        }
        if (catalogFails) assertTrue("$label: every catalog failed", statuses(catalogs()).all { it == 503 })
        else assertEquals("$label: the catalog once", listOf(200), statuses(catalogs()))
        assertTopicSide(label)
        assertEquals(emptyList<Throwable>(), rig.reports)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /** A02a: catalog 200 and every tab 503 through the whole ladder, then exhausted. */
    @Test
    fun `A02a every tab 503 walks the whole ladder and stops`() = ladderRow("A02a", catalogFails = false)

    /** A02b: every catalog and tab 503 through the whole ladder, then exhausted. */
    @Test
    fun `A02b every catalog and tab 503 walks the whole ladder and stops`() = ladderRow("A02b", catalogFails = true)

    private fun Rig.writtenFiles(): List<String> =
        if (diskRoot.isDirectory) diskRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(diskRoot).path }.toList() else emptyList()

    /**
     * A05a: the applied 200 is written through the production write ports: the issuer marks that graph data may exist, the
     * disk store holds the tab, and no REST send is added.
     */
    @Test
    fun `A05a an applied tab is written through the production write ports with no further send`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            awaitTrue("the chart is published", deadline) { rig.onMain { holder.currentState().content == GraphV2Content.READY } }
            awaitTrue("the tab is on disk", deadline) { rig.writtenFiles().isNotEmpty() }
            rig.awaitTopics(deadline)
            quiet("A05a", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        assertTrue("the issuer marked graph data before the write", rig.marks.get() >= 1)
        println("CUT-C01 A05a files: ${rig.writtenFiles()}, marks: ${rig.marks.get()}")
        assertEquals(listOf(200), statuses(catalogs()))
        assertEquals(listOf(200), statuses(tabs()))
        assertTopicSide("A05a")
        assertEquals(emptyList<Throwable>(), rig.reports)
        ColdStartBudget.assertWithin(judgeRow("A05a"))
    }

    /**
     * A05b: a disk that refuses every write: the chart is still published from memory, nothing is written, no REST send is
     * added, and the failure stays inside the coordinator's diagnostic boundary (no graph failure reported).
     */
    @Test
    fun `A05b a refused write keeps the chart and adds no send`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val rig = Rig(blockDisk = true)
        rig.row {
            val holder = checkNotNull(rig.coldStart(deadline))
            awaitTrue("the chart is published", deadline) { rig.onMain { holder.currentState().content == GraphV2Content.READY } }
            rig.awaitTopics(deadline)
            quiet("A05b", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        assertTrue("premise: the disk root is not a directory", rig.diskRoot.isFile)
        assertEquals("nothing written", emptyList<String>(), rig.writtenFiles())
        assertEquals(listOf(200), statuses(catalogs()))
        assertEquals(listOf(200), statuses(tabs()))
        assertEquals("no graph failure reported", emptyList<Throwable>(), rig.reports)
        ColdStartBudget.assertWithin(judgeRow("A05b"))
    }

    private fun handshakes() = sends.all().filter { it.zone == "ws" }

    /**
     * A03a: six entitlement re-confirmations of the same grant while the graph is active: the grant, the connection and the
     * graph's sends stay as they were.
     */
    @Test
    fun `A03a six re-confirmations of the same grant change nothing`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 30_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            awaitTrue("the tab applied", deadline) { rig.usdApplied() }
            rig.awaitTopics(deadline)
            val grant = checkNotNull(rig.coordinator.topicGrantResult().fence)
            val graphBefore = graph().size
            repeat(6) {
                // A spaced control. Six back-to-back sequential calls exceeded the budget (api excess 10.023 > 10): that run is
                // kept as failing evidence (cut_c0102_observations/A03a_burst_excess.xml), not replaced by this row.
                delay(3_000)
                rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
                assertEquals("re-confirmation ${it + 1} keeps the grant", grant, rig.coordinator.topicGrantResult().fence)
            }
            quiet("A03a", 5_000)
            assertEquals("no graph send for a re-confirmation", graphBefore, graph().size)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 30_000)
        assertEquals("one connection", 1, handshakes().size)
        assertEquals("the six re-confirmations were sent", 2 + 6, sends.all().count { it.path == "/api/entitlements" })
        ColdStartBudget.assertWithin(judgeRow("A03a"))
    }

    /**
     * A03b: six real re-approvals: each connection's usd subscription is refused (premium_required) while the issuer cannot
     * read the identity, so the refusal becomes a pending loss the issuer re-checks after its floor and turns into a
     * RefusalReapproval grant that the deliverer hands the session — same identity and user epoch, a new grant, the permit
     * marked re-approved. The connection ladder is spent by the fifth; after the sixth no connection and no automatic
     * bootstrap opens for 10 s. No screen is open here: the permit's effect on a recovery is A04b and A04d.
     */
    @Test
    fun `A03b six real re-approvals spend the connection ladder and then open nothing`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 200_000
        entitlements += unauthorized()
        val rig = Rig()
        // The issuer's identity read comes back once the issuer has taken the refusal over (its re-check is owed).
        val restorer = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                if (!rig.issuerIdentityReadable && rig.coordinator.recheckDiagnostics().owedIntent != null) rig.issuerIdentityReadable = true
                delay(20)
            }
        }
        try {
            rig.row {
                // Armed before the start: every connection's usd subscription, the first included, is refused.
                refuseUsd = { rig.issuerIdentityReadable = false }
                rig.approve()
                val first = checkNotNull(rig.coordinator.topicGrantResult().fence)
                rig.startOwner()
                val grants = linkedSetOf(first.grant)
                awaitTrue("six re-approvals", deadline) {
                    rig.coordinator.topicGrantResult().fence?.grant?.let(grants::add)
                    grants.size >= 7
                }
                val graphBefore = graph().size
                val last = rig.coordinator.topicGrantResult()
                assertTrue("the last grant is a refusal re-approval: ${last.cause}",
                    last.cause is com.jay.fxi.data.entitlements.TopicGrantCause.RefusalReapproval)
                assertEquals("the same identity", first.identity, checkNotNull(last.fence).identity)
                assertEquals("the same user epoch", first.userAccessEpoch, last.fence.userAccessEpoch)
                val connections = handshakes().size
                val bootstraps = sends.all().count { it.path == "/api/v2/topics/snapshot" }
                quiet("A03b", 10_000)
                assertEquals("no connection after the spent ladder", connections, handshakes().size)
                assertEquals("no automatic bootstrap after the spent ladder", bootstraps, sends.all().count { it.path == "/api/v2/topics/snapshot" })
                // No screen is open in this row, so this is no evidence of a recovery waiting on the permit (that is A04b·A04d).
                assertEquals("no graph send after the spent ladder", graphBefore, graph().size)
                assertEquals("one connection per re-approval inside the ladder: the first and five more", 6, connections)
                val permit = rig.onMain { checkNotNull(rig.permitSlot).require()() }
                assertTrue("the permit is the re-approved grant's: $permit", permit?.reapproved == true && permit.fence == last.fence)
                println("CUT-C02 A03b: refusals=${refusals.get()}, connections=$connections, grants=${grants.size}, permit=$permit")
            }
        } finally {
            refuseUsd = null
            restorer.cancel()
        }
        assertTrue("within the row deadline", nowMillis() - start <= 200_000)
        ColdStartBudget.assertWithin(judgeRow("A03b"))
    }


    /** The recovery events' issued-trigger count (RT05 sequence), read on Main. */
    private suspend fun Rig.triggers(): Long = onMain {
        val e = assembly.events
        e.javaClass.getDeclaredField("sequence").apply { isAccessible = true }.get(e) as Long
    }

    /**
     * A04a: through the production permit publisher and RT05: the first Connection is the baseline and issues nothing; the
     * server dropping the socket brings a new Connection, which issues one RECONNECT; a second drop inside the 30 s cooldown
     * brings another Connection and issues nothing, and nothing skipped is replayed.
     */
    @Test
    fun `A04a a new connection issues one reconnect trigger and one inside the cooldown issues none`() = runBlocking {
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            awaitTrue("the tab applied", deadline) { rig.usdApplied() }
            rig.awaitTopics(deadline)
            assertEquals("the first connection is the baseline: no trigger", 0L, rig.triggers())
            val tabsBefore = tabs().size
            openSockets.last().close(1011, "server restart")
            awaitTrue("a second connection", deadline) { handshakes().size == 2 }
            awaitTrue("one reconnect trigger", deadline) { rig.triggers() == 1L }
            delay(3_000)
            val afterFirst = tabs().size
            println("CUT-C02 A04a after the first reconnect: tabs ${tabsBefore} -> ${afterFirst}, triggers ${rig.triggers()}")
            openSockets.last().close(1011, "server restart")
            awaitTrue("a third connection", deadline) { handshakes().size == 3 }
            delay(3_000)
            assertEquals("inside the cooldown the new connection issues nothing", 1L, rig.triggers())
            assertTrue("at most one recovery send for the trigger\n${sends.timeline()}", afterFirst - tabsBefore <= 1)
            quiet("A04a", 5_000)
            assertEquals("nothing skipped is replayed", 1L, rig.triggers())
        }
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow("A04a"))
    }

    /**
     * A04c: a real 61 s in the background and the return: the process foreground reaches the recovery events through the
     * owner, the return issues one FOREGROUND_RETURN (the cooldown long past, a published context), and the background
     * itself issues nothing.
     */
    @Test
    fun `A04c a return after a minute in the background issues one foreground-return trigger`() = runBlocking {
        val prepared = clearOfBoundary(110_000)
        println("CUT-C02 A04c: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 100_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            awaitTrue("the tab applied", deadline) { rig.usdApplied() }
            rig.awaitTopics(deadline)
            assertEquals("premise: no trigger yet", 0L, rig.triggers())
            rig.foreground.value = false
            delay(61_000)
            assertEquals("the background issues nothing", 0L, rig.triggers())
            rig.foreground.value = true
            awaitTrue("one foreground-return trigger", deadline) { rig.triggers() == 1L }
            quiet("A04c", 5_000)
            assertEquals("exactly one", 1L, rig.triggers())
        }
        assertTrue("within the row deadline", nowMillis() - start <= 100_000)
        ColdStartBudget.assertWithin(judgeRow("A04c"))
    }

    // --- demand from real deliveries (cut_c0102_progress.r1/codex.r1.md §3-2) ------------------------------------------------

    private val kst = java.time.ZoneOffset.ofHours(9)
    private val isoKst = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private fun isoAt(epochSecond: Long) = isoKst.format(java.time.Instant.ofEpochSecond(epochSecond).atOffset(kst))

    /** The usd topic snapshot (contract fixture envelope) with one kb quote stamped now and nothing else. */
    private fun usdSnapshot(rate: Double = 1390.0): String {
        val base = wire.parseToJsonElement(File("src/test/resources/contracts/v2/topic/snapshot-fx-usd-krw.json").readText()).jsonObject
        val data = base.getValue("data").jsonObject
        val kb = kotlinx.serialization.json.buildJsonObject {
            put("asset", kotlinx.serialization.json.JsonPrimitive("usd-krw"))
            put("rate", kotlinx.serialization.json.JsonPrimitive(rate))
            put("source", kotlinx.serialization.json.JsonPrimitive("kb"))
            put("timestamp", kotlinx.serialization.json.JsonPrimitive(isoAt(System.currentTimeMillis() / 1000)))
        }
        // One kb quote and nothing else (no reference): the recorder gets kb.usd alone.
        val newData = kotlinx.serialization.json.JsonObject(
            data.filterKeys { it != "reference" } + ("banks" to kotlinx.serialization.json.JsonArray(listOf(kb)))
        )
        return kotlinx.serialization.json.JsonObject(base + ("data" to newData)).toString()
    }

    /** The closed 10-minute starts of the last 24 h before the current bucket, oldest first (144). */
    private fun closedStarts(): List<Long> {
        val now = System.currentTimeMillis() / 1000
        val current = now - now % 600
        return (1..144).map { current - it * 600L }.reversed()
    }

    /** A usd 1d tab whose kb.usd holds a point at each of [starts], stamped from the test's clock. */
    private fun usdTab(starts: List<Long>): String {
        val base = wire.parseToJsonElement(fixture("usd-1d-krx-hidden.json")).jsonObject
        val now = System.currentTimeMillis() / 1000
        val current = now - now % 600
        val points = kotlinx.serialization.json.JsonArray(starts.map { s ->
            kotlinx.serialization.json.buildJsonObject {
                put("high", kotlinx.serialization.json.JsonPrimitive(1390.0)); put("low", kotlinx.serialization.json.JsonPrimitive(1390.0))
                put("rate", kotlinx.serialization.json.JsonPrimitive(1390.0)); put("source", kotlinx.serialization.json.JsonPrimitive("kb"))
                put("ts", kotlinx.serialization.json.JsonPrimitive(isoAt(s)))
            }
        })
        val series = kotlinx.serialization.json.JsonArray(base.getValue("series").jsonArray.map { element ->
            val obj = element.jsonObject
            if (obj.getValue("id").jsonPrimitive.content == "kb.usd") kotlinx.serialization.json.JsonObject(obj + ("data" to points)) else obj
        })
        val metadata = kotlinx.serialization.json.JsonObject(base.getValue("metadata").jsonObject + mapOf(
            "domain_start_at" to kotlinx.serialization.json.JsonPrimitive(isoAt(current - 86_400)),
            "domain_end_at" to kotlinx.serialization.json.JsonPrimitive(isoAt(current)),
            "fetched_at" to kotlinx.serialization.json.JsonPrimitive(isoAt(now))
        ))
        return kotlinx.serialization.json.JsonObject(base + mapOf("series" to series, "metadata" to metadata)).toString()
    }

    /** The recorder's usd demand for the current grant, read on Main. */
    private suspend fun Rig.usdDemand(): com.jay.fxi.data.graph.GraphTabRecoveryDemand {
        val fence = checkNotNull(coordinator.topicGrantResult().fence)
        val lifetime = checkNotNull(uses.acquire(fence))
        return onMain { assembly.recorder.recoveryDemand("usd", fence, lifetime, kotlinx.datetime.Clock.System.now()) }
    }

    /** The graph coordinator's in-memory protected components for [key] (its slot), or null; read on Main. */
    private fun Rig.slotComponents(key: com.jay.fxi.data.graph.GraphKey): com.jay.fxi.data.graph.GraphV2DiskComponents? {
        val c = assembly.coordinator
        @Suppress("UNCHECKED_CAST")
        val slot = (privateField(c, "protectedSlots") as Map<Any, Any>)[key] ?: return null
        return privateField(slot, "components") as com.jay.fxi.data.graph.GraphV2DiskComponents
    }

    /** kb.usd's pending closed starts (epoch seconds), read on Main. */
    private suspend fun Rig.kbClosedPending(): Set<Long> = onMain {
        val now = System.currentTimeMillis() / 1000
        val current = now - now % 600
        assembly.recorder.state.value.series.filterKeys { it.seriesId == "kb.usd" }.values.single()
            .pending.keys.map { it.epochSeconds }.filter { it < current }.toSet()
    }

    private suspend fun Rig.hasKbSeries(): Boolean = onMain {
        assembly.recorder.state.value.series.keys.any { it.seriesId == "kb.usd" }
    }

    /**
     * The common preparation (codex §3-2 ①–④): a real REST bootstrap of the usd topic creates kb.usd in the recorder, then
     * the first current-time WS snapshot on the acknowledged socket resumes that path, which demands the last 24 h of closed
     * buckets.
     */
    private suspend fun Rig.prepareClosedDemand(deadline: Long, krx: Boolean = false) {
        usdSnapshotLive = true
        if (krx) onCatalog = { ok(fixture("catalog-krx-visible.json")) }
        // The first tab answers only once the catalog is adopted, so no cold recovery budget is left from the start and the
        // reconnect opens the cycle at its first round (a tab completing before the catalog would leave one with a 3 s deadline).
        onTab = {
            val until = System.nanoTime() + 10_000_000_000L
            while (parts == null || assembly.coordinator.state.value.catalog == null) {
                if (System.nanoTime() > until) break
                Thread.sleep(10)
            }
            ok(fixture(if (krx) "usd-1d-krx-visible.json" else "usd-1d-krx-hidden.json"))
        }
        coldStart(deadline)
        awaitTrue("catalog adopted", deadline) { catalogAdopted() }
        awaitTrue("the first tab applied", deadline) { usdApplied() && tabs().all { it.bodyEnd != null } }
        awaitTrue("the REST delivery created kb.usd", deadline) { hasKbSeries() }
        usdSnapshotLive = false
        awaitTrue("an acknowledged socket", deadline) { openSockets.isNotEmpty() }
        openSockets.last().send(usdSnapshot())
        awaitTrue("a closed demand from the resumed WS path", deadline) {
            usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false)
        }
    }

    /**
     * A09: a closed demand made by real deliveries, a reconnect that opens the recovery cycle, two partial 200s that leave
     * buckets unmet (each next round 3 s, then 6 s after the previous completed), and a third 200 that meets the rest.
     */
    @Test
    fun `A09 a real closed demand survives two partial 200s and the third meets it`() = runBlocking {
        val prepared = clearOfBoundary(30_000)
        println("CUT-C02 A09: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 20_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            val starts = closedStarts()
            val baseline = tabs().size
            val recoveryCalls = AtomicInteger()
            onTab = {
                when (recoveryCalls.getAndIncrement()) {
                    0 -> ok(usdTab(starts.take(48)))
                    1 -> ok(usdTab(starts.take(96)))
                    else -> ok(usdTab(starts))
                }
            }
            assertEquals("premise: every closed start is demanded", starts.toSet(), rig.kbClosedPending())
            openSockets.last().close(1011, "server restart")
            awaitTrue("the first partial applied", deadline) { tabs().size == baseline + 1 && tabs().last().bodyEnd != null && rig.kbClosedPending().size < 144 }
            assertEquals("the first partial released exactly the starts it gave", starts.drop(48).toSet(), rig.kbClosedPending())
            awaitTrue("the second partial applied", deadline) { tabs().size == baseline + 2 && tabs().last().bodyEnd != null && rig.kbClosedPending().size < 96 }
            assertEquals("the second partial released exactly the starts it gave", starts.drop(96).toSet(), rig.kbClosedPending())
            awaitTrue("three recovery rounds", deadline) { tabs().size == baseline + 3 && tabs().all { it.bodyEnd != null } }
            awaitTrue("the demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
            quiet("A09", 5_000)
            val r = tabs().drop(baseline)
            assertTrue("the second round goes 3 s after the first completed\n${sends.timeline()}", r[1].start >= checkNotNull(r[0].bodyEnd) + 3_000 - 50)
            assertTrue("the third round goes 6 s after the second completed\n${sends.timeline()}", r[2].start >= checkNotNull(r[1].bodyEnd) + 6_000 - 50)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 20_000)
        ColdStartBudget.assertWithin(judgeRow("A09"))
    }

    /** The usd recovery budget's observable fields (rounds, completionRung, deadline, permitWait set, stop), read on Main. */
    private data class BudgetView(val rounds: Int, val completionRung: Int, val deadline: kotlinx.datetime.Instant?, val waitingPermit: Boolean, val stop: Any?)

    private suspend fun Rig.usdBudget(): BudgetView? = onMain {
        val c = assembly.coordinator
        @Suppress("UNCHECKED_CAST")
        val budgets = c.javaClass.getDeclaredField("recoveryBudgets").apply { isAccessible = true }.get(c) as Map<Any, Any>
        budgets.values.singleOrNull()?.let { b ->
            fun f(name: String) = b.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(b)
            BudgetView(f("rounds") as Int, f("completionRung") as Int, f("deadline") as kotlinx.datetime.Instant?, f("permitWait") != null, f("stop"))
        }
    }

    /**
     * A04b: the recovery waits on the production permit and resumes it as it was. After two partial 200s the budget's next
     * round is due 6 s on; going offline before then closes the permit (automatic false), so past that deadline nothing is
     * sent and the budget waits for the permit with its rounds, rung, absolute deadline and demand unchanged. Back online,
     * the new publication resumes the budget on its original deadline — already passed, so at once, with no new interval —
     * and the third 200 meets the demand.
     */
    @Test
    fun `A04b offline makes the recovery wait on the permit and online resumes it on its original deadline`() = runBlocking {
        val prepared = clearOfBoundary(50_000)
        println("CUT-C02 A04b: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            val starts = closedStarts()
            val baseline = tabs().size
            val recoveryCalls = AtomicInteger()
            onTab = {
                when (recoveryCalls.getAndIncrement()) {
                    0 -> ok(usdTab(starts.take(48)))
                    1 -> ok(usdTab(starts.take(96)))
                    else -> ok(usdTab(starts))
                }
            }
            openSockets.last().close(1011, "server restart")
            awaitTrue("the second partial applied", deadline) { tabs().size == baseline + 2 && tabs().last().bodyEnd != null && rig.kbClosedPending().size < 96 }
            val before = checkNotNull(rig.usdBudget()) { "no recovery budget" }
            val due = checkNotNull(before.deadline)
            assertTrue("premise: the next round is still ahead", kotlinx.datetime.Clock.System.now() < due)
            rig.online.value = false
            awaitTrue("the permit closed", deadline) { rig.onMain { checkNotNull(rig.permitSlot).require()() }?.automatic != true }
            while (kotlinx.datetime.Clock.System.now() < due + 2.seconds) delay(50)
            assertEquals("nothing sent past the original deadline", baseline + 2, tabs().size)
            val waiting = checkNotNull(rig.usdBudget())
            assertTrue("the budget waits for the permit: $waiting", waiting.waitingPermit)
            assertEquals("rounds, rung and absolute deadline kept", before.copy(waitingPermit = true), waiting)
            assertEquals("the unmet demand kept", starts.drop(96).toSet(), rig.kbClosedPending())
            val resumedAt = nowMillis()
            rig.online.value = true
            awaitTrue("the third round", deadline) { tabs().size == baseline + 3 && tabs().last().bodyEnd != null }
            assertTrue("resumed on the original deadline, with no new interval\n${sends.timeline()}", tabs().last().start - resumedAt < 3_000)
            awaitTrue("the demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
            quiet("A04b", 5_000)
        }
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow("A04b"))
    }

    /**
     * A04d: a re-approved grant's recovery waits for that grant's Connection and goes before it opens. With a closed demand
     * and an open recovery cycle, the next subscription is refused: the issuer re-approves (same user epoch, new grant) and
     * the session publishes a re-approved permit with no Connection, which is not automatic, so the round that falls due waits
     * and nothing is sent. Once the new grant's Connection exists — its 101 held by the server — the permit turns automatic
     * and the waiting round goes before the socket is open.
     */
    @Test
    fun `A04d a re-approved grant's recovery waits for its connection and goes before it opens`() = runBlocking {
        val prepared = clearOfBoundary(50_000)
        println("CUT-C02 A04d: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val rig = Rig()
        val restorer = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                if (!rig.issuerIdentityReadable && rig.coordinator.recheckDiagnostics().owedIntent != null) rig.issuerIdentityReadable = true
                delay(20)
            }
        }
        // When the production permit first stopped being automatic after the reconnect's Connection reached the server (the
        // refusal's closing): the window that must send nothing starts there. Polled every 5 ms on Main.
        val permitClosedAt = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val watchPermit = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            rig.row {
                rig.prepareClosedDemand(deadline)
                val starts = closedStarts()
                val baseline = tabs().size
                val recoveryCalls = AtomicInteger()
                onTab = {
                    when (recoveryCalls.getAndIncrement()) {
                        0 -> ok(usdTab(starts.take(48)))
                        1 -> ok(usdTab(starts.take(96)))
                        else -> ok(usdTab(starts))
                    }
                }
                val first = checkNotNull(rig.coordinator.topicGrantResult().fence)
                // The reconnect's subscription is refused once; the connection after the re-approval has its 101 held 4 s.
                var refusedAt = Long.MAX_VALUE
                refuseUsd = {
                    refuseUsd = null
                    refusedAt = nowMillis()
                    rig.issuerIdentityReadable = false
                    holdHandshakeMillis.set(4_000L)
                }
                val closedAt = nowMillis()
                val arrivalsAtClose = wsArrivals.size
                watchPermit.set(true)
                val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    while (permitClosedAt.get() == Long.MAX_VALUE) {
                        val permit = rig.onMain { rig.permitSlot?.require()?.invoke() }
                        // Armed once the reconnect's Connection reached the server, so the closing seen is the refusal's,
                        // not the dropped socket's own gap before that Connection.
                        if (watchPermit.get() && wsArrivals.size > arrivalsAtClose && permit?.automatic != true) {
                            permitClosedAt.compareAndSet(Long.MAX_VALUE, nowMillis())
                        }
                        delay(5)
                    }
                }
                openSockets.last().close(1011, "server restart")
                awaitTrue("a re-approval", deadline) { rig.coordinator.topicGrantResult().fence?.grant?.let { it != first.grant } == true }
                val reapprovedAt = nowMillis()
                val reapproved = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("the same identity", first.identity, reapproved.identity)
                assertEquals("the same user epoch", first.userAccessEpoch, reapproved.userAccessEpoch)
                assertTrue("premise: the reconnect's subscription was refused", refusals.get() >= 1)
                awaitTrue("the re-approved grant's connection reaches the server", deadline) { wsArrivals.size >= arrivalsAtClose + 2 }
                awaitTrue("its handshake is recorded", deadline) { handshakes().size >= 3 }
                // The session's Connection exists from the client's connect, before the server sees the request: the client-side
                // handshake start is the reference (the server's arrival is a few ms later).
                val connectionAt = minOf(handshakes()[2].start, wsArrivals[arrivalsAtClose + 1])
                assertTrue("premise: that connection came after the re-approval", connectionAt >= reapprovedAt - 50)
                watcher.cancel()
                // While the permit is still automatic — the reconnect's own Connection before its refusal is handled — a send is
                // allowed (RT01-A10, the window before the refusal is taken over). From the permit's closing to the re-approved
                // grant's connection nothing may depart; 50 ms covers a send already decided when the permit closed (the
                // decision runs on Main, the send's start is stamped on the network thread), and 50 ms before the connection
                // covers the Connection existing just before its request is stamped. A round falling due inside the window
                // (3 s and later) is far outside both margins.
                val closing = permitClosedAt.get()
                assertTrue("premise: the permit closed before the re-approval", closing <= reapprovedAt)
                assertEquals("no recovery send from the permit's closing until the re-approved grant's connection " +
                    "(closing=$closing connection=$connectionAt arrivals=${wsArrivals.toList()} tabs=${tabs().map { it.start }})\n${sends.timeline()}",
                    0, tabs().count { it.start in (closing + 50) until (connectionAt - 50) })
                awaitTrue("the waiting round goes", deadline) { tabs().any { it.start >= connectionAt - 50 } }
                val round = tabs().first { it.start >= connectionAt - 50 }
                awaitTrue("the held socket opens", deadline) { handshakes().size >= 3 && handshakes()[2].end != null }
                val opened = checkNotNull(handshakes()[2].end)
                assertTrue("the round goes once the connection exists\n${sends.timeline()}", round.start >= connectionAt - 50)
                assertTrue("and before the held socket opens\n${sends.timeline()}", round.start < opened)
                println("CUT-C02 A04d: closed@$closedAt refused@$refusedAt permitClosed@${permitClosedAt.get()} reapproved@$reapprovedAt connection@$connectionAt round@${round.start} opened@$opened")
                awaitTrue("the demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
                quiet("A04d", 5_000)
                val permit = rig.onMain { checkNotNull(rig.permitSlot).require()() }
                assertTrue("the permit is the re-approved grant's, automatic with its live connection: $permit",
                    permit != null && permit.fence == reapproved && permit.reapproved && permit.automatic && permit.connectionGeneration != null)
                println("CUT-C02 A04d permit after the round: $permit")
            }
        } finally {
            refuseUsd = null
            restorer.cancel()
        }
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow("A04d"))
    }
}
