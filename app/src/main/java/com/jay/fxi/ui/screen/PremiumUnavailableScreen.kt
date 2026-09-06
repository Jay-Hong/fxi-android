package com.jay.fxi.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

/**
 * What a server-confirmed premium user sees between S2 and S3.
 *
 * `ANDROID_V2_PLAN.md` requires this to be an internal-only shell that does **not** fall back to
 * the legacy surface: falling back would re-open the very data paths S2 closed. The artifact that
 * contains this screen is not shippable, and the D24 arming gate is what enforces that — S3 removes
 * the shell when the topic runtime lands.
 */
@Composable
internal fun PremiumUnavailableScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "실시간 화면 준비 중",
            color = PrimaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "구독이 확인되었습니다. 실시간 데이터 연결은 다음 단계에서 연결됩니다.",
            color = SecondaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
