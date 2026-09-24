package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fiftieth file — builder preparation gates left OPEN by the re-judgment (group ②). The role is the
 * builder's own boundary, as in the original units (e.g. C4 retryNo: `assertNotNull(eligible, plan.preparationFailure)`):
 * independent vector of the builder inputs on that path, written here as literals of the production conditions
 * DemandAuthPlan.kt:125–165 / DemandAuthFacts.kt:15–21·207–211 → twin prepared (and reaching Confirm) → negative vector
 * with only the target false → the builder refuses (preparation failure present). The literal failure is a separate
 * classification method (CLASSIFICATION_ONLY). A decide-level role was tried first (b2a60be6): with most builder guards
 * disabled, a later layer still refuses the malformed plan, so the decide result does not separate these gates.
 * Paths: UPDATE_AUTH Answer (guard typed DP:152 · stated seconds DF:207–210 · captured wait readable DP:160 · merge boot
 * DP:161 · retry typed DP:139 · retry scope owner/binding/origin DP:141 · order issue DF:17 for the retry and the AUTH
 * stop), SETTLE successor-must-be-new (DP:140), Caller floor boot (DP:71), UPDATE_AUTH guard typed (DP:57), END replacement
 * pin (DP:97), END and REBIND request typing/need (DP:127 · DF:117).
 */
class DemandAuthBacklogContract50Test {
    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime): RecordTransactionDecision<*> {
        val c = F.command(plan)
        return ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(before), F.context(runtime), false, false)
    }
    private class Case(val plan: DemandAuthPlan, val raw: Preferences, val rt: DemandAuthRuntime = F.runtime())
    private fun role(id: String, only: String, vec: (Case) -> List<Pair<String, Boolean>>, twin: Case, bad: Case) {
        F.schema(twin.raw)
        assertEquals("truth vector: twin builder inputs", emptySet<String>(), V.falses(vec(twin)))
        assertNull("positive twin must be prepared", twin.plan.preparationFailure)
        assertTrue("positive twin: reaches Confirm", decide(twin.plan, twin.raw, twin.rt) is RecordTransactionDecision.Confirm)
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(vec(bad)))
        assertNotNull(F.eligible(id), bad.plan.preparationFailure)
    }
    private fun cls(label: String, expected: String, bad: Case) = assertEquals("classification: $label preparation failure", expected, bad.plan.preparationFailure)
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private val ok = 21L
    private val exhausted = Long.MAX_VALUE
    private fun readable(b: BootReading) = b.bootId != "" && b.elapsedMillis >= 0

    // UPDATE_AUTH Answer — builder inputs (DP:150–165, DF:15–21·207–211)
    private class A(val d: AcceptedQueryDecision, val guard: ControlNode, val retry: ControlNode?, val initial: Long,
        val expectsIssue: Boolean, val expectsStopIssue: Boolean)
    private fun ans(a: A): List<Pair<String, Boolean>> {
        val s = when (val o = a.d.outcome) { is EntitlementsOutcome.Pending -> o.retryAfterSeconds; is EntitlementsOutcome.Indeterminate -> o.retryAfterSeconds; else -> null }
        val old = a.retry?.let { demand(it) }
        return listOf("guardTyped" to (guard(a.guard) != null), "seconds" to (s == null || (s >= 0 && s <= Long.MAX_VALUE / 1000)),
            "floorOrigin" to (a.d.floorOrigin == a.d.query.order.origin), "minDelay" to (a.d.decisionMinDelayMillis >= 0),
            "statedReadable" to (s == null || readable(a.d.capture)), "mergeReadable" to readable(a.d.mergeNow),
            "retryTyped" to (a.retry == null || old != null), "retryOwner" to (old == null || old.ownerUid == "A"),
            "retryBinding" to (old == null || old.binding == 3L), "retryOrigin" to (old == null || old.raisedAt.origin == F.life),
            // DF:15–17: the source refuses only when its last issued order is Long.MAX_VALUE (bindingStart 1, after ≤ 22 here)
            "issue" to (!(a.expectsIssue || a.expectsStopIssue) || a.initial != Long.MAX_VALUE))
    }
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    private fun answerCase(a: A, raw: Preferences) = Case(DemandAuthPlan.auth(a.guard, a.retry, F.binding, LifecycleAuthEvent.Answer(a.d), LifecycleOrderSource(F.life, a.initial), "g-new", "r-new"), raw)
    private val answers = mutableMapOf<Case, A>()
    private fun ac(a: A, raw: Preferences) = answerCase(a, raw).also { answers[it] = a }
    private val av: (Case) -> List<Pair<String, Boolean>> = { k -> ans(answers.getValue(k)) }
    // twin: existing sufficient REQUEST kept (no issue); pending 30 s
    private fun twinKept() = F.guard().let { g -> F.request().let { r -> ac(A(pending, g, r, ok, false, false), F.raw(g, r)) } }
    private fun twinNew() = F.guard().let { g -> ac(A(pending, g, null, ok, true, false), F.raw(g)) }

    private fun secondsBad() = F.guard().let { g -> ac(A(F.decision(outcome = EntitlementsOutcome.Pending(false, 1L shl 61)), g, null, ok, false, false), F.raw(g)) }
    @Test fun A15a_answer() = role("Z.bl.A15a.answer", "seconds", av, twinNew(), secondsBad())
    @Test fun A15a_classification() = cls("A15a (2^61 s)", "InvalidBootReading", secondsBad())
    private fun capturedBad() = F.guard().let { g -> ac(A(F.decision(outcome = EntitlementsOutcome.Pending(false, 30), capture = BootReading("", 10000)), g, null, ok, true, false), F.raw(g)) }
    @Test fun capturedBoot_answer() = role("Z.bl.capturedBoot.answer", "statedReadable", av, twinNew(), capturedBad())
    @Test fun capturedBoot_classification() = cls("captured boot", "InvalidBootReading", capturedBad())
    private val stable get() = F.decision(outcome = EntitlementsOutcome.StableInactive(false))
    private fun mergeTwin() = F.guard().let { g -> ac(A(stable, g, null, ok, false, false), F.raw(g)) }
    private fun mergeBad() = F.guard().let { g -> ac(A(F.decision(outcome = EntitlementsOutcome.StableInactive(false), merge = BootReading("", 10000)), g, null, ok, false, false), F.raw(g)) }
    @Test fun mergeBoot_answer() = role("Z.bl.mergeBoot.answer", "mergeReadable", av, mergeTwin(), mergeBad())
    @Test fun mergeBoot_classification() = cls("merge boot", "InvalidBootReading", mergeBad())
    private fun guardUntyped() = F.request(id = "g").let { gNode -> ac(A(pending, gNode, null, ok, true, false), F.raw(gNode)) }
    @Test fun guardTyped_answer() = role("Z.bl.guardTyped.answer", "guardTyped", av, twinNew(), guardUntyped())
    @Test fun guardTyped_classification() = cls("answer guard", "InvalidGuard", guardUntyped())
    // SETTLE reaches answer()'s own guard check (DP:152) first; UPDATE_AUTH checks the guard earlier (DP:57)
    private fun settleGuard(gNode: ControlNode): Case {
        val r = F.request(); val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        return Case(DemandAuthPlan.settle(listOf(r), gNode, null, F.binding, d, orders, "g-new", "r-new"), F.raw(gNode, r))
    }
    private val sgv: (Case) -> List<Pair<String, Boolean>> = { k -> listOf("guardTyped" to (guard(k.plan.guardBefore) != null)) }
    @Test fun guardTyped_settle() = role("Z.bl.guardTyped.settle", "guardTyped", sgv, settleGuard(F.guard(F.auth.copy(authStopped = false))), settleGuard(F.request(id = "g")))
    @Test fun guardTyped_settle_classification() = cls("settle guard", "InvalidGuard", settleGuard(F.request(id = "g")))
    private fun retryUntyped() = F.guard().let { g -> F.guard(id = "r").let { rNode -> ac(A(pending, g, rNode, ok, false, false), F.raw(g, rNode)) } }
    @Test fun retryTyped_answer() = role("Z.bl.retryTyped.answer", "retryTyped", av, twinKept(), retryUntyped())
    @Test fun retryTyped_classification() = cls("retry typed", "InvalidRetryRequest", retryUntyped())
    private fun retryScope(r: ControlNode) = F.guard().let { g -> ac(A(pending, g, r, ok, false, false), F.raw(g, r)) }
    @Test fun retryOwner_answer() = role("Z.bl.retryOwner.answer", "retryOwner", av, twinKept(), retryScope(F.request(owner = "B")))
    @Test fun retryBinding_answer() = role("Z.bl.retryBinding.answer", "retryBinding", av, twinKept(), retryScope(F.request(binding = 4)))
    @Test fun retryOrigin_answer() = role("Z.bl.retryOrigin.answer", "retryOrigin", av, twinKept(), retryScope(F.request(origin = LifetimeId("other"))))
    @Test fun retryScope_classification() = cls("retry scope", "RetryScopeMismatch", retryScope(F.request(owner = "B")))
    private fun issueBad() = F.guard().let { g -> ac(A(pending, g, null, exhausted, true, false), F.raw(g)) }
    @Test fun Q16_retryIssue_answer() = role("Z.bl.Q16.retryIssue.answer", "issue", av, twinNew(), issueBad())
    @Test fun Q16_retryIssue_classification() = cls("retry issue", "OrderExhausted", issueBad())
    private val authFail get() = F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION))
    private fun stopTwin() = F.guard(F.auth.copy(authStopped = false)).let { g -> ac(A(authFail, g, null, ok, false, true), F.raw(g)) }
    private fun stopBad() = F.guard(F.auth.copy(authStopped = false)).let { g -> ac(A(authFail, g, null, exhausted, false, true), F.raw(g)) }
    @Test fun Q16_stopIssue_answer() = role("Z.bl.Q16.stopIssue.answer", "issue", av, stopTwin(), stopBad())
    @Test fun Q16_stopIssue_classification() = cls("AUTH stop issue", "OrderExhausted", stopBad())

    // SETTLE: successor must be new when a REQUEST is consumed (DP:140)
    private fun settle(retry: ControlNode?): Case {
        val g = F.guard(F.auth.copy(authStopped = false)); val r = F.request()
        val d = F.decision(minDelay = 30000, followUp = RefreshIntent.FORCE_PREMIUM)
        val nodes = listOfNotNull(g, r, retry)
        return Case(DemandAuthPlan.settle(listOf(r), g, retry, F.binding, d, orders, "g-new", "r-new"), F.raw(*nodes.toTypedArray()))
    }
    private val sv: (Case) -> List<Pair<String, Boolean>> = { k -> listOf("successorNew" to (k.plan.retryBefore == null)) }
    @Test fun successorNew_settle() = role("Z.bl.successorNew.settle", "successorNew", sv, settle(null), settle(F.request(id = "r2", order = 5)))
    @Test fun successorNew_classification() = cls("successor must be new", "SuccessorMustBeNew", settle(F.request(id = "r2", order = 5)))

    // Caller: the floor's remaining time at the caller's boot reading must be readable (DP:71)
    private fun callerAt(now: BootReading): Case {
        val g = F.guard(F.auth.copy(authStopped = false), 60000)
        val f = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding, LifecycleOrderGrant(F.life, 20, 1, 0, 21), RefreshIntent.FORCE_PREMIUM, now)
        return Case(DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(f), LifecycleOrderSource(F.life, 21), "g-new", "r-new"), F.raw(g), F.runtime(caller = f))
    }
    private val cv: (Case) -> List<Pair<String, Boolean>> = { k -> val now = (k.plan.event as LifecycleAuthEvent.Caller).fact.now
        listOf("floorPresent" to (guard(k.plan.guardBefore)?.floor != null), "nowReadable" to readable(now)) }
    @Test fun callerBoot_caller() = role("Z.bl.callerBoot.caller", "nowReadable", cv, callerAt(F.now), callerAt(BootReading("", 10000)))
    @Test fun callerBoot_classification() = cls("caller boot", "InvalidBootReading", callerAt(BootReading("", 10000)))

    // UPDATE_AUTH Initialize: guard typed (DP:57)
    private fun initWith(g: ControlNode) = Case(DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new"), F.raw(g))
    private val iv: (Case) -> List<Pair<String, Boolean>> = { k -> listOf("guardTyped" to (k.plan.guardBefore == null || guard(k.plan.guardBefore) != null)) }
    @Test fun guardTyped_init() = role("Z.bl.guardTyped.init", "guardTyped", iv, initWith(F.guard(auth = null)), initWith(F.request(id = "g")))
    @Test fun guardTyped_init_classification() = cls("init guard", "InvalidGuard", initWith(F.request(id = "g")))

    // END: replacement pinned to the plan binding (DP:97); request need (DF:117 via DP:129)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val closure get() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
    private fun endWith(replacement: LifecycleBinding?, requests: List<ControlNode> = emptyList()): Case {
        val g = F.guard(auth = oldAuth)
        return Case(DemandAuthPlan.end(g, requests, F.binding, closure, replacement, orders), F.raw(g, *requests.toTypedArray()), F.runtime(closure = closure))
    }
    private val ev: (Case) -> List<Pair<String, Boolean>> = { k -> listOf("replacementPinned" to (k.plan.replacement == null || k.plan.replacement == F.binding),
        "requestsNeeded" to k.plan.targets.filter { it.role == LifecycleRole.REQUEST }.all { t -> demand(t.before)!!.let { !(it.binding == 3L && it.raisedAt.origin == F.life) } }) }
    @Test fun replacementPin_end() = role("Z.bl.replacementPin.end", "replacementPinned", ev, endWith(F.binding), endWith(F.binding.copy(startEventId = "other")))
    @Test fun replacementPin_classification() = cls("END replacement", "AuthInitializationIneligible", endWith(F.binding.copy(startEventId = "other")))
    private val endNeedV: (Case) -> List<Pair<String, Boolean>> = { k -> val reqs = requestsOf(k)
        listOf("requestTyped" to reqs.all { demand(it) != null }, "requestNeeded" to reqs.mapNotNull { demand(it) }.all { !(it.binding == 3L && it.raisedAt.origin == F.life) }) }
    private val endInputs = mutableMapOf<Case, List<ControlNode>>()
    private fun requestsOf(k: Case) = endInputs.getValue(k)
    private fun endReq(nodes: List<ControlNode>) = endWith(null, nodes).also { endInputs[it] = nodes }
    @Test fun Q11b_end() = role("Z.bl.Q11b.end", "requestNeeded", endNeedV, endReq(listOf(F.request(binding = 2))), endReq(listOf(F.request())))
    @Test fun Q11b_end_classification() = cls("END request need", "InvalidRebind", endReq(listOf(F.request())))

    // REBIND: request typed (DP:127)
    private val rebInputs = mutableMapOf<Case, List<ControlNode>>()
    private fun reb(nodes: List<ControlNode>) = Case(DemandAuthPlan.rebind(nodes, F.binding, orders), F.raw(*nodes.toTypedArray())).also { rebInputs[it] = nodes }
    private val rv: (Case) -> List<Pair<String, Boolean>> = { k -> listOf("requestTyped" to rebInputs.getValue(k).all { demand(it) != null }) }
    @Test fun requestTyped_rebind() = role("Z.bl.requestTyped.rebind", "requestTyped", rv, reb(listOf(F.request(binding = 2, order = 50))), reb(listOf(F.guard(auth = null, id = "r"))))
    @Test fun requestTyped_classification() = cls("rebind target", "InvalidRequestTarget", reb(listOf(F.guard(auth = null, id = "r"))))
}
