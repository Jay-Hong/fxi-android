package com.jay.fxi.data.remote

import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What comes out of a real socket, in what order.
 *
 * Against `MockWebServer` rather than a fake listener, because the two things being checked here
 * are exactly the ones a fake would assume away: that frames arrive in the order they were sent,
 * and that a frame the decoder refuses costs one frame and not the connection.
 *
 * Real I/O, so the waiting is a bounded timeout rather than a virtual clock.
 */
class TopicTransportTest {

    private companion object {
        const val LOCAL = "http://localhost/ws"
        const val NORMAL_CLOSURE = 1000
    }

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var transport: TopicTransport

    /** The server's end of the socket, kept only so [stop] can end it too. */
    @Volatile private var peer: WebSocket? = null

    /** The server side of each test, installed before the socket is opened. */
    private fun serve(onText: (WebSocket, String) -> Unit) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) = onText(webSocket, text)
            })
        )
        transport.open(Request.Builder().url(server.url("/ws")).build())
    }

    @Before
    fun start() {
        peer = null
        server = MockWebServer()
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        transport = TopicTransport(client, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
    }

    @After
    fun stop() {
        // Teardown runs in two parts, and the second part always runs.
        //
        // `MockWebServer.shutdown()` gives each of its task queues five seconds to fall idle and
        // then throws `IOException("Gave up waiting for queue to shut down")`. That is what failed
        // `frames keep the order they arrived in` on CI at `24b0628` — in teardown, after its
        // assertions had passed. A connection is served on such a queue (`MockWebServer.kt:468` in
        // okhttp 4.12.0, `cancelable = false`), and for a web socket that task is a blocking read
        // loop: the queue stays busy until the loop reads a close frame. The old teardown closed
        // this end only and went straight to `shutdown()`, so whether that frame reached the
        // server inside the five seconds was left unsequenced.
        //
        // **Not reproduced locally.** Closing or not closing each end, four combinations, all shut
        // down in 0-2 ms here, so what delayed the frame on that CI run is not established. This
        // closes a window rather than a diagnosed cause.
        var failure: Throwable? = null
        try {
            awaitClosedHandshake()
        } catch (waitFailed: Throwable) {
            failure = waitFailed
        }
        try {
            // Reached even when the wait above threw — otherwise one slow close would leak a
            // client, a server and a socket into every test after it, and the run would fail
            // somewhere with no relation to the cause. Found by review.
            transport.cancel()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        } catch (cleanupFailed: Throwable) {
            // The first failure is the one that explains the rest, so it stays the one thrown.
            val first = failure
            if (first == null) failure = cleanupFailed else first.addSuppressed(cleanupFailed)
        }
        failure?.let { throw it }
    }

    /**
     * The server closes, this side answers, and teardown waits for the stream to end.
     *
     * Ending the socket from the server's side is what the read loop above is waiting to hear;
     * `TopicTransport.onClosing` answers it. **What the wait proves depends on how the stream
     * ended**: on `Closed` this side has written its close frame, because okhttp raises `onClosed`
     * from `writeOneFrame` *after* `writeClose` (`RealWebSocket.kt:533-537`, 4.12.0). The stream
     * also ends on `Failed` and on `cancel()`, and then the wait is a bound and nothing more —
     * `TopicTransport` closes its outbox on all three. Nothing is asserted here, because each test
     * body already asserts its own outcome and a second opinion in teardown would answer for a
     * test that has already spoken. Found by review.
     *
     * `close()` and not `cancel()`: a server-side `RealWebSocket` has no `Call` behind it and
     * `cancel()` throws `NullPointerException` where it stands.
     */
    private fun awaitClosedHandshake() {
        val serverHalf = peer ?: return
        serverHalf.close(NORMAL_CLOSURE, null)
        runBlocking { withTimeout(5.seconds) { transport.events.collect { } } }
    }

    private fun dxyFrame(rate: Double) =
        """{"type":"snapshot","version":1,"topic":"dxy:spot","data":{"dxy":{
            "rate":$rate,"timestamp":"2026-08-31T10:20:00+09:00","source":"investing"}}}"""

    /** Reads events until [count] have arrived, failing rather than hanging if they do not. */
    private fun take(count: Int): List<TopicTransportEvent> = runBlocking {
        withTimeout(5.seconds) {
            val seen = mutableListOf<TopicTransportEvent>()
            transport.events.first { event ->
                seen += event
                seen.size == count
            }
            seen
        }
    }

    /**
     * The keep-alive round trip, end to end.
     *
     * The client sends raw text and the server answers in JSON (D10). This is the shape a pong
     * timeout would wait on, so it is worth seeing arrive over a socket rather than assumed.
     */
    @Test
    fun `a raw ping is answered by a json pong`() {
        serve { socket, text -> if (text == "ping") socket.send("""{"type":"pong"}""") }

        val opened = take(1)
        assertEquals(listOf(TopicTransportEvent.Opened), opened)
        assertTrue(transport.send("ping"))
        assertEquals(
            TopicTransportEvent.Received(DecodedTopicFrame.Pong),
            take(1).single()
        )
    }

    /**
     * A frame the decoder refuses costs one frame.
     *
     * D11's whole point: the decoder fails, the transport isolates, and the socket keeps reading.
     * The valid snapshot after the bad frame is what proves the connection survived it — and it
     * arrives after, not before, which is the ordering half of the same contract.
     */
    @Test
    fun `an undecodable frame is isolated and the next one still arrives`() {
        serve { socket, _ ->
            socket.send("{ this is not json")
            socket.send("""{"type":"snapshot","version":1,"topic":"dxy:spot","data":{"dxy":{
                "rate":103.4,"timestamp":"2026-08-31T10:20:00+09:00","source":"investing"}}}""")
        }

        take(1)
        transport.send("go")
        val (bad, good) = take(2)

        assertTrue("첫 프레임은 격리돼야 한다: $bad", bad is TopicTransportEvent.Undecodable)
        assertEquals(18, (bad as TopicTransportEvent.Undecodable).length)
        assertTrue("다음 프레임이 그대로 와야 한다: $good", good is TopicTransportEvent.Received)
        assertTrue((good as TopicTransportEvent.Received).frame is DecodedTopicFrame.Dxy)
    }

    /**
     * Frames come out in the order they went in.
     *
     * The legacy service launched a coroutine per message, which is the same work in an order
     * nobody chose. The merge would still refuse an older reading, so what reordering costs is
     * everything the merge cannot referee — an ACK against the snapshot it admits, and two
     * readings sharing an instant, where the rule is that the first one stays.
     */
    @Test
    fun `frames keep the order they arrived in`() {
        val rates = listOf(101.0, 102.0, 103.0, 104.0, 105.0)
        serve { socket, _ ->
            rates.forEach {
                socket.send(
                    """{"type":"snapshot","version":1,"topic":"dxy:spot","data":{"dxy":{
                        "rate":$it,"timestamp":"2026-08-31T10:20:00+09:00","source":"investing"}}}"""
                )
            }
        }

        take(1)
        transport.send("go")
        val received = take(rates.size).map {
            ((it as TopicTransportEvent.Received).frame as DecodedTopicFrame.Dxy).value.data.dxy.rate
        }

        assertEquals(rates, received)
    }

    /**
     * Nothing is dropped while nobody is listening.
     *
     * The socket tests above cannot show this: their collector keeps up with the server, so a
     * buffer that only kept the newest event would still look right. Here every frame is delivered
     * **before** anything reads, so the buffer is the only thing holding them — and a burst that
     * arrives faster than the layer above can drain it is the ordinary case, not the exotic one.
     * Driven through the listener directly rather than a socket, so the result does not depend on
     * who wins a race. Found by review: the conflated-buffer mutation survived without it.
     */
    @Test
    fun `frames delivered before anyone reads are all kept, in order`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())

        val listener = requireNotNull(captured.listener)
        listener.onOpen(captured.socket, upgradeResponse())
        val rates = listOf(201.0, 202.0, 203.0, 204.0, 205.0)
        rates.forEach { listener.onMessage(captured.socket, dxyFrame(it)) }

        val events = runBlocking {
            withTimeout(5.seconds) {
                val seen = mutableListOf<TopicTransportEvent>()
                direct.events.first { seen += it; seen.size == rates.size + 1 }
                seen
            }
        }

        assertEquals(TopicTransportEvent.Opened, events.first())
        assertEquals(
            rates,
            events.drop(1).map {
                ((it as TopicTransportEvent.Received).frame as DecodedTopicFrame.Dxy).value.data.dxy.rate
            }
        )
    }

    /**
     * The body never leaves the transport, not even inside an exception.
     *
     * Both decoders quote the input back: kotlinx.serialization appends `JSON input: …`, and the
     * date one says `Failed to parse an instant from '…'`. Measured, not assumed — and it is why
     * the event carries a length and a failure name rather than the `Throwable`. Anything that
     * prints this event prints no frame. Found by review.
     */
    @Test
    fun `an undecodable frame carries no part of its body`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())
        val listener = requireNotNull(captured.listener)
        listener.onOpen(captured.socket, upgradeResponse())

        listOf(
            "{ SECRETMARKER not json",
            """{"type":"snapshot","version":1,"topic":"dxy:spot","data":{"dxy":{
                "rate":1,"timestamp":"SECRETMARKER","source":"x"}}}"""
        ).forEach { listener.onMessage(captured.socket, it) }

        val events = drain(direct, 3)
        events.drop(1).forEach {
            assertTrue("$it 가 격리되지 않았다", it is TopicTransportEvent.Undecodable)
            assertTrue("본문이 사건에 실렸다: $it", !it.toString().contains("SECRETMARKER"))
        }
    }

    /**
     * A connection is opened once, and the stream ends when it does.
     *
     * Opening twice would replace the socket while the first kept delivering into the same stream
     * with nobody holding a handle to close it. And a stream that stays open after the connection
     * has gone leaves every collector waiting on a socket that no longer exists. Found by review.
     */
    @Test
    fun `a transport is single use and its stream ends with the connection`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())

        assertThrows(IllegalStateException::class.java) {
            direct.open(Request.Builder().url(LOCAL).build())
        }

        val listener = requireNotNull(captured.listener)
        listener.onOpen(captured.socket, upgradeResponse())
        listener.onClosed(captured.socket, 1000, "bye")

        val all = runBlocking { withTimeout(5.seconds) { direct.events.toList() } }
        assertEquals(
            listOf(TopicTransportEvent.Opened, TopicTransportEvent.Closed(1000, "bye")),
            all
        )
    }

    /**
     * A consumer that sends the moment it hears `Opened` finds a socket.
     *
     * OkHttp may run `onOpen` before `newWebSocket` returns, so a transport that only learned its
     * socket from the return value would answer `false` to a send in exactly that window — after
     * the event is out and before `open` has anything to store.
     *
     * The send happens **inside** `newWebSocket`, which is the whole point: sending after `open`
     * returns misses the window entirely, and a test that did so passed with the fix removed.
     */
    @Test
    fun `a send inside the open window works because onOpen supplies the socket`() {
        lateinit var direct: TopicTransport
        val early = object : WebSocket.Factory {
            val socket = RecordingSocket()
            var sentDuringOpen: Boolean? = null

            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                listener.onOpen(socket, upgradeResponse())
                sentDuringOpen = direct.send("ping")
                return socket
            }
        }
        direct = TopicTransport(early, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())

        assertEquals("Opened 직후, open 이 반환하기 전에 보낼 소켓이 없었다", true, early.sentDuringOpen)
        assertEquals(listOf("ping"), early.socket.sent)
    }

    /**
     * A socket that turns up after the transport is done with is let go of, not adopted.
     *
     * Cancelling while the factory is still constructing one is reachable and used to leave the
     * worst of both: the event stream closed and the socket alive, with nobody holding a handle to
     * it. The socket the factory returns is cancelled, and a callback still in flight registers
     * nothing and says nothing. Found by review.
     */
    @Test
    fun `a socket arriving after cancellation is cancelled rather than adopted`() {
        lateinit var direct: TopicTransport
        val late = object : WebSocket.Factory {
            val socket = RecordingSocket()
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                direct.cancel()                                   // …while this is still running
                listener.onOpen(socket, upgradeResponse())        // …and a callback still arrives
                return socket
            }
        }
        direct = TopicTransport(late, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())

        assertTrue("취소된 뒤 도착한 소켓을 붙들었다", late.socket.cancelled)
        assertEquals("취소 뒤에도 보낼 수 있었다", false, direct.send("ping"))
        assertEquals(emptyList<TopicTransportEvent>(), runBlocking {
            withTimeout(5.seconds) { direct.events.toList() }
        })
    }

    /**
     * A graceful close asked for before the socket existed is not lost.
     *
     * While the factory is still building one there is nothing to close, and dropping the request
     * there left the connection running after somebody had asked it to stop. It is kept and
     * applied to whichever socket turns up. Found by review.
     */
    @Test
    fun `a close during the open window is applied to the socket that arrives`() {
        lateinit var direct: TopicTransport
        val slow = object : WebSocket.Factory {
            val socket = RecordingSocket()
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                direct.close(1000, "changed my mind")
                return socket
            }
        }
        direct = TopicTransport(slow, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())

        assertEquals(listOf(1000 to "changed my mind"), slow.socket.closes)
        assertTrue("우아한 종료를 취소로 처리했다", !slow.socket.cancelled)
    }

    /**
     * A socket that already failed is not cancelled on the way past.
     *
     * A synchronous `onFailure` ends the transport before the factory has returned, and the socket
     * it then hands back is one OkHttp never finished building. Cancelling that reaches inside for
     * a call it never made, so only an end that was *asked for* cancels. Found by review.
     */
    @Test
    fun `a socket that failed synchronously is left alone`() {
        val failing = object : WebSocket.Factory {
            val socket = RecordingSocket()
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                listener.onFailure(socket, IllegalStateException("no upgrade"), null)
                return socket
            }
        }
        val direct = TopicTransport(failing, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())

        assertTrue("이미 죽은 소켓을 취소했다", !failing.socket.cancelled)
        val all = runBlocking { withTimeout(5.seconds) { direct.events.toList() } }
        assertEquals(1, all.size)
        assertTrue("$all", all.single() is TopicTransportEvent.Failed)
    }

    /**
     * A cancel and a failure that overlap still leave the dead socket alone.
     *
     * Two orders reach the same place and both were reachable: a cancel while the factory is
     * working, followed by the synchronous failure already on its way; and a collector answering
     * `Failed` by cancelling at once. Whichever came first, the socket has reported terminal and
     * OkHttp never finished building it — reaching for it is a call that was never made. Found by
     * review, after the first fix handled only one of the two orders.
     */
    @Test
    fun `a cancel overlapping a synchronous failure leaves the socket alone`() {
        lateinit var direct: TopicTransport
        val both = object : WebSocket.Factory {
            val socket = RecordingSocket()
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                direct.cancel()                                            // asked to stop…
                listener.onFailure(socket, IllegalStateException("no upgrade"), null)  // …and it died
                return socket
            }
        }
        direct = TopicTransport(both, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())

        assertTrue("이미 죽은 소켓을 취소했다", !both.socket.cancelled)
    }

    /** …and in the other order: the failure first, the cancel from whoever saw it. */
    @Test
    fun `a cancel answering a failure leaves the socket alone`() {
        lateinit var direct: TopicTransport
        val failThenCancel = object : WebSocket.Factory {
            val socket = RecordingSocket()
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                listener.onFailure(socket, IllegalStateException("no upgrade"), null)
                direct.cancel()          // what a collector reading `Failed` would do
                return socket
            }
        }
        direct = TopicTransport(failThenCancel, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())

        assertTrue("실패를 본 뒤의 취소가 죽은 소켓을 건드렸다", !failThenCancel.socket.cancelled)
    }

    /**
     * Nothing is sent once a stop has been asked for, including in the gap before it lands.
     *
     * A close requested while the factory is still working is applied a moment later, and a send
     * slipped into that gap would go out on a connection its caller had already asked to end.
     * The refusal is logical — from the moment the request is recorded — not a property of the
     * socket. Found by review.
     */
    @Test
    fun `nothing is sent after a stop has been asked for`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())
        requireNotNull(captured.listener).onOpen(captured.socket, upgradeResponse())

        // A socket is bound, so nothing but the recorded intent can refuse this send. Asking with
        // no socket yet would pass whether the intent is checked or not — the first version of
        // this test did exactly that and both guard mutations survived it.
        assertTrue("사전 조건: 종료 전에는 보낼 수 있어야 한다", direct.send("early"))
        direct.close(1000, "stop")

        assertEquals("종료 요청 뒤에 보냈다", false, direct.send("late"))
        assertEquals(listOf("early"), captured.socket.sent)
        assertEquals(listOf(1000 to "stop"), captured.socket.closes)
    }

    /**
     * …and after a cancel, which holds for a different reason: the socket is released.
     *
     * No flag is involved. Worth its own test anyway — the behaviour is what a caller relies on,
     * and if `cancel` ever stopped releasing the socket this is what would say so.
     */
    @Test
    fun `nothing is sent after a cancel`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())
        requireNotNull(captured.listener).onOpen(captured.socket, upgradeResponse())

        assertTrue(direct.send("early"))
        direct.cancel()

        assertEquals("취소 뒤에 보냈다", false, direct.send("late"))
        assertEquals(listOf("early"), captured.socket.sent)
    }

    /**
     * A cancel after a normal close leaves the socket alone too.
     *
     * The same invariant as the failure path, and it needs saying separately: a close that
     * completes is a connection that is over, and reaching for it afterwards is reaching for
     * something finished. Without this, dropping the internal record from `onClosed` goes
     * unnoticed — the stream still ends, so every other test is happy. Found by review.
     */
    @Test
    fun `a cancel after a normal close leaves the socket alone`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())
        val listener = requireNotNull(captured.listener)
        listener.onOpen(captured.socket, upgradeResponse())
        listener.onClosed(captured.socket, 1000, "bye")

        direct.cancel()

        assertTrue("끝난 연결의 소켓을 취소했다", !captured.socket.cancelled)
        assertEquals("끝난 뒤에 보냈다", false, direct.send("late"))
    }

    /**
     * The end is recorded before it is announced.
     *
     * A collector that answers `Failed` by cancelling is the ordinary path, and it can run the
     * instant the event is published. If the internal state were settled *after* publishing, that
     * collector would find a transport that still thought the socket was live — and would send on
     * it, and cancel a socket OkHttp never finished building.
     *
     * No timing involved: an undispatched collector on `Unconfined` re-enters on this very thread,
     * inside the publish. Shown to me by review, which also measured that publishing first makes
     * both of the assertions below flip.
     */
    @Test
    fun `the end is settled before the event is published`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.open(Request.Builder().url(LOCAL).build())
        val listener = requireNotNull(captured.listener)
        listener.onOpen(captured.socket, upgradeResponse())

        runBlocking {
            val waiter = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                direct.events.first { it is TopicTransportEvent.Failed }
                assertEquals("실패를 본 순간 아직 보낼 수 있었다", false, direct.send("late"))
                direct.cancel()
            }
            listener.onFailure(captured.socket, IllegalStateException("failed"), null)
            waiter.join()
        }

        assertTrue("이미 죽은 소켓을 취소했다", !captured.socket.cancelled)
        assertEquals(emptyList<String>(), captured.socket.sent)
    }

    /**
     * A factory that refuses outright ends the transport rather than stranding it.
     *
     * Some requests it will not upgrade at all, and it says so by throwing. Leaving the stage open
     * and the stream running would leave every collector waiting on a connection that never was.
     */
    @Test
    fun `a factory that throws ends the stream`() {
        val refusing = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
                throw IllegalArgumentException("not upgradable")
        }
        val direct = TopicTransport(refusing, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)

        assertThrows(IllegalArgumentException::class.java) {
            direct.open(Request.Builder().url(LOCAL).build())
        }
        val all = runBlocking { withTimeout(5.seconds) { direct.events.toList() } }
        assertEquals(1, all.size)
        assertTrue("$all", all.single() is TopicTransportEvent.Failed)
    }

    /** Cancelling before anything opened still ends it: a later `open` is refused. */
    @Test
    fun `cancelling before opening still ends the transport`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured, TopicFrameDecoder(Json { ignoreUnknownKeys = true })::decode)
        direct.cancel()

        assertThrows(IllegalStateException::class.java) {
            direct.open(Request.Builder().url(LOCAL).build())
        }
        assertEquals(null, captured.listener)
    }

    /**
     * The peer closing first is answered, and the stream ends.
     *
     * Over a real socket, because what is being checked is the handshake: `onClosing` is where
     * this side completes it, and calling `onClosed` by hand would prove nothing about that.
     */
    @Test
    fun `a close started by the server is completed and ends the stream`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.close(1000, "server done")
                }
            })
        )
        transport.open(Request.Builder().url(server.url("/ws")).build())

        val all = runBlocking { withTimeout(5.seconds) { transport.events.toList() } }
        assertEquals(TopicTransportEvent.Opened, all.first())
        assertTrue("연결이 끝나지 않았다: $all", all.last() is TopicTransportEvent.Closed)
    }

    /**
     * Only the chosen family is isolated; everything else travels on.
     *
     * The isolation is for input this client cannot read — the family both decoders fail in.
     * Catching everything would file a bug here as a bad frame and keep reading, which hides it
     * behind a connection that looks healthy. Not a perfect fence: an `IllegalArgumentException`
     * raised by our own mistake inside the decoder is indistinguishable from a bad date. What this
     * checks is that something outside the family comes back out.
     */
    @Test
    fun `a fault that is not a decode failure is not isolated`() {
        val captured = CapturingFactory()
        val direct = TopicTransport(captured) { error("a bug in this app, not a bad frame") }
        direct.open(Request.Builder().url(LOCAL).build())
        val listener = requireNotNull(captured.listener)
        listener.onOpen(captured.socket, upgradeResponse())

        assertThrows(IllegalStateException::class.java) {
            listener.onMessage(captured.socket, """{"type":"pong"}""")
        }
    }

    private fun drain(transport: TopicTransport, count: Int): List<TopicTransportEvent> =
        runBlocking {
            withTimeout(5.seconds) {
                val seen = mutableListOf<TopicTransportEvent>()
                transport.events.first { seen += it; seen.size == count }
                seen
            }
        }

    private fun upgradeResponse() = Response.Builder()
        .request(Request.Builder().url(LOCAL).build())
        .protocol(okhttp3.Protocol.HTTP_1_1).code(101).message("").build()

    private open class RecordingSocket : WebSocket {
        val sent = mutableListOf<String>()
        var cancelled = false
        val closes = mutableListOf<Pair<Int, String?>>()
        override fun cancel() { cancelled = true }
        override fun close(code: Int, reason: String?): Boolean { closes += code to reason; return true }
        override fun queueSize() = 0L
        override fun request() = Request.Builder().url(LOCAL).build()
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: okio.ByteString) = true
    }

    /** Hands back the listener the transport installed, so frames can be delivered without a socket. */
    private class CapturingFactory : WebSocket.Factory {
        var listener: WebSocketListener? = null
        val socket = RecordingSocket()

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return socket
        }
    }

    /**
     * A raw text `pong` is not a pong (D10).
     *
     * There is no fast path for one: it is not JSON, so it fails to decode and is isolated like
     * any other malformed frame. Accepting it would invent a keep-alive contract the server does
     * not have, and a client that believed it would think a silent connection was answering.
     */
    @Test
    fun `a raw text pong is isolated rather than accepted`() {
        serve { socket, _ -> socket.send("pong") }

        take(1)
        transport.send("ping")
        assertTrue(take(1).single() is TopicTransportEvent.Undecodable)
    }
}
