package com.jay.fxi.data.auth

import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-scoped owner of Firebase token acquisition and rejected-token refreshes.
 *
 * Fetches are detached from individual callers so one canceled screen does not cancel a shared
 * credential lookup that another authenticated transport consumer is awaiting.
 *
 * ### Credential recovery (S1 recovery signal §2)
 *
 * It also decides, for what goes through it, when the same identity has a usable credential again after a failure, and announces
 * that through [AuthCredentialRecoveryStream]. A failure run (episode) opens on a real acquisition failure this provider saw, a new
 * refresh's Unusable verdict, or rejection evidence newly recorded here — each once, and only while the identity it was captured
 * for is still current. A remembered verdict, a waiter re-reading a shared result, a repeated fingerprint or a null refresh
 * answer is not a new failure; neither is no user, an identity change or cancellation. A later failure in an open run keeps its
 * episode and moves its failure order. The run closes, with one event, on a completed acquisition for the same identity that
 * started after the run's latest failure and returned a credential that is not a known rejection. Waiters that joined a shared
 * lookup keep that lookup's start. An identity change drops an open run without an event. Recovery neither clears remembered
 * rejections nor restores a replay budget, and none of this changes what acquisitions return or throw.
 *
 * Topic's final `invalid_token` rejection does not reach this provider yet; L-4e E6 hands it over.
 */
class AuthTokenProvider internal constructor(
    private val source: AuthTokenSource,
    private val processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Shared with the premium issuer; see [AccessOrderSequence]. No default: an owner of its own would compare with nothing. */
    private val orders: AccessOrderSequence,
    /**
     * Where a recovery subscriber's exception goes, in [processScope], after every delivery owed has been made. Production reports
     * it: the credential it follows is already decided, and the other subscribers and later events still get theirs.
     */
    private val onRecoverySubscriberFailure: (Throwable) -> Unit = { throw it }
) : AuthCredentialRecoveryStream {
    private data class FetchKey(
        val identity: AuthIdentity,
        val forceRefresh: Boolean
    )

    private data class RejectedKey(
        val identity: AuthIdentity,
        val tokenSha256: String
    )

    private sealed interface RefreshOutcome {
        data class Refreshed(val snapshot: AuthSnapshot) : RefreshOutcome
        data object Unusable : RefreshOutcome
    }

    private sealed interface RefreshDecision {
        data class Await(val deferred: Deferred<RefreshOutcome>) : RefreshDecision
        data class UseCurrentCredential(val tokenSha256: String) : RefreshDecision
        data object Unusable : RefreshDecision
    }

    private val mutex = Mutex()
    private val inFlightFetches = mutableMapOf<FetchKey, Deferred<AuthSnapshot>>()
    private val inFlightRefreshes = mutableMapOf<RejectedKey, Deferred<RefreshOutcome>>()
    private var rejectedIdentity: AuthIdentity? = null
    private val completedRefreshes = mutableMapOf<String, String?>()

    /** An identity's open failure run. Guarded by [mutex]. */
    private data class FailureEpisode(val identity: AuthIdentity, val number: Long, val latestFailureOrder: Long)

    private var failureEpisode: FailureEpisode? = null
    private var lastEpisodeNumber = 0L

    /**
     * Subscribers and owed deliveries, guarded by [recoveryLock]: a JVM monitor, since [observe] cannot suspend. It is taken
     * inside [mutex] and never the other way round, and nothing runs under it but list edits.
     */
    private val recoveryLock = Any()
    private val recoverySubscribers = ArrayList<(AuthCredentialRecovery) -> Unit>()
    private val owedRecoveries = ArrayDeque<Pair<(AuthCredentialRecovery) -> Unit, AuthCredentialRecovery>>()

    /** Serialises delivery; guarded by [deliveryLock]. The monitor is reentrant, so the flag is what refuses a nested run. */
    private val deliveryLock = Any()
    private var delivering = false

    /** Registers [onRecovery] for events decided after this call. Registration and event ordering share [recoveryLock]. */
    override fun observe(onRecovery: (AuthCredentialRecovery) -> Unit) {
        synchronized(recoveryLock) { recoverySubscribers += onRecovery }
    }

    suspend fun currentSnapshot(): AuthSnapshot {
        val identity = source.currentIdentity()
            ?: throw AuthUnavailableException("No authenticated user")
        return fetchSingleFlight(identity, forceRefresh = false)
    }

    fun currentIdentityFence(): AuthIdentityFence? =
        source.currentIdentity()?.let { AuthIdentityFence(it.uid, it.authGeneration) }

    /**
     * Synchronously makes credentials from [expected] stale before an app-owned auth transition.
     * A false result means Firebase had already moved to another identity.
     */
    fun invalidateCurrentSession(expected: AuthIdentityFence): Boolean =
        source.invalidateCurrentSession(expected.identity)

    /**
     * Invalidates an existing session before an app-owned sign-in SDK mutation starts.
     * Firebase Auth 24.0.1 updates its existing FirebaseUser object for a same-UID sign-in, so
     * object identity alone cannot detect that credential/session replacement.
     */
    fun advanceGenerationBeforeSignIn(): Boolean = source.invalidateCurrentIdentity()

    /**
     * Returns the one forced-refresh result owned by [rejected], or null when replay is unsafe.
     * Concurrent callers share the same refresh, while each safe read still owns at most
     * one replay of its own request.
     */
    suspend fun refreshAfterUnauthorized(rejected: AuthSnapshot): AuthSnapshot? {
        requireCurrent(rejected)
        val key = rejected.rejectedKey()
        val decision = mutex.withLock {
            if (source.currentIdentity() != rejected.identity) {
                throw AuthIdentityChangedException()
            }
            selectRejectedIdentityLocked(rejected.identity)
            // A completed entry may remain until cleanup runs; later callers must use the completed-refresh policy below.
            inFlightRefreshes[key]?.takeUnless { it.isCompleted }?.let { return@withLock RefreshDecision.Await(it) }
            if (completedRefreshes.containsKey(key.tokenSha256)) {
                return@withLock completedRefreshes[key.tokenSha256]?.let {
                    RefreshDecision.UseCurrentCredential(it)
                } ?: RefreshDecision.Unusable
            }

            val created = processScope.async {
                // An acquisition failure was recorded by the lookup that saw it; only this refresh's own verdict is new here.
                var verdictUnusable = false
                val outcome = try {
                    val refreshed = fetchSingleFlight(rejected.identity, forceRefresh = true)
                    if (refreshed.rejectedKey() == key || isKnownRejected(refreshed)) {
                        verdictUnusable = true
                        RefreshOutcome.Unusable
                    } else {
                        RefreshOutcome.Refreshed(refreshed)
                    }
                } catch (_: AuthUnavailableException) {
                    RefreshOutcome.Unusable
                }
                mutex.withLock {
                    if (rejectedIdentity == rejected.identity) {
                        completedRefreshes[key.tokenSha256] =
                            (outcome as? RefreshOutcome.Refreshed)
                                ?.snapshot
                                ?.rejectedKey()
                                ?.tokenSha256
                    }
                    if (verdictUnusable) recordFailureLocked(rejected.identity)
                }
                outcome
            }
            inFlightRefreshes[key] = created
            created.invokeOnCompletion {
                processScope.launch {
                    mutex.withLock {
                        if (inFlightRefreshes[key] === created) {
                            inFlightRefreshes.remove(key)
                        }
                    }
                }
            }
            RefreshDecision.Await(created)
        }

        val candidate = when (decision) {
            is RefreshDecision.Await -> when (val outcome = decision.deferred.await()) {
                is RefreshOutcome.Refreshed -> outcome.snapshot
                RefreshOutcome.Unusable -> null
            }
            is RefreshDecision.UseCurrentCredential -> try {
                fetchSingleFlight(rejected.identity, forceRefresh = false).takeIf {
                    it.rejectedKey().tokenSha256 == decision.tokenSha256
                }
            } catch (_: AuthUnavailableException) {
                null
            }
            RefreshDecision.Unusable -> null
        }
        return candidate?.takeUnless {
            it.rejectedKey() == key || isKnownRejected(it)
        }
    }

    suspend fun isKnownRejected(snapshot: AuthSnapshot): Boolean {
        val key = snapshot.rejectedKey()
        return mutex.withLock {
            rejectedIdentity == snapshot.identity &&
                (
                    completedRefreshes.containsKey(key.tokenSha256) ||
                        inFlightRefreshes[key]?.isCompleted == false
                    )
        }
    }

    /** Marks a replay credential as rejected without granting it another replay in this process. */
    suspend fun recordRejected(snapshot: AuthSnapshot) {
        val key = snapshot.rejectedKey()
        mutex.withLock {
            if (source.currentIdentity() != snapshot.identity) {
                throw AuthIdentityChangedException()
            }
            selectRejectedIdentityLocked(snapshot.identity)
            val repeated = completedRefreshes.containsKey(key.tokenSha256) && completedRefreshes[key.tokenSha256] == null
            completedRefreshes[key.tokenSha256] = null
            inFlightRefreshes.remove(key)
            if (!repeated) recordFailureLocked(snapshot.identity)
        }
    }

    fun isCurrent(snapshot: AuthSnapshot): Boolean = source.currentIdentity() == snapshot.identity

    fun requireCurrent(snapshot: AuthSnapshot) {
        if (!isCurrent(snapshot)) {
            throw AuthIdentityChangedException()
        }
    }

    fun requireCurrent(fence: AuthIdentityFence) {
        if (source.currentIdentity() != fence.identity) {
            throw AuthIdentityChangedException()
        }
    }

    private suspend fun fetchSingleFlight(
        identity: AuthIdentity,
        forceRefresh: Boolean
    ): AuthSnapshot {
        if (source.currentIdentity() != identity) {
            throw AuthIdentityChangedException()
        }

        val key = FetchKey(identity, forceRefresh)
        val deferred = mutex.withLock {
            // Same as refreshes: reusing a completed lookup would hand out a credential a later refresh has replaced.
            inFlightFetches[key]?.takeUnless { it.isCompleted } ?: orders.next().let { startedOrder -> processScope.async {
                if (source.currentIdentity() != identity) {
                    throw AuthIdentityChangedException()
                }
                val token = try {
                    source.fetchToken(identity, forceRefresh)
                } catch (failure: AuthUnavailableException) {
                    // Recorded once here, for every waiter; the throw below is unchanged.
                    withContext(NonCancellable) { mutex.withLock { recordFailureLocked(identity) } }
                    throw failure
                }
                if (source.currentIdentity() != identity) {
                    throw AuthIdentityChangedException()
                }
                val snapshot = AuthSnapshot(identity.uid, identity.authGeneration, token)
                withContext(NonCancellable) { mutex.withLock { closeEpisodeLocked(snapshot, startedOrder) } }
                deliverOwedRecoveries()
                snapshot
            }.also { created ->
                inFlightFetches[key] = created
                created.invokeOnCompletion {
                    processScope.launch {
                        mutex.withLock {
                            if (inFlightFetches[key] === created) {
                                inFlightFetches.remove(key)
                            }
                        }
                    }
                }
            } }
        }
        val snapshot = deferred.await()
        if (source.currentIdentity() != identity) {
            throw AuthIdentityChangedException()
        }
        return snapshot
    }

    /** Opens or extends [identity]'s failure run, if it is still current. Callers hold [mutex]. */
    private fun recordFailureLocked(identity: AuthIdentity) {
        if (source.currentIdentity() != identity) return
        val order = orders.next()
        val open = failureEpisode
        failureEpisode = if (open != null && open.identity == identity) {
            open.copy(latestFailureOrder = order)
        } else {
            FailureEpisode(identity, ++lastEpisodeNumber, order)
        }
    }

    /**
     * Closes the open run with one owed event when [snapshot] recovers it; drops a run another identity left. Callers hold [mutex],
     * which is why the known-rejection read is inlined rather than taken through [isKnownRejected].
     */
    private fun closeEpisodeLocked(snapshot: AuthSnapshot, startedOrder: Long) {
        // No run, no read: an acquisition with nothing to recover reads the identity exactly as before.
        val open = failureEpisode ?: return
        val identity = snapshot.identity
        if (source.currentIdentity() != identity) return
        if (open.identity != identity) {
            failureEpisode = null
            return
        }
        if (startedOrder <= open.latestFailureOrder) return
        val key = snapshot.rejectedKey()
        val knownRejected = rejectedIdentity == identity &&
            (completedRefreshes.containsKey(key.tokenSha256) || inFlightRefreshes[key]?.isCompleted == false)
        if (knownRejected) return
        // Decided under the subscribers' monitor: a subscriber registered before it gets it, one registered after does not.
        synchronized(recoveryLock) {
            failureEpisode = null
            val recovery = AuthCredentialRecovery(snapshot.fence, open.number, startedOrder, orders.next())
            recoverySubscribers.forEach { subscriber -> owedRecoveries.addLast(subscriber to recovery) }
        }
    }

    /**
     * Hands owed events over outside [mutex], one at a time and in decision order. A subscriber that throws does not cost the
     * others their delivery or the acquisition its result; [onRecoverySubscriberFailure] takes it in [processScope] afterwards.
     */
    private fun deliverOwedRecoveries() {
        val failures = ArrayList<Throwable>()
        synchronized(deliveryLock) {
            if (delivering) return
            delivering = true
            try {
                while (true) {
                    val (subscriber, recovery) = synchronized(recoveryLock) { owedRecoveries.removeFirstOrNull() } ?: break
                    try {
                        subscriber(recovery)
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            } finally {
                delivering = false
            }
        }
        failures.forEach { failure -> processScope.launch { onRecoverySubscriberFailure(failure) } }
    }

    private fun selectRejectedIdentityLocked(identity: AuthIdentity) {
        if (rejectedIdentity != identity) {
            rejectedIdentity = identity
            completedRefreshes.clear()
        }
    }

    private fun AuthSnapshot.rejectedKey(): RejectedKey = RejectedKey(
        identity = identity,
        tokenSha256 = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    )

}
