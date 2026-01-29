package com.jay.fxi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.GraphSource
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.color

/**
 * 그래프 소스 토글 버튼 행 (iOS SourceToggleButton과 동일한 스타일)
 */
@Composable
fun SourceToggleRow(
    selectedSources: Set<GraphSource>,
    onToggle: (GraphSource) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GraphSource.entries.forEach { source ->
            SourceToggleButton(
                source = source,
                isSelected = source in selectedSources,
                onClick = { onToggle(source) }
            )
        }
    }
}

@Composable
private fun SourceToggleButton(
    source: GraphSource,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val indicatorColor = if (isSelected) source.color else SecondaryText.copy(alpha = 0.3f)
    val backgroundColor = if (isSelected) source.color.copy(alpha = 0.15f) else InputBackground
    val borderColor = if (isSelected) source.color.copy(alpha = 0.5f) else Color.Transparent
    val textColor = if (isSelected) PrimaryText else SecondaryText

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 색상 인디케이터 원
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(indicatorColor)
        )

        // 소스명
        Text(
            text = source.displayName,
            color = textColor,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
        )
    }
}
