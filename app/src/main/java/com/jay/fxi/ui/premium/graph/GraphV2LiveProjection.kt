package com.jay.fxi.ui.premium.graph

import com.jay.fxi.data.graph.GraphComposedBucket
import com.jay.fxi.data.graph.GraphDataScope
import com.jay.fxi.data.graph.GraphObservationSeriesKey
import com.jay.fxi.data.graph.GraphRecoverableState
import com.jay.fxi.data.graph.composeGraphBucket
import com.jay.fxi.data.graph.graphObservationBucketStart
import com.jay.fxi.domain.model.FreeGraph
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.BandPoint
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.LinePoint
import com.jay.fxi.ui.graph.PreparedGraph
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

/** Source freshness governs tip use only, independently of the ten-minute bucket width. */
internal fun graphLineFreshness(source: String): Duration =
    if (source == "hana") 1200.seconds else 600.seconds

/** Projects immutable recorder evidence at an explicit time, without feeding display points back. */
internal fun projectGraphV2Live(
    graph: FreeGraph,
    prepared: PreparedGraph,
    period: GraphPeriod,
    scope: GraphDataScope,
    live: Map<GraphObservationSeriesKey, GraphRecoverableState>,
    now: Instant
): PreparedGraph {
    val current = graphObservationBucketStart(now)
    val unreplacedBySeries = mutableMapOf<String, Map<Instant, GraphComposedBucket.Unreplaced>>()
    val merged = if (period == GraphPeriod.ONE_DAY) {
        graph.copy(series = graph.series.map { series ->
            val data = live[GraphObservationSeriesKey(scope, series.seriesId)]?.data
                ?: return@map series
            val restStarts = series.points.mapTo(mutableSetOf()) { it.timestamp }
            val unreplaced = mutableMapOf<Instant, GraphComposedBucket.Unreplaced>()
            val additions = (data.serverBuckets.keys + data.app.buckets.keys).mapNotNull { start ->
                if (start < current - 24.hours || start > current || start in restStarts) {
                    return@mapNotNull null
                }
                // Every start comes from a server or app bucket, so composition has evidence.
                when (val bucket = composeGraphBucket(data, start)!!) {
                    is GraphComposedBucket.Closed -> bucket.point
                    is GraphComposedBucket.Unreplaced -> {
                        unreplaced[start] = bucket
                        FreeGraphPoint(start, bucket.close, bucket.range.high, bucket.range.low)
                    }
                }
            }
            unreplacedBySeries[series.seriesId] = unreplaced
            series.copy(points = series.points + additions)
        })
    } else graph
    val base = if (period == GraphPeriod.ONE_DAY) GraphPreparedBuilder.build(merged, period) else prepared
    val mergedBySeries = merged.series.associateBy { it.seriesId }

    return base.copy(bySeries = base.bySeries.mapValues { (id, original) ->
        val series = if (original.axisGroup == "index") original.copy(bandPoints = emptyList()) else original
        val data = live[GraphObservationSeriesKey(scope, id)]?.data ?: return@mapValues series
        val lastLine = series.linePoints.lastOrNull() ?: return@mapValues series
        // Clock reversal may leave a REST point ahead of now; never append behind it.
        if (now <= lastLine.ts) return@mapValues series

        val tip = data.app.tip?.takeIf { now - it.observedAt < graphLineFreshness(it.source) }
        var band = series.bandPoints
        val endPoint = if (period == GraphPeriod.ONE_DAY) {
            val unreplaced = unreplacedBySeries.getValue(id)
            val currentBucket = unreplaced[current]
            if (currentBucket != null) {
                if (series.axisGroup != "index") {
                    band = band + BandPoint(now, currentBucket.range.low, currentBucket.range.high)
                }
                LinePoint(now, currentBucket.close)
            } else {
                // The final unreplaced bucket has no neighbour for the builder to collapse toward.
                unreplaced[lastLine.ts]?.let { bucket ->
                    if (series.axisGroup != "index") {
                        band = band + BandPoint(bucket.start + 600.seconds, bucket.close, bucket.close)
                    }
                }
                val points = mergedBySeries.getValue(id).points
                tip?.takeIf { observation ->
                    val tipStart = graphObservationBucketStart(observation.observedAt)
                    points.none { it.timestamp == current } &&
                        (tipStart in unreplaced || points.none { it.timestamp == tipStart })
                }?.let { LinePoint(now, it.rate) }
            }
        } else {
            tip?.let { LinePoint(now, it.rate) }
        }
        val extrema = if (endPoint == null) series.extrema else {
            val range = series.extrema!!
            minOf(range.start, endPoint.rate)..maxOf(range.endInclusive, endPoint.rate)
        }
        series.copy(
            linePoints = series.linePoints + listOfNotNull(endPoint),
            bandPoints = band,
            extrema = extrema,
            lastObservation = if (period == GraphPeriod.ONE_DAY) series.lastObservation else endPoint ?: series.lastObservation
        )
    })
}
