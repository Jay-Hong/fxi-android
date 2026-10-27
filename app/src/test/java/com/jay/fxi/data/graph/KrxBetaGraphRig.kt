package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessBlock
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantContext
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
import com.jay.fxi.data.remote.TopicUseLifetime
import com.jay.fxi.data.remote.dto.GraphV2CatalogPeriod
import com.jay.fxi.data.remote.dto.GraphV2CatalogResponse
import com.jay.fxi.data.remote.dto.GraphV2CatalogTab
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2InProgressSeed
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
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.time.AppClock
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import com.jay.fxi.data.auth.AccessOrderSequence
import com.jay.fxi.data.entitlements.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame

import com.jay.fxi.data.graph.KrxBetaGraphData.NOON
import com.jay.fxi.data.graph.KrxBetaGraphData.KEY
import com.jay.fxi.data.graph.KrxBetaGraphData.OTHER
import com.jay.fxi.data.graph.KrxBetaGraphData.JPY
import com.jay.fxi.data.graph.KrxBetaGraphData.ONLINE
import com.jay.fxi.data.graph.KrxBetaGraphData.KRX_SERIES
import com.jay.fxi.data.graph.KrxBetaGraphData.RecordingFiles
import com.jay.fxi.data.graph.KrxBetaGraphData.HeldStore
import com.jay.fxi.data.graph.KrxBetaGraphData.Sent
import com.jay.fxi.data.graph.KrxBetaGraphData.ok
import com.jay.fxi.data.graph.KrxBetaGraphData.catalog
import com.jay.fxi.data.graph.KrxBetaGraphData.onlineDto
import com.jay.fxi.data.graph.KrxBetaGraphData.codec
import com.jay.fxi.data.graph.KrxBetaGraphData.components
import com.jay.fxi.data.graph.KrxBetaGraphData.joined
import com.jay.fxi.data.graph.KrxBetaGraphData.serverTab
import com.jay.fxi.data.graph.KrxBetaGraphData.put
import com.jay.fxi.data.graph.KrxBetaGraphData.putGeneral
import com.jay.fxi.data.graph.KrxBetaGraphData.putKrx

/** β r2 §13. Real issuer snapshots are read directly, never copied or synthesized.
 * S-1 mode is explicit for R04/R14. Publishing alone delivers no coordinator event.
 * Disk, gate, use authority, seed and optional write paths are real; transport answers are parked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object KrxBetaGraphData {
    val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
    val KEY = GraphKey("usd", GraphPeriod.THREE_MONTHS)
    val OTHER = GraphKey("usd", GraphPeriod.ONE_YEAR)
    val JPY = GraphKey("jpy", GraphPeriod.THREE_MONTHS)
    val IDS = listOf("hana.usd-krw", "krx.usd-krw-futures", "kb.usd-krw")
    const val ONLINE = "investing.usd-krw"
    const val KRX_SERIES = "krx.usd-krw-futures"
    val ALL_IDS = IDS + ONLINE
    fun serverTab(
        rate: Double = 1300.0,
        ids: List<String> = IDS,
        fetchedAt: Instant = NOON - 1.hours
    ) = GraphV2Tab(
        "usd", GraphPeriod.THREE_MONTHS, "1d", fetchedAt,
        FreeGraph("1d", ids.mapIndexed { i, id ->
            FreeGraphSeries(id, listOf(FreeGraphPoint(NOON - 1.days, rate + i, rate + i + 1, rate + i - 1, "x")), id, "krw", "KRW", 2)
        }, "2026-07-05", "2026-10-05"),
        emptyMap()
    )

    fun components(tab: GraphV2Tab, key: GraphV2GeneralKey, krx: String?, response: String = "r1") =
        (splitGraphV2ServerTab(tab, key, krx, response, null) as GraphV2Validation.Valid).value

    fun joined(c: GraphV2DiskComponents, withKrx: Boolean = true) =
        joinGraphV2Components(c.general, if (withKrx) c.krx else null).tab

    val codec = JsonGraphV2EnvelopeCodec()

    fun hex(text: String) = text.encodeToByteArray().joinToString("") { "%02x".format(it) }

    fun generalPath(root: File, k: GraphV2GeneralKey) =
        File(root, "general/${hex(k.uid)}/${hex(k.userAccessEpoch)}/${hex(k.tab)}/${k.period}.json")

    fun krxPath(root: File, k: GraphV2KrxKey) =
        File(root, "krx/${hex(k.uid)}/${hex(k.userAccessEpoch)}/${hex(k.krxCapabilityEpoch)}/${hex(k.tab)}/${k.period}.json")

    fun write(file: File, bytes: ByteArray) {
        checkNotNull(file.parentFile).mkdirs()
        file.writeBytes(bytes)
    }

    fun KrxBetaGraphRig.putGeneral(g: GraphV2GeneralEnvelope) =
        write(generalPath(root, g.key), (codec.encodeGeneral(g) as GraphV2Validation.Valid).value)

    fun KrxBetaGraphRig.putKrx(k: GraphV2KrxEnvelope) =
        write(krxPath(root, k.key), (codec.encodeKrx(k) as GraphV2Validation.Valid).value)

    fun KrxBetaGraphRig.put(c: GraphV2DiskComponents) {
        putGeneral(c.general)
        c.krx?.let { putKrx(it) }
    }

    fun isKrx(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath.startsWith("krx/")

    class RecordingFiles : GraphV2AtomicFileIo {
        private val real = DefaultGraphV2AtomicFileIo()
        val reads = mutableListOf<File>()
        val prepares = mutableListOf<File>()
        var failRead: (File) -> Boolean = { false }
        override fun read(file: File): ByteArray? {
            reads += file
            if (failRead(file)) throw IOException("read refused")
            return real.read(file)
        }
        override fun prepareReplace(target: File, bytes: ByteArray): GraphV2PreparedReplace {
            prepares += target
            return real.prepareReplace(target, bytes)
        }
        override fun publishReplace(prepared: GraphV2PreparedReplace) = real.publishReplace(prepared)
        override fun discardReplace(prepared: GraphV2PreparedReplace) = real.discardReplace(prepared)
        override fun enumerateFiles(root: File): List<File> = real.enumerateFiles(root)
        override fun deleteIfExists(file: File): Boolean = real.deleteIfExists(file)
    }

    /** The real store, with each read result and key recorded and a GENERAL result held after the real read when asked. */
    class HeldStore(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
        val generalReads = mutableListOf<GraphV2DiskRead<GraphV2GeneralEnvelope>>()
        val generalKeys = mutableListOf<GraphV2GeneralKey>()
        val krxReads = mutableListOf<GraphV2DiskRead<GraphV2KrxEnvelope>>()
        /** GENERAL read number (1-based) → what its result waits for before it is returned. */
        val hold = mutableMapOf<Int, CompletableDeferred<Unit>>()
        /** KRX read number (1-based) → what its result waits for before it is returned. */
        val krxHold = mutableMapOf<Int, CompletableDeferred<Unit>>()

        override suspend fun readGeneral(
            key: GraphV2GeneralKey,
            catalog: GraphCatalog?,
            admission: GraphV2IoAdmission
        ): GraphV2DiskRead<GraphV2GeneralEnvelope> {
            val result = real.readGeneral(key, catalog, admission)
            generalReads += result
            generalKeys += key
            hold[generalReads.size]?.await()
            return result
        }

        override suspend fun readKrx(
            key: GraphV2KrxKey,
            catalog: GraphCatalog?,
            admission: GraphV2IoAdmission
        ): GraphV2DiskRead<GraphV2KrxEnvelope> {
            val result = real.readKrx(key, catalog, admission)
            krxReads += result
            krxHold[krxReads.size]?.await()
            return result
        }

        val reservations = mutableListOf<GraphV2WriteReservation>()
        val withdrawals = mutableListOf<GraphV2WriteTicket>()
        val cancellations = mutableListOf<GraphV2WriteTicket>()
        val writes = mutableListOf<GraphV2WriteReport>()
        override fun reserveWrite(components: GraphV2DiskComponents): GraphV2WriteReservation =
            real.reserveWrite(components).also { reservations += it }
        override fun withdrawKrx(ticket: GraphV2WriteTicket): Boolean = real.withdrawKrx(ticket).also { withdrawals += ticket }
        override fun cancelWrite(ticket: GraphV2WriteTicket) { cancellations += ticket; real.cancelWrite(ticket) }
        override suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport =
            real.write(ticket, admission).also { writes += it }
        fun release(n: Int) = hold.getValue(n).complete(Unit)
        fun releaseKrx(n: Int) = krxHold.getValue(n).complete(Unit)
    }

    class Sent(val key: GraphKey, val admittedAtSend: Boolean, val atMs: Long) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
    }

    fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    fun <T> status(code: Int, retryAfter: String? = null): AuthenticatedHttpResponse<T> {
        val headers = if (retryAfter == null) Headers.headersOf() else Headers.headersOf("Retry-After", retryAfter)
        return AuthenticatedHttpResponse(code, headers, null,
            AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP), byteArrayOf(1))
    }

    fun catalog(tabs: List<String> = listOf("usd")) = GraphV2CatalogResponse(
        tabs = tabs.map { tab -> GraphV2CatalogTab(tab, tab, emptyMap(),
            listOf("1d", "1w", "3m", "1y").associateWith { GraphV2CatalogPeriod(ALL_IDS, ALL_IDS) }) },
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = 172800
    )

    /**
     * An online answer with a general and a KRX series; [krx] false leaves the KRX series out, [empty] every series, and
     * [inProgress] adds a current bucket to each series it keeps.
     */
    fun onlineDto(
        rate: Double,
        period: GraphPeriod = GraphPeriod.THREE_MONTHS,
        krx: Boolean = true,
        empty: Boolean = false,
        tab: String = "usd",
        inProgress: Boolean = false
    ) =
        GraphV2TabResponse(
            tab = tab,
            period = period.code,
            series = if (empty) emptyList() else listOfNotNull(
                GraphV2Series(ONLINE, ONLINE, "krw", "KRW", 2, listOf(GraphV2Point(NOON - 1.days, rate, "x")),
                    GraphV2Provenance(false, emptyList()), null),
                if (!krx) null else GraphV2Series(KRX_SERIES, KRX_SERIES, "krw", "KRW", 1,
                    listOf(GraphV2Point(NOON - 1.days, rate + 5, "krx")), GraphV2Provenance(false, emptyList()), null)
            ),
            metadata = GraphV2Metadata(NOON - 1.hours, "1d", GraphV2Range("2026-07-05", "2026-10-05")),
            inProgress = if (!inProgress || empty) null else listOfNotNull(ONLINE, KRX_SERIES.takeIf { krx }).associateWith {
                GraphV2InProgressSeed(NOON - 10.minutes, rate + 2, rate - 2, rate, NOON - 5.minutes)
            }
        )

}

@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxBetaGraphRig(
    test: TestScope, val root: File, initialSnapshot: TopicAccessSnapshot,
    initialFence: TopicSessionFence, val row: String,
    private val realSnapshot: (() -> TopicAccessSnapshot)? = null,
    writePorts: GraphV2WritePorts? = null,
    private val start: Instant = NOON
) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = initialFence
        var protectedOpen = true
        /** The catalog's tabs; usd alone unless a row lists more before its first request. */
        var tabs = listOf("usd")
        private var revision = 100L
        var handSnapshot: TopicAccessSnapshot = initialSnapshot
            set(value) {
                revision += 1
                field = value.copy(revision = revision)
            }
        var acquires = 0
        var observerThrows = false
        /** Gate snapshot reads so far; a hook runs just before the read with its number. */
        var gateReads = 0
        private val gateHooks = mutableMapOf<Int, () -> Unit>()
        fun atGateRead(n: Int, action: () -> Unit) { gateHooks[n] = action }
        /** Request-owner snapshot reads so far; a hook runs just before the read with its number (S4 RT01-B2b-1). */
        var accessReads = 0
        private val accessHooks = mutableMapOf<Int, () -> Unit>()
        fun atAccessRead(n: Int, action: () -> Unit) { accessHooks[n] = action }
        fun clearAccessHooks() = accessHooks.clear()
        val snapshot: TopicAccessSnapshot get() = realSnapshot?.invoke() ?: handSnapshot
        val files = RecordingFiles()
        val store = HeldStore(FileGraphV2DiskStore({ root }, codec, files, StandardTestDispatcher(test.testScheduler)))
        val diagnostics = mutableListOf<GraphV2SeedDiagnostic>()
        val failures = mutableListOf<Throwable>()
        val sent = mutableListOf<Sent>()

        val uses = object : TopicUseAuthority {
            private val inner = SnapshotTopicUseAuthority { snapshot }
            override fun acquire(fence: TopicSessionFence): TopicUseLifetime? {
                acquires++
                return inner.acquire(fence)
            }
            override fun admits(lifetime: TopicUseLifetime): Boolean = inner.admits(lifetime)
        }

        val owners = object : GraphOwnerSource {
            override fun currentIdentity(): AuthIdentityFence? = fence?.identity
            override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
                if (currentIdentity() != expected) throw AuthIdentityChangedException()
                return AuthSnapshot(expected.uid, expected.authGeneration, "token")
            }
        }

        val gate = GraphV2AccessGate(
            currentIdentity = { owners.currentIdentity() },
            currentAccessFence = { fence },
            snapshot = {
                gateReads++
                gateHooks.remove(gateReads)?.invoke()
                snapshot
            },
            protectedAdmission = { protectedOpen }
        )

        val fetcher = object : GraphV2Fetching {
            override suspend fun catalog(
                owner: AuthSnapshot,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2CatalogResponse> = ok(catalog(tabs))

            override suspend fun tab(
                owner: AuthSnapshot,
                key: GraphKey,
                useAdmitted: () -> Boolean
            ): AuthenticatedHttpResponse<GraphV2TabResponse> {
                val s = Sent(key, useAdmitted(), test.testScheduler.currentTime - base)
                sent += s
                return s.tab.await()
            }
        }

        val coordinator: GraphV2RequestCoordinator = GraphV2RequestCoordinator(
            fetcher = fetcher,
            owners = owners,
            currentAccessFence = { fence },
            uses = uses,
            protectedAdmission = { true },
            accessSnapshot = {
                accessReads++
                accessHooks.remove(accessReads)?.invoke()
                snapshot
            },
            scope = scope,
            clock = AppClock { at(test.testScheduler.currentTime - base) },
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { failures += it },
            cachePorts = GraphV2CachePorts(store = store, gate = gate, writePorts = writePorts, onSeedDiagnostic = {
                diagnostics += it
                if (observerThrows) throw IllegalStateException("observer")
            })
        )

        val state get() = coordinator.state.value
        fun entry(key: GraphKey = KEY) = state.entries.getValue(key)
        fun ids(key: GraphKey = KEY) = entry(key).tab.graph.series.map { it.seriesId }
        fun rate(key: GraphKey = KEY): Double? = entry(key).tab.graph.series.first().points.first().rate
        fun exposed(key: GraphKey = KEY) = coordinator.protectedEntry(key)
        fun exposedIds(key: GraphKey = KEY) = checkNotNull(exposed(key)) { "not exposed" }.tab.graph.series.map { it.seriesId }
        fun tabTimes() = sent.filter { it.admittedAtSend }.map { it.atMs }
        fun tabTimes(key: GraphKey) = sent.filter { it.admittedAtSend && it.key == key }.map { it.atMs }
        fun at(ms: Long): Instant = start + ms.milliseconds

        fun start() = coordinator.start()
        fun close() = scope.cancel()

        /** Activates [key] and answers its request with [dto]. */
        fun adopt(test: TestScope, dto: GraphV2TabResponse, key: GraphKey = KEY) {
            start(); coordinator.onActivated(key); test.runCurrent()
            sent.last().tab.complete(ok(dto)); test.runCurrent()
        }

        fun flip(test: TestScope, next: TopicAccessSnapshot) {
            check(realSnapshot == null) { "S-1 only" }; handSnapshot = next
            coordinator.onContextChanged(); test.runCurrent()
        }

    fun generalKey(key: GraphKey = KEY) = GraphV2GeneralKey(checkNotNull(fence).identity.uid,
        checkNotNull(checkNotNull(fence).userAccessEpoch), key.tab, key.period.code)
    fun componentsFor(tab: GraphV2Tab, epoch: String?, response: String = "seed") = components(tab, generalKey(), epoch, response)
    fun seedReasons() = diagnostics.filter { it.component == GraphV2DiskComponent.GENERAL }.map { it.reason }
    fun retire(selects: (GraphCapabilityScope) -> Boolean): Pair<Set<GraphCapabilityScope>, GraphRuntimeRetirement> {
        val seen = mutableListOf<GraphCapabilityScope>()
        val result = coordinator.retireCapabilities { seen += it; selects(it) }
        check(seen.size == seen.toSet().size) { "contract($row): each retirement candidate once" }
        return seen.toSet() to result
    }
    fun scope(epoch: String?) = GraphCapabilityScope(checkNotNull(fence).identity.uid, epoch)
}

/** Real PAC dependencies use the existing transition store, source and completed purger. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KrxBetaGraphPac(val test: TestScope, val row: String) {
    val job = SupervisorJob()
    private var id = 0
    val store = KrxHarnessStore(EpochIdGenerator { "b1b-${id++}" })
    val source = KrxHarnessSource({ test.testScheduler.currentTime }) { EntitlementsOutcome.StableActive(true) }
    private val purger = KrxHarnessPurger()
    val pac = krxBetaPac(source, store, purger, purger,
        CoroutineScope(job + StandardTestDispatcher(test.testScheduler)),
        RecheckClock { test.testScheduler.currentTime }, { source.liveFence }, AccessOrderSequence())
    val nextEpochForDiskFixture get() = "b1b-$id"
    val c get() = pac.coordinator
    val snapshot get() = c.accessSnapshot
    lateinit var fence: TopicSessionFence
    val publications = mutableListOf<TopicAccessSnapshot>()
    init { pac.check.observe { publications += it } }
    suspend fun open() {
        c.onIdentityChanged(AuthIdentityFence(KrxHarnessDefaults.OWNER, 1L)).applied()
        c.refresh(RefreshIntent.FORCE_PREMIUM); test.runCurrent()
        fence = checkNotNull(c.topicGrantResult().fence) { "contract($row): real issued fence" }
        assertEquals("contract($row): real PAC OPEN", KrxContestPhase.OPEN, c.krxContestView?.state?.phase)
        assertTrue("contract($row): real ON p/GENERAL/KRX", snapshot.krxPermitSeq >= 1 &&
            snapshot.facts.capabilityAllowed && snapshot.acquireUse(fence) != null)
    }
    suspend fun hold() {
        val before = snapshot
        source.identityNulls = 1
        c.onTopicRejected(c.reserveRejection(fence.grant, KrxHarnessDefaults.KRX_REFUSAL))
        assertEquals("premise: $row identity fault reached", 0, source.identityNulls)
        sameGeneral(before)
        assertEquals("contract($row): hold keeps epoch", before.facts.recordFence, snapshot.facts.recordFence)
        assertEquals("contract($row): hold increases C", before.capabilityInvalidations + 1, snapshot.capabilityInvalidations)
        assertFalse("contract($row): actual capability hold", snapshot.facts.capabilityAllowed)
    }
    suspend fun rotate(): String {
        val before = snapshot
        source.next = { EntitlementsOutcome.StableActive(false) }
        c.refresh(RefreshIntent.FORCE_ENTITLEMENTS); test.runCurrent()
        if (store.record.krxCapabilityEpoch == before.facts.recordFence?.krxCapabilityEpoch) {
            test.advanceTimeBy(5_010); test.runCurrent()
            c.refresh(RefreshIntent.FORCE_ENTITLEMENTS); test.runCurrent()
        }
        sameGeneral(before)
        assertFalse("contract($row): hidden KRX", snapshot.facts.capabilityAllowed)
        assertEquals("contract($row): C increments once", before.capabilityInvalidations + (if (before.facts.capabilityAllowed) 1 else 0), snapshot.capabilityInvalidations)
        val epoch = checkNotNull(store.record.krxCapabilityEpoch)
        assertTrue("contract($row): actual record epoch rotated", epoch != before.facts.recordFence?.krxCapabilityEpoch)
        return epoch
    }
    suspend fun reopen() {
        val before = snapshot
        source.next = { EntitlementsOutcome.StableActive(true) }
        c.refresh(RefreshIntent.FORCE_ENTITLEMENTS); test.runCurrent()
        sameGeneral(before)
        assertEquals("contract($row): reopen OPEN", KrxContestPhase.OPEN, c.krxContestView?.state?.phase)
        assertTrue("contract($row): fresh permit allowed", snapshot.facts.capabilityAllowed && snapshot.krxPermitSeq >= before.krxPermitSeq &&
            (before.facts.capabilityAllowed || snapshot.krxPermitSeq > before.krxPermitSeq))
        assertEquals("contract($row): release keeps C", before.capabilityInvalidations, snapshot.capabilityInvalidations)
    }
    fun sameGeneral(before: TopicAccessSnapshot) {
        assertEquals("contract($row): same token", before.facts.token, snapshot.facts.token)
        assertEquals("contract($row): same U", before.userInvalidations, snapshot.userInvalidations)
        assertEquals("contract($row): same USER epoch", before.facts.recordFence?.userAccessEpoch, snapshot.facts.recordFence?.userAccessEpoch)
        assertEquals("contract($row): same USER end", before.lastUserEnd, snapshot.lastUserEnd)
        assertTrue("contract($row): standing GENERAL", snapshot.facts.tokenStanding && snapshot.facts.userAllowed)
    }
    /** Called from the test's finally, even when its first contract fails. */
    fun finish() {
        try { assertSame("contract($row): checker installed", pac.check, c.onAccessPublishedForTest) }
        finally { try { pac.check.assertHeld() } finally { job.cancel() } }
    }
}
