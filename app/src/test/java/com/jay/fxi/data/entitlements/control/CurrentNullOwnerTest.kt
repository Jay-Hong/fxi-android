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
import java.util.UUID
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal

class CurrentNullOwnerTest : CurrentNullOwnerBase() {
    private suspend fun apply(s: CurrentNullSettlement, initial: Preferences = raw(s)): Pair<CommandRef, ControlStoreResult.Confirmed> {
        seedN(s, initial); val c = registerN(s)
        val result = successN(executeN(c, context.copy(ownerUid = s.executor.ownerUid)), ConfirmedEffect.AppliedThisAttempt)
        assertLanded(c, s, initial)
        assertEquals(result.snapshot.record.original, disk())
        val fixed = CurrentNullFixtures.ordered(s).map { (ControlObligations.read(ControlKind.SEAL, it) as ControlEntryRead.Interpreted).value as SealV1 }
        assertEquals(fixed.map { it.id } + s.demandId, result.effectiveIds)
        val receipt = result.handoverSettlement!!
        assertEquals(HandoverSettlementTransition.CURRENT_NULL, receipt.transition)
        assertEquals(s.operationId, receipt.operationId); assertEquals(s.executor.originLifetimeId, receipt.originLifetimeId)
        assertEquals(s.before, receipt.before); assertEquals(witness(s).after, receipt.after)
        assertEquals(fixed.associate { it.id to witness(s, it) }, receipt.witnesses)
        assertEquals(fixed.associate { it.id to JournalObservation.Present }, receipt.journal)
        assertEquals(s.demandId, receipt.demandId); assertEquals(HandoverDemandObservation.Present, receipt.demand)
        assertFalse(receipt.hasUninterpretable); assertFalse(receipt.hasUninterpretableMetadata)
        assertNull(o.control.checkpoint(c))
        val count = o.storage.writes
        successN(executeN(c, null), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(count, o.storage.writes)
        return c to result
    }
    @Test fun G23_zeroCompanions() = runReleaseTest { apply(spec()); Unit }
    @Test fun G23_oneCompanion() = runReleaseTest { apply(spec(companions = listOf(node(companionUser)))); Unit }
    @Test fun G23_twoCompanions() = runReleaseTest {
        val (_, result) = apply(both())
        assertEquals(listOf("s", "us", "c", "ks", "n-demand"), result.effectiveIds)
        assertEquals(1, read(disk()).arrays.getValue(ControlKind.DEMAND).entries.size)
    }
    @Test fun G09_beforeEpochNull() = runReleaseTest { apply(spec(fence = before.copy(userAccessEpoch = null))); Unit }
    @Test fun G09_alreadyAllocatedStillRotates() = runReleaseTest { apply(spec()); assertNotEquals("u2", disk()[USER_EPOCH]) }
    @Test fun G10_capabilityOnlyPreservesUser() = runReleaseTest {
        apply(spec(target = node(nullKrx), companions = listOf(node(companionKrx)), demand = request.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS)))
        assertEquals("u2", disk()[USER_EPOCH]); assertEquals(true, disk()[MAY_CONTAIN_PREMIUM])
    }
    @Test fun G17_nullOwnerRequest() = runReleaseTest {
        apply(spec(target = node(nullUser.replace("\"A\"", "null")), fence = before.copy(ownerUid = null),
            exec = executor.copy(ownerUid = null), demand = request.copy(ownerUid = null))); Unit
    }
    @Test fun G22_exactNullJournalNotDuplicated() = runReleaseTest {
        val s = spec(); apply(s, raw(s).toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|||USER" }); Unit
    }
    @Test fun G22_broadAndDuplicateJournalPreserved() = runReleaseTest {
        val s = both(); apply(s, raw(s).toMutablePreferences().apply { this[PURGE_JOURNAL] = "|||USER\n|||USER" }); Unit
    }
    @Test fun G18_allObligationsAndBytesPreserved() = runReleaseTest {
        val s = spec(); val p = raw(s).toMutablePreferences().apply {
            this[ControlStoreTestStorage.DEMAND] = "[${ControlObligationFixtures.request},${ControlObligationFixtures.guard}]"
            this[ControlStoreTestStorage.HOLD] = "  [ ${ControlObligationFixtures.hold} ]  "
            this[ControlStoreTestStorage.RECOVERY] = " [${ControlObligationFixtures.recovery}] "
            this[ControlStoreTestStorage.SEAL] = "[$nullUser,$nullKrx]"
            this[READ_BARRIER] = 17
        }
        val (_, result) = apply(s, p)
        assertEquals(listOf("c"), result.handoverSettlement!!.remainingSeals.map { it.id })
        assertArrayEquals(p[blob], disk()[blob]); assertEquals(17L, disk()[READ_BARRIER])
    }
    @Test fun G17_prepareFreezesEverything() = runReleaseTest {
        val s = both(); seedN(s); var issued = 0
        val facade = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, (++issued).toLong()) })
        val mutable = s.nullTargets.toMutableList()
        val c = facade.prepareCurrentNullSettlement(mutable, read(disk()), before, executor, request)
        mutable.clear()
        val fixed = (c.body as ControlCommandBody.RotateAndSettleCurrentNull).input
        assertEquals(4, issued); assertEquals(UUID(0, 1).toString(), c.id)
        assertEquals(UUID(0, 2).toString(), fixed.demandId); assertSame(request, fixed.demand)
        assertEquals(UUID(0, 3).toString(), fixed.newUserEpoch); assertEquals(UUID(0, 4).toString(), fixed.newKrxEpoch)
        assertEquals(listOf("s", "us", "c", "ks"), fixed.targets.map { it.seal.id })
        o.storage.before = true
        assertTrue(controlTestTimeout("N prepare failure") { facade.execute(c, context) } is ControlStoreResult.Unconfirmed)
        successN(controlTestTimeout("N prepare retry") { facade.execute(c, context) }, ConfirmedEffect.AppliedThisAttempt)
        assertEquals(4, issued); assertLanded(c, fixed, raw(s))
    }
    private suspend fun excluded(extra: String) {
        val s = spec(); val p = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser,$extra]" }
        seedN(s, p)
        val c = o.control.prepareCurrentNullSettlement(s.nullTargets, read(p), before, executor, request)
        val fixed = (c.body as ControlCommandBody.RotateAndSettleCurrentNull).input
        assertTrue("G23: excluded namespace must not be captured as companion", fixed.companions.isEmpty())
        val result = successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        assertLanded(c, fixed, p); assertEquals(1, result.handoverSettlement!!.remainingSeals.size)
    }
    @Test fun G23_excludesOtherOwner() = runReleaseTest { excluded(companionUser.replace("\"A\"", "\"B\"")) }
    @Test fun G23_excludesPastEpoch() = runReleaseTest { excluded(companionUser.replace("u2", "older")) }
    @Test fun G23_excludesOtherAxis() = runReleaseTest { excluded(companionKrx) }
    @Test fun A12_releaseExcluded() = runReleaseTest {
        val (c, _) = apply(both()); val source = disk(); val writes = o.storage.writes
        val result = controlTestTimeout("N release") { o.control.releaseAfterConsumption(c) }
        assertEquals(ReleaseRejectionReason.UnsupportedCommandKind, (result as? ControlCommandReleaseResult.Rejected)?.reason)
        assertEquals(source, disk()); assertEquals(writes, o.storage.writes); assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
    }
    @Test fun A11_reopenReclamationPreservesN() = runReleaseTest {
        apply(both()); o.close(); val next = open(file); val source = disk(); val writes = next.storage.writes
        controlTestTimeout("N excluded from old lifetime reclamation") { next.control.reclaimPreviousLifetimeEvidence() }
        assertEquals(source, disk()); assertEquals(writes, next.storage.writes)
    }
    private fun lateAdd() = o.control.prepare(o.control.addition(ControlKind.SEAL) { id ->
        literal(nullUser); set("id", ControlScalar.Text(id))
    })
    @Test fun F04_simpleAllocationAllowsLateNullAdd() = runReleaseTest {
        val s = spec(fence = before.copy(userAccessEpoch = null)); val source = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[]" }
        seedN(s, source); val late = lateAdd()
        controlTestTimeout("simple allocation") { o.owner.bindOwner("A") }
        val allocated = disk()[USER_EPOCH]; assertNotNull(allocated)
        val add = controlTestTimeout("late NULL after allocation") { o.control.execute(late) }
        assertTrue("F04: late NULL addition after allocation must be confirmed", add is ControlStoreResult.Confirmed)
        val seal = read(disk()).arrays.getValue(ControlKind.SEAL).entries.single() as ControlEntryRead.Interpreted
        assertNull((seal.value as SealV1).settlement); assertEquals(allocated, disk()[USER_EPOCH])
        val current = disk(); val fence = FenceV1("A", current[USER_EPOCH], current[KRX_EPOCH])
        val c = o.control.prepareCurrentNullSettlement(listOf(seal.original), read(current), fence, executor, request)
        successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        assertNotEquals(allocated, disk()[USER_EPOCH]); assertTrue(disk()[PURGE_JOURNAL]!!.contains("A|||USER"))
    }
    @Test fun F04_lateAddAfterNFormsNewActiveSeal() = runReleaseTest {
        seedN(); val late = lateAdd(); val c = registerN(spec())
        successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        val settled = read(disk()).locations("s").single().second.payload()
        val add = controlTestTimeout("late NULL after N") { o.control.execute(late) } as ControlStoreResult.Confirmed
        val newId = add.effectiveIds.single(); assertNotEquals("s", newId)
        val now = read(disk()); assertEquals(settled, now.locations("s").single().second.payload())
        val active = now.locations(newId).single().second as ControlEntryRead.Interpreted
        assertNull((active.value as SealV1).settlement)
        val fence = FenceV1("A", disk()[USER_EPOCH], disk()[KRX_EPOCH])
        val freshRequest = request.copy(raisedAt = EventOrderV1(life, 24))
        val next = o.control.prepareCurrentNullSettlement(listOf(active.original), now, fence, executor, freshRequest)
        successN(executeN(next), ConfirmedEffect.AppliedThisAttempt)
        assertNotEquals(fence.userAccessEpoch, disk()[USER_EPOCH])
        assertEquals(settled, read(disk()).locations("s").single().second.payload())
    }
    @Test fun G23_settledNamespaceExcluded() = runReleaseTest {
        val prior = RetiredNamespaceFixtures.spec(target = node(companionUser), fence = before.copy(userAccessEpoch = "retired-after"))
        val settled = NamespaceSettlementFixtures.withWitness(companionUser, RetiredNamespaceFixtures.witness(prior))
        val p = raw().toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = NamespaceSettlementFixtures.jsonArray(node(nullUser), settled) }
        seedN(raw = p)
        val c = o.control.prepareCurrentNullSettlement(spec().nullTargets, read(p), before, executor, request)
        val fixed = (c.body as ControlCommandBody.RotateAndSettleCurrentNull).input
        assertTrue("G23: excluded namespace must not be captured as companion", fixed.companions.isEmpty())
        successN(executeN(c), ConfirmedEffect.AppliedThisAttempt)
        assertLanded(c, fixed, p)
        assertEquals(settled.toPayloadEntry(), read(disk()).locations("us").single().second.payload())
    }
    @Test fun G23_currentEpochNullHasNoCompanion() = runReleaseTest {
        val s = spec(fence = before.copy(userAccessEpoch = null))
        val p = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.SEAL] = "[$nullUser,$companionUser]" }
        seedN(s, p)
        val c = o.control.prepareCurrentNullSettlement(s.nullTargets, read(p), s.before, executor, request)
        val fixed = (c.body as ControlCommandBody.RotateAndSettleCurrentNull).input
        assertTrue(fixed.companions.isEmpty())
        successN(executeN(c), ConfirmedEffect.AppliedThisAttempt); assertLanded(c, fixed, p)
    }
    @Test fun G07_prepareIssuesOnlySelectedEpoch() = runReleaseTest {
        val s = spec(target = node(nullKrx)); seedN(s); var issued = 0
        val facade = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, (++issued).toLong()) })
        val c = facade.prepareCurrentNullSettlement(s.nullTargets, read(disk()), before, executor, request)
        val fixed = (c.body as ControlCommandBody.RotateAndSettleCurrentNull).input
        assertEquals(3, issued); assertNull(fixed.newUserEpoch); assertNotNull(fixed.newKrxEpoch)
        successN(controlTestTimeout("N one-axis issuance") { facade.execute(c, context) }, ConfirmedEffect.AppliedThisAttempt)
        assertEquals(3, issued); assertLanded(c, fixed, raw(s))
    }
}
