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

class RetiredNullCandidateTest : RetiredNullOwnerBase() {
    @Test fun G19_witnessOrigin() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("l-origin", "other") }
        assertNotEquals(good, broken)
        assertFalse("G19_witnessOrigin: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_journal() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { remove(PURGE_JOURNAL) }
        assertNotEquals(good, broken)
        assertFalse("G19_journal: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_applied() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[evidenceKey] = "[]" }
        assertNotEquals(good, broken)
        assertFalse("G19_applied: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_extraEvidence() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[evidenceKey] = this[evidenceKey]!!.dropLast(1) + "," + this[evidenceKey]!!.drop(1).replace("l-operation", "other") }
        assertNotEquals(good, broken)
        assertFalse("G19_extraEvidence: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_extraSeal() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.dropLast(1) + ",$nullKrx]" }
        assertNotEquals(good, broken)
        assertFalse("G19_extraSeal: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G17_unexpectedDemand() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[${ControlObligationFixtures.request}]" }
        assertNotEquals(good, broken)
        assertFalse("G17_unexpectedDemand: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_missingWitness() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser]" }
        assertNotEquals(good, broken)
        assertFalse("G19_missingWitness: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_missingSeal() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[]" }
        assertNotEquals(good, broken)
        assertFalse("G19_missingSeal: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_extraOwnSeal() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.dropLast(1) + "," + this[ControlStoreTestStorage.SEAL]!!.drop(1).replace("\"s\"", "\"other\"") }
        assertNotEquals(good, broken)
        assertFalse("G19_extraOwnSeal: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_immutable() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("\"A\"", "\"C\"") }
        assertNotEquals(good, broken)
        assertFalse("G19_immutable: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_OWNER_UID() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[OWNER_UID] = "other" }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_OWNER_UID: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_USER_EPOCH() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[USER_EPOCH] = "other" }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_USER_EPOCH: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_KRX_EPOCH() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[KRX_EPOCH] = "other" }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_KRX_EPOCH: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_MAY_CONTAIN_PREMIUM() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_MAY_CONTAIN_PREMIUM: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_MAY_CONTAIN_KRX() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[MAY_CONTAIN_KRX] = false }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_MAY_CONTAIN_KRX: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_TEARDOWN_OWED_FOR() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "other" }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_TEARDOWN_OWED_FOR: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_READ_BARRIER() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[READ_BARRIER] = 99L }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_READ_BARRIER: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_blob() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[blob] = byteArrayOf(4) }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_blob: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_DEMAND() {
        val s = spec(); val c = command(s); val key = ControlRecordKeys.payload(ControlPayloadKey.DEMAND)
        val initial = raw(s).toMutablePreferences().apply { this[key] = " [ ${ControlObligationFixtures.request}, ${ControlObligationFixtures.guard} ] " }
        val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[key] = "[]" }
        assertFalse("G18_preserve_DEMAND: existing obligation must remain", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_HOLD() {
        val s = spec(); val c = command(s); val key = ControlRecordKeys.payload(ControlPayloadKey.HOLD)
        val initial = raw(s).toMutablePreferences().apply { this[key] = " [ ${ControlObligationFixtures.hold} ] " }
        val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[key] = "[]" }
        assertFalse("G18_preserve_HOLD: existing obligation must remain", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_RECOVERY_INTENT() {
        val s = spec(); val c = command(s); val key = ControlRecordKeys.payload(ControlPayloadKey.RECOVERY_INTENT)
        val initial = raw(s).toMutablePreferences().apply { this[key] = " [ ${ControlObligationFixtures.recovery} ] " }
        val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[key] = "[]" }
        assertFalse("G18_preserve_RECOVERY_INTENT: existing obligation must remain", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_operationSet() {
        val s = spec(); val c = command(s)
        val good = raw(s)
        assertTrue(transition.validCandidate(c, s, read(good), landed(c, s, good)))
        val initial = raw(s).toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(node(nullUser), withWitness(s, node(nullKrx)))
        }
        val candidate = landed(c, s, initial)
        assertFalse("G19_operationSet: entire own operation seal set must match", transition.validCandidate(c, s, read(initial), candidate))
    }
    @Test fun G19_recordGate() {
        val s = spec(); val c = command(s)
        val good = raw(s)
        assertTrue(transition.validCandidate(c, s, read(good), landed(c, s, good)))
        val initial = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[{}]" }
        assertFalse("G19_recordGate: candidate must be fully interpretable", transition.validCandidate(c, s, read(initial), landed(c, s, initial)))
    }
}
