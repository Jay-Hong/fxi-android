package com.jay.fxi.ui.alert

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.R
import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.alert.BankAlertSectionState.Content
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusConnecting
import com.jay.fxi.ui.theme.StatusError

@Composable
internal fun AlertSection(
    currency: SupportedCurrency,
    state: BankAlertSectionState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    actionIdentity: AuthIdentityFence?,
    onToggle: (Int, Boolean) -> Unit,
    onDelete: (Int) -> Unit,
    notificationsBlocked: Boolean,
    onEnableNotifications: () -> Unit,
    addAvailability: BankAlertAddAvailability,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    var isExpanded by remember { mutableStateOf(true) }
    var pendingDelete by remember(actionIdentity) { mutableStateOf<PendingBankDelete?>(null) }
    val onToggleExpanded = { isExpanded = !isExpanded }

    Column(
        modifier = modifier
            .testTag(BankAlertSectionTags.ROOT)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(vertical = metrics.sectionPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = SecondaryText,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "환율 알림",
                    color = PrimaryText,
                    fontSize = metrics.sectionTitleFontSize,
                    fontWeight = FontWeight.SemiBold
                )
                if (state.activeCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.activeCount.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            style = TextStyle(
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = state.refreshEnabled,
                modifier = Modifier
                    .testTag(BankAlertSectionTags.REFRESH)
                    .size(32.dp)
            ) {
                if (state.refreshSpinning) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "새로고침",
                        tint = if (state.refreshEnabled) SecondaryText else SecondaryText.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .testTag(BankAlertSectionTags.TOGGLE_EXPANDED)
                    .size(32.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    tint = SecondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150))
        ) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp),
                    color = SecondaryText.copy(alpha = 0.2f),
                    thickness = 0.5.dp
                )
                when (val content = state.content) {
                    Content.Loading -> {
                        Row(
                            modifier = Modifier
                                .testTag(BankAlertSectionTags.LOADING)
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = Primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("알림 설정 로드 중...", color = SecondaryText, fontSize = 12.sp)
                        }
                    }
                    is Content.Error -> {
                        Column(
                            modifier = Modifier
                                .testTag(BankAlertSectionTags.ERROR)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(content.message, color = StatusError, fontSize = 13.sp)
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier.testTag(BankAlertSectionTags.RETRY)
                            ) {
                                Text("다시 시도", color = Primary, fontSize = 13.sp)
                            }
                        }
                    }
                    Content.Empty -> {
                        if (notificationsBlocked) {
                            Column(
                                modifier = Modifier
                                    .testTag(BankAlertSectionTags.NOTIFY_PROMPT)
                                    .fillMaxWidth()
                                    .padding(horizontal = metrics.horizontalPadding, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "환율 알림을 받으려면\n알림 권한이 필요합니다",
                                    color = PrimaryText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "원하는 환율에 도달하면 푸시 알림으로 알려드립니다",
                                    color = SecondaryText,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = onAdd,
                                    modifier = Modifier.testTag(BankAlertSectionTags.NOTIFY_PROMPT_BUTTON),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Primary,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("알림 권한 설정하기")
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .testTag(BankAlertSectionTags.EMPTY)
                                    .fillMaxWidth()
                                    .padding(vertical = metrics.sectionPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bell_slash),
                                    contentDescription = null,
                                    tint = SecondaryText.copy(alpha = 0.5f),
                                    modifier = Modifier.size(28.dp)
                                )
                                Text("설정된 알림이 없습니다", color = SecondaryText, fontSize = 12.sp)
                            }
                            BankAlertAddArea(addAvailability, metrics, onAdd)
                        }
                    }
                    is Content.Rows -> {
                        if (notificationsBlocked) {
                            Row(
                                modifier = Modifier
                                    .testTag(BankAlertSectionTags.NOTIFY_BANNER)
                                    .fillMaxWidth()
                                    .clickable(onClick = onEnableNotifications)
                                    .padding(horizontal = metrics.horizontalPadding, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = StatusError,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "알림을 받을 수 없습니다",
                                        color = PrimaryText,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text("알림 권한을 설정해주세요", color = SecondaryText, fontSize = 12.sp)
                                }
                            }
                        }
                        Column(
                            Modifier
                                .testTag(BankAlertSectionTags.ROWS)
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            content.rows.forEachIndexed { index, setting ->
                                key(currency, setting.id) {
                                    BankSetting(
                                        setting = setting,
                                        metrics = metrics,
                                        onOpen = { onEdit(setting.id) },
                                        onToggle = { enabled -> onToggle(setting.id, enabled) },
                                        onDelete = {
                                            val id = setting.id
                                            pendingDelete = PendingBankDelete(
                                                id = id,
                                                bankName = setting.bankType?.displayName ?: setting.bank,
                                                confirm = { onDelete(id) }
                                            )
                                        }
                                    )
                                }
                                if (index < content.rows.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = metrics.horizontalPadding),
                                        color = SecondaryText.copy(alpha = 0.1f),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                        }
                        BankAlertAddArea(addAvailability, metrics, onAdd)
                    }
                }
            }
        }
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            modifier = Modifier.testTag(BankAlertSectionTags.DELETE_DIALOG),
            onDismissRequest = { pendingDelete = null },
            title = { Text("알림 삭제") },
            text = { Text("'${pending.bankName}' 알림을 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(BankAlertSectionTags.DELETE_CONFIRM),
                    onClick = {
                        pendingDelete = null
                        pending.confirm()
                    }
                ) { Text("삭제", color = StatusError) }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag(BankAlertSectionTags.DELETE_CANCEL),
                    onClick = { pendingDelete = null }
                ) { Text("취소") }
            }
        )
    }
}

@Composable
private fun BankAlertAddArea(
    availability: BankAlertAddAvailability,
    metrics: RateLayoutMetrics,
    onAdd: () -> Unit
) {
    when (availability) {
        BankAlertAddAvailability.Hidden -> Unit
        is BankAlertAddAvailability.Available -> {
            Row(
                modifier = Modifier
                    .padding(horizontal = metrics.horizontalPadding)
                    .padding(top = 12.dp, bottom = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(InputBackground)
                    .testTag(BankAlertSectionTags.ADD_BUTTON)
                    .clickable(onClick = onAdd)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AddCircle, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                Text("알림 추가", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (availability.remaining in 1..3) {
                    Text("(${availability.remaining}개 남음)", color = SecondaryText, fontSize = 12.sp)
                }
            }
        }
        BankAlertAddAvailability.AtCap -> {
            Row(
                modifier = Modifier
                    .testTag(BankAlertSectionTags.ADD_AT_CAP)
                    .fillMaxWidth()
                    .padding(horizontal = metrics.horizontalPadding)
                    .padding(top = 12.dp, bottom = 4.dp)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(14.dp))
                Text("최대 30개까지 설정할 수 있습니다", color = SecondaryText, fontSize = 12.sp)
            }
        }
    }
}

private data class PendingBankDelete(val id: Int, val bankName: String, val confirm: () -> Unit)

@Composable
private fun BankSetting(
    setting: AlertSetting,
    metrics: RateLayoutMetrics,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val conditionColor = if (setting.condition == AlertCondition.ABOVE) PositiveColor else NegativeColor
    val statusLabel = when {
        setting.triggered -> "알림 발송됨"
        setting.repeatIntervalSec == null -> null
        else -> RepeatInterval.from(setting.repeatIntervalSec)?.everyLabel ?: "반복 알림"
    }

    Row(
        modifier = Modifier
            .testTag(BankAlertSectionTags.row(setting.id))
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = if (setting.isEnabled) "켜짐" else "꺼짐"
            }
            .padding(horizontal = metrics.horizontalPadding, vertical = metrics.alertRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .testTag(BankAlertSectionTags.rowOpen(setting.id))
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.width(metrics.alertBankNameWidth)
                    .alpha(if (setting.isEnabled) 1f else 0.5f)
            ) {
                Text(
                    text = setting.bankType?.displayName ?: setting.bank,
                    color = PrimaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
                if (statusLabel != null) {
                    Text(
                        text = statusLabel,
                        color = if (setting.triggered) StatusConnecting else Primary,
                        fontSize = 11.sp,
                        modifier = Modifier.offset(y = (-7).dp)
                    )
                }
            }
            Row(
                Modifier.weight(1f).alpha(if (setting.isEnabled) 1f else 0.5f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = setting.formattedThreshold,
                    color = PrimaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (setting.condition == AlertCondition.ABOVE) "△" else "▽",
                    color = conditionColor,
                    fontSize = metrics.alertConditionSymbolFontSize,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = setting.condition.displayText,
                    color = conditionColor,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
            }
        }
        Switch(
            checked = setting.isEnabled,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag(BankAlertSectionTags.toggle(setting.id)).scale(0.75f),
            thumbContent = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                checkedTrackColor = Primary,
                uncheckedTrackColor = SecondaryText.copy(alpha = 0.3f),
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag(BankAlertSectionTags.delete(setting.id)).size(32.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_trash),
                contentDescription = "삭제",
                tint = StatusError.copy(alpha = 0.8f),
                modifier = Modifier.size(21.dp)
            )
        }
    }
}
