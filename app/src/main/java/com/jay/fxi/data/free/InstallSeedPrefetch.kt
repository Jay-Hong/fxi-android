package com.jay.fxi.data.free

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * S4 CUT-CC1: starts the install seed read once, on [dispatcher], and returns at once (`cut_cc1_agreed.r3.md` §1.3). Repeated
 * starts return the same Job. A failure that is not a cancellation is handed to [report] once; the failed Job ends failed,
 * the process scope lives on, and nothing is cached, so the next get() reads again. A cancellation is not reported.
 */
internal class InstallSeedPrefetch(
    private val seeds: InstallSeedSource,
    dispatcher: CoroutineDispatcher,
    report: (Throwable) -> Unit
) {
    // A report that fails must not take the process down: reporting is diagnostic only.
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> runCatching { report(failure) } }
    )
    private var job: Job? = null

    @Synchronized
    fun launch(): Job = job ?: scope.launch { seeds.get() }.also { job = it }
}
