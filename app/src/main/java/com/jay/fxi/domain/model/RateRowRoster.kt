package com.jay.fxi.domain.model

/**
 * A list of quotes the user could reorder or hide as a unit.
 *
 * Two, because two are what anyone would rearrange. The tether tab's USD/KRW headings hold two
 * banks and one reference quote, and neither is a list you sort — the reference is the reference by
 * position, not by preference. Naming scopes nobody edits would give the store keys with no writer.
 */
enum class RateRowList { FX_BANKS, TETHER_EXCHANGES }

/**
 * Who is on the list, and in what order.
 *
 * This is `SourcePreferenceManager`'s job on iOS, split in two here. **The stored half — the user's
 * own order and their hidden set — arrives with the sheet that writes it**; a store with no writer
 * would be a keyspace nobody could observe, and the read path would be a constant. What lands now
 * is the half that has an answer today: the defaults, and the projection every caller goes through
 * so the stored half has one seam to appear at rather than five.
 *
 * Order is therefore the server's for the moment. That is the honest default and not a placeholder:
 * with nothing stored there is no user order to honour, and inventing one would rearrange rows
 * nobody asked to rearrange.
 */
object RateRowRoster {

    /**
     * Codes a fresh install does not show. Everything else on the wire is shown.
     *
     * Citi is the only one, by `ANDROID_V2_PLAN.md:800` — "Citi는 신규 표시 기본에서 제외". It is a
     * default, not a ban: the sanitizer admits Citi deliberately (`FreeSnapshotSanitizer.kt:178`)
     * and the sheet will be able to turn it back on. Absent from this map means nothing is hidden.
     */
    fun hiddenByDefault(list: RateRowList): Set<String> = when (list) {
        RateRowList.FX_BANKS -> setOf("citi")
        RateRowList.TETHER_EXCHANGES -> emptySet()
    }

    /**
     * The codes to draw, in order.
     *
     * D18: what is eligible is the visible set intersected with what actually arrived. A code that
     * arrived but is not on the list is kept rather than dropped — the sanitizer already decided
     * what may be shown, and a source this app has not been taught about is still a real quote.
     * Dropping it would take a number off the screen with nothing left to notice the loss.
     *
     * **D18's rescue is not here, and cannot be yet.** It projects one *default-visible* candidate
     * when the user has hidden everything — so it needs a stored hidden set to be distinct from the
     * defaults. Today they are the same thing, which means the only way `eligible` empties is that
     * every code which arrived is hidden *by default*, and then there is no default-visible
     * candidate to rescue. Falling back to the first code that arrived would put Citi back on
     * screen, which is the opposite of what the default says: reviewed, and reachable — the
     * sanitizer drops entries one at a time, so a payload can arrive holding Citi alone. An empty
     * section is the honest answer until the sheet can say otherwise, and the slice that adds the
     * store owns the rescue.
     */
    fun effective(list: RateRowList, present: List<String>): List<String> {
        val hidden = hiddenByDefault(list)
        return present.filterNot { it in hidden }
    }
}
