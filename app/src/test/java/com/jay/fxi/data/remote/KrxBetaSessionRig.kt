package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthFenceStream
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.KrxBetaPac
import com.jay.fxi.data.entitlements.KrxContestPhase
import com.jay.fxi.data.entitlements.KrxHarnessDefaults
import com.jay.fxi.data.entitlements.KrxHarnessFactory
import com.jay.fxi.data.entitlements.PremiumAccessTopicGrantIssuer
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicGrantDeliverer
import com.jay.fxi.data.entitlements.grantedKrxVisible
import com.jay.fxi.data.entitlements.krxBetaPac
import com.jay.fxi.data.entitlements.krxHarness
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** β §3: fresh assembly, leaving TSCT's private OFF helpers unchanged. No KRX subscription is enabled. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxBetaSessionRig(val test: TestScope) {
    private val job = SupervisorJob()
    lateinit var beta: KrxBetaPac
        private set
    val krx = test.krxHarness(job, KrxHarnessFactory { p ->
        krxBetaPac(p.source, p.store, p.purger, p.purger, p.scope, p.clock,
            { p.source.liveFence }, p.orders).also { beta = it }.coordinator
    })
    val pac get() = beta.coordinator
    val snapshot get() = pac.accessSnapshot
    val identity = AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)
    val uses = SnapshotTopicUseAuthority { snapshot }
    val issuer = PremiumAccessTopicGrantIssuer(pac)
    val store = TopicSubscriptionStateStore()
    val wires = mutableListOf<KrxWire>()
    val connectGenerations = mutableListOf<Long>()
    val requests = mutableListOf<TopicSubscribeRequest>()
    val graph = mutableListOf<TopicGraphInput>()
    data class Live(val uid: String, val epoch: String, val rates: TopicRates)
    val live = mutableListOf<Live>()
    data class Delivery(val allowed: Boolean, val fence: TopicSessionFence?, val origin: TopicGrantOrigin)
    val deliveries = mutableListOf<Delivery>()
    var revisions = 0
        private set
    data class Bootstrap(
        val topic: String,
        val at: Long,
        val admitted: () -> Boolean,
        val answer: CompletableDeferred<TopicSnapshotOutcome> = CompletableDeferred(),
        var returned: Boolean = false
    )
    val bootstraps = mutableListOf<Bootstrap>()
    private val decoder = TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode
    val clock = object : TopicCommandClock {
        override fun nowMillis() = test.testScheduler.currentTime
        override suspend fun sleep(duration: Duration) = delay(duration)
    }
    private val credentials = object : TopicCommandCredentials {
        override suspend fun currentSnapshot() = AuthSnapshot(identity.uid, identity.authGeneration, "test-token")
        override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? = null
        override suspend fun recordRejected(credential: AuthSnapshot) = Unit
        override suspend fun recordRejectionEvidence(credential: AuthSnapshot) = Unit
    }
    private lateinit var deliverer: TopicGrantDeliverer
    val session = TopicSessionCoordinator(
        scope = krx.parts.scope, clock = clock,
        connect = { generation ->
            connectGenerations += generation
            val wire = KrxWire { _, _ -> }.also { wires += it }
            TopicTransport(wire, decoder).also { it.open(Request.Builder().url(TopicSessionKrxHarness.URL).build()) }
        },
        credentials = credentials, orders = krx.parts.orders, authority = uses,
        bootstrap = { _, topic, admitted ->
            val call = Bootstrap(topic, clock.nowMillis(), admitted).also { bootstraps += it }
            if (!admitted()) throw TopicUseWithheldException(emptyList())
            call.answer.await().also { call.returned = true }
        },
        store = store,
        encode = { request -> requests += request; Json.encodeToString(TopicSubscribeRequest.serializer(), request) },
        newRequestId = { "r${requests.size + 1}" }, jitter = { 0.0 }, liveIdentity = { identity },
        bootstrapIssueGap = 500.milliseconds,
        onRejected = { owner, reasons -> deliverer.forwardRejection(owner, reasons) },
        desired = setOf(USD, TETHER),
        offerLive = { uid, epoch, rates -> live += Live(uid, epoch, rates) },
        graphSink = { input -> graph += input; TopicGraphOffer.ENQUEUED },
        wallClock = { 1_000_000L + clock.nowMillis() },
        krxSendReader = uses
    )
    init {
        val sink = object : TopicGrantSink {
            override fun setAccess(allowed: Boolean, fence: TopicSessionFence?, origin: TopicGrantOrigin) {
                deliveries += Delivery(allowed, fence, origin)
                session.setAccess(allowed, fence, origin)
            }
            override fun accessRevised() { revisions++; session.accessRevised() }
        }
        deliverer = TopicGrantDeliverer(issuer, sink, AuthFenceStream { it(identity) }, krx.parts.scope, clock,
            deliveryDispatcher = StandardTestDispatcher(test.testScheduler))
    }
    suspend fun approve(): TopicSessionFence {
        krx.grantedKrxVisible()
        val fence = checkNotNull(issuer.topicGrantResult().fence) { "contract(ON-T): issued fence" }
        assertEquals("contract(ON-T): OPEN", KrxContestPhase.OPEN, pac.krxContestView?.state?.phase)
        assertTrue("contract(ON-T): positive permit", snapshot.krxPermitSeq >= 1)
        assertTrue("contract(ON-T): capability allowed", snapshot.facts.capabilityAllowed)
        assertTrue("contract(ON-T): GENERAL usable", uses.acquire(fence) != null)
        return fence
    }
    fun goLive() {
        session.start(); session.setOnline(true); session.setFocus(identity, FreeTab.USD)
        deliverer.start(); step(100)
        assertEquals("contract(ON-T): one connection through deliverer", 1, wires.size)
        wires.single().open(); step(1)
        val request = requests.single()
        wires.single().deliver(ack(request.requestId)); step(1)
    }
    suspend fun rotate() {
        krx.source.next = { EntitlementsOutcome.StableActive(krxVisible = false) }
        pac.refresh(RefreshIntent.FORCE_ENTITLEMENTS)
        step(100)
    }
    fun step(ms: Long = 0) { if (ms > 0) test.advanceTimeBy(ms); test.runCurrent() }
    fun delivered(text: String) = TopicSnapshotOutcome.Delivered(decoder(text))
    fun observations() = graph.filterIsInstance<TopicGraphInput.Observations>()
    fun close() = job.cancel()

    companion object {
        const val USD = "fx:usd-krw"
        const val TETHER = "usdt:krw"
        fun tether(rate: Double) = TopicSessionKrxHarness.tetherFrame(rate)
        fun usd(rate: Double) = TopicSessionKrxHarness.usdFrame(rate)
        private fun ack(id: String) = """{"type":"subscription_ack","request_id":"$id","operation":"subscribe","accepted_topics":[{"topic":"$USD"},{"topic":"$TETHER"}],"rejected_topics":[],"removed_topics":[],"active_subscriptions":[{"topic":"$USD"},{"topic":"$TETHER"}]}"""
    }
}

/** Keep the factory and its unconditional checker teardown together for KW15's lifecycle audit. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun krxBetaSessionTest(body: suspend TestScope.(KrxBetaSessionRig) -> Unit) = runTest {
    val rig = KrxBetaSessionRig(this)
    var failure: Throwable? = null
    try { body(rig) } catch (t: Throwable) { failure = t; throw t } finally {
        try { rig.beta.check.assertHeld() } catch (t: Throwable) {
            if (failure != null) failure.addSuppressed(t) else throw t
        } finally { rig.close() }
    }
}
