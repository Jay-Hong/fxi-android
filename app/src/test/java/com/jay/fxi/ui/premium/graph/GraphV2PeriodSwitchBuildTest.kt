package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessFacts
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.entitlements.TopicGrantContext
import com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo
import com.jay.fxi.data.graph.FileGraphV2DiskStore
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservation
import com.jay.fxi.data.graph.GraphObservationId
import com.jay.fxi.data.graph.GraphObservationOrder
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRecorder
import com.jay.fxi.data.graph.GraphRecoverableState
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2CachePorts
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec
import com.jay.fxi.data.graph.observeRecoverable
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicGraphCandidate
import com.jay.fxi.data.remote.TopicGraphInput
import com.jay.fxi.data.remote.TopicGraphPath
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAttribution
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
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.time.AppClock
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphSeriesStyles
import com.jay.fxi.ui.graph.LinePoint
import com.jay.fxi.ui.graph.PreparedGraph
import com.jay.fxi.ui.graph.PreparedSeries
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S5 A3 C1 contract r2 (JVM; r2 adds B08-B09 after the implementation review found two cost-move paths the r1
 * rows did not name - a fullscreen opened right after a switch and a shown refresh that changes the graph): a period switch neither builds a REST graph the click does not draw nor changes
 * what the holder settles on; the REST build for a key is computed only when read and then kept for that key.
 *
 * Oracles: A3/impl/design.r3.md D2 (render clears the REST build on a new preparation key and computes it only when read;
 * while `surfaceVisible() && (changed || publish)` holds for a period other than 1d the REST build is evaluated before
 * publishLive, so it still precedes the clock and the recorder/live read; a 1d publication evaluates none; `built =
 * livePublication?.prepared ?: rest()`), D3 (projectGraphV2Live takes a lazy REST argument and a `prepare`; for 1d it builds
 * only the merged graph, otherwise it reads the REST build as its base), D4 (the holder takes a LAST constructor parameter
 * `prepare: (FreeGraph, GraphPeriod) -> PreparedGraph = GraphPreparedBuilder::build`, and both the REST build in render and
 * the projection's merged build go through it) and its section "계약 시험" rows B1-B7, written as Codex's design review
 * r1 (A3/impl/codex_design.r1.txt items 2 and 4) worded them: B2 and B4 range over p in {1w, 3m, 1y}; B6 and B7 were added
 * because a lazy REST build must neither come back on a publication nor move after the clock read (a freshness boundary
 * would otherwise change the tip and rightEdgeNow without a fixed clock noticing). Selection: A3/step1/selection.r3.md
 * sections 1-2 (the click's REST build is unread on 1d while shown; a hidden surface pays for it once instead; settled
 * states must equal the eager computation under the same inputs, the same fixed now and the same final visibility).
 *
 * Build counting: the fixture passes a counting wrapper of GraphPreparedBuilder.build through the named `prepare`
 * parameter. A call is a REST build when its graph argument equals the exposed REST graph of the key in hand (and its period
 * is that key's), and a merged build otherwise. Every 1d row holds live (or recorder) evidence with a bucket the REST answer
 * lacks inside the rolling day, so the merged graph always differs from the exposed one and the two kinds are told apart
 * by argument; rows on 1w, 3m and 1y have no merged build at all, so their totals are REST builds.
 *
 * Fixture: GraphV2LivePublishTest's C1-1b holder fixture (real request owner, gate, disk store, use authority, selection
 * session and recorder; fake fetcher and selection store; the holder's clock is the coordinator's virtual clock plus an
 * adjustable offset), copied with every named argument, with a catalog of all four periods and the counting `prepare`.
 * 2026-10-05 KST; t0 = NOON. Steps are runCurrent and advanceTimeBy, never advanceUntilIdle. Each row runs without and with
 * the recorder where it says so. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphV2PeriodSwitchBuildTest {

    @get:Rule val folder = TemporaryFolder()

    private companion object {
        /** 2026-10-05 12:00 KST. */
        val NOON: Instant = Instant.parse("2026-10-05T03:00:00Z")
        /** 00:00 KST on 2026-10-05, the 1d rolling window's start. */
        val DAY0: Instant = Instant.parse("2026-10-04T15:00:00Z")
        val A = AuthIdentityFence("u1", 1L)
        val FENCE_A = TopicSessionFence(A, "e1", TopicGrantToken(7L))
        val OWNER_A = TopicDisplayOwner(A, 1L)
        val KEY_1D = GraphKey("usd", GraphPeriod.ONE_DAY)
        const val X = "hana.usd"
        const val Y = "kb.usd"
        val SCOPE = GraphDataScope("u1", "e1")
        val KY = GraphObservationSeriesKey(SCOPE, Y)
        val KX = GraphObservationSeriesKey(SCOPE, X)
        val LONG = listOf(GraphPeriod.ONE_WEEK, GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR)
        fun key(period: GraphPeriod) = GraphKey("usd", period)
    }

    private fun snap(): TopicAccessSnapshot {
        val binding = EntitlementsIdentity("u1", 1L)
        val record = AccessFence("u1", "e1", "K1")
        val issued = TopicGrantContext(binding, record, 10L)
        return TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = TopicAccessFacts.NONE.copy(
                token = TopicGrantToken(7L),
                issuedFor = issued,
                binding = binding,
                recordFence = record,
                decisionGeneration = 10L,
                tokenStanding = true,
                userBlocks = emptySet(),
                capabilityBlocks = emptySet()
            ),
            userInvalidations = 3L
        )
    }

    private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

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

    private fun dto(period: GraphPeriod, vararg series: S) = GraphV2TabResponse(
        tab = "usd",
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

    private fun day(vararg series: S) = ok(dto(GraphPeriod.ONE_DAY, *series))
    private fun xy(xBase: Double = 1400.0) = day(S(X, pts(6, xBase)), S(Y, pts(6, 1380.0)))
    private fun quarterPts(base: Double) = pts(6, base, DAY0 - 240.hours, 24.hours)
    private fun weekPts(base: Double) = pts(6, base, DAY0 - 48.hours, 6.hours)

    /** The answer each period's request gets in this contract; a 1d answer is GraphV2LivePublishTest's xy(). */
    private fun restAnswer(period: GraphPeriod): AuthenticatedHttpResponse<GraphV2TabResponse> = when (period) {
        GraphPeriod.ONE_DAY -> xy()
        GraphPeriod.ONE_WEEK -> ok(dto(period, S(X, weekPts(1400.0)), S(Y, weekPts(1380.0))))
        else -> ok(dto(period, S(X, quarterPts(1400.0)), S(Y, quarterPts(1380.0))))
    }

    private class FakeSelectionStore : GraphSelectionStore {
        val committed = mutableMapOf<GraphSelectionKey, GraphSeriesSelection>()

        private fun current(key: GraphSelectionKey) = committed[key]?.let {
            GraphSelectionReadResult.Present(
                GraphSelectionRecord(1, key.uid, key.audience, key.tab, it.visibleSeriesIds, it.initializedSeries)
            )
        } ?: GraphSelectionReadResult.Absent

        private val confirmPauses = ArrayDeque<CompletableDeferred<Unit>>()

        /** The next confirmation waits until the returned signal completes. */
        fun pauseNextConfirm() = CompletableDeferred<Unit>().also { confirmPauses.addLast(it) }

        override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult {
            confirmPauses.removeFirstOrNull()?.await()
            return current(key)
        }
        override suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult = current(key)
        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult {
            committed[key] = selection
            return GraphSelectionWriteResult.Committed
        }
    }

    private val opened = mutableListOf<Fixture>()

    private fun holderTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            body()
        } finally {
            opened.toList().forEach { it.close() }
            opened.clear()
        }
    }

    private class Sent(val key: GraphKey) {
        val answer = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
    }

    private inner class Fixture(val test: TestScope, private val start: Instant = NOON, val withRecorder: Boolean = false) {
        init { opened += this }
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val base = test.testScheduler.currentTime
        val clock = AppClock { start + (test.testScheduler.currentTime - base).milliseconds }

        var protectedOpen = true
        var catalogDto: GraphV2CatalogResponse = catalog("usd" to mapOf(
            "1d" to period(listOf(X, Y), listOf(Y)),
            "1w" to period(listOf(X, Y), listOf(Y)),
            "3m" to period(listOf(X, Y), listOf(Y)),
            "1y" to period(listOf(X, Y), listOf(Y))
        ))
        val display = MutableStateFlow(TopicDisplayState.NONE.copy(owner = OWNER_A))
        val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(A, FreeTab.USD))
        val accessRevisions = MutableStateFlow(0L)
        val live = MutableStateFlow<Map<GraphObservationSeriesKey, GraphRecoverableState>>(emptyMap())
        val sent = mutableListOf<Sent>()
        var snapshot: TopicAccessSnapshot = snap()
        val uses = SnapshotTopicUseAuthority { snapshot }

        val owners = object : GraphOwnerSource {
            override fun currentIdentity(): AuthIdentityFence = A
            override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
                if (expected != A) throw AuthIdentityChangedException()
                return AuthSnapshot(expected.uid, expected.authGeneration, "token")
            }
        }

        val gate = GraphV2AccessGate(
            currentIdentity = { A },
            currentAccessFence = { FENCE_A },
            snapshot = { snapshot },
            protectedAdmission = { protectedOpen }
        )

        val root: File = folder.newFolder()
        val store = FileGraphV2DiskStore({ root }, JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), dispatcher)

        val fetcher = object : GraphV2Fetching {
            override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = ok(catalogDto)
            override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2TabResponse> {
                val s = Sent(key)
                sent += s
                return s.answer.await()
            }
        }

        val coordinator = GraphV2RequestCoordinator(
            fetcher = fetcher,
            owners = owners,
            currentAccessFence = { FENCE_A },
            uses = uses,
            protectedAdmission = { true },
            accessSnapshot = { snapshot },
            scope = scope,
            clock = clock,
            rateLimitJitter = { kotlin.time.Duration.ZERO },
            onEventFailure = {},
            cachePorts = GraphV2CachePorts(store = store, gate = gate, onSeedDiagnostic = {})
        )

        var offset: Duration = Duration.ZERO
        var holderClockReads = 0
        /** Runs once on the holder's next clock read, inside the publication that reads it. */
        var onClockRead: (() -> Unit)? = null
        private val holderClock = AppClock {
            holderClockReads++
            onClockRead?.invoke()
            clock.now() + offset
        }

        /** F2f: the graph recorder on this fixture's suppliers and executor; the holder reads it only when [withRecorder]. */
        var recorderReads = 0
        /** RT05a: the recorder's wall time moved apart from the virtual clock, as the holder's [offset] is. */
        var recorderOffset: Duration = Duration.ZERO
        val recorder = GraphRecorder(
            scope, MutableStateFlow(0L), { recorderReads++; snapshot }, { recorderReads++; FENCE_A },
            { recorderReads++; coordinator.state.value.catalog }, gate, AppClock { recorderReads++; clock.now() + recorderOffset }
        )
        private val readsRecorder = withRecorder

        val selections = FakeSelectionStore()
        val session = GraphSeriesSelectionSession(selections, GraphSelectionAudience.PREMIUM, "usd", scope, dispatcher)

        /** Every call of the holder's `prepare`, in order: the graph and period it was given. */
        val builds = mutableListOf<Pair<FreeGraph, GraphPeriod>>()
        /** Runs inside the next `prepare` call only, after its build. */
        var onPrepare: (() -> Unit)? = null
        val prepare: (FreeGraph, GraphPeriod) -> PreparedGraph = { graph, period ->
            builds += graph to period
            val built = GraphPreparedBuilder.build(graph, period)
            onPrepare?.let { hook ->
                onPrepare = null
                hook()
            }
            built
        }

        val holder = GraphV2ScreenStateHolder(
            tab = "usd",
            coordinator = coordinator,
            selectionSession = session,
            liveIdentity = { A },
            display = display,
            focus = focus,
            currentAccessFence = { FENCE_A },
            uses = uses,
            gate = gate,
            accessRevisions = accessRevisions,
            scope = scope,
            dispatcher = dispatcher,
            live = live,
            clock = holderClock,
            recorder = if (readsRecorder) recorder else null,
            prepare = prepare
        )

        val published = mutableListOf<GraphV2ScreenState>()

        fun run() = test.runCurrent()

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        fun open() {
            coordinator.start()
            holder.start()
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { holder.state.collect { published += it } }
            run()
            holder.onActivated(checkNotNull(display.value.owner))
            run()
        }

        /** Opens, answers 1d with X and Y, and lets the selection initialize to the catalog default (Y visible). */
        fun ready() {
            open()
            answer(KEY_1D, xy())
            assertEquals("premise", GraphV2Content.READY, now().content)
            assertEquals("premise", setOf(Y), chart().renderedIds)
        }

        fun now() = holder.currentState()
        fun chart() = checkNotNull(now().chart) { "no chart in ${now()}" }
        fun edge() = now().chart?.rightEdgeNow
        fun y(): PreparedSeries = chart().prepared.bySeries.getValue(Y)
        fun token() = checkNotNull(now().inlineToken) { "no inline token in ${now()}" }

        /** The holder's wall time: what its next clock read returns. */
        fun wall(): Instant = clock.now() + offset

        fun answer(key: GraphKey, response: AuthenticatedHttpResponse<GraphV2TabResponse>) {
            sent.first { it.key == key && !it.answer.isCompleted }.answer.complete(response)
            run()
        }

        /** Answers every request still open for [key] with [restAnswer], so nothing is in flight afterwards. */
        fun settle(key: GraphKey) {
            run()
            repeat(4) {
                if (sent.none { it.key == key && !it.answer.isCompleted }) return
                answer(key, restAnswer(key.period))
            }
            assertTrue("premise: nothing left in flight for $key", sent.none { it.key == key && !it.answer.isCompleted })
        }

        /** Selects [period] through the current inline token and settles its request. */
        fun select(period: GraphPeriod) {
            holder.selectPeriod(token(), period)
            settle(key(period))
            assertEquals("premise: on $period", period, now().activePeriod)
            assertNotNull("premise: a chart on $period", now().chart)
        }

        fun show() {
            holder.setSurfaceVisible(token(), true)
            run()
        }

        fun hide() {
            holder.setSurfaceVisible(token(), false)
            run()
        }

        fun push(
            vararg values: Pair<Instant, Double>,
            key: GraphObservationSeriesKey = KY,
            source: String = "kb",
            at: Instant = clock.now()
        ) {
            live.value = withObservations(live.value, key, source, at, *values)
            run()
        }

        fun pushNow(rate: Double) = push(clock.now() to rate)

        /** [prior] with [values] of [source] observed into [key]'s record at [at], without running anything. */
        fun withObservations(
            prior: Map<GraphObservationSeriesKey, GraphRecoverableState>,
            key: GraphObservationSeriesKey,
            source: String,
            at: Instant,
            vararg values: Pair<Instant, Double>
        ): Map<GraphObservationSeriesKey, GraphRecoverableState> {
            val record = prior[key] ?: GraphRecoverableState.empty(key, GraphObservationOrder(emptyList()))
            val next = observeRecoverable(
                record, values.map { (t, rate) -> GraphObservation(key, GraphObservationId(source, "usd-krw", t, rate)) }, at
            ).state
            return prior + (key to next)
        }

        fun exposed(key: GraphKey, allowed: Set<String> = setOf(X, Y)): FreeGraph {
            val graph = checkNotNull(coordinator.protectedEntry(key)).tab.graph
            return graph.copy(series = graph.series.filter { it.seriesId in allowed })
        }

        fun rest(key: GraphKey, allowed: Set<String> = setOf(X, Y)): PreparedGraph =
            GraphPreparedBuilder.build(exposed(key, allowed), key.period)

        val lifetime get() = checkNotNull(uses.acquire(FENCE_A))

        /** The evidence a publication reads: the recorder's checked read with a recorder, live.value without. */
        fun source(): Map<GraphObservationSeriesKey, GraphRecoverableState> =
            if (withRecorder) recorder.exposed(FENCE_A, lifetime) else live.value

        /** The eager oracle: the existing projection over GraphPreparedBuilder's REST build, at [at], over [source]. */
        fun expected(key: GraphKey, at: Instant, evidence: Map<GraphObservationSeriesKey, GraphRecoverableState> = source()): PreparedGraph =
            projectGraphV2Live(exposed(key), rest(key), key.period, SCOPE, evidence, at)

        /** A quote of [source] (kb unless given) handed to the recorder as the topic sink would hand it over. */
        fun record(rate: Double, at: Instant = clock.now(), source: String = "kb") {
            observeQuote(rate, at, source)
            run()
        }

        /** [record] without running anything afterwards, for use inside a holder call. */
        fun observeQuote(rate: Double, at: Instant, source: String = "kb") {
            recorder.observe(
                TopicGraphInput.Observations(
                    1L, "fx:usd-krw", TopicGraphPath.WS, TopicUseAttribution(Any(), FENCE_A, 1L, lifetime), 1L,
                    listOf(TopicGraphCandidate.Quote(source, "usd-krw", rate, at, null))
                )
            )
        }

        /** One piece of evidence for [id] at [at]: a recorder quote with a recorder, a live observation without. */
        fun evidence(rate: Double, at: Instant = clock.now(), id: String = Y) {
            val source = if (id == X) "hana" else "kb"
            if (withRecorder) record(rate, at, source) else push(at to rate, key = if (id == X) KX else KY, source = source)
        }

        /** REST builds for [key] since [mark]: calls whose graph is the exposed REST graph of [key]. */
        fun restBuilds(mark: Int, key: GraphKey): Int =
            builds.drop(mark).count { (graph, period) -> period == key.period && graph == exposed(key) }

        /** Merged builds for [key] since [mark]: calls on [key]'s period whose graph is not its exposed REST graph. */
        fun mergedBuilds(mark: Int, key: GraphKey): Int =
            builds.drop(mark).count { (graph, period) -> period == key.period && graph != exposed(key) }

        fun total(mark: Int): Int = builds.size - mark

        fun close() {
            scope.cancel()
            opened.remove(this)
        }
    }

    private fun mode(f: Fixture) = if (f.withRecorder) "recorder" else "live"

    // --- B1: 1w -> 1d while shown ------------------------------------------------------------------------------------

    /** B01: 1w -> 1d while the inline surface shows the chart builds once (the merged graph), drawn at that now. */
    @Test fun B01_aShownSwitchToOneDayBuildsOnlyTheMergedGraph() = holderTest {
        for (withRecorder in listOf(false, true)) {
            val f = Fixture(this, withRecorder = withRecorder)
            f.ready()
            f.evidence(1341.1)
            f.show()
            f.select(GraphPeriod.ONE_WEEK)
            assertNotNull("${mode(f)} premise: 1w published", f.edge())
            f.advance(10)
            val mark = f.builds.size
            val t = f.wall()
            f.select(GraphPeriod.ONE_DAY)
            assertEquals("${mode(f)}: exactly one build for the switch", 1, f.total(mark))
            assertEquals("${mode(f)}: it is the merged build", 1, f.mergedBuilds(mark, KEY_1D))
            assertEquals("${mode(f)}: no REST build on the click", 0, f.restBuilds(mark, KEY_1D))
            assertEquals("${mode(f)}: rightEdgeNow is the switch's now", t, f.edge())
            assertEquals("${mode(f)}: the chart is the live projection at that now", f.expected(KEY_1D, t), f.chart().prepared)
            f.close()
        }
    }

    // --- B2: 1d -> p while shown ---------------------------------------------------------------------------------------

    /** B02: 1d -> p (1w, 3m, 1y) while shown builds once (the REST build, the projection's base), drawn at that now. */
    @Test fun B02_aShownSwitchToALongPeriodBuildsOnlyItsRestGraph() = holderTest {
        for (withRecorder in listOf(false, true)) for (p in LONG) {
            val f = Fixture(this, withRecorder = withRecorder)
            f.ready()
            f.evidence(1341.1)
            f.show()
            f.advance(10)
            val mark = f.builds.size
            val t = f.wall()
            f.select(p)
            val case = "${mode(f)} 1d->$p"
            assertEquals("$case: exactly one build", 1, f.total(mark))
            assertEquals("$case: it is the REST build", 1, f.restBuilds(mark, key(p)))
            assertEquals("$case: rightEdgeNow is the switch's now", t, f.edge())
            assertEquals("$case: the projection over its REST build at that now", f.expected(key(p), t), f.chart().prepared)
            f.close()
        }
    }

    // --- B3: hiding after a switch -------------------------------------------------------------------------------------

    /**
     * B03: hidden after a switch, the chart is the REST build with no right edge; the key has exactly one REST build in all
     * across the switch and the hide (B01 and B06 fix that on 1d it is the hide's); further reads, a second hide, emissions
     * and an equal answer before showing again build nothing, and showing again builds no REST graph.
     */
    @Test fun B03_aHideAfterASwitchDrawsTheRestBuildOnceAndKeepsIt() = holderTest {
        for (withRecorder in listOf(false, true)) for (p in listOf(GraphPeriod.ONE_DAY) + LONG) {
            val f = Fixture(this, withRecorder = withRecorder)
            f.ready()
            f.evidence(1341.1)
            f.show()
            if (p == GraphPeriod.ONE_DAY) f.select(GraphPeriod.ONE_WEEK)
            f.advance(10)
            val mark = f.builds.size
            f.select(p)
            val switched = f.builds.size
            f.hide()
            val case = "${mode(f)} ->$p then hide"
            assertEquals("$case: the hide builds exactly the REST graph it still lacks",
                if (p == GraphPeriod.ONE_DAY) 1 else 0, f.total(switched))
            assertNull("$case: no right edge", f.edge())
            assertEquals("$case: the REST build is drawn", f.rest(key(p)), f.chart().prepared)
            assertEquals("$case: one REST build across the switch and the hide", 1, f.restBuilds(mark, key(p)))

            val kept = f.builds.size
            repeat(3) { f.now() }
            f.hide()
            f.display.value = f.display.value.copy(containsSeed = !f.display.value.containsSeed)
            f.run()
            f.accessRevisions.value += 1
            f.run()
            f.coordinator.onRefreshRequested(force = true)
            f.settle(key(p))
            f.advance(500)
            assertEquals("$case: the same key builds nothing more before showing again", kept, f.builds.size)
            assertEquals("$case: still the REST build", f.rest(key(p)), f.chart().prepared)

            f.show()
            assertEquals("$case: showing again builds no REST graph", 0, f.restBuilds(kept, key(p)))
            assertNotNull("$case premise: shown again", f.edge())
            f.close()
        }
    }

    // --- B4: settled states --------------------------------------------------------------------------------------------

    private class Sequence(val name: String, val ending: (GraphPeriod) -> GraphPeriod, val steps: (Fixture, GraphPeriod) -> Unit)

    private val sequences = listOf(
        Sequence("shown, 1d->p", { it }) { f, p -> f.show(); f.select(p) },
        Sequence("shown, 1d->p->1d", { GraphPeriod.ONE_DAY }) { f, p ->
            f.show(); f.select(p); f.advance(100); f.evidence(1341.2); f.select(GraphPeriod.ONE_DAY)
        },
        Sequence("shown, 1d->p, hidden, ->1d, shown", { GraphPeriod.ONE_DAY }) { f, p ->
            f.show(); f.select(p); f.hide(); f.advance(100); f.evidence(1341.2); f.select(GraphPeriod.ONE_DAY); f.show()
        },
        Sequence("hidden, 1d->p->1d->p", { it }) { f, p ->
            f.select(p); f.select(GraphPeriod.ONE_DAY); f.advance(100); f.evidence(1341.2); f.select(p)
        },
    )

    /**
     * B04: after any switching order (1d <-> p for p in 1w, 3m, 1y; shown and hidden; with and without the recorder) the
     * settled state at one fixed now and one final visibility equals the eager oracle built here from GraphPreparedBuilder
     * and the existing projectGraphV2Live: shown, the projection at that now with rightEdgeNow = now; hidden, the REST build
     * with none - and the toggles, content and status a READY 1d-default selection shows.
     */
    @Test fun B04_settledStatesEqualTheEagerComputation() = holderTest {
        for (withRecorder in listOf(false, true)) for (p in LONG) for (sequence in sequences) for (shown in listOf(true, false)) {
            val f = Fixture(this, withRecorder = withRecorder)
            f.ready()
            f.evidence(1341.1)
            sequence.steps(f, p)
            f.advance(1000)
            val fixed = f.wall()
            if (shown) f.show() else f.hide()
            f.holder.onTimeEvent()
            f.run()
            val final = sequence.ending(p)
            val key = key(final)
            val case = "${mode(f)} p=$p [${sequence.name}] final ${if (shown) "shown" else "hidden"}"
            val rest = GraphPreparedBuilder.build(f.exposed(key), final)
            val prepared = if (shown) projectGraphV2Live(f.exposed(key), rest, final, SCOPE, f.source(), fixed) else rest
            val actual = f.now()
            val toggles = f.exposed(key).series.map {
                GraphV2SeriesToggle(it.seriesId, GraphSeriesStyles.of(it.seriesId, it.label), it.seriesId == Y, true,
                    requireNotNull(it.axisGroup))
            }
            val oracle = actual.copy(
                activePeriod = final,
                periods = GraphPeriod.entries.toList(),
                content = GraphV2Content.READY,
                selectionStatus = GraphV2SelectionStatus.READY,
                toggles = toggles,
                chart = GraphV2ChartModel(prepared, setOf(Y), if (shown) fixed else null),
                refreshing = false,
                requestFailure = null,
                notice = null,
                fullscreenOpen = false,
                fullscreenToken = null
            )
            assertEquals("$case: chart", oracle.chart, actual.chart)
            assertEquals("$case: state", oracle, actual)
            f.close()
        }
    }

    // --- B5: resumes start hidden ----------------------------------------------------------------------------------------

    /**
     * B05: after a context return, a tab leave and return, and a fullscreen closed and reopened while the inline surface is
     * hidden, nothing is published before the new surface's true report - not on a live change, not on a deadline, not on
     * the old token's report - and once it reports, the old token's reports change nothing.
     */
    @Test fun B05_eachResumeWaitsForTheNewSurfacesReport() = holderTest {
        for (withRecorder in listOf(false, true)) for (path in listOf("context", "tab", "fullscreen")) {
            val f = Fixture(this, withRecorder = withRecorder)
            f.ready()
            f.evidence(1341.1)
            val case = "${mode(f)} $path"
            val old: GraphV2UiToken
            if (path == "fullscreen") {
                f.holder.enterFullscreen(f.token())
                f.run()
                old = checkNotNull(f.now().fullscreenToken)
                f.holder.setSurfaceVisible(old, true)
                f.run()
                assertNotNull("$case premise: the fullscreen shows it", f.edge())
                f.holder.exitFullscreen(old)
                f.run()
                assertNull("$case premise: closed with the inline surface hidden", f.edge())
                f.holder.enterFullscreen(f.token())
                f.run()
            } else {
                f.show()
                old = f.token()
                assertNotNull("$case premise: shown", f.edge())
                if (path == "tab") {
                    f.focus.value = OwnedTopicFocus(A, FreeTab.JPY)
                    f.holder.onDeactivated()
                    f.run()
                    f.focus.value = OwnedTopicFocus(A, FreeTab.USD)
                    f.run()
                } else {
                    f.holder.onDeactivated()
                    f.run()
                }
                f.holder.onActivated(OWNER_A)
                f.settle(KEY_1D)
            }
            assertNotNull("$case premise: a chart after the resume", f.now().chart)
            val next = if (path == "fullscreen") checkNotNull(f.now().fullscreenToken) else f.token()
            assertNotEquals("$case premise: a new token", old, next)
            assertNull("$case: starts hidden", f.edge())
            f.evidence(1341.2)
            f.advance(400)
            assertNull("$case: a live change publishes nothing before the report", f.edge())
            f.holder.setSurfaceVisible(old, true)
            f.run()
            assertNull("$case: the old token's report has no effect", f.edge())
            f.advance(10)
            val t = f.wall()
            f.holder.setSurfaceVisible(next, true)
            f.run()
            assertEquals("$case: the new surface's report publishes at once", t, f.edge())
            assertEquals("$case", f.expected(KEY_1D, t), f.chart().prepared)
            f.holder.setSurfaceVisible(old, false)
            f.run()
            assertEquals("$case: the old token's false changes nothing", t, f.edge())
            f.close()
        }
    }

    // --- B6: publications after the switch -------------------------------------------------------------------------------

    /**
     * B06: right after B01 (no REST build for 1d yet), a live change's 350 ms publication and a time event each build once -
     * the merged graph, no REST - over the newest evidence at their own now; the first hide then builds the REST graph once
     * and later hides and reads none. On 1w, 3m and 1y the first publication builds the REST graph once and later
     * publications and hides build nothing.
     */
    @Test fun B06_publicationsAfterASwitchNeverRebuildTheRestGraph() = holderTest {
        for (withRecorder in listOf(false, true)) {
            val f = Fixture(this, withRecorder = withRecorder)
            val m = mode(f)
            f.ready()
            f.evidence(1341.1)
            f.show()
            f.select(GraphPeriod.ONE_WEEK)
            f.select(GraphPeriod.ONE_DAY)

            f.advance(10)
            val beforeDeadline = f.builds.size
            f.evidence(1341.2)
            val t1 = f.wall() + 350.milliseconds
            f.advance(350)
            assertEquals("$m premise: the deadline published", t1, f.edge())
            assertEquals("$m deadline: one build", 1, f.total(beforeDeadline))
            assertEquals("$m deadline: merged", 1, f.mergedBuilds(beforeDeadline, KEY_1D))
            assertEquals("$m deadline: no REST build", 0, f.restBuilds(beforeDeadline, KEY_1D))
            assertEquals("$m deadline: newest evidence at its now", f.expected(KEY_1D, t1), f.chart().prepared)
            assertEquals("$m deadline: the newest value ends the line", 1341.2, f.y().linePoints.last().rate, 0.0)

            f.advance(10)
            val beforeTime = f.builds.size
            val t2 = f.wall()
            f.holder.onTimeEvent()
            f.run()
            assertEquals("$m time event: published at its now", t2, f.edge())
            assertEquals("$m time event: one build", 1, f.total(beforeTime))
            assertEquals("$m time event: merged", 1, f.mergedBuilds(beforeTime, KEY_1D))
            assertEquals("$m time event: no REST build", 0, f.restBuilds(beforeTime, KEY_1D))
            assertEquals("$m time event", f.expected(KEY_1D, t2), f.chart().prepared)

            val beforeHide = f.builds.size
            f.hide()
            assertEquals("$m first hide: one build", 1, f.total(beforeHide))
            assertEquals("$m first hide: the REST build", 1, f.restBuilds(beforeHide, KEY_1D))
            assertEquals("$m first hide: drawn", f.rest(KEY_1D), f.chart().prepared)
            val afterHide = f.builds.size
            f.hide()
            repeat(2) { f.now() }
            f.evidence(1341.3)
            f.advance(400)
            assertEquals("$m: later hides and reads build nothing", afterHide, f.builds.size)
            f.close()
        }
        for (withRecorder in listOf(false, true)) for (p in LONG) {
            val f = Fixture(this, withRecorder = withRecorder)
            val case = "${mode(f)} $p"
            f.ready()
            f.evidence(1341.1)
            f.show()
            val beforeSwitch = f.builds.size
            f.select(p)
            assertEquals("$case first publication: one REST build", 1, f.restBuilds(beforeSwitch, key(p)))
            assertEquals("$case first publication: nothing else", 1, f.total(beforeSwitch))

            f.advance(10)
            val beforeDeadline = f.builds.size
            f.evidence(1341.2)
            val t1 = f.wall() + 350.milliseconds
            f.advance(350)
            assertEquals("$case premise: the deadline published", t1, f.edge())
            assertEquals("$case deadline: no build", 0, f.total(beforeDeadline))
            assertEquals("$case deadline: newest evidence at its now", f.expected(key(p), t1), f.chart().prepared)

            f.advance(10)
            val beforeTime = f.builds.size
            val t2 = f.wall()
            f.holder.onTimeEvent()
            f.run()
            assertEquals("$case time event: published at its now", t2, f.edge())
            assertEquals("$case time event: no build", 0, f.total(beforeTime))

            val beforeHide = f.builds.size
            f.hide()
            f.hide()
            repeat(2) { f.now() }
            assertEquals("$case hides: no build", 0, f.total(beforeHide))
            assertEquals("$case hidden: the REST build", f.rest(key(p)), f.chart().prepared)
            f.close()
        }
    }

    // --- B7: the REST build precedes the clock and the evidence --------------------------------------------------------

    /**
     * B07: on a new 1w, 3m or 1y key, the REST build happens before the publication reads the clock and the evidence. The
     * counting prepare moves the holder's clock 20 s ahead and adds a fresh kb tip for Y while it builds: the publication's
     * now is the moved one, so the 590 s old kb tip of Y and the 1190 s old hana tip of X are past their freshness
     * (graphLineFreshness 600 s / hana 1200 s) - X ends with no tip - and Y ends at the tip added during the build. Read
     * before the build, both old tips would still have been fresh and the right edge 20 s earlier.
     */
    @Test fun B07_theRestBuildPrecedesTheClockAndTheEvidence() = holderTest {
        for (withRecorder in listOf(false, true)) for (p in LONG) {
            val f = Fixture(this, withRecorder = withRecorder)
            val case = "${mode(f)} $p"
            f.ready()
            val t0 = f.wall()
            f.evidence(1341.7, at = t0 - 590.seconds, id = Y)
            f.evidence(1401.7, at = t0 - 1190.seconds, id = X)
            f.show()
            assertEquals("$case premise: no time has passed", t0, f.wall())
            f.holder.selectPeriod(f.token(), p)
            f.run()
            val before = f.source()
            val moved = t0 + 20.seconds
            f.onPrepare = {
                f.offset += 20.seconds
                f.recorderOffset += 20.seconds
                if (f.withRecorder) {
                    f.observeQuote(1350.0, moved - 1.seconds, "kb")
                } else {
                    f.live.value = f.withObservations(f.live.value, KY, "kb", moved, (moved - 1.seconds) to 1350.0)
                }
            }
            val mark = f.builds.size
            f.answer(key(p), restAnswer(p))
            assertNull("$case premise: the hook ran inside a build", f.onPrepare)
            assertEquals("$case: one build, the REST one", 1, f.restBuilds(mark, key(p)))
            assertEquals("$case: one build in all", 1, f.total(mark))
            assertEquals("$case: the clock is read after the build", moved, f.edge())
            assertEquals("$case: the projection at the moved now over the evidence read after the build",
                f.expected(key(p), moved), f.chart().prepared)
            assertEquals("$case: X's hana tip is stale at the moved now", f.rest(key(p)).bySeries.getValue(X),
                f.chart().prepared.bySeries.getValue(X))
            assertEquals("$case: Y ends at the tip added during the build", LinePoint(moved, 1350.0), f.y().linePoints.last())

            val early = projectGraphV2Live(f.exposed(key(p)), f.rest(key(p)), p, SCOPE, before, t0)
            assertEquals("$case premise: read before the build, X's hana tip would end the line",
                LinePoint(t0, 1401.7), early.bySeries.getValue(X).linePoints.last())
            assertEquals("$case premise: read before the build, Y's old kb tip would end the line",
                LinePoint(t0, 1341.7), early.bySeries.getValue(Y).linePoints.last())
            f.close()
        }
    }

    // --- B8: a fullscreen opened right after a switch (contract r2, cost move re-agreed) --------------------------------------

    /**
     * B08: opening the fullscreen right after a shown 1w -> 1d switch covers the inline surface before the fullscreen is
     * measured: the inline false draws the REST build once (the cost the click no longer pays), and the fullscreen's true
     * then publishes the merged build once, at its own now. On HEAD the click built REST eagerly and the cover built nothing.
     */
    @Test fun B08_aFullscreenOpenedRightAfterASwitchBuildsTheRestGraphWhenTheInlineIsCovered() = holderTest {
        for (withRecorder in listOf(false, true)) {
            val f = Fixture(this, withRecorder = withRecorder)
            val m = mode(f)
            f.ready()
            f.evidence(1341.1)
            f.show()
            f.select(GraphPeriod.ONE_WEEK)
            f.advance(10)
            f.select(GraphPeriod.ONE_DAY)
            val inline = f.token()
            val beforeOpen = f.builds.size
            f.holder.enterFullscreen(inline)
            f.run()
            val fullscreen = checkNotNull(f.now().fullscreenToken) { "premise: the fullscreen opened" }
            assertEquals("$m premise: the inline token is unchanged", inline, f.token())
            assertEquals("$m opening builds nothing", 0, f.total(beforeOpen))
            val beforeCover = f.builds.size
            f.holder.setSurfaceVisible(inline, false)
            f.run()
            assertEquals("$m cover: one build", 1, f.total(beforeCover))
            assertEquals("$m cover: the REST build", 1, f.restBuilds(beforeCover, KEY_1D))
            assertNull("$m cover: no right edge", f.edge())
            assertEquals("$m cover: the REST build is drawn", f.rest(KEY_1D), f.chart().prepared)
            f.advance(10)
            val t = f.wall()
            val beforeShow = f.builds.size
            f.holder.setSurfaceVisible(fullscreen, true)
            f.run()
            assertEquals("$m fullscreen shown: one build", 1, f.total(beforeShow))
            assertEquals("$m fullscreen shown: the merged build", 1, f.mergedBuilds(beforeShow, KEY_1D))
            assertEquals("$m fullscreen shown: at its now", t, f.edge())
            assertEquals("$m fullscreen shown: the live projection", f.expected(KEY_1D, t), f.chart().prepared)
            f.close()
        }
    }

    // --- B9: a shown refresh that changes the graph (contract r2) --------------------------------------------------------------

    /**
     * B09: shown on 1d, a refreshed answer that changes the exposed graph (a new preparation key, not a period switch) builds
     * once - the merged graph, no REST build; the first hide then builds the REST graph once and later hides and reads build
     * nothing. On HEAD the refresh built REST and merged.
     */
    @Test fun B09_aShownRefreshThatChangesTheGraphBuildsOnlyTheMergedGraph() = holderTest {
        for (withRecorder in listOf(false, true)) {
            val f = Fixture(this, withRecorder = withRecorder)
            val m = mode(f)
            f.ready()
            f.evidence(1341.1)
            f.show()
            f.advance(10)
            val before = f.exposed(KEY_1D)
            val mark = f.builds.size
            f.coordinator.onRefreshRequested(force = true)
            f.run()
            f.answer(KEY_1D, xy(xBase = 1410.0))
            assertNotEquals("$m premise: the exposed graph changed", before, f.exposed(KEY_1D))
            assertNotNull("$m premise: still published live", f.edge())
            assertEquals("$m refresh: one build", 1, f.total(mark))
            assertEquals("$m refresh: the merged build", 1, f.mergedBuilds(mark, KEY_1D))
            assertEquals("$m refresh: no REST build", 0, f.restBuilds(mark, KEY_1D))
            val beforeHide = f.builds.size
            f.hide()
            assertEquals("$m first hide: one build", 1, f.total(beforeHide))
            assertEquals("$m first hide: the REST build", 1, f.restBuilds(beforeHide, KEY_1D))
            val kept = f.builds.size
            f.hide()
            repeat(2) { f.now() }
            assertEquals("$m later hides and reads build nothing", kept, f.builds.size)
            f.close()
        }
    }
}
