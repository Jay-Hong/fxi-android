package com.jay.fxi.data.entitlements

import com.jay.fxi.data.auth.AuthIdentityFence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push-registration decision, away from the components that make it.
 *
 * Every entry point that can register a device goes through this, so the two cannot drift, and the
 * rule needs no RevenueCat, no Firebase and no Android to exercise.
 */
class OwnedPremiumAccessTest {

    private val sessionA1 = AuthIdentityFence("user-a", 1L)
    private val sessionA2 = AuthIdentityFence("user-a", 2L)
    private val sessionB1 = AuthIdentityFence("user-b", 1L)

    private fun confirmed(uid: String, generation: Long) =
        OwnedPremiumAccess(uid, generation, PremiumAccessState.PremiumConfirmed)

    @Test
    fun onlyTheExactSessionTheGrantWasDecidedForIsAuthorised() {
        val grant = confirmed("user-a", 1L)

        assertTrue(grant.confirmsPremiumFor(sessionA1))

        // Another account: B's auth callback running before A's grant was cleared used to register
        // B's device on A's entitlement.
        assertFalse("another account's grant authorised this one", grant.confirmsPremiumFor(sessionB1))

        // The same account, a session later. A sign-out followed by a sign-in of the *same* user
        // rotates the generation and nothing else, so uid alone cannot tell these apart — and the
        // torn-down session's grant was authorising the new one.
        assertFalse("a previous session of the same account was honoured", grant.confirmsPremiumFor(sessionA2))

        assertFalse("a signed-out session was authorised", grant.confirmsPremiumFor(null))
    }

    @Test
    fun noOtherStateAuthorisesRegistration() {
        listOf(
            PremiumAccessState.NoGrant,
            PremiumAccessState.FreeConfirmed,
            PremiumAccessState.Rejected,
            PremiumAccessState.Pending(continuityEligible = true)
        ).forEach { state ->
            assertFalse(
                "$state authorised a registration",
                OwnedPremiumAccess("user-a", 1L, state).confirmsPremiumFor(sessionA1)
            )
        }
    }
}
