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

class RetiredNullExactTest {
    @Test fun A02_operationId() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_operationId: altered witness must not match", transition.witnessMatches(expected.copy(operationId = "other"), expected))
    }
    @Test fun A02_originLifetimeId() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_originLifetimeId: altered witness must not match", transition.witnessMatches(expected.copy(originLifetimeId = LifetimeId("other")), expected))
    }
    @Test fun A02_before_ownerUid() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_before_ownerUid: altered witness must not match", transition.witnessMatches(expected.copy(before = expected.before.copy(ownerUid = "other")), expected))
    }
    @Test fun A02_before_userAccessEpoch() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_before_userAccessEpoch: altered witness must not match", transition.witnessMatches(expected.copy(before = expected.before.copy(userAccessEpoch = "other")), expected))
    }
    @Test fun A02_before_krxCapabilityEpoch() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_before_krxCapabilityEpoch: altered witness must not match", transition.witnessMatches(expected.copy(before = expected.before.copy(krxCapabilityEpoch = "other")), expected))
    }
    @Test fun A02_after_ownerUid() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_after_ownerUid: altered witness must not match", transition.witnessMatches(expected.copy(after = expected.after.copy(ownerUid = "other")), expected))
    }
    @Test fun A02_after_userAccessEpoch() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_after_userAccessEpoch: altered witness must not match", transition.witnessMatches(expected.copy(after = expected.after.copy(userAccessEpoch = "other")), expected))
    }
    @Test fun A02_after_krxCapabilityEpoch() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_after_krxCapabilityEpoch: altered witness must not match", transition.witnessMatches(expected.copy(after = expected.after.copy(krxCapabilityEpoch = "other")), expected))
    }
    @Test fun A02_journal_ownerUid() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_journal_ownerUid: altered witness must not match", transition.witnessMatches(expected.copy(journal = expected.journal.copy(ownerUid = "other")), expected))
    }
    @Test fun A02_journal_axis() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_journal_axis: altered witness must not match", transition.witnessMatches(expected.copy(journal = expected.journal.copy(axis = PurgeScope.CAPABILITY)), expected))
    }
    @Test fun A02_journal_epoch() {
        val expected = witness(spec())
        assertTrue(transition.witnessMatches(expected, expected))
        assertFalse("A02_journal_epoch: altered witness must not match", transition.witnessMatches(expected.copy(journal = expected.journal.copy(epoch = "other")), expected))
    }
    @Test fun A02_type() {
        val expected = witness(spec())
        val v1 = SettlementEvidenceV1(expected.operationId, expected.originLifetimeId, StoreOp.JOURNAL_RETIRED, expected.before, expected.after, expected.journal)
        assertFalse("A02_type: V1 must not match L V2", transition.witnessMatches(v1, expected))
    }
    @Test fun A01_commandId() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)
        assertTrue(ControlAppliedEvidence.matches(c, tracked, good))
        assertFalse("A01_commandId: altered Applied must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement("other", c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)))
    }
    @Test fun A01_lifetime() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)
        assertTrue(ControlAppliedEvidence.matches(c, tracked, good))
        assertFalse("A01_lifetime: altered Applied must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, "other", HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)))
    }
    @Test fun A01_kind() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)
        assertTrue(ControlAppliedEvidence.matches(c, tracked, good))
        assertFalse("A01_kind: altered Applied must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Rotation(c.id, c.ownerTrackingLifetimeId.value, listOf("s"), "d")))
    }
    @Test fun A01_transition() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)
        assertTrue(ControlAppliedEvidence.matches(c, tracked, good))
        assertFalse("A01_transition: altered Applied must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), null)))
    }
    @Test fun A01_sealIds() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)
        assertTrue(ControlAppliedEvidence.matches(c, tracked, good))
        assertFalse("A01_sealIds: altered Applied must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("other"), null)))
    }
    @Test fun A01_demandId() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)
        assertTrue(ControlAppliedEvidence.matches(c, tracked, good))
        assertFalse("A01_demandId: altered Applied must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), "other")))
    }
    @Test fun A01_order() {
        val c = command(both()); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s", "c"), null)
        assertTrue(ControlAppliedEvidence.matches(c, tracked, good))
        assertFalse("A01_order: reordered Applied must not match", ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("c", "s"), null)))
    }
    @Test fun A01_expectedApplied() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val good = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("s"), null)
        tracked.expectedApplied = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NULL, listOf("other"), null)
        assertFalse("A01_expectedApplied: retained evidence must match", ControlAppliedEvidence.matches(c, tracked, good))
    }
    @Test fun G02_immutable_id() {
        val expected = node(nullUser)
        val actual = node(nullUser.replace("\"s\"", "\"other\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_immutable_id: immutable field must match", compare.immutableSealMatches(actual, expected))
    }
    @Test fun G02_immutable_kind() {
        val expected = node(nullUser)
        val actual = node(nullUser.replace("\"NULL_NAMESPACE\"", "\"NAMESPACE\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_immutable_kind: immutable field must match", compare.immutableSealMatches(actual, expected))
    }
    @Test fun G02_immutable_ownerUid() {
        val expected = node(nullUser)
        val actual = node(nullUser.replace("\"A\"", "\"C\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_immutable_ownerUid: immutable field must match", compare.immutableSealMatches(actual, expected))
    }
    @Test fun G02_immutable_axis() {
        val expected = node(nullUser)
        val actual = node(nullUser.replace("\"USER\"", "\"CAPABILITY\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(expected, expected))
        assertFalse("G02_immutable_axis: immutable field must match", compare.immutableSealMatches(actual, expected))
    }
}
