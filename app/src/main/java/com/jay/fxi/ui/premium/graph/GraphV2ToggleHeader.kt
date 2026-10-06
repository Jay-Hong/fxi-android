package com.jay.fxi.ui.premium.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class GraphV2HeaderAction { EXPAND, CLOSE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GraphV2ToggleHeader(
    toggles: List<GraphV2SeriesToggle>,
    selectionStatus: GraphV2SelectionStatus,
    token: GraphV2UiToken?,
    action: GraphV2HeaderAction,
    onToggle: (GraphV2UiToken, String) -> Unit,
    onAction: (GraphV2UiToken) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = if (action == GraphV2HeaderAction.EXPAND) GraphV2Surface.INLINE else GraphV2Surface.FULLSCREEN
    val layout = arrangeGraphV2Toggles(toggles)
    // Separate row children reserve the action's width, including its touch target, before the flow is measured.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 96.dp).testTag(GraphV2UiTags.indices(surface)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            layout.indexColumn.forEach { toggle ->
                GraphV2Toggle(toggle, selectionStatus, token, surface, onToggle)
            }
        }
        FlowRow(
            modifier = Modifier.weight(1f).testTag(GraphV2UiTags.references(surface)),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            layout.referenceFlow.forEach { toggle ->
                GraphV2Toggle(toggle, selectionStatus, token, surface, onToggle)
            }
        }
        IconButton(
            onClick = { if (token != null) onAction(token) },
            enabled = token != null,
            modifier = Modifier.size(48.dp).testTag(
                if (action == GraphV2HeaderAction.EXPAND) GraphV2UiTags.expand() else GraphV2UiTags.close()
            ),
        ) {
            Icon(
                imageVector = if (action == GraphV2HeaderAction.EXPAND) Icons.Default.OpenInFull else Icons.Default.Close,
                contentDescription = if (action == GraphV2HeaderAction.EXPAND) "그래프 확대" else "닫기",
            )
        }
    }
}

@Composable
private fun GraphV2Toggle(
    toggle: GraphV2SeriesToggle,
    selectionStatus: GraphV2SelectionStatus,
    token: GraphV2UiToken?,
    surface: GraphV2Surface,
    onToggle: (GraphV2UiToken, String) -> Unit,
) {
    val confirmed = selectionStatus == GraphV2SelectionStatus.READY
    val enabled = confirmed && toggle.enabled && token != null
    val selected = confirmed && toggle.selected
    val toggleState = when {
        !confirmed -> ToggleableState.Indeterminate
        selected -> ToggleableState.On
        else -> ToggleableState.Off
    }
    val color = Color(toggle.style.colorHex)
    Row(
        modifier = Modifier
            .testTag(GraphV2UiTags.toggle(surface, toggle.seriesId))
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (selected) 0.18f else 0.06f))
            .triStateToggleable(state = toggleState, enabled = enabled, role = Role.Checkbox) {
                if (enabled) onToggle(token, toggle.seriesId)
            }
            .semantics { if (!confirmed) stateDescription = "설정 확인 중" }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp).testTag(GraphV2UiTags.check(surface, toggle.seriesId)),
            )
        } else {
            Box(Modifier.size(14.dp).padding(3.dp).background(color, CircleShape))
        }
        Text(text = toggle.style.label, color = color, fontSize = 12.sp)
    }
}
