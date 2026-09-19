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
class CurrentNullCandidateTest : CurrentNullOwnerBase() {
    @Test fun G19_nullWitness() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser]" }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_journal() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { remove(PURGE_JOURNAL) }
        assertNotEquals(good, broken)
        assertFalse("G19_journal: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_request() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[]" }
        assertNotEquals(good, broken)
        assertFalse("G19_request: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_applied() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[evidenceKey] = "[]" }
        assertNotEquals(good, broken)
        assertFalse("G19_applied: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_appliedDemand() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[evidenceKey] = this[evidenceKey]!!.replace("n-demand", "other") }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_userEpoch() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[USER_EPOCH] = "other" }
        assertNotEquals(good, broken)
        assertFalse("G19_userEpoch: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_krxEpoch() {
        val s = spec(target = node(nullKrx)); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[KRX_EPOCH] = "other" }
        assertNotEquals(good, broken)
        assertFalse("G19_krxEpoch: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_userMarker() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = true }
        assertNotEquals(good, broken)
        assertFalse("G19_userMarker: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_krxMarker() {
        val s = spec(target = node(nullKrx)); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[MAY_CONTAIN_KRX] = true }
        assertNotEquals(good, broken)
        assertFalse("G19_krxMarker: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_OWNER_UID() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[OWNER_UID] = "B" }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_MAY_CONTAIN_KRX() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[MAY_CONTAIN_KRX] = false }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_TEARDOWN_OWED_FOR() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_READ_BARRIER() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[READ_BARRIER] = 99L }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G18_preserve_blob() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[blob] = byteArrayOf(4) }
        assertNotEquals(good, broken)
        assertFalse("G18_preserve_blob: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G17_candidateRequest_owner() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = this[ControlStoreTestStorage.DEMAND]!!.replace("\"A\"", "\"B\"") }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G17_candidateRequest_binding() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = this[ControlStoreTestStorage.DEMAND]!!.replace("9", "10") }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G17_candidateRequest_origin() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = this[ControlStoreTestStorage.DEMAND]!!.replace("n-origin", "other") }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G17_candidateRequest_order() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = this[ControlStoreTestStorage.DEMAND]!!.replace("23", "24") }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G17_candidateRequest_intent() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = this[ControlStoreTestStorage.DEMAND]!!.replace("FORCE_PREMIUM", "FORCE_ENTITLEMENTS") }
        assertNotEquals(good, broken)
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_extraEvidence() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[evidenceKey] = this[evidenceKey]!!.dropLast(1) + "," + this[evidenceKey]!!.drop(1).replace("n-operation", "other-operation") }
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
    @Test fun G19_immutableCompanion() {
        val s = spec(companions = listOf(node(companionUser))); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("\"epoch\":\"u2\"", "\"epoch\":\"older\"") }
        assertNotEquals(good, broken)
        assertFalse("G19_immutableCompanion: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_witnessOrigin() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("n-origin", "other") }
        assertNotEquals(good, broken)
        assertFalse("G19_witnessOrigin: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_exactOperationSetBoundary() {
        val s = spec(); val c = command(s)
        val prior = NamespaceSettlementFixtures.withWitness(nullUser.replace("\"s\"", "\"extra\""), witness(s))
        val initial = raw(s).toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(node(nullUser), prior)
        }
        // Candidate boundary only: a pre-existing extra own-operation seal must not be adopted.
        // Other rows are unchanged, all named targets/witnesses and the appended Applied are exact.
        val candidate = landed(c, s, initial)
        assertFalse(read(candidate).blocksProtectedAdmission)
        assertFalse("G19_exactOperationSetBoundary: altered candidate must be rejected", transition.validCandidate(c, s, read(initial), candidate))
        assertTrue(transition.validCandidate(c, s, read(raw(s)), landed(c, s)))
    }
    @Test fun G19_companionWitnessRequired() {
        val s = spec(companions = listOf(node(companionUser))); val c = command(s)
        val good = landed(c, s); val broken = good.toMutablePreferences().apply {
            val entries = read(good).arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>()
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*entries.map {
                if (it.value.id == "us") node(companionUser) else it.original
            }.toTypedArray())
        }
        assertTrue(transition.validCandidate(c, s, read(raw(s)), good))
        assertFalse(transition.validCandidate(c, s, read(raw(s)), broken))
    }
}
