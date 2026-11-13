package com.jay.fxi.service

import com.jay.fxi.data.auth.AuthIdentityFence
import com.jay.fxi.data.entitlements.OwnedPremiumAccess
import com.jay.fxi.data.entitlements.PremiumAccessState
import org.junit.Assert.assertEquals
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
        assertTrue(pushRegistrationAllowed(sessionA1, confirmedForA1, shouldRegisterForPush = true, capabilityGranted = true))

        // The mismatch this gate exists for: a grant decided for one identity while the request
        // goes out as another.
        assertFalse(
            "another account's grant authorised this registration",
            pushRegistrationAllowed(sessionB1, confirmedForA1, shouldRegisterForPush = true, capabilityGranted = true)
        )
        assertFalse(
            "a torn-down session of the same account authorised it",
            pushRegistrationAllowed(sessionA2, confirmedForA1, shouldRegisterForPush = true, capabilityGranted = true)
        )
        assertFalse(
            "a registration was allowed with no identity to send it as",
            pushRegistrationAllowed(null, confirmedForA1, shouldRegisterForPush = true, capabilityGranted = true)
        )
    }

    @Test
    fun theUsersOwnChoiceAndTheServersAnswerAreBothRequired() {
        assertFalse(
            "registered without the user having asked for notifications",
            pushRegistrationAllowed(sessionA1, confirmedForA1, shouldRegisterForPush = false, capabilityGranted = true)
        )
        assertFalse(
            "registered on an unconfirmed grant",
            pushRegistrationAllowed(
                sessionA1,
                OwnedPremiumAccess("user-a", 1L, PremiumAccessState.FreeConfirmed),
                shouldRegisterForPush = true,
                capabilityGranted = true
            )
        )
    }

    /** S7 E5b2a / D21: the OS capability is its own condition, read fresh, so a stale intent cannot outlive a denial. */
    @Test
    fun S7E5B2A_G01_noRegistrationWhileTheOsCannotShowAnAlert() {
        for (owner in listOf(sessionA1, sessionA2, sessionB1, null)) for (intent in listOf(true, false)) for (access in listOf(
            confirmedForA1, OwnedPremiumAccess("user-a", 1L, PremiumAccessState.FreeConfirmed), OwnedPremiumAccess())) {
            assertFalse("registered with the OS refusing alerts ($owner, $intent, $access)",
                pushRegistrationAllowed(owner, access, shouldRegisterForPush = intent, capabilityGranted = false))
        }
        assertTrue("control: the same session with the capability on", pushRegistrationAllowed(sessionA1, confirmedForA1, shouldRegisterForPush = true, capabilityGranted = true))
    }

    /** S7 E5b2a gap G02: only a server-confirmed premium for the exact session qualifies; no other state does. */
    @Test
    fun S7E5B2A_GAP02_onlyTheServerConfirmedStateForTheExactSessionQualifies() {
        val states = listOf(PremiumAccessState.NoGrant, PremiumAccessState.FreeConfirmed, PremiumAccessState.Rejected,
            PremiumAccessState.Pending(false), PremiumAccessState.Pending(true), PremiumAccessState.PremiumConfirmed)
        for (state in states) for (owner in listOf(sessionA1, sessionA2, sessionB1, null))
            for (intent in listOf(false, true)) for (capability in listOf(false, true)) {
                val expected = state == PremiumAccessState.PremiumConfirmed && owner == sessionA1 && intent && capability
                assertEquals("$state $owner intent=$intent capability=$capability", expected,
                    pushRegistrationAllowed(owner, OwnedPremiumAccess("user-a", 1L, state), shouldRegisterForPush = intent, capabilityGranted = capability))
            }
    }
}
