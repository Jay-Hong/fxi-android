package com.jay.fxi.ui.premium.graph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jay.fxi.ui.components.PeriodTabBar
import com.jay.fxi.ui.graph.GraphChart
import com.jay.fxi.ui.graph.GraphZoomState

/** Inline rendering only. Selection and period change only when the host supplies the next state. */
@Composable
internal fun GraphV2Section(
    state: GraphV2ScreenState,
    actions: GraphV2UiActions,
    modifier: Modifier = Modifier,
) {
    val presentation = resolveGraphV2Status(state)
    if (presentation.center.kind == GraphV2CenterKind.NONE) return
    val token = state.inlineToken
    val surface = GraphV2Surface.INLINE
    // The inline token contains owner/fence/lifetime/binding/tab/period/screenGeneration, but no
    // selection or prepared graph. Keep zoom outside the content branch so all-off and refresh
    // do not dispose it; a new context or period starts a fresh window.
    key(token, state.tab, state.activePeriod) {
        var zoom by remember { mutableStateOf(GraphZoomState()) }
        Column(
            modifier = modifier.fillMaxWidth().testTag(GraphV2UiTags.root(surface)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GraphV2ToggleHeader(
                toggles = state.toggles,
                selectionStatus = state.selectionStatus,
                token = token,
                action = GraphV2HeaderAction.EXPAND,
                onToggle = actions.toggleSeries,
                onAction = actions.enterFullscreen,
            )
            val centerModifier = Modifier.fillMaxWidth().height(240.dp)
            when (presentation.center.kind) {
                GraphV2CenterKind.CHART -> {
                    val chart = requireNotNull(state.chart) { "READY requires a chart model" }
                    GraphChart(
                        prepared = chart.prepared,
                        visibleIds = chart.renderedIds,
                        modifier = centerModifier.testTag(GraphV2UiTags.chart(surface)),
                        zoom = zoom,
                        onZoom = { zoom = it },
                        onSingleTap = null,
                    )
                }
                GraphV2CenterKind.STATUS -> GraphV2StatusArea(state, token, actions.retrySelection, centerModifier)
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
