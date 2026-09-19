package com.jay.fxi.data.entitlements.control

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Schedules are fixed by latches/deferred storage boundaries, never elapsed sleeps. */
class ControlReleaseRaceTest : ReleaseOwnerTestBase() {
    private suspend fun leaseRace(entry: Int, ownsLease: Boolean) {
        o.seed(); val c = confirmed(); val checkpoint = o.control.checkpoint(c)
        val reached = CountDownLatch(1); val resume = CountDownLatch(1); val first = AtomicBoolean(true)
        val delegate = tracking.executing; val worker = Executors.newSingleThreadExecutor()
        ControlReleaseFixtures.replaceLease(tracking, object : MutableSet<CommandRef> by delegate {
            override fun add(element: CommandRef): Boolean {
                if (element === c && first.compareAndSet(true, false)) {
                    if (ownsLease) check(delegate.add(element))
                    reached.countDown(); check(resume.await(10, TimeUnit.SECONDS))
                    if (ownsLease) return true
                }
                return delegate.add(element)
            }
        })
        try {
            val caller = worker.submit<Any> { runReleaseTest {
                when (entry) {
                    0 -> o.control.execute(c)
                    1 -> o.control.execute(c, NamespaceSettlementFixtures.context)
                    2 -> o.control.confirmPrevious(c, checkpoint)
                    else -> o.control.releaseAfterConsumption(c)
                }
            } }
            assertTrue(reached.await(10, TimeUnit.SECONDS))
            if (ownsLease) rejected(c, ReleaseRejectionReason.InFlight) else released(c)
            val before = disk(); val count = tracking.evidenceDiscontinuityCount
            resume.countDown(); val result = caller.get(10, TimeUnit.SECONDS)
            if (ownsLease) assertTrue(result is ControlStoreResult.Confirmed)
            else if (entry == 3) assertTrue(result is ControlCommandReleaseResult.AlreadyReleased)
            else assertTrue(result is ControlStoreResult.Released)
            assertTrue("recheck precedes unresolved publication", tracking.snapshot().isEmpty())
            assertTrue(tracking.executing.isEmpty()); assertEquals(before, disk()); assertEquals(count, tracking.evidenceDiscontinuityCount)
        } finally {
            resume.countDown(); worker.shutdownNow()
            try { assertTrue("lease worker terminated", worker.awaitTermination(10, TimeUnit.SECONDS)) }
            finally { ControlReleaseFixtures.replaceLease(tracking, delegate) }
        }
    }
    @Test fun A01_executeLeaseExcludesRelease() = runReleaseTest<Unit> { leaseRace(0, true) }
    @Test fun A01_contextLeaseExcludesRelease() = runReleaseTest<Unit> { leaseRace(1, true) }
    @Test fun A01_previousConfirmationLeaseExcludesRelease() = runReleaseTest<Unit> { leaseRace(2, true) }
    @Test fun A15_executeRechecksAfterRealRelease() = runReleaseTest<Unit> { leaseRace(0, false) }
    @Test fun A15_contextRechecksAfterRealRelease() = runReleaseTest<Unit> { leaseRace(1, false) }
    @Test fun A15_previousRechecksAfterRealRelease() = runReleaseTest<Unit> { leaseRace(2, false) }
    @Test fun A13_releaseRechecksTerminalBeforeLatestIdentity() = runReleaseTest<Unit> { leaseRace(3, false) }

    private suspend fun pendingPublication(entry: Int) = kotlinx.coroutines.coroutineScope {
        o.seed(); val c = confirmed(); val checkpoint = o.control.checkpoint(c); val before = disk()
        val pause = ControlStoreTestStorage.Pause(); o.storage.pause = pause
        val caller = async { o.control.releaseAfterConsumption(c) }
        try {
            withTimeout(10_000) { pause.reached.await() }
            assertEquals(before, disk()) // Write block ran, but FileStorage scope has not landed.
            assertEquals(ControlCommandLifecycle.RELEASE_PENDING, c.lifecycleState)
            assertNotNull(history(c).releaseDescriptor); assertTrue(c in tracking.recoverySnapshot().pendingReleases)
            val result = controlTestTimeout("business entry while release holds owner") {
                when (entry) {
                    0 -> o.control.execute(c)
                    1 -> o.control.execute(c, NamespaceSettlementFixtures.context)
                    else -> o.control.confirmPrevious(c, checkpoint)
                }
            }
            assertTrue(result is ControlStoreResult.ReleasePending)
            assertEquals(setOf(c), result.localPendingReleases); assertTrue(result.localUnresolvedCommands.isEmpty())
            rejected(c, ReleaseRejectionReason.InFlight)
        } finally { pause.release.complete(Unit) }
        assertTrue(withTimeout(10_000) { caller.await() } is ControlCommandReleaseResult.Released)
        assertFalse(c in tracking.recoverySnapshot().pendingReleases); assertNull(tracking.findPrepared(c))
    }
    @Test fun C07_pendingPublicationPrecedesConcurrentExecute() = runReleaseTest<Unit> { pendingPublication(0) }
    @Test fun C07_pendingPublicationPrecedesConcurrentContextExecute() = runReleaseTest<Unit> { pendingPublication(1) }
    @Test fun C07_pendingPublicationPrecedesConcurrentPreviousConfirmation() = runReleaseTest<Unit> { pendingPublication(2) }

    @Test fun M04_cleanupRaceDoesNotRemoveSameIdSuccessor() = runReleaseTest<Unit> {
        o.seed(); val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, 555) })
        val c = store.prepare(add().actions.single()); confirmed(c)
        val map = ControlReleaseFixtures.commands(tracking)
        val removed = CountDownLatch(1); val resume = CountDownLatch(1)
        val replacement = object : ConcurrentHashMap<String, TrackedControlCommand>(map) {
            override fun remove(key: String, value: TrackedControlCommand): Boolean {
                val result = super.remove(key, value)
                if (key == c.id) { removed.countDown(); check(resume.await(10, TimeUnit.SECONDS)) }
                return result
            }
        }
        ControlCommandTracking::class.java.getDeclaredField("commands").apply { isAccessible = true }.set(tracking, replacement)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val caller = worker.submit<ControlCommandReleaseResult> { runReleaseTest { store.releaseAfterConsumption(c) } }
            assertTrue(removed.await(10, TimeUnit.SECONDS))
            assertEquals(ControlCommandLifecycle.RELEASED, c.lifecycleState)
            val b = store.prepare(add().actions.single()); val first = history(b)
            assertTrue(controlTestTimeout("release during exact cleanup") { store.releaseAfterConsumption(c) } is ControlCommandReleaseResult.AlreadyReleased)
            assertTrue(controlTestTimeout("execute during exact cleanup") { store.execute(c) } is ControlStoreResult.Released)
            resume.countDown(); assertTrue(caller.get(10, TimeUnit.SECONDS) is ControlCommandReleaseResult.Released)
            assertSame(first, history(b)); assertEquals(ControlCommandLifecycle.RETAINED, b.lifecycleState)
        } finally {
            resume.countDown(); worker.shutdownNow()
            assertTrue("cleanup worker terminated", worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
    @Test fun M01_conditionalCleanupPreservesAReplacedExactHistory() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); val original = history(c)
        val map = ControlReleaseFixtures.commands(tracking)
        val other = CommandRef(c.id, c.body, c.ownerTrackingLifetimeId); val successor = TrackedControlCommand(other)
        val replacement = object : ConcurrentHashMap<String, TrackedControlCommand>(map) {
            override fun remove(key: String, value: TrackedControlCommand): Boolean {
                // Adversarial replacement immediately before the atomic compare-and-remove.
                put(key, successor)
                return super.remove(key, value)
            }
            override fun remove(key: String): TrackedControlCommand? { put(key, successor); return super.remove(key) }
        }
        ControlCommandTracking::class.java.getDeclaredField("commands").apply { isAccessible = true }.set(tracking, replacement)
        assertTrue(o.control.releaseAfterConsumption(c) is ControlCommandReleaseResult.Released)
        assertSame(successor, replacement[c.id]); assertNotSame(original, replacement[c.id])
        assertFalse(c in tracking.recoverySnapshot().pendingReleases)
    }
}
