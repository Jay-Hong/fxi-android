package com.jay.fxi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            startTopicOwner = { started += "topic-owner" },
            launchRateMigration = { started += "rate-migration" },
            purgeRetiredStores = { started += "purge" }
        )
        // Order is asserted with them: the two that bind identity go before anything that might depend on one, the rate
        // migration's cutover reads the topic owner's readiness so it follows the owner (R4-c C4-J-START-ADMISSION), and the
        // purge answers to nobody and goes last.
        assertEquals(listOf("access", "snapshots", "topic-owner", "rate-migration", "purge"), started)
    }

    @Test
    fun `C4-J-START-ADMISSION only an admitted process that carries data starts app-owned services`() {
        assertFalse("OFF", shouldStartAppOwnedServices(releaseAdmissionOpen = false, benchmarkNoData = false))
        assertFalse("OFF and no-data", shouldStartAppOwnedServices(releaseAdmissionOpen = false, benchmarkNoData = true))
        assertFalse("ON and no-data", shouldStartAppOwnedServices(releaseAdmissionOpen = true, benchmarkNoData = true))
        assertTrue("ON", shouldStartAppOwnedServices(releaseAdmissionOpen = true, benchmarkNoData = false))
    }
}
