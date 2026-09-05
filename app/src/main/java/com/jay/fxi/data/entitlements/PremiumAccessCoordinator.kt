package com.jay.fxi.data.entitlements

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    scope: CoroutineScope,
    clock: RecheckClock
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<PremiumAccessState>(PremiumAccessState.NoGrant)
    val state: StateFlow<PremiumAccessState> = _state.asStateFlow()

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

    private val schedule =
        RecheckSchedule(scope, clock) { intent, origin -> refresh(intent, origin) }

    /**
     * Binds a signed-in owner and resumes any purge a previous process left journalled.
     *
     * Resuming first matters: the markers a resumed purge clears are inputs to the reducer.
     */
    suspend fun onOwnerChanged(uid: String) = mutex.withLock {
        decisionGeneration += 1
        clearForcePremiumLocked()
        schedule.cancel()
        store.bindOwner(uid)
        _state.value = PremiumAccessState.NoGrant
        _krx.value = KrxCapabilityState.HIDDEN
        _lastEffects.value = emptyList()
        resumePendingPurgesLocked()
    }

    /**
     * Sign-out is a teardown.
     *
     * The plan lists logout with fresh-false and typed reject as an event that rotates the
     * namespace and journals a purge. Clearing only in-memory state would let the next sign-in of
     * the same uid inherit protected data that was never re-authorised.
     */
    suspend fun onSignedOut() = mutex.withLock {
        decisionGeneration += 1
        clearForcePremiumLocked()
        schedule.cancel()
        store.signOut()
        _state.value = PremiumAccessState.NoGrant
        _krx.value = KrxCapabilityState.HIDDEN
        resumePendingPurgesLocked()
    }

    /** Re-runs purges a previous process journalled but did not finish. */
    suspend fun resumePendingPurges() = mutex.withLock { resumePendingPurgesLocked() }

    suspend fun refresh(
        intent: RefreshIntent,
        origin: QueryOrigin = QueryOrigin.CALLER
    ) {
        if (!schedule.shouldQuery(intent, origin)) {
            // Refused by the absolute floor. The wait is honoured, but a purchase or restore that
            // arrived during it must still make the eventual retry strong enough to grant.
            schedule.upgradePendingIntent(intent)
            return
        }

        var token: Long? = null
        val started: StartedQuery = mutex.withLock {
            val record = store.load()
            if (intent == RefreshIntent.FORCE_PREMIUM) {
                // Owner-bound, not a global flag: an escalation still running for a signed-out
                // user must not suppress the new user's first query.
                if (forcePremiumToken != null && forcePremiumOwner == record.ownerUid) return
                token = ++nextRequestToken
                forcePremiumToken = token
                forcePremiumOwner = record.ownerUid
            }
            StartedQuery(record.fence(), decisionGeneration)
        }
        schedule.recordQueryStarted()

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
            StartedQuery(store.load().fence(), decisionGeneration)
        }
        val outcome = when (code) {
            TopicRejection.PREMIUM_REQUIRED -> EntitlementsOutcome.PremiumRequired
            TopicRejection.KRX_ENTITLEMENT_REQUIRED -> EntitlementsOutcome.KrxEntitlementRequired
        }
        // The rejection concerns the session the coordinator already owns, and the namespace fence
        // covers that. There is no separate transport identity to match against.
        apply(RefreshIntent.FORCE_ENTITLEMENTS, outcome, answeredAs = null, started = started)
    }

    private fun clearForcePremiumLocked() {
        forcePremiumToken = null
        forcePremiumOwner = null
    }

    private data class StartedQuery(val fence: AccessFence, val generation: Long)

    private suspend fun apply(
        intent: RefreshIntent,
        outcome: EntitlementsOutcome,
        /** Transport identity the answer came from, or null when the input had none. */
        answeredAs: EntitlementsIdentity?,
        started: StartedQuery
    ) {
        // Null means the answer was held back rather than decided: see the auth-session check.
        val decision: AccessDecision? = mutex.withLock {
            val record = store.load()
            // Four independent staleness checks, because each catches something the others miss:
            //  - generation: an authoritative loss or reset that rotated nothing,
            //  - fence: a namespace that has since been retired,
            //  - owner: an answer the transport actually fetched as somebody else,
            //  - auth session: the same uid on a session the transport has since superseded.
            if (started.generation != decisionGeneration) return
            if (record.fence() != started.fence) return
            if (answeredAs == null) {
                decideLocked(record, intent, outcome)
            } else {
                if (answeredAs.ownerUid != record.ownerUid) return
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
        }

        if (decision == null) {
            // Held back, not decided. The answer stays out of the state, but the query is retried
            // at its own strength so an eventual identity still reaches the same conclusion.
            //
            // The floor is the one the answer stated, falling back to the same default the reducer
            // uses for an outcome it cannot settle. Zero re-queried immediately inside a window the
            // server had explicitly asked us to wait out. (The schedule's own absolute floor from
            // earlier answers is preserved separately, by its `maxOf`.)
            schedule.schedule(
                RecheckRequest(
                    intent,
                    minDelayMillis = outcome.statedRetryFloorMillis()
                        ?: PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS
                )
            )
            return
        }

        val recheck = decision.recheck
        if (recheck == null) schedule.cancel() else schedule.schedule(recheck)
    }

    /** Reduce, persist, publish. Callers must hold [mutex] — hence the `Locked` suffix. */
    private suspend fun decideLocked(
        record: AccessEpochRecord,
        intent: RefreshIntent,
        outcome: EntitlementsOutcome
    ): AccessDecision {
        val decision = PremiumAccessReducer.reduce(
            current = record.toSnapshotFacts(_state.value, _krx.value),
            intent = intent,
            outcome = outcome
        )

        // I4: persist the new id and the purge journal *before* the transition is observable.
        // Publishing first would leave a visible Rejected with a namespace still live if the
        // write failed.
        persistRotationsLocked(decision.effects)

        if (outcome.isAuthoritativeLoss()) decisionGeneration += 1

        _state.value = decision.state
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
     */
    private suspend fun resumePendingPurgesLocked() {
        val record = store.load()
        if (record.pendingPurges.isEmpty()) return
        val completed = record.pendingPurges.filter { entry ->
            val namespace = PurgeNamespace(
                ownerUid = entry.ownerUid,
                currentUserAccessEpoch = record.userAccessEpoch,
                currentKrxCapabilityEpoch = record.krxCapabilityEpoch,
                pending = entry
            )
            entry.scopes.map { scope ->
                when (scope) {
                    PurgeScope.USER -> userPurger.purgeUserScope(namespace)
                    PurgeScope.CAPABILITY -> capabilityPurger.purgeCapabilityScope(namespace)
                }
            }.all { it == PurgeResult.Completed }
        }
        if (completed.isNotEmpty()) store.completePurges(completed)
    }
}

/** WebSocket topic rejection codes (`app/topic_wire.py`). Fed in by a later WS slice. */
enum class TopicRejection { PREMIUM_REQUIRED, KRX_ENTITLEMENT_REQUIRED }
