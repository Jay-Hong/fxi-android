package com.jay.fxi.ui.alert

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
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
        dragHandle = { BankAlertSheetDragHandle() }, modifier = Modifier.testTag("comparison-editor")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                TextButton(onClick = dismiss) { Text("취소") }
                Text(fields.title)
                if (pending) CircularProgressIndicator(Modifier.size(24.dp))
                else TextButton(onClick = save, enabled = canSave, modifier = Modifier.testTag("comparison-save")) { Text("저장") }
            }
            message?.let { Text(it, Modifier.testTag("comparison-message")) }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ComparisonAlertEditorFields(fields, quotes, !pending, change)
            }
        }
    }
}

@Composable
internal fun ComparisonAlertEditorFields(fields: ComparisonFields, quotes: ComparisonQuotes, enabled: Boolean,
    change: ((ComparisonFields, ComparisonQuotes) -> ComparisonFields) -> Unit) {
    if (!fields.signed) Text(fields.guidance)
    for (right in if (fields.signed) listOf(false, true) else listOf(false)) {
        if (fields.signed) Text(if (right) "비교" else "기준")
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(fields.heading)
        if (fields.signed) {
            if (fields.wide) Text("넓은 범위 ±1000")
            ComparisonMenu("⋮", enabled, listOf(if (fields.wide) "넓은 범위 사용 중 (±1000)" else "넓은 범위 설정"),
                disabled = if (fields.wide) 0 else -1) { change { state, live -> state.widen(live) } }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((value, label) in listOf("gte" to "△ 이상", "lte" to "▽ 이하")) {
            TextButton(onClick = { change { state, live -> state.condition(value, live) } }, enabled = enabled,
                modifier = Modifier.semantics { selected = fields.operator == value }) { Text(label) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ComparisonStepButton("−", enabled) { n -> change { state, _ -> state.step(-1, n) } }
        Text(fields.fieldText, Modifier.testTag("comparison-value"), color = when {
            fields.signed && fields.threshold > 0 -> PositiveColor
            fields.signed && fields.threshold < 0 -> NegativeColor
            else -> MaterialTheme.colorScheme.onSurface
        })
        ComparisonStepButton("＋", enabled) { n -> change { state, _ -> state.step(1, n) } }
    }
    fields.exactText?.let { Text(it) }
    if (fields.wide) Text("비정상 급등락 감지용 넓은 범위(±1000)입니다. 시트를 닫으면 기본(±100)으로 돌아갑니다.")
    fields.current(quotes).forEach { Text(it) }
    val options = listOf("한번만") + RepeatInterval.entries.map { it.label }
    val unknown = fields.repeatLabel !in options
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("반복")
        ComparisonMenu(fields.repeatLabel, enabled, if (unknown) options + fields.repeatLabel else options,
            disabled = if (unknown) options.size else -1, chosen = fields.repeatLabel) { index ->
            change { state, _ -> state.copy(repeatSeconds = RepeatInterval.entries.getOrNull(index - 1)?.seconds) }
        }
    }
    fields.summary?.let { Text(it) }
}

@Composable
private fun ComparisonMenu(label: String, enabled: Boolean, options: List<String>, disabled: Int, chosen: String? = null, pick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text(label) }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, enabled = index != disabled,
                    modifier = Modifier.semantics { selected = chosen == option },
                    onClick = { open = false; pick(index) })
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
