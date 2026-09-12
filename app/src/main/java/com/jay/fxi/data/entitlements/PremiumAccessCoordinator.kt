package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Between automatic persistence rounds. Long enough that a transient disk fault can clear. */
private const val DEFAULT_PERSISTENCE_RETRY_DELAY_MILLIS = 2_000L

/**
 * Owns the D23 access state and serialises every input that can change it.
 *
 * Scope of this slice — deliberately narrow, see `ANDROID_V2_PLAN.md` §5.1 and the still-open
 * server D21 ordering contract:
 *  - It reads `/api/entitlements` over the existing protected transport, classifies the answer,
 *    runs [PremiumAccessReducer], persists epochs, and schedules re-queries.
 *  - It does **not** switch the Root surface, open a WebSocket, migrate legacy caches, or send the
 *    FCM teardown. Those change the app's permission boundary and are separate work.
 *
 * [AccessEffect.PushDelete] is recorded in [lastEffects] and never executed here. A later slice
 * consumes it once the server ordering contract exists; until then an executed teardown would be
 * a client-side rule with no server guarantee behind it.
 */
class PremiumAccessCoordinator(
    private val source: EntitlementsSource,
    private val store: AccessEpochStore,
    private val userPurger: UserScopePurger,
    private val capabilityPurger: CapabilityScopePurger,
    private val scope: CoroutineScope,
    private val clock: RecheckClock,
    private val jitter: ProbeJitter = ProbeJitter.Default,
    /** Delay between automatic persistence rounds. A policy value, injected so a test can pin it. */
    private val persistenceRetryDelayMillis: Long = DEFAULT_PERSISTENCE_RETRY_DELAY_MILLIS,
    /**
     * The live auth fence, read from the auth tracker — the same read an app sign-out captures its
     * fence with. Not [EntitlementsSource.currentIdentity], which turns a lookup failure other than
     * cancellation into null. Read fresh at each decision that depends on it, after the last
     * suspension before that decision.
     */
    private val liveFence: () -> AuthIdentityFence?
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(OwnedPremiumAccess())
    /**
     * The access decision **and the identity it was decided for**.
     *
     * A bare state cannot say whose it is, and Root reads auth and access from two different flows:
     * during the gap between Firebase reporting a new user and this coordinator being told about
     * them, a bare `PremiumConfirmed` left over from the previous user is what Root would branch on.
     * Publishing the owner alongside lets a reader refuse a grant that is not its own instead of
     * inferring it from timing.
     */
    val state: StateFlow<OwnedPremiumAccess> = _state.asStateFlow()

    private val _krx = MutableStateFlow(KrxCapabilityState.HIDDEN)
    val krx: StateFlow<KrxCapabilityState> = _krx.asStateFlow()

    /** Effects the reducer declared on the most recent applied decision. Diagnostic and tests. */
    private val _lastEffects = MutableStateFlow<List<AccessEffect>>(emptyList())
    val lastEffects: StateFlow<List<AccessEffect>> = _lastEffects.asStateFlow()

    /**
     * Bumped on every identity boundary and on every *authoritative loss input*.
     *
     * Keyed on the input, not on the resulting state: a cached ACTIVE landing on an already-free
     * state changes nothing and must not invalidate a fresh query still in flight. The namespace
     * fence alone is also not enough, because a rejection arriving with nothing granted rotates no
     * epoch and would still match.
     */
    private var decisionGeneration: Long = 0L

    /**
     * Token of the `fresh_premium=true` query in flight, and the owner it runs for.
     *
     * A token, not just a flag: an earlier request finishing must release only its own latch. An
     * unconditional release let a stale completion unlatch the current user's escalation and admit
     * a duplicate query.
     */
    private var forcePremiumToken: Long? = null
    private var forcePremiumOwner: String? = null
    private var nextRequestToken: Long = 0L

    /**
     * Lifetime fence for propagation probes and scheduled retries. **Not** [decisionGeneration].
     *
     * [decisionGeneration] is bumped by every authoritative loss *input*, and a stable
     * `premium_active=false` is exactly what the probe expects to receive while the server's
     * view of a fresh purchase is still catching up. A probe fenced on it would die on its own
     * first answer. This one moves only at an identity boundary.
     */
    private var probeEpoch: Long = 0L

    /** The owner a probe is currently running for, or null. Single-flight, iOS parity. */
    private var probeRunningForOwner: String? = null

    /** The open app sign-out, or null. Guarded by [mutex]; transitions come from [SignOutAttemptPolicy]. */
    private var attempt: SignOutAttempt? = null
    private var nextTicket: Long = 0L

    /**
     * The fence whose binding reached the disk: set once `bindOwner` returns, cleared by an end.
     *
     * Not [state]'s identity — that is published *before* the disk work, so it can name a binding
     * that never landed. Guarded by [mutex].
     */
    private var completedBinding: AuthIdentityFence? = null

    /** The identity event held by a failed edit, until that same event is applied. Guarded by [mutex]. */
    private var heldEvent: HeldIdentityEvent? = null

    /**
     * The head task whose disk work failed with no sign-out attempt to own the outcome.
     *
     * An open attempt records its own unresolved edits and its recovery settles them. Without one,
     * the same failure used to leave the consumer with an exception and nothing to retry. While
     * this is set the record on disk may or may not match what [state] already published, so access
     * queries are refused — but the head task itself may still run, or nothing would ever clear it.
     *
     * Guarded by [mutex]. Ids are never reused.
     */
    private var identityPersistencePending: PendingPersistence? = null
    private var nextPendingId: Long = 0L

    private val _persistenceSignal = MutableStateFlow<PendingPersistence?>(null)

    /** Access is refused while a sign-out attempt is open **or** a persistence hold is unresolved. */
    private fun accessAdmittedLocked(): Boolean =
        SignOutAttemptPolicy.admitsAccessQueries(attempt) && identityPersistencePending == null

    /** Publishes the hold so waiters see the revision they were handed move. */
    private fun setPendingLocked(pending: PendingPersistence?) {
        identityPersistencePending = pending
        _persistenceSignal.value = pending
    }

    /**
     * Where a stalled head task left its work, named so the caller knows what to wait on.
     *
     * Which of the two it is follows from [open] and not from the failure: an attempt that owns the
     * edit records it, and only a task with no attempt opens a hold. Both are read *after* the
     * failure was recorded, so the revision handed out is one a waiter can observe from.
     */
    private fun stalledLocked(open: SignOutAttempt?): IdentityStep =
        if (open == null) {
            val pending = checkNotNull(identityPersistencePending) {
                "an unowned edit failed without opening a hold"
            }
            IdentityStep.AwaitPersistence(
                pending.id, pending.revision, pending.nextAttemptAt, pending.blocked
            )
        } else {
            IdentityStep.AwaitAttempt(open.ticket, attemptSignal.value.revision)
        }

    /** The attempt whose automatic recovery has been claimed. Tickets are never reused. Guarded by [mutex]. */
    private var recoveryClaimed: SignOutTicket? = null

    private val _recoveryStatus = MutableStateFlow<SignOutRecoveryStatus?>(null)

    /**
     * The open attempt's automatic recovery, for the recovery banner. Written under [mutex] together
     * with the attempt, so it never outlives the attempt it describes. See [SignOutRecoveryStatus].
     */
    internal val recoveryStatus: StateFlow<SignOutRecoveryStatus?> = _recoveryStatus.asStateFlow()

    /** The open attempt as the identity FIFO and a recovery run see it. Moves with every attempt change. */
    private val attemptSignal = MutableStateFlow(AttemptSignal(ticket = null, revision = 0L, held = false))

    private val schedule =
        RecheckSchedule(scope, clock) { intent, origin, bindingEpoch ->
            refresh(intent, origin, requireProbeEpoch = bindingEpoch)
        }

    /**
     * Binds the owner to the identity the caller **observed**, and resumes any purge a previous
     * process left journalled.
     *
     * Resuming first matters: the markers a resumed purge clears are inputs to the reducer.
     *
     * Named for the identity rather than the owner because a same-uid generation change is one of
     * these too — the tracked sign-in path advances the generation before Firebase does anything,
     * and that transition never changes the uid.
     *
     * The generation is taken from [identity] and **not re-read**. A re-read can pair the uid from
     * one observation with the generation of another. It can also advance the tracker if it sees
     * a changed uid or session marker; an unchanged observation does not advance it.
     *
     * Does nothing and reports [IdentityStep.AwaitAttempt] while an open sign-out has an edit in
     * flight; the caller keeps the event and tries again. The same answer comes back when its own
     * edit fails under an open attempt. With no attempt, a failed edit reports
     * [IdentityStep.AwaitPersistence] instead — see [identityEditLocked].
     */
    internal suspend fun onIdentityChanged(identity: AuthIdentityFence): IdentityStep = mutex.withLock {
        // Checked under the same lock as the edit below: a gate seen open before this lock was
        // taken can have closed since.
        if (!SignOutAttemptPolicy.admitsIdentityEvents(attempt)) return@withLock stalledLocked(attempt)
        val open = attempt
        heldEvent?.let { pending ->
            check(pending is HeldIdentityEvent.Bind && pending.fence == identity && pending.ticket == open?.ticket) {
                "the held identity event is retried before any other: held $pending, got $identity"
            }
        }
        decisionGeneration += 1
        cancelProbeLocked()
        clearForcePremiumLocked()
        // Same reason as sign-out: the previous owner's grant must stop being readable before the
        // disk work, not after it. KRX goes with it — published after the disk work, a read or
        // write that fails would leave the previous owner's VISIBLE standing.
        _state.value =
            OwnedPremiumAccess(identity.uid, identity.authGeneration, PremiumAccessState.NoGrant)
        _krx.value = KrxCapabilityState.HIDDEN
        schedule.cancel(preserveServerFloor = true)
        val held = { HeldIdentityEvent.Bind(checkNotNull(open).ticket, identity) }
        val work = IdentityWork.Bind(identity)
        val before = identityEditLocked(open, PendingEdit.READ, before = null, held, work) { store.load() }
            ?: return@withLock stalledLocked(open)
        val after = identityEditLocked(open, PendingEdit.BIND_OWNER, before, held, work) { store.bindOwner(identity.uid) }
            ?: return@withLock stalledLocked(open)
        adoptLandingLocked(work)
        // Judged from the record the bind returned, before any purge below can clear its receipt.
        open?.let { current ->
            setAttemptLocked(
                SignOutAttemptPolicy.identityBound(
                    current,
                    retiredAttemptNamespace = SignOutAttemptPolicy.retired(before, after, current.fence.uid)
                )
            )
        }
        // The completion carries the generation, so the caller can bind its follow-up query to
        // *this* binding. A query queued behind a sign-out would otherwise start under the next
        // generation and revive a session that is gone.
        cleanupAndFinishLocked(work, completionFor(work))
    }

    /**
     * Sign-out is a teardown.
     *
     * The plan lists logout with fresh-false and typed reject as an event that rotates the
     * namespace and journals a purge. Clearing only in-memory state would let the next sign-in of
     * the same uid inherit protected data that was never re-authorised.
     */
    internal suspend fun onSignedOut(ended: AuthIdentityFence): IdentityStep = mutex.withLock {
        // Same contract as [onIdentityChanged]: held, untouched, while an edit is in flight.
        if (!SignOutAttemptPolicy.admitsIdentityEvents(attempt)) return@withLock stalledLocked(attempt)
        val open = attempt
        val retry = heldEvent
        if (retry != null) {
            check(retry is HeldIdentityEvent.End && retry.ended == ended && retry.ticket == open?.ticket) {
                "the held identity event is retried before any other: held $retry, got the end of $ended"
            }
        }
        decisionGeneration += 1
        cancelProbeLocked()
        clearForcePremiumLocked()
        // Publish the revocation *before* the store work, not after. Persist-before-observe is the
        // rule for a grant; for taking one away it is backwards — `store.signOut()` is disk I/O, and
        // until it returns a reader still sees the old grant. A same-uid sign-in landing in that
        // window matches on uid and opens the premium surface on a session that no longer exists.
        // KRX is published here for the same reason, not after the store work: a read or write
        // that fails below would otherwise leave the ended session's VISIBLE standing.
        _state.value = OwnedPremiumAccess(null, null, PremiumAccessState.NoGrant)
        _krx.value = KrxCapabilityState.HIDDEN
        schedule.cancel(preserveServerFloor = true)
        // A retried end whose rotation a read-back found landed uses that result. Not because
        // rotating again would destroy anything — slice 5 made `signOut` give up the owner, so
        // `planEnd` answers LEAVE_DISK — but because a blind retry reads as NotAttempted and throws
        // away the knowledge that it landed.
        val endWork = IdentityWork.End(ended)
        val rotation = (retry as? HeldIdentityEvent.End)?.receipt ?: run {
            val held = { HeldIdentityEvent.End(checkNotNull(open).ticket, ended) }
            val before = identityEditLocked(open, PendingEdit.READ, before = null, held, endWork) { store.load() }
                ?: return@withLock stalledLocked(open)
            // Targeted: `store.signOut()` rotates whoever the record names, so it only runs when that
            // is the uid whose session ended.
            when (SignOutAttemptPolicy.planEnd(ended, before.ownerUid)) {
                SignOutAttemptPolicy.EndPlan.ROTATE -> EditResult.Landed(
                    identityEditLocked(open, PendingEdit.END, before, held, endWork) { store.signOut() }
                        ?: return@withLock stalledLocked(open)
                )
                SignOutAttemptPolicy.EndPlan.LEAVE_DISK -> EditResult.NotAttempted
            }
        }
        // What this end did is recorded before cleanup runs, because cleanup can fail. Only
        // finishing the attempt — which releases the seal — waits for it.
        val finishing = open?.let { current ->
            val next = SignOutAttemptPolicy.afterEnd(current, ended, rotation, liveFence())
            // Null only when the rotation landed, so the provisional state says exactly that.
            setAttemptLocked(next ?: SignOutAttempt.Recovering(current.ticket, current.fence, TeardownKnowledge.LANDED))
            next == null
        } ?: false
        adoptLandingLocked(endWork)
        cleanupAndFinishLocked(endWork, completionFor(endWork), finishing)
    }

    /**
     * Starts an app sign-out for [fence]: seals access, then persists the intent.
     *
     * The order is fixed — live check, seal, disk. A request already stale at entry does not seal
     * or write. Sealing publishes NoGrant and HIDDEN and closes this coordinator's protected
     * access entry points before disk work.
     */
    internal suspend fun prepareSignOut(fence: AuthIdentityFence): SignOutStart = mutex.withLock {
        // Ordering, not exclusion: the app's request goes through the identity FIFO, and a head task
        // with an unresolved hold has not returned, so this body is unreachable while one stands.
        // Reaching it with a hold means somebody bypassed the queue. Unowned recovery assumes no
        // attempt is open — it edits through the unowned path, which records holds rather than the
        // attempt's unresolved edit — so reject that overlap before creating an attempt.
        check(identityPersistencePending == null) {
            "a sign-out prepared while an identity hold is unresolved: $identityPersistencePending"
        }
        when (val request = SignOutAttemptPolicy.request(attempt, fence)) {
            is SignOutAttemptPolicy.Request.Joined -> return@withLock SignOutStart.Joined(request.ticket)
            is SignOutAttemptPolicy.Request.Busy -> return@withLock SignOutStart.Busy(request.ticket)
            SignOutAttemptPolicy.Request.Start -> Unit
        }
        val live = liveFence()
        if (live != fence) return@withLock SignOutStart.Stale
        val ticket = SignOutTicket(++nextTicket)
        // Keep the seal represented even if the first read is cancelled.
        setAttemptLocked(
            SignOutAttempt.Unresolved(
                ticket, fence, PendingEdit.READ, TeardownKnowledge.NOT_OWED, before = null
            )
        )
        sealLocked(fence)
        val read = try {
            Result.success(store.load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Result.failure(failed)
        }
        val prologue = SignOutAttemptPolicy.prologue(
            ticket, fence, liveFence(), completedBinding, read.map { it.ownerUid }
        )
        when (prologue) {
            SignOutAttemptPolicy.Prologue.Stale -> {
                // Already sealed, but no intent write was attempted.
                // A failed read keeps the unresolved READ state.
                if (read.isSuccess) {
                    setAttemptLocked(
                        SignOutAttempt.Recovering(ticket, fence, TeardownKnowledge.NOT_OWED)
                    )
                }
                SignOutStart.RecoveryRequired(ticket)
            }
            is SignOutAttemptPolicy.Prologue.Unreadable -> {
                setAttemptLocked(prologue.attempt)
                SignOutStart.RecoveryRequired(ticket)
            }
            is SignOutAttemptPolicy.Prologue.RecoveryRequired -> {
                setAttemptLocked(prologue.attempt)
                SignOutStart.RecoveryRequired(ticket)
            }
            is SignOutAttemptPolicy.Prologue.Persist -> {
                setAttemptLocked(prologue.attempt)
                // Not abandoned half way: a Preparing attempt holds every identity event, and a
                // cancellation here would leave it holding them with nothing to settle it.
                withContext(NonCancellable) {
                    val write = persistIntentLocked(fence.uid, read.getOrThrow())
                    val next = SignOutAttemptPolicy.intentWritten(prologue.attempt, write, liveFence())
                    setAttemptLocked(next)
                    if (next is SignOutAttempt.Armed) SignOutStart.Armed(ticket)
                    else SignOutStart.RecoveryRequired(ticket)
                }
            }
        }
    }

    /**
     * The intent write, classified by what reached the disk.
     *
     * An exception alone does not say whether the edit became durable, so the record is read back.
     * A read-back that shows the intent confirms that edit; one that does not is not treated as the
     * edit landing.
     */
    private suspend fun persistIntentLocked(uid: String, before: AccessEpochRecord): EditResult = try {
        EditResult.Landed(store.beginSignOut(uid))
    } catch (failed: Exception) {
        // Called inside NonCancellable. A CancellationException thrown by the store still
        // needs reconciliation; it does not establish whether the edit reached disk.
        try {
            val readBack = store.load()
            if (SignOutAttemptPolicy.holdsIntent(readBack, uid)) EditResult.Landed(readBack)
            else EditResult.NotAttempted
        } catch (unreadable: Exception) {
            EditResult.Unknown(before)
        }
    }

    /** Takes protected access away from [fence] ahead of disk work; see [onSignedOut] for why first. */
    private suspend fun sealLocked(fence: AuthIdentityFence) {
        decisionGeneration += 1
        cancelProbeLocked()
        clearForcePremiumLocked()
        _state.value = OwnedPremiumAccess(fence.uid, fence.authGeneration, PremiumAccessState.NoGrant)
        _krx.value = KrxCapabilityState.HIDDEN
        schedule.cancel(preserveServerFloor = true)
    }

    /**
     * One disk step of an identity event, a recovery barrier, or recovery itself.
     *
     * With no attempt open a failure propagates as it always has. With one open, the step's outcome
     * is recorded as unresolved, along with the FIFO item [held] names when there is one — null tells
     * the caller to stop there. No read-back here: a known "not attempted" would be retried at once by
     * the FIFO, so the retry waits for [resolvePendingEdit] instead. Nothing after the step runs, so
     * no purge can erase its receipt.
     */
    private suspend fun <T : Any> identityEditLocked(
        open: SignOutAttempt?,
        edit: PendingEdit,
        before: AccessEpochRecord?,
        held: () -> HeldIdentityEvent?,
        /** What is being persisted, for the hold a failure opens when no attempt owns it. */
        work: IdentityWork,
        step: suspend () -> T
    ): T? =
        if (open == null) unownedEditLocked(work, edit, before, step)
        else ownedEditLocked(open, edit, before, held, step)

    /**
     * One disk step of an event an open attempt owns.
     *
     * A failure becomes that attempt's unresolved edit, and its recovery settles it. Nothing is
     * thrown at the consumer: the FIFO item is held and retried in place.
     */
    private suspend fun <T : Any> ownedEditLocked(
        open: SignOutAttempt,
        edit: PendingEdit,
        before: AccessEpochRecord?,
        held: () -> HeldIdentityEvent?,
        step: suspend () -> T
    ): T? = try {
        step()
    } catch (failed: Exception) {
        setAttemptLocked(SignOutAttemptPolicy.editFailed(open, edit, before))
        heldEvent = held()
        // A store can throw CancellationException of its own. Only this caller's cancellation ends here.
        currentCoroutineContext().ensureActive()
        null
    }

    /**
     * One disk step with no attempt to own the outcome.
     *
     * This used to throw, and the consumer that called it has no handler — a disk fault took the
     * process. The failure now opens a persistence hold instead: the disk may or may not have taken
     * the edit, and only this head task can find out.
     */
    private suspend fun <T : Any> unownedEditLocked(
        work: IdentityWork,
        edit: PendingEdit,
        before: AccessEpochRecord?,
        step: suspend () -> T
    ): T? = try {
        step()
    } catch (failed: Exception) {
        holdPersistenceLocked(work, edit, before)
        currentCoroutineContext().ensureActive()
        null
    }

    private fun holdPersistenceLocked(work: IdentityWork, edit: PendingEdit, before: AccessEpochRecord?) {
        openOrExtendHoldLocked(work, PendingPersistence.Phase.Unknown(edit, before))
    }

    /**
     * Opens the hold for [work] at [phase], or moves an existing one to it, and schedules a round.
     *
     * A failure inside an existing hold keeps that hold's id and budget: minting a new one would
     * hand every failure a fresh three rounds. Different work replaces the hold — it is a different
     * head task, and the old one is not this task's to finish.
     */
    private fun openOrExtendHoldLocked(work: IdentityWork, phase: PendingPersistence.Phase) {
        val existing = identityPersistencePending
        setPendingLocked(
            if (existing != null && existing.work == work) {
                PersistenceRecoveryPolicy.afterFailedRound(
                    existing.copy(phase = phase),
                    now = clock.elapsedMillis(),
                    delayMillis = persistenceRetryDelayMillis,
                    undecidable = false
                )
            } else {
                PendingPersistence(
                    id = nextPendingId++,
                    work = work,
                    phase = phase,
                    revision = 0L,
                    // The failure that opens a hold does not spend a round; the first retry does.
                    spent = 0,
                    nextAttemptAt = clock.elapsedMillis() + persistenceRetryDelayMillis
                )
            }
        )
    }

    /** What the purge resume at the end of a head task did. */
    private enum class CleanupOutcome {
        /**
         * The resume was accepted. Completed entries were dropped; deferred ones stay journalled,
         * which is what the current wiring always answers — so this does not mean the journal is
         * empty.
         */
        DONE,

        /** Cleanup returned false: a purger reported [PurgeResult.Failed], or an open attempt caught a throw. */
        FAILED,

        /** The resume threw with nobody to own it, so the hold parked at [PendingPersistence.Phase.CleanupPending]. */
        HELD
    }

    /**
     * Resumes purges at the end of a head task, holding rather than throwing when nobody owns it.
     *
     * A throw and a reported [PurgeResult.Failed] are not the same fact, and only the throw holds.
     * The throw can come from the load, a purger, or the completion write — all three are inside
     * this step — and it says nothing about what ran, so an automatic round is worth having and,
     * without the hold, the exception would end the consumer. A normal false return means a purger
     * answered [PurgeResult.Failed]; those entries stay journalled and are not dropped, and this
     * slice keeps the existing behaviour of completing the identity task on it rather than locking
     * a signed-in session out over an old namespace. Neither result says the store is healthy.
     *
     * The write is already on disk by the time this runs, which is why the hold parks at
     * [PendingPersistence.Phase.CleanupPending] and not at a phase that would run the write again.
     */
    private suspend fun cleanupOrHoldLocked(
        work: IdentityWork,
        completion: IdentityCompletion
    ): CleanupOutcome {
        if (attempt != null) return if (cleanupLocked()) CleanupOutcome.DONE else CleanupOutcome.FAILED
        return try {
            if (resumePendingPurgesLocked()) CleanupOutcome.DONE else CleanupOutcome.FAILED
        } catch (failed: Exception) {
            openOrExtendHoldLocked(work, PendingPersistence.Phase.CleanupPending(completion))
            currentCoroutineContext().ensureActive()
            CleanupOutcome.HELD
        }
    }

    /**
     * Adopts what a landed write did, in memory. Runs once, when the landing is established.
     *
     * Kept apart from the purge resume that follows it because only that resume can fail after the
     * write is on disk: a hold at [PendingPersistence.Phase.CleanupPending] carries the completion
     * this produced, so a later round finishes the task without repeating any of this.
     */
    private fun adoptLandingLocked(work: IdentityWork) {
        when (work) {
            is IdentityWork.Bind -> {
                completedBinding = work.fence
                _krx.value = KrxCapabilityState.HIDDEN
                _lastEffects.value = emptyList()
            }
            is IdentityWork.End -> completedBinding = null
            IdentityWork.StartupPurge -> Unit
        }
        heldEvent = null
    }

    /**
     * The purge resume that ends a head task, and what it leaves behind.
     *
     * [finishing] is the attempt's business and is false on every recovery round, which runs with
     * no attempt open. Finishing an open attempt here needs an accepted resume and nobody live: a
     * reported failure means a namespace this teardown owed was not cleared, and releasing the seal
     * over it would let the next session read what that entry was meant to erase. With no attempt,
     * a reported failure completes the task and leaves those entries journalled, while a throw
     * keeps the hold. Neither undoes the landing.
     */
    private suspend fun cleanupAndFinishLocked(
        work: IdentityWork,
        completion: IdentityCompletion,
        finishing: Boolean = false
    ): IdentityStep {
        val cleanup = cleanupOrHoldLocked(work, completion)
        if (cleanup == CleanupOutcome.HELD) return stalledLocked(attempt)
        // Read again after the last suspension: somebody may have signed in during cleanup.
        if (finishing && cleanup == CleanupOutcome.DONE && liveFence() == null) setAttemptLocked(null)
        setPendingLocked(null)
        return IdentityStep.Applied(completion)
    }

    /**
     * Suspends until the hold [step] names may be worth resuming: its state moved, or its timer
     * came due. A hint only — [resumePersistence] judges again under the lock.
     *
     * Both wake-ups are needed. The timer alone would sleep through a manual wake, and the signal
     * alone would sleep through the automatic round nothing else announces.
     */
    internal suspend fun awaitPersistenceRetry(step: IdentityStep.AwaitPersistence) {
        val moved = suspend {
            _persistenceSignal.first {
                it == null || it.id != step.id || it.revision > step.afterRevision ||
                    // Setting the wake did raise a revision, but this step already carries the
                    // later one the stopped batch returned. No newer revision is coming to admit
                    // that retained wake, and asking again would only coalesce into it.
                    (it.nextAttemptAt == null && it.wakeRequested)
            }
            Unit
        }
        val due = step.nextAttemptAt ?: return moved()
        val remaining = due - clock.elapsedMillis()
        if (remaining <= 0) return
        withTimeoutOrNull(remaining) { moved() }
    }

    /**
     * Spends one recovery round on the hold [id] names, and says where that leaves it.
     *
     * Admission, the disk work and the result all happen under this one lock hold. That is the
     * other half of the single-execution guarantee the budget gives: a second resume arriving for
     * the same deadline can only ever see the state this round left behind.
     */
    internal suspend fun resumePersistence(id: Long): IdentityStep = mutex.withLock {
        val pending = identityPersistencePending ?: return@withLock IdentityStep.StaleResume(id)
        when (val round = PersistenceRecoveryPolicy.admitRound(pending, id, clock.elapsedMillis())) {
            PersistenceRecoveryPolicy.Round.Stale -> IdentityStep.StaleResume(id)
            PersistenceRecoveryPolicy.Round.TooEarly,
            PersistenceRecoveryPolicy.Round.Blocked -> stalledLocked(open = null)
            is PersistenceRecoveryPolicy.Round.Run -> runRoundLocked(round.pending)
        }
    }

    /**
     * Asks for one more round on the hold [id] names, whatever its budget says.
     *
     * A wake only: it does not run the round. What it does is give a stopped batch something to
     * spend, so the next resume finds work admitted. A wake that arrives while a batch is still
     * scheduled is kept rather than spent, so it cannot skip that batch's delay.
     */
    internal suspend fun retryPersistence(id: Long): Boolean = mutex.withLock {
        val pending = identityPersistencePending ?: return@withLock false
        if (pending.id != id || pending.wakeRequested) return@withLock false
        setPendingLocked(pending.copy(wakeRequested = true, revision = pending.revision + 1))
        true
    }

    /**
     * One admitted round: establish what the failed edit did, then act on that.
     *
     * A read-back and the work it authorises belong to the same round — the read-back is what makes
     * the work safe to run, so charging it separately would spend the budget on knowing and leave
     * none for doing.
     */
    private suspend fun runRoundLocked(admitted: PendingPersistence): IdentityStep {
        val known = when (val phase = admitted.phase) {
            is PendingPersistence.Phase.Unknown -> when (val resolved = resolveHeldEditLocked(admitted, phase)) {
                is HeldEditResolution.Established -> resolved.pending
                HeldEditResolution.Unreadable -> return failRoundLocked(admitted, undecidable = false)
                HeldEditResolution.Undecidable -> return failRoundLocked(admitted, undecidable = true)
            }
            else -> admitted
        }
        // Published before the work runs, so this round's spent budget and established phase are
        // the state a failure falls back to. A new edit that fails replaces ReadyToRetry with
        // Unknown — that edit's outcome is its own question — while keeping the id and the rounds
        // already spent. After a landing, a thrown cleanup step keeps CleanupPending and the
        // completion it carries; a reported failure finishes the task and leaves its entries
        // journalled.
        setPendingLocked(known)
        return when (val phase = known.phase) {
            is PendingPersistence.Phase.ReadyToRetry -> rerunLocked(known, phase.before)
            is PendingPersistence.Phase.CleanupPending ->
                cleanupAndFinishLocked(known.work, phase.completion)
            is PendingPersistence.Phase.Unknown -> error("a round left the outcome unknown: $known")
        }
    }

    /** What a round learned about the edit it inherited. The two failures are not the same fact. */
    private sealed interface HeldEditResolution {
        data class Established(val pending: PendingPersistence) : HeldEditResolution

        /** The record could not be read, so the edit is exactly as unknown as before. */
        data object Unreadable : HeldEditResolution

        /** The record matched neither the edit's result nor what it started from. */
        data object Undecidable : HeldEditResolution
    }

    /**
     * Establishes what a held edit did.
     *
     * A failed read is not read back at all: the record cannot show a write that never started, so
     * the question has no answer to find, and asking it would spend the round on a read that can
     * fail for the same reason the first one did. Every other edit is judged by the policy from the
     * record as it now stands.
     *
     * A landing is adopted here, where it is established, and not again by the round that finishes
     * the cleanup behind it.
     */
    private suspend fun resolveHeldEditLocked(
        admitted: PendingPersistence,
        phase: PendingPersistence.Phase.Unknown
    ): HeldEditResolution {
        if (!SignOutAttemptPolicy.needsReadBack(phase.edit)) {
            return HeldEditResolution.Established(
                admitted.copy(phase = PendingPersistence.Phase.ReadyToRetry(phase.before))
            )
        }
        val readBack = try {
            store.load()
        } catch (failed: Exception) {
            currentCoroutineContext().ensureActive()
            return HeldEditResolution.Unreadable
        }
        return when (
            val outcome =
                SignOutAttemptPolicy.resolveUnownedEdit(admitted.work, phase.edit, phase.before, readBack)
        ) {
            is SignOutAttemptPolicy.UnownedResolution.RunAgain -> HeldEditResolution.Established(
                admitted.copy(phase = PendingPersistence.Phase.ReadyToRetry(outcome.before))
            )
            SignOutAttemptPolicy.UnownedResolution.Landed -> {
                adoptLandingLocked(admitted.work)
                HeldEditResolution.Established(
                    admitted.copy(
                        phase = PendingPersistence.Phase.CleanupPending(completionFor(admitted.work))
                    )
                )
            }
            SignOutAttemptPolicy.UnownedResolution.Undecidable -> HeldEditResolution.Undecidable
        }
    }

    /** The completion a landed write earns, by the work that landed. */
    private fun completionFor(work: IdentityWork): IdentityCompletion = when (work) {
        is IdentityWork.Bind ->
            IdentityCompletion(work.fence, AccessDecisionGeneration(decisionGeneration))
        is IdentityWork.End, IdentityWork.StartupPurge ->
            IdentityCompletion(completed = null, queryGeneration = null)
    }

    /** Records a failed round and reports where the hold now stands. */
    private fun failRoundLocked(admitted: PendingPersistence, undecidable: Boolean): IdentityStep {
        setPendingLocked(
            PersistenceRecoveryPolicy.afterFailedRound(
                admitted,
                now = clock.elapsedMillis(),
                delayMillis = persistenceRetryDelayMillis,
                undecidable = undecidable
            )
        )
        return stalledLocked(open = null)
    }

    /**
     * Runs the work again, from where the read-back says it may safely start.
     *
     * A null [before] means the read that precedes the write never produced one, so it runs first.
     * The published state is not touched: the head task published it before its first edit and it
     * has been correct ever since — the record on disk is what is behind.
     */
    private suspend fun rerunLocked(admitted: PendingPersistence, before: AccessEpochRecord?): IdentityStep =
        when (val work = admitted.work) {
            is IdentityWork.Bind -> {
                val read = before ?: unownedEditLocked(work, PendingEdit.READ, before = null) { store.load() }
                if (read == null) stalledLocked(open = null)
                else {
                    val bound = unownedEditLocked(work, PendingEdit.BIND_OWNER, read) {
                        store.bindOwner(work.fence.uid)
                    }
                    if (bound == null) stalledLocked(open = null)
                    else landAndFinishLocked(work)
                }
            }
            is IdentityWork.End -> {
                val read = before ?: unownedEditLocked(work, PendingEdit.READ, before = null) { store.load() }
                if (read == null) stalledLocked(open = null)
                else when (SignOutAttemptPolicy.planEnd(work.ended, read.ownerUid)) {
                    SignOutAttemptPolicy.EndPlan.ROTATE -> {
                        val rotated = unownedEditLocked(work, PendingEdit.END, read) { store.signOut() }
                        if (rotated == null) stalledLocked(open = null)
                        else landAndFinishLocked(work)
                    }
                    // Nothing to rotate: the record does not name the uid whose session ended.
                    SignOutAttemptPolicy.EndPlan.LEAVE_DISK -> landAndFinishLocked(work)
                }
            }
            // Nothing about a startup purge lands in the record, so its whole work *is* the cleanup.
            IdentityWork.StartupPurge -> cleanupAndFinishLocked(work, completionFor(work))
        }

    /** A write this round put on disk: adopt it, then run the cleanup behind it. */
    private suspend fun landAndFinishLocked(work: IdentityWork): IdentityStep {
        adoptLandingLocked(work)
        return cleanupAndFinishLocked(work, completionFor(work))
    }

    /**
     * Resumes purges after an edit that already landed.
     *
     * Requires an open attempt: [cleanupOrHoldLocked] owns the case where nobody does. A failure —
     * reported or thrown — is that attempt's cleanup failure, so it keeps the seal and never becomes
     * the edit's failure. The caller's own cancellation still propagates.
     */
    private suspend fun cleanupLocked(): Boolean {
        check(attempt != null) { "cleanupLocked requires an open sign-out attempt" }
        return try {
            resumePendingPurgesLocked()
        } catch (failed: Exception) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /**
     * Reads back the edit an open attempt is waiting on, from outside the identity FIFO held behind it.
     *
     * Resolving reopens identity events, and the event that was held is retried before any other. A
     * landed end leaves its result for that retry. Without [allowReadBack] the edit is reported as
     * still unknown and the record is not read — whether one is waiting is judged here either way.
     */
    internal suspend fun resolvePendingEdit(ticket: SignOutTicket, allowReadBack: Boolean = true): EditResolution =
        mutex.withLock {
            val open = attempt as? SignOutAttempt.Unresolved
            if (open == null || open.ticket != ticket) return@withLock EditResolution.NOT_PENDING
            if (!allowReadBack) return@withLock EditResolution.STILL_UNKNOWN
            resolvePendingEditLocked(open)
        }

    private suspend fun resolvePendingEditLocked(open: SignOutAttempt.Unresolved): EditResolution {
        val readBack = try {
            store.load()
        } catch (failed: Exception) {
            currentCoroutineContext().ensureActive()
            return EditResolution.STILL_UNKNOWN
        }
        val resolution = SignOutAttemptPolicy.resolve(open, readBack) as? SignOutAttemptPolicy.Resolution.Resolved
            ?: return EditResolution.INCONSISTENT
        var held = heldEvent
        // A barrier's bind that ran is classified by the candidate's binding, apart from what it did to
        // the attempt's namespace; the result is kept so the resumed barrier need not read again.
        if (held is HeldIdentityEvent.Barrier && held.bind is BarrierBind.Unknown) {
            val bind = SignOutAttemptPolicy.barrierBindResolved(held.bind, held.candidate, readBack)
                ?: return EditResolution.INCONSISTENT
            held = held.copy(bind = bind)
        }
        resolution.endReceipt?.let { receipt ->
            check(held is HeldIdentityEvent.End && held.ticket == open.ticket) {
                "a landed end without the end event it belongs to: $held"
            }
            held = held.copy(receipt = receipt)
        }
        heldEvent = held
        setAttemptLocked(resolution.next)
        return EditResolution.RESOLVED
    }

    /**
     * Moves recovery on by at most one logical step — one read-back, or one settling attempt — and
     * reports where it stands. A failed edit is not retried inside a call. Without [allowReadBack] an
     * unresolved edit is reported as it is, unread: a run spends its read-backs as it chooses.
     *
     * Meant for outside the identity FIFO. An unresolved edit may be read back while its event is
     * held. Once resolved, that event must finish on the FIFO before recovery judges whether
     * another settling rotation is needed.
     */
    internal suspend fun advanceRecovery(ticket: SignOutTicket, allowReadBack: Boolean = true): RecoveryAdvance =
        mutex.withLock {
            val open = attempt
            if (open == null || open.ticket != ticket) return@withLock RecoveryAdvance.CLOSED
            when (open) {
                is SignOutAttempt.Armed -> RecoveryAdvance.DRIVER_OWNS
                is SignOutAttempt.Preparing -> error("a preparing attempt is only visible inside its own lock")
                is SignOutAttempt.Unresolved -> when {
                    !allowReadBack -> RecoveryAdvance.UNRESOLVED
                    else -> when (resolvePendingEditLocked(open)) {
                        EditResolution.RESOLVED -> RecoveryAdvance.RESOLVED
                        EditResolution.STILL_UNKNOWN -> RecoveryAdvance.UNRESOLVED
                        EditResolution.INCONSISTENT -> RecoveryAdvance.INCONSISTENT
                        EditResolution.NOT_PENDING -> error("resolving the open attempt's own edit")
                    }
                }
                is SignOutAttempt.Recovering -> settleLocked(open)
            }
        }

    private suspend fun settleLocked(open: SignOutAttempt.Recovering): RecoveryAdvance {
        if (heldEvent != null) return RecoveryAdvance.NEEDS_BARRIER
        val disk = ownedEditLocked(open, PendingEdit.READ, before = null, held = { null }) { store.load() }
            ?: return RecoveryAdvance.UNRESOLVED
        return when (SignOutAttemptPolicy.recover(open, disk)) {
            // Targeted: recover() asks for this only while the disk names the attempt's uid as owner.
            SignOutAttemptPolicy.RecoveryStep.SETTLE -> {
                val rotation = try {
                    EditResult.Landed(store.signOut())
                } catch (failed: Exception) {
                    EditResult.Unknown(disk)
                }
                setAttemptLocked(SignOutAttemptPolicy.settled(open, rotation))
                currentCoroutineContext().ensureActive()
                // Cleanup is left to the barrier, which cannot release the seal before it is accepted.
                if (rotation is EditResult.Landed) RecoveryAdvance.PROGRESSED else RecoveryAdvance.UNRESOLVED
            }
            SignOutAttemptPolicy.RecoveryStep.INCONSISTENT -> RecoveryAdvance.INCONSISTENT
            SignOutAttemptPolicy.RecoveryStep.AWAIT_BARRIER -> RecoveryAdvance.NEEDS_BARRIER
        }
    }

    /**
     * Recovery's last step, for the identity FIFO's consumer to run at a barrier. [candidate] is to be
     * captured before that barrier is enqueued.
     *
     * Releases the seal only once cleanup is accepted, the candidate — or nobody — is still live
     * after the last suspension, and no identity event is held. It does not call signOut or replay
     * an end. Binding can still rotate namespaces through bindOwner; cleanup is checked afterward.
     */
    internal suspend fun completeRecovery(ticket: SignOutTicket, candidate: AuthIdentityFence?): BarrierOutcome =
        mutex.withLock {
            val open = attempt
            if (open == null || open.ticket != ticket) return@withLock barrierOutcome(BarrierStep.CLOSED)
            if (open is SignOutAttempt.Armed) return@withLock barrierOutcome(BarrierStep.DRIVER_OWNS)
            if (!SignOutAttemptPolicy.admitsIdentityEvents(open)) return@withLock barrierOutcome(BarrierStep.HELD)
            val recovering = open as SignOutAttempt.Recovering
            val resumed = heldEvent
            if (resumed != null) {
                // Checked before any side effect: only this barrier, resumed, may run while one is held.
                check(resumed is HeldIdentityEvent.Barrier && resumed.ticket == ticket && resumed.candidate == candidate) {
                    "a barrier ran ahead of the held identity event: held $resumed, barrier for $candidate"
                }
                when (resumed.bind) {
                    BarrierBind.Landed -> completedBinding = resumed.candidate
                    BarrierBind.NotStarted, BarrierBind.NotLanded -> Unit
                    is BarrierBind.Unknown -> error("an unknown barrier bind is classified before admission reopens")
                }
                heldEvent = null
            }
            when (val step = SignOutAttemptPolicy.barrier(recovering, candidate, liveFence(), completedBinding)) {
                SignOutAttemptPolicy.Barrier.NotReady -> barrierOutcome(BarrierStep.NOT_READY)
                SignOutAttemptPolicy.Barrier.Recapture -> barrierOutcome(BarrierStep.RECAPTURE)
                SignOutAttemptPolicy.Barrier.Hold -> barrierOutcome(BarrierStep.HOLD)
                is SignOutAttemptPolicy.Barrier.Bind -> bindBarrierLocked(recovering, checkNotNull(candidate))
                SignOutAttemptPolicy.Barrier.FinishSignedOut -> finishSignedOutLocked()
            }
        }

    private suspend fun bindBarrierLocked(open: SignOutAttempt.Recovering, candidate: AuthIdentityFence): BarrierOutcome {
        decisionGeneration += 1
        cancelProbeLocked()
        clearForcePremiumLocked()
        // Sealed onto the candidate before the disk work, as any binding is.
        _state.value = OwnedPremiumAccess(candidate.uid, candidate.authGeneration, PremiumAccessState.NoGrant)
        schedule.cancel(preserveServerFloor = true)
        val before = ownedEditLocked(open, PendingEdit.READ, before = null, held = {
            HeldIdentityEvent.Barrier(open.ticket, candidate, BarrierBind.NotStarted)
        }) { store.load() } ?: return barrierOutcome(BarrierStep.HELD)
        val after = ownedEditLocked(open, PendingEdit.BIND_OWNER, before, held = {
            HeldIdentityEvent.Barrier(open.ticket, candidate, BarrierBind.Unknown(before))
        }) { store.bindOwner(candidate.uid) } ?: return barrierOutcome(BarrierStep.HELD)
        completedBinding = candidate
        _krx.value = KrxCapabilityState.HIDDEN
        if (!cleanupLocked()) return barrierOutcome(BarrierStep.CLEANUP_FAILED)
        // Read after the last suspension.
        if (!SignOutAttemptPolicy.barrierBound(candidate, EditResult.Landed(after), liveFence())) {
            return barrierOutcome(BarrierStep.RECAPTURE)
        }
        setAttemptLocked(null)
        return barrierOutcome(BarrierStep.RELEASED, AccessDecisionGeneration(decisionGeneration))
    }

    private suspend fun finishSignedOutLocked(): BarrierOutcome {
        if (!cleanupLocked()) return barrierOutcome(BarrierStep.CLEANUP_FAILED)
        if (liveFence() != null) return barrierOutcome(BarrierStep.RECAPTURE)
        setAttemptLocked(null)
        return barrierOutcome(BarrierStep.RELEASED)
    }

    private fun barrierOutcome(step: BarrierStep, releasedGeneration: AccessDecisionGeneration? = null) =
        BarrierOutcome(step, completedBinding, releasedGeneration)

    /** Suspends while the open sign-out has an edit in flight. The identity FIFO waits here. */
    suspend fun awaitIdentityEventsAdmitted() {
        attemptSignal.first { !it.held }
    }

    /**
     * Suspends until the attempt [ticket] names changes after [afterRevision] into holding identity
     * events, or stops being the open attempt. A hint only: the caller decides under the lock again.
     */
    internal suspend fun awaitAttemptHeldOrGone(ticket: SignOutTicket, afterRevision: Long): AttemptSignal =
        attemptSignal.first { it.revision > afterRevision && (it.ticket != ticket || it.held) }

    /** The live fence, for a recovery barrier's candidate — captured before the barrier is enqueued. */
    internal fun liveIdentity(): AuthIdentityFence? = liveFence()

    private fun setAttemptLocked(next: SignOutAttempt?) {
        attempt = next
        // The status is the open attempt's and goes with it.
        if (_recoveryStatus.value?.ticket != next?.ticket) _recoveryStatus.value = null
        attemptSignal.value = AttemptSignal(
            ticket = next?.ticket,
            revision = attemptSignal.value.revision + 1,
            held = !SignOutAttemptPolicy.admitsIdentityEvents(next),
            recovering = next is SignOutAttempt.Recovering || next is SignOutAttempt.Unresolved
        )
    }

    /** Every change of the open attempt, for the binder's recovery supervisor. A hint: decisions are taken under the lock. */
    internal val attemptSignals: StateFlow<AttemptSignal> get() = attemptSignal

    /**
     * Takes the one automatic recovery run [ticket] gets, if it is the open attempt and recovery owns
     * it now. Judged under the lock, so a state seen only briefly — the unresolved read a preparation
     * starts with, before it arms — does not use the claim up.
     */
    internal suspend fun claimRecovery(ticket: SignOutTicket): Boolean = mutex.withLock {
        val open = attempt
        val recoverable = open is SignOutAttempt.Recovering || open is SignOutAttempt.Unresolved
        if (open?.ticket != ticket || !recoverable || recoveryClaimed == ticket) return@withLock false
        recoveryClaimed = ticket
        true
    }

    /** Records [change] to [ticket]'s recovery status, only if it is still the open attempt — checked and written under the lock. */
    internal suspend fun publishRecovery(ticket: SignOutTicket, change: (SignOutRecoveryStatus) -> SignOutRecoveryStatus) {
        mutex.withLock {
            if (attempt?.ticket != ticket) return@withLock
            _recoveryStatus.value =
                change(_recoveryStatus.value?.takeIf { it.ticket == ticket } ?: SignOutRecoveryStatus(ticket, running = false))
        }
    }

    /**
     * Called by the stopped driver. Transfers only the matching Armed attempt to Recovering(OWED),
     * without disk work. The recovery executor orders settlement after its FIFO barrier.
     */
    internal suspend fun stopDriver(ticket: SignOutTicket): Boolean = mutex.withLock {
        val open = attempt as? SignOutAttempt.Armed ?: return@withLock false
        if (open.ticket != ticket) return@withLock false
        setAttemptLocked(SignOutAttemptPolicy.driverStopped(open))
        true
    }

    /**
     * A local purchase or restore reported success.
     *
     * RevenueCat is a signal, never the authority: this starts a bounded re-query window and
     * nothing else. `ANDROID_V2_PLAN.md` D23 `FreeConfirmed` row — "로컬 구매·복원 성공은 fresh
     * 조회와 bounded propagation probe만 시작".
     *
     * Non-suspend so a billing callback on any thread can call it without a scope of its own.
     */
    fun onLocalPremiumSignal() {
        scope.launch { runPropagationProbe() }
    }

    /**
     * Re-asks for a fresh decision across the window the server's own caches take to catch up.
     *
     * Every tick goes through [refresh] with the default [QueryOrigin.CALLER], so
     * [RecheckSchedule] keeps sole ownership of the rate limit: a tick landing inside a server
     * `Retry-After` is refused and folded into the queued retry. Passing
     * [QueryOrigin.SCHEDULED] here would walk straight past that floor.
     *
     * It does **not** stop on `FreeConfirmed` — that answer *is* the propagation window.
     */
    private suspend fun runPropagationProbe() {
        // A **live credential**, not persisted ownership. A record still names an owner while a
        // sign-out is only decided, and an external sign-out takes the credential away before any
        // of that. Gating on the record alone let a billing callback delivered after logout start
        // querying: with no credential the transport throws, that classifies TRANSIENT, and the
        // reducer arms a recheck — teardown undone by a retry ladder that outlives the probe window.
        val run = mutex.withLock {
            // Establish admission, live identity and persisted ownership while holding this lock.
            // A landed sign-out without a subsequent bind is also refused by the null-owner check.
            if (!accessAdmittedLocked()) return
            val live = source.currentIdentity() ?: return
            val owner = store.load().ownerUid ?: return
            if (owner != live.ownerUid) return
            // A later signal joins the running budget rather than restarting it, so repeated
            // billing callbacks cannot extend the window indefinitely.
            if (probeRunningForOwner == owner) return
            probeRunningForOwner = owner
            ProbeRun(owner, probeEpoch)
        }
        try {
            PROBE_DELAY_MILLIS.forEachIndexed { index, nominal ->
                delay(jitter.delayMillisFor(index, nominal))
                if (mutex.withLock { probeEpoch != run.epoch }) return
                when (_state.value.state) {
                    // Goal reached, or the server said no in a way a local true cannot reopen.
                    PremiumAccessState.PremiumConfirmed, PremiumAccessState.Rejected -> return
                    // The epoch travels with the call: the check above only narrows the window,
                    // it does not close it.
                    else -> refresh(RefreshIntent.FORCE_PREMIUM, requireProbeEpoch = run.epoch)
                }
            }
        } finally {
            // Same rule as the escalation latch: release only what is still mine, and survive
            // cancellation long enough to do it.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (probeEpoch == run.epoch && probeRunningForOwner == run.owner) {
                        probeRunningForOwner = null
                    }
                }
            }
        }
    }

    private fun cancelProbeLocked() {
        probeEpoch += 1
        probeRunningForOwner = null
    }

    private data class ProbeRun(val owner: String, val epoch: Long)

    /** Re-runs purges a previous process journalled but did not finish. */
    suspend fun resumePendingPurges() {
        mutex.withLock {
            // An unresolved edit's receipt is a journal entry; purging before its read-back erases it.
            if (attempt is SignOutAttempt.Unresolved) return@withLock
            // The same rule for an edit no attempt owns. What the journal carries is the evidence
            // an unowned END read-back needs; the guard covers every Unknown phase because the
            // phase alone does not say which edit is waiting.
            if (identityPersistencePending?.phase is PendingPersistence.Phase.Unknown) return@withLock
            resumePendingPurgesLocked()
        }
    }

    suspend fun refresh(
        intent: RefreshIntent,
        origin: QueryOrigin = QueryOrigin.CALLER,
        /** The [decisionGeneration] the caller believes it is still querying for, if it pinned one. */
        requireDecisionGeneration: AccessDecisionGeneration? = null,
        /**
         * Probe ticks and scheduled retries: the [probeEpoch] the caller believes it still owns.
         *
         * Checking it in the probe *before* calling here is not enough. A sign-out landing between
         * that check and this critical section makes [StartedQuery] capture the **post**-sign-out
         * generation and fence, so [apply]'s staleness checks are self-consistent, the answer is
         * applied, and an `Unauthenticated` TRANSIENT arms a recheck — the retry ladder survives
         * the teardown. The check has to happen where the state is captured.
         */
        requireProbeEpoch: Long? = null
    ) {
        var token: Long? = null
        val started: StartedQuery = mutex.withLock {
            // First, ahead of the schedule and the store: an open sign-out admits no query at all.
            if (!accessAdmittedLocked()) return
            if (requireProbeEpoch != null && probeEpoch != requireProbeEpoch) return
            // The caller pinned this query to a binding. `launch` gives no ordering guarantee
            // against a sign-out that ran while it was still queued, and by the time this body runs
            // the generation it would otherwise capture is the *new* one — so an unauthorised query
            // would apply cleanly and re-arm a recheck for a session that ended.
            if (requireDecisionGeneration != null &&
                decisionGeneration != requireDecisionGeneration.value
            ) {
                return
            }
            // Validate and defer under the same coordinator lock as identity teardown. A stale
            // queued lookup must not revive the floor's timer before its lifetime check runs.
            // Deferred callbacks carry this binding's probeEpoch back through the check above;
            // unlike decisionGeneration, it survives a live probe's StableInactive answers.
            if (!schedule.shouldQuery(intent, origin)) {
                schedule.deferUntilFloor(intent, bindingEpoch = probeEpoch)
                return
            }
            val record = store.load()
            if (intent == RefreshIntent.FORCE_PREMIUM) {
                // Owner-bound, not a global flag: an escalation still running for a signed-out
                // user must not suppress the new user's first query.
                if (forcePremiumToken != null && forcePremiumOwner == record.ownerUid) return
                token = ++nextRequestToken
                forcePremiumToken = token
                forcePremiumOwner = record.ownerUid
            }
            schedule.recordQueryStarted()
            StartedQuery(record.fence(), decisionGeneration, boundIdentityLocked())
        }

        val result = try {
            source.fetch(freshPremium = intent.canGrantPremium)
        } finally {
            // Released on every exit, including cancellation, but only by the request that took it.
            //
            // NonCancellable covers the release alone, never the fetch above. Taking the mutex
            // suspends whenever anything else holds it, and a cancelled coroutine cannot suspend —
            // so without this a cancellation timed against any other coordinator call left the
            // token behind and wedged every later escalation for that owner.
            token?.let { mine ->
                withContext(NonCancellable) {
                    mutex.withLock { if (forcePremiumToken == mine) clearForcePremiumLocked() }
                }
            }
        }

        when (result) {
            is EntitlementsResult.Answered ->
                apply(intent, result.outcome, result.identity, started)
            // No credential was obtained, so there is no identity to match. The outcome is always
            // indeterminate, which can neither grant nor tear down, so applying it is safe.
            is EntitlementsResult.Unauthenticated ->
                apply(intent, result.outcome, answeredAs = null, started = started)
        }
    }

    /** Feeds a WebSocket topic rejection into the same reducer. Not produced by the REST path. */
    suspend fun onTopicRejected(code: TopicRejection) {
        val started = mutex.withLock {
            if (!accessAdmittedLocked()) return
            StartedQuery(store.load().fence(), decisionGeneration, boundIdentityLocked())
        }
        val outcome = when (code) {
            TopicRejection.PREMIUM_REQUIRED -> EntitlementsOutcome.PremiumRequired
            TopicRejection.KRX_ENTITLEMENT_REQUIRED -> EntitlementsOutcome.KrxEntitlementRequired
        }
        // The rejection concerns the session the coordinator already owns, and the namespace fence
        // covers that. There is no separate transport identity to match against.
        apply(RefreshIntent.FORCE_ENTITLEMENTS, outcome, answeredAs = null, started = started)
    }

    /** The session the published binding is standing on, or null before anything is bound. */
    private fun boundIdentityLocked(): EntitlementsIdentity? {
        val bound = _state.value
        val uid = bound.uid ?: return null
        val generation = bound.authGeneration ?: return null
        return EntitlementsIdentity(uid, generation)
    }

    private fun clearForcePremiumLocked() {
        forcePremiumToken = null
        forcePremiumOwner = null
    }

    private data class StartedQuery(
        val fence: AccessFence,
        val generation: Long,
        /**
         * The auth session the binding was standing on when this query left.
         *
         * Not the same question as "is the answer's session live". A binding made from a delayed
         * `(A, g1)` observation while the live session is already `(A, g2)` sends a query that
         * runs — and answers — as `g2`. Every other check passes: the decision generation has not
         * moved, the namespace is the same, the owner uid matches, and the answer's session *is*
         * the live one. The result would then be published carrying the `g1` the binding still
         * holds. Comparing against what was bound is what refuses it.
         */
        val boundIdentity: EntitlementsIdentity?
    )

    private suspend fun apply(
        intent: RefreshIntent,
        outcome: EntitlementsOutcome,
        /** Transport identity the answer came from, or null when the input had none. */
        answeredAs: EntitlementsIdentity?,
        started: StartedQuery
    ) {
        // One lock hold for the whole thing: decide *and* act on the schedule.
        //
        // Releasing between the two is the same window in a different place. A sign-out landing
        // there runs its own `schedule.cancel()` first, and this answer then re-arms the retry the
        // teardown had just removed. Every armed callback also captures the current probe epoch
        // and revalidates it in [refresh], covering teardown after this lock is released.
        //
        // Lock order is coordinator then schedule, and never the reverse: [RecheckSchedule] only
        // reaches back through its fire-time callback, which runs in its own coroutine after both
        // locks are released.
        mutex.withLock {
            // An answer landing while a sign-out is open is dropped without arming anything.
            if (!accessAdmittedLocked()) return
            val record = store.load()
            // Five independent staleness checks, because each catches something the others miss:
            //  - generation: an authoritative loss or reset that rotated nothing,
            //  - fence: a namespace that has since been retired,
            //  - owner: an answer the transport actually fetched as somebody else,
            //  - auth session: the same uid on a session the transport has since superseded,
            //  - bound session: an answer for a session this binding was never standing on.
            if (started.generation != decisionGeneration) return
            if (record.fence() != started.fence) return

            // Null means the answer was held back rather than decided.
            val decision: AccessDecision? = if (answeredAs == null) {
                decideLocked(record, intent, outcome)
            } else {
                if (answeredAs.ownerUid != record.ownerUid) return
                // ...and the session the binding was standing on, which the live check above
                // cannot stand in for: both can be the *new* session while the binding is still
                // the old one.
                if (started.boundIdentity != null && answeredAs != started.boundIdentity) return
                val live = source.currentIdentity()
                when {
                    live == answeredAs -> decideLocked(record, intent, outcome)
                    // A *different* session superseded this answer, and whatever established it
                    // owns the schedule from here.
                    live != null -> return
                    // No credential to compare against. That supersedes nothing, so nobody else
                    // will arm a re-check — returning here too would make a transient credential
                    // failure terminal.
                    else -> null
                }
            }

            val recheck = if (decision != null) {
                decision.recheck
            } else {
                // Held back, not decided. The answer stays out of the state, but the query is
                // retried at its own strength so an eventual identity reaches the same conclusion.
                // The floor is the one the answer stated, falling back to the same default the
                // reducer uses for an outcome it cannot settle — zero would re-query immediately
                // inside a window the server had explicitly asked us to wait out.
                RecheckRequest(
                    intent,
                    minDelayMillis = outcome.statedRetryFloorMillis()
                        ?: PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS
                )
            }
            if (recheck == null) schedule.cancel()
            else schedule.schedule(recheck, bindingEpoch = probeEpoch)
        }
    }

    /** Reduce, persist, publish. Callers must hold [mutex] — hence the `Locked` suffix. */
    private suspend fun decideLocked(
        record: AccessEpochRecord,
        intent: RefreshIntent,
        outcome: EntitlementsOutcome
    ): AccessDecision {
        val decision = PremiumAccessReducer.reduce(
            current = record.toSnapshotFacts(_state.value.state, _krx.value),
            intent = intent,
            outcome = outcome
        )

        // I4: persist the new id and the purge journal *before* the transition is observable.
        // Publishing first would leave a visible Rejected with a namespace still live if the
        // write failed.
        persistRotationsLocked(decision.effects)

        if (outcome.isAuthoritativeLoss()) decisionGeneration += 1

        // The generation was published when this owner was bound and does not move while they
        // stay bound — an answer for anyone else never reaches here, `apply` refuses it first.
        _state.value = OwnedPremiumAccess(record.ownerUid, _state.value.authGeneration, decision.state)
        _krx.value = decision.krx
        _lastEffects.value = decision.effects

        if (decision.effects.any {
                it == AccessEffect.PurgeUserScope || it == AccessEffect.PurgeCapabilityScope
            }
        ) {
            resumePendingPurgesLocked()
        }
        return decision
    }

    private fun EntitlementsOutcome.isAuthoritativeLoss(): Boolean =
        this is EntitlementsOutcome.StableInactive || this == EntitlementsOutcome.PremiumRequired

    private suspend fun persistRotationsLocked(effects: List<AccessEffect>) {
        val rotateUser = AccessEffect.RotateUserEpoch in effects
        val rotateKrx = AccessEffect.RotateKrxEpoch in effects
        if (rotateUser || rotateKrx) {
            store.beginRotation(rotateUser = rotateUser, rotateKrx = rotateKrx)
        }
        // AccessEffect.PushDelete is intentionally not executed; see the class KDoc.
        // AccessEffect.StartForcePremiumSingleFlight is carried out by the scheduled recheck.
    }

    /**
     * Runs every outstanding journal entry and drops only the ones that actually completed.
     *
     * Per entry, not per journal: a chain of account switches leaves several superseded
     * namespaces, and clearing them together would strand whichever ones the purger could not
     * finish.
     *
     * Returns false when a purger reported [PurgeResult.Failed]. [PurgeResult.Deferred] is not a
     * failure: it is what the current wiring always answers.
     */
    private suspend fun resumePendingPurgesLocked(): Boolean {
        val record = store.load()
        if (record.pendingPurges.isEmpty()) return true
        var failed = false
        val completed = record.pendingPurges.filter { entry ->
            val namespace = PurgeNamespace(
                ownerUid = entry.ownerUid,
                currentUserAccessEpoch = record.userAccessEpoch,
                currentKrxCapabilityEpoch = record.krxCapabilityEpoch,
                pending = entry
            )
            val results = entry.scopes.map { purgeScope ->
                when (purgeScope) {
                    PurgeScope.USER -> userPurger.purgeUserScope(namespace)
                    PurgeScope.CAPABILITY -> capabilityPurger.purgeCapabilityScope(namespace)
                }
            }
            if (results.any { it is PurgeResult.Failed }) failed = true
            results.all { it == PurgeResult.Completed }
        }
        if (completed.isNotEmpty()) store.completePurges(completed)
        return !failed
    }
}

/**
 * Nominal sleep before each propagation-probe tick, in milliseconds.
 *
 * These are **delays, not offsets** — iOS `EntitlementsManager.swift`
 * `premiumRejectionRetryDelaysSeconds` is consumed by a sleep per tick, so the nominal window is
 * cumulative: 0s, 2s, 7s, 17s, 37s.
 */
private val PROBE_DELAY_MILLIS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 20_000L)

/**
 * How long a probe tick actually waits.
 *
 * Injected for the same reason [RecheckClock] is: the production spread is random, and a test
 * that cannot pin it cannot assert when a tick fired.
 */
fun interface ProbeJitter {
    fun delayMillisFor(index: Int, nominalMillis: Long): Long

    companion object {
        /**
         * iOS parity: the first tick is spread over 0-1s and the rest by ±20%, so a fleet of
         * clients finishing a purchase together does not re-query in lockstep.
         */
        val Default = ProbeJitter { index, nominal ->
            if (index == 0) kotlin.random.Random.nextLong(0L, 1_001L)
            else (nominal * (0.8 + kotlin.random.Random.nextDouble() * 0.4)).toLong()
        }

        /** Exact nominal delays, so tick times are assertable. */
        val None = ProbeJitter { _, nominal -> nominal }
    }
}

/** WebSocket topic rejection codes (`app/topic_wire.py`). Fed in by a later WS slice. */
enum class TopicRejection { PREMIUM_REQUIRED, KRX_ENTITLEMENT_REQUIRED }
