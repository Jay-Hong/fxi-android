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
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.spec
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.raw
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.before
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.executor
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.context
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.request
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.life
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.target
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.transition
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.command
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.witness
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.read
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.landed
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.decide
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.negative
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.blob
import org.junit.Assert.*
import org.junit.Test

import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.nullUser
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.nullKrx
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.companionUser
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.companionKrx
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.newUser
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.newKrx
import com.jay.fxi.data.entitlements.control.CurrentNullFixtures.both

class CurrentNullGuardTest : CurrentNullOwnerBase() {
    @Test fun G06_type_owner() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[intPreferencesKey(OWNER_UID.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_user() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[intPreferencesKey(USER_EPOCH.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_krx() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[intPreferencesKey(KRX_EPOCH.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_premiumMarker() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[intPreferencesKey(MAY_CONTAIN_PREMIUM.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_krxMarker() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[intPreferencesKey(MAY_CONTAIN_KRX.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_teardown() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[intPreferencesKey(TEARDOWN_OWED_FOR.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_type_journal() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[intPreferencesKey(PURGE_JOURNAL.name)] = 3 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_empty_user() = runReleaseTest {
        val s = spec(fence = before.copy(userAccessEpoch = ""))
        deniedN(s, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_empty_krx() = runReleaseTest {
        val s = spec(fence = before.copy(krxCapabilityEpoch = ""))
        deniedN(s, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_fence_owner() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[OWNER_UID] = "changed" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_fence_user() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[USER_EPOCH] = "changed" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_fence_krx() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[KRX_EPOCH] = "changed" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_fence_krx_selectedAxis() = runReleaseTest {
        // Rotating this axis prevents candidate preservation from masking a skipped fence check.
        val s = spec(target = node(nullKrx))
        deniedN(s, source = raw(s).toMutablePreferences().apply { this[KRX_EPOCH] = "changed" },
            reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_context_owner() = runReleaseTest {
        deniedN(context = context.copy(ownerUid = "B"), reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_context_binding() = runReleaseTest {
        deniedN(context = context.copy(binding = 10), reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_context_origin() = runReleaseTest {
        deniedN(context = context.copy(originLifetimeId = LifetimeId("other")), reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_missingContext() = runReleaseTest {
        deniedN(context = null, reason = RejectionReason.InvalidRequest("AttemptContextRequired"))
    }
    @Test fun G06_executorFence() = runReleaseTest {
        deniedN(
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
    @Test fun G17_request_owner() = runReleaseTest {
        deniedN(spec(demand = request.copy(ownerUid = "B")), reason = RejectionReason.InvalidRequest("DemandScopeMismatch"))
    }
    @Test fun G17_request_binding() = runReleaseTest {
        deniedN(spec(demand = request.copy(binding = 8)), reason = RejectionReason.InvalidRequest("DemandScopeMismatch"))
    }
    @Test fun G17_request_origin() = runReleaseTest {
        deniedN(spec(demand = request.copy(raisedAt = EventOrderV1(LifetimeId("other"), 23))), reason = RejectionReason.InvalidRequest("DemandScopeMismatch"))
    }
    @Test fun G17_negativeOrder() = runReleaseTest {
        assertEquals("InvalidDemand", transition.invalidInput(spec(demand = request.copy(raisedAt = EventOrderV1(life, -1)))))
        deniedN(spec(demand = request.copy(raisedAt = EventOrderV1(life, -1))), reason = RejectionReason.InvalidRequest("InvalidDemand"))
    }
    @Test fun G17_userIntent() = runReleaseTest {
        deniedN(spec(demand = request.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS)), reason = RejectionReason.InvalidRequest("InsufficientIntent"))
    }
    @Test fun G17_capabilityIntent() = runReleaseTest {
        deniedN(spec(target = node(nullKrx), demand = request.copy(intent = RefreshIntent.IF_STALE)), reason = RejectionReason.InvalidRequest("InsufficientIntent"))
    }
    @Test fun G24_teardown_owner() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" }, reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_teardown_thirdOwner() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "C" }, reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_signOutOpen() = runReleaseTest {
        deniedN(context = context.copy(signOutOpen = true), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_identityPersistencePending() = runReleaseTest {
        deniedN(context = context.copy(identityPersistencePending = true), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G22_fiveFields() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||USER|future" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun G22_damaged() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "broken" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun G22_scope() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||UNKNOWN" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun A13_schema1() = runReleaseTest {
        val p = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SCHEMA] = 1; remove(evidenceKey); remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)) }
        deniedN(source = p, reason = RecoveryReason.ControlSchemaMigrationRequired)
    }
    @Test fun A13_opaque_seal() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[${nullUser},{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_demand() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_hold() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_recovery() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.RECOVERY] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_evidence() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = "[{}]" }, reason = RecoveryReason.UninterpretableMetadata)
    }
    @Test fun A13_opaque_fence() = runReleaseTest {
        deniedN(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = "[{}]" }, reason = RecoveryReason.UninterpretableMetadata)
    }
    @Test fun G17_collision_SEAL() = runReleaseTest {
        val row = node(nullKrx).toPayloadEntry().fields
        val collision = ControlNode.of(kotlinx.serialization.json.JsonObject(row + ("id" to kotlinx.serialization.json.JsonPrimitive("n-demand"))))
        val p = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)] = NamespaceSettlementFixtures.jsonArray(spec().nullTargets.first(), collision) }
        deniedN(source = p, reason = ConflictReason.IdCollision)
    }
    @Test fun G17_collision_DEMAND() = runReleaseTest {
        val row = node(ControlObligationFixtures.request).toPayloadEntry().fields
        val collision = ControlNode.of(kotlinx.serialization.json.JsonObject(row + ("id" to kotlinx.serialization.json.JsonPrimitive("n-demand"))))
        val p = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.DEMAND)] = NamespaceSettlementFixtures.jsonArray(collision) }
        deniedN(source = p, reason = ConflictReason.IdCollision)
    }
    @Test fun G17_collision_HOLD() = runReleaseTest {
        val row = node(ControlObligationFixtures.hold).toPayloadEntry().fields
        val collision = ControlNode.of(kotlinx.serialization.json.JsonObject(row + ("id" to kotlinx.serialization.json.JsonPrimitive("n-demand"))))
        val p = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.HOLD)] = NamespaceSettlementFixtures.jsonArray(collision) }
        deniedN(source = p, reason = ConflictReason.IdCollision)
    }
    @Test fun G17_collision_RECOVERY_INTENT() = runReleaseTest {
        val row = node(ControlObligationFixtures.recovery).toPayloadEntry().fields
        val collision = ControlNode.of(kotlinx.serialization.json.JsonObject(row + ("id" to kotlinx.serialization.json.JsonPrimitive("n-demand"))))
        val p = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = NamespaceSettlementFixtures.jsonArray(collision) }
        deniedN(source = p, reason = ConflictReason.IdCollision)
    }
    @Test fun G03_emptyNulls() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("EmptyTargets", transition.invalidInput(spec(nulls = emptyList())))
    }
    @Test fun G01_nullKind() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("UnsupportedTargetKind", transition.invalidInput(spec(target = node(companionUser))))
    }
    @Test fun G01_opaqueTarget() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("UnsupportedTargetKind", transition.invalidInput(spec(target = node("{}"))))
    }
    @Test fun G01_companionKind() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("UnsupportedCompanionKind", transition.invalidInput(spec(companions = listOf(node(nullUser.replace("\"s\"", "\"other\""))), fence = before.copy(userAccessEpoch = null))))
    }
    @Test fun G01_opaqueCompanion() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("UnsupportedTargetKind", transition.invalidInput(spec(companions = listOf(node("{}")))))
    }
    @Test fun G01_settledInput() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("AlreadySettledInput", transition.invalidInput(spec(target = NamespaceSettlementFixtures.withWitness(nullUser, witness(spec())))))
    }
    @Test fun G03_duplicateId() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("DuplicateTargetId", transition.invalidInput(spec(companions = listOf(node(companionUser.replace("\"us\"", "\"s\""))))))
    }
    @Test fun G03_duplicateAxis() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("DuplicateAxis", transition.invalidInput(spec(nulls = listOf(node(nullUser), node(nullUser.replace("\"s\"", "\"other\""))))))
    }
    @Test fun G03_duplicateCompanionAxis() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("DuplicateCompanionAxis", transition.invalidInput(spec(companions = listOf(node(companionUser), node(companionUser.replace("\"us\"", "\"other\""))))))
    }
    @Test fun G06_targetOwner() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("TargetFenceMismatch", transition.invalidInput(spec(target = node(nullUser.replace("\"A\"", "\"B\"")))))
    }
    @Test fun G23_companionOwner() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("TargetFenceMismatch", transition.invalidInput(spec(companions = listOf(node(companionUser.replace("\"A\"", "\"B\""))))))
    }
    @Test fun G23_companionAxis() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("CompanionAxisMismatch", transition.invalidInput(spec(companions = listOf(node(companionKrx)))))
    }
    @Test fun G23_companionEpoch() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("CompanionEpochMismatch", transition.invalidInput(spec(companions = listOf(node(companionUser.replace("u2", "old"))))))
    }
    @Test fun G07_null() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("EpochNotFresh", transition.invalidInput(spec(u = null)))
    }
    @Test fun G07_empty() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("EpochNotFresh", transition.invalidInput(spec(u = "")))
    }
    @Test fun G07_beforeUser() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("EpochNotFresh", transition.invalidInput(spec(fence = before.copy(userAccessEpoch = newUser), u = newUser)))
    }
    @Test fun G07_beforeKrx() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("EpochNotFresh", transition.invalidInput(spec(fence = before.copy(krxCapabilityEpoch = newUser), u = newUser)))
    }
    @Test fun G07_equalFresh() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("EpochNotFresh", transition.invalidInput(spec(nulls = listOf(node(nullUser), node(nullKrx)), k = newUser)))
    }
    @Test fun G07_invalidUuid() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("InvalidEpochUuid", transition.invalidInput(spec(u = "not-a-uuid")))
    }
    @Test fun G07_unselectedUser() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("UnexpectedEpoch", transition.invalidInput(spec(target = node(nullKrx), u = newUser)))
    }
    @Test fun G07_unselectedKrx() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("UnexpectedEpoch", transition.invalidInput(spec(k = newKrx)))
    }
    @Test fun G17_emptyOperation() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("InvalidOperationId", transition.invalidInput(spec(op = "")))
    }
    @Test fun G17_emptyDemandId() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("InvalidDemand", transition.invalidInput(spec(did = "")))
    }
    @Test fun G17_targetDemandId() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("InvalidDemand", transition.invalidInput(spec(did = "s")))
    }
    @Test fun G17_companionDemandId() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("InvalidDemand", transition.invalidInput(spec(companions = listOf(node(companionUser)), did = "us")))
    }
    @Test fun G21_owner_empty() {
        val owner = ""
        val s = spec(target = node(nullUser.replace("\"A\"", CurrentNullFixtures.q(owner))),
            fence = before.copy(ownerUid = owner), exec = executor.copy(ownerUid = owner), demand = request.copy(ownerUid = owner))
        assertEquals("UnrepresentableJournalField", transition.invalidInput(s))
    }
    @Test fun G21_owner_delimiter() {
        val owner = "bad|owner"
        val s = spec(target = node(nullUser.replace("\"A\"", CurrentNullFixtures.q(owner))),
            fence = before.copy(ownerUid = owner), exec = executor.copy(ownerUid = owner), demand = request.copy(ownerUid = owner))
        assertEquals("UnrepresentableJournalField", transition.invalidInput(s))
    }
    @Test fun G21_owner_newline() {
        val owner = "bad\nowner"
        val s = spec(target = node(nullUser.replace("\"A\"", CurrentNullFixtures.q(owner))),
            fence = before.copy(ownerUid = owner), exec = executor.copy(ownerUid = owner), demand = request.copy(ownerUid = owner))
        assertEquals("UnrepresentableJournalField", transition.invalidInput(s))
    }
    @Test fun G21_owner_surrogate() {
        val owner = "bad\uD800owner"
        val s = spec(target = node(nullUser.replace("\"A\"", CurrentNullFixtures.q(owner))),
            fence = before.copy(ownerUid = owner), exec = executor.copy(ownerUid = owner), demand = request.copy(ownerUid = owner))
        assertEquals("UnrepresentableJournalField", transition.invalidInput(s))
    }
    @Test fun G03_activeNullKey() = runReleaseTest {

        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser,${nullUser.replace("\"s\"", "\"other\"")}]" }, reason = ConflictReason.AmbiguousSealKey)
    }
    @Test fun G02_changed_ownerUid() = runReleaseTest {

        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[${nullUser.replace("\"A\"", "\"B\"")}]" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G01_exactPreimage_withReplacementKey() = runReleaseTest {
        val changed = nullUser.replace("\"A\"", "\"B\"")
        val replacement = nullUser.replace("\"s\"", "\"replacement\"")
        val source = raw().toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = "[$changed,$replacement]"
        }
        val rows = read(source).arrays.getValue(ControlKind.SEAL).entries
            .map { (it as ControlEntryRead.Interpreted).value as SealV1 }
        assertEquals("fixture has exactly one active expected key", 1,
            rows.count { it.settlement == null && it.key == target(spec()).key })
        assertEquals(setOf("s", "replacement"), rows.map { it.id }.toSet())
        deniedN(source = source, reason = ConflictReason.TargetChanged)
    }
    @Test fun G02_changed_axis() = runReleaseTest {

        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[${nullUser.replace("\"USER\"", "\"CAPABILITY\"")}]" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G02_missingId() = runReleaseTest {

        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[${nullUser.replace("\"s\"", "\"other\"")}]" }, reason = ConflictReason.TargetMissing)
    }
    @Test fun G23_addedCompanion() = runReleaseTest {

        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser,$companionUser]" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G23_missingCompanion() = runReleaseTest {
        val s = spec(companions = listOf(node(companionUser)))
        deniedN(s, source = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser]" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G23_changedCompanion() = runReleaseTest {
        val s = spec(companions = listOf(node(companionUser)))
        deniedN(s, source = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser,${companionUser.replace("u2", "old")}]" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G23_ambiguousFixedCompanion() = runReleaseTest {
        val s = spec(companions = listOf(node(companionUser)))
        deniedN(s, source = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser,$companionUser,${companionUser.replace("\"us\"", "\"other\"")}]" }, reason = ConflictReason.AmbiguousSealKey)
    }
    @Test fun G23_ambiguousNewCompanion() = runReleaseTest {

        deniedN(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser,$companionUser,${companionUser.replace("\"us\"", "\"other\"")}]" }, reason = ConflictReason.AmbiguousSealKey)
    }
}
