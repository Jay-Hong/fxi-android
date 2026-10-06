package com.jay.fxi.ui.premium.graph

import com.jay.fxi.domain.model.GraphPeriod

/** Events carry the token of the state that rendered the control; the holder checks its validity. */
internal data class GraphV2UiActions(
    val selectPeriod: (GraphV2UiToken, GraphPeriod) -> Unit,
    val toggleSeries: (GraphV2UiToken, String) -> Unit,
    val enterFullscreen: (GraphV2UiToken) -> Unit,
    val exitFullscreen: (GraphV2UiToken) -> Unit,
    val retrySelection: (GraphV2UiToken) -> Unit,
)
