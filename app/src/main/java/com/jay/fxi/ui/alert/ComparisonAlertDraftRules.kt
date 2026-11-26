package com.jay.fxi.ui.alert

import com.jay.fxi.data.alerts.ComparisonAlertDraft
import com.jay.fxi.data.alerts.ComparisonAlertPair
import com.jay.fxi.domain.model.Bank
import com.jay.fxi.domain.model.ComparisonAlertSetting
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.RateSourceSets
import com.jay.fxi.domain.model.RepeatInterval
import com.jay.fxi.domain.model.TopicQuoteKey
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal typealias ComparisonLeg = Pair<String, String>
internal typealias ComparisonQuotes = Map<TopicQuoteKey, Double>

internal fun ComparisonAlertSetting.originalPair() =
    ComparisonAlertPair(tab, diffType, leftSource to leftAsset, rightSource to rightAsset)

internal fun comparisonName(code: String) =
    Exchange.fromCode(code)?.displayName ?: Bank.fromCode(code)?.shortName ?: code

private fun fixed(value: Double, digits: Int, signed: Boolean = false) =
    String.format(Locale.ROOT, "%${if (signed) "+" else ""}.${digits}f", value)

private fun general(value: Double, signed: Boolean = false): String {
    val text = String.format(Locale.ROOT, if (signed) "%+g" else "%g", if (value == 0.0) 0.0 else value)
    val mantissa = text.substringBefore('e')
    val number = if ('.' in mantissa) mantissa.trimEnd('0').trimEnd('.') else mantissa
    return number + text.removePrefix(mantissa)
}

internal fun ComparisonQuotes.price(leg: ComparisonLeg) =
    get(TopicQuoteKey(leg.first, leg.second))?.takeIf { it.isFinite() && it > 0.0 }

internal object ComparisonStepRepeat {
    const val delayMs = 400L
    const val intervalMs = 100L
    fun nextDelay(fired: Int) = if (fired == 0) delayMs else intervalMs
    fun count(elapsed: Long) = if (elapsed < delayMs) 0 else (1 + (elapsed - delayMs) / intervalMs).toInt()
    fun steps(repetition: Int) = if (repetition >= 11) 10 else 1
}

internal data class ComparisonFields(
    val tab: String, val kind: String, val editing: Boolean, val selection: List<ComparisonLeg>,
    val threshold: Double, val operator: String = "gte", val repeatSeconds: Int? = null,
    val wide: Boolean = false, val manual: Boolean = false
) {
    val signed get() = kind == "signed"
    val title get() = (if (signed) "김프 알림" else "비교 알림") + if (editing) " 수정" else ""
    val heading get() = if (signed) "김프 기준" else "차이 기준"
    val guidance get() = if (tab == "tether") "비교할 거래소 2곳을 선택해주세요" else "비교할 은행 2곳을 선택해주세요"
    val pair get() = if (selection.size == 2) ComparisonAlertPair(tab, kind, selection[0], selection[1]) else null
    val repeatLabel get() = if (repeatSeconds == null) "한번만" else RepeatInterval.from(repeatSeconds)?.label ?: "기존 반복 간격 유지"
    val fieldText get() = when {
        signed -> general(threshold, true)
        tab == "tether" -> general(threshold)
        else -> fixed(threshold, 1)
    }
    val exactText get() = threshold.toString().takeIf { editing && fieldText.toDoubleOrNull() != threshold && threshold.isFinite() }
    val summary: String? get() {
        val p = pair ?: return null
        val a = comparisonName(p.left.first)
        val b = comparisonName(p.right.first)
        val above = operator == "gte"
        if (!above && operator != "lte") return null
        val text = if (signed) {
            "$a 김프가 $b 대비 ${general(threshold, true)}원 " + if (above) "이상으로 오르면 알려드려요" else "이하로 떨어지면 알려드려요"
        } else {
            "$a·$b 차이가 ${general(abs(threshold))}원 " + if (above) "이상 벌어지면 알려드려요" else "이내로 좁혀지면 알려드려요"
        }
        return "\"$text\""
    }

    fun candidates(right: Boolean = false): List<ComparisonLeg> {
        val asset = RateSourceSets.TAB_ASSETS[tab] ?: return emptyList()
        if (signed && tab != "tether" || kind !in setOf("signed", "absolute")) return emptyList()
        return when {
            signed && right -> listOf("investing", "kb", "hana").map { it to "usd-krw" }
            tab == "tether" -> RateSourceSets.EXCHANGES.map { it to asset }
            else -> RateSourceSets.FX_GRAPH_SOURCES.map { it to asset }
        }
    }

    fun pairs() = candidates().flatMap { left ->
        candidates(right = true).map { right -> ComparisonAlertPair(tab, kind, left, right) }
    }.filter { it.supported() }

    fun caption(leg: ComparisonLeg, quotes: ComparisonQuotes): String? = quotes.price(leg)?.let {
        fixed(it, when {
            signed && leg.second == "usd-krw" -> 2
            tab == "tether" -> 0
            else -> 1
        })
    }

    private fun difference(quotes: ComparisonQuotes): Double? {
        val p = pair ?: return null
        return (quotes.price(p.left) ?: return null) - (quotes.price(p.right) ?: return null)
    }

    fun current(quotes: ComparisonQuotes): List<String> {
        val p = pair ?: return emptyList()
        val difference = difference(quotes) ?: return emptyList()
        val a = comparisonName(p.left.first)
        val b = comparisonName(p.right.first)
        return if (signed) listOf("지금 $a-$b", fixed(difference, 1, true) + "원",
            fixed(difference / quotes.price(p.right)!! * 100, 2, true) + "%")
        else listOf("지금 $a↔$b", fixed(abs(difference), if (tab == "tether") 0 else 1) + "원")
    }

    private val scale get() = if (signed && !wide || !signed && tab != "tether") 10 else 1
    private val low get() = if (!signed) 0.0 else if (wide) -1000.0 else -100.0
    private val high get() = if (!signed) 999.0 else -low

    private fun units(value: Double): Double {
        val scaled = value * scale
        return Math.copySign(floor(abs(scaled)) + if (abs(scaled) % 1 >= 0.5) 1.0 else 0.0, scaled)
    }

    private fun nearest(value: Double) = units(value) / scale

    private fun suggest(quotes: ComparisonQuotes): ComparisonFields {
        val diff = difference(quotes)
        val target = if (signed) diff?.let { nearest(it.coerceIn(low, high)) } ?: threshold
            else ((units(abs(diff ?: 0.0).coerceAtMost(high)) + 1.0) / scale).coerceAtMost(high)
        return copy(threshold = target, manual = false)
    }

    fun select(leg: ComparisonLeg, right: Boolean, quotes: ComparisonQuotes): ComparisonFields {
        if (leg !in candidates(right)) return this
        val next = when {
            signed -> selection.toMutableList().apply { this[if (right) 1 else 0] = leg }
            leg in selection -> selection - leg
            else -> selection.takeLast(1) + leg
        }
        val updated = copy(selection = next)
        if (next.size == 2 && updated.pair?.supported() != true) return this
        return if (editing) updated else updated.suggest(quotes)
    }

    fun condition(value: String, quotes: ComparisonQuotes): ComparisonFields {
        val next = copy(operator = value)
        return if (editing || signed || value == operator) next else next.suggest(quotes)
    }

    fun widen(quotes: ComparisonQuotes): ComparisonFields {
        if (!signed || wide) return this
        val next = copy(wide = true)
        val target = if (!editing && !manual) difference(quotes) ?: threshold else threshold
        return next.copy(threshold = next.nearest(target.coerceIn(next.low, next.high)))
    }

    fun step(direction: Int, repetition: Int = 1): ComparisonFields {
        val target = when {
            !threshold.isFinite() -> if (threshold > 0.0) high else low
            threshold !in low..high -> threshold.coerceIn(low, high)
            else -> {
                val scaled = threshold * scale
                val steps = ComparisonStepRepeat.steps(repetition)
                (if (direction > 0) floor(scaled) + steps else ceil(scaled) - steps) / scale
            }
        }
        return copy(threshold = target.coerceIn(low, high), manual = true)
    }

    fun draft(): ComparisonAlertDraft? {
        val target = pair ?: return null
        val repeat = RepeatInterval.from(repeatSeconds)
        if (repeatSeconds != null && repeat == null) return null
        if (abs(threshold) > 10000.0) return null
        return ComparisonAlertDraft(target, operator, threshold, repeat).takeIf { it.supported() }
    }

    fun duplicate(rows: List<ComparisonAlertSetting>, self: Int?) = rows.any { row ->
        row.id != self && row.operator == operator && pair?.matches(row.originalPair()) == true &&
            abs(row.threshold - threshold) < 0.001
    }

    companion object {
        fun create(tab: String, kind: String, quotes: ComparisonQuotes): ComparisonFields {
            if (kind != "signed") return ComparisonFields(tab, kind, false, emptyList(), 2.0)
            val seed = ComparisonFields(tab, kind, false, listOf("bithumb" to "usdt-krw", "investing" to "usd-krw"), 0.0)
            val diff = seed.difference(quotes) ?: return seed
            return seed.copy(wide = abs(diff) > 100).suggest(quotes)
        }

        fun restore(row: ComparisonAlertSetting) = ComparisonFields(row.tab, row.diffType, true,
            listOf(row.leftSource to row.leftAsset, row.rightSource to row.rightAsset), row.threshold,
            row.operator, row.repeatIntervalSec, row.diffType == "signed" && abs(row.threshold) > 100)
    }
}
