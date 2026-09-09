package com.jay.fxi.data.remote

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionAckTopic
import com.jay.fxi.data.remote.dto.SubscriptionRejection
import com.jay.fxi.data.remote.dto.TopicSubscribeRequest
import com.jay.fxi.domain.model.TopicRejectionReason
import com.jay.fxi.domain.model.TopicSubscriptionStateStore
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        fun fence(uid: String = "u1", generation: Long = 1L, epoch: String = "epoch-1") =
            TopicSessionFence(AuthIdentityFence(uid, generation), epoch)
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

    private class Harness(test: TestScope) {
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
        val clock = object : TopicCommandClock {
            override fun nowMillis(): Long = scheduler.currentTime + clockSkewMillis
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        var credential = AuthSnapshot("u1", 1L, "token-1")
        var refreshed: AuthSnapshot? = null
        val credentials = object : TopicCommandCredentials {
            override suspend fun currentSnapshot() = credential
            override suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot) = refreshed
        }
        val acknowledgements = mutableListOf<TopicCommandAcknowledgement>()

        val wires = mutableListOf<Wire>()
        var failNextConnect = false
        val requests = mutableListOf<TopicSubscribeRequest>()
        val rejected = mutableListOf<Map<String, TopicRejectionReason>>()
        val undecodable = mutableListOf<Pair<Int, String>>()
        var jitterUnit = 0.0
        var clockSkewMillis = 0L

        /** Applied to every socket this harness makes; see [Wire.autoPong]. */
        var autoPong = true

        val decode = TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode

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
            store = store,
            encode = { request -> requests += request; "encoded-${request.requestId}" },
            newRequestId = { "r${requests.size + 1}" },
            jitter = { jitterUnit },
            onRejected = { rejected += it },
            onAcknowledgement = { acknowledgements += it },
            onUndecodable = { length, failure -> undecodable += length to failure },
            desired = setOf(TETHER, USD)
        )

        val wire: Wire get() = wires.last()

        fun goLive() {
            coordinator.start()
            coordinator.setAccess(true, fence())
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

        fun subscriptionError(requestId: String, code: String) =
            """{"type":"subscription_error","request_id":"$requestId","error":"$code"}"""

        fun tetherFrame(rate: Double) =
            """{"type":"snapshot","version":1,"topic":"usdt:krw","data":{"usdt_krw":[
               {"source":"upbit","asset":"usdt-krw","rate":$rate,
                "timestamp":"2026-08-31T10:20:00+09:00"}],"usd_krw_banks":[]}}"""

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

        h.coordinator.setAccess(true, fence())
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
     * empty one — and `ANDROID_V2_PLAN.md:889` says KRX must not satisfy tether delivery. The two
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

        h.coordinator.setAccess(true, fence(uid = "u2"))
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
        h.coordinator.setAccess(true, fence(uid = "u2"))
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
        h.coordinator.setAccess(true, fence(uid = "u2"))
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

        repeat(3) { h.coordinator.setAccess(true, fence()) }
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
        h.coordinator.setAccess(true, fence(uid = "u3"))
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
}
