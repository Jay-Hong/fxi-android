package com.jay.fxi.macrobenchmark

/**
 * S5-4 E slice 5: how a journey reads the screen for its before and after records, free of Android so the host harness tests
 * it. One poll reads the premium tabs, the period tabs and the inline charts once each, in that order, into a fresh snapshot.
 * A read that returns null was not observed (a node replaced mid-read). When a read throws, the fields this poll already read
 * go to the record first and then the same error goes on; nothing is read again to describe a failure. The completion wait
 * compares each snapshot and the record keeps that same object: the matching one on success, the last one on a timeout.
 */
internal object S5Poll {
    /** One poll's reads, filled in order; a field not (yet) read is null. */
    class Snapshot {
        var tabs: List<Pair<String, Boolean>>? = null
        var periods: List<Pair<String, Boolean>>? = null
        var charts: Int? = null
        var labels: List<String>? = null
        var chartRead = false

        fun checked(tag: String) = tabs?.singleOrNull { it.first == tag }?.second == true
        fun selected(tag: String) = periods?.singleOrNull { it.first == tag }?.second == true
        fun state(scroll: String?) = S5Behavior.State(
            tab = tabs?.filter { it.second }?.singleOrNull()?.first?.removePrefix("premium_tab_"),
            period = periods?.filter { it.second }?.singleOrNull()?.first?.removePrefix("period_tab:"),
            ready = if (chartRead) labels != null else null,
            seriesShown = labels?.size,
            scroll = scroll
        )
    }

    /** The screen reads of one poll, in the order they run; null means not observed. */
    class Reads<C>(
        val tabs: () -> List<Pair<String, Boolean>>?,
        val periods: () -> List<Pair<String, Boolean>>?,
        val charts: () -> List<C>?,
        /** The description of the one chart found; asked only when exactly one was. */
        val description: (C) -> String?,
        val labelsOf: (String) -> List<String>?
    )

    /** One poll; [keep] gets the snapshot when it is complete, or as far as it got when a read throws. */
    fun <C> poll(reads: Reads<C>, keep: (Snapshot) -> Unit): Snapshot {
        val shot = Snapshot()
        try {
            shot.tabs = reads.tabs()
            shot.periods = reads.periods()
            val charts = reads.charts()
            shot.charts = charts?.size
            if (charts?.size == 1) {
                val text = reads.description(charts.single())
                shot.labels = text?.let(reads.labelsOf)
                shot.chartRead = text != null
            } else {
                shot.chartRead = charts != null
            }
        } catch (error: Throwable) {
            keep(shot)
            throw error
        }
        keep(shot)
        return shot
    }

    /**
     * Polls at least once, until [matches] or, after a snapshot that did not match, [expired]. Returns whether it matched and
     * the snapshot it compared last.
     */
    fun <C> await(reads: Reads<C>, keep: (Snapshot) -> Unit, matches: (Snapshot) -> Boolean, expired: () -> Boolean,
                  pause: () -> Unit): Pair<Boolean, Snapshot> {
        while (true) {
            val shot = poll(reads, keep)
            if (matches(shot)) return true to shot
            if (expired()) return false to shot
            pause()
        }
    }

    /** The before stage: one poll into the record, then the scroll position, read last. */
    fun <C> before(observation: S5Behavior.Observation, reads: Reads<C>, scroll: () -> String) {
        val shot = poll(reads) { observation.before = it.state(null) }
        observation.before = shot.state(scroll())
    }

    /** What the after stage keeps from a snapshot: the chart count among the lookups and the after state. */
    fun keepAfter(observation: S5Behavior.Observation, shot: Snapshot, scroll: String?) {
        observation.lookup(S5Behavior.CHART, shot.charts)
        observation.after = shot.state(scroll)
    }

    /** Reads the anchor once, keeps the position it shows, and returns that same read for the caller's assertion. */
    fun afterScroll(readAnchor: () -> Int, pageTop: Int, keep: (String) -> Unit): Int {
        val top = readAnchor()
        keep(if (top == pageTop) "top" else "moved")
        return top
    }
}
