package com.jay.fxi.ui.subscription

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.R
import com.jay.fxi.domain.model.AlertCondition
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

/**
 * 체험용 알림 설정 섹션
 * 메인 AlertSection/AlertRow 와 동일한 레이아웃 (+ "예시" 뱃지, 안내 배너)
 */
@Composable
fun SampleAlertSection(
    viewModel: SamplePreviewViewModel,
    onAddTap: () -> Unit,
    onEditTap: (SampleAlertSetting) -> Unit,
    modifier: Modifier = Modifier,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    var isExpanded by remember { mutableStateOf(true) }
    val settings = viewModel.filteredAlertSettings
    val activeCount = settings.count { it.isEnabled }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(vertical = metrics.sectionPadding)
    ) {
        // 헤더 (메인 AlertSection 헤더와 동일 구조)
        SectionHeader(
            isExpanded = isExpanded,
            activeCount = activeCount,
            onToggle = { isExpanded = !isExpanded },
            metrics = metrics
        )

        // 내용 (펼침/접힘, 메인과 동일 150ms)
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150))
        ) {
            SectionContent(
                settings = settings,
                viewModel = viewModel,
                onAddTap = onAddTap,
                onEditTap = onEditTap,
                metrics = metrics
            )
        }
    }
}

@Composable
private fun SectionHeader(
    isExpanded: Boolean,
    activeCount: Int,
    onToggle: () -> Unit,
    metrics: RateLayoutMetrics
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 왼쪽: 토글 영역 (클릭 → 접기/펼치기)
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = SecondaryText,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "환율 알림",
                fontSize = metrics.sectionTitleFontSize,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryText
            )

            // 활성 개수 뱃지 (메인 AlertSection과 동일: 18dp 원형)
            if (activeCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$activeCount",
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

            Spacer(modifier = Modifier.width(6.dp))

            // "예시" 뱃지 (캡슐형)
            Text(
                text = "예시",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFFA500),
                lineHeight = 9.sp,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                modifier = Modifier
                    .background(
                        Color(0xFFFFA500).copy(alpha = 0.15f),
                        RoundedCornerShape(percent = 50)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // 오른쪽: 접기/펼치기 버튼 (메인과 동일 크기)
        IconButton(
            onClick = { onToggle() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "접기" else "펼치기",
                tint = SecondaryText,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SectionContent(
    settings: List<SampleAlertSetting>,
    viewModel: SamplePreviewViewModel,
    onAddTap: () -> Unit,
    onEditTap: (SampleAlertSetting) -> Unit,
    metrics: RateLayoutMetrics
) {
    Column {
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = SecondaryText.copy(alpha = 0.2f),
            thickness = 0.5.dp
        )

        if (settings.isEmpty()) {
            EmptyStateView(metrics)
        } else {
            AlertListView(
                settings = settings,
                viewModel = viewModel,
                onEditTap = onEditTap,
                metrics = metrics
            )
        }

        AddAlertButton(
            canAdd = viewModel.canAddAlert,
            remainingCount = viewModel.remainingAlertCount,
            onAddTap = onAddTap,
            metrics = metrics
        )
    }
}

@Composable
private fun EmptyStateView(metrics: RateLayoutMetrics) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = metrics.sectionPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_bell_slash),
            contentDescription = null,
            tint = SecondaryText.copy(alpha = 0.5f),
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = "설정된 알림이 없습니다",
            color = SecondaryText,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun AlertListView(
    settings: List<SampleAlertSetting>,
    viewModel: SamplePreviewViewModel,
    onEditTap: (SampleAlertSetting) -> Unit,
    metrics: RateLayoutMetrics
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        settings.forEachIndexed { index, setting ->
            SampleAlertRow(
                setting = setting,
                onEdit = { onEditTap(setting) },
                onToggle = { viewModel.toggleAlert(setting.id) },
                onDelete = { viewModel.deleteAlert(setting.id) },
                metrics = metrics
            )
            if (index < settings.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = metrics.horizontalPadding),
                    color = SecondaryText.copy(alpha = 0.1f),
                    thickness = 0.5.dp
                )
            }
        }
    }
}

@Composable
private fun SampleAlertRow(
    setting: SampleAlertSetting,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    metrics: RateLayoutMetrics
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val contentAlpha = if (setting.isEnabled) 1f else 0.5f
    val conditionColor = when (setting.condition) {
        AlertCondition.ABOVE -> PositiveColor
        AlertCondition.BELOW -> NegativeColor
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(
                horizontal = metrics.horizontalPadding,
                vertical = metrics.alertRowVerticalPadding
            )
    ) {
        // 은행명 + 트리거 상태 (메인 AlertRow와 동일)
        Column(
            modifier = Modifier
                .width(metrics.alertBankNameWidth)
                .alpha(contentAlpha)
        ) {
            Text(
                text = setting.bank.displayName,
                color = PrimaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
            if (setting.triggered) {
                Text(
                    text = "알림 발송됨",
                    color = StatusConnecting,
                    fontSize = 11.sp,
                    modifier = Modifier.offset(y = (-7).dp)
                )
            }
        }

        // 조건 정보 (메인 AlertRow와 동일)
        Row(
            modifier = Modifier
                .weight(1f)
                .alpha(contentAlpha),
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
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = setting.conditionSymbol,
                color = conditionColor,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = setting.condition.displayText,
                color = conditionColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }

        // 토글 (메인 AlertRow와 동일: 0.75f, 흰색 thumb)
        Switch(
            checked = setting.isEnabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.scale(0.75f),
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

        // 삭제 버튼 (메인 AlertRow와 동일: ic_trash)
        IconButton(
            onClick = { showDeleteConfirmation = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_trash),
                contentDescription = "삭제",
                tint = StatusError.copy(alpha = 0.8f),
                modifier = Modifier.size(21.dp)
            )
        }
    }

    // 삭제 확인 다이얼로그
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("알림 삭제") },
            text = { Text("'${setting.bank.displayName}' 알림을 삭제하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) {
                    Text("삭제", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun AddAlertButton(
    canAdd: Boolean,
    remainingCount: Int,
    onAddTap: () -> Unit,
    metrics: RateLayoutMetrics
) {
    if (canAdd) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.horizontalPadding)
                .padding(top = metrics.sectionPadding, bottom = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(InputBackground)
                .clickable(onClick = onAddTap)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.AddCircle,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "알림 추가 (예시)",
                color = Primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (remainingCount <= SampleAlertConfig.SHOW_REMAINING_THRESHOLD) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "(${remainingCount}개 남음)",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.horizontalPadding)
                .padding(top = metrics.sectionPadding, bottom = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = SecondaryText,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "최대 ${SampleAlertConfig.MAX_COUNT}개까지 설정할 수 있습니다",
                color = SecondaryText,
                fontSize = 12.sp
            )
        }
    }
}

