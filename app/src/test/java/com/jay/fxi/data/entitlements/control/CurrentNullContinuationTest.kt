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
class CurrentNullContinuationTest : CurrentNullOwnerBase() {
    @Test fun A05_confirmOnly() {
        val s = spec(); val c = command(s)
        negative(decide(s, raw(s), only = true), ConflictReason.TargetChanged)
    }
    @Test fun A05_previouslyConfirmed() {
        val s = spec(); val c = command(s)
        negative(decide(s, raw(s), confirmed = true), ConflictReason.TargetChanged)
    }
    @Test fun A05_ownApplied() {
        val s = spec(); val c = command(s)
        negative(decide(s, raw(s).toMutablePreferences().apply { this[evidenceKey] = "[${CurrentNullFixtures.applied(c, s)}]" }, c = c), ConflictReason.TargetChanged)
    }
    @Test fun G17_operationCollision() {
        val s = spec(); val c = command(s)
        val sibling = NamespaceSettlementFixtures.withWitness(nullUser.replace("\"s\"", "\"other\""), witness(s))
        val p = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(s.nullTargets.first(), sibling) }
        negative(decide(s, p, c = c), ConflictReason.OperationIdCollision)
    }

    @Test fun A03_partialWitness() {
        val s = both(); val c = command(s)
        val complete = landed(c, s)
        val p = complete.toMutablePreferences().apply {
            val entries = read(complete).arrays.getValue(ControlKind.SEAL).entries.map { (it as ControlEntryRead.Interpreted).original }
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*entries.map {
                if (it.text("id") == FieldRead.Present("ks")) node(companionKrx) else it
            }.toTypedArray())
        }
        negative(decide(s, p, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A03_otherOperationOnOneTarget() {
        val s = both(); val c = command(s); val p = landed(c, s).toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replaceFirst("n-operation", "other")
        }
        negative(decide(s, p, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A03_missingNullWithApplied() {
        val s = both(); val c = command(s); val p = landed(c, s).toMutablePreferences().apply {
            val entries = read(this).arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>()
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*entries.filter { it.value.id != "s" }.map { it.original }.toTypedArray())
        }
        negative(decide(s, p, c = c), ConflictReason.TargetMissing)
    }
    @Test fun A03_missingCompanionDuringConfirmation() {
        val s = both(); val c = command(s); val p = landed(c, s).toMutablePreferences().apply {
            val entries = read(this).arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>()
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*entries.filter { it.value.id != "ks" }.map { it.original }.toTypedArray())
        }
        negative(decide(s, p, c = c), ConflictReason.TargetMissing)
    }
    @Test fun A03_missingCompanionWithoutAppliedDuringConfirmation() {
        val s = both(); val c = command(s); val p = landed(c, s).toMutablePreferences().apply {
            this[evidenceKey] = "[]"
            val entries = read(this).arrays.getValue(ControlKind.SEAL).entries.filterIsInstance<ControlEntryRead.Interpreted>()
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*entries.filter { it.value.id != "ks" }.map { it.original }.toTypedArray())
        }
        negative(decide(s, p, c = c), ConflictReason.TargetMissing)
    }
}
