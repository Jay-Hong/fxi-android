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
import com.jay.fxi.domain.model.GraphV2InProgress
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
 * Claude-owned S4 B1b-2a-1 contract r4: the request owner seeds an empty slot from the Graph V2 disk store.
 * S4 B1b-2a-2 contract r1 (rows E01-E10) adds the protected exposure: protectedEntry(key) answers only through the live gate,
 * from component slots kept beside the entries — a disk seed's own pair, or an online 200 split with the capability epoch
 * bound when its request started (none when that bind failed). Design: b1b2a_design_codex.r1 §4·§6 and b1b2a_verdict_codex.r1
 * R3 (two online rows) and R4 (a failed start bind keeps a GENERAL-only slot).
 *
 * Oracles: ANDROID_V2_PLAN.md S4 :1288-1290 (a stored answer is shown unconfirmed under fresh approval; only an online 200
 * records freshness; no disk age), :1318-1319 and :1338 (a late answer never lands); iOS a36682f GraphV2ViewModel.swift
 * :423-458 (the cache seeds an empty slot, the online fetch still goes out, an online success stamps freshness) and
 * :170-176 / :474-479 (a cached tab does not end the retry owner or count as a confirmed midnight).
 * Design: R4c/S4 b1b2a_design_codex.r1 (ports, DiskSeedFinished, seed ownership, read order GENERAL then KRX, the six-step
 * application, failures as values) as trimmed by b1b2a_review_claude.r1 / b1b2a_verdict_codex.r1 (the null-stamp occupancy
 * boundary and the online exposure rows move to 2c and 2a-2).
 *
 * The fixture uses the real request owner, the real FileGraphV2DiskStore over a temporary directory with the real JSON codec,
 * and the real GraphV2AccessGate. The use authority is the real SnapshotTopicUseAuthority over the same fake published
 * snapshot the gate reads; its facts follow the issuer (standing from the issued and current contexts, one invalidation
 * per loss of the user axis or the standing token). A decorator around the store records each read result and can hold a
 * GENERAL result after the real read returned, so a completion arrives later than the change a row makes.
 * Rows C01-C17 read raw state.entries; rows E01-E10 read protectedEntry.
 */
class GraphV2RequestCoordinatorCacheTest {

    @get:Rule val folder = TemporaryFolder()

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        /** 23:50 KST on 2026-10-05. */
        val BEFORE_MIDNIGHT: Instant = Instant.parse("2026-10-05T14:50:00Z")
        val KEY = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val GKEY = GraphV2GeneralKey("u1", "e1", "usd", GraphPeriod.THREE_MONTHS.code)
        val FENCE = TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", TopicGrantToken(7L))
        val IDS = listOf("hana.usd-krw", "krx.usd-krw-futures", "kb.usd-krw")
        const val ONLINE = "investing.usd-krw"
        const val KRX_SERIES = "krx.usd-krw-futures"
        val ALL_IDS = IDS + ONLINE
    }

    // --- the published topic access ---------------------------------------------------------------------------

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
        emptyPoints: Boolean = false,
        fetchedAt: Instant = NOON - 1.hours,
        inProgress: Map<String, GraphV2InProgress> = emptyMap()
    ) = GraphV2Tab(
        "usd", GraphPeriod.THREE_MONTHS, "1d", fetchedAt,
        FreeGraph("1d", ids.mapIndexed { i, id ->
            FreeGraphSeries(id, if (emptyPoints) emptyList() else listOf(FreeGraphPoint(NOON - 1.days, rate + i, rate + i + 1, rate + i - 1, "x")),
                id, "krw", "KRW", 2)
        }, "2026-07-05", "2026-10-05"),
        inProgress
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

    /** The real file boundary, recording reads and able to refuse one. */
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

    /** The real store, with each read result recorded and a GENERAL result held after the real read when asked. */
    private class HeldStore(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
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

    private class Sent(val key: GraphKey, val admittedAtSend: Boolean, val atMs: Long) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
    }

    /** Every fixture a row opened; closed even when an assertion fails, or a midnight timer would run the scheduler forever. */
    private val opened = mutableListOf<Fixture>()

    private fun cacheTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private inner class Fixture(test: TestScope, start: Instant = NOON, cache: Boolean = true) {
        init { opened += this }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = FENCE
        var liveIdentity: AuthIdentityFence? = null
        var protectedOpen = true
        private var revision = 100L
        var snapshot: TopicAccessSnapshot = snap()
            set(value) {
                revision += 1
                field = value.copy(revision = revision)
            }
        var acquires = 0
        /** Fault injection: the diagnostic observer throws, or the gate's snapshot read throws (the use authority is unaffected). */
        var observerThrows = false
        var gateThrows = false
        /** Cancels the request owner's scope at the next clock read, which is the next event's handling. */
        var cancelAtNextEvent = false
        val root: File = folder.newFolder()
        val files = RecordingFiles()
        val store = HeldStore(FileGraphV2DiskStore(root, codec, files, StandardTestDispatcher(test.testScheduler)))
        val diagnostics = mutableListOf<GraphV2SeedDiagnostic>()
        val failures = mutableListOf<Throwable>()
        val sent = mutableListOf<Sent>()
        var autoTab: ((Sent) -> Unit)? = null

        val uses = object : TopicUseAuthority {
            private val inner = SnapshotTopicUseAuthority { snapshot }
            override fun acquire(fence: TopicSessionFence): TopicUseLifetime? {
                acquires++
                return inner.acquire(fence)
            }
            override fun admits(lifetime: TopicUseLifetime): Boolean = inner.admits(lifetime)
        }

        val owners = object : GraphOwnerSource {
            override fun currentIdentity(): AuthIdentityFence? = liveIdentity ?: fence?.identity
            override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
                if (currentIdentity() != expected) throw AuthIdentityChangedException()
                return AuthSnapshot(expected.uid, expected.authGeneration, "token")
            }
        }

        val gate = GraphV2AccessGate(
            currentIdentity = { owners.currentIdentity() },
            currentAccessFence = { fence },
            snapshot = { if (gateThrows) throw IllegalStateException("gate input") else snapshot },
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
                autoTab?.invoke(s)
                return s.tab.await()
            }
        }

        val coordinator = GraphV2RequestCoordinator(
            fetcher = fetcher,
            owners = owners,
            currentAccessFence = { fence },
            uses = uses,
            protectedAdmission = { true },
            scope = scope,
            clock = AppClock {
                if (cancelAtNextEvent) {
                    cancelAtNextEvent = false
                    scope.cancel()
                }
                start + (test.testScheduler.currentTime - base).milliseconds
            },
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { failures += it },
            cachePorts = if (!cache) null else GraphV2CachePorts(store = store, gate = gate, onSeedDiagnostic = {
                diagnostics += it
                if (observerThrows) throw IllegalStateException("observer")
            })
        )

        fun exposed() = coordinator.protectedEntry(KEY)

        val state get() = coordinator.state.value
        fun tabs() = sent.filter { it.admittedAtSend }
        fun tabTimes() = tabs().map { it.atMs }

        /** The live session and the issuer move to another context together; the request owner is not told. */
        fun rebind(uid: String, gen: Long, epoch: String, token: Long, krx: String = "K1", generation: Long = 10L, invalidations: Long = 3L) {
            fence = TopicSessionFence(AuthIdentityFence(uid, gen), epoch, TopicGrantToken(token))
            liveIdentity = null
            snapshot = snap(token = token, uid = uid, gen = gen, epoch = epoch, krx = krx, generation = generation, invalidations = invalidations)
        }

        fun start() = coordinator.start()
        fun close() = scope.cancel()
    }

    // --- responses ---------------------------------------------------------------------------------------------

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    private fun <T> status(code: Int, retryAfter: String? = null): AuthenticatedHttpResponse<T> {
        val headers = if (retryAfter == null) Headers.headersOf() else Headers.headersOf("Retry-After", retryAfter)
        return AuthenticatedHttpResponse(code, headers, null,
            AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP), byteArrayOf(1))
    }

    /** Every period of usd lists every series a row uses, valid for two days. */
    private fun catalog() = GraphV2CatalogResponse(
        tabs = listOf(GraphV2CatalogTab("usd", "usd", emptyMap(),
            listOf("1d", "1w", "3m", "1y").associateWith { GraphV2CatalogPeriod(ALL_IDS, ALL_IDS) })),
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = 172800
    )

    private fun onlineDto(rate: Double) = GraphV2TabResponse(
        tab = "usd",
        period = GraphPeriod.THREE_MONTHS.code,
        series = listOf(GraphV2Series(ONLINE, ONLINE, "krw", "KRW", 2, listOf(GraphV2Point(NOON - 1.days, rate, "x")),
            GraphV2Provenance(false, emptyList()), null)),
        metadata = GraphV2Metadata(NOON - 1.hours, "1d", GraphV2Range("2026-07-05", "2026-10-05"))
    )

    /** An online answer with a general and a KRX series. */
    private fun onlineKrxDto(rate: Double) = GraphV2TabResponse(
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

    private val fail503: (Sent) -> Unit = { it.tab.complete(status(503)) }
    private fun secs(vararg s: Int) = s.map { it * 1000L }

    private fun TestScope.step(d: Duration) {
        advanceTimeBy(d.inWholeMilliseconds)
        runCurrent()
    }

    private fun Fixture.rate() = state.entries.getValue(KEY).tab.graph.series.first().points.first().rate
    private fun Fixture.ids() = state.entries.getValue(KEY).tab.graph.series.map { it.seriesId }

    // --- B04 a stored answer seeds an empty slot, unconfirmed, whatever its age ----------------------------------

    /** An old answer, an empty tab and a series without points each seed the empty slot with no stamp; the online check still goes out. */
    @Test fun C01_aStoredAnswerSeedsAnEmptySlotUnconfirmedWhateverItsAge() = cacheTest {
        for ((label, tab) in listOf(
            "an answer fetched 400 days ago, file untouched since 1970" to serverTab(fetchedAt = NOON - 400.days),
            "an empty tab" to serverTab(ids = emptyList()),
            "series without points" to serverTab(emptyPoints = true)
        )) {
            val f = Fixture(this)
            val c = components(tab)
            f.put(c)
            generalPath(f.root, GKEY).setLastModified(0L)
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            assertEquals(label, GraphEntry(joined(c), null), f.state.entries[KEY])
            assertEquals("$label: the online check still goes out", 1, f.tabs().size)
            assertEquals("$label: a seed writes nothing", emptyList<File>(), f.files.prepares)
            f.close()
        }
    }

    // --- B05 a late seed never lands -----------------------------------------------------------------------------

    /** A read finished under the captured session, completed after the live session moved on: nothing lands; the new context seeds. */
    @Test fun C02_aCompletionAfterTheSessionMovedOnInstallsNothing() = cacheTest {
        for (label in listOf("auth generation", "user epoch", "uid")) {
            val f = Fixture(this)
            f.put(components(serverTab(rate = 1300.0)))
            f.store.hold[1] = CompletableDeferred()
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            assertEquals(label, 1, f.store.generalReads.size)
            when (label) {
                "auth generation" -> f.liveIdentity = AuthIdentityFence("u1", 2L)
                "user epoch" -> f.rebind("u1", 1L, "e2", 8L)
                else -> f.liveIdentity = AuthIdentityFence("u2", 1L)
            }
            f.store.release(1); runCurrent()
            assertNull("$label: the old seed is not installed", f.state.entries[KEY])

            val expected = when (label) {
                "auth generation" -> { f.rebind("u1", 2L, "e1", 8L, generation = 11L); 1300.0 }
                "user epoch" -> { f.put(components(serverTab(rate = 1500.0), key = GKEY.copy(userAccessEpoch = "e2"))); 1500.0 }
                else -> {
                    f.rebind("u2", 1L, "f1", 9L)
                    f.put(components(serverTab(rate = 1600.0), key = GraphV2GeneralKey("u2", "f1", "usd", GraphPeriod.THREE_MONTHS.code)))
                    1600.0
                }
            }
            f.coordinator.onContextChanged(); runCurrent()
            assertEquals("$label: the new context seeds", expected, f.rate(), 0.0)
            f.close()
        }
    }

    /** A user hold that came and went spends the captured lifetime: the completion installs nothing and acquires nothing. */
    @Test fun C03_aUserHoldRoundTripSpendsTheSeed() = cacheTest {
        val f = Fixture(this)
        f.put(components(serverTab()))
        f.store.hold[1] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.snapshot = snap(userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), invalidations = 4L)
        f.snapshot = snap(invalidations = 4L)
        val acquires = f.acquires
        f.store.release(1); runCurrent()
        assertNull(f.state.entries[KEY])
        assertEquals("the completion acquires no lifetime", acquires, f.acquires)
        f.coordinator.onContextChanged(); runCurrent()
        assertEquals("a lifetime taken after the hold seeds", 1300.0, f.rate(), 0.0)
        f.close()
    }

    /** One seed owner per key: an older seed's completion neither lands nor removes the newer owner. */
    @Test fun C04_anOlderSeedNeitherLandsNorEndsTheNewerOne() = cacheTest {
        val f = Fixture(this)
        f.put(components(serverTab(rate = 1300.0)))
        f.store.hold[1] = CompletableDeferred()
        f.store.hold[2] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.put(components(serverTab(rate = 1500.0)))
        f.coordinator.onDeactivated(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals("the newer activation seeds again", 2, f.store.generalReads.size)
        f.store.release(1); runCurrent()
        assertNull("the older seed does not land", f.state.entries[KEY])
        f.store.release(2); runCurrent()
        assertEquals("the newer seed lands", 1500.0, f.rate(), 0.0)
        f.close()
    }

    /** One seed per activation and context: repeated triggers, and a missing file, do not read again; a new activation does. */
    @Test fun C05_oneSeedPerActivationAndContext() = cacheTest {
        val f = Fixture(this)
        f.store.hold[1] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        fun triggers() {
            f.coordinator.onActivated(KEY)
            f.coordinator.onRefreshRequested(force = false)
            f.coordinator.onRefreshRequested(force = true)
            f.coordinator.onContextChanged()
        }
        triggers(); runCurrent()
        assertEquals("while a seed waits", 1, f.store.generalReads.size)
        f.store.release(1); runCurrent()
        assertTrue(f.store.generalReads.single() is GraphV2DiskRead.Absent)
        triggers(); runCurrent()
        assertEquals("after a missing file", 1, f.store.generalReads.size)
        f.coordinator.onDeactivated(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals("a new activation reads again", 2, f.store.generalReads.size)
        f.close()
    }

    /** An online answer adopted while a seed waits stays, stamp and all. */
    @Test fun C06_aSeedNeverReplacesAnAdoptedOnlineAnswer() = cacheTest {
        val f = Fixture(this)
        f.put(components(serverTab(rate = 1300.0)))
        f.store.hold[1] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        val online = f.state.entries.getValue(KEY)
        assertNotNull(online.online200At)
        f.store.release(1); runCurrent()
        assertEquals(online, f.state.entries[KEY])
        f.close()
    }

    // --- B06 a seed is not an online confirmation ----------------------------------------------------------------

    /** Seed then 503, or 503 then seed: the seed stays unconfirmed beside the failure and the cold ladder keeps its times. */
    @Test fun C07_aSeedDoesNotEndTheColdLadder() = cacheTest {
        for (seedFirst in listOf(true, false)) {
            val f = Fixture(this)
            val c = components(serverTab())
            f.put(c)
            if (!seedFirst) {
                f.autoTab = fail503
                f.store.hold[1] = CompletableDeferred()
            }
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            if (seedFirst) {
                assertEquals(GraphEntry(joined(c), null), f.state.entries[KEY])
                assertTrue(!f.sent.single().tab.isCompleted)
                assertTrue(KEY !in f.state.failures)
                f.autoTab = fail503
                fail503(f.sent.single()); runCurrent()
            } else {
                step(1.seconds); f.store.release(1); runCurrent()
            }
            assertEquals("seed first=$seedFirst", GraphEntry(joined(c), null), f.state.entries[KEY])
            step(100.seconds)
            assertEquals("seed first=$seedFirst", secs(0, 3, 9, 21, 45, 93), f.tabTimes())
            assertEquals(GraphEntry(joined(c), null), f.state.entries[KEY])
            assertTrue("the failure is kept", KEY in f.state.failures)
            f.close()
        }
    }

    /** A seeded key is still unconfirmed at midnight: its 00:02 failure goes to the cold ladder, not the confirmed backoff. */
    @Test fun C08_aSeedIsNotAConfirmedMidnight() = cacheTest {
        val f = Fixture(this, BEFORE_MIDNIGHT)
        val c = components(serverTab())
        f.put(c)
        var n = 0
        f.autoTab = { s -> if (n++ == 0) s.tab.complete(status(404)) else fail503(s) }
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals(GraphEntry(joined(c), null), f.state.entries[KEY])
        step(720.seconds); step(300.seconds)
        assertEquals(secs(0, 720, 723, 729, 741, 765, 813), f.tabTimes())
        assertEquals(GraphEntry(joined(c), null), f.state.entries[KEY])
        f.close()
    }

    // --- B08 last-good is kept ---------------------------------------------------------------------------------

    /** A seed outlives graph failures, the TTL and two KST days unchanged; an outside refresh still checks online. */
    @Test fun C09_aSeedOutlivesFailuresAndTime() = cacheTest {
        val f = Fixture(this)
        val c = components(serverTab())
        f.put(c)
        f.autoTab = fail503
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        step(2.days)
        assertEquals(GraphEntry(joined(c), null), f.state.entries[KEY])
        val before = f.tabs().size
        f.coordinator.onRefreshRequested(); runCurrent()
        assertEquals("an outside refresh checks online", before + 1, f.tabs().size)
        assertEquals(GraphEntry(joined(c), null), f.state.entries[KEY])
        f.close()
    }

    // --- B10 the KRX half under the current capability ------------------------------------------------------------

    /** With the capability blocked the general half seeds alone and the KRX half is never asked for. */
    @Test fun C10_aBlockedCapabilityReadsNoKrx() = cacheTest {
        val f = Fixture(this)
        f.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED))
        val c = components(serverTab())
        f.put(c)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals(GraphEntry(joined(c, withKrx = false), null), f.state.entries[KEY])
        assertEquals("no KRX store read", 0, f.store.krxReads.size)
        assertTrue("no KRX file read", f.files.reads.none { isKrx(f.root, it) })
        f.close()

        // K1 captured, then the capability alone is held before the KRX half is read: the seed's own KRX admission check stops it.
        val g = Fixture(this)
        g.put(c)
        g.store.hold[1] = CompletableDeferred()
        g.start(); g.coordinator.onActivated(KEY); runCurrent()
        assertTrue(g.store.generalReads.single() is GraphV2DiskRead.Found)

        g.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN))
        g.store.release(1); runCurrent()

        assertEquals(GraphEntry(joined(c, withKrx = false), null), g.state.entries[KEY])
        assertEquals(0, g.store.krxReads.size)
        assertTrue(g.files.reads.none { isKrx(g.root, it) })
        g.close()
    }

    /** Bytes the codec refuses: a refused general half seeds nothing; a refused, missing or unreadable KRX half leaves the general one. */
    @Test fun C11_storedBytesAreCheckedPerHalf() = cacheTest {
        val c = components(serverTab())
        val generalOnly = GraphEntry(joined(c, withKrx = false), null)

        val a = Fixture(this)
        a.putKrx(checkNotNull(c.krx))
        write(generalPath(a.root, GKEY), "{".encodeToByteArray())
        a.start(); a.coordinator.onActivated(KEY); runCurrent()
        assertTrue("malformed general", a.store.generalReads.single() is GraphV2DiskRead.Rejected)
        assertEquals("no KRX read after a refused general half", 0, a.store.krxReads.size)
        assertNull(a.state.entries[KEY])
        assertTrue(a.diagnostics.any { it.key == KEY && it.component == GraphV2DiskComponent.GENERAL })
        a.close()

        val b = Fixture(this)
        val jpy = (splitGraphV2ServerTab(serverTab().copy(tab = "jpy"), GKEY.copy(tab = "jpy"), null, "r1", null) as GraphV2Validation.Valid).value
        write(generalPath(b.root, GKEY), (codec.encodeGeneral(jpy.general) as GraphV2Validation.Valid).value)
        b.start(); b.coordinator.onActivated(KEY); runCurrent()
        assertTrue("another key's envelope", b.store.generalReads.single() is GraphV2DiskRead.Rejected)
        assertEquals("no KRX read after a refused general half", 0, b.store.krxReads.size)
        assertNull(b.state.entries[KEY])
        b.close()

        for ((label, setup, expected) in listOf<Triple<String, (Fixture) -> Unit, (GraphV2DiskRead<GraphV2KrxEnvelope>) -> Boolean>>(
            Triple("malformed KRX", { f -> f.putGeneral(c.general); write(krxPath(f.root, c.krx!!.key), "{".encodeToByteArray()) },
                { it is GraphV2DiskRead.Rejected }),
            Triple("missing KRX", { f -> f.putGeneral(c.general) }, { it is GraphV2DiskRead.Absent }),
            Triple("unreadable KRX", { f -> f.put(c); f.files.failRead = { isKrx(f.root, it) } }, { it is GraphV2DiskRead.Failed })
        )) {
            val f = Fixture(this)
            setup(f)
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            assertTrue(label, expected(f.store.krxReads.single()))
            assertEquals(label, generalOnly, f.state.entries[KEY])
            if (label != "missing KRX") {
                assertTrue("$label: diagnosed", f.diagnostics.any { it.key == KEY && it.component == GraphV2DiskComponent.KRX })
            }
            f.close()
        }
    }

    /** A codec-valid KRX half of another answer, other metadata or a colliding ordinal is left out; the exact pair joins in order. */
    @Test fun C12_onlyTheExactPairJoins() = cacheTest {
        val krxSeed = GraphV2InProgress(NOON - 10.hours, 1302.0, 1300.0, 1301.0, NOON - 1.hours)
        val c = components(serverTab(inProgress = mapOf("krx.usd-krw-futures" to krxSeed)))
        val krx = checkNotNull(c.krx)
        val generalOnly = GraphEntry(joined(c, withKrx = false), null)
        for ((label, other) in listOf(
            "another response" to checkNotNull(components(serverTab(inProgress = mapOf("krx.usd-krw-futures" to krxSeed)), response = "r2").krx),
            "other metadata" to checkNotNull(components(serverTab(fetchedAt = NOON - 2.hours, inProgress = mapOf("krx.usd-krw-futures" to krxSeed))).krx),
            "a colliding ordinal" to krx.copy(component = krx.component.copy(series = krx.component.series.map { it.copy(ordinal = 0) }))
        )) {
            val f = Fixture(this)
            f.putGeneral(c.general)
            f.putKrx(other)
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            assertTrue("$label: the KRX half was read", f.store.krxReads.single() is GraphV2DiskRead.Found)
            assertEquals(label, generalOnly, f.state.entries[KEY])
            f.close()
        }
        val f = Fixture(this)
        f.put(c)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals(GraphEntry(joined(c), null), f.state.entries[KEY])
        assertEquals(IDS, f.ids())
        assertEquals(krxSeed, f.state.entries.getValue(KEY).tab.inProgress["krx.usd-krw-futures"])
        f.close()
    }

    /**
     * A KRX K1 read completed after the issuer rotated to K2 and approved token 8: nothing lands, not even the general half,
     * because token 7 no longer stands. A revision with no relevant change lets it land. The new context seeds K2.
     */
    @Test fun C13_aRotationSpendsTheSeedAndTheNewContextSeedsK2() = cacheTest {
        val f = Fixture(this)
        f.put(components(serverTab()))
        f.store.hold[1] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN))
        f.snapshot = snap(krx = "K2", issuedRecord = AccessFence("u1", "e1", "K1"),
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L)
        f.rebind("u1", 1L, "e1", 8L, krx = "K2", generation = 11L, invalidations = 4L)
        f.store.release(1); runCurrent()
        assertNull(f.state.entries[KEY])
        val k2 = components(serverTab(rate = 1500.0), krx = "K2")
        f.put(k2)
        f.coordinator.onContextChanged(); runCurrent()
        assertEquals(GraphEntry(joined(k2), null), f.state.entries[KEY])
        f.close()

        val g = Fixture(this)
        val c = components(serverTab())
        g.put(c)
        g.store.hold[1] = CompletableDeferred()
        g.start(); g.coordinator.onActivated(KEY); runCurrent()
        g.snapshot = snap(unconfirmed = true)
        g.store.release(1); runCurrent()
        assertEquals("a revision alone", GraphEntry(joined(c), null), g.state.entries[KEY])
        g.close()
    }

    // --- seed start points --------------------------------------------------------------------------------------

    /**
     * A refused or throwing bind spends no attempt: a later refresh or an unchanged context notice starts the seed. An occupied
     * slot and an unsupported key start none.
     */
    @Test fun C15_whereASeedStarts() = cacheTest {
        for (trigger in listOf("refresh", "unchanged context notice")) {
            val f = Fixture(this)
            val c = components(serverTab())
            f.put(c)
            f.protectedOpen = false
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            assertEquals("$trigger: a refused bind reads nothing", 0, f.store.generalReads.size)
            f.protectedOpen = true
            if (trigger == "refresh") f.coordinator.onRefreshRequested() else f.coordinator.onContextChanged()
            runCurrent()
            assertEquals(trigger, GraphEntry(joined(c), null), f.state.entries[KEY])
            f.close()
        }

        val t = Fixture(this)
        val c = components(serverTab())
        t.put(c)
        t.gateThrows = true
        t.start(); t.coordinator.onActivated(KEY); runCurrent()
        t.gateThrows = false
        assertEquals("a throwing bind reads nothing", 0, t.store.generalReads.size)
        assertTrue("a throwing bind is diagnosed", t.diagnostics.any { it.key == KEY })
        t.coordinator.onRefreshRequested(); runCurrent()
        assertEquals("and spends no attempt", GraphEntry(joined(c), null), t.state.entries[KEY])
        assertEquals(emptyList<Throwable>(), t.failures)
        t.close()

        val o = Fixture(this)
        o.start(); o.coordinator.onActivated(KEY); runCurrent()
        assertEquals(1, o.store.generalReads.size)
        o.sent.single().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        o.coordinator.onDeactivated(); o.coordinator.onActivated(KEY); runCurrent()
        assertEquals("an occupied slot starts no seed", 1, o.store.generalReads.size)
        o.close()

        val u = Fixture(this)
        u.start(); u.coordinator.onActivated(KEY); runCurrent()
        assertEquals(1, u.store.generalReads.size)
        u.coordinator.onActivated(GraphKey("eur", GraphPeriod.THREE_MONTHS)); runCurrent()
        assertEquals("a key the catalog does not list starts no seed", 1, u.store.generalReads.size)
        u.close()
    }

    /** GENERAL access closed after the bind but before the seed runs: the seed asks the store nothing (Codex counterexample to D1). */
    @Test fun C16_generalClosedAfterBindCallsNoStore() = cacheTest {
        val f = Fixture(this)
        f.start(); runCurrent()
        f.coordinator.onActivated(KEY)
        // Activation is queued first; the withdrawal runs before its queued seed.
        f.scope.launch { f.protectedOpen = false }
        runCurrent()
        assertEquals(0, f.store.generalReads.size)
        assertEquals(0, f.store.krxReads.size)
        assertEquals(0, f.files.reads.size)
        assertNull(f.state.entries[KEY])
        f.close()
    }

    /** The scope is cancelled while the seed completion is being handled: nothing is installed (Codex counterexample to D3). */
    @Test fun C17_scopeCancelledAtSeedCompletionInstallsNothing() = cacheTest {
        val f = Fixture(this)
        f.put(components(serverTab()))
        f.store.hold[1] = CompletableDeferred()
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals(1, f.store.generalReads.size)
        assertNull(f.state.entries[KEY])
        // The next event is the released seed completion.
        f.cancelAtNextEvent = true
        f.store.release(1); runCurrent()
        assertNull(f.state.entries[KEY])
        assertEquals(emptyList<Throwable>(), f.failures)
        f.close()
    }

    // --- E: the protected exposure (B1b-2a-2) --------------------------------------------------------------------

    private fun GraphEntry?.ids() = checkNotNull(this) { "nothing exposed" }.tab.graph.series.map { it.seriesId }

    /** B02-b: an exposed seed or online answer closes at once with the live gate, before any control event; KRX alone closes alone. */
    @Test fun E01_theExposureFollowsTheLiveGate() = cacheTest {
        val krxSeed = GraphV2InProgress(NOON - 10.hours, 1302.0, 1300.0, 1301.0, NOON - 1.hours)
        val c = components(serverTab(inProgress = mapOf(KRX_SERIES to krxSeed)))
        for (source in listOf("seed", "online")) {
            val f = Fixture(this)
            if (source == "seed") f.put(c)
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            if (source == "online") { f.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent() }
            val open = checkNotNull(f.exposed()) { source }
            assertTrue("$source: KRX shown", KRX_SERIES in open.ids())
            f.protectedOpen = false
            assertNull("$source: protected admission closed", f.exposed())
            f.protectedOpen = true
            assertEquals("$source: reopened", open, f.exposed())
            f.snapshot = snap(userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), invalidations = 4L)
            assertNull("$source: the user axis held", f.exposed())
            f.close()

            val g = Fixture(this)
            if (source == "seed") g.put(c)
            g.start(); g.coordinator.onActivated(KEY); runCurrent()
            if (source == "online") { g.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent() }
            g.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN))
            val generalOnly = checkNotNull(g.exposed()) { "$source: KRX held" }
            assertTrue("$source: KRX held, no KRX series", KRX_SERIES !in generalOnly.ids())
            assertNull("$source: KRX held, no KRX bucket", generalOnly.tab.inProgress[KRX_SERIES])
            assertEquals("$source: the stamp is the slot's", g.state.entries.getValue(KEY).online200At, generalOnly.online200At)
            g.close()
        }
    }

    /** R3: an online answer's KRX half keeps the epoch its request bound; after a rotation to K2 only a K2 request shows KRX. */
    @Test fun E02_anOnlineKrxHalfKeepsItsRequestsEpoch() = cacheTest {
        val f = Fixture(this)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        assertTrue(KRX_SERIES in f.exposed().ids())
        f.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.CONTEXT_UNCERTAIN))
        f.snapshot = snap(krx = "K2", issuedRecord = AccessFence("u1", "e1", "K1"),
            capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L)
        f.rebind("u1", 1L, "e1", 8L, krx = "K2", generation = 11L, invalidations = 4L)
        f.coordinator.onContextChanged(); runCurrent()
        assertEquals("the K1 half is not shown under K2", listOf(ONLINE), f.exposed().ids())
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.sent.last().tab.complete(ok(onlineKrxDto(1600.0))); runCurrent()
        assertEquals("a request bound under K2 shows its KRX half", listOf(ONLINE, KRX_SERIES), f.exposed().ids())
        f.close()
    }

    /** R3: a request that started with the capability held keeps no KRX half, even if the capability opens before its answer. */
    @Test fun E03_theEpochIsBoundWhenTheRequestStarts() = cacheTest {
        val f = Fixture(this)
        f.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED))
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.snapshot = snap()
        f.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        assertEquals(listOf(ONLINE), f.exposed().ids())
        f.close()
    }

    /** R4: a request whose start bind failed keeps a GENERAL-only slot: nothing while closed, the general half once open. */
    @Test fun E04_aFailedStartBindKeepsAGeneralOnlySlot() = cacheTest {
        val f = Fixture(this)
        f.protectedOpen = false
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        assertNotNull("the request owner adopted it", f.state.entries[KEY])
        assertNull("closed", f.exposed())
        f.protectedOpen = true
        assertEquals("open: the general half only", listOf(ONLINE), f.exposed().ids())
        f.close()

        // Opened before the answer: a completion that re-bound only because the start bind was null would show KRX.
        val g = Fixture(this)
        g.protectedOpen = false
        g.start(); g.coordinator.onActivated(KEY); runCurrent()
        g.protectedOpen = true
        g.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        assertNotNull(g.state.entries[KEY])
        assertEquals(listOf(ONLINE), g.exposed().ids())
        g.close()
    }

    /** Without cache ports nothing is exposed, while the request owner works as before. */
    @Test fun E05_noCacheExposesNothing() = cacheTest {
        val f = Fixture(this, cache = false)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        assertNotNull(f.state.entries[KEY])
        assertNull(f.exposed())
        f.close()
    }

    /** B08: an exposed seed and an exposed online answer stay exposed through failures and two days, stamps unchanged. */
    @Test fun E06_lastGoodStaysExposed() = cacheTest {
        val c = components(serverTab())
        for (source in listOf("seed", "online")) {
            val f = Fixture(this)
            if (source == "seed") f.put(c)
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            if (source == "online") { f.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent() }
            val before = checkNotNull(f.exposed()) { source }
            f.autoTab = fail503
            f.sent.filter { !it.tab.isCompleted }.forEach { fail503(it) }
            runCurrent()
            f.coordinator.onRefreshRequested(force = true); runCurrent()
            assertTrue("$source: a graph request failed", f.tabs().size >= 2 && KEY in f.state.failures)
            step(2.days)
            assertEquals(source, before, f.exposed())
            f.close()
        }
    }

    /** Within one data scope a new context exposes the kept slot; another USER epoch clears it. */
    @Test fun E07_theDataScopeDecidesWhatIsKept() = cacheTest {
        val f = Fixture(this)
        val c = components(serverTab())
        f.put(c)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        val exposed = checkNotNull(f.exposed())
        f.rebind("u1", 2L, "e1", 8L, generation = 11L)
        f.coordinator.onContextChanged(); runCurrent()
        assertEquals("same scope, new context: kept and shown", exposed.tab, f.exposed()?.tab)
        f.rebind("u1", 2L, "e2", 9L, generation = 12L)
        f.coordinator.onContextChanged(); runCurrent()
        assertNull("another USER epoch: not exposed", f.exposed())
        f.close()
    }

    /** An online answer replaces the seed's slot as well as its entry. */
    @Test fun E08_anOnlineAnswerReplacesTheSeedSlot() = cacheTest {
        val f = Fixture(this)
        f.put(components(serverTab()))
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertEquals(IDS, f.exposed().ids())
        f.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        val online = checkNotNull(f.exposed())
        assertEquals(listOf(ONLINE, KRX_SERIES), online.ids())
        assertNotNull(online.online200At)
        f.close()
    }

    /** A user hold that came and went leaves nothing exposed until the request owner takes the new lifetime. */
    @Test fun E09_aUserHoldRoundTripHidesUntilTheContextMovesOn() = cacheTest {
        val f = Fixture(this)
        f.put(components(serverTab()))
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        val exposed = checkNotNull(f.exposed())
        f.snapshot = snap(userBlocks = setOf(TopicAccessBlock.LOSS_CANDIDATE), invalidations = 4L)
        f.snapshot = snap(invalidations = 4L)
        assertNull("the context's lifetime is spent", f.exposed())
        f.coordinator.onContextChanged(); runCurrent()
        assertEquals(exposed, f.exposed())
        f.close()
    }

    /** A request owner whose scope ended exposes nothing. */
    @Test fun E10_anEndedScopeExposesNothing() = cacheTest {
        val f = Fixture(this)
        f.put(components(serverTab()))
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertNotNull(f.exposed())
        assertNull("a key without a slot exposes nothing", f.coordinator.protectedEntry(GraphKey("usd", GraphPeriod.ONE_YEAR)))
        f.close()
        assertNull(f.exposed())
    }

    /**
     * A data scope that lapses (no live fence) and returns to the same UID and USER epoch clears the slots with the entries:
     * an online-only slot is not shown again until a new answer lands (Codex counterexample to the N07 equivalence).
     */
    @Test fun E11_aLapsedScopeClearsTheSlots() = cacheTest {
        val f = Fixture(this)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(ok(onlineKrxDto(1500.0))); runCurrent()
        assertNotNull(f.exposed())
        f.fence = null
        f.coordinator.onContextChanged(); runCurrent()
        assertNull("no live fence", f.exposed())
        f.fence = FENCE
        f.coordinator.onContextChanged(); runCurrent()
        assertNull("the raw entry is gone", f.state.entries[KEY])
        assertNull("back to the same scope: the slot is gone too", f.exposed())
        f.close()
    }

    // --- failures are values -------------------------------------------------------------------------------------

    /** A missing, refused or unreadable seed is diagnosed and changes no failure, ladder, floor or request. */
    @Test fun C14_aSeedFailureChangesNoSchedule() = cacheTest {
        for ((label, setup) in listOf<Pair<String, (Fixture) -> Unit>>(
            "missing" to { },
            "refused" to { f -> write(generalPath(f.root, GKEY), "{".encodeToByteArray()) },
            "unreadable" to { f -> f.put(components(serverTab())); f.files.failRead = { true } },
            "a throwing diagnostic observer" to { f -> write(generalPath(f.root, GKEY), "{".encodeToByteArray()); f.observerThrows = true },
            "a throwing access input at completion" to { f -> f.put(components(serverTab())) }
        )) {
            val f = Fixture(this)
            setup(f)
            f.autoTab = fail503
            f.store.hold[1] = CompletableDeferred()
            f.start(); f.coordinator.onActivated(KEY); runCurrent()
            step(1.seconds)
            if (label == "a throwing access input at completion") f.gateThrows = true
            f.store.release(1); runCurrent()
            f.gateThrows = false
            assertNull(label, f.state.entries[KEY])
            assertTrue("$label: the failure is kept", KEY in f.state.failures)
            if (label != "missing") assertTrue("$label: diagnosed", f.diagnostics.any { it.key == KEY })
            step(99.seconds)
            assertEquals(label, secs(0, 3, 9, 21, 45, 93), f.tabTimes())
            assertEquals("$label: nothing reaches the loop's failure handler", emptyList<Throwable>(), f.failures)
            f.close()
        }

        val g = Fixture(this)
        var n = 0
        g.autoTab = { s -> if (n++ == 0) s.tab.complete(status(429, "120")) else fail503(s) }
        g.store.hold[1] = CompletableDeferred()
        g.start(); g.coordinator.onActivated(KEY); runCurrent()
        step(1.seconds); g.store.release(1); runCurrent()
        step(299.seconds)
        assertEquals("the floor and the ladder after it", secs(0, 120, 126, 138, 162, 210), g.tabTimes())
        g.close()
    }
}
