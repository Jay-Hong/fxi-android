package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An edited node on its way to the record, through everything that is actually between them.
 *
 * The tests in [ControlEditorTest] stop at the node. These carry it through the real export, the real
 * envelope encoder and a real `Preferences` file read back by a second store, because every loss this
 * layer is answering for happened at one of those boundaries rather than inside the edit.
 */
class ControlNodeEnvelopeTest {

    private val codec = ControlPayloadCodec()
    private val key = stringPreferencesKey("seal_v1")

    private fun node(json: String): ControlNode =
        ControlNode.of(Json.parseToJsonElement(json) as JsonObject)

    private fun written(result: ControlWriteResult): ControlNode =
        (result as ControlWriteResult.Written).node

    private fun throughAFile(value: String): String? {
        val dir = Files.createTempDirectory("p2b2-envelope").toFile()
        val file = File(dir, "control.preferences_pb")
        return try {
            runBlocking {
                val writing = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                PreferenceDataStoreFactory.create(scope = writing, produceFile = { file })
                    .edit { it[key] = value }
                writing.cancel()
                writing.coroutineContext.job.join()

                val reading = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val back = PreferenceDataStoreFactory.create(scope = reading, produceFile = { file })
                    .data.first()[key]
                reading.cancel()
                reading.coroutineContext.job.join()
                back
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * One field named and changed; everything else — a number literal no reader would spell back the
     * same way, a lone surrogate no UTF-8 file can carry raw, a nested unknown object — arrives at the
     * far side of a file unchanged.
     */
    @Test
    fun `an edited node survives the envelope and a file with everything it did not name`() {
        val base = node(
            """{"owner":"old","kept":1e5,"zero":-0,"future":"\uD800","nest":{"deep":{"x":[1,"\uDFFF"]}}}"""
        )

        val edited = written(base.edited { set("owner", ControlScalar.Text("new")) })
        val encoded = codec.encode(listOf(edited.toPayloadEntry())) as PayloadWrite.Encoded
        val stored = checkNotNull(throughAFile(encoded.text))

        assertEquals(encoded.text, stored)
        assertEquals(
            """[{"owner":"new","kept":1e5,"zero":-0,"future":"\uD800","nest":{"deep":{"x":[1,"\uDFFF"]}}}]""",
            stored
        )

        val reread = codec.decode(stored)
        assertTrue("$reread", reread is PayloadRead.Parsed)
        val back = ControlNode.of((reread as PayloadRead.Parsed).entries.single().let { it as PayloadEntry.Obj }.fields)
        assertEquals(setOf("owner", "kept", "zero", "future", "nest"), back.names)
        assertEquals(FieldRead.Present("new"), back.text("owner"))
        assertEquals(FieldRead.Present("\uD800"), back.text("future"))
    }

    /** Encoding the same node twice gives the same text — the first pass is the only normalisation. */
    @Test
    fun `re-encoding an edited node changes nothing further`() {
        val edited = written(node("""{"a":"old","keep":1.50}""").edited { set("a", ControlScalar.Text("new")) })

        val once = (codec.encode(listOf(edited.toPayloadEntry())) as PayloadWrite.Encoded).text
        val reread = codec.decode(once) as PayloadRead.Parsed
        val twice = (codec.encode(reread.entries) as PayloadWrite.Encoded).text

        assertEquals(once, twice)
    }

    // --- 23. 노드의 성공은 저장 가능성이 아니다 ------------------------------------------------------

    /**
     * A node this layer was happy to write is still a payload the envelope may refuse. The refusal is
     * of the whole write: the entries the caller holds are untouched, and making them fit by dropping
     * one is the loss this record exists to prevent.
     */
    @Test
    fun `an edit the node accepted can still be too large for the envelope`() {
        val small = ControlPayloadCodec(maxPayloadBytes = 40)
        val base = node("""{"a":"1","unknown":"keep"}""")

        val edited = written(base.edited { set("a", ControlScalar.Text("x".repeat(60))) })
        val write = small.encode(listOf(edited.toPayloadEntry()))

        assertTrue("$write", write is PayloadWrite.TooLarge)
        assertEquals(
            """[{"a":"${"x".repeat(60)}","unknown":"keep"}]""",
            (codec.encode(listOf(edited.toPayloadEntry())) as PayloadWrite.Encoded).text
        )
    }

    /**
     * Depth is the envelope's precondition rather than a refusal it returns, and an edit can cross it.
     *
     * Two edits can, not one. A name that was not there before is the obvious way. The other is an
     * existing empty list: `all {}` holds vacuously, so a list with nothing in it takes names, and its
     * first element sits a level below where the list already was — the key and its kind both stay the
     * same. A node cannot check this itself, because it does not know where in a payload it sits.
     *
     * The limit counts every level the tree scan walks, leaves included: `[{"a":{"b":"scalar"}}]` is
     * four, not the three its brackets suggest.
     */
    @Test
    fun `an edit that deepens a node past the envelope's limit is refused by the envelope`() {
        val shallow = ControlPayloadCodec(maxDepth = 4)

        val empty = node("""{"a":{"names":[]}}""")
        assertTrue(shallow.encode(listOf(empty.toPayloadEntry())) is PayloadWrite.Encoded)
        val filled = written(empty.edited { descend("a") { set("names", ControlScalar.Names(listOf("x"))) } })
        assertEquals(
            """[{"a":{"names":["x"]}}]""",
            (codec.encode(listOf(filled.toPayloadEntry())) as PayloadWrite.Encoded).text
        )
        assertThrows(IllegalArgumentException::class.java) {
            shallow.encode(listOf(filled.toPayloadEntry()))
        }

        val base = node("""{"a":{"b":"scalar"}}""")

        val unchanged = written(base.edited { set("a2", ControlScalar.Text("still shallow")) })
        assertTrue(shallow.encode(listOf(unchanged.toPayloadEntry())) is PayloadWrite.Encoded)

        val deepened = written(base.edited { descend("a") { set("added", ControlScalar.Names(listOf("x"))) } })

        assertThrows(IllegalArgumentException::class.java) {
            shallow.encode(listOf(deepened.toPayloadEntry()))
        }
        assertEquals(
            """[{"a":{"b":"scalar","added":["x"]}}]""",
            (codec.encode(listOf(deepened.toPayloadEntry())) as PayloadWrite.Encoded).text
        )
    }
}
