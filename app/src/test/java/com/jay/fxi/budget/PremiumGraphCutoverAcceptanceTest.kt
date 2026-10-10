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
        /** The test-only request header [permitAtSend] fills. */
        const val PERMIT_HEADER = "X-Cut-Permit"
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
    /**
     * While set, read on the client's thread as each graph REST request goes out — after the coordinator's send checkpoint —
     * and sent with it as [PERMIT_HEADER], so a server hook knows what held when it was sent.
     */
    @Volatile private var permitAtSend: (() -> String?)? = null
    private val tetherCalls = AtomicInteger()
    private val wire = Json { ignoreUnknownKeys = true }
    /** While set, the usd topic's REST bootstrap answers a current kb quote (a real delivery); otherwise 404 as before. */
    @Volatile private var usdSnapshotLive = false
    /** The usd REST bootstrap body while [usdSnapshotLive]: one kb quote stamped now, unless a row sets it. */
    @Volatile private var usdBootstrap: () -> String = { usdSnapshot() }

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
    /** Batch 2a: each socket on which the server has sent an acknowledgement admitting the usd topic, after the send. */
    private val usdAcked: MutableList<WebSocket> = Collections.synchronizedList(mutableListOf())

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
                if (usd in topics) usdAcked += webSocket
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
                        if (url.queryParameter("topic") == "fx:usd-krw" && usdSnapshotLive) ok(usdBootstrap())
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

    /**
     * The stored last tab: usd, so the focus provider restores usd for the live identity. A row can script `lastTab` call by
     * call ([script]: a value, a non-cancellation failure, or a suspend gate then a value; gates suspend, so the runtime's serial
     * executor is never blocked); every call's entry and its return or failure are logged.
     */
    private class Tabs(@Volatile var tab: FreeTab) : FreeTabStore {
        val script = ConcurrentLinkedQueue<suspend () -> FreeTab>()
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun lastTab(uid: String): FreeTab {
            log += "enter"
            val step = script.poll()
            val result = try {
                step?.invoke() ?: tab
            } catch (failure: Exception) {
                if (failure !is kotlinx.coroutines.CancellationException) log += "throw"
                throw failure
            }
            log += "return $result"
            return result
        }
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
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val tag = if (request.url.encodedPath.startsWith("/api/v2/graph/")) permitAtSend?.invoke() else null
                chain.proceed(if (tag == null) request else request.newBuilder().header(PERMIT_HEADER, tag).build())
            }
            .addNetworkInterceptor(sends.interceptor)
            .build()

    private inner class Rig(identity: AuthIdentity? = AuthIdentity("user-a", 1), blockDisk: Boolean = false, blockKrx: Boolean = false) {
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
        val diskRoot = File(root, "graph_v2").also {
            if (blockDisk) it.writeText("not a directory")
            // S4 C01 3b: only the KRX tree blocked, so KRX writes fail while general writes go on.
            if (blockKrx) File(it.apply { mkdirs() }, "krx").writeText("not a directory")
        }
        /**
         * S4 C01 3b: the next socket factory call throws once; [thrownConnect] is that call's number in [connectCalls]. The
         * number is the session's attempt (generation) number: `++generation` happens only before `connect(number)`
         * (TopicSessionCoordinator.kt:1665-1671), whose `connect` calls the factory once (TopicRuntime.kt:131-134), and a rig
         * has one owner, one runtime and one session.
         */
        @Volatile var throwNextConnect = false
        @Volatile var thrownConnect: Long? = null
        /** S4 C01 3b: socket factory calls so far. */
        val connectCalls = AtomicInteger(0)
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
                webSocketFactory = {
                    val call = connectCalls.incrementAndGet()
                    if (throwNextConnect) {
                        throwNextConnect = false
                        thrownConnect = call.toLong()
                        throw java.io.IOException("the row refuses this connect")
                    }
                    ws
                },
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

        /**
         * S4 C01 batch 3 (cut_c01_b3_agreed.r1.md §4-2): the premium route's target rule (PremiumTopicRoute.kt:86-92) — the
         * selected tab's mounted holder with the screen owner. A target change deactivates the previous holder before
         * activating the next, a null target only deactivates, and [RouteStandIn.close] ends the route (its target
         * deactivated) and then closes the mount, as the route's effects are disposed. Rows before batch 3 keep [openScreen].
         */
        suspend fun openRoute(deadline: Long): RouteStandIn {
            awaitTrue("the host is published", deadline) { onMain { owner.graphHost.value != null } }
            return onMain {
                val opened = checkNotNull(owner.graphHost.value).open()
                mount = opened
                val route = RouteStandIn(opened)
                route.job = CoroutineScope(ownerJob + main).launch {
                    kotlinx.coroutines.flow.combine(owner.consumer.state, opened.holders) { _, holders -> holders }.collect { holders ->
                        val ui = owner.consumer.currentState().ui
                        val holder = ui.selectedTab?.serverTab?.let { holders[it] }
                        val screenOwner = ui.owner
                        val next = if (screenOwner != null && holder != null) holder to screenOwner else null
                        if (next != route.target) {
                            route.target?.first?.onDeactivated()
                            route.target = next
                            next?.let { (h, o) -> h.onActivated(o) }
                        }
                    }
                }
                route
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
        val rigRef = java.util.concurrent.atomic.AtomicReference<Rig?>(null)
        val catalogSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        onTab = { request ->
            when (request.requestUrl!!.queryParameter("period")) {
                "3m" -> { hold3m.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("usd-3m-krx-visible.json")) }
                "1w" -> ok(fixture("usd-3m-krx-visible.json")) // a screen action's send: counted, its answer not judged
                else -> {
                    // The first 1d answer waits for the catalog's adoption, so the screen initializes its selection from the
                    // catalog's defaults (without a catalog it would show every admitted series, KRX included).
                    val until = System.nanoTime() + 10_000_000_000L
                    while (!catalogSeen.get() && System.nanoTime() < until) {
                        if (rigRef.get()?.let { it.parts != null && it.assembly.coordinator.state.value.catalog != null } == true) {
                            catalogSeen.set(true)
                        } else Thread.sleep(10)
                    }
                    ok(fixture("usd-1d-krx-visible.json"))
                }
            }
        }
        val rig = Rig()
        rigRef.set(rig)
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
                // KRX selected by a real screen action and rendered, so its render can be judged in the state. Before it, the
                // confirmed selection (the catalog's defaults) holds no KRX and the toggle is offered.
                val krxId = "krx.usd-krw-futures"
                val ready = com.jay.fxi.ui.premium.graph.GraphV2SelectionStatus.READY
                rig.onMain {
                    val st = holder.currentState()
                    assertTrue("$label: premise: a ready selection without KRX, its toggle offered (${st.selectionStatus} ${st.selection})",
                        st.selectionStatus == ready && st.selection?.visibleSeriesIds?.contains(krxId) == false &&
                            st.toggles.any { it.seriesId == krxId && it.enabled && !it.selected })
                    holder.toggleSeries(checkNotNull(st.inlineToken), krxId)
                }
                awaitTrue("$label: premise: the toggle committed KRX to the confirmed selection and it renders beside GENERAL", deadline) {
                    rig.onMain {
                        val st = holder.currentState()
                        st.selectionStatus == ready && st.selection?.visibleSeriesIds?.contains(krxId) == true && st.chart?.let { chart ->
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
        if (catalogFails) onCatalog = { unavailable() }
        val rig = Rig()
        // RT03b-Q03 (agreed b2 r3): each tab's registration as it is sent — no catalog adopted, no recovery capture.
        val registrations = Collections.synchronizedList(mutableListOf<TabRegistrationView>())
        onTab = {
            if (catalogFails) registrations += runBlocking { rig.usdTabRegistration() }
            unavailable()
        }
        rig.row {
            rig.coldStart(deadline)
            awaitTrue("six tab rounds completed", deadline) { tabs().size == 6 && tabs().all { it.bodyEnd != null } }
            rig.awaitTopics(deadline)
            if (catalogFails) {
                awaitTrue("$label: the cycle is exhausted", deadline) { rig.usdBudget()?.stop?.toString() == "EXHAUSTED" }
                val budget = checkNotNull(rig.usdBudget())
                assertEquals("$label: one cycle of six rounds ($budget)", 6, budget.rounds)
                assertEquals("$label: the catalog requirement is kept", com.jay.fxi.data.graph.GraphTabRecoveryDemand.CatalogRequired, rig.usdDemand())
            }
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
        if (catalogFails) {
            assertEquals("$label: every catalog failed, one per round with the initial one (six)\n${sends.timeline()}", List(6) { 503 }, statuses(catalogs()))
            val registered = synchronized(registrations) { registrations.toList() }
            assertEquals("$label: every tab was registered with no catalog and no recovery capture: $registered",
                List(6) { TabRegistrationView(false, emptyList()) }, registered)
            // Each later round issues at most one catalog and one tab: between two tab sends, at most one catalog send.
            val c = catalogs()
            for (i in 0 until 5) {
                val between = c.filter { it.start > checkNotNull(t[i].bodyEnd) && it.start <= t[i + 1].start }
                assertTrue("$label: round ${i + 2} issued at most one catalog (${between.size})\n${sends.timeline()}", between.size <= 1)
                // C03/C04: the round's catalog goes alone and ends before its tab.
                between.forEach {
                    assertTrue("$label: round ${i + 2}'s catalog ended before its tab\n${sends.timeline()}", checkNotNull(it.bodyEnd) <= t[i + 1].start)
                }
            }
        } else assertEquals("$label: the catalog once", listOf(200), statuses(catalogs()))
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

    // --- CUT-C01 batch 2a: RT03b-Q01 (cut_c01_b2_agreed.r1.md) ---------------------------------------------------------------

    /** Waits for a socket newer than [before] sockets whose usd subscription the server has acknowledged (a data frame sent after
     *  it on that socket reaches the client after the acknowledgement). */
    private suspend fun awaitUsdAcknowledged(label: String, before: Int, deadline: Long) =
        awaitTrue("$label: a new socket with the usd topic acknowledged", deadline) {
            openSockets.size > before && synchronized(usdAcked) { usdAcked.any { it === openSockets.last() } }
        }

    /**
     * Ends a row whose topic session was replaced: every topic snapshot of the new session since [since] has been answered,
     * every graph body has ended, the server and the recorder agree, and nothing more goes for a second.
     */
    private suspend fun settleNewSession(label: String, since: Long, deadline: Long) {
        awaitTrue("$label: the new session's topic snapshots", deadline) {
            DESIRED.all { topic -> sends.all().any { it.param("topic") == topic && it.start >= since && it.bodyEnd != null } }
        }
        awaitTrue("$label: every graph body ended", deadline) { graph().all { it.bodyEnd != null } }
        awaitTrue("$label: the server and the recorder agree", deadline) {
            synchronized(received) { received.toList() }.sorted() == sends.all().map { it.key }.sorted()
        }
        quiet(label, 1_000)
    }

    /** The recorder's held inputs, read on Main. */
    private suspend fun Rig.pendingInputs(): List<com.jay.fxi.data.remote.TopicGraphInput> = onMain {
        assembly.recorder.state.value.pending.inputs
    }

    private fun com.jay.fxi.data.remote.TopicGraphInput.isObservation(path: com.jay.fxi.data.remote.TopicGraphPath) =
        this is com.jay.fxi.data.remote.TopicGraphInput.Observations && this.path == path

    /** The coordinator's catalog adoption instant, read on Main. */
    private suspend fun Rig.catalogAt(): kotlinx.datetime.Instant? = onMain { privateField(assembly.coordinator, "catalogAt") as kotlinx.datetime.Instant? }

    private enum class AdoptionCase { IN_FLIGHT, LATE, EARLY }

    /**
     * RT03b-Q01 (agreed b2 r3): the first usd 1d goes with no catalog and so no recovery capture; the REST bootstrap observation
     * and a current WS snapshot are held for the catalog. [AdoptionCase.IN_FLIGHT]: the catalog is adopted while the first tab
     * is held, its inputs replay into a closed demand, and the first tab's full answer releases none of it. [AdoptionCase.LATE]:
     * the first tab completes (the demand is CatalogRequired), and the catalog is adopted after the 3 s round was due.
     * [AdoptionCase.EARLY]: the catalog is adopted before it. In every case one follow-up tab carries the capture — at the round's
     * deadline (first completion + 3 s), or at the late adoption — and releases what it gives; one catalog, two tabs, and no
     * third tab for 5 s. Times are judged on the coordinator's own instants (deadline, catalogAt) mapped to the send clock.
     */
    private fun catalogAdoptionRow(label: String, case: AdoptionCase) = runBlocking {
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 45_000
        val wallAtOrigin = System.currentTimeMillis() - nowMillis()
        fun rel(instant: kotlinx.datetime.Instant) = instant.toEpochMilliseconds() - wallAtOrigin
        entitlements += unauthorized()
        val holdCatalog = java.util.concurrent.CountDownLatch(1)
        val holdFirst = java.util.concurrent.CountDownLatch(1)
        val holdFollow = java.util.concurrent.CountDownLatch(1)
        val firstRegistration = java.util.concurrent.atomic.AtomicReference<TabRegistrationView?>(null)
        val followRegistration = java.util.concurrent.atomic.AtomicReference<TabRegistrationView?>(null)
        val starts = closedStarts()
        val calls = AtomicInteger()
        val rig = Rig()
        try {
            rig.row {
                usdSnapshotLive = true
                onCatalog = { holdCatalog.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("catalog-krx-hidden.json")) }
                onTab = {
                    when (calls.getAndIncrement()) {
                        0 -> {
                            firstRegistration.set(runBlocking { rig.usdTabRegistration() })
                            holdFirst.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(usdTab(starts))
                        }
                        1 -> {
                            followRegistration.set(runBlocking { rig.usdTabRegistration() })
                            holdFollow.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(usdTab(starts.take(96)))
                        }
                        else -> ok(usdTab(starts))
                    }
                }
                rig.coldStart(deadline)
                awaitTrue("$label: the first tab is in flight", deadline) { tabs().size == 1 }
                awaitTrue("$label: the REST observation is held for the catalog", deadline) {
                    rig.pendingInputs().any { it.isObservation(com.jay.fxi.data.remote.TopicGraphPath.REST_BOOTSTRAP) }
                }
                usdSnapshotLive = false
                awaitTrue("$label: an acknowledged socket", deadline) { openSockets.isNotEmpty() }
                openSockets.last().send(usdSnapshot())
                awaitTrue("$label: the WS observation is held after it", deadline) {
                    rig.pendingInputs().let { inputs ->
                        val rest = inputs.indexOfFirst { it.isObservation(com.jay.fxi.data.remote.TopicGraphPath.REST_BOOTSTRAP) }
                        val ws = inputs.indexOfFirst { it.isObservation(com.jay.fxi.data.remote.TopicGraphPath.WS) }
                        rest >= 0 && ws > rest
                    }
                }
                assertFalse("$label: no kb series before the catalog", rig.hasKbSeries())
                awaitTrue("$label: the first tab's registration was read at its send", deadline) { firstRegistration.get() != null }
                assertEquals("$label: the first tab went with no catalog and no recovery capture",
                    TabRegistrationView(false, emptyList()), firstRegistration.get())

                when (case) {
                    AdoptionCase.IN_FLIGHT -> {
                        holdCatalog.countDown()
                        awaitTrue("$label: the catalog is adopted and the held inputs replay", deadline) {
                            rig.catalogAdopted() && rig.pendingInputs().isEmpty() && rig.hasKbSeries()
                        }
                        awaitTrue("$label: a closed demand from the replay", deadline) {
                            rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false)
                        }
                        assertEquals("$label: premise: every closed start is demanded", starts.toSet(), rig.kbClosedPending())
                        assertTrue("$label: premise: the first tab is still held", tabs()[0].bodyEnd == null)
                        holdFirst.countDown()
                    }
                    AdoptionCase.LATE, AdoptionCase.EARLY -> holdFirst.countDown()
                }
                awaitTrue("$label: the first tab completed and a budget opened", deadline) {
                    tabs()[0].bodyEnd != null && rig.usdBudget()?.deadline != null
                }
                val roundDue = rel(checkNotNull(checkNotNull(rig.usdBudget()).deadline))
                val firstEnd = checkNotNull(tabs()[0].bodyEnd)
                println("CUT-C01 $label: first tab body end $firstEnd ms, the round due $roundDue ms")
                when (case) {
                    AdoptionCase.IN_FLIGHT -> {
                        delay(300)
                        assertEquals("$label: the first completion released none of the demand (it carried no capture)",
                            starts.toSet(), rig.kbClosedPending())
                    }
                    AdoptionCase.LATE -> {
                        assertEquals("$label: the demand needs the catalog", com.jay.fxi.data.graph.GraphTabRecoveryDemand.CatalogRequired, rig.usdDemand())
                        while (nowMillis() < maxOf(roundDue, firstEnd + 3_000) + 1_500) {
                            assertEquals("$label: no tab while the round waits for the catalog\n${sends.timeline()}", 1, tabs().size)
                            delay(50)
                        }
                        holdCatalog.countDown()
                        awaitTrue("$label: the catalog is adopted", deadline) { rig.catalogAdopted() }
                        assertTrue("$label: premise: adopted after the 3 s round was due (physical)",
                            rel(checkNotNull(rig.catalogAt())) >= firstEnd + 3_000)
                    }
                    AdoptionCase.EARLY -> {
                        assertEquals("$label: the demand needs the catalog", com.jay.fxi.data.graph.GraphTabRecoveryDemand.CatalogRequired, rig.usdDemand())
                        holdCatalog.countDown()
                        awaitTrue("$label: the catalog is adopted", deadline) { rig.catalogAdopted() }
                        assertTrue("$label: premise: adopted before the round was due", rel(checkNotNull(rig.catalogAt())) < roundDue)
                    }
                }
                if (case != AdoptionCase.IN_FLIGHT) {
                    awaitTrue("$label: the replay made a closed demand after the adoption", deadline) {
                        rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false)
                    }
                }
                awaitTrue("$label: the follow-up tab went", deadline) { tabs().size == 2 }
                awaitTrue("$label: the follow-up's registration was read at its send", deadline) { followRegistration.get() != null }
                val follow = tabs()[1]
                val adopted = rel(checkNotNull(rig.catalogAt()))
                println("CUT-C01 $label: catalog adopted $adopted ms, follow-up sent ${follow.start} ms")
                when (case) {
                    AdoptionCase.IN_FLIGHT, AdoptionCase.EARLY -> {
                        assertTrue("$label: the follow-up goes at the round's deadline, not before (${follow.start} vs $roundDue)\n${sends.timeline()}",
                            follow.start >= roundDue - 50)
                        assertTrue("$label: and without a needless delay (${follow.start} vs $roundDue)", follow.start <= roundDue + 2_000)
                        assertTrue("$label: 3 s after the first completed (physical, ${follow.start} vs $firstEnd)", follow.start >= firstEnd + 3_000 - 50)
                        assertTrue("$label: and not later than the 3 s rung allows (physical, ${follow.start} vs $firstEnd)", follow.start <= firstEnd + 5_000)
                    }
                    AdoptionCase.LATE -> {
                        assertTrue("$label: the follow-up goes after the late adoption (${follow.start} vs $adopted)\n${sends.timeline()}",
                            follow.start >= adopted - 50)
                        assertTrue("$label: with no new interval (${follow.start} vs $adopted)", follow.start <= adopted + 2_000)
                    }
                }
                val captures = followRegistration.get()?.captures.orEmpty()
                assertTrue("$label: the follow-up carries kb.usd's recovery capture: $captures", captures.any { it.seriesKey.seriesId == "kb.usd" })
                assertEquals("$label: the demand is kept while the follow-up is held", starts.toSet(), rig.kbClosedPending())
                holdFollow.countDown()
                awaitTrue("$label: the follow-up applied", deadline) { tabs()[1].bodyEnd != null && rig.kbClosedPending().size < 144 }
                assertEquals("$label: the follow-up released exactly the starts it gave", starts.drop(96).toSet(), rig.kbClosedPending())
                // Nothing else until 5 s after the follow-up completed; then the next round, at its 6 s rung, meets the rest.
                val followEnd = checkNotNull(tabs()[1].bodyEnd)
                quiet(label, followEnd + 5_000 - nowMillis())
                assertEquals("$label: one catalog", listOf(200), statuses(catalogs()))
                assertEquals("$label: two tabs until then", 2, tabs().size)
                awaitTrue("$label: the next round met the rest", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
                assertTrue("$label: at its 6 s rung (${tabs()[2].start} vs $followEnd)", tabs()[2].start >= followEnd + 6_000 - 50)
                assertEquals("$label: three tabs in all", 3, tabs().size)
            }
        } finally {
            holdCatalog.countDown(); holdFirst.countDown(); holdFollow.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 45_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    @Test
    fun `RT03b-Q01a a catalog adopted while the first tab is in flight leaves the demand to one follow-up with its capture`() =
        catalogAdoptionRow("RT03b-Q01a", AdoptionCase.IN_FLIGHT)

    @Test
    fun `RT03b-Q01b a catalog adopted after the round was due sends the follow-up at once`() =
        catalogAdoptionRow("RT03b-Q01b", AdoptionCase.LATE)

    @Test
    fun `RT03b-Q01c a catalog adopted before the round was due sends the follow-up at the round`() =
        catalogAdoptionRow("RT03b-Q01c", AdoptionCase.EARLY)

    // --- CUT-C01 batch 2a: RT03b-Q02 (cut_c01_b2_agreed.r1.md) ---------------------------------------------------------------

    /** kb.usd's recoverable state in the recorder, read on Main. */
    private suspend fun Rig.kbState(): com.jay.fxi.data.graph.GraphRecoverableState? = onMain {
        assembly.recorder.state.value.series.entries.firstOrNull { it.key.seriesId == "kb.usd" }?.value
    }

    /**
     * RT03b-Q02: a failure releases nothing, a partial 200 only what it gave, and a new gap during a held round keeps its new
     * generation. A reconnect opens the cycle: round 1 answers 503, round 2 half the window. While round 3 is held, a second
     * drop inside the reconnect cooldown (no trigger) and a current WS snapshot re-demand the whole window under a newer
     * generation, and the screen's 1d→3m→1d joins the 1d request in flight. Round 3's full answer is applied yet releases
     * nothing of the newer demand; round 4 meets it.
     */
    @Test
    fun `RT03b-Q02 a failure, a partial 200 and a new gap release only what was given and keep the new generation`() = runBlocking {
        val label = "RT03b-Q02"
        val prepared = clearOfBoundary(70_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 60_000
        entitlements += unauthorized()
        val hold3 = java.util.concurrent.CountDownLatch(1)
        val rig = Rig()
        try {
            rig.row {
                rig.prepareClosedDemand(deadline)
                val holder = checkNotNull(rig.onMain { rig.mount?.holders?.value?.get("usd") })
                val starts = closedStarts()
                val baseline = tabs().size
                val oneDay = AtomicInteger()
                onTab = { request ->
                    if (request.requestUrl!!.queryParameter("period") == "3m") ok(fixture("usd-3m-krx-hidden.json"))
                    else when (oneDay.getAndIncrement()) {
                        0 -> unavailable()
                        1 -> ok(usdTab(starts.take(48)))
                        2 -> { hold3.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(usdTab(starts)) }
                        else -> ok(usdTab(starts))
                    }
                }
                val oneDayTabs = { tabs().drop(baseline).filter { it.param("period") == "1d" } }
                assertEquals("$label: premise: every closed start is demanded", starts.toSet(), rig.kbClosedPending())
                val socketsBefore = openSockets.size
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: round 1 (503) completed", deadline) { oneDayTabs().size == 1 && oneDayTabs()[0].bodyEnd != null }
                assertEquals("$label: a failure releases nothing", starts.toSet(), rig.kbClosedPending())
                awaitTrue("$label: round 2 (half) applied", deadline) { oneDayTabs().size == 2 && oneDayTabs()[1].bodyEnd != null && rig.kbClosedPending().size < 144 }
                assertEquals("$label: a partial 200 releases exactly the starts it gave", starts.drop(48).toSet(), rig.kbClosedPending())

                // Round 3 held: its captures, then a new gap.
                awaitTrue("$label: round 3 in flight", deadline) { oneDayTabs().size == 3 }
                val captured = checkNotNull(rig.usdTabRegistration().captures).single { it.seriesKey.seriesId == "kb.usd" }.capturedGeneration
                val versionBefore = rig.kbState()?.lastAppliedVersion
                awaitTrue("$label: the reconnected socket", deadline) { openSockets.size > socketsBefore }
                val triggersBefore = rig.triggers()
                val socketsMid = openSockets.size
                openSockets.last().close(1011, "server restart")
                awaitUsdAcknowledged(label, socketsMid, deadline)
                openSockets.last().send(usdSnapshot())
                awaitTrue("$label: the new gap re-demands the window under a newer generation", deadline) {
                    rig.kbClosedPending() == starts.toSet() && rig.kbState()?.pending
                        ?.filterKeys { it.epochSeconds in starts }?.values?.all { it.generation > captured } == true
                }
                assertEquals("$label: the second drop inside the cooldown issued no trigger", triggersBefore, rig.triggers())
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m tab applied", deadline) { tabs().drop(baseline).any { it.param("period") == "3m" && it.bodyEnd != null } }
                awaitTrue("$label: the screen offers a 3m token", deadline) { rig.onMain { holder.currentState().inlineToken?.period == GraphPeriod.THREE_MONTHS } }
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.ONE_DAY) }
                delay(1_000)
                assertEquals("$label: the return to 1d joins the request in flight (one 1d request)\n${sends.timeline()}", 3, oneDayTabs().size)
                assertEquals("$label: one 3m tab", 1, tabs().drop(baseline).count { it.param("period") == "3m" })

                hold3.countDown()
                awaitTrue("$label: round 3 completed", deadline) { oneDayTabs()[2].bodyEnd != null }
                awaitTrue("$label: round 3's data applied", deadline) {
                    val v = rig.kbState()?.lastAppliedVersion
                    v != null && (versionBefore == null || v > versionBefore)
                }
                delay(500)
                assertEquals("$label: round 3 released nothing of the newer demand", starts.toSet(), rig.kbClosedPending())
                awaitTrue("$label: round 4 met the demand", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
                assertEquals("$label: four rounds", 4, oneDayTabs().size)
                quiet(label, 3_000)
            }
        } finally {
            hold3.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 60_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 2a: RT01-A09 (cut_c01_b2_agreed.r1.md) ----------------------------------------------------------------

    /** The recorder's own catalog supplier, invoked directly (observation only; production calls it on its serial executor). */
    private fun Rig.recorderCatalog(): Any? {
        @Suppress("UNCHECKED_CAST")
        val supply = privateField(assembly.recorder, "currentCatalog") as () -> Any?
        return supply()
    }

    /**
     * RT01-A09a: inputs held before the catalog replay in their order. The REST bootstrap observation (kb at T1) and two WS
     * observations at the same later T2 with different values are held, in that sequence, with no kb series. Once the catalog
     * is adopted they replay: the pending inputs empty, the first WS value keeps the T2 tie, and the WS resume — replayed after
     * the REST observation created kb — marks the closed window with RECEIVE_GAP under one generation. A closed demand alone is
     * not taken as the evidence of order.
     */
    @Test
    fun `RT01-A09a inputs held before the catalog replay in their order once it is adopted`() = runBlocking {
        val label = "RT01-A09a"
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val holdCatalog = java.util.concurrent.CountDownLatch(1)
        val holdFirst = java.util.concurrent.CountDownLatch(1)
        val now = System.currentTimeMillis() / 1000
        val t1 = now - 60
        val t2 = now - 30
        val rig = Rig()
        try {
            rig.row {
                usdSnapshotLive = true
                usdBootstrap = { usdSnapshotAt(1390.0, t1) }
                onCatalog = { holdCatalog.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("catalog-krx-hidden.json")) }
                val starts = closedStarts()
                onTab = { holdFirst.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(usdTab(starts)) }
                rig.coldStart(deadline)
                awaitTrue("$label: the REST observation is held", deadline) {
                    rig.pendingInputs().any { it.isObservation(com.jay.fxi.data.remote.TopicGraphPath.REST_BOOTSTRAP) }
                }
                usdSnapshotLive = false
                awaitTrue("$label: an acknowledged socket", deadline) { openSockets.isNotEmpty() }
                openSockets.last().send(usdSnapshotAt(1391.0, t2))
                openSockets.last().send(usdSnapshotAt(1392.0, t2))
                awaitTrue("$label: both WS observations are held", deadline) {
                    rig.pendingInputs().count { it.isObservation(com.jay.fxi.data.remote.TopicGraphPath.WS) } == 2
                }
                val held = rig.pendingInputs()
                println("CUT-C01 $label held: " + held.joinToString { input ->
                    when (input) {
                        is com.jay.fxi.data.remote.TopicGraphInput.Observations -> "#${input.sequence} obs ${input.path}"
                        is com.jay.fxi.data.remote.TopicGraphInput.Continuity -> "#${input.sequence} ${input.kind} ${input.paths}"
                        else -> "#${input.sequence} ${input.javaClass.simpleName}"
                    }
                })
                assertEquals("$label: held in sequence order", held.map { it.sequence }.sorted(), held.map { it.sequence })
                val rest = held.indexOfFirst { it.isObservation(com.jay.fxi.data.remote.TopicGraphPath.REST_BOOTSTRAP) }
                val ws = held.withIndex().filter { it.value.isObservation(com.jay.fxi.data.remote.TopicGraphPath.WS) }.map { it.index }
                // The WS path's resume (a REST path resume may come first, before kb exists, and marks nothing).
                val resume = held.indexOfFirst {
                    it is com.jay.fxi.data.remote.TopicGraphInput.Continuity && it.kind == com.jay.fxi.data.remote.TopicGraphEventKind.DELIVERY_RESUMED &&
                        com.jay.fxi.data.remote.TopicGraphPath.WS in it.paths
                }
                assertTrue("$label: REST, then the WS resume, then the two WS observations (rest $rest, resume $resume, ws $ws)",
                    rest >= 0 && resume > rest && ws.size == 2 && ws[0] > resume && ws[1] > ws[0])
                assertFalse("$label: no kb series before the catalog", rig.hasKbSeries())

                holdCatalog.countDown()
                awaitTrue("$label: the held inputs replayed", deadline) { rig.pendingInputs().isEmpty() && rig.hasKbSeries() }
                val kb = checkNotNull(rig.kbState())
                val tip = checkNotNull(kb.data.app.tip)
                assertEquals("$label: the first WS value keeps the T2 tie", 1391.0, tip.rate, 0.0)
                assertEquals("$label: at T2", t2, tip.observedAt.epochSeconds)
                val closed = kb.pending.filterKeys { it.epochSeconds in starts }
                assertEquals("$label: the resume marked every closed start", starts.toSet(), closed.keys.map { it.epochSeconds }.toSet())
                assertTrue("$label: as a receive gap under one generation (${closed.values.map { it.generation }.toSet()})",
                    closed.values.all { com.jay.fxi.data.graph.GraphRecoveryReason.RECEIVE_GAP in it.reasons } &&
                        closed.values.map { it.generation }.toSet().size == 1)
                holdFirst.countDown()
                rig.awaitTopics(deadline)
                awaitTrue("$label: the tabs settle", deadline) { tabs().all { it.bodyEnd != null } }
            }
        } finally {
            holdCatalog.countDown(); holdFirst.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-A09b: the same scope loses its catalog. With kb recorded, a capability rotation (KRX hidden, a new grant on the same
     * user epoch) leaves no catalog until the new one is answered; that answer is held. A WS frame in the meantime is held
     * (pending inputs grow, kb's tip unchanged); once the catalog is adopted again it replays into kb.
     */
    @Test
    fun `RT01-A09b a same-scope catalog loss holds the WS input and the readoption replays it`() = runBlocking {
        val label = "RT01-A09b"
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        val holdNewCatalog = java.util.concurrent.CountDownLatch(1)
        val krxVisible = java.util.concurrent.atomic.AtomicBoolean(true)
        val rig = Rig()
        try {
            rig.row {
                rig.prepareClosedDemand(deadline, krx = true)
                val starts = closedStarts()
                onCatalog = {
                    if (krxVisible.get()) ok(fixture("catalog-krx-visible.json"))
                    else { holdNewCatalog.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("catalog-krx-hidden.json")) }
                }
                // Let the cold start settle before the rotation, as RT01-A05 does (back to back it crowds the API budget).
                rig.awaitTopics(deadline)
                delay(3_500)
                onTab = { ok(fixture(if (krxVisible.get()) "usd-1d-krx-visible.json" else "usd-1d-krx-hidden.json")) }
                val tipBefore = checkNotNull(rig.kbState()?.data?.app?.tip)
                val f1 = checkNotNull(rig.bridge())
                val socketsBefore = openSockets.size
                val rotatedAt = nowMillis()
                krxVisible.set(false)
                entitlements += ok(PREMIUM_HIDDEN)
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("$label: premise: the same user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                assertNotEquals("$label: premise: a new grant", f1.grant, f2.grant)
                awaitTrue("$label: the new context is consumed with no catalog", deadline) {
                    rig.assembly.coordinator.state.value.source?.fence == f2 && !rig.catalogAdopted()
                }
                awaitTrue("$label: the new catalog is asked and held", deadline) { catalogs().any { it.end == null } }
                // The frame comes on the new grant's connection (one on the old connection would be refused at replay).
                awaitUsdAcknowledged(label, socketsBefore, deadline)
                val heldBefore = rig.pendingInputs().size
                val later = System.currentTimeMillis() / 1000
                openSockets.last().send(usdSnapshotAt(1395.0, later))
                awaitTrue("$label: the WS input is held", deadline) { rig.pendingInputs().size > heldBefore }
                val describe = { inputs: List<com.jay.fxi.data.remote.TopicGraphInput> -> inputs.joinToString { input ->
                    when (input) {
                        is com.jay.fxi.data.remote.TopicGraphInput.Observations -> "#${input.sequence} obs ${input.path} ${input.attribution.lifetime}"
                        is com.jay.fxi.data.remote.TopicGraphInput.Continuity -> "#${input.sequence} ${input.kind} ${input.paths}"
                        else -> "#${input.sequence} ${input.javaClass.simpleName}"
                    }
                } }
                println("CUT-C01 $label held after the frame: ${describe(rig.pendingInputs())}; bridge ${rig.bridge()}")
                assertEquals("$label: kb's tip unchanged while the catalog is missing", tipBefore, rig.kbState()?.data?.app?.tip)
                holdNewCatalog.countDown()
                awaitTrue("$label: the catalog is adopted again", deadline) { rig.catalogAdopted() }
                delay(1_000)
                println("CUT-C01 $label after readoption: held ${describe(rig.pendingInputs())}; tip ${rig.kbState()?.data?.app?.tip}; source ${rig.assembly.coordinator.state.value.source}")
                awaitTrue("$label: the held input replays into kb", deadline) {
                    rig.pendingInputs().isEmpty() && rig.kbState()?.data?.app?.tip?.rate == 1395.0
                }
                assertEquals("$label: at the frame's time", later, checkNotNull(rig.kbState()?.data?.app?.tip).observedAt.epochSeconds)
                // End cleanly: the next round meets the closed demand, and the new session settles.
                onTab = { ok(usdTab(starts)) }
                awaitTrue("$label: the closed demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
                settleNewSession(label, rotatedAt, deadline)
            }
        } finally {
            holdNewCatalog.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-A09c: another scope never gets the previous scope's catalog. A real StableInactive answer ends the user (new user
     * epoch) and a FORCE_PREMIUM answer re-grants on the new epoch, with Main and the topic executor held as in RT01-A05
     * merged; the topic executor is released first. While Main is held the coordinator's publication still carries the old
     * scope's catalog and the bridge already has the new epoch's fence: the recorder's catalog supplier returns null.
     */
    @Test
    fun `RT01-A09c a new user epoch never reads the previous scope's catalog`() = runBlocking {
        val label = "RT01-A09c"
        val prepared = clearOfBoundary(40_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            // prepareClosedDemand's tab script waits for a catalog; the new scope's tabs answer at once.
            onTab = { ok(fixture("usd-1d-krx-hidden.json")) }
            rig.awaitTopics(deadline)
            // Let the cold start settle before the identity churn, as RT01-A05 does.
            delay(3_500)
            val f1 = checkNotNull(rig.bridge())
            assertNotNull("$label: premise: the recorder reads the catalog in its scope", rig.recorderCatalog())
            val tabsBefore = tabs().size
            val churnAt = nowMillis()
            val mainHold = holdMain()
            try {
                val runtimeHold = rig.holdRuntime()
                try {
                    entitlements += ok("""{"krx_visible":false,"premium_active":false,"premium_pending":false}""")
                    kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                    entitlements += ok(PREMIUM_HIDDEN)
                    kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
                } finally {
                    runtimeHold.release()
                }
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence) { "$label: no re-grant after the user end" }
                assertNotEquals("$label: premise: a new user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                awaitTrue("$label: the bridge has the new epoch's fence while Main is held", deadline) { rig.bridge() == f2 }
                val publication = rig.assembly.coordinator.state.value
                assertNotNull("$label: premise: the publication still carries the old scope's catalog", publication.catalog)
                assertEquals("$label: premise: of the old scope", f1.userAccessEpoch, publication.dataScope?.userAccessEpoch)
                assertNull("$label: the recorder's supplier returns no catalog for the new scope", rig.recorderCatalog())
            } finally {
                mainHold.release()
            }
            awaitTrue("$label: the new scope's context is consumed", deadline) {
                rig.assembly.coordinator.state.value.source?.fence == rig.bridge()
            }
            // The new scope starts its own requests; the row ends once they have reached the server and settled.
            awaitTrue("$label: the new scope's tab applied", deadline) {
                tabs().size > tabsBefore && tabs().all { it.bodyEnd != null } && rig.usdApplied()
            }
            settleNewSession(label, churnAt, deadline)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 2b: RT03b-Q04 (cut_c01_b2_agreed.r1.md) ---------------------------------------------------------------

    /** The usd recovery budget object itself (identity, not a view), read on Main. */
    private suspend fun Rig.usdBudgetObject(): Any? = onMain {
        @Suppress("UNCHECKED_CAST")
        val budgets = privateField(assembly.coordinator, "recoveryBudgets") as Map<Any, Any>
        budgets.entries.singleOrNull { privateField(it.key, "tab") == "usd" }?.value
    }

    /** In one Main step: the usd budget object, its stop, and the trigger sequence. */
    private suspend fun Rig.budgetSample(): Triple<Any?, Any?, Long> = onMain {
        @Suppress("UNCHECKED_CAST")
        val budgets = privateField(assembly.coordinator, "recoveryBudgets") as Map<Any, Any>
        val budget = budgets.entries.singleOrNull { privateField(it.key, "tab") == "usd" }?.value
        val events = assembly.events
        Triple(budget, budget?.let { privateField(it, "stop") },
            events.javaClass.getDeclaredField("sequence").apply { isAccessible = true }.get(events) as Long)
    }

    /**
     * Who issued a graph request and under what permit: [byRound] is the in-flight registration's `automaticRecovery` as the
     * coordinator holds it when the server takes the request (null when none is registered then); [permitOpen] is the live
     * recovery permit's `automatic` as the client read it sending the request ([permitAtSend]; null when untagged).
     */
    private data class SendOrigin(val at: Long, val kind: String, val byRound: Boolean?, val permitOpen: Boolean?)

    /** The [permitAtSend] reader of a row: the live permit's `automatic`, read off Main (the session publishes it volatile). */
    private fun Rig.permitTag(): () -> String? = { if (permitSlot?.get()?.invoke()?.automatic == true) "open" else "closed" }

    /** From a server hook, in one Main step: the in-flight catalog ([catalog]) or usd 1d tab registration; the permit from [request]. */
    private suspend fun Rig.sendOrigin(catalog: Boolean, request: RecordedRequest): SendOrigin = onMain {
        val c = assembly.coordinator
        val registration = if (catalog) privateField(c, "catalogRequest") else {
            @Suppress("UNCHECKED_CAST")
            val requests = privateField(c, "tabRequests") as Map<com.jay.fxi.data.graph.GraphKey, Any>
            requests.entries.firstOrNull { it.key.tab == "usd" && it.key.period.code == "1d" }?.value
        }
        SendOrigin(nowMillis(), if (catalog) "catalog" else "tab", registration?.let { privateField(it, "automaticRecovery") as Boolean },
            when (request.getHeader(PERMIT_HEADER)) { "open" -> true; "closed" -> false; else -> null })
    }

    /**
     * RT03b-Q04: one open recovery cycle over a real closed demand (prepared KRX-visible) survives three offline holds, two
     * real re-approvals (each a refused subscription and a new grant on the same user epoch) and a capability reissue (KRX
     * approved visible again, then hidden: a rotation and a new grant). Every round answers part of the window, so the demand
     * stays. After each event the usd budget is the same object, its rounds and rung never go down, and a hold keeps its
     * deadline and issues no round. Past RT05's 30 s cooldown a reconnect on the same grant issues a trigger on the running
     * cycle. Sampled every 10 ms in between, the budget stays that object and never stops, so every trigger lands on the
     * running cycle (no exhausted or capped cycle a trigger could reopen). Catalogs by issuer: the initial one at most one;
     * since the cycle opened, the rounds' own at most the rounds, the screen's own activation at most one per grant change and
     * none outside one, none in a hold, overall at most the rounds plus the grant changes. Every request a round issued goes
     * with the permit open, as the client read it sending the request. (An event failure goes to Crashlytics, not
     * `rig.reports`; its effect on the cycle, a stop, is what the sampling sees.)
     */
    @Test
    fun `RT03b-Q04 repeated holds, re-approvals and a capability reissue keep one budget with its counters and deadline`() = runBlocking {
        val label = "RT03b-Q04"
        val prepared = clearOfBoundary(130_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 120_000
        val wallAtOrigin = System.currentTimeMillis() - nowMillis()
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        val rig = Rig()
        val restorer = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                if (!rig.issuerIdentityReadable && rig.coordinator.recheckDiagnostics().owedIntent != null) rig.issuerIdentityReadable = true
                delay(20)
            }
        }
        var sampler: kotlinx.coroutines.Job? = null
        try {
            rig.row {
                rig.prepareClosedDemand(deadline, krx = true)
                val starts = closedStarts()
                val rounds = AtomicInteger()
                // Each round gives a further eighth of the window: the demand stays for the whole row.
                val origins = Collections.synchronizedList(mutableListOf<SendOrigin>())
                permitAtSend = rig.permitTag()
                onCatalog = { request -> origins += runBlocking { rig.sendOrigin(catalog = true, request) }; ok(fixture("catalog-krx-visible.json")) }
                onTab = { request -> origins += runBlocking { rig.sendOrigin(catalog = false, request) }; ok(usdTab(starts.take(8 * (rounds.incrementAndGet())), "usd-1d-krx-visible.json")) }
                rig.awaitTopics(deadline)
                delay(3_500)
                val baseline = tabs().size
                val catalogsAtOpen = catalogs().size
                val catalogOriginsAtOpen = synchronized(origins) { origins.count { it.kind == "catalog" } }
                val triggersAtOpen = rig.triggers()
                val openedAt = nowMillis()
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: round 1 completed", deadline) { tabs().size == baseline + 1 && tabs().last().bodyEnd != null }
                awaitTrue("$label: the budget waits for its next round", deadline) { rig.usdBudget()?.deadline != null }
                val budget = checkNotNull(rig.usdBudgetObject())
                // Between the checks as well: the budget stays this object and never stops, so no trigger can reopen a cycle;
                // every trigger the row sees lands on this running budget. The grant tokens are recorded with their times.
                val violations = Collections.synchronizedList(mutableListOf<String>())
                val triggerSteps = Collections.synchronizedList(mutableListOf<String>())
                val grantChanges = Collections.synchronizedList(mutableListOf<Long>())
                sampler = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    var previous = rig.budgetSample()
                    var token = rig.coordinator.accessSnapshot.facts.token
                    while (true) {
                        delay(10)
                        val sample = rig.budgetSample()
                        val at = nowMillis()
                        if (sample.first !== budget) violations += "$at: the budget is ${sample.first}"
                        if (sample.second != null) violations += "$at: the budget stopped: ${sample.second}"
                        if (sample.third > previous.third) {
                            triggerSteps += "$at: ${previous.third} -> ${sample.third}"
                            if (previous.first !== budget || previous.second != null) violations += "$at: a trigger after ${previous.second}"
                        }
                        previous = sample
                        val now = rig.coordinator.accessSnapshot.facts.token
                        if (now != null && now != token) { token = now; grantChanges += at }
                    }
                }
                val holdWindows = mutableListOf<Pair<Long, Long>>()
                val grantWindows = mutableListOf<Triple<String, Long, Long>>()
                var last = checkNotNull(rig.usdBudget())
                val log = mutableListOf<String>()
                suspend fun check(event: String) {
                    val now = checkNotNull(rig.usdBudget()) { "$label: no usd budget after $event" }
                    log += "$event: $now"
                    assertSame("$label: the same budget object after $event", budget, rig.usdBudgetObject())
                    assertTrue("$label: rounds never go down after $event ($last -> $now)", now.rounds >= last.rounds)
                    assertTrue("$label: the rung never goes down after $event ($last -> $now)", now.completionRung >= last.completionRung)
                    assertNull("$label: the cycle has not stopped after $event ($now)", now.stop)
                    assertTrue("$label: the demand stays after $event", rig.kbClosedPending().isNotEmpty())
                    last = now
                }
                check("round 1")

                // Three offline holds: the deadline is kept while the permit is closed.
                repeat(3) { i ->
                    // Entered with the next round at least 0.5 s away: no round can leave between this check and the permit closing.
                    awaitTrue("$label: hold ${i + 1}: no round in flight and the next not imminent", deadline) {
                        tabs().all { it.bodyEnd != null } &&
                            rig.usdBudget()?.deadline?.let { it.toEpochMilliseconds() - wallAtOrigin - nowMillis() >= 500 } == true
                    }
                    val offlineAt = nowMillis()
                    rig.online.value = false
                    awaitTrue("$label: hold ${i + 1}: the permit closed", deadline) {
                        rig.onMain { checkNotNull(rig.permitSlot).require()() }?.automatic != true
                    }
                    // Read once the permit is closed: a round that left just before is already counted.
                    val before = checkNotNull(rig.usdBudget())
                    delay(1_500)
                    val during = checkNotNull(rig.usdBudget())
                    assertEquals("$label: hold ${i + 1} keeps the deadline", before.deadline, during.deadline)
                    assertEquals("$label: hold ${i + 1}: no round while held", before.rounds, during.rounds)
                    rig.online.value = true
                    awaitTrue("$label: hold ${i + 1}: the permit is automatic again", deadline) {
                        rig.onMain { checkNotNull(rig.permitSlot).require()() }?.automatic == true
                    }
                    check("hold ${i + 1}")
                    holdWindows += offlineAt to nowMillis()
                }

                // Two re-approvals: the reconnect's subscription is refused once, the issuer re-approves on a new grant.
                repeat(2) { i ->
                    awaitTrue("$label: re-approval ${i + 1}: no round in flight", deadline) { tabs().all { it.bodyEnd != null } }
                    val grantBefore = checkNotNull(rig.coordinator.topicGrantResult().fence)
                    val refusalsBefore = refusals.get()
                    refuseUsd = {
                        refuseUsd = null
                        rig.issuerIdentityReadable = false
                    }
                    val closeAt = nowMillis()
                    openSockets.last().close(1011, "server restart")
                    awaitTrue("$label: re-approval ${i + 1}: a new grant", deadline) {
                        rig.coordinator.topicGrantResult().fence?.grant?.let { it != grantBefore.grant } == true
                    }
                    val grantAfter = checkNotNull(rig.coordinator.topicGrantResult().fence)
                    assertEquals("$label: re-approval ${i + 1}: the same user epoch", grantBefore.userAccessEpoch, grantAfter.userAccessEpoch)
                    assertTrue("$label: re-approval ${i + 1}: premise: refused", refusals.get() > refusalsBefore)
                    awaitTrue("$label: re-approval ${i + 1}: its context is consumed", deadline) {
                        rig.assembly.coordinator.state.value.source?.fence == grantAfter
                    }
                    delay(2_000)
                    check("re-approval ${i + 1}")
                    grantWindows += Triple("re-approval ${i + 1}", closeAt, nowMillis())
                }

                // A capability reissue: KRX approved visible again, then hidden (a rotation and a new grant on the same user epoch).
                awaitTrue("$label: reissue: no round in flight", deadline) { tabs().all { it.bodyEnd != null } }
                val reissueAt = nowMillis()
                val beforeReissue = checkNotNull(rig.coordinator.topicGrantResult().fence)
                entitlements += ok(premiumKrxVisible)
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                val epochOpen = rig.epochStore.load().krxCapabilityEpoch
                entitlements += ok(PREMIUM_HIDDEN)
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                val reissued = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertNotEquals("$label: reissue: a new grant", beforeReissue.grant, reissued.grant)
                assertEquals("$label: reissue: the same user epoch", beforeReissue.userAccessEpoch, reissued.userAccessEpoch)
                assertNotEquals("$label: reissue: the capability epoch rotated", epochOpen, rig.epochStore.load().krxCapabilityEpoch)
                awaitTrue("$label: reissue: its context is consumed", deadline) { rig.assembly.coordinator.state.value.source?.fence == reissued }
                delay(2_000)
                check("capability reissue")
                grantWindows += Triple("capability reissue", reissueAt, nowMillis())

                // A trigger on the running cycle: past RT05's 30 s cooldown since the cycle's opening RECONNECT, a reconnect on the same
                // grant issues one more. It lands on this budget (the sampler checks every trigger step) and resets nothing.
                while (nowMillis() < openedAt + 31_000) delay(50)
                awaitTrue("$label: late reconnect: no round in flight", deadline) { tabs().all { it.bodyEnd != null } }
                val triggersBefore = rig.triggers()
                val grantBeforeLate = checkNotNull(rig.coordinator.topicGrantResult().fence).grant
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: late reconnect: one trigger", deadline) { rig.triggers() > triggersBefore }
                delay(2_000)
                assertEquals("$label: late reconnect: premise: the same grant", grantBeforeLate, rig.coordinator.topicGrantResult().fence?.grant)
                check("late reconnect")
                sampler?.cancel()

                println("CUT-C01 $label budget after each event:\n" + log.joinToString("\n"))
                val changes = synchronized(grantChanges) { grantChanges.toList() }
                val catalogTimes = catalogs().drop(catalogsAtOpen).map { it.start }
                println("CUT-C01 $label triggers ${triggersAtOpen} -> ${rig.triggers()} (${synchronized(triggerSteps) { triggerSteps.toList() }}); " +
                    "grant changes $changes; catalogs since open $catalogTimes; holds $holdWindows; grant windows $grantWindows; rounds ${last.rounds}")
                assertTrue("$label: premise: the cycle has rounds left (${last.rounds} < 6)", last.rounds < 6)
                assertTrue("$label: premise: a trigger landed while the cycle ran", synchronized(triggerSteps) { triggerSteps.isNotEmpty() })
                assertEquals("$label: between the checks the budget stayed this running object", emptyList<String>(), synchronized(violations) { violations.toList() })
                // Catalogs by issuer (cut_c01_b2b_catalog.r1), each send paired with who issued it (one catalog is in flight at a time,
                // so both lists are in send order) and judged on the client's clock: the initial one at most one; since the cycle
                // opened, the rounds' own at most the rounds, the screen's own activation at most one per grant change (the reissue's
                // intermediate change counted) and none outside one, none in a hold on the same grant; overall at most the rounds plus
                // the grant changes.
                val sent = synchronized(origins) { origins.toList() }
                val catalogOrigins = sent.filter { it.kind == "catalog" }.drop(catalogOriginsAtOpen)
                println("CUT-C01 $label origins $sent")
                assertTrue("$label: the initial catalog is at most one ($catalogsAtOpen)", catalogsAtOpen <= 1)
                assertEquals("$label: premise: every catalog since the cycle opened is attributed", catalogTimes.size, catalogOrigins.size)
                assertTrue("$label: premise: every catalog has its registration", catalogOrigins.all { it.byRound != null })
                val pairs = catalogTimes.zip(catalogOrigins)
                assertTrue("$label: the rounds' own catalogs are at most the rounds (${last.rounds})\n${sends.timeline()}",
                    pairs.count { it.second.byRound == true } <= last.rounds)
                holdWindows.forEachIndexed { i, (from, until) ->
                    assertEquals("$label: premise: hold ${i + 1} keeps the grant", 0, changes.count { it in from..until })
                    assertEquals("$label: no catalog in hold ${i + 1}\n${sends.timeline()}", 0, catalogTimes.count { it in from..until })
                }
                grantWindows.forEach { (name, from, until) ->
                    val inWindow = changes.count { it in from..until }
                    assertTrue("$label: premise: $name changed the grant", inWindow >= 1)
                    assertTrue("$label: the activation's own catalogs in $name are at most its grant changes ($inWindow)\n${sends.timeline()}",
                        pairs.count { (at, o) -> o.byRound == false && at in from..until } <= inWindow)
                }
                assertEquals("$label: no activation catalog outside a grant change\n${sends.timeline()}", emptyList<Long>(),
                    pairs.filter { (at, o) -> o.byRound == false && grantWindows.none { (_, from, until) -> at in from..until } }.map { it.first })
                assertTrue("$label: catalogs since the cycle opened (${catalogTimes.size}) are at most rounds + grant changes " +
                    "(${last.rounds} + ${changes.size})\n${sends.timeline()}", catalogTimes.size <= last.rounds + changes.size)
                // Every request a round issued went with the permit open, as the client read it sending the request.
                assertTrue("$label: premise: every graph request was tagged as it was sent", sent.all { it.permitOpen != null })
                assertEquals("$label: no round-issued request without the permit\n${sends.timeline()}", emptyList<SendOrigin>(),
                    sent.filter { it.byRound == true && it.permitOpen != true })
                // End cleanly: the rest of the window, then the new session settles.
                onTab = { ok(usdTab(starts, "usd-1d-krx-visible.json")) }
                awaitTrue("$label: the demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
                awaitTrue("$label: the tabs settle", deadline) { graph().all { it.bodyEnd != null } }
                awaitTrue("$label: the server and the recorder agree", deadline) {
                    synchronized(received) { received.toList() }.sorted() == sends.all().map { it.key }.sorted()
                }
                quiet(label, 1_000)
            }
        } finally {
            restorer.cancel()
            sampler?.cancel()
            permitAtSend = null
            refuseUsd = null
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 120_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 2b: RT03b-Q05 (cut_c01_b2_agreed.r1.md) ---------------------------------------------------------------

    /** Topic snapshot (REST bootstrap) sends, in order. */
    private fun bootstraps() = sends.all().filter { it.path == "/api/v2/topics/snapshot" }

    /**
     * RT03b-Q05a: re-approval recovery under a real D3 backoff. With an open cycle over a closed demand, the reconnect's
     * subscription is refused twice in a row, so the session re-approves twice and reconnects under D3 (its reconnection
     * display past the first attempt). From each re-approval — its entitlements answer as the client recorded it — until that
     * grant's connection started, no tab and no topic bootstrap goes; the screen's owner-following activation may send at most one
     * catalog (cut_c01_b2b_catalog.r1), never the round's. The second window has the round owed in it. The last connection's
     * 101 is held 4 s: the waiting round goes once the connection exists, promptly and before it opens. Closing that socket one
     * second before the next round falls due, with the demand still there, nothing goes until the next connection, and then the
     * round goes at once. Every request a round issued went with the permit open, as the client read it sending the request.
     */
    @Test
    fun `RT03b-Q05a re-approvals under a D3 backoff send no round or bootstrap until the grant's connection and the round goes before it opens`() = runBlocking {
        val label = "RT03b-Q05a"
        val prepared = clearOfBoundary(80_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 70_000
        val wallAtOrigin = System.currentTimeMillis() - nowMillis()
        entitlements += unauthorized()
        val rig = Rig()
        val restorer = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                if (!rig.issuerIdentityReadable && rig.coordinator.recheckDiagnostics().owedIntent != null) rig.issuerIdentityReadable = true
                delay(20)
            }
        }
        // Each grant change (re-approval) as it is observed, with the usd budget at that moment; the session's reconnection attempts.
        val grantChanges = Collections.synchronizedList(mutableListOf<Triple<Long, String, BudgetView?>>())
        val attempts = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        // Who issued each graph request, read when the server takes it.
        val origins = Collections.synchronizedList(mutableListOf<SendOrigin>())
        var grantWatch: kotlinx.coroutines.Job? = null
        try {
            rig.row {
                rig.prepareClosedDemand(deadline)
                val starts = closedStarts()
                val rounds = AtomicInteger()
                // A sixth of the demand per round: it stays through the tail.
                val catalogsAtHook = catalogs().size
                permitAtSend = rig.permitTag()
                onCatalog = { request -> origins += runBlocking { rig.sendOrigin(catalog = true, request) }; ok(fixture("catalog-krx-hidden.json")) }
                onTab = { request -> origins += runBlocking { rig.sendOrigin(catalog = false, request) }; ok(usdTab(starts.take(24 * rounds.incrementAndGet()))) }
                rig.awaitTopics(deadline)
                delay(3_500)
                val first = checkNotNull(rig.coordinator.topicGrantResult().fence)
                @Suppress("UNCHECKED_CAST")
                val display = rig.onMain { privateField(rig.owner.consumer, "display") } as StateFlow<TopicDisplayState>
                grantWatch = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    var seen = first.grant
                    var attempt = 0
                    while (true) {
                        val now = rig.coordinator.accessSnapshot.facts.token
                        if (now != null && now != seen) {
                            seen = now
                            val at = nowMillis()
                            grantChanges += Triple(at, "$now owner ${rig.onMain { rig.owner.consumer.currentState().ui.owner }}", rig.usdBudget())
                        }
                        val recovery = display.value.recovery
                        if (recovery is com.jay.fxi.data.remote.TopicRecoveryDisplay.Reconnecting && recovery.attempt != attempt) {
                            attempt = recovery.attempt
                            attempts += nowMillis() to attempt
                        }
                        delay(5)
                    }
                }
                // Two refusals in a row; the connection after the second re-approval has its 101 held.
                val refusalsLeft = AtomicInteger(2)
                refuseUsd = {
                    if (refusalsLeft.decrementAndGet() <= 0) {
                        refuseUsd = null
                        holdHandshakeMillis.set(4_000L)
                    }
                    rig.issuerIdentityReadable = false
                }
                val arrivalsAtClose = wsArrivals.size
                val handshakesAtClose = handshakes().size
                val tabsAtClose = tabs().size
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: two refusals", deadline) { refusals.get() >= 2 }
                awaitTrue("$label: the second re-approval", deadline) {
                    rig.coordinator.topicGrantResult().fence?.let { it.grant != first.grant } == true && refuseUsd == null
                }
                val reapproved = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("$label: the same user epoch", first.userAccessEpoch, reapproved.userAccessEpoch)
                // The last connection: after the second refusal, its 101 held.
                awaitTrue("$label: the re-approved grant's connection reaches the server", deadline) { wsArrivals.size >= arrivalsAtClose + 3 }
                val connections = wsArrivals.drop(arrivalsAtClose).take(3)
                // Each connection as the client started it (its WebSocket call, before any TCP connect): the time its own first
                // sends are measured against.
                awaitTrue("$label: the client started the three connections", deadline) { handshakes().size >= handshakesAtClose + 3 }
                val wsStarts = handshakes().drop(handshakesAtClose).take(3).map { it.start }
                val changes = synchronized(grantChanges) { grantChanges.toList() }
                println("CUT-C01 $label: connections $connections; grant changes $changes; attempts ${synchronized(attempts) { attempts.toList() }}; " +
                    "bootstraps ${bootstraps().map { "${it.param("topic")}@${it.start}" }}; catalogs ${catalogs().map { it.start }}")
                assertEquals("$label: premise: two re-approvals observed", 2, changes.size)
                assertTrue("$label: premise: D3 is backing off (an attempt past the first before the last connection)",
                    synchronized(attempts) { attempts.toList() }.any { it.second >= 2 && it.first < wsStarts[2] })
                assertEquals("$label: premise: the round is owed at the first re-approval, waiting for the permit", true, changes[0].third?.waitingPermit)
                val connectionAt = wsStarts[2]
                val entitlementEnds = sends.all().filter { it.path == "/api/entitlements" }.mapNotNull { it.bodyEnd }
                // Catalog sends paired with who issued them (one catalog is in flight at a time, so both lists are in send order).
                val catalogPairs = { catalogs().drop(catalogsAtHook).zip(synchronized(origins) { origins.toList() }.filter { it.kind == "catalog" }) }
                // Each re-approval's window, on the client's clock: from its entitlements answer as the client recorded it (the grant
                // cannot change before it) until that grant's connection started (the 2nd and 3rd), less 50 ms for the connection's
                // own first sends, which go out with it.
                listOf(changes[0].first to wsStarts[1], changes[1].first to wsStarts[2]).forEachIndexed { i, (seen, until) ->
                    val from = checkNotNull(entitlementEnds.filter { it <= seen }.maxOrNull()) { "$label: re-approval ${i + 1}'s entitlements answer" }
                    assertTrue("$label: premise: that answer is re-approval ${i + 1}'s ($from, seen at $seen)", seen - from < 1_000)
                    assertTrue("$label: premise: re-approval ${i + 1} came before its connection ($from, $until)", from < until - 50)
                    assertEquals("$label: no tab in re-approval ${i + 1}'s window\n${sends.timeline()}", 0,
                        tabs().count { it.start in from until (until - 50) })
                    assertEquals("$label: no topic bootstrap in re-approval ${i + 1}'s window\n${sends.timeline()}", 0,
                        bootstraps().count { it.start in from until (until - 50) })
                    val window = catalogPairs().filter { (exchange, _) -> exchange.start in from until until }
                    println("CUT-C01 $label: catalogs in re-approval ${i + 1}'s window ($from..$until): ${window.map { (e, o) -> "${e.start} $o" }}")
                    // The one catalog allowed is the screen's own activation request, never the round's (cut_c01_b2b_catalog.r1).
                    assertTrue("$label: premise: each catalog in the window has its registration", window.all { it.second.byRound != null })
                    assertTrue("$label: at most one catalog in re-approval ${i + 1}'s window\n${sends.timeline()}", window.size <= 1)
                    assertEquals("$label: no round-issued catalog in re-approval ${i + 1}'s window", emptyList<SendOrigin>(),
                        window.map { it.second }.filter { it.byRound == true })
                }
                // The second window has the round owed in it: due before that grant's connection.
                val due2 = checkNotNull(changes[1].third?.deadline) { "$label: premise: a round deadline at the second re-approval" }
                    .toEpochMilliseconds() - wallAtOrigin
                assertTrue("$label: premise: the round is owed in the second window (due $due2, connection ${wsStarts[2]})", due2 < wsStarts[2] - 100)
                // The round goes once the connection exists, promptly, before the held socket opens.
                awaitTrue("$label: the waiting round goes", deadline) { tabs().drop(tabsAtClose).any { it.start >= connectionAt - 50 } }
                val round = tabs().drop(tabsAtClose).first { it.start >= connectionAt - 50 }
                assertTrue("$label: the round goes promptly (${round.start} vs connection $connectionAt, due $due2)\n${sends.timeline()}",
                    round.start <= maxOf(connectionAt, due2) + 1_000)
                awaitTrue("$label: the held socket opens", deadline) { handshakes().last().end != null }
                val opened = checkNotNull(handshakes().last().end)
                assertTrue("$label: the round goes before the held socket opens (${round.start} vs $opened)\n${sends.timeline()}", round.start < opened)
                awaitTrue("$label: the round completed", deadline) { tabs().all { it.bodyEnd != null } }

                // The tail: the next round falls due one second after the socket closes, with the demand still there.
                awaitTrue("$label: the next round is scheduled", deadline) { rig.usdBudget()?.deadline != null }
                val tailDue = checkNotNull(rig.usdBudget()?.deadline).toEpochMilliseconds() - wallAtOrigin
                assertTrue("$label: premise: the next round is not due yet ($tailDue)", tailDue - nowMillis() >= 1_500)
                while (nowMillis() < tailDue - 1_000) delay(20)
                assertTrue("$label: premise: the demand is still there", rig.kbClosedPending().isNotEmpty())
                assertEquals("$label: premise: the deadline has not moved", tailDue,
                    checkNotNull(rig.usdBudget()?.deadline).toEpochMilliseconds() - wallAtOrigin)
                val handshakesBefore = handshakes().size
                val graphBefore = graph().size
                // The round after the next connection meets the rest of the demand, so the row ends on it.
                onTab = { request -> origins += runBlocking { rig.sendOrigin(catalog = false, request) }; ok(usdTab(starts)) }
                val closedAt = nowMillis()
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: the client saw the close: the permit closed", deadline) {
                    rig.permitSlot?.get()?.invoke()?.automatic != true
                }
                assertTrue("$label: premise: the permit closed before the round fell due (${nowMillis()} vs $tailDue)", nowMillis() < tailDue)
                awaitTrue("$label: the next connection", deadline) { handshakes().size > handshakesBefore }
                val nextConnection = handshakes()[handshakesBefore].start
                println("CUT-C01 $label: tail closed $closedAt, due $tailDue, next connection started $nextConnection")
                assertTrue("$label: premise: the round fell due while the socket was gone ($tailDue, $closedAt..$nextConnection)",
                    tailDue in (closedAt + 100) until (nextConnection - 100))
                assertEquals("$label: no graph request between the close and the next connection\n${sends.timeline()}", 0,
                    graph().drop(graphBefore).count { it.start < nextConnection - 50 })
                awaitTrue("$label: the round goes on the next connection", deadline) { tabs().any { it.start >= closedAt } }
                val tailRound = tabs().first { it.start >= closedAt }
                assertTrue("$label: at once (${tailRound.start} vs $nextConnection)\n${sends.timeline()}", tailRound.start <= nextConnection + 1_000)
                // End cleanly.
                awaitTrue("$label: the demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
                awaitTrue("$label: the tabs settle", deadline) { graph().all { it.bodyEnd != null } }
                awaitTrue("$label: the server and the recorder agree", deadline) {
                    synchronized(received) { received.toList() }.sorted() == sends.all().map { it.key }.sorted()
                }
                quiet(label, 1_000)
                // Attributed at the server, with no time boundary: every request a round issued went with the permit open.
                val sent = synchronized(origins) { origins.toList() }
                println("CUT-C01 $label origins $sent")
                assertEquals("$label: premise: every catalog since the hook is attributed", catalogs().size - catalogsAtHook, sent.count { it.kind == "catalog" })
                assertTrue("$label: premise: every graph request was tagged as it was sent", sent.all { it.permitOpen != null })
                assertEquals("$label: no round-issued request without the permit\n${sends.timeline()}", emptyList<SendOrigin>(),
                    sent.filter { it.byRound == true && it.permitOpen != true })
            }
        } finally {
            refuseUsd = null
            permitAtSend = null
            restorer.cancel()
            grantWatch?.cancel()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 70_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT03b-Q05b, the contrast on the same grant: offline closes the permit while the next round falls due; back online the
     * budget resumes on its preserved deadline at once, and no topic already bootstrapped under this grant is bootstrapped
     * again. (This row is not counted as evidence for a general P4 release.)
     */
    @Test
    fun `RT03b-Q05b on the same grant an offline round resumes on its deadline and no bootstrap is issued again`() = runBlocking {
        val label = "RT03b-Q05b"
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 45_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            val starts = closedStarts()
            val rounds = AtomicInteger()
            onTab = { ok(usdTab(starts.take(48 * rounds.incrementAndGet()))) }
            rig.awaitTopics(deadline)
            delay(3_500)
            val grant = checkNotNull(rig.coordinator.topicGrantResult().fence)
            val baseline = tabs().size
            openSockets.last().close(1011, "server restart")
            awaitTrue("$label: round 1 completed", deadline) { tabs().size == baseline + 1 && tabs().last().bodyEnd != null }
            awaitTrue("$label: the reconnected topics settle", deadline) { bootstraps().all { it.bodyEnd != null } && openSockets.isNotEmpty() }
            delay(500)
            val firstEnd = checkNotNull(tabs().last().bodyEnd)
            val wallAtOrigin = System.currentTimeMillis() - nowMillis()
            awaitTrue("$label: the next round is scheduled after round 1", deadline) {
                rig.usdBudget()?.deadline?.let { it.toEpochMilliseconds() - wallAtOrigin > firstEnd } == true
            }
            val before = checkNotNull(rig.usdBudget())
            val due = checkNotNull(before.deadline).toEpochMilliseconds() - wallAtOrigin
            val bootstrapsBefore = bootstraps().size
            rig.online.value = false
            awaitTrue("$label: the permit closed", deadline) { rig.onMain { checkNotNull(rig.permitSlot).require()() }?.automatic != true }
            assertTrue("$label: premise: the permit closed before the deadline (${nowMillis()} vs $due)", nowMillis() < due)
            assertEquals("$label: premise: nothing went before the permit closed", baseline + 1, tabs().size)
            while (nowMillis() < due + 2_000) {
                assertEquals("$label: nothing sent past the deadline while offline", baseline + 1, tabs().size)
                delay(50)
            }
            assertEquals("$label: the deadline is kept", before.deadline, checkNotNull(rig.usdBudget()).deadline)
            val back = nowMillis()
            rig.online.value = true
            awaitTrue("$label: the next round", deadline) { tabs().size == baseline + 2 }
            assertTrue("$label: at once on the preserved deadline (${tabs().last().start - back} ms)\n${sends.timeline()}", tabs().last().start - back < 3_000)
            assertEquals("$label: premise: the same grant", grant.grant, rig.coordinator.topicGrantResult().fence?.grant)
            delay(3_000)
            val reissued = bootstraps().drop(bootstrapsBefore)
            assertEquals("$label: no topic bootstrapped again on the same grant: ${reissued.map { it.param("topic") }}\n${sends.timeline()}",
                emptyList<String?>(), reissued.map { it.param("topic") })
            onTab = { ok(usdTab(starts)) }
            awaitTrue("$label: the demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
            awaitTrue("$label: the tabs settle", deadline) { graph().all { it.bodyEnd != null } }
            quiet(label, 1_000)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 45_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 2b: RT01-B04 (cut_c01_b2_agreed.r1.md) ----------------------------------------------------------------

    /** Lowercase hex of [value]'s UTF-8 bytes, as the graph disk store names its directories. */
    private fun diskHex(value: String): String = value.encodeToByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Every KRX file on disk with the SHA-256 of its bytes. */
    private fun Rig.krxFiles(): Map<String, String> = writtenFiles().filter { it.startsWith("krx/") }.associateWith { path ->
        java.security.MessageDigest.getInstance("SHA-256").digest(File(diskRoot, path).readBytes()).joinToString("") { "%02x".format(it) }
    }

    /** KRX files new or changed since [before] (a file the purger removed is neither). */
    private fun Rig.krxWrittenSince(before: Map<String, String>): Set<String> =
        krxFiles().filter { (path, hash) -> before[path] != hash }.keys

    /** Waits until the coordinator holds no write task, read on Main. */
    private suspend fun Rig.awaitWritesDrained(label: String, deadline: Long) = awaitTrue("$label: the graph writes drained", deadline) {
        onMain { (privateField(assembly.coordinator, "writeTasks") as Map<*, *>).isEmpty() }
    }

    /** The registered usd request for [period] as sent: whether one is registered, and its capture's KRX epoch. Read on Main. */
    private suspend fun Rig.usdCaptureEpoch(period: String): Pair<Boolean, String?> = onMain {
        @Suppress("UNCHECKED_CAST")
        val requests = privateField(assembly.coordinator, "tabRequests") as Map<com.jay.fxi.data.graph.GraphKey, Any>
        val registration = requests.entries.firstOrNull { it.key.tab == "usd" && it.key.period.code == period }?.value
            ?: return@onMain false to null
        val capture = privateField(registration, "accessCapture") as com.jay.fxi.data.graph.GraphV2AccessCapture?
        true to capture?.krxCapabilityEpoch
    }

    /** Selects 3m on [holder] with the token the screen offers once it offers one for the current context. */
    private suspend fun selectThreeMonths(rig: Rig, holder: GraphV2ScreenStateHolder, label: String, deadline: Long) {
        // A token of the delivered grant's context, read and used in one Main step (an older context's token can still be
        // offered just before the screen retires it).
        awaitTrue("$label: the screen goes to 3m on the current context", deadline) {
            val fence = rig.bridge()
            rig.onMain {
                val token = holder.currentState().inlineToken
                if (token != null && fence != null && token.fence == fence) {
                    holder.selectPeriod(token, GraphPeriod.THREE_MONTHS)
                }
                holder.currentState().let { it.activePeriod == GraphPeriod.THREE_MONTHS && fence != null && it.inlineToken?.fence == fence }
            }
        }
    }

    /** The usd entry key for [period], read on Main. */
    private suspend fun Rig.usdKey(period: String): com.jay.fxi.data.graph.GraphKey? = onMain {
        assembly.coordinator.state.value.entries.keys.firstOrNull { it.tab == "usd" && it.period.code == period }
    }

    /**
     * RT01-B04a: a request carrying the old KRX epoch completes released. Under KRX VISIBLE (epoch E1) a 3m tab is sent with
     * an E1 capture and held; a capability rotation (KRX hidden) moves the epoch and gives a new grant on the same user epoch,
     * and the new context's requests are held. The old 3m then completes (200, KRX-visible values no other answer carries):
     * nothing of it is applied — no 3m entry, no slot — and no KRX file is written.
     */
    @Test
    fun `RT01-B04a a request carrying the old KRX epoch completes released and applies and writes nothing`() = runBlocking {
        val label = "RT01-B04a"
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 45_000
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        val holdOld = java.util.concurrent.CountDownLatch(1)
        val holdNew = java.util.concurrent.CountDownLatch(1)
        val krxVisible = java.util.concurrent.atomic.AtomicBoolean(true)
        val oldBody = shiftedTab(fixture("usd-3m-krx-visible.json"), 7.0)
        val oldAll = tabValues(oldBody).values.flatten().toSet()
        val threeMonthCalls = AtomicInteger()
        onCatalog = {
            if (krxVisible.get()) ok(fixture("catalog-krx-visible.json"))
            else { holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("catalog-krx-hidden.json")) }
        }
        onTab = { request ->
            val threeMonth = request.requestUrl!!.queryParameter("period") == "3m"
            when {
                threeMonth && threeMonthCalls.getAndIncrement() == 0 -> { holdOld.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(oldBody) }
                krxVisible.get() -> ok(fixture("usd-1d-krx-visible.json"))
                else -> {
                    holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    ok(fixture(if (threeMonth) "usd-3m-krx-hidden.json" else "usd-1d-krx-hidden.json"))
                }
            }
        }
        val rig = Rig()
        try {
            rig.row {
                val holder = checkNotNull(rig.coldStart(deadline))
                rig.awaitTopics(deadline)
                awaitTrue("$label: catalog and the usd tab applied", deadline) {
                    rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null }
                }
                quiet("$label settle", 3_500)
                val e1 = checkNotNull(rig.epochStore.load().krxCapabilityEpoch)
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m tab is in flight", deadline) { tabs().any { it.param("period") == "3m" && it.bodyEnd == null } }
                assertEquals("$label: premise: the 3m request carries the E1 capture", true to e1, rig.usdCaptureEpoch("3m"))
                val krxBefore = rig.krxFiles()
                val f1 = checkNotNull(rig.bridge())
                val rotatedAt = nowMillis()
                krxVisible.set(false)
                entitlements += ok(PREMIUM_HIDDEN)
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("$label: premise: the same user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                assertNotEquals("$label: premise: the KRX epoch moved", e1, rig.epochStore.load().krxCapabilityEpoch)
                awaitTrue("$label: the new context is consumed", deadline) { rig.assembly.coordinator.state.value.source?.fence == f2 }
                assertEquals("$label: the old 3m request is released (no 3m registered)", false to null as String?, rig.usdCaptureEpoch("3m"))

                holdOld.countDown()
                awaitTrue("$label: the old 3m body ended", deadline) { tabs().first { it.param("period") == "3m" }.bodyEnd != null }
                assertEquals("$label: premise: the old answer was a 200", 200, tabs().first { it.param("period") == "3m" }.status)
                delay(500)
                val key = rig.usdKey("3m")
                assertNull("$label: no 3m entry from the old answer", key)
                assertTrue("$label: no old value anywhere in the entries", rig.onMain {
                    rig.assembly.coordinator.state.value.entries.values
                        .flatMap { e -> e.tab.graph.series.flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } } }
                        .toSet().intersect(oldAll).isEmpty()
                })
                rig.awaitWritesDrained(label, deadline)
                assertEquals("$label: no KRX file written or changed by the old answer", emptySet<String>(), rig.krxWrittenSince(krxBefore))
                holdNew.countDown()
                settleNewSession(label, rotatedAt, deadline)
                rig.awaitWritesDrained(label, deadline)
                // Everything after the rotation is KRX-hidden, so a late write of the old answer is the only way a KRX file appears.
                assertEquals("$label: still no KRX file written or changed at the end", emptySet<String>(), rig.krxWrittenSince(krxBefore))
            }
        } finally {
            holdOld.countDown()
            holdNew.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 45_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-B04b: only a capture of the new KRX epoch uses the new KRX half. Under KRX VISIBLE (epoch E1) the 3m tab is applied,
     * written under E1 and exposed with KRX (the E1 control). A capability rotation hides KRX (the epoch moves to E2); the
     * re-requested 3m captures no KRX epoch and is held, answered with KRX-visible data (the catalog stays KRX-visible, so no
     * catalog filtering removes KRX). A re-approval makes KRX visible on E2. The held answer then completes and is applied —
     * its own GENERAL values reach the entry and a protected read — with no KRX in its slot or the read, the entry unconfirmed,
     * and, once the writes drain, no KRX file written or changed (the next request's answer is held meanwhile). Each phase's
     * answer carries its own values. The next 3m request captures E2: its slot's KRX key is E2, a KRX file is written under
     * E2, and the protected read's KRX values are the E2 answer's, none of the E1 control's, while the E1 file stays on disk.
     */
    @Test
    fun `RT01-B04b under a hidden capability the tab captures no KRX epoch and after the re-approval only the new epoch is used`() = runBlocking {
        val label = "RT01-B04b"
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 50_000
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        // 0 = visible, 1 = hidden (3m answers held), 2 = re-approved.
        val phase = AtomicInteger(0)
        val holdHidden = java.util.concurrent.CountDownLatch(1)
        // The re-approved phase's 3m answers wait until the held answer is judged, so no legitimate E2 write mixes in.
        val holdE2 = java.util.concurrent.CountDownLatch(1)
        val threeMonthEpochs = Collections.synchronizedList(mutableListOf<Pair<Boolean, String?>>())
        // Each phase's 3m answer carries values no other phase's does (all KRX-visible): which answer a value came from is
        // readable from the value itself.
        val controlBody = fixture("usd-3m-krx-visible.json")
        val heldBody = shiftedTab(controlBody, 3.0)
        val e2Body = shiftedTab(controlBody, 5.0)
        val generalOf = { body: String -> tabValues(body).filterKeys { !it.startsWith("krx.") }.values.flatten().toSet() }
        val krxOf = { body: String -> tabValues(body).filterKeys { it.startsWith("krx.") }.values.flatten().toSet() }
        onCatalog = { ok(fixture("catalog-krx-visible.json")) }
        val rig = Rig()
        onTab = { request ->
            if (request.requestUrl!!.queryParameter("period") == "3m") {
                threeMonthEpochs += runBlocking { rig.usdCaptureEpoch("3m") }
                // The answer is decided when the request arrives: the held one stays the hidden phase's answer.
                when (phase.get()) {
                    0 -> ok(controlBody)
                    1 -> { holdHidden.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(heldBody) }
                    else -> { holdE2.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(e2Body) }
                }
            } else ok(fixture("usd-1d-krx-visible.json"))
        }
        try {
            rig.row {
                val holder = checkNotNull(rig.coldStart(deadline))
                rig.awaitTopics(deadline)
                awaitTrue("$label: catalog and the usd tab applied", deadline) {
                    rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null }
                }
                quiet("$label settle", 3_500)
                val record = rig.epochStore.load()
                val e1 = checkNotNull(record.krxCapabilityEpoch)
                val f1 = checkNotNull(rig.bridge())
                val krxDir = { epoch: String -> "krx/${diskHex(f1.identity.uid)}/${diskHex(checkNotNull(f1.userAccessEpoch))}/${diskHex(epoch)}/${diskHex("usd")}/3m.json" }
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m tab applied under E1", deadline) { krxDir(e1) in rig.writtenFiles() }
                val key = checkNotNull(rig.usdKey("3m"))
                assertTrue("$label: premise: the E1 control is exposed with KRX", rig.onMain {
                    rig.assembly.coordinator.protectedEntry(key)?.tab?.graph?.series?.any { it.seriesId.startsWith("krx.") } == true
                })
                assertEquals("$label: premise: its slot's KRX key is E1", e1, rig.onMain { rig.slotComponents(key)?.krx?.key?.krxCapabilityEpoch })

                // Hidden: the epoch moves; the re-requested 3m captures no KRX epoch and is held.
                phase.set(1)
                val calls3m = threeMonthEpochs.size
                entitlements += ok(PREMIUM_HIDDEN)
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                val e2 = checkNotNull(rig.epochStore.load().krxCapabilityEpoch)
                assertNotEquals("$label: premise: the KRX epoch moved", e1, e2)
                // The new grant's context starts the screen on 1d; the screen goes back to 3m.
                selectThreeMonths(rig, holder, label, deadline)
                awaitTrue("$label: the hidden 3m request is sent", deadline) { threeMonthEpochs.size > calls3m }
                assertEquals("$label: it captures no KRX epoch", true to null as String?, threeMonthEpochs[calls3m])
                // Re-approved: KRX visible on E2.
                entitlements += ok(premiumKrxVisible)
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                assertEquals("$label: premise: the re-approval keeps E2", e2, rig.epochStore.load().krxCapabilityEpoch)
                assertTrue("$label: premise: KRX is allowed again", rig.coordinator.accessSnapshot.facts.capabilityAllowed)
                phase.set(2)
                val callsHidden = threeMonthEpochs.size
                val krxBeforeHeld = rig.krxFiles()
                holdHidden.countDown()
                awaitTrue("$label: the held answer completed", deadline) {
                    tabs().filter { it.param("period") == "3m" }.let { t -> t.size >= 2 && t[1].bodyEnd != null }
                }
                val heldGeneral = generalOf(heldBody)
                // The null-capture answer is applied: its own GENERAL values reach the entry (not the E1 control's).
                awaitTrue("$label: the held answer's GENERAL is applied", deadline) {
                    rig.onMain {
                        rig.assembly.coordinator.state.value.entries[key]?.tab?.graph?.series.orEmpty()
                            .filterNot { it.seriesId.startsWith("krx.") }.flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } }
                            .let { values -> values.isNotEmpty() && values.all { it in heldGeneral } }
                    }
                }
                rig.awaitWritesDrained(label, deadline)
                val afterHidden = rig.onMain {
                    val slot = rig.slotComponents(key)
                    val entry = rig.assembly.coordinator.state.value.entries[key]
                    val read = rig.assembly.coordinator.protectedEntry(key)?.tab?.graph?.series.orEmpty()
                    Triple(slot?.krx, entry?.online200At, read)
                }
                val readIds = afterHidden.third.map { it.seriesId }
                val readGeneral = afterHidden.third.filterNot { it.seriesId.startsWith("krx.") }
                    .flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } }
                println("CUT-C01 $label after the null-capture answer: slot krx ${afterHidden.first?.key}; entry confirmed ${afterHidden.second}; read $readIds")
                assertNull("$label: no KRX in the slot from the null capture", afterHidden.first)
                assertTrue("$label: no KRX in a protected read", readIds.none { it.startsWith("krx.") })
                assertTrue("$label: the protected read's GENERAL is the held answer's", readGeneral.isNotEmpty() && readGeneral.all { it in heldGeneral })
                assertNull("$label: the entry is unconfirmed", afterHidden.second)
                assertEquals("$label: no KRX file written or changed by the null-capture answer", emptySet<String>(), rig.krxWrittenSince(krxBeforeHeld))
                holdE2.countDown()

                // The next 3m request captures E2 and only it uses the KRX half (the screen back on 3m if a new context reset it).
                if (rig.onMain { holder.currentState().activePeriod } != GraphPeriod.THREE_MONTHS) selectThreeMonths(rig, holder, label, deadline)
                awaitTrue("$label: a 3m request with the E2 capture is sent", deadline) {
                    threeMonthEpochs.drop(callsHidden).any { it == (true to e2) }
                }
                awaitTrue("$label: its KRX is written under E2", deadline) { krxDir(e2) in rig.writtenFiles() }
                awaitTrue("$label: the slot's KRX key is E2 and the protected read has KRX", deadline) {
                    rig.onMain {
                        rig.slotComponents(key)?.krx?.key?.krxCapabilityEpoch == e2 &&
                            rig.assembly.coordinator.protectedEntry(key)?.tab?.graph?.series?.any { it.seriesId.startsWith("krx.") } == true
                    }
                }
                assertTrue("$label: the E1 file stays on disk", krxDir(e1) in rig.writtenFiles())
                // Never joined with E1: the protected read's KRX values are the E2 answer's, none of the E1 control's.
                val readKrx = rig.onMain {
                    rig.assembly.coordinator.protectedEntry(key)?.tab?.graph?.series.orEmpty().filter { it.seriesId.startsWith("krx.") }
                        .flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } }
                }
                assertTrue("$label: the protected read's KRX is the E2 answer's ($readKrx)",
                    readKrx.isNotEmpty() && readKrx.all { it in krxOf(e2Body) } && readKrx.none { it in krxOf(controlBody) })
                println("CUT-C01 $label 3m capture epochs:${threeMonthEpochs.map { it.second?.let { e -> if (e == e1) "E1" else if (e == e2) "E2" else "other" } ?: "null" }}")
                assertTrue("$label: no 3m request after the rotation captured E1",
                    threeMonthEpochs.drop(calls3m).none { it.second == e1 })
                rig.awaitTopics(deadline)
                awaitTrue("$label: the tabs settle", deadline) { graph().all { it.bodyEnd != null } }
                awaitTrue("$label: the server and the recorder agree", deadline) {
                    synchronized(received) { received.toList() }.sorted() == sends.all().map { it.key }.sorted()
                }
                quiet(label, 1_000)
            }
        } finally {
            holdHidden.countDown()
            holdE2.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 50_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 2b: RT01-B05 (cut_c01_b2_agreed.r1.md) ----------------------------------------------------------------

    /** [json] (a graph tab) with every point's rate, high and low raised by [delta]: values no other answer carries. */
    private fun shiftedTab(json: String, delta: Double): String {
        val base = wire.parseToJsonElement(json).jsonObject
        val fields = setOf("rate", "high", "low")
        val series = kotlinx.serialization.json.JsonArray(base.getValue("series").jsonArray.map { element ->
            val obj = element.jsonObject
            val data = obj["data"]?.jsonArray ?: return@map obj
            kotlinx.serialization.json.JsonObject(obj + ("data" to kotlinx.serialization.json.JsonArray(data.map { point ->
                kotlinx.serialization.json.JsonObject(point.jsonObject.mapValues { (key, value) ->
                    val number = (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                    if (key in fields && number != null) kotlinx.serialization.json.JsonPrimitive(number + delta) else value
                })
            })))
        })
        return kotlinx.serialization.json.JsonObject(base + ("series" to series)).toString()
    }

    /** Every rate, high and low a graph tab answer carries, by series id. */
    private fun tabValues(json: String): Map<String, Set<Double>> =
        wire.parseToJsonElement(json).jsonObject.getValue("series").jsonArray.associate { element ->
            val obj = element.jsonObject
            obj.getValue("id").jsonPrimitive.content to obj["data"]?.jsonArray.orEmpty().flatMap { point ->
                listOf("rate", "high", "low").mapNotNull { point.jsonObject[it]?.jsonPrimitive?.content?.toDoubleOrNull() }
            }.toSet()
        }

    /** The coordinator's shared retry floor, read on Main. */
    private suspend fun Rig.retryFloor(): kotlinx.datetime.Instant? = onMain {
        privateField(assembly.coordinator, "sharedRetryFloor") as kotlinx.datetime.Instant?
    }

    /**
     * RT01-B05a: a late old completion with a Retry-After, after a same-scope context change. A closed demand's round
     * (carrying kb.usd's capture) is held; a capability rotation (KRX hidden) gives a new grant on the same user epoch, and the
     * new context's requests are held too. The old round then completes: 200, KRX-visible data covering the whole demand
     * (kb.usd at every closed start) with values no other answer carries, and Retry-After 20. None of it is applied — not to
     * the entries, the protected read or the recorder, and the demand is unchanged — yet its floor (20 s + the usd jitter of
     * 10 s) is kept: once the new context's held requests are answered, a 3m selection is not sent before the floor (nor
     * before the old body end + 30 s), nor is any other graph request.
     */
    @Test
    fun `RT01-B05a a late old completion after a context change applies nothing and its Retry-After floor holds the new context`() = runBlocking {
        val label = "RT01-B05a"
        assertEquals("premise: J = 10 s for usd", 10.seconds, FreeSnapshotSchedulePolicy.jitterFor(SEED, "usd"))
        val prepared = clearOfBoundary(90_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 80_000
        val wallAtOrigin = System.currentTimeMillis() - nowMillis()
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        val holdOld = java.util.concurrent.CountDownLatch(1)
        val holdNew = java.util.concurrent.CountDownLatch(1)
        val krxVisible = java.util.concurrent.atomic.AtomicBoolean(true)
        val rig = Rig()
        try {
            rig.row {
                rig.prepareClosedDemand(deadline, krx = true)
                val starts = closedStarts()
                // The old answer covers the whole demand (kb.usd at every closed start), KRX-visible, every value shifted: applied
                // as a recovery it would release the demand and move the recorder's tip.
                val oldBody = shiftedTab(usdTab(starts, "usd-1d-krx-visible.json"), 7.0)
                val oldValues = tabValues(oldBody)
                assertEquals("premise: the old answer's kb.usd covers the demand", starts.size,
                    wire.parseToJsonElement(oldBody).jsonObject.getValue("series").jsonArray
                        .first { it.jsonObject.getValue("id").jsonPrimitive.content == "kb.usd" }.jsonObject.getValue("data").jsonArray.size)
                val holder = checkNotNull(rig.onMain { rig.mount?.holders?.value?.get("usd") })
                onCatalog = {
                    if (krxVisible.get()) ok(fixture("catalog-krx-visible.json"))
                    else { holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("catalog-krx-hidden.json")) }
                }
                val calls = AtomicInteger()
                onTab = { request ->
                    if (calls.getAndIncrement() == 0) {
                        holdOld.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        ok(oldBody).setHeader("Retry-After", 20)
                    } else {
                        holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        if (request.requestUrl!!.queryParameter("period") == "3m") ok(fixture("usd-3m-krx-hidden.json"))
                        else ok(fixture("usd-1d-krx-hidden.json"))
                    }
                }
                rig.awaitTopics(deadline)
                delay(3_500)
                val baseline = tabs().size
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: the round is in flight", deadline) { tabs().size == baseline + 1 }
                val captured = checkNotNull(rig.usdTabRegistration().captures)
                assertTrue("$label: premise: the old round carries kb.usd's capture: $captured", captured.any { it.seriesKey.seriesId == "kb.usd" })
                val f1 = checkNotNull(rig.bridge())
                val rotatedAt = nowMillis()
                krxVisible.set(false)
                entitlements += ok(PREMIUM_HIDDEN)
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("$label: premise: the same user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                assertNotEquals("$label: premise: a new grant", f1.grant, f2.grant)
                awaitTrue("$label: the new context is consumed", deadline) { rig.assembly.coordinator.state.value.source?.fence == f2 }
                assertNull("$label: premise: no floor yet", rig.retryFloor())

                holdOld.countDown()
                awaitTrue("$label: the old round's body ended", deadline) { tabs()[baseline].bodyEnd != null }
                val oldEnd = checkNotNull(tabs()[baseline].bodyEnd)
                assertEquals("$label: premise: the old answer was a 200", 200, tabs()[baseline].status)
                awaitTrue("$label: the old answer's floor is recorded", deadline) { rig.retryFloor() != null }
                val floorAt = checkNotNull(rig.retryFloor()).toEpochMilliseconds() - wallAtOrigin
                println("CUT-C01 $label: old body end $oldEnd ms, floor at $floorAt ms")
                assertTrue("$label: the floor is the Retry-After plus the jitter ($floorAt vs $oldEnd)", floorAt >= oldEnd + 30_000 - 50 && floorAt <= oldEnd + 31_000)
                delay(300)
                // None of the old answer is applied.
                assertEquals("$label: the demand is unchanged", starts.toSet(), rig.kbClosedPending())
                val applied = rig.onMain {
                    rig.assembly.coordinator.state.value.entries.values.flatMap { e -> e.tab.graph.series.flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } } }.toSet()
                }
                val protected = rig.onMain {
                    rig.assembly.coordinator.state.value.entries.keys.mapNotNull { rig.assembly.coordinator.protectedEntry(it) }
                        .flatMap { e -> e.tab.graph.series.flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } } }.toSet()
                }
                val oldAll = oldValues.values.flatten().toSet()
                assertTrue("$label: no old value in the entries", applied.intersect(oldAll).isEmpty())
                assertTrue("$label: no old value in a protected read", protected.intersect(oldAll).isEmpty())
                val recorded = rig.kbState()?.data?.app?.tip?.rate
                assertFalse("$label: no old value in the recorder (tip $recorded)", recorded != null && recorded in oldAll)

                // The floor holds the new context: its held requests answered, a 3m selection waits for the floor.
                holdNew.countDown()
                awaitTrue("$label: the new context's held requests are answered", deadline) { graph().all { it.bodyEnd != null } }
                val graphBefore = graph().size
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m tab went", deadline) { tabs().any { it.param("period") == "3m" } }
                val threeMonth = tabs().first { it.param("period") == "3m" }
                println("CUT-C01 $label: 3m sent ${threeMonth.start} ms")
                assertTrue("$label: the 3m send waits for the floor (${threeMonth.start} vs $floorAt)\n${sends.timeline()}", threeMonth.start >= floorAt - 50)
                assertTrue("$label: and for the old body end + 30 s (${threeMonth.start} vs $oldEnd)", threeMonth.start >= oldEnd + 30_000 - 50)
                assertTrue("$label: within 2 s of it (${threeMonth.start} vs $oldEnd)", threeMonth.start <= oldEnd + 32_000)
                assertTrue("$label: and goes at it (${threeMonth.start} vs $floorAt)", threeMonth.start <= floorAt + 2_000)
                assertEquals("$label: no other graph send under the floor\n${sends.timeline()}", emptyList<SendRecorder.Exchange>(),
                    graph().drop(graphBefore).filter { it.start < floorAt - 50 })
                awaitTrue("$label: the 3m tab applied", deadline) { tabs().first { it.param("period") == "3m" }.bodyEnd != null }
                settleNewSession(label, rotatedAt, deadline)
            }
        } finally {
            holdOld.countDown()
            holdNew.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 80_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-B05b: a late old completion with a Retry-After, after the same user's end (RT01-A05 separate). A 3m tab is in
     * flight; the user signs in again and the new approval's answer is held, so the end is delivered alone and the old context
     * is disposed. The old 3m then completes on the old token (200, values no other answer carries, Retry-After 20): nothing of
     * it is applied, and its floor is recorded before the new approval is released. The new context's refill (catalog and
     * tab) is not sent before that floor, and goes once it passes.
     */
    @Test
    fun `RT01-B05b a late old completion after the user's end keeps its floor over the new context's refill`() = runBlocking {
        val label = "RT01-B05b"
        assertEquals("premise: J = 10 s for usd", 10.seconds, FreeSnapshotSchedulePolicy.jitterFor(SEED, "usd"))
        val prepared = clearOfBoundary(90_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 80_000
        val wallAtOrigin = System.currentTimeMillis() - nowMillis()
        entitlements += unauthorized()
        val holdOld = java.util.concurrent.CountDownLatch(1)
        val oldBody = shiftedTab(fixture("usd-3m-krx-hidden.json"), 7.0)
        val oldAll = tabValues(oldBody).values.flatten().toSet()
        onCatalog = { ok(fixture("catalog-krx-hidden.json")) }
        onTab = { request ->
            if (request.requestUrl!!.queryParameter("period") == "3m") {
                holdOld.await(30, java.util.concurrent.TimeUnit.SECONDS)
                ok(oldBody).setHeader("Retry-After", 20)
            } else ok(fixture("usd-1d-krx-hidden.json"))
        }
        val rig = Rig()
        try {
            rig.row {
                val holder = checkNotNull(rig.coldStart(deadline))
                rig.awaitTopics(deadline)
                awaitTrue("$label: catalog and the usd tab applied", deadline) {
                    rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null }
                }
                val f1 = checkNotNull(rig.bridge())
                quiet("$label settle", 3_500)
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m tab is in flight", deadline) { tabs().any { it.param("period") == "3m" && it.bodyEnd == null } }

                val endAt = nowMillis()
                entitlementsGate = java.util.concurrent.CountDownLatch(1)
                val approval = CoroutineScope(rig.issuerJob + Dispatchers.Default).launch {
                    val next = rig.firebase.nextGeneration()
                    rig.coordinator.onIdentityChanged(next)
                    rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
                }
                var oldEnd = 0L
                var floorAt = 0L
                var releasedAt = 0L
                try {
                    awaitTrue("$label: the end alone was delivered", deadline) { rig.bridge() == null }
                    awaitTrue("$label: the old context is disposed", deadline) { rig.assembly.coordinator.state.value.source == null }
                    assertNull("$label: premise: no floor yet", rig.retryFloor())
                    holdOld.countDown()
                    awaitTrue("$label: the old 3m body ended", deadline) { tabs().first { it.param("period") == "3m" }.bodyEnd != null }
                    oldEnd = checkNotNull(tabs().first { it.param("period") == "3m" }.bodyEnd)
                    awaitTrue("$label: the old answer's floor is recorded", deadline) { rig.retryFloor() != null }
                    floorAt = checkNotNull(rig.retryFloor()).toEpochMilliseconds() - wallAtOrigin
                    println("CUT-C01 $label: old 3m body end $oldEnd ms, floor at $floorAt ms")
                    assertTrue("$label: the floor is the Retry-After plus the jitter ($floorAt vs $oldEnd)",
                        floorAt >= oldEnd + 30_000 - 50 && floorAt <= oldEnd + 31_000)
                    delay(300)
                    assertTrue("$label: nothing of the old answer is applied", rig.onMain {
                        rig.assembly.coordinator.state.value.entries.values
                            .flatMap { e -> e.tab.graph.series.flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } } }
                            .toSet().intersect(oldAll).isEmpty()
                    })
                } finally {
                    releasedAt = nowMillis()
                    entitlementsGate?.countDown()
                    entitlementsGate = null
                }
                approval.join()
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("$label: premise: the same user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                awaitTrue("$label: the new grant is delivered", deadline) { rig.bridge() == f2 }
                awaitTrue("$label: the new context is consumed", deadline) { rig.assembly.coordinator.state.value.source?.fence == f2 }
                // The refill waits for the floor, then goes.
                awaitTrue("$label: the refill went", deadline) { graph().any { it.start > releasedAt } }
                val refill = graph().filter { it.start > releasedAt }
                println("CUT-C01 $label: refill ${refill.map { "${it.path.substringAfterLast('/')} ${it.start}" }}")
                assertTrue("$label: no refill before the floor ($floorAt)\n${sends.timeline()}", refill.all { it.start >= floorAt - 50 })
                assertTrue("$label: the refill goes at the floor (${refill.first().start} vs $floorAt)", refill.first().start <= floorAt + 2_000)
                awaitTrue("$label: the refill applied", deadline) { rig.usdApplied() && graph().all { it.bodyEnd != null } }
                assertTrue("$label: still nothing of the old answer", rig.onMain {
                    rig.assembly.coordinator.state.value.entries.values
                        .flatMap { e -> e.tab.graph.series.flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } } }
                        .toSet().intersect(oldAll).isEmpty()
                })
                settleNewSession(label, endAt, deadline)
            }
        } finally {
            holdOld.countDown()
            entitlementsGate?.countDown(); entitlementsGate = null
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 80_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 3a: RT01-A06, RT01-S02 (cut_c01_b3_agreed.r1.md) -----------------------------------------------------

    /** The coordinator's request publication, read on Main. */
    private suspend fun Rig.requestState(): com.jay.fxi.data.graph.GraphRequestState = onMain { assembly.coordinator.state.value }

    /** The coordinator's protected slot object for [key] (the object itself, not its components), read on Main. */
    private suspend fun Rig.slotObject(key: com.jay.fxi.data.graph.GraphKey): Any? = onMain {
        (privateField(assembly.coordinator, "protectedSlots") as Map<*, *>)[key]
    }

    /** Whether the usd 1d disk seed was tried and has finished (no seed registration left), read on Main. */
    private suspend fun Rig.usdSeedFinished(): Boolean = onMain {
        val c = assembly.coordinator
        val tried = privateField(c, "attemptedSeedKey") as com.jay.fxi.data.graph.GraphKey?
        tried?.tab == "usd" && tried.period.code == "1d" && privateField(c, "seedRegistration") == null
    }

    /** Every file of the graph disk store with the SHA-256 of its bytes. */
    private fun Rig.diskFiles(): Map<String, String> = writtenFiles().associateWith { path ->
        java.security.MessageDigest.getInstance("SHA-256").digest(File(diskRoot, path).readBytes()).joinToString("") { "%02x".format(it) }
    }

    /** The last real observation of every series on the screen's last published chart (its state, not a new render), on Main. */
    private suspend fun Rig.shownLast(holder: GraphV2ScreenStateHolder): Map<String, com.jay.fxi.ui.graph.LinePoint?>? = onMain {
        holder.state.value.chart?.prepared?.bySeries?.mapValues { it.value.lastObservation }
    }

    /**
     * RT01-A06: a grant change with no end keeps the last good entry (the A2a03b contract, in the production composition). A
     * real 3m 503 leaves one failure and a second 3m request is sent and held; the screen is back on a confirmed 1d. A refused
     * subscription makes the issuer re-approve on a new grant (same identity, user epoch, KRX epoch, no USER or capability
     * end), and the new context's graph answers are held. Once the new context is consumed: the 1d entry and its stamp and the
     * protected slot are the same objects, the catalog and failures are dropped, the old 3m request is released and its late
     * 200 is never applied; the screen (through the batch 3 route) shows the kept entry under the new owner. The entry is fresh
     * (TTL 3600 s with no catalog, same KST date), the recorder holds no series and no trigger or recovery cycle occurs in the
     * window: the screen's own activation sends the one catalog, then for a second and until the release no tab goes; the
     * kept objects are checked again just before the release and the old 200 again at the end. The auth-generation half is
     * structurally excluded: the issuer records an IDENTITY_CHANGED USER end on every identity change
     * (PremiumAccessCoordinator.kt:454), which is RT01-A05.
     */
    @Test
    fun `RT01-A06 a re-approval with no end keeps the last good entry and slot and drops the old requests`() = runBlocking {
        val label = "RT01-A06"
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 50_000
        entitlements += unauthorized()
        val hold3m = java.util.concurrent.CountDownLatch(1)
        val holdNew = java.util.concurrent.CountDownLatch(1)
        val afterReapproval = java.util.concurrent.atomic.AtomicBoolean(false)
        val threeMonthCalls = AtomicInteger()
        val oldBody = shiftedTab(fixture("usd-3m-krx-hidden.json"), 7.0)
        val oldAll = tabValues(oldBody).values.flatten().toSet()
        onCatalog = {
            if (afterReapproval.get()) holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS)
            ok(fixture("catalog-krx-hidden.json"))
        }
        onTab = { request ->
            if (request.requestUrl!!.queryParameter("period") == "3m") {
                when (threeMonthCalls.getAndIncrement()) {
                    0 -> unavailable()
                    1 -> { hold3m.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(oldBody) }
                    else -> { holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("usd-3m-krx-hidden.json")) }
                }
            } else {
                if (afterReapproval.get()) holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS)
                ok(fixture("usd-1d-krx-hidden.json"))
            }
        }
        val rig = Rig()
        val restorer = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                if (!rig.issuerIdentityReadable && rig.coordinator.recheckDiagnostics().owedIntent != null) rig.issuerIdentityReadable = true
                delay(20)
            }
        }
        try {
            rig.row {
                val (_, holder) = rig.coldStartRoute(deadline)
                rig.awaitTopics(deadline)
                awaitTrue("$label: catalog and the usd tab applied", deadline) {
                    rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null }
                }
                quiet("$label settle", 3_500)
                // A real failure: the first 3m answer is a 503.
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m 503 is applied as a failure", deadline) {
                    rig.requestState().failures.keys.any { it.tab == "usd" && it.period.code == "3m" }
                }
                assertEquals("$label: premise: exactly one failure", 1, rig.requestState().failures.size)
                // A separate 3m request: away to 1d and back to 3m, sent, registered and its body held.
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.ONE_DAY) }
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the separate 3m request is sent and registered", deadline) {
                    tabs().count { it.param("period") == "3m" } == 2 && rig.usdCaptureEpoch("3m").first
                }
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.ONE_DAY) }
                awaitTrue("$label: back on 1d", deadline) { rig.onMain { holder.currentState().activePeriod == GraphPeriod.ONE_DAY } }
                val key1d = checkNotNull(rig.usdKey("1d"))
                assertTrue("$label: premise: the 3m request is still registered after the return", rig.usdCaptureEpoch("3m").first)
                assertNull("$label: premise: its body is held", tabs().filter { it.param("period") == "3m" }[1].bodyEnd)
                val before = rig.requestState()
                assertNotNull("$label: premise: a catalog is adopted", before.catalog)
                val entry = checkNotNull(before.entries[key1d]) { "$label: premise: a 1d entry" }
                val stamp = checkNotNull(entry.online200At) { "$label: premise: the 1d entry is confirmed" }
                assertEquals("$label: premise: the failure is still there", 1, before.failures.size)
                val slot = checkNotNull(rig.slotObject(key1d)) { "$label: premise: a protected slot" }
                assertEquals("$label: premise: the active key is usd 1d", key1d, rig.onMain { privateField(rig.assembly.coordinator, "activeKey") })
                val shownBefore = checkNotNull(rig.shownLast(holder)) { "$label: premise: the screen shows the 1d entry" }
                assertEquals("$label: premise: no recovery demand", com.jay.fxi.data.graph.GraphTabRecoveryDemand.None, rig.usdDemand())
                val epoch = rig.epochStore.load().krxCapabilityEpoch
                val userEnds = rig.coordinator.accessSnapshot.lastUserEnd?.sequence ?: 0L
                val capabilityEnds = rig.coordinator.accessSnapshot.lastCapabilityEnd?.sequence ?: 0L
                val f1 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                val tabsBefore = tabs().size
                val catalogsBefore = catalogs().size

                // The re-approval: the reconnect's subscription is refused once and the issuer re-approves on a new grant.
                afterReapproval.set(true)
                refuseUsd = {
                    refuseUsd = null
                    rig.issuerIdentityReadable = false
                }
                val reapprovalAt = nowMillis()
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: a re-approval on a new grant", deadline) {
                    rig.coordinator.topicGrantResult().fence?.grant?.let { it != f1.grant } == true
                }
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("$label: premise: the same identity", f1.identity, f2.identity)
                assertEquals("$label: premise: the same user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                awaitTrue("$label: its context is consumed", deadline) { rig.assembly.coordinator.state.value.source?.fence == f2 }
                // The window: from here until the held answers are released, no trigger and no recovery cycle.
                val triggersInWindow = rig.triggers()
                val noBudgets = suspend { rig.onMain { (privateField(rig.assembly.coordinator, "recoveryBudgets") as Map<*, *>).isEmpty() } }
                assertTrue("$label: premise: no recovery cycle is open", noBudgets())
                // The entry is fresh for the coordinator: with no catalog the TTL is GraphV2Domain.ttl(null), 3600 s, on the same KST date.
                val age = kotlinx.datetime.Clock.System.now() - stamp
                assertTrue("$label: premise: the 1d entry is fresh ($age)", age < 3600.seconds)
                assertEquals("$label: premise: on the same KST date",
                    java.time.Instant.ofEpochMilli(stamp.toEpochMilliseconds()).atOffset(kst).toLocalDate(), java.time.LocalDate.now(kst))
                // Every new graph answer is still held: what the coordinator holds now is what the grant change left.
                val after = rig.requestState()
                assertSame("$label: the 1d entry is the same object", entry, after.entries[key1d])
                assertEquals("$label: with the same stamp", stamp, after.entries[key1d]?.online200At)
                assertSame("$label: the protected slot is the same object", slot, rig.slotObject(key1d))
                assertNull("$label: the catalog is dropped", after.catalog)
                assertEquals("$label: the failures are dropped", emptyMap<com.jay.fxi.data.graph.GraphKey, Throwable>(), after.failures)
                assertEquals("$label: the old 3m request is released", false to null as String?, rig.usdCaptureEpoch("3m"))
                awaitTrue("$label: the screen shows the kept entry under the new owner", deadline) {
                    rig.onMain { holder.currentState().let { it.content == GraphV2Content.READY && it.inlineToken?.fence == f2 } }
                }
                assertEquals("$label: with the same values", shownBefore, rig.shownLast(holder))
                // With the catalog dropped the recorder's demand reads CatalogRequired whatever it holds; no closed demand exists,
                // since the recorder holds no series (no kb delivery in this row), and no trigger can open a cycle in the window.
                assertTrue("$label: premise: the recorder holds no series (no closed demand)",
                    rig.onMain { rig.assembly.recorder.state.value.series.isEmpty() })
                // The screen's own activation sends the one catalog (the catalog is null, so it is needed); then for a second no tab.
                awaitTrue("$label: the screen's activation catalog is sent", deadline) { catalogs().size == catalogsBefore + 1 }
                val windowEnd = nowMillis() + 1_000
                while (nowMillis() < windowEnd) {
                    assertEquals("$label: no new tab sent\n${sends.timeline()}", tabsBefore, tabs().size)
                    delay(50)
                }
                assertEquals("$label: premise: the same KRX epoch", epoch, rig.epochStore.load().krxCapabilityEpoch)
                assertEquals("$label: premise: no USER end", userEnds, rig.coordinator.accessSnapshot.lastUserEnd?.sequence ?: 0L)
                assertEquals("$label: premise: no capability end", capabilityEnds, rig.coordinator.accessSnapshot.lastCapabilityEnd?.sequence ?: 0L)

                // The old 3m completes late: nothing of it is applied.
                val oldValuesApplied = suspend {
                    rig.onMain {
                        rig.assembly.coordinator.state.value.entries.values
                            .flatMap { e -> e.tab.graph.series.flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } } }
                            .toSet().intersect(oldAll).isNotEmpty()
                    }
                }
                hold3m.countDown()
                awaitTrue("$label: the old 3m body ended", deadline) { tabs().filter { it.param("period") == "3m" }[1].bodyEnd != null }
                assertEquals("$label: premise: the old answer was a 200", 200, tabs().filter { it.param("period") == "3m" }[1].status)
                delay(500)
                assertNull("$label: no 3m entry from the old answer", rig.usdKey("3m"))
                assertFalse("$label: no old value in the entries", oldValuesApplied())
                // Just before the new answers are released, the window still holds.
                val beforeRelease = rig.requestState()
                assertSame("$label: still the same 1d entry", entry, beforeRelease.entries[key1d])
                assertEquals("$label: still the same stamp", stamp, beforeRelease.entries[key1d]?.online200At)
                assertSame("$label: still the same slot", slot, rig.slotObject(key1d))
                assertNull("$label: still no catalog", beforeRelease.catalog)
                assertEquals("$label: still no new tab\n${sends.timeline()}", tabsBefore, tabs().size)
                assertEquals("$label: still the one catalog\n${sends.timeline()}", catalogsBefore + 1, catalogs().size)
                assertEquals("$label: no trigger in the window", triggersInWindow, rig.triggers())
                assertTrue("$label: and no recovery cycle", noBudgets())
                holdNew.countDown()
                settleNewSession(label, reapprovalAt, deadline)
                assertNull("$label: at the end, still no 3m entry", rig.usdKey("3m"))
                assertFalse("$label: and no old value in the entries", oldValuesApplied())
            }
        } finally {
            hold3m.countDown()
            holdNew.countDown()
            refuseUsd = null
            restorer.cancel()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 50_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-S02a: with no file on disk the seed restores nothing. On an empty disk the cold start tries the usd 1d seed and it
     * finishes; with the network answer held, the coordinator has no entry and no slot (so no stamp) and the screen waits.
     */
    @Test
    fun `RT01-S02a with no file on disk the seed restores nothing and the screen waits for the network`() = runBlocking {
        val label = "RT01-S02a"
        val prepared = clearOfBoundary(40_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 30_000
        entitlements += unauthorized()
        val holdTab = java.util.concurrent.CountDownLatch(1)
        onTab = { holdTab.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("usd-1d-krx-hidden.json")) }
        val rig = Rig()
        try {
            rig.row {
                assertEquals("$label: premise: the graph disk is empty", emptyList<String>(), rig.writtenFiles())
                val (_, holder) = rig.coldStartRoute(deadline)
                awaitTrue("$label: the 1d tab is sent and held", deadline) { tabs().any { it.param("period") == "1d" && it.bodyEnd == null } }
                awaitTrue("$label: the 1d seed was tried and finished", deadline) { rig.usdSeedFinished() }
                val state = rig.requestState()
                assertEquals("$label: no entry", emptyMap<com.jay.fxi.data.graph.GraphKey, Any>(), state.entries)
                assertEquals("$label: no protected slot", emptyMap<Any, Any>(),
                    rig.onMain { (privateField(rig.assembly.coordinator, "protectedSlots") as Map<*, *>).toMap() })
                assertEquals("$label: the screen waits for the network", GraphV2Content.LOADING, rig.onMain { holder.currentState().content })
                assertEquals("$label: still nothing on disk", emptyList<String>(), rig.writtenFiles())
                holdTab.countDown()
                awaitTrue("$label: the network answer applied", deadline) { rig.usdApplied() && tabs().all { it.bodyEnd != null } }
                awaitTrue("$label: the server and the recorder agree", deadline) {
                    synchronized(received) { received.toList() }.sorted() == sends.all().map { it.key }.sorted()
                }
                quiet(label, 1_000)
            }
        } finally {
            holdTab.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 30_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-S02c: a new user epoch never restores the previous epoch's files. The 1d answer is applied and written (writes
     * drained, its files recorded). A real StableInactive answer ends the user and a FORCE_PREMIUM answer re-grants on a new
     * user epoch; the new scope's graph answers are held. Once the new context is consumed and its seed tried and finished: the
     * previous epoch's files are still on disk unchanged (a precondition), yet the coordinator has no entry and no slot.
     */
    @Test
    fun `RT01-S02c a new user epoch does not restore the previous epoch's files still on disk`() = runBlocking {
        val label = "RT01-S02c"
        val prepared = clearOfBoundary(50_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val holdNew = java.util.concurrent.CountDownLatch(1)
        val newScope = java.util.concurrent.atomic.AtomicBoolean(false)
        onCatalog = {
            if (newScope.get()) holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS)
            ok(fixture("catalog-krx-hidden.json"))
        }
        onTab = {
            if (newScope.get()) holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS)
            ok(fixture("usd-1d-krx-hidden.json"))
        }
        val rig = Rig()
        try {
            rig.row {
                rig.coldStartRoute(deadline)
                rig.awaitTopics(deadline)
                awaitTrue("$label: catalog and the usd tab applied", deadline) {
                    rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null }
                }
                rig.awaitWritesDrained(label, deadline)
                val f1 = checkNotNull(rig.bridge())
                val filesBefore = rig.diskFiles()
                val epochDir = diskHex(checkNotNull(f1.userAccessEpoch))
                assertTrue("$label: premise: the 1d answer was written under the first user epoch ($filesBefore)",
                    filesBefore.keys.any { it.contains("/$epochDir/") && it.endsWith("1d.json") })
                quiet("$label settle", 3_500)
                val churnAt = nowMillis()
                newScope.set(true)
                entitlements += ok("""{"krx_visible":false,"premium_active":false,"premium_pending":false}""")
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                entitlements += ok(PREMIUM_HIDDEN)
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM) }
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence) { "$label: no re-grant after the user end" }
                assertNotEquals("$label: premise: a new user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                awaitTrue("$label: the new scope's context is consumed", deadline) { rig.assembly.coordinator.state.value.source?.fence == f2 }
                awaitTrue("$label: the new scope's 1d seed was tried and finished", deadline) { rig.usdSeedFinished() }
                // A precondition (the composition's purger defers): the previous epoch's files are still there to be misread.
                assertEquals("$label: premise: the previous epoch's files are on disk unchanged", filesBefore,
                    rig.diskFiles().filterKeys { it in filesBefore.keys })
                val state = rig.requestState()
                assertEquals("$label: no entry in the new scope", emptyMap<com.jay.fxi.data.graph.GraphKey, Any>(), state.entries)
                assertEquals("$label: no protected slot in the new scope", emptyMap<Any, Any>(),
                    rig.onMain { (privateField(rig.assembly.coordinator, "protectedSlots") as Map<*, *>).toMap() })
                holdNew.countDown()
                awaitTrue("$label: the new scope's tab applied", deadline) { rig.usdApplied() && tabs().all { it.bodyEnd != null } }
                settleNewSession(label, churnAt, deadline)
            }
        } finally {
            holdNew.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 3a: RT05-T03a (cut_c01_b3_agreed.r1.md) --------------------------------------------------------------

    /** The recovery events' consumed Connection as (session key, generation), or null; read on Main. */
    private suspend fun Rig.eventsConnection(): Pair<Any?, Long>? = onMain {
        privateField(assembly.events, "connection")?.let { privateField(it, "sessionKey") to (privateField(it, "generation") as Long) }
    }

    /** The foreground the recovery events have consumed, read on Main. */
    private suspend fun Rig.eventsForeground(): Boolean? = onMain { privateField(assembly.events, "foreground") as Boolean? }

    /**
     * RT05-T03(a): a Connection change consumed in the background only moves the baseline. Once the events have consumed the
     * background, the server drops the socket: the session's next Connection (same session key, a higher generation) is
     * consumed as the baseline and issues nothing. Back in the foreground within 60 s and in the same 600 s bucket, the return
     * issues nothing either. A foreground drop then brings a Connection consumed past the cooldown (no resync yet), which
     * issues exactly one RECONNECT.
     */
    @Test
    fun `RT05-T03a a connection change in the background only moves the baseline and the next foreground drop reconnects once`() = runBlocking {
        val label = "RT05-T03a"
        val prepared = clearOfBoundary(60_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 50_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.coldStart(deadline)
            rig.awaitTopics(deadline)
            awaitTrue("$label: the tab applied", deadline) { rig.usdApplied() && tabs().all { it.bodyEnd != null } }
            awaitTrue("$label: the first Connection is the baseline", deadline) { rig.eventsConnection() != null }
            val baseline = checkNotNull(rig.eventsConnection())
            assertEquals("$label: premise: the baseline issued nothing", 0L, rig.triggers())
            val bucket = System.currentTimeMillis() / 600_000
            rig.foreground.value = false
            awaitTrue("$label: the events consumed the background", deadline) { rig.eventsForeground() == false }
            openSockets.last().close(1011, "server restart")
            awaitTrue("$label: the background Connection is consumed as the baseline", deadline) {
                rig.eventsConnection()?.let { it.first == baseline.first && it.second > baseline.second } == true
            }
            val background = checkNotNull(rig.eventsConnection())
            assertEquals("$label: it is the live permit's Connection", background.second,
                rig.permitSlot?.get()?.invoke()?.connectionGeneration)
            assertEquals("$label: no trigger in the background", 0L, rig.triggers())
            rig.foreground.value = true
            awaitTrue("$label: the events consumed the return", deadline) { rig.eventsForeground() == true }
            delay(1_000)
            assertEquals("$label: the return within 60 s issues nothing", 0L, rig.triggers())
            assertEquals("$label: premise: the same 600 s bucket", bucket, System.currentTimeMillis() / 600_000)
            assertNull("$label: premise: no resync yet, so the cooldown has passed", rig.onMain { privateField(rig.assembly.events, "lastResync") })
            openSockets.last().close(1011, "server restart")
            awaitTrue("$label: the foreground Connection is consumed", deadline) {
                rig.eventsConnection()?.let { it.first == baseline.first && it.second > background.second } == true
            }
            awaitTrue("$label: one RECONNECT", deadline) { rig.triggers() == 1L }
            delay(2_000)
            assertEquals("$label: exactly one", 1L, rig.triggers())
            assertEquals("$label: premise: still the same 600 s bucket", bucket, System.currentTimeMillis() / 600_000)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 50_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 3a: RT01-H02 (cut_c01_b3_agreed.r1.md) ----------------------------------------------------------------

    /** The batch 3 route stand-in ([Rig.openRoute]); its fields are read and written on Main. */
    private class RouteStandIn(val mount: GraphScreenMount) {
        var job: Job? = null
        var target: Pair<GraphV2ScreenStateHolder, com.jay.fxi.data.remote.TopicDisplayOwner>? = null

        /** As the route's disposal (call on Main): its effect stops, its target is deactivated, then the mount closes. */
        fun close() {
            job?.cancel()
            target?.first?.onDeactivated()
            target = null
            mount.close()
        }
    }

    /** The wall second the last [sendUsdFrame] was stamped with. */
    @Volatile private var lastFrameSecond = 0L

    /**
     * Sends the usd snapshot with [rate] on the current socket, stamped in a wall second after the previous frame's: the topic
     * merge ignores a quote carrying the same timestamp as the one it holds.
     */
    private suspend fun sendUsdFrame(rate: Double) {
        while (System.currentTimeMillis() / 1000 <= lastFrameSecond) delay(10)
        val second = System.currentTimeMillis() / 1000
        lastFrameSecond = second
        openSockets.last().send(usdSnapshotAt(rate, second))
    }

    /** The holder's pending live publication, read on Main. */
    private fun GraphV2ScreenStateHolder.livePublishJob(): Job? = privateField(this, "livePublishJob") as Job?

    /** kb.usd's last real observation on the holder's last published chart (its state, not a new render), read on Main. */
    private fun GraphV2ScreenStateHolder.chartKb(): Double? =
        state.value.chart?.prepared?.bySeries?.get("kb.usd")?.lastObservation?.rate

    /** The recorder's kb.usd tip, read on Main. */
    private fun Rig.kbTipNow(): Double? =
        assembly.recorder.state.value.series.entries.firstOrNull { it.key.seriesId == "kb.usd" }?.value?.data?.app?.tip?.rate

    /** Approves, starts the owner and opens the batch 3 route; returns it with its usd holder once targeted and shown. */
    private suspend fun Rig.coldStartRoute(deadline: Long): Pair<RouteStandIn, GraphV2ScreenStateHolder> {
        approve()
        startOwner()
        val route = openRoute(deadline)
        awaitTrue("the route targets the usd holder", deadline) {
            onMain { route.target?.first?.let { it === mount?.holders?.value?.get("usd") } == true }
        }
        val holder = onMain { checkNotNull(route.target).first }
        showSurface(holder, deadline)
        return route to holder
    }

    /**
     * Waits for a publication the row did not cause — a 30 s freshness tick: a new right edge with the recorder's tip unchanged,
     * no publication pending and no graph request in flight — so the next tick is about 30 s away.
     */
    private suspend fun Rig.awaitFreshnessTick(holder: GraphV2ScreenStateHolder, label: String, deadline: Long) {
        val (edge, tip) = onMain { holder.state.value.chart?.rightEdgeNow to kbTipNow() }
        awaitTrue("$label: a freshness tick publishes", deadline) {
            onMain { holder.state.value.chart?.rightEdgeNow != edge && kbTipNow() == tip && holder.livePublishJob() == null } &&
                graph().all { it.bodyEnd != null }
        }
    }

    /** Waits until the screen is ready to take a live value: READY, no publication pending, no graph request in flight. */
    private suspend fun Rig.awaitReadyToSend(holder: GraphV2ScreenStateHolder, label: String, deadline: Long) =
        awaitTrue("$label: ready, nothing pending, no graph request in flight", deadline) {
            onMain { holder.state.value.content == GraphV2Content.READY && holder.livePublishJob() == null } &&
                graph().all { it.bodyEnd != null }
        }

    /**
     * RT01-H02's live screen through the batch 3 route: a REST delivery creates kb.usd in the recorder; on an acknowledged
     * socket a warm-up WS value (1395) is published; then a freshness tick is seen, so the next one is far away.
     */
    private suspend fun Rig.prepareLive(label: String, deadline: Long): Pair<RouteStandIn, GraphV2ScreenStateHolder> {
        usdSnapshotLive = true
        val (route, holder) = coldStartRoute(deadline)
        awaitTrue("$label: catalog and the usd tab applied", deadline) { catalogAdopted() && usdApplied() && tabs().all { it.bodyEnd != null } }
        awaitTrue("$label: the REST delivery created kb.usd", deadline) { hasKbSeries() }
        usdSnapshotLive = false
        awaitTopics(deadline)
        awaitUsdAcknowledged(label, 0, deadline)
        quiet("$label settle", 3_500)
        sendUsdFrame(1395.0)
        awaitTrue("$label: the warm-up value is published", deadline) {
            onMain { holder.chartKb() == 1395.0 && holder.livePublishJob() == null }
        }
        awaitFreshnessTick(holder, label, deadline)
        return route to holder
    }

    /**
     * Sends a value on the current socket and, in the Main step that first sees the recorder take it with its publication
     * scheduled, checks it is not shown yet and runs [then]; returns the pending publication and the value. If a stalled poll
     * finds the value already published (its wait missed), it tries again with [rate] + 0.25 and + 0.5, logging the miss.
     */
    private suspend fun Rig.sendAndCatchWait(
        holder: GraphV2ScreenStateHolder, rate: Double, label: String, deadline: Long, then: () -> Unit = {}
    ): Pair<Job, Double> {
        repeat(3) { attempt ->
            val value = rate + 0.25 * attempt
            var caught: Job? = null
            sendUsdFrame(value)
            awaitTrue("$label: the recorder takes $value with its publication scheduled", deadline) {
                onMain {
                    val job = holder.livePublishJob()
                    when {
                        kbTipNow() == value && job != null -> {
                            check(holder.chartKb() != value) { "$label: $value was published before its wait ended" }
                            then()
                            caught = job
                            true
                        }
                        kbTipNow() == value && job == null && holder.chartKb() == value -> true
                        else -> false
                    }
                }
            }
            caught?.let { return it to value }
            println("CUT-C01 $label: the wait of $value was missed (attempt ${attempt + 1})")
        }
        fail("$label: three waits missed in a row")
        error("unreachable")
    }

    /**
     * RT01-H02(a): a live value waits for its scheduled publication. Ready, visible, nothing pending and no graph request in
     * flight, a WS value is taken by the recorder and its publication is scheduled; until that wait ends the value is not
     * shown, and once it ends it is (sample times are logged as auxiliary evidence, every 20 ms). A next value whose wait is
     * pending is hidden in the same Main step: the wait is cancelled, the value is not shown while hidden, and shown again it
     * is published at once.
     */
    @Test
    fun `RT01-H02a a live value is published when its wait ends and hiding inside the wait cancels it`() = runBlocking {
        val label = "RT01-H02a"
        val prepared = clearOfBoundary(80_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 70_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            val (_, holder) = rig.prepareLive(label, deadline)
            rig.awaitReadyToSend(holder, label, deadline)
            val sentAt = nowMillis()
            val (wait, first) = rig.sendAndCatchWait(holder, 1401.0, label, deadline)
            val scheduledAt = nowMillis()
            while (rig.onMain {
                    if (!wait.isCompleted) check(holder.chartKb() != first) { "$label: $first was published during its wait" }
                    !wait.isCompleted
                }) delay(20)
            val endedAt = nowMillis()
            assertFalse("$label: the wait ran to its end", wait.isCancelled)
            assertEquals("$label: once the wait ended, $first is published", first, rig.onMain { holder.chartKb() })
            println("CUT-C01 $label: sent $sentAt, schedule seen $scheduledAt, end seen $endedAt (20 ms samples)")
            rig.awaitReadyToSend(holder, label, deadline)
            var hiddenAt = 0L
            val (hidden, second) = rig.sendAndCatchWait(holder, 1402.0, label, deadline) {
                holder.setSurfaceVisible(checkNotNull(holder.currentState().inlineToken), false)
                hiddenAt = nowMillis()
            }
            assertTrue("$label: the hidden wait is cancelled", hidden.isCancelled)
            val hiddenUntil = nowMillis() + 600
            while (nowMillis() < hiddenUntil) {
                assertTrue("$label: hidden, $second is not shown and nothing is pending",
                    rig.onMain { holder.chartKb() != second && holder.livePublishJob() == null })
                delay(20)
            }
            assertTrue("$label: the hidden wait has completed", hidden.isCompleted)
            rig.onMain { holder.setSurfaceVisible(checkNotNull(holder.currentState().inlineToken), true) }
            assertEquals("$label: shown again, $second is published at once", second, rig.onMain { holder.chartKb() })
            println("CUT-C01 $label: hidden at $hiddenAt")
            quiet(label, 1_000)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 70_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-H02(b): the route ends inside a pending wait. In the Main step that sees a WS value's publication pending, the route
     * stand-in ends (its target deactivated, the mount closed): the wait is cancelled and completes, so the value is never
     * published; the old token's period selection and series toggle send nothing and change nothing (refused because the
     * screen has no context); the recorder takes the next value.
     */
    @Test
    fun `RT01-H02b closing the route inside a pending wait cancels it and the old token's actions do nothing`() = runBlocking {
        val label = "RT01-H02b"
        val prepared = clearOfBoundary(80_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 70_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            val (route, holder) = rig.prepareLive(label, deadline)
            rig.awaitReadyToSend(holder, label, deadline)
            val token = checkNotNull(rig.onMain { holder.currentState().inlineToken })
            val (wait, _) = rig.sendAndCatchWait(holder, 1403.0, label, deadline) { route.close() }
            assertTrue("$label: the pending wait is cancelled", wait.isCancelled)
            delay(600)
            assertTrue("$label: and has completed", wait.isCompleted)
            val closed = rig.onMain { holder.currentState() }
            val tabsBefore = tabs().size
            rig.onMain {
                holder.selectPeriod(token, GraphPeriod.THREE_MONTHS)
                holder.toggleSeries(token, "kb.usd")
            }
            delay(1_000)
            assertEquals("$label: the old token's selection sends nothing", 0, tabs().drop(tabsBefore).count { it.param("period") == "3m" })
            assertEquals("$label: and changes nothing", closed, rig.onMain { holder.currentState() })
            sendUsdFrame(1404.0)
            awaitTrue("$label: the recorder takes the next value", deadline) { rig.onMain { rig.kbTipNow() == 1404.0 } }
            quiet(label, 1_000)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 70_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-H02(c): a wait pending when the grant changes. A WS value's publication is pending; Main is held at once. While it
     * is held the wait's timer fires (its resume queued behind the hold), a probe is queued right after it, and then a refused
     * subscription makes the issuer re-approve on a new grant (same identity and user epoch) and the holder's display moves to
     * it. Released, the old wait runs first, after the transition was published, and the probe right after it sees that it
     * published nothing: no state shows the value, the state is not the old token's and no live publication is kept. The holder
     * then runs under the new owner, and the old token's period selection and series toggle send nothing and change neither
     * the period nor the selection.
     */
    @Test
    fun `RT01-H02c a wait pending across a grant change publishes nothing under the old token and its actions do nothing`() = runBlocking {
        val label = "RT01-H02c"
        val prepared = clearOfBoundary(90_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 80_000
        entitlements += unauthorized()
        val rig = Rig()
        val restorer = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                if (!rig.issuerIdentityReadable && rig.coordinator.recheckDiagnostics().owedIntent != null) rig.issuerIdentityReadable = true
                delay(20)
            }
        }
        try {
            rig.row {
                val (_, holder) = rig.prepareLive(label, deadline)
                rig.awaitReadyToSend(holder, label, deadline)
                val oldToken = checkNotNull(rig.onMain { holder.currentState().inlineToken })
                val f1 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                // The display the holder was built with.
                val display = checkNotNull(rig.runtimeDisplay)
                // The warm-up WS value after the REST delivery left a closed demand; the reconnect's recovery round meets it whole.
                onTab = { ok(usdTab(closedStarts())) }
                refuseUsd = {
                    refuseUsd = null
                    rig.issuerIdentityReadable = false
                }
                val (wait, value) = rig.sendAndCatchWait(holder, 1405.0, label, deadline)
                val caughtAt = nowMillis()
                val hold = holdMain()
                val reapprovalAt = nowMillis()
                // Read in the Main step right after the old wait's: the passive published state and the live publication.
                var probe: List<Any?>? = null
                holding(hold) {
                    // Once the wait's timer has fired its resume is queued behind the hold; a probe queued now runs right after it,
                    // before anything the grant change queues.
                    while (nowMillis() - caughtAt < 400) delay(10)
                    mainExecutor.execute {
                        probe = listOf(holder.state.value.inlineToken, holder.chartKb(), privateField(holder, "livePublication"),
                            wait.isCompleted, wait.isCancelled)
                    }
                    openSockets.last().close(1011, "server restart")
                    awaitTrue("$label: a re-approval on a new grant while Main is held", deadline) {
                        rig.coordinator.topicGrantResult().fence?.grant?.let { it != f1.grant } == true
                    }
                    awaitTrue("$label: the screen's display moved to the new grant while Main is held", deadline) {
                        display.value.owner?.let { it.grantEpoch != oldToken.owner.grantEpoch } == true
                    }
                    assertTrue("$label: premise: the wait came due during the hold (${nowMillis() - caughtAt} ms)", nowMillis() - caughtAt > 400)
                    assertFalse("$label: premise: it has not run yet", wait.isCompleted)
                }
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("$label: premise: the same identity", f1.identity, f2.identity)
                assertEquals("$label: premise: the same user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                awaitTrue("$label: the probe ran", deadline) { probe != null }
                val seen = checkNotNull(probe)
                println("CUT-C01 $label: probe after the old wait: token ${seen[0]}, kb ${seen[1]}, live ${seen[2]}, completed ${seen[3]}, cancelled ${seen[4]}")
                assertEquals("$label: the old wait ran before the probe, not cancelled", true to false, seen[3] to seen[4])
                assertFalse("$label: it published nothing: no state shows $value", seen[1] == value)
                assertNotEquals("$label: and the published state is not the old token's", oldToken, seen[0])
                assertNull("$label: and no live publication is kept", seen[2])
                awaitTrue("$label: the holder runs under the new owner, ready with toggles", deadline) {
                    rig.onMain { holder.currentState().let { it.inlineToken?.fence == f2 && it.content == GraphV2Content.READY && it.toggles.isNotEmpty() } }
                }
                val periodBefore = rig.onMain { holder.currentState().activePeriod }
                val selectionBefore = rig.onMain { holder.currentState().toggles.map { it.seriesId to it.selected } }
                val tabsBefore = tabs().size
                rig.onMain {
                    holder.selectPeriod(oldToken, GraphPeriod.THREE_MONTHS)
                    holder.toggleSeries(oldToken, "kb.usd")
                }
                delay(1_000)
                assertEquals("$label: the old token's selection sends nothing", 0, tabs().drop(tabsBefore).count { it.param("period") == "3m" })
                assertEquals("$label: and changes not the period", periodBefore, rig.onMain { holder.currentState().activePeriod })
                assertEquals("$label: nor the selection", selectionBefore, rig.onMain { holder.currentState().toggles.map { it.seriesId to it.selected } })
                awaitTrue("$label: the recovery demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
                settleNewSession(label, reapprovalAt, deadline)
            }
        } finally {
            refuseUsd = null
            restorer.cancel()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 80_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-H02(d): inline and fullscreen share one owner. The fullscreen token shares the inline token's owner, fence, lifetime
     * and binding; with inline hidden and fullscreen shown, a WS value's wait runs and the value is published. After the exit,
     * the old fullscreen token is refused: it shows no surface, reopens nothing and selects nothing.
     */
    @Test
    fun `RT01-H02d the fullscreen token shares the owner and publishes while inline is hidden and is refused after the exit`() = runBlocking {
        val label = "RT01-H02d"
        val prepared = clearOfBoundary(80_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 70_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            val (_, holder) = rig.prepareLive(label, deadline)
            val inline = checkNotNull(rig.onMain { holder.currentState().inlineToken })
            rig.onMain { holder.enterFullscreen(inline) }
            val full = checkNotNull(rig.onMain { holder.currentState().fullscreenToken }) { "$label: no fullscreen token" }
            assertEquals("$label: the fullscreen surface", com.jay.fxi.ui.premium.graph.GraphV2Surface.FULLSCREEN, full.surface)
            assertEquals("$label: the same owner", inline.owner, full.owner)
            assertEquals("$label: the same fence", inline.fence, full.fence)
            assertEquals("$label: the same lifetime", inline.lifetime, full.lifetime)
            assertEquals("$label: the same binding", inline.binding, full.binding)
            rig.onMain {
                holder.setSurfaceVisible(full, true)
                holder.setSurfaceVisible(checkNotNull(holder.currentState().inlineToken), false)
            }
            assertEquals("$label: premise: shown through fullscreen only", false to true,
                rig.onMain { (privateField(holder, "inlineVisible") as Boolean) to (privateField(holder, "fullscreenVisible") as Boolean) })
            rig.awaitReadyToSend(holder, label, deadline)
            val (wait, value) = rig.sendAndCatchWait(holder, 1406.0, label, deadline)
            awaitTrue("$label: the wait ends", deadline) { wait.isCompleted }
            assertFalse("$label: the wait ran to its end", wait.isCancelled)
            assertEquals("$label: $value is published through fullscreen", value, rig.onMain { holder.chartKb() })
            rig.onMain { holder.exitFullscreen(full) }
            val exited = rig.onMain { holder.currentState() }
            assertFalse("$label: the fullscreen is closed", exited.fullscreenOpen)
            assertNull("$label: with no fullscreen token", exited.fullscreenToken)
            val tabsBefore = tabs().size
            rig.onMain {
                holder.setSurfaceVisible(full, true)
                holder.enterFullscreen(full)
                holder.selectPeriod(full, GraphPeriod.THREE_MONTHS)
            }
            delay(1_000)
            assertEquals("$label: the old fullscreen token selects nothing", 0, tabs().drop(tabsBefore).count { it.param("period") == "3m" })
            assertFalse("$label: reopens nothing", rig.onMain { holder.currentState().fullscreenOpen })
            assertFalse("$label: and shows no surface", rig.onMain { privateField(holder, "fullscreenVisible") as Boolean })
            quiet(label, 1_000)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 70_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 3b: RT01-H01, RT05-T03(b)(c), RT01-S02(b)(d), RT05-T01a (cut_c01_b3b_agreed.r1.md) -----------------------

    /**
     * Calls the selection callback the process consumer is bound to — the runtime's selectTab (TopicRuntimeOwner.kt:142,
     * PremiumTopicConsumer.kt:79) — on Main. The owner keeps no runtime field.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun Rig.consumerSelect(identity: AuthIdentityFence, tab: FreeTab) = onMain {
        (privateField(owner.consumer, "selectTab") as (AuthIdentityFence, FreeTab) -> Unit)(identity, tab)
    }

    /** The focus a mounted holder reads (the focus provider's), read on Main. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun Rig.focusNow(): com.jay.fxi.data.remote.OwnedTopicFocus? = onMain {
        (privateField(checkNotNull(checkNotNull(mount).holders.value["usd"]), "focus") as StateFlow<com.jay.fxi.data.remote.OwnedTopicFocus?>).value
    }

    /** The batch 3 route's current target tab (null when none), read on Main. */
    private suspend fun Rig.routeTargetTab(route: RouteStandIn): String? = onMain {
        val target = route.target?.first ?: return@onMain null
        checkNotNull(mount).holders.value.entries.firstOrNull { it.value === target }?.key
    }

    /**
     * RT01-H01(a): a failed restore falls back to USD. The stored last tab's read throws; the focus becomes USD for the live
     * identity, the route's target is the usd holder and only usd's graph tab goes; the jpy and eur holders stay inactive.
     */
    @Test
    fun `RT01-H01a a failed restore falls back to usd and only the usd holder is active`() = runBlocking {
        val label = "RT01-H01a"
        val prepared = clearOfBoundary(40_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 30_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.tabs.script += { throw IllegalStateException("the stored tab cannot be read") }
        rig.row {
            val (route, holder) = rig.coldStartRoute(deadline)
            awaitTrue("$label: the usd tab applied", deadline) { rig.usdApplied() && tabs().all { it.bodyEnd != null } }
            assertEquals("$label: premise: the restore was tried and failed", listOf("enter", "throw"), synchronized(rig.tabs.log) { rig.tabs.log.toList() })
            val identity = checkNotNull(rig.provider.currentIdentityFence())
            assertEquals("$label: the focus falls back to usd", com.jay.fxi.data.remote.OwnedTopicFocus(identity, FreeTab.USD), rig.focusNow())
            assertEquals("$label: the route targets the usd holder", "usd", rig.routeTargetTab(route))
            assertEquals("$label: only usd's graph tab went", setOf("usd"), tabs().map { it.param("tab") }.toSet())
            assertEquals("$label: one usd 1d tab", listOf("usd" to "1d"), tabs().map { it.param("tab") to it.param("period") })
            assertEquals("$label: the jpy and eur holders are inactive", listOf(GraphV2Content.INACTIVE, GraphV2Content.INACTIVE),
                rig.onMain { listOf("jpy", "eur").map { checkNotNull(rig.mount).holders.value.getValue(it).currentState().content } })
            assertEquals("$label: premise: the usd holder is ready", GraphV2Content.READY, rig.onMain { holder.currentState().content })
            quiet(label, 1_000)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 30_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-H01(b): a selection wins over a pending restore. The first stored-tab read is held (suspended). With the focus null
     * and no screen owner, the production runtime selection — the callback the consumer is bound to — selects JPY for the live
     * identity: the focus becomes JPY and the route targets the jpy holder. The restore is then released with USD: the focus
     * stays JPY and only jpy's graph tab goes; asked directly, the usd holder gets no context, token or activation (INACTIVE) and
     * sends nothing. (Not evidence that the consumer's UI can select before a restore.)
     */
    @Test
    fun `RT01-H01b a selection made while the restore is pending wins over it`() = runBlocking {
        val label = "RT01-H01b"
        val prepared = clearOfBoundary(40_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 30_000
        entitlements += unauthorized()
        val rig = Rig()
        onTab = { request ->
            if (request.requestUrl!!.queryParameter("tab") == "jpy") ok(jpyTab()) else ok(fixture("usd-1d-krx-hidden.json"))
        }
        val restore = kotlinx.coroutines.CompletableDeferred<FreeTab>()
        rig.tabs.script += { restore.await() }
        try {
            rig.row {
                rig.approve()
                rig.startOwner()
                val route = rig.openRoute(deadline)
                awaitTrue("$label: the restore is entered and held", deadline) { synchronized(rig.tabs.log) { rig.tabs.log.toList() } == listOf("enter") }
                assertNull("$label: premise: no focus yet", rig.focusNow())
                assertNull("$label: premise: no screen owner yet", rig.onMain { rig.owner.consumer.currentState().ui.owner })
                val identity = checkNotNull(rig.provider.currentIdentityFence())
                rig.consumerSelect(identity, FreeTab.JPY)
                awaitTrue("$label: the focus is jpy and the route targets the jpy holder", deadline) {
                    rig.focusNow() == com.jay.fxi.data.remote.OwnedTopicFocus(identity, FreeTab.JPY) && rig.routeTargetTab(route) == "jpy"
                }
                restore.complete(FreeTab.USD)
                awaitTrue("$label: the restore returned", deadline) { synchronized(rig.tabs.log) { rig.tabs.log.toList() } == listOf("enter", "return USD") }
                delay(500)
                assertEquals("$label: the focus stays jpy", com.jay.fxi.data.remote.OwnedTopicFocus(identity, FreeTab.JPY), rig.focusNow())
                assertEquals("$label: the route still targets the jpy holder", "jpy", rig.routeTargetTab(route))
                awaitTrue("$label: the jpy tab applied", deadline) {
                    rig.onMain { rig.assembly.coordinator.state.value.entries.keys.any { it.tab == "jpy" } } && tabs().all { it.bodyEnd != null }
                }
                val usd = rig.onMain { checkNotNull(rig.mount).holders.value.getValue("usd") }
                val screenOwner = checkNotNull(rig.onMain { rig.owner.consumer.currentState().ui.owner })
                val graphBefore = graph().size
                val asked = rig.onMain {
                    usd.onActivated(screenOwner)
                    val s = usd.currentState()
                    listOf(privateField(usd, "context"), privateField(usd, "activation"), s.inlineToken, s.content)
                }
                assertEquals("$label: asked directly, the usd holder gets no context, activation or token and stays inactive",
                    listOf(null, null, null, GraphV2Content.INACTIVE), asked)
                delay(1_000)
                assertEquals("$label: and sends nothing", 0, graph().drop(graphBefore).size)
                assertEquals("$label: a second later the usd holder is still inactive", GraphV2Content.INACTIVE, rig.onMain { usd.currentState().content })
                rig.onMain { usd.onDeactivated() }
                assertEquals("$label: only jpy's graph tab went", setOf("jpy"), tabs().map { it.param("tab") }.toSet())
                assertEquals("$label: the focus is still jpy at the end", com.jay.fxi.data.remote.OwnedTopicFocus(identity, FreeTab.JPY), rig.focusNow())
                quiet(label, 1_000)
            }
        } finally {
            restore.complete(FreeTab.USD)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 30_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-H01(c): a restore of an older generation is refused. The first restore (generation 1) is held; the same user signs in
     * again (a new generation, re-approved on the same user epoch) and the second restore returns USD at once: the focus is
     * (generation 2, USD). Then the first restore returns JPY: the focus stays (generation 2, USD), the route never targets the
     * jpy holder and no jpy graph tab goes.
     */
    @Test
    fun `RT01-H01c a restore of an older generation is refused after the same user's new generation`() = runBlocking {
        val label = "RT01-H01c"
        val prepared = clearOfBoundary(50_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val rig = Rig()
        val first = kotlinx.coroutines.CompletableDeferred<FreeTab>()
        rig.tabs.script += { first.await() }
        rig.tabs.script += { FreeTab.USD }
        val targets = Collections.synchronizedList(mutableListOf<String?>())
        var watch: Job? = null
        try {
            rig.row {
                rig.approve()
                rig.startOwner()
                val route = rig.openRoute(deadline)
                watch = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    while (true) { targets += rig.routeTargetTab(route); delay(10) }
                }
                awaitTrue("$label: the first restore is entered and held", deadline) { synchronized(rig.tabs.log) { rig.tabs.log.toList() } == listOf("enter") }
                val generation1 = checkNotNull(rig.provider.currentIdentityFence())
                awaitSendsQuietly(rig, deadline)
                val approval = CoroutineScope(rig.issuerJob + Dispatchers.Default).launch {
                    val next = rig.firebase.nextGeneration()
                    rig.coordinator.onIdentityChanged(next)
                    rig.coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
                }
                approval.join()
                val generation2 = checkNotNull(rig.provider.currentIdentityFence())
                assertNotEquals("$label: premise: a new generation", generation1, generation2)
                assertEquals("$label: premise: of the same user", generation1.uid, generation2.uid)
                awaitTrue("$label: the second restore focuses usd for the new generation", deadline) {
                    rig.focusNow() == com.jay.fxi.data.remote.OwnedTopicFocus(generation2, FreeTab.USD) && rig.routeTargetTab(route) == "usd"
                }
                first.complete(FreeTab.JPY)
                awaitTrue("$label: the first restore returned", deadline) { synchronized(rig.tabs.log) { rig.tabs.log.toList() }.contains("return JPY") }
                delay(1_000)
                assertEquals("$label: the focus stays the new generation's usd", com.jay.fxi.data.remote.OwnedTopicFocus(generation2, FreeTab.USD), rig.focusNow())
                watch?.cancel()
                assertFalse("$label: the route never targeted the jpy holder", synchronized(targets) { targets.toList() }.contains("jpy"))
                assertEquals("$label: no jpy graph tab", 0, tabs().count { it.param("tab") == "jpy" })
                awaitTrue("$label: the usd tab applied", deadline) { rig.usdApplied() && graph().all { it.bodyEnd != null } }
                quiet(label, 1_000)
            }
        } finally {
            watch?.cancel()
            first.complete(FreeTab.JPY)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /** Lets every send end and settle for 3.5 s before an identity change (as RT01-A05 does, for the api budget). */
    private suspend fun awaitSendsQuietly(rig: Rig, deadline: Long) {
        awaitTrue("every send ended", deadline) { sends.all().all { it.bodyEnd != null } }
        delay(3_500)
    }

    /** The live recovery permit, read off Main (the session publishes it volatile). */
    private fun Rig.permitNow(): com.jay.fxi.data.remote.TopicGraphRecoveryPermit? = permitSlot?.get()?.invoke()

    /**
     * RT05-T03(b): a first connect that throws. The socket factory throws on the first attempt (its call number is the
     * attempt's generation). With the runtime held right after it — so no retry can publish — the events are notified on Main
     * with the real permit supplier, which then names a fence but no Connection: no baseline, no trigger. (Consuming such a
     * permit leaves no state, GraphRecoveryEvents.kt:107, so the row drives the consumption instead of waiting for it.) The
     * first real Connection's generation is above the failed attempt's (no permit ever names the failed attempt as a
     * Connection); it becomes the baseline and issues nothing. In the same 600 s bucket and the foreground, a later drop
     * issues exactly one RECONNECT.
     */
    @Test
    fun `RT05-T03b a first connect that throws leaves no connection and the first real one is the baseline`() = runBlocking {
        val label = "RT05-T03b"
        val prepared = clearOfBoundary(50_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.throwNextConnect = true
        val seenConnections = Collections.synchronizedSet(mutableSetOf<Long>())
        var sampler: Job? = null
        try {
            rig.row {
                sampler = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    while (true) { rig.permitNow()?.connectionGeneration?.let { seenConnections += it }; delay(5) }
                }
                rig.coldStartRoute(deadline)
                awaitTrue("$label: the first connect threw", deadline) { rig.thrownConnect != null }
                val failed = checkNotNull(rig.thrownConnect)
                val consumed = holding(rig.holdRuntime()) {
                    rig.onMain {
                        val published = rig.permitNow()
                        rig.assembly.events.onPermitChanged()
                        listOf(published?.fence != null, published?.connectionGeneration, privateField(rig.assembly.events, "connection"),
                            rig.triggersOnMain())
                    }
                }
                assertEquals("$label: notified with a permit naming a fence and no Connection, the events keep no baseline and issue nothing",
                    listOf(true, null, null, 0L), consumed)
                awaitTrue("$label: the first real Connection is consumed as the baseline", deadline) { rig.eventsConnection() != null }
                val baseline = checkNotNull(rig.eventsConnection())
                assertTrue("$label: its generation is above the failed attempt's (${baseline.second} > $failed)", baseline.second > failed)
                assertEquals("$label: premise: the failed attempt was the first factory call and the baseline is the next one",
                    listOf(1L, 2L), listOf(failed, baseline.second))
                assertEquals("$label: it is the live permit's", baseline.second, rig.permitNow()?.connectionGeneration)
                assertEquals("$label: the baseline issues nothing", 0L, rig.triggers())
                rig.awaitTopics(deadline)
                awaitTrue("$label: the tab applied", deadline) { rig.usdApplied() && tabs().all { it.bodyEnd != null } }
                val bucket = System.currentTimeMillis() / 600_000
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: one RECONNECT", deadline) { rig.triggers() == 1L }
                delay(2_000)
                assertEquals("$label: exactly one", 1L, rig.triggers())
                assertEquals("$label: premise: the same 600 s bucket", bucket, System.currentTimeMillis() / 600_000)
                assertEquals("$label: premise: in the foreground", true, rig.eventsForeground())
                sampler?.cancel()
                assertFalse("$label: no permit ever named the failed attempt as a Connection (${synchronized(seenConnections) { seenConnections.toList() }})",
                    synchronized(seenConnections) { seenConnections.contains(failed) })
            }
        } finally {
            sampler?.cancel()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /** The trigger sequence, read on Main (from inside an [Rig.onMain] block). */
    private fun Rig.triggersOnMain(): Long {
        val e = assembly.events
        return e.javaClass.getDeclaredField("sequence").apply { isAccessible = true }.get(e) as Long
    }

    /**
     * RT05-T03(c): a delayed old permit revision is read as the latest permit. In the foreground the first real Connection is
     * the baseline (no resync yet, no trigger); notifying the events again with the same permit changes nothing. With Main held,
     * the server drops the socket twice in turn, each reconnect seen on the runtime's permit (same session key, a higher
     * generation) before the next drop. Released, the events' Connection is the latest recorded one and exactly one RECONNECT is
     * issued (same 600 s bucket, foreground, cooldown passed); a further notification issues nothing more.
     */
    @Test
    fun `RT05-T03c revisions delayed behind Main are read as the latest permit and resync once`() = runBlocking {
        val label = "RT05-T03c"
        val prepared = clearOfBoundary(50_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.coldStartRoute(deadline)
            rig.awaitTopics(deadline)
            awaitTrue("$label: the tab applied", deadline) { rig.usdApplied() && tabs().all { it.bodyEnd != null } }
            awaitTrue("$label: the first real Connection is the baseline", deadline) { rig.eventsConnection() != null }
            val baseline = checkNotNull(rig.eventsConnection())
            assertNull("$label: premise: no resync yet", rig.onMain { privateField(rig.assembly.events, "lastResync") })
            assertEquals("$label: premise: no trigger", 0L, rig.triggers())
            rig.onMain { rig.assembly.events.onPermitChanged() }
            assertEquals("$label: a repeated notification keeps the baseline", baseline, rig.eventsConnection())
            assertEquals("$label: and issues nothing", 0L, rig.triggers())
            val bucket = System.currentTimeMillis() / 600_000
            var latest: com.jay.fxi.data.remote.TopicGraphRecoveryPermit? = null
            holding(holdMain()) {
                var previous = baseline.second
                repeat(2) { i ->
                    val sockets = openSockets.size
                    openSockets.last().close(1011, "server restart")
                    awaitTrue("$label: reconnect ${i + 1} is on the runtime's permit while Main is held", deadline) {
                        rig.permitNow()?.let { it.sessionKey == baseline.first && (it.connectionGeneration ?: 0L) > previous } == true
                    }
                    previous = checkNotNull(rig.permitNow()?.connectionGeneration)
                    awaitTrue("$label: the server opened its socket", deadline) { openSockets.size > sockets }
                }
                latest = rig.permitNow()
                assertEquals("$label: premise: no trigger while Main is held", 0L, rig.triggersOffMain())
            }
            val last = checkNotNull(latest)
            awaitTrue("$label: the events' Connection is the latest recorded one", deadline) {
                rig.eventsConnection() == (last.sessionKey to checkNotNull(last.connectionGeneration))
            }
            awaitTrue("$label: one RECONNECT", deadline) { rig.triggers() == 1L }
            delay(1_000)
            assertEquals("$label: exactly one", 1L, rig.triggers())
            assertEquals("$label: premise: the same 600 s bucket", bucket, System.currentTimeMillis() / 600_000)
            assertEquals("$label: premise: in the foreground", true, rig.eventsForeground())
            rig.onMain { rig.assembly.events.onPermitChanged() }
            delay(500)
            assertEquals("$label: a further notification issues nothing more", 1L, rig.triggers())
            rig.awaitTopics(deadline)
            awaitTrue("$label: the topics settle", deadline) { sends.all().all { it.bodyEnd != null } }
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /** The trigger sequence read without Main (a plain field read; only while Main is held, where nothing else writes it). */
    private fun Rig.triggersOffMain(): Long = triggersOnMain()

    /** The coordinator's entry for usd 1d and whether its slot has a KRX component, read on Main. */
    private suspend fun Rig.usd1dEntryView(): Triple<com.jay.fxi.data.graph.GraphEntry?, Boolean, Set<Double>> = onMain {
        val c = assembly.coordinator
        val key = c.state.value.entries.keys.firstOrNull { it.tab == "usd" && it.period.code == "1d" }
        val entry = key?.let { c.state.value.entries[it] }
        val krx = key?.let { slotComponents(it)?.krx } != null
        val values = entry?.tab?.graph?.series.orEmpty().filterNot { it.seriesId.startsWith("krx.") }
            .flatMap { s -> s.points.flatMap { listOfNotNull(it.rate, it.high, it.low) } }.toSet()
        Triple(entry, krx, values)
    }

    /** The same user signs in again (a new generation; the end delivered, then the new grant on the same user epoch). */
    private suspend fun Rig.signInAgain(label: String, deadline: Long): com.jay.fxi.data.remote.TopicSessionFence {
        val epoch = checkNotNull(coordinator.topicGrantResult().fence).userAccessEpoch
        val approval = CoroutineScope(issuerJob + Dispatchers.Default).launch {
            val next = firebase.nextGeneration()
            coordinator.onIdentityChanged(next)
            coordinator.refresh(RefreshIntent.FORCE_PREMIUM)
        }
        approval.join()
        val fence = checkNotNull(coordinator.topicGrantResult().fence)
        assertEquals("$label: premise: the same user epoch", epoch, fence.userAccessEpoch)
        awaitTrue("$label: the new context is consumed", deadline) { assembly.coordinator.state.value.source?.fence == fence }
        return fence
    }

    /**
     * RT01-S02: after a same-user sign-in, KRX is still allowed and the capability epoch — a segment of the KRX file path — is
     * still [epoch], so the restore looks at the KRX file the row prepared. (The rig answers an entitlements call with no
     * scripted response with KRX hidden, so each sign-in scripts a KRX-visible answer.)
     */
    private suspend fun Rig.assertKrxKept(label: String, epoch: Any?) {
        assertTrue("$label: premise: KRX is allowed under the new grant", coordinator.accessSnapshot.facts.capabilityAllowed)
        assertEquals("$label: premise: the same capability epoch (the KRX file path)", epoch, epochStore.load().krxCapabilityEpoch)
    }

    /**
     * RT01-S02(b): a KRX file of another response is not joined (FillEmpty). KRX-visible, response 1 is applied and written and
     * its KRX file kept aside; a same-user sign-in refills from disk and its network answer, response 2 (other values), is
     * applied and written over the same paths. Response 1's KRX file is then put back, so GENERAL and KRX carry different
     * response IDs. A second same-user sign-in, its answers held: once its seed was tried and finished, the entry holds response
     * 2's GENERAL only — no KRX series, no KRX in the slot, no stamp, only response 2's values including one response 1 does
     * not have — and its tab goes, captured under the same KRX epoch. Both sign-ins keep KRX allowed under the same capability
     * epoch, so the paths stay the same. The response ID is the only join clause that fails: the shift changes series values
     * only, so key, server metadata and ordinals match (GraphV2Disk.kt:207-211).
     */
    @Test
    fun `RT01-S02b a KRX file of another response is not joined to the restored general file`() = runBlocking {
        val label = "RT01-S02b"
        val prepared = clearOfBoundary(70_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 60_000
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        val response1 = fixture("usd-1d-krx-visible.json")
        val response2 = shiftedTab(response1, 3.0)
        val phase = AtomicInteger(1)
        val holdThird = java.util.concurrent.CountDownLatch(1)
        onCatalog = {
            if (phase.get() == 3) holdThird.await(30, java.util.concurrent.TimeUnit.SECONDS)
            ok(fixture("catalog-krx-visible.json"))
        }
        onTab = {
            when (phase.get()) {
                1 -> ok(response1)
                2 -> ok(response2)
                else -> { holdThird.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(response2) }
            }
        }
        val rig = Rig()
        try {
            rig.row {
                rig.coldStartRoute(deadline)
                rig.awaitTopics(deadline)
                awaitTrue("$label: response 1 applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
                rig.awaitWritesDrained(label, deadline)
                val krx1 = checkNotNull(rig.writtenFiles().singleOrNull { it.startsWith("krx/") && it.endsWith("1d.json") }) {
                    "$label: premise: one 1d KRX file after response 1 (${rig.writtenFiles()})"
                }
                val kept = File(rig.diskRoot, krx1).readBytes()
                val krxEpoch = rig.epochStore.load().krxCapabilityEpoch
                awaitSendsQuietly(rig, deadline)
                phase.set(2)
                entitlements += ok(premiumKrxVisible)
                rig.signInAgain(label, deadline)
                rig.assertKrxKept(label, krxEpoch)
                val values1 = tabValues(response1).values.flatten().toSet()
                val values2 = tabValues(response2).values.flatten().toSet()
                awaitTrue("$label: response 2 applied (its values only)", deadline) {
                    val values = rig.usd1dEntryView().third
                    values.isNotEmpty() && values.all { it in values2 } && values.any { it !in values1 } && graph().all { it.bodyEnd != null }
                }
                rig.awaitWritesDrained(label, deadline)
                assertFalse("$label: premise: response 2 rewrote the KRX file", File(rig.diskRoot, krx1).readBytes().contentEquals(kept))
                File(rig.diskRoot, krx1).writeBytes(kept)
                awaitSendsQuietly(rig, deadline)
                phase.set(3)
                val tabsBefore = tabs().size
                entitlements += ok(premiumKrxVisible)
                rig.signInAgain(label, deadline)
                rig.assertKrxKept(label, krxEpoch)
                awaitTrue("$label: the seed was tried and finished", deadline) { rig.usdSeedFinished() }
                val (entry, krxInSlot, values) = rig.usd1dEntryView()
                assertNotNull("$label: the general file is restored", entry)
                assertNull("$label: with no stamp", entry?.online200At)
                assertTrue("$label: no KRX series", entry!!.tab.graph.series.none { it.seriesId.startsWith("krx.") })
                assertFalse("$label: no KRX in the slot", krxInSlot)
                assertTrue("$label: response 2's general values only, one of them not response 1's ($values)",
                    values.isNotEmpty() && values.all { it in values2 } && values.any { it !in values1 })
                awaitTrue("$label: its tab goes", deadline) { tabs().size > tabsBefore }
                assertEquals("$label: premise: the held tab is captured under the same KRX epoch", true to krxEpoch, rig.usdCaptureEpoch("1d"))
                holdThird.countDown()
                awaitTrue("$label: settled", deadline) { graph().all { it.bodyEnd != null } && rig.usdApplied() }
                quiet(label, 1_000)
            }
        } finally {
            holdThird.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 60_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT01-S02(d): a KRX write that fails leaves the general file. The disk's `krx` directory is blocked by a regular file;
     * a KRX-visible answer writes its general file and no KRX file. The blocking file is then removed, so the seed finds no KRX
     * file (Absent) as after a failed write, not a read error. A same-user sign-in with KRX still allowed under the same
     * capability epoch, its answers held: once its seed was tried and finished, the entry holds the general part only (no KRX
     * series or slot KRX) with no stamp, and its held tab is captured under the same KRX epoch.
     */
    @Test
    fun `RT01-S02d a failed KRX write leaves the general file to restore alone`() = runBlocking {
        val label = "RT01-S02d"
        val prepared = clearOfBoundary(50_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 40_000
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        val second = java.util.concurrent.atomic.AtomicBoolean(false)
        val holdSecond = java.util.concurrent.CountDownLatch(1)
        onCatalog = {
            if (second.get()) holdSecond.await(30, java.util.concurrent.TimeUnit.SECONDS)
            ok(fixture("catalog-krx-visible.json"))
        }
        onTab = {
            if (second.get()) holdSecond.await(30, java.util.concurrent.TimeUnit.SECONDS)
            ok(fixture("usd-1d-krx-visible.json"))
        }
        val rig = Rig(blockKrx = true)
        try {
            rig.row {
                rig.coldStartRoute(deadline)
                rig.awaitTopics(deadline)
                awaitTrue("$label: the answer applied", deadline) { rig.catalogAdopted() && rig.usdApplied() && tabs().all { it.bodyEnd != null } }
                rig.awaitWritesDrained(label, deadline)
                assertTrue("$label: premise: the general 1d file was written (${rig.writtenFiles()})", rig.writtenFiles().any { it.startsWith("general/") && it.endsWith("1d.json") })
                assertTrue("$label: premise: with KRX visible", rig.onMain {
                    rig.assembly.coordinator.state.value.entries.values.any { e -> e.tab.graph.series.any { it.seriesId.startsWith("krx.") } }
                })
                assertEquals("$label: premise: no KRX file", emptyList<String>(), rig.writtenFiles().filter { it.startsWith("krx/") })
                assertTrue("$label: premise: the blocking file is removed", File(rig.diskRoot, "krx").let { it.isFile && it.delete() })
                val krxEpoch = rig.epochStore.load().krxCapabilityEpoch
                awaitSendsQuietly(rig, deadline)
                second.set(true)
                val tabsBefore = tabs().size
                entitlements += ok(premiumKrxVisible)
                rig.signInAgain(label, deadline)
                rig.assertKrxKept(label, krxEpoch)
                awaitTrue("$label: the seed was tried and finished", deadline) { rig.usdSeedFinished() }
                val (entry, krxInSlot, _) = rig.usd1dEntryView()
                assertNotNull("$label: the general file is restored", entry)
                assertNull("$label: with no stamp", entry?.online200At)
                assertTrue("$label: no KRX series", entry!!.tab.graph.series.none { it.seriesId.startsWith("krx.") })
                assertFalse("$label: no KRX in the slot", krxInSlot)
                awaitTrue("$label: its tab goes", deadline) { tabs().size > tabsBefore }
                assertEquals("$label: premise: the held tab is captured under the same KRX epoch", true to krxEpoch, rig.usdCaptureEpoch("1d"))
                holdSecond.countDown()
                awaitTrue("$label: settled", deadline) { graph().all { it.bodyEnd != null } && rig.usdApplied() }
                quiet(label, 1_000)
            }
        } finally {
            holdSecond.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 40_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT05-T01(a): time alone changes no observation. With preparation and observation inside one 600 s bucket: cold start,
     * catalog and tab applied, a real kb tip from a WS value, topic deliveries acknowledged, graph requests done, the screen
     * READY, visible, nothing pending.
     *
     * Excluded stretch (reported, not counted as time alone): this server never delivers tether, so the first subscription's
     * delivery deadline (45 s, TopicRequestPolicy.kt:91) hands tether to a revalidation (TopicSessionCoordinator.kt:3086) — a
     * tether-only subscribe whose acknowledgement is a continuity input to the recorder (ACK_ACTIVE_SET_CHANGED, :1966-1983) —
     * and that revalidation's own deadline degrades tether (:3096) with a DELIVERY_INTERRUPTED input (:2042-2045). The row waits
     * for the revalidation subscribe and the DEGRADED display, then 2 s.
     *
     * From then, for 65 s sampled every 20 ms on Main: the right edge moves two or three times, 25 to 35 s apart (30 s
     * freshness ticks, no 10 s publication), while the recorder state object, the coordinator's entries instance, the 1d entry
     * object and the kb tip stay the same, tether stays DEGRADED, and no subscription, graph request or other REST send goes.
     * (A tether value from the server would not avoid the stretch: it arms the D14 silence window, whose expiry suspects and
     * revalidates tether the same way, TopicSessionCoordinator.kt:2910-2975.)
     */
    @Test
    fun `RT05-T01a time alone moves the right edge on the freshness tick and changes no observation`() = runBlocking {
        val label = "RT05-T01a"
        val prepared = clearOfBoundary(185_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 175_000
        val bucket = System.currentTimeMillis() / 600_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            val (_, holder) = rig.prepareLive(label, deadline)
            rig.awaitReadyToSend(holder, label, deadline)
            @Suppress("UNCHECKED_CAST")
            val display = rig.onMain { privateField(rig.owner.consumer, "display") } as StateFlow<TopicDisplayState>
            fun tetherState() = display.value.topicState.stateFor(TETHER).deliveryState
            awaitTrue("$label: the excluded stretch: tether's revalidation subscribe went", deadline) {
                synchronized(subscribed) { subscribed.count { it == TETHER } } >= 2
            }
            awaitTrue("$label: the excluded stretch ends with tether DEGRADED", deadline) {
                tetherState() == com.jay.fxi.domain.model.TopicDeliveryState.DEGRADED
            }
            println("CUT-C01 $label: excluded stretch (tether first-delivery watchdog, revalidation, degrade) ended at ${nowMillis() - start} ms")
            delay(2_000)
            fun sample() = listOf(holder.state.value.chart?.rightEdgeNow, rig.assembly.recorder.state.value,
                rig.assembly.coordinator.state.value.entries,
                rig.assembly.coordinator.state.value.entries.values.firstOrNull { it.tab.period == GraphPeriod.ONE_DAY })
            val base = rig.onMain { sample() + rig.kbTipNow() }
            assertNotNull("$label: premise: a 1d entry", base[3])
            assertNotNull("$label: premise: a kb tip", base[4])
            assertNull("$label: premise: no live publication pending", rig.onMain { holder.livePublishJob() })
            val graphBefore = graph().size
            val sendsBefore = sends.all().size
            val subscribedBefore = synchronized(subscribed) { subscribed.size }
            val edges = mutableListOf<Long>()
            var edge = base[0]
            val windowStart = nowMillis()
            val until = windowStart + 65_000
            while (nowMillis() < until) {
                val now = rig.onMain { sample() + rig.kbTipNow() }
                if (now[0] != edge) { edge = now[0]; edges += nowMillis() }
                assertSame("$label: the recorder state object stays", base[1], now[1])
                assertSame("$label: the coordinator's entries instance stays", base[2], now[2])
                assertSame("$label: the 1d entry object stays", base[3], now[3])
                assertEquals("$label: the kb tip stays", base[4], now[4])
                assertEquals("$label: tether stays DEGRADED", com.jay.fxi.domain.model.TopicDeliveryState.DEGRADED, tetherState())
                assertEquals("$label: no subscription is sent", subscribedBefore, synchronized(subscribed) { subscribed.size })
                assertEquals("$label: no graph request\n${sends.timeline()}", graphBefore, graph().size)
                assertEquals("$label: no other send\n${sends.timeline()}", sendsBefore, sends.all().size)
                delay(20)
            }
            println("CUT-C01 $label: right edge moved at ${edges.map { it - windowStart }} ms into the window")
            assertTrue("$label: the right edge moved two or three times ($edges)", edges.size in 2..3)
            assertTrue("$label: 25 to 35 s apart ($edges)", edges.zipWithNext().all { (a, b) -> b - a in 25_000..35_000 })
            assertEquals("$label: premise: the same 600 s bucket", bucket, System.currentTimeMillis() / 600_000)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 175_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 2a: RT03b-Q06 (cut_c01_b2_agreed.r1.md) ---------------------------------------------------------------

    /**
     * RT03b-Q06 (i-a)/(i-b): an open cycle whose round falls due while usd 1d is not the active key — the screen on JPY
     * ([longPeriod] false) or on usd 3m (true). Past the deadline nothing for the budget is sent (no usd 1d tab, no catalog)
     * and the demand is unchanged; back on usd 1d, exactly one usd 1d tab goes at once and meets it.
     */
    private fun inactiveRoundRow(label: String, longPeriod: Boolean) = runBlocking {
        val prepared = clearOfBoundary(70_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 50_000
        val wallAtOrigin = System.currentTimeMillis() - nowMillis()
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            val holder = checkNotNull(rig.onMain { rig.mount?.holders?.value?.get("usd") })
            val starts = closedStarts()
            val baseline = tabs().size
            val usdOneDayCalls = AtomicInteger()
            onTab = { request ->
                val url = request.requestUrl!!
                when {
                    url.queryParameter("tab") == "jpy" -> ok(jpyTab())
                    url.queryParameter("period") == "3m" -> ok(fixture("usd-3m-krx-hidden.json"))
                    usdOneDayCalls.getAndIncrement() == 0 -> unavailable()
                    else -> ok(usdTab(starts))
                }
            }
            val usdOneDay = { tabs().drop(baseline).filter { it.param("tab") == "usd" && it.param("period") == "1d" } }
            openSockets.last().close(1011, "server restart")
            awaitTrue("$label: round 1 (503) completed", deadline) { usdOneDay().size == 1 && usdOneDay()[0].bodyEnd != null }
            // The cycle's first deadline is its opening; wait for the one round 1's completion set.
            val firstEnd = checkNotNull(usdOneDay()[0].bodyEnd)
            awaitTrue("$label: the next round is scheduled after round 1", deadline) {
                rig.usdBudget()?.deadline?.let { it.toEpochMilliseconds() - wallAtOrigin > firstEnd } == true
            }
            val due = checkNotNull(checkNotNull(rig.usdBudget()).deadline).toEpochMilliseconds() - wallAtOrigin
            val away = if (longPeriod) {
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.THREE_MONTHS) }
                awaitTrue("$label: the 3m tab applied", deadline) { tabs().drop(baseline).any { it.param("period") == "3m" && it.bodyEnd != null } }
                holder
            } else {
                rig.moveTo(FreeTab.JPY, holder, deadline).also {
                    // The jpy key's own activation request can follow the move: it is sent and answered before the away window.
                    awaitTrue("$label: the jpy tab applied", deadline) {
                        tabs().drop(baseline).any { t -> t.param("tab") == "jpy" && t.bodyEnd != null }
                    }
                }
            }
            assertTrue("$label: premise: usd 1d left before the round was due (${nowMillis()} vs $due)", nowMillis() < due)
            awaitTrue("$label: the away key's request settled", deadline) { graph().all { it.bodyEnd != null } }
            val graphAway = graph().size
            while (nowMillis() < due + 4_000) {
                assertEquals("$label: no usd 1d tab while it is not active\n${sends.timeline()}", 1, usdOneDay().size)
                assertEquals("$label: no graph send at all for the budget while away\n${sends.timeline()}", graphAway, graph().size)
                delay(50)
            }
            assertEquals("$label: the demand is unchanged", starts.toSet(), rig.kbClosedPending())
            val back = nowMillis()
            if (longPeriod) {
                rig.onMain { holder.selectPeriod(checkNotNull(holder.currentState().inlineToken), GraphPeriod.ONE_DAY) }
            } else {
                rig.moveTo(FreeTab.USD, away, deadline)
            }
            awaitTrue("$label: one usd 1d tab on the return", deadline) { usdOneDay().size == 2 }
            assertTrue("$label: at once (${usdOneDay()[1].start - back} ms after the return)\n${sends.timeline()}", usdOneDay()[1].start - back <= 1_500)
            awaitTrue("$label: it met the demand", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
            quiet(label, 3_000)
            assertEquals("$label: exactly one usd 1d tab on the return", 2, usdOneDay().size)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 50_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    @Test
    fun `RT03b-Q06a a round falling due while another tab is active waits for usd and goes once on the return`() =
        inactiveRoundRow("RT03b-Q06a", longPeriod = false)

    @Test
    fun `RT03b-Q06b a round falling due while usd 3m is active waits for usd 1d and goes once on the return`() =
        inactiveRoundRow("RT03b-Q06b", longPeriod = true)

    /**
     * RT03b-Q06c: a terminal answer (404) stops the cycle as TERMINAL with the demand kept; nothing is sent for 10 s, and a
     * reconnect past the cooldown, which issues a trigger, reopens nothing.
     */
    @Test
    fun `RT03b-Q06c a terminal answer stops the cycle, keeps the demand and no trigger reopens it`() = runBlocking {
        val label = "RT03b-Q06c"
        val prepared = clearOfBoundary(80_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 70_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            val starts = closedStarts()
            val baseline = tabs().size
            onTab = { MockResponse().setResponseCode(404).setBody("""{"detail":"not found"}""") }
            val sockets = openSockets.size
            openSockets.last().close(1011, "server restart")
            awaitTrue("$label: the reconnected socket", deadline) { openSockets.size > sockets }
            val reconnectedAt = nowMillis()
            awaitTrue("$label: round 1 (404) completed", deadline) { tabs().size == baseline + 1 && tabs().last().bodyEnd != null }
            awaitTrue("$label: the cycle stopped as terminal", deadline) { rig.usdBudget()?.stop?.toString() == "TERMINAL" }
            assertEquals("$label: the demand is kept", starts.toSet(), rig.kbClosedPending())
            assertEquals("$label: the recorder still demands",
                com.jay.fxi.data.graph.GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false), rig.usdDemand())
            quiet(label, 10_000)
            while (nowMillis() < reconnectedAt + 31_000) delay(100)
            val triggersBefore = rig.triggers()
            val socketsMid = openSockets.size
            openSockets.last().close(1011, "server restart")
            awaitTrue("$label: a reconnect past the cooldown", deadline) { openSockets.size > socketsMid }
            awaitTrue("$label: it issued a trigger", deadline) { rig.triggers() > triggersBefore }
            quiet(label, 5_000)
            assertEquals("$label: still terminal", "TERMINAL", rig.usdBudget()?.stop?.toString())
            assertEquals("$label: one recovery round only", 1, tabs().size - baseline)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 70_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT03b-Q06d: a round cancelled by a same-scope context change. Round 1 is held; a capability rotation (KRX hidden) gives
     * a new grant on the same user epoch; the old round's full answer then completes released and applies nothing (the
     * demand is unchanged). The next round asks the catalog once and its answer meets the demand.
     */
    @Test
    fun `RT03b-Q06d a round released by a same-scope context change applies nothing and the next asks the catalog once`() = runBlocking {
        val label = "RT03b-Q06d"
        val prepared = clearOfBoundary(70_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 50_000
        entitlements += unauthorized()
        entitlements += ok(premiumKrxVisible)
        val holdOld = java.util.concurrent.CountDownLatch(1)
        // Every request after the change waits until the old round's release has been judged.
        val holdNew = java.util.concurrent.CountDownLatch(1)
        val krxVisible = java.util.concurrent.atomic.AtomicBoolean(true)
        val rig = Rig()
        try {
            rig.row {
                rig.prepareClosedDemand(deadline, krx = true)
                val starts = closedStarts()
                val baseline = tabs().size
                onCatalog = {
                    if (krxVisible.get()) ok(fixture("catalog-krx-visible.json"))
                    else { holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS); ok(fixture("catalog-krx-hidden.json")) }
                }
                // Let the cold start settle before the reconnect and rotation, as RT01-A05 does.
                rig.awaitTopics(deadline)
                delay(3_500)
                val calls = AtomicInteger()
                // Each later tab's registration as it is sent: the one that meets the demand must carry the capture.
                val laterRegistrations = Collections.synchronizedList(mutableListOf<TabRegistrationView>())
                val laterFromRound = Collections.synchronizedList(mutableListOf<Boolean?>())
                onTab = {
                    if (calls.getAndIncrement() == 0) holdOld.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    else {
                        laterRegistrations += runBlocking { rig.usdTabRegistration() }
                        laterFromRound += runBlocking { rig.usdTabFromRound() }
                        holdNew.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    ok(usdTab(starts))
                }
                openSockets.last().close(1011, "server restart")
                awaitTrue("$label: round 1 in flight", deadline) { tabs().size == baseline + 1 }
                val captured = checkNotNull(rig.usdTabRegistration().captures)
                assertTrue("$label: premise: round 1 carries kb.usd's capture: $captured", captured.any { it.seriesKey.seriesId == "kb.usd" })
                val f1 = checkNotNull(rig.bridge())
                krxVisible.set(false)
                entitlements += ok(PREMIUM_HIDDEN)
                val catalogsBefore = catalogs().size
                kotlinx.coroutines.withTimeout(10_000) { rig.coordinator.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }
                val f2 = checkNotNull(rig.coordinator.topicGrantResult().fence)
                assertEquals("$label: premise: the same user epoch", f1.userAccessEpoch, f2.userAccessEpoch)
                assertNotEquals("$label: premise: a new grant", f1.grant, f2.grant)
                awaitTrue("$label: the new context is consumed", deadline) { rig.assembly.coordinator.state.value.source?.fence == f2 }
                holdOld.countDown()
                awaitTrue("$label: the old round's body ended", deadline) { tabs()[baseline].bodyEnd != null }
                assertEquals("$label: premise: the old round's full answer was delivered", 200, tabs()[baseline].status)
                delay(500)
                assertEquals("$label: the released round applied nothing (the demand is unchanged)", starts.toSet(), rig.kbClosedPending())
                assertTrue("$label: premise: nothing after the change has completed yet",
                    tabs().drop(baseline + 1).none { it.bodyEnd != null } && catalogs().drop(catalogsBefore).none { it.bodyEnd != null })
                holdNew.countDown()
                awaitTrue("$label: a later round met the demand", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
                quiet(label, 3_000)
                val after = catalogs().drop(catalogsBefore)
                assertEquals("$label: one catalog after the change\n${sends.timeline()}", listOf(200), statuses(after))
                val later = synchronized(laterRegistrations) { laterRegistrations.toList() }
                val fromRound = synchronized(laterFromRound) { laterFromRound.toList() }
                val laterTabs = tabs().drop(baseline + 1)
                println("CUT-C01 $label later tabs: ${laterTabs.map { it.start }}; registrations $later; from a round $fromRound")
                // The next round, and nothing else: one tab after the change, sent after the catalog ended, with the capture.
                assertEquals("$label: exactly one tab after the change\n${sends.timeline()}", 1, laterTabs.size)
                assertEquals("$label: exactly one later registration: $later", 1, later.size)
                assertTrue("$label: the catalog ended before the later tab\n${sends.timeline()}",
                    checkNotNull(after.single().bodyEnd) <= laterTabs.single().start)
                assertTrue("$label: the sole later tab carried kb.usd's capture: $later",
                    later.single().captures.orEmpty().any { it.seriesKey.seriesId == "kb.usd" })
                assertEquals("$label: and was issued by the recovery round", listOf<Boolean?>(true), fromRound)
            }
        } finally {
            holdOld.countDown()
            holdNew.countDown()
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 50_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /** The catalog fixture with its cache TTL replaced by [seconds]. */
    private fun catalogWithTtl(seconds: Int): String {
        val base = wire.parseToJsonElement(fixture("catalog-krx-hidden.json")).jsonObject
        return kotlinx.serialization.json.JsonObject(base + ("cache_ttl_seconds" to kotlinx.serialization.json.JsonPrimitive(seconds))).toString()
    }

    /**
     * RT03b-Q06e: a catalog whose TTL is 20 s. Rounds that start inside the TTL send no catalog; the first round after it
     * sends exactly one catalog before its tab. Every round answers 503 until the round after the TTL, whose answer meets
     * the demand.
     */
    @Test
    fun `RT03b-Q06e rounds inside the catalog TTL ask no catalog and the first after it asks one`() = runBlocking {
        val label = "RT03b-Q06e"
        val prepared = clearOfBoundary(80_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 60_000
        val wallAtOrigin = System.currentTimeMillis() - nowMillis()
        entitlements += unauthorized()
        onCatalog = { ok(catalogWithTtl(20)) }
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            val starts = closedStarts()
            val baseline = tabs().size
            val adoptedAt = checkNotNull(rig.catalogAt()).toEpochMilliseconds() - wallAtOrigin
            val expiresAt = adoptedAt + 20_000
            // Decided as each round arrives: inside the TTL 503, the first one after it the full answer.
            onTab = { if (nowMillis() >= expiresAt) ok(usdTab(starts)) else unavailable() }
            val catalogsBefore = catalogs().size
            openSockets.last().close(1011, "server restart")
            // Rounds until one starts after the TTL; that one is answered in full.
            awaitTrue("$label: a round starts after the TTL", deadline) { tabs().drop(baseline).any { it.start >= expiresAt } }
            awaitTrue("$label: the demand is met", deadline) { rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None }
            quiet(label, 3_000)
            val rounds = tabs().drop(baseline)
            val inside = rounds.filter { it.start < expiresAt }
            val first = rounds.first { it.start >= expiresAt }
            val newCatalogs = catalogs().drop(catalogsBefore)
            println("CUT-C01 $label: catalog adopted $adoptedAt ms, expires $expiresAt ms; rounds ${rounds.map { it.start }}; catalogs ${newCatalogs.map { it.start }}")
            assertTrue("$label: premise: at least two rounds inside the TTL (${inside.size})", inside.size >= 2)
            assertTrue("$label: no catalog during the rounds inside the TTL\n${sends.timeline()}",
                newCatalogs.none { it.start <= checkNotNull(inside.last().bodyEnd) })
            assertEquals("$label: exactly one catalog for the first round after the TTL, before its tab\n${sends.timeline()}",
                1, newCatalogs.count { it.start > checkNotNull(inside.last().bodyEnd) && it.start <= first.start })
            assertEquals("$label: no other catalog\n${sends.timeline()}", 1, newCatalogs.size)
            assertTrue("$label: that catalog ended before the round's tab\n${sends.timeline()}", checkNotNull(newCatalogs.single().bodyEnd) <= first.start)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 60_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    // --- CUT-C01 batch 2a: RT03b-Q03 (cut_c01_b2_agreed.r1.md) ---------------------------------------------------------------

    /**
     * RT03b-Q03a: the recovery ladder over a real closed demand. A reconnect opens the cycle; every round answers 503: six
     * rounds at most, each next round [ladder] after the previous completed; then the budget stops EXHAUSTED with the demand
     * kept (every closed start still pending, the recorder still demanding), and nothing is sent for 49 s.
     */
    @Test
    fun `RT03b-Q03a a real closed demand walks the whole ladder, stops at the cap and keeps the demand`() = runBlocking {
        val label = "RT03b-Q03a"
        val prepared = clearOfBoundary(200_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 190_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            val starts = closedStarts()
            val baseline = tabs().size
            onTab = { unavailable() }
            assertEquals("$label: premise: every closed start is demanded", starts.toSet(), rig.kbClosedPending())
            openSockets.last().close(1011, "server restart")
            awaitTrue("$label: six recovery rounds completed", deadline) { tabs().size == baseline + 6 && tabs().all { it.bodyEnd != null } }
            val r = tabs().drop(baseline)
            for (i in 0 until 5) {
                val gap = r[i + 1].start - checkNotNull(r[i].bodyEnd)
                assertTrue("$label: round ${i + 2} goes ${ladder[i]} ms after round ${i + 1} completed, not before (gap $gap)\n${sends.timeline()}",
                    gap >= ladder[i] - 50)
                assertTrue("$label: and without a needless delay (gap $gap)\n${sends.timeline()}", gap <= ladder[i] + 2_000)
            }
            awaitTrue("$label: the cycle is exhausted", deadline) { rig.usdBudget()?.stop?.toString() == "EXHAUSTED" }
            assertEquals("$label: six rounds in the cycle", 6, checkNotNull(rig.usdBudget()).rounds)
            assertEquals("$label: every closed start is still demanded", starts.toSet(), rig.kbClosedPending())
            assertEquals("$label: the recorder still demands the closed buckets",
                com.jay.fxi.data.graph.GraphTabRecoveryDemand.Pending(closed = true, mappingWait = false), rig.usdDemand())
            quiet(label, 49_000)
            assertEquals("$label: still exhausted after the quiet window", "EXHAUSTED", rig.usdBudget()?.stop?.toString())
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 190_000)
        assertEquals("$label: every recovery round answered 503", List(6) { 503 }, statuses(tabs()).takeLast(6))
        ColdStartBudget.assertWithin(judgeRow(label))
    }

    /**
     * RT03b-Q03b: a stated floor longer than the rung. The second round answers 503 with Retry-After 30 s; the third round,
     * due 6 s after it, waits for the floor (30 s + the usd jitter of 10 s) and nothing else is sent before; it then meets the
     * demand.
     */
    @Test
    fun `RT03b-Q03b a Retry-After longer than the rung holds the next round until the floor`() = runBlocking {
        val label = "RT03b-Q03b"
        assertEquals("premise: J = 10 s for usd", 10.seconds, FreeSnapshotSchedulePolicy.jitterFor(SEED, "usd"))
        val prepared = clearOfBoundary(90_000)
        println("CUT-C01 $label: waited ${prepared} ms before the start to stay clear of a 600 s boundary")
        val start = nowMillis()
        val deadline = start + 80_000
        entitlements += unauthorized()
        val rig = Rig()
        rig.row {
            rig.prepareClosedDemand(deadline)
            val starts = closedStarts()
            val baseline = tabs().size
            val calls = AtomicInteger()
            onTab = {
                when (calls.getAndIncrement()) {
                    0 -> unavailable()
                    1 -> unavailable(retryAfter = 30)
                    else -> ok(usdTab(starts))
                }
            }
            openSockets.last().close(1011, "server restart")
            awaitTrue("$label: the second round completed", deadline) { tabs().size == baseline + 2 && tabs().last().bodyEnd != null }
            val second = tabs()[baseline + 1]
            val secondEnd = checkNotNull(second.bodyEnd)
            awaitTrue("$label: the third round went", deadline) { tabs().size == baseline + 3 }
            val third = tabs()[baseline + 2]
            val graphBetween = graph().filter { it.start > secondEnd && it.start < third.start }
            assertTrue("$label: the third round waits for the floor, not the 6 s rung (${third.start - secondEnd} ms)\n${sends.timeline()}",
                third.start >= secondEnd + 40_000 - 50)
            assertTrue("$label: and goes at the floor (${third.start - secondEnd} ms)\n${sends.timeline()}", third.start <= secondEnd + 42_000)
            assertEquals("$label: nothing else sent under the floor\n${sends.timeline()}", emptyList<SendRecorder.Exchange>(), graphBetween)
            awaitTrue("$label: the third round met the demand", deadline) {
                rig.usdDemand() == com.jay.fxi.data.graph.GraphTabRecoveryDemand.None
            }
            quiet(label, 5_000)
        }
        assertEquals("no reported failure", emptyList<Throwable>(), rig.reports)
        assertTrue("within the row deadline", nowMillis() - start <= 80_000)
        ColdStartBudget.assertWithin(judgeRow(label))
    }

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
    private fun usdSnapshot(rate: Double = 1390.0): String = usdSnapshotAt(rate, System.currentTimeMillis() / 1000)

    /** The usd topic snapshot (contract fixture envelope) with one kb quote stamped at [epochSecond] and nothing else. */
    private fun usdSnapshotAt(rate: Double, epochSecond: Long): String {
        val base = wire.parseToJsonElement(File("src/test/resources/contracts/v2/topic/snapshot-fx-usd-krw.json").readText()).jsonObject
        val data = base.getValue("data").jsonObject
        val kb = kotlinx.serialization.json.buildJsonObject {
            put("asset", kotlinx.serialization.json.JsonPrimitive("usd-krw"))
            put("rate", kotlinx.serialization.json.JsonPrimitive(rate))
            put("source", kotlinx.serialization.json.JsonPrimitive("kb"))
            put("timestamp", kotlinx.serialization.json.JsonPrimitive(isoAt(epochSecond)))
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

    /** A usd 1d tab (from the fixture [from]) whose kb.usd holds a point at each of [starts], stamped from the test's clock. */
    private fun usdTab(starts: List<Long>, from: String = "usd-1d-krx-hidden.json"): String {
        val base = wire.parseToJsonElement(fixture(from)).jsonObject
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
        budgets.entries.singleOrNull { privateField(it.key, "tab") == "usd" }?.value?.let { b ->
            fun f(name: String) = b.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(b)
            BudgetView(f("rounds") as Int, f("completionRung") as Int, f("deadline") as kotlinx.datetime.Instant?, f("permitWait") != null, f("stop"))
        }
    }

    /** The usd tab's registered 1d request as seen at its send: whether a catalog was adopted, and its recovery captures. */
    private data class TabRegistrationView(val catalogAdopted: Boolean, val captures: List<com.jay.fxi.data.graph.GraphRecoveryRequest>?)

    /** Read on Main; [captures] is null when no usd 1d request is registered. */
    private suspend fun Rig.usdTabRegistration(): TabRegistrationView = onMain {
        val c = assembly.coordinator
        @Suppress("UNCHECKED_CAST")
        val requests = privateField(c, "tabRequests") as Map<com.jay.fxi.data.graph.GraphKey, Any>
        @Suppress("UNCHECKED_CAST")
        val captures = requests.entries.firstOrNull { it.key.tab == "usd" && it.key.period.code == "1d" }
            ?.value?.let { privateField(it, "recoveryRequests") as List<com.jay.fxi.data.graph.GraphRecoveryRequest> }
        TabRegistrationView(c.state.value.catalog != null, captures)
    }

    /** Whether the registered usd 1d request was issued by a recovery round; read on Main, null when none is registered. */
    private suspend fun Rig.usdTabFromRound(): Boolean? = onMain {
        @Suppress("UNCHECKED_CAST")
        val requests = privateField(assembly.coordinator, "tabRequests") as Map<com.jay.fxi.data.graph.GraphKey, Any>
        requests.entries.firstOrNull { it.key.tab == "usd" && it.key.period.code == "1d" }?.value?.let { privateField(it, "automaticRecovery") as Boolean }
    }

    /** A 503 the graph endpoints answer; with [retryAfter], its Retry-After header (seconds). */
    private fun unavailable(retryAfter: Int? = null): MockResponse =
        MockResponse().setResponseCode(503).setBody("""{"detail":"unavailable"}""").also { r -> retryAfter?.let { r.setHeader("Retry-After", it) } }

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
