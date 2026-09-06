package com.jay.fxi.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current,
    /**
     * iOS v2.6 parity gating. follow-latest + zoom + 1d가 모두 true인 구간에서만 true로 전달.
     * 상위에서 이 신호를 받아 10초 synthetic tick을 gating하여 불필요한 recomposition 방지.
     * null이면 아무 동작 없음 (햅틱을 쓰지 않는 호출부).
     */
    onFollowActiveChanged: ((Boolean) -> Unit)? = null
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
                    modifier = Modifier.fillMaxSize(),
                    onFollowActiveChanged = onFollowActiveChanged
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
    val flatDxyValue: Double?,
    // M4-a: DXY path 그리기에 사용할 visible-filtered + 양 경계 interpolated 버킷.
    // 1d default view에서는 전체 dxyGraphData와 동일, 1d zoom 상태에서는
    // 시간 visible 안의 버킷만 + 양 경계에 linear-interpolated 가상 버킷 prepend/append.
    // iOS visibleDxyPoints 등가 (computeDxyPath는 이 리스트를 그대로 그림).
    val dxyDisplayBuckets: List<GraphBucket>
)

private data class XTick(
    val ts: Int,
    val label: String?,
    val showLabel: Boolean,
    val isMidnight: Boolean = false,
    // M4-c: 30분 보조 grid (visible <= 2.5h일 때만 추가). 라벨 없이 dashed line만 그림.
    val isHalfHour: Boolean = false
)

/// M4-b: gesture y-lock snapshot — pinch/pan began 시점에 yRange/dxyRange 통째 캡처.
/// computeChartState가 lock을 받으면 visible 데이터 기반 재계산을 우회하고 lock 값을 사용,
/// 결과적으로 라벨/정규화 기준이 gesture 동안 고정됨. iOS pocGestureYLock 등가.
///
/// 알려진 트레이드오프: lock 동안 visible 데이터의 새 max/min이 lock 범위를 초과하면
/// 정규화된 line이 chart 경계를 잠시 벗어나 잘릴 수 있음. pinch는 점진적이라 한 프레임의
/// deviation은 작아 실용적으로 거슬리지 않으며, 라벨 안정성이 더 큰 가치.
private data class GestureYLock(
    val rateRangeMin: Double,
    val rateRangeMax: Double,
    val yMin: Double,
    val yMax: Double,
    val dxyMin: Double?,
    val dxyMax: Double?,
    val isDxyFlat: Boolean,
    val flatDxyValue: Double?
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
    modifier: Modifier = Modifier,
    /** public RateGraphView로부터 forwarding. 실제 follow state는 이 함수 내부에 있으므로
     *  여기서 isFollowActive를 계산해 상위에 알림 (Option A bridge). f37b94b scope 오류 보정. */
    onFollowActiveChanged: ((Boolean) -> Unit)? = null
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
    // 회전 등 configuration change에서 사용자 줌/follow 상태 유지 (iOS @State 등가).
    // inputs에 period를 포함하므로:
    //   - 회전 (period 동일): saved value 복원 → 줌 상태 보존
    //   - period 변경 (1d → 1w 등): inputs 변화 감지 → init 재실행 → default reset
    // 따로 "회전인지 period 변경인지" 분기 로직 불필요.
    var pocVisibleDomain by rememberSaveable(period, stateSaver = VisibleDomainSaver) {
        mutableStateOf<ClosedRange<Long>?>(null)
    }
    var pocIsFollowingLatest by rememberSaveable(period) { mutableStateOf(true) }

    // iOS v2.6 parity: follow + zoom + 1d 3조건 모두 충족 시에만 상위에 "follow 활성" 신호.
    // 상위(CurrencyTabContent)가 이 값을 gating하여 10초 synthetic tick을 돌릴지 결정.
    // 이전엔 상위가 period == 1d만 보고 tick을 돌려 불필요한 invalidation이 컸음.
    val isFollowActive = pocIsFollowingLatest &&
        pocVisibleDomain != null &&
        period == GraphPeriod.ONE_DAY
    LaunchedEffect(isFollowActive) {
        onFollowActiveChanged?.invoke(isFollowActive)
    }

    // M2: Gesture baseline (iOS pocHandlePinch/PanBegan 등가)
    // pinch: began에서 한 번 캡처 + changed에서 finger-centered 계산용
    var pocPinchBaselineDomain: ClosedRange<Long>? by remember { mutableStateOf(null) }
    var pocPinchAnchorTimeSec: Long? by remember { mutableStateOf(null) }
    var pocPinchAnchorFraction: Float by remember { mutableStateOf(0.5f) }
    // pan: began에서 baseline 캡처 + changed에서 translation 기반 이동
    var pocPanBaselineDomain: ClosedRange<Long>? by remember { mutableStateOf(null) }

    // M3-a: 더블탭 감지용 last tap 레코드 (0L = 없음)
    var pocLastTapTimeMs: Long by remember { mutableStateOf(0L) }
    var pocLastTapPosition: Offset by remember { mutableStateOf(Offset.Zero) }

    // M4-b: gesture y-lock — pinch/pan began 시점에 yRange/dxyRange snapshot,
    // gesture ended 시 release. visible 데이터 갱신으로 인한 매 프레임 라벨 값 변동
    // (사용자 체감 "Y축 라벨이 빠르게 훅훅 변함") 차단. iOS pocGestureYLock 등가.
    var pocGestureYLock: GestureYLock? by remember { mutableStateOf(null) }

    // M5: 더블탭 줌 애니메이션 관리 — pocVisibleDomain을 0.25s에 걸쳐 점진적으로
    // 보간하는 coroutine job. iOS withAnimation(.easeOut(0.25)) 등가.
    // 새 더블탭/pinch/pan/period change/dispose 시 cancel.
    var pocAnimationJob: Job? by remember { mutableStateOf(null) }
    val animationScope = rememberCoroutineScope()

    // 기간 변경 시 ephemeral state / animation / lock 리셋 (설계서 §4 M1/M2/M3/M4/M5).
    // pocVisibleDomain / pocIsFollowingLatest 는 rememberSaveable(period, ...) 가 자동 reset하므로
    // 여기서 다시 null/true 할당하면 회전 시 복원된 값을 파괴. 해당 두 라인 의도적으로 제외.
    LaunchedEffect(period) {
        pocAnimationJob?.cancel()
        pocAnimationJob = null
        pocPinchBaselineDomain = null
        pocPinchAnchorTimeSec = null
        pocPanBaselineDomain = null
        pocLastTapTimeMs = 0L
        pocGestureYLock = null
    }

    // M5: composable dispose 시 진행 중인 애니메이션 job 정리
    DisposableEffect(Unit) {
        onDispose {
            pocAnimationJob?.cancel()
        }
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

    val chartState = remember(rateGraphData, dxyGraphData, period, resolvedVisibleDomain, pocGestureYLock) {
        computeChartState(rateGraphData, dxyGraphData, period, resolvedVisibleDomain, pocGestureYLock)
    } ?: return

    // M2 fix: pointerInput(period, totalLengthSec)는 제스처 도중 재시작 방지를 위해 key를 최소화하므로,
    // coroutine 내부에서 참조하는 파생 state를 rememberUpdatedState로 감싸 최신 값 읽기를 보장한다.
    // (iOS UIKit gesture recognizer가 action block에서 최신 state를 참조하는 패턴과 등가)
    val currentLastDataTs by rememberUpdatedState(lastDataTs)
    val currentDataBounds by rememberUpdatedState(dataBounds)
    val currentResolvedVisibleDomain by rememberUpdatedState(resolvedVisibleDomain)
    val currentHasDxy by rememberUpdatedState(chartState.hasDxy)
    // M3-a: plot y 경계 계산에 필요 (회전 등으로 값 변경 시 stale 방지)
    val currentVerticalPadding by rememberUpdatedState(verticalPadding)
    // M4-b: pinch/pan began 시점에 yRange/dxyRange snapshot을 위해 최신 chartState 참조
    val currentChartState by rememberUpdatedState(chartState)

    // yLock release 시에만 yRange/rateRange/dxyRange를 0.25s easeOut으로 보간.
    // iOS의 withAnimation(.easeOut(0.25)) { pocGestureYLock = nil } 등가.
    // 다른 변화(lock 진입, 데이터 갱신)는 snap() 으로 즉시 반영.
    //
    // 원리: 직전 frame에 lock이 set이었고 이번 frame에 null이면 lockJustReleased = true.
    // 이때만 tween(250). 그 외는 snap(). animateFloatAsState는 animSpec이 NEW target과
    // 함께 쓰일 때만 적용되므로, in-flight animation은 interrupt 받지 않고 계속 진행.
    //
    // 보간 대상: yMin/yMax (grid + rate line), rateRangeMin/Max (DXY normalizer numerator),
    // dxyMin/Max (DXY normalizer denominator). 모두 같은 spec으로 병렬 보간하여 축/rate/DXY가
    // 동기화된 transition으로 움직임.
    //
    // DXY 라벨 VALUE는 chartState.dxyMin/Max (natural, settled) 기준으로 생성 — lock overlay
    // 해제 시 "true state는 natural, 위치만 interpolate" semantic. 라벨이 transition 중 step
    // 경계를 넘어 flicker 하는 것보다 natural 값으로 고정하고 위치만 보간하는 쪽이 자연스러움.
    val prevLocked = remember { mutableStateOf(false) }
    val lockJustReleased = prevLocked.value && pocGestureYLock == null
    // composition 본문에서 MutableState를 바로 write하는 건 Compose anti-pattern이라
    // SideEffect로 commit 후 update. 다음 recomposition에서 갱신된 prevLocked 값이 읽힘.
    SideEffect {
        prevLocked.value = pocGestureYLock != null
    }
    val yRangeAnimSpec: AnimationSpec<Float> = if (lockJustReleased) {
        tween(durationMillis = 250, easing = LinearOutSlowInEasing)
    } else {
        snap()
    }
    val animatedYMin by animateFloatAsState(
        targetValue = chartState.yMin.toFloat(),
        animationSpec = yRangeAnimSpec,
        label = "yMin"
    )
    val animatedYMax by animateFloatAsState(
        targetValue = chartState.yMax.toFloat(),
        animationSpec = yRangeAnimSpec,
        label = "yMax"
    )
    val animatedRateRangeMin by animateFloatAsState(
        targetValue = chartState.rateRangeMin.toFloat(),
        animationSpec = yRangeAnimSpec,
        label = "rateRangeMin"
    )
    val animatedRateRangeMax by animateFloatAsState(
        targetValue = chartState.rateRangeMax.toFloat(),
        animationSpec = yRangeAnimSpec,
        label = "rateRangeMax"
    )
    val animatedDxyMin by animateFloatAsState(
        targetValue = (chartState.dxyMin ?: 0.0).toFloat(),
        animationSpec = yRangeAnimSpec,
        label = "dxyMin"
    )
    val animatedDxyMax by animateFloatAsState(
        targetValue = (chartState.dxyMax ?: 0.0).toFloat(),
        animationSpec = yRangeAnimSpec,
        label = "dxyMax"
    )

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
                // M3-a: plot y 경계 (Canvas draw block과 동일 공식)
                val plotTopPx = currentVerticalPadding.toPx()
                val plotBottomPx = size.height - 16.dp.toPx() - plotTopPx

                // Pan 활성 여부 — 줌된 상태에서만 1-finger consume
                var gestureMode: GestureMode = GestureMode.UNDETERMINED
                var panReferencePoint: Offset = firstDown.position
                // M2 fix-3: pinch 누적 배율 (gesture-local).
                // calculateZoom()은 per-frame 증분이므로, baselineLen/perFrameZoom으로
                // 계산하면 누적이 되지 않고 baseline 근처에서 맴돌아 실질 줌이 발생하지 않는다.
                var pinchCumZoom: Float = 1f

                // M3-a: tap 감지용 gesture-local 추적 + 복구용 pre-gesture snapshot.
                // detectTapGestures를 chain 하면 pan 경로에서 consume된 이벤트를 못 봐
                // 줌 상태 더블탭이 작동 안 함 → awaitEachGesture 내부에서 수동 감지.
                val downTimeMs: Long = firstDown.uptimeMillis
                val downPosition: Offset = firstDown.position
                var maxPointers: Int = 1
                var maxTravel: Float = 0f
                var lastUpTimeMs: Long = downTimeMs
                val preGestureDomain: ClosedRange<Long>? = pocVisibleDomain
                val preGestureFollow: Boolean = pocIsFollowingLatest

                do {
                    val event = awaitPointerEvent()
                    val pressedPointers = event.changes.count { it.pressed }
                    // M3-a: tap 추적 — 모든 pointer가 뗐을 때 마지막 up 시점 기록
                    if (pressedPointers == 0) {
                        event.changes.firstOrNull()?.let { ch ->
                            lastUpTimeMs = ch.uptimeMillis
                        }
                        break
                    }
                    if (pressedPointers > maxPointers) maxPointers = pressedPointers
                    event.changes.firstOrNull()?.let { ch ->
                        val dx = ch.position.x - downPosition.x
                        val dy = ch.position.y - downPosition.y
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist > maxTravel) maxTravel = dist
                    }

                    when {
                        pressedPointers >= 2 -> {
                            // === Pinch (finger-centered) ===
                            if (gestureMode != GestureMode.PINCH) {
                                // Pinch began: baseline + anchor 캡처
                                // M5: 진행 중인 더블탭 애니메이션/지연 lock release 취소
                                // (애니메이션 중 사용자가 새 pinch 시작하면 즉시 양도)
                                pocAnimationJob?.cancel()
                                pocAnimationJob = null
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
                                // M4-b: yRange/dxyRange snapshot (mutation 전 chartState에서)
                                val csBefore = currentChartState
                                pocGestureYLock = GestureYLock(
                                    rateRangeMin = csBefore.rateRangeMin,
                                    rateRangeMax = csBefore.rateRangeMax,
                                    yMin = csBefore.yMin,
                                    yMax = csBefore.yMax,
                                    dxyMin = csBefore.dxyMin,
                                    dxyMax = csBefore.dxyMax,
                                    isDxyFlat = csBefore.isDxyFlat,
                                    flatDxyValue = csBefore.flatDxyValue
                                )
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
                                    val maxBoundary = lastTs
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
                                // M5: 진행 중인 더블탭 애니메이션/지연 lock release 취소
                                pocAnimationJob?.cancel()
                                pocAnimationJob = null
                                val baseline: ClosedRange<Long>? = currentResolvedVisibleDomain
                                if (baseline == null) {
                                    gestureMode = GestureMode.PASS_THROUGH
                                    continue
                                }
                                // M4-b: yRange/dxyRange snapshot (mutation 전 chartState에서)
                                val csBefore = currentChartState
                                pocGestureYLock = GestureYLock(
                                    rateRangeMin = csBefore.rateRangeMin,
                                    rateRangeMax = csBefore.rateRangeMax,
                                    yMin = csBefore.yMin,
                                    yMax = csBefore.yMax,
                                    dxyMin = csBefore.dxyMin,
                                    dxyMax = csBefore.dxyMax,
                                    isDxyFlat = csBefore.isDxyFlat,
                                    flatDxyValue = csBefore.flatDxyValue
                                )
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
                                    val maxBoundary = lastTs
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

                // === M3-a: Tap 판정 (pinch/pan end 처리보다 우선) ===
                // "Tap"의 정의:
                //   - 최대 1 pointer만 눌렸고
                //   - 이동 거리가 touchSlop 미만 (pan 아님)
                //   - 지속 시간이 longPressTimeout 미만 (long press 아님)
                //   - pinch가 한 번도 발생하지 않음
                //   - 다운 위치가 plot 영역 내부 (x축 라벨/Y축 숫자 영역 제외)
                val tapSlopPx = viewConfiguration.touchSlop
                val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                val tapDurationMs = lastUpTimeMs - downTimeMs
                val inPlotX = downPosition.x in plotLeft..(plotLeft + plotWidth)
                val inPlotY = downPosition.y in plotTopPx..plotBottomPx
                val wasTap = maxPointers == 1 &&
                    maxTravel < tapSlopPx &&
                    tapDurationMs in 0..longPressTimeoutMs &&
                    gestureMode != GestureMode.PINCH &&
                    inPlotX && inPlotY

                if (wasTap) {
                    val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
                    val doubleTapMinTimeMs = viewConfiguration.doubleTapMinTimeMillis
                    // Double-tap 위치 slop은 Android 플랫폼 표준에 맞춰 100dp 기반으로.
                    // 이전 tapSlopPx * 2 (~40dp) 는 자연스러운 손/몸 흔들림으로 인한 두 탭
                    // 위치 편차를 흡수 못해 실패율 증가 (사용자 보고로 재현 확인).
                    // AOSP ViewConfiguration의 DOUBLE_TAP_SLOP_IN_DIPS가 100dp 고정 상수이며
                    // getScaledDoubleTapSlop()는 100dp × density의 device-specific px 반환.
                    // Compose ViewConfiguration은 해당 값을 직접 노출하지 않으므로 100.dp.toPx()
                    // 로 동일 계산.
                    val doubleTapSlopPx = 100.dp.toPx()
                    val sinceLast = downTimeMs - pocLastTapTimeMs
                    val lastDx = downPosition.x - pocLastTapPosition.x
                    val lastDy = downPosition.y - pocLastTapPosition.y
                    val distFromLast = sqrt(lastDx * lastDx + lastDy * lastDy)
                    val isDoubleTap = pocLastTapTimeMs > 0L &&
                        sinceLast in doubleTapMinTimeMs..doubleTapTimeoutMs &&
                        distFromLast < doubleTapSlopPx

                    if (isDoubleTap) {
                        // === 더블탭 처리 (iOS pocHandleDoubleTap 등가) ===
                        // M5: 즉시 set 대신 0.25s easeOut 애니메이션. iOS withAnimation 등가.
                        // 진행 중인 애니메이션이 있으면 먼저 cancel (chaining: 새 from은 last animated value).
                        pocAnimationJob?.cancel()
                        pocAnimationJob = null
                        // M5 fix: 진행 중인 zoom in 애니메이션의 lock이 살아있을 수 있으므로 release.
                        // 그러지 않으면 빠른 연속 더블탭(zoom in 직후 reset)에서 첫 zoom in의 lock이
                        // reset 애니메이션 동안 누설되어 라벨이 stale 값에 frozen됨.
                        // (reset 분기는 yLock을 사용하지 않으므로 자체적으로 release하지 않음)
                        pocGestureYLock = null

                        val bounds = currentDataBounds
                        val lastTs = currentLastDataTs
                        if (bounds != null && lastTs != null) {
                            val defaultStart = bounds.start
                            val defaultEnd = lastTs + trailingBufferSec(period)
                            // 현재 effective from (애니메이션 도중 다시 더블탭한 경우 last animated value)
                            val fromDomain = pocVisibleDomain ?: (defaultStart..defaultEnd)

                            if (preGestureDomain != null) {
                                // === 줌 상태 → reset (default view로 복귀) ===
                                // iOS는 reset에 yLock을 사용하지 않음 (도메인 expand는 자연스럽게).
                                val toStart = defaultStart
                                val toEnd = defaultEnd
                                pocAnimationJob = animationScope.launch {
                                    animateDomainTransition(
                                        fromStart = fromDomain.start,
                                        fromEnd = fromDomain.endInclusive,
                                        toStart = toStart,
                                        toEnd = toEnd
                                    ) { s, e ->
                                        pocVisibleDomain = s..e
                                        pocIsFollowingLatest = false  // 애니메이션 중 freeze
                                    }
                                    // 애니메이션 정상 종료 → null + follow on (default view 복귀)
                                    pocVisibleDomain = null
                                    pocIsFollowingLatest = true
                                    pocAnimationJob = null
                                }
                            } else {
                                // === 기본 보기 → 탭 위치 중심 6h window 줌인 ===
                                val baselineLen = defaultEnd - defaultStart
                                val fraction = ((downPosition.x - plotLeft) / plotWidth).coerceIn(0f, 1f)
                                val tapTimeSec = defaultStart + (baselineLen * fraction).toLong()
                                // iOS GraphConfig.doubleTapZoomWindow = 6h
                                val halfWindow = 6L * 3600L / 2L
                                val proposed = (tapTimeSec - halfWindow)..(tapTimeSec + halfWindow)
                                // gesture clamp right boundary = lastTs (trailing buffer 제외).
                                // defaultEnd(trailing 포함)는 visual baseline/fraction 계산에만 사용.
                                val clamped = clampVisibleDomain(proposed, defaultStart, lastTs)

                                // M5: zoom in은 yLock 사용 (iOS 패턴) — 애니메이션 중 visible 데이터
                                // shrink로 인한 yRange 떨림 차단. 현재 chartState 기준으로 snapshot.
                                val csBefore = currentChartState
                                pocGestureYLock = GestureYLock(
                                    rateRangeMin = csBefore.rateRangeMin,
                                    rateRangeMax = csBefore.rateRangeMax,
                                    yMin = csBefore.yMin,
                                    yMax = csBefore.yMax,
                                    dxyMin = csBefore.dxyMin,
                                    dxyMax = csBefore.dxyMax,
                                    isDxyFlat = csBefore.isDxyFlat,
                                    flatDxyValue = csBefore.flatDxyValue
                                )

                                pocAnimationJob = animationScope.launch {
                                    animateDomainTransition(
                                        fromStart = fromDomain.start,
                                        fromEnd = fromDomain.endInclusive,
                                        toStart = clamped.start,
                                        toEnd = clamped.endInclusive
                                    ) { s, e ->
                                        pocVisibleDomain = s..e
                                        pocIsFollowingLatest = false  // 애니메이션 중 freeze
                                    }
                                    // 애니메이션 정상 종료 → 최종 target으로 settle + follow 재평가
                                    pocVisibleDomain = clamped
                                    val distance = abs(clamped.endInclusive - lastTs)
                                    pocIsFollowingLatest = distance < 600L
                                    // M5 divergence (iOS Task 0.26s 미채택): yLock을 즉시 release.
                                    // iOS는 0.26s 지연 후 release하여 settle 프레임이 lock으로 그려진 뒤
                                    // 자연 yRange로 전환되는 polish를 추가했지만, Android에서 동일 패턴을
                                    // 안전하게 구현하려면 "지연 release window 동안 새 pinch/pan이 시작되면
                                    // stale lock 값을 snapshot하는" 위험을 막기 위해 자연 chartState 파생을
                                    // 별도로 두거나 computeChartState를 두 번 계산해야 함. ETC 원칙에 따라
                                    // 즉시 release를 채택. §9 divergence 노트 참조.
                                    pocGestureYLock = null
                                    pocAnimationJob = null
                                }
                            }
                        }
                        pocLastTapTimeMs = 0L  // 소비됨, 리셋
                    } else {
                        // 단일 탭 → pre-gesture 상태 복구 후 lastTap 기록
                        // (줌 상태에서는 PAN 경로가 pocIsFollowingLatest=false + baseline 쓴 상태라
                        //  복구하지 않으면 의도치 않은 follow 중단이 남는다)
                        pocVisibleDomain = preGestureDomain
                        pocIsFollowingLatest = preGestureFollow
                        // M3-a fix: double tap timeout 기준은 "첫 탭의 up → 두 번째 탭의 down" 간격
                        // (Android GestureDetector.DOUBLE_TAP_TIMEOUT 관례). down 기준으로 저장하면
                        // 첫 탭이 길었던 만큼 허용 window가 단축돼 느린 더블탭을 놓친다.
                        pocLastTapTimeMs = lastUpTimeMs
                        pocLastTapPosition = downPosition
                    }
                    // tap 처리했으므로 pinch/pan 정리만 하고 full-unzoom 판정은 스킵.
                    // M4-b: PAN 경로를 잠시 거쳤다면 yLock이 설정됐을 수 있으므로 cleanup.
                    pocPinchBaselineDomain = null
                    pocPinchAnchorTimeSec = null
                    pocPanBaselineDomain = null
                    pocGestureYLock = null
                    return@awaitEachGesture
                }

                // M3-a fix: wasTap == false로 종료된 모든 gesture는 이전 single tap 후보를 리셋.
                // 이유: plot 안 tap1 → plot 밖 짧은 tap/실패 pan/pinch/long press → plot 안 tap2 시퀀스에서
                // tap1과 tap2가 잘못 묶여 false-positive double tap이 발생하는 것을 방지.
                // (Android GestureDetector와 동일한 semantic — 어떤 non-tap gesture도 pending state 취소)
                pocLastTapTimeMs = 0L
                pocLastTapPosition = Offset.Zero

                // === Gesture ended: full-unzoom 릴리스 + follow-latest 재평가 + yLock release ===
                // DISCARDED도 포함: pinch → 1-finger 전환 후 release한 경우. PINCH 단계에서
                // pocGestureYLock/pocVisibleDomain/pocIsFollowingLatest를 이미 mutate한 상태이므로
                // PINCH/PAN과 동일하게 정리해야 lock leak 및 follow-latest leak이 발생하지 않음.
                // (M3-a 단계에서 누락됐던 follow-latest/full-unzoom leak이 M4-b yLock leak과 합쳐져
                //  코덱스 리뷰로 발견)
                if (gestureMode == GestureMode.PINCH ||
                    gestureMode == GestureMode.PAN ||
                    gestureMode == GestureMode.DISCARDED
                ) {
                    pocPinchBaselineDomain = null
                    pocPinchAnchorTimeSec = null
                    pocPanBaselineDomain = null
                    pocGestureYLock = null  // M4-b: gesture 종료 시 lock release → yRange 자연 재계산
                    val raw = pocVisibleDomain
                    val latest = currentLastDataTs
                    val bounds = currentDataBounds
                    if (raw != null && latest != null && bounds != null) {
                        val rawLen = raw.endInclusive - raw.start
                        // gesture clamp right boundary가 lastDataTs (trailing 제외)이므로
                        // zoom out 시 최대 domain length = dataBounds span.
                        // 이에 맞춰 full-unzoom 판정 기준도 dataBounds span (trailing 제외)으로 변경.
                        val unzoomedLen =
                            (bounds.endInclusive - bounds.start)
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

        // yLock release 시 보간된 y 관련 값. 평상시(lock 진입 / 데이터 갱신)는 snap이라 즉시 반영.
        val effYMin = animatedYMin.toDouble()
        val effYMax = animatedYMax.toDouble()
        val effRateRangeMin = animatedRateRangeMin.toDouble()
        val effRateRangeMax = animatedRateRangeMax.toDouble()
        val effDxyMin = if (chartState.dxyMin != null) animatedDxyMin.toDouble() else null
        val effDxyMax = if (chartState.dxyMax != null) animatedDxyMax.toDouble() else null

        val layout = ChartLayout(
            chartLeft = leftPad,
            chartTop = innerPad,
            chartRight = size.width - rightPad,
            chartBottom = size.height - bottomPad - innerPad,
            xMin = chartState.xMin,
            xMax = chartState.xMax,
            yMin = effYMin,
            yMax = effYMax
        )

        val xTicks = computeXTicks(period, layout.xMin, layout.xMax, chartState.lastDataTs)
        val yTicks = computeYTicks(layout.yMin, layout.yMax)
        val sourcePaths = computeRatePaths(rateGraphData, layout, period, chartState.hasDxy)
        val dxyPath = if (chartState.hasDxy && effDxyMin != null && effDxyMax != null) {
            // M4-a: chartState.dxyDisplayBuckets 사용 (visible-filtered + 양 경계 interpolated).
            // 정규화 파라미터는 lock release 시 보간된 값(effRateRange*, effDxy*) 사용 →
            // DXY line이 rate line/grid와 동기화된 transition으로 움직임.
            computeDxyPath(chartState.dxyDisplayBuckets, layout, effRateRangeMin, effRateRangeMax, effDxyMin, effDxyMax)
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

        // M4-c: 30분 보조 grid의 dashed line effect (강한 줌 시 정각 tick 사이 보조선).
        val halfHourDashEffect = PathEffect.dashPathEffect(
            floatArrayOf(with(density) { 3.dp.toPx() }, with(density) { 3.dp.toPx() }),
            0f
        )
        // 30분 보조 grid 색상 — 정각 grid(alpha 0.3)보다 명확히 흐려야 시각 위계 유지.
        // Color.copy(alpha)는 곱셈이 아닌 교체이므로 0.3보다 작은 절대값을 직접 지정.
        val halfHourGridColor = SecondaryText.copy(alpha = 0.18f)
        for (tick in xTicks) {
            val x = layout.mapX(tick.ts)
            if (x < layout.chartLeft || x > layout.chartRight) continue
            if (tick.isHalfHour) {
                // 30분 grid: dashed, 라벨 없음, 정각 tick보다 흐림
                drawLine(
                    color = halfHourGridColor,
                    start = Offset(x, layout.chartTop),
                    end = Offset(x, layout.chartBottom),
                    strokeWidth = gridStroke,
                    pathEffect = halfHourDashEffect
                )
                continue
            }
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

        if (chartState.hasDxy && effDxyMin != null && effDxyMax != null) {
            if (chartState.isDxyFlat && chartState.flatDxyValue != null) {
                // flat 라벨 값은 chartState.flatDxyValue (natural, settled) 사용,
                // 위치 계산은 animated 정규화 파라미터로.
                val y = layout.mapY(
                    normalizeDxyValue(
                        chartState.flatDxyValue,
                        effRateRangeMin,
                        effRateRangeMax,
                        effDxyMin,
                        effDxyMax
                    )
                )
                val textResult = textMeasurer.measure(formatDxyValue(chartState.flatDxyValue), dxyLabelStyle)
                drawText(
                    textLayoutResult = textResult,
                    topLeft = Offset(0f, y - textResult.size.height / 2f)
                )
            } else {
                // 라벨 VALUE 세트는 chartState.dxyMin/Max (settled) 기준으로 생성 — transition 중
                // step 경계 flicker 방지. 각 라벨의 y 위치만 animated 정규화로 계산.
                for (label in generateDxyLabels(chartState.dxyMin!!, chartState.dxyMax!!)) {
                    val normalized = normalizeDxyValue(
                        label.value,
                        effRateRangeMin,
                        effRateRangeMax,
                        effDxyMin,
                        effDxyMax
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

/// Configuration change (회전 등) 시 pocVisibleDomain 을 보존하기 위한 Saver.
/// ClosedRange<Long>? 은 직접 Saveable 이 아니라 Long 두 개의 list 로 직렬화.
/// null (default view) 은 emptyList 로 저장.
private val VisibleDomainSaver = listSaver<ClosedRange<Long>?, Long>(
    save = { range ->
        if (range == null) emptyList() else listOf(range.start, range.endInclusive)
    },
    restore = { list ->
        if (list.isEmpty()) null else list[0]..list[1]
    }
)

/// M5: 더블탭 줌 애니메이션 — pocVisibleDomain을 fromDomain → toDomain으로 0.25s 보간.
/// iOS withAnimation(.easeOut(0.25)) 등가. 매 프레임 onFrame을 호출하여 caller가
/// state 갱신을 책임지게 함 (Animatable<Float> progress + linear interpolation).
///
/// 취소: Job.cancel() 시 Animatable.animateTo가 CancellationException으로 종료됨.
/// caller는 launch { try { ... } catch (CancellationException) { rethrow } } 패턴으로
/// 호출하여 cancel 시 onSettled 후속 처리를 건너뛴다.
private suspend fun animateDomainTransition(
    fromStart: Long,
    fromEnd: Long,
    toStart: Long,
    toEnd: Long,
    durationMs: Int = 250,
    onFrame: (start: Long, end: Long) -> Unit
) {
    val anim = Animatable(0f)
    anim.animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = durationMs, easing = LinearOutSlowInEasing)
    ) {
        val t = value.coerceIn(0f, 1f)
        val newStart = fromStart + ((toStart - fromStart) * t).toLong()
        val newEnd = fromEnd + ((toEnd - fromEnd) * t).toLong()
        onFrame(newStart, newEnd)
    }
}

/// M2 fix-3: visible domain clamp (경계 + 길이).
/// - minBoundary/maxBoundary: 허용 가능한 좌우 경계.
///   gesture clamp (pinch/pan/double-tap)에서는 maxBoundary = `lastDataTs` (trailing buffer 제외)
///   를 넘겨야 한다. default view rendering의 xMax는 별도로 trailing buffer를 포함 (computeChartState).
///   이 분리가 iOS setVisibleDomain의 latestAnchor / xDomain.upperBound 분리와 등가.
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
/// visible 필터 → DXY 경계 보간(M4-a) → computeYRange → computeDxyRange → ChartState 조립.
/// visibleDomain: M1 이후 ClosedRange<Long>(epoch 초 Long 단위). null이면 전체 data 기반.
/// yLock: M4-b — 제공되면 yRange/dxyRange를 lock 값으로 강제 (gesture 중 라벨 안정화).
///        dxyDisplayBuckets은 lock과 무관하게 visible 기반으로 산출 (line은 신선한 데이터로 그리되,
///        정규화 기준만 lock).
private fun computeChartState(
    rateGraphData: Map<GraphSource, List<GraphBucket>>,
    dxyGraphData: List<GraphBucket>,
    period: GraphPeriod,
    visibleDomain: ClosedRange<Long>? = null,  // 1d 줌 상태에서만 non-null
    yLock: GestureYLock? = null
): ChartState? {
    val rateBuckets = rateGraphData.values.flatten()
    val allBuckets = if (rateBuckets.isNotEmpty()) rateBuckets + dxyGraphData else dxyGraphData
    if (allBuckets.isEmpty()) return null

    // visible domain 설정 시 rate 버킷 필터링 (y range 계산용).
    // 필터 결과 empty면 전체 fallback (iOS와 동일 정책).
    val visibleRateBuckets = visibleDomain?.let { d ->
        rateBuckets.filter { it.bucketTs.toLong() in d }.ifEmpty { rateBuckets }
    } ?: rateBuckets

    val allTs = allBuckets.map { it.bucketTs }
    val fullXMin = allTs.min()
    val lastDataTs = allTs.max()

    // x축 범위: visible domain 설정 시 그 범위로, 아니면 전체 + trailing buffer
    val xMin = visibleDomain?.start?.toInt() ?: fullXMin
    val xMax = visibleDomain?.endInclusive?.toInt() ?: (lastDataTs + trailingBufferSec(period))

    // M4-a: DXY display 버킷 산출 — visible 필터 결과에 양 경계 interpolated 가상 포인트
    // prepend/append. 1d default view에서는 전체 dxyGraphData를 그대로 사용 (zoom 아닐 땐
    // 보간 불필요). iOS visibleDxyPoints 등가 (RateGraphView.swift L251-264).
    //
    // ⚠️ 중요: rate처럼 ifEmpty { full } fallback을 base에 미리 적용하면 안 된다.
    // "between buckets" 줌 시나리오 (visible window가 두 인접 dxy bucket 사이에 끼임)에서
    // raw filter는 empty이지만 boundary interpolation은 양쪽에서 성공해야 한다. base에
    // fallback이 적용돼 있으면 "전체 데이터 + boundary"가 mix되어 의미 없는 line이 추가됨.
    // → raw filter를 base로 쓰고, 최종 withBoundaries가 비었을 때만 fallback.
    //
    // 이 augmented 리스트는 dxyRange/yRange fallback 계산과 path 그리기 모두에 사용된다 →
    // 정규화 기준에 boundary 값이 포함되므로 visible 직후 다음 버킷이 visible dxyMin/dxyMax
    // 밖이라도 path가 chartTop/chartBottom으로 "shoot"되는 v1.x 수직 아티팩트가 사라진다.
    val dxyDisplayBuckets: List<GraphBucket> = if (visibleDomain != null && dxyGraphData.isNotEmpty()) {
        val sortedAllDxy = dxyGraphData.sortedBy { it.bucketTs }
        val rawVisibleSorted = sortedAllDxy.filter { it.bucketTs.toLong() in visibleDomain }
        val left = interpolateDxyBoundary(xMin.toLong(), sortedAllDxy)
        val right = interpolateDxyBoundary(xMax.toLong(), sortedAllDxy)
        val withBoundaries = listOfNotNull(left) + rawVisibleSorted + listOfNotNull(right)
        withBoundaries.ifEmpty { dxyGraphData }
    } else {
        dxyGraphData
    }

    val computedYRange = computeYRange(visibleRateBuckets, dxyDisplayBuckets) ?: return null
    val computedDxyRangeInfo = computeDxyRange(dxyDisplayBuckets)

    // M4-b: yLock 적용 — gesture 동안 yRange/dxyRange를 lock 값으로 강제.
    // dxyDisplayBuckets는 lock과 무관하게 visible 기반으로 산출 (line은 신선한 데이터로
    // 그리되 정규화 기준만 lock). 이렇게 해야 panning 중에도 line은 자연스럽게 흐르고
    // 라벨만 안정.
    val effectiveYMin = yLock?.yMin ?: computedYRange.domainMin
    val effectiveYMax = yLock?.yMax ?: computedYRange.domainMax
    val effectiveRateMin = yLock?.rateRangeMin ?: computedYRange.paddedMin
    val effectiveRateMax = yLock?.rateRangeMax ?: computedYRange.paddedMax

    // dxy lock은 lock에 dxy 정보가 있을 때만 적용. lock에 dxy가 없는데 현재 hasDxy면
    // 일반적으로 발생하지 않지만(같은 데이터 셋), 안전을 위해 computed로 fallback.
    val effectiveHasDxy = if (yLock != null) {
        yLock.dxyMin != null && yLock.dxyMax != null
    } else {
        computedDxyRangeInfo != null
    }
    val effectiveDxyMin = yLock?.dxyMin ?: computedDxyRangeInfo?.dxyMin
    val effectiveDxyMax = yLock?.dxyMax ?: computedDxyRangeInfo?.dxyMax
    val effectiveIsDxyFlat = yLock?.isDxyFlat ?: (computedDxyRangeInfo?.isFlat ?: false)
    val effectiveFlatDxyValue = yLock?.flatDxyValue ?: computedDxyRangeInfo?.flatValue

    return ChartState(
        xMin = xMin,
        xMax = xMax,
        yMin = effectiveYMin,
        yMax = effectiveYMax,
        rateRangeMin = effectiveRateMin,
        rateRangeMax = effectiveRateMax,
        lastDataTs = lastDataTs,
        hasDxy = effectiveHasDxy,
        dxyMin = effectiveDxyMin,
        dxyMax = effectiveDxyMax,
        isDxyFlat = effectiveIsDxyFlat,
        flatDxyValue = effectiveFlatDxyValue,
        dxyDisplayBuckets = dxyDisplayBuckets
    )
}

/// M4-a: visible 경계(boundarySec)에 해당하는 interpolated DXY 버킷 생성.
/// - sortedDxy: bucketTs 오름차순 정렬된 전체 DXY 버킷
/// - 경계가 data 범위 밖이면 null (extrapolation 안 함)
/// - 경계가 정확히 한 버킷의 timestamp와 일치하면 null (이미 visible에 포함)
/// - 그 외: 경계를 감싸는 두 인접 버킷의 close 값으로 linear interpolation
///
/// iOS interpolateDxyBoundaryPoint 등가. 가상 GraphBucket의 max/min/close는 모두
/// interpolated value로 동일 (밴드 그리지 않음).
private fun interpolateDxyBoundary(
    boundarySec: Long,
    sortedDxy: List<GraphBucket>
): GraphBucket? {
    if (sortedDxy.isEmpty()) return null
    val firstTs = sortedDxy.first().bucketTs.toLong()
    val lastTs = sortedDxy.last().bucketTs.toLong()
    // 경계가 data 범위 밖이면 보간 불가 (extrapolation 방지)
    if (boundarySec < firstTs || boundarySec > lastTs) return null

    // 경계 뒤의 첫 버킷 (>= boundary)
    val after = sortedDxy.firstOrNull { it.bucketTs.toLong() >= boundarySec } ?: return null
    // 경계가 정확히 버킷에 걸리면 이미 visible에 포함 — 보간 불필요
    if (after.bucketTs.toLong() == boundarySec) return null
    // 경계 앞의 마지막 버킷 (< boundary)
    val before = sortedDxy.lastOrNull { it.bucketTs.toLong() < boundarySec } ?: return null

    val totalSpan = (after.bucketTs - before.bucketTs).toDouble()
    if (totalSpan <= 0.0) return null
    val ratio = (boundarySec - before.bucketTs) / totalSpan
    val interpolatedClose = before.close + (after.close - before.close) * ratio

    return GraphBucket(
        bucketTs = boundarySec.toInt(),
        max = interpolatedClose,
        min = interpolatedClose,
        close = interpolatedClose
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
            // M4-c: visible length 기반 adaptive hour interval + 강한 줌(≤ 2.5h)에서 30분 보조 grid.
            // 정각 tick과 30분 grid는 서로 다른 함수에서 별도 생성한 뒤 merge sorted (한 루프에 섞으면
            // 중복/누락 위험).
            val visibleLengthSec = (xMax - xMin).coerceAtLeast(1)
            val hourInterval = adaptiveHourIntervalOneDay(visibleLengthSec)
            val hourTicks = generateHourTicksOneDay(xMin, xMax, hourInterval, lastDataTs, kst)
            if (visibleLengthSec <= 2 * 3600 + 1800) {
                val halfHourGrids = generateHalfHourGridsOneDay(xMin, xMax, kst)
                (hourTicks + halfHourGrids).sortedBy { it.ts }
            } else {
                hourTicks
            }
        }

        GraphPeriod.ONE_WEEK -> generateDayTicks(xMin, xMax, lastDataTs, 1)
        GraphPeriod.THREE_MONTHS -> generateDayTicks(xMin, xMax, lastDataTs, 14)
        GraphPeriod.ONE_YEAR -> generateMonthTicks(xMin, xMax, lastDataTs)
    }
}

/// M4-c: visible length 기반 1d adaptive hour interval. iOS pocAdaptiveHourInterval 등가.
/// - 12h 초과 → 3시간
/// - 6h 초과 → 1시간
/// - 그 외 → 1시간 (30min은 정수 시간 alignment가 필요해 1h로 clamp; 30분 grid는 별도 함수에서 생성)
///
/// ⚠️ 정수 나눗셈으로 hours를 계산하면 12h ~ 12h59m 구간이 hours == 12로 truncate되어
/// "> 12" false가 되고 iOS Double semantic 대비 임계점이 어긋남. 초 단위로 직접 비교.
private fun adaptiveHourIntervalOneDay(visibleLengthSec: Int): Int {
    return when {
        visibleLengthSec > 12 * 3600 -> 3
        visibleLengthSec > 6 * 3600 -> 1
        else -> 1
    }
}

/// M4-c: ONE_DAY 정각 tick 생성 (hourInterval 단위로 정렬). 기존 ONE_DAY 분기에서
/// 하드코딩됐던 3시간 로직을 hourInterval 변수화하여 추출.
private fun generateHourTicksOneDay(
    xMin: Int,
    xMax: Int,
    hourInterval: Int,
    lastDataTs: Int,
    kst: TimeZone
): List<XTick> {
    val ticks = mutableListOf<XTick>()
    val startDt = Instant.fromEpochSeconds(xMin.toLong()).toLocalDateTime(kst)
    val hourAligned = (startDt.hour / hourInterval) * hourInterval
    val tickDt = kotlinx.datetime.LocalDateTime(
        startDt.year, startDt.monthNumber, startDt.dayOfMonth, hourAligned, 0, 0
    )
    var tickTs = tickDt.toInstant(kst).epochSeconds.toInt()
    if (tickTs < xMin) tickTs += hourInterval * 3600
    while (tickTs <= xMax) {
        val dt = Instant.fromEpochSeconds(tickTs.toLong()).toLocalDateTime(kst)
        ticks += XTick(
            ts = tickTs,
            label = if (dt.hour == 0) "${dt.monthNumber}/${dt.dayOfMonth}" else "%02d".format(dt.hour),
            showLabel = tickTs <= lastDataTs && (lastDataTs - tickTs) >= 600,
            isMidnight = dt.hour == 0
        )
        tickTs += hourInterval * 3600
    }
    return ticks
}

/// M4-c: ONE_DAY 30분 보조 grid 생성. minute==30 위치만 반환. 정각 tick과 중복 없도록
/// 항상 30분 단위만 생성. iOS generateHalfHourGrids 등가.
private fun generateHalfHourGridsOneDay(
    xMin: Int,
    xMax: Int,
    kst: TimeZone
): List<XTick> {
    val ticks = mutableListOf<XTick>()
    val startDt = Instant.fromEpochSeconds(xMin.toLong()).toLocalDateTime(kst)
    // 가장 가까운 :30 시점부터 시작
    val firstHalfDt = kotlinx.datetime.LocalDateTime(
        startDt.year, startDt.monthNumber, startDt.dayOfMonth, startDt.hour, 30, 0
    )
    var tickTs = firstHalfDt.toInstant(kst).epochSeconds.toInt()
    if (tickTs < xMin) tickTs += 3600
    while (tickTs <= xMax) {
        ticks += XTick(
            ts = tickTs,
            label = null,
            showLabel = false,
            isMidnight = false,
            isHalfHour = true
        )
        tickTs += 3600
    }
    return ticks
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
        val allBuckets = rateGraphData[source]?.sortedBy { it.bucketTs } ?: return@mapNotNull null
        if (allBuckets.isEmpty()) return@mapNotNull null

        // visible range + ±1 bucket 마진으로 필터.
        // 이전엔 전체 bucket으로 path 생성 후 clipRect로 시각만 잘랐으나, 줌 상태에서
        // 145 buckets × 3 sources = 435 lineTo 호출이 매 프레임 반복되어 frame drop 유발
        // (사용자 관찰 "pan 시 선이 툭툭 재정렬"). visible + margin으로 줄이면 max zoom(1h)
        // 기준 ~8 buckets/source로 18x 감소. margin은 index 기반 ±1 bucket이라 bucket
        // 간격(gap 포함)에 무관하게 edge segment가 chart 경계까지 확실히 이어짐.
        val firstIdx = allBuckets.indexOfFirst { it.bucketTs >= layout.xMin }
        val lastIdx = allBuckets.indexOfLast { it.bucketTs <= layout.xMax }
        val startIdx = if (firstIdx > 0) firstIdx - 1 else 0
        val endIdx = if (lastIdx >= 0 && lastIdx < allBuckets.lastIndex) lastIdx + 1 else allBuckets.lastIndex
        val buckets = if (firstIdx < 0 && lastIdx < 0) {
            allBuckets  // visible range에 bucket이 하나도 없으면 fallback (전체)
        } else {
            allBuckets.subList(startIdx, (endIdx + 1).coerceAtMost(allBuckets.size))
        }

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
    // iOS generateDxyAxisLabels와 동일하게 inset 없이 range 전체에서 step 정렬.
    // (이전엔 Android 전용으로 `inset = span * 0.05` 적용했으나 사용자 체감 비교 결과
    // iOS 5-label이 DXY 변화 폭 해석에 더 명확 — divergence 철회. §9 참조)
    val start = ceil(dxyMin / step) * step
    val end = floor(dxyMax / step) * step
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

    // M4-a: 줌이 깊어 (dxyMin, dxyMax)가 step 경계 사이에 끼면 start > end가 되어
    // labels가 빈 배열이 되는 케이스 — DXY 라벨 표시가 완전히 사라지는 시각 결손 발생.
    // 해결: range 중앙값을 단일 라벨로 fallback (%.2f 정밀도). step round 시 range 밖으로
    // 빠지는 버그를 피하려고 mid를 value에 직접 넣음 (iOS와 동일 패턴).
    if (labels.isEmpty()) {
        val mid = (dxyMin + dxyMax) / 2.0
        labels += DxyLabel(mid, "%.2f".format(mid))
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
