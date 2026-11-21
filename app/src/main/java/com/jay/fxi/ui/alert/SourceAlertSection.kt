package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.ManualRefreshState
import com.jay.fxi.data.alerts.SourceAlertRowPolicy
import com.jay.fxi.data.alerts.SourceAlertSettingsOwner
import com.jay.fxi.data.alerts.SourceAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.SourceAlertSetting
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusConnecting
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow

/** S8-4a: the premium tether tab reads the process's one source settings owner; it writes nothing (S8-5). */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SourceAlertSectionEntryPoint {
    fun sourceAlertSettingsOwner(): SourceAlertSettingsOwner
}

internal data class SourceAlertSectionState(
    val content: Content,
    val activeCount: Int,
    val refreshSpinning: Boolean,
    val refreshEnabled: Boolean
) {
    sealed interface Content {
        data object Loading : Content
        data class Error(val message: String) : Content   // Fixed copy, never server text.
        data object Empty : Content
        data class Rows(val rows: List<SourceAlertSetting>) : Content
    }
}

/**
 * A success is shown only for [shownIdentity] (UID and generation); another identity's success shows nothing yet. Before any
 * success, a terminal failure shows its error, whatever identity the snapshot carries (none, on a first failure).
 */
internal fun sourceAlertSectionState(
    settings: SourceAlertSettingsState,
    manual: ManualRefreshState,
    shownIdentity: AuthIdentityFence
): SourceAlertSectionState {
    val snapshot = settings.settings?.takeIf { settings.identity == shownIdentity }
    val rows = snapshot?.let { adopted ->
        SourceAlertRowPolicy.EXCHANGES.project(adopted, SourceAlertRowPolicy.SETTINGS_ASSET).sortedByDescending { it.createdAt }
    }.orEmpty()
    val phase = settings.phase
    val content = when {
        rows.isNotEmpty() -> SourceAlertSectionState.Content.Rows(rows)
        snapshot != null -> SourceAlertSectionState.Content.Empty
        settings.settings != null -> SourceAlertSectionState.Content.Loading
        phase is AlertLoadPhase.TerminalError -> SourceAlertSectionState.Content.Error(sourceAlertErrorMessage(phase.failure))
        else -> SourceAlertSectionState.Content.Loading
    }
    return SourceAlertSectionState(
        content = content,
        activeCount = rows.count { it.isEnabled },
        refreshSpinning = manual.sending,
        refreshEnabled = !manual.coolingDown && !manual.sending
    )
}

private fun sourceAlertErrorMessage(failure: AlertLoadFailure): String = when (failure) {
    AlertLoadFailure.AuthRequired -> "로그인이 필요합니다."
    AlertLoadFailure.Forbidden -> "접근 권한이 없습니다."
    is AlertLoadFailure.Failed -> if (failure.kind == AlertRepositoryFailureKind.NETWORK) {
        "네트워크 연결을 확인해 주세요."
    } else {
        "알림 설정을 불러오지 못했습니다."
    }
}

/** A projection/delegation seam over the one source owner, never another canonical settings owner. Read only. */
internal interface SourceAlertSectionSource {
    val settings: StateFlow<SourceAlertSettingsState>
    val manualRefresh: StateFlow<ManualRefreshState>
    fun ensure()
    fun authoritative()
    fun manual()
}

internal fun interface SourceAlertSectionSourceFactory {
    fun create(context: Context): SourceAlertSectionSource
}

internal object ProductionSourceAlertSectionSourceFactory : SourceAlertSectionSourceFactory {
    override fun create(context: Context): SourceAlertSectionSource {
        val owner = EntryPointAccessors.fromApplication(context.applicationContext, SourceAlertSectionEntryPoint::class.java)
            .sourceAlertSettingsOwner()
        return object : SourceAlertSectionSource {
            override val settings = owner.state
            override val manualRefresh = owner.manualRefresh
            override fun ensure() = owner.ensure()
            override fun authoritative() = owner.authoritative()
            override fun manual() = owner.manual()
        }
    }
}

internal object SourceAlertSectionTags {
    const val ROOT = "source_alert_section"
    const val REFRESH = "source_alert_refresh"
    const val TOGGLE_EXPANDED = "source_alert_toggle_expanded"
    const val RETRY = "source_alert_retry"
    const val ACTIVE_COUNT = "source_alert_active_count"
    const val ROW = "source_alert_row"
}

/** One mount of the section; its stored callbacks act only while it is alive and still shown. */
private class SourceAlertMount {
    var alive = true
}

/**
 * The tether tab's source alerts, read only. [owner] is the screen's display owner; [isShown] answers, at the moment of a
 * click, whether this owner's tether tab is active with settings and news detail closed. A rendered fullscreen removes the mount.
 */
@Composable
internal fun SourceAlertSection(
    tab: FreeTab,
    owner: TopicDisplayOwner,
    isShown: () -> Boolean,
    modifier: Modifier = Modifier,
    sourceFactory: SourceAlertSectionSourceFactory = ProductionSourceAlertSectionSourceFactory
) {
    if (tab != FreeTab.TETHER) return
    val applicationContext = LocalContext.current.applicationContext
    val source = remember(applicationContext, sourceFactory) { sourceFactory.create(applicationContext) }
    key(source, owner) {
        val settings by source.settings.collectAsState()
        val manual by source.manualRefresh.collectAsState()
        val mount = remember { SourceAlertMount() }
        val shownNow by rememberUpdatedState(isShown)
        DisposableEffect(mount) {
            onDispose { mount.alive = false }
        }
        LaunchedEffect(mount) { source.ensure() }
        val act: (() -> Unit) -> Unit = { action -> if (mount.alive && shownNow()) action() }
        SourceAlertSectionView(
            state = sourceAlertSectionState(settings, manual, owner.identity),
            onRetry = { act(source::authoritative) },
            onRefresh = { act(source::manual) },
            modifier = modifier
        )
    }
}

@Composable
private fun SourceAlertSectionView(
    state: SourceAlertSectionState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    Column(
        modifier = modifier
            .testTag(SourceAlertSectionTags.ROOT)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { isExpanded = !isExpanded }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("테더 알림", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (state.activeCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.testTag(SourceAlertSectionTags.ACTIVE_COUNT).size(18.dp).clip(CircleShape).background(Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(state.activeCount.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = state.refreshEnabled,
                modifier = Modifier.testTag(SourceAlertSectionTags.REFRESH).size(32.dp)
            ) {
                if (state.refreshSpinning) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "새로고침",
                        tint = if (state.refreshEnabled) SecondaryText else SecondaryText.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier.testTag(SourceAlertSectionTags.TOGGLE_EXPANDED).size(32.dp)
            ) {
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    tint = SecondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                when (val content = state.content) {
                    SourceAlertSectionState.Content.Loading -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                    is SourceAlertSectionState.Content.Error -> Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(content.message, color = SecondaryText, fontSize = 13.sp)
                        TextButton(onClick = onRetry, modifier = Modifier.testTag(SourceAlertSectionTags.RETRY)) {
                            Text("다시 시도", color = Primary, fontSize = 13.sp)
                        }
                    }
                    SourceAlertSectionState.Content.Empty -> Text(
                        "설정된 거래소 알림이 없습니다",
                        color = SecondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    is SourceAlertSectionState.Content.Rows -> Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        content.rows.forEach { SourceAlertReadRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceAlertReadRow(setting: SourceAlertSetting) {
    val conditionColor = when (setting.condition) {
        AlertCondition.ABOVE -> PositiveColor
        AlertCondition.BELOW -> NegativeColor
    }
    Row(
        modifier = Modifier
            .testTag(SourceAlertSectionTags.ROW)
            .fillMaxWidth()
            .alpha(if (setting.isEnabled) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(96.dp)) {
            Text(
                Exchange.fromCode(setting.source)?.displayName ?: setting.source,
                color = PrimaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The row's on/off state as read text, in the bank switch's own words (AlertSection's stateDescription).
            Text(if (setting.isEnabled) "켜짐" else "꺼짐", color = SecondaryText, fontSize = 11.sp)
            if (setting.triggered) Text("알림 발송됨", color = StatusConnecting, fontSize = 11.sp)
        }
        Text(
            ExchangeRate.formatRate(setting.threshold, setting.asset).replace("원", ""),
            color = PrimaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Spacer(Modifier.width(4.dp))
        Text(setting.condition.symbol, color = conditionColor, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(2.dp))
        Text(setting.condition.displayText, color = conditionColor, fontSize = 14.sp, maxLines = 1)
        if (setting.repeatIntervalSec != null) {
            Spacer(Modifier.width(8.dp))
            Text("반복", color = SecondaryText, fontSize = 12.sp)
        }
    }
}
