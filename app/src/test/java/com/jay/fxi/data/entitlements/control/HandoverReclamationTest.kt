package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.applied
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.lSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.nSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.nCompanion
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.rSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.record
import org.junit.Assert.*
import org.junit.Test

class HandoverReclamationTest {
    private val lifetime = OwnerTrackingLifetimeId.issue()
    private val sealKey = ControlRecordKeys.payload(ControlKind.SEAL)
    private val evidenceKey = ReclamationFixtures.evidenceKey
    private val handovers = listOf(
        applied("RETIRED_NAMESPACE", """["rs"]""", "null", "rop"),
        applied("CURRENT_NULL", """["ns","nc"]""", "\"nd\"", "nop"),
        applied(), applied(command = "current-l", lifetime = lifetime.value)
    ).joinToString(",")
    private fun mixed(): Preferences = record(
        seals = "[${ReclamationFixtures.settled()},$rSeal,$nSeal,$nCompanion,$lSeal]",
        evidence = "[${ReclamationFixtures.mutation()},${ReclamationFixtures.rotation()},$handovers]"
    ).toMutablePreferences().apply {
        this[byteArrayPreferencesKey("external-bytes")] = byteArrayOf(0, 1, -1)
    }.toPreferences()

    private fun decide(raw: Preferences) = ReclaimPreviousLifetimeEvidence.decide(
        ControlRecordReader().read(raw), lifetime, ControlPayloadCodec())

    private fun preserved(id: String) {
        val source = mixed()
        val read = ControlRecordReader().read(source) as ControlRecordRead.Supported
        assertFalse(read.blocksProtectedAdmission)
        val decision = decide(source)
        assertTrue(decision is RecordTransactionDecision.Confirm)
        assertNull(decision.value)
        val candidate = (decision as RecordTransactionDecision.Confirm).candidate
        val expected = source.toMutablePreferences().apply {
            this[sealKey] = "[$rSeal,$nSeal,$nCompanion,$lSeal]"
            this[evidenceKey] = "[$handovers]"
        }.toPreferences()
        assertEquals(expected, candidate) // Preferences performs ByteArray content comparison.
        assertEquals(source, read.original)
        assertTrue(id in ReclamationFixtures.ids(candidate[sealKey]!!))
        assertEquals(4, ReclamationFixtures.ids(candidate[evidenceKey]!!, "commandId").size)
    }

    @Test fun A11a_R_v1_and_applied_survive_mixed_reclamation() = preserved("rs")
    @Test fun A11b_N_v1_and_applied_survive_mixed_reclamation() = preserved("ns")
    @Test fun A11b_N_companion_namespace_and_applied_survive_mixed_reclamation() = preserved("nc")
    @Test fun A11c_L_v2_and_applied_survive_mixed_reclamation() = preserved("ls")

    @Test fun A11_only_old_settlements_confirm_original_without_normalization() {
        val source = record(evidence = " [ ${applied()} ] ")
        val read = ControlRecordReader().read(source)
        val result = ReclaimPreviousLifetimeEvidence.decide(read, lifetime, ControlPayloadCodec())
        assertSame(read.original, (result as RecordTransactionDecision.Confirm).candidate)
        assertNull(result.value)
    }

    @Test fun A11_current_settlement_is_preserved() {
        val source = record(evidence = "[${applied(lifetime = lifetime.value)}]")
        assertEquals(source, (decide(source) as RecordTransactionDecision.Confirm).candidate)
    }

    private fun refused(source: Preferences, reason: RecoveryReason) {
        assertTrue(decide(mixed()) is RecordTransactionDecision.Confirm)
        val saved = source.toPreferences()
        val attempt = runCatching { decide(source) }
        assertNull("must return recovery without throwing", attempt.exceptionOrNull())
        val decision = attempt.getOrThrow()
        assertTrue("must not produce a removal candidate", decision is RecordTransactionDecision.Observe)
        val result = decision.value as ControlEvidenceReclamationResult.RecoveryRequired
        assertEquals(reason, result.reason)
        assertEquals(saved, result.observation.original)
        assertEquals(saved, source)
    }

    private fun opaque(key: ControlPayloadKey, reason: RecoveryReason) = refused(mixed().toMutablePreferences().apply {
        this[ControlRecordKeys.payload(key)] = "[{\"future\":true}]"
    }, reason)

    @Test fun A13_opaque_seal_blocks_all_reclamation() = opaque(ControlPayloadKey.SEAL, RecoveryReason.UninterpretableObligations)
    @Test fun A13_opaque_demand_blocks_all_reclamation() = opaque(ControlPayloadKey.DEMAND, RecoveryReason.UninterpretableObligations)
    @Test fun A13_opaque_hold_blocks_all_reclamation() = opaque(ControlPayloadKey.HOLD, RecoveryReason.UninterpretableObligations)
    @Test fun A13_opaque_recovery_blocks_all_reclamation() = opaque(ControlPayloadKey.RECOVERY_INTENT, RecoveryReason.UninterpretableObligations)
    @Test fun A13_opaque_evidence_blocks_all_reclamation() = opaque(ControlPayloadKey.COMMAND_EVIDENCE, RecoveryReason.UninterpretableMetadata)
    @Test fun A13_nonempty_fence_blocks_all_reclamation() = opaque(ControlPayloadKey.SCOPE_FENCE, RecoveryReason.UninterpretableMetadata)

    @Test fun A13_duplicate_obligation_ids_block_all_reclamation() = refused(mixed().toMutablePreferences().apply {
        this[ControlRecordKeys.payload(ControlKind.DEMAND)] = "[${ControlObligationFixtures.request.replace("\"d\"", "\"ls\"")}]"
    }, RecoveryReason.UninterpretableObligations)

    @Test fun A13_duplicate_command_ids_block_all_reclamation() = refused(mixed().toMutablePreferences().apply {
        this[evidenceKey] = "[${ReclamationFixtures.mutation(command = "lop")},${applied()}]"
    }, RecoveryReason.UninterpretableMetadata)
}
