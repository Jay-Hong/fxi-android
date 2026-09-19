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

import java.util.UUID

class RetiredNamespaceOwnerTest : RetiredNamespaceOwnerBase() {
    private suspend fun apply(s: RetiredNamespaceSettlement, initial: Preferences = raw(s)): Pair<CommandRef, ControlStoreResult.Confirmed> {
        seedR(s, initial)
        val c = registerR(s)
        val result = successR(executeR(c, context.copy(ownerUid = s.executor.ownerUid)), ConfirmedEffect.AppliedThisAttempt)
        assertLanded(c, s, initial)
        assertEquals(result.snapshot.record.original, disk())
        assertEquals(listOfNotNull(target(s).id, s.demandId), result.effectiveIds)
        val receipt = result.handoverSettlement!!
        assertEquals(HandoverSettlementTransition.RETIRED_NAMESPACE, receipt.transition)
        assertEquals(s.operationId, receipt.operationId)
        assertEquals(s.executor.originLifetimeId, receipt.originLifetimeId)
        assertEquals(s.before, receipt.before); assertEquals(s.before, receipt.after)
        assertEquals(mapOf(target(s).id to witness(s)), receipt.witnesses)
        assertEquals(mapOf(target(s).id to JournalObservation.Present), receipt.journal)
        assertEquals(s.demandId, receipt.demandId)
        assertEquals(if (s.demand == null) HandoverDemandObservation.NotRequired else HandoverDemandObservation.Present, receipt.demand)
        assertFalse(receipt.hasUninterpretable); assertFalse(receipt.hasUninterpretableMetadata)
        assertNull(o.control.checkpoint(c))
        val count = o.storage.writes
        successR(executeR(c, null), ConfirmedEffect.PostconditionConfirmed)
        assertEquals(count, o.storage.writes)
        return c to result
    }
    @Test fun G05_currentOwnerPreserved() = runReleaseTest { apply(spec()); Unit }
    @Test fun G05_departedOwnerPreserved() = runReleaseTest { apply(departed()); Unit }
    @Test fun G17_capabilityMinimumRequest() = runReleaseTest {
        apply(spec(target = node(NamespaceSettlementFixtures.krx), demand = request.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS))); Unit
    }
    @Test fun G17_nullCurrentOwnerRequest() = runReleaseTest {
        apply(spec(target = node(NamespaceSettlementFixtures.user.replace("\"A\"", "null")), fence = before.copy(ownerUid = null),
            exec = executor.copy(ownerUid = null), demand = request.copy(ownerUid = null))); Unit
    }
    @Test fun G17_nullDepartedOwnerNoRequest() = runReleaseTest {
        apply(spec(target = node(NamespaceSettlementFixtures.user.replace("\"A\"", "null")), did = null, demand = null)); Unit
    }
    @Test fun G17_departedCurrentOwnerNull() = runReleaseTest {
        apply(spec(fence = before.copy(ownerUid = null), exec = executor.copy(ownerUid = null), did = null, demand = null)); Unit
    }
    @Test fun G22_exactJournalAlreadyPresentStillSettles() = runReleaseTest {
        val s = spec(); apply(s, raw(s).toMutablePreferences().apply { this[PURGE_JOURNAL] = "A|u||USER" }); Unit
    }
    @Test fun G22_broadAndDuplicateJournalsPreserved() = runReleaseTest {
        val s = departed(); apply(s, raw(s).toMutablePreferences().apply { this[PURGE_JOURNAL] = "|||USER\n|||USER" }); Unit
    }
    @Test fun G18_allObligationsAndByteArrayPreserved() = runReleaseTest {
        val s = spec(); val source = raw(s).toMutablePreferences().apply {
            this[ControlStoreTestStorage.DEMAND] = "[${ControlObligationFixtures.request},${ControlObligationFixtures.guard}]"
            this[ControlStoreTestStorage.HOLD] = "  [ ${ControlObligationFixtures.hold} ]  "
            this[ControlStoreTestStorage.RECOVERY] = " [${ControlObligationFixtures.recovery}] "
            this[ControlStoreTestStorage.SEAL] = "[${NamespaceSettlementFixtures.user},${NamespaceSettlementFixtures.krx}]"
            this[READ_BARRIER] = 17
        }
        val (_, result) = apply(s, source)
        assertEquals(listOf("c"), result.handoverSettlement!!.remainingSeals.map { it.id })
        assertArrayEquals(source[blob], disk()[blob])
        assertEquals(17L, disk()[READ_BARRIER])
    }
    @Test fun G18_departedPreservesRawDemandText() = runReleaseTest {
        val s = departed(); val source = raw(s).toMutablePreferences().apply { this[ControlStoreTestStorage.DEMAND] = "  [ ${ControlObligationFixtures.request},${ControlObligationFixtures.guard} ] " }
        apply(s, source); assertEquals(source[ControlStoreTestStorage.DEMAND], disk()[ControlStoreTestStorage.DEMAND])
    }
    @Test fun G17_prepareFreezesIdsAndOrderAcrossRetry() = runReleaseTest {
        val s = spec(); seedR(s)
        var issued = 0
        val facade = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, (++issued).toLong()) })
        val c = facade.prepareRetiredNamespaceSettlement(s.target, before, executor, request)
        val input = (c.body as ControlCommandBody.SettleRetiredNamespace).input
        assertEquals(2, issued); assertEquals(UUID(0, 1).toString(), c.id)
        assertEquals(UUID(0, 2).toString(), input.demandId); assertSame(request, input.demand)
        o.storage.before = true
        assertTrue(controlTestTimeout("prepared first failure") { facade.execute(c, context) } is ControlStoreResult.Unconfirmed)
        successR(controlTestTimeout("prepared retry") { facade.execute(c, context) }, ConfirmedEffect.AppliedThisAttempt)
        assertEquals(2, issued); assertLanded(c, input, raw(s))
    }
    @Test fun G17_prepareDepartedIssuesOnlyOperation() = runReleaseTest {
        val s = departed(); seedR(s); var issued = 0
        val facade = ControlRecordStore(o.owner, ControlIdGenerator { UUID(0, (++issued).toLong()) })
        val c = facade.prepareRetiredNamespaceSettlement(s.target, s.before, s.executor, null)
        assertEquals(1, issued)
        val result = successR(controlTestTimeout("departed prepare") { facade.execute(c, context.copy(ownerUid = "B")) }, ConfirmedEffect.AppliedThisAttempt)
        assertEquals(HandoverDemandObservation.NotRequired, result.handoverSettlement!!.demand)
    }
    @Test fun A12_R_releaseRemainsUnsupported() = runReleaseTest {
        val (c, _) = apply(spec()); val source = disk(); val writes = o.storage.writes
        val result = controlTestTimeout("R release excluded") { o.control.releaseAfterConsumption(c) }
        assertEquals(ReleaseRejectionReason.UnsupportedCommandKind, (result as? ControlCommandReleaseResult.Rejected)?.reason)
        assertEquals(source, disk()); assertEquals(writes, o.storage.writes)
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
    }
}
