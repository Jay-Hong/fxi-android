package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.emptyGuard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.guard
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.hold
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.seal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.topicHold
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ControlObligationArrayTest {
    private val codec = ControlPayloadCodec()
    private fun read(kind: ControlKind, text: String) = ControlObligations.readArray(kind, codec.decode(text)) as ControlArrayRead.Parsed

    @Test fun `normal sibling edits preserve unknown nested literals scalar entries and order`() {
        val future = request.replace("\"d\"", "\"future-d\"").dropLast(1) + ",\"future\":{\"precise\":1e-400,\"nested\":[-0,null]}}"
        val payload = codec.decode("[$future,7,$request]")
        val array = ControlObligations.readArray(ControlKind.DEMAND, payload) as ControlArrayRead.Parsed
        assertTrue(array.hasUninterpretable)
        assertTrue(array.entries[0] is ControlEntryRead.Uninterpretable)
        assertTrue(array.entries[1] is ControlEntryRead.Uninterpretable)
        assertTrue(array.entries[2] is ControlEntryRead.Interpreted)
        val changed = ControlObligations.editArrayEntry(ControlKind.DEMAND, payload, 2) { set("raisedAt", ControlScalar.Integer(8)) } as ControlArrayWriteResult.Written
        assertEquals("[$future,7,${request.replace("\"raisedAt\":4", "\"raisedAt\":8")}]", (codec.encode(changed.payload.entries) as PayloadWrite.Encoded).text)
        assertEquals("[$future,7,$request]", (codec.encode((payload as PayloadRead.Parsed).entries) as PayloadWrite.Encoded).text)
        assertTrue((ControlObligations.readArray(ControlKind.DEMAND, changed.payload) as ControlArrayRead.Parsed).hasUninterpretable)
    }

    @Test fun `duplicate id implicates all occurrences including a future object but not independent sibling`() {
        val future = request.dropLast(1) + ",\"future\":true}"
        val payload = codec.decode("[$request,$future,$emptyGuard]")
        val array = ControlObligations.readArray(ControlKind.DEMAND, payload) as ControlArrayRead.Parsed
        assertTrue(array.entries[0] is ControlEntryRead.Uninterpretable)
        assertTrue(array.entries[1] is ControlEntryRead.Uninterpretable)
        assertTrue(array.entries[2] is ControlEntryRead.Interpreted)
        var called = false
        assertEquals(ControlArrayWriteResult.Rejected(ControlWriteFailure.UNINTERPRETABLE_OBLIGATION), ControlObligations.editArrayEntry(ControlKind.DEMAND, payload, 0) { called = true })
        assertFalse(called)
    }

    @Test fun `two different guard ids are both uninterpretable while requests remain independently editable`() {
        val futureGuard = emptyGuard.replace("\"g\"", "\"g2\"").dropLast(1) + ",\"future\":true}"
        val array = read(ControlKind.DEMAND, "[$guard,$futureGuard,$request]")
        assertTrue(array.entries[0] is ControlEntryRead.Uninterpretable)
        assertTrue(array.entries[1] is ControlEntryRead.Uninterpretable)
        assertTrue(array.entries[2] is ControlEntryRead.Interpreted)
        val payload = codec.decode("[$guard,$futureGuard,$request]")
        assertTrue(ControlObligations.editArrayEntry(ControlKind.DEMAND, payload, 2) { set("raisedAt", ControlScalar.Integer(9)) } is ControlArrayWriteResult.Written)
    }

    @Test fun `multiple requests from old and new lifetime coexist without implicit merge`() {
        val other = request.replace("\"d\"", "\"d2\"").replace("\"life\"", "\"new-life\"")
        val array = read(ControlKind.DEMAND, "[$request,$other,$emptyGuard]")
        assertFalse(array.hasUninterpretable)
        assertEquals(listOf("d", "d2", "g"), array.entries.map { (it as ControlEntryRead.Interpreted).value.id })
    }

    @Test fun `same seal key with different ids is retained even with future fields`() {
        val other = seal.replace("\"s\"", "\"s2\"")
        val future = seal.replace("\"s\"", "\"s3\"").dropLast(1) + ",\"future\":1}"
        val payload = codec.decode("[$seal,$other,$future]")
        val array = ControlObligations.readArray(ControlKind.SEAL, payload) as ControlArrayRead.Parsed
        assertEquals(3, array.entries.size)
        assertTrue(array.entries[0] is ControlEntryRead.Interpreted)
        assertTrue(array.entries[1] is ControlEntryRead.Interpreted)
        assertTrue(array.entries[2] is ControlEntryRead.Uninterpretable)
        val same = ControlObligations.editArrayEntry(ControlKind.SEAL, payload, 0) {} as ControlArrayWriteResult.Written
        assertEquals("[$seal,$other,$future]", (codec.encode(same.payload.entries) as PayloadWrite.Encoded).text)
    }

    @Test fun `hold array keeps oldest first rather than sorting ids or kinds`() {
        val first = hold.replace("\"h\"", "\"z\"")
        val second = topicHold.replace("\"t\"", "\"a\"")
        val payload = codec.decode("[$first,$second]")
        val array = ControlObligations.readArray(ControlKind.HOLD, payload) as ControlArrayRead.Parsed
        assertEquals(listOf("z", "a"), array.entries.map { (it as ControlEntryRead.Interpreted).value.id })
        val same = ControlObligations.editArrayEntry(ControlKind.HOLD, payload, 1) {} as ControlArrayWriteResult.Written
        assertEquals("[$first,$second]", (codec.encode(same.payload.entries) as PayloadWrite.Encoded).text)
    }

    @Test fun `unreadable envelope remains unreadable and no callback runs`() {
        val payload = codec.decode("{broken") as PayloadRead.Unreadable
        assertEquals(ControlArrayRead.Unreadable(payload), ControlObligations.readArray(ControlKind.SEAL, payload))
        var called = false
        assertEquals(ControlArrayWriteResult.Rejected(ControlWriteFailure.UNINTERPRETABLE_OBLIGATION), ControlObligations.editArrayEntry(ControlKind.SEAL, payload, 0) { called = true })
        assertFalse(called)
        assertTrue(read(ControlKind.HOLD, "[]").entries.isEmpty())
    }

    @Test fun `opaque arrays detach mutable backing on import and export`() {
        val backing = mutableListOf<JsonElement>(JsonPrimitive("old"))
        val parsed = ControlObligations.readArray(ControlKind.SEAL, PayloadRead.Parsed(listOf(PayloadEntry.Uninterpretable(JsonArray(backing))))) as ControlArrayRead.Parsed
        backing += JsonPrimitive("changed")
        val entry = parsed.entries.single() as ControlEntryRead.Uninterpretable
        val exported = (entry.original as PayloadEntry.Uninterpretable).raw as JsonArray
        try {
            (exported as MutableList<JsonElement>).add(JsonPrimitive("export-change"))
        } catch (_: UnsupportedOperationException) {
            // A read-only export also prevents the attempted alias mutation.
        } catch (_: ClassCastException) {
            // Kotlin's read-only JsonArray may reject the mutable interface itself.
        }
        assertEquals("[\"old\"]", (entry.original as PayloadEntry.Uninterpretable).raw.toString())
    }

    @Test fun `array edit checks bounds and does not publish invalid changes`() {
        val payload = codec.decode("[$request]")
        assertEquals(ControlArrayWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), ControlObligations.editArrayEntry(ControlKind.DEMAND, payload, -1) { fail() })
        assertEquals(ControlArrayWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), ControlObligations.editArrayEntry(ControlKind.DEMAND, payload, 0) { set("id", ControlScalar.Text("different")) })
    }
}
