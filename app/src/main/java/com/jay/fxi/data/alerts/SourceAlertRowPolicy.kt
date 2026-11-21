package com.jay.fxi.data.alerts

import com.jay.fxi.domain.model.SourceAlertSetting

/**
 * Which source alert rows an owner adopts, which rows a tab shows, and what counts toward the cap. Pure: no state, no I/O.
 *
 * A row is adopted only when its user is the request's user and its exact (source, asset) pair is allowed; no case folding
 * or trimming. An id the answer carries more than once is dropped with every one of its rows, an allowed row included, so a
 * conflicting answer can neither be adopted nor written to; the next regular canonical GET decides again. Dropped rows are
 * kept nowhere. S8-5a: a draft's or a patch's target pair is judged by the same exact test ([admits]), and the editor's picker
 * lists the same pairs in the policy's order ([sources]); the pairs are defined here only.
 */
internal class SourceAlertRowPolicy(private val allowed: Set<Pair<String, String>>) {
    fun adopt(rows: List<SourceAlertSetting>, uid: String): List<SourceAlertSetting> {
        val repeated = rows.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        return rows.filter { it.id !in repeated && it.userId == uid && admits(it.source, it.asset) }
    }

    /** Whether ([source], [asset]) is exactly one of the allowed pairs: no case folding or trimming, as in [adopt]. */
    fun admits(source: String, asset: String): Boolean = (source to asset) in allowed

    /** The sources allowed with [asset], in the policy's order. */
    fun sources(asset: String): List<String> = allowed.filter { it.second == asset }.map { it.first }

    /** The adopted rows of exactly [asset], in order. */
    fun project(rows: List<SourceAlertSetting>, asset: String): List<SourceAlertSetting> = rows.filter { it.asset == asset }

    /** The cap's input: distinct ids of the adopted rows, enabled or not, once or repeating. */
    fun count(rows: List<SourceAlertSetting>): Int = rows.mapTo(mutableSetOf()) { it.id }.size

    companion object {
        /** The one asset the settings GET asks for. */
        const val SETTINGS_ASSET = "usdt-krw"

        /** The five exchanges, each with [SETTINGS_ASSET] only. */
        val EXCHANGES = SourceAlertRowPolicy(
            setOf("upbit", "bithumb", "coinone", "korbit", "gopax").mapTo(mutableSetOf()) { it to SETTINGS_ASSET }
        )
    }
}
