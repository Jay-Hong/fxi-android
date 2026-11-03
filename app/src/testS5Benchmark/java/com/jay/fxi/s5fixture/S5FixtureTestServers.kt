package com.jay.fxi.s5fixture

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertTrue

/** Test-only deterministic values; these are public evidence nonces, never credentials. */
internal object D17Inputs {
    const val NONCE = "00112233445566778899aabbccddeeff"
    const val LIFETIME = "102132435465768798a9bacbdcedfe0f"
    const val INSTANCE = "2031425364758697a8b9cadbecfd0e1f"
    val context = AttemptContext(AttemptState.OK, "attempt-001", NONCE,
        "12345678-1234-4234-8234-123456789abc", "a".repeat(64), "b".repeat(64))
    val identity = InstanceIdentity(LIFETIME, INSTANCE, 1234, "com.jay.fxi", 1_800_000_000_000, 0, 10123)
    val contextBytes: ByteArray get() = ("""{"schema":2,"attemptId":"attempt-001","runnerNonce":"$NONCE","bootId":"${context.bootId}","targetApkSha256":"${context.targetApkSha256}","testApkSha256":"${context.testApkSha256}"}""" + "\n").encodeToByteArray()
    fun summary(state: SummaryState, count: Int = 0, context: AttemptContext = this.context) =
        ProcessSummary(state, context.runnerNonce ?: Summary64Codec.ZERO_ID, LIFETIME, INSTANCE, count)
}

internal class SimulatedExit(val code: Int) : Error("simulated fatal($code)")
internal class SimulatedKill : Error("external kill snapshot already frozen")

internal enum class WriteFailure { ZERO, PARTIAL, WRITE, FSYNC }
internal data class IOFault(val kind: String, val failure: WriteFailure)

/** append+fsync model: partial bytes remain; fsync failure does not get a success event.
 * The model never silently truncates an existing file on exclusive create.
 */
internal class RecordingJournalIO(val events: MutableList<String>) : JournalIO {
    private val files = linkedMapOf<String, ByteArray>()
    @Volatile var fault: IOFault? = null
    @Volatile var beforeWrite: (String, ByteArray) -> Unit = { _, _ -> }
    @Volatile var afterBytes: (String, ByteArray, Boolean) -> Unit = { _, _, _ -> }
    @Volatile var afterSync: (String, ByteArray) -> Unit = { _, _ -> }
    private val registryInFlight = AtomicInteger()
    val maxRegistryInFlight = AtomicInteger()

    override fun appendAndSync(file: String, line: ByteArray) {
        if (file != "registry.jsonl") return write(file, line, exclusive = false)
        val active = registryInFlight.incrementAndGet()
        maxRegistryInFlight.updateAndGet { maxOf(it, active) }
        try { write(file, line, exclusive = false) } finally { registryInFlight.decrementAndGet() }
    }
    override fun createExclusiveAndSync(file: String, firstLine: ByteArray) = write(file, firstLine, exclusive = true)

    @Synchronized private fun write(file: String, line: ByteArray, exclusive: Boolean) {
        if (exclusive && files.containsKey(file)) throw IOException("exclusive collision")
        beforeWrite(file, line.copyOf())
        val kind = Json.parseToJsonElement(line.decodeToString().trimEnd()).jsonObject.getValue("kind").jsonPrimitive.content
        events += "write:$kind"
        val failure = fault?.takeIf { it.kind == kind }?.failure
        if (failure == WriteFailure.ZERO || failure == WriteFailure.WRITE) throw IOException("$failure:$kind")
        val payload = if (failure == WriteFailure.PARTIAL) line.copyOf(maxOf(1, line.size / 2)) else line
        files[file] = files.getOrElse(file) { byteArrayOf() } + payload
        afterBytes(file, payload.copyOf(), failure == WriteFailure.PARTIAL)
        if (failure != null) throw IOException("$failure:$kind")
        events += "sync:$kind"
        afterSync(file, line.copyOf())
    }

    @Synchronized fun snapshot(): Map<String, ByteArray> = files.mapValues { it.value.copyOf() }
    fun lines(kind: String): List<JsonObject> = snapshot().values.flatMap { raw ->
        raw.decodeToString().lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
        }.filter { it["kind"]?.jsonPrimitive?.content == kind }.toList()
    }
}

/** OS model distinguishes applying a value from normal return; no stale-IDLE fallback after success. */
internal class RecordingSummary(private val events: MutableList<String>) : ProcessSummarySink {
    @Volatile var current: ByteArray? = null
    val returned = CopyOnWriteArrayList<ProcessSummary>()
    @Volatile var beforeApply: (ProcessSummary) -> Unit = {}
    @Volatile var afterApply: (ProcessSummary) -> Unit = {}
    override fun replace(bytes: ByteArray) {
        val decoded = Summary64Codec.decode(bytes)
        events += "summary:call:${decoded.state}:${decoded.counter}"
        beforeApply(decoded)
        current = bytes.copyOf()
        events += "summary:applied:${decoded.state}:${decoded.counter}"
        afterApply(decoded)
        returned += decoded
        events += "summary:returned:${decoded.state}:${decoded.counter}"
    }
    fun decoded(): ProcessSummary? = current?.let(Summary64Codec::decode)
}

internal class D17Frames : WebSocketListener() {
    val opened = CountDownLatch(1)
    val frames = LinkedBlockingQueue<String>()
    override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
    override fun onMessage(webSocket: WebSocket, text: String) { frames.put(text) }
}

internal data class FrozenD17(val files: Map<String, ByteArray>, val summary: ProcessSummary?, val count: Int)

/** Actual Server + actual journal, only OS/file/fatal boundaries are doubles. No manually guarded request path. */
internal class D17Rig(
    val context: AttemptContext = D17Inputs.context,
    val process: PurityProcessScope = PurityProcessScope(D17Inputs.LIFETIME),
    val io: RecordingJournalIO = RecordingJournalIO(CopyOnWriteArrayList()),
    val sdk: Int = 33
) : AutoCloseable {
    val events: MutableList<String> = io.events
    val summary = RecordingSummary(events)
    val fatals = LinkedBlockingQueue<Int>()
    val diagnostics = CopyOnWriteArrayList<String>()
    val bound = AtomicInteger()
    val counter = AtomicInteger()
    @Volatile var hook: (PurityPoint) -> Unit = {}
    @Volatile var beforeFatal: (Int) -> Unit = {}
    @Volatile var fatalFailure: ((Int) -> Nothing)? = null
    val fatal = Fatal { code ->
        beforeFatal(code)
        fatals.put(code)
        events += "fatal:$code"
        fatalFailure?.invoke(code) ?: throw SimulatedExit(code)
    }
    val journal = S5PurityJournal(io,
        S5PurityPlatform.select(sdk) { PurityMode.Supported(summary) { D17Inputs.identity } },
        fatal, process, counter,
        checkpoint = { point -> events += "point:$point"; hook(point) },
        diagnostic = { diagnostics += it })
    val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
    private val sockets = mutableListOf<WebSocket>()
    private var ownedServer: S5FixtureServer? = null
    val server: S5FixtureServer get() = ownedServer ?: S5FixtureServer(
        load = { File("src/benchmark/assets/s5_4/$it").readBytes() },
        report = { events += "report:$it" }, purity = journal, attemptContext = context, fatal = fatal,
        onBound = { bound.incrementAndGet(); events += "bound" }
    ).also { ownedServer = it }

    fun start(): String = server.awaitEndpoints().restBaseUrl
    fun get(path: String): Int = client.newCall(Request.Builder().url(start() + path).build()).execute().use { it.code }
    fun socket(id: String): Pair<WebSocket, D17Frames> {
        val frames = D17Frames()
        val socket = client.newWebSocket(Request.Builder().url(server.awaitEndpoints().webSocketUrl).build(), frames)
        sockets += socket
        assertTrue("contract(D17a):$id/precondition-websocket-upgrade", frames.opened.await(3, TimeUnit.SECONDS))
        return socket to frames
    }
    fun register() = journal.registerBeforeBind(context)
    fun freeze() = FrozenD17(io.snapshot(), summary.decoded(), counter.get())
    fun localComplete(expectedCount: Int): Boolean {
        // Only a local necessary-condition oracle. C1..C8 and exit reason validation belong to Python.
        val value = summary.decoded() ?: return false
        return context.attemptState == AttemptState.OK && value.state == SummaryState.IDLE &&
            value.counter == expectedCount && counter.get() == expectedCount &&
            io.lines("register").size == 1 && io.lines("begin").size == 1 && io.lines("commit").size == expectedCount
    }
    override fun close() {
        sockets.forEach { it.cancel() }
        ownedServer?.close()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        // Do not invoke journal.close as test teardown: it is itself an L1 TODO / subject of close tests.
    }
}
