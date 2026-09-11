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

/** The namespace edit whose outcome a [SignOutAttempt.Unresolved] is waiting to read back. */
internal enum class PendingEdit { READ, BEGIN_SIGN_OUT, BIND_OWNER, SIGN_OUT }

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
                PendingEdit.SIGN_OUT,
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

    /**
     * Applies knowledge the caller established by reconciling this unresolved edit.
     * This function does not inspect a read-back record or classify the edit's outcome.
     */
    fun readBack(attempt: SignOutAttempt.Unresolved, knowledge: TeardownKnowledge): SignOutAttempt.Recovering =
        SignOutAttempt.Recovering(attempt.ticket, attempt.fence, knowledge)

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
            PendingEdit.SIGN_OUT,
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
