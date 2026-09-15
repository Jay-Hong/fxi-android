package com.jay.fxi.data.auth

import java.util.concurrent.atomic.AtomicLong

/**
 * The one process-local order credential acquisition and the premium issuer compare against each other (S1 recovery signal §3).
 *
 * Production builds a single instance and hands it to [AuthTokenProvider] and the issuer. Nothing that compares its numbers with
 * another owner's may build its own: two sequences make "started after" meaningless across them. Numbers only grow and are never
 * reset, not on a binding or identity change; gaps carry no meaning. Taking one blocks nothing and calls nobody back.
 */
class AccessOrderSequence {
    private val last = AtomicLong(0L)

    fun next(): Long = last.incrementAndGet()
}

/**
 * The same identity (uid and generation) acquired a credential that is not a known rejection, for the first time since a failure.
 *
 * An observation that a candidate credential exists again — not that the server accepts it, and not a premium approval or a new
 * topic grant. [episode] names the failure run it closes and only filters duplicates. [fetchStartedOrder] is when the acquisition
 * that closed it started, [recoveredOrder] when the provider decided the recovery; both come from [AccessOrderSequence].
 */
@ConsistentCopyVisibility
data class AuthCredentialRecovery internal constructor(
    val fence: AuthIdentityFence,
    val episode: Long,
    val fetchStartedOrder: Long,
    val recoveredOrder: Long
)

/**
 * Delivers [AuthCredentialRecovery] events to subscribers registered before them. No replay.
 *
 * Delivery happens outside the provider's lock, never re-enters a subscriber, and keeps the order events were decided in. A
 * subscriber should only enqueue: an exception it throws does not change the acquisition's result or reach other subscribers.
 */
fun interface AuthCredentialRecoveryStream {
    fun observe(onRecovery: (AuthCredentialRecovery) -> Unit)
}
