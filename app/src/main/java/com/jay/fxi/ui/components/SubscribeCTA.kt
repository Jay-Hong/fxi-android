package com.jay.fxi.ui.components

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
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.SecondaryText

internal object SubscribeCTATags {
    const val BUTTON = "subscribe-cta-button"
    const val NOTE = "subscribe-cta-note"
}

/**
 * The free preview's footer (D26, iOS `SubscribeCTA.swift`): instead of saving, a way to the subscription's benefits, and an
 * honest note that saving and sending come with the subscription. It promises no automatic activation. What a tap does —
 * close the preview first, then open the paywall — is the caller's.
 */
@Composable
internal fun SubscribeCTA(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Primary)
                .testTag(SubscribeCTATags.BUTTON)
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("구독 혜택 보기", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "알림 저장과 발송은 구독 후 사용할 수 있어요",
            modifier = Modifier.fillMaxWidth().testTag(SubscribeCTATags.NOTE),
            color = SecondaryText,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}
