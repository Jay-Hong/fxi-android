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

class RetiredNullBoundaryTest {
    @Test fun G06_type_OWNER_UID() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[intPreferencesKey(OWNER_UID.name)] = 7 }
        assertEquals("G06_type_OWNER_UID: raw field type", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun G06_type_USER_EPOCH() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[intPreferencesKey(USER_EPOCH.name)] = 7 }
        assertEquals("G06_type_USER_EPOCH: raw field type", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun G06_type_KRX_EPOCH() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[intPreferencesKey(KRX_EPOCH.name)] = 7 }
        assertEquals("G06_type_KRX_EPOCH: raw field type", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun G06_type_MAY_CONTAIN_PREMIUM() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[intPreferencesKey(MAY_CONTAIN_PREMIUM.name)] = 7 }
        assertEquals("G06_type_MAY_CONTAIN_PREMIUM: raw field type", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun G06_type_MAY_CONTAIN_KRX() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[intPreferencesKey(MAY_CONTAIN_KRX.name)] = 7 }
        assertEquals("G06_type_MAY_CONTAIN_KRX: raw field type", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun G06_type_TEARDOWN_OWED_FOR() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[intPreferencesKey(TEARDOWN_OWED_FOR.name)] = 7 }
        assertEquals("G06_type_TEARDOWN_OWED_FOR: raw field type", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun G06_type_PURGE_JOURNAL() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[intPreferencesKey(PURGE_JOURNAL.name)] = 7 }
        assertEquals("G06_type_PURGE_JOURNAL: raw field type", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun G06_domain_USER_EPOCH() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[USER_EPOCH] = "" }
        assertEquals("G06_domain_USER_EPOCH: raw epoch domain", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun G06_domain_KRX_EPOCH() {
        assertNull(transition.rawProblem(raw()))
        val wrong = raw().toMutablePreferences().apply { this[KRX_EPOCH] = "" }
        assertEquals("G06_domain_KRX_EPOCH: raw epoch domain", RecoveryReason.UnreadableEpochState, transition.rawProblem(wrong))
    }
    @Test fun A13_schema1() {
        val source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SCHEMA] = 1; remove(ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)); remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE)) }
        assertEquals("A13_schema1: migration required", RecoveryReason.ControlSchemaMigrationRequired, RetiredNamespaceSettlementTransition.recordProblem(read(source)))
    }
}
