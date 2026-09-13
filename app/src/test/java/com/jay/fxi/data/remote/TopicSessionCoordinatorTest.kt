package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.AccessEpochRecord
import com.jay.fxi.data.entitlements.AccessEpochStore
import com.jay.fxi.data.entitlements.AccessEpochTransitions
import com.jay.fxi.data.entitlements.LossObligation
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.EntitlementsResult
import com.jay.fxi.data.entitlements.EntitlementsSource
import com.jay.fxi.data.entitlements.EpochIdGenerator
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.data.entitlements.ProbeJitter
import com.jay.fxi.data.entitlements.CapabilityScopePurger
import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeResult
import com.jay.fxi.data.entitlements.UserScopePurger
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionAckTopic
import com.jay.fxi.data.remote.dto.SubscriptionRejection
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.TopicAuthResolution
import com.jay.fxi.domain.model.TopicControlState
import com.jay.fxi.domain.model.TopicDeliveryState
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionSnapshot
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session's lifetime: one socket at a time, one subscribe per connection, and one ladder.
 *
 * The pieces this drives are all checked where they live — the reconnection arithmetic, the
 * command, the merge rule. What is checked here is what only the coordinator decides: which
 * generation an answer belongs to, what a disconnection destroys, and what it does *not* do
 * afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicSessionCoordinatorTest {

    private companion object {
        const val URL = "http://localhost/ws"
        const val PONG = """{"type":"pong"}"""
        const val TETHER = TopicCatalogue.TETHER
        const val USD = "fx:usd-krw"
        val STABILITY_MS = 30_000L
        val PING_MS = 30_000L
        fun fence(uid: String = "u1", generation: Long = 1L, epoch: String = "epoch-1", grant: Long = 1L) =
            TopicSessionFence(AuthIdentityFence(uid, generation), epoch, TopicGrantToken(grant))
    }

    /** One connection's socket, and the listener the coordinator's transport installed on it. */
    private class Wire : WebSocket.Factory {
        val sent = mutableListOf<String>()
        var cancelled = false
        var listener: WebSocketListener? = null
        var sendSucceeds = true

        /**
         * Answer keep-alives, as a live peer does.
         *
         * On by default so a test about something else is not quietly ended by a pong deadline
         * forty seconds in — which is what happened to the one about a command's three attempts,
         * and made it look as though the command had stopped early. The keep-alive tests turn it
         * off, because for them the silence is the subject.
         */
        var autoPong = true

        private val socket = object : WebSocket {
            override fun cancel() { cancelled = true }
            override fun close(code: Int, reason: String?) = true
            override fun queueSize() = 0L
            override fun request() = Request.Builder().url(URL).build()
            override fun send(text: String): Boolean {
                if (!sendSucceeds) return false
                sent += text
                if (autoPong && text == "ping") listener?.onMessage(this, PONG)
                return true
            }
            override fun send(bytes: okio.ByteString) = true
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return socket
        }

        fun open() = listener!!.onOpen(
            socket,
            Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
                .code(101).message("").build()
        )

        fun deliver(text: String) = listener!!.onMessage(socket, text)

        fun drop() = listener!!.onFailure(socket, java.io.IOException("dropped"), null)

        fun closed() = listener!!.onClosed(socket, 1000, "bye")
    }

    private class Harness(test: TestScope, desired: Set<String> = setOf(TETHER, USD)) {
        val scheduler = test.testScheduler
        /**
         * `runTest`'s own background scope, not a scope of this harness's making.
         *
         * A failing assertion skips the `cleanUp()` at the end of each test, and a private
         * `SupervisorJob` then keeps the keep-alive loop scheduling for ever — the failure never
         * finishes reporting. `backgroundScope` is cancelled by `runTest` however the test ends,
         * and work left only there does not hold it open either. Every wait in this file is an
         * `advanceTimeBy`, which does run background work; `advanceUntilIdle` would not, and that
         * is the trap recorded for this repo. Patch from review.
         */
        val scope = test.backgroundScope
        val store = TopicSubscriptionStateStore()
        /** Every wait asked for, which is how a command's deadline windows are seen from here. */
        val sleeps = mutableListOf<Duration>()
        val clock = object : TopicCommandClock {
            override fun nowMillis(): Long = scheduler.currentTime + clockSkewMillis
            override suspend fun sleep(duration: Duration) {
                sleeps += duration
                delay(duration)
            }
        }
        var credential = AuthSnapshot("u1", 1L, "token-1")
        var refreshed: AuthSnapshot? = null

        /** Held to keep a command inside its token wait, which is where a deadline can pass. */
        var credentialGate: CompletableDeferred<Unit>? = null
        val credentials = object : TopicCommandCredentials {
            override suspend fun currentSnapshot(): AuthSnapshot {
                credentialGate?.await()
                return credential
            }

            override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot) = refreshed
        }
        val acknowledgements = mutableListOf<TopicCommandAcknowledgement>()

        /** Every bootstrap the session issued, with the grant each one bound itself to. */
        val bootstrapCalls = mutableListOf<Pair<AuthIdentityFence, String>>()

        /** Held to keep one bootstrap in flight while the session moves on underneath it. */
        var bootstrapGate: CompletableDeferred<Unit>? = null

        /** Every bootstrap that got past the gate — which a cancelled one does not. */
        val bootstrapFinished = mutableListOf<String>()

        /** Transport-level HTTP evidence handed out ahead of the grant filter. */
        val bootstrapEvidence = mutableListOf<Pair<Int?, String?>>()

        /** Every non-delivery handed over, with the grant the session named for it. */
        val undelivered = mutableListOf<Triple<TopicSessionFence, String, TopicSnapshotOutcome>>()

        /**
         * What the REST twin answers, chosen by **issue** as well as topic.
         *
         * Read after the gate, so a test can stage the answer while the request is held — and
         * keyed on the issue because topic alone cannot tell one request from another. A test that
         * holds a request and then makes the session ask again gets two calls for the same topic,
         * and with a topic-only fake both would answer identically: "the held answer leaked" and
         * "the second request delivered" would be the same bytes on screen.
         *
         * The default is the answer a session with nothing staged should get: not a verdict.
         */
        var bootstrapOutcome: (issue: Int, topic: String) -> TopicSnapshotOutcome = { _, _ ->
            TopicSnapshotOutcome.Unreachable(java.io.IOException("nothing staged"))
        }

        /** Every snapshot the session published, in order. */
        val topicStates = mutableListOf<TopicSubscriptionSnapshot>()

        /**
         * Thrown by the state observer on the **degraded** snapshot, once.
         *
         * Narrow on purpose. A listener that throws at any snapshot throws at the first one the
         * session publishes — the pending request, from inside the loop's own turn — and then what
         * is being watched is the loop dying of its own exception rather than the path under test.
         */
        var topicStateFailure: Throwable? = null

        /** Injected into the acknowledgement listener, to stand for one that misbehaves. */
        var acknowledgementFailure: Throwable? = null

        val wires = mutableListOf<Wire>()
        var failNextConnect = false
        val requests = mutableListOf<TopicSubscribeRequest>()
        val rejected = mutableListOf<Map<String, TopicRejectionReason>>()

        /** The fence each refusal was handed back with, and whether its socket had already gone. */
        val rejectedOwners = mutableListOf<TopicSessionFence>()
        val socketGoneAtRefusal = mutableListOf<Boolean>()

        /** Where a test sends each refusal on, the way runtime wiring would. Recorded above either way. */
        var refusalSink: ((TopicSessionFence, Map<String, TopicRejectionReason>) -> Unit)? = null
        val undecodable = mutableListOf<Pair<Int, String>>()
        var jitterUnit = 0.0
        var clockSkewMillis = 0L

        /** Applied to every socket this harness makes; see [Wire.autoPong]. */
        var autoPong = true

        val decode = TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode

        /**
         * Who Firebase would say is signed in, right now.
         *
         * [setAccess] moves this with the grant as a **fixture convenience, not a production
         * invariant**: live identity and a queued `Access` really can differ, at the same uid
         * included. A test of that boundary sets this on its own and calls
         * `coordinator.setAccess` directly, so that granting does not also move the identity.
         */
        var liveFence: AuthIdentityFence? = fence().identity

        /** How many times the session actually observed the identity. Counted, not assumed. */
        var liveIdentityReads = 0

        val coordinator = TopicSessionCoordinator(
            scope = scope,
            clock = clock,
            connect = {
                if (failNextConnect) {
                    failNextConnect = false
                    throw java.io.IOException("refused")
                }
                val wire = Wire()
                wire.autoPong = autoPong
                wires += wire
                TopicTransport(wire, decode).also {
                    it.open(Request.Builder().url(URL).build())
                }
            },
            credentials = credentials,
            bootstrap = { owner, topic ->
                // Taken before the append and never re-read: the pair is non-suspending, and this
                // harness runs on one serial test dispatcher, so no other issue can land between.
                val issue = bootstrapCalls.size
                bootstrapCalls += owner to topic
                bootstrapGate?.await()
                bootstrapFinished += topic
                bootstrapOutcome(issue, topic)
            },
            store = store,
            encode = { request -> requests += request; "encoded-${request.requestId}" },
            newRequestId = { "r${requests.size + 1}" },
            jitter = { jitterUnit },
            liveIdentity = { liveIdentityReads++; liveFence },
            onBootstrapHttpEvidence = { status, retryAfter -> bootstrapEvidence += status to retryAfter },
            onRejected = { owner, reasons ->
                rejected += reasons
                rejectedOwners += owner
                socketGoneAtRefusal += wires.last().cancelled
                refusalSink?.invoke(owner, reasons)
            },
            onAcknowledgement = {
                acknowledgements += it
                acknowledgementFailure?.let { failure -> throw failure }
            },
            onUndecodable = { length, failure -> undecodable += length to failure },
            onBootstrapUndelivered = { owner, topic, outcome ->
                undelivered += Triple(owner, topic, outcome)
            },
            onTopicState = {
                topicStates += it
                if (it.stateFor(TETHER).deliveryState == TopicDeliveryState.DEGRADED) {
                    topicStateFailure?.let { failure ->
                        topicStateFailure = null
                        throw failure
                    }
                }
            },
            desired = desired
        )

        val wire: Wire get() = wires.last()

        /**
         * Grants access **and** moves the live identity with it.
         *
         * Every test that does not care about the apply-time identity check goes through here, so
         * the check is invisible to them; the ones that do care set [liveFence] afterwards.
         */
        fun setAccess(allowed: Boolean, granted: TopicSessionFence?) {
            liveFence = granted?.identity
            coordinator.setAccess(allowed, granted)
        }

        fun goLive() {
            coordinator.start()
            setAccess(true, fence())
            coordinator.setOnline(true)
        }

        fun ack(requestId: String, active: List<String>, rejections: Map<String, String> = emptyMap()) =
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

        fun ackWithLease(requestId: String, topic: String, seconds: Long) =
            Json.encodeToString(
                SubscriptionAck.serializer(),
                SubscriptionAck(
                    requestId = requestId,
                    operation = "subscribe",
                    acceptedTopics = listOf(SubscriptionAckTopic(topic)),
                    rejectedTopics = emptyList(),
                    removedTopics = emptyList(),
                    activeSubscriptions = listOf(SubscriptionAckTopic(topic, "lease-1", seconds))
                )
            ).replace("""{"request_id""", """{"type":"subscription_ack","request_id""")

        /** An acknowledgement whose `active_subscriptions` each carry a lease. */
        fun ackWithLeases(requestId: String, vararg leases: Triple<String, String, Long>) =
            Json.encodeToString(
                SubscriptionAck.serializer(),
                SubscriptionAck(
                    requestId = requestId,
                    operation = "subscribe",
                    acceptedTopics = leases.map { SubscriptionAckTopic(it.first) },
                    rejectedTopics = emptyList(),
                    removedTopics = emptyList(),
                    activeSubscriptions = leases.map {
                        SubscriptionAckTopic(it.first, it.second, it.third)
                    }
                )
            ).replace("""{"request_id""", """{"type":"subscription_ack","request_id""")

        fun fxFrame(rate: Double) =
            """{"type":"snapshot","version":1,"topic":"$USD","data":{"banks":[
               {"source":"kb","asset":"usd-krw","rate":$rate,
                "timestamp":"2026-08-31T10:20:00+09:00"}]}}"""

        /** A tether payload with no usable price — after D8 this is also the KRX-only shape. */
        fun emptyTetherFrame() =
            """{"type":"snapshot","version":1,"topic":"usdt:krw","data":{
               "usdt_krw":[],"usd_krw_banks":[]}}"""

        fun subscriptionError(requestId: String, code: String) =
            """{"type":"subscription_error","request_id":"$requestId","error":"$code"}"""

        fun tetherFrame(rate: Double, timestamp: String = "2026-08-31T10:20:00+09:00") =
            """{"type":"snapshot","version":1,"topic":"usdt:krw","data":{"usdt_krw":[
               {"source":"upbit","asset":"usdt-krw","rate":$rate,
                "timestamp":"$timestamp"}],"usd_krw_banks":[]}}"""

        /** One bootstrap answer, decoded by the same decoder the socket path uses. */
        fun delivered(text: String) = TopicSnapshotOutcome.Delivered(decode(text))

        fun dxyFrame(rate: Double) =
            """{"type":"snapshot","version":1,"topic":"dxy:spot","data":{"dxy":{
               "rate":$rate,"timestamp":"2026-08-31T10:20:00+09:00","source":"investing"}}}"""

        fun cleanUp() = scope.cancel()
    }

    /** Access and connectivity are both conditions; neither alone opens a socket. */
    @Test
    fun `a socket is opened only when the session has somewhere to be`() = runTest {
        val h = Harness(this)
        h.coordinator.start()
        h.coordinator.setOnline(true)
        advanceTimeBy(100)
        assertEquals("권한 없이 연결했다", 0, h.wires.size)

        h.setAccess(true, fence())
        advanceTimeBy(100)
        assertEquals(1, h.wires.size)
        h.cleanUp()
    }

    /** A first connection subscribes at once — the spread is for the ones that come back. */
    @Test
    fun `the first connection subscribes without waiting`() = runTest {
        val h = Harness(this)
        h.jitterUnit = 1.0
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        assertEquals(1, h.requests.size)
        assertEquals(setOf(TETHER, USD), h.requests.single().topics.toSet())
        h.cleanUp()
    }

    /**
     * A frame counts as delivery only if something in it survived validation.
     *
     * A publisher that sends a list of unusable numbers is a fault, and treating it as an answer
     * would let it satisfy the very watchdog meant to notice it. A frame whose entries were all
     * *older* than what is held is a different thing — the socket answered, and that is what the
     * generation counts.
     */
    @Test
    fun `receive evidence follows validation, not decoding`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        h.wire.deliver(h.tetherFrame(-1.0))
        advanceTimeBy(1)
        assertEquals("검증 탈락 프레임이 수신 증거가 됐다", 0L, h.store.snapshot.stateFor(TETHER).receiveGeneration)

        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        assertEquals(1L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
        assertEquals(1390.0, h.coordinator.rates.value.quotes.values.single().rate, 0.0)

        // Older than what is held: nothing merges, and it still counts.
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        assertEquals(2L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
        h.cleanUp()
    }

    /**
     * A snapshot with no usable price in it is not a delivery, however it got that way.
     *
     * The narrow case is a tether frame carrying only `usd_krw_futures`: D8 removed that field
     * from the DTO so it is swallowed as an unknown key, which leaves a payload identical to an
     * empty one — and `ANDROID_V2_PLAN.md` D14 says KRX must not satisfy tether delivery. The two
     * cannot be told apart here, so neither counts. Found by review.
     */
    @Test
    fun `empty and KRX-only snapshots are not delivery evidence`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        val frames = listOf(
            """{"type":"snapshot","version":1,"topic":"usdt:krw",
               "data":{"usdt_krw":[],"usd_krw_banks":[]}}""",
            """{"type":"snapshot","version":1,"topic":"fx:usd-krw",
               "data":{"banks":[]}}""",
            """{"type":"snapshot","version":1,"topic":"usdt:krw",
               "data":{"usdt_krw":[],"usd_krw_banks":[],
               "usd_krw_futures":{"source":"krx","asset":"usd-krw-futures",
               "rate":1390.0,"timestamp":"2026-08-31T10:20:00+09:00"}}}"""
        )
        frames.forEach { frame ->
            h.wire.deliver(frame)
            advanceTimeBy(1)
            assertEquals(0L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
            assertEquals(0L, h.store.snapshot.stateFor(USD).receiveGeneration)
            assertTrue(h.coordinator.rates.value.quotes.isEmpty())
        }
        assertTrue("디코드 실패로 샜다", h.undecodable.isEmpty())
        h.cleanUp()
    }

    /** The index has its own slot and its own evidence. */
    @Test
    fun `the dollar index is recorded under its own topic`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.dxyFrame(99.5))
        advanceTimeBy(1)

        assertEquals("구독하지 않는 topic 을 기록했다", 0L, h.store.snapshot.stateFor(TopicCatalogue.DXY).receiveGeneration)
        assertNull(h.coordinator.rates.value.dollarIndex)
        h.cleanUp()
    }

    /** Legacy `rates` proves nothing (I1) — not delivery, and not that the ladder may reset. */
    @Test
    fun `the legacy payload is ignored`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver("""{"type":"rates","data":{"rates":[]}}""")
        advanceTimeBy(1)

        assertEquals(0L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
        assertEquals(1, h.wires.size)
        h.cleanUp()
    }

    /** A frame the decoder refuses costs the frame, not the connection (D11). */
    @Test
    fun `an undecodable frame does not end the connection`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver("{ not json")
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)

        assertEquals(1, h.wires.size)
        assertEquals(1L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
        h.cleanUp()
    }

    /**
     * A dropped socket takes what the server said with it, and keeps what the user asked for.
     *
     * The reconnection then subscribes again — which is the only thing that reopens a subscription
     * this slice will do on its own.
     */
    @Test
    fun `a disconnection discards confirmation and keeps desire`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ack("r1", active = listOf(TETHER, USD)))
        advanceTimeBy(1)
        assertTrue(h.store.snapshot.stateFor(TETHER).confirmed)

        h.wire.drop()
        advanceTimeBy(1)
        assertEquals("끊긴 소켓의 구독이 확인된 채로 남았다", false, h.store.snapshot.stateFor(TETHER).confirmed)
        assertTrue("desired 까지 지웠다", h.store.snapshot.stateFor(TETHER).desired)

        advanceTimeBy(3_000)
        assertEquals(2, h.wires.size)
        h.cleanUp()
    }

    /** The ladder is D3's, and a reconnection spreads its subscribe. */
    @Test
    fun `the reconnection ladder grows and the resubscribe is spread`() = runTest {
        val h = Harness(this)
        h.jitterUnit = 0.5
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()

        // attempt 1: 2s, unjittered at a draw of 0.5.
        advanceTimeBy(1_999)
        assertEquals(1, h.wires.size)
        advanceTimeBy(2)
        assertEquals(2, h.wires.size)

        h.wire.open()
        advanceTimeBy(1)
        assertEquals("재구독이 즉시 나갔다 — 흩뜨려야 한다", 1, h.requests.size)
        advanceTimeBy(1_100)
        assertEquals(2, h.requests.size)
        h.cleanUp()
    }

    /**
     * Only thirty seconds of an open connection reopens the ladder.
     *
     * Not a frame, and specifically not the legacy `rates` the server sends everyone on connect —
     * the post-ACK close loop D3 exists for would otherwise reset the counter every lap.
     */
    @Test
    fun `the ladder resets after thirty stable seconds and not on a frame`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()
        advanceTimeBy(2_100)
        h.wire.open()
        advanceTimeBy(1)

        // A frame well inside the window changes nothing.
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(STABILITY_MS - 5_000)
        h.wire.drop()
        // Still on the ladder: attempt 2 is 4s, so nothing at 2s.
        advanceTimeBy(2_100)
        assertEquals("프레임이 사다리를 초기화했다", 2, h.wires.size)
        advanceTimeBy(2_000)
        assertEquals(3, h.wires.size)

        // …and thirty seconds of an open socket does reset it.
        h.wire.open()
        advanceTimeBy(STABILITY_MS + 1_000)
        h.wire.drop()
        advanceTimeBy(2_100)
        assertEquals(4, h.wires.size)
        h.cleanUp()
    }

    /** A factory that throws reports twice; the ladder must hear it once. */
    @Test
    fun `a refused connection spends one rung and not two`() = runTest {
        val h = Harness(this)
        h.failNextConnect = true
        h.goLive()
        advanceTimeBy(100)
        assertEquals(0, h.wires.size)

        // Attempt 1's delay is 2s. Two rungs would have made the next one 4s away.
        advanceTimeBy(2_100)
        assertEquals(1, h.wires.size)
        h.cleanUp()
    }

    /** Going offline is not a failure to retry around. */
    @Test
    fun `going offline closes the socket and schedules nothing`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        h.coordinator.setOnline(false)
        advanceTimeBy(10_000)
        assertEquals("오프라인인데 다시 연결했다", 1, h.wires.size)

        h.coordinator.setOnline(true)
        advanceTimeBy(100)
        assertEquals(2, h.wires.size)
        h.cleanUp()
    }

    /** Repeating a lifecycle state is not a new trigger, and does not reopen the budget. */
    @Test
    fun `a repeated lifecycle notification does not reopen the ladder`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()
        advanceTimeBy(2_100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()
        // The drop has to be *processed* before the notifications arrive. Posted in the same
        // instant they queue behind a connection that is still open, `reconsider` returns on
        // `connection != null`, and the test passes whether the guard exists or not — which is
        // how it passed against a build with the guard removed. Found by mutation.
        advanceTimeBy(1)

        // Attempt 2's wait is 4s. Repeating "online" during it must not make it 2s again.
        repeat(5) { h.coordinator.setOnline(true) }
        advanceTimeBy(1)
        assertEquals("같은 상태를 반복해서 곧바로 다시 연결했다", 2, h.wires.size)
        advanceTimeBy(2_100)
        assertEquals("같은 상태를 반복해서 예산을 되살렸다", 2, h.wires.size)
        advanceTimeBy(2_000)
        assertEquals(3, h.wires.size)
        h.cleanUp()
    }

    /** A grant change ends the session it was authenticated for and forgets its prices. */
    @Test
    fun `a different grant drops the socket and the prices`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        assertEquals(1, h.coordinator.rates.value.quotes.size)

        h.setAccess(true, fence(uid = "u2"))
        advanceTimeBy(1)
        assertTrue("앞 grant 의 소켓이 살아 있다", h.wires[0].cancelled)
        assertEquals("앞 grant 의 시세가 남았다", 0, h.coordinator.rates.value.quotes.size)
        assertEquals(0L, h.store.snapshot.stateFor(TETHER).receiveGeneration)

        advanceTimeBy(100)
        assertEquals(2, h.wires.size)
        h.cleanUp()
    }

    /** A refusal reaches the caller when it arrives, not when the deadline expires. */
    @Test
    fun `a refusal is surfaced on the acknowledgement`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(
            h.ack("r1", active = listOf(TETHER), rejections = mapOf(USD to "premium_required"))
        )
        advanceTimeBy(1)

        assertEquals(
            listOf(mapOf(USD to TopicRejectionReason.PREMIUM_REQUIRED)),
            h.rejected
        )
        h.cleanUp()
    }

    /**
     * The refusal goes back with the grant its connection was opened under, after that
     * connection has already ended itself.
     *
     * A `premium_required` refusal tears the connection down before anyone is told. Reading the
     * owner from the session at that point would name whatever is current, which is exactly the
     * thing a consumer has to be able to tell apart from the grant that was refused.
     */
    @Test
    fun `a refusal is handed back with its connection's grant after that connection has ended`() = runTest {
        val h = Harness(this)
        h.coordinator.start()
        h.setAccess(true, fence(grant = 7L))
        h.coordinator.setOnline(true)
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        h.wire.deliver(
            h.ack("r1", active = listOf(TETHER), rejections = mapOf(USD to "premium_required"))
        )
        advanceTimeBy(1)

        assertEquals("거부가 연결의 grant 가 아닌 것을 들고 왔다", listOf(fence(grant = 7L)), h.rejectedOwners)
        assertEquals("거부를 넘길 때 연결이 아직 살아 있었다", listOf(true), h.socketGoneAtRefusal)
        h.cleanUp()
    }

    /** The access side of these tests, in memory, issuing grants for the harness's own account `u1`/1. */
    private class Issuer(test: TestScope, h: Harness) {
        private var n = 0
        val ids = EpochIdGenerator { "issuer-${n++}" }
        var record = AccessEpochRecord()
        val store = object : AccessEpochStore {
            override suspend fun load() = record
            override suspend fun bindOwner(uid: String) = AccessEpochTransitions.bindOwner(record, uid, ids).also { record = it }
            override suspend fun signOut() = AccessEpochTransitions.signOut(record, ids).also { record = it }
            override suspend fun retireUnverifiedStart() =
                AccessEpochTransitions.retireUnverifiedStart(record, ids).also { record = it }
            override suspend fun beginSignOut(uid: String) = AccessEpochTransitions.beginSignOut(record, uid).also { record = it }
            override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
                AccessEpochTransitions.rotate(record, rotateUser, rotateKrx, ids).also { record = it }
            override suspend fun completePurges(completed: Collection<PendingPurge>) =
                AccessEpochTransitions.completePurges(record, completed).also { record = it }
            override suspend fun journalRetired(obligation: LossObligation) =
                AccessEpochTransitions.journalRetired(record, obligation).also { record = it }
            override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
                AccessEpochTransitions.markMayContainData(record, premium, krx).also { record = it }
        }
        private val purger = object : UserScopePurger, CapabilityScopePurger {
            override suspend fun purgeUserScope(namespace: PurgeNamespace) = PurgeResult.Completed
            override suspend fun purgeCapabilityScope(namespace: PurgeNamespace) = PurgeResult.Completed
        }
        val premium = PremiumAccessCoordinator(
            source = object : EntitlementsSource {
                override suspend fun fetch(freshPremium: Boolean): EntitlementsResult = EntitlementsResult.Answered(
                    EntitlementsIdentity("u1", 1L),
                    EntitlementsOutcome.StableActive(krxVisible = false)
                )
                override suspend fun currentIdentity() = EntitlementsIdentity("u1", 1L)
            },
            store = store,
            userPurger = purger,
            capabilityPurger = purger,
            scope = h.scope,
            clock = { test.testScheduler.currentTime },
            jitter = ProbeJitter.None,
            liveFence = { AuthIdentityFence("u1", 1L) }
        )

        init {
            // What runtime wiring would do with a refusal: hand the grant it answered to the issuer.
            h.refusalSink = { owner, reasons -> h.scope.launch { premium.onTopicRejected(owner.grant, reasons.values) } }
        }

        /** Binds `u1`/1, confirms premium, and returns the fence a session may run under. */
        suspend fun grant(): TopicSessionFence {
            premium.onIdentityChanged(AuthIdentityFence("u1", 1L))
            premium.refresh(RefreshIntent.FORCE_PREMIUM)
            return checkNotNull(premium.topicGrant()) { "no grant was issued" }
        }
    }

    private suspend fun TestScope.refusedUnder(h: Harness, issued: TopicSessionFence) {
        h.coordinator.start()
        h.setAccess(true, issued)
        h.coordinator.setOnline(true)
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
    }

    /** Joined end to end: a refusal on a connection opened under an issued grant is applied by the issuer. */
    @Test
    fun `a refusal for the grant a connection was opened under is applied by the issuer`() = runTest {
        val h = Harness(this)
        val issuer = Issuer(this, h)
        val issued = issuer.grant()
        assertEquals(PremiumAccessState.PremiumConfirmed, issuer.premium.state.value.state)
        refusedUnder(h, issued)

        h.wire.deliver(h.ack("r1", active = listOf(TETHER), rejections = mapOf(USD to "premium_required")))
        advanceTimeBy(1)

        assertEquals("발급한 grant 의 거부가 적용되지 않았다", PremiumAccessState.Rejected, issuer.premium.state.value.state)
        h.cleanUp()
    }

    /** The same refusal, after the grant's context moved without anyone asking for a new grant, is discarded. */
    @Test
    fun `a refusal arriving after its grant's context moved is discarded by the issuer`() = runTest {
        val h = Harness(this)
        val issuer = Issuer(this, h)
        val issued = issuer.grant()
        refusedUnder(h, issued)

        issuer.record = AccessEpochTransitions.rotate(issuer.record, rotateUser = true, rotateKrx = false, ids = issuer.ids)
        val moved = issuer.record
        h.wire.deliver(h.ack("r1", active = listOf(TETHER), rejections = mapOf(USD to "premium_required")))
        advanceTimeBy(1)

        assertEquals(1, h.rejectedOwners.size)
        assertEquals("문맥이 바뀐 뒤의 거부가 적용됐다", PremiumAccessState.PremiumConfirmed, issuer.premium.state.value.state)
        assertEquals(moved, issuer.record)
        h.cleanUp()
    }

    /** A fence that differs only in its grant is a different session, with everything that implies. */
    @Test
    fun `a grant that differs only in its token drops the socket and the prices`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        assertEquals(1, h.coordinator.rates.value.quotes.size)

        h.setAccess(true, fence(grant = 2L))
        advanceTimeBy(1)
        assertTrue("앞 grant 의 소켓이 살아 있다", h.wires[0].cancelled)
        assertEquals("앞 grant 의 시세가 남았다", 0, h.coordinator.rates.value.quotes.size)
        assertEquals(0L, h.store.snapshot.stateFor(TETHER).receiveGeneration)

        advanceTimeBy(100)
        assertEquals(2, h.wires.size)
        h.cleanUp()
    }

    /** The refusal latch is the refused grant's, so a new token for the same account asks again. */
    @Test
    fun `a refused session connects again only once its grant token changes`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(
            h.ack("r1", active = listOf(TETHER), rejections = mapOf(USD to "premium_required"))
        )
        advanceTimeBy(1)

        h.setAccess(true, fence())
        advanceTimeBy(10_000)
        assertEquals("거부된 같은 grant 로 다시 연결했다", 1, h.wires.size)

        h.setAccess(true, fence(grant = 2L))
        advanceTimeBy(100)
        assertEquals("새 grant 인데 연결하지 않았다", 2, h.wires.size)
        h.cleanUp()
    }

    /** The identity-loss latch is keyed the same way: the same fence stays retired, a new token does not. */
    @Test
    fun `a retired session connects again only once its grant token changes`() = runTest {
        val h = Harness(this)
        silenceReady(h)
        val first = h.wire

        h.liveFence = null
        first.deliver(h.tetherFrame(1400.0))
        advanceTimeBy(1)
        assertTrue("신원 상실로 은퇴하지 않았다", first.cancelled)

        // The identity is back, as the same session. Granting is done directly so it does not move it.
        h.liveFence = fence().identity
        h.coordinator.setAccess(true, fence())
        advanceTimeBy(10_000)
        assertEquals("은퇴한 같은 grant 로 다시 연결했다", 1, h.wires.size)

        h.coordinator.setAccess(true, fence(grant = 2L))
        advanceTimeBy(100)
        assertEquals("새 토큰인데 연결하지 않았다", 2, h.wires.size)
        h.cleanUp()
    }

    /** An answer decided under the previous token is neither applied nor handed over. */
    @Test
    fun `a bootstrap answer from before a token change is neither applied nor handed over`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapGate = CompletableDeferred()
        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        h.coordinator.requestBootstrap(USD)
        advanceTimeBy(1)
        assertEquals(issued + 2, h.bootstrapCalls.size)
        val handedBefore = h.undelivered.size

        h.setAccess(true, fence(grant = 2L))
        advanceTimeBy(100)
        assertEquals("새 토큰이 desired topic 을 다시 묻지 않았다", issued + 4, h.bootstrapCalls.size)

        // Only the two held issues answer; whatever the new grant asks for itself is not the subject.
        h.bootstrapOutcome = { issue, _ ->
            when (issue) {
                issued -> h.delivered(h.tetherFrame(1390.0, timestamp = "2026-08-31T10:25:00+09:00"))
                issued + 1 -> TopicSnapshotOutcome.Dormant
                else -> TopicSnapshotOutcome.Unreachable(java.io.IOException("이 시험의 대상이 아니다"))
            }
        }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertTrue(
            "앞 토큰의 답이 시세에 들어왔다",
            h.coordinator.rates.value.quotes.values.none { it.rate == 1390.0 }
        )
        assertTrue(
            "앞 토큰의 답을 밖으로 보고했다",
            h.undelivered.drop(handedBefore).none { it.third === TopicSnapshotOutcome.Dormant }
        )
        h.cleanUp()
    }

    /**
     * A finished command does not hand itself a new budget.
     *
     * Resending whatever is still unconfirmed on every completion would turn a terminal refusal —
     * or three spent attempts — into an endless ladder of its own, which is the thing those two
     * are the ceiling of.
     */
    @Test
    fun `a command that ends does not start another on the same connection`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        assertEquals(1, h.requests.size)

        // Nobody answers, so the command spends its three attempts and stops. A refusal would
        // end it too, but a `premium_required` one also latches the session shut — and a test
        // that used it would pass against a build that *did* resend, because the latch would
        // stop the resend rather than the contract. Found by mutation.
        advanceTimeBy(200_000)
        assertEquals("세 번을 넘겨 보냈다", 3, h.requests.size)
        assertEquals(1, h.wires.size)

        advanceTimeBy(200_000)
        assertEquals("예산이 끝난 command 가 스스로 새 예산을 열었다", 3, h.requests.size)
        h.cleanUp()
    }

    /** A keep-alive nobody answers ends the connection; one that is answered does not. */
    @Test
    fun `an unanswered ping ends the connection`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.autoPong = false
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire

        advanceTimeBy(PING_MS + 1)
        assertTrue("ping 을 보내지 않았다", first.sent.any { it == "ping" })

        advanceTimeBy(10_001)
        assertEquals("응답 없는 keep-alive 로 연결이 끊기지 않았다", true, first.cancelled)
        h.cleanUp()
    }

    /**
     * A keep-alive that will not even go out ends the connection at once.
     *
     * Waiting the pong deadline out would be waiting for an answer to a question never asked. The
     * branch had no test until a mutation removed it and nothing noticed. Found by mutation.
     */
    @Test
    fun `a ping that cannot be sent ends the connection immediately`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.autoPong = false
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire

        first.sendSucceeds = false
        advanceTimeBy(PING_MS + 2)
        assertEquals("보내지도 못한 ping 을 10초 기다렸다", true, first.cancelled)
        h.cleanUp()
    }

    /** Only a pong clears the deadline — a stream of prices does not prove the peer is answering. */
    @Test
    fun `a data frame does not answer a ping`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.autoPong = false
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire

        advanceTimeBy(PING_MS + 1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(10_001)
        assertEquals("시세 프레임이 pong 을 대신했다", true, first.cancelled)
        h.cleanUp()
    }

    /** …and a pong does. */
    @Test
    fun `a pong keeps the connection`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.autoPong = false
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire

        advanceTimeBy(PING_MS + 1)
        h.wire.deliver(PONG)
        advanceTimeBy(10_001)
        assertEquals(false, first.cancelled)
        assertEquals(1, h.wires.size)
        h.cleanUp()
    }

    /** A socket that never opens is not waited on for ever. */
    @Test
    fun `a connection that never opens is given up on`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        assertEquals(1, h.wires.size)

        advanceTimeBy(15_001)
        assertEquals(true, h.wires[0].cancelled)
        advanceTimeBy(2_100)
        assertEquals(2, h.wires.size)
        h.cleanUp()
    }

    /** A closed connection's answers belong to it, and not to the one that replaced it. */
    @Test
    fun `an answer from a closed generation is not applied to the live one`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire
        h.wire.closed()
        advanceTimeBy(2_100)
        h.wire.open()
        advanceTimeBy(1)
        assertEquals(2, h.wires.size)

        // The old socket's listener is still reachable; the coordinator must not care.
        first.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        assertEquals("죽은 세대의 프레임이 반영됐다", 0, h.coordinator.rates.value.quotes.size)
        assertEquals(0L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
        h.cleanUp()
    }

    /**
     * A frame from the connection a grant change replaced is not the new session's.
     *
     * Reachable because the transport's collector has not run yet when the change is posted: the
     * frame is queued behind it, connection two exists by the time it is read, and only the
     * generation on the input says it belongs to the one that is gone. The earlier test for this
     * was checking the *transport* — it delivers to a socket already cancelled, so nothing arrived
     * regardless. Found by review.
     */
    @Test
    fun `a frame queued before a grant change is not applied to the session after it`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire

        first.deliver(h.tetherFrame(1390.0))
        h.setAccess(true, fence(uid = "u2"))
        advanceTimeBy(1)

        assertEquals("앞 grant 로 온 프레임이 새 세션에 남았다", 0, h.coordinator.rates.value.quotes.size)
        assertEquals(0L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
        h.cleanUp()
    }

    /**
     * A reconnection that was already queued does not open a second socket.
     *
     * Cancelling the timer does not unpost what it has queued. Without a ticket on the
     * reservation, a foreground transition landing in the same instant opened one socket and the
     * stale event opened another — and the first was then held by nothing, so even `stop()` left
     * it running. Found by review.
     */
    @Test
    fun `a stale reconnection event does not open a second socket`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()

        // Sit exactly on the reconnection's due instant, then let a foreground return race it.
        advanceTimeBy(1_600)
        h.coordinator.setForeground(true)
        advanceTimeBy(1)
        assertEquals("소켓이 둘 열렸다", 2, h.wires.size)

        // The first socket reported its own failure, so there is nothing left to cancel on it —
        // `TopicTransport` had already let it go. The live one is the one `stop()` closes.
        h.coordinator.stop()
        advanceTimeBy(1)
        assertEquals(listOf(false, true), h.wires.map { it.cancelled })
        h.cleanUp()
    }

    /**
     * Nothing is sent under a grant this socket was not opened for.
     *
     * The provider answers with whoever is signed in now, which is not necessarily who the
     * connection is for — a subscribe went out carrying one account's token on another's session.
     * Found by review.
     */
    @Test
    fun `a credential for another identity is not sent`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.credential = AuthSnapshot("u2", 1L, "token-2")
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(10)

        assertEquals("다른 신원의 토큰으로 보냈다", 0, h.requests.size)
        h.cleanUp()
    }

    /** A refusal about the account outlives the socket it arrived on. */
    @Test
    fun `premium_required stops the session and not just the connection`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(
            h.ack("r1", active = emptyList(), rejections = mapOf(TETHER to "premium_required"))
        )
        advanceTimeBy(60_000)
        assertEquals(1, h.wires.size)

        // The refusal has to outlast a *fresh trigger*, which is the whole difference between
        // holding it against the grant and holding it against the socket. Ending the connection
        // deliberately already stops the automatic ladder, so a test that only waited passed
        // either way. Found by mutation.
        h.coordinator.setOnline(false)
        h.coordinator.setOnline(true)
        h.coordinator.setForeground(true)
        advanceTimeBy(60_000)
        assertEquals("거절받은 grant 로 다시 연결했다", 1, h.wires.size)
        assertEquals(1, h.requests.size)

        // …and a different grant is a different question.
        h.setAccess(true, fence(uid = "u2"))
        h.credential = AuthSnapshot("u2", 1L, "token-2")
        advanceTimeBy(100)
        assertEquals(2, h.wires.size)
        h.cleanUp()
    }

    /** The same grant, said again, is not a new trigger. */
    @Test
    fun `repeating the same access does not reopen the ladder`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()
        advanceTimeBy(1)

        repeat(3) { h.setAccess(true, fence()) }
        advanceTimeBy(1)
        assertEquals("같은 grant 반복이 곧바로 다시 연결했다", 1, h.wires.size)
        h.cleanUp()
    }

    /** Going to the background is not a way around the ladder. */
    @Test
    fun `entering the background does not skip the backoff`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.coordinator.setForeground(true)
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()
        advanceTimeBy(1)

        h.coordinator.setForeground(false)
        advanceTimeBy(1)
        assertEquals("background 진입이 backoff 를 건너뛰었다", 1, h.wires.size)
        advanceTimeBy(1_700)
        assertEquals(2, h.wires.size)
        h.cleanUp()
    }

    /** A stopped session does not come back through inputs that were already queued. */
    @Test
    fun `a stopped session ignores what was already queued`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        h.coordinator.stop()
        h.coordinator.setOnline(false)
        h.coordinator.setOnline(true)
        advanceTimeBy(60_000)

        assertEquals("멈춘 세션이 버퍼에 남은 입력으로 되살아났다", 1, h.wires.size)
        assertEquals(true, h.wires.single().cancelled)
        h.cleanUp()
    }

    /**
     * A pong that arrives after its deadline is a late answer, not an answer.
     *
     * The waits are relative and the clock counts deep sleep, so a wait can return long past the
     * instant it was meant to end. Recording the deadline is what makes the difference visible;
     * a boolean cleared by whatever turned up next did not. Found by review.
     */
    @Test
    fun `a pong that arrives after its deadline does not keep the connection`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.autoPong = false
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire

        advanceTimeBy(PING_MS + 1)
        h.clockSkewMillis = 11_000
        h.wire.deliver(PONG)
        advanceTimeBy(10_001)

        assertEquals("마감이 지난 pong 을 받아들였다", true, first.cancelled)
        h.cleanUp()
    }

    /** The acknowledgement reaches the caller whole, because the next slice renews against it. */
    @Test
    fun `the acknowledgement is handed over with its leases and its instant`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLease("r1", TETHER, seconds = 900))
        advanceTimeBy(1)

        val ack = h.acknowledgements.single()
        assertEquals(setOf(TETHER), ack.accepted)
        assertEquals(900L, ack.leases.single().durationSeconds)
        assertEquals(101L, ack.acknowledgedAtMillis)
        h.cleanUp()
    }

    /**
     * A socket that opens after its own deadline is late, not open.
     *
     * Accepting it disarmed the timeout as a side effect, because that one only fires while the
     * connection is still unopened. The deadline is absolute, so a clock that ran on while the
     * wait did not is exactly the case it is for. Found by review.
     */
    @Test
    fun `a connection that opens after its deadline is not accepted`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.clockSkewMillis = 16_000
        h.wire.open()
        advanceTimeBy(1)

        assertEquals("마감이 지난 뒤 열린 소켓을 받아들였다", true, h.wires[0].cancelled)
        assertEquals(0, h.requests.size)
        h.cleanUp()
    }

    /**
     * Coming back is when a connection that died quietly is noticed.
     *
     * The waits are relative and the clock is not, so a socket whose pong deadline passed during
     * a suspension is still held — nothing has fired to say otherwise. `reconsider` alone returns
     * on "there is a connection" and keeps it. Found by review.
     */
    @Test
    fun `returning to the foreground drops a connection whose deadline has passed`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.autoPong = false
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire

        advanceTimeBy(PING_MS + 1)
        h.clockSkewMillis = 11_000
        h.coordinator.setForeground(true)
        advanceTimeBy(1)

        assertEquals("마감이 지난 연결을 그대로 들고 돌아왔다", true, first.cancelled)
        h.cleanUp()
    }

    /**
     * A refresh that answers with somebody else is not "no usable credential".
     *
     * `takeIf` folded the two together and the command read the result as a spent auth replay.
     * They are different endings: one is the provider saying it cannot help, the other is a
     * credential for an account this connection was never opened for. Found by review.
     */
    @Test
    fun `a refresh for another identity ends the session rather than spending the replay`() =
        runTest {
            val h = Harness(this)
            h.goLive()
            h.refreshed = AuthSnapshot("u2", 1L, "token-2")
            advanceTimeBy(100)
            h.wire.open()
            advanceTimeBy(1)
            assertEquals(1, h.requests.size)

            h.wire.deliver(h.subscriptionError("r1", "invalid_token"))
            advanceTimeBy(1)

            assertEquals("다른 신원의 갱신 토큰으로 다시 보냈다", 1, h.requests.size)
            assertEquals(true, h.wires[0].cancelled)
            advanceTimeBy(60_000)
            assertEquals("사다리를 탔다 — 되돌아갈 grant 가 없다", 1, h.wires.size)
            h.cleanUp()
        }

    /**
     * A command that ends by identity change takes its connection with it.
     *
     * The command throws rather than returning, so the completion the session listens for never
     * arrived: the reference stayed set and the socket went on pinging for a grant that was gone.
     * Found by review.
     */
    @Test
    fun `a command stopped by an identity change closes its connection`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.credential = AuthSnapshot("u2", 1L, "token-2")
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(10)

        assertEquals(0, h.requests.size)
        assertEquals("신원이 바뀌었는데 소켓이 남았다", true, h.wires[0].cancelled)

        advanceTimeBy(60_000)
        assertEquals("ping 을 계속 보냈다", false, h.wires[0].sent.any { it == "ping" })
        assertEquals(1, h.wires.size)
        h.cleanUp()
    }

    /** A cancelled scope leaves the same state behind as an orderly stop. */
    @Test
    fun `cancelling the scope clears the connection state too`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ack("r1", active = listOf(TETHER, USD)))
        advanceTimeBy(1)
        assertTrue(h.store.snapshot.stateFor(TETHER).confirmed)

        h.scope.cancel()
        advanceTimeBy(1)
        assertEquals(true, h.wires[0].cancelled)
        assertEquals(
            "소켓만 닫고 확인 상태를 남겼다",
            false,
            h.store.snapshot.stateFor(TETHER).confirmed
        )
    }

    /**
     * A cancelled reservation stays cancelled even when there is no connection to hide behind.
     *
     * The `connection == null` check answers a different question, and here it is satisfied: the
     * foreground attempt failed outright, so nothing is open — and only the ticket says the
     * queued event belongs to a reservation that was already called off. Recipe from review.
     */
    @Test
    fun `a cancelled reservation does not reconnect even with no connection open`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()
        advanceTimeBy(1_600)

        h.failNextConnect = true
        h.coordinator.setForeground(true)
        advanceTimeBy(1)

        assertEquals("취소된 예약이 다시 연결했다", 1, h.wires.size)
        h.cleanUp()
    }

    /**
     * A grant proven not to be signed in is not tried again by a lifecycle transition.
     *
     * Closing the connection was not enough: the grant stayed in `wanted()`, so the next
     * foreground return opened another socket for it, and the network flip after that opened a
     * third. The credential check kept the wrong token off the wire each time — but the session
     * had already been told this grant is gone. Found by review.
     */
    @Test
    fun `a grant whose identity is gone is not reopened by a lifecycle change`() = runTest {
        val h = Harness(this)
        h.goLive()
        h.credential = AuthSnapshot("u2", 1L, "token-2")
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(10)
        assertEquals(1, h.wires.size)

        h.coordinator.setForeground(true)
        advanceTimeBy(10)
        h.coordinator.setOnline(false)
        h.coordinator.setOnline(true)
        advanceTimeBy(10_000)
        assertEquals("사라진 신원의 grant 로 다시 열었다", 1, h.wires.size)

        // A grant that is actually signed in is a different question.
        h.credential = AuthSnapshot("u3", 1L, "token-3")
        h.setAccess(true, fence(uid = "u3"))
        advanceTimeBy(100)
        assertEquals(2, h.wires.size)
        h.cleanUp()
    }

    /**
     * A reconnection spreads its subscribe even when its budget was reset.
     *
     * A network or foreground transition starts the ladder again from zero, and keying the spread
     * on the budget made those look like first connections — so every client that lost the same
     * server resubscribed at the same instant, which is what the spread is for. Found by review.
     */
    @Test
    fun `a lifecycle reconnection still spreads its resubscribe`() = runTest {
        val h = Harness(this)
        h.jitterUnit = 1.0
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        assertEquals("최초 연결은 즉시여야 한다", 1, h.requests.size)

        h.coordinator.setOnline(false)
        h.coordinator.setOnline(true)
        advanceTimeBy(10)
        h.wire.open()
        advanceTimeBy(1)
        assertEquals("예산이 새로 시작했다고 재구독을 즉시 보냈다", 1, h.requests.size)

        advanceTimeBy(2_100)
        assertEquals(2, h.requests.size)
        h.cleanUp()
    }

    /**
     * A first attempt that failed before opening still leaves a reconnection to spread.
     *
     * "Has a socket ever opened" is not the question — the retry after a handshake that never
     * completed is a reconnection like any other, and every client that lost the same server is
     * making it at the same moment. Found by review.
     */
    @Test
    fun `a retry after a first connection that never opened is spread`() = runTest {
        val h = Harness(this)
        h.jitterUnit = 1.0
        h.goLive()
        advanceTimeBy(100)
        h.wire.drop()
        // Attempt 1 at a draw of 1.0 is 2s × 1.2; the drop itself is processed a tick after it
        // is delivered, so the reconnection lands just past 2.4s.
        advanceTimeBy(2_500)
        assertEquals(2, h.wires.size)

        h.wire.open()
        advanceTimeBy(1)
        assertEquals("열린 적 없는 첫 시도 뒤의 재연결이 즉시 재구독했다", 0, h.requests.size)
        advanceTimeBy(2_100)
        assertEquals(1, h.requests.size)
        h.cleanUp()
    }

    /** What the transport was careful to keep about a bad frame reaches the caller. */
    @Test
    fun `an undecodable frame is reported with its length and failure`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver("{ not json")
        advanceTimeBy(1)

        val (length, failure) = h.undecodable.single()
        assertEquals(10, length)
        assertTrue("예외 종류가 비었다", failure.isNotEmpty())
        assertEquals(1, h.wires.size)
        h.cleanUp()
    }

    /** Stopping is deliberate: the socket goes and nothing takes its place. */
    @Test
    fun `stopping does not reconnect`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        h.coordinator.stop()
        advanceTimeBy(60_000)
        assertEquals(true, h.wires[0].cancelled)
        assertEquals(1, h.wires.size)
        h.cleanUp()
    }

    /** Each connection gets one subscribe, and each subscribe a new request id. */
    @Test
    fun `each connection subscribes once under its own request id`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.drop()
        advanceTimeBy(2_100)
        h.wire.open()
        advanceTimeBy(2_100)

        assertEquals(2, h.requests.size)
        assertNotEquals(h.requests[0].requestId, h.requests[1].requestId)
        h.cleanUp()
    }

    // ---- leases -------------------------------------------------------------------------------

    /**
     * The renewal goes out ahead of the lease, asking for what that lease covered.
     *
     * `900 − 180 − 0`, from the **shortest** lease and with the draw only subtracting. The
     * arithmetic is locked where it lives; what is checked here is that an acknowledgement's leases
     * became a timer on this connection at all.
     */
    @Test
    fun `a lease is renewed ahead of its expiry`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        // Only one of the two desired topics came back with a lease, which is what makes the
        // renewal's scope worth asserting: it asks for what it holds, not for everything wanted.
        h.sleeps.clear()
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)
        assertEquals(1, h.requests.size)
        // Read here, before the acknowledgement that answers the handover can replace it: every
        // acknowledgement re-schedules the renewal, so an assertion taken after the second one
        // would pass on a version that had dropped the first one's leases. Found by review.
        assertTrue(
            "최초 ACK 가 갱신 타이머를 예약하지 않았다",
            720.seconds in h.sleeps
        )

        // Nothing ever delivered, so the first-delivery watchdog hands its silence over at 45s.
        // Answered here so it does not spend its own attempts on top of what is being measured —
        // and answered with the **same** lease id, because an acknowledgement carrying no leases
        // is the server saying there are none, which would take the renewal's schedule with it.
        advanceTimeBy(45_200)
        assertEquals(2, h.requests.size)
        h.wire.deliver(h.ackWithLeases("r2", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)

        advanceTimeBy(719_000)
        assertEquals("갱신이 lead 보다 일찍 나갔다", 2, h.requests.size)

        advanceTimeBy(2_000)
        assertEquals("lead 가 지났는데 갱신하지 않았다", 3, h.requests.size)
        assertEquals("lease 없는 topic 까지 재인증했다", setOf(TETHER), h.requests[2].topics.toSet())
        h.cleanUp()
    }

    /**
     * A renewal does not queue behind the delivery it is running beside.
     *
     * A lease of exactly the lead time clamps the wait to zero, so the renewal is sent while the
     * first delivery still has forty-four seconds of its deadline left — which is the whole reason
     * a connection holds more than one command. The store showing an open request again is the
     * second half: that request belongs to the renewal, not to the command that was already
     * acknowledged.
     */
    @Test
    fun `a renewal runs beside a first delivery that is still waiting`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 180L), Triple(USD, "L2", 180L)))
        advanceTimeBy(1)

        assertEquals("즉시 갱신이 배달을 기다렸다", 2, h.requests.size)
        assertEquals(setOf(TETHER, USD), h.requests[1].topics.toSet())
        assertEquals(TopicControlState.PENDING, h.store.snapshot.controlState)
        assertTrue("갱신이 연결을 끊었다", !h.wires.single().cancelled)

        // The renewal's own answer, arriving while the first command still holds the connection.
        // Both are listening; only one of them owns this `request_id`, and the leases it carries
        // are what the next renewal is paced by — so a third request at the new lead proves the
        // frame reached the command that asked for it.
        h.sleeps.clear()
        h.wire.deliver(h.ackWithLeases("r2", Triple(TETHER, "L3", 900L), Triple(USD, "L4", 900L)))
        advanceTimeBy(1)
        // The same reading, and for the same reason: the third acknowledgement below re-schedules
        // everything, so the request at the lead time alone no longer proves this one landed.
        assertTrue(
            "갱신 ACK 가 다음 갱신 타이머를 예약하지 않았다",
            720.seconds in h.sleeps
        )
        // Still nothing delivered, so the first-delivery watchdog hands over at its own deadline.
        // Answered with the same lease ids, which restates them rather than taking them away.
        advanceTimeBy(45_100)
        assertEquals(3, h.requests.size)
        h.wire.deliver(h.ackWithLeases("r3", Triple(TETHER, "L3", 900L), Triple(USD, "L4", 900L)))
        advanceTimeBy(1)

        advanceTimeBy(719_000)
        assertEquals("두 번째 갱신이 일찍 나갔다", 3, h.requests.size)

        advanceTimeBy(2_000)
        assertEquals("갱신의 ack 이 다음 갱신을 잡지 못했다", 4, h.requests.size)
        h.cleanUp()
    }

    /**
     * A lease the server declares already over is an expiry, not a renewal.
     *
     * Both come due at the same instant, and either one ends the connection: the expiry directly,
     * and the renewal because its handler reads the deadline before starting anything — a lease
     * that expires at the acknowledgement is already past it. iOS says the same, its zero-lease
     * tests reaching the re-authentication only through an injected sleep that holds the expiry
     * back.
     */
    @Test
    fun `a zero lease is enforced as an expiry rather than a renewal`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 0L)))
        advanceTimeBy(1)

        assertTrue("이미 끝난 lease 로 계속 받았다", first.cancelled)
        assertEquals("만료된 lease 로 재인증을 보냈다", 1, h.requests.size)
        assertEquals(false, h.store.snapshot.stateFor(TETHER).confirmed)

        advanceTimeBy(2_100)
        assertEquals("만료 뒤 다시 연결하지 않았다", 2, h.wires.size)
        h.cleanUp()
    }

    /**
     * A lease nobody renewed ends the connection when its deadline arrives, and not before.
     *
     * The renewal at the lead time went out and was never answered — which is the case this exists
     * for. Afterwards the socket is gone, and an acknowledgement arriving on it confirms nothing.
     */
    @Test
    fun `a lease nobody renewed ends the connection at its deadline`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)

        advanceTimeBy(899_000)
        assertTrue("마감 전에 끊었다", !first.cancelled)

        advanceTimeBy(2_000)
        assertTrue("절대 마감이 지났는데 계속 받았다", first.cancelled)

        first.deliver(h.ackWithLeases("r9", Triple(TETHER, "L9", 900L)))
        advanceTimeBy(1)
        assertEquals(
            "끝난 연결의 늦은 ack 이 구독을 확정했다",
            false, h.store.snapshot.stateFor(TETHER).confirmed
        )
        h.cleanUp()
    }

    /** The soonest deadline governs, and the ones behind it do not each buy a reconnection. */
    @Test
    fun `the earliest lease decides the deadline and one reconnection follows`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L), Triple(USD, "L2", 901L)))
        advanceTimeBy(1)

        advanceTimeBy(900_100)
        assertTrue("가장 이른 마감이 아무것도 하지 않았다", h.wires.first().cancelled)

        advanceTimeBy(2_100)
        assertEquals(2, h.wires.size)

        advanceTimeBy(1_000)
        assertEquals("두 번째 lease 가 두 번째 재연결을 샀다", 2, h.wires.size)
        h.cleanUp()
    }

    /**
     * Coming back to the foreground is where a deadline the timers slept through is noticed.
     *
     * The waits are relative and the clock is not, so a device that suspended past the expiry has a
     * timer that has not fired. The reconnection this raises goes through the ladder: `reconsider`
     * is **not** called, or every client that slept through the same fifteen minutes would come
     * back at one instant.
     */
    @Test
    fun `coming back to the foreground enforces an expiry the timer slept through`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)

        h.clockSkewMillis = 900_000
        h.coordinator.setForeground(true)
        advanceTimeBy(1)

        assertTrue("전경 복귀가 만료를 집행하지 않았다", first.cancelled)
        assertEquals("만료를 집행하고 곧바로 다시 연결했다", 1, h.wires.size)

        advanceTimeBy(2_100)
        assertEquals("사다리가 다시 연결하지 않았다", 2, h.wires.size)
        h.cleanUp()
    }

    /**
     * A renewal that wakes past the deadline it was meant to beat is an expiry.
     *
     * The wait is relative and the clock is not, so a device that suspended returns with the timer
     * still owing a second and the deadline long gone. Sending the renewal then asks the server to
     * re-grant a permission that had already lapsed — and it would, which is the whole reason the
     * expiry is absolute. Found by review, reproduced with this fixture.
     */
    @Test
    fun `a renewal that wakes past its deadline is enforced as an expiry`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        // A second over the lead: the renewal is due in one second, the deadline in 181.
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 181L)))
        advanceTimeBy(1)
        val first = h.wire

        h.clockSkewMillis = 182_000
        advanceTimeBy(1_100)

        assertEquals("만료된 lease 로 재인증을 보냈다", 1, h.requests.size)
        assertTrue("깨어난 갱신이 마감을 지나쳤다", first.cancelled)
        h.cleanUp()
    }

    /** A new grant does not carry the subscription past the absolute deadline it replaces. */
    @Test
    fun `a grant arriving after the deadline does not extend it`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        // Under the lead, so the renewal goes at once and the deadline is fifteen seconds out.
        // Short on purpose: the renewal's own twenty-second acknowledgement deadline is read off
        // the same clock, so **one** jump big enough to pass a 181-second lease would make the
        // answer late instead — the command's own rule, and a different test's subject. A longer
        // lease can be arranged with two jumps, one before the send to move that deadline out and
        // one after; this is the same case reached in one step. Review's point.
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 15L)))
        advanceTimeBy(1)
        val first = h.wire
        assertEquals("lead 아래 lease 가 즉시 갱신하지 않았다", 2, h.requests.size)

        // The deadline passes while the renewal is in flight, and the answer brings a new id.
        h.clockSkewMillis = 16_000
        first.deliver(h.ackWithLeases("r2", Triple(TETHER, "L3", 900L)))
        advanceTimeBy(1)

        assertTrue("새 grant 가 지나간 절대 마감을 이어받았다", first.cancelled)
        h.cleanUp()
    }

    /**
     * A listener that throws does not wedge the session.
     *
     * `CancellationException` especially: it ends the command's coroutine without going through
     * any failure path, so what takes the command off its connection has to run either way. The
     * leases are settled before the listener is called, which is why the renewal still comes.
     */
    @Test
    fun `an acknowledgement listener that throws leaves the session working`() = runTest {
        val h = Harness(this)
        h.acknowledgementFailure = kotlinx.coroutines.CancellationException("listener")
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)

        assertEquals(1, h.acknowledgements.size)
        assertTrue("리스너 예외가 소켓을 끊었다", !h.wire.cancelled)

        advanceTimeBy(721_000)
        assertEquals("리스너 예외가 갱신 타이머를 가져갔다", 2, h.requests.size)
        h.cleanUp()
    }

    /**
     * A renewal does not open a delivery window, and the clock is where that shows.
     *
     * The session itself cannot tell the purposes apart — it does not read the outcome — so the
     * evidence is the wait the command asks for. The initial delivery's D14 sleep is recorded
     * before `h.sleeps.clear()`, and no new delivery re-arms it during the observation below.
     * The first delivery is answered by a frame here so that
     * the only delivery window on offer would be the renewal's own. Review's counter-example to
     * "there is no cheap observation point".
     */
    @Test
    fun `a renewal does not open a delivery window`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 15L)))
        advanceTimeBy(1)
        assertEquals("lead 아래 lease 가 즉시 갱신하지 않았다", 2, h.requests.size)

        h.sleeps.clear()
        h.wire.deliver(h.ackWithLeases("r2", Triple(TETHER, "L3", 900L)))
        advanceTimeBy(1)

        assertTrue(
            "갱신이 배달 마감을 기다렸다: ${h.sleeps}",
            h.sleeps.none { it >= 40.seconds && it <= 50.seconds }
        )
        h.cleanUp()
    }

    /**
     * A frame that arrives after the deadline is not delivery evidence, and not a price either.
     *
     * The window is the same one the renewal and the foreground return already answer for: the
     * timers are relative waits and the deadline is absolute, so a device that suspended past it
     * comes back with the expiry still unfired. What is different here is that a *frame* can be
     * what arrives first, and counting it would let a subscription the client has already declared
     * over satisfy the very watchdog that exists to notice it going quiet. Found by review.
     */
    @Test
    fun `a frame arriving after the deadline is not counted`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)
        val first = h.wire

        // Still inside the lease: the same frame, on the same socket, is delivery.
        h.clockSkewMillis = 899_000
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        assertEquals("만료 전 프레임을 버렸다", 1L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
        assertEquals(
            TopicDeliveryState.HEALTHY,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        assertTrue("만료 전인데 끊었다", !first.cancelled)

        // Past it, with the timer still owing its wait.
        h.clockSkewMillis = 901_000
        h.wire.deliver(h.tetherFrame(1391.0))
        advanceTimeBy(1)

        assertEquals(
            "만료된 구독의 프레임이 수신 증거가 됐다",
            1L, h.store.snapshot.stateFor(TETHER).receiveGeneration
        )
        assertEquals(
            "만료된 구독의 값이 저장됐다",
            1390.0, h.coordinator.rates.value.quotes.values.single().rate, 0.0
        )
        assertTrue("만료를 집행하지 않았다", first.cancelled)
        h.cleanUp()
    }

    /**
     * A topic the server refused does not get to deliver.
     *
     * iOS asks this before touching a frame's data (`canAcceptTopicFrame`) and this side did not:
     * a refused topic that kept arriving would satisfy its own delivery watchdog, and the refusal
     * in the store would sit beside a `HEALTHY` reading of the same topic. `topic_unavailable`
     * rather than `premium_required` because that one ends the connection, and what is under test
     * is a connection that carries on.
     */
    @Test
    fun `a refused topic's frame is not counted`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(
            h.ack("r1", active = listOf(USD), rejections = mapOf(TETHER to "topic_unavailable"))
        )
        advanceTimeBy(1)
        assertEquals(
            TopicRejectionReason.TOPIC_UNAVAILABLE,
            h.store.snapshot.stateFor(TETHER).rejection
        )

        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)

        assertEquals(
            "거절된 topic 의 프레임이 수신 증거가 됐다",
            0L, h.store.snapshot.stateFor(TETHER).receiveGeneration
        )
        assertTrue("거절된 topic 의 값이 저장됐다", h.coordinator.rates.value.quotes.isEmpty())
        assertTrue("거절 하나로 연결을 끊었다", !h.wire.cancelled)
        h.cleanUp()
    }

    /**
     * Nothing delivers on a credential the server has rejected.
     *
     * The other half of iOS's `canAcceptTopicFrame`. `invalid_token` with no replacement leaves the
     * store's authentication verdict failed while the socket is still open — and a frame arriving
     * then is data this session cannot claim it was entitled to.
     */
    @Test
    fun `a frame after a failed authentication is not counted`() = runTest {
        val h = Harness(this)
        h.refreshed = null
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.subscriptionError("r1", "invalid_token"))
        advanceTimeBy(1)
        assertEquals(TopicAuthResolution.FAILED, h.store.snapshot.authResolution)

        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)

        assertEquals(
            "인증이 깨진 뒤의 프레임이 수신 증거가 됐다",
            0L, h.store.snapshot.stateFor(TETHER).receiveGeneration
        )
        assertTrue(h.coordinator.rates.value.quotes.isEmpty())
        h.cleanUp()
    }

    // ---- silence (D14) -------------------------------------------------------------------------

    /** Delivers, is acknowledged, and ends its first-delivery command — the state D14 starts from. */
    private suspend fun TestScope.silenceReady(h: Harness) {
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        // The frame first, so the acknowledgement finds nothing silent and the first-delivery
        // command ends instead of holding its forty-five seconds.
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        h.wire.deliver(h.ack("r1", active = listOf(TETHER)))
        advanceTimeBy(1)
        assertEquals(1, h.requests.size)
        assertEquals(TopicDeliveryState.HEALTHY, h.store.snapshot.stateFor(TETHER).deliveryState)
    }

    /**
     * Forty-five seconds of tether silence is worth one quiet question, and only one.
     *
     * The window is armed by the delivery, so what is asserted is the instant it runs out: nothing
     * before it, one revalidation of the tether topic after it, and nothing more from the same
     * window afterwards.
     */
    @Test
    fun `tether silence asks once`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(44_000)
        assertEquals("45초 전에 물었다", 1, h.requests.size)

        advanceTimeBy(2_000)
        assertEquals("침묵이 지났는데 묻지 않았다", 2, h.requests.size)
        assertEquals(setOf(TETHER), h.requests[1].topics.toSet())
        assertEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )

        // Answered, so what follows cannot be that command retrying — it has nothing left to ask.
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(90_000)
        assertEquals("같은 창으로 두 번 물었다", 2, h.requests.size)
        assertEquals(1, h.store.snapshot.stateFor(TETHER).revalidationAttempt)
        h.cleanUp()
    }

    /** A delivery moves the deadline; the frames that are not deliveries do not. */
    @Test
    fun `only a tether delivery moves the window`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        // Forty seconds in. None of these is a tether delivery: the FX frame is a delivery for
        // another topic, the empty tether payload is the shape a KRX-only frame arrives as, and
        // the index is not in this session's desired set at all.
        advanceTimeBy(40_000)
        h.wire.deliver(h.fxFrame(1390.0))
        h.wire.deliver(h.emptyTetherFrame())
        h.wire.deliver(h.dxyFrame(99.5))
        advanceTimeBy(1)
        assertEquals("FX 배달이 기록되지 않았다", 1L, h.store.snapshot.stateFor(USD).receiveGeneration)

        advanceTimeBy(6_000)
        assertEquals("배달이 아닌 프레임이 창을 밀었다", 2, h.requests.size)
        h.cleanUp()
    }

    /** A real delivery does move it, which is what makes the previous test discriminating. */
    @Test
    fun `a tether delivery re-arms the window`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(40_000)
        h.wire.deliver(h.tetherFrame(1391.0))
        advanceTimeBy(1)

        advanceTimeBy(6_000)
        assertEquals("배달이 창을 다시 무장하지 않았다", 1, h.requests.size)

        advanceTimeBy(40_000)
        assertEquals("다시 무장한 창이 만료되지 않았다", 2, h.requests.size)
        h.cleanUp()
    }

    /**
     * While a first delivery is being worked on, the silence belongs to that owner.
     *
     * A reconnection is a fresh first-delivery effort — the ladder, the connect and the resubscribe
     * spread are part of it — so a window running out inside it must not produce a second
     * resubscribe. It is spent all the same, which the foreground return afterwards checks: an
     * unspent window would ask the moment the effort ended.
     */
    @Test
    fun `a window that runs out during a reconnection is held and spent`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(20_000)
        h.wire.drop()
        advanceTimeBy(2_100)
        h.wire.open()
        advanceTimeBy(2_100)
        assertEquals("재연결이 다시 구독하지 않았다", 2, h.requests.size)
        // Acknowledged but not delivered: the effort is still under way for the whole delivery
        // deadline, and the command has nothing left to retry with.
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(1)

        // The window from the first connection runs out while that effort is still running.
        advanceTimeBy(25_000)
        assertEquals("첫 배달 노력 중에 D14 가 끼어들었다", 2, h.requests.size)

        // That effort ends with its own delivery deadline and hands its silence over — one
        // question, from the watchdog that was waiting, not from the window that was spent.
        advanceTimeBy(20_000)
        assertEquals(3, h.requests.size)
        h.wire.deliver(h.ack("r3", active = listOf(TETHER)))
        advanceTimeBy(1)

        h.coordinator.setForeground(true)
        advanceTimeBy(1)
        assertEquals("소진된 창이 다시 물었다", 3, h.requests.size)
        h.cleanUp()
    }

    /**
     * A renewal holding the request channel makes the question wait, not disappear.
     *
     * The store keeps one open request, so two commands sending at once write over each other's
     * verdict. The question is asked when the channel is free — from the renewal's acknowledgement,
     * which is the only thing that wakes it.
     */
    @Test
    fun `a question deferred behind a renewal is asked when the channel frees`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        // 220 − 180 = 40 seconds of lead, so the renewal goes out before the silence runs out.
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 220L)))
        advanceTimeBy(1)
        assertEquals(1, h.requests.size)

        advanceTimeBy(40_100)
        assertEquals("갱신이 나가지 않았다", 2, h.requests.size)

        advanceTimeBy(5_000)
        assertEquals("갱신이 요청 채널을 쥔 동안 재검증이 나갔다", 2, h.requests.size)

        h.wire.deliver(h.ackWithLeases("r2", Triple(TETHER, "L2", 900L)))
        advanceTimeBy(1)
        assertEquals("채널이 비었는데 미뤄둔 질문이 사라졌다", 3, h.requests.size)
        assertEquals(setOf(TETHER), h.requests[2].topics.toSet())
        // The renewal's own outcome lands right after, and it says nothing about delivery: a
        // revalidation that has just begun must not be taken back by it.
        assertEquals(
            "갱신의 결과가 방금 시작한 재검증을 거뒀다",
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /** A grant change takes the window with it — the next grant starts with nothing delivered. */
    @Test
    fun `a grant change clears the window`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        h.credential = AuthSnapshot("u2", 2L, "token-2")
        h.setAccess(true, fence(uid = "u2", generation = 2L))
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        assertEquals("새 grant 가 구독하지 않았다", 2, h.requests.size)
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(1)

        // The new grant delivers nothing, so its own first-delivery watchdog asks once — and that
        // is the only question it is owed.
        advanceTimeBy(45_200)
        assertEquals(3, h.requests.size)
        h.wire.deliver(h.ack("r3", active = listOf(TETHER)))
        advanceTimeBy(1)

        // Asking again here would mean the old grant's window survived the purge — the foreground
        // return is what would read it.
        advanceTimeBy(46_000)
        h.coordinator.setForeground(true)
        advanceTimeBy(1)
        assertEquals("지워진 창이 물었다", 3, h.requests.size)
        h.cleanUp()
    }

    /** The same holds when only the grant token moved — the account and its credential stay put. */
    @Test
    fun `a token change clears the window`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        h.setAccess(true, fence(grant = 2L))
        advanceTimeBy(100)
        // A second socket, not the first one opened again: re-opening the old wire would also send a
        // subscription, and then nothing below would be about the new grant at all.
        assertEquals("토큰만 바뀐 grant 가 새 연결을 열지 않았다", 2, h.wires.size)
        assertTrue(h.wires[0].cancelled)
        h.wire.open()
        advanceTimeBy(1)
        assertEquals("새 grant 가 구독하지 않았다", 2, h.requests.size)
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(1)

        advanceTimeBy(45_200)
        assertEquals(3, h.requests.size)
        h.wire.deliver(h.ack("r3", active = listOf(TETHER)))
        advanceTimeBy(1)

        advanceTimeBy(46_000)
        h.coordinator.setForeground(true)
        advanceTimeBy(1)
        assertEquals("지워진 창이 물었다", 3, h.requests.size)
        h.cleanUp()
    }

    /** A lease that lapsed must stop D14 too — the rule is the connection's, not the renewal's. */
    @Test
    fun `silence does not ask on an expired lease`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)
        val first = h.wire
        assertEquals(1, h.requests.size)

        // The deadline passed while the timers slept; D14's window is the one that wakes first.
        h.clockSkewMillis = 901_000
        advanceTimeBy(45_100)

        assertEquals("만료된 lease 위에서 재검증을 보냈다", 1, h.requests.size)
        assertTrue("D14 가 만료를 집행하지 않았다", first.cancelled)
        assertNotEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /**
     * Nothing goes out under a lease that lapsed while the command was waiting for a token.
     *
     * The three starters ask before they start; this is the boundary they cannot cover, because the
     * deadline can pass after the decision and before the send. Held at the token so the gap is the
     * subject rather than an accident of timing.
     */
    @Test
    fun `a send is refused once the lease has lapsed`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)
        val subscribesBefore = h.wire.sent.count { it.startsWith("encoded-") }

        // The renewal comes due at 720s and is held inside its token wait.
        val gate = CompletableDeferred<Unit>()
        h.credentialGate = gate
        advanceTimeBy(720_100)
        assertEquals("토큰을 기다리는데 송신했다", subscribesBefore, h.wire.sent.count { it.startsWith("encoded-") })

        // The lease runs out while it waits, and only then is the token handed over.
        h.clockSkewMillis = 901_000
        gate.complete(Unit)
        advanceTimeBy(1)

        assertEquals(
            "만료된 lease 위로 송신했다",
            subscribesBefore, h.wire.sent.count { it.startsWith("encoded-") }
        )
        // The refusal is not the end of it: the loop is told, so the socket goes now rather than
        // when the relative timer happens to wake.
        assertTrue("송신 경계가 찾은 만료를 loop 에 알리지 않았다", h.wires.first().cancelled)
        h.cleanUp()
    }

    /**
     * A grant change takes the window with it, seen through a first-delivery effort that ends early.
     *
     * The effort normally runs the full delivery deadline, which is the same forty-five seconds as
     * the window and hides the question. A listener throwing ends the command as soon as the
     * acknowledgement is applied, so the leaked window — if it leaked — would be read by an owner
     * that has already finished. Review's counterexample.
     */
    @Test
    fun `a grant change clears the window, seen through an early-ending effort`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        h.credential = AuthSnapshot("u2", 2L, "token-2")
        h.setAccess(true, fence(uid = "u2", generation = 2L))
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        assertEquals(2, h.requests.size)

        h.acknowledgementFailure = kotlinx.coroutines.CancellationException("listener")
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(1)
        assertEquals(true, h.store.snapshot.stateFor(TETHER).confirmed)

        // Past the old grant's deadline, with nobody owning a first delivery any more.
        advanceTimeBy(60_000)
        assertEquals("지워진 창이 새 grant 에서 물었다", 2, h.requests.size)
        h.cleanUp()
    }

    /**
     * A question that waited for the request channel is dropped if the lease went while it waited.
     */
    @Test
    fun `a deferred question is dropped when the lease went while it waited`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        // Renewal at 40s, silence at 45s, the absolute deadline at 220s.
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 220L)))
        advanceTimeBy(1)
        val first = h.wire

        advanceTimeBy(40_100)
        assertEquals("갱신이 나가지 않았다", 2, h.requests.size)

        advanceTimeBy(5_000)
        assertEquals("갱신이 채널을 쥔 동안 재검증이 나갔다", 2, h.requests.size)
        assertEquals(TopicDeliveryState.SUSPECT, h.store.snapshot.stateFor(TETHER).deliveryState)

        // The deadline passes while the renewal is still spending its attempts.
        h.clockSkewMillis = 181_000
        advanceTimeBy(120_000)

        assertNotEquals(
            "만료된 연결 위에서 미뤄둔 질문이 되살아났다",
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        assertTrue("채널이 비었는데 만료를 집행하지 않았다", first.cancelled)
        h.cleanUp()
    }

    /**
     * A credential the server has refused is not retried by a timer.
     *
     * `authResolution` failing does not clear `confirmed` or the healthy reading, so every gate D14
     * passes on the way to a question is still open — and the store's own policy says a failed
     * credential is reopened by an authentication change or by the user, not by a clock
     * (`TopicSubscriptionState.kt:105-108`).
     */
    @Test
    fun `silence does not ask on a failed credential`() = runTest {
        val h = Harness(this)
        h.refreshed = null
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        // 220 − 180: the renewal goes out before the window runs out, and is refused.
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 220L)))
        advanceTimeBy(1)

        advanceTimeBy(40_100)
        assertEquals(2, h.requests.size)
        h.wire.deliver(h.subscriptionError("r2", "invalid_token"))
        advanceTimeBy(1)
        assertEquals(TopicAuthResolution.FAILED, h.store.snapshot.authResolution)

        advanceTimeBy(5_000)
        assertEquals("인증이 깨졌는데 타이머가 다시 물었다", 2, h.requests.size)
        assertNotEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /**
     * A renewal that stood aside is replaced by the schedule the next acknowledgement brings.
     *
     * Its answer granted a fresh lease, so the wait it computed is the current one; sending the
     * renewal that was waiting for the channel would ask again moments after being answered.
     */
    @Test
    fun `a new lease replaces a renewal that stood aside`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        // 240 − 180: the window runs out at 45s and the renewal comes due at 60s, so D14 has the
        // channel when the renewal arrives.
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 240L)))
        advanceTimeBy(1)

        advanceTimeBy(45_100)
        assertEquals("D14 가 묻지 않았다", 2, h.requests.size)

        advanceTimeBy(15_000)
        assertEquals("갱신이 채널을 기다리지 않았다", 2, h.requests.size)

        // The question is answered with a fresh lease, which is the schedule that now stands.
        h.wire.deliver(h.ackWithLeases("r2", Triple(TETHER, "L2", 900L)))
        advanceTimeBy(1)
        assertEquals("새 lease 를 받자마자 미뤄둔 갱신이 나갔다", 2, h.requests.size)
        h.cleanUp()
    }

    /** The credential can fail while a question waits for the channel; the resume must see that. */
    @Test
    fun `a deferred question is dropped when the credential failed while it waited`() = runTest {
        val h = Harness(this)
        h.refreshed = null
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 220L)))
        advanceTimeBy(1)

        advanceTimeBy(40_100)
        assertEquals("갱신이 나가지 않았다", 2, h.requests.size)

        advanceTimeBy(5_000)
        assertEquals("갱신이 채널을 쥔 동안 재검증이 나갔다", 2, h.requests.size)

        // The renewal is refused for its credential, which ends it and frees the channel.
        h.wire.deliver(h.subscriptionError("r2", "invalid_token"))
        advanceTimeBy(1)
        assertEquals(TopicAuthResolution.FAILED, h.store.snapshot.authResolution)

        assertEquals("인증이 깨졌는데 미뤄둔 질문이 나갔다", 2, h.requests.size)
        assertNotEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /**
     * The same, on the path where nothing was ever sent after the deadline.
     *
     * A command parked on its token and then cancelled reaches neither an acknowledgement nor the
     * send boundary, so neither of the other two ways of noticing an expiry runs — what stands
     * between that and an unwanted revalidation is the resume's own check. The command that would
     * follow is parked on a second token so that it cannot reach the send boundary either, which
     * is what keeps the two answers apart. Review's path.
     */
    @Test
    fun `a resume after a cancelled command does not revive the question on an expired lease`() =
        runTest {
            val h = Harness(this)
            h.goLive()
            advanceTimeBy(100)
            h.wire.open()
            advanceTimeBy(1)
            h.wire.deliver(h.tetherFrame(1390.0))
            advanceTimeBy(1)
            h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 220L)))
            advanceTimeBy(1)
            val first = h.wire

            // The renewal comes due at 40s and parks on its token, holding the request channel.
            val parked = CompletableDeferred<Unit>()
            h.credentialGate = parked
            advanceTimeBy(40_100)
            assertEquals("토큰을 기다리는데 요청을 등록했다", 1, h.requests.size)

            // The window runs out at 45s and stands aside for that channel.
            advanceTimeBy(5_000)
            assertEquals(TopicDeliveryState.SUSPECT, h.store.snapshot.stateFor(TETHER).deliveryState)

            // The lease deadline passes while it waits, and the wait ends by cancellation.
            h.clockSkewMillis = 181_000
            h.credentialGate = CompletableDeferred()
            parked.completeExceptionally(kotlinx.coroutines.CancellationException("token"))
            advanceTimeBy(1)

            assertTrue("채널이 비었는데 만료를 집행하지 않았다", first.cancelled)
            assertNotEquals(
                "만료된 연결 위에서 미뤄둔 질문이 되살아났다",
                TopicDeliveryState.REVALIDATING,
                h.store.snapshot.stateFor(TETHER).deliveryState
            )
            h.cleanUp()
        }

    // ---- outcomes and publication (D14 B) --------------------------------------------------------

    /** Silence answered by an acknowledgement and then more silence is what degrades a topic. */
    @Test
    fun `a revalidation that stays silent degrades its topic`() = runTest {
        val h = Harness(this)
        silenceReady(h)
        advanceTimeBy(45_100)
        assertEquals(2, h.requests.size)
        assertEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )

        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(1)
        assertEquals(
            "ACK 만으로 degraded 로 갔다",
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )

        advanceTimeBy(45_100)
        assertEquals(
            "재검증 뒤에도 조용한데 degraded 가 아니다",
            TopicDeliveryState.DEGRADED,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        assertEquals(TopicDeliveryState.DEGRADED, h.topicStates.last().stateFor(TETHER).deliveryState)
        h.cleanUp()
    }

    /** A revalidation that never got an answer is not the topic's failure. */
    @Test
    fun `a revalidation with no answer is taken back rather than degraded`() = runTest {
        val h = Harness(this)
        silenceReady(h)
        advanceTimeBy(45_100)
        assertEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )

        // No acknowledgement at all: three attempts, then the command stops.
        advanceTimeBy(120_000)
        assertNotEquals(
            "응답 없는 재검증을 topic 의 실패로 올렸다",
            TopicDeliveryState.DEGRADED,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        assertEquals(0, h.store.snapshot.stateFor(TETHER).revalidationAttempt)

        // Taken back leaves the topic askable again — and the window that already asked must not
        // ask a second time for the same silence. Nothing has delivered since, so it is the very
        // same window.
        val asked = h.requests.size
        h.coordinator.setForeground(true)
        advanceTimeBy(1)
        assertEquals("같은 창이 두 번째 질문을 냈다", asked, h.requests.size)
        h.cleanUp()
    }

    /** A topic that starts arriving again during its revalidation is healthy, not degraded. */
    @Test
    fun `a revalidation answered by a frame ends healthy`() = runTest {
        val h = Harness(this)
        silenceReady(h)
        advanceTimeBy(45_100)
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(1)

        // Five seconds in, so the window this delivery arms lands well after the revalidation's
        // own delivery deadline — the outcome is applied inside that gap and nothing else is.
        advanceTimeBy(5_000)
        h.wire.deliver(h.tetherFrame(1391.0))
        advanceTimeBy(1)
        assertEquals(TopicDeliveryState.HEALTHY, h.store.snapshot.stateFor(TETHER).deliveryState)

        advanceTimeBy(40_000)
        assertEquals(
            "회복한 topic 을 늦은 결과가 degraded 로 되돌렸다",
            TopicDeliveryState.HEALTHY,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /**
     * The degradation an expiry writes is published before the teardown erases it.
     *
     * `end` puts a still-wanted topic back to never-received in the same turn, so a publication
     * that only ran at the end of the turn would never carry the degraded reading — which is why
     * the write was left out until there was an observer for it.
     */
    @Test
    fun `an expiry publishes its degradation before the teardown`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)

        advanceTimeBy(900_200)

        val states = h.topicStates.map { it.stateFor(TETHER).deliveryState }
        val degradedAt = states.indexOf(TopicDeliveryState.DEGRADED)
        val clearedAt = states.indexOfLast { it == TopicDeliveryState.NEVER_RECEIVED }
        assertTrue("만료의 degraded 를 아무도 못 봤다: $states", degradedAt >= 0)
        assertTrue("정리가 degraded 보다 먼저 발행됐다: $states", clearedAt > degradedAt)
        h.cleanUp()
    }

    /**
     * The acknowledgement's own store writes are published when they happen.
     *
     * They happen inside the command, not on the session's queue, so a publication that only ran at
     * the end of each queued input would hold them until the next input arrives.
     */
    @Test
    fun `an acknowledgement is published without waiting for its command to end`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ack("r1", active = listOf(TETHER)))
        advanceTimeBy(1)

        assertEquals(
            "ACK 의 상태 변화가 명령이 끝날 때까지 발행되지 않았다",
            true, h.topicStates.last().stateFor(TETHER).confirmed
        )
        h.cleanUp()
    }

    /**
     * An earlier revalidation's result must not reap a later, unrelated one.
     *
     * The first question is answered by a frame, so its own result says nothing is silent — but by
     * the time that result is applied a *second* episode has begun, and "is a revalidation in
     * progress" cannot tell the two apart. Review's path.
     */
    @Test
    fun `a stale revalidation result does not reap the next one`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(45_100)
        assertEquals("첫 질문이 나가지 않았다", 2, h.requests.size)
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(1)

        // Answered by data: the first question's own result will carry no silence, and a new
        // window is armed by that same frame.
        h.wire.deliver(h.tetherFrame(1391.0))
        advanceTimeBy(1)
        assertEquals(TopicDeliveryState.HEALTHY, h.store.snapshot.stateFor(TETHER).deliveryState)

        // The device slept: the new window is already past when the app comes back, so a second
        // episode starts while the first command is still waiting out its delivery deadline.
        advanceTimeBy(4_800)
        h.clockSkewMillis = 41_000
        h.coordinator.setForeground(true)
        advanceTimeBy(1)
        assertEquals("두 번째 질문이 나가지 않았다", 3, h.requests.size)
        assertEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )

        // The first command's deadline now passes, and its result is about an episode that ended.
        advanceTimeBy(45_000)
        assertEquals(
            "지난 질문의 결과가 새 질문을 거뒀다",
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /** A revalidation cancelled before it could answer is taken back, not left standing. */
    @Test
    fun `a cancelled revalidation is taken back`() = runTest {
        val h = Harness(this)
        silenceReady(h)
        advanceTimeBy(45_100)
        assertEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )

        // The listener throws, so the command ends with no outcome at all.
        h.acknowledgementFailure = kotlinx.coroutines.CancellationException("listener")
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(1)

        assertNotEquals(
            "결과 없이 끝난 재검증이 topic 을 재검증 중으로 남겼다",
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        assertEquals(0, h.store.snapshot.stateFor(TETHER).revalidationAttempt)
        h.cleanUp()
    }

    /**
     * The teardown an expiry owes does not depend on the observer behaving.
     *
     * An acknowledgement is where a command's own coroutine discovers an expiry, and the
     * publication happens there too. A listener throwing takes that coroutine down with it, so the
     * teardown has to be owed regardless of what the observer does with the snapshot.
     *
     * The throw is aimed at the degraded snapshot, which is the one an expiry writes. Aimed at any
     * snapshot it lands on the pending request instead — inside the loop, before the command has
     * even applied the acknowledgement — and then the socket closes for a different reason
     * entirely. Found by review, which caught the earlier aim.
     */
    @Test
    fun `an expiry found while acknowledging tears down even when the observer throws`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)
        // A lease long enough that the question near its end is a revalidation, not a renewal.
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)
        val first = h.wire

        // Delivering every forty seconds keeps the window moving, so the only question comes near
        // the deadline: a renewal cannot be the one that finds this, because its own
        // acknowledgement deadline falls 160 seconds before the lease's.
        repeat(18) { round ->
            advanceTimeBy(40_000)
            h.wire.deliver(h.tetherFrame(1390.0 + round))
            advanceTimeBy(1)
        }
        // The renewal came due on the way. Answered with the **same** lease id, so it is a
        // restatement and the absolute deadline stays where it was.
        assertEquals(2, h.requests.size)
        h.wire.deliver(h.ackWithLeases("r2", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)
        repeat(3) { round ->
            advanceTimeBy(40_000)
            h.wire.deliver(h.tetherFrame(1500.0 + round))
            advanceTimeBy(1)
        }
        assertEquals("배달이 창을 밀지 못했다", 2, h.requests.size)

        advanceTimeBy(45_100)
        assertEquals("마감 직전의 질문이 나가지 않았다", 3, h.requests.size)

        // Past the lease, still inside that question's own twenty seconds, and with the relative
        // timer not yet woken — so the answer is where the expiry is found.
        advanceTimeBy(14_500)
        h.clockSkewMillis = 600
        h.topicStateFailure = kotlinx.coroutines.CancellationException("observer")
        h.wire.deliver(h.ack("r3", active = listOf(TETHER)))
        advanceTimeBy(1)

        // The intended point was reached: the degraded snapshot went out and took the throw.
        assertTrue(
            "만료의 degraded 스냅샷이 발행되지 않았다",
            h.topicStates.any { it.stateFor(TETHER).deliveryState == TopicDeliveryState.DEGRADED }
        )
        assertNull("관측자가 던지지 않았다", h.topicStateFailure)
        assertTrue("관측자가 던지자 소켓이 만료된 lease 위에 남았다", first.cancelled)
        h.cleanUp()
    }

    /**
     * A topic that never delivered is asked about once, by the watchdog that was waiting for it.
     *
     * The silence window is armed by a delivery, so a subscription that never produced one is held
     * at `NEVER_DELIVERED` for ever — the question has to come from the first-delivery watchdog
     * instead, and it is the same question, through the same door.
     */
    @Test
    fun `a first delivery that never arrives hands its silence to one revalidation`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ack("r1", active = listOf(TETHER, USD)))
        advanceTimeBy(1)
        assertEquals("ACK 만으로 다시 물었다", 1, h.requests.size)

        advanceTimeBy(45_100)
        assertEquals("최초 배달의 침묵이 아무것도 묻지 않았다", 2, h.requests.size)
        assertEquals("tether 만 물어야 한다", setOf(TETHER), h.requests[1].topics.toSet())
        assertEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )

        // And only once: the window was never armed, so nothing else is waiting to ask.
        h.wire.deliver(h.ack("r2", active = listOf(TETHER)))
        advanceTimeBy(90_000)
        assertEquals("최초 배달과 침묵 소유자가 각각 물었다", 2, h.requests.size)
        assertEquals(
            "재검증 뒤에도 조용한데 degraded 가 아니다",
            TopicDeliveryState.DEGRADED,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /** Another topic's silence is not tether's: only the tether question exists. */
    @Test
    fun `a first delivery silent only for another topic asks nothing`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.ack("r1", active = listOf(USD)))
        advanceTimeBy(1)

        advanceTimeBy(45_100)
        assertEquals("tether 가 조용하지 않은데 물었다", 1, h.requests.size)
        assertNotEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /**
     * Still not tether's, when tether was confirmed after the command that reports the silence.
     *
     * A command reports silence for **its own** accepted set, taken from the acknowledgement that
     * answered it (`TopicSubscribeCommand.kt:480-481`), while `confirmed` in the store is
     * overwritten by every later acknowledgement's whole-connection list
     * (`TopicSubscriptionState.kt:248`). So the two disagree here: the first request was answered
     * with USD alone, and a renewal's answer added tether afterwards. A handover widened to "any
     * topic was silent" asks about tether in this state; the narrow one does not. Found by review,
     * which refused the equivalence this was first recorded as.
     */
    @Test
    fun `a silence for another topic asks nothing about a tether confirmed later`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        // The first answer accepts USD only, and its lease renews at once — that is request two.
        h.wire.deliver(h.ackWithLeases("r1", Triple(USD, "U1", 180L)))
        advanceTimeBy(1)
        assertEquals(2, h.requests.size)

        // The renewal's own answer carries the whole connection, and tether is in this one.
        h.wire.deliver(
            h.ackWithLeases("r2", Triple(USD, "U2", 900L), Triple(TETHER, "T1", 900L))
        )
        advanceTimeBy(1)
        assertTrue(
            "후속 ACK 가 tether 를 confirmed 로 만들지 못했다",
            h.store.snapshot.stateFor(TETHER).confirmed
        )
        assertEquals(
            TopicDeliveryState.NEVER_RECEIVED,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )

        advanceTimeBy(45_100)
        assertEquals("USD 만 침묵인 최초 결과가 tether 를 재검증했다", 2, h.requests.size)
        assertNotEquals(
            TopicDeliveryState.REVALIDATING,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        h.cleanUp()
    }

    /**
     * A handover is a start, and a start does not happen on a permission that has lapsed.
     *
     * The watchdog's wait is relative and the lease's deadline is not, so a clock that moved
     * without the scheduler — a device that slept — is where the two part. The credential is held
     * from here on so the question cannot reach the send boundary and be refused *there* instead:
     * what is under test is the entry point. Found by review, which reproduced the start.
     */
    @Test
    fun `a first delivery hands nothing over on a lease that has lapsed`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val first = h.wire
        h.wire.deliver(h.ackWithLeases("r1", Triple(TETHER, "L1", 900L)))
        advanceTimeBy(1)
        assertEquals(1, h.requests.size)

        // Short of the watchdog's deadline, and then the clock alone moves past the lease.
        advanceTimeBy(44_000)
        h.credentialGate = CompletableDeferred()
        h.clockSkewMillis = 900_000
        advanceTimeBy(1_200)

        assertTrue("만료된 lease 위에 소켓이 남았다", first.cancelled)
        assertEquals(
            "만료된 lease 위에서 재검증을 시작했다",
            TopicDeliveryState.NEVER_RECEIVED,
            h.store.snapshot.stateFor(TETHER).deliveryState
        )
        assertEquals("만료된 lease 로 질문을 보냈다", 1, h.requests.size)
        h.cleanUp()
    }

    // ---- the REST bootstrap ---------------------------------------------------------------------

    /**
     * The request carries the grant that was current when it was **decided**.
     *
     * Attaching a grant to the answer is not the same as binding the request to it, and this is the
     * half that is easy to skip: the job runs later than the decision, so a service reading whoever
     * is signed in at its own start would send under a grant nobody chose.
     */
    @Test
    fun `a bootstrap is bound to the grant it was asked under`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)

        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)

        assertEquals(listOf(fence().identity to TETHER), h.bootstrapCalls.drop(issued))
        h.cleanUp()
    }

    /**
     * Two questions are asked before a request is spent, and both are asked on the loop.
     *
     * Being signed in is not enough — an offline session has nowhere to put the answer — and a
     * topic this build does not consume has no reader for it. The third call is the control: with
     * both conditions met the same ask does go out, so what the first two measure is the gate and
     * not a session that never asks at all.
     */
    @Test
    fun `a bootstrap is not spent on a session in no state to use it`() = runTest {
        val h = Harness(this)
        h.coordinator.start()
        h.setAccess(true, fence())
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)
        assertTrue("오프라인 세션이 요청을 썼다", h.bootstrapCalls.isEmpty())

        h.coordinator.setOnline(true)
        h.coordinator.requestBootstrap("krx:usd-krw-futures")
        advanceTimeBy(100)
        // 크기가 아니라 **이름**으로 잠근다. 자동 fan-out 이 붙으면 이 시점의 목록은 비어 있지
        // 않게 되지만, 그때도 확인해야 하는 것은 여전히 "KRX 를 묻지 않았다" 하나다.
        assertTrue(
            "이 빌드가 읽지 않는 topic 을 물었다",
            h.bootstrapCalls.none { it.second == "krx:usd-krw-futures" }
        )

        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        assertEquals(listOf(fence().identity to TETHER), h.bootstrapCalls.drop(issued))
        h.cleanUp()
    }

    /**
     * A fence that comes back is still a grant that ended.
     *
     * **This one is deliberately artificial.** A real sign-out and sign-in raises the auth
     * generation (`AuthSessionGenerationTracker.observe` bumps on a changed session marker, and
     * the app's sign-out calls `invalidate` first), so the fence either side would differ and the
     * transport would object on its own. What is staged here is the fence arriving *equal* anyway,
     * from whatever cause, and what it locks is that the counter — not fence equality — is what
     * decides. The withdrawal tests above are the same guard on a case that is not artificial.
     */
    @Test
    fun `an answer authorised before a sign-out is discarded`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapGate = CompletableDeferred()
        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        assertEquals(issued + 1, h.bootstrapCalls.size)

        h.setAccess(false, null)
        h.setAccess(true, fence())
        advanceTimeBy(100)

        // 보류된 그 발행 하나만 값을 낸다 — 세션이 스스로 무엇을 더 묻게 되더라도 이 단정이
        // 재는 것은 "보류됐던 답이 샜는가" 하나로 남는다. 그 값의 timestamp 는 **더 새것**이라,
        // merge 의 strictly-newer 규칙 때문에 새면 도착 순서와 무관하게 이겨서 반드시 보인다.
        h.bootstrapOutcome = { issue, _ ->
            if (issue == issued) {
                h.delivered(h.tetherFrame(1390.0, timestamp = "2026-08-31T10:25:00+09:00"))
            } else {
                TopicSnapshotOutcome.Unreachable(java.io.IOException("이 시험의 대상이 아니다"))
            }
        }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertTrue(
            "지난 grant 의 답이 새 세션에 적용됐다",
            h.coordinator.rates.value.quotes.values.none { it.rate == 1390.0 }
        )
        h.cleanUp()
    }

    /**
     * An answer authorised before access was withdrawn is not applied after it.
     *
     * The withdrawal here hands back the **same** fence, which is the shape a comparison of
     * fences cannot see. Whether the entitlements layer ever sends that shape is not settled —
     * it has no wiring to this coordinator yet — so what this locks is the contract rather than
     * a claim about it: an access withdrawal, however it arrives, ends this session's claim on
     * an answer authorised before it.
     */
    @Test
    fun `an answer authorised before access was withdrawn is discarded`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapGate = CompletableDeferred()
        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        assertEquals(issued + 1, h.bootstrapCalls.size)

        h.setAccess(false, fence())
        advanceTimeBy(100)

        // 보류된 그 발행 하나만 값을 낸다 — 세션이 스스로 무엇을 더 묻게 되더라도 이 단정이
        // 재는 것은 "보류됐던 답이 샜는가" 하나로 남는다. 그 값의 timestamp 는 **더 새것**이라,
        // merge 의 strictly-newer 규칙 때문에 새면 도착 순서와 무관하게 이겨서 반드시 보인다.
        h.bootstrapOutcome = { issue, _ ->
            if (issue == issued) {
                h.delivered(h.tetherFrame(1390.0, timestamp = "2026-08-31T10:25:00+09:00"))
            } else {
                TopicSnapshotOutcome.Unreachable(java.io.IOException("이 시험의 대상이 아니다"))
            }
        }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertTrue(
            "접근이 철회된 세션에 프리미엄 값이 들어왔다",
            h.coordinator.rates.value.quotes.values.none { it.rate == 1390.0 }
        )
        h.cleanUp()
    }

    /**
     * And a withdrawal that is granted again does not revive it.
     *
     * Both transitions keep the same fence, so nothing in this round trip is a grant change. An
     * answer decided before the withdrawal belongs to the authority that ended, not to the one
     * that was granted afterwards — which is what a bare "is access on now?" check would miss.
     */
    @Test
    fun `an answer does not survive a withdrawal that is granted again`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapGate = CompletableDeferred()
        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)

        h.setAccess(false, fence())
        h.setAccess(true, fence())
        advanceTimeBy(100)

        // 보류된 그 발행 하나만 값을 낸다 — 세션이 스스로 무엇을 더 묻게 되더라도 이 단정이
        // 재는 것은 "보류됐던 답이 샜는가" 하나로 남는다. 그 값의 timestamp 는 **더 새것**이라,
        // merge 의 strictly-newer 규칙 때문에 새면 도착 순서와 무관하게 이겨서 반드시 보인다.
        h.bootstrapOutcome = { issue, _ ->
            if (issue == issued) {
                h.delivered(h.tetherFrame(1390.0, timestamp = "2026-08-31T10:25:00+09:00"))
            } else {
                TopicSnapshotOutcome.Unreachable(java.io.IOException("이 시험의 대상이 아니다"))
            }
        }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertTrue(
            "철회를 왕복한 뒤 옛 답이 되살아났다",
            h.coordinator.rates.value.quotes.values.none { it.rate == 1390.0 }
        )
        h.cleanUp()
    }

    /**
     * What the session consumes is fixed at construction, whatever the caller does after.
     *
     * `val` on the parameter stops the field being re-assigned and says nothing about the set's
     * contents — a caller that hands in a `mutableSetOf` still holds the same object. Without the
     * copy the answer path could no longer trust the check the issue made, and the missing
     * re-check there would stop being sound. Found by review.
     */
    @Test
    fun `the desired set is fixed at construction`() = runTest {
        val caller = mutableSetOf(TETHER)
        val h = Harness(this, desired = caller)
        h.goLive()
        advanceTimeBy(100)

        val issued = h.bootstrapCalls.size
        caller.remove(TETHER)
        caller.add("krx:usd-krw-futures")
        h.coordinator.requestBootstrap(TETHER)
        h.coordinator.requestBootstrap("krx:usd-krw-futures")
        advanceTimeBy(100)

        assertEquals(
            "호출자가 바꾼 집합이 세션의 desired 를 바꿨다",
            listOf(fence().identity to TETHER),
            h.bootstrapCalls.drop(issued)
        )
        h.cleanUp()
    }

    /**
     * A grant the socket refused does not get its data back through the other door.
     *
     * `premium_required` is a fact about the account, and the session latches it: it stops
     * connecting and stops asking. Nothing about that is a transition the grant counter can see —
     * the fence is the same one and access has not been withdrawn yet — so the counter passes an
     * answer that raced the refusal. This is the check that does not.
     */
    @Test
    fun `an answer for a grant the socket refused is discarded`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.bootstrapGate = CompletableDeferred()
        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        assertEquals(issued + 1, h.bootstrapCalls.size)

        h.wire.deliver(h.ack("r1", active = emptyList(), rejections = mapOf(TETHER to "premium_required")))
        advanceTimeBy(1)

        h.bootstrapOutcome = { _, _ -> h.delivered(h.tetherFrame(1390.0)) }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertTrue(
            "거절된 grant 에 REST 로 값이 다시 들어왔다",
            h.coordinator.rates.value.quotes.isEmpty()
        )
        h.cleanUp()
    }

    /**
     * A grant whose credential turned out to be somebody else's does not get its data either.
     *
     * The socket's own command discovers this — the provider answers with an account the
     * connection was not opened for — and the session latches the grant as lost. Like the refusal
     * above, no fence reaches this session and no access is withdrawn, so the counter sees
     * nothing. The REST answer itself is unaffected: the fake here does not go through the bound
     * credentials, which is what leaves the coordinator's own check as the only thing that can
     * refuse it.
     */
    @Test
    fun `an answer for a grant whose credential moved is discarded`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapGate = CompletableDeferred()
        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        assertEquals(issued + 1, h.bootstrapCalls.size)

        h.credential = AuthSnapshot("u2", 1L, "token-2")
        h.wire.open()
        advanceTimeBy(10)

        h.bootstrapOutcome = { _, _ -> h.delivered(h.tetherFrame(1390.0)) }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertTrue(
            "신원이 사라진 grant 에 값이 들어왔다",
            h.coordinator.rates.value.quotes.isEmpty()
        )
        h.cleanUp()
    }

    /**
     * The same answer, with the session standing still, **is** applied.
     *
     * Without this the discard above would pass on a bootstrap that never delivers anything.
     */
    @Test
    fun `an answer from the grant still in force is applied`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapGate = CompletableDeferred()
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)

        h.bootstrapOutcome = { _, _ -> h.delivered(h.tetherFrame(1390.0)) }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertEquals(1390.0, h.coordinator.rates.value.quotes.values.single().rate, 0.0)
        h.cleanUp()
    }

    /**
     * A bootstrap delivers on a socket that never opened, and does not stand in for one that did.
     *
     * Both halves matter. The prices land with no frame anywhere in the session, which is the whole
     * reason this path exists — and the canonical delivery state does not move, because the counter
     * behind it is the socket's and the first-delivery watchdog reads it. A REST answer satisfying
     * that watchdog would let a session that has never received a frame look like one that has.
     */
    @Test
    fun `a bootstrap delivers without a socket and does not satisfy the watchdog`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        assertEquals("소켓이 열렸다 — 이 시험의 전제가 아니다", 1, h.wires.size)

        h.bootstrapOutcome = { _, _ -> h.delivered(h.tetherFrame(1390.0)) }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)

        assertEquals(1390.0, h.coordinator.rates.value.quotes.values.single().rate, 0.0)
        val tether = h.store.snapshot.stateFor(TETHER)
        assertEquals("REST 답이 소켓 수신으로 세어졌다", 0L, tether.receiveGeneration)
        assertEquals(
            "REST 답이 최초 배달 감시를 충족시켰다",
            TopicDeliveryState.NEVER_RECEIVED,
            tether.deliveryState
        )
        h.cleanUp()
    }

    /**
     * D14's window is armed by a REST delivery exactly as by a socket one.
     *
     * Measured against the deadline that was already running: forty seconds into the first window,
     * a bootstrap answers. The original deadline passes with no question, and the one the bootstrap
     * armed produces it forty seconds later — which is the difference between arming the window and
     * merely putting prices on screen.
     */
    @Test
    fun `a bootstrap arms the silence window`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(40_000)
        h.bootstrapOutcome = { _, _ ->
            h.delivered(h.tetherFrame(1391.0, timestamp = "2026-08-31T10:21:00+09:00"))
        }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        assertEquals(
            "새 값이 화면에 오르지 않았다",
            1391.0,
            h.coordinator.rates.value.quotes.values.single().rate,
            0.0
        )

        advanceTimeBy(6_000)
        assertEquals("REST 배달이 창을 다시 무장하지 않았다", 1, h.requests.size)

        advanceTimeBy(40_000)
        assertEquals("다시 무장한 창이 만료되지 않았다", 2, h.requests.size)
        assertEquals(setOf(TETHER), h.requests[1].topics.toSet())
        h.cleanUp()
    }

    /**
     * A payload with no usable price arms nothing — the KRX-only shape included.
     *
     * After D8 an empty tether snapshot and one carrying only `usd_krw_futures` are the same bytes
     * by the time they reach here, and D14 forbids the second satisfying tether delivery. The
     * original window runs out on time, which is what says the bootstrap did not touch it.
     */
    @Test
    fun `an empty bootstrap payload arms nothing`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(40_000)
        h.bootstrapOutcome = { _, _ -> h.delivered(h.emptyTetherFrame()) }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)

        advanceTimeBy(6_000)
        assertEquals("빈 payload 가 창을 밀었다", 2, h.requests.size)
        h.cleanUp()
    }

    /**
     * A valid answer that is older than what is held changes no price and is still a delivery.
     *
     * The two facts are separate and this is the case that separates them: the merge rule refuses
     * it, because equal or older never replaces, while the window counts it, because the server
     * answered and that is what silence is about.
     */
    @Test
    fun `a stale bootstrap payload overwrites nothing and still counts`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(40_000)
        h.bootstrapOutcome = { _, _ ->
            h.delivered(h.tetherFrame(1234.0, timestamp = "2026-08-31T10:19:00+09:00"))
        }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)

        assertEquals(
            "오래된 값이 화면의 가격을 덮었다",
            1390.0,
            h.coordinator.rates.value.quotes.values.single().rate,
            0.0
        )
        advanceTimeBy(6_000)
        assertEquals("오래된 배달이 배달로 세어지지 않았다", 1, h.requests.size)
        advanceTimeBy(40_000)
        assertEquals(2, h.requests.size)
        h.cleanUp()
    }

    /**
     * The grant is read where the ask is decided, not where the job happens to start.
     *
     * The two are different turns of the same loop: the request is queued behind whatever else is
     * waiting, and a job body that read the field for itself would send under whichever grant had
     * arrived by then. Here a different account signs in between the two, and what the request
     * carries is still the one that asked.
     */
    @Test
    fun `a bootstrap does not follow the session to another grant`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)

        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        h.setAccess(true, fence(uid = "u2", generation = 2L, epoch = "epoch-2"))
        advanceTimeBy(100)

        // 이 시험이 낸 **첫** 발행만이 대상이다. 두 번째 grant 가 스스로 무엇을 묻든 그것은
        // 다른 사실이고, 여기서 물은 것은 "이 요청이 새 grant 를 따라갔는가" 하나다.
        assertEquals(fence().identity to TETHER, h.bootstrapCalls.drop(issued).first())
        h.cleanUp()
    }

    /**
     * A bootstrap for another topic delivers its own prices and arms nothing.
     *
     * D14's window is tether's alone — FX has no time-based expiry at all — so an FX answer must
     * not move a deadline it has no bearing on. The prices are the control: the answer was applied,
     * and what it did not do is the subject.
     */
    @Test
    fun `an fx bootstrap does not arm the tether window`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(40_000)
        h.bootstrapOutcome = { _, _ -> h.delivered(h.fxFrame(1390.0)) }
        h.coordinator.requestBootstrap(USD)
        advanceTimeBy(1)
        assertEquals("FX bootstrap 이 가격을 넣지 않았다", 2, h.coordinator.rates.value.quotes.size)

        advanceTimeBy(6_000)
        assertEquals("FX bootstrap 이 tether 창을 밀었다", 2, h.requests.size)
        h.cleanUp()
    }

    /** A stopped session lets go of the request it was still paying for. */
    @Test
    fun `stopping lets go of a bootstrap still out`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapGate = CompletableDeferred()
        val issued = h.bootstrapCalls.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        assertEquals(issued + 1, h.bootstrapCalls.size)

        h.coordinator.stop()
        advanceTimeBy(1)
        val finishedBeforeRelease = h.bootstrapFinished.toList()
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertEquals(
            "멈춘 세션이 요청을 끝까지 붙들었다",
            finishedBeforeRelease,
            h.bootstrapFinished.toList()
        )
        h.cleanUp()
    }

    /**
     * An identity change during a bootstrap is not an answer, and does not take the session with it.
     *
     * The transport raises it when the account it captured is no longer signed in. Nothing about
     * the topic has been learned, so nothing is recorded — and the next ask still works, which is
     * what says the failure stayed inside its own job.
     */
    @Test
    fun `an identity change during a bootstrap leaves the session working`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)

        h.bootstrapOutcome = { _, _ -> throw AuthIdentityChangedException() }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)
        assertTrue("계정이 바뀐 요청이 값을 남겼다", h.coordinator.rates.value.quotes.isEmpty())

        h.bootstrapOutcome = { _, _ -> h.delivered(h.tetherFrame(1390.0)) }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)
        assertEquals(1390.0, h.coordinator.rates.value.quotes.values.single().rate, 0.0)
        h.cleanUp()
    }

    /**
     * Everything that is not a delivery leaves the session exactly as it was.
     *
     * A dormant endpoint is the sharpest of them: it is answered before authentication, so it says
     * nothing about this user or this topic — and it is a 404, which a path reading statuses rather
     * than bodies could read as an absence worth recording. Nothing is recorded, and the window
     * that was already running keeps its own deadline.
     */
    @Test
    fun `a refusal is not a delivery`() = runTest {
        val h = Harness(this)
        silenceReady(h)

        advanceTimeBy(40_000)
        h.bootstrapOutcome = { _, _ -> TopicSnapshotOutcome.Dormant }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)

        assertEquals(
            "거절이 가격을 바꿨다",
            1390.0,
            h.coordinator.rates.value.quotes.values.single().rate,
            0.0
        )
        advanceTimeBy(6_000)
        assertEquals("거절이 창을 밀었다", 2, h.requests.size)
        h.cleanUp()
    }

    // ---- the hand-off (L-3b) --------------------------------------------------------------------

    /**
     * An answer that is not a snapshot leaves the session with its evidence intact.
     *
     * The session has no seat for any of these — a dormant endpoint is answered before
     * authentication, a hidden topic cannot be un-desired here, a refusal's premium meaning
     * belongs to the layer that owns entitlements. What it can do is not swallow them. The
     * outcome is compared by **identity**, so a path that rebuilt or re-cased it fails.
     */
    @Test
    fun `an answer that is not a snapshot is handed over with its evidence`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val before = h.undelivered.size

        h.bootstrapOutcome = { _, _ -> TopicSnapshotOutcome.Dormant }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)

        val handed = h.undelivered.drop(before)
        assertEquals("비배달이 인계되지 않았다", 1, handed.size)
        assertEquals(fence(), handed.single().first)
        assertEquals(TETHER, handed.single().second)
        assertSame(TopicSnapshotOutcome.Dormant, handed.single().third)
        assertTrue("거절이 가격이 됐다", h.coordinator.rates.value.quotes.isEmpty())
        h.cleanUp()
    }

    /**
     * The retry floor the server actually sent survives the crossing.
     *
     * This route answers 503 two ways and only one of them says when to come back. Dropping the
     * header here would leave the layer that owns retries guessing, and guessing is how a
     * five-second floor gets applied to a failover measured in minutes.
     */
    @Test
    fun `a refusal keeps the retry floor the server sent`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val before = h.undelivered.size

        val refused = refusal(503, """{"detail":"Subscription status pending. Retry later."}""", "5")
        h.bootstrapOutcome = { _, _ -> refused }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)

        val handed = h.undelivered.drop(before).single().third
        assertTrue(handed is TopicSnapshotOutcome.Refused)
        assertEquals(
            "서버가 준 재시도 하한을 건너오며 잃었다",
            "5",
            (handed as TopicSnapshotOutcome.Refused).failure.retryAfter
        )
        assertEquals(503, handed.failure.statusCode)
        h.cleanUp()
    }

    /**
     * A grant that ended takes its answers with it — the hand-off included.
     *
     * The seam sits **after** the grant counter, deliberately. In front of it the session would
     * be reporting answers that are no longer its own, which is the thing the counter exists to
     * refuse; a listener acting on one would act for an account that is gone.
     */
    @Test
    fun `an answer for a grant that has ended is not handed over`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val issued = h.bootstrapCalls.size
        h.bootstrapGate = CompletableDeferred()
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        val before = h.undelivered.size

        h.setAccess(false, null)
        h.setAccess(true, fence())
        advanceTimeBy(100)

        h.bootstrapOutcome = { issue, _ ->
            if (issue == issued) {
                TopicSnapshotOutcome.Dormant
            } else {
                TopicSnapshotOutcome.Unreachable(java.io.IOException("이 시험의 대상이 아니다"))
            }
        }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertTrue(
            "끝난 grant 의 답을 밖으로 보고했다",
            h.undelivered.drop(before).none { it.third === TopicSnapshotOutcome.Dormant }
        )
        h.cleanUp()
    }

    /**
     * And so does a grant the socket refused, which no counter can see.
     *
     * `premium_required` latches this session shut without changing the fence or withdrawing
     * access. The seam sits after that check too — one guard short and the refusal would be
     * reported as though the grant were still live.
     */
    @Test
    fun `an answer for a grant the socket refused is not handed over`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        h.bootstrapGate = CompletableDeferred()
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        val before = h.undelivered.size

        h.wire.deliver(
            h.ack("r1", active = emptyList(), rejections = mapOf(TETHER to "premium_required"))
        )
        advanceTimeBy(1)

        h.bootstrapOutcome = { _, _ -> TopicSnapshotOutcome.Dormant }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertEquals("거절된 grant 의 답을 밖으로 보고했다", before, h.undelivered.size)
        h.cleanUp()
    }

    /** The other latch the counter cannot see gets the same treatment. */
    @Test
    fun `an answer for a grant whose credential moved is not handed over`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapGate = CompletableDeferred()
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        val before = h.undelivered.size

        h.credential = AuthSnapshot("u2", 1L, "token-2")
        h.wire.open()
        advanceTimeBy(10)

        h.bootstrapOutcome = { _, _ -> TopicSnapshotOutcome.Dormant }
        h.bootstrapGate!!.complete(Unit)
        advanceTimeBy(100)

        assertEquals("신원이 사라진 grant 의 답을 밖으로 보고했다", before, h.undelivered.size)
        h.cleanUp()
    }

    /** A delivery is data, not a report — the control that makes the rest discriminating. */
    @Test
    fun `a delivery is not handed over`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val before = h.undelivered.size

        h.bootstrapOutcome = { _, _ -> h.delivered(h.tetherFrame(1390.0)) }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)

        assertEquals("배달을 비배달로 보고했다", before, h.undelivered.size)
        assertEquals(1390.0, h.coordinator.rates.value.quotes.values.single().rate, 0.0)
        h.cleanUp()
    }

    /**
     * An identity change is not an answer, and is not reported as one.
     *
     * The transport raises it when the account the request was authorised for is no longer
     * signed in. The session hears that from `Access`; inventing an outcome here would put a
     * verdict the server never gave in front of whoever is listening. Nothing is staged as a
     * grant change in this test on purpose — the counter must not be what refuses it.
     */
    @Test
    fun `an identity change during a bootstrap hands nothing over`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val before = h.undelivered.size

        h.bootstrapOutcome = { _, _ -> throw AuthIdentityChangedException() }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)

        assertEquals("계정 변경을 답으로 지어내 보고했다", before, h.undelivered.size)
        assertTrue(h.coordinator.rates.value.quotes.isEmpty())
        h.cleanUp()
    }

    /**
     * The grant named in the hand-off is **this session's**, not a constant.
     *
     * A consumer that defers work has to be able to say which account the answer was about, so a
     * seam that always named the same grant would be worse than none. This session is deliberately
     * not the default one the other tests use — and the topic is deliberately not the one every
     * other test here asks for, because a seam that always named `usdt:krw` would pass those.
     */
    @Test
    fun `the grant and topic handed over are this session's own`() = runTest {
        val h = Harness(this)
        val mine = fence(uid = "u9", generation = 4L, epoch = "epoch-9")
        h.coordinator.start()
        h.setAccess(true, mine)
        h.coordinator.setOnline(true)
        advanceTimeBy(100)
        val before = h.undelivered.size

        h.bootstrapOutcome = { _, _ -> TopicSnapshotOutcome.Dormant }
        h.coordinator.requestBootstrap(USD)
        advanceTimeBy(100)

        val handed = h.undelivered.drop(before).single()
        assertEquals(mine, handed.first)
        assertNotEquals(fence(), handed.first)
        assertEquals(USD, handed.second)
        h.cleanUp()
    }

    // ---- the trigger (L-3c) ---------------------------------------------------------------------

    /**
     * A grant that becomes usable asks for everything this session consumes, once.
     *
     * The set is compared with the one this test constructed rather than a literal count, so a
     * session that later consumes more topics does not turn this into a number to update.
     */
    @Test
    fun `a grant that becomes usable asks for every desired topic once`() = runTest {
        val consumed = TopicCatalogue.DESIRED
        val h = Harness(this, desired = consumed)
        h.goLive()
        advanceTimeBy(100)

        assertEquals("이 세션이 읽는 topic 전부를 묻지 않았다", consumed, h.bootstrapCalls.map { it.second }.toSet())
        assertEquals("한 grant 에 두 번 물었다", consumed.size, h.bootstrapCalls.size)
        assertEquals(setOf(fence().identity), h.bootstrapCalls.map { it.first }.toSet())
        h.cleanUp()
    }

    /**
     * A grant that arrives before the network is asked for when the network arrives.
     *
     * This is why the gate comes **before** the latch. Access lands first here — which is the
     * ordinary order, since an account is known before connectivity is — and at that moment there
     * is nothing to ask on. A latch spent at that point would mean this grant is never asked for.
     */
    @Test
    fun `a grant that arrives before the network asks when the network arrives`() = runTest {
        val h = Harness(this)
        h.coordinator.start()
        h.setAccess(true, fence())
        advanceTimeBy(100)
        assertTrue("네트워크가 없는데 요청을 썼다", h.bootstrapCalls.isEmpty())

        h.coordinator.setOnline(true)
        advanceTimeBy(100)

        assertEquals(setOf(TETHER, USD), h.bootstrapCalls.map { it.second }.toSet())
        h.cleanUp()
    }

    /**
     * And a grant that arrives on a live network is asked for at once.
     *
     * The mirror of the test above, and the reason there are two call sites: neither branch can
     * make the other's case. `goLive()` is deliberately not used — it sets access before
     * connectivity, which is the *other* order.
     */
    @Test
    fun `a grant that arrives on a live network asks at once`() = runTest {
        val h = Harness(this)
        h.coordinator.start()
        h.coordinator.setOnline(true)
        advanceTimeBy(100)
        assertTrue("권한이 없는데 요청을 썼다", h.bootstrapCalls.isEmpty())

        h.setAccess(true, fence())
        advanceTimeBy(100)

        assertEquals(setOf(TETHER, USD), h.bootstrapCalls.map { it.second }.toSet())
        h.cleanUp()
    }

    /** A network that flaps under one grant is still one grant, and is asked for once. */
    @Test
    fun `a network that flaps under one grant does not ask again`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val asked = h.bootstrapCalls.size

        h.coordinator.setOnline(false)
        advanceTimeBy(100)
        h.coordinator.setOnline(true)
        advanceTimeBy(100)

        assertEquals("같은 grant 를 네트워크가 돌아올 때마다 다시 물었다", asked, h.bootstrapCalls.size)
        h.cleanUp()
    }

    /**
     * A withdrawal that is granted again **is** a new grant to ask for.
     *
     * The fence is identical on both sides of this, so a latch keyed on the fence would decide
     * the session has already asked. It is keyed on the counter, which the withdrawal raised.
     */
    @Test
    fun `a withdrawal that is granted again asks once more`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val asked = h.bootstrapCalls.size

        h.setAccess(false, fence())
        advanceTimeBy(100)
        h.setAccess(true, fence())
        advanceTimeBy(100)

        assertEquals(
            "철회 뒤 다시 받은 권한을 새 grant 로 보지 않았다",
            asked * 2,
            h.bootstrapCalls.size
        )
        h.cleanUp()
    }

    /**
     * The latch belongs to the automatic fan-out, not to the public entry point.
     *
     * `requestBootstrap` hands the decision to its caller — the manual retry the FX cutover owes,
     * and S6's capability flip. A latch reaching into it would swallow both silently.
     */
    @Test
    fun `a manual request is not swallowed by the latch`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val asked = h.bootstrapCalls.size

        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)

        assertEquals(asked + 1, h.bootstrapCalls.size)
        assertEquals(fence().identity to TETHER, h.bootstrapCalls.last())
        h.cleanUp()
    }

    /**
     * A reconnection is not a new grant.
     *
     * The ladder reopens sockets without issuing a new grant. Resetting or bypassing the grant
     * latch during reconnection would issue extra bootstraps. This test observes bootstrap
     * issues across a real reconnection.
     */
    @Test
    fun `a reconnection under one grant does not ask again`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val asked = h.bootstrapCalls.size

        h.wire.drop()
        advanceTimeBy(2_100)
        h.wire.open()
        advanceTimeBy(2_100)
        assertEquals("재연결이 일어나지 않아 이 시험이 아무것도 재지 않는다", 2, h.wires.size)

        assertEquals("재연결마다 다시 물었다", asked, h.bootstrapCalls.size)
        h.cleanUp()
    }

    // ---- the apply-time live identity boundary (L-4a) -------------------------------------------

    /**
     * An answer that arrives after the account moved is not this session's to apply.
     *
     * The three filters above it read state the loop was *told* about — the grant counter and the
     * two latches. None has heard of a move that has not been dequeued, and some moves never
     * arrive as an `Access` at all: `AuthUidStream` carries a bare uid, while `authGeneration`
     * also advances on the explicit invalidation the real sign-in path runs before Firebase does
     * anything.
     */
    @Test
    fun `a bootstrap answer is refused when the account moved while it was out`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapOutcome = { _, _ -> h.delivered(h.tetherFrame(1390.0)) }
        val gate = CompletableDeferred<Unit>()
        h.bootstrapGate = gate
        // A delta, not a count: this session's own fan-out already finished two on the way here.
        val finished = h.bootstrapFinished.size
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        assertEquals("답이 붙들리지 않아 이 시험이 창을 재지 못한다", finished, h.bootstrapFinished.size)

        // No `Access` — this is precisely the move the loop is never told about.
        h.liveFence = AuthIdentityFence("u2", 1L)
        gate.complete(Unit)
        advanceTimeBy(100)

        assertTrue("떠난 계정의 답이 적용됐다", h.coordinator.rates.value.quotes.isEmpty())
        h.cleanUp()
    }

    /**
     * A frame in the same position retires the socket rather than being dropped.
     *
     * Dropping frame after frame on a connection that stays up starves the delivery evidence
     * `receive` records, and D14's silence window would then file an account moving as the topic
     * going quiet. Retiring is the same sequence a command's own identity report runs.
     */
    @Test
    fun `identity loss retires an armed session until a new grant arrives`() = runTest {
        val h = Harness(this)
        silenceReady(h)
        val first = h.wire
        val sockets = h.wires.size
        val requests = h.requests.size
        val rates = h.coordinator.rates.value
        val received = h.store.snapshot.stateFor(TETHER).receiveGeneration
        val published = h.topicStates.size

        h.liveFence = null
        first.deliver(h.tetherFrame(1400.0))
        advanceTimeBy(1)

        assertTrue("신원 상실을 관측한 턴에 소켓을 닫지 않았다", first.cancelled)
        assertEquals("거절한 프레임이 가격을 바꿨다", rates, h.coordinator.rates.value)
        assertEquals(received, h.store.snapshot.stateFor(TETHER).receiveGeneration)

        // A live identity can come back before `Access` catches up, so the wrapper is deliberately
        // not used here: granting must not be what moves the identity.
        h.liveFence = AuthIdentityFence("u1", 3L)
        h.credential = AuthSnapshot("u1", 3L, "token-3")
        h.coordinator.setAccess(true, fence())
        h.coordinator.setForeground(true)
        advanceTimeBy(120_000)

        assertEquals("은퇴한 grant 로 다시 연결했다", sockets, h.wires.size)
        assertEquals("신원 상실 뒤 침묵을 재검증했다", requests, h.requests.size)
        assertTrue(
            "신원 상실이 topic 저하로 기록됐다",
            h.topicStates.drop(published).none {
                it.stateFor(TETHER).deliveryState in setOf(
                    TopicDeliveryState.SUSPECT,
                    TopicDeliveryState.REVALIDATING,
                    TopicDeliveryState.DEGRADED
                )
            }
        )

        h.coordinator.setAccess(true, fence(generation = 3L))
        advanceTimeBy(100)
        assertEquals("새 grant 로 복구하지 못했다", sockets + 1, h.wires.size)
        h.wire.open()
        advanceTimeBy(1)
        h.wire.deliver(h.tetherFrame(1400.0))
        advanceTimeBy(1)
        assertEquals(1L, h.store.snapshot.stateFor(TETHER).receiveGeneration)
        assertTrue(h.coordinator.rates.value.quotes.isNotEmpty())
        h.cleanUp()
    }

    /**
     * The observation is not free, so a frame this session was never going to consume must not
     * pay for it: `liveIdentity` reconciles Firebase with the auth tracker and can advance its
     * generation. Counted rather than argued.
     */
    @Test
    fun `a frame this session does not consume never reads the live identity`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)
        val reads = h.liveIdentityReads

        h.wire.deliver(h.dxyFrame(99.0))
        advanceTimeBy(1)

        assertEquals("구독하지 않는 topic 이 신원 관측을 구동했다", reads, h.liveIdentityReads)
        h.cleanUp()
    }

    /**
     * Same uid, same Firebase user object — and still a different session.
     *
     * `AuthSessionGenerationTracker.invalidate` advances the generation on an explicit call, and
     * `AuthTransitionCoordinator` runs exactly that before a sign-in starts. A check keyed on the
     * uid alone would admit the answer the previous session was owed.
     */
    @Test
    fun `the same account with a rotated generation is still a different session`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.wire.open()
        advanceTimeBy(1)

        h.liveFence = AuthIdentityFence("u1", 2L)
        h.wire.deliver(h.tetherFrame(1390.0))
        advanceTimeBy(1)

        assertTrue("generation 만 오른 전이를 같은 세션으로 읽었다", h.coordinator.rates.value.quotes.isEmpty())
        h.cleanUp()
    }

    /**
     * The body is refused, the rate limit is not — and a refusal with no HTTP answer invents none.
     *
     * The limit was levied on the transport by address; it outlives the credential that happened
     * to carry it. The bare identity change is the control: both fields are null there, and a
     * seam that fired anyway would tell a floor owner a window exists that never did.
     */
    @Test
    fun `an identity change carries the rate limit out and a bare one carries nothing`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        val before = h.bootstrapEvidence.size

        h.bootstrapOutcome = { _, _ ->
            throw AuthIdentityChangedException(statusCode = 429, retryAfter = "30")
        }
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(100)
        assertEquals(
            "서버가 닫은 창을 신원 변경과 함께 버렸다",
            listOf<Pair<Int?, String?>>(429 to "30"),
            h.bootstrapEvidence.drop(before)
        )

        val after = h.bootstrapEvidence.size
        h.bootstrapOutcome = { _, _ -> throw AuthIdentityChangedException() }
        h.coordinator.requestBootstrap(USD)
        advanceTimeBy(100)
        assertEquals("HTTP 답이 없었는데 증거가 생겼다", after, h.bootstrapEvidence.size)
        h.cleanUp()
    }

    /**
     * A refusal's evidence outlives the grant that asked for it.
     *
     * The grant filter drops the whole outcome, and the retry floor would go down with it — which
     * is how the next session arrives inside a window the server explicitly closed. So the
     * evidence leaves the job before the filter, and the hand-off still does not happen.
     */
    @Test
    fun `a refusal's evidence survives the grant that asked for it ending`() = runTest {
        val h = Harness(this)
        h.goLive()
        advanceTimeBy(100)
        h.bootstrapOutcome = { _, _ -> refusal(429, """{"error":"slow down"}""", retryAfter = "60") }
        val gate = CompletableDeferred<Unit>()
        h.bootstrapGate = gate
        h.coordinator.requestBootstrap(TETHER)
        advanceTimeBy(1)
        val handed = h.undelivered.size
        val evidence = h.bootstrapEvidence.size

        h.setAccess(false, fence())
        gate.complete(Unit)
        advanceTimeBy(100)

        assertEquals("끝난 grant 의 답이 밖으로 보고됐다", handed, h.undelivered.size)
        assertEquals(
            "주소에 매겨진 한도를 grant 와 함께 버렸다",
            listOf<Pair<Int?, String?>>(429 to "60"),
            h.bootstrapEvidence.drop(evidence)
        )
        h.cleanUp()
    }

    /** One refusal with the server's own evidence on it, built the way the transport builds one. */
    private fun refusal(code: Int, body: String, retryAfter: String? = null): TopicSnapshotOutcome {
        val headers = okhttp3.Headers.Builder().apply {
            retryAfter?.let { add("Retry-After", it) }
        }.build()
        val raw = Response.Builder()
            .request(Request.Builder().url("https://example.invalid/api/v2/topics/snapshot").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("synthetic")
            .headers(headers)
            .build()
        val response = retrofit2.Response.error<okhttp3.ResponseBody>(
            body.toByteArray().toResponseBody(),
            raw
        )
        return TopicSnapshotOutcome.Refused(
            response.preserve(AuthenticatedEndpoint.TOPIC_SNAPSHOT).failure!!
        )
    }
}
