package com.jay.fxi.ui.premium.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphCarryIn
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.DisplayDomain
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphProjection
import com.jay.fxi.ui.graph.LinePoint
import com.jay.fxi.ui.graph.LiveDomainMode
import com.jay.fxi.ui.graph.PreparedGraph
import com.jay.fxi.ui.graph.PreparedSeries
import com.jay.fxi.ui.graph.TimeRange
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned S5 A2 U-B contract r2: `GraphV2ScreenPresenter.content` decides READY / NO_DATA without building the whole
 * projection, and decides exactly as before.
 *
 * Design: R4c/S5/s5_4e/s10_next/A2_impl/design.r2.md (U-B). Measured motive: `content` ran the full `GraphProjection.plot`
 * 7-9 times per tab entry and 6 times per period switch only to read `isEmpty` (A2_plan/phase0/report.r2.md).
 *
 * Oracles: a frozen copy of `content` as it was (the full projection's `isEmpty`); a frozen copy of the selection `plot` drew
 * from (wire order, each series' own id), which pins the new shared `visibleSeries` and `plot` independently of each other; and
 * hand-built boundary graphs where the clock alone decides whether a line can be drawn. The random inputs are seeded prepared
 * graphs, including the shape a live projection hands over (index series without a band); every case leaves `plot` without an
 * exception, so the equivalence is never vacuous, and both decided outcomes and the NO_SELECTION branch occur. B4/B5 hold the
 * structure the change exists for: no full projection on the decision path, and one selection shared with `plot`.
 *
 * The implementation thread reads but does not edit this file.
 */
class GraphV2ContentDecisionTest {

    private companion object {
        val BASE: Instant = Instant.parse("2026-10-05T03:00:00Z")
        const val CASES = 20_000
        val IDS = listOf("a.usd", "b.usdt-krw", "dxy", "krx.usd-krw-futures", "c.jpy")
    }

    /** `content` as it was before U-B: the full projection decides READY / NO_DATA. */
    private fun frozenContent(prepared: PreparedGraph, renderedIds: Set<String>): GraphV2Content {
        if (prepared.bySeries.values.none { it.lastObservation != null }) return GraphV2Content.NO_DATA
        if (renderedIds.isEmpty()) return GraphV2Content.NO_SELECTION
        val plot = GraphProjection.plot(prepared, renderedIds, null, null)
        return if (plot == null || plot.isEmpty) GraphV2Content.NO_DATA else GraphV2Content.READY
    }

    /** `plot`'s selection as it was before U-B, frozen: wire order, filtered by each series' own id. */
    private fun frozenSelection(prepared: PreparedGraph, ids: Set<String>): List<PreparedSeries> =
        prepared.order.mapNotNull { prepared.bySeries[it] }.filter { it.seriesId in ids }

    private class Case(val prepared: PreparedGraph, val rendered: Set<String>, val zoom: ClosedRange<Instant>?, val now: Instant?)

    private fun cases(seed: Long): Sequence<Case> = sequence {
        val rnd = java.util.Random(seed)
        repeat(CASES) {
            val period = GraphPeriod.entries[rnd.nextInt(GraphPeriod.entries.size)]
            val bucket = GraphPreparedBuilder.bucketFor(period)
            val ids = IDS.shuffled(rnd).take(1 + rnd.nextInt(IDS.size))
            val series = ids.map { id ->
                val count = if (rnd.nextInt(5) == 0) 0 else 1 + rnd.nextInt(40)
                var ts = BASE - bucket * (count + rnd.nextInt(30))
                val points = List(count) {
                    ts += bucket * (if (rnd.nextInt(6) == 0) 2 + rnd.nextInt(30) else 1)
                    val rate = 1300.0 + rnd.nextDouble() * 100
                    if (rnd.nextBoolean()) FreeGraphPoint(ts, rate, rate + rnd.nextDouble(), rate - rnd.nextDouble())
                    else FreeGraphPoint(ts, rate, null, null)
                }
                FreeGraphSeries(
                    id, points,
                    axisGroup = if (id == "dxy") "index" else "krw",
                    insufficientHistory = rnd.nextInt(6) == 0,
                    carryIn = if (rnd.nextInt(3) == 0 && points.isNotEmpty()) {
                        FreeGraphCarryIn(1350.0, points.first().timestamp - bucket * (1 + rnd.nextInt(80)))
                    } else null
                )
            }
            val declared = rnd.nextInt(3) != 0
            val graph = FreeGraph(
                bucketSize = null,
                series = series,
                domainStartAt = if (declared) BASE - bucket * (20 + rnd.nextInt(200)) else null,
                domainEndAt = if (declared) BASE + bucket * rnd.nextInt(5) - bucket * (if (rnd.nextInt(10) == 0) 400 else 0) else null,
                liveDomainMode = if (!declared) null else listOf("rolling", "fixed_start", "bogus")[rnd.nextInt(3)]
            )
            val built = GraphPreparedBuilder.build(graph, period)
            // A live projection hands over index series without their band.
            val prepared = if (rnd.nextInt(4) == 0) {
                built.copy(bySeries = built.bySeries.mapValues { (_, s) -> if (s.axisGroup == "index") s.copy(bandPoints = emptyList()) else s })
            } else built
            val rendered = when (rnd.nextInt(8)) {
                0 -> emptySet()
                1 -> setOf("not.in.graph")
                else -> (ids.filter { rnd.nextInt(4) != 0 } + if (rnd.nextInt(5) == 0) listOf("not.in.graph") else emptyList()).toSet()
            }
            val zoom = if (rnd.nextInt(3) == 0) {
                val start = BASE - bucket * rnd.nextInt(60)
                start..(start + bucket * (rnd.nextInt(40) - 5))
            } else null
            val now = if (rnd.nextBoolean()) BASE + (rnd.nextInt(240) - 120).minutes else null
            yield(Case(prepared, rendered, zoom, now))
        }
    }

    @Test fun B1_contentDecidesAsTheFullProjectionDid() {
        val seen = mutableMapOf<GraphV2Content, Int>()
        val decided = mutableMapOf<GraphV2Content, Int>()
        for (c in cases(20261019L)) {
            val expected = frozenContent(c.prepared, c.rendered)
            assertEquals("content", expected, GraphV2ScreenPresenter.content(c.prepared, c.rendered))
            seen.merge(expected, 1, Int::plus)
            if (c.rendered.isNotEmpty() && c.prepared.bySeries.values.any { it.lastObservation != null }) {
                decided.merge(expected, 1, Int::plus)
            }
        }
        assertTrue("NO_SELECTION occurs: $seen", (seen[GraphV2Content.NO_SELECTION] ?: 0) > 100)
        for (outcome in listOf(GraphV2Content.READY, GraphV2Content.NO_DATA)) {
            assertTrue("the drawable decision yields $outcome: $decided", (decided[outcome] ?: 0) > 100)
        }
    }

    @Test fun B2_theSharedSelectionIsTheFrozenOneInWireOrderAndThePlotDrawsIt() {
        var drawn = 0
        var reordered = 0
        for (c in cases(20261020L)) {
            for (ids in listOf(c.rendered, c.rendered.reversed().toSet())) {
                val expected = frozenSelection(c.prepared, ids)
                assertEquals("visibleSeries", expected, GraphProjection.visibleSeries(c.prepared, ids))
                val plot = GraphProjection.plot(c.prepared, ids, c.zoom, c.now) ?: continue
                assertEquals("plot lines", expected.map { it.seriesId }, plot.lines.map { it.seriesId })
                drawn++
            }
            if (frozenSelection(c.prepared, c.rendered).size >= 2) reordered++
        }
        assertTrue("projections were drawn: $drawn", drawn > 1_000)
        assertTrue("selections with two or more series, whose reversed ids differ from wire order: $reordered", reordered > 1_000)
    }

    @Test fun B3_hasDrawableLineIsTheProjectionsNonEmptiness() {
        var drawable = 0
        var notDrawable = 0
        for (c in cases(20261021L)) {
            val expected = GraphProjection.plot(c.prepared, c.rendered, null, c.now)?.isEmpty == false
            assertEquals("hasDrawableLine", expected, GraphProjection.hasDrawableLine(c.prepared, c.rendered, c.now))
            if (expected) drawable++ else notDrawable++
        }
        assertTrue("both outcomes occur: $drawable / $notDrawable", drawable > 1_000 && notDrawable > 1_000)
    }

    /**
     * Helper boundaries, not builder-reachable inputs: a series whose only drawable point is the trailing hold, so the clock
     * alone decides. With a fixed-start domain the hold is within the four-day week budget at the snapshot and beyond it five
     * days later; on the data-bounds fallback the hold exists only once a clock pushes the right edge past the last observation.
     */
    @Test fun B3b_theClockAloneCanDecideWhetherALineIsDrawable() {
        val t = BASE
        fun heldOnly(id: String) = PreparedSeries(
            seriesId = id, label = id, axisGroup = "krw", linePoints = emptyList(), bandPoints = emptyList(),
            extrema = 1300.0..1300.0, lastObservation = LinePoint(t, 1300.0),
            insufficientHistory = false, carryInApplied = false
        )
        val fixed = PreparedGraph(
            GraphPeriod.ONE_WEEK, listOf("a.usd"), mapOf("a.usd" to heldOnly("a.usd")),
            DisplayDomain(t - 6.days, t + 1.hours, LiveDomainMode.FIXED_START), TimeRange(t - 6.days, t)
        )
        assertEquals("premise: the projection draws the hold at the snapshot", false, GraphProjection.plot(fixed, setOf("a.usd"), null, null)?.isEmpty)
        assertEquals("premise: and has nothing to draw five days on", true, GraphProjection.plot(fixed, setOf("a.usd"), null, t + 5.days)?.isEmpty)
        assertTrue("snapshot: within the budget", GraphProjection.hasDrawableLine(fixed, setOf("a.usd"), null))
        assertFalse("five days on: beyond the budget", GraphProjection.hasDrawableLine(fixed, setOf("a.usd"), t + 5.days))

        val fallback = PreparedGraph(
            GraphPeriod.ONE_WEEK, listOf("a.usd"), mapOf("a.usd" to heldOnly("a.usd")), null, TimeRange(t - 2.days, t)
        )
        assertEquals("premise: no hold without a clock", true, GraphProjection.plot(fallback, setOf("a.usd"), null, null)?.isEmpty)
        assertEquals("premise: a clock makes the hold", false, GraphProjection.plot(fallback, setOf("a.usd"), null, t + 1.hours)?.isEmpty)
        assertFalse("no clock: the right edge is the last observation", GraphProjection.hasDrawableLine(fallback, setOf("a.usd"), null))
        assertTrue("a clock: the hold reaches it", GraphProjection.hasDrawableLine(fallback, setOf("a.usd"), t + 1.hours))
    }

    private fun bodyOf(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        assertTrue("$declaration is declared", start >= 0)
        val ends = listOf("\n    fun ", "\n    internal fun ", "\n    private fun ", "\n    /**", "\n}")
            .map { source.indexOf(it, start + 1) }
            .filter { it >= 0 }
        return source.substring(start, ends.minOrNull() ?: source.length)
    }

    @Test fun B4_contentNoLongerBuildsTheProjection() {
        val source = File("src/main/java/com/jay/fxi/ui/premium/graph/GraphV2ScreenPresenter.kt").readText()
        val body = bodyOf(source, "fun content(")
        for (forbidden in listOf(Regex("""\bproject\s*\("""), Regex("""\bplot\s*\("""), Regex("""::(plot|project)\b"""))) {
            assertFalse("content must not reach ${forbidden.pattern}", forbidden.containsMatchIn(body))
        }
        assertTrue("content decides with the shared predicate", body.contains("GraphProjection.hasDrawableLine("))
    }

    @Test fun B5_thePredicateBuildsNoPlotAndPlotUsesTheSharedSelection() {
        val source = File("src/main/java/com/jay/fxi/ui/graph/GraphProjection.kt").readText()
        val predicate = bodyOf(source, "fun hasDrawableLine(")
        for (forbidden in listOf(
            Regex("""\bplot\s*\("""), Regex("""::plot\b"""), Regex("""\bGraphPlot\b"""),
            Regex("""\bwindowExtrema\b"""), Regex("""\bGraphAxis\."""), Regex("""\brendered\s*\(""")
        )) {
            assertFalse("hasDrawableLine must not reach ${forbidden.pattern}", forbidden.containsMatchIn(predicate))
        }
        assertTrue("decides from the frame", predicate.contains("GraphFrame.resolve("))
        assertTrue("over the shared selection", predicate.contains("visibleSeries("))
        val plot = bodyOf(source, "fun plot(")
        assertTrue("plot draws the shared selection", plot.contains("visibleSeries("))
        assertFalse("plot no longer keeps its own copy of the selection", plot.contains("prepared.order.mapNotNull"))
    }
}
