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

class RetiredNamespaceCandidateTest : RetiredNamespaceOwnerBase() {
    @Test fun G19_missingWitness() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[${NamespaceSettlementFixtures.user}]" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_missingJournal() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { remove(PURGE_JOURNAL) }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_missingRequest() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[]" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_missingApplied() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[evidenceKey] = "[]" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_wrongWitness() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = good[ControlStoreTestStorage.SEAL]!!.replace("r-origin", "other") }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_wrongApplied() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[evidenceKey] = good[evidenceKey]!!.replace("r-demand", "other") }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_departedUnexpectedRequest() {
        val s = departed(); val c = command(s); val initial = raw(s); val good = landed(c, s)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[${ControlObligationFixtures.request}]" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_owner() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[OWNER_UID] = "changed" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_user() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[USER_EPOCH] = "changed" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_krx() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[KRX_EPOCH] = "changed" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_premiumMarker() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[MAY_CONTAIN_PREMIUM] = false }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_krxMarker() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[MAY_CONTAIN_KRX] = false }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_teardown() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "C" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_externalBytes() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[blob] = byteArrayOf(8) }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_barrier() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[READ_BARRIER] = 42L }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_hold() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.HOLD] = "[${ControlObligationFixtures.hold}]" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun A14_preserve_recovery() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.RECOVERY] = "[${ControlObligationFixtures.recovery}]" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G17_exactRequest_id() {
        val s = spec(); val actual = DemandV1("r-demand", "A", 9, RefreshIntent.FORCE_PREMIUM, EventOrderV1(life, 23))
        assertTrue(transition.demandMatches(actual, s))
        assertFalse(transition.demandMatches(actual.copy(id = "other"), s))
    }
    @Test fun G17_exactRequest_owner() {
        val s = spec(); val actual = DemandV1("r-demand", "A", 9, RefreshIntent.FORCE_PREMIUM, EventOrderV1(life, 23))
        assertTrue(transition.demandMatches(actual, s))
        assertFalse(transition.demandMatches(actual.copy(ownerUid = "B"), s))
    }
    @Test fun G17_exactRequest_binding() {
        val s = spec(); val actual = DemandV1("r-demand", "A", 9, RefreshIntent.FORCE_PREMIUM, EventOrderV1(life, 23))
        assertTrue(transition.demandMatches(actual, s))
        assertFalse(transition.demandMatches(actual.copy(binding = 10), s))
    }
    @Test fun G17_exactRequest_origin() {
        val s = spec(); val actual = DemandV1("r-demand", "A", 9, RefreshIntent.FORCE_PREMIUM, EventOrderV1(life, 23))
        assertTrue(transition.demandMatches(actual, s))
        assertFalse(transition.demandMatches(actual.copy(raisedAt = actual.raisedAt.copy(origin = LifetimeId("other"))), s))
    }
    @Test fun G17_exactRequest_order() {
        val s = spec(); val actual = DemandV1("r-demand", "A", 9, RefreshIntent.FORCE_PREMIUM, EventOrderV1(life, 23))
        assertTrue(transition.demandMatches(actual, s))
        assertFalse(transition.demandMatches(actual.copy(raisedAt = actual.raisedAt.copy(value = 24)), s))
    }
    @Test fun G17_exactRequest_intent() {
        val s = spec(); val actual = DemandV1("r-demand", "A", 9, RefreshIntent.FORCE_PREMIUM, EventOrderV1(life, 23))
        assertTrue(transition.demandMatches(actual, s))
        assertFalse(transition.demandMatches(actual.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS), s))
    }
    @Test fun G17_invalidOp() {
        assertEquals("InvalidOperationId", transition.invalidInput(spec(op = "")))
    }
    @Test fun G17_emptyDemandId() {
        assertEquals("InvalidDemand", transition.invalidInput(spec(did = "")))
    }
    @Test fun G17_sameDemandId() {
        assertEquals("InvalidDemand", transition.invalidInput(spec(did = "s")))
    }
    @Test fun A02_immutableSealFields() {
        val fixed = spec().target
        val changed = node(NamespaceSettlementFixtures.user.replace("\"u\"", "\"other\""))
        val compare = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(compare.immutableSealMatches(fixed, fixed))
        assertFalse(compare.immutableSealMatches(changed, fixed))
    }
    @Test fun G19_extraSiblingEvidence() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val sibling = """{"version":2,"commandId":"extra","ownerTrackingLifetimeId":"${c.ownerTrackingLifetimeId.value}","kind":"MUTATIONS","targets":[{"index":0,"kind":"SEAL","id":"other","joined":false,"written":true}]}"""
        val broken = good.toMutablePreferences().apply { this[evidenceKey] = this[evidenceKey]!!.dropLast(1) + "," + sibling + "]" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_extraSiblingSeal() {
        val s = spec(); val c = command(s); val initial = raw(s); val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val broken = good.toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.dropLast(1) + ",${NamespaceSettlementFixtures.krx}]" }
        assertFalse(transition.validCandidate(c, s, read(initial), broken))
    }
    @Test fun G19_siblingRequestChanged() {
        val s = spec(); val c = command(s)
        val initial = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "[${ControlObligationFixtures.request}]" }
        val good = landed(c, s, initial)
        assertTrue(transition.validCandidate(c, s, read(initial), good))
        val old = ControlObligationFixtures.request
        val replacement = old.replace("\"binding\":3", "\"binding\":4")
        assertNotEquals(old, replacement)
        val bad = good.toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = this[ControlStoreTestStorage.DEMAND]!!.replace(old, replacement) }
        assertNotEquals(good, bad)
        assertFalse(transition.validCandidate(c, s, read(initial), bad))
    }
}
