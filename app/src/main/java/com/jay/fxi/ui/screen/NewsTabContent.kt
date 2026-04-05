package com.jay.fxi.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.ui.components.NewsRow
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import kotlinx.coroutines.launch

private const val NEAR_TOP_OFFSET_DP = 120

/**
 * 뉴스 탭 콘텐츠
 *
 * - 수동 새로고침 없음 (polling/foreground refresh/초기 fetch로 자동 갱신)
 * - 새 뉴스 도착 시: 상단 근처면 자동 스크롤, 아니면 "새 뉴스" 배너 표시
 * - 첫 진입/상세 오버레이 열림/탭 비활성 시 자동 동작 보류
 */
@Composable
fun NewsTabContent(
    newsItems: List<NewsItem>,
    isLoading: Boolean,
    error: String?,
    refreshTrigger: Int,
    isPremium: Boolean,
    isTabVisible: Boolean,
    isDetailOpen: Boolean,
    onRetry: () -> Unit,
    onDetailOpen: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val visibleItems = if (isPremium) newsItems
    else newsItems.filter { !it.isPremiumContent }

    val latestItemId = visibleItems.firstOrNull()?.id
    var previousItemId by remember { mutableStateOf<String?>(null) }
    var showNewItemsBanner by remember { mutableStateOf(false) }

    // 새 첫 기사 도착 감지 + 보류 해제 재평가
    // 키에 isTabVisible/isDetailOpen을 포함시켜, 조건이 풀릴 때 다시 평가됨
    LaunchedEffect(latestItemId, isTabVisible, isDetailOpen) {
        val prev = previousItemId
        val current = latestItemId ?: return@LaunchedEffect

        // 첫 바인딩은 스킵
        if (prev == null) {
            previousItemId = current
            return@LaunchedEffect
        }
        if (prev == current) return@LaunchedEffect

        // 상세 오버레이 열렸거나 탭 비활성 → 자동 동작 보류 (previousItemId 갱신 안 함)
        // 조건이 풀리면 이 LaunchedEffect가 재실행되어 다시 평가됨
        if (isDetailOpen || !isTabVisible) return@LaunchedEffect

        // 상단 근처 판정: index == 0 && offset < 120dp
        val nearTopOffsetPx = with(density) { NEAR_TOP_OFFSET_DP.dp.toPx() }.toInt()
        val isNearTop = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset < nearTopOffsetPx

        if (isNearTop) {
            listState.animateScrollToItem(0)
            showNewItemsBanner = false
        } else {
            showNewItemsBanner = true
        }
        previousItemId = current
    }

    // 사용자가 상단 근처로 스크롤하면 배너 자동 숨김 (자동 스크롤과 동일 기준)
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (!showNewItemsBanner) return@LaunchedEffect
        val nearTopOffsetPx = with(density) { NEAR_TOP_OFFSET_DP.dp.toPx() }.toInt()
        if (listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset < nearTopOffsetPx
        ) {
            showNewItemsBanner = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        when {
            isLoading && visibleItems.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = SecondaryText, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("뉴스 불러오는 중...", color = SecondaryText, fontSize = 14.sp)
                }
            }
            error != null && visibleItems.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("뉴스를 불러올 수 없습니다", color = PrimaryText, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = SecondaryText, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onRetry) {
                        Text("다시 시도", color = Primary)
                    }
                }
            }
            visibleItems.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("뉴스가 없습니다", color = PrimaryText, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "환율 관련 뉴스가 수집되면 여기에 표시됩니다",
                        color = SecondaryText,
                        fontSize = 14.sp
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = WindowInsets.navigationBars.asPaddingValues()
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        NewsRow(
                            item = item,
                            refreshTrigger = refreshTrigger,
                            onClick = {
                                val link = item.link ?: return@NewsRow
                                if (item.shouldOpenExternally) {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                    )
                                } else {
                                    onDetailOpen(link)
                                }
                            }
                        )
                        HorizontalDivider(
                            color = SecondaryText.copy(alpha = 0.15f),
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }

                // 새 뉴스 배너 (플로팅 칩)
                if (showNewItemsBanner) {
                    NewItemsBanner(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(0) }
                            showNewItemsBanner = false
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun NewItemsBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(top = 12.dp)
            .background(Primary, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowUpward,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "새 뉴스", color = Color.White, fontSize = 12.sp)
    }
}
