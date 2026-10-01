package com.jay.fxi.ui.premium.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.ui.premium.PremiumTopicConsumer
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import com.jay.fxi.ui.screen.NewsDetailOverlay
import com.jay.fxi.ui.screen.NewsTabContent
import com.jay.fxi.ui.settings.SettingsScreen
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.viewmodel.NewsViewModel

/**
 * R4-c C3c-2: connects [PremiumTopicScreen] to its consumer, the news view model and settings. The host owns the consumer and its
 * main-thread scope; this route neither creates nor stops the topic runtime. Not mounted yet (C4 mounts it).
 */
@Composable
internal fun PremiumTopicRoute(
    consumer: PremiumTopicConsumer,
    identity: AuthIdentityFence?,
    isActive: Boolean,
    newsViewModel: NewsViewModel,
    userInfo: UserInfo?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    settingsContent: @Composable (userInfo: UserInfo?, onSignOut: () -> Unit, onDismiss: () -> Unit) -> Unit = { u, s, d ->
        SettingsScreen(userInfo = u, onSignOut = s, onDismiss = d)
    }
) {
    LaunchedEffect(consumer) { consumer.start() }
    LaunchedEffect(consumer, identity) { consumer.onIdentityChanged() }
    val observed by consumer.state.collectAsState()
    // Observe publications, but recheck live inputs immediately before rendering.
    val screen = observed.let { if (isActive) consumer.currentState() else PremiumTopicScreenState.NONE }
    val activeNow by rememberUpdatedState(isActive)
    val signOutNow by rememberUpdatedState(onSignOut)
    val userNow by rememberUpdatedState(userInfo)
    val owner = screen.ui.owner

    Box(modifier.fillMaxSize()) {
        if (owner != null && screen.ui.selectedTab != null) {
            key(consumer, owner) {
                // Temporary overlays are never restored into another owner or a new active session.
                var settingsOpen by remember { mutableStateOf(false) }
                var detailUrl by remember { mutableStateOf<String?>(null) }
                val allowed: () -> Boolean = { activeNow && consumer.currentState().ui.owner == owner }
                val lifecycle = LocalLifecycleOwner.current.lifecycle

                PremiumTopicScreen(
                    state = screen,
                    onUserTabSelected = { capturedOwner, tab ->
                        if (allowed() && capturedOwner == owner) consumer.onUserTabSelected(capturedOwner, tab)
                    },
                    onRetryConnection = { capturedOwner ->
                        if (allowed() && capturedOwner == owner) consumer.retryConnection(capturedOwner)
                    },
                    onApplyRows = { capturedOwner, tab, list, seeded, order, hidden ->
                        if (allowed() && capturedOwner == owner) {
                            consumer.applyRowPreference(capturedOwner, tab, list, seeded, order, hidden)
                        }
                    },
                    onOpenSettings = { if (allowed()) settingsOpen = true },
                    newsContent = { visible ->
                        val items by newsViewModel.newsItems.collectAsState()
                        val loading by newsViewModel.isLoading.collectAsState()
                        val error by newsViewModel.error.collectAsState()
                        val refresh by newsViewModel.refreshTrigger.collectAsState()
                        val visibleNow by rememberUpdatedState(visible)

                        DisposableEffect(newsViewModel, visible) {
                            val appeared = visible && allowed()
                            if (appeared) newsViewModel.onTabAppear(isPremium = true)
                            onDispose { if (appeared) newsViewModel.onTabDisappear() }
                        }
                        DisposableEffect(lifecycle, newsViewModel) {
                            var stopped = false
                            val observer = LifecycleEventObserver { _, event ->
                                when (event) {
                                    Lifecycle.Event.ON_STOP -> stopped = true
                                    Lifecycle.Event.ON_RESUME -> {
                                        // NewsViewModel ignores a resume while its page is not shown (onTabDisappear).
                                        if (stopped && allowed()) {
                                            newsViewModel.onForegroundResume(isPremium = true)
                                        }
                                        stopped = false
                                    }
                                    else -> Unit
                                }
                            }
                            lifecycle.addObserver(observer)
                            onDispose { lifecycle.removeObserver(observer) }
                        }
                        NewsTabContent(
                            newsItems = items, isLoading = loading, error = error, refreshTrigger = refresh,
                            isPremium = true, isTabVisible = visible, isDetailOpen = detailUrl != null,
                            onRetry = { if (visibleNow && allowed()) newsViewModel.retry(isPremium = true) },
                            onDetailOpen = { url -> if (visibleNow && allowed()) detailUrl = url }
                        )
                    }
                )
                if (settingsOpen) {
                    BackHandler { settingsOpen = false }
                    Surface(Modifier.fillMaxSize(), color = Background, contentColor = PrimaryText) {
                        settingsContent(
                            userInfo?.takeIf { it.uid == owner.identity.uid },
                            { if (allowed() && userNow?.uid == owner.identity.uid) signOutNow() },
                            { settingsOpen = false }
                        )
                    }
                }
                detailUrl?.let { url ->
                    NewsDetailOverlay(url = url, onDismiss = { detailUrl = null })
                }
            }
        }
    }
}
