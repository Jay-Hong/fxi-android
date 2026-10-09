package com.jay.fxi.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * S4 RT07: launches [GraphCacheMigration.run] at most once per GraphCacheCutover instance, returning the same Job after
 * success, failure, or cancellation. The process's one instance is [AppGraphCacheCutover]'s (S4 CUT-CC5-4).
 *
 * A failure goes to [report] once and is never treated as done; the journal keeps the last completed step for the next
 * process. A cancellation is not reported and propagates. There is no retry timer. [scope] must run on an IO dispatcher: the
 * migration's sweeps block.
 */
internal class GraphCacheCutover(
    private val migration: GraphCacheMigration,
    private val scope: CoroutineScope,
    private val report: (Throwable) -> Unit
) {
    private var job: Job? = null

    @Synchronized
    fun launch(): Job = job ?: scope.launch {
        try {
            migration.run()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            report(error)
        }
    }.also { job = it }
}
