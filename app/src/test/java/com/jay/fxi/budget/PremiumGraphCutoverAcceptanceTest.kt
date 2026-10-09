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
import com.jay.fxi.data.remote.TopicForegroundStream
import com.jay.fxi.data.remote.TopicFrameDecoder
import com.jay.fxi.data.remote.TopicRuntimeFactory
import com.jay.fxi.data.remote.TopicRuntimeOwner
import com.jay.fxi.data.remote.TopicSnapshotBootstrapService
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.di.NetworkModule
import com.jay.fxi.domain.model.FreeTab
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** While set, each subscription naming the usd topic is acknowledged with that topic refused, after this hook runs. */
    @Volatile private var refuseUsd: (() -> Unit)? = null
    /** When each WebSocket request reached the server (the client's Connection exists by then), in order. */
    private val wsArrivals = Collections.synchronizedList(mutableListOf<Long>())
    /** The next handshake's 101 is held this long once (0: none). */
    private val holdHandshakeMillis = java.util.concurrent.atomic.AtomicLong(0L)
    /** Every server-side socket opened, in order; the last is the live one. */
    private val openSockets = Collections.synchronizedList(mutableListOf<WebSocket>())
    private val refusals = AtomicInteger()

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
            val refuse = refuseUsd
            val usd = "fx:usd-krw"
            if (refuse != null && usd in topics) {
                refuse()
                refusals.incrementAndGet()
                webSocket.send(C4OwnerHarness.ack(id, topics - usd, mapOf(usd to "premium_required")))
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
                    "/api/entitlements" -> entitlements.poll() ?: ok(PREMIUM_HIDDEN)
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
        override fun currentIdentity(): AuthIdentity? = identity
        override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
            if (forceRefresh) token = "fresh-token-${System.nanoTime()}"
            return token
        }
        override fun observe(onFence: (AuthIdentityFence?) -> Unit) {
            observers += onFence
            onFence(identity?.let { AuthIdentityFence(it.uid, it.authGeneration) })
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
            }
        }
        /** The issuer on its production scope: a SupervisorJob on Default, independent of Main. */
        val coordinator = budgetCoordinator(api, provider, CoroutineScope(issuerJob + Dispatchers.Default), epochStore, { issuerIdentityReadable })
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
        /** The permit slot the production builder was handed, kept for observation only. */
        @Volatile var permitSlot: com.jay.fxi.data.graph.LateBound<() -> com.jay.fxi.data.remote.TopicGraphRecoveryPermit?>? = null
        val production = AppProcessGraphBuilder(
            disk = Provider { disk },
            deletions = Provider { DeletionAdmissionStore() },
            seeds = Provider { InstallSeedSource({ SEED }, Dispatchers.IO) },
            api = Provider { api },
            uses = Provider { uses },
            selections = Provider { selections },
            coordinator = coordinator,
            tokens = provider
        )
        val builder = com.jay.fxi.data.graph.ProcessGraphBuilder { permit, seed, timeEvent, parent ->
            permitSlot = permit
            production.build(permit, seed, timeEvent, parent).also { parts = it }
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
                issuer = PremiumAccessTopicGrantIssuer(coordinator),
                fences = firebase,
                liveFence = provider::currentIdentityFence,
                recoveries = provider,
                tabs = tabs,
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
                CoroutineScope(ownerJob + main).launch {
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
        identityReadable: () -> Boolean
    ): PremiumAccessCoordinator {
        // The production binding for both purgers: it answers Deferred, so every journal entry stays owed.
        val purger = UnimplementedScopePurger()
        return PremiumAccessCoordinator(
            // The production source; only its identity read can be held unreadable, as Firebase can be for the issuer alone.
            source = AuthenticatedEntitlementsSource(api).let { real ->
                object : com.jay.fxi.data.entitlements.EntitlementsSource {
                    override suspend fun fetch(freshPremium: Boolean) = real.fetch(freshPremium)
                    override suspend fun currentIdentity() = if (identityReadable()) real.currentIdentity() else null
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
    private fun usdSnapshot(): String {
        val base = wire.parseToJsonElement(File("src/test/resources/contracts/v2/topic/snapshot-fx-usd-krw.json").readText()).jsonObject
        val data = base.getValue("data").jsonObject
        val kb = kotlinx.serialization.json.buildJsonObject {
            put("asset", kotlinx.serialization.json.JsonPrimitive("usd-krw"))
            put("rate", kotlinx.serialization.json.JsonPrimitive(1390.0))
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
    private suspend fun Rig.prepareClosedDemand(deadline: Long) {
        usdSnapshotLive = true
        // The first tab answers only once the catalog is adopted, so no cold recovery budget is left from the start and the
        // reconnect opens the cycle at its first round (a tab completing before the catalog would leave one with a 3 s deadline).
        onTab = {
            val until = System.nanoTime() + 10_000_000_000L
            while (parts == null || assembly.coordinator.state.value.catalog == null) {
                if (System.nanoTime() > until) break
                Thread.sleep(10)
            }
            ok(fixture("usd-1d-krx-hidden.json"))
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
