package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthCredentialRecovery
import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.KrxAttemptCheck
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.HEAD_FACTORY
import com.jay.fxi.data.entitlements.KrxBetaPac
import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.entitlements.KrxHarnessDefaults
import com.jay.fxi.data.entitlements.KrxHarnessFactory
import com.jay.fxi.data.entitlements.KrxSendMarker
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.PremiumAccessTopicGrantIssuer
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantDeliverer
import com.jay.fxi.data.entitlements.grantedKrxVisible
import com.jay.fxi.data.entitlements.issueGrant
import com.jay.fxi.data.entitlements.krxBetaPac
import com.jay.fxi.data.entitlements.krxHarness
import com.jay.fxi.data.entitlements.rejectKrx
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.TopicSubscriptionSnapshot
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * α-1a: real PAC → issuer → TGD → serial TSC → real one-argument SUA; only I/O, clock and persistence are fakes.
 * Frozen HEAD mode substitutes only TSC/CMD; it does not generate expectations from the candidate.
 * Every row owns this recorder and all dependencies. No switch is enabled here: krxBetaPac owns that one site.
 * ACK wire order alone does not imply capture promotion; a queued first KRX frame may be dropped. G1(ii) REST
 * supplies the same value; an already-consumed reconnect FIRST can wait for the next tick (memory retained, no expiry).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxSessionIntegrationRig(
    val test: TestScope,
    val row: String,
    val on: Boolean = true,
    val frozenHead: Boolean = false,
    val desired: Set<String> = TopicCatalogue.DESIRED + KRX,
    val gap: Duration = 500.milliseconds,
    val withRestore: Boolean = false
) {
    private val job = SupervisorJob()
    var beta: KrxBetaPac? = null
        private set
    val h = test.krxHarness(job, if (on) KrxHarnessFactory { p ->
        krxBetaPac(p.source, p.store, p.purger, p.purger, p.scope, p.clock,
            { p.source.liveFence }, p.orders).also { beta = it }.coordinator
    } else HEAD_FACTORY)
    val pac get() = h.coordinator
    val snapshot get() = pac.accessSnapshot
    var identity: AuthIdentityFence? = AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)
    var credential = AuthSnapshot(KrxHarnessDefaults.OWNER, 1L, "fixture-credential")
    var refreshResult: AuthSnapshot? = null
    var credentialGate: CompletableDeferred<Unit>? = null
    var afterRefresh: (() -> Unit)? = null
    val events = mutableListOf<String>()
    val publications = mutableListOf<String>()
    val reads = mutableListOf<Pair<String, TopicAccessSnapshot>>()
    enum class ReadKind { PAYLOAD, METADATA }
    data class ReadEvent(val method: String, val snapshot: TopicAccessSnapshot, val at: Long) {
        val kind: ReadKind get() = if (method == "currentRevision") ReadKind.METADATA else ReadKind.PAYLOAD
    }
    val readLog = mutableListOf<ReadEvent>()
    var snapshotSupply: () -> TopicAccessSnapshot = { snapshot }
    var afterMetadataRead: (() -> Unit)? = null
    /** α-3: observation after this exact supplier return was recorded, before SUA consumes it. Default has no effect. */
    var afterPayloadRead: ((ReadEvent) -> Unit)? = null
    val uses = SnapshotTopicUseAuthority {
        val method = Thread.currentThread().stackTrace.firstOrNull {
            it.className == SnapshotTopicUseAuthority::class.java.name && it.methodName in setOf(
                "currentRevision", "acquire", "admits", "acquireKrx", "admitsKrx", "admitsWithKrx", "acquireWithKrx")
        }?.methodName ?: "unknown"
        val metadata = method == "currentRevision"
        snapshotSupply().also {
            reads += (if (metadata) "revision" else "payload") to it
            readLog += ReadEvent(method, it, test.testScheduler.currentTime)
            if (metadata) afterMetadataRead?.also { afterMetadataRead = null }?.invoke()
            else afterPayloadRead?.invoke(readLog.last())
        }
    }
    val issuer = RecordingTopicGrantIssuer(PremiumAccessTopicGrantIssuer(pac))
    val store = TopicSubscriptionStateStore()
    val states = mutableListOf<TopicSubscriptionSnapshot>()
    val requests = mutableListOf<TopicSubscribeRequest>()
    data class WireSend(val request: TopicSubscribeRequest, val accepted: Boolean, val at: Long)
    val sends = mutableListOf<WireSend>()
    val wires = mutableListOf<KrxWire>()
    val wire get() = wires.last()
    var failNextWire = 0
    var beforeEncode: ((TopicSubscribeRequest) -> Unit)? = null
    /** Runs inside fake socket.send, before its Boolean is returned (D-C). */
    var afterWireSend: (() -> Unit)? = null
    var jitterUnit = 0.0
    var bootstrapOverride: (suspend (AuthIdentityFence, String, () -> Boolean) -> TopicSnapshotOutcome)? = null
    var httpEvidence: (Int?, String?) -> Unit = { _, _ -> }
    var seed: com.jay.fxi.data.local.TopicLastKnownRestore? = null
    private val restore: (suspend (TopicSessionFence) -> com.jay.fxi.data.local.TopicLastKnownRestore)? =
        if (withRestore) ({ _: TopicSessionFence -> seed ?: com.jay.fxi.data.local.TopicLastKnownRestore.NotAdmitted }) else null
    var wireWindow: ((WireSend) -> Unit)? = null
    data class BoundaryEvent(val name: String, val requestId: String?, val value: Any?)
    class CommandRecord(val id: Long, val command: TopicSubscribeCommand, val purpose: TopicCommandPurpose) {
        val events = mutableListOf<BoundaryEvent>()
        val prepared = mutableListOf<KrxCommandAttempt>()
        val checks = mutableListOf<Pair<KrxCommandAttempt, KrxAttemptCheck>>()
        val sent = mutableListOf<KrxCommandAttempt>()
        val discarded = mutableListOf<KrxCommandAttempt>()
        val dispatches = mutableListOf<Pair<TopicSubscribeRequest, KrxAttemptDispatch>>()
        val tickets = mutableListOf<Any?>()
    }
    val commands = mutableListOf<CommandRecord>()
    var onPrepared: ((CommandRecord, KrxCommandAttempt) -> Unit)? = null
    var onBeforeCheck: ((CommandRecord, KrxCommandAttempt) -> Unit)? = null
    var onChecked: ((CommandRecord, KrxCommandAttempt, KrxAttemptCheck) -> Unit)? = null
    var onDiscarded: ((CommandRecord, KrxCommandAttempt) -> Unit)? = null
    var onStarted: ((CommandRecord) -> Unit)? = null
    val readyInWire: Boolean get() = commands.any { record ->
        record.checks.any { (attempt, verdict) -> verdict == KrxAttemptCheck.READY && attempt !in record.sent && attempt !in record.discarded }
    }
    var onState: ((TopicSubscriptionSnapshot) -> Unit)? = null
    var processRefusals = true
    val delayedRefusals = mutableListOf<() -> Unit>()
    val acknowledgements = mutableListOf<TopicCommandAcknowledgement>()
    val graph = mutableListOf<TopicGraphInput>()
    val offers = mutableListOf<TopicRates>()
    var onOffer: (TopicRates) -> Unit = {}
    val deliveries = mutableListOf<String>()
    /** α-3: hold only TGD revision notifications; real PAC publication and grant delivery continue normally. */
    var holdRevisionNotifications = false
    var heldRevisionNotifications = 0
        private set
    var forwardedRevisionNotifications = 0
        private set
    fun releaseRevisionNotifications() {
        holdRevisionNotifications = false
        val count = heldRevisionNotifications
        heldRevisionNotifications = 0
        repeat(count) { forwardedRevisionNotifications++; revise() }
        drain()
    }
    val undelivered = mutableListOf<Pair<String, TopicSnapshotOutcome>>()
    class SleepProbe(val at: Long, val duration: Duration, val job: Job?) {
        var cancelled = false
        var returned = false
    }
    val sleeps = mutableListOf<SleepProbe>()
    val clock = object : TopicCommandClock {
        override fun nowMillis() = test.testScheduler.currentTime
        override suspend fun sleep(duration: Duration) {
            val probe = SleepProbe(nowMillis(), duration, currentCoroutineContext()[Job]).also { sleeps += it }
            try { delay(duration); probe.returned = true }
            catch (cancelled: CancellationException) { probe.cancelled = true; throw cancelled }
        }
    }
    val retirements = KrxRecordingRetirer { clock.nowMillis() }
    var httpFloor = 0L
    private var serial = 0
    private val decoder = TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode
    private val credentials = object : TopicCommandCredentials {
        override suspend fun currentSnapshot(): AuthSnapshot {
            credentialGate?.await()
            return credential
        }
        override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? {
            afterRefresh?.also { afterRefresh = null }?.invoke()
            return refreshResult
        }
        override suspend fun recordRejected(credential: AuthSnapshot) { events += "auth:rejected" }
        override suspend fun recordRejectionEvidence(credential: AuthSnapshot) { events += "auth:evidence" }
    }
    enum class Stage { TOKEN, BODY, RETURNED, CANCELLED }
    class Call(val topic: String, val at: Long, val use: () -> Boolean, val read: TopicAccessSnapshot) {
        val token = CompletableDeferred<Unit>()
        val body = CompletableDeferred<TopicSnapshotOutcome>()
        var stage = Stage.TOKEN
        var cancelled = false
        val exchanges = mutableListOf<Boolean>()
    }
    val calls = mutableListOf<Call>()
    private suspend fun bootstrap(owner: AuthIdentityFence, topic: String, admitted: () -> Boolean): TopicSnapshotOutcome {
        bootstrapOverride?.let { return it(owner, topic, admitted) }
        val issueRead = if (topic == KRX) readLog.lastOrNull { it.method == "acquireWithKrx" }?.snapshot ?: snapshot else snapshot
        val call = Call(topic, clock.nowMillis(), admitted, issueRead).also { calls += it }
        events += "http:$topic:${clock.nowMillis()}"
        try {
            call.token.await()
            val allowed = admitted() && owner == identity
            call.exchanges += allowed
            if (!allowed) throw TopicUseWithheldException(emptyList())
            call.stage = Stage.BODY
            return call.body.await().also { call.stage = Stage.RETURNED }
        } catch (cancelled: CancellationException) {
            call.cancelled = true
            call.stage = Stage.CANCELLED
            events += "cancel:$topic"
            throw cancelled
        }
    }
    private val connect: (Long) -> TopicTransport = { generation ->
        events += "connect:$generation"
        val wire = KrxWire { text, accepted ->
            requests.lastOrNull { "encoded-${it.requestId}" == text }?.let {
                sends += WireSend(it, accepted, clock.nowMillis())
                events += "send:${it.requestId}:${it.topics}:$accepted"
                wireWindow?.invoke(sends.last())
            }
            afterWireSend?.also { afterWireSend = null }?.invoke()
        }.also { it.failSends = failNextWire; failNextWire = 0; wires += it }
        TopicTransport(wire, decoder).also { it.open(Request.Builder().url(TopicSessionKrxHarness.URL).build()) }
    }
    private val encode: (TopicSubscribeRequest) -> String = { request ->
        beforeEncode?.invoke(request)
        requests += request
        events += "encode:${request.requestId}:${request.topics}"
        "encoded-${request.requestId}"
    }
    private var fenceObserver: ((AuthIdentityFence?) -> Unit)? = null
    private lateinit var deliverer: TopicGrantDeliverer
    private val publicRefusal: (TopicSessionFence, Map<String, com.jay.fxi.domain.model.TopicRejectionReason>) -> Unit = { owner, reasons ->
        val action = { deliverer.forwardRejection(owner, reasons) }
        if (processRefusals) action() else delayedRefusals += action
    }
    private val markedRefusal: (TopicSessionFence, Map<String, com.jay.fxi.domain.model.TopicRejectionReason>, KrxSendMarker?) -> Unit = { owner, reasons, marker ->
        val action = { deliverer.forwardRejection(owner, reasons, marker) }
        if (processRefusals) action() else delayedRefusals += action
    }
    private val stateSink: (TopicSubscriptionSnapshot) -> Unit = { states += it; onState?.invoke(it) }
    val session: TopicSessionCoordinator? = if (frozenHead) null else TopicSessionCoordinator(
        scope = h.parts.scope, clock = clock, connect = connect, credentials = credentials, orders = h.parts.orders,
        authority = uses, krxSendReader = uses, krxRetirer = retirements, bootstrap = ::bootstrap, store = store, encode = encode,
        newRequestId = { "r${++serial}" }, jitter = { jitterUnit }, liveIdentity = { identity },
        bootstrapIssueGap = gap, bootstrapNotBeforeMillis = { httpFloor },
        onBootstrapHttpEvidence = { status, retry -> httpEvidence(status, retry) },
        onRejected = publicRefusal, onRejectedWithMarker = markedRefusal,
        onAcknowledgement = { acknowledgements += it; events += "ack:${it.accepted}:${it.rejected}" },
        onTopicState = stateSink, onBootstrapUndelivered = { _, topic, outcome -> undelivered += topic to outcome },
        desired = desired, restoreSeed = restore, offerLive = { _, _, rates -> offers += rates; onOffer(rates) },
        graphSink = { graph += it; TopicGraphOffer.ENQUEUED }, wallClock = { 1_000_000L + clock.nowMillis() }
    )
    val head: A1aHeadTopicSessionCoordinator? = if (!frozenHead) null else A1aHeadTopicSessionCoordinator(
        scope = h.parts.scope, clock = clock, connect = connect, credentials = credentials, orders = h.parts.orders,
        authority = uses, krxSendReader = uses, bootstrap = ::bootstrap, store = store, encode = encode,
        newRequestId = { "r${++serial}" }, jitter = { jitterUnit }, liveIdentity = { identity },
        bootstrapIssueGap = gap, bootstrapNotBeforeMillis = { httpFloor },
        onBootstrapHttpEvidence = { status, retry -> httpEvidence(status, retry) },
        onRejected = publicRefusal, onRejectedWithMarker = markedRefusal,
        onAcknowledgement = { acknowledgements += it; events += "ack:${it.accepted}:${it.rejected}" },
        onTopicState = stateSink, onBootstrapUndelivered = { _, topic, outcome -> undelivered += topic to outcome },
        desired = desired, restoreSeed = restore, offerLive = { _, _, rates -> offers += rates; onOffer(rates) },
        graphSink = { graph += it; TopicGraphOffer.ENQUEUED }, wallClock = { 1_000_000L + clock.nowMillis() }
    )
    val rates get() = session?.rates?.value ?: head!!.rates.value
    val display get() = session?.display?.value ?: head!!.display.value
    val consumed: Long get() {
        val target = checkNotNull(session) { "contract($row): candidate session" }
        return privateValue(target, "consumedPermit") as Long
    }
    val krxSends get() = sends.filter { KRX in it.request.topics && it.accepted }
    val krxCalls get() = calls.filter { it.topic == KRX }
    init {
        h.source.liveFence = identity
        session?.krxAttemptBoundaryDecorator = { delegate ->
            val live = checkNotNull(privateValue(checkNotNull(session), "connection"))
            @Suppress("UNCHECKED_CAST")
            val running = privateValue(live, "commands") as Map<Long, Any>
            val entry = running.entries.single { (_, value) ->
                val command = privateValue(value, "command")
                command is TopicSubscribeCommand && commands.none { it.command === command }
            }
            val command = privateValue(entry.value, "command") as TopicSubscribeCommand
            val record = CommandRecord(entry.key, command, privateValue(entry.value, "purpose") as TopicCommandPurpose)
            commands += record
            @Suppress("UNCHECKED_CAST")
            val started = privateValue(command, "onRequestStarted") as () -> Unit
            setPrivate(command, "onRequestStarted", {
                record.tickets += privateValue(store, "currentTicket")
                started()
                onStarted?.invoke(record)
            } as () -> Unit)
            // Install finishes synchronously. This serial task is queued before command.run's launch, so it decorates the
            // actual installed dispatch, without another operational seam and without an extra read or physical send.
            h.parts.scope.launch {
                @Suppress("UNCHECKED_CAST")
                val dispatch = privateValue(command, "krxAttemptDispatch") as (TopicSubscribeRequest, String) -> KrxAttemptDispatch
                val observe: (TopicSubscribeRequest, String) -> KrxAttemptDispatch = { request, text ->
                    dispatch(request, text).also { result -> record.dispatches += request to result }
                }
                setPrivate(command, "krxAttemptDispatch", observe)
            }
            object : KrxCommandAttemptBoundary {
                override fun prepare(scope: KrxAttemptScope): KrxCommandAttempt? {
                    record.events += BoundaryEvent("prepare", scope.requestId, scope)
                    return delegate.prepare(scope).also { attempt ->
                        if (attempt != null) { record.prepared += attempt; onPrepared?.invoke(record, attempt) }
                    }
                }
                override fun beforeEncode(attempt: KrxCommandAttempt): KrxAttemptCheck {
                    onBeforeCheck?.invoke(record, attempt)
                    return delegate.beforeEncode(attempt).also { verdict ->
                        record.events += BoundaryEvent("beforeEncode", attempt.requestId, verdict)
                        record.checks += attempt to verdict
                        onChecked?.invoke(record, attempt, verdict)
                    }
                }
                override fun sent(attempt: KrxCommandAttempt) {
                    record.events += BoundaryEvent("sent", attempt.requestId, attempt)
                    delegate.sent(attempt); record.sent += attempt
                }
                override fun discard(attempt: KrxCommandAttempt) {
                    record.events += BoundaryEvent("discard", attempt.requestId, attempt)
                    delegate.discard(attempt); record.discarded += attempt
                    onDiscarded?.invoke(record, attempt)
                }
            }
        }
        beta?.check?.observe { publications += "${it.revision}:${it.krxPermitSeq}:${it.capabilityInvalidations}:${it.facts.capabilityAllowed}" }
        val sink = object : TopicGrantSink {
            override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
                deliveries += "access:$allowed:${fence?.grant?.value}:$origin"
                session?.setAccess(allowed, fence, origin) ?: head!!.setAccess(allowed, fence, origin)
            }
            override fun accessRevised() {
                deliveries += "revision"
                if (holdRevisionNotifications) heldRevisionNotifications++
                else { forwardedRevisionNotifications++; revise() }
            }
        }
        deliverer = TopicGrantDeliverer(issuer, sink, AuthFenceStream { callback -> fenceObserver = callback; callback(identity) }, h.parts.scope, clock,
            deliveryDispatcher = StandardTestDispatcher(test.testScheduler))
    }
    suspend fun approve() {
        h.grantedKrxVisible()
        assertTrue("premise: $row real PAC grants GENERAL", snapshot.facts.userAllowed)
        if (on) {
            assertTrue("premise: $row real PAC has a permit", snapshot.krxPermitSeq > 0)
            assertEquals("premise: $row OPEN", KrxContestPhase.OPEN, pac.krxContestView?.state?.phase)
        }
    }
    fun start() {
        if (session != null) { session.start(); session.setOnline(true); session.setFocus(checkNotNull(identity), FreeTab.USD) }
        else { head!!.start(); head.setOnline(true); head.setFocus(checkNotNull(identity), FreeTab.USD) }
        deliverer.start()
        until("premise: $row grant reaches one connection", 250) { wires.size == 1 }
    }
    fun open() { wire.open(); drain() }
    fun drain() = test.runCurrent()
    fun advance(ms: Long) { require(ms >= 0); test.advanceTimeBy(ms); drain() }
    fun until(message: String, budget: Long = 1_000, predicate: () -> Boolean) {
        val end = clock.nowMillis() + budget
        drain()
        while (!predicate() && clock.nowMillis() < end) advance(minOf(10, end - clock.nowMillis()))
        assertTrue(message, predicate())
    }
    fun revise() { session?.accessRevised() ?: head!!.accessRevised() }
    fun request(topic: String) { session?.requestBootstrap(topic) ?: head!!.requestBootstrap(topic); drain() }
    fun first(): TopicSubscribeRequest {
        until("premise: $row FIRST physically sent") { sends.isNotEmpty() }
        return sends.first().request
    }
    fun ack(request: TopicSubscribeRequest = sends.last().request, reject: Boolean = false, seconds: Long? = 600) {
        wire.deliver(ackText(request, reject, seconds)); drain()
    }
    private data class FakeLease(val id: String, val seconds: Long)
    private val activeByWire = mutableMapOf<KrxWire, MutableSet<String>>()
    private val leasesByWire = mutableMapOf<KrxWire, MutableMap<String, FakeLease>>()
    fun ackText(request: TopicSubscribeRequest, reject: Boolean = false, seconds: Long? = 600): String =
        ackFor(request, if (reject) mapOf(KRX to "krx_entitlement_required") else emptyMap(), seconds)
    fun ackFor(request: TopicSubscribeRequest, rejected: Map<String, String>, seconds: Long? = 600,
        leaseTopics: Set<String> = request.topics.toSet()): String {
        val accepted = request.topics.filterNot { it in rejected }
        val active = activeByWire.getOrPut(wire) { linkedSetOf() }
        val leases = leasesByWire.getOrPut(wire) { linkedMapOf() }
        active.addAll(accepted); active.removeAll(rejected.keys)
        rejected.keys.forEach(leases::remove)
        if (seconds != null) for (topic in accepted) if (topic in leaseTopics) {
            leases[topic] = FakeLease("${request.requestId}-$topic", seconds)
        }
        fun entry(topic: String): String {
            val lease = leases[topic]
            return if (lease == null) """{"topic":"$topic"}"""
            else """{"topic":"$topic","lease_id":"${lease.id}","lease_duration_seconds":${lease.seconds}}"""
        }
        val refusals = rejected.entries.joinToString(",") { (topic, reason) -> """{"topic":"$topic","error":"$reason"}""" }
        return """{"type":"subscription_ack","request_id":"${request.requestId}","operation":"subscribe","accepted_topics":[${accepted.joinToString(",", transform = ::entry)}],"rejected_topics":[$refusals],"removed_topics":[],"active_subscriptions":[${active.joinToString(",", transform = ::entry)}]}"""
    }
    fun rejectForManual(includeKrx: Boolean = false) {
        store.markDegraded(USD)
        if (includeKrx) store.markDegraded(KRX)
        assertTrue("premise: $row HEAD canonical state makes USD manual-eligible", USD in store.snapshot.manualRetryTopics)
    }
    fun authFenceChanged(value: AuthIdentityFence) {
        identity = value; credential = AuthSnapshot(value.uid, value.authGeneration, "fixture-${value.authGeneration}")
        h.source.liveFence = value
        h.source.identity = com.jay.fxi.data.entitlements.EntitlementsIdentity(value.uid, value.authGeneration)
        fenceObserver?.invoke(value)
    }
    fun complete(call: Call, outcome: TopicSnapshotOutcome) { call.token.complete(Unit); call.body.complete(outcome); drain() }
    fun delivered(text: String) = TopicSnapshotOutcome.Delivered(decoder(text))
    private var generalTickSerial = 0L
    private var krxTickSerial = 0L
    fun generalTick(rate: Double = 1444.0) {
        val at = kotlinx.datetime.Instant.fromEpochMilliseconds(kotlinx.datetime.Instant.parse("2026-08-31T01:20:00Z").toEpochMilliseconds() + ++generalTickSerial)
        wire.deliver(TopicSessionKrxHarness.tetherFrame(rate, at.toString())); drain()
    }
    fun krxTick(rate: Double = 1555.0) {
        val at = kotlinx.datetime.Instant.fromEpochMilliseconds(kotlinx.datetime.Instant.parse("2026-09-26T01:20:00Z").toEpochMilliseconds() + ++krxTickSerial)
        wire.deliver(krxFrame(rate).replace("2026-09-26T10:20:00+09:00", at.toString())); drain()
    }
    fun assertGeneral(rate: Double = 1444.0) {
        generalTick(rate)
        assertTrue("premise: $row GENERAL frame remains usable", rate in rates.quotes.values.map { it.rate })
    }
    suspend fun falseThenTrue() {
        h.source.next = { EntitlementsOutcome.StableActive(false) }; pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); drain()
        h.source.next = { EntitlementsOutcome.StableActive(true) }; pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); drain()
    }
    suspend fun awaiting(): CompletableDeferred<EntitlementsOutcome> {
        val gate = CompletableDeferred<EntitlementsOutcome>().also { h.source.gates += it }
        h.rejectKrx(h.issueGrant()); drain()
        assertEquals("premise: $row AWAITING window", KrxContestPhase.AWAITING, pac.krxContestView?.state?.phase)
        assertTrue("premise: $row capability withheld", !snapshot.facts.capabilityAllowed)
        return gate
    }
    suspend fun hold() {
        h.rejectKrx(h.issueGrant()); drain()
        until("premise: $row first reapproval OPEN", 1_000) { pac.krxContestView?.state?.phase == KrxContestPhase.OPEN }
        h.rejectKrx(h.issueGrant()); drain()
        assertEquals("premise: $row HOLD window", KrxContestPhase.HOLD, pac.krxContestView?.state?.phase)
    }
    fun assertWindow(phase: KrxContestPhase) = assertEquals("premise: $row still inside $phase window", phase, pac.krxContestView?.state?.phase)
    fun reconnect() {
        val count = wires.size
        wire.drop(); until("premise: $row reconnect created", 2_600) { wires.size == count + 1 }
        open()
    }
    fun recover() {
        val orders = h.parts.orders
        val event = AuthCredentialRecovery(checkNotNull(identity), orders.next(), orders.next(), orders.next())
        session?.onCredentialRecovered(event) ?: head!!.onCredentialRecovered(event); drain()
    }
    fun manual(tab: FreeTab = FreeTab.USD) {
        val owner = checkNotNull(display.owner)
        session?.retryTopics(owner, tab) ?: head!!.retryTopics(owner, tab); drain()
    }
    fun trace(): List<String> = listOf(
        "events=$events", "deliveries=$deliveries", "states=$states", "rates=$rates", "display=$display",
        "graph=${graphTrace()}", "offers=$offers",
        "ticket=${privateValue(store, "currentTicket")}:${privateValue(store, "requestSerial")}:${privateValue(store, "currentAuthTicket")}:${privateValue(store, "authRefreshSerial")}", "calls=${calls.map { listOf(it.topic, it.at, it.stage, it.exchanges, it.cancelled) }}",
        "access=${snapshot.revision}:${snapshot.krxPermitSeq}:${snapshot.userInvalidations}:${snapshot.capabilityInvalidations}:${snapshot.facts}",
        "reads=${reads.map { (kind, value) -> "$kind:${value.revision}:${value.krxPermitSeq}" }}",
        "reserved=${issuer.reserved.map { listOf(it.arity, it.grant, it.marker, it.reasons) }}"
    )
    private fun graphTrace(): List<List<Any?>> {
        val keys = java.util.IdentityHashMap<Any, Int>()
        fun key(value: Any): Int = keys[value] ?: keys.size.also { keys[value] = it }
        return graph.map { input -> when (input) {
            is TopicGraphInput.Observations -> listOf(input.sequence, input.topic, input.path,
                key(input.attribution.sessionKey), input.attribution.owner, input.attribution.grantEpoch,
                input.attribution.lifetime, input.connectionGeneration, input.candidates)
            is TopicGraphInput.Continuity -> listOf(input.sequence, input.kind, input.reason, input.topics, input.paths,
                key(input.authority.sessionKey), input.authority.owner, input.authority.grantEpoch,
                input.authority.lifetime, input.connectionGeneration, input.occurredAtEpochMillis, input.payload)
        } }
    }
    /** Holds the actual posted CommandDone object, preserving its id/order; useful for both recovery input orders. */
    inner class CommandDoneGate : AutoCloseable {
        private val target = checkNotNull(session)
        @Suppress("UNCHECKED_CAST")
        private val original = privateValue(target, "inputs") as Channel<Any>
        private val held = Channel<Any>(Channel.UNLIMITED)
        var intercepted = 0
            private set
        private var open = false
        init {
            val relay = object : Channel<Any> by original {
                override fun trySend(element: Any): ChannelResult<Unit> =
                    if (!open && element.javaClass.simpleName == "CommandDone") {
                        intercepted++; held.trySend(element)
                    } else original.trySend(element)
            }
            setPrivate(target, "inputs", relay)
        }
        fun release() {
            open = true
            while (true) original.trySend(held.tryReceive().getOrNull() ?: break)
            drain()
        }
        override fun close() { release(); setPrivate(target, "inputs", original); held.close() }
    }
    fun holdCommandDone() = CommandDoneGate()
    fun commandJob(record: CommandRecord): Job? {
        val live = privateValue(checkNotNull(session), "connection") ?: return null
        @Suppress("UNCHECKED_CAST")
        val running = privateValue(live, "commands") as Map<Long, Any>
        return running[record.id]?.let { privateValue(it, "job") as Job? }
    }
    fun flushRefusals() { delayedRefusals.toList().also { delayedRefusals.clear() }.forEach { it() }; drain() }
    fun close() { job.cancel(); drain() }
    companion object {
        const val KRX = TopicCatalogue.KRX_FUTURES
        const val USD = "fx:usd-krw"
        const val TETHER = TopicCatalogue.TETHER
        fun setPrivate(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)
        }
        fun privateValue(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true; it.get(target)
        }
        fun krxFrame(rate: Double) = """{"type":"snapshot","version":1,"topic":"$KRX","data":{"usd_krw_futures":{"source":"krx","asset":"usd-krw-futures","rate":$rate,"timestamp":"2026-09-26T10:20:00+09:00"}}}"""
    }
}

/** The check is unconditional, including an assertion failure before start/open. OFF has no ON checker. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun a1aTest(row: String, desired: Set<String> = TopicCatalogue.DESIRED + KrxSessionIntegrationRig.KRX, body: suspend TestScope.(KrxSessionIntegrationRig) -> Unit) = runTest {
    val rig = KrxSessionIntegrationRig(this, row, desired = desired)
    var failure: Throwable? = null
    try { body(rig) } catch (t: Throwable) { failure = t; throw t } finally {
        try { rig.beta!!.check.assertHeld() } catch (t: Throwable) {
            if (failure != null) failure.addSuppressed(t) else throw t
        } finally { rig.close() }
    }
}
