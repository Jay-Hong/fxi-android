package com.jay.fxi.ui.graph

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Claude-owned S4 C2b-2 contract r2 (instrumented, part 1): the shared GraphChart takes an optional right edge and uses that
 * one instant for the frame, the zoom window and the plot; without one it draws exactly as before.
 *
 * Oracles: ANDROID_V2_PLAN.md :1317-1319 (timers move the axis); GraphFrame.resolve's rightEdgeNow seam (rolling 1d slides
 * the whole window). Design: R4c/S4 c2b2_design_codex.r1 section 1 (a last `rightEdgeNow: Instant? = null` parameter; frame =
 * resolve(prepared, rightEdgeNow), window = zoom.windowFor(frame, period), plot = GraphProjection.plot(prepared, visibleIds,
 * window, rightEdgeNow), each remembered with rightEdgeNow among its keys; the free call keeps the default), rows N01-N02
 * reduced: their gesture and two-surface zoom halves are already the existing GraphZoomGesturesTest's and
 * GraphV2FullscreenTest's and are rerun as the T25 regression.
 *
 * What is observed: the chart's spoken description names the observed extremes on screen (GraphPlot.describe), so a window
 * that moves changes its numbers. Fixture: one KRW series (an id outside the bank and exchange tables, so its legend is
 * the server label "X"), a point every 10 minutes at 1000 + index from 00:00 (index 0) to
 * 24:00 (index 144) on a rolling 1d domain [00:00, 24:00]. Unzoomed, the drawn window is the frame padded by 10 minutes.
 *
 * r2 (two-lens completeness check, c2b2_contract.r1/completeness_workflow.result.json): R02 also checks the chart writes no
 * zoom back when only the right edge moves. The exact strings assume the AVD's en-US locale (GraphPlot.describe formats
 * with the default locale).
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphChartRightEdgeNowTest {

    @get:Rule val rule = createComposeRule()

    private val midnight: Instant = Instant.parse("2026-10-05T15:00:00Z")
    private val prepared = GraphPreparedBuilder.build(
        FreeGraph(
            "10min",
            listOf(FreeGraphSeries(
                "x.usd", (0..144).map { FreeGraphPoint(midnight + (it * 10).minutes, 1000.0 + it, null, null) },
                label = "X", axisGroup = "krw"
            )),
            domainStartAt = midnight, domainEndAt = midnight + 24.hours, liveDomainMode = "rolling"
        ),
        GraphPeriod.ONE_DAY
    )

    private fun spoken() = rule.onNodeWithTag("chart").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    /**
     * R01: unzoomed, the same prepared graph read at a right edge of 25:00 draws [00:50, 25:10], whose lowest value is index 5;
     * with no right edge it draws [-00:10, 24:10] from index 0 - and a right edge that changes while composed redraws.
     */
    @Test fun R01_theRightEdgeMovesTheDrawnWindow() {
        var edge by mutableStateOf<Instant?>(null)
        rule.setContent {
            GraphChart(prepared, setOf("x.usd"), Modifier.fillMaxWidth().height(240.dp).testTag("chart"), rightEdgeNow = edge)
        }
        rule.waitForIdle()
        assertEquals("환율 추이 그래프. X. 최저 1000.00 최고 1144.00", spoken())
        edge = midnight + 25.hours
        rule.waitForIdle()
        assertEquals("환율 추이 그래프. X. 최저 1005.00 최고 1144.00", spoken())
        edge = null
        rule.waitForIdle()
        assertEquals("the free default is unchanged", "환율 추이 그래프. X. 최저 1000.00 최고 1144.00", spoken())
    }

    /**
     * R02: a following six-hour zoom rides the right edge - [18:00, 24:00] (1108..1144) with no right edge, [19:00, 25:00]
     * (1114..1144) at 25:00; a zoom that does not follow keeps its window; the chart never writes a zoom back for this.
     */
    @Test fun R02_aFollowingZoomRidesTheRightEdge() {
        var edge by mutableStateOf<Instant?>(null)
        var zoom by mutableStateOf(GraphZoomState(visible = (midnight + 18.hours)..(midnight + 24.hours), isFollowing = true))
        val emitted = mutableListOf<GraphZoomState>()
        rule.setContent {
            GraphChart(
                prepared, setOf("x.usd"), Modifier.fillMaxWidth().height(240.dp).testTag("chart"), zoom = zoom,
                onZoom = { emitted += it }, rightEdgeNow = edge
            )
        }
        rule.waitForIdle()
        assertEquals("환율 추이 그래프. X. 최저 1108.00 최고 1144.00", spoken())
        edge = midnight + 25.hours
        rule.waitForIdle()
        assertEquals("환율 추이 그래프. X. 최저 1114.00 최고 1144.00", spoken())

        zoom = GraphZoomState(visible = (midnight + 18.hours)..(midnight + 24.hours), isFollowing = false)
        rule.waitForIdle()
        assertEquals("a fixed zoom keeps its window", "환율 추이 그래프. X. 최저 1108.00 최고 1144.00", spoken())
        assertEquals("the chart writes no zoom back when the right edge moves", emptyList<GraphZoomState>(), emitted)
    }
}
