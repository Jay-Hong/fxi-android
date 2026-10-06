package com.jay.fxi.ui.premium.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphProjection
import com.jay.fxi.ui.graph.PreparedGraph
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S4 C1-1a contract r1: the pure presentation step between the protected graph and the shared chart.
 *
 * Oracles: ANDROID_V2_PLAN.md :1286 (insufficient_history is a 200 with empty or partial data, not a failure), :1292 (a user's
 * all-off is not an absence of data). Design: R4c/S4 c1_design_codex.r1 row 05, c1_1_api_codex.r1 §2 (`GraphV2Content`,
 * `GraphV2ChartModel`, `GraphV2ScreenPresenter.content/project`) and c1_1_scope_codex.r1 (W10 and P-zoom replace re-asserting
 * the shared arithmetic, which ui/graph's own tests already pin).
 *
 * `content` classifies a prepared graph and the rendered ids: no data anywhere is NO_DATA before anything else; real data with
 * nothing rendered is NO_SELECTION; rendered series with nothing to draw - no points, or no frame to draw them in - are
 * NO_DATA; otherwise READY. `project` is the shared projection with the rendered ids, the zoom only when the chart can use
 * it (a 1d window whose start is before its end) and no clock: C1 draws history, C2 brings the live edge.
 *
 * The shared functions are the oracle on purpose: their arithmetic is contract-tested in ui/graph, and what this unit owns is
 * what it hands them. The implementation thread reads but does not edit this file.
 */
class GraphV2ScreenPresenterTest {

    private companion object {
        /** 1d rolling window: 2026-10-05 00:00 UTC to 24 hours later. */
        val D0: Instant = Instant.parse("2026-10-05T00:00:00Z")
        val D1: Instant = D0 + 24.hours

        fun points(n: Int, base: Double, from: Instant = D0 + 1.hours, step: Duration = 10.minutes, band: Boolean = true) =
            List(n) { i ->
                FreeGraphPoint(from + step * i, base + i, if (band) base + i + 1 else null, if (band) base + i - 1 else null)
            }

        /** A quiet hour: the rate barely moves, so its axis is narrower than the zoomed axis's 2.0 floor. */
        fun quiet(n: Int, from: Instant = D0 + 1.hours) =
            List(n) { i -> FreeGraphPoint(from + 10.minutes * i, 1400.0, 1400.1, 1399.9) }

        fun krw(id: String, points: List<FreeGraphPoint>) = FreeGraphSeries(id, points, id, "krw", "KRW", 2)

        fun index(id: String, points: List<FreeGraphPoint>) = FreeGraphSeries(id, points, id, "index", null, 2)

        fun day(vararg series: FreeGraphSeries, declared: Boolean = true) = FreeGraph(
            "10m", series.toList(),
            domainStartAt = if (declared) D0 else null,
            domainEndAt = if (declared) D1 else null,
            liveDomainMode = if (declared) "rolling" else null
        )

        fun prepared(graph: FreeGraph, period: GraphPeriod = GraphPeriod.ONE_DAY): PreparedGraph =
            GraphPreparedBuilder.build(graph, period)

        fun content(graph: FreeGraph, rendered: Set<String>, period: GraphPeriod = GraphPeriod.ONE_DAY) =
            GraphV2ScreenPresenter.content(prepared(graph, period), rendered)
    }

    // --- 05: what the screen says when there is nothing to draw ----------------------------------------------------

    /**
     * 05a: an empty answer is NO_DATA - with or without a selection, so no data outranks no selection; a partial answer of two
     * points is READY; real data with nothing selected is NO_SELECTION, not NO_DATA and not a fallback to a default.
     */
    @Test fun C05a_emptyPartialAndAllOffAreToldApart() {
        assertEquals("no series", GraphV2Content.NO_DATA, content(day(), emptySet()))
        assertEquals("a series with no points", GraphV2Content.NO_DATA, content(day(krw("X", emptyList())), setOf("X")))
        assertEquals("no data outranks no selection", GraphV2Content.NO_DATA, content(day(krw("X", emptyList())), emptySet()))
        assertEquals("two points", GraphV2Content.READY, content(day(krw("X", points(2, 1400.0))), setOf("X")))
        assertEquals("all off over real data", GraphV2Content.NO_SELECTION, content(day(krw("X", points(2, 1400.0))), emptySet()))
    }

    /**
     * 05b: a selection whose series has no points is NO_DATA even though another series has data and the declared window
     * gives the shared plot a frame (a non-null, empty plot - the premise); a single point with no usable domain has no frame
     * at all and is NO_DATA; the same point inside a declared window is drawable.
     */
    @Test fun C05b_aSelectionWithNothingToDrawIsNoData() {
        val declared = day(krw("X", emptyList()), krw("Y", points(2, 1400.0)))
        val emptyPlot = GraphProjection.plot(prepared(declared), setOf("X"))
        assertNotNull("premise: the declared window still frames an empty chart", emptyPlot)
        assertTrue("premise: nothing is drawn", emptyPlot!!.isEmpty)
        assertEquals(GraphV2Content.NO_DATA, content(declared, setOf("X")))
        assertEquals("Y is not drawn in X's place", GraphV2Content.NO_DATA,
            content(day(krw("X", emptyList()), krw("Y", points(2, 1400.0)), declared = false), setOf("X")))

        val lone = day(krw("X", points(1, 1400.0)), declared = false)
        assertNull("premise: one point and no domain give no frame", GraphProjection.plot(prepared(lone), setOf("X")))
        assertEquals(GraphV2Content.NO_DATA, content(lone, setOf("X")))
        assertEquals("one point inside a declared window", GraphV2Content.READY, content(day(krw("X", points(1, 1400.0))), setOf("X")))
    }

    // --- W10 / P-zoom: what is handed to the shared projection ----------------------------------------------------

    /**
     * W10: for each selection - one rate, two rates, a rate with the index, the index alone - and both without a zoom and with
     * a valid 1d zoom, the plot is exactly the shared projection of the same prepared graph, the rendered ids, that zoom and
     * no clock. The premises show the zoom and a clock each change the plot, so dropping the one or passing the other is seen.
     */
    @Test fun W10_theProjectionIsTheSharedOneWithTheSelectionAndTheZoom() {
        val p = prepared(day(
            krw("X", points(12, 1400.0)),
            krw("Y", points(12, 1380.0, from = D0 + 2.hours)),
            index("dxy", points(12, 99.0, band = false))
        ))
        val zoom = (D0 + 1.hours)..(D0 + 2.hours)
        assertNotEquals("premise: a valid zoom changes the plot",
            GraphProjection.plot(p, setOf("X"), null, null), GraphProjection.plot(p, setOf("X"), zoom, null))
        assertNotEquals("premise: a clock changes the plot",
            GraphProjection.plot(p, setOf("X"), null, null), GraphProjection.plot(p, setOf("X"), null, D1 + 3.hours))
        for (ids in listOf(setOf("X"), setOf("X", "Y"), setOf("X", "dxy"), setOf("dxy"))) {
            for (z in listOf(null, zoom)) {
                assertEquals("$ids zoom=$z", GraphProjection.plot(p, ids, z, null),
                    GraphV2ScreenPresenter.project(GraphV2ChartModel(p, ids), z))
            }
        }
    }

    /**
     * P-zoom: a zoom the chart cannot use is dropped whole - a valid window on a week (the shared plot would apply it), and on a
     * day a window whose start equals or passes its end (the shared plot would still tighten the axis on it). Each gives
     * exactly the unzoomed plot, compared as a whole. The day's series is quiet so that tightening is visible: its unzoomed axis
     * spans 1.2, under the 2.0 a tightened axis is padded to.
     */
    @Test fun Pzoom_aZoomTheChartCannotUseIsDroppedWhole() {
        val week = prepared(FreeGraph("1h", listOf(krw("X", points(72, 1400.0, step = 1.hours)))), GraphPeriod.ONE_WEEK)
        val window = (D0 + 10.hours)..(D0 + 20.hours)
        assertNotEquals("premise: the shared plot applies a window on a week",
            GraphProjection.plot(week, setOf("X"), null, null), GraphProjection.plot(week, setOf("X"), window, null))
        assertEquals(GraphProjection.plot(week, setOf("X"), null, null),
            GraphV2ScreenPresenter.project(GraphV2ChartModel(week, setOf("X")), window))

        val dayPlot = prepared(day(krw("X", quiet(12))))
        val unzoomed = GraphProjection.plot(dayPlot, setOf("X"), null, null)
        val same = (D0 + 3.hours)..(D0 + 3.hours)
        val inverted = (D0 + 4.hours)..(D0 + 3.hours)
        for ((label, z) in listOf("start = end" to same, "start > end" to inverted)) {
            assertNotEquals("premise ($label): the shared plot tightens on it", unzoomed, GraphProjection.plot(dayPlot, setOf("X"), z, null))
            assertEquals(label, unzoomed, GraphV2ScreenPresenter.project(GraphV2ChartModel(dayPlot, setOf("X")), z))
        }
    }
}
