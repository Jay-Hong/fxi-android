package com.jay.fxi.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Tune
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.displayState
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.GraphBucket
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.domain.model.SupportedCurrency
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.jay.fxi.ui.alert.AlertAddSheet
import com.jay.fxi.ui.alert.AlertSection
import com.jay.fxi.ui.components.BankCustomizeSheet
import com.jay.fxi.ui.components.DxyToggleButton
import com.jay.fxi.ui.components.PeriodTabBar
import com.jay.fxi.ui.components.RateBarView
import com.jay.fxi.ui.components.RateGraphView
import com.jay.fxi.ui.components.SourceToggleRow
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.viewmodel.AlertViewModel
import com.jay.fxi.ui.viewmodel.BankPreferenceViewModel
import com.jay.fxi.ui.viewmodel.GraphViewModel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 통화별 콘텐츠 화면 (iOS CurrencyTabView와 동일한 구조)
 *
 * - 일반 모드: 그래프 섹션 + 환율 리스트 섹션
 * - 그래프 전체 화면: 그래프만 표시 (더블탭 또는 확대 버튼)
 * - 환율 전체 화면: 환율 리스트만 표시 (더블탭 또는 확대 버튼)
 */
@Composable
fun CurrencyTabContent(
    currency: SupportedCurrency,
    rates: List<ExchangeRate>,
    graphViewModel: GraphViewModel,
    alertViewModel: AlertViewModel,
    bankPreferenceViewModel: BankPreferenceViewModel,
    isPremium: Boolean,
    lastUpdated: Instant? = null,
    /** WebSocket indices.dxy (10초 live). null이면 graphCache fallback. iOS dxyLive parity. */
    dxyLive: com.jay.fxi.data.remote.dto.DxyLiveTick? = null
) {
    val alertState by alertViewModel.state.collectAsStateWithLifecycle()
    val hasPermission by alertViewModel.hasNotificationPermission.collectAsStateWithLifecycle()
    val isPermissionDenied by alertViewModel.isPermissionDenied.collectAsStateWithLifecycle()
    val isRefreshing by alertViewModel.isRefreshing.collectAsStateWithLifecycle()
    val canRefresh by alertViewModel.canManualRefresh.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }
    var editSetting by remember { mutableStateOf<com.jay.fxi.domain.model.AlertSetting?>(null) }
    var initialBank by remember { mutableStateOf<Bank?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var showBankCustomizeSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current

    // Permission request for POST_NOTIFICATIONS (Android 13+)
    var pendingCreate by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingShowAddSheet by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        alertViewModel.onPermissionResult(
            granted = granted,
            pendingCreate = pendingCreate
        )
        pendingCreate = null
        if (granted && pendingShowAddSheet) {
            showAddSheet = true
        }
        pendingShowAddSheet = false
    }

    // 설정 앱에서 돌아올 때 권한 상태 갱신
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                alertViewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val selectedSources by graphViewModel.selectedSources.collectAsStateWithLifecycle()
    val activePeriod by graphViewModel.activePeriod.collectAsStateWithLifecycle()
    val dxyVisible by graphViewModel.dxyVisible.collectAsStateWithLifecycle()
    val graphVersion by graphViewModel.graphVersion.collectAsStateWithLifecycle()
    val isLoading by graphViewModel.isLoading.collectAsStateWithLifecycle()
    val isGraphFullscreen by graphViewModel.isGraphFullscreen.collectAsStateWithLifecycle()
    val isRatesFullscreen by graphViewModel.isRatesFullscreen.collectAsStateWithLifecycle()
    val bankDisplayConfig by bankPreferenceViewModel.displayConfig.collectAsStateWithLifecycle()
    val orderedBanks by bankPreferenceViewModel.orderedBanks.collectAsStateWithLifecycle()

    // 현재 통화의 환율만 필터링
    val filteredRates = remember(rates, currency) {
        rates.filter { it.currency == currency.code }
    }

    // iOS v2.6 parity: follow + zoom + 1d 3조건 모두 true일 때만 10초 synthetic tick.
    // isFollowActive는 RateGraphView에서 onFollowActiveChanged 콜백으로 전달받음.
    // 상태 변경 시 LaunchedEffect가 자동 취소/재기동. 조건 이탈 시 tick 중단.
    var isFollowActive by remember { mutableStateOf(false) }
    var followTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(isFollowActive) {
        if (!isFollowActive) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(10_000L)
            followTick = followTick.inc()
        }
    }

    val ratesDisplayState = remember(filteredRates, currency, bankDisplayConfig) {
        filteredRates.displayState(currency, bankDisplayConfig)
    }
    val displayedRates = ratesDisplayState.rates
    val referenceRate = ratesDisplayState.referenceRate
    val rateRange = ratesDisplayState.range

    // 그래프 데이터
    val rawGraphData by produceState<Map<GraphSource, List<GraphBucket>>>(
        initialValue = emptyMap<GraphSource, List<GraphBucket>>(),
        currency,
        activePeriod,
        selectedSources,
        dxyVisible,
        graphVersion
    ) {
        val data = mutableMapOf<GraphSource, List<GraphBucket>>()
        val sources: Set<GraphSource> = when (activePeriod) {
            GraphPeriod.ONE_DAY -> selectedSources
            else -> setOf(GraphSource.REFERENCE)
        } + if (currency == SupportedCurrency.USD_KRW && dxyVisible) {
            setOf(GraphSource.DXY)
        } else {
            emptySet()
        }

        for (source in sources) {
            val buckets = graphViewModel.getGraphBuckets(currency.code, source)
            data[source] = buckets
        }
        value = data
    }

    val graphPayload = remember(
        rawGraphData,
        filteredRates,
        currency,
        selectedSources,
        activePeriod,
        dxyVisible,
        graphVersion,
        dxyLive,
        followTick
    ) {
        buildGraphPayload(
            rawGraphData = rawGraphData,
            filteredRates = filteredRates,
            currency = currency,
            selectedSources = selectedSources,
            activePeriod = activePeriod,
            dxyVisible = dxyVisible,
            // iOS parity: live tick(10초) 우선, 없으면 graphCache 마지막 버킷 close (1분 stale) fallback.
            latestDxyRate = dxyLive?.rate ?: graphViewModel.latestDxyRate(),
            hasFreshPeriodCache = graphViewModel.isPeriodGraphFresh(currency, activePeriod)
        )
    }

    // 업데이트 시간 포맷
    val updateTimeStr = remember(lastUpdated) {
        lastUpdated?.let {
            val kst = TimeZone.of("Asia/Seoul")
            val dt = it.toLocalDateTime(kst)
            "업데이트: %02d:%02d".format(dt.hour, dt.minute)
        }
    }

    // 실제 윈도우 폭 기준으로 메트릭스 선택 (split-screen 대응)
    BoxWithConstraints {
        val metrics = RateLayoutMetrics.fromWindow(maxWidth, maxHeight)

        CompositionLocalProvider(LocalRateLayoutMetrics provides metrics) {
            when {
                isGraphFullscreen -> {
                    // 전체 화면 그래프 모드
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .padding(horizontal = metrics.horizontalPadding)
                            .padding(top = 8.dp, bottom = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currency == SupportedCurrency.USD_KRW) {
                                    DxyToggleButton(
                                        isSelected = dxyVisible,
                                        onClick = { graphViewModel.toggleDxy() }
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                if (activePeriod == GraphPeriod.ONE_DAY) {
                                    SourceToggleRow(
                                        selectedSources = selectedSources,
                                        onToggle = { source -> graphViewModel.toggleSource(source) }
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                }

                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "축소",
                                    tint = SecondaryText,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clickable { graphViewModel.setGraphFullscreen(false) }
                                )
                            }

                            if (activePeriod != GraphPeriod.ONE_DAY) {
                                Text(
                                    text = currency.displayName,
                                    color = SecondaryText,
                                    fontSize = 11.sp,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }

                        // 그래프 (남은 공간 전체)
                        // M3-b: fullscreen 종료는 Cancel 아이콘으로만 수행.
                        // [iOS divergence — 의도적]
                        //   iOS는 fullscreen 안에서 단일 탭 종료 + 더블탭 줌을 UIKit recognizer chain
                        //   (double-tap-to-fail)으로 공존시킨다. Compose에서 동일 패턴을 구현하려면
                        //   RateGraphView에 onSingleTap 콜백 파라미터를 추가하고 manual tap 감지 내부에서
                        //   단일 탭 분기를 외부로 전달해야 하므로 API 확장 + 테스트 부담이 크다.
                        //   과거 구현은 wrapper에 detectTapGestures(onDoubleTap = exit)를 두었으나,
                        //   wrapper가 outer에서 event를 consume해 RateGraphView 내부 M3-a 더블탭 줌이
                        //   먹통이 되는 역회귀가 발생했다.
                        //   ETC 원칙에 따라 iOS parity 대신 "Cancel 아이콘 단일 종료" 정책을 의도적으로 채택.
                        //   향후 단일 탭 종료 affordance가 꼭 필요해지면 RateGraphView 콜백 확장으로 재검토.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBackground)
                                .padding(metrics.sectionPadding)
                        ) {
                            RateGraphView(
                                rateGraphData = graphPayload.rateGraphData,
                                dxyGraphData = graphPayload.dxyGraphData,
                                period = activePeriod,
                                isLoading = isLoading,
                                modifier = Modifier.fillMaxSize(),
                                onFollowActiveChanged = { isFollowActive = it }
                            )
                        }

                        PeriodTabBar(
                            activePeriod = activePeriod,
                            onSelectPeriod = { period ->
                                graphViewModel.setActivePeriod(period)
                                scope.launch { graphViewModel.loadGraphForUserSelection(currency, period) }
                            },
                            modifier = Modifier.padding(top = 20.dp)
                        )
                    }
                }

                isRatesFullscreen -> {
                    // 전체 화면 환율 리스트 모드
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = metrics.horizontalPadding)
                            .padding(top = 8.dp, bottom = 12.dp)
                    ) {
                        // 헤더
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
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
                                    .clickable { showBankCustomizeSheet = true },
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

                            if (updateTimeStr != null) {
                                Text(
                                    text = updateTimeStr,
                                    color = SecondaryText,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "축소",
                                tint = SecondaryText,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable { graphViewModel.setRatesFullscreen(false) }
                            )
                        }

                        // 환율 리스트 (세로: 가운데, 가로: 상단 + 스크롤)
                        val isPortrait = LocalConfiguration.current.orientation ==
                            android.content.res.Configuration.ORIENTATION_PORTRAIT

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBackground)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = { graphViewModel.setRatesFullscreen(false) }
                                    )
                                },
                            contentAlignment = if (isPortrait) Alignment.Center else Alignment.TopCenter
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isPortrait) Modifier else Modifier.verticalScroll(rememberScrollState())
                                    )
                                    .padding(
                                        horizontal = metrics.horizontalPadding,
                                        vertical = metrics.sectionPadding
                                    ),
                                verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing)
                            ) {
                                displayedRates.forEach { rate ->
                                    RateBarView(
                                        rate = rate,
                                        referenceRate = referenceRate,
                                        minRate = rateRange?.first ?: rate.rate,
                                        maxRate = rateRange?.second ?: rate.rate
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    // 일반 모드
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = metrics.horizontalPadding)
                            .padding(top = 8.dp, bottom = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(metrics.sectionSpacing)
                    ) {
                        // 그래프 섹션
                        // M3-b: wrapper의 "그래프 주변 더블탭 → fullscreen 진입" 제거.
                        // 이유: M3-a에서 RateGraphView 내부에 manual double-tap zoom 토글이 들어가면서,
                        // 그래프 내부 더블탭은 줌인/리셋, 그래프 "주변"(label 영역 등) 더블탭은 fullscreen 진입이
                        // 되어 같은 제스처의 의미가 위치에 따라 달라지는 혼란이 발생했다. iOS도 동일 문제를
                        // 겪고 wrapper 더블탭 진입을 제거했다. fullscreen 진입은 아래 OpenInFull 아이콘으로만 수행.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBackground)
                                .padding(metrics.sectionPadding)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (currency == SupportedCurrency.USD_KRW) {
                                        DxyToggleButton(
                                            isSelected = dxyVisible,
                                            onClick = { graphViewModel.toggleDxy() }
                                        )
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    if (activePeriod == GraphPeriod.ONE_DAY) {
                                        SourceToggleRow(
                                            selectedSources = selectedSources,
                                            onToggle = { source -> graphViewModel.toggleSource(source) }
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(InputBackground)
                                            .clickable { graphViewModel.setGraphFullscreen(true) },
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

                                if (activePeriod != GraphPeriod.ONE_DAY) {
                                    Text(
                                        text = currency.displayName,
                                        color = SecondaryText,
                                        fontSize = 11.sp,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // 그래프
                            RateGraphView(
                                rateGraphData = graphPayload.rateGraphData,
                                dxyGraphData = graphPayload.dxyGraphData,
                                period = activePeriod,
                                isLoading = isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = metrics.sectionPadding, bottom = 12.dp),
                                onFollowActiveChanged = { isFollowActive = it }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            PeriodTabBar(
                                activePeriod = activePeriod,
                                onSelectPeriod = { period ->
                                    graphViewModel.setActivePeriod(period)
                                    scope.launch { graphViewModel.loadGraphForUserSelection(currency, period) }
                                }
                            )
                        }

                        // 환율 리스트 섹션
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBackground)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = { graphViewModel.setRatesFullscreen(true) }
                                    )
                                }
                                .padding(top = metrics.sectionPadding)
                        ) {
                            // 헤더: "은행별 환율" + 업데이트 시간 + 확대 버튼
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = metrics.horizontalPadding),
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
                                        .clickable { showBankCustomizeSheet = true },
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

                                if (updateTimeStr != null) {
                                    Text(
                                        text = updateTimeStr,
                                        color = SecondaryText,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(InputBackground)
                                        .clickable { graphViewModel.setRatesFullscreen(true) },
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

                            // 환율 바 리스트
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = metrics.horizontalPadding)
                                    .padding(bottom = metrics.ratesSectionBottomPadding),
                                verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing)
                            ) {
                                displayedRates.forEach { rate ->
                                    RateBarView(
                                        rate = rate,
                                        referenceRate = referenceRate,
                                        minRate = rateRange?.first ?: rate.rate,
                                        maxRate = rateRange?.second ?: rate.rate
                                    )
                                }
                            }
                        }

                        // 알림 섹션
                        AlertSection(
                            currency = currency,
                            alertState = alertState,
                            hasPermission = hasPermission,
                            isPermissionDenied = isPermissionDenied,
                            canAddMore = alertViewModel.canAddMore,
                            remainingCount = alertViewModel.remainingCount,
                            isRefreshing = isRefreshing,
                            canRefresh = canRefresh,
                            scrollState = scrollState,
                            onToggle = { setting -> alertViewModel.toggleSetting(setting) },
                            onDelete = { setting -> alertViewModel.deleteSetting(setting) },
                            onEdit = { setting ->
                                editSetting = setting
                                showAddSheet = true
                            },
                            onAdd = {
                                scope.launch {
                                    initialBank = alertViewModel.loadLastSelectedBank(currency.code)
                                    showAddSheet = true
                                }
                            },
                            onRequestPermissionForAdd = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    // 권한 승인 후 add sheet 자동 오픈 (iOS와 동일)
                                    pendingShowAddSheet = true
                                    alertViewModel.markPermissionRequested()
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // Android 12 이하: POST_NOTIFICATIONS 런타임 권한 없음 → 시스템 설정으로 이동
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            onRequestPermissionForBanner = {
                                // 어떤 경로로든 stale pending 플래그가 남아있을 수 있으므로,
                                // 배너(권한만 켜기)에서는 add sheet 자동 오픈을 강제로 막는다.
                                pendingShowAddSheet = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    // 배너는 "권한만 켜기" 의도: 권한 승인 후 add sheet 자동 오픈 금지
                                    alertViewModel.markPermissionRequested()
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // Android 12 이하: 시스템 설정으로 이동
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            onOpenSettings = {
                                // 설정 이동은 add sheet 자동 오픈과 무관해야 한다.
                                pendingShowAddSheet = false
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            },
                            onRetry = { alertViewModel.loadSettings() },
                            onRefresh = { alertViewModel.refreshNow() }
                        )
                    }
                }
            }
        }
    }

    // AlertAddSheet
    if (showAddSheet) {
        AlertAddSheet(
            currency = currency,
            rates = rates,
            editSetting = editSetting,
            initialBank = if (editSetting == null) initialBank else null,
            isSaving = isSaving,
            hasDuplicate = { bank, condition, threshold, excludeId ->
                alertViewModel.hasDuplicate(bank, currency.code, condition, threshold, excludeId)
            },
            onSave = { bank, condition, threshold, isEnabled ->
                isSaving = true
                val editing = editSetting
                if (editing != null) {
                    alertViewModel.updateSetting(
                        id = editing.id,
                        bank = bank,
                        condition = condition,
                        threshold = threshold,
                        isEnabled = isEnabled,
                        onSuccess = {
                            isSaving = false
                            showAddSheet = false
                            editSetting = null
                        },
                        onError = {
                            isSaving = false
                        }
                    )
                } else {
                    val createAction = {
                        alertViewModel.createSetting(
                            bank = bank,
                            currency = currency.code,
                            condition = condition,
                            threshold = threshold,
                            isPremium = isPremium,
                            onPermissionNeeded = { intentOwner ->
                                isSaving = false
                                // Preserve the original auth generation across the permission UI.
                                pendingCreate = {
                                    isSaving = true
                                    alertViewModel.createSetting(
                                        bank = bank,
                                        currency = currency.code,
                                        condition = condition,
                                        threshold = threshold,
                                        isPremium = isPremium,
                                        owner = intentOwner,
                                        onPermissionNeeded = {},
                                        onSuccess = {
                                            isSaving = false
                                            // 성공 시에만 은행 저장 (iOS와 동일)
                                            alertViewModel.saveLastSelectedBank(currency.code, bank)
                                            showAddSheet = false
                                            editSetting = null
                                        },
                                        onError = {
                                            isSaving = false
                                        }
                                    )
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onSuccess = {
                                isSaving = false
                                // 성공 시에만 은행 저장 (iOS와 동일)
                                alertViewModel.saveLastSelectedBank(currency.code, bank)
                                showAddSheet = false
                                editSetting = null
                            },
                            onError = {
                                isSaving = false
                            }
                        )
                    }
                    createAction()
                }
            },
            onDismiss = {
                isSaving = false
                showAddSheet = false
                editSetting = null
                initialBank = null
            }
        )
    }

    if (showBankCustomizeSheet) {
        BankCustomizeSheet(
            orderedBanks = orderedBanks,
            onApply = { items ->
                bankPreferenceViewModel.apply(items)
                showBankCustomizeSheet = false
            },
            onDismiss = { showBankCustomizeSheet = false }
        )
    }
}

private data class GraphPayload(
    val rateGraphData: Map<GraphSource, List<GraphBucket>>,
    val dxyGraphData: List<GraphBucket>
)

private fun buildGraphPayload(
    rawGraphData: Map<GraphSource, List<GraphBucket>>,
    filteredRates: List<ExchangeRate>,
    currency: SupportedCurrency,
    selectedSources: Set<GraphSource>,
    activePeriod: GraphPeriod,
    dxyVisible: Boolean,
    latestDxyRate: Double?,
    hasFreshPeriodCache: Boolean
): GraphPayload {
    // iOS v2.6 parity: live tail x축 위치는 항상 "현재 시각"(Clock.System.now()).
    // 이전에는 max(filteredRates.timestamp)를 써서 rates 정체 시 tail도 정지 → freeze.
    // 이제 broadcast 유무와 무관하게 현재 시각으로 전진 (재렌더 트리거는 followTick 담당).
    val tailTimestamp = Clock.System.now().epochSeconds.toInt()

    val rateGraphData = rawGraphData
        .filterKeys { it != GraphSource.DXY }
        .toMutableMap()

    val dxyGraphData = rawGraphData[GraphSource.DXY].orEmpty()

    if (activePeriod == GraphPeriod.ONE_DAY) {
        selectedSources.forEach { source ->
            val liveRate = filteredRates.firstOrNull { it.bank == source.code }?.rate ?: return@forEach
            val existing = rateGraphData[source].orEmpty()
            rateGraphData[source] = appendLiveTail(existing, tailTimestamp, liveRate)
        }
    } else {
        val referenceRate = filteredRates.firstOrNull { it.bank == Bank.INVESTING.code }?.rate
        if (referenceRate != null && hasFreshPeriodCache) {
            val existing = rateGraphData[GraphSource.REFERENCE].orEmpty()
            rateGraphData[GraphSource.REFERENCE] = appendLiveTail(existing, tailTimestamp, referenceRate)
        }
    }

    val finalDxyGraphData = if (
        currency == SupportedCurrency.USD_KRW &&
        dxyVisible &&
        latestDxyRate != null &&
        (activePeriod == GraphPeriod.ONE_DAY || hasFreshPeriodCache)
    ) {
        appendLiveTail(dxyGraphData, tailTimestamp, latestDxyRate)
    } else {
        dxyGraphData
    }

    return GraphPayload(
        rateGraphData = rateGraphData.filterValues { it.isNotEmpty() },
        dxyGraphData = finalDxyGraphData
    )
}

private fun appendLiveTail(
    buckets: List<GraphBucket>,
    tailTimestamp: Int,
    close: Double
): List<GraphBucket> {
    if (buckets.isEmpty()) return buckets

    val lastBucket = buckets.last()
    if (tailTimestamp <= lastBucket.bucketTs) return buckets

    return buckets + GraphBucket(
        bucketTs = tailTimestamp,
        max = close,
        min = close,
        close = close
    )
}
