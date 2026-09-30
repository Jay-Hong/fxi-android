package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.purge.JournalEntry
import com.jay.fxi.data.entitlements.purge.PurgeCause
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotReader
import com.jay.fxi.data.entitlements.purge.PurgeJournalCodec
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned P2-L contract: 계획서 동결 후 16번's explicit recovery for a record whose seven control keys are all absent
 * (design by Codex in P2L/impl_design_codex.r1.md). Journal lines are compared through the codec's reading so scope
 * order and field spelling stay the codec's; new lines are four-field (the production writer's shape until P3).
 * L07~L10 added after the first battery (reachable boundaries the first rows did not pin).
 * The implementation thread reads but does not edit this file.
 */
class RecoverSchemaAbsentLegacyContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<TerminationFixture>()
    @After fun close() = runReleaseTest { controlTestTimeout("P2-L cleanup", 30_000) { opened.forEach { it.storage.close() } } }
    private fun fixture() = TerminationFixture(folder.root, opened.size).also { opened += it }
    private val both = setOf(PurgeScope.USER, PurgeScope.CAPABILITY)
    /** A missing file reads as an empty record, as the store sees it. */
    private suspend fun diskOrEmpty(f: TerminationFixture) = if (f.file.exists()) f.disk() else emptyPreferences()
    private suspend fun recover(f: TerminationFixture) = controlTestTimeout("recover") { f.store.recoverSchemaAbsentLegacy() }
    private fun owed(p: PendingPurge) = JournalEntry.Owed(p, PurgeCause.UNKNOWN)
    private fun journal(p: Preferences) = PurgeJournalCodec.decodeAll(checkNotNull(p[PURGE_JOURNAL]) { "journal present" })
    private suspend fun legacy(f: TerminationFixture, change: (MutablePreferences) -> Unit) = f.edit { it.clear(); change(it) }
    private fun controlKeys(p: Preferences) = p.asMap().keys.map { it.name }.filter {
        it == ControlRecordKeys.SCHEMA || ControlRecordKeys.required(2).any { k -> ControlRecordKeys.payload(k).name == it } }

    /** schema 2, every required array present and empty, two fresh distinct epochs different from [old]. */
    private fun initialised(id: String, p: Preferences, vararg old: String?) {
        assertEquals("$id: schema 2", 2, p[intPreferencesKey(ControlRecordKeys.SCHEMA)])
        for (k in ControlRecordKeys.required(2)) assertEquals("$id: $k empty", "[]", p[ControlRecordKeys.payload(k)])
        val u = p[USER_EPOCH]; val k = p[KRX_EPOCH]
        assertTrue("$id: fresh epochs, got $u / $k", u != null && k != null && u != k && u !in old && k !in old)
        assertEquals("$id: markers cleared", listOf(false, false), listOf(p[MAY_CONTAIN_PREMIUM] ?: false, p[MAY_CONTAIN_KRX] ?: false))
    }

    @Test fun L01_anEmptyOrMissingRecord_isNeverInitialisedByReading_onlyByTheExplicitCommand() = runReleaseTest {
        val missing = fixture()
        assertTrue("L01: reading a missing file needs recovery", ControlRecordReader().read(diskOrEmpty(missing)) is ControlRecordRead.MigrationOrRecoveryRequired)
        assertTrue("L01: the 1→2 upgrade refuses it", controlTestTimeout("upgrade") { missing.store.upgradeControlSchemaV1ToV2() } is ControlSchemaUpgradeResult.RecoveryRequired)
        assertEquals("L01: nothing written by reading or upgrading", emptyList<String>(), controlKeys(diskOrEmpty(missing)))
        for ((name, f) in listOf("missing" to missing, "empty" to fixture().also { legacy(it) {} })) {
            val r = recover(f)
            assertTrue("L01 $name: Confirmed, got $r", r is ControlSchemaUpgradeResult.Confirmed)
            val p = f.disk()
            initialised("L01 $name", p)
            assertEquals("L01 $name: both axes owed, unknown owner and epochs", listOf(owed(PendingPurge(null, null, null, both))), journal(p))
        }
    }

    @Test fun L02_ownerAndJournalTextAreKept_coveredAxesAreNotRepeated() = runReleaseTest {
        val f = fixture(); val kept = "A|uX||USER\nA|u0||USER"
        legacy(f) { it[OWNER_UID] = "A"; it[USER_EPOCH] = "u0"; it[KRX_EPOCH] = "k0"; it[MAY_CONTAIN_PREMIUM] = true; it[MAY_CONTAIN_KRX] = true
            it[PURGE_JOURNAL] = kept; it[stringPreferencesKey("unrelated")] = "x" }
        assertTrue(recover(f) is ControlSchemaUpgradeResult.Confirmed)
        val p = f.disk()
        initialised("L02", p, "u0", "k0")
        assertEquals("L02: owner kept", "A", p[OWNER_UID])
        assertEquals("L02: unrelated key kept", "x", p[stringPreferencesKey("unrelated")])
        val raw = checkNotNull(p[PURGE_JOURNAL])
        assertTrue("L02: the old journal text is kept verbatim at the front, got $raw", raw.startsWith("$kept\n"))
        assertEquals("L02: only the uncovered axis is added", listOf(owed(PendingPurge("A", "u0", "k0", setOf(PurgeScope.CAPABILITY)))), journal(p).drop(2))
    }

    @Test fun L02b_anAbsentOwnerOrEpochStaysUnknown_neverFilledIn() = runReleaseTest {
        val f = fixture()
        legacy(f) { it[USER_EPOCH] = "u0"; it[KRX_EPOCH] = "k0" }
        assertTrue(recover(f) is ControlSchemaUpgradeResult.Confirmed)
        val p = f.disk()
        initialised("L02b", p, "u0", "k0")
        assertEquals("L02b: no owner invented", null, p[OWNER_UID])
        assertEquals(listOf(owed(PendingPurge(null, "u0", "k0", both))), journal(p))
    }

    @Test fun L03_anythingButAllSevenKeysAbsent_isRefused_andNothingIsWritten() = runReleaseTest {
        val cases = listOf<Pair<String, (MutablePreferences) -> Unit>>(
            "payload without schema" to { it[ControlRecordKeys.payload(ControlKind.DEMAND)] = "[]" },
            "journal of another type" to { it[intPreferencesKey(PURGE_JOURNAL.name)] = 5 },
            "uninterpretable journal line" to { it[PURGE_JOURNAL] = "A|u0||USER|FUTURE" })
        for ((name, seed) in cases) {
            val f = fixture(); legacy(f) { it[OWNER_UID] = "A"; seed(it) }
            val before = f.disk()
            val r = recover(f)
            assertTrue("L03 $name: RecoveryRequired, got $r", r is ControlSchemaUpgradeResult.RecoveryRequired)
            assertEquals("L03 $name: untouched", before, f.disk())
        }
    }

    @Test fun L04_rightAfterRecovery_theCombinedReadStaysClosedOnTheJournal() = runReleaseTest {
        val f = fixture(); legacy(f) { it[OWNER_UID] = "A"; it[USER_EPOCH] = "u0"; it[KRX_EPOCH] = "k0" }
        assertTrue(recover(f) is ControlSchemaUpgradeResult.Confirmed)
        val r = PurgeControlSnapshotReader().read(f.disk())
        assertTrue("L04: Closed with JOURNAL_PRESENT, got $r",
            r is PurgeControlSnapshotRead.Closed && PurgeControlSnapshotRead.ClosedReason.JOURNAL_PRESENT in r.reasons)
    }

    @Test fun L05_aWriteFaultLeavesTheOldState_aRetryLandsTheWholeNewState_andAFurtherRetryOnlyConfirms() = runReleaseTest {
        val f = fixture(); legacy(f) { it[OWNER_UID] = "A"; it[USER_EPOCH] = "u0"; it[KRX_EPOCH] = "k0" }
        val before = f.disk()
        f.storage.storage.before = true
        val failed = try { recover(f) } finally { f.storage.storage.before = false }
        assertTrue("L05: Unconfirmed, got $failed", failed is ControlSchemaUpgradeResult.Unconfirmed)
        assertEquals("L05: the old state stays", before, f.disk())
        assertTrue(recover(f) is ControlSchemaUpgradeResult.Confirmed)
        val landed = f.disk()
        initialised("L05", landed, "u0", "k0")
        assertEquals(listOf(owed(PendingPurge("A", "u0", "k0", both))), journal(landed))
        assertTrue("L05: a further retry only confirms", recover(f) is ControlSchemaUpgradeResult.Confirmed)
        val again = f.disk()
        assertEquals("L05: no new epochs", listOf(landed[USER_EPOCH], landed[KRX_EPOCH]), listOf(again[USER_EPOCH], again[KRX_EPOCH]))
        assertEquals("L05: no new journal line", landed[PURGE_JOURNAL], again[PURGE_JOURNAL])
    }

    @Test fun L07_aNormalSchemaTwoRecord_isNotClaimedAsARecovery_andIsUntouched() = runReleaseTest {
        val f = fixture(); f.edit { it.clear(); it += ControlLifecycleEvidenceFixtures.raw() }
        val before = f.disk()
        val r = recover(f)
        assertTrue("L07: RecoveryRequired(MigrationOrRecovery), got $r",
            r is ControlSchemaUpgradeResult.RecoveryRequired && r.reason == RecoveryReason.MigrationOrRecovery)
        assertEquals("L07: untouched", before, f.disk())
    }

    @Test fun L08_anotherOwnersLine_coversNothingForThisOwner() = runReleaseTest {
        val f = fixture(); val kept = "B|u0|k0|USER,CAPABILITY"
        legacy(f) { it[OWNER_UID] = "A"; it[USER_EPOCH] = "u0"; it[KRX_EPOCH] = "k0"; it[PURGE_JOURNAL] = kept }
        assertTrue(recover(f) is ControlSchemaUpgradeResult.Confirmed)
        assertEquals("L08: both axes still owed for A", listOf(owed(PendingPurge("A", "u0", "k0", both))), journal(f.disk()).drop(1))
    }

    @Test fun L09_aLineWithoutAnEpoch_coversThatAxisForAnyEpochOfTheOwner() = runReleaseTest {
        val f = fixture(); val kept = "A|||USER"
        legacy(f) { it[OWNER_UID] = "A"; it[USER_EPOCH] = "u0"; it[KRX_EPOCH] = "k0"; it[PURGE_JOURNAL] = kept }
        assertTrue(recover(f) is ControlSchemaUpgradeResult.Confirmed)
        assertEquals("L09: only CAPABILITY added", listOf(owed(PendingPurge("A", "u0", "k0", setOf(PurgeScope.CAPABILITY)))), journal(f.disk()).drop(1))
    }

    @Test fun L10_freshEpochsThatRepeatAnOldOrOwedEpoch_areRefused() {
        val fresh = "00000000-0000-0000-0000-000000000abc"
        val legacyRead = { journal: String?, user: String ->
            val p = androidx.datastore.preferences.core.mutablePreferencesOf().apply {
                this[OWNER_UID] = "A"; this[USER_EPOCH] = user; this[KRX_EPOCH] = "k0"; journal?.let { this[PURGE_JOURNAL] = it } }.toPreferences()
            ControlRecordReader().read(p)
        }
        val other = "00000000-0000-0000-0000-000000000def"
        for ((name, read) in listOf("equals the old epoch" to legacyRead(null, fresh), "equals an owed epoch" to legacyRead("A|$fresh||USER", "u0"))) {
            val d = RecoverSchemaAbsentLegacy.decide(read, RecoveryFreshEpochs(fresh, other))
            assertTrue("L10 $name: Observe(UnreadableEpochState), got $d",
                d is com.jay.fxi.data.entitlements.RecordTransactionDecision.Observe && d.value == RecoveryReason.UnreadableEpochState)
        }
    }

    @Test fun L06_onlyTheStoreAndUnconnectedRunnerReachTheRecovery() {
        val sources = File("src/main/java/com/jay/fxi").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("the walk must see the decider", sources.any { it.name == "RecoverSchemaAbsentLegacy.kt" })
        // The runner invokes the store facade; D12 prevents an operational caller.
        assertEquals(emptyList<String>(), sources.filter { it.name !in setOf("RecoverSchemaAbsentLegacy.kt", "ControlRecordStore.kt", "ControlRestartRunner.kt") &&
            (it.readText().contains("RecoverSchemaAbsentLegacy") || it.readText().contains("recoverSchemaAbsentLegacy")) }.map { it.name })
        val store = sources.single { it.name == "ControlRecordStore.kt" }.readText()
        assertEquals("the store only declares the command", 1, Regex("\\brecoverSchemaAbsentLegacy\\b").findAll(store).count())
    }
}
