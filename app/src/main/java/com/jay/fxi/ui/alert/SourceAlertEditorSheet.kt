package com.jay.fxi.ui.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

internal object SourceAlertEditorSheetTags {
    const val SHEET = "source-alert-editor-sheet"
    const val CANCEL = "source-alert-editor-cancel"
    const val SAVE = "source-alert-editor-save"
    const val TITLE = "source-alert-editor-title"
    fun source(code: String) = "source-alert-editor-source-$code"
    const val THRESHOLD = "source-alert-editor-threshold"
    const val CONDITION = "source-alert-editor-condition"
    const val CURRENT_RATE = "source-alert-editor-current-rate"
    const val RANGE = "source-alert-editor-range"
    const val HINT = "source-alert-editor-hint"
    fun repeatOption(i: Int) = "source-alert-editor-repeat-option-$i"
    const val REPEAT_KEEP = "source-alert-editor-repeat-keep"
    const val REMAINING = "source-alert-editor-remaining"
    const val SAVING = "source-alert-editor-saving"
    const val ERROR_DIALOG = "source-alert-editor-error-dialog"
    const val ERROR_CONFIRM = "source-alert-editor-error-confirm"
}

/**
 * S8-5b: one editor object, one draft. The sheet reads no owner, repository, store or permission helper; it draws the holder's
 * [input], shows the pure editor's check of it against [quotes] and [existing], and hands typing to [onInput], an exchange
 * pick to [onPick] (the holder refills from the live quotes) and a save to [onSubmit].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceAlertEditorSheet(
    mode: SourceAlertEditorMode,
    sources: List<String>,
    asset: String,
    quotes: Map<String, Double>,
    existing: List<SourceAlertSetting>,
    saving: Boolean,
    errorMessage: String?,
    remaining: Int?,
    addEnabled: Boolean,
    input: SourceAlertEditorInput,
    onInput: (SourceAlertEditorInput) -> Unit,
    onPick: (String) -> Unit,
    onSubmit: () -> Unit,
    onErrorDismiss: () -> Unit,
    onDismiss: () -> Unit
) {
    // Later quotes recheck the input but never rewrite it.
    val check = SourceAlertEditor.check(mode, input, sources, asset, quotes, existing)
    val canSave = check.canSave && !saving && (mode != SourceAlertEditorMode.Add || addEnabled)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(SourceAlertEditorSheetTags.SHEET),
        containerColor = Background,
        dragHandle = { SourceSheetHandle() }
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag(SourceAlertEditorSheetTags.CANCEL)) {
                        Text(SourceAlertEditor.Copy.CANCEL, color = SecondaryText)
                    }
                    Text(
                        text = if (mode == SourceAlertEditorMode.Add) SourceAlertEditor.Copy.ADD_TITLE else SourceAlertEditor.Copy.EDIT_TITLE,
                        modifier = Modifier.testTag(SourceAlertEditorSheetTags.TITLE),
                        color = PrimaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { if (canSave) onSubmit() },
                        enabled = canSave,
                        modifier = Modifier.testTag(SourceAlertEditorSheetTags.SAVE)
                    ) {
                        Text(
                            SourceAlertEditor.Copy.SAVE,
                            color = if (canSave) Primary else SecondaryText.copy(alpha = 0.5f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                SourceAlertEditorFields(
                    mode = mode,
                    sources = sources,
                    quotes = quotes,
                    input = input,
                    check = check,
                    enabled = !saving,
                    remaining = remaining.takeIf { mode == SourceAlertEditorMode.Add },
                    onInput = { if (!saving) onInput(it) },
                    onPick = { if (!saving) onPick(it) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (saving) SourceSavingOverlay()
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = onErrorDismiss,
            modifier = Modifier.testTag(SourceAlertEditorSheetTags.ERROR_DIALOG),
            title = { Text(SourceAlertEditor.Copy.ERROR_TITLE) },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = onErrorDismiss, modifier = Modifier.testTag(SourceAlertEditorSheetTags.ERROR_CONFIRM)) {
                    Text(SourceAlertEditor.Copy.OK)
                }
            }
        )
    }
}

@Composable
private fun SourceAlertEditorFields(
    mode: SourceAlertEditorMode,
    sources: List<String>,
    quotes: Map<String, Double>,
    input: SourceAlertEditorInput,
    check: SourceAlertEditorCheck,
    enabled: Boolean,
    remaining: Int?,
    onInput: (SourceAlertEditorInput) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("거래소", color = SecondaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            sources.chunked(3).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    line.forEach { code ->
                        val selected = code == input.source
                        Box(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Primary.copy(alpha = 0.15f) else InputBackground)
                                .border(if (selected) 2.dp else 0.dp, if (selected) Primary else Color.Transparent, RoundedCornerShape(10.dp))
                                .testTag(SourceAlertEditorSheetTags.source(code))
                                .clickable(enabled = enabled) { onPick(code) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(Exchange.fromCode(code)?.displayName ?: code, color = PrimaryText, fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    repeat(3 - line.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                check.quote?.let { "현재 시세 ${SourceAlertEditor.formatThreshold(it)}원" } ?: "현재 시세를 확인할 수 없습니다",
                color = SecondaryText,
                fontSize = 13.sp,
                modifier = Modifier.testTag(SourceAlertEditorSheetTags.CURRENT_RATE)
            )
            check.range?.let { range ->
                Text(
                    "설정 가능 범위 ${SourceAlertEditor.formatThreshold(range.start)} ~ ${SourceAlertEditor.formatThreshold(range.endInclusive)}원",
                    color = SecondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag(SourceAlertEditorSheetTags.RANGE)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("알림 받을 시세", color = SecondaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(InputBackground).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = input.thresholdText,
                        onValueChange = { onInput(input.copy(thresholdText = it)) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).testTag(SourceAlertEditorSheetTags.THRESHOLD),
                        textStyle = TextStyle(
                            color = if (input.thresholdText.isNotEmpty() &&
                                (check.threshold == null || check.duplicate || check.noQuote || check.outOfRange)
                            ) NegativeColor else PrimaryText,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        cursorBrush = SolidColor(Primary)
                    )
                    Text("원", color = SecondaryText, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
                }
                val conditionColor = if (input.condition == AlertCondition.ABOVE) PositiveColor else NegativeColor
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(conditionColor.copy(alpha = 0.15f))
                        .border(2.dp, conditionColor, RoundedCornerShape(12.dp))
                        .testTag(SourceAlertEditorSheetTags.CONDITION)
                        .clickable(enabled = enabled) {
                            onInput(input.copy(condition = if (input.condition == AlertCondition.ABOVE) AlertCondition.BELOW else AlertCondition.ABOVE))
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${input.condition.symbol} ${input.condition.displayText}", color = conditionColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            SourceAlertEditor.hint(check)?.let { hint ->
                Text(hint, color = NegativeColor, fontSize = 12.sp, modifier = Modifier.testTag(SourceAlertEditorSheetTags.HINT))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val texts = SourceAlertEditor.repeatTexts(mode, input.repeat)
            Text("알림 방식", color = SecondaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(texts.description, color = SecondaryText, fontSize = 12.sp)
            // An unregistered original interval keeps its own option, so another choice can be undone.
            val unregistered = (mode as? SourceAlertEditorMode.Edit)?.setting?.repeatIntervalSec?.let { seconds ->
                com.jay.fxi.domain.model.RepeatInterval.from(seconds) == null
            } == true
            val keptUnregistered = unregistered && input.repeat == RepeatIntervalChange.Keep
            if (unregistered) {
                SourceRepeatOption("기존 반복 간격 유지", selected = keptUnregistered, enabled = enabled, tag = SourceAlertEditorSheetTags.REPEAT_KEEP) {
                    onInput(input.copy(repeat = RepeatIntervalChange.Keep))
                }
            }
            SourceAlertEditor.REPEAT_CHOICES.forEachIndexed { i, interval ->
                val change = interval?.let { RepeatIntervalChange.Every(it) } ?: RepeatIntervalChange.Once
                val selectedSeconds = when (val repeat = input.repeat) {
                    RepeatIntervalChange.Keep -> (mode as? SourceAlertEditorMode.Edit)?.setting?.repeatIntervalSec
                    RepeatIntervalChange.Once -> null
                    is RepeatIntervalChange.Every -> repeat.interval.seconds
                }
                val selected = !keptUnregistered && selectedSeconds == interval?.seconds
                SourceRepeatOption(interval?.label ?: "한번만", selected, enabled, SourceAlertEditorSheetTags.repeatOption(i)) {
                    onInput(input.copy(repeat = change))
                }
            }
        }
        if (remaining != null && remaining <= 3) {
            Text(
                if (remaining <= 0) "알림을 더 추가할 수 없습니다" else "${remaining}개 더 추가할 수 있습니다",
                color = if (remaining <= 0) NegativeColor else SecondaryText,
                fontSize = 12.sp,
                modifier = Modifier.testTag(SourceAlertEditorSheetTags.REMAINING)
            )
        }
    }
}

@Composable
private fun SourceRepeatOption(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Primary.copy(alpha = 0.15f) else InputBackground)
            .testTag(tag)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = PrimaryText, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun SourceSheetHandle() {
    Box(Modifier.padding(vertical = 8.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SecondaryText.copy(alpha = 0.4f)))
}

@Composable
private fun SourceSavingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).testTag(SourceAlertEditorSheetTags.SAVING),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(CardBackground).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(Modifier.size(32.dp), color = PrimaryText, strokeWidth = 3.dp)
            Text(SourceAlertEditor.Copy.SAVING, color = PrimaryText, fontSize = 14.sp)
        }
    }
}
