package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotRead.ClosedReason
import com.jay.fxi.data.entitlements.purge.PurgeControlSnapshotReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned P2-M contract (purger 설계 v3 final §7 재시작 순서·§9 P2; Codex design P2M/design_codex.r1.md, narrowed
 * by Claude to the decision order). The model only picks the next piece of work from one confirmed snapshot and the
 * first identity observation. The effects of each step are the existing transitions' contracts (C3, P2-L, 1→2
 * upgrade, evidence reclamation); partial purge and failed journal settlement after restart are P3 rows (§9 P3).
 * Order: confirmed read → damage → identity → schema-absent legacy → schema 1 → previous-lifetime MUTATIONS/ROTATION
 * evidence (미확정 편집; lifecycle/settlement rows are reclaimed by P3's closure owner) → prior intent → seal → hold → purge journal → await fresh approval. Demand never blocks the
 * protected path (control queries follow their own AUTH·floor rules). The implementation reads but does not edit
 * this file.
 */
class ControlRestartModelContractTest {
    private val F = ControlLifecycleEvidenceFixtures
    private val O = ControlObligationFixtures
    // The access-epoch store's journal key, written out (see PurgeControlSnapshotReader).
    private val journalKey = stringPreferencesKey("pending_purge_journal")
    private val identities = RestartIdentity.entries
    private val changed = listOf(RestartIdentity.NO_UID, RestartIdentity.UID_CHANGED)

    private fun snap(p: Preferences) = PurgeControlSnapshotReader().read(p)
    private fun decide(p: Preferences, id: RestartIdentity = RestartIdentity.SAME_UID) =
        ControlRestartModel.decide(RestartRead.Confirmed(snap(p)), id)
    private fun set(base: Preferences, kind: ControlKind, json: String) =
        base.toMutablePreferences().apply { this[ControlRecordKeys.payload(kind)] = "[$json]" }.toPreferences()
    private fun journal(base: Preferences, raw: String) = base.toMutablePreferences().apply { this[journalKey] = raw }.toPreferences()
    private fun blocked(vararg r: ClosedReason) = RestartStep.Blocked(r.toSet())
    private val v2 = F.raw()
    private val evidence = F.raw(evidence = "[${F.wire()}]")
    private val sevenAbsentWithOwner = mutablePreferencesOf().apply {
        this[stringPreferencesKey("owner_uid")] = "A"; this[stringPreferencesKey("user_access_epoch")] = "u"
    }.toPreferences()

    @Test fun M01_anUnconfirmedRead_decidesNothing_forEveryIdentity() {
        for (id in identities) assertEquals("M01 $id", RestartStep.ReRead, ControlRestartModel.decide(RestartRead.Unconfirmed, id))
    }

    @Test fun M02_anUnreadableRecord_isBlocked_beforeAnyIdentityWork() {
        val future = v2.toMutablePreferences().apply { this[intPreferencesKey(ControlRecordKeys.SCHEMA)] = 9 }.toPreferences()
        assertTrue("M02 fixture", snap(future).control is ControlRecordRead.Unreadable)
        for (id in identities) assertEquals("M02 $id", blocked(ClosedReason.CONTROL_UNREADABLE), decide(future, id))
    }

    @Test fun M03_anUninterpretableObligationOrMetadata_isBlocked_forEveryIdentity() {
        val obligation = set(v2, ControlKind.DEMAND, """{"id":"x","kind":"FUTURE"}""")
        val metadata = v2.toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = """[{"x":1}]""" }.toPreferences()
        for (id in identities) {
            assertEquals("M03 obligation $id", blocked(ClosedReason.CONTROL_UNINTERPRETABLE_OBLIGATION), decide(obligation, id))
            assertEquals("M03 metadata $id", blocked(ClosedReason.CONTROL_UNINTERPRETABLE_METADATA), decide(metadata, id))
        }
    }

    @Test fun M04_aWrongTypeOrUnreadableJournal_isBlocked_evenOnASchemaAbsentStart() {
        val wrong = { base: Preferences -> base.toMutablePreferences().apply { this[intPreferencesKey("pending_purge_journal")] = 1 }.toPreferences() }
        val bad = "u1|a||USER|FUTURE"
        for (base in listOf(v2, emptyPreferences(), sevenAbsentWithOwner)) for (id in identities) {
            assertEquals("M04 wrong $id", blocked(ClosedReason.JOURNAL_WRONG_TYPE), decide(wrong(base), id))
            assertEquals("M04 unreadable $id", blocked(ClosedReason.JOURNAL_UNINTERPRETABLE), decide(journal(base, bad), id))
        }
    }

    @Test fun M05_everyBlockingReasonFoundIsKept_andNonBlockingOnesAreNot() {
        val both = journal(set(F.raw(schema = 1), ControlKind.DEMAND, """{"id":"x","kind":"FUTURE"}"""), "u1|a||USER|FUTURE")
        assertEquals("M05", blocked(ClosedReason.CONTROL_UNINTERPRETABLE_OBLIGATION, ClosedReason.JOURNAL_UNINTERPRETABLE), decide(both))
    }

    @Test fun M06_aSchemaAbsentStart_withAChangedOrMissingUid_isOneCombinedRetirement() {
        for (base in listOf(emptyPreferences(), sevenAbsentWithOwner, journal(sevenAbsentWithOwner, "u0|old||USER|SIGN_OUT"))) {
            assertTrue("M06 fixture", snap(base).control is ControlRecordRead.MigrationOrRecoveryRequired)
            for (id in changed) assertEquals("M06 $id", RestartStep.IdentityWithLegacyRecovery(id), decide(base, id))
            assertEquals("M06 deletion", RestartStep.IdentityFirst(RestartIdentity.DELETION_PENDING), decide(base, RestartIdentity.DELETION_PENDING))
            assertEquals("M06 same", RestartStep.LegacySchemaAbsentRecovery, decide(base))
        }
    }

    @Test fun M07_aChangedIdentity_comesBeforeEveryReadableObligation() {
        val loaded = journal(set(set(evidence, ControlKind.SEAL, O.seal), ControlKind.RECOVERY_INTENT, O.recovery), "u0|old||USER")
        for (base in listOf(v2, loaded, F.raw(schema = 1))) for (id in identities - RestartIdentity.SAME_UID) {
            assertEquals("M07 $id", RestartStep.IdentityFirst(id), decide(base, id))
        }
    }

    @Test fun M08_schemaOne_isUpgradedFirst_whateverItOwes() {
        val owing = journal(set(F.raw(schema = 1), ControlKind.SEAL, O.seal), "u0|old||USER")
        for (base in listOf(F.raw(schema = 1), owing)) assertEquals("M08", RestartStep.SchemaUpgradeV1ToV2, decide(base))
    }

    @Test fun M09_mutationOrRotationEvidence_isReclaimedBeforeAnyObligation() {
        val mutation = F.raw(evidence = "[${ReclamationFixtures.mutation()}]")
        val rotation = ReclamationFixtures.raw()
        for (base in listOf(mutation, rotation)) {
            val m = (snap(base).control as ControlRecordRead.Supported).metadata as ControlMetadataRead.V2
            assertTrue("M09 fixture", m.evidence.entries.isNotEmpty() && !m.evidence.hasUninterpretable)
        }
        val loaded = journal(set(set(set(mutation, ControlKind.RECOVERY_INTENT, O.recovery), ControlKind.SEAL, O.seal), ControlKind.HOLD, O.hold), "u0|old||USER")
        for (base in listOf(mutation, rotation, loaded)) assertEquals("M09", RestartStep.ReclaimPreviousLifetimeEvidence, decide(base))
    }

    // ReclaimPreviousLifetimeEvidence reclaims only MUTATIONS and ROTATION rows; lifecycle and settlement rows need a
    // closure the model cannot see, so choosing reclamation for them would pick the same step forever (Codex r1 REVISE).
    @Test fun M09b_lifecycleEvidenceAlone_doesNotStopTheOrder() {
        val m = (snap(evidence).control as ControlRecordRead.Supported).metadata as ControlMetadataRead.V2
        assertTrue("M09b fixture", m.evidence.entries.isNotEmpty() && !m.evidence.hasUninterpretable)
        assertEquals("M09b alone", RestartStep.AwaitFreshApproval, decide(evidence))
        assertEquals("M09b hold", RestartStep.RecoverHold, decide(set(evidence, ControlKind.HOLD, O.hold)))
        assertEquals("M09b journal", RestartStep.Purge, decide(journal(evidence, "u0|old||USER")))
    }

    @Test fun M10_M11_M12_intentThenSealThenHold() {
        val hold = journal(set(v2, ControlKind.HOLD, O.hold), "u0|old||USER")
        val seal = set(hold, ControlKind.SEAL, O.seal)
        val intent = set(seal, ControlKind.RECOVERY_INTENT, O.recovery)
        assertEquals("M10", RestartStep.RecoverIntent, decide(intent))
        assertEquals("M10 alone", RestartStep.RecoverIntent, decide(set(v2, ControlKind.RECOVERY_INTENT, O.recovery)))
        assertEquals("M11", RestartStep.SettleSeal, decide(seal))
        assertEquals("M11 settled", RestartStep.SettleSeal, decide(set(v2, ControlKind.SEAL, O.settledSeal)))
        assertEquals("M12", RestartStep.RecoverHold, decide(hold))
        assertEquals("M12 topic", RestartStep.RecoverHold, decide(set(v2, ControlKind.HOLD, O.topicHold)))
    }

    @Test fun M13_aReadableJournal_isPurged_andDemandDoesNotBlockIt() {
        for (raw in listOf("", "u0|old||USER", "u0|old||USER|SIGN_OUT\nu1|a|b|CAPABILITY")) {
            assertEquals("M13 '$raw'", RestartStep.Purge, decide(journal(v2, raw)))
            assertEquals("M13 demand '$raw'", RestartStep.Purge, decide(journal(set(v2, ControlKind.DEMAND, O.request), raw)))
        }
    }

    @Test fun M14_nothingOwedButDemand_awaitsFreshApproval_neverOpens() {
        assertEquals("M14 empty", RestartStep.AwaitFreshApproval, decide(v2))
        assertEquals("M14 request", RestartStep.AwaitFreshApproval, decide(set(v2, ControlKind.DEMAND, O.request)))
        assertEquals("M14 guard", RestartStep.AwaitFreshApproval, decide(set(v2, ControlKind.DEMAND, O.guard)))
    }

    @Test fun M15_theStepVocabularyHasNoOpening() {
        val names = RestartStep::class.java.declaredClasses.filter { RestartStep::class.java.isAssignableFrom(it) }.map { it.simpleName }.toSet()
        assertEquals("M15", setOf("ReRead", "Blocked", "IdentityFirst", "IdentityWithLegacyRecovery", "LegacySchemaAbsentRecovery",
            "SchemaUpgradeV1ToV2", "ReclaimPreviousLifetimeEvidence", "RecoverIntent", "SettleSeal", "RecoverHold", "Purge",
            "AwaitFreshApproval"), names)
    }

    @Test fun M16_lock_onlyTheUnconnectedRunnerCallsTheModel() {
        val main = File("src/main/java")
        val callers = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ControlRestartModel.kt" }
            .filter { it.readText().contains("ControlRestartModel") }.map { it.name }.toList()
        // P3-d's runner is the sole allowed caller; D12 keeps production from reaching it.
        assertEquals("M16", listOf("ControlRestartRunner.kt"), callers)
    }
}
