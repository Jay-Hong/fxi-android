package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.purge.PurgeControlConfirmedRead
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
 * Claude-owned P3-d contract (purger 설계 v3 final §7 재시작 순서, §9 P3; P3/split_codex.r1.md, narrowed by
 * P3d/narrowing_codex.r1.md: the UNCLEAN_RESTART five-field transition moves to P3-i's format switch).
 * The runner reads through P3-a, decides with P2-M, and executes only the three steps it owns every input for —
 * schema-absent legacy recovery, the 1→2 upgrade and previous-lifetime evidence reclamation — re-reading after each
 * confirmed step. At most one owned step runs per call: no owned step leads to another in the P2-M order, so an owned
 * step decided again after a confirmed one is NoProgress (battery r1: a step cap was unobservable). Every other step
 * is a closed handoff with no write; an unconfirmed re-read after a confirmed step is Handoff(ReRead) (battery r2:
 * the separate guard was unobservable). Nothing in production calls it.
 * The implementation reads but does not edit this file.
 */
class ControlRestartRunnerContractTest {
    private val journalKey = stringPreferencesKey("pending_purge_journal")
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private val schemaKey = intPreferencesKey("control_schema")

    private fun <T> withStorage(body: suspend (ControlStoreTestStorage) -> T): T = runBlocking {
        val dir = Files.createTempDirectory("p3d").toFile()
        val s = ControlStoreTestStorage(File(dir, "fxi_access_epoch.preferences_pb"))
        try { body(s) } finally { s.close(); dir.deleteRecursively() }
    }
    private fun runner(s: ControlStoreTestStorage) = ControlRestartRunner(PurgeControlConfirmedRead(s.owner), s.control)
    private suspend fun run(s: ControlStoreTestStorage, id: RestartIdentity = RestartIdentity.SAME_UID) = runner(s).run(id)
    private suspend fun leaveReadBackPending(s: ControlStoreTestStorage) {
        s.storage.before = true
        try { s.owner.markMayContainData(premium = true, krx = false); fail("fixture: the write must fail") } catch (_: IOException) {}
    }
    /** Fixture precondition through the already-landed read seam and model, before the runner under contract. */
    private suspend fun decided(s: ControlStoreTestStorage) = ControlRestartModel.decide(PurgeControlConfirmedRead(s.owner).read(), RestartIdentity.SAME_UID)
    private suspend fun ownerOnly(s: ControlStoreTestStorage) = s.data.edit {
        it[stringPreferencesKey("owner_uid")] = "A"; it[stringPreferencesKey("user_access_epoch")] = "u"
    }

    @Test fun D01_aCleanRecord_handsOffToFreshApproval_withoutAnyWrite() = withStorage { s ->
        s.seed(); val before = s.raw(); val writes = s.storage.writes
        assertEquals("D01", RestartRunResult.Handoff(RestartStep.AwaitFreshApproval), run(s))
        assertEquals("D01: no write", writes, s.storage.writes)
        assertEquals("D01: file unchanged", before, s.raw())
    }

    @Test fun D02_aSchemaAbsentStart_isRecovered_thenHandsOffThePurge() = withStorage { s ->
        ownerOnly(s)
        assertEquals("D02", RestartRunResult.Handoff(RestartStep.Purge), run(s))
        val raw = s.raw()
        assertEquals("D02: schema 2", 2, raw[schemaKey])
        assertTrue("D02: retirement journal", !raw[journalKey].isNullOrEmpty())
    }

    @Test fun D03_schemaOne_isUpgraded_thenDecidedAgain() = withStorage { s ->
        s.seed(schema = 1)
        assertEquals("D03", RestartRunResult.Handoff(RestartStep.AwaitFreshApproval), run(s))
        assertEquals("D03: schema 2", 2, s.raw()[schemaKey])
        s.data.edit { it[schemaKey] = 1; it.remove(evidenceKey); it.remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)); it[journalKey] = "u0|old||USER" }
        assertEquals("D03 journal", RestartRunResult.Handoff(RestartStep.Purge), run(s))
        assertEquals("D03 journal kept", "u0|old||USER", s.raw()[journalKey])
    }

    @Test fun D04_previousLifetimeEvidence_isReclaimed_thenDecidedAgain() = withStorage { s ->
        s.seed(); s.data.edit { it[evidenceKey] = "[${ReclamationFixtures.mutation()}]" }
        assertEquals("D04 fixture", RestartStep.ReclaimPreviousLifetimeEvidence, decided(s))
        assertEquals("D04", RestartRunResult.Handoff(RestartStep.AwaitFreshApproval), run(s))
        assertEquals("D04: evidence reclaimed", "[]", s.raw()[evidenceKey])
    }

    @Test fun D05_currentLifetimeEvidence_isNoProgress_notALoop() = withStorage { s ->
        val current = ControlCommandTracking.forOwner(s.owner).lifetimeId.value
        s.seed(); s.data.edit { it[evidenceKey] = "[${ReclamationFixtures.mutation(lifetime = current)}]" }
        assertEquals("D05 fixture", RestartStep.ReclaimPreviousLifetimeEvidence, decided(s))
        assertEquals("D05", RestartRunResult.NoProgress(RestartStep.ReclaimPreviousLifetimeEvidence), run(s))
        assertEquals("D05: evidence kept", "[${ReclamationFixtures.mutation(lifetime = current)}]", s.raw()[evidenceKey])
    }

    @Test fun D06_aStoreRefusal_stopsWithoutWriting() = withStorage { s ->
        s.seed(); s.data.edit { it[evidenceKey] = "[${ReclamationFixtures.rotation(ids = listOf("missing"))}]" }
        assertEquals("D06 fixture", RestartStep.ReclaimPreviousLifetimeEvidence, decided(s))
        val before = s.raw()
        assertEquals("D06", RestartRunResult.StepRefused(RestartStep.ReclaimPreviousLifetimeEvidence), run(s))
        assertEquals("D06: file unchanged", before, s.raw())
    }

    @Test fun D07_stepsNeedingOutsideInputs_areHandedOff_withoutAnyWrite() = withStorage { s ->
        val O = ControlObligationFixtures
        val cases = listOf(
            Triple("intent", suspend { s.seed(recovery = "[${O.recovery}]") }, RestartStep.RecoverIntent as RestartStep),
            Triple("seal", suspend { s.seed(seal = "[${O.seal}]") }, RestartStep.SettleSeal),
            Triple("hold", suspend { s.seed(hold = "[${O.hold}]") }, RestartStep.RecoverHold),
            Triple("journal", suspend { s.seed(); s.data.edit { it[journalKey] = "u0|old||USER" } }, RestartStep.Purge),
            Triple("blocked", suspend { s.seed(); s.data.edit { it[schemaKey] = 9 } }, RestartStep.Blocked(setOf(ClosedReason.CONTROL_UNREADABLE)))
        )
        for ((name, arrange, step) in cases) {
            s.data.edit { it.clear() }; arrange()
            val before = s.raw(); val writes = s.storage.writes
            assertEquals("D07 $name", RestartRunResult.Handoff(step), run(s))
            assertEquals("D07 $name: no write", writes, s.storage.writes)
            assertEquals("D07 $name: file unchanged", before, s.raw())
        }
    }

    @Test fun D08_aChangedIdentity_isHandedOff_beforeAnyRecovery() = withStorage { s ->
        ownerOnly(s); val before = s.raw()
        assertEquals("D08 changed", RestartRunResult.Handoff(RestartStep.IdentityWithLegacyRecovery(RestartIdentity.UID_CHANGED)), run(s, RestartIdentity.UID_CHANGED))
        assertEquals("D08 deletion", RestartRunResult.Handoff(RestartStep.IdentityFirst(RestartIdentity.DELETION_PENDING)), run(s, RestartIdentity.DELETION_PENDING))
        assertEquals("D08: file unchanged", before, s.raw())
    }

    @Test fun D09_anUnconfirmedRead_runsNothing() = withStorage { s ->
        ownerOnly(s); leaveReadBackPending(s)
        s.storage.before = true
        assertEquals("D09", RestartRunResult.Unconfirmed, run(s))
        assertFalse("D09: no recovery", s.raw().asMap().keys.any { it.name == "control_schema" })
    }

    @Test fun D10_anUnconfirmedStep_stops_andTheNextRunFinishesIt() = withStorage { s ->
        ownerOnly(s)
        s.storage.before = true
        assertEquals("D10", RestartRunResult.Unconfirmed, run(s))
        assertFalse("D10: nothing landed", s.raw().asMap().keys.any { it.name == "control_schema" })
        assertEquals("D10 retry", RestartRunResult.Handoff(RestartStep.Purge), run(s))
    }

    // Battery r1 RM7: a legacy refusal must stop as a refusal, not be handed off or retried.
    @Test fun D11_aLegacyRefusal_stopsWithoutWriting() = withStorage { s ->
        s.data.edit { it[stringPreferencesKey("owner_uid")] = "a|b" }
        assertEquals("D11 fixture", RestartStep.LegacySchemaAbsentRecovery, decided(s))
        val before = s.raw()
        assertEquals("D11", RestartRunResult.StepRefused(RestartStep.LegacySchemaAbsentRecovery), run(s))
        assertEquals("D11: file unchanged", before, s.raw())
    }

    // Battery r1 RM10: an unconfirmed reclamation stops; it is not re-read into NoProgress.
    @Test fun D13_anUnconfirmedReclamation_stops_andTheNextRunFinishesIt() = withStorage { s ->
        s.seed(); s.data.edit { it[evidenceKey] = "[${ReclamationFixtures.mutation()}]" }
        s.storage.before = true
        assertEquals("D13", RestartRunResult.Unconfirmed, run(s))
        assertEquals("D13: evidence kept", "[${ReclamationFixtures.mutation()}]", s.raw()[evidenceKey])
        assertEquals("D13 retry", RestartRunResult.Handoff(RestartStep.AwaitFreshApproval), run(s))
    }

    // Battery r2 RN6: an unconfirmed 1→2 upgrade stops; the next run finishes it.
    @Test fun D14_anUnconfirmedUpgrade_stops_andTheNextRunFinishesIt() = withStorage { s ->
        s.seed(schema = 1)
        s.storage.before = true
        assertEquals("D14", RestartRunResult.Unconfirmed, run(s))
        assertEquals("D14: still schema 1", 1, s.raw()[schemaKey])
        assertEquals("D14 retry", RestartRunResult.Handoff(RestartStep.AwaitFreshApproval), run(s))
    }

    @Test fun D12_lock_noProductionCaller() {
        val callers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ControlRestartRunner.kt" }
            .filter { it.readText().contains("ControlRestartRunner") }.map { it.name }.toList()
        assertEquals("D12", emptyList<String>(), callers)
    }
}
