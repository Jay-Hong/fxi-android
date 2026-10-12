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
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRecorder
import com.jay.fxi.data.graph.GraphRecorderState
import com.jay.fxi.data.graph.GraphRecoverableState
import com.jay.fxi.data.graph.GraphRecoveryEvents
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
import com.jay.fxi.data.remote.dto.GraphV2InProgressSeed
import com.jay.fxi.data.remote.dto.GraphV2Metadata
import com.jay.fxi.data.remote.dto.GraphV2Point
import com.jay.fxi.data.remote.dto.GraphV2Provenance
import com.jay.fxi.data.remote.dto.GraphV2Range
import com.jay.fxi.data.remote.dto.GraphV2Series
import com.jay.fxi.data.remote.dto.GraphV2TabResponse
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSeriesSelection
import com.jay.fxi.time.AppClock
import com.jay.fxi.ui.graph.LinePoint
import com.jay.fxi.ui.graph.PreparedSeries
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Instant
import okhttp3.Headers
import org.junit.rules.TemporaryFolder
import com.jay.fxi.ui.premium.graph.TetherGraphData.A
import com.jay.fxi.ui.premium.graph.TetherGraphData.FENCE_A
import com.jay.fxi.ui.premium.graph.TetherGraphData.OWNER_A
import com.jay.fxi.ui.premium.graph.TetherGraphData.catalog
import com.jay.fxi.ui.premium.graph.TetherGraphData.ok

/**
 * S5-2a/S5-2b test fixture shared by the tether graph holder tests: the premium usd and tether holders over the real request
 * coordinator, graph recorder and cache gate, with the server data the tests answer with. Moved here unchanged from
 * TetherGraphHolderTest (S5-2a) and extended with a start time, a publication collector, the real recovery-event producer,
 * hide, failures and a display-side live feed (agreed plan R4c/S5 s5_2b1_plan.r2.md).
 */
internal object TetherGraphData {
    /** 12:00 KST, the start of B. */
    val B: Instant = Instant.parse("2026-10-05T03:00:00Z")
    val DAY0: Instant = Instant.parse("2026-10-04T15:00:00Z")
    val A = AuthIdentityFence("u1", 1L)
    val FENCE_A = TopicSessionFence(A, "e1", TopicGrantToken(7L))
    val OWNER_A = TopicDisplayOwner(A, 1L)
    val EXCHANGES = listOf("upbit", "bithumb", "coinone", "korbit", "gopax")
    val REFERENCES = listOf("investing.usd", "kb.usd", "hana.usd")
    const val KRX = "krx.usd-krw-futures"
    val USD_1D = listOf("investing.usd", "kb.usd", "hana.usd", "dxy")
    val TETHER_1D = EXCHANGES.map { "$it.usdt-krw" } + KRX + REFERENCES + "dxy" + "dxy_futures"
    val LONG_USD = listOf("investing.usd", KRX, "hana.usd", "dxy")
    val LONG_TETHER = listOf("bithumb.usdt-krw", KRX, "investing.usd", "hana.usd", "dxy")

    fun <T> ok(body: T) = AuthenticatedHttpResponse(200, Headers.headersOf(), body, null, byteArrayOf(1))

    fun <T> status(code: Int): AuthenticatedHttpResponse<T> = AuthenticatedHttpResponse(
        code, Headers.headersOf(), null,
        AuthenticatedHttpFailure(code, Headers.headersOf(), byteArrayOf(), null, null, AuthenticatedFailureKind.OTHER_HTTP),
        byteArrayOf(1)
    )

    fun catalog(tetherOneDay: List<String> = TETHER_1D, ttlSeconds: Int = 172800) = GraphV2CatalogResponse(
        tabs = listOf(
            GraphV2CatalogTab("usd", "usd", emptyMap(), mapOf(
                "1d" to GraphV2CatalogPeriod(USD_1D, listOf("investing.usd", "hana.usd")),
                "3m" to GraphV2CatalogPeriod(LONG_USD, listOf("investing.usd", "hana.usd"))
            )),
            GraphV2CatalogTab("tether", "tether", emptyMap(), mapOf(
                "1d" to GraphV2CatalogPeriod(tetherOneDay, listOf("upbit.usdt-krw", "bithumb.usdt-krw", "hana.usd", KRX)),
                "1w" to GraphV2CatalogPeriod(LONG_TETHER, listOf("bithumb.usdt-krw", "hana.usd", KRX)),
                "3m" to GraphV2CatalogPeriod(LONG_TETHER, listOf("bithumb.usdt-krw", "hana.usd", KRX)),
                "1y" to GraphV2CatalogPeriod(LONG_TETHER, listOf("bithumb.usdt-krw", "hana.usd", KRX))
            ))
        ),
        version = "2026-10-05",
        supportedPeriods = listOf("1w", "3m", "1y"),
        cacheTtlSeconds = ttlSeconds
    )

    fun series(id: String, points: List<GraphV2Point>) = GraphV2Series(
        id, id, if (id.startsWith("dxy")) "index" else "krw", if (id.startsWith("dxy")) "pt" else "KRW", 2, points,
        GraphV2Provenance(false, emptyList())
    )

    /** Six closed 10-minute points from 01:00 KST, the same for a series id in every tab. */
    fun dayPoints(id: String): List<GraphV2Point> {
        val base = if (id.startsWith("dxy")) 104.0 else 1340.0 + id.length
        return List(6) { i -> GraphV2Point(DAY0 + 1.hours + 10.minutes * i, base + i, "x", base + i + 1, base + i - 1) }
    }

    fun day(tab: String, ids: List<String>, seeds: Map<String, GraphV2InProgressSeed> = emptyMap()) = ok(GraphV2TabResponse(
        tab = tab, period = "1d",
        series = ids.map { series(it, dayPoints(it)) },
        metadata = GraphV2Metadata(B - 1.minutes, "10min", GraphV2Range("2026-10-05", "2026-10-05"),
            domainStartAt = DAY0, domainEndAt = DAY0 + 24.hours, liveDomainMode = "rolling"),
        inProgress = seeds
    ))

    fun seed(low: Double, high: Double, close: Double) = GraphV2InProgressSeed(B, high, low, close, B + 30.seconds)

    fun longPoints(id: String, period: GraphPeriod): List<GraphV2Point> {
        val offset = when (period) { GraphPeriod.ONE_WEEK -> 0.0; GraphPeriod.THREE_MONTHS -> 20.0; else -> 40.0 }
        val base = (if (id.startsWith("dxy")) 103.0 else 1330.0 + id.length) + offset / (if (id.startsWith("dxy")) 10.0 else 1.0)
        val (start, step) = when (period) {
            GraphPeriod.ONE_WEEK -> (B - 7.hours) to 1.hours
            GraphPeriod.THREE_MONTHS -> (DAY0 - 240.hours) to 24.hours
            else -> (DAY0 - 3000.hours) to 24.hours
        }
        return List(6) { i -> GraphV2Point(start + step * i, base + i, "x", base + i + 1, base + i - 1) }
    }

    fun long(tab: String, period: GraphPeriod, ids: List<String>) = ok(GraphV2TabResponse(
        tab = tab, period = period.code, series = ids.map { series(it, longPoints(it, period)) },
        metadata = GraphV2Metadata(B - 1.minutes, if (period == GraphPeriod.ONE_WEEK) "1h" else "1d", GraphV2Range("2026-07-05", "2026-10-05"))
    ))

    fun linesOf(points: List<GraphV2Point>) = points.map { LinePoint(it.ts, it.rate) }
}

internal class TetherFakeSelectionStore : GraphSelectionStore {
    val committed = mutableMapOf<GraphSelectionKey, GraphSeriesSelection>()
    private fun current(key: GraphSelectionKey) = committed[key]?.let {
        GraphSelectionReadResult.Present(GraphSelectionRecord(1, key.uid, key.audience, key.tab, it.visibleSeriesIds, it.initializedSeries))
    } ?: GraphSelectionReadResult.Absent
    override suspend fun confirmGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult = current(key)
    override suspend fun readGraphSelection(key: GraphSelectionKey): GraphSelectionReadResult = current(key)
    override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection): GraphSelectionWriteResult {
        committed[key] = selection
        return GraphSelectionWriteResult.Committed
    }
}

internal class TetherSent(val key: GraphKey) {
    val answer = CompletableDeferred<AuthenticatedHttpResponse<GraphV2TabResponse>>()
}

internal class TetherGraphFixture(
    val test: TestScope,
    private val folder: TemporaryFolder,
    start: Instant = TetherGraphData.B + 1.minutes,
    krxCapability: Boolean = true,
    catalogTtlSeconds: Int = 172800
) {
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val base = test.testScheduler.currentTime
    /** Starts at [start] (B + 1 min unless given). */
    val clock = AppClock { start + (test.testScheduler.currentTime - base).milliseconds }

    var catalogDto: GraphV2CatalogResponse = catalog(ttlSeconds = catalogTtlSeconds)
    val display = MutableStateFlow(TopicDisplayState.NONE.copy(owner = OWNER_A))
    val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(A, FreeTab.USD))
    val accessRevisions = MutableStateFlow(0L)
    val sent = mutableListOf<TetherSent>()
    val snapshot: TopicAccessSnapshot = run {
        val binding = EntitlementsIdentity("u1", 1L)
        val record = AccessFence("u1", "e1", "K1")
        TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = TopicAccessFacts.NONE.copy(
                token = TopicGrantToken(7L), issuedFor = TopicGrantContext(binding, record, 10L), binding = binding,
                recordFence = record, decisionGeneration = 10L, tokenStanding = true, userBlocks = emptySet(),
                capabilityBlocks = if (krxCapability) emptySet() else setOf(TopicAccessBlock.NOT_GRANTED)
            ),
            userInvalidations = 3L
        )
    }
    val uses = SnapshotTopicUseAuthority { snapshot }
    val owners = object : GraphOwnerSource {
        override fun currentIdentity(): AuthIdentityFence = A
        override suspend fun capture(expected: AuthIdentityFence): AuthSnapshot {
            if (expected != A) throw AuthIdentityChangedException()
            return AuthSnapshot(expected.uid, expected.authGeneration, "token")
        }
    }
    val gate = GraphV2AccessGate({ A }, { FENCE_A }, { snapshot }, { true })
    val store = FileGraphV2DiskStore({ folder.newFolder() }, JsonGraphV2EnvelopeCodec(), DefaultGraphV2AtomicFileIo(), dispatcher)
    val fetcher = object : GraphV2Fetching {
        override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = ok(catalogDto)
        override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2TabResponse> {
            val s = TetherSent(key)
            sent += s
            return s.answer.await()
        }
    }
    lateinit var coordinator: GraphV2RequestCoordinator
    val recorder = GraphRecorder(scope, MutableStateFlow(0L), { snapshot }, { FENCE_A }, { coordinator.state.value.catalog }, gate, clock)
    init {
        coordinator = GraphV2RequestCoordinator(
            fetcher = fetcher, owners = owners, currentAccessFence = { FENCE_A }, uses = uses, protectedAdmission = { true },
            accessSnapshot = { snapshot }, scope = scope, clock = clock, rateLimitJitter = { Duration.ZERO }, onEventFailure = {},
            cachePorts = GraphV2CachePorts(store = store, gate = gate, onSeedDiagnostic = {}), recorder = recorder
        )
    }
    val selections = TetherFakeSelectionStore()
    /** A display-side feed the recorder-backed holders must never read (S5-2b T05(c), T21(i)). */
    val live = MutableStateFlow<Map<GraphObservationSeriesKey, GraphRecoverableState>>(emptyMap())
    val holders = listOf("usd", "tether").associateWith { tab ->
        GraphV2ScreenStateHolder(
            tab = tab, coordinator = coordinator,
            selectionSession = GraphSeriesSelectionSession(selections, GraphSelectionAudience.PREMIUM, tab, scope, dispatcher),
            liveIdentity = { A }, display = display, focus = focus, currentAccessFence = { FENCE_A }, uses = uses, gate = gate,
            accessRevisions = accessRevisions, scope = scope, dispatcher = dispatcher, live = live, clock = clock, recorder = recorder
        )
    }

    /** Every published state per tab, collected from the first exposure on (S5-2b). */
    val published = mapOf("usd" to mutableListOf<GraphV2ScreenState>(), "tether" to mutableListOf())
    /** The recorder's state read when each published state was first observed, index for index with [published]. */
    val recorderAtPublication = mapOf("usd" to mutableListOf<GraphRecorderState>(), "tether" to mutableListOf())
    fun collectPublications() {
        for ((tab, holder) in holders) {
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                holder.state.collect {
                    published.getValue(tab) += it
                    recorderAtPublication.getValue(tab) += recorder.state.value
                }
            }
        }
        run()
    }

    /** The real recovery-event producer over this fixture, fanning time events out to both holders (S5-2b). */
    val events = GraphRecoveryEvents(
        scope, clock, { coordinator.state.value.source }, coordinator::onRecoveryTrigger, recorder::retain,
        { holders.values.forEach { it.onTimeEvent() } }, { null }, coordinator::onContextChanged
    )

    fun run() = test.runCurrent()
    fun advance(ms: Long) {
        test.advanceTimeBy(ms)
        test.runCurrent()
    }

    fun start() {
        coordinator.start()
        holders.values.forEach { it.start() }
        run()
    }

    /** Moves the focus to [tab] and activates its holder alone, as the route does. */
    fun activate(tab: String) {
        holders.filterKeys { it != tab }.values.forEach { it.onDeactivated() }
        focus.value = OwnedTopicFocus(A, if (tab == "usd") FreeTab.USD else FreeTab.TETHER)
        run()
        holders.getValue(tab).onActivated(OWNER_A)
        run()
    }

    fun state(tab: String) = holders.getValue(tab).currentState()
    fun token(tab: String) = checkNotNull(state(tab).inlineToken) { "no inline token for $tab in ${state(tab)}" }
    fun show(tab: String) {
        holders.getValue(tab).setSurfaceVisible(token(tab), true)
        run()
    }
    fun hide(tab: String) {
        holders.getValue(tab).setSurfaceVisible(token(tab), false)
        run()
    }
    fun prepared(tab: String) = checkNotNull(state(tab).chart) { "no chart for $tab in ${state(tab)}" }.prepared
    fun seriesOf(tab: String, id: String): PreparedSeries = prepared(tab).bySeries.getValue(id)

    fun answer(key: GraphKey, response: AuthenticatedHttpResponse<GraphV2TabResponse>) {
        sent.first { it.key == key && !it.answer.isCompleted }.answer.complete(response)
        run()
    }

    /** Fails the pending request of [key] with [code] (S5-2b T21). */
    fun fail(key: GraphKey, code: Int = 503) = answer(key, TetherGraphData.status(code))

    val lifetime get() = checkNotNull(uses.acquire(FENCE_A))
    private fun observe(topic: String, candidate: TopicGraphCandidate) {
        recorder.observe(
            TopicGraphInput.Observations(1L, topic, TopicGraphPath.WS, TopicUseAttribution(Any(), FENCE_A, 1L, lifetime), 1L, listOf(candidate))
        )
        run()
    }
    /** A USD row of [source] by `fx:usd-krw`. */
    fun fx(source: String, rate: Double, at: Instant) = observe("fx:usd-krw", TopicGraphCandidate.Quote(source, "usd-krw", rate, at, null))
    /** The same row carried by the tether topic. */
    fun tetherRow(source: String, rate: Double, at: Instant) = observe("usdt:krw", TopicGraphCandidate.Quote(source, "usd-krw", rate, at, null))
    fun exchange(source: String, rate: Double, at: Instant, changed: Instant? = at) =
        observe("usdt:krw", TopicGraphCandidate.Quote(source, "usdt-krw", rate, at, changed))
    fun dxy(rate: Double, at: Instant) = observe("dxy:spot", TopicGraphCandidate.DollarIndex(rate, at, "investing"))

    fun close() = scope.cancel()
}
