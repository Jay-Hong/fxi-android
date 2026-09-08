package com.jay.fxi.ui.rates

import java.text.NumberFormat
import java.util.Locale

/**
 * The number a rate row shows, and the value that number stands for.
 *
 * Both halves are here because the bar's width depends on the second one. A row's bar is a picture
 * of its number, so two rows printing `1400.00` must get the same width — otherwise the eye reads a
 * difference the text says does not exist. Formatting in one place and rounding in another is how
 * that drifts apart, so [quantized] is defined as "parse back what [format] printed" rather than as
 * a rounding rule that merely resembles it.
 *
 * `ExchangeRate.formatRate` prints the same number with a unit suffix for the legacy screens. The
 * two converge when S4 deletes those; until then this is the v2 owner and that one is not called
 * from here.
 */
object RateDisplay {
    /** Won quotes are published to the second decimal, and every surface shows exactly that. */
    const val FRACTION_DIGITS = 2

    fun format(value: Double): String = formatter().format(value)

    /**
     * The value [format] would print, as a number.
     *
     * Non-finite input is returned unchanged: `NumberFormat` prints `NaN`/`∞`, which it cannot then
     * parse, and a rate row has nothing to draw for those anyway.
     */
    fun quantized(value: Double): Double {
        if (!value.isFinite()) return value
        return formatter().parse(format(value))?.toDouble() ?: value
    }

    /** `NumberFormat` is not thread-safe, so callers get their own — the same reason the domain model does. */
    private fun formatter(): NumberFormat = NumberFormat.getNumberInstance(Locale.KOREA).apply {
        isGroupingUsed = false
        minimumFractionDigits = FRACTION_DIGITS
        maximumFractionDigits = FRACTION_DIGITS
    }
}
