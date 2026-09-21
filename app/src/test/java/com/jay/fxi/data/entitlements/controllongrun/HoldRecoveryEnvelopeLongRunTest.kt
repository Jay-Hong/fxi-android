package com.jay.fxi.data.entitlements.controllongrun

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.*
import com.jay.fxi.data.entitlements.control.HoldRecoveryFixtures as F
import org.junit.Assert.*
import org.junit.Test

/** Exact envelope boundaries are deliberately outside control.* discovery. */
class HoldRecoveryEnvelopeLongRunTest {
    private val codec = ControlPayloadCodec()
    private val lax = ControlPayloadCodec(maxPayloadBytes = 100000, maxDepth = 70)
    private fun row(id: String) = AppliedEvidence.Lifecycle(id, "11111111-1111-4111-8111-111111111111",
        LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "prior", LifecycleEffect.REMOVE)))
    private fun bytes(id: String, evidence: Boolean, size: Int, token: String) {
        val short = if (evidence) "[${ControlAppliedEvidence.node(row(token))}]" else "[${DemandAuthFixtures.request(id = token).toPayloadEntry().fields}]"
        val extraBytes = size - short.toByteArray(Charsets.UTF_8).size
        val tokenBytes = kotlinx.serialization.json.JsonPrimitive(token).toString()
            .toByteArray(Charsets.UTF_8).size - 2
        val padded = token.repeat(1 + extraBytes / tokenBytes) + "x".repeat(extraBytes % tokenBytes)
        val raw = if (evidence) "[${ControlAppliedEvidence.node(row(padded))}]" else "[${DemandAuthFixtures.request(id = padded).toPayloadEntry().fields}]"
        assertEquals(size, raw.toByteArray(Charsets.UTF_8).size)
        if (evidence) {
            assertFalse(ControlEvidenceReader.read(lax.decode(raw) as PayloadRead.Parsed).hasUninterpretable)
            val before = F.read(F.before()); val candidate = before.original.toMutablePreferences()
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
            val before = F.before().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = raw }
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
        val p=F.plan(); val c=F.command(p); val key=ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
        val own=ControlLifecycleEvidenceFixtures.wire("RECOVER_HOLD",
            """{"kind":"HOLD","id":"h","effect":"REMOVE"},{"kind":"DEMAND","id":"new-request","effect":"CREATE"},{"kind":"DEMAND","id":"g","effect":"REPLACE"}""",
            c.id,c.ownerTrackingLifetimeId.value)
        val prior=ControlLifecycleEvidenceFixtures.wire(command="prior")
        val short="[$prior,$own]"
        val padded=ControlLifecycleEvidenceFixtures.wire(command="prior"+"x".repeat(size-short.toByteArray(Charsets.UTF_8).size))
        val before=F.before().toMutablePreferences().apply { this[key]="[$padded]" }
        assertFalse(F.read(before).hasUninterpretableMetadata)
        val d=ControlLifecycleConfirmation(codec).decide(c,p.descriptor(),F.read(before),F.context(),false,false)
        if(size==65536) {
            assertTrue(F.atomic("RH_C12writerAt"),d is RecordTransactionDecision.Confirm)
            val candidate=(d as RecordTransactionDecision.Confirm).candidate
            assertEquals(F.atomic("RH_C12writerAt"),size,candidate[key]!!.toByteArray(Charsets.UTF_8).size)
            assertTrue(F.read(candidate).locations("h").isEmpty())
        } else {
            assertTrue(F.atomic("RH_C12writerOver"),d is RecordTransactionDecision.Observe)
            val result=((d as RecordTransactionDecision.Observe).value as ControlRecordStore.Outcome.Negative).result
            assertTrue(F.atomic("RH_C12writerOver"),result is ControlStoreResult.Rejected && result.reason is RejectionReason.TooLarge)
            assertFalse(F.read(before).locations("h").isEmpty())
        }
    }
    @Test fun writerAt() = writer(65536)
    @Test fun writerOver() = writer(65537)
    @Test fun demandAt() = bytes("RH_C12demandAt", false, 65536, "ascii")
    @Test fun demandOver() = bytes("RH_C12demandOver", false, 65537, "ascii")
    @Test fun demandUnicodeAt() = bytes("RH_C12demandUnicodeAt", false, 65536, "한글😀")
    @Test fun demandUnicodeOver() = bytes("RH_C12demandUnicodeOver", false, 65537, "한글😀")
    @Test fun demandEscapeAt() = bytes("RH_C12demandEscapeAt", false, 65536, "\"\\\n")
    @Test fun demandEscapeOver() = bytes("RH_C12demandEscapeOver", false, 65537, "\"\\\n")
    @Test fun evidenceAt() = bytes("RH_C12evidenceAt", true, 65536, "ascii")
    @Test fun evidenceOver() = bytes("RH_C12evidenceOver", true, 65537, "ascii")
    @Test fun evidenceUnicodeAt() = bytes("RH_C12evidenceUnicodeAt", true, 65536, "한글😀")
    @Test fun evidenceUnicodeOver() = bytes("RH_C12evidenceUnicodeOver", true, 65537, "한글😀")
    @Test fun evidenceEscapeAt() = bytes("RH_C12evidenceEscapeAt", true, 65536, "\"\\\n")
    @Test fun evidenceEscapeOver() = bytes("RH_C12evidenceEscapeOver", true, 65537, "\"\\\n")
    @Test fun demandDepth64() = depth("RH_C12demandDepth64", false, 64)
    @Test fun demandDepth65() = depth("RH_C12demandDepth65", false, 65)
    @Test fun evidenceDepth64() = depth("RH_C12evidenceDepth64", true, 64)
    @Test fun evidenceDepth65() = depth("RH_C12evidenceDepth65", true, 65)
}
