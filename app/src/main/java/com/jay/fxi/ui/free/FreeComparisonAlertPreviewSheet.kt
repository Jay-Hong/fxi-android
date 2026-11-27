package com.jay.fxi.ui.free

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jay.fxi.ui.alert.ComparisonAlertEditorFields
import com.jay.fxi.ui.alert.ComparisonFields
import com.jay.fxi.ui.alert.ComparisonQuotes
import com.jay.fxi.ui.components.SubscribeCTA
import com.jay.fxi.ui.theme.Background

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FreeComparisonAlertPreviewSheet(
    sheetState: SheetState, fields: ComparisonFields, quotes: ComparisonQuotes, enabled: Boolean,
    change: ((ComparisonFields, ComparisonQuotes) -> ComparisonFields) -> Unit,
    onClose: () -> Unit, onSubscribe: () -> Unit
) {
    val kind = fields.kind
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = Background,
        modifier = Modifier.testTag(comparisonPreviewTag(kind, "sheet"))) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, modifier = Modifier.testTag(comparisonPreviewTag(kind, "close"))) { Text("닫기") }
                Text(comparisonPreviewTitle(kind) + " 추가 (미리보기)", Modifier.testTag(comparisonPreviewTag(kind, "title")))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                ComparisonAlertEditorFields(fields, quotes, enabled, change, message = null)
            }
            SubscribeCTA(onClick = onSubscribe, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}
