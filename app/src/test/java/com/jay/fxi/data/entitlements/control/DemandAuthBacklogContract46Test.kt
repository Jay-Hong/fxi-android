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
 * 5e-1 contract, forty-sixth file — the Z.ABA refusal on owner B (C32) with the rebind non-target conditions closed by the
 * builder-layer method of C39 (STEP3ZJ r5 판정: C32 premise incomplete). The refusal is the builder's rebind predicate
 * (DemandAuthPlan.kt:125–129 `InvalidRebind`), which the store rejects before any candidate (ControlLifecycle.kt:182; for
 * the same failed plan, which has no targets, LC:190 validDescriptor also refuses it — each bypass alone is masked by the
 * other. DemandAuthTransition.kt:24 also checks preparationFailure; bypassing these two guards is not claimed sufficient).
 * Role method: an independent vector of the rebind predicate (DemandAuthFacts.kt:115–119) — owner (Q11a) · needed (Q11b,
 * the row is not already on the executor's binding and origin) — is all true for owner A and false only at the owner for
 * owner B. Q11c (next intent ≥ old) holds by construction: no intent override is passed, so the builder hands the old
 * intent itself to the predicate (DemandAuthPlan.kt:128); it is not a vector item. The positive twin is the same stored
 * REQUEST re-bound through the store by owner A (Confirmed); then owner B's store refuses and keeps the row. Classification is a separate method (CLASSIFICATION_ONLY).
 */
class DemandAuthBacklogContract46Test {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun store(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c46 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c46 cleanup", 30000) { opened.forEach { it.close() } } }
    private fun row(raw: Preferences, id: String): ControlNode = (F.read(raw).locations(id).single().second as ControlEntryRead.Interpreted).original

    private val r = F.request(binding = 2, origin = LifetimeId("old"), order = 500)
    private val other = F.request(id = "other", owner = "B")
    private val bindingB = F.binding.copy(executor = F.binding.executor.copy(ownerUid = "B"), identity = IdentityV1("B", 2))
    private fun reb(old: DemandV1, b: LifecycleBinding) = listOf(
        "owner" to (old.ownerUid == b.executor.ownerUid),
        "needed" to !(old.binding == b.executor.binding && old.raisedAt.origin == b.executor.originLifetimeId))

    @Test fun Z_ABA_rebindOwner() = runReleaseTest {
        val old = demand(r)!!
        assertEquals("fixture: owner B's executor differs from A's only in the owner", F.binding.executor.copy(ownerUid = "B"), bindingB.executor)
        assertEquals("truth vector: owner A (twin)", emptySet<String>(), DemandAuthTruthVector.falses(reb(old, F.binding)))
        val a = store(F.raw(r, other))
        val confirmed = a.control.execute(a.control.prepareRebindRequests(listOf(r), F.binding, LifecycleOrderSource(F.life, 21)),
            F.context(F.runtime()))
        assertTrue("positive twin: owner A re-binds the same stored REQUEST, got $confirmed", confirmed is ControlStoreResult.Confirmed)
        assertEquals("truth vector: only the owner differs for owner B", setOf("owner"), DemandAuthTruthVector.falses(reb(old, bindingB)))
        val o = store(F.raw(r, other))
        controlTestTimeout("switch to B") { o.data.updateData { it.toMutablePreferences().apply { this[DataStoreAccessEpochStore.OWNER_UID] = "B" } } }
        val refused = o.control.execute(o.control.prepareRebindRequests(listOf(r), bindingB, LifecycleOrderSource(F.life, 21)),
            F.context(F.runtime(binding = bindingB, live = bindingB.identity)))
        assertTrue(F.eligible("Z.ABA.rebindOwner"), refused is ControlStoreResult.Rejected)
        assertEquals(F.atomic("Z.ABA.rebindOwner.rowKept"), r.toPayloadEntry(), row(o.raw(), "r").toPayloadEntry())
    }
    @Test fun Z_ABA_classification() = assertEquals("classification: owner B's rebind preparation failure", "InvalidRebind",
        DemandAuthPlan.rebind(listOf(r), bindingB, LifecycleOrderSource(F.life, 21)).preparationFailure)
}
