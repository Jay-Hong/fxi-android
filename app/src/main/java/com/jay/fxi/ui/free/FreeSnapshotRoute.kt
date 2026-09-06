package com.jay.fxi.ui.free

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(viewModel, lifecycle, uid, isActive) {
        viewModel.bind(uid)
        val coordinator = viewModel.activityCoordinator
        fun updateActivity() {
            coordinator.update(
                uid = uid,
                tab = FreeSnapshotUiState.DEFAULT_KEY.tab,
                period = FreeSnapshotUiState.DEFAULT_KEY.period,
                isForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                isActive = isActive
            )
        }
        val observer = LifecycleEventObserver { _, _ -> updateActivity() }
        lifecycle.addObserver(observer)
        updateActivity()
        onDispose {
            lifecycle.removeObserver(observer)
            coordinator.dispose()
        }
    }

    FreeSnapshotScreen(
        state = state.forOwner(uid),
        userInfo = userInfo?.takeIf { it.uid == uid },
        onSignOut = onSignOut,
        onSubscribe = onSubscribe,
        modifier = modifier
    )
}
