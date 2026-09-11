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
    clock: RecheckClock,
    private val jitter: ProbeJitter = ProbeJitter.Default,
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

    /** False while the open attempt has an edit in flight. Identity events wait on it. */
    private val identityEventsAdmitted = MutableStateFlow(true)

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
     * Returns null, having done nothing, while an open sign-out has an edit in flight. The caller
     * keeps the event and tries again once [awaitIdentityEventsAdmitted] returns. It also returns
     * null when its own edit fails while an attempt is open: see [identityEditLocked].
     */
    suspend fun onIdentityChanged(identity: AuthIdentityFence): AccessDecisionGeneration? = mutex.withLock {
        // Checked under the same lock as the edit below: a gate seen open before this lock was
        // taken can have closed since.
        if (!SignOutAttemptPolicy.admitsIdentityEvents(attempt)) return@withLock null
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
        // disk work, not after it.
        _state.value =
            OwnedPremiumAccess(identity.uid, identity.authGeneration, PremiumAccessState.NoGrant)
        schedule.cancel(preserveServerFloor = true)
        val held = { HeldIdentityEvent.Bind(checkNotNull(open).ticket, identity) }
        val before = identityEditLocked(open, PendingEdit.READ, before = null, held) { store.load() }
            ?: return@withLock null
        val after = identityEditLocked(open, PendingEdit.BIND_OWNER, before, held) { store.bindOwner(identity.uid) }
            ?: return@withLock null
        completedBinding = identity
        heldEvent = null
        // Judged from the record the bind returned, before any purge below can clear its receipt.
        open?.let { current ->
            setAttemptLocked(
                SignOutAttemptPolicy.identityBound(
                    current,
                    retiredAttemptNamespace = SignOutAttemptPolicy.retired(before, after, current.fence.uid)
                )
            )
        }
        _krx.value = KrxCapabilityState.HIDDEN
        _lastEffects.value = emptyList()
        cleanupLocked()
        // Handed back so the caller can bind its follow-up query to *this* binding. A query queued
        // behind a sign-out would otherwise start under the next generation and revive a session
        // that is gone.
        AccessDecisionGeneration(decisionGeneration)
    }

    /**
     * Sign-out is a teardown.
     *
     * The plan lists logout with fresh-false and typed reject as an event that rotates the
     * namespace and journals a purge. Clearing only in-memory state would let the next sign-in of
     * the same uid inherit protected data that was never re-authorised.
     */
    suspend fun onSignedOut(ended: AuthIdentityFence): Boolean = mutex.withLock {
        // Same contract as [onIdentityChanged]: held, untouched, while an edit is in flight.
        if (!SignOutAttemptPolicy.admitsIdentityEvents(attempt)) return@withLock false
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
        _state.value = OwnedPremiumAccess(null, null, PremiumAccessState.NoGrant)
        schedule.cancel(preserveServerFloor = true)
        // A retried end whose rotation a read-back found landed uses that result: rotating again
        // would take down the namespace minted by the first rotation.
        val rotation = (retry as? HeldIdentityEvent.End)?.receipt ?: run {
            val held = { HeldIdentityEvent.End(checkNotNull(open).ticket, ended) }
            val before = identityEditLocked(open, PendingEdit.READ, before = null, held) { store.load() }
                ?: return@withLock false
            // Targeted: `store.signOut()` rotates whoever the record names, so it only runs when that
            // is the uid whose session ended.
            when (SignOutAttemptPolicy.planEnd(ended, before.ownerUid)) {
                SignOutAttemptPolicy.EndPlan.ROTATE -> EditResult.Landed(
                    identityEditLocked(open, PendingEdit.END, before, held) { store.signOut() }
                        ?: return@withLock false
                )
                SignOutAttemptPolicy.EndPlan.LEAVE_DISK -> EditResult.NotAttempted
            }
        }
        completedBinding = null
        heldEvent = null
        _krx.value = KrxCapabilityState.HIDDEN
        // What this end did is recorded before cleanup runs, because cleanup can fail. Only
        // finishing the attempt — which releases the seal — waits for it.
        val finishing = open?.let { current ->
            val next = SignOutAttemptPolicy.afterEnd(current, ended, rotation, liveFence())
            // Null only when the rotation landed, so the provisional state says exactly that.
            setAttemptLocked(next ?: SignOutAttempt.Recovering(current.ticket, current.fence, TeardownKnowledge.LANDED))
            next == null
        } ?: false
        val cleanupOk = cleanupLocked()
        // Read again after the last suspension: somebody may have signed in during cleanup.
        if (finishing && cleanupOk && liveFence() == null) setAttemptLocked(null)
        true
    }

    /**
     * Starts an app sign-out for [fence]: seals access, then persists the intent.
     *
     * The order is fixed — live check, seal, disk. A request already stale at entry does not seal
     * or write. Sealing publishes NoGrant and HIDDEN and closes this coordinator's protected
     * access entry points before disk work.
     */
    internal suspend fun prepareSignOut(fence: AuthIdentityFence): SignOutStart = mutex.withLock {
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
     * One disk step of an identity event.
     *
     * With no attempt open a failure propagates as it always has. With one open, the step's outcome
     * is recorded as unresolved and the event is held — null tells the caller to hand it back. No
     * read-back here: a known "not attempted" would be retried at once by the FIFO, so the retry waits
     * for [resolvePendingEdit] instead. Nothing after the step runs, so no purge can erase its receipt.
     */
    private suspend fun <T : Any> identityEditLocked(
        open: SignOutAttempt?,
        edit: PendingEdit,
        before: AccessEpochRecord?,
        held: () -> HeldIdentityEvent,
        step: suspend () -> T
    ): T? = try {
        step()
    } catch (failed: Exception) {
        if (open == null) throw failed
        setAttemptLocked(SignOutAttemptPolicy.editFailed(open, edit, before))
        heldEvent = held()
        // A store can throw CancellationException of its own. Only this caller's cancellation ends here.
        currentCoroutineContext().ensureActive()
        null
    }

    /**
     * Resumes purges after an edit that already landed.
     *
     * With an attempt open, a failure — reported or thrown — is a cleanup failure: it keeps the seal
     * and never becomes that edit's failure. With none open it propagates as it always has.
     */
    private suspend fun cleanupLocked(): Boolean {
        if (attempt == null) return resumePendingPurgesLocked()
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
     * landed end leaves its result for that retry.
     */
    internal suspend fun resolvePendingEdit(ticket: SignOutTicket): EditResolution = mutex.withLock {
        val open = attempt as? SignOutAttempt.Unresolved
        if (open == null || open.ticket != ticket) return@withLock EditResolution.NOT_PENDING
        val readBack = try {
            store.load()
        } catch (failed: Exception) {
            currentCoroutineContext().ensureActive()
            return@withLock EditResolution.STILL_UNKNOWN
        }
        when (val resolution = SignOutAttemptPolicy.resolve(open, readBack)) {
            is SignOutAttemptPolicy.Resolution.Resolved -> {
                resolution.endReceipt?.let { receipt ->
                    val pending = heldEvent
                    check(pending is HeldIdentityEvent.End && pending.ticket == ticket) {
                        "a landed end without the end event it belongs to: $pending"
                    }
                    heldEvent = pending.copy(receipt = receipt)
                }
                setAttemptLocked(resolution.next)
                EditResolution.RESOLVED
            }
            SignOutAttemptPolicy.Resolution.Inconsistent -> EditResolution.INCONSISTENT
        }
    }

    /** Suspends while the open sign-out has an edit in flight. The identity FIFO waits here. */
    suspend fun awaitIdentityEventsAdmitted() {
        identityEventsAdmitted.first { it }
    }

    private fun setAttemptLocked(next: SignOutAttempt?) {
        attempt = next
        identityEventsAdmitted.value = SignOutAttemptPolicy.admitsIdentityEvents(next)
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
        // A **live credential**, not persisted ownership. The record keeps its uid across a
        // sign-out so the purge journal can still name whose namespace it is cleaning, so
        // `ownerUid != null` stays true for a signed-out process. Gating on it alone let a
        // billing callback delivered after logout start querying: with no credential the
        // transport throws, that classifies TRANSIENT, and the reducer arms a recheck — teardown
        // undone by a retry ladder that outlives the probe window.
        val run = mutex.withLock {
            // Inside the lock, not before it. Read outside, a sign-out could complete between the
            // read and the lock: the record keeps its uid, so the stale identity would still match
            // and the probe would adopt the *post*-sign-out epoch as its own — passing every later
            // fence. Both facts are then established under one critical section.
            if (!SignOutAttemptPolicy.admitsAccessQueries(attempt)) return
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
            if (!SignOutAttemptPolicy.admitsAccessQueries(attempt)) return
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
            if (!SignOutAttemptPolicy.admitsAccessQueries(attempt)) return
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
            if (!SignOutAttemptPolicy.admitsAccessQueries(attempt)) return
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
