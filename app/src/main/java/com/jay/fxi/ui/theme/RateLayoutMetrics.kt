package com.jay.fxi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 화면 크기에 따른 레이아웃 메트릭스 (iOS LayoutMetrics.swift와 동일 구조)
 *
 * 사용법:
 * ```
 * BoxWithConstraints {
 *     val metrics = RateLayoutMetrics.fromWindow(maxWidth, maxHeight)
 *     RateBarView(metrics = metrics, ...)
 * }
 * ```
 */
@Immutable
data class RateLayoutMetrics(
    // MARK: - Graph
    val graphHeight: Dp,
    val graphVerticalPadding: Dp,

    // MARK: - Rate Bar
    val barHeight: Dp,
    val bankIconSize: Dp,
    val rateValueFontSize: TextUnit,
    val diffFontSize: TextUnit,
    val directionFontSize: TextUnit,
    val timestampFontSize: TextUnit,
    val timestampWidth: Dp,
    val barTimestampSpacing: Dp,
    val minBarWidth: Dp,
    val diffMinWidth: Dp,
    val barInnerStartPadding: Dp,   // 바 내부 왼쪽 패딩 (아이콘 앞)
    val barInnerEndPadding: Dp,     // 바 내부 오른쪽 패딩 (환율값 뒤)
    val barToDiffSpacing: Dp,       // 바와 차잇값 사이 간격

    // MARK: - Section & Typography
    val sectionTitleFontSize: TextUnit,
    val horizontalPadding: Dp,
    val rowSpacing: Dp,
    val sectionSpacing: Dp,
    val sectionPadding: Dp,

    // MARK: - Rates Section
    val ratesHeaderBottomSpacing: Dp,
    val ratesSectionBottomPadding: Dp,

    // MARK: - Alert Section
    val alertBankNameWidth: Dp,
    val alertRowVerticalPadding: Dp,
    val alertConditionSymbolFontSize: TextUnit,
    val alertConditionToggleFontSize: TextUnit
) {
    companion object {
        /** Phone 메트릭스 (< 600dp) - iOS regular/large 기준 */
        val Phone = RateLayoutMetrics(
            // Graph
            graphHeight = 190.dp,
            graphVerticalPadding = 14.dp,

            // Rate Bar
            barHeight = 42.dp,
            bankIconSize = 30.dp,
            rateValueFontSize = 17.sp,
            diffFontSize = 15.sp,
            directionFontSize = 13.sp,
            timestampFontSize = 11.sp,
            timestampWidth = 46.dp,
            barTimestampSpacing = 6.dp,
            minBarWidth = 120.dp,
            diffMinWidth = 55.dp,
            barInnerStartPadding = 6.dp,    // 바 내부 왼쪽 패딩
            barInnerEndPadding = 12.dp,     // 바 내부 오른쪽 패딩 (10→12: 여유 증가)
            barToDiffSpacing = 6.dp,        // 바-차잇값 간격 (1→6: 대폭 증가)

            // Section & Typography
            sectionTitleFontSize = 17.sp,
            horizontalPadding = 12.dp,
            rowSpacing = 14.dp,  // 12 → 14: 바 간격 증가
            sectionSpacing = 12.dp,
            sectionPadding = 12.dp,

            // Rates Section
            ratesHeaderBottomSpacing = 16.dp,
            ratesSectionBottomPadding = 20.dp,

            // Alert Section
            alertBankNameWidth = 80.dp,
            alertRowVerticalPadding = 2.dp,
            alertConditionSymbolFontSize = 11.sp,
            alertConditionToggleFontSize = 14.sp
        )

        /** Tablet 메트릭스 (≥ 600dp) - SM-T505N 10.4" 테블릿 최적화 */
        val Tablet = RateLayoutMetrics(
            // Graph
            graphHeight = 200.dp,
            graphVerticalPadding = 16.dp,  // iOS와 동일하게 유지

            // Rate Bar - 바 높이 증가, 폰트 더 축소
            barHeight = 50.dp,             // 바 높이
            bankIconSize = 34.dp,          // 아이콘 크기
            rateValueFontSize = 16.sp,     // 폰트 축소
            diffFontSize = 14.sp,          // 폰트 축소
            directionFontSize = 12.sp,     // 화살표 전용 (Roboto ▲/▼ 글리프 시각 보정)
            timestampFontSize = 11.sp,     // 폰트 축소
            timestampWidth = 50.dp,        // 폰트 축소에 맞춤
            barTimestampSpacing = 8.dp,    // 간격
            minBarWidth = 160.dp,          // 최소 바 너비
            diffMinWidth = 65.dp,          // 60 → 65: 차잇값 공간 증가
            barInnerStartPadding = 8.dp,   // 바 내부 왼쪽 패딩 (비율 1.33)
            barInnerEndPadding = 16.dp,    // 바 내부 오른쪽 패딩 (비율 1.33)
            barToDiffSpacing = 10.dp,      // 바-차잇값 간격 (비율 1.67)

            // Section & Typography
            sectionTitleFontSize = 16.sp,  // 18 → 16: 폰트 더 축소
            horizontalPadding = 18.dp,     // 20 → 18: 줄임
            rowSpacing = 16.dp,            // 10 → 16: 바 간격 대폭 증가
            sectionSpacing = 14.dp,
            sectionPadding = 12.dp,        // 14 → 12: 줄임

            // Rates Section
            ratesHeaderBottomSpacing = 10.dp,
            ratesSectionBottomPadding = 16.dp,

            // Alert Section
            alertBankNameWidth = 85.dp,    // 90 → 85: 폰트 축소에 맞춤
            alertRowVerticalPadding = 1.dp,
            alertConditionSymbolFontSize = 11.sp,
            alertConditionToggleFontSize = 14.sp
        )

        /** Tablet 기준점 (600dp) */
        private val TABLET_BREAKPOINT = 600.dp

        /**
         * 실제 윈도우 크기 기준으로 메트릭스 선택 (split-screen 대응)
         */
        fun fromWindow(width: Dp, height: Dp): RateLayoutMetrics {
            val base = if (width >= TABLET_BREAKPOINT) Tablet else Phone
            val heightMultiplier = when {
                width >= TABLET_BREAKPOINT -> 1f
                height < 700.dp -> 0.92f
                height < 820.dp -> 1.0f
                else -> 1.1f
            }
            return base.copy(
                graphHeight = (base.graphHeight.value * heightMultiplier).dp
            )
        }

        fun fromWidth(width: Dp): RateLayoutMetrics {
            return fromWindow(width, 844.dp)
        }
    }
}

/**
 * CompositionLocal for RateLayoutMetrics
 * 기본값은 Phone 메트릭스
 */
val LocalRateLayoutMetrics = staticCompositionLocalOf { RateLayoutMetrics.Phone }
