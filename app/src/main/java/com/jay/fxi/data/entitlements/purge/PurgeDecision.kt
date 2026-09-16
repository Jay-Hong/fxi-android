package com.jay.fxi.data.entitlements.purge

import com.jay.fxi.data.entitlements.PurgeNamespace
import com.jay.fxi.data.entitlements.PurgeScope

/**
 * Permission to delete a user's own preferences, carried by the deletion that asked for it.
 *
 * A [PurgeCause] of [PurgeCause.ACCOUNT_DELETION] on its own is not enough: a journal entry is a
 * record of a namespace being retired, and any path could name that cause. What authorises
 * removing the user's stored intent is the deletion's own durable obligation — whose owner it is,
 * which deletion it belongs to, and that the deletion has reached the phase where local state goes.
 *
 * P1 defines the shape and nothing mints one. The account-deletion paths hand one over in P3/S10
 * (purger 설계 v3 final §9), so today these targets are reported as still owed rather than deleted.
 */
data class DeletionAuthorization(
    val ownerUid: String,
    /** The deletion operation this belongs to, stable across process death. */
    val operationId: String,
    val localCleanupAllowed: Boolean
)

/** What this purger must do about one target, for one axis and one cause. */
enum class TargetDisposition {
    /** Delete it now. */
    DELETE_NOW,

    /** Data of this namespace that this purger may not delete. It keeps the journal owed. */
    OUTSTANDING,

    /** Not this obligation's business: another axis, control plane, or device state. */
    NOT_APPLICABLE
}

/**
 * Which targets a purge of one axis may delete, and which keep it unfinished.
 *
 * Pure, and deliberately the only place the rules live: the aggregation below counts dispositions
 * without re-deciding them, and the tests pin the table rather than the aggregation's behaviour.
 */
object PurgeDecision {

    fun disposition(
        target: PurgeTarget,
        scope: PurgeScope,
        cause: PurgeCause,
        namespace: PurgeNamespace,
        authorization: DeletionAuthorization?
    ): TargetDisposition {
        if (scope !in target.scopes) return TargetDisposition.NOT_APPLICABLE
        return when (target.classification) {
            PurgeClassification.DERIVED_HERE -> TargetDisposition.DELETE_NOW

            PurgeClassification.ACCOUNT_DELETION_ONLY -> when {
                // A capability revoke never reaches the user's own preferences (§7 S1).
                scope != PurgeScope.USER -> TargetDisposition.NOT_APPLICABLE
                cause != PurgeCause.ACCOUNT_DELETION -> TargetDisposition.NOT_APPLICABLE
                // The cause says a deletion, so this data is owed — but without the deletion's own
                // authorisation nothing here may remove it, and it must not be counted as done.
                authorizes(authorization, namespace) -> TargetDisposition.DELETE_NOW
                else -> TargetDisposition.OUTSTANDING
            }

            PurgeClassification.CUTOVER_OWNED,
            PurgeClassification.UNDER_REVIEW,
            PurgeClassification.HANDED_OVER -> TargetDisposition.OUTSTANDING

            PurgeClassification.NOT_USER_DATA -> TargetDisposition.NOT_APPLICABLE
        }
    }

    /**
     * Whether this authorisation covers this namespace.
     *
     * The owner has to be named on both sides and be the same person. A journal entry whose owner
     * could not be narrowed (`null`, written when the record itself was lost) is never covered:
     * deleting "whoever's preferences these are" is exactly the deletion this refuses.
     */
    private fun authorizes(authorization: DeletionAuthorization?, namespace: PurgeNamespace): Boolean {
        val auth = authorization ?: return false
        val owner = namespace.pending.ownerUid ?: return false
        return auth.localCleanupAllowed && auth.ownerUid == owner
    }
}
