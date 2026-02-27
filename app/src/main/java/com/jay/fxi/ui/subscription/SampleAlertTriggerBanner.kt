package com.jay.fxi.ui.subscription

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.R

/**
 * 알림 트리거 시뮬레이션 배너 (iOS 푸시 알림 스타일)
 * iOS SampleAlertTriggerBanner 동일 레이아웃
 */
@Composable
fun SampleAlertTriggerBanner(
    visible: Boolean,
    alert: SampleAlertSetting?,
    currentRate: Double?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && alert != null && currentRate != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        if (alert != null && currentRate != null) {
            BannerContent(
                alert = alert,
                currentRate = currentRate,
                onDismiss = onDismiss
            )
        }
    }
}

private val BannerBackground = Color(0xFF363638)
private val BannerBorder = Color.White.copy(alpha = 0.15f)

@Composable
private fun BannerContent(
    alert: SampleAlertSetting,
    currentRate: Double,
    onDismiss: () -> Unit
) {
    val formattedCurrentRate = String.format("%.2f", currentRate)
    val title = "${alert.conditionEmoji}  ${alert.bank.displayName}  ${alert.currencyName}"
    val body = "[ ${alert.formattedThreshold} ${alert.conditionArrow}${alert.condition.displayText} 도달 ]  $formattedCurrentRate"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .shadow(elevation = 20.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(BannerBackground)
            .border(1.dp, BannerBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // 앱 아이콘 (iOS 40×40, cornerRadius 9 파리티)
        Image(
            painter = painterResource(id = R.drawable.ic_splash_icon),
            contentDescription = "FXi",
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White)
        )

        Spacer(modifier = Modifier.width(10.dp))

        // 알림 내용
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
            Text(
                text = body,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // 닫기 버튼 (iOS 24pt 파리티, IconButton 대신 직접 clickable → 48dp 최소 사이즈 회피)
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "닫기",
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier
                .size(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
    }
}
