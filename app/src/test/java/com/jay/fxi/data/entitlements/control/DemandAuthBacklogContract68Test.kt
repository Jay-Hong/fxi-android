package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixty-eighth file — classification on the **original** fixtures of two units whose role is masked
 * downstream (re-judgment r3 kept them OPEN because the approved masking record was observed on a different fixture):
 *  - V.envelopes.evidence (C2): the evidence limit is the midpoint of the before/after evidence sizes, (2223 + 2439) / 2 =
 *    2331; the change is refused TooLarge on COMMAND_EVIDENCE (DT:42–43). Masking on this fixture: probe/mask-probe-c68.
 *  - T.guardCreate.class (C21): UPDATE_AUTH Initialize while a guard `g-other` (no AUTH, wait 30000) exists — Conflict
 *    TargetChanged (DT:32). Masking on this fixture: probe/mask-probe-c68.
 * Premises: the writer truth vector names only the target (sizes written here for the envelope); literal classification.
 */
class DemandAuthBacklogContract68Test {
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun decide(p: DemandAuthPlan, raw: Preferences, codec: ControlPayloadCodec = F.codec): RecordTransactionDecision<*> {
        val c = F.command(p)
        val read = ControlRecordReader(codec).read(raw) as ControlRecordRead.Supported
        return ControlLifecycleConfirmation(codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, read, F.context(), false, false)
    }
    private fun initPlan() = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life, 21), "g-new", "r-new")
    private fun writer(p: DemandAuthPlan, raw: Preferences) = V.falses(V.decideGates(p, F.context(), raw)) +
        V.falses(V.eligibility(p, F.runtime(), raw)) + V.falses(V.commonPremises(p, raw, "command"))
    private fun bytes(raw: Preferences, key: Preferences.Key<String>) = raw[key]!!.toByteArray(Charsets.UTF_8).size

    @Test fun V_envelopesEvidence_classification() {
        val rows = (1..10).map {
            ControlAppliedEvidence.node(AppliedEvidence.Lifecycle("old-$it", "00000000-0000-0000-0000-%012d".format(it),
                LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "gone-$it", LifecycleEffect.REMOVE))))
        }
        val key = ControlLifecycleEvidenceFixtures.evidenceKey
        val before = F.raw().toMutablePreferences().apply { this[key] = JsonArray(rows).toString() }.toPreferences()
        F.schema(before)
        val p = initPlan(); assertNull("prepared", p.preparationFailure)
        assertEquals("premise: writer truth vector holds", emptySet<String>(), writer(p, before))
        val reference = (decide(p, before) as RecordTransactionDecision.Confirm).candidate
        val limit = (bytes(before, key) + bytes(reference, key)) / 2
        assertEquals("fixture: the C2 midpoint limit", 2331, limit)
        assertEquals("premise: only the evidence size exceeds the limit", setOf("evidenceFits"), V.falses(listOf(
            "demandFits" to (bytes(reference, ControlRecordKeys.payload(ControlKind.DEMAND)) <= limit), "evidenceFits" to (bytes(reference, key) <= limit))))
        val result = negative(decide(p, before, ControlPayloadCodec(maxPayloadBytes = limit)))
        assertTrue("classification: the evidence envelope is too large", result is ControlStoreResult.Rejected &&
            result.reason == RejectionReason.TooLarge(ControlPayloadKey.COMMAND_EVIDENCE, 2439, 2331))
    }

    @Test fun T_guardCreateOther_classification() {
        val p = initPlan(); assertNull("prepared", p.preparationFailure)
        val before = F.raw(F.guard(auth = null, wait = 30000, id = "g-other")); F.schema(before)
        assertEquals("premise: only guard creation is unavailable", setOf("dt.guardCreate"), writer(p, before))
        val result = negative(decide(p, before))
        assertTrue("classification: guard creation conflicts", result is ControlStoreResult.Conflict && result.reason == ConflictReason.TargetChanged)
    }
}
