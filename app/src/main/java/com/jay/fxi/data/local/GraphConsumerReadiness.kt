package com.jay.fxi.data.local

/**
 * S4 CUT-P3c: the graph migration's readiness. [requireReady] returns only after [markReady]: the process's graph runtime was
 * assembled and started on Main, its initial inputs handed over, and the screen host installed, so the v2 consumers are
 * authoritative and read absence as default (an empty disk store, an absent selection). Otherwise it throws
 * IllegalStateException at once; it never waits, since the launcher starts the migration only after [markReady]. A process
 * whose graph runtime failed never marks it. Dormant: nothing in production constructs it.
 */
internal class GraphConsumerReadiness : GraphMigrationReadiness {
    @Volatile private var ready = false

    /** Idempotent; there is no way back to not-ready. */
    fun markReady() {
        ready = true
    }

    override suspend fun requireReady() {
        check(ready) { "Graph runtime and screen host are not ready" }
    }
}
