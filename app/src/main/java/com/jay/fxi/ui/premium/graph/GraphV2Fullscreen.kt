package com.jay.fxi.ui.premium.graph

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Draws only while the state has a fullscreen token; its zoom is its own, apart from the inline one. */
@Composable
internal fun GraphV2Fullscreen(
    state: GraphV2ScreenState,
    actions: GraphV2UiActions,
    modifier: Modifier = Modifier,
) {
    GraphV2SurfaceOwner(state, actions, GraphV2Surface.FULLSCREEN, modifier)
}
