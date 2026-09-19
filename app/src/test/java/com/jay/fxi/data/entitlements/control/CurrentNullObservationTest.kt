package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.context
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.both
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.raw
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.spec
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.read
import java.io.IOException
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class CurrentNullObservationTest : CurrentNullOwnerBase() {
    @Test fun A06_firstObservationWithOpaqueSibling_currentWitnessRetained() = runReleaseTest {
        firstObservationThenLoss(spec(), restorePreimage = false)
    }

    @Test fun A06_firstObservationWithOpaqueSibling_currentPreimageRestored() = runReleaseTest {
        firstObservationThenLoss(spec(), restorePreimage = true)
    }

    @Test fun A06_firstObservationWithOpaqueSibling_bothWitnessRetained() = runReleaseTest {
        firstObservationThenLoss(both(), restorePreimage = false)
    }

    @Test fun A06_firstObservationWithOpaqueSibling_bothPreimageRestored() = runReleaseTest {
        firstObservationThenLoss(both(), restorePreimage = true)
    }

    private suspend fun firstObservationThenLoss(s: CurrentNullSettlement, restorePreimage: Boolean) {
        val initial = raw(s)
        seedN(s, initial)
        val c = registerN(s)
        val ctx = context.copy(ownerUid = s.executor.ownerUid)
        val tracked = history(c)

        // The complete candidate reaches FileStorage, but the caller never receives confirmation.
        o.storage.afterScope = true
        val failed = executeN(c, ctx)
        assertTrue(failed is ControlStoreResult.Unconfirmed)
        failed as ControlStoreResult.Unconfirmed
        assertEquals(UnconfirmedReason.StorageFailure, failed.reason)
        assertEquals(ControlAttemptPhase.ConfirmingStorage, failed.phase)
        assertTrue(failed.failure is IOException)
        assertEquals(setOf(c), failed.localUnresolvedCommands)
        assertTrue(tracked.confirmationRequested.get())
        assertFalse(tracked.confirmed.get())
        assertFalse(tracked.observedApplied.get())
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
        assertFalse(c in tracking.executing)
        assertLanded(c, s, initial)
        val landed = disk()
        assertNotNull(own(landed, c))

        // This retry is the first actual observation of the command's Applied row.
        val opaque = landed.toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[{}]" }
        controlTestTimeout("N opaque sibling after landing") { o.data.updateData { opaque } }
        val recoveryWrites = o.storage.writes
        val recovery = executeN(c, ctx)
        NamespaceSettlementFixtures.negative(recovery, RecoveryReason.UninterpretableObligations)
        val observedAtRecovery = tracked.observedApplied.get()
        assertFalse(tracked.confirmed.get())
        assertEquals(setOf(c), recovery.localUnresolvedCommands)
        assertEquals(opaque, disk())
        assertEquals(opaque, o.raw())
        assertEquals(recoveryWrites, o.storage.writes)
        assertEquals(BigInteger.ZERO, tracking.evidenceDiscontinuityCount)
        assertFalse(c in tracking.executing)

        // Repair the sibling and remove Applied, either retaining the witness or restoring preimage.
        val restored = (if (restorePreimage) initial else landed).toMutablePreferences().apply {
            this[evidenceKey] = "[]"
        }
        controlTestTimeout("N repair sibling and lose Applied") { o.data.updateData { restored } }
        val lostWrites = o.storage.writes
        val lost = executeN(c, ctx)
        NamespaceSettlementFixtures.negative(lost, RecoveryReason.CommandEvidenceLost)
        assertTrue("the rejected observation must retain Applied history", observedAtRecovery)
        assertTrue(tracked.observedApplied.get())
        assertFalse(tracked.confirmed.get())
        assertSame(tracked, history(c))
        assertEquals(setOf(c), lost.localUnresolvedCommands)
        assertEquals(setOf(c), tracking.recoverySnapshot().unresolvedCommands)
        assertTrue(lost.localPendingReleases.isEmpty())
        assertEquals(restored, disk())
        assertEquals(restored, o.raw())
        assertEquals(lostWrites, o.storage.writes)
        assertEquals(BigInteger.ZERO, tracked.firstConfirmDiscontinuityCount)
        assertEquals(BigInteger.ZERO, tracking.evidenceDiscontinuityCount)
        assertFalse(c in tracking.executing)
    }
}
