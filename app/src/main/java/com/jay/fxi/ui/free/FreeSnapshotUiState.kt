package com.jay.fxi.ui.free

import com.jay.fxi.data.free.FreeSnapshotFreshness
import com.jay.fxi.data.free.FreeSnapshotKey
import com.jay.fxi.data.free.FreeSnapshotReadState
import com.jay.fxi.domain.model.FreeRate
import com.jay.fxi.domain.model.FreeSnapshot
import com.jay.fxi.domain.model.FreeTab
import com.jay.fxi.domain.model.GraphPeriod
import com.jay.fxi.ui.graph.GraphPreparedBuilder
import com.jay.fxi.ui.graph.GraphSeriesStyles
import com.jay.fxi.ui.graph.PreparedGraph
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import com.jay.fxi.domain.model.RateRowRoster
import com.jay.fxi.ui.rates.RateRowPresenter
import com.jay.fxi.ui.rates.editableRosters
import com.jay.fxi.ui.rates.RateRowsView
import com.jay.fxi.ui.rates.asRateScales
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.datetime.Instant

/** Missing entries carry no request status: neither a spinner nor an error can be inferred. */
enum class FreeSnapshotAvailability { AWAITING_SNAPSHOT, FRESH, DELAYED, UNAVAILABLE }

/**
 * A titled group of rows, as it will be drawn.
 *
 * The FX tabs send one group and the tether tab sends three, which is the shape the server already
 * has: `FreeRate.Grouped` is not a flat list with extras, it is exchanges, banks and a single
 * reference quote that mean different things beside each other. Flattening them would put 업비트 and
 * 하나은행 in one column under one heading and lose what the tab is for.
 *
 * The rows keep their numbers. An earlier shape carried `(source: String, value: String)`, which
 * lost the value, the observation time and the source's identity somewhere between the snapshot and
 * the Canvas — so the screen could only print two columns of text, and every decision
 * `RateRowPresenter` makes had nowhere to land.
 */
typealias FreeSnapshotRateSection = RateRowsView

/**
 * One row as the editing sheet shows it.
 *
 * Hidden rows are here and nowhere else on the surface — a row you cannot see is exactly the row
 * you open the sheet to turn back on.
 */
data class RateRowEditEntry(
    val code: String,
    val label: String,
    val visible: Boolean,
    /**
     * Drawn only because hiding everything would have left the heading empty — D18's rescue.
     *
     * Shown as temporary rather than as a choice, and never written back: the user's own set still
     * says hidden, which is what "원본은 바꾸지 않고" asks for.
     */
    val projected: Boolean = false
)

/** One list the user can rearrange, with everything that arrived for it. */
data class RateRowEditor(
    val list: RateRowList,
    val title: String,
    val entries: List<RateRowEditEntry>
)

/** One entry of the graph's series switch. Absent from the snapshot means absent from the row. */
data class FreeSeriesToggle(val seriesId: String, val label: String, val visible: Boolean)

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
    /** Draw-ready geometry for every series in the answer, hidden ones included. */
    val graph: PreparedGraph? = null,
    /** Which of them are drawn. The chart needs both: the frame spans all, the axes span these. */
    val visibleSeriesIds: Set<String> = emptySet(),
    /** Every series this answer carries, whether drawn or not — the row has to offer both. */
    val seriesToggles: List<FreeSeriesToggle> = emptyList(),
    /** The lists this tab lets the user rearrange. Empty on 뉴스 and before a snapshot lands. */
    val rowEditors: List<RateRowEditor> = emptyList()
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
            visibleSeriesIds: Set<String>,
            rowPreferences: Map<RateRowList, RateRowPreference> = emptyMap()
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
            // Built over the whole answer, hidden series included: the time axis spans all of
            // them, so filtering here would let a toggle slide the chart sideways. All-off then
            // draws an empty frame, which `ANDROID_V2_PLAN.md:839` allows outright — D18's
            // "project one candidate rather than show nothing" is a rule for source lists.
            val graph = GraphPreparedBuilder.build(entry.snapshot.graph, period)
            return state.copy(
                rateSections = sections(entry.snapshot.rate, rowPreferences),
                rowEditors = editors(entry.snapshot.rate, rowPreferences),
                graph = graph,
                visibleSeriesIds = visibleSeriesIds,
                seriesToggles = graph.order.mapNotNull { id ->
                    graph.bySeries[id]?.let {
                        FreeSeriesToggle(id, GraphSeriesStyles.of(id, it.label).label, id in visibleSeriesIds)
                    }
                }
            )
        }

        /**
         * Empty groups are dropped rather than shown as headings with nothing under them.
         *
         * Each scale is presented on its own, which is the point: the tether tab's exchanges are
         * USDT/KRW and its two USD/KRW headings share one ruler with each other.
         */
        private fun sections(
            rate: FreeRate,
            preferences: Map<RateRowList, RateRowPreference>
        ): List<FreeSnapshotRateSection> {
            // A list the user can still open keeps its heading even when it draws nothing, because
            // the heading is where the button that reopens the sheet lives. Drop it and someone who
            // hid everything is left with a sentence and no way back.
            //
            // "Can still open" means the payload actually carried rows for it. A heading with
            // nothing behind it and nothing to edit is noise, and that is the empty-answer case.
            val openable = rate.editableRosters().filterValues { it.isNotEmpty() }.keys
            return rate.asRateScales(preferences).flatMap { RateRowPresenter.present(it) }
                .filter { it.rows.isNotEmpty() || it.list in openable }
        }

        /**
         * What the sheet edits, built from the payload rather than from the sections.
         *
         * A hidden row is not in a section, so a sheet built from sections could never show it —
         * and then nothing could ever be turned back on.
         */
        private fun editors(
            rate: FreeRate,
            preferences: Map<RateRowList, RateRowPreference>
        ): List<RateRowEditor> = rate.editableRosters().mapNotNull { (list, quotes) ->
            if (quotes.isEmpty()) return@mapNotNull null
            val preference = preferences[list]
            val hidden = RateRowRoster.hidden(list, preference)
            val projected = RateRowRoster.effective(list, quotes.map { it.id }, preference).projected
            val byCode = quotes.associateBy { it.id }
            val entries = RateRowRoster.arrangement(list, quotes.map { it.id }, preference)
                .mapNotNull { code ->
                    byCode[code]?.let {
                        RateRowEditEntry(code, it.label, code !in hidden, code == projected)
                    }
                }
            RateRowEditor(list, list.editorTitle, entries)
        }

        /** What the sheet is called. The heading it edits, not the type's name. */
        private val RateRowList.editorTitle: String
            get() = when (this) {
                RateRowList.FX_BANKS -> "은행 순서 설정"
                RateRowList.TETHER_EXCHANGES -> "거래소 순서 설정"
            }

        private val zone = ZoneId.of("Asia/Seoul")
        private val basisFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'KST'").withZone(zone)
        private val dateFormatter = DateTimeFormatter.ofPattern("MM.dd").withZone(zone)

        private fun Instant.toJavaInstant() = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

    }
}
