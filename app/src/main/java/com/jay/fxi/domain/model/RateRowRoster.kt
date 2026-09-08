package com.jay.fxi.domain.model

/**
 * A list of quotes the user could reorder or hide as a unit.
 *
 * Two, because two are what anyone would rearrange. The tether tab's USD/KRW headings hold two
 * banks and one reference quote, and neither is a list you sort — the reference is the reference by
 * position, not by preference. Naming scopes nobody edits would give the store keys with no writer.
 */
enum class RateRowList {
    FX_BANKS,
    TETHER_EXCHANGES;

    /**
     * The name the store writes down.
     *
     * Separate from [name] on purpose, the same way `FreeTab.storageValue` is: renaming or
     * reordering the constants must not silently orphan what is already on disk.
     */
    val storageValue: String get() = name
}

/**
 * What one user has said about one list. Either half may be absent, and absent is not empty.
 *
 * [order] absent means "no opinion — draw them in the order they arrived". Recording an order the
 * user never asked for would freeze the list as it happened to be on the day they hid something,
 * and a source the server added later would land at the end for good.
 *
 * [hidden] absent means the defaults apply. An empty set is an opinion of its own: show everything,
 * including what the defaults hide.
 */
data class RateRowPreference(
    val order: List<String>? = null,
    val hidden: Set<String>? = null
)

/** What a list draws, and whether any of it is there only because the alternative was nothing. */
data class RateRowSelection(
    val codes: List<String>,
    /**
     * The row D18 rescued, if one was needed.
     *
     * It is not a change to what the user stored, which is why it travels beside [codes] instead of
     * being folded into them — the sheet shows it as temporary, and nothing saves it back.
     */
    val projected: String? = null
)

/**
 * Who is on the list, and in what order.
 *
 * `SourcePreferenceManager`'s job on iOS. The stored half comes from `RateRowPreferenceStore`; the
 * defaults and the projection both halves go through live here, with no Android or storage types in
 * sight so the rules can be checked without a device.
 */
object RateRowRoster {

    /**
     * Codes a fresh install does not show. Everything else on the wire is shown.
     *
     * Citi is the only one, by `ANDROID_V2_PLAN.md:813` — "Citi는 신규 표시 기본에서 제외". It is a
     * default, not a ban: the sanitizer admits Citi deliberately (`FreeSnapshotSanitizer.kt:178`)
     * and the sheet can turn it back on.
     */
    fun hiddenByDefault(list: RateRowList): Set<String> = when (list) {
        RateRowList.FX_BANKS -> setOf("citi")
        RateRowList.TETHER_EXCHANGES -> emptySet()
    }

    /**
     * The codes to draw, in order.
     *
     * D18: eligible is the visible set intersected with what actually arrived. Order is the user's
     * where they have given one, and a code they have never seen is appended in the order it
     * arrived rather than dropped — the sanitizer already decided what may be shown, and a source
     * this app has not been taught about is still a real quote.
     *
     * **The rescue does not change what is stored.** When everything eligible is hidden, one
     * default-visible row is drawn so the heading is not left empty, and it comes back as
     * [RateRowSelection.projected] rather than folded into the codes as though it had been chosen.
     * If not even a default-visible row arrived, nothing is drawn — putting a hidden-by-default row
     * back would say the opposite of what the default says, and that case is reachable: the
     * sanitizer drops entries one at a time, so a payload can hold Citi alone.
     */
    fun effective(
        list: RateRowList,
        present: List<String>,
        preference: RateRowPreference? = null
    ): RateRowSelection {
        val hidden = hidden(list, preference)
        val ordered = arrangement(list, present, preference)
        val eligible = ordered.filterNot { it in hidden }
        if (eligible.isNotEmpty()) return RateRowSelection(eligible)

        val rescued = ordered.firstOrNull { it !in hiddenByDefault(list) }
            ?: return RateRowSelection(emptyList())
        return RateRowSelection(listOf(rescued), projected = rescued)
    }

    /**
     * Every code that arrived, in the order the list draws them — **hidden ones included**.
     *
     * What [effective] shows is a filter over this. The editing sheet needs the unfiltered version,
     * because a row you cannot see is exactly the row you opened the sheet to turn back on.
     *
     * The user's order is honoured, and anything they have not seen keeps the place it arrived in.
     * Appending rather than inserting is the honest choice: a stored order says nothing about where
     * a code nobody has arranged belongs, and guessing a neighbour for it would move rows the user
     * did arrange.
     */
    fun arrangement(
        list: RateRowList,
        present: List<String>,
        preference: RateRowPreference? = null
    ): List<String> {
        val stored = preference?.order ?: return present
        val arrived = present.toSet()
        return stored.filter { it in arrived } + present.filterNot { it in stored }
    }

    /** What this user hides on this list — their own set when they have one, the defaults otherwise. */
    fun hidden(list: RateRowList, preference: RateRowPreference? = null): Set<String> =
        preference?.hidden ?: hiddenByDefault(list)
}
