package com.jay.fxi.domain.model

/**
 * Which graph series the free surface draws.
 *
 * Two rules, both from `ANDROID_V2_PLAN.md §7 S2`:
 *
 * - **All-off is a choice.** D18's "project one candidate when the selection resolves to nothing"
 *   applies to source lists, not to graphs, so an empty set is drawn as an empty graph rather than
 *   quietly repopulated. That is only expressible if "the user turned everything off" is
 *   distinguishable from "the user has never chosen", which is what [resolve] takes a nullable
 *   stored set for.
 * - **The tether tab's two dollar indices are mutually exclusive.** `dxy` is the spot index and
 *   `dxy_futures` the futures one; overlaying both says nothing the user asked for. Turning one on
 *   turns the other off — turning one *off* leaves the other alone, so all-off stays reachable.
 */
object FreeSeriesVisibility {

    private const val DXY = "dxy"
    private const val DXY_FUTURES = "dxy_futures"

    /** The mutually exclusive pairs for [tab]. Only the tether tab has any. */
    private fun exclusiveWith(tab: FreeTab, seriesId: String): String? =
        if (tab != FreeTab.TETHER) null else when (seriesId) {
            DXY -> DXY_FUTURES
            DXY_FUTURES -> DXY
            else -> null
        }

    /** A null [stored] set means nothing has been chosen yet; an empty one means all-off. */
    fun resolve(tab: FreeTab, stored: Set<String>?): Set<String> = stored ?: tab.defaultVisibleSeriesIds

    /**
     * Turn [seriesId] on or off, keeping the tab's exclusivity.
     *
     * Returns a new set whose membership for [seriesId] is inverted, so the result always differs
     * from [current].
     */
    fun toggle(tab: FreeTab, current: Set<String>, seriesId: String): Set<String> =
        if (seriesId in current) {
            current - seriesId
        } else {
            (current - setOfNotNull(exclusiveWith(tab, seriesId))) + seriesId
        }
}
