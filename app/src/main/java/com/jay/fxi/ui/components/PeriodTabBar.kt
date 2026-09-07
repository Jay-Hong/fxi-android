package com.jay.fxi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

private val TabBarBackground = Color(0xFF1A1A1A)

@Composable
fun PeriodTabBar(
    activePeriod: GraphPeriod,
    onSelectPeriod: (GraphPeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(TabBarBackground)
            // Without this the four items announce their own selected state but not that they are
            // one choice: a screen reader says "selected" without saying "of what".
            .selectableGroup()
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GraphPeriod.entries.forEach { period ->
            val isActive = period == activePeriod
            val bgColor by animateColorAsState(
                targetValue = if (isActive) InputBackground else Color.Transparent,
                animationSpec = tween(durationMillis = 200),
                label = "tabBg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isActive) PrimaryText else SecondaryText,
                animationSpec = tween(durationMillis = 200),
                label = "tabText"
            )
            Text(
                text = period.displayName,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(bgColor)
                    // `selectable` rather than `clickable`: it is what tells a screen reader which
                    // period is the chosen one. Disabling the active tab conveys "unavailable"
                    // instead, which is the opposite of what is true. The guard keeps the original
                    // behaviour of not re-asking for a period that is already showing.
                    .selectable(selected = isActive, role = Role.Tab) {
                        if (!isActive) onSelectPeriod(period)
                    }
                    .padding(vertical = 2.dp),
            )
        }
    }
}
