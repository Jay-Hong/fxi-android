package com.jay.fxi.data.entitlements

/** Durable facts the reducer needs beyond the sealed state itself. */
data class AccessSnapshot(
    val state: PremiumAccessState = PremiumAccessState.NoGrant,
    val krx: KrxCapabilityState = KrxCapabilityState.HIDDEN,
    /** A protected namespace that may still hold premium data written under an older epoch. */
    val mayContainPremiumData: Boolean = false,
    /** A capability namespace that may still hold KRX data. */
    val mayContainKrxData: Boolean = false,
    /** A user-scope rotation was persisted but its purge is not journalled complete. */
    val pendingUserPurge: Boolean = false,
    /** A capability-scope rotation was persisted but its purge is not journalled complete. */
    val pendingCapabilityPurge: Boolean = false
)

/**
 * The single implementation of the D23 reducer transition table.
 *
 * Pure by construction: no I/O, no clock, no coroutines. Side effects are *declared* in
 * [AccessDecision.effects] and executed elsewhere, so the table itself stays directly testable.
 *
 * Four rules are easy to state loosely and get wrong, so they are named here:
 *  1. Only [RefreshIntent.FORCE_PREMIUM] can create a grant. A cached ACTIVE from an ordinary or
 *     `.forceEntitlements` query must escalate instead of granting.
 *  2. A stable `premium_active=false` is [PremiumAccessState.FreeConfirmed]; a typed
 *     `premium_required` is [PremiumAccessState.Rejected]. They are different states.
 *  3. `premium_pending=true` and every indeterminate answer preserve an existing
 *     [PremiumAccessState.PremiumConfirmed], with no time expiry.
 *  4. A fail-closed `krx_visible=false` applies *before* the pending branch.
 */
object PremiumAccessReducer {

    /** Base floor for a typed backoff when the server supplied no `Retry-After`. */
    const val DEFAULT_BACKOFF_FLOOR_MILLIS: Long = 5_000L

    fun reduce(
        current: AccessSnapshot,
        intent: RefreshIntent,
        outcome: EntitlementsOutcome
    ): AccessDecision {
        // A set, not a list: the KRX false edge and an authoritative premium loss can both call for
        // the same capability teardown, and declaring it twice would read as two rotations.
        val effects = linkedSetOf<AccessEffect>()

        // (4) Fail-closed KRX first: a false edge hides and purges regardless of the premium branch.
        var krx = current.krx
        val envelopeKrxVisible = outcome.krxVisible()
        if (envelopeKrxVisible == false) {
            krx = applyKrxFalseEdge(current, effects)
        }

        val nextState = when (outcome) {
            is EntitlementsOutcome.StableActive ->
                reduceStableActive(current, intent, effects)

            is EntitlementsOutcome.StableInactive -> {
                // A premium loss takes the capability with it: KRX is a premium-gated surface.
                krx = KrxCapabilityState.HIDDEN
                reduceAuthoritativeLoss(current, PremiumAccessState.FreeConfirmed, effects)
            }

            is EntitlementsOutcome.PremiumRequired -> {
                krx = KrxCapabilityState.HIDDEN
                reduceAuthoritativeLoss(current, PremiumAccessState.Rejected, effects)
            }

            is EntitlementsOutcome.KrxEntitlementRequired -> {
                // Rejects the capability only; the premium axis is untouched.
                if (krx == KrxCapabilityState.VISIBLE || current.mayContainKrxData) {
                    krx = applyKrxFalseEdge(current, effects)
                }
                current.state
            }

            is EntitlementsOutcome.Pending,
            is EntitlementsOutcome.Indeterminate ->
                // (3) An existing grant survives; otherwise no grant is created.
                if (current.state.grantsPremiumRuntime) {
                    current.state
                } else {
                    PremiumAccessState.Pending(
                        continuityEligible = current.mayContainPremiumData || current.pendingUserPurge
                    )
                }
        }

        // A fresh `krx_visible=true` only opens while the premium runtime is actually open.
        if (envelopeKrxVisible == true && nextState.grantsPremiumRuntime) {
            krx = KrxCapabilityState.VISIBLE
        }

        return AccessDecision(
            state = nextState,
            krx = krx,
            effects = effects.toList(),
            recheck = recheckFor(current, intent, outcome, nextState)
        )
    }

    private fun reduceStableActive(
        current: AccessSnapshot,
        intent: RefreshIntent,
        effects: MutableSet<AccessEffect>
    ): PremiumAccessState {
        if (intent.canGrantPremium) {
            // A grant does not rotate the user epoch; it reopens the current namespace.
            return PremiumAccessState.PremiumConfirmed
        }
        // (1) Cached ACTIVE: never a grant.
        if (current.state.grantsPremiumRuntime) return current.state
        effects += AccessEffect.StartForcePremiumSingleFlight
        return current.state
    }

    /**
     * Shared tail for the two authoritative losses. Teardown runs only when something protected
     * could still exist, so a repeat answer does not re-rotate.
     */
    private fun reduceAuthoritativeLoss(
        current: AccessSnapshot,
        target: PremiumAccessState,
        effects: MutableSet<AccessEffect>
    ): PremiumAccessState {
        val hadProtected = current.state.grantsPremiumRuntime || current.mayContainPremiumData
        if (hadProtected) {
            effects += AccessEffect.RotateUserEpoch
            effects += AccessEffect.RotateKrxEpoch
            effects += AccessEffect.PurgeUserScope
            effects += AccessEffect.PurgeCapabilityScope
            effects += AccessEffect.PushDelete
        }
        return target
    }

    /**
     * Edge-triggered KRX hide.
     *
     * DoD: "반복 pending `krx_visible=false`에서 KRX epoch rotate·journal write·purge는 최초
     * edge에만 1회". A repeat false must therefore not mint another epoch. That matters most when
     * a purge is stuck — with a deferred purger, rotating on every poll would spin the namespace
     * id forever while nothing is actually cleaned up.
     *
     * An outstanding journal still gets its purge retried; resuming is not a new edge.
     */
    private fun applyKrxFalseEdge(
        current: AccessSnapshot,
        effects: MutableSet<AccessEffect>
    ): KrxCapabilityState {
        // The marker is the whole test, because a rotation absorbs it: after one teardown the
        // live marker is false, so a repeat false is not an edge. The journal must play no part
        // here — it is global while markers are per-namespace, so consulting it would let another
        // user's stuck entry suppress a teardown this namespace genuinely needs.
        val isFirstEdge =
            current.krx == KrxCapabilityState.VISIBLE || current.mayContainKrxData
        when {
            isFirstEdge -> {
                effects += AccessEffect.RotateKrxEpoch
                effects += AccessEffect.PurgeCapabilityScope
            }
            current.pendingCapabilityPurge -> effects += AccessEffect.PurgeCapabilityScope
        }
        return KrxCapabilityState.HIDDEN
    }

    private fun recheckFor(
        current: AccessSnapshot,
        intent: RefreshIntent,
        outcome: EntitlementsOutcome,
        nextState: PremiumAccessState
    ): RecheckRequest? = when (outcome) {
        is EntitlementsOutcome.StableActive ->
            // Escalation is itself a recheck; a real grant needs nothing further.
            if (intent.canGrantPremium || nextState.grantsPremiumRuntime) {
                null
            } else {
                RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L)
            }

        is EntitlementsOutcome.StableInactive,
        is EntitlementsOutcome.PremiumRequired -> null

        is EntitlementsOutcome.KrxEntitlementRequired ->
            RecheckRequest(RefreshIntent.FORCE_ENTITLEMENTS, minDelayMillis = 0L)

        // Preserve the originating mode: a premium decision pending must not weaken to ordinary.
        is EntitlementsOutcome.Pending ->
            RecheckRequest(
                intent,
                minDelayMillis = outcome.statedRetryFloorMillis()
                    ?: DEFAULT_BACKOFF_FLOOR_MILLIS
            )

        is EntitlementsOutcome.Indeterminate -> when (outcome.reason) {
            // Auth recovery owns this; re-asking entitlements would just fail again.
            IndeterminateReason.AUTHENTICATION -> null
            IndeterminateReason.UNTYPED_FORBIDDEN ->
                RecheckRequest(RefreshIntent.FORCE_PREMIUM, minDelayMillis = 0L)
            IndeterminateReason.TRANSIENT, IndeterminateReason.DECODE ->
                RecheckRequest(
                    intent,
                    minDelayMillis = outcome.statedRetryFloorMillis()
                        ?: DEFAULT_BACKOFF_FLOOR_MILLIS
                )
        }
    }

    private fun EntitlementsOutcome.krxVisible(): Boolean? = when (this) {
        is EntitlementsOutcome.StableActive -> krxVisible
        is EntitlementsOutcome.StableInactive -> krxVisible
        is EntitlementsOutcome.Pending -> krxVisible
        // Typed and indeterminate answers carry no envelope value.
        EntitlementsOutcome.PremiumRequired,
        EntitlementsOutcome.KrxEntitlementRequired,
        is EntitlementsOutcome.Indeterminate -> null
    }
}
