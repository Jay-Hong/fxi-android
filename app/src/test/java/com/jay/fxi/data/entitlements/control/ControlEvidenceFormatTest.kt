package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlEvidenceFormatTest {
    private val codec = ControlPayloadCodec()
    private val owner = "01234567-89ab-cdef-0123-456789abcdef"
    private val target = """{"index":0,"kind":"DEMAND","id":"d","joined":false,"written":true}"""
    private val mutations get() = """{"version":2,"commandId":"cmd","ownerTrackingLifetimeId":"$owner","kind":"MUTATIONS","targets":[$target]}"""
    private val rotation get() = """{"version":2,"commandId":"rotate","ownerTrackingLifetimeId":"$owner","kind":"ROTATION","sealIds":["b","a"],"demandId":"d"}"""
    private fun read(vararg rows: String): ControlEvidenceRead = ControlEvidenceReader.read(codec.decode(rows.joinToString(",", "[", "]")) as PayloadRead.Parsed)
    private fun value(text: String): AppliedEvidence {
        val result = read(text)
        assertFalse(result.hasUninterpretable)
        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals(ControlEvidenceEntryRead.Interpreted::class.java, entry.javaClass)
        entry as ControlEvidenceEntryRead.Interpreted
        assertEquals(Json.parseToJsonElement(text), entry.original.toPayloadEntry().fields)
        return entry.value
    }
    private fun opaque(text: String) {
        val result = read(text)
        assertTrue("must be opaque: $text", result.hasUninterpretable)
        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals(ControlEvidenceEntryRead.Uninterpretable::class.java, entry.javaClass)
        assertEquals((codec.decode("[$text]") as PayloadRead.Parsed).entries.single(), (entry as ControlEvidenceEntryRead.Uninterpretable).original)
    }
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun with(text: String, key: String, value: JsonElement?) = JsonObject(obj(text).toMutableMap().apply {
        if (value == null) remove(key) else put(key, value)
    }).toString()
    private fun targets(vararg values: String) = with(mutations, "targets", JsonArray(values.map(Json::parseToJsonElement)))

    @Test fun closedRowsRejectEachMissingMistypedAndUnknownField() {
        for (text in listOf(mutations, rotation)) {
            value(text)
            opaque(with(text, "state", JsonPrimitive("APPLIED")))
            for (key in obj(text).keys) {
                opaque(with(text, key, null))
                for (bad in listOf(JsonNull, JsonObject(emptyMap()), JsonPrimitive(true))) opaque(with(text, key, bad))
            }
            for (version in listOf("1", "3", "0", "-1", "2.0", "2e0", "\"2\"", "9223372036854775808"))
                opaque(with(text, "version", Json.parseToJsonElement(version)))
            opaque(with(text, "commandId", JsonPrimitive("")))
            opaque(with(text, "commandId", JsonPrimitive(9)))
            opaque(with(text, "kind", JsonPrimitive("FLOOR")))
            opaque(with(text, "kind", JsonPrimitive("")))
        }
        for (text in listOf("null", "false", "[]", "3", "\"text\"")) opaque(text)
    }

    @Test fun canonicalOwnerLifetimeOnly() {
        for (text in listOf(mutations, rotation)) {
            assertEquals(owner, value(text).ownerTrackingLifetimeId)
            for (bad in listOf("", "life", owner.uppercase(), "1-1-1-1-1", "$owner ", owner.replace("a", "g")))
                opaque(with(text, "ownerTrackingLifetimeId", JsonPrimitive(bad)))
        }
    }

    @Test fun targetFieldsAndOrderedCompleteIndices() {
        val expected = AppliedTarget(0, ControlKind.DEMAND, "d", false, true)
        val result = value(mutations) as AppliedEvidence.Mutations
        assertEquals("cmd", result.commandId)
        assertEquals(listOf(expected), result.targets)
        for (kind in ControlKind.entries) {
            assertEquals(kind, (value(targets(with(target, "kind", JsonPrimitive(kind.name)))) as AppliedEvidence.Mutations).targets.single().kind)
        }
        for (key in obj(target).keys) {
            opaque(targets(with(target, key, null)))
            for (bad in listOf(JsonNull, JsonObject(emptyMap()), JsonArray(emptyList())) +
                if (key == "id") listOf(JsonPrimitive(7)) else listOf(JsonPrimitive("bad")))
                opaque(targets(with(target, key, bad)))
        }
        for (flag in listOf("joined", "written")) for (spelling in listOf("true", "false"))
            opaque(targets(with(target, flag, JsonPrimitive(spelling))))
        opaque(targets(with(target, "future", JsonPrimitive(1))))
        for (id in listOf(JsonPrimitive(""), JsonPrimitive(7), JsonPrimitive(false))) opaque(targets(with(target, "id", id)))
        for (index in listOf("-1", "1", "2", "0.0", "0e0", "\"0\"", "9223372036854775808"))
            opaque(targets(with(target, "index", Json.parseToJsonElement(index))))
        opaque(targets())
        opaque(targets("false"))
        opaque(targets(target, target)) // extra index 0
        val second = with(with(target, "index", JsonPrimitive(1)), "id", JsonPrimitive("e"))
        val third = with(with(target, "index", JsonPrimitive(2)), "id", JsonPrimitive("f"))
        assertEquals(listOf("d", "e", "f"), (value(targets(target, second, third)) as AppliedEvidence.Mutations).targets.map { it.id })
        opaque(targets(second, target))
        opaque(targets(target, third))
        opaque(targets(with(target, "index", JsonPrimitive(1))))
        opaque(targets(target, with(target, "index", JsonPrimitive(1)))) // repeated id, correct index
    }

    @Test fun writtenAndJoinedRulesAllowMixedNoops() {
        val noop = with(target, "written", JsonPrimitive(false))
        val joined = with(noop, "joined", JsonPrimitive(true))
        opaque(targets(noop))
        opaque(targets(joined))
        opaque(targets(with(target, "joined", JsonPrimitive(true))))
        for (extra in listOf(noop, joined)) {
            val other = with(with(extra, "index", JsonPrimitive(1)), "id", JsonPrimitive("other"))
            val result = value(targets(target, other)) as AppliedEvidence.Mutations
            assertEquals(listOf(true, false), result.targets.map { it.written })
            assertEquals(listOf(false, extra == joined), result.targets.map { it.joined })
        }
    }

    @Test fun rotationCardinalityIdentityAndOrder() {
        val result = value(rotation) as AppliedEvidence.Rotation
        assertEquals("rotate", result.commandId)
        assertEquals(owner, result.ownerTrackingLifetimeId)
        assertEquals(listOf("b", "a"), result.sealIds)
        assertEquals("d", result.demandId)
        assertEquals(listOf("one"), (value(with(rotation, "sealIds", JsonArray(listOf(JsonPrimitive("one"))))) as AppliedEvidence.Rotation).sealIds)
        for (ids in listOf("[]", "[\"a\",\"b\",\"c\"]", "[\"a\",\"a\"]", "[\"\"]", "[1]", "[null]", "[{}]"))
            opaque(with(rotation, "sealIds", Json.parseToJsonElement(ids)))
        for (id in listOf(JsonPrimitive(""), JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive(1)))
            opaque(with(rotation, "demandId", id))
    }

    @Test fun duplicateCommandsIncludeOpaqueRowsAndNeverChooseAWinner() {
        for (other in listOf(mutations, with(rotation, "commandId", JsonPrimitive("cmd")),
            "{\"commandId\":\"cmd\",\"future\":1}", with(mutations, "version", JsonPrimitive(3)))) {
            for (rows in listOf(arrayOf(mutations, other), arrayOf(other, mutations))) {
                val result = read(*rows)
                assertTrue(result.hasUninterpretable)
                assertEquals(2, result.entries.size)
                result.entries.forEachIndexed { index, entry ->
                    assertEquals(ControlEvidenceEntryRead.Uninterpretable::class.java, entry.javaClass)
                    assertEquals((codec.decode("[${rows[index]}]") as PayloadRead.Parsed).entries.single(),
                        (entry as ControlEvidenceEntryRead.Uninterpretable).original)
                }
            }
        }
        val distinct = read(mutations, rotation, "{\"commandId\":17}")
        assertEquals(listOf(ControlEvidenceEntryRead.Interpreted::class.java, ControlEvidenceEntryRead.Interpreted::class.java,
            ControlEvidenceEntryRead.Uninterpretable::class.java), distinct.entries.map { it.javaClass })
        // A target's id and a command id belong to separate namespaces.
        assertEquals("d", value(with(mutations, "commandId", JsonPrimitive("d"))).commandId)
    }

    @Test fun interpretedListsAndConstructorInputsAreImmutable() {
        val targets = mutableListOf(AppliedTarget(0, ControlKind.HOLD, "h", false, true), AppliedTarget(1, ControlKind.SEAL, "s", false, false))
        val row = AppliedEvidence.Mutations("c", owner, targets)
        targets.clear()
        assertEquals(listOf(AppliedTarget(0, ControlKind.HOLD, "h", false, true), AppliedTarget(1, ControlKind.SEAL, "s", false, false)), row.targets)
        val ids = mutableListOf("s", "t")
        val rotation = AppliedEvidence.Rotation("r", owner, ids, "d")
        ids.clear()
        assertEquals(listOf("s", "t"), rotation.sealIds)
        for (list in listOf(row.targets, rotation.sealIds, read(mutations).entries)) {
            val error = runCatching { (list as MutableList).clear() }.exceptionOrNull()
            assertEquals(UnsupportedOperationException::class.java, error?.javaClass)
            assertNull(error?.message)
        }
    }
}
