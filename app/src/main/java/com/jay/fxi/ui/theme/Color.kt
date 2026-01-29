package com.jay.fxi.ui.theme

import androidx.compose.ui.graphics.Color
import com.jay.fxi.util.AppColors

/**
 * FXi 앱 색상 정의
 * iOS 앱과 동일한 색상 팔레트 사용
 * AppColors의 Long 상수를 Color로 변환
 */

// Background colors
val Background = Color(AppColors.BACKGROUND)
val CardBackground = Color(AppColors.CARD_BACKGROUND)
val InputBackground = Color(AppColors.INPUT_BACKGROUND)

// Text colors
val PrimaryText = Color(AppColors.PRIMARY_TEXT)
val SecondaryText = Color(AppColors.SECONDARY_TEXT)

// Status colors (exchange rate - Korean financial convention)
val PositiveColor = Color(AppColors.POSITIVE)   // 기준보다 비쌈 (빨강)
val NegativeColor = Color(AppColors.NEGATIVE)   // 기준보다 쌈 (초록)
val NeutralColor = Color(AppColors.NEUTRAL)

// Connection status colors
val StatusOnline = Color(AppColors.STATUS_ONLINE)
val StatusConnecting = Color(AppColors.STATUS_CONNECTING)
val StatusError = Color(AppColors.STATUS_ERROR)

// Reference border
val ReferenceBorder = Color(AppColors.REFERENCE_BORDER)

// Material3 color scheme colors
val Primary = Color(0xFF8B5CF6)       // iOS AccentColor (다크모드)
val OnPrimary = Color(0xFF121212)
val PrimaryContainer = Color(0xFF2C3E50)
val OnPrimaryContainer = Color(0xFFE0E0E0)

val Secondary = Color(0xFFFFB200)     // KB 은행 색상 기반
val OnSecondary = Color(0xFF121212)
val SecondaryContainer = Color(0xFF3D3D3D)
val OnSecondaryContainer = Color(0xFFE0E0E0)

val Tertiary = Color(0xFF00A7A0)      // Hana 은행 색상 기반
val OnTertiary = Color(0xFF121212)
val TertiaryContainer = Color(0xFF003D3D)
val OnTertiaryContainer = Color(0xFFE0E0E0)

val Surface = Color(AppColors.CARD_BACKGROUND)
val OnSurface = Color(AppColors.PRIMARY_TEXT)
val SurfaceVariant = Color(AppColors.INPUT_BACKGROUND)
val OnSurfaceVariant = Color(AppColors.SECONDARY_TEXT)

val Error = Color(AppColors.STATUS_ERROR)
val OnError = Color(0xFF121212)
val ErrorContainer = Color(0xFF93000A)
val OnErrorContainer = Color(0xFFFFDAD6)

val Outline = Color(0xFF404040)
val OutlineVariant = Color(0xFF2C2C2C)
val Scrim = Color(0xFF000000)
