package com.jay.fxi.data.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.AccessEpochRecord
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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
 * Claude-owned S4 B1b-2b contract (2b-1 r2, 2b-2a r1, 2b-2b r1): the request owner writes an adopted online answer through to the Graph V2 disk store.
 *
 * Oracles: ANDROID_V2_PLAN.md :499 (a namespace's data marker is established before data is stored there), :1289 (only an
 * online 200 records freshness; a store outcome never does), :1294 (a store failure is diagnostic), :1338-1340 (no late
 * writer revives a namespace). Design: R4c/S4 b1b2b_design_codex.r1 as trimmed by b1b2b_review_claude.r1 and
 * b1b2b_verdict_codex.r1 (T1-T5): write ports as one nullable bundle on the cache ports; the adopted slot's components are
 * reserved at once, before any asynchronous preparation; the write capture is the request's start capture (a null one
 * reserves and cancels); a seed is never written back; Ready is the producer's persistence contract and is checked per
 * axis (GENERAL: owner, USER epoch, premium marker; KRX: the same on its own record plus the captured KRX epoch and the KRX
 * marker); a blocked GENERAL stops the whole write, a blocked KRX the KRX half; store outcomes change no entry, stamp,
 * failure, retry owner or request. B1b-2b-2a (rows X): admission is live at the preparation start and at the store delegate
 * (a reserved ticket grants nothing; a protected, capability-only or USER withdrawal before the delegate is honoured without
 * acquiring a new lifetime), a blocked preparation cancels its ticket, and the preparation starts only after the adoption is
 * published. B1b-2b-2b (rows Y): reservation follows adoption (a blocked or null-capture later answer still supersedes), a
 * completion releases only its own writer, a context change cancels pending writers, deactivation keeps them, and a write
 * completion leaves another key's cold retry and a due Wake untouched.
 *
 * The fixture is the cache contract's: the real request owner, the real FileGraphV2DiskStore with the JSON codec and the real
 * gate over one fake published snapshot that the use authority reads too. A store decorator records reserve, cancel and
 * write; the file boundary records prepares and publishes; a fake preparation port records its calls and can hold or throw.
 */
class GraphV2RequestCoordinatorWriteTest {

    @get:Rule val folder = TemporaryFolder()

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        val KEY = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        val GKEY = GraphV2GeneralKey("u1", "e1", "usd", GraphPeriod.THREE_MONTHS.code)
        val FENCE = TopicSessionFence(AuthIdentityFence("u1", 1L), "e1", TopicGrantToken(7L))
        const val ONLINE = "investing.usd-krw"
        const val KRX_SERIES = "krx.usd-krw-futures"
        val IDS = listOf("hana.usd-krw", KRX_SERIES, "kb.usd-krw")
        val ALL_IDS = IDS + ONLINE
        /** The namespace record a producer has made durable for the live V capture. */
        val M = AccessEpochRecord(ownerUid = "u1", userAccessEpoch = "e1", krxCapabilityEpoch = "K1",
            mayContainPremiumData = true, mayContainKrxData = true)
    }

    // --- the published topic access (the cache contract's issuer model) ------------------------------------------

    private fun snap(
        token: Long? = 7L,
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        invalidations: Long = 3L
    ): TopicAccessSnapshot {
        val binding = EntitlementsIdentity("u1", 1L)
        val record = AccessFence("u1", "e1", "K1")
        val issued = token?.let { TopicGrantContext(binding, record, 10L) }
        return TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = TopicAccessFacts.NONE.copy(
                token = token?.let(::TopicGrantToken),
                issuedFor = issued,
                binding = binding,
                recordFence = record,
                decisionGeneration = 10L,
                tokenStanding = issued != null,
                userBlocks = userBlocks,
                capabilityBlocks = capabilityBlocks,
                capabilityContextUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in capabilityBlocks
            ),
            userInvalidations = invalidations
        )
    }

    // --- stored and online answers ------------------------------------------------------------------------------

    private val codec = JsonGraphV2EnvelopeCodec()

    private fun serverTab(rate: Double = 1200.0) = GraphV2Tab(
        "usd", GraphPeriod.THREE_MONTHS, "1d", NOON - 1.hours,
        FreeGraph("1d", IDS.mapIndexed { i, id ->
            FreeGraphSeries(id, listOf(FreeGraphPoint(NOON - 1.days, rate + i, rate + i + 1, rate + i - 1, "x")), id, "krw", "KRW", 2)
        }, "2026-07-05", "2026-10-05"),
        emptyMap()
    )

    private fun components(tab: GraphV2Tab) =
        (splitGraphV2ServerTab(tab, GKEY, "K1", "r0", null) as GraphV2Validation.Valid).value

    private fun hex(text: String) = text.encodeToByteArray().joinToString("") { "%02x".format(it) }

    private fun generalPath(root: File, k: GraphV2GeneralKey) =
        File(root, "general/${hex(k.uid)}/${hex(k.userAccessEpoch)}/${hex(k.tab)}/${k.period}.json")

    private fun krxPath(root: File, k: GraphV2KrxKey) =
        File(root, "krx/${hex(k.uid)}/${hex(k.userAccessEpoch)}/${hex(k.krxCapabilityEpoch)}/${hex(k.tab)}/${k.period}.json")

    private fun Fixture.put(c: GraphV2DiskComponents) {
        generalPath(root, c.general.key).also { it.parentFile.mkdirs() }
            .writeBytes((codec.encodeGeneral(c.general) as GraphV2Validation.Valid).value)
        c.krx?.let { k ->
            krxPath(root, k.key).also { it.parentFile.mkdirs() }.writeBytes((codec.encodeKrx(k) as GraphV2Validation.Valid).value)
        }
    }

    private fun rateOf(c: GraphV2DiskComponents) = c.general.component.series.first().series.points.first().rate

    private fun isKrx(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath.startsWith("krx/")

    private fun onlineDto(rate: Double) = GraphV2TabResponse(
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

    private fun catalog() = GraphV2CatalogResponse(
        tabs = listOf(GraphV2CatalogTab("usd", "usd", emptyMap(),
            listOf("1d", "1w", "3m", "1y").associateWith { GraphV2CatalogPeriod(ALL_IDS, ALL_IDS) })),
        version = "2026-05-27",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = 172800
    )

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    private fun <T> status(code: Int): AuthenticatedHttpResponse<T> {
        val headers = Headers.headersOf()
        return AuthenticatedHttpResponse(code, headers, null,
            AuthenticatedHttpFailure(code, headers, byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP), byteArrayOf(1))
    }

    // --- the fixture --------------------------------------------------------------------------------------------

    private class RecordingFiles : GraphV2AtomicFileIo {
        private val real = DefaultGraphV2AtomicFileIo()
        val prepares = mutableListOf<File>()
        val publishes = mutableListOf<File>()
        var failPublish: (File) -> Boolean = { false }
        var failPrepare: (File) -> Boolean = { false }
        override fun read(file: File): ByteArray? = real.read(file)
        override fun prepareReplace(target: File, bytes: ByteArray): GraphV2PreparedReplace {
            prepares += target
            if (failPrepare(target)) throw IOException("prepare refused")
            return PreparedTarget(target, real.prepareReplace(target, bytes))
        }
        override fun publishReplace(prepared: GraphV2PreparedReplace) {
            val p = prepared as PreparedTarget
            if (failPublish(p.target)) throw IOException("publish refused")
            real.publishReplace(p.inner)
            publishes += p.target
        }
        override fun discardReplace(prepared: GraphV2PreparedReplace) = real.discardReplace((prepared as PreparedTarget).inner)
        override fun enumerateFiles(root: File): List<File> = real.enumerateFiles(root)
        override fun deleteIfExists(file: File): Boolean = real.deleteIfExists(file)
    }

    private class PreparedTarget(val target: File, val inner: GraphV2PreparedReplace) : GraphV2PreparedReplace

    /**
     * Behaves like Dispatchers.Main.immediate on its own thread: a task arrives through [queue], and a launch made while a task
     * runs starts in place. Delays are the test scheduler's.
     */
    @OptIn(InternalCoroutinesApi::class)
    private class InPlaceDispatcher(private val queue: TestDispatcher) : CoroutineDispatcher(), Delay by queue {
        private var inside = false
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !inside
        override fun dispatch(context: CoroutineContext, block: Runnable) = queue.dispatch(context, Runnable {
            val outer = inside
            inside = true
            try {
                block.run()
            } finally {
                inside = outer
            }
        })
    }

    /** The real store, recording what the request owner asks of it. */
    private class WriteStore(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
        val reserved = mutableListOf<Pair<GraphV2DiskComponents, GraphV2WriteReservation>>()
        val cancelled = mutableListOf<GraphV2WriteTicket>()
        val written = mutableListOf<Pair<GraphV2WriteTicket, GraphV2WriteReport>>()
        /** Runs inside the write task, after Ready, just before the real store is asked. */
        var beforeWrite: (() -> Unit)? = null
        var holdBeforeWrite: CompletableDeferred<Unit>? = null
        var writeThrows: Throwable? = null
        /** By write call index: the real store has answered, the completion is not yet returned. */
        val holdAfterWrite = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var afterWriteDelay: Duration? = null
        private var writeCalls = 0
        override fun reserveWrite(components: GraphV2DiskComponents): GraphV2WriteReservation =
            real.reserveWrite(components).also { reserved += components to it }
        override fun cancelWrite(ticket: GraphV2WriteTicket) {
            cancelled += ticket
            real.cancelWrite(ticket)
        }
        override suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport {
            val index = writeCalls++
            beforeWrite?.invoke()
            holdBeforeWrite?.await()
            writeThrows?.let { throw it }
            val report = real.write(ticket, admission)
            written += ticket to report
            holdAfterWrite[index]?.await()
            afterWriteDelay?.let { delay(it) }
            return report
        }
        val tickets get() = reserved.mapNotNull { (it.second as? GraphV2WriteReservation.Reserved)?.ticket }
    }

    private class Sent(val key: GraphKey, val atMs: Long) {
        val tab = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
    }

    private val opened = mutableListOf<Fixture>()

    private fun writeTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private inner class Fixture(test: TestScope, writes: Boolean = true, dispatcher: CoroutineDispatcher? = null) {
        init { opened += this }
        val scope = CoroutineScope(SupervisorJob() + (dispatcher ?: StandardTestDispatcher(test.testScheduler)))
        val base = test.testScheduler.currentTime
        var fence: TopicSessionFence? = FENCE
        var protectedOpen = true
        var snapshot: TopicAccessSnapshot = snap()
        val root: File = folder.newFolder()
        val files = RecordingFiles()
        val real = FileGraphV2DiskStore(root, codec, files, StandardTestDispatcher(test.testScheduler))
        val store = WriteStore(real)
        val failures = mutableListOf<Throwable>()
        val sent = mutableListOf<Sent>()

        /** Preparation: calls are recorded; a result can be held, chosen or thrown. */
        val prepareCalls = mutableListOf<Pair<GraphV2AccessCapture, Boolean>>()
        var onPrepare: (() -> Unit)? = null
        var prepareDelay: Duration? = null
        /** By preparation call index. */
        val holdCall = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val answerAt = mutableMapOf<Int, GraphV2WritePreparation>()
        var holdPrepare: CompletableDeferred<Unit>? = null
        var prepareAnswer: (GraphV2AccessCapture, Boolean) -> GraphV2WritePreparation = { _, wantsKrx ->
            GraphV2WritePreparation(GraphV2NamespacePreparation.Ready(M), if (wantsKrx) GraphV2NamespacePreparation.Ready(M) else null)
        }
        val blocked = mutableListOf<GraphV2PreparationBlocked>()
        var blockedThrows = false
        val writeDiagnostics = mutableListOf<GraphV2WriteDiagnostic>()
        var writeDiagnosticThrows = false

        var acquires = 0
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
            snapshot = { snapshot },
            protectedAdmission = { protectedOpen }
        )

        val fetcher = object : GraphV2Fetching {
            override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2CatalogResponse> =
                ok(catalog())
            override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2TabResponse> {
                val s = Sent(key, test.testScheduler.currentTime - base)
                sent += s
                return s.tab.await()
            }
        }

        val writePorts = GraphV2WritePorts(
            prepareWrite = GraphV2PrepareWrite { captured, wantsKrx ->
                val index = prepareCalls.size
                prepareCalls += captured to wantsKrx
                onPrepare?.invoke()
                prepareDelay?.let { delay(it) }
                holdPrepare?.await()
                holdCall[index]?.await()
                answerAt[index] ?: prepareAnswer(captured, wantsKrx)
            },
            onPreparationBlocked = {
                blocked += it
                if (blockedThrows) throw IllegalStateException("blocked observer")
            },
            onWriteDiagnostic = {
                writeDiagnostics += it
                if (writeDiagnosticThrows) throw IllegalStateException("diagnostic observer")
            }
        )

        val coordinator = GraphV2RequestCoordinator(
            fetcher = fetcher,
            owners = owners,
            currentAccessFence = { fence },
            uses = uses,
            protectedAdmission = { true },
            accessSnapshot = { snapshot },
            scope = scope,
            clock = AppClock { NOON + (test.testScheduler.currentTime - base).milliseconds },
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { failures += it },
            cachePorts = GraphV2CachePorts(store = store, gate = gate, onSeedDiagnostic = { },
                writePorts = if (writes) writePorts else null)
        )

        val state get() = coordinator.state.value
        fun start() = coordinator.start()
        fun close() = scope.cancel()

        /** Activates the key and answers its request with an online 200. */
        fun adopt(test: TestScope, rate: Double = 1500.0) {
            start(); coordinator.onActivated(KEY); test.runCurrent()
            sent.last().tab.complete(ok(onlineDto(rate))); test.runCurrent()
        }

        suspend fun diskGeneral(): Double? =
            (real.readGeneral(GKEY, null) { true } as? GraphV2DiskRead.Found)?.envelope?.component?.series?.first()?.series?.points?.first()?.rate

        suspend fun diskKrx(epoch: String = "K1"): Double? =
            (real.readKrx(GraphV2KrxKey("u1", "e1", epoch, "usd", GraphPeriod.THREE_MONTHS.code), null) { true }
                as? GraphV2DiskRead.Found)?.envelope?.component?.series?.first()?.series?.points?.first()?.rate
    }

    private fun TestScope.step(d: Duration) {
        advanceTimeBy(d.inWholeMilliseconds)
        runCurrent()
    }

    // --- rows ---------------------------------------------------------------------------------------------------

    /** Without write ports nothing is reserved, prepared or written. */
    @Test fun W01_noWritePortsWriteNothing() = writeTest {
        val f = Fixture(this, writes = false)
        f.adopt(this)
        assertNotNull(f.state.entries[KEY]?.online200At)
        assertEquals(0, f.store.reserved.size)
        assertEquals(0, f.prepareCalls.size)
        assertEquals(0, f.store.written.size)
        assertEquals(emptyList<File>(), f.files.prepares)
    }

    /** An adopted answer is written with the request's own capture, though its HTTP registration is already disposed. */
    @Test fun W02_anAdoptedAnswerIsWrittenThrough() = writeTest {
        val f = Fixture(this)
        f.adopt(this)
        assertEquals(1, f.store.tickets.size)
        val (captured, wantsKrx) = f.prepareCalls.single()
        assertEquals(FENCE, captured.fence)
        assertEquals("K1", captured.krxCapabilityEpoch)
        assertTrue(wantsKrx)
        assertEquals(1500.0, f.diskGeneral())
        assertEquals(1505.0, f.diskKrx())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** A seed is never written back. */
    @Test fun W03_aSeedIsNotWrittenBack() = writeTest {
        val f = Fixture(this)
        f.put(components(serverTab()))
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        assertNotNull("the seed landed", f.state.entries[KEY])
        assertNull(f.state.entries.getValue(KEY).online200At)
        assertEquals(0, f.store.reserved.size)
        assertEquals(0, f.prepareCalls.size)
        assertEquals(emptyList<File>(), f.files.prepares)
    }

    /** A request whose start bind failed reserves (so an older writer is superseded) and cancels at once; nothing is prepared. */
    @Test fun W04_aNullStartCaptureReservesAndCancels() = writeTest {
        val f = Fixture(this)
        f.protectedOpen = false
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.protectedOpen = true
        f.sent.single().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        assertEquals(1, f.store.tickets.size)
        assertEquals("the reservation is cancelled", f.store.tickets, f.store.cancelled)
        assertEquals("nothing is prepared", 0, f.prepareCalls.size)
        assertEquals(emptyList<File>(), f.files.prepares)
        assertNull(f.diskGeneral())
    }

    /** A request that started with the capability held reserves and prepares the general half only, whatever opens later. */
    @Test fun W05_aNullStartKrxEpochWritesTheGeneralHalfOnly() = writeTest {
        val f = Fixture(this)
        f.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED))
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.snapshot = snap()
        f.sent.single().tab.complete(ok(onlineDto(1500.0))); runCurrent()
        assertNull("no KRX candidate", f.store.reserved.single().first.krx)
        val (captured, wantsKrx) = f.prepareCalls.single()
        assertNull("the start capture had no epoch", captured.krxCapabilityEpoch)
        assertEquals(false, wantsKrx)
        assertEquals(1500.0, f.diskGeneral())
        assertNull(f.diskKrx())
    }

    /**
     * B07: the stamp is the adoption's; a later store success or failure moves no stamp, failure or request. A throwing
     * write-diagnostic observer stays outside A2's event catch.
     */
    @Test fun W06_theStampIsTheAdoptions() = writeTest {
        for (fails in listOf(false, true)) {
            val f = Fixture(this)
            f.holdPrepare = CompletableDeferred()
            f.writeDiagnosticThrows = true
            if (fails) f.files.failPublish = { true }
            f.adopt(this)
            val adopted = checkNotNull(f.state.entries[KEY])
            assertEquals("fails=$fails", NOON, adopted.online200At)
            step(10.seconds)
            checkNotNull(f.holdPrepare).complete(Unit); runCurrent()
            assertEquals("fails=$fails: one write ran", 1, f.store.written.size)
            assertEquals("fails=$fails: a failed write is diagnosed", fails, f.writeDiagnostics.isNotEmpty())
            assertEquals("fails=$fails", adopted, f.state.entries[KEY])
            assertTrue("fails=$fails", KEY !in f.state.failures)
            assertEquals("fails=$fails: no further request", 1, f.sent.size)
            assertEquals("fails=$fails", emptyList<Throwable>(), f.failures)
            f.close()
        }
    }

    /** B08 write side: a failed write after a graph failure leaves the adopted entry, its stamp and its exposure for two days. */
    @Test fun W07_aFailedWriteKeepsLastGood() = writeTest {
        val f = Fixture(this)
        f.holdPrepare = CompletableDeferred()
        f.files.failPublish = { true }
        f.adopt(this)
        val adopted = checkNotNull(f.state.entries[KEY])
        val exposed = checkNotNull(f.coordinator.protectedEntry(KEY))
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.sent.last().tab.complete(status(503)); runCurrent()
        checkNotNull(f.holdPrepare).complete(Unit); runCurrent()
        assertTrue("the write ran and failed", f.store.written.single().second.general is GraphV2ComponentWriteOutcome.Failed)
        step(2.days)
        assertEquals(adopted, f.state.entries[KEY])
        assertEquals(exposed, f.coordinator.protectedEntry(KEY))
    }

    /** B12-p: each preparation answer is checked per axis; a blocked axis is reported and touches no file. */
    @Test fun W08_preparationIsCheckedPerAxis() = writeTest {
        val ready = { r: AccessEpochRecord -> GraphV2NamespacePreparation.Ready(r) }
        val blockedAxis = GraphV2NamespacePreparation.Blocked("not durable")
        data class Case(val label: String, val general: GraphV2NamespacePreparation, val krx: GraphV2NamespacePreparation?,
                        val generalWritten: Boolean, val blockedComponents: Set<GraphV2DiskComponent>)
        for (c in listOf(
            Case("GENERAL owner u2", ready(M.copy(ownerUid = "u2")), ready(M), false, setOf(GraphV2DiskComponent.GENERAL)),
            Case("GENERAL USER e2", ready(M.copy(userAccessEpoch = "e2")), ready(M), false, setOf(GraphV2DiskComponent.GENERAL)),
            Case("GENERAL premium marker false", ready(M.copy(mayContainPremiumData = false)), ready(M), false, setOf(GraphV2DiskComponent.GENERAL)),
            Case("GENERAL blocked", blockedAxis, ready(M), false, setOf(GraphV2DiskComponent.GENERAL)),
            Case("KRX owner u2", ready(M), ready(M.copy(ownerUid = "u2")), true, setOf(GraphV2DiskComponent.KRX)),
            Case("KRX USER e2", ready(M), ready(M.copy(userAccessEpoch = "e2")), true, setOf(GraphV2DiskComponent.KRX)),
            Case("KRX epoch K2", ready(M), ready(M.copy(krxCapabilityEpoch = "K2")), true, setOf(GraphV2DiskComponent.KRX)),
            Case("KRX premium marker false", ready(M), ready(M.copy(mayContainPremiumData = false)), true, setOf(GraphV2DiskComponent.KRX)),
            Case("KRX marker false", ready(M), ready(M.copy(mayContainKrxData = false)), true, setOf(GraphV2DiskComponent.KRX)),
            Case("KRX candidate, no KRX answer", ready(M), null, true, setOf(GraphV2DiskComponent.KRX)),
            Case("KRX blocked", ready(M), blockedAxis, true, setOf(GraphV2DiskComponent.KRX)),
            Case("GENERAL record with K2 and no KRX marker, KRX blocked", ready(M.copy(krxCapabilityEpoch = "K2", mayContainKrxData = false)),
                blockedAxis, true, setOf(GraphV2DiskComponent.KRX))
        )) {
            val f = Fixture(this)
            f.prepareAnswer = { _, _ -> GraphV2WritePreparation(c.general, c.krx) }
            f.adopt(this)
            assertEquals(c.label, 1, f.prepareCalls.size)
            assertEquals("${c.label}: blocked axes", c.blockedComponents, f.blocked.map { it.component }.toSet())
            assertTrue("${c.label}: blocked reports name this write's key", f.blocked.all { it.key == KEY })
            assertEquals("${c.label}: general half", if (c.generalWritten) 1500.0 else null, f.diskGeneral())
            assertNull("${c.label}: no KRX half", f.diskKrx())
            assertTrue("${c.label}: no KRX file touched", f.files.prepares.none { isKrx(f.root, it) })
            if (!c.generalWritten) assertEquals("${c.label}: no file touched", emptyList<File>(), f.files.prepares)
            assertEquals(c.label, emptyList<Throwable>(), f.failures)
            f.close()
        }

        // The preparation port throwing reports every requested axis with its cause and writes nothing.
        val t = Fixture(this)
        val cause = IOException("record unreadable")
        t.prepareAnswer = { _, _ -> throw cause }
        t.adopt(this)
        assertEquals(setOf(GraphV2DiskComponent.GENERAL, GraphV2DiskComponent.KRX), t.blocked.map { it.component }.toSet())
        assertTrue(t.blocked.all { it.cause === cause })
        assertEquals(emptyList<File>(), t.files.prepares)
        assertEquals(emptyList<Throwable>(), t.failures)
        t.close()

        // A throwing blocked observer changes nothing: a KRX-only block still lets the GENERAL half through.
        val o = Fixture(this)
        o.blockedThrows = true
        o.prepareAnswer = { _, _ -> GraphV2WritePreparation(ready(M), blockedAxis) }
        o.adopt(this)
        assertEquals(setOf(GraphV2DiskComponent.KRX), o.blocked.map { it.component }.toSet())
        assertEquals(1500.0, o.diskGeneral())
        assertNull(o.diskKrx())
        assertEquals(emptyList<Throwable>(), o.failures)
        o.close()

        // Nor does it cut the reports short: a GENERAL block and a missing KRX answer are both reported, and nothing is written.
        val g = Fixture(this)
        g.blockedThrows = true
        g.prepareAnswer = { _, _ -> GraphV2WritePreparation(blockedAxis, null) }
        g.adopt(this)
        assertEquals(setOf(GraphV2DiskComponent.GENERAL, GraphV2DiskComponent.KRX), g.blocked.map { it.component }.toSet())
        assertEquals(emptyList<File>(), g.files.prepares)
        assertEquals(emptyList<Throwable>(), g.failures)
        g.close()
    }

    // --- B1b-2b-2a: admission at the preparation start and at the store delegate --------------------------------

    /** T3: a reserved ticket grants nothing. GENERAL closing before the preparation coroutine runs stops the preparation. */
    @Test fun X01_aTicketIsNotAuthority() = writeTest {
        val f = Fixture(this)
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        // The request started under an open gate. The 200 is queued first, the withdrawal after it: the request's own
        // admission does not read it, but the completion's configuration read finds it closed (B1b-2c-2: unconfirmed).
        f.sent.single().tab.complete(ok(onlineDto(1500.0)))
        f.scope.launch { f.protectedOpen = false }
        runCurrent()
        assertNotNull("adopted", f.state.entries[KEY])
        assertNull("completion configuration unavailable: unconfirmed", f.state.entries[KEY]?.online200At)
        assertEquals("reserved", 1, f.store.tickets.size)
        assertEquals("that ticket is cancelled", f.store.tickets.toSet(), f.store.cancelled.toSet())
        assertEquals("nothing is prepared", 0, f.prepareCalls.size)
        assertEquals(0, f.store.written.size)
        assertEquals(emptyList<File>(), f.files.prepares)
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** B12-s: after Ready the protected gate closes just before the store delegate. The live admission keeps every file closed. */
    @Test fun X02_aWithdrawalBeforeTheDelegateIsHonoured() = writeTest {
        val f = Fixture(this)
        f.store.beforeWrite = { f.protectedOpen = false }
        f.adopt(this)
        assertEquals("Ready", 1, f.prepareCalls.size)
        assertEquals("delegated", 1, f.store.written.size)
        assertEquals(emptyList<File>(), f.files.prepares)
        assertNull(f.diskGeneral())
        assertNull(f.diskKrx())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** B12-s: only the capability closes just before the delegate (the token and USER lifetime stand): GENERAL is written, KRX is not. */
    @Test fun X03_aCapabilityOnlyHoldBeforeTheDelegateStopsKrxOnly() = writeTest {
        val f = Fixture(this)
        f.store.beforeWrite = { f.snapshot = snap(capabilityBlocks = setOf(TopicAccessBlock.NOT_GRANTED)) }
        f.adopt(this)
        assertEquals("delegated", 1, f.store.written.size)
        assertEquals(1500.0, f.diskGeneral())
        assertNull(f.diskKrx())
        assertTrue("no KRX file touched", f.files.prepares.none { isKrx(f.root, it) })
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * CF1: a USER hold and its release land between Ready and the delegate with no context event or new answer (same token and
     * fence, invalidations 3 -> 4). The adopted writer started under 3 writes nothing, and nothing acquires a new lifetime for
     * it. Only afterwards, a refresh adopts under 4 and that answer is written.
     */
    @Test fun X04_aUserHoldBeforeTheDelegateRetiresTheAdoptedWriter() = writeTest {
        val f = Fixture(this)
        var acquiresAtResume = -1
        f.store.beforeWrite = {
            f.snapshot = snap(userBlocks = setOf(TopicAccessBlock.NOT_GRANTED), invalidations = 4L)
            f.snapshot = snap(invalidations = 4L)
            acquiresAtResume = f.acquires
        }
        f.adopt(this)
        assertEquals("delegated", 1, f.store.written.size)
        assertEquals(emptyList<File>(), f.files.prepares)
        assertNull(f.diskGeneral())
        assertEquals("no acquire after the release", acquiresAtResume, f.acquires)

        f.store.beforeWrite = null
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("a new lifetime writes", 1600.0, f.diskGeneral())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** A blocked or failed preparation cancels its own ticket. */
    @Test fun X05_aBlockedPreparationCancelsItsTicket() = writeTest {
        val cause = IOException("record unreadable")
        for ((label, answer) in listOf<Pair<String, (GraphV2AccessCapture, Boolean) -> GraphV2WritePreparation>>(
            "GENERAL blocked" to { _, _ -> GraphV2WritePreparation(GraphV2NamespacePreparation.Blocked("not durable"), null) },
            "preparation throws" to { _, _ -> throw cause }
        )) {
            val f = Fixture(this)
            f.prepareAnswer = answer
            f.adopt(this)
            assertEquals(label, 1, f.prepareCalls.size)
            assertEquals(label, 1, f.store.tickets.size)
            assertEquals("$label: that ticket is cancelled", f.store.tickets.toSet(), f.store.cancelled.toSet())
            assertEquals(label, 0, f.store.written.size)
            f.close()
        }
    }

    /**
     * The preparation starts only after the adoption is published. The loop wakes from a dispatched task with the adoption
     * already buffered (the 200's resumption is dispatched before a control event's), where a launch would start in place.
     */
    @Test fun X06_thePreparationSeesThePublishedAdoption() = writeTest {
        val f = Fixture(this, dispatcher = InPlaceDispatcher(StandardTestDispatcher(testScheduler)))
        var seenStamp: Instant? = null
        var seenExposure: GraphEntry? = null
        f.onPrepare = {
            seenStamp = f.state.entries[KEY]?.online200At
            seenExposure = f.coordinator.protectedEntry(KEY)
        }
        f.start(); f.coordinator.onActivated(KEY); runCurrent()
        f.sent.single().tab.complete(ok(onlineDto(1500.0)))
        f.coordinator.onContextChanged()
        runCurrent()
        assertEquals(1, f.prepareCalls.size)
        assertEquals("the stamp is public", NOON, seenStamp)
        assertEquals("the exposure is public", NOON, seenExposure?.online200At)
        assertEquals(1500.0, f.diskGeneral())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    // --- B1b-2b-2b: ordering, ownership, cleanup and schedule contention ----------------------------------------

    private val other = GraphKey("usd", GraphPeriod.ONE_YEAR)

    /** Reservation follows adoption: R2 is reserved, prepared and blocked while R1 waits; R1's Ready then loses to R2. */
    @Test fun Y01_reservationFollowsAdoption() = writeTest {
        val f = Fixture(this)
        f.put(components(serverTab(1200.0)))
        f.holdCall[0] = CompletableDeferred()
        f.answerAt[1] = GraphV2WritePreparation(GraphV2NamespacePreparation.Blocked("not durable"), null)
        f.adopt(this, 1500.0)
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("R2 was prepared", 2, f.prepareCalls.size)
        checkNotNull(f.holdCall[0]).complete(Unit); runCurrent()
        assertEquals("reserved in adoption order", listOf(1500.0, 1600.0), f.store.reserved.map { rateOf(it.first) })
        assertEquals("R1 was delegated", 1, f.store.written.size)
        assertEquals(emptyList<File>(), f.files.prepares)
        assertEquals("the stored answer stays", 1200.0, f.diskGeneral())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** A later answer whose start capture was null still reserves (and cancels), so the waiting R1 loses to it. */
    @Test fun Y02_aNullCaptureStillSupersedes() = writeTest {
        val f = Fixture(this)
        f.put(components(serverTab(1200.0)))
        f.holdCall[0] = CompletableDeferred()
        f.adopt(this, 1500.0)
        f.protectedOpen = false
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.protectedOpen = true
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("R2 reserved", 2, f.store.tickets.size)
        assertTrue("and cancelled at once", f.store.tickets[1] in f.store.cancelled)
        assertEquals("R2 is not prepared", 1, f.prepareCalls.size)
        checkNotNull(f.holdCall[0]).complete(Unit); runCurrent()
        assertEquals("R1 was delegated", 1, f.store.written.size)
        assertEquals(emptyList<File>(), f.files.prepares)
        assertEquals(1200.0, f.diskGeneral())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /** A completion releases its own writer only: R1's late completion leaves R2 waiting, and R2 is then written. */
    @Test fun Y03_aCompletionReleasesOnlyItsOwnWriter() = writeTest {
        val f = Fixture(this)
        val r1Returns = CompletableDeferred<Unit>()
        f.store.holdAfterWrite[0] = r1Returns
        f.holdCall[1] = CompletableDeferred()
        f.adopt(this, 1500.0)
        assertEquals("R1 is stored; its completion is not delivered", 1500.0, f.diskGeneral())
        f.coordinator.onRefreshRequested(force = true); runCurrent()
        f.sent.last().tab.complete(ok(onlineDto(1600.0))); runCurrent()
        assertEquals("R2 waits in preparation", 2, f.prepareCalls.size)
        val (t1, t2) = f.store.tickets
        r1Returns.complete(Unit); runCurrent()
        assertTrue("R1's completion releases R1's ticket", t1 in f.store.cancelled)
        assertTrue("and not R2's", t2 !in f.store.cancelled)
        checkNotNull(f.holdCall[1]).complete(Unit); runCurrent()
        assertEquals(1600.0, f.diskGeneral())
        assertEquals(emptyList<Throwable>(), f.failures)
    }

    /**
     * A real context change (the lifetime moves to invalidations 4), the scope's end or close() cancels a writer that is
     * preparing or writing, before any late result. Its job ends: nothing is delegated or written afterwards. close() cancels
     * it before it returns (S4 RT01-A3).
     */
    @Test fun Y04_aContextChangeOrScopeEndCancelsPendingWriters() = writeTest {
        for (end in listOf("context", "scope", "close")) for (stage in listOf("preparing", "writing")) {
            val label = "$end/$stage"
            val f = Fixture(this)
            val hold = CompletableDeferred<Unit>()
            if (stage == "preparing") f.holdPrepare = hold else f.store.holdBeforeWrite = hold
            f.adopt(this)
            val ticket = f.store.tickets.single()
            assertTrue("$label: not cancelled yet", ticket !in f.store.cancelled)
            if (end == "context") {
                // A USER hold came and went: the context's lifetime is now invalidations 4.
                f.snapshot = snap(invalidations = 4L)
                f.coordinator.onContextChanged()
            } else if (end == "close") {
                f.coordinator.close()
                assertTrue("$label: cancelled before close returns", ticket in f.store.cancelled)
            } else {
                f.close()
            }
            runCurrent()
            assertTrue("$label: cancelled", ticket in f.store.cancelled)
            hold.complete(Unit); runCurrent()
            assertEquals("$label: nothing is delegated", 0, f.store.written.size)
            assertEquals("$label: nothing is written", emptyList<File>(), f.files.prepares)
            assertEquals(label, emptyList<Throwable>(), f.failures)
            f.close()
        }
    }

    /** Deactivation or another key's activation keeps an adopted writer: it is written when its preparation is Ready. */
    @Test fun Y05_deactivationKeepsAnAdoptedWriter() = writeTest {
        for (how in listOf("deactivate", "another key")) {
            val f = Fixture(this)
            f.holdPrepare = CompletableDeferred()
            f.adopt(this)
            if (how == "deactivate") f.coordinator.onDeactivated() else f.coordinator.onActivated(other)
            runCurrent()
            checkNotNull(f.holdPrepare).complete(Unit); runCurrent()
            assertEquals(how, 1500.0, f.diskGeneral())
            assertEquals(how, 1505.0, f.diskKrx())
            assertEquals(emptyList<Throwable>(), f.failures)
            f.close()
        }
    }

    /** B07 cold: X's write fails at t=2 while Y's cold ladder runs; each failure kind leaves Y's sends and A2's catch alone. */
    @Test fun Y06_aWriteFailureLeavesAnotherKeysColdRetry() = writeTest {
        for (failure in listOf("file prepare", "file publish", "store adapter", "diagnostic observer")) {
            val f = Fixture(this)
            f.holdPrepare = CompletableDeferred()
            when (failure) {
                "file prepare" -> f.files.failPrepare = { true }
                "file publish" -> f.files.failPublish = { true }
                "store adapter" -> f.store.writeThrows = IOException("adapter")
                "diagnostic observer" -> {
                    f.files.failPublish = { true }
                    f.writeDiagnosticThrows = true
                }
            }
            fun elapsed() = testScheduler.currentTime - f.base
            fun ySends() = f.sent.filter { it.key == other }
            f.adopt(this)
            step(1.seconds)
            f.coordinator.onActivated(other); runCurrent()
            ySends().last().tab.complete(status(503)); runCurrent()
            step(1.seconds)
            checkNotNull(f.holdPrepare).complete(Unit); runCurrent()
            assertTrue("$failure: the write failed and was diagnosed", f.writeDiagnostics.isNotEmpty())
            for (at in listOf(4_000L, 10_000L, 22_000L, 46_000L, 94_000L)) {
                advanceTimeBy(at - elapsed()); runCurrent()
                ySends().last().tab.complete(status(503)); runCurrent()
            }
            step(200.seconds)
            assertEquals(failure, listOf(1_000L, 4_000L, 10_000L, 22_000L, 46_000L, 94_000L), ySends().map { it.atMs })
            assertEquals(failure, emptyList<Throwable>(), f.failures)
            f.close()
        }
    }

    /**
     * A write completion does not run the common rearm path. X's completion and Y's floor Wake are due at t=10, X's armed
     * first, and a deactivation is queued after both: the due Wake sends Y before the deactivation.
     */
    @Test fun Y07_aWriteCompletionLeavesTheDueWake() = writeTest {
        for (completion in listOf("preparation", "disk write")) {
            val f = Fixture(this)
            if (completion == "preparation") f.prepareDelay = 10.seconds else f.store.afterWriteDelay = 10.seconds
            f.adopt(this)
            step(7.seconds)
            f.coordinator.onActivated(other); runCurrent()
            // A 429 without Retry-After: the floor and Y's cold deadline are both t=10.
            f.sent.last().tab.complete(status(429)); runCurrent()
            f.scope.launch { delay(3.seconds); f.coordinator.onDeactivated() }
            step(3.seconds)
            assertEquals("$completion: Y was sent at t=10", listOf(0L, 7_000L, 10_000L), f.sent.map { it.atMs })
            assertEquals(emptyList<Throwable>(), f.failures)
            f.close()
        }
    }
}
