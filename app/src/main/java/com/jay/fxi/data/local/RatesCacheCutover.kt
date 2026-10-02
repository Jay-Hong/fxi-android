package com.jay.fxi.data.local

import android.content.Context
import android.util.Log
import com.jay.fxi.data.remote.TopicRuntimeOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * This process's one rate cache migration, whose consumer cutover is [owner]'s readiness.
 */
@Singleton
internal class RatesCacheCutover internal constructor(
    private val migration: RatesCacheMigration,
    private val owner: TopicRuntimeOwner,
    private val scope: CoroutineScope,
    private val report: (Throwable) -> Unit
) {
    @Inject
    constructor(
        owner: TopicRuntimeOwner,
        cacheService: CacheService,
        @ApplicationContext context: Context
    ) : this(
        RatesCacheMigration(LocalMigrationJournal.from(context), cacheService::deleteRetiredRateKeys),
        owner,
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        { cause -> Log.w("RatesCacheCutover", "Rate cache migration failed", cause) }
    )

    private var job: Job? = null

    /**
     * Launches the migration on [scope] once; a repeat returns the same job. Its cutover step is [TopicRuntimeOwner.requireReady].
     * A failure goes to [report] and is never treated as done; a cancellation is not reported and propagates.
     */
    @Synchronized
    fun launch(): Job = job ?: scope.launch {
        try {
            migration.run { owner.requireReady() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // The journal retains the last completed checkpoint for the next process to retry.
            report(error)
        }
    }.also { job = it }
}
