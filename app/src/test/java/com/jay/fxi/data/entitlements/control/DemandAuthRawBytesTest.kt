package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class DemandAuthRawBytesTest {
    private fun reordered(before: ControlNode): ControlNode {
        val raw = before.toPayloadEntry().fields
        val auth = raw["auth"] as JsonObject
        val after = ControlNode.of(JsonObject(raw + ("auth" to JsonObject(auth.entries.reversed().associate { it.key to it.value }))))
        assertEquals(guard(before), guard(after))
        val codec = ControlPayloadCodec()
        val beforeText = (codec.encode(listOf(PayloadEntry.Obj(auth))) as PayloadWrite.Encoded).text
        val afterText = (codec.encode(listOf(PayloadEntry.Obj(after.toPayloadEntry().fields["auth"] as JsonObject))) as PayloadWrite.Encoded).text
        assertNotEquals("only AUTH subtree serialization differs", beforeText, afterText)
        return after
    }
    @Test fun authKeyOrder() {
        val before = F.guard()
        assertFalse(F.eligible("A09.order"), genericAuthUnchanged(ControlKind.DEMAND, before, reordered(before)))
    }
    @Test fun floorAuthKeyOrder() {
        val before = F.guard(wait = 60000)
        val after = reordered(before)
        assertEquals(guard(before)!!.floor, guard(after)!!.floor)
        assertFalse(F.atomic("A11.order"), ControlObligations.validFloorResult(before, after, guard(after)!!, guard(before)!!.floor!!))
    }
    @Test fun unchangedSerializationAndFloorEdit() {
        val before = F.guard(wait = 30000)
        assertTrue(genericAuthUnchanged(ControlKind.DEMAND, before, before))
        val after = ControlObligations.recordFloor(before, F.now, 60000, F.life) as ControlWriteResult.Written
        assertEquals(before.toPayloadEntry().fields["auth"].toString(), after.node.toPayloadEntry().fields["auth"].toString())
    }
}
