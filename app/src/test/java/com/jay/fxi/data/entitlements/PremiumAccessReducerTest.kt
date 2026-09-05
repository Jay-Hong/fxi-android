package com.jay.fxi.data.entitlements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the D23 reducer transition table (`ANDROID_V2_PLAN.md` §5.1).
 *
 * Four rows are routinely paraphrased into something weaker, so each has a named test:
 *  - only `.forcePremium` can grant; a cached ACTIVE escalates instead,
 *  - a stable `premium_active=false` is `FreeConfirmed`, which is *not* `Rejected`,
 *  - `premium_pending=true` is never an authoritative loss even though it carries
 *    `premium_active=false`,
 *  - an indeterminate answer preserves an existing grant with no time expiry.
 */
class PremiumAccessReducerTest {

    private val noGrant = AccessSnapshot()
    private val confirmed = AccessSnapshot(state = PremiumAccessState.PremiumConfirmed)

    // --- rule 1: only .forcePremium can grant -------------------------------------------------

    @Test
    fun cachedActiveFromOrdinaryQuery_doesNotGrant_andEscalates() {
        val decision = PremiumAccessReducer.reduce(
            noGrant,
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.StableActive(krxVisible = true)
        )

        assertEquals(PremiumAccessState.NoGrant, decision.state)
        assertTrue(AccessEffect.StartForcePremiumSingleFlight in decision.effects)
        assertEquals(RefreshIntent.FORCE_PREMIUM, decision.recheck?.intent)
        // A cached ACTIVE must not open KRX either: the premium runtime is not open.
        assertEquals(KrxCapabilityState.HIDDEN, decision.krx)
    }

    @Test
    fun cachedActiveFromForceEntitlements_doesNotGrant() {
        val decision = PremiumAccessReducer.reduce(
            noGrant,
            RefreshIntent.FORCE_ENTITLEMENTS,
            EntitlementsOutcome.StableActive(krxVisible = false)
        )

        assertEquals(PremiumAccessState.NoGrant, decision.state)
        assertTrue(AccessEffect.StartForcePremiumSingleFlight in decision.effects)
    }

    @Test
    fun forcePremiumActive_grants_andDoesNotRotateUserEpoch() {
        val decision = PremiumAccessReducer.reduce(
            noGrant,
            RefreshIntent.FORCE_PREMIUM,
            EntitlementsOutcome.StableActive(krxVisible = true)
        )

        assertEquals(PremiumAccessState.PremiumConfirmed, decision.state)
        assertEquals(KrxCapabilityState.VISIBLE, decision.krx)
        // "grant 자체는 epoch를 rotate하지 않음" — a grant reopens, it does not renumber.
        assertFalse(AccessEffect.RotateUserEpoch in decision.effects)
        assertFalse(AccessEffect.RotateKrxEpoch in decision.effects)
        assertNull(decision.recheck)
    }

    // --- rule 2: FreeConfirmed and Rejected are different states ------------------------------

    @Test
    fun stableInactive_isFreeConfirmed_notRejected() {
        val decision = PremiumAccessReducer.reduce(
            confirmed,
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.StableInactive(krxVisible = false)
        )

        assertEquals(PremiumAccessState.FreeConfirmed, decision.state)
    }

    @Test
    fun typedPremiumRequired_isRejected_notFreeConfirmed() {
        val decision = PremiumAccessReducer.reduce(
            confirmed,
            RefreshIntent.FORCE_ENTITLEMENTS,
            EntitlementsOutcome.PremiumRequired
        )

        assertEquals(PremiumAccessState.Rejected, decision.state)
    }

    @Test
    fun authoritativePremiumLoss_alsoHidesKrx() {
        // KRX is a premium-gated surface, and these envelopes carry no `krx_visible` of their own,
        // so leaving the capability visible would survive a full rejection.
        listOf(
            EntitlementsOutcome.PremiumRequired,
            EntitlementsOutcome.StableInactive(krxVisible = false)
        ).forEach { outcome ->
            val decision = PremiumAccessReducer.reduce(
                AccessSnapshot(
                    state = PremiumAccessState.PremiumConfirmed,
                    krx = KrxCapabilityState.VISIBLE
                ),
                RefreshIntent.FORCE_ENTITLEMENTS,
                outcome
            )

            assertEquals("$outcome must hide KRX", KrxCapabilityState.HIDDEN, decision.krx)
        }
    }

    @Test
    fun authoritativeLoss_declaresEachTeardownEffectOnce() {
        val decision = PremiumAccessReducer.reduce(
            AccessSnapshot(
                state = PremiumAccessState.PremiumConfirmed,
                krx = KrxCapabilityState.VISIBLE,
                mayContainKrxData = true
            ),
            RefreshIntent.IF_STALE,
            // The KRX false edge and the premium loss both call for a capability teardown.
            EntitlementsOutcome.StableInactive(krxVisible = false)
        )

        assertEquals(
            "a duplicated effect would read as two rotations",
            decision.effects.size,
            decision.effects.toSet().size
        )
    }

    @Test
    fun authoritativeLoss_withProtectedRuntime_declaresTeardown() {
        val decision = PremiumAccessReducer.reduce(
            confirmed,
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.StableInactive(krxVisible = false)
        )

        assertTrue(AccessEffect.RotateUserEpoch in decision.effects)
        assertTrue(AccessEffect.PurgeUserScope in decision.effects)
        assertTrue(AccessEffect.PushDelete in decision.effects)
    }

    @Test
    fun authoritativeLoss_withNothingProtected_declaresNoTeardown() {
        val decision = PremiumAccessReducer.reduce(
            AccessSnapshot(state = PremiumAccessState.FreeConfirmed),
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.StableInactive(krxVisible = false)
        )

        assertEquals(PremiumAccessState.FreeConfirmed, decision.state)
        assertTrue(decision.effects.isEmpty())
    }

    // --- rule 3: pending is not an authoritative loss -----------------------------------------

    @Test
    fun pending_carriesPremiumActiveFalse_butPreservesExistingGrant() {
        val decision = PremiumAccessReducer.reduce(
            confirmed,
            RefreshIntent.FORCE_PREMIUM,
            // The server sends premium_active=false alongside premium_pending=true.
            EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 5)
        )

        assertEquals(PremiumAccessState.PremiumConfirmed, decision.state)
        assertFalse(AccessEffect.RotateUserEpoch in decision.effects)
        assertFalse(AccessEffect.PushDelete in decision.effects)
    }

    @Test
    fun pending_withNoGrant_staysPending_withoutCreatingOne() {
        val decision = PremiumAccessReducer.reduce(
            noGrant,
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Pending(krxVisible = false)
        )

        assertEquals(PremiumAccessState.Pending(continuityEligible = false), decision.state)
        assertFalse(decision.state.grantsPremiumRuntime)
    }

    @Test
    fun pending_withUnpurgedNamespace_isContinuityEligible() {
        val decision = PremiumAccessReducer.reduce(
            AccessSnapshot(mayContainPremiumData = true),
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Pending(krxVisible = false)
        )

        assertEquals(PremiumAccessState.Pending(continuityEligible = true), decision.state)
    }

    @Test
    fun pendingRetry_preservesOriginatingIntent_andServerRetryAfter() {
        val decision = PremiumAccessReducer.reduce(
            noGrant,
            RefreshIntent.FORCE_PREMIUM,
            // Deliberately not 5 s: that equals DEFAULT_BACKOFF_FLOOR_MILLIS, so it cannot tell
            // "used the server's floor" apart from "silently substituted the default".
            EntitlementsOutcome.Pending(krxVisible = false, retryAfterSeconds = 30)
        )

        // "premium 판정 pending을 ordinary mode로 약화하지 않는다"
        assertEquals(RefreshIntent.FORCE_PREMIUM, decision.recheck?.intent)
        assertEquals(30_000L, decision.recheck?.minDelayMillis)
    }

    /** The other half of the pair: no server floor means the reducer's own default, not zero. */
    @Test
    fun pendingWithoutARetryAfter_fallsBackToTheDefaultFloor() {
        val decision = PremiumAccessReducer.reduce(
            noGrant,
            RefreshIntent.FORCE_PREMIUM,
            EntitlementsOutcome.Pending(krxVisible = false)
        )

        assertEquals(
            PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS,
            decision.recheck?.minDelayMillis
        )
    }

    // --- rule 4: indeterminate never downgrades, and never expires -----------------------------

    @Test
    fun everyIndeterminateReason_preservesExistingGrant() {
        IndeterminateReason.entries.forEach { reason ->
            val decision = PremiumAccessReducer.reduce(
                confirmed,
                RefreshIntent.IF_STALE,
                EntitlementsOutcome.Indeterminate(reason)
            )

            assertEquals(
                "reason=$reason must not downgrade a grant",
                PremiumAccessState.PremiumConfirmed,
                decision.state
            )
            assertTrue(
                "reason=$reason must declare no teardown",
                decision.effects.none {
                    it == AccessEffect.RotateUserEpoch ||
                        it == AccessEffect.PurgeUserScope ||
                        it == AccessEffect.PushDelete
                }
            )
        }
    }

    @Test
    fun everyIndeterminateReason_withNoGrant_createsNoGrant() {
        IndeterminateReason.entries.forEach { reason ->
            val decision = PremiumAccessReducer.reduce(
                noGrant,
                RefreshIntent.FORCE_PREMIUM,
                EntitlementsOutcome.Indeterminate(reason)
            )

            assertFalse("reason=$reason must not grant", decision.state.grantsPremiumRuntime)
        }
    }

    @Test
    fun authenticationIndeterminate_schedulesNoEntitlementsRecheck() {
        val decision = PremiumAccessReducer.reduce(
            confirmed,
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION)
        )

        // Auth recovery owns a 401; re-asking entitlements would only fail again.
        assertNull(decision.recheck)
    }

    @Test
    fun untypedForbidden_reasksWithForcePremium() {
        val decision = PremiumAccessReducer.reduce(
            confirmed,
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Indeterminate(IndeterminateReason.UNTYPED_FORBIDDEN)
        )

        assertEquals(RefreshIntent.FORCE_PREMIUM, decision.recheck?.intent)
    }

    @Test
    fun transientIndeterminate_carriesRetryAfterAsFloor() {
        val decision = PremiumAccessReducer.reduce(
            confirmed,
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT, retryAfterSeconds = 42)
        )

        assertEquals(42_000L, decision.recheck?.minDelayMillis)
    }

    // --- KRX axis ------------------------------------------------------------------------------

    @Test
    fun krxVisibleFalse_isAppliedBeforeThePendingBranch() {
        val decision = PremiumAccessReducer.reduce(
            AccessSnapshot(
                state = PremiumAccessState.PremiumConfirmed,
                krx = KrxCapabilityState.VISIBLE,
                mayContainKrxData = true
            ),
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Pending(krxVisible = false)
        )

        // Premium survives the pending envelope, but the fail-closed KRX value still applies.
        assertEquals(PremiumAccessState.PremiumConfirmed, decision.state)
        assertEquals(KrxCapabilityState.HIDDEN, decision.krx)
        assertTrue(AccessEffect.RotateKrxEpoch in decision.effects)
        assertTrue(AccessEffect.PurgeCapabilityScope in decision.effects)
    }

    @Test
    fun krxFalseToFalse_whenAlreadySettled_rotatesNothing() {
        val decision = PremiumAccessReducer.reduce(
            AccessSnapshot(
                state = PremiumAccessState.PremiumConfirmed,
                krx = KrxCapabilityState.HIDDEN,
                mayContainKrxData = false,
                pendingCapabilityPurge = false
            ),
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Pending(krxVisible = false)
        )

        // Repeat false is not a new edge: reschedule only.
        assertTrue(decision.effects.isEmpty())
        assertEquals(KrxCapabilityState.HIDDEN, decision.krx)
    }

    @Test
    fun krxFalseToFalse_withOutstandingPurge_resumesWithoutRotatingAgain() {
        val decision = PremiumAccessReducer.reduce(
            AccessSnapshot(
                state = PremiumAccessState.PremiumConfirmed,
                krx = KrxCapabilityState.HIDDEN,
                mayContainKrxData = false,
                pendingCapabilityPurge = true
            ),
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Pending(krxVisible = false)
        )

        // DoD: rotate/journal/purge happen on the first edge only. With a deferred purger the
        // journal stays outstanding, so re-rotating here would mint a new id on every poll.
        assertFalse(AccessEffect.RotateKrxEpoch in decision.effects)
        assertTrue("the stuck purge must still be retried",
            AccessEffect.PurgeCapabilityScope in decision.effects)
    }

    @Test
    fun repeatedFalseWithStuckPurge_neverRotatesMoreThanOnce() {
        var snapshot = AccessSnapshot(
            state = PremiumAccessState.PremiumConfirmed,
            krx = KrxCapabilityState.VISIBLE,
            mayContainKrxData = true
        )
        var rotations = 0

        repeat(5) {
            val decision = PremiumAccessReducer.reduce(
                snapshot,
                RefreshIntent.IF_STALE,
                EntitlementsOutcome.Pending(krxVisible = false)
            )
            val rotated = AccessEffect.RotateKrxEpoch in decision.effects
            if (rotated) rotations += 1
            // Mirror AccessEpochTransitions.rotate: the entry absorbs the marker, so the live one
            // clears even though the deferred purge leaves the journal outstanding.
            snapshot = snapshot.copy(
                krx = decision.krx,
                mayContainKrxData = snapshot.mayContainKrxData && !rotated,
                pendingCapabilityPurge = true
            )
        }

        assertEquals("only the first edge may rotate", 1, rotations)
    }

    /**
     * Regression: the journal is global but markers are per-namespace. A previous user's stuck
     * entry must not suppress a teardown that the *current* namespace genuinely needs.
     */
    @Test
    fun anotherNamespacesStuckJournal_doesNotSuppressThisFirstEdge() {
        val decision = PremiumAccessReducer.reduce(
            AccessSnapshot(
                state = PremiumAccessState.PremiumConfirmed,
                krx = KrxCapabilityState.HIDDEN,
                // This namespace has unjournalled KRX data...
                mayContainKrxData = true,
                // ...while an older namespace still owes a purge.
                pendingCapabilityPurge = true
            ),
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Pending(krxVisible = false)
        )

        assertTrue(
            "the current namespace's data must still be journalled",
            AccessEffect.RotateKrxEpoch in decision.effects
        )
    }

    @Test
    fun krxVisibleTrue_doesNotOpenWithoutAPremiumRuntime() {
        val decision = PremiumAccessReducer.reduce(
            noGrant,
            RefreshIntent.IF_STALE,
            EntitlementsOutcome.Pending(krxVisible = true)
        )

        assertEquals(KrxCapabilityState.HIDDEN, decision.krx)
    }

    @Test
    fun krxEntitlementRequired_hidesCapability_withoutRejectingPremium() {
        val decision = PremiumAccessReducer.reduce(
            AccessSnapshot(
                state = PremiumAccessState.PremiumConfirmed,
                krx = KrxCapabilityState.VISIBLE
            ),
            RefreshIntent.FORCE_ENTITLEMENTS,
            EntitlementsOutcome.KrxEntitlementRequired
        )

        assertEquals(PremiumAccessState.PremiumConfirmed, decision.state)
        assertEquals(KrxCapabilityState.HIDDEN, decision.krx)
        assertTrue(AccessEffect.RotateKrxEpoch in decision.effects)
        assertFalse(AccessEffect.RotateUserEpoch in decision.effects)
        assertFalse(AccessEffect.PushDelete in decision.effects)
        assertEquals(RefreshIntent.FORCE_ENTITLEMENTS, decision.recheck?.intent)
    }
}
