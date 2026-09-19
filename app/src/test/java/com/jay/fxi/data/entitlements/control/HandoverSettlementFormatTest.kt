package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.applied
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.change
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.evidence
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.lSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.lWitness
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.nSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.rSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.record
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.rejectEvidence
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.rejectSeal
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.row
import com.jay.fxi.data.entitlements.control.HandoverFormatFixtures.seal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HandoverSettlementFormatTest {
    @Test fun A19_missing_version() {
        rejectSeal(change(lSeal, "settlement.version", null))
    }
    @Test fun A19_missing_kind() {
        rejectSeal(change(lSeal, "settlement.kind", null))
    }
    @Test fun A19_missing_operationId() {
        rejectSeal(change(lSeal, "settlement.operationId", null))
    }
    @Test fun A19_missing_originLifetimeId() {
        rejectSeal(change(lSeal, "settlement.originLifetimeId", null))
    }
    @Test fun A19_missing_before() {
        rejectSeal(change(lSeal, "settlement.before", null))
    }
    @Test fun A19_missing_after() {
        rejectSeal(change(lSeal, "settlement.after", null))
    }
    @Test fun A19_missing_journal() {
        rejectSeal(change(lSeal, "settlement.journal", null))
    }
    @Test fun A19_unknown_witness_key() {
        rejectSeal(change(lSeal, "settlement.future", JsonPrimitive(true)))
    }
    @Test fun A19_mixed_v1_operation() {
        rejectSeal(change(lSeal, "settlement.operation", JsonPrimitive("JOURNAL_RETIRED")))
    }
    @Test fun A19_wrong_version() {
        rejectSeal(change(lSeal, "settlement.version", JsonPrimitive(3)))
    }
    @Test fun A19_quoted_version() {
        rejectSeal(change(lSeal, "settlement.version", JsonPrimitive("2")))
    }
    @Test fun A19_wrong_kind() {
        rejectSeal(change(lSeal, "settlement.kind", JsonPrimitive("OWNER_DEPARTURE")))
    }
    @Test fun A19_wrong_kind_type() {
        rejectSeal(change(lSeal, "settlement.kind", JsonPrimitive(2)))
    }
    @Test fun A19_empty_operationId() {
        rejectSeal(change(lSeal, "settlement.operationId", JsonPrimitive("")))
    }
    @Test fun A19_null_operationId() {
        rejectSeal(change(lSeal, "settlement.operationId", JsonNull))
    }
    @Test fun A19_numeric_operationId() {
        rejectSeal(change(lSeal, "settlement.operationId", JsonPrimitive(2)))
    }
    @Test fun A19_empty_originLifetimeId() {
        rejectSeal(change(lSeal, "settlement.originLifetimeId", JsonPrimitive("")))
    }
    @Test fun A19_null_originLifetimeId() {
        rejectSeal(change(lSeal, "settlement.originLifetimeId", JsonNull))
    }
    @Test fun A19_numeric_originLifetimeId() {
        rejectSeal(change(lSeal, "settlement.originLifetimeId", JsonPrimitive(2)))
    }
    @Test fun A19_unknown_before_key() {
        rejectSeal(change(lSeal, "settlement.before.future", JsonPrimitive(true)))
    }
    @Test fun A19_null_before() {
        rejectSeal(change(lSeal, "settlement.before", JsonNull))
    }
    @Test fun A19_missing_before_ownerUid() {
        rejectSeal(change(lSeal, "settlement.before.ownerUid", null))
    }
    @Test fun A19_numeric_before_ownerUid() {
        rejectSeal(change(lSeal, "settlement.before.ownerUid", JsonPrimitive(2)))
    }
    @Test fun A19_missing_before_userAccessEpoch() {
        rejectSeal(change(lSeal, "settlement.before.userAccessEpoch", null))
    }
    @Test fun A19_numeric_before_userAccessEpoch() {
        rejectSeal(change(lSeal, "settlement.before.userAccessEpoch", JsonPrimitive(2)))
    }
    @Test fun A19_missing_before_krxCapabilityEpoch() {
        rejectSeal(change(lSeal, "settlement.before.krxCapabilityEpoch", null))
    }
    @Test fun A19_numeric_before_krxCapabilityEpoch() {
        rejectSeal(change(lSeal, "settlement.before.krxCapabilityEpoch", JsonPrimitive(2)))
    }
    @Test fun A19_empty_before_userAccessEpoch() {
        rejectSeal(change(lSeal, "settlement.before.userAccessEpoch", JsonPrimitive("")))
    }
    @Test fun A19_empty_before_krxCapabilityEpoch() {
        rejectSeal(change(lSeal, "settlement.before.krxCapabilityEpoch", JsonPrimitive("")))
    }
    @Test fun A19_unknown_after_key() {
        rejectSeal(change(lSeal, "settlement.after.future", JsonPrimitive(true)))
    }
    @Test fun A19_null_after() {
        rejectSeal(change(lSeal, "settlement.after", JsonNull))
    }
    @Test fun A19_missing_after_ownerUid() {
        rejectSeal(change(lSeal, "settlement.after.ownerUid", null))
    }
    @Test fun A19_numeric_after_ownerUid() {
        rejectSeal(change(lSeal, "settlement.after.ownerUid", JsonPrimitive(2)))
    }
    @Test fun A19_missing_after_userAccessEpoch() {
        rejectSeal(change(lSeal, "settlement.after.userAccessEpoch", null))
    }
    @Test fun A19_numeric_after_userAccessEpoch() {
        rejectSeal(change(lSeal, "settlement.after.userAccessEpoch", JsonPrimitive(2)))
    }
    @Test fun A19_missing_after_krxCapabilityEpoch() {
        rejectSeal(change(lSeal, "settlement.after.krxCapabilityEpoch", null))
    }
    @Test fun A19_numeric_after_krxCapabilityEpoch() {
        rejectSeal(change(lSeal, "settlement.after.krxCapabilityEpoch", JsonPrimitive(2)))
    }
    @Test fun A19_empty_after_userAccessEpoch() {
        rejectSeal(change(lSeal, "settlement.after.userAccessEpoch", JsonPrimitive("")))
    }
    @Test fun A19_empty_after_krxCapabilityEpoch() {
        rejectSeal(change(lSeal, "settlement.after.krxCapabilityEpoch", JsonPrimitive("")))
    }
    @Test fun A19_unknown_journal_key() {
        rejectSeal(change(lSeal, "settlement.journal.future", JsonPrimitive(true)))
    }
    @Test fun A19_null_journal() {
        rejectSeal(change(lSeal, "settlement.journal", JsonNull))
    }
    @Test fun A19_missing_journal_ownerUid() {
        rejectSeal(change(lSeal, "settlement.journal.ownerUid", null))
    }
    @Test fun A19_numeric_journal_ownerUid() {
        rejectSeal(change(lSeal, "settlement.journal.ownerUid", JsonPrimitive(2)))
    }
    @Test fun A19_missing_journal_axis() {
        rejectSeal(change(lSeal, "settlement.journal.axis", null))
    }
    @Test fun A19_numeric_journal_axis() {
        rejectSeal(change(lSeal, "settlement.journal.axis", JsonPrimitive(2)))
    }
    @Test fun A19_missing_journal_epoch() {
        rejectSeal(change(lSeal, "settlement.journal.epoch", null))
    }
    @Test fun A19_numeric_journal_epoch() {
        rejectSeal(change(lSeal, "settlement.journal.epoch", JsonPrimitive(2)))
    }
    @Test fun A19_epoch_key_on_null_target() {
        rejectSeal(change(lSeal, "epoch", JsonNull))
    }
    @Test fun A19_namespace_target() {
        rejectSeal(change(change(lSeal, "kind", JsonPrimitive("NAMESPACE")), "epoch", JsonPrimitive("old")))
    }
    @Test fun A19_changed_after_ownerUid() {
        rejectSeal(change(lSeal, "settlement.after.ownerUid", JsonPrimitive("C")))
    }
    @Test fun A19_changed_after_userAccessEpoch() {
        rejectSeal(change(lSeal, "settlement.after.userAccessEpoch", JsonPrimitive("other-u")))
    }
    @Test fun A19_changed_after_krxCapabilityEpoch() {
        rejectSeal(change(lSeal, "settlement.after.krxCapabilityEpoch", JsonPrimitive("other-k")))
    }
    @Test fun A19_same_owner() {
        rejectSeal(change(change(lSeal, "ownerUid", JsonPrimitive("B")), "settlement.journal.ownerUid", JsonPrimitive("B")))
    }
    @Test fun A19_both_owners_null() {
        rejectSeal(lSeal.replace("\"A\"", "null").replace("\"B\"", "null"))
    }
    @Test fun A19_journal_owner() {
        rejectSeal(change(lSeal, "settlement.journal.ownerUid", JsonPrimitive("B")))
    }
    @Test fun A19_journal_null_wildcard() {
        rejectSeal(change(lSeal, "settlement.journal.ownerUid", JsonNull))
    }
    @Test fun A19_journal_axis() {
        rejectSeal(change(lSeal, "settlement.journal.axis", JsonPrimitive("CAPABILITY")))
    }
    @Test fun A19_journal_unknown_axis() {
        rejectSeal(change(lSeal, "settlement.journal.axis", JsonPrimitive("OTHER")))
    }
    @Test fun A19_journal_nonnull_epoch() {
        rejectSeal(change(lSeal, "settlement.journal.epoch", JsonPrimitive("old")))
    }
    @Test fun A19_journal_empty_epoch() {
        rejectSeal(change(lSeal, "settlement.journal.epoch", JsonPrimitive("")))
    }
    @Test fun A19_l_exact_witness() {
        val value = seal()
        assertEquals(SealTargetKind.NULL_NAMESPACE, value.kind)
        assertEquals(SealKey("A", PurgeScope.USER, null), value.key)
        assertEquals(RetiredNullSettlementEvidenceV2("lop", LifetimeId("origin"), FenceV1("B", "u2", "k2"),
            FenceV1("B", "u2", "k2"), JournalTargetV1("A", PurgeScope.USER, null)), value.settlement)
    }
    @Test fun A19_null_subject_preserved() {
        assertNull(seal(lSeal.replace("\"A\"", "null")).key.ownerUid)
    }
    @Test fun A19_null_current_owner_preserved() {
        assertNull(seal(lSeal.replace("\"B\"", "null")).settlement!!.before.ownerUid)
    }
    @Test fun A19_null_current_epochs_supported() {
        assertNull(seal(lSeal.replace("\"u2\"", "null").replace("\"k2\"", "null")).settlement!!.before.userAccessEpoch)
    }
    @Test fun A19_empty_owner_is_text() {
        assertEquals("", seal(lSeal.replace("\"A\"", "\"\"")).key.ownerUid)
    }
    @Test fun A21_same_axis_epoch_reuse_rejected() {
        rejectSeal(change(rSeal, "settlement.after.userAccessEpoch", JsonPrimitive("old")))
    }
    @Test fun A21_other_axis_epoch_reuse_preserved() {
        assertTrue(seal(change(rSeal, "settlement.after.krxCapabilityEpoch", JsonPrimitive("old"))).settlement is SettlementEvidenceV1)
    }
    @Test fun A20_v1_retired_namespace_preserved() {
        assertEquals(com.jay.fxi.data.entitlements.StoreOp.JOURNAL_RETIRED, (seal(rSeal).settlement as SettlementEvidenceV1).operation)
    }
    @Test fun A20_v1_current_null_preserved() {
        assertEquals(com.jay.fxi.data.entitlements.StoreOp.BEGIN_ROTATION, (seal(nSeal).settlement as SettlementEvidenceV1).operation)
    }
    @Test fun A19_v1_before_owner_still_required() {
        rejectSeal(change(nSeal, "settlement.before.ownerUid", JsonPrimitive("B")))
    }
    @Test fun A19_equal_empty_user_epochs_rejected() {
        rejectSeal(lSeal.replace("\"u2\"", "\"\""))
    }
    @Test fun A19_equal_empty_krx_epochs_rejected() {
        rejectSeal(lSeal.replace("\"k2\"", "\"\""))
    }
}
