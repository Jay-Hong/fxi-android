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
import com.jay.fxi.data.local.RateRowPreferenceStore
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.SourceRate
import com.jay.fxi.ui.graph.LinePoint
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

    /** Same shape as the real one: a different owner sees nothing, and writing claims the file. */
    private class FakeRowStore(
        private val stored: MutableMap<RateRowList, RateRowPreference> = mutableMapOf()
    ) : RateRowPreferenceStore {
        var owner: String? = null
        val writes = mutableListOf<Pair<RateRowList, RateRowPreference>>()

        override suspend fun preferences(uid: String): Map<RateRowList, RateRowPreference> =
            if (owner == null || owner == uid) stored.toMap() else emptyMap()

        override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) {
            owner = uid
            stored[list] = preference
            writes += list to preference
        }
    }

    private fun withViewModel(
        tabStore: FreeTabStore = FakeTabStore(),
        seriesStore: FreeVisibleSeriesStore = FakeSeriesStore(),
        rowStore: RateRowPreferenceStore = FakeRowStore(),
        block: suspend TestScope.(FreeSnapshotViewModel, MutableStateFlow<FreeSnapshotReadState>) -> Unit
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reads = MutableStateFlow(FreeSnapshotReadState())
        val vm = FreeSnapshotViewModel(
            reads, FreeSnapshotActivityCoordinator({ _, _ -> }, {}), tabStore, seriesStore, rowStore
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
        assertNull(vm.uiState.value.graph)
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
        assertNull("the previous period's graph was reused", vm.uiState.value.graph)
        assertNull(vm.uiState.value.asOfLabel)
        reads.value = readState(snapshot = snapshot(other))
        testScheduler.runCurrent()
        assertEquals(other, vm.uiState.value.dataKey)
        assertEquals(listOf("investing.usd"), vm.uiState.value.drawnSeriesIds)
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
        assertEquals(fresh.graph, vm.uiState.value.graph)
        reads.value = readState(freshness = FreeSnapshotFreshness.UNAVAILABLE)
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.UNAVAILABLE, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.rateSections.isEmpty())
        assertNull(vm.uiState.value.graph)
        assertEquals(fresh.asOfLabel, vm.uiState.value.asOfLabel)
    }

    @Test
    fun emptySuccessfulPayloadIsStillFresh() = withViewModel { vm, reads ->
        reads.value = readState(snapshot = snapshot().copy(rate = FreeRate.Flat("usd-krw", emptyList()), graph = FreeGraph(null, emptyList())))
        vm.bind("u1")
        assertEquals(FreeSnapshotAvailability.FRESH, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.drawnSeriesIds.isEmpty())
        assertTrue(vm.uiState.value.rateSections.isEmpty())
    }

    @Test
    fun chartUsesSnapshotTimeAndValues_withoutSyntheticLiveTail() = withViewModel { vm, reads ->
        reads.value = readState()
        vm.bind("u1")
        // Nothing is invented *past* the last reading — 1일 never holds out to the right edge.
        val points = vm.uiState.value.pointsOf("investing.usd")
        assertEquals("a tail was invented past the last reading", basis, points.last().ts)
        assertEquals(1400.0, points.last().rate, 0.0)
        assertEquals(basis - 2.hours, points.first().ts)
        // Between them there *is* a hold: the 1일 bucket is ten minutes, so two readings two hours
        // apart are a gap, and a gap is held flat and then stepped rather than drawn through.
        assertEquals(listOf(1390.0, 1390.0, 1400.0), points.map { it.rate })
        assertEquals(basis - 10.minutes, points[1].ts)

        val single = snapshot().let { it.copy(graph = FreeGraph(null, listOf(it.graph.series.single().copy(points = it.graph.series.single().points.take(1))))) }
        reads.value = readState(snapshot = single)
        testScheduler.runCurrent()
        assertEquals(listOf(basis - 2.hours), vm.uiState.value.pointsOf("investing.usd").map { it.ts })
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
        val vm = FreeSnapshotViewModel(scheduler, FakeTabStore(), FakeSeriesStore(), FakeRowStore())
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
            assertTrue(vm.uiState.value.drawnSeriesIds.isEmpty())
            assertEquals(initialCalls, calls)
        } finally {
            vm.viewModelScope.cancel()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * `ANDROID_V2_PLAN.md:843` — the row opens where this UID left it, and on 달러 for anyone else.
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
     * Citi does not reach the screen on a fresh install, and everything else does.
     *
     * `ANDROID_V2_PLAN.md:813` — "Citi는 신규 표시 기본에서 제외". The sanitizer admits Citi on
     * purpose, so this is a default and not a ban; what it must not be is silent, which is why the
     * assertion is here at the screen state rather than only in the projection's own tests.
     */
    @Test
    fun citiIsNotShownOnAFreshInstall() = withViewModel(FakeTabStore("u1" to FreeTab.USD)) { vm, reads ->
        val usd = FreeSnapshotKey("usd", FreeSnapshotUiState.DEFAULT_PERIOD)
        val withCiti = snapshot(usd).copy(
            rate = FreeRate.Flat(
                "usd-krw",
                listOf(
                    ExchangeRate("usd-krw", "investing", 1398.8, basis),
                    ExchangeRate("usd-krw", "kb", 1399.0, basis),
                    ExchangeRate("usd-krw", "citi", 1401.6, basis)
                )
            )
        )
        reads.value = readState(snapshot = withCiti)
        vm.bind("u1")
        val rows = vm.uiState.value.rateSections.single().rows
        assertEquals(listOf("investing", "kb"), rows.map { it.id })
        // …and the hidden row is gone from the scale too, not merely from the list.
        assertTrue("씨티가 도메인에 남아 있다", vm.uiState.value.rateSections.single().domain!!.endInclusive < 1401.0)
    }

    /**
     * The sheet's answer reaches the screen and the store, and the order it did not change is not
     * written down.
     *
     * Recording the arrival order because somebody hid one bank would freeze the list: every source
     * the server added afterwards would land at the end for good. So the sheet returns what it was
     * seeded with, and only a difference from that counts as an order.
     */
    @Test
    fun hidingOneBankStoresTheHiddenSetAndNotAnOrder() {
        val rows = FakeRowStore()
        withViewModel(FakeTabStore("u1" to FreeTab.USD), rowStore = rows) { vm, reads ->
            reads.value = readState(snapshot = fxSnapshot())
            vm.bind("u1")
            val seeded = listOf("investing", "kb", "hana")
            vm.applyRowPreference(RateRowList.FX_BANKS, seeded, seeded, setOf("kb"))
            testScheduler.runCurrent()

            assertEquals(listOf("investing", "hana"), vm.uiState.value.rateSections.single().rows.map { it.id })
            val written = rows.writes.single().second
            assertNull("순서를 바꾸지 않았는데 순서가 기록됐다", written.order)
            // The defaults become the user's own set the moment they have one — Citi has to stay in
            // it or the next launch would show a bank they never asked for. Absent means defaults,
            // and once the set exists the defaults no longer apply to it at all.
            assertEquals(setOf("citi", "kb"), written.hidden)
        }
    }

    /** Reordering is written down, because that one the user did ask for. */
    @Test
    fun reorderingIsStored() {
        val rows = FakeRowStore()
        withViewModel(FakeTabStore("u1" to FreeTab.USD), rowStore = rows) { vm, reads ->
            reads.value = readState(snapshot = fxSnapshot())
            vm.bind("u1")
            vm.applyRowPreference(
                RateRowList.FX_BANKS,
                seeded = listOf("investing", "kb", "hana"),
                order = listOf("hana", "investing", "kb"),
                hidden = emptySet()
            )
            testScheduler.runCurrent()

            assertEquals(listOf("hana", "investing", "kb"), vm.uiState.value.rateSections.single().rows.map { it.id })
            assertEquals(listOf("hana", "investing", "kb"), rows.writes.single().second.order)
        }
    }

    /**
     * A source that did not arrive keeps the choice the user made about it.
     *
     * The sheet can only speak for what the payload carried. Treating its answer as the whole truth
     * would mean that opening it on a thin day and pressing 완료 quietly forgot every choice about a
     * source that happened to be absent — and the user would have no way to know.
     */
    @Test
    fun aSourceThatDidNotArriveKeepsItsStoredChoice() {
        val rows = FakeRowStore(
            mutableMapOf(
                RateRowList.FX_BANKS to RateRowPreference(
                    order = listOf("sc", "investing", "bs", "kb", "hana"),
                    hidden = setOf("bs", "citi")
                )
            )
        )
        withViewModel(FakeTabStore("u1" to FreeTab.USD), rowStore = rows) { vm, reads ->
            reads.value = readState(snapshot = fxSnapshot())
            vm.bind("u1")
            // Only investing/kb/hana arrived. The sheet moves hana to the front and hides kb.
            vm.applyRowPreference(
                RateRowList.FX_BANKS,
                seeded = listOf("investing", "kb", "hana"),
                order = listOf("hana", "investing", "kb"),
                hidden = setOf("kb")
            )
            testScheduler.runCurrent()

            val written = rows.writes.single().second
            // sc and bs keep their seats; the three that arrived fill the rest in their new order.
            assertEquals(listOf("sc", "hana", "bs", "investing", "kb"), written.order)
            // bs and citi were not on screen, so their hidden state is untouched; kb joins them.
            assertEquals(setOf("bs", "citi", "kb"), written.hidden)
        }
    }

    /**
     * A source the stored order has never seen can be dragged anywhere, not just to the end.
     *
     * The first version filled the seats the stored list already had, so a code with no seat was
     * appended however far the user had dragged it — investing moved to the front came back last.
     * Found by review; the sheet's order is the whole truth about the codes it showed.
     */
    @Test
    fun aNewSourceCanBeDraggedToTheFront() {
        val rows = FakeRowStore(
            mutableMapOf(RateRowList.FX_BANKS to RateRowPreference(order = listOf("kb", "hana")))
        )
        withViewModel(FakeTabStore("u1" to FreeTab.USD), rowStore = rows) { vm, reads ->
            reads.value = readState(snapshot = fxSnapshot())
            vm.bind("u1")
            // investing has no stored seat, so the sheet opens on [kb, hana, investing].
            val editor = vm.uiState.value.rowEditors.single()
            assertEquals(listOf("kb", "hana", "investing"), editor.entries.map { it.code })

            vm.applyRowPreference(
                RateRowList.FX_BANKS,
                seeded = listOf("kb", "hana", "investing"),
                order = listOf("investing", "kb", "hana"),
                hidden = emptySet()
            )
            testScheduler.runCurrent()

            assertEquals(listOf("investing", "kb", "hana"), rows.writes.single().second.order)
            assertEquals(
                listOf("investing", "kb", "hana"),
                vm.uiState.value.rateSections.single().rows.map { it.id }
            )
        }
    }

    /**
     * Hiding everything still draws one row, and the sheet says that row is only borrowed.
     *
     * D18: the projection must not change what is stored. The store still says all three are
     * hidden, and the editor marks the drawn one so the sheet can say why it is there.
     */
    @Test
    fun hidingEverythingDrawsOneBorrowedRow() {
        val rows = FakeRowStore()
        withViewModel(FakeTabStore("u1" to FreeTab.USD), rowStore = rows) { vm, reads ->
            reads.value = readState(snapshot = fxSnapshot())
            vm.bind("u1")
            vm.applyRowPreference(
                RateRowList.FX_BANKS,
                seeded = listOf("investing", "kb", "hana"),
                order = listOf("investing", "kb", "hana"),
                hidden = setOf("investing", "kb", "hana")
            )
            testScheduler.runCurrent()

            assertEquals(listOf("investing"), vm.uiState.value.rateSections.single().rows.map { it.id })
            assertEquals(setOf("citi", "investing", "kb", "hana"), rows.writes.single().second.hidden)
            val editor = vm.uiState.value.rowEditors.single { it.list == RateRowList.FX_BANKS }
            assertEquals(listOf(true, false, false), editor.entries.map { it.projected })
            assertTrue("숨긴 행이 편집 목록에서 사라졌다", editor.entries.none { it.visible })
        }
    }

    /**
     * The heading survives an empty list, because it is where the way back lives.
     *
     * Everything hidden leaves one projected row here, so the emptier case is the section being
     * dropped entirely — which would take the 조정 button with it.
     */
    @Test
    fun anEditableHeadingSurvivesWithNothingToDraw() {
        withViewModel(FakeTabStore("u1" to FreeTab.USD)) { vm, reads ->
            // Citi alone: hidden by default, and not a rescue candidate either.
            reads.value = readState(
                snapshot = fxSnapshot(listOf(ExchangeRate("usd-krw", "citi", 1401.6, basis)))
            )
            vm.bind("u1")
            val section = vm.uiState.value.rateSections.single()
            assertTrue("행이 남아 있다", section.rows.isEmpty())
            assertEquals(RateRowList.FX_BANKS, section.list)
            assertEquals(1, vm.uiState.value.rowEditors.size)
        }
    }

    /**
     * A stored arrangement is in force from the first frame, not from the first edit.
     *
     * The whole point of the store. Everything else here writes and then reads its own memory, so
     * without this the surface could ignore the file entirely and every other test would pass.
     */
    @Test
    fun aStoredArrangementIsAppliedOnBind() {
        val rows = FakeRowStore(
            mutableMapOf(
                RateRowList.FX_BANKS to RateRowPreference(
                    order = listOf("hana", "investing", "kb"),
                    hidden = setOf("kb")
                )
            )
        )
        withViewModel(FakeTabStore("u1" to FreeTab.USD), rowStore = rows) { vm, reads ->
            reads.value = readState(snapshot = fxSnapshot())
            vm.bind("u1")
            assertEquals(
                listOf("hana", "investing"),
                vm.uiState.value.rateSections.single().rows.map { it.id }
            )
            // …and the sheet opens on the same arrangement, hidden row included.
            val editor = vm.uiState.value.rowEditors.single()
            assertEquals(listOf("hana", "investing", "kb"), editor.entries.map { it.code })
            assertEquals(listOf(true, true, false), editor.entries.map { it.visible })
            assertTrue("읽기만 했는데 기록됐다", rows.writes.isEmpty())
        }
    }

    /** Another owner's stored arrangement is not read for this one. */
    @Test
    fun anotherOwnersArrangementIsNotRead() {
        val rows = FakeRowStore(
            mutableMapOf(RateRowList.FX_BANKS to RateRowPreference(hidden = setOf("investing")))
        ).apply { owner = "someone-else" }
        withViewModel(FakeTabStore("u1" to FreeTab.USD), rowStore = rows) { vm, reads ->
            reads.value = readState(snapshot = fxSnapshot())
            vm.bind("u1")
            assertEquals(
                listOf("investing", "kb", "hana"),
                vm.uiState.value.rateSections.single().rows.map { it.id }
            )
        }
    }

    private fun fxSnapshot(
        entries: List<ExchangeRate> = listOf(
            ExchangeRate("usd-krw", "investing", 1398.8, basis),
            ExchangeRate("usd-krw", "kb", 1399.0, basis),
            ExchangeRate("usd-krw", "hana", 1398.4, basis)
        )
    ) = snapshot(FreeSnapshotKey("usd", FreeSnapshotUiState.DEFAULT_PERIOD))
        .copy(rate = FreeRate.Flat("usd-krw", entries))

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
            vm.uiState.value.rateSections.map { it.title to it.rows.single().label }
        )
    }

    /**
     * `ANDROID_V2_PLAN.md:839`. The defaults match iOS's `FreeCurrencyConfig`. Binding alone must
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
            assertFalse(vm.uiState.value.drawnSeriesIds.isEmpty())
            assertTrue("the default was written back as if it were a choice", series.writes.isEmpty())

            // The default carries four ids; this answer only carries one of them, so hiding it
            // empties the graph while the other three stay chosen.
            vm.toggleSeries("investing.usd")
            testScheduler.runCurrent()
            assertTrue(vm.uiState.value.drawnSeriesIds.isEmpty())
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
            assertTrue("all-off did not survive the rebind", vm.uiState.value.drawnSeriesIds.isEmpty())
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

/**
 * What the chart would draw, from the state alone.
 *
 * The view model no longer produces per-series cards — it produces the whole prepared graph plus
 * the visible set, and the chart decides. These read that decision back without a Compose runtime.
 */
private val FreeSnapshotUiState.drawnSeriesIds: List<String>
    get() = graph?.order.orEmpty().filter { it in visibleSeriesIds && graph?.bySeries?.get(it)?.linePoints?.isNotEmpty() == true }

private fun FreeSnapshotUiState.pointsOf(seriesId: String): List<LinePoint> =
    graph?.bySeries?.get(seriesId)?.linePoints.orEmpty()
