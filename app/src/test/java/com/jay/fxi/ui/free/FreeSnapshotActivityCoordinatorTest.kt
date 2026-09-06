package com.jay.fxi.ui.free

import com.jay.fxi.domain.model.GraphPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeSnapshotActivityCoordinatorTest {
    private val events = mutableListOf<String>()
    private val coordinator = FreeSnapshotActivityCoordinator(
        onActivated = { tab, period -> events += "$tab/${period.code}" },
        onDeactivated = { events += "inactive" }
    )

    private fun update(
        uid: String = "u1",
        tab: String = "usd",
        period: GraphPeriod = GraphPeriod.THREE_MONTHS,
        foreground: Boolean = true,
        active: Boolean = true
    ) = coordinator.update(uid, tab, period, foreground, active)

    @Test
    fun onlyVisibleForegroundDestinationActivates_andRepeatedUpdatesAreNoOps() {
        update(foreground = false)
        update(active = false)
        assertEquals(emptyList<String>(), events)
        update()
        update()
        assertEquals(listOf("usd/3m"), events)
    }

    @Test
    fun backgroundStopsOnce_andForegroundReactivatesSameSelection() {
        update()
        update(foreground = false)
        update(foreground = false)
        update()
        assertEquals(listOf("usd/3m", "inactive", "usd/3m"), events)
    }

    @Test
    fun newsOrOverlayStopsRequests_andHiddenSelectionIsAppliedOnReturn() {
        update()
        update(active = false)
        update(tab = "eur", period = GraphPeriod.ONE_WEEK, active = false)
        update(tab = "eur", period = GraphPeriod.ONE_WEEK)
        assertEquals(listOf("usd/3m", "inactive", "eur/1w"), events)
    }

    @Test
    fun tabAndPeriodSwitchesActivateNewKeyWithoutResettingScheduler() {
        update()
        update(period = GraphPeriod.ONE_YEAR)
        update(tab = "jpy", period = GraphPeriod.ONE_YEAR)
        assertEquals(listOf("usd/3m", "usd/1y", "jpy/1y"), events)
    }

    @Test
    fun uidSwitchReleasesPreviousActivity_evenWhenKeyIsUnchanged() {
        update()
        update(uid = "u2")
        assertEquals(listOf("usd/3m", "inactive", "usd/3m"), events)
    }

    @Test
    fun disposalIsIdempotent_andDestinationCanReenter() {
        coordinator.dispose()
        update()
        coordinator.dispose()
        coordinator.dispose()
        update()
        assertEquals(listOf("usd/3m", "inactive", "usd/3m"), events)
    }
}
