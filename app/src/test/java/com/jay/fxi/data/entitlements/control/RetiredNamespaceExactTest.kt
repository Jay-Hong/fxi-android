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

class RetiredNamespaceExactTest : RetiredNamespaceOwnerBase() {
    @Test fun A02_operationId() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(operationId = "other"), expected))
    }
    @Test fun A02_origin() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(originLifetimeId = LifetimeId("other")), expected))
    }
    @Test fun A02_operation() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(operation = StoreOp.BEGIN_ROTATION), expected))
    }
    @Test fun A02_before_ownerUid() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(before = expected.before.copy(ownerUid = "other")), expected))
    }
    @Test fun A02_before_userAccessEpoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(before = expected.before.copy(userAccessEpoch = "other")), expected))
    }
    @Test fun A02_before_krxCapabilityEpoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(before = expected.before.copy(krxCapabilityEpoch = "other")), expected))
    }
    @Test fun A02_after_ownerUid() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(after = expected.after.copy(ownerUid = "other")), expected))
    }
    @Test fun A02_after_userAccessEpoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(after = expected.after.copy(userAccessEpoch = "other")), expected))
    }
    @Test fun A02_after_krxCapabilityEpoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(after = expected.after.copy(krxCapabilityEpoch = "other")), expected))
    }
    @Test fun A02_journal_ownerUid() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(journal = expected.journal.copy(ownerUid = "B")), expected))
    }
    @Test fun A02_journal_axis() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(journal = expected.journal.copy(axis = PurgeScope.CAPABILITY)), expected))
    }
    @Test fun A02_journal_epoch() {
        val expected = witness(spec())
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.witnessMatches(expected, expected))
        assertFalse(compare.witnessMatches(expected.copy(journal = expected.journal.copy(epoch = "other")), expected))
    }
    @Test fun A02_ownerIntegration() {
        val s = spec(); val c = command(s)
        val p = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("r-origin", "other") }
        negative(decide(s, p, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A02_immutableIntegration() {
        val s = spec(); val c = command(s)
        val p = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("\"epoch\":\"u\"", "\"epoch\":\"other\"").replace("\"epoch\":\"other\"}}", "\"epoch\":null}}") }
        negative(decide(s, p, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A01_commandId() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse(ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement("other", c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")))
    }
    @Test fun A01_lifetime() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse(ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, "other", HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")))
    }
    @Test fun A01_kind() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse(ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Rotation(c.id, c.ownerTrackingLifetimeId.value, listOf("s"), "r-demand")))
    }
    @Test fun A01_transition() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse(ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.CURRENT_NULL, listOf("s"), "r-demand")))
    }
    @Test fun A01_sealIds() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse(ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("other"), "r-demand")))
    }
    @Test fun A01_demandId() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")
        assertTrue(ControlAppliedEvidence.matches(c, tracked, correct))
        assertFalse(ControlAppliedEvidence.matches(c, tracked, AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), null)))
    }
    @Test fun A01_expectedApplied() {
        val c = command(); val tracked = TrackedControlCommand(c)
        val correct = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), "r-demand")
        tracked.expectedApplied = AppliedEvidence.Settlement(c.id, c.ownerTrackingLifetimeId.value, HandoverSettlementTransition.RETIRED_NAMESPACE, listOf("s"), null)
        assertFalse(ControlAppliedEvidence.matches(c, tracked, correct))
    }
    @Test fun G17_constructorMissingDemand() {
        assertThrows(IllegalArgumentException::class.java) { spec(demand = null) }
    }
    @Test fun G17_constructorMissingId() {
        assertThrows(IllegalArgumentException::class.java) { spec(did = null) }
    }
}
