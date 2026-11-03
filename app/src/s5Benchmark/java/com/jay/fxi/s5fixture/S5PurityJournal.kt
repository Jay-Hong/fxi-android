package com.jay.fxi.s5fixture

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** D17 journal effects and wire codecs; Android-only calls stay behind the supported API boundary. */
internal fun interface ProcessSummarySink { fun replace(bytes: ByteArray) }

internal interface JournalIO {
    fun appendAndSync(file: String, line: ByteArray)
    fun createExclusiveAndSync(file: String, firstLine: ByteArray)
}

internal fun interface Fatal { fun terminate(code: Int): Nothing }

internal enum class AttemptState(val wire: String) { OK("ok"), MISSING("missing"), INVALID("invalid") }

internal data class AttemptContext(
    val attemptState: AttemptState,
    val attemptId: String?,
    val runnerNonce: String?,
    val bootId: String?,
    val targetApkSha256: String? = null,
    val testApkSha256: String? = null
) {
    companion object {
        /** Must reject duplicate/unknown keys, wrong types, nonfinite values and truncated JSON+LF.
         * Missing and invalid input normalize ALL attribution fields to null, never a partial context.
         */
        fun read(bytes: ByteArray?): AttemptContext {
            if (bytes == null) return AttemptContext(AttemptState.MISSING, null, null, null)
            return try {
                val obj = StrictJournalJson.lines(bytes).single()
                require(obj.keys == setOf("schema", "attemptId", "runnerNonce", "bootId", "targetApkSha256", "testApkSha256"))
                StrictJournalJson.integer(obj, "schema", 2, 2)
                fun text(key: String) = StrictJournalJson.text(obj, key)
                require(Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(text("attemptId")))
                require(validId(text("runnerNonce")))
                require(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(text("bootId")))
                for (key in listOf("targetApkSha256", "testApkSha256")) require(Regex("[0-9a-f]{64}").matches(text(key)))
                AttemptContext(AttemptState.OK, text("attemptId"), text("runnerNonce"), text("bootId"), text("targetApkSha256"), text("testApkSha256"))
            } catch (_: Exception) { AttemptContext(AttemptState.INVALID, null, null, null) }
        }
    }
}

private fun validId(value: String): Boolean = Regex("[0-9a-f]{32}").matches(value) && value != Summary64Codec.ZERO_ID

private object StrictJournalJson {
    fun text(obj: JsonObject, key: String): String {
        val value = obj[key] as? JsonPrimitive ?: error("missing string")
        require(value.isString)
        return value.content
    }
    fun integer(obj: JsonObject, key: String, minimum: Long = 0, maximum: Long = Int.MAX_VALUE.toLong()): Long {
        val value = obj[key] as? JsonPrimitive ?: error("missing integer")
        require(!value.isString && Regex("0|[1-9][0-9]*").matches(value.content))
        return value.content.toLong().also { require(it in minimum..maximum) }
    }
    fun lines(bytes: ByteArray): List<JsonObject> {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        require(text.endsWith("\n") && !text.endsWith("\n\n"))
        return text.dropLast(1).split('\n').map { line ->
            require(line.isNotBlank())
            val keys = ArrayDeque<MutableSet<String>>()
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '{' -> keys.addLast(mutableSetOf())
                    '}' -> keys.removeLast()
                    '"' -> {
                        val start = i++
                        while (i < line.length) {
                            if (line[i] == '\\') i += 2
                            else if (line[i++] == '"') break
                        }
                        val end = i
                        while (i < line.length && line[i].isWhitespace()) i++
                        if (i < line.length && line[i] == ':') {
                            val key = Json.parseToJsonElement(line.substring(start, end)).jsonPrimitive.content
                            require(keys.last().add(key))
                        }
                        continue
                    }
                }
                i++
            }
            require(keys.isEmpty())
            Json.parseToJsonElement(line) as? JsonObject ?: error("object required")
        }
    }
}

/** Uses O_EXCL/O_APPEND without truncation and fsyncs every written record. */
internal class AndroidJournalIO(private val root: File) : JournalIO {
    private fun write(name: String, bytes: ByteArray, exclusive: Boolean) {
        require(name == "registry.jsonl" || Regex("instance-[0-9a-f]{32}\\.jsonl").matches(name))
        Files.createDirectories(root.toPath())
        require(!Files.isSymbolicLink(root.toPath()))
        val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW or
            (if (exclusive) OsConstants.O_EXCL else OsConstants.O_APPEND)
        val fd = Os.open(File(root, name).path, flags, 384)
        try {
            var offset = 0
            while (offset < bytes.size) {
                val n = Os.write(fd, bytes, offset, bytes.size - offset)
                check(n > 0)
                offset += n
            }
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
    override fun appendAndSync(file: String, line: ByteArray) = write(file, line, false)
    override fun createExclusiveAndSync(file: String, firstLine: ByteArray) = write(file, firstLine, true)
}

/** Probe `absent`: only a real absence is false; any other lookup failure propagates (r4 §4.5). */
internal fun purityRootExists(root: java.nio.file.Path): Boolean = try {
    Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    true
} catch (_: java.nio.file.NoSuchFileException) { false }

/** attempt.json: null only when absent; any other lookup or read failure is non-null INVALID bytes (r4 §4.2). */
internal fun readAttemptContextBytes(path: java.nio.file.Path): ByteArray? {
    return try {
        val attributes = try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) { return null }
        if (attributes.isRegularFile) Files.readAllBytes(path) else byteArrayOf()
    } catch (_: Exception) {
        // Non-null empty bytes are deliberately INVALID, never a missing or partial context.
        byteArrayOf()
    }
}

internal fun newPurityId(): String {
    val random = SecureRandom()
    while (true) {
        val id = ByteArray(16).also(random::nextBytes).losslessHex()
        if (id != Summary64Codec.ZERO_ID) return id
    }
}

internal data class InstanceIdentity(
    val lifetimeId: String,
    val instanceId: String,
    val pid: Int,
    val processName: String,
    val processStartWallMs: Long,
    val userId: Int,
    val packageUid: Int
)

internal enum class SummaryState(val wire: Int) { IDLE(0), BUSY(1), POISON(2) }

internal data class ProcessSummary(
    val state: SummaryState,
    val runnerNonce: String,
    val lifetimeId: String,
    val instanceId: String,
    val counter: Int
)

/** Pure r4 §4.3 codec. Zero nonce is diagnostic only; identity is always nonzero. */
internal object Summary64Codec {
    private val magic = "FXID17R1".encodeToByteArray()
    const val ZERO_ID = "00000000000000000000000000000000"

    fun encode(value: ProcessSummary): ByteArray {
        require(value.counter >= 0)
        val nonce = decodeId(value.runnerNonce, allowZero = true)
        val lifetime = decodeId(value.lifetimeId)
        val instance = decodeId(value.instanceId)
        return ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN).apply {
            put(magic); put(1.toByte()); put(value.state.wire.toByte()); putShort(0.toShort())
            put(nonce); put(lifetime); put(instance); putInt(value.counter)
        }.array()
    }

    fun decode(bytes: ByteArray): ProcessSummary {
        require(bytes.size == 64)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val header = ByteArray(8).also { buffer.get(it) }
        require(header.contentEquals(magic))
        require(buffer.get().toInt() == 1)
        val stateCode = buffer.get().toInt()
        val state = SummaryState.entries.singleOrNull { it.wire == stateCode }
        requireNotNull(state)
        require(buffer.short.toInt() == 0)
        fun id() = ByteArray(16).also { buffer.get(it) }.losslessHex()
        val nonce = id()
        val lifetime = id()
        val instance = id()
        require(lifetime != ZERO_ID && instance != ZERO_ID)
        val counter = buffer.int
        require(counter >= 0) // rejects unsigned 2147483648..4294967295
        return ProcessSummary(state, nonce, lifetime, instance, counter)
    }

    private fun decodeId(value: String, allowZero: Boolean = false): ByteArray {
        require(Regex("[0-9a-f]{32}").matches(value))
        require(allowZero || value != ZERO_ID)
        return ByteArray(16) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

internal fun ByteArray.losslessHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

/** Pure writer: the bridge uses these bytes, never Python-authored JSON masquerading as Kotlin output. */
internal object JournalLineCodec {
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun bytes(value: JsonObject) = (value.toString() + "\n").encodeToByteArray()

    fun registration(kind: String, identity: InstanceIdentity, context: AttemptContext): ByteArray {
        require(kind == "register" || kind == "begin")
        return bytes(buildJsonObject {
            put("kind", kind); put("schema", 2)
            put("instanceId", identity.instanceId); put("lifetimeId", identity.lifetimeId)
            put("attemptId", nullable(context.attemptId)); put("runnerNonce", nullable(context.runnerNonce))
            put("attemptState", context.attemptState.wire)
            put("pid", identity.pid); put("processName", identity.processName)
            put("processStartWallMs", identity.processStartWallMs)
            put("userId", identity.userId); put("packageUid", identity.packageUid)
            put("bootId", nullable(context.bootId))
        })
    }

    fun transaction(kind: String, identity: InstanceIdentity, context: AttemptContext, seq: Int, line: String = ""): ByteArray {
        require(kind == "prepare" || kind == "commit")
        require(seq in 1..Int.MAX_VALUE)
        return bytes(buildJsonObject {
            put("kind", kind); put("schema", 2); put("instanceId", identity.instanceId)
            put("attemptId", nullable(context.attemptId)); put("runnerNonce", nullable(context.runnerNonce))
            put("seq", seq)
            if (kind == "prepare") put("line", line)
        })
    }

    /** Strict JSONL/schema/sequence validation belongs to the journal, not Json's lenient duplicate handling. */
    fun readInstance(bytes: ByteArray): List<JsonObject> {
        val rows = StrictJournalJson.lines(bytes)
        val begin = rows.first()
        val registrationKeys = setOf("kind", "schema", "instanceId", "lifetimeId", "attemptId", "runnerNonce", "attemptState", "pid", "processName", "processStartWallMs", "userId", "packageUid", "bootId")
        require(begin.keys == registrationKeys && StrictJournalJson.text(begin, "kind") == "begin")
        require(validId(StrictJournalJson.text(begin, "instanceId")) && validId(StrictJournalJson.text(begin, "lifetimeId")))
        for (key in listOf("pid", "userId", "packageUid")) StrictJournalJson.integer(begin, key)
        StrictJournalJson.integer(begin, "processStartWallMs", maximum = Long.MAX_VALUE)
        require(StrictJournalJson.text(begin, "processName").isNotEmpty())
        require(StrictJournalJson.text(begin, "attemptState") in setOf("ok", "missing", "invalid"))
        require((rows.size - 1) % 2 == 0)
        for ((index, row) in rows.withIndex()) {
            StrictJournalJson.integer(row, "schema", 2, 2)
            if (index == 0) continue
            val kind = if (index % 2 == 1) "prepare" else "commit"
            val keys = setOf("kind", "schema", "instanceId", "attemptId", "runnerNonce", "seq") + if (kind == "prepare") setOf("line") else emptySet()
            require(row.keys == keys && StrictJournalJson.text(row, "kind") == kind)
            StrictJournalJson.integer(row, "seq", ((index + 1) / 2).toLong(), ((index + 1) / 2).toLong())
            for (key in listOf("instanceId", "attemptId", "runnerNonce")) require(row[key] == begin[key])
            if (kind == "prepare") StrictJournalJson.text(row, "line")
        }
        return rows
    }
}

/** Pure admission transition contract; OS BUSY must have returned before admitted() may be called. */
internal data class AdmissionState(val active: Int = 0, val poisoned: Boolean = false, val closed: Boolean = false) {
    init { require(active >= 0) }
    fun admitted(): AdmissionState {
        check(!poisoned && !closed && active < Int.MAX_VALUE)
        return copy(active = active + 1)
    }
    fun succeeded(): AdmissionState {
        check(active > 0 && !poisoned)
        return copy(active = active - 1)
    }
    fun failed(): AdmissionState = copy(poisoned = true, closed = true)
    fun close(): AdmissionState = if (active > 0) failed() else copy(closed = true)
    val mayPublishIdle: Boolean get() = active == 0 && !poisoned && !closed
}

/** Checkpoints are synchronous fault/barrier seams, not another writer or a replacement for Server traffic. */
internal enum class PurityPoint {
    REGISTERED, BEGIN_SYNCED, CLASSIFIED_UNEXPECTED, PREPARE_STARTED, PREPARE_PARTIAL,
    PREPARE_SYNCED, BEFORE_COUNTER, AFTER_COUNTER, COMMIT_STARTED, COMMIT_PARTIAL,
    COMMIT_SYNCED, BEFORE_LEAVE, AFTER_LEAVE, POISONED, BEFORE_FATAL,
    HTTP_URL, HTTP_RESPONSE, WS_PARSE, WS_PING, WS_SEND
}

/** Shared by journals in ONE process: one lifetime, registry lock, summary/admission ownership.
 * A second instance in this lifetime remains collector-incomplete; it is never silently reset.
 */
internal class PurityProcessScope(val lifetimeId: String) {
    val startupLock = Any()
    val registryLock = Any()
    val admissionLock = Any()
}

internal sealed interface PurityMode {
    data class UnsupportedApi(val sdk: Int) : PurityMode
    data class Supported(val sink: ProcessSummarySink, val identity: () -> InstanceIdentity) : PurityMode
}

/** Injectable Android boundary. Host API tests forbid invoking createApi30 on unsupported SDKs. */
internal interface S5PurityRuntime {
    val sdk: Int
    val io: JournalIO
    val process: PurityProcessScope
    val contextBytes: ByteArray?
    fun createApi30(): PurityMode.Supported
    fun diagnostic(line: String)
}

internal class S5PurityJournal(
    val io: JournalIO,
    val mode: PurityMode,
    val fatal: Fatal,
    val process: PurityProcessScope,
    val counter: AtomicInteger = AtomicInteger(0),
    val checkpoint: (PurityPoint) -> Unit = {},
    val diagnostic: (String) -> Unit = {}
) : AutoCloseable {
    private var state = AdmissionState()
    private var identity: InstanceIdentity? = null
    private var context: AttemptContext? = null
    private var unsupportedReported = false
    private val transactionLock = Any()
    private val admittedHere = ThreadLocal.withInitial { 0 }

    private fun publish(value: SummaryState) {
        val supported = mode as PurityMode.Supported
        val who = checkNotNull(identity)
        supported.sink.replace(Summary64Codec.encode(ProcessSummary(value, context?.runnerNonce ?: Summary64Codec.ZERO_ID,
            who.lifetimeId, who.instanceId, counter.get())))
    }

    private fun poison() {
        if (state.poisoned) return
        state = state.failed()
        checkpoint(PurityPoint.POISONED)
        try { if (identity != null && mode is PurityMode.Supported) publish(SummaryState.POISON) }
        catch (_: Exception) { /* One best-effort POISON; the previously returned BUSY remains the witness. */ }
    }

    private fun die(code: Int): Nothing {
        synchronized(process.admissionLock) { poison() }
        checkpoint(PurityPoint.BEFORE_FATAL)
        fatal.terminate(code)
    }

    fun registerBeforeBind(context: AttemptContext): InstanceIdentity = synchronized(process.startupLock) {
        if (mode is PurityMode.UnsupportedApi) {
            if (!unsupportedReported) { diagnostic("D17_UNSUPPORTED_API"); unsupportedReported = true }
            return@synchronized InstanceIdentity(process.lifetimeId, Summary64Codec.ZERO_ID, 0, "", 0, 0, 0)
        }
        synchronized(process.admissionLock) {
            try {
                check(identity == null && !state.closed && counter.get() == 0 && state.active == 0)
                this.context = context
                identity = (mode as PurityMode.Supported).identity()
                val who = checkNotNull(identity)
                require(who.lifetimeId == process.lifetimeId && validId(who.instanceId) && validId(who.lifetimeId))
                publish(SummaryState.BUSY)
                synchronized(process.registryLock) { io.appendAndSync("registry.jsonl", JournalLineCodec.registration("register", who, context)) }
                checkpoint(PurityPoint.REGISTERED)
                io.createExclusiveAndSync("instance-${who.instanceId}.jsonl", JournalLineCodec.registration("begin", who, context))
                checkpoint(PurityPoint.BEGIN_SYNCED)
                publish(SummaryState.IDLE)
                who
            } catch (error: Exception) { die(87) }
        }
    }

    fun <T> classifiedCallback(block: () -> T): T {
        if (mode is PurityMode.UnsupportedApi) return block()
        synchronized(process.admissionLock) {
            if (state.poisoned || state.closed) die(86)
            checkNotNull(identity) { "registration required" }
            if (state.active == Int.MAX_VALUE) die(86)
            if (state.active == 0) {
                try { publish(SummaryState.BUSY) } catch (error: Exception) {
                    try { diagnostic("D17_SUMMARY_FAILURE admission ${error.javaClass.simpleName} fatal(88)") }
                    catch (_: Exception) { /* Diagnostics cannot prevent poison/fatal. */ }
                    die(88)
                }
            }
            state = state.admitted()
        }
        admittedHere.set(checkNotNull(admittedHere.get()) + 1)
        try {
            val result = try { block() } catch (error: Exception) { die(86) }
            checkpoint(PurityPoint.BEFORE_LEAVE)
            synchronized(process.admissionLock) {
                if (state.poisoned || state.closed) die(86)
                state = state.succeeded()
                if (state.mayPublishIdle) {
                    try { publish(SummaryState.IDLE) } catch (error: Exception) { die(86) }
                }
            }
            checkpoint(PurityPoint.AFTER_LEAVE)
            return result
        } finally { admittedHere.set(checkNotNull(admittedHere.get()) - 1) }
    }

    fun recordUnexpected(line: String) {
        if (mode is PurityMode.UnsupportedApi) { counter.incrementAndGet(); return }
        check(checkNotNull(admittedHere.get()) > 0) { "Unexpected outside admitted callback" }
        synchronized(transactionLock) {
            try {
                synchronized(process.admissionLock) { check(!state.poisoned && !state.closed) }
                checkpoint(PurityPoint.CLASSIFIED_UNEXPECTED)
                val current = counter.get()
                check(current < Int.MAX_VALUE)
                val next = current + 1
                val who = checkNotNull(identity)
                val ctx = checkNotNull(context)
                val file = "instance-${who.instanceId}.jsonl"
                checkpoint(PurityPoint.PREPARE_STARTED)
                io.appendAndSync(file, JournalLineCodec.transaction("prepare", who, ctx, next, line))
                checkpoint(PurityPoint.PREPARE_SYNCED)
                checkpoint(PurityPoint.BEFORE_COUNTER)
                check(counter.incrementAndGet() == next)
                checkpoint(PurityPoint.AFTER_COUNTER)
                checkpoint(PurityPoint.COMMIT_STARTED)
                io.appendAndSync(file, JournalLineCodec.transaction("commit", who, ctx, next))
                checkpoint(PurityPoint.COMMIT_SYNCED)
            } catch (error: Exception) { die(86) }
        }
    }
    fun snapshot(): AdmissionState = synchronized(process.admissionLock) { state }
    override fun close() = synchronized(process.admissionLock) {
        if (state.active > 0) poison() else state = state.close()
    }

    /** Boundary-only seam; no preceding journal history is invented. */
    internal fun seedForTest(counter: Int, active: Int = 0) = synchronized(process.admissionLock) {
        require(counter >= 0 && active >= 0)
        check(identity != null && !state.closed && state.active == 0)
        this.counter.set(counter)
        state = AdmissionState(active)
        publish(if (active == 0) SummaryState.IDLE else SummaryState.BUSY)
    }
}

/** Runtime adapter contract. createApi30 is lazy: SDK <30 may not even load its implementation. */
internal object S5PurityPlatform {
    fun select(sdk: Int, createApi30: () -> PurityMode.Supported): PurityMode =
        if (sdk < 30) PurityMode.UnsupportedApi(sdk) else createApi30()

    /** All API-30-only method bodies and hidden-API reflection stay in this nested adapter. */
    internal interface IdentityCalls {
        fun applicationProcessName(): String
        fun processMyProcessName33(): String
        fun pid(): Int
        fun uid(): Int
        fun userId(uid: Int): Int
        fun processStartWallMs(): Long
        fun newInstanceId(): String
    }

    internal object AndroidIdentityCalls : IdentityCalls {
        override fun applicationProcessName(): String = Application.getProcessName()
        override fun processMyProcessName33(): String = error("API33 process name is not used")
        override fun pid(): Int = Process.myPid()
        override fun uid(): Int = Process.myUid()
        override fun userId(uid: Int): Int {
            // getIdentifier is @SystemApi in the public SDK. Read the public UserHandle parcel instead.
            val parcel = android.os.Parcel.obtain()
            return try {
                UserHandle.getUserHandleForUid(uid).writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                parcel.readInt()
            } finally { parcel.recycle() }
        }
        override fun processStartWallMs(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime() + Process.getStartElapsedRealtime()
        override fun newInstanceId(): String = newPurityId()
    }

    internal class Api30Impl(
        private val activityManager: ActivityManager?,
        private val identityCalls: IdentityCalls = AndroidIdentityCalls
    ) : ProcessSummarySink {
        override fun replace(bytes: ByteArray) {
            check(Build.VERSION.SDK_INT >= 30)
            require(bytes.size == 64)
            activityManager!!.setProcessStateSummary(bytes)
        }
        // API 30/32: Application.getProcessName(), NEVER Process.myProcessName().
        fun identity(process: PurityProcessScope): InstanceIdentity {
            val uid = identityCalls.uid()
            return InstanceIdentity(process.lifetimeId, identityCalls.newInstanceId(), identityCalls.pid(),
                identityCalls.applicationProcessName(), identityCalls.processStartWallMs(), identityCalls.userId(uid), uid)
        }
        fun historicalExits(userId: Int): List<ProbeExitRecord> {
            check(Build.VERSION.SDK_INT >= 30 && Process.myUid() == 2000)
            val service = ActivityManager::class.java.getDeclaredMethod("getService").invoke(null)
            val iface = Class.forName("android.app.IActivityManager")
            val slice = iface.getMethod("getHistoricalProcessExitReasons", String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(service, S5PurityProbe.PACKAGE, 0, 0, userId)
            val records = Class.forName("android.content.pm.ParceledListSlice").getMethod("getList").invoke(slice) as List<*>
            return records.map { item ->
                val record = item as ApplicationExitInfo
                check(record.packageUid != Process.myUid())
                ProbeExitRecord(record.pid, record.processName, record.realUid, record.packageUid, record.definingUid,
                    record.timestamp, record.reason, record.status, record.processStateSummary)
            }
        }
    }
}

internal data class ProbeExitRecord(
    val pid: Int, val processName: String, val realUid: Int, val packageUid: Int,
    val definingUid: Int, val timestampMs: Long, val reason: Int, val status: Int,
    val summary: ByteArray?
)

/** Lossless getter serialization only; never inserts identity from the registry. */
internal object ProbeExitCodec {
    fun encode(record: ProbeExitRecord): JsonObject = buildJsonObject {
        put("pid", record.pid); put("processName", record.processName); put("realUid", record.realUid)
        put("packageUid", record.packageUid); put("definingUid", record.definingUid)
        put("timestampMs", record.timestampMs); put("reason", record.reason); put("status", record.status)
        put("summaryHex", record.summary?.losslessHex()?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal interface ProbeRuntime {
    val sdk: Int
    fun unsupported(): Nothing
    fun runApi30(args: Array<String>): Unit
}

@Keep
object S5PurityProbe {
    const val PACKAGE = "com.jay.fxi"
    const val ROOT = "/sdcard/Android/data/com.jay.fxi/files/s5purity/"

    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        run(args, object : ProbeRuntime {
            override val sdk: Int get() = Build.VERSION.SDK_INT
            override fun unsupported(): Nothing {
                System.err.println("D17_UNSUPPORTED_API")
                Runtime.getRuntime().halt(64)
                error("halt returned")
            }
            override fun runApi30(args: Array<String>) = ProbeApi30.execute(args)
        })
    }

    private object ProbeApi30 {
        fun execute(args: Array<String>) {
            require(args.size == 5)
            val (op, user, attempt, nonce, commandNonce) = args.toList()
            require(op in setOf("absent", "inventory", "exits") && Regex("0|[1-9][0-9]*").matches(user))
            val userId = user.toInt()
            require(Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(attempt) && validId(nonce) && Regex("[A-Za-z0-9._-]{1,128}").matches(commandNonce))
            check(Process.myUid() == 2000)
            val root = File(ROOT).toPath()
            val payload = when (op) {
                "absent" -> buildJsonObject { put("exists", purityRootExists(root)) }
                "inventory" -> {
                    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
                    val files = Files.newDirectoryStream(root).use { stream -> stream.toList().sortedBy { it.fileName.toString() }.map { path ->
                        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                        val data = Files.readAllBytes(path)
                        buildJsonObject { put("name", path.fileName.toString()); put("type", "file"); put("size", data.size)
                            put("sha256", MessageDigest.getInstance("SHA-256").digest(data).losslessHex()) }
                    } }
                    buildJsonObject { put("files", JsonArray(files)) }
                }
                else -> buildJsonObject { put("records", JsonArray(S5PurityPlatform.Api30Impl(null).historicalExits(userId).map(ProbeExitCodec::encode))) }
            }
            println(buildJsonObject {
                put("schema", 2); put("op", op); put("attemptId", attempt); put("runnerNonce", nonce)
                put("commandNonce", commandNonce); put("collectorUid", Process.myUid()); put("userId", userId); put("payload", payload)
            })
        }
    }

    /** main must delegate here before op dispatch, API30 loading/reflection or target initialization. */
    internal fun run(args: Array<String>, runtime: ProbeRuntime) {
        if (runtime.sdk < 30) runtime.unsupported()
        runtime.runApi30(args)
    }
}
