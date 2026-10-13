package com.jay.fxi.s5fixture

import java.io.File
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** S5-4 slice 4b: the fixture server on loopback, its fixed answers, its unexpected count, its sockets and its lifecycle. */
class S5FixtureServerTest {

    private val reports = LinkedBlockingQueue<String>()
    private val loads = ConcurrentHashMap<String, AtomicInteger>()
    private val servers = mutableListOf<S5FixtureServer>()
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    private fun asset(name: String) = File("src/benchmark/assets/s5_4/$name").readBytes()

    /** Every server a test makes is closed after it. */
    private fun newServer(
        load: (String) -> ByteArray = { loads.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet(); asset(it) },
        startTimeoutMillis: Long = 10_000L,
        onBound: (Int) -> Unit = {}
    ) = S5FixtureServer(load, { reports.put(it) }, startTimeoutMillis, onBound).also { servers += it }

    @After fun tearDown() {
        servers.forEach { it.close() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun call(request: Request): Pair<Int, ByteArray> = client.newCall(request).execute().use { it.code to it.body!!.bytes() }

    private fun get(url: String, token: String? = null): Pair<Int, String> =
        call(Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }.build()).let { (code, body) -> code to body.decodeToString() }

    private fun port(endpoints: com.jay.fxi.di.ServerEndpoints) = endpoints.restBaseUrl.removePrefix("http://127.0.0.1:").removeSuffix("/").toInt()

    private fun refused(port: Int): Boolean =
        try {
            Socket("127.0.0.1", port).close()
            false
        } catch (expected: ConnectException) {
            true
        }

    private fun awaitRefused(port: Int) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!refused(port)) {
            check(System.nanoTime() < end) { "port $port still accepts connections" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `S5-4 4b the address is given after the bind, on loopback, and is the same on every call`() {
        val server = newServer()
        val endpoints = server.awaitEndpoints()
        assertTrue(endpoints.restBaseUrl.startsWith("http://127.0.0.1:"))
        assertEquals("ws://127.0.0.1:${port(endpoints)}/ws", endpoints.webSocketUrl)
        assertTrue("an assigned port", port(endpoints) > 0)
        assertEquals(endpoints, server.awaitEndpoints())
        assertFalse(refused(port(endpoints)))
    }

    @Test
    fun `S5-4 4b the asset bytes, each read once while starting`() {
        val base = newServer().awaitEndpoints().restBaseUrl
        val files = mapOf(
            "api/v2/graph/catalog" to "catalog.json",
            "api/v2/graph/tab?tab=tether&period=1d" to "tether-1d.json",
            "api/v2/graph/tab?tab=tether&period=1w" to "tether-1w.json",
            "api/v2/graph/tab?tab=usd&period=1d" to "usd-1d.json"
        )
        repeat(2) {
            for ((path, name) in files) {
                val (code, body) = call(Request.Builder().url(base + path).build())
                assertEquals(path, 200, code)
                assertArrayEquals(path, asset(name), body)
            }
        }
        assertEquals("each payload read once", files.values.associateWith { 1 }, loads.mapValues { it.value.get() })
    }

    @Test
    fun `S5-4 4b exact queries - expected 404s are fixed and uncounted, everything else is counted`() {
        val server = newServer()
        val base = server.awaitEndpoints().restBaseUrl
        val premium = """{"krx_visible":true,"premium_active":true}"""
        val unavailable = """{"error":"topic_unavailable"}"""
        assertEquals(200 to premium, get(base + "api/entitlements"))
        assertEquals(200 to premium, get(base + "api/entitlements?fresh_premium=true"))
        assertEquals(404 to unavailable, get(base + "api/v2/topics/snapshot?topic=fx:usd-krw"))
        assertEquals(404 to unavailable, get(base + "api/v2/free/snapshot?tab=usd&period=1d"))
        assertEquals(0, server.unexpectedCount)
        val unexpected = listOf(
            "api/entitlements?fresh_premium=false",
            "api/entitlements?fresh_premium=true&fresh_premium=true",
            "api/v2/graph/catalog?x=1",
            "api/v2/graph/tab?tab=tether",
            "api/v2/graph/tab?tab=tether&period=1d&x=1",
            "api/v2/graph/tab?tab=usd&period=1w",
            "api/v2/topics/snapshot?topic=fx:unknown",
            "api/v2/free/snapshot?tab=usd",
            "api/user/me"
        )
        for ((i, path) in unexpected.withIndex()) {
            assertEquals(path, 404, get(base + path).first)
            assertEquals(path, i + 1, server.unexpectedCount)
        }
        assertEquals(404, call(Request.Builder().url(base + "api/v2/graph/catalog").delete().build()).first)
        assertEquals("a wrong method is unexpected", unexpected.size + 1, server.unexpectedCount)
    }

    @Test
    fun `S5-4 4b headers and bodies are neither recorded nor reported`() {
        val server = newServer()
        val base = server.awaitEndpoints().restBaseUrl
        get(base + "api/v2/graph/catalog", token = "secret-token-1")
        call(
            Request.Builder().url(base + "api/notification-settings").header("Authorization", "Bearer secret-token-4")
                .post("secret-body-3".toRequestBody("application/json".toMediaType())).build()
        )
        assertEquals(1, server.unexpectedCount)
        val report = checkNotNull(reports.poll(5, TimeUnit.SECONDS))
        assertTrue(report, report.contains("POST /api/notification-settings"))
        val seen = server.recentRequests() + report + reports.toList()
        for (secret in listOf("secret-token-1", "secret-token-4", "secret-body-3", "Authorization", "Bearer")) {
            assertFalse("$secret is not kept", seen.any { secret in it })
        }
    }

    @Test
    fun `S5-4 4b the record keeps the newest 64 while the unexpected count keeps counting`() {
        val server = newServer()
        val base = server.awaitEndpoints().restBaseUrl
        repeat(70) { get(base + "api/unknown/$it") }
        assertEquals(70, server.unexpectedCount)
        val recent = server.recentRequests()
        assertEquals(64, recent.size)
        assertEquals("GET /api/unknown/6 ", recent.first())
        assertEquals("GET /api/unknown/69 ", recent.last())
    }

    private class Frames : WebSocketListener() {
        val frames = LinkedBlockingQueue<String>()
        val ended = CountDownLatch(1)
        override fun onMessage(webSocket: WebSocket, text: String) { frames.put(text) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.countDown() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            frames.put("failure: $t")
            ended.countDown()
        }
        fun next(): String = checkNotNull(frames.poll(10, TimeUnit.SECONDS)) { "no frame" }
    }

    private fun open(url: String, frames: Frames): WebSocket = client.newWebSocket(Request.Builder().url(url).build(), frames)

    /** Closes from the client and waits for the server's answer, so the server has no open connection left. */
    private fun shut(socket: WebSocket, frames: Frames) {
        socket.close(1000, null)
        assertTrue("the server answered the close", frames.ended.await(10, TimeUnit.SECONDS))
        assertFalse("closed cleanly", frames.frames.any { it.startsWith("failure") })
    }

    @Test
    fun `S5-4 4b the socket answers ping and acknowledges subscribe and unsubscribe, without recording the token`() {
        val server = newServer()
        val frames = Frames()
        val socket = open(server.awaitEndpoints().webSocketUrl, frames)
        try {
            socket.send("ping")
            assertEquals("""{"type":"pong"}""", frames.next())
            socket.send("""{"type":"subscribe","request_id":"r1","id_token":"secret-token-2","topics":["fx:usd-krw","usdt:krw"]}""")
            val ack = frames.next()
            assertTrue(ack, ack.startsWith("""{"type":"subscription_ack","request_id":"r1","operation":"subscribe""""))
            assertTrue(ack, ack.contains(""""accepted_topics":[{"topic":"fx:usd-krw"},{"topic":"usdt:krw"}]"""))
            socket.send("""{"type":"unsubscribe","request_id":"r2","topics":["usdt:krw"]}""")
            val removed = frames.next()
            assertTrue(removed, removed.contains(""""removed_topics":["usdt:krw"]""") && removed.contains(""""active_subscriptions":[{"topic":"fx:usd-krw"}]"""))
            assertFalse((server.recentRequests() + reports.toList()).any { "secret-token-2" in it })
            assertEquals(0, server.unexpectedCount)
        } finally {
            shut(socket, frames)
        }
    }

    @Test
    fun `S5-4 4b each connection keeps its own subscriptions, and a new connection starts with none`() {
        val server = newServer()
        val url = server.awaitEndpoints().webSocketUrl
        val a = Frames()
        val b = Frames()
        val first = open(url, a)
        val second = open(url, b)
        first.send("""{"type":"subscribe","request_id":"a1","topics":["fx:usd-krw"]}""")
        assertTrue(a.next().contains(""""active_subscriptions":[{"topic":"fx:usd-krw"}]"""))
        second.send("""{"type":"subscribe","request_id":"b1","topics":["usdt:krw"]}""")
        assertTrue("the other connection's topic is not in this one", b.next().contains(""""active_subscriptions":[{"topic":"usdt:krw"}]"""))
        second.send("""{"type":"unsubscribe","request_id":"b2","topics":["fx:usd-krw"]}""")
        assertTrue(b.next().contains(""""active_subscriptions":[{"topic":"usdt:krw"}]"""))
        first.send("""{"type":"subscribe","request_id":"a2","topics":[]}""")
        assertTrue("an unsubscribe elsewhere leaves this one", a.next().contains(""""active_subscriptions":[{"topic":"fx:usd-krw"}]"""))
        shut(first, a)
        shut(second, b)
        val c = Frames()
        val third = open(url, c)
        try {
            third.send("""{"type":"subscribe","request_id":"c1","topics":["krx:usd-krw-futures"]}""")
            assertTrue("a reconnect starts empty", c.next().contains(""""active_subscriptions":[{"topic":"krx:usd-krw-futures"}]"""))
        } finally {
            shut(third, c)
        }
        assertEquals(0, server.unexpectedCount)
    }

    @Test
    fun `S5-4 4b an unreadable socket frame is counted as unexpected`() {
        val server = newServer()
        val frames = Frames()
        val socket = open(server.awaitEndpoints().webSocketUrl, frames)
        try {
            socket.send("""{"type":"auth","request_id":"x1"}""")
            socket.send("not json")
            socket.send("ping")
            assertEquals("""{"type":"pong"}""", frames.next())
            assertEquals(2, server.unexpectedCount)
        } finally {
            shut(socket, frames)
        }
    }

    @Test
    fun `S5-4 4b close shuts the server and every later call fails, twice is harmless`() {
        val server = newServer()
        val port = port(server.awaitEndpoints())
        server.close()
        server.close()
        awaitRefused(port)
        try {
            server.awaitEndpoints()
            fail("no address after close")
        } catch (expected: IllegalStateException) {
            assertEquals("S5 fixture server is closed", expected.message)
        }
    }

    @Test
    fun `S5-4 4b a timed-out start is final and a later bind is shut at once`() {
        val gate = CountDownLatch(1)
        val bound = LinkedBlockingQueue<Int>()
        val slow = newServer(
            load = { name ->
                // Ignores the interrupt from the close, so the bind really happens after it.
                while (true) {
                    try {
                        gate.await()
                        break
                    } catch (ignored: InterruptedException) {
                    }
                }
                asset(name)
            },
            startTimeoutMillis = 200L,
            onBound = { bound.put(it) }
        )
        try {
            slow.awaitEndpoints()
            fail("no address while starting")
        } catch (expected: TimeoutException) {
        }
        gate.countDown()
        val port = checkNotNull(bound.poll(10, TimeUnit.SECONDS)) { "the late bind did not happen" }
        awaitRefused(port)
        try {
            slow.awaitEndpoints()
            fail("a timed-out start stays failed")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun `S5-4 4b a failed start gives no address, now or later`() {
        val broken = newServer(load = { error("missing asset $it") })
        try {
            broken.awaitEndpoints()
            fail("no address without a started server")
        } catch (expected: ExecutionException) {
        }
        try {
            broken.awaitEndpoints()
            fail("a failed start stays failed")
        } catch (expected: IllegalStateException) {
            assertEquals("S5 fixture server is closed", expected.message)
        }
    }

    @Test
    fun `S5-4 4b closed servers leave no worker thread`() {
        repeat(3) { newServer().awaitEndpoints() }
        servers.forEach { it.close() }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Thread.getAllStackTraces().keys.any { it.name == "s5-fixture-server" && it.isAlive }) {
            check(System.nanoTime() < end) { "a fixture server worker is still alive" }
            Thread.sleep(20)
        }
    }
}
