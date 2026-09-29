package com.jay.fxi.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * `ANDROID_V2_PLAN.md §9.1`: every legacy local target moves `detected → consumer_cutover → legacy_deleted`, in that order,
 * and a crash between steps re-runs the same step. `fxi_bank_preferences` is the plan's one exception and goes through
 * [RetiredStores] instead.
 *
 * The journal itself must not cross devices: a marker restored without its legacy data is a combination §9.1 forbids.
 * `BackupRulesLedgerTest` holds that decision.
 *
 * Calls for one target must be serialised by the caller: the step reads the stage, runs the work, then writes, so two
 * concurrent calls for the same target could both run the work. The work must be safe to repeat — that is also what makes a
 * step interrupted before its record simply re-run.
 */
private const val MIGRATION_JOURNAL_NAME = "fxi_migration_journal"

private val Context.migrationJournalDataStore: DataStore<Preferences> by preferencesDataStore(
    name = MIGRATION_JOURNAL_NAME
)

/** The §9.1 targets that go through the journal. [key] is the on-disk name and must not change once shipped. */
internal enum class LegacyMigrationTarget(val key: String) {
    /** `fxi_cache` `rates`, `rates_timestamp` — S3, with the legacy rate consumer cutover. */
    RATES_CACHE("rates_cache"),
    /** `fxi_cache` `last_bank_*` — S7. */
    LAST_BANK_SELECTION("last_bank_selection"),
    /** `graph_cache_*.json` — S4, with the Graph V2 cutover. */
    GRAPH_CACHE_FILES("graph_cache_files"),
    /** `fxi_graph_preferences` — S4. */
    GRAPH_PREFERENCES("graph_preferences")
}

/** In order. The on-disk value is the enum name. */
internal enum class LegacyMigrationStage { DETECTED, CONSUMER_CUTOVER, LEGACY_DELETED }

internal sealed interface MigrationAdvance {
    /** The target moved to the requested stage. */
    data object Advanced : MigrationAdvance
    /** The target was already there: the re-run after a crash, nothing written. */
    data object AlreadyThere : MigrationAdvance
    /** The requested stage is more than one step ahead; [current] is what the journal holds (null = never recorded). */
    data class Skipped(val current: LegacyMigrationStage?) : MigrationAdvance
    /** The requested stage is behind the recorded one. */
    data class Backward(val current: LegacyMigrationStage) : MigrationAdvance
}

internal class LocalMigrationJournal(private val dataStore: DataStore<Preferences>) {

    /** The recorded stage, or null if the target was never recorded. An unreadable value is an error, not absence. */
    suspend fun stage(target: LegacyMigrationTarget): LegacyMigrationStage? {
        val recorded = dataStore.data.first()[stringPreferencesKey(target.key)] ?: return null
        return LegacyMigrationStage.entries.firstOrNull { it.name == recorded }
            ?: throw IllegalStateException("Unreadable migration stage for ${target.key}: $recorded")
    }

    /** Records [LegacyMigrationStage.DETECTED] for a target never recorded. */
    suspend fun detect(target: LegacyMigrationTarget): MigrationAdvance =
        advance(target, LegacyMigrationStage.DETECTED, null) {}

    /**
     * From [LegacyMigrationStage.DETECTED] only: runs [work] — the new store's commit and the consumer switch — and records
     * [LegacyMigrationStage.CONSUMER_CUTOVER] only after it returns. If [work] throws, nothing is recorded and the exception
     * propagates; the next run repeats the work. Any other stage returns without running [work].
     */
    suspend fun cutover(target: LegacyMigrationTarget, work: suspend () -> Unit): MigrationAdvance =
        advance(target, LegacyMigrationStage.CONSUMER_CUTOVER, LegacyMigrationStage.DETECTED, work)

    /** From [LegacyMigrationStage.CONSUMER_CUTOVER] only: runs [work] — the legacy deletion — then records [LegacyMigrationStage.LEGACY_DELETED]. */
    suspend fun deleteLegacy(target: LegacyMigrationTarget, work: suspend () -> Unit): MigrationAdvance =
        advance(target, LegacyMigrationStage.LEGACY_DELETED, LegacyMigrationStage.CONSUMER_CUTOVER, work)

    private suspend fun advance(
        target: LegacyMigrationTarget,
        next: LegacyMigrationStage,
        previous: LegacyMigrationStage?,
        work: suspend () -> Unit
    ): MigrationAdvance {
        val current = stage(target)
        if (current == next) return MigrationAdvance.AlreadyThere
        if (current != null && current.ordinal > next.ordinal) return MigrationAdvance.Backward(current)
        if (current != previous) return MigrationAdvance.Skipped(current)

        work()
        dataStore.edit { it[stringPreferencesKey(target.key)] = next.name }
        return MigrationAdvance.Advanced
    }

    companion object {
        const val NAME = MIGRATION_JOURNAL_NAME
        fun from(context: Context) = LocalMigrationJournal(context.migrationJournalDataStore)
    }
}
