package com.jay.fxi.ui.free

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

/**
 * The free 테더 tab's alert teaser (S8-7, D26; iOS `FreeSourceAlertPreviewSection.swift`): a heading marked 미리보기, and once
 * opened only a line about the subscription and one button into the preview. No alert list, no history.
 *
 * Whether it is open belongs to the page that shows it ([expanded], [onToggle]): kept while that page stays composed, and
 * collapsed whenever the page is composed afresh.
 */
@Composable
internal fun FreeSourceAlertPreviewSection(expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .testTag(FreeSourcePreviewTags.SECTION)
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .testTag(FreeSourcePreviewTags.TOGGLE)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("테더 알림", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                "미리보기",
                modifier = Modifier.clip(RoundedCornerShape(50)).background(InputBackground).padding(horizontal = 6.dp, vertical = 2.dp),
                color = SecondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = SecondaryText
            )
        }
        if (expanded) {
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp), color = SecondaryText.copy(alpha = 0.2f))
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = SecondaryText.copy(alpha = 0.6f))
                Text("구독하면 거래소별 목표 시세 알림을 받을 수 있어요", color = SecondaryText, fontSize = 12.sp)
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(InputBackground)
                    .testTag(FreeSourcePreviewTags.OPEN)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AddCircle, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("알림 추가 (미리보기)", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
