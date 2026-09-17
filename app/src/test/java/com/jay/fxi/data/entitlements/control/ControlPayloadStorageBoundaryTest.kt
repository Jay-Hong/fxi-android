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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the record's own storage does to the text this codec writes.
 *
 * The codec's other tests stop at the string it returns. This one carries that string through a real
 * `Preferences` file and reads it back with a second store, because the loss this guards against
 * happens below the codec: the file is protobuf, protobuf strings are UTF-8, and a lone surrogate is
 * not something UTF-8 can carry — the encoder substitutes `?` rather than failing, so nothing above
 * would notice.
 *
 * Reading back through a *second* [PreferenceDataStoreFactory] instance is the point. A store answers
 * from memory after its own write, so asking the one that just wrote proves nothing about the file;
 * measured that way a lone surrogate looks preserved.
 */
class ControlPayloadStorageBoundaryTest {

    private val codec = ControlPayloadCodec()
    private val key = stringPreferencesKey("seal_v1")

    private fun throughAFile(value: String): String? {
        val dir = Files.createTempDirectory("p2b-storage").toFile()
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

    private fun written(raw: String): String =
        (codec.encode((codec.decode(raw) as PayloadRead.Parsed).entries) as PayloadWrite.Encoded).text

    /**
     * The control the rest of this class needs: the boundary really does lose a lone surrogate. Without
     * it a storage that happened to preserve everything would let the assertions below pass for nothing.
     */
    @Test
    fun `the tree's own spelling of a lone surrogate does not survive a file`() {
        val asTheTreeSpellsIt = """[{"future":"${"\uD800"}"}]"""

        val back = throughAFile(asTheTreeSpellsIt)

        assertNotEquals(asTheTreeSpellsIt, back)
        assertEquals("""[{"future":"?"}]""", back)
    }

    @Test
    fun `what the codec writes comes back from a file unchanged`() {
        for (raw in listOf(
            """[{"future":"\uD800"}]""",
            """[{"\uD800":"x"}]""",
            """[{"a":{"nested":"\uDFFF"}}]""",
            """[{"ok":"😀한글"}]""",
            """[{"plain":"a"}]"""
        )) {
            val text = written(raw)

            assertEquals(raw, text, throughAFile(text))
        }
    }

    /**
     * Two lone surrogate keys is the case that bites twice: stored as the tree spells them they both
     * come back as `?`, and the codec's own duplicate-key branch then refuses the payload — damage it
     * would have inflicted on itself.
     */
    @Test
    fun `two lone surrogate keys still read back as two keys`() {
        val raw = """[{"\uD800":1,"\uDC00":2}]"""

        val stored = throughAFile(written(raw))
        val reread = codec.decode(checkNotNull(stored))

        assertEquals(raw, stored)
        assertTrue("$reread", reread is PayloadRead.Parsed)
        assertEquals(2, ((reread as PayloadRead.Parsed).entries.single() as PayloadEntry.Obj).fields.size)
    }

    /** And the same two keys written as the tree spells them are the damage — this is what was avoided. */
    @Test
    fun `the tree's own spelling of two lone surrogate keys collapses into a refusal`() {
        val asTheTreeSpellsIt = """[{"${"\uD800"}":1,"${"\uDC00"}":2}]"""

        val stored = checkNotNull(throughAFile(asTheTreeSpellsIt))

        assertEquals("""[{"?":1,"?":2}]""", stored)
        assertEquals(
            PayloadRead.Unreadable(PayloadUnreadable.DUPLICATE_KEY, stored),
            codec.decode(stored)
        )
    }
}
