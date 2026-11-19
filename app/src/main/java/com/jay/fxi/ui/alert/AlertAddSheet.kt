package com.jay.fxi.ui.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

internal object AlertAddSheetTags {
    const val SHEET = "alert-editor-sheet"
    const val CANCEL = "alert-editor-cancel"
    const val SAVE = "alert-editor-save"
    const val TITLE = "alert-editor-title"
    const val HEADER_BANK = "alert-editor-header-bank"
    fun bank(code: String) = "alert-editor-bank-$code"
    const val THRESHOLD = "alert-editor-threshold"
    const val CONDITION = "alert-editor-condition"
    const val CURRENT_RATE = "alert-editor-current-rate"
    const val RANGE = "alert-editor-range"
    const val OUT_OF_RANGE = "alert-editor-out-of-range"
    const val DUPLICATE = "alert-editor-duplicate"
    const val REPEAT = "alert-editor-repeat"
    const val REPEAT_LABEL = "alert-editor-repeat-label"
    const val REPEAT_DESCRIPTION = "alert-editor-repeat-description"
    fun repeatOption(i: Int) = "alert-editor-repeat-option-$i"
    const val BANNER_REPEAT = "alert-editor-banner-repeat"
    const val REMAINING = "alert-editor-remaining"
    const val REMAINING_ICON = "alert-editor-remaining-icon"
    const val SAVING = "alert-editor-saving"
    const val ERROR_DIALOG = "alert-editor-error-dialog"
    const val ERROR_CONFIRM = "alert-editor-error-confirm"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlertAddSheet(
    mode: BankAlertEditorMode,
    currency: SupportedCurrency,
    quotes: Map<String, Double>,
    existing: List<AlertSetting>,
    saving: Boolean,
    errorMessage: String?,
    remaining: Int?,
    addBank: String? = null,
    onSubmit: (BankAlertEditorInput) -> Unit,
    onErrorDismiss: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(mode) { mutableStateOf(BankAlertEditor.initial(mode, quotes, addBank)) }
    val check = BankAlertEditor.check(mode, input, currency, quotes, existing)
    val canSave = check.canSave && !saving
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(AlertAddSheetTags.SHEET),
        containerColor = Background,
        dragHandle = { BankAlertSheetDragHandle() }
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag(AlertAddSheetTags.CANCEL)) {
                        Text("취소", color = SecondaryText)
                    }
                    Text(
                        text = if (mode == BankAlertEditorMode.Add) "알림 추가" else "알림 수정",
                        modifier = Modifier.testTag(AlertAddSheetTags.TITLE),
                        color = PrimaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { if (canSave) onSubmit(input) },
                        enabled = canSave,
                        modifier = Modifier.testTag(AlertAddSheetTags.SAVE)
                    ) {
                        Text(
                            "저장",
                            color = if (canSave) Primary else SecondaryText.copy(alpha = 0.5f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                BankAlertEditorFields(
                    mode = mode,
                    currency = currency,
                    quotes = quotes,
                    input = input,
                    check = check,
                    enabled = !saving,
                    remaining = remaining.takeIf { mode == BankAlertEditorMode.Add },
                    onInput = { if (!saving) input = it },
                    modifier = Modifier.weight(1f)
                )
            }
            if (saving) SavingOverlay()
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            modifier = Modifier.testTag(AlertAddSheetTags.ERROR_DIALOG),
            title = { Text("오류") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(
                    onClick = onErrorDismiss,
                    modifier = Modifier.testTag(AlertAddSheetTags.ERROR_CONFIRM)
                ) { Text("확인") }
            }
        )
    }
}

@Composable
private fun SavingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .testTag(AlertAddSheetTags.SAVING),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                .background(CardBackground)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(Modifier.size(32.dp), color = PrimaryText, strokeWidth = 3.dp)
            Text("저장 중...", color = PrimaryText, fontSize = 14.sp)
        }
    }
}
