package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.math.BigInteger
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

/**
 * Claude-owned 6-1B T3 contract (skeleton v12 §4.1; revision 06 §3.3·§7.1). abandonBeforeFirstConfirm terminates only a
 * Mutations or Lifecycle command with no business confirmation history: each history field alone refuses with
 * NotNeverConfirm(field) before any storage access; RotateAndSettle/Handover bodies are UnsupportedInThisUnit after the
 * lease (InFlight first under contention; 6-2/6-3 open them);
 * wrong tracker lifetime and a same-id unregistered ref are refused first. The owner reads the latest record and
 * observes it at once — an interpretable own Applied is recorded as observedApplied before any whole-record rejection —
 * and refuses a record that is not wholly interpretable schema 2 (RecoveryRequired) or that still carries an own Applied
 * row (Conflict). Positive paths complete as NeverSubmitted with the owner-returned snapshot and storage evidence, body
 * detached and exact U/P/commands cleanup. Closure declarations and the storage boundary are T4. The implementation
 * thread reads but does not edit this file.
 */
class ControlNeverConfirmOwnerContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private suspend fun abandon(f: TerminationFixture, c: CommandRef) =
        controlTestTimeout("abandon") { f.store.abandonBeforeFirstConfirm(c, closure(c)) }
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    // Preferences equality compares ByteArray values by content; a raw asMap() comparison would compare array identity.
    private fun withoutBarrier(p: Preferences): Preferences = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun barrier(p: Preferences) = p[ControlStoreTestStorage.BARRIER] ?: 0L
    private fun own(c: CommandRef) = ControlReleaseFixtures.wire(ControlReleaseFixtures.row(c))

    private fun unrelated(f: TerminationFixture): Pair<CommandRef, CommandRef> {
        val u = f.store.prepare(); f.addUnresolved(u)
        val p = f.store.prepare(); f.addPending(p)
        return u to p
    }
    private suspend fun assertCompleted(id: String, f: TerminationFixture, c: CommandRef, u: CommandRef, p: CommandRef,
        wroteBarrier: Boolean) {
        val before = f.disk()
        val result = abandon(f, c)
        assertTrue("D2B6/T3.$id: completed $result", result is ControlCompletionResult.Completed)
        result as ControlCompletionResult.Completed
        assertEquals("D2B6/T3.$id: neverSubmitted", CompletionMode.NeverSubmitted, result.mode)
        assertSame(c, result.command)
        val after = f.disk()
        assertEquals("D2B6/T3.$id: snapshotIsOwnerReturn", after, result.snapshot.record.original)
        assertEquals("D2B6/T3.$id: proof", if (wroteBarrier) RecordTransactionEvidence.CompletedWriteScope
            else RecordTransactionEvidence.LockedFileRead, result.proof.storage)
        assertEquals("D2B6/T3.$id: barrier", barrier(before) + if (wroteBarrier) 1 else 0, barrier(after))
        assertEquals("D2B6/T3.$id: onlyBarrierChanged", withoutBarrier(before), withoutBarrier(after))
        assertEquals("D2B6/T3.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("D2B6/T3.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("D2B6/T3.$id: exactCommandRemoved", f.tracker.findPrepared(c))
        val work = f.tracker.recoverySnapshot()
        assertEquals("D2B6/T3.$id: exactU", setOf(u), work.unresolvedCommands)
        assertEquals("D2B6/T3.$id: exactP", setOf(p), work.pendingReleases)
        assertEquals("D2B6/T3.$id: resultSets", work.unresolvedCommands, result.localUnresolvedCommands)
        assertEquals("D2B6/T3.$id: resultSets", work.pendingReleases, result.localPendingReleases)
        assertTrue("D2B6/T3.$id: leaseReleased", f.tracker.executing.isEmpty())
    }

    // ── positives ─────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun T3_01_prepareOnlyCompletes() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val (u, p) = unrelated(f)
        assertCompleted("01", f, c, u, p, wroteBarrier = false)
    }

    @Test fun T3_02_readFaultBeforeFirstConfirmCompletes() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations()
        f.boundary.failNextBeforeSnapshot = true
        val failed = controlTestTimeout("read fault execute") { f.store.execute(c) }
        assertTrue("fixture: unconfirmed read fault $failed", failed is ControlStoreResult.Unconfirmed)
        val t = f.history(c)
        assertTrue("fixture: c in U", c in f.tracker.recoverySnapshot().unresolvedCommands)
        assertFalse("fixture: never requested", t.confirmationRequested.get())
        assertNull("fixture: first confirm unbound", t.firstConfirmDiscontinuityCount)
        val (u, p) = unrelated(f)
        // The earlier non-normal transaction left a read-back obligation: the management Confirm writes the barrier.
        assertCompleted("02", f, c, u, p, wroteBarrier = true)
    }

    @Test fun T3_03_preparedLifecycleCompletes() = runBlocking {
        val f = fixture()
        controlTestTimeout("lifecycle seed") { f.storage.data.updateData { F.raw() } }
        val c = f.tracker.registerPrepared(F.command(life = f.tracker.lifetimeId))
        val (u, p) = unrelated(f)
        assertCompleted("03", f, c, u, p, wroteBarrier = false)
    }

    // ── refused before storage access ─────────────────────────────────────────────────────────────────────────────
    private suspend fun refusedBeforeStorage(id: String, f: TerminationFixture, c: CommandRef, expected: CompletionRejectionReason,
        k: TerminationClosure = closure(c)) {
        val t = f.tracker.findPrepared(c)
        val work = f.tracker.recoverySnapshot(); val before = f.storage.raw(); val writes = f.storage.storage.writes
        val accessBefore = f.boundary.accesses
        val result = controlTestTimeout("refused abandon") { f.store.abandonBeforeFirstConfirm(c, k) }
        assertEquals("D2B6/T3.$id: noStorageAccess", 0, f.boundary.accesses - accessBefore)
        assertTrue("D2B6/T3.$id: rejected $result", result is ControlCompletionResult.Rejected)
        result as ControlCompletionResult.Rejected
        assertEquals("D2B6/T3.$id: reason", expected, result.reason)
        assertEquals("D2B6/T3.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertSame(t, f.tracker.findPrepared(c))
        assertEquals("D2B6/T3.$id: setsUnchanged", work, f.tracker.recoverySnapshot())
        assertEquals(work.unresolvedCommands, result.localUnresolvedCommands); assertEquals(work.pendingReleases, result.localPendingReleases)
        assertEquals("D2B6/T3.$id: noWrite", before, f.storage.raw()); assertEquals(writes, f.storage.storage.writes)
        assertTrue("D2B6/T3.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun notNeverConfirm(id: String, violation: NeverConfirmViolation, taint: suspend (TerminationFixture, CommandRef, TrackedControlCommand) -> Unit) {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val t = f.history(c)
        taint(f, c, t)
        refusedBeforeStorage(id, f, c, CompletionRejectionReason.NotNeverConfirm(violation))
        if (violation != NeverConfirmViolation.TerminationDescriptorBound) assertNull("D2B6/T3.$id: noDescriptor", t.terminationDescriptor)
    }
    @Test fun T3_10_confirmationRequested() = runBlocking {
        notNeverConfirm("10", NeverConfirmViolation.ConfirmationRequested) { _, _, t -> t.confirmationRequested.set(true) }
    }
    @Test fun T3_11_firstConfirmBound() = runBlocking {
        notNeverConfirm("11", NeverConfirmViolation.FirstConfirmBound) { _, _, t -> t.bindFirstConfirm(BigInteger.ZERO) }
    }
    @Test fun T3_12_confirmed() = runBlocking {
        notNeverConfirm("12", NeverConfirmViolation.Confirmed) { _, _, t -> t.confirmed.set(true) }
    }
    @Test fun T3_13_expectedApplied() = runBlocking {
        notNeverConfirm("13", NeverConfirmViolation.ExpectedApplied) { _, c, t -> t.expectedApplied = ControlReleaseFixtures.row(c) }
    }
    @Test fun T3_14_observedApplied() = runBlocking {
        notNeverConfirm("14", NeverConfirmViolation.ObservedApplied) { _, _, t -> t.observedApplied.set(true) }
    }
    @Test fun T3_15_terminationDescriptorBound() = runBlocking {
        notNeverConfirm("15", NeverConfirmViolation.TerminationDescriptorBound) { f, _, t ->
            // An otherwise unreachable RETAINED+descriptor state: copy a real descriptor from a ref left pending by a write fault.
            val d = f.mutations(); f.armReadBack(); f.storage.storage.before = true
            val pending = controlTestTimeout("pending donor") { f.store.abandonBeforeFirstConfirm(d, closure(d)) }
            check(pending is ControlCompletionResult.Unconfirmed && d.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING) { "fixture: $pending" }
            val field = TrackedControlCommand::class.java.getDeclaredField("terminationDescriptor").apply { isAccessible = true }
            field.set(t, checkNotNull(field.get(f.history(d))))
        }
    }
    @Test fun T3_16_rotationBodyUnsupportedInThisUnit() = runBlocking {
        val f = fixture(); f.storage.seed()
        val input = RotateAndSettleNamespaces(emptyList(), NamespaceSettlementFixtures.fence, NamespaceSettlementFixtures.life,
            NamespaceSettlementFixtures.demand, "op", "d", null, null)
        val c = f.tracker.registerPrepared(CommandRef("op", ControlCommandBody.RotateAndSettle(input), f.tracker.lifetimeId))
        refusedBeforeStorage("16", f, c, CompletionRejectionReason.UnsupportedInThisUnit)
    }
    @Test fun T3_16b_inFlightPrecedesUnsupportedBody() = runBlocking {
        val f = fixture(); f.storage.seed()
        val input = RotateAndSettleNamespaces(emptyList(), NamespaceSettlementFixtures.fence, NamespaceSettlementFixtures.life,
            NamespaceSettlementFixtures.demand, "op", "d", null, null)
        val c = f.tracker.registerPrepared(CommandRef("op", ControlCommandBody.RotateAndSettle(input), f.tracker.lifetimeId))
        check(f.tracker.executing.add(c))
        try {
            val accessBefore = f.boundary.accesses
            val r = controlTestTimeout("busy rotation abandon") { f.store.abandonBeforeFirstConfirm(c, closure(c)) }
            assertEquals("D2B6/T3.16b: inFlightFirst", CompletionRejectionReason.InFlight, (r as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/T3.16b: noStorageAccess", 0, f.boundary.accesses - accessBefore)
        } finally { f.tracker.executing.remove(c) }
    }
    @Test fun T3_17_handoverBodyUnsupportedInThisUnit() = runBlocking {
        val f = fixture(); f.storage.seed()
        val c = f.tracker.registerPrepared(CurrentNullFixtures.command(lifetime = f.tracker.lifetimeId))
        refusedBeforeStorage("17", f, c, CompletionRejectionReason.UnsupportedInThisUnit)
    }
    @Test fun T3_18_wrongTrackerLifetime() = runBlocking {
        val f = fixture(); f.storage.seed(); val other = fixture(); val c = other.mutations()
        refusedBeforeStorage("18", f, c, CompletionRejectionReason.WrongTrackerLifetime)
        assertEquals("D2B6/T3.18: otherTrackerUntouched", ControlCommandLifecycle.RETAINED, c.lifecycleState)
    }
    @Test fun T3_19_sameIdUnregisteredRef() = runBlocking {
        val f = fixture(); f.storage.seed(); val c = f.mutations()
        val clone = CommandRef(c.id, c.body, c.ownerTrackingLifetimeId)
        refusedBeforeStorage("19", f, clone, CompletionRejectionReason.NotRegisteredIdentity)
        assertSame("D2B6/T3.19: registeredRefUntouched", c, f.history(c).command)
    }

    // ── whole-record gate after the actual owner read ─────────────────────────────────────────────────────────────
    private suspend fun gate(id: String, expected: Any, observedFirst: Boolean = false, change: (MutablePreferences, CommandRef) -> Unit) {
        val f = fixture(); f.storage.seed(); val c = f.mutations(); val t = f.history(c)
        f.edit { change(it, c) }
        val work = f.tracker.recoverySnapshot(); val before = f.storage.raw(); val writes = f.storage.storage.writes
        val count = f.tracker.evidenceDiscontinuityCount
        val result = abandon(f, c)
        when (expected) {
            is RecoveryReason -> assertEquals("D2B6/T3.$id: recoveryRequired $result", expected,
                (result as? ControlCompletionResult.RecoveryRequired)?.reason)
            ConflictReason::class -> assertTrue("D2B6/T3.$id: conflict $result", result is ControlCompletionResult.Conflict)
            else -> error("unsupported expectation")
        }
        assertEquals("D2B6/T3.$id: observedAppliedFirst", observedFirst, t.observedApplied.get())
        assertEquals("D2B6/T3.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/T3.$id: noDescriptor", t.terminationDescriptor)
        assertSame(t, f.tracker.findPrepared(c))
        assertEquals("D2B6/T3.$id: setsUnchanged", work, f.tracker.recoverySnapshot())
        assertEquals("D2B6/T3.$id: noWrite", before, f.storage.raw()); assertEquals(writes, f.storage.storage.writes)
        val classified = ControlRecordReader().read(before)
        assertEquals("D2B6/T3.$id: observedOnce",
            count + if (classified !is ControlRecordRead.Supported || classified.schemaVersion != 2) BigInteger.ONE else BigInteger.ZERO,
            f.tracker.evidenceDiscontinuityCount)
    }
    @Test fun T3_20_unrelatedArrayEntryUninterpretable() = runBlocking {
        gate("20", RecoveryReason.UninterpretableObligations) { p, _ -> p[ControlStoreTestStorage.DEMAND] = "[17]" }
    }
    @Test fun T3_21_metadataUninterpretable() = runBlocking {
        gate("21", RecoveryReason.UninterpretableMetadata) { p, _ -> p[evidenceKey] = "[17]" }
    }
    @Test fun T3_22_schemaNotTwo() = runBlocking {
        gate("22", RecoveryReason.ControlSchemaMigrationRequired) { p, _ ->
            p[ControlStoreTestStorage.SCHEMA] = 1; p.remove(evidenceKey); p.remove(ReclamationFixtures.fenceKey)
        }
    }
    @Test fun T3_23_unreadableEnvelope() = runBlocking {
        gate("23", RecoveryReason.UnreadableRecord) { p, _ -> p[ControlStoreTestStorage.HOLD] = "[" }
    }
    @Test fun T3_24_ownAppliedIsObservedBeforeUninterpretableSibling() = runBlocking {
        gate("24", RecoveryReason.UninterpretableObligations, observedFirst = true) { p, c ->
            p[evidenceKey] = "[${own(c)}]"; p[ControlStoreTestStorage.DEMAND] = "[17]"
        }
    }
    @Test fun T3_25_ownAppliedRowIsConflict() = runBlocking {
        gate("25", ConflictReason::class, observedFirst = true) { p, c -> p[evidenceKey] = "[${own(c)}]" }
    }
}
