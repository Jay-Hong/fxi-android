package com.jay.fxi.data.entitlements.control

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A completed caller and joined old DataStore precede every same-file reopening. */
class ControlReleaseAccumulationRestartTest {
    @get:Rule val folder = TemporaryFolder()

    private suspend fun restart(landed: Boolean) {
        val file = File(folder.root, "restart.preferences_pb")
        val old = ReleaseAccumulationFixture(file, ReleaseStringProfile.SHORT_ASCII)
        try {
            old.seed()
            repeat(3) { old.cycle() }
            val ref = old.prepareFloor(old.disk())
            old.executeConfirmed(ref)
            val checkpoint = checkNotNull(old.control.checkpoint(ref))
            val before = old.disk()
            if (landed) old.storage.storage.afterScope = true else old.storage.storage.before = true
            assertTrue(old.release(ref) is ControlCommandReleaseResult.Unconfirmed)
            assertEquals(ControlCommandLifecycle.RELEASE_PENDING, ref.lifecycleState)
            assertEquals(!landed, old.own(old.disk(), ref) != null)
            assertEquals(ReleaseAccumulationFixture.without(before, ReleaseAccumulationFixture.evidenceKey),
                ReleaseAccumulationFixture.without(old.disk(), ReleaseAccumulationFixture.evidenceKey))
            old.close() // release returned; then cancelAndJoin of the entire old DataStore scope.
            val reopened = ReleaseAccumulationFixture(file, ReleaseStringProfile.SHORT_ASCII)
            try {
                assertNotSame(old.tracker, reopened.tracker)
                assertNotSame(old.tracker.lifetimeId, reopened.tracker.lifetimeId)
                assertTrue(reopened.execute(ref) is ControlStoreResult.ReleasePending)
                assertTrue(controlTestTimeout("old pending checkpoint after restart") {
                    reopened.control.confirmPrevious(ref, checkpoint)
                } is ControlStoreResult.ReleasePending)
                val rejected = reopened.release(ref) as ControlCommandReleaseResult.Rejected
                assertEquals(ReleaseRejectionReason.WrongTrackerLifetime, rejected.reason)
                assertTrue(rejected.localUnresolvedCommands.isEmpty())
                assertTrue(rejected.localPendingReleases.isEmpty())
                // A fresh current-lifetime row must survive 2c alongside the old rows' reclamation.
                val current = reopened.prepareFloor(reopened.disk(), 10)
                reopened.executeConfirmed(current)
                val before2c = reopened.disk()
                val currentRow = reopened.rows(before2c).last()
                assertTrue(controlTestTimeout("reclaim previous lifetimes after restart") {
                    reopened.control.reclaimPreviousLifetimeEvidence()
                } is ControlEvidenceReclamationResult.Confirmed)
                val after = reopened.disk()
                assertNotNull(reopened.own(after, current))
                assertEquals("current row values preserved", currentRow, reopened.rows(after).single())
                assertEquals(1, reopened.rows(after).size)
                assertNull(reopened.own(after, ref))
                assertNull(reopened.own(after, old.waiting))
                assertNull(reopened.own(after, old.landed))
                assertNull(reopened.own(after, old.pending))
                assertNull(reopened.own(after, old.rotation))
                // Existing 2c also reclaims previous-lifetime rotation's settled seal. Other
                // obligations and all external/fence values remain exactly as immediately before 2c.
                assertEquals("[]", after[ControlStoreTestStorage.SEAL])
                assertEquals(ReleaseAccumulationFixture.without(before2c,
                    ReleaseAccumulationFixture.evidenceKey, ControlStoreTestStorage.SEAL),
                    ReleaseAccumulationFixture.without(after,
                        ReleaseAccumulationFixture.evidenceKey, ControlStoreTestStorage.SEAL))
                assertEquals(setOf(current), ControlReleaseFixtures.commands(reopened.tracker).values.map { it.command }.toSet())
                assertTrue(reopened.tracker.recoverySnapshot().unresolvedCommands.isEmpty())
                assertTrue(reopened.tracker.recoverySnapshot().pendingReleases.isEmpty())
                assertTrue(reopened.tracker.executing.isEmpty())
                assertEquals(ControlCommandLifecycle.RELEASE_PENDING, ref.lifecycleState)
            } finally { reopened.close() }
        } finally { old.close() }
    }

    @Test fun C15_unlandedRestartReclaimsOldRowsAndPreservesNewLifetime() = runReleaseTest { restart(false) }
    @Test fun C16_landedRestartKeepsAbsenceAndPreservesNewLifetime() = runReleaseTest { restart(true) }
}
