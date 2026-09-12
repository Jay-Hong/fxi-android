package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence

/**
 * What a persistence hold is for.
 *
 * [HeldIdentityEvent] cannot stand in: it requires a [SignOutTicket], and these holds exist exactly
 * when no sign-out attempt owns the work. Faking a ticket would put a made-up attempt into the
 * transitions that read one.
 */
internal sealed interface IdentityWork {

    /** The purge resume the consumer runs before it accepts any identity event. */
    data object StartupPurge : IdentityWork

    data class Bind(val fence: AuthIdentityFence) : IdentityWork

    data class End(val ended: AuthIdentityFence) : IdentityWork
}

/** What a finished head task hands back, so the caller can bind its follow-up to this work. */
internal data class IdentityCompletion(
    val completed: AuthIdentityFence?,
    /** Only a bind needs a query afterwards; an end and a startup purge carry null. */
    val queryGeneration: AccessDecisionGeneration?
)

/** Why no automatic round is scheduled. Not the same fact as "the budget ran out". */
internal enum class NoAutoRetry {
    BUDGET_EXHAUSTED,

    /** A read-back matched neither the edit's result nor the record it started from. Nothing is guessed. */
    UNDECIDABLE
}

/**
 * One head task's persistence hold, from its first unknown edit to its completion.
 *
 * The id is issued once and kept: a new id per failure would hand every failure a fresh budget. The
 * phase says what is left to do, which is not the same question as whether a retry may run now —
 * that is [nextAttemptAt], [spent] and [wakeRequested].
 */
internal data class PendingPersistence(
    val id: Long,
    val work: IdentityWork,
    val phase: Phase,
    /** Raised on every state change. A waiter observes from the value it was handed. */
    val revision: Long,
    /** Rounds consumed by the current recovery batch. The first failure does not consume one. */
    val spent: Int,
    /** When the next automatic round may run, or null when none is scheduled. Never extended by a re-judgement. */
    val nextAttemptAt: Long?,
    /** A manual wake that has not been spent yet. Duplicates coalesce into this one flag. */
    val wakeRequested: Boolean = false,
    /**
     * Why no automatic round is scheduled, or null while one is.
     *
     * Kept here rather than recomputed: an undecidable read-back and an exhausted budget both leave
     * no deadline, so the state has to carry which one it was — a caller that re-derived it from
     * somewhere else would be guessing.
     */
    val blocked: NoAutoRetry? = null
) {
    sealed interface Phase {

        /**
         * A disk step failed and what it did is not established yet.
         *
         * A write needs the record read back before anything else runs. A READ does not: it starts
         * no write, so there is nothing in the record to find and the retry simply reads again.
         */
        data class Unknown(val edit: PendingEdit, val before: AccessEpochRecord?) : Phase

        /**
         * The same work can be run again: either its write never started, or a read-back showed it
         * did not land. It is not "the record looks right", which a write that never ran also gives.
         */
        data class ReadyToRetry(val before: AccessEpochRecord?) : Phase

        /**
         * The write landed and has been adopted. Only the purge resume behind it is left.
         *
         * The completion is carried rather than recomputed: it belongs to the moment the landing
         * was established, and the round that finishes the cleanup must not mint another.
         */
        data class CleanupPending(val completion: IdentityCompletion) : Phase
    }
}

/**
 * What one step of a head task did, decided under the coordinator's lock.
 *
 * The caller waits outside that lock, so by the time it comes back the answer it holds may be old.
 * Every result therefore names what to observe, and the coordinator judges again before it acts —
 * the same rule the attempt signal already follows.
 */
internal sealed interface IdentityStep {

    /** This head task is done. Nothing else in this result may be read as completion. */
    data class Applied(val completion: IdentityCompletion) : IdentityStep

    /** An open sign-out's recovery owns the resolution. Wait for that attempt to admit events again. */
    data class AwaitAttempt(val ticket: SignOutTicket, val revision: Long) : IdentityStep

    /**
     * This task's own persistence owns the resolution.
     *
     * [nextAttemptAt] is when an automatic round may run; null means none is scheduled and [blocked]
     * says why. A waiter watches for a revision above [afterRevision], but a timer may also wake it
     * with no new revision at all.
     */
    data class AwaitPersistence(
        val id: Long,
        val afterRevision: Long,
        val nextAttemptAt: Long?,
        val blocked: NoAutoRetry?
    ) : IdentityStep

    /** The id named by a resume is not the pending one any more. It completes nothing by itself. */
    data class StaleResume(val id: Long) : IdentityStep
}

/**
 * The rules a persistence hold follows, kept pure so the budget can be read without a coordinator.
 *
 * A round is one admitted [PersistenceRecoveryPolicy.admitRound] to the first failure after it: a read-back
 * and the re-execution it authorises belong to the same round, and a failed read-back spends it.
 * Neither a successful read-back nor a phase change resets anything.
 */
internal object PersistenceRecoveryPolicy {

    /** Rounds an automatic batch may take after the failure that opened the hold. */
    const val AUTOMATIC_ROUNDS = 3

    /**
     * Whether the resume named by [id] may run now, and what the hold looks like when it does.
     *
     * A wake that arrives while an automatic batch is still running is kept, not spent: it must not
     * skip that batch's delay. Only a batch that stopped — out of rounds, or undecidable — turns a
     * wake into a new batch, and that batch starts its own count.
     */
    fun admitRound(pending: PendingPersistence, id: Long, now: Long): Round = when {
        pending.id != id -> Round.Stale
        pending.nextAttemptAt != null && now >= pending.nextAttemptAt -> Round.Run(
            // Admitting spends the schedule, so the same deadline cannot be claimed twice. That is
            // half of the guarantee: the other half is the caller running admission, the disk work
            // and the result under one lock hold, so a second arrival only ever sees the state this
            // round left behind. A wake held through the round is still there afterwards, on purpose.
            pending.copy(spent = pending.spent + 1, nextAttemptAt = null, revision = pending.revision + 1)
        )
        pending.nextAttemptAt != null -> Round.TooEarly
        pending.wakeRequested -> Round.Run(
            pending.copy(spent = 1, wakeRequested = false, blocked = null, revision = pending.revision + 1)
        )
        else -> Round.Blocked
    }

    sealed interface Round {
        /** The hold moved on or was completed by something else. Consumes nothing. */
        data object Stale : Round

        /** Woken before the scheduled time. Consumes nothing. */
        data object TooEarly : Round

        /** Stopped, with no manual wake to spend. */
        data object Blocked : Round

        /** [pending] already has this round counted. */
        data class Run(val pending: PendingPersistence) : Round
    }

    /** What the hold looks like after a round failed, given the reason no automatic round follows. */
    fun afterFailedRound(
        pending: PendingPersistence,
        now: Long,
        delayMillis: Long,
        undecidable: Boolean
    ): PendingPersistence {
        val schedule = when {
            undecidable -> null
            pending.spent >= AUTOMATIC_ROUNDS -> null
            else -> now + delayMillis
        }
        return pending.copy(
            revision = pending.revision + 1,
            nextAttemptAt = schedule,
            blocked = when {
                schedule != null -> null
                undecidable -> NoAutoRetry.UNDECIDABLE
                else -> NoAutoRetry.BUDGET_EXHAUSTED
            }
        )
    }
}
