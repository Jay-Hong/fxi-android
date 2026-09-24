package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixty-second file — classification of the evidence size rejection (DemandAuthTransition.kt:42–43,
 * T.evidenceReason.class · V.envelopes.evidence). With the append rejection bypassed the candidate validator still refuses
 * (DT:44, evidence rows ≠ expected), so the C57 W_evidence_size role (not Confirm) is held twice; this method states the
 * same premises (writer truth vector holds, only the evidence size exceeds the limit) and asserts the literal rejection:
 * TooLarge on COMMAND_EVIDENCE at 2439 bytes against the 2300 limit (the C21 fixture).
 */
class DemandAuthBacklogContract62Test {
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun decide(p: DemandAuthPlan, raw: Preferences, codec: ControlPayloadCodec): RecordTransactionDecision<*> {
        val c = F.command(p)
        val read = ControlRecordReader(codec).read(raw) as ControlRecordRead.Supported
        return ControlLifecycleConfirmation(codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, read, F.context(), false, false)
    }
    @Test fun W_evidence_size_classification() {
        val rows = (1..10).map {
            ControlAppliedEvidence.node(AppliedEvidence.Lifecycle("old-$it", "00000000-0000-0000-0000-%012d".format(it),
                LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "gone-$it", LifecycleEffect.REMOVE))))
        }
        val before = F.raw().toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = JsonArray(rows).toString() }.toPreferences()
        F.schema(before)
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life, 21), "g-new", "r-new")
        assertNull("prepared", p.preparationFailure)
        assertEquals("premise: writer truth vector holds", emptySet<String>(), V.falses(V.decideGates(p, F.context(), before)) +
            V.falses(V.eligibility(p, F.runtime(), before)) + V.falses(V.commonPremises(p, before, "command")))
        val twin = decide(p, before, F.codec) as RecordTransactionDecision.Confirm
        val sizes = listOf("demandFits" to (twin.candidate[ControlRecordKeys.payload(ControlKind.DEMAND)]!!.toByteArray(Charsets.UTF_8).size <= 2300),
            "evidenceFits" to (twin.candidate[ControlLifecycleEvidenceFixtures.evidenceKey]!!.toByteArray(Charsets.UTF_8).size <= 2300))
        assertEquals("premise: only the evidence size exceeds the limit", setOf("evidenceFits"), V.falses(sizes))
        val result = negative(decide(p, before, ControlPayloadCodec(maxPayloadBytes = 2300)))
        assertTrue("classification: the evidence envelope is too large", result is ControlStoreResult.Rejected &&
            result.reason == RejectionReason.TooLarge(ControlPayloadKey.COMMAND_EVIDENCE, 2439, 2300))
    }
}
