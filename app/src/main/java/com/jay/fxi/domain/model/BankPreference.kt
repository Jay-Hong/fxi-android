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
    val scale: ScalePolicy
) {
    /** 바 스케일 분모로 사용 (robust 활성 시 clip된 bulk, 비활성 시 raw와 동일). */
    val range: Pair<Double, Double>?
        get() = scale.displayRange
}

fun List<ExchangeRate>.displayState(
    currency: SupportedCurrency,
    config: BankDisplayConfig
): RatesDisplayState {
    val ratesByBank = filter { it.currency == currency.code }.associateBy { it.bank }
    val orderedRates = config.order.mapNotNull { bank -> ratesByBank[bank.code] }
    val referenceRate = orderedRates.firstOrNull()
    val scale = ScalePolicyCalculator.compute(orderedRates)

    return RatesDisplayState(
        rates = orderedRates,
        referenceRate = referenceRate,
        scale = scale
    )
}
