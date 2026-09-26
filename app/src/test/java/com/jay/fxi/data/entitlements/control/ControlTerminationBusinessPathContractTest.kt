package com.jay.fxi.data.entitlements.control

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.commands
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.replaceRecovery
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Contract-owned DataStore boundary counter: every data collection and updateData call during the measured window. */
internal class TerminationAccessCountingData(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    @Volatile var collects = 0
    @Volatile var updates = 0
    val total get() = collects + updates
    override val data: Flow<Preferences> get() = flow { collects++; emitAll(delegate.data) }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        updates++
        return delegate.updateData(transform)
    }
}

/**
 * Claude-owned 6-1 T5 contract, business-path part (skeleton v6 §5 run/terminalResult/withRecoveryWork/attach;
 * revision 06 §3.2·§3.3·§3.4·§3.5 G19). execute (both overloads) and confirmPrevious meet TERMINATION_PENDING/
 * TERMINATED as closed results before the lease — without touching even a held business lease — and again after the
 * lease before temporarily adding unresolved work. The closed result copies U/P from one recovery snapshot (including
 * c ∈ U∩P), makes no DataStore data collection or updateData call (counted at the DataStore boundary; fixtures also
 * have no control schema, so an owner read would move the observation count), and
 * never reads the terminated body. The new terminal results cannot be wrapped as storage outcomes and carry no
 * diagnostic. Termination is simulated in memory (cell CAS + U/P/commands edits); it is not owner-termination evidence
 * and the confirmed-ref fixture below is not reachable through the 6-1B NeverConfirm entry. The management-lease
 * InFlight part of T5 is 6-1B. The implementation thread reads but does not edit this file.
 */
class ControlTerminationBusinessPathContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private var counter: TerminationAccessCountingData? = null
    private fun open() = ControlStoreTestStorage(File(folder.root, "termination-${opened.size}.preferences_pb")) {
        TerminationAccessCountingData(it).also { counting -> counter = counting }
    }.also { opened += it }
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
    /** Memory-only stand-ins for the 6-1B owner path: P ∪ {c} then pending; TERMINATED then U−{c}, P−{c}, exact removal. */
    private fun simulateTerminationPending(tracker: ControlCommandTracking, c: CommandRef) {
        val work = tracker.recoverySnapshot()
        replaceRecovery(tracker, LocalRecoveryWork(work.unresolvedCommands, work.pendingReleases + c))
        invoke(c, "beginTermination")
    }
    private fun simulateTerminated(tracker: ControlCommandTracking, c: CommandRef) {
        val tracked = checkNotNull(tracker.findPrepared(c))
        invoke(c, "completeTermination")
        val work = tracker.recoverySnapshot()
        replaceRecovery(tracker, LocalRecoveryWork(work.unresolvedCommands - c, work.pendingReleases - c))
        check(commands(tracker).remove(c.id, tracked))
    }
    private fun expectedType(terminated: Boolean) =
        if (terminated) ControlStoreResult.Terminated::class.java else ControlStoreResult.TerminationPending::class.java
    private suspend fun call(store: ControlRecordStore, c: CommandRef, checkpoint: ControlCommandCheckpoint, entry: Int) = when (entry) {
        0 -> store.execute(c)
        1 -> store.execute(c, NamespaceSettlementFixtures.context)
        else -> store.confirmPrevious(c, checkpoint)
    }

    /** [inUnresolved] puts c in U before pending (a first business Confirm read failure left it unresolved) → c ∈ U∩P. */
    private suspend fun precheck(terminated: Boolean, entry: Int, inUnresolved: Boolean) {
        val o = open() // No control schema: any owner read moves the observation count.
        val tracker = ControlCommandTracking.forOwner(o.owner)
        val c = o.control.prepare(action(o.control))
        val checkpoint = checkNotNull(o.control.checkpoint(c))
        val unrelatedU = o.control.prepare(); tracker.markUnresolved(unrelatedU)
        val unrelatedP = o.control.prepare()
        if (inUnresolved) tracker.markUnresolved(c)
        simulateTerminationPending(tracker, c)
        run { val w = tracker.recoverySnapshot(); replaceRecovery(tracker, LocalRecoveryWork(w.unresolvedCommands, w.pendingReleases + unrelatedP)) }
        if (inUnresolved) assertTrue("fixture: c ∈ U∩P", c in tracker.recoverySnapshot().unresolvedCommands && c in tracker.recoverySnapshot().pendingReleases)
        if (terminated) simulateTerminated(tracker, c)
        val beforeWork = tracker.recoverySnapshot()
        if (terminated) {
            assertEquals("fixture: exact c only left U", setOf(unrelatedU), beforeWork.unresolvedCommands)
            assertEquals("fixture: exact c only left P", setOf(unrelatedP), beforeWork.pendingReleases)
        }
        val before = o.raw(); val writes = o.storage.writes; val count = tracker.evidenceDiscontinuityCount
        val access = checkNotNull(counter); val accessBefore = access.total
        val delegate = tracker.executing; val touched = AtomicInteger(0)
        check(delegate.add(c)) // A held business lease that the pre-check must neither need nor touch.
        ControlReleaseFixtures.replaceLease(tracker, object : MutableSet<CommandRef> by delegate {
            override fun add(element: CommandRef): Boolean { touched.incrementAndGet(); return delegate.add(element) }
            override fun remove(element: CommandRef): Boolean { touched.incrementAndGet(); return delegate.remove(element) }
            override fun contains(element: CommandRef): Boolean { touched.incrementAndGet(); return delegate.contains(element) }
        })
        val attempted = try { runCatching { call(o.control, c, checkpoint, entry) } }
        finally { ControlReleaseFixtures.replaceLease(tracker, delegate); delegate.remove(c) }
        val accessDuring = access.total - accessBefore
        val id = "pre${if (terminated) "T" else "P"}${if (inUnresolved) "UP" else ""}$entry"
        assertNull("D2B6/T5.$id: closedBeforeLease", attempted.exceptionOrNull())
        val result = attempted.getOrThrow()
        assertEquals("D2B6/T5.$id: closedBeforeLease", expectedType(terminated), result.javaClass)
        assertSame(c, result.command)
        assertEquals("D2B6/T5.$id: leaseUntouched", 0, touched.get())
        assertEquals("D2B6/T5.$id: noDataStoreAccess", 0, accessDuring)
        assertEquals("D2B6/T5.$id: oneSnapshot", beforeWork.unresolvedCommands, result.localUnresolvedCommands)
        assertEquals("D2B6/T5.$id: oneSnapshot", beforeWork.pendingReleases, result.localPendingReleases)
        assertEquals("D2B6/T5.$id: setsUnchanged", beforeWork, tracker.recoverySnapshot())
        assertEquals("D2B6/T5.$id: noStorageRead", count, tracker.evidenceDiscontinuityCount)
        assertEquals("D2B6/T5.$id: noStorageWrite", before, o.raw()); assertEquals(writes, o.storage.writes)
    }
    @Test fun T5_01_pendingExecute() = runBlocking { precheck(false, 0, false) }
    @Test fun T5_02_pendingExecuteContext() = runBlocking { precheck(false, 1, false) }
    @Test fun T5_03_pendingConfirmPrevious() = runBlocking { precheck(false, 2, false) }
    @Test fun T5_04_terminatedExecute() = runBlocking { precheck(true, 0, false) }
    @Test fun T5_05_terminatedExecuteContext() = runBlocking { precheck(true, 1, false) }
    @Test fun T5_06_terminatedConfirmPrevious() = runBlocking { precheck(true, 2, false) }
    @Test fun T5_14_pendingInUnresolvedExecute() = runBlocking { precheck(false, 0, true) }
    @Test fun T5_15_pendingInUnresolvedExecuteContext() = runBlocking { precheck(false, 1, true) }
    @Test fun T5_16_pendingInUnresolvedConfirmPrevious() = runBlocking { precheck(false, 2, true) }
    @Test fun T5_17_terminatedFromUnresolvedExecute() = runBlocking { precheck(true, 0, true) }
    @Test fun T5_18_terminatedFromUnresolvedExecuteContext() = runBlocking { precheck(true, 1, true) }
    @Test fun T5_19_terminatedFromUnresolvedConfirmPrevious() = runBlocking { precheck(true, 2, true) }

    private suspend fun postLease(terminated: Boolean, entry: Int) {
        val o = open() // No control schema: entering storage would move the observation count.
        val tracker = ControlCommandTracking.forOwner(o.owner)
        val c = o.control.prepare(action(o.control)); val checkpoint = checkNotNull(o.control.checkpoint(c))
        val before = o.raw(); val writes = o.storage.writes
        val reached = CountDownLatch(1); val resume = CountDownLatch(1)
        val delegate = tracker.executing; val first = AtomicBoolean(true)
        ControlReleaseFixtures.replaceLease(tracker, object : MutableSet<CommandRef> by delegate {
            override fun add(element: CommandRef): Boolean {
                if (element === c && first.compareAndSet(true, false)) {
                    reached.countDown(); check(resume.await(10, TimeUnit.SECONDS)) { "lease pause timed out" }
                }
                return delegate.add(element)
            }
        })
        val worker = Executors.newSingleThreadExecutor()
        val id = "post${if (terminated) "T" else "P"}$entry"
        try {
            val access = checkNotNull(counter); val accessBefore = access.total
            val future = worker.submit<ControlStoreResult> { runBlocking { call(o.control, c, checkpoint, entry) } }
            assertTrue("entry reached after retained precheck", reached.await(10, TimeUnit.SECONDS))
            simulateTerminationPending(tracker, c)
            if (terminated) simulateTerminated(tracker, c)
            resume.countDown()
            val result = future.get(10, TimeUnit.SECONDS)
            assertEquals("D2B6/T5.$id: noDataStoreAccess", 0, access.total - accessBefore)
            assertEquals("D2B6/T5.$id: closedAfterLease", expectedType(terminated), result.javaClass)
            assertTrue("D2B6/T5.$id: recheckPrecedesUnresolved", tracker.snapshot().isEmpty())
            assertTrue(result.localUnresolvedCommands.isEmpty())
            assertEquals(if (terminated) emptySet<CommandRef>() else setOf(c), result.localPendingReleases)
            assertEquals("D2B6/T5.$id: noStorage", before, o.raw()); assertEquals(writes, o.storage.writes)
            assertEquals("D2B6/T5.$id: noObservation", BigInteger.ZERO, tracker.evidenceDiscontinuityCount)
            assertTrue("D2B6/T5.$id: leaseReleased", tracker.executing.isEmpty())
        } finally { resume.countDown(); worker.shutdownNow(); ControlReleaseFixtures.replaceLease(tracker, delegate) }
    }
    @Test fun T5_07_pendingExecuteAfterLease() = runBlocking { postLease(false, 0) }
    @Test fun T5_08_pendingExecuteContextAfterLease() = runBlocking { postLease(false, 1) }
    @Test fun T5_09_pendingConfirmPreviousAfterLease() = runBlocking { postLease(false, 2) }
    @Test fun T5_10_terminatedExecuteAfterLease() = runBlocking { postLease(true, 0) }
    @Test fun T5_11_terminatedExecuteContextAfterLease() = runBlocking { postLease(true, 1) }
    @Test fun T5_12_terminatedConfirmPreviousAfterLease() = runBlocking { postLease(true, 2) }

    @Test fun T5_13_registrationRejectsClosedRefs() {
        val o = open(); val tracker = ControlCommandTracking.forOwner(o.owner)
        for ((id, terminated) in listOf("13a" to false, "13b" to true)) {
            val c = o.control.prepare(action(o.control))
            simulateTerminationPending(tracker, c); if (terminated) simulateTerminated(tracker, c)
            val failure = runCatching { tracker.registerPrepared(c) }.exceptionOrNull()
            assertTrue("D2B6/T5.$id: noReRegistration", failure is IllegalStateException)
            // The state guard, not the UUID-collision guard, must refuse (the pending ref is still registered).
            assertEquals("D2B6/T5.$id: noReRegistration", "closed command cannot be registered", failure?.message)
        }
    }

    @Test fun T5_20_terminalResultsCannotBeStorageOutcomes() {
        val o = open(); val c = o.control.prepare()
        val work = LocalRecoveryWork(setOf(o.control.prepare()), setOf(o.control.prepare()))
        val method = ControlRecordStore::class.java.getDeclaredMethod("withRecoveryWork", ControlStoreResult::class.java, LocalRecoveryWork::class.java)
            .apply { isAccessible = true }
        for ((id, result) in listOf<Pair<String, ControlStoreResult>>(
            "20a" to ControlStoreResult.TerminationPending(c, emptySet(), emptySet()),
            "20b" to ControlStoreResult.Terminated(c, emptySet(), emptySet())
        )) {
            val failure = runCatching { method.invoke(o.control, result, work) }.exceptionOrNull()
            assertEquals("D2B6/T5.$id: notStorageOutcome", "terminal results cannot be storage outcomes",
                (failure as? InvocationTargetException)?.targetException?.message)
        }
    }

    @Test fun T5_21_terminalResultsCarryNoDiagnostic() {
        val command = F.command(F.descriptor(transition = LifecycleTransition.SETTLE_QUERY, targets = listOf(F.createRequest)))
        val diagnostic = checkNotNull(ControlLifecycleDiagnostics.observe(command, TrackedControlCommand(command), null, null))
        for ((id, result) in listOf<Pair<String, ControlStoreResult>>(
            "21a" to ControlStoreResult.TerminationPending(command, emptySet(), emptySet()),
            "21b" to ControlStoreResult.Terminated(command, emptySet(), emptySet())
        )) {
            assertSame("D2B6/T5.$id: returnedAsIs", result, ControlLifecycleDiagnostics.attach(result, diagnostic))
            assertFalse("D2B6/T5.$id: noDiagnosticField",
                result.javaClass.declaredFields.any { it.name == "lifecycleDiagnostic" })
        }
    }
}
