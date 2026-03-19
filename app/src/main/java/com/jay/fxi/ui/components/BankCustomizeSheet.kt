package com.jay.fxi.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.data.local.BankPreferenceManager
import com.jay.fxi.domain.model.BankPreferenceItem
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.ReferenceBorder
import com.jay.fxi.ui.theme.SecondaryText
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankCustomizeSheet(
    orderedBanks: List<BankPreferenceItem>,
    onApply: (List<BankPreferenceItem>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hapticFeedback = LocalHapticFeedback.current

    var draftItems by remember { mutableStateOf(orderedBanks) }

    LaunchedEffect(orderedBanks) {
        draftItems = orderedBanks
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        draftItems = draftItems.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Background,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(SecondaryText.copy(alpha = 0.4f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
        ) {
            // Header: 취소 / 제목 / 완료
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("취소", color = SecondaryText)
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "은행 순서 설정",
                    color = PrimaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))

                TextButton(onClick = { onApply(draftItems) }) {
                    Text("완료", color = Primary, fontWeight = FontWeight.SemiBold)
                }
            }

            // 안내 문구 + ⋮ 메뉴
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = ReferenceBorder,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "가장 위의 은행이 기준 환율이 됩니다",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "더보기",
                            tint = SecondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("기본값으로 초기화") },
                            onClick = {
                                draftItems = BankPreferenceManager.defaultOrder
                                showMenu = false
                            }
                        )
                    }
                }
            }

            // 드래그 리오더 리스트
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(draftItems, key = { it.bankCode }) { item ->
                    ReorderableItem(reorderableState, key = item.bankCode) { isDragging ->
                        val elevation by animateDpAsState(
                            if (isDragging) 8.dp else 0.dp,
                            label = "dragElevation"
                        )
                        val index = draftItems.indexOfFirst { it.bankCode == item.bankCode }
                        val isReference = item.isVisible && draftItems.firstVisibleIndex() == index
                        val canHide = !(item.isVisible && draftItems.visibleCount() <= 1)

                        Surface(
                            shadowElevation = elevation,
                            shape = RoundedCornerShape(12.dp),
                            color = CardBackground
                        ) {
                            BankCustomizeRow(
                                item = item,
                                isReference = isReference,
                                canHide = canHide,
                                onToggleVisibility = {
                                    if (item.isVisible && draftItems.visibleCount() <= 1) return@BankCustomizeRow
                                    draftItems = draftItems.toMutableList().also { list ->
                                        val idx = list.indexOfFirst { it.bankCode == item.bankCode }
                                        if (idx >= 0) {
                                            list[idx] = item.copy(isVisible = !item.isVisible)
                                        }
                                    }
                                },
                                dragHandleModifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            )
                        }
                    }
                }

            }
        }
    }
}

@Composable
private fun BankCustomizeRow(
    item: BankPreferenceItem,
    isReference: Boolean,
    canHide: Boolean,
    onToggleVisibility: () -> Unit,
    dragHandleModifier: Modifier = Modifier
) {
    val bank = item.bank

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isReference) ReferenceBorder.copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 표시/숨김 토글
        IconButton(
            onClick = onToggleVisibility,
            enabled = canHide || !item.isVisible,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (item.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (item.isVisible) "숨기기" else "표시하기",
                tint = when {
                    item.isVisible && canHide -> ReferenceBorder
                    item.isVisible -> SecondaryText.copy(alpha = 0.4f)
                    else -> SecondaryText.copy(alpha = 0.55f)
                },
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // 은행 아이콘
        if (bank != null) {
            BankIcon(
                bank = bank,
                isSelected = item.isVisible,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 은행 이름
        Text(
            text = bank?.displayName ?: item.bankCode,
            color = if (item.isVisible) PrimaryText else SecondaryText.copy(alpha = 0.65f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.weight(1f))

        // 기준 뱃지 (캡슐형, 오른쪽 정렬)
        if (isReference) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ReferenceBorder)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "기준",
                    color = Color.White,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // 드래그 핸들 (3줄)
        IconButton(
            onClick = {},
            modifier = dragHandleModifier.size(40.dp)
        ) {
            ThreeBarDragHandle(
                color = SecondaryText.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ThreeBarDragHandle(
    color: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val barWidth = size.width * 0.75f
        val barHeight = 1.8.dp.toPx()
        val totalHeight = barHeight * 3 + 3.dp.toPx() * 2
        val startY = (size.height - totalHeight) / 2f
        val startX = (size.width - barWidth) / 2f

        for (i in 0..2) {
            val y = startY + i * (barHeight + 3.dp.toPx()) + barHeight / 2f
            drawLine(
                color = color,
                start = Offset(startX, y),
                end = Offset(startX + barWidth, y),
                strokeWidth = barHeight,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun List<BankPreferenceItem>.visibleCount(): Int = count { it.isVisible }

private fun List<BankPreferenceItem>.firstVisibleIndex(): Int? = indexOfFirst { it.isVisible }
    .takeIf { it >= 0 }
