package com.jay.fxi.ui.alert

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.jay.fxi.data.remote.dto.ComparisonAlertHistoryItem
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.ui.components.BankIcon
import com.jay.fxi.ui.rates.view.RateRowGlyph
import com.jay.fxi.ui.theme.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal data class ComparisonHistoryLeg(val code: String, val name: String, val rate: String)
internal data class ComparisonHistoryRow(
    val left: ComparisonHistoryLeg, val spread: String, val detail: String?, val right: ComparisonHistoryLeg,
    val negativeSpread: Boolean, val threshold: String, val direction: String, val date: String, val time: String
)

private fun sign(value: Double, signed: Boolean) = when {
    value.toRawBits() < 0 -> "-"
    signed -> "+"
    else -> ""
}

private fun fixed(value: Double, decimals: Int, signed: Boolean = false): String =
    sign(value, signed) + BigDecimal(abs(value)).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()

private fun threshold(value: Double, signed: Boolean): String {
    val rounded = BigDecimal(abs(value)).round(MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    val exponent = rounded.precision() - rounded.scale() - 1
    val magnitude = if (exponent in -4..5) rounded.toPlainString() else {
        rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString() +
            String.format(Locale.ROOT, "e%+03d", exponent)
    }
    return sign(value, signed) + magnitude
}

internal fun comparisonHistoryRow(item: ComparisonAlertHistoryItem): ComparisonHistoryRow {
    val signed = item.diffType == "signed"
    val decimals = if (item.tab == "tether") 0 else 2
    fun leg(code: String, rate: Double, places: Int) = ComparisonHistoryLeg(code,
        Exchange.fromCode(code)?.displayName ?: Bank.fromCode(code)?.shortName ?: code, fixed(rate, places))
    val detail = when {
        !signed -> "↔"
        item.rightRate == 0.0 -> null
        else -> (item.spread / item.rightRate * 100).takeIf { it.isFinite() }?.let { fixed(it, 2, true) + "%" }
    }
    val local = item.sentAt.toLocalDateTime(TimeZone.of("Asia/Seoul"))
    return ComparisonHistoryRow(
        leg(item.leftSource, item.leftRate, decimals),
        if (signed) fixed(item.spread, 1, true) else fixed(abs(item.spread), decimals),
        detail, leg(item.rightSource, item.rightRate, if (signed) 2 else decimals),
        signed && item.spread < 0, threshold(item.threshold, signed),
        if (item.operator == "gte") "△ 이상" else "▽ 이하",
        String.format(Locale.ROOT, "%02d-%02d", local.monthNumber, local.dayOfMonth),
        String.format(Locale.ROOT, "%02d:%02d", local.hour, local.minute)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComparisonAlertHistorySheet(kind: String, body: ComparisonHistoryBody, onRetry: () -> Unit,
    onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val title = if (kind == "signed") "김프 알림" else "비교 알림"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background) {
        Column(Modifier.fillMaxWidth().testTag("comparison-history-sheet").padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("$title 히스토리", color = PrimaryText, style = MaterialTheme.typography.titleMedium)
                TextButton(onDismiss) { Text("닫기") }
            }
            when (body) {
                ComparisonHistoryBody.Loading -> Message {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text("히스토리 로드 중...", color = SecondaryText)
                }
                ComparisonHistoryBody.Empty -> Message {
                    Icon(Icons.Default.NotificationsOff, null, tint = SecondaryText)
                    Text("발송된 ${title}이 없습니다", color = SecondaryText)
                }
                is ComparisonHistoryBody.Failed -> Message {
                    Icon(if (body.temporary) Icons.Default.Schedule else Icons.Default.Warning, null,
                        tint = if (body.temporary) StatusConnecting else StatusError)
                    Text(body.message, color = PrimaryText)
                    TextButton(onRetry) { Text("다시 시도") }
                }
                is ComparisonHistoryBody.Loaded -> PullToRefreshBox(body.refreshing, onRefresh,
                    Modifier.fillMaxWidth().height(400.dp).testTag("comparison-history-list")) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(body.items) { item -> DeliveredRow(comparisonHistoryRow(item)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeliveredRow(row: ComparisonHistoryRow) {
    val color = if (row.negativeSpread) StatusError else StatusOnline
    FlowRow(Modifier.fillMaxWidth().testTag("comparison-history-row").semantics { isTraversalGroup = true }.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Leg(row.left, left = true)
        Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(row.spread, color = color, style = MaterialTheme.typography.bodySmall)
            row.detail?.let { Text(it, color = if (it == "↔") SecondaryText else color.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall) }
        }
        Leg(row.right, left = false)
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(row.threshold, color = PrimaryText, style = MaterialTheme.typography.bodySmall)
            Text(row.direction, color = if (row.direction == "△ 이상") PositiveColor else NegativeColor,
                style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f).widthIn(min = 40.dp).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.End) {
            Text(row.date, color = SecondaryText, style = MaterialTheme.typography.labelSmall)
            Text(row.time, color = SecondaryText, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Leg(leg: ComparisonHistoryLeg, left: Boolean) {
    val side = if (left) "왼쪽" else "오른쪽"
    val glyph: @Composable () -> Unit = {
        val bank = Bank.fromCode(leg.code)
        if (bank == null) RateRowGlyph(leg.code, leg.name, Modifier.size(26.dp))
        else BankIcon(bank, useHighRes = true, modifier = Modifier.size(26.dp))
    }
    Row(Modifier.clearAndSetSemantics { contentDescription = "$side ${leg.name} ${leg.rate}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (left) glyph()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(leg.rate, color = PrimaryText, style = MaterialTheme.typography.bodySmall)
            Text(leg.name, color = SecondaryText, style = MaterialTheme.typography.labelSmall)
        }
        if (!left) glyph()
    }
}
