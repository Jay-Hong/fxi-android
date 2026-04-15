package com.jay.fxi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
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
import androidx.compose.runtime.rememberUpdatedState
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

    // MARK: - Graph zoom state (1d period only)
    // See GRAPH_ZOOM_DESIGN.md §5. M1 상태 모델 전환 후 구조:
    //   - pocVisibleDomain: 사용자 raw 입력 (free window, ClosedRange<Long>, 단위 초 epoch)
    //     null = 기본 보기 (전체 + 자연 follow-latest)
    //     non-null = 명시적 visible window
    //   - pocIsFollowingLatest: 기본 true. 제스처 중 freeze, 제스처 ended 후 reevaluate (M2+)
    //   - resolvedVisibleDomain: follow 반영된 파생 domain (chart 렌더 + 필터 single source)
    // Scope (parity baseline): pinch-to-zoom only, 내부적으로 right-edge anchor.
    // M2부터 finger-centered pinch + 1-finger pan 추가 예정.
    var pocVisibleDomain by remember { mutableStateOf<ClosedRange<Long>?>(null) }
    var pocIsFollowingLatest by remember { mutableStateOf(true) }

    // M2: Gesture baseline (iOS pocHandlePinch/PanBegan 등가)
    // pinch: began에서 한 번 캡처 + changed에서 finger-centered 계산용
    var pocPinchBaselineDomain: ClosedRange<Long>? by remember { mutableStateOf(null) }
    var pocPinchAnchorTimeSec: Long? by remember { mutableStateOf(null) }
    var pocPinchAnchorFraction: Float by remember { mutableStateOf(0.5f) }
    // pan: began에서 baseline 캡처 + changed에서 translation 기반 이동
    var pocPanBaselineDomain: ClosedRange<Long>? by remember { mutableStateOf(null) }

    // 기간 변경 시 줌/follow 상태 리셋 (설계서 §4 M1/M2)
    LaunchedEffect(period) {
        pocVisibleDomain = null
        pocIsFollowingLatest = true
        pocPinchBaselineDomain = null
        pocPinchAnchorTimeSec = null
        pocPanBaselineDomain = null
    }

    // 전체 데이터의 시간 폭 (초)
    val totalLengthSec = remember(rateGraphData, dxyGraphData) {
        val all = rateGraphData.values.flatten() + dxyGraphData
        if (all.isEmpty()) 86_400L
        else (all.maxOf { it.bucketTs } - all.minOf { it.bucketTs }).toLong().coerceAtLeast(1L)
    }

    // 최신 데이터 시점 (follow-latest anchor)
    val lastDataTs: Long? = remember(rateGraphData, dxyGraphData) {
        val all = rateGraphData.values.flatten() + dxyGraphData
        all.maxOfOrNull { it.bucketTs }?.toLong()
    }

    // M2: 데이터 전체 경계 (setVisibleDomain clamp에 필요)
    val dataBounds: ClosedRange<Long>? = remember(rateGraphData, dxyGraphData) {
        val all = rateGraphData.values.flatten() + dxyGraphData
        if (all.isEmpty()) null
        else all.minOf { it.bucketTs }.toLong()..all.maxOf { it.bucketTs }.toLong()
    }

    // 파생: follow 모드 반영된 실제 표시 domain.
    // chart 렌더, visible 필터, gesture baseline 전부의 single source of truth.
    // 원칙: raw pocVisibleDomain 직접 참조 금지, 이 파생값을 사용.
    val resolvedVisibleDomain: ClosedRange<Long>? = remember(
        pocVisibleDomain, pocIsFollowingLatest, lastDataTs, period
    ) {
        if (period != GraphPeriod.ONE_DAY) return@remember null
        val raw = pocVisibleDomain ?: return@remember null
        if (pocIsFollowingLatest && lastDataTs != null) {
            val length = raw.endInclusive - raw.start
            (lastDataTs - length)..lastDataTs
        } else raw
    }

    val chartState = remember(rateGraphData, dxyGraphData, period, resolvedVisibleDomain) {
        computeChartState(rateGraphData, dxyGraphData, period, resolvedVisibleDomain)
    } ?: return

    // M2 fix: pointerInput(period, totalLengthSec)는 제스처 도중 재시작 방지를 위해 key를 최소화하므로,
    // coroutine 내부에서 참조하는 파생 state를 rememberUpdatedState로 감싸 최신 값 읽기를 보장한다.
    // (iOS UIKit gesture recognizer가 action block에서 최신 state를 참조하는 패턴과 등가)
    val currentLastDataTs by rememberUpdatedState(lastDataTs)
    val currentDataBounds by rememberUpdatedState(dataBounds)
    val currentResolvedVisibleDomain by rememberUpdatedState(resolvedVisibleDomain)
    val currentHasDxy by rememberUpdatedState(chartState.hasDxy)

    Canvas(
        // M2: finger-centered pinch + 1-finger pan (zoomed only) + pager arbitration (G1).
        // 전략:
        //   - 2+ pointer → pinch (centroid 기반 anchor), 항상 consume
        //   - 1 pointer + zoomed → pan, consume (pager에 전파 차단)
        //   - 1 pointer + not zoomed → pass through (pager swipe 정상)
        //   - gesture ended → follow-latest 재평가
        // iOS의 UIPanGestureRecognizer.isEnabled = pocIsZoomedOrPanned 토글과 등가.
        modifier = modifier.pointerInput(period, totalLengthSec) {
            if (period != GraphPeriod.ONE_DAY) return@pointerInput
            awaitEachGesture {
                val firstDown = awaitFirstDown(requireUnconsumed = false)
                // Gesture 시작 시 baseline 초기화 (이전 state 잔재 제거)
                pocPinchBaselineDomain = null
                pocPinchAnchorTimeSec = null
                pocPanBaselineDomain = null

                // plot 좌표 캐시 (hasDxy 변화 드물어 gesture 시작 시점 값 사용).
                // M2 fix: currentHasDxy는 rememberUpdatedState로 감싼 최신 값.
                val gestureLeftPad = if (currentHasDxy) 34.dp.toPx() else 8.dp.toPx()
                val gestureRightPad = 40.dp.toPx()
                val plotLeft = gestureLeftPad
                val plotWidth = (size.width - gestureLeftPad - gestureRightPad).coerceAtLeast(1f)

                // Pan 활성 여부 — 줌된 상태에서만 1-finger consume
                var gestureMode: GestureMode = GestureMode.UNDETERMINED
                var panReferencePoint: Offset = firstDown.position
                // M2 fix-3: pinch 누적 배율 (gesture-local).
                // calculateZoom()은 per-frame 증분이므로, baselineLen/perFrameZoom으로
                // 계산하면 누적이 되지 않고 baseline 근처에서 맴돌아 실질 줌이 발생하지 않는다.
                var pinchCumZoom: Float = 1f

                do {
                    val event = awaitPointerEvent()
                    val pressedPointers = event.changes.count { it.pressed }
                    if (pressedPointers == 0) break

                    when {
                        pressedPointers >= 2 -> {
                            // === Pinch (finger-centered) ===
                            if (gestureMode != GestureMode.PINCH) {
                                // Pinch began: baseline + anchor 캡처
                                // M2 fix-2: fallback은 computeChartState의 default xMax와 일치 (trailing buffer 포함).
                                val baseline: ClosedRange<Long>? =
                                    currentResolvedVisibleDomain
                                        ?: currentDataBounds?.let {
                                            it.start..(it.endInclusive + trailingBufferSec(period))
                                        }
                                if (baseline == null) {
                                    gestureMode = GestureMode.PINCH
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                pocVisibleDomain = baseline  // raw sync (follow off 대비)
                                pocIsFollowingLatest = false  // freeze during gesture
                                pocPinchBaselineDomain = baseline
                                pinchCumZoom = 1f  // M2 fix-3: 누적 배율 리셋

                                val centroid = event.calculateCentroid(useCurrent = true)
                                val xInPlot = (centroid.x - plotLeft).coerceIn(0f, plotWidth)
                                val fraction = (xInPlot / plotWidth).coerceIn(0f, 1f)
                                pocPinchAnchorFraction = fraction

                                val baselineLen = baseline.endInclusive - baseline.start
                                pocPinchAnchorTimeSec =
                                    baseline.start + (baselineLen * fraction).toLong()

                                gestureMode = GestureMode.PINCH
                            }

                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f) {
                                // M2 fix-3: 누적 배율 적용. calculateZoom()은 per-frame 증분.
                                pinchCumZoom = (pinchCumZoom * zoomChange).coerceIn(0.01f, 100f)
                                val baseline = pocPinchBaselineDomain
                                val anchor = pocPinchAnchorTimeSec
                                val bounds = currentDataBounds
                                val lastTs = currentLastDataTs
                                if (baseline != null && anchor != null && bounds != null && lastTs != null) {
                                    val baselineLen = baseline.endInclusive - baseline.start
                                    val rawNewLen = (baselineLen / pinchCumZoom).toLong()
                                    val fraction = pocPinchAnchorFraction
                                    val newStart = anchor - (rawNewLen * fraction).toLong()
                                    val newEnd = anchor + (rawNewLen * (1f - fraction)).toLong()
                                    val minBoundary = bounds.start
                                    val maxBoundary = lastTs + trailingBufferSec(period)
                                    pocVisibleDomain = clampVisibleDomain(
                                        newStart..newEnd,
                                        minBoundary,
                                        maxBoundary
                                    )
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }

                        pressedPointers == 1 -> {
                            // === 1-finger pan (zoomed state only) ===
                            if (gestureMode == GestureMode.PINCH) {
                                // Pinch → 1-finger 전환: pinch 종료, pan 시작 금지
                                gestureMode = GestureMode.DISCARDED
                                continue
                            }
                            if (gestureMode == GestureMode.DISCARDED) {
                                // pinch 여파라 이번 gesture cycle은 pan 무시
                                continue
                            }

                            val isZoomed = pocVisibleDomain != null
                            if (!isZoomed) {
                                // 기본 보기에서 1-finger는 pager에 양보 — consume 안 함
                                gestureMode = GestureMode.PASS_THROUGH
                                continue
                            }

                            if (gestureMode == GestureMode.UNDETERMINED) {
                                // Pan began: baseline 캡처 (M2 fix: 최신 resolved 사용)
                                val baseline: ClosedRange<Long>? = currentResolvedVisibleDomain
                                if (baseline == null) {
                                    gestureMode = GestureMode.PASS_THROUGH
                                    continue
                                }
                                pocVisibleDomain = baseline  // raw sync
                                pocIsFollowingLatest = false  // freeze
                                pocPanBaselineDomain = baseline
                                panReferencePoint = event.changes.first().position
                                gestureMode = GestureMode.PAN
                                // 첫 프레임은 consume만 하고 이동 계산은 다음 프레임부터
                                event.changes.forEach { it.consume() }
                            } else if (gestureMode == GestureMode.PAN) {
                                val baseline = pocPanBaselineDomain
                                val bounds = currentDataBounds
                                val lastTs = currentLastDataTs
                                if (baseline != null && bounds != null && lastTs != null) {
                                    val current = event.changes.first().position
                                    val translationX = current.x - panReferencePoint.x
                                    val baselineLen = baseline.endInclusive - baseline.start
                                    val timeDelta = (-translationX * baselineLen / plotWidth).toLong()
                                    val newStart = baseline.start + timeDelta
                                    val newEnd = baseline.endInclusive + timeDelta
                                    val minBoundary = bounds.start
                                    val maxBoundary = lastTs + trailingBufferSec(period)
                                    pocVisibleDomain = clampVisibleDomain(
                                        newStart..newEnd,
                                        minBoundary,
                                        maxBoundary
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })

                // === Gesture ended: full-unzoom 릴리스 + follow-latest 재평가 ===
                if (gestureMode == GestureMode.PINCH || gestureMode == GestureMode.PAN) {
                    pocPinchBaselineDomain = null
                    pocPinchAnchorTimeSec = null
                    pocPanBaselineDomain = null
                    val raw = pocVisibleDomain
                    val latest = currentLastDataTs
                    val bounds = currentDataBounds
                    if (raw != null && latest != null && bounds != null) {
                        val rawLen = raw.endInclusive - raw.start
                        // M2 fix-4: default view 길이 기준(= totalLength + trailingBuffer).
                        // totalLength 기준으로 판정하면 경계가 어긋나 의도치 않은 null 복귀 발생.
                        val unzoomedLen =
                            (bounds.endInclusive - bounds.start) + trailingBufferSec(period).toLong()
                        if (rawLen >= unzoomedLen * 99L / 100L) {
                            // 사실상 전체 보기 → default(null) + follow on
                            pocVisibleDomain = null
                            pocIsFollowingLatest = true
                        } else {
                            // 줌 유지 상태 → 최신 근접 여부로 follow 판정
                            val distance = kotlin.math.abs(raw.endInclusive - latest)
                            // iOS pocFollowThreshold 등가: bucketDuration(600s)
                            pocIsFollowingLatest = distance < 600L
                        }
                    }
                }
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

/// M2: Gesture 모드 — awaitEachGesture 루프 내 pointer count 전환을 추적.
/// UNDETERMINED: 첫 down 직후 (pinch/pan 결정 전)
/// PINCH: 2+ pointer 활성, finger-centered zoom 중
/// PAN: 1 pointer + zoomed 상태에서 pan 중
/// PASS_THROUGH: 1 pointer + not zoomed (pager에 양보)
/// DISCARDED: pinch → 1 pointer 전환 후 나머지 gesture 무시
private enum class GestureMode { UNDETERMINED, PINCH, PAN, PASS_THROUGH, DISCARDED }

/// M2 fix-3: visible domain clamp (경계 + 길이).
/// - minBoundary/maxBoundary: 허용 가능한 좌우 경계. maxBoundary는 default view의 xMax와 일치해야
///   하므로 caller는 `lastDataTs + trailingBufferSec(period)`를 넘겨야 한다.
/// - minLength: 최소 zoom 길이 (1h).
/// - 항상 non-null range 반환. "전체 복귀(null)" 판정은 caller의 gesture-end 단계에서만 수행한다.
///   (이전 구현은 프레임 중간에 null 반환 → 떨림/플리커 발생. 사용자 관찰로 확인됨.)
private fun clampVisibleDomain(
    proposed: ClosedRange<Long>,
    minBoundary: Long,
    maxBoundary: Long,
    minLength: Long = 3_600L
): ClosedRange<Long> {
    val maxLen = (maxBoundary - minBoundary).coerceAtLeast(minLength)
    val proposedLen = (proposed.endInclusive - proposed.start).coerceAtLeast(1L)
    val clampedLen = proposedLen.coerceIn(minLength, maxLen)

    val center = proposed.start + proposedLen / 2
    var start = center - clampedLen / 2
    var end = start + clampedLen

    if (start < minBoundary) {
        start = minBoundary
        end = start + clampedLen
    }
    if (end > maxBoundary) {
        end = maxBoundary
        start = end - clampedLen
    }
    if (start < minBoundary) start = minBoundary
    return start..end
}

/// M1 결과: yRange 계산 순수 함수 분리.
/// visible rate/dxy 버킷을 받아 파생값(패딩 포함 min/max) 반환.
/// fallback: rate 버킷이 비면 dxy 버킷의 close를 사용.
private data class YRange(
    val paddedMin: Double,  // rateRangeMin (DXY 정규화 기준)
    val paddedMax: Double,  // rateRangeMax
    val domainMin: Double,  // yMin (y축 domain, 추가 margin 포함)
    val domainMax: Double   // yMax
)

private fun computeYRange(
    visibleRateBuckets: List<GraphBucket>,
    visibleDxyBuckets: List<GraphBucket>
): YRange? {
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
    return YRange(
        paddedMin = paddedMin,
        paddedMax = paddedMax,
        domainMin = paddedMin - yDomainMargin,
        domainMax = paddedMax + yDomainMargin
    )
}

/// M1 결과: dxyRange 계산 순수 함수 분리.
/// visible dxy 버킷에서 min/max/flat 판정 반환. 빈 입력이면 null.
/// M4에서 yRange 정합성 패딩(iOS v2.3)까지 확장 예정.
private data class DxyRangeInfo(
    val dxyMin: Double,
    val dxyMax: Double,
    val isFlat: Boolean,
    val flatValue: Double?
)

private fun computeDxyRange(visibleDxyBuckets: List<GraphBucket>): DxyRangeInfo? {
    if (visibleDxyBuckets.isEmpty()) return null
    val dxyCloses = visibleDxyBuckets.map { it.close }
    val rawDxyMin = dxyCloses.min()
    val rawDxyMax = dxyCloses.max()
    val dxyMargin = (rawDxyMax - rawDxyMin) * 0.05
    val dxyMin = rawDxyMin - dxyMargin
    val dxyMax = rawDxyMax + dxyMargin
    val flatValue = dxyCloses.firstOrNull()?.let { first ->
        val roundedFirst = (first * 100).roundToNearestStep(1.0) / 100.0
        if (dxyCloses.all { abs(((it * 100).roundToNearestStep(1.0) / 100.0) - roundedFirst) < 0.0001 }) {
            roundedFirst
        } else {
            null
        }
    }
    return DxyRangeInfo(
        dxyMin = dxyMin,
        dxyMax = dxyMax,
        isFlat = flatValue != null,
        flatValue = flatValue
    )
}

/// M1 결과: computeChartState는 thin orchestrator.
/// visible 필터 → computeYRange → computeDxyRange → ChartState 조립.
/// visibleDomain: M1 이후 ClosedRange<Long>(epoch 초 Long 단위). null이면 전체 data 기반.
private fun computeChartState(
    rateGraphData: Map<GraphSource, List<GraphBucket>>,
    dxyGraphData: List<GraphBucket>,
    period: GraphPeriod,
    visibleDomain: ClosedRange<Long>? = null  // 1d 줌 상태에서만 non-null
): ChartState? {
    val rateBuckets = rateGraphData.values.flatten()
    val allBuckets = if (rateBuckets.isNotEmpty()) rateBuckets + dxyGraphData else dxyGraphData
    if (allBuckets.isEmpty()) return null

    // visible domain 설정 시 버킷 필터링 (y/dxy range 계산용).
    // 필터 결과 empty면 전체 fallback (iOS와 동일 정책).
    val visibleRateBuckets = visibleDomain?.let { d ->
        rateBuckets.filter { it.bucketTs.toLong() in d }.ifEmpty { rateBuckets }
    } ?: rateBuckets
    val visibleDxyBuckets = visibleDomain?.let { d ->
        dxyGraphData.filter { it.bucketTs.toLong() in d }.ifEmpty { dxyGraphData }
    } ?: dxyGraphData

    val allTs = allBuckets.map { it.bucketTs }
    val fullXMin = allTs.min()
    val lastDataTs = allTs.max()

    // x축 범위: visible domain 설정 시 그 범위로, 아니면 전체 + trailing buffer
    val xMin = visibleDomain?.start?.toInt() ?: fullXMin
    val xMax = visibleDomain?.endInclusive?.toInt() ?: (lastDataTs + trailingBufferSec(period))

    val yRange = computeYRange(visibleRateBuckets, visibleDxyBuckets) ?: return null
    val dxyRangeInfo = computeDxyRange(visibleDxyBuckets)

    return ChartState(
        xMin = xMin,
        xMax = xMax,
        yMin = yRange.domainMin,
        yMax = yRange.domainMax,
        rateRangeMin = yRange.paddedMin,
        rateRangeMax = yRange.paddedMax,
        lastDataTs = lastDataTs,
        hasDxy = dxyRangeInfo != null,
        dxyMin = dxyRangeInfo?.dxyMin,
        dxyMax = dxyRangeInfo?.dxyMax,
        isDxyFlat = dxyRangeInfo?.isFlat ?: false,
        flatDxyValue = dxyRangeInfo?.flatValue
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
