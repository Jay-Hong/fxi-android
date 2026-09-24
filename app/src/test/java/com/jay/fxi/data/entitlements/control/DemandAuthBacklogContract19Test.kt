package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, nineteenth file: unchanged facts keep their original subtree, and an unchanged SEAL edit is written.
 *  - U.planGuardAuth.stateOrderLiteral — an AUTH change keeps an unchanged authStateOrder literal (DemandAuthPlan.kt:229),
 *    in the manner of C08.literals for the other AUTH numbers.
 *  - F.planFloor.initializeLiteral — Initialize keeps an unchanged floor subtree (DemandAuthPlan.kt:219 comment, :233).
 *  - N.planEnd.guardLiteral — END keeps the unchanged floor subtree of the original guard (DemandAuthPlan.kt:94).
 *  - X.editApply.sealUnchanged — a SEAL edit that changes nothing is written unchanged (ControlCommand.kt:38–39).
 * Respelled literals (-0 for 0) are fixed wire constants of the fixture, as in C08.literals and X.floorValues.literal;
 * expected subtrees are taken from the fixture's original node, never from the production output.
 */
class DemandAuthBacklogContract19Test {
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun fields(n: ControlNode) = n.toPayloadEntry().fields
    private fun respell(n: ControlNode, from: String, to: String): ControlNode {
        val text = fields(n).toString()
        assertEquals("fixture: one literal to respell", 1, text.split(from).size - 1)
        return F.node(text.replace(from, to))
    }

    // U.planGuardAuth — DemandAuthPlan.kt:220–231. A running AUTH (a stopped one needs a nonzero state order,
    // ControlSchema.kt:259): authStopAppliedOrder changes, authStateOrder stays 0 and is spelled -0.

    @Test fun U_planGuardAuth_stateOrderLiteral() {
        val old = respell(F.guard(F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 20)),
            "\"authStateOrder\":0,", "\"authStateOrder\":-0,")
        val before = guard(old)!!.auth!!
        assertEquals("fixture: authStateOrder value", 0L, before.authStateOrder)
        val changed = before.copy(authStopAppliedOrder = 21)
        assertNotEquals("fixture: the AUTH fact changes", before, changed)
        val beforeAuth = fields(old)["auth"] as JsonObject
        assertEquals("fixture: the respelled literal", Json.parseToJsonElement("-0"), beforeAuth["authStateOrder"])
        val after = guardNode(old, "g", changed, guard(old)!!.floor)
        assertEquals(F.atomic("U.planGuardAuth.stateOrderLiteral"), beforeAuth["authStateOrder"], (fields(after)["auth"] as JsonObject)["authStateOrder"])
    }

    // F.planFloor — DemandAuthPlan.kt:59–61 and :233–239. A guard without AUTH whose floor anchor 0 is spelled -0.

    @Test fun F_planFloor_initializeLiteral() {
        val g = respell(F.guard(auth = null, wait = 30000), "\"anchorElapsedMillis\":10000,", "\"anchorElapsedMillis\":-0,")
        assertEquals("fixture: floor value", FloorV1("boot", 0, 30000, F.life), guard(g)!!.floor)
        assertEquals("fixture: the respelled literal", Json.parseToJsonElement("-0"), (fields(g)["floor"] as JsonObject)["anchorElapsedMillis"])
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertNotNull("fixture: Initialize installs AUTH", guard(p.guardAfter)?.auth)
        assertEquals(F.atomic("F.planFloor.initializeLiteral"), fields(g)["floor"], p.guardAfter?.let { fields(it)["floor"] })
    }

    // N.planEnd — DemandAuthPlan.kt:89–96. END hands AUTH to the current binding; the floor fact is unchanged.

    @Test fun N_planEnd_guardLiteral() {
        val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
        val g = respell(F.guard(auth = oldAuth, wait = 30000), "\"anchorElapsedMillis\":10000,", "\"anchorElapsedMillis\":-0,")
        assertEquals("fixture: floor value", FloorV1("boot", 0, 30000, F.life), guard(g)!!.floor)
        val closure = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
        val p = DemandAuthPlan.end(g, emptyList(), F.binding, closure, F.binding, orders)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: the floor fact is unchanged", guard(g)!!.floor, guard(p.guardAfter)?.floor)
        assertNotEquals("fixture: the AUTH fact changes", guard(g)!!.auth, guard(p.guardAfter)?.auth)
        assertEquals(F.atomic("N.planEnd.guardLiteral"), fields(g)["floor"], p.guardAfter?.let { fields(it)["floor"] })
    }

    // X.editApply — ControlCommand.kt:35–41. The pure editor writes an unchanged SEAL; the mutation keeps it.

    @Test fun X_editApply_sealUnchanged() {
        val preimage = ControlObligationFixtures.node(ControlObligationFixtures.seal)
        assertNotNull("fixture: an interpreted SEAL", ControlSchema.read(ControlKind.SEAL, preimage))
        val pure = ControlObligations.editExisting(ControlKind.SEAL, preimage) { set("epoch", ControlScalar.Text("old")) }
        assertTrue("fixture: the pure editor writes", pure is ControlWriteResult.Written)
        assertEquals("fixture: the edit changes nothing", preimage.toPayloadEntry(), (pure as ControlWriteResult.Written).node.toPayloadEntry())
        val action = ControlMutation.Edit.prepare(ControlKind.SEAL, preimage) { set("epoch", ControlScalar.Text("old")) }
        assertEquals(F.atomic("X.editApply.sealUnchanged"), preimage.toPayloadEntry(),
            (action.changed as? ControlWriteResult.Written)?.node?.toPayloadEntry())
    }
}
