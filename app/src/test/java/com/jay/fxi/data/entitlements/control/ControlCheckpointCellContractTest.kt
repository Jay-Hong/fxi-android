package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.commands
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.replaceRecovery
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-1 T6 contract (skeleton v6 §5 checkpoint; revision 06 §3.4 G25). checkpoint takes no execution lease
 * and does not read its busy state; it looks up the exact tracked first, then captures the (state, body) cell once. The
 * RETAINED view capture is the issuance linearization point: removal before lookup → null, termination published
 * between lookup and capture → closed view → null, termination (with body detach and exact removal) after the capture
 * but before assembly still completes the in-flight checkpoint from the captured inputs, and a held RETAINED business
 * lease is neither read nor touched. The exact lookup precedes the view judgment. The single cell read itself is pinned
 * by the structure test. Termination is simulated in memory because the
 * owner termination entry belongs to 6-1B. The existing storage-boundary checkpoint regressions stay in their suites.
 * The implementation thread reads but does not edit this file.
 */
class ControlCheckpointCellContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open() = ControlStoreTestStorage(File(folder.root, "checkpoint-${opened.size}.preferences_pb")).also { opened += it }
    @After fun close() = runBlocking { opened.forEach { it.close() } }
    private fun action(store: ControlRecordStore) = store.addition(ControlKind.RECOVERY_INTENT) { id ->
        literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
    }
    private fun invoke(target: Any, name: String) {
        try {
            target.javaClass.declaredMethods.single { it.name.substringBefore('$') == name && it.parameterCount == 0 }
                .apply { isAccessible = true }.invoke(target)
        } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    }
    private fun pending(tracker: ControlCommandTracking, c: CommandRef) {
        val work = tracker.recoverySnapshot()
        replaceRecovery(tracker, LocalRecoveryWork(work.unresolvedCommands, work.pendingReleases + c))
        invoke(c, "beginTermination")
    }
    private fun terminate(tracker: ControlCommandTracking, c: CommandRef, removeEntry: Boolean = true) {
        val tracked = checkNotNull(tracker.findPrepared(c))
        invoke(c, "completeTermination")
        val work = tracker.recoverySnapshot()
        replaceRecovery(tracker, LocalRecoveryWork(work.unresolvedCommands - c, work.pendingReleases - c))
        if (removeEntry) check(commands(tracker).remove(c.id, tracked))
    }
    private fun replaceCommands(tracker: ControlCommandTracking, map: ConcurrentHashMap<String, TrackedControlCommand>) {
        ControlCommandTracking::class.java.getDeclaredField("commands").apply { isAccessible = true }.set(tracker, map)
    }

    @Test fun T6_01_removalBeforeLookupIssuesNothing() {
        val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner); val c = o.control.prepare()
        pending(tracker, c); terminate(tracker, c)
        assertNull("D2B6/T6.01: removedBeforeLookup", o.control.checkpoint(c))
    }

    @Test fun T6_01b_exactLookupPrecedesTheViewJudgment() {
        // A still-registered pending ref: an implementation that judges the state before the lookup never calls get.
        val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner); val c = o.control.prepare()
        pending(tracker, c)
        val original = commands(tracker); val lookups = AtomicInteger(0)
        replaceCommands(tracker, object : ConcurrentHashMap<String, TrackedControlCommand>(original) {
            override fun get(key: String): TrackedControlCommand? {
                if (key == c.id) lookups.incrementAndGet()
                return super.get(key)
            }
        })
        try {
            assertNull("D2B6/T6.01b: pendingIsClosed", o.control.checkpoint(c))
            assertEquals("D2B6/T6.01b: lookupFirst", 1, lookups.get())
        } finally { replaceCommands(tracker, original) }
    }

    @Test fun T6_02_terminationBetweenLookupAndCaptureIsAClosedView() {
        for ((id, terminated) in listOf("02a" to false, "02b" to true)) {
            val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner); val c = o.control.prepare()
            val original = commands(tracker); val armed = AtomicBoolean(true)
            replaceCommands(tracker, object : ConcurrentHashMap<String, TrackedControlCommand>(original) {
                override fun get(key: String): TrackedControlCommand? {
                    val found = super.get(key)
                    // Publish termination right after the exact lookup succeeded, before the cell capture.
                    if (found?.command === c && armed.compareAndSet(true, false)) {
                        pending(tracker, c); if (terminated) terminate(tracker, c, removeEntry = false)
                    }
                    return found
                }
            })
            try {
                val issued = runCatching { o.control.checkpoint(c) }
                assertNull("D2B6/T6.$id: closedViewNoBodyRead", issued.exceptionOrNull())
                assertNull("D2B6/T6.$id: closedViewIsNull", issued.getOrThrow())
                assertTrue("D2B6/T6.$id: seamReached", !armed.get())
            } finally { replaceCommands(tracker, original) }
        }
    }

    @Test fun T6_03_terminationAfterCaptureCompletesFromCapturedInputs() {
        val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner)
        val c = o.control.prepare(action(o.control)); val tracked = checkNotNull(tracker.findPrepared(c))
        val fixed = tracked.targets.get(); val armed = AtomicBoolean(true)
        fun fire() { if (armed.compareAndSet(true, false)) { pending(tracker, c); terminate(tracker, c) } }
        // AtomicReference.get is final, so the seam is the targets copy made while assembling the checkpoint — after the
        // RETAINED view capture: termination, body detach and exact removal are published there.
        val instrumented = object : List<ControlCommandTarget?> by fixed {
            override fun get(index: Int): ControlCommandTarget? { fire(); return fixed[index] }
            override fun iterator(): Iterator<ControlCommandTarget?> { fire(); return fixed.iterator() }
            override fun listIterator(): ListIterator<ControlCommandTarget?> { fire(); return fixed.listIterator() }
            override fun listIterator(index: Int): ListIterator<ControlCommandTarget?> { fire(); return fixed.listIterator(index) }
        }
        tracked.targets.set(instrumented)
        try {
            val issued = runCatching { o.control.checkpoint(c) }
            assertNull("D2B6/T6.03: noBodyReadAfterCapture", issued.exceptionOrNull())
            val checkpoint = issued.getOrThrow()
            assertTrue("D2B6/T6.03: seamReached", !armed.get())
            assertEquals("D2B6/T6.03: terminalPublishedDuringCall", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
            assertTrue("D2B6/T6.03: capturedInputsComplete", checkpoint != null)
            assertSame("D2B6/T6.03: capturedInputsComplete", c, checkpoint!!.command)
            assertEquals("D2B6/T6.03: capturedInputsComplete", fixed, checkpoint.targets)
            assertEquals("D2B6/T6.03: capturedInputsComplete", false, checkpoint.confirmationRequested)
            assertNull("D2B6/T6.03: noNewIssuanceAfterTerminal", o.control.checkpoint(c))
        } finally { tracked.targets.set(fixed) }
    }

    @Test fun T6_04_heldBusinessLeaseDoesNotBlockIssuance() {
        val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner); val c = o.control.prepare()
        val delegate = tracker.executing; val touched = AtomicBoolean(false)
        check(delegate.add(c))
        ControlReleaseFixtures.replaceLease(tracker, object : MutableSet<CommandRef> by delegate {
            override fun add(element: CommandRef): Boolean { touched.set(true); return delegate.add(element) }
            override fun remove(element: CommandRef): Boolean { touched.set(true); return delegate.remove(element) }
            override fun contains(element: CommandRef): Boolean { touched.set(true); return delegate.contains(element) }
        })
        try {
            assertNotNull("D2B6/T6.04: issuedDuringBusinessLease", o.control.checkpoint(c))
            assertTrue("D2B6/T6.04: leaseNotRead", !touched.get())
        } finally { ControlReleaseFixtures.replaceLease(tracker, delegate); delegate.remove(c) }
    }

    @Test fun T6_05_pendingViewIsNullWithoutBodyReliance() {
        val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner); val c = o.control.prepare()
        pending(tracker, c)
        assertNull("D2B6/T6.05: pendingIsClosed", o.control.checkpoint(c))
        assertNotNull(tracker.findPrepared(c))
    }
}
