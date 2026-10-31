package com.jay.fxi.data.remote

import com.jay.fxi.S64HeadWorld
import com.jay.fxi.S64HeadPacFactory
import com.jay.fxi.data.auth.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.*
import com.jay.fxi.domain.model.*
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import retrofit2.Retrofit
import okio.buffer

/** Candidate-only, read-only observation. All reflective reads are declared here; never copied to HEAD. */
internal class S64Observation(private val runtime: TopicRuntime, private val row: String) {
    data class Field(val owner: String, val name: String, val type: String, val purpose: String)
    companion object {
        val FIELDS = listOf(
            Field("TopicRuntime", "session", "TopicSessionCoordinator", "Factory identity/all D rows"),
            Field("TopicSessionCoordinator", "unissuedBootstraps", "Set<String>", "D02/D07 actual plan"),
            Field("TopicSessionCoordinator", "desired", "Set<String>", "D01-D15 actual loop-owned desired"),
            Field("TopicSessionCoordinator", "grantEpoch", "Long", "D02/D07-D11 original epoch"),
            Field("TopicSessionCoordinator", "consumedPermit", "Long", "D02/D03/D06 consumption"),
            Field("TopicSessionCoordinator", "connection", "Connection?", "D04/D06/D08-D11 owner"),
            Field("TopicSessionCoordinator", "liveRates", "TopicRates", "D08/D09 live tail"),
            Field("TopicSessionCoordinator", "krxRestIssues", "Map<Long,KrxRestIssue>", "D07/D09/D10/D14 original issue"),
            Field("TopicSessionCoordinator", "bootstrapsOut", "Map<Long,BootstrapOut>", "D07/D09 original Job"),
            Field("TopicSessionCoordinator", "store", "TopicSubscriptionStateStore", "D06/D08 immutable canonical snapshot"),
            Field("Connection", "generation", "Long", "D08 wire generation"),
            Field("Connection", "fence", "TopicSessionFence", "D08 original grant"),
            Field("Connection", "lifetime", "TopicUseLifetime", "D06/D09 GENERAL use"),
            Field("Connection", "leases", "TopicLeaseRegistry", "D08 GENERAL leases"),
            Field("Connection", "krxLeases", "TopicLeaseRegistry", "D08 original lease"),
            Field("Connection", "krxActive", "KrxCapture?", "D08 original tuple"),
            Field("Connection", "krxPending", "KrxPending?", "D05/D09 actual outstanding WS refusal"),
            Field("KrxPending", "capture", "KrxCapture", "D05/D09 original pending request"),
            Field("Connection", "controlOwner", "Long?", "D06/D08 control ticket"),
            Field("Connection", "commands", "Map<Long,RunningCommand>", "D04/D06 registered command"),
            Field("Connection", "authEnded", "Map<Long,AuthEndedRecord>", "D06 recovery premise"),
            Field("RunningCommand", "command", "TopicSubscribeCommand", "D06 ticket identity"),
            Field("RunningCommand", "purpose", "TopicCommandPurpose", "D06 actual lane"),
            Field("TopicSubscribeCommand", "ticket", "TopicRequestTicket?", "D06/D08 canonical ticket"),
            Field("KrxCapture", "requestId", "String", "D08 original tuple"),
            Field("KrxCapture", "connectionGeneration", "Long", "D08 original tuple"),
            Field("KrxCapture", "grantEpoch", "Long", "D08 original epoch"),
            Field("KrxCapture", "stamp", "KrxSessionStamp", "D08 original permit"),
            Field("KrxCapture", "use", "KrxUseLifetime", "D08 original use"),
            Field("BootstrapOut", "grantEpoch", "Long", "D07/D09 original epoch"),
            Field("BootstrapOut", "topic", "String", "D07/D09 original topic"),
            Field("BootstrapOut", "job", "Job", "D07/D09 actual consumer"),
            Field("KrxRestIssue", "requestId", "Long", "D07/D14 source ID"),
            Field("KrxRestIssue", "grantEpoch", "Long", "D07/D10 original epoch"),
            Field("KrxRestIssue", "owner", "TopicSessionFence", "D10 original fence"),
            Field("KrxRestIssue", "stamp", "KrxSessionStamp", "D07/D14 original publication"),
            Field("KrxRestIssue", "marker", "KrxSendMarker", "D10/D14 original marker"),
            Field("KrxRestIssue", "use", "KrxUseLifetime", "D09 original use"),
            Field("KrxRestIssue", "topic", "String", "D07/D14 source topic"),
            Field("KrxRestIssue", "origin", "KrxBootstrapOrigin", "D07 original INITIAL_PLAN/PERMIT terminal")
        )
    }
    private fun read(target: Any, name: String): Any? {
        val owner = target.javaClass.simpleName
        assertTrue("precondition($row/observation): declared read $owner.$name", FIELDS.any { it.owner == owner && it.name == name })
        val field = try { target.javaClass.getDeclaredField(name) } catch (missing: NoSuchFieldException) {
            throw AssertionError("precondition($row/observation): missing read-only field $owner.$name", missing)
        }
        field.isAccessible = true
        return field.get(target)
    }
    val session: TopicSessionCoordinator get() = read(runtime, "session") as? TopicSessionCoordinator
        ?: throw AssertionError("precondition($row/observation): runtime session type")
    val plan: List<String> get() = (read(session, "unissuedBootstraps") as Set<*>).map {
        it as? String ?: throw AssertionError("precondition($row/observation): plan element type") }
    val desired get() = (read(session,"desired") as Set<*>).map { it as String }.toSet()
    val epoch get() = read(session, "grantEpoch") as Long
    val consumed get() = read(session, "consumedPermit") as Long
    val live: TopicRates get() = (read(session, "liveRates") as TopicRates).let { it.copy(quotes = it.quotes.toMap()) }
    val canonical get() = (read(session, "store") as TopicSubscriptionStateStore).snapshot
    val retirements get() = session.krxRetirementRecords.mapValues { it.value?.copy() }.toMap()
    val cleanups get() = session.krxMemoryCleanupLog.map { it.copy() }
    data class ConnectionView(val generation: Long, val fence: TopicSessionFence,
        val use: TopicUseLifetime, val generalLeases: Map<String, TopicLeaseGrant>, val krxLeases: Map<String, TopicLeaseGrant>,
        val controlOwner: Long?, val tickets: Map<Long, TopicRequestTicket?>, val purposes: Map<Long, TopicCommandPurpose>,
        val authEndedCount: Int,val capture: Capture?,val pendingRequestId: String?)
    data class Capture(val tuple: KrxSubscriptionRetirement, val epoch: Long, val stamp: KrxSessionStamp, val use: KrxUseLifetime)
    fun connection(): ConnectionView? {
        val c = read(session, "connection") ?: return null
        val commands = (read(c, "commands") as Map<*, *>).toMap()
        val capture = read(c, "krxActive")?.let { k ->
            val leases = (read(c, "krxLeases") as TopicLeaseRegistry).held.toMap()
            Capture(KrxSubscriptionRetirement(read(k, "connectionGeneration") as Long, read(k, "requestId") as String,
                leases[TopicCatalogue.KRX_FUTURES]?.leaseId), read(k, "grantEpoch") as Long,
                read(k, "stamp") as KrxSessionStamp, read(k, "use") as KrxUseLifetime)
        }
        return ConnectionView(read(c, "generation") as Long, read(c, "fence") as TopicSessionFence,
            read(c, "lifetime") as TopicUseLifetime, (read(c, "leases") as TopicLeaseRegistry).held.toMap(),
            (read(c, "krxLeases") as TopicLeaseRegistry).held.toMap(), read(c, "controlOwner") as Long?,
            commands.map { (key, value) -> key as Long to (read(read(value!!, "command")!!, "ticket") as TopicRequestTicket?) }.toMap(),
            commands.map { (key, value) -> key as Long to read(value!!, "purpose") as TopicCommandPurpose }.toMap(),
            (read(c,"authEnded") as Map<*,*>).size,capture,read(c,"krxPending")?.let { read(read(it,"capture")!!,"requestId") as String })
    }
    data class RestIssue(val id: Long, val epoch: Long, val owner: TopicSessionFence,
        val stamp: KrxSessionStamp, val marker: KrxSendMarker, val use: KrxUseLifetime, val origin: String)
    data class Bootstrap(val epoch: Long,val topic: String,val active: Boolean)
    fun bootstrapFor(job: Job): Bootstrap? {
        val value = (read(session,"bootstrapsOut") as Map<*,*>).values.filterNotNull().singleOrNull { read(it,"job") === job } ?: return null
        return Bootstrap(read(value,"grantEpoch") as Long,read(value,"topic") as String,(read(value,"job") as Job).isActive)
    }
    val restIssues: List<RestIssue> get() = (read(session,"krxRestIssues") as Map<*,*>).values.filterNotNull().map { i ->
        RestIssue(read(i,"requestId") as Long,read(i,"grantEpoch") as Long,read(i,"owner") as TopicSessionFence,
            read(i,"stamp") as KrxSessionStamp,read(i,"marker") as KrxSendMarker,read(i,"use") as KrxUseLifetime,(read(i,"origin") as Enum<*>).name)
    }
    fun issue(job: Job, topic: String): RestIssue? {
        if (topic != TopicCatalogue.KRX_FUTURES) return null
        val out = (read(session, "bootstrapsOut") as Map<*, *>).values.filterNotNull().singleOrNull { read(it, "job") === job }
            ?: throw AssertionError("precondition($row/issue): callback owns an actual bootstrap Job")
        val originalEpoch = read(out, "grantEpoch") as Long
        val matches = (read(session, "krxRestIssues") as Map<*, *>).values.filterNotNull().filter {
            read(it, "grantEpoch") == originalEpoch && read(it, "topic") == topic
        }
        assertEquals("precondition($row/issue): one original typed KRX issue at callback", 1, matches.size)
        val i = matches.single()
        return RestIssue(read(i, "requestId") as Long, originalEpoch, read(i, "owner") as TopicSessionFence,
            read(i, "stamp") as KrxSessionStamp, read(i, "marker") as KrxSendMarker, read(i, "use") as KrxUseLifetime,(read(i,"origin") as Enum<*>).name)
    }
}

/** Typed endpoint adapter; the delegated Retrofit surface is blocked before any network I/O. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S64Http(private val r: S6Krx4Rig) {
    data class Reply(val status: Int, val body: String, val retryAfter: String? = null)
    class Call(val topic: String, val owner: AuthIdentityFence, val at: Long, val job: Job,
        val issue: S64Observation.RestIssue?, val source: com.jay.fxi.S64HeadWorld.Call, val attemptBudget: Duration) {
        val replies = Channel<Reply>(Channel.UNLIMITED)
        var tokenStarted = false
        var tokenReturned = false
        var physicalAt: Long? = null
        var bodyEnd: Long? = null
        var endpointReturned = false
        var serviceReturned = false
        var cancelled = false
        var cancellation: CancellationException? = null
        var ignoreCancellation = false
        var outcome: TopicSnapshotOutcome? = null
        val sends = mutableListOf<Long>()
    }
    val calls = mutableListOf<Call>()
    var tokenFailure: Throwable? = null
    var response: (String) -> Reply = { Reply(200, S64HeadWorld.frame(it, 1555.0)) }
    var ignoreKrxCancellation = false
    var budget: Duration? = null
    var endpointWork: (() -> Unit)? = null
    var onKrxBodyEnd: ((Call) -> Unit)? = null
    var afterServiceReturned: ((Call, TopicSnapshotOutcome) -> Unit)? = null
    val seamEvidence = mutableListOf<String>()
    fun recordSeam(point: String, call: Call): String {
        val s = r.snapshot
        val evidence = "${r.id}/${r.subcase}/$point endpointReturned=${call.endpointReturned} bodyEnd=${call.bodyEnd} " +
            "serviceReturned=${call.serviceReturned} outcome=${call.outcome?.javaClass?.simpleName} " +
            "worldCallbackReturned=${call.source.outcome != null} worldOutcome=${call.source.outcome?.javaClass?.simpleName} " +
            "jobCancelled=${call.job.isCancelled} cancellationObserved=${call.cancelled} " +
            "revision=${s.revision} grant=${s.facts.token} permit=${s.krxPermitSeq} " +
            "sessionEpoch=${r.observation.epoch} " +
            "userEpoch=${s.facts.recordFence?.userAccessEpoch} capEpoch=${s.facts.recordFence?.krxCapabilityEpoch} " +
            "issue=${call.issue} reservations=${r.world.issuer.reserved.toList()}"
        seamEvidence += evidence; println(evidence)
        return evidence
    }
    private val blockedApi = Retrofit.Builder().baseUrl("https://fixture.invalid/")
        .client(OkHttpClient.Builder().addInterceptor { throw AssertionError("precondition(${r.id}/HTTP): unexpected delegated endpoint") }.build())
        .build().create(AuthenticatedApiService::class.java)
    suspend fun bootstrap(owner: AuthIdentityFence, topic: String, admitted: () -> Boolean): TopicSnapshotOutcome {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val source = r.world.calls.last()
        val call = Call(topic, owner, r.world.clock.nowMillis(), job, r.observation.issue(job, topic), source,
            budget ?: TopicSnapshotBootstrapService.ATTEMPT_BUDGET).also {
            it.ignoreCancellation = topic == S6Krx4Rig.K && ignoreKrxCancellation; calls += it
        }
        val provider = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity(r.world.identity.uid, r.world.identity.authGeneration)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
                call.tokenStarted = true
                if (topic !in r.world.tokenHold) source.token.complete(Unit)
                source.token.await()
                if (topic == S6Krx4Rig.K) tokenFailure?.let { throw it }
                call.tokenReturned = true
                return "s64-local-token"
            }
        }, processScope = r.world.tokenScope, orders = r.world.h.parts.orders)
        val endpoint = object : AuthenticatedApiService by blockedApi {
            override suspend fun getTopicSnapshot(auth: AuthRequestTag, topic: String): retrofit2.Response<ResponseBody> {
                auth.use?.let { if (!it.context.admitted()) throw TopicUseWithheldException(it.context.observed()) }
                val now = r.world.clock.nowMillis()
                val request = Request.Builder().url("https://fixture.invalid/api/v2/topics/snapshot?topic=$topic").get().build()
                call.physicalAt = now; call.sends += now; source.physicallySent = now
                val reply = if (topic !in r.world.bodyHold) response(topic) else try { call.replies.receive() }
                    catch (cancelled: CancellationException) {
                        call.cancelled = true; call.cancellation = cancelled
                        if (!call.ignoreCancellation) throw cancelled
                        withContext(NonCancellable) { call.replies.receive() }
                    }
                if (topic == S6Krx4Rig.K) endpointWork?.invoke()
                call.endpointReturned = true
                source.responseBody = reply.body
                source.exchanges += S64HeadWorld.Exchange(request.method,request.url.encodedPath,mapOf("topic" to checkNotNull(request.url.queryParameter("topic"))),now,reply.status)
                val body = reply.body.toResponseBody("application/json".toMediaType())
                var bodyEnded = false
                fun ended() {
                    call.bodyEnd = r.world.clock.nowMillis(); source.bodyEnd = call.bodyEnd
                    if (!bodyEnded) { bodyEnded = true; if (topic == S6Krx4Rig.K) onKrxBodyEnd?.also { onKrxBodyEnd = null }?.invoke(call) }
                }
                val observed = object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    private val buffered by lazy {
                        object : okio.ForwardingSource(body.source()) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                                if (it == -1L) ended()
                            }
                            override fun close() { try { super.close() } finally { ended() } }
                        }.buffer()
                    }
                    override fun source(): okio.BufferedSource = buffered
                }
                val raw = okhttp3.Response.Builder().request(request)
                    .protocol(Protocol.HTTP_1_1).code(reply.status).message("s64 endpoint")
                    .apply { reply.retryAfter?.let { header("Retry-After", it) } }.build()
                return if (reply.status in 200..299) retrofit2.Response.success<ResponseBody>(observed, raw) else retrofit2.Response.error<ResponseBody>(observed, raw)
            }
        }
        val client = AuthenticatedApiClient(endpoint, AuthenticatedTransport(provider) { true }, kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        val service = budget?.let { TopicSnapshotBootstrapService(client, r.world.decoder, it) }
            ?: TopicSnapshotBootstrapService(client, r.world.decoder)
        try {
            val originalOutcome = service.bootstrap(owner, topic, admitted)
            call.outcome = originalOutcome; call.serviceReturned = true
            if (topic == S6Krx4Rig.K) afterServiceReturned?.also { afterServiceReturned = null }?.invoke(call, originalOutcome)
            return originalOutcome
        } catch (cancelled: CancellationException) {
            call.cancelled = true; call.cancellation = cancelled; throw cancelled
        }
    }
    fun reply(call: Call,reply: Reply,drainAfter: Boolean = true) {
        assertTrue("precondition(${r.id}/${r.subcase}): endpoint reply gate open", call.replies.trySend(reply).isSuccess)
        if (drainAfter) r.world.drain()
    }
    fun releaseForCleanup() {
        calls.forEach { it.source.token.complete(Unit); it.replies.trySend(Reply(200,S64HeadWorld.frame(it.topic,1555.0))) }
        r.world.drain()
    }
    fun completeGeneral(rate: Double = 1888.0) {
        calls.filter { it.topic in S6Krx4Rig.G && !it.serviceReturned && !it.cancelled }.forEach {
            reply(it, Reply(200, S64HeadWorld.frame(it.topic, rate)))
        }
    }
}

/** Worker publications keep their real executor while every delay uses the same virtual scheduler. */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class S64PublicationDispatcher(private val worker: CoroutineDispatcher,private val timer: TestDispatcher,
    private val delayRegistered: (Long) -> Unit) : CoroutineDispatcher(), Delay {
    override fun dispatch(context: CoroutineContext,block: Runnable) = worker.dispatch(context,block)
    override fun scheduleResumeAfterDelay(timeMillis: Long,continuation: CancellableContinuation<Unit>) {
        timer.scheduleResumeAfterDelay(timeMillis,continuation)
        delayRegistered(timeMillis) // Observe the existing timer registration; never creates a timer.
    }
    override fun invokeOnTimeout(timeMillis: Long,block: Runnable,context: CoroutineContext): DisposableHandle = timer.invokeOnTimeout(timeMillis,block,context)
}

/** All candidate runtime state comes from the same Factory-created instance. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S6Krx4Rig(test: TestScope, val id: String, val subcase: String,
    on: Boolean = true, tab: FreeTab = FreeTab.USD, worker: Boolean = false) : AutoCloseable {
    private var beta: KrxBetaPac? = null
    data class Installation(val readsBefore: Int,val readsAfter: Int,val jobsBefore: Int,val jobsAfter: Int)
    var installation: Installation? = null
        private set
    private val publicationWorker = if (worker) Executors.newSingleThreadExecutor().asCoroutineDispatcher() else null
    private val publicationDelays = mutableListOf<Pair<Long,Long>>()
    val world = S64HeadWorld(test, "$id/$subcase", tab,
        pacFactory = S64HeadPacFactory { p ->
            if (on) krxBetaPac(p.source, p.store, p.purger, p.purger, p.scope, p.clock,
                { p.source.liveFence }, p.orders).also { beta = it }.coordinator
            else PremiumAccessCoordinator(source = p.source, store = p.store, userPurger = p.purger, capabilityPurger = p.purger,
                scope = p.scope, clock = p.clock, liveFence = { p.source.liveFence }, orders = p.orders,
                jitter = ProbeJitter.None, krxContest = KrxContestSwitch.OFF)
        }, configure = { w, factory ->
            val reads = w.reads.size; val jobs = w.runtimeScope.coroutineContext[Job]!!.children.count()
            factory.installKrxDesiredProducer(if (on) checkNotNull(beta).sharedSwitch else KrxContestSwitch.OFF)
            installation = Installation(reads,w.reads.size,jobs,w.runtimeScope.coroutineContext[Job]!!.children.count())
        },
        pacDispatcher = publicationWorker?.let { S64PublicationDispatcher(it,StandardTestDispatcher(test.testScheduler)) { delay ->
            synchronized(publicationDelays) { publicationDelays += test.testScheduler.currentTime to delay }
        } })
    val observation = S64Observation(world.runtime, "$id/$subcase")
    val http = S64Http(this)
    data class Boundary(val name: String, val attempt: KrxCommandAttempt, val verdict: KrxAttemptCheck? = null)
    private fun frozen(a: KrxCommandAttempt) = a.copy(topics=a.topics.toSet(),pendingAckTopics=a.pendingAckTopics.toSet(),watched=a.watched.toSet(),manualTopics=a.manualTopics.toSet())
    val boundaries = mutableListOf<Boundary>()
    data class Registration(val requestId: String,val commandId: Long,val source: KrxAttemptSource,val controlOwner: Long?,val ticket: TopicRequestTicket?,val purpose: TopicCommandPurpose?)
    val registrations = mutableListOf<Registration>()
    data class Query(val order: Long, val at: Long, val origin: QueryOrigin, val intent: RefreshIntent, val reapprovalOnly: Boolean)
    val queries = mutableListOf<Query>()
    private var reapprovalStarted: CompletableDeferred<Unit>? = null
    var beforeTypedEncode: ((KrxCommandAttempt) -> Unit)? = null
    init {
        world.bootstrapEndpoint = http::bootstrap
        pac.onQueryStartedForTest = { order, at, origin, intent, reapproval ->
            queries += Query(order,at,origin,intent,reapproval)
            if (origin == QueryOrigin.SCHEDULED && intent == RefreshIntent.FORCE_PREMIUM) reapprovalStarted?.complete(Unit)
        }
        observation.session.krxAttemptBoundaryDecorator = { delegate -> object : KrxCommandAttemptBoundary {
            override fun prepare(scope: KrxAttemptScope) = delegate.prepare(scope)?.also { boundaries += Boundary("prepared", frozen(it)) }
            override fun beforeEncode(attempt: KrxCommandAttempt): KrxAttemptCheck {
                boundaries += Boundary("registered-before-encode",frozen(attempt))
                val registered = observation.connection()
                registrations += Registration(attempt.requestId,attempt.commandId,attempt.source,registered?.controlOwner,registered?.tickets?.get(attempt.commandId),registered?.purposes?.get(attempt.commandId))
                beforeTypedEncode?.also { beforeTypedEncode = null }?.invoke(attempt)
                return delegate.beforeEncode(attempt).also { boundaries += Boundary("checked", frozen(attempt), it) }
            }
            override fun sent(attempt: KrxCommandAttempt) { delegate.sent(attempt); boundaries += Boundary("sent", frozen(attempt)) }
            override fun discard(attempt: KrxCommandAttempt) { delegate.discard(attempt); boundaries += Boundary("discarded", frozen(attempt)) }
        } }
    }
    val pac get() = world.pac
    val snapshot get() = world.snapshot
    val desired get() = observation.desired
    val calls get() = http.calls.toList()
    val krxCalls get() = calls.filter { it.topic == K }
    val sends get() = world.sends.toList()
    val krxSends get() = sends.filter { K in it.first.topics && it.second && it.first.type == "subscribe" }
    val display get() = world.runtime.display.value
    data class Receipt(val tuple: KrxSubscriptionRetirement,val epoch: Long,val permit: Long,val before: Long,val after: Long,val rate: Double)
    val receipts = mutableListOf<Receipt>()
    private var receiptMillis = 1_790_385_600_000L
    fun pre(label: String, value: Boolean) = assertTrue("precondition($id/$subcase): $label", value)
    fun yes(label: String, value: Boolean) = assertTrue("contract($id): $subcase/$label", value)
    fun eq(label: String, expected: Any?, actual: Any?) = assertEquals("contract($id): $subcase/$label", expected, actual)
    suspend fun start(visible: Boolean = true,open: Boolean = true,leaseSeconds: Long = 600,fullActiveAck: Boolean = false) {
        if (publicationWorker != null && beta != null) {
            val watermark = runtimeReadWatermark()
            val virtualAt = world.clock.nowMillis()
            // Same normal PAC inputs as P.approve, without running the test scheduler on the worker.
            world.h.source.next = { EntitlementsOutcome.StableActive(visible) }
            try {
                runBlocking { withTimeout(5_000) { withContext(publicationWorker) {
                    pac.onIdentityChanged(world.identity).applied()
                    pac.refresh(RefreshIntent.FORCE_PREMIUM)
                } } }
            } catch (failed: TimeoutCancellationException) {
                throw AssertionError("UNREACHED_SEAM normal PAC startup input; ${driverEvidence(watermark)}",failed)
            }
            pre("PAC startup virtual clock unchanged; ${driverEvidence(watermark)}",world.clock.nowMillis() == virtualAt)
            bridgeWorkerRuntime("PAC GENERAL allowed",watermark) { snapshot.facts.userAllowed }
            world.runtime.start(); world.runtime.setOnline(true); world.runtime.setForeground(true)
            bridgeWorkerRuntime("normal deliverer standing/owner/socket/permit",watermark) {
                snapshot.facts.userAllowed && snapshot.facts.tokenStanding && display.owner != null && world.wires.isNotEmpty() &&
                    (!visible || snapshot.krxPermitSeq > 0 && snapshot.facts.capabilityAllowed)
            }
        } else {
            world.approve(visible)
            world.start()
        }
        pre("normal deliverer PAC GENERAL standing and owner",snapshot.facts.userAllowed && snapshot.facts.tokenStanding && display.owner != null)
        if (beta != null && visible) {
            if (publicationWorker == null) world.until("normal deliverer ON permit",1_000) { snapshot.krxPermitSeq > 0 && snapshot.facts.capabilityAllowed }
            pre("ON real permit",snapshot.krxPermitSeq > 0 && snapshot.facts.capabilityAllowed)
        }
        if (open) { world.open(); if (fullActiveAck) ackAllFullActive(seconds=leaseSeconds) else world.ackAll(seconds=leaseSeconds) }
    }
    fun acceptKrx(rate: Double = 1777.0,wire: KrxWire = world.wire) {
        val original = observation.connection()?.capture
        pre("actual original active K capture",original != null && original.tuple.leaseId != null)
        val captured = checkNotNull(original)
        val before = display.topicState.stateFor(K).receiveGeneration
        repeat(2) {
            val timestamp = kotlinx.datetime.Instant.fromEpochMilliseconds(++receiptMillis).toString()
            wire.deliver(S64HeadWorld.krxFrame(rate).replace("2026-09-26T10:20:00+09:00", timestamp)); world.drain()
        }
        pre("accepted original KRX receive", display.rates.quotes.values.any { it.source == "krx" && it.rate == rate } &&
            display.topicState.stateFor(K).receiveGeneration > before)
        pre("receipt belongs to unchanged original capture",observation.connection()?.capture?.tuple == captured.tuple)
        receipts += Receipt(captured.tuple.copy(),captured.epoch,captured.stamp.permitSeq,before,display.topicState.stateFor(K).receiveGeneration,rate)
    }
    // Server-owned input ledger. Never seeded from observation.connection().generalLeases/krxLeases.
    private data class ServerLease(val id: String,val expires: Long)
    private val serverLeases = mutableMapOf<KrxWire,LinkedHashMap<String,ServerLease>>()
    private val serverAcked = mutableSetOf<String>()
    fun ackAllFullActive(rejected: Map<String,String> = emptyMap(),seconds: Long = 600) {
        repeat(3) {
            for ((request,sent,_) in sends) if (sent && request.type == "subscribe" && serverAcked.add(request.requestId)) {
                ackFullActive(world.wire,request,rejected,seconds)
            }
        }
    }
    private fun ackFullActive(wire: KrxWire,request: TopicSubscribeRequest,rejected: Map<String,String>,seconds: Long = 600) {
        pre("full-active ACK answers actual outgoing request",sends.any { it.second && it.first == request })
        serverAcked += request.requestId
        val now = world.clock.nowMillis()
        val active = serverLeases.getOrPut(wire) { linkedMapOf() }
        active.entries.removeAll { it.value.expires <= now }
        val accepted = request.topics.filter { it !in rejected }
        request.topics.filter { it in rejected }.forEach { active.remove(it) }
        accepted.forEach { active[it] = ServerLease("${request.requestId}-$it",now + seconds * 1_000) }
        fun lease(topic: String,value: ServerLease) = buildJsonObject {
            put("topic",topic); put("lease_id",value.id)
            put("lease_duration_seconds",maxOf(0,(value.expires-now)/1_000))
        }
        val ack = buildJsonObject {
            put("type","subscription_ack"); put("request_id",request.requestId); put("operation","subscribe")
            put("accepted_topics",JsonArray(accepted.map { lease(it,active.getValue(it)) }))
            put("rejected_topics",JsonArray(rejected.filterKeys { it in request.topics }.map { (topic,error) -> buildJsonObject { put("topic",topic); put("error",error) } }))
            put("removed_topics",JsonArray(emptyList()))
            put("active_subscriptions",JsonArray(active.map { (topic,value) -> lease(topic,value) }))
        }
        wire.deliver(ack.toString()); world.drain()
    }
    fun publishOnWorker(visible: Boolean) {
        val worker = checkNotNull(publicationWorker)
        val revision = snapshot.revision
        runBlocking { withTimeout(5_000) {
            withContext(worker) { world.publish(visible) }
            withContext(worker) { } // PAC completion/worker queue handshake; no virtual-clock advance.
        } }
        pre("worker publication completed revision=$revision -> ${snapshot.revision}",snapshot.revision > revision)
    }
    fun runtimeReadWatermark(): Int = synchronized(world.reads) { world.reads.size }
    data class PacEffects(val token: TopicGrantToken?,val facts: TopicAccessFacts,val permit: Long,
        val contest: KrxContestView?,val rejection: TopicRejectionView,val owedIntent: RefreshIntent?,val heldCandidates: Int)
    /** Read-only PAC diagnostics; safe inside a service hook, with no test-scheduler re-entry. */
    fun readPacEffects(grant: TopicGrantToken): PacEffects {
        val at = world.clock.nowMillis()
        val result = runBlocking { withTimeout(5_000) { withContext(checkNotNull(publicationWorker)) {
            val owed = pac.recheckDiagnostics().owedIntent
            val held = pac.heldLossCandidateCount()
            val s = snapshot
            val facts = s.facts.copy(userBlocks=s.facts.userBlocks.toSet(),capabilityBlocks=s.facts.capabilityBlocks.toSet())
            val rejection = pac.rejectionView(grant).let { it.copy(pendingOrders=it.pendingOrders.toList()) }
            PacEffects(facts.token,facts,s.krxPermitSeq,pac.krxContestView,rejection,owed,held)
        } } }
        pre("read-only PAC diagnostics virtual now unchanged ($at -> ${world.clock.nowMillis()})",world.clock.nowMillis() == at)
        return result
    }
    fun driverEvidence(watermark: Int): String {
        val s = snapshot
        val reads = synchronized(world.reads) { world.reads.drop(watermark).map { it.revision to it.facts.capabilityAllowed } }
        return "now=${world.clock.nowMillis()} revision=${s.revision} userAllowed=${s.facts.userAllowed} " +
            "standing=${s.facts.tokenStanding} grant=${s.facts.token} permit=${s.krxPermitSeq} capabilityAllowed=${s.facts.capabilityAllowed} " +
            "owner=${display.owner} sockets=${world.wires.size} readWatermark=$watermark newRuntimeReads=$reads " +
            "queries=${queries.toList()} attempts=${boundaries.toList()}"
    }
    /** Driver preparation only. Never invoke from beforeTypedEncode/afterServiceReturned callbacks. */
    fun bridgeWorkerRuntime(label: String,watermark: Int,predicate: () -> Boolean) {
        val worker = checkNotNull(publicationWorker)
        val virtualAt = world.clock.nowMillis()
        try {
            runBlocking { withTimeout(5_000) {
                do {
                    world.drain()
                    withContext(worker) { }
                    world.drain()
                    pre("worker/runtime bridge virtual clock unchanged; ${driverEvidence(watermark)}",world.clock.nowMillis() == virtualAt)
                } while (!predicate()) // A queue marker alone is not completion of every descendant task.
            } }
        } catch (failed: TimeoutCancellationException) {
            throw AssertionError("UNREACHED_SEAM $label; ${driverEvidence(watermark)}",failed)
        }
        println("$id/$subcase bridge $label: ${driverEvidence(watermark)}")
    }
    /** D11 USER-CAP B preparation only; never called from a runtime callback. */
    fun prepareUserCapB(original: S64Observation.RestIssue,oldWire: KrxWire) {
        val virtualAt = world.clock.nowMillis()
        val watermark = runtimeReadWatermark()
        val boundaryStart = boundaries.size
        var stage = "B-identity-and-approval"
        var request: TopicSubscribeRequest? = null
        var ackSent = false
        var sendReadWatermark: Int? = null
        fun evidence() = "$stage ${driverEvidence(watermark)} connection=${observation.connection()} " +
            "originalOwner=${original.owner} originalStamp=${original.stamp} originalUse=${original.use} " +
            "sendReadWatermark=$sendReadWatermark Bsend=$request ACK=$ackSent capture=${observation.connection()?.capture}"
        try {
            world.deliverIdentity()
            world.h.source.next = { EntitlementsOutcome.StableActive(true) }
            // The deliverer's PAC mutex owner may resume on the test dispatcher after DataStore IO.
            // Keep that dispatcher moving while the normal B inputs complete on the real worker.
            val approval = world.h.parts.scope.async(checkNotNull(publicationWorker)) {
                pac.onIdentityChanged(world.identity).applied()
                pac.refresh(RefreshIntent.FORCE_PREMIUM)
            }
            try {
                bridgeWorkerRuntime(evidence(),watermark) { approval.isCompleted }
                runBlocking { withTimeout(5_000) { approval.await() } } // Propagate the actual input failure.
            } finally {
                if (!approval.isCompleted) approval.cancel()
            }
            stage = "B-owner-grant-effective-publication-fence"
            bridgeWorkerRuntime(evidence(),watermark) {
                val c = observation.connection()
                snapshot.facts.tokenStanding && snapshot.facts.userAllowed && snapshot.facts.capabilityAllowed &&
                    snapshot.krxPermitSeq > 0 && snapshot.facts.token != original.owner.grant &&
                    world.identity != original.owner.identity && display.owner?.identity == world.identity && K in desired &&
                    c?.fence?.identity == world.identity && c.fence.grant == snapshot.facts.token && c.fence != original.owner &&
                    world.wires.isNotEmpty() && world.wire !== oldWire
            }
            val fence = checkNotNull(observation.connection()).fence
            val permit = snapshot.krxPermitSeq
            val targetRevision = snapshot.revision
            // TSC.open already acquired B's use, possibly before entry. FIRST obtains a new send read after Opened.
            val beforeOpenReads = runtimeReadWatermark()
            sendReadWatermark = beforeOpenReads
            stage = "B-wire-open-runtime-send-read-and-actual-K-send"
            world.open()
            bridgeWorkerRuntime(evidence(),watermark) {
                val c = observation.connection()
                request = boundaries.drop(boundaryStart).lastOrNull {
                    it.name == "sent" && K in it.attempt.topics &&
                        it.attempt.source in setOf(KrxAttemptSource.FIRST,KrxAttemptSource.PERMIT) &&
                        it.attempt.connectionGeneration == c?.generation && it.attempt.stamp.permitSeq == permit &&
                        it.attempt.generalLifetime.grant == fence.grant && it.attempt.generalLifetime != original.use.lifetime &&
                        it.attempt.krxUse?.let { use -> snapshot.admitsKrxUse(use) } == true &&
                        synchronized(world.reads) { world.reads.drop(beforeOpenReads).any { read ->
                            read.revision >= targetRevision && read.revision == it.attempt.stamp.revision &&
                                read.facts.token == fence.grant && read.facts.tokenStanding && read.facts.userAllowed &&
                                read.facts.capabilityAllowed && read.krxPermitSeq == permit &&
                                read.capabilityInvalidations == it.attempt.stamp.capabilityInvalidations &&
                                read.facts.recordFence?.krxCapabilityEpoch == it.attempt.marker.krxCapabilityEpoch
                        } }
                }?.let { sent -> krxSends.lastOrNull { it.first.requestId == sent.attempt.requestId }?.first }
                request != null && c?.fence == fence
            }
            stage = "B-actual-request-ACK-and-active-capture"
            val actual = checkNotNull(request)
            val sent = boundaries.drop(boundaryStart).last { it.name == "sent" && it.attempt.requestId == actual.requestId }.attempt
            world.ackOn(world.wire,actual); ackSent = true
            bridgeWorkerRuntime(evidence(),watermark) {
                val c = observation.connection(); val capture = c?.capture
                c?.fence == fence && capture != null && capture.tuple.requestId == actual.requestId &&
                    capture.tuple.leaseId == "${actual.requestId}-$K" && capture.stamp.permitSeq == permit &&
                    capture.epoch == observation.epoch && capture.stamp == sent.stamp && capture.use == sent.krxUse &&
                    capture.use != original.use && capture.use.lifetime.grant == fence.grant &&
                    snapshot.admitsKrxUse(capture.use) && capture.use.lifetime == c.use
            }
            pre("B preparation virtual now unchanged; ${evidence()}",world.clock.nowMillis() == virtualAt)
            println("$id/$subcase B prepared: ${evidence()}")
        } catch (failed: Throwable) {
            throw AssertionError("UNREACHED_SEAM B preparation; ${evidence()}",failed)
        }
    }
    fun credentialEvidence(): String = "now=${world.clock.nowMillis()} revision=${snapshot.revision} " +
        "credentialReads=${world.credentialReads} gateCompleted=${world.credentialGate?.isCompleted} " +
        "sends=${sends.map { it.first.requestId to it.second }} " +
        "attempts=${boundaries.map { Triple(it.name,it.attempt.source,it.attempt.requestId) }} registrations=$registrations"
    fun awaitCredentialRead(before: Int) {
        val virtualAt = world.clock.nowMillis()
        try {
            runBlocking { withTimeout(5_000) {
                while (world.credentialReads <= before) { world.drain(); yield() }
            } }
        } catch (failed: TimeoutCancellationException) {
            throw AssertionError("UNREACHED_SEAM credential barrier; ${credentialEvidence()}",failed)
        }
        pre("actual credentialReads increased; ${credentialEvidence()}",world.credentialReads > before)
        pre("credential barrier leaves virtual clock unchanged; ${credentialEvidence()}",world.clock.nowMillis() == virtualAt)
    }
    private fun rejectRealPendingKrx(identityUnreadable: Boolean = false): Long {
        world.until("actual outstanding K control for WS refusal",1_500) {
            val pending = observation.connection()?.pendingRequestId
            pending != null && sends.any { it.second && it.first.requestId == pending && K in it.first.topics }
        }
        val pending = checkNotNull(observation.connection()?.pendingRequestId)
        val request = sends.single { it.second && it.first.requestId == pending }.first
        pre("refusal belongs to real typed send",boundaries.any { it.name == "sent" && it.attempt.requestId == pending })
        val before = world.issuer.reserved.size
        val rejectedAt = world.clock.nowMillis()
        if (identityUnreadable) {
            pre("issuer identity fault initially clear",world.h.source.identityNulls == 0)
            world.h.source.identityNulls = 1
        }
        if (serverLeases.containsKey(world.wire)) ackFullActive(world.wire,request,mapOf(K to "krx_entitlement_required"))
        else world.ackOn(world.wire,request,mapOf(K to "krx_entitlement_required"))
        pre("real WS reservation channel reached",world.issuer.reserved.size > before)
        if (identityUnreadable) pre("issuer identity-read fault consumed once",world.h.source.identityNulls == 0)
        return rejectedAt
    }
    data class PureContestInput(val response: CompletableDeferred<EntitlementsOutcome>,val queriesBefore: Int,val fetchesBefore: Int,val rejectedAt: Long)
    suspend fun pureAwaiting(): PureContestInput {
        val gate = CompletableDeferred<EntitlementsOutcome>().also { world.h.source.gates += it }
        val queriesBefore = queries.size; val fetchesBefore = world.h.source.calls.size
        val rejectedAt = rejectRealPendingKrx(identityUnreadable=true)
        pre("pure AWAITING (UNREACHED_SEAM otherwise)",pac.krxContestView?.state?.phase == KrxContestPhase.AWAITING)
        return PureContestInput(gate,queriesBefore,fetchesBefore,rejectedAt)
    }
    fun awaitPureQuery(input: PureContestInput) {
        val floor = PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS
        world.advance(maxOf(0,input.rejectedAt + floor - world.clock.nowMillis()))
        world.until("actual pure scheduled query at response gate",floor) {
            queries.drop(input.queriesBefore).any { it.origin == QueryOrigin.SCHEDULED && it.intent == RefreshIntent.FORCE_ENTITLEMENTS } &&
                world.h.source.calls.size > input.fetchesBefore && world.h.source.gates.none { it === input.response }
        }
        pre("scheduled query respects default floor",queries.drop(input.queriesBefore).filter { it.origin == QueryOrigin.SCHEDULED }.all { it.at >= input.rejectedAt + floor })
        pre("actual endpoint response still held",!input.response.isCompleted)
    }
    suspend fun awaiting(): CompletableDeferred<EntitlementsOutcome> {
        val gate = CompletableDeferred<EntitlementsOutcome>(); world.h.source.gates += gate
        rejectRealPendingKrx()
        pre("AWAITING",pac.krxContestView?.state?.phase == KrxContestPhase.AWAITING)
        return gate
    }
    suspend fun hold() {
        rejectRealPendingKrx()
        world.until("first real WS rejection reopens",1_000) { pac.krxContestView?.state?.phase == KrxContestPhase.OPEN }
        rejectRealPendingKrx()
        pre("HOLD",pac.krxContestView?.state?.phase == KrxContestPhase.HOLD)
    }
    data class ReapprovalInput(val old: TopicSessionFence,val displayEpoch: Long,val armedAt: Long,val refusal: TopicRejectionView,val queriesBefore: Int,
        val response: CompletableDeferred<EntitlementsOutcome>?,val started: CompletableDeferred<Unit>,val completed: CompletableDeferred<TopicGrantResult>)
    private suspend fun <T> reapprovalPac(label: String,block: suspend () -> T): T {
        val worker = publicationWorker ?: return block() // Preserve the existing worker=false D07/D10 input.
        val at = world.clock.nowMillis()
        println("$id/$subcase reapproval $label before now=$at revision=${snapshot.revision}")
        val result = runBlocking { withTimeout(5_000) { withContext(worker) { block() } } }
        val now = world.clock.nowMillis()
        println("$id/$subcase reapproval $label after now=$now revision=${snapshot.revision}")
        pre("reapproval $label virtual now unchanged ($at -> $now)",now == at)
        return result
    }
    suspend fun armReapproval(holdResponse: Boolean = false): ReapprovalInput {
        // EXPLICIT_ISSUER_INPUT after normal runtime cold-start; this pull must preserve its standing token.
        val old = checkNotNull(reapprovalPac("original fence pull") { pac.topicGrantResult() }.fence)
        val response = if (holdResponse) CompletableDeferred<EntitlementsOutcome>().also { world.h.source.gates += it } else null
        world.h.source.next = { EntitlementsOutcome.StableActive(true) }
        val before = world.h.source.calls.size
        val armedAt = world.clock.nowMillis()
        val started = CompletableDeferred<Unit>().also { reapprovalStarted = it }
        pre("no earlier issuer identity failure",world.h.source.identityNulls == 0)
        world.h.source.identityNulls = 1
        val delaysBefore = synchronized(publicationDelays) { publicationDelays.size }
        reapprovalPac("actual premium refusal") { pac.onTopicRejected(old.grant,listOf(TopicRejectionReason.PREMIUM_REQUIRED)) }
        pre("issuer-only live read consumed",world.h.source.identityNulls == 0)
        val refusal = pac.rejectionView(old.grant)
        pre("actual premium refusal handed over",refusal.latestCurrent != null && refusal.pendingOrders.isEmpty())
        val diagnostics = reapprovalPac("demand diagnostics") { pac.recheckDiagnostics() }
        pre("actual FORCE_PREMIUM demand",diagnostics.owedIntent == RefreshIntent.FORCE_PREMIUM)
        pre("same original grant standing during hold",snapshot.facts.tokenStanding && snapshot.facts.token == old.grant)
        val done = CompletableDeferred<TopicGrantResult>()
        world.h.parts.scope.launch {
            pac.accessRevisions.first { snapshot.facts.tokenStanding && snapshot.facts.token != old.grant }
            done.complete(pac.topicGrantResult())
        }
        publicationWorker?.let { worker -> runBlocking { withTimeout(5_000) { withContext(worker) { } } } } // Synchronous registration barrier: no test-scheduler auto-advance.
        if (publicationWorker != null) {
            val registered = synchronized(publicationDelays) { publicationDelays.drop(delaysBefore) }
            println("$id/$subcase reapproval timer armedAt=$armedAt now=${world.clock.nowMillis()} registrations=$registered")
            pre("actual default-floor delay registered at original arming time",registered.any { (at,delay) -> at == armedAt && delay == PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS })
        }
        return ReapprovalInput(old,observation.epoch,armedAt,refusal,before,response,started,done)
    }
    suspend fun awaitHeldReapprovalQuery(input: ReapprovalInput) {
        pre("response gate installed",input.response != null)
        world.advance(maxOf(0,input.armedAt + PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS - world.clock.nowMillis()))
        // Real watchdog only: the scheduled query's clock and delay above are virtual.
        runBlocking { withTimeout(5_000) {
            input.started.await()
            withContext(checkNotNull(publicationWorker)) { } // The started query reaches the endpoint response gate.
        } }
        pre("scheduled fresh query actually waiting",world.h.source.calls.drop(input.queriesBefore).any { it.freshPremium } && !input.completed.isCompleted)
    }
    fun verifyReapproval(input: ReapprovalInput,result: TopicGrantResult): TopicSessionFence {
        val next = checkNotNull(result.fence)
        val cause = result.cause as? TopicGrantCause.RefusalReapproval
        pre("domain-input RefusalReapproval cause",cause != null && next.grant != input.old.grant)
        pre("reapproval original grant and refusal",cause!!.from == input.old.grant && cause.refusalOrder == input.refusal.latestCurrent && cause.queryOrder > checkNotNull(input.refusal.latestReported))
        pre("reapproval same identity and USER epoch",next.identity == input.old.identity && next.userAccessEpoch == input.old.userAccessEpoch)
        pre("reapproval actual fresh query",world.h.source.calls.drop(input.queriesBefore).any { it.freshPremium })
        pre("reapproval actual scheduled query after floor",queries.any { it.order == cause.queryOrder && it.origin == QueryOrigin.SCHEDULED && it.intent == RefreshIntent.FORCE_PREMIUM && it.at >= input.armedAt + PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS })
        return next
    }
    suspend fun reapprove(): TopicSessionFence {
        val input = armReapproval()
        world.until("scheduled real refusal reapproval",10_000) { input.completed.isCompleted }
        val next = verifyReapproval(input,input.completed.await())
        world.until("normal deliverer replaced grant",3_000) { display.owner?.let { it.identity == next.identity && it.grantEpoch > input.displayEpoch } == true }
        return next
    }
    suspend fun withdraw() {
        world.h.source.next = { EntitlementsOutcome.StableInactive(false) }
        pac.refresh(RefreshIntent.FORCE_PREMIUM); world.drain()
        pre("explicit stable withdrawal", !snapshot.facts.userAllowed && !snapshot.facts.tokenStanding)
    }
    private fun cleanupEvidence(root: Job,stage: String): String {
        fun tree(job: Job,path: String): List<String> = listOf(
            "$path name=$job active=${job.isActive} cancelled=${job.isCancelled} completed=${job.isCompleted}"
        ) + job.children.toList().flatMapIndexed { index,child -> tree(child,"$path/$index") }
        return "stage=$stage now=${world.clock.nowMillis()} jobs=${tree(root,"PAC/root")} " +
            "runtime=${tree(checkNotNull(world.runtimeScope.coroutineContext[Job]),"runtime")} " +
            "HTTP=${calls.filter { !it.job.isCompleted || !it.serviceReturned }.map { "${it.topic}:job=${it.job}:tokenStarted=${it.tokenStarted}:tokenReturned=${it.tokenReturned}:tokenCompleted=${it.source.token.isCompleted}:sourceBodyCompleted=${it.source.body.isCompleted}:endpoint=${it.endpointReturned}:bodyEnd=${it.bodyEnd}:service=${it.serviceReturned}:cancelled=${it.cancelled}" }}"
    }
    override fun close() {
        val root = checkNotNull(world.h.parts.scope.coroutineContext[Job])
        val virtualAt = world.clock.nowMillis()
        var firstFailure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (failed: Throwable) {
                val first = firstFailure
                if (first == null) firstFailure = failed else if (failed !== first) first.addSuppressed(failed)
            }
        }
        try {
            // Disposal begins only after the test body; retain the original root and P persistence cleanup.
            attempt { world.runtime.stop() }
            attempt { root.cancel() }
            attempt { http.releaseForCleanup() }
            attempt {
                var stage = "root-cancelled-and-endpoints-released"
                try {
                    runBlocking { withTimeout(5_000) {
                        while (!root.isCompleted) {
                            stage = "driver-drain"; world.drain()
                            stage = "publication-worker-roundtrip"; publicationWorker?.let { withContext(it) { } }
                            stage = "driver-drain-after-worker"; world.drain()
                            stage = "real-IO-yield"; withContext(Dispatchers.IO) { yield() }
                            check(world.clock.nowMillis() == virtualAt) { "cleanup changed virtual clock; ${cleanupEvidence(root,stage)}" }
                        }
                    } }
                    check(world.clock.nowMillis() == virtualAt) { "cleanup changed virtual clock; ${cleanupEvidence(root,stage)}" }
                } catch (failed: Throwable) {
                    throw AssertionError("cleanup root settlement failed; ${cleanupEvidence(root,stage)}",failed)
                }
            }
            // Never delete an unfinished DataStore directory or replace its failure with P's join timeout.
            if (root.isCompleted) attempt { world.close() }
        } finally {
            attempt { beta?.let { it.check.assertHeld() } }
            if (root.isCompleted) attempt { publicationWorker?.close() }
        }
        firstFailure?.let { throw it }
    }
    companion object { val G = S64HeadWorld.G; const val K = S64HeadWorld.K }
}

internal fun s64Cases(id: String, cases: List<String>): List<String> {
    val selected = System.getenv("S6_4A_SUBCASE") ?: return cases
    val prefix = "$id/"
    val chosen = if (selected.startsWith(prefix)) selected.removePrefix(prefix) else ""
    assertTrue("precondition($id/selection): exact registered subcase $selected", chosen in cases)
    return listOf(chosen)
}
@OptIn(ExperimentalCoroutinesApi::class)
internal fun s64Test(id: String, subcase: String, on: Boolean = true, tab: FreeTab = FreeTab.USD, worker: Boolean = false,
    body: suspend TestScope.(S6Krx4Rig) -> Unit) = runTest {
    val rig = S6Krx4Rig(this, id, subcase, on, tab, worker)
    var bodyFailure: Throwable? = null
    try {
        body(rig)
    } catch (failed: Throwable) {
        bodyFailure = failed
        throw failed
    } finally {
        try {
            rig.close()
        } catch (cleanup: Throwable) {
            val primary = bodyFailure
            if (primary == null) throw cleanup
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
    }
}
