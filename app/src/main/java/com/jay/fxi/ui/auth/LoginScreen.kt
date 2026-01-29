package com.jay.fxi.ui.auth

import android.app.Activity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import com.jay.fxi.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.ui.theme.Background
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText
import com.jay.fxi.ui.theme.StatusError
import kotlin.math.sin

private data class LoginParticle(
    val symbol: String,
    val xFraction: Float,
    val yFraction: Float,
    val size: Int,
    val baseOpacity: Float,
    val rotation: Float,
    val phaseOffset: Float
)

private val ParticleColor = Color(0xFFF5A623)

private val particles = listOf(
    // Row 1 (0-10%)
    LoginParticle("$", 0.08f, 0.02f, 13, 0.20f, -8f, 0f),
    LoginParticle("¥", 0.28f, 0.05f, 11, 0.18f, 12f, 0.25f),
    LoginParticle("€", 0.52f, 0.03f, 14, 0.22f, -5f, 0.5f),
    LoginParticle("₩", 0.75f, 0.06f, 12, 0.19f, 15f, 0.75f),
    LoginParticle("$", 0.92f, 0.01f, 11, 0.17f, -12f, 0.25f),
    LoginParticle("€", 0.40f, 0.08f, 13, 0.21f, 7f, 0.5f),
    // Row 2 (10-22%)
    LoginParticle("₩", 0.05f, 0.12f, 15, 0.23f, -14f, 0.75f),
    LoginParticle("$", 0.22f, 0.16f, 12, 0.19f, 10f, 0f),
    LoginParticle("¥", 0.38f, 0.13f, 14, 0.22f, -3f, 0.25f),
    LoginParticle("€", 0.55f, 0.18f, 11, 0.18f, 16f, 0.5f),
    LoginParticle("₩", 0.72f, 0.14f, 13, 0.20f, -9f, 0.75f),
    LoginParticle("$", 0.88f, 0.19f, 16, 0.24f, 5f, 0f),
    LoginParticle("¥", 0.15f, 0.20f, 12, 0.19f, -11f, 0.25f),
    // Row 3 (22-34%)
    LoginParticle("€", 0.03f, 0.25f, 14, 0.21f, 8f, 0.5f),
    LoginParticle("₩", 0.20f, 0.28f, 11, 0.18f, -6f, 0.75f),
    LoginParticle("$", 0.42f, 0.24f, 15, 0.23f, 13f, 0f),
    LoginParticle("¥", 0.60f, 0.30f, 12, 0.19f, -4f, 0.25f),
    LoginParticle("€", 0.78f, 0.26f, 13, 0.20f, 18f, 0.5f),
    LoginParticle("₩", 0.95f, 0.32f, 11, 0.17f, -10f, 0.75f),
    // Row 4 (34-46%)
    LoginParticle("$", 0.10f, 0.36f, 13, 0.20f, 6f, 0f),
    LoginParticle("¥", 0.30f, 0.40f, 16, 0.24f, -13f, 0.25f),
    LoginParticle("€", 0.48f, 0.37f, 12, 0.19f, 9f, 0.5f),
    LoginParticle("₩", 0.65f, 0.42f, 14, 0.22f, -7f, 0.75f),
    LoginParticle("$", 0.82f, 0.38f, 11, 0.18f, 14f, 0f),
    LoginParticle("¥", 0.50f, 0.44f, 13, 0.20f, -2f, 0.25f),
    // Row 5 (46-58%)
    LoginParticle("€", 0.07f, 0.48f, 15, 0.23f, 11f, 0.5f),
    LoginParticle("₩", 0.25f, 0.52f, 12, 0.19f, -8f, 0.75f),
    LoginParticle("$", 0.45f, 0.49f, 14, 0.22f, 4f, 0f),
    LoginParticle("¥", 0.62f, 0.55f, 11, 0.18f, -15f, 0.25f),
    LoginParticle("€", 0.80f, 0.50f, 13, 0.20f, 7f, 0.5f),
    LoginParticle("₩", 0.93f, 0.54f, 16, 0.24f, -3f, 0.75f),
    // Row 6 (58-70%)
    LoginParticle("$", 0.04f, 0.60f, 12, 0.19f, 15f, 0f),
    LoginParticle("¥", 0.18f, 0.64f, 14, 0.22f, -10f, 0.25f),
    LoginParticle("€", 0.35f, 0.61f, 11, 0.18f, 6f, 0.5f),
    LoginParticle("₩", 0.55f, 0.67f, 13, 0.20f, -14f, 0.75f),
    LoginParticle("$", 0.73f, 0.62f, 15, 0.23f, 3f, 0f),
    LoginParticle("¥", 0.90f, 0.66f, 12, 0.19f, -9f, 0.25f),
    // Row 7 (70-82%)
    LoginParticle("€", 0.12f, 0.72f, 14, 0.22f, 12f, 0.5f),
    LoginParticle("₩", 0.28f, 0.76f, 11, 0.18f, -5f, 0.75f),
    LoginParticle("$", 0.46f, 0.73f, 16, 0.24f, 8f, 0f),
    LoginParticle("¥", 0.63f, 0.78f, 13, 0.20f, -11f, 0.25f),
    LoginParticle("€", 0.82f, 0.74f, 12, 0.19f, 16f, 0.5f),
    LoginParticle("₩", 0.96f, 0.79f, 14, 0.22f, -7f, 0.75f),
    // Row 8 (82-94%)
    LoginParticle("$", 0.06f, 0.84f, 13, 0.20f, 10f, 0f),
    LoginParticle("¥", 0.24f, 0.88f, 15, 0.23f, -13f, 0.25f),
    LoginParticle("€", 0.40f, 0.85f, 11, 0.18f, 5f, 0.5f),
    LoginParticle("₩", 0.58f, 0.90f, 14, 0.22f, -8f, 0.75f),
    LoginParticle("$", 0.76f, 0.86f, 12, 0.19f, 14f, 0f),
    LoginParticle("¥", 0.91f, 0.92f, 13, 0.20f, -4f, 0.25f),
    // Row 9 (94-100%)
    LoginParticle("€", 0.15f, 0.95f, 12, 0.19f, 9f, 0.5f),
    LoginParticle("₩", 0.42f, 0.97f, 14, 0.22f, -12f, 0.75f),
    LoginParticle("$", 0.68f, 0.96f, 11, 0.18f, 7f, 0f),
    LoginParticle("¥", 0.88f, 0.98f, 13, 0.20f, -6f, 0.25f),
)

@Composable
fun LoginScreen(
    onSignedIn: (() -> Unit)? = null,
    onPreviewClick: (() -> Unit)? = null,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val isLoading by authViewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by authViewModel.errorMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(authState) {
        if (authState is AuthState.SignedIn) {
            onSignedIn?.invoke()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Particle background
        ParticlesBackground()

        // Top gradient fade
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Background, Background.copy(alpha = 0f))
                    )
                )
        )

        // Bottom gradient fade
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Background.copy(alpha = 0f), Background)
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Hero section - centered message
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Radial-like gradient behind text
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(200.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Background.copy(alpha = 0.9f),
                                    Background.copy(alpha = 0.6f),
                                    Color.Transparent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "대한민국 주요은행 환율 알림",
                            color = PrimaryText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                        Text(
                            text = "(인터넷전문은행 제외)",
                            color = SecondaryText.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        if (onPreviewClick != null) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clickable { onPreviewClick() }
                                    .padding(vertical = 4.dp)
                            ) {
                                val previewColor = ParticleColor
                                Text(
                                    text = "예시 화면 먼저보기",
                                    color = previewColor,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.drawBehind {
                                        val strokeWidth = 0.8.dp.toPx()
                                        val y = size.height + 2.dp.toPx()
                                        drawLine(
                                            color = previewColor,
                                            start = Offset(0f, y),
                                            end = Offset(size.width, y),
                                            strokeWidth = strokeWidth
                                        )
                                    }
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = previewColor,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom section: buttons + terms
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Google button
                Button(
                    onClick = {
                        activity?.let { authViewModel.signInWithGoogle(it) }
                    },
                    enabled = !isLoading && activity != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1F1F1F)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_google),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Google로 계속하기", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }

                // Apple button
                OutlinedButton(
                    onClick = {
                        activity?.let { authViewModel.signInWithApple(it) }
                    },
                    enabled = !isLoading && activity != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = CardBackground,
                        contentColor = PrimaryText
                    ),
                    border = BorderStroke(1.dp, SecondaryText.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_apple),
                        contentDescription = null,
                        tint = PrimaryText,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Apple로 계속하기", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }

                if (isLoading) {
                    Spacer(modifier = Modifier.height(4.dp))
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 3.dp
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage ?: "",
                        color = StatusError,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // 개인정보처리방침 동의 문구
                Spacer(modifier = Modifier.height(8.dp))
                val uriHandler = LocalUriHandler.current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "로그인 시 ", color = SecondaryText, fontSize = 12.sp)
                    Text(
                        text = "개인정보처리방침",
                        color = Primary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://fxfxi.blogspot.com/2026/01/blog-post.html")
                        }
                    )
                    Text(text = "에 동의합니다", color = SecondaryText, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ParticlesBackground() {
    val transition = rememberInfiniteTransition(label = "particles")
    val animProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "particleOpacity"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val density = LocalDensity.current
        // iOS: heroHeight = geometry.size.height - 220
        val buttonAreaPx = with(density) { 220.dp.toPx() }
        val heroHeightPx = (heightPx - buttonAreaPx).coerceAtLeast(0f)

        particles.forEach { p ->
            val opacityVariation = sin((animProgress + p.phaseOffset) * 2 * Math.PI).toFloat() * 0.08f
            val alpha = (p.baseOpacity + opacityVariation).coerceIn(0f, 1f)

            val xPx = (p.xFraction * widthPx).toInt()
            val yPx = (p.yFraction * heroHeightPx).toInt()

            Text(
                text = p.symbol,
                color = ParticleColor.copy(alpha = alpha),
                fontSize = p.size.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .offset { IntOffset(xPx, yPx) }
                    .rotate(p.rotation)
            )
        }
    }
}
