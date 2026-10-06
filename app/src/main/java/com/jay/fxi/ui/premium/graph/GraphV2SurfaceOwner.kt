package com.jay.fxi.ui.premium.graph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jay.fxi.ui.components.PeriodTabBar
import com.jay.fxi.ui.graph.GraphChart
import com.jay.fxi.ui.graph.GraphZoomState
import com.jay.fxi.ui.graph.GraphZoomStateSaver
import com.jay.fxi.ui.theme.Background

/** Each surface owns a saveable zoom for its rendered token, independently of the chart branch. */
@Composable
internal fun GraphV2SurfaceOwner(
    state: GraphV2ScreenState,
    actions: GraphV2UiActions,
    surface: GraphV2Surface,
    modifier: Modifier = Modifier,
) {
    val fullscreen = surface == GraphV2Surface.FULLSCREEN
    val token = when (surface) {
        GraphV2Surface.INLINE -> state.inlineToken
        GraphV2Surface.FULLSCREEN -> state.fullscreenToken ?: return
    }
    val presentation = resolveGraphV2Status(state)
    if (presentation.center.kind == GraphV2CenterKind.NONE) return
    // Tokens change on context/period transitions and fullscreen reopening, but not selection
    // or data refresh. Keep the zoom alive while a status replaces the chart on the same surface.
    key(token) {
        var zoom by rememberSaveable(stateSaver = GraphZoomStateSaver) {
            mutableStateOf(GraphZoomState())
        }
        val onClose: (() -> Unit)? = if (fullscreen) {
            { actions.exitFullscreen(checkNotNull(token)) }
        } else null
        if (onClose != null) BackHandler(onBack = onClose)
        val surfaceModifier = if (fullscreen) {
            modifier.fillMaxSize().background(Background)
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)
        } else modifier.fillMaxWidth()
        Column(
            modifier = surfaceModifier.testTag(GraphV2UiTags.root(surface)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GraphV2ToggleHeader(
                toggles = state.toggles,
                selectionStatus = state.selectionStatus,
                token = token,
                action = if (fullscreen) GraphV2HeaderAction.CLOSE else GraphV2HeaderAction.EXPAND,
                onToggle = actions.toggleSeries,
                onAction = if (fullscreen) actions.exitFullscreen else actions.enterFullscreen,
            )
            val centerModifier = if (fullscreen) Modifier.fillMaxWidth().weight(1f)
                else Modifier.fillMaxWidth().height(240.dp)
            when (presentation.center.kind) {
                GraphV2CenterKind.CHART -> {
                    val chart = requireNotNull(state.chart) { "READY requires a chart model" }
                    GraphChart(
                        prepared = chart.prepared,
                        visibleIds = chart.renderedIds,
                        modifier = centerModifier.testTag(GraphV2UiTags.chart(surface)),
                        zoom = zoom,
                        onZoom = { zoom = it },
                        onSingleTap = onClose,
                    )
                }
                GraphV2CenterKind.STATUS -> GraphV2StatusArea(
                    state = state,
                    token = token,
                    onRetrySelection = actions.retrySelection,
                    modifier = centerModifier,
                    onClose = if (fullscreen) actions.exitFullscreen else null,
                )
                GraphV2CenterKind.NONE -> Unit
            }
            GraphV2SecondaryStatusArea(presentation, surface)
            PeriodTabBar(
                activePeriod = state.activePeriod,
                onSelectPeriod = { period -> if (token != null) actions.selectPeriod(token, period) },
                modifier = Modifier.testTag(GraphV2UiTags.periods(surface)),
                periods = state.periods,
                enabled = token != null,
            )
        }
    }
}
