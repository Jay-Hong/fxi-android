package com.jay.fxi.data.entitlements.controllongrun

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.*
import com.jay.fxi.data.entitlements.control.FloorGuardFixtures as F
import org.junit.Assert.*
import org.junit.Test

/** Exact envelope boundaries are deliberately outside control.* discovery. */
class FloorGuardEnvelopeLongRunTest {
    private val codec = ControlPayloadCodec()
    private val lax = ControlPayloadCodec(maxPayloadBytes = 100000, maxDepth = 70)
    private fun row(id: String) = AppliedEvidence.Lifecycle(id, "11111111-1111-4111-8111-111111111111",
        LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "prior", LifecycleEffect.REMOVE)))
    private fun bytes(id: String, evidence: Boolean, size: Int, token: String) {
        val short = if (evidence) "[${ControlAppliedEvidence.node(row(token))}]" else "[${DemandAuthFixtures.request(id = token).toPayloadEntry().fields}]"
        val padded = token + "x".repeat(size - short.toByteArray(Charsets.UTF_8).size)
        val raw = if (evidence) "[${ControlAppliedEvidence.node(row(padded))}]" else "[${DemandAuthFixtures.request(id = padded).toPayloadEntry().fields}]"
        assertEquals(size, raw.toByteArray(Charsets.UTF_8).size)
        if (evidence) {
            assertFalse(ControlEvidenceReader.read(lax.decode(raw) as PayloadRead.Parsed).hasUninterpretable)
            val before = F.read(F.raw()); val candidate = before.original.toMutablePreferences()
            val error = ControlAppliedEvidence.append(candidate, before, row(padded), codec)
            assertTrue(F.atomic(id), if (size == 65536) error == null else error is RejectionReason.TooLarge && candidate == before.original)
        } else {
            val rows = (lax.decode(raw) as PayloadRead.Parsed).entries
            assertNotNull(ControlSchema.read(ControlKind.DEMAND, ControlNode.of((rows.single() as PayloadEntry.Obj).fields)))
            val result = NamespaceSettlementTransition(codec).encodeChanged(ControlKind.DEMAND, rows)
            assertTrue(F.atomic(id), if (size == 65536) result is NamespaceSettlementTransition.ChangedPayload.Encoded else
                result is NamespaceSettlementTransition.ChangedPayload.Rejected && result.reason is RejectionReason.TooLarge)
        }
    }
    private fun depth(id: String, evidence: Boolean, level: Int) {
        val raw = "[".repeat(level - 1) + "0" + "]".repeat(level - 1)
        if (evidence) {
            val before = F.raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = raw }
            val read = ControlRecordReader(lax).read(before) as ControlRecordRead.Supported
            assertTrue(read.hasUninterpretableMetadata) // Envelope-only boundary, never a legal writer input.
            val candidate = before.toMutablePreferences()
            val error = ControlAppliedEvidence.append(candidate, read, row("new"), codec)
            assertTrue(F.atomic(id), if (level == 64) error == null else error is RejectionReason.InvalidRequest && candidate == before)
        } else {
            val entries = (lax.decode(raw) as PayloadRead.Parsed).entries
            val result = NamespaceSettlementTransition(codec).encodeChanged(ControlKind.DEMAND, entries)
            assertTrue(F.atomic(id), if (level == 64) result is NamespaceSettlementTransition.ChangedPayload.Encoded else result is NamespaceSettlementTransition.ChangedPayload.Rejected)
        }
    }
    private fun writer(size: Int) {
        val c = F.command(); val key = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        val prior = row("prior")
        val small = "[${ControlAppliedEvidence.node(prior)},${ControlLifecycleEvidenceFixtures.wire(c)}]"
        val priorPadded = row("prior" + "x".repeat(size - small.toByteArray(Charsets.UTF_8).size))
        val before = F.raw(F.empty).toMutablePreferences().apply { this[key] = "[${ControlAppliedEvidence.node(priorPadded)}]" }
        assertFalse(F.read(before).hasUninterpretableMetadata)
        val d = ControlLifecycleConfirmation(codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), null, false, false)
        if (size == 65536) {
            assertTrue(d is RecordTransactionDecision.Confirm)
            assertEquals(size, (d as RecordTransactionDecision.Confirm).candidate[key]!!.toByteArray(Charsets.UTF_8).size)
            assertTrue(F.read(d.candidate).locations("g").isEmpty())
        } else {
            assertTrue(d is RecordTransactionDecision.Observe)
            val result = (d.value as ControlRecordStore.Outcome.Negative).result
            assertTrue(result is ControlStoreResult.Rejected && result.reason is RejectionReason.TooLarge)
            assertFalse(F.read(before).locations("g").isEmpty())
        }
    }
    @Test fun writerAt() = writer(65536)
    @Test fun writerOver() = writer(65537)
    @Test fun demandAt() = bytes("EG_C12demandAt", false, 65536, "ascii")
    @Test fun demandOver() = bytes("EG_C12demandOver", false, 65537, "ascii")
    @Test fun demandUnicodeAt() = bytes("EG_C12demandUnicodeAt", false, 65536, "한글😀")
    @Test fun demandUnicodeOver() = bytes("EG_C12demandUnicodeOver", false, 65537, "한글😀")
    @Test fun demandEscapeAt() = bytes("EG_C12demandEscapeAt", false, 65536, "\"\\\n")
    @Test fun demandEscapeOver() = bytes("EG_C12demandEscapeOver", false, 65537, "\"\\\n")
    @Test fun evidenceAt() = bytes("EG_C12evidenceAt", true, 65536, "ascii")
    @Test fun evidenceOver() = bytes("EG_C12evidenceOver", true, 65537, "ascii")
    @Test fun evidenceUnicodeAt() = bytes("EG_C12evidenceUnicodeAt", true, 65536, "한글😀")
    @Test fun evidenceUnicodeOver() = bytes("EG_C12evidenceUnicodeOver", true, 65537, "한글😀")
    @Test fun evidenceEscapeAt() = bytes("EG_C12evidenceEscapeAt", true, 65536, "\"\\\n")
    @Test fun evidenceEscapeOver() = bytes("EG_C12evidenceEscapeOver", true, 65537, "\"\\\n")
    @Test fun demandDepth64() = depth("EG_C12demandDepth64", false, 64)
    @Test fun demandDepth65() = depth("EG_C12demandDepth65", false, 65)
    @Test fun evidenceDepth64() = depth("EG_C12evidenceDepth64", true, 64)
    @Test fun evidenceDepth65() = depth("EG_C12evidenceDepth65", true, 65)
}
