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

class RetiredNullWriterTest {
    @Test fun A19_version() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_version: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_kind() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_kind: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_operationId() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_operationId: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_origin() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_origin: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_before() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_before: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_after() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_after: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_journalOwner() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_journalOwner: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_journalAxis() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_journalAxis: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_journalEpoch() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_journalEpoch: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_fenceOwner() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_fenceOwner: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_fenceUser() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_fenceUser: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_fenceKrx() {
        val s = spec()
        val actual = transition.settle(s.targets.single(), witness(s))
        assertEquals("A19_fenceKrx: exact L wire", withWitness(s).toPayloadEntry(), actual?.toPayloadEntry())
    }
    @Test fun A19_required_version() {
        val s = spec()
        assertEquals("A19_required_version: required wire field", withWitness(s).toPayloadEntry(), transition.settle(s.targets.single(), witness(s))?.toPayloadEntry())
    }
    @Test fun A19_required_kind() {
        val s = spec()
        assertEquals("A19_required_kind: required wire field", withWitness(s).toPayloadEntry(), transition.settle(s.targets.single(), witness(s))?.toPayloadEntry())
    }
    @Test fun A19_required_operationId() {
        val s = spec()
        assertEquals("A19_required_operationId: required wire field", withWitness(s).toPayloadEntry(), transition.settle(s.targets.single(), witness(s))?.toPayloadEntry())
    }
    @Test fun A19_required_originLifetimeId() {
        val s = spec()
        assertEquals("A19_required_originLifetimeId: required wire field", withWitness(s).toPayloadEntry(), transition.settle(s.targets.single(), witness(s))?.toPayloadEntry())
    }
    @Test fun A19_required_before() {
        val s = spec()
        assertEquals("A19_required_before: required wire field", withWitness(s).toPayloadEntry(), transition.settle(s.targets.single(), witness(s))?.toPayloadEntry())
    }
    @Test fun A19_required_after() {
        val s = spec()
        assertEquals("A19_required_after: required wire field", withWitness(s).toPayloadEntry(), transition.settle(s.targets.single(), witness(s))?.toPayloadEntry())
    }
    @Test fun A19_required_epoch() {
        val s = spec()
        assertEquals("A19_required_epoch: required wire field", withWitness(s).toPayloadEntry(), transition.settle(s.targets.single(), witness(s))?.toPayloadEntry())
    }
    @Test fun A19_unknownKey() {
        val s = spec()
        assertEquals("A19_unknownKey: exact field set", withWitness(s).toPayloadEntry(), transition.settle(s.targets.single(), witness(s))?.toPayloadEntry())
    }
    @Test fun A19_rejectBeforeOwner() {
        val s = spec(); val w = witness(s)
        assertNotNull(transition.settle(s.targets.single(), w))
        assertNull("A19_rejectBeforeOwner: static writer rejection", transition.settle(s.targets.single(), w.copy(before = w.before.copy(ownerUid = "A"))))
    }
    @Test fun A19_rejectAfterOwner() {
        val s = spec(); val w = witness(s)
        assertNotNull(transition.settle(s.targets.single(), w))
        assertNull("A19_rejectAfterOwner: static writer rejection", transition.settle(s.targets.single(), w.copy(after = w.after.copy(ownerUid = "C"))))
    }
    @Test fun A19_rejectAfterUser() {
        val s = spec(); val w = witness(s)
        assertNotNull(transition.settle(s.targets.single(), w))
        assertNull("A19_rejectAfterUser: static writer rejection", transition.settle(s.targets.single(), w.copy(after = w.after.copy(userAccessEpoch = "u3"))))
    }
    @Test fun A19_rejectAfterKrx() {
        val s = spec(); val w = witness(s)
        assertNotNull(transition.settle(s.targets.single(), w))
        assertNull("A19_rejectAfterKrx: static writer rejection", transition.settle(s.targets.single(), w.copy(after = w.after.copy(krxCapabilityEpoch = "k3"))))
    }
    @Test fun A19_rejectCurrentSubject() {
        val s = spec(); val w = witness(s)
        assertNotNull(transition.settle(s.targets.single(), w))
        assertNull("A19_rejectCurrentSubject: static writer rejection", transition.settle(s.targets.single(), w.copy(before = w.before.copy(ownerUid = "A"), after = w.after.copy(ownerUid = "A"))))
    }
    @Test fun A19_rejectJournalOwner() {
        val s = spec(); val w = witness(s)
        assertNotNull(transition.settle(s.targets.single(), w))
        assertNull("A19_rejectJournalOwner: static writer rejection", transition.settle(s.targets.single(), w.copy(journal = w.journal.copy(ownerUid = "B"))))
    }
    @Test fun A19_rejectJournalAxis() {
        val s = spec(); val w = witness(s)
        assertNotNull(transition.settle(s.targets.single(), w))
        assertNull("A19_rejectJournalAxis: static writer rejection", transition.settle(s.targets.single(), w.copy(journal = w.journal.copy(axis = PurgeScope.CAPABILITY))))
    }
    @Test fun A19_rejectJournalEpoch() {
        val s = spec(); val w = witness(s)
        assertNotNull(transition.settle(s.targets.single(), w))
        assertNull("A19_rejectJournalEpoch: static writer rejection", transition.settle(s.targets.single(), w.copy(journal = w.journal.copy(epoch = "old"))))
    }
    @Test fun A19_rejectNamespace() {
        val s = spec(); val w = witness(s)
        assertNull("A19_rejectNamespace: NULL required", transition.settle(node(ControlObligationFixtures.seal), w.copy(journal = w.journal.copy(ownerUid = null))))
    }
}
