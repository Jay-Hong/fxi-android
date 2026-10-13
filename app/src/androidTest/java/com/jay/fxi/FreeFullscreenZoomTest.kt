package com.jay.fxi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.components.PeriodTabBarTags
import com.jay.fxi.ui.free.FreeSeriesToggle
import com.jay.fxi.ui.free.FreeSnapshotAvailability
import com.jay.fxi.ui.free.FreeSnapshotScreen
import com.jay.fxi.ui.free.FreeSnapshotUiState
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
                onApplyRowPreference = { _, _, _, _ -> },
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


    // --- S5-3 E1 (T25 tether): a synthetic free tether snapshot through the shared screen and chart ----------------

    private val tetherPeriods = listOf(GraphPeriod.ONE_DAY, GraphPeriod.ONE_WEEK, GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR)
    private val tetherLabels = mapOf("upbit.usdt-krw" to "업비트", "hana.usd" to "하나")

    /** Each period's own rising readings and as_of label (11:30 + its index, KST). */
    private fun tetherState(period: GraphPeriod, visible: Set<String>): FreeSnapshotUiState {
        val i = tetherPeriods.indexOf(period)
        val step = when (period) {
            GraphPeriod.ONE_DAY -> 10.minutes
            GraphPeriod.ONE_WEEK -> 1.hours
            else -> 24.hours
        }
        val end = midnight + 2.hours + 30.minutes + i.minutes
        fun series(id: String, base: Double) = FreeGraphSeries(
            seriesId = id, label = tetherLabels.getValue(id), axisGroup = "krw",
            points = (0..23).map { FreeGraphPoint(end - step * (23 - it), base + 10 * i + it * 0.5, null, null) }
        )
        return FreeSnapshotUiState(
            uid = "tester",
            selectedTab = FreeTab.TETHER,
            period = period,
            availability = FreeSnapshotAvailability.FRESH,
            asOfLabel = "2026.09.08 11:3$i KST",
            graph = GraphPreparedBuilder.build(
                FreeGraph(bucketSize = null, series = listOf(series("upbit.usdt-krw", 1380.0), series("hana.usd", 1350.0))),
                period
            ),
            visibleSeriesIds = visible,
            seriesToggles = tetherLabels.map { (id, label) -> FreeSeriesToggle(id, label, id in visible) }
        )
    }

    /**
     * E1 (S5-3, T25 tether): the free tether screen mounted directly with a synthetic snapshot per period. The four periods
     * each show their own as_of and chart; series toggles, all hidden and restored, change and then restore the chart; a
     * fullscreen trip with a zoom leaves the card as it was; an identical rebuilt state shows the same as_of and chart. Not
     * evidence of the real sign-in, Hilt, root branch or an OS return.
     *
     * No tab round trip here: moving the pager back onto 테더 prefetches the adjacent 뉴스 page, whose FreeNewsTab takes a
     * Hilt view model this compose rule cannot provide (FreeSnapshotScreen.kt:230, FreeNewsTab.kt:66). The view-model round
     * trip is U1 (FreeSnapshotViewModelTest); the screen's is the device run D1.
     */
    @Test
    fun aFreeTetherSnapshotKeepsItsChartThroughPeriodsTogglesAndFullscreen() {
        val all = tetherLabels.keys
        var period by mutableStateOf(GraphPeriod.ONE_DAY)
        var visible by mutableStateOf<Set<String>>(all)
        var rebuild by mutableStateOf(0)
        composeRule.setContent {
            @Suppress("UNUSED_EXPRESSION") rebuild
            FreeSnapshotScreen(
                state = tetherState(period, visible),
                onSelectTab = {},
                onSelectPeriod = { period = it },
                onToggleSeries = { id -> visible = if (id in visible) visible - id else visible + id },
                onApplyRowPreference = { _, _, _, _ -> },
                onSignOut = {},
                onSubscribe = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("기준시각 2026.09.08 11:30 KST").assertIsDisplayed()
        val day = spoken()
        fun assertPeriodExtrema(p: GraphPeriod, description: String) {
            val i = tetherPeriods.indexOf(p)
            val expected = " 최저 %.2f 최고 %.2f".format(
                1350.0 + 10 * i, 1391.5 + 10 * i)
            assertTrue("$p: expected server extrema $expected, got $description",
                description.endsWith(expected))
        }
        assertPeriodExtrema(GraphPeriod.ONE_DAY, day)
        assertTrue("both series are named: $day", day.contains("업비트") && day.contains("하나"))

        val seen = mutableMapOf(GraphPeriod.ONE_DAY to day)
        for (p in tetherPeriods.drop(1)) {
            composeRule.onNodeWithTag(PeriodTabBarTags.period(p)).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("기준시각 2026.09.08 11:3${tetherPeriods.indexOf(p)} KST").assertIsDisplayed()
            seen[p] = spoken()
            assertPeriodExtrema(p, seen.getValue(p))
        }
        assertEquals("each period draws its own chart", 4, seen.values.toSet().size)
        composeRule.onNodeWithTag(PeriodTabBarTags.period(GraphPeriod.ONE_DAY)).performClick()
        composeRule.waitForIdle()
        assertEquals("back on 1d, the same chart", day, spoken())

        composeRule.onNodeWithText("업비트").performClick(); composeRule.waitForIdle()
        val hanaOnly = spoken()
        assertFalse("업비트 hidden: $hanaOnly", hanaOnly.contains("업비트"))
        assertTrue("하나 remains: $hanaOnly", hanaOnly.contains("하나"))
        composeRule.onNodeWithText("하나").performClick(); composeRule.waitForIdle()
        assertTrue("all hidden", visible.isEmpty())
        composeRule.onNodeWithText("표시할 항목을 선택해 주세요.").assertIsDisplayed()
        chart().assertDoesNotExist()
        composeRule.onNodeWithText("업비트").performClick(); composeRule.onNodeWithText("하나").performClick()
        composeRule.waitForIdle()
        assertEquals("restored, the same chart", day, spoken())

        openFullscreen()
        val fullscreenBeforeZoom = spoken()
        zoomIn()
        assertNotEquals("the tether fullscreen pinch changed nothing",
            fullscreenBeforeZoom, spoken())
        closeFullscreen()
        assertEquals("the fullscreen trip leaves the card", day, spoken())

        rebuild += 1
        composeRule.waitForIdle()
        composeRule.onNodeWithText("기준시각 2026.09.08 11:30 KST").assertIsDisplayed()
        assertEquals("an identical rebuilt state draws the same chart", day, spoken())
    }

    private companion object {
        const val CHART_PREFIX = "환율 추이 그래프"
    }
}
