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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.R
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.LocalRateLayoutMetrics
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.RateLayoutMetrics
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusConnecting
import com.jay.fxi.ui.theme.StatusError
import com.jay.fxi.ui.viewmodel.AlertState
import com.jay.fxi.ui.viewmodel.AlertViewModel

@Composable
fun AlertSection(
    currency: SupportedCurrency,
    alertState: AlertState,
    hasPermission: Boolean,
    isPermissionDenied: Boolean,
    canAddMore: Boolean,
    remainingCount: Int,
    isRefreshing: Boolean,
    canRefresh: Boolean,
    scrollState: ScrollState? = null,
    onToggle: (AlertSetting) -> Unit,
    onDelete: (AlertSetting) -> Unit,
    onEdit: (AlertSetting) -> Unit,
    onAdd: () -> Unit,
    /**
     * 권한 요청 (의도: 권한 승인 후 바로 "알림 추가" 흐름으로 이어짐)
     * - iOS: permissionPromptView / addAlertButton 둘 다 승인 후 add sheet 오픈
     * - Android 13+: 런타임 권한 승인 콜백에서 add sheet 오픈
     * - Android 12 이하: 설정 화면으로 보내고, 돌아오면 사용자가 다시 "알림 추가"를 탭해야 함
     */
    onRequestPermissionForAdd: () -> Unit,
    /**
     * 권한 요청 (의도: 권한만 켜기)
     * - "알림을 받을 수 없습니다" 배너에서 사용
     * - 권한 승인 후 add sheet를 자동으로 열지 않음 (iOS와 동일)
     */
    onRequestPermissionForBanner: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    metrics: RateLayoutMetrics = LocalRateLayoutMetrics.current
) {
    val settings = alertState.settings
        .filter { it.currency == currency.code }
        .distinctBy { it.id }
    val activeCount = settings.count { it.isEnabled }
    var isExpanded by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // 토글 로직 추출 (헤더 영역 + chevron 공유)
    val toggleExpanded = {
        val willExpand = !isExpanded
        isExpanded = willExpand
        // 펼칠 때 스크롤과 애니메이션 동시 시작
        if (willExpand && scrollState != null) {
            coroutineScope.launch {
                val estimatedHeight = 500 + (settings.size * 80)
                scrollState.animateScrollTo(scrollState.value + estimatedHeight)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(vertical = metrics.sectionPadding)
    ) {
        // Header: 토글 영역과 액션 버튼 분리
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
                    .clickable { toggleExpanded() },
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
                    color = PrimaryText,
                    fontSize = metrics.sectionTitleFontSize,
                    fontWeight = FontWeight.SemiBold
                )

                // 활성화된 알림 개수 뱃지
                if (activeCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
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
            }

            // 오른쪽: 새로고침 버튼 (독립 클릭)
            IconButton(
                onClick = onRefresh,
                enabled = canRefresh && !isRefreshing,
                modifier = Modifier.size(32.dp)
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "새로고침",
                        tint = if (canRefresh) SecondaryText else SecondaryText.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 접기/펼치기 버튼 (chevron 클릭 가능)
            IconButton(
                onClick = { toggleExpanded() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    tint = SecondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Content (빠른 애니메이션 150ms)
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Divider after header (iOS와 동일)
                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp),
                    color = SecondaryText.copy(alpha = 0.2f),
                    thickness = 0.5.dp
                )

                when {
                    alertState is AlertState.Loading -> {
                        // Loading view (iOS와 동일: 가로 배치)
                        Row(
                            modifier = Modifier
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "알림 설정 로드 중...",
                                color = SecondaryText,
                                fontSize = 12.sp
                            )
                        }
                    }

                    alertState is AlertState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = (alertState as AlertState.Error).message,
                                color = StatusError,
                                fontSize = 13.sp
                            )
                            TextButton(onClick = onRetry) {
                                Text("다시 시도", color = Primary, fontSize = 13.sp)
                            }
                        }
                    }

                    !hasPermission && settings.isEmpty() -> {
                        // Permission prompt view (iOS와 동일: 아이콘 + 리치 텍스트)
                        PermissionPromptView(
                            isPermissionDenied = isPermissionDenied,
                            onRequestPermissionForAdd = onRequestPermissionForAdd,
                            onOpenSettings = onOpenSettings
                        )
                    }

                    else -> {
                        // Permission warning banner (권한 없음 + 목록 있음)
                        if (!hasPermission && settings.isNotEmpty()) {
                            PermissionWarningBanner(
                                isPermissionDenied = isPermissionDenied,
                                onRequestPermissionForBanner = onRequestPermissionForBanner,
                                onOpenSettings = onOpenSettings
                            )
                        }

                        // 알림 목록 또는 빈 상태
                        if (settings.isNotEmpty()) {
                            AlertListView(
                                settings = settings,
                                onToggle = onToggle,
                                onDelete = onDelete,
                                onEdit = onEdit
                            )
                        } else {
                            // Empty state view (iOS와 동일: 아이콘 + 텍스트)
                            EmptyStateView()
                        }

                        // 추가 버튼 또는 최대 개수 안내
                        AddAlertButton(
                            canAddMore = canAddMore,
                            remainingCount = remainingCount,
                            hasPermission = hasPermission,
                            isPermissionDenied = isPermissionDenied,
                            onAdd = onAdd,
                            onRequestPermissionForAdd = onRequestPermissionForAdd,
                            onOpenSettings = onOpenSettings
                        )
                    }
                }

            }
        }
    }
}

// MARK: - Empty State View

@Composable
private fun EmptyStateView() {
    val metrics = LocalRateLayoutMetrics.current
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

// MARK: - Permission Prompt View

@Composable
private fun PermissionPromptView(
    isPermissionDenied: Boolean,
    onRequestPermissionForAdd: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val metrics = LocalRateLayoutMetrics.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.horizontalPadding)
            .padding(top = metrics.sectionPadding, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_bell_badge),
            contentDescription = null,
            tint = StatusConnecting,
            modifier = Modifier
                .size(32.dp)
                .padding(top = 8.dp)
        )

        Text(
            text = "환율 알림을 받으려면\n알림 권한이 필요합니다",
            color = PrimaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Text(
            text = "원하는 환율에 도달하면 푸시 알림으로 알려드립니다",
            color = SecondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        // Permission button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Primary)
                .clickable {
                    if (isPermissionDenied) onOpenSettings() else onRequestPermissionForAdd()
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = Background,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isPermissionDenied) "설정에서 알림 켜기" else "알림 권한 설정하기",
                color = Background,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// MARK: - Permission Warning Banner

@Composable
private fun PermissionWarningBanner(
    isPermissionDenied: Boolean,
    onRequestPermissionForBanner: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val metrics = LocalRateLayoutMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.horizontalPadding)
            .padding(top = metrics.sectionPadding)
            .clip(RoundedCornerShape(8.dp))
            .background(StatusConnecting.copy(alpha = 0.15f))
            .clickable {
                if (isPermissionDenied) onOpenSettings() else onRequestPermissionForBanner()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = StatusConnecting,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))

        // 2 lines (iOS와 동일)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "알림을 받을 수 없습니다",
                color = PrimaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "알림 권한을 설정해주세요",
                color = SecondaryText,
                fontSize = 12.sp
            )
        }

        // Chevron (iOS와 동일)
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = SecondaryText.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
    }
}

// MARK: - Alert List View

@Composable
private fun AlertListView(
    settings: List<AlertSetting>,
    onToggle: (AlertSetting) -> Unit,
    onDelete: (AlertSetting) -> Unit,
    onEdit: (AlertSetting) -> Unit
) {
    val metrics = LocalRateLayoutMetrics.current
    Column(
        modifier = Modifier.padding(top = 8.dp)
    ) {
        settings.forEachIndexed { index, setting ->
            AlertRow(
                setting = setting,
                onToggle = { onToggle(setting) },
                onDelete = { onDelete(setting) },
                onTap = { onEdit(setting) }
            )

            // Divider between items (iOS와 동일)
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

// MARK: - Add Alert Button

@Composable
private fun AddAlertButton(
    canAddMore: Boolean,
    remainingCount: Int,
    hasPermission: Boolean,
    isPermissionDenied: Boolean,
    onAdd: () -> Unit,
    onRequestPermissionForAdd: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val metrics = LocalRateLayoutMetrics.current
    if (canAddMore) {
        // Add button (iOS와 동일: 아웃라인 스타일)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.horizontalPadding)
                .padding(top = metrics.sectionPadding, bottom = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(InputBackground)
                .clickable {
                    if (!hasPermission) {
                        if (isPermissionDenied) onOpenSettings() else onRequestPermissionForAdd()
                    } else {
                        onAdd()
                    }
                }
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
                text = "알림 추가",
                color = Primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            // 남은 개수 표시 (3개 이하일 때)
            if (remainingCount <= 3) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "(${remainingCount}개 남음)",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        }
    } else {
        // 최대 개수 도달 안내 (iOS와 동일: 아이콘 추가)
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
                text = "최대 ${AlertViewModel.MAX_COUNT}개까지 설정할 수 있습니다",
                color = SecondaryText,
                fontSize = 12.sp
            )
        }
    }
}
