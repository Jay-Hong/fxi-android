package com.jay.fxi.ui.screen

import com.jay.fxi.data.entitlements.PremiumAccessState
import com.jay.fxi.domain.model.AuthProvider
import com.jay.fxi.domain.model.AuthState
import com.jay.fxi.domain.model.UserInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the Root mapping before anything consumes it.
 *
 * Three properties are the ones that get paraphrased away:
 *  - auth outranks access, so a stale grant cannot survive a sign-out,
 *  - `Unknown` is not `SignedOut` — collapsing them flashes Login on every cold start,
 *  - every signed-in non-`PremiumConfirmed` state is the *same* surface, so the mapping cannot be
 *    replaced by a two-valued boolean copied from `isPremium`.
 */
class RootDestinationTest {

    private val signedIn = AuthState.SignedIn(
        UserInfo(
            uid = "user-a",
            email = null,
            displayName = null,
            photoUrl = null,
            provider = AuthProvider.GOOGLE
        )
    )

    /** Every access state, so a new case cannot be added without this list failing to compile. */
    private val allAccessStates = listOf(
        PremiumAccessState.NoGrant,
        PremiumAccessState.PremiumConfirmed,
        PremiumAccessState.FreeConfirmed,
        PremiumAccessState.Pending(continuityEligible = true),
        PremiumAccessState.Pending(continuityEligible = false),
        PremiumAccessState.Rejected
    )

    @Test
    fun signedOut_isLogin_forEveryAccessState() {
        allAccessStates.forEach { access ->
            assertEquals(
                "a signed-out user must never reach a granted surface (access=$access)",
                RootDestination.Login,
                rootDestinationFor(AuthState.SignedOut, access)
            )
        }
    }

    @Test
    fun unknownAuth_isSplash_notLogin() {
        // Collapsing Unknown into SignedOut would flash Login on every cold start, before
        // Firebase reports the restored user.
        assertEquals(
            RootDestination.Splash,
            rootDestinationFor(AuthState.Unknown, PremiumAccessState.NoGrant)
        )
    }

    @Test
    fun unknownAuth_staysSplash_evenWithAConfirmedGrant() {
        assertEquals(
            RootDestination.Splash,
            rootDestinationFor(AuthState.Unknown, PremiumAccessState.PremiumConfirmed)
        )
    }

    @Test
    fun signedIn_onlyPremiumConfirmed_opensPremium() {
        assertEquals(
            RootDestination.Premium,
            rootDestinationFor(signedIn, PremiumAccessState.PremiumConfirmed)
        )
    }

    @Test
    fun signedIn_everyOtherAccessState_isTheSameFreeSurface() {
        val notConfirmed = allAccessStates.filter { it != PremiumAccessState.PremiumConfirmed }
        // Five distinct states, one destination. Rejected included: the D23 table sends a typed
        // `premium_required` to "즉시 무료 전환", not back to Login. A boolean derived from
        // `isPremium` gives the same answer here but loses Rejected vs Pending, which step 3 needs.
        assertEquals(5, notConfirmed.size)
        notConfirmed.forEach { access ->
            assertEquals(
                "signed-in non-confirmed must be the real free surface (access=$access)",
                RootDestination.FreeSnapshot,
                rootDestinationFor(signedIn, access)
            )
        }
    }
}
