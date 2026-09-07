package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

/**
 * A window on the time axis, **unpadded**.
 *
 * Unpadded is the load-bearing word. Two windows exist and they are not interchangeable:
 *
 * - this one — where the data actually is. The carry-in seed is planted on its left edge, the
 *   trailing hold runs to its right edge, the "is the feed dead" gate measures against that edge,
 *   and zoom anchors to it.
 * - [GraphFrame.rendered] — this one plus breathing room, and the *only* thing the x scale, the
 *   tick filter and the zoom clamp's outer bound may use.
 *
 * Collapsing them into one value is the mistake this split exists to prevent: the seed lands
 * outside the data, the hold overshoots by the padding, and the dead-feed gate measures an age
 * inflated by up to three days on 1년 — refusing holds it should have allowed.
 */
data class TimeFrame(val start: Instant, val end: Instant) {
    init {
        require(start < end) { "a time frame must move forwards" }
    }

    val length: Duration get() = end - start
}

object GraphFrame {

    /**
     * Breathing room at each edge, so the first and last points are not drawn on the border.
     *
     * Asymmetric on the long periods because their right edge is where the newest value is, and a
     * value pinned to the frame's edge is the one hardest to read.
     */
    fun paddingFor(period: GraphPeriod): Pair<Duration, Duration> = when (period) {
        GraphPeriod.ONE_DAY -> 600.seconds to 600.seconds
        GraphPeriod.ONE_WEEK -> 3600.seconds to 3600.seconds
        GraphPeriod.THREE_MONTHS -> 21600.seconds to 86400.seconds
        GraphPeriod.ONE_YEAR -> 86400.seconds to 259200.seconds
    }

    /**
     * The unpadded window.
     *
     * [rightEdgeNow] is the seam the live graph will use and the free graph never does. Null means
     * "the answer is as of when it says it is", which is what a snapshot is; a non-null reading
     * means "keep up with the clock", which is what S4 needs. The two modes differ in which edge
     * moves: `rolling` slides the whole window so its length is preserved, `fixed_start` pins the
     * left edge and lets the right one grow.
     *
     * Falls back to where the data is when the server declared no usable domain.
     */
    fun resolve(prepared: PreparedGraph, rightEdgeNow: Instant? = null): TimeFrame? {
        val domain = prepared.domain
        if (domain == null) {
            // Older servers, and any answer whose domain failed validation. The live seam applies
            // here too: without it a live graph on a fallback window would stop at the last
            // observation and never reach the present.
            val bounds = prepared.dataBounds ?: return null
            val end = if (rightEdgeNow == null) bounds.end else maxOf(bounds.end, rightEdgeNow)
            return TimeFrame(bounds.start, end)
        }
        return when (domain.mode) {
            LiveDomainMode.ROLLING -> {
                val end = rightEdgeNow ?: domain.end
                TimeFrame(end - (domain.end - domain.start), end)
            }
            LiveDomainMode.FIXED_START -> {
                val end = if (rightEdgeNow == null) domain.end else maxOf(domain.end, rightEdgeNow)
                TimeFrame(domain.start, end)
            }
        }
    }

    /** The padded window. For the x scale and the tick filter — never for placing data. */
    fun rendered(frame: TimeFrame, period: GraphPeriod): TimeFrame {
        val (lead, trail) = paddingFor(period)
        return TimeFrame(frame.start - lead, frame.end + trail)
    }

    /**
     * A flat hold from the last observation out to the frame's right edge, when that is honest.
     *
     * Snapshot trailing holds require a long period, sufficient history, and a last observation
     * strictly before the frame end. The maximum age is four days for 1주 and five days for
     * 3달/1년, inclusive.
     *
     * A rate published an hour ago has not changed and may say so; one that stopped long enough
     * ago is missing rather than unchanged, and a flat line to the present would say the wrong
     * thing. 1일 never holds at all — over a single day, anything worth holding is a gap the user
     * should see.
     */
    fun trailingHold(
        series: PreparedSeries,
        period: GraphPeriod,
        frame: TimeFrame
    ): LinePoint? {
        if (series.insufficientHistory) return null
        val budget = GraphPreparedBuilder.maxTrailingGap(period) ?: return null
        val last = series.lastObservation ?: return null
        if (frame.end <= last.ts) return null
        if (frame.end - last.ts > budget) return null
        return LinePoint(frame.end, last.rate)
    }

    /** The line as drawn: everything the builder produced, plus the hold when one is warranted. */
    fun lineWithTrailing(
        series: PreparedSeries,
        period: GraphPeriod,
        frame: TimeFrame
    ): List<LinePoint> = series.linePoints + listOfNotNull(trailingHold(series, period, frame))
}
