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

/**
 * Claude-owned S4 B1b-2c contract (2c-1 r3, 2c-2 r4): the request owner re-gates its slots when the capability configuration changes.
 *
 * Oracles: iOS a36682f GraphV2ViewModel.swift :270-321 (setKrxVisible: keep a seed from the disk raw answer before clearing,
 * clear freshness, purge KRX, show the re-gated seed unconfirmed) and :423-458 (only an online 200 stamps freshness);
 * ANDROID_V2_PLAN.md :1288 (a KRX withdrawal keeps the general half). Design: R4c/S4 b1b2c_design_codex.r1 as trimmed by
 * b1b2c_review_claude.r1 and b1b2c_verdict_codex.r1 (T1-T5): the gate reads the record's capability configuration (record
 * epoch, allowed) from the admitted snapshot; a configuration change in the same data scope, found on a context notice even
 * when the request context is unchanged, keeps every occupied key's general half, removes its KRX half and clears its stamp;
 * only the active key re-reads its own stored answer, and only a stored answer whose GENERAL responseId equals the occupied
 * slot's (at the start and at the completion of the read) and whose KRX half joins structurally supplements the slot, without
 * a stamp; a revision alone changes nothing. B1b-2c-2 (rows T; b1b2c2_design_codex.r1): after a change the active key is
 * re-checked online once - by a request already registered for it, else by an existing cold owner, else by one request three
 * seconds after the first change of a burst (not before a shared floor ends; a retryable failure of it arms cold, a terminal
 * one does not). An online 200 confirms the current configuration when that configuration is closed, or when its request
 * started with the current open epoch; under a closed configuration the slot keeps no KRX half; an unconfirmed 200 (started
 * without an epoch, now open) is re-checked three seconds after it lands. A wake also compares and supplements.
 *
 * The fixture is the cache contract's: the real request owner, the real FileGraphV2DiskStore with the JSON codec, the real
 * gate and the real SnapshotTopicUseAuthority over one fake published snapshot whose facts follow the issuer (standing from
 * the issued and current contexts; a capability block does not count as a user invalidation). Write ports are absent, so an
 * online answer never reaches the disk and every stored answer is one a row put there. A decorator around the store records
 * each read and its key, and can hold a GENERAL or a KRX result after the real read returned. A gate read hook stands in for a
 * publisher on another thread: it changes the snapshot just before a chosen gate read.
 */
class GraphV2RequestCoordinatorReGateTest {

    @get:Rule val folder = TemporaryFolder()

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        /** 23:50 KST on 2026-10-05; the midnight check is due at 00:02 KST, 720 seconds later. */
        val BEFORE_MIDNIGHT: Instant = Instant.parse("2026-10-05T14:50:00Z")
        val KEY = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val OTHER = GraphKey("usd", GraphPeriod.ONE_YEAR)
        val GKEY = GraphV2GeneralKey("u1", "e1", "usd", GraphPeriod.THREE_MONTHS.code)
        val FENCE = TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", TopicGrantToken(7L))
        val IDS = listOf("hana.usd-krw", "krx.usd-krw-futures", "kb.usd-krw")
        val GENERAL_IDS = listOf("hana.usd-krw", "kb.usd-krw")
        const val ONLINE = "investing.usd-krw"
        const val KRX_SERIES = "krx.usd-krw-futures"
        val ALL_IDS = IDS + ONLINE
        /** A capability-only hold: the record, the issued context, the token and the user invalidations stay. */
        val HELD = setOf(TopicAccessBlock.LOSS_CANDIDATE)
    }

    // --- the published topic access (the cache contract's issuer model) ------------------------------------------

    private fun snap(
        token: Long? = 7L,
        uid: String = "u1",
        gen: Long = 1L,
        epoch: String? = "e1",
        krx: String? = "K1",
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        issuedRecord: AccessFence? = null,
        generation: Long = 10L,
        invalidations: Long = 3L,
        unconfirmed: Boolean = false
    ): TopicAccessSnapshot {
        val binding = EntitlementsIdentity(uid, gen)
        val record = AccessFence(uid, epoch, krx)
        val issued = token?.let { TopicGrantContext(binding, issuedRecord ?: record, generation) }
        val userUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in userBlocks
        return TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = TopicAccessFacts.NONE.copy(
                token = token?.let(::TopicGrantToken),
                issuedFor = issued,
                binding = binding,
                recordFence = record,
                decisionGeneration = generation,
                tokenStanding = issued != null && !userUncertain && issued == TopicGrantContext(binding, record, generation),
                userBlocks = userBlocks,
                capabilityBlocks = capabilityBlocks,
                recordUnconfirmed = unconfirmed,
                userContextUncertain = userUncertain,
                capabilityContextUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in capabilityBlocks
            ),
            userInvalidations = invalidations
        )
    }

    // --- stored answers ---------------------------------------------------------------------------------------

    private fun serverTab(
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

    private fun components(tab: GraphV2Tab, key: GraphV2GeneralKey = GKEY, krx: String? = "K1", response: String = "r1") =
        (splitGraphV2ServerTab(tab, key, krx, response, null) as GraphV2Validation.Valid).value

    private fun joined(c: GraphV2DiskComponents, withKrx: Boolean = true) =
        joinGraphV2Components(c.general, if (withKrx) c.krx else null).tab

    private val codec = JsonGraphV2EnvelopeCodec()

    private fun hex(text: String) = text.encodeToByteArray().joinToString("") { "%02x".format(it) }

    private fun generalPath(root: File, k: GraphV2GeneralKey) =
        File(root, "general/${hex(k.uid)}/${hex(k.userAccessEpoch)}/${hex(k.tab)}/${k.period}.json")

    private fun krxPath(root: File, k: GraphV2KrxKey) =
        File(root, "krx/${hex(k.uid)}/${hex(k.userAccessEpoch)}/${hex(k.krxCapabilityEpoch)}/${hex(k.tab)}/${k.period}.json")

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    private fun Fixture.putGeneral(g: GraphV2GeneralEnvelope) =
        write(generalPath(root, g.key), (codec.encodeGeneral(g) as GraphV2Validation.Valid).value)

    private fun Fixture.putKrx(k: GraphV2KrxEnvelope) =
        write(krxPath(root, k.key), (codec.encodeKrx(k) as GraphV2Validation.Valid).value)

    private fun Fixture.put(c: GraphV2DiskComponents) {
        putGeneral(c.general)
        c.krx?.let { putKrx(it) }
    }

    private fun isKrx(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath.startsWith("krx/")

    // --- the fixture --------------------------------------------------------------------------------------------

    private class RecordingFiles : GraphV2AtomicFileIo {
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
    private class HeldStore(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
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

        fun release(n: Int) = hold.getValue(n).complete(Unit)
        fun releaseKrx(n: Int) = krxHold.getValue(n).complete(Unit)
    }

    private class Sent(val key: GraphKey, val admittedAtSend: Boolean, val atMs: Long) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
    }

    private val opened = mutableListOf<Fixture>()

    private fun flipTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
            opened.forEach { assertEquals("a timer loop", null, it.loopAt) }
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private inner class Fixture(test: TestScope, private val start: Instant = NOON, withRecorder: Boolean = false) {
        init { opened += this }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = FENCE
        var protectedOpen = true
        /** The catalog's tabs; usd alone unless a row lists more before its first request. */
        var tabs = listOf("usd")
        private var revision = 100L
        var snapshot: TopicAccessSnapshot = snap()
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
        val root: File = folder.newFolder()
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

        /** S4 RT03b-1b: a recorder on the gate and snapshot, so a 1d key's cold ladder becomes a recovery budget. */
        val recorder: GraphRecorder? = if (!withRecorder) null else GraphRecorder(
            scope, kotlinx.coroutines.flow.MutableStateFlow(1L), { snapshot }, { fence }, { coordinator.state.value.catalog }, gate,
            AppClock { start + (test.testScheduler.currentTime - base).milliseconds }
        )
        /** With a recorder, the clock fails a timer that re-arms over 1000 times within one virtual millisecond. */
        private val loopGuard = withRecorder
        var loopAt: Long? = null
        private var readsAt = -1L
        private var reads = 0
        private val guardedNow: () -> Instant = {
            val at = test.testScheduler.currentTime - base
            if (loopGuard) {
                if (at != readsAt) {
                    readsAt = at
                    reads = 0
                }
                if (++reads > 1_000) {
                    loopAt = at
                    throw IllegalStateException("timer loop at $at ms")
                }
            }
            start + at.milliseconds
        }

        val coordinator: GraphV2RequestCoordinator = GraphV2RequestCoordinator(
            fetcher = fetcher,
            owners = owners,
            currentAccessFence = { fence },
            uses = uses,
            protectedAdmission = { true },
            recorder = recorder,
            accessSnapshot = {
                accessReads++
                accessHooks.remove(accessReads)?.invoke()
                snapshot
            },
            scope = scope,
            clock = AppClock { guardedNow() },
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { failures += it },
            cachePorts = GraphV2CachePorts(store = store, gate = gate, onSeedDiagnostic = {
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

        /** The live session and the issuer move to another context together; the request owner is not told. */
        fun rebind(token: Long, krx: String, invalidations: Long) {
            fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", TopicGrantToken(token))
            snapshot = snap(token = token, krx = krx, invalidations = invalidations)
        }

        fun start() = coordinator.start()
        fun close() = scope.cancel()

        /** Activates [key] and answers its request with [dto]. */
        fun adopt(test: TestScope, dto: GraphV2TabResponse, key: GraphKey = KEY) {
            start(); coordinator.onActivated(key); test.runCurrent()
            sent.last().tab.complete(ok(dto)); test.runCurrent()
        }

        fun flip(test: TestScope, next: TopicAccessSnapshot) {
            snapshot = next
            coordinator.onContextChanged(); test.runCurrent()
        }
    }

    // --- responses ---------------------------------------------------------------------------------------------

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    private fun <T> status(code: Int, retryAfter: String? = null): AuthenticatedHttpResponse<T> {
        val headers = if (retryAfter == null) Headers.headersOf() else Headers.headersOf("Retry-After", retryAfter)
        return AuthenticatedHttpResponse(code, headers, null,
            AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP), byteArrayOf(1))
    }

    private fun catalog(tabs: List<String> = listOf("usd")) = GraphV2CatalogResponse(
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
    private fun onlineDto(
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

    private fun TestScope.step(d: Duration) {
        advanceTimeBy(d.inWholeMilliseconds)
        runCurrent()
    }

    // --- rows -------------------------------------------------------------------------------------------------

    /** C01: a change before anything is loaded re-gates nothing and reads nothing more than the activation's seed. */
    @Test fun F01_aChangeBeforeAnythingIsLoadedReadsNothingMore() = flipTest {
        val f = Fixture(this)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertNull(f.state.entries[KEY])
        assertEquals("the activation's seed read", 1, f.store.generalReads.size)
        f.flip(this, snap(capabilityBlocks = HELD))
        f.flip(this, snap())
        assertNull(f.state.entries[KEY])
        assertEquals("no further read", 1, f.store.generalReads.size)
        assertEquals(0, f.store.krxReads.size)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * C02: the capability opens with the request context unchanged (the context notice finds no context change). The occupied
     * slot, adopted from a request that started without an epoch, keeps its general half and loses its stamp.
     */
    @Test fun F02_anOpeningInTheSameContextClearsTheStamp() = flipTest {
        val f = Fixture(this)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.adopt(this, onlineDto(1500.0))
        assertEquals("adopted", NOON, f.entry().online200At)
        f.flip(this, snap())
        assertNull("unconfirmed", f.entry().online200At)
        assertEquals("the general half stays", 1500.0, f.rate())
        assertEquals("no KRX half from a start without an epoch", listOf(ONLINE), f.ids())
        assertNull("exposed unconfirmed", checkNotNull(f.exposed()).online200At)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * C03: a capability-only hold removes the KRX half from the slot and the raw entry and clears the stamp; the general half
     * stays. When the capability returns, the removed half does not come back from memory (nothing is stored on disk).
     */
    @Test fun F03_aHoldRemovesTheKrxHalfAndItDoesNotReturnFromMemory() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        assertEquals(listOf(ONLINE, KRX_SERIES), f.exposedIds())
        f.flip(this, snap(capabilityBlocks = HELD))
        assertNull(f.entry().online200At)
        assertEquals("raw: the general half", listOf(ONLINE), f.ids())
        assertEquals(1500.0, f.rate())
        assertEquals(listOf(ONLINE), f.exposedIds())
        assertNull(checkNotNull(f.exposed()).online200At)
        f.flip(this, snap())
        assertEquals("no KRX from memory", listOf(ONLINE), f.exposedIds())
        assertEquals(listOf(ONLINE), f.ids())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * C04: a real rotation K1 -> K2 as the issuer publishes it (token 7 stops standing, invalidations 3 -> 4; token 8 is approved
     * for the K2 record). The new context keeps the general half without the K1 half and unconfirmed; an answer to a request
     * sent before the rotation is not adopted.
     */
    @Test fun F04_aRotationKeepsTheGeneralHalfWithoutTheOldKrxHalf() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val old = f.sent.last()
        f.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN))
        f.snapshot = snap(krx = "K2", issuedRecord = AccessFence("u1", "e1", "K1"),
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L)
        f.rebind(token = 8L, krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        old.tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("the old request's answer is not adopted", 1500.0, f.rate())
        assertNull(f.entry().online200At)
        assertEquals("no K1 half in memory", listOf(ONLINE), f.ids())
        assertEquals(listOf(ONLINE), f.exposedIds())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** C05: a new revision whose change leaves the configuration (record unconfirmed, then confirmed) re-gates nothing. */
    @Test fun F05_aRevisionAloneReGatesNothing() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        val reads = f.store.generalReads.size
        val sends = f.sent.size
        f.flip(this, snap(unconfirmed = true))
        f.flip(this, snap())
        assertEquals("the stamp stays", NOON, f.entry().online200At)
        assertEquals(listOf(ONLINE, KRX_SERIES), f.ids())
        assertEquals("no re-read", reads, f.store.generalReads.size)
        assertEquals("no request", sends, f.sent.size)
    }

    /** C06: every occupied key loses its stamp and KRX half; only the active key re-reads its stored answer. */
    @Test fun F06_anInactiveKeyIsReGatedButNotRead() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1400.0, period = GraphPeriod.ONE_YEAR), key = OTHER)
        f.coordinator.onActivated(KEY); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        val otherReads = f.store.generalKeys.count { it.period == OTHER.period.code }
        f.flip(this, snap(capabilityBlocks = HELD))
        assertNull(f.entry(KEY).online200At)
        assertNull("the inactive key too", f.entry(OTHER).online200At)
        assertEquals(1400.0, f.rate(OTHER))
        assertEquals(listOf(ONLINE), f.ids(OTHER))
        assertEquals("the inactive key is not read", otherReads, f.store.generalKeys.count { it.period == OTHER.period.code })
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * C07: a seed installed while the capability was held is general only; when it opens, the same stored answer (same
     * responseId) supplements the slot with its KRX half, still unconfirmed, and nothing is written.
     */
    @Test fun F07_theSameStoredAnswerSupplementsItsKrxHalf() = flipTest {
        val f = Fixture(this)
        val c = components(serverTab(1300.0))
        f.put(c)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals("seeded, general only", GraphEntry(joined(c, withKrx = false), null), f.state.entries[KEY])
        assertEquals("no KRX read under the hold", 0, f.store.krxReads.size)
        f.flip(this, snap())
        assertEquals("the same answer with its KRX half", GraphEntry(joined(c), null), f.state.entries[KEY])
        assertEquals(IDS, f.exposedIds())
        assertNull(checkNotNull(f.exposed()).online200At)
        assertEquals(emptyList<File>(), f.files.prepares)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** C08: a stored answer of another response (R1) never supplements the adopted R2, nor replaces it. */
    @Test fun F08_anotherStoredResponseDoesNotSupplement() = flipTest {
        val f = Fixture(this)
        f.put(components(serverTab(1100.0)))
        f.snapshot = snap(capabilityBlocks = HELD)
        f.adopt(this, onlineDto(1500.0))
        assertEquals("R2 adopted over the R1 seed", 1500.0, f.rate())
        f.flip(this, snap())
        assertEquals("R2 stays", 1500.0, f.rate())
        assertEquals("R1's KRX half is not attached", listOf(ONLINE), f.ids())
        assertEquals(listOf(ONLINE), f.exposedIds())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** C09: R2 adopted while R1's supplement read is out: the late R1 completion does not take the slot back. */
    @Test fun F09_aSupplementLosesToAnAnswerAdoptedMeanwhile() = flipTest {
        val f = Fixture(this)
        f.put(components(serverTab(1100.0)))
        f.snapshot = snap(capabilityBlocks = HELD)
        f.store.hold[2] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals("seeded R1", 1100.0, f.rate())
        f.flip(this, snap())
        assertEquals("the supplement read is out", 2, f.store.generalReads.size)
        f.sent.single().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        assertEquals("R2 adopted", 1500.0, f.rate())
        f.store.release(2); runCurrent()
        assertEquals("R2 stays", 1500.0, f.rate())
        assertEquals(listOf(ONLINE), f.ids())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** C10: a stored KRX half that does not join the same answer structurally leaves the general half alone. */
    @Test fun F10_aStructurallyMismatchedKrxHalfIsNotJoined() = flipTest {
        for ((label, krx) in listOf(
            "another responseId" to components(serverTab(1300.0), response = "r2").krx,
            "other metadata" to components(serverTab(1300.0, fetchedAt = NOON - 2.hours)).krx,
            "an ordinal collision" to components(serverTab(1300.0, ids = listOf(KRX_SERIES, "hana.usd-krw", "kb.usd-krw"))).krx
        )) {
            val f = Fixture(this)
            f.putGeneral(components(serverTab(1300.0)).general)
            f.putKrx(checkNotNull(krx))
            f.snapshot = snap(capabilityBlocks = HELD)
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            f.flip(this, snap())
            assertTrue("$label: the KRX half was read", f.store.krxReads.any { it is GraphV2DiskRead.Found })
            assertEquals(label, GENERAL_IDS, f.ids())
            assertEquals(label, GENERAL_IDS, f.exposedIds())
            assertEquals(label, emptyList<Throwable>(), f.failures)
            f.close()
        }
    }

    /**
     * C20 (2a's null-stamp occupancy, in the real order): S1 fills an empty slot but its result waits; R2 is adopted; a
     * capability-only hold re-gates R2 to an unconfirmed slot; S1's completion still does not land. Also for an empty R2.
     */
    @Test fun F11_anOccupiedUnconfirmedSlotIsNotFilledByALateSeed() = flipTest {
        for ((label, dto) in listOf("R2" to onlineDto(1500.0), "an empty R2" to onlineDto(1500.0, empty = true))) {
            val f = Fixture(this)
            f.put(components(serverTab(1100.0)))
            f.store.hold[1] = CompletableDeferred()
            f.adopt(this, dto)
            assertEquals(label, NOON, f.entry().online200At)
            f.flip(this, snap(capabilityBlocks = HELD))
            val regated = f.entry()
            assertNull(label, regated.online200At)
            f.store.release(1); runCurrent()
            assertEquals("$label: the late seed does not land", regated, f.entry())
            assertEquals(label, emptyList<Throwable>(), f.failures)
            f.close()
        }
    }

    /** C21: a USER hold and release (invalidations 3 -> 4) while a supplement read is out: it lands nothing and acquires nothing. */
    @Test fun F12_aUserHoldRoundTripRetiresTheSupplement() = flipTest {
        val f = Fixture(this)
        f.put(components(serverTab(1300.0)))
        f.snapshot = snap(capabilityBlocks = HELD)
        f.store.hold[2] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.flip(this, snap())
        assertEquals("the supplement read is out", 2, f.store.generalReads.size)
        f.snapshot = snap(userBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L)
        f.snapshot = snap(invalidations = 4L)
        val acquires = f.acquires
        f.store.release(2); runCurrent()
        assertEquals("nothing lands", GENERAL_IDS, f.ids())
        assertEquals("nothing acquires", acquires, f.acquires)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** C22: a failed supplement (absent, rejected, unreadable KRX; a throwing observer) leaves the cold retry and the failure. */
    @Test fun F13_aFailedSupplementLeavesTheColdRetry() = flipTest {
        for (failure in listOf("absent", "rejected", "unreadable", "observer")) {
            val f = Fixture(this)
            val c = components(serverTab(1300.0))
            f.putGeneral(c.general)
            val krxFile = krxPath(f.root, checkNotNull(c.krx).key)
            when (failure) {
                "absent" -> Unit
                "rejected" -> write(krxFile, "not json".encodeToByteArray())
                "unreadable" -> {
                    f.putKrx(checkNotNull(c.krx))
                    f.files.failRead = { isKrx(f.root, it) }
                }
                "observer" -> {
                    write(krxFile, "not json".encodeToByteArray())
                    f.observerThrows = true
                }
            }
            f.snapshot = snap(capabilityBlocks = HELD)
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            f.sent.single().tab.complete(status(503)); runCurrent()
            f.flip(this, snap())
            assertEquals(failure, failure != "absent", f.diagnostics.isNotEmpty())
            step(3.seconds)
            assertEquals("$failure: the cold retry goes out", listOf(0L, 3_000L), f.tabTimes())
            assertTrue("$failure: the failure stays", KEY in f.state.failures)
            assertEquals(failure, GENERAL_IDS, f.ids())
            assertEquals(failure, emptyList<Throwable>(), f.failures)
            assertEquals(failure, emptyList<File>(), f.files.prepares)
            f.close()
        }
    }

    /**
     * A change that lands while the empty-slot seed is still reading waits for that one owner: the seed installs its general
     * half (read without an epoch), and the supplement it now needs runs when the seed ends, adding the KRX half unconfirmed.
     */
    @Test fun F14_aChangeDuringTheSeedIsAppliedWhenTheSeedEnds() = flipTest {
        val f = Fixture(this)
        val c = components(serverTab(1300.0))
        f.put(c)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.store.hold[1] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.flip(this, snap())
        assertNull("the seed is still reading", f.state.entries[KEY])
        assertEquals("one owner", 1, f.store.generalReads.size)
        f.store.release(1); runCurrent()
        assertEquals("the seed, then its supplement", GraphEntry(joined(c), null), f.state.entries[KEY])
        assertEquals(2, f.store.generalReads.size)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** Without a context notice, a refresh or a re-activation of the same key still finds the change and re-gates. */
    @Test fun F15_aRefreshOrReActivationFindsAMissedChange() = flipTest {
        for (how in listOf("refresh", "re-activation")) {
            val f = Fixture(this)
            f.adopt(this, onlineDto(1500.0))
            f.snapshot = snap(capabilityBlocks = HELD)
            if (how == "refresh") f.coordinator.onRefreshRequested() else f.coordinator.onActivated(KEY)
            runCurrent()
            assertNull(how, f.entry().online200At)
            assertEquals(how, listOf(ONLINE), f.ids())
            assertEquals(how, emptyList<Throwable>(), f.failures)
            f.close()
        }
    }

    /**
     * A new data scope whose first configuration read is refused (protected closed): an answer adopted there before the first
     * successful read stays unconfirmed. That read sets the new scope's comparison base; it does not confirm the earlier answer.
     */
    @Test fun F16_aNewScopeStartsItsOwnComparisonBase() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        // The user epoch moves to e2 (token 8 for the new record; invalidations 3 -> 4); protected admission is closed.
        f.protectedOpen = false
        f.fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "e2", TopicGrantToken(8L))
        f.snapshot = snap(token = 8L, epoch = "e2", krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        assertTrue("the new scope starts empty", f.state.entries.isEmpty())
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertNull("completion configuration unavailable: unconfirmed", f.entry().online200At)
        val reads = f.store.generalReads.size
        f.protectedOpen = true
        f.coordinator.onContextChanged(); runCurrent()
        assertNull("the first successful read sets the base without confirming the earlier answer", f.entry().online200At)
        assertEquals(1600.0, f.rate())
        assertEquals("no supplement", reads, f.store.generalReads.size)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * The notice's configuration read sees the opening; just before the next gate read (the bind that starts the supplement) the
     * publisher holds the capability, and then releases it. The start under the hold is refused without spending the attempt;
     * the supplement after the release adds the KRX half.
     */
    @Test fun F17_aSupplementStartRefusedUnderAPassingHoldIsNotSpent() = flipTest {
        val f = Fixture(this)
        val c = components(serverTab(1300.0))
        f.put(c)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.snapshot = snap()
        f.atGateRead(f.gateReads + 2) { f.snapshot = snap(capabilityBlocks = HELD) }
        f.coordinator.onContextChanged(); runCurrent()
        f.flip(this, snap())
        assertEquals("the same answer with its KRX half", GraphEntry(joined(c), null), f.state.entries[KEY])
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * A -> B -> A while a supplement has read both halves but its result waits; then its completion and a deactivation are queued
     * in that order. The completion from the first A is refused, and the re-read for the second A loses to the deactivation.
     */
    @Test fun F18_aSupplementFromAnEarlierOpeningIsRefused() = flipTest {
        val f = Fixture(this)
        f.put(components(serverTab(1300.0)))
        f.snapshot = snap(capabilityBlocks = HELD)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.store.krxHold[1] = CompletableDeferred()
        f.flip(this, snap())
        assertEquals("the supplement read its KRX half", 1, f.store.krxReads.size)
        f.flip(this, snap(capabilityBlocks = HELD))
        f.flip(this, snap())
        f.store.releaseKrx(1)
        f.scope.launch { f.coordinator.onDeactivated() }
        runCurrent()
        assertEquals("the earlier opening's supplement does not land", GENERAL_IDS, f.ids())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** A supplement whose KRX read failed keeps its need: once the failure clears, a deactivation and re-activation supplements. */
    @Test fun F19_aFailedSupplementKeepsItsNeedForTheNextActivation() = flipTest {
        val f = Fixture(this)
        val c = components(serverTab(1300.0))
        f.put(c)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.files.failRead = { isKrx(f.root, it) }
        f.flip(this, snap())
        assertEquals("the KRX read failed", GENERAL_IDS, f.ids())
        f.files.failRead = { false }
        f.coordinator.onDeactivated(); runCurrent()
        f.coordinator.onActivated(KEY); runCurrent()
        assertEquals("supplemented on the next activation", GraphEntry(joined(c), null), f.state.entries[KEY])
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    // --- B1b-2c-2: the online re-check after a change ------------------------------------------------------------

    /** C02 timing: an opening on an occupied slot with nothing in flight and no cold owner is re-checked once, at t=3. */
    @Test fun T01_anOpeningIsReCheckedOnceAfterThreeSeconds() = flipTest {
        val f = Fixture(this)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.adopt(this, onlineDto(1500.0))
        assertEquals("closed and confirmed", NOON, f.entry().online200At)
        f.flip(this, snap())
        step(2_999.milliseconds)
        assertEquals("nothing before t=3", listOf(0L), f.tabTimes())
        step(1.milliseconds)
        assertEquals("one check at t=3", listOf(0L, 3_000L), f.tabTimes())
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("started with the open epoch: confirmed", f.at(3_000), f.entry().online200At)
        assertEquals(listOf(ONLINE, KRX_SERIES), f.ids())
        step(10.seconds)
        assertEquals(listOf(0L, 3_000L), f.tabTimes())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** C01 timing: a change on an empty slot (its request ended terminally, nothing in flight, no cold) schedules no check. */
    @Test fun T02_aChangeBeforeAnythingIsLoadedSchedulesNothing() = flipTest {
        val f = Fixture(this)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(status(400)); runCurrent()
        assertNull(f.state.entries[KEY])
        f.flip(this, snap(capabilityBlocks = HELD))
        step(10.seconds)
        assertEquals("only the activation's request", listOf(0L), f.tabTimes())
    }

    /** C06 timing: only the active key is re-checked; the inactive one is not requested. */
    @Test fun T03_onlyTheActiveKeyIsReChecked() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1400.0, period = GraphPeriod.ONE_YEAR), key = OTHER)
        f.coordinator.onActivated(KEY); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        f.flip(this, snap(capabilityBlocks = HELD))
        step(10.seconds)
        assertEquals(listOf(0L, 3_000L), f.tabTimes(KEY))
        assertEquals("the inactive key", listOf(0L), f.tabTimes(OTHER))
    }

    /** C11: changes at t=0, 1 and 2 are one burst: the first deadline holds, and one check goes out at t=3. */
    @Test fun T04_aBurstOfChangesIsCheckedOnceFromTheFirstChange() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.flip(this, snap(capabilityBlocks = HELD))
        step(1.seconds); f.flip(this, snap())
        step(1.seconds); f.flip(this, snap(capabilityBlocks = HELD))
        step(1.seconds)
        assertEquals(listOf(0L, 3_000L), f.tabTimes())
        // A terminal end frees the request, so a second scheduled check would show.
        f.sent.last().tab.complete(status(400)); runCurrent()
        step(1.seconds)
        assertEquals("nothing at t=4", listOf(0L, 3_000L), f.tabTimes())
        step(1.seconds)
        assertEquals("nothing at t=5", listOf(0L, 3_000L), f.tabTimes())
        step(8.seconds)
        assertEquals("no other check", listOf(0L, 3_000L), f.tabTimes())
    }

    /**
     * C12 and (b): a request started with the open epoch K1 at t=1 is in flight when the capability is held at t=2. The check
     * is left to it: its 200 at t=4 confirms the closed configuration, no other check goes out, and the slot keeps no KRX half -
     * reopening K1 without a notice exposes none.
     */
    @Test fun T05_aRequestInFlightConfirmsAClosedConfigurationWithoutAKrxHalf() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        step(1.seconds)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val inFlight = f.sent.last()
        step(1.seconds)
        f.flip(this, snap(capabilityBlocks = HELD))
        assertNull(f.entry().online200At)
        step(2.seconds)
        inFlight.tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("confirmed at t=4", f.at(4_000), f.entry().online200At)
        assertEquals("raw: no KRX half", listOf(ONLINE), f.ids())
        step(10.seconds)
        assertEquals("no check of its own", listOf(0L, 1_000L), f.tabTimes())
        f.snapshot = snap()
        assertEquals("reopened without a notice: no KRX from memory", listOf(ONLINE), f.exposedIds())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** C12 open control: a request started with K1 lands after a hold and a reopening of the same K1: confirmed, with KRX. */
    @Test fun T06_aRequestStartedWithTheReopenedEpochConfirmsIt() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val inFlight = f.sent.last()
        f.flip(this, snap(capabilityBlocks = HELD))
        step(1.seconds); f.flip(this, snap())
        step(1.seconds)
        inFlight.tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals(f.at(2_000), f.entry().online200At)
        assertEquals(listOf(ONLINE, KRX_SERIES), f.ids())
        step(10.seconds)
        assertEquals("no check of its own", listOf(0L, 0L), f.tabTimes())
    }

    /**
     * C13: a request started without an epoch (held) lands at t=2 after an opening at t=0: its general half is adopted
     * unconfirmed and is re-checked three seconds after it lands (t=5), not three seconds after the opening.
     */
    @Test fun T07_anUnconfirmedAnswerIsReCheckedThreeSecondsAfterItLands() = flipTest {
        val f = Fixture(this)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.flip(this, snap())
        step(2.seconds)
        f.sent.single().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        assertNull("unconfirmed", f.entry().online200At)
        assertEquals(listOf(ONLINE), f.ids())
        step(2.seconds)
        assertEquals("not at t=3", listOf(0L), f.tabTimes())
        step(1.seconds)
        assertEquals("at t=5", listOf(0L, 5_000L), f.tabTimes())
    }

    /** C14: an existing cold owner (next attempt at t=9) takes the check of a change at t=4: nothing at t=7, its own at t=9. */
    @Test fun T08_anExistingColdOwnerTakesTheCheck() = flipTest {
        val f = Fixture(this)
        f.put(components(serverTab(1300.0)))
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.last().tab.complete(status(503)); runCurrent()
        step(3.seconds)
        f.sent.last().tab.complete(status(503)); runCurrent()
        step(1.seconds)
        f.flip(this, snap(capabilityBlocks = HELD))
        step(5.seconds)
        assertEquals("the ladder only", listOf(0L, 3_000L, 9_000L), f.tabTimes())
    }

    /** C15: the check at t=3 fails with 503: cold starts from it, retrying at t=6. */
    @Test fun T09_aRetryableFailureOfTheCheckArmsCold() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.flip(this, snap(capabilityBlocks = HELD))
        step(3.seconds)
        f.sent.last().tab.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals(listOf(0L, 3_000L, 6_000L), f.tabTimes())
    }

    /** C16: a terminal failure of the check (400, 404) arms no cold. */
    @Test fun T10_aTerminalFailureOfTheCheckArmsNoCold() = flipTest {
        for (code in listOf(400, 404)) {
            val f = Fixture(this)
            f.adopt(this, onlineDto(1500.0))
            f.flip(this, snap(capabilityBlocks = HELD))
            step(3.seconds)
            f.sent.last().tab.complete(status(code)); runCurrent()
            step(20.seconds)
            assertEquals("$code", listOf(0L, 3_000L), f.tabTimes())
            f.close()
        }
    }

    /** C17: a shared floor until t=10 postpones the check to t=10; a deactivation before it drops the check. */
    @Test fun T11_aSharedFloorPostponesTheCheck() = flipTest {
        for (deactivate in listOf(false, true)) {
            val f = Fixture(this)
            f.adopt(this, onlineDto(1500.0))
            f.coordinator.onRefreshRequested(force = true); runCurrent()
            f.sent.last().tab.complete(status(429, retryAfter = "10")); runCurrent()
            f.flip(this, snap(capabilityBlocks = HELD))
            step(5.seconds)
            if (deactivate) { f.coordinator.onDeactivated(); runCurrent() }
            step(10.seconds)
            assertEquals("deactivate=$deactivate", if (deactivate) listOf(0L, 0L) else listOf(0L, 0L, 10_000L), f.tabTimes())
            f.close()
        }
    }

    /** C18: an older request of key A lands while B's check waits: B's check still goes out at t=3. */
    @Test fun T12_anotherKeysCompletionKeepsTheCheck() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1400.0, period = GraphPeriod.ONE_YEAR), key = OTHER)
        f.coordinator.onActivated(KEY); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val olderA = f.sent.last()
        f.coordinator.onActivated(OTHER); runCurrent()
        f.flip(this, snap(capabilityBlocks = HELD))
        step(1.seconds)
        olderA.tab.complete(ok(onlineDto(1600.0))); runCurrent()
        step(2.seconds)
        assertEquals("B's check", listOf(0L, 3_000L), f.tabTimes(OTHER))
    }

    /**
     * C19: a change at 00:01:58 waits for its check (due 00:02:01); the midnight request at 00:02 fails unconfirmed (503) and
     * hands over to cold (00:02:03); the waiting check does not go out at 00:02:01.
     */
    @Test fun T13_theMidnightRequestTakesTheCheckAndHandsOverToCold() = flipTest {
        val f = Fixture(this, start = BEFORE_MIDNIGHT)
        f.adopt(this, onlineDto(1500.0))
        step(718.seconds)
        f.flip(this, snap(capabilityBlocks = HELD))
        step(2.seconds)
        assertEquals("the midnight request", listOf(0L, 720_000L), f.tabTimes())
        f.sent.last().tab.complete(status(503)); runCurrent()
        step(5.seconds)
        assertEquals("cold at 00:02:03, nothing at 00:02:01", listOf(0L, 720_000L, 723_000L), f.tabTimes())
    }

    /** T2 control: a refresh after a change goes out at once; its 200 confirms, and no other check follows. */
    @Test fun T14_aRefreshAfterAChangeIsNotHeldBack() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.flip(this, snap(capabilityBlocks = HELD))
        step(1.seconds)
        f.coordinator.onRefreshRequested(); runCurrent()
        assertEquals("not held back", listOf(0L, 1_000L), f.tabTimes())
        step(1.seconds)
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals(f.at(2_000), f.entry().online200At)
        step(10.seconds)
        assertEquals(listOf(0L, 1_000L), f.tabTimes())
    }

    /**
     * M27, supplement: an opening published without a notice is found by the cold wake at t=3, which also starts the supplement:
     * one more GENERAL read, then the KRX half from disk, unconfirmed (the wake's own request stays unanswered).
     */
    @Test fun T15_aWakeFindsAnOpeningAndSupplements() = flipTest {
        val f = Fixture(this)
        val c = components(serverTab(1300.0))
        f.put(c)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(status(503)); runCurrent()
        val reads = f.store.generalReads.size
        f.store.hold[reads + 1] = CompletableDeferred()
        step(1.seconds)
        f.snapshot = snap()
        step(2.seconds)
        assertEquals("the cold request", listOf(0L, 3_000L), f.tabTimes())
        assertEquals("the supplement read", reads + 1, f.store.generalReads.size)
        f.store.release(reads + 1); runCurrent()
        assertEquals(GraphEntry(joined(c), null), f.state.entries[KEY])
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * M27, comparison: a hold published without a notice is found by the midnight wake: before the midnight request is answered,
     * the raw KRX half is gone and the raw and protected stamps are clear; the general half stays.
     */
    @Test fun T16_aWakeFindsAHoldBeforeItsRequestIsAnswered() = flipTest {
        val f = Fixture(this, start = BEFORE_MIDNIGHT)
        f.adopt(this, onlineDto(1500.0))
        step(719.seconds)
        f.snapshot = snap(capabilityBlocks = HELD)
        step(1.seconds)
        assertEquals("the midnight request", listOf(0L, 720_000L), f.tabTimes())
        assertEquals(listOf(ONLINE), f.ids())
        assertNull(f.entry().online200At)
        assertNull(checkNotNull(f.exposed()).online200At)
        assertEquals(1500.0, f.rate())
    }

    /**
     * Delegation to a request of an earlier activation: KEY's request is out, OTHER and then KEY are activated, and a hold lands.
     * The check is left to that request; its 503 at t=1 hands over to cold under the current activation (retry at t=4).
     */
    @Test fun T17_aDelegatedRequestOfAnEarlierActivationHandsOverToCold() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val earlier = f.sent.last()
        f.coordinator.onActivated(OTHER); runCurrent()
        f.coordinator.onActivated(KEY); runCurrent()
        f.flip(this, snap(capabilityBlocks = HELD))
        step(1.seconds)
        earlier.tab.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals(listOf(0L, 0L, 4_000L), f.tabTimes(KEY))
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** A withdrawal published during the completion's configuration read (the token rotates): the answer is not adopted. */
    @Test fun T18_aWithdrawalDuringTheCompletionReadRefusesTheAnswer() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.atGateRead(f.gateReads + 1) { f.rebind(token = 8L, krx = "K1", invalidations = 4L) }
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("not adopted", 1500.0, f.rate())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * A cold attempt sent under a hold lands after an opening as an unconfirmed 200 (no epoch at its start): it spends no rung,
     * and the cold owner waits its current interval again from the landing (t5 + 3 = t8); no separate check is scheduled.
     */
    @Test fun T19_anUnconfirmedAnswerOfAColdAttemptKeepsTheColdWait() = flipTest {
        val f = Fixture(this)
        f.put(components(serverTab(1300.0)))
        f.snapshot = snap(capabilityBlocks = HELD)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(status(503)); runCurrent()
        step(3.seconds)
        assertEquals("the cold attempt", listOf(0L, 3_000L), f.tabTimes())
        step(1.seconds)
        f.flip(this, snap())
        step(1.seconds)
        f.sent.last().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        assertNull("unconfirmed", f.entry().online200At)
        step(3.seconds)
        assertEquals("cold again at t=8", listOf(0L, 3_000L, 8_000L), f.tabTimes())
        step(10.seconds)
        assertEquals(listOf(0L, 3_000L, 8_000L), f.tabTimes())
    }

    /** An answer whose completion configuration was unavailable (protected closed) schedules no check of its own. */
    @Test fun T20_anUnavailableConfigurationSchedulesNoCheck() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.protectedOpen = false
        f.fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "e2", TopicGrantToken(8L))
        f.snapshot = snap(token = 8L, epoch = "e2", krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertNull(f.entry().online200At)
        val sends = f.tabTimes()
        step(10.seconds)
        assertEquals("no check follows", sends, f.tabTimes())
    }

    // --- S4 RT03b-1b P09: unconfirmed 200s under a recovery budget -------------------------------------------------------
    //
    // Agreed in R4c/S4 rt03b1b_api_agreed.r3 (row P09). With a recorder, a 1d key's unconfirmed answers belong to its recovery
    // budget: they climb its ladder like failures and stop at six; the key starts no flip of its own while the budget waits or
    // after it stopped, also for an outside request's unconfirmed answer. Each request here starts under a capability hold
    // (no KRX epoch in its capture) and lands after the capability opened, so its 200 is unconfirmed (T19's construction).

    /** P09: unconfirmed 200s run the budget's six rounds at 0/3/9/21/45/93 s, and no flip follows them or a later one. */
    @Test fun P09_unconfirmedAnswersRunTheBudgetAndStartNoFlip() = flipTest {
        val day = GraphKey("usd", GraphPeriod.ONE_DAY)
        val f = Fixture(this, withRecorder = true)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.start(); f.coordinator.onActivated(day); runCurrent()
        val due = listOf(0, 3, 9, 21, 45, 93)
        for ((i, at) in due.withIndex()) {
            if (i > 0) step((at - due[i - 1]).seconds)
            assertEquals("round ${i + 1}", due.take(i + 1).map { it * 1000L }, f.tabTimes(day))
            f.flip(this, snap())
            f.sent.last().tab.complete(ok(onlineDto(1500.0 + i, period = GraphPeriod.ONE_DAY))); runCurrent()
            assertNull("unconfirmed", f.entry(day).online200At)
            f.flip(this, snap(capabilityBlocks = HELD))
        }
        step(300.seconds)
        assertEquals("stopped at six", due.map { it * 1000L }, f.tabTimes(day))

        f.coordinator.onRefreshRequested(force = true); runCurrent()
        assertEquals(7, f.tabTimes(day).size)
        f.flip(this, snap())
        f.sent.last().tab.complete(ok(onlineDto(1600.0, period = GraphPeriod.ONE_DAY))); runCurrent()
        assertNull("premise: unconfirmed again", f.entry(day).online200At)
        step(30.seconds)
        assertEquals("no flip after the outside answer", 7, f.tabTimes(day).size)
    }

    /** P09b: a usd 1d budget does not hold back usd 3m's flip check: long periods keep their flip. */
    @Test fun P09b_aLongPeriodKeepsItsFlipBesideA1dBudget() = flipTest {
        val day = GraphKey("usd", GraphPeriod.ONE_DAY)
        val f = Fixture(this, withRecorder = true)
        f.start(); f.coordinator.onActivated(day); runCurrent()
        f.sent.last().tab.complete(status(503)); runCurrent()
        f.coordinator.onActivated(KEY); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        assertTrue("premise: confirmed", f.entry().online200At != null)
        step(1.seconds)
        f.flip(this, snap(capabilityBlocks = HELD))
        assertNull("premise: the flip stripped the stamp", f.entry().online200At)
        step(3.seconds)
        assertEquals("3m's flip check at +3 s", listOf(0L, 4_000L), f.tabTimes(KEY))
    }

    // --- S4 RT01-B2b-1: retiring a KRX capability epoch ---------------------------------------------------------------
    //
    // Agreed in R4c/S4 rt01b2b_api_agreed.r2 (r1 proposal plus Codex's replacements). retireCapabilities runs on the loop's
    // serial executor between events. Its caller has established that every selected named epoch really ended: it is never
    // reused or published again. With the real issuer a K rotation also ends the old use lifetime - standing compares the
    // issued context with the record, and losing it counts one user invalidation - and this fixture's snapshot follows that
    // rule, so [rotate] below is the issuer's rotation as published inside its lock, before any new grant. The live epoch is
    // the record's (owner, K) from one snapshot read, whatever the capability's admission: a hold is not a retirement.
    //
    // Under that premise some effects are visible only beyond it, so two parts leave it on purpose and say so: B2b05's
    // `admitted` (the port starts no seed, its discarded attempt lets a normal start retry) and WriteTest's B2b06 (a request
    // keeps its general half alone). The real issuer's rotation, renewal and re-approval are PremiumAccessTopicSnapshotTest's
    // graphB2b01 (HTTP, the null-K capture) and graphB2b02 (a pending FillEmpty seed); a pending SupplementOccupied across the
    // rotation is B2b05's `supplement` over this fixture, whose rejection takes the same gate path (the dead lifetime binds
    // nothing). Recorded as equivalent for the mutation battery (rt01b2b1 contract): publishState and publish behave alike
    // between events, since no write preparation is pending then (B2a's (d)).

    private val CK1 = GraphCapabilityScope("u1", "K1")
    private val CK2 = GraphCapabilityScope("u1", "K2")
    private val JPY = GraphKey("jpy", GraphPeriod.THREE_MONTHS)

    /** The P3-i adapter's sweep for owner u1: every epoch but [keep]; with no keep, every one. */
    private fun sweep(keep: String?): (GraphCapabilityScope) -> Boolean =
        { it.uid == "u1" && (keep == null || it.krxCapabilityEpoch != keep) }

    /** What one retirement passes to its selector - each candidate once - and what it answers. */
    private fun Fixture.retireK(selects: (GraphCapabilityScope) -> Boolean): Pair<Set<GraphCapabilityScope>, GraphRuntimeRetirement> {
        val seen = mutableListOf<GraphCapabilityScope>()
        val result = coordinator.retireCapabilities { seen += it; selects(it) }
        assertEquals("each candidate once: $seen", seen.size, seen.toSet().size)
        return seen.toSet() to result
    }

    /** The issuer's K1 -> K2 rotation inside its lock: K1's token stops standing, one invalidation, no new grant yet. */
    private fun Fixture.rotate() {
        snapshot = snap(krx = "K2", issuedRecord = AccessFence("u1", "e1", "K1"),
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L)
    }

    private fun GraphEntry.withoutKrx() = copy(tab = tab.copy(
        graph = tab.graph.copy(series = tab.graph.series.filterNot { it.seriesId.startsWith("krx.") }),
        inProgress = tab.inProgress.filterKeys { !it.startsWith("krx.") }
    ))

    private fun Fixture.seedReasons() = diagnostics.filter { it.component == GraphV2DiskComponent.GENERAL }.map { it.reason }

    /**
     * B2b01 (S4 RT01-B2b-1): the record's epoch is live and always a candidate, even with nothing held for it; selecting it
     * refuses the call before anything goes. One snapshot read names it, with the record's own owner - not the binding's, the
     * grant's or the session's, and with or without a session. A capability hold does not make it retirable: a request
     * started with it still takes its KRX half when the capability reopens.
     */
    @Test fun B2b01_theRecordsEpochIsRefused_alsoWhileHeld() = flipTest {
        val idle = Fixture(this)
        assertEquals("live though nothing is held", setOf(CK1) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, idle.retireK { true })

        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        val before = f.state
        assertEquals(setOf(CK1) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, f.retireK { true })
        assertEquals("nothing goes", before, f.state)
        assertEquals(listOf(ONLINE, KRX_SERIES), f.exposedIds())

        f.atAccessRead(f.accessReads + 2) { f.snapshot = snap(uid = "u2", krx = "K9") }
        assertEquals("one snapshot read", setOf(CK1) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, f.retireK { it == CK1 })
        f.clearAccessHooks()
        f.snapshot = snap(uid = "u2").let { it.copy(facts = it.facts.copy(recordFence = AccessFence("u1", "e1", "K1"))) }
        f.fence = TopicSessionFence(AuthIdentityFence("u2", 1L), "e1", TopicGrantToken(7L))
        assertEquals("the record's owner, not the binding's, the grant's or the session's",
            setOf(CK1) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, f.retireK { it == CK1 })
        f.fence = null
        assertEquals("nor with no session", setOf(CK1) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, f.retireK { it == CK1 })
        f.snapshot = snap()
        f.fence = FENCE
        assertEquals(before, f.state)

        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val inFlight = f.sent.last()
        f.flip(this, snap(capabilityBlocks = HELD))
        val held = f.state
        assertEquals("held, still live", setOf(CK1) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, f.retireK { it == CK1 })
        assertEquals(held, f.state)
        step(1.seconds); f.flip(this, snap())
        inFlight.tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("the request kept its epoch", listOf(ONLINE, KRX_SERIES), f.ids())
        assertEquals(listOf(ONLINE, KRX_SERIES), f.exposedIds())
        assertEquals(emptyList<Throwable>(), f.failures)
        assertEquals(emptyList<Throwable>(), idle.failures)
    }

    /**
     * B2b02 (S4 RT01-B2b-1, B03): retired before the request owner hears of the rotation, K1's half leaves every key - the
     * active one with its current bucket, an inactive long period and an inactive tab - at once and nothing else does:
     * the general halves, the stamps, the catalog and the configuration stay. The invalidated reader sees nothing; the kept
     * configuration lets the next sync clear the stamps as before, and nothing of K1 is left to retire.
     */
    @Test fun B2b02_aRetiredEpochLeavesEveryKeyBeforeTheRotationIsHeard() = flipTest {
        val f = Fixture(this)
        f.tabs = listOf("usd", "jpy")
        f.adopt(this, onlineDto(1400.0, period = GraphPeriod.ONE_YEAR), key = OTHER)
        f.coordinator.onActivated(JPY); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1200.0, tab = "jpy"))); runCurrent()
        f.coordinator.onActivated(KEY); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1500.0, inProgress = true))); runCurrent()
        listOf(KEY, OTHER, JPY).forEach { assertEquals("premise: $it", listOf(ONLINE, KRX_SERIES), f.ids(it)) }
        assertEquals("premise", setOf(ONLINE, KRX_SERIES), f.entry().tab.inProgress.keys)
        val before = f.state

        f.rotate()
        assertEquals("no token stands, yet the record's epoch is live", setOf(CK2, CK1) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED,
            f.retireK { it == CK2 })
        assertEquals(before, f.state)
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, f.retireK(sweep("K2")))
        assertEquals("only the KRX half goes, from every key", before.copy(entries = before.entries.mapValues { it.value.withoutKrx() }),
            f.state)
        assertEquals(setOf(ONLINE), f.entry().tab.inProgress.keys)
        assertNull("the invalidated reader sees nothing", f.exposed())
        assertEquals("nothing of K1 is left", setOf(CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, f.retireK(sweep("K2")))
        assertEquals("the record's closed epoch alone is still refused", setOf(CK2) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED,
            f.retireK { true })

        f.rebind(token = 8L, krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        assertNull("the next sync clears the stamp", f.entry().online200At)
        assertNull(f.entry(OTHER).online200At)
        listOf(KEY, OTHER, JPY).forEach { assertEquals(listOf(ONLINE), f.ids(it)) }
        assertEquals(listOf(ONLINE), f.exposedIds())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * B2b03 (S4 RT01-B2b-1, B03): the port needs none of what the configuration strip needs. It removes K1's half when the
     * rotation was heard without a context (the old grant acquires nothing), when a new context's binding is closed, when
     * the record names no epoch at all - then nothing is live - and when the slot was adopted before any configuration was
     * set: its epoch is the slot's own.
     */
    @Test fun B2b03_itWorksWhereTheConfigurationStripDoesNot() = flipTest {
        val noContext = Fixture(this)
        noContext.adopt(this, onlineDto(1500.0))
        noContext.rotate()
        noContext.coordinator.onContextChanged(); runCurrent()
        assertEquals("premise: the strip did not run", listOf(ONLINE, KRX_SERIES), noContext.ids())
        val stamp = noContext.entry().online200At
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, noContext.retireK(sweep("K2")))
        assertEquals(listOf(ONLINE), noContext.ids())
        assertEquals(1500.0, noContext.rate())
        assertEquals(stamp, noContext.entry().online200At)

        val closed = Fixture(this)
        closed.adopt(this, onlineDto(1500.0))
        closed.protectedOpen = false
        closed.rebind(token = 8L, krx = "K2", invalidations = 4L)
        closed.coordinator.onContextChanged(); runCurrent()
        assertEquals("premise: the strip did not run", listOf(ONLINE, KRX_SERIES), closed.ids())
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, closed.retireK(sweep("K2")))
        assertEquals(listOf(ONLINE), closed.ids())

        val sealed = Fixture(this)
        sealed.adopt(this, onlineDto(1500.0))
        sealed.snapshot = snap(krx = null, issuedRecord = AccessFence("u1", "e1", "K1"),
            capabilityBlocks = setOf(TopicAccessBlock.DERIVED_SEAL), invalidations = 4L)
        assertEquals("no epoch, nothing live", setOf(CK1) to GraphRuntimeRetirement.REMOVED, sealed.retireK { it == CK1 })
        assertEquals(listOf(ONLINE), sealed.ids())

        val unset = Fixture(this)
        unset.atGateRead(1) { unset.snapshot = snap(userBlocks = setOf(TopicAccessBlock.NOT_GRANTED)) }
        unset.atGateRead(2) { unset.snapshot = snap() }
        unset.adopt(this, onlineDto(1500.0))
        assertEquals("premise: K1's half, adopted with no configuration to compare", listOf(ONLINE, KRX_SERIES), unset.ids())
        unset.rotate()
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, unset.retireK(sweep("K2")))
        assertEquals(listOf(ONLINE), unset.ids())
        listOf(noContext, closed, sealed, unset).forEach { assertEquals(emptyList<Throwable>(), it.failures) }
    }

    /**
     * B2b05 (S4 RT01-B2b-1, B03): a seed or a supplement out for K1 is given up - its own registration, not just its KRX half:
     * its answer applies nothing and ends as an expired owner. A later normal start takes a new binding under the new grant,
     * and K1's stored half is not joined to it. The seed and a spent supplement's attempt are candidates of their own. Beyond
     * the premise, with the old use still admitted: the port itself starts no seed, and its discarded attempt lets a normal
     * start retry. A seed or a supplement attempt made under a closed configuration owns no KRX and is no candidate.
     */
    @Test fun B2b05_aSeedOrSupplementOutForTheEpochIsGivenUp() = flipTest {
        val c = components(serverTab(1300.0))
        val fill = Fixture(this)
        fill.put(c)
        fill.store.hold[1] = CompletableDeferred()
        fill.start(); fill.coordinator.onActivated(KEY); runCurrent()
        assertEquals("premise: the seed is out", 1, fill.store.generalReads.size)
        fill.rotate()
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, fill.retireK(sweep("K2")))
        fill.store.release(1); runCurrent()
        assertNull("its answer applies nothing", fill.state.entries[KEY])
        assertEquals(listOf("Seed ownership expired"), fill.seedReasons())
        assertEquals("the port starts no seed", 1, fill.store.generalReads.size)
        assertEquals(setOf(CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, fill.retireK(sweep("K2")))
        fill.rebind(token = 8L, krx = "K2", invalidations = 4L)
        fill.coordinator.onContextChanged(); runCurrent()
        assertEquals("a new start, general only", GraphEntry(joined(c, withKrx = false), null), fill.state.entries[KEY])

        val supplement = Fixture(this)
        supplement.put(c)
        supplement.snapshot = snap(capabilityBlocks = HELD)
        supplement.start(); supplement.coordinator.onActivated(KEY); runCurrent()
        assertEquals("premise: seeded general only", GraphEntry(joined(c, withKrx = false), null), supplement.state.entries[KEY])
        supplement.store.hold[2] = CompletableDeferred()
        supplement.flip(this, snap())
        assertEquals("premise: the supplement is out", 2, supplement.store.generalReads.size)
        supplement.rotate()
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, supplement.retireK(sweep("K2")))
        supplement.store.release(2); runCurrent()
        assertEquals("its answer applies nothing", GraphEntry(joined(c, withKrx = false), null), supplement.state.entries[KEY])
        assertEquals(listOf("Seed ownership expired"), supplement.seedReasons())
        assertEquals(setOf(CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, supplement.retireK(sweep("K2")))

        // The seed alone holds K1 once its request has failed.
        val alone = Fixture(this)
        alone.put(c)
        alone.store.hold[1] = CompletableDeferred()
        alone.start(); alone.coordinator.onActivated(KEY); runCurrent()
        alone.sent.single().tab.complete(status(503)); runCurrent()
        assertTrue("premise: the request has ended", alone.state.inFlight.isEmpty())
        alone.rotate()
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, alone.retireK(sweep("K2")))
        alone.store.release(1); runCurrent()
        assertEquals(listOf("Seed ownership expired"), alone.seedReasons())

        // A spent supplement that joined no KRX half leaves only its (K1, open) attempt.
        val spent = Fixture(this)
        spent.putGeneral(c.general)
        spent.snapshot = snap(capabilityBlocks = HELD)
        spent.start(); spent.coordinator.onActivated(KEY); runCurrent()
        spent.flip(this, snap())
        assertEquals("premise: the supplement ran and joined nothing", 2, spent.store.generalReads.size)
        assertEquals(GraphEntry(joined(c, withKrx = false), null), spent.state.entries[KEY])
        spent.rotate()
        assertEquals("the attempt is a candidate of its own", setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, spent.retireK(sweep("K2")))
        assertEquals(setOf(CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, spent.retireK(sweep("K2")))

        // Beyond the agreed premise on purpose, as B2b06: the record moves to K2 while the old use stays admitted, so a seed
        // the port started, or a kept attempt, would show.
        val admitted = Fixture(this)
        admitted.put(c)
        admitted.store.hold[1] = CompletableDeferred()
        admitted.start(); admitted.coordinator.onActivated(KEY); runCurrent()
        admitted.snapshot = snap(krx = "K2")
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, admitted.retireK(sweep("K2")))
        runCurrent()
        assertEquals("the port starts no seed, though the kept context could bind one", 1, admitted.store.generalReads.size)
        admitted.store.release(1); runCurrent()
        assertNull(admitted.state.entries[KEY])
        assertEquals(listOf("Seed ownership expired"), admitted.seedReasons())
        admitted.coordinator.onRefreshRequested(); runCurrent()
        assertEquals("the discarded attempt lets a normal start retry", GraphEntry(joined(c, withKrx = false), null),
            admitted.state.entries[KEY])
        // Made under a closed configuration, a seed or a supplement attempt holds no KRX ownership and is no candidate.
        val closedSeed = Fixture(this)
        closedSeed.put(c)
        closedSeed.snapshot = snap(capabilityBlocks = HELD)
        closedSeed.store.hold[1] = CompletableDeferred()
        closedSeed.start(); closedSeed.coordinator.onActivated(KEY); runCurrent()
        assertEquals("premise: the closed seed is out", 1, closedSeed.store.generalReads.size)
        closedSeed.rotate()
        assertEquals(setOf(CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, closedSeed.retireK(sweep("K2")))
        closedSeed.store.release(1); runCurrent()
        assertEquals("it ends at the closed gate, not as a discarded owner", listOf("Seed exposure admission is closed"),
            closedSeed.seedReasons())

        val closedAttempt = Fixture(this)
        closedAttempt.adopt(this, onlineDto(1500.0))
        closedAttempt.flip(this, snap(capabilityBlocks = HELD))
        assertEquals("premise: the closed supplement ran", 2, closedAttempt.store.generalReads.size)
        closedAttempt.rotate()
        assertEquals(setOf(CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, closedAttempt.retireK(sweep("K2")))
        listOf(fill, supplement, alone, spent, admitted, closedSeed, closedAttempt).forEach {
            assertEquals(emptyList<Throwable>(), it.failures)
        }
    }

    /**
     * B2b07 (S4 RT01-B2b-1): K1 left only in a request released by the new grant; K2's half adopted since, a K2 supplement
     * and a K2 request still out. Selecting the live K2 with K1 refuses everything; a selector that throws on its second call
     * changes nothing; the K1 capture then goes, K2's half and stamp stay, and a repeat removes nothing. The released
     * request's late answer applies nothing, K2's supplement ends unharmed, and K2's request keeps its epoch.
     */
    @Test fun B2b07_anUnselectedEpochStays_andAThrowingSelectorChangesNothing() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val old = f.sent.last()
        f.store.hold[2] = CompletableDeferred() // K2's supplement stays out over the ports
        f.rotate()
        f.rebind(token = 8L, krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        step(3.seconds)
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("premise: K2's half", listOf(ONLINE, KRX_SERIES), f.exposedIds())
        assertEquals("premise: K2's supplement is out", 2, f.store.generalReads.size)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        val k2Out = f.sent.last()
        assertTrue("premise: a request started with K2 is out", k2Out !== old && KEY in f.state.inFlight)
        val adopted = f.state

        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, f.retireK { true })
        assertEquals(adopted, f.state)
        val boom = IllegalStateException("selector")
        var calls = 0
        assertEquals(boom, assertThrows(IllegalStateException::class.java) {
            f.coordinator.retireCapabilities { if (++calls == 2) throw boom else it == CK1 }
        })
        assertEquals(adopted, f.state)

        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, f.retireK(sweep("K2")))
        assertEquals("K2's half and stamp stay", adopted, f.state)
        assertEquals(listOf(ONLINE, KRX_SERIES), f.exposedIds())
        assertEquals(setOf(CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, f.retireK(sweep("K2")))
        old.tab.complete(ok(onlineDto(1700.0))); runCurrent()
        assertEquals("the late answer applies nothing", adopted, f.state)
        f.store.release(2); runCurrent()
        assertEquals("K2's supplement was not given up", emptyList<String>(), f.seedReasons())
        k2Out.tab.complete(ok(onlineDto(1800.0))); runCurrent()
        assertEquals("K2's request kept its epoch", listOf(ONLINE, KRX_SERIES), f.ids())
        assertEquals("and confirms K2", f.at(3_000), f.entry().online200At)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * B2b09 (S4 RT01-B2b-1): both axes end together (user epoch e1 -> e2 and K1 -> K2, the session already on e2). Retiring
     * e1 and K1 in either order leaves the same state, the late answer applies nothing, and neither port finds anything
     * after it. Return values are not compared: the user retirement leaves a released request's K capture to the other.
     */
    @Test fun B2b09_bothAxesInEitherOrderLeaveTheSameState() = flipTest {
        val e1 = GraphDataScope("u1", "e1")
        fun TestScope.ended(userFirst: Boolean): GraphRequestState {
            val f = Fixture(this)
            f.adopt(this, onlineDto(1500.0))
            f.coordinator.onRefreshRequested(force = true); runCurrent()
            val old = f.sent.last()
            f.fence = TopicSessionFence(AuthIdentityFence("u1", 1L), "e2", TopicGrantToken(8L))
            f.snapshot = snap(epoch = "e2", krx = "K2", issuedRecord = AccessFence("u1", "e1", "K1"),
                capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L)
            val user = { assertEquals(GraphRuntimeRetirement.REMOVED, f.coordinator.retireScopes { it == e1 }) }
            val capability = {
                assertEquals("userFirst=$userFirst: the released request's K1 capture goes",
                    setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, f.retireK(sweep("K2")))
            }
            if (userFirst) { user(); capability() } else { capability(); user() }
            val retired = f.state
            old.tab.complete(ok(onlineDto(1700.0))); runCurrent()
            assertEquals("userFirst=$userFirst: the late answer applies nothing", retired, f.state)
            assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, f.coordinator.retireScopes { it == e1 })
            assertEquals(setOf(CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, f.retireK(sweep("K2")))
            assertEquals(emptyList<Throwable>(), f.failures)
            return retired
        }
        val userFirst = ended(userFirst = true)
        assertEquals(GraphRequestState(), userFirst)
        assertEquals(userFirst, ended(userFirst = false))
    }

    /**
     * B2b08 (S4 RT01-B2b-1): two ended epochs - K1 in a released request, K2's half adopted and unheard ended in turn - and K3
     * live. A selector that throws after it has selected changes nothing. An exact selection of K1 leaves K2's half, then an
     * exact selection of K2 takes it.
     */
    @Test fun B2b08_anExactSelectionKeepsAnotherEndedEpoch() = flipTest {
        val ck3 = GraphCapabilityScope("u1", "K3")
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.rotate()
        f.rebind(token = 8L, krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        step(3.seconds)
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("premise: K2's half", listOf(ONLINE, KRX_SERIES), f.exposedIds())
        f.snapshot = snap(token = 8L, krx = "K3", issuedRecord = AccessFence("u1", "e1", "K2"),
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 5L)
        val before = f.state
        val (all, none) = f.retireK { false }
        assertEquals(setOf(ck3, CK2, CK1) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, all to none)

        val boom = IllegalStateException("after a selection")
        var calls = 0
        assertEquals(boom, assertThrows(IllegalStateException::class.java) {
            f.coordinator.retireCapabilities { if (++calls == all.size) throw boom else it != ck3 }
        })
        assertEquals("nothing goes before the throw", before, f.state)
        assertEquals(setOf(ck3, CK2, CK1) to GraphRuntimeRetirement.REMOVED, f.retireK { it == CK1 })
        assertEquals("K2's half is not selected", before, f.state)
        assertEquals(setOf(ck3, CK2) to GraphRuntimeRetirement.NOTHING_TO_REMOVE, f.retireK { it == CK1 })
        assertEquals(setOf(ck3, CK2) to GraphRuntimeRetirement.REMOVED, f.retireK { it == CK2 })
        assertEquals(before.copy(entries = before.entries.mapValues { it.value.withoutKrx() }), f.state)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * B2b10 (S4 RT01-B2b-1): under the live K2, a failed re-check leaves a failure and a cold retry. Retiring K1 (held by a
     * released request) keeps both: the failure stays published and the cold retry goes out on time.
     */
    @Test fun B2b10_theLiveEpochsFailureAndColdRetryStay() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.rotate()
        f.rebind(token = 8L, krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        step(3.seconds)
        f.sent.last().tab.complete(status(503)); runCurrent()
        val failed = f.state
        assertTrue("premise: a failure", KEY in failed.failures)
        val sends = f.sent.size
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, f.retireK(sweep("K2")))
        assertEquals("the failure stays", failed, f.state)
        step(3.seconds)
        assertEquals("the cold retry still goes out", sends + 1, f.sent.size)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * B2b11 (S4 RT01-B2b-1): under the live K2, a capability hold arms the re-check of the active key. Retiring K1 (held by a
     * released request) keeps it: the re-check goes out three seconds after the hold.
     */
    @Test fun B2b11_theLiveEpochsReCheckStays() = flipTest {
        val f = Fixture(this)
        f.adopt(this, onlineDto(1500.0))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.rotate()
        f.rebind(token = 8L, krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        step(3.seconds)
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        val sends = f.tabTimes()
        f.flip(this, snap(token = 8L, krx = "K2", invalidations = 4L, capabilityBlocks = HELD))
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, f.retireK(sweep("K2")))
        step(3.seconds)
        assertEquals("the re-check armed before the port still goes out", sends + 6_000L, f.tabTimes())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * B2b12 (S4 RT01-B2b-1, D6): the port keeps the capability generation. A stored answer seeded under K1 (its general half;
     * the disk holds K2's half, not K1's) waits for K2's supplement after the new grant, while the K1 request released by
     * that grant still holds K1. Retiring K1 meanwhile leaves the supplement valid, and it joins K2's stored half.
     */
    @Test fun B2b12_anUnselectedEpochsSupplementStaysValid() = flipTest {
        val c = components(serverTab(1300.0), krx = "K2")
        val f = Fixture(this)
        f.put(c)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals("premise: seeded general only", GraphEntry(joined(c, withKrx = false), null), f.state.entries[KEY])
        f.store.hold[2] = CompletableDeferred()
        f.rotate()
        f.rebind(token = 8L, krx = "K2", invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        assertEquals("premise: K2's supplement is out", 2, f.store.generalReads.size)
        assertEquals(setOf(CK2, CK1) to GraphRuntimeRetirement.REMOVED, f.retireK(sweep("K2")))
        f.store.release(2); runCurrent()
        assertEquals("the supplement joins K2's half", GraphEntry(joined(c), null), f.state.entries[KEY])
        assertEquals(emptyList<String>(), f.seedReasons())
        assertEquals(emptyList<Throwable>(), f.failures)
    }
}
