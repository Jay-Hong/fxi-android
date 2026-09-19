package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import org.junit.Assert.*
import org.junit.Test

class ControlReleaseRecoveryResultTest : ReleaseOwnerTestBase() {
    private suspend fun withOtherWork(): Triple<CommandRef, CommandRef, CommandRef> {
        o.seed(); val c = confirmed(); val a = confirmed(); pending(a); val b = add(); o.storage.before = true
        assertTrue(o.control.execute(b) is ControlStoreResult.Unconfirmed)
        return Triple(c, a, b)
    }
    private fun memberships(result: ControlCommandReleaseResult, a: CommandRef, b: CommandRef) {
        assertEquals(setOf(a), result.localPendingReleases); assertEquals(setOf(b), result.localUnresolvedCommands)
        assertEquals(result.localPendingReleases, tracking.recoverySnapshot().pendingReleases)
        assertEquals(result.localUnresolvedCommands, tracking.snapshot())
    }
    @Test fun A17_earlyRejectedCarriesBothActualSets() = runReleaseTest<Unit> {
        val (_, a, b) = withOtherWork(); val idle = add()
        val result = o.control.releaseAfterConsumption(idle)
        assertEquals(ReleaseRejectionReason.NotConfirmed, (result as ControlCommandReleaseResult.Rejected).reason); memberships(result, a, b)
    }
    @Test fun A17_conflictCarriesBothActualSets() = runReleaseTest<Unit> {
        val (c, a, b) = withOtherWork()
        o.data.edit { it[evidenceKey] = it[evidenceKey]!!.replace(c.ownerTrackingLifetimeId.value, ReclamationFixtures.oldLife) }
        val result = o.control.releaseAfterConsumption(c)
        assertEquals(ConflictReason.CommandEvidenceMismatch, (result as ControlCommandReleaseResult.Conflict).reason); memberships(result, a, b)
    }
    @Test fun A17_encodingRejectionWrapperCarriesBothActualSets() = runReleaseTest<Unit> {
        val (c, a, b) = withOtherWork()
        // Pure wrapping boundary: valid removal shrinks a payload, so the encoder's defensive
        // rejection is tested independently instead of fabricating a FileStorage rejection.
        val reason = ReleaseRejectionReason.Encoding(RejectionReason.InvalidRequest("encoding refused"))
        val decision = ControlCommandReleaseResult.Rejected(c, emptySet(), emptySet(), reason, c.lifecycleState, read(disk()))
        val method = ControlRecordStore::class.java.getDeclaredMethod("withReleaseRecoveryWork",
            ControlCommandReleaseResult::class.java, LocalRecoveryWork::class.java).apply { isAccessible = true }
        val result = method.invoke(o.control, decision, tracking.recoverySnapshot()) as ControlCommandReleaseResult.Rejected
        assertEquals(reason, result.reason); memberships(result, a, b)
    }
    @Test fun A17_recoveryCarriesBothActualSets() = runReleaseTest<Unit> {
        val (c, a, b) = withOtherWork(); o.data.edit { it[ReclamationFixtures.fenceKey] = "[17]" }
        val result = o.control.releaseAfterConsumption(c)
        assertEquals(RecoveryReason.UninterpretableMetadata, (result as ControlCommandReleaseResult.RecoveryRequired).reason); memberships(result, a, b)
    }
    @Test fun A17_unconfirmedCarriesBothActualSetsIncludingSelfPending() = runReleaseTest<Unit> {
        val (c, a, b) = withOtherWork(); o.storage.before = true
        val result = o.control.releaseAfterConsumption(c)
        assertTrue(result is ControlCommandReleaseResult.Unconfirmed)
        assertEquals(setOf(a, c), result.localPendingReleases); assertEquals(setOf(b), result.localUnresolvedCommands)
    }
    @Test fun A17_releasedCarriesBothActualSetsAfterSelfCleanup() = runReleaseTest<Unit> {
        val (c, a, b) = withOtherWork(); memberships(released(c, barrierAllowed = true), a, b)
    }
    @Test fun A17_alreadyReleasedCarriesCurrentWorkSnapshot() = runReleaseTest<Unit> {
        val (c, a, b) = withOtherWork(); released(c, barrierAllowed = true)
        val result = o.control.releaseAfterConsumption(c)
        assertTrue(result is ControlCommandReleaseResult.AlreadyReleased); memberships(result, a, b)
        assertTrue(o.control.execute(b) is ControlStoreResult.Confirmed)
        assertEquals(setOf(b), result.localUnresolvedCommands); assertEquals(setOf(a), result.localPendingReleases)
        assertEquals(UnsupportedOperationException::class.java, runCatching { (result.localPendingReleases as MutableSet).clear() }.exceptionOrNull()?.javaClass)
    }
    @Test fun A17_otherOwnerRejectReportsReceivingOwnerWorkOnly() = runReleaseTest<Unit> {
        val (c, a, b) = withOtherWork(); val other = open(); other.seed(); val foreign = add(other.control)
        val result = o.control.releaseAfterConsumption(foreign)
        assertEquals(ReleaseRejectionReason.WrongTrackerLifetime, (result as ControlCommandReleaseResult.Rejected).reason); memberships(result, a, b)
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
    }
    @Test fun A16_pendingAlsoUnresolvedIsAnInvariantFailureWithoutRepair() = runReleaseTest<Unit> {
        o.seed(); val c = confirmed(); pending(c); val t = history(c); val descriptor = t.releaseDescriptor; val before = disk()
        tracking.markUnresolved(c) // Deliberately corrupt in-memory fixture: no production path does this.
        assertEquals("pending release is also business unresolved", runCatching { o.control.releaseAfterConsumption(c) }.exceptionOrNull()?.message)
        assertSame(t, history(c)); assertSame(descriptor, t.releaseDescriptor); assertEquals(before, disk())
        assertEquals(setOf(c), tracking.snapshot()); assertEquals(setOf(c), tracking.recoverySnapshot().pendingReleases)
    }
    @Test fun C10_schemaUpgradeDoesNotClearEitherRecoverySet() = runReleaseTest<Unit> {
        val (_, a, b) = withOtherWork()
        o.data.edit { it[ControlStoreTestStorage.SCHEMA] = 1; it.remove(evidenceKey); it.remove(ReclamationFixtures.fenceKey) }
        assertTrue(o.control.upgradeControlSchemaV1ToV2() is ControlSchemaUpgradeResult.Confirmed)
        assertEquals(setOf(a), tracking.recoverySnapshot().pendingReleases); assertEquals(setOf(b), tracking.snapshot())
    }
}
