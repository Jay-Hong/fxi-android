package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.applied
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.lSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.rSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.record
import org.junit.Assert.*
import org.junit.Test

class HandoverRecordFormatTest {
    private fun read(raw: Preferences): ControlRecordRead {
        val result = ControlRecordReader().read(raw)
        assertEquals(raw, result.original)
        return result
    }
    @Test fun A20_L_schema2_interpreted() {
        val result = read(record()) as ControlRecordRead.Supported
        assertFalse(result.blocksProtectedAdmission)
        assertTrue(((result.arrays.getValue(ControlKind.SEAL).entries.single() as ControlEntryRead.Interpreted).value as SealV1)
            .settlement is RetiredNullSettlementEvidenceV2)
    }
    @Test fun A20_L_schema1_whole_seal_opaque_with_v1_sibling() {
        val result = read(record(seals = "[$lSeal,$rSeal]", schema = 1)) as ControlRecordRead.Supported
        assertTrue(result.hasUninterpretable)
        val entries = result.arrays.getValue(ControlKind.SEAL).entries
        assertEquals(ControlObligationFixtures.node(lSeal).toPayloadEntry(), (entries[0] as ControlEntryRead.Uninterpretable).original)
        assertTrue(((entries[1] as ControlEntryRead.Interpreted).value as SealV1).settlement is SettlementEvidenceV1)
        assertSame(ControlMetadataRead.NotPresentV1, result.metadata)
    }
    private fun mixedSchema(key: ControlPayloadKey) {
        val source = record(schema = 1).toMutablePreferences().apply { this[ControlRecordKeys.payload(key)] = "[]" }
        val observed = read(source)
        assertTrue("whole record must be unreadable", observed is ControlRecordRead.Unreadable)
        val result = observed as ControlRecordRead.Unreadable
        assertEquals(listOf(ControlRecordProblem.UnexpectedPayloadForSchema(1, key)), result.problems)
    }
    @Test fun A20_schema1_mixed_evidence_rejected() = mixedSchema(ControlPayloadKey.COMMAND_EVIDENCE)
    @Test fun A20_schema1_mixed_fence_rejected() = mixedSchema(ControlPayloadKey.SCOPE_FENCE)

    @Test fun A20_unknown_schema_not_downgraded() {
        val result = read(record().toMutablePreferences().apply { this[intPreferencesKey(ControlRecordKeys.SCHEMA)] = 3 }) as ControlRecordRead.Unreadable
        assertEquals(listOf(ControlRecordProblem.UnsupportedSchema(3)), result.problems)
    }

    private fun envelope(key: ControlPayloadKey, payload: String, reason: PayloadUnreadable) {
        val source = record().toMutablePreferences().apply { this[ControlRecordKeys.payload(key)] = payload }
        val observed = read(source)
        assertTrue("whole record must be unreadable", observed is ControlRecordRead.Unreadable)
        val result = observed as ControlRecordRead.Unreadable
        assertEquals(listOf(ControlRecordProblem.UnreadablePayload(key, PayloadRead.Unreadable(reason, payload))), result.problems)
    }
    @Test fun A19_duplicate_L_key_is_envelope_failure() = envelope(ControlPayloadKey.SEAL,
        "[${lSeal.replace("\"version\":2", "\"version\":2,\"version\":2")}]", PayloadUnreadable.DUPLICATE_KEY)
    @Test fun A01_duplicate_Applied_key_is_envelope_failure() = envelope(ControlPayloadKey.COMMAND_EVIDENCE,
        "[${applied().replace("\"demandId\":null", "\"demandId\":null,\"demandId\":null")}]", PayloadUnreadable.DUPLICATE_KEY)

    private fun padded(wire: String, size: Int): String = "[$wire]" + " ".repeat(size - "[$wire]".toByteArray(Charsets.UTF_8).size)
    @Test fun A19_L_payload_exact_65536_supported() {
        val source = record().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)] = padded(lSeal, 65_536) }
        assertFalse((read(source) as ControlRecordRead.Supported).blocksProtectedAdmission)
    }
    @Test fun A19_L_payload_65537_rejected() = envelope(ControlPayloadKey.SEAL, padded(lSeal, 65_537), PayloadUnreadable.TOO_LARGE)
    @Test fun A01_Applied_payload_exact_65536_supported() {
        val source = record(evidence = padded(applied(), 65_536))
        assertFalse((read(source) as ControlRecordRead.Supported).blocksProtectedAdmission)
    }
    @Test fun A01_Applied_payload_65537_rejected() = envelope(ControlPayloadKey.COMMAND_EVIDENCE, padded(applied(), 65_537), PayloadUnreadable.TOO_LARGE)

    private fun nested(wire: String, wrappers: Int) = "[$wire," + "[".repeat(wrappers) + "0" + "]".repeat(wrappers) + "]"
    @Test fun A19_depth64_preserves_unknown_sibling() {
        val source = record(seals = nested(lSeal, 62))
        val result = read(source) as ControlRecordRead.Supported
        assertTrue(result.hasUninterpretable)
        assertTrue(result.arrays.getValue(ControlKind.SEAL).entries.first() is ControlEntryRead.Interpreted)
    }
    @Test fun A19_depth65_rejects_whole_seal_envelope() = envelope(ControlPayloadKey.SEAL, nested(lSeal, 63), PayloadUnreadable.TOO_DEEP)
    @Test fun A01_depth64_preserves_unknown_sibling() {
        val result = read(record(evidence = nested(applied(), 62))) as ControlRecordRead.Supported
        assertTrue(result.hasUninterpretableMetadata)
        assertTrue((result.metadata as ControlMetadataRead.V2).evidence.entries.first() is ControlEvidenceEntryRead.Interpreted)
    }
    @Test fun A01_depth65_rejects_whole_Applied_envelope() = envelope(ControlPayloadKey.COMMAND_EVIDENCE, nested(applied(), 63), PayloadUnreadable.TOO_DEEP)
    private fun sizedEntry(wire: String, path: String, bytes: Int): PayloadEntry.Obj {
        val small = HandoverFormatFixtures.change(wire, path, kotlinx.serialization.json.JsonPrimitive("한"))
        val count = "[$small]".toByteArray(Charsets.UTF_8).size
        return ControlObligationFixtures.node(HandoverFormatFixtures.change(wire, path,
            kotlinx.serialization.json.JsonPrimitive("한" + "x".repeat(bytes - count)))).toPayloadEntry()
    }
    @Test fun A19_L_encoder_exact_utf8_limit() {
        val entry = sizedEntry(lSeal, "settlement.operationId", 65_536)
        val encoded = ControlPayloadCodec().encode(listOf(entry)) as PayloadWrite.Encoded
        assertEquals(65_536, encoded.text.toByteArray(Charsets.UTF_8).size)
        assertFalse((read(record(seals = encoded.text)) as ControlRecordRead.Supported).blocksProtectedAdmission)
    }
    @Test fun A19_L_encoder_over_utf8_limit() {
        val entry = sizedEntry(lSeal, "settlement.operationId", 65_537)
        assertEquals(PayloadWrite.TooLarge(65_537, 65_536), ControlPayloadCodec().encode(listOf(entry)))
    }
    @Test fun A01_Applied_encoder_exact_utf8_limit() {
        val entry = sizedEntry(applied(), "commandId", 65_536)
        val encoded = ControlPayloadCodec().encode(listOf(entry)) as PayloadWrite.Encoded
        assertEquals(65_536, encoded.text.toByteArray(Charsets.UTF_8).size)
        assertFalse((read(record(evidence = encoded.text)) as ControlRecordRead.Supported).blocksProtectedAdmission)
    }
    @Test fun A01_Applied_encoder_over_utf8_limit() {
        val entry = sizedEntry(applied(), "commandId", 65_537)
        assertEquals(PayloadWrite.TooLarge(65_537, 65_536), ControlPayloadCodec().encode(listOf(entry)))
    }
}
