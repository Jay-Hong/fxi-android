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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S4 B1b-2c-1 contract r3: the request owner re-gates its slots when the capability configuration changes.
 *
 * Oracles: iOS a36682f GraphV2ViewModel.swift :270-321 (setKrxVisible: keep a seed from the disk raw answer before clearing,
 * clear freshness, purge KRX, show the re-gated seed unconfirmed) and :423-458 (only an online 200 stamps freshness);
 * ANDROID_V2_PLAN.md :1288 (a KRX withdrawal keeps the general half). Design: R4c/S4 b1b2c_design_codex.r1 as trimmed by
 * b1b2c_review_claude.r1 and b1b2c_verdict_codex.r1 (T1-T5): the gate reads the record's capability configuration (record
 * epoch, allowed) from the admitted snapshot; a configuration change in the same data scope, found on a context notice even
 * when the request context is unchanged, keeps every occupied key's general half, removes its KRX half and clears its stamp;
 * only the active key re-reads its own stored answer, and only a stored answer whose GENERAL responseId equals the occupied
 * slot's (at the start and at the completion of the read) and whose KRX half joins structurally supplements the slot, without
 * a stamp; a revision alone changes nothing. The three-second re-check, its ownership and the start-capture shortfall are
 * B1b-2c-2.
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
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private inner class Fixture(test: TestScope) {
        init { opened += this }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = FENCE
        var protectedOpen = true
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
        val root: File = folder.newFolder()
        val files = RecordingFiles()
        val store = HeldStore(FileGraphV2DiskStore(root, codec, files, StandardTestDispatcher(test.testScheduler)))
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
            ): AuthenticatedHttpResponse<GraphV2CatalogResponse> = ok(catalog())

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

        val coordinator = GraphV2RequestCoordinator(
            fetcher = fetcher,
            owners = owners,
            currentAccessFence = { fence },
            uses = uses,
            scope = scope,
            clock = AppClock { NOON + (test.testScheduler.currentTime - base).milliseconds },
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

    private fun <T> status(code: Int): AuthenticatedHttpResponse<T> {
        val headers = Headers.headersOf()
        return AuthenticatedHttpResponse(code, headers, null,
            AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP), byteArrayOf(1))
    }

    private fun catalog() = GraphV2CatalogResponse(
        tabs = listOf(GraphV2CatalogTab("usd", "usd", emptyMap(),
            listOf("1d", "1w", "3m", "1y").associateWith { GraphV2CatalogPeriod(ALL_IDS, ALL_IDS) })),
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = 172800
    )

    /** An online answer with a general and a KRX series; [krx] false leaves the KRX series out, [empty] every series. */
    private fun onlineDto(rate: Double, period: GraphPeriod = GraphPeriod.THREE_MONTHS, krx: Boolean = true, empty: Boolean = false) =
        GraphV2TabResponse(
            tab = "usd",
            period = period.code,
            series = if (empty) emptyList() else listOfNotNull(
                GraphV2Series(ONLINE, ONLINE, "krw", "KRW", 2, listOf(GraphV2Point(NOON - 1.days, rate, "x")),
                    GraphV2Provenance(false, emptyList()), null),
                if (!krx) null else GraphV2Series(KRX_SERIES, KRX_SERIES, "krw", "KRW", 1,
                    listOf(GraphV2Point(NOON - 1.days, rate + 5, "krx")), GraphV2Provenance(false, emptyList()), null)
            ),
            metadata = GraphV2Metadata(NOON - 1.hours, "1d", GraphV2Range("2026-07-05", "2026-10-05"))
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
     * successful read keeps its stamp. That read sets the new scope's comparison base; it is not a change from the old scope.
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
        assertEquals("adopted in the new scope", NOON, f.entry().online200At)
        val reads = f.store.generalReads.size
        f.protectedOpen = true
        f.coordinator.onContextChanged(); runCurrent()
        assertEquals("the first successful read is a base, not a change", NOON, f.entry().online200At)
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
}
