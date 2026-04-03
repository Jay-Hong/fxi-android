package com.jay.fxi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.NewsContentType
import com.jay.fxi.domain.model.NewsItem
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

/**
 * 뉴스 리스트 셀 (iOS NewsRowView 패리티)
 *
 * iOS: .frame(height: 64) + .padding(.vertical, 14) → 콘텐츠 64pt, 총 92pt
 *
 * Android: defaultMinSize(minHeight=64dp)로 최소 높이 보장
 * + padding(vertical=14dp)이 바깥에 적용 → 총 최소 92dp
 * 1줄/2줄 제목 모두 동일 최소 높이 유지, 시간 라벨 잘림 방지
 */
@Composable
fun NewsRow(
    item: NewsItem,
    refreshTrigger: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isTappable) { onClick() }
            .padding(vertical = 14.dp, horizontal = 20.dp)
            .defaultMinSize(minHeight = 64.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = item.decodedTitle,
                color = PrimaryText,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.timeAgo(refreshTrigger),
                color = SecondaryText,
                fontSize = 12.sp
            )
        }

        if (item.isTappable) {
            Spacer(modifier = Modifier.widthIn(min = 12.dp))
            if (item.resolvedContentType == NewsContentType.REPORT_PDF) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = "보고서",
                    tint = SecondaryText,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SecondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
