package com.jay.fxi.data.entitlements.purge

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.control.ControlRecordRead
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage
import com.jay.fxi.data.entitlements.control.RestartRead
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead.ClosedReason
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Claude-owned P3-a contract (purger 설계 v3 final §7 "저장 확인을 수반하는 읽기", §9 P3; split P3/split_codex.r1.md).
 * The seam reads the control record and the purge journal through the single owner's transaction with an unchanged
 * Confirm candidate, so a pending read-back barrier is confirmed in the same update, and publishes only the snapshot
 * the owner returned. The result is the P2-M [RestartRead]; it never opens anything. A storage IOException is
 * [RestartRead.Unconfirmed]; any other failure is not swallowed. Nothing in production calls the seam (P3-i wires it).
 * The implementation reads but does not edit this file.
 */
class PurgeControlConfirmedReadContractTest {
    private val journalKey = stringPreferencesKey("pending_purge_journal")
    private val barrierKey = longPreferencesKey("read_barrier")

    private fun <T> withStorage(body: suspend (ControlStoreTestStorage) -> T): T = runBlocking {
        val dir = Files.createTempDirectory("p3a").toFile()
        val s = ControlStoreTestStorage(File(dir, "fxi_access_epoch.preferences_pb"))
        try { body(s) } finally { s.close(); dir.deleteRecursively() }
    }
    private fun seam(s: ControlStoreTestStorage) = PurgeControlConfirmedRead(s.owner)
    private fun confirmed(id: String, r: RestartRead): PurgeControlSnapshotRead {
        assertTrue("$id: Confirmed, got $r", r is RestartRead.Confirmed)
        return (r as RestartRead.Confirmed).snapshot
    }
    /** A write that does not return normally leaves the owner's read-back obligation. */
    private suspend fun leaveReadBackPending(s: ControlStoreTestStorage) {
        s.storage.before = true
        try { s.owner.markMayContainData(premium = true, krx = false); fail("fixture: the write must fail") } catch (_: IOException) {}
    }

    @Test fun A01_aCleanRecord_isConfirmedPendingAdmission_withoutAnyWrite() = withStorage { s ->
        s.seed(); val before = s.raw(); val writes = s.storage.writes
        val snap = confirmed("A01", seam(s).read())
        assertTrue("A01: PendingRuntimeAdmission, got $snap", snap is PurgeControlSnapshotRead.PendingRuntimeAdmission)
        assertEquals("A01: no write", writes, s.storage.writes)
        assertEquals("A01: file unchanged", before, s.raw())
    }

    @Test fun A02_aJournalIsReadFromTheSameSnapshot_andKeptVerbatim() = withStorage { s ->
        s.seed(); val raw = "u0|old||USER|SIGN_OUT\nu1|a||USER"
        s.data.edit { it[journalKey] = raw }
        val snap = confirmed("A02", seam(s).read())
        assertTrue("A02: Closed, got $snap", snap is PurgeControlSnapshotRead.Closed)
        assertEquals("A02", setOf(ClosedReason.JOURNAL_PRESENT), (snap as PurgeControlSnapshotRead.Closed).reasons)
        assertEquals("A02: raw kept", raw, (snap.journal as PurgeControlSnapshotRead.Journal.Present).raw)
        assertEquals("A02: file kept", raw, s.raw()[journalKey])
    }

    @Test fun A03_aSchemaAbsentStart_staysMigrationOrRecovery_andIsNotFilledIn() = withStorage { s ->
        s.data.edit { it[stringPreferencesKey("owner_uid")] = "A"; it[stringPreferencesKey("user_access_epoch")] = "u" }
        val snap = confirmed("A03", seam(s).read())
        assertTrue("A03: MigrationOrRecoveryRequired, got ${snap.control}", snap.control is ControlRecordRead.MigrationOrRecoveryRequired)
        assertFalse("A03: no schema written", s.raw().asMap().keys.any { it.name == "control_schema" })
    }

    @Test fun A04_aPendingReadBack_isConfirmedInTheSameUpdate_andOnlyThatSnapshotIsPublished() = withStorage { s ->
        s.seed(); leaveReadBackPending(s)
        val barrier = s.raw()[barrierKey] ?: 0L; val writes = s.storage.writes
        val snap = confirmed("A04", seam(s).read())
        assertEquals("A04: one barrier write", writes + 1, s.storage.writes)
        assertEquals("A04: barrier advanced", barrier + 1L, s.raw()[barrierKey])
        assertEquals("A04: published snapshot carries the barrier", barrier + 1L, snap.control.original[barrierKey])
        val again = s.storage.writes
        confirmed("A04 again", seam(s).read())
        assertEquals("A04: confirmed once, no second barrier", again, s.storage.writes)
    }

    @Test fun A05_aFailedBarrierWrite_isUnconfirmed_andTheObligationStays() = withStorage { s ->
        s.seed(); leaveReadBackPending(s)
        val barrier = s.raw()[barrierKey] ?: 0L
        s.storage.before = true
        assertEquals("A05", RestartRead.Unconfirmed, seam(s).read())
        val writes = s.storage.writes
        confirmed("A05 retry", seam(s).read())
        assertEquals("A05: the retry still writes the barrier", writes + 1, s.storage.writes)
        assertEquals("A05: barrier advanced once", barrier + 1L, s.raw()[barrierKey])
    }

    @Test fun A06_aNonStorageFailure_isNotSwallowed() = withStorage { s ->
        s.seed(); leaveReadBackPending(s)
        s.storage.unexpected = true
        try { seam(s).read(); fail("A06: must throw") } catch (e: IllegalStateException) {
            assertEquals("A06", "unexpected implementation failure", e.message)
        }
    }

    @Test fun A07_lock_onlyTheUnconnectedRunnerCallsTheSeam() {
        val callers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "PurgeControlConfirmedRead.kt" }
            .filter { it.readText().contains("PurgeControlConfirmedRead") }.map { it.name }.toList()
        // P3-d's runner is the sole allowed caller; its D12 keeps production from reaching it.
        assertEquals("A07", listOf("ControlRestartRunner.kt"), callers)
    }

}
