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

import com.jay.fxi.data.entitlements.RefreshIntent

/** Short real-file sequences. Long capacity scenarios remain outside control.*. */
class HandoverCombinedTest : RetiredNullOwnerBase() {
    private val r = node("""{"id":"rs","kind":"NAMESPACE","ownerUid":"B","axis":"USER","epoch":"u-old"}""")
    private val n = node("""{"id":"ns","kind":"NULL_NAMESPACE","ownerUid":"B","axis":"CAPABILITY"}""")
    private val companion = node("""{"id":"nc","kind":"NAMESPACE","ownerUid":"B","axis":"CAPABILITY","epoch":"k2"}""")
    private fun initial() = raw(both()).toMutablePreferences().apply {
        this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(node(nullUser), node(nullKrx), r, n, companion)
        this[ControlStoreTestStorage.DEMAND] = " [ ${ControlObligationFixtures.request}, ${ControlObligationFixtures.guard} ] "
        this[ControlStoreTestStorage.HOLD] = " [ ${ControlObligationFixtures.hold} ] "
        this[ControlStoreTestStorage.RECOVERY] = " [ ${ControlObligationFixtures.recovery} ] "
    }
    private suspend fun fence() = FenceV1(disk()[OWNER_UID], disk()[USER_EPOCH], disk()[KRX_EPOCH])
    private suspend fun prepare(kind: String): CommandRef = when (kind) {
        "R" -> o.control.prepareRetiredNamespaceSettlement(r, fence(), executor,
            SettlementDemand("B", 9, EventOrderV1(life, 41), RefreshIntent.FORCE_PREMIUM))
        "N" -> o.control.prepareCurrentNullSettlement(listOf(n), read(disk()), fence(), executor,
            SettlementDemand("B", 9, EventOrderV1(life, 42), RefreshIntent.FORCE_ENTITLEMENTS))
        "L" -> o.control.prepareRetiredNullSettlement(both().targets, fence(), executor)
        else -> error(kind)
    }
    private suspend fun step(kind: String): CommandRef {
        val c = prepare(kind); val source = disk()
        val expected = when (val input = (c.body as ControlCommandBody.Handover).input) {
            is RetiredNamespaceSettlement -> RetiredNamespaceFixtures.landed(c, input, source).toMutablePreferences().apply {
                // The single-command R fixture replaces evidence; mixed sequences must append it.
                this[evidenceKey] = source[evidenceKey]!!.dropLast(1) + (if (source[evidenceKey] == "[]") "" else ",") +
                    RetiredNamespaceFixtures.applied(c, input) + "]"
            }
            is CurrentNullSettlement -> CurrentNullFixtures.landed(c, input, source)
            is RetiredNullSettlement -> landed(c, input, source)
        }.toMutablePreferences().apply { remove(READ_BARRIER) }
        successL(executeL(c), ConfirmedEffect.AppliedThisAttempt)
        assertEquals("combined: exact independent atomic record for $kind", expected, disk().toMutablePreferences().apply { remove(READ_BARRIER) })
        return c
    }
    private suspend fun sequence(first: String, second: String, third: String) {
        seedL(raw = initial())
        val one = step(first); val two = step(second); val three = step(third)
        val saved = disk(); val writes = o.storage.writes
        successL(executeL(one, null), ConfirmedEffect.PostconditionConfirmed)
        successL(executeL(two, null), ConfirmedEffect.PostconditionConfirmed)
        successL(executeL(three, null), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(saved, disk()); assertEquals(writes, o.storage.writes)
        assertTrue(read(saved).arrays.getValue(ControlKind.SEAL).entries.all { ((it as ControlEntryRead.Interpreted).value as SealV1).settlement != null })
        assertEquals(4, read(saved).arrays.getValue(ControlKind.DEMAND).entries.size)
    }
    @Test fun R_N_L() = runReleaseTest { sequence("R", "N", "L") }
    @Test fun L_N_R() = runReleaseTest { sequence("L", "N", "R") }
    @Test fun N_R_L() = runReleaseTest { sequence("N", "R", "L") }

    @Test fun A11_realRNLWithMutationReleaseAndPreviousRotationReclamation() = runReleaseTest {
        seedL(raw = initial())
        val r = step("R"); val n = step("N"); val l = step("L")
        val refs = listOf(r, n, l)
        rejected(r, ReleaseRejectionReason.UnsupportedCommandKind)
        rejected(n, ReleaseRejectionReason.UnsupportedCommandKind)
        rejected(l, ReleaseRejectionReason.UnsupportedCommandKind)
        val m = confirmed(); released(m)
        assertEquals(setOf(r.id, n.id, l.id), rows(disk()).map { (ControlNode.of(it.fields).text("commandId") as FieldRead.Present).value }.toSet())
        // Leave one ordinary mutation for previous-lifetime evidence reclamation.
        val oldMutation = confirmed()
        val legacySeal = ReclamationFixtures.settled("legacy-s", operation = "legacy-op")
        controlTestTimeout("mixed legacy rotation seed") { o.data.edit {
            it[ControlStoreTestStorage.SEAL] = it[ControlStoreTestStorage.SEAL]!!.dropLast(1) + ",$legacySeal]"
            it[evidenceKey] = it[evidenceKey]!!.dropLast(1) + ",${ReclamationFixtures.rotation("legacy-op", ids = listOf("legacy-s"))}]"
        } }
        val source = disk()
        val expected = source.toMutablePreferences().apply {
            this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(*read(source).arrays.getValue(ControlKind.SEAL).entries
                .filter { (it as ControlEntryRead.Interpreted).value.id != "legacy-s" }.map { (it as ControlEntryRead.Interpreted).original }.toTypedArray())
            this[evidenceKey] = NamespaceSettlementFixtures.jsonArray(*rows(source).filter {
                val id = (ControlNode.of(it.fields).text("commandId") as FieldRead.Present).value
                id != oldMutation.id && id != "legacy-op"
            }.map { ControlNode.of(it.fields) }.toTypedArray())
        }
        o.close(); val next = open(file)
        assertTrue(controlTestTimeout("mixed previous reclamation") { next.control.reclaimPreviousLifetimeEvidence() } is ControlEvidenceReclamationResult.Confirmed)
        assertEquals("A11: only previous MUTATIONS and ROTATION evidence and legacy seal are removed", expected, disk())
        val writes = next.storage.writes
        assertTrue(controlTestTimeout("previous R") { next.control.confirmPrevious(r) } is ControlStoreResult.Confirmed)
        assertTrue(controlTestTimeout("previous N") { next.control.confirmPrevious(n) } is ControlStoreResult.Confirmed)
        assertTrue(controlTestTimeout("previous L") { next.control.confirmPrevious(l) } is ControlStoreResult.Confirmed)
        assertEquals(expected, disk()); assertEquals(writes, next.storage.writes)
        assertTrue(refs.all { ControlCommandTracking.forOwner(next.owner).findPrepared(it) == null })
    }
    @Test fun A17_unlandedLThenNThenFreshLDoesNotResolveOldRef() = runReleaseTest {
        seedL(raw = initial()); val old = prepare("L"); o.storage.before = true
        assertTrue(executeL(old) is ControlStoreResult.Unconfirmed)
        step("N")
        val source = disk(); val writes = o.storage.writes
        NamespaceSettlementFixtures.negative(executeL(old), ConflictReason.TargetChanged)
        assertEquals(source, disk()); assertEquals(writes, o.storage.writes)
        step("L"); step("R")
        assertEquals(setOf(old), tracking.snapshot())
        rejected(old, ReleaseRejectionReason.UnsupportedCommandKind)
    }
    @Test fun A09_landedLReturnFailureThenNAndRestartConfirmsHistoricalWitness() = runReleaseTest {
        seedL(raw = initial()); val l = prepare("L"); val input = (l.body as ControlCommandBody.SettleRetiredNull).input
        val source = disk(); o.storage.afterScope = true
        assertTrue(executeL(l) is ControlStoreResult.Unconfirmed); assertLanded(l, input, source)
        step("N"); step("R"); val saved = disk()
        o.close(); val next = open(file)
        val result = controlTestTimeout("mixed failure restart") { next.control.confirmPrevious(l) }
        assertTrue(result is ControlStoreResult.Confirmed)
        assertEquals(ConfirmedEffect.PostconditionConfirmed, (result as ControlStoreResult.Confirmed).effect)
        assertEquals(input.before, result.handoverSettlement!!.before)
        assertEquals(saved, disk())
    }
}
