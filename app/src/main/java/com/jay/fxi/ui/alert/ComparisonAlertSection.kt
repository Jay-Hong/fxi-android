package com.jay.fxi.ui.alert

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.jay.fxi.data.alerts.AlertLoadFailure
import com.jay.fxi.data.alerts.AlertLoadPhase
import com.jay.fxi.data.alerts.ComparisonAlertSettingsOwner
import com.jay.fxi.data.alerts.ComparisonAlertSettingsState
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicDisplayOwner
import com.jay.fxi.data.repository.AlertRepositoryFailureKind
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.ui.premium.PremiumTopicConsumer
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
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ComparisonAlertSectionEntryPoint {
    fun comparisonAlertSettingsOwner(): ComparisonAlertSettingsOwner
}

internal interface ComparisonAlertSectionSource {
    val state: StateFlow<ComparisonAlertSettingsState>
    fun ensure()
    fun authoritative()
}

internal fun interface ComparisonAlertSectionSourceFactory {
    fun create(context: Context): ComparisonAlertSectionSource
}

internal class OwnerComparisonSectionSource(owner: ComparisonAlertSettingsOwner) : ComparisonAlertSectionSource {
    private val settingsOwner = owner
    override val state = owner.state
    override fun ensure() = settingsOwner.ensure()
    override fun authoritative() = settingsOwner.authoritative()
}

internal object ProductionComparisonAlertSectionSourceFactory : ComparisonAlertSectionSourceFactory {
    override fun create(context: Context): ComparisonAlertSectionSource {
        val entry = EntryPointAccessors.fromApplication(context, ComparisonAlertSectionEntryPoint::class.java)
        return OwnerComparisonSectionSource(entry.comparisonAlertSettingsOwner())
    }
}

internal sealed interface ComparisonSectionBody {
    data object Loading : ComparisonSectionBody
    data class Error(val message: String) : ComparisonSectionBody
    data class Loaded(val rows: List<ComparisonAlertSetting>) : ComparisonSectionBody {
        val enabledCount = rows.count { it.isEnabled }
    }
}

internal fun ComparisonAlertSettingsState.body(identityNow: AuthIdentityFence, tab: String, kind: String): ComparisonSectionBody {
    if (identity == identityNow && adopted != null) return ComparisonSectionBody.Loaded(adopted.project(tab, kind))
    val error = phase as? AlertLoadPhase.TerminalError ?: return ComparisonSectionBody.Loading
    if (identity != null && identity != identityNow) return ComparisonSectionBody.Loading
    val message = when (val failure = error.failure) {
        AlertLoadFailure.AuthRequired -> "로그인이 필요합니다."
        AlertLoadFailure.Forbidden -> "접근 권한이 없습니다."
        is AlertLoadFailure.Failed -> if (failure.kind == AlertRepositoryFailureKind.NETWORK) {
            "네트워크 연결을 확인해 주세요."
        } else {
            "알림 설정을 불러오지 못했습니다."
        }
    }
    return ComparisonSectionBody.Error(message)
}

internal data class ComparisonRowText(
    val pair: String, val value: String, val direction: String, val description: String, val note: String?
)

internal fun ComparisonAlertSetting.rowText(): ComparisonRowText {
    fun name(code: String) = Exchange.fromCode(code)?.displayName ?: Bank.fromCode(code)?.shortName ?: code
    val left = name(leftSource)
    val right = name(rightSource)
    val signed = diffType == "signed"
    val pattern = if (tab == "tether") "0.##" else "0.0"
    val number = DecimalFormat(pattern, DecimalFormatSymbols(Locale.ROOT)).apply {
        roundingMode = RoundingMode.HALF_EVEN
        isGroupingUsed = false
    }.format(if (threshold == 0.0) 0.0 else threshold)
    val value = if (signed && threshold > 0) "+$number" else number
    val spoken = if (signed && threshold >= 0) "+$number" else number
    val above = operator == "gte"
    val direction = if (above) "이상" else "이하"
    val description = if (signed) {
        "$left vs $right 김프 ${spoken}원 $direction"
    } else {
        val change = if (above) "벌어지면" else "좁혀지면"
        "${left}−${right} 차이 ${spoken}원 $direction $change"
    }
    return ComparisonRowText(if (signed) "$left-$right" else "$left↔$right", value,
        if (above) "△ 이상" else "▽ 이하", description,
        if (triggered) "알림 발송됨" else RepeatInterval.from(repeatIntervalSec)?.everyLabel)
}

/** Main-confined. Once a publication or a live check retires this mount its saved actions stay inert. */
internal class ComparisonSectionMount(private val shown: () -> Boolean, private val retired: () -> Unit) {
    private var alive = true

    fun end() {
        if (alive) {
            alive = false
            retired()
        }
    }

    fun dispose() { alive = false }

    fun runShown(action: () -> Unit) {
        if (!alive) return
        if (shown() && alive) action() else end()
    }
}

internal class ComparisonSlot(private val owner: TopicDisplayOwner, private val tab: FreeTab) {
    fun matches(publishedOwner: TopicDisplayOwner?, selectedTab: FreeTab?) =
        publishedOwner == owner && selectedTab == tab
}

@Composable
internal fun ComparisonAlertSection(
    tab: FreeTab, kind: String, owner: TopicDisplayOwner, consumer: PremiumTopicConsumer,
    sourceFactory: ComparisonAlertSectionSourceFactory, isShown: () -> Boolean
) {
    val slot = remember(owner, tab) { ComparisonSlot(owner, tab) }
    val current = { consumer.currentState().ui.let { slot.matches(it.owner, it.selectedTab) } && isShown() }
    ComparisonSectionMountContent(tab, kind, owner, sourceFactory, current) { end ->
        consumer.addScreenListener { published ->
            if (!slot.matches(published.ui.owner, published.ui.selectedTab)) end()
        }
    }
}

@Composable
internal fun ComparisonSectionMountContent(
    tab: FreeTab, kind: String, owner: TopicDisplayOwner, sourceFactory: ComparisonAlertSectionSourceFactory,
    shown: () -> Boolean, listen: (() -> Unit) -> (() -> Unit)
) {
    val serverTab = tab.serverTab ?: return
    val current by rememberUpdatedState(shown)
    if (!current()) return
    var epoch by remember(owner, tab, kind) { mutableStateOf(0L) }
    key(owner, tab, kind, sourceFactory, epoch) {
        val context = LocalContext.current.applicationContext
        val source = remember(context, sourceFactory) { sourceFactory.create(context) }
        val mount = remember { ComparisonSectionMount({ current() }, { epoch++ }) }
        var attached by remember { mutableStateOf(false) }
        DisposableEffect(mount) {
            val stop = listen(mount::end)
            mount.runShown {
                source.ensure()
                attached = true
            }
            onDispose {
                stop()
                mount.dispose()
            }
        }
        val body = if (attached) {
            val state by source.state.collectAsState()
            state.body(owner.identity, serverTab, kind)
        } else ComparisonSectionBody.Loading
        ComparisonCard(kind, serverTab, body) {
            mount.runShown(source::authoritative)
        }
    }
}

@Composable
private fun ComparisonCard(kind: String, tab: String, body: ComparisonSectionBody, refresh: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    val signed = kind == "signed"
    val title = if (signed) "김프 알림" else "비교 알림"
    Column(Modifier.fillMaxWidth().testTag("comparison-$kind").padding(8.dp)
        .background(CardBackground, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (signed) {
                Box(Modifier.testTag("comparison-won-icon").size(24.dp).border(1.dp, Primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text("₩", Modifier.clearAndSetSemantics {}, color = Primary)
                }
            } else {
                Icon(Icons.Default.SwapHoriz, null, Modifier.testTag("comparison-swap-icon"), tint = Primary)
            }
            Text(title, Modifier.padding(start = 8.dp), color = PrimaryText)
            if (body is ComparisonSectionBody.Loaded && body.enabledCount > 0) {
                Text(body.enabledCount.toString(), Modifier.padding(start = 6.dp).testTag("comparison-$kind-count")
                    .background(Primary, CircleShape).padding(horizontal = 6.dp), color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(refresh, Modifier.testTag("comparison-$kind-refresh")) { Icon(Icons.Default.Refresh, "$title 새로고침") }
            IconButton({ expanded = !expanded }, Modifier.testTag("comparison-$kind-expand")) {
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    "$title " + if (expanded) "접기" else "펼치기")
            }
        }
        if (expanded) when (body) {
            ComparisonSectionBody.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Text("불러오는 중...", Modifier.padding(start = 8.dp), color = SecondaryText)
            }
            is ComparisonSectionBody.Error -> {
                Text(body.message, color = SecondaryText)
                TextButton(refresh, Modifier.testTag("comparison-$kind-retry")) { Text("다시 시도") }
            }
            is ComparisonSectionBody.Loaded -> {
                if (body.rows.isEmpty()) Text(when {
                    signed -> "거래소 시세와 환율의 차이(김프/역프) 알림"
                    tab == "tether" -> "거래소 간 가격 차이 알림"
                    else -> "소스 간 가격 차이 알림"
                }, color = SecondaryText)
                body.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 12.dp).testTag("comparison-$kind-divider"),
                        color = SecondaryText.copy(alpha = 0.2f))
                    ComparisonRow(row)
                }
            }
        }
    }
}

@Composable
private fun ComparisonRow(row: ComparisonAlertSetting) {
    val text = row.rowText()
    Row(Modifier.fillMaxWidth().testTag("comparison-row-${row.id}").padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).alpha(if (row.isEnabled) 1f else 0.5f)
            .clearAndSetSemantics { contentDescription = text.description }) {
            Text(text.pair, color = PrimaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text.value, color = PrimaryText)
                Text(text.direction, color = if (row.operator == "gte") PositiveColor else NegativeColor)
            }
            text.note?.let { Text(it, color = if (row.triggered) StatusConnecting else Primary) }
        }
        Switch(checked = row.isEnabled, onCheckedChange = null, modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(row.isEnabled)
            stateDescription = if (row.isEnabled) "켜짐" else "꺼짐"
        })
    }
}
