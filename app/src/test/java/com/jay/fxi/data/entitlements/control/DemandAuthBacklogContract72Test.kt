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
 * 5e-1 contract, seventy-second file — Z.C builder side with the refusal boundary named (STEP3ZK r16 판정 ④, criterion B):
 * writer · event · design sub-condition → exact builder defect → actual refusal boundary → stored record unchanged.
 * Eight writer events run through the real store (ControlStoreTestStorage): REBIND_REQUESTS, SETTLE_QUERY, UPDATE_AUTH
 * Answer / Initialize / Caller / Recovery (C49 fixtures), END_AUTH_BINDING with and without a replacement (C33 · C42 fixtures).
 * Per event:
 *  - `<e>_confirms` (KILL companion): the unaltered writer reaches Confirmed — a builder defect that is refused fails here.
 *  - three DEFENSE methods, each vacuous when the store confirms and otherwise naming one boundary exactly:
 *    `<e>_refusedAtPreparation`  LC:182 — the registered plan's preparationFailure is non-null, the result is
 *                                Rejected InvalidRequest(<that failure>), and the stored Preferences are unchanged;
 *    `<e>_refusedAtDescriptor`   LC:190 — no preparation failure, Rejected InvalidRequest(InvalidLifecycleDescriptor), unchanged;
 *    `<e>_refusedAtValidator`    DT:46  — no preparation failure, Rejected InvalidRequest(RequiredDecisionEffectMissing), unchanged
 *                                (within DemandAuth with a prepared plan this detail is returned only at DT:46).
 * A measured claim is always a pair on the same mutant: `<e>_confirms` STRICT_KILL + exactly one named DEFENSE passing.
 * A DEFENSE method alone is vacuous under a confirming store and is never counted. "Unchanged" compares the stored
 * Preferences (payload strings included), not the serialized file bytes. Builder exceptions, mutants that do not fire on
 * these fixtures, and confirmed-but-wrong outputs are outside these methods and are disposed of separately.
 */
class DemandAuthBacklogContract72Test {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun store(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c72 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c72 cleanup", 30000) { opened.forEach { it.close() } } }

    private val orders get() = LifecycleOrderSource(F.life, 21)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val sibling = F.request(id = "sibling", owner = "B")
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    private val closure get() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
    private val callerFact get() = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding,
        LifecycleOrderGrant(F.life, 20, 1, 0, 21), RefreshIntent.FORCE_PREMIUM, F.now)
    private val recoveryFact get() = LifecycleRecovery("recovery", F.identity, F.life, 11, 21, 1)

    private class Run(val raw: Preferences, val runtime: DemandAuthRuntime, val prepare: (ControlRecordStore) -> CommandRef)
    private fun rebind(): Run { val old = F.request(binding = 2, order = 50)
        return Run(F.raw(old, sibling), F.runtime()) { it.prepareRebindRequests(listOf(old), F.binding, orders) } }
    private fun settle(): Run { val g = F.guard(F.auth.copy(authStopped = false)); val r = F.request()
        val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        return Run(F.raw(g, r, sibling), F.runtime()) { it.prepareSettleQuery(listOf(r), g, null, F.binding, d, orders) } }
    private fun answer(): Run { val g = F.guard()
        return Run(F.raw(g, sibling), F.runtime()) { it.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Answer(pending), orders) } }
    private fun initialize(): Run =
        Run(F.raw(sibling), F.runtime()) { it.prepareUpdateAuth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders) }
    private fun caller(): Run { val g = F.guard(); val f = callerFact
        return Run(F.raw(g, sibling), F.runtime(caller = f)) { it.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Caller(f), orders) } }
    private fun recovery(): Run { val g = F.guard(); val f = recoveryFact
        return Run(F.raw(g, sibling), F.runtime(recovery = f)) { it.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Recovery(f), orders) } }
    private fun endReplacement(): Run { val g = F.guard(auth = oldAuth, wait = 30000); val old = F.request(binding = 2); val c = closure
        return Run(F.raw(g, old, sibling), F.runtime(closure = c)) { it.prepareEndAuthBinding(g, listOf(old), F.binding, c, F.binding, orders) } }
    private fun endNone(): Run { val g = F.guard(auth = oldAuth, wait = 30000); val old = F.request(binding = 2); val c = closure
        return Run(F.raw(g, old, sibling), F.runtime(closure = c)) { it.prepareEndAuthBinding(g, listOf(old), F.binding, c, null, orders) } }

    private class Outcome(val result: ControlStoreResult, val after: Preferences, val failure: String?)
    private suspend fun execute(w: Run): Outcome {
        val o = store(w.raw)
        val c = w.prepare(o.control)
        val failure = (c.body as ControlCommandBody.Lifecycle).input.demandAuth?.preparationFailure
        return Outcome(o.control.execute(c, F.context(w.runtime)), o.raw(), failure)
    }
    private fun rejected(r: ControlStoreResult, detail: String?) =
        detail != null && r is ControlStoreResult.Rejected && r.reason == RejectionReason.InvalidRequest(detail)

    private fun confirms(id: String, w: Run) = runReleaseTest {
        assertTrue(F.atomic("Z.Cc.$id.confirms"), execute(w).result is ControlStoreResult.Confirmed)
    }
    private fun refusedAt(id: String, boundary: String, w: Run, named: (Outcome) -> Boolean) = runReleaseTest {
        val x = execute(w)
        if (x.result is ControlStoreResult.Confirmed) return@runReleaseTest
        assertTrue(F.atomic("Z.Cc.$id.$boundary"), named(x))
        assertEquals(F.atomic("Z.Cc.$id.$boundary.storedUnchanged"), w.raw, x.after)
    }
    private fun preparation(id: String, w: Run) = refusedAt(id, "preparation", w) { rejected(it.result, it.failure) }
    private fun descriptor(id: String, w: Run) = refusedAt(id, "descriptor", w) { it.failure == null && rejected(it.result, "InvalidLifecycleDescriptor") }
    private fun validator(id: String, w: Run) = refusedAt(id, "validator", w) { it.failure == null && rejected(it.result, "RequiredDecisionEffectMissing") }

    @Test fun rebind_confirms() = confirms("rebind", rebind())
    @Test fun rebind_refusedAtPreparation() = preparation("rebind", rebind())
    @Test fun rebind_refusedAtDescriptor() = descriptor("rebind", rebind())
    @Test fun rebind_refusedAtValidator() = validator("rebind", rebind())
    @Test fun settle_confirms() = confirms("settle", settle())
    @Test fun settle_refusedAtPreparation() = preparation("settle", settle())
    @Test fun settle_refusedAtDescriptor() = descriptor("settle", settle())
    @Test fun settle_refusedAtValidator() = validator("settle", settle())
    @Test fun answer_confirms() = confirms("answer", answer())
    @Test fun answer_refusedAtPreparation() = preparation("answer", answer())
    @Test fun answer_refusedAtDescriptor() = descriptor("answer", answer())
    @Test fun answer_refusedAtValidator() = validator("answer", answer())
    @Test fun initialize_confirms() = confirms("initialize", initialize())
    @Test fun initialize_refusedAtPreparation() = preparation("initialize", initialize())
    @Test fun initialize_refusedAtDescriptor() = descriptor("initialize", initialize())
    @Test fun initialize_refusedAtValidator() = validator("initialize", initialize())
    @Test fun caller_confirms() = confirms("caller", caller())
    @Test fun caller_refusedAtPreparation() = preparation("caller", caller())
    @Test fun caller_refusedAtDescriptor() = descriptor("caller", caller())
    @Test fun caller_refusedAtValidator() = validator("caller", caller())
    @Test fun recovery_confirms() = confirms("recovery", recovery())
    @Test fun recovery_refusedAtPreparation() = preparation("recovery", recovery())
    @Test fun recovery_refusedAtDescriptor() = descriptor("recovery", recovery())
    @Test fun recovery_refusedAtValidator() = validator("recovery", recovery())
    @Test fun endReplacement_confirms() = confirms("endReplacement", endReplacement())
    @Test fun endReplacement_refusedAtPreparation() = preparation("endReplacement", endReplacement())
    @Test fun endReplacement_refusedAtDescriptor() = descriptor("endReplacement", endReplacement())
    @Test fun endReplacement_refusedAtValidator() = validator("endReplacement", endReplacement())
    @Test fun endNone_confirms() = confirms("endNone", endNone())
    @Test fun endNone_refusedAtPreparation() = preparation("endNone", endNone())
    @Test fun endNone_refusedAtDescriptor() = descriptor("endNone", endNone())
    @Test fun endNone_refusedAtValidator() = validator("endNone", endNone())
}
