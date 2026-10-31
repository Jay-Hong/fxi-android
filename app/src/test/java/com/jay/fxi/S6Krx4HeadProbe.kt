package com.jay.fxi

import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.remote.*
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.*
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.PremiumTopicScreenBanner
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import retrofit2.Retrofit
import kotlin.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

/** This entire source, including the driver, uses only APIs present in 4f4cc635. */
@OptIn(ExperimentalCoroutinesApi::class)
class S6Krx4HeadProbe {
    @Test fun writeHeadTraceOnlyWhenRequested() {
        val destination = System.getenv("S6_4A_HEAD_TRACE_OUT") ?: return
        val traces = COLUMNS.associateWith { trace(it) }
        val result = buildJsonObject { put("schema", SCHEMA); put("head", HEAD); put("variant", BuildConfig.BUILD_TYPE)
            put("raw_columns", JsonObject(traces)); put("columns", JsonObject(traces.mapValues { normalize(it.value) })) }
        Files.write(Paths.get(destination), result.toString().toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
    companion object {
        const val SCHEMA = "s64a-head-v2"
        const val HEAD = "4f4cc635f409b750e703119b0664cfd40ede639c"
        val D01_CASES = listOf(FreeTab.USD,FreeTab.TETHER).flatMap { tab -> listOf("before","after").flatMap { at -> listOf("false","true","pending").map { "$tab/$at/$it" } } }
        val COLUMNS = listOf("USD", "TETHER", "NEWS", "raw-flips", "pending", "late-frame", "failed-send", "reconnect", "manual", "uid", "floor-general-body", "refusal-reapproval", "withdrawal") + D01_CASES.map { "D01/$it" }
        /** Only opaque IDs are aliased. Traversal and all arrays retain their original order. */
        internal fun normalize(raw: JsonElement): JsonElement {
            val epochs = linkedMapOf<String,String>(); val grants = linkedMapOf<String,String>()
            fun walk(node: JsonElement, key: String = ""): JsonElement = when (node) {
                is JsonObject -> JsonObject(node.mapValues { (name,value) -> walk(value,name) })
                is JsonArray -> JsonArray(node.map { walk(it,key) })
                JsonNull -> JsonNull
                else -> if (key in setOf("userEpoch","capabilityEpoch","userEndNamespace","capabilityEndNamespace","issuedUserEpoch","issuedCapabilityEpoch","grant")) {
                    val value = node.jsonPrimitive.content
                    val table = if (key == "grant") grants else epochs
                    JsonPrimitive(table.getOrPut(value) { "${if (key == "grant") "grant" else "epoch"}@${table.size + 1}" })
                } else node
            }
            return walk(raw)
        }
        /** The exact OFF matrix driver is shared with D01; observer adds assertions only. */
        internal suspend fun exerciseOffD01(w: S64HeadWorld,sub: String,observe: (String) -> Unit = {}) {
            val (_,at,raw) = sub.split('/')
            if (at == "before") w.approve(raw == "true") else { w.approve(false); w.start(); w.open(); w.ackAll() }
            var gate: CompletableDeferred<EntitlementsOutcome>? = null; var pending: Job? = null
            if (raw == "pending") {
                gate = CompletableDeferred(); w.h.source.gates += gate
                pending = w.test.launch { w.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }; w.drain()
                assertTrue("precondition(${w.row}/pending): real query blocked",!pending.isCompleted)
            } else if (at == "after") w.refresh(raw == "true")
            if (at == "before" && gate != null) {
                gate.complete(EntitlementsOutcome.Pending(krxVisible=true,retryAfterSeconds=1)); pending?.join(); w.drain()
                observe("pending-response-before"); w.checkpoint("pending-response-before")
            }
            observe("before-start"); w.checkpoint("before-start")
            if (at == "before") { w.start(); w.open(); w.ackAll() }
            observe("started"); w.checkpoint("started")
            if (at == "after" && gate != null) {
                gate.complete(EntitlementsOutcome.Pending(krxVisible=true,retryAfterSeconds=1)); pending?.join(); w.drain()
                observe("pending-response-after"); w.checkpoint("pending-response-after")
            }
            repeat(3) {
                w.refresh(it % 2 == 0); w.runtime.setOnline(false); w.drain()
                w.runtime.selectTab(w.identity,w.tab); w.runtime.setOnline(true); w.drain()
                observe("repeat-$it"); w.checkpoint("repeat-$it")
            }
            w.advance(3_000); observe("final"); w.checkpoint("final")
        }
        internal fun trace(column: String, adapter: (S64HeadWorld, TopicRuntimeFactory) -> Unit = { _, _ -> }): JsonElement {
            var result: JsonElement = JsonNull
            runTest {
                val tab = when { column.startsWith("D01/") -> FreeTab.valueOf(column.split('/')[1]); column == "TETHER" -> FreeTab.TETHER; column == "NEWS" -> FreeTab.NEWS; else -> FreeTab.USD }
                val w = S64HeadWorld(this, "O41/$column", tab = tab, configure = adapter)
                try {
                    if (column.startsWith("D01/")) {
                        exerciseOffD01(w,column.removePrefix("D01/"))
                        result = w.trace()
                    } else {
                    if (column == "floor-general-body") { w.floor = 1_000; w.bodyHold = S64HeadWorld.G }
                    w.approve(false); w.checkpoint("approved-before-start")
                    w.start(); w.checkpoint("started-before-socket"); w.open()
                    w.ackAll(if (column == "manual") mapOf("fx:usd-krw" to "topics_disabled") else emptyMap())
                    w.advance(3_000); if (column != "floor-general-body") w.finishAll(); w.checkpoint("cold")
                    when (column) {
                        "raw-flips" -> for (visible in listOf(true, true, true, false)) { w.refresh(visible); w.checkpoint("raw=$visible") }
                        "pending" -> {
                            val gate = CompletableDeferred<EntitlementsOutcome>(); w.h.source.gates += gate
                            val pending = launch { w.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS) }; runCurrent()
                            w.checkpoint("pending"); gate.complete(EntitlementsOutcome.StableActive(true)); pending.join()
                            w.checkpoint("resolved")
                        }
                        "late-frame" -> { w.wire.deliver(S64HeadWorld.krxFrame(9876.0)); w.drain(); w.checkpoint("late-K") }
                        "failed-send", "reconnect" -> {
                            w.failNextWire = if (column == "failed-send") 1 else 0
                            w.wire.drop(); w.until("new socket", 3_000) { w.wires.size == 2 }; w.open(); w.ackAll()
                        }
                        "manual" -> {
                            assertTrue("precondition(O41/manual): actual manual rejection", "fx:usd-krw" in w.runtime.display.value.topicState.manualRetryTopics)
                            val before = w.sends.size
                            w.runtime.retryTopics(checkNotNull(w.runtime.display.value.owner),FreeTab.USD); w.drain()
                            assertTrue("precondition(O41/manual): actual successful manual subscribe",w.sends.drop(before).any { it.second && "fx:usd-krw" in it.first.topics })
                            w.ackAll()
                        }
                        "uid" -> { w.changeUid("user-b"); w.approve(true); w.drain() }
                        "refusal-reapproval" -> {
                            val oldOwner = checkNotNull(w.runtime.display.value.owner) { "precondition(O41/refusal-reapproval): original runtime grant" }
                            val old = checkNotNull(w.pac.topicGrantResult().fence) { "precondition(O41/refusal-reapproval): original issued fence" }
                            val live = w.h.source.identity
                            assertNotNull("precondition(O41/refusal-reapproval): original readable identity",live)
                            val queries = w.h.source.calls.size
                            // HEAD's readable PREMIUM_REQUIRED is authoritative loss, not same-context reapproval.
                            // Only the issuer's endpoint identity read fails; runtime/liveFence remain unchanged.
                            assertEquals("precondition(O41/refusal-reapproval): no previous identity-read failure",0,w.h.source.identityNulls)
                            w.h.source.identityNulls = 1
                            w.pac.onTopicRejected(old.grant,listOf(TopicRejectionReason.PREMIUM_REQUIRED))
                            assertEquals("precondition(O41/refusal-reapproval): issuer live read consumed once",0,w.h.source.identityNulls)
                            assertEquals("precondition(O41/refusal-reapproval): source identity unchanged",live,w.h.source.identity)
                            w.drain()
                            val refusal = w.pac.rejectionView(old.grant)
                            assertTrue("precondition(O41/refusal-reapproval): actual refusal taken over",refusal.pendingOrders.isEmpty() && refusal.latestCurrent != null)
                            assertEquals("precondition(O41/refusal-reapproval): premium query owed",RefreshIntent.FORCE_PREMIUM,w.pac.recheckDiagnostics().owedIntent)
                            assertTrue("precondition(O41/refusal-reapproval): held original grant still standing",w.snapshot.facts.tokenStanding && w.snapshot.facts.token == old.grant)
                            w.checkpoint("refusal-held")
                            val floor = PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS
                            w.advance(floor - 1)
                            assertEquals("precondition(O41/refusal-reapproval): no query before default floor",queries,w.h.source.calls.size)
                            w.advance(1)
                            w.until("new refusal grant",3_000) { w.snapshot.facts.token != old.grant && w.runtime.display.value.owner != null && w.runtime.display.value.owner != oldOwner }
                            val result = w.pac.topicGrantResult()
                            val reapproved = checkNotNull(result.fence) { "precondition(O41/refusal-reapproval): replacement issued fence" }
                            val cause = result.cause as? TopicGrantCause.RefusalReapproval
                            assertNotNull("precondition(O41/refusal-reapproval): real issue cause",cause)
                            assertEquals("precondition(O41/refusal-reapproval): original refusal grant",old.grant,cause!!.from)
                            assertEquals("precondition(O41/refusal-reapproval): original refusal order",refusal.latestCurrent,cause.refusalOrder)
                            assertTrue("precondition(O41/refusal-reapproval): query starts after reported refusal",cause.queryOrder > checkNotNull(refusal.latestReported))
                            assertEquals("precondition(O41/refusal-reapproval): same identity",old.identity,reapproved.identity)
                            assertEquals("precondition(O41/refusal-reapproval): same user epoch",old.userAccessEpoch,reapproved.userAccessEpoch)
                            assertTrue("precondition(O41/refusal-reapproval): actual fresh premium query",w.h.source.calls.drop(queries).any { it.freshPremium })
                            w.checkpoint("reapproved")
                        }
                        "withdrawal" -> {
                            assertNotNull("precondition(O41/withdrawal): original runtime grant",w.runtime.display.value.owner)
                            w.h.source.next = { EntitlementsOutcome.StableInactive(false) }
                            w.pac.refresh(RefreshIntent.FORCE_PREMIUM); w.drain()
                            w.until("grant withdrawn",3_000) { !w.snapshot.facts.userAllowed && !w.snapshot.facts.tokenStanding && w.runtime.display.value.owner == null }
                            w.checkpoint("withdrawn")
                        }
                        "floor-general-body" -> {
                            assertTrue("precondition(O41/floor-general-body): real G body held",w.calls.any { it.topic in S64HeadWorld.G && it.physicallySent != null && it.bodyEnd == null })
                            w.checkpoint("held-G-body"); w.finishAll()
                        }
                    }
                    w.advance(3_000); w.finishAll(); w.checkpoint("final")
                    result = w.trace()
                    }
                } finally { w.close() }
            }
            return result
        }
    }
}

/** Endpoint, credentials and clock doubles only. Grants, publication, use and retirement are production objects. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S64HeadWorld(
    val test: TestScope,
    val row: String,
    val tab: FreeTab = FreeTab.USD,
    pacFactory: S64HeadPacFactory = S64HeadPacFactory { p -> PremiumAccessCoordinator(
        source = p.source, store = p.store, userPurger = p.purger, capabilityPurger = p.purger,
        scope = p.scope, clock = p.clock, liveFence = { p.source.liveFence }, orders = p.orders,
        jitter = ProbeJitter.None, krxContest = KrxContestSwitch.OFF) },
    configure: (S64HeadWorld, TopicRuntimeFactory) -> Unit = { _, _ -> },
    private val pacDispatcher: CoroutineDispatcher? = null
) : AutoCloseable {
    private val job = SupervisorJob()
    private val persistenceDirectory = Files.createTempDirectory("s64a-access-").toFile()
    private val parts = S64HeadParts(
        scope = CoroutineScope(job + (pacDispatcher ?: StandardTestDispatcher(test.testScheduler))),
        clock = RecheckClock { test.testScheduler.currentTime },
        source = KrxHarnessSource({ test.testScheduler.currentTime }) { EntitlementsOutcome.StableActive(true) },
        orders = AccessOrderSequence(), purger = UnimplementedScopePurger(),
        store = DataStoreAccessEpochStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + (pacDispatcher ?: StandardTestDispatcher(test.testScheduler)))) {
                java.io.File(persistenceDirectory, "access.preferences_pb")
            }, EpochIdGenerator.Random))
    val h = S64HeadHarness(parts, pacFactory.create(parts))
    val pac get() = h.coordinator
    val snapshot get() = pac.accessSnapshot
    var identity = AuthIdentityFence("user-a", 1)
    private var fenceCallback: ((AuthIdentityFence?) -> Unit)? = null
    private var recoveryCallback: ((AuthCredentialRecovery) -> Unit)? = null
    val reads = mutableListOf<TopicAccessSnapshot>()
    val memoryReads = mutableListOf<TopicAccessSnapshot>()
    val uses = SnapshotTopicUseAuthority { snapshot.also { synchronized(reads) { reads += it } } }
    val reader: KrxSendReadAuthority = uses
    val runtimeScope = CoroutineScope(SupervisorJob(job) + StandardTestDispatcher(test.testScheduler))
    val tokenScope = CoroutineScope(job + StandardTestDispatcher(test.testScheduler))
    data class Sleep(val at: Long, val millis: Long, val job: Job?, var cancelled: Boolean = false, var returned: Boolean = false)
    val sleeps = mutableListOf<Sleep>()
    val clock = object : TopicCommandClock {
        override fun nowMillis() = test.testScheduler.currentTime
        override suspend fun sleep(duration: Duration) {
            val sleep = Sleep(nowMillis(), duration.inWholeMilliseconds, currentCoroutineContext()[Job]).also { sleeps += it }
            try { delay(duration); sleep.returned = true } catch (cancelled: CancellationException) { sleep.cancelled = true; throw cancelled }
        }
    }
    val decoder = TopicFrameDecoder(Json { ignoreUnknownKeys = true })
    private val wireJson = Json { ignoreUnknownKeys = true }
    val encoded = mutableListOf<TopicSubscribeRequest>()
    val outgoingFrames = mutableListOf<JsonElement>()
    val sends = mutableListOf<Triple<TopicSubscribeRequest, Boolean, Long>>()
    val sendPublications = mutableListOf<TopicAccessSnapshot>()
    val controls = mutableListOf<Pair<String, Long>>()
    val wires = mutableListOf<KrxWire>()
    val wire get() = wires.last()
    var failNextWire = 0
    var credentialGate: CompletableDeferred<Unit>? = null
    var credentialFailure: AuthUnavailableException? = null
    var credentialReads = 0
    private val floorState = TopicBootstrapRetryFloor(clock) { kotlinx.datetime.Instant.fromEpochMilliseconds(1_790_385_600_000L + clock.nowMillis()) }
    var floor: Long
        get() = floorState.notBeforeMillis()
        set(value) {
            val delta = value - clock.nowMillis()
            require(delta >= 0 && delta % 1_000L == 0L) { "precondition($row/floor): exact nonnegative Retry-After seconds" }
            floorState.record(429,(delta/1_000L).toString())
        }
    var tokenHold: Set<String> = emptySet()
    var bodyHold: Set<String> = emptySet()
    var bootstrapEndpoint: (suspend (AuthIdentityFence, String, () -> Boolean) -> TopicSnapshotOutcome)? = null
    val calls = mutableListOf<Call>()
    val graph = mutableListOf<TopicGraphInput>()
    data class Call(val topic: String, val owner: AuthIdentityFence, val issuedAt: Long,
        val publication: TopicAccessSnapshot, val admitted: () -> Boolean, val grantEpoch: Long?,
        val token: CompletableDeferred<Unit> = CompletableDeferred(),
        val body: CompletableDeferred<String> = CompletableDeferred(),
        var physicallySent: Long? = null, var bodyEnd: Long? = null, var cancelled: Boolean = false,
        var outcome: TopicSnapshotOutcome? = null, var cancellation: CancellationException? = null,
        var responseBody: String? = null, val exchanges: MutableList<Exchange> = mutableListOf())
    data class Exchange(val method: String,val path: String,val query: Map<String,String>,val at: Long,val status: Int)
    private var serial = 0
    private val credentials = object : TopicCommandCredentials {
        override suspend fun currentSnapshot(): AuthSnapshot {
            credentialReads++
            credentialGate?.await()
            credentialFailure?.let { throw it }
            return AuthSnapshot(identity.uid, identity.authGeneration, "local-credential")
        }
        override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? = null
        override suspend fun recordRejected(credential: AuthSnapshot) = Unit
        override suspend fun recordRejectionEvidence(credential: AuthSnapshot) = Unit
    }
    private val blockedApi by lazy { Retrofit.Builder().baseUrl("https://fixture.invalid/")
        .client(OkHttpClient.Builder().addInterceptor { throw AssertionError("precondition($row/HEAD-HTTP): unexpected delegated endpoint") }.build())
        .build().create(AuthenticatedApiService::class.java) }
    private suspend fun defaultBootstrap(call: Call): TopicSnapshotOutcome {
        val provider = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity(identity.uid,identity.authGeneration)
            override suspend fun fetchToken(identity: AuthIdentity,forceRefresh: Boolean): String {
                if (call.topic !in tokenHold) call.token.complete(Unit)
                call.token.await(); return "s64-local-token"
            }
        },processScope = tokenScope,orders = h.parts.orders)
        val endpoint = object : AuthenticatedApiService by blockedApi {
            override suspend fun getTopicSnapshot(auth: AuthRequestTag,topic: String): retrofit2.Response<ResponseBody> {
                auth.use?.let { if (!it.context.admitted()) throw TopicUseWithheldException(it.context.observed()) }
                call.physicallySent = clock.nowMillis()
                val request = okhttp3.Request.Builder().url("https://fixture.invalid/api/v2/topics/snapshot?topic=$topic").get().build()
                call.exchanges += Exchange(request.method,request.url.encodedPath,mapOf("topic" to checkNotNull(request.url.queryParameter("topic"))),clock.nowMillis(),200)
                if (topic !in bodyHold) call.body.complete(frame(topic,1555.0))
                val raw = call.body.await().also { call.responseBody = it }.toResponseBody("application/json".toMediaType())
                val observed = object : ResponseBody() {
                    override fun contentType() = raw.contentType()
                    override fun contentLength() = raw.contentLength()
                    private val buffered by lazy { object : okio.ForwardingSource(raw.source()) {
                        override fun read(sink: okio.Buffer,byteCount: Long) = super.read(sink,byteCount).also { if (it == -1L) call.bodyEnd = clock.nowMillis() }
                        override fun close() { try { super.close() } finally { call.bodyEnd = clock.nowMillis() } }
                    }.buffer() }
                    override fun source(): okio.BufferedSource = buffered
                }
                val response = okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("s64 endpoint").build()
                return retrofit2.Response.success<ResponseBody>(observed,response)
            }
        }
        val client = AuthenticatedApiClient(endpoint,AuthenticatedTransport(provider) { true },Json { ignoreUnknownKeys = true })
        return TopicSnapshotBootstrapService(client,decoder).bootstrap(call.owner,call.topic,call.admitted)
    }
    val issuer = RecordingTopicGrantIssuer(PremiumAccessTopicGrantIssuer(pac))
    private val factory = TopicRuntimeFactory(
        webSocketFactory = { KrxWire { text, accepted ->
            val request = runCatching { wireJson.decodeFromString<TopicSubscribeRequest>(text) }.getOrNull()
            if (request == null) controls += text to clock.nowMillis()
            else { sends += Triple(request,accepted,clock.nowMillis()); outgoingFrames += Json.parseToJsonElement(text); sendPublications += snapshot }
        }.also { it.failSends = failNextWire; failNextWire = 0; wires += it } },
        webSocketUrl = "wss://fixture.invalid/ws", decode = decoder::decode,
        bootstrap = { owner, topic, admitted ->
            val call = Call(topic, owner, clock.nowMillis(), snapshot, admitted, callbackEpoch()).also { calls += it }
            try {
                val endpoint = bootstrapEndpoint
                if (endpoint != null) {
                    endpoint(owner, topic, admitted).also { call.outcome = it }
                } else {
                defaultBootstrap(call).also { call.outcome = it }
                }
            } catch (cancelled: CancellationException) { call.cancelled = true; call.cancellation = cancelled; throw cancelled }
        },
        issuer = issuer,
        fences = AuthFenceStream { callback -> fenceCallback = callback; callback(identity) },
        liveFence = { identity }, recoveries = AuthCredentialRecoveryStream { recoveryCallback = it },
        tabs = object : FreeTabStore {
            override suspend fun lastTab(uid: String) = tab
            override suspend fun remember(uid: String, tab: FreeTab) = Unit
        }, credentials = credentials, orders = h.parts.orders, authority = reader, clock = clock,
        newBootstrapFloor = { floorState }, newScope = { runtimeScope },
        encode = { request -> encoded += request; wireJson.encodeToString(request) },
        newRequestId = { "request-${++serial}" }, jitter = { 0.0 },
        krxSendReader = reader, krxMemorySnapshot = { snapshot.also { memoryReads += it } }
    )
    val runtime: TopicRuntime
    init {
        h.source.liveFence = identity
        configure(this, factory)
        runtime = factory.create(graphSink = TopicGraphSink { graph += it; TopicGraphOffer.ENQUEUED })
    }
    private fun callbackEpoch() = runtime.display.value.owner?.grantEpoch
    val desired get() = runtime.display.value.topicState.topics.filterValues { it.desired }.keys
    val krxCalls get() = calls.filter { it.topic == K }
    val krxSends get() = sends.filter { K in it.first.topics && it.second }
    suspend fun approve(visible: Boolean) {
        h.source.next = { EntitlementsOutcome.StableActive(visible) }
        pac.onIdentityChanged(identity).applied()
        pac.refresh(RefreshIntent.FORCE_PREMIUM); drain()
        until("PAC GENERAL allowed", 500) { snapshot.facts.userAllowed }
    }
    suspend fun publish(visible: Boolean) {
        h.source.next = { EntitlementsOutcome.StableActive(visible) }
        pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
    }
    suspend fun refresh(visible: Boolean) { publish(visible); drain() }
    fun start() {
        runtime.start(); runtime.setOnline(true); runtime.setForeground(true); drain()
        until("normal deliverer GENERAL standing and owner",500) { snapshot.facts.userAllowed && snapshot.facts.tokenStanding && runtime.display.value.owner != null }
        until("socket",500) { wires.isNotEmpty() }
    }
    fun open() { wire.open(); drain() }
    private val acked = mutableSetOf<String>()
    fun ackAll(rejected: Map<String, String> = emptyMap(), seconds: Long = 600) {
        repeat(3) {
            for ((request, sent, _) in sends.toList()) if (sent && request.type == "subscribe" && acked.add(request.requestId)) {
                ackOn(wire,request,rejected,seconds)
            }
        }
    }
    fun ackOn(target: KrxWire,request: TopicSubscribeRequest,rejected: Map<String,String> = emptyMap(),seconds: Long = 600,drainAfter: Boolean = true,extraRejected: Map<String,String> = emptyMap()) {
        val accepted = request.topics.filter { it !in rejected }
        fun entries(topics: List<String>) = topics.joinToString(",") { """{"topic":"$it","lease_id":"${request.requestId}-$it","lease_duration_seconds":$seconds}""" }
        val refusals = (rejected.filterKeys { it in request.topics } + extraRejected).entries.joinToString(",") { (topic,error) -> """{"topic":"$topic","error":"$error"}""" }
        target.deliver("""{"type":"subscription_ack","request_id":"${request.requestId}","operation":"subscribe","accepted_topics":[${entries(accepted)}],"rejected_topics":[$refusals],"removed_topics":[],"active_subscriptions":[${entries(accepted)}]}""")
        if (drainAfter) drain()
    }
    private var tickSerial = 0L
    fun tick(rate: Double = 1555.0, empty: Boolean = false) {
        wire.deliver(if (empty) """{"type":"snapshot","version":1,"topic":"$K","data":{"usd_krw_futures":null}}""" else krxFrame(rate).replace("2026-09-26T10:20:00+09:00", kotlinx.datetime.Instant.fromEpochMilliseconds(1_790_385_600_000L + ++tickSerial).toString())); drain()
    }
    fun drain() = test.runCurrent()
    fun advance(ms: Long) { require(ms >= 0); test.advanceTimeBy(ms); drain() }
    fun until(subcase: String, budget: Long = 1_000, predicate: () -> Boolean) {
        val end = clock.nowMillis() + budget; drain()
        while (!predicate() && clock.nowMillis() < end) advance(minOf(10, end - clock.nowMillis()))
        assertTrue("precondition($row/$subcase): reached within ${budget}ms", predicate())
    }
    fun finishAll() { calls.toList().filter { !it.cancelled }.forEach { it.token.complete(Unit); it.body.complete(frame(it.topic,1555.0)) }; drain() }
    fun changeLiveUidOnly(uid: String) {
        identity = AuthIdentityFence(uid, identity.authGeneration + 1)
        h.source.identity = EntitlementsIdentity(uid, identity.authGeneration); h.source.liveFence = identity
    }
    fun deliverIdentity() { fenceCallback?.invoke(identity); drain() }
    fun changeUid(uid: String) { changeLiveUidOnly(uid); deliverIdentity() }
    fun recover() { val o = h.parts.orders; recoveryCallback?.invoke(AuthCredentialRecovery(identity, o.next(), o.next(), o.next())); drain() }
    private val checkpoints = mutableListOf<JsonElement>()
    fun checkpoint(name: String) {
        checkpoints += buildJsonObject { put("name", name); put("desired", strings(desired.sorted())); put("uid", identity.uid)
            put("access", accessTrace(snapshot)); put("grantEpoch", runtime.display.value.owner?.grantEpoch?.let(::JsonPrimitive) ?: JsonNull)
            put("liveChildren", h.parts.scope.coroutineContext[Job]!!.children.count()); put("runtimeChildren", runtimeScope.coroutineContext[Job]!!.children.count())
            put("rates",strings(runtime.display.value.rates.quotes.values.map { "${it.source}:${it.asset}:${it.rate}" }.sorted()))
            val state = runtime.display.value.topicState
            put("controlState",state.controlState.name); put("authResolution",state.authResolution.name)
            put("degraded",strings(state.degradedTopics.sorted())); put("manual",strings(state.manualRetryTopics.sorted()))
            put("canonical",JsonObject(state.topics.toSortedMap().mapValues { (_,topic) -> buildJsonObject {
                put("desired",topic.desired); put("confirmed",topic.confirmed); put("receive",topic.receiveGeneration)
                put("delivery",topic.deliveryState.name); put("rejection",topic.rejection?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("revalidation",topic.revalidationAttempt)
            } })) }
    }
    fun trace(): JsonElement = buildJsonObject {
        put("desired", JsonArray(checkpoints))
        put("subscribe", JsonArray(sends.mapIndexed { index,(r,accepted,at) -> buildJsonObject {
            put("frame",outgoingFrames[index]); put("id", r.requestId); put("operation", r.type); put("topics", strings(r.topics)); put("sent", accepted); put("at", at)
        } }))
        put("bootstrap", JsonArray(calls.map { c -> buildJsonObject {
            put("topic", c.topic); put("uid", c.owner.uid); put("issued", c.issuedAt); put("physical", c.physicallySent?.let(::JsonPrimitive) ?: JsonNull)
            put("bodyEnd", c.bodyEnd?.let(::JsonPrimitive) ?: JsonNull); put("cancelled", c.cancelled)
            put("access", accessTrace(c.publication)); put("grantEpoch", c.grantEpoch?.let(::JsonPrimitive) ?: JsonNull)
            put("outcome", c.outcome?.javaClass?.simpleName?.let(::JsonPrimitive) ?: JsonNull)
            put("responseBody",c.responseBody?.let(::JsonPrimitive) ?: JsonNull)
            put("exchanges",JsonArray(c.exchanges.map { x -> buildJsonObject {
                put("method",x.method); put("path",x.path); put("query",JsonObject(x.query.mapValues { JsonPrimitive(it.value) })); put("at",x.at); put("status",x.status)
            } }))
        } }))
        put("control", JsonArray(controls.map { (text, at) -> buildJsonObject { put("text", text); put("at", at) } }))
        put("authorityReads",JsonArray(reads.map(::accessTrace)))
        put("memoryReads",JsonArray(memoryReads.map(::accessTrace)))
        put("banner", bannerTrace())
        put("additionalRequests", JsonArray(h.source.calls.map { buildJsonObject { put("at", it.atMillis); put("fresh", it.freshPremium) } }))
    }
    private fun accessTrace(s: TopicAccessSnapshot): JsonElement = buildJsonObject {
        fun opaque(name: String,value: String?) { put(name,value?.let(::JsonPrimitive) ?: JsonNull) }
        put("revision",s.revision); put("permit",s.krxPermitSeq); put("ci",s.capabilityInvalidations)
        put("grant",s.facts.token?.value?.let(::JsonPrimitive) ?: JsonNull)
        opaque("binding",s.facts.binding?.ownerUid); opaque("recordOwner",s.facts.recordFence?.ownerUid)
        opaque("userEpoch",s.facts.recordFence?.userAccessEpoch); opaque("capabilityEpoch",s.facts.recordFence?.krxCapabilityEpoch)
        opaque("issuedUserEpoch",s.facts.issuedFor?.access?.userAccessEpoch); opaque("issuedCapabilityEpoch",s.facts.issuedFor?.access?.krxCapabilityEpoch)
        opaque("userEndNamespace",s.lastUserEnd?.namespace); opaque("capabilityEndNamespace",s.lastCapabilityEnd?.namespace)
        put("decisionGeneration",s.facts.decisionGeneration); put("userInvalidations",s.userInvalidations)
        put("userEndFloor",s.userEndInvalidationsFloor); put("standing",s.facts.tokenStanding)
        put("userBlocks",strings(s.facts.userBlocks.map { it.name }.sorted())); put("capabilityBlocks",strings(s.facts.capabilityBlocks.map { it.name }.sorted()))
    }
    private fun bannerTrace(): JsonElement {
        val rows = mutableListOf<JsonElement>()
        val owner = TopicDisplayOwner(identity, 1)
        for (tab in FreeTab.entries) for (generalDegraded in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
            val topics = G.associateWith { TopicSubscriptionState(desired = true, confirmed = true,
                deliveryState = if (generalDegraded) TopicDeliveryState.DEGRADED else TopicDeliveryState.HEALTHY) } +
                (K to TopicSubscriptionState(desired = false, deliveryState = TopicDeliveryState.DEGRADED))
            val d = kotlinx.coroutines.flow.MutableStateFlow(TopicDisplayState(owner = owner, rates = TopicRates(), containsSeed = false, connection = TopicConnectionDisplay.OPEN,
                topicState = TopicSubscriptionSnapshot(topics, controlState = TopicControlState.ACKNOWLEDGED)))
            val f = kotlinx.coroutines.flow.MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(identity, tab))
            val consumer = PremiumTopicConsumer(d, f, { identity }, object : RateRowPreferenceStore {
                override suspend fun preferences(uid: String) = emptyMap<RateRowList, RateRowPreference>()
                override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) = Unit
            }, { _, _ -> }, {}, { _, _ -> }, scope)
            try {
                consumer.start(); drain()
                val b = consumer.currentState().banner as? PremiumTopicScreenBanner.Topic
                rows += buildJsonObject { put("tab", tab.name); put("generalDegraded", generalDegraded)
                    put("reason", b?.reason?.name?.let(::JsonPrimitive) ?: JsonNull); put("canRetry", b?.canRetry ?: false) }
            } finally { scope.cancel() }
        }
        return JsonArray(rows)
    }
    override fun close() {
        runtime.stop(); job.cancel(); drain()
        if (pacDispatcher != null) runBlocking { withTimeout(5_000) { job.join() } } // disposal watchdog, not a contract clock.
        persistenceDirectory.deleteRecursively()
    }
    companion object {
        const val K = TopicCatalogue.KRX_FUTURES
        val G = setOf("fx:usd-krw", "fx:jpy-krw", "fx:eur-krw", "usdt:krw", "dxy:spot")
        fun strings(values: Iterable<String>) = JsonArray(values.map(::JsonPrimitive))
        fun krxFrame(rate: Double) = """{"type":"snapshot","version":1,"topic":"$K","data":{"usd_krw_futures":{"source":"krx","asset":"usd-krw-futures","rate":$rate,"timestamp":"2026-09-26T10:20:00+09:00"}}}"""
        fun frame(topic: String, rate: Double): String = when (topic) {
            K -> krxFrame(rate)
            "usdt:krw" -> """{"type":"snapshot","version":1,"topic":"usdt:krw","data":{"exchanges":[{"source":"upbit","asset":"usdt-krw","rate":$rate,"timestamp":"2026-09-26T10:20:00+09:00"}]}}"""
            "dxy:spot" -> """{"type":"snapshot","version":1,"topic":"dxy:spot","data":{"dxy":{"rate":99.0,"timestamp":"2026-09-26T10:20:00+09:00","source":"investing"}}}"""
            else -> """{"type":"snapshot","version":1,"topic":"$topic","data":{"banks":[{"source":"kb","asset":"${topic.removePrefix("fx:")}","rate":$rate,"timestamp":"2026-09-26T10:20:00+09:00"}]}}"""
        }
    }
}

/** These are real persisted access transitions and the production deferred purger; only the source is an endpoint double. */
internal data class S64HeadParts(val scope: CoroutineScope, val clock: RecheckClock,
    val source: KrxHarnessSource, val orders: AccessOrderSequence, val purger: UnimplementedScopePurger,
    val store: DataStoreAccessEpochStore)
internal fun interface S64HeadPacFactory { fun create(parts: S64HeadParts): PremiumAccessCoordinator }
internal class S64HeadHarness(val parts: S64HeadParts, val coordinator: PremiumAccessCoordinator) {
    val source get() = parts.source
    suspend fun issueGrant(): TopicGrantToken = checkNotNull(coordinator.topicGrantResult().fence).grant
    suspend fun rejectKrx(grant: TopicGrantToken) = coordinator.onTopicRejected(grant, listOf(TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED))
}
