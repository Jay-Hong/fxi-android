package com.jay.fxi.ui.graph

import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
import com.jay.fxi.domain.model.GraphPeriod
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

/**
 * Draw-ready geometry, computed once per response and **without reading a clock**.
 *
 * iOS's equivalent builder is documented as making zero `Date()` calls, and keeping that property
 * is what lets the result be cached: a model that consulted the clock would bake one instant into
 * a value that outlives it. Everything that depends on "when is now" — the frame, and the flat
 * hold that runs to its right edge — is resolved later, in `GraphFrame.kt`, from an explicit right
 * edge. For the free surface that right edge is the snapshot's own `as_of`, so nothing here is ever
 * stale; S4's live graph will pass a real clock reading to the same functions.
 */
data class LinePoint(val ts: Instant, val rate: Double)

/** The shaded high/low band. Absent wherever the server sent no extremes. */
data class BandPoint(val ts: Instant, val low: Double, val high: Double)

data class PreparedSeries(
    val seriesId: String,
    val label: String,
    val axisGroup: String?,
    /** Carry-in seed (when admitted) plus gap holds. **No trailing hold** — that needs a frame. */
    val linePoints: List<LinePoint>,
    /**
     * Built from the server's own points only.
     *
     * A seeded or held point carries no high/low, so including them would draw a band the data
     * never claimed — widest exactly where the evidence is thinnest.
     */
    val bandPoints: List<BandPoint>,
    /** This series' own observed range, widened by an admitted seed. Null when it has no points. */
    val extrema: ClosedFloatingPointRange<Double>?,
    /** The last *real* observation. The trailing hold is drawn from it, so it is kept apart. */
    val lastObservation: LinePoint?,
    val insufficientHistory: Boolean,
    /** Whether a seed was admitted. Only the fact — the carry-in's own `observedAt` is not kept. */
    val carryInApplied: Boolean
)

/** `rolling` follows the right edge; `fixed_start` pins the left one. Anything else is refused. */
enum class LiveDomainMode { ROLLING, FIXED_START }

/** The server's declared display window, once every part of it has been validated. */
data class DisplayDomain(val start: Instant, val end: Instant, val mode: LiveDomainMode)

/** The span the data itself occupies, used when the server declares no usable domain. */
data class TimeRange(val start: Instant, val end: Instant)

data class PreparedGraph(
    val period: GraphPeriod,
    /** Wire order, preserved: the draw order is a display concern, not the model's. */
    val order: List<String>,
    /**
     * Every series in the response, **including ones the user has switched off**.
     *
     * The time axis is computed across all of them. Hiding a series must change which lines are
     * drawn and the value axis they share — never where the x axis begins and ends, or a toggle
     * would slide the whole chart sideways.
     */
    val bySeries: Map<String, PreparedSeries>,
    val domain: DisplayDomain?,
    val dataBounds: TimeRange?
)

object GraphPreparedBuilder {

    /** 1d buckets are 10 minutes; a week is hourly; the long periods are daily. */
    fun bucketFor(period: GraphPeriod): Duration = when (period) {
        GraphPeriod.ONE_DAY -> 600.seconds
        GraphPeriod.ONE_WEEK -> 3600.seconds
        GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR -> 86400.seconds
    }

    /**
     * How long a snapshot may hold its last value out to the right edge before giving up.
     *
     * A feed that stopped days ago must not be drawn as a flat line running to the present — that
     * reads as "unchanged", not "no data". Null for 1d, which never holds at all.
     */
    fun maxTrailingGap(period: GraphPeriod): Duration? = when (period) {
        GraphPeriod.ONE_DAY -> null
        GraphPeriod.ONE_WEEK -> 4.days
        GraphPeriod.THREE_MONTHS, GraphPeriod.ONE_YEAR -> 5.days
    }

    fun build(graph: FreeGraph, period: GraphPeriod): PreparedGraph {
        val domain = validatedDomain(graph, period)
        val bucket = bucketFor(period)
        val prepared = graph.series.map { prepare(it, period, domain, bucket) }
        return PreparedGraph(
            period = period,
            order = prepared.map { it.seriesId },
            bySeries = prepared.associateBy { it.seriesId },
            domain = domain,
            dataBounds = dataBoundsOf(graph.series)
        )
    }

    /**
     * The server's domain, or nothing.
     *
     * All three fields have to be present and agree with each other *and* with the period —
     * `rolling` belongs to 1d and only 1d. A domain that is partly right is not partly usable: it
     * would place the left edge somewhere the data never was.
     */
    internal fun validatedDomain(graph: FreeGraph, period: GraphPeriod): DisplayDomain? {
        val start = graph.domainStartAt ?: return null
        val end = graph.domainEndAt ?: return null
        val mode = when (graph.liveDomainMode) {
            "rolling" -> LiveDomainMode.ROLLING
            "fixed_start" -> LiveDomainMode.FIXED_START
            else -> return null
        }
        if (start >= end) return null
        if ((mode == LiveDomainMode.ROLLING) != (period == GraphPeriod.ONE_DAY)) return null
        return DisplayDomain(start, end, mode)
    }

    /**
     * The span the data occupies, measured over the KRW series alone where the tab has any.
     *
     * A dollar index reaching further back than every rate would otherwise stretch the axis to
     * cover a series that is only ever an overlay. A tab with no KRW series at all falls back to
     * the index — which is not a shape the free tabs currently send, but is what the reference
     * does.
     */
    internal fun dataBoundsOf(series: List<FreeGraphSeries>): TimeRange? {
        // Chosen by which axes the answer *has*, not by which happen to carry points. A rate tab
        // that returned its rates empty has no span — falling through to the index overlay would
        // invent one out of a series that is only ever drawn against the rates.
        val krw = series.filter { it.axisGroup == KRW_AXIS }
        val target = if (krw.isEmpty()) series.filter { it.axisGroup == INDEX_AXIS } else krw
        val stamps = target.flatMap { s -> s.points.map { it.timestamp } }
        val start = stamps.minOrNull() ?: return null
        val end = stamps.maxOrNull() ?: return null
        return if (start < end) TimeRange(start, end) else null
    }

    private fun prepare(
        series: FreeGraphSeries,
        period: GraphPeriod,
        domain: DisplayDomain?,
        bucket: Duration
    ): PreparedSeries {
        val ordered = series.points.sortedBy { it.timestamp }
        val seed = carryInSeed(series, period, domain, ordered)
        val seeded = listOfNotNull(seed) + ordered.map { LinePoint(it.timestamp, it.rate) }
        return PreparedSeries(
            seriesId = series.seriesId,
            label = series.label,
            axisGroup = series.axisGroup,
            linePoints = forwardFilled(seeded, bucket),
            bandPoints = forwardFilledBand(ordered, bucket),
            extrema = extremaOf(ordered, seed, series.axisGroup),
            lastObservation = ordered.lastOrNull()?.let { LinePoint(it.timestamp, it.rate) },
            insufficientHistory = series.insufficientHistory,
            carryInApplied = seed != null
        )
    }

    /**
     * The last observation from *before* the window, planted on its left edge.
     *
     * Without it a series whose first point is a day inside the window is drawn starting there,
     * and the eye reads the missing stretch as missing data rather than as "unchanged since
     * before". Five conditions, all necessary:
     *
     * - not 1d, where the window is short enough that a stale seed would dominate it;
     * - not `insufficient_history`, where the server is already saying the series does not reach back;
     * - a validated domain, because the seed's position *is* its left edge — placing it at the
     *   first data point instead would draw a flat run to nowhere;
     * - a carry-in actually sent;
     * - and the first real point strictly later than that edge, or the seed would sit on top of it.
     *
     * It is planted in the forward-fill's **input**, not its output: as an input it is a point the
     * gap rule can then hold from, which is the whole purpose. Appended afterwards it becomes a
     * lone vertex the line runs diagonally away from — the exact artefact iOS's own comment warns
     * about at the same spot.
     */
    internal fun carryInSeed(
        series: FreeGraphSeries,
        period: GraphPeriod,
        domain: DisplayDomain?,
        ordered: List<FreeGraphPoint>
    ): LinePoint? {
        if (period == GraphPeriod.ONE_DAY) return null
        if (series.insufficientHistory) return null
        val edge = domain?.start ?: return null
        val carry = series.carryIn ?: return null
        val first = ordered.firstOrNull() ?: return null
        if (first.timestamp <= edge) return null
        return LinePoint(edge, carry.rate)
    }

    /**
     * Hold the last value across a gap instead of drawing a straight line through it.
     *
     * A weekend with no trading is not a slow drift from Friday to Monday. The hold is placed one
     * bucket before the next point, so the line stays flat and then steps — and the threshold is
     * `1.5 ×` a bucket so that ordinary jitter in bucket spacing is not mistaken for a gap.
     */
    internal fun forwardFilled(points: List<LinePoint>, bucket: Duration): List<LinePoint> {
        if (points.size < 2) return points
        val filled = mutableListOf<LinePoint>()
        points.forEachIndexed { index, point ->
            filled += point
            val next = points.getOrNull(index + 1) ?: return@forEachIndexed
            if (next.ts - point.ts <= bucket * 1.5) return@forEachIndexed
            val hold = next.ts - bucket
            if (hold > point.ts) filled += LinePoint(hold, point.rate)
        }
        return filled
    }

    /**
     * The band collapses to nothing across a gap, at both ends of it.
     *
     * Carrying a high/low band forward would claim a spread nobody observed. Every collapse point
     * uses the previous close. For gaps wider than two buckets, collapse at `point.ts + bucket`
     * and `next.ts - bucket`. For gaps wider than 1.5 and at most two buckets, use only
     * `next.ts - bucket`.
     */
    internal fun forwardFilledBand(points: List<FreeGraphPoint>, bucket: Duration): List<BandPoint> {
        val band = mutableListOf<BandPoint>()
        points.forEachIndexed { index, point ->
            val high = point.high ?: return@forEachIndexed
            val low = point.low ?: return@forEachIndexed
            band += BandPoint(point.timestamp, low, high)
            // The neighbour is the next point the *server* sent, not the next one that happened to
            // carry extremes. Skipping over a band-less point would merge two ordinary intervals
            // into one apparent gap.
            val next = points.getOrNull(index + 1) ?: return@forEachIndexed
            if (next.timestamp - point.timestamp <= bucket * 1.5) return@forEachIndexed
            val after = point.timestamp + bucket
            val before = next.timestamp - bucket
            // Both shoulders use the previous close, keeping the zero-width segment horizontal.
            // Using the next close at the far shoulder would introduce a slope.
            if (after < before) {
                band += BandPoint(after, point.rate, point.rate)
                band += BandPoint(before, point.rate, point.rate)
            } else {
                band += BandPoint(before, point.rate, point.rate)
            }
        }
        return band
    }

    /**
     * KRW extrema include rate/high/low; index extrema include rate only. Either range also
     * includes an admitted carry-in.
     *
     * The index range is separate from the KRW range; including index wicks would compress the
     * normalized index line's movement.
     */
    internal fun extremaOf(
        points: List<FreeGraphPoint>,
        seed: LinePoint?,
        axisGroup: String?
    ): ClosedFloatingPointRange<Double>? {
        if (points.isEmpty()) return null
        val values = if (axisGroup == INDEX_AXIS) {
            points.map { it.rate }
        } else {
            points.flatMap { listOfNotNull(it.rate, it.high, it.low) }
        } + listOfNotNull(seed?.rate)
        return values.min()..values.max()
    }

    internal const val KRW_AXIS = "krw"

    /** The index axis; historical extrema exclude high/low. */
    internal const val INDEX_AXIS = "index"
}
