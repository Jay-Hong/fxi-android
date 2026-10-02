package com.jay.fxi.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The legacy rate cache in `fxi_cache`: the §9.1 [LegacyMigrationTarget.RATES_CACHE] target, and nothing else in that file. */
internal object LegacyRateCacheKeys {
    val RATES = stringPreferencesKey("rates")
    val RATES_TIMESTAMP = longPreferencesKey("rates_timestamp")
}

/** Removes the two legacy rate keys from [store] and leaves every other key (`last_bank_*` is S7's). Absent keys are not an error. */
internal suspend fun deleteLegacyRateKeys(store: DataStore<Preferences>) {
    store.edit { preferences ->
        preferences.remove(LegacyRateCacheKeys.RATES)
        preferences.remove(LegacyRateCacheKeys.RATES_TIMESTAMP)
    }
}

/**
 * S3-R3: runs [LegacyMigrationTarget.RATES_CACHE] through the journal — `detected`, then the consumer cutover [run] is handed,
 * then [deleteLegacy] — serialising its own calls, as the journal requires. [RatesCacheCutover] launches it once per process.
 */
internal class RatesCacheMigration(
    private val journal: LocalMigrationJournal,
    private val deleteLegacy: suspend () -> Unit
) {
    private val mutex = Mutex()

    /** [cutover] is the new store's commit and the consumer switch; it runs at most until it has once returned. */
    suspend fun run(cutover: suspend () -> Unit) = mutex.withLock {
        val target = LegacyMigrationTarget.RATES_CACHE
        when (val result = journal.detect(target)) {
            MigrationAdvance.Advanced, MigrationAdvance.AlreadyThere -> Unit
            is MigrationAdvance.Backward -> when (result.current) {
                LegacyMigrationStage.CONSUMER_CUTOVER -> Unit
                LegacyMigrationStage.LEGACY_DELETED -> return@withLock
                else -> throw IllegalStateException("Unexpected rate cache detect result: $result")
            }
            else -> throw IllegalStateException("Unexpected rate cache detect result: $result")
        }

        val cutoverResult = journal.cutover(target, cutover)
        if (cutoverResult != MigrationAdvance.Advanced && cutoverResult != MigrationAdvance.AlreadyThere) {
            throw IllegalStateException("Unexpected rate cache cutover result: $cutoverResult")
        }

        val deletionResult = journal.deleteLegacy(target, deleteLegacy)
        if (deletionResult != MigrationAdvance.Advanced && deletionResult != MigrationAdvance.AlreadyThere) {
            throw IllegalStateException("Unexpected rate cache deletion result: $deletionResult")
        }
    }
}
