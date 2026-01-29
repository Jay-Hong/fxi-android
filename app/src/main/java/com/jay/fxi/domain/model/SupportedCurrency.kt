package com.jay.fxi.domain.model

/**
 * 지원 통화
 */
enum class SupportedCurrency(
    val code: String,
    val displayName: String,
    val tabTitle: String
) {
    USD_KRW("usd-krw", "USD/KRW", "달러"),
    JPY_KRW("jpy-krw", "JPY/KRW", "엔화"),
    EUR_KRW("eur-krw", "EUR/KRW", "유로");

    val isJPY: Boolean get() = this == JPY_KRW

    companion object {
        fun fromCode(code: String): SupportedCurrency? = entries.find { it.code == code }
    }
}
