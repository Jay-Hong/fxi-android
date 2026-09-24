package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, twenty-second file: positive generic operations (obligation table 194 X.addApply, 195 X.editApply —
 * "별도 양성·산출 계약").
 *  - X.addApply.builderId — Add.prepare hands the issued id to the builder; a builder that writes the id it is given
 *    produces a Written SEAL with that id (ControlCommand.kt:17–24).
 *  - X.editApply.guardUnchanged — an edit that changes nothing on a guard with AUTH is Written unchanged
 *    (ControlCommand.kt:35–41, genericAuthUnchanged CC:91–95). For a guard the pure editor permits only AUTH changes
 *    (ControlObligations.kt:234–235) and A09 forbids them here, so the unchanged edit is its only positive generic edit.
 * Expected payloads are the fixture's original nodes; the production output is an observed value only.
 */
class DemandAuthBacklogContract22Test {
    @Test fun X_addApply_builderId() {
        val issued = UUID(0, 7)
        val expected = ControlObligationFixtures.node(ControlObligationFixtures.nullSeal.replace("\"id\":\"s\"", "\"id\":\"$issued\""))
        assertNotNull("fixture: an interpretable SEAL without settlement",
            (ControlSchema.read(ControlKind.SEAL, expected) as? SealV1)?.takeIf { it.settlement == null })
        val add = ControlMutation.Add.prepare(ControlKind.SEAL, issued) { id -> literal(ControlObligationFixtures.nullSeal); set("id", ControlScalar.Text(id)) }
        assertEquals(F.atomic("X.addApply.builderId"), Triple(ControlKind.SEAL, issued.toString(), expected.toPayloadEntry()),
            Triple(add.kind, add.proposedId, (add.built as? ControlWriteResult.Written)?.node?.toPayloadEntry()))
    }

    @Test fun X_editApply_guardUnchanged() {
        val before = F.guard(wait = 30000)
        assertNotNull("fixture: an interpretable guard with AUTH", guard(before)?.auth)
        val pure = ControlObligations.editExisting(ControlKind.DEMAND, before) {}
        assertTrue("fixture: the pure editor writes", pure is ControlWriteResult.Written)
        assertEquals("fixture: the edit changes nothing", before.toPayloadEntry(), (pure as ControlWriteResult.Written).node.toPayloadEntry())
        val action = ControlMutation.Edit.prepare(ControlKind.DEMAND, before) {}
        assertEquals(F.atomic("X.editApply.guardUnchanged"), before.toPayloadEntry(),
            (action.changed as? ControlWriteResult.Written)?.node?.toPayloadEntry())
    }
}
