package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.lSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.lWitness
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.seal
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class HandoverTypeBoundaryTest : ReleaseOwnerTestBase() {
    private val before = FenceV1("B", "u2", "k2")
    private val executor = SettlementExecutor("B", 3, LifetimeId("origin"))
    private fun r() = ControlCommandBody.SettleRetiredNamespace(RetiredNamespaceSettlement(
        node(ControlObligationFixtures.seal), before, executor, "op", null, null))
    private fun n() = ControlCommandBody.RotateAndSettleCurrentNull(CurrentNullSettlement(
        listOf(node(ControlObligationFixtures.nullSeal)), emptyList(), before, executor, "op", "d",
        NamespaceSettlementFixtures.demand, "u3", null))
    private fun l() = ControlCommandBody.SettleRetiredNull(RetiredNullSettlement(
        listOf(node(ControlObligationFixtures.nullSeal)), before, executor, "op"))

    private fun releaseExcluded(body: ControlCommandBody) {
        val lifetime = OwnerTrackingLifetimeId.issue()
        val command = CommandRef("op", body, lifetime)
        val tracked = TrackedControlCommand(command).apply { confirmed.set(true) }
        val targets = tracked.targets.get()
        val result = ControlCommandReleaseEligibility.decide(command, lifetime, tracked, false, false)
        assertEquals(ReleaseRejectionReason.UnsupportedCommandKind,
            (result as? ControlCommandReleaseEligibility.Decision.Rejected)?.reason)
        assertEquals(ControlCommandLifecycle.RETAINED, command.lifecycleState)
        assertTrue(tracked.confirmed.get())
        assertSame(targets, tracked.targets.get())
        assertNull(tracked.releaseDescriptor)
        assertNull(tracked.expectedApplied)
        assertFalse(tracked.confirmationRequested.get())
    }
    @Test fun A12_R_release_excluded() = releaseExcluded(r())
    @Test fun A12_N_release_excluded() = releaseExcluded(n())
    @Test fun A12_L_release_excluded() = releaseExcluded(l())

    @Test fun A18_generic_add_cannot_store_L_witness() {
        assertTrue(seal().settlement is RetiredNullSettlementEvidenceV2)
        val action = ControlMutation.Add.prepare(ControlKind.SEAL, UUID.randomUUID()) { id ->
            literal(lSeal); set("id", ControlScalar.Text(id))
        }
        assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), action.built)
    }
    @Test fun A18_generic_edit_cannot_store_L_witness() {
        val preimage = node(ControlObligationFixtures.nullSeal)
        val pure = ControlObligations.editExisting(ControlKind.SEAL, preimage) {
            createChild("settlement") { literal(lWitness) }
        }
        assertTrue("positive control: valid L shape", pure is ControlWriteResult.Written)
        val action = ControlMutation.Edit.prepare(ControlKind.SEAL, preimage) {
            createChild("settlement") { literal(lWitness) }
        }
        assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), action.changed)
    }
    @Test fun A18_generic_add_cannot_store_SETTLEMENT_applied() {
        assertEquals(setOf("SEAL", "DEMAND", "HOLD", "RECOVERY_INTENT"), ControlKind.entries.map { it.name }.toSet())
        val action = ControlMutation.Add.prepare(ControlKind.SEAL, UUID.randomUUID()) { id ->
            literal(HandoverFormatFixtures.applied()); set("id", ControlScalar.Text(id))
        }
        assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), action.built)
    }
    @Test fun A18_generic_edit_cannot_store_SETTLEMENT_applied() {
        val action = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {
            set("kind", ControlScalar.Text("SETTLEMENT"))
        }
        assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), action.changed)
    }
    @Test fun A18_handover_id_must_match_operation() {
        assertEquals("op", CommandRef("op", l(), OwnerTrackingLifetimeId.issue()).id)
        assertEquals("handover command id must equal operationId",
            runCatching { CommandRef("other", l(), OwnerTrackingLifetimeId.issue()) }.exceptionOrNull()?.message)
    }

    private fun unimplemented(body: ControlCommandBody) = runReleaseTest {
        o.seed()
        val command = tracking.registerPrepared(CommandRef("op", body, tracking.lifetimeId))
        val source = o.raw()
        val writes = o.storage.writes
        assertNull(o.control.checkpoint(command))
        val result = controlTestTimeout("type-only handover execute") { o.control.execute(command) }
        assertEquals(RejectionReason.InvalidRequest("HandoverSettlementNotImplemented"),
            (result as? ControlStoreResult.Rejected)?.reason)
        assertEquals(writes, o.storage.writes)
        assertEquals(source, o.raw())
        assertTrue(result.localUnresolvedCommands.isEmpty())
        assertTrue(result.localPendingReleases.isEmpty())
        assertFalse(command in tracking.executing)
        assertFalse(history(command).confirmationRequested.get())
    }
    @Test fun A18_R_named_writer_has_no_checkpoint() = runReleaseTest {
        val input = (r() as ControlCommandBody.SettleRetiredNamespace).input
        controlTestTimeout("R boundary seed") { o.data.updateData { RetiredNamespaceFixtures.raw(input) } }
        val command = tracking.registerPrepared(CommandRef("op", r(), tracking.lifetimeId))
        assertNull(o.control.checkpoint(command))
        val result = controlTestTimeout("R named dispatch") {
            o.control.execute(command, AttemptContext("B", 3, LifetimeId("origin"), false, false))
        }
        assertTrue(result is ControlStoreResult.Confirmed)
        assertNotNull((result as ControlStoreResult.Confirmed).handoverSettlement)
        assertNull(result.settlement)
        assertNull(o.control.checkpoint(command))
        assertFalse(command in tracking.executing)
    }
    @Test fun A18_N_has_no_writer_or_checkpoint() = unimplemented(n())
    @Test fun A18_L_has_no_writer_or_checkpoint() = unimplemented(l())
    @Test fun A18_confirmPrevious_cannot_import_empty_handover_checkpoint() = runReleaseTest {
        o.seed()
        val command = CommandRef("op", l(), OwnerTrackingLifetimeId.issue())
        val source = o.raw()
        val checkpoint = ControlCommandCheckpoint(command, emptyList(), true)
        val result = controlTestTimeout("type-only previous handover") { o.control.confirmPrevious(command, checkpoint) }
        assertEquals(RejectionReason.InvalidRequest("HandoverSettlementNotImplemented"),
            (result as? ControlStoreResult.Rejected)?.reason)
        assertEquals(source, o.raw())
        assertNull(tracking.findPrepared(command))
        assertFalse(command in tracking.executing)
    }
    @Test fun A18_no_matching_Applied_confirmation_before_writer_unit() {
        val command = CommandRef("op", l(), OwnerTrackingLifetimeId.issue())
        val row = AppliedEvidence.Settlement("op", command.ownerTrackingLifetimeId.value,
            HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)
        assertFalse(ControlAppliedEvidence.matches(command, TrackedControlCommand(command), row))
    }

    @Test fun A19_rotation_witness_match_excludes_L_type() {
        val l = seal().settlement!!
        val v1 = SettlementEvidenceV1(l.operationId, l.originLifetimeId,
            com.jay.fxi.data.entitlements.StoreOp.JOURNAL_RETIRED, l.before, l.after, l.journal)
        assertTrue(NamespaceSettlementTransition(ControlPayloadCodec()).witnessMatches(v1, v1))
        assertFalse(NamespaceSettlementTransition(ControlPayloadCodec()).witnessMatches(l, v1))
    }
    @Test fun A18_fixed_target_collections_are_detached() {
        val targets = mutableListOf(node(ControlObligationFixtures.nullSeal))
        val companions = mutableListOf(node(ControlObligationFixtures.seal))
        val l = RetiredNullSettlement(targets, before, executor, "op")
        val n = CurrentNullSettlement(targets, companions, before, executor, "op", "d",
            NamespaceSettlementFixtures.demand, "u3", null)
        targets.clear(); companions.clear()
        assertEquals(1, l.targets.size)
        assertEquals(1, n.nullTargets.size)
        assertEquals(1, n.companions.size)
        assertTrue(runCatching { (l.targets as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (n.nullTargets as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (n.companions as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test fun A18_receipt_NotRequired_is_distinct_from_Absent_and_detached() {
        val witnesses = mutableMapOf("ls" to seal().settlement!!)
        val journal = mutableMapOf("ls" to JournalObservation.Absent)
        val remaining = mutableListOf(seal(ControlObligationFixtures.seal))
        val receipt = HandoverSettlementReceipt(HandoverSettlementTransition.RETIRED_NULL, "lop", LifetimeId("origin"),
            before, before, witnesses, journal, null, HandoverDemandObservation.NotRequired, remaining, false, true)
        witnesses.clear(); journal.clear(); remaining.clear()
        assertNull(receipt.demandId)
        assertNotEquals(HandoverDemandObservation.Absent, receipt.demand)
        assertEquals(HandoverDemandObservation.NotRequired, receipt.demand)
        assertEquals(setOf("ls"), receipt.witnesses.keys)
        assertEquals(mapOf("ls" to JournalObservation.Absent), receipt.journal)
        assertEquals(1, receipt.remainingSeals.size)
        assertTrue(receipt.blocksProtectedAdmission)
        assertTrue(runCatching { (receipt.witnesses as MutableMap).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (receipt.journal as MutableMap).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (receipt.remainingSeals as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }
    @Test fun A18_transition_type_has_only_R_N_L() {
        assertEquals(setOf("RETIRED_NAMESPACE", "CURRENT_NULL", "RETIRED_NULL"),
            HandoverSettlementTransition.entries.map { it.name }.toSet())
    }
    private fun receiptFlags(opaque: Boolean, metadata: Boolean) = HandoverSettlementReceipt(
        HandoverSettlementTransition.RETIRED_NULL, "lop", LifetimeId("origin"), before, before,
        mapOf("ls" to seal().settlement!!), mapOf("ls" to JournalObservation.Absent),
        null, HandoverDemandObservation.NotRequired, emptyList(), opaque, metadata)
    @Test fun A18_receipt_obligation_flag_blocks_admission() {
        assertTrue(receiptFlags(true, false).blocksProtectedAdmission)
    }
    @Test fun A18_receipt_without_flags_is_not_a_blocking_diagnosis() {
        assertFalse(receiptFlags(false, false).blocksProtectedAdmission)
    }
}
