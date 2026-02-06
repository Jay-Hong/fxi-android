package com.jay.fxi.ui.subscription

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.subscription.SubscriptionManager
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.domain.model.referenceRate
import com.jay.fxi.domain.model.sortedByBank
import com.jay.fxi.ui.components.RateBarView
import com.jay.fxi.ui.components.RateGraphView
import com.jay.fxi.ui.components.SourceToggleRow
import com.jay.fxi.ui.settings.SettingsScreen
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.SecondaryText
import kotlinx.coroutines.delay

private object LockedPreviewState {
    var hasShownWelcomeThisSession = false
}

/** 알림 추가/수정 시트 상태 */
private sealed class AlertSheetState {
    data object Add : AlertSheetState()
    data class Edit(val setting: SampleAlertSetting) : AlertSheetState()
}

@Composable
fun LockedPreviewScreen(
    subscriptionManager: SubscriptionManager,
    onPrimaryAction: () -> Unit,
    onClose: (() -> Unit)? = null,
    userInfo: UserInfo? = null,
    onSignOut: (() -> Unit)? = null,
    isPaywallVisible: Boolean = false
) {
    val viewModel = remember { SamplePreviewViewModel() }
    val scrollState = rememberScrollState()

    var showSettings by remember { mutableStateOf(false) }
    var showWelcomeAlert by remember { mutableStateOf(false) }
    var alertSheetState by remember { mutableStateOf<AlertSheetState?>(null) }
    var isFloatingCTAHighlighted by remember { mutableStateOf(false) }
    var ctaHighlightTriggerCount by remember { mutableIntStateOf(0) }

    // ── SubscriptionManager 연동 (iOS 파리티) ──
    val trialDurationText by subscriptionManager.trialDurationText.collectAsStateWithLifecycle()
    val showGiftIcon = trialDurationText != null
    val primaryCTATitle = if (trialDurationText != null) "첫 구독자 $trialDurationText 무료" else "프리미엄 구독하기"

    // offerings preload (iOS onAppear에서 loadOfferings 호출과 동일)
    LaunchedEffect(Unit) {
        subscriptionManager.loadOfferings()
    }

    // ── ViewModel 데이터 ──

    val sampleRates = viewModel.rates
    val graphData = viewModel.graphData
    val selectedSources = viewModel.selectedSources

    val minRate = remember(sampleRates) {
        if (sampleRates.isEmpty()) 0.0 else sampleRates.minOf { it.rate }
    }
    val maxRate = remember(sampleRates) {
        if (sampleRates.isEmpty()) 0.0 else sampleRates.maxOf { it.rate }
    }
    val referenceRate = remember(sampleRates) {
        sampleRates.referenceRate(SupportedCurrency.USD_KRW.code)
    }

    // ── 햅틱 피드백 ──

    val hapticFeedback = LocalHapticFeedback.current
    SideEffect {
        viewModel.onHapticFeedback = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // ── Lifecycle: start / stop ──

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    // ── Background / Foreground 감지 ──

    var isInForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isInForeground = true
                Lifecycle.Event.ON_PAUSE -> isInForeground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 통합 시뮬레이션 pause/resume (iOS updateSimulationState 동일) ──

    val shouldPause = showSettings || alertSheetState != null || isPaywallVisible || !isInForeground
    LaunchedEffect(shouldPause) {
        if (shouldPause) {
            viewModel.pause()
        } else {
            viewModel.resume()
        }
    }

    // ── CTA 강조: 배너 표시 시 활성화 ──

    LaunchedEffect(viewModel.showAlertBanner) {
        if (viewModel.showAlertBanner) {
            isFloatingCTAHighlighted = true
            ctaHighlightTriggerCount++
        }
    }

    // CTA 강조 10초 후 복귀 (새 트리거 시 타이머 리셋)
    LaunchedEffect(ctaHighlightTriggerCount) {
        if (ctaHighlightTriggerCount > 0) {
            delay(10_000)
            isFloatingCTAHighlighted = false
        }
    }

    // ── 환영 팝업 (세션당 1회) ──

    LaunchedEffect(Unit) {
        if (!LockedPreviewState.hasShownWelcomeThisSession) {
            delay(500)
            LockedPreviewState.hasShownWelcomeThisSession = true
            showWelcomeAlert = true
        }
    }

    // ── UI ──

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        val metrics = RateLayoutMetrics.fromWidth(maxWidth)
        CompositionLocalProvider(LocalRateLayoutMetrics provides metrics) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 상단 배너
                    SampleBannerWithCTA(onPrimaryAction = onPrimaryAction)

                    // 탭 선택기
                    LockedTabPicker(
                        onSettingsClick = { showSettings = true },
                        onLockedTabClick = onPrimaryAction
                    )

                    // 스크롤 콘텐츠
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 그래프 섹션 (Card)
                        SampleGraphSection(
                            graphData = graphData,
                            selectedSources = selectedSources,
                            onToggleSource = { source -> viewModel.toggleSource(source) }
                        )

                        // 환율 섹션 (Card)
                        SampleRatesSection(
                            sampleRates = sampleRates,
                            referenceRate = referenceRate,
                            minRate = minRate,
                            maxRate = maxRate
                        )

                        // 알림 설정 섹션 (Card)
                        SampleAlertSection(
                            viewModel = viewModel,
                            onAddTap = { alertSheetState = AlertSheetState.Add },
                            onEditTap = { setting -> alertSheetState = AlertSheetState.Edit(setting) }
                        )

                        // 플로팅 CTA 공간 확보
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }

                // 플로팅 CTA
                FloatingCTA(
                    label = if (isFloatingCTAHighlighted) "방금 예시 알림, 실제로 받아보기"
                    else primaryCTATitle,
                    isHighlighted = isFloatingCTAHighlighted,
                    showGiftIcon = showGiftIcon,
                    onClick = onPrimaryAction,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // 알림 트리거 배너 (상단 오버레이)
                SampleAlertTriggerBanner(
                    visible = viewModel.showAlertBanner,
                    alert = viewModel.triggeredAlert,
                    currentRate = viewModel.triggeredCurrentRate,
                    onDismiss = { viewModel.dismissAlertBanner() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }
        }
    }

    // 알림 추가/수정 시트
    when (val state = alertSheetState) {
        is AlertSheetState.Add -> {
            SampleAlertAddSheet(
                viewModel = viewModel,
                onDismiss = { alertSheetState = null }
            )
        }
        is AlertSheetState.Edit -> {
            SampleAlertAddSheet(
                viewModel = viewModel,
                editSetting = state.setting,
                onDismiss = { alertSheetState = null }
            )
        }
        null -> {}
    }

    // 환영 팝업
    if (showWelcomeAlert) {
        AlertDialog(
            onDismissRequest = { showWelcomeAlert = false },
            title = { Text("✨ 예시 화면입니다") },
            text = { Text("지금 보시는 환율과 그래프는 실제 데이터가 아닌 예시입니다.") },
            confirmButton = {
                TextButton(onClick = { showWelcomeAlert = false }) {
                    Text("알겠습니다")
                }
            }
        )
    }

    // 설정 화면
    if (showSettings) {
        SettingsScreen(
            userInfo = userInfo,
            onSignOut = onSignOut,
            showRatingOption = false,
            onDismiss = { showSettings = false }
        )
    }
}

// MARK: - 플로팅 CTA

/** iOS ctaGoldGradient 동일: Yellow → Orange 대각선 그라데이션 */
private val GoldColor = Color(0xFFF5A623)
private val GoldGradient = Brush.linearGradient(
    colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500))
)

@Composable
private fun FloatingCTA(
    label: String,
    isHighlighted: Boolean,
    showGiftIcon: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderAlpha = if (isHighlighted) 0.8f else 0.5f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(CardBackground.copy(alpha = 0.95f))
            .border(1.dp, Color(0xFFFFA500).copy(alpha = borderAlpha), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 아이콘: 체험 자격 → CardGiftcard, 기본 → WorkspacePremium (iOS crown.fill/gift.fill 대응)
            Icon(
                imageVector = if (showGiftIcon) Icons.Default.CardGiftcard else Icons.Default.WorkspacePremium,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isHighlighted) Color(0xFFFFA500) else GoldColor
            )
            Spacer(modifier = Modifier.width(10.dp))
            // 텍스트: 강조 시 orange, 기본 시 gold gradient (iOS foregroundStyle 파리티)
            if (isHighlighted) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFA500)
                )
            } else {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        brush = GoldGradient
                    )
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isHighlighted) Color(0xFFFFA500).copy(alpha = 0.7f)
                else GoldColor.copy(alpha = 0.7f)
            )
        }
    }
}

// MARK: - 상단 배너 (iOS sampleBannerWithCTA)

@Composable
private fun SampleBannerWithCTA(onPrimaryAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFA500).copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 오렌지 dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFA500))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "예시 화면",
            color = PrimaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "•",
            color = SecondaryText,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "가상 데이터입니다",
            color = SecondaryText,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "실제 환율 보기 →",
            color = Color(0xFFFFA500).copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier.clickable { onPrimaryAction() }
        )
    }
}

// MARK: - 탭 선택기 (iOS LockedTabPicker)

@Composable
private fun LockedTabPicker(
    onSettingsClick: () -> Unit,
    onLockedTabClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground)
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // USD 탭 (활성)
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = SupportedCurrency.USD_KRW.tabTitle,
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Primary)
            )
        }

        // JPY 탭 (잠금)
        LockedCurrencyTab(
            currency = SupportedCurrency.JPY_KRW,
            onClick = onLockedTabClick,
            modifier = Modifier.weight(1f)
        )

        // EUR 탭 (잠금)
        LockedCurrencyTab(
            currency = SupportedCurrency.EUR_KRW,
            onClick = onLockedTabClick,
            modifier = Modifier.weight(1f)
        )

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(36.dp)
        ) {
            Image(
                painter = painterResource(id = com.jay.fxi.R.drawable.ic_settings),
                contentDescription = "설정",
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(SecondaryText)
            )
        }
    }
}

@Composable
private fun LockedCurrencyTab(
    currency: SupportedCurrency,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = currency.tabTitle,
                color = SecondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = SecondaryText.copy(alpha = 0.7f),
                modifier = Modifier.size(10.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.height(2.dp))
    }
}

// MARK: - 그래프 섹션 (Card + 워터마크 + 태그)

@Composable
private fun SampleGraphSection(
    graphData: Map<GraphSource, List<com.jay.fxi.domain.model.GraphBucket>>,
    selectedSources: Set<GraphSource>,
    onToggleSource: (GraphSource) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 헤더: 타이틀 + 소스 토글
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "24시간 추이",
                color = PrimaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.weight(1f))
            SourceToggleRow(
                selectedSources = selectedSources,
                onToggle = onToggleSource
            )
        }

        // 그래프 + 워터마크 + 태그
        Box {
            RateGraphView(
                graphData = graphData,
                selectedSources = selectedSources,
                isLoading = false,
                modifier = Modifier.fillMaxWidth()
            )

            // SAMPLE 워터마크 (중앙 대각선)
            Text(
                text = "SAMPLE",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = SecondaryText.copy(alpha = 0.08f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .rotate(-15f)
            )

            // 좌상단 "예시 그래프" 태그
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = Color(0xFFFFA500).copy(alpha = 0.7f)
                )
                Text(
                    text = "예시 그래프",
                    fontSize = 11.sp,
                    color = Color(0xFFFFA500).copy(alpha = 0.7f)
                )
            }
        }
    }
}

// MARK: - 환율 섹션 (Card + 태그)

@Composable
private fun SampleRatesSection(
    sampleRates: List<com.jay.fxi.domain.model.ExchangeRate>,
    referenceRate: com.jay.fxi.domain.model.ExchangeRate?,
    minRate: Double,
    maxRate: Double,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
    ) {
        // 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.horizontalPadding)
                .padding(top = metrics.sectionPadding, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "은행별 환율",
                color = PrimaryText,
                fontSize = metrics.sectionTitleFontSize,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = Color(0xFFFFA500).copy(alpha = 0.7f)
                )
                Text(
                    text = "예시 데이터",
                    fontSize = 11.sp,
                    color = Color(0xFFFFA500).copy(alpha = 0.7f)
                )
            }
        }

        // 환율 바 리스트 (메인 CurrencyTabContent와 동일 간격)
        Column(
            modifier = Modifier.padding(
                horizontal = metrics.horizontalPadding,
                vertical = metrics.sectionPadding
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing)
        ) {
            sampleRates.sortedByBank().forEach { rate ->
                RateBarView(
                    rate = rate,
                    referenceRate = referenceRate,
                    minRate = minRate,
                    maxRate = maxRate
                )
            }
        }
    }
}
