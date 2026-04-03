package com.jay.fxi.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.ui.components.NewsRow
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

/**
 * 뉴스 탭 콘텐츠
 *
 * - 수동 새로고침 없음 (polling/foreground refresh/초기 fetch로 자동 갱신)
 * - 순수 LazyColumn + Android 기본 overscroll stretch
 * - empty/error 상태에서만 "다시 시도" 버튼
 * - external_link 상세는 onDetailOpen 콜백으로 상위에 위임 (전체화면 오버레이)
 * - report_pdf는 여기서 직접 외부 브라우저 오픈
 */
@Composable
fun NewsTabContent(
    newsItems: List<NewsItem>,
    isLoading: Boolean,
    error: String?,
    refreshTrigger: Int,
    isPremium: Boolean,
    onRetry: () -> Unit,
    onDetailOpen: (String) -> Unit
) {
    val context = LocalContext.current

    val visibleItems = if (isPremium) newsItems
    else newsItems.filter { !it.isPremiumContent }

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
            }
        }
    }
}
