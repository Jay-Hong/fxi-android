package com.jay.fxi.ui.free

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.alert.BankAlertEditor
import com.jay.fxi.ui.alert.BankAlertEditorFields
import com.jay.fxi.ui.alert.BankAlertEditorInput
import com.jay.fxi.ui.alert.BankAlertEditorMode
import com.jay.fxi.ui.alert.BankAlertSheetDragHandle
import com.jay.fxi.ui.components.SubscribeCTA
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

internal object FreeAlertPreviewTags {
    const val SECTION = "free-alert-preview-section"
    const val SECTION_TOGGLE = "free-alert-preview-toggle"
    const val OPEN = "free-alert-preview-open"
    const val SHEET = "free-alert-preview-sheet"
    const val CLOSE = "free-alert-preview-close"
    const val TITLE = "free-alert-preview-title"
}

/**
 * The free bank alert preview (D26): the premium editor's fields over the quotes it opened with, and in place of a save, only
 * [SubscribeCTA]. It has no save at all — no submit, no saving state, no existing alerts, no remaining count, no remembered
 * bank — and it starts nothing: hiding, finishing and the paywall belong to the host that owns [sheetState]. [enabled] is false
 * while the host is closing it, so the fields take no input then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FreeBankAlertPreviewSheet(
    sheetState: SheetState,
    currency: SupportedCurrency,
    quotes: Map<String, Double>,
    input: BankAlertEditorInput,
    enabled: Boolean,
    onInput: (BankAlertEditorInput) -> Unit,
    onClose: () -> Unit,
    onSubscribe: () -> Unit
) {
    val check = BankAlertEditor.check(BankAlertEditorMode.Add, input, currency, quotes, emptyList())
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.testTag(FreeAlertPreviewTags.SHEET),
        containerColor = Background,
        dragHandle = { BankAlertSheetDragHandle() }
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterStart).testTag(FreeAlertPreviewTags.CLOSE)
                ) {
                    Text("닫기", color = SecondaryText)
                }
                Text(
                    text = "알림 추가 (미리보기)",
                    modifier = Modifier.align(Alignment.Center).testTag(FreeAlertPreviewTags.TITLE),
                    color = PrimaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            BankAlertEditorFields(
                mode = BankAlertEditorMode.Add,
                currency = currency,
                quotes = quotes,
                input = input,
                check = check,
                enabled = enabled,
                remaining = null,
                onInput = onInput,
                modifier = Modifier.weight(1f)
            )
            SubscribeCTA(onClick = onSubscribe, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}
