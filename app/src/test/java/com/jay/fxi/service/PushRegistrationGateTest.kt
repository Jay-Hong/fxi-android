package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registration gate, judged on the JVM.
 *
 * Extracted for the same reason [ApiPushDeviceServer] is: the manager it belongs to needs a
 * `Context`, and the rule it applies is the one thing here worth failing a build over.
 */
class PushRegistrationGateTest {

    private val sessionA1 = AuthIdentityFence("user-a", 1L)
    private val sessionA2 = AuthIdentityFence("user-a", 2L)
    private val sessionB1 = AuthIdentityFence("user-b", 1L)
    private val confirmedForA1 =
        OwnedPremiumAccess("user-a", 1L, PremiumAccessState.PremiumConfirmed)

    @Test
    fun registersOnlyForTheSessionTheGrantWasDecidedFor() {
        assertTrue(pushRegistrationAllowed(sessionA1, confirmedForA1, shouldRegisterForPush = true))

        // The mismatch this gate exists for: a grant decided for one identity while the request
        // goes out as another.
        assertFalse(
            "another account's grant authorised this registration",
            pushRegistrationAllowed(sessionB1, confirmedForA1, shouldRegisterForPush = true)
        )
        assertFalse(
            "a torn-down session of the same account authorised it",
            pushRegistrationAllowed(sessionA2, confirmedForA1, shouldRegisterForPush = true)
        )
        assertFalse(
            "a registration was allowed with no identity to send it as",
            pushRegistrationAllowed(null, confirmedForA1, shouldRegisterForPush = true)
        )
    }

    @Test
    fun theUsersOwnChoiceAndTheServersAnswerAreBothRequired() {
        assertFalse(
            "registered without the user having asked for notifications",
            pushRegistrationAllowed(sessionA1, confirmedForA1, shouldRegisterForPush = false)
        )
        assertFalse(
            "registered on an unconfirmed grant",
            pushRegistrationAllowed(
                sessionA1,
                OwnedPremiumAccess("user-a", 1L, PremiumAccessState.FreeConfirmed),
                shouldRegisterForPush = true
            )
        )
    }
}
