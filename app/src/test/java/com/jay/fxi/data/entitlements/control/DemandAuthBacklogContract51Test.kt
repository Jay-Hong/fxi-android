package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fifty-first file — DemandAuthTransition.durableEffects (DT:154–168) direct boundary, including the private
 * exactRow (DT:273–277) reached through the proof record and the current record, left OPEN by the re-judgment (group ③).
 * Truth vector written here in production order: namespace needed ⇒ proof present (Q10namespace) and its raw fence equals
 * the after fence (Q10fence); then per effect: proof present (Q10) · node parses (DT:163) · proof row exact (Q10proof) ·
 * current row exact (Q10current), where "exact" = one location · same kind · interpreted · same payload (two rows with the
 * id are both read as uninterpretable — ControlRecordReader.kt:146–157 — so a duplicate is a location count, never an
 * interpretable pair). Guards mirrored:
 * after a failing presence/parse, later items of that effect are vacuously true. Twin = the normal decision with one
 * confirmed DEMAND effect (literal result true); negative = only the target false; role = durableEffects false.
 */
class DemandAuthBacklogContract51Test {
    private val e = F.request(id = "e", order = 7)
    private val current get() = F.read(F.raw(e))
    private fun proofOf(raw: Preferences) = ConfirmedControlSnapshot(F.read(raw))
    private fun rows(r: ControlRecordRead.Supported, id: String) = r.locations(id)
    private fun exact(tag: String, r: ControlRecordRead.Supported?, kind: ControlKind, id: String, node: ControlNode): List<Pair<String, Boolean>> {
        val l = r?.let { rows(it, id) } ?: emptyList()
        val single = l.size == 1
        val interp = single && l[0].second is ControlEntryRead.Interpreted
        return listOf("$tag.single" to single, "$tag.kind" to (!single || l[0].first == kind), "$tag.interpreted" to (!single || interp),
            // DT:276 `kind && payload`: the payload is not compared once the kind differs
            "$tag.payload" to (!interp || l[0].first != kind || (l[0].second as ControlEntryRead.Interpreted).original.toPayloadEntry() == node.toPayloadEntry()))
    }
    private fun vec(d: AcceptedQueryDecision, read: ControlRecordRead.Supported): List<Pair<String, Boolean>> {
        val needed = d.confirmedAfterFence != d.acceptedBeforeFence
        val ns = d.namespaceConfirmation
        val out = mutableListOf("namespaceProof" to (!needed || ns != null),
            "namespaceFence" to (!needed || ns == null || FenceV1(ns.record.original[DataStoreAccessEpochStore.OWNER_UID],
                ns.record.original[USER_EPOCH], ns.record.original[KRX_EPOCH]) == d.confirmedAfterFence))
        d.effects.forEachIndexed { i, x ->
            val proof = x.confirmation; val value = ControlSchema.read(x.kind, x.node)
            out += "e$i.proof" to (proof != null)
            out += "e$i.parses" to (proof == null || value != null)
            if (proof != null && value != null) { out += exact("e$i.proofRow", proof.record, x.kind, value.id, x.node); out += exact("e$i.currentRow", read, x.kind, value.id, x.node) }
            else { for (t in listOf("proofRow", "currentRow")) for (k in listOf("single", "kind", "interpreted", "payload")) out += "e$i.$t.$k" to true }
        }
        return out
    }
    private fun eff(node: ControlNode = e, proof: ConfirmedControlSnapshot? = proofOf(F.raw(e)), kind: ControlKind = ControlKind.DEMAND) = LifecycleDurableEffect(kind, node, proof)
    private fun no(id: String, only: String, d: AcceptedQueryDecision, read: ControlRecordRead.Supported = current) {
        val twin = F.decision(effects = listOf(eff()))
        assertEquals("truth vector: normal durable effects", emptySet<String>(), V.falses(vec(twin, current)))
        assertTrue("positive twin: the confirmed effect is durable", F.transition.durableEffects(twin, current))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vec(d, read)))
        assertFalse(F.eligible(id), F.transition.durableEffects(d, read))
    }

    // namespace (DT:155–158): the after fence differs from the before fence
    private val fenceU2 = F.fence.copy(userAccessEpoch = "u2")
    private fun rawU2() = F.raw(e).toMutablePreferences().apply { this[USER_EPOCH] = "u2" }.toPreferences()
    @Test fun Q10namespace() = no("Z.de.Q10namespace", "namespaceProof", F.decision(after = fenceU2, effects = listOf(eff()), namespace = null))
    @Test fun Q10fence() = no("Z.de.Q10fence", "namespaceFence", F.decision(after = fenceU2, effects = listOf(eff()), namespace = proofOf(F.raw(e))))
    @Test fun Q10namespace_twinWithProof() = assertTrue("positive: a matching namespace proof makes the fence change durable",
        F.transition.durableEffects(F.decision(after = fenceU2, effects = listOf(eff()), namespace = proofOf(rawU2())), current))

    // per effect (DT:160–166)
    @Test fun Q10proof_missing() = no("Z.de.Q10", "e0.proof", F.decision(effects = listOf(eff(proof = null))))
    private val unparsable get() = F.node(buildJsonObject { put("id", "e"); put("kind", "REQUEST"); put("future", true) }.toString())
    @Test fun Q10_parse() = no("Z.de.parse", "e0.parses", F.decision(effects = listOf(eff(node = unparsable))))
    @Test fun Q10proof_absent() = no("Z.de.Q10proof.absent", "e0.proofRow.single", F.decision(effects = listOf(eff(proof = proofOf(F.raw())))))
    @Test fun Q10proof_payload() = no("Z.de.Q10proof.payload", "e0.proofRow.payload", F.decision(effects = listOf(eff(proof = proofOf(F.raw(F.request(id = "e", order = 8)))))))
    @Test fun Q10current_absent() = no("Z.de.Q10current.absent", "e0.currentRow.single", F.decision(effects = listOf(eff())), F.read(F.raw()))
    // exactRow sub-conditions beyond absence (DT:275–276): two rows with the id · an uninterpretable row with the id
    private val opaqueE get() = F.node(buildJsonObject { put("id", "e"); put("kind", "REQUEST"); put("future", true) }.toString())
    @Test fun Q10proof_duplicate() = no("Z.de.Q10proof.duplicate", "e0.proofRow.single", F.decision(effects = listOf(eff(proof = proofOf(F.raw(e, e))))))
    @Test fun Q10proof_uninterpreted() = no("Z.de.Q10proof.uninterpreted", "e0.proofRow.interpreted", F.decision(effects = listOf(eff(proof = proofOf(F.raw(opaqueE))))))
    // one interpretable row with the id, but of another kind (a HOLD row "e") — only the kind differs
    private val holdE get() = ControlLifecycleEvidenceFixtures.raw(hold = "[" + ControlObligationFixtures.hold.replace("\"id\":\"h\"", "\"id\":\"e\"") + "]")
    @Test fun Q10proof_kind() = no("Z.de.Q10proof.kind", "e0.proofRow.kind", F.decision(effects = listOf(eff(proof = proofOf(holdE)))))
    @Test fun Q10current_duplicate() = no("Z.de.Q10current.duplicate", "e0.currentRow.single", F.decision(effects = listOf(eff())), F.read(F.raw(e, e)))
    @Test fun Q10current_payload() = no("Z.de.Q10current.payload", "e0.currentRow.payload", F.decision(effects = listOf(eff())), F.read(F.raw(F.request(id = "e", order = 8))))
}
