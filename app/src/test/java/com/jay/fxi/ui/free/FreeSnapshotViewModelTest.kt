package com.jay.fxi.ui.free

import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AuthUidStream
import com.jay.fxi.data.free.FreeSnapshotEntry
import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.data.local.FreeTabStore
import com.jay.fxi.data.local.FreeVisibleSeriesStore
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.SourceRate
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreeSnapshotViewModelTest {
    private val key = FreeSnapshotKey("usd", FreeSnapshotUiState.DEFAULT_PERIOD)
    private val basis = Instant.parse("2026-09-06T02:30:00Z")

    private fun snapshot(key: FreeSnapshotKey = this.key) = FreeSnapshot(
        tab = key.tab,
        period = key.period,
        asOf = basis,
        generatedAt = basis + 1.minutes,
        refreshNotBefore = basis + 48.hours,
        rate = FreeRate.Flat("usd-krw", listOf(ExchangeRate("usd-krw", "kb", 1400.0, basis - 1.hours))),
        graph = FreeGraph(null, listOf(FreeGraphSeries(
            seriesId = "investing.usd",
            points = listOf(
                FreeGraphPoint(basis - 2.hours, 1390.0, null, null),
                FreeGraphPoint(basis, 1400.0, null, null)
            )
        )))
    )

    private fun readState(
        uid: String = "u1",
        freshness: FreeSnapshotFreshness = FreeSnapshotFreshness.FRESH,
        snapshot: FreeSnapshot = snapshot()
    ) = FreeSnapshotReadState(uid, mapOf(FreeSnapshotKey(snapshot.tab, snapshot.period) to FreeSnapshotEntry(snapshot, freshness)))

    /** Answers instantly unless a test hands it a gate, which is how the pre-restore state is seen. */
    private class FakeTabStore(
        private var stored: Pair<String, FreeTab>? = null,
        private val gate: CompletableDeferred<Unit>? = null
    ) : FreeTabStore {
        val writes = mutableListOf<Pair<String, FreeTab>>()

        override suspend fun lastTab(uid: String): FreeTab {
            gate?.await()
            return stored?.takeIf { it.first == uid }?.second ?: FreeTab.INITIAL
        }

        override suspend fun remember(uid: String, tab: FreeTab) {
            stored = uid to tab
            writes += uid to tab
        }
    }

    private class FakeSeriesStore(
        private var stored: MutableMap<FreeTab, Set<String>> = mutableMapOf()
    ) : FreeVisibleSeriesStore {
        var owner: String? = null
        val writes = mutableListOf<Pair<FreeTab, Set<String>>>()

        override suspend fun visibleSeries(uid: String): Map<FreeTab, Set<String>> =
            if (owner == null || owner == uid) stored.toMap() else emptyMap()

        override suspend fun remember(uid: String, tab: FreeTab, seriesIds: Set<String>) {
            owner = uid
            stored[tab] = seriesIds
            writes += tab to seriesIds
        }
    }

    private fun withViewModel(
        tabStore: FreeTabStore = FakeTabStore(),
        seriesStore: FreeVisibleSeriesStore = FakeSeriesStore(),
        block: suspend TestScope.(FreeSnapshotViewModel, MutableStateFlow<FreeSnapshotReadState>) -> Unit
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reads = MutableStateFlow(FreeSnapshotReadState())
        val vm = FreeSnapshotViewModel(
            reads, FreeSnapshotActivityCoordinator({ _, _ -> }, {}), tabStore, seriesStore
        )
        try {
            block(vm, reads)
        } finally {
            // Cancel the actual ViewModel scope without Android/Compose test artifacts.
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun absenceIsNeutral_andBindDoesNotActivateOrFetch() = withViewModel { vm, reads ->
        vm.bind("u1")
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.AWAITING_SNAPSHOT, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.rateSections.isEmpty())
        reads.value = FreeSnapshotReadState("u1")
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.AWAITING_SNAPSHOT, vm.uiState.value.availability)
    }

    @Test
    fun ownerMismatchNeverRenders_andUidSwitchClearsSynchronously() = withViewModel { vm, reads ->
        reads.value = readState()
        vm.bind("u1")
        assertFalse(vm.uiState.value.rateSections.isEmpty())
        // Render-time fence closes the frame before the new UID's bind effect.
        assertTrue(vm.uiState.value.forOwner("u2").rateSections.isEmpty())
        vm.bind("u2")
        assertTrue(vm.uiState.value.rateSections.isEmpty())
        testScheduler.runCurrent()
        assertNull(vm.uiState.value.asOfLabel)
        reads.value = readState(uid = "u2")
        testScheduler.runCurrent()
        assertFalse(vm.uiState.value.rateSections.isEmpty())
        reads.value = readState(uid = "u1", freshness = FreeSnapshotFreshness.DELAYED)
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.AWAITING_SNAPSHOT, vm.uiState.value.availability)
    }

    @Test
    fun switchingToUncachedPeriodCannotReusePreviousChart() = withViewModel { vm, reads ->
        reads.value = readState()
        vm.bind("u1")
        val other = key.copy(period = GraphPeriod.ONE_WEEK)
        vm.selectPeriod(other.period)
        assertTrue(vm.uiState.value.charts.isEmpty())
        assertNull(vm.uiState.value.asOfLabel)
        reads.value = readState(snapshot = snapshot(other))
        testScheduler.runCurrent()
        assertEquals(other, vm.uiState.value.dataKey)
        assertFalse(vm.uiState.value.charts.isEmpty())
    }

    @Test
    fun delayedRetainsSnapshot_expiredRemovesAllValues_butKeepsBasis() = withViewModel { vm, reads ->
        reads.value = readState()
        vm.bind("u1")
        assertEquals("2026.09.06 11:30 KST", vm.uiState.value.asOfLabel)
        val fresh = vm.uiState.value
        reads.value = readState(freshness = FreeSnapshotFreshness.DELAYED)
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.DELAYED, vm.uiState.value.availability)
        assertEquals(fresh.rateSections, vm.uiState.value.rateSections)
        assertEquals(fresh.charts, vm.uiState.value.charts)
        reads.value = readState(freshness = FreeSnapshotFreshness.UNAVAILABLE)
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.UNAVAILABLE, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.rateSections.isEmpty())
        assertTrue(vm.uiState.value.charts.isEmpty())
        assertEquals(fresh.asOfLabel, vm.uiState.value.asOfLabel)
    }

    @Test
    fun emptySuccessfulPayloadIsStillFresh() = withViewModel { vm, reads ->
        reads.value = readState(snapshot = snapshot().copy(rate = FreeRate.Flat("usd-krw", emptyList()), graph = FreeGraph(null, emptyList())))
        vm.bind("u1")
        assertEquals(FreeSnapshotAvailability.FRESH, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.charts.isEmpty())
        assertTrue(vm.uiState.value.rateSections.isEmpty())
    }

    @Test
    fun chartUsesSnapshotTimeAndValues_withoutSyntheticLiveTail() = withViewModel { vm, reads ->
        reads.value = readState()
        vm.bind("u1")
        // The chart carries the snapshot's own instants and values — no tail is invented past the
        // last one, and nothing is normalised away before anyone can ask what time it was.
        val chart = vm.uiState.value.charts.single()
        assertEquals(listOf(basis - 2.hours, basis), chart.points.map { it.timestamp })
        assertEquals(listOf(1390.0, 1400.0), chart.points.map { it.rate })
        assertEquals(basis - 2.hours, chart.domain.start)
        assertEquals(basis, chart.domain.end)
        assertEquals(
            listOf(FreeSnapshotChartPoint(0f, 1f), FreeSnapshotChartPoint(1f, 0f)),
            FreeChartGeometry.normalize(chart.points, chart.domain)
        )

        val single = snapshot().let { it.copy(graph = FreeGraph(null, listOf(it.graph.series.single().copy(points = it.graph.series.single().points.take(1))))) }
        reads.value = readState(snapshot = single)
        testScheduler.runCurrent()
        val one = vm.uiState.value.charts.single()
        assertEquals(listOf(basis - 2.hours), one.points.map { it.timestamp })
        assertEquals(
            listOf(FreeSnapshotChartPoint(0.5f, 0.5f)),
            FreeChartGeometry.normalize(one.points, one.domain)
        )
    }

    /**
     * Two series on one axis are drawn in one frame — and each still describes itself.
     *
     * Reading the caption off the shared frame reported numbers the series never reached: a line
     * that moved between 1400 and 1401 was captioned, and read aloud by the screen reader, as
     * 1390–1410. The frame is for drawing; the caption is a claim about this series.
     */
    @Test
    fun aSeriesCaptionReportsItsOwnRange_notTheSharedAxis() = withViewModel { vm, reads ->
        val flat = FreeGraphSeries(
            seriesId = "hana.usd", label = "하나", axisGroup = "krw",
            points = listOf(
                FreeGraphPoint(basis - 2.hours, 1400.0, null, null),
                FreeGraphPoint(basis, 1401.0, null, null)
            )
        )
        val swingy = FreeGraphSeries(
            seriesId = "investing.usd", label = "인베스팅", axisGroup = "krw",
            points = listOf(
                FreeGraphPoint(basis - 2.hours, 1390.0, null, null),
                FreeGraphPoint(basis, 1410.0, null, null)
            )
        )
        reads.value = readState(snapshot = snapshot().copy(graph = FreeGraph(null, listOf(swingy, flat))))
        vm.bind("u1")

        val charts = vm.uiState.value.charts.associateBy { it.seriesId }
        assertEquals(setOf("investing.usd", "hana.usd"), charts.keys)
        // One frame, so the two lines are comparable…
        assertEquals(charts.getValue("hana.usd").domain, charts.getValue("investing.usd").domain)
        assertEquals(1390.0, charts.getValue("hana.usd").domain.low, 0.0)
        // …and each caption is still about its own series.
        assertEquals("1400.00", charts.getValue("hana.usd").low)
        assertEquals("1401.00", charts.getValue("hana.usd").high)
        assertEquals("1390.00", charts.getValue("investing.usd").low)
        assertEquals("1410.00", charts.getValue("investing.usd").high)
    }

    @Test
    fun schedulerFreshnessWakeExpiresScreenWithoutFetch_orUiTimer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var calls = 0
        val scheduler = FreeSnapshotScheduler(
            fetcher = object : FreeSnapshotFetching {
                override suspend fun fetch(tab: String, period: GraphPeriod): FreeSnapshot {
                    calls++
                    return snapshot(FreeSnapshotKey(tab, period))
                }
            },
            uidStream = AuthUidStream { it("u1") },
            authFence = { AuthIdentityFence("u1", 1L) },
            scope = scope,
            clock = { basis + testScheduler.currentTime.milliseconds },
            installId = { "test-install" }
        )
        val vm = FreeSnapshotViewModel(scheduler, FakeTabStore(), FakeSeriesStore())
        try {
            scheduler.start()
            vm.bind("u1")
            vm.activityCoordinator.update("u1", key.tab, key.period, true, true)
            testScheduler.runCurrent()
            val initialCalls = calls
            assertEquals(FreeSnapshotAvailability.FRESH, vm.uiState.value.availability)
            testScheduler.advanceTimeBy(2.hours.inWholeMilliseconds)
            testScheduler.runCurrent()
            assertEquals(FreeSnapshotAvailability.DELAYED, vm.uiState.value.availability)
            testScheduler.advanceTimeBy(22.hours.inWholeMilliseconds)
            testScheduler.runCurrent()
            assertEquals(FreeSnapshotAvailability.UNAVAILABLE, vm.uiState.value.availability)
            assertTrue(vm.uiState.value.charts.isEmpty())
            assertEquals(initialCalls, calls)
        } finally {
            vm.viewModelScope.cancel()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * `ANDROID_V2_PLAN.md:830` — the row opens where this UID left it, and on 달러 for anyone else.
     * The owner is stored beside the value precisely so a second account cannot inherit the first
     * one's tab.
     */
    @Test
    fun theStoredTabIsRestoredForItsOwnerOnly() = withViewModel(FakeTabStore("u1" to FreeTab.TETHER)) { vm, _ ->
        vm.bind("u1")
        assertEquals(FreeTab.TETHER, vm.uiState.value.selectedTab)
        assertEquals(FreeSnapshotKey("tether", FreeSnapshotUiState.DEFAULT_PERIOD), vm.uiState.value.dataKey)
        vm.bind("u2")
        assertEquals(FreeTab.USD, vm.uiState.value.selectedTab)
    }

    /**
     * Publishing 달러 while the stored tab is still being read would activate 달러 on the way to
     * 테더 — one wasted request against the free endpoint on every cold start. Nothing is selected,
     * and so nothing is activated, until the answer lands.
     */
    @Test
    fun noTabIsSelectedUntilTheStoreAnswers() {
        val gate = CompletableDeferred<Unit>()
        withViewModel(FakeTabStore("u1" to FreeTab.TETHER, gate)) { vm, _ ->
            val bound = launch { vm.bind("u1") }
            testScheduler.runCurrent()
            assertNull(vm.uiState.value.selectedTab)
            assertNull("a request was authorised before the selection was known", vm.uiState.value.dataKey)
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(FreeTab.TETHER, vm.uiState.value.selectedTab)
            bound.join()
        }
    }

    /**
     * The surface's pager reports whatever page it is anchored on, and it is built before the
     * restore lands. A report that arrived first must not become the selection, and above all must
     * not be written to the store — that would destroy the tab being restored at that very moment.
     */
    @Test
    fun aSelectionArrivingBeforeTheRestoreCannotSurviveIt() {
        val gate = CompletableDeferred<Unit>()
        val store = FakeTabStore("u1" to FreeTab.TETHER, gate)
        withViewModel(store) { vm, _ ->
            val bound = launch { vm.bind("u1") }
            testScheduler.runCurrent()
            vm.selectTab(FreeTab.USD)
            testScheduler.runCurrent()
            assertNull(vm.uiState.value.selectedTab)
            assertTrue("a stray selection was persisted with no owner", store.writes.isEmpty())
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(FreeTab.TETHER, vm.uiState.value.selectedTab)
            assertTrue(store.writes.isEmpty())
            bound.join()
        }
    }

    @Test
    fun selectingATabPersistsIt_andNewsAuthorisesNothing() {
        val store = FakeTabStore()
        withViewModel(store) { vm, _ ->
            vm.bind("u1")
            vm.selectTab(FreeTab.NEWS)
            testScheduler.runCurrent()
            assertEquals(FreeTab.NEWS, vm.uiState.value.selectedTab)
            assertNull("뉴스 is not snapshot data", vm.uiState.value.dataKey)
            assertEquals(listOf("u1" to FreeTab.NEWS), store.writes)
            // Reselecting the same tab is not a change and must not rewrite the store.
            vm.selectTab(FreeTab.NEWS)
            testScheduler.runCurrent()
            assertEquals(1, store.writes.size)
        }
    }

    /** Nothing is bound yet, so a selection has no owner to be stored against. */
    @Test
    fun selectionsBeforeBindAreIgnored() {
        val store = FakeTabStore()
        withViewModel(store) { vm, _ ->
            vm.selectTab(FreeTab.EUR)
            vm.selectPeriod(GraphPeriod.ONE_YEAR)
            testScheduler.runCurrent()
            assertNull(vm.uiState.value.selectedTab)
            assertTrue(store.writes.isEmpty())
        }
    }

    /**
     * The tether tab's rate block is three groups, not a flat list with extras: exchanges, banks
     * and one reference quote mean different things beside each other. Flattening them would put
     * 업비트 and 하나은행 in one column under one heading.
     */
    @Test
    fun tetherKeepsItsThreeGroupsApart_andDropsEmptyOnes() = withViewModel(FakeTabStore("u1" to FreeTab.TETHER)) { vm, reads ->
        val tether = FreeSnapshotKey("tether", FreeSnapshotUiState.DEFAULT_PERIOD)
        val grouped = snapshot(tether).copy(
            rate = FreeRate.Grouped(
                primaryAsset = "usdt-krw",
                usdtKrw = listOf(SourceRate("upbit", "usdt-krw", 1401.0, basis)),
                usdKrwBanks = listOf(ExchangeRate("usd-krw", "hana", 1400.0, basis)),
                usdKrwReference = null
            )
        )
        reads.value = readState(snapshot = grouped)
        vm.bind("u1")
        assertEquals(
            listOf("거래소 USDT/KRW" to "업비트", "은행 USD/KRW" to "하나은행"),
            vm.uiState.value.rateSections.map { it.title to it.rows.single().source }
        )
    }

    /**
     * `ANDROID_V2_PLAN.md:826`. The defaults match iOS's `FreeCurrencyConfig`. Binding alone must
     * not persist the resolved default; the first toggle persists the resulting selection,
     * including an empty set when everything is off.
     */
    @Test
    fun graphSeriesStartOnTheTabsDefault_andOnlyRealChangesArePersisted() {
        val series = FakeSeriesStore()
        withViewModel(seriesStore = series) { vm, reads ->
            reads.value = readState()
            vm.bind("u1")
            assertEquals(
                listOf("investing.usd" to true),
                vm.uiState.value.seriesToggles.map { it.seriesId to it.visible }
            )
            assertFalse(vm.uiState.value.charts.isEmpty())
            assertTrue("the default was written back as if it were a choice", series.writes.isEmpty())

            // The default carries four ids; this answer only carries one of them, so hiding it
            // empties the graph while the other three stay chosen.
            vm.toggleSeries("investing.usd")
            testScheduler.runCurrent()
            assertTrue(vm.uiState.value.charts.isEmpty())
            assertEquals(
                listOf(FreeTab.USD to setOf("kb.usd", "hana.usd", "dxy")),
                series.writes
            )
        }
    }

    /**
     * All-off is a choice the plan permits, so it has to be storable *and* distinguishable from
     * "never chosen" on the way back in — an empty set that read as absence would reopen on the
     * default every launch.
     */
    @Test
    fun turningEverySeriesOffIsStored_andSurvivesARebind() {
        val series = FakeSeriesStore()
        withViewModel(seriesStore = series) { vm, reads ->
            reads.value = readState()
            vm.bind("u1")
            FreeTab.USD.defaultVisibleSeriesIds.forEach { vm.toggleSeries(it) }
            testScheduler.runCurrent()
            assertEquals(emptySet<String>(), series.writes.last().second)

            vm.bind("u2")
            vm.bind("u1")
            reads.value = readState()
            testScheduler.runCurrent()
            assertEquals(
                listOf("investing.usd" to false),
                vm.uiState.value.seriesToggles.map { it.seriesId to it.visible }
            )
            assertTrue("all-off did not survive the rebind", vm.uiState.value.charts.isEmpty())
        }
    }

    /** A toggle before anything is bound has no owner to be stored against. */
    @Test
    fun seriesTogglesBeforeBindAreIgnored() {
        val series = FakeSeriesStore()
        withViewModel(seriesStore = series) { vm, _ ->
            vm.toggleSeries("investing.usd")
            testScheduler.runCurrent()
            assertTrue(series.writes.isEmpty())
        }
    }

    /** 뉴스 has no graph, so there is nothing on it to show or hide. */
    @Test
    fun theNewsTabHasNoSeriesToToggle() {
        val series = FakeSeriesStore()
        withViewModel(seriesStore = series) { vm, _ ->
            vm.bind("u1")
            vm.selectTab(FreeTab.NEWS)
            vm.toggleSeries("investing.usd")
            testScheduler.runCurrent()
            assertTrue(series.writes.isEmpty())
            assertTrue(vm.uiState.value.seriesToggles.isEmpty())
        }
    }
}
