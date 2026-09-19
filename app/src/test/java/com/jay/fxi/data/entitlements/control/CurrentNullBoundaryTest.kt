package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Direct boundaries retain their own verdict before later candidate checks can mask a bypass. */
class CurrentNullBoundaryTest {
    @Test fun A13_schema1_recordProblem_boundary() {
        val raw = CurrentNullFixtures.raw()
        assertNull(RetiredNamespaceSettlementTransition.recordProblem(CurrentNullFixtures.read(raw)))
        val legacy = raw.toMutablePreferences().apply {
            this[intPreferencesKey("control_schema")] = 1
            remove(ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE))
            remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE))
        }
        val read = CurrentNullFixtures.read(legacy)
        assertEquals(1, read.schemaVersion)
        assertFalse(read.hasUninterpretable)
        assertFalse(read.hasUninterpretableMetadata)
        assertEquals("A13: schema 1 must require migration at the record boundary",
            RecoveryReason.ControlSchemaMigrationRequired, RetiredNamespaceSettlementTransition.recordProblem(read))
    }

    private fun sizedEntry(bytes: Int): PayloadEntry.Obj {
        // [{"pad":""}] is 12 ASCII bytes; the Korean character occupies three UTF-8 bytes.
        val entry = PayloadEntry.Obj(JsonObject(mapOf("pad" to JsonPrimitive("한" + "x".repeat(bytes - 15)))))
        assertEquals("fixture UTF-8 byte count", bytes, JsonArray(listOf(entry.fields)).toString().toByteArray(Charsets.UTF_8).size)
        return entry
    }

    @Test fun G20_encode65537_boundary() {
        val codec = ControlPayloadCodec()
        val atLimit = codec.encode(listOf(sizedEntry(65_536)))
        assertTrue("G20: encoding exactly 65536 bytes must succeed", atLimit is PayloadWrite.Encoded)
        val overLimit = codec.encode(listOf(sizedEntry(65_537)))
        assertTrue("G20: encoding 65537 bytes must return TooLarge, not Encoded", overLimit is PayloadWrite.TooLarge)
        assertEquals(PayloadWrite.TooLarge(65_537, 65_536), overLimit)
    }

    private fun nestedEntry(depth: Int): PayloadEntry {
        var tree: JsonElement = JsonPrimitive(0)
        repeat(depth - 2) { tree = JsonArray(listOf(tree)) }
        return PayloadEntry.Uninterpretable(tree)
    }

    @Test fun G20_decodeDepth65_boundary() {
        val codec = ControlPayloadCodec()
        // 64 array brackets pass the text scan; the scalar leaf is the 65th tree level.
        val atLimit = "[".repeat(63) + "0" + "]".repeat(63)
        val overLimit = "[".repeat(64) + "0" + "]".repeat(64)
        assertTrue("G20: decoding tree depth 64 must succeed", codec.decode(atLimit) is PayloadRead.Parsed)
        val result = codec.decode(overLimit)
        assertTrue("G20: decoding tree depth 65 must return Unreadable, not Parsed", result is PayloadRead.Unreadable)
        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.TOO_DEEP, overLimit), result)
    }

    @Test fun G20_encodeDepth65_boundary() {
        val codec = ControlPayloadCodec()
        assertTrue("G20: encoding tree depth 64 must succeed", codec.encode(listOf(nestedEntry(64))) is PayloadWrite.Encoded)
        val result = runCatching { codec.encode(listOf(nestedEntry(65))) }
        assertTrue("G20: encoding tree depth 65 must throw IllegalArgumentException, not succeed",
            result.exceptionOrNull() is IllegalArgumentException)
    }
}
