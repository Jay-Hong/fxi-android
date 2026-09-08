package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.domain.model.FreeTab
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Which graph series the free surface draws, per tab.
 *
 * An absent tab entry uses the tab's default. A stored empty string decodes to an empty set,
 * preserving all-off without a separate initialized flag.
 */
interface FreeVisibleSeriesStore {
    /**
     * Every tab this UID has chosen for. A tab absent from the map has never been chosen for; a
     * tab mapped to an empty set is all-off.
     *
     * Read whole rather than per tab so a tab switch stays synchronous — one read returns the
     * whole preferences object regardless, and a per-tab read would put a suspension in front of
     * every switch for nothing.
     */
    suspend fun visibleSeries(uid: String): Map<FreeTab, Set<String>>

    suspend fun remember(uid: String, tab: FreeTab, seriesIds: Set<String>)
}

/**
 * A file of its own.
 *
 * `ANDROID_V2_PLAN.md:839` puts the free preference in a namespace separate from the premium one,
 * and D27 wants backup-eligible user intent kept physically apart from control-plane state. This
 * holds neither entitlement nor capability data, so it is ordinary preference material.
 */
private val Context.freeGraphDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fxi_free_graph"
)

/**
 * How a chosen set is written down, kept out of the DataStore plumbing so it can be tested.
 *
 * This codec maps stored strings to sets and preserves an empty string as an empty set. The store
 * detects absent keys, and `FreeSeriesVisibility.resolve` applies defaults to absent choices.
 */
internal object FreeVisibleSeriesCodec {
    const val SEPARATOR = "\n"

    fun encode(seriesIds: Set<String>): String {
        require(seriesIds.none { it.contains(SEPARATOR) }) { "series id must not contain a newline" }
        // Sorted so an unchanged choice is written identically every time.
        return seriesIds.sorted().joinToString(SEPARATOR)
    }

    /** An empty string decodes to the empty set — all-off — not to nothing. */
    fun decode(raw: String): Set<String> = raw.split(SEPARATOR).filter(String::isNotEmpty).toSet()
}

@Singleton
class DataStoreFreeVisibleSeriesStore @Inject constructor(
    @ApplicationContext private val context: Context
) : FreeVisibleSeriesStore {

    override suspend fun visibleSeries(uid: String): Map<FreeTab, Set<String>> {
        require(uid.isNotBlank())
        val preferences = context.freeGraphDataStore.data.first()
        if (preferences[OWNER_UID] != uid) return emptyMap()
        // Non-empty series IDs without newlines round-trip through this encoding.
        return FreeTab.entries.mapNotNull { tab ->
            preferences[key(tab)]?.let { tab to FreeVisibleSeriesCodec.decode(it) }
        }.toMap()
    }

    override suspend fun remember(uid: String, tab: FreeTab, seriesIds: Set<String>) {
        require(uid.isNotBlank())
        val encoded = FreeVisibleSeriesCodec.encode(seriesIds)
        context.freeGraphDataStore.edit { preferences ->
            // A different owner's choices are not merged with this one's — they are replaced.
            if (preferences[OWNER_UID] != uid) {
                FreeTab.entries.forEach { preferences.remove(key(it)) }
                preferences[OWNER_UID] = uid
            }
            preferences[key(tab)] = encoded
        }
    }

    private fun key(tab: FreeTab) = stringPreferencesKey("visible_series_${tab.storageValue}")

    private companion object {
        val OWNER_UID = stringPreferencesKey("owner_uid")
    }
}
