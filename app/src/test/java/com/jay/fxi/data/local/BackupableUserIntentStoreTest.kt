package com.jay.fxi.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.domain.model.GraphSeriesSelection
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned S4 B2b-1 contract r1: the UID-scoped graph selection record in the new backupable-intent DataStore.
 *
 * Oracles: ANDROID_V2_PLAN.md :1292 (visibleSeriesIds and initializedSeries persisted together, UID-scoped, so a new default
 * and a user's all-off stay apart), :1098 S2 (free and premium selections are separate namespaces), :397 I4 (user intent is
 * not derived data). Design: R4c/S4 b2_design_codex.r1 §2 and b2b_api_codex.r1 §1-§2 (key = uid, audience, tab - no period
 * or epoch; physical key graph_selection/v1/<base64url uid>/<free|premium>/<base64url tab>; one JSON record per key with
 * sorted arrays; Absent / Present / Unreadable kept apart; a write is Committed, NotCommitted - the candidate never reached
 * DataStore - or Uncertain - it did and no normal return followed; a read and its read-back go through DataStore's write
 * transaction, never through the cached data flow; a cancellation propagates).
 *
 * Fixture: [IntentStoreHarness]. The implementation thread reads but does not edit this file.
 */
class BackupableUserIntentStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private companion object {
        val A_PREMIUM_USD = GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, "usd")
        val A_FREE_USD = GraphSelectionKey("A", GraphSelectionAudience.FREE, "usd")
        val A_PREMIUM_JPY = GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, "jpy")
        val B_PREMIUM_USD = GraphSelectionKey("B", GraphSelectionAudience.PREMIUM, "usd")
    }

    private val harness by lazy { IntentStoreHarness(File(folder.root, "fxi_backupable_user_intent.preferences_pb")) }

    @After fun tearDown() = runBlocking { harness.close() }

    private suspend fun open() = harness.open()

    private fun sel(v: Set<String>, i: Set<String>) = GraphSeriesSelection(v, i)

    private fun present(key: GraphSelectionKey, v: Set<String>, i: Set<String>) =
        GraphSelectionReadResult.Present(GraphSelectionRecord(1, key.uid, key.audience, key.tab, v, i))

    private fun name(key: GraphSelectionKey) = GraphSelectionPreferencesCodec.keyName(key)

    private fun GraphSelectionReadResult.reason() = (this as GraphSelectionReadResult.Unreadable).reason

    // --- B2-09: namespace -------------------------------------------------------------------------------------

    /** B2-09a: the UID, the audience and the tab each keep a record of their own across a reopen; B is absent until written. */
    @Test fun B2_09a_eachUidAudienceAndTabKeepsItsOwnRecord() = runBlocking {
        val o = open()
        assertEquals(GraphSelectionWriteResult.Committed, o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("P"), setOf("P"))))
        assertEquals(GraphSelectionWriteResult.Committed, o.store.writeGraphSelection(A_FREE_USD, sel(setOf("F"), setOf("F"))))
        assertEquals(GraphSelectionWriteResult.Committed, o.store.writeGraphSelection(A_PREMIUM_JPY, sel(setOf("J"), setOf("J"))))
        val r = open().store
        assertEquals(present(A_PREMIUM_USD, setOf("P"), setOf("P")), r.readGraphSelection(A_PREMIUM_USD))
        assertEquals(present(A_FREE_USD, setOf("F"), setOf("F")), r.readGraphSelection(A_FREE_USD))
        assertEquals(present(A_PREMIUM_JPY, setOf("J"), setOf("J")), r.readGraphSelection(A_PREMIUM_JPY))
        assertEquals("B starts absent", GraphSelectionReadResult.Absent, r.readGraphSelection(B_PREMIUM_USD))
        assertEquals(GraphSelectionWriteResult.Committed, r.writeGraphSelection(B_PREMIUM_USD, sel(setOf("Y"), setOf("Y"))))
        val again = open().store
        assertEquals(present(B_PREMIUM_USD, setOf("Y"), setOf("Y")), again.readGraphSelection(B_PREMIUM_USD))
        assertEquals("A untouched", present(A_PREMIUM_USD, setOf("P"), setOf("P")), again.readGraphSelection(A_PREMIUM_USD))
        assertEquals("A untouched", present(A_FREE_USD, setOf("F"), setOf("F")), again.readGraphSelection(A_FREE_USD))
        assertEquals("A untouched", present(A_PREMIUM_JPY, setOf("J"), setOf("J")), again.readGraphSelection(A_PREMIUM_JPY))
    }

    /**
     * B2-09b: the physical key encodes each component with the fixed encoding, so a delimiter inside a UID or a tab cannot
     * collide; a blank UID or tab is refused with IllegalArgumentException - by the key, and by the UID deletion - before any I/O.
     */
    @Test fun B2_09b_componentsAreEncodedAndBlankOnesRefused() = runBlocking {
        assertEquals("graph_selection/v1/QQ/premium/dXNk", name(A_PREMIUM_USD))
        assertEquals("graph_selection/v1/QQ/free/dXNk", name(A_FREE_USD))
        val left = GraphSelectionKey("a/premium/b", GraphSelectionAudience.PREMIUM, "c")
        val right = GraphSelectionKey("a", GraphSelectionAudience.PREMIUM, "b/premium/c")
        assertTrue("distinct physical keys", name(left) != name(right))
        val o = open()
        assertEquals(GraphSelectionWriteResult.Committed, o.store.writeGraphSelection(left, sel(setOf("L"), setOf("L"))))
        assertEquals(GraphSelectionWriteResult.Committed, o.store.writeGraphSelection(right, sel(setOf("R"), setOf("R"))))
        val r = open()
        assertEquals(present(left, setOf("L"), setOf("L")), r.store.readGraphSelection(left))
        assertEquals(present(right, setOf("R"), setOf("R")), r.store.readGraphSelection(right))
        val updates = r.dataStore.updates
        listOf("", "   ").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) { GraphSelectionKey(blank, GraphSelectionAudience.PREMIUM, "usd") }
            assertThrows(IllegalArgumentException::class.java) { GraphSelectionKey("A", GraphSelectionAudience.PREMIUM, blank) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { r.store.deleteGraphSelections(blank) } }
        }
        assertEquals("no DataStore call for refused input", updates, r.dataStore.updates)
    }

    // --- B2-10: atomic persistence, errors, uncertainty ------------------------------------------------------

    /** B2-10a: an all-off pair (empty visible, X initialized) is committed and restored exactly, not as an absence. */
    @Test fun B2_10a_anAllOffPairIsRestoredExactly() = runBlocking {
        val o = open()
        o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("X"), setOf("X")))
        assertEquals(GraphSelectionWriteResult.Committed, o.store.writeGraphSelection(A_PREMIUM_USD, sel(emptySet(), setOf("X"))))
        assertEquals(present(A_PREMIUM_USD, emptySet(), setOf("X")), open().store.readGraphSelection(A_PREMIUM_USD))
    }

    /** B2-10b: both sets change in one record - one value holds all six fields, arrays sorted. */
    @Test fun B2_10b_bothSetsChangeInOneRecord() = runBlocking {
        val o = open()
        o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("X"), setOf("X")))
        assertEquals(GraphSelectionWriteResult.Committed, o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("Y"), setOf("Y", "X"))))
        val r = open()
        assertEquals(present(A_PREMIUM_USD, setOf("Y"), setOf("X", "Y")), r.store.readGraphSelection(A_PREMIUM_USD))
        assertEquals(
            mapOf(
                name(A_PREMIUM_USD) to "{\"schemaVersion\":1,\"uid\":\"A\",\"audience\":\"premium\",\"tab\":\"usd\"," +
                    "\"visibleSeriesIds\":[\"Y\"],\"initializedSeries\":[\"X\",\"Y\"]}"
            ),
            r.raw().asMap().mapKeys { it.key.name }
        )
    }

    /**
     * B2-10c: a failure before the candidate reaches DataStore is NotCommitted, and the old pair stays whole. A cancellation
     * there propagates rather than becoming a result.
     */
    @Test fun B2_10c_aFailureBeforeTheDelegateIsNotCommitted() = runBlocking {
        val o = open()
        o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("X"), setOf("X")))
        o.dataStore.failBeforeUpdate = true
        assertTrue(o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("Y"), setOf("X", "Y"))) is GraphSelectionWriteResult.NotCommitted)
        o.dataStore.cancelBeforeUpdate = true
        assertThrows(CancellationException::class.java) {
            runBlocking { o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("Y"), setOf("X", "Y"))) }
        }
        assertEquals(present(A_PREMIUM_USD, setOf("X"), setOf("X")), open().store.readGraphSelection(A_PREMIUM_USD))
    }

    /**
     * B2-10d: a failure after the write block and before the rename is Uncertain. DataStore's cache already serves the new pair
     * (the premise); the read-back and the file still have the old pair.
     */
    @Test fun B2_10d_aFailureBeforeTheRenameIsUncertainAndReadsBackOld() = runBlocking {
        val o = open()
        o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("X"), setOf("X")))
        o.storage.failAfterWrite = true
        assertTrue(o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("Y"), setOf("X", "Y"))) is GraphSelectionWriteResult.Uncertain)
        val cached = o.raw()[stringPreferencesKey(name(A_PREMIUM_USD))]
        assertTrue("premise: the cache serves the new pair", cached?.contains("\"visibleSeriesIds\":[\"Y\"]") == true)
        assertEquals("read-back: old", present(A_PREMIUM_USD, setOf("X"), setOf("X")), o.store.confirmGraphSelection(A_PREMIUM_USD))
        assertEquals("read: old", present(A_PREMIUM_USD, setOf("X"), setOf("X")), o.store.readGraphSelection(A_PREMIUM_USD))
        assertEquals("file: old", present(A_PREMIUM_USD, setOf("X"), setOf("X")), open().store.readGraphSelection(A_PREMIUM_USD))
    }

    /** B2-10e: a failure after DataStore returned normally is Uncertain, never Committed; the read-back finds the new pair. */
    @Test fun B2_10e_aFailureAfterTheDelegateReturnedIsUncertainAndReadsBackNew() = runBlocking {
        val o = open()
        o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("X"), setOf("X")))
        o.dataStore.failAfterUpdate = true
        assertTrue(o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("Y"), setOf("X", "Y"))) is GraphSelectionWriteResult.Uncertain)
        assertEquals(present(A_PREMIUM_USD, setOf("Y"), setOf("X", "Y")), o.store.confirmGraphSelection(A_PREMIUM_USD))
        assertEquals(present(A_PREMIUM_USD, setOf("Y"), setOf("X", "Y")), open().store.readGraphSelection(A_PREMIUM_USD))
    }

    /** B2-10f: a read-back that fails on I/O is Unreadable(IO), never Absent; once the failure clears it finds the exact pair. */
    @Test fun B2_10f_aFailedReadBackIsUnreadableNotAbsent() = runBlocking {
        val o = open()
        o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("X"), setOf("X")))
        o.dataStore.failAfterUpdate = true
        o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("Y"), setOf("X", "Y")))
        o.storage.failRead = true
        assertEquals(GraphSelectionUnreadableReason.IO, o.store.confirmGraphSelection(A_PREMIUM_USD).reason())
        assertEquals(GraphSelectionUnreadableReason.IO, o.store.readGraphSelection(A_PREMIUM_USD).reason())
        o.storage.failRead = false
        assertEquals(present(A_PREMIUM_USD, setOf("Y"), setOf("X", "Y")), o.store.confirmGraphSelection(A_PREMIUM_USD))
    }

    /**
     * B2-10g: a damaged record is Unreadable with its reason, never Absent, and is not overwritten - malformed JSON, a value of
     * another Preferences type, a missing field (MALFORMED_RECORD); schema 2 (UNSUPPORTED_SCHEMA); another uid, audience or tab
     * inside the record (IDENTITY_MISMATCH); visible not inside initialized (INVALID_SELECTION); a damaged file
     * (CORRUPT_DATASTORE, its bytes kept).
     */
    @Test fun B2_10g_aDamagedRecordIsUnreadableAndNotOverwritten() = runBlocking {
        val key = name(A_PREMIUM_USD)
        fun json(schema: Int = 1, uid: String = "A", audience: String = "premium", tab: String = "usd",
                 v: String = "[\"X\"]", i: String = "[\"X\"]") =
            "{\"schemaVersion\":$schema,\"uid\":\"$uid\",\"audience\":\"$audience\",\"tab\":\"$tab\"," +
                "\"visibleSeriesIds\":$v,\"initializedSeries\":$i}"
        val cases = listOf<Triple<String, GraphSelectionUnreadableReason, (MutablePreferences) -> Unit>>(
            Triple("malformed JSON", GraphSelectionUnreadableReason.MALFORMED_RECORD, { it[stringPreferencesKey(key)] = "{not json" }),
            Triple("another type", GraphSelectionUnreadableReason.MALFORMED_RECORD, { it[intPreferencesKey(key)] = 3 }),
            Triple("missing field", GraphSelectionUnreadableReason.MALFORMED_RECORD, {
                it[stringPreferencesKey(key)] =
                    "{\"schemaVersion\":1,\"uid\":\"A\",\"audience\":\"premium\",\"tab\":\"usd\",\"visibleSeriesIds\":[\"X\"]}"
            }),
            Triple("schema 2", GraphSelectionUnreadableReason.UNSUPPORTED_SCHEMA, { it[stringPreferencesKey(key)] = json(schema = 2) }),
            Triple("another uid", GraphSelectionUnreadableReason.IDENTITY_MISMATCH, { it[stringPreferencesKey(key)] = json(uid = "B") }),
            Triple("another audience", GraphSelectionUnreadableReason.IDENTITY_MISMATCH, {
                it[stringPreferencesKey(key)] = json(audience = "free")
            }),
            Triple("another tab", GraphSelectionUnreadableReason.IDENTITY_MISMATCH, { it[stringPreferencesKey(key)] = json(tab = "jpy") }),
            Triple("visible outside initialized", GraphSelectionUnreadableReason.INVALID_SELECTION, {
                it[stringPreferencesKey(key)] = json(v = "[\"Y\"]", i = "[\"X\"]")
            })
        )
        for ((label, reason, damage) in cases) {
            harness.close()
            harness.file.delete()
            val o = open()
            o.putRaw(damage)
            val before = o.raw()
            assertEquals(label, reason, o.store.readGraphSelection(A_PREMIUM_USD).reason())
            assertEquals("$label: read-back", reason, o.store.confirmGraphSelection(A_PREMIUM_USD).reason())
            val writes = o.storage.writes
            assertTrue("$label: not overwritten",
                o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("Z"), setOf("Z"))) is GraphSelectionWriteResult.NotCommitted)
            assertEquals("$label: no candidate written", writes, o.storage.writes)
            assertEquals("$label: unchanged", before, o.raw())
        }
        harness.close()
        val damaged = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        harness.file.writeBytes(damaged)
        val o = open()
        assertEquals("a damaged file", GraphSelectionUnreadableReason.CORRUPT_DATASTORE, o.store.readGraphSelection(A_PREMIUM_USD).reason())
        assertTrue("a damaged file: not overwritten",
            o.store.writeGraphSelection(A_PREMIUM_USD, sel(setOf("Z"), setOf("Z"))) is GraphSelectionWriteResult.NotCommitted)
        assertEquals("a damaged file: no write", 0, o.storage.writes)
        harness.close()
        assertTrue("a damaged file: bytes kept", damaged.contentEquals(harness.file.readBytes()))
    }

    /** B2-10h: an absent key and an empty record are told apart; a read and a read-back write nothing and add no other key. */
    @Test fun B2_10h_anAbsentKeyAndAnEmptyRecordAreToldApart() = runBlocking {
        val o = open()
        assertEquals(GraphSelectionWriteResult.Committed, o.store.writeGraphSelection(A_FREE_USD, sel(emptySet(), emptySet())))
        val r = open()
        val writes = r.storage.writes
        assertEquals(GraphSelectionReadResult.Absent, r.store.readGraphSelection(A_PREMIUM_USD))
        assertEquals(present(A_FREE_USD, emptySet(), emptySet()), r.store.readGraphSelection(A_FREE_USD))
        assertEquals(GraphSelectionReadResult.Absent, r.store.confirmGraphSelection(A_PREMIUM_USD))
        assertEquals(present(A_FREE_USD, emptySet(), emptySet()), r.store.confirmGraphSelection(A_FREE_USD))
        assertEquals("no physical write", writes, r.storage.writes)
        assertEquals("only the graph record's key", setOf(name(A_FREE_USD)), r.raw().asMap().keys.map { it.name }.toSet())
    }
}
