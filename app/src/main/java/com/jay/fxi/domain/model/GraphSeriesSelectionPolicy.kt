package com.jay.fxi.domain.model

/** Pure selection policy; callers own persistence and capability baseline tracking. */
object GraphSeriesSelectionPolicy {
    private const val TETHER_TAB = "tether"
    private const val HANA_USD = "hana.usd"
    private const val KRX_PREFIX = "krx."

    /** A null selection means confirmed record absence, including for the missing-catalog fallback. */
    fun initialize(
        current: GraphSeriesSelection?,
        tab: String,
        universe: GraphSelectionUniverse,
        krxVisible: Boolean
    ): GraphSelectionChange = when (universe) {
        is GraphSelectionUniverse.Catalog -> {
            val eligible = atGate(universe.allSeries, krxVisible)
            val newIds = eligible - current?.initializedSeries.orEmpty()
            if (newIds.isEmpty()) {
                GraphSelectionChange.Unchanged
            } else {
                val defaults = universe.defaultVisible.intersect(eligible).let {
                    if (tab == TETHER_TAB && krxVisible) it - HANA_USD else it
                }
                GraphSelectionChange.Replace(
                    GraphSeriesSelection(
                        visibleSeriesIds = current?.visibleSeriesIds.orEmpty() + newIds.intersect(defaults),
                        initializedSeries = current?.initializedSeries.orEmpty() + newIds
                    )
                )
            }
        }
        is GraphSelectionUniverse.CatalogUnavailable -> {
            val admitted = atGate(universe.admittedSeries, krxVisible)
            if (current == null && admitted.isNotEmpty()) {
                // Without a catalog, only confirmed record absence permits the admitted-id fallback.
                GraphSelectionChange.Replace(GraphSeriesSelection(admitted, admitted))
            } else {
                GraphSelectionChange.Unchanged
            }
        }
        GraphSelectionUniverse.Unsupported -> GraphSelectionChange.Unchanged
    }

    fun toggle(
        current: GraphSeriesSelection,
        tab: String,
        seriesId: String,
        toggleableIds: Set<String>,
        krxVisible: Boolean
    ): GraphSelectionChange {
        if (seriesId !in toggleableIds) {
            return GraphSelectionChange.Rejected("Series is not toggleable")
        }
        if (!krxVisible && seriesId.startsWith(KRX_PREFIX)) {
            return GraphSelectionChange.Rejected("KRX series is hidden by the capability gate")
        }

        val next = if (seriesId in current.visibleSeriesIds) {
            GraphSeriesSelection(
                visibleSeriesIds = current.visibleSeriesIds - seriesId,
                initializedSeries = current.initializedSeries + seriesId
            )
        } else {
            val counterpart = if (tab == TETHER_TAB) {
                when (seriesId) {
                    "dxy" -> "dxy_futures"
                    "dxy_futures" -> "dxy"
                    else -> null
                }
            } else {
                null
            }
            val turnedOff = setOfNotNull(counterpart).intersect(current.visibleSeriesIds)
            GraphSeriesSelection(
                visibleSeriesIds = (current.visibleSeriesIds - turnedOff) + seriesId,
                initializedSeries = current.initializedSeries + turnedOff + seriesId
            )
        }
        return GraphSelectionChange.Replace(next)
    }

    /** The first capability baseline (null) is not a flip. Reset only the tether hana/KRX pair. */
    fun resetKrxPairOnFlip(
        current: GraphSeriesSelection?,
        tab: String,
        previousVisible: Boolean?,
        nextVisible: Boolean
    ): GraphSelectionChange {
        if (current == null || tab != TETHER_TAB || previousVisible == null || previousVisible == nextVisible) {
            return GraphSelectionChange.Unchanged
        }
        val pair = (current.visibleSeriesIds + current.initializedSeries).filterTo(linkedSetOf()) {
            it == HANA_USD || it.startsWith(KRX_PREFIX)
        }
        if (pair.isEmpty()) return GraphSelectionChange.Unchanged
        return GraphSelectionChange.Replace(
            GraphSeriesSelection(
                visibleSeriesIds = current.visibleSeriesIds - pair,
                initializedSeries = current.initializedSeries - pair
            )
        )
    }

    /** Projection only: preserve raw ids from other periods and allow an empty rendered selection. */
    fun renderedIds(
        current: GraphSeriesSelection,
        admittedPeriodIds: Set<String>,
        krxVisible: Boolean
    ): Set<String> = atGate(current.visibleSeriesIds.intersect(admittedPeriodIds), krxVisible)

    private fun atGate(ids: Set<String>, krxVisible: Boolean): Set<String> =
        ids.filterTo(linkedSetOf()) { krxVisible || !it.startsWith(KRX_PREFIX) }
}
