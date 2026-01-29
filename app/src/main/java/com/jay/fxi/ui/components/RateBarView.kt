package com.jay.fxi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.NeutralColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.ReferenceBorder
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.color
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * 환율 바 차트 항목 (iOS RateBarView와 동일한 레이아웃)
 *
 * 레이아웃: [---바(아이콘+환율)---][차이값/은행명]...[타임스탬프]
 */
@Composable
fun RateBarView(
    rate: ExchangeRate,
    referenceRate: ExchangeRate?,
    minRate: Double,
    maxRate: Double,
    modifier: Modifier = Modifier
) {
    val bank = rate.bankType ?: return
    val isReference = bank.isReference

    // 바 너비 계산 + 애니메이션
    val targetBarWidth = remember(rate.rate, minRate, maxRate) {
        calculateBarWidth(rate.rate, minRate, maxRate)
    }
    val animatedBarWidth by animateFloatAsState(
        targetValue = targetBarWidth,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "barWidth"
    )

    // 환율 값 애니메이션 (숫자 롤링)
    val animatedRate by animateFloatAsState(
        targetValue = rate.rate.toFloat(),
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "rateValue"
    )

    // 차이 계산 + 애니메이션
    val diff = if (isReference || referenceRate == null) null else rate.rate - referenceRate.rate
    val animatedDiff by animateFloatAsState(
        targetValue = (diff ?: 0.0).toFloat(),
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "diffValue"
    )
    val diffColor by animateColorAsState(
        targetValue = when {
            diff == null -> NeutralColor
            diff > 0.005 -> PositiveColor
            diff < -0.005 -> NegativeColor
            else -> NeutralColor
        },
        animationSpec = tween(durationMillis = 1000),
        label = "diffColor"
    )

    // 방향 인디케이터 + 펄스 상태
    var showDirection by remember { mutableStateOf(false) }
    var directionSymbol by remember { mutableStateOf("▲") }
    var directionColor by remember { mutableStateOf(Color.Transparent) }
    var previousRate by remember { mutableDoubleStateOf(rate.rate) }
    var hasAppeared by remember { mutableStateOf(false) }
    var pulseScale by remember { mutableFloatStateOf(1f) }
    val animatedScale by animateFloatAsState(
        targetValue = pulseScale,
        animationSpec = tween(
            durationMillis = if (pulseScale > 1f) 150 else 850,
            easing = LinearEasing
        ),
        label = "pulse"
    )

    // 값 변경 감지 → 방향 인디케이터 + 펄스
    LaunchedEffect(rate.rate) {
        if (!hasAppeared) {
            hasAppeared = true
            previousRate = rate.rate
            return@LaunchedEffect
        }
        if (abs(rate.rate - previousRate) >= 0.01) {
            val isIncrease = rate.rate > previousRate
            pulseScale = 1.03f
            delay(150)
            pulseScale = 1f
            directionSymbol = if (isIncrease) "▲" else "▼"
            directionColor = if (isIncrease) PositiveColor else NegativeColor
            showDirection = true
            delay(1000)
            showDirection = false
        }
        previousRate = rate.rate
    }

    // 타임스탬프 (KST)
    val (dateStr, timeStr) = remember(rate.timestamp) {
        val kst = TimeZone.of("Asia/Seoul")
        val dt = rate.timestamp.toLocalDateTime(kst)
        "%02d-%02d".format(dt.monthNumber, dt.dayOfMonth) to
            "%02d:%02d".format(dt.hour, dt.minute)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 바 + 차이값 영역
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 바 (아이콘 + 환율 포함)
            Row(
                modifier = Modifier
                    .fillMaxWidth(animatedBarWidth)
                    .height(42.dp)
                    .scale(animatedScale)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bank.color)
                    .then(
                        if (isReference) Modifier.border(2.dp, ReferenceBorder, RoundedCornerShape(6.dp))
                        else Modifier
                    )
                    .padding(start = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 은행 아이콘
                Image(
                    painter = painterResource(id = bank.iconRes),
                    contentDescription = bank.displayName,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                )

                Spacer(modifier = Modifier.weight(1f))

                // 환율 값 (숫자 롤링, 오른쪽 정렬)
                Text(
                    text = formatRateValue(animatedRate.toDouble()),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 10.dp),
                    style = LocalTextStyle.current.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.8f),
                            offset = Offset(0f, 1.5f),
                            blurRadius = 2f
                        )
                    )
                )
            }

            Spacer(modifier = Modifier.width(1.dp))

            // 차이값 섹션
            Box(
                modifier = Modifier.widthIn(min = 55.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showDirection) {
                    Text(
                        text = directionSymbol,
                        color = directionColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else if (isReference) {
                    Text(
                        text = bank.displayName,
                        color = SecondaryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 14.sp
                    )
                } else if (diff != null) {
                    Text(
                        text = formatDifference(animatedDiff.toDouble()),
                        color = diffColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // 타임스탬프 (오른쪽 끝 고정, iOS spacing: 1)
        Column(
            modifier = Modifier.width(46.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = dateStr,
                color = SecondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 13.sp,
                textAlign = TextAlign.End
            )
            Text(
                text = timeStr,
                color = SecondaryText,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * 바 너비 계산 (웹/iOS 버전과 동일한 로직)
 */
private fun calculateBarWidth(rate: Double, minRate: Double, maxRate: Double): Float {
    val rateRange = maxRate - minRate
    if (rateRange == 0.0) return 0.75f

    val normalized = ((rate - minRate) / rateRange).toFloat()

    return when {
        rateRange >= 4 -> 0.48f + normalized * 0.30f   // 48% ~ 78%
        rateRange >= 3 -> 0.50f + normalized * 0.28f   // 50% ~ 78%
        rateRange >= 2 -> 0.53f + normalized * 0.25f   // 53% ~ 78%
        rateRange >= 1 -> 0.57f + normalized * 0.21f   // 57% ~ 78%
        rateRange >= 0.6 -> 0.62f + normalized * 0.15f // 62% ~ 77%
        rateRange > 0.3 -> 0.67f + normalized * 0.10f  // 67% ~ 77%
        else -> 0.72f + normalized * 0.04f              // 72% ~ 76%
    }
}

private fun formatRateValue(rate: Double): String {
    return NumberFormat.getNumberInstance(Locale.KOREA).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(rate)
}

private fun formatDifference(difference: Double): String {
    val sign = if (difference >= 0) "+" else ""
    return "$sign${NumberFormat.getNumberInstance(Locale.KOREA).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(difference)}"
}
