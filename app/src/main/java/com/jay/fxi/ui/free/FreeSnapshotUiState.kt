package com.jay.fxi.ui.free

import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.domain.model.Exchange
import com.jay.fxi.domain.model.ExchangeRate
import com.jay.fxi.domain.model.FreeGraphPoint
import com.jay.fxi.domain.model.FreeGraphSeries
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

/** One entry of the graph's series switch. Absent from the snapshot means absent from the row. */
data class FreeSeriesToggle(val seriesId: String, val label: String, val visible: Boolean)

/**
 * One series, in its own units, plus the frame it shares with the others on its axis.
 *
 * The points are *not* normalized here. They used to be, each series against its own extremes,
 * which made two series in one frame incomparable and left no way to express a zoom at all — the
 * timestamps and the highs and lows were gone by the time anything could ask for them.
 */
data class FreeSnapshotChart(
    val seriesId: String,
    val label: String,
    val points: List<FreeGraphPoint>,
    /** The frame it is drawn in: time shared with every series, value shared with its own axis. */
    val domain: FreeChartDomain,
    /**
     * This series' own observed extremes.
     *
     * Not the frame's. A near-flat line sharing an axis with a volatile one is drawn against the
     * volatile one's range, but saying so in its caption — and to a screen reader — would report
     * numbers this series never reached.
     */
    val low: String,
    val high: String,
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
    val charts: List<FreeSnapshotChart> = emptyList(),
    /** Every series this answer carries, whether drawn or not — the row has to offer both. */
    val seriesToggles: List<FreeSeriesToggle> = emptyList()
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
        /**
         * 1일, as on iOS and as on this app's own premium surface (`GraphViewModel.kt:66`).
         *
         * The free surface opened on 3달 while everything else opened on 1일 — an inconsistency
         * rather than a decision; nothing recorded a reason for it. It also decided which period
         * the scheduler fetches first, and 1일 is the only period that will carry zoom.
         */
        val DEFAULT_PERIOD = GraphPeriod.ONE_DAY

        internal fun from(
            uid: String,
            tab: FreeTab,
            period: GraphPeriod,
            readState: FreeSnapshotReadState,
            visibleSeriesIds: Set<String>
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
            val series = entry.snapshot.graph.series
            return state.copy(
                rateSections = sections(entry.snapshot.rate),
                // All-off draws an empty graph. `ANDROID_V2_PLAN.md:826` allows it outright, and
                // D18's "project one candidate rather than show nothing" is a rule for source
                // lists, not for graphs — repopulating here would overrule a real choice.
                charts = charts(series, visibleSeriesIds),
                seriesToggles = series.map {
                    FreeSeriesToggle(it.seriesId, it.label, it.seriesId in visibleSeriesIds)
                }
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

        /**
         * One chart per drawn series, each carrying the frame shared by everything on its axis.
         *
         * Grouping by axis is what keeps a KRW rate near 1,400 and a dollar index near 99 from
         * being forced onto one scale, which would flatten both.
         */
        private fun charts(
            series: List<FreeGraphSeries>,
            visibleSeriesIds: Set<String>
        ): List<FreeSnapshotChart> {
            val drawn = series.filter { it.seriesId in visibleSeriesIds && it.points.isNotEmpty() }
            val frames = FreeChartGeometry.frames(drawn)
            return drawn.mapNotNull { item ->
                val frame = frames[item.axisGroup] ?: return@mapNotNull null
                val own = FreeChartGeometry.extremesOf(item.points) ?: return@mapNotNull null
                val decimals = (item.decimals ?: 2).coerceIn(0, 8)
                val unit = item.unit?.let { " $it" }.orEmpty()
                fun format(value: Double) = String.format(Locale.KOREA, "%.${decimals}f", value) + unit
                FreeSnapshotChart(
                    seriesId = item.seriesId,
                    label = item.label,
                    points = item.points.sortedBy { it.timestamp },
                    domain = frame,
                    low = format(own.start),
                    high = format(own.endInclusive),
                    start = dateFormatter.format(frame.start.toJavaInstant()),
                    end = dateFormatter.format(frame.end.toJavaInstant())
                )
            }
        }
    }
}
