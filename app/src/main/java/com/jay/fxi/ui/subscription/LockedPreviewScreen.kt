package com.jay.fxi.ui.subscription

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.subscription.SubscriptionManager
import com.jay.fxi.domain.model.BankDisplayConfig
import com.jay.fxi.domain.model.BankPreferenceItem
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.domain.model.TabSelection
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.ui.components.BankCustomizeSheet
import com.jay.fxi.ui.components.DxyToggleButton
import com.jay.fxi.ui.components.PeriodTabBar
import com.jay.fxi.ui.components.RateBarView
import com.jay.fxi.ui.components.RateGraphView
import com.jay.fxi.ui.components.SourceToggleRow
import com.jay.fxi.ui.screen.NewsDetailOverlay
import com.jay.fxi.ui.screen.NewsTabContent
import com.jay.fxi.ui.settings.SettingsScreen
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.viewmodel.BankPreferenceViewModel
import com.jay.fxi.ui.viewmodel.NewsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
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
    isPaywallVisible: Boolean = false,
    bankPreferenceViewModel: BankPreferenceViewModel = hiltViewModel(),
    newsViewModel: NewsViewModel = hiltViewModel()
) {
    val viewModel = remember { SamplePreviewViewModel() }
    val scope = rememberCoroutineScope()
    val currencies = SupportedCurrency.entries
    val totalPages = currencies.size + 1  // 통화 3 + 뉴스 1
    val newsPageIndex = currencies.size    // 3

    // 화면 레벨 TabSelection (SamplePreviewViewModel.selectedCurrency와 별도 관리)
    var selectedTab by remember { mutableStateOf<TabSelection>(TabSelection.Currency(SupportedCurrency.USD_KRW)) }

    // News state
    val newsItems by newsViewModel.newsItems.collectAsStateWithLifecycle()
    val newsIsLoading by newsViewModel.isLoading.collectAsStateWithLifecycle()
    val newsError by newsViewModel.error.collectAsStateWithLifecycle()
    val newsRefreshTrigger by newsViewModel.refreshTrigger.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { totalPages }
    )
    var tabTargetPage by remember { mutableStateOf<Int?>(null) }

    // 스와이프 50% 넘으면 탭 밑줄 즉시 반응 (MainScreen과 동일 패턴)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                if (tabTargetPage != null) return@collect
                val newTab = if (page == newsPageIndex) {
                    TabSelection.News
                } else {
                    val currency = currencies.getOrNull(page) ?: return@collect
                    if (currency != viewModel.selectedCurrency) {
                        viewModel.selectCurrency(currency)
                    }
                    TabSelection.Currency(currency)
                }
                if (newTab != selectedTab) {
                    selectedTab = newTab
                }
            }
    }

    // 탭 클릭 애니메이션
    LaunchedEffect(tabTargetPage) {
        val targetPage = tabTargetPage ?: return@LaunchedEffect
        try {
            if (targetPage != pagerState.settledPage || pagerState.currentPageOffsetFraction != 0f) {
                pagerState.animateScrollToPage(page = targetPage)
            }
        } finally {
            if (tabTargetPage == targetPage) {
                tabTargetPage = null
            }
        }
    }

    // News 탭 라이프사이클
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            is TabSelection.News -> newsViewModel.onTabAppear(isPremium = false)
            is TabSelection.Currency -> newsViewModel.onTabDisappear()
        }
    }

    // 뉴스 VM 정리
    DisposableEffect(Unit) {
        onDispose { newsViewModel.onTabDisappear() }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showBankCustomize by remember { mutableStateOf(false) }
    var newsDetailUrl by remember { mutableStateOf<String?>(null) }
    var showWelcomeAlert by remember { mutableStateOf(false) }
    var alertSheetState by remember { mutableStateOf<AlertSheetState?>(null) }
    var isFloatingCTAHighlighted by remember { mutableStateOf(false) }
    var ctaHighlightTriggerCount by remember { mutableIntStateOf(0) }
    var isGraphFullscreen by remember { mutableStateOf(false) }
    var isRatesFullscreen by remember { mutableStateOf(false) }

    // ── SubscriptionManager 연동 (iOS 파리티) ──
    val trialDurationText by subscriptionManager.trialDurationText.collectAsStateWithLifecycle()
    val showGiftIcon = trialDurationText != null
    val primaryCTATitle = if (trialDurationText != null) "첫 구독자 $trialDurationText 무료" else "프리미엄 구독하기"
    val bankDisplayConfig by bankPreferenceViewModel.displayConfig.collectAsStateWithLifecycle()
    val orderedBanks by bankPreferenceViewModel.orderedBanks.collectAsStateWithLifecycle()

    // offerings preload (iOS onAppear에서 loadOfferings 호출과 동일)
    LaunchedEffect(Unit) {
        subscriptionManager.loadOfferings()
    }

    // ── ViewModel 데이터 ──

    val selectedCurrency = selectedTab.currencyValue ?: viewModel.selectedCurrency
    val sampleRates = viewModel.rates
    val graphData = viewModel.graphData
    val selectedSources = viewModel.selectedSources
    val activePeriod = viewModel.activePeriod
    val dxyVisible = viewModel.dxyVisible

    val sampleDisplayState = remember(sampleRates, selectedCurrency, bankDisplayConfig) {
        viewModel.displayState(bankDisplayConfig)
    }
    val sampleGraphPayload = remember(
        graphData,
        selectedCurrency,
        selectedSources,
        activePeriod,
        dxyVisible
    ) {
        buildSampleGraphPayload(
            rawGraphData = graphData,
            currency = selectedCurrency,
            selectedSources = selectedSources,
            activePeriod = activePeriod,
            dxyVisible = dxyVisible
        )
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

    val shouldPause = showSettings || showBankCustomize || alertSheetState != null || isPaywallVisible || !isInForeground
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
        val metrics = RateLayoutMetrics.fromWindow(maxWidth, maxHeight)
        CompositionLocalProvider(LocalRateLayoutMetrics provides metrics) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isGraphFullscreen -> {
                        SampleFullscreenGraphView(
                            viewModel = viewModel,
                            currency = selectedCurrency,
                            graphPayload = sampleGraphPayload,
                            onClose = { isGraphFullscreen = false }
                        )
                    }

                    isRatesFullscreen -> {
                        SampleFullscreenRatesView(
                            displayState = sampleDisplayState,
                            orderedBanks = orderedBanks,
                            onCustomize = { showBankCustomize = true },
                            onClose = { isRatesFullscreen = false }
                        )
                    }

                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            SampleBannerWithCTA(onPrimaryAction = onPrimaryAction)

                            LockedTabPicker(
                                selectedTab = selectedTab,
                                onTabSelected = { tab ->
                                    if (tab != selectedTab) {
                                        selectedTab = tab
                                        if (tab is TabSelection.Currency) {
                                            viewModel.selectCurrency(tab.currency)
                                        }
                                        val targetPage = when (tab) {
                                            is TabSelection.Currency -> currencies.indexOf(tab.currency)
                                            is TabSelection.News -> newsPageIndex
                                        }
                                        if (targetPage >= 0) {
                                            tabTargetPage = targetPage
                                        }
                                    }
                                },
                                onSettingsClick = { showSettings = true }
                            )

                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                key = { if (it == newsPageIndex) "news" else currencies[it].code }
                            ) { page ->
                                if (page == newsPageIndex) {
                                    NewsTabContent(
                                        newsItems = newsItems,
                                        isLoading = newsIsLoading,
                                        error = newsError,
                                        refreshTrigger = newsRefreshTrigger,
                                        isPremium = false,
                                        isTabVisible = pagerState.settledPage == newsPageIndex,
                                        isDetailOpen = newsDetailUrl != null,
                                        onRetry = { newsViewModel.retry(isPremium = false) },
                                        onDetailOpen = { url -> newsDetailUrl = url }
                                    )
                                } else {
                                    val pageCurrency = currencies[page]
                                    val pageRates = viewModel.rates
                                    val pageDisplayState = remember(pageRates, pageCurrency, bankDisplayConfig) {
                                        viewModel.displayState(pageCurrency, bankDisplayConfig)
                                    }
                                    val pageGraphData = viewModel.graphData
                                    val pageGraphPayload = remember(
                                        pageGraphData, pageCurrency,
                                        selectedSources, activePeriod, dxyVisible
                                    ) {
                                        buildSampleGraphPayload(
                                            rawGraphData = pageGraphData,
                                            currency = pageCurrency,
                                            selectedSources = selectedSources,
                                            activePeriod = activePeriod,
                                            dxyVisible = dxyVisible
                                        )
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        SampleGraphSection(
                                            viewModel = viewModel,
                                            currency = pageCurrency,
                                            graphPayload = pageGraphPayload,
                                            onFullscreenTap = { isGraphFullscreen = true }
                                        )

                                        SampleRatesSection(
                                            displayState = pageDisplayState,
                                            onCustomize = { showBankCustomize = true },
                                            onFullscreenTap = { isRatesFullscreen = true }
                                        )

                                        SampleAlertSection(
                                            viewModel = viewModel,
                                            onAddTap = { alertSheetState = AlertSheetState.Add },
                                            onEditTap = { setting -> alertSheetState = AlertSheetState.Edit(setting) }
                                        )

                                        Spacer(modifier = Modifier.height(100.dp))
                                    }
                                }
                            }
                        }

                        FloatingCTA(
                            label = if (isFloatingCTAHighlighted) "방금 예시 알림, 실제로 받아보기"
                            else primaryCTATitle,
                            isHighlighted = isFloatingCTAHighlighted,
                            showGiftIcon = showGiftIcon,
                            onClick = onPrimaryAction,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }

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

    if (showBankCustomize) {
        BankCustomizeSheet(
            orderedBanks = orderedBanks,
            onApply = { items ->
                bankPreferenceViewModel.apply(items)
                showBankCustomize = false
            },
            onDismiss = { showBankCustomize = false }
        )
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

    // 뉴스 상세 풀스크린 오버레이 (탭 바 포함 전체 화면 덮음)
    newsDetailUrl?.let { url ->
        NewsDetailOverlay(
            url = url,
            onDismiss = { newsDetailUrl = null }
        )
    }
}

private data class SampleGraphPayload(
    val rateGraphData: Map<GraphSource, List<com.jay.fxi.domain.model.GraphBucket>>,
    val dxyGraphData: List<com.jay.fxi.domain.model.GraphBucket>
)

@Composable
private fun SampleFullscreenGraphView(
    viewModel: SamplePreviewViewModel,
    currency: SupportedCurrency,
    graphPayload: SampleGraphPayload,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 8.dp, bottom = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (viewModel.hasDxyData(currency)) {
                    DxyToggleButton(
                        isSelected = viewModel.dxyVisible,
                        onClick = { viewModel.toggleDxy() }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (viewModel.activePeriod == GraphPeriod.ONE_DAY) {
                    SourceToggleRow(
                        selectedSources = viewModel.selectedSources,
                        onToggle = { source -> viewModel.toggleSource(source) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }

                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "닫기",
                    tint = SecondaryText,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onClose() }
                )
            }

            if (viewModel.activePeriod != GraphPeriod.ONE_DAY) {
                Text(
                    text = currency.displayName,
                    color = SecondaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBackground)
                .padding(12.dp)
        ) {
            // Note: outer detectTapGestures(onDoubleTap = onClose) 제거.
            // Live(CurrencyTabContent) M3-b 정책 — fullscreen 종료는 Cancel 아이콘만,
            // 그래프 내부 더블탭은 RateGraphView 내부 6h zoom 토글에 양보.
            RateGraphView(
                rateGraphData = graphPayload.rateGraphData,
                dxyGraphData = graphPayload.dxyGraphData,
                period = viewModel.activePeriod,
                isLoading = false,
                modifier = Modifier.fillMaxSize()
            )

            Text(
                text = "SAMPLE",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = SecondaryText.copy(alpha = 0.08f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .rotate(-15f)
            )
        }

        PeriodTabBar(
            activePeriod = viewModel.activePeriod,
            onSelectPeriod = { period -> viewModel.selectPeriod(period) },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp)
        )
    }
}

@Composable
private fun SampleFullscreenRatesView(
    displayState: com.jay.fxi.domain.model.RatesDisplayState,
    orderedBanks: List<BankPreferenceItem>,
    onCustomize: () -> Unit,
    onClose: () -> Unit,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 8.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "은행별 환율",
                color = PrimaryText,
                fontSize = metrics.sectionTitleFontSize,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(InputBackground)
                    .clickable { onCustomize() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "은행 설정",
                    tint = SecondaryText,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "예시 데이터",
                color = Color(0xFFFFA500).copy(alpha = 0.7f),
                fontSize = 11.sp
            )
            Icon(
                imageVector = Icons.Default.Cancel,
                contentDescription = "닫기",
                tint = SecondaryText,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(22.dp)
                    .clickable { onClose() }
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBackground)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onClose() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = metrics.horizontalPadding, vertical = metrics.sectionPadding),
                verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing)
            ) {
                if (displayState.rates.isEmpty()) {
                    Text(
                        text = "환율 데이터가 없습니다",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                } else {
                    displayState.rates.forEach { rate ->
                        RateBarView(
                            rate = rate,
                            referenceRate = displayState.referenceRate,
                            minRate = displayState.range?.first ?: rate.rate,
                            maxRate = displayState.range?.second ?: rate.rate
                        )
                    }
                }
            }
        }
    }
}

private fun buildSampleGraphPayload(
    rawGraphData: Map<String, List<com.jay.fxi.domain.model.GraphBucket>>,
    currency: SupportedCurrency,
    selectedSources: Set<GraphSource>,
    activePeriod: GraphPeriod,
    dxyVisible: Boolean
): SampleGraphPayload {
    val rateSources = if (activePeriod == GraphPeriod.ONE_DAY) {
        selectedSources
    } else {
        setOf(GraphSource.REFERENCE)
    }

    val rateGraphData = rateSources.associateWith { source ->
        rawGraphData[source.code].orEmpty()
    }.filterValues { it.isNotEmpty() }

    val dxyGraphData = if (
        currency == SupportedCurrency.USD_KRW && dxyVisible
    ) {
        rawGraphData[GraphSource.DXY.code].orEmpty()
    } else {
        emptyList()
    }

    return SampleGraphPayload(
        rateGraphData = rateGraphData,
        dxyGraphData = dxyGraphData
    )
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(CardBackground.copy(alpha = 0.95f))
            .border(1.dp, Color(0xFFFFA500).copy(alpha = 0.6f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 20.dp)
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
                    color = Color(0xFFFFA500),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
            } else {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        brush = GoldGradient
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
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
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "•", color = SecondaryText, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "가상 데이터입니다",
            color = SecondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "실제 환율 보기 →",
            color = Color(0xFFFFA500).copy(alpha = 0.8f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.clickable { onPrimaryAction() }
        )
    }
}

// MARK: - 탭 선택기 (iOS LockedTabPicker + News)

@Composable
private fun LockedTabPicker(
    selectedTab: TabSelection,
    onTabSelected: (TabSelection) -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground)
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SupportedCurrency.entries.forEach { currency ->
            val isSelected = selectedTab is TabSelection.Currency && selectedTab.currency == currency
            PreviewTab(
                label = currency.tabTitle,
                isSelected = isSelected,
                onClick = { onTabSelected(TabSelection.Currency(currency)) },
                modifier = Modifier.weight(1f)
            )
        }

        // 뉴스 탭
        PreviewTab(
            label = "뉴스",
            isSelected = selectedTab is TabSelection.News,
            onClick = { onTabSelected(TabSelection.News) },
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
private fun PreviewTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = if (isSelected) PrimaryText else SecondaryText,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (isSelected) Primary else Color.Transparent)
        )
    }
}

// MARK: - 그래프 섹션 (Card + 워터마크 + 태그)

@Composable
private fun SampleGraphSection(
    viewModel: SamplePreviewViewModel,
    currency: SupportedCurrency,
    graphPayload: SampleGraphPayload,
    onFullscreenTap: () -> Unit
) {
    // Note: outer detectTapGestures(onDoubleTap = onFullscreenTap) 제거.
    // 이유: 섹션 전체 영역의 double-tap wrapper가 RateGraphView 내부 M3-a 더블탭 zoom을
    // 가로채서 6h zoom 대신 fullscreen 진입으로 동작하던 M3-b 정책 전파 누락 해소.
    // fullscreen 진입은 OpenInFull 아이콘 전용 (Live와 동일 정책).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (viewModel.hasDxyData(currency)) {
                    DxyToggleButton(
                        isSelected = viewModel.dxyVisible,
                        onClick = { viewModel.toggleDxy() }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (viewModel.activePeriod == GraphPeriod.ONE_DAY) {
                    SourceToggleRow(
                        selectedSources = viewModel.selectedSources,
                        onToggle = { source -> viewModel.toggleSource(source) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(InputBackground)
                        .clickable { onFullscreenTap() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "전체 화면",
                        tint = SecondaryText,
                        modifier = Modifier.size(14.dp).rotate(90f)
                    )
                }
            }

            if (viewModel.activePeriod != GraphPeriod.ONE_DAY) {
                Text(
                    text = currency.displayName,
                    color = SecondaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box {
            RateGraphView(
                rateGraphData = graphPayload.rateGraphData,
                dxyGraphData = graphPayload.dxyGraphData,
                period = viewModel.activePeriod,
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

            // "예시 그래프" 태그 — Y축 환율 범례 숫자 오른쪽 끝 정렬 (iOS 파리티)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-10).dp)
                    .padding(end = 2.dp),
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

        Spacer(modifier = Modifier.height(8.dp))

        PeriodTabBar(
            activePeriod = viewModel.activePeriod,
            onSelectPeriod = { period -> viewModel.selectPeriod(period) }
        )
    }
}

// MARK: - 환율 섹션 (Card + 태그)

@Composable
private fun SampleRatesSection(
    displayState: com.jay.fxi.domain.model.RatesDisplayState,
    onCustomize: () -> Unit,
    onFullscreenTap: () -> Unit,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onFullscreenTap() }
                )
            }
    ) {
        // 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.horizontalPadding)
                .padding(top = metrics.sectionPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "은행별 환율",
                color = PrimaryText,
                fontSize = metrics.sectionTitleFontSize,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(InputBackground)
                    .clickable { onCustomize() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "은행 설정",
                    tint = SecondaryText,
                    modifier = Modifier.size(14.dp)
                )
            }
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
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(InputBackground)
                    .clickable { onFullscreenTap() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "전체 화면",
                    tint = SecondaryText,
                    modifier = Modifier.size(14.dp).rotate(90f)
                )
            }
        }

        Spacer(modifier = Modifier.height(metrics.ratesHeaderBottomSpacing))

        if (displayState.rates.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "환율 데이터가 없습니다",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(horizontal = metrics.horizontalPadding)
                    .padding(bottom = metrics.ratesSectionBottomPadding),
                verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing)
            ) {
                displayState.rates.forEach { rate ->
                    RateBarView(
                        rate = rate,
                        referenceRate = displayState.referenceRate,
                        minRate = displayState.range?.first ?: rate.rate,
                        maxRate = displayState.range?.second ?: rate.rate
                    )
                }
            }
        }
    }
}
