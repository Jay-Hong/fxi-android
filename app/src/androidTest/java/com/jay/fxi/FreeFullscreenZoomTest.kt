package com.jay.fxi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.free.FreeSnapshotAvailability
import com.jay.fxi.ui.free.FreeSnapshotScreen
import com.jay.fxi.ui.free.FreeSnapshotUiState
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * Who owns the zoom when the chart goes fullscreen — the three properties slice 8 could not test.
 *
 * iOS presents fullscreen as a **separate chart instance** over the inline one and gets two of these
 * for free: fullscreen always opens at full extent, and the inline zoom underneath is untouched by
 * the trip. Android replaces its content instead of covering it, so both are arranged deliberately,
 * and neither is visible to a test that only looks at the chart it just pinched.
 *
 * They are testable now because slice 9 made `GraphPlot.observed` follow the window. The chart's
 * accessibility description reports those extremes, so zoom finally shows up somewhere a test can
 * read — which is also the point of that change: while zoomed, the old description named values
 * that were not on screen. The fixture rises monotonically for the same reason; with a repeating
 * series every window has the same extremes and this file would assert nothing.
 */
class FreeFullscreenZoomTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val midnight = Instant.parse("2026-09-08T00:00:00Z")

    private fun uiState(period: GraphPeriod) = FreeSnapshotUiState(
        uid = "tester",
        selectedTab = FreeTab.USD,
        period = period,
        availability = FreeSnapshotAvailability.FRESH,
        graph = GraphPreparedBuilder.build(
            FreeGraph(
                bucketSize = null,
                series = listOf(
                    FreeGraphSeries(
                        seriesId = "investing.usd",
                        label = "인베스팅",
                        axisGroup = "krw",
                        points = (0..143).map {
                            FreeGraphPoint(midnight + (it * 10).minutes, 1400.0 + it * 0.1, null, null)
                        }
                    )
                ),
                domainStartAt = midnight,
                domainEndAt = midnight + 24.hours,
                liveDomainMode = "rolling"
            ),
            period
        ),
        visibleSeriesIds = setOf("investing.usd")
    )

    private fun chart(): SemanticsNodeInteraction =
        composeRule.onNode(hasContentDescription(CHART_PREFIX, substring = true))

    /** What a screen reader would say — and, since slice 9, a statement of the visible extremes. */
    private fun spoken(): String =
        chart().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    private fun zoomIn() {
        chart().performTouchInput {
            val y = height / 2f
            pinch(
                start0 = Offset(width * 0.40f, y), end0 = Offset(width * 0.05f, y),
                start1 = Offset(width * 0.60f, y), end1 = Offset(width * 0.95f, y)
            )
        }
        composeRule.waitForIdle()
    }

    private fun mount() {
        var state by mutableStateOf(uiState(GraphPeriod.ONE_DAY))
        composeRule.setContent {
            FreeSnapshotScreen(
                state = state,
                onSelectTab = {},
                onSelectPeriod = { state = uiState(it) },
                onToggleSeries = {},
                onSignOut = {},
                onSubscribe = {}
            )
        }
    }

    private fun openFullscreen() {
        composeRule.onNodeWithText("전체화면").performClick()
        composeRule.waitForIdle()
    }

    private fun closeFullscreen() {
        composeRule.onNodeWithText("닫기").performClick()
        composeRule.waitForIdle()
    }

    /** Fullscreen opens at full extent every time, however the last visit left it. */
    @Test
    fun fullscreenAlwaysOpensUnzoomed() {
        mount()
        openFullscreen()
        val opened = spoken()

        zoomIn()
        assertNotEquals("the pinch changed nothing, so this test cannot tell", opened, spoken())

        closeFullscreen()
        openFullscreen()
        assertEquals("fullscreen reopened still zoomed", opened, spoken())
    }

    /** …and the card underneath is untouched by the trip. */
    @Test
    fun theInlineZoomSurvivesTheFullscreenTrip() {
        mount()
        val flat = spoken()
        zoomIn()
        val inlineZoomed = spoken()
        assertNotEquals("the inline pinch changed nothing", flat, inlineZoomed)

        openFullscreen()
        closeFullscreen()

        assertEquals("the fullscreen trip threw the card's zoom away", inlineZoomed, spoken())
    }

    /** A period change is a different chart, so the window from the old one means nothing. */
    @Test
    fun changingThePeriodInFullscreenClearsItsZoom() {
        mount()
        openFullscreen()
        val opened = spoken()

        zoomIn()
        assertNotEquals("the pinch changed nothing, so this test cannot tell", opened, spoken())

        composeRule.onNodeWithText("1주").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1일").performClick()
        composeRule.waitForIdle()

        assertEquals("the zoom survived a period change", opened, spoken())
    }

    private companion object {
        const val CHART_PREFIX = "환율 추이 그래프"
    }
}
