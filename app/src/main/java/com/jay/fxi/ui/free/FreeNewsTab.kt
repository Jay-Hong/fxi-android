package com.jay.fxi.ui.free

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.ui.screen.NewsDetailOverlay
import com.jay.fxi.ui.screen.NewsTabContent
import com.jay.fxi.ui.viewmodel.NewsViewModel

/**
 * The 뉴스 tab on the free surface.
 *
 * The same view model and list the premium surface uses, entered with `isPremium = false` — which
 * is not a flag to remember to set but the only truth available here: a confirmed subscriber never
 * reaches this surface. It buys the longer refresh cooldown, no polling, and the host filter that
 * keeps subscriber-only articles out of the list.
 *
 * News is not snapshot data. Nothing here touches the free scheduler, which the surface keeps
 * deactivated for as long as this tab is the selection.
 */
@Composable
internal fun FreeNewsTab(isVisible: Boolean, modifier: Modifier = Modifier) {
    val viewModel: NewsViewModel = hiltViewModel()
    val items by viewModel.newsItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val refreshTrigger by viewModel.refreshTrigger.collectAsStateWithLifecycle()
    var detailUrl by rememberSaveable { mutableStateOf<String?>(null) }

    // Paired appear/disappear. Leaving the tab is what stops the refresh, so it has to be reported
    // on the way out as well as the way in — including when the surface itself goes away.
    DisposableEffect(viewModel, isVisible) {
        if (isVisible) viewModel.onTabAppear(isPremium = false)
        onDispose { if (isVisible) viewModel.onTabDisappear() }
    }

    Box(modifier.fillMaxSize()) {
        NewsTabContent(
            newsItems = items,
            isLoading = isLoading,
            error = error,
            refreshTrigger = refreshTrigger,
            isPremium = false,
            isTabVisible = isVisible,
            isDetailOpen = detailUrl != null,
            onRetry = { viewModel.retry(isPremium = false) },
            onDetailOpen = { detailUrl = it }
        )
        detailUrl?.let { url -> NewsDetailOverlay(url = url, onDismiss = { detailUrl = null }) }
    }
}
