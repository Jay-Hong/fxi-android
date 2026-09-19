package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Each mutation selects one method and must fail at its named NEGATIVE assertion, after controls. */
class ControlEvidenceSubguardTest {
    private val owner = "01234567-89ab-cdef-0123-456789abcdef"
    private val target = """{"index":0,"kind":"DEMAND","id":"d","joined":false,"written":true}"""
    private val mutations get() = """{"version":2,"commandId":"cmd","ownerTrackingLifetimeId":"$owner","kind":"MUTATIONS","targets":[$target]}"""
    private val rotation get() = """{"version":2,"commandId":"rotate","ownerTrackingLifetimeId":"$owner","kind":"ROTATION","sealIds":["b","a"],"demandId":"d"}"""
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun with(text: String, key: String, value: JsonElement?) = JsonObject((json(text) as JsonObject).toMutableMap().apply {
        if (value == null) remove(key) else put(key, value)
    }).toString()
    private fun targets(vararg rows: String) = with(mutations, "targets", JsonArray(rows.map(::json)))
    private fun indexed(index: Int, id: String) = with(with(target, "index", JsonPrimitive(index)), "id", JsonPrimitive(id))
    private fun read(text: String) = ControlEvidenceReader.read(ControlPayloadCodec().decode("[$text]") as PayloadRead.Parsed)

    private fun positive(text: String) {
        assertTrue(javaClass.desiredAssertionStatus())
        val result = read(text)
        assertFalse("POSITIVE metadata flag: $text", result.hasUninterpretable)
        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals("POSITIVE row: $text", ControlEvidenceEntryRead.Interpreted::class.java, entry.javaClass)
        entry as ControlEvidenceEntryRead.Interpreted
        assertEquals(json(text), entry.original.toPayloadEntry().fields)
        assertEquals(owner, entry.value.ownerTrackingLifetimeId)
        if (text == rotation) {
            assertEquals("rotate", entry.value.commandId)
            val value = entry.value as AppliedEvidence.Rotation
            assertEquals(listOf("b", "a"), value.sealIds)
            assertEquals("d", value.demandId)
        } else {
            assertEquals("cmd", entry.value.commandId)
            val expected = (json(text) as JsonObject)["targets"] as JsonArray
            val actual = (entry.value as AppliedEvidence.Mutations).targets
            assertEquals(expected.size, actual.size)
            expected.forEachIndexed { i, row ->
                row as JsonObject
                assertEquals(AppliedTarget((row.getValue("index") as JsonPrimitive).int,
                    ControlKind.valueOf((row.getValue("kind") as JsonPrimitive).content),
                    (row.getValue("id") as JsonPrimitive).content,
                    (row.getValue("joined") as JsonPrimitive).boolean,
                    (row.getValue("written") as JsonPrimitive).boolean), actual[i])
            }
        }
    }

    private fun opaque(label: String, text: String) {
        val result = read(text)
        assertTrue("$label: $text", result.hasUninterpretable)
        assertEquals("$label entries", 1, result.entries.size)
        val entry = result.entries.single()
        assertEquals("$label class", ControlEvidenceEntryRead.Uninterpretable::class.java, entry.javaClass)
        val original = (entry as ControlEvidenceEntryRead.Uninterpretable).original as PayloadEntry.Obj
        assertEquals("$label original", json(text), original.fields)
    }

    @Test fun ownerMissingIsIndependentOfOwnerType() {
        positive(mutations)
        opaque("CONTROL owner type", with(mutations, "ownerTrackingLifetimeId", JsonPrimitive(7)))
        opaque("NEGATIVE[OWNER_MISSING]", with(mutations, "ownerTrackingLifetimeId", null))
    }

    @Test fun ownerPresentWithWrongTypeIsRejected() {
        for (row in listOf(mutations, rotation)) {
            positive(row)
            opaque("CONTROL owner missing", with(row, "ownerTrackingLifetimeId", null))
            opaque("CONTROL noncanonical owner", with(row, "ownerTrackingLifetimeId", JsonPrimitive("life")))
            for (bad in listOf(JsonPrimitive(7), JsonNull, JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())))
                opaque("NEGATIVE[OWNER_TYPE]", with(row, "ownerTrackingLifetimeId", bad))
        }
    }

    @Test fun missingVersionIsIndependentOfVersionValue() {
        positive(mutations)
        opaque("CONTROL future version", with(mutations, "version", JsonPrimitive(3)))
        opaque("CONTROL string version", with(mutations, "version", JsonPrimitive("2")))
        opaque("NEGATIVE[VERSION_MISSING]", with(mutations, "version", null))
    }

    @Test fun futureVersionIsRejectedWithVersionPresent() {
        for (row in listOf(mutations, rotation)) {
            positive(row)
            opaque("CONTROL missing version", with(row, "version", null))
            opaque("CONTROL string version", with(row, "version", JsonPrimitive("2")))
            opaque("NEGATIVE[VERSION_FUTURE]", with(row, "version", JsonPrimitive(3)))
        }
    }

    @Test fun stringVersionIsRejectedWithVersionPresent() {
        for (row in listOf(mutations, rotation)) {
            positive(row)
            opaque("CONTROL missing version", with(row, "version", null))
            opaque("CONTROL future version", with(row, "version", JsonPrimitive(3)))
            opaque("NEGATIVE[VERSION_STRING]", with(row, "version", JsonPrimitive("2")))
        }
    }

    @Test fun unknownRowKindIsRejectedWithKindPresent() {
        positive(mutations); positive(rotation)
        opaque("CONTROL missing kind", with(mutations, "kind", null))
        opaque("CONTROL kind type", with(mutations, "kind", JsonPrimitive(7)))
        opaque("NEGATIVE[ROW_KIND_UNKNOWN]", with(mutations, "kind", JsonPrimitive("FLOOR")))
    }

    @Test fun swappedIndicesAreRejectedWithCompleteTypedIndices() {
        positive(targets(target, indexed(1, "e")))
        opaque("CONTROL missing index", targets(with(target, "index", null)))
        opaque("CONTROL index type", targets(with(target, "index", JsonPrimitive("0"))))
        opaque("CONTROL out of range", targets(indexed(-1, "d"), indexed(1, "e")))
        opaque("CONTROL gap", targets(target, indexed(2, "e")))
        opaque("NEGATIVE[INDEX_SWAP]", targets(indexed(1, "d"), indexed(0, "e")))
    }

    @Test fun indexGapIsRejectedWithFirstIndexAndTypesValid() {
        positive(targets(target, indexed(1, "e")))
        opaque("CONTROL missing index", targets(with(target, "index", null)))
        opaque("CONTROL index type", targets(with(target, "index", JsonPrimitive("0"))))
        opaque("CONTROL nonzero first", targets(indexed(1, "d")))
        opaque("CONTROL backwards", targets(target, indexed(0, "e")))
        opaque("CONTROL backwards after gap", targets(target, indexed(3, "e"), indexed(2, "f")))
        opaque("NEGATIVE[INDEX_GAP]", targets(target, indexed(2, "e")))
    }

    @Test fun missingTargetKindIsIndependentOfUnknownTargetKind() {
        positive(mutations)
        opaque("CONTROL unknown target kind", targets(with(target, "kind", JsonPrimitive("FLOOR"))))
        opaque("CONTROL target kind type", targets(with(target, "kind", JsonPrimitive(7))))
        opaque("NEGATIVE[TARGET_KIND_MISSING]", targets(with(target, "kind", null)))
    }

    @Test fun unknownTargetKindIsRejectedWithKindPresent() {
        positive(mutations)
        opaque("CONTROL missing target kind", targets(with(target, "kind", null)))
        opaque("CONTROL target kind type", targets(with(target, "kind", JsonPrimitive(7))))
        opaque("NEGATIVE[TARGET_KIND_UNKNOWN]", targets(with(target, "kind", JsonPrimitive("FLOOR"))))
    }

    @Test fun missingTargetsContainerIsIndependentOfContainerType() {
        positive(mutations)
        opaque("CONTROL targets type", with(mutations, "targets", JsonPrimitive(true)))
        opaque("NEGATIVE[TARGETS_MISSING]", with(mutations, "targets", null))
    }

    @Test fun targetsContainerTypeIsRejectedWithContainerPresent() {
        positive(mutations)
        opaque("CONTROL missing targets", with(mutations, "targets", null))
        for (bad in listOf(JsonPrimitive(true), JsonNull, JsonObject(emptyMap()), JsonPrimitive("[]")))
            opaque("NEGATIVE[TARGETS_TYPE]", with(mutations, "targets", bad))
    }

    @Test fun missingSealIdsContainerIsIndependentOfContainerType() {
        positive(rotation)
        opaque("CONTROL sealIds type", with(rotation, "sealIds", JsonPrimitive(true)))
        opaque("NEGATIVE[SEAL_IDS_MISSING]", with(rotation, "sealIds", null))
    }

    @Test fun sealIdsContainerTypeIsRejectedWithContainerPresent() {
        positive(rotation)
        opaque("CONTROL missing sealIds", with(rotation, "sealIds", null))
        for (bad in listOf(JsonPrimitive(true), JsonNull, JsonObject(emptyMap()), JsonPrimitive("[]")))
            opaque("NEGATIVE[SEAL_IDS_TYPE]", with(rotation, "sealIds", bad))
    }
}
