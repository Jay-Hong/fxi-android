package com.jay.fxi.data.entitlements.control

import java.io.File
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Short integration/mutation oracles; each independent condition has its own JUnit method. */
class ControlReleaseAccumulationTest {
    @get:Rule val folder = TemporaryFolder()

    private suspend fun withFixture(block: suspend (ReleaseAccumulationFixture) -> Unit) {
        val fixture = ReleaseAccumulationFixture(File(folder.root, "short.preferences_pb"), ReleaseStringProfile.SHORT_ASCII)
        try { fixture.seed(); block(fixture) } finally { fixture.close() }
    }

    @Test fun M08_threeCyclesKeepOnlyActiveControls() = runReleaseTest {
        withFixture { f -> repeat(3) { f.cycle() } }
    }

    private suspend fun consumeAndRelease(f: ReleaseAccumulationFixture, command: CommandRef) {
        assertNotNull(f.own(f.disk(), command))
        val tracked = checkNotNull(f.tracker.findPrepared(command))
        val checkpoint = checkNotNull(f.control.checkpoint(command))
        val first = tracked.firstConfirmDiscontinuityCount
        val result = f.release(command)
        assertTrue(result is ControlCommandReleaseResult.Released)
        assertNull(f.tracker.findPrepared(command))
        assertNull(f.own(f.disk(), command))
        assertFalse(command in f.tracker.recoverySnapshot().pendingReleases)
        assertFalse(command in f.tracker.recoverySnapshot().unresolvedCommands)
        assertTrue(f.tracker.executing.isEmpty())
        assertEquals(first, tracked.firstConfirmDiscontinuityCount)
        assertTrue(tracked.observedApplied.get())
        assertTrue(f.execute(command) is ControlStoreResult.Released)
        assertTrue(controlTestTimeout("old checkpoint is terminal") {
            f.control.confirmPrevious(command, checkpoint)
        } is ControlStoreResult.Released)
    }

    @Test fun M02_waitingConsumerCanLaterConsumeAndRelease() = runReleaseTest {
        withFixture { f -> f.cycle(); consumeAndRelease(f, f.waiting) }
    }

    @Test fun M03_landedBusinessFailureCanConfirmThenRelease() = runReleaseTest {
        withFixture { f ->
            f.cycle()
            val first = checkNotNull(f.tracker.findPrepared(f.landed)).firstConfirmDiscontinuityCount
            f.executeConfirmed(f.landed)
            assertEquals(first, checkNotNull(f.tracker.findPrepared(f.landed)).firstConfirmDiscontinuityCount)
            consumeAndRelease(f, f.landed)
        }
    }

    @Test fun M03_unlandedBusinessFailureCanExecuteThenRelease() = runReleaseTest {
        withFixture { f ->
            f.cycle()
            val first = checkNotNull(f.tracker.findPrepared(f.unlanded)).firstConfirmDiscontinuityCount
            assertNull(f.own(f.disk(), f.unlanded))
            f.executeConfirmed(f.unlanded)
            assertEquals(first, checkNotNull(f.tracker.findPrepared(f.unlanded)).firstConfirmDiscontinuityCount)
            consumeAndRelease(f, f.unlanded)
        }
    }

    @Test fun M01_pendingManagementFailureCanRetryWithoutResolvingBusinessControls() = runReleaseTest {
        withFixture { f ->
            f.cycle()
            val old = f.tracker.recoverySnapshot()
            val history = checkNotNull(f.tracker.findPrepared(f.pending))
            val descriptor = history.releaseDescriptor
            assertTrue(f.release(f.pending) is ControlCommandReleaseResult.Released)
            assertSame(descriptor, history.releaseDescriptor)
            assertNull(f.tracker.findPrepared(f.pending))
            assertNull(f.own(f.disk(), f.pending))
            assertEquals(old.unresolvedCommands, f.tracker.recoverySnapshot().unresolvedCommands)
            assertEquals(old.pendingReleases - f.pending, f.tracker.recoverySnapshot().pendingReleases)
            assertEquals(setOf(f.waiting, f.landed, f.unlanded, f.prepared, f.rotation),
                ControlReleaseFixtures.commands(f.tracker).values.map { it.command }.toSet())
            assertTrue(f.tracker.executing.isEmpty())
        }
    }

    @Test fun C10_C11_C14_normalReadsEditsBarrierReclamationAndFacadeKeepContinuity() = runReleaseTest {
        withFixture { f ->
            assertEquals(BigInteger.ZERO, f.tracker.evidenceDiscontinuityCount)
            val command = f.prepareFloor(f.disk())
            f.executeConfirmed(command)
            val history = checkNotNull(f.tracker.findPrepared(command))
            val first = history.firstConfirmDiscontinuityCount
            val facade = ControlRecordStore(f.storage.owner)
            assertTrue(controlTestTimeout("same owner 2c") { facade.reclaimPreviousLifetimeEvidence() } is ControlEvidenceReclamationResult.Confirmed)
            f.storage.storage.before = true
            assertTrue(f.release(command) is ControlCommandReleaseResult.Unconfirmed)
            assertTrue(controlTestTimeout("recreated facade with read-back barrier") {
                facade.releaseAfterConsumption(command)
            } is ControlCommandReleaseResult.Released)
            assertEquals(first, history.firstConfirmDiscontinuityCount)
            assertEquals(BigInteger.ZERO, f.tracker.evidenceDiscontinuityCount)
            f.assertControls(f.disk())
        }
    }
}
