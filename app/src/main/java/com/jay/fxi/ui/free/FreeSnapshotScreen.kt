package com.jay.fxi.ui.free

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jay.fxi.domain.model.UserInfo

/** Renders sanitized hourly data only. No live tail, request status inference, or premium VM. */
@Composable
fun FreeSnapshotScreen(
    state: FreeSnapshotUiState,
    onSignOut: () -> Unit,
    onSubscribe: () -> Unit,
    userInfo: UserInfo? = null,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text("달러 · USD/KRW", style = MaterialTheme.typography.headlineSmall)
                    Text("무료 · 시간별 스냅샷", style = MaterialTheme.typography.bodyMedium)
                    (userInfo?.displayName ?: userInfo?.email)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onSignOut) { Text("로그아웃") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.asOfLabel?.let { Text("기준시각 $it", style = MaterialTheme.typography.bodySmall) }
                when (state.availability) {
                    FreeSnapshotAvailability.AWAITING_SNAPSHOT -> Text("아직 표시할 스냅샷이 없습니다.")
                    FreeSnapshotAvailability.FRESH -> FreshnessBadge("정상", delayed = false)
                    FreeSnapshotAvailability.DELAYED -> {
                        FreshnessBadge("지연", delayed = true)
                        Text("업데이트가 지연되어 마지막 스냅샷을 표시합니다.")
                    }
                    FreeSnapshotAvailability.UNAVAILABLE -> Text("스냅샷이 만료되어 데이터를 표시할 수 없습니다.")
                }
            }
        }
        if (state.availability == FreeSnapshotAvailability.FRESH || state.availability == FreeSnapshotAvailability.DELAYED) {
            item { Text("환율 추이 · ${state.key.period.displayName}", style = MaterialTheme.typography.titleMedium) }
            if (state.charts.isEmpty()) {
                item { Text("표시할 그래프 데이터가 없습니다.") }
            }
            state.charts.forEach { chart -> item { SnapshotChart(chart) } }
            item { Text("은행별 환율", style = MaterialTheme.typography.titleMedium) }
            if (state.rates.isEmpty()) {
                item { Text("표시할 환율 데이터가 없습니다.") }
            }
            state.rates.forEach { rate ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(rate.source)
                        Text(rate.value)
                    }
                }
            }
        }
        item {
            Button(onClick = onSubscribe, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("프리미엄 구독")
            }
        }
    }
}

@Composable
private fun FreshnessBadge(label: String, delayed: Boolean) {
    Surface(
        color = if (delayed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun SnapshotChart(chart: FreeSnapshotChart) {
    val color = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(chart.label, style = MaterialTheme.typography.titleSmall)
            Text("${chart.minimum} ~ ${chart.maximum}", style = MaterialTheme.typography.bodySmall)
            Canvas(
                Modifier.fillMaxWidth().height(140.dp).padding(4.dp).semantics {
                    contentDescription = "${chart.label}, ${chart.start}부터 ${chart.end}, 최저 ${chart.minimum}, 최고 ${chart.maximum}"
                }
            ) {
                if (chart.points.size == 1) {
                    val point = chart.points.single()
                    drawCircle(color, radius = 3.dp.toPx(), center = Offset(point.x * size.width, point.y * size.height))
                } else {
                    val path = Path()
                    chart.points.forEachIndexed { index, point ->
                        val x = point.x * size.width
                        val y = point.y * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(chart.start, style = MaterialTheme.typography.bodySmall)
                Text(chart.end, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
