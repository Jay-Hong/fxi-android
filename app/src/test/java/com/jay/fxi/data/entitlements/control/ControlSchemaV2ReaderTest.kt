package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class ControlSchemaV2ReaderTest {
    private val reader = ControlRecordReader()
    private val schema = intPreferencesKey("control_schema")
    private fun raw(version: Int = 2) = mutablePreferencesOf(schema to version).apply {
        ControlRecordKeys.required(version).forEach { this[ControlRecordKeys.payload(it)] = "[]" }
    }
    private fun supported(p: Preferences): ControlRecordRead.Supported {
        val result = reader.read(p)
        assertEquals(ControlRecordRead.Supported::class.java, result.javaClass)
        assertEquals(p, result.original)
        return result as ControlRecordRead.Supported
    }
    private fun unreadable(p: Preferences, vararg problems: ControlRecordProblem) {
        val result = reader.read(p)
        assertEquals(ControlRecordRead.Unreadable::class.java, result.javaClass)
        assertEquals(problems.toList(), (result as ControlRecordRead.Unreadable).problems)
        assertEquals(p, result.original)
    }

    @Test fun schemaClassificationAndPrecedence() {
        assertTrue(javaClass.desiredAssertionStatus())
        for (version in 1..2) {
            val result = supported(raw(version))
            assertEquals(version, result.schemaVersion)
            assertEquals(ControlKind.entries.toSet(), result.arrays.keys)
            if (version == 1) assertSame(ControlMetadataRead.NotPresentV1, result.metadata)
            else assertEquals(ControlMetadataRead.V2::class.java, result.metadata.javaClass)
            assertFalse(result.hasUninterpretable)
            assertFalse(result.hasUninterpretableMetadata)
            assertFalse(result.blocksProtectedAdmission)
        }
        for (version in listOf(0, -1, 3, Int.MAX_VALUE)) {
            unreadable(mutablePreferencesOf(schema to version), ControlRecordProblem.UnsupportedSchema(version))
        }
        for (p in listOf(mutablePreferencesOf(longPreferencesKey("control_schema") to 2L),
            mutablePreferencesOf(stringPreferencesKey("control_schema") to "2"))) {
            unreadable(p, ControlRecordProblem.WrongType("control_schema"))
        }
        assertEquals(ControlRecordRead.MigrationOrRecoveryRequired::class.java,
            reader.read(mutablePreferencesOf(stringPreferencesKey("foreign") to "keep")).javaClass)
        for (key in ControlPayloadKey.entries) {
            unreadable(mutablePreferencesOf(ControlRecordKeys.payload(key) to "[]"), ControlRecordProblem.MissingSchema)
        }
        assertEquals(listOf("seal_v1", "demand_v1", "hold_v1", "recovery_intent_v1", "command_evidence_v2", "scope_fence_v2"),
            ControlRecordKeys.allPayloads.map { it.wireName })
    }

    @Test fun requiredForbiddenAndProblemOrder() {
        for (version in 1..2) {
            supported(raw(version))
            for (key in ControlRecordKeys.required(version)) {
                unreadable(raw(version).apply { remove(ControlRecordKeys.payload(key)) }, ControlRecordProblem.MissingPayload(key))
                for (value in listOf<Any>(2, 2L, true, setOf("[]"))) {
                    val p = raw(version)
                    when (value) {
                        is Int -> p[intPreferencesKey(key.wireName)] = value
                        is Long -> p[longPreferencesKey(key.wireName)] = value
                        is Boolean -> p[booleanPreferencesKey(key.wireName)] = value
                        else -> p[stringSetPreferencesKey(key.wireName)] = setOf("[]")
                    }
                    unreadable(p, ControlRecordProblem.WrongType(key.wireName))
                }
            }
        }
        for (key in listOf(ControlPayloadKey.COMMAND_EVIDENCE, ControlPayloadKey.SCOPE_FENCE)) {
            unreadable(raw(1).apply { this[ControlRecordKeys.payload(key)] = "[]" },
                ControlRecordProblem.UnexpectedPayloadForSchema(1, key))
        }
        unreadable(mutablePreferencesOf(schema to 2), *ControlPayloadKey.entries.map { ControlRecordProblem.MissingPayload(it) }.toTypedArray())
        unreadable(mutablePreferencesOf(schema to 1,
            stringPreferencesKey("command_evidence_v2") to "garbage", intPreferencesKey("scope_fence_v2") to 8),
            *ControlPayloadKey.entries.take(4).map { ControlRecordProblem.MissingPayload(it) }.toTypedArray(),
            ControlRecordProblem.UnexpectedPayloadForSchema(1, ControlPayloadKey.COMMAND_EVIDENCE),
            ControlRecordProblem.UnexpectedPayloadForSchema(1, ControlPayloadKey.SCOPE_FENCE))
    }

    @Test fun everyPayloadUsesTheSameStrictEnvelopeAndUtf8Limits() {
        val bad = listOf(
            "" to PayloadUnreadable.NOT_JSON, "[}" to PayloadUnreadable.NOT_JSON,
            "[foo]" to PayloadUnreadable.NOT_JSON, "[1]2]" to PayloadUnreadable.NOT_JSON,
            "{}" to PayloadUnreadable.NOT_AN_ARRAY,
            "[{\"a\":1,\"a\":2}]" to PayloadUnreadable.DUPLICATE_KEY,
            "[".repeat(65) + "]".repeat(65) to PayloadUnreadable.TOO_DEEP,
            "[".repeat(64) + "0" + "]".repeat(64) to PayloadUnreadable.TOO_DEEP,
            "[\"" + "x".repeat(65533) + "\"]" to PayloadUnreadable.TOO_LARGE,
            "[\"" + "한".repeat(21844) + "a\"]" to PayloadUnreadable.TOO_LARGE
        )
        for (key in ControlPayloadKey.entries) {
            for ((text, reason) in bad) unreadable(raw().apply { this[ControlRecordKeys.payload(key)] = text },
                ControlRecordProblem.UnreadablePayload(key, PayloadRead.Unreadable(reason, text)))
            for (text in listOf("[]", "[".repeat(64) + "]".repeat(64),
                "[\"" + "x".repeat(65532) + "\"]", "[\"" + "한".repeat(21844) + "\"]")) {
                val p = raw().apply { this[ControlRecordKeys.payload(key)] = text }
                assertEquals(text, supported(p).original[ControlRecordKeys.payload(key)])
            }
        }
    }

    @Test fun flagsAreIndependentAndReservedFencePreservesEverySlot() {
        for (obligation in listOf(false, true)) for (evidence in listOf(false, true)) for (fence in listOf(false, true)) {
            val p = raw().apply {
                if (obligation) this[stringPreferencesKey("hold_v1")] = "[null]"
                if (evidence) this[stringPreferencesKey("command_evidence_v2")] = "[false]"
                if (fence) this[stringPreferencesKey("scope_fence_v2")] = "[null, 1e400, {\"future\":-0}]"
            }
            val result = supported(p)
            assertEquals(obligation, result.hasUninterpretable)
            assertEquals(evidence || fence, result.hasUninterpretableMetadata)
            assertEquals(obligation || evidence || fence, result.blocksProtectedAdmission)
            val metadata = result.metadata as ControlMetadataRead.V2
            assertEquals(evidence, metadata.evidence.hasUninterpretable)
            assertEquals(fence, metadata.scopeFence.hasUninterpretable)
            assertEquals(if (fence) 3 else 0, metadata.scopeFence.entries.size)
            assertEquals((ControlPayloadCodec().decode(p[stringPreferencesKey("scope_fence_v2")]!!) as PayloadRead.Parsed).entries,
                metadata.scopeFence.entries)
        }
    }

    @Test fun originalAndMetadataAreDetachedAndFrozen() {
        val text = " [ {\"future\":[1e400, -0, {\"deep\":true}]}, null ] "
        val p = raw().apply {
            this[stringPreferencesKey("command_evidence_v2")] = text
            this[stringPreferencesKey("scope_fence_v2")] = text
            this[stringSetPreferencesKey("foreign")] = setOf("alpha", "beta")
        }
        val expected = p.toPreferences()
        val result = supported(p)
        p[stringPreferencesKey("command_evidence_v2")] = "[]"
        p.remove(stringSetPreferencesKey("foreign"))
        assertEquals(expected, result.original)
        assertEquals(text, result.original[stringPreferencesKey("command_evidence_v2")])
        val metadata = result.metadata as ControlMetadataRead.V2
        val entry = metadata.evidence.entries.first() as ControlEvidenceEntryRead.Uninterpretable
        assertEquals(PayloadEntry.Obj::class.java, entry.original.javaClass)
        val original = entry.original as PayloadEntry.Obj
        ((original.fields["future"] as kotlinx.serialization.json.JsonArray)[2] as kotlinx.serialization.json.JsonObject)
            .let { (it.keys as MutableSet<String>).remove("deep") }
        val parsed = ControlPayloadCodec().decode(text) as PayloadRead.Parsed
        assertEquals(parsed.entries.first(), entry.original)
        assertEquals(PayloadEntry.Obj::class.java, metadata.scopeFence.entries.first().javaClass)
        val fenceOriginal = metadata.scopeFence.entries.first() as PayloadEntry.Obj
        ((fenceOriginal.fields["future"] as kotlinx.serialization.json.JsonArray)[2] as kotlinx.serialization.json.JsonObject)
            .let { (it.keys as MutableSet<String>).remove("deep") }
        assertEquals(parsed.entries, metadata.scopeFence.entries)
        exactFailure(UnsupportedOperationException::class.java, null) { (metadata.evidence.entries as MutableList).clear() }
        exactFailure(UnsupportedOperationException::class.java, null) { (metadata.scopeFence.entries as MutableList).clear() }
        exactFailure(IllegalStateException::class.java, "Do mutate preferences once returned to DataStore.") {
            (result.original as MutablePreferences)[schema] = 3
        }
    }

    @Test fun metadataIdentitiesDoNotPolluteTheObligationNamespace() {
        val p = raw().apply {
            this[stringPreferencesKey("seal_v1")] = "[${ControlObligationFixtures.seal}]"
            this[stringPreferencesKey("command_evidence_v2")] = """[{"id":"s","commandId":"s","future":true}]"""
        }
        val result = supported(p)
        assertFalse(result.hasUninterpretable)
        assertTrue(result.hasUninterpretableMetadata)
        assertEquals(ControlEntryRead.Interpreted::class.java, result.arrays.getValue(ControlKind.SEAL).entries.single().javaClass)
        assertEquals("s", (result.arrays.getValue(ControlKind.SEAL).entries.single() as ControlEntryRead.Interpreted).value.id)
        assertEquals(p, result.original)
    }

    @Test fun metadataImportDetachesMutableTreesAndLists() {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "future" to kotlinx.serialization.json.JsonPrimitive("kept"), "commandId" to kotlinx.serialization.json.JsonPrimitive("opaque"))
        val entries = mutableListOf<PayloadEntry>(PayloadEntry.Obj(kotlinx.serialization.json.JsonObject(fields)))
        val payload = PayloadRead.Parsed(entries)
        val evidence = ControlEvidenceReader.read(payload)
        val fence = ScopeFenceRead(payload)
        fields["future"] = kotlinx.serialization.json.JsonPrimitive("changed")
        entries.clear()
        assertTrue(evidence.hasUninterpretable)
        assertTrue(fence.hasUninterpretable)
        assertEquals(1, evidence.entries.size)
        assertEquals(1, fence.entries.size)
        val expected = PayloadEntry.Obj(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"future":"kept","commandId":"opaque"}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(expected, (evidence.entries.single() as ControlEvidenceEntryRead.Uninterpretable).original)
        assertEquals(listOf(expected), fence.entries)
    }

    private fun exactFailure(type: Class<out Throwable>, message: String?, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertEquals(type, error?.javaClass)
        assertEquals(message, error?.message)
    }
}
