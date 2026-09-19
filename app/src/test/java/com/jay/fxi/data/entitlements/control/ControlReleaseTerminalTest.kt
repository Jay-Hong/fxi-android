package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.simulatePending
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.simulateReleased
import java.io.File
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Terminal fixtures simulate memory closure only; no storage reclamation is claimed by unit 1. */
class ControlReleaseTerminalTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open() = ControlStoreTestStorage(File(folder.root, "terminal-${opened.size}.preferences_pb")).also { opened += it }
    @After fun close() = runBlocking { opened.forEach { it.close() } }
    private fun action(store: ControlRecordStore) = store.addition(ControlKind.RECOVERY_INTENT) { id ->
        literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
    }

    private suspend fun terminal(released: Boolean, entry: Int, otherOwner: Boolean = false, reissue: Boolean = false) {
        val o = open(); o.seed()
        val tracker = ControlCommandTracking.forOwner(o.owner)
        val store = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, 99) })
        val c = store.prepare(action(o.control))
        assertTrue(store.execute(c) is ControlStoreResult.Confirmed)
        val checkpoint = checkNotNull(store.checkpoint(c))
        val history = checkNotNull(tracker.findPrepared(c))
        val baseline = history.firstConfirmDiscontinuityCount
        simulatePending(tracker, c, ReleasePendingDescriptor.ExactMutations(history.expectedApplied as AppliedEvidence.Mutations))
        if (released) simulateReleased(tracker, c)
        val successor = if (reissue) store.prepare(action(o.control)) else null
        val receiver = if (otherOwner) open() else o
        val receiverTracking = ControlCommandTracking.forOwner(receiver.owner)
        val unrelated = receiver.control.prepare()
        receiverTracking.markUnresolved(unrelated)
        val receiverPending = receiver.control.prepare()
        simulatePending(receiverTracking, receiverPending)
        val beforeWork = receiverTracking.recoverySnapshot()
        val before = receiver.raw(); val writes = receiver.storage.writes
        val count = receiverTracking.evidenceDiscontinuityCount
        // A terminal precheck must bypass even a held business lease.
        assertTrue(receiverTracking.executing.add(c))
        val attempted = try { runCatching {
            when (entry) {
                0 -> receiver.control.execute(c)
                1 -> receiver.control.execute(c, NamespaceSettlementFixtures.context)
                else -> receiver.control.confirmPrevious(c, checkpoint)
            }
        } } finally { assertTrue(receiverTracking.executing.remove(c)) }
        assertNull("terminal precheck bypasses held lease", attempted.exceptionOrNull())
        val result = attempted.getOrThrow()
        assertEquals("terminal result", if (released) ControlStoreResult.Released::class.java else ControlStoreResult.ReleasePending::class.java, result.javaClass)
        assertSame(c, result.command)
        assertEquals(beforeWork.unresolvedCommands, result.localUnresolvedCommands)
        assertEquals(beforeWork.pendingReleases, result.localPendingReleases)
        assertEquals(beforeWork.unresolvedCommands, receiverTracking.snapshot())
        assertEquals(beforeWork.pendingReleases, receiverTracking.recoverySnapshot().pendingReleases)
        assertEquals(before, receiver.raw()); assertEquals(writes, receiver.storage.writes)
        assertEquals("terminal does not observe storage", count, receiverTracking.evidenceDiscontinuityCount)
        assertEquals(baseline, history.firstConfirmDiscontinuityCount); assertTrue(history.observedApplied.get())
        if (successor != null) assertSame(successor, tracker.findPrepared(successor)?.command)
    }

    @Test fun A11_releasedExecute() = runBlocking { terminal(true, 0) }
    @Test fun A11_releasedExecuteContext() = runBlocking { terminal(true, 1) }
    @Test fun A11_releasedConfirmPrevious() = runBlocking { terminal(true, 2) }
    @Test fun A12_pendingExecute() = runBlocking { terminal(false, 0) }
    @Test fun A12_pendingExecuteContext() = runBlocking { terminal(false, 1) }
    @Test fun A12_pendingConfirmPrevious() = runBlocking { terminal(false, 2) }
    @Test fun C17_oldReleasedExecute() = runBlocking { terminal(true, 0, otherOwner = true) }
    @Test fun C17_oldReleasedContext() = runBlocking { terminal(true, 1, otherOwner = true) }
    @Test fun C17_oldReleasedConfirmPrevious() = runBlocking { terminal(true, 2, otherOwner = true) }
    @Test fun C18_oldPendingExecute() = runBlocking { terminal(false, 0, otherOwner = true) }
    @Test fun C18_oldPendingContext() = runBlocking { terminal(false, 1, otherOwner = true) }
    @Test fun C18_oldPendingConfirmPrevious() = runBlocking { terminal(false, 2, otherOwner = true) }
    @Test fun M04_reusedIdOldExecuteStaysTerminal() = runBlocking { terminal(true, 0, reissue = true) }
    @Test fun M04_reusedIdOldContextStaysTerminal() = runBlocking { terminal(true, 1, reissue = true) }
    @Test fun M04_reusedIdOldCheckpointStaysTerminal() = runBlocking { terminal(true, 2, reissue = true) }

    private fun checkpointClosed(released: Boolean) {
        val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner); val c = o.control.prepare()
        assertNotNull(o.control.checkpoint(c))
        simulatePending(tracker, c)
        if (released) { ControlReleaseFixtures.released(c) } // Keep map entry to isolate the lifecycle guard.
        assertNull("closed ref cannot issue checkpoint", o.control.checkpoint(c))
        assertNotNull(tracker.findPrepared(c))
    }
    @Test fun A14_pendingCheckpointNotIssued() = checkpointClosed(false)
    @Test fun A14_releasedCheckpointNotIssued() = checkpointClosed(true)

    private suspend fun race(entry: Int, pendingOnly: Boolean = false) {
        val o = open() // Intentionally no control schema: entering storage increments observation count.
        val tracker = ControlCommandTracking.forOwner(o.owner)
        val c = o.control.prepare(action(o.control)); val checkpoint = o.control.checkpoint(c)
        val before = o.raw(); val writes = o.storage.writes
        val reached = CountDownLatch(1); val resume = CountDownLatch(1)
        val delegate = tracker.executing
        val first = AtomicBoolean(true)
        ControlReleaseFixtures.replaceLease(tracker, object : MutableSet<CommandRef> by delegate {
            override fun add(element: CommandRef): Boolean {
                if (element === c && first.compareAndSet(true, false)) {
                    reached.countDown(); check(resume.await(10, TimeUnit.SECONDS)) { "lease pause timed out" }
                }
                return delegate.add(element)
            }
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<ControlStoreResult> { runBlocking {
                when (entry) {
                    0 -> o.control.execute(c)
                    1 -> o.control.execute(c, NamespaceSettlementFixtures.context)
                    else -> o.control.confirmPrevious(c, checkpoint)
                }
            } }
            assertTrue("entry reached after retained precheck", reached.await(10, TimeUnit.SECONDS))
            simulatePending(tracker, c)
            if (!pendingOnly) simulateReleased(tracker, c)
            resume.countDown()
            val result = future.get(10, TimeUnit.SECONDS)
            assertEquals("lease recheck must return terminal", if (pendingOnly) ControlStoreResult.ReleasePending::class.java else ControlStoreResult.Released::class.java, result.javaClass)
            assertTrue("lease recheck precedes unresolved addition", tracker.snapshot().isEmpty())
            assertTrue(result.localUnresolvedCommands.isEmpty())
            assertEquals(if (pendingOnly) setOf(c) else emptySet<CommandRef>(), result.localPendingReleases)
            assertEquals(before, o.raw()); assertEquals(writes, o.storage.writes)
            assertEquals(BigInteger.ZERO, tracker.evidenceDiscontinuityCount)
            assertTrue("lease released on early return", tracker.executing.isEmpty())
        } finally { resume.countDown(); worker.shutdownNow(); ControlReleaseFixtures.replaceLease(tracker, delegate) }
    }
    @Test fun A15_executeRechecksBeforeUnresolved() = runBlocking { race(0) }
    @Test fun A15_contextRechecksBeforeUnresolved() = runBlocking { race(1) }
    @Test fun A15_confirmPreviousRechecksBeforeUnresolved() = runBlocking { race(2) }
    @Test fun A15_pendingRecheckBeforeUnresolved() = runBlocking { race(0, pendingOnly = true) }
}
