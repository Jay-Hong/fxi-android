package com.jay.fxi.data.entitlements.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a control payload must survive.
 *
 * The envelope names no obligation field, so every assertion here is about the two ways a payload
 * can be lost: read as something it does not say, or rewritten smaller than it came in.
 */
class ControlPayloadCodecTest {

    private val codec = ControlPayloadCodec()

    // --- the empty state, which is not absence ---------------------------------------------------------------------

    @Test
    fun `an empty array is the normal empty state`() {
        assertEquals(PayloadRead.Parsed(emptyList()), codec.decode("[]"))
    }

    /**
     * The mirror of the purge journal's rule: the writer stores the empty state as `[]`, so a key
     * holding nothing did not come from the writer and must not be read as "nothing is owed".
     */
    @Test
    fun `a payload holding nothing is damage rather than an empty list`() {
        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.NOT_JSON, ""), codec.decode(""))
    }

    @Test
    fun `an empty list is written as the explicit empty array`() {
        assertEquals(PayloadWrite.Encoded("[]"), codec.encode(emptyList()))
    }

    // --- shape ------------------------------------------------------------------------------------------------------

    @Test
    fun `objects are kept whole and in order`() {
        val read = codec.decode("""[{"id":"a"},{"id":"b"}]""")

        assertEquals(
            PayloadRead.Parsed(
                listOf(
                    PayloadEntry.Obj(JsonObject(mapOf("id" to JsonPrimitive("a")))),
                    PayloadEntry.Obj(JsonObject(mapOf("id" to JsonPrimitive("b"))))
                )
            ),
            read
        )
    }

    /** An obligation is an object, so anything else in the array is a wrong type — kept, not guessed at. */
    @Test
    fun `an element that is not an object is kept verbatim`() {
        val read = codec.decode("""[1,"x",null,[{"id":"a"}],{"id":"b"}]""")

        assertEquals(
            listOf(
                PayloadEntry.Uninterpretable(JsonPrimitive(1)),
                PayloadEntry.Uninterpretable(JsonPrimitive("x")),
                PayloadEntry.Uninterpretable(JsonNull),
                PayloadEntry.Uninterpretable(JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("a")))))),
                PayloadEntry.Obj(JsonObject(mapOf("id" to JsonPrimitive("b"))))
            ),
            (read as PayloadRead.Parsed).entries
        )
    }

    @Test
    fun `an element that is not an object is written back as it came in`() {
        val raw = """[1,"x",null,[1,2],{"id":"b"}]"""

        val written = codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries)

        assertEquals(PayloadWrite.Encoded(raw), written)
    }

    @Test
    fun `text that is not json is kept with its reason`() {
        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.NOT_JSON, "{not json"), codec.decode("{not json"))
    }

    @Test
    fun `json that is not an array is kept with its reason`() {
        val raw = """{"id":"a"}"""

        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.NOT_AN_ARRAY, raw), codec.decode(raw))
    }

    // --- preservation -------------------------------------------------------------------------------------------------

    /**
     * The envelope names no field, so a field it has never heard of — at any depth — cannot be
     * dropped. This is the property the obligation readers are built on top of.
     */
    @Test
    fun `fields this build never names survive a read and a write`() {
        val raw = """[{"id":"a","fromALaterBuild":{"nested":[1,{"deeper":true}]},"axis":"USER"}]"""

        val written = codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries)

        assertEquals(PayloadWrite.Encoded(raw), written)
    }

    /**
     * A serializer re-reads an unquoted literal as a `Long` or a `Double` and writes that back, which
     * rounds, changes and — for `1e400` — throws. None of these values has to mean anything to this
     * build for it to owe writing them back unchanged.
     */
    @Test
    fun `a number no primitive could hold is written back as it came in`() {
        val raw = """[{"tiny":1e-400,"huge":1e400,"precise":1.0000000000000000001,""" +
            """"big":123456789012345678901234567890,"shaped":1.5E+3}]"""

        val written = codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries)

        assertEquals(PayloadWrite.Encoded(raw), written)
    }

    @Test
    fun `a hostile number nested inside an unknown field survives too`() {
        val raw = """[{"a":"x","b":{"c":[1e400,-0,{"d":1.0000000000000000001}]}}]"""

        val written = codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries)

        assertEquals(PayloadWrite.Encoded(raw), written)
    }

    // --- what the parser accepts and this does not ---------------------------------------------------------------------

    /**
     * The tree reader takes literals JSON has no room for, and a serializer would then write `foo`
     * back as `"foo"`. Passing them on would leave every later reader owing an `isString` check on
     * every value; refusing the payload here does not.
     */
    @Test
    fun `a literal json has no room for is not read`() {
        for (raw in listOf(
            """[{"future":foo}]""",
            """[{"future":01}]""",
            """[{"future":truee}]""",
            """[{"future":NaN}]""",
            """[{"future":+1}]""",
            """[{"future":.5}]"""
        )) {
            assertEquals(raw, PayloadRead.Unreadable(PayloadUnreadable.NOT_JSON, raw), codec.decode(raw))
        }
    }

    @Test
    fun `a raw control character inside a string is not read`() {
        val raw = "[\"line\nbreak\"]"

        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.NOT_JSON, raw), codec.decode(raw))
    }

    /**
     * The parser keeps one value and drops the other, so by the time there is a tree the loss has
     * already happened. Re-writing what is left would make it permanent and leave no trace.
     */
    @Test
    fun `an object naming the same key twice is kept whole and unread`() {
        for (raw in listOf(
            """[{"future":1,"future":2}]""",
            """[{"a":1,"\u0061":2}]""",
            """[{"future":{"x":1,"x":2}}]""",
            """[{"future":{"nested":1},"future":{}}]"""
        )) {
            assertEquals(raw, PayloadRead.Unreadable(PayloadUnreadable.DUPLICATE_KEY, raw), codec.decode(raw))
        }
    }

    /** The count is of members, so a colon inside a string, an empty object and an array must not move it. */
    @Test
    fun `counting members is not confused by punctuation strings or empty containers`() {
        val raw = """[{},{"same":null},{"same":{}},{"text":"a:\"b","future":[1,2]}]"""

        assertEquals(PayloadWrite.Encoded(raw), codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries))
    }

    /** A hand-built entry is the only way to reach this, and it is a mistake rather than damage. */
    @Test(expected = IllegalArgumentException::class)
    fun `writing a value json cannot express is refused`() {
        codec.encode(listOf(PayloadEntry.Obj(JsonObject(mapOf("a" to JsonPrimitive(Double.NaN))))))
    }

    @Test
    fun `key order is kept as it was found`() {
        val raw = """[{"b":1,"a":2}]"""

        assertEquals(PayloadWrite.Encoded(raw), codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries))
    }

    /**
     * DataStore skips the write when the value has not changed (`DataStoreImpl.transformAndWrite`),
     * so re-reading and re-writing an untouched payload must produce the same bytes. Parsing does
     * drop the whitespace between tokens, so the guarantee is idempotence and not identity.
     */
    @Test
    fun `re-reading what was written produces the same bytes again`() {
        val spaced = """[ { "id" : "a" } ]"""

        val once = (codec.encode((codec.decode(spaced) as PayloadRead.Parsed).entries) as PayloadWrite.Encoded).text
        val twice = (codec.encode((codec.decode(once) as PayloadRead.Parsed).entries) as PayloadWrite.Encoded).text

        assertNotEquals(spaced, once)
        assertEquals(once, twice)
    }

    // --- size ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a payload of exactly the limit is read`() {
        val raw = """["${"a".repeat(16)}"]"""
        assertEquals(20, raw.toByteArray(Charsets.UTF_8).size)

        assertTrue(codecWith(20).decode(raw) is PayloadRead.Parsed)
    }

    @Test
    fun `a payload one byte over the limit is not read`() {
        val raw = """["${"a".repeat(17)}"]"""
        assertEquals(21, raw.toByteArray(Charsets.UTF_8).size)

        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.TOO_LARGE, raw), codecWith(20).decode(raw))
    }

    /** Ten characters, twenty-two bytes: counting characters would let this one through. */
    @Test
    fun `the limit counts utf-8 bytes and not characters`() {
        val raw = """["${"가".repeat(6)}"]"""
        assertEquals(10, raw.length)
        assertEquals(22, raw.toByteArray(Charsets.UTF_8).size)

        assertEquals(PayloadUnreadable.TOO_LARGE, (codecWith(20).decode(raw) as PayloadRead.Unreadable).reason)
    }

    /**
     * Reading, the size check runs before the parse — the cost being guarded against is parsing
     * something huge. An oversized payload is too large even when it is also malformed.
     */
    @Test
    fun `an oversized payload is not parsed to find out it is also malformed`() {
        val raw = "[".repeat(25)

        assertEquals(PayloadUnreadable.TOO_LARGE, (codecWith(20).decode(raw) as PayloadRead.Unreadable).reason)
    }

    /**
     * The same rule one step earlier: the scan that counts members also walks the whole text, so an
     * oversized payload must not be scanned either — not even to discover that its string never ends.
     */
    @Test
    fun `an oversized payload is not scanned to find out its string never ends`() {
        val raw = "[\"" + "a".repeat(25)

        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.TOO_LARGE, raw), codecWith(20).decode(raw))
    }

    /** Writing, it runs after the final encoding: until then the byte count is a guess. */
    @Test
    fun `a payload that would not fit is refused rather than shortened`() {
        val entries = listOf(PayloadEntry.Obj(JsonObject(mapOf("id" to JsonPrimitive("a".repeat(40))))))

        assertEquals(PayloadWrite.TooLarge(51, 20), codecWith(20).encode(entries))
    }

    @Test
    fun `a payload that encodes to exactly the limit is written`() {
        val entries = listOf(PayloadEntry.Obj(JsonObject(mapOf("id" to JsonPrimitive("a".repeat(9))))))

        val written = codecWith(20).encode(entries)

        assertEquals(20, (written as PayloadWrite.Encoded).text.toByteArray(Charsets.UTF_8).size)
    }

    /** Sixteen bytes in ten characters: the write side counts bytes too, and the limit is on the bytes. */
    @Test
    fun `the write limit counts utf-8 bytes and not characters`() {
        val entries = listOf(PayloadEntry.Obj(JsonObject(mapOf("a" to JsonPrimitive("가가")))))

        assertEquals(PayloadWrite.TooLarge(16, 12), codecWith(12).encode(entries))
    }

    // --- strictness ---------------------------------------------------------------------------------------------------

    /**
     * The injected `StorageJson` would read this: it has `isLenient` on. Reading it here would turn a
     * damaged payload into a plausible obligation, which is the one thing this record must not do.
     */
    @Test
    fun `json this build would have to be lenient to read is not read`() {
        val raw = """[{id:"a"}]"""

        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.NOT_JSON, raw), codec.decode(raw))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a limit that admits nothing is refused at construction`() {
        ControlPayloadCodec(maxPayloadBytes = 0)
    }

    /**
     * The tree reader keeps going past an array's ending, so `[1]2]` parses as `[1,2]` and damage would
     * be written back looking like a well-formed list. Pairing the brackets in the same scan refuses it.
     */
    @Test
    fun `text that runs on past the array ending is not read`() {
        for (raw in listOf("[1]2]", """[{"a":1}]{"b":2}]""", "[]]", """[{"a":1}}]""")) {
            assertEquals(raw, PayloadRead.Unreadable(PayloadUnreadable.NOT_JSON, raw), codec.decode(raw))
        }
    }

    // --- 저장이 지우는 문자 -------------------------------------------------------------------------------------------

    /**
     * A lone surrogate is not a character UTF-8 can carry, and the record's storage substitutes `?`
     * for it. Measured on a real DataStore file: the escaped form arrives as pure ASCII and stores
     * fine, so writing the tree's own spelling back is what loses it. The value is the same either
     * way, so it is written in the spelling that survives.
     */
    @Test
    fun `a lone surrogate is written in the spelling storage keeps`() {
        for (raw in listOf(
            """[{"future":"\uD800"}]""",
            """[{"future":"\uDC00"}]""",
            """[{"\uD800":"x"}]""",
            """[{"a":{"nested":"\uDFFF"}}]"""
        )) {
            val written = codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries)

            assertEquals(raw, PayloadWrite.Encoded(raw), written)
        }
    }

    /**
     * Two of them in one object is the sharp case: stored as the tree spells them, both keys come
     * back as `?`, and this codec's own duplicate-key branch then refuses the payload for good.
     * Writing them escaped is what keeps that from being self-inflicted.
     */
    @Test
    fun `two lone surrogate keys stay two keys`() {
        val raw = """[{"\uD800":1,"\uDC00":2}]"""

        val written = codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries)

        assertEquals(PayloadWrite.Encoded(raw), written)
        assertTrue(codec.decode((written as PayloadWrite.Encoded).text) is PayloadRead.Parsed)
    }

    /** A surrogate that has its pair encodes, so it is left alone. */
    @Test
    fun `a surrogate pair is written as the characters it is`() {
        val raw = """[{"ok":"\uD83D\uDE00한글"}]"""
        val entries = (codec.decode(raw) as PayloadRead.Parsed).entries

        val text = (codec.encode(entries) as PayloadWrite.Encoded).text

        assertEquals("""[{"ok":"😀한글"}]""", text)
        assertEquals(text, String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    /** The escape is six characters where the tree had one, so the limit has to see the longer text. */
    @Test
    fun `the size limit sees the escaped length`() {
        val entries = (codec.decode("""[{"a":"\uD800"}]""") as PayloadRead.Parsed).entries

        assertEquals(PayloadWrite.TooLarge(16, 12), codecWith(12).encode(entries))
    }

    // --- depth ---------------------------------------------------------------------------------------------------------

    private fun nested(depth: Int) = "[" + """{"a":""".repeat(depth) + "0" + "}".repeat(depth) + "]"

    /** Array, then `depth` objects, then the value: the limit counts every level. */
    @Test
    fun `a payload nested exactly to the limit is read and written back`() {
        val raw = nested(6)

        assertEquals(PayloadWrite.Encoded(raw), ControlPayloadCodec(maxDepth = 8).encode(
            (ControlPayloadCodec(maxDepth = 8).decode(raw) as PayloadRead.Parsed).entries
        ))
    }

    /**
     * `JsonElement.toString` recurses, and a tree deep enough to overflow it still fits the size limit
     * easily — measured: 2,000 levels is 12,003 bytes and overflows. Neither direction may reach that.
     */
    @Test
    fun `a payload nested past the limit is not read`() {
        val raw = nested(7)

        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.TOO_DEEP, raw), ControlPayloadCodec(maxDepth = 8).decode(raw))
    }

    @Test
    fun `a payload the default limit refuses is well inside the size limit`() {
        val raw = nested(2_000)

        assertTrue(raw.toByteArray(Charsets.UTF_8).size < ControlPayloadCodec.DEFAULT_MAX_PAYLOAD_BYTES)
        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.TOO_DEEP, raw), codec.decode(raw))
    }

    /**
     * The depth is counted off the text before the parser sees it, because the tree reader recurses
     * too. This payload is both too deep and never closed: reading it as `TOO_DEEP` is what shows the
     * depth was counted first, since a scan that only reported malformed text would say `NOT_JSON`.
     */
    @Test
    fun `depth is counted before the parser is handed anything`() {
        val raw = "[" + """{"a":""".repeat(100)

        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.TOO_DEEP, raw), ControlPayloadCodec(maxDepth = 8).decode(raw))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writing a tree nested past the limit is refused`() {
        val shallow = ControlPayloadCodec(maxDepth = 8)
        val entries = (shallow.decode(nested(6)) as PayloadRead.Parsed).entries

        ControlPayloadCodec(maxDepth = 3).encode(entries)
    }

    /**
     * A brace that closes a bracket is refused where it stands, not carried until something else
     * notices. Without that the stack unwinds one too far and the next sixty-five brackets read as
     * depth rather than as the damage they follow.
     */
    @Test
    fun `a brace closing a bracket is refused before anything else can explain the payload`() {
        val raw = "[}" + "[".repeat(65)

        assertEquals(PayloadRead.Unreadable(PayloadUnreadable.NOT_JSON, raw), codec.decode(raw))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a depth limit that admits nothing is refused at construction`() {
        ControlPayloadCodec(maxDepth = 0)
    }

    private fun codecWith(limit: Int) = ControlPayloadCodec(maxPayloadBytes = limit)
}
