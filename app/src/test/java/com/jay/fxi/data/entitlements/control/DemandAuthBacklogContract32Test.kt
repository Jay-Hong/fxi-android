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
 * 5e-1 contract, thirty-second file: the Z.ABA scenario of C29 with the non-target conditions of the refusal on owner B
 * confirmed before the refusal role (design r3:683 "each negative fixture first asserts the non-target conditions").
 * Before the refusal: the stored row is A's old REQUEST, owner B's binding differs from A's only in the owner, and a
 * positive twin — the same REQUEST, intent and binding with owner A — is re-bindable. Only the owner then differs.
 */
class DemandAuthBacklogContract32Test {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun owner(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c32 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c32 cleanup", 30000) { opened.forEach { it.close() } } }
    private fun row(raw: Preferences, id: String): ControlNode = (F.read(raw).locations(id).single().second as ControlEntryRead.Interpreted).original

    @Test fun Z_ABA_premisedRefusal() = runReleaseTest {
        val r = F.request(binding = 2, origin = LifetimeId("old"), order = 500)
        val other = F.request(id = "other", owner = "B")
        val o = owner(F.raw(r, other))
        val b = F.binding.copy(executor = F.binding.executor.copy(ownerUid = "B"), identity = IdentityV1("B", 2))
        assertEquals("fixture: the stored row is A's old REQUEST", r.toPayloadEntry(), row(o.raw(), "r").toPayloadEntry())
        assertEquals("fixture: owner B's executor differs from A's only in the owner", F.binding.executor.copy(ownerUid = "B"), b.executor)
        assertTrue("positive twin: the same REQUEST, intent and binding with owner A is re-bindable",
            DemandAuthBoundary.rebind(demand(r)!!, F.binding, demand(r)!!.intent))
        controlTestTimeout("switch to B") { o.data.updateData { it.toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "B" } } }
        val refused = o.control.execute(o.control.prepareRebindRequests(listOf(r), b, LifecycleOrderSource(F.life, 21)),
            F.context(F.runtime(binding = b, live = b.identity)))
        assertTrue(F.eligible("Z.ABA.p.refusedOnB"), refused is ControlStoreResult.Rejected)
        assertEquals(F.atomic("Z.ABA.p.rowKeptOnB"), r.toPayloadEntry(), row(o.raw(), "r").toPayloadEntry())
    }
}
