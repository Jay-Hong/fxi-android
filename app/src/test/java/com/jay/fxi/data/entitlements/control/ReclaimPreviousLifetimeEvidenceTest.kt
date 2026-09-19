package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlStoreTestStorage.Companion.SEAL
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.evidenceKey
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.fenceKey
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.mutation
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.raw
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.rotation
import com.jay.fxi.data.entitlements.control.ReclamationFixtures.settled
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Every negative subcondition is a separately invoked JUnit method. */
class ReclaimPreviousLifetimeEvidenceTest {
    private val lifetime = OwnerTrackingLifetimeId.issue()
    private fun decide(raw: Preferences) = ReclaimPreviousLifetimeEvidence.decide(
        ControlRecordReader().read(raw), lifetime, ControlPayloadCodec())

    private fun confirmed(raw: Preferences): Preferences {
        val decision = decide(raw)
        assertTrue("$decision", decision is RecordTransactionDecision.Confirm)
        assertNull(decision.value)
        return (decision as RecordTransactionDecision.Confirm).candidate
    }

    private fun refused(raw: Preferences, reason: RecoveryReason = RecoveryReason.InconsistentReclamation) {
        val before = raw.toPreferences()
        val decision = decide(raw)
        assertTrue("must not request storage confirmation: $decision", decision is RecordTransactionDecision.Observe)
        assertTrue("${decision.value}", decision.value is ControlEvidenceReclamationResult.RecoveryRequired)
        val result = decision.value as ControlEvidenceReclamationResult.RecoveryRequired
        assertEquals(reason, result.reason)
        assertEquals(before, result.observation.original)
        assertEquals(before, raw)
    }

    @Test fun ordinaryRowRemovesOnlyEvidenceEvenWhenTargetIsMissing() {
        val source = raw(evidence = "[${mutation(id = "absent")}]", seals = " [ ] ")
        assertEquals(source.toMutablePreferences().apply { this[evidenceKey] = "[]" }, confirmed(source))
    }

    @Test fun everyPreviousLifetimeIsSelectedTogether() {
        val source = raw(evidence = "[${mutation()},${mutation("m2", ReclamationFixtures.otherLife)}]")
        assertEquals(source.toMutablePreferences().apply { this[evidenceKey] = "[]" }, confirmed(source))
    }

    @Test fun singleAxisRotationRemovesEvidenceAndSealInOneCandidate() {
        val source = raw()
        val expected = source.toMutablePreferences().apply { this[evidenceKey] = "[]"; this[SEAL] = "[]" }
        assertEquals(expected, confirmed(source))
    }

    @Test fun twoAxisRotationRemovesTheCompleteUnorderedSealSet() {
        val source = raw(seals = "[${settled()},${settled("c", PurgeScope.CAPABILITY)}]",
            evidence = "[${rotation(ids = listOf("c", "s"))},${mutation()}]")
        assertEquals(source.toMutablePreferences().apply { this[evidenceKey] = "[]"; this[SEAL] = "[]" }, confirmed(source))
    }

    @Test fun missingSealRefusesWholeCandidate() = refused(raw(seals = "[]"))
    @Test fun oneOfTwoSealsMissingRefusesWholeCandidate() = refused(raw(evidence = "[${rotation(ids = listOf("s", "c"))}]"))
    @Test fun targetInAnotherObligationArrayIsNotASeal() = refused(raw(evidence = "[${rotation(ids = listOf("d"))}]"))
    @Test fun unsettledNamespaceRefusesWholeCandidate() = refused(raw(seals = "[${NamespaceSettlementFixtures.user}]"))
    @Test fun settledNullNamespaceRefusesWholeCandidate() = refused(raw(seals = "[${ControlObligationFixtures.settledSeal}]"))
    @Test fun wrongOperationLinkRefusesWholeCandidate() = refused(raw(seals = "[${settled(operation = "wrong")}]"))
    @Test fun extraSealForOperationRefusesWholeCandidate() = refused(raw(seals = "[${settled()},${settled("extra")}]"))

    @Test fun laterInconsistentRotationAlsoPreservesEarlierValidAndOrdinaryRows() = refused(raw(
        evidence = "[${mutation()},${rotation()},${rotation(command = "bad", ids = listOf("missing"))}]"))

    @Test fun currentLifetimeRowsSurviveWithoutValidatingTheirOldLinks() {
        val current = rotation(command = "current", lifetime = lifetime.value, ids = listOf("missing"))
        val source = raw(evidence = "[${mutation()},$current]")
        assertEquals(source.toMutablePreferences().apply { this[evidenceKey] = "[$current]" }, confirmed(source))
    }

    @Test fun noPreviousRowsConfirmOriginalWithoutNormalizationOrTombstone() {
        val source = raw(evidence = " [ ${mutation(lifetime = lifetime.value)} ] ", seals = " [ ] ")
        val read = ControlRecordReader().read(source)
        val decision = ReclaimPreviousLifetimeEvidence.decide(read, lifetime, ControlPayloadCodec())
        assertSame(read.original, (decision as RecordTransactionDecision.Confirm).candidate)
        assertEquals(source, confirmed(source))
    }

    private fun preservesSeal(sibling: String) {
        val source = raw(seals = "[${settled()},$sibling]")
        val candidate = confirmed(source)
        assertEquals(source.toMutablePreferences().apply { this[evidenceKey] = "[]"; this[SEAL] = "[$sibling]" }, candidate)
    }

    @Test fun legacySettledSealWithoutAppliedSurvives() = preservesSeal(settled("legacy", operation = "legacy-op"))
    @Test fun unrelatedUnsettledSealSurvives() = preservesSeal(NamespaceSettlementFixtures.krx)
    @Test fun unrelatedNullNamespaceSurvives() = preservesSeal(ControlObligationFixtures.nullSeal.replace("\"s\"", "\"null\""))
    @Test fun unrelatedSettledNullNamespaceSurvives() = preservesSeal(ControlObligationFixtures.settledSeal
        .replace("\"s\"", "\"null\"").replace("\"op\"", "\"legacy-null-op\""))

    @Test fun absentDemandAndConsumedJournalDoNotPreventReclamation() {
        val source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[]" }
        assertEquals(source.toMutablePreferences().apply { this[evidenceKey] = "[]"; this[SEAL] = "[]" }, confirmed(source))
    }

    private fun preservesKey(key: Preferences.Key<*>) {
        val source = raw().toMutablePreferences().apply {
            this[DataStoreAccessEpochStore.PURGE_JOURNAL] = "journal-verbatim"
            this[DataStoreAccessEpochStore.TEARDOWN_OWED_FOR] = "old-owner"
            this[fenceKey] = " [ ] "
        }
        assertNotNull(source.asMap()[key])
        val result = confirmed(source)
        assertEquals(source.asMap()[key], result.asMap()[key])
        assertEquals("[]", result[evidenceKey]); assertEquals("[]", result[SEAL])
    }

    @Test fun demandPayloadIncludingAuthAndFloorSurvivesVerbatim() = preservesKey(ControlStoreTestStorage.DEMAND)
    @Test fun holdPayloadSurvivesVerbatim() = preservesKey(ControlStoreTestStorage.HOLD)
    @Test fun recoveryIntentPayloadSurvivesVerbatim() = preservesKey(ControlStoreTestStorage.RECOVERY)
    @Test fun scopeFenceSurvivesVerbatim() = preservesKey(fenceKey)
    @Test fun ownerUidSurvives() = preservesKey(DataStoreAccessEpochStore.OWNER_UID)
    @Test fun userEpochSurvives() = preservesKey(DataStoreAccessEpochStore.USER_EPOCH)
    @Test fun capabilityEpochSurvives() = preservesKey(DataStoreAccessEpochStore.KRX_EPOCH)
    @Test fun premiumMarkerSurvives() = preservesKey(DataStoreAccessEpochStore.MAY_CONTAIN_PREMIUM)
    @Test fun capabilityMarkerSurvives() = preservesKey(DataStoreAccessEpochStore.MAY_CONTAIN_KRX)
    @Test fun journalSurvivesVerbatim() = preservesKey(DataStoreAccessEpochStore.PURGE_JOURNAL)
    @Test fun teardownSurvives() = preservesKey(DataStoreAccessEpochStore.TEARDOWN_OWED_FOR)
    @Test fun externalKeySurvives() = preservesKey(ControlStoreTestStorage.EXTRA)

    @Test fun opaqueEvidenceWithReadablePreviousLifetimeBlocksAllRemoval() = refused(raw(
        evidence = "[${mutation()},${rotation().dropLast(1)},\"future\":true}]"), RecoveryReason.UninterpretableMetadata)
    @Test fun opaqueEvidenceWithCurrentLifetimeStillBlocksAllRemoval() = refused(raw(
        evidence = "[${mutation()},${rotation(lifetime = lifetime.value).dropLast(1)},\"future\":true}]"), RecoveryReason.UninterpretableMetadata)
    @Test fun nonObjectEvidenceBlocksAllRemoval() = refused(raw(evidence = "[${mutation()},null]"), RecoveryReason.UninterpretableMetadata)
    @Test fun duplicateEvidenceIdsBlockAllRemoval() = refused(raw(evidence = "[${rotation()},${rotation()}]"), RecoveryReason.UninterpretableMetadata)
    @Test fun opaqueFenceBlocksAllRemoval() = refused(raw().toMutablePreferences().apply { this[fenceKey] = "[{\"owner\":\"other\"}]" },
        RecoveryReason.UninterpretableMetadata)

    private fun opaque(kind: ControlKind) = refused(raw().toMutablePreferences().apply {
        this[ControlRecordKeys.payload(kind)] = "[{\"id\":\"future\",\"ownerUid\":\"unrelated\",\"kind\":\"FUTURE\"}]"
    }, RecoveryReason.UninterpretableObligations)

    @Test fun opaqueSealBlocksAllRemoval() = opaque(ControlKind.SEAL)
    @Test fun opaqueDemandBlocksAllRemoval() = opaque(ControlKind.DEMAND)
    @Test fun opaqueHoldBlocksAllRemoval() = opaque(ControlKind.HOLD)
    @Test fun opaqueRecoveryIntentBlocksAllRemoval() = opaque(ControlKind.RECOVERY_INTENT)
    @Test fun opaqueObligationStillBlocksWhenNoPreviousRowsExist() = refused(raw(evidence = "[]").toMutablePreferences().apply {
        this[ControlStoreTestStorage.HOLD] = "[null]"
    }, RecoveryReason.UninterpretableObligations)
    @Test fun duplicateObligationIdsBlockAllRemoval() = refused(raw(seals = "[${settled()},${settled()}]"),
        RecoveryReason.UninterpretableObligations)

    @Test fun actualSnapshotObservationPrecedesManagementDecisionInsideOwnerCallback() {
        val root = SealSourceTripwire.sourceRoot(File(checkNotNull(System.getProperty("user.dir"))), System.getProperty("fxi.seal.sourceRoot"))
        val source = File(root, "main/java/com/jay/fxi/data/entitlements/control/ControlRecordStore.kt").readText()
        val method = source.substringAfter("suspend fun reclaimPreviousLifetimeEvidence()").substringBefore("private suspend fun run(")
        val expected = """
            val transaction = owner.transactRecord { snapshot ->
                val read = reader.read(snapshot)
                tracking.observe(read)
                observation = read
                ReclaimPreviousLifetimeEvidence.decide(read, tracking.lifetimeId, codec)
            }
        """.trimIndent().lines().map(String::trim)
        assertEquals(1, method.lines().map(String::trim).windowed(expected.size).count { it == expected })
        assertEquals(1, Regex("tracking\\.observe\\(").findAll(method).count())
        assertFalse(method.contains("bindFirstConfirm"))
    }
}
