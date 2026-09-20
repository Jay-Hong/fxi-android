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
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.spec
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.raw
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.both
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.before
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.executor
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.context
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.life
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.target
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.transition
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.command
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.witness
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.read
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.landed
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.decide
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.negative
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.blob
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.nullUser
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.nullKrx
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.withWitness
import com.jay.fxi.data.entitlements.control.RetiredNullFixtures.q
import org.junit.Assert.*
import org.junit.Test

class RetiredNullGuardTest : RetiredNullOwnerBase() {
    @Test fun G03_empty() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G03_empty: input rejection", "EmptyTargets" as Any, transition.invalidInput(spec(targets = emptyList())))
    }
    @Test fun G03_three() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G03_three: input rejection", "TooManyTargets" as Any, transition.invalidInput(spec(targets = listOf(node(nullUser), node(nullKrx), node(nullUser.replace("\"s\"", "\"third\""))))))
    }
    @Test fun G01_opaque() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G01_opaque: input rejection", "UnsupportedTargetKind" as Any, transition.invalidInput(spec(targets = listOf(node(nullUser), node("{}")))))
    }
    @Test fun G01_kind() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G01_kind: input rejection", "UnsupportedTargetKind", transition.invalidInput(spec(target = node(ControlObligationFixtures.seal))))
    }
    @Test fun G01_settled() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G01_settled: input rejection", "AlreadySettledInput", transition.invalidInput(spec(target = withWitness(spec()))))
    }
    @Test fun G03_id() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G03_id: input rejection", "DuplicateTargetId", transition.invalidInput(spec(targets = listOf(node(nullUser), node(nullKrx.replace("\"c\"", "\"s\""))))))
    }
    @Test fun G03_axis() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G03_axis: input rejection", "DuplicateAxis", transition.invalidInput(spec(targets = listOf(node(nullUser), node(nullUser.replace("\"s\"", "\"other\""))))))
    }
    @Test fun G15_mixed() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G15_mixed: input rejection", "MixedSubjectOwners", transition.invalidInput(spec(targets = listOf(node(nullUser), node(nullKrx.replace("\"A\"", "\"C\""))))))
    }
    @Test fun G15_current() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G15_current: input rejection", "SubjectOwnerIsCurrent", transition.invalidInput(spec(fence = before.copy(ownerUid = "A"), exec = executor.copy(ownerUid = "A"))))
    }
    @Test fun G17_operation() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G17_operation: input rejection", "InvalidOperationId", transition.invalidInput(spec(op = "")))
    }
    @Test fun G06_executorOwner() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G06_executorOwner: input rejection", "ExecutorFenceMismatch", transition.invalidInput(spec(exec = executor.copy(ownerUid = "C"))))
    }
    @Test fun G06_executorBinding() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G06_executorBinding: input rejection", "InvalidExecutor", transition.invalidInput(spec(exec = executor.copy(binding = -1))))
    }
    @Test fun G06_executorOrigin() {
        assertNull(transition.invalidInput(spec()))
        assertEquals("G06_executorOrigin: input rejection", "InvalidExecutor", transition.invalidInput(spec(exec = executor.copy(originLifetimeId = LifetimeId("")))))
    }
    @Test fun G21_empty() {
        val bad = spec(target = node(nullUser.replace("\"A\"", q(""))))
        assertEquals("G21_empty: input rejection", "UnrepresentableJournalField", transition.invalidInput(bad))
    }
    @Test fun G21_delimiter() {
        val bad = spec(target = node(nullUser.replace("\"A\"", q("bad|owner"))))
        assertEquals("G21_delimiter: input rejection", "UnrepresentableJournalField", transition.invalidInput(bad))
    }
    @Test fun G21_newline() {
        val bad = spec(target = node(nullUser.replace("\"A\"", q("bad\nowner"))))
        assertEquals("G21_newline: input rejection", "UnrepresentableJournalField", transition.invalidInput(bad))
    }
    @Test fun G21_surrogate() {
        val bad = spec(target = node(nullUser.replace("\"A\"", q("bad\uD800owner"))))
        assertEquals("G21_surrogate: input rejection", "UnrepresentableJournalField", transition.invalidInput(bad))
    }
    @Test fun G06_fence_OWNER_UID() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[OWNER_UID] = "other" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_fence_USER_EPOCH() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[USER_EPOCH] = "other" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_fence_KRX_EPOCH() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[KRX_EPOCH] = "other" }, reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_contextOwner() = runReleaseTest {
        deniedL(context = context.copy(ownerUid = "C"), reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_contextBinding() = runReleaseTest {
        deniedL(context = context.copy(binding = 10), reason = ConflictReason.TargetChanged)
    }
    @Test fun G06_contextOrigin() = runReleaseTest {
        deniedL(context = context.copy(originLifetimeId = LifetimeId("other")), reason = ConflictReason.TargetChanged)
    }
    @Test fun G24_signOut() = runReleaseTest {
        deniedL(context = context.copy(signOutOpen = true), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_identity() = runReleaseTest {
        deniedL(context = context.copy(identityPersistencePending = true), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G06_contextMissing() = runReleaseTest {
        deniedL(context = null, reason = RejectionReason.InvalidRequest("AttemptContextRequired"))
    }
    @Test fun G24_teardownSubject() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" }, reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_teardownThird() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "C" }, reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G06_rawType_OWNER_UID() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[intPreferencesKey(OWNER_UID.name)] = 7 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_rawType_USER_EPOCH() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[intPreferencesKey(USER_EPOCH.name)] = 7 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_rawType_KRX_EPOCH() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[intPreferencesKey(KRX_EPOCH.name)] = 7 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_rawType_MAY_CONTAIN_PREMIUM() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[intPreferencesKey(MAY_CONTAIN_PREMIUM.name)] = 7 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_rawType_MAY_CONTAIN_KRX() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[intPreferencesKey(MAY_CONTAIN_KRX.name)] = 7 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_rawType_TEARDOWN_OWED_FOR() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[intPreferencesKey(TEARDOWN_OWED_FOR.name)] = 7 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_rawType_PURGE_JOURNAL() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[intPreferencesKey(PURGE_JOURNAL.name)] = 7 }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_empty_USER_EPOCH() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[USER_EPOCH] = "" }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G06_empty_KRX_EPOCH() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[KRX_EPOCH] = "" }, reason = RecoveryReason.UnreadableEpochState)
    }
    @Test fun G22_fiveFields() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||USER|future" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun G22_damaged() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "broken" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun G22_scope() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||UNKNOWN" }, reason = RecoveryReason.JournalMigrationRequired)
    }
    @Test fun A13_opaque_SEAL() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.SEAL)] = "[$nullUser,{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_DEMAND() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.DEMAND)] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_HOLD() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.HOLD)] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_RECOVERY_INTENT() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.RECOVERY_INTENT)] = "[{}]" }, reason = RecoveryReason.UninterpretableObligations)
    }
    @Test fun A13_opaque_COMMAND_EVIDENCE() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)] = "[{}]" }, reason = RecoveryReason.UninterpretableMetadata)
    }
    @Test fun A13_opaque_SCOPE_FENCE() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)] = "[{}]" }, reason = RecoveryReason.UninterpretableMetadata)
    }
    @Test fun G03_activeKey() = runReleaseTest {
        deniedL(source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser,${nullUser.replace("\"s\"", "\"other\"")}]" }, reason = ConflictReason.AmbiguousSealKey)
    }
}
