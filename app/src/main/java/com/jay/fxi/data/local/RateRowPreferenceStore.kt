package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jay.fxi.domain.model.RateRowList
import com.jay.fxi.domain.model.RateRowPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * What the user has said about a rate list's order and which of its rows they want to see.
 *
 * Read whole rather than per list, for the same reason the graph store does: one read returns the
 * whole preferences object anyway, and a per-list read would put a suspension in front of every
 * tab switch for nothing.
 */
interface RateRowPreferenceStore {
    /** Lists this UID has never chosen for are absent, which is not the same as chosen-empty. */
    suspend fun preferences(uid: String): Map<RateRowList, RateRowPreference>

    suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference)
}

/**
 * A file of its own, for user intent.
 *
 * D27 wants backup-eligible user intent kept physically apart from token, capability and cache
 * state. An order and a hidden set are exactly that — worth carrying to a new device, and harmless
 * if they arrive there. The two backup XMLs are exclude-only and the manifest allows backup, so
 * landing this file with no rule change is what makes it eligible; `BackupRulesLedgerTest` records
 * that as a decision rather than an accident.
 */
private val Context.userIntentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fxi_user_intent"
)

/**
 * How the two axes are written down, kept out of the DataStore plumbing so it can be tested.
 *
 * **Order and visibility are separate keys, and either may be absent.** Welding them into one
 * record would mean that hiding a single bank freezes the order as it happened to be that day: the
 * user never asked for an order, but one would be stored, and a bank the server added later would
 * land at the end forever. Absent order means "no opinion — use the order it arrived in".
 */
internal object RateRowPreferenceCodec {
    const val SEPARATOR = "\n"

    /** Order is written in order. Sorting it would be writing down something else. */
    fun encodeOrder(codes: List<String>): String {
        require(codes.none { it.contains(SEPARATOR) }) { "source code must not contain a newline" }
        return codes.joinToString(SEPARATOR)
    }

    fun encodeHidden(codes: Set<String>): String {
        require(codes.none { it.contains(SEPARATOR) }) { "source code must not contain a newline" }
        // Sorted so an unchanged choice is written identically every time.
        return codes.sorted().joinToString(SEPARATOR)
    }

    /**
     * An empty string decodes to nothing hidden — a deliberate "show everything", not absence.
     *
     * **Duplicates are dropped, first occurrence winning.** This is the boundary where disk becomes
     * data, and a file is not something this build wrote: it survives updates, and D27 makes this
     * one travel between devices. `ANDROID_V2_PLAN.md:799` asks for exactly this — dedup with the
     * relative order kept. Downstream reads the answer as a list of distinct codes: `arrangement`
     * keeps whatever the stored order holds, `effective` hands it to the rows, and the sheet uses
     * the code as a `LazyColumn` key — so a repeated code would draw one row twice and then throw
     * on the duplicate key. Cleaning it here is what lets the rest simply assume it. Found by
     * review.
     */
    fun decode(raw: String): List<String> =
        raw.split(SEPARATOR).filter(String::isNotEmpty).distinct()
}

@Singleton
class DataStoreRateRowPreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context
) : RateRowPreferenceStore {

    override suspend fun preferences(uid: String): Map<RateRowList, RateRowPreference> {
        require(uid.isNotBlank())
        val preferences = context.userIntentDataStore.data.first()
        if (preferences[OWNER_UID] != uid) return emptyMap()
        return RateRowList.entries.mapNotNull { list ->
            val order = preferences[orderKey(list)]?.let { RateRowPreferenceCodec.decode(it) }
            val hidden = preferences[hiddenKey(list)]?.let { RateRowPreferenceCodec.decode(it).toSet() }
            if (order == null && hidden == null) null else list to RateRowPreference(order, hidden)
        }.toMap()
    }

    override suspend fun remember(uid: String, list: RateRowList, preference: RateRowPreference) {
        require(uid.isNotBlank())
        val order = preference.order?.let { RateRowPreferenceCodec.encodeOrder(it) }
        val hidden = preference.hidden?.let { RateRowPreferenceCodec.encodeHidden(it) }
        context.userIntentDataStore.edit { preferences ->
            // A different owner's choices are not merged with this one's — they are replaced.
            if (preferences[OWNER_UID] != uid) {
                RateRowList.entries.forEach {
                    preferences.remove(orderKey(it))
                    preferences.remove(hiddenKey(it))
                }
                preferences[OWNER_UID] = uid
            }
            // A null axis is an axis the user has no opinion on, so it is removed rather than
            // written empty: an empty hidden set means "show everything", which is an opinion.
            if (order == null) preferences.remove(orderKey(list)) else preferences[orderKey(list)] = order
            if (hidden == null) preferences.remove(hiddenKey(list)) else preferences[hiddenKey(list)] = hidden
        }
    }

    private fun orderKey(list: RateRowList) = stringPreferencesKey("rate_row_order_${list.storageValue}")

    private fun hiddenKey(list: RateRowList) = stringPreferencesKey("rate_row_hidden_${list.storageValue}")

    private companion object {
        /**
         * Owned by this concern, not shared with the file's other tenants.
         *
         * `fxi_user_intent` is meant to hold more than this one day. A single `owner_uid` shared
         * across tenants would let one of them claim the file for a UID and leave another's rows
         * readable to it, so each concern carries its own owner and purges only its own keys.
         */
        val OWNER_UID = stringPreferencesKey("rate_row_owner_uid")
    }
}
