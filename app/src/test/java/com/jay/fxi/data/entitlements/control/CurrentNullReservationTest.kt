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
import com.jay.fxi.data.entitlements.PendingPurge

class CurrentNullReservationTest : CurrentNullOwnerBase() {
    private suspend fun reservation(kind: ControlKind, row: String, interpreted: Boolean = true) {
        val p = raw().toMutablePreferences().apply {
            this[ControlRecordKeys.payload(kind)] = if (kind == ControlKind.SEAL) "[$nullUser,$row]" else "[$row]"
        }
        val read = read(p)
        assertEquals("fixture classification", interpreted, read.arrays.getValue(kind).entries.last() is ControlEntryRead.Interpreted)
        val shared = NamespaceSettlementTransition(ControlPayloadCodec())
        assertTrue(shared.epochsAreUnused(listOf(newKrx), read, emptyList()))
        assertFalse("G08: reserved epoch must not be reported unused", shared.epochsAreUnused(listOf(newUser), read, emptyList()))
        if (interpreted) deniedN(source = p, reason = RejectionReason.InvalidRequest("EpochNotFresh"))
    }
    private suspend fun journal(line: String) {
        val p = raw().toMutablePreferences().apply { this[PURGE_JOURNAL] = line }
        deniedN(source = p, reason = RejectionReason.InvalidRequest("EpochNotFresh"))
    }
    @Test fun G08_journalUser() = runReleaseTest { journal("B|$newUser||USER") }
    @Test fun G08_journalKrx() = runReleaseTest { journal("B||$newUser|CAPABILITY") }
    @Test fun G08_sealKey() = runReleaseTest { reservation(ControlKind.SEAL, companionUser.replace("u2", newUser)) }
    @Test fun G08_recoveryTarget() = runReleaseTest {
        reservation(ControlKind.RECOVERY_INTENT, ControlObligationFixtures.recovery.replace("\"targetEpoch\":null", "\"targetEpoch\":\"$newUser\""))
    }
    @Test fun G08_hold_query_user() = runReleaseTest {
        reservation(ControlKind.HOLD, ControlObligationFixtures.hold.replace("\"userAccessEpoch\":\"u\"", "\"userAccessEpoch\":\"$newUser\""))
    }
    @Test fun G08_hold_query_krx() = runReleaseTest {
        reservation(ControlKind.HOLD, ControlObligationFixtures.hold.replace("\"krxCapabilityEpoch\":\"k\"", "\"krxCapabilityEpoch\":\"$newUser\""))
    }
    @Test fun G08_hold_topic_user() = runReleaseTest {
        reservation(ControlKind.HOLD, ControlObligationFixtures.topicHold.replace("\"userAccessEpoch\":\"u\"", "\"userAccessEpoch\":\"$newUser\""))
    }
    @Test fun G08_hold_topic_krx() = runReleaseTest {
        reservation(ControlKind.HOLD, ControlObligationFixtures.topicHold.replace("\"krxCapabilityEpoch\":\"k\"", "\"krxCapabilityEpoch\":\"$newUser\""))
    }
    @Test fun G08_v1_before_user() = runReleaseTest {
        val prior = RetiredNamespaceFixtures.spec()
        val w = RetiredNamespaceFixtures.witness(prior).copy(before = RetiredNamespaceFixtures.before.copy(userAccessEpoch = newUser))
        val row = NamespaceSettlementFixtures.withWitness(NamespaceSettlementFixtures.user.replace("\"s\"", "\"past\""), w)
        reservation(ControlKind.SEAL, row.toPayloadEntry().fields.toString())
    }
    @Test fun G08_v1_before_krx() = runReleaseTest {
        val prior = RetiredNamespaceFixtures.spec()
        val w = RetiredNamespaceFixtures.witness(prior).copy(before = RetiredNamespaceFixtures.before.copy(krxCapabilityEpoch = newUser))
        val row = NamespaceSettlementFixtures.withWitness(NamespaceSettlementFixtures.user.replace("\"s\"", "\"past\""), w)
        reservation(ControlKind.SEAL, row.toPayloadEntry().fields.toString())
    }
    @Test fun G08_v1_after_user() = runReleaseTest {
        val prior = RetiredNamespaceFixtures.spec()
        val w = RetiredNamespaceFixtures.witness(prior).copy(after = RetiredNamespaceFixtures.before.copy(userAccessEpoch = newUser))
        val row = NamespaceSettlementFixtures.withWitness(NamespaceSettlementFixtures.user.replace("\"s\"", "\"past\""), w)
        reservation(ControlKind.SEAL, row.toPayloadEntry().fields.toString())
    }
    @Test fun G08_v1_after_krx() = runReleaseTest {
        val prior = RetiredNamespaceFixtures.spec()
        val w = RetiredNamespaceFixtures.witness(prior).copy(after = RetiredNamespaceFixtures.before.copy(krxCapabilityEpoch = newUser))
        val row = NamespaceSettlementFixtures.withWitness(NamespaceSettlementFixtures.user.replace("\"s\"", "\"past\""), w)
        reservation(ControlKind.SEAL, row.toPayloadEntry().fields.toString())
    }
    @Test fun G08_lV2_before_user_scannerBoundary() = runReleaseTest {
        val row = """{"id":"past","kind":"NULL_NAMESPACE","ownerUid":"B","axis":"USER","settlement":{"version":2,"kind":"RETIRED_NULL","before":{"userAccessEpoch":"$newUser"}}}"""
        reservation(ControlKind.SEAL, row, interpreted = false)
    }
    @Test fun G08_lV2_before_krx_scannerBoundary() = runReleaseTest {
        val row = """{"id":"past","kind":"NULL_NAMESPACE","ownerUid":"B","axis":"USER","settlement":{"version":2,"kind":"RETIRED_NULL","before":{"krxCapabilityEpoch":"$newUser"}}}"""
        reservation(ControlKind.SEAL, row, interpreted = false)
    }
    @Test fun G08_lV2_after_user_scannerBoundary() = runReleaseTest {
        val row = """{"id":"past","kind":"NULL_NAMESPACE","ownerUid":"B","axis":"USER","settlement":{"version":2,"kind":"RETIRED_NULL","after":{"userAccessEpoch":"$newUser"}}}"""
        reservation(ControlKind.SEAL, row, interpreted = false)
    }
    @Test fun G08_lV2_after_krx_scannerBoundary() = runReleaseTest {
        val row = """{"id":"past","kind":"NULL_NAMESPACE","ownerUid":"B","axis":"USER","settlement":{"version":2,"kind":"RETIRED_NULL","after":{"krxCapabilityEpoch":"$newUser"}}}"""
        reservation(ControlKind.SEAL, row, interpreted = false)
    }
    @Test fun G08_opaque_SEAL_scannerBoundary() = runReleaseTest {
        reservation(ControlKind.SEAL, """{"id":"opaque","future":[{"epoch":"$newUser"}]}""", interpreted = false)
    }
    @Test fun G08_opaque_DEMAND_scannerBoundary() = runReleaseTest {
        reservation(ControlKind.DEMAND, """{"id":"opaque","future":[{"epoch":"$newUser"}]}""", interpreted = false)
    }
    @Test fun G08_opaque_HOLD_scannerBoundary() = runReleaseTest {
        reservation(ControlKind.HOLD, """{"id":"opaque","future":[{"epoch":"$newUser"}]}""", interpreted = false)
    }
    @Test fun G08_opaque_RECOVERY_INTENT_scannerBoundary() = runReleaseTest {
        reservation(ControlKind.RECOVERY_INTENT, """{"id":"opaque","future":[{"epoch":"$newUser"}]}""", interpreted = false)
    }
    @Test fun G08_v1JournalEpoch_scannerBoundary() = runReleaseTest {
        reservation(ControlKind.SEAL, """{"id":"past","settlement":{"journal":{"epoch":"$newUser"}}}""", interpreted = false)
    }
    @Test fun G08_lV2BothFencesInterpreted() = runReleaseTest {
        val row = HandoverFormatFixtures.lSeal.replace("u2", newUser)
        assertTrue(row.contains(newUser))
        reservation(ControlKind.SEAL, row)
    }
}
