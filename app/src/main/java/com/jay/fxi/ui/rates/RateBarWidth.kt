package com.jay.fxi.ui.rates

import kotlin.math.ulp

/**
 * How wide a rate row's bar is. Linear in the value, over the range the scale policy chose.
 *
 * The legacy bar used a table of banded percentages (`35%~80%` when the span is at least 4 won,
 * `40%~80%` at 3, and so on down to `65%~77%`). Inside a band the mapping is linear but the bands
 * themselves are not, so a spread that crosses one changes every bar's width by a step nobody's
 * quote moved. Measured on iOS before the same change, 5~19% of neighbouring pairs came out
 * indistinguishable; a plain linear map made it 0.
 *
 * **The domain is the scale policy's `displayRange`, not the raw min/max.** That is the whole point
 * of `ScalePolicy`: one outlier stretching the raw range squashes everything else into a few pixels.
 * A value outside the display range clamps to an end, which is what an outlier should look like.
 * (Linear over a chosen domain is not "double correction" — reshaping strategies like kernels or
 * bands are; picking a domain is just picking a domain.)
 */
object RateBarWidth {
    /** The widest a bar may be, as a share of the row. Beyond this the difference column is squeezed. */
    const val MAX_FRACTION = 0.80f

    /**
     * All lengths are in one unit — dp at the call site — and the result is in that unit too.
     *
     * The clamps live here rather than in the layout because they are half of what the width *is*:
     * "every bar the same length" has two causes, a scale policy that never activated and a minimum
     * width that swallowed the difference. A test that only checked the ratio would catch one.
     */
    fun of(
        value: Double,
        low: Double,
        high: Double,
        available: Float,
        minBar: Float,
        diffMin: Float
    ): Float {
        // Geometry first, and separately: `lower` is built from these, so a non-finite one would be
        // handed back as the fallback and a NaN width goes on to poison the layout it reaches.
        if (!minBar.isFinite() || !available.isFinite() || !diffMin.isFinite()) return 0f

        val lower = maxOf(0f, minBar)
        val upper = maxOf(lower, minOf(MAX_FRACTION * available, available - diffMin))
        val budget = upper - lower

        // A rate we cannot place still has a row to sit in, so it gets the shortest bar there is.
        if (!value.isFinite() || !low.isFinite() || !high.isFinite()) return lower

        // Quantized, so two rows printing the same number cannot be drawn at different lengths.
        val bottom = RateDisplay.quantized(minOf(low, high))
        val top = RateDisplay.quantized(maxOf(low, high))
        val span = top - bottom
        // One quote, or several that print the same: there is no spread to position within, and
        // pinning them to either end would read as an extreme. The middle says "no comparison here".
        if (span <= 1.0.ulp) return lower + budget * 0.5f

        val position = ((RateDisplay.quantized(value) - bottom) / span).coerceIn(0.0, 1.0)
        return lower + budget * position.toFloat()
    }
}
