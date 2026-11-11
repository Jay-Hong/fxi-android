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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.R
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.alert.BankAlertSectionState.Content
import com.jay.fxi.ui.theme.CardBackground
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
    modifier: Modifier = Modifier,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    var isExpanded by remember { mutableStateOf(true) }
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
                    }
                    is Content.Rows -> {
                        Column(
                            Modifier
                                .testTag(BankAlertSectionTags.ROWS)
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            content.rows.forEachIndexed { index, setting ->
                                key(currency, setting.id) {
                                    ReadOnlyBankSetting(setting = setting, metrics = metrics)
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
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyBankSetting(
    setting: AlertSetting,
    metrics: RateLayoutMetrics
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
            .alpha(if (setting.isEnabled) 1f else 0.5f)
            .padding(horizontal = metrics.horizontalPadding, vertical = metrics.alertRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(metrics.alertBankNameWidth)) {
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
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
}
