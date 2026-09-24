package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixty-seventh file — the Edit.prepare connection (ControlCommand.kt:35–41) for the two A09 inputs STEP3ZK
 * r10 kept OPEN (design §9.3): A09b the generic edit **creates** AUTH on a guard without it; A09c the generic edit changes the
 * AUTH orders `true/10/20 → true/11/22`. Same form as C66 A09_editVector: the Edit.prepare vector (pure edit written · SEAL
 * non-settlement · AUTH literal preserved) over literal nodes, the pure editor's output asserted equal to the literal as a
 * premise, twin = the empty edit of the same guard (written), role = the generic edit is not written.
 */
class DemandAuthBacklogContract67Test {
    private fun editVec(before: ControlNode, after: ControlNode?) = listOf(
        "written" to (after != null),
        "nonSettlement" to true, // DEMAND, not SEAL
        "authPreserved" to (after == null || ControlSchema.read(ControlKind.DEMAND, before) !is ScheduleGuardV1 ||
            before.toPayloadEntry().fields["auth"]?.toString() == after.toPayloadEntry().fields["auth"]?.toString()))
    private fun editNo(id: String, g: ControlNode, change: ControlEditor.() -> Unit, changedLiteral: ControlNode) {
        val emptyEdit: ControlEditor.() -> Unit = {}
        assertEquals("premise: the pure empty edit writes the preimage", g.toPayloadEntry(),
            (ControlObligations.editExisting(ControlKind.DEMAND, g, emptyEdit) as ControlWriteResult.Written).node.toPayloadEntry())
        assertEquals("premise: the pure change writes the literal", changedLiteral.toPayloadEntry(),
            (ControlObligations.editExisting(ControlKind.DEMAND, g, change) as ControlWriteResult.Written).node.toPayloadEntry())
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(editVec(g, g)))
        assertTrue("positive twin: the AUTH-preserving generic edit is written", ControlMutation.Edit.prepare(ControlKind.DEMAND, g, emptyEdit).changed is ControlWriteResult.Written)
        assertEquals("truth vector: only the AUTH literal differs", setOf("authPreserved"), V.falses(editVec(g, changedLiteral)))
        assertFalse(F.eligible(id), ControlMutation.Edit.prepare(ControlKind.DEMAND, g, change).changed is ControlWriteResult.Written)
    }
    private val resumed = F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0)
    @Test fun A09b_editCreate() {
        val authLiteral = F.guard(auth = resumed).toPayloadEntry().fields["auth"]!!.toString()
        editNo("Z.gen.A09b.edit", F.guard(auth = null), { createChild("auth") { literal(authLiteral) } }, F.guard(auth = resumed))
    }
    @Test fun A09c_editOrders() = editNo("Z.gen.A09c.edit", F.guard(),
        { descend("auth") { set("authStateOrder", ControlScalar.Integer(11)); set("authStopAppliedOrder", ControlScalar.Integer(22)) } },
        F.guard(F.auth.copy(authStateOrder = 11, authStopAppliedOrder = 22)))
}
