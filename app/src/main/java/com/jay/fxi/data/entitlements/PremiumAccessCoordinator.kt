package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.remote.TopicGrantToken
import com.jay.fxi.data.remote.TopicSessionFence
import com.jay.fxi.domain.model.TopicRejectionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Between automatic persistence rounds. Long enough that a transient disk fault can clear. */
private const val DEFAULT_PERSISTENCE_RETRY_DELAY_MILLIS = 2_000L

/** The longest a loss recovery round waits before trying the disk again. */
private const val LOSS_RECOVERY_MAX_DELAY_MILLIS = 300_000L

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
    store: AccessEpochStore,
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
    private val liveFence: () -> AuthIdentityFence?,
    /** Test barrier before completion delivery, outside both locks. Production leaves it empty. */
    private val beforeRecheckSettled: suspend (Long, Long) -> Unit = { _, _ -> },
    /** Observes a loss re-approval after the scheduler accepts it. */
    private val onLossReapprovalScheduled: (RefreshIntent, Long) -> Unit = { _, _ -> }
) {
    private val mutex = Mutex()

    /** Explicit losses whose rotation this process could not establish. Guarded by [mutex]. */
    private val lossSeals = LossSealLedger()

    /** Every store call this class makes; each confirmed record reaches [lossSeals] on its way back. */
    private val store: AccessEpochStore = ObservedStore(store)

    /** The loss recovery run while one is armed. Guarded by [mutex]; the run clears it when it has nothing to do. */
    private var lossRecovery: Job? = null

    /** A landed loss rotation whose journal is still to be handed to the purgers, or whose purge failed or threw. */
    private var lossCleanupOwed = false

    /**
     * Loss answers whose decision read failed, oldest first. Each holds its axes back from [state] and [krx] until
     * candidate recovery reads the record and applies or discards it (S1r-2c). Guarded by [mutex].
     */
    private val lossCandidates = ArrayList<LossCandidate>()
    private var nextHoldId: Long = 0L

    /** The candidate recovery run while one is armed. Guarded by [mutex]; the run clears it when no candidate is left. */
    private var candidateRecovery: Job? = null
    private var candidateRecoveryAttempts = 0

    /**
     * The re-check the current binding still owes (S1r-2a). Kept apart from the schedule's timer, so a query that is dropped,
     * goes stale or is cancelled, or an answer that clears the timer without answering the requirement, does not take the
     * requirement with it. Guarded by [mutex].
     */
    private var recheckDemand: RecheckDemand? = null

    /** One sequence for demands and query starts, so "started after the demand" can be compared. Guarded by [mutex]. */
    private var nextOrderSeq: Long = 0L

    /** Queries that have started and have not finished applying, by their start order. Guarded by [mutex]. */
    private val inFlightQueries = HashMap<Long, InFlightQuery>()

    internal data class RecheckDiagnostics(
        val bindingEpoch: Long,
        val registeredQueryCount: Int,
        /** The intent the current demand owes, or null with none. */
        val owedIntent: RefreshIntent? = null
    )

    /** Immutable diagnostic snapshot, including any registration incorrectly retained from an old binding. */
    internal suspend fun recheckDiagnostics(): RecheckDiagnostics = mutex.withLock {
        RecheckDiagnostics(probeEpoch, inFlightQueries.size, recheckDemand?.intent)
    }

    /** Diagnostic: how many loss candidates still hold access back (S1r-2c). */
    internal suspend fun heldLossCandidateCount(): Int = mutex.withLock { lossCandidates.size }

    /** An authentication answer stopped new automatic re-queries for this binding. Guarded by [mutex]. */
    private var authStopped = false

    /** The order of the event that last stopped or resumed [authStopped]; an answer that started earlier moves neither. */
    private var authStateOrder: Long = 0L

    /** A loss re-approval not yet reported through onLossReapprovalScheduled, because nothing could be armed for it yet. */
    private var unreportedReapproval: RefreshIntent? = null

    private var lossRecoveryAttempts = 0

    /**
     * The reducer's view of access: what every decision reads and publishes, and what binding and the probes read. Not
     * what consumers see — [state] and [krx] put the loss candidates' holds on top (S1r-2c §2.2). A write republishes
     * those in the same call, so no reader sees a held grant between the two. Guarded by [mutex].
     */
    private val _state = Authoritative(OwnedPremiumAccess())
    private val _krx = Authoritative(KrxCapabilityState.HIDDEN)

    private inner class Authoritative<T>(initial: T) {
        var value: T = initial
            set(next) {
                field = next
                republishEffectiveLocked()
            }
    }

    private val _effectiveState = MutableStateFlow(OwnedPremiumAccess())
    /**
     * The access decision **and the identity it was decided for**.
     *
     * A bare state cannot say whose it is, and Root reads auth and access from two different flows:
     * during the gap between Firebase reporting a new user and this coordinator being told about
     * them, a bare `PremiumConfirmed` left over from the previous user is what Root would branch on.
     * Publishing the owner alongside lets a reader refuse a grant that is not its own instead of
     * inferring it from timing.
     *
     * While a loss answer whose record could not be read holds the user axis, a grant is shown as NoGrant; the decision
     * itself is untouched and comes back as soon as the hold is released (S1r-2c).
     */
    val state: StateFlow<OwnedPremiumAccess> = _effectiveState.asStateFlow()

    private val _effectiveKrx = MutableStateFlow(KrxCapabilityState.HIDDEN)
    /** KRX visibility as consumers see it: hidden while any loss candidate holds the capability axis (S1r-2c). */
    val krx: StateFlow<KrxCapabilityState> = _effectiveKrx.asStateFlow()

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

    /**
     * The grant last issued to a topic session by [topicGrant], or null. Guarded by [mutex].
     *
     * Only the latest is kept. An older token is not a grant anyone may still act for — it either
     * named the same context (and was re-issued as itself) or a context that has since moved.
     */
    private var issuedTopicGrant: IssuedTopicGrant? = null
    private var nextTopicGrant: Long = 0L

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

    /**
     * The last hold this process told the user about. Ids are never reused.
     *
     * Kept here rather than derived, and by the coordinator rather than a surface: a hold running a
     * re-check is indistinguishable from one that never stopped, so "we have already said something
     * about this" has to be remembered by whoever serialises the changes. A surface holding it
     * instead would be a second source of truth that can disagree.
     *
     * Deliberately **not** cleared when the hold resolves. Whether it still counts is one question —
     * does it name the hold that is standing now — and answering it in one place is what keeps a
     * stale value from mattering. Clearing as well would be a second guard covering the same case,
     * and neither would then be observable on its own.
     */
    private var surfacedHoldId: Long? = null

    /**
     * The hold whose round is executing right now, or null. Guarded by [mutex].
     *
     * Tracked rather than derived because the fields cannot say it. A round's read-back runs before
     * the updated hold is published, and admitting an automatic round *keeps* a wake that was held through
     * it — so "no schedule and a standing wake" describes both a request nobody has picked up and a
     * round already at work.
     */
    private var runningPersistenceId: Long? = null

    private val _identityRecovery = MutableStateFlow<IdentityRecoveryState>(IdentityRecoveryState.None)

    /** What a surface may say about identity work that has not finished. See [IdentityRecoveryState]. */
    internal val identityRecovery: StateFlow<IdentityRecoveryState> = _identityRecovery.asStateFlow()

    /** Publishes the hold so waiters see the revision they were handed move. */
    private fun setPendingLocked(pending: PendingPersistence?) {
        identityPersistencePending = pending
        _persistenceSignal.value = pending
        // The first stop is what earns the banner. Later rounds of the same hold keep it, blocked
        // or not, because the id has not changed.
        if (pending?.blocked != null) surfacedHoldId = pending.id
        publishIdentityRecoveryLocked()
    }

    /**
     * Recomposes the surface state from everything that feeds it, under the lock that changed it.
     *
     * Called from every writer of the display inputs, so a surface never sees an attempt change
     * without the recovery status that belongs to it, or a hold change without its own progress.
     */
    private fun publishIdentityRecoveryLocked() {
        _identityRecovery.value = identityRecoveryOf(
            attempt = attempt,
            automaticRunning = _recoveryStatus.value?.running == true,
            hold = identityPersistencePending,
            // The only question a stale id has to survive: does it name the hold standing now.
            surfaced = identityPersistencePending?.let { surfacedHoldId == it.id } ?: false,
            runningHoldId = runningPersistenceId
        )
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
        RecheckSchedule(
            scope,
            clock,
            onDue = { intent, origin, bindingEpoch -> refresh(intent, origin, requireProbeEpoch = bindingEpoch) },
            onSettled = { bindingEpoch, revision ->
                beforeRecheckSettled(bindingEpoch, revision)
                onScheduleSettled(bindingEpoch, revision)
            }
        )

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
        // Before anything is touched: this task would raise the generation, republish state and
        // edit the record, and its success would clear somebody else's hold on the way out. A hold
        // is resumed through resumePersistence, never by re-entering here.
        check(identityPersistencePending == null) {
            "an identity change entered over an unresolved hold: $identityPersistencePending"
        }
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
        check(identityPersistencePending == null) {
            "an end entered over an unresolved hold: $identityPersistencePending"
        }
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
     * hand every failure a fresh three rounds.
     *
     * Different work never lands here. Replacing a hold would mint a new id, budget and phase, so
     * whoever was waiting on the old one holds a resume contract that no longer describes anything
     * — and the journal surviving is not the same fact as the *work* being handed over. The FIFO is
     * what keeps this true: a head task with an unresolved hold has not returned.
     */
    private fun openOrExtendHoldLocked(work: IdentityWork, phase: PendingPersistence.Phase) {
        val existing = identityPersistencePending
        check(existing == null || existing.work == work) {
            "a hold for $work opened over one for ${existing?.work}"
        }
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
                // After the bind's confirmed record was observed: a seal still standing for this owner is owed to
                // this binding too, before any answer reaches it (S1r-2b §7.2).
                lossSeals.registerReapproval(probeEpoch, work.fence.uid)
                if (!lossSeals.isEmpty) kickLossRecoveryLocked()
            }
            is IdentityWork.End, IdentityWork.UnverifiedStart -> completedBinding = null
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
        // Admission can reopen on the same binding here (a StartupPurge hold): what it owes is looked at again.
        ensureDemandArmedLocked()
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
        // Marked before the read-back, not after: that read is the round's first disk work, and
        // until it returns nothing else would say the round had started.
        runningPersistenceId = admitted.id
        publishIdentityRecoveryLocked()
        try {
            return runAdmittedRoundLocked(admitted)
        } finally {
            runningPersistenceId = null
            publishIdentityRecoveryLocked()
        }
    }

    private suspend fun runAdmittedRoundLocked(admitted: PendingPersistence): IdentityStep {
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
        is IdentityWork.End, IdentityWork.StartupPurge, IdentityWork.UnverifiedStart ->
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
            // Planned again from what the round was handed — the read-back a resolution kept, or a new
            // read — never from the record the failed attempt started with.
            IdentityWork.UnverifiedStart -> {
                val read = before ?: unownedEditLocked(work, PendingEdit.READ, before = null) { store.load() }
                if (read == null) stalledLocked(open = null) else retireUnverifiedStartLocked(read)
            }
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
        lossSeals.registerReapproval(probeEpoch, candidate.uid)
        if (!lossSeals.isEmpty) kickLossRecoveryLocked()
        if (!cleanupLocked()) return barrierOutcome(BarrierStep.CLEANUP_FAILED)
        // Read after the last suspension.
        if (!SignOutAttemptPolicy.barrierBound(candidate, EditResult.Landed(after), liveFence())) {
            return barrierOutcome(BarrierStep.RECAPTURE)
        }
        setAttemptLocked(null)
        ensureDemandArmedLocked()
        return barrierOutcome(BarrierStep.RELEASED, AccessDecisionGeneration(decisionGeneration))
    }

    private suspend fun finishSignedOutLocked(): BarrierOutcome {
        if (!cleanupLocked()) return barrierOutcome(BarrierStep.CLEANUP_FAILED)
        if (liveFence() != null) return barrierOutcome(BarrierStep.RECAPTURE)
        setAttemptLocked(null)
        ensureDemandArmedLocked()
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
        // The surface reads the attempt itself, not `recovering` — an Armed attempt is excluded
        // from that flag and would otherwise be a sealed state nothing can report.
        publishIdentityRecoveryLocked()
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
            // The third writer of a surface input: whether a run is under way is part of what the
            // banner says, and it changes here without the attempt or the hold moving.
            publishIdentityRecoveryLocked()
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
        // A binding's re-check state and query registrations end with it (S1r-2a §2.1).
        inFlightQueries.clear()
        recheckDemand = null
        authStopped = false
        authStateOrder = 0L
        unreportedReapproval = null
        // Not republished here: every caller publishes NoGrant next, with no suspension between (S1r-2c §2.1). Republishing
        // now would show the ending binding's grant with its holds already gone.
        lossCandidates.clear()
    }

    private data class ProbeRun(val owner: String, val epoch: Long)

    /** Re-runs purges a previous process journalled but did not finish. */
    /**
     * The purge resume the identity consumer runs before it accepts anything, as a head task.
     *
     * Startup-only, and kept apart from [resumePendingPurges] because that one *skips* in front of
     * an edit whose outcome is unknown — a skip is not a finished startup task, and a caller that
     * read it as one would open the funnel over work that never ran.
     *
     * Nothing has opened an attempt or a hold when this runs, so both are contract checks rather
     * than gates. A failure here becomes an [IdentityWork.StartupPurge] hold: the consumer waits on
     * it and resumes it by id, and access stays refused meanwhile — not because the journal still
     * has entries, which is the normal answer from today's purgers, but because this resume ended
     * in an exception and the store it could not finish is the same one every access query reads.
     */
    internal suspend fun resumeStartupPurge(): IdentityStep = mutex.withLock {
        check(attempt == null) { "a sign-out attempt was open before the identity consumer started" }
        check(identityPersistencePending == null) { "a hold stood before the identity consumer started" }
        val work = IdentityWork.StartupPurge
        // The whole of this work is the cleanup: a purge resume writes no identity edit, so there
        // is no landing to establish and nothing for a read-back to classify.
        cleanupAndFinishLocked(work, completionFor(work))
    }

    /**
     * Settles a cold start whose first identity observation is no uid (plan amendment 6).
     *
     * Not a sign-out being inferred: a sign-out that never reached the record and a restore error look
     * the same from here. The namespace is retired because nothing shows it continues with the starting
     * identity; preferences are not in it. See [AccessEpochTransitions.retireUnverifiedStart].
     *
     * For the start-up path only — the binder's first observation, which the stream replays on
     * registration ahead of any sign-out request, after the startup purge finished. So the checks below
     * are the contract, not gates. A failure opens a hold, and that hold is resumed through
     * [resumePersistence], never by calling this again.
     */
    internal suspend fun onUnverifiedStart(): IdentityStep = mutex.withLock {
        check(attempt == null) { "a sign-out attempt was open at the first identity observation" }
        check(identityPersistencePending == null) { "a hold stood at the first identity observation" }
        // Taking away, so published before the disk work — for the same reason [onSignedOut] gives.
        decisionGeneration += 1
        cancelProbeLocked()
        clearForcePremiumLocked()
        _state.value = OwnedPremiumAccess(null, null, PremiumAccessState.NoGrant)
        _krx.value = KrxCapabilityState.HIDDEN
        schedule.cancel(preserveServerFloor = true)
        val work = IdentityWork.UnverifiedStart
        val read = unownedEditLocked(work, PendingEdit.READ, before = null) { store.load() }
            ?: return@withLock stalledLocked(open = null)
        retireUnverifiedStartLocked(read)
    }

    /**
     * From a read that succeeded: complete, or run the retirement and land it.
     *
     * [read] is also when a LEAVE completes — a marker set after it is later input, for whatever binds
     * next. The store decides the edit on the record it reads inside its own edit, so a marker that
     * arrived after [read] still takes part; the landing check recognises what it caused.
     */
    private suspend fun retireUnverifiedStartLocked(read: AccessEpochRecord): IdentityStep {
        val work = IdentityWork.UnverifiedStart
        if (SignOutAttemptPolicy.planUnverifiedStart(read) == SignOutAttemptPolicy.UnverifiedStartPlan.LEAVE) {
            adoptLandingLocked(work)
            // A retry that reads nothing owed finishes here, and this is what ends its hold: there is no
            // cleanup to run, which is the only other place a hold is cleared.
            setPendingLocked(null)
            ensureDemandArmedLocked()
            return IdentityStep.Applied(completionFor(work))
        }
        unownedEditLocked(work, PendingEdit.UNVERIFIED_START, before = read) { store.retireUnverifiedStart() }
            ?: return stalledLocked(open = null)
        return landAndFinishLocked(work)
    }

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
        var queryIntent = intent
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
            // A caller asking again is what resumes re-queries an authentication answer stopped, before the floor is
            // consulted: a caller the floor holds back still gets its request armed below (S1r-2a §2.4).
            if (origin == QueryOrigin.CALLER) resumeAfterAuthStopLocked()
            if (origin == QueryOrigin.SCHEDULED) {
                // A timer serves the demand: with nothing owed it asks nothing, it runs at the demand's current strength, and a
                // query already running at that strength answers for it.
                val demand = recheckDemand ?: return
                queryIntent = maxOf(intent, demand.intent)
                if (inFlightCoversLocked(probeEpoch, queryIntent)) return
            }
            // Validate and defer under the same coordinator lock as identity teardown. A stale
            // queued lookup must not revive the floor's timer before its lifetime check runs.
            // Deferred callbacks carry this binding's probeEpoch back through the check above;
            // unlike decisionGeneration, it survives a live probe's StableInactive answers.
            val queryAt = clock.elapsedMillis()
            if (!schedule.shouldQuery(queryIntent, origin, now = queryAt)) {
                val heldByFloor = schedule.floorBlocksNow(now = queryAt)
                schedule.deferUntilFloor(queryIntent, bindingEpoch = probeEpoch)
                // The debounce alone is not a request; a floor holding a caller back is (S1r-2a §2.1).
                if (heldByFloor) {
                    raiseDemandLocked(queryIntent, independent = true)
                    ensureDemandArmedLocked()
                }
                return
            }
            val record = store.load()
            if (queryIntent == RefreshIntent.FORCE_PREMIUM) {
                // Owner-bound, not a global flag: an escalation still running for a signed-out
                // user must not suppress the new user's first query.
                if (forcePremiumToken != null && forcePremiumOwner == record.ownerUid) return
                token = ++nextRequestToken
                forcePremiumToken = token
                forcePremiumOwner = record.ownerUid
            }
            schedule.recordQueryStarted()
            val order = ++nextOrderSeq
            inFlightQueries[order] = InFlightQuery(probeEpoch, queryIntent)
            StartedQuery(record.fence(), decisionGeneration, boundIdentityLocked(), order, probeEpoch, queryIntent)
        }

        try {
            fetchAndApply(queryIntent, started) { token }
        } finally {
            // Around the fetch and the apply both, however they end: whatever this query was relied on to answer is looked at
            // again once it cannot answer any more (S1r-2a §2.4).
            withContext(NonCancellable) {
                mutex.withLock {
                    inFlightQueries.remove(started.order)
                    if (started.binding == probeEpoch) ensureDemandArmedLocked()
                }
            }
        }
    }

    private suspend fun fetchAndApply(intent: RefreshIntent, started: StartedQuery, token: () -> Long?) {
        val result = try {
            source.fetch(freshPremium = intent.canGrantPremium)
        } finally {
            // Released on every exit, including cancellation, but only by the request that took it.
            //
            // NonCancellable covers the release alone, never the fetch above. Taking the mutex
            // suspends whenever anything else holds it, and a cancelled coroutine cannot suspend —
            // so without this a cancellation timed against any other coordinator call left the
            // token behind and wedged every later escalation for that owner.
            token()?.let { mine ->
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

    /**
     * The fence a topic session may run under right now, or null when no premium grant stands.
     *
     * Issued only while access is admitted, the state is [PremiumAccessState.PremiumConfirmed], a
     * binding exists, the record on disk is owned by that binding and the live identity is that
     * binding — all read under one lock hold. The context it names is the binding, the record's
     * three-part fence and [decisionGeneration]; asking again while that context holds returns the
     * **same** token, and a changed context — a KRX rotation alone included — gets a new one.
     *
     * A read that throws or is cancelled issues nothing and leaves the previous grant as it was.
     *
     * Wiring this to a session's `setAccess` is not done here.
     */
    internal suspend fun topicGrant(): TopicSessionFence? = mutex.withLock {
        if (!accessAdmittedLocked()) return@withLock null
        if (_state.value.state != PremiumAccessState.PremiumConfirmed) return@withLock null
        // Refused without touching the issued grant: a hold is not a loss, and it must not make its own candidate stale.
        if (lossCandidates.any { PurgeScope.USER in it.axes }) return@withLock null
        val bound = boundIdentityLocked() ?: return@withLock null
        val record = store.load()
        if (record.ownerUid != bound.ownerUid) return@withLock null
        // Checked against the record just confirmed, not a value computed before it (S1r-2b §5).
        if (PurgeScope.USER in lossSeals.sealedAxes(bound.ownerUid)) return@withLock null
        if (source.currentIdentity() != bound) return@withLock null
        val context = TopicGrantContext(bound, record.fence(), decisionGeneration)
        val issued = issuedTopicGrant?.takeIf { it.context == context }
            ?: IssuedTopicGrant(TopicGrantToken(++nextTopicGrant), context).also { issuedTopicGrant = it }
        TopicSessionFence(
            identity = AuthIdentityFence(bound.ownerUid, bound.authGeneration),
            userAccessEpoch = record.userAccessEpoch,
            grant = issued.token
        )
    }

    /**
     * Feeds one acknowledgement's WebSocket refusals into the reducer, for the grant they answered.
     *
     * [grant] is the token on the fence of the connection that was refused, as that connection was
     * opened — not the session's current one. [reasons] is the acknowledgement's whole set, collapsed
     * by [TopicRejection.of] into at most one decision; reasons that are not about access reach
     * neither the reducer nor the schedule.
     *
     * The refusal is applied only if, under one lock hold and in this order: [grant] is the grant
     * last issued, access is admitted, the decision generation is still the one it was issued at,
     * the record's fence is unchanged, the binding is the same and the live identity is still that
     * binding. Matching the token is necessary and not sufficient — the context behind it can have
     * moved without anyone asking for a new grant.
     *
     * Anything else discards it: no state, rotation, purge or schedule change, not even a
     * cancellation, because whatever is scheduled belongs to a later event. A live identity that
     * cannot be read discards it too, and that says nothing about a sign-out having happened.
     *
     * A record read that throws does not throw from here: the refusal is held as a loss candidate unless it is already
     * known stale, and candidate recovery reads the record again (S1r-2c). An identity read that throws still throws,
     * before the reducer runs. A rotation that cannot be persisted does not throw; see [decideLocked].
     */
    internal suspend fun onTopicRejected(grant: TopicGrantToken, reasons: Collection<TopicRejectionReason>) {
        val outcome = when (TopicRejection.of(reasons) ?: return) {
            TopicRejection.PREMIUM_REQUIRED -> EntitlementsOutcome.PremiumRequired
            TopicRejection.KRX_ENTITLEMENT_REQUIRED -> EntitlementsOutcome.KrxEntitlementRequired
        }
        mutex.withLock {
            val context = issuedTopicGrant?.takeIf { it.token == grant }?.context ?: return
            if (!accessAdmittedLocked()) return
            if (context.generation != decisionGeneration) return
            val record = try {
                store.load()
            } catch (failed: Exception) {
                holdUnreadableLossLocked(outcome, CandidateProvenance.Topic(grant, context), failed)
                return
            }
            if (record.fence() != context.access) return
            if (boundIdentityLocked() != context.identity) return
            if (source.currentIdentity() != context.identity) return
            val decision = decideLocked(record, RefreshIntent.FORCE_ENTITLEMENTS, outcome)
            // Not a query's answer, so it answers no demand; a re-check it asks for is a new requirement (S1r-2a §2.1).
            val recheck = decision.recheck
            if (recheck != null) {
                raiseDemandLocked(recheck.intent, independent = true)
                armRecheckLocked(recheck)
            } else if (recheckDemand == null) {
                schedule.cancel()
            }
            ensureDemandArmedLocked()
        }
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
        val boundIdentity: EntitlementsIdentity?,
        /** Start order, shared with demands (S1r-2a §2.2). */
        val order: Long,
        /** The [probeEpoch] this query started under. */
        val binding: Long,
        /** The intent it actually asked with. */
        val intent: RefreshIntent
    )

    /** What the current binding still owes: a query at least [intent] strong that starts after [raisedAt]. */
    private data class RecheckDemand(val binding: Long, val intent: RefreshIntent, val raisedAt: Long)

    private data class InFlightQuery(val binding: Long, val intent: RefreshIntent)

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
            val record = try {
                store.load()
            } catch (failed: Exception) {
                holdUnreadableLossLocked(outcome, CandidateProvenance.Query(started, answeredAs), failed)
                return
            }
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

            if (decision != null) {
                settleDemandLocked(started, outcome, decision.recheck)
            } else {
                // Held back, not decided. The answer stays out of the state, but the query is
                // retried at its own strength so an eventual identity reaches the same conclusion.
                // The floor is the one the answer stated, falling back to the same default the
                // reducer uses for an outcome it cannot settle — zero would re-query immediately
                // inside a window the server had explicitly asked us to wait out.
                val retry = RecheckRequest(
                    intent,
                    minDelayMillis = outcome.statedRetryFloorMillis()
                        ?: PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS
                )
                // The same requirement retried, not a new one.
                raiseDemandLocked(retry.intent, independent = false)
                armRecheckLocked(retry)
            }
            ensureDemandArmedLocked()
        }
    }

    /**
     * Updates the demand for a decided answer (S1r-2a §2.3) and acts on the schedule. Callers hold [mutex].
     *
     * Only an answer that started after the demand, asked at least as strongly and settled what the demand needs ends it.
     * An answer that does not leaves the timer alone even when it asks for nothing more; its own re-check joins the demand
     * as a retry. With no demand, or once it is answered, the schedule is armed or cleared as before.
     */
    private suspend fun settleDemandLocked(started: StartedQuery, outcome: EntitlementsOutcome, recheck: RecheckRequest?) {
        val authentication =
            outcome is EntitlementsOutcome.Indeterminate && outcome.reason == IndeterminateReason.AUTHENTICATION
        if (started.order > authStateOrder) {
            if (authentication) {
                authStopped = true
                authStateOrder = started.order
            } else if (authStopped) {
                authStopped = false
                authStateOrder = started.order
            }
        }
        val demand = recheckDemand
        val answered = demand != null &&
            started.binding == demand.binding &&
            started.order > demand.raisedAt &&
            started.intent >= demand.intent &&
            outcome.settlesDemandFor(demand.intent)
        if (demand == null || answered) {
            recheckDemand = null
            unreportedReapproval = null
            if (recheck == null) {
                schedule.cancel()
            } else {
                raiseDemandLocked(recheck.intent, independent = true)
                armRecheckLocked(recheck)
            }
        } else if (recheck != null) {
            raiseDemandLocked(recheck.intent, independent = false)
            armRecheckLocked(recheck)
        }
    }

    private fun EntitlementsOutcome.settlesDemandFor(demand: RefreshIntent): Boolean = when (this) {
        is EntitlementsOutcome.StableActive, is EntitlementsOutcome.StableInactive, is EntitlementsOutcome.PremiumRequired -> true
        // The premium axis is left as it was, so a premium demand is still owed.
        is EntitlementsOutcome.KrxEntitlementRequired -> demand != RefreshIntent.FORCE_PREMIUM
        else -> false
    }

    /**
     * Records a demand for the current binding. A retry of the one owed keeps its order unless it asks more strongly; an
     * independent event always takes a new one (S1r-2a §2.1). Callers hold [mutex].
     */
    private fun raiseDemandLocked(intent: RefreshIntent, independent: Boolean) {
        val current = recheckDemand
        recheckDemand = when {
            current == null -> RecheckDemand(probeEpoch, intent, ++nextOrderSeq)
            independent || intent > current.intent -> RecheckDemand(probeEpoch, maxOf(intent, current.intent), ++nextOrderSeq)
            else -> current
        }
    }

    /** Arms [recheck] as before, unless an authentication answer stopped re-queries: then only its floor is kept. */
    private suspend fun armRecheckLocked(recheck: RecheckRequest) {
        if (authStopped) schedule.recordFloorWithoutArming(recheck.minDelayMillis)
        else schedule.schedule(recheck, bindingEpoch = probeEpoch)
    }

    /**
     * Makes sure something will run the owed demand, and arms a re-query only when nothing will (S1r-2a §2.4). Callers hold
     * [mutex]; the schedule's lock is taken inside it, never the other way round, and no job is awaited.
     */
    private suspend fun ensureDemandArmedLocked() {
        val demand = recheckDemand ?: return
        if (demand.binding != probeEpoch || !accessAdmittedLocked() || authStopped) return
        // A query at least as strong is still running, whenever it started: its end looks again.
        if (inFlightCoversLocked(demand.binding, demand.intent)) return
        if (!schedule.foldIntoUnfired(demand.intent)) {
            schedule.schedule(RecheckRequest(demand.intent, minDelayMillis = 0L), bindingEpoch = demand.binding)
        }
        unreportedReapproval?.let { reapproval ->
            unreportedReapproval = null
            onLossReapprovalScheduled(reapproval, demand.binding)
        }
    }

    private fun inFlightCoversLocked(binding: Long, intent: RefreshIntent): Boolean =
        inFlightQueries.values.any { it.binding == binding && it.intent >= intent }

    private fun resumeAfterAuthStopLocked() {
        authStopped = false
        authStateOrder = ++nextOrderSeq
    }

    /** A timer finished, fired or not. Only the latest arming of this binding looks again; later ones own what follows. */
    private suspend fun onScheduleSettled(bindingEpoch: Long, revision: Long) {
        mutex.withLock {
            if (bindingEpoch != probeEpoch || schedule.revision != revision) return
            if (scope.coroutineContext[Job]?.isActive == false) return
            ensureDemandArmedLocked()
        }
    }

    /**
     * Reduce, persist, publish. Callers must hold [mutex] — hence the `Locked` suffix.
     *
     * I4 on the success path: the new ids and the purge journal are persisted before the transition is published, and
     * the purge runs after. A rotation whose write fails or whose outcome is unknown does not escape: the loss is still
     * published, and what could not be established is sealed and handed to loss recovery ([LossSealLedger], S1r-2b).
     * A loss for a target recovery already owns joins it and writes nothing now.
     *
     * What is sealed for this owner is applied before publishing, on both paths ([publishLocked]).
     */
    private suspend fun decideLocked(
        record: AccessEpochRecord,
        intent: RefreshIntent,
        outcome: EntitlementsOutcome,
        /**
         * Runs once the decision is published, before a cancellation after publishing goes through — for a caller whose own
         * follow-up must not be lost with it (S1r-2c §2.3). Protected from cancellation itself.
         */
        onPublished: (suspend (AccessDecision) -> Unit)? = null
    ): AccessDecision {
        val decision = PremiumAccessReducer.reduce(
            current = record.toSnapshotFacts(_state.value.state, _krx.value),
            intent = intent,
            outcome = outcome
        )
        // AccessEffect.PushDelete is intentionally not executed; see the class KDoc.
        // AccessEffect.StartForcePremiumSingleFlight is carried out by the scheduled recheck.
        val targets = buildList {
            if (AccessEffect.RotateUserEpoch in decision.effects) add(LossTarget.of(record, PurgeScope.USER))
            if (AccessEffect.RotateKrxEpoch in decision.effects) add(LossTarget.of(record, PurgeScope.CAPABILITY))
        }
        val fresh = targets.filterNot(lossSeals::joins)
        // Settled even when the caller is cancelled: once the write starts, its outcome is classified and the loss
        // published before cancellation is allowed through.
        val rotated = fresh.isEmpty() || withContext(NonCancellable) { rotateLossTargetsLocked(record, fresh) }

        if (outcome.isAuthoritativeLoss()) decisionGeneration += 1

        publishLocked(record.ownerUid, decision.state, decision.krx, decision.effects)

        // A cleanup recovery already owns keeps its retry deadline: a repeated input — a purge-only KRX false edge
        // included — does not run it early. Recovery's round covers this decision's journal too.
        val purgeNow = rotated && !lossCleanupOwed && decision.effects.any {
            it == AccessEffect.PurgeUserScope || it == AccessEffect.PurgeCapabilityScope
        }
        if (purgeNow && !currentCoroutineContext().isActive) lossCleanupOwed = true
        if (!rotated || lossCleanupOwed) kickLossRecoveryLocked()
        try {
            currentCoroutineContext().ensureActive()
            if (purgeNow) purgeOrHandOverLocked()
        } finally {
            onPublished?.let { followUp -> withContext(NonCancellable) { followUp(decision) } }
        }
        return decision
    }

    private fun EntitlementsOutcome.isAuthoritativeLoss(): Boolean =
        this is EntitlementsOutcome.StableInactive || this == EntitlementsOutcome.PremiumRequired

    /**
     * The first write for [fresh]. True when every target is established retired — the write returned, or a confirmed
     * read-back shows it. Otherwise the targets not established are sealed, this binding is owed a re-approval, and
     * false is returned. A CancellationException thrown by the store is an unknown outcome like any other.
     *
     * A null target is never ended by a read-back: its release needs a rotation that returned (S1r-2b §3B).
     */
    private suspend fun rotateLossTargetsLocked(record: AccessEpochRecord, fresh: List<LossTarget>): Boolean {
        try {
            store.beginRotation(
                rotateUser = fresh.any { it.axis == PurgeScope.USER },
                rotateKrx = fresh.any { it.axis == PurgeScope.CAPABILITY }
            )
            return true
        } catch (failed: Exception) {
            // Classified below. The write may have landed, so its cleanup is owed from here whatever the read-back says.
            lossCleanupOwed = true
        }
        val readBack = try {
            store.load()
        } catch (unreadable: Exception) {
            null
        }
        val owed = fresh.filter { target ->
            readBack == null || when (target) {
                is LossTarget.Namespace ->
                    LossObligations.judge(target.obligation, readBack) != ObligationStatus.RETIRED
                is LossTarget.NullNamespace -> true
            }
        }
        if (owed.isEmpty()) return true
        lossSeals.open(owed)
        lossSeals.registerReapproval(probeEpoch, record.ownerUid)
        return false
    }

    /**
     * Publishes a decided state after applying what is sealed for [ownerUid] (S1r-2b §7.1). A sealed user axis
     * suppresses a premium grant and hides KRX; a sealed KRX axis hides KRX only. A suppressed grant leaves the published
     * state as it was when that grants nothing, NoGrant otherwise, and owes this binding a re-approval.
     */
    private fun publishLocked(
        ownerUid: String?,
        state: PremiumAccessState,
        krx: KrxCapabilityState,
        effects: List<AccessEffect>
    ) {
        val sealed = lossSeals.sealedAxes(ownerUid)
        var published = state
        var visibleKrx = krx
        if (PurgeScope.USER in sealed) {
            if (state.grantsPremiumRuntime) {
                published = _state.value.state.takeUnless { it.grantsPremiumRuntime } ?: PremiumAccessState.NoGrant
                lossSeals.registerReapproval(probeEpoch, ownerUid)
            }
            visibleKrx = KrxCapabilityState.HIDDEN
        }
        if (PurgeScope.CAPABILITY in sealed) visibleKrx = KrxCapabilityState.HIDDEN
        // The generation was published when this owner was bound and does not move while they
        // stay bound — an answer for anyone else never reaches here, `apply` refuses it first.
        _state.value = OwnedPremiumAccess(ownerUid, _state.value.authGeneration, published)
        _krx.value = visibleKrx
        _lastEffects.value = effects
    }

    /**
     * The purge behind a landed loss rotation. A reported failure or a throw is not the caller's: loss recovery keeps
     * the cleanup owed and retries it without rotating again (S1r-2b §6). Deferred is a hand-over, not a failure.
     */
    private suspend fun purgeOrHandOverLocked() {
        val clean = try {
            resumePendingPurgesLocked()
        } catch (failed: Exception) {
            lossCleanupOwed = true
            kickLossRecoveryLocked()
            // A CancellationException from a purger or the store is a failed cleanup; the caller's own cancellation
            // still goes through, with the cleanup already handed over.
            currentCoroutineContext().ensureActive()
            false
        }
        if (!clean) {
            lossCleanupOwed = true
            kickLossRecoveryLocked()
        }
    }

    private fun kickLossRecoveryLocked() {
        if (lossRecovery != null) return
        lossRecovery = scope.launch {
            runRecovery(
                roundLocked = { lossRecoveryRoundLocked() },
                delayMillis = { recoveryDelayMillis(lossRecoveryAttempts) },
                clearIfMineLocked = { self -> if (lossRecovery === self) lossRecovery = null }
            )
        }
    }

    /**
     * One recovery worker: rounds under [mutex], waits outside it. Shared by loss recovery and candidate recovery, which
     * keep their own job, attempts and retry time.
     */
    private suspend fun runRecovery(
        roundLocked: suspend () -> RecoveryNext,
        delayMillis: () -> Long,
        clearIfMineLocked: (Job?) -> Unit
    ) {
        val self = currentCoroutineContext()[Job]
        try {
            while (true) {
                when (val next = mutex.withLock { roundLocked() }) {
                    RecoveryNext.Done -> return
                    RecoveryNext.Retry -> delay(delayMillis())
                    is RecoveryNext.AwaitAdmission -> merge(
                        attemptSignal.filter { it != next.attempt },
                        _persistenceSignal.filter { it != next.pending }
                    ).first()
                }
            }
        } finally {
            // However the run ends, a later kick must be able to start another. Only this run's own reference is cleared.
            withContext(NonCancellable) {
                mutex.withLock { clearIfMineLocked(self) }
            }
        }
    }

    private fun recoveryDelayMillis(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 16)
        return (persistenceRetryDelayMillis shl shift).coerceAtMost(LOSS_RECOVERY_MAX_DELAY_MILLIS)
    }

    /**
     * One loss recovery round (S1r-2b §4). Writes nothing while access is not admitted — an identity edit or an open
     * sign-out owns the record then — and waits for either to move.
     *
     * Rotates only the current owner's namespace, and only for a target of that owner (§3B (R)). A null target is
     * rotated only while a rotation can release it: its axis still has no epoch, or an unknown entry already covers it.
     * An obligation whose epoch left the record with nothing handed on gets its entry restored ([AccessEpochStore.journalRetired]).
     * A round that throws is retried after a growing delay. A round that leaves nothing it can act on ends the run; what is
     * still sealed then waits for evidence from elsewhere, and is not rotated on a timer.
     */
    private suspend fun lossRecoveryRoundLocked(): RecoveryNext {
        if (!accessAdmittedLocked()) {
            return RecoveryNext.AwaitAdmission(attemptSignal.value, _persistenceSignal.value)
        }
        try {
            val record = store.load()
            val owner = record.ownerUid
            val current = lossSeals.obligations().filter {
                it.ownerUid == owner && lossSeals.status(it) == ObligationStatus.STILL_CURRENT
            }
            val releasable = lossSeals.nullTargets().filter { rotationCanRelease(it, record) }
            val axes = current.map { it.axis } + releasable.map { it.axis }
            val rotateUser = PurgeScope.USER in axes
            val rotateKrx = PurgeScope.CAPABILITY in axes
            if (rotateUser || rotateKrx) {
                // Owed from the attempt: a rotation that lands and then throws is retired by the next read, and its
                // journal must not be left behind.
                lossCleanupOwed = true
                val after = store.beginRotation(rotateUser = rotateUser, rotateKrx = rotateKrx)
                releasable.forEach { lossSeals.releaseByRotation(it, record, after) }
            }
            lossSeals.obligations().filter { lossSeals.status(it) == ObligationStatus.UNKNOWN }.forEach {
                lossCleanupOwed = true
                store.journalRetired(it)
            }
            // Before the cleanup: a purge that keeps failing must not keep a resolved binding from being asked again.
            lossSeals.takeReapproval(probeEpoch, _state.value.uid)?.let { axes ->
                val intent =
                    if (PurgeScope.USER in axes) RefreshIntent.FORCE_PREMIUM else RefreshIntent.FORCE_ENTITLEMENTS
                // Handed to the demand before the next suspension; reported once something is armed for it (S1r-2a §2.5).
                raiseDemandLocked(intent, independent = true)
                unreportedReapproval = unreportedReapproval?.let { maxOf(it, intent) } ?: intent
                ensureDemandArmedLocked()
            }
            if (lossCleanupOwed) lossCleanupOwed = !resumePendingPurgesLocked()
        } catch (failed: Exception) {
            // A CancellationException thrown by the store or a purger is a failed round; this run being cancelled is not.
            currentCoroutineContext().ensureActive()
            lossRecoveryAttempts += 1
            return RecoveryNext.Retry
        }
        if (lossCleanupOwed || lossRecoveryHasWorkLocked()) {
            lossRecoveryAttempts += 1
            return RecoveryNext.Retry
        }
        lossRecovery = null
        lossRecoveryAttempts = 0
        return RecoveryNext.Done
    }

    /** Whether a round could still act on something, judged on the last confirmed record. */
    private fun lossRecoveryHasWorkLocked(): Boolean {
        val record = lossSeals.lastConfirmed ?: return !lossSeals.isEmpty
        return lossSeals.obligations().any {
            val status = lossSeals.status(it)
            status == ObligationStatus.UNKNOWN || (status == ObligationStatus.STILL_CURRENT && it.ownerUid == record.ownerUid)
        } || lossSeals.nullTargets().any { rotationCanRelease(it, record) }
    }

    private fun rotationCanRelease(seal: NullTargetSeal, record: AccessEpochRecord): Boolean =
        seal.ownerUid == record.ownerUid && (
            LossObligations.epochOf(record, seal.axis) == null ||
                record.pendingPurges.any { entry ->
                    seal.axis in entry.scopes &&
                        (entry.ownerUid == null || entry.ownerUid == seal.ownerUid) &&
                        when (seal.axis) {
                            PurgeScope.USER -> entry.userAccessEpoch
                            PurgeScope.CAPABILITY -> entry.krxCapabilityEpoch
                        } == null
                }
            )

    // --- S1r-2c: a loss answer whose record could not be read ------------------------------------------------------------

    /** Where a loss candidate came from, kept as it was so recovery checks it the way the original path would have. */
    private sealed interface CandidateProvenance {
        /** A REST answer: the query as it started, and the session the transport answered as. */
        data class Query(val started: StartedQuery, val answeredAs: EntitlementsIdentity?) : CandidateProvenance

        /** A WebSocket refusal: the grant it refused and the context that grant was issued in. No query order is made up. */
        data class Topic(val grant: TopicGrantToken, val context: TopicGrantContext) : CandidateProvenance
    }

    private class LossCandidate(
        val holdId: Long,
        /** The [probeEpoch] it was held under. */
        val binding: Long,
        val axes: Set<PurgeScope>,
        val outcome: EntitlementsOutcome,
        val provenance: CandidateProvenance,
        /** The stated server floor as an absolute time, taken once when the hold was made; null without one. */
        val floorNotBefore: Long?
    )

    private sealed interface CandidateCheck {
        data object Stale : CandidateCheck
        data object IdentityUnknown : CandidateCheck
        data class Valid(val record: AccessEpochRecord) : CandidateCheck
    }

    /** The axes a loss answer takes away, or null when it is not one — the reducer's loss branches (PremiumAccessReducer). */
    private fun EntitlementsOutcome.lossAxes(): Set<PurgeScope>? = when (this) {
        is EntitlementsOutcome.StableInactive, EntitlementsOutcome.PremiumRequired ->
            setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
        EntitlementsOutcome.KrxEntitlementRequired -> setOf(PurgeScope.CAPABILITY)
        // A KRX false edge is applied ahead of the premium branch, whatever that branch does.
        is EntitlementsOutcome.StableActive -> setOf(PurgeScope.CAPABILITY).takeUnless { krxVisible }
        is EntitlementsOutcome.Pending -> setOf(PurgeScope.CAPABILITY).takeUnless { krxVisible }
        is EntitlementsOutcome.Indeterminate -> null
    }

    /**
     * [state] and [krx] from the reducer's view and the candidates' holds. A user hold shows a grant as NoGrant and leaves
     * any other state as it is; a capability hold, or a state that grants nothing, hides KRX. Called on every write of
     * either. Callers hold [mutex].
     */
    private fun republishEffectiveLocked() {
        val decided = _state.value
        _effectiveState.value =
            if (decided.state.grantsPremiumRuntime && lossCandidates.any { PurgeScope.USER in it.axes }) {
                decided.copy(state = PremiumAccessState.NoGrant)
            } else {
                decided
            }
        // KRX opens only under a premium runtime (PremiumAccessReducer). Following that here too keeps a boundary — NoGrant
        // written before HIDDEN — from showing KRX again in between once it has dropped the holds.
        _effectiveKrx.value =
            if (!decided.state.grantsPremiumRuntime || lossCandidates.any { PurgeScope.CAPABILITY in it.axes }) {
                KrxCapabilityState.HIDDEN
            } else {
                _krx.value
            }
    }

    /**
     * A decision's record read threw (S1r-2c §2.1). A loss answer that is not already known stale is held: its axes are
     * withheld from [state] and [krx], and candidate recovery owns reading the record again. Nothing the reducer reads moves
     * — no state, seal, epoch, purge, demand or authentication order. A floor the answer stated is recorded once (§2.5).
     *
     * An answer that is not a loss rethrows [failed], as before (§2.7). The caller's own cancellation goes through after
     * the hand-over; a CancellationException the store threw while the caller is still active is a failed read like any
     * other. Callers hold [mutex].
     */
    private suspend fun holdUnreadableLossLocked(
        outcome: EntitlementsOutcome,
        provenance: CandidateProvenance,
        failed: Exception
    ) {
        val axes = outcome.lossAxes() ?: throw failed
        if (!knownStaleWithoutRecordLocked(provenance, probeEpoch)) {
            val floor = outcome.statedRetryFloorMillis()
            lossCandidates += LossCandidate(
                holdId = ++nextHoldId,
                binding = probeEpoch,
                axes = axes,
                outcome = outcome,
                provenance = provenance,
                floorNotBefore = floor?.let { clock.elapsedMillis() + it }
            )
            republishEffectiveLocked()
            kickCandidateRecoveryLocked()
            // After the hand-over: a timer that fired into this query is the very job recording a floor cancels.
            if (floor != null) withContext(NonCancellable) { schedule.recordFloorWithoutArming(floor) }
        }
        currentCoroutineContext().ensureActive()
    }

    /**
     * What can be told stale without the record: the generation or binding moved, the answer is for another owner than the
     * query's namespace, for another session than the binding stood on, or a live session is known and is someone else.
     * A live identity that cannot be read says nothing. Callers hold [mutex].
     */
    private fun knownStaleWithoutRecordLocked(provenance: CandidateProvenance, binding: Long): Boolean {
        if (binding != probeEpoch) return true
        val live = liveFence()?.let { EntitlementsIdentity(it.uid, it.authGeneration) }
        return when (provenance) {
            is CandidateProvenance.Query -> {
                val started = provenance.started
                val answeredAs = provenance.answeredAs
                started.generation != decisionGeneration ||
                    started.binding != probeEpoch ||
                    (answeredAs != null && (
                        answeredAs.ownerUid != started.fence.ownerUid ||
                            (started.boundIdentity != null && answeredAs != started.boundIdentity) ||
                            (live != null && live != answeredAs)
                        ))
            }
            is CandidateProvenance.Topic -> {
                val context = provenance.context
                issuedTopicGrant?.takeIf { it.token == provenance.grant }?.context != context ||
                    context.generation != decisionGeneration ||
                    boundIdentityLocked() != context.identity ||
                    (live != null && live != context.identity)
            }
        }
    }

    private fun kickCandidateRecoveryLocked() {
        if (candidateRecovery != null) return
        candidateRecovery = scope.launch {
            runRecovery(
                roundLocked = { candidateRecoveryRoundLocked() },
                delayMillis = { recoveryDelayMillis(candidateRecoveryAttempts) },
                clearIfMineLocked = { self -> if (candidateRecovery === self) candidateRecovery = null }
            )
        }
    }

    /**
     * One candidate recovery round (S1r-2c §2.3), oldest candidate first, each checked against a record read for it.
     * Waits while access is not admitted. A failed read, or a live identity that cannot be read, keeps the candidate and
     * ends the round; stale and resolved candidates release their own hold only. Ends the run when none is left.
     */
    private suspend fun candidateRecoveryRoundLocked(): RecoveryNext {
        while (true) {
            val candidate = lossCandidates.firstOrNull() ?: run {
                candidateRecovery = null
                candidateRecoveryAttempts = 0
                return RecoveryNext.Done
            }
            if (!accessAdmittedLocked()) {
                return RecoveryNext.AwaitAdmission(attemptSignal.value, _persistenceSignal.value)
            }
            if (knownStaleWithoutRecordLocked(candidate.provenance, candidate.binding)) {
                releaseCandidateLocked(candidate)
                continue
            }
            val check = try {
                checkCandidateLocked(candidate, store.load())
            } catch (failed: Exception) {
                // A CancellationException thrown by the store or the source is a failed round; this run being cancelled is not.
                currentCoroutineContext().ensureActive()
                candidateRecoveryAttempts += 1
                return RecoveryNext.Retry
            }
            when (check) {
                CandidateCheck.Stale -> releaseCandidateLocked(candidate)
                CandidateCheck.IdentityUnknown -> {
                    candidateRecoveryAttempts += 1
                    return RecoveryNext.Retry
                }
                is CandidateCheck.Valid -> resolveCandidateLocked(candidate, check.record)
            }
        }
    }

    /** The checks the original path would have made, against [record] just read. Callers hold [mutex]. */
    private suspend fun checkCandidateLocked(candidate: LossCandidate, record: AccessEpochRecord): CandidateCheck {
        when (val provenance = candidate.provenance) {
            is CandidateProvenance.Query -> {
                val started = provenance.started
                if (started.generation != decisionGeneration || record.fence() != started.fence) return CandidateCheck.Stale
                val answeredAs = provenance.answeredAs ?: return CandidateCheck.Valid(record)
                if (answeredAs.ownerUid != record.ownerUid) return CandidateCheck.Stale
                if (started.boundIdentity != null && answeredAs != started.boundIdentity) return CandidateCheck.Stale
                return when (source.currentIdentity()) {
                    answeredAs -> CandidateCheck.Valid(record)
                    null -> CandidateCheck.IdentityUnknown
                    else -> CandidateCheck.Stale
                }
            }
            is CandidateProvenance.Topic -> {
                val context = provenance.context
                if (issuedTopicGrant?.takeIf { it.token == provenance.grant }?.context != context) return CandidateCheck.Stale
                if (context.generation != decisionGeneration || record.fence() != context.access) return CandidateCheck.Stale
                if (boundIdentityLocked() != context.identity) return CandidateCheck.Stale
                return when (source.currentIdentity()) {
                    context.identity -> CandidateCheck.Valid(record)
                    null -> CandidateCheck.IdentityUnknown
                    else -> CandidateCheck.Stale
                }
            }
        }
    }

    /**
     * Applies a candidate that checked out, then releases its hold. The loss is published inside [decideLocked]; what follows
     * it — the schedule, handled the way the candidate's own path handles a decided answer (§2.5, §2.6), and the release — is
     * handed to [decideLocked] so a cancellation after publishing loses neither. Callers hold [mutex].
     */
    private suspend fun resolveCandidateLocked(candidate: LossCandidate, record: AccessEpochRecord) {
        val provenance = candidate.provenance
        val intent = when (provenance) {
            is CandidateProvenance.Query -> provenance.started.intent
            is CandidateProvenance.Topic -> RefreshIntent.FORCE_ENTITLEMENTS
        }
        decideLocked(record, intent, candidate.outcome) { decision ->
            try {
                val recheck = decision.recheck?.remainingOf(candidate)
                when (provenance) {
                    is CandidateProvenance.Query -> settleDemandLocked(provenance.started, candidate.outcome, recheck)
                    // Not a query's answer, so it answers no demand; a re-check it asks for is a new requirement (S1r-2a §2.1).
                    is CandidateProvenance.Topic ->
                        if (recheck != null) {
                            raiseDemandLocked(recheck.intent, independent = true)
                            armRecheckLocked(recheck)
                        } else if (recheckDemand == null) {
                            schedule.cancel()
                        }
                }
                ensureDemandArmedLocked()
            } finally {
                releaseCandidateLocked(candidate)
            }
        }
    }

    /** A stated floor is not restarted by a late resolution: only what is left of it is asked for. */
    private fun RecheckRequest.remainingOf(candidate: LossCandidate): RecheckRequest {
        val notBefore = candidate.floorNotBefore ?: return this
        return copy(minDelayMillis = (notBefore - clock.elapsedMillis()).coerceAtLeast(0L))
    }

    private fun releaseCandidateLocked(candidate: LossCandidate) {
        if (lossCandidates.remove(candidate)) republishEffectiveLocked()
    }

    private sealed interface RecoveryNext {
        data object Done : RecoveryNext
        data object Retry : RecoveryNext
        data class AwaitAdmission(val attempt: AttemptSignal, val pending: PendingPersistence?) : RecoveryNext
    }

    /**
     * Passes every confirmed record to [lossSeals] (S1r-2b §5).
     *
     * A load that returns is confirmed ([AccessEpochStore.load]). A mutation's return is observed only when no store call
     * has failed or been cancelled since the last confirmed load: after one, a no-op edit's normal return does not
     * establish anything, and the next load decides. [before] handed to the ledger is the last confirmed record at the
     * start of the call.
     */
    private inner class ObservedStore(private val delegate: AccessEpochStore) : AccessEpochStore {
        private var unconfirmed = false

        private suspend fun call(op: StoreOp, block: suspend () -> AccessEpochRecord): AccessEpochRecord {
            val before = lossSeals.lastConfirmed
            val result = try {
                block()
            } catch (failed: Throwable) {
                unconfirmed = true
                throw failed
            }
            if (op == StoreOp.LOAD) unconfirmed = false
            if (!unconfirmed) {
                // Something retired here was handed to a journal that still needs cleaning, whoever's call this was —
                // an identity task that completes on a failed purge does not end that.
                if (lossSeals.observe(op, before, result)) lossCleanupOwed = true
                // New evidence can make a seal recovery had given up on actionable again. A waiting retry keeps its deadline.
                if (lossRecovery == null && (lossCleanupOwed || lossRecoveryHasWorkLocked())) kickLossRecoveryLocked()
            }
            return result
        }

        override suspend fun load() = call(StoreOp.LOAD) { delegate.load() }
        override suspend fun bindOwner(uid: String) = call(StoreOp.BIND_OWNER) { delegate.bindOwner(uid) }
        override suspend fun signOut() = call(StoreOp.SIGN_OUT) { delegate.signOut() }
        override suspend fun retireUnverifiedStart() =
            call(StoreOp.RETIRE_UNVERIFIED_START) { delegate.retireUnverifiedStart() }
        override suspend fun beginSignOut(uid: String) = call(StoreOp.BEGIN_SIGN_OUT) { delegate.beginSignOut(uid) }
        override suspend fun beginRotation(rotateUser: Boolean, rotateKrx: Boolean) =
            call(StoreOp.BEGIN_ROTATION) { delegate.beginRotation(rotateUser, rotateKrx) }
        override suspend fun completePurges(completed: Collection<PendingPurge>) =
            call(StoreOp.COMPLETE_PURGES) { delegate.completePurges(completed) }
        override suspend fun journalRetired(obligation: LossObligation) =
            call(StoreOp.JOURNAL_RETIRED) { delegate.journalRetired(obligation) }
        override suspend fun markMayContainData(premium: Boolean, krx: Boolean) =
            call(StoreOp.MARK_MAY_CONTAIN_DATA) { delegate.markMayContainData(premium, krx) }
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

/** WebSocket topic rejection codes (`app/topic_wire.py`) that are about access. Not wired to a session yet. */
enum class TopicRejection {
    PREMIUM_REQUIRED,
    KRX_ENTITLEMENT_REQUIRED;

    companion object {
        /**
         * One acknowledgement's refusals as one access event, independent of order and repeats.
         *
         * Premium wins. Applying a KRX refusal first could rotate the capability epoch and turn the
         * premium refusal that came with it into a stale one; a premium refusal already covers both
         * axes. Reasons that are not about access give null.
         */
        fun of(reasons: Collection<TopicRejectionReason>): TopicRejection? = when {
            TopicRejectionReason.PREMIUM_REQUIRED in reasons -> PREMIUM_REQUIRED
            TopicRejectionReason.KRX_ENTITLEMENT_REQUIRED in reasons -> KRX_ENTITLEMENT_REQUIRED
            else -> null
        }
    }
}

/** What a topic grant was issued for: the binding, the record's fence, and the decision generation. */
private data class TopicGrantContext(
    val identity: EntitlementsIdentity,
    val access: AccessFence,
    val generation: Long
)

private data class IssuedTopicGrant(val token: TopicGrantToken, val context: TopicGrantContext)
