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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jay.fxi.ui.components.BankIcon
import com.jay.fxi.ui.components.BankIconFallback
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BankHistorySheet(
    sheet: BankHistorySheetState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
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
                BankHistoryBody.Loading -> HistoryMessage {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("히스토리 로드 중...", color = SecondaryText)
                }
                BankHistoryBody.Empty -> HistoryMessage {
                    Text("발송된 알림이 없습니다", color = SecondaryText)
                }
                is BankHistoryBody.Failed -> HistoryMessage {
                    Text(body.message, color = PrimaryText)
                    TextButton(onClick = onRetry) {
                        Text("다시 시도")
                    }
                }
                is BankHistoryBody.Loaded -> PullToRefreshBox(
                    isRefreshing = body.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxWidth().height(400.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(body.items) { item ->
                            HistoryRow(bankHistoryRow(item))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

@Composable
private fun HistoryRow(row: BankHistoryRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bank = row.bank
        if (bank != null) {
            BankIcon(bank = bank, modifier = Modifier.size(36.dp))
        } else {
            BankIconFallback(bank = null, modifier = Modifier.size(36.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "${row.bankName} · ${row.currencyLabel}",
                color = PrimaryText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val condition = row.condition
            Text(
                text = if (condition == null) "이전 알림"
                else "${condition.threshold} ${condition.symbol} ${condition.name}",
                color = SecondaryText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(row.rate, color = PrimaryText, style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(row.date, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
            Text(row.time, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
