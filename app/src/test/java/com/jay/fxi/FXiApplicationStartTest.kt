package com.jay.fxi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * That an admitted process starts everything it is supposed to start.
 *
 * The list exists so the quiet member of it cannot go missing on its own. Two of these announce
 * their own absence — no sign-in, no refreshing — while the retired-store purge is indistinguishable
 * from a phone that was already clean, so nobody would notice for a release or two that the v1
 * file it deletes had stopped being deleted.
 */
class FXiApplicationStartTest {

    @Test
    fun everyAppOwnedServiceIsStarted() {
        val started = mutableListOf<String>()
        startAppOwnedServices(
            bindAccess = { started += "access" },
            startFreeSnapshots = { started += "snapshots" },
            purgeRetiredStores = { started += "purge" }
        )
        // Order is asserted with them: the purge answers to nobody and goes last, and the two that
        // bind identity go before anything that might depend on one.
        assertEquals(listOf("access", "snapshots", "purge"), started)
    }
}
