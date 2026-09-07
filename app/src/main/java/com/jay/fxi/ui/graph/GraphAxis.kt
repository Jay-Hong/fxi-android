package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.GraphPeriod
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** A horizontal gridline and the number printed beside it. */
data class AxisTick(val value: Double, val label: String)

/** A vertical gridline. [showLabel] false means draw the line but print nothing under it. */
data class XTick(
    val ts: Instant,
    val label: String,
    val showLabel: Boolean,
    val isMidnight: Boolean
)

/**
 * Axis arithmetic: ranges, gridlines, and the second scale a dollar index is drawn against.
 *
 * The nice-step ladder and the index label ladder are **copies** of the shipped paid graph's, not
 * shared code. The paid graph has no tests at all, so sharing would mean editing it to widen a
 * visibility modifier and hoping; copying leaves its regression surface at zero lines and lets S4
 * delete the original, which the plan already schedules.
 *
 * The x-axis label policy follows iOS Graph V2. Its two deliberate differences from the paid
 * graph are documented at [labelVisible].
 */
object GraphAxis {

    private val KST = TimeZone.of("Asia/Seoul")

    /** Below this, a KRW axis is widened: a 0.2-won day drawn full height reads as a crisis. */
    const val KRW_MARGIN_FLOOR = 0.5

    /** The same idea on the index scale, where the numbers are ~1/14th the size. */
    const val INDEX_MARGIN_FLOOR = 0.05

    /**
     * The range an axis covers: the union of what its visible series claim, plus a margin.
     *
     * Only the *visible* series count — hiding a line must free the axis it was stretching. (The
     * time axis is the opposite: it spans everything, so a toggle never slides the chart
     * sideways.)
     *
     * The margin is `max(5% of the span, floor)` on **each** side, and it is unconditional. It is
     * not only a rescue for flat series: without it the highest and lowest points of every chart
     * are drawn exactly on the border, where a line is half-clipped and unreadable. The floor is
     * what keeps a 0.2-won day from being magnified into a cliff.
     */
    fun composeAxisRange(
        extremas: List<ClosedFloatingPointRange<Double>>,
        marginFloor: Double
    ): ClosedFloatingPointRange<Double>? {
        if (extremas.isEmpty()) return null
        val low = extremas.minOf { it.start }
        val high = extremas.maxOf { it.endInclusive }
        val margin = maxOf((high - low) * 0.05, marginFloor)
        return (low - margin)..(high + margin)
    }

    // --- value gridlines ---------------------------------------------------------------------------

    /**
     * Gridlines on a 1/2/5/10 ladder, at most five of them inside the range.
     *
     * The range itself is never rounded to the ladder — only the lines are. Rounding the range
     * would silently enlarge it, and on a tight zoom that undoes the zoom.
     */
    fun valueTicks(low: Double, high: Double): List<AxisTick> {
        if (high <= low) return listOf(AxisTick(low, "%.1f".format(low)))
        var step = niceStep((high - low) / DESIRED_TICKS)
        while (interiorCount(low, high, step) > MAX_INTERIOR_TICKS) step = nextLadderStep(step)
        return ticksAt(low, high, step)
    }

    internal fun ticksAt(low: Double, high: Double, step: Double): List<AxisTick> {
        if (step <= 0.0) return listOf(AxisTick(low, "%.1f".format(low)))
        return (floor(low / step).toLong()..ceil(high / step).toLong()).map { index ->
            val value = index.toDouble() * step
            AxisTick(value, "%.1f".format(value))
        }
    }

    internal fun interiorCount(low: Double, high: Double, step: Double): Int {
        if (step <= 0.0 || high <= low) return 0
        val epsilon = (high - low) * 1e-9
        return (floor(low / step).toLong()..ceil(high / step).toLong()).count { index ->
            val value = index.toDouble() * step
            value > low + epsilon && value < high - epsilon
        }
    }

    /** d3's rule: snap to 1, 2, 5 or 10 by geometric-mean thresholds rather than midpoints. */
    internal fun niceStep(rawStep: Double): Double {
        if (rawStep <= 0.0) return 1.0
        val scale = 10.0.pow(floor(log10(rawStep)))
        val error = rawStep / scale
        val base = when {
            error >= sqrt(50.0) -> 10.0
            error >= sqrt(10.0) -> 5.0
            error >= sqrt(2.0) -> 2.0
            else -> 1.0
        }
        return base * scale
    }

    internal fun nextLadderStep(step: Double): Double {
        if (step <= 0.0) return 1.0
        val scale = 10.0.pow(floor(log10(step)))
        val base = step / scale
        return when {
            base <= 1.0 -> 2.0
            base <= 2.0 -> 5.0
            else -> 10.0
        } * scale
    }

    // --- the index overlay ---------------------------------------------------------------------------

    /**
     * An index value placed on the KRW scale, so one chart can carry both.
     *
     * Degenerate spans collapse to the bottom of the rate range rather than dividing by zero; the
     * line is then flat, which is what a spanless series is.
     */
    fun normalizeIndexValue(
        value: Double,
        rateLow: Double,
        rateHigh: Double,
        indexLow: Double,
        indexHigh: Double
    ): Double {
        val indexSpan = indexHigh - indexLow
        val rateSpan = rateHigh - rateLow
        if (indexSpan <= 0.0 || rateSpan <= 0.0) return rateLow
        return rateLow + (value - indexLow) / indexSpan * rateSpan
    }

    /**
     * The index's own numbers, on their own ladder.
     *
     * Its steps are a tenth of the rate axis's because a dollar index moves in hundredths. Two
     * decimals below a step of 0.1, one above, so a 0.05 ladder does not print the same label
     * twice. A zoom tight enough to fall between two steps yields nothing at all — the midpoint is
     * then labelled alone, because an axis with no numbers on it is unreadable.
     */
    fun indexLabels(low: Double, high: Double): List<AxisTick> {
        val span = high - low
        if (span <= 0.0) return emptyList()
        val rawStep = span / 4.0
        val step = when {
            rawStep < 0.08 -> 0.05
            rawStep < 0.15 -> 0.1
            rawStep < 0.35 -> 0.2
            rawStep < 0.75 -> 0.5
            rawStep < 1.5 -> 1.0
            else -> 2.0
        }
        val format = if (step < 0.1) "%.2f" else "%.1f"
        val labels = mutableListOf<AxisTick>()
        var value = ceil(low / step) * step
        val end = floor(high / step) * step
        var previous: String? = null
        while (value <= end + step * 0.01) {
            val snapped = round(value / step) * step
            val text = String.format(format, snapped)
            if (text != previous) {
                labels += AxisTick(snapped, text)
                previous = text
            }
            value += step
        }
        if (labels.isEmpty()) {
            val mid = (low + high) / 2.0
            labels += AxisTick(mid, "%.2f".format(mid))
        }
        return labels
    }

    // --- time gridlines ------------------------------------------------------------------------------

    /**
     * Vertical gridlines, generated from the **data** window and then clipped to the **display**
     * window.
     *
     * The two windows do different jobs and both are needed. Generating from the padded window
     * would shift the whole alignment: the 3달 fixture starts at Seoul midnight on 6/2, and six
     * hours of padding moves the fortnightly base to 6/1, so every date label is a day out.
     * Clipping to the display window is what keeps a stray line in the margin from appearing —
     * and, because the first surviving tick is the one whose label is suppressed on the long
     * periods, an off-screen tick would otherwise absorb that suppression and let the real first
     * label print where it gets clipped.
     *
     * [display] is also where zoom enters: pass the zoomed window and the ticks follow it.
     */
    fun xTicks(
        period: GraphPeriod,
        data: TimeFrame,
        display: TimeFrame,
        rightEdge: Instant?
    ): List<XTick> {
        // Generated a little past the data's end so the final aligned tick is not lost to rounding.
        val until = data.end + 30.minutes
        val generated = when (period) {
            GraphPeriod.ONE_DAY -> hourStamps(data.start, until, hourIntervalFor(display.length))
            GraphPeriod.ONE_WEEK -> dayStamps(data.start, until, 1)
            GraphPeriod.THREE_MONTHS -> dayStamps(data.start, until, 14)
            GraphPeriod.ONE_YEAR -> monthStamps(data.start, until)
        }.filter { it >= display.start && it <= display.end }

        val first = generated.firstOrNull()
        return generated.map { at ->
            val local = at.toLocalDateTime(KST)
            val midnight = local.hour == 0 && local.minute == 0
            XTick(
                ts = at,
                label = when {
                    period == GraphPeriod.ONE_YEAR -> "${local.monthNumber}월"
                    midnight -> "${local.monthNumber}/${local.dayOfMonth}"
                    else -> "%02d".format(local.hour)
                },
                showLabel = labelVisible(at, rightEdge, first, period, midnight),
                isMidnight = midnight
            )
        }
    }

    /**
     * Whether a tick may print its label. iOS Graph V2's rule, which differs from the paid graph's
     * in two ways — each a deliberate change, not an oversight:
     *
     * - it measures against the **frame's** right edge, not the last data point. The frame can
     *   reach past the data to today, and the paid rule hides today's date exactly when the user
     *   most wants to see it.
     * - midnight on 1일 gets a wider berth (1200s vs 600s), because its label is a date and takes
     *   twice the width of an hour.
     *
     * Hiding the first tick only on the long periods is *not* a change — the paid graph already
     * does that, and on 1일 the leading date is the only thing saying which day the left edge is.
     *
     * A tick past the right edge needs no guard of its own: its distance to the edge is negative
     * and every minimum is positive, so the distance test below already refuses it. The reference
     * spells that case out separately; a mutant of the extra check survived every test here, which
     * is what redundant-but-harmless looks like from the outside.
     */
    fun labelVisible(
        at: Instant,
        rightEdge: Instant?,
        firstTick: Instant?,
        period: GraphPeriod,
        isMidnight: Boolean
    ): Boolean {
        if (rightEdge == null) return true
        if (period != GraphPeriod.ONE_DAY && firstTick != null && at == firstTick) return false
        val minimumDistance: Duration = when (period) {
            GraphPeriod.ONE_DAY -> if (isMidnight) 1200.seconds else 600.seconds
            GraphPeriod.ONE_WEEK -> 6.hours
            GraphPeriod.THREE_MONTHS -> 3.days
            GraphPeriod.ONE_YEAR -> 10.days
        }
        return rightEdge - at >= minimumDistance
    }

    /** Three-hourly beyond half a day, hourly once zoomed in past it. */
    internal fun hourIntervalFor(visible: Duration): Int = if (visible > 12.hours) 3 else 1

    /**
     * From Seoul midnight of the starting day, stepping by [interval] — and **not** skipped past
     * [from].
     *
     * A stamp earlier than the data window is not waste: the display window reaches further left
     * by the padding, so it is inside the visible area and the clip keeps it. Skipping it drops the
     * one tick whose label is the date, and a 1일 chart opening at 00:05 loses the only thing
     * saying which day it is.
     */
    private fun hourStamps(from: Instant, until: Instant, interval: Int): List<Instant> {
        val startAt = from.toLocalDateTime(KST)
        var at = LocalDateTime(startAt.year, startAt.monthNumber, startAt.dayOfMonth, 0, 0, 0).toInstant(KST)
        val stamps = mutableListOf<Instant>()
        while (at <= until) {
            stamps += at
            at += interval.hours
        }
        return stamps
    }

    private fun dayStamps(from: Instant, until: Instant, intervalDays: Int): List<Instant> {
        val startAt = from.toLocalDateTime(KST)
        var at = LocalDateTime(startAt.year, startAt.monthNumber, startAt.dayOfMonth, 0, 0, 0).toInstant(KST)
        val stamps = mutableListOf<Instant>()
        while (at <= until) {
            stamps += at
            at += intervalDays.days
        }
        return stamps
    }

    /**
     * Every second month, aligned to an even one so the labels do not shuffle as data accumulates.
     *
     * January steps back to the previous December rather than clamping to January: clamping leaves
     * an odd month, and the whole sequence is then odd for a year.
     */
    private fun monthStamps(from: Instant, until: Instant): List<Instant> {
        val startAt = from.toLocalDateTime(KST)
        val month = startAt.monthNumber
        var local = if (month % 2 == 0) {
            LocalDateTime(startAt.year, month, 1, 0, 0, 0)
        } else if (month == 1) {
            LocalDateTime(startAt.year - 1, 12, 1, 0, 0, 0)
        } else {
            LocalDateTime(startAt.year, month - 1, 1, 0, 0, 0)
        }
        val stamps = mutableListOf<Instant>()
        while (local.toInstant(KST) <= until) {
            stamps += local.toInstant(KST)
            val next = local.monthNumber + 2
            local = LocalDateTime(local.year + (next - 1) / 12, ((next - 1) % 12) + 1, 1, 0, 0, 0)
        }
        return stamps
    }

    private const val DESIRED_TICKS = 4
    private const val MAX_INTERIOR_TICKS = 5
}
