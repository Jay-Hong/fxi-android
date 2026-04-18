package com.jay.fxi.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Image
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.domain.model.AppState
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.domain.model.TabSelection
import com.jay.fxi.ui.auth.AuthViewModel
import com.jay.fxi.ui.settings.SettingsScreen
import com.jay.fxi.ui.components.ConnectionStatusBanner
import com.jay.fxi.ui.components.ErrorView
import com.jay.fxi.ui.components.LoadingView
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.viewmodel.AlertViewModel
import com.jay.fxi.ui.viewmodel.BankPreferenceViewModel
import com.jay.fxi.ui.viewmodel.ExchangeRateViewModel
import com.jay.fxi.ui.viewmodel.GraphViewModel
import com.jay.fxi.ui.viewmodel.NewsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 메인 화면
 *
 * iOS ContentView와 동일한 역할:
 * - 통화 탭 선택기 (탭 터치 + 스와이프) + 뉴스 탭
 * - AppState에 따른 화면 분기
 * - 연결 상태 배너
 */
@Composable
fun MainScreen(
    isPremium: Boolean,
    authViewModel: AuthViewModel,
    exchangeRateViewModel: ExchangeRateViewModel = hiltViewModel(),
    graphViewModel: GraphViewModel = hiltViewModel(),
    alertViewModel: AlertViewModel = hiltViewModel(),
    bankPreferenceViewModel: BankPreferenceViewModel = hiltViewModel(),
    newsViewModel: NewsViewModel = hiltViewModel()
) {
    val appState by exchangeRateViewModel.appState.collectAsStateWithLifecycle()
    val connectionState by exchangeRateViewModel.connectionState.collectAsStateWithLifecycle()
    val lastUpdated by exchangeRateViewModel.lastUpdated.collectAsStateWithLifecycle()
    val dxyLive by exchangeRateViewModel.dxyLive.collectAsStateWithLifecycle()
    val activeCurrency by graphViewModel.activeCurrency.collectAsStateWithLifecycle()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    // News state
    val newsItems by newsViewModel.newsItems.collectAsStateWithLifecycle()
    val newsIsLoading by newsViewModel.isLoading.collectAsStateWithLifecycle()
    val newsError by newsViewModel.error.collectAsStateWithLifecycle()
    val newsRefreshTrigger by newsViewModel.refreshTrigger.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var newsDetailUrl by remember { mutableStateOf<String?>(null) }

    // TabSelection 기반 탭 관리
    val currencies = SupportedCurrency.entries
    val totalPages = currencies.size + 1 // 통화 3 + 뉴스 1
    val newsPageIndex = currencies.size   // 3

    var selectedTab by remember { mutableStateOf<TabSelection>(TabSelection.Currency(activeCurrency)) }
    var tabTargetPage by remember { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val pagerState = rememberPagerState(
        initialPage = currencies.indexOf(activeCurrency).coerceAtLeast(0),
        pageCount = { totalPages }
    )

    // 서비스 시작은 MainScreen 진입 시, 종료는 session scope(RootScreen의
    // logout/premium 해제 LaunchedEffect에서 reset() 호출)에서만 수행.
    // MainScreen 단순 dispose(회전 등)에서는 stop을 호출하지 않아 WebSocket/
    // connectionState가 유지되도록 함. 앱 종료 cleanup은 ViewModel.onCleared
    // 에서 처리.
    //
    // 왜: rotation 시 onDispose에서 stop() 호출 → connectionState Connecting
    // 전환 → ConnectionStatusBanner expand/shrink → 아래 콘텐츠 reflow로 화면
    // 출렁임 (iOS 대비 divergence). stop을 session scope로 올려 해소.
    DisposableEffect(Unit) {
        graphViewModel.start()
        exchangeRateViewModel.start()
        alertViewModel.loadSettingsIfNeeded()
        onDispose {
            newsViewModel.onTabDisappear()
        }
    }

    // 라이프사이클 이벤트 처리: 캐시 저장 + 포그라운드 복귀 동기화
    // sawStop: 초기 attach 시 ON_RESUME 무시, ON_STOP 이후의 복귀만 처리
    DisposableEffect(lifecycleOwner) {
        var sawStop = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    sawStop = true
                    graphViewModel.saveCache()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (sawStop) {
                        sawStop = false
                        alertViewModel.refreshOnForeground()
                        newsViewModel.onForegroundResume(isPremium)
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 외부에서 활성 통화가 바뀌면 UI 선택 상태도 동일하게 맞춘다.
    LaunchedEffect(activeCurrency) {
        val currentCurrency = selectedTab.currencyValue
        if (currentCurrency != null && activeCurrency != currentCurrency) {
            selectedTab = TabSelection.Currency(activeCurrency)
            val targetPage = currencies.indexOf(activeCurrency)
            if (targetPage >= 0 && targetPage != pagerState.settledPage) {
                tabTargetPage = targetPage
            }
        }
    }

    // currentPage는 스와이프가 절반을 넘으면 다음 페이지로 바뀐다.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                if (tabTargetPage != null) return@collect
                val newTab = if (page == newsPageIndex) {
                    TabSelection.News
                } else {
                    currencies.getOrNull(page)?.let { TabSelection.Currency(it) } ?: return@collect
                }
                if (newTab != selectedTab) {
                    selectedTab = newTab
                }
            }
    }

    // 탭 클릭으로 시작한 페이지 전환은 목표 페이지까지 중간 상태에 흔들리지 않고 끝까지 보낸다.
    LaunchedEffect(tabTargetPage) {
        val targetPage = tabTargetPage ?: return@LaunchedEffect
        try {
            if (targetPage != pagerState.settledPage || pagerState.currentPageOffsetFraction != 0f) {
                pagerState.animateScrollToPage(
                    page = targetPage,
                    animationSpec = tween(durationMillis = 400)
                )
            }
        } finally {
            if (tabTargetPage == targetPage) {
                tabTargetPage = null
                val settledTab = if (pagerState.settledPage == newsPageIndex) {
                    TabSelection.News
                } else {
                    currencies.getOrNull(pagerState.settledPage)
                        ?.let { TabSelection.Currency(it) }
                        ?: currencies.getOrNull(pagerState.currentPage)
                            ?.let { TabSelection.Currency(it) }
                }
                if (settledTab != null && settledTab != selectedTab) {
                    selectedTab = settledTab
                }
            }
        }
    }

    // 선택 상태가 바뀌면 ViewModel을 함께 동기화한다.
    LaunchedEffect(selectedTab) {
        when (val tab = selectedTab) {
            is TabSelection.Currency -> {
                newsViewModel.onTabDisappear()
                if (tab.currency != activeCurrency) {
                    graphViewModel.setActiveCurrency(tab.currency)
                }
                graphViewModel.loadGraph(tab.currency)
            }
            is TabSelection.News -> {
                newsViewModel.onTabAppear(isPremium)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 연결 상태 배너
        ConnectionStatusBanner(
            connectionState = connectionState,
            isOffline = appState.isOffline,
            isLoading = appState.isLoading,
            lastUpdated = lastUpdated,
            onReconnect = { exchangeRateViewModel.refresh() }
        )

        // 통화 탭 선택기 + 뉴스 탭 + 설정 버튼
        TabRow(
            selectedTab = selectedTab,
            onTabSelected = { tab ->
                if (tab != selectedTab) {
                    selectedTab = tab
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

        // 콘텐츠 영역 (스와이프 가능)
        // 뉴스 탭은 환율 API와 독립 — HorizontalPager를 항상 렌더링하고
        // 환율 상태(Loading/Error)는 통화 페이지 내부에서 분기
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().weight(1f),
            key = { if (it == newsPageIndex) "news" else currencies[it].code }
        ) { page ->
            if (page == newsPageIndex) {
                NewsTabContent(
                    newsItems = newsItems,
                    isLoading = newsIsLoading,
                    error = newsError,
                    refreshTrigger = newsRefreshTrigger,
                    isPremium = isPremium,
                    isTabVisible = pagerState.settledPage == newsPageIndex,
                    isDetailOpen = newsDetailUrl != null,
                    onRetry = { newsViewModel.retry(isPremium) },
                    onDetailOpen = { url -> newsDetailUrl = url }
                )
            } else {
                // 통화 페이지: AppState에 따라 분기
                when (val state = appState) {
                    is AppState.Loading -> {
                        LoadingView()
                    }
                    is AppState.Connected -> {
                        CurrencyTabContent(
                            currency = currencies[page],
                            rates = state.currentRates,
                            graphViewModel = graphViewModel,
                            alertViewModel = alertViewModel,
                            bankPreferenceViewModel = bankPreferenceViewModel,
                            isPremium = isPremium,
                            lastUpdated = lastUpdated,
                            dxyLive = dxyLive
                        )
                    }
                    is AppState.Offline -> {
                        val rates = state.cachedRates
                        if (rates != null) {
                            CurrencyTabContent(
                                currency = currencies[page],
                                rates = rates,
                                graphViewModel = graphViewModel,
                                alertViewModel = alertViewModel,
                                bankPreferenceViewModel = bankPreferenceViewModel,
                                isPremium = isPremium,
                                lastUpdated = lastUpdated,
                                dxyLive = dxyLive
                            )
                        } else {
                            ErrorView(message = "캐시된 데이터가 없습니다")
                        }
                    }
                    is AppState.Error -> {
                        ErrorView(
                            message = state.message,
                            onRetry = {
                                scope.launch {
                                    exchangeRateViewModel.refresh()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 설정 화면
    if (showSettings) {
        val userInfo = (authState as? AuthState.SignedIn)?.user
        if (userInfo != null) {
            SettingsScreen(
                userInfo = userInfo,
                onSignOut = { authViewModel.signOut() },
                onDismiss = { showSettings = false }
            )
        }
    }

    // 뉴스 상세 풀스크린 오버레이 (탭 바 포함 전체 화면 덮음, iOS fullScreenCover 패리티)
    newsDetailUrl?.let { url ->
        NewsDetailOverlay(
            url = url,
            onDismiss = { newsDetailUrl = null }
        )
    }
}

/**
 * 탭 선택기 (통화 3개 + 뉴스 + 설정 버튼)
 */
@Composable
private fun TabRow(
    selectedTab: TabSelection,
    onTabSelected: (TabSelection) -> Unit,
    onSettingsClick: () -> Unit
) {
    val currencies = SupportedCurrency.entries

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        // 통화 탭
        currencies.forEach { currency ->
            val isSelected = selectedTab is TabSelection.Currency && selectedTab.currency == currency
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(TabSelection.Currency(currency)) },
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    text = currency.tabTitle,
                    fontSize = 14.sp,
                    color = if (isSelected) PrimaryText else SecondaryText,
                    fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (isSelected) Primary else androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }

        // 뉴스 탭
        val isNewsSelected = selectedTab is TabSelection.News
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onTabSelected(TabSelection.News) },
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(
                text = "뉴스",
                fontSize = 14.sp,
                color = if (isNewsSelected) PrimaryText else SecondaryText,
                fontWeight = if (isNewsSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(if (isNewsSelected) Primary else androidx.compose.ui.graphics.Color.Transparent)
            )
        }

        // 설정 버튼
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
