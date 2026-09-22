package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fourth file (review r2): new targets that the table had mapped to existing 5b tests whose premises are
 * weaker than §9.1 539 (LinkTest.invalidEligibility / effectsNo, PreparationTest.denied). Each fixture here asserts the
 * TV truth vector (exactly the named condition false) or an inline independent vector before the target assertion.
 * The existing tests stay as 5b regression evidence; they are not reassigned as kill tests for these targets.
 */
class DemandAuthBacklogContract4Test {
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)

    private fun ineligible(id: String, only: String, plan: DemandAuthPlan, runtime: DemandAuthRuntime, source: Preferences) {
        F.schema(source)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("truth vector: eligibility", setOf(only), V.falses(V.eligibility(plan, runtime, source)))
        assertNotNull(F.eligible(id), F.transition.eligibility(plan, runtime, F.read(source)))
    }
    /** durableEffects boundary: the eligibility vector of the carrying plan isolates the named effect condition. */
    private fun effectsNo(id: String, only: String, d: AcceptedQueryDecision, source: Preferences) {
        val g = F.guard(); val p = F.plan(d, g, F.request())
        assertEquals("truth vector: eligibility", setOf(only), V.falses(V.eligibility(p, F.runtime(), source)))
        assertFalse(F.eligible(id), F.transition.durableEffects(d, F.read(source)))
    }
    private fun rawWith(vararg nodes: ControlNode, userEpoch: String = "u"): Preferences = F.raw(F.guard(), F.request(), *nodes)
        .toMutablePreferences().apply { this[com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.USER_EPOCH] = userEpoch }.toPreferences()

    // S.effectsSel · S.effectsNull · S.exactRow payload — §4.2 253, §4.3 284.

    private val moved = F.fence.copy(userAccessEpoch = "new")
    @Test fun S_effectsSel() = effectsNo("S.effectsSel", "effects.namespaceProof",
        F.decision(outcome = EntitlementsOutcome.Pending(false, 30), after = moved), rawWith(userEpoch = "new"))
    @Test fun S_effectsNull_fence() = effectsNo("S.effectsNull.fence", "effects.namespaceFence",
        F.decision(outcome = EntitlementsOutcome.Pending(false, 30), after = moved, namespace = ConfirmedControlSnapshot(F.read(F.raw()))),
        rawWith(userEpoch = "new"))
    @Test fun S_exactRow_proofPayload() {
        val e = F.request(id = "e")
        val proof = ConfirmedControlSnapshot(F.read(F.raw(F.request(id = "e", order = 5))))
        effectsNo("S.exactRow.proofPayload", "effects[0].proofExact",
            F.decision(outcome = EntitlementsOutcome.Pending(false, 30), effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, e, proof))),
            rawWith(e))
    }
    @Test fun S_exactRow_currentPayload() {
        val e = F.request(id = "e")
        val proof = ConfirmedControlSnapshot(F.read(F.raw(e)))
        effectsNo("S.exactRow.currentPayload", "effects[0].currentExact",
            F.decision(outcome = EntitlementsOutcome.Pending(false, 30), effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, e, proof))),
            rawWith(F.request(id = "e", order = 5)))
    }

    // S.zeroRemove · U.updateSel · U.answerBlock (narrowing) — §5.1 327–332.

    @Test fun S_zeroRemove() {
        val g = F.guard(); val r = F.request()
        ineligible("S.zeroRemove", "settle.zeroRemoveKeepsAuth", F.plan(guard = g, retry = r, settle = true), F.runtime(), F.raw(g, r))
    }
    @Test fun U_updateSel() {
        val g = F.guard(auth = null); val r = F.request()
        ineligible("U.updateSel", "update.auth", F.plan(guard = g, retry = r), F.runtime(), F.raw(g, r))
    }
    /** A non-AUTH answer that is newer than a resumed AUTH changes nothing; UPDATE_AUTH is the wrong route. */
    @Test fun U_answerBlock() {
        val g = F.guard(auth = F.auth.copy(authStopped = false)); val r = F.request()
        ineligible("U.answerBlock", "update.change", F.plan(guard = g, retry = r), F.runtime(), F.raw(g, r))
    }

    // U.callerFacts · U.recoveryFacts — §3.1 126·130, §5.1 325·334–353.

    private fun caller(binding: LifecycleBinding = F.binding) = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, binding,
        LifecycleOrderGrant(F.life, 20, 1, 0, 21), RefreshIntent.FORCE_PREMIUM, F.now)
    private fun callerPlan(c: LifecycleCaller, g: ControlNode = F.guard()) =
        DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(c), orders, "g-new", "r-new")
    @Test fun U_callerFacts_event() { val c = caller(); ineligible("U.callerFacts.event", "caller.event", callerPlan(c), F.runtime(), F.raw(F.guard())) }
    @Test fun U_callerFacts_pin() {
        val c = caller(F.binding.copy(startEventId = "other"))
        ineligible("U.callerFacts.pin", "caller.pin", callerPlan(c), F.runtime(caller = c), F.raw(F.guard()))
    }
    @Test fun U_callerFacts_identity() {
        val c = caller()
        ineligible("U.callerFacts.identity", "caller.identity", callerPlan(c), F.runtime(caller = c, live = IdentityV1("A", 7)), F.raw(F.guard()))
    }
    private fun recovery(origin: LifetimeId = F.life) = LifecycleRecovery("recovery", F.identity, origin, 11, 21, 1)
    private fun recoveryPlan(r: LifecycleRecovery) = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Recovery(r), orders, "g-new", "r-new")
    @Test fun U_recoveryFacts_event() = ineligible("U.recoveryFacts.event", "recovery.event", recoveryPlan(recovery()), F.runtime(), F.raw(F.guard()))
    @Test fun U_recoveryFacts_origin() {
        val r = recovery(LifetimeId("old"))
        ineligible("U.recoveryFacts.origin", "recovery.origin", recoveryPlan(r), F.runtime(recovery = r), F.raw(F.guard()))
    }

    // N.currentClosure · N.oldScope · N.owed (narrowing) — §5.2 357–363.

    private fun closure(scope: AuthSnapshotV1 = oldAuth) = LifecycleBindingClosure(scope, true, setOf("w"), setOf("w"), 5)
    private fun endPlan(g: ControlNode, c: LifecycleBindingClosure, requests: List<ControlNode> = emptyList()) =
        DemandAuthPlan.end(g, requests, F.binding, c, null, orders)
    @Test fun N_currentClosure_current() {
        val g = F.guard(auth = oldAuth); val c = closure()
        ineligible("N.currentClosure.current", "end.closureCurrent", endPlan(g, c),
            F.runtime(closure = c.copy(capturedWork = setOf("w", "x"), joinedWork = setOf("w", "x"))), F.raw(g))
    }
    @Test fun N_currentClosure_generation() {
        val g = F.guard(auth = oldAuth); val c = closure()
        ineligible("N.currentClosure.generation", "end.closureGeneration", endPlan(g, c), F.runtime(closure = c, generation = 6), F.raw(g))
    }
    @Test fun N_oldScope() {
        val current = F.auth.copy(authStopped = true)
        val g = F.guard(auth = current); val c = closure(current)
        ineligible("N.oldScope", "end.notCurrentScope", endPlan(g, c), F.runtime(closure = c), F.raw(g))
    }
    @Test fun N_owed_missing() {
        val g = F.guard(auth = oldAuth); val c = closure(); val owed = F.request(binding = 2)
        ineligible("N.owed.missing", "end.owed", endPlan(g, c), F.runtime(closure = c), F.raw(g, owed))
    }

    // H.retryPre — §4.3 282, §4.5 308: the retry REQUEST's form and scope (planner boundary, inline vector).

    private fun retryNo(id: String, expectedFalse: String, plan: DemandAuthPlan, node: ControlNode, successorMustBeNew: Boolean) {
        val old = demand(node); val x = F.binding.executor
        val facts = listOf(
            "request" to (old != null),
            "successorNew" to !successorMustBeNew,
            "owner" to (old == null || old.ownerUid == x.ownerUid),
            "binding" to (old == null || old.binding == x.binding),
            "origin" to (old == null || old.raisedAt.origin == x.originLifetimeId))
        assertEquals("truth vector: retry preparation", setOf(expectedFalse), V.falses(facts))
        assertNotNull(F.eligible(id), plan.preparationFailure)
    }
    private val pending = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    @Test fun H_retryPre_kind() { val node = F.guard(auth = null, id = "x"); retryNo("H.retryPre.kind", "request", F.plan(pending, F.guard(), node), node, false) }
    @Test fun H_retryPre_successorNew() {
        val node = F.request(id = "old-retry")
        retryNo("H.retryPre.successorNew", "successorNew", F.plan(pending, F.guard(), node, settle = true, removes = listOf(F.request())), node, true)
    }
    @Test fun H_retryPre_owner() { val node = F.request(owner = "B"); retryNo("H.retryPre.owner", "owner", F.plan(pending, F.guard(), node), node, false) }
    @Test fun H_retryPre_binding() { val node = F.request(binding = 2); retryNo("H.retryPre.binding", "binding", F.plan(pending, F.guard(), node), node, false) }
    @Test fun H_retryPre_origin() {
        val node = F.request(origin = LifetimeId("old")); retryNo("H.retryPre.origin", "origin", F.plan(pending, F.guard(), node), node, false)
    }

    // H.originTerm (narrowing) — §4.4 294: the same current binding and origin needs no rebind (direct boundary, inline vector).

    @Test fun H_originTerm_same() {
        val old = demand(F.request())!!; val x = F.binding.executor
        F.onlyFalse(old.ownerUid == x.ownerUid, !(old.binding == x.binding && old.raisedAt.origin == x.originLifetimeId), old.intent >= old.intent)
        assertFalse(F.eligible("H.originTerm.same"), DemandAuthBoundary.rebind(old, F.binding, old.intent))
    }

    // F.planCapture · N.planPin · P.guardPre — preparation boundaries with inline vectors.

    private fun answerFacts(d: AcceptedQueryDecision, guard: ControlNode?): List<Pair<String, Boolean>> {
        val seconds = when (val outcome = d.outcome) {
            is EntitlementsOutcome.Pending -> outcome.retryAfterSeconds
            is EntitlementsOutcome.Indeterminate -> outcome.retryAfterSeconds
            else -> null
        }
        return listOf(
            "guardParses" to (guard == null || guard(guard) != null),
            "seconds" to (seconds == null || (seconds >= 0 && seconds <= Long.MAX_VALUE / 1000)),
            "floorOrigin" to (d.floorOrigin == d.query.order.origin),
            "minDelay" to (d.decisionMinDelayMillis >= 0),
            "captureBoot" to (seconds == null || d.capture.bootId != ""),
            "captureElapsed" to (seconds == null || d.capture.elapsedMillis >= 0),
            "merge" to (d.mergeNow.bootId != "" && d.mergeNow.elapsedMillis >= 0))
    }
    @Test fun F_planCapture_boot() {
        val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 30), capture = BootReading("", 10000))
        assertEquals("truth vector: answer preparation", setOf("captureBoot"), V.falses(answerFacts(d, F.guard())))
        assertNotNull(F.eligible("F.planCapture.boot"), F.plan(d).preparationFailure)
    }
    @Test fun F_planCapture_elapsed() {
        val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 30), capture = BootReading("boot", -1))
        assertEquals("truth vector: answer preparation", setOf("captureElapsed"), V.falses(answerFacts(d, F.guard())))
        assertNotNull(F.eligible("F.planCapture.elapsed"), F.plan(d).preparationFailure)
    }
    @Test fun N_planPin() {
        val g = F.guard(auth = oldAuth); val other = F.binding.copy(startEventId = "other-start")
        F.onlyFalse(guard(g) != null, other == F.binding)
        assertNotNull(F.eligible("N.planPin"), DemandAuthPlan.end(g, emptyList(), F.binding, closure(), other, orders).preparationFailure)
    }
    @Test fun P_guardPre_auth() {
        val notGuard = F.request(id = "not-guard")
        F.onlyFalse(guard(notGuard) != null)
        assertNotNull(F.eligible("P.guardPre.auth"),
            DemandAuthPlan.auth(notGuard, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new").preparationFailure)
    }
    @Test fun P_guardPre_answer() {
        val notGuard = F.request(id = "not-guard")
        assertEquals("truth vector: answer preparation", setOf("guardParses"), V.falses(answerFacts(F.decision(), notGuard)))
        assertNotNull(F.eligible("P.guardPre.answer"),
            DemandAuthPlan.settle(listOf(F.request()), notGuard, null, F.binding, F.decision(), orders, "g-new", "r-new").preparationFailure)
    }
}
