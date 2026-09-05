package com.jay.fxi.data.entitlements

/**
 * The namespace a purge must remove: everything not owned by the current epoch.
 *
 * [ownerUid] is the owner of the data being deleted, taken from the journal — after an account
 * switch that is the *previous* user, not whoever is signed in now.
 */
data class PurgeNamespace(
    val ownerUid: String?,
    val currentUserAccessEpoch: String?,
    val currentKrxCapabilityEpoch: String?,
    /** The single journal entry this call must satisfy. Others stay owed. */
    val pending: PendingPurge
)

/**
 * Outcome of a purge attempt.
 *
 * [Deferred] exists so that an unimplemented purger cannot be mistaken for a completed one. The
 * journal is cleared only on [Completed]; anything else leaves the pending-purge entry in place so
 * the next process start retries it.
 */
sealed interface PurgeResult {
    data object Completed : PurgeResult

    /** No purge ran. The caller must keep the journal. */
    data class Deferred(val reason: String) : PurgeResult

    data class Failed(val cause: Throwable) : PurgeResult
}

/** Removes all domain/user data for a superseded user namespace. */
fun interface UserScopePurger {
    suspend fun purgeUserScope(namespace: PurgeNamespace): PurgeResult
}

/** Removes KRX capability data for a superseded capability namespace. */
fun interface CapabilityScopePurger {
    suspend fun purgeCapabilityScope(namespace: PurgeNamespace): PurgeResult
}

/**
 * Placeholder wiring for this slice.
 *
 * The real purgers own live caches whose consumers are still the legacy rate/graph stores, which
 * `ANDROID_V2_PLAN.md` §5.1 says are explicitly not removed at this stage. Returning
 * [PurgeResult.Deferred] keeps the journal intact, so nothing is reported as cleaned up that was
 * not: an interface with a `TODO` that silently returned success would produce exactly the
 * false-completion this journal exists to prevent.
 */
class UnimplementedScopePurger : UserScopePurger, CapabilityScopePurger {
    override suspend fun purgeUserScope(namespace: PurgeNamespace): PurgeResult =
        PurgeResult.Deferred(DEFERRED_REASON)

    override suspend fun purgeCapabilityScope(namespace: PurgeNamespace): PurgeResult =
        PurgeResult.Deferred(DEFERRED_REASON)

    private companion object {
        const val DEFERRED_REASON =
            "purge targets are not owned by this slice; journal retained for a later slice"
    }
}
