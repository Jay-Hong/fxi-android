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
import com.jay.fxi.ui.premium.graph.GraphScreenHost
import com.jay.fxi.ui.premium.graph.GraphScreenMount
import com.jay.fxi.ui.premium.graph.GraphV2ScreenStateHolder
import com.jay.fxi.ui.premium.graph.GraphV2UiActions
import com.jay.fxi.ui.screen.NewsDetailOverlay
import com.jay.fxi.ui.screen.NewsTabContent
import com.jay.fxi.ui.settings.SettingsScreen
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.viewmodel.NewsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * R4-c C3c-2: connects [PremiumTopicScreen] to its consumer, the news view model and settings. The host owns the consumer and its
 * main-thread scope; this route neither creates nor stops the topic runtime. Root mounts it for a current premium grant.
 *
 * S4 CUT-CC5-3: with a [graphHost], the route opens one mount of it in an effect and closes it on dispose or when the host
 * changes; the mount, a Compose state, never outlives its host. The host starts and closes the holders. The active target is
 * the holder of the selected usd/jpy/eur tab for the fresh screen's owner while the route is active: a new target
 * deactivates the previous holder before activating the new one; TETHER, NEWS, no owner, inactive and dispose release it,
 * and recomposition, graph updates and inline/fullscreen switches do not. A new identity or a new set of mounted holders is
 * enqueued to each holder as a context change. The graph slot carries that holder's state, fresh reads and actions.
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
    },
    graphHost: GraphScreenHost? = null
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

    var mount by remember { mutableStateOf<GraphScreenMount?>(null) }
    DisposableEffect(graphHost) {
        val opened = graphHost?.open()
        mount = opened
        onDispose {
            mount = null
            opened?.close()
        }
    }
    // Keyed by the mount, so a new mount never shows the previous one's holders for a frame.
    val holders = key(mount) { (mount?.holders ?: NO_HOLDERS).collectAsState().value }
    LaunchedEffect(identity, holders) { holders.values.forEach { it.onContextChanged() } }
    // Only usd, jpy and eur have holders, so TETHER and NEWS find none.
    val holder = screen.ui.selectedTab?.serverTab?.let { holders[it] }
    // An inactive route draws NONE, so it has no owner and no target.
    val target = if (owner != null && holder != null) holder to owner else null
    DisposableEffect(target) {
        target?.let { (h, o) -> h.onActivated(o) }
        onDispose { target?.first?.onDeactivated() }
    }
    val graphSlot = target?.let { (h, o) ->
        remember(h, o) {
            PremiumFxGraphSlot(
                owner = o,
                state = h.state,
                currentState = h::currentState,
                actions = GraphV2UiActions(
                    selectPeriod = h::selectPeriod,
                    toggleSeries = h::toggleSeries,
                    enterFullscreen = h::enterFullscreen,
                    exitFullscreen = h::exitFullscreen,
                    retrySelection = h::retrySelection,
                    setSurfaceVisible = h::setSurfaceVisible
                )
            )
        }
    }

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
                    onRetryTopics = { capturedOwner, tab ->
                        if (allowed() && capturedOwner == owner) consumer.retryTopics(capturedOwner, tab)
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
                    },
                    graphSlot = graphSlot
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

private val NO_HOLDERS: StateFlow<Map<String, GraphV2ScreenStateHolder>> = MutableStateFlow(emptyMap())
