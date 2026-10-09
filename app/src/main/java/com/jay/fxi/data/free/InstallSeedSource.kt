package com.jay.fxi.data.free

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * S4 CUT-P3a: the install's stable spread seed (`free_snapshot/install_seed`), read - and created once if absent - off the
 * main thread on first [get], then held in memory. [current] reads only volatile memory: null until a successful value is published, then that value even before a waiting [get] returns.
 * Dormant: nothing in production constructs it; the free scheduler's own private reader is unchanged until CC1.
 *
 * [get] serialises read attempts per instance, so at most one [read] runs at a time, and reads on [io] only while nothing is
 * cached. Concurrent calls that neither fail nor are cancelled share one read and one value. A failed read reaches only the
 * call that ran it and is not cached; any call still waiting may then try again. A caller's cancellation propagates, on the
 * cached path too. A waiting caller's cancellation does not cancel the call that is reading; a reading call cancelled before
 * its value is published caches nothing, and the next attempt starts only after the synchronous read has really ended. A
 * caller cancelled after the value was published leaves it cached. Cancellation neither interrupts a synchronous read nor
 * undoes its write.
 */
internal class InstallSeedSource(private val read: () -> String, private val io: CoroutineDispatcher) {
    private val attempts = Mutex()

    @Volatile private var published: String? = null

    val current: String? get() = published

    suspend fun get(): String {
        val value = attempts.withLock { published ?: withContext(io) { read() }.also { published = it } }
        // The one check every return passes, the cached ones included.
        currentCoroutineContext().ensureActive()
        return value
    }
}
