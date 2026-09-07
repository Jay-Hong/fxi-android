package com.jay.fxi.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.ui.theme.SecondaryText
import kotlinx.datetime.Instant

/**
 * One framed chart for every visible series.
 *
 * It holds no state. Everything that could be decided wrongly — which window, which axis, what is
 * folded onto what — was decided by [GraphProjection], which is testable on the JVM; this only
 * turns that into pixels. `visibleDomain` is the zoom window and is null until the zoom slice
 * lands, at which point this composable does not change.
 */
@Composable
fun GraphChart(
    prepared: PreparedGraph,
    visibleIds: Set<String>,
    modifier: Modifier = Modifier,
    visibleDomain: ClosedRange<Instant>? = null,
    emptyMessage: String = "표시할 그래프 데이터가 없습니다."
) {
    val plot = remember(prepared, visibleIds, visibleDomain) {
        GraphProjection.plot(prepared, visibleIds, visibleDomain)
    }
    if (plot == null || plot.isEmpty) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(emptyMessage, color = SecondaryText, fontSize = 12.sp)
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = SecondaryText, fontFeatureSettings = "tnum")
    val indexStyle = remember {
        TextStyle(
            fontSize = 10.sp,
            color = Color(GraphSource.DXY.colorHex).copy(alpha = 0.85f),
            fontFeatureSettings = "tnum"
        )
    }
    val density = LocalDensity.current

    Canvas(modifier.semantics { contentDescription = plot.describe() }) {
        // Shared with the gestures rather than computed here: see `GraphPlotGeometry`.
        val plotted = GraphPlotGeometry.area(
            widthPx = size.width,
            heightPx = size.height,
            hasIndex = plot.indexRange != null,
            density = density.density
        )
        if (plotted.isEmpty) return@Canvas
        val area = Rect(plotted.left, plotted.top, plotted.right, plotted.bottom)

        drawGridlines(plot, area, measurer, axisStyle)
        // Bands first so no line is buried under a neighbour's shading.
        plot.lines.forEach { line -> drawBand(line, plot, area) }
        plot.lines.forEach { line -> drawLine(line, plot, area, density.density) }
        drawValueLabels(plot, area, measurer, axisStyle, size.width)
        drawIndexLabels(plot, area, measurer, indexStyle)
    }
}

private fun DrawScope.drawGridlines(
    plot: GraphPlot,
    area: Rect,
    measurer: TextMeasurer,
    style: TextStyle
) {
    val gridColor = SecondaryText.copy(alpha = 0.15f)
    plot.valueTicks.forEach { tick ->
        val range = plot.valueRange ?: return@forEach
        if (tick.value < range.start || tick.value > range.endInclusive) return@forEach
        val y = area.top + GraphProjection.yOf(tick.value, range) * area.height
        drawLine(gridColor, Offset(area.left, y), Offset(area.right, y), strokeWidth = 1f)
    }
    plot.xTicks.forEach { tick ->
        val x = area.left + GraphProjection.xOf(tick.ts, plot.display) * area.width
        if (x < area.left || x > area.right) return@forEach
        drawLine(gridColor, Offset(x, area.top), Offset(x, area.bottom), strokeWidth = 1f)
        if (!tick.showLabel) return@forEach
        val laid = measurer.measure(tick.label, style)
        drawText(laid, topLeft = Offset(x - laid.size.width / 2f, area.bottom + 2f))
    }
}

private fun DrawScope.drawBand(line: GraphPlotLine, plot: GraphPlot, area: Rect) {
    val range = plot.valueRange ?: return
    if (line.band.size < 2) return
    val path = Path()
    // The same rule the line uses: keep the shoulders that straddle the window, or the band stops
    // short of the edge the line runs to.
    val visible = GraphProjection.visibleBandSpan(line.band, plot.display)
    if (visible.size < 2) return
    visible.forEachIndexed { index, point ->
        val x = area.left + GraphProjection.xOf(point.ts, plot.display) * area.width
        val y = area.top + GraphProjection.yOf(point.high, range) * area.height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    visible.asReversed().forEach { point ->
        val x = area.left + GraphProjection.xOf(point.ts, plot.display) * area.width
        val y = area.top + GraphProjection.yOf(point.low, range) * area.height
        path.lineTo(x, y)
    }
    path.close()
    clipRect(area.left, area.top, area.right, area.bottom) {
        drawPath(path, Color(line.style.colorHex).copy(alpha = 0.12f))
    }
}

private fun DrawScope.drawLine(line: GraphPlotLine, plot: GraphPlot, area: Rect, density: Float) {
    val range = plot.valueRange ?: return
    val points = GraphProjection.visibleSpan(line.points, plot.display)
    if (points.isEmpty()) return
    val color = Color(line.style.colorHex)
    if (points.size == 1) {
        val point = points.single()
        val x = area.left + GraphProjection.xOf(point.ts, plot.display) * area.width
        val y = area.top + GraphProjection.yOf(point.rate, range) * area.height
        clipRect(area.left, area.top, area.right, area.bottom) {
            drawCircle(color, radius = 2.5f * density, center = Offset(x, y))
        }
        return
    }
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = area.left + GraphProjection.xOf(point.ts, plot.display) * area.width
        val y = area.top + GraphProjection.yOf(point.rate, range) * area.height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    clipRect(area.left, area.top, area.right, area.bottom) {
        drawPath(path, color, style = Stroke(width = line.style.lineWidthDp * density, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawValueLabels(
    plot: GraphPlot,
    area: Rect,
    measurer: TextMeasurer,
    style: TextStyle,
    width: Float
) {
    val range = plot.valueRange ?: return
    plot.valueTicks.forEach { tick ->
        if (tick.value < range.start || tick.value > range.endInclusive) return@forEach
        val y = area.top + GraphProjection.yOf(tick.value, range) * area.height
        val laid = measurer.measure(tick.label, style)
        drawText(laid, topLeft = Offset(width - laid.size.width - 4f, y - laid.size.height / 2f))
    }
}

/** The index keeps its own numbers on the left, so a folded line is still readable as an index. */
private fun DrawScope.drawIndexLabels(
    plot: GraphPlot,
    area: Rect,
    measurer: TextMeasurer,
    style: TextStyle
) {
    val rates = plot.valueRange ?: return
    val index = plot.indexRange ?: return
    plot.indexTicks.forEach { tick ->
        val folded = GraphAxis.normalizeIndexValue(
            tick.value, rates.start, rates.endInclusive, index.start, index.endInclusive
        )
        val y = area.top + GraphProjection.yOf(folded, rates) * area.height
        if (y < area.top || y > area.bottom) return@forEach
        val laid = measurer.measure(tick.label, style)
        drawText(laid, topLeft = Offset(2f, y - laid.size.height / 2f))
    }
}

/**
 * What a screen reader hears.
 *
 * The numbers are the **observed** extremes, not the axis range. The axis is padded by design —
 * five per cent on each side — so reading it out as "최저/최고" reports values no series reached.
 * That is the same mistake a per-series caption made before this chart replaced it, and losing it
 * once was enough.
 */
internal fun GraphPlot.describe(): String {
    val names = lines.filter { it.points.isNotEmpty() }.joinToString(", ") { it.style.label }
    val range = observed?.let { " 최저 %.2f 최고 %.2f".format(it.start, it.endInclusive) }.orEmpty()
    return "환율 추이 그래프. $names.$range"
}
