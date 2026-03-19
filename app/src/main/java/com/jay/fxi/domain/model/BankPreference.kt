package com.jay.fxi.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BankPreferenceItem(
    val bankCode: String,
    val isVisible: Boolean
) {
    val bank: Bank?
        get() = Bank.fromCode(bankCode)

    constructor(bank: Bank, isVisible: Boolean) : this(
        bankCode = bank.code,
        isVisible = isVisible
    )
}

data class BankDisplayConfig(
    val order: List<Bank>
)

data class RatesDisplayState(
    val rates: List<ExchangeRate>,
    val referenceRate: ExchangeRate?,
    val range: Pair<Double, Double>?
)

fun List<ExchangeRate>.displayState(
    currency: SupportedCurrency,
    config: BankDisplayConfig
): RatesDisplayState {
    val ratesByBank = filter { it.currency == currency.code }.associateBy { it.bank }
    val orderedRates = config.order.mapNotNull { bank -> ratesByBank[bank.code] }
    val referenceRate = orderedRates.firstOrNull()
    val range = if (orderedRates.isEmpty()) {
        null
    } else {
        orderedRates.minOf { it.rate } to orderedRates.maxOf { it.rate }
    }

    return RatesDisplayState(
        rates = orderedRates,
        referenceRate = referenceRate,
        range = range
    )
}
