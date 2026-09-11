package com.jay.fxi.data.auth

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes app-owned Firebase sign-in and sign-out transitions within this process. */
@Singleton
class AuthTransitionCoordinator internal constructor(
    private val processScope: CoroutineScope,
    private val beforeTrackedTransition: () -> Unit = {}
) {
    @Inject
    constructor(authTokenProvider: AuthTokenProvider) : this(
        processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        beforeTrackedTransition = { authTokenProvider.advanceGenerationBeforeSignIn() }
    )

    private val mutex = Mutex()

    suspend fun <T> withTransition(block: suspend () -> T): T = mutex.withLock {
        block()
    }

    /**
     * Waits for the transition lease in the caller so cancellation can discard queued UI work.
     * Once the lease is acquired, the SDK operation starts undispatched and ownership moves to the
     * process scope until that already-started operation reaches a terminal state.
     */
    suspend fun <T> withTrackedTransition(block: suspend () -> T): T =
        handOff(beforeTrackedTransition, block)

    /**
     * The same hand-off as [withTrackedTransition], without the sign-in hook. For sign-out.
     *
     * The sign-in hook uses the announcing invalidation path. Sign-out instead uses
     * [AuthTokenProvider.invalidateCurrentSession], whose retire step does not announce its
     * generation change; an identity change found by its preceding observation can still be
     * delivered. Announcing the retirement's same-uid fence could make AuthAccessBinder rebind
     * the uid being signed out.
     */
    suspend fun <T> withHandedOffTransition(block: suspend () -> T): T = handOff({}, block)

    private suspend fun <T> handOff(before: () -> Unit, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val lockOwner = Any()
        mutex.lock(lockOwner)
        var handedOff = false
        try {
            // A caller canceled while queued must not start a stale Firebase operation after the
            // preceding transition releases the mutex.
            currentCoroutineContext().ensureActive()
            val operation = processScope.async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    before()
                    block()
                } finally {
                    mutex.unlock(lockOwner)
                }
            }
            handedOff = true
            return operation.await()
        } finally {
            if (!handedOff) {
                mutex.unlock(lockOwner)
            }
        }
    }
}
