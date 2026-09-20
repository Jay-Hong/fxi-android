package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DemandAuthGenericTest {
    private fun editNo(id: String, before: ControlNode, change: ControlEditor.() -> Unit) {
        val pure = ControlObligations.editExisting(ControlKind.DEMAND, before, change)
        assertTrue("fixture must pass pure editor and schema", pure is ControlWriteResult.Written)
        val mutation = ControlMutation.Edit.prepare(ControlKind.DEMAND, before, change)
        assertFalse(F.eligible(id), mutation.changed is ControlWriteResult.Written)
    }
    @Test fun A09a() = editNo("A09a", F.guard()) { descend("auth") { set("authStopped", ControlScalar.Flag(false)); set("authStateOrder", ControlScalar.Integer(21)) } }
    @Test fun A09b() = editNo("A09b", F.guard(auth = null)) { createChild("auth") { literal(F.guard(auth = F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0)).toPayloadEntry().fields["auth"].toString()) } }
    @Test fun A09c() = editNo("A09c", F.guard()) { descend("auth") { set("authStateOrder", ControlScalar.Integer(11)); set("authStopAppliedOrder", ControlScalar.Integer(22)) } }
    @Test fun A09d() {
        val raw = F.guard(auth = F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0)).toPayloadEntry().fields.toString()
        val before = F.node(raw.replace("\"authStateOrder\":0", "\"authStateOrder\":-0"))
        val after = F.node(raw)
        assertEquals(guard(before), guard(after))
        assertFalse(F.eligible("A09d"), genericAuthUnchanged(ControlKind.DEMAND, before, after))
    }
    private fun addNo(id: String, node: ControlNode) {
        val issued = UUID.randomUUID()
        val raw = JsonObject(node.toPayloadEntry().fields + ("id" to JsonPrimitive(issued.toString()))).toString()
        assertTrue(ControlObligations.build(ControlKind.DEMAND) { literal(raw) } is ControlWriteResult.Written)
        val add = ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(raw) }
        assertFalse(F.eligible(id), add.built is ControlWriteResult.Written)
    }
    @Test fun A10a() = addNo("A10a", F.guard(auth = F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0)))
    @Test fun A10b() = addNo("A10b", F.guard())
    @Test fun A10c() = addNo("A10c", F.guard(wait = 60000))
    @Test fun authAbsentAndUnchangedPositive() {
        assertTrue(ControlMutation.Edit.prepare(ControlKind.DEMAND, F.guard()) {}.changed is ControlWriteResult.Written)
        val issued = UUID.randomUUID()
        val raw = F.guard(auth = null, wait = 1000, id = issued.toString()).toPayloadEntry().fields.toString()
        assertTrue(ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(raw) }.built is ControlWriteResult.Written)
    }
}
