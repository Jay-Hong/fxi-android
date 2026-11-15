package com.jay.fxi.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resumes the last-bank journal target; a completed target still sweeps on every process start. */
internal class LastBankSelectionMigration(
    private val journal: LocalMigrationJournal,
    private val requireReady: suspend () -> Unit,
    private val deleteLegacy: suspend () -> Unit
) {
    private val mutex = Mutex()

    suspend fun run() = mutex.withLock {
        val target = LegacyMigrationTarget.LAST_BANK_SELECTION
        when (journal.stage(target)) {
            null -> {
                expect(journal.detect(target))
                expect(journal.cutover(target) { requireReady() })
                expect(journal.deleteLegacy(target) { deleteLegacy() })
            }
            LegacyMigrationStage.DETECTED -> {
                expect(journal.cutover(target) { requireReady() })
                expect(journal.deleteLegacy(target) { deleteLegacy() })
            }
            LegacyMigrationStage.CONSUMER_CUTOVER ->
                expect(journal.deleteLegacy(target) { deleteLegacy() })
            LegacyMigrationStage.LEGACY_DELETED -> deleteLegacy()
        }
    }

    private fun expect(result: MigrationAdvance) {
        check(result == MigrationAdvance.Advanced || result == MigrationAdvance.AlreadyThere) {
            "Unexpected last bank selection migration result: $result"
        }
    }
}

/** One runner and one launch for the admitted process, independent of screen lifetimes. */
@Singleton
internal class LastBankSelectionCutover internal constructor(
    private val migration: LastBankSelectionMigration,
    private val scope: CoroutineScope,
    private val report: (Throwable) -> Unit
) {
    @Inject constructor(
        memory: LastSelectedBankMemory,
        cacheService: CacheService,
        @ApplicationContext context: Context
    ) : this(
        LastBankSelectionMigration(LocalMigrationJournal.from(context), memory::requireReady, cacheService::deleteLastBankKeys),
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        { cause -> Log.w("LastBankSelectionCutover", "Last bank selection migration failed", cause) }
    )

    private var job: Job? = null

    @Synchronized fun launch(): Job = job ?: scope.launch {
        try {
            migration.run()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
        }
    }.also { job = it }
}
