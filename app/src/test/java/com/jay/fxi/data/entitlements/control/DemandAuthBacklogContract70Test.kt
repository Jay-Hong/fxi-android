package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * 5e-1 contract, seventieth file — the three units STEP3ZK r12 reopened because their vectors used the production builder /
 * editor output (C55 A10_add, C57 X_addApply_seal / X_editApply_seal). Same form as C69 Add and C67 Edit: the vector is
 * computed over **literal nodes**, and the production builder / pure editor output is first asserted equal to that
 * literal as a premise; twin and role as before.
 *  - A10a (DemandAuthGenericTest): Add.prepare of a guard carrying a resumed AUTH is not built.
 *  - X.addApply (C20): Add.prepare of a SEAL carrying a settlement is not built (ControlCommand.kt:22 SEAL term).
 *  - X.editApply (C20): Edit.prepare of a SEAL whose edit creates a settlement is not written (CC:38–39).
 */
class DemandAuthBacklogContract70Test {
    private fun withId(node: ControlNode, id: String) = JsonObject(node.toPayloadEntry().fields + ("id" to JsonPrimitive(id))).toString()
    private fun built(kind: ControlKind, raw: String) = (ControlObligations.build(kind) { literal(raw) } as ControlWriteResult.Written).node

    // A10a — DEMAND guard with a resumed AUTH
    private fun demandVec(lit: ControlNode, issued: String): List<Pair<String, Boolean>> {
        val v = ControlSchema.read(ControlKind.DEMAND, lit)
        return listOf("parses" to (v != null), "idMatches" to (v == null || v.id == issued),
            "allowed" to (v == null || !(v is ScheduleGuardV1 && "auth" in lit.names)))
    }
    @Test fun A10a_addLiteral() {
        val issued = UUID.randomUUID(); val iss = issued.toString()
        val resumed = F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0)
        val twinRaw = withId(F.guard(auth = null, wait = 1000), iss); val raw = withId(F.guard(auth = resumed), iss)
        for (r in listOf(twinRaw, raw)) assertEquals("premise: the builder writes the literal", F.node(r).toPayloadEntry(), built(ControlKind.DEMAND, r).toPayloadEntry())
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(demandVec(F.node(twinRaw), iss)))
        assertTrue("positive twin: a guard without AUTH is built", ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(twinRaw) }.built is ControlWriteResult.Written)
        assertEquals("truth vector: only the guard's AUTH differs", setOf("allowed"), V.falses(demandVec(F.node(raw), iss)))
        assertFalse(F.eligible("Z.gen.A10a.addLiteral"), ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(raw) }.built is ControlWriteResult.Written)
    }

    // X.addApply — SEAL with a settlement
    private fun sealVec(lit: ControlNode, id: String): List<Pair<String, Boolean>> {
        val v = (ControlObligations.read(ControlKind.SEAL, lit) as? ControlEntryRead.Interpreted)?.value
        return listOf("parses" to (v != null), "idMatches" to (v == null || v.id == id),
            "sealNoSettlement" to (v == null || v !is SealV1 || v.settlement == null), "genericAllowed" to true /* not a guard */)
    }
    @Test fun X_addApply_sealLiteral() {
        val plainId = UUID(0, 1); val settledId = UUID(0, 2)
        val plainRaw = withId(ControlObligationFixtures.node(ControlObligationFixtures.nullSeal), plainId.toString())
        val settledRaw = withId(ControlObligationFixtures.node(ControlObligationFixtures.settledSeal), settledId.toString())
        for (r in listOf(plainRaw, settledRaw)) assertEquals("premise: the builder writes the literal", F.node(r).toPayloadEntry(), built(ControlKind.SEAL, r).toPayloadEntry())
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(sealVec(F.node(plainRaw), plainId.toString())))
        assertTrue("positive twin: a SEAL without settlement is built", ControlMutation.Add.prepare(ControlKind.SEAL, plainId) { literal(plainRaw) }.built is ControlWriteResult.Written)
        assertEquals("truth vector: only the settlement differs", setOf("sealNoSettlement"), V.falses(sealVec(F.node(settledRaw), settledId.toString())))
        assertFalse(F.eligible("Z.gen.addSeal.literal"), ControlMutation.Add.prepare(ControlKind.SEAL, settledId) { literal(settledRaw) }.built is ControlWriteResult.Written)
    }

    // X.editApply — SEAL edit that creates a settlement
    private fun editVec(before: ControlNode, after: ControlNode?) = listOf(
        "written" to (after != null),
        "nonSettlement" to (after != null && before.toPayloadEntry() == after.toPayloadEntry()),
        "authPreserved" to true /* SEAL, not a guard */)
    @Test fun X_editApply_sealLiteral() {
        val pre = ControlObligationFixtures.node(ControlObligationFixtures.nullSeal)
        val settledLiteral = ControlObligationFixtures.node(ControlObligationFixtures.settledSeal)
        val emptyEdit: ControlEditor.() -> Unit = {}
        val settle: ControlEditor.() -> Unit = { createChild("settlement") { literal(ControlObligationFixtures.settlement) } }
        assertEquals("premise: the pure empty edit writes the preimage", pre.toPayloadEntry(),
            (ControlObligations.editExisting(ControlKind.SEAL, pre, emptyEdit) as ControlWriteResult.Written).node.toPayloadEntry())
        assertEquals("premise: the pure settlement edit writes the settled SEAL literal", settledLiteral.toPayloadEntry(),
            (ControlObligations.editExisting(ControlKind.SEAL, pre, settle) as ControlWriteResult.Written).node.toPayloadEntry())
        assertEquals("truth vector: twin (empty edit)", emptySet<String>(), V.falses(editVec(pre, pre)))
        assertTrue("positive twin: the payload-preserving SEAL edit is written", ControlMutation.Edit.prepare(ControlKind.SEAL, pre, emptyEdit).changed is ControlWriteResult.Written)
        assertEquals("truth vector: only the SEAL payload differs", setOf("nonSettlement"), V.falses(editVec(pre, settledLiteral)))
        assertFalse(F.eligible("Z.gen.editSeal.literal"), ControlMutation.Edit.prepare(ControlKind.SEAL, pre, settle).changed is ControlWriteResult.Written)
    }
}
