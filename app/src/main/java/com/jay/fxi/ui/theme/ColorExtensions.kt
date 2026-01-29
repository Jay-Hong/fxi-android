package com.jay.fxi.ui.theme

import androidx.compose.ui.graphics.Color
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.GraphSource

/**
 * Domain 모델의 colorHex를 Compose Color로 변환하는 확장 함수
 * Domain 레이어의 UI 의존성을 제거하기 위해 UI 레이어에서 변환 담당
 *
 * Note: Bank.iconRes, Bank.iconResLarge는 Bank enum에 직접 정의됨
 */

/**
 * Bank의 브랜드 색상
 */
val Bank.color: Color
    get() = Color(colorHex)

/**
 * GraphSource의 그래프 라인 색상
 */
val GraphSource.color: Color
    get() = Color(colorHex)
