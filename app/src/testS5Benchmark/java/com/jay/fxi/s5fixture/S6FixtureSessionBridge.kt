package com.jay.fxi.s5fixture

import com.jay.fxi.S64HeadPacFactory
import com.jay.fxi.S64HeadWorld
import com.jay.fxi.data.auth.AuthIdentity
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthTokenProvider
import com.jay.fxi.data.auth.AuthTokenSource
import com.jay.fxi.data.entitlements.KrxBetaPac
import com.jay.fxi.data.entitlements.KrxContestSwitch
import com.jay.fxi.data.entitlements.PremiumAccessCoordinator
import com.jay.fxi.data.entitlements.ProbeJitter
import com.jay.fxi.data.entitlements.krxBetaPac
import com.jay.fxi.data.remote.AuthSnapshotInterceptor
import com.jay.fxi.data.remote.AuthenticatedApiClient
import com.jay.fxi.data.remote.AuthenticatedApiService
import com.jay.fxi.data.remote.AuthenticatedTransport
import com.jay.fxi.data.remote.KrxWire
import com.jay.fxi.data.remote.S64Observation
import com.jay.fxi.data.remote.TopicRuntimeFactory
import com.jay.fxi.data.remote.TopicSnapshotBootstrapService
import com.jay.fxi.data.remote.TopicSnapshotOutcome
import com.jay.fxi.data.remote.TopicUseNetworkInterceptor
import com.jay.fxi.domain.model.FreeTab
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import retrofit2.Retrofit

/** Only endpoint/wire assembly changes. No session, grant, acceptance or receipt field is written. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class S6FixtureSessionBridge(test: TestScope, val subId: String, tab: FreeTab, on: Boolean) : AutoCloseable {
    val server = S5FixtureServer({ File("src/benchmark/assets/s5_4/$it").readBytes() }, {})
    private val endpoints = server.awaitEndpoints()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private var beta: KrxBetaPac? = null
    private val sockets = mutableListOf<SocketBridge>()
    val world = S64HeadWorld(test, "D16/S-$subId", tab,
        pacFactory = S64HeadPacFactory { p ->
            if (on) krxBetaPac(p.source, p.store, p.purger, p.purger, p.scope, p.clock,
                { p.source.liveFence }, p.orders).also { beta = it }.coordinator
            else PremiumAccessCoordinator(source = p.source, store = p.store, userPurger = p.purger,
                capabilityPurger = p.purger, scope = p.scope, clock = p.clock,
                liveFence = { p.source.liveFence }, orders = p.orders, jitter = ProbeJitter.None,
                krxContest = KrxContestSwitch.OFF)
        }, configure = { _, factory ->
            factory.installKrxDesiredProducer(if (on) checkNotNull(beta).sharedSwitch else KrxContestSwitch.OFF)
            // S64HeadWorld exposes configure but no wire-factory setter. Wrap its original factory
            // before create(), keeping KrxWire's existing send/time ledger and disabling its fake pong.
            val field = TopicRuntimeFactory::class.java.getDeclaredField("webSocketFactory").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = field.get(factory) as () -> WebSocket.Factory
            val wrapped: () -> WebSocket.Factory = {
                SocketBridge((original() as KrxWire).also { it.autoPong = false }).also { sockets += it }
            }
            field.set(factory, wrapped)
        })
    val observation = S64Observation(world.runtime, "D16/S-$subId")
    val calls = mutableListOf<RestCall>()
    val frames = mutableListOf<String>()

    class RestCall(val source: S64HeadWorld.Call, val issue: S64Observation.RestIssue?, val job: Job) {
        val topic get() = source.topic
        var tokenStarted = false
        var tokenReturned = false
        var status: Int? = null
        var serverReached = false
        var outcome: TopicSnapshotOutcome? = null
        var returned = false
        var cancelled = false
        @Volatile var headersSentAt: Long? = null
    }

    init { world.bootstrapEndpoint = ::bootstrap }

    fun eq(obligation: String, expected: Any?, actual: Any?) =
        assertEquals("contract(D16):S-$subId/$obligation", expected, actual)
    fun yes(obligation: String, actual: Boolean) = assertTrue("contract(D16):S-$subId/$obligation", actual)
    fun premise(label: String, actual: Boolean) = assertTrue("precondition(D16):S-$subId/$label", actual)

    /** Wall-time watchdog only: I/O completion never advances the virtual contract clock. */
    fun awaitIo(label: String, ready: () -> Boolean) {
        val at = world.clock.nowMillis()
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            world.drain()
            if (ready()) break
            premise("$label (real I/O watchdog)", System.nanoTime() < end)
            Thread.sleep(1)
        }
        premise("$label keeps virtual time", world.clock.nowMillis() == at)
    }

    /** Waits for all already issued REST work except a deliberately blocked token. */
    fun settleRest() = awaitIo("issued REST reaches service terminal or held token") {
        calls.all { it.returned || it.cancelled || it.tokenStarted && !it.tokenReturned && it.topic in world.tokenHold }
    }

    fun advance(millis: Long) { world.advance(millis); settleRest() }

    private suspend fun bootstrap(owner: AuthIdentityFence, topic: String, admitted: () -> Boolean): TopicSnapshotOutcome {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val source = world.calls.last()
        val call = RestCall(source, observation.issue(job, topic), job).also { calls += it }
        val provider = AuthTokenProvider(object : AuthTokenSource {
            override fun currentIdentity() = AuthIdentity(world.identity.uid, world.identity.authGeneration)
            override suspend fun fetchToken(identity: AuthIdentity, forceRefresh: Boolean): String {
                call.tokenStarted = true
                if (topic !in world.tokenHold) source.token.complete(Unit)
                source.token.await()
                call.tokenReturned = true
                return "d16-local-token"
            }
        }, processScope = world.tokenScope, orders = world.h.parts.orders)
        val wireClient = client.newBuilder().eventListener(object : EventListener() {
            override fun requestHeadersEnd(callOnWire: Call, request: Request) {
                call.headersSentAt = world.clock.nowMillis()
            }
        }).addInterceptor(AuthSnapshotInterceptor(provider))
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val raw = response.body
                if (raw == null) response else response.newBuilder().body(object : ResponseBody() {
                    val bytes = Buffer()
                    override fun contentType() = raw.contentType()
                    override fun contentLength() = raw.contentLength()
                    private fun ended() {
                        source.bodyEnd = world.clock.nowMillis()
                        source.responseBody = bytes.clone().readUtf8()
                    }
                    private val observed by lazy { object : ForwardingSource(raw.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            val count = super.read(sink, byteCount)
                            if (count > 0) sink.copyTo(bytes, sink.size - count, count)
                            if (count == -1L) ended()
                            return count
                        }
                        override fun close() {
                            try { super.close() } finally {
                                ended()
                            }
                        }
                    }.buffer() }
                    override fun source() = observed
                }).build()
            }.addNetworkInterceptor(TopicUseNetworkInterceptor(provider)).build()
        val api = Retrofit.Builder().baseUrl(endpoints.restBaseUrl).client(wireClient).build()
            .create(AuthenticatedApiService::class.java)
        // Delegate the actual Retrofit call; do not construct a Response or ResponseBody here.
        val endpoint = object : AuthenticatedApiService by api {
            override suspend fun getTopicSnapshot(auth: com.jay.fxi.data.remote.AuthRequestTag, topic: String): retrofit2.Response<ResponseBody> {
                val response = api.getTopicSnapshot(auth, topic)
                call.status = response.code()
                call.serverReached = server.recentRequests().count { it == restLine(topic) } == 1
                premise("actual server arrival paired with HTTP response for $topic", call.serverReached)
                source.physicallySent = call.headersSentAt
                premise("wire header completion observed for $topic", source.physicallySent != null)
                source.exchanges += S64HeadWorld.Exchange("GET", "/api/v2/topics/snapshot", mapOf("topic" to topic),
                    checkNotNull(source.physicallySent), response.code())
                return response
            }
        }
        val service = TopicSnapshotBootstrapService(AuthenticatedApiClient(endpoint,
            AuthenticatedTransport(provider) { true }, Json { ignoreUnknownKeys = true }), world.decoder)
        try {
            return service.bootstrap(owner, topic, admitted).also { call.outcome = it; call.returned = true }
        } catch (cancelled: CancellationException) {
            call.cancelled = true
            throw cancelled
        }
    }

    fun restArrivals(): List<String> = server.recentRequests().filter { it.startsWith("GET /api/v2/topics/snapshot ") }
    fun assertPhysicalPairing() {
        eq("physical-server-arrivals", calls.filter { it.serverReached }.map { restLine(it.topic) }.sorted(), restArrivals().sorted())
        eq("physical-rig-ledger", calls.count { it.serverReached }, world.calls.count { it.physicallySent != null })
        calls.filter { it.serverReached }.forEach {
            yes("bodyEnd-${it.topic}", it.source.bodyEnd != null)
            yes("response-bytes-${it.topic}", it.source.responseBody != null)
        }
    }

    /** Extra observer fields are read-only; unlike the assembly seam these never set a field. */
    private fun sessionRead(name: String): Any? {
        require(name in setOf("krxWsReceipt", "krxBootstrapOwed", "krxLadderJob", "bootstrapsOut", "krxInitialIssuedGrant"))
        return observation.session.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(observation.session) }
    }
    private fun activeCaptureObject(): Any? {
        val connection = observation.session.javaClass.getDeclaredField("connection").let {
            it.isAccessible = true; it.get(observation.session)
        } ?: return null
        return connection.javaClass.getDeclaredField("krxActive").let { it.isAccessible = true; it.get(connection) }
    }
    fun wsSatisfiedByOriginalCapture(): Boolean = sessionRead("krxWsReceipt")?.let { it === activeCaptureObject() } == true &&
        observation.connection()?.capture?.epoch == observation.epoch
    fun assertKTerminal() {
        yes("K-plan-terminal", S64HeadWorld.K !in observation.plan)
        eq("K-issue-terminal", emptyList<S64Observation.RestIssue>(), observation.restIssues)
        eq("K-owed-terminal", null, sessionRead("krxBootstrapOwed"))
        yes("K-ladder-terminal", (sessionRead("krxLadderJob") as Job?)?.isActive != true)
        val outstanding = sessionRead("bootstrapsOut") as Map<*, *>
        yes("all-issued-jobs-terminal", outstanding.isEmpty())
    }
    fun initialSlotIssuedForCurrentGrant() = sessionRead("krxInitialIssuedGrant") == observation.epoch

    private sealed interface SocketEvent {
        data class Open(val response: Response) : SocketEvent
        data class Text(val text: String) : SocketEvent
        data class Failure(val cause: Throwable, val response: Response?) : SocketEvent
        data class Closed(val code: Int, val reason: String) : SocketEvent
    }

    /** Real callbacks are queued, then delivered unchanged on the test thread one at a time. */
    private inner class SocketBridge(val rig: KrxWire) : WebSocket.Factory {
        val events = LinkedBlockingQueue<SocketEvent>()
        lateinit var actual: WebSocket
        lateinit var local: WebSocket
        lateinit var downstream: WebSocketListener
        val facade = object : WebSocket {
            override fun request() = actual.request()
            override fun queueSize() = actual.queueSize()
            override fun send(text: String): Boolean {
                val accepted = actual.send(text)
                // KrxWire records only a successful enqueue to the real OkHttp socket.
                // ACK from that server is asserted separately; enqueue alone proves no arrival.
                if (accepted) check(local.send(text))
                return accepted
            }
            override fun send(bytes: ByteString): Boolean = actual.send(bytes)
            override fun close(code: Int, reason: String?) = actual.close(code, reason)
            override fun cancel() { local.cancel(); actual.cancel() }
        }
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            downstream = listener
            local = rig.newWebSocket(request, listener)
            actual = client.newWebSocket(request.newBuilder().url(endpoints.webSocketUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { events.put(SocketEvent.Open(response)) }
                override fun onMessage(webSocket: WebSocket, text: String) { events.put(SocketEvent.Text(text)) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.put(SocketEvent.Failure(t, response)) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.put(SocketEvent.Closed(code, reason)) }
            })
            return facade
        }
        fun deliver(event: SocketEvent) {
            when (event) {
                is SocketEvent.Open -> downstream.onOpen(facade, event.response)
                is SocketEvent.Text -> { frames += event.text; downstream.onMessage(facade, event.text) }
                is SocketEvent.Failure -> throw AssertionError("precondition(D16):S-$subId/loopback WS failed", event.cause)
                is SocketEvent.Closed -> downstream.onClosed(facade, event.code, event.reason)
            }
            world.drain()
        }
        fun cancel() { if (this::actual.isInitialized) actual.cancel() }
    }

    fun openActualSocket() {
        premise("rig socket created", sockets.size == 1 && world.wires.size == 1)
        val socket = sockets.single()
        awaitIo("actual loopback upgrade") { socket.events.isNotEmpty() }
        val event = socket.events.remove()
        premise("first callback is real 101", event is SocketEvent.Open && event.response.code == 101)
        socket.deliver(event)
        premise("server recorded upgrade", server.recentRequests().contains("GET /ws "))
    }

    /** Bounded wait is an assertion on missing server behavior, never a fabricated input. */
    fun nextFrame(obligation: String, timeoutMillis: Long = 2_000, beforeDeliver: (String) -> Unit = {}): String {
        val socket = sockets.single()
        val event = socket.events.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        yes(obligation, event is SocketEvent.Text)
        val text = (event as SocketEvent.Text).text
        beforeDeliver(text)
        socket.deliver(event)
        return text
    }

    fun assertNoExtraFrames() {
        eq("no-extra-server-frame", null, sockets.single().events.poll(100, TimeUnit.MILLISECONDS))
    }

    fun assertOnPublicationHeld() { beta?.check?.assertHeld() }

    override fun close() {
        try { world.close() } finally {
            sockets.forEach { it.cancel() }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.close()
        }
    }

    companion object {
        fun restLine(topic: String) = "GET /api/v2/topics/snapshot topic=$topic"
    }
}
