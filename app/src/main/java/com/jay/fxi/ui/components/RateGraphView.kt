package com.jay.fxi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.color
import kotlin.math.pow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * 환율 그래프 뷰 (Canvas 기반, iOS RateGraphView와 동일)
 *
 * - 24시간 환율 그래프
 * - 단일 소스: min~max 밴드 + close 라인
 * - 다중 소스: 각 소스별 close 라인
 * - 시간 기반 X축 (4시간 간격, 00시는 M/d)
 * - Y축 오른쪽, 소수점 1자리
 *
 * @param metrics 레이아웃 메트릭스 (phone/tablet 적응형)
 */
@Composable
fun RateGraphView(
    graphData: Map<GraphSource, List<GraphBucket>>,
    selectedSources: Set<GraphSource>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    Box(
        modifier = modifier.height(metrics.graphHeight),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading && graphData.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        color = SecondaryText,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "그래프 로딩 중...",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                }
            }
            graphData.isEmpty() || graphData.values.all { it.isEmpty() } -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.height(28.dp)
                    )
                    Text(
                        text = "그래프 데이터가 없습니다",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                }
            }
            else -> {
                RateGraphCanvas(
                    graphData = graphData,
                    selectedSources = selectedSources,
                    verticalPadding = metrics.graphVerticalPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// ============ Data classes for pre-computed chart data ============

private data class ChartLayout(
    val chartLeft: Float,
    val chartTop: Float,
    val chartRight: Float,
    val chartBottom: Float,
    val xMin: Int,
    val xMax: Int,
    val yMin: Double,
    val yMax: Double
) {
    val chartWidth: Float get() = chartRight - chartLeft
    val chartHeight: Float get() = chartBottom - chartTop

    fun mapX(ts: Int): Float {
        if (xMax == xMin) return chartLeft
        return chartLeft + (ts - xMin).toFloat() / (xMax - xMin) * chartWidth
    }

    fun mapY(value: Double): Float {
        if (yMax == yMin) return chartTop + chartHeight / 2
        return chartBottom - ((value - yMin) / (yMax - yMin) * chartHeight).toFloat()
    }
}

private data class XTick(
    val ts: Int,
    val label: String?,  // null = grid only, no label
    val showLabel: Boolean,
    val isMidnight: Boolean = false
)

private data class YTick(
    val value: Double,
    val label: String
)

private data class SourcePaths(
    val source: GraphSource,
    val closePath: Path,
    val bandPath: Path?  // non-null only for single source
)

// ============ Canvas composable ============

@Composable
private fun RateGraphCanvas(
    graphData: Map<GraphSource, List<GraphBucket>>,
    selectedSources: Set<GraphSource>,
    verticalPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val labelStyle = remember {
        TextStyle(
            fontSize = 11.sp,
            color = SecondaryText,
            fontFeatureSettings = "tnum"
        )
    }

    // Padding in px
    val leftPad = with(density) { 8.dp.toPx() }
    val rightPad = with(density) { 40.dp.toPx() }
    val bottomPad = with(density) { 16.dp.toPx() }
    val innerPad = with(density) { verticalPadding.toPx() }  // inner plot padding top+bottom
    val gridStroke = with(density) { 0.5.dp.toPx() }
    val lineStroke = with(density) { 1.5.dp.toPx() }
    val yLabelGap = with(density) { 4.dp.toPx() }
    val xLabelGap = with(density) { 1.dp.toPx() }

    val gridColor = SecondaryText.copy(alpha = 0.3f)
    val midnightGridColor = SecondaryText.copy(alpha = 0.4f)

    // Pre-compute all chart data
    val chartState = remember(graphData, selectedSources) {
        computeChartState(graphData, selectedSources)
    }

    if (chartState == null) return

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Layout
        val layout = ChartLayout(
            chartLeft = leftPad,
            chartTop = innerPad,
            chartRight = canvasWidth - rightPad,
            chartBottom = canvasHeight - bottomPad - innerPad,
            xMin = chartState.xMin,
            xMax = chartState.xMax,
            yMin = chartState.yMin,
            yMax = chartState.yMax
        )

        // Compute ticks
        val xTicks = computeXTicks(layout.xMin, layout.xMax, chartState.lastDataTs)
        val sampleLabel = "0000.0"
        val labelHeight = textMeasurer.measure(sampleLabel, labelStyle).size.height.toFloat()
        val yTicks = computeYTicks(layout.yMin, layout.yMax, layout.chartHeight, labelHeight)

        // Compute paths
        val sourcePaths = computeSourcePaths(
            graphData, selectedSources, layout, selectedSources.size == 1
        )

        // === Render ===

        // 1. Grid lines (can extend to label area, draw before clip)
        // Horizontal grid + Y labels (skip min/max ticks to avoid extra axis lines)
        val yTickEpsilon = (layout.yMax - layout.yMin) * 1e-9
        for (tick in yTicks) {
            if (tick.value <= layout.yMin + yTickEpsilon) continue
            if (tick.value >= layout.yMax - yTickEpsilon) continue
            val y = layout.mapY(tick.value)
            // Grid line
            drawLine(
                color = gridColor,
                start = Offset(layout.chartLeft, y),
                end = Offset(layout.chartRight, y),
                strokeWidth = gridStroke
            )
            // Y label (right of chart)
            val textResult = textMeasurer.measure(tick.label, labelStyle)
            drawText(
                textLayoutResult = textResult,
                topLeft = Offset(
                    layout.chartRight + yLabelGap,
                    y - textResult.size.height / 2f
                )
            )
        }

        // Vertical grid + X labels
        for (tick in xTicks) {
            val x = layout.mapX(tick.ts)
            // Grid line (always, thicker for midnight/date boundary)
            drawLine(
                color = if (tick.isMidnight) midnightGridColor else gridColor,
                start = Offset(x, layout.chartTop),
                end = Offset(x, layout.chartBottom),
                strokeWidth = if (tick.isMidnight) gridStroke * 2f else gridStroke
            )
            // X label (below chart, only if showLabel)
            if (tick.showLabel && tick.label != null) {
                val textResult = textMeasurer.measure(tick.label, labelStyle)
                drawText(
                    textLayoutResult = textResult,
                    topLeft = Offset(
                        x - textResult.size.width / 2f,
                        layout.chartBottom + innerPad + xLabelGap
                    )
                )
            }
        }

        // 2. Clip to chart area for band + lines
        clipRect(
            left = layout.chartLeft,
            top = layout.chartTop,
            right = layout.chartRight,
            bottom = layout.chartBottom
        ) {
            // 3. Band (single source)
            for (sp in sourcePaths) {
                if (sp.bandPath != null) {
                    drawPath(
                        path = sp.bandPath,
                        color = sp.source.color.copy(alpha = 0.35f)
                    )
                }
            }

            // 4. Close lines
            for (sp in sourcePaths) {
                drawPath(
                    path = sp.closePath,
                    color = sp.source.color,
                    style = Stroke(width = lineStroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

// ============ Data computation (pure functions) ============

private data class ChartState(
    val xMin: Int,
    val xMax: Int,
    val yMin: Double,
    val yMax: Double,
    val lastDataTs: Int
)

private fun computeChartState(
    graphData: Map<GraphSource, List<GraphBucket>>,
    selectedSources: Set<GraphSource>
): ChartState? {
    val allBuckets = selectedSources.flatMap { source ->
        graphData[source] ?: emptyList()
    }
    if (allBuckets.isEmpty()) return null

    val allTs = allBuckets.map { it.bucketTs }
    val xMin = allTs.min()
    val lastDataTs = allTs.max()
    val xMax = lastDataTs + 1800  // 30-minute trailing buffer

    // Y domain: close values only (iOS yAxisRange)
    val allCloses = allBuckets.map { it.close }
    val minClose = allCloses.min()
    val maxClose = allCloses.max()
    val range = maxClose - minClose
    val margin5pct = range * 0.05
    val rangeMin = minClose - margin5pct
    val rangeMax = maxClose + margin5pct
    val yMin = rangeMin - 0.8
    val yMax = rangeMax + 0.8

    return ChartState(xMin, xMax, yMin, yMax, lastDataTs)
}

private fun computeXTicks(xMin: Int, xMax: Int, lastDataTs: Int): List<XTick> {
    val kst = TimeZone.of("Asia/Seoul")
    val ticks = mutableListOf<XTick>()

    // Find first 3-hour tick >= xMin
    val startDt = Instant.fromEpochSeconds(xMin.toLong()).toLocalDateTime(kst)
    val hourAligned = (startDt.hour / 3) * 3
    // Build first tick datetime
    val tickDt = kotlinx.datetime.LocalDateTime(
        startDt.year, startDt.monthNumber, startDt.dayOfMonth,
        hourAligned, 0, 0
    )
    val tickInstant = tickDt.toInstant(kst)
    var tickTs = tickInstant.epochSeconds.toInt()

    // If tickTs < xMin, advance to next 3-hour mark
    if (tickTs < xMin) {
        tickTs += 3 * 3600
    }

    while (tickTs <= xMax) {
        val dt = Instant.fromEpochSeconds(tickTs.toLong()).toLocalDateTime(kst)
        val label = if (dt.hour == 0) {
            "${dt.monthNumber}/${dt.dayOfMonth}"
        } else {
            "%02d".format(dt.hour)
        }

        // Label visibility rules (iOS shouldDisplayXAxisLabel)
        val showLabel = tickTs <= lastDataTs && (lastDataTs - tickTs) >= 600

        ticks.add(XTick(ts = tickTs, label = label, showLabel = showLabel, isMidnight = dt.hour == 0))
        tickTs += 3 * 3600
    }

    return ticks
}

private fun computeYTicks(
    yMin: Double,
    yMax: Double,
    plotHeightPx: Float,
    labelHeightPx: Float
): List<YTick> {
    if (yMax <= yMin || plotHeightPx <= 0f) {
        return listOf(YTick(value = yMin, label = "%.1f".format(yMin)))
    }

    // iOS Swift Charts와 유사하게 6-7개 눈금 목표
    val minSpacing = labelHeightPx * 1.3f  // 1.6 → 1.3: 더 촘촘하게
    val maxTicks = (kotlin.math.floor(plotHeightPx / minSpacing.toDouble()).toInt() + 1)
        .coerceAtLeast(4)
    val targetTicks = maxTicks.coerceIn(5, 8)  // 4-5 → 5-8: iOS와 유사하게

    var step = niceStep((yMax - yMin) / (targetTicks - 1))
    var ticks = buildYTicks(yMin, yMax, step)

    // 너무 많은 경우에만 step 증가 (iOS는 관대함)
    while (ticks.size > maxTicks + 2) {
        step = nextNiceStep(step)
        ticks = buildYTicks(yMin, yMax, step)
    }

    return ticks
}

private fun buildYTicks(yMin: Double, yMax: Double, step: Double): List<YTick> {
    val start = kotlin.math.floor(yMin / step) * step
    val end = kotlin.math.ceil(yMax / step) * step
    val ticks = mutableListOf<YTick>()
    var value = start
    while (value <= end + 1e-9) {
        ticks.add(YTick(value = value, label = "%.1f".format(value)))
        value += step
    }
    return ticks
}

private fun niceStep(rawStep: Double): Double {
    if (rawStep <= 0.0) return 1.0
    val exponent = kotlin.math.floor(kotlin.math.log10(rawStep))
    val scale = 10.0.pow(exponent)
    val base = rawStep / scale
    // iOS Swift Charts처럼 step=2.0을 더 선호하도록 조정
    // 2.5 제거: base <= 3.0까지 2.0 사용
    val niceBase = if (base <= 1.0) 1.0
        else if (base <= 3.0) 2.0  // 2.0, 2.5 → 2.0 통합 (iOS 스타일)
        else if (base <= 6.0) 5.0  // 5.0 범위 확장
        else 10.0
    return niceBase * scale
}

private fun nextNiceStep(step: Double): Double {
    if (step <= 0.0) return 1.0
    val exponent = kotlin.math.floor(kotlin.math.log10(step))
    val scale = 10.0.pow(exponent)
    val base = step / scale
    return if (base <= 1.0) 2.0 * scale
        else if (base <= 2.0) 2.5 * scale
        else if (base <= 2.5) 5.0 * scale
        else if (base <= 5.0) 10.0 * scale
        else 10.0.pow(exponent + 1)
}

private fun computeSourcePaths(
    graphData: Map<GraphSource, List<GraphBucket>>,
    selectedSources: Set<GraphSource>,
    layout: ChartLayout,
    isSingleSource: Boolean
): List<SourcePaths> {
    return selectedSources.sortedBy { it.ordinal }.mapNotNull { source ->
        val buckets = graphData[source]?.sortedBy { it.bucketTs } ?: return@mapNotNull null
        if (buckets.isEmpty()) return@mapNotNull null

        // Close line path
        val closePath = Path().apply {
            buckets.forEachIndexed { i, bucket ->
                val x = layout.mapX(bucket.bucketTs)
                val y = layout.mapY(bucket.close)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        // Band path (single source only)
        val bandPath = if (isSingleSource) {
            Path().apply {
                // Max line: left to right
                buckets.forEachIndexed { i, bucket ->
                    val x = layout.mapX(bucket.bucketTs)
                    val y = layout.mapY(bucket.max)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                // Min line: right to left
                buckets.reversed().forEach { bucket ->
                    val x = layout.mapX(bucket.bucketTs)
                    val y = layout.mapY(bucket.min)
                    lineTo(x, y)
                }
                close()
            }
        } else null

        SourcePaths(source = source, closePath = closePath, bandPath = bandPath)
    }
}
