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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
    ) = S5FixtureServer(load, { reports.put(it) }, testJournal(), D17Inputs.context, Fatal { code -> throw SimulatedExit(code) },
        startTimeoutMillis, onBound).also { servers += it }

    /** S6-4b D17: every server is built with recording journal/summary sinks (s6_4b_contracts.r4.md §4.2). */
    private fun testJournal() = S5PurityJournal(
        RecordingJournalIO(java.util.concurrent.CopyOnWriteArrayList()),
        S5PurityPlatform.select(33) { PurityMode.Supported(RecordingSummary(java.util.concurrent.CopyOnWriteArrayList())) { D17Inputs.identity } },
        Fatal { code -> throw SimulatedExit(code) },
        PurityProcessScope(D17Inputs.LIFETIME)
    )

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
        assertEquals(200 to S5FixtureRoutes.TOPIC_SNAPSHOTS.getValue("usdt:krw"), get(base + "api/v2/topics/snapshot?topic=usdt:krw"))
        assertEquals(404 to unavailable, get(base + "api/v2/topics/snapshot?topic=fx:usd-krw"))
        assertEquals(404 to unavailable, get(base + "api/v2/free/snapshot?tab=usd&period=1d"))
        val krx = get(base + "api/v2/topics/snapshot?topic=krx:usd-krw-futures")
        assertEquals("contract(D16):R-200/the exact KRX query is answered 200", 200, krx.first)
        assertEquals("contract(D16):R-body/the KRX REST body is the contract JSON", krxContract(), Json.parseToJsonElement(krx.second))
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
        // S6-4b D16 (s6_4b_contracts.r4.md §3.2): every non-exact KRX shape stays unexpected and counted.
        var counted = server.unexpectedCount
        for ((label, path) in listOf(
            "duplicate" to "api/v2/topics/snapshot?topic=krx:usd-krw-futures&topic=krx:usd-krw-futures",
            "extra" to "api/v2/topics/snapshot?topic=krx:usd-krw-futures&x=1"
        )) {
            assertEquals("contract(D16):R-malformed-$label/answered 404", 404, get(base + path).first)
            assertEquals("contract(D16):R-malformed-$label/counted", ++counted, server.unexpectedCount)
        }
        val post = call(Request.Builder().url(base + "api/v2/topics/snapshot?topic=krx:usd-krw-futures")
            .post("".toRequestBody("application/json".toMediaType())).build())
        assertEquals("contract(D16):R-malformed-method/answered 404", 404, post.first)
        assertEquals("contract(D16):R-malformed-method/counted", ++counted, server.unexpectedCount)
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
        /** Nothing more arrives in a short wait; frames the server sent are already queued by then. */
        fun none() = assertEquals(null, frames.poll(500, TimeUnit.MILLISECONDS))
        /** The next frame, or a failure carrying the contract message (so a missing frame fails on the responsible assertion). */
        fun nextOr(message: String): String = frames.poll(10, TimeUnit.SECONDS) ?: throw AssertionError(message)
    }

    private fun snapshot(topic: String) = S5FixtureRoutes.TOPIC_SNAPSHOTS.getValue(topic)

    /** The KRX contract JSON, read from the corpus, never from the fixture table under test. */
    private fun krxContract(): JsonElement =
        Json.parseToJsonElement(File("src/test/resources/contracts/v2/topic/snapshot-krx.json").readText())

    private val krxSubscribe = """{"type":"subscribe","request_id":"%s","topics":["krx:usd-krw-futures"]}"""

    /** (design_amendment.a1) Each ACK entry's lease fields by topic, for accepted_topics and active_subscriptions. */
    private fun ackLeases(ack: String, field: String): Map<String, Pair<String?, Long?>> =
        Json.parseToJsonElement(ack).jsonObject.getValue(field).jsonArray.associate { e ->
            val o = e.jsonObject
            o.getValue("topic").jsonPrimitive.content to
                (o["lease_id"]?.jsonPrimitive?.contentOrNull to o["lease_duration_seconds"]?.jsonPrimitive?.longOrNull)
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
            assertEquals("after the ACK, each accepted topic's snapshot once, in order", snapshot("fx:usd-krw"), frames.next())
            assertEquals(snapshot("usdt:krw"), frames.next())
            frames.none()
            socket.send("""{"type":"unsubscribe","request_id":"r2","topics":["usdt:krw"]}""")
            val removed = frames.next()
            assertTrue(removed, removed.contains(""""removed_topics":["usdt:krw"]""") && removed.contains(""""active_subscriptions":[{"topic":"fx:usd-krw"}]"""))
            frames.none()
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
        assertEquals(snapshot("fx:usd-krw"), a.next())
        second.send("""{"type":"subscribe","request_id":"b1","topics":["usdt:krw"]}""")
        assertTrue("the other connection's topic is not in this one", b.next().contains(""""active_subscriptions":[{"topic":"usdt:krw"}]"""))
        assertEquals("only this connection's new topic", snapshot("usdt:krw"), b.next())
        second.send("""{"type":"unsubscribe","request_id":"b2","topics":["fx:usd-krw"]}""")
        assertTrue(b.next().contains(""""active_subscriptions":[{"topic":"usdt:krw"}]"""))
        first.send("""{"type":"subscribe","request_id":"a2","topics":[]}""")
        assertTrue("an unsubscribe elsewhere leaves this one", a.next().contains(""""active_subscriptions":[{"topic":"fx:usd-krw"}]"""))
        a.none()
        b.none()
        shut(first, a)
        shut(second, b)
        val c = Frames()
        val third = open(url, c)
        try {
            third.send("""{"type":"subscribe","request_id":"c1","topics":["krx:usd-krw-futures"]}""")
            assertEquals("a reconnect starts with only its own KRX subscription",
                setOf("krx:usd-krw-futures"), ackLeases(c.next(), "active_subscriptions").keys)
            // S6-4b D16 (r4 §3.3, #15 revision): the new connection is sent the KRX snapshot once after its ACK, then nothing.
            val reconnectMessage = "contract(D16):W-reconnect/a new connection gets the KRX snapshot once after the ACK"
            assertEquals(reconnectMessage, krxContract(), Json.parseToJsonElement(c.nextOr(reconnectMessage)))
            c.none()
        } finally {
            shut(third, c)
        }
        assertEquals(0, server.unexpectedCount)
    }

    /** S6-4b D16 (s6_4b_contracts.r4.md §3.3): the KRX snapshot at most once per connection, after the ACK; GENERAL unchanged. */
    @Test
    fun `S6-4b D16 the KRX snapshot is sent once per connection, after the ACK, never again on that connection`() {
        val server = newServer()
        val url = server.awaitEndpoints().webSocketUrl
        val a = Frames()
        val first = open(url, a)
        try {
            first.send(krxSubscribe.format("k1"))
            val ack = a.next()
            assertTrue("contract(D16):W-ack-first/the ACK comes before the KRX snapshot: $ack", ack.startsWith("""{"type":"subscription_ack","request_id":"k1""""))
            val acceptedLease = ackLeases(ack, "accepted_topics")["krx:usd-krw-futures"]
            val activeLease = ackLeases(ack, "active_subscriptions")["krx:usd-krw-futures"]
            assertTrue("contract(D16):W-lease-accepted/the KRX accepted entry carries a lease id: $ack", acceptedLease?.first?.isNotEmpty() == true)
            assertEquals("contract(D16):W-lease-duration/the KRX lease is 660 s, the server wire value, not the 900 s cap", 660L, acceptedLease?.second)
            assertEquals("contract(D16):W-lease-active/the active KRX entry carries the same lease", acceptedLease, activeLease)
            val firstMessage = "contract(D16):W-first/the first KRX subscribe gets the contract snapshot"
            val firstSnapshot = a.nextOr(firstMessage)
            assertEquals(firstMessage, krxContract(), Json.parseToJsonElement(firstSnapshot))
            assertEquals("contract(D16):W-rest-equals-ws/the REST body and the socket snapshot are the same bytes",
                get(server.awaitEndpoints().restBaseUrl + "api/v2/topics/snapshot?topic=krx:usd-krw-futures").second, firstSnapshot)
            assertEquals("contract(D16):W-no-live/no live frame follows the KRX snapshot", null,
                a.frames.poll(500, TimeUnit.MILLISECONDS))
            first.send(krxSubscribe.format("k2"))
            val reAck = a.next()
            assertTrue(reAck.startsWith("""{"type":"subscription_ack","request_id":"k2""""))
            assertEquals("contract(D16):W-lease-same-connection/a repeated ACK on the same connection keeps the same lease id",
                acceptedLease?.first, ackLeases(reAck, "accepted_topics")["krx:usd-krw-futures"]?.first)
            val allAcks = "contract(D16):W-lease-all-acks/every ACK naming KRX carries this connection's lease on both sides"
            assertEquals("$allAcks (k2 accepted)", acceptedLease, ackLeases(reAck, "accepted_topics")["krx:usd-krw-futures"])
            assertEquals("$allAcks (k2 active)", acceptedLease, ackLeases(reAck, "active_subscriptions")["krx:usd-krw-futures"])
            assertEquals("contract(D16):W-resubscribe/a repeated subscribe on the same connection sends no second KRX snapshot", null, a.frames.poll(500, TimeUnit.MILLISECONDS))
            first.send("""{"type":"unsubscribe","request_id":"k3","topics":["krx:usd-krw-futures"]}""")
            assertTrue(a.next().startsWith("""{"type":"subscription_ack","request_id":"k3""""))
            first.send(krxSubscribe.format("k4"))
            val k4 = a.next()
            assertTrue(k4.startsWith("""{"type":"subscription_ack","request_id":"k4""""))
            assertEquals("contract(D16):W-unsubscribe-resubscribe/a resubscribe after unsubscribe on the same connection sends no KRX snapshot", null, a.frames.poll(500, TimeUnit.MILLISECONDS))
            assertEquals("$allAcks (k4 accepted)", acceptedLease, ackLeases(k4, "accepted_topics")["krx:usd-krw-futures"])
            assertEquals("$allAcks (k4 active)", acceptedLease, ackLeases(k4, "active_subscriptions")["krx:usd-krw-futures"])
            // A general-only subscribe and unsubscribe while KRX stays: the active KRX entry keeps the lease, general entries none.
            first.send("""{"type":"subscribe","request_id":"g1","topics":["fx:usd-krw"]}""")
            val g1 = a.next()
            assertTrue(g1.startsWith("""{"type":"subscription_ack","request_id":"g1""""))
            assertEquals("$allAcks (g1 active KRX)", acceptedLease, ackLeases(g1, "active_subscriptions")["krx:usd-krw-futures"])
            assertEquals("$allAcks (g1 accepted general)", null to null, ackLeases(g1, "accepted_topics").getValue("fx:usd-krw"))
            assertEquals("$allAcks (g1 active general)", null to null, ackLeases(g1, "active_subscriptions").getValue("fx:usd-krw"))
            assertEquals(snapshot("fx:usd-krw"), a.next())
            first.send("""{"type":"unsubscribe","request_id":"g2","topics":["fx:usd-krw"]}""")
            val g2 = a.next()
            assertTrue(g2.startsWith("""{"type":"subscription_ack","request_id":"g2""""))
            assertEquals("$allAcks (g2 active KRX)", acceptedLease, ackLeases(g2, "active_subscriptions")["krx:usd-krw-futures"])
            assertEquals("$allAcks (g2 active is KRX only)", setOf("krx:usd-krw-futures"), ackLeases(g2, "active_subscriptions").keys)
            a.none()
        } finally {
            shut(first, a)
        }
        assertEquals(0, server.unexpectedCount)
    }

    @Test
    fun `S6-4b D16 a duplicated KRX topic in one subscribe gets one snapshot, and each connection gets its own`() {
        val server = newServer()
        val url = server.awaitEndpoints().webSocketUrl
        val a = Frames()
        val b = Frames()
        val first = open(url, a)
        val second = open(url, b)
        try {
            first.send("""{"type":"subscribe","request_id":"d1","topics":["krx:usd-krw-futures","krx:usd-krw-futures"]}""")
            assertTrue(a.next().startsWith("""{"type":"subscription_ack","request_id":"d1""""))
            val dupMessage = "contract(D16):W-duplicate-array/the first of a repeated KRX topic gets the snapshot"
            assertEquals(dupMessage, krxContract(), Json.parseToJsonElement(a.nextOr(dupMessage)))
            assertEquals("contract(D16):W-duplicate-array/a KRX topic repeated in one subscribe is sent once", null, a.frames.poll(500, TimeUnit.MILLISECONDS))
            second.send(krxSubscribe.format("e1"))
            assertTrue(b.next().startsWith("""{"type":"subscription_ack","request_id":"e1""""))
            val indMessage = "contract(D16):W-independent/another open connection gets its own KRX snapshot"
            assertEquals(indMessage, krxContract(), Json.parseToJsonElement(b.nextOr(indMessage)))
            b.none()
            a.none()
        } finally {
            shut(first, a)
            shut(second, b)
        }
        assertEquals(0, server.unexpectedCount)
    }

    @Test
    fun `S6-4b D16 a general topic keeps its snapshot on every subscribe, including after an unsubscribe`() {
        val server = newServer()
        val frames = Frames()
        val socket = open(server.awaitEndpoints().webSocketUrl, frames)
        try {
            socket.send("""{"type":"subscribe","request_id":"g1","topics":["fx:usd-krw"]}""")
            val gAck = frames.next()
            assertTrue(gAck.startsWith("""{"type":"subscription_ack","request_id":"g1""""))
            assertEquals("contract(D16):W-lease-general-none/a general entry carries no lease", null to null,
                ackLeases(gAck, "accepted_topics").getValue("fx:usd-krw"))
            assertEquals(snapshot("fx:usd-krw"), frames.next())
            socket.send("""{"type":"subscribe","request_id":"g2","topics":["fx:usd-krw"]}""")
            assertTrue(frames.next().startsWith("""{"type":"subscription_ack","request_id":"g2""""))
            val gMessage = "contract(D16):W-general-resubscribe/a general topic is sent again on a repeated subscribe"
            assertEquals(gMessage, snapshot("fx:usd-krw"), frames.nextOr(gMessage))
            socket.send("""{"type":"unsubscribe","request_id":"g3","topics":["fx:usd-krw"]}""")
            assertTrue(frames.next().startsWith("""{"type":"subscription_ack","request_id":"g3""""))
            socket.send("""{"type":"subscribe","request_id":"g4","topics":["fx:usd-krw"]}""")
            assertTrue(frames.next().startsWith("""{"type":"subscription_ack","request_id":"g4""""))
            val g2Message = "contract(D16):W-general-unsubscribe-resubscribe/a general topic is sent again after an unsubscribe"
            assertEquals(g2Message, snapshot("fx:usd-krw"), frames.nextOr(g2Message))
            assertEquals("contract(D16):W-no-live/no live frame follows", null, frames.frames.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            shut(socket, frames)
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
