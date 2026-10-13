package com.jay.fxi.s5fixture

import com.jay.fxi.data.local.GraphSelectionAudience
import com.jay.fxi.data.local.GraphSelectionKey
import com.jay.fxi.data.local.GraphSelectionReadResult
import com.jay.fxi.data.local.GraphSelectionWriteResult
import com.jay.fxi.domain.model.GraphSeriesSelection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** S5-4 slice 4b: where the fixture's tether selection lives: the fixture user's PREMIUM tether key. */
internal val S5_FIXTURE_SELECTION_KEY = GraphSelectionKey(S5FixtureIdentity.UID, GraphSelectionAudience.PREMIUM, "tether")

/** The bundle's frozen selection seed (assets bundle.json `selection`). */
internal fun bundleSelection(bundle: ByteArray): GraphSeriesSelection {
    val selection = Json.parseToJsonElement(bundle.decodeToString()).jsonObject.getValue("selection").jsonObject
    fun ids(field: String) = selection.getValue(field).jsonArray.map { it.jsonPrimitive.content }.toSet()
    return GraphSeriesSelection(ids("visible_series_ids"), ids("initialized_series"))
}

/**
 * Writes [selection] under the fixture key and reads it back: true only when the write committed and the confirmed record is
 * exactly that key and selection. The write and the confirmation are the selection store's own operations.
 */
internal suspend fun seedSelection(
    selection: GraphSeriesSelection,
    write: suspend (GraphSelectionKey, GraphSeriesSelection) -> GraphSelectionWriteResult,
    confirm: suspend (GraphSelectionKey) -> GraphSelectionReadResult
): Boolean {
    val key = S5_FIXTURE_SELECTION_KEY
    if (write(key, selection) != GraphSelectionWriteResult.Committed) return false
    val record = (confirm(key) as? GraphSelectionReadResult.Present)?.record ?: return false
    return record.uid == key.uid && record.audience == key.audience && record.tab == key.tab &&
        record.visibleSeriesIds == selection.visibleSeriesIds && record.initializedSeries == selection.initializedSeries
}
