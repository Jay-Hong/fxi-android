package com.jay.fxi.data.remote

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.purge.KrxMemoryScope
import com.jay.fxi.data.graph.*
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.field
import com.jay.fxi.data.remote.S6KrxCleanupRig.Companion.setField
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.Instant
import org.junit.Assert.*

/** Actual checked PAC, typed session, wire ACKs and Retrofit REST producer; no private method calls. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S6KrxGraphSessionRig(val test: TestScope, val row: String, on: Boolean = true) {
    val cleanup = S6KrxCleanupRig(test, row, on)
    val r get() = cleanup.r
    val fence = KrxGraphFence()
    val issued = mutableListOf<Pair<KrxMemoryScope, KrxGraphStamp?>>()
    val cutoffs = mutableListOf<Pair<KrxMemoryScope?, KrxGraphTopicCutoff>>()
    val inputs = mutableListOf<TopicGraphInput>()
    val offers = mutableListOf<Pair<TopicGraphInput, TopicGraphOffer>>()
    var target: TopicGraphSink = TopicGraphSink { TopicGraphOffer.ENQUEUED }
    val ingress = object : KrxGraphIngress {
        override fun capture(scope: KrxMemoryScope): KrxGraphStamp? = fence.capture(scope).also { issued += scope to it }
        override fun topicUnavailable(original: KrxMemoryScope?): KrxGraphTopicCutoff =
            fence.topicUnavailable(original).also { cutoffs += original to it }
    }
    init {
        // Dependency assembly only, before start. Both fields are read back to catch fixture drift.
        setField(cleanup.session, "krxGraphIngress", ingress)
        val sink = TopicGraphSink { input -> inputs += input; target.tryOffer(input).also { offers += input to it } }
        setField(cleanup.session, "graphSink", sink)
        assertSame("premise: $row graph sink dependency installed", sink, field(cleanup.session, "graphSink"))
        assertSame("premise: $row ingress dependency installed", ingress, field(cleanup.session, "krxGraphIngress"))
    }
    fun c(text: String) = "contract(${row.substringBefore('/')}/${row.substringAfter('/', "main")}): $text"
    fun yes(text: String, value: Boolean) = assertTrue(c(text), value)
    fun eq(text: String, expected: Any?, actual: Any?) = assertEquals(c(text), expected, actual)
    fun <T : Any> need(value: T?, text: String): T = value ?: throw AssertionError(c(text))
    suspend fun start(ack: Boolean = true, leaseSeconds: Long = 600) {
        if (r.on) cleanup.live(ack = ack, rates = false, seconds = leaseSeconds)
        else {
            r.approve(); r.start(); r.open(); val first = r.first()
            if (ack) r.ack(first, seconds = leaseSeconds)
        }
    }
    fun ack() = r.ack(cleanup.lastKrxRequest())
    fun frame(rate: Double = 1555.0, at: Instant = AT, changed: Instant? = null,
        source: String = "krx", asset: String = "usd-krw-futures"): String {
        val change = changed?.let { ",\"rate_changed_at\":\"$it\"" } ?: ""
        return """{"type":"snapshot","version":1,"topic":"${TopicCatalogue.KRX_FUTURES}","data":{"usd_krw_futures":{"source":"$source","asset":"$asset","rate":$rate,"timestamp":"$at"$change}}}"""
    }
    fun deliver(text: String) { r.wire.deliver(text); r.drain() }
    fun ws(rate: Double = 1555.0, at: Instant = AT, changed: Instant? = null): TopicGraphInput.Observations {
        val before = inputs.size
        deliver(frame(rate, at, changed))
        val batch = need(inputs.drop(before).filterIsInstance<TopicGraphInput.Observations>()
            .singleOrNull { it.topic == TopicCatalogue.KRX_FUTURES }, "validated actual WS produces exactly one dedicated batch")
        eq("WS path", TopicGraphPath.WS, batch.path)
        origin(batch)
        return batch
    }
    fun origin(batch: TopicGraphInput.Observations): KrxGraphStamp {
        val stamp = need(batch.krxGraphStamp, "original source stamp is carried")
        yes("stamp was issued at a source, not reconstructed at consumption", issued.any { it.second === stamp })
        eq("original owner", batch.attribution.owner, stamp.scope.owner)
        eq("original GENERAL lifetime", batch.attribution.lifetime, stamp.scope.use.lifetime)
        yes("positive source issue sequence", stamp.issueSequence > 0)
        return stamp
    }
    fun rest(rate: Double = 1555.0, at: Instant = AT, changed: Instant? = null,
        beforeComplete: () -> Unit = {}): TopicGraphInput.Observations {
        val call = cleanup.issue()
        yes("REST producer is live before completion", !call.cancelled && call.outcome == null)
        val before = inputs.size
        beforeComplete()
        cleanup.f.reply(call, KrxA1bFixture.Reply(200, frame(rate, at, changed)))
        yes("actual producer completed normally", call.stage == KrxA1bFixture.Stage.RETURNED && !call.cancelled && call.outcome != null)
        val batch = need(inputs.drop(before).filterIsInstance<TopicGraphInput.Observations>()
            .singleOrNull { it.topic == TopicCatalogue.KRX_FUTURES }, "actual REST completion produces one dedicated batch")
        eq("REST path", TopicGraphPath.REST_BOOTSTRAP, batch.path)
        origin(batch)
        return batch
    }
    fun d1(): TopicSubscribeRequest {
        // A renewal is a new SENT command on the current connection, while the old ACK remains active.
        val request = renewal()
        val before = cutoffs.size
        cleanup.unavailable(request)
        yes("verified current D1 invokes ingress once", cutoffs.size == before + 1)
        val (original, result) = cutoffs.last()
        need(original, "D1 has the original accepted command scope")
        eq("D1 installs an access cutoff, not a purge result", KrxGraphTopicResult.INSTALLED, result.result)
        return request
    }
    fun renewal(topic: String = TopicCatalogue.KRX_FUTURES): TopicSubscribeRequest {
        // A <=180s lease schedules renewal during ACK drain; observe the live lane before waiting.
        fun outstanding(): TopicSubscribeRequest? {
            val live = field(cleanup.session, "connection") ?: return null
            val lane = field(live, "controlOwner") as Long? ?: return null
            @Suppress("UNCHECKED_CAST") val commands = field(live, "commands") as Map<Long, Any>
            val running = commands[lane] ?: return null
            val purpose = field(running, "purpose") as TopicCommandPurpose
            if (purpose !in setOf(TopicCommandPurpose.LEASE_RENEWAL, TopicCommandPurpose.LEASE_RENEWAL_WITH_REVALIDATION)) return null
            return r.sends.lastOrNull { it.accepted && it.request.type == "subscribe" && topic in it.request.topics }?.request
        }
        outstanding()?.let { return it }
        val before = r.sends.size
        // Actual lease policy is max(0, duration-180-jitter). Advance to its deadline, not an invented 1.2s wait.
        val live = need(field(cleanup.session, "connection"), "renewal has a current connection")
        val registry = field(live, if (r.on && TopicCatalogue.carriesKrx(topic)) "krxLeases" else "leases") as com.jay.fxi.domain.model.TopicLeaseRegistry
        val grant = need(registry.held[topic], "renewal has an actual acknowledged lease")
        val wait = (grant.expiresAtMillis - 180_000L - r.clock.nowMillis()).coerceAtLeast(0)
        r.advance(wait)
        r.until(c("actual renewal is SENT on its live control lane"), 1_200) {
            r.sends.drop(before).any { it.accepted && it.request.type == "subscribe" && topic in it.request.topics }
        }
        return need(outstanding(), "actual renewal remains pending on the current control lane")
    }
    suspend fun capEnd() {
        r.h.source.next = { EntitlementsOutcome.StableActive(false) }
        r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.drain()
        yes("inside confirmed cap-end window", !r.snapshot.facts.capabilityAllowed && r.snapshot.lastCapabilityEnd != null)
    }
    suspend fun capReopen() {
        r.h.source.next = { EntitlementsOutcome.StableActive(true) }
        r.pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS); r.drain()
        yes("new capability allowed", r.snapshot.facts.capabilityAllowed)
    }
    // A typed rejection literal is confined to this approved helper (current inventory amendment).
    fun runtimeFactory(): TopicRuntimeFactory = TopicRuntimeFactory(
        webSocketFactory = {
            KrxWire { text, accepted ->
                r.requests.lastOrNull { "encoded-${it.requestId}" == text }?.let {
                    r.sends += KrxSessionIntegrationRig.WireSend(it, accepted, r.clock.nowMillis())
                }
            }.also { r.wires += it }
        },
        webSocketUrl = TopicSessionKrxHarness.URL,
        decode = TopicFrameDecoder(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })::decode,
        bootstrap = { owner, topic, admitted -> need(r.bootstrapOverride, "actual REST service dependency")(owner, topic, admitted) },
        issuer = r.issuer,
        fences = com.jay.fxi.data.auth.AuthFenceStream { it(r.identity) },
        liveFence = { r.identity }, recoveries = com.jay.fxi.data.auth.AuthCredentialRecoveryStream { },
        tabs = object : com.jay.fxi.data.local.FreeTabStore {
            override suspend fun lastTab(uid: String) = com.jay.fxi.domain.model.FreeTab.USD
            override suspend fun remember(uid: String, tab: com.jay.fxi.domain.model.FreeTab) = Unit
        },
        credentials = field(cleanup.session, "credentials") as TopicCommandCredentials,
        orders = r.h.parts.orders, authority = r.uses, clock = r.clock,
        newBootstrapFloor = { object : TopicBootstrapFloor {
            override fun record(statusCode: Int?, retryAfter: String?) = Unit
            override fun notBeforeMillis() = 0L
        } }, newScope = { r.h.parts.scope },
        encode = { r.requests += it; "encoded-${it.requestId}" },
        newRequestId = { "graph-runtime-${r.requests.size}" }, jitter = { 0.0 },
        krxSendReader = r.uses, krxMemorySnapshot = { r.snapshotSupply() }
    )

    /** Observe the factory's actual dependencies, including NONE if it was miswired; never replace them with our fence. */
    fun attach(runtime: TopicRuntime, desired: Set<String> = r.desired, wallClock: (() -> Long)? = null) {
        val assembled = field(runtime, "session") as TopicSessionCoordinator
        if (wallClock != null) {
            // Existing TSC clock dependency, installed before start. Factory has no wallClock argument.
            setField(assembled, "wallClock", wallClock)
            assertSame("premise: $row scripted TSC wall clock installed", wallClock, field(assembled, "wallClock"))
        }
        setField(assembled, "desired", desired)
        setField(r, "session", assembled)
        val actualInput = field(assembled, "graphSink") as TopicGraphSink
        val actualIngress = field(assembled, "krxGraphIngress") as KrxGraphIngress
        setField(assembled, "graphSink", TopicGraphSink { input -> inputs += input; actualInput.tryOffer(input).also { offers += input to it } })
        setField(assembled, "krxGraphIngress", object : KrxGraphIngress {
            override fun capture(scope: KrxMemoryScope) = actualIngress.capture(scope).also { issued += scope to it }
            override fun topicUnavailable(original: KrxMemoryScope?) = actualIngress.topicUnavailable(original).also { cutoffs += original to it }
        })
    }
    fun rejectionKind() = TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED
    fun close() { fence.close(); cleanup.close() }
    companion object { val AT: Instant = Instant.parse("2026-09-26T01:20:00Z") }
}
