package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.mutablePreferencesOf
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

/** Retain the old history only in this caller; successful release must unlink it without resetting it. */
class ControlReleaseRetainedHistoryTest : ReleaseOwnerTestBase() {
    @Test fun M03_releaseKeepsObservedAppliedOnDetachedHistory() = runReleaseTest {
        o.seed()
        val ref = confirmed()
        val tracked = history(ref)
        assertTrue(tracked.observedApplied.get())
        assertTrue(controlTestTimeout("release retained observed history") {
            o.control.releaseAfterConsumption(ref)
        } is ControlCommandReleaseResult.Released)
        assertNull(tracking.findPrepared(ref))
        assertTrue("detached observedApplied remains monotonic", tracked.observedApplied.get())
    }

    @Test fun M03_releaseKeepsOldFirstConfirmOnDetachedHistory() = runReleaseTest {
        o.seed()
        val ref = confirmed()
        val tracked = history(ref)
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
        val supported = disk()
        // Defensive input: only actual owner observation advances continuity, never a direct flag edit.
        controlTestTimeout("inject missing control schema") { o.data.updateData { mutablePreferencesOf() } }
        assertTrue(controlTestTimeout("observe real discontinuity") {
            o.control.releaseAfterConsumption(ref)
        } is ControlCommandReleaseResult.RecoveryRequired)
        assertEquals(BigInteger.ONE, tracking.evidenceDiscontinuityCount)
        controlTestTimeout("restore exact supported record") { o.data.updateData { supported } }
        assertTrue(controlTestTimeout("release after supported restoration") {
            o.control.releaseAfterConsumption(ref)
        } is ControlCommandReleaseResult.Released)
        assertNull(tracking.findPrepared(ref))
        assertEquals("release must not reset or rebind business first Confirm", BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
    }
}
