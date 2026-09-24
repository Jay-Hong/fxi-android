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
 * 5e-1 contract, seventy-third file — C72 method (same four methods per event, role prefix Z.Cd) for the three events whose
 * floor-carrying effects the C72 fixtures did not exercise (Diag72 effect diff): a floor-blocked CALLER with an existing
 * REQUEST (C44 fixture — successor · field · floor carry-over), RECOVERY over a guard with a floor, and UPDATE_AUTH
 * Initialize over a floor-only guard without AUTH (DP:59 `before?.floor`; DT:111 initialization needs no before AUTH).
 * The C72 KDoc below applies unchanged.
 *
 * (C72) Z.C builder side with the refusal boundary named (STEP3ZK r16 판정 ④, criterion B):
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
class DemandAuthBacklogContract73Test {
    @get:Rule val temp = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private suspend fun store(raw: Preferences): ControlStoreTestStorage = ControlStoreTestStorage(temp.newFile()).also {
        opened += it; controlTestTimeout("c73 seed") { it.data.updateData { raw } }
    }
    @After fun cleanup() = runBlocking { controlTestTimeout("c73 cleanup", 30000) { opened.forEach { it.close() } } }

    private val orders get() = LifecycleOrderSource(F.life, 21)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val sibling = F.request(id = "sibling", owner = "B")
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    private val closure get() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
    private val callerFact get() = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding,
        LifecycleOrderGrant(F.life, 20, 1, 0, 21), RefreshIntent.FORCE_PREMIUM, F.now)
    private val recoveryFact get() = LifecycleRecovery("recovery", F.identity, F.life, 11, 21, 1)

    private class Run(val raw: Preferences, val runtime: DemandAuthRuntime, val prepare: (ControlRecordStore) -> CommandRef)
    private fun callerFloor(): Run {
        val o = LifecycleOrderSource(F.life, 21)
        val c = LifecycleCaller("caller-22", LifecycleCallerOrigin.CALLER, F.binding, o.issue(F.binding.startedOrder)!!, RefreshIntent.FORCE_ENTITLEMENTS, F.now)
        val g = F.guard(F.auth.copy(authStopped = false), 60000); val old = F.request(intent = RefreshIntent.FORCE_PREMIUM, order = 4)
        return Run(F.raw(g, old, sibling), F.runtime(caller = c)) { it.prepareUpdateAuth(g, old, F.binding, LifecycleAuthEvent.Caller(c), o) } }
    private fun recoveryFloor(): Run { val g = F.guard(wait = 30000); val f = recoveryFact
        return Run(F.raw(g, sibling), F.runtime(recovery = f)) { it.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Recovery(f), orders) } }
    private fun initializeFloor(): Run { val g = F.guard(auth = null, wait = 30000)
        return Run(F.raw(g, sibling), F.runtime()) { it.prepareUpdateAuth(g, null, F.binding, LifecycleAuthEvent.Initialize, orders) } }

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
        assertTrue(F.atomic("Z.Cd.$id.confirms"), execute(w).result is ControlStoreResult.Confirmed)
    }
    private fun refusedAt(id: String, boundary: String, w: Run, named: (Outcome) -> Boolean) = runReleaseTest {
        val x = execute(w)
        if (x.result is ControlStoreResult.Confirmed) return@runReleaseTest
        assertTrue(F.atomic("Z.Cd.$id.$boundary"), named(x))
        assertEquals(F.atomic("Z.Cd.$id.$boundary.storedUnchanged"), w.raw, x.after)
    }
    private fun preparation(id: String, w: Run) = refusedAt(id, "preparation", w) { rejected(it.result, it.failure) }
    private fun descriptor(id: String, w: Run) = refusedAt(id, "descriptor", w) { it.failure == null && rejected(it.result, "InvalidLifecycleDescriptor") }
    private fun validator(id: String, w: Run) = refusedAt(id, "validator", w) { it.failure == null && rejected(it.result, "RequiredDecisionEffectMissing") }

    @Test fun callerFloor_confirms() = confirms("callerFloor", callerFloor())
    @Test fun callerFloor_refusedAtPreparation() = preparation("callerFloor", callerFloor())
    @Test fun callerFloor_refusedAtDescriptor() = descriptor("callerFloor", callerFloor())
    @Test fun callerFloor_refusedAtValidator() = validator("callerFloor", callerFloor())
    @Test fun recoveryFloor_confirms() = confirms("recoveryFloor", recoveryFloor())
    @Test fun recoveryFloor_refusedAtPreparation() = preparation("recoveryFloor", recoveryFloor())
    @Test fun recoveryFloor_refusedAtDescriptor() = descriptor("recoveryFloor", recoveryFloor())
    @Test fun recoveryFloor_refusedAtValidator() = validator("recoveryFloor", recoveryFloor())
    @Test fun initializeFloor_confirms() = confirms("initializeFloor", initializeFloor())
    @Test fun initializeFloor_refusedAtPreparation() = preparation("initializeFloor", initializeFloor())
    @Test fun initializeFloor_refusedAtDescriptor() = descriptor("initializeFloor", initializeFloor())
    @Test fun initializeFloor_refusedAtValidator() = validator("initializeFloor", initializeFloor())
}
