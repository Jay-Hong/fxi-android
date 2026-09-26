package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.commands
import com.jay.fxi.data.entitlements.control.ControlReleaseFixtures.replaceRecovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-1 T2 contract (skeleton v6 §2·§5; revision 06 §2 AlreadyTerminated, §3.3, §3.5). The general release
 * keeps its own order and rules and meets the new states as: TERMINATION_PENDING → Rejected(OtherManagementPath),
 * TERMINATED → the authority-free closed result AlreadyTerminated. In the pure eligibility this comes after the lifetime
 * check and before the lease, registration, body and business checks; in releaseAfterConsumption before the lease
 * (no lease access, no DataStore access) and again after it (no DataStore access). The terminated body is never read.
 * DataStore data collections and updateData calls are counted at the boundary; the fixtures also have no control
 * schema, so an owner read would additionally move the observation count. RELEASED/
 * RELEASE_PENDING/ROTATION expectations stay locked by the existing release suites. The implementation thread reads
 * but does not edit this file.
 */
class ControlReleaseTerminationStateContractTest : ReleaseOwnerTestBase() {
    private fun beginTermination(c: CommandRef) = invoke(c, "beginTermination")
    private fun completeTermination(c: CommandRef) = invoke(c, "completeTermination")
    private fun invoke(target: Any, name: String) {
        try {
            target.javaClass.declaredMethods.single { it.name.substringBefore('$') == name && it.parameterCount == 0 }
                .apply { isAccessible = true }.invoke(target)
        } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    }
    private fun rotationTracked(): TrackedControlCommand {
        val input = RotateAndSettleNamespaces(emptyList(), NamespaceSettlementFixtures.fence,
            NamespaceSettlementFixtures.life, NamespaceSettlementFixtures.demand, "op", "d", null, null)
        val ref = CommandRef("op", ControlCommandBody.RotateAndSettle(input), OwnerTrackingLifetimeId.issue())
        return TrackedControlCommand(ref).apply { confirmed.set(true) }
    }
    private fun eligibility(ref: CommandRef, life: OwnerTrackingLifetimeId = ref.ownerTrackingLifetimeId,
        registered: TrackedControlCommand? = null, busy: Boolean = true, unresolved: Boolean = true) =
        ControlCommandReleaseEligibility.decide(ref, life, registered, busy, unresolved)
    private fun otherPath(id: String, decision: ControlCommandReleaseEligibility.Decision) =
        assertEquals("D2B6/T2.$id: otherManagementPath",
            ControlCommandReleaseEligibility.Decision.Rejected(ReleaseRejectionReason.OtherManagementPath), decision)
    private fun alreadyTerminated(id: String, decision: ControlCommandReleaseEligibility.Decision) =
        assertEquals("D2B6/T2.$id: alreadyTerminated", ControlCommandReleaseEligibility.Decision.AlreadyTerminated, decision)
    /** Memory-only stand-ins for the 6-1B owner path. */
    private fun simulatePending(tracker: ControlCommandTracking, c: CommandRef) {
        val work = tracker.recoverySnapshot()
        replaceRecovery(tracker, LocalRecoveryWork(work.unresolvedCommands, work.pendingReleases + c))
        beginTermination(c)
    }
    private fun simulateTerminated(tracker: ControlCommandTracking, c: CommandRef) {
        val tracked = checkNotNull(tracker.findPrepared(c))
        completeTermination(c)
        val work = tracker.recoverySnapshot()
        replaceRecovery(tracker, LocalRecoveryWork(work.unresolvedCommands - c, work.pendingReleases - c))
        check(commands(tracker).remove(c.id, tracked))
    }
    private class Counted(val storage: ControlStoreTestStorage, val access: TerminationAccessCountingData) {
        val tracker get() = ControlCommandTracking.forOwner(storage.owner)
        fun add(): CommandRef = storage.control.prepare(storage.control.addition(ControlKind.RECOVERY_INTENT) { id ->
            literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id))
        })
    }
    private fun counted(name: String): Counted {
        var access: TerminationAccessCountingData? = null
        val storage = ControlStoreTestStorage(File(folder.root, "counted-$name.preferences_pb")) {
            TerminationAccessCountingData(it).also { counting -> access = counting }
        }.also { opened += it }
        return Counted(storage, checkNotNull(access))
    }
    private fun assertClosedRelease(id: String, c: CommandRef, terminated: Boolean, result: ControlCommandReleaseResult,
        work: LocalRecoveryWork) {
        if (terminated) {
            assertEquals("D2B6/T2.$id: alreadyTerminated",
                ControlCommandReleaseResult.AlreadyTerminated(c, work.unresolvedCommands, work.pendingReleases), result)
        } else {
            assertTrue("D2B6/T2.$id: otherManagementPath $result", result is ControlCommandReleaseResult.Rejected)
            result as ControlCommandReleaseResult.Rejected
            assertEquals("D2B6/T2.$id: otherManagementPath", ReleaseRejectionReason.OtherManagementPath, result.reason)
            assertEquals("D2B6/T2.$id: otherManagementPath", ControlCommandLifecycle.TERMINATION_PENDING, result.state)
            assertSame(c, result.command)
            assertEquals(work.unresolvedCommands, result.localUnresolvedCommands)
            assertEquals(work.pendingReleases, result.localPendingReleases)
            assertEquals("D2B6/T2.$id: noObservation", null, result.observation)
        }
    }

    @Test fun T2_01_terminationPendingPrecedesLeaseBodyAndBusinessChecks() {
        // Each case would otherwise yield a different reason: InFlight / UnsupportedCommandKind / Unresolved.
        val a = ControlReleaseFixtures.fixture().command.also { beginTermination(it) }
        otherPath("01a", eligibility(a, registered = null, busy = true, unresolved = true))
        val rotation = rotationTracked().also { beginTermination(it.command) }
        otherPath("01b", eligibility(rotation.command, registered = rotation, busy = false, unresolved = false))
        val business = ControlReleaseFixtures.fixture().also { it.confirmed.set(false); beginTermination(it.command) }
        otherPath("01c", eligibility(business.command, registered = business, busy = false, unresolved = true))
    }

    @Test fun T2_02_terminatedIsClosedBeforeLeaseBodyAndBusinessChecks() {
        val a = ControlReleaseFixtures.fixture().command.also { beginTermination(it); completeTermination(it) }
        alreadyTerminated("02a", eligibility(a, registered = null, busy = true, unresolved = true))
        val rotation = rotationTracked().also { beginTermination(it.command); completeTermination(it.command) }
        alreadyTerminated("02b", eligibility(rotation.command, registered = rotation, busy = false, unresolved = false))
        val business = ControlReleaseFixtures.fixture().also {
            it.confirmed.set(false); beginTermination(it.command); completeTermination(it.command)
        }
        alreadyTerminated("02c", eligibility(business.command, registered = business, busy = false, unresolved = true))
    }

    @Test fun T2_03_wrongLifetimeStillWins() {
        for ((id, c) in listOf(
            "03a" to ControlReleaseFixtures.fixture().command.also { beginTermination(it) },
            "03b" to ControlReleaseFixtures.fixture().command.also { beginTermination(it); completeTermination(it) }
        )) {
            assertEquals("D2B6/T2.$id: lifetimeFirst",
                ControlCommandReleaseEligibility.Decision.Rejected(ReleaseRejectionReason.WrongTrackerLifetime),
                eligibility(c, life = OwnerTrackingLifetimeId.issue()))
        }
    }

    @Test fun T2_04_storeClosesNewStatesBeforeTheLease() = runReleaseTest<Unit> {
        for ((id, terminated) in listOf("04a" to false, "04b" to true)) {
            val f = counted(id); val tracker = f.tracker; val c = f.add()
            simulatePending(tracker, c); if (terminated) simulateTerminated(tracker, c)
            val delegate = tracker.executing; val touched = AtomicInteger(0)
            check(delegate.add(c)) // A held lease that the pre-check must neither need nor touch.
            ControlReleaseFixtures.replaceLease(tracker, object : MutableSet<CommandRef> by delegate {
                override fun add(element: CommandRef): Boolean { touched.incrementAndGet(); return delegate.add(element) }
                override fun remove(element: CommandRef): Boolean { touched.incrementAndGet(); return delegate.remove(element) }
                override fun contains(element: CommandRef): Boolean { touched.incrementAndGet(); return delegate.contains(element) }
            })
            try {
                val work = tracker.recoverySnapshot(); val before = f.storage.raw(); val writes = f.storage.storage.writes
                val count = tracker.evidenceDiscontinuityCount; val t = tracker.findPrepared(c)
                val accessBefore = f.access.total
                val result = controlTestTimeout("pre-lease closed release") { f.storage.control.releaseAfterConsumption(c) }
                assertEquals("D2B6/T2.$id: noDataStoreAccess", 0, f.access.total - accessBefore)
                assertClosedRelease(id, c, terminated, result, work)
                assertEquals("D2B6/T2.$id: leaseUntouched", 0, touched.get())
                assertEquals("D2B6/T2.$id: noStorageRead", count, tracker.evidenceDiscontinuityCount)
                assertEquals("D2B6/T2.$id: noStorageWrite", before, f.storage.raw()); assertEquals(writes, f.storage.storage.writes)
                assertEquals("D2B6/T2.$id: setsUnchanged", work, tracker.recoverySnapshot())
                assertSame(t, tracker.findPrepared(c))
            } finally { ControlReleaseFixtures.replaceLease(tracker, delegate); delegate.remove(c) }
        }
    }

    @Test fun T2_05_storeRechecksNewStatesAfterTheLease() = runReleaseTest<Unit> {
        for ((id, terminated) in listOf("05a" to false, "05b" to true)) {
            val f = counted(id); val tracker = f.tracker; val c = f.add()
            val delegate = tracker.executing; val first = AtomicBoolean(true)
            ControlReleaseFixtures.replaceLease(tracker, object : MutableSet<CommandRef> by delegate {
                override fun add(element: CommandRef): Boolean {
                    // RETAINED at the pre-check; the new state is published while the lease is being acquired.
                    if (element === c && first.compareAndSet(true, false)) {
                        simulatePending(tracker, c); if (terminated) simulateTerminated(tracker, c)
                    }
                    return delegate.add(element)
                }
            })
            try {
                val before = f.storage.raw(); val writes = f.storage.storage.writes; val count = tracker.evidenceDiscontinuityCount
                val accessBefore = f.access.total
                val result = controlTestTimeout("post-lease closed release") { f.storage.control.releaseAfterConsumption(c) }
                assertEquals("D2B6/T2.$id: noDataStoreAccess", 0, f.access.total - accessBefore)
                assertTrue("D2B6/T2.$id: seamReached", !first.get())
                assertClosedRelease(id, c, terminated, result, tracker.recoverySnapshot())
                assertEquals("D2B6/T2.$id: noStorageRead", count, tracker.evidenceDiscontinuityCount)
                assertEquals("D2B6/T2.$id: noStorageWrite", before, f.storage.raw()); assertEquals(writes, f.storage.storage.writes)
                assertTrue("D2B6/T2.$id: leaseReleased", tracker.executing.isEmpty())
            } finally { ControlReleaseFixtures.replaceLease(tracker, delegate) }
        }
    }
}
