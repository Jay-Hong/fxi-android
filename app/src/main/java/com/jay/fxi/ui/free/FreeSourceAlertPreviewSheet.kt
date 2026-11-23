package com.jay.fxi.ui.free

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.ui.alert.SourceAlertDraftRules
import com.jay.fxi.ui.alert.SourceAlertEditorInput
import com.jay.fxi.ui.alert.symbol
import com.jay.fxi.ui.components.SubscribeCTA
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

internal object FreeSourcePreviewTags {
    const val SECTION = "free-source-preview-section"
    const val TOGGLE = "free-source-preview-toggle"
    const val OPEN = "free-source-preview-open"
    const val SHEET = "free-source-preview-sheet"
    const val TITLE = "free-source-preview-title"
    const val CLOSE = "free-source-preview-close"
    const val QUOTE = "free-source-preview-quote"
    const val RANGE = "free-source-preview-range"
    const val THRESHOLD = "free-source-preview-threshold"
    const val CONDITION = "free-source-preview-condition"
    const val REPEAT_ONCE = "free-source-preview-repeat-once"
    fun source(code: String) = "free-source-preview-source-$code"
    fun repeat(seconds: Int) = "free-source-preview-repeat-$seconds"
}

/**
 * S8-7 (D26 for 테더, `R4c/S8/s8_7_contract.codex.r2.md` §2·§3): the free tether alert preview. The premium add editor's
 * fields over the quotes it opened with — the five exchanges of the row policy, the current quote and range as information,
 * the price, the condition, the repeat and its description — and in place of a save, only [SubscribeCTA]. No save, no saving
 * state, no range warning or error colour, no duplicate or remaining count, no remembered exchange; it starts nothing: hiding,
 * finishing and the paywall belong to the host that owns [sheetState]. [enabled] is false while the host is closing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FreeSourceAlertPreviewSheet(
    sheetState: SheetState,
    quotes: Map<String, Double>,
    input: SourceAlertEditorInput,
    enabled: Boolean,
    onInput: (SourceAlertEditorInput) -> Unit,
    onClose: () -> Unit,
    onSubscribe: () -> Unit
) {
    val sources = SourceAlertRowPolicy.EXCHANGES.sources(SourceAlertRowPolicy.SETTINGS_ASSET)
    val quote = SourceAlertDraftRules.usableQuote(quotes[input.source])
    val range = SourceAlertDraftRules.range(quote)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.testTag(FreeSourcePreviewTags.SHEET),
        containerColor = Background,
        dragHandle = { PreviewSheetHandle() }
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).testTag(FreeSourcePreviewTags.CLOSE)) {
                    Text("닫기", color = SecondaryText)
                }
                Text(
                    text = "테더 알림 추가 (미리보기)",
                    modifier = Modifier.align(Alignment.Center).testTag(FreeSourcePreviewTags.TITLE),
                    color = PrimaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
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
                                        .testTag(FreeSourcePreviewTags.source(code))
                                        .clickable(enabled = enabled) { onInput(SourceAlertDraftRules.pick(input, code, quotes)) }
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
                        quote?.let { "현재 시세 ${SourceAlertDraftRules.formatThreshold(it)}원" } ?: "현재 시세를 확인할 수 없습니다",
                        color = SecondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag(FreeSourcePreviewTags.QUOTE)
                    )
                    range?.let {
                        Text(
                            "설정 가능 범위 ${SourceAlertDraftRules.formatThreshold(it.start)} ~ ${SourceAlertDraftRules.formatThreshold(it.endInclusive)}원",
                            color = SecondaryText,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag(FreeSourcePreviewTags.RANGE)
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
                                modifier = Modifier.weight(1f).testTag(FreeSourcePreviewTags.THRESHOLD),
                                textStyle = TextStyle(
                                    color = PrimaryText,
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
                                .testTag(FreeSourcePreviewTags.CONDITION)
                                .clickable(enabled = enabled) {
                                    onInput(input.copy(condition = if (input.condition == AlertCondition.ABOVE) AlertCondition.BELOW else AlertCondition.ABOVE))
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${input.condition.symbol} ${input.condition.displayText}", color = conditionColor, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chosen = (input.repeat as? RepeatIntervalChange.Every)?.interval
                    Text("알림 방식", color = SecondaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(SourceAlertDraftRules.addRepeatDescription(chosen), color = SecondaryText, fontSize = 12.sp)
                    (listOf<RepeatInterval?>(null) + RepeatInterval.entries).forEach { interval ->
                        val change = interval?.let { RepeatIntervalChange.Every(it) } ?: RepeatIntervalChange.Once
                        PreviewRepeatOption(
                            label = interval?.label ?: "한번만",
                            selected = chosen == interval,
                            enabled = enabled,
                            tag = interval?.let { FreeSourcePreviewTags.repeat(it.seconds) } ?: FreeSourcePreviewTags.REPEAT_ONCE
                        ) { onInput(input.copy(repeat = change)) }
                    }
                }
            }
            SubscribeCTA(onClick = onSubscribe, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun PreviewRepeatOption(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
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
private fun PreviewSheetHandle() {
    Box(Modifier.padding(vertical = 8.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SecondaryText.copy(alpha = 0.4f)))
}
