package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, twenty-ninth file: the two integration scenarios of the obligation table's Z section, carried over from
 * DemandAuthIntegrationTest (:138 ownDecisionBeforeAfter, :148 AtoBtoA) with role assertions so that exact mutants can be
 * measured strictly. Premises are asserted first with the "fixture:" / "positive twin:" prefixes.
 *  - Z.ABA — on owner B a rebind of A's old REQUEST is refused and the row is kept; back on A the REQUEST is re-bound,
 *    then consumed, and the other owner's row is kept (design §4.4 r3:290–302).
 *  - Z.ownDecision — a query whose own decision rotated the user epoch (before/after fences differ) consumes the REQUEST
 *    and keeps the rotated epoch (design §4.2 r3:251).
 */
class DemandAuthBacklogContract29Test {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun owner(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c29 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c29 cleanup", 30000) { opened.forEach { it.close() } } }
    private fun row(raw: Preferences, id: String): ControlNode = (F.read(raw).locations(id).single().second as ControlEntryRead.Interpreted).original

    @Test fun Z_ABA_refusedThenRebound() = runReleaseTest {
        val r = F.request(binding = 2, origin = LifetimeId("old"), order = 500)
        val other = F.request(id = "other", owner = "B")
        val o = owner(F.raw(r, other))
        val b = F.binding.copy(executor = F.binding.executor.copy(ownerUid = "B"), identity = IdentityV1("B", 2))
        controlTestTimeout("switch to B") { o.data.updateData { it.toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "B" } } }
        val refused = o.control.execute(o.control.prepareRebindRequests(listOf(r), b, LifecycleOrderSource(F.life, 21)),
            F.context(F.runtime(binding = b, live = b.identity)))
        assertTrue(F.eligible("Z.ABA.refusedOnB"), refused is ControlStoreResult.Rejected)
        assertEquals(F.atomic("Z.ABA.rowKeptOnB"), r.toPayloadEntry(), row(o.raw(), "r").toPayloadEntry())
        controlTestTimeout("return to A") { o.data.updateData { it.toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "A" } } }
        val orders = LifecycleOrderSource(F.life, 21)
        val rebound = o.control.execute(o.control.prepareRebindRequests(listOf(r), F.binding, orders), F.context())
        assertTrue("positive twin: back on A the REQUEST re-binds", rebound is ControlStoreResult.Confirmed)
        val fresh = row((rebound as ControlStoreResult.Confirmed).snapshot.record.original, "r")
        val d = F.decision(q = F.query.copy(order = EventOrderV1(F.life, orders.issue(1)!!.value)))
        val result = o.control.execute(o.control.prepareSettleQuery(listOf(fresh), null, null, F.binding, d, orders),
            F.context(F.runtime(registrations = listOf(d.registration))))
        assertTrue(F.atomic("Z.ABA.consumedOnA"),
            result is ControlStoreResult.Confirmed && result.snapshot.record.locations("r").isEmpty())
        assertEquals(F.atomic("Z.ABA.otherKept"), other.toPayloadEntry(),
            row((result as ControlStoreResult.Confirmed).snapshot.record.original, "other").toPayloadEntry())
    }

    @Test fun Z_ownDecision_rotatedEpoch() = runReleaseTest {
        val after = F.fence.copy(userAccessEpoch = "rotated")
        val r = F.request()
        val source = F.raw(r).toMutablePreferences().apply { this[DataStoreAccessEpochStore.USER_EPOCH] = "rotated" }
        val d = F.decision(after = after, afterGeneration = 6, namespace = ConfirmedControlSnapshot(F.read(source)))
        assertNotEquals("fixture: the decision's before and after fences differ", d.acceptedBeforeFence, d.confirmedAfterFence)
        assertEquals("fixture: the stored epoch is the decision's after epoch", "rotated", source[DataStoreAccessEpochStore.USER_EPOCH])
        val o = owner(source)
        val result = o.control.execute(o.control.prepareSettleQuery(listOf(r), null, null, F.binding, d, LifecycleOrderSource(F.life, 21)),
            F.context(F.runtime(generation = 6)))
        assertTrue(F.atomic("Z.ownDecision.consumed"),
            result is ControlStoreResult.Confirmed && result.snapshot.record.locations("r").isEmpty())
        assertEquals(F.atomic("Z.ownDecision.epochKept"), "rotated",
            (result as ControlStoreResult.Confirmed).snapshot.record.original[DataStoreAccessEpochStore.USER_EPOCH])
    }
}
