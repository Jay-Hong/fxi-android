package com.jay.fxi.data.local

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * S4 RT07: the consumer readiness the graph cutover step waits on. Returning asserts that the v2 store's required commit, or
 * its explicitly verified absence-as-default contract, and the consumer cutover have succeeded. Readiness must not depend on
 * login, premium approval, catalog fetch success, or graph screen visitation. It runs inside this migration's lock and the
 * journal's cutover step, so it must not wait for this migration. No production implementation is supplied by RT07.
 */
internal fun interface GraphMigrationReadiness {
    suspend fun requireReady()
}

/**
 * What one physical sweep of a legacy graph target found and did. [clean] holds only after both the deletion and the final
 * absence check: a name still listed afterwards is in [failed] whatever its delete answered, so a name can be in both
 * [deleted] and [failed]. An absent directory sweeps clean with nothing listed.
 */
internal data class GraphLegacySweep(
    val deleted: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val unreadableDir: String? = null
) {
    val clean: Boolean get() = failed.isEmpty() && unreadableDir == null
}

/** Why a graph migration run did not finish: each failed target with its original cause (the first as the cause). */
internal class GraphCacheMigrationFailure(val failures: Map<LegacyMigrationTarget, Throwable>) :
    Exception("Graph cache migration did not finish: ${failures.keys}", failures.values.firstOrNull()) {
    init {
        require(failures.isNotEmpty()) { "a failure names at least one target" }
        failures.values.drop(1).forEach(::addSuppressed)
    }
}

/**
 * S4 RT07: retires the v1 graph cache files and the v1 graph preference store (`ANDROID_V2_PLAN.md` §9.1), each through its
 * own journal target, under one mutex. Its file work blocks: callers run it on an IO dispatcher. Dormant: nothing in
 * production constructs it.
 *
 * Each target resumes from the stage it last recorded: from none, detect, cut over and delete; from DETECTED, cut over and
 * delete; from CONSUMER_CUTOVER, delete; from LEGACY_DELETED, sweep again without writing the journal, because a downgrade can
 * write the legacy data back. The cutover step is [readiness]; the deletion step is a physical sweep that decodes nothing,
 * opens no DataStore and does not recurse, and that counts only once a second listing shows nothing left. An unreadable stage
 * is that target's failure, never absence or completion. The two targets are independent: both are attempted, and their
 * failures are thrown together. Only this run's own cancellation propagates: a CancellationException a step throws while the
 * run is still active — a readiness timeout, say — is that target's failure.
 *
 * @param delete the file deletion; a seam so that a refusal can be tested without changing permissions.
 */
internal class GraphCacheMigration(
    private val journal: LocalMigrationJournal,
    private val filesDir: File,
    private val readiness: GraphMigrationReadiness,
    private val delete: (File) -> Boolean = File::delete
) {
    private val mutex = Mutex()

    /**
     * Aggregates target failures, including journal errors and a CancellationException thrown while this run is still active,
     * into GraphCacheMigrationFailure after attempting both targets. failures retains each failed target's original cause. The
     * run's own cancellation propagates without aggregation, also when it arrives before the failures are thrown.
     */
    suspend fun run() = mutex.withLock {
        val failures = linkedMapOf<LegacyMigrationTarget, Throwable>()
        for (target in TARGETS) {
            try {
                migrate(target)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                failures[target] = cancelled
            } catch (failure: Throwable) {
                failures[target] = failure
            }
        }
        if (failures.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            throw GraphCacheMigrationFailure(failures)
        }
    }

    private suspend fun migrate(target: LegacyMigrationTarget) {
        when (journal.stage(target)) {
            null -> {
                expect(target, journal.detect(target))
                cutOver(target)
                deleteLegacy(target)
            }
            LegacyMigrationStage.DETECTED -> {
                cutOver(target)
                deleteLegacy(target)
            }
            LegacyMigrationStage.CONSUMER_CUTOVER -> deleteLegacy(target)
            LegacyMigrationStage.LEGACY_DELETED -> sweep(target).requireClean(target)
        }
    }

    private suspend fun cutOver(target: LegacyMigrationTarget) =
        expect(target, journal.cutover(target) { readiness.requireReady() })

    private suspend fun deleteLegacy(target: LegacyMigrationTarget) =
        expect(target, journal.deleteLegacy(target) { sweep(target).requireClean(target) })

    private fun expect(target: LegacyMigrationTarget, result: MigrationAdvance) {
        if (result != MigrationAdvance.Advanced && result != MigrationAdvance.AlreadyThere) {
            throw IllegalStateException("Unexpected graph migration result for ${target.key}: $result")
        }
    }

    private fun GraphLegacySweep.requireClean(target: LegacyMigrationTarget) {
        if (!clean) throw IllegalStateException("Legacy ${target.key} not removed: $this")
    }

    private fun sweep(target: LegacyMigrationTarget): GraphLegacySweep {
        val dir = if (target == LegacyMigrationTarget.GRAPH_CACHE_FILES) filesDir else File(filesDir, DATASTORE_DIR)
        val matches: (String) -> Boolean = if (target == LegacyMigrationTarget.GRAPH_CACHE_FILES) {
            { it.startsWith(CACHE_PREFIX) && it.endsWith(CACHE_SUFFIX) }
        } else {
            { it == PREFERENCES_BASE || it.startsWith("$PREFERENCES_BASE.") }
        }
        if (!dir.exists()) return GraphLegacySweep()
        val present = dir.listFiles() ?: return GraphLegacySweep(unreadableDir = dir.path)
        val deleted = mutableListOf<String>()
        val refused = mutableListOf<String>()
        present.filter { matches(it.name) }.forEach { file ->
            // A directory under a legacy name is not the legacy file; deleting it, or into it, is not this sweep's to do.
            if (file.isDirectory || !delete(file)) refused += file.name else deleted += file.name
        }
        val remaining = dir.listFiles()?.map { it.name }?.filter(matches)
            ?: return GraphLegacySweep(deleted, refused, dir.path)
        return GraphLegacySweep(deleted, (refused + remaining).distinct())
    }

    private companion object {
        val TARGETS = listOf(LegacyMigrationTarget.GRAPH_CACHE_FILES, LegacyMigrationTarget.GRAPH_PREFERENCES)
        const val CACHE_PREFIX = "graph_cache_"
        const val CACHE_SUFFIX = ".json"
        /** Where `preferencesDataStore(name = …)` puts its file, relative to `filesDir`. */
        const val DATASTORE_DIR = "datastore"
        const val PREFERENCES_BASE = "fxi_graph_preferences.preferences_pb"
    }
}
