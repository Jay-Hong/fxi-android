package com.jay.fxi.ui.alert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jay.fxi.data.remote.dto.SourceAlertHistoryItem
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import java.util.Locale
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** One delivered source alert as drawn: the condition's threshold and the rate that fired it are separate fields. */
internal data class SourceHistoryRow(
    val exchangeName: String,
    val condition: String,
    val triggeredRate: String,
    val date: String,
    val time: String
)

internal fun sourceHistoryRow(item: SourceAlertHistoryItem): SourceHistoryRow {
    val local = item.sentAt.toLocalDateTime(TimeZone.of("Asia/Seoul"))
    return SourceHistoryRow(
        exchangeName = Exchange.fromCode(item.source)?.displayName ?: item.source,
        condition = "${formatSourceHistoryNumber(item.threshold)} ${item.condition.symbol} ${item.condition.displayText}",
        triggeredRate = formatSourceHistoryNumber(item.triggeredRate),
        date = String.format(Locale.ROOT, "%02d-%02d", local.monthNumber, local.dayOfMonth),
        time = String.format(Locale.ROOT, "%02d:%02d", local.hour, local.minute)
    )
}

/** Fixed decimal point, exactly two fraction digits, no grouping or currency rescaling (as the bank history). */
private fun formatSourceHistoryNumber(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceHistorySheet(
    sheet: SourceHistorySheetState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background
    ) {
        Column(modifier = Modifier.fillMaxWidth().testTag(SourceHistoryTags.SHEET).padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("알림 히스토리", color = PrimaryText, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) {
                    Text("닫기")
                }
            }
            when (val body = sheet.body) {
                SourceHistoryBody.Loading -> SourceHistoryMessage {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("히스토리 로드 중...", color = SecondaryText)
                }
                SourceHistoryBody.Empty -> SourceHistoryMessage {
                    Text("발송된 알림이 없습니다", color = SecondaryText)
                }
                is SourceHistoryBody.Failed -> SourceHistoryMessage {
                    Text(body.message, color = PrimaryText)
                    TextButton(onClick = onRetry) {
                        Text("다시 시도")
                    }
                }
                is SourceHistoryBody.Loaded -> PullToRefreshBox(
                    isRefreshing = body.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxWidth().height(400.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(body.items) { item ->
                            SourceHistoryReadRow(sourceHistoryRow(item))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceHistoryMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

@Composable
private fun SourceHistoryReadRow(row: SourceHistoryRow) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(SourceHistoryTags.ROW).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.exchangeName, color = PrimaryText, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(row.condition, color = SecondaryText, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Text(row.triggeredRate, color = PrimaryText, style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(row.date, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
            Text(row.time, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
