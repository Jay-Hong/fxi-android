package com.jay.fxi.ui.premium.graph

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Selection and period change only when the host supplies the next state. */
@Composable
internal fun GraphV2Section(
    state: GraphV2ScreenState,
    actions: GraphV2UiActions,
    modifier: Modifier = Modifier,
    hostExposed: Boolean = true,
) {
    GraphV2SurfaceOwner(state, actions, GraphV2Surface.INLINE, modifier, hostExposed)
}
