package com.jay.fxi.ui.premium.graph

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
import com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo
import com.jay.fxi.data.graph.FileGraphV2DiskStore
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRuntimeRetirement
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2CachePorts
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionUnreadableReason
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
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
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.GraphSeriesSelectionPolicy
import com.jay.fxi.time.AppClock
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphSeriesStyles
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S4 C1-1b contract r2: the premium graph screen state holder over the real request owner, gate and selection
 * session. r2 adds W03c, H06d, H06e, BToken and two assertions (battery r1 survivors H06, H07, H14, H19, H23, H28). r3 adopts,
 * after independent review, Codex's r2 boundary probes as the L-rows (H02 and H22 among them); L7 expects the opposite of
 * the probe it came from: the same identity's new owner reads the pending save back once instead of waiting forever. r4: H07d
 * also forbids falling back to the first-check state while the uncertain save is confirmed (battery r3 survivor H37). r5
 * (with C1-2a): H07a also checks each toggle carries its protected series' axisGroup. r6 (C1-2b-1): H02b also checks the
 * writes, the catalog requests and the binding instance across the activations and the fullscreen round trip; H08p - a
 * period chosen in the fullscreen keeps it open with new tokens (iOS a36682f GraphV2Section.swift :445-470); H08r - retire,
 * deactivation and a closed protected admission remove it for good. r7 (battery r1 survivor K02): H08r also covers a use
 * invalidation re-acquired at once (closed by the render that the gate refuses first) and a focus that leaves the tab and
 * comes back - the gate does not see focus, so the render does not close it, and the same owner re-acquires without
 * onActivated's second retire: only the retire itself closes the fullscreen there. r8 (S4 RT01-B3, API agreed in R4c/S4
 * rt01b3_api_agreed.r2): B3H01, B3H02 and B3H04-B3H06 drive the runtime port (coordinator first, then this holder, one
 * block on the fixture's dispatcher) and the holder's retirement calls - the screen empty at the port's return, KRX
 * retirement without a forced retire, the port's failures and its walk over a copy of the holder list. The explicit
 * retire of a selected context is equivalent here: holder and coordinator share one fence supplier, so the render
 * retires the same context (PT graphB301 fixes that supplier in the assembly). r9 (S4 RT03b-0, API agreed in R4c/S4
 * rt03b0_api_agreed.r2): the retired-snapshot guard and its probe are removed - a code-review point, since no row can
 * observe their absence; the holder reads a request snapshot's catalog and failure only under the use it was
 * synchronized for (its source), so B3H03 - which drives the coordinator and the holder's activation, not the port -
 * holds a new use of the same scope and fence, and another fence with the same lifetime, off the old use's catalog and
 * failure while the coordinator has not moved (U4).
 *
 * Oracles: ANDROID_V2_PLAN.md :1286 (catalog periods per tab only, server X axis, insufficient history is a 200), :1288-1292
 * (protected reads only under the current access, KRX joined only under the current capability, visible and initialized saved
 * together per UID), :318 (no protected use outside the activation order). Design: R4c/S4 c1_design_codex.r1 rows 01-07,
 * c1_1_api_codex.r1 §2-§8 (holder signature, state, tokens, Uncertain read-back once, krxVisible from the gate, preparation key,
 * context change order, fixture), c1_1_scope_codex.r1 (W03, W03b replace re-asserting shared arithmetic; prepared reuse is
 * asserted as the same instance, builder calls are a review point), c11b_mapping_codex.r1 (content precedence INACTIVE >
 * BLOCKED > UNSUPPORTED > no entry LOADING/ERROR > SELECTION_PENDING > presenter; chart only when an entry is exposed and the
 * selection is READY, non-null on NO_DATA; toggles only from the exposed entry, disabled and unchecked while the selection is
 * not READY; selection null while not READY; initialize from Catalog / CatalogUnavailable / Unsupported, never rewritten
 * automatically after NotCommitted; start binds and restores without activating; repeated activations merge; tokens need an
 * active, valid context and a binding, not an entry; a withdrawn period keeps its key active and starts no new request).
 *
 * iOS divergence, intended: Android shows only the tab's catalog periods (S4 plan), where iOS a36682f GraphV2Section.swift:549
 * always draws all four; a null catalog is the A1 fallback of four periods, a catalog without the tab gives none, and top-level
 * supported_periods and other tabs' periods are never used.
 *
 * Fixture: the real GraphV2RequestCoordinator with a fake fetcher (each tab request waits for its answer), the real
 * FileGraphV2DiskStore and gate (no write ports), the real SnapshotTopicUseAuthority over one mutable published snapshot, the
 * real GraphSeriesSelectionSession over a fake store that does not serialise and can pause, and mutable display, focus and
 * revision flows. Everything runs on one test scheduler; steps are runCurrent and explicit answers, never advanceUntilIdle,
 * because the request owner keeps timers. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphV2ScreenStateHolderTest {

    @get:Rule val folder = TemporaryFolder()

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        /** 00:00 KST on 2026-10-05, the 1d rolling window's start. */
        val DAY0: Instant = Instant.parse("2026-10-04T15:00:00Z")
        val A = AuthIdentityFence("u1", 1L)
        val B = AuthIdentityFence("u2", 1L)
        val FENCE_A = TopicSessionFence(A, "e1", TopicGrantToken(7L))
        val FENCE_B = TopicSessionFence(B, "e2", TopicGrantToken(8L))
        val OWNER_A = TopicDisplayOwner(A, 1L)
        val KEY_1D = GraphKey("usd", GraphPeriod.ONE_DAY)
        val KEY_3M = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        const val X = "hana.usd"
        const val Y = "kb.usd"
        const val Z = "investing.usd"
        const val DXY = "dxy"
        const val KRX = "krx.usd-krw-futures"
        val HELD = setOf(TopicAccessBlock.LOSS_CANDIDATE)
        fun sel(v: Set<String>, i: Set<String>) = GraphSeriesSelection(v, i)
        fun key(uid: String) = GraphSelectionKey(uid, GraphSelectionAudience.PREMIUM, "usd")
    }
    // --- the published topic access (the cache contract's issuer model) ------------------------------------------

    private fun snap(
        uid: String = "u1",
        gen: Long = 1L,
        token: Long? = 7L,
        epoch: String? = "e1",
        krx: String? = "K1",
        userBlocks: Set<TopicAccessBlock> = emptySet(),
        capabilityBlocks: Set<TopicAccessBlock> = emptySet(),
        generation: Long = 10L,
        invalidations: Long = 3L
    ): TopicAccessSnapshot {
        val binding = EntitlementsIdentity(uid, gen)
        val record = AccessFence(uid, epoch, krx)
        val issued = token?.let { TopicGrantContext(binding, record, generation) }
        val userUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in userBlocks
        return TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = TopicAccessFacts.NONE.copy(
                token = token?.let(::TopicGrantToken),
                issuedFor = issued,
                binding = binding,
                recordFence = record,
                decisionGeneration = generation,
                tokenStanding = issued != null && !userUncertain,
                userBlocks = userBlocks,
                capabilityBlocks = capabilityBlocks,
                userContextUncertain = userUncertain,
                capabilityContextUncertain = TopicAccessBlock.CONTEXT_UNCERTAIN in capabilityBlocks
            ),
            userInvalidations = invalidations
        )
    }

    // --- responses ---------------------------------------------------------------------------------------------

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    private fun <T> status(code: Int): AuthenticatedHttpResponse<T> = AuthenticatedHttpResponse(
        code, Headers.headersOf(), null,
        AuthenticatedHttpFailure(code, Headers.headersOf(), byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP),
        byteArrayOf(1)
    )

    /** One catalog period: every listed id, the defaults visible. */
    private fun period(all: List<String>, defaults: List<String>) = GraphV2CatalogPeriod(all, defaults)

    private fun catalog(vararg tabs: Pair<String, Map<String, GraphV2CatalogPeriod>>, ttlSeconds: Int = 172800) =
        GraphV2CatalogResponse(
            tabs = tabs.map { (tab, periods) -> GraphV2CatalogTab(tab, tab, emptyMap(), periods) },
            version = "2026-10-05",
            supportedPeriods = listOf("1y"),
            cacheTtlSeconds = ttlSeconds
        )

    private class S(val id: String, val points: List<GraphV2Point>, val axis: String = "krw")

    private fun pts(n: Int, base: Double, from: Instant = DAY0 + 1.hours, step: kotlin.time.Duration = 10.minutes) =
        List(n) { i -> GraphV2Point(from + step * i, base + i, "x", base + i + 1, base + i - 1) }

    /** A 1d answer declares the KST day as its rolling window; the long periods declare none. */
    private fun dto(period: GraphPeriod, vararg series: S, tab: String = "usd") = GraphV2TabResponse(
        tab = tab,
        period = period.code,
        series = series.map {
            GraphV2Series(it.id, it.id, it.axis, if (it.axis == "krw") "KRW" else "pt", 2, it.points,
                GraphV2Provenance(false, emptyList()))
        },
        metadata = if (period == GraphPeriod.ONE_DAY) {
            GraphV2Metadata(NOON - 1.minutes, "10min", GraphV2Range("2026-10-05", "2026-10-05"),
                domainStartAt = DAY0, domainEndAt = DAY0 + 24.hours, liveDomainMode = "rolling")
        } else {
            GraphV2Metadata(NOON - 1.minutes, "1d", GraphV2Range("2026-07-05", "2026-10-05"))
        }
    )

    // --- the selection store: does not serialise, pauses a named operation, records calls ------------------------

    private class Pause {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class FakeSelectionStore : GraphSelectionStore {
        val committed = mutableMapOf<GraphSelectionKey, GraphSeriesSelection>()
        val log = mutableListOf<String>()
        private val pauses = mutableMapOf<String, ArrayDeque<Pause>>()
        val nextConfirm = ArrayDeque<GraphSelectionReadResult>()
        val nextWrite = ArrayDeque<(GraphSelectionKey, GraphSeriesSelection) -> GraphSelectionWriteResult>()

        fun pauseNext(op: String) = Pause().also { pauses.getOrPut(op) { ArrayDeque() }.addLast(it) }
        val writes get() = log.count { it.startsWith("write:") }
        val confirms get() = log.count { it.startsWith("confirm:") }

        private suspend fun gate(op: String) {
            val pause = pauses[op]?.removeFirstOrNull() ?: return
            pause.reached.complete(Unit)
            pause.release.await()
        }

        private fun current(key: GraphSelectionKey) = committed[key]?.let {
            GraphSelectionReadResult.Present(
                GraphSelectionRecord(1, key.uid, key.audience, key.tab, it.visibleSeriesIds, it.initializedSeries)
            )
        } ?: GraphSelectionReadResult.Absent

        override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            log += "confirm:${key.uid}"
            gate("confirm:${key.uid}")
            return nextConfirm.removeFirstOrNull() ?: current(key)
        }

        override suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            log += "read:${key.uid}"
            return current(key)
        }

        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult {
            log += "write:${key.uid}:${selection.visibleSeriesIds.sorted()}/${selection.initializedSeries.sorted()}"
            gate("write:${key.uid}")
            val outcome = nextWrite.removeFirstOrNull()
            return if (outcome != null) outcome(key, selection) else {
                committed[key] = selection
                GraphSelectionWriteResult.Committed
            }
        }
    }

    // --- the fixture --------------------------------------------------------------------------------------------

    private val opened = mutableListOf<Fixture>()

    private fun holderTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private class Sent(val key: GraphKey) {
        val answer = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
    }

    private inner class Fixture(val test: TestScope, private val start: Instant = NOON, private val withPorts: Boolean = true) {
        init { opened += this }
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val base = test.testScheduler.currentTime

        var identity: AuthIdentityFence? = A
        var fence: TopicSessionFence? = FENCE_A
        var protectedOpen = true
        var snapshot: TopicAccessSnapshot = snap()
        /** Null makes the catalog request fail, which leaves the request owner without a catalog (the A1 fallback). */
        var catalogDto: GraphV2CatalogResponse? = catalog("usd" to mapOf(
            "1d" to period(listOf(X, Y, Z, DXY), listOf(X)),
            "3m" to period(listOf(X, Y, Z, DXY), listOf(X))
        ))
        val display = MutableStateFlow(TopicDisplayState.NONE.copy(owner = OWNER_A))
        val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(A, FreeTab.USD))
        val accessRevisions = MutableStateFlow(0L)
        val sent = mutableListOf<Sent>()
        var catalogCalls = 0
        val failures = mutableListOf<Throwable>()

        val uses = SnapshotTopicUseAuthority { snapshot }

        val owners = object : GraphOwnerSource {
            override fun currentIdentity(): AuthIdentityFence? = identity
            override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
                if (identity != expected) throw AuthIdentityChangedException()
                return AuthSnapshot(expected.uid, expected.authGeneration, "token")
            }
        }

        val gate = GraphV2AccessGate(
            currentIdentity = { identity },
            currentAccessFence = { fence },
            snapshot = { snapshot },
            protectedAdmission = { protectedOpen }
        )

        val root: File = folder.newFolder()
        val store = FileGraphV2DiskStore({ root }, JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), dispatcher)

        val fetcher = object : GraphV2Fetching {
            override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2CatalogResponse> {
                catalogCalls++
                return catalogDto?.let { ok(it) } ?: status(500)
            }
            override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2TabResponse> {
                val s = Sent(key)
                sent += s
                return s.answer.await()
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
            rateLimitJitter = { kotlin.time.Duration.ZERO },
            onEventFailure = { failures += it },
            cachePorts = if (withPorts) GraphV2CachePorts(store = store, gate = gate, onSeedDiagnostic = {}) else null
        )

        val selections = FakeSelectionStore()
        val session = GraphSeriesSelectionSession(selections, GraphSelectionAudience.PREMIUM, "usd", scope, dispatcher)

        val holder = GraphV2ScreenStateHolder(
            tab = "usd",
            coordinator = coordinator,
            selectionSession = session,
            liveIdentity = { identity },
            display = display,
            focus = focus,
            currentAccessFence = { fence },
            uses = uses,
            gate = gate,
            accessRevisions = accessRevisions,
            scope = scope,
            dispatcher = dispatcher
        )

        val published = mutableListOf<GraphV2ScreenState>()

        fun run() = test.runCurrent()

        /** Starts both owners, activates the holder for the current display owner and settles the first steps. */
        fun open() {
            coordinator.start()
            holder.start()
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { holder.state.collect { published += it } }
            run()
            holder.onActivated(checkNotNull(display.value.owner))
            run()
        }

        fun now() = holder.currentState()

        /** Answers the oldest unanswered request for [key]. */
        fun answer(key: GraphKey, response: AuthenticatedHttpResponse<GraphV2TabResponse>) {
            sent.first { it.key == key && !it.answer.isCompleted }.answer.complete(response)
            run()
        }

        fun sentFor(key: GraphKey) = sent.count { it.key == key }

        fun token() = checkNotNull(now().inlineToken) { "no inline token in ${now()}" }

        fun close() = scope.cancel()
    }

    // --- answers ------------------------------------------------------------------------------------------------

    private fun day(vararg series: S) = ok(dto(GraphPeriod.ONE_DAY, *series))

    private fun fullDay(xBase: Double = 1400.0) =
        day(S(X, pts(6, xBase)), S(Y, pts(6, 1380.0)), S(Z, pts(6, 1390.0)), S(DXY, pts(6, 99.0), "index"))

    private fun quarter(vararg series: S) = ok(dto(GraphPeriod.THREE_MONTHS, *series))

    private fun quarterPts(base: Double) = pts(6, base, DAY0 - 240.hours, 24.hours)

    private fun twoSeriesCatalog(defaults: List<String> = listOf(X)) =
        catalog("usd" to mapOf("1d" to period(listOf(X, Y), defaults)))

    private fun xy() = day(S(X, pts(6, 1400.0)), S(Y, pts(6, 1380.0)))

    // --- 01: periods ----------------------------------------------------------------------------------------------

    /** 01a: the tab's catalog periods only - not another tab's, not top-level supported_periods; an unoffered period is refused. */
    @Test fun H01a_onlyTheTabsCatalogPeriodsAreOffered() = holderTest {
        val f = Fixture(this)
        f.catalogDto = catalog(
            "usd" to mapOf("1d" to period(listOf(X), listOf(X)), "3m" to period(listOf(X), listOf(X))),
            "jpy" to mapOf("1w" to period(listOf("hana.jpy"), listOf("hana.jpy")))
        )
        f.open()
        f.answer(KEY_1D, day(S(X, pts(6, 1400.0))))
        assertEquals(listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), f.now().periods)
        val t = f.token()
        f.holder.selectPeriod(t, GraphPeriod.ONE_WEEK)
        f.holder.selectPeriod(t, GraphPeriod.ONE_YEAR)
        f.run()
        assertEquals(GraphPeriod.ONE_DAY, f.now().activePeriod)
        assertEquals(GraphV2Content.READY, f.now().content)
        assertEquals(0, f.sentFor(GraphKey("usd", GraphPeriod.ONE_WEEK)))
        assertEquals(0, f.sentFor(GraphKey("usd", GraphPeriod.ONE_YEAR)))
    }

    /**
     * 01b: without a catalog every period is offered (A1 fallback); a catalog without the tab offers none and the screen is
     * UNSUPPORTED with no chart even though an answer arrived; a current period a new catalog withdraws stays selected,
     * UNSUPPORTED, with no chart or toggles, no new request for it and no initialize.
     */
    @Test fun H01b_noCatalogAMissingTabAndAWithdrawnPeriodAreToldApart() = holderTest {
        val f = Fixture(this)
        f.catalogDto = null
        f.open()
        assertEquals(GraphPeriod.entries.toList(), f.now().periods)

        val g = Fixture(this)
        g.catalogDto = catalog("jpy" to mapOf("1d" to period(listOf("hana.jpy"), listOf("hana.jpy"))))
        g.open()
        g.answer(KEY_1D, fullDay())
        assertEquals(emptyList<GraphPeriod>(), g.now().periods)
        assertEquals(GraphV2Content.UNSUPPORTED, g.now().content)
        assertNull(g.now().chart)
        assertEquals(emptyList<GraphV2SeriesToggle>(), g.now().toggles)

        val h = Fixture(this)
        h.catalogDto = catalog(
            "usd" to mapOf("1d" to period(listOf(X), listOf(X)), "3m" to period(listOf(X), listOf(X))), ttlSeconds = 60
        )
        h.open()
        h.answer(KEY_1D, day(S(X, pts(6, 1400.0))))
        h.holder.selectPeriod(h.token(), GraphPeriod.THREE_MONTHS)
        h.run()
        h.answer(KEY_3M, quarter(S(X, quarterPts(1400.0))))
        assertEquals(GraphV2Content.READY, h.now().content)
        h.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X), listOf(X))), ttlSeconds = 60)
        advanceTimeBy(61.seconds.inWholeMilliseconds)
        h.coordinator.onRefreshRequested()
        h.run()
        // A 3m request may have started in that pump, before the new catalog landed.
        h.sent.filter { it.key == KEY_3M && !it.answer.isCompleted }.forEach { it.answer.complete(quarter(S(X, quarterPts(1400.0)))) }
        h.run()
        assertEquals(GraphPeriod.THREE_MONTHS, h.now().activePeriod)
        assertEquals(GraphV2Content.UNSUPPORTED, h.now().content)
        assertNull(h.now().chart)
        assertEquals(emptyList<GraphV2SeriesToggle>(), h.now().toggles)
        val writes = h.selections.writes
        val sent3m = h.sentFor(KEY_3M)
        h.coordinator.onRefreshRequested(force = true)
        h.run()
        assertEquals("no new request for the withdrawn period", sent3m, h.sentFor(KEY_3M))
        assertEquals("no initialize for an unsupported period", writes, h.selections.writes)
    }

    // --- 02: the active key ---------------------------------------------------------------------------------------

    /**
     * 02a: a period change takes effect at once and shows only that period - a late answer for the previous period draws
     * nothing; the new period is LOADING until its answer, then READY; a failed answer for it is ERROR, not another chart.
     */
    @Test fun H02a_aPeriodChangeShowsOnlyThatPeriod() = holderTest {
        val f = Fixture(this)
        f.open()
        f.holder.selectPeriod(f.token(), GraphPeriod.THREE_MONTHS)
        f.run()
        assertEquals(GraphPeriod.THREE_MONTHS, f.now().activePeriod)
        assertEquals(GraphV2Content.LOADING, f.now().content)
        assertFalse("nothing exposed is refreshing", f.now().refreshing)
        assertNull(f.now().chart)
        val mark = f.published.size
        f.answer(KEY_1D, fullDay())
        assertEquals(GraphV2Content.LOADING, f.now().content)
        assertNull(f.now().chart)
        assertTrue("no 1d chart after the change", f.published.drop(mark).none { it.chart?.prepared?.period == GraphPeriod.ONE_DAY })
        f.answer(KEY_3M, quarter(S(X, quarterPts(1400.0)), S(Y, quarterPts(1380.0))))
        assertEquals(GraphV2Content.READY, f.now().content)
        assertEquals(GraphPeriod.THREE_MONTHS, checkNotNull(f.now().chart).prepared.period)

        val g = Fixture(this)
        g.open()
        g.answer(KEY_1D, fullDay())
        g.holder.selectPeriod(g.token(), GraphPeriod.THREE_MONTHS)
        g.run()
        g.answer(KEY_3M, status(500))
        assertEquals(GraphV2Content.ERROR, g.now().content)
        assertNotNull(g.now().requestFailure)
        assertNull(g.now().chart)
    }

    /**
     * 02b: a refresh over a last good answer is `refreshing`; its failure keeps the same prepared chart and shows the failure;
     * repeated activation and a fullscreen round trip bind, restore and request nothing.
     */
    @Test fun H02b_aRefreshFailureKeepsTheLastGoodAnswer() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val chart = checkNotNull(f.now().chart)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        assertTrue(f.now().refreshing)
        assertEquals(GraphV2Content.READY, f.now().content)
        f.answer(KEY_1D, status(500))
        assertEquals(GraphV2Content.READY, f.now().content)
        assertFalse(f.now().refreshing)
        assertNotNull(f.now().requestFailure)
        assertSame(chart.prepared, checkNotNull(f.now().chart).prepared)

        val confirms = f.selections.confirms
        val writes = f.selections.writes
        val sent = f.sent.size
        val catalogCalls = f.catalogCalls
        val binding = f.token().binding
        f.holder.onActivated(OWNER_A)
        f.holder.onActivated(OWNER_A)
        f.run()
        f.holder.enterFullscreen(f.token())
        f.run()
        assertTrue(f.now().fullscreenOpen)
        assertSame("no rebind for the fullscreen", binding, checkNotNull(f.now().fullscreenToken).binding)
        f.holder.exitFullscreen(checkNotNull(f.now().fullscreenToken))
        f.run()
        assertFalse(f.now().fullscreenOpen)
        assertEquals(confirms, f.selections.confirms)
        assertEquals(writes, f.selections.writes)
        assertEquals(sent, f.sent.size)
        assertEquals(catalogCalls, f.catalogCalls)
        assertSame("no rebind for the activations or the round trip", binding, f.token().binding)
    }

    /**
     * 08 period: a period chosen in the fullscreen keeps it open - iOS a36682f's fullscreen cover holds its own period bar and
     * only its close button or a tap dismisses it. Both tokens are new, so the fullscreen token from before the change can
     * neither close the fullscreen nor change the period, and the new fullscreen token closes it.
     */
    @Test fun H08p_aPeriodChosenInTheFullscreenKeepsItOpen() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        f.holder.enterFullscreen(f.token())
        f.run()
        val before = checkNotNull(f.now().fullscreenToken)
        val inlineBefore = f.token()
        f.holder.selectPeriod(before, GraphPeriod.THREE_MONTHS)
        f.run()
        assertEquals(GraphPeriod.THREE_MONTHS, f.now().activePeriod)
        assertTrue("the fullscreen stays open", f.now().fullscreenOpen)
        val after = checkNotNull(f.now().fullscreenToken) { "the open fullscreen has a token" }
        assertEquals(GraphPeriod.THREE_MONTHS, after.period)
        assertTrue("a new screen", after.screenGeneration != before.screenGeneration)
        assertTrue("a new inline token", f.token() != inlineBefore)
        f.holder.exitFullscreen(before)
        f.run()
        assertTrue("the old fullscreen token cannot close it", f.now().fullscreenOpen)
        f.holder.selectPeriod(before, GraphPeriod.ONE_DAY)
        f.run()
        assertEquals("nor change the period", GraphPeriod.THREE_MONTHS, f.now().activePeriod)
        f.answer(KEY_3M, quarter(S(X, quarterPts(1400.0)), S(Y, quarterPts(1380.0))))
        assertEquals(GraphV2Content.READY, f.now().content)
        assertTrue(f.now().fullscreenOpen)
        f.holder.exitFullscreen(checkNotNull(f.now().fullscreenToken))
        f.run()
        assertFalse("the new fullscreen token closes it", f.now().fullscreenOpen)
        assertNull(f.now().fullscreenToken)
    }

    /**
     * 08 removal: a retired context, a deactivation, a closed protected admission, a use invalidation re-acquired at once and
     * a focus that leaves and comes back still remove the fullscreen, and the screen published again for the same identity
     * does not bring it back.
     */
    @Test fun H08r_aRetiredDeactivatedOrBlockedScreenDropsTheFullscreen() = holderTest {
        val g = Fixture(this)
        g.open()
        g.answer(KEY_1D, fullDay())
        g.holder.enterFullscreen(g.token())
        g.run()
        assertTrue(g.now().fullscreenOpen)
        val next = TopicDisplayOwner(A, 2L)
        g.display.value = TopicDisplayState.NONE.copy(owner = next)
        g.holder.onContextChanged()
        g.run()
        g.holder.onActivated(next)
        g.run()
        assertNotNull("premise: the new owner's screen is published", g.now().inlineToken)
        assertFalse("a retired context removes the fullscreen", g.now().fullscreenOpen)
        assertNull(g.now().fullscreenToken)

        val h = Fixture(this)
        h.open()
        h.answer(KEY_1D, fullDay())
        h.holder.enterFullscreen(h.token())
        h.run()
        h.holder.onDeactivated()
        h.run()
        h.holder.onActivated(OWNER_A)
        h.run()
        assertNotNull("premise: the reactivated screen is published", h.now().inlineToken)
        assertFalse("a deactivation removes the fullscreen", h.now().fullscreenOpen)
        assertNull(h.now().fullscreenToken)

        val k = Fixture(this)
        k.open()
        k.answer(KEY_1D, fullDay())
        k.holder.enterFullscreen(k.token())
        k.run()
        k.protectedOpen = false
        k.accessRevisions.value += 1
        assertEquals(GraphV2Content.BLOCKED, k.now().content)
        k.run()
        assertEquals("still blocked once the revision is handled", GraphV2Content.BLOCKED, k.now().content)
        k.protectedOpen = true
        k.accessRevisions.value += 1
        k.run()
        assertNotNull("premise: the reopened screen is published", k.now().inlineToken)
        assertFalse("a closed protected admission removes the fullscreen", k.now().fullscreenOpen)
        assertNull(k.now().fullscreenToken)

        val u = Fixture(this)
        u.open()
        u.answer(KEY_1D, fullDay())
        u.holder.enterFullscreen(u.token())
        u.run()
        assertTrue(u.now().fullscreenOpen)
        val before = u.token()
        u.snapshot = snap(invalidations = 4L)
        assertNull(u.now().fullscreenToken)
        u.holder.onContextChanged()
        u.run()
        assertEquals("premise: the same owner re-acquired a new use", 4L, u.token().lifetime.invalidations)
        assertEquals("premise: no reactivation was needed", before.owner, u.token().owner)
        assertFalse("a use invalidation removes the fullscreen", u.now().fullscreenOpen)
        assertNull(u.now().fullscreenToken)

        val v = Fixture(this)
        v.open()
        v.answer(KEY_1D, fullDay())
        v.holder.enterFullscreen(v.token())
        v.run()
        assertTrue(v.now().fullscreenOpen)
        val shown = v.token()
        v.focus.value = OwnedTopicFocus(A, FreeTab.JPY)
        assertNull("the focus left the tab", v.now().inlineToken)
        v.holder.onContextChanged()
        v.run()
        v.focus.value = OwnedTopicFocus(A, FreeTab.USD)
        v.holder.onContextChanged()
        v.run()
        assertNotNull("premise: the same owner re-acquired the tab", v.now().inlineToken)
        assertEquals("premise: no reactivation was needed", shown.owner, v.token().owner)
        assertFalse("a focus that left and came back removes the fullscreen", v.now().fullscreenOpen)
        assertNull(v.now().fullscreenToken)
    }

    // --- W03 / W03b / W10h: what the holder hands the shared builder and projection --------------------------------

    /** W03: the chart is the shared build of the whole exposed graph - every series, selected or not - for the active period. */
    @Test fun W03_theChartIsTheSharedBuildOfTheWholeExposedGraph() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val exposed = checkNotNull(f.coordinator.protectedEntry(KEY_1D))
        val chart = checkNotNull(f.now().chart)
        assertEquals(GraphPreparedBuilder.build(exposed.tab.graph, GraphPeriod.ONE_DAY), chart.prepared)
        assertEquals(setOf(X, Y, Z, DXY), chart.prepared.bySeries.keys)
        assertNotNull("premise: the declared window was taken", chart.prepared.domain)
        assertEquals(setOf(X), chart.renderedIds)
        assertEquals(sel(setOf(X), setOf(X, Y, Z, DXY)), f.now().selection)
    }

    /**
     * W03b: the prepared graph is the same instance across a toggle, a fullscreen round trip and the same answer arriving
     * again; a changed point makes a new one.
     */
    @Test fun W03b_thePreparedGraphIsKeptWhileItsInputIsUnchanged() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val p0 = checkNotNull(f.now().chart).prepared
        f.holder.toggleSeries(f.token(), Y)
        f.run()
        assertEquals(setOf(X, Y), checkNotNull(f.now().chart).renderedIds)
        assertSame(p0, checkNotNull(f.now().chart).prepared)
        f.holder.enterFullscreen(f.token())
        f.run()
        f.holder.exitFullscreen(checkNotNull(f.now().fullscreenToken))
        f.run()
        assertSame(p0, checkNotNull(f.now().chart).prepared)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, fullDay())
        assertSame("the same answer again", p0, checkNotNull(f.now().chart).prepared)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, fullDay(xBase = 1401.0))
        assertNotSame("a changed point", p0, checkNotNull(f.now().chart).prepared)
    }

    /**
     * W03c: the exposed graph is the protected entry narrowed by the current catalog - a newer catalog that drops a series the
     * entry still carries removes it from the toggles and the prepared graph.
     */
    @Test fun W03c_aNewerCatalogNarrowsTheExposedGraph() = holderTest {
        val f = Fixture(this)
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, Y), listOf(X))), ttlSeconds = 60)
        f.open()
        f.answer(KEY_1D, xy())
        assertEquals(setOf(X, Y), f.now().toggles.map { it.seriesId }.toSet())
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X), listOf(X))), ttlSeconds = 60)
        advanceTimeBy(61.seconds.inWholeMilliseconds)
        f.coordinator.onRefreshRequested()
        f.run()
        assertEquals("premise: the entry in hand still carries Y", listOf(X, Y),
            checkNotNull(f.coordinator.protectedEntry(KEY_1D)).tab.graph.series.map { it.seriesId })
        assertEquals(listOf(X), f.now().toggles.map { it.seriesId })
        assertEquals(setOf(X), checkNotNull(f.now().chart).prepared.bySeries.keys)
    }

    /** W10h: the rendered ids follow the selection through the B2a policy with the gate's KRX answer. */
    @Test fun W10h_renderedIdsFollowTheSelectionThroughThePolicy() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        fun check(expected: Set<String>) {
            val now = f.now()
            val chart = checkNotNull(now.chart)
            assertEquals(expected, chart.renderedIds)
            assertEquals(GraphSeriesSelectionPolicy.renderedIds(checkNotNull(now.selection), chart.prepared.bySeries.keys, true),
                chart.renderedIds)
        }
        check(setOf(X))
        f.holder.toggleSeries(f.token(), Y); f.run(); check(setOf(X, Y))
        f.holder.toggleSeries(f.token(), Y); f.run()
        f.holder.toggleSeries(f.token(), DXY); f.run(); check(setOf(X, DXY))
        f.holder.toggleSeries(f.token(), X); f.run(); check(setOf(DXY))
    }

    // --- 06: initialize ---------------------------------------------------------------------------------------------

    /**
     * 06a: an absent record gets the catalog default once; a period change initializes only the ids new to it; the user's off
     * and the raw ids of another period survive; going back writes nothing.
     */
    @Test fun H06a_newIdsAreInitializedOnceAndRawChoicesSurviveAPeriodChange() = holderTest {
        val f = Fixture(this)
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, Y), listOf(X)), "3m" to period(listOf(X, Z), listOf(X))))
        f.open()
        f.answer(KEY_1D, xy())
        assertEquals(sel(setOf(X), setOf(X, Y)), f.selections.committed[key("u1")])
        f.holder.toggleSeries(f.token(), X)
        f.run()
        assertEquals(sel(emptySet(), setOf(X, Y)), f.selections.committed[key("u1")])
        f.holder.selectPeriod(f.token(), GraphPeriod.THREE_MONTHS)
        f.run()
        f.answer(KEY_3M, quarter(S(X, quarterPts(1400.0)), S(Z, quarterPts(1390.0))))
        assertEquals("only Z is new", sel(emptySet(), setOf(X, Y, Z)), f.selections.committed[key("u1")])
        f.holder.selectPeriod(f.token(), GraphPeriod.ONE_DAY)
        f.run()
        assertEquals(3, f.selections.writes)
        assertEquals(sel(emptySet(), setOf(X, Y, Z)), f.selections.committed[key("u1")])
        assertEquals(GraphV2Content.NO_SELECTION, f.now().content)
    }

    /** 06b: an empty record with an empty catalog default is initialized to all-off, not replaced by a default or an absence. */
    @Test fun H06b_anEmptyRecordIsInitializedToAllOff() = holderTest {
        val f = Fixture(this)
        f.selections.committed[key("u1")] = sel(emptySet(), emptySet())
        f.catalogDto = twoSeriesCatalog(defaults = emptyList())
        f.open()
        f.answer(KEY_1D, xy())
        assertEquals(sel(emptySet(), setOf(X, Y)), f.selections.committed[key("u1")])
        assertEquals(GraphV2Content.NO_SELECTION, f.now().content)
    }

    /**
     * 06c: without a catalog only an absent record falls back to every exposed series, and only once an answer is exposed; an
     * empty record stays empty and nothing is written.
     */
    @Test fun H06c_withoutACatalogOnlyAnAbsentRecordShowsEveryExposedSeries() = holderTest {
        val f = Fixture(this)
        f.catalogDto = null
        f.open()
        assertEquals("nothing to initialize from before an answer", 0, f.selections.writes)
        f.answer(KEY_1D, xy())
        assertEquals(sel(setOf(X, Y), setOf(X, Y)), f.selections.committed[key("u1")])
        assertEquals(GraphV2Content.READY, f.now().content)

        val g = Fixture(this)
        g.catalogDto = null
        g.selections.committed[key("u1")] = sel(emptySet(), emptySet())
        g.open()
        g.answer(KEY_1D, xy())
        assertEquals(0, g.selections.writes)
        assertEquals(GraphV2Content.NO_SELECTION, g.now().content)
    }

    /**
     * H06d: an initialize whose save did not commit is not written again on its own - a revision, a refresh, a new answer -
     * and leaves the selection pending (INITIALIZING with the save notice, nothing drawn, toggles disabled) instead of showing
     * an unsaved or empty choice; an explicit retry initializes again.
     */
    @Test fun H06d_aFailedInitializeWaitsForAnExplicitRetry() = holderTest {
        val f = Fixture(this)
        f.catalogDto = twoSeriesCatalog()
        f.selections.nextWrite.addLast { _, _ -> GraphSelectionWriteResult.NotCommitted(IOException("not saved")) }
        f.open()
        f.answer(KEY_1D, xy())
        assertEquals(1, f.selections.writes)
        val now = f.now()
        assertEquals(GraphV2SelectionStatus.INITIALIZING, now.selectionStatus)
        assertEquals(GraphV2Content.SELECTION_PENDING, now.content)
        assertEquals(GraphV2Notice.SAVE_NOT_COMMITTED, now.notice)
        assertNull(now.selection)
        assertNull(now.chart)
        assertTrue(now.toggles.none { it.enabled || it.selected })
        f.accessRevisions.value += 1
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, xy())
        assertEquals("no automatic rewrite", 1, f.selections.writes)
        f.holder.retrySelection(f.token())
        f.run()
        assertEquals(2, f.selections.writes)
        assertEquals(sel(setOf(X), setOf(X, Y)), f.selections.committed[key("u1")])
        assertEquals(GraphV2SelectionStatus.READY, f.now().selectionStatus)
        assertEquals(GraphV2Content.READY, f.now().content)
    }

    /** H06e: under a capability that does not admit KRX, initialize adds no KRX series - the gate's answer reaches the policy. */
    @Test fun H06e_aBlockedCapabilityInitializesNoKrxSeries() = holderTest {
        val f = Fixture(this)
        f.snapshot = snap(capabilityBlocks = HELD)
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, KRX), listOf(X, KRX))))
        f.open()
        f.answer(KEY_1D, day(S(X, pts(6, 1400.0)), S(KRX, pts(6, 1405.0))))
        assertEquals(sel(setOf(X), setOf(X)), f.selections.committed[key("u1")])
        assertEquals(listOf(X), f.now().toggles.map { it.seriesId })
    }

    // --- 07: toggles and their saves --------------------------------------------------------------------------------

    /** 07a: an id no enum knows gets a toggle with the shared style; toggles start from the confirmed selection. */
    @Test fun H07a_aDynamicIdGetsAToggleWithTheSharedStyle() = holderTest {
        val f = Fixture(this)
        val fresh = "newbank.usd"
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, fresh, DXY), listOf(X))))
        f.open()
        f.answer(KEY_1D, day(S(X, pts(6, 1400.0)), S(fresh, pts(6, 1395.0)), S(DXY, pts(6, 99.0), "index")))
        val toggles = f.now().toggles.associateBy { it.seriesId }
        assertEquals(setOf(X, fresh, DXY), toggles.keys)
        assertEquals(GraphSeriesStyles.of(fresh, fresh), toggles.getValue(fresh).style)
        assertEquals("each toggle carries its series' axis", mapOf(X to "krw", fresh to "krw", DXY to "index"),
            toggles.mapValues { it.value.axisGroup })
        assertTrue(toggles.values.all { it.enabled })
        assertEquals(mapOf(X to true, fresh to false, DXY to false), toggles.mapValues { it.value.selected })
        f.holder.toggleSeries(f.token(), fresh)
        f.run()
        assertTrue(f.now().toggles.first { it.seriesId == fresh }.selected)
    }

    /**
     * 07b: a toggle queued behind a held save is computed from the first save's result; until a save commits the screen shows
     * the last confirmed selection, and the toggles stay enabled.
     */
    @Test fun H07b_queuedTogglesAreComputedInTurnAndNotShownBeforeTheyCommit() = holderTest {
        val f = Fixture(this)
        f.catalogDto = twoSeriesCatalog()
        f.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y))
        f.open()
        f.answer(KEY_1D, xy())
        assertEquals("restored, nothing to initialize", 0, f.selections.writes)
        val held = f.selections.pauseNext("write:u1")
        val t = f.token()
        f.holder.toggleSeries(t, X)
        f.run()
        assertTrue(held.reached.isCompleted)
        f.holder.toggleSeries(t, Y)
        f.run()
        assertEquals(sel(setOf(X), setOf(X, Y)), f.now().selection)
        assertTrue(f.now().toggles.all { it.enabled })
        held.release.complete(Unit)
        f.run()
        assertEquals(sel(setOf(Y), setOf(X, Y)), f.selections.committed[key("u1")])
        assertEquals(
            listOf("write:u1:[]/[hana.usd, kb.usd]", "write:u1:[kb.usd]/[hana.usd, kb.usd]"),
            f.selections.log.filter { it.startsWith("write:") }
        )
        assertEquals(sel(setOf(Y), setOf(X, Y)), f.now().selection)
    }

    /** 07c: a save that did not commit keeps the selection, says so, and is not written again on its own. */
    @Test fun H07c_aSaveThatDidNotCommitIsNotRetriedOnItsOwn() = holderTest {
        val f = Fixture(this)
        f.catalogDto = twoSeriesCatalog()
        f.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y))
        f.open()
        f.answer(KEY_1D, xy())
        f.selections.nextWrite.addLast { _, _ -> GraphSelectionWriteResult.NotCommitted(IOException("not saved")) }
        f.holder.toggleSeries(f.token(), X)
        f.run()
        assertEquals(sel(setOf(X), setOf(X, Y)), f.now().selection)
        assertEquals(GraphV2Notice.SAVE_NOT_COMMITTED, f.now().notice)
        assertEquals(1, f.selections.writes)
        f.accessRevisions.value += 1
        f.holder.enterFullscreen(f.token())
        f.run()
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, xy())
        assertEquals("no automatic rewrite", 1, f.selections.writes)
    }

    /**
     * 07d: a save with an uncertain answer is read back exactly once, automatically; meanwhile the selection is unknown (no
     * selection, no chart, toggles disabled and unchecked) and a further toggle writes nothing; the read-back shows the exact
     * pair on disk - the old one when the save did not land, the new one when it did.
     */
    @Test fun H07d_anUncertainSaveIsReadBackOnceAndBlocksChangesMeanwhile() = holderTest {
        for (landed in listOf(false, true)) {
            val f = Fixture(this)
            f.catalogDto = twoSeriesCatalog()
            f.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y))
            f.open()
            f.answer(KEY_1D, xy())
            val confirms = f.selections.confirms
            f.selections.nextWrite.addLast { k, s ->
                if (landed) f.selections.committed[k] = s
                GraphSelectionWriteResult.Uncertain(IOException("no answer"))
            }
            val held = f.selections.pauseNext("confirm:u1")
            val t = f.token()
            val mark = f.published.size
            f.holder.toggleSeries(t, X)
            f.run()
            assertTrue("landed=$landed: read back without being asked", held.reached.isCompleted)
            val now = f.now()
            assertEquals(GraphV2SelectionStatus.CONFIRMING, now.selectionStatus)
            assertEquals(GraphV2Content.SELECTION_PENDING, now.content)
            assertNull(now.selection)
            assertNull(now.chart)
            assertTrue(now.toggles.none { it.enabled || it.selected })
            val writes = f.selections.writes
            f.holder.toggleSeries(f.now().inlineToken ?: t, Y)
            f.run()
            assertEquals("landed=$landed: nothing written while confirming", writes, f.selections.writes)
            held.release.complete(Unit)
            f.run()
            assertEquals("landed=$landed: exactly one read-back", confirms + 1, f.selections.confirms)
            val onDisk = if (landed) sel(emptySet(), setOf(X, Y)) else sel(setOf(X), setOf(X, Y))
            assertEquals(onDisk, f.selections.committed[key("u1")])
            assertEquals(GraphV2SelectionStatus.READY, f.now().selectionStatus)
            assertEquals(onDisk, f.now().selection)
            assertTrue("landed=$landed: never back to the first check while confirming",
                f.published.drop(mark).none { it.selectionStatus == GraphV2SelectionStatus.AWAITING_RESTORE })
        }
    }

    /**
     * 07e: a read-back that is unreadable stays unreadable - toggles disabled, no default written, no further read-back from a
     * revision, a fullscreen round trip or a refresh - until an explicit retry reads once and recovers.
     */
    @Test fun H07e_anUnreadableReadBackWaitsForAnExplicitRetry() = holderTest {
        val f = Fixture(this)
        f.catalogDto = twoSeriesCatalog()
        f.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y))
        f.open()
        f.answer(KEY_1D, xy())
        f.selections.nextWrite.addLast { _, _ -> GraphSelectionWriteResult.Uncertain(IOException("no answer")) }
        f.selections.nextConfirm.addLast(GraphSelectionReadResult.Unreadable(GraphSelectionUnreadableReason.IO))
        f.holder.toggleSeries(f.token(), X)
        f.run()
        assertEquals(GraphV2SelectionStatus.UNREADABLE, f.now().selectionStatus)
        assertNull(f.now().selection)
        assertTrue(f.now().toggles.none { it.enabled })
        assertEquals(1, f.selections.writes)
        val confirms = f.selections.confirms
        f.accessRevisions.value += 1
        f.holder.enterFullscreen(f.token())
        f.run()
        f.holder.exitFullscreen(checkNotNull(f.now().fullscreenToken))
        f.run()
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, xy())
        assertEquals("no automatic read-back", confirms, f.selections.confirms)
        assertEquals("no default written", 1, f.selections.writes)
        f.holder.retrySelection(f.token())
        f.run()
        assertEquals(confirms + 1, f.selections.confirms)
        assertEquals(GraphV2SelectionStatus.READY, f.now().selectionStatus)
        assertEquals(sel(setOf(X), setOf(X, Y)), f.now().selection)
    }

    // --- boundaries ---------------------------------------------------------------------------------------------------

    /**
     * Owner: before the holder handles a change, the very next render already shows nothing of the previous context - no
     * chart, toggles, selection or token - and a callback captured under it does nothing. A new UID rebinds and restores; the
     * same UID with a new auth generation rebinds too; the same identity under a new display owner clears the screen without
     * rebinding.
     */
    @Test fun BOwner_aContextChangeClearsTheScreenBeforeItIsHandled() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val old = f.token()
        val logMark = f.selections.log.size
        f.identity = B
        f.fence = FENCE_B
        f.snapshot = snap(uid = "u2", token = 8L, epoch = "e2")
        f.display.value = TopicDisplayState.NONE.copy(owner = TopicDisplayOwner(B, 1L))
        f.focus.value = OwnedTopicFocus(B, FreeTab.USD)
        val before = f.now()
        assertNull("no chart of A", before.chart)
        assertEquals(emptyList<GraphV2SeriesToggle>(), before.toggles)
        assertNull(before.selection)
        assertNull(before.inlineToken)
        f.holder.toggleSeries(old, Y)
        f.holder.onContextChanged()
        f.run()
        assertTrue("A's callback wrote nothing", f.selections.log.drop(logMark).none { it.startsWith("write:u1") })
        assertTrue("B is restored", "confirm:u2" in f.selections.log.drop(logMark))

        val g = Fixture(this)
        g.open()
        g.answer(KEY_1D, fullDay())
        val confirms = g.selections.confirms
        val a2 = AuthIdentityFence("u1", 2L)
        g.identity = a2
        g.fence = TopicSessionFence(a2, "e1", TopicGrantToken(7L))
        g.snapshot = snap(gen = 2L)
        g.display.value = TopicDisplayState.NONE.copy(owner = TopicDisplayOwner(a2, 1L))
        g.focus.value = OwnedTopicFocus(a2, FreeTab.USD)
        assertNull(g.now().chart)
        g.holder.onContextChanged()
        g.run()
        assertEquals("a new auth generation is a new binding", confirms + 1, g.selections.confirms)

        val h = Fixture(this)
        h.open()
        h.answer(KEY_1D, fullDay())
        val hOld = h.token()
        val hConfirms = h.selections.confirms
        val hWrites = h.selections.writes
        h.display.value = TopicDisplayState.NONE.copy(owner = TopicDisplayOwner(A, 2L))
        assertNull("a new display owner clears the screen", h.now().chart)
        assertNull(h.now().inlineToken)
        h.holder.onContextChanged()
        h.holder.toggleSeries(hOld, Y)
        h.run()
        assertEquals("no rebind for the same identity", hConfirms, h.selections.confirms)
        assertEquals("the old owner's callback wrote nothing", hWrites, h.selections.writes)
    }

    /**
     * Gate: closed protected admission is BLOCKED with nothing drawn; without cache ports nothing is exposed even though the
     * request owner holds the answer; a KRX withdrawal removes KRX from the toggles and the rendered ids at the next render
     * and keeps the general series and the raw selection.
     */
    @Test fun BGate_nothingUnexposedIsShownAndAKrxWithdrawalKeepsTheRest() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        f.protectedOpen = false
        f.accessRevisions.value += 1
        assertEquals(GraphV2Content.BLOCKED, f.now().content)
        assertNull(f.now().chart)
        assertEquals(emptyList<GraphV2SeriesToggle>(), f.now().toggles)

        val g = Fixture(this, withPorts = false)
        g.open()
        g.answer(KEY_1D, fullDay())
        assertNotNull("premise: the request owner holds the answer", g.coordinator.state.value.entries[KEY_1D])
        assertNull(g.now().chart)

        val h = Fixture(this)
        h.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, KRX), listOf(X, KRX))))
        h.open()
        h.answer(KEY_1D, day(S(X, pts(6, 1400.0)), S(KRX, pts(6, 1405.0))))
        assertEquals(setOf(X, KRX), checkNotNull(h.now().chart).renderedIds)
        assertEquals(setOf(X, KRX), h.now().toggles.map { it.seriesId }.toSet())
        h.snapshot = snap(capabilityBlocks = HELD)
        h.accessRevisions.value += 1
        val now = h.now()
        assertEquals(setOf(X), checkNotNull(now.chart).renderedIds)
        assertEquals(listOf(X), now.toggles.map { it.seriesId })
        h.run()
        assertEquals(GraphV2Content.READY, h.now().content)
        assertEquals(listOf(X), h.now().toggles.map { it.seriesId })
        assertEquals("the raw selection keeps KRX", sel(setOf(X, KRX), setOf(X, KRX)), h.selections.committed[key("u1")])
    }

    /**
     * Tokens: a token from before a period change can neither toggle nor change the period; a closed fullscreen's token can
     * neither toggle nor close the fullscreen opened after it.
     */
    @Test fun BToken_aTokenFromAnEarlierScreenIsRefused() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val dayToken = f.token()
        f.holder.selectPeriod(dayToken, GraphPeriod.THREE_MONTHS)
        f.run()
        f.answer(KEY_3M, quarter(S(X, quarterPts(1400.0)), S(Y, quarterPts(1380.0))))
        val writes = f.selections.writes
        f.holder.toggleSeries(dayToken, Y)
        f.run()
        assertEquals("a token from before the period change", writes, f.selections.writes)
        f.holder.selectPeriod(dayToken, GraphPeriod.ONE_DAY)
        f.run()
        assertEquals(GraphPeriod.THREE_MONTHS, f.now().activePeriod)
        f.holder.enterFullscreen(f.token())
        f.run()
        val closed = checkNotNull(f.now().fullscreenToken)
        f.holder.exitFullscreen(closed)
        f.run()
        f.holder.toggleSeries(closed, Y)
        f.run()
        assertEquals("a closed fullscreen's token", writes, f.selections.writes)
        f.holder.enterFullscreen(f.token())
        f.run()
        f.holder.exitFullscreen(closed)
        f.run()
        assertTrue("the new fullscreen stays open", f.now().fullscreenOpen)
    }

    /** Close: the screen and tokens go first; the started save finishes; close returns after it; nothing is published after. */
    @Test fun BClose_closeClearsTheScreenFirstAndLetsTheSaveFinish() = holderTest {
        val f = Fixture(this)
        f.catalogDto = twoSeriesCatalog()
        f.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y))
        f.open()
        f.answer(KEY_1D, xy())
        val held = f.selections.pauseNext("write:u1")
        f.holder.toggleSeries(f.token(), X)
        f.run()
        assertTrue(held.reached.isCompleted)
        val closing = async { f.holder.close() }
        f.run()
        assertEquals(GraphV2Content.INACTIVE, f.now().content)
        assertNull(f.now().chart)
        assertNull(f.now().inlineToken)
        assertFalse("close waits for the save", closing.isCompleted)
        val mark = f.published.size
        held.release.complete(Unit)
        f.run()
        assertTrue(closing.isCompleted)
        assertEquals("the save finished", sel(emptySet(), setOf(X, Y)), f.selections.committed[key("u1")])
        assertTrue("nothing drawable published after close", f.published.drop(mark).none { it.chart != null || it.inlineToken != null })
        val sent = f.sent.size
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        assertEquals("a closed active holder released its key", sent, f.sent.size)
    }

    /** Close of a deactivated holder leaves the shared request owner serving the holder that is active now. */
    @Test fun BClose_closingAnOldHolderLeavesTheNewHoldersActivation() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        f.holder.onDeactivated()
        f.run()
        val otherStore = FakeSelectionStore()
        val otherSession = GraphSeriesSelectionSession(otherStore, GraphSelectionAudience.PREMIUM, "usd", f.scope, f.dispatcher)
        val other = GraphV2ScreenStateHolder(
            tab = "usd", coordinator = f.coordinator, selectionSession = otherSession, liveIdentity = { f.identity },
            display = f.display, focus = f.focus, currentAccessFence = { f.fence }, uses = f.uses, gate = f.gate,
            accessRevisions = f.accessRevisions, scope = f.scope, dispatcher = f.dispatcher
        )
        other.start()
        f.run()
        other.onActivated(OWNER_A)
        f.run()
        val closing = async { f.holder.close() }
        f.run()
        assertTrue(closing.isCompleted)
        val sent = f.sentFor(KEY_1D)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        assertEquals("the active holder's key is still served", sent + 1, f.sentFor(KEY_1D))
        assertNotNull(other.currentState().chart)
    }

    // --- lifecycle and access boundaries (r3, from Codex's r2 probes) -------------------------------------------------

    /** L1: a refresh asked right after a period change already uses the new key; the previous period is not refreshed. */
    @Test fun L1_aRefreshRightAfterAPeriodChangeUsesTheNewKey() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val dayRequests = f.sentFor(KEY_1D)
        f.holder.selectPeriod(f.token(), GraphPeriod.THREE_MONTHS)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        assertEquals("no refresh of the previous period", dayRequests, f.sentFor(KEY_1D))
        assertEquals(1, f.sentFor(KEY_3M))
    }

    /** L2: a repeated activation by the same owner is merged even after the entry has gone stale - it asks nothing. */
    @Test fun L2_aRepeatedActivationIsMergedAfterTheEntryGoesStale() = holderTest {
        val f = Fixture(this)
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, Y), listOf(X))), ttlSeconds = 60)
        f.open()
        f.answer(KEY_1D, xy())
        val requests = f.sent.size
        advanceTimeBy(61.seconds.inWholeMilliseconds)
        f.holder.onActivated(OWNER_A)
        f.run()
        assertEquals(requests, f.sent.size)
        assertEquals(GraphV2Content.READY, f.now().content)
        assertFalse(f.now().refreshing)
    }

    /**
     * L3: start is idempotent and only binds and restores - no request, no write, INACTIVE; deactivation clears the screen;
     * activating again draws without another restore.
     */
    @Test fun L3_startOnlyBindsAndActivationDrawsWithoutAnotherRestore() = holderTest {
        val f = Fixture(this)
        f.coordinator.start()
        f.holder.start()
        f.holder.start()
        f.run()
        assertEquals(1, f.selections.confirms)
        assertEquals(0, f.selections.writes)
        assertEquals(0, f.sent.size)
        assertEquals(GraphV2Content.INACTIVE, f.now().content)
        f.holder.onActivated(OWNER_A)
        f.run()
        f.answer(KEY_1D, fullDay())
        f.holder.onDeactivated()
        assertNull(f.now().chart)
        assertNull(f.now().inlineToken)
        f.holder.onDeactivated()
        f.run()
        f.holder.onActivated(OWNER_A)
        f.run()
        assertEquals(1, f.selections.confirms)
        assertEquals(GraphV2Content.READY, f.now().content)
    }

    /**
     * L4: a use invalidation retires the screen and its tokens at the next render, even though a new use can be admitted; the
     * new context has a newer screen and lifetime, the old token writes nothing, and the chart is prepared afresh.
     */
    @Test fun L4_aUseInvalidationRetiresTheTokensEvenWhenANewUseIsAdmitted() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val old = f.token()
        val previous = checkNotNull(f.now().chart).prepared
        f.snapshot = snap(invalidations = 4L)
        assertNull(f.now().chart)
        assertNull(f.now().inlineToken)
        val writes = f.selections.writes
        f.holder.onContextChanged()
        f.run()
        val next = f.token()
        assertTrue(next.screenGeneration > old.screenGeneration)
        assertEquals(4L, next.lifetime.invalidations)
        f.holder.toggleSeries(old, Y)
        f.run()
        assertEquals(writes, f.selections.writes)
        assertNotSame(previous, checkNotNull(f.now().chart).prepared)
        assertEquals(GraphV2Content.READY, f.now().content)
    }

    /** L5: a toggle queued behind a held save is checked against the catalog current when it runs - a dropped series writes nothing. */
    @Test fun L5_aQueuedToggleIsCheckedAgainstTheCurrentCatalog() = holderTest {
        val f = Fixture(this)
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, Y), listOf(X))), ttlSeconds = 60)
        f.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y))
        f.open()
        f.answer(KEY_1D, xy())
        val held = f.selections.pauseNext("write:u1")
        val t = f.token()
        f.holder.toggleSeries(t, X)
        f.run()
        f.holder.toggleSeries(t, Y)
        f.run()
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X), listOf(X))), ttlSeconds = 60)
        advanceTimeBy(61.seconds.inWholeMilliseconds)
        f.coordinator.onRefreshRequested()
        f.run()
        held.release.complete(Unit)
        f.run()
        assertEquals(1, f.selections.writes)
        assertEquals(sel(emptySet(), setOf(X, Y)), f.selections.committed[key("u1")])
        assertEquals(listOf(X), f.now().toggles.map { it.seriesId })
    }

    /**
     * L6: a save that started under one period or one fullscreen is still read back once when its answer is uncertain, after
     * the period has changed or the fullscreen has closed - the save belongs to the owner, not to the surface.
     */
    @Test fun L6_anUncertainSaveIsReadBackAfterItsPeriodOrFullscreenIsGone() = holderTest {
        val f = Fixture(this)
        f.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y, Z, DXY))
        f.open()
        f.answer(KEY_1D, fullDay())
        val held = f.selections.pauseNext("write:u1")
        f.selections.nextWrite.addLast { _, _ -> GraphSelectionWriteResult.Uncertain(IOException("period changed")) }
        val confirms = f.selections.confirms
        f.holder.toggleSeries(f.token(), X)
        f.run()
        f.holder.selectPeriod(f.token(), GraphPeriod.THREE_MONTHS)
        f.run()
        f.answer(KEY_3M, quarter(S(X, quarterPts(1400.0))))
        held.release.complete(Unit)
        f.run()
        assertEquals(confirms + 1, f.selections.confirms)
        assertEquals(GraphV2SelectionStatus.READY, f.now().selectionStatus)
        assertEquals(GraphPeriod.THREE_MONTHS, f.now().activePeriod)

        val g = Fixture(this)
        g.catalogDto = twoSeriesCatalog()
        g.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y))
        g.open()
        g.answer(KEY_1D, xy())
        g.holder.enterFullscreen(g.token())
        val full = checkNotNull(g.now().fullscreenToken)
        val gHeld = g.selections.pauseNext("write:u1")
        g.selections.nextWrite.addLast { _, _ -> GraphSelectionWriteResult.Uncertain(IOException("surface changed")) }
        val gConfirms = g.selections.confirms
        g.holder.toggleSeries(full, X)
        g.run()
        g.holder.exitFullscreen(full)
        gHeld.release.complete(Unit)
        g.run()
        assertEquals(gConfirms + 1, g.selections.confirms)
        assertEquals(GraphV2SelectionStatus.READY, g.now().selectionStatus)
        assertFalse(g.now().fullscreenOpen)
    }

    /**
     * L7: when the display owner changes for the same identity while a save is pending, the old owner's uncertain answer is
     * not acted on for it - but the binding is shared, so the new owner reads the pending save back once and recovers
     * instead of waiting on a confirmation nobody will ask for. A new owner of the same identity reactivates without a rebind
     * or a new request, and the old owner's token writes nothing.
     */
    @Test fun L7_aNewOwnerOfTheSameIdentityRecoversAPendingSave() = holderTest {
        val f = Fixture(this)
        f.catalogDto = twoSeriesCatalog()
        f.selections.committed[key("u1")] = sel(setOf(X), setOf(X, Y))
        f.open()
        f.answer(KEY_1D, xy())
        val old = f.token()
        val held = f.selections.pauseNext("write:u1")
        f.selections.nextWrite.addLast { k, s ->
            f.selections.committed[k] = s
            GraphSelectionWriteResult.Uncertain(IOException("owner changed"))
        }
        val confirms = f.selections.confirms
        val requests = f.sent.size
        f.holder.toggleSeries(old, X)
        f.run()
        val next = TopicDisplayOwner(A, 2L)
        f.display.value = TopicDisplayState.NONE.copy(owner = next)
        assertNull(f.now().chart)
        f.holder.onContextChanged()
        f.run()
        f.holder.onActivated(next)
        f.run()
        held.release.complete(Unit)
        f.run()
        assertEquals("the new owner reads the pending save back once", confirms + 1, f.selections.confirms)
        assertEquals(GraphV2SelectionStatus.READY, f.now().selectionStatus)
        assertEquals(sel(emptySet(), setOf(X, Y)), f.now().selection)
        assertEquals(next, f.token().owner)
        assertEquals("no new request for the same identity", requests, f.sent.size)
        val writes = f.selections.writes
        f.holder.toggleSeries(old, Y)
        f.run()
        assertEquals("the old owner's token writes nothing", writes, f.selections.writes)
    }

    // --- S4 RT01-B3: runtime retirement through the holder -----------------------------------------------------

    private val S1 = GraphDataScope("u1", "e1")
    private val E2 = GraphDataScope("u1", "e2")

    /** One task on the fixture's dispatcher: nothing else runs between the port's return and the reads in [block]. */
    private suspend fun <T> Fixture.onMain(block: suspend () -> T): T = withContext(dispatcher) { block() }

    private fun Fixture.port() = GraphRuntimeRetirementPort(coordinator, { listOf(holder) }, dispatcher)

    /** u1's epoch e1 ends for good: the delivered fence and the published access move to e2; nothing is handled yet. */
    private fun Fixture.endEpochE1() {
        fence = TopicSessionFence(A, "e2", TopicGrantToken(8L))
        snapshot = snap(epoch = "e2", token = 8L)
    }

    /** A second holder over the same coordinator, selection store kind and suppliers (the BClose construction). */
    private fun Fixture.otherHolder() = GraphV2ScreenStateHolder(
        tab = "usd",
        coordinator = coordinator,
        selectionSession = GraphSeriesSelectionSession(FakeSelectionStore(), GraphSelectionAudience.PREMIUM, "usd", scope, dispatcher),
        liveIdentity = { identity },
        display = display,
        focus = focus,
        currentAccessFence = { fence },
        uses = uses,
        gate = gate,
        accessRevisions = accessRevisions,
        scope = scope,
        dispatcher = dispatcher
    )

    /**
     * B3H01 (B02): a retired USER scope leaves the screen inside the port's block - BLOCKED with no chart, toggles,
     * selection or token. As end-state checks of the epoch boundary, not of the port alone: the old token writes nothing,
     * and a late e1 answer leaves e2's drawn chart in place.
     */
    @Test fun B3H01_aRetiredUserScopeLeavesTheScreenBeforeThePortReturns() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val old = f.token()
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        assertTrue("premise: a request of e1 is out", f.now().refreshing)
        val writes = f.selections.writes
        f.endEpochE1()
        assertEquals("premise: nothing was handled", GraphV2Content.READY, f.holder.state.value.content)
        val (result, at) = f.onMain {
            val r = f.port().retireScopes { it == S1 }
            assertNull("the coordinator retired e1 in the port's block", f.coordinator.state.value.dataScope)
            assertNull("the empty snapshot has no source", f.coordinator.state.value.source)
            r to f.holder.state.value
        }
        assertEquals(GraphRuntimeRetirement.REMOVED, result)
        assertEquals(GraphV2Content.BLOCKED, at.content)
        assertNull(at.chart)
        assertEquals(emptyList<GraphV2SeriesToggle>(), at.toggles)
        assertNull(at.selection)
        assertNull(at.inlineToken)
        f.holder.toggleSeries(old, Y)
        f.run()
        assertEquals("the old token writes nothing", writes, f.selections.writes)
        val pending = f.sent.filter { it.key == KEY_1D && !it.answer.isCompleted }
        assertEquals("premise: the late e1 request, then e2's own", 2, pending.size)
        pending.last().answer.complete(fullDay(1600.0))
        f.run()
        val drawn = checkNotNull(f.now().chart) { "e2's answer draws" }
        pending.first().answer.complete(fullDay(1500.0))
        f.run()
        assertSame("the late e1 answer draws nothing over e2", drawn.prepared, checkNotNull(f.now().chart).prepared)
    }

    /**
     * B3H02: an unselected holder keeps its preparation (read through currentState: state.value would keep an equal old
     * instance), and is still re-rendered although the coordinator had nothing to remove - a closed admission nobody told
     * it about is published at the port's return. A scope the coordinator synchronizes without a context (another user's
     * fence while its owner source still reports u1) carries no source.
     */
    @Test fun B3H02_anUnselectedScopeIsKeptAndStillReRendered() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val before = f.now()
        val prepared = checkNotNull(before.chart).prepared
        val (kept, keptAt) = f.onMain { f.port().retireScopes { it.userAccessEpoch == "e0" } to f.holder.currentState() }
        assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, kept)
        assertEquals(GraphV2Content.READY, keptAt.content)
        assertSame("the preparation is kept", prepared, checkNotNull(keptAt.chart).prepared)
        assertEquals(before.inlineToken, keptAt.inlineToken)
        f.protectedOpen = false
        val published = f.onMain {
            f.port().retireScopes { it.userAccessEpoch == "e0" }
            f.holder.state.value
        }
        assertEquals("an unselected holder is still re-rendered", GraphV2Content.BLOCKED, published.content)

        val g = Fixture(this)
        g.open()
        g.answer(KEY_1D, fullDay())
        g.fence = FENCE_B
        g.coordinator.onContextChanged()
        g.run()
        assertEquals("premise: the coordinator moved", GraphDataScope("u2", "e2"), g.coordinator.state.value.dataScope)
        assertNull("a scope synchronized without a context has no source", g.coordinator.state.value.source)
    }

    /**
     * B3H03 (U4, S4 RT03b-0): one use of a scope ends with a refresh failure on screen; the holder retires it and takes
     * the next use of the same scope at once, and the coordinator publishes a copy (a capability retirement) before it
     * hears of the new use. Until it does, the new use reads neither the old use's catalog (the four fallback periods, also
     * when a period is chosen) nor its failure. Once the coordinator moves, a catalog adopted for the new use is read.
     * Another fence with the same lifetime (u1's next auth generation under the same grant) reads none of the old catalog
     * either.
     */
    @Test fun B3H03_aNewUseOfTheSameScopeReadsNothingOfTheOldOne() = holderTest {
        val f = Fixture(this)
        f.catalogDto = catalog("usd" to mapOf(
            "1d" to period(listOf(X, KRX), listOf(X, KRX)),
            "3m" to period(listOf(X, KRX), listOf(X, KRX))
        ))
        f.open()
        f.answer(KEY_1D, day(S(X, pts(6, 1400.0)), S(KRX, pts(6, 1405.0))))
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, status(500))
        assertNotNull("premise: a failure of the first use", f.now().requestFailure)
        val catalogPeriods = listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS)
        assertEquals("premise: the catalog's periods", catalogPeriods, f.now().periods)
        val oldSource = checkNotNull(f.coordinator.state.value.source) { "premise: the snapshot carries its source" }
        // One block without suspension: nothing runs until f.run().
        f.snapshot = snap(krx = "K2", invalidations = 4L)
        assertNull("premise: the lazy retire", f.now().chart)
        f.holder.onActivated(OWNER_A)
        assertEquals("premise: the next use", 4L, f.token().lifetime.invalidations)
        assertEquals("premise: a copy published without a sync", GraphRuntimeRetirement.REMOVED,
            f.coordinator.retireCapabilities { it.krxCapabilityEpoch == "K1" })
        assertEquals("premise: the coordinator has not moved", oldSource, f.coordinator.state.value.source)
        val oldCatalog = checkNotNull(f.coordinator.state.value.catalog) { "premise: the copy keeps the old catalog" }
        assertNotNull("premise: and the old use's failure", f.coordinator.state.value.failures[KEY_1D])
        assertEquals("premise: in the holder's scope", S1, f.coordinator.state.value.dataScope)
        val window = f.now()
        assertEquals("the old use's catalog is not read", GraphPeriod.entries.toList(), window.periods)
        assertNull("nor its failure", window.requestFailure)
        f.holder.selectPeriod(f.token(), GraphPeriod.ONE_WEEK)
        assertEquals("a period outside the old catalog is chosen as with no catalog", GraphPeriod.ONE_WEEK, f.now().activePeriod)
        f.run()
        assertNotEquals("premise: the coordinator moved", oldSource, f.coordinator.state.value.source)
        assertNotSame("premise: a catalog adopted for the new use", oldCatalog, f.coordinator.state.value.catalog)
        assertEquals("the new use's catalog is read", catalogPeriods, f.now().periods)

        // Another fence, the same lifetime: u1's next auth generation keeps grant 7 and invalidations 3. Deactivated, the
        // holder rebinds without telling the coordinator, then takes the new context before the coordinator hears of it.
        val g = Fixture(this)
        g.open()
        g.answer(KEY_1D, fullDay())
        assertEquals("premise: the catalog's periods", catalogPeriods, g.now().periods)
        val gSource = checkNotNull(g.coordinator.state.value.source) { "premise: the snapshot carries its source" }
        g.holder.onDeactivated()
        g.run()
        val a2 = AuthIdentityFence("u1", 2L)
        val renewed = TopicSessionFence(a2, "e1", TopicGrantToken(7L))
        g.identity = a2
        g.fence = renewed
        g.snapshot = snap(gen = 2L)
        g.display.value = TopicDisplayState.NONE.copy(owner = TopicDisplayOwner(a2, 1L))
        g.focus.value = OwnedTopicFocus(a2, FreeTab.USD)
        g.run()
        assertEquals("premise: the coordinator was not told", gSource, g.coordinator.state.value.source)
        g.holder.onActivated(TopicDisplayOwner(a2, 1L))
        val renewedToken = g.token()
        assertEquals("premise: another fence", renewed, renewedToken.fence)
        assertEquals("premise: the same lifetime", gSource.lifetime, renewedToken.lifetime)
        assertEquals("another fence with the same lifetime reads none of the old catalog",
            GraphPeriod.entries.toList(), g.now().periods)
        g.run()
        assertEquals("once the coordinator moves, its catalog is read", catalogPeriods, g.now().periods)
    }

    /**
     * B3H04 (B03): a KRX retirement. When the rotation ends the use, the screen is BLOCKED inside the port's block; the
     * renewed use draws only GENERAL - the raw selection keeps KRX - until a fresh answer under the new epoch brings it back.
     * When the context stays current
     * (outside the contract), the 3m period, the fullscreen and GENERAL stay and only KRX leaves.
     */
    @Test fun B3H04_aKrxRetirementLeavesOnlyGeneral() = holderTest {
        val both = catalog("usd" to mapOf(
            "1d" to period(listOf(X, KRX), listOf(X, KRX)),
            "3m" to period(listOf(X, KRX), listOf(X, KRX))
        ))
        val krxDay = { base: Double -> day(S(X, pts(6, base)), S(KRX, pts(6, base + 5))) }
        val f = Fixture(this)
        f.catalogDto = both
        f.open()
        f.answer(KEY_1D, krxDay(1400.0))
        assertEquals("premise", setOf(X, KRX), checkNotNull(f.now().chart).renderedIds)
        f.snapshot = snap(krx = "K2", invalidations = 4L)
        val (result, at) = f.onMain {
            f.port().retireCapabilities { it.krxCapabilityEpoch == "K1" } to f.holder.state.value
        }
        assertEquals(GraphRuntimeRetirement.REMOVED, result)
        assertEquals(GraphV2Content.BLOCKED, at.content)
        assertNull(at.chart)
        f.run()
        assertEquals("the renewed use draws GENERAL alone", setOf(X), checkNotNull(f.now().chart).renderedIds)
        assertEquals(listOf(X), f.now().toggles.map { it.seriesId })
        assertEquals("the raw selection keeps KRX", sel(setOf(X, KRX), setOf(X, KRX)), f.selections.committed[key("u1")])
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.sent.filter { it.key == KEY_1D && !it.answer.isCompleted }.forEach { it.answer.complete(krxDay(1500.0)) }
        f.run()
        assertEquals("a fresh answer under K2 brings KRX", setOf(X, KRX), checkNotNull(f.now().chart).renderedIds)

        val g = Fixture(this)
        g.catalogDto = both
        g.open()
        g.answer(KEY_1D, krxDay(1400.0))
        g.holder.selectPeriod(g.token(), GraphPeriod.THREE_MONTHS)
        g.run()
        g.answer(KEY_3M, quarter(S(X, quarterPts(1400.0)), S(KRX, quarterPts(1405.0))))
        g.holder.enterFullscreen(g.token())
        g.run()
        assertTrue("premise", g.now().fullscreenOpen)
        assertEquals("premise", GraphPeriod.THREE_MONTHS, g.now().activePeriod)
        assertEquals("premise", setOf(X, KRX), checkNotNull(g.now().chart).renderedIds)
        g.snapshot = snap(krx = "K2")
        val kept = g.onMain {
            g.port().retireCapabilities { it.krxCapabilityEpoch == "K1" }
            g.holder.state.value
        }
        assertEquals(GraphV2Content.READY, kept.content)
        assertTrue("the fullscreen stays", kept.fullscreenOpen)
        assertEquals("the period stays", GraphPeriod.THREE_MONTHS, kept.activePeriod)
        assertEquals("the same context still reads the catalog through the copy",
            listOf(GraphPeriod.ONE_DAY, GraphPeriod.THREE_MONTHS), kept.periods)
        assertEquals("KRX left at the publication", setOf(X), checkNotNull(kept.chart).renderedIds)
    }

    /**
     * B3H05: the same identity's next epoch - the holder rebinds nothing and draws e2; retiring e1 then keeps e2's
     * preparation, binding and saves.
     */
    @Test fun B3H05_anotherEpochOfTheSameIdentityIsUntouched() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val binding = f.token().binding
        f.endEpochE1()
        f.accessRevisions.value += 1
        f.run()
        f.answer(KEY_1D, fullDay(1500.0))
        assertEquals("premise: e2 draws", GraphV2Content.READY, f.now().content)
        assertSame("premise: no rebind", binding, f.token().binding)
        val confirms = f.selections.confirms
        val writes = f.selections.writes
        val before = f.now()
        val at = f.onMain {
            f.port().retireScopes { it == S1 }
            f.holder.currentState()
        }
        assertEquals(before, at)
        assertSame(checkNotNull(before.chart).prepared, checkNotNull(at.chart).prepared)
        assertEquals(confirms, f.selections.confirms)
        assertEquals(writes, f.selections.writes)
        assertSame(binding, f.token().binding)
    }

    /**
     * B3H06: failures and lists. A live selected scope - USER or capability - touches no holder (the capability case with
     * a closed admission, so that a wrongly run re-render would publish). A selector throw changes nothing in the holder,
     * and evaluates the held context's scope rather than the delivered fence's; also when it throws on the current e2
     * scope while a closed admission would change the re-render. Through the port a coordinator-step throw propagates
     * before any holder runs, and a holder-step throw propagates without rolling back the coordinator's retirement. The
     * port walks a copy of the list: a closed holder ahead of an active one is INACTIVE without a throw, and the active one
     * is retired even though the mount drops it from the list on publication. A repeated call on the closed holder changes
     * nothing. Only the current context's non-null scope is evaluated - once, and not at all without a context, not even
     * the coordinator's scope - and without a context the call still publishes.
     */
    @Test fun B3H06_failuresAndLists() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        val before = f.holder.state.value
        val (live, at) = f.onMain { f.port().retireScopes { it == S1 } to f.holder.state.value }
        assertEquals(GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, live)
        assertSame("no holder was touched", before, at)
        assertThrows(IllegalStateException::class.java) { f.holder.retireScopes { throw IllegalStateException("selector") } }
        assertSame(before, f.holder.state.value)
        assertSame(checkNotNull(before.chart).prepared, checkNotNull(f.now().chart).prepared)
        var calls = 0
        val (coordinatorThrow, untouched) = f.onMain {
            runCatching {
                // The live scope is the coordinator's first candidate: this throws there, before any holder.
                f.port().retireScopes { calls++; if (calls == 1) throw IllegalStateException("coordinator step") else it == S1 }
            }.exceptionOrNull() to f.holder.state.value
        }
        assertTrue("a coordinator-step throw propagates: $coordinatorThrow", coordinatorThrow is IllegalStateException)
        assertSame("and no holder runs", before, untouched)

        val k = Fixture(this)
        k.open()
        k.answer(KEY_1D, fullDay())
        k.endEpochE1()
        k.accessRevisions.value += 1
        k.run()
        k.answer(KEY_1D, fullDay(1500.0))
        assertEquals("premise: the context is e2's", "e2", k.token().fence.userAccessEpoch)
        val shown = k.holder.state.value
        val shownPrepared = checkNotNull(shown.chart).prepared
        k.protectedOpen = false
        val evaluated = mutableListOf<GraphDataScope>()
        assertThrows(IllegalStateException::class.java) {
            k.holder.retireScopes { evaluated += it; if (it == E2) throw IllegalStateException("current scope") else false }
        }
        assertEquals("the current scope was the one evaluated", listOf(E2), evaluated)
        assertSame("nothing was published", shown, k.holder.state.value)
        k.protectedOpen = true
        assertSame("nor prepared again", shownPrepared, checkNotNull(k.now().chart).prepared)

        val h = Fixture(this)
        h.open()
        h.answer(KEY_1D, fullDay())
        h.endEpochE1()
        val shownBefore = h.holder.state.value
        val evaluatedStale = mutableListOf<GraphDataScope>()
        assertThrows(IllegalStateException::class.java) {
            h.holder.retireScopes { evaluatedStale += it; throw IllegalStateException("context scope") }
        }
        assertEquals("the held context's scope, not the delivered fence's", listOf(S1), evaluatedStale)
        val (holderThrow, unchanged) = h.onMain {
            runCatching {
                // The coordinator evaluates every candidate before it publishes; this throws only at the holder step.
                h.port().retireScopes {
                    if (h.coordinator.state.value.dataScope == null) throw IllegalStateException("holder step") else it == S1
                }
            }.exceptionOrNull() to h.holder.state.value
        }
        assertTrue("a holder-step throw propagates: $holderThrow", holderThrow is IllegalStateException)
        assertNull("the coordinator's retirement is not rolled back", h.coordinator.state.value.dataScope)
        assertSame("the throwing holder changed nothing", shownBefore, unchanged)

        val g = Fixture(this)
        g.open()
        g.answer(KEY_1D, fullDay())
        val other = g.otherHolder()
        other.start()
        g.run()
        other.onActivated(OWNER_A)
        g.run()
        assertNotNull("premise: the other holder holds e1", other.currentState().inlineToken)
        g.holder.close()
        g.run()
        g.endEpochE1()
        val mounted = mutableListOf(g.holder, other)
        // The mount drops a holder once it shows nothing, mutating the list while the port walks it.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            other.state.collect { if (it.content == GraphV2Content.BLOCKED) mounted.remove(other) }
        }
        val mountedPort = GraphRuntimeRetirementPort(g.coordinator, { mounted }, g.dispatcher)
        val (closed, mixed) = g.onMain {
            assertEquals(GraphRuntimeRetirement.REMOVED, mountedPort.retireScopes { it == S1 })
            g.holder.state.value to other.state.value
        }
        assertEquals(GraphV2Content.INACTIVE, closed.content)
        assertEquals("the holder after the closed one is retired too", GraphV2Content.BLOCKED, mixed.content)
        assertNull(mixed.inlineToken)
        val again = g.onMain {
            assertEquals(GraphRuntimeRetirement.NOTHING_TO_REMOVE, g.port().retireScopes { it == S1 })
            g.holder.state.value
        }
        assertSame("a repeat on the closed holder changes nothing", closed, again)
        val noContext = mutableListOf<GraphDataScope>()
        g.holder.retireScopes { noContext += it; true }
        assertEquals("no context, no evaluation", emptyList<GraphDataScope>(), noContext)

        val j = Fixture(this)
        j.open()
        j.answer(KEY_1D, fullDay())
        val drawn = j.holder.state.value
        j.protectedOpen = false
        val liveCapability = j.onMain { j.port().retireCapabilities { it.krxCapabilityEpoch == "K1" } to j.holder.state.value }
        assertEquals(GraphRuntimeRetirement.LIVE_SCOPE_SELECTED, liveCapability.first)
        assertSame("a live capability touches no holder", drawn, liveCapability.second)
        j.protectedOpen = true
        val seen = mutableListOf<GraphDataScope>()
        j.holder.retireScopes { seen += it; false }
        assertEquals("the current context's scope, once", listOf(S1), seen)
        val selectedOnce = mutableListOf<GraphDataScope>()
        j.holder.retireScopes { selectedOnce += it; true }
        assertEquals("selected, it is still evaluated once", listOf(S1), selectedOnce)
        j.snapshot = snap(invalidations = 4L)
        assertNull("premise: no context is left", j.now().inlineToken)
        assertEquals("premise: the coordinator still offers e1", S1, j.coordinator.state.value.dataScope)
        val none = mutableListOf<GraphDataScope>()
        j.holder.retireScopes { none += it; true }
        assertEquals("no context, no evaluation - not of the coordinator's scope either", emptyList<GraphDataScope>(), none)

        val n = Fixture(this)
        n.open()
        n.answer(KEY_1D, fullDay())
        n.holder.onDeactivated()
        assertNotEquals("premise: bound", GraphV2SelectionStatus.UNBOUND, n.holder.state.value.selectionStatus)
        n.session.bind(B) // the selection session moves on; the holder has not handled it
        val unevaluated = mutableListOf<GraphDataScope>()
        n.holder.retireScopes { unevaluated += it; true }
        assertEquals("premise: no context, no evaluation", emptyList<GraphDataScope>(), unevaluated)
        assertEquals("without a context the call still publishes", GraphV2SelectionStatus.UNBOUND,
            n.holder.state.value.selectionStatus)
    }


    // --- S4 CUT-CC5-1: an activation request kept apart from access (`cut_cc5_agreed.r1.md`) --------------------------------

    /** Starts both owners without activating the holder. */
    private fun Fixture.startOnly() {
        coordinator.start()
        holder.start()
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { holder.state.collect { published += it } }
        run()
    }

    /**
     * CC5-1-A01: an activation asked for while the focus is on another tab acquires nothing — the screen stays inactive and
     * nothing is requested — and becomes active, with no further call, once the focus reaches this tab.
     */
    @Test fun CC51_A01_anActivationBeforeTheFocusArrivesBecomesActiveWhenItDoes() = holderTest {
        val f = Fixture(this)
        f.focus.value = OwnedTopicFocus(A, FreeTab.JPY)
        f.startOnly()
        f.holder.onActivated(OWNER_A)
        f.run()
        assertEquals("CC5-1-A01 not yet: inactive", GraphV2Content.INACTIVE, f.now().content)
        assertEquals("CC5-1-A01 not yet: nothing requested", 0, f.sentFor(KEY_1D))
        f.focus.value = OwnedTopicFocus(A, FreeTab.USD)
        f.run()
        assertTrue("CC5-1-A01 active once the focus arrives: the day is requested", f.sentFor(KEY_1D) > 0)
        f.answer(KEY_1D, fullDay())
        assertNotNull("CC5-1-A01 and the screen is published", f.now().inlineToken)
    }

    /**
     * CC5-1-A02: an activation for a new identity whose change was only enqueued — live identity, fence, display and focus
     * already moved, the holder not yet rebound — fails at first and becomes active after the rebind, with no further call.
     */
    @Test fun CC51_A02_anActivationBeforeTheRebindBecomesActiveAfterIt() = holderTest {
        val f = Fixture(this)
        f.startOnly()
        val ownerB = TopicDisplayOwner(B, 1L)
        f.identity = B
        f.fence = FENCE_B
        f.snapshot = snap(uid = "u2", token = 8L, epoch = "e2")
        f.display.value = TopicDisplayState.NONE.copy(owner = ownerB)
        f.focus.value = OwnedTopicFocus(B, FreeTab.USD)
        f.holder.onActivated(ownerB)
        assertEquals("CC5-1-A02 premise: before the rebind the activation acquires nothing", GraphV2Content.INACTIVE, f.now().content)
        f.run()
        assertTrue("CC5-1-A02 after the rebind it is active: the day is requested", f.sentFor(KEY_1D) > 0)
        f.answer(KEY_1D, fullDay())
        assertEquals("CC5-1-A02 for the new owner", ownerB, f.token().owner)
    }

    /** CC5-1-A03: a stale owner's request never activates, whatever changes later: nothing is requested or written. */
    @Test fun CC51_A03_aStaleOwnersRequestNeverActivates() = holderTest {
        val f = Fixture(this)
        f.startOnly()
        f.holder.onActivated(TopicDisplayOwner(A, 9L))
        f.run()
        f.accessRevisions.value += 1
        f.focus.value = OwnedTopicFocus(A, FreeTab.USD)
        f.run()
        assertEquals("CC5-1-A03 inactive", GraphV2Content.INACTIVE, f.now().content)
        assertEquals("CC5-1-A03 nothing requested", 0, f.sentFor(KEY_1D))
        assertEquals("CC5-1-A03 nothing written", 0, f.selections.writes)
    }

    /**
     * CC5-1-A04: a request made while access is blocked requests nothing and becomes active once access returns and is
     * reported, with no further call.
     */
    @Test fun CC51_A04_aRequestWhileBlockedActivatesWhenAccessReturns() = holderTest {
        val f = Fixture(this)
        f.fence = null
        f.startOnly()
        f.holder.onActivated(OWNER_A)
        f.run()
        assertEquals("CC5-1-A04 blocked: nothing requested", 0, f.sentFor(KEY_1D))
        f.fence = FENCE_A
        f.accessRevisions.value += 1
        f.run()
        assertTrue("CC5-1-A04 active once access returns", f.sentFor(KEY_1D) > 0)
    }

    /** CC5-1-A05: onDeactivated, and close, clear a pending request: a later change activates nothing. */
    @Test fun CC51_A05_deactivationAndCloseClearAPendingRequest() = holderTest {
        for (clear in listOf("deactivated", "closed")) {
            val f = Fixture(this)
            f.focus.value = OwnedTopicFocus(A, FreeTab.JPY)
            f.startOnly()
            f.holder.onActivated(OWNER_A)
            f.run()
            if (clear == "deactivated") f.holder.onDeactivated() else f.holder.close()
            f.run()
            f.focus.value = OwnedTopicFocus(A, FreeTab.USD)
            f.run()
            assertEquals("CC5-1-A05 $clear: inactive", GraphV2Content.INACTIVE, f.now().content)
            assertEquals("CC5-1-A05 $clear: nothing requested", 0, f.sentFor(KEY_1D))
        }
    }

    /**
     * CC5-1-A06: an activation that fails releases what was active before: an active screen asked to activate an owner that
     * cannot be acquired becomes inactive at once and drops its activation; the earlier owner does not come back by itself.
     */
    @Test fun CC51_A06_aFailedActivationReleasesThePreviousOne() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, fullDay())
        assertNotNull("CC5-1-A06 premise: active", f.now().inlineToken)
        f.holder.onActivated(TopicDisplayOwner(A, 9L))
        f.run()
        assertEquals("CC5-1-A06 released at once", GraphV2Content.INACTIVE, f.now().content)
        assertNull("CC5-1-A06 no token", f.now().inlineToken)
        f.accessRevisions.value += 1
        f.run()
        assertEquals("CC5-1-A06 the earlier owner does not come back by itself", GraphV2Content.INACTIVE, f.now().content)
    }
}
