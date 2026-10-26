package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.KrxHandSnapshots
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.dto.GraphV2CatalogPeriod
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogTab
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Provenance
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2Series
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphCatalog
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.time.AppClock
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers

/**
 * S-2 (S6-2b-1b-α contract tests r1; design r5 §13 T.4 and T.14): the graph coordinator rig of TA-03. The CacheTest `Fixture`
 * (GraphV2RequestCoordinatorCacheTest.kt:260-367, a private inner class) and its `snap` (:107-140) cannot be reused, so this is a
 * minimal copy of that fixture's shape over the S-1 hand snapshots: the real [GraphV2RequestCoordinator], the real
 * [FileGraphV2DiskStore] over a temporary directory with the real JSON codec, the real [GraphV2AccessGate] and the real
 * [SnapshotTopicUseAuthority] over the same published snapshot (CacheTest :218-231 for the held store).
 *
 * **The rig's core property** (T.4): assigning [snapshot] tells the coordinator nothing. The coordinator synchronizes its
 * capability configuration only on Activate, ContextChanged, Refresh and Wake (GraphV2RequestCoordinator.kt:798, :809, :815,
 * :1949), and the rig delivers a control event only through [activate], [refresh] and [contextChanged], which count in
 * [controlEvents]. So a row can put a same-epoch hold round trip between two synchronizations and check that none happened.
 *
 * The session is S-1's: identity u1/1, user epoch "U1", token 7 ([KrxHandSnapshots.FENCE7]) and use lifetime (7, 3)
 * ([KrxHandSnapshots.LIFETIME7]). [bindNow] binds that session on the rig's own gate at the current snapshot. It is the same
 * function the coordinator's binds call (GraphV2RequestCoordinator.kt request start :1210 `bindRequestAccess`, called from `startTab`
 * :1176; seed start :868; completion configuration :1427; protectedEntry :773), on the same inputs, so a row uses it to state what
 * the capture the coordinator takes at that moment holds without reaching into the coordinator.
 *
 * No write ports are installed, as in CacheTest, so an online answer reserves no disk write.
 *
 * tests r5 (mutation battery r1 `seed_count_now`, agreed with Codex): the gate's snapshot supplier records each read in [gateReads],
 * tagged with whether it was made inside the coordinator's seed completion (`applyDiskSeed` on the call stack), and can be armed with
 * [switchAfterSeedCompletionGateRead]: right after the n-th such read returns, the rig publishes the given snapshots through [snapshot]
 * (so, by the core property, the coordinator is told nothing). This places a same-epoch round trip between the completion gate's read
 * and anything the completion reads after it, which no event-level step can reach: a round trip before the completion makes the
 * completion's own gate refuse KRX, and one after it is after the slot is stored. Limit: the trigger names one private production
 * method (`applyDiskSeed`); if it is renamed the switch never fires and the row's window assertion ('the round trip was published
 * inside the seed completion') fails — a RED naming the window, not a silent GREEN.
 */
internal class KrxGraphCoordinatorRig(test: TestScope, val root: File, initial: TopicAccessSnapshot, start: Instant = NOON) {

    companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val KEY = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val GKEY = GraphV2GeneralKey(
            KrxHandSnapshots.IDENTITY.uid, KrxHandSnapshots.USER_EPOCH, "usd", GraphPeriod.THREE_MONTHS.code
        )
        val IDS = listOf("hana.usd-krw", "krx.usd-krw-futures", "kb.usd-krw")
        const val ONLINE = "investing.usd-krw"
        const val KRX_SERIES = "krx.usd-krw-futures"
        val ALL_IDS = IDS + ONLINE
        val KRX_BUCKET = GraphV2InProgress(NOON - 10.hours, 1302.0, 1300.0, 1301.0, NOON - 1.hours)

        /** A stored answer of the three [IDS] (CacheTest :144-158). */
        fun serverTab(
            rate: Double = 1300.0,
            ids: List<String> = IDS,
            inProgress: Map<String, GraphV2InProgress> = emptyMap()
        ) = GraphV2Tab(
            "usd", GraphPeriod.THREE_MONTHS, "1d", NOON - 1.hours,
            FreeGraph("1d", ids.mapIndexed { i, id ->
                FreeGraphSeries(id, listOf(FreeGraphPoint(NOON - 1.days, rate + i, rate + i + 1, rate + i - 1, "x")), id, "krw", "KRW", 2)
            }, "2026-07-05", "2026-10-05"),
            inProgress
        )

        fun components(tab: GraphV2Tab, krx: String? = "K1", response: String = "r1"): GraphV2DiskComponents =
            (splitGraphV2ServerTab(tab, GKEY, krx, response, null) as GraphV2Validation.Valid).value

        fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

        /** Every period of usd lists every series a row uses, valid for two days (CacheTest :350-357). */
        fun catalog() = GraphV2CatalogResponse(
            tabs = listOf(GraphV2CatalogTab("usd", "usd", emptyMap(),
                listOf("1d", "1w", "3m", "1y").associateWith { GraphV2CatalogPeriod(ALL_IDS, ALL_IDS) })),
            version = "2026-05-27",
            supportedPeriods = listOf("1w", "3m", "1y"),
            cacheTtlSeconds = 172800
        )

        /** An online answer with a general and a KRX series (CacheTest :368-379). */
        fun onlineKrxDto(rate: Double) = GraphV2TabResponse(
            tab = "usd",
            period = GraphPeriod.THREE_MONTHS.code,
            series = listOf(
                GraphV2Series(ONLINE, ONLINE, "krw", "KRW", 2, listOf(GraphV2Point(NOON - 1.days, rate, "x")),
                    GraphV2Provenance(false, emptyList()), null),
                GraphV2Series(KRX_SERIES, KRX_SERIES, "krw", "KRW", 1, listOf(GraphV2Point(NOON - 1.days, rate + 5, "krx")),
                    GraphV2Provenance(false, emptyList()), null)
            ),
            metadata = GraphV2Metadata(NOON - 1.hours, "1d", GraphV2Range("2026-07-05", "2026-10-05"))
        )

        private fun hex(text: String) = text.encodeToByteArray().joinToString("") { "%02x".format(it) }

        fun generalPath(root: File, k: GraphV2GeneralKey) =
            File(root, "general/${hex(k.uid)}/${hex(k.userAccessEpoch)}/${hex(k.tab)}/${k.period}.json")

        fun krxPath(root: File, k: GraphV2KrxKey) =
            File(root, "krx/${hex(k.uid)}/${hex(k.userAccessEpoch)}/${hex(k.krxCapabilityEpoch)}/${hex(k.tab)}/${k.period}.json")

        /** The coordinator class and its seed completion method, for the gate read tag (tests r5; a private method keeps its name). */
        private val COORDINATOR_CLASS: String = GraphV2RequestCoordinator::class.java.name
        private const val SEED_COMPLETION = "applyDiskSeed"
    }

    /** The real file boundary, recording each file read (CacheTest :202-216, without fault injection). */
    class RecordingFiles : GraphV2AtomicFileIo {
        private val real = DefaultGraphV2AtomicFileIo()
        val reads = mutableListOf<File>()
        override fun read(file: File): ByteArray? {
            reads += file
            return real.read(file)
        }
        override fun prepareReplace(target: File, bytes: ByteArray): GraphV2PreparedReplace = real.prepareReplace(target, bytes)
        override fun publishReplace(prepared: GraphV2PreparedReplace) = real.publishReplace(prepared)
        override fun discardReplace(prepared: GraphV2PreparedReplace) = real.discardReplace(prepared)
        override fun enumerateFiles(root: File): List<File> = real.enumerateFiles(root)
        override fun deleteIfExists(file: File): Boolean = real.deleteIfExists(file)
    }

    /** The real store, each read result recorded and a GENERAL result held after the real read when asked (CacheTest :218-243). */
    class HeldStore(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
        val generalReads = mutableListOf<GraphV2DiskRead<GraphV2GeneralEnvelope>>()
        val krxReads = mutableListOf<GraphV2DiskRead<GraphV2KrxEnvelope>>()
        /** GENERAL read number (1-based) → what its result waits for before it is returned. */
        val hold = mutableMapOf<Int, CompletableDeferred<Unit>>()

        override suspend fun readGeneral(
            key: GraphV2GeneralKey,
            catalog: GraphCatalog?,
            admission: GraphV2IoAdmission
        ): GraphV2DiskRead<GraphV2GeneralEnvelope> {
            val result = real.readGeneral(key, catalog, admission)
            generalReads += result
            hold[generalReads.size]?.await()
            return result
        }

        override suspend fun readKrx(
            key: GraphV2KrxKey,
            catalog: GraphCatalog?,
            admission: GraphV2IoAdmission
        ): GraphV2DiskRead<GraphV2KrxEnvelope> = real.readKrx(key, catalog, admission).also { krxReads += it }

        fun release(n: Int) = hold.getValue(n).complete(Unit)
    }

    /** One tab request the fetcher received: whether its use was admitted when it went out, and its answer to complete. */
    class Sent(val key: GraphKey, val admittedAtSend: Boolean) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
    }

    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
    private val base = test.testScheduler.currentTime
    var fence: TopicSessionFence? = KrxHandSnapshots.FENCE7
    var protectedOpen = true
    private var revision = initial.revision

    /** Every snapshot the rig published, in order, the initial one first. A premise over all observed snapshots reads this. */
    val published = mutableListOf(initial)

    /** Assigning publishes [value] with the next revision. It tells the coordinator nothing (the rig's core property). */
    var snapshot: TopicAccessSnapshot = initial
        set(value) {
            revision += 1
            field = value.copy(revision = revision)
            published += field
        }

    /** Control events the rig delivered to the coordinator ([activate], [refresh], [contextChanged]). */
    var controlEvents = 0
        private set

    private val codec = JsonGraphV2EnvelopeCodec()
    val files = RecordingFiles()
    val store = HeldStore(FileGraphV2DiskStore({ root }, codec, files, StandardTestDispatcher(test.testScheduler)))
    val diagnostics = mutableListOf<GraphV2SeedDiagnostic>()
    val failures = mutableListOf<Throwable>()
    val sent = mutableListOf<Sent>()

    val uses = SnapshotTopicUseAuthority { snapshot }

    private val owners = object : GraphOwnerSource {
        override fun currentIdentity(): AuthIdentityFence? = fence?.identity
        override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
            if (currentIdentity() != expected) throw AuthIdentityChangedException()
            return AuthSnapshot(expected.uid, expected.authGeneration, "token")
        }
    }

    /**
     * One read of the gate's snapshot supplier (tests r5): its place among the gate's reads, the snapshot it returned, and whether it
     * was made inside the coordinator's seed completion (`GraphV2RequestCoordinator.applyDiskSeed` on the call stack).
     */
    class GateRead(val index: Int, val snapshot: TopicAccessSnapshot, val inSeedCompletion: Boolean)

    /** Every read of the gate's snapshot supplier, in order (the coordinator's binds and joins and [bindNow] alike). */
    val gateReads = mutableListOf<GateRead>()

    private var armedSwitch: Pair<Int, List<TopicAccessSnapshot>>? = null

    /** The [gateReads] index of the read right after which the armed switch published; null while it has not fired. */
    var switchedAfterGateRead: Int? = null
        private set

    /**
     * Arms a read-count-triggered switch (tests r5): right after the [n]-th gate read made inside a seed completion has taken its
     * snapshot, [next] is published in order through [snapshot] (which tells the coordinator nothing), so every later read of any of
     * the rig's suppliers sees the last of them. The triggering read itself returns the snapshot it took. Fires once.
     */
    fun switchAfterSeedCompletionGateRead(n: Int, vararg next: TopicAccessSnapshot) {
        require(n >= 1 && next.isNotEmpty()) { "switch needs a read number ≥ 1 and at least one snapshot" }
        armedSwitch = n to next.toList()
    }

    private fun gateSnapshot(): TopicAccessSnapshot {
        val taken = snapshot
        val inCompletion = Thread.currentThread().stackTrace.any { frame ->
            (frame.className == COORDINATOR_CLASS || frame.className.startsWith("$COORDINATOR_CLASS$")) &&
                (frame.methodName == SEED_COMPLETION || frame.methodName.startsWith("$SEED_COMPLETION$"))
        }
        gateReads += GateRead(gateReads.size, taken, inCompletion)
        val armed = armedSwitch
        if (armed != null && inCompletion && gateReads.count { it.inSeedCompletion } == armed.first) {
            armedSwitch = null
            armed.second.forEach { snapshot = it }
            switchedAfterGateRead = gateReads.size - 1
        }
        return taken
    }

    val gate = GraphV2AccessGate(
        currentIdentity = { owners.currentIdentity() },
        currentAccessFence = { fence },
        snapshot = { gateSnapshot() },
        protectedAdmission = { protectedOpen }
    )

    private val fetcher = object : GraphV2Fetching {
        override suspend fun catalog(
            owner: AuthSnapshot,
            useAdmitted: () -> Boolean
        ): AuthenticatedHttpResponse<GraphV2CatalogResponse> = ok(catalog())

        override suspend fun tab(
            owner: AuthSnapshot,
            key: GraphKey,
            useAdmitted: () -> Boolean
        ): AuthenticatedHttpResponse<GraphV2TabResponse> {
            val s = Sent(key, useAdmitted())
            sent += s
            return s.tab.await()
        }
    }

    val coordinator = GraphV2RequestCoordinator(
        fetcher = fetcher,
        owners = owners,
        currentAccessFence = { fence },
        uses = uses,
        protectedAdmission = { true },
        accessSnapshot = { snapshot },
        scope = scope,
        clock = AppClock { start + (test.testScheduler.currentTime - base).milliseconds },
        rateLimitJitter = { Duration.ZERO },
        onEventFailure = { failures += it },
        cachePorts = GraphV2CachePorts(store = store, gate = gate, onSeedDiagnostic = { diagnostics += it })
    )

    val state: GraphRequestState get() = coordinator.state.value

    fun start() = coordinator.start()

    fun activate() {
        controlEvents += 1
        coordinator.onActivated(KEY)
    }

    fun refresh(force: Boolean) {
        controlEvents += 1
        coordinator.onRefreshRequested(force)
    }

    fun contextChanged() {
        controlEvents += 1
        coordinator.onContextChanged()
    }

    /** What the holder shows (GraphV2ScreenStateHolder.kt:602 goes through this). */
    fun exposed(): GraphEntry? = coordinator.protectedEntry(KEY)

    /** The capture a coordinator bind of the S-1 session takes at the current snapshot (see the class comment). */
    fun bindNow(): GraphV2AccessCapture? = gate.bind(KrxHandSnapshots.FENCE7, KrxHandSnapshots.LIFETIME7)

    /** Tab requests whose use was admitted when they went out. */
    fun tabs(): List<Sent> = sent.filter { it.admittedAtSend }

    /** KRX file reads so far (files under `krx/`). */
    fun krxFileReads(): List<File> = files.reads.filter { it.relativeTo(root).invariantSeparatorsPath.startsWith("krx/") }

    fun put(c: GraphV2DiskComponents) {
        write(generalPath(root, c.general.key), (codec.encodeGeneral(c.general) as GraphV2Validation.Valid).value)
        c.krx?.let { write(krxPath(root, it.key), (codec.encodeKrx(it) as GraphV2Validation.Valid).value) }
    }

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    fun close() = scope.cancel()
}

/**
 * One TA-03 test: [body] opens rigs with `open(initialSnapshot)`, each on its own new root from [newRoot]; every rig opened is closed
 * when the body ends, even on failure, or a coordinator timer would keep the scheduler running (CacheTest :248-256).
 */
internal fun krxGraphRigTest(
    newRoot: () -> File,
    body: suspend TestScope.(open: (TopicAccessSnapshot) -> KrxGraphCoordinatorRig) -> Unit
): TestResult = runTest {
    val opened = mutableListOf<KrxGraphCoordinatorRig>()
    try {
        body { initial -> KrxGraphCoordinatorRig(this, newRoot(), initial).also { opened += it } }
    } finally {
        opened.forEach { it.close() }
    }
}
