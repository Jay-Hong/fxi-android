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

class RetiredNamespaceContinuationTest : RetiredNamespaceOwnerBase() {
    @Test fun A05_confirmOnly() {
        val s = spec(); val c = command(s)
        negative(decide(s, raw(s), only = true), ConflictReason.TargetChanged)
    }
    @Test fun A05_previouslyConfirmed() {
        val s = spec(); val c = command(s)
        negative(decide(s, raw(s), confirmed = true), ConflictReason.TargetChanged)
    }
    @Test fun A05_ownApplied() {
        val s = spec(); val c = command(s)
        negative(decide(s, raw(s).toMutablePreferences().apply { this[evidenceKey] = "[${RetiredNamespaceFixtures.applied(c, s)}]" }, c = c), ConflictReason.TargetChanged)
    }
    @Test fun G17_operationCollision() {
        val s = spec(); val c = command(s)
        val sibling = NamespaceSettlementFixtures.withWitness(NamespaceSettlementFixtures.user.replace("\"s\"", "\"other\""), witness(s))
        val p = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(s.target, sibling) }
        negative(decide(s, p, c = c), ConflictReason.OperationIdCollision)
    }
    @Test fun G01_unparseableTarget() {
        val s = spec(target = node("{}"))
        assertEquals("UnsupportedTargetKind", transition.invalidInput(s))
    }
    @Test fun G05_otherAxisNullPreserved() = runReleaseTest {
        val s = spec(fence = before.copy(krxCapabilityEpoch = null)); seedR(s); val c = registerR(s)
        successR(executeR(c), ConfirmedEffect.AppliedThisAttempt); assertLanded(c, s, raw(s))
    }
    @Test fun G05_falseMarkersPreserved() = runReleaseTest {
        val p = raw().toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false; this[MAY_CONTAIN_KRX] = false }
        seedR(raw = p); val c = registerR(spec())
        successR(executeR(c), ConfirmedEffect.AppliedThisAttempt); assertLanded(c, spec(), p)
    }
    @Test fun G24_departed_ownerTeardown() = runReleaseTest {
        val s = departed()
        deniedR(s, source = raw(s).toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "B" }, context = context.copy(ownerUid = "B"), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_departed_thirdTeardown() = runReleaseTest {
        val s = departed()
        deniedR(s, source = raw(s).toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "C" }, context = context.copy(ownerUid = "B"), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_departed_signOut() = runReleaseTest {
        val s = departed()
        deniedR(s, source = raw(s).toMutablePreferences().apply {  }, context = context.copy(ownerUid = "B", signOutOpen = true), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun G24_departed_identity() = runReleaseTest {
        val s = departed()
        deniedR(s, source = raw(s).toMutablePreferences().apply {  }, context = context.copy(ownerUid = "B", identityPersistencePending = true), reason = ConflictReason.IdentityTransitionPending)
    }
    @Test fun A10_previousLifetimeIsNotRebound() = runReleaseTest {
        val s = spec(); seedR(s); val c = registerR(s)
        successR(executeR(c), ConfirmedEffect.AppliedThisAttempt)
        o.close(); val next = open(file); val nt = ControlCommandTracking.forOwner(next.owner)
        controlTestTimeout("replace tracker linkage") { next.data.edit { it[evidenceKey] = it[evidenceKey]!!.replace(c.ownerTrackingLifetimeId.value, nt.lifetimeId.value) } }
        val saved = disk(); val writes = next.storage.writes
        val result = controlTestTimeout("old R tracker mismatch") { next.control.confirmPrevious(c) }
        NamespaceSettlementFixtures.negative(result, ConflictReason.CommandEvidenceMismatch)
        assertNull(nt.findPrepared(c)); assertEquals(saved, disk()); assertEquals(writes, next.storage.writes); assertFalse(c in nt.executing)
    }
    @Test fun A10_previousWitnessWithoutApplied() = runReleaseTest {
        val s = departed(); seedR(s); val c = registerR(s)
        successR(executeR(c, context.copy(ownerUid = "B")), ConfirmedEffect.AppliedThisAttempt)
        controlTestTimeout("remove old evidence") { o.data.edit { it[evidenceKey] = "[]" } }
        o.close(); val next = open(file); val saved = disk(); val writes = next.storage.writes
        val result = controlTestTimeout("old fixed witness") { next.control.confirmPrevious(c) }
        assertTrue(result is ControlStoreResult.Confirmed)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, (result as ControlStoreResult.Confirmed).effect)
        assertEquals(saved, disk()); assertEquals(writes, next.storage.writes)
        assertNull(ControlCommandTracking.forOwner(next.owner).findPrepared(c))
    }
    @Test fun A02_immutableIntegrationIsExact() {
        val s = spec(); val c = command(s)
        val p = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("\"ownerUid\":\"A\"", "\"ownerUid\":null") }
        // Both reader and witness remain valid only for an exact fixed preimage.
        negative(decide(s, p, c = c), RecoveryReason.InconsistentSettlement)
    }
    private fun rejectTargetList(targets: List<ControlNode>) {
        val method = ControlRecordStore::class.java.declaredMethods.single { it.name.substringBefore('$') == "prepareRetiredNamespaceSettlement" }
        assertEquals(ControlNode::class.java, method.parameterTypes.first())
        assertThrows(IllegalArgumentException::class.java) {
            method.invoke(o.control, targets, before, executor, request)
        }
        assertTrue(tracking.snapshot().isEmpty())
        assertEquals(0, o.storage.writes)
    }
    @Test fun G03_zeroTargetListCannotEnterR() { rejectTargetList(emptyList()) }
    @Test fun G03_twoTargetListCannotEnterR() { rejectTargetList(listOf(spec().target, node(NamespaceSettlementFixtures.krx))) }
}
