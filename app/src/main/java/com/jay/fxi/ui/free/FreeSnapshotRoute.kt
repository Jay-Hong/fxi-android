package com.jay.fxi.ui.free

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.jay.fxi.domain.model.UserInfo

/** Root retains ownership of auth/access and overlays; no premium services are resolved here. */
@Composable
fun FreeSnapshotRoute(
    uid: String,
    onSignOut: () -> Unit,
    onSubscribe: () -> Unit,
    userInfo: UserInfo? = null,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    require(uid.isNotBlank())
    val viewModel: FreeSnapshotViewModel = hiltViewModel()
    // Plain `collectAsState`, deliberately. The lifecycle-aware collector stops while the process
    // is stopped, which leaves this value behind whatever the view model did in the meantime — and
    // the resume is exactly when the activation below is recomputed. The upstream is a
    // `MutableStateFlow` in the same process, so collecting it while stopped costs nothing.
    val state by viewModel.uiState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shown = state.forOwner(uid)

    LaunchedEffect(viewModel, uid) { viewModel.bind(uid) }

    // The one activation owner for the whole surface. Everything that can change what should be
    // running — the settled tab, the period, the paywall, the process going to the background —
    // arrives here and nowhere else, so no two callers can disagree about what is active.
    // `dataKey` is null on 뉴스 and until the stored selection lands, and both mean "run nothing".
    //
    // Foreground is read as Compose state rather than from a lifecycle callback, which makes this
    // single effect the only writer. A callback cannot be that writer: it fires on resume and reads
    // whatever the composition last held, so a selection that changed while stopped would have it
    // reactivating the tab from before the pause.
    val coordinator = viewModel.activityCoordinator
    val lifecycleState by lifecycle.currentStateAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(coordinator, uid, shown.dataKey, isActive, foreground) {
        coordinator.update(
            uid = uid,
            tab = shown.dataKey?.tab,
            period = shown.dataKey?.period ?: FreeSnapshotUiState.DEFAULT_PERIOD,
            isForeground = foreground,
            isActive = isActive
        )
    }
    DisposableEffect(coordinator, uid) { onDispose { coordinator.dispose() } }

    FreeSnapshotScreen(
        state = shown,
        userInfo = userInfo?.takeIf { it.uid == uid },
        onSelectTab = viewModel::selectTab,
        onSelectPeriod = viewModel::selectPeriod,
        onToggleSeries = viewModel::toggleSeries,
        onApplyRowPreference = viewModel::applyRowPreference,
        onSignOut = onSignOut,
        onSubscribe = onSubscribe,
        modifier = modifier
    )
}
