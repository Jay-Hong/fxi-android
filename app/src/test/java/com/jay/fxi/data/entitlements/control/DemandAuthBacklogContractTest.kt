package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.PremiumAccessReducer
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract: the 5b backlog rows where a boundary result, a decide gate or a shared calculation
 * had no writer-level evidence. Written from the design before any 5e-1 target exists.
 *
 * Each negative fixture must assert an independent truth vector at its tested boundary before the target assertion.
 * A passing twin is supplementary evidence and does not establish all non-target premises.
 * Unverified premises and masked single mutants remain OPEN until independently reviewed.
 * Twin and premise assertions use plain messages, so their failure is never a target's kill.
 */
class DemandAuthBacklogContractTest {
    private val other = LifetimeId("other")
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)

    private fun decide(plan: DemandAuthPlan, before: Preferences, context: AttemptContext): RecordTransactionDecision<*> {
        val c = F.command(plan)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        return ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(before), context, false, false)
    }
    /** Exactly the named conditions are false among the entries covered by TV; omitted gates need separate premises. */
    private fun vector(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime, context: AttemptContext,
        gate: String?, eligibilityFalse: String?) {
        assertEquals("truth vector: writer gates", setOfNotNull(gate), V.falses(V.decideGates(plan, context, before)))
        assertEquals("truth vector: eligibility", setOfNotNull(eligibilityFalse), V.falses(V.eligibility(plan, runtime, before)))
        // F.command issues the command id "command"; the premises decideGates omits are checked here.
        assertEquals("truth vector: common premises", emptySet<String>(), V.falses(V.commonPremises(plan, before, "command")))
    }
    private fun notApplied(id: String, plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime,
        context: AttemptContext = F.context(runtime), gate: String? = null, eligibilityFalse: String? = null) {
        F.schema(before)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        vector(plan, before, runtime, context, gate, eligibilityFalse)
        assertFalse(F.eligible(id), decide(plan, before, context) is RecordTransactionDecision.Confirm)
    }
    private fun ineligible(id: String, only: String, plan: DemandAuthPlan, runtime: DemandAuthRuntime, source: Preferences) {
        F.schema(source)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("truth vector: eligibility", setOf(only), V.falses(V.eligibility(plan, runtime, source)))
        assertNotNull(F.eligible(id), F.transition.eligibility(plan, runtime, F.read(source)))
    }
    private fun twin(plan: DemandAuthPlan, runtime: DemandAuthRuntime, source: Preferences) {
        F.schema(source)
        assertNull("positive twin must be prepared", plan.preparationFailure)
        assertEquals("positive twin truth vector", emptySet<String>(), V.falses(V.eligibility(plan, runtime, source)))
        assertNull("positive twin must be eligible", F.transition.eligibility(plan, runtime, F.read(source)))
    }
    private fun caller(origin: LifecycleCallerOrigin = LifecycleCallerOrigin.CALLER, grantOrigin: LifetimeId = F.life) =
        LifecycleCaller("caller", origin, F.binding, LifecycleOrderGrant(grantOrigin, 20, 1, 0, 21), RefreshIntent.FORCE_PREMIUM, F.now)
    private fun callerPlan(c: LifecycleCaller, g: ControlNode) =
        DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(c), LifecycleOrderSource(F.life, 21), "g-new", "r-new")
    private fun recovery(episode: Long = 1) = LifecycleRecovery("recovery", F.identity, F.life, 11, 21, episode)
    private fun recoveryPlan(r: LifecycleRecovery, g: ControlNode) =
        DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Recovery(r), LifecycleOrderSource(F.life, 21), "g-new", "r-new")
    private fun closure(entriesClosed: Boolean = true) = LifecycleBindingClosure(oldAuth, entriesClosed, setOf("w"), setOf("w"), 5)
    private fun endPlan(g: ControlNode, c: LifecycleBindingClosure, replacement: LifecycleBinding? = null) =
        DemandAuthPlan.end(g, emptyList(), F.binding, c, replacement, LifecycleOrderSource(F.life, 21))
    private fun initializePlan() =
        DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life, 21), "g-new", "r-new")

    // G: writer decide gates (design §3.1 130–139, §9.1 543). Checked through the real ControlLifecycle decide.

    /** §3.1 130: each attempt passes freshly captured context; prepared values are never reused as current context. */
    @Test fun G_context() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r); val rt = F.runtime()
        F.apply(p, F.raw(g, r), rt)
        notApplied("G.context", p, F.raw(g, r), rt, F.context(rt).copy(demandAuth = null), gate = "dt.runtime")
    }

    /** §3.1 136: the named writer's own currentness (runtime binding) — the common gate only sees executor facts. */
    @Test fun G_current() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        F.apply(p, F.raw(g, r))
        val rt = F.runtime(binding = F.binding.copy(startEventId = "other-start"))
        assertEquals("fixture: the common gate sees the same executor", p.binding.executor, rt.binding.executor)
        assertNotEquals("fixture: the captured binding differs", p.binding, rt.binding)
        notApplied("G.current", p, F.raw(g, r), rt, gate = "dt.runtimeBinding")
    }

    /** §3.1 135: a fixed unchanged row must still equal its preimage; ControlLifecycle checks only changed targets. */
    @Test fun G_unchanged() {
        val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
        val g = F.guard(auth = resumed); val r = F.request()
        val p = F.plan(F.decision(), g, retry = null, settle = true, removes = listOf(r))
        assertEquals("fixture: the guard is fixed as unchanged", listOf("g"), p.unchanged.map { it.target.id })
        F.apply(p, F.raw(g, r))
        notApplied("G.unchanged", p, F.raw(F.guard(auth = resumed, wait = 60000), r), F.runtime(), gate = "unchanged[g].preimage")
    }

    /** §3.1 137: defense regression; the final reader also rejects two guards, so this is not a DT:32 single-mutant kill. */
    @Test fun G_guardCreate() {
        val p = initializePlan()
        F.apply(p, F.raw())
        val before = F.raw(F.guard(auth = null, wait = 30000, id = "g-other"))
        F.schema(before)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        vector(p, before, F.runtime(), F.context(), gate = "dt.guardCreate", eligibilityFalse = null)
        assertFalse(
            "guard creation defense regression; not a DT:32 single-mutant kill",
            decide(p, before, F.context()) is RecordTransactionDecision.Confirm)
    }

    /** §9.1 543: the eligibility result is applied by the real writer, not only returned by the function. */
    @Test fun G_eligApply() {
        val r = F.request(); val gOk = F.guard()
        F.apply(F.plan(guard = gOk, retry = r), F.raw(gOk, r))
        val g = F.guard(auth = F.auth.copy(authGeneration = 9)); val p = F.plan(guard = g, retry = r)
        assertNotNull("fixture: eligibility rejects this plan", F.transition.eligibility(p, F.runtime(), F.read(F.raw(g, r))))
        notApplied("G.eligApply", p, F.raw(g, r), F.runtime(), eligibilityFalse = "answer.scopeGeneration")
    }

    // S/H/U/N: each boundary result applied inside eligibility (§9.1 543). Same level as DemandAuthLinkTest.

    /** §4.2 247: only an actually registered query of this runtime. */
    @Test fun W_acceptance() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r); val source = F.raw(g, r)
        twin(p, F.runtime(), source)
        val rt = F.runtime(registrations = emptyList())
        assertFalse("fixture: the query is not registered", p.decision!!.registration in rt.registrations)
        ineligible("W.acceptance", "q.registered", p, rt, source)
    }

    /** §4.2 250–251: currentness immediately before the settlement write. */
    @Test fun W_after() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r); val source = F.raw(g, r)
        twin(p, F.runtime(), source)
        val rt = F.runtime(generation = 6)
        assertNotEquals("fixture: generation moved", p.decision!!.expectedAfterGeneration, rt.generation)
        ineligible("W.after", "after.generation", p, rt, source)
    }

    /** §4.2 253: every decision-required durable effect is confirmed. */
    @Test fun W_effects() {
        val g = F.guard(); val r = F.request(); val e = F.request(id = "e")
        val source = F.raw(g, r, e)
        F.schema(source)
        val observed = F.read(source).locations("e").single().second as ControlEntryRead.Interpreted
        assertEquals("fixture: the current effect row is exact", e.toPayloadEntry(), observed.original.toPayloadEntry())
        val confirmed = LifecycleDurableEffect(
            ControlKind.DEMAND, e, ConfirmedControlSnapshot(F.read(source)))
        val ok = F.decision(outcome = EntitlementsOutcome.Pending(false, 30), effects = listOf(confirmed))
        twin(F.plan(ok, g, r), F.runtime(), source)
        val missingProof = confirmed.copy(confirmation = null)
        val bad = F.decision(outcome = EntitlementsOutcome.Pending(false, 30), effects = listOf(missingProof))
        assertNull("fixture: only the confirmation is absent", bad.effects.single().confirmation)
        assertEquals(confirmed.kind, missingProof.kind)
        assertEquals(confirmed.node.toPayloadEntry(), missingProof.node.toPayloadEntry())
        ineligible("W.effects", "effects[0].proof", F.plan(bad, g, r), F.runtime(), source)
    }

    /** §4.2 246: the query intent must be at least the REQUEST intent. The planner does not check REMOVE targets. */
    @Test fun W_request() {
        val q = F.query.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS)
        val d = F.decision(q = q); val rt = F.runtime(registrations = listOf(d.registration)); val g = F.guard()
        val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
        twin(F.plan(d, g, retry = null, settle = true, removes = listOf(weak)), rt, F.raw(g, weak))
        val r = F.request()
        assertTrue("fixture: the query is weaker than the REQUEST", q.intent < demand(r)!!.intent)
        ineligible("W.request", "remove[r].intent", F.plan(d, g, retry = null, settle = true, removes = listOf(r)), rt, F.raw(g, r))
    }

    /** §4.2 table: Pending never settles a REQUEST. */
    @Test fun W_consumes() {
        val g = F.guard(); val r = F.request(); val source = F.raw(g, r)
        twin(F.plan(F.decision(), g, retry = null, settle = true, removes = listOf(r)), F.runtime(), source)
        val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
        ineligible("W.consumes", "remove[r].settles", F.plan(d, g, retry = null, settle = true, removes = listOf(r)), F.runtime(), source)
    }

    /** §5.1 332: an answer needs the matching AUTH scope. The answer planner does not check scope. */
    @Test fun W_answerScope() {
        val r = F.request(); val gOk = F.guard()
        twin(F.plan(guard = gOk, retry = r), F.runtime(), F.raw(gOk, r))
        val g = F.guard(auth = F.auth.copy(authGeneration = 9))
        ineligible("W.answerScope", "answer.scopeGeneration", F.plan(guard = g, retry = r), F.runtime(), F.raw(g, r))
    }

    /** §4.5 306: a handover order comes from the current origin's single source. */
    @Test fun W_order() {
        val old = F.request(binding = 2)
        twin(DemandAuthPlan.rebind(listOf(old), F.binding, LifecycleOrderSource(F.life, 21)), F.runtime(), F.raw(old))
        val foreign = LifecycleOrderSource(other, 21)
        assertNotEquals("fixture: the source belongs to another origin", F.binding.executor.originLifetimeId, foreign.origin)
        ineligible("W.order", "request[r].orderOrigin", DemandAuthPlan.rebind(listOf(old), F.binding, foreign), F.runtime(), F.raw(old))
    }

    /** §5.1 320: initial installation needs the current bound and live identity. */
    @Test fun W_initialize() {
        val p = initializePlan()
        twin(p, F.runtime(), F.raw())
        val rt = F.runtime(live = IdentityV1("A", 7))
        assertNotEquals("fixture: live identity differs", F.binding.identity, rt.liveIdentity)
        ineligible("W.initialize", "init.live", p, rt, F.raw())
    }

    /** §5.1 324: only an actual CALLER request; SCHEDULED is excluded. */
    @Test fun W_caller() {
        val g = F.guard(); val ok = caller()
        twin(callerPlan(ok, g), F.runtime(caller = ok), F.raw(g))
        val bad = caller(origin = LifecycleCallerOrigin.SCHEDULED)
        ineligible("W.caller", "caller.origin", callerPlan(bad, g), F.runtime(caller = bad), F.raw(g))
    }

    /** §5.1 332: a CALLER resume needs the matching AUTH scope. */
    @Test fun W_callerScope() {
        val c = caller(); val gOk = F.guard()
        twin(callerPlan(c, gOk), F.runtime(caller = c), F.raw(gOk))
        val g = F.guard(auth = F.auth.copy(authGeneration = 9))
        ineligible("W.callerScope", "caller.scopeGeneration", callerPlan(c, g), F.runtime(caller = c), F.raw(g))
    }

    /**
     * §5.1 324: the caller order comes from the same sequence. The violation is the grant origin so that the
     * later AUTH-change relation (A03a) still holds and cannot mask a bypass.
     */
    @Test fun W_callerOrder() {
        val g = F.guard(); val ok = caller()
        twin(callerPlan(ok, g), F.runtime(caller = ok), F.raw(g))
        val bad = caller(grantOrigin = other)
        ineligible("W.callerOrder", "caller.orderOrigin", callerPlan(bad, g), F.runtime(caller = bad), F.raw(g))
    }

    /** §5.1 332: credential recovery needs the matching AUTH scope. */
    @Test fun W_recoveryScope() {
        val rec = recovery(); val gOk = F.guard()
        twin(recoveryPlan(rec, gOk), F.runtime(recovery = rec), F.raw(gOk))
        val g = F.guard(auth = F.auth.copy(authGeneration = 9))
        ineligible("W.recoveryScope", "recovery.scopeGeneration", recoveryPlan(rec, g), F.runtime(recovery = rec), F.raw(g))
    }

    /** §5.1 351: a recovery episode is consumed once per origin. */
    @Test fun W_recovery() {
        val rec = recovery(episode = 1); val g = F.guard(); val p = recoveryPlan(rec, g)
        twin(p, F.runtime(recovery = rec), F.raw(g))
        ineligible("W.recovery", "recovery.episode", p, F.runtime(recovery = rec, consumed = 1), F.raw(g))
    }

    /** §5.2 357: END needs the exact old scope's entries closed. */
    @Test fun W_closure() {
        val g = F.guard(auth = oldAuth); val ok = closure()
        twin(endPlan(g, ok), F.runtime(closure = ok), F.raw(g))
        val bad = closure(entriesClosed = false)
        ineligible("W.closure", "end.entriesClosed", endPlan(g, bad), F.runtime(closure = bad), F.raw(g))
    }

    /** §5.2 361: an END replacement is the new binding's eligible initial snapshot. */
    @Test fun W_replacement() {
        val g = F.guard(auth = oldAuth); val c = closure(); val p = endPlan(g, c, F.binding)
        twin(p, F.runtime(closure = c), F.raw(g))
        ineligible("W.replacement", "end.replacement.live", p, F.runtime(live = IdentityV1("A", 7), closure = c), F.raw(g))
    }

    /** §5.1 322: a new stopApplied comes from the same sequence. */
    @Test fun W_stopOrder() {
        val d = F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION)); val g = F.guard()
        twin(F.plan(d, g, retry = null), F.runtime(), F.raw(g))
        ineligible("W.stopOrder", "stop.orderOrigin", F.plan(d, g, retry = null, orders = LifecycleOrderSource(other, 21)), F.runtime(), F.raw(g))
    }

    /**
     * §3.2 END: the first row ends or replaces an old AUTH. A floor-only guard has no AUTH to end; the replacement
     * makes the input a valid descriptor, so the rejection must come from the writer.
     */
    @Test fun N_auth() {
        val c = closure(); val gOld = F.guard(auth = oldAuth, wait = 30000)
        F.apply(endPlan(gOld, c, F.binding), F.raw(gOld), F.runtime(closure = c))
        val g = F.guard(auth = null, wait = 30000)
        assertNull("fixture: the guard has no AUTH", guard(g)!!.auth)
        notApplied("N.auth", endPlan(g, c, F.binding), F.raw(g), F.runtime(closure = c), eligibilityFalse = "end.auth")
    }

    // P: preparation boundaries.

    /** §5.3 372–374: the answer planner's input conditions; exactly the named one is expected false. */
    private fun answerPreconditions(d: AcceptedQueryDecision, seconds: Long, secondsInRange: Boolean = true, floorOrigin: Boolean = true) {
        val facts = listOf(
            "guardParses" to (guard(F.guard()) != null),
            "secondsNonNegative" to (seconds >= 0),
            "secondsInRange" to (seconds <= Long.MAX_VALUE / 1000),
            "floorOrigin" to (d.floorOrigin == d.query.order.origin),
            "minDelay" to (d.decisionMinDelayMillis >= 0),
            "capture" to (d.capture.bootId != "" && d.capture.elapsedMillis >= 0),
            "merge" to (d.mergeNow.bootId != "" && d.mergeNow.elapsedMillis >= 0))
        val expected = setOfNotNull("secondsInRange".takeIf { !secondsInRange }, "floorOrigin".takeIf { !floorOrigin })
        assertEquals("truth vector: answer preparation", expected, V.falses(facts))
    }

    /** §4.4 294 (Q11): an ineligible rebind is a preparation failure. */
    @Test fun H_planRebind() {
        val old = F.request(binding = 2)
        assertNull("positive twin must be prepared", DemandAuthPlan.rebind(listOf(old), F.binding, LifecycleOrderSource(F.life, 21)).preparationFailure)
        val weaker = DemandAuthPlan.rebind(listOf(old), F.binding, LifecycleOrderSource(F.life, 21), mapOf("r" to RefreshIntent.IF_STALE))
        val o = demand(old)!!; val x = F.binding.executor
        F.onlyFalse(o.ownerUid == x.ownerUid, !(o.binding == x.binding && o.raisedAt.origin == x.originLifetimeId),
            RefreshIntent.IF_STALE >= o.intent)
        assertNotNull(F.eligible("H.planRebind"), weaker.preparationFailure)
    }

    /** §5.3 373: the source floor origin must link to the answered query. */
    @Test fun F_planOrigin() {
        assertNull("positive twin must be prepared", F.plan().preparationFailure)
        val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 30), origin = other)
        assertNotEquals("fixture: floor origin differs", d.query.order.origin, d.floorOrigin)
        answerPreconditions(d, 30, floorOrigin = false)
        assertNotNull(F.eligible("F.planOrigin"), F.plan(d).preparationFailure)
    }

    /** §5.3 372: a stated 0-second wait is a floor, distinct from no stated wait. */
    @Test fun F_planRequired_zero() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT, 0)))
        assertNull("fixture plan must be prepared", p.preparationFailure)
        val floor = guard(p.guardAfter)?.floor
        assertNotNull(F.atomic("F.planRequired.zero"), floor)
        assertEquals(F.atomic("F.planRequired.zero"), 0L, floor!!.waitMillis)
    }

    /** §5.3 371: TRANSIENT with no stated wait keeps the default lower bound. */
    @Test fun F_planRequired_absent() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT, null)))
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals(F.atomic("F.planRequired.absent"), PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS, guard(p.guardAfter)?.floor?.waitMillis)
    }

    // F.policy: §5.3 371 retry intent and lower bound. Expected values come from the design sentence, not the function.

    private val weakQuery = F.query.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS)
    private fun weak(outcome: EntitlementsOutcome = EntitlementsOutcome.StableInactive(false),
        reapproval: LifecycleReapproval = LifecycleReapproval.NOT_REQUIRED) = F.decision(q = weakQuery, outcome = outcome, reapproval = reapproval)

    @Test fun F_policy_pendingIntent() = assertEquals(F.atomic("F.policy.pendingIntent"), RefreshIntent.FORCE_ENTITLEMENTS,
        DemandAuthBoundary.retryIntent(weak(EntitlementsOutcome.Pending(false, 30))))
    @Test fun F_policy_transientIntent() = assertEquals(F.atomic("F.policy.transientIntent"), RefreshIntent.FORCE_ENTITLEMENTS,
        DemandAuthBoundary.retryIntent(weak(EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT))))
    @Test fun F_policy_transientDelay() = assertEquals(F.atomic("F.policy.transientDelay"), PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS,
        DemandAuthBoundary.minimumDelay(weak(EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT))))
    @Test fun F_policy_decodeIntent() = assertEquals(F.atomic("F.policy.decodeIntent"), RefreshIntent.FORCE_ENTITLEMENTS,
        DemandAuthBoundary.retryIntent(weak(EntitlementsOutcome.Indeterminate(IndeterminateReason.DECODE))))
    @Test fun F_policy_decodeDelay() = assertEquals(F.atomic("F.policy.decodeDelay"), PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS,
        DemandAuthBoundary.minimumDelay(weak(EntitlementsOutcome.Indeterminate(IndeterminateReason.DECODE))))
    @Test fun F_policy_blockedIntent() = assertEquals(F.atomic("F.policy.blockedIntent"), RefreshIntent.FORCE_PREMIUM,
        DemandAuthBoundary.retryIntent(weak(reapproval = LifecycleReapproval.BLOCKED)))
    @Test fun F_policy_blockedDelay() = assertEquals(F.atomic("F.policy.blockedDelay"), PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS,
        DemandAuthBoundary.minimumDelay(weak(reapproval = LifecycleReapproval.BLOCKED)))
    /** §5.3 372: a stated wait is not replaced by the default lower bound. */
    @Test fun F_policy_statedDelay() = assertEquals(F.atomic("F.policy.statedDelay"), 0L,
        DemandAuthBoundary.minimumDelay(weak(EntitlementsOutcome.Pending(false, 30))))

    @Test fun F_policy_pendingDelay() = assertEquals(
        F.atomic("F.policy.pendingDelay"),
        PremiumAccessReducer.DEFAULT_BACKOFF_FLOOR_MILLIS,
        DemandAuthBoundary.minimumDelay(weak(EntitlementsOutcome.Pending(false, null))))

    @Test fun F_policy_untypedIntent() = assertEquals(
        F.atomic("F.policy.untypedIntent"),
        RefreshIntent.FORCE_PREMIUM,
        DemandAuthBoundary.retryIntent(weak(
            EntitlementsOutcome.Indeterminate(IndeterminateReason.UNTYPED_FORBIDDEN))))

    @Test fun F_policy_krxIntent() = assertEquals(
        F.atomic("F.policy.krxIntent"),
        RefreshIntent.FORCE_ENTITLEMENTS,
        DemandAuthBoundary.retryIntent(weak(EntitlementsOutcome.KrxEntitlementRequired)))

    @Test fun F_policy_authNoIntrinsicRetry() = assertNull(
        F.atomic("F.policy.authNoIntrinsicRetry"),
        DemandAuthBoundary.retryIntent(weak(
            EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION))))

    @Test fun F_policy_followUpRaisesIntent() = assertEquals(
        F.atomic("F.policy.followUpRaisesIntent"),
        RefreshIntent.FORCE_PREMIUM,
        DemandAuthBoundary.retryIntent(F.decision(
            q = weakQuery,
            outcome = EntitlementsOutcome.Pending(false, 30),
            followUp = RefreshIntent.FORCE_PREMIUM)))

    @Test fun F_policy_followUpCannotWeakenIntent() = assertEquals(
        F.atomic("F.policy.followUpCannotWeakenIntent"),
        RefreshIntent.FORCE_ENTITLEMENTS,
        DemandAuthBoundary.retryIntent(F.decision(
            q = weakQuery,
            outcome = EntitlementsOutcome.Pending(false, 30),
            followUp = RefreshIntent.IF_STALE)))

    @Test fun F_policy_decisionDelay() = assertEquals(
        F.atomic("F.policy.decisionDelay"),
        9000L,
        DemandAuthBoundary.minimumDelay(F.decision(
            q = weakQuery,
            outcome = EntitlementsOutcome.Pending(false, 30),
            minDelay = 9000)))

    @Test fun F_planSeconds_overflowToZero() {
        val ok = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 0)))
        assertNull("positive twin must be prepared", ok.preparationFailure)
        val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 2305843009213693952L))
        assertEquals("fixture: the Long product wraps to zero", 0L, 2305843009213693952L * 1000)
        answerPreconditions(d, 2305843009213693952L, secondsInRange = false)
        val bad = F.plan(d)
        assertNotNull(F.eligible("F.planSeconds.overflowToZero"), bad.preparationFailure)
    }

    // V: calculations the writer and its validator share (DT:37/:251 expectedRows, DT:43/:259 evidence).
    // A mutation there moves both sides, so the expected record is built here, from the design.

    private fun appliedContract(id: String, plan: DemandAuthPlan, before: Preferences): Pair<CommandRef, Preferences> {
        F.schema(before)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        val c = F.command(plan)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        val result = ControlLifecycleConfirmation(F.codec).decide(
            c, input, F.read(before), F.context(), false, false)
        assertTrue(F.atomic(id), result is RecordTransactionDecision.Confirm)
        return c to (result as RecordTransactionDecision.Confirm<*>).candidate
    }

    private val successorDecision = F.decision(followUp = RefreshIntent.FORCE_PREMIUM)
    private fun demandRows(raw: Preferences) =
        (Json.parseToJsonElement(raw[ControlRecordKeys.payload(ControlKind.DEMAND)]!!) as JsonArray).map { it as JsonObject }
    private fun id(row: JsonObject) = (row["id"] as JsonPrimitive).content

    /** §3.1 139: rows other than the changed ones keep their elements, order and literals. */
    @Test fun V_rows() {
        val x = F.node(F.request(id = "x", owner = "B", binding = 0)
            .toPayloadEntry().fields.toString().replace("\"binding\":0", "\"binding\":-0"))
        val y = F.request(id = "y", owner = "B", binding = 1, intent = RefreshIntent.FORCE_ENTITLEMENTS)
        val g = F.guard(); val r = F.request()
        val p = F.plan(successorDecision, g, retry = null, settle = true, removes = listOf(r))
        assertEquals("fixture: consume r, create a successor, resume the guard",
            listOf("r" to LifecycleEffect.REMOVE, "r-new" to LifecycleEffect.CREATE, "g" to LifecycleEffect.REPLACE),
            p.targets.map { it.target.id to it.target.effect })
        val (_, after) = appliedContract("V.rows", p, F.raw(x, g, r, y))
        val rows = demandRows(after); val ids = rows.map(::id)
        assertEquals(F.atomic("V.rows"), listOf(x, y).map { it.toPayloadEntry().fields.toString() },
            rows.filter { id(it) !in setOf("g", "r", "r-new") }.map { it.toString() })
        assertFalse(F.atomic("V.rows"), "r" in ids)
        assertEquals(F.atomic("V.rows"), 1, ids.count { it == "r-new" })
        assertEquals(F.atomic("V.rows"), 1, ids.count { it == "g" })
    }

    /** §3.2: the self Applied row, in the fixed target order REMOVE → selected REQUEST → guard. */
    @Test fun V_evid() {
        val g = F.guard(); val r = F.request()
        val p = F.plan(successorDecision, g, retry = null, settle = true, removes = listOf(r))
        val (c, after) = appliedContract("V.evid", p, F.raw(g, r))
        fun target(id: String, effect: String) = buildJsonObject { put("kind", "DEMAND"); put("id", id); put("effect", effect) }
        val expected = buildJsonObject {
            put("version", 2); put("commandId", c.id); put("ownerTrackingLifetimeId", c.ownerTrackingLifetimeId.value)
            put("kind", "CONTROL_LIFECYCLE"); put("transition", "SETTLE_QUERY")
            put("targets", JsonArray(listOf(target("r", "REMOVE"), target("r-new", "CREATE"), target("g", "REPLACE"))))
        }
        val rows = Json.parseToJsonElement(after[ControlLifecycleEvidenceFixtures.evidenceKey]!!) as JsonArray
        assertEquals(F.atomic("V.evid"), listOf<JsonElement>(expected), rows.toList())
    }
}
