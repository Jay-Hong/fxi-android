package com.jay.fxi.ui.premium.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.ui.free.RateRowEditor
import com.jay.fxi.ui.premium.PremiumTopicScreenBanner
import com.jay.fxi.ui.premium.PremiumTopicScreenState
import com.jay.fxi.ui.premium.PremiumTopicUiState
import com.jay.fxi.ui.rates.RateDisplay
import com.jay.fxi.ui.rates.view.RateBarRow
import com.jay.fxi.ui.rates.view.RateRowCustomizeSheet
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.SecondaryText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/**
 * R4-c C3c-2: the premium topic screen's layout — five tabs in a pager, the status line, the selected tab's rows, the row sheet,
 * the rate fullscreen and the news slot. It draws [state] as given and reports only confirmed user actions through the Root route.
 */
@Composable
internal fun PremiumTopicScreen(
    state: PremiumTopicScreenState,
    onUserTabSelected: (TopicDisplayOwner, FreeTab) -> Unit,
    onRetryConnection: (TopicDisplayOwner) -> Unit,
    onRetryTopics: (TopicDisplayOwner, FreeTab) -> Unit,
    onApplyRows: (owner: TopicDisplayOwner, tab: FreeTab, list: RateRowList, seeded: List<String>, order: List<String>, hidden: Set<String>) -> Unit,
    onOpenSettings: () -> Unit,
    newsContent: @Composable (isTabVisible: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val owner = state.ui.owner
    val selectedTab = state.ui.selectedTab
    // A length-prefixed UID keeps all three owner components in an unambiguous Bundle key.
    val ownerKey = owner?.let {
        "${it.identity.uid.length}:${it.identity.uid}:${it.identity.authGeneration}:${it.grantEpoch}"
    }
    val ownerState = rememberSaveableStateHolder()
    var savedOwnerKey by rememberSaveable { mutableStateOf(ownerKey) }
    SideEffect {
        if (savedOwnerKey != ownerKey) {
            // After the old provider has disposed, discard its saved pages and overlays too.
            savedOwnerKey?.let(ownerState::removeState)
            savedOwnerKey = ownerKey
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Background, contentColor = PrimaryText) {
        if (owner != null && ownerKey != null && selectedTab != null) {
            // The provider starts a fresh composition per owner key, so nothing remembered crosses owners.
            ownerState.SaveableStateProvider(ownerKey) {
                PremiumTopicOwnedScreen(state, owner, selectedTab, onUserTabSelected, onRetryConnection,
                    onRetryTopics, onApplyRows, onOpenSettings, newsContent)
            }
        }
    }
}

private data class PremiumTopicEdit(val owner: TopicDisplayOwner, val tab: FreeTab, val editor: RateRowEditor)

@Composable
private fun PremiumTopicOwnedScreen(
    state: PremiumTopicScreenState,
    owner: TopicDisplayOwner,
    selectedTab: FreeTab,
    onUserTabSelected: (TopicDisplayOwner, FreeTab) -> Unit,
    onRetryConnection: (TopicDisplayOwner) -> Unit,
    onRetryTopics: (TopicDisplayOwner, FreeTab) -> Unit,
    onApplyRows: (TopicDisplayOwner, FreeTab, RateRowList, List<String>, List<String>, Set<String>) -> Unit,
    onOpenSettings: () -> Unit,
    newsContent: @Composable (Boolean) -> Unit
) {
    val tabs = FreeTab.entries
    val pager = rememberPagerState(initialPage = selectedTab.ordinal) { tabs.size }
    val pages = rememberSaveableStateHolder()
    val currentTab by rememberUpdatedState(selectedTab)
    val reportSelection by rememberUpdatedState(onUserTabSelected)
    var gestureActive by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var overlayTab by rememberSaveable { mutableStateOf(selectedTab.name) }
    var editing by remember { mutableStateOf<PremiumTopicEdit?>(null) }

    LaunchedEffect(pager) {
        var drag: DragInteraction.Start? = null
        var startPage = pager.settledPage
        pager.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    drag = interaction
                    startPage = pager.settledPage
                    gestureActive = true
                }
                is DragInteraction.Stop -> if (interaction.start == drag) {
                    // Stop is emitted before the fling starts. Cross a frame before awaiting its final settle.
                    withFrameNanos { }
                    snapshotFlow { pager.isScrollInProgress }.first { !it }
                    val landed = pager.settledPage
                    drag = null
                    gestureActive = false
                    if (landed != startPage) reportSelection(owner, tabs[landed])
                }
                is DragInteraction.Cancel -> if (interaction.start == drag) {
                    withFrameNanos { }
                    snapshotFlow { pager.isScrollInProgress }.first { !it }
                    drag = null
                    gestureActive = false
                    pager.scrollToPage(currentTab.ordinal)
                }
                else -> Unit
            }
        }
    }
    LaunchedEffect(selectedTab) {
        if (gestureActive) {
            snapshotFlow { gestureActive }.first { !it }
            // Allow the final user report to be answered before applying a deferred focus correction.
            withFrameNanos { }
        }
        pager.scrollToPage(currentTab.ordinal)
    }
    LaunchedEffect(selectedTab) {
        if (overlayTab != selectedTab.name) {
            editing = null
            fullscreen = false
            overlayTab = selectedTab.name
        }
    }

    val openEditor: (RateRowEditor) -> Unit = { editing = PremiumTopicEdit(owner, selectedTab, it) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = RateLayoutMetrics.fromWindow(maxWidth, maxHeight)
        CompositionLocalProvider(LocalRateLayoutMetrics provides metrics) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Column(
                            Modifier.weight(1f).testTag(PremiumTopicTags.tab(tab))
                                .semantics { selected = isSelected }
                                .clickable { onUserTabSelected(owner, tab) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(tab.title, fontSize = 14.sp, color = if (isSelected) PrimaryText else SecondaryText,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(2.dp).background(if (isSelected) Primary else Color.Transparent))
                        }
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(PremiumTopicTags.SETTINGS)) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                }
                state.banner?.let { banner ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(banner.text, Modifier.testTag(PremiumTopicTags.BANNER), style = MaterialTheme.typography.bodySmall)
                            state.updatedText?.let {
                                Text(it, Modifier.testTag(PremiumTopicTags.BANNER_TIME), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        banner.action?.let {
                            TextButton(onClick = {
                                when (banner) {
                                    PremiumTopicScreenBanner.Failed -> onRetryConnection(owner)
                                    is PremiumTopicScreenBanner.Topic -> onRetryTopics(owner, selectedTab)
                                    else -> Unit
                                }
                            }, modifier = Modifier.testTag(PremiumTopicTags.BANNER_ACTION)) {
                                Text(it)
                            }
                        }
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).testTag(PremiumTopicTags.PAGER),
                    key = { tabs[it].name }, userScrollEnabled = !fullscreen && editing == null) { page ->
                    val tab = tabs[page]
                    pages.SaveableStateProvider(tab.name) {
                        if (tab == FreeTab.NEWS) {
                            newsContent(pager.settledPage == page)
                        } else {
                            // Keep each page's position even while it has no accepted rows to draw.
                            val scroll = rememberScrollState()
                            if (tab == selectedTab) {
                                PremiumTopicRates(state.ui, scroll, fullscreen = false, onToggleFullscreen = { fullscreen = true },
                                    onOpenEditor = openEditor)
                            } else {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            if (fullscreen && selectedTab.isData) {
                BackHandler(enabled = editing == null) { fullscreen = false }
                Surface(Modifier.fillMaxSize().testTag(PremiumTopicTags.FULLSCREEN_LAYER)
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { fullscreen = false }) },
                    color = Background, contentColor = PrimaryText) {
                    PremiumTopicRates(state.ui, rememberScrollState(), fullscreen = true,
                        onToggleFullscreen = { fullscreen = false }, onOpenEditor = openEditor,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars))
                }
            }
            editing?.takeIf { it.owner == owner && it.tab == selectedTab }?.let { edit ->
                RateRowCustomizeSheet(editor = edit.editor, onDismiss = { editing = null },
                    onApply = { seeded, order, hidden ->
                        editing = null
                        onApplyRows(edit.owner, edit.tab, edit.editor.list, seeded, order, hidden)
                    })
            }
        }
    }
}

@Composable
private fun PremiumTopicRates(
    ui: PremiumTopicUiState,
    scroll: ScrollState,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenEditor: (RateRowEditor) -> Unit,
    modifier: Modifier = Modifier
) {
    val metrics = LocalRateLayoutMetrics.current
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = metrics.horizontalPadding), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.heading.orEmpty(), modifier = Modifier.weight(1f).then(
                if (fullscreen) Modifier else Modifier.testTag(PremiumTopicTags.HEADING)),
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onToggleFullscreen, modifier = Modifier.testTag(
                if (fullscreen) PremiumTopicTags.FULLSCREEN_CLOSE else PremiumTopicTags.FULLSCREEN)) {
                Text(if (fullscreen) "닫기" else "전체화면")
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .then(if (fullscreen) Modifier else Modifier.testTag(PremiumTopicTags.RATES)
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onToggleFullscreen() }) })
                .verticalScroll(scroll).windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = metrics.horizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing)
        ) {
            if (ui.rateSections.isEmpty()) Text("표시할 환율 데이터가 없습니다.")
            ui.rateSections.forEach { section ->
                key(section.asset, section.list) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        ui.rowEditors.firstOrNull { it.list == section.list }?.let { editor ->
                            TextButton(onClick = { onOpenEditor(editor) }, modifier = Modifier.testTag(PremiumTopicTags.customize(editor.list))) {
                                Text("조정")
                            }
                        }
                    }
                    if (section.rows.isEmpty()) Text("모두 숨겨져 있습니다. 조정에서 다시 켤 수 있습니다.")
                    section.rows.forEach { row ->
                        key(row.id) { RateBarRow(row, section.domain, Modifier.testTag(PremiumTopicTags.row(row.id))) }
                    }
                }
            }
            ui.dollarIndex?.let { dxy ->
                Text("달러 인덱스 (DXY) · ${RateDisplay.format(dxy.rate)}", Modifier.testTag(PremiumTopicTags.DXY),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
