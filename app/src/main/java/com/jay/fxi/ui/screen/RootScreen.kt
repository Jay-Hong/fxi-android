package com.jay.fxi.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertEventBus
import com.jay.fxi.service.PushNotificationManager
import com.jay.fxi.subscription.SubscriptionManager
import kotlinx.coroutines.flow.StateFlow
import com.jay.fxi.ui.auth.AuthViewModel
import com.jay.fxi.ui.auth.LoginScreen
import com.jay.fxi.ui.subscription.LockedPreviewScreen
import com.jay.fxi.ui.subscription.PaywallScreen
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.viewmodel.AlertViewModel
import com.jay.fxi.ui.viewmodel.ExchangeRateViewModel
import com.jay.fxi.ui.viewmodel.GraphViewModel
import com.jay.fxi.ui.viewmodel.NewsViewModel

@Composable
fun RootScreen(
    subscriptionManager: SubscriptionManager,
    pushNotificationManager: PushNotificationManager,
    pendingAlertEvent: StateFlow<AlertEvent?>? = null,
    onPendingAlertEventConsumed: () -> Unit = {},
    alertEventBus: AlertEventBus? = null,
    authViewModel: AuthViewModel = hiltViewModel(),
    exchangeRateViewModel: ExchangeRateViewModel = hiltViewModel(),
    graphViewModel: GraphViewModel = hiltViewModel(),
    alertViewModel: AlertViewModel = hiltViewModel(),
    newsViewModel: NewsViewModel = hiltViewModel()
) {
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val isPremium by subscriptionManager.isPremium.collectAsStateWithLifecycle()
    val isLoadingInitial by subscriptionManager.isLoadingInitial.collectAsStateWithLifecycle()

    var showPreview by rememberSaveable { mutableStateOf(false) }
    var showPaywall by rememberSaveable { mutableStateOf(false) }

    // 콜드스타트 시 pending 알림 이벤트 처리 (Compose 준비 후)
    val pendingEvent by pendingAlertEvent?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    LaunchedEffect(pendingEvent) {
        val event = pendingEvent ?: return@LaunchedEffect
        alertEventBus?.emit(event)
        onPendingAlertEventConsumed()
    }

    // Reset UI-only states on auth changes
    LaunchedEffect(authState) {
        if (authState !is AuthState.SignedOut) {
            showPreview = false
        }
        if (authState !is AuthState.SignedIn) {
            showPaywall = false
        }
    }

    // 구매 완료 시 Paywall 닫기
    LaunchedEffect(isPremium) {
        if (isPremium) {
            showPaywall = false
        }
    }

    // 상태 전환 시 서비스 정리
    val previousAuthState = remember { mutableStateOf<AuthState?>(null) }
    LaunchedEffect(authState) {
        val previous = previousAuthState.value
        if (previous != null && previous !is AuthState.SignedOut && authState is AuthState.SignedOut) {
            exchangeRateViewModel.stop()
            graphViewModel.stop()
            alertViewModel.reset()
            newsViewModel.reset()
            pushNotificationManager.unregisterDeviceFromServer()
        }
        previousAuthState.value = authState
    }

    val previousPremiumState = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isPremium) {
        val previous = previousPremiumState.value
        if (previous == true && !isPremium) {
            exchangeRateViewModel.stop()
            graphViewModel.stop()
            alertViewModel.reset()
            newsViewModel.reset()
            pushNotificationManager.unregisterDeviceFromServer()
        }
        previousPremiumState.value = isPremium
    }

    when (authState) {
        AuthState.Unknown -> {
            SplashScreen()
        }
        AuthState.SignedOut -> {
            if (showPreview) {
                LockedPreviewScreen(
                    subscriptionManager = subscriptionManager,
                    onPrimaryAction = { showPreview = false },
                    onClose = { showPreview = false }
                )
            } else {
                LoginScreen(
                    onPreviewClick = { showPreview = true }
                )
            }
        }
        is AuthState.SignedIn -> {
            if (isLoadingInitial) {
                FullScreenLoading(message = "구독 상태 확인 중...")
            } else if (isPremium) {
                MainScreen(
                    isPremium = isPremium,
                    authViewModel = authViewModel,
                    exchangeRateViewModel = exchangeRateViewModel,
                    graphViewModel = graphViewModel,
                    alertViewModel = alertViewModel
                )
            } else {
                val userInfo = (authState as? AuthState.SignedIn)?.user
                // Box overlay: LockedPreviewScreen stays in composition while Paywall is shown
                // (iOS .sheet() 동작과 동일 — 시뮬레이션이 Paywall 표시 중에도 유지됨)
                Box(modifier = Modifier.fillMaxSize()) {
                    LockedPreviewScreen(
                        subscriptionManager = subscriptionManager,
                        onPrimaryAction = { showPaywall = true },
                        userInfo = userInfo,
                        onSignOut = { authViewModel.signOut() },
                        isPaywallVisible = showPaywall
                    )
                    if (showPaywall) {
                        // PaywallScreen이 overlay로 올라간 상태에서도, 빈 영역 탭이
                        // 아래 LockedPreviewScreen으로 전달되지 않도록 터치를 흡수한다.
                        //
                        // Note: PaywallScreen의 인터랙션(스크롤/버튼)을 방해하지 않도록
                        // 흡수 레이어는 PaywallScreen "뒤"에 둔다.
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null,
                                        onClick = {}
                                    )
                            )
                            PaywallScreen(
                                subscriptionManager = subscriptionManager,
                                onClose = { showPaywall = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        val offsetY = with(LocalDensity.current) {
            (maxHeight / 3.28f)
        }
        Image(
            painter = painterResource(id = R.drawable.ic_splash_icon),
            contentDescription = "FXi",
            modifier = Modifier
                .size(100.dp)
                .align(Alignment.TopCenter)
                .offset(y = offsetY - 50.dp)
        )
    }
}

@Composable
private fun FullScreenLoading(
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            CircularProgressIndicator(
                color = Primary,
                strokeWidth = 3.dp
            )
            Text(
                text = message,
                color = SecondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
