package com.jay.fxi.ui.premium.graph

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.auth.AuthSnapshot
import com.jay.fxi.data.entitlements.TopicAccessSnapshot
import com.jay.fxi.data.graph.GraphKey
import com.jay.fxi.data.graph.GraphOwnerSource
import com.jay.fxi.data.graph.GraphRuntimeAssembly
import com.jay.fxi.data.graph.GraphSeriesSelectionSession
import com.jay.fxi.data.graph.GraphV2Fetching
import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionStore
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.data.remote.AuthenticatedHttpResponse
import com.jay.fxi.data.remote.OwnedTopicFocus
import com.jay.fxi.data.remote.TopicConnectionDisplay
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.remote.TopicDisplayState
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.data.remote.TopicUseAuthority
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
import com.jay.fxi.domain.model.TopicQuote
import com.jay.fxi.domain.model.TopicRates
import com.jay.fxi.time.SystemAppClock
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S5 A3 C1 contract r1 (instrumented, real holder): the surfaces of a real premium FX graph holder keep its
 * visibility through a period change whose new token is not measured yet, end it with false when that token stops or leaves
 * before its first measurement - cancelling the live publication and dropping the right edge - and, on every resume, give
 * the holder no visibility before the new surface's first measurement.
 *
 * Oracles: A3/impl/design.r3.md D1 (the closing observer reports (token, false) on ON_STOP and dispose before the first
 * measurement; an exposed surface omits only its initial start report; the holder keeps what it knows: a period change keeps
 * visibility, every other path that changes the surface's meaning clears the reports - selection.r3 section 2's holder
 * evidence) and D2 (with visibility kept, the new key is published at once), rows P05 (the real-holder STOP and removal cases:
 * the live publication is cancelled and rightEdgeNow is null) and P06 (B05's resume paths with a real surface and holder,
 * before and after the first measurement) of design.r3's "계약 시험", as Codex's design review r1 (A3/impl/codex_design.r1.txt
 * items 1 and 4) worded them. Selection: A3/step1/selection.r3.md sections 1-2. Cancellation is observed through rightEdgeNow
 * (render draws the right edge only from the kept live publication) and through no pending publication; no 350 ms deadline
 * is created before the stop. The implementation must keep the private holder fields livePublishJob, inlineVisible and
 * fullscreenVisible, read here by reflection.
 *
 * Harness: PremiumTopicRouteGraphTest's real host over a real, started GraphRuntimeAssembly with its answering fetcher (a
 * catalog of every period, immediate answers), cached disk ports, admitted access snapshot and absent selection store,
 * copied with every named argument; the usd holder comes from a mount the test opens on Main and is activated for the
 * display owner as the route's effect would. The route itself is not composed - its pager composes a page only while it
 * measures, so a held first measurement could not be expressed there. Instead the test composes the slot's two surfaces as
 * PremiumTopicScreen does (state observed for invalidation, the holder read fresh in composition, the six actions bound to
 * the holder) with every visibility report also recorded, and the inline surface's hostExposed as the screen passes it
 * (false while the fullscreen overlay is open).
 *
 * Withholding the first measurement: as in GraphV2PeriodSwitchVisibilityTest, each surface sits in a SubcomposeLayout
 * ([Held]) that always subcomposes it but measures and places it only while its `placed` flag is true; onGloballyPositioned
 * reaches placed nodes only. The token change (selectPeriod, a context or tab return, a fullscreen reopen) and clearing
 * `placed` happen in one main-thread block, and a counting onGloballyPositioned on the surface's own modifier proves as a
 * premise that nothing was measured in between. The lifecycle is a LifecycleRegistry driven on the main thread. Holder
 * fields are read by reflection on Main, as PremiumTopicRouteGraphTest reads them. The implementation thread reads but does
 * not edit this file.
 */
class GraphV2PeriodSwitchHolderIntegrationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val t = Instant.parse("2026-10-01T01:00:00Z")
    private val a1 = AuthIdentityFence("A", 1L)
    private val o1 = TopicDisplayOwner(a1, 1L)
    private val fence = TopicSessionFence(a1, "e1", com.jay.fxi.data.remote.TopicGrantToken(1L))
    private fun rates() = TopicRates().merge(listOf(TopicQuote("kb", "usd-krw", 1390.0, t), TopicQuote("upbit", "usdt-krw", 1400.0, t)))

    private val display = MutableStateFlow(TopicDisplayState(o1, rates(), false, TopicConnectionDisplay.OPEN))
    private val focus = MutableStateFlow<OwnedTopicFocus?>(OwnedTopicFocus(a1, FreeTab.USD))
    @Volatile private var live: AuthIdentityFence = a1
    private val hostScopes = mutableListOf<CoroutineScope>()
    private val cacheRoots = java.util.Collections.synchronizedList(mutableListOf<java.io.File>())
    private var mount: GraphScreenMount? = null
    @After fun tearDown() {
        runCatching { rule.runOnUiThread { mount?.close() } }
        hostScopes.forEach { it.cancel() }
        cacheRoots.forEach { it.deleteRecursively() }
    }

    private val selections = object : GraphSelectionStore {
        override suspend fun readGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
        override suspend fun writeGraphSelection(key: GraphSelectionKey, selection: GraphSeriesSelection) = GraphSelectionWriteResult.Committed
        override suspend fun confirmGraphSelection(key: GraphSelectionKey) = GraphSelectionReadResult.Absent
    }

    /** The issuer's published access for [fence]: it admits A's grant 1 in user epoch e1, so a holder can take its context. */
    @Volatile private var snapshot: TopicAccessSnapshot = run {
        val binding = com.jay.fxi.data.entitlements.EntitlementsIdentity("A", 1L)
        val record = com.jay.fxi.data.entitlements.AccessFence("A", "e1", "K1")
        TopicAccessSnapshot.INITIAL.copy(
            revision = 100L,
            facts = com.jay.fxi.data.entitlements.TopicAccessFacts.NONE.copy(
                token = fence.grant,
                issuedFor = com.jay.fxi.data.entitlements.TopicGrantContext(binding, record, 10L),
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

    private val coordinators = java.util.Collections.synchronizedList(mutableListOf<com.jay.fxi.data.graph.GraphV2RequestCoordinator>())
    private val silent = object : GraphV2Fetching {
        override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = awaitCancellation()
        override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean) = awaitCancellation()
    }

    private fun newHost(
        built: MutableList<GraphV2ScreenStateHolder>,
        fetcher: GraphV2Fetching = silent,
        store: GraphSelectionStore = selections,
        cached: Boolean = false
    ): GraphScreenHost {
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { hostScopes += it }
        val authority: TopicUseAuthority = com.jay.fxi.data.entitlements.SnapshotTopicUseAuthority { snapshot }
        val assembly = GraphRuntimeAssembly(
            accessSnapshot = { snapshot },
            accessRevisions = MutableStateFlow(0L),
            liveIdentity = { a1 },
            uses = authority,
            protectedAdmission = { true },
            fetcher = fetcher,
            owners = object : GraphOwnerSource {
                override fun currentIdentity(): AuthIdentityFence = a1
                override suspend fun capture(expected: AuthIdentityFence) = AuthSnapshot(expected.uid, expected.authGeneration, "token")
            },
            // Exposure reads the protected publication, which needs cache ports; an empty disk root under the test's cache.
            cachePorts = if (cached) { gate ->
                val root = java.io.File(rule.activity.cacheDir, "route-graph-${java.util.UUID.randomUUID()}").also { cacheRoots += it }
                com.jay.fxi.data.graph.GraphV2CachePorts(
                    store = com.jay.fxi.data.graph.FileGraphV2DiskStore({ root }, com.jay.fxi.data.graph.JsonGraphV2EnvelopeCodec(),
                        com.jay.fxi.data.graph.DefaultGraphV2AtomicFileIo(), Dispatchers.IO),
                    gate = gate,
                    onSeedDiagnostic = { }
                )
            } else { _ -> null },
            main = Dispatchers.Main,
            parent = Job(hostScope.coroutineContext[Job]),
            clock = SystemAppClock,
            rateLimitJitter = { Duration.ZERO },
            onEventFailure = { }
        )
        assembly.fences.setAccess(true, fence, com.jay.fxi.data.remote.TopicGrantOrigin.NewContext)
        rule.runOnUiThread { assembly.start() }
        coordinators += assembly.coordinator
        return GraphScreenHost(hostScope, { tab ->
            val session = GraphSeriesSelectionSession(store, GraphSelectionAudience.PREMIUM, tab, hostScope, Dispatchers.Main)
            GraphV2ScreenStateHolder(tab, assembly.coordinator, session, { live }, display, focus, assembly.fences.current, authority,
                assembly.gate, MutableStateFlow(0L), hostScope, Dispatchers.Main, recorder = assembly.recorder).also { built += it }
        }, { })
    }

    private fun GraphV2ScreenStateHolder.field(name: String): Any? =
        GraphV2ScreenStateHolder::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun <T> onMain(read: () -> T): T {
        var result: Result<T>? = null
        rule.runOnUiThread { result = runCatching(read) }
        return checkNotNull(result).getOrThrow()
    }

    private fun newBuilt() = java.util.Collections.synchronizedList(mutableListOf<GraphV2ScreenStateHolder>())

    private fun until(label: String, condition: () -> Boolean) = rule.waitUntil(5_000) { condition() }.also {
        assertTrue(label, condition())
    }

    /** Answers at once: a catalog of two KRW series per FX tab and period (only [x] shown by default) and their data. */
    private fun answering(): GraphV2Fetching = object : GraphV2Fetching {
        private fun <T> ok(body: T) = AuthenticatedHttpResponse(200, okhttp3.Headers.headersOf(), body, null, byteArrayOf(1))
        override suspend fun catalog(owner: AuthSnapshot, useAdmitted: () -> Boolean) = ok(GraphV2CatalogResponse(
            tabs = listOf("usd", "jpy", "eur", "tether").map { tab ->
                GraphV2CatalogTab(tab, tab, emptyMap(), GraphPeriod.entries.associate {
                    it.code to GraphV2CatalogPeriod(listOf(x(tab), y(tab)), listOf(x(tab)))
                })
            },
            version = "2026-05-27",
            supportedPeriods = listOf("1w", "3m", "1y"),
            cacheTtlSeconds = 172800
        ))
        override suspend fun tab(owner: AuthSnapshot, key: GraphKey, useAdmitted: () -> Boolean): AuthenticatedHttpResponse<GraphV2TabResponse> {
            val now = SystemAppClock.now()
            val day = kotlin.time.Duration.parse("1d")
            val hour = kotlin.time.Duration.parse("1h")
            val points = listOf(GraphV2Point(now - hour - hour, 1390.0, "x"), GraphV2Point(now - hour, 1391.0, "x"))
            return ok(GraphV2TabResponse(
                tab = key.tab,
                period = key.period.code,
                series = listOf(x(key.tab), y(key.tab)).map { id ->
                    GraphV2Series(id, id, "krw", "KRW", 2, points, GraphV2Provenance(false, emptyList()), null)
                },
                metadata = GraphV2Metadata(
                    now, if (key.period == GraphPeriod.ONE_DAY) "10min" else "1d",
                    GraphV2Range((now - day * 400).toString().take(10), now.toString().take(10))
                )
            ))
        }
    }

    private fun x(tab: String) = "investing.$tab-krw"
    private fun y(tab: String) = "kb.$tab-krw"

    // --- the surfaces over the real holder ------------------------------------------------------------------------------

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val life = TestOwner()
    private var inlinePlaced by mutableStateOf(true)
    private var fullPlaced by mutableStateOf(true)
    private var inlineMounted by mutableStateOf(true)
    private var fullMounted by mutableStateOf(true)
    private var inlineExposed by mutableStateOf(true)
    private val inlinePositions = AtomicInteger()
    private val fullPositions = AtomicInteger()
    private val reports: MutableList<Pair<GraphV2UiToken, Boolean>> = Collections.synchronizedList(mutableListOf())

    private fun last(token: GraphV2UiToken): Boolean? = synchronized(reports) { reports.lastOrNull { it.first == token }?.second }
    private fun shows(token: GraphV2UiToken): Int = synchronized(reports) { reports.count { it.first == token && it.second } }

    /** Always subcomposes [content]; measures and places it only while [placed]. Unplaced content gets no onGloballyPositioned. */
    @Composable
    private fun Held(placed: Boolean, content: @Composable () -> Unit) {
        SubcomposeLayout(Modifier.fillMaxWidth().height(160.dp)) { constraints ->
            val measurables = subcompose(Unit, content)
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = if (placed) measurables.map { it.measure(loose) } else emptyList()
            layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
        }
    }

    /** Opens a mount on a host over an answering assembly, activates its usd holder for o1, and draws both surfaces. */
    private fun openShown(): GraphV2ScreenStateHolder {
        val host = newHost(newBuilt(), answering(), selections, cached = true)
        rule.runOnUiThread { mount = host.open() }
        until("premise: the usd holder is mounted") { onMain { mount?.holders?.value?.get("usd") != null } }
        val h = onMain { checkNotNull(mount?.holders?.value?.get("usd")) }
        rule.runOnUiThread {
            life.registry.currentState = Lifecycle.State.RESUMED
            h.onActivated(o1)
        }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides life) {
                val observed by h.state.collectAsState()
                val graph = observed.let { h.currentState() }
                val actions = remember(h) {
                    GraphV2UiActions(
                        selectPeriod = h::selectPeriod,
                        toggleSeries = h::toggleSeries,
                        enterFullscreen = h::enterFullscreen,
                        exitFullscreen = h::exitFullscreen,
                        retrySelection = h::retrySelection,
                        setSurfaceVisible = { token, shown ->
                            reports += token to shown
                            h.setSurfaceVisible(token, shown)
                        }
                    )
                }
                Column {
                    Held(inlinePlaced) {
                        if (inlineMounted) {
                            GraphV2Section(graph, actions, Modifier.onGloballyPositioned { inlinePositions.incrementAndGet() },
                                hostExposed = inlineExposed)
                        }
                    }
                    Held(fullPlaced) {
                        if (fullMounted) {
                            GraphV2Fullscreen(graph, actions, Modifier.onGloballyPositioned { fullPositions.incrementAndGet() })
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return h
    }

    private fun GraphV2ScreenStateHolder.edge(): Instant? = onMain { currentState().chart?.rightEdgeNow }
    private fun GraphV2ScreenStateHolder.inline(): GraphV2UiToken = onMain { checkNotNull(currentState().inlineToken) { "no inline token" } }
    private fun GraphV2ScreenStateHolder.fullscreen(): GraphV2UiToken? = onMain { currentState().fullscreenToken }
    private fun GraphV2ScreenStateHolder.flag(name: String): Boolean = onMain { field(name) as Boolean }

    /** The holder publishes nothing live: no pending publication, no published result, no right edge. */
    private fun assertNothingLive(label: String, h: GraphV2ScreenStateHolder) {
        assertNull("$label: no live publication is pending", onMain { h.field("livePublishJob") })
        assertNull("$label: rightEdgeNow is null", h.edge())
        assertTrue("$label premise: a chart is still drawn", onMain { h.currentState().chart != null })
    }

    private fun publishedOn(h: GraphV2ScreenStateHolder, period: GraphPeriod): Boolean = onMain {
        h.currentState().let { it.activePeriod == period && it.chart?.rightEdgeNow != null }
    }

    /** Opens the fullscreen over the shown inline surface, which the screen then marks host-hidden, and waits until it shows. */
    private fun openFullscreen(h: GraphV2ScreenStateHolder): GraphV2UiToken {
        val inline = h.inline()
        rule.runOnUiThread {
            inlineExposed = false
            h.enterFullscreen(inline)
        }
        rule.waitForIdle()
        val full = checkNotNull(h.fullscreen()) { "premise: the fullscreen is open" }
        until("premise: the fullscreen shows the live chart") { h.flag("fullscreenVisible") && h.edge() != null }
        return full
    }

    /**
     * Changes the period through [token] with [hold] cleared in the same main-thread block and waits, as a premise, for the
     * new key's live publication through the still unmeasured new token. On HEAD the new token's start report is false, so
     * this premise is where HEAD fails.
     */
    private fun heldPeriodChange(h: GraphV2ScreenStateHolder, token: GraphV2UiToken, hold: () -> Unit, positions: AtomicInteger) {
        val before = positions.get()
        rule.runOnUiThread {
            hold()
            h.selectPeriod(token, GraphPeriod.THREE_MONTHS)
        }
        rule.waitForIdle()
        until("premise (design D1+D2): the period change kept the holder's visibility and published 3m through the unmeasured token") {
            publishedOn(h, GraphPeriod.THREE_MONTHS)
        }
        assertEquals("premise: the new token was not measured", before, positions.get())
    }

    // --- P05 with the real holder --------------------------------------------------------------------------------------

    /** P05 (real holder, inline, STOP): the unmeasured new inline token ends with false on stop; nothing stays live. */
    @Test fun P05_realHolder_inlineStopBeforeTheFirstMeasurement() {
        val h = openShown()
        until("premise: 1d is published to the shown inline surface") { publishedOn(h, GraphPeriod.ONE_DAY) }
        val old = h.inline()
        heldPeriodChange(h, old, { inlinePlaced = false }, inlinePositions)
        val next = h.inline()
        assertNotEquals("premise: a new token", old, next)
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        assertEquals("the stop ends the new token with false", false, last(next))
        assertEquals("the new token was never shown", 0, shows(next))
        assertTrue("the holder's inline report is off", !h.flag("inlineVisible"))
        assertNothingLive("inline stop", h)
    }

    /** P05 (real holder, inline, removal): the unmeasured new inline token ends with false on removal; nothing stays live. */
    @Test fun P05_realHolder_inlineRemovalBeforeTheFirstMeasurement() {
        val h = openShown()
        until("premise: 1d is published to the shown inline surface") { publishedOn(h, GraphPeriod.ONE_DAY) }
        val old = h.inline()
        heldPeriodChange(h, old, { inlinePlaced = false }, inlinePositions)
        val next = h.inline()
        rule.runOnUiThread { inlineMounted = false }
        rule.waitForIdle()
        assertEquals("the removal ends the new token with false", false, last(next))
        assertEquals("the new token was never shown", 0, shows(next))
        assertTrue("the holder's inline report is off", !h.flag("inlineVisible"))
        assertNothingLive("inline removal", h)
    }

    /** P05 (real holder, fullscreen, STOP): the unmeasured new fullscreen token ends with false on stop; nothing stays live. */
    @Test fun P05_realHolder_fullscreenStopBeforeTheFirstMeasurement() {
        val h = openShown()
        until("premise: 1d is published to the shown inline surface") { publishedOn(h, GraphPeriod.ONE_DAY) }
        val old = openFullscreen(h)
        heldPeriodChange(h, old, { fullPlaced = false }, fullPositions)
        val next = checkNotNull(h.fullscreen()) { "premise: the fullscreen stays open" }
        assertNotEquals("premise: a new fullscreen token", old, next)
        rule.runOnUiThread { life.registry.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        assertEquals("the stop ends the new fullscreen token with false", false, last(next))
        assertEquals("the new fullscreen token was never shown", 0, shows(next))
        assertTrue("no report is on", !h.flag("fullscreenVisible") && !h.flag("inlineVisible"))
        assertNothingLive("fullscreen stop", h)
    }

    /** P05 (real holder, fullscreen, removal): the unmeasured new fullscreen token ends with false on removal. */
    @Test fun P05_realHolder_fullscreenRemovalBeforeTheFirstMeasurement() {
        val h = openShown()
        until("premise: 1d is published to the shown inline surface") { publishedOn(h, GraphPeriod.ONE_DAY) }
        val old = openFullscreen(h)
        heldPeriodChange(h, old, { fullPlaced = false }, fullPositions)
        val next = checkNotNull(h.fullscreen()) { "premise: the fullscreen stays open" }
        rule.runOnUiThread { fullMounted = false }
        rule.waitForIdle()
        assertEquals("the removal ends the new fullscreen token with false", false, last(next))
        assertEquals("the new fullscreen token was never shown", 0, shows(next))
        assertTrue("no report is on", !h.flag("fullscreenVisible") && !h.flag("inlineVisible"))
        assertNothingLive("fullscreen removal", h)
    }

    // --- P06: B05's resumes with the real surface and holder ----------------------------------------------------------------

    /** Before the new surface's first measurement the holder is hidden and the old token's report is refused; after it, shown. */
    private fun assertResume(label: String, h: GraphV2ScreenStateHolder, old: GraphV2UiToken, next: GraphV2UiToken,
                             flag: String, release: () -> Unit) {
        assertNotEquals("$label premise: a new token", old, next)
        assertEquals("$label: no true before the first measurement", 0, shows(next))
        assertTrue("$label: the holder is not shown before the first measurement", !h.flag(flag))
        assertNull("$label: nothing published before the first measurement", h.edge())
        rule.runOnUiThread { h.setSurfaceVisible(old, true) }
        rule.waitForIdle()
        assertTrue("$label: the old token's report is refused", !h.flag(flag))
        assertNull("$label: still nothing published", h.edge())
        rule.runOnUiThread { release() }
        rule.waitForIdle()
        until("$label: shown once measured") { last(next) == true && h.flag(flag) && h.edge() != null }
    }

    /** P06 (real holder, context return): a deactivation and reactivation start the new inline token hidden until measured. */
    @Test fun P06_realHolder_aContextReturnWaitsForTheFirstMeasurement() {
        val h = openShown()
        until("premise: 1d is published to the shown inline surface") { publishedOn(h, GraphPeriod.ONE_DAY) }
        val old = h.inline()
        val before = inlinePositions.get()
        rule.runOnUiThread {
            inlinePlaced = false
            h.onDeactivated()
            h.onActivated(o1)
        }
        rule.waitForIdle()
        until("premise: the returned context draws a chart") { onMain { h.currentState().chart != null } }
        assertEquals("premise: not measured since the return", before, inlinePositions.get())
        assertResume("context", h, old, h.inline(), "inlineVisible") { inlinePlaced = true }
    }

    /** P06 (real holder, tab leave and return): the returned inline surface starts hidden until measured. */
    @Test fun P06_realHolder_aTabReturnWaitsForTheFirstMeasurement() {
        val h = openShown()
        until("premise: 1d is published to the shown inline surface") { publishedOn(h, GraphPeriod.ONE_DAY) }
        val old = h.inline()
        rule.runOnUiThread {
            inlinePlaced = false
            inlineMounted = false
            focus.value = OwnedTopicFocus(a1, FreeTab.JPY)
            h.onDeactivated()
        }
        rule.waitForIdle()
        assertEquals("the left tab's token ended with false", false, last(old))
        val before = inlinePositions.get()
        rule.runOnUiThread {
            focus.value = OwnedTopicFocus(a1, FreeTab.USD)
            h.onActivated(o1)
            inlineMounted = true
        }
        rule.waitForIdle()
        until("premise: the returned tab draws a chart") { onMain { h.currentState().chart != null } }
        assertEquals("premise: not measured since the return", before, inlinePositions.get())
        assertResume("tab", h, old, h.inline(), "inlineVisible") { inlinePlaced = true }
    }

    /**
     * P06 (real holder, fullscreen reopened with the inline surface hidden): closing the fullscreen leaves nothing shown, and
     * the reopened fullscreen starts hidden until measured.
     */
    @Test fun P06_realHolder_aReopenedFullscreenWaitsForTheFirstMeasurement() {
        val h = openShown()
        until("premise: 1d is published to the shown inline surface") { publishedOn(h, GraphPeriod.ONE_DAY) }
        val old = openFullscreen(h)
        rule.runOnUiThread { h.exitFullscreen(old) }
        rule.waitForIdle()
        assertEquals("the closed fullscreen's token ended with false", false, last(old))
        assertTrue("premise: the inline surface is host-hidden", !h.flag("inlineVisible"))
        assertNull("closing with the inline surface hidden publishes nothing", h.edge())
        val before = fullPositions.get()
        rule.runOnUiThread {
            fullPlaced = false
            h.enterFullscreen(checkNotNull(h.currentState().inlineToken))
        }
        rule.waitForIdle()
        val next = checkNotNull(h.fullscreen()) { "premise: reopened" }
        assertEquals("premise: not measured since the reopening", before, fullPositions.get())
        assertResume("fullscreen", h, old, next, "fullscreenVisible") { fullPlaced = true }
    }
}
