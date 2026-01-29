package com.jay.fxi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.SecondaryText

/**
 * iOS 스타일 토글 스위치
 * Material 3 Switch와 달리 thumb이 트랙을 거의 꽉 채움
 */
@Composable
fun IOSStyleToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f
) {
    val trackWidth = 51.dp * scale
    val trackHeight = 31.dp * scale
    val thumbSize = 27.dp * scale
    val thumbPadding = 2.dp * scale

    val trackColor by animateColorAsState(
        targetValue = if (checked) Primary else SecondaryText.copy(alpha = 0.3f),
        animationSpec = tween(durationMillis = 200),
        label = "trackColor"
    )

    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "thumbOffset"
    )

    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
            .padding(thumbPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        val maxOffset = trackWidth - thumbSize - (thumbPadding * 2)

        Box(
            modifier = Modifier
                .offset(x = maxOffset * thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
