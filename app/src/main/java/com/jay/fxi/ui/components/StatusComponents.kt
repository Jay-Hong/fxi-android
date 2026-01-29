package com.jay.fxi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.ConnectionState
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusConnecting
import com.jay.fxi.ui.theme.StatusError
import com.jay.fxi.ui.theme.StatusOnline
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 로딩 화면
 */
@Composable
fun LoadingView(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = Primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "환율 정보 로딩 중...",
                color = SecondaryText,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * 에러 화면
 */
@Composable
fun ErrorView(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "⚠️",
                fontSize = 48.sp
            )
            Text(
                text = message,
                color = PrimaryText,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = Background
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "다시 시도",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 연결 상태 배너
 *
 * iOS StatusBanner/OfflineBanner/ConnectionStatusView와 동일한 디자인:
 * - 상태 dot (8dp 원) + 텍스트 + 마지막 업데이트 시간
 * - 오프라인: wifi.slash 스타일 빨간 배경 + 마지막 업데이트
 * - 실패: 재연결 버튼
 * - Connected: 표시 안 함
 */
@Composable
fun ConnectionStatusBanner(
    connectionState: ConnectionState,
    isOffline: Boolean,
    isLoading: Boolean = false,
    lastUpdated: Instant? = null,
    onReconnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // iOS: Loading 상태에서는 배너 숨김
    val shouldShow = !isLoading && (isOffline ||
            connectionState != ConnectionState.Connected)

    AnimatedVisibility(
        visible = shouldShow,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        if (isOffline) {
            // 오프라인 배너 (iOS OfflineBanner)
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(StatusError.copy(alpha = 0.15f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = StatusError,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "오프라인 모드",
                    color = PrimaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (lastUpdated != null) {
                    Text(
                        text = " · ",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "마지막 업데이트: ${formatTimeOnly(lastUpdated)}",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        } else {
            // 연결 상태 배너 (iOS ConnectionStatusView)
            val dotColor = when (connectionState) {
                is ConnectionState.Connected -> StatusOnline
                is ConnectionState.Connecting, is ConnectionState.Reconnecting -> StatusConnecting
                else -> StatusError
            }

            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(CardBackground)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 상태 dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))

                // 상태 텍스트
                Text(
                    text = connectionState.statusText,
                    color = SecondaryText,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                // 재연결 버튼 (실패 시)
                if (connectionState is ConnectionState.Failed && onReconnect != null) {
                    TextButton(
                        onClick = onReconnect,
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Primary)
                    ) {
                        Text(
                            text = "재연결",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimeOnly(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.of("Asia/Seoul"))
    return String.format("%02d:%02d", dt.hour, dt.minute)
}
