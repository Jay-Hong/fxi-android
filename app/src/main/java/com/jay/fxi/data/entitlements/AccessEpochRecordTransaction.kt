package com.jay.fxi.data.entitlements

import androidx.datastore.preferences.core.Preferences

/**
 * A decision made from the latest file snapshot inside the owner's atomic update.
 * Domain validation and complete candidate encoding belong to the caller, before [Confirm].
 * Neither branch establishes command history, settlement, or permission to use protected data.
 */
internal sealed interface RecordTransactionDecision<out T> {
    val value: T

    /**
     * Return an observation without changing the snapshot or confirming a previously failed read-back.
     * Rejection, conflict, and recovery decisions use this branch. DataStore may still have written
     * a corruption replacement before invoking the decision; this is not a promise of zero I/O.
     */
    data class Observe<T>(override val value: T) : RecordTransactionDecision<T>

    /**
     * Apply the complete validated candidate, preserving every unrelated key from the input snapshot.
     * An unchanged candidate is allowed. Only the owner may change `read_barrier`: if its cache needs
     * confirmation, it adds that barrier to this same update and clears the flag only on completion.
     */
    data class Confirm<T>(val candidate: Preferences, override val value: T) : RecordTransactionDecision<T>
}

/** Storage evidence only, never evidence of a prior command's execution or clean continuity. */
internal enum class RecordTransactionEvidence {
    /** The atomic update read the file under its write lock and returned normally. */
    LockedFileRead,

    /** A changed candidate or owner-added barrier completed its write scope, including rename. */
    CompletedWriteScope
}

/** Published only after the DataStore update returns; [snapshot] is a detached, frozen copy. */
internal data class RecordTransactionResult<T>(
    val value: T,
    val snapshot: Preferences,
    val evidence: RecordTransactionEvidence
)
