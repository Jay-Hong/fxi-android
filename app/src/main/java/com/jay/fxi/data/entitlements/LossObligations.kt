package com.jay.fxi.data.entitlements

/**
 * One axis of a namespace that a validated explicit loss must retire, as the record stood when the loss was decided.
 *
 * Kept by value, not by reference to that record: a later record can have moved on for reasons of its own, and what
 * has to be established is only whether this namespace was handed to cleanup.
 */
data class LossObligation(
    val ownerUid: String?,
    val axis: PurgeScope,
    val epoch: String
)

/** Where [LossObligation] stands against a record whose persistence is established. */
enum class ObligationStatus {
    /** The namespace is still live on that axis. */
    STILL_CURRENT,

    /** The namespace was replaced and its cleanup was handed to the journal. */
    RETIRED,

    /** The namespace was replaced, but nothing shows its cleanup was handed on. Not resolved by allocating another. */
    UNKNOWN
}

object LossObligations {

    fun epochOf(record: AccessEpochRecord, axis: PurgeScope): String? = when (axis) {
        PurgeScope.USER -> record.userAccessEpoch
        PurgeScope.CAPABILITY -> record.krxCapabilityEpoch
    }

    private fun epochOf(entry: PendingPurge, axis: PurgeScope): String? = when (axis) {
        PurgeScope.USER -> entry.userAccessEpoch
        PurgeScope.CAPABILITY -> entry.krxCapabilityEpoch
    }

    /**
     * Whether [entry] owes the cleanup of [obligation]'s namespace.
     *
     * [PendingPurge] reads a null owner as every owner and a null epoch as every past namespace of that axis except
     * the live one, so either one covers. The live one is not a concern here: [judge] asks this only after the
     * obligation's epoch has left the record.
     */
    fun covers(entry: PendingPurge, obligation: LossObligation): Boolean {
        if (obligation.axis !in entry.scopes) return false
        if (entry.ownerUid != null && entry.ownerUid != obligation.ownerUid) return false
        val entryEpoch = epochOf(entry, obligation.axis)
        return entryEpoch == null || entryEpoch == obligation.epoch
    }

    /**
     * [record] must be one whose persistence is established.
     *
     * An epoch that differs is not evidence enough: an empty or incomplete record can drop the epoch without
     * journalling it, and a namespace allocated afterwards differs just the same. A covering entry that a completed
     * purge later removes is not needed again: [LossSealLedger] drops an obligation the first time a confirmed record
     * shows it retired, and the coordinator reads the record before it completes a purge.
     */
    fun judge(obligation: LossObligation, record: AccessEpochRecord): ObligationStatus = when {
        epochOf(record, obligation.axis) == obligation.epoch -> ObligationStatus.STILL_CURRENT
        record.pendingPurges.any { covers(it, obligation) } -> ObligationStatus.RETIRED
        else -> ObligationStatus.UNKNOWN
    }
}
