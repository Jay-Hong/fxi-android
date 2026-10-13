package com.jay.fxi.s5fixture

import com.jay.fxi.data.remote.dto.SubscriptionAck
import com.jay.fxi.data.remote.dto.SubscriptionAckTopic
import com.jay.fxi.di.ServerEndpoints
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * S5-4 slice 4b: the S5 fixture's loopback server. Construction schedules the start on a worker; [awaitEndpoints] returns the
 * real address only after the server is bound to 127.0.0.1 on an assigned port. A start that fails or takes longer than
 * [startTimeoutMillis] closes the server for good: that call and every later one fail, and a bind that completes after the
 * close is shut down at once. After [close], no call returns an address. Payloads are read once while starting. The server keeps no request queue and records at most
 * [RECORD_LIMIT] recent requests as method, path and query names and values; headers and bodies (tokens) are never recorded or
 * reported. [unexpectedCount] keeps counting after the record drops an entry, and counts unreadable socket frames too. Each
 * socket connection keeps its own subscriptions.
 */
internal class S5FixtureServer(
    private val load: (String) -> ByteArray,
    private val report: (String) -> Unit,
    private val startTimeoutMillis: Long = 10_000L,
    /** Called with the bound port before the closed check, so a test can see a late bind shut down. */
    private val onBound: (Int) -> Unit = {}
) : AutoCloseable {
    private val server = MockWebServer()
    private val payloads = mutableMapOf<String, ByteArray>()
    private val recent = ArrayDeque<String>()
    private val unexpected = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }
    private val starter = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "s5-fixture-server").apply { isDaemon = true } }
    private val started: Future<ServerEndpoints>

    val unexpectedCount: Int get() = unexpected.get()

    fun recentRequests(): List<String> = synchronized(recent) { recent.toList() }

    fun awaitEndpoints(): ServerEndpoints {
        check(!closed.get()) { "S5 fixture server is closed" }
        return try {
            started.get(startTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Idempotent: cancels a start still running, stops the worker and shuts the server down if it is bound. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        started.cancel(true)
        starter.shutdownNow()
        server.shutdown()
    }

    private fun start(): ServerEndpoints {
        for (name in listOf("catalog.json", "tether-1d.json", "tether-1w.json", "usd-1d.json")) payloads[name] = load(name)
        payloads[S5FixtureRoutes.ENTITLEMENTS] = S5FixtureRoutes.ENTITLEMENTS_BODY.encodeToByteArray()
        server.dispatcher = dispatcher
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        onBound(server.port)
        // MockWebServer's start and shutdown are synchronized: a close that came first found nothing to shut, so the late bind is
        // shut here.
        if (closed.get()) {
            server.shutdown()
            throw IllegalStateException("S5 fixture server closed while starting")
        }
        return ServerEndpoints("http://127.0.0.1:${server.port}/", "ws://127.0.0.1:${server.port}/ws")
    }

    private fun unexpected(message: String) {
        unexpected.incrementAndGet()
        report(message)
    }

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            // MockWebServer queues every request it records; drain it so a long run keeps nothing.
            while (server.takeRequest(0, TimeUnit.MILLISECONDS) != null) Unit
            val url = request.requestUrl
            val method = request.method.orEmpty()
            val path = url?.encodedPath.orEmpty()
            val query = url?.let { u -> u.queryParameterNames.associateWith { u.queryParameterValues(it) } }.orEmpty()
            val upgrade = request.getHeader("Upgrade").equals("websocket", ignoreCase = true)
            val route = if (url == null) S5Route.Unexpected else S5FixtureRoutes.route(method, path, query, upgrade)
            val line = "$method $path " + query.entries.sortedBy { it.key }.joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }
            synchronized(recent) {
                recent.addLast(line)
                while (recent.size > RECORD_LIMIT) recent.removeFirst()
            }
            return when (route) {
                is S5Route.Ok -> MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody(Buffer().write(payloads.getValue(route.payload)))
                S5Route.Expected404 -> MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"topic_unavailable"}""")
                S5Route.WebSocket -> MockResponse().withWebSocketUpgrade(newSocketListener())
                S5Route.Unexpected -> {
                    unexpected("S5 fixture: unexpected request $line")
                    MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /** One per connection. Acknowledges every subscribe and unsubscribe as fully accepted, answers ping with pong, sends no live frame. */
    private fun newSocketListener() = object : WebSocketListener() {
        private val active = linkedSetOf<String>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "ping") {
                webSocket.send("""{"type":"pong"}""")
                return
            }
            val request = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return unexpected("S5 fixture: unreadable frame")
            val type = request["type"]?.jsonPrimitive?.content ?: return unexpected("S5 fixture: frame without type")
            val id = request["request_id"]?.jsonPrimitive?.content ?: return unexpected("S5 fixture: frame without request_id")
            val topics = request.topics()
            val removed = synchronized(active) {
                when (type) {
                    "subscribe" -> { active += topics; emptyList() }
                    "unsubscribe" -> { active -= topics.toSet(); topics }
                    else -> return unexpected("S5 fixture: unknown frame type $type")
                }
            }
            val now = synchronized(active) { active.map { SubscriptionAckTopic(it) } }
            val ack = SubscriptionAck(
                requestId = id,
                operation = type,
                acceptedTopics = if (type == "subscribe") topics.map { SubscriptionAckTopic(it) } else emptyList(),
                rejectedTopics = emptyList(),
                removedTopics = removed,
                activeSubscriptions = now
            )
            webSocket.send(json.encodeToString(SubscriptionAck.serializer(), ack).replaceFirst("{", """{"type":"subscription_ack","""))
        }

        /** Answers the peer's close, so the connection ends and the server can shut down. */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        private fun JsonObject.topics(): List<String> = this["topics"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    }

    init {
        // Last, after every property the worker reads is initialised.
        started = starter.submit<ServerEndpoints> { start() }
    }

    private companion object {
        const val RECORD_LIMIT = 64
    }
}
