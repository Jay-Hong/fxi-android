package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.TerminationClosures.of as closure
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Claude-owned 6-2A contract: Rotation NeverConfirm (6-2 skeleton r3 §5 and T7; revision 06 §7.1; 6-1 carry row
 * `6-1_carry_to_6-2_6-3.md`). abandonBeforeFirstConfirm/retryTermination now accept a RotateAndSettle body (Handover
 * stays UnsupportedInThisUnit until 6-3). Besides the 6-1B history gate and whole-record gate, the owner reads the latest
 * record and refuses as Conflict when an own Applied row exists (observedApplied recorded first) or when ANY seal carries
 * a settlement whose operationId is this command's id — even without an own Applied row, and even when only part of
 * the fixed targets are settled. A settled seal of another operation is not own evidence and is preserved untouched;
 * the prepared active seal is preserved too. The absence descriptor stays EvidenceAbsent(AbandonBeforeFirstConfirm), and
 * a pending retry re-runs the same witness scan without turning partial evidence into absence. Records are produced by the
 * real rotation transition (NamespaceSettlementFixtures.settled), not written by hand. The implementation thread reads
 * but does not edit this file.
 */
class ControlRotationNeverConfirmContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runBlocking { opened.forEach { it.storage.close() } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val N = NamespaceSettlementFixtures
    private val both = N.input(targets = listOf(node(N.user), node(N.krx)))

    private fun rotation(f: TerminationFixture, input: RotateAndSettleNamespaces = N.input()): CommandRef =
        f.tracker.registerPrepared(CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), f.tracker.lifetimeId))
    private suspend fun write(f: TerminationFixture, record: Preferences) = f.edit { it.clear(); it += record }
    /** The real transition's candidate for [input] applied under this fixture's tracker lifetime (own Applied row). */
    private fun applied(f: TerminationFixture, input: RotateAndSettleNamespaces = N.input(), seals: String = "[${N.user}]") =
        N.settled(input, N.raw(seals), f.tracker.lifetimeId)
    private fun withoutEvidence(p: Preferences) = p.toMutablePreferences().apply { this[evidenceKey] = "[]" }.toPreferences()
    private fun withoutBarrier(p: Preferences) = p.toMutablePreferences().apply { remove(ControlStoreTestStorage.BARRIER) }.toPreferences()
    private fun sealIds(p: Preferences) = Json.parseToJsonElement(checkNotNull(p[sealKey])).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
    private fun settlementOp(p: Preferences, id: String) = Json.parseToJsonElement(checkNotNull(p[sealKey])).jsonArray
        .single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject["settlement"]?.jsonObject?.getValue("operationId")?.jsonPrimitive?.content

    private suspend fun abandon(f: TerminationFixture, c: CommandRef) = controlTestTimeout("rotation abandon") { f.store.abandonBeforeFirstConfirm(c, closure(c)) }
    private suspend fun retry(f: TerminationFixture, c: CommandRef) = controlTestTimeout("rotation retry") { f.store.retryTermination(c, closure(c)) }

    private suspend fun assertCompleted(id: String, f: TerminationFixture, c: CommandRef) {
        val before = f.disk()
        val r = abandon(f, c)
        assertTrue("D2B6/6-2.T7.$id: completed $r", r is ControlCompletionResult.Completed)
        r as ControlCompletionResult.Completed
        assertEquals("D2B6/6-2.T7.$id: neverSubmitted", CompletionMode.NeverSubmitted, r.mode)
        assertSame(c, r.command)
        val after = f.disk()
        assertEquals("D2B6/6-2.T7.$id: snapshotIsOwnerReturn", after, r.snapshot.record.original)
        assertEquals("D2B6/6-2.T7.$id: onlyBarrierMayChange", withoutBarrier(before), withoutBarrier(after))
        assertEquals("D2B6/6-2.T7.$id: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("D2B6/6-2.T7.$id: bodyDetached", c.captureStateAndBody().body)
        assertNull("D2B6/6-2.T7.$id: exactCommandRemoved", f.tracker.findPrepared(c))
        assertTrue("D2B6/6-2.T7.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    private suspend fun assertConflictFirstEntry(id: String, f: TerminationFixture, c: CommandRef, observed: Boolean) {
        f.armReadBack() // any management Confirm(original) would now write the barrier: whole-record equality = Confirm 0
        val before = f.storage.raw()
        val r = abandon(f, c)
        assertTrue("D2B6/6-2.T7.$id: conflict $r", r is ControlCompletionResult.Conflict)
        assertEquals("D2B6/6-2.T7.$id: observedApplied", observed, f.history(c).observedApplied.get())
        assertEquals("D2B6/6-2.T7.$id: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertNull("D2B6/6-2.T7.$id: noDescriptor", f.history(c).terminationDescriptor)
        assertFalse("D2B6/6-2.T7.$id: notPending", c in f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/6-2.T7.$id: recordUntouched", before, f.storage.raw())
        assertTrue("D2B6/6-2.T7.$id: leaseReleased", f.tracker.executing.isEmpty())
    }
    /** Leaves [c] TERMINATION_PENDING with the read-back armed: the management Confirm write fails before its block. */
    private suspend fun pending(f: TerminationFixture, c: CommandRef): TerminationPendingDescriptor {
        f.armReadBack(); f.storage.storage.before = true
        val r = abandon(f, c)
        check(r is ControlCompletionResult.Unconfirmed && c.lifecycleState == ControlCommandLifecycle.TERMINATION_PENDING) { "fixture: $r" }
        f.storage.storage.before = false
        val d = checkNotNull(f.history(c).terminationDescriptor)
        assertTrue("D2B6/6-2.T7: absenceDescriptor $d", d is TerminationPendingDescriptor.EvidenceAbsent &&
            d.mode == CompletionMode.NeverSubmitted && d.entry == TerminationEntry.AbandonBeforeFirstConfirm)
        return d
    }
    private suspend fun assertRetryConflictKeepsPending(id: String, f: TerminationFixture, c: CommandRef, d: TerminationPendingDescriptor) {
        val before = f.storage.raw()
        val r = retry(f, c)
        assertTrue("D2B6/6-2.T7.$id: conflict $r", r is ControlCompletionResult.Conflict)
        assertEquals("D2B6/6-2.T7.$id: stillPending", ControlCommandLifecycle.TERMINATION_PENDING, c.lifecycleState)
        assertSame("D2B6/6-2.T7.$id: descriptorKept", d, f.history(c).terminationDescriptor)
        assertTrue("D2B6/6-2.T7.$id: pKept", c in f.tracker.recoverySnapshot().pendingReleases)
        // The read-back is armed, so any management Confirm would write the barrier: whole-record equality = no Confirm.
        assertEquals("D2B6/6-2.T7.$id: noConfirmRequested", before, f.storage.raw())
        assertTrue("D2B6/6-2.T7.$id: leaseReleased", f.tracker.executing.isEmpty())
    }

    // ── positives ─────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun T7r_01_preparedOnlyRotationCompletesAndKeepsTheActiveSeal() = runBlocking {
        val f = fixture(); write(f, N.raw()); val c = rotation(f)
        assertCompleted("01", f, c)
        assertEquals("D2B6/6-2.T7.01: activeSealKept", listOf("s"), sealIds(f.disk()))
        assertNull("D2B6/6-2.T7.01: stillUnsettled", settlementOp(f.disk(), "s"))
    }
    @Test fun T7r_02_anotherOperationsSettledSealIsNotOwnEvidence() = runBlocking {
        val f = fixture()
        val other = N.input(op = "00000000-0000-0000-0000-000000000009", did = "00000000-0000-0000-0000-000000000010")
        write(f, N.settled(other, N.raw(), N.trackerLife)) // settled by `other` under a foreign lifetime
        val c = rotation(f)
        check(settlementOp(f.disk(), "s") == other.operationId)
        assertCompleted("02", f, c)
        assertEquals("D2B6/6-2.T7.02: otherOperationSealKept", other.operationId, settlementOp(f.disk(), "s"))
    }

    // ── own evidence refuses (carry row) ─────────────────────────────────────────────────────────────────────────
    @Test fun T7r_03_ownOperationWitnessWithoutAppliedIsConflict() = runBlocking {
        // 6-1 carry row: own-operation settled witness, no own Applied → Conflict, management Confirm 0.
        val f = fixture(); write(f, withoutEvidence(applied(f))); val c = rotation(f)
        check(settlementOp(f.disk(), "s") == c.id && f.disk()[evidenceKey] == "[]")
        assertConflictFirstEntry("03", f, c, observed = false)
    }
    @Test fun T7r_04_ownWitnessOnOneOfTwoTargetsIsConflict() = runBlocking {
        // Partial own evidence is still own evidence: never synthesized into absence.
        val f = fixture(); val full = withoutEvidence(applied(f, both, "[${N.user},${N.krx}]"))
        val seals = Json.parseToJsonElement(checkNotNull(full[sealKey])).jsonArray
            .map { if (it.jsonObject.getValue("id").jsonPrimitive.content == "c") Json.parseToJsonElement(N.krx) else it }
        write(f, full.toMutablePreferences().apply { this[sealKey] = JsonArray(seals).toString() }.toPreferences())
        val c = rotation(f, both)
        check(settlementOp(f.disk(), "s") == c.id && settlementOp(f.disk(), "c") == null)
        assertConflictFirstEntry("04", f, c, observed = false)
    }
    @Test fun T7r_04b_ownWitnessOnASealOutsideTheFixedTargetsIsConflict() = runBlocking {
        // Any seal settled by this operation is own evidence, not only the fixed targets: the target "s" stays active while
        // the CAPABILITY seal "c" (not a target of this ref) carries this command's operation witness.
        val f = fixture(); val krxOnly = N.input(targets = listOf(node(N.krx)))
        val settledC = withoutEvidence(applied(f, krxOnly, "[${N.krx}]"))
        val cSeal = Json.parseToJsonElement(checkNotNull(settledC[sealKey])).jsonArray
        write(f, settledC.toMutablePreferences().apply { this[sealKey] = JsonArray(listOf(Json.parseToJsonElement(N.user)) + cSeal).toString() }.toPreferences())
        val c = rotation(f) // fixed targets: only "s"
        check(krxOnly.operationId == c.id && settlementOp(f.disk(), "c") == c.id && settlementOp(f.disk(), "s") == null)
        assertConflictFirstEntry("04b", f, c, observed = false)
    }
    @Test fun T7r_05_ownAppliedIsConflictAndObservedFirst() = runBlocking {
        val f = fixture(); write(f, applied(f)); val c = rotation(f)
        check(f.disk()[evidenceKey] != "[]")
        assertConflictFirstEntry("05", f, c, observed = true)
    }

    // ── the shared gates still apply to a Rotation body ──────────────────────────────────────────────────────────
    @Test fun T7r_06_uninterpretableRecordIsRecoveryRequired() = runBlocking {
        val f = fixture(); write(f, N.raw().toMutablePreferences().apply { this[demandKey] = "[17]" }.toPreferences()); val c = rotation(f)
        f.armReadBack()
        val before = f.storage.raw()
        val r = abandon(f, c)
        assertEquals("D2B6/6-2.T7.06: recoveryRequired $r", RecoveryReason.UninterpretableObligations,
            (r as? ControlCompletionResult.RecoveryRequired)?.reason)
        assertEquals("D2B6/6-2.T7.06: retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        assertEquals("D2B6/6-2.T7.06: recordUntouched", before, f.storage.raw())
    }
    @Test fun T7r_07_eachHistoryFieldIsNotNeverConfirmBeforeStorage() = runBlocking {
        val taints: List<Pair<NeverConfirmViolation, suspend (TerminationFixture, CommandRef, TrackedControlCommand) -> Unit>> = listOf(
            NeverConfirmViolation.ConfirmationRequested to { _, _, t -> t.confirmationRequested.set(true) },
            NeverConfirmViolation.FirstConfirmBound to { _, _, t -> t.bindFirstConfirm(BigInteger.ZERO) },
            NeverConfirmViolation.Confirmed to { _, _, t -> t.confirmed.set(true) },
            NeverConfirmViolation.ExpectedApplied to { _, c, t -> t.expectedApplied = ControlReleaseFixtures.row(c) },
            NeverConfirmViolation.ObservedApplied to { _, _, t -> t.observedApplied.set(true) },
            NeverConfirmViolation.TerminationDescriptorBound to { f, _, t ->
                // Otherwise unreachable RETAINED+descriptor: copy a real descriptor from a donor left pending by a write fault.
                val donor = f.mutations(); f.armReadBack(); f.storage.storage.before = true
                val pend = controlTestTimeout("pending donor") { f.store.abandonBeforeFirstConfirm(donor, closure(donor)) }
                check(pend is ControlCompletionResult.Unconfirmed) { "fixture: $pend" }
                f.storage.storage.before = false
                val field = TrackedControlCommand::class.java.getDeclaredField("terminationDescriptor").apply { isAccessible = true }
                field.set(t, checkNotNull(field.get(f.history(donor))))
            })
        for ((violation, taint) in taints) {
            val f = fixture(); write(f, N.raw()); val c = rotation(f); taint(f, c, f.history(c))
            val accessBefore = f.boundary.accesses
            val r = abandon(f, c)
            assertEquals("D2B6/6-2.T7.07: $violation", CompletionRejectionReason.NotNeverConfirm(violation),
                (r as? ControlCompletionResult.Rejected)?.reason)
            assertEquals("D2B6/6-2.T7.07: $violation noStorageAccess", 0, f.boundary.accesses - accessBefore)
            assertEquals("D2B6/6-2.T7.07: $violation retained", ControlCommandLifecycle.RETAINED, c.lifecycleState)
        }
    }

    // ── pending retry re-runs the witness scan ───────────────────────────────────────────────────────────────────
    @Test fun T7r_08_retrySeesAnOwnWitnessThatAppeared() = runBlocking {
        val f = fixture(); write(f, N.raw()); val c = rotation(f); val d = pending(f, c)
        val witnessed = withoutEvidence(applied(f))
        f.edit { p -> p[sealKey] = checkNotNull(witnessed[sealKey]) }
        check(settlementOp(f.storage.raw(), "s") == c.id)
        assertRetryConflictKeepsPending("08", f, c, d)
    }
    @Test fun T7r_09_retryPartialOwnWitnessIsNotAbsence() = runBlocking {
        val f = fixture(); write(f, N.raw("[${N.user},${N.krx}]")); val c = rotation(f, both); val d = pending(f, c)
        val full = applied(f, both, "[${N.user},${N.krx}]")
        val seals = Json.parseToJsonElement(checkNotNull(full[sealKey])).jsonArray
            .map { if (it.jsonObject.getValue("id").jsonPrimitive.content == "c") Json.parseToJsonElement(N.krx) else it }
        f.edit { p -> p[sealKey] = JsonArray(seals).toString() }
        check(settlementOp(f.storage.raw(), "s") == c.id && settlementOp(f.storage.raw(), "c") == null)
        assertRetryConflictKeepsPending("09", f, c, d)
    }
    @Test fun T7r_10_retryWithoutOwnEvidenceCompletes() = runBlocking {
        val f = fixture(); write(f, N.raw()); val c = rotation(f); pending(f, c)
        val r = retry(f, c)
        assertTrue("D2B6/6-2.T7.10: completed $r", r is ControlCompletionResult.Completed &&
            r.mode == CompletionMode.NeverSubmitted)
        assertEquals("D2B6/6-2.T7.10: terminated", ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("D2B6/6-2.T7.10: exactCommandRemoved", f.tracker.findPrepared(c))
        assertFalse("D2B6/6-2.T7.10: pCleared", c in f.tracker.recoverySnapshot().pendingReleases)
        assertEquals("D2B6/6-2.T7.10: activeSealKept", listOf("s"), sealIds(f.disk()))
    }

    // ── r3 (measurement 6-2A r1): the witness scan belongs to Rotation only (skeleton v12 §4.1 step 3, :76) ────────
    @Test fun T7r_11_mutationsRefIgnoresASealSettledUnderItsId() = runBlocking {
        // A record is external data: a seal settled under a Mutations command's id is not that command's own evidence
        // (Mutations never write settlements), so the Mutations NeverConfirm still completes.
        val f = fixture(); val c = f.mutations()
        val foreign = N.input(op = c.id, did = "00000000-0000-0000-0000-000000000011")
        write(f, withoutEvidence(N.settled(foreign, N.raw(), N.trackerLife)))
        check(settlementOp(f.disk(), "s") == c.id)
        assertCompleted("11", f, c)
        assertEquals("D2B6/6-2.T7.11: sealKept", c.id, settlementOp(f.disk(), "s"))
    }
}
