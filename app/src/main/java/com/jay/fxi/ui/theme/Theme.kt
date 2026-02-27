package com.jay.fxi.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

/**
 * FXi 다크 테마 전용 색상 스킴
 * iOS 앱과 동일하게 다크 모드만 지원
 */
private val FXiDarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = Background,
    onBackground = PrimaryText,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Scrim
)

/**
 * FXi 앱 테마
 * @param darkTheme 다크 테마 여부 (기본값: 항상 다크)
 * @param content 테마가 적용될 컨텐츠
 */
@Composable
fun FXiTheme(
    darkTheme: Boolean = true, // 항상 다크 테마
    content: @Composable () -> Unit
) {
    val colorScheme = FXiDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // statusBarColor/navigationBarColor는 API 35에서 deprecated되었으나
            // edge-to-edge가 필요 없는 다크 테마 앱에서는 여전히 유효함
            // 향후 edge-to-edge 구현 시 enableEdgeToEdge() + SystemBarStyle로 대체 가능
            @Suppress("DEPRECATION")
            window.statusBarColor = Background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Background.toArgb()

            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    // 시스템 글꼴 크기가 과도하게 커도 레이아웃이 깨지지 않도록 fontScale 상한 적용
    val density = LocalDensity.current
    val cappedDensity = Density(
        density = density.density,
        fontScale = density.fontScale.coerceAtMost(1.1f)
    )

    CompositionLocalProvider(LocalDensity provides cappedDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
