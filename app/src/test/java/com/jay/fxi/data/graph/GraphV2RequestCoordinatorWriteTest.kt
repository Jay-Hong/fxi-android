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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Claude-owned S4 B1b-2b-1 contract r2: the request owner writes an adopted online answer through to the Graph V2 disk store.
 *
 * Oracles: ANDROID_V2_PLAN.md :499 (a namespace's data marker is established before data is stored there), :1289 (only an
 * online 200 records freshness; a store outcome never does), :1294 (a store failure is diagnostic), :1338-1340 (no late
 * writer revives a namespace). Design: R4c/S4 b1b2b_design_codex.r1 as trimmed by b1b2b_review_claude.r1 and
 * b1b2b_verdict_codex.r1 (T1-T5): write ports as one nullable bundle on the cache ports; the adopted slot's components are
 * reserved at once, before any asynchronous preparation; the write capture is the request's start capture (a null one
 * reserves and cancels); a seed is never written back; Ready is the producer's persistence contract and is checked per
 * axis (GENERAL: owner, USER epoch, premium marker; KRX: the same on its own record plus the captured KRX epoch and the KRX
 * marker); a blocked GENERAL stops the whole write, a blocked KRX the KRX half; store outcomes change no entry, stamp,
 * failure, retry owner or request. Contention and lifetime are B1b-2b-2.
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
        override fun read(file: File): ByteArray? = real.read(file)
        override fun prepareReplace(target: File, bytes: ByteArray): GraphV2PreparedReplace {
            prepares += target
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

    /** The real store, recording what the request owner asks of it. */
    private class WriteStore(private val real: GraphV2DiskStore) : GraphV2DiskStore by real {
        val reserved = mutableListOf<Pair<GraphV2DiskComponents, GraphV2WriteReservation>>()
        val cancelled = mutableListOf<GraphV2WriteTicket>()
        val written = mutableListOf<Pair<GraphV2WriteTicket, GraphV2WriteReport>>()
        override fun reserveWrite(components: GraphV2DiskComponents): GraphV2WriteReservation =
            real.reserveWrite(components).also { reserved += components to it }
        override fun cancelWrite(ticket: GraphV2WriteTicket) {
            cancelled += ticket
            real.cancelWrite(ticket)
        }
        override suspend fun write(ticket: GraphV2WriteTicket, admission: GraphV2IoAdmission): GraphV2WriteReport =
            real.write(ticket, admission).also { written += ticket to it }
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

    private inner class Fixture(test: TestScope, writes: Boolean = true) {
        init { opened += this }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val base = test.testScheduler.currentTime
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
        var holdPrepare: CompletableDeferred<Unit>? = null
        var prepareAnswer: (GraphV2AccessCapture, Boolean) -> GraphV2WritePreparation = { _, wantsKrx ->
            GraphV2WritePreparation(GraphV2NamespacePreparation.Ready(M), if (wantsKrx) GraphV2NamespacePreparation.Ready(M) else null)
        }
        val blocked = mutableListOf<GraphV2PreparationBlocked>()
        var blockedThrows = false
        val writeDiagnostics = mutableListOf<GraphV2WriteDiagnostic>()
        var writeDiagnosticThrows = false

        val uses = object : TopicUseAuthority {
            private val inner = SnapshotTopicUseAuthority { snapshot }
            override fun acquire(fence: TopicSessionFence): TopicUseLifetime? = inner.acquire(fence)
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
                prepareCalls += captured to wantsKrx
                holdPrepare?.await()
                prepareAnswer(captured, wantsKrx)
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
}
