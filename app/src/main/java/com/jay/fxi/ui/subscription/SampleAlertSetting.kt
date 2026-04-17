package com.jay.fxi.ui.subscription

import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.SupportedCurrency
import com.jay.fxi.ui.alert.symbol
import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * 체험용 알림 설정 (로컬 메모리 전용, 서버 미연동)
 * iOS SampleAlertSetting 동일 구조
 */
data class SampleAlertSetting(
    val id: String = UUID.randomUUID().toString(),
    val bank: Bank,
    val currency: SupportedCurrency = SupportedCurrency.USD_KRW,
    val condition: AlertCondition,
    val threshold: Double,
    val isEnabled: Boolean = true,
    val triggered: Boolean = false,
    val createdAt: Instant = Clock.System.now()
) {
    /** 소수점 2자리, 천단위 구분자 없음 (iOS formattedThreshold 동일) */
    val formattedThreshold: String
        get() = String.format("%.2f", threshold)

    /** "1435.55원 이하" 형태 */
    val descriptionText: String
        get() = "${formattedThreshold}원 ${condition.displayText}"

    /** 조건 심볼 */
    val conditionSymbol: String
        get() = condition.symbol

    /** 통화 한글명 */
    val currencyName: String
        get() = when (currency) {
            SupportedCurrency.USD_KRW -> "달러"
            SupportedCurrency.JPY_KRW -> "엔화"
            SupportedCurrency.EUR_KRW -> "유로"
        }

    /** 조건 아이콘 (트리거 배너용) */
    val conditionEmoji: String
        get() = when (condition) {
            AlertCondition.ABOVE -> "📈"
            AlertCondition.BELOW -> "📉"
        }

    /** 조건 화살표 (트리거 배너용) */
    val conditionArrow: String
        get() = when (condition) {
            AlertCondition.ABOVE -> "↑"
            AlertCondition.BELOW -> "↓"
        }
}

/** 최대 알림 개수 */
object SampleAlertConfig {
    const val MAX_COUNT = 30
    const val SHOW_REMAINING_THRESHOLD = 3
}
