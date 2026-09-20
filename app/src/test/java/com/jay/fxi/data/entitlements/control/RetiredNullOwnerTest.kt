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

import java.util.UUID
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal

class RetiredNullOwnerTest : RetiredNullOwnerBase() {
    private suspend fun apply(s: RetiredNullSettlement, initial: Preferences = raw(s)): Pair<CommandRef, ControlStoreResult.Confirmed> {
        seedL(s, initial); val c = registerL(s)
        val result = successL(executeL(c, context.copy(ownerUid = s.executor.ownerUid)), ConfirmedEffect.AppliedThisAttempt)
        assertLanded(c, s, initial)
        assertEquals(result.snapshot.record.original, disk())
        val fixed = RetiredNullFixtures.ordered(s).map { (ControlObligations.read(ControlKind.SEAL, it) as ControlEntryRead.Interpreted).value as SealV1 }
        assertEquals(fixed.map { it.id }, result.effectiveIds)
        val receipt = result.handoverSettlement!!
        assertEquals(HandoverSettlementTransition.RETIRED_NULL, receipt.transition)
        assertEquals(s.operationId, receipt.operationId); assertEquals(s.executor.originLifetimeId, receipt.originLifetimeId)
        assertEquals(s.before, receipt.before); assertEquals(s.before, receipt.after)
        assertEquals(fixed.associate { it.id to witness(s, it) }, receipt.witnesses)
        assertEquals(fixed.associate { it.id to JournalObservation.Present }, receipt.journal)
        assertNull(receipt.demandId); assertEquals(HandoverDemandObservation.NotRequired, receipt.demand)
        assertFalse(receipt.hasUninterpretable); assertFalse(receipt.hasUninterpretableMetadata)
        assertNull(o.control.checkpoint(c))
        val count = o.storage.writes
        successL(executeL(c, null), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(count, o.storage.writes)
        return c to result
    }
    @Test fun G15_departedUser() = runReleaseTest { apply(spec()); Unit }
    @Test fun G15_departedCapability() = runReleaseTest { apply(spec(target = node(nullKrx))); Unit }
    @Test fun G15_bothOrdered() = runReleaseTest {
        val (_, result) = apply(both())
        assertEquals(listOf("s", "c"), result.effectiveIds)
        assertEquals("[]", disk()[ControlStoreTestStorage.DEMAND])
    }
    @Test fun G15_nullSubject() = runReleaseTest { apply(spec(target = node(nullUser.replace("\"A\"", "null")))); Unit }
    @Test fun G15_nullCurrent() = runReleaseTest { apply(spec(fence = before.copy(ownerUid = null), exec = executor.copy(ownerUid = null))); Unit }
    @Test fun G15_bothNullIsCurrent() = runReleaseTest {
        val s = spec(target = node(nullUser.replace("\"A\"", "null")), fence = before.copy(ownerUid = null), exec = executor.copy(ownerUid = null))
        deniedL(s, context = context.copy(ownerUid = null), reason = RejectionReason.InvalidRequest("SubjectOwnerIsCurrent"))
    }
    @Test fun G16_nullUserEpochPreserved() = runReleaseTest { apply(spec(fence = before.copy(userAccessEpoch = null))); assertNull(disk()[USER_EPOCH]) }
    @Test fun G16_nullCapabilityEpochPreserved() = runReleaseTest { apply(spec(fence = before.copy(krxCapabilityEpoch = null))); assertNull(disk()[KRX_EPOCH]) }
    @Test fun G16_bothEpochsNullPreserved() = runReleaseTest { apply(spec(fence = before.copy(userAccessEpoch = null, krxCapabilityEpoch = null))); Unit }
    @Test fun G22_exactJournalNotDuplicated() = runReleaseTest {
        val s = spec(); apply(s, raw(s).toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|||USER" }); Unit
    }
    @Test fun G22_broadAndDuplicateJournalPreserved() = runReleaseTest {
        val s = both(); apply(s, raw(s).toMutablePreferences().apply { this[PURGE_JOURNAL] = "|||USER\n|||USER" }); Unit
    }
    @Test fun G18_allObligationsAndBytesPreserved() = runReleaseTest {
        val s = spec(); val p = raw(s).toMutablePreferences().apply {
            this[ControlStoreTestStorage.DEMAND] = " [ ${ControlObligationFixtures.request}, ${ControlObligationFixtures.guard} ] "
            this[ControlStoreTestStorage.HOLD] = "  [ ${ControlObligationFixtures.hold} ]  "
            this[ControlStoreTestStorage.RECOVERY] = " [${ControlObligationFixtures.recovery}] "
            this[ControlStoreTestStorage.SEAL] = "[$nullUser,$nullKrx]"
            this[READ_BARRIER] = 17
        }
        val (_, result) = apply(s, p)
        assertEquals(listOf("c"), result.handoverSettlement!!.remainingSeals.map { it.id })
        assertArrayEquals(p[blob], disk()[blob]); assertEquals(17L, disk()[READ_BARRIER])
    }
    @Test fun G17_prepareIssuesOnlyOperationAndFreezesTargets() = runReleaseTest {
        val s = both(); seedL(s); var issued = 0
        val facade = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, (++issued).toLong()) })
        val mutable = s.targets.toMutableList()
        val c = facade.prepareRetiredNullSettlement(mutable, before, executor)
        mutable.clear()
        val fixed = (c.body as ControlCommandBody.SettleRetiredNull).input
        assertEquals(1, issued); assertEquals(UUID(0, 1).toString(), c.id)
        assertSame(before, fixed.before); assertSame(executor, fixed.executor)
        assertEquals(listOf("s", "c"), fixed.effectiveIds)
        assertTrue(runCatching { (fixed.ordered as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (fixed.effectiveIds as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        o.storage.before = true
        assertTrue(controlTestTimeout("L prepare failure") { facade.execute(c, context) } is ControlStoreResult.Unconfirmed)
        successL(controlTestTimeout("L prepare retry") { facade.execute(c, context) }, ConfirmedEffect.AppliedThisAttempt)
        assertEquals(1, issued); assertLanded(c, fixed, raw(s))
    }
    @Test fun A12_releaseExcluded() = runReleaseTest {
        val (c, _) = apply(both())
        rejected(c, ReleaseRejectionReason.UnsupportedCommandKind)
    }
    @Test fun A11_reopenReclamationPreservesL() = runReleaseTest {
        apply(both()); o.close(); val next = open(file); val source = disk(); val writes = next.storage.writes
        controlTestTimeout("L previous lifetime preservation") { next.control.reclaimPreviousLifetimeEvidence() }
        assertEquals(source, disk()); assertEquals(writes, next.storage.writes)
    }
    private fun lateAdd() = o.control.prepare(o.control.addition(ControlKind.SEAL) { id ->
        literal(nullUser); set("id", ControlScalar.Text(id))
    })
    @Test fun F06_legacyDepartureThenLateNullAddThenL() = runReleaseTest {
        val initial = raw(spec()).toMutablePreferences().apply { this[OWNER_UID] = "A"; this[ControlStoreTestStorage.SEAL] = "[]" }
        seedL(raw = initial); val late = lateAdd()
        controlTestTimeout("legacy A to B") { o.owner.bindOwner("B") }
        val current = disk(); assertEquals("B", current[OWNER_UID])
        val add = controlTestTimeout("late A NULL") { o.control.execute(late) }
        assertTrue("F06: late A Add must be stored", add is ControlStoreResult.Confirmed)
        val entry = read(disk()).arrays.getValue(ControlKind.SEAL).entries.single() as ControlEntryRead.Interpreted
        assertNull("F06: departure alone must not settle NULL", (entry.value as SealV1).settlement)
        val fence = FenceV1("B", current[USER_EPOCH], current[KRX_EPOCH])
        val c = o.control.prepareRetiredNullSettlement(listOf(entry.original), fence, executor)
        val source = disk(); val fixed = (c.body as ControlCommandBody.SettleRetiredNull).input
        successL(executeL(c), ConfirmedEffect.AppliedThisAttempt); assertLanded(c, fixed, source)
        assertEquals(current[OWNER_UID], disk()[OWNER_UID]); assertEquals(current[USER_EPOCH], disk()[USER_EPOCH])
        assertEquals(current[KRX_EPOCH], disk()[KRX_EPOCH]); assertEquals(current[MAY_CONTAIN_PREMIUM], disk()[MAY_CONTAIN_PREMIUM])
        assertEquals(current[MAY_CONTAIN_KRX], disk()[MAY_CONTAIN_KRX])
        assertTrue(disk()[PURGE_JOURNAL]!!.split('\n').contains("A|||USER"))
        assertTrue(disk()[PURGE_JOURNAL]!!.contains(current[PURGE_JOURNAL].orEmpty()))
    }
    @Test fun G15_abaRejectsLAndFreshBindingNSettles() = runReleaseTest {
        seedL(raw = raw().toMutablePreferences().apply {
            this[OWNER_UID] = "A"; this[ControlStoreTestStorage.SEAL] = "[]"
            this[ControlStoreTestStorage.DEMAND] = "[${ControlObligationFixtures.request}]"
        })
        val late = lateAdd()
        controlTestTimeout("legacy A B A") { o.owner.bindOwner("B"); o.owner.bindOwner("A") }
        assertTrue(controlTestTimeout("late A NULL after ABA") { o.control.execute(late) } is ControlStoreResult.Confirmed)
        val entry = read(disk()).arrays.getValue(ControlKind.SEAL).entries.single() as ControlEntryRead.Interpreted
        assertNull((entry.value as SealV1).settlement)
        val fence = FenceV1("A", disk()[USER_EPOCH], disk()[KRX_EPOCH])
        val exec = SettlementExecutor("A", 19, LifetimeId("fresh-A"))
        val ctx = AttemptContext("A", 19, exec.originLifetimeId, false, false)
        val l = o.control.prepareRetiredNullSettlement(listOf(entry.original), fence, exec)
        val saved = disk(); val writes = o.storage.writes
        NamespaceSettlementFixtures.negative(executeL(l, ctx), RejectionReason.InvalidRequest("SubjectOwnerIsCurrent"))
        assertEquals(saved, disk()); assertEquals(writes, o.storage.writes)
        val demand = SettlementDemand("A", 19, EventOrderV1(exec.originLifetimeId, 50), com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM)
        val n = o.control.prepareCurrentNullSettlement(listOf(entry.original), read(saved), fence, exec, demand)
        val result = controlTestTimeout("ABA N") { o.control.execute(n, ctx) }
        assertTrue(result is ControlStoreResult.Confirmed); assertNotEquals(fence.userAccessEpoch, disk()[USER_EPOCH])
        val current = read(disk())
        assertEquals(2, current.arrays.getValue(ControlKind.DEMAND).entries.size)
        assertEquals(node(ControlObligationFixtures.request).toPayloadEntry(), current.locations("d").single().second.payload())
        val freshId = (n.body as ControlCommandBody.RotateAndSettleCurrentNull).input.demandId
        val request = current.locations(freshId).single().second as ControlEntryRead.Interpreted
        assertEquals(19L, (request.value as DemandV1).binding)
        assertEquals(exec.originLifetimeId, (request.value as DemandV1).raisedAt.origin)
    }
}
