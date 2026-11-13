package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.BankAlertDraft
import com.jay.fxi.data.alerts.BankAlertEdit
import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.AlertSetting
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.SupportedCurrency
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

internal sealed interface BankAlertEditorMode {
    data object Add : BankAlertEditorMode
    data class Edit(val setting: AlertSetting) : BankAlertEditorMode
}

internal data class BankAlertEditorInput(
    val bank: Bank?,
    val condition: AlertCondition,
    val thresholdText: String,
    val repeat: RepeatIntervalChange
)

internal data class BankAlertEditorCheck(
    val threshold: Double?,
    val quote: Double?,
    val range: ClosedFloatingPointRange<Double>?,
    val outOfRange: Boolean,
    val duplicate: Boolean,
    val changed: Boolean,
    val validSelection: Boolean,
    val canSave: Boolean
)

internal sealed interface BankAlertSubmit {
    data class Add(val draft: BankAlertDraft) : BankAlertSubmit
    data class Edit(val id: Int, val edit: BankAlertEdit) : BankAlertSubmit
}

internal data class BankAlertRepeatTexts(
    val label: String,
    val description: String,
    val banner: String,
    val repeats: Boolean
)

internal enum class BankAlertThresholdTone { NONE, SAME, ABOVE, BELOW }

internal object BankAlertEditor {
    const val SAME_THRESHOLD = 0.005

    val BANK_CHOICES: List<Bank> = listOf(
        Bank.INVESTING, Bank.KB, Bank.HANA, Bank.SHINHAN, Bank.WOORI,
        Bank.IBK, Bank.NH, Bank.SC, Bank.BS
    )
    val REPEAT_CHOICES: List<RepeatInterval?> = listOf(null) + RepeatInterval.entries

    fun initial(mode: BankAlertEditorMode, quotes: Map<String, Double>): BankAlertEditorInput =
        when (mode) {
            BankAlertEditorMode.Add -> BankAlertEditorInput(
                bank = Bank.KB,
                condition = AlertCondition.BELOW,
                thresholdText = usableQuote(quotes[Bank.KB.code])?.let(::formatThreshold).orEmpty(),
                repeat = RepeatIntervalChange.Once
            )
            is BankAlertEditorMode.Edit -> BankAlertEditorInput(
                bank = mode.setting.bankType,
                condition = mode.setting.condition,
                thresholdText = formatThreshold(mode.setting.threshold),
                repeat = RepeatIntervalChange.Keep
            )
        }

    fun onBankPicked(
        input: BankAlertEditorInput,
        bank: Bank,
        mode: BankAlertEditorMode,
        quotes: Map<String, Double>
    ): BankAlertEditorInput = input.copy(
        bank = bank,
        thresholdText = if (mode == BankAlertEditorMode.Add) {
            usableQuote(quotes[bank.code])?.let(::formatThreshold) ?: input.thresholdText
        } else {
            input.thresholdText
        }
    )

    fun sanitize(raw: String): String {
        val whole = StringBuilder()
        val fraction = StringBuilder()
        var dot = false
        for (char in raw) {
            when {
                char == '.' -> dot = true
                char in '0'..'9' -> {
                    if (dot) {
                        if (fraction.length < 2) fraction.append(char)
                    } else if (whole.length < 4) {
                        whole.append(char)
                    }
                }
            }
        }
        return if (dot) "$whole.$fraction" else whole.toString()
    }

    fun range(quote: Double?): ClosedFloatingPointRange<Double>? {
        val q = usableQuote(quote) ?: return null
        val lower = floor(q * 0.5 / 10.0 + 0.5) * 10.0
        val upper = floor(q * 1.5 / 10.0 + 0.5) * 10.0
        return if (lower.isFinite() && upper.isFinite()) lower..upper else null
    }

    fun check(
        mode: BankAlertEditorMode,
        input: BankAlertEditorInput,
        currency: SupportedCurrency,
        quotes: Map<String, Double>,
        existing: List<AlertSetting>
    ): BankAlertEditorCheck {
        val threshold = parseThreshold(input.thresholdText)
        val bankRaw = effectiveBank(mode, input)
        val quote = usableQuote(bankRaw?.let { quotes[it] })
        val range = range(quote)
        val outOfRange = threshold != null && range != null && threshold !in range
        val duplicate = threshold != null && bankRaw != null && existing.any { row ->
            row.currency == currency.code &&
                row.bank == bankRaw &&
                row.condition == input.condition &&
                (mode !is BankAlertEditorMode.Edit || row.id != mode.setting.id) &&
                abs(row.threshold - threshold) < SAME_THRESHOLD
        }
        val changed = when (mode) {
            BankAlertEditorMode.Add -> true
            is BankAlertEditorMode.Edit -> {
                val original = mode.setting
                bankRaw != original.bank ||
                    input.condition != original.condition ||
                    (threshold != null && abs(threshold - original.threshold) >= SAME_THRESHOLD) ||
                    !original.isEnabled ||
                    repeatSeconds(mode, input.repeat) != original.repeatIntervalSec
            }
        }
        val validSelection = validSelection(mode, input, currency)
        return BankAlertEditorCheck(
            threshold = threshold,
            quote = quote,
            range = range,
            outOfRange = outOfRange,
            duplicate = duplicate,
            changed = changed,
            validSelection = validSelection,
            canSave = threshold != null && validSelection && !outOfRange && !duplicate && changed
        )
    }

    fun submit(
        mode: BankAlertEditorMode,
        input: BankAlertEditorInput,
        currency: SupportedCurrency
    ): BankAlertSubmit {
        require(validSelection(mode, input, currency))
        val threshold = requireNotNull(parseThreshold(input.thresholdText))
        return when (mode) {
            BankAlertEditorMode.Add -> BankAlertSubmit.Add(
                BankAlertDraft(
                    currency = currency.code,
                    bank = requireNotNull(input.bank).code,
                    condition = input.condition,
                    threshold = threshold,
                    isEnabled = true,
                    repeat = when (val repeat = input.repeat) {
                        RepeatIntervalChange.Once -> null
                        is RepeatIntervalChange.Every -> repeat.interval
                        RepeatIntervalChange.Keep -> error("Invalid repeat choice")
                    }
                )
            )
            is BankAlertEditorMode.Edit -> {
                val original = mode.setting
                BankAlertSubmit.Edit(
                    id = original.id,
                    edit = BankAlertEdit(
                        bank = input.bank?.code?.takeIf { it != original.bank },
                        condition = input.condition.takeIf { it != original.condition },
                        threshold = threshold.takeIf { abs(it - original.threshold) >= SAME_THRESHOLD },
                        isEnabled = if (original.isEnabled) null else true,
                        repeat = if (repeatSeconds(mode, input.repeat) == original.repeatIntervalSec) {
                            RepeatIntervalChange.Keep
                        } else {
                            input.repeat
                        }
                    )
                )
            }
        }
    }

    fun repeatTexts(mode: BankAlertEditorMode, repeat: RepeatIntervalChange): BankAlertRepeatTexts {
        val seconds = repeatSeconds(mode, repeat)
        if (seconds == null) {
            return BankAlertRepeatTexts(
                "한번만", "조건 충족 시 1회만 발송", "알림은 1회 발송 후 자동으로 꺼집니다", false
            )
        }
        val interval = RepeatInterval.from(seconds)
        return if (interval == null) {
            BankAlertRepeatTexts(
                "반복 알림", "조건 충족 동안 반복 발송", "조건이 충족되는 동안 반복 발송됩니다", true
            )
        } else {
            BankAlertRepeatTexts(
                interval.label,
                "조건 충족 동안 ${interval.everyLabel} 발송",
                "조건이 충족되는 동안 ${interval.everyLabel} 반복 발송됩니다",
                true
            )
        }
    }

    fun tone(check: BankAlertEditorCheck): BankAlertThresholdTone {
        val threshold = check.threshold ?: return BankAlertThresholdTone.NONE
        val quote = check.quote ?: return BankAlertThresholdTone.NONE
        return when {
            abs(threshold - quote) < SAME_THRESHOLD -> BankAlertThresholdTone.SAME
            threshold > quote -> BankAlertThresholdTone.ABOVE
            else -> BankAlertThresholdTone.BELOW
        }
    }

    fun formatThreshold(value: Double): String =
        NumberFormat.getNumberInstance(Locale.KOREA).apply {
            isGroupingUsed = false
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(value)

    private fun parseThreshold(text: String): Double? {
        if (text.isEmpty() || text.any { it !in '0'..'9' && it != '.' } || text.count { it == '.' } > 1) {
            return null
        }
        return text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    }

    private fun usableQuote(quote: Double?): Double? = quote?.takeIf { it.isFinite() && it > 0 }

    private fun effectiveBank(mode: BankAlertEditorMode, input: BankAlertEditorInput): String? =
        input.bank?.code ?: (mode as? BankAlertEditorMode.Edit)?.setting?.bank

    private fun repeatSeconds(mode: BankAlertEditorMode, repeat: RepeatIntervalChange): Int? =
        when (repeat) {
            RepeatIntervalChange.Keep -> (mode as? BankAlertEditorMode.Edit)?.setting?.repeatIntervalSec
            RepeatIntervalChange.Once -> null
            is RepeatIntervalChange.Every -> repeat.interval.seconds
        }

    private fun validSelection(
        mode: BankAlertEditorMode,
        input: BankAlertEditorInput,
        currency: SupportedCurrency
    ): Boolean = when (mode) {
        BankAlertEditorMode.Add -> input.bank in BANK_CHOICES && input.repeat != RepeatIntervalChange.Keep
        is BankAlertEditorMode.Edit -> currency.code == mode.setting.currency && (
            input.bank in BANK_CHOICES ||
                (input.bank == null && mode.setting.bankType == null) ||
                (input.bank == Bank.CITI && mode.setting.bank == Bank.CITI.code)
            )
    }
}
