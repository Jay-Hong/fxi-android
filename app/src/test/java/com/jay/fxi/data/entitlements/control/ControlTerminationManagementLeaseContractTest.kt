package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-1B T5 contract, management-lease part (skeleton v11 §4.1·§4.2; revision 06 §3.3). The management
 * entries share the per-command lease with the business paths: a held business lease makes abandonBeforeFirstConfirm
 * InFlight with zero DataStore access; while a management attempt holds the lease with c already TERMINATION_PENDING, a
 * business execute / confirmPrevious sees the pending state first and returns TerminationPending (checkpoint null), a
 * second abandon gets OtherManagementPath and a retry gets InFlight — none of them touching the DataStore. The
 * implementation thread reads but does not edit this file.
 */
class ControlTerminationManagementLeaseContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }

    @Test fun T5M_01_heldBusinessLeaseMakesAbandonInFlight() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val tracker = f.tracker
        val reached = CountDownLatch(1); val resume = CountDownLatch(1); val first = AtomicBoolean(true)
        val delegate = tracker.executing
        ControlReleaseFixtures.replaceLease(tracker, object : MutableSet<CommandRef> by delegate {
            override fun add(element: CommandRef): Boolean {
                val added = delegate.add(element)
                if (element === c && first.compareAndSet(true, false)) { reached.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }
                return added
            }
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val business = worker.submit<ControlStoreResult> { runBlocking { f.store.execute(c) } }
            assertTrue(reached.await(10, TimeUnit.SECONDS)) // The business execute now holds c's lease.
            val work = tracker.recoverySnapshot(); val before = f.storage.raw(); val writes = f.storage.storage.writes
            val accessBefore = f.boundary.accesses
            val r = controlTestTimeout("abandon in flight") { f.store.abandonBeforeFirstConfirm(c, closure(c)) }
            assertEquals("D2B6/T5M.01: noDataStoreAccess", 0, f.boundary.accesses - accessBefore)
            assertEquals("D2B6/T5M.01: inFlight", CompletionRejectionReason.InFlight, (r as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/T5M.01: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
            assertNull("D2B6/T5M.01: noDescriptor", f.history(c).terminationDescriptor)
            assertEquals("D2B6/T5M.01: setsUnchanged", work, tracker.recoverySnapshot())
            assertEquals("D2B6/T5M.01: noWrite", before, f.storage.raw()); assertEquals(writes, f.storage.storage.writes)
            resume.countDown()
            assertTrue(business.get(10, TimeUnit.SECONDS) is ControlStoreResult.Confirmed)
        } finally { resume.countDown(); worker.shutdownNow(); ControlReleaseFixtures.replaceLease(tracker, delegate) }
    }

    @Test fun T5M_02_managementLeaseWithPendingStateSeenFirstByOtherEntries() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val tracker = f.tracker
        val checkpoint = checkNotNull(f.store.checkpoint(c))
        f.armReadBack() // So the management Confirm(original) really enters the write scope, where it pauses.
        val pause = ControlStoreTestStorage.Pause(); f.storage.storage.pause = pause
        val management = async { f.store.abandonBeforeFirstConfirm(c, closure(c)) }
        controlTestTimeout("management write pause") { pause.reached.await() }
        try {
            assertEquals("fixture: pending published before Confirm", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
            assertTrue("fixture: management holds the lease", c in tracker.executing)
            val writes = f.storage.storage.writes; val accessBefore = f.boundary.accesses
            assertTrue("D2B6/T5M.02a: executeSeesPending", f.store.execute(c) is ControlStoreResult.TerminationPending)
            assertTrue("D2B6/T5M.02b: contextSeesPending",
                f.store.execute(c, NamespaceSettlementFixtures.context) is ControlStoreResult.TerminationPending)
            assertTrue("D2B6/T5M.02c: confirmPreviousSeesPending", f.store.confirmPrevious(c, checkpoint) is ControlStoreResult.TerminationPending)
            assertNull("D2B6/T5M.02d: noCheckpoint", f.store.checkpoint(c))
            assertEquals("D2B6/T5M.02e: secondAbandonOtherPath", CompletionRejectionReason.OtherManagementPath,
                (f.store.abandonBeforeFirstConfirm(c, closure(c)) as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/T5M.02f: retryInFlight", CompletionRejectionReason.InFlight,
                (f.store.retryTermination(c, closure(c)) as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/T5M.02: noDataStoreAccess", 0, f.boundary.accesses - accessBefore)
            assertEquals("D2B6/T5M.02: noExtraWrites", writes, f.storage.storage.writes)
        } finally { pause.release.complete(Unit) }
        assertTrue("D2B6/T5M.02: managementCompletes", management.await() is ControlCompletionResult.Completed)
        assertEquals(ControlCommandLifecycle.TERMINATED, c.lifecycleState)
    }
}
