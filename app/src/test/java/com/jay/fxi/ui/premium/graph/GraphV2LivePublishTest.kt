package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.auth.AuthIdentityChangedException
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.AccessFence
import com.jay.fxi.data.entitlements.EntitlementsIdentity
import com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority
import com.jay.fxi.data.entitlements.TopicAccessEnd
import com.jay.fxi.data.entitlements.TopicAccessEndReason
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
import com.jay.fxi.data.graph.GraphRecoveryEvents
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2AccessGate
import com.jay.fxi.data.graph.GraphV2CachePorts
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.graph.GraphV2RequestCoordinator
import com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec
import com.jay.fxi.data.graph.applyGraphRecoveryResponse
import com.jay.fxi.data.graph.captureGraphRecoveryRequest
import com.jay.fxi.data.graph.observeRecoverable
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionRecord
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.remote.AuthenticatedFailureKind
import com.jay.fxi.data.remote.AuthenticatedHttpFailure
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
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.domain.model.GraphV2InProgress
import com.jay.fxi.domain.model.GraphV2Tab
import com.jay.fxi.time.AppClock
import com.jay.fxi.ui.graph.BandPoint
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.LinePoint
import com.jay.fxi.ui.graph.PreparedGraph
import com.jay.fxi.ui.graph.PreparedSeries
import com.jay.fxi.ui.graph.TimeFrame
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
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S4 C2b-1 contract r5 (JVM): the premium FX graph holder publishes the live projection at most once per
 * 350 ms trailing deadline, only while a surface shows it, and reads nothing unpublished on its other paths.
 *
 * Oracles: ANDROID_V2_PLAN.md :1317-1319 (timers move the axis and decide line use only), :1328 (350 ms trailing publish, not
 * a resetting debounce; off-screen accumulates with no publish task; re-exposure flushes at once), :1344 (right after a
 * return, source-less accumulation and tips are invalidated and rebuilt on one serial executor before the first
 * publication), :1349-1350 (only the current bucket reaches now), :1365 (no source-less old tip in any publication after a
 * return), :1367 (23:59 -> 00:00); iOS 89e866d GraphV2ViewModel.swift :234-238 (re-exposure flushes at once), :241-244 (a
 * detected bucket rollover publishes at once), :247-255 (350 ms, deadline not reset). Design: R4c/S4 c2_design_codex.r1
 * section 3 and rows C2-04, C2-11, C2-13, as reduced by c2_review_claude.r1 item 6 (Job + delay + generation on the
 * holder's serial dispatcher, no scheduler interface, no AppClock-driven publisher class) and agreed in c2_review_codex.r1
 * item 6 with its two distinctions (an in-flight request, a failure, a rate or display collection or a selection change does
 * not flush; access block, context end and close drop the pending deadline and the published result). The pure projection
 * is C2a, committed in fa15bc2 (GraphV2LiveProjection.kt sha256 a6268e9c8160b61cd3e28f3386e9850405aa40bbd1af8d5051a8cd9e9b3bc84a,
 * the oracle of expected()). GraphChart's rightEdgeNow, the surfaces' visibility reports and the free-graph regression are
 * C2b-2. r2 adds what a three-lens completeness check (c2b1_contract.r1/completeness_workflow.result.json) showed open: an
 * access block clears the reports, a fullscreen needs its own report, publications emit state themselves, cancelled
 * deadlines leave the scheduler usable, T16, midnight, the dispatcher-measured deadline, and the paths that are no event.
 * r3 (Codex's r2 review): the fixture's access snapshot clears both block sets, as the C1-1b fixture does - r2 kept
 * TopicAccessFacts.NONE's three user blocks, so no use was ever acquired and every holder row stopped at BLOCKED.
 * r4 (battery c2b1_r1 survivors B05, B07): V25 holds the selection confirmation open past a deadline - a change while the
 * chart is withheld schedules nothing, and a deadline that comes due then leaves the scheduler usable.
 * r5 (Codex's counterexamples to two r4 battery classifications, B06 and B19): V26 - a change made while hidden leaves no
 * deadline behind a re-exposure that finds the chart withheld; V27 - an access block first found by the check after
 * rendering also clears the reports, inline and fullscreen. The holder's clock can run a hook on a read.
 * r6 (S4 F2f, design f2f_design_codex.r1): rows W01-W05 at the end. The holder takes an optional trailing
 * `recorder: GraphRecorder? = null`; with it, each publication reads `recorder.exposed(context fence, context lifetime)` in
 * place of `live.value`, the recorder's state is subscribed only as a change signal, live is not subscribed, and the holder
 * neither starts nor closes the recorder. The fixture gains a recorder on its own suppliers and executor, a mutable access
 * snapshot and a second holder over the same coordinator and recorder.
 *
 * Android additions, not iOS 89e866d behaviour: every time event publishes at once (iOS only on a detected rollover);
 * hiding drops the published result; the right edge moves on a time event even when the projection is unchanged. iOS's
 * same-input skip and tip re-accumulation are not carried over. A "source-less old value" (V13, V16) is one that only an
 * earlier publication held - narrower than iOS T05's "no app-derived value" (c2_review_codex.r1 item 8).
 *
 * API this contract assumes:
 *  - GraphV2ScreenStateHolder gains two trailing constructor parameters, `live: StateFlow<Map<GraphObservationSeriesKey,
 *    GraphRecoverableState>>` and `clock: AppClock`, both with defaults (a new empty flow per holder, SystemAppClock) so that
 *    C1 callers and contracts are unchanged; F2 passes the real ones. Without a visibility report the holder neither reads
 *    the clock nor schedules anything.
 *  - `fun setSurfaceVisible(token: GraphV2UiToken, visible: Boolean)` and `fun onTimeEvent()` on the holder.
 *  - `GraphV2ChartModel(prepared, renderedIds, rightEdgeNow: Instant? = null)`; `GraphV2ScreenPresenter.project(chart,
 *    visibleDomain)` passes `chart.rightEdgeNow` to `GraphProjection.plot`.
 *
 * Semantics:
 *  - A publication reads `clock.now()` exactly once and stores, for the current preparation key, projectGraphV2Live(exposed
 *    graph, the REST build the holder keeps for that same key, period, GraphDataScope(uid, user access epoch) of the current
 *    context, live.value, now), and emits the resulting state itself. While that key is current the chart is that result
 *    with rightEdgeNow = now; otherwise the chart is the REST build with rightEdgeNow null, as in C1. No other path reads
 *    the clock.
 *  - Visible: the inline surface, or the open fullscreen, was last reported visible through a token the holder accepts. A
 *    report is recorded whether or not a chart exists yet. An open fullscreen counts only once it reports. Both flags are
 *    cleared when the context retires (deactivation, close, a new owner or use) and when access is blocked; the fullscreen
 *    flag also when the fullscreen closes. A period change keeps them. A report through a refused token, visible or not,
 *    changes nothing.
 *  - While visible and a chart exists: a change of `live` schedules one publication 350 ms later on the holder's dispatcher
 *    if none is pending; later changes do not move it, and the delay is measured on the dispatcher, not by the clock. A
 *    false-to-true change of the aggregate visibility, onTimeEvent() and a new preparation key (a changed REST exposure, a
 *    period, a narrowed catalog, a reopened access, a chart arriving after loading) cancel the pending one and publish at
 *    once - a new key found while rendering is published within that call. While hidden nothing is scheduled or published.
 *  - A true-to-false change of the aggregate visibility, an access block, a retired context and close cancel the pending
 *    publication and drop the published result; a deadline that comes due afterwards publishes nothing. After any
 *    cancellation the next live change is scheduled afresh.
 *  - currentState() with an unchanged preparation key, selection changes, a chart withheld while the selection is confirmed
 *    (its key and result are kept; its return is no event), a second surface reporting or leaving while the other still
 *    shows the chart, opening or closing the fullscreen over a shown inline surface, a display, focus or access-revision
 *    emission that changes neither the owner nor the access, an in-flight request, a failure, an answer equal to the
 *    exposure in hand and a catalog fetched again unchanged publish nothing, keep the published result and leave a pending
 *    deadline in place.
 *  - The holder has no timer of its own: time passing changes nothing until onTimeEvent() (F2 produces those events).
 *
 * S4 RT05a (rt05_api_agreed.r2 §4-b, §5 item 2, rows E03 and E06): the recovery event adapter on the fixture's executor,
 * coordinator and recorder, emitting into this holder's onTimeEvent, with the holder's clock. Its 30 s freshness tick moves
 * a long chart's right end; a recorder tip ends it only while younger than its freshness (600 s, hana 1200 s), and its expiry
 * drops only that end, adding no recorder bucket (the 1d current bucket's band reaching a time event's now is V07's). A return
 * retains before its time event: shown through the return, that time event draws the retained record; hidden, the re-show
 * flush after the return does. Past a day nothing of the old window is drawn, and after a clock reversal that evicts the
 * furthest-future buckets the end is the re-chosen tip, not the one an unretained record would draw. Only the prepared
 * projection and its right edge are judged here, not the presenter's final clipping.
 *
 * Owed by C2b-2: each surface reports through key(token) - visible while shown under a token, not visible when that token
 * changes or disappears or the surface leaves - so a block (tokens become null) and a period change are followed by a fresh
 * report under the current token.
 *
 * Fixture: the C1-1b holder fixture (real request owner, gate, disk store, use authority and selection session; fake fetcher
 * and selection store) plus a mutable live flow. The holder's clock is the coordinator's virtual clock plus an adjustable
 * offset, and it counts its reads. 2026-10-05 KST; t0 = NOON unless a row says otherwise. Steps are runCurrent and
 * advanceTimeBy, never advanceUntilIdle. The implementation thread reads but does not edit this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphV2LivePublishTest {

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
        val KEY_3M = GraphKey("usd", GraphPeriod.THREE_MONTHS)
        const val X = "hana.usd"
        const val Y = "kb.usd"
        val SCOPE = GraphDataScope("u1", "e1")
        val KY = GraphObservationSeriesKey(SCOPE, Y)
        val KX = GraphObservationSeriesKey(SCOPE, X)
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

    private fun <T> status(code: Int): AuthenticatedHttpResponse<T> = AuthenticatedHttpResponse(
        code, Headers.headersOf(), null,
        AuthenticatedHttpFailure(code, Headers.headersOf(), byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP),
        byteArrayOf(1)
    )

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
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    private class Sent(val key: GraphKey) {
        val answer = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
    }

    private inner class Fixture(val test: TestScope, private val start: Instant = NOON, withRecorder: Boolean = false) {
        init { opened += this }
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val base = test.testScheduler.currentTime
        val clock = AppClock { start + (test.testScheduler.currentTime - base).milliseconds }

        var protectedOpen = true
        var catalogDto: GraphV2CatalogResponse = catalog("usd" to mapOf(
            "1d" to period(listOf(X, Y), listOf(Y)),
            "3m" to period(listOf(X, Y), listOf(Y))
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

        /** Another screen over the same coordinator and recorder, with its own selection session. */
        fun newHolder() = GraphV2ScreenStateHolder(
            tab = "usd",
            coordinator = coordinator,
            selectionSession = GraphSeriesSelectionSession(selections, GraphSelectionAudience.PREMIUM, "usd", scope, dispatcher),
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
            recorder = recorder
        )

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
            recorder = if (readsRecorder) recorder else null
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

        fun answer(key: GraphKey, response: AuthenticatedHttpResponse<GraphV2TabResponse>) {
            sent.first { it.key == key && !it.answer.isCompleted }.answer.complete(response)
            run()
        }

        fun show() {
            holder.setSurfaceVisible(token(), true)
            run()
        }

        fun hide() {
            holder.setSurfaceVisible(token(), false)
            run()
        }

        /** Every distinct live right edge published so far, in order. */
        fun edges() = published.mapNotNull { it.chart?.rightEdgeNow }.distinct()

        fun push(vararg values: Pair<Instant, Double>, key: GraphObservationSeriesKey = KY) {
            val prior = live.value[key] ?: GraphRecoverableState.empty(key, GraphObservationOrder(emptyList()))
            val next = observeRecoverable(
                prior, values.map { (t, rate) -> GraphObservation(key, GraphObservationId("kb", "usd-krw", t, rate)) }, clock.now()
            ).state
            live.value = live.value + (key to next)
            run()
        }

        fun pushNow(rate: Double) = push(clock.now() to rate)

        /** Applies a 1d response carrying only [seed] for Y to the live record, as D3 does. */
        fun applySeed(seed: GraphV2InProgress) {
            val prior = live.value[KY] ?: GraphRecoverableState.empty(KY, GraphObservationOrder(emptyList()))
            val tab = GraphV2Tab(
                "usd", GraphPeriod.ONE_DAY, "10min", clock.now(), FreeGraph("10min", listOf(FreeGraphSeries(Y, emptyList()))),
                mapOf(Y to seed)
            )
            live.value = live.value + (KY to applyGraphRecoveryResponse(prior, SCOPE, captureGraphRecoveryRequest(prior, 0), tab, clock.now()))
            run()
        }

        fun exposed(key: GraphKey, allowed: Set<String> = setOf(X, Y)): FreeGraph {
            val graph = checkNotNull(coordinator.protectedEntry(key)).tab.graph
            return graph.copy(series = graph.series.filter { it.seriesId in allowed })
        }

        fun rest(key: GraphKey, allowed: Set<String> = setOf(X, Y)): PreparedGraph =
            GraphPreparedBuilder.build(exposed(key, allowed), key.period)

        fun expected(key: GraphKey, at: Instant, allowed: Set<String> = setOf(X, Y)): PreparedGraph =
            projectGraphV2Live(exposed(key, allowed), rest(key, allowed), key.period, SCOPE, live.value, at)

        val lifetime get() = checkNotNull(uses.acquire(FENCE_A))

        /** A quote of [source] (kb unless given) handed to the recorder as the topic sink would hand it over. */
        fun record(rate: Double, at: Instant = clock.now(), source: String = "kb") {
            recorder.observe(
                TopicGraphInput.Observations(
                    1L, "fx:usd-krw", TopicGraphPath.WS, TopicUseAttribution(Any(), FENCE_A, 1L, lifetime), 1L,
                    listOf(TopicGraphCandidate.Quote(source, "usd-krw", rate, at, null))
                )
            )
            run()
        }

        /**
         * RT05: the recovery event adapter over this fixture, emitting into [holder], with no permit; its clock is the holder's
         * wall time.
         */
        fun events() = GraphRecoveryEvents(
            scope, AppClock { clock.now() + offset }, { coordinator.state.value.source }, coordinator::onRecoveryTrigger,
            recorder::retain, holder::onTimeEvent, { null }, coordinator::onContextChanged
        )

        /** Shows the chart and selects 3m, answered with X and Y quarter series. */
        fun quarter() {
            show()
            holder.selectPeriod(token(), GraphPeriod.THREE_MONTHS)
            run()
            answer(KEY_3M, ok(dto(GraphPeriod.THREE_MONTHS, S(X, quarterPts(1400.0)), S(Y, quarterPts(1380.0)))))
        }

        /** The projection a publication at [at] makes from the recorder's checked read. */
        fun fromRecorder(key: GraphKey, at: Instant): PreparedGraph =
            projectGraphV2Live(exposed(key), rest(key), key.period, SCOPE, recorder.exposed(FENCE_A, lifetime), at)

        fun close() = scope.cancel()
    }

    private fun PreparedSeries.values(): List<Double> =
        linePoints.map { it.rate } + bandPoints.flatMap { listOf(it.low, it.high) } + listOfNotNull(extrema?.start, extrema?.endInclusive)

    // --- trailing deadline -----------------------------------------------------------------------------------------

    /** V01: changes at 0, 100 and 349 ms are published once at 350 ms with the latest; a change at 400 ms at 750 ms. */
    @Test fun V01_liveChangesArePublishedOnATrailingDeadlineThatIsNotReset() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        assertEquals("showing itself emits", t0, f.published.last().chart?.rightEdgeNow)
        assertEquals(t0, f.edge())
        assertEquals(f.expected(KEY_1D, t0), f.chart().prepared)

        f.pushNow(1341.1)
        assertEquals(t0, f.edge())
        f.advance(100)
        f.pushNow(1341.2)
        f.advance(249)
        f.pushNow(1341.3)
        assertEquals("349 ms: still the first publication", t0, f.edge())
        f.advance(1)
        val p1 = t0 + 350.milliseconds
        assertEquals("the deadline itself emits", p1, f.published.last().chart?.rightEdgeNow)
        assertEquals(p1, f.edge())
        assertEquals(f.expected(KEY_1D, p1), f.chart().prepared)
        assertEquals(LinePoint(p1, 1341.3), f.y().linePoints.last())

        f.advance(50)
        f.pushNow(1341.4)
        f.advance(349)
        assertEquals(p1, f.edge())
        f.advance(1)
        val p2 = t0 + 750.milliseconds
        assertEquals(p2, f.edge())
        assertEquals(LinePoint(p2, 1341.4), f.y().linePoints.last())
        assertEquals(listOf(t0, p1, p2), f.edges())
    }

    // --- visibility ------------------------------------------------------------------------------------------------

    /**
     * V02: hidden, nothing is scheduled or published and the chart is the REST build - not even for a changed REST answer;
     * showing publishes at once with the latest record and exposure; hiding drops the published result and its pending
     * deadline, which does not fire after a later show; the next change is scheduled afresh.
     */
    @Test fun V02_onlyAShownSurfaceIsPublishedTo() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.pushNow(1341.1)
        f.advance(1000)
        assertNull(f.edge())
        assertEquals(f.rest(KEY_1D), f.chart().prepared)
        assertTrue(f.edges().isEmpty())

        val s1 = t0 + 1000.milliseconds
        f.show()
        assertEquals(s1, f.edge())
        assertEquals(f.expected(KEY_1D, s1), f.chart().prepared)

        f.advance(10)
        f.pushNow(1341.2)
        f.advance(90)
        f.hide()
        assertNull(f.edge())
        assertEquals(f.rest(KEY_1D), f.chart().prepared)
        val mark = f.published.size
        f.advance(900)
        f.pushNow(1341.3)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, xy(xBase = 1401.0))
        f.advance(500)
        assertTrue(f.published.drop(mark).all { it.chart?.rightEdgeNow == null })

        val s2 = t0 + 2500.milliseconds
        f.show()
        assertEquals(s2, f.edge())
        assertEquals(f.expected(KEY_1D, s2), f.chart().prepared)
        assertEquals(LinePoint(s2, 1341.3), f.y().linePoints.last())
        assertEquals(listOf(s1, s2), f.edges())

        f.advance(10)
        f.pushNow(1341.4)
        f.advance(90)
        f.hide()
        f.advance(100)
        f.show()
        val s3 = t0 + 2700.milliseconds
        assertEquals(s3, f.edge())
        f.advance(300)
        assertEquals("the deadline from before the hide publishes nothing", listOf(s1, s2, s3), f.edges())
        f.pushNow(1341.5)
        f.advance(350)
        assertEquals("a change after the return is scheduled afresh", t0 + 3350.milliseconds, f.edge())
    }

    /**
     * V03: an open fullscreen counts once it reports, and then alone is a shown surface; a second shown surface is no new
     * exposure; with both reports gone the result is dropped; the closed fullscreen's token is refused; the inline surface
     * shown again publishes at once; a reopened fullscreen does not inherit the closed one's report.
     */
    @Test fun V03_eitherSurfaceShowsTheOneSharedResult() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.holder.enterFullscreen(f.token())
        f.run()
        assertNull("an open fullscreen is not shown until it reports", f.edge())
        val fs = checkNotNull(f.now().fullscreenToken)
        f.holder.setSurfaceVisible(fs, true)
        f.run()
        assertEquals(t0, f.edge())
        assertEquals(f.expected(KEY_1D, t0), f.chart().prepared)

        f.advance(10)
        f.show()
        assertEquals("already shown", t0, f.edge())
        f.hide()
        assertEquals("the fullscreen still shows it", t0, f.edge())
        f.holder.exitFullscreen(fs)
        f.run()
        assertNull(f.edge())
        f.holder.setSurfaceVisible(fs, true)
        f.run()
        assertNull("the closed fullscreen's token is refused", f.edge())

        f.advance(10)
        f.show()
        assertEquals(t0 + 20.milliseconds, f.edge())
        assertEquals(listOf(t0, t0 + 20.milliseconds), f.edges())

        f.holder.enterFullscreen(f.token())
        f.run()
        f.hide()
        assertNull("a reopened fullscreen does not inherit the closed one's report", f.edge())
    }

    // --- immediate events ------------------------------------------------------------------------------------------

    /**
     * V04: an answer equal to the exposure in hand is no event - the published result is kept and the pending deadline still
     * fires; a changed REST answer publishes at once and its pending deadline is cancelled; the next change is scheduled
     * afresh.
     */
    @Test fun V04_aChangedRestExposurePublishesAtOnce() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        f.advance(90)
        val p0 = f.chart().prepared
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, xy())
        assertSame("an unchanged answer is no new exposure", p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        f.advance(260)
        val p1 = t0 + 360.milliseconds
        assertEquals(p1, f.edge())

        f.advance(40)
        f.pushNow(1341.2)
        f.advance(100)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, xy(xBase = 1401.0))
        val r = t0 + 500.milliseconds
        assertEquals(r, f.edge())
        assertEquals(f.expected(KEY_1D, r), f.chart().prepared)
        f.advance(300)
        assertEquals(listOf(t0, p1, r), f.edges())
        f.pushNow(1341.3)
        f.advance(350)
        assertEquals("a change after the flush is scheduled afresh", t0 + 1150.milliseconds, f.edge())
    }

    /**
     * V05: a period change publishes nothing while its answer is loading and publishes at once when it arrives, with the long
     * projection and the record's fresh tip; the earlier pending deadline publishes nothing.
     */
    @Test fun V05_aNewPeriodPublishesAtOnce() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        f.advance(90)
        f.holder.selectPeriod(f.token(), GraphPeriod.THREE_MONTHS)
        f.run()
        assertEquals(GraphV2Content.LOADING, f.now().content)
        f.advance(100)
        f.answer(KEY_3M, ok(dto(GraphPeriod.THREE_MONTHS, S(X, quarterPts(1400.0)), S(Y, quarterPts(1380.0)))))
        val r = t0 + 200.milliseconds
        assertEquals(r, f.edge())
        assertEquals(GraphPeriod.THREE_MONTHS, f.chart().prepared.period)
        assertEquals(f.expected(KEY_3M, r), f.chart().prepared)
        assertEquals(LinePoint(r, 1341.1), f.y().linePoints.last())
        f.advance(300)
        assertEquals(listOf(t0, r), f.edges())
    }

    /** V06: a catalog that narrows the exposed graph publishes at once; the pending deadline publishes nothing. */
    @Test fun V06_aNarrowedCatalogPublishesAtOnce() = holderTest {
        val f = Fixture(this)
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, Y), listOf(Y))), ttlSeconds = 60)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(Y), listOf(Y))), ttlSeconds = 60)
        f.advance(60_900)
        f.pushNow(1341.1)
        f.advance(100)
        f.coordinator.onRefreshRequested()
        f.run()
        val r = t0 + 61.seconds
        assertEquals(setOf(Y), f.chart().prepared.bySeries.keys)
        assertEquals(r, f.edge())
        assertEquals(f.expected(KEY_1D, r, allowed = setOf(Y)), f.chart().prepared)
        f.advance(400)
        assertEquals(listOf(t0, r), f.edges())
    }

    /** V06b: a catalog fetched again unchanged is no event - the result is kept and the pending deadline fires on time. */
    @Test fun V06b_anUnchangedCatalogIsNoEvent() = holderTest {
        val f = Fixture(this)
        f.catalogDto = catalog("usd" to mapOf("1d" to period(listOf(X, Y), listOf(Y))), ttlSeconds = 60)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        val p0 = f.chart().prepared
        f.advance(60_900)
        f.pushNow(1341.1)
        f.advance(100)
        f.coordinator.onRefreshRequested()
        f.run()
        assertSame(p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        f.advance(250)
        assertEquals(t0 + 61_250.milliseconds, f.edge())
    }

    /**
     * V07: time passing alone changes nothing, not even at the tip's expiry; a time event publishes at once - at 12:00 the
     * 11:50 bucket ends at its own shoulder and the fresh tip ends the line, at 12:09:50 the 600 s old tip no longer does, at
     * 12:10:00 the projection is unchanged but the right edge still moves; hidden, a time event publishes nothing.
     */
    @Test fun V07_timeEventsArePublishedAtOnceAndOnlyWhenShown() = holderTest {
        val f = Fixture(this, start = NOON - 10.seconds)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.pushNow(1341.7)
        f.advance(350)
        val p1 = t0 + 350.milliseconds
        assertEquals(p1, f.edge())
        assertEquals(BandPoint(p1, 1341.7, 1341.7), f.y().bandPoints.last())
        assertEquals(LinePoint(p1, 1341.7), f.y().linePoints.last())

        f.advance(9_650)
        assertEquals("no timer of its own", p1, f.edge())
        f.holder.onTimeEvent()
        f.run()
        assertEquals("the time event itself emits", NOON, f.published.last().chart?.rightEdgeNow)
        assertEquals(NOON, f.edge())
        assertEquals(f.expected(KEY_1D, NOON), f.chart().prepared)
        assertEquals(BandPoint(NOON, 1341.7, 1341.7), f.y().bandPoints.last())
        assertTrue(f.y().bandPoints.none { it.ts > NOON })
        assertEquals(LinePoint(NOON, 1341.7), f.y().linePoints.last())

        f.advance(590_000)
        assertEquals("no timer of its own at the tip's expiry", NOON, f.edge())
        f.holder.onTimeEvent()
        f.run()
        val late = NOON + 590.seconds
        assertEquals(late, f.edge())
        assertEquals(LinePoint(NOON - 10.minutes, 1341.7), f.y().linePoints.last())

        val q = f.chart().prepared
        f.advance(10_000)
        f.holder.onTimeEvent()
        f.run()
        assertEquals(NOON + 600.seconds, f.edge())
        assertEquals("the edge moves though the projection is unchanged", q, f.chart().prepared)

        f.hide()
        f.advance(10_000)
        f.holder.onTimeEvent()
        f.run()
        assertNull(f.edge())
        assertEquals(listOf(t0, p1, NOON, late, NOON + 600.seconds), f.edges())
    }

    // --- nothing else publishes ------------------------------------------------------------------------------------

    /**
     * V08: before the deadline, repeated reads, a selection change, a fullscreen round trip and display and access-revision
     * emissions that change neither the owner nor the access keep the published result - the same instance and right edge -
     * and read no clock; the deadline still publishes on time, reading the clock once.
     */
    @Test fun V08_otherPathsReadOnlyThePublishedResult() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        val p0 = f.chart().prepared
        val reads = f.holderClockReads
        f.advance(10)
        f.pushNow(1341.1)
        repeat(3) {
            assertSame(p0, f.chart().prepared)
            assertEquals(t0, f.edge())
        }
        f.holder.toggleSeries(f.token(), X)
        f.run()
        assertEquals(setOf(X, Y), f.chart().renderedIds)
        assertSame(p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        f.holder.enterFullscreen(f.token())
        f.run()
        f.holder.exitFullscreen(checkNotNull(f.now().fullscreenToken))
        f.run()
        assertSame(p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        f.display.value = f.display.value.copy(containsSeed = !f.display.value.containsSeed)
        f.run()
        f.accessRevisions.value += 1
        f.run()
        assertSame(p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        f.advance(349)
        assertSame(p0, f.chart().prepared)
        assertEquals("only a publication reads the clock", reads, f.holderClockReads)
        f.advance(1)
        assertEquals("a publication reads the clock once", reads + 1, f.holderClockReads)
        assertEquals(t0 + 360.milliseconds, f.edge())
        assertNotSame(p0, f.chart().prepared)
        assertEquals(listOf(t0, t0 + 360.milliseconds), f.edges())
    }

    /** V09: an in-flight refresh and its failure keep the published result; live changes still publish afterwards. */
    @Test fun V09_aRequestInFlightOrFailedIsNoEvent() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        val p0 = f.chart().prepared
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        assertTrue(f.now().refreshing)
        assertSame(p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        f.answer(KEY_1D, status(500))
        assertNotNull(f.now().requestFailure)
        assertSame(p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        f.advance(10)
        f.pushNow(1341.1)
        f.advance(350)
        assertEquals(t0 + 360.milliseconds, f.edge())
        assertEquals(listOf(t0, t0 + 360.milliseconds), f.edges())
    }

    /** V09b: a failure that arrives while a deadline is pending leaves it in place. */
    @Test fun V09b_aFailureLeavesThePendingDeadline() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_1D, status(500))
        assertNotNull("premise", f.now().requestFailure)
        assertEquals(t0, f.edge())
        f.advance(350)
        assertEquals(t0 + 360.milliseconds, f.edge())
    }

    // --- retire, block, close ----------------------------------------------------------------------------------------

    /**
     * V10: a deactivation drops the pending deadline and the result; the reactivated screen starts hidden, refuses the old
     * token's report, and publishes at once when its own token reports it shown.
     */
    @Test fun V10_aRetiredContextStartsAgainHidden() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        val old = f.token()
        f.advance(90)
        f.holder.onDeactivated()
        f.run()
        assertEquals(GraphV2Content.INACTIVE, f.now().content)
        f.advance(400)
        assertEquals(listOf(t0), f.edges())
        f.holder.onActivated(OWNER_A)
        f.run()
        assertNotNull(f.now().chart)
        assertNull("a new context starts hidden", f.edge())
        f.holder.setSurfaceVisible(old, true)
        f.run()
        assertNull("the retired token is refused", f.edge())
        f.show()
        val r = t0 + 500.milliseconds
        assertEquals(r, f.edge())
        assertEquals(f.expected(KEY_1D, r), f.chart().prepared)
        assertEquals(listOf(t0, r), f.edges())
    }

    /** V10b: a new owner retires the context like a deactivation - its pending deadline is dropped and its screen starts hidden. */
    @Test fun V10b_aNewOwnerStartsHidden() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        val next = TopicDisplayOwner(A, 2L)
        f.display.value = TopicDisplayState.NONE.copy(owner = next)
        f.holder.onContextChanged()
        f.run()
        f.holder.onActivated(next)
        f.run()
        assertNotNull("premise: the new owner's chart", f.now().chart)
        assertNull("a new owner's screen starts hidden", f.edge())
        f.advance(400)
        assertNull(f.edge())
        assertEquals(listOf(t0), f.edges())
        f.show()
        assertEquals(t0 + 410.milliseconds, f.edge())
    }

    /**
     * V11: a blocked access drops the pending deadline, the result and the surface reports; reopened, nothing is published
     * until a surface reports again - a surface that left during the block could not say so - and then at once, with the
     * current time; the result from before the block is never shown again.
     */
    @Test fun V11_aBlockedAccessDropsTheResultAndTheReports() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        f.advance(90)
        f.protectedOpen = false
        assertEquals(GraphV2Content.BLOCKED, f.now().content)
        f.run()
        val mark = f.published.size
        f.advance(400)
        assertEquals(listOf(t0), f.edges())
        f.protectedOpen = true
        f.now()
        f.run()
        assertNotNull("premise: the reopened chart", f.now().chart)
        assertNull("nothing is published unseen", f.edge())
        f.show()
        val r = t0 + 500.milliseconds
        assertEquals(r, f.edge())
        assertEquals(f.expected(KEY_1D, r), f.chart().prepared)
        assertTrue(f.published.drop(mark).none { it.chart?.rightEdgeNow == t0 })
    }

    /** V12: close drops the pending deadline; nothing drawable is published afterwards. */
    @Test fun V12_closeDropsThePendingPublication() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        val mark = f.published.size
        val closing = async { f.holder.close() }
        f.run()
        f.advance(500)
        assertTrue(closing.isCompleted)
        assertEquals(GraphV2Content.INACTIVE, f.now().content)
        assertTrue(f.published.drop(mark).none { it.chart != null })
        assertEquals(listOf(t0), f.edges())
    }

    // --- what is published ---------------------------------------------------------------------------------------------

    /**
     * V13 (T05, T05-L across a return): a value published before the surface was hidden appears in no state from the hide on,
     * once the record no longer holds it - the first publication after showing again is computed afresh.
     */
    @Test fun V13_T05_noOldValueSurvivesAReturn() = holderTest {
        for (old in listOf(1345.7, 1337.7)) {
            val f = Fixture(this)
            f.ready()
            val t0 = f.clock.now()
            f.push(NOON - 3.hours + 30.seconds to old)
            f.show()
            assertTrue("$old premise", old in f.y().values())
            f.advance(100)
            f.hide()
            val mark = f.published.size
            f.live.value = mapOf(KY to observeRecoverable(
                GraphRecoverableState.empty(KY, GraphObservationOrder(emptyList())),
                listOf(GraphObservation(KY, GraphObservationId("kb", "usd-krw", f.clock.now(), 1341.7))),
                f.clock.now()
            ).state)
            f.run()
            f.advance(100)
            f.show()
            assertEquals("$old", t0 + 200.milliseconds, f.edge())
            assertTrue("$old", 1341.7 in f.y().values())
            assertTrue("$old", f.published.drop(mark).none { state ->
                state.chart?.prepared?.bySeries?.get(Y)?.values()?.contains(old) == true
            })
        }
    }

    /**
     * V14: a series the REST answer left empty becomes drawable from the live record alone - NO_DATA, then READY with a last
     * observation - while a record under another access epoch is not read.
     */
    @Test fun V14_aLiveOnlySeriesBecomesReady() = holderTest {
        val f = Fixture(this)
        f.open()
        f.answer(KEY_1D, day(S(X, pts(6, 1400.0)), S(Y, emptyList())))
        assertEquals(GraphV2Content.NO_DATA, f.now().content)
        val t0 = f.clock.now()
        f.show()
        assertEquals(t0, f.edge())
        assertEquals(GraphV2Content.NO_DATA, f.now().content)
        f.push(f.clock.now() to 1341.7, key = GraphObservationSeriesKey(GraphDataScope("u1", "e2"), Y))
        f.advance(10)
        f.holder.onTimeEvent()
        f.run()
        assertEquals(t0 + 10.milliseconds, f.edge())
        assertEquals("another epoch's record is not read", GraphV2Content.NO_DATA, f.now().content)
        f.pushNow(1341.7)
        f.advance(350)
        assertEquals(GraphV2Content.READY, f.now().content)
        assertEquals(LinePoint(NOON, 1341.7), f.y().lastObservation)
    }

    /** V15: the presenter's projection takes the chart's right edge - the rolling 1d frame ends there; without one, at the domain. */
    @Test fun V15_thePresenterUsesTheChartsRightEdge() {
        val graph = FreeGraph(
            "10min",
            listOf(FreeGraphSeries(Y, listOf(
                FreeGraphPoint(DAY0 + 1.hours, 1380.0, 1381.0, 1379.0),
                FreeGraphPoint(DAY0 + 1.hours + 10.minutes, 1381.0, 1382.0, 1380.0)
            ), axisGroup = "krw")),
            domainStartAt = DAY0, domainEndAt = DAY0 + 24.hours, liveDomainMode = "rolling"
        )
        val prepared = GraphPreparedBuilder.build(graph, GraphPeriod.ONE_DAY)
        assertEquals(
            TimeFrame(NOON - 24.hours, NOON),
            checkNotNull(GraphV2ScreenPresenter.project(GraphV2ChartModel(prepared, setOf(Y), NOON))).data
        )
        assertEquals(
            TimeFrame(DAY0, DAY0 + 24.hours),
            checkNotNull(GraphV2ScreenPresenter.project(GraphV2ChartModel(prepared, setOf(Y)))).data
        )
    }

    // --- r2: completeness rows -------------------------------------------------------------------------------------

    /**
     * V16 (T16, T16-L across a return): a current-bucket seed [1341.7, 1341.9], an app value of 1342.1 (1341.5), and a
     * 1342.2 (1341.4) observed while a REST refresh is pending, in two orders. From the return on, every published state
     * carries only ranges the record held at some point - never the value only the earlier publication had - and the final
     * ranges are [1341.7, 1342.1] then [1341.7, 1342.2] ([1341.5, 1341.9] then [1341.4, 1341.9]).
     */
    @Test fun V16_T16_everyPublicationCarriesOnlyItsEvidence() = holderTest {
        for (low in listOf(false, true)) for (seedFirst in listOf(true, false)) {
            val case = "low=$low seedFirst=$seedFirst"
            val f = Fixture(this)
            f.ready()
            val seed = GraphV2InProgress(NOON, 1341.9, 1341.7, 1341.8, NOON)
            val first = if (low) 1341.5 else 1342.1
            val second = if (low) 1341.4 else 1342.2
            val seeded = 1341.7 to 1341.9
            val afterFirst = if (low) 1341.5 to 1341.9 else 1341.7 to 1342.1
            val afterSecond = if (low) 1341.4 to 1341.9 else 1341.7 to 1342.2
            val allowed = setOf(seeded, first to first, afterFirst, afterSecond)

            f.push(NOON - 3.hours + 30.seconds to 1345.7)
            f.show()
            assertTrue("$case premise", 1345.7 in f.y().values())
            f.hide()
            val mark = f.published.size
            f.live.value = emptyMap()
            f.run()
            f.advance(100)
            if (seedFirst) f.applySeed(seed) else f.pushNow(first)
            f.show()
            f.advance(100)
            if (seedFirst) f.pushNow(first) else f.applySeed(seed)
            f.advance(350)
            assertEquals(case, afterFirst, f.y().bandPoints.single { it.ts == NOON }.let { it.low to it.high })
            f.holder.onTimeEvent()
            f.run()
            f.coordinator.onRefreshRequested(force = true)
            f.run()
            f.advance(100)
            f.pushNow(second)
            f.advance(350)
            assertEquals(case, afterSecond, f.y().bandPoints.single { it.ts == NOON }.let { it.low to it.high })
            f.answer(KEY_1D, xy())
            f.holder.onTimeEvent()
            f.run()
            assertEquals(case, afterSecond, f.y().bandPoints.single { it.ts == NOON }.let { it.low to it.high })

            for (state in f.published.drop(mark)) {
                val y = state.chart?.prepared?.bySeries?.get(Y) ?: continue
                assertFalse("$case $state", 1345.7 in y.values())
                for (band in y.bandPoints.filter { it.ts >= NOON }) assertTrue("$case $band", (band.low to band.high) in allowed)
            }
        }
    }

    /**
     * V17 (23:59 -> 00:00): a deadline set at 23:59:59.900 and due at 00:00:00.250 reads the new day - the 23:50 bucket ends at
     * its 00:00 shoulder, its fresh tip ends the line, and the rolling frame ends at that right edge.
     */
    @Test fun V17_aDeadlineAcrossMidnightReadsTheNewDay() = holderTest {
        val start = Instant.parse("2026-10-05T14:59:59.800Z")
        val midnight = Instant.parse("2026-10-05T15:00:00Z")
        val f = Fixture(this, start = start)
        f.ready()
        f.show()
        f.advance(100)
        f.pushNow(1341.5)
        f.advance(349)
        assertEquals(start, f.edge())
        f.advance(1)
        val p = start + 450.milliseconds
        assertEquals(p, f.edge())
        assertEquals(f.expected(KEY_1D, p), f.chart().prepared)
        assertEquals(BandPoint(midnight, 1341.5, 1341.5), f.y().bandPoints.last())
        assertEquals(LinePoint(p, 1341.5), f.y().linePoints.last())
        assertEquals(TimeFrame(p - 24.hours, p), checkNotNull(GraphV2ScreenPresenter.project(f.chart())).data)
    }

    /** V18: the 350 ms is measured on the dispatcher - a clock jump while it is pending neither shortens nor lengthens it. */
    @Test fun V18_theDeadlineIsMeasuredOnTheDispatcher() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        f.advance(90)
        f.offset = 5.minutes
        f.advance(259)
        assertEquals(t0, f.edge())
        f.advance(1)
        val p = t0 + 360.milliseconds + 5.minutes
        assertEquals(p, f.edge())
        assertEquals(f.expected(KEY_1D, p), f.chart().prepared)
    }

    /** V19: a time event publishes at once and cancels the pending deadline; a later change is scheduled afresh. */
    @Test fun V19_aTimeEventReplacesThePendingDeadline() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.advance(10)
        f.pushNow(1341.1)
        f.advance(90)
        f.holder.onTimeEvent()
        f.run()
        val e = t0 + 100.milliseconds
        assertEquals(e, f.edge())
        assertEquals(f.expected(KEY_1D, e), f.chart().prepared)
        f.advance(300)
        assertEquals("the cancelled deadline publishes nothing", listOf(t0, e), f.edges())
        f.pushNow(1341.2)
        f.advance(350)
        assertEquals("a later change is scheduled afresh", t0 + 750.milliseconds, f.edge())
    }

    /** V20: a surface reported shown while the answer is loading is published to as soon as the chart arrives. */
    @Test fun V20_aSurfaceShownWhileLoadingIsPublishedToWhenTheChartArrives() = holderTest {
        val f = Fixture(this)
        f.open()
        assertEquals("premise", GraphV2Content.LOADING, f.now().content)
        val t0 = f.clock.now()
        f.show()
        assertNull(f.now().chart)
        f.advance(100)
        f.answer(KEY_1D, xy())
        assertEquals("premise", GraphV2Content.READY, f.now().content)
        val r = t0 + 100.milliseconds
        assertEquals(r, f.edge())
        assertEquals(f.expected(KEY_1D, r), f.chart().prepared)
    }

    /**
     * V21: with the inline surface shown, the fullscreen reporting, the inline leaving and returning, and the fullscreen
     * closing are no event - the result is kept and the pending deadline publishes on time.
     */
    @Test fun V21_theFullscreenComingAndGoingOverAShownInlineIsNoEvent() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        val p0 = f.chart().prepared
        f.advance(10)
        f.holder.enterFullscreen(f.token())
        f.run()
        val fs = checkNotNull(f.now().fullscreenToken)
        f.holder.setSurfaceVisible(fs, true)
        f.run()
        assertEquals("a second shown surface is no new exposure", t0, f.edge())
        f.pushNow(1341.1)
        f.advance(40)
        f.hide()
        assertEquals(t0, f.edge())
        f.advance(50)
        f.show()
        f.advance(50)
        f.holder.exitFullscreen(fs)
        f.run()
        assertSame(p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        f.advance(210)
        assertEquals(t0 + 360.milliseconds, f.edge())
        assertEquals(listOf(t0, t0 + 360.milliseconds), f.edges())
    }

    /** V22: after a period change the old inline token's hide is refused - the new screen stays published to. */
    @Test fun V22_aRefusedHideChangesNothing() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        val old = f.token()
        f.holder.selectPeriod(old, GraphPeriod.THREE_MONTHS)
        f.run()
        f.advance(10)
        f.answer(KEY_3M, ok(dto(GraphPeriod.THREE_MONTHS, S(X, quarterPts(1400.0)), S(Y, quarterPts(1380.0)))))
        val r = t0 + 10.milliseconds
        assertEquals("premise: still shown across the period", r, f.edge())
        f.pushNow(1341.1)
        f.holder.setSurfaceVisible(old, false)
        f.run()
        assertEquals("the retired token's hide is refused", r, f.edge())
        f.advance(350)
        assertEquals(r + 350.milliseconds, f.edge())
    }

    /** V23: a changed long-period REST answer is projected from its own build, not the previous answer's. */
    @Test fun V23_aChangedLongRestExposureIsProjectedFromItsOwnBuild() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.holder.selectPeriod(f.token(), GraphPeriod.THREE_MONTHS)
        f.run()
        f.answer(KEY_3M, ok(dto(GraphPeriod.THREE_MONTHS, S(X, quarterPts(1400.0)), S(Y, quarterPts(1380.0)))))
        f.advance(10)
        f.coordinator.onRefreshRequested(force = true)
        f.run()
        f.answer(KEY_3M, ok(dto(GraphPeriod.THREE_MONTHS, S(X, quarterPts(1401.0)), S(Y, quarterPts(1381.0)))))
        val r = t0 + 10.milliseconds
        assertEquals(r, f.edge())
        assertEquals(f.expected(KEY_3M, r), f.chart().prepared)
    }

    /** V24: a chart withheld while the selection is confirmed keeps its key and result; its return publishes nothing. */
    @Test fun V24_aChartWithheldForConfirmationKeepsItsResult() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        val p0 = f.chart().prepared
        f.advance(10)
        f.holder.retrySelection(f.token())
        assertNull("premise: the chart is withheld while confirming", f.now().chart)
        f.run()
        assertEquals("premise", GraphV2SelectionStatus.READY, f.now().selectionStatus)
        assertSame(p0, f.chart().prepared)
        assertEquals(t0, f.edge())
        assertEquals(listOf(t0), f.edges())
    }

    /**
     * V25: while the selection is confirmed the chart is withheld - a live change then schedules nothing, even when the chart
     * returns before 350 ms; a deadline that comes due while the chart is withheld publishes nothing and leaves the scheduler
     * usable for the next change once the chart is back.
     */
    @Test fun V25_aWithheldChartSchedulesNothingAndKeepsTheSchedulerUsable() = holderTest {
        val f = Fixture(this)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        val held = f.selections.pauseNextConfirm()
        f.holder.retrySelection(f.token())
        f.run()
        assertNull("premise: withheld while confirming", f.now().chart)
        f.advance(10)
        f.pushNow(1341.1)
        f.advance(90)
        held.complete(Unit)
        f.run()
        assertEquals("premise", GraphV2SelectionStatus.READY, f.now().selectionStatus)
        assertEquals(t0, f.edge())
        f.advance(300)
        assertEquals("a change while withheld schedules nothing", listOf(t0), f.edges())

        f.pushNow(1341.2)
        val again = f.selections.pauseNextConfirm()
        f.advance(100)
        f.holder.retrySelection(f.token())
        f.run()
        assertNull("premise: withheld again", f.now().chart)
        f.advance(300)
        again.complete(Unit)
        f.run()
        assertEquals("the deadline that came due while withheld published nothing", t0, f.edge())
        f.pushNow(1341.3)
        f.advance(350)
        assertEquals("the scheduler is still usable", t0 + 1150.milliseconds, f.edge())
    }

    /**
     * V26: a change made while hidden leaves no deadline - not even behind a re-exposure that finds the chart withheld and
     * cannot publish; when the chart returns with the same key nothing is published.
     */
    @Test fun V26_aHiddenChangeLeavesNoDeadlineBehindAWithheldReexposure() = holderTest {
        val f = Fixture(this)
        f.ready()
        f.show()
        f.hide()
        f.pushNow(1341.1)
        val held = f.selections.pauseNextConfirm()
        f.holder.retrySelection(f.token())
        f.run()
        assertNull("premise: withheld while confirming", f.now().chart)
        f.advance(100)
        f.show()
        assertNull("premise: still withheld", f.now().chart)
        f.advance(100)
        held.complete(Unit)
        f.run()
        assertNotNull("premise: the chart is back", f.now().chart)
        assertNull("a same-key return is no event", f.edge())
        f.advance(150)
        assertNull("the change made while hidden left no deadline", f.edge())
    }

    /**
     * V27: an access block that only the check after rendering finds - here it closes during the publication's clock read -
     * clears the reports like any block, inline and fullscreen: reopened before another render, nothing is published until
     * a surface reports again.
     */
    @Test fun V27_aBlockFoundAfterRenderingAlsoClearsTheReports() = holderTest {
        for (fullscreen in listOf(false, true)) {
            val f = Fixture(this)
            f.ready()
            if (fullscreen) {
                f.holder.enterFullscreen(f.token())
                f.run()
                f.holder.setSurfaceVisible(checkNotNull(f.now().fullscreenToken), true)
                f.run()
            } else {
                f.show()
            }
            assertNotNull("fullscreen=$fullscreen premise: published", f.edge())
            f.advance(10)
            f.onClockRead = {
                f.onClockRead = null
                f.protectedOpen = false
            }
            f.holder.onTimeEvent()
            assertEquals("fullscreen=$fullscreen premise: found after rendering", GraphV2Content.BLOCKED, f.holder.state.value.content)
            f.protectedOpen = true
            assertNotNull("fullscreen=$fullscreen premise: reopened", f.now().chart)
            assertNull("fullscreen=$fullscreen: a fresh report is needed", f.edge())
        }
    }

    // === F2f: the holder reads the graph recorder (r6) =======================================================================
    //
    // With a recorder the holder subscribes to its state only as a change signal and, inside each publication, reads
    // `recorder.exposed(context fence, context lifetime)` in place of `live.value` - never merged with live, never filled from
    // it, never the raw state. The holder neither starts nor closes the recorder; its own end leaves the recorder recording.

    /** W01: a recorder change is published on the 350 ms deadline with the recorder's latest checked read, not the first one. */
    @Test fun W01_aRecorderChangeIsPublishedWithItsLatestEvidence() = holderTest {
        val f = Fixture(this, withRecorder = true)
        f.ready()
        val t0 = f.clock.now()
        f.show()
        f.record(1341.1)
        f.advance(100)
        f.record(1345.5, f.clock.now())
        f.advance(249)
        assertEquals("349 ms: still the first publication", t0, f.edge())
        f.advance(1)
        val t1 = t0 + 350.milliseconds
        assertEquals(t1, f.edge())
        assertEquals(f.fromRecorder(KEY_1D, t1), f.chart().prepared)
    }

    /** W02: with a recorder, live is neither subscribed nor merged; an empty recorder read is not filled from live. */
    @Test fun W02_theRecorderReplacesLive() = holderTest {
        Fixture(this, withRecorder = true).let { f ->
            f.ready()
            val t0 = f.clock.now()
            f.show()
            f.push(t0 to 1360.0)
            f.push(t0 to 1370.0, key = KX)
            f.advance(400)
            assertEquals("a live change schedules nothing", listOf(t0), f.edges())
            f.record(1341.1)
            f.advance(350)
            val t1 = checkNotNull(f.edge())
            assertEquals("recorder only", f.fromRecorder(KEY_1D, t1), f.chart().prepared)
        }
        Fixture(this, withRecorder = true).let { f ->
            f.ready()
            f.show()
            f.push(f.clock.now() to 1360.0)
            f.advance(10)
            f.holder.onTimeEvent()
            f.run()
            val at = checkNotNull(f.edge())
            assertTrue("premise: the recorder has nothing", f.recorder.exposed(FENCE_A, f.lifetime).isEmpty())
            assertEquals(
                "nothing from live", projectGraphV2Live(f.exposed(KEY_1D), f.rest(KEY_1D), GraphPeriod.ONE_DAY, SCOPE, emptyMap(), at),
                f.chart().prepared
            )
        }
    }

    /** W03: data the recorder still holds but its checked read refuses (an unsynced user end) is never drawn. */
    @Test fun W03_rawRecorderStateIsNeverDrawn() = holderTest {
        val f = Fixture(this, withRecorder = true)
        f.ready()
        f.show()
        f.record(1341.1)
        f.advance(350)
        f.snapshot = f.snapshot.copy(
            lastUserEnd = TopicAccessEnd(1L, TopicAccessEndReason.SIGNED_OUT, EntitlementsIdentity("u1", 1L), "u1", "e1")
        )
        assertTrue("premise: the raw state still holds it", KY in f.recorder.state.value.series)
        assertTrue("premise: the checked read refuses it", f.recorder.exposed(FENCE_A, f.lifetime).isEmpty())
        f.advance(10)
        f.holder.onTimeEvent()
        f.run()
        val at = checkNotNull(f.edge()) { "premise: the holder still shows the chart" }
        assertEquals(f.expected(KEY_1D, at), f.chart().prepared)
    }

    /** W04: hiding, deactivating, leaving the fullscreen and closing the holder keep the recorder's data and its recording. */
    @Test fun W04_aScreenEndLeavesTheRecorderRecording() = holderTest {
        for (end in listOf("hide", "deactivate", "fullscreen", "close")) {
            val f = Fixture(this, withRecorder = true)
            f.ready()
            f.show()
            f.record(1341.1)
            val held = f.recorder.state.value.series.getValue(KY)
            when (end) {
                "hide" -> f.hide()
                "deactivate" -> {
                    f.holder.onDeactivated()
                    f.run()
                }
                "fullscreen" -> {
                    f.holder.enterFullscreen(f.token())
                    f.run()
                    val fs = checkNotNull(f.now().fullscreenToken)
                    f.holder.setSurfaceVisible(fs, true)
                    f.run()
                    f.holder.exitFullscreen(fs)
                    f.run()
                }
                "close" -> {
                    val closing = async { f.holder.close() }
                    f.run()
                    assertTrue("$end premise", closing.isCompleted)
                }
            }
            assertSame("$end: the data is kept", held, f.recorder.state.value.series[KY])
            f.advance(1_000)
            val at = f.clock.now()
            f.record(1342.2, at)
            assertTrue(
                "$end: the recorder still records",
                GraphObservationId("kb", "usd-krw", at, 1342.2) in f.recorder.state.value.series.getValue(KY).data.app.observations
            )
        }
    }

    /** W05: starting a holder does not start the recorder; a new holder with its own session reads the same recorder later. */
    @Test fun W05_theHolderNeverStartsTheRecorderAndANewHolderReadsIt() = holderTest {
        val f = Fixture(this, withRecorder = true)
        f.ready()
        assertEquals("the holder read nothing of the recorder before showing", 0, f.recorderReads)
        f.recorder.start()
        f.run()
        assertTrue("the host started it", f.recorderReads > 0)
        f.record(1341.1)
        val closing = async { f.holder.close() }
        f.run()
        assertTrue(closing.isCompleted)

        f.advance(1_000)
        f.record(1342.2, f.clock.now())
        val next = f.newHolder()
        next.start()
        f.run()
        next.onActivated(OWNER_A)
        f.run()
        val token = checkNotNull(next.currentState().inlineToken) { "no inline token in ${next.currentState()}" }
        next.setSurfaceVisible(token, true)
        f.run()
        val at = checkNotNull(next.currentState().chart?.rightEdgeNow)
        assertEquals("the kept and the later evidence", f.fromRecorder(KEY_1D, at), next.currentState().chart?.prepared)
        assertTrue(f.recorder.state.value.series.getValue(KY).data.app.observations.map { it.rate }.containsAll(listOf(1341.1, 1342.2)))
        val ending = async { next.close() }
        f.run()
        assertTrue(ending.isCompleted)
    }

    // --- S4 RT05a: the adapter's time events and return (rows E03, E06, rt05_api_agreed.r2 §6) --------------------------

    /** One E03 case: [id]'s recorder tip at t0 + [late] ms on a 3m chart, judged by the adapter's ticks around [freshness] s. */
    private fun TestScope.freshnessCase(id: String, source: String, freshness: Int, late: Long) {
        val f = Fixture(this, withRecorder = true)
        f.ready()
        f.quarter()
        val key = GraphObservationSeriesKey(SCOPE, id)
        val t0 = f.clock.now()
        f.record(1341.7, t0 + late.milliseconds, source)
        f.events().onForeground(true)
        f.run()
        val buckets = f.recorder.state.value.series.getValue(key).data.app.buckets
        val rest = f.rest(KEY_3M).bySeries.getValue(id)
        fun series() = f.chart().prepared.bySeries.getValue(id)
        val case = "$id at +$late ms"

        f.advance((freshness - 30) * 1_000L)
        val before = t0 + (freshness - 30).seconds
        assertEquals("$case: the tick moved the edge", before, f.edge())
        assertEquals(case, LinePoint(before, 1341.7), series().linePoints.last())
        f.advance(30_000)
        val at = t0 + freshness.seconds
        assertEquals(case, at, f.edge())
        if (late == 0L) {
            assertEquals("$case: at its freshness only the end goes; REST closes and extrema stay", rest, series())
        } else {
            assertEquals("$case: 1 ms younger than its freshness", LinePoint(at, 1341.7), series().linePoints.last())
            f.advance(30_000)
            assertEquals("$case: then it goes", rest, series())
        }
        assertEquals("$case: no recorder bucket added", buckets, f.recorder.state.value.series.getValue(key).data.app.buckets)
    }

    /**
     * E03 (RT05-T01): the adapter's freshness tick moves a 3m chart's right end. A recorder tip ends it while younger than its
     * freshness - 600 s, hana 1200 s - and at that age the tick drops only the end; REST closes and extrema stay and no
     * recorder bucket is added.
     */
    @Test fun E03_theFreshnessTickEndsOnlyAStaleTip() = holderTest {
        for (late in listOf(0L, 1L)) {
            freshnessCase(Y, "kb", 600, late)
            freshnessCase(X, "hana", 1200, late)
        }
    }

    /**
     * E06 (RT05-T02, §5 item 2): a return retains before its time event. Hidden through it, the re-show flush projects the
     * retained record: past a day nothing of the old window or tip is drawn, and after a clock reversal of two hours that evicts
     * the five furthest-future buckets of a full record the 3m end is the re-chosen tip - the one an unretained record would
     * not draw. Shown through it, the return's own time event already draws the re-chosen tip.
     */
    @Test fun E06_theFirstPublicationAfterAReturnProjectsTheRetainedRecord() = holderTest {
        val f = Fixture(this, withRecorder = true)
        f.ready()
        f.show()
        f.record(1341.7)
        f.advance(350)
        val e = f.events()
        e.onForeground(true)
        f.run()
        f.hide()
        e.onForeground(false)
        f.run()
        f.offset = 26.hours
        f.recorderOffset = 26.hours
        val mark = f.published.size
        e.onForeground(true)
        f.run()
        assertEquals("the return's time event publishes nothing while hidden", mark, f.published.size)
        assertTrue("retained before the flush: 12:00 is outside 25 h",
            f.recorder.state.value.series.getValue(KY).data.app.buckets.isEmpty())
        f.show()
        val later = f.clock.now() + f.offset
        assertEquals(later, f.edge())
        assertEquals(f.fromRecorder(KEY_1D, later), f.chart().prepared)
        assertTrue("no old point and no tip end", 1341.7 !in f.y().values())

        val g = Fixture(this, withRecorder = true)
        g.ready()
        g.quarter()
        // 151 kb buckets, one per 10 minutes from 25 h before 12:00, oldest first: the tip is 12:00's 1450.0.
        for (i in 150 downTo 0) g.record(1450.0 - i, NOON - (i * 10).minutes)
        val r = g.events()
        r.onForeground(true)
        g.run()
        g.hide()
        r.onForeground(false)
        g.run()
        g.offset = (-2).hours
        g.recorderOffset = (-2).hours
        val at = g.clock.now() + g.offset
        assertEquals("premise: an unretained record ends at 12:00's tip", LinePoint(at, 1450.0),
            g.fromRecorder(KEY_3M, at).bySeries.getValue(Y).linePoints.last())
        r.onForeground(true)
        g.run()
        g.show()
        assertEquals(at, g.edge())
        assertEquals("the tip re-chosen after the eviction", LinePoint(at, 1445.0), g.y().linePoints.last())
        assertEquals(g.fromRecorder(KEY_3M, at), g.chart().prepared)

        val h = Fixture(this, withRecorder = true)
        h.ready()
        h.quarter()
        for (i in 150 downTo 0) h.record(1450.0 - i, NOON - (i * 10).minutes)
        val v = h.events()
        v.onForeground(true)
        h.run()
        v.onForeground(false)
        h.run()
        h.offset = (-2).hours
        h.recorderOffset = (-2).hours
        v.onForeground(true)
        h.run()
        val back = h.clock.now() + h.offset
        assertEquals("shown through the return", back, h.edge())
        assertEquals("its own time event draws the re-chosen tip", LinePoint(back, 1445.0), h.y().linePoints.last())
    }
}
