package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence

/**
 * D23 vocabulary: reducer inputs, sealed access state, and declared side effects.
 *
 * The canonical transition table is `ANDROID_V2_PLAN.md` §5.1 "D23 reducer 전이 계약".
 * [PremiumAccessReducer] is the only place that implements it.
 */

/** Why a refresh was requested. The mode is part of the reducer input, not just transport. */
/**
 * An access decision together with the identity it belongs to.
 *
 * [uid] is null only before any owner has been bound, or after a sign-out. A consumer that reads
 * this alongside a separate auth signal must compare the two: the flows move independently, and the
 * window between them is exactly where a previous user's grant would otherwise be honoured.
 */
data class OwnedPremiumAccess(
    val uid: String? = null,
    /**
     * The auth generation the decision was made under.
     *
     * The uid alone cannot tell one session from the next: a sign-out followed by a sign-in of the
     * *same* user rotates this and nothing else. Without it a grant left over from the previous
     * session matches the new one and opens the premium surface on an entitlement that was torn
     * down — and no publishing order closes that on its own, because a reader can observe auth
     * before the coordinator has even dequeued the identity event.
     */
    val authGeneration: Long? = null,
    val state: PremiumAccessState = PremiumAccessState.NoGrant
)

/**
 * Whether the server has confirmed premium for **exactly this session**.
 *
 * Bound to the generation as well as the uid. Checking the state alone let one account's grant
 * authorise another account's push registration; checking the uid alone still let a *previous
 * session of the same account* do it. Extracted so every entry point that can register a device
 * shares one answer, and so the decision is testable without RevenueCat, Firebase or Android.
 */
fun OwnedPremiumAccess.confirmsPremiumFor(session: AuthIdentityFence?): Boolean =
    session != null &&
        uid == session.uid &&
        authGeneration == session.authGeneration &&
        state == PremiumAccessState.PremiumConfirmed

enum class RefreshIntent {
    /** Ordinary foreground refresh. Client-side debounce applies. */
    IF_STALE,

    /** Bypasses the client debounce only. Used for KRX-only rejections. */
    FORCE_ENTITLEMENTS,

    /** Sends `fresh_premium=true`; bypasses the server premium cache as well. */
    FORCE_PREMIUM;

    val bypassesClientDebounce: Boolean
        get() = this != IF_STALE

    /** Only a `fresh_premium=true` query can produce an authoritative ACTIVE grant. */
    val canGrantPremium: Boolean
        get() = this == FORCE_PREMIUM
}

/** Why the server answer could not decide premium. Each maps to a distinct recovery. */
enum class IndeterminateReason {
    /** Transport failure, 408, 429, or 5xx. Typed backoff. */
    TRANSIENT,

    /** 401 that survived the single forced refresh and replay. Auth recovery, not a rejection. */
    AUTHENTICATION,

    /** 403 with no recognised typed code. Re-ask with [RefreshIntent.FORCE_PREMIUM]. */
    UNTYPED_FORBIDDEN,

    /** 2xx whose body failed the wire schema, or a protocol violation. */
    DECODE
}

/**
 * Classified `/api/entitlements` outcome.
 *
 * `krxVisible` is carried on every envelope that has one because the plan applies a fail-closed
 * `krx_visible=false` *before* the pending branch (iOS parity, `EntitlementsManager.swift:437`).
 */
sealed interface EntitlementsOutcome {
    /** 200 with a stable `premium_active=true`. */
    data class StableActive(val krxVisible: Boolean) : EntitlementsOutcome

    /** 200 with a stable `premium_active=false`. Authoritative free. */
    data class StableInactive(val krxVisible: Boolean) : EntitlementsOutcome

    /**
     * 200 with `premium_pending=true`. Never an authoritative premium false: the provider may be
     * unavailable or the server cache may have missed.
     *
     * [retryAfterSeconds] is the body field the server sends with this envelope
     * (`schemas.EntitlementsResponse.retry_after_seconds`), not the HTTP header.
     */
    data class Pending(
        val krxVisible: Boolean,
        val retryAfterSeconds: Long? = null
    ) : EntitlementsOutcome

    /** Typed `premium_required`. Authoritative rejection of the whole premium surface. */
    data object PremiumRequired : EntitlementsOutcome

    /** Typed `krx_entitlement_required`. Rejects the KRX capability only. */
    data object KrxEntitlementRequired : EntitlementsOutcome

    /** No decision was delivered. Never downgrades an existing grant. */
    data class Indeterminate(
        val reason: IndeterminateReason,
        /** Server-supplied `Retry-After`, in seconds, when present. A hard floor; never shortened. */
        val retryAfterSeconds: Long? = null
    ) : EntitlementsOutcome
}

/** D23 sealed access state. `Pending` and `NoGrant` both mean "no premium runtime". */
sealed interface PremiumAccessState {
    /** Nothing known yet, and nothing was ever confirmed in this namespace. */
    data object NoGrant : PremiumAccessState

    /** A `fresh_premium=true` ACTIVE was observed. Survives pending and indeterminate answers. */
    data object PremiumConfirmed : PremiumAccessState

    /** A stable `premium_active=false` was observed. */
    data object FreeConfirmed : PremiumAccessState

    /**
     * Awaiting a decision with no grant in hand.
     *
     * [continuityEligible] records that the app may still hold a *previous* namespace whose
     * markers have not been cleared, so a later confirmation reopens rather than recreates.
     */
    data class Pending(val continuityEligible: Boolean) : PremiumAccessState

    /** Typed `premium_required`. A local `true` must not reopen this. */
    data object Rejected : PremiumAccessState

    /** True only while the server currently authorises the premium runtime. */
    val grantsPremiumRuntime: Boolean
        get() = this is PremiumConfirmed
}

/** KRX capability visibility, tracked independently of the premium axis. */
enum class KrxCapabilityState {
    /** Never opened, or hidden by a false edge. */
    HIDDEN,

    /** Opened by a fresh `krx_visible=true` while the premium runtime was open. */
    VISIBLE
}

/**
 * A side effect the reducer *declares*. Nothing here is executed by this slice.
 *
 * Wiring these to the real Root surface, cache purge and FCM teardown changes the app's
 * permission boundary and is deliberately out of scope: see `ANDROID_V2_PLAN.md` §5.1 D21 and
 * the still-open server D21 ordering contract.
 */
sealed interface AccessEffect {
    /** Persist a new `userAccessEpoch` and a pending-purge journal entry for the old namespace. */
    data object RotateUserEpoch : AccessEffect

    /** Persist a new `krxCapabilityEpoch` and a pending-purge journal entry. */
    data object RotateKrxEpoch : AccessEffect

    /** Cancel protected runtime and purge the old user namespace. */
    data object PurgeUserScope : AccessEffect

    /** Hide and purge the old KRX capability namespace. */
    data object PurgeCapabilityScope : AccessEffect

    /**
     * Unregister the FCM device token.
     *
     * Declared only. The client-side DELETE-before-POST ordering that this belongs to depends on
     * a server ordering guarantee that Phase A0 showed the current server does not provide
     * (`docs/d21-a0-evidence/REPORT.md`: "client-only FAIL"). Implementing it before that
     * contract is settled would ship a rule that does not achieve its purpose.
     */
    data object PushDelete : AccessEffect

    /** A cached ACTIVE cannot grant; escalate to a single in-flight `fresh_premium=true` query. */
    data object StartForcePremiumSingleFlight : AccessEffect
}

/** What the reducer decided, including the follow-up query it wants. */
data class AccessDecision(
    val state: PremiumAccessState,
    val krx: KrxCapabilityState,
    val effects: List<AccessEffect>,
    /** Non-null when the coordinator should schedule another query. */
    val recheck: RecheckRequest?
)

/**
 * The retry floor this answer itself stated, in milliseconds, or null when it stated none.
 *
 * A `Retry-After` is a rate limit on the transport, so it survives a decision the coordinator
 * declines to apply: not acting on a body is not licence to ignore the wait that came with it.
 */
fun EntitlementsOutcome.statedRetryFloorMillis(): Long? = when (this) {
    is EntitlementsOutcome.Pending -> retryAfterSeconds
    is EntitlementsOutcome.Indeterminate -> retryAfterSeconds
    else -> null
}?.times(1_000L)

/** A follow-up query request owned by a single [RecheckSchedule]. */
data class RecheckRequest(
    val intent: RefreshIntent,
    /**
     * Earliest permitted delay. A server `Retry-After` is a hard floor: the schedule may wait
     * longer, never less.
     */
    val minDelayMillis: Long
)
