package com.jay.fxi.domain.model

import com.jay.fxi.util.InstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Locale

/**
 * 환율 데이터
 */
@Serializable
data class ExchangeRate(
    val currency: String,      // "usd-krw", "jpy-krw", "eur-krw"
    val bank: String,          // "investing", "kb", "hana", ...
    val rate: Double,          // 1407.50
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant
) {
    val id: String get() = "$currency-$bank"

    val bankType: Bank? get() = Bank.fromCode(bank)
    val currencyType: SupportedCurrency? get() = SupportedCurrency.fromCode(currency)
    val isJPY: Boolean get() = currency == SupportedCurrency.JPY_KRW.code

    val formattedRate: String get() = formatRate(rate, currency)

    /**
     * 기준 환율 대비 차이 계산
     */
    fun difference(reference: ExchangeRate?): Double? {
        if (reference == null) return null
        return rate - reference.rate
    }

    /**
     * 기준 환율 대비 차이 포맷팅
     */
    fun formattedDifference(reference: ExchangeRate?): String? {
        val diff = difference(reference) ?: return null
        val formatted = createNumberFormatter().format(diff)
        val prefix = if (diff > 0) "+" else ""
        return "$prefix$formatted"
    }

    companion object {
        /**
         * Thread-safe NumberFormat 생성
         * NumberFormat은 thread-safe하지 않으므로 매 호출 시 새로 생성
         */
        private fun createNumberFormatter(): NumberFormat =
            NumberFormat.getNumberInstance(Locale.KOREA).apply {
                // iOS와 표기 통일: 천단위 구분자(,) 제거
                isGroupingUsed = false
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }

        fun formatRate(rate: Double, currency: String): String {
            val formatted = createNumberFormatter().format(rate)
            return if (currency == SupportedCurrency.JPY_KRW.code) {
                "${formatted}원 (100엔)"
            } else {
                "${formatted}원"
            }
        }
    }
}

/**
 * ExchangeRate 리스트 확장 함수
 */
fun List<ExchangeRate>.filterByCurrency(currency: String): List<ExchangeRate> =
    filter { it.currency == currency }

fun List<ExchangeRate>.filterByCurrency(currency: SupportedCurrency): List<ExchangeRate> =
    filter { it.currency == currency.code }

fun List<ExchangeRate>.referenceRate(currency: String): ExchangeRate? =
    find { it.currency == currency && it.bank == Bank.INVESTING.code }

fun List<ExchangeRate>.rateRange(currency: String): Pair<Double, Double>? {
    val filtered = filterByCurrency(currency)
    if (filtered.isEmpty()) return null
    return filtered.minOf { it.rate } to filtered.maxOf { it.rate }
}

fun List<ExchangeRate>.sortedByBank(): List<ExchangeRate> =
    sortedBy { rate -> Bank.sortedEntries.indexOfFirst { it.code == rate.bank } }
