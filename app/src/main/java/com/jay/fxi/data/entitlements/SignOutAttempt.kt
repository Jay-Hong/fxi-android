package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence

/** Identifies one app sign-out attempt. Allocated by the coordinator, never reused in a process. */
@JvmInline
internal value class SignOutTicket(val value: Long)

/**
 * What an attempt knows about the teardown it was started for.
 *
 * Purge progress is tracked apart from this: [LANDED] means a rotation that reached the disk
 * retired the namespace the attempt was taking down, never that its data is gone.
 */
internal enum class TeardownKnowledge {
    /** The intent is confirmed not persisted, so this attempt owes nothing on disk. */
    NOT_OWED,

    /** The intent is persisted and nothing has retired the namespace it names yet. */
    OWED,

    /** A rotation that reached the disk retired that namespace. */
    LANDED
}

/** Where an app sign-out request ended up. Only [Armed] lets its caller run the sign-out. */
internal sealed interface SignOutStart {
    /** The request's fence is no longer live. Nothing was sealed or written. */
    data object Stale : SignOutStart

    /** An attempt for the same fence is already open. Being told about it is not a right to run it. */
    data class Joined(val ticket: SignOutTicket) : SignOutStart

    /** An attempt for another fence is open. */
    data class Busy(val ticket: SignOutTicket) : SignOutStart

    /** The intent is persisted: this caller, and only this one, may run the sign-out. */
    data class Armed(val ticket: SignOutTicket) : SignOutStart

    /** Access is sealed but the sign-out cannot run; recovery owns the attempt. */
    data class RecoveryRequired(val ticket: SignOutTicket) : SignOutStart
}

/** What reading back an unresolved edit established. */
internal enum class EditResolution {
    /** No edit of that attempt is waiting. */
    NOT_PENDING,

    /** Classified; identity events are admitted again. */
    RESOLVED,

    /** The record could not be read, or reading it was not allowed. Still unresolved. */
    STILL_UNKNOWN,

    /** The record is neither the edit's result nor where it started. Still unresolved, and nothing is guessed. */
    INCONSISTENT
}

/** The namespace edit whose outcome a [SignOutAttempt.Unresolved] is waiting to read back. */
internal enum class PendingEdit {
    /** A read that had to precede an edit failed. Nothing was written. */
    READ,
    BEGIN_SIGN_OUT,
    BIND_OWNER,

    /** The rotation a real end runs. A landed-but-unknown end needs its receipt to preserve landing knowledge on retry. */
    END,

    /** Recovery's rotation of an intent still owed on disk. */
    SETTLE,

    /** A cold start's retirement when its first observation is no uid. No attempt ever owns it. */
    UNVERIFIED_START
}

/**
 * An identity event the coordinator held because its edit failed, kept until that same event is
 * applied.
 *
 * Resolving the edit reopens admission, but the binder retries the event only later. The retry is
 * checked against this, and a landed end's result waits here for it.
 */
internal sealed interface HeldIdentityEvent {
    val ticket: SignOutTicket

    data class Bind(override val ticket: SignOutTicket, val fence: AuthIdentityFence) : HeldIdentityEvent

    /** [receipt] is set once a read-back confirms the end's rotation landed; the retry uses it instead of rotating. */
    data class End(
        override val ticket: SignOutTicket,
        val ended: AuthIdentityFence,
        val receipt: EditResult.Landed? = null
    ) : HeldIdentityEvent

    /** A recovery barrier whose own disk step failed; [bind] says how far that step got. */
    data class Barrier(
        override val ticket: SignOutTicket,
        val candidate: AuthIdentityFence,
        val bind: BarrierBind
    ) : HeldIdentityEvent
}

/**
 * How far a held barrier's bind got. Kept apart from the attempt's teardown knowledge: the record
 * has no auth generation, so only a bind that actually ran can complete the candidate's binding.
 */
internal sealed interface BarrierBind {
    /** The read before the bind failed; the bind never ran. */
    data object NotStarted : BarrierBind

    /** The bind ran and failed. [before] is the record it started from. */
    data class Unknown(val before: AccessEpochRecord) : BarrierBind

    /** A read-back confirmed the candidate bound, a permitted no-op included. */
    data object Landed : BarrierBind

    /** A read-back confirmed the bind did not land. */
    data object NotLanded : BarrierBind
}

/**
 * What the identity FIFO and a recovery run watch: which attempt is open, whether it holds events,
 * and whether recovery owns it.
 */
internal data class AttemptSignal(
    val ticket: SignOutTicket?,
    val revision: Long,
    val held: Boolean,
    val recovering: Boolean = false
)

/** One step of recovery, outside the identity FIFO. */
internal enum class RecoveryAdvance {
    /** No attempt, or another one. */
    CLOSED,

    /** The original driver still holds the right to run the sign-out. */
    DRIVER_OWNS,

    /** A read-back classified an unresolved edit; call again. */
    RESOLVED,

    /** A settling rotation landed; call again. */
    PROGRESSED,

    /** An edit's outcome is not established: it just failed, or its read-back did. */
    UNRESOLVED,

    /** The disk contradicts what the attempt knows. Sealed; nothing is guessed. */
    INCONSISTENT,

    /** Further progress requires the identity FIFO: finish its held event, or run a recovery barrier. */
    NEEDS_BARRIER
}

/**
 * How one recovery run ended; calling again is the caller's decision.
 *
 * FINISHED means this run observed a RELEASED reply. CLOSED means the ticket was no longer open or a
 * CLOSED reply was observed; it does not imply that the seal remained held. An outstanding barrier
 * may finish after the run returns.
 */
internal enum class RecoveryOutcome {
    FINISHED,
    CLOSED,
    DRIVER_OWNS,
    UNRESOLVED,
    INCONSISTENT,
    HOLD,
    CLEANUP_FAILED,

    /** The run used up its barrier re-entries while the identity kept moving. Still sealed. */
    RETRY_LATER
}

/**
 * What the process knows about the open attempt's automatic recovery, for the recovery banner.
 *
 * Kept only while [ticket] is the open attempt: it goes when the attempt ends, and a late result
 * for an ended attempt is not written. [outcome] is null until a run returns.
 */
internal data class SignOutRecoveryStatus(
    val ticket: SignOutTicket,
    val running: Boolean,
    val outcome: RecoveryOutcome? = null,
    /** The run ended with an exception rather than an outcome. */
    val failed: Boolean = false
)

internal enum class BarrierStep {
    CLOSED,
    DRIVER_OWNS,

    /** An edit is unresolved. Judge the barrier again, with the same candidate, once it is resolved. */
    HELD,
    NOT_READY,
    RECAPTURE,

    /** Nobody is live, but the end of the completed binding is not proven. An observation, not a diagnosis. */
    HOLD,
    CLEANUP_FAILED,
    RELEASED
}

/**
 * A recovery barrier's result.
 *
 * [completed] is the coordinator's completed binding when the step ended, whatever the step, so the
 * consumer can adopt it before its next item. [releasedGeneration] is set only when the seal was
 * released onto a bound candidate, for the query pinned to that binding.
 */
internal data class BarrierOutcome(
    val step: BarrierStep,
    val completed: AuthIdentityFence?,
    val releasedGeneration: AccessDecisionGeneration? = null
)

/** What a namespace edit is known to have done. */
internal sealed interface EditResult {
    /**
     * The requested edit completed normally, including a permitted no-op, or read-back confirmed
     * that edit's durable result. A successful read alone does not establish this outcome.
     * [after] must belong to that edit; callers classify an unchanged result after a failed write
     * separately rather than treating the read itself as a landed edit.
     */
    data class Landed(val after: AccessEpochRecord) : EditResult

    /**
     * The edit did not take effect: it was not attempted, or a read-back after a failed attempt
     * showed it absent.
     */
    data object NotAttempted : EditResult

    /** Neither the edit nor a read-back established what happened. */
    data class Unknown(val before: AccessEpochRecord?) : EditResult
}

/**
 * An app sign-out in progress. While one exists, protected access stays sealed.
 *
 * Only [Armed] carries the right to run the authentication side effects (unregister, CAS, Firebase
 * sign-out), and only for the driver that created it: a repeated request is told about the
 * attempt, it does not get to run it again.
 */
internal sealed interface SignOutAttempt {
    val ticket: SignOutTicket
    val fence: AuthIdentityFence

    /** Access is sealed; the intent write's outcome is not established yet. */
    data class Preparing(
        override val ticket: SignOutTicket,
        override val fence: AuthIdentityFence
    ) : SignOutAttempt

    /** The intent is persisted and confirmed; the original driver may proceed. */
    data class Armed(
        override val ticket: SignOutTicket,
        override val fence: AuthIdentityFence
    ) : SignOutAttempt

    /** The driver's run is over or was taken away; recovery owns what is left. */
    data class Recovering(
        override val ticket: SignOutTicket,
        override val fence: AuthIdentityFence,
        val knowledge: TeardownKnowledge
    ) : SignOutAttempt

    /**
     * An edit's outcome is unknown. No further namespace change until it is read back.
     *
     * [knownBefore] is what the attempt had established before that edit; [before] is the record
     * the edit started from, kept so a read-back can tell what the edit did.
     */
    data class Unresolved(
        override val ticket: SignOutTicket,
        override val fence: AuthIdentityFence,
        val edit: PendingEdit,
        val knownBefore: TeardownKnowledge,
        val before: AccessEpochRecord?
    ) : SignOutAttempt
}

/**
 * The transition table for an app sign-out attempt, agreed with review before any wiring.
 *
 * Pure by construction, like [PremiumAccessReducer]: no I/O, no clock, no coroutines. The
 * coordinator reads the disk and the live identity, asks this what to do, performs it, and feeds the
 * result back.
 *
 * Three rules are easy to state loosely and get wrong, so they are named here:
 *  1. [retired] checks for a journal receipt in the edit result before purge, rather than comparing ids.
 *  2. An end is rotated whenever the disk owner is the uid whose session ended, whatever the attempt
 *     already knows. Skipping it would lean on "nothing was written while sealed", which nothing
 *     here proves. See [planEnd].
 *  3. Nothing here repairs a binding. A prologue that finds the binding under its fence incomplete
 *     asks for recovery instead of binding it itself. See [prologue].
 */
internal object SignOutAttemptPolicy {

    sealed interface Request {
        /** No attempt is open: this request may start one. */
        data object Start : Request

        /** An attempt for the same fence is open. Being told about it is not a right to run it. */
        data class Joined(val ticket: SignOutTicket) : Request

        /** An attempt for another fence is open. */
        data class Busy(val ticket: SignOutTicket) : Request
    }

    fun request(current: SignOutAttempt?, fence: AuthIdentityFence): Request = when {
        current == null -> Request.Start
        current.fence == fence -> Request.Joined(current.ticket)
        else -> Request.Busy(current.ticket)
    }

    /** Refresh, probe, apply and topic rejection are admitted only while no attempt is open. */
    fun admitsAccessQueries(current: SignOutAttempt?): Boolean = current == null

    /** Identity events wait while an edit's outcome is still being established. */
    fun admitsIdentityEvents(current: SignOutAttempt?): Boolean =
        current !is SignOutAttempt.Preparing && current !is SignOutAttempt.Unresolved

    sealed interface Prologue {
        /** The request's fence is no longer live. Do not start its intent write. */
        data object Stale : Prologue

        /** The disk owner could not be read. Sealed; nothing written. */
        data class Unreadable(val attempt: SignOutAttempt.Unresolved) : Prologue

        /** Live is the request's fence, but the binding under it is not complete. Sealed. */
        data class RecoveryRequired(val attempt: SignOutAttempt.Recovering) : Prologue

        /** Sealed; persist the intent next. */
        data class Persist(val attempt: SignOutAttempt.Preparing) : Prologue
    }

    /**
     * Runs on the identity FIFO, after every event queued before it.
     *
     * [completed] is the fence whose binding reached the disk — not the fence last published, which
     * is set before the disk work. [diskOwner] fails when the record could not be read.
     */
    fun prologue(
        ticket: SignOutTicket,
        fence: AuthIdentityFence,
        live: AuthIdentityFence?,
        completed: AuthIdentityFence?,
        diskOwner: Result<String?>
    ): Prologue {
        if (live != fence) return Prologue.Stale
        val owner = diskOwner.getOrElse {
            return Prologue.Unreadable(
                SignOutAttempt.Unresolved(ticket, fence, PendingEdit.READ, TeardownKnowledge.NOT_OWED, before = null)
            )
        }
        if (completed != fence || owner != fence.uid) {
            return Prologue.RecoveryRequired(
                SignOutAttempt.Recovering(ticket, fence, TeardownKnowledge.NOT_OWED)
            )
        }
        return Prologue.Persist(SignOutAttempt.Preparing(ticket, fence))
    }

    /** Whether [record] carries the intent of [uid]: named as owner and as owed. */
    fun holdsIntent(record: AccessEpochRecord, uid: String): Boolean =
        record.ownerUid == uid && record.teardownOwedFor == uid

    /**
     * Where a preparing attempt goes once the intent write is settled one way or the other.
     *
     * A write that reached the disk arms only if the live identity is still the request's: a session
     * that moved meanwhile has no one entitled to run its sign-out.
     */
    fun intentWritten(
        attempt: SignOutAttempt.Preparing,
        write: EditResult,
        live: AuthIdentityFence?
    ): SignOutAttempt = when (write) {
        is EditResult.Landed -> when {
            !holdsIntent(write.after, attempt.fence.uid) ->
                SignOutAttempt.Recovering(attempt.ticket, attempt.fence, TeardownKnowledge.NOT_OWED)
            live == attempt.fence -> SignOutAttempt.Armed(attempt.ticket, attempt.fence)
            else -> SignOutAttempt.Recovering(attempt.ticket, attempt.fence, TeardownKnowledge.OWED)
        }
        EditResult.NotAttempted ->
            SignOutAttempt.Recovering(attempt.ticket, attempt.fence, TeardownKnowledge.NOT_OWED)
        is EditResult.Unknown -> SignOutAttempt.Unresolved(
            attempt.ticket,
            attempt.fence,
            PendingEdit.BEGIN_SIGN_OUT,
            TeardownKnowledge.NOT_OWED,
            write.before
        )
    }

    /**
     * Whether [after]'s journal contains the user epoch [uid] held in [before].
     *
     * Inputs must be coherent records produced by the store transitions without epoch reuse.
     * Pass the edit's returned record before resuming purge; cleanup can remove the entry.
     *
     * This helper deliberately uses journal membership. A false result after cleanup does not
     * establish that no rotation occurred, and this helper does not classify such read-backs.
     */
    fun retired(before: AccessEpochRecord, after: AccessEpochRecord, uid: String): Boolean {
        if (before.ownerUid != uid) return false
        val userEpoch = before.userAccessEpoch ?: return false
        // The epoch alone identifies the entry: a rotation hands the live epoch to the journal and
        // mints a new one, epochs are never reused, and only a user-axis rotation records one.
        return after.pendingPurges.any { it.userAccessEpoch == userEpoch }
    }

    /**
     * A binding reached the disk while an attempt was open.
     *
     * The attempt loses any right to run authentication side effects — the session it was started
     * for is no longer the one bound. Whether it also landed comes from the edit's own receipt.
     */
    fun identityBound(attempt: SignOutAttempt, retiredAttemptNamespace: Boolean): SignOutAttempt.Recovering {
        val knownBefore = knowledgeOf(attempt)
        val knowledge = if (retiredAttemptNamespace) TeardownKnowledge.LANDED else knownBefore
        return SignOutAttempt.Recovering(attempt.ticket, attempt.fence, knowledge)
    }

    enum class EndPlan {
        /** Rotate the current owner. */
        ROTATE,

        /** The disk owner is not the uid whose session ended: rotating would take down someone else's. */
        LEAVE_DISK
    }

    /** Deliberately blind to what an open attempt knows. See rule 2 on the object. */
    fun planEnd(ended: AuthIdentityFence, diskOwner: String?): EndPlan =
        if (diskOwner == ended.uid) EndPlan.ROTATE else EndPlan.LEAVE_DISK

    /**
     * Where an open attempt stands after a real end was handled; null when it is finished.
     *
     * Finished means: its own session ended, nobody is signed in, and the end's rotation reached the
     * disk. Anything short of that keeps the attempt, and the seal, in recovery.
     *
     * This function decides only the namespace result. Before applying a null result, the caller
     * must accept cleanup as Completed or Deferred. On purge/clear failure it must instead retain
     * Recovering(LANDED). Cleanup retries must not replay this end's rotation; the driver is
     * responsible for keeping those operations separate.
     */
    fun afterEnd(
        attempt: SignOutAttempt,
        ended: AuthIdentityFence,
        rotation: EditResult,
        live: AuthIdentityFence?
    ): SignOutAttempt? {
        val knownBefore = knowledgeOf(attempt)
        return when (rotation) {
            is EditResult.Unknown -> SignOutAttempt.Unresolved(
                attempt.ticket,
                attempt.fence,
                PendingEdit.END,
                knownBefore,
                rotation.before
            )
            EditResult.NotAttempted ->
                SignOutAttempt.Recovering(attempt.ticket, attempt.fence, knownBefore)
            is EditResult.Landed -> {
                val knowledge =
                    if (ended.uid == attempt.fence.uid) TeardownKnowledge.LANDED else knownBefore
                if (ended == attempt.fence && live == null) null
                else SignOutAttempt.Recovering(attempt.ticket, attempt.fence, knowledge)
            }
        }
    }

    /** The original driver stopped before its sign-out landed. Recovery takes over. */
    fun driverStopped(attempt: SignOutAttempt): SignOutAttempt = when (attempt) {
        is SignOutAttempt.Armed ->
            SignOutAttempt.Recovering(attempt.ticket, attempt.fence, TeardownKnowledge.OWED)
        // Already taken away — by a binding, an end, or an unresolved edit — and owned from there.
        else -> attempt
    }

    /** An edit an open attempt depends on failed without establishing what it did. */
    fun editFailed(attempt: SignOutAttempt, edit: PendingEdit, before: AccessEpochRecord?): SignOutAttempt.Unresolved =
        SignOutAttempt.Unresolved(attempt.ticket, attempt.fence, edit, knowledgeOf(attempt), before)

    sealed interface Resolution {
        /** [endReceipt] is set only for an [PendingEdit.END] that read back as landed. */
        data class Resolved(
            val next: SignOutAttempt.Recovering,
            val endReceipt: EditResult.Landed? = null
        ) : Resolution

        /** The read-back is neither the edit's result nor the record it started from. Nothing is guessed. */
        data object Inconsistent : Resolution
    }

    /**
     * Classifies an unresolved edit from a record read back after it.
     *
     * [readBack] must come before any purge, for the reason [retired] gives.
     */
    fun resolve(attempt: SignOutAttempt.Unresolved, readBack: AccessEpochRecord): Resolution {
        fun recovering(knowledge: TeardownKnowledge) =
            SignOutAttempt.Recovering(attempt.ticket, attempt.fence, knowledge)
        val known = attempt.knownBefore
        return when (attempt.edit) {
            PendingEdit.READ -> Resolution.Resolved(recovering(known))
            PendingEdit.BEGIN_SIGN_OUT -> Resolution.Resolved(
                recovering(
                    if (holdsIntent(readBack, attempt.fence.uid)) TeardownKnowledge.OWED else TeardownKnowledge.NOT_OWED
                )
            )
            // Whether a bind itself landed is left to the held event's retry, which repeats a landed
            // bind as a no-op.
            // A settle that did not land leaves the intent on disk for recover() to find again.
            PendingEdit.BIND_OWNER, PendingEdit.SETTLE -> {
                val before = checkNotNull(attempt.before) { "${attempt.edit} keeps the record it started from" }
                Resolution.Resolved(
                    recovering(if (retired(before, readBack, attempt.fence.uid)) TeardownKnowledge.LANDED else known)
                )
            }
            // An end cannot be retried blind. `signOut` now gives up the owner, so `planEnd` will not
            // rotate a second time before something binds again — but a retry then reads as
            // NotAttempted, which keeps this attempt's prior knowledge. The receipt below is what
            // turns a landed-but-unknown end into a landed one.
            PendingEdit.END -> {
                val before = checkNotNull(attempt.before) { "END keeps the record it started from" }
                val owner = before.ownerUid
                when {
                    owner != null && retired(before, readBack, owner) ->
                        Resolution.Resolved(recovering(known), endReceipt = EditResult.Landed(readBack))
                    sameNamespace(before, readBack) -> Resolution.Resolved(recovering(known))
                    else -> Resolution.Inconsistent
                }
            }
            // Only the identity consumer's first observation starts one, before any sign-out can be
            // requested, and its failures are held as unowned work — never as an attempt's edit.
            PendingEdit.UNVERIFIED_START ->
                error("an unverified start's edit cannot belong to a sign-out attempt")
        }
    }

    /** What `bindOwner(uid)` leaves behind, whether it bound, settled or found nothing to do. */
    fun bindLanded(record: AccessEpochRecord, uid: String): Boolean =
        record.ownerUid == uid &&
            record.teardownOwedFor == null &&
            record.userAccessEpoch != null &&
            record.krxCapabilityEpoch != null

    /**
     * How a barrier's bind that ran and failed turned out; null when [readBack] shows neither.
     *
     * A record already meeting the postcondition counts as landed: the bind ran, and a no-op is one of
     * its permitted results.
     */
    fun barrierBindResolved(
        bind: BarrierBind.Unknown,
        candidate: AuthIdentityFence,
        readBack: AccessEpochRecord
    ): BarrierBind? = when {
        bindLanded(readBack, candidate.uid) -> BarrierBind.Landed
        sameNamespace(bind.before, readBack) -> BarrierBind.NotLanded
        else -> null
    }

    /** What a read-back says about an edit that no attempt owned. */
    internal sealed interface UnownedResolution {
        /** The write never started, or it did not land: the same work can run again. */
        data class RunAgain(val before: AccessEpochRecord?) : UnownedResolution

        /** The write landed: only the cleanup behind it is left. */
        data object Landed : UnownedResolution

        /** Neither the edit's result nor the record it started from. Nothing is guessed. */
        data object Undecidable : UnownedResolution
    }

    /**
     * Whether establishing what [edit] did needs the record read back.
     *
     * A READ starts no write, so there is nothing in the record for a read-back to find — and the
     * caller would be spending a read on a question with no answer, at the one moment reads are
     * known to be failing. [resolveUnownedEdit] gives the same answer to a caller that read anyway.
     */
    internal fun needsReadBack(edit: PendingEdit): Boolean = edit != PendingEdit.READ

    /**
     * Classifies an unowned edit from a record read back after it, by the work it was persisting.
     *
     * The predicates are the ones the attempt paths already use, and the split follows what each
     * edit can have done. A READ never starts the write that follows it, so its resolution is not a
     * question about landing at all — and a record that happens to satisfy the postcondition does
     * not turn it into one. A bind is judged the way a barrier's bind is, by its postcondition, and
     * an end by whether its rotation reached the journal.
     */
    internal fun resolveUnownedEdit(
        work: IdentityWork,
        edit: PendingEdit,
        before: AccessEpochRecord?,
        readBack: AccessEpochRecord
    ): UnownedResolution = when {
        edit == PendingEdit.READ -> UnownedResolution.RunAgain(before)
        work is IdentityWork.Bind && edit == PendingEdit.BIND_OWNER -> when {
            bindLanded(readBack, work.fence.uid) -> UnownedResolution.Landed
            before != null && sameNamespace(before, readBack) -> UnownedResolution.RunAgain(before)
            else -> UnownedResolution.Undecidable
        }
        work is IdentityWork.End && edit == PendingEdit.END -> {
            val owner = before?.ownerUid
            when {
                owner != null && retired(before, readBack, owner) -> UnownedResolution.Landed
                before != null && sameNamespace(before, readBack) -> UnownedResolution.RunAgain(before)
                else -> UnownedResolution.Undecidable
            }
        }
        work is IdentityWork.UnverifiedStart && edit == PendingEdit.UNVERIFIED_START -> when {
            before == null -> UnownedResolution.Undecidable
            // First, whatever the plan was: a marker set after the read can turn a dropped intent into
            // a rotation inside the store's edit, and that rotation landing is still this work landing.
            rotationLanded(before, readBack) -> UnownedResolution.Landed
            planUnverifiedStart(before) == UnverifiedStartPlan.DROP_STALE_INTENT &&
                sameNamespace(before.copy(teardownOwedFor = null), readBack) -> UnownedResolution.Landed
            // The read-back, not `before`: a marker added meanwhile must decide the next plan.
            sameNamespace(before, readBack) -> UnownedResolution.RunAgain(readBack)
            else -> UnownedResolution.Undecidable
        }
        // A startup purge is not an identity edit: nothing about it lands in the record, so the only
        // answer a read-back gives is that the resume can be tried again.
        work is IdentityWork.StartupPurge -> UnownedResolution.RunAgain(before)
        else -> UnownedResolution.Undecidable
    }

    /** What a cold start whose first observation is no uid does to [record]; see [planUnverifiedStart]. */
    enum class UnverifiedStartPlan {
        /** An owner is on the record: retire it the way a landed sign-out does. */
        RETIRE_OWNER,

        /** No owner, but a marker says protected data may be here: retire with the owner unknown. */
        RETIRE_UNOWNED,

        /** Nothing to retire, only an intent that names nobody bound: drop it. */
        DROP_STALE_INTENT,

        /** Nothing owed. The read that found this is when the work is complete. */
        LEAVE
    }

    /** Mirrors [AccessEpochTransitions.retireUnverifiedStart] branch for branch, deciding only. */
    fun planUnverifiedStart(record: AccessEpochRecord): UnverifiedStartPlan = when {
        record.ownerUid != null -> UnverifiedStartPlan.RETIRE_OWNER
        record.mayContainPremiumData || record.mayContainKrxData -> UnverifiedStartPlan.RETIRE_UNOWNED
        record.teardownOwedFor != null -> UnverifiedStartPlan.DROP_STALE_INTENT
        else -> UnverifiedStartPlan.LEAVE
    }

    /**
     * Whether [readBack] is exactly the both-axes rotation of [before] that retires its namespace.
     *
     * [retired]'s evidence is an epoch reaching the journal, which needs no owner; this is that
     * evidence made exact so it also holds with no owner or null epochs. The journal must be the old
     * one plus precisely the retiring entry, in order and count — so an entry of unknown epochs that
     * was already owed is never taken for this rotation's receipt — and both epochs must be new.
     * Markers are not compared: they may stand again in the new namespace after the landing.
     *
     * [readBack] must come before any purge, for the reason [retired] gives.
     */
    fun rotationLanded(before: AccessEpochRecord, readBack: AccessEpochRecord): Boolean {
        fun fresh(next: String?, old: String?) = next != null && next != old
        val receipt = PendingPurge(
            ownerUid = before.ownerUid,
            userAccessEpoch = before.userAccessEpoch,
            krxCapabilityEpoch = before.krxCapabilityEpoch,
            scopes = AccessEpochTransitions.ALL_SCOPES
        )
        return readBack.ownerUid == null &&
            readBack.teardownOwedFor == null &&
            fresh(readBack.userAccessEpoch, before.userAccessEpoch) &&
            fresh(readBack.krxCapabilityEpoch, before.krxCapabilityEpoch) &&
            readBack.pendingPurges == before.pendingPurges + receipt
    }

    /**
     * The fields a rotation changes. The mayContain markers are left out: they are not what tells a
     * rotation apart, and their store entry point does not go through the coordinator's lock.
     */
    private fun sameNamespace(a: AccessEpochRecord, b: AccessEpochRecord): Boolean =
        a.ownerUid == b.ownerUid &&
            a.userAccessEpoch == b.userAccessEpoch &&
            a.krxCapabilityEpoch == b.krxCapabilityEpoch &&
            a.teardownOwedFor == b.teardownOwedFor &&
            a.pendingPurges == b.pendingPurges

    enum class RecoveryStep {
        /** The intent is still owed on disk: settle it with one rotation. */
        SETTLE,

        /** Nothing left to settle: bind the live identity through the FIFO barrier. */
        AWAIT_BARRIER,

        /** Memory says owed, the disk does not name this uid as owed. Nothing is guessed; stay sealed. */
        INCONSISTENT
    }

    fun recover(attempt: SignOutAttempt.Recovering, disk: AccessEpochRecord): RecoveryStep =
        when (attempt.knowledge) {
            TeardownKnowledge.OWED ->
                if (holdsIntent(disk, attempt.fence.uid)) RecoveryStep.SETTLE else RecoveryStep.INCONSISTENT
            TeardownKnowledge.NOT_OWED, TeardownKnowledge.LANDED -> RecoveryStep.AWAIT_BARRIER
        }

    /** Where recovery stands after its settling rotation. */
    fun settled(attempt: SignOutAttempt.Recovering, rotation: EditResult): SignOutAttempt = when (rotation) {
        is EditResult.Landed -> SignOutAttempt.Recovering(attempt.ticket, attempt.fence, TeardownKnowledge.LANDED)
        EditResult.NotAttempted -> attempt
        is EditResult.Unknown -> SignOutAttempt.Unresolved(
            attempt.ticket,
            attempt.fence,
            PendingEdit.SETTLE,
            attempt.knowledge,
            rotation.before
        )
    }

    sealed interface Barrier {
        /** The intent is still owed: settle first. */
        data object NotReady : Barrier

        /** The live identity moved since the candidate was captured: capture again and re-enqueue. */
        data object Recapture : Barrier

        /** Nothing to do yet; stay sealed. */
        data object Hold : Barrier

        /** Bind the candidate, then check again with [barrierBound]. */
        data class Bind(val uid: String) : Barrier

        /** Nobody is signed in and that end has been processed: finish without a query. */
        data object FinishSignedOut : Barrier
    }

    /**
     * Recovery's last step, run on the identity FIFO.
     *
     * [candidate] was captured before the barrier was enqueued. A live identity read while it is
     * processed is not adopted: the event that identity published may still be queued behind it.
     */
    fun barrier(
        attempt: SignOutAttempt.Recovering,
        candidate: AuthIdentityFence?,
        live: AuthIdentityFence?,
        completed: AuthIdentityFence?
    ): Barrier = when {
        attempt.knowledge == TeardownKnowledge.OWED -> Barrier.NotReady
        live != candidate -> Barrier.Recapture
        candidate != null -> Barrier.Bind(candidate.uid)
        completed == null -> Barrier.FinishSignedOut
        else -> Barrier.Hold
    }

    /** The barrier may finish only if its bind reached the disk and the candidate is still live. */
    fun barrierBound(candidate: AuthIdentityFence, bind: EditResult, liveAfter: AuthIdentityFence?): Boolean =
        bind is EditResult.Landed && liveAfter == candidate

    private fun knowledgeOf(attempt: SignOutAttempt): TeardownKnowledge = when (attempt) {
        is SignOutAttempt.Armed -> TeardownKnowledge.OWED
        is SignOutAttempt.Recovering -> attempt.knowledge
        // An edit's outcome is still open, so nothing is settled. Identity events wait behind
        // [admitsIdentityEvents]; reaching here means the wiring let one through.
        is SignOutAttempt.Preparing, is SignOutAttempt.Unresolved ->
            error("an attempt with an edit in flight has no settled knowledge")
    }
}
