package com.jay.fxi.ui.free

import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import kotlinx.datetime.Instant

/** Normalized coordinates keep chart calculation off the Compose path and JVM-testable. */
data class FreeSnapshotChartPoint(val x: Float, val y: Float)

/**
 * The frame a set of series is drawn in.
 *
 * Time is shared by everything on screen; value is not. A KRW rate near 1,400 and a dollar index
 * near 99 share no scale, and forcing them into one would flatten both into straight lines — which
 * is why [FreeGraphSeries.axisGroup] exists and why the range is resolved per group.
 */
data class FreeChartDomain(
    val start: Instant,
    val end: Instant,
    val low: Double,
    val high: Double
) {
    init {
        require(start <= end) { "chart domain must not run backwards" }
        require(low <= high) { "chart value range must not run backwards" }
    }
}

/**
 * Where a series' points land inside a frame, in 0..1 with y already flipped for the screen.
 *
 * Separate from the domain on purpose: the same points normalize differently as the frame changes,
 * which is the whole of zooming. Each series used to be scaled against its *own* extremes, so two
 * series in one frame could not be compared and no zoom was expressible at all.
 */
object FreeChartGeometry {

    /**
     * The frame each axis group is drawn in.
     *
     * One entry point rather than a time helper and a value helper, because splitting them is
     * exactly the mistake it exists to prevent: **time is shared by everything on screen, value is
     * shared only within an axis group.** Computing a whole domain per group also splits the x
     * axis, and then the same instant sits mid-chart on one axis and hard left on another — the
     * two lines stop being readable against each other, which is the entire reason they are on one
     * chart. Series with no points take no part in the frame and get no entry.
     *
     * The frame is taken from the data. The server also sends `domain_start_at`/`domain_end_at`,
     * and iOS prefers those; wiring that precedence is a separate change, since "absent" and
     * "narrower than the data" both need an answer first.
     */
    fun frames(series: List<FreeGraphSeries>): Map<String?, FreeChartDomain> {
        val drawn = series.filter { it.points.isNotEmpty() }
        if (drawn.isEmpty()) return emptyMap()
        val everyPoint = drawn.flatMap { it.points }
        val start = everyPoint.minOf { it.timestamp }
        val end = everyPoint.maxOf { it.timestamp }
        return drawn.groupBy { it.axisGroup }.mapValues { (_, group) ->
            val points = group.flatMap { it.points }
            FreeChartDomain(start = start, end = end, low = points.low(), high = points.high())
        }
    }

    /** One series' own observed extremes — what a label about *that* series may report. */
    fun extremesOf(points: List<FreeGraphPoint>): ClosedFloatingPointRange<Double>? =
        if (points.isEmpty()) null else points.low()..points.high()

    fun normalize(points: List<FreeGraphPoint>, domain: FreeChartDomain): List<FreeSnapshotChartPoint> {
        val span = (domain.end - domain.start).inWholeMilliseconds.toDouble()
        val spread = domain.high - domain.low
        return points.sortedBy { it.timestamp }.map { point ->
            FreeSnapshotChartPoint(
                x = if (span == 0.0) 0.5f
                else ((point.timestamp - domain.start).inWholeMilliseconds / span).toFloat(),
                // Screen y grows downward, so the highest value sits at 0.
                y = if (spread == 0.0) 0.5f else (1.0 - (point.rate - domain.low) / spread).toFloat()
            )
        }
    }

    // `high`/`low` are the bucket's extremes; a frame clipped to the closes would cut the wicks off
    // the very candles the server sent.
    private fun List<FreeGraphPoint>.low() = minOf { minOf(it.rate, it.low ?: it.rate) }
    private fun List<FreeGraphPoint>.high() = maxOf { maxOf(it.rate, it.high ?: it.rate) }
}
