package com.jay.fxi.ui.free

import androidx.lifecycle.viewModelScope
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.AuthUidStream
import com.jay.fxi.data.free.FreeSnapshotEntry
import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.data.free.FreeSnapshotScheduler
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.repository.FreeSnapshotFetching
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val key = FreeSnapshotUiState.DEFAULT_KEY
    private val basis = Instant.parse("2026-09-06T02:30:00Z")

    private fun snapshot(key: FreeSnapshotKey = this.key) = FreeSnapshot(
        tab = key.tab,
        period = key.period,
        asOf = basis,
        generatedAt = basis + 1.minutes,
        refreshNotBefore = basis + 48.hours,
        rate = FreeRate.Flat("usd-krw", listOf(ExchangeRate("usd-krw", "kb", 1400.0, basis - 1.hours))),
        graph = FreeGraph(null, listOf(FreeGraphSeries(
            seriesId = "usd_krw",
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

    private fun withViewModel(block: suspend TestScope.(FreeSnapshotViewModel, MutableStateFlow<FreeSnapshotReadState>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reads = MutableStateFlow(FreeSnapshotReadState())
        val vm = FreeSnapshotViewModel(reads, FreeSnapshotActivityCoordinator({ _, _ -> }, {}))
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
        assertTrue(vm.uiState.value.rates.isEmpty())
        reads.value = FreeSnapshotReadState("u1")
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.AWAITING_SNAPSHOT, vm.uiState.value.availability)
    }

    @Test
    fun ownerMismatchNeverRenders_andUidSwitchClearsSynchronously() = withViewModel { vm, reads ->
        reads.value = readState()
        vm.bind("u1")
        assertFalse(vm.uiState.value.rates.isEmpty())
        // Render-time fence closes the frame before the new UID's bind effect.
        assertTrue(vm.uiState.value.forOwner("u2").rates.isEmpty())
        vm.bind("u2")
        assertTrue(vm.uiState.value.rates.isEmpty())
        testScheduler.runCurrent()
        assertNull(vm.uiState.value.asOfLabel)
        reads.value = readState(uid = "u2")
        testScheduler.runCurrent()
        assertFalse(vm.uiState.value.rates.isEmpty())
        reads.value = readState(uid = "u1", freshness = FreeSnapshotFreshness.DELAYED)
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.AWAITING_SNAPSHOT, vm.uiState.value.availability)
    }

    @Test
    fun switchingToUncachedPeriodCannotReusePreviousChart() = withViewModel { vm, reads ->
        reads.value = readState()
        vm.bind("u1")
        val other = key.copy(period = GraphPeriod.ONE_WEEK)
        vm.bind("u1", other)
        assertTrue(vm.uiState.value.charts.isEmpty())
        assertNull(vm.uiState.value.asOfLabel)
        reads.value = readState(snapshot = snapshot(other))
        testScheduler.runCurrent()
        assertEquals(other, vm.uiState.value.key)
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
        assertEquals(fresh.rates, vm.uiState.value.rates)
        assertEquals(fresh.charts, vm.uiState.value.charts)
        reads.value = readState(freshness = FreeSnapshotFreshness.UNAVAILABLE)
        testScheduler.runCurrent()
        assertEquals(FreeSnapshotAvailability.UNAVAILABLE, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.rates.isEmpty())
        assertTrue(vm.uiState.value.charts.isEmpty())
        assertEquals(fresh.asOfLabel, vm.uiState.value.asOfLabel)
    }

    @Test
    fun emptySuccessfulPayloadIsStillFresh() = withViewModel { vm, reads ->
        reads.value = readState(snapshot = snapshot().copy(rate = FreeRate.Flat("usd-krw", emptyList()), graph = FreeGraph(null, emptyList())))
        vm.bind("u1")
        assertEquals(FreeSnapshotAvailability.FRESH, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.charts.isEmpty())
        assertTrue(vm.uiState.value.rates.isEmpty())
    }

    @Test
    fun chartUsesSnapshotTimeAndValues_withoutSyntheticLiveTail() = withViewModel { vm, reads ->
        reads.value = readState()
        vm.bind("u1")
        assertEquals(listOf(FreeSnapshotChartPoint(0f, 1f), FreeSnapshotChartPoint(1f, 0f)), vm.uiState.value.charts.single().points)
        val single = snapshot().let { it.copy(graph = FreeGraph(null, listOf(it.graph.series.single().copy(points = it.graph.series.single().points.take(1))))) }
        reads.value = readState(snapshot = single)
        testScheduler.runCurrent()
        assertEquals(listOf(FreeSnapshotChartPoint(0.5f, 0.5f)), vm.uiState.value.charts.single().points)
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
        val vm = FreeSnapshotViewModel(scheduler)
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
}
