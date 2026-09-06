package com.jay.fxi.ui.free

import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.GraphPeriod
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.datetime.Instant

/** Missing entries carry no request status: neither a spinner nor an error can be inferred. */
enum class FreeSnapshotAvailability { AWAITING_SNAPSHOT, FRESH, DELAYED, UNAVAILABLE }

data class FreeSnapshotRateRow(val source: String, val value: String)

/** Normalized coordinates keep chart calculation off the Compose path and JVM-testable. */
data class FreeSnapshotChartPoint(val x: Float, val y: Float)

data class FreeSnapshotChart(
    val label: String,
    val points: List<FreeSnapshotChartPoint>,
    val minimum: String,
    val maximum: String,
    val start: String,
    val end: String
)

data class FreeSnapshotUiState(
    val uid: String? = null,
    val key: FreeSnapshotKey = DEFAULT_KEY,
    val availability: FreeSnapshotAvailability = FreeSnapshotAvailability.AWAITING_SNAPSHOT,
    val asOfLabel: String? = null,
    val rates: List<FreeSnapshotRateRow> = emptyList(),
    val charts: List<FreeSnapshotChart> = emptyList()
) {
    /** Also used at render time, before the effect binding a changed UID has run. */
    fun forOwner(uid: String): FreeSnapshotUiState =
        if (this.uid == uid) this else FreeSnapshotUiState(uid = uid)

    companion object {
        val DEFAULT_KEY = FreeSnapshotKey("usd", GraphPeriod.THREE_MONTHS)

        internal fun from(
            uid: String,
            key: FreeSnapshotKey,
            readState: FreeSnapshotReadState
        ): FreeSnapshotUiState {
            val empty = FreeSnapshotUiState(uid = uid, key = key)
            if (readState.uid != uid) return empty
            val entry = readState.entries[key] ?: return empty
            val availability = when (entry.freshness) {
                FreeSnapshotFreshness.FRESH -> FreeSnapshotAvailability.FRESH
                FreeSnapshotFreshness.DELAYED -> FreeSnapshotAvailability.DELAYED
                FreeSnapshotFreshness.UNAVAILABLE -> FreeSnapshotAvailability.UNAVAILABLE
            }
            val state = empty.copy(
                availability = availability,
                asOfLabel = basisFormatter.format(entry.snapshot.asOf.toJavaInstant())
            )
            // Expired values must not survive in the presentation state, including chart points.
            if (availability == FreeSnapshotAvailability.UNAVAILABLE) return state
            return state.copy(
                rates = (entry.snapshot.rate as? FreeRate.Flat)?.entries.orEmpty().map { rate ->
                    FreeSnapshotRateRow(rate.bankType?.displayName ?: rate.bank, rate.formattedRate)
                },
                charts = charts(entry.snapshot)
            )
        }

        private val zone = ZoneId.of("Asia/Seoul")
        private val basisFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'KST'").withZone(zone)
        private val dateFormatter = DateTimeFormatter.ofPattern("MM.dd").withZone(zone)

        private fun Instant.toJavaInstant() = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

        private fun charts(snapshot: FreeSnapshot): List<FreeSnapshotChart> = snapshot.graph.series.mapNotNull { series ->
            val points = series.points
            if (points.isEmpty()) return@mapNotNull null
            val first = points.minOf { it.timestamp }
            val last = points.maxOf { it.timestamp }
            val duration = (last - first).inWholeMilliseconds.toDouble()
            val minimum = points.minOf { it.rate }
            val maximum = points.maxOf { it.rate }
            val spread = maximum - minimum
            val decimals = (series.decimals ?: 2).coerceIn(0, 8)
            val unit = series.unit?.let { " $it" }.orEmpty()
            FreeSnapshotChart(
                label = series.label,
                points = points.sortedBy { it.timestamp }.map { point ->
                    FreeSnapshotChartPoint(
                        x = if (duration == 0.0) 0.5f else ((point.timestamp - first).inWholeMilliseconds / duration).toFloat(),
                        y = if (spread == 0.0) 0.5f else (1.0 - (point.rate - minimum) / spread).toFloat()
                    )
                },
                minimum = String.format(Locale.KOREA, "%.${decimals}f", minimum) + unit,
                maximum = String.format(Locale.KOREA, "%.${decimals}f", maximum) + unit,
                start = dateFormatter.format(first.toJavaInstant()),
                end = dateFormatter.format(last.toJavaInstant())
            )
        }
    }
}
