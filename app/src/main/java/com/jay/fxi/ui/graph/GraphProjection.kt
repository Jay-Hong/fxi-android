package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.GraphPeriod
import kotlinx.datetime.Instant

/**
 * What one chart draws, decided before any pixel is touched.
 *
 * Every rule that could be got wrong lives here rather than inside the Canvas, because a Canvas
 * cannot be tested on the JVM and this is where the axis contract actually is: the time axis spans
 * every series the answer carries, and the value axes span only the visible ones.
 */
data class GraphPlot(
    /** Where the data is. Positions the ticks' alignment and the label cutoff. */
    val data: TimeFrame,
    /** What the x scale maps onto — padded, or the zoom window when there is one. */
    val display: TimeFrame,
    /**
     * The padded frame, kept even while zoomed.
     *
     * [display] stops being it the moment a zoom window exists, and the gestures need it anyway:
     * it is what "zoomed all the way out" is measured against. Carried here rather than recomputed
     * by the gesture code, so the two cannot answer differently — the same reason the plot
     * rectangle has one owner.
     */
    val rendered: TimeFrame,
    /** The value axis the lines are drawn against. Null when nothing is visible. */
    val valueRange: ClosedFloatingPointRange<Double>?,
    /**
     * The index's own range, whenever an index series is visible — beside rates or alone.
     *
     * Alone it *is* [valueRange], and keeping it set is what preserves the index's own label
     * precision: the rate ladder prints one decimal, so an index spanning 0.1 would label every
     * gridline "99.0".
     */
    val indexRange: ClosedFloatingPointRange<Double>?,
    /** What the drawn rate series actually reached, before any axis margin. */
    val observed: ClosedFloatingPointRange<Double>?,
    val lines: List<GraphPlotLine>,
    val xTicks: List<XTick>,
    val valueTicks: List<AxisTick>,
    val indexTicks: List<AxisTick>
) {
    val isEmpty: Boolean get() = lines.none { it.points.isNotEmpty() }
}

data class GraphPlotLine(
    val seriesId: String,
    val style: GraphSeriesStyle,
    /** Already projected onto the value axis — an index line is folded onto the rate scale here. */
    val points: List<LinePoint>,
    val band: List<BandPoint>,
    val isIndex: Boolean
)

object GraphProjection {

    private const val INDEX_AXIS = "index"

    /**
     * Build the plot.
     *
     * [visibleDomain] is the zoom window; null means the whole padded frame. The *data* frame is
     * unaffected by zooming — it is what the ticks align to and what decides whether a label is too
     * close to the edge to print.
     */
    /**
     * Everything the chart actually paints between the two edges, and nothing else.
     *
     * The temptation here is to reach past the window: the renderer selects one point beyond each
     * edge ([visibleSpan]/[visibleBandSpan]) so a line arriving from off-screen still arrives, and
     * an early version — and iOS's `visibleYValues`, which expands by a bucket — widened the axis to
     * make room for those neighbours' own values. That is unnecessary, and it is worse than
     * unnecessary. `xOf` puts `display.start` at the plot's left edge and `display.end` at its
     * right, and every series is drawn inside `clipRect(area…)`, so the neighbour's y is never
     * painted; only the part of the segment between the edges is. Widening for a value nobody can
     * see is exactly what flattens the chart the user just zoomed in to read — a spike a minute
     * outside the window would squash everything inside it.
     *
     * What *is* on screen at an edge is where the segment crosses it, so each crossing contributes
     * its interpolated value. Without that the opposite error appears: a shaded band drawn right
     * across the window from two observations outside it would be missed entirely, both by the axis
     * (which would clip it) and by the description (which would not mention it).
     *
     * So the axis and the accessibility reading want the same set, and take it from here. The band
     * counts because it is drawn; an index series has none, and folding its wicks onto a rate scale
     * would be meaningless anyway — the same split `GraphPrepared.extremaOf` makes.
     *
     * The selection is the renderer's own, not a rule that resembles it. Two things go wrong when
     * you only filter by time: [visibleSpan] resolves a **duplicate timestamp** on the left edge to
     * the last of them, so an earlier point sharing that instant is never drawn; and `drawBand`
     * gives up when the *selected* span is one point, which a two-point band whose window catches
     * only its far end is — it has no width left to shade. Asking the same functions the Canvas
     * asks removes both by construction.
     */
    internal fun windowExtrema(
        linePoints: List<LinePoint>,
        bandPoints: List<BandPoint>,
        window: TimeFrame
    ): ClosedFloatingPointRange<Double>? {
        val line = visibleSpan(linePoints, window)
        val band = visibleBandSpan(bandPoints, window).takeIf { it.size >= 2 }.orEmpty()
        val values = onScreenValues(line.map { it.ts to listOf(it.rate) }, window) +
            onScreenValues(band.map { it.ts to listOf(it.low, it.high) }, window)
        return if (values.isEmpty()) null else values.min()..values.max()
    }

    /**
     * Everything a piecewise-linear series shows between the two edges, edges included.
     *
     * Samples carry a list because the band contributes two values at each timestamp and they cross
     * an edge together; the arity is fixed by the caller, so paired lookup is safe.
     */
    private fun onScreenValues(
        samples: List<Pair<Instant, List<Double>>>,
        window: TimeFrame
    ): List<Double> = buildList {
        samples.forEachIndexed { index, (ts, values) ->
            if (ts >= window.start && ts <= window.end) addAll(values)
            val (nextTs, nextValues) = samples.getOrNull(index + 1) ?: return@forEachIndexed
            for (edge in listOf(window.start, window.end)) {
                // Strict on both sides: a point *on* an edge was already taken as itself, and a
                // segment that only touches an edge shows nothing its endpoint did not.
                if (ts < edge && nextTs > edge) {
                    val fraction = (edge - ts) / (nextTs - ts)
                    values.forEachIndexed { k, from -> add(from + (nextValues[k] - from) * fraction) }
                }
            }
        }
    }

    fun plot(
        prepared: PreparedGraph,
        visibleIds: Set<String>,
        visibleDomain: ClosedRange<Instant>? = null,
        rightEdgeNow: Instant? = null
    ): GraphPlot? {
        val data = GraphFrame.resolve(prepared, rightEdgeNow) ?: return null
        val rendered = GraphFrame.rendered(data, prepared.period)
        val display = visibleDomain
            ?.takeIf { it.start < it.endInclusive }
            ?.let { TimeFrame(it.start, it.endInclusive) }
            ?: rendered

        val visible = prepared.order.mapNotNull { prepared.bySeries[it] }.filter { it.seriesId in visibleIds }
        val rates = visible.filter { it.axisGroup != INDEX_AXIS }
        val indices = visible.filter { it.axisGroup == INDEX_AXIS }

        // iOS draws the shaded high/low band only for a lone rate series with no index beside it.
        // With two lines the shading of one sits under the other and reads as a third series; with
        // an index folded in, the band would be measured against a scale it does not belong to.
        val banded = rates.size == 1 && indices.isEmpty()

        // Built once and shared with the lines below, so what is measured is the very list the
        // renderer is handed. `bandOf` is `GraphPlotLine.band` restated: no band beside a second
        // line, none for an index. How much of it survives the window is [windowExtrema]'s job,
        // because that is where `drawBand` decides it too.
        val drawnLine = visible.associate { it.seriesId to GraphFrame.lineWithTrailing(it, prepared.period, data) }
        fun lineOf(series: PreparedSeries) = drawnLine[series.seriesId].orEmpty()
        fun bandOf(series: PreparedSeries) =
            if (banded && series.axisGroup != INDEX_AXIS) series.bandPoints else emptyList()

        // One set, two uses: the axis must hold what is on screen and the description must name it.
        val rateWindow = rates.mapNotNull { windowExtrema(lineOf(it), bandOf(it), display) }

        // Narrowing the axis is a different decision from narrowing the reading. iOS gates it on a
        // single rate series and says why: one line zoomed in is someone looking closely, while two
        // lines rescaled independently of each other would move apart for no reason the data gives
        // (`GraphV2Section.swift`'s `isTightenEligible`). Other periods do not zoom at all.
        val tighten = visibleDomain != null &&
            prepared.period == GraphPeriod.ONE_DAY &&
            rates.size == 1 &&
            indices.isEmpty()
        val rateRange = if (tighten) {
            GraphZoomMath.tightenedYDomain(rateWindow.flatMap { listOf(it.start, it.endInclusive) })
                ?: GraphAxis.composeAxisRange(rates.mapNotNull { it.extrema }, GraphAxis.KRW_MARGIN_FLOOR)
        } else {
            GraphAxis.composeAxisRange(rates.mapNotNull { it.extrema }, GraphAxis.KRW_MARGIN_FLOOR)
        }
        val ownIndexRange = GraphAxis.composeAxisRange(indices.mapNotNull { it.extrema }, GraphAxis.INDEX_MARGIN_FLOOR)

        // With no rate series on screen the index is not an overlay any more — it is the chart, and
        // it gets the axis to itself. Folding it onto an absent rate scale would have nothing to
        // fold onto.
        val valueRange = rateRange ?: ownIndexRange
        val foldIndex = rateRange != null && ownIndexRange != null

        val lines = visible.map { series ->
            val isIndex = series.axisGroup == INDEX_AXIS
            val points = lineOf(series)
            GraphPlotLine(
                seriesId = series.seriesId,
                style = GraphSeriesStyles.of(series.seriesId, series.label),
                points = if (foldIndex && isIndex && valueRange != null) {
                    points.map { LinePoint(it.ts, foldOntoRates(it.rate, valueRange, ownIndexRange!!)) }
                } else {
                    points
                },
                band = if (banded && !isIndex) series.bandPoints else emptyList(),
                isIndex = isIndex
            )
        }

        return GraphPlot(
            data = data,
            display = display,
            rendered = rendered,
            valueRange = valueRange,
            indexRange = ownIndexRange,
            // What the accessibility description reads out, and it says "on screen" — so these are
            // the window's extremes, not the day's, and the raw union rather than
            // `composeAxisRange`, which would add the axis margin this value exists to be free of.
            observed = rateWindow.takeIf { it.isNotEmpty() }
                ?.let { extremas -> extremas.minOf { it.start }..extremas.maxOf { it.endInclusive } },
            lines = lines,
            xTicks = GraphAxis.xTicks(prepared.period, data, display, data.end),
            // The right-hand rate ladder belongs to rates. An index alone owns the axis and is
            // labelled by its own column instead, at its own precision.
            valueTicks = rateRange?.let { GraphAxis.valueTicks(it.start, it.endInclusive) }.orEmpty(),
            indexTicks = ownIndexRange?.let { GraphAxis.indexLabels(it.start, it.endInclusive) }.orEmpty()
        )
    }

    private fun foldOntoRates(
        value: Double,
        rates: ClosedFloatingPointRange<Double>,
        index: ClosedFloatingPointRange<Double>
    ) = GraphAxis.normalizeIndexValue(value, rates.start, rates.endInclusive, index.start, index.endInclusive)

    /** 0..1 across the display window. Values outside it are allowed — the caller clips. */
    fun xOf(ts: Instant, display: TimeFrame): Float {
        val span = display.length.inWholeMilliseconds.toDouble()
        if (span <= 0.0) return 0.5f
        return ((ts - display.start).inWholeMilliseconds / span).toFloat()
    }

    /** 0..1 down the plot: the largest value sits at 0, because screen y grows downward. */
    fun yOf(value: Double, range: ClosedFloatingPointRange<Double>): Float {
        val span = range.endInclusive - range.start
        if (span <= 0.0) return 0.5f
        return (1.0 - (value - range.start) / span).toFloat()
    }

    /** The band's equivalent of [visibleSpan] — same reason, same rule. */
    fun visibleBandSpan(points: List<BandPoint>, display: TimeFrame): List<BandPoint> {
        if (points.isEmpty()) return points
        val first = points.indexOfLast { it.ts <= display.start }.coerceAtLeast(0)
        val lastFound = points.indexOfFirst { it.ts >= display.end }
        return points.subList(first, (if (lastFound == -1) points.lastIndex else lastFound) + 1)
    }

    /** Points whose *segment* touches the window, so a line entering from off-screen still enters. */
    fun visibleSpan(points: List<LinePoint>, display: TimeFrame): List<LinePoint> {
        if (points.isEmpty()) return points
        val first = points.indexOfLast { it.ts <= display.start }.coerceAtLeast(0)
        val lastFound = points.indexOfFirst { it.ts >= display.end }
        val last = if (lastFound == -1) points.lastIndex else lastFound
        return points.subList(first, last + 1)
    }
}
