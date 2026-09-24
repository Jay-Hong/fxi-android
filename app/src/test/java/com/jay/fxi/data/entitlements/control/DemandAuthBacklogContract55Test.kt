package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * 5e-1 contract, fifty-fifth file — generic obligation boundaries left OPEN (group ⑥), direct with a truth vector written
 * here in production order, a twin with the literal result, one false condition, and the role:
 *  - genericAddAllowed (ControlCommand.kt:87–90, A10): a generic Add may not carry a guard's AUTH; A10a resumed AUTH ·
 *    A10b stopped AUTH · A10c AUTH with a floor. Connection: Add.prepare (CC:17–24) rejects it (built not Written).
 *  - genericAuthUnchanged (CC:91–95, A09): a generic Edit of a guard may not change its AUTH literal; A09a resume ·
 *    A09b AUTH created · A09c orders · A09d the literal `-0` against `0` (equal as values, different as serialization).
 *    Connection: Edit.prepare (CC:35–41) rejects it (changed not Written).
 *  - recordFloor input gates (ControlObligations.kt:124–127): parses · is a guard · now boot/elapsed readable · wait ≥ 0 ·
 *    origin non-empty · the old floor's remaining readable — role: the result is not Written.
 *  - validFloorResult (CO:151–155): A11a untouched subtrees keep their serialization · A11b the floor equals the expected.
 */
class DemandAuthBacklogContract55Test {
    private val resumed = F.auth.copy(authStopped = false, authStateOrder = 0, authStopAppliedOrder = 0)

    // genericAddAllowed — direct
    private fun addVec(node: ControlNode) = listOf("noGuardAuth" to !(ControlSchema.read(ControlKind.DEMAND, node) is ScheduleGuardV1 && "auth" in node.names))
    private fun addNo(id: String, node: ControlNode) {
        val twin = F.guard(auth = null, wait = 1000)
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(addVec(twin)))
        assertTrue("positive twin: a guard without AUTH may be added", genericAddAllowed(ControlSchema.read(ControlKind.DEMAND, twin)!!, twin))
        assertEquals("truth vector: only the guard's AUTH differs", setOf("noGuardAuth"), V.falses(addVec(node)))
        assertFalse(F.eligible(id), genericAddAllowed(ControlSchema.read(ControlKind.DEMAND, node)!!, node))
    }
    @Test fun A10a_direct() = addNo("Z.gen.A10a", F.guard(auth = resumed))
    @Test fun A10b_direct() = addNo("Z.gen.A10b", F.guard())
    @Test fun A10c_direct() = addNo("Z.gen.A10c", F.guard(wait = 60000))

    // Add.prepare — built Written only when the id matches, a SEAL carries no settlement and the generic add is allowed
    private fun withId(node: ControlNode, id: String) = JsonObject(node.toPayloadEntry().fields + ("id" to JsonPrimitive(id))).toString()
    private fun prepVec(raw: String, issued: String): List<Pair<String, Boolean>> {
        val built = ControlObligations.build(ControlKind.DEMAND) { literal(raw) }
        val node = (built as? ControlWriteResult.Written)?.node
        val value = node?.let { (ControlObligations.read(ControlKind.DEMAND, it) as ControlEntryRead.Interpreted).value }
        return listOf("built" to (node != null), "idMatches" to (value == null || value.id == issued),
            "allowed" to (value == null || !(value is ScheduleGuardV1 && "auth" in node!!.names)))
    }
    private fun prepNo(id: String, only: String, node: ControlNode, rawId: String? = null) {
        val issued = UUID.randomUUID(); val iss = issued.toString()
        val twinRaw = withId(F.guard(auth = null, wait = 1000), iss)
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(prepVec(twinRaw, iss)))
        assertTrue("positive twin: the add is built", ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(twinRaw) }.built is ControlWriteResult.Written)
        val raw = withId(node, rawId ?: iss)
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(prepVec(raw, iss)))
        assertFalse(F.eligible(id), ControlMutation.Add.prepare(ControlKind.DEMAND, issued) { literal(raw) }.built is ControlWriteResult.Written)
    }
    @Test fun A10_add() = prepNo("Z.gen.A10.add", "allowed", F.guard(auth = resumed))
    @Test fun addId_add() = prepNo("Z.gen.addId", "idMatches", F.guard(auth = null, wait = 1000), rawId = "other-id")

    // genericAuthUnchanged — direct
    private fun authVec(before: ControlNode, after: ControlNode) = listOf("authLiteral" to (ControlSchema.read(ControlKind.DEMAND, before) !is ScheduleGuardV1 ||
        before.toPayloadEntry().fields["auth"]?.toString() == after.toPayloadEntry().fields["auth"]?.toString()))
    private fun authNo(id: String, before: ControlNode, after: ControlNode) {
        val twinAfter = F.node(JsonObject(before.toPayloadEntry().fields + ("floor" to buildJsonObject { put("anchorBootId", "boot"); put("anchorElapsedMillis", 10000); put("waitMillis", 1000); put("originLifetimeId", "life") })).toString())
        assertEquals("truth vector: twin (floor only)", emptySet<String>(), V.falses(authVec(before, twinAfter)))
        assertTrue("positive twin: a floor-only edit keeps the AUTH literal", genericAuthUnchanged(ControlKind.DEMAND, before, twinAfter))
        assertEquals("truth vector: only the AUTH literal differs", setOf("authLiteral"), V.falses(authVec(before, after)))
        assertFalse(F.eligible(id), genericAuthUnchanged(ControlKind.DEMAND, before, after))
    }
    @Test fun A09a_direct() = authNo("Z.gen.A09a", F.guard(), F.guard(F.auth.copy(authStopped = false, authStateOrder = 21)))
    @Test fun A09b_direct() = authNo("Z.gen.A09b", F.guard(auth = null), F.guard(auth = resumed))
    @Test fun A09c_direct() = authNo("Z.gen.A09c", F.guard(), F.guard(F.auth.copy(authStateOrder = 11, authStopAppliedOrder = 22)))
    @Test fun A09d_direct() {
        val raw = F.guard(auth = resumed).toPayloadEntry().fields.toString()
        val before = F.node(raw.replace("\"authStateOrder\":0", "\"authStateOrder\":-0")); val after = F.node(raw)
        assertEquals("fixture: equal as values", guard(before), guard(after))
        authNo("Z.gen.A09d", before, after)
    }

    // Edit.prepare connection
    @Test fun A09_edit() {
        val g = F.guard()
        // twin: an edit that keeps the AUTH literal (the empty edit, as DemandAuthGenericTest.authAbsentAndUnchangedPositive;
        // a generic edit creating the floor child is not written — shown by this contract's first baseline)
        val twin = ControlMutation.Edit.prepare(ControlKind.DEMAND, g) {}
        assertTrue("positive twin: an AUTH-preserving generic edit is written", twin.changed is ControlWriteResult.Written)
        val change: ControlEditor.() -> Unit = { descend("auth") { set("authStopped", ControlScalar.Flag(false)); set("authStateOrder", ControlScalar.Integer(21)) } }
        val pure = ControlObligations.editExisting(ControlKind.DEMAND, g, change)
        assertTrue("fixture: the pure editor writes it", pure is ControlWriteResult.Written)
        assertEquals("truth vector: only the AUTH literal differs", setOf("authLiteral"), V.falses(authVec(g, (pure as ControlWriteResult.Written).node)))
        assertFalse(F.eligible("Z.gen.A09.edit"), ControlMutation.Edit.prepare(ControlKind.DEMAND, g, change).changed is ControlWriteResult.Written)
    }

    // recordFloor input gates
    private fun floorVec(original: ControlNode, now: BootReading, wait: Long, origin: LifetimeId): List<Pair<String, Boolean>> {
        val v = ControlSchema.read(ControlKind.DEMAND, original)
        val g = v as? ScheduleGuardV1
        return listOf("parses" to (v != null), "isGuard" to (v == null || g != null), "nowBoot" to (now.bootId != ""), "nowElapsed" to (now.elapsedMillis >= 0),
            "wait" to (wait >= 0), "origin" to origin.value.isNotEmpty(),
            "oldRemaining" to (g?.floor == null || g.floor.remainingAt(now) != null))
    }
    private fun floorNo(id: String, only: String, original: ControlNode, now: BootReading = F.now, wait: Long = 1000, origin: LifetimeId = F.life) {
        val twin = F.guard(auth = null)
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(floorVec(twin, F.now, 1000, F.life)))
        assertTrue("positive twin: the floor is recorded", ControlObligations.recordFloor(twin, F.now, 1000, F.life) is ControlWriteResult.Written)
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(floorVec(original, now, wait, origin)))
        assertFalse(F.eligible(id), ControlObligations.recordFloor(original, now, wait, origin) is ControlWriteResult.Written)
    }
    @Test fun F_floorUninterpretable() = floorNo("Z.fl.parses", "parses", F.node(buildJsonObject { put("id", "g"); put("kind", "SCHEDULE_GUARD"); put("future", true) }.toString()))
    @Test fun F_floorNotGuard() = floorNo("Z.fl.isGuard", "isGuard", F.request(id = "g"))
    @Test fun F_floorNowBoot() = floorNo("Z.fl.nowBoot", "nowBoot", F.guard(auth = null), now = BootReading("", 10000))
    @Test fun F_floorNowElapsed() = floorNo("Z.fl.nowElapsed", "nowElapsed", F.guard(auth = null), now = BootReading("boot", -1))
    @Test fun F_floorNegativeWait() = floorNo("Z.fl.wait", "wait", F.guard(auth = null), wait = -1)
    @Test fun F_floorOrigin() = floorNo("Z.fl.origin", "origin", F.guard(auth = null), origin = LifetimeId(""))
    // an old floor whose remaining time is unreadable (empty anchor boot id); if the schema refuses that shape the fixture
    // parse assertion below fails instead, which records the gate as unreachable through a readable guard
    private val badFloorGuard get() = F.node(buildJsonObject { put("id", "g"); put("kind", "SCHEDULE_GUARD")
        put("floor", buildJsonObject { put("anchorBootId", ""); put("anchorElapsedMillis", 10000); put("waitMillis", 30000); put("originLifetimeId", "life") }) }.toString())
    /** Record (not a role): the old remaining is unreadable only for a floor the schema refuses (the guard with an empty
     *  anchor boot id does not parse — the first baseline showed it), or for an unreadable now, which the earlier nowBoot /
     *  nowElapsed gates refuse first. So CO:127's `?: return invalid()` has no readable-guard input of its own. */
    @Test fun F_floorOldRemaining_record() {
        assertNull("record: a guard whose floor has an empty anchor boot id does not parse", guard(badFloorGuard))
        assertFalse("record: recordFloor refuses it", ControlObligations.recordFloor(badFloorGuard, F.now, 1000, F.life) is ControlWriteResult.Written)
    }

    // validFloorResult
    private fun vfr(original: ControlNode, candidate: ControlNode, expected: FloorV1) = listOf(
        "unchanged" to (JsonObject(original.toPayloadEntry().fields - "floor").mapValues { it.value.toString() } == JsonObject(candidate.toPayloadEntry().fields - "floor").mapValues { it.value.toString() }),
        "floor" to (guard(candidate)?.floor == expected))
    private fun vfrNo(id: String, only: String, candidate: ControlNode, expected: FloorV1) {
        val original = F.guard(); val exp = FloorV1("boot", 10000, 1000, F.life)
        val written = (ControlObligations.recordFloor(original, F.now, 1000, F.life) as ControlWriteResult.Written).node
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(vfr(original, written, exp)))
        assertTrue("positive twin: the recorded floor is valid", ControlObligations.validFloorResult(original, written, guard(written)!!, exp))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vfr(original, candidate, expected)))
        assertFalse(F.eligible(id), ControlObligations.validFloorResult(original, candidate, guard(candidate)!!, expected))
    }
    private val written get() = (ControlObligations.recordFloor(F.guard(), F.now, 1000, F.life) as ControlWriteResult.Written).node
    @Test fun A11a_unchanged() = vfrNo("Z.fl.A11a", "unchanged",
        F.node(JsonObject(written.toPayloadEntry().fields + ("auth" to F.guard(F.auth.copy(authStateOrder = 11)).toPayloadEntry().fields["auth"]!!)).toString()), FloorV1("boot", 10000, 1000, F.life))
    @Test fun A11b_floor() = vfrNo("Z.fl.A11b", "floor", written, FloorV1("boot", 10000, 2000, F.life))
}
