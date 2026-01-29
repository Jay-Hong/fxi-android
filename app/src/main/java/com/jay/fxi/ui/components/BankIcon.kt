package com.jay.fxi.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.ui.theme.InputBackground
import com.jay.fxi.ui.theme.SecondaryText

/**
 * 은행 아이콘 뷰 (iOS BankIconView와 동일)
 *
 * @param bank 표시할 은행
 * @param isSelected 선택 상태 (비선택 시 grayscale + opacity 적용)
 * @param useHighRes true면 200x200 고해상도 아이콘 사용 (알림 추가/수정용)
 * @param modifier Modifier
 */
@Composable
fun BankIcon(
    bank: Bank,
    isSelected: Boolean = true,
    useHighRes: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Grayscale ColorMatrix (iOS grayscale 0.9와 유사)
    val grayscaleMatrix = ColorMatrix().apply {
        setToSaturation(0f)  // 완전 grayscale
    }

    // 선택 상태에 따른 ColorFilter와 alpha
    val colorFilter = if (isSelected) null else ColorFilter.colorMatrix(grayscaleMatrix)
    val alpha = if (isSelected) 1f else 0.5f

    // iOS와 동일: useHighRes에 따라 다른 이미지 사용
    val iconRes = if (useHighRes) bank.iconResLarge else bank.iconRes

    Image(
        painter = painterResource(id = iconRes),
        contentDescription = bank.displayName,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp)),
        contentScale = ContentScale.Fit,
        alpha = alpha,
        colorFilter = colorFilter
    )
}

/**
 * 은행 아이콘 폴백 뷰 (이미지 로드 실패 시 사용)
 */
@Composable
fun BankIconFallback(
    bank: Bank?,
    modifier: Modifier = Modifier
) {
    val bankColor = bank?.let { Color(it.colorHex) } ?: SecondaryText

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bank?.let { bankColor.copy(alpha = 0.2f) } ?: InputBackground),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = bank?.let { getBankInitial(it) } ?: "?",
            color = bankColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun getBankInitial(bank: Bank): String = when (bank) {
    Bank.INVESTING -> "INV"
    Bank.KB -> "KB"
    Bank.HANA -> "하나"
    Bank.SHINHAN -> "신한"
    Bank.WOORI -> "우리"
    Bank.IBK -> "IBK"
    Bank.NH -> "농협"
    Bank.SC -> "SC"
    Bank.BS -> "부산"
    Bank.CITI -> "CITI"
}
