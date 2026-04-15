package com.jay.fxi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

@Composable
fun RateGraphView(
    rateGraphData: Map<GraphSource, List<GraphBucket>>,
    dxyGraphData: List<GraphBucket>,
    period: GraphPeriod,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    Box(
        modifier = modifier.height(metrics.graphHeight),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading && rateGraphData.isEmpty() && dxyGraphData.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(color = SecondaryText, strokeWidth = 2.dp)
                    Text(text = "그래프 로딩 중...", color = SecondaryText, fontSize = 12.sp)
                }
            }

            rateGraphData.isEmpty() && dxyGraphData.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.height(28.dp)
                    )
                    Text(text = "그래프 데이터가 없습니다", color = SecondaryText, fontSize = 12.sp)
                }
            }

            else -> {
                RateGraphCanvas(
                    rateGraphData = rateGraphData,
                    dxyGraphData = dxyGraphData,
                    period = period,
                    verticalPadding = metrics.graphVerticalPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

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
    fun mapX(ts: Int): Float {
        if (xMax == xMin) return chartLeft
        return chartLeft + (ts - xMin).toFloat() / (xMax - xMin) * (chartRight - chartLeft)
    }

    fun mapY(value: Double): Float {
        if (yMax == yMin) return chartTop + (chartBottom - chartTop) / 2f
        return chartBottom - ((value - yMin) / (yMax - yMin) * (chartBottom - chartTop)).toFloat()
    }
}

private data class ChartState(
    val xMin: Int,
    val xMax: Int,
    val yMin: Double,
    val yMax: Double,
    val rateRangeMin: Double,
    val rateRangeMax: Double,
    val lastDataTs: Int,
    val hasDxy: Boolean,
    val dxyMin: Double?,
    val dxyMax: Double?,
    val isDxyFlat: Boolean,
    val flatDxyValue: Double?
)

private data class XTick(
    val ts: Int,
    val label: String?,
    val showLabel: Boolean,
    val isMidnight: Boolean = false
)

private data class YTick(
    val value: Double,
    val label: String
)

private data class DxyLabel(
    val value: Double,
    val label: String
)

private data class SourcePath(
    val source: GraphSource,
    val path: Path,
    val bandPath: Path? = null
)

@Composable
private fun RateGraphCanvas(
    rateGraphData: Map<GraphSource, List<GraphBucket>>,
    dxyGraphData: List<GraphBucket>,
    period: GraphPeriod,
    verticalPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(fontSize = 11.sp, color = SecondaryText, fontFeatureSettings = "tnum")
    }
    val dxyLabelStyle = remember {
        TextStyle(fontSize = 10.sp, color = GraphSource.DXY.color.copy(alpha = 0.85f), fontFeatureSettings = "tnum")
    }

    // MARK: - PoC: Graph zoom (1d period only)
    // See GRAPH_ZOOM_DESIGN.md §4. Scope: pinch-to-zoom only, anchored to right edge.
    // Excluded from PoC v1: pan, reset gesture, isInteracting lock, fling.
    var pocVisibleLengthSec by remember { mutableStateOf<Long?>(null) }

    // 기간 변경 시 줌 리셋 (설계서 §3)
    LaunchedEffect(period) {
        pocVisibleLengthSec = null
    }

    // 전체 데이터의 시간 폭 (초)
    val totalLengthSec = remember(rateGraphData, dxyGraphData) {
        val all = rateGraphData.values.flatten() + dxyGraphData
        if (all.isEmpty()) 86_400L
        else (all.maxOf { it.bucketTs } - all.minOf { it.bucketTs }).toLong().coerceAtLeast(1L)
    }

    // 줌 상태의 visible window (1d만, 오른쪽 edge 앵커)
    val pocVisibleWindow: IntRange? = remember(pocVisibleLengthSec, rateGraphData, dxyGraphData, period) {
        if (period != GraphPeriod.ONE_DAY) return@remember null
        val visLen = pocVisibleLengthSec ?: return@remember null
        val all = rateGraphData.values.flatten() + dxyGraphData
        val lastTs = all.maxOfOrNull { it.bucketTs } ?: return@remember null
        val start = (lastTs - visLen).toInt()
        start..lastTs
    }

    val chartState = remember(rateGraphData, dxyGraphData, period, pocVisibleWindow) {
        computeChartState(rateGraphData, dxyGraphData, period, pocVisibleWindow)
    } ?: return

    Canvas(
        // PoC: HorizontalPager와의 제스처 공존을 위해 detectTransformGestures 대신
        // awaitEachGesture 수동 루프를 사용. 2+ 포인터에서만 consume하여
        // 1-손가락 드래그는 Pager로 그대로 전파. (G1 검증 목적)
        modifier = modifier.pointerInput(period, totalLengthSec) {
            if (period != GraphPeriod.ONE_DAY) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val activePointers = event.changes.count { it.pressed }
                    if (activePointers >= 2) {
                        val zoomChange = event.calculateZoom()
                        if (zoomChange != 1f) {
                            val current = pocVisibleLengthSec ?: totalLengthSec
                            val newLength = (current / zoomChange).toLong()
                                .coerceIn(3_600L, totalLengthSec)
                            pocVisibleLengthSec =
                                if (newLength >= totalLengthSec) null else newLength
                            // 2+ finger pinch만 consume — 1-finger pan은 Pager로 pass through
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        val leftPad = with(density) { if (chartState.hasDxy) 34.dp.toPx() else 8.dp.toPx() }
        val rightPad = with(density) { 40.dp.toPx() }
        val bottomPad = with(density) { 16.dp.toPx() }
        val innerPad = with(density) { verticalPadding.toPx() }
        val gridStroke = with(density) { 0.5.dp.toPx() }
        val yLabelGap = with(density) { 4.dp.toPx() }

        val layout = ChartLayout(
            chartLeft = leftPad,
            chartTop = innerPad,
            chartRight = size.width - rightPad,
            chartBottom = size.height - bottomPad - innerPad,
            xMin = chartState.xMin,
            xMax = chartState.xMax,
            yMin = chartState.yMin,
            yMax = chartState.yMax
        )

        val xTicks = computeXTicks(period, layout.xMin, layout.xMax, chartState.lastDataTs)
        val yTicks = computeYTicks(layout.yMin, layout.yMax)
        val sourcePaths = computeRatePaths(rateGraphData, layout, period, chartState.hasDxy)
        val dxyPath = if (chartState.hasDxy && chartState.dxyMin != null && chartState.dxyMax != null) {
            computeDxyPath(dxyGraphData, layout, chartState.rateRangeMin, chartState.rateRangeMax, chartState.dxyMin, chartState.dxyMax)
        } else {
            null
        }

        val gridColor = SecondaryText.copy(alpha = 0.3f)
        val midnightGridColor = SecondaryText.copy(alpha = 0.4f)

        val yTickEpsilon = (layout.yMax - layout.yMin) * 1e-9
        for (tick in yTicks) {
            if (tick.value <= layout.yMin + yTickEpsilon) continue
            if (tick.value >= layout.yMax - yTickEpsilon) continue
            val y = layout.mapY(tick.value)
            drawLine(
                color = gridColor,
                start = Offset(layout.chartLeft, y),
                end = Offset(layout.chartRight, y),
                strokeWidth = gridStroke
            )
            val textResult = textMeasurer.measure(tick.label, labelStyle)
            drawText(
                textLayoutResult = textResult,
                topLeft = Offset(layout.chartRight + yLabelGap, y - textResult.size.height / 2f)
            )
        }

        for (tick in xTicks) {
            val x = layout.mapX(tick.ts)
            if (x < layout.chartLeft || x > layout.chartRight) continue
            drawLine(
                color = if (tick.isMidnight) midnightGridColor else gridColor,
                start = Offset(x, layout.chartTop),
                end = Offset(x, layout.chartBottom),
                strokeWidth = if (tick.isMidnight) gridStroke * 2f else gridStroke
            )
            if (tick.showLabel && tick.label != null) {
                val textResult = textMeasurer.measure(tick.label, labelStyle)
                drawText(
                    textLayoutResult = textResult,
                    topLeft = Offset(x - textResult.size.width / 2f, layout.chartBottom + innerPad + with(density) { 1.dp.toPx() })
                )
            }
        }

        if (chartState.hasDxy && chartState.dxyMin != null && chartState.dxyMax != null) {
            if (chartState.isDxyFlat && chartState.flatDxyValue != null) {
                val y = layout.mapY(
                    normalizeDxyValue(
                        chartState.flatDxyValue,
                        chartState.rateRangeMin,
                        chartState.rateRangeMax,
                        chartState.dxyMin,
                        chartState.dxyMax
                    )
                )
                val textResult = textMeasurer.measure(formatDxyValue(chartState.flatDxyValue), dxyLabelStyle)
                drawText(
                    textLayoutResult = textResult,
                    topLeft = Offset(0f, y - textResult.size.height / 2f)
                )
            } else {
                for (label in generateDxyLabels(chartState.dxyMin, chartState.dxyMax)) {
                    val normalized = normalizeDxyValue(
                        label.value,
                        chartState.rateRangeMin,
                        chartState.rateRangeMax,
                        chartState.dxyMin,
                        chartState.dxyMax
                    )
                    val y = layout.mapY(normalized)
                    val textResult = textMeasurer.measure(label.label, dxyLabelStyle)
                    drawText(
                        textLayoutResult = textResult,
                        topLeft = Offset(0f, y - textResult.size.height / 2f)
                    )
                }
            }
        }

        clipRect(
            left = layout.chartLeft,
            top = layout.chartTop,
            right = layout.chartRight,
            bottom = layout.chartBottom
        ) {
            sourcePaths.forEach { sourcePath ->
                sourcePath.bandPath?.let { band ->
                    drawPath(path = band, color = sourcePath.source.color.copy(alpha = 0.35f))
                }
            }

            sourcePaths.forEach { sourcePath ->
                drawPath(
                    path = sourcePath.path,
                    color = sourcePath.source.color,
                    style = Stroke(
                        width = if (sourcePath.source == GraphSource.INVESTING || sourcePath.source == GraphSource.REFERENCE) 1.3.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }

            if (dxyPath != null) {
                drawPath(
                    path = dxyPath,
                    color = GraphSource.DXY.color.copy(alpha = 0.75f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

private fun computeChartState(
    rateGraphData: Map<GraphSource, List<GraphBucket>>,
    dxyGraphData: List<GraphBucket>,
    period: GraphPeriod,
    visibleWindow: IntRange? = null  // PoC: 1d 줌 상태에서만 non-null
): ChartState? {
    val rateBuckets = rateGraphData.values.flatten()
    val allBuckets = if (rateBuckets.isNotEmpty()) rateBuckets + dxyGraphData else dxyGraphData
    if (allBuckets.isEmpty()) return null

    // PoC: visible window이 설정되면 y-range 계산용 버킷을 필터링
    val visibleRateBuckets = visibleWindow?.let { w ->
        rateBuckets.filter { it.bucketTs in w }.ifEmpty { rateBuckets }
    } ?: rateBuckets
    val visibleDxyBuckets = visibleWindow?.let { w ->
        dxyGraphData.filter { it.bucketTs in w }.ifEmpty { dxyGraphData }
    } ?: dxyGraphData

    val allTs = allBuckets.map { it.bucketTs }
    val fullXMin = allTs.min()
    val lastDataTs = allTs.max()

    // x축 범위: visible window 설정 시 그 범위로, 아니면 전체 + trailing buffer
    val xMin = visibleWindow?.first ?: fullXMin
    val xMax = visibleWindow?.last ?: (lastDataTs + trailingBufferSec(period))

    val rateCloses = visibleRateBuckets.map { it.close }
    val fallbackCloses = visibleDxyBuckets.map { it.close }
    val targetCloses = if (rateCloses.isNotEmpty()) rateCloses else fallbackCloses
    if (targetCloses.isEmpty()) return null
    val minClose = targetCloses.min()
    val maxClose = targetCloses.max()
    val margin = (maxClose - minClose) * 0.05
    val paddedMin = minClose - margin
    val paddedMax = maxClose + margin
    val yDomainMargin = 0.8

    val hasDxy = visibleDxyBuckets.isNotEmpty()
    val dxyCloses = visibleDxyBuckets.map { it.close }
    val rawDxyMin = dxyCloses.minOrNull()
    val rawDxyMax = dxyCloses.maxOrNull()
    val dxyMargin = if (rawDxyMin != null && rawDxyMax != null) (rawDxyMax - rawDxyMin) * 0.05 else 0.0
    val dxyMin = rawDxyMin?.let { it - dxyMargin }
    val dxyMax = rawDxyMax?.let { it + dxyMargin }
    val flatValue = dxyCloses.firstOrNull()?.let { first ->
        val roundedFirst = (first * 100).roundToNearestStep(1.0) / 100.0
        if (dxyCloses.all { abs(((it * 100).roundToNearestStep(1.0) / 100.0) - roundedFirst) < 0.0001 }) {
            roundedFirst
        } else {
            null
        }
    }

    return ChartState(
        xMin = xMin,
        xMax = xMax,
        yMin = paddedMin - yDomainMargin,
        yMax = paddedMax + yDomainMargin,
        rateRangeMin = paddedMin,
        rateRangeMax = paddedMax,
        lastDataTs = lastDataTs,
        hasDxy = hasDxy,
        dxyMin = dxyMin,
        dxyMax = dxyMax,
        isDxyFlat = flatValue != null,
        flatDxyValue = flatValue
    )
}

private fun computeXTicks(
    period: GraphPeriod,
    xMin: Int,
    xMax: Int,
    lastDataTs: Int
): List<XTick> {
    val kst = TimeZone.of("Asia/Seoul")
    return when (period) {
        GraphPeriod.ONE_DAY -> {
            val ticks = mutableListOf<XTick>()
            val startDt = Instant.fromEpochSeconds(xMin.toLong()).toLocalDateTime(kst)
            val hourAligned = (startDt.hour / 3) * 3
            val tickDt = kotlinx.datetime.LocalDateTime(
                startDt.year, startDt.monthNumber, startDt.dayOfMonth, hourAligned, 0, 0
            )
            var tickTs = tickDt.toInstant(kst).epochSeconds.toInt()
            if (tickTs < xMin) tickTs += 3 * 3600
            while (tickTs <= xMax) {
                val dt = Instant.fromEpochSeconds(tickTs.toLong()).toLocalDateTime(kst)
                ticks += XTick(
                    ts = tickTs,
                    label = if (dt.hour == 0) "${dt.monthNumber}/${dt.dayOfMonth}" else "%02d".format(dt.hour),
                    showLabel = tickTs <= lastDataTs && (lastDataTs - tickTs) >= 600,
                    isMidnight = dt.hour == 0
                )
                tickTs += 3 * 3600
            }
            ticks
        }

        GraphPeriod.ONE_WEEK -> generateDayTicks(xMin, xMax, lastDataTs, 1)
        GraphPeriod.THREE_MONTHS -> generateDayTicks(xMin, xMax, lastDataTs, 14)
        GraphPeriod.ONE_YEAR -> generateMonthTicks(xMin, xMax, lastDataTs)
    }
}

private fun generateDayTicks(
    xMin: Int,
    xMax: Int,
    lastDataTs: Int,
    intervalDays: Int
): List<XTick> {
    val kst = TimeZone.of("Asia/Seoul")
    val ticks = mutableListOf<XTick>()
    val startDt = Instant.fromEpochSeconds(xMin.toLong()).toLocalDateTime(kst)
    val startOfDay = kotlinx.datetime.LocalDateTime(
        startDt.year, startDt.monthNumber, startDt.dayOfMonth, 0, 0, 0
    ).toInstant(kst)
    var tickTs = startOfDay.epochSeconds.toInt()
    while (tickTs <= xMax) {
        val dt = Instant.fromEpochSeconds(tickTs.toLong()).toLocalDateTime(kst)
        val isFirstTick = tickTs == startOfDay.epochSeconds.toInt()
        ticks += XTick(
            ts = tickTs,
            label = "${dt.monthNumber}/${dt.dayOfMonth}",
            showLabel = !isFirstTick && tickTs <= lastDataTs &&
                (lastDataTs - tickTs) >= if (intervalDays == 1) 6 * 3600 else 3 * 86_400,
            isMidnight = true
        )
        tickTs += intervalDays * 86_400
    }
    return ticks
}

private fun generateMonthTicks(
    xMin: Int,
    xMax: Int,
    lastDataTs: Int
): List<XTick> {
    val kst = TimeZone.of("Asia/Seoul")
    val ticks = mutableListOf<XTick>()
    val startDt = Instant.fromEpochSeconds(xMin.toLong()).toLocalDateTime(kst)
    val alignedMonth = if (startDt.monthNumber % 2 == 0) startDt.monthNumber else max(1, startDt.monthNumber - 1)
    var current = kotlinx.datetime.LocalDateTime(startDt.year, alignedMonth, 1, 0, 0, 0).toInstant(kst)
    val firstTs = current.epochSeconds.toInt()
    while (current.epochSeconds.toInt() <= xMax) {
        val dt = current.toLocalDateTime(kst)
        val tickTs = current.epochSeconds.toInt()
        ticks += XTick(
            ts = tickTs,
            label = "${dt.monthNumber}월",
            showLabel = tickTs != firstTs && tickTs <= lastDataTs && (lastDataTs - tickTs) >= 10 * 86_400,
            isMidnight = true
        )
        val nextMonth = dt.monthNumber + 2
        val nextYear = dt.year + (nextMonth - 1) / 12
        val normalizedMonth = ((nextMonth - 1) % 12) + 1
        current = kotlinx.datetime.LocalDateTime(nextYear, normalizedMonth, 1, 0, 0, 0).toInstant(kst)
    }
    return ticks
}

private fun computeYTicks(yMin: Double, yMax: Double): List<YTick> {
    if (yMax <= yMin) return listOf(YTick(yMin, "%.1f".format(yMin)))

    val desiredCountHint = 4
    val span = yMax - yMin
    val rawStep = span / desiredCountHint
    var step = d3LikeNiceStep(rawStep)
    val maxInteriorTickCount = 5
    var interiorTickCount = interiorTickCountForNiceStep(yMin, yMax, step)
    while (interiorTickCount > maxInteriorTickCount) {
        step = next12510Step(step)
        interiorTickCount = interiorTickCountForNiceStep(yMin, yMax, step)
    }
    return buildYTicks(yMin, yMax, step)
}

private fun buildYTicks(yMin: Double, yMax: Double, step: Double): List<YTick> {
    if (step <= 0.0) return listOf(YTick(yMin, "%.1f".format(yMin)))
    val startIndex = floor(yMin / step).toLong()
    val endIndex = ceil(yMax / step).toLong()
    val ticks = mutableListOf<YTick>()
    var i = startIndex
    while (i <= endIndex) {
        val value = i.toDouble() * step
        ticks += YTick(value, "%.1f".format(value))
        i += 1
    }
    return ticks
}

private fun interiorTickCountForNiceStep(yMin: Double, yMax: Double, step: Double): Int {
    if (step <= 0.0 || yMax <= yMin) return 0
    val span = yMax - yMin
    val eps = span * 1e-9
    val startIndex = floor(yMin / step).toLong()
    val endIndex = ceil(yMax / step).toLong()
    var count = 0
    var i = startIndex
    while (i <= endIndex) {
        val value = i.toDouble() * step
        if (value > yMin + eps && value < yMax - eps) count += 1
        i += 1
    }
    return count
}

private fun d3LikeNiceStep(rawStep: Double): Double {
    if (rawStep <= 0.0) return 1.0
    val exponent = floor(log10(rawStep))
    val scale = 10.0.pow(exponent)
    val error = rawStep / scale
    val base = when {
        error >= sqrt(50.0) -> 10.0
        error >= sqrt(10.0) -> 5.0
        error >= sqrt(2.0) -> 2.0
        else -> 1.0
    }
    return base * scale
}

private fun next12510Step(step: Double): Double {
    if (step <= 0.0) return 1.0
    val exponent = floor(log10(step))
    val scale = 10.0.pow(exponent)
    val base = step / scale
    val nextBase = when {
        base <= 1.0 -> 2.0
        base <= 2.0 -> 5.0
        base <= 5.0 -> 10.0
        else -> 10.0
    }
    return nextBase * scale
}

private fun computeRatePaths(
    rateGraphData: Map<GraphSource, List<GraphBucket>>,
    layout: ChartLayout,
    period: GraphPeriod,
    hasDxy: Boolean
): List<SourcePath> {
    val isSingleSource = period == GraphPeriod.ONE_DAY && rateGraphData.size == 1 && !hasDxy
    return rateGraphData.keys.sortedBy { it.ordinal }.mapNotNull { source ->
        val buckets = rateGraphData[source]?.sortedBy { it.bucketTs } ?: return@mapNotNull null
        if (buckets.isEmpty()) return@mapNotNull null

        val path = Path().apply {
            buckets.forEachIndexed { index, bucket ->
                val x = layout.mapX(bucket.bucketTs)
                val y = layout.mapY(bucket.close)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        val bandPath = if (isSingleSource) {
            Path().apply {
                buckets.forEachIndexed { index, bucket ->
                    val x = layout.mapX(bucket.bucketTs)
                    val y = layout.mapY(bucket.max)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                buckets.asReversed().forEach { bucket ->
                    val x = layout.mapX(bucket.bucketTs)
                    val y = layout.mapY(bucket.min)
                    lineTo(x, y)
                }
                close()
            }
        } else {
            null
        }
        SourcePath(source = source, path = path, bandPath = bandPath)
    }
}

private fun computeDxyPath(
    dxyBuckets: List<GraphBucket>,
    layout: ChartLayout,
    rateRangeMin: Double,
    rateRangeMax: Double,
    dxyMin: Double,
    dxyMax: Double
): Path? {
    val sorted = dxyBuckets.sortedBy { it.bucketTs }
    if (sorted.isEmpty()) return null

    return Path().apply {
        sorted.forEachIndexed { index, bucket ->
            val x = layout.mapX(bucket.bucketTs)
            val normalized = normalizeDxyValue(bucket.close, rateRangeMin, rateRangeMax, dxyMin, dxyMax)
            val y = layout.mapY(normalized)
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}

private fun normalizeDxyValue(
    dxyValue: Double,
    rateMin: Double,
    rateMax: Double,
    dxyMin: Double,
    dxyMax: Double
): Double {
    val dxySpan = dxyMax - dxyMin
    val rateSpan = rateMax - rateMin
    if (dxySpan <= 0.0 || rateSpan <= 0.0) return rateMin
    return rateMin + (dxyValue - dxyMin) / dxySpan * rateSpan
}

private fun generateDxyLabels(dxyMin: Double, dxyMax: Double): List<DxyLabel> {
    val span = dxyMax - dxyMin
    if (span <= 0.0) return emptyList()
    val rawStep = span / 4.0
    val step = when {
        rawStep < 0.08 -> 0.05
        rawStep < 0.15 -> 0.1
        rawStep < 0.35 -> 0.2
        rawStep < 0.75 -> 0.5
        rawStep < 1.5 -> 1.0
        else -> 2.0
    }
    val format = if (step < 0.1) "%.2f" else "%.1f"
    val inset = span * 0.05
    val start = ceil((dxyMin + inset) / step) * step
    val end = floor((dxyMax - inset) / step) * step
    val labels = mutableListOf<DxyLabel>()
    var value = start
    var previous: String? = null
    while (value <= end + step * 0.01) {
        val normalized = (value / step).roundToNearestStep(1.0) * step
        val text = String.format(format, normalized)
        if (text != previous) {
            labels += DxyLabel(normalized, text)
            previous = text
        }
        value += step
    }
    return labels
}

private fun formatDxyValue(value: Double): String {
    return if (abs(value % 0.1) > 0.001) "%.2f".format(value) else "%.1f".format(value)
}

private fun trailingBufferSec(period: GraphPeriod): Int = when (period) {
    GraphPeriod.ONE_DAY -> 2_400
    GraphPeriod.ONE_WEEK -> 3_600
    GraphPeriod.THREE_MONTHS -> 86_400
    GraphPeriod.ONE_YEAR -> 86_400 * 3
}

private fun Double.roundToNearestStep(step: Double): Double = if (step == 0.0) this else kotlin.math.round(this / step) * step
