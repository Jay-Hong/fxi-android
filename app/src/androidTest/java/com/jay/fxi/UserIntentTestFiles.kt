package com.jay.fxi

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.jay.fxi.data.local.userIntentDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `fxi_user_intent` as the device tests see it (S7 E5b4a).
 *
 * The process keeps one DataStore over the file, with its cache, for its whole life. Clearing goes
 * through that DataStore so disk and cache agree; reading what a new process would read goes
 * through a second DataStore over a byte copy, which knows nothing but the file.
 */
internal object UserIntentTestFiles {

    fun file(context: Context) = File(context.filesDir, "datastore/fxi_user_intent.preferences_pb")

    fun clear(context: Context) = runBlocking { context.userIntentDataStore.edit { it.clear() } }.let { }

    fun seed(context: Context, block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) =
        runBlocking { context.userIntentDataStore.edit { block(it) } }.let { }

    fun inMemory(context: Context): Map<Preferences.Key<*>, Any> =
        runBlocking { context.userIntentDataStore.data.first() }.asMap()

    fun onDisk(context: Context): Map<Preferences.Key<*>, Any> {
        // A fresh name each time: DataStore refuses a second instance over a path whose first
        // instance has not finished shutting down.
        val copy = File.createTempFile("user_intent_copy", ".preferences_pb", context.cacheDir)
        file(context).copyTo(copy, overwrite = true)
        val job = SupervisorJob()
        try {
            return runBlocking {
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + job),
                    produceFile = { copy }
                ).data.first().asMap()
            }
        } finally {
            runBlocking { job.cancelAndJoin() }
            copy.delete()
        }
    }

    /** The disk names the rate rows have used since before the delegate moved (main 82e28ff). */
    val RATE_ROW_LISTS = listOf("FX_BANKS", "TETHER_EXCHANGES")
    val RATE_ROW_OWNER = stringPreferencesKey("rate_row_owner_uid")
    fun rateRowOrder(list: String) = stringPreferencesKey("rate_row_order_$list")
    fun rateRowHidden(list: String) = stringPreferencesKey("rate_row_hidden_$list")

    /** The bank store's two keys, as agreed (s7e5b4_api_agreed.r1.md §2.1). */
    val BANK_OWNER = stringPreferencesKey("last_selected_bank_owner_uid")
    val BANK = stringPreferencesKey("last_selected_bank")

    /**
     * Everything that is not the bank: every rate row axis of every list, under another owner, and
     * a sentinel of each value type a future tenant of the file could keep.
     */
    fun seedOthers(context: Context) = seed(context) { p ->
        p[RATE_ROW_OWNER] = "u9"
        RATE_ROW_LISTS.forEach { list ->
            p[rateRowOrder(list)] = "kb\nhana"
            p[rateRowHidden(list)] = "citi"
        }
        p[stringPreferencesKey("future_tenant_string")] = "x"
        p[booleanPreferencesKey("future_tenant_boolean")] = true
        p[intPreferencesKey("future_tenant_int")] = 7
        p[stringSetPreferencesKey("future_tenant_set")] = setOf("a", "b")
    }

    fun withoutBank(map: Map<Preferences.Key<*>, Any>) =
        map.filterKeys { it.name != BANK_OWNER.name && it.name != BANK.name }
}
