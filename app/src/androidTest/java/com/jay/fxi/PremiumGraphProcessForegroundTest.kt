package com.jay.fxi

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantContext
import com.jay.fxi.data.entitlements.TopicGrantIssuer
import com.jay.fxi.data.entitlements.TopicGrantResult
import com.jay.fxi.data.entitlements.TopicRejectionLedger
import com.jay.fxi.data.entitlements.TopicRejectionReservation
import com.jay.fxi.data.free.InstallSeedSource
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRuntimeAssembly
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.ProcessGraphBuilder
import com.jay.fxi.data.graph.ProcessGraphParts
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.remote.ElapsedRealtimeTopicCommandClock
import com.jay.fxi.data.remote.ProcessTopicForegroundStream
import com.jay.fxi.data.remote.TopicBootstrapFloor
import com.jay.fxi.data.remote.TopicCommandCredentials
import com.jay.fxi.data.remote.TopicForegroundStream
import com.jay.fxi.data.remote.TopicFrameDecoder
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicRuntimeFactory
import com.jay.fxi.data.remote.TopicRuntimeOwner
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicSnapshotOutcome
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.repository.NewsRepository
import com.jay.fxi.time.SystemAppClock
import com.jay.fxi.ui.premium.graph.GraphScreenHost
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import com.jay.fxi.ui.premium.view.PremiumTopicRoute
import com.jay.fxi.ui.viewmodel.NewsViewModel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 CUT-CC5-3 D3 integration row (`cut_cc5_agreed.r1.md`, `cut_api_agreed.r3.md` D3): on a device, the real
 * topic owner with the real process foreground stream (ProcessLifecycleOwner), the real runtime factory, a real graph
 * assembly and starter, the real screen host and holders, and the real route on the real activity. Only auth, the server
 * and stores are replaced. The route synthesizes no foreground: the process return is the platform's.
 *
 * One Main-confined log records, in order: each process foreground delivery around the owner's own handling of it
 * (`fg:<v>:begin`/`fg:<v>:end`), each recovery time event (`time`), the host's construction with the recovery events'
 * foreground at that moment (`host:<v>`), and each change of the usd holder's surface visibility, observed from inside the
 * holder's own call (`show`/`hide`).
 */
class PremiumGraphProcessForegroundTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val a1 = AuthIdentityFence("A", 1L)
    private val fence = TopicSessionFence(a1, "e1", TopicGrantToken(1L))
    private val binding = EntitlementsIdentity("A", 1L)
    private val record = AccessFence("A", "e1", "K1")
    private val snapshot: TopicAccessSnapshot = TopicAccessSnapshot.INITIAL.copy(
        revision = 100L,
        facts = TopicAccessFacts.NONE.copy(
            token = fence.grant,
            issuedFor = TopicGrantContext(binding, record, 10L),
            binding = binding,
            recordFence = record,
            decisionGeneration = 10L,
            tokenStanding = true,
            userBlocks = emptySet(),
            capabilityBlocks = emptySet()
        ),
        userInvalidations = 3L
    )
    private val authority: TopicUseAuthority = SnapshotTopicUseAuthority { snapshot }

    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val scopes = Collections.synchronizedList(mutableListOf<CoroutineScope>())
    private val ownerMain = CoroutineScope(Dispatchers.Main + SupervisorJob()).also { scopes += it }
    private val seedGate = CountDownLatch(1)
    @Volatile private var assembly: GraphRuntimeAssembly? = null
    @Volatile private var usd: GraphV2ScreenStateHolder? = null
    private var usdShown = false
    /** Main only: set while the recovery events' time event runs, so the host's fan-out reaching usd can be logged. */
    private var inTime = false
    /** The process lifecycle is shared by every test: a finished test's owner hears nothing more. */
    @Volatile private var disposed = false
    /** For each `show`, whether a lifecycle dispatch was on the stack: the surface's start effect reporting it. */
    private val showFromLifecycle = Collections.synchronizedList(mutableListOf<Boolean>())

    @After fun tearDown() {
        disposed = true
        seedGate.countDown()
        scopes.forEach { it.cancel() }
    }

    private val socket = object : WebSocket {
        override fun cancel() = Unit
        override fun close(code: Int, reason: String?) = true
        override fun queueSize() = 0L
        override fun request() = Request.Builder().url("http://localhost/ws").build()
        override fun send(text: String) = true
        override fun send(bytes: okio.ByteString) = true
    }

    @Volatile private var listener: WebSocketListener? = null

    /** The server accepting the connection the runtime opened. */
    private fun openConnection() {
        until("premise: the runtime opened a connection") { listener != null }
        checkNotNull(listener).onOpen(socket, okhttp3.Response.Builder().request(socket.request()).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(101).message("").build())
    }

    private val issuer = object : TopicGrantIssuer {
        private val ledger = TopicRejectionLedger(AtomicLong(0L)::incrementAndGet)
        override val accessRevisions: StateFlow<Long> = MutableStateFlow(0L)
        override suspend fun topicGrantResult() = TopicGrantResult(fence, snapshot)
        override fun reserveRejection(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>) = ledger.reserve(grant, reasons)
        override fun abandonRejection(reservation: TopicRejectionReservation) = ledger.abandon(reservation)
        override suspend fun onTopicRejected(reservation: TopicRejectionReservation) = ledger.complete(reservation, true)
    }

    private val factory = TopicRuntimeFactory(
        webSocketFactory = { WebSocket.Factory { _: Request, l: WebSocketListener -> listener = l; socket } },
        webSocketUrl = "http://localhost/ws",
        decode = TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode,
        bootstrap = { _, _, _ -> TopicSnapshotOutcome.Degraded },
        issuer = issuer,
        fences = AuthFenceStream { it(a1) },
        liveFence = { a1 },
        recoveries = AuthCredentialRecoveryStream { },
        tabs = object : FreeTabStore {
            override suspend fun lastTab(uid: String) = FreeTab.USD
            override suspend fun remember(uid: String, tab: FreeTab) = Unit
        },
        credentials = object : TopicCommandCredentials {
            override suspend fun currentSnapshot() = AuthSnapshot(a1.uid, a1.authGeneration, "token")
            override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? = null
            override suspend fun recordRejected(credential: AuthSnapshot) = Unit
            override suspend fun recordRejectionEvidence(credential: AuthSnapshot) = Unit
        },
        orders = AccessOrderSequence(),
        authority = authority,
        clock = ElapsedRealtimeTopicCommandClock(),
        newBootstrapFloor = {
            object : TopicBootstrapFloor {
                override fun record(statusCode: Int?, retryAfter: String?) = Unit
                override fun notBeforeMillis() = 0L
            }
        },
        newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)).also { scopes += it } },
        encode = { Json.encodeToString(TopicSubscribeRequest.serializer(), it) },
        newRequestId = { java.util.UUID.randomUUID().toString() },
        jitter = { 0.0 }
    )

    private val selections = object : GraphSelectionStore {
        override suspend fun readGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection) = GraphSelectionWriteResult.Committed
        override suspend fun confirmGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
    }

    /** Records the usd holder's surface visibility whenever the holder itself reads the live identity, on Main. */
    private fun observeSurface() {
        val holder = usd ?: return
        val shown = GraphV2ScreenStateHolder::class.java.getDeclaredField("inlineVisible").apply { isAccessible = true }
            .get(holder) as Boolean
        if (shown != usdShown) {
            usdShown = shown
            if (shown) showFromLifecycle += Thread.currentThread().stackTrace.any { it.className.startsWith("androidx.lifecycle.LifecycleRegistry") }
            log += if (shown) "show" else "hide"
        }
    }

    /** The real assembly over a request-less fetcher; its seed is read only once [seedGate] opens, so the host comes late. */
    private val builder = ProcessGraphBuilder { permit, seed, timeEvent, parent ->
        val built = GraphRuntimeAssembly(
            accessSnapshot = { snapshot },
            accessRevisions = issuer.accessRevisions,
            liveIdentity = { a1 },
            uses = authority,
            protectedAdmission = { true },
            fetcher = object : GraphV2Fetching {
                override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = awaitCancellation()
                override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean) = awaitCancellation()
            },
            owners = object : GraphOwnerSource {
                override fun currentIdentity(): AuthIdentityFence = a1
                override suspend fun capture(expected: AuthIdentityFence) = AuthSnapshot(expected.uid, expected.authGeneration, "token")
            },
            cachePorts = { null },
            main = Dispatchers.Main,
            parent = parent,
            clock = SystemAppClock,
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { },
            recoveryPermit = { permit.require()() },
            timeEvent = {
                log += "time"
                inTime = true
                try {
                    timeEvent()
                } finally {
                    inTime = false
                }
            }
        ).also { assembly = it }
        ProcessGraphParts(built, InstallSeedSource({ seedGate.await(); "seed" }, Dispatchers.IO)) { tab, display, focus, scope ->
            val session = GraphSeriesSelectionSession(selections, GraphSelectionAudience.PREMIUM, tab, scope, Dispatchers.Main)
            var self: GraphV2ScreenStateHolder? = null
            GraphV2ScreenStateHolder(tab, built.coordinator, session, {
                if (self === usd) {
                    // The host's fan-out reached this holder inside the time event: its publication reads the live identity.
                    if (inTime && log.lastOrNull() != "time:usd") log += "time:usd"
                    observeSurface()
                }
                a1
            }, display, focus, built.fences.current, authority, built.gate, issuer.accessRevisions, scope, Dispatchers.Main,
                recorder = built.recorder).also {
                self = it
                if (tab == "usd") usd = it
            }
        }
    }

    private fun eventsForeground(): Any? = checkNotNull(assembly).events.let { e ->
        e.javaClass.getDeclaredField("foreground").apply { isAccessible = true }.get(e)
    }

    private val owner = TopicRuntimeOwner(
        factory = factory,
        online = MutableStateFlow(true),
        foreground = TopicForegroundStream { onForeground ->
            ProcessTopicForegroundStream().observe { value ->
                if (disposed) return@observe
                log += "fg:$value:begin"
                onForeground(value)
                log += "fg:$value:end"
            }
        },
        fences = AuthFenceStream { it(a1) },
        liveIdentity = { a1 },
        rowPreferenceStore = object : RateRowPreferenceStore {
            override suspend fun preferences(uid: String) = emptyMap<RateRowList, RateRowPreference>()
            override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
        },
        main = ownerMain,
        graphBuilder = builder,
        newGraphHost = { scope, create, onFailure ->
            log += "host:${eventsForeground()}"
            GraphScreenHost(scope, create, onFailure)
        }
    )

    private fun <T> onMain(read: () -> T): T {
        var result: Result<T>? = null
        rule.runOnUiThread { result = runCatching(read) }
        return checkNotNull(result).getOrThrow()
    }

    private fun logged(): List<String> = onMain { log.toList() }

    private fun until(label: String, millis: Long = 5_000, condition: () -> Boolean) =
        rule.waitUntil(millis) { condition() }.also { assertTrue(label, condition()) }

    /** Starts the owner on Main in the foreground, then shows the route with the owner's consumer and published host. */
    private fun startAndShow() {
        rule.runOnUiThread { owner.start() }
        val vm = NewsViewModel(object : NewsRepository {
            override suspend fun fetchNews(limit: Int, hours: Double): List<NewsItem> = emptyList()
            override suspend fun saveCache(items: List<NewsItem>) = Unit
            override suspend fun loadCache(): List<NewsItem>? = null
        })
        rule.setContent {
            val host by owner.graphHost.collectAsState()
            PremiumTopicRoute(consumer = owner.consumer, identity = a1, isActive = true, newsViewModel = vm, userInfo = null,
                onSignOut = { }, graphHost = host)
        }
        rule.waitForIdle()
        openConnection()
    }

    /**
     * CC5-3-D3-1: the host the owner installs while the process is already in the foreground, its seed arriving late, is
     * built only after the initial hand-over gave the recovery events that foreground; until then the starter keeps it.
     */
    @Test fun cc53_D3_1_aHostInstalledLateInTheForegroundIsExposedOnlyAfterTheHandOver() {
        startAndShow()
        assertEquals("CC5-3-D3-1 premise: the process foreground reached the owner at registration", "fg:true:begin", logged().first())
        rule.waitForIdle()
        assertNull("CC5-3-D3-1 premise: no host before the seed", onMain { owner.graphHost.value })
        assertTrue("CC5-3-D3-1 premise: nothing shown before the host", "show" !in logged())
        assertNull("CC5-3-D3-1 the foreground is kept, not forwarded, before the hand-over", onMain { eventsForeground() })
        seedGate.countDown()
        until("CC5-3-D3-1 the usd surface is shown") { "show" in logged() }
        val events = logged()
        assertEquals("CC5-3-D3-1 one host, built with the hand-over's foreground already delivered", listOf("host:true"),
            events.filter { it.startsWith("host:") })
        assertEquals("CC5-3-D3-1 one foreground delivery, at registration, before the host: $events", 1,
            events.subList(0, events.indexOf("host:true")).count { it == "fg:true:begin" })
    }

    /**
     * CC5-3-D3-2: a return from the background is handled by the real process foreground path — its time event included,
     * through the recovery events and the host to the usd holder — and completes before the route's surface re-exposure
     * flush, which the activity's own lifecycle start drives afterwards. Measured on API 32 (Pixel_3a emulator).
     */
    @Test fun cc53_D3_2_aProcessReturnCompletesBeforeTheSurfaceReExposureFlush() {
        startAndShow()
        seedGate.countDown()
        until("CC5-3-D3-2 premise: the usd surface is shown") { "show" in logged() }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        until("CC5-3-D3-2 premise: the surface was hidden and the process left the foreground", millis = 10_000) {
            logged().let { "hide" in it && "fg:false:end" in it }
        }
        val mark = logged().size
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        until("CC5-3-D3-2 the surface is shown again", millis = 10_000) { "show" in logged().drop(mark) }
        val after = logged().drop(mark)
        val begin = after.indexOf("fg:true:begin")
        val end = after.indexOf("fg:true:end")
        val show = after.indexOf("show")
        assertTrue("CC5-3-D3-2 the return was delivered: $after", begin >= 0 && end > begin)
        assertEquals("CC5-3-D3-2 one return, the platform's — the route synthesizes none: $after", 1, after.count { it == "fg:true:begin" })
        assertTrue("CC5-3-D3-2 the return's time event reached the usd holder through the host inside its handling: $after",
            after.subList(begin, end).containsAll(listOf("time", "time:usd")))
        assertTrue("CC5-3-D3-2 the return completed before the re-exposure flush: $after", end < show)
        assertTrue("CC5-3-D3-2 the re-exposure came from the surface's lifecycle start", showFromLifecycle.last())
    }
}
