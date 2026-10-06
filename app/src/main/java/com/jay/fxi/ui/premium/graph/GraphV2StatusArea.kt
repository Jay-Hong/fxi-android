package com.jay.fxi.ui.premium.graph

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.ui.theme.SecondaryText

@Composable
internal fun GraphV2StatusArea(
    state: GraphV2ScreenState,
    token: GraphV2UiToken?,
    onRetrySelection: (GraphV2UiToken) -> Unit,
    modifier: Modifier = Modifier,
    onClose: ((GraphV2UiToken) -> Unit)? = null,
) {
    val center = resolveGraphV2Status(state).center
    if (center.kind != GraphV2CenterKind.STATUS) return
    val surface = token?.surface ?: if (onClose == null) GraphV2Surface.INLINE else GraphV2Surface.FULLSCREEN
    val closeModifier = if (onClose != null && token != null) {
        Modifier.clickable(onClickLabel = "닫기") { onClose(token) }
    } else Modifier
    Box(
        modifier = modifier.testTag(GraphV2UiTags.status(surface)).then(closeModifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (center.showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).testTag(GraphV2UiTags.statusSpinner(surface)),
                    strokeWidth = 2.dp,
                )
            }
            center.message?.let {
                Text(
                    text = it,
                    color = SecondaryText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(GraphV2UiTags.statusText(surface)),
                )
            }
            if (center.showSelectionRetry) {
                TextButton(
                    onClick = { if (token != null) onRetrySelection(token) },
                    enabled = token != null,
                    modifier = Modifier.testTag(GraphV2UiTags.selectionRetry(surface)),
                ) {
                    Text("다시 시도")
                }
            }
        }
    }
}

@Composable
internal fun GraphV2SecondaryStatusArea(
    presentation: GraphV2StatusPresentation,
    surface: GraphV2Surface,
    modifier: Modifier = Modifier,
) {
    if (presentation.noticeText == null && presentation.requestFailureText == null &&
        !presentation.showRefreshingSpinner) return
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        presentation.noticeText?.let {
            Text(it, color = SecondaryText, fontSize = 11.sp, modifier = Modifier.testTag(GraphV2UiTags.notice(surface)))
        }
        presentation.requestFailureText?.let {
            Text(it, color = SecondaryText, fontSize = 11.sp,
                modifier = Modifier.testTag(GraphV2UiTags.requestFailure(surface)))
        }
        if (presentation.showRefreshingSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp).testTag(GraphV2UiTags.refreshing(surface)),
                strokeWidth = 2.dp,
            )
        }
    }
}
