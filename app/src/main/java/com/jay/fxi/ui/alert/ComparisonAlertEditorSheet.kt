package com.jay.fxi.ui.alert

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val comparisonPartialFailure = "새 알림은 생성됐습니다. 기존 알림을 삭제하지 못했습니다. 목록에서 기존 알림을 직접 삭제해 주세요."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComparisonAlertEditorSheet(fields: ComparisonFields, quotes: ComparisonQuotes, pending: Boolean,
    canSave: Boolean, message: String?, change: ((ComparisonFields, ComparisonQuotes) -> ComparisonFields) -> Unit,
    save: () -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background,
        dragHandle = { BankAlertSheetDragHandle() }, modifier = Modifier.testTag("comparison-editor")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                TextButton(onClick = dismiss) { Text("취소") }
                Text(fields.title)
                if (pending) CircularProgressIndicator(Modifier.size(24.dp))
                else TextButton(onClick = save, enabled = canSave, modifier = Modifier.testTag("comparison-save")) { Text("저장") }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ComparisonAlertEditorFields(fields, quotes, !pending, change, message)
            }
        }
    }
}

@Composable
internal fun ComparisonAlertEditorFields(fields: ComparisonFields, quotes: ComparisonQuotes, enabled: Boolean,
    change: ((ComparisonFields, ComparisonQuotes) -> ComparisonFields) -> Unit, message: String? = null) {
    var wideOpen by remember { mutableStateOf(false) }
    var repeatOpen by remember { mutableStateOf(false) }
    val duplicate = message == comparisonDuplicate
    val headlineStyle = LocalTextStyle.current.copy(
        color = if (duplicate) StatusConnecting else PrimaryText,
        fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Default, lineHeight = 20.sp
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val signed = fields.signed
    val headlineMinHeight = remember(signed, headlineStyle, measurer, density) {
        // minLines uses Latin metrics, which can be shorter than two lines of Hangul.
        if (signed) with(density) { measurer.measure("가\n가", headlineStyle).size.height.toDp() } else 0.dp
    }
    Text(if (duplicate) comparisonDuplicate else fields.headline(),
        Modifier.fillMaxWidth().heightIn(min = headlineMinHeight).testTag("comparison-headline"),
        style = headlineStyle, color = headlineStyle.color, minLines = if (signed) 2 else 1)
    if (message != null && !duplicate) {
        Text(message, Modifier.testTag("comparison-message"), color = StatusError, fontSize = 12.sp,
            fontWeight = FontWeight.Normal, fontFamily = FontFamily.Default)
    }
    for (right in if (fields.signed) listOf(false, true) else listOf(false)) {
        if (fields.signed) Text(if (right) "비교" else "기준")
        Column(Modifier.fillMaxWidth().testTag(if (right) "comparison-right" else "comparison-left")) {
            for (chunk in fields.candidates(right).chunked(5)) {
                Row(Modifier.fillMaxWidth()) {
                    for (leg in chunk) {
                        val picked = leg in fields.selection
                        Column(Modifier.weight(1f).testTag("comparison-pick-${leg.first}").clickable(enabled = enabled) {
                            change { state, live -> state.select(leg, right, live) }
                        }.semantics { selected = picked }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(comparisonName(leg.first), color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            fields.caption(leg, quotes)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    repeat(5 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    ComparisonCurrent(fields, quotes)
    Row(Modifier.fillMaxWidth().testTag("comparison-heading-row"), verticalAlignment = Alignment.CenterVertically) {
        Text(fields.heading, color = SecondaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default)
        if (fields.signed) {
            if (fields.wide) Text("넓은 범위 ±1000", Modifier.padding(start = 6.dp)
                .background(Primary.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
                color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Default)
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { wideOpen = true }, enabled = enabled, modifier = Modifier.testTag("comparison-wide")) {
                    Text("⋮", color = SecondaryText, fontSize = 18.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Default)
                }
                DropdownMenu(expanded = wideOpen && enabled, onDismissRequest = { wideOpen = false }) {
                    DropdownMenuItem(text = { Text(if (fields.wide) "넓은 범위 사용 중 (±1000)" else "넓은 범위 설정") },
                        enabled = !fields.wide, onClick = {
                            wideOpen = false
                            change { state, live -> state.widen(live) }
                        })
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).background(InputBackground, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ComparisonStepButton("−", enabled) { n -> change { state, _ -> state.step(-1, n) } }
            val inputColor = when (fields.thresholdTone(fields.threshold)) {
                ComparisonTone.Up -> StatusOnline
                ComparisonTone.Down -> StatusError
                ComparisonTone.Neutral -> PrimaryText
            }
            Text(fields.fieldText, Modifier.weight(1f).testTag("comparison-value"), color = inputColor,
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
            ComparisonStepButton("＋", enabled) { n -> change { state, _ -> state.step(1, n) } }
        }
        val conditionColor = when (fields.conditionLabel) {
            ComparisonCondition.Above -> PositiveColor
            ComparisonCondition.Below -> NegativeColor
            ComparisonCondition.Unknown -> SecondaryText
        }
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(conditionColor.copy(alpha = 0.15f))
            .border(2.dp, conditionColor, RoundedCornerShape(12.dp)).testTag("comparison-condition")
            .clickable(enabled = enabled, role = Role.Button) {
                change { state, live -> state.condition(nextOperator(state.operator), live) }
            }.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(fields.conditionLabel.label, color = conditionColor, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Default)
        }
    }
    fields.exactText?.let { Text(it) }
    if (fields.wide) Text("비정상 급등락 감지용 넓은 범위(±1000)입니다. 시트를 닫으면 기본(±100)으로 돌아갑니다.",
        color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Default)
    val options = listOf("한번만") + RepeatInterval.entries.map { it.label }
    val unknown = fields.repeatLabel !in options
    Row(Modifier.fillMaxWidth().background(CardBackground, RoundedCornerShape(12.dp)).padding(16.dp)
        .testTag("comparison-repeat-row"), verticalAlignment = Alignment.CenterVertically) {
        Text("반복", color = PrimaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Default)
        Spacer(Modifier.weight(1f))
        Box {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Primary.copy(alpha = 0.12f))
                .testTag("comparison-repeat").clickable(enabled = enabled, role = Role.Button) { repeatOpen = true }
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(fields.repeatLabel, color = Primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Default)
                Canvas(Modifier.size(9.dp)) {
                    for (y in listOf(0.15f, 0.85f)) {
                        val tip = Offset(size.width / 2, size.height * y)
                        val baseY = size.height * (if (y < 0.5f) 0.42f else 0.58f)
                        drawLine(Primary, Offset(0f, baseY), tip, 1.dp.toPx())
                        drawLine(Primary, tip, Offset(size.width, baseY), 1.dp.toPx())
                    }
                }
            }
            DropdownMenu(expanded = repeatOpen && enabled, onDismissRequest = { repeatOpen = false }) {
                val labels = if (unknown) options + fields.repeatLabel else options
                labels.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(option) }, enabled = index < options.size,
                        modifier = Modifier.semantics { selected = fields.repeatLabel == option },
                        onClick = {
                            repeatOpen = false
                            change { state, _ -> state.copy(repeatSeconds = RepeatInterval.entries.getOrNull(index - 1)?.seconds) }
                        })
                }
            }
        }
    }
}

@Composable
private fun ComparisonCurrent(fields: ComparisonFields, quotes: ComparisonQuotes) {
    val lines = fields.current(quotes)
    val reserve = if (fields.signed) 36.dp else 24.dp
    Row(Modifier.fillMaxWidth().testTag("comparison-current").heightIn(min = reserve),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val diff = fields.difference(quotes)
        if (diff != null) {
            val amountColor = if (fields.currentTone(diff) == ComparisonTone.Down) StatusError else StatusOnline
            Text(lines[0], color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Default)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(lines[1], color = amountColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Default, style = TextStyle(fontFeatureSettings = "tnum", lineHeight = 19.sp))
                if (fields.signed) Text(lines[2], color = amountColor.copy(alpha = 0.65f), fontSize = 11.sp,
                    fontWeight = FontWeight.Normal, fontFamily = FontFamily.Default,
                    style = TextStyle(fontFeatureSettings = "tnum", lineHeight = 15.sp))
            }
        }
    }
}

@Composable
private fun ComparisonStepButton(label: String, enabled: Boolean, step: (Int) -> Unit) {
    val current by rememberUpdatedState(step)
    Box(Modifier.size(48.dp).testTag("comparison-step-$label").semantics {
        contentDescription = label
        role = Role.Button
        if (enabled) onClick { current(1); true } else disabled()
    }.pointerInput(enabled) {
        if (enabled) coroutineScope {
            val gestureScope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
                down.consume()
                var count = 0
                val repeat = gestureScope.launch {
                    while (true) {
                        delay(ComparisonStepRepeat.nextDelay(count))
                        current(++count)
                    }
                }
                var inside = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        val pos = pointer.position
                        if (pos.x < 0 || pos.y < 0 || pos.x >= size.width || pos.y >= size.height || pointer.isConsumed) {
                            inside = false
                            repeat.cancel()
                        }
                        pointer.consume()
                        if (!pointer.pressed) {
                            if (inside && count == 0) current(1)
                            break
                        }
                    } while (true)
                } finally {
                    repeat.cancel()
                }
            }
        }
    }, contentAlignment = Alignment.Center) { Text(label) }
}
