package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant

/**
 * Where the zoom window is, decided without touching a pixel or reading a clock.
 *
 * A port of iOS `GraphZoomMath.swift`, function for function, because the free graph's zoom is
 * specified by iOS Graph V2 and not by this app's paid graph. The gestures that drive it arrive in
 * slice 7; everything they will have to compute is here, where the JVM can test it.
 *
 * Two windows are in play and confusing them is the mistake this file is written to avoid. The
 * *padded* frame is [clampVisibleDomain]'s `fullDomain` — what the x scale maps onto, so "have we
 * zoomed back out to everything?" is asked against it. `latestAnchor` is the **unpadded right edge
 * of the resolved data window** — `GraphFrame.resolve(...)!!.end`, which is the server's domain end
 * when there is one and the last observation only when there is not. Not the newest point: a
 * snapshot whose points stop at 10:00 but whose domain ends at 10:07 anchors at 10:07.
 *
 * iOS splits them the same way (`GraphV2Section.swift:917` padded `xDomain`, `:656` unpadded
 * `liveAnchor` = `dataBounds.end`). Anchoring on the padded edge instead would not break following
 * outright — a window that never reaches the boundary still follows — but it would break the way
 * anyone actually resumes it: pan to the right-hand end and the window stops one trailing padding
 * *past the anchor*, and that distance is never within a bucket of it, so following never returns.
 */
object GraphZoomMath {

    /** How far in a pinch may go: six buckets, which is one hour on 1일. */
    fun minVisibleLength(period: GraphPeriod): Duration = GraphPreparedBuilder.bucketFor(period) * 6

    /** What a double tap opens when nothing is zoomed yet. */
    fun doubleTapWindow(period: GraphPeriod): Duration = when (period) {
        GraphPeriod.ONE_DAY -> 6.hours
        else -> GraphPreparedBuilder.bucketFor(period) * 30
    }

    /**
     * How near the live edge a gesture must end for following to resume — one bucket.
     *
     * The edge is the resolved domain end, not the newest point, and the two can differ: a snapshot
     * whose points stop at 10:00 but whose domain runs to 10:17 resumes at 10:17, and letting go on
     * that last point would be seventeen minutes adrift. One bucket is the spacing between
     * observations, so releasing on the edge resumes and releasing one observation back does not.
     */
    fun followThreshold(period: GraphPeriod): Duration = GraphPreparedBuilder.bucketFor(period)

    /**
     * Where a finger is: as a fraction across the plot, and as the instant under it.
     *
     * The fraction is a Double although it comes from Float pixels — it is multiplied by a window
     * length in nanoseconds, and Float carries too few digits to survive that.
     */
    data class Anchor(val fraction: Double, val instant: Instant)

    fun locationToInstant(
        locationX: Float,
        plotLeft: Float,
        plotWidth: Float,
        baseline: ClosedRange<Instant>
    ): Anchor {
        // A zero-width plot has no meaningful fraction; the middle is the least surprising answer
        // and keeps a pinch from throwing before the chart has been measured.
        val fraction =
            if (plotWidth > 0f) ((locationX - plotLeft).toDouble() / plotWidth).coerceIn(0.0, 1.0) else 0.5
        return Anchor(fraction, baseline.start + baseline.length * fraction)
    }

    /**
     * Follow-latest: keep the window's length, pin its right edge to [latest].
     *
     * This is what makes a zoomed chart drift with incoming data instead of freezing in the past.
     * [latest] is the resolved domain end — see the note on this file, which is the only place the
     * distinction from "the newest observation" is spelled out.
     */
    fun resolvedDomain(
        raw: ClosedRange<Instant>,
        latest: Instant?,
        isFollowing: Boolean
    ): ClosedRange<Instant> {
        if (!isFollowing || latest == null) return raw
        return (latest - raw.length)..latest
    }

    /**
     * Whether following resumes once a gesture ends — only if it let go near the live edge.
     *
     * Drag back into history and the window stays put; that is the whole point of having dragged.
     */
    fun shouldFollow(rawEnd: Instant, latest: Instant?, threshold: Duration): Boolean {
        if (latest == null) return false
        return (rawEnd - latest).absoluteValue < threshold
    }

    /** A pan, in pixels, as a parallel move of the window. Dragging right walks into the past. */
    fun panTranslated(
        baseline: ClosedRange<Instant>,
        translationX: Float,
        plotWidth: Float
    ): ClosedRange<Instant> {
        if (plotWidth <= 0f) return baseline
        val delta = baseline.length * (-translationX.toDouble() / plotWidth.toDouble())
        return (baseline.start + delta)..(baseline.endInclusive + delta)
    }

    /**
     * A pinch, before clamping: the instant under the finger stays under the finger.
     *
     * [scale] above 1 zooms in. It is bounded the way iOS bounds it, because a recogniser can report
     * an extreme ratio from a stray touch and the window's length is divided by it.
     */
    fun pinched(baseline: ClosedRange<Instant>, anchor: Anchor, scale: Float): ClosedRange<Instant> {
        // Clamped after widening: `0.01f` as a Double is a shade under 0.01, which would let the
        // bounded window come out marginally longer than the same gesture does on iOS.
        val bounded = scale.toDouble().coerceIn(0.01, 100.0)
        val length = baseline.length / bounded
        val fraction = anchor.fraction
        return (anchor.instant - length * fraction)..(anchor.instant + length * (1.0 - fraction))
    }

    /**
     * The one place a proposed window becomes a real one — every gesture goes through here.
     *
     * Null means "show everything", which is why the zoom state is a nullable window rather than a
     * window plus a flag: a window grown back to the whole frame *is* the unzoomed chart, and saying
     * that in one value keeps the two from ever disagreeing.
     */
    fun clampVisibleDomain(
        proposed: ClosedRange<Instant>,
        fullDomain: ClosedRange<Instant>,
        latestAnchor: Instant?,
        minLength: Duration
    ): ClosedRange<Instant>? {
        val fullLength = fullDomain.length
        if (fullLength <= Duration.ZERO) return null
        val proposedLength = proposed.length

        val clamped = maxOf(minOf(proposedLength, fullLength), minLength)
        // 99%, not 100%: the arithmetic that produced `proposed` has already rounded, and a window a
        // few nanoseconds short of the whole frame is the whole frame as far as anyone can see.
        if (clamped >= fullLength * 0.99) return null

        val centre = proposed.start + proposedLength / 2
        val rightBoundary = latestAnchor ?: fullDomain.endInclusive
        var start = centre - clamped / 2
        var end = centre + clamped / 2
        if (start < fullDomain.start) {
            start = fullDomain.start
            end = start + clamped
        }
        if (end > rightBoundary) {
            end = rightBoundary
            start = end - clamped
        }
        return start..end
    }

    internal val ClosedRange<Instant>.length: Duration get() = endInclusive - start

    /**
     * How narrow the value axis may get, in KRW.
     *
     * iOS calls it `tightenMinSpan` and fixes it at 2.0, with the arithmetic written down: it keeps
     * a half-won blip under a quarter of the height, and leaves a 0.1 tick about nine points apart
     * so the labels stay readable. Without a floor, a quiet hour reads as a mountain range.
     */
    const val TIGHTEN_MIN_SPAN = 2.0

    /**
     * The value axis for what is actually on screen, never narrower than [minSpan].
     *
     * The margin comes first and is **the same formula the unzoomed axis uses**
     * (`GraphAxis.composeAxisRange`: `max(5% of the span, floor)` on each side). Tightening is not a
     * licence to draw the extremes on the border — that is exactly as unreadable inside a zoom as
     * outside one, and it is the mistake the first version of this function made.
     *
     * [minSpan] then bounds the **total** span, margin included, so the floor means what it says:
     * a quiet window cannot be magnified past it. Both ends grow equally, so the line stays where
     * the eye left it instead of sliding to an edge.
     *
     * Null when there is nothing in the window — a zoom into a gap has no values to scale to, and
     * the caller falls back to the whole day rather than inventing a range.
     */
    fun tightenedYDomain(
        values: List<Double>,
        minSpan: Double = TIGHTEN_MIN_SPAN,
        marginFloor: Double = GraphAxis.KRW_MARGIN_FLOOR
    ): ClosedFloatingPointRange<Double>? {
        if (values.isEmpty()) return null
        val low = values.min()
        val high = values.max()
        val margin = maxOf((high - low) * 0.05, marginFloor)
        var lower = low - margin
        var upper = high + margin
        val span = upper - lower
        if (span < minSpan) {
            val pad = (minSpan - span) / 2
            lower -= pad
            upper += pad
        }
        return lower..upper
    }
}
