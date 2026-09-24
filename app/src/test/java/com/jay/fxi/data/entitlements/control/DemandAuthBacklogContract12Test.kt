package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * 5e-1 contract, twelfth file: the positive direction of the generic Add/Edit preparation (CC:17-42).
 *
 * A09a-c and A10a-c are the negative half — a generic mutation must not install or change AUTH. Nothing asserted
 * the other half, so every mutant that makes a *valid* generic write be rejected, lose the issued id, or change the
 * declared kind was observed only through `ControlAppliedLifecycleTest:58`'s `confirmed()` storage premise.
 * Design §9.1 545 forbids counting that premise as a kill.
 *
 * Three boundaries, each asserted with its own independent expectation (§9.1 539):
 *
 *   - CC:18·24 the Add carries the issued id and the built node keeps it.
 *   - CC:22 an unsettled SEAL Add is allowed; only a settled one is not.
 *   - CC:36-41 an Edit that leaves AUTH alone is written, with the declared kind and preimage preserved.
 */
class DemandAuthBacklogContract12Test {

    private fun withId(raw: String, id: String): String =
        JsonObject(F.node(raw).toPayloadEntry().fields + ("id" to JsonPrimitive(id))).toString()

    // RC.generic.addPositive — CC:17-24. A schema-valid REQUEST add keeps the issued id and is written.

    @Test fun RC_generic_addPositive() {
        val issued = UUID.randomUUID()
        val raw = withId(ControlObligationFixtures.request, issued.toString())
        assertTrue("fixture: the node passes the pure builder",
            ControlObligations.build(ControlKind.DEMAND) { literal(raw) } is ControlWriteResult.Written)
        val add = ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(raw) }
        assertEquals(F.atomic("RC.generic.addPositive"), issued.toString(), add.proposedId)
        assertEquals(F.atomic("RC.generic.addPositive"), ControlKind.DEMAND, add.kind)
        assertTrue(F.atomic("RC.generic.addPositive"), add.built is ControlWriteResult.Written)
        assertEquals(F.atomic("RC.generic.addPositive"), issued.toString(),
            checkNotNull(ControlSchema.read(ControlKind.DEMAND, (add.built as ControlWriteResult.Written).node)).id)
    }

    // RC.generic.sealAddPositive — CC:22. A SEAL with no settlement is an ordinary add.

    @Test fun RC_generic_sealAddPositive() {
        val issued = UUID.randomUUID()
        val raw = withId(ControlObligationFixtures.seal, issued.toString())
        val built = ControlObligations.build(ControlKind.SEAL) { literal(raw) }
        assertTrue("fixture: the node passes the pure builder", built is ControlWriteResult.Written)
        val value = ControlSchema.read(ControlKind.SEAL, (built as ControlWriteResult.Written).node)
        assertTrue("fixture: the fixture is a SEAL", value is SealV1)
        assertNull("fixture: the SEAL is unsettled", (value as SealV1).settlement)
        val add = ControlMutation.Add.prepare(ControlKind.SEAL, issued) { literal(raw) }
        assertEquals(F.atomic("RC.generic.sealAddPositive"), issued.toString(), add.proposedId)
        assertTrue(F.atomic("RC.generic.sealAddPositive"), add.built is ControlWriteResult.Written)
    }

    // RC.generic.editPositive — CC:35-41. An edit that leaves AUTH alone is written with kind and preimage intact.
    //
    // A generic Edit may not change a ScheduleGuard's AUTH, and a floor change uses Edit.floor. A no-op generic Edit that
    // keeps the original is allowed (ControlObligations:99, DemandAuthGenericTest:38). To exercise a real value change
    // this contract raises a REQUEST's raisedAt from 4 to 9 (ControlObligations:231-233). That input has no AUTH, so it
    // does not verify preservation of a guard's AUTH subtree.
    //
    // The expected payload is built from the input fields; no production editor output is used as an expectation (§9.1 539).

    @Test fun RC_generic_editPositive() {
        val before = F.request()
        val original = checkNotNull(demand(before))
        assertEquals("fixture: the preimage is a REQUEST with raisedAt 4", 4L, original.raisedAt.value)
        val expectedFields = JsonObject(before.toPayloadEntry().fields + ("raisedAt" to JsonPrimitive(9)))
        val change: ControlEditor.() -> Unit = { set("raisedAt", ControlScalar.Integer(9)) }
        val pure = ControlObligations.editExisting(ControlKind.DEMAND, before, change)
        assertTrue("fixture: the change passes the pure editor", pure is ControlWriteResult.Written)
        assertEquals("fixture: the pure editor changes raisedAt only",
            expectedFields, (pure as ControlWriteResult.Written).node.toPayloadEntry().fields)
        val edit = ControlMutation.Edit.prepare(ControlKind.DEMAND, before, change)
        assertEquals(F.atomic("RC.generic.editPositive"), ControlKind.DEMAND, edit.kind)
        assertSame(F.atomic("RC.generic.editPositive"), before, edit.before)
        assertTrue(F.atomic("RC.generic.editPositive"), edit.changed is ControlWriteResult.Written)
        assertEquals(F.atomic("RC.generic.editPositive"),
            expectedFields, (edit.changed as ControlWriteResult.Written).node.toPayloadEntry().fields)
    }
}
