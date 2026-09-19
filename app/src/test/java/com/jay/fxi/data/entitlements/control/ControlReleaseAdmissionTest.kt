package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlReleaseAdmissionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun A02_confirmedThenBadCheckpointRemainsUnresolved() = runBlocking {
        val o = ControlStoreTestStorage(File(folder.root, "admission.preferences_pb"))
        try {
            o.seed()
            val tracker = ControlCommandTracking.forOwner(o.owner)
            val c = o.control.prepare(o.control.addition(ControlKind.RECOVERY_INTENT) { id ->
                literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
            })
            assertTrue(o.control.execute(c) is ControlStoreResult.Confirmed)
            val tracked = checkNotNull(tracker.findPrepared(c))
            val pending = o.control.prepare()
            ControlReleaseFixtures.simulatePending(tracker, pending)
            val wrongCheckpoint = ControlCommandCheckpoint(pending, emptyList(), true)
            val before = o.raw(); val writes = o.storage.writes
            val result = o.control.confirmPrevious(c, wrongCheckpoint)
            assertEquals(UnconfirmedReason.HistoryUnavailable, (result as? ControlStoreResult.Unconfirmed)?.reason)
            assertEquals(setOf(c), result.localUnresolvedCommands)
            assertEquals(setOf(pending), result.localPendingReleases)
            assertTrue(tracked.confirmed.get())
            val admission = ControlCommandReleaseEligibility.decide(c, tracker.lifetimeId,
                tracker.findPrepared(c), c in tracker.executing, tracker.isUnresolved(c))
            assertEquals("unresolved business work takes priority over confirmed history", ReleaseRejectionReason.Unresolved,
                (admission as? ControlCommandReleaseEligibility.Decision.Rejected)?.reason)
            assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
            assertNull(tracked.releaseDescriptor); assertSame(tracked, tracker.findPrepared(c))
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
        } finally { o.close() }
    }
}
