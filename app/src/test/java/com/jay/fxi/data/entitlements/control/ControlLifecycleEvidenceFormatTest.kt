package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures.wire
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures.target
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures.parse
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures.eligible
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures.retry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlLifecycleEvidenceFormatTest {
    private fun accepted(transition: String, targets: String) {
        val source = wire(transition, targets)
        val entries = parse(source).entries
        assertEquals(1, entries.size)
        val value = (entries.single() as ControlEvidenceEntryRead.Interpreted).value as AppliedEvidence.Lifecycle
        assertEquals(transition, value.transition.name)
        assertEquals(Json.parseToJsonElement(source), ControlAppliedEvidence.node(value))
        assertFalse(ControlAppliedEvidence.node(value).toString().contains("index"))
    }
    private fun refused(id: String, source: String) {
        assertFalse(parse(wire()).hasUninterpretable)
        val parsed = parse(source)
        assertTrue(eligible(id), parsed.hasUninterpretable)
        assertEquals(Json.parseToJsonElement(source), ((parsed.entries.single() as ControlEvidenceEntryRead.Uninterpretable).original as PayloadEntry.Obj).fields)
    }
    private fun settleShapeRefused(id: String, targets: String) {
        val rows = (Json.parseToJsonElement("[$targets]") as JsonArray).map { it as JsonObject }
        val effects = rows.map { it.getValue("effect").jsonPrimitive.content }
        assertEquals(rows.size, rows.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
        assertTrue(rows.all { it.keys == setOf("kind", "id", "effect") })
        val violations = listOf(rows.isEmpty(), rows.any { it.getValue("kind").jsonPrimitive.content != "DEMAND" },
            effects.dropWhile { it == "REMOVE" }.size > 2,
            effects.dropWhile { it == "REMOVE" }.any { it == "REMOVE" })
        assertEquals("exactly the targeted structural predicate is false", 1, violations.count { it })
        refused(id, wire("SETTLE_QUERY", targets))
    }
    private fun change(name: String, value: JsonElement) = JsonObject((Json.parseToJsonElement(wire()) as JsonObject) + (name to value)).toString()
    private fun omit(name: String) = JsonObject((Json.parseToJsonElement(wire()) as JsonObject) - name).toString()

    @Test fun rebind() = accepted("REBIND_REQUESTS", target(id = "a", effect = "REPLACE") + "," + target(id = "b", effect = "REPLACE"))
    @Test fun settleConsumes() = accepted("SETTLE_QUERY", target(id = "a") + "," + target(id = "b", effect = "CREATE") + "," + target(id = "c", effect = "REPLACE"))
    @Test fun settleOnlyRemoval() = accepted("SETTLE_QUERY", target(id = "a"))
    @Test fun settleZeroOneCreate() = accepted("SETTLE_QUERY", target(effect = "CREATE"))
    @Test fun settleZeroOneReplace() = accepted("SETTLE_QUERY", target(effect = "REPLACE"))
    @Test fun settleZeroTwo() = accepted("SETTLE_QUERY", target(id = "a", effect = "REPLACE") + "," + target(id = "b", effect = "CREATE"))
    @Test fun update() = accepted("UPDATE_AUTH", target(effect = "CREATE") + "," + target(id = "d", effect = "REPLACE"))
    @Test fun endBinding() = accepted("END_AUTH_BINDING", target(effect = "REPLACE"))
    @Test fun removeGuard() = accepted("REMOVE_EMPTY_GUARD", target())
    @Test fun recoverHold() = accepted("RECOVER_HOLD", target("HOLD", "h") + "," + target(id = "d", effect = "CREATE") + "," + target(effect = "REPLACE"))
    @Test fun recoverHoldOnlyGuard() = accepted("RECOVER_HOLD", target("HOLD", "h") + "," + target(effect = "REPLACE"))
    @Test fun recoverHoldOnlySource() = accepted("RECOVER_HOLD", target("HOLD", "h"))
    @Test fun recoverIntent() = accepted("RECOVER_INTENT", target("RECOVERY_INTENT", "r") + "," + target(id = "d", effect = "CREATE"))
    @Test fun recoverIntentOnlySource() = accepted("RECOVER_INTENT", target("RECOVERY_INTENT", "r"))

    @Test fun P01_extra() = refused("P01", change("extra", JsonPrimitive(1)))
    @Test fun P02_index() = refused("P02", wire(targets = target().dropLast(1) + ",\"index\":0}"))
    @Test fun P03_emptyId() = refused("P03", wire(targets = target(id = "")))
    @Test fun P04_duplicateId() = refused("P04", wire("REBIND_REQUESTS", target(effect = "REPLACE") + "," + target(effect = "REPLACE")))
    @Test fun P05_unknownTransition() = refused("P05", wire("FUTURE"))
    @Test fun expireFloorIsNotReserved() = refused("P05.expire", wire("EXPIRE_FLOOR"))
    @Test fun closeSessionIsNotReserved() = refused("P05.close", wire("CLOSE_RECOVERY_SESSION"))
    @Test fun P06_unknownEffect() = refused("P06", wire(targets = target(effect = "DELETE")))
    @Test fun P07_unknownKind() = refused("P07", wire(targets = target(kind = "JOURNAL")))
    @Test fun P08_targetScalar() = refused("P08", wire(targets = "1"))
    @Test fun P09_missingTransition() = refused("P09", omit("transition"))
    @Test fun P10_missingTargets() = refused("P10", omit("targets"))
    @Test fun P11_missingVersion() = refused("P11", omit("version"))
    @Test fun P12_missingCommand() = refused("P12", omit("commandId"))
    @Test fun P13_missingLifetime() = refused("P13", omit("ownerTrackingLifetimeId"))
    @Test fun P14_missingKind() = refused("P14", omit("kind"))
    @Test fun P15_wrongTargetsType() = refused("P15", change("targets", JsonObject(emptyMap())))
    @Test fun P16_wrongVersion() = refused("P16", change("version", JsonPrimitive(3)))
    @Test fun P17_wrongLifetime() = refused("P17", change("ownerTrackingLifetimeId", JsonPrimitive("UPPER-NOT-UUID")))
    @Test fun P18_wrongIdType() = refused("P18", wire(targets = target().replace("\"g\"", "1")))
    @Test fun P19_missingEffect() = refused("P19", wire(targets = """{"kind":"DEMAND","id":"g"}"""))
    @Test fun P20_missingTargetKind() = refused("P20", wire(targets = """{"id":"g","effect":"REMOVE"}"""))
    @Test fun P21_missingTargetId() = refused("P21", wire(targets = """{"kind":"DEMAND","effect":"REMOVE"}"""))
    @Test fun P22_emptyCommand() = refused("P22", wire(command = ""))
    @Test fun G06a_empty() = settleShapeRefused("G06a", "")
    @Test fun G06b_nonDemand() = settleShapeRefused("G06b", target("HOLD", "h"))
    @Test fun G06c_threeOptional() = settleShapeRefused("G06c", target(id = "a", effect = "CREATE") + "," + target(id = "b", effect = "REPLACE") + "," + target(id = "c", effect = "CREATE"))
    @Test fun G06d_removeAfterOptional() = settleShapeRefused("G06d", target(id = "a", effect = "CREATE") + "," + target(id = "b"))
    @Test fun rebindCannotRemove() = refused("P23", wire("REBIND_REQUESTS"))
    @Test fun updateCannotRemove() = refused("P24", wire("UPDATE_AUTH"))
    @Test fun P34_updateAtMostTwo() {
        val source = wire("UPDATE_AUTH", target(id = "a", effect = "CREATE") + "," +
            target(id = "b", effect = "CREATE") + "," + target(id = "c", effect = "CREATE"))
        val fields = Json.parseToJsonElement(source).jsonObject
        assertEquals(setOf("version", "commandId", "ownerTrackingLifetimeId", "kind", "transition", "targets"), fields.keys)
        assertEquals(JsonPrimitive(2), fields["version"])
        assertEquals(JsonPrimitive("lc"), fields["commandId"])
        assertEquals(JsonPrimitive(ReclamationFixtures.oldLife), fields["ownerTrackingLifetimeId"])
        assertEquals(JsonPrimitive("CONTROL_LIFECYCLE"), fields["kind"])
        assertEquals(JsonPrimitive("UPDATE_AUTH"), fields["transition"])
        val rows = fields.getValue("targets").jsonArray.map { it.jsonObject }
        assertEquals(3, rows.size)
        assertEquals(listOf("a", "b", "c"), rows.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(3, rows.map { it.getValue("id") }.toSet().size)
        assertTrue(rows.all { it.keys == setOf("kind", "id", "effect") })
        assertTrue(rows.all { it["kind"] == JsonPrimitive("DEMAND") && it["effect"] == JsonPrimitive("CREATE") })
        // The same common fields and first two targets form an independently eligible control.
        val withinLimit = JsonObject(fields + ("targets" to JsonArray(rows.take(2))))
        assertFalse(parse(withinLimit.toString()).hasUninterpretable)
        refused("P34", source)
    }
    @Test fun endCannotCreate() = refused("P25", wire("END_AUTH_BINDING", target(effect = "CREATE")))
    @Test fun emptyGuardExactlyOne() = refused("P26", wire(targets = target(id = "a") + "," + target(id = "b")))
    @Test fun holdNeedsHoldSource() = refused("P27", wire("RECOVER_HOLD"))
    @Test fun holdNeedsRemoveSource() = refused("P28", wire("RECOVER_HOLD", target("HOLD", "h", "REPLACE")))
    @Test fun holdAtMostThree() = refused("P29", wire("RECOVER_HOLD", target("HOLD", "h") + "," + target(id = "a", effect = "CREATE") + "," + target(id = "b", effect = "CREATE") + "," + target(id = "c", effect = "CREATE")))
    @Test fun holdRequestMustCreateWhenBothPresent() = refused("P30", wire("RECOVER_HOLD", target("HOLD", "h") + "," + target(id = "a", effect = "REPLACE") + "," + target(id = "b", effect = "CREATE")))
    @Test fun intentNeedsIntentSource() = refused("P31", wire("RECOVER_INTENT", target("HOLD", "h")))
    @Test fun intentAtMostTwo() = refused("P32", wire("RECOVER_INTENT", target("RECOVERY_INTENT", "r") + "," + target(id = "a", effect = "CREATE") + "," + target(id = "b", effect = "CREATE")))
    @Test fun intentRequestCannotReplace() = refused("P33", wire("RECOVER_INTENT", target("RECOVERY_INTENT", "r") + "," + target(effect = "REPLACE")))

    @Test fun R06a_duplicateCommandInvalidatesBoth() {
        val source = wire() + "," + wire()
        val entries = parse(source).entries
        assertEquals(2, entries.size)
        assertTrue(retry("R06a"), entries.all { it is ControlEvidenceEntryRead.Uninterpretable })
    }
    @Test fun R06b_opaqueDuplicateCannotLoseToValidSibling() {
        val entries = parse(wire() + "," + """{"commandId":"lc","future":true}""").entries
        assertEquals(2, entries.size)
        assertTrue(retry("R06b"), entries.all { it is ControlEvidenceEntryRead.Uninterpretable })
    }
    @Test fun existingThreeKindsStillParseAlongsideLifecycle() {
        val source = ReclamationFixtures.mutation() + "," + ReclamationFixtures.rotation() + "," + HandoverFormatFixtures.applied() + "," + wire()
        val entries = parse(source).entries
        assertEquals(4, entries.size)
        assertFalse(parse(source).hasUninterpretable)
        assertTrue((entries[0] as ControlEvidenceEntryRead.Interpreted).value is AppliedEvidence.Mutations)
        assertTrue((entries[1] as ControlEvidenceEntryRead.Interpreted).value is AppliedEvidence.Rotation)
        assertTrue((entries[2] as ControlEvidenceEntryRead.Interpreted).value is AppliedEvidence.Settlement)
    }
}
