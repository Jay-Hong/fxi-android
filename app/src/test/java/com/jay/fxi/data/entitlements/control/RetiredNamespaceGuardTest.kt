package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_KRX
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.READ_BARRIER
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.spec
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.departed
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.raw
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.before
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.executor
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.context
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.request
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.life
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.target
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.transition
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.command
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.witness
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.read
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.landed
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.decide
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.negative
import com.jay.fxi.data.entitlements.control.RetiredNamespaceFixtures.blob
import org.junit.Assert.*
import org.junit.Test

class RetiredNamespaceGuardTest : RetiredNamespaceOwnerBase() {
    @Test fun G01_kind() = runReleaseTest {
        val s = spec(target = node(ControlObligationFixtures.nullSeal))
        deniedR(s, reason = RejectionReason.InvalidRequest("UnsupportedTargetKind"))
    }
    @Test fun G01_settledInput() = runReleaseTest {
        val s = spec(target = NamespaceSettlementFixtures.withWitness(NamespaceSettlementFixtures.user, witness(spec())))
        deniedR(s, reason = RejectionReason.InvalidRequest("AlreadySettledInput"))
    }
    @Test fun G02_id() {
        val actual = target(spec())
        assertTrue(transition.targetMatches(actual, actual))
        assertFalse(transition.targetMatches(actual.copy(id = "other"), actual))
    }
    @Test fun G02_kind() {
        val actual = target(spec())
        assertTrue(transition.targetMatches(actual, actual))
        assertFalse(transition.targetMatches(actual.copy(kind = SealTargetKind.NULL_NAMESPACE), actual))
    }
    @Test fun G02_owner() {
        val actual = target(spec())
        assertTrue(transition.targetMatches(actual, actual))
        assertFalse(transition.targetMatches(actual.copy(key = actual.key.copy(ownerUid = "B")), actual))
    }
    @Test fun G02_axis() {
        val actual = target(spec())
        assertTrue(transition.targetMatches(actual, actual))
        assertFalse(transition.targetMatches(actual.copy(key = actual.key.copy(axis = PurgeScope.CAPABILITY)), actual))
    }
    @Test fun G02_epoch() {
        val actual = target(spec())
        assertTrue(transition.targetMatches(actual, actual))
        assertFalse(transition.targetMatches(actual.copy(key = actual.key.copy(epoch = "other")), actual))
    }
    @Test fun G01_changedPreimage() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.user.replace("\"u\"", "\"other\"").let { "[$it]" } }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G03_duplicateActiveKey() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[${NamespaceSettlementFixtures.user},${NamespaceSettlementFixtures.user.replace("\"s\"", "\"other\"")}]" }, reason = ConflictReason.AmbiguousSealKey)
    }
    @Test fun G04_currentSameOwner() = runReleaseTest {
        val s = spec(fence = before.copy(userAccessEpoch = "u"))
        deniedR(s, reason = ConflictReason.TargetChanged)
    }
    @Test fun G04_currentDifferentOwner() = runReleaseTest {
        val s = spec(fence = before.copy(ownerUid = "B", userAccessEpoch = "u"), exec = executor.copy(ownerUid = "B"), did = null, demand = null)
        deniedR(s, context = context.copy(ownerUid = "B"), reason = ConflictReason.TargetChanged)
    }
    @Test fun G04_nullEpoch() = runReleaseTest {
        val s = spec(fence = before.copy(userAccessEpoch = null))
        deniedR(s, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_owner() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[intPreferencesKey(OWNER_UID.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_user() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[intPreferencesKey(USER_EPOCH.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_krx() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[intPreferencesKey(KRX_EPOCH.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_premiumMarker() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[intPreferencesKey(MAY_CONTAIN_PREMIUM.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_krxMarker() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[intPreferencesKey(MAY_CONTAIN_KRX.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_teardown() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[intPreferencesKey(TEARDOWN_OWED_FOR.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_journal() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[intPreferencesKey(PURGE_JOURNAL.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_empty_user() = runReleaseTest {
        val s = spec(fence = before.copy(userAccessEpoch = ""))
        deniedR(s, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_empty_krx() = runReleaseTest {
        val s = spec(fence = before.copy(krxCapabilityEpoch = ""))
        deniedR(s, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_fence_owner() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[OWNER_UID] = "changed" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_fence_user() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[USER_EPOCH] = "changed" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_fence_krx() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[KRX_EPOCH] = "changed" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_context_owner() = runReleaseTest {
        deniedR(context = context.copy(ownerUid = "B"), reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_context_binding() = runReleaseTest {
        deniedR(context = context.copy(binding = 10), reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_context_origin() = runReleaseTest {
        deniedR(context = context.copy(originLifetimeId = LifetimeId("other")), reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_missingContext() = runReleaseTest {
        deniedR(context = null, reason = RejectionReason.InvalidRequest("AttemptContextRequired"))
    }
    @Test fun G06_executorFence() = runReleaseTest {
        deniedR(
            spec(exec = executor.copy(ownerUid = "B"), demand = request.copy(ownerUid = "B")),
            context = context.copy(ownerUid = "B"),
            reason = RejectionReason.InvalidRequest("ExecutorFenceMismatch")
        )
    }
    @Test fun G06_invalidExecutor_binding() {
        assertEquals("InvalidExecutor", transition.invalidInput(spec(exec = executor.copy(binding = -1), demand = request.copy(binding = -1))))
    }
    @Test fun G06_invalidExecutor_origin() {
        assertEquals("InvalidExecutor", transition.invalidInput(spec(exec = executor.copy(originLifetimeId = LifetimeId("")), demand = request.copy(raisedAt = EventOrderV1(LifetimeId(""), 23)))))
    }
    @Test fun G17_currentRequestRequired() = runReleaseTest {
        deniedR(spec(did = null, demand = null), reason = RejectionReason.InvalidRequest("DemandRequirementMismatch"))
    }
    @Test fun G17_departedNoRequest() = runReleaseTest {
        val s = spec(fence = before.copy(ownerUid = "B"), exec = executor.copy(ownerUid = "B"), demand = request.copy(ownerUid = "B"))
        deniedR(s, reason = RejectionReason.InvalidRequest("DemandRequirementMismatch"))
    }
    @Test fun G17_request_owner() = runReleaseTest {
        deniedR(spec(demand = request.copy(ownerUid = "B")), reason = RejectionReason.InvalidRequest("DemandScopeMismatch"))
    }
    @Test fun G17_request_binding() = runReleaseTest {
        deniedR(spec(demand = request.copy(binding = 8)), reason = RejectionReason.InvalidRequest("DemandScopeMismatch"))
    }
    @Test fun G17_request_origin() = runReleaseTest {
        deniedR(spec(demand = request.copy(raisedAt = EventOrderV1(LifetimeId("other"), 23))), reason = RejectionReason.InvalidRequest("DemandScopeMismatch"))
    }
    @Test fun G17_negativeOrder() = runReleaseTest {
        assertEquals("InvalidDemand", transition.invalidInput(spec(demand = request.copy(raisedAt = EventOrderV1(life, -1)))))
        deniedR(spec(demand = request.copy(raisedAt = EventOrderV1(life, -1))), reason = RejectionReason.InvalidRequest("InvalidDemand"))
    }
    @Test fun G17_userIntent() = runReleaseTest {
        deniedR(spec(demand = request.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS)), reason = RejectionReason.InvalidRequest("InsufficientIntent"))
    }
    @Test fun G17_capabilityIntent() = runReleaseTest {
        deniedR(spec(target = node(NamespaceSettlementFixtures.krx), demand = request.copy(intent = RefreshIntent.IF_STALE)), reason = RejectionReason.InvalidRequest("InsufficientIntent"))
    }
    @Test fun G24_teardown_owner() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" }, reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_teardown_thirdOwner() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "C" }, reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_signOutOpen() = runReleaseTest {
        deniedR(context = context.copy(signOutOpen = true), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_identityPersistencePending() = runReleaseTest {
        deniedR(context = context.copy(identityPersistencePending = true), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G22_fiveFields() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||USER|future" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun G22_damaged() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "broken" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun G22_scope() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||UNKNOWN" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun G21_owner_empty() = runReleaseTest {
        assertFalse(transition.journalField(""))
        val changed = node(NamespaceSettlementFixtures.user.replace("\"A\"", RetiredNamespaceFixtures.q("")))
        val s = spec(target = changed, did = null, demand = null)
        deniedR(s, reason = RejectionReason.InvalidRequest("UnrepresentableJournalField"))
    }
    @Test fun G21_owner_delimiter() = runReleaseTest {
        assertFalse(transition.journalField("bad|value"))
        val changed = node(NamespaceSettlementFixtures.user.replace("\"A\"", RetiredNamespaceFixtures.q("bad|value")))
        val s = spec(target = changed, did = null, demand = null)
        assertEquals("UnrepresentableJournalField", transition.invalidInput(s))
        deniedR(s, reason = RejectionReason.InvalidRequest("UnrepresentableJournalField"))
    }
    @Test fun G21_owner_newline() = runReleaseTest {
        assertFalse(transition.journalField("bad\nvalue"))
        val changed = node(NamespaceSettlementFixtures.user.replace("\"A\"", RetiredNamespaceFixtures.q("bad\nvalue")))
        val s = spec(target = changed, did = null, demand = null)
        deniedR(s, reason = RejectionReason.InvalidRequest("UnrepresentableJournalField"))
    }
    @Test fun G21_owner_surrogate() = runReleaseTest {
        assertFalse(transition.journalField("bad\uD800value"))
        val changed = node(NamespaceSettlementFixtures.user.replace("\"A\"", RetiredNamespaceFixtures.q("bad\uD800value")))
        val s = spec(target = changed, did = null, demand = null)
        deniedR(s, reason = RejectionReason.InvalidRequest("UnrepresentableJournalField"))
    }
    @Test fun G21_epoch_empty() = runReleaseTest {
        assertFalse(transition.journalField(""))
    }
    @Test fun G21_epoch_delimiter() = runReleaseTest {
        assertFalse(transition.journalField("bad|value"))
        val changed = node(NamespaceSettlementFixtures.user.replace("\"u\"", RetiredNamespaceFixtures.q("bad|value")))
        val s = spec(target = changed)
        assertEquals("UnrepresentableJournalField", transition.invalidInput(s))
        deniedR(s, reason = RejectionReason.InvalidRequest("UnrepresentableJournalField"))
    }
    @Test fun G21_epoch_newline() = runReleaseTest {
        assertFalse(transition.journalField("bad\nvalue"))
        val changed = node(NamespaceSettlementFixtures.user.replace("\"u\"", RetiredNamespaceFixtures.q("bad\nvalue")))
        val s = spec(target = changed)
        deniedR(s, reason = RejectionReason.InvalidRequest("UnrepresentableJournalField"))
    }
    @Test fun G21_epoch_surrogate() = runReleaseTest {
        assertFalse(transition.journalField("bad\uD800value"))
        val changed = node(NamespaceSettlementFixtures.user.replace("\"u\"", RetiredNamespaceFixtures.q("bad\uD800value")))
        val s = spec(target = changed)
        deniedR(s, reason = RejectionReason.InvalidRequest("UnrepresentableJournalField"))
    }
    @Test fun A13_schema1() = runReleaseTest {
        val p = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SCHEMA] = 1; remove(evidenceKey); remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)) }
        deniedR(source = p, reason = RecoveryReason.ControlSchemaMigrationRequired)
    }
    @Test fun A13_opaque_seal() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[${NamespaceSettlementFixtures.user},{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_demand() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_hold() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_recovery() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.RECOVERY] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_evidence() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = "[{}]" }, reason = RecoveryReason.UninterpretableMetadata)
    }
    @Test fun A13_opaque_fence() = runReleaseTest {
        deniedR(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = "[{}]" }, reason = RecoveryReason.UninterpretableMetadata)
    }
    @Test fun G17_collision_SEAL() = runReleaseTest {
        val row = node(NamespaceSettlementFixtures.krx).toPayloadEntry().fields
        val collision = ControlNode.of(kotlinx.serialization.json.JsonObject(row + ("id" to kotlinx.serialization.json.JsonPrimitive("r-demand"))))
        val p = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)] = NamespaceSettlementFixtures.jsonArray(spec().target, collision) }
        deniedR(source = p, reason = ConflictReason.IdCollision)
    }
    @Test fun G17_collision_DEMAND() = runReleaseTest {
        val row = node(ControlObligationFixtures.request).toPayloadEntry().fields
        val collision = ControlNode.of(kotlinx.serialization.json.JsonObject(row + ("id" to kotlinx.serialization.json.JsonPrimitive("r-demand"))))
        val p = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.DEMAND)] = NamespaceSettlementFixtures.jsonArray(collision) }
        deniedR(source = p, reason = ConflictReason.IdCollision)
    }
    @Test fun G17_collision_HOLD() = runReleaseTest {
        val row = node(ControlObligationFixtures.hold).toPayloadEntry().fields
        val collision = ControlNode.of(kotlinx.serialization.json.JsonObject(row + ("id" to kotlinx.serialization.json.JsonPrimitive("r-demand"))))
        val p = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = NamespaceSettlementFixtures.jsonArray(collision) }
        deniedR(source = p, reason = ConflictReason.IdCollision)
    }
    @Test fun G17_collision_RECOVERY_INTENT() = runReleaseTest {
        val row = node(ControlObligationFixtures.recovery).toPayloadEntry().fields
        val collision = ControlNode.of(kotlinx.serialization.json.JsonObject(row + ("id" to kotlinx.serialization.json.JsonPrimitive("r-demand"))))
        val p = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = NamespaceSettlementFixtures.jsonArray(collision) }
        deniedR(source = p, reason = ConflictReason.IdCollision)
    }
}
