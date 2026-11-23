package com.jay.fxi.ui.alert

import com.jay.fxi.data.remote.dto.RepeatIntervalChange
import com.jay.fxi.domain.model.AlertCondition
import com.jay.fxi.domain.model.RepeatInterval
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * S8-7 (`R4c/S8/s8_7_contract.codex.r2.md` §2): the tether alert draft's pure rules, shared by the premium add editor and the
 * free preview — the first input, an explicit source pick, the whole-won text of a quote, its usable value and range, and the
 * one line that describes an add's repeat choice. It knows no policy, no user, no saved model, no mode and no result, so the
 * free preview can use it without reaching any of them.
 */
internal object SourceAlertDraftRules {

    /** An add's first input: [preferred] when it is one of [sources], else the first; below; once; the quote's whole won. */
    fun seed(sources: List<String>, quotes: Map<String, Double>, preferred: String?): SourceAlertEditorInput {
        val source = preferred?.takeIf { it in sources } ?: sources.first()
        return SourceAlertEditorInput(
            source = source,
            condition = AlertCondition.BELOW,
            thresholdText = usableQuote(quotes[source])?.let(::formatThreshold).orEmpty(),
            repeat = RepeatIntervalChange.Once
        )
    }

    /** An add's explicit pick of [source]: refilled from its quote when there is one; without one the typed text stays. */
    fun pick(input: SourceAlertEditorInput, source: String, quotes: Map<String, Double>): SourceAlertEditorInput =
        input.copy(source = source, thresholdText = usableQuote(quotes[source])?.let(::formatThreshold) ?: input.thresholdText)

    /** The line under "알림 방식" for an add: once when [interval] is null, else the repeat at that interval. */
    fun addRepeatDescription(interval: RepeatInterval?): String =
        if (interval == null) "조건 충족 시 1회만 발송" else "조건 충족 동안 ${interval.everyLabel} 발송"

    /** A whole won, no grouping: the seed of a quote and the shown value of a row. */
    fun formatThreshold(value: Double): String = BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toPlainString()

    /** A quote the editor can use: finite and positive. */
    fun usableQuote(quote: Double?): Double? = quote?.takeIf { it.isFinite() && it > 0 }

    /** `[round10(0.5q), round10(1.5q)]`, both ends included; none without a usable quote or for a non-finite bound. */
    fun range(quote: Double?): ClosedFloatingPointRange<Double>? {
        val q = usableQuote(quote) ?: return null
        val lower = floor(q * 0.5 / 10.0 + 0.5) * 10.0
        val upper = floor(q * 1.5 / 10.0 + 0.5) * 10.0
        return if (lower.isFinite() && upper.isFinite()) lower..upper else null
    }
}
