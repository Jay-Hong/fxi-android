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

class RetiredNullContinuationTest : RetiredNullOwnerBase() {
    @Test fun A05_confirmOnly() {
        val result = decide(only = true)
        negative(result, ConflictReason.TargetChanged)
    }
    @Test fun A05_previouslyConfirmed() {
        val result = decide(confirmed = true)
        negative(result, ConflictReason.TargetChanged)
    }
    @Test fun A05_ownApplied() {
        val s = spec(); val c = command(s)
        val source = raw(s).toMutablePreferences().apply { this[evidenceKey] = "[${RetiredNullFixtures.applied(c, s)}]" }
        negative(decide(s, source, c = c), ConflictReason.TargetChanged)
    }
    @Test fun G17_operationCollision() {
        val s = spec(); val c = command(s)
        val sibling = withWitness(s, node(nullUser.replace("\"s\"", "\"other\"")))
        val source = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(s.targets.single(), sibling) }
        negative(decide(s, source, c = c), ConflictReason.OperationIdCollision)
    }
    @Test fun A03_partialWitness() {
        val s = both(); val c = command(s)
        val source = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(withWitness(s, node(nullUser)), node(nullKrx)) }
        negative(decide(s, source, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A03_otherOperation() {
        val s = both(); val c = command(s)
        val source = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replaceFirst("l-operation", "other") }
        negative(decide(s, source, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A03_missing() {
        val s = both(); val c = command(s)
        val source = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(withWitness(s, node(nullUser))) }
        negative(decide(s, source, c = c), ConflictReason.TargetMissing)
    }
    @Test fun A03_extraOwn() {
        val s = spec(); val c = command(s)
        val source = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(withWitness(s), withWitness(s, node(nullKrx))) }
        negative(decide(s, source, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun A02_confirmationWitness() {
        val s = spec(); val c = command(s)
        val source = landed(c, s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = this[ControlStoreTestStorage.SEAL]!!.replace("l-origin", "other") }
        assertFalse(read(source).hasUninterpretable)
        negative(decide(s, source, c = c), RecoveryReason.InconsistentSettlement)
    }
    @Test fun G02_changedPreimage() = runReleaseTest {
        val changed = node(nullUser.replace("\"A\"", "\"C\""))
        val replacement = node(nullUser.replace("\"s\"", "\"replacement\""))
        val source = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(changed, replacement) }
        deniedL(source = source, reason = ConflictReason.TargetChanged)
    }
    @Test fun G17_otherApplied() = runReleaseTest {
        seedL(); val c = registerL(spec())
        controlTestTimeout("operation occupied by mutation") { o.data.edit { it[evidenceKey] = "[${ReclamationFixtures.mutation(command = c.id, lifetime = c.ownerTrackingLifetimeId.value)}]" } }
        val saved = disk(); val writes = o.storage.writes
        NamespaceSettlementFixtures.negative(executeL(c), ConflictReason.CommandEvidenceMismatch)
        assertTrue(history(c).observedApplied.get()); assertEquals(saved, disk()); assertEquals(writes, o.storage.writes)
    }
}
