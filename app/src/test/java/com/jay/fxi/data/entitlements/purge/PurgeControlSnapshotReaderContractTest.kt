package com.jay.fxi.data.entitlements.purge

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.ControlKind
import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures
import com.jay.fxi.data.entitlements.control.ControlRecordKeys
import com.jay.fxi.data.entitlements.control.ControlRecordProblem
import com.jay.fxi.data.entitlements.control.ControlRecordRead
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead.ClosedReason
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead.Journal
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned P2-J contract (purger 설계 v3 final §3.1·§7·§9; design agreed with Codex in R4/p2j_design_codex.r1.md).
 * One Preferences snapshot, two readers: the control record (ControlRecordReader) and the purge journal
 * (PurgeJournalCodec), combined so that neither side's failure is taken as the other side's "clean". The journal
 * literals below are wire text written by hand, not produced by the codec's writer. The implementation reads but does
 * not edit this file.
 */
class PurgeControlSnapshotReaderContractTest {
    private val F = ControlLifecycleEvidenceFixtures
    private val journalKey = DataStoreAccessEpochStore.PURGE_JOURNAL
    private val widened = JournalEntry.Owed(PendingPurge(null, null, null, setOf(PurgeScope.USER, PurgeScope.CAPABILITY)), PurgeCause.UNKNOWN)
    private fun read(p: Preferences) = PurgeControlSnapshotReader().read(p)
    private fun withJournal(base: Preferences, raw: String): Preferences = base.toMutablePreferences().apply { this[journalKey] = raw }.toPreferences()
    private fun closed(id: String, r: PurgeControlSnapshotRead, reasons: Set<ClosedReason>): PurgeControlSnapshotRead.Closed {
        assertTrue("$id: Closed, got $r", r is PurgeControlSnapshotRead.Closed)
        r as PurgeControlSnapshotRead.Closed
        assertEquals("$id: reasons", reasons, r.reasons)
        return r
    }
    private fun entries(id: String, r: PurgeControlSnapshotRead, raw: String): List<JournalEntry> {
        val j = r.journal
        assertTrue("$id: Present, got $j", j is Journal.Present)
        assertEquals("$id: raw kept", raw, (j as Journal.Present).raw)
        return j.entries
    }

    @Test fun J01_schemaTwo_noJournalKey_isPendingRuntimeAdmission_andKeepsUnrelatedKeys() {
        val r = read(F.raw())
        assertTrue("J01: PendingRuntimeAdmission, got $r", r is PurgeControlSnapshotRead.PendingRuntimeAdmission)
        assertEquals(Journal.Absent, r.journal)
        assertArrayEquals(byteArrayOf(0, 1, -1), r.control.original.asMap().entries.single { it.key.name == "lifecycle-external" }.value as ByteArray)
    }

    @Test fun J02_J03_J04_aPresentKeyThatSaysNothing_isDamage_everyLineKeptInOrder() {
        val cases = mapOf(
            "" to listOf(widened),
            "\n" to listOf(widened, widened),
            "u0|e1|e2|USER\n" to listOf(JournalEntry.Owed(PendingPurge("u0", "e1", "e2", setOf(PurgeScope.USER)), PurgeCause.UNKNOWN), widened)
        )
        for ((raw, expected) in cases) {
            val r = read(withJournal(F.raw(), raw))
            closed("J02-04 '$raw'", r, setOf(ClosedReason.JOURNAL_PRESENT))
            assertEquals("J02-04 '$raw': entries", expected, entries("J02-04", r, raw))
        }
    }

    @Test fun J05_J06_fourFieldsReadAsUnknown_fiveFieldsKeepTheirCause() {
        val four = read(withJournal(F.raw(), "u1|old-user||USER"))
        closed("J05", four, setOf(ClosedReason.JOURNAL_PRESENT))
        assertEquals(listOf(JournalEntry.Owed(PendingPurge("u1", "old-user", null, setOf(PurgeScope.USER)), PurgeCause.UNKNOWN)), entries("J05", four, "u1|old-user||USER"))
        val five = read(withJournal(F.raw(), "u1|old-user||USER|SIGN_OUT"))
        closed("J06", five, setOf(ClosedReason.JOURNAL_PRESENT))
        assertEquals(listOf(JournalEntry.Owed(PendingPurge("u1", "old-user", null, setOf(PurgeScope.USER)), PurgeCause.SIGN_OUT)), entries("J06", five, "u1|old-user||USER|SIGN_OUT"))
    }

    @Test fun J07_J08_anUnreadableNewFormLine_isKeptVerbatim_neverTakenAsUnknown() {
        for (raw in listOf("u1|old-user||USER|FUTURE", "u1|old-user||USER,BOGUS|SIGN_OUT", "u1|old-user||USER|SIGN_OUT|extra")) {
            val r = read(withJournal(F.raw(), raw))
            closed("J07-08 '$raw'", r, setOf(ClosedReason.JOURNAL_PRESENT, ClosedReason.JOURNAL_UNINTERPRETABLE))
            assertEquals("J07-08 '$raw'", listOf(JournalEntry.Uninterpretable(raw)), entries("J07-08", r, raw))
        }
    }

    @Test fun J09_healthySiblingsAroundAnUnreadableLine_stayInOrder() {
        val raw = "u1|a||USER|SIGN_OUT\nu2|b||USER|FUTURE\nu3|c|d|CAPABILITY"
        val r = read(withJournal(F.raw(), raw))
        closed("J09", r, setOf(ClosedReason.JOURNAL_PRESENT, ClosedReason.JOURNAL_UNINTERPRETABLE))
        assertEquals(listOf(
            JournalEntry.Owed(PendingPurge("u1", "a", null, setOf(PurgeScope.USER)), PurgeCause.SIGN_OUT),
            JournalEntry.Uninterpretable("u2|b||USER|FUTURE"),
            JournalEntry.Owed(PendingPurge("u3", "c", "d", setOf(PurgeScope.CAPABILITY)), PurgeCause.UNKNOWN)), entries("J09", r, raw))
    }

    @Test fun J10_aJournalKeyOfAnotherType_isWrongType_andItsValueStaysInTheSnapshot() {
        val p = F.raw().toMutablePreferences().apply { this[intPreferencesKey(journalKey.name)] = 5 }.toPreferences()
        val r = read(p)
        closed("J10", r, setOf(ClosedReason.JOURNAL_WRONG_TYPE))
        assertEquals(Journal.WrongType, r.journal)
        assertEquals(5, r.control.original.asMap().entries.single { it.key.name == journalKey.name }.value)
    }

    @Test fun J11_anEmptySnapshot_isMigrationOrRecovery() {
        val r = read(emptyPreferences())
        closed("J11", r, setOf(ClosedReason.CONTROL_MIGRATION_OR_RECOVERY))
        assertTrue(r.control is ControlRecordRead.MigrationOrRecoveryRequired)
        assertEquals(Journal.Absent, r.journal)
    }

    @Test fun J12_aLegacySnapshotWithAJournal_isNeverCleanEvidence_bothReasonsStand() {
        val legacy = mutablePreferencesOf().apply {
            this[stringPreferencesKey("owner_uid")] = "A"; this[journalKey] = "A|u|k|USER"
        }.toPreferences()
        val r = read(legacy)
        closed("J12", r, setOf(ClosedReason.CONTROL_MIGRATION_OR_RECOVERY, ClosedReason.JOURNAL_PRESENT))
        assertTrue(r.control is ControlRecordRead.MigrationOrRecoveryRequired)
    }

    @Test fun J13_aMissingSchemaWithControlKeys_isUnreadable_andTheJournalIsStillRead() {
        val p = withJournal(F.raw().toMutablePreferences().apply { remove(intPreferencesKey(ControlRecordKeys.SCHEMA)) }.toPreferences(), "u1|a||USER|SIGN_OUT")
        val r = read(p)
        closed("J13", r, setOf(ClosedReason.CONTROL_UNREADABLE, ClosedReason.JOURNAL_PRESENT))
        assertEquals(listOf(ControlRecordProblem.MissingSchema), (r.control as ControlRecordRead.Unreadable).problems)
        assertEquals(listOf(JournalEntry.Owed(PendingPurge("u1", "a", null, setOf(PurgeScope.USER)), PurgeCause.SIGN_OUT)), entries("J13", r, "u1|a||USER|SIGN_OUT"))
    }

    @Test fun J14_anUnreadableControlRecordAndAnUnreadableLine_areBothKept() {
        val schema3 = F.raw().toMutablePreferences().apply { this[intPreferencesKey(ControlRecordKeys.SCHEMA)] = 3 }.toPreferences()
        val noHold = F.raw().toMutablePreferences().apply { remove(ControlRecordKeys.payload(ControlKind.HOLD)) }.toPreferences()
        for ((name, base) in listOf("schema 3" to schema3, "missing hold" to noHold)) {
            val r = read(withJournal(base, "u1|a||USER|FUTURE"))
            closed("J14 $name", r, setOf(ClosedReason.CONTROL_UNREADABLE, ClosedReason.JOURNAL_PRESENT, ClosedReason.JOURNAL_UNINTERPRETABLE))
            assertTrue("J14 $name: problems kept", (r.control as ControlRecordRead.Unreadable).problems.isNotEmpty())
            assertEquals(listOf(JournalEntry.Uninterpretable("u1|a||USER|FUTURE")), entries("J14 $name", r, "u1|a||USER|FUTURE"))
        }
    }

    @Test fun J15_schemaOne_isSupportedButNeedsMigration() {
        val r = read(F.raw(schema = 1))
        closed("J15", r, setOf(ClosedReason.CONTROL_SCHEMA_MIGRATION_REQUIRED))
        assertEquals(1, (r.control as ControlRecordRead.Supported).schemaVersion)
    }

    @Test fun J16_anUninterpretableObligationOrMetadata_closesEvenWithoutAJournal() {
        val obligation = F.raw(hold = "[7]"); val metadata = F.raw(evidence = "[7]")
        check((ControlRecordReaderAccess.read(obligation) as ControlRecordRead.Supported).hasUninterpretable) { "fixture: obligation" }
        check((ControlRecordReaderAccess.read(metadata) as ControlRecordRead.Supported).hasUninterpretableMetadata) { "fixture: metadata" }
        closed("J16 obligation", read(obligation), setOf(ClosedReason.CONTROL_UNINTERPRETABLE_OBLIGATION))
        closed("J16 metadata", read(metadata), setOf(ClosedReason.CONTROL_UNINTERPRETABLE_METADATA))
    }

    @Test fun J17_aRestartSnapshotWithAJournalLeft_isClosed_andReadingTwiceChangesNothing() {
        val raw = "A|old-u|k|USER|IDENTITY_SWITCH\n"
        val p = withJournal(F.raw(), raw)
        val first = closed("J17", read(p), setOf(ClosedReason.JOURNAL_PRESENT))
        val second = closed("J17 again", read(p), setOf(ClosedReason.JOURNAL_PRESENT))
        assertEquals(first.journal, second.journal)
        assertEquals(2, entries("J17", first, raw).size)
        assertEquals(raw, p[journalKey])
    }

    @Test fun J18_onlyTheConfirmedSeamReachesTheReader() {
        val sources = File("src/main/java/com/jay/fxi").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("the walk must see the reader", sources.any { it.name == "PurgeControlSnapshotReader.kt" })
        // The confirmed seam is the sole caller: it decodes only the owner's completed transaction snapshot.
        assertEquals(listOf("PurgeControlConfirmedRead.kt"), sources.filter {
            it.name != "PurgeControlSnapshotReader.kt" && it.readText().contains("PurgeControlSnapshotReader")
        }.map { it.name })
    }
}

/** Reads the control record directly, to check a fixture's precondition without the reader under contract. */
private object ControlRecordReaderAccess {
    fun read(p: Preferences) = com.jay.fxi.data.entitlements.control.ControlRecordReader().read(p)
}
