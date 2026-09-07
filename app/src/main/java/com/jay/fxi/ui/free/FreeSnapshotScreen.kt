package com.jay.fxi.ui.free

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.UserInfo
import com.jay.fxi.ui.components.PeriodTabBar
import com.jay.fxi.ui.graph.GraphChart
import com.jay.fxi.ui.graph.GraphSeriesStyles
import com.jay.fxi.ui.graph.GraphZoomState
import com.jay.fxi.ui.graph.GraphZoomStateSaver
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
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
    // Edge-to-edge is on for the whole app, so a surface that insets nowhere is drawn under the
    // clock and under the navigation bar. The premium surface pads the status bar the same way
    // (`MainScreen.kt:244`); the bottom is handled as list padding so content still scrolls under
    // the bar rather than stopping short of it.
    // Two things, and painting only the first is worse than painting neither.
    //
    // The window theme is `Theme.Material.Light`, so a Compose surface that paints nothing shows
    // white through — and this app is dark-only. Every other screen paints the app background
    // itself (`SettingsScreen`, `MainScreen`, the splash); this one was the exception.
    //
    // `LocalContentColor` also defaults to black outside a `Surface`, so every `Text` that does not
    // name a colour — the heading, the basis time, the bank rows, the empty and expired notices —
    // was black. On the white background that was merely wrong; on the dark one it is invisible.
    // Fullscreen is a branch, not a Dialog: it belongs to this surface's state and to the same
    // view model, so the period chosen inside it is still chosen when it closes. Saved, so a
    // rotation does not drop the user back out of it.
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // The flag belongs to the tab it was opened from, and only a tab with a graph can open it. If a
    // restore ever lands on 뉴스 while it is set, drop it — otherwise the next data tab the user
    // opens would arrive already in fullscreen, which they never asked for.
    val selectedTab = state.selectedTab
    LaunchedEffect(selectedTab) { if (selectedTab != null && !selectedTab.isData) fullscreen = false }

    // iOS presents fullscreen *over* the free screen (`.fullScreenCover`), so the list underneath is
    // still there when it closes. Replacing the content instead keeps the pager and its chart from
    // drawing behind an opaque cover — but on its own it throws away where the user had scrolled to,
    // and the 전체화면 button is far enough down the list to be worth coming back to.
    //
    // Held here, above the branch, and handed to each page rather than wrapped around the whole
    // pager: a lazy layout keeps its items' state in a holder that **prunes every item that is not
    // currently composed** whenever it is asked to save (`LazySaveableStateHolder.performSave`,
    // foundation 1.7.2, and its KDoc says so outright). Wrapping the pager would therefore restore
    // the page you left from and silently drop the other four — the news list you had scrolled
    // included. Providing per page puts that state in this holder instead, where nothing prunes it.
    val pageState = rememberSaveableStateHolder()

    Surface(color = Background, contentColor = PrimaryText, modifier = modifier.fillMaxSize()) {
    if (fullscreen && selectedTab?.isData == true) {
        FreeGraphFullscreen(
            tab = selectedTab,
            state = state,
            onSelectPeriod = onSelectPeriod,
            onToggleSeries = onToggleSeries,
            onClose = { fullscreen = false }
        )
        return@Surface
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
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
        val selected = selectedTab ?: return@Column
        FreeSnapshotTabs(selected, state, pageState, onSelectTab, onSelectPeriod, onToggleSeries,
            onExpand = { fullscreen = true }, onSubscribe = onSubscribe)
    }
    }
}

@Composable
private fun ColumnScope.FreeSnapshotTabs(
    selected: FreeTab,
    state: FreeSnapshotUiState,
    pageState: SaveableStateHolder,
    onSelectTab: (FreeTab) -> Unit,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onToggleSeries: (String) -> Unit,
    onExpand: () -> Unit,
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

    // Equal widths, not a scrolling row. Five two-character labels fit any phone, and letting the
    // row scroll centred 달러 and pushed 뉴스 and 유로 half off their edges. Deliberately the same
    // idiom as the premium tab row (`MainScreen.kt:368`) so the two surfaces do not drift apart.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            Column(
                modifier = Modifier.weight(1f).clickable { onSelectTab(tab) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = tab.title,
                    fontSize = 14.sp,
                    color = if (isSelected) PrimaryText else SecondaryText,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (isSelected) Primary else Color.Transparent)
                )
            }
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().weight(1f),
        key = { tabs[it].name }
    ) { page ->
        pageState.SaveableStateProvider(tabs[page].name) {
        when (val tab = tabs[page]) {
            FreeTab.NEWS -> FreeNewsTab(isVisible = pagerState.settledPage == page)
            // Every data tab reads the state for the *selected* one. An off-screen page holds no
            // snapshot of its own, which is why only the settled page is ever the one shown.
            else -> FreeSnapshotTabContent(
                tab = tab,
                state = if (tab == selected) state else FreeSnapshotUiState(uid = state.uid),
                onSelectPeriod = onSelectPeriod,
                onToggleSeries = onToggleSeries,
                onExpand = onExpand,
                onSubscribe = onSubscribe
            )
        }
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
    onExpand: () -> Unit,
    onSubscribe: () -> Unit
) {
    val visible = state.availability == FreeSnapshotAvailability.FRESH ||
        state.availability == FreeSnapshotAvailability.DELAYED

    // Held per page, inside the holder slice 5 put around each one: that makes the zoom this tab's
    // own, and carries it across the fullscreen trip that removes the pager. iOS gets the same two
    // properties from `.fullScreenCover` leaving the inline chart alive underneath.
    var zoom by rememberSaveable(stateSaver = GraphZoomStateSaver) {
        mutableStateOf(GraphZoomState())
    }
    // Changing period drops the window, as `resetZoom` does on iOS — but only on an actual change.
    // Keyed on entering composition it would also fire on the way back from fullscreen and undo
    // the very thing the holder just restored.
    var zoomedPeriod by rememberSaveable { mutableStateOf(state.period.name) }
    LaunchedEffect(state.period) {
        if (zoomedPeriod != state.period.name) {
            zoom = GraphZoomState()
            zoomedPeriod = state.period.name
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tab.heading, style = MaterialTheme.typography.headlineSmall)
                state.asOfLabel?.let { Text("기준시각 $it", style = MaterialTheme.typography.bodySmall) }
                when (state.availability) {
                    FreeSnapshotAvailability.FRESH -> FreshnessBadge("정상", delayed = false)
                    FreeSnapshotAvailability.DELAYED -> {
                        FreshnessBadge("지연", delayed = true)
                        Text("업데이트가 지연되어 마지막 스냅샷을 표시합니다.")
                    }
                    FreeSnapshotAvailability.AWAITING_SNAPSHOT,
                    FreeSnapshotAvailability.UNAVAILABLE ->
                        state.availability.missingChartNotice?.let { Text(it) }
                }
            }
        }
        // Outside `visible`: the four periods stay reachable while the selected one is expired or
        // has not arrived. Hiding them would strand the user on the one period they cannot see.
        item { PeriodTabBar(state.period, onSelectPeriod) }
        if (visible) {
            item { Text("환율 추이", style = MaterialTheme.typography.titleMedium) }
            if (state.seriesToggles.isNotEmpty()) {
                item { SeriesToggles(state.seriesToggles, onToggleSeries) }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onExpand) { Text("전체화면") }
                        }
                        GraphSurface(
                            state,
                            Modifier.fillMaxWidth().height(220.dp).padding(12.dp),
                            zoom = zoom,
                            onZoom = { zoom = it }
                        )
                    }
                }
            }
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

/**
 * What the screen says in place of a chart it cannot draw.
 *
 * Inline this only ever reaches the header, because the chart card is not built at all unless the
 * snapshot is FRESH or DELAYED. Fullscreen keeps its chrome across a period change, so it can be
 * holding the frame when the answer turns out to be missing or expired — and there the same
 * sentence goes where the chart was, from here, so the two cannot come to disagree.
 */
private val FreeSnapshotAvailability.missingChartNotice: String?
    get() = when (this) {
        FreeSnapshotAvailability.AWAITING_SNAPSHOT -> "아직 표시할 스냅샷이 없습니다."
        FreeSnapshotAvailability.UNAVAILABLE -> "스냅샷이 만료되어 데이터를 표시할 수 없습니다."
        FreeSnapshotAvailability.FRESH, FreeSnapshotAvailability.DELAYED -> null
    }

/**
 * The graph, filling whatever it is given.
 *
 * Shared by the card and the fullscreen view so the two cannot drift: the empty-state wording in
 * particular has to say the same thing in both, and it distinguishes "you switched everything off"
 * from "this period has no data".
 */
@Composable
private fun GraphSurface(
    state: FreeSnapshotUiState,
    modifier: Modifier = Modifier,
    zoom: GraphZoomState = GraphZoomState(),
    onZoom: ((GraphZoomState) -> Unit)? = null
) {
    val graph = state.graph
    if (graph == null) {
        // Empty space here reads as a chart that broke rather than one that is not there yet.
        Box(modifier, contentAlignment = Alignment.Center) {
            state.availability.missingChartNotice?.let { Text(it, textAlign = TextAlign.Center) }
        }
        return
    }
    GraphChart(
        prepared = graph,
        visibleIds = state.visibleSeriesIds,
        modifier = modifier,
        zoom = zoom,
        onZoom = onZoom,
        emptyMessage = if (state.seriesToggles.isNotEmpty() && state.seriesToggles.none { it.visible }) {
            "표시할 항목을 선택해 주세요."
        } else {
            "표시할 그래프 데이터가 없습니다."
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesToggles(toggles: List<FreeSeriesToggle>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        toggles.forEach { series ->
            FilterChip(
                selected = series.visible,
                onClick = { onToggle(series.seriesId) },
                label = { Text(series.label) },
                // The line's own colour. Without it several lines in one frame are unattributable
                // — the per-series cards used to carry the name, and merging them took that away.
                leadingIcon = {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                Color(GraphSeriesStyles.of(series.seriesId, series.label).colorHex),
                                CircleShape
                            )
                    )
                }
            )
        }
    }
}

/**
 * The graph with the chrome that changes it, and nothing else.
 *
 * The period bar and the toggles come along because changing either is the reason to be here; they
 * are bound to the same view model, so a period picked in fullscreen is still picked on the way
 * out. Everything else — tabs, rates, the subscribe button — is covered.
 */
@Composable
private fun FreeGraphFullscreen(
    tab: FreeTab,
    state: FreeSnapshotUiState,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onToggleSeries: (String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tab.heading, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("닫기") }
        }
        PeriodTabBar(state.period, onSelectPeriod)
        if (state.seriesToggles.isNotEmpty()) SeriesToggles(state.seriesToggles, onToggleSeries)

        // A tap anywhere leaves, on every period — iOS does the same
        // (`GraphV2Section.swift:1692`, `:1708`), and zoom is no reason to withhold it. On 1일 iOS
        // routes the tap through a UIKit recognizer declared `require(toFail: doubleTap)`
        // (`GestureOverlayView.swift:99`), so the tap that starts a zoom never reaches dismissal.
        // Whoever adds the double tap here must put both in ONE `detectTapGestures` — its `onTap`
        // waits out the double-tap window when `onDoubleTap` is set, which is the same guarantee.
        // A second, independent detector would give away the first half of every zoom.
        val interaction = remember { MutableInteractionSource() }
        GraphSurface(
            state,
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(interactionSource = interaction, indication = null, onClick = onClose)
        )
    }
}
