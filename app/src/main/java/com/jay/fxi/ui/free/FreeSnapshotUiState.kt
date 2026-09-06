package com.jay.fxi.ui.free

import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.domain.model.SourceRate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.datetime.Instant

/** Missing entries carry no request status: neither a spinner nor an error can be inferred. */
enum class FreeSnapshotAvailability { AWAITING_SNAPSHOT, FRESH, DELAYED, UNAVAILABLE }

data class FreeSnapshotRateRow(val source: String, val value: String)

/**
 * A titled group of rows.
 *
 * The FX tabs have one group and the tether tab has three, which is the shape the server already
 * sends: `FreeRate.Grouped` is not a flat list with extras, it is exchanges, banks and a single
 * reference quote that mean different things beside each other. Flattening them would put 업비트
 * and 하나은행 in one column under one heading and lose what the tab is for.
 */
data class FreeSnapshotRateSection(val title: String, val rows: List<FreeSnapshotRateRow>)

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
    /**
     * Null until the stored selection has been read.
     *
     * The distinction is load-bearing rather than cosmetic: [dataKey] is what the surface activates
     * the scheduler for, and defaulting to 달러 here would fire a request for 달러 on the way to a
     * user whose stored tab is 테더 — one wasted round trip on every cold start, against an
     * endpoint whose whole point is that clients ask for it rarely.
     */
    val selectedTab: FreeTab? = null,
    val period: GraphPeriod = DEFAULT_PERIOD,
    val availability: FreeSnapshotAvailability = FreeSnapshotAvailability.AWAITING_SNAPSHOT,
    val asOfLabel: String? = null,
    val rateSections: List<FreeSnapshotRateSection> = emptyList(),
    val charts: List<FreeSnapshotChart> = emptyList()
) {
    /**
     * What the scheduler would be activated for.
     *
     * Null on 뉴스, and null before the stored selection has been read. Both mean the same thing to
     * the one caller that acts on it — run nothing — which is why they need no separate states.
     */
    val dataKey: FreeSnapshotKey?
        get() = selectedTab?.serverTab?.let { FreeSnapshotKey(it, period) }

    /** Also used at render time, before the effect binding a changed UID has run. */
    fun forOwner(uid: String): FreeSnapshotUiState =
        if (this.uid == uid) this else FreeSnapshotUiState(uid = uid)

    companion object {
        val DEFAULT_PERIOD = GraphPeriod.THREE_MONTHS

        internal fun from(
            uid: String,
            tab: FreeTab,
            period: GraphPeriod,
            readState: FreeSnapshotReadState
        ): FreeSnapshotUiState {
            val empty = FreeSnapshotUiState(uid = uid, selectedTab = tab, period = period)
            // 뉴스 has no snapshot of its own, so there is nothing here that could be stale,
            // delayed or expired — the neutral state is the whole of it.
            val key = empty.dataKey ?: return empty
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
                rateSections = sections(entry.snapshot.rate),
                charts = charts(entry.snapshot)
            )
        }

        /** Empty groups are dropped rather than shown as headings with nothing under them. */
        private fun sections(rate: FreeRate): List<FreeSnapshotRateSection> = when (rate) {
            is FreeRate.Flat -> listOf(FreeSnapshotRateSection("은행별 환율", rate.entries.map { it.row() }))
            is FreeRate.Grouped -> listOf(
                FreeSnapshotRateSection("거래소 USDT/KRW", rate.usdtKrw.map { it.row() }),
                FreeSnapshotRateSection("은행 USD/KRW", rate.usdKrwBanks.map { it.row() }),
                FreeSnapshotRateSection("기준 USD/KRW", listOfNotNull(rate.usdKrwReference?.row()))
            )
        }.filter { it.rows.isNotEmpty() }

        private fun ExchangeRate.row() = FreeSnapshotRateRow(bankType?.displayName ?: bank, formattedRate)

        private fun SourceRate.row() = FreeSnapshotRateRow(
            Exchange.fromCode(source)?.displayName ?: source,
            ExchangeRate.formatRate(rate, asset)
        )

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
