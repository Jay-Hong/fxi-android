package com.jay.fxi.data.remote

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.auth.AuthCredentialRecoveryStream
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.auth.AuthUnavailableException
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantIssuer
import com.jay.fxi.data.entitlements.TopicGrantResult
import com.jay.fxi.data.entitlements.TopicRejectionLedger
import com.jay.fxi.data.entitlements.TopicRejectionReservation
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.data.local.TopicLastKnownStore
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionAckTopic
import com.jay.fxi.data.remote.dto.SubscriptionRejection
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Holds every task dispatched to it until [drain]: the owner's Main in the C4 harness, so a test can tell work done inside a
 * callback from work enqueued for Main. Never immediate.
 */
internal class ManualMain : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()
    var dispatched = 0
        private set
    val idle: Boolean get() = queue.isEmpty()
    private val executing = ThreadLocal.withInitial { false }
    val isExecuting: Boolean get() = executing.get()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched += 1
        queue.addLast(block)
    }

    /** Runs the queued tasks, and those they queue, until none is left; returns how many ran. */
    fun drain(): Int {
        var ran = 0
        while (true) {
            val next = queue.removeFirstOrNull() ?: return ran
            val previous = executing.get()
            executing.set(true)
            try {
                next.run()
            } finally {
                executing.set(previous)
            }
            ran += 1
        }
    }
}

/** An auth fence stream that delivers the current fence on subscription and every [emit] to every subscriber, synchronously. */
internal class C4Fences(var current: AuthIdentityFence?) {
    private val observers = mutableListOf<(AuthIdentityFence?) -> Unit>()
    val subscribers: Int get() = observers.size
    val stream = AuthFenceStream { onFence -> observers += onFence; onFence(current) }

    fun emit(next: AuthIdentityFence?) {
        current = next
        observers.toList().forEach { it(next) }
    }
}

internal class C4Wire(private val url: String) : WebSocket.Factory {
    val requests = mutableListOf<Request>()
    var listener: WebSocketListener? = null
    private val socket = object : WebSocket {
        override fun cancel() = Unit
        override fun close(code: Int, reason: String?): Boolean = true
        override fun queueSize() = 0L
        override fun request() = Request.Builder().url(url).build()
        override fun send(text: String): Boolean {
            if (text == "ping") listener?.onMessage(this, """{"type":"pong"}""")
            return true
        }
        override fun send(bytes: okio.ByteString) = true
    }
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        requests += request
        this.listener = listener
        return socket
    }
    fun open() = listener!!.onOpen(
        socket,
        Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("").build()
    )
    fun drop() = listener!!.onFailure(socket, java.io.IOException("dropped"), null)
    fun deliver(text: String) = listener!!.onMessage(socket, text)
}

internal class C4Issuer : TopicGrantIssuer {
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

internal class C4Tabs : FreeTabStore {
    val stored = mutableMapOf<String, FreeTab>()
    var gate: CompletableDeferred<Unit>? = null
    val reads = mutableListOf<String>()
    val remembered = mutableListOf<Pair<String, FreeTab>>()
    override suspend fun lastTab(uid: String): FreeTab {
        reads += uid
        gate?.await()
        return stored[uid] ?: FreeTab.USD
    }
    override suspend fun remember(uid: String, tab: FreeTab) {
        remembered += uid to tab
        stored[uid] = tab
    }
}

/** The row preference store: every read and the job it ran in, every cancelled read, and every save as it reaches the store. */
internal class C4Prefs : RateRowPreferenceStore {
    val stored = mutableMapOf<String, Map<RateRowList, RateRowPreference>>()
    val reads = mutableListOf<String>()
    val readJobs = mutableListOf<Job>()
    val cancelled = mutableListOf<String>()
    /** While set, every read parks on it. */
    var readGate: CompletableDeferred<Unit>? = null
    val remembered = mutableListOf<Pair<String, RateRowList>>()
    /** The next save parks on it, after it has been recorded as reaching the store. */
    var rememberGate: CompletableDeferred<Unit>? = null

    override suspend fun preferences(uid: String): Map<RateRowList, RateRowPreference> {
        reads += uid
        readJobs += checkNotNull(currentCoroutineContext()[Job])
        try {
            readGate?.await()
        } catch (e: CancellationException) {
            cancelled += uid
            throw e
        }
        return stored[uid].orEmpty()
    }

    override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) {
        remembered += uid to list
        val gate = rememberGate
        rememberGate = null
        gate?.await()
        stored[uid] = stored[uid].orEmpty() + (list to preference)
    }
}

/** An in-memory Preferences store for the last-known seed, counting reads and writes. */
internal class C4MemoryPrefs : DataStore<Preferences> {
    val state = MutableStateFlow(emptyPreferences())
    var reads = 0
    var writes = 0
    override val data: Flow<Preferences> get() = flow { reads += 1; emit(state.value) }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { writes += 1; state.value = it }
}

/**
 * Claude-owned R4-c C4 harness (R4c/C4/design_codex.r3.md): the production [TopicRuntimeFactory], [TopicRuntime],
 * [TopicRuntimeOwner] and premium consumer, joined as the app joins them. Only auth, server, stores, clock and platform inputs
 * are replaced; no refusal or display is assigned directly. The runtime's scope runs on the test scheduler; the owner's Main
 * is a [ManualMain] that [settle] drains. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class C4OwnerHarness(
    private val test: TestScope,
    live: AuthIdentityFence? = U1,
    online: Boolean = false,
    withLastKnown: Boolean = false,
    /** The last-known file; a new one unless a previous process's is handed over. */
    val memory: C4MemoryPrefs = C4MemoryPrefs(),
    issuer: TopicGrantIssuer? = null,
    authority: TopicUseAuthority? = null,
    /** The first runtime creation throws, as a failing owner start would. */
    failFirstCreate: Boolean = false
) {
    companion object {
        const val URL = "http://localhost/ws"
        val U1 = AuthIdentityFence("u1", 1L)
        val F1 = TopicSessionFence(U1, "epoch-1", TopicGrantToken(1L))
        const val TETHER = TopicCatalogue.TETHER
        const val USD = "fx:usd-krw"
        val decoder = TopicFrameDecoder(Json { ignoreUnknownKeys = true })

        fun tetherFrame(rate: Double, timestamp: String = "2026-08-31T10:20:00+09:00") =
            """{"type":"snapshot","version":1,"topic":"usdt:krw","data":{"usdt_krw":[
               {"source":"upbit","asset":"usdt-krw","rate":$rate,"timestamp":"$timestamp"}],"usd_krw_banks":[]}}"""

        /** A v1 `rates` broadcast, as the legacy server path sends it. */
        const val LEGACY_RATES_FRAME = """{"type":"rates","data":{"rates":[
            {"currency":"usd-krw","bank":"kb","rate":1401.5,"timestamp":"2026-08-31T10:20:00+09:00"},
            {"currency":"usd-krw","bank":"investing","rate":1400.0,"timestamp":"2026-08-31T10:20:00+09:00"}],
            "indices":{"dxy":{"rate":99.2,"timestamp":"2026-08-31T10:20:00+09:00","source":"investing"}},
            "metadata":{"updated_at":"2026-08-31T10:20:00+09:00","currencies":["usd-krw"],"banks":["kb"],"total_count":2}}}"""

        fun ack(requestId: String, active: List<String>, rejections: Map<String, String>) =
            Json.encodeToString(
                SubscriptionAck.serializer(),
                SubscriptionAck(
                    requestId = requestId,
                    operation = "subscribe",
                    acceptedTopics = active.map { SubscriptionAckTopic(it) },
                    rejectedTopics = rejections.map { SubscriptionRejection(it.key, it.value) },
                    removedTopics = emptyList(),
                    activeSubscriptions = active.map { SubscriptionAckTopic(it) }
                )
            ).replace("""{"request_id""", """{"type":"subscription_ack","request_id""")
    }

    val scheduler = test.testScheduler
    private val parent = test.backgroundScope.coroutineContext[Job]
    val main = ManualMain()
    val consumerIdentityReadsOnMain = mutableListOf<Boolean>()
    var live: AuthIdentityFence? = live
    val fences = C4Fences(live)
    val online = MutableStateFlow(online)
    val foregroundObservers = mutableListOf<(Boolean) -> Unit>()
    val wire = C4Wire(URL)
    /** The grant source when no issuer is handed in; it grants [F1]. */
    val fakeIssuer = C4Issuer().also { it.grant = F1 }
    val tabs = C4Tabs()
    val prefs = C4Prefs()
    /** One per runtime the factory created. */
    val scopes = mutableListOf<CoroutineScope>()
    val subscribes = mutableListOf<TopicSubscribeRequest>()
    val bootstrapCalls = mutableListOf<Pair<AuthIdentityFence, String>>()
    var bootstrapOutcome: (String) -> TopicSnapshotOutcome = { TopicSnapshotOutcome.Degraded }
    /** Bootstraps of these topics park on their gate before answering. */
    val bootstrapGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var ids = 0
    private var createFailures = if (failFirstCreate) 1 else 0
    private val clock = object : TopicCommandClock {
        override fun nowMillis(): Long = scheduler.currentTime
        override suspend fun sleep(duration: Duration) = delay(duration)
    }

    fun lastKnownOver(prefs: DataStore<Preferences>) = TopicLastKnownStore(prefs, { scheduler.currentTime }, 300L,
        CoroutineScope(SupervisorJob(parent) + StandardTestDispatcher(scheduler)))

    val factory = TopicRuntimeFactory(
        webSocketFactory = { wire },
        webSocketUrl = URL,
        decode = decoder::decode,
        bootstrap = { owner, topic, _ ->
            bootstrapCalls += owner to topic
            bootstrapGates[topic]?.await()
            bootstrapOutcome(topic)
        },
        issuer = issuer ?: fakeIssuer,
        fences = fences.stream,
        liveFence = { this.live },
        recoveries = AuthCredentialRecoveryStream { },
        tabs = tabs,
        credentials = object : TopicCommandCredentials {
            override suspend fun currentSnapshot(): AuthSnapshot {
                val identity = this@C4OwnerHarness.live ?: throw AuthUnavailableException("signed out")
                return AuthSnapshot(identity.uid, identity.authGeneration, "token-${identity.uid}")
            }
            override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? = null
            override suspend fun recordRejected(credential: AuthSnapshot) = Unit
            override suspend fun recordRejectionEvidence(credential: AuthSnapshot) = Unit
        },
        orders = AccessOrderSequence(),
        authority = authority ?: object : TopicUseAuthority {
            override fun acquire(fence: TopicSessionFence) = TopicUseLifetime(fence.grant, 0L)
            override fun admits(lifetime: TopicUseLifetime) = true
        },
        clock = clock,
        newBootstrapFloor = {
            object : TopicBootstrapFloor {
                override fun record(statusCode: Int?, retryAfter: String?) = Unit
                override fun notBeforeMillis(): Long = 0L
            }
        },
        newScope = {
            if (createFailures > 0) {
                createFailures -= 1
                throw IllegalStateException("runtime creation failed")
            }
            CoroutineScope(SupervisorJob(parent) + StandardTestDispatcher(scheduler)).also { scopes += it }
        },
        encode = { request -> subscribes += request; Json.encodeToString(TopicSubscribeRequest.serializer(), request) },
        newRequestId = { "r${++ids}" },
        jitter = { 0.0 },
        lastKnown = if (withLastKnown) lastKnownOver(memory) else null
    )

    val owner = TopicRuntimeOwner(
        factory = factory,
        online = this.online,
        foreground = TopicForegroundStream { foregroundObservers += it },
        fences = fences.stream,
        liveIdentity = {
            consumerIdentityReadsOnMain += main.isExecuting
            this.live
        },
        rowPreferenceStore = prefs,
        main = CoroutineScope(SupervisorJob() + main)
    )

    /** The platform reporting the process coming to (true) or leaving (false) the foreground. */
    fun foreground(value: Boolean) = foregroundObservers.toList().forEach { it(value) }

    /** Firebase moving to [next]: the live read changes, then the stream delivers it. */
    fun signIn(next: AuthIdentityFence?) {
        live = next
        fences.emit(next)
    }

    /** Advances the clock by [millis], then runs the scheduler and Main in turns until neither has anything due now. */
    fun settle(millis: Long = 0) {
        if (millis > 0) test.advanceTimeBy(millis)
        repeat(1_000) {
            test.runCurrent()
            if (main.drain() == 0) {
                test.runCurrent()
                if (main.idle) return
            }
        }
        error("the scheduler and Main did not settle")
    }

    fun asked(): List<String> = bootstrapCalls.map { it.second }

    val screen: PremiumTopicScreenState get() = owner.consumer.state.value
}
