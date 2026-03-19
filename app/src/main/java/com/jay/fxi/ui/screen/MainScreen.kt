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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 메인 화면
 *
 * iOS ContentView와 동일한 역할:
 * - 통화 탭 선택기 (탭 터치 + 스와이프)
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
    bankPreferenceViewModel: BankPreferenceViewModel = hiltViewModel()
) {
    val appState by exchangeRateViewModel.appState.collectAsStateWithLifecycle()
    val connectionState by exchangeRateViewModel.connectionState.collectAsStateWithLifecycle()
    val lastUpdated by exchangeRateViewModel.lastUpdated.collectAsStateWithLifecycle()
    val activeCurrency by graphViewModel.activeCurrency.collectAsStateWithLifecycle()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var selectedCurrency by remember { mutableStateOf(activeCurrency) }
    var tabTargetPage by remember { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currencies = SupportedCurrency.entries

    val pagerState = rememberPagerState(
        initialPage = currencies.indexOf(activeCurrency).coerceAtLeast(0),
        pageCount = { currencies.size }
    )

    // 서비스 시작/종료 (MainScreen 생명주기와 동기화)
    DisposableEffect(Unit) {
        graphViewModel.start()
        exchangeRateViewModel.start()
        alertViewModel.loadSettingsIfNeeded()
        onDispose {
            graphViewModel.stop()
            exchangeRateViewModel.stop()
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
        if (activeCurrency != selectedCurrency) {
            selectedCurrency = activeCurrency
            val targetPage = currencies.indexOf(activeCurrency)
            if (targetPage >= 0 && targetPage != pagerState.settledPage) {
                tabTargetPage = targetPage
            }
        }
    }

    // currentPage는 스와이프가 절반을 넘으면 다음 페이지로 바뀐다.
    // iOS TabView(selection:) 체감과 맞추기 위해 탭 강조 상태도 이 시점에 함께 갱신한다.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                if (tabTargetPage != null) return@collect
                val currency = currencies.getOrNull(page) ?: return@collect
                if (currency != selectedCurrency) {
                    selectedCurrency = currency
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
                val settledCurrency = currencies.getOrNull(pagerState.settledPage)
                    ?: currencies.getOrNull(pagerState.currentPage)
                if (settledCurrency != null && settledCurrency != selectedCurrency) {
                    selectedCurrency = settledCurrency
                }
            }
        }
    }

    // 선택 상태가 바뀌면 ViewModel을 함께 동기화한다.
    LaunchedEffect(selectedCurrency) {
        if (selectedCurrency != activeCurrency) {
            graphViewModel.setActiveCurrency(selectedCurrency)
        }
        graphViewModel.loadGraph(selectedCurrency)
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

        // 통화 탭 선택기 + 설정 버튼 (iOS currencyTabPicker 구조)
        CurrencyTabRow(
            selectedCurrency = selectedCurrency,
            onCurrencySelected = { currency ->
                if (currency != selectedCurrency) {
                    selectedCurrency = currency
                    val targetPage = currencies.indexOf(currency)
                    if (targetPage >= 0) {
                        tabTargetPage = targetPage
                    }
                }
            },
            onSettingsClick = { showSettings = true }
        )

        // 콘텐츠 영역 (스와이프 가능)
        when (val state = appState) {
            is AppState.Loading -> {
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    LoadingView()
                }
            }
            is AppState.Connected -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().weight(1f),
                    key = { currencies[it].code }
                ) { page ->
                    CurrencyTabContent(
                        currency = currencies[page],
                        rates = state.currentRates,
                        graphViewModel = graphViewModel,
                        alertViewModel = alertViewModel,
                        bankPreferenceViewModel = bankPreferenceViewModel,
                        isPremium = isPremium,
                        lastUpdated = lastUpdated
                    )
                }
            }
            is AppState.Offline -> {
                state.cachedRates?.let { rates ->
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().weight(1f),
                        key = { currencies[it].code }
                    ) { page ->
                        CurrencyTabContent(
                            currency = currencies[page],
                            rates = rates,
                            graphViewModel = graphViewModel,
                            alertViewModel = alertViewModel,
                            bankPreferenceViewModel = bankPreferenceViewModel,
                            isPremium = isPremium,
                            lastUpdated = lastUpdated
                        )
                    }
                } ?: Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    ErrorView(message = "캐시된 데이터가 없습니다")
                }
            }
            is AppState.Error -> {
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
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
}

/**
 * 통화 탭 선택기 + 설정 버튼 (iOS currencyTabPicker 완전 재현)
 * iOS: HStack(alignment: .center, spacing: 0) + .padding(.horizontal, 12) + .padding(.top, 4)
 */
@Composable
private fun CurrencyTabRow(
    selectedCurrency: SupportedCurrency,
    onCurrencySelected: (SupportedCurrency) -> Unit,
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
        currencies.forEach { currency ->
            val isSelected = currency == selectedCurrency
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onCurrencySelected(currency) },
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    text = currency.displayName,
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

        // iOS: SettingsButton().frame(width: 36, height: 36).padding(.leading, 12)
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
