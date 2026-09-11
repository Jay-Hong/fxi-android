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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.R
import com.jay.fxi.admission.ReleaseAdmission
import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.ui.free.FreeSnapshotRoute
import com.jay.fxi.service.AlertEvent
import com.jay.fxi.service.AlertEventBus
import com.jay.fxi.subscription.SubscriptionManager
import com.jay.fxi.ui.auth.AuthViewModel
import com.jay.fxi.ui.auth.LoginScreen
import com.jay.fxi.ui.subscription.PaywallScreen
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Provider

@Composable
fun RootScreen(
    subscriptionManagerProvider: Provider<SubscriptionManager>,
    pendingAlertEvent: StateFlow<AlertEvent?>? = null,
    onPendingAlertEventConsumed: () -> Unit = {},
    alertEventBusProvider: Provider<AlertEventBus>,
) {
    if (!ReleaseAdmission.isOpen) {
        ReleaseUnavailableScreen()
        return
    }

    ArmedRootScreen(
        subscriptionManager = subscriptionManagerProvider.get(),
        pendingAlertEvent = pendingAlertEvent,
        onPendingAlertEventConsumed = onPendingAlertEventConsumed,
        alertEventBus = alertEventBusProvider.get()
    )
}

@Composable
private fun ArmedRootScreen(
    subscriptionManager: SubscriptionManager,
    pendingAlertEvent: StateFlow<AlertEvent?>? = null,
    onPendingAlertEventConsumed: () -> Unit = {},
    alertEventBus: AlertEventBus? = null,
    authViewModel: AuthViewModel = hiltViewModel(),
    rootViewModel: RootViewModel = hiltViewModel()
) {
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val ownedAccess by rootViewModel.access.collectAsStateWithLifecycle()

    // A grant is only this session's if it was decided for this session. While the two flows are
    // out of step the safe reading is "no grant", which sends us to the free surface rather than
    // opening the premium one on somebody else's entitlement.
    val signedInUid = (authState as? AuthState.SignedIn)?.user?.uid
    val accessForSession = rootViewModel.accessForSession(signedInUid, ownedAccess)

    var showPaywall by rememberSaveable { mutableStateOf(false) }

    // Cold-start alert intent, once Compose is ready.
    val pendingEvent by pendingAlertEvent?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    LaunchedEffect(pendingEvent) {
        val event = pendingEvent ?: return@LaunchedEffect
        alertEventBus?.emit(event)
        onPendingAlertEventConsumed()
    }

    LaunchedEffect(authState) {
        if (authState !is AuthState.SignedIn) showPaywall = false
    }

    // The paywall closes on a server-confirmed grant and on nothing else. It used to close itself
    // on RevenueCat's local flag, which meant a restored purchase the server had not confirmed
    // dismissed it instantly — the decision now lives here alone.
    LaunchedEffect(accessForSession) {
        if (accessForSession == PremiumAccessState.PremiumConfirmed) showPaywall = false
    }

    // The whole Root decision, in one pure call. `isLoadingInitial` is gone with it: D23 sends
    // `Resolving` to the free surface rather than to a spinner, because a local purchase flag is
    // not an access answer and waiting on one is what let a stale grant open the premium surface.
    when (rootDestinationFor(authState, accessForSession)) {
        RootDestination.Splash -> SplashScreen()

        // No preview. `ANDROID_V2_PLAN.md` removes the signed-out sample surface outright.
        RootDestination.Login -> LoginScreen()

        RootDestination.FreeSnapshot -> {
            val user = (authState as? AuthState.SignedIn)?.user
            if (user == null) {
                SplashScreen()
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    FreeSnapshotRoute(
                        uid = user.uid,
                        onSignOut = { authViewModel.signOut() },
                        onSubscribe = { showPaywall = true },
                        userInfo = user,
                        isActive = !showPaywall
                    )
                    if (showPaywall) {
                        // Absorb taps behind the paywall so the surface underneath stays inert,
                        // while leaving the paywall's own scrolling and buttons alone.
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

        RootDestination.Premium -> PremiumUnavailableScreen()
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
