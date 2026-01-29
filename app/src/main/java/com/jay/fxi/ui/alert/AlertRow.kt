package com.jay.fxi.ui.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import com.jay.fxi.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.ui.theme.NegativeColor
import com.jay.fxi.ui.theme.PositiveColor
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusConnecting
import com.jay.fxi.ui.theme.StatusError

@Composable
fun AlertRow(
    setting: AlertSetting,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onTap: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val contentAlpha = if (setting.isEnabled) 1f else 0.5f
    val conditionColor = when (setting.condition) {
        AlertCondition.ABOVE -> PositiveColor
        AlertCondition.BELOW -> NegativeColor
    }
    val conditionSymbol = when (setting.condition) {
        AlertCondition.ABOVE -> "△"
        AlertCondition.BELOW -> "▽"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bank name + triggered badge
        Column(
            modifier = Modifier
                .width(90.dp)
                .alpha(contentAlpha)
        ) {
            Text(
                text = setting.bankType?.displayName ?: setting.bank,
                color = PrimaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (setting.triggered) {
                Text(
                    text = "알림 발송됨",
                    color = StatusConnecting,
                    fontSize = 11.sp,
                    modifier = Modifier.offset(y = (-7).dp)
                )
            }
        }

        // Condition text
        Row(
            modifier = Modifier
                .weight(1f)
                .alpha(contentAlpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = setting.formattedThreshold,
                color = PrimaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = conditionSymbol,
                color = conditionColor,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = setting.condition.displayText,
                color = conditionColor,
                fontSize = 14.sp
            )
        }

        // 흰색 thumb + 일관된 크기 토글 (0.75 스케일)
        Switch(
            checked = setting.isEnabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.scale(0.75f),
            thumbContent = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                checkedTrackColor = Primary,
                uncheckedTrackColor = SecondaryText.copy(alpha = 0.3f),
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Delete (iOS SF Symbol trash와 동일)
        IconButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_trash),
                contentDescription = "삭제",
                tint = StatusError.copy(alpha = 0.8f),
                modifier = Modifier.size(21.dp)
            )
        }
    }

    // 삭제 확인 다이얼로그
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("알림 삭제") },
            text = {
                Text("'${setting.bankType?.displayName ?: setting.bank}' 알림을 삭제하시겠습니까?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("삭제", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}
