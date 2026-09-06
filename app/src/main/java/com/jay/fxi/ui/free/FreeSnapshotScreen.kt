package com.jay.fxi.ui.free

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.UserInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** Renders sanitized hourly data only. No live tail, request status inference, or premium VM. */
@Composable
fun FreeSnapshotScreen(
    state: FreeSnapshotUiState,
    onSelectTab: (FreeTab) -> Unit,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onToggleSeries: (String) -> Unit,
    onSignOut: () -> Unit,
    onSubscribe: () -> Unit,
    userInfo: UserInfo? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("무료 · 시간별 스냅샷", style = MaterialTheme.typography.bodyMedium)
                (userInfo?.displayName ?: userInfo?.email)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onSignOut) { Text("로그아웃") }
        }
        // The row and the pager come up with the restored selection, and not one frame before it.
        // `rememberPagerState` captures its initial page exactly once: built while the selection is
        // still unknown, it anchors on 달러 and then *reports that back* as its settled page —
        // overwriting the stored tab with one the user never chose. Waiting is what makes that
        // impossible, rather than a guard that has to stay ahead of the coroutine that races it.
        val selected = state.selectedTab ?: return@Column
        FreeSnapshotTabs(selected, state, onSelectTab, onSelectPeriod, onToggleSeries, onSubscribe)
    }
}

@Composable
private fun ColumnScope.FreeSnapshotTabs(
    selected: FreeTab,
    state: FreeSnapshotUiState,
    onSelectTab: (FreeTab) -> Unit,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onToggleSeries: (String) -> Unit,
    onSubscribe: () -> Unit
) {
    val tabs = FreeTab.entries
    val pagerState = rememberPagerState(initialPage = selected.ordinal) { tabs.size }

    // Two directions, one owner. A swipe reports only its *settled* page, so the selection — and
    // with it the one activated snapshot key — never lands on a tab the finger merely passed over.
    //
    // `drop(1)` discards the pager's opening report, which is never a choice anyone made: it is
    // whatever page the pager came up on. `rememberPagerState` saves and restores its page, and a
    // restore outranks `initialPage`, so after process death that opening report is the page from
    // the *last* process — and reporting it would overwrite the stored tab with it. The stored tab
    // is authoritative and the effect below is what moves the pager onto it.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { page -> tabs.getOrNull(page)?.let(onSelectTab) }
    }
    LaunchedEffect(selected) {
        if (pagerState.currentPage != selected.ordinal) pagerState.animateScrollToPage(selected.ordinal)
    }

    ScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 12.dp) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = index == selected.ordinal,
                onClick = { onSelectTab(tab) },
                text = { Text(tab.title) }
            )
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().weight(1f),
        key = { tabs[it].name }
    ) { page ->
        when (val tab = tabs[page]) {
            FreeTab.NEWS -> FreeNewsTab(isVisible = pagerState.settledPage == page)
            // Every data tab reads the state for the *selected* one. An off-screen page holds no
            // snapshot of its own, which is why only the settled page is ever the one shown.
            else -> FreeSnapshotTabContent(
                tab = tab,
                state = if (tab == selected) state else FreeSnapshotUiState(uid = state.uid),
                onSelectPeriod = onSelectPeriod,
                onToggleSeries = onToggleSeries,
                onSubscribe = onSubscribe
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FreeSnapshotTabContent(
    tab: FreeTab,
    state: FreeSnapshotUiState,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onToggleSeries: (String) -> Unit,
    onSubscribe: () -> Unit
) {
    val visible = state.availability == FreeSnapshotAvailability.FRESH ||
        state.availability == FreeSnapshotAvailability.DELAYED
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tab.heading, style = MaterialTheme.typography.headlineSmall)
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
        // Outside `visible`: the four periods stay reachable while the selected one is expired or
        // has not arrived. Hiding them would strand the user on the one period they cannot see.
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GraphPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = period == state.period,
                        onClick = { onSelectPeriod(period) },
                        label = { Text(period.displayName) }
                    )
                }
            }
        }
        if (visible) {
            item { Text("환율 추이", style = MaterialTheme.typography.titleMedium) }
            if (state.seriesToggles.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.seriesToggles.forEach { series ->
                            FilterChip(
                                selected = series.visible,
                                onClick = { onToggleSeries(series.seriesId) },
                                label = { Text(series.label) }
                            )
                        }
                    }
                }
            }
            if (state.charts.isEmpty()) {
                // All-off is a choice the plan permits, so it is stated rather than repaired.
                // Series that are switched on but carry no points for this period are a data gap,
                // not an empty selection — telling the user to pick something would be wrong there.
                item {
                    Text(
                        if (state.seriesToggles.isEmpty() || state.seriesToggles.any { it.visible }) {
                            "표시할 그래프 데이터가 없습니다."
                        } else {
                            "표시할 항목을 선택해 주세요."
                        }
                    )
                }
            }
            state.charts.forEach { chart -> item { SnapshotChart(chart) } }
            if (state.rateSections.isEmpty()) {
                item { Text("표시할 환율 데이터가 없습니다.") }
            }
            state.rateSections.forEach { section ->
                item { Text(section.title, style = MaterialTheme.typography.titleMedium) }
                section.rows.forEach { rate ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(rate.source)
                            Text(rate.value)
                        }
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

/** "달러 · USD/KRW" for a currency tab, the plain title otherwise. */
private val FreeTab.heading: String
    get() = currency?.let { "$title · ${it.displayName}" } ?: title

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
