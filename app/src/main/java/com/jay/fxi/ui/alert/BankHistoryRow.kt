package com.jay.fxi.ui.alert

import com.jay.fxi.data.remote.dto.AlertHistoryItem
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.SupportedCurrency
import java.util.Locale
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal data class BankHistoryConditionText(val threshold: String, val symbol: String, val name: String)

internal data class BankHistoryRow(
    val bank: Bank?,
    val bankName: String,
    val currencyLabel: String,
    val condition: BankHistoryConditionText?,
    val rate: String,
    val date: String,
    val time: String
)

internal fun bankHistoryRow(item: AlertHistoryItem): BankHistoryRow {
    val bank = Bank.fromCode(item.bank)
    val condition = item.condition
    val threshold = item.threshold
    val local = item.sentAt.toLocalDateTime(TimeZone.of("Asia/Seoul"))
    return BankHistoryRow(
        bank = bank,
        bankName = bank?.displayName ?: item.bank,
        currencyLabel = SupportedCurrency.fromCode(item.currency)?.tabTitle ?: item.currency.uppercase(Locale.ROOT),
        condition = if (condition != null && threshold != null) {
            BankHistoryConditionText(formatHistoryNumber(threshold), condition.symbol, condition.displayText)
        } else null,
        rate = formatHistoryNumber(item.rate),
        date = String.format(Locale.ROOT, "%02d-%02d", local.monthNumber, local.dayOfMonth),
        time = String.format(Locale.ROOT, "%02d:%02d", local.hour, local.minute)
    )
}

/** Fixed decimal point, exactly two fraction digits, no grouping or currency rescaling. */
private fun formatHistoryNumber(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
