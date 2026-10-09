package com.jay.fxi.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * S4 CUT-CC5-4: this process's one graph cache migration (`cut_cc5_agreed.r1.md`). Its readiness is the one instance the
 * migration's cutover step checks. The topic owner resolves it on Main once the graph runtime has started and handed over
 * its initial inputs and the screen host is installed, then calls [markReadyAndLaunch]; a process whose graph failed, or
 * that never starts the owner (D24 off, no data), never does. Constructing it reads no journal and deletes nothing; the
 * injected constructor resolves [Context.getFilesDir] on the resolving thread.
 */
@Singleton
internal class AppGraphCacheCutover internal constructor(
    private val readiness: GraphConsumerReadiness,
    private val cutover: GraphCacheCutover
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        GraphConsumerReadiness(),
        LocalMigrationJournal.from(context),
        context.filesDir,
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        { cause -> Log.w("AppGraphCacheCutover", "Graph cache migration failed", cause) }
    )

    /** The migration over [journal] and [filesDir] whose cutover step is this [readiness]; [scope] must run on IO. */
    internal constructor(
        readiness: GraphConsumerReadiness,
        journal: LocalMigrationJournal,
        filesDir: File,
        scope: CoroutineScope,
        report: (Throwable) -> Unit,
        delete: (File) -> Boolean = File::delete
    ) : this(readiness, GraphCacheCutover(GraphCacheMigration(journal, filesDir, readiness, delete), scope, report))

    /**
     * Main: marks the v2 consumers authoritative, then launches the migration once; a repeat returns the same job. A
     * migration failure is reported once and keeps the journal's last completed step; it does not undo the graph.
     */
    fun markReadyAndLaunch(): Job {
        readiness.markReady()
        return cutover.launch()
    }
}
