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
class CurrentNullExactTest : CurrentNullOwnerBase() {
    @Test fun A02_operationId() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_operationId: altered witness must not match", compare.witnessMatches(expected.copy(operationId = "other"), expected))
    }
    @Test fun A02_origin() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_origin: altered witness must not match", compare.witnessMatches(expected.copy(originLifetimeId = LifetimeId("other")), expected))
    }
    @Test fun A02_operation() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_operation: altered witness must not match", compare.witnessMatches(expected.copy(operation = StoreOp.JOURNAL_RETIRED), expected))
    }
    @Test fun A02_before_ownerUid() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_before_ownerUid: altered witness must not match", compare.witnessMatches(expected.copy(before = expected.before.copy(ownerUid = "other")), expected))
    }
    @Test fun A02_before_userAccessEpoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_before_userAccessEpoch: altered witness must not match", compare.witnessMatches(expected.copy(before = expected.before.copy(userAccessEpoch = "other")), expected))
    }
    @Test fun A02_before_krxCapabilityEpoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_before_krxCapabilityEpoch: altered witness must not match", compare.witnessMatches(expected.copy(before = expected.before.copy(krxCapabilityEpoch = "other")), expected))
    }
    @Test fun A02_after_ownerUid() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_after_ownerUid: altered witness must not match", compare.witnessMatches(expected.copy(after = expected.after.copy(ownerUid = "other")), expected))
    }
    @Test fun A02_after_userAccessEpoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_after_userAccessEpoch: altered witness must not match", compare.witnessMatches(expected.copy(after = expected.after.copy(userAccessEpoch = "other")), expected))
    }
    @Test fun A02_after_krxCapabilityEpoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_after_krxCapabilityEpoch: altered witness must not match", compare.witnessMatches(expected.copy(after = expected.after.copy(krxCapabilityEpoch = "other")), expected))
    }
    @Test fun A02_journal_ownerUid() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_journal_ownerUid: altered witness must not match", compare.witnessMatches(expected.copy(journal = expected.journal.copy(ownerUid = "B")), expected))
    }
    @Test fun A02_journal_axis() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_journal_axis: altered witness must not match", compare.witnessMatches(expected.copy(journal = expected.journal.copy(axis = PurgeScope.CAPABILITY)), expected))
    }
    @Test fun A02_journal_epoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse("A02_journal_epoch: altered witness must not match", compare.witnessMatches(expected.copy(journal = expected.journal.copy(epoch = "other")), expected))
    }
    @Test fun A02_ownerIntegration() {
        val s = spec(); val c = command(s)
        val p = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("n-origin", "other") }
        negative(decide(s, p, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A02_immutableIntegration() {
        val s = spec(companions = listOf(node(companionUser))); val c = command(s)
        val p = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("\"epoch\":\"u2\"", "\"epoch\":\"older\"") }
        assertFalse(read(p).hasUninterpretable)
        negative(decide(s, p, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A01_commandId() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse("A01_commandId: altered Applied evidence must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement("other", c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")))
    }
    @Test fun A01_lifetime() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse("A01_lifetime: altered Applied evidence must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, "other", HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")))
    }
    @Test fun A01_kind() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse("A01_kind: altered Applied evidence must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Rotation(c.id, c.ownerTrackingLifetimeId.value, listOf("s"), "n-demand")))
    }
    @Test fun A01_transition() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse("A01_transition: altered Applied evidence must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "n-demand")))
    }
    @Test fun A01_sealIds() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse("A01_sealIds: altered Applied evidence must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("other"), "n-demand")))
    }
    @Test fun A01_demandId() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse("A01_demandId: altered Applied evidence must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), null)))
    }
    @Test fun A01_expectedApplied() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "n-demand")
        tracked.expectedApplied = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), null)
        assertFalse("A01_expectedApplied: altered Applied evidence must not match", ControlAppliedEvidence.matches(c, tracked, correct))
    }
    @Test fun A01_order() {
        val c = command(both()); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s", "us", "c", "ks"), "n-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, good))
        assertFalse("A01_order: reordered sealIds must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value,
            HandoverSettlementTransition.CURRENT_NULL, listOf("us", "s", "c", "ks"), "n-demand")))
    }
    @Test fun G02_exact_id_boundary() {
        val expected = node(companionUser)
        val changed = node(companionUser.replace("\"us\"", "\"other\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_exact_id_boundary: altered immutable seal must not match", compare.immutableSealMatches(changed, expected))
    }
    @Test fun G02_exact_kind_boundary() {
        val expected = node(companionUser)
        val changed = node(companionUser.replace("\"NAMESPACE\"", "\"NULL_NAMESPACE\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_exact_kind_boundary: altered immutable seal must not match", compare.immutableSealMatches(changed, expected))
    }
    @Test fun G02_exact_owner_boundary() {
        val expected = node(companionUser)
        val changed = node(companionUser.replace("\"A\"", "\"B\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_exact_owner_boundary: altered immutable seal must not match", compare.immutableSealMatches(changed, expected))
    }
    @Test fun G02_exact_axis_boundary() {
        val expected = node(companionUser)
        val changed = node(companionUser.replace("\"USER\"", "\"CAPABILITY\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_exact_axis_boundary: altered immutable seal must not match", compare.immutableSealMatches(changed, expected))
    }
    @Test fun G02_exact_epoch_boundary() {
        val expected = node(companionUser)
        val changed = node(companionUser.replace("\"u2\"", "\"older\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_exact_epoch_boundary: altered immutable seal must not match", compare.immutableSealMatches(changed, expected))
    }
}
