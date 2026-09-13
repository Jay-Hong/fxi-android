package com.jay.fxi.data.auth

import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped owner of Firebase token acquisition and rejected-token refreshes.
 *
 * Fetches are detached from individual callers so one canceled screen does not cancel a shared
 * credential lookup that another authenticated transport consumer is awaiting.
 */
class AuthTokenProvider internal constructor(
    private val source: AuthTokenSource,
    private val processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
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
                val outcome = try {
                    val refreshed = fetchSingleFlight(rejected.identity, forceRefresh = true)
                    if (refreshed.rejectedKey() == key || isKnownRejected(refreshed)) {
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
            completedRefreshes[key.tokenSha256] = null
            inFlightRefreshes.remove(key)
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
            inFlightFetches[key]?.takeUnless { it.isCompleted } ?: processScope.async {
                if (source.currentIdentity() != identity) {
                    throw AuthIdentityChangedException()
                }
                val token = source.fetchToken(identity, forceRefresh)
                if (source.currentIdentity() != identity) {
                    throw AuthIdentityChangedException()
                }
                AuthSnapshot(identity.uid, identity.authGeneration, token)
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
            }
        }
        val snapshot = deferred.await()
        if (source.currentIdentity() != identity) {
            throw AuthIdentityChangedException()
        }
        return snapshot
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
