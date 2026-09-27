package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Claude-owned 6-4bA1c-1 contract: the four DemandAuth Lifecycle transitions of the pure required-obligation derivation
 * (T2 rows for REBIND_REQUESTS, SETTLE_QUERY, UPDATE_AUTH, END_AUTH_BINDING). Fixed inputs: 6-4bA1c-1 table consensus (table r2
 * A1c-1 section + Unavailable r3 common 1-13 + U1/U2), recipes 6-4bA1c1_recipes_codex.r1.md. Expected lists are written here
 * from the fixture facts, independently of the deriver.
 *
 * Declarations this slice adds: ObligationRole.LifecycleCommand(transition), ObligationComponent.QUERY,
 * ObligationSubject.ExactTarget(kind, id), DecisionNamespaceScope + RequiredLowerBound.DecisionNamespace,
 * FixedSourceFact.LifecyclePlan(descriptor); the five unused *Duty declarations are removed.
 * Row slots use ObligationRole.Lifecycle(transition, fixed.role); command-level slots (B, Y, D, Dn, P) use LifecycleCommand.
 *
 * fixedSources, as this contract pins them (table r2 lists G, D, V, E_i, C, A_e, T_i, U_j):
 *  - G = [(LIFECYCLE_PLAN, null, WHOLE): LifecyclePlan(descriptor)]; D = G + BINDING; V = D + DECISION + QUERY; E_i = V + effect i;
 *    C = D + CLOSURE; A_e = (Answer ? V : D) + EVENT.
 *  - T_i / U_j appended after a base list do NOT repeat G: [(TARGET|UNCHANGED, i, WHOLE), (…, SOURCE): before ?: after (if any),
 *    (…, AFTER): after (if any)].
 *  - Row base: REBIND D, SETTLE V, UPDATE_AUTH A_e, END C. A REQUEST slot with an issued grant appends
 *    (LIFECYCLE_PLAN, i, GRANT): grants[after.id]; an AUTH slot appends Caller's event order or Answer's authStopGrant at the
 *    GUARD's target index. requiredUnchanged rows carry no grant. P = base + every T_i + every U_j.
 * The implementation thread reads but does not edit this file.
 */
class RequiredObligationsDemandAuthContractTest {
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val E = AllowedSlotDisposition.EITHER
    private val C = AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY
    private val D = AllowedSlotDisposition.DURABLY_OWNED_ONLY
    private val op = "command"

    // ── fixture helpers ────────────────────────────────────────────────────────────────────────────────────────────────
    private class Case(val plan: DemandAuthPlan, val ref: CommandRef) {
        val body = ref.body as ControlCommandBody.Lifecycle
        val descriptor get() = body.input
        val input get() = RequirementInput.Lifecycle(ref, body)
    }
    private fun case(p: DemandAuthPlan, targets: List<String>, unchanged: List<String> = emptyList()): Case {
        fun rows(xs: List<LifecycleFixedTarget>) = xs.map { "${it.role}/${it.target.effect}/${it.target.id}" }
        assertNull("recipe must prepare", p.preparationFailure)
        assertEquals("recipe targets", targets, rows(p.targets))
        assertEquals("recipe unchanged", unchanged, rows(p.unchanged))
        return Case(p, F.command(p))
    }
    private fun demandOf(n: ControlNode?) = n?.let { demand(it) }
    private fun guardOf(n: ControlNode?) = n?.let { guard(it) }

    // ── fixed sources ──────────────────────────────────────────────────────────────────────────────────────────────────
    private fun ev(root: FixedInputRoot, index: Int?, facet: FixedInputFacet, f: FixedSourceFact) =
        FixedSourceEvidence(FixedInputLocation(root, index, facet), f)
    private fun g(c: Case) = listOf(ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE, FixedSourceFact.LifecyclePlan(c.descriptor)))
    private fun d(c: Case) = g(c) + ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.BINDING, FixedSourceFact.Binding(c.plan.binding))
    private fun v(c: Case) = d(c) +
        ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.DECISION, FixedSourceFact.Decision(c.plan.decision!!)) +
        ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.QUERY, FixedSourceFact.Query(c.plan.decision!!.registration))
    private fun e(c: Case, i: Int) = v(c) + ev(FixedInputRoot.DECISION_EFFECT, i, FixedInputFacet.EFFECT,
        FixedSourceFact.DecisionEffect(c.plan.decision!!.effects[i]))
    private fun closure(c: Case) = d(c) + ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.CLOSURE, FixedSourceFact.BindingClosure(c.plan.closure!!))
    private fun authEvent(c: Case) = (if (c.plan.event is LifecycleAuthEvent.Answer) v(c) else d(c)) +
        ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.EVENT, FixedSourceFact.AuthEvent(c.plan.event!!))
    private fun row(root: FixedInputRoot, i: Int, t: LifecycleFixedTarget): List<FixedSourceEvidence> = listOfNotNull(
        ev(root, i, FixedInputFacet.WHOLE, FixedSourceFact.LifecycleTarget(t)),
        (t.before ?: t.after)?.let { ev(root, i, FixedInputFacet.SOURCE, FixedSourceFact.Node(t.target.kind, it)) },
        t.after?.let { ev(root, i, FixedInputFacet.AFTER, FixedSourceFact.Node(t.target.kind, it)) })
    private fun t(c: Case, i: Int) = row(FixedInputRoot.LIFECYCLE_TARGET, i, c.descriptor.targets[i])
    private fun u(c: Case, j: Int) = row(FixedInputRoot.LIFECYCLE_UNCHANGED, j, c.descriptor.requiredUnchanged[j])
    private fun grant(i: Int, grant: LifecycleOrderGrant) = listOf(ev(FixedInputRoot.LIFECYCLE_PLAN, i, FixedInputFacet.GRANT, FixedSourceFact.OrderGrant(grant)))
    private fun everyRow(c: Case) = c.descriptor.targets.indices.flatMap { t(c, it) } + c.descriptor.requiredUnchanged.indices.flatMap { u(c, it) }

    // ── expected slots ─────────────────────────────────────────────────────────────────────────────────────────────────
    private fun slot(role: ObligationRole, subject: ObligationSubject, component: ObligationComponent, b: LandingBranch,
        bound: RequiredLowerBound, allowed: AllowedSlotDisposition, f: List<FixedSourceEvidence>) =
        RequiredSlot(RequiredObligationKey(role, subject, component, b), SlotRequirement.Required(bound, allowed, f))
    private fun cmd(c: Case) = ObligationRole.LifecycleCommand(c.descriptor.transition)
    private fun rowRole(c: Case, fixedRole: LifecycleRole) = ObligationRole.Lifecycle(c.descriptor.transition, fixedRole)

    private fun b(c: Case, landing: AllowedSlotDisposition, f: List<FixedSourceEvidence>): List<RequiredSlot> {
        val scope = BindingScope(c.plan.binding, c.plan.closure)
        return listOf(slot(cmd(c), scope, ObligationComponent.BINDING, L, RequiredLowerBound.Binding(scope), landing, f),
            slot(cmd(c), scope, ObligationComponent.BINDING, N, RequiredLowerBound.Binding(scope), E, f))
    }
    private fun y(c: Case): List<RequiredSlot> {
        val dcs = c.plan.decision!!
        val scope = QueryScope(dcs.registration, dcs.source)
        return listOf(slot(cmd(c), scope, ObligationComponent.QUERY, L, RequiredLowerBound.Query(scope), C, v(c)),
            slot(cmd(c), scope, ObligationComponent.QUERY, N, RequiredLowerBound.Query(scope), E, v(c)))
    }
    private fun effect(c: Case, i: Int): List<RequiredSlot> {
        val dcs = c.plan.decision!!
        val fx = dcs.effects[i]
        val scope = DecisionEffectScope(op, dcs.registration.id, i, fx.kind, fx.node)
        return listOf(L, N).map { slot(cmd(c), scope, ObligationComponent.DURABLE_EFFECT, it, RequiredLowerBound.DecisionEffect(fx), E, e(c, i)) }
    }
    private fun namespace(c: Case): List<RequiredSlot> {
        val dcs = c.plan.decision!!
        val scope = DecisionNamespaceScope(op, dcs.registration.id, dcs.acceptedBeforeFence, dcs.confirmedAfterFence)
        return listOf(L, N).map { slot(cmd(c), scope, ObligationComponent.DURABLE_EFFECT, it,
            RequiredLowerBound.DecisionNamespace(dcs.acceptedBeforeFence, dcs.confirmedAfterFence), E, v(c)) }
    }
    private fun q(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>): List<RequiredSlot> {
        val old = demandOf(fixed.before); val after = demandOf(fixed.after)!!
        val s = old ?: after
        val subject = ObligationSubject.Request(s.id, s.ownerUid, s.binding, s.raisedAt)
        val role = rowRole(c, LifecycleRole.REQUEST)
        return listOf(
            slot(role, subject, ObligationComponent.REQUEST, L,
                RequiredLowerBound.Request(after, after.id, after.ownerUid, after.binding, after.intent, after.raisedAt), E, f),
            slot(role, subject, ObligationComponent.REQUEST, N,
                RequiredLowerBound.Request(s, after.id, after.ownerUid, after.binding, after.intent, after.raisedAt), E, f))
    }
    private fun qRemoved(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>): List<RequiredSlot> {
        val old = demandOf(fixed.before)!!
        val subject = ObligationSubject.Request(old.id, old.ownerUid, old.binding, old.raisedAt)
        val bound = RequiredLowerBound.Request(old, old.id, old.ownerUid, old.binding, old.intent, old.raisedAt)
        val role = rowRole(c, LifecycleRole.REQUEST)
        return listOf(slot(role, subject, ObligationComponent.REQUEST, L, bound, C, f),
            slot(role, subject, ObligationComponent.REQUEST, N, bound, E, f))
    }
    private fun floor(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>): List<RequiredSlot> {
        val source = guardOf(fixed.before)?.floor; val need = guardOf(fixed.after)!!.floor!!
        val subject = ObligationSubject.Floor(ControlKind.DEMAND, fixed.target.id, (source ?: need).originLifetimeId)
        return listOf(L, N).map { slot(rowRole(c, LifecycleRole.GUARD), subject, ObligationComponent.FLOOR, it,
            RequiredLowerBound.Floor(ControlKind.DEMAND, fixed.target.id, need), D, f) }
    }
    private fun auth(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>, endWithoutReplacement: Boolean = false): List<RequiredSlot> {
        val source = guardOf(fixed.before)?.auth; val after = guardOf(fixed.after)?.auth
        val s = (source ?: after)!!
        val subject = ObligationSubject.Auth(s.ownerUid, s.binding, s.originLifetimeId, s.authGeneration)
        val role = rowRole(c, LifecycleRole.GUARD)
        val landing = if (endWithoutReplacement) slot(role, subject, ObligationComponent.AUTH, L, RequiredLowerBound.Auth(source!!), C, f)
            else slot(role, subject, ObligationComponent.AUTH, L, RequiredLowerBound.Auth(after!!), E, f)
        return listOf(landing, slot(role, subject, ObligationComponent.AUTH, N, RequiredLowerBound.Auth(s), E, f))
    }
    private fun exact(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        slot(rowRole(c, LifecycleRole.GUARD), ObligationSubject.ExactTarget(ControlKind.DEMAND, fixed.target.id), ObligationComponent.SOURCE, it,
            RequiredLowerBound.ExactSource(ControlKind.DEMAND, fixed.target.id, fixed.before!!), E, f) }
    private fun p(c: Case, base: List<FixedSourceEvidence>): List<RequiredSlot> {
        val scope = ReceiptScope(op, c.descriptor.transition, c.plan.decision?.registration?.id, null)
        val f = base + everyRow(c)
        return listOf(slot(cmd(c), scope, ObligationComponent.RECEIPT, L, RequiredLowerBound.Receipt(scope), C, f),
            RequiredSlot(RequiredObligationKey(cmd(c), scope, ObligationComponent.RECEIPT, N), SlotRequirement.NotRequiredByContract(f)))
    }

    // ── runners ────────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun check(c: Case, expected: List<RequiredSlot>) {
        val r = deriveRequiredObligations(c.input)
        if (r !is RequirementDerivation.Available) fail("expected Available, got $r")
        r as RequirementDerivation.Available
        assertSame(c.ref, r.commandBinding.ref)
        assertSame(c.body, r.commandBinding.body)
        assertEquals(FixedCommandKind.Lifecycle(c.descriptor.transition), r.commandBinding.kind)
        assertEquals(expected, r.orderedSlots)
        assertTrue(runCatching { (r.orderedSlots as MutableList<RequiredSlot>).add(r.orderedSlots.first()) }.isFailure)
        val exp = r.orderedSlots.map { ExpectedSlot(it.key, it.necessity) }
        val required = r.orderedSlots.filter { it.necessity == SlotNecessity.Required }.map { SubmittedSlot(it.key, ObligationDisposition.DurablyOwned) }
        assertEquals(CoverageResult.Complete, checkSlotCoverage(exp, required))
        for ((branch, problem) in listOf(L to CoverageProblem.MissingL, N to CoverageProblem.MissingN)) {
            val drop = required.first { it.key.branch == branch }
            assertEquals(CoverageResult.Rejected(listOf(problem)), checkSlotCoverage(exp, required - drop))
        }
    }
    private fun unavailable(input: RequirementInput, reason: RequiredObligationsUnavailable, root: FixedInputRoot, index: Int?, facet: FixedInputFacet) =
        assertEquals(RequirementDerivation.Unavailable(reason, FixedInputLocation(root, index, facet)), deriveRequiredObligations(input))

    // ── recipes (6-4bA1c1_recipes_codex.r1.md) ─────────────────────────────────────────────────────────────────────────
    private val old = LifetimeId("old")
    private fun rebindCase(): Case {
        val r0 = F.request(id = "r0", binding = 2, origin = old, order = 500)
        val r1 = F.request(id = "r1", binding = 2, origin = old, intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 501)
        val p = DemandAuthPlan.rebind(listOf(r0, r1), F.binding, LifecycleOrderSource(F.life, 21), intents = mapOf("r1" to RefreshIntent.FORCE_PREMIUM))
        return case(p, listOf("REQUEST/REPLACE/r0", "REQUEST/REPLACE/r1"))
    }
    private fun settleFullCase(): Case {
        val removed = F.request(id = "removed")
        val gd = F.guard(auth = F.auth.copy(authStopped = false))
        val effect = F.request(id = "effect", order = 7)
        val effect2 = F.request(id = "effect2", order = 8)
        val proof = ConfirmedControlSnapshot(F.read(F.raw(effect, effect2)))
        val oldFence = F.fence.copy(userAccessEpoch = "u-old")
        val dcs = F.decision(q = F.query.copy(fence = oldFence), before = oldFence, outcome = EntitlementsOutcome.StableInactive(false),
            minDelay = 30_000, followUp = RefreshIntent.FORCE_PREMIUM,
            effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, effect, proof), LifecycleDurableEffect(ControlKind.DEMAND, effect2, proof)),
            namespace = proof)
        val p = DemandAuthPlan.settle(listOf(removed), gd, null, F.binding, dcs, LifecycleOrderSource(F.life, 21), "unused-guard-id", "retry")
        return case(p, listOf("REQUEST/REMOVE/removed", "REQUEST/CREATE/retry", "GUARD/REPLACE/g"))
    }
    private fun settleEmptyGuardCase(): Case {
        val r = F.request(id = "removed")
        val gd = F.guard(auth = null)
        val p = DemandAuthPlan.settle(listOf(r), gd, null, F.binding, F.decision(), LifecycleOrderSource(F.life, 21), "unused-guard-id", "unused-retry-id")
        return case(p, listOf("REQUEST/REMOVE/removed"), listOf("GUARD/REPLACE/g"))
    }
    private fun settleFloorOnlyKeptRetryCase(): Case {
        val p = DemandAuthPlan.settle(emptyList(), F.guard(auth = null), F.request(id = "reuse"), F.binding,
            F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), LifecycleOrderSource(F.life, 21), "g-new", "r-new")
        return case(p, listOf("GUARD/REPLACE/g"), listOf("REQUEST/REPLACE/reuse"))
    }
    private fun settleKeptFloorGuardWithoutAuthCase(): Case {
        val p = DemandAuthPlan.settle(listOf(F.request(id = "removed")), F.guard(auth = null, wait = 600_000), null, F.binding,
            F.decision(), LifecycleOrderSource(F.life, 21), "unused-guard-id", "unused-retry-id")
        return case(p, listOf("REQUEST/REMOVE/removed"), listOf("GUARD/REPLACE/g"))
    }
    private fun settleFloorOriginChangesCase(): Case {
        val a = F.auth.copy(authStopped = false)
        val gd = F.node("""{"id":"g","kind":"SCHEDULE_GUARD","floor":{"anchorBootId":"boot","anchorElapsedMillis":10000,"waitMillis":1000,""" +
            """"originLifetimeId":"old-origin"},"auth":{"ownerUid":"${a.ownerUid}","authGeneration":${a.authGeneration},"binding":${a.binding},""" +
            """"originLifetimeId":"${a.originLifetimeId.value}","authStopped":false,"authStateOrder":${a.authStateOrder},""" +
            """"authStopAppliedOrder":${a.authStopAppliedOrder}}}""")
        val p = DemandAuthPlan.settle(listOf(F.request(id = "removed")), gd, null, F.binding, F.decision(minDelay = 30_000),
            LifecycleOrderSource(F.life, 21), "unused-guard-id", "unused-retry-id")
        return case(p, listOf("REQUEST/REMOVE/removed", "GUARD/REPLACE/g"))
    }
    private fun answerEffectFenceCase(): Case {
        val effect = F.request(id = "effect", order = 7)
        val proof = ConfirmedControlSnapshot(F.read(F.raw(effect)))
        val oldFence = F.fence.copy(userAccessEpoch = "u-old")
        val dcs = F.decision(q = F.query.copy(fence = oldFence), before = oldFence,
            outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION, 60),
            effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, effect, proof)), namespace = proof)
        val p = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(dcs), LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        return case(p, listOf("GUARD/REPLACE/g"))
    }
    private fun answerFenceOnlyCase(): Case {
        val oldFence = F.fence.copy(userAccessEpoch = "u-old")
        val dcs = F.decision(q = F.query.copy(fence = oldFence), before = oldFence,
            outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION, 60),
            effects = emptyList(), namespace = ConfirmedControlSnapshot(F.read(F.raw())))
        val p = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(dcs), LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        return case(p, listOf("GUARD/REPLACE/g"))
    }
    private fun answerKeptRequestNamedAuthCase(): Case {
        val dcs = F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION, 60), followUp = RefreshIntent.FORCE_PREMIUM)
        val p = DemandAuthPlan.auth(F.guard(), F.request(id = "auth"), F.binding, LifecycleAuthEvent.Answer(dcs),
            LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        return case(p, listOf("GUARD/REPLACE/g"), listOf("REQUEST/REPLACE/auth"))
    }
    private fun answerRetryNamedAuthCase(): Case {
        val dcs = F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION, 60), followUp = RefreshIntent.FORCE_PREMIUM)
        val p = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(dcs), LifecycleOrderSource(F.life, 21), "unused-g", "auth")
        return case(p, listOf("GUARD/REPLACE/g", "REQUEST/CREATE/auth"))
    }
    private fun callerCase(retryId: String, wait: Long?): Case {
        val orders = LifecycleOrderSource(F.life, 21)
        val caller = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding, orders.issue(F.binding.startedOrder)!!, RefreshIntent.FORCE_PREMIUM, F.now)
        val p = DemandAuthPlan.auth(F.guard(wait = wait), null, F.binding, LifecycleAuthEvent.Caller(caller), orders, "unused-g", retryId)
        return case(p, if (wait != null) listOf("GUARD/REPLACE/g", "REQUEST/CREATE/$retryId") else listOf("GUARD/REPLACE/g"))
    }
    private fun answerStopCase(): Case {
        val dcs = F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION, 60))
        val p = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(dcs), LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        assertEquals(22L, p.authStopGrant?.value)
        return case(p, listOf("GUARD/REPLACE/g"))
    }
    private fun initializeCase() = case(DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize,
        LifecycleOrderSource(F.life, 21), "g-init", "unused-r"), listOf("GUARD/CREATE/g-init"))
    private fun recoveryCase() = case(DemandAuthPlan.auth(F.guard(), null, F.binding,
        LifecycleAuthEvent.Recovery(LifecycleRecovery("recovery", F.identity, F.life, 11, 22, 1)), LifecycleOrderSource(F.life, 21), "unused-g", "unused-r"),
        listOf("GUARD/REPLACE/g"))
    private fun endCase(replacement: Boolean, wait: Long? = 60_000): Case {
        val gd = F.guard(auth = F.auth.copy(binding = 2, originLifetimeId = old), wait = wait)
        val r = F.request(id = "rebound", binding = 2, origin = old, order = 500)
        val cl = LifecycleBindingClosure(guard(gd)!!.auth!!, true, setOf("work"), setOf("work"), 5)
        val p = DemandAuthPlan.end(gd, listOf(r), F.binding, cl, if (replacement) F.binding else null, LifecycleOrderSource(F.life, 21))
        return case(p, listOf("GUARD/REPLACE/g", "REQUEST/REPLACE/rebound"))
    }

    // ═══ REBIND_REQUESTS ════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun A01_rebind_bindingThenEachRequestWithItsGrant_thenReceipt() {
        val c = rebindCase()
        val base = d(c)
        val t0 = c.descriptor.targets[0]; val t1 = c.descriptor.targets[1]
        assertEquals(RefreshIntent.FORCE_PREMIUM, demandOf(t1.after)!!.intent)
        check(c, b(c, E, base) +
            q(c, t0, base + t(c, 0) + grant(0, c.plan.grants.getValue("r0"))) +
            q(c, t1, base + t(c, 1) + grant(1, c.plan.grants.getValue("r1"))) +
            p(c, base))
    }

    // ═══ SETTLE_QUERY ═══════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun A02_settle_queryEffectsNamespace_thenRemoveRetryFloorAuth_thenReceipt() {
        val c = settleFullCase()
        val dcs = c.plan.decision!!
        assertTrue("fence changed → Dn", dcs.acceptedBeforeFence != dcs.confirmedAfterFence)
        val base = v(c)
        val (removed, retry, gd) = c.descriptor.targets
        check(c, b(c, E, d(c)) + y(c) + effect(c, 0) + effect(c, 1) + namespace(c) +
            qRemoved(c, removed, base + t(c, 0)) +
            q(c, retry, base + t(c, 1) + grant(1, c.plan.grants.getValue("retry"))) +
            floor(c, gd, base + t(c, 2)) + auth(c, gd, base + t(c, 2)) +
            p(c, base))
    }

    @Test fun A03_settle_emptyRequiredUnchangedGuard_isExactSourceBothBranches() {
        val c = settleEmptyGuardCase()
        val dcs = c.plan.decision!!
        assertEquals("no fence change → no Dn", dcs.acceptedBeforeFence, dcs.confirmedAfterFence)
        val base = v(c)
        check(c, b(c, E, d(c)) + y(c) +
            qRemoved(c, c.descriptor.targets[0], base + t(c, 0)) +
            exact(c, c.descriptor.requiredUnchanged[0], base + u(c, 0)) +
            p(c, base))
    }

    @Test fun A03b_settle_floorOnlyGuard_andKeptRetryInRequiredUnchanged_noGrant() {
        val c = settleFloorOnlyKeptRetryCase()
        assertNull(guardOf(c.descriptor.targets[0].after)!!.auth)
        val base = v(c)
        check(c, b(c, E, d(c)) + y(c) +
            floor(c, c.descriptor.targets[0], base + t(c, 0)) +
            q(c, c.descriptor.requiredUnchanged[0], base + u(c, 0)) +
            p(c, base))
    }

    @Test fun A03c_settle_keptGuardWithFloorButNoAuth_isFloorOnly_notExactSource() {
        val c = settleKeptFloorGuardWithoutAuthCase()
        val u0 = c.descriptor.requiredUnchanged[0]
        assertTrue(guardOf(u0.after)!!.floor != null); assertNull(guardOf(u0.after)!!.auth)
        val base = v(c)
        check(c, b(c, E, d(c)) + y(c) +
            qRemoved(c, c.descriptor.targets[0], base + t(c, 0)) +
            floor(c, u0, base + u(c, 0)) +
            p(c, base))
    }

    @Test fun A03d_settle_floorSubjectOriginIsTheBeforeFloorsOrigin() {
        val c = settleFloorOriginChangesCase()
        val gd = c.descriptor.targets[1]
        assertEquals(LifetimeId("old-origin"), guardOf(gd.before)!!.floor!!.originLifetimeId)
        assertEquals(F.life, guardOf(gd.after)!!.floor!!.originLifetimeId)
        val base = v(c)
        check(c, b(c, E, d(c)) + y(c) +
            qRemoved(c, c.descriptor.targets[0], base + t(c, 0)) +
            floor(c, gd, base + t(c, 1)) + auth(c, gd, base + t(c, 1)) +
            p(c, base))
    }

    // ═══ UPDATE_AUTH ════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun A04_updateAuthAnswer_stopGrantOnAuth() {
        val c = answerStopCase()
        val base = authEvent(c)
        val gd = c.descriptor.targets[0]
        check(c, b(c, E, d(c)) + y(c) +
            floor(c, gd, base + t(c, 0)) +
            auth(c, gd, base + t(c, 0) + grant(0, c.plan.authStopGrant!!)) +
            p(c, base))
    }

    @Test fun A04b_updateAuthAnswer_withEffectAndFenceChange() {
        val c = answerEffectFenceCase()
        val dcs = c.plan.decision!!
        assertTrue(dcs.acceptedBeforeFence != dcs.confirmedAfterFence)
        val base = authEvent(c)
        val gd = c.descriptor.targets[0]
        check(c, b(c, E, d(c)) + y(c) + effect(c, 0) + namespace(c) +
            floor(c, gd, base + t(c, 0)) +
            auth(c, gd, base + t(c, 0) + grant(0, c.plan.authStopGrant!!)) +
            p(c, base))
    }

    @Test fun A04d_updateAuthAnswer_fenceChangeWithoutEffects_stillNamespaceSlot() {
        val c = answerFenceOnlyCase()
        val dcs = c.plan.decision!!
        assertTrue(dcs.effects.isEmpty())
        assertTrue(dcs.acceptedBeforeFence != dcs.confirmedAfterFence)
        val base = authEvent(c)
        val gd = c.descriptor.targets[0]
        check(c, b(c, E, d(c)) + y(c) + namespace(c) +
            floor(c, gd, base + t(c, 0)) +
            auth(c, gd, base + t(c, 0) + grant(0, c.plan.authStopGrant!!)) +
            p(c, base))
    }

    @Test fun A04c_answerRetryIdAuth_authUsesStopGrant_requestUsesMapEntry() {
        val c = answerRetryNamedAuthCase()
        val stop = c.plan.authStopGrant!!; val mapped = c.plan.grants.getValue("auth")
        assertTrue("the retry grant overwrote the map's auth entry", stop != mapped)
        val base = authEvent(c)
        val gd = c.descriptor.targets[0]; val retry = c.descriptor.targets[1]
        check(c, b(c, E, d(c)) + y(c) +
            floor(c, gd, base + t(c, 0)) + auth(c, gd, base + t(c, 0) + grant(0, stop)) +
            q(c, retry, base + t(c, 1) + grant(1, mapped)) +
            p(c, base))
    }

    @Test fun A04e_keptRequestNamedAuth_getsNoGrant_evenThoughTheMapHoldsTheStopGrant() {
        val c = answerKeptRequestNamedAuthCase()
        assertEquals("the map's auth entry is the stop grant", c.plan.authStopGrant, c.plan.grants["auth"])
        val base = authEvent(c)
        val gd = c.descriptor.targets[0]
        check(c, b(c, E, d(c)) + y(c) +
            floor(c, gd, base + t(c, 0)) + auth(c, gd, base + t(c, 0) + grant(0, c.plan.authStopGrant!!)) +
            q(c, c.descriptor.requiredUnchanged[0], base + u(c, 0)) +
            p(c, base))
    }

    @Test fun A05_updateAuthInitialize_authOnly_subjectFromAfter() {
        val c = initializeCase()
        assertEquals(initialAuth(F.binding), guardOf(c.descriptor.targets[0].after)!!.auth)
        val base = authEvent(c)
        check(c, b(c, E, d(c)) + auth(c, c.descriptor.targets[0], base + t(c, 0)) + p(c, base))
    }

    @Test fun A06_updateAuthCaller_eventOrderOnAuth_retryGrantOnRequest() {
        val c = callerCase("caller-retry", 60_000)
        val base = authEvent(c)
        val gd = c.descriptor.targets[0]; val retry = c.descriptor.targets[1]
        val callerOrder = (c.plan.event as LifecycleAuthEvent.Caller).fact.order
        assertNull(c.plan.authStopGrant)
        check(c, b(c, E, d(c)) +
            floor(c, gd, base + t(c, 0)) + auth(c, gd, base + t(c, 0) + grant(0, callerOrder)) +
            q(c, retry, base + t(c, 1) + grant(1, c.plan.grants.getValue("caller-retry"))) +
            p(c, base))
    }

    @Test fun A06b_updateAuthCaller_noFloor_noRetry() {
        val c = callerCase("caller-retry", null)
        val base = authEvent(c)
        val callerOrder = (c.plan.event as LifecycleAuthEvent.Caller).fact.order
        check(c, b(c, E, d(c)) + auth(c, c.descriptor.targets[0], base + t(c, 0) + grant(0, callerOrder)) + p(c, base))
    }

    @Test fun A06c_callerRetryIdAuth_authUsesEventOrder_requestUsesMapEntry() {
        val c = callerCase("auth", 60_000)
        val callerOrder = (c.plan.event as LifecycleAuthEvent.Caller).fact.order
        val mapped = c.plan.grants.getValue("auth")
        assertTrue("the retry grant overwrote the map's auth entry", callerOrder != mapped)
        val base = authEvent(c)
        val gd = c.descriptor.targets[0]; val retry = c.descriptor.targets[1]
        check(c, b(c, E, d(c)) +
            floor(c, gd, base + t(c, 0)) + auth(c, gd, base + t(c, 0) + grant(0, callerOrder)) +
            q(c, retry, base + t(c, 1) + grant(1, mapped)) +
            p(c, base))
    }

    @Test fun A07_updateAuthRecovery_authOnly() {
        val c = recoveryCase()
        val base = authEvent(c)
        check(c, b(c, E, d(c)) + auth(c, c.descriptor.targets[0], base + t(c, 0)) + p(c, base))
    }

    // ═══ END_AUTH_BINDING ═══════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun A08_end_withoutReplacement_bindingAndAuthLandingAreCompletion_floorKept() {
        val c = endCase(replacement = false)
        assertNull(guardOf(c.descriptor.targets[0].after)!!.auth)
        val base = closure(c)
        val gd = c.descriptor.targets[0]; val r = c.descriptor.targets[1]
        check(c, b(c, C, base) +
            floor(c, gd, base + t(c, 0)) + auth(c, gd, base + t(c, 0), endWithoutReplacement = true) +
            q(c, r, base + t(c, 1) + grant(1, c.plan.grants.getValue("rebound"))) +
            p(c, base))
    }

    @Test fun A09_end_withReplacement_newAuthLanding() {
        val c = endCase(replacement = true)
        assertEquals(initialAuth(F.binding), guardOf(c.descriptor.targets[0].after)!!.auth)
        val base = closure(c)
        val gd = c.descriptor.targets[0]; val r = c.descriptor.targets[1]
        check(c, b(c, C, base) +
            floor(c, gd, base + t(c, 0)) + auth(c, gd, base + t(c, 0)) +
            q(c, r, base + t(c, 1) + grant(1, c.plan.grants.getValue("rebound"))) +
            p(c, base))
    }

    @Test fun A10_end_withoutFloor_noFloorSlot() {
        val c = endCase(replacement = false, wait = null)
        val base = closure(c)
        val gd = c.descriptor.targets[0]; val r = c.descriptor.targets[1]
        check(c, b(c, C, base) + auth(c, gd, base + t(c, 0), endWithoutReplacement = true) +
            q(c, r, base + t(c, 1) + grant(1, c.plan.grants.getValue("rebound"))) +
            p(c, base))
    }

    // ═══ Unavailable: common 1-13 ═══════════════════════════════════════════════════════════════════════════════════════
    private val inconsistent = RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT
    private fun descriptor(c: Case, transition: LifecycleTransition = c.descriptor.transition,
        targets: List<LifecycleFixedTarget> = c.descriptor.targets, unchanged: List<LifecycleFixedTarget> = c.descriptor.requiredUnchanged,
        executor: SettlementExecutor? = c.descriptor.executor, plan: DemandAuthPlan? = c.plan) =
        ControlLifecycleDescriptor(op, transition, targets, executor, requiredUnchanged = unchanged, demandAuth = plan)
    private fun lifecycleInput(d: ControlLifecycleDescriptor): RequirementInput.Lifecycle {
        val body = ControlCommandBody.Lifecycle(d)
        return RequirementInput.Lifecycle(CommandRef(op, body, OwnerTrackingLifetimeId.issue()), body)
    }
    private fun prepared(p: DemandAuthPlan): RequirementInput.Lifecycle {
        assertTrue("fixture must carry a preparation failure", p.preparationFailure != null)
        return lifecycleInput(p.descriptor(op))
    }

    @Test fun B01_terminatedRef_bodyUnavailable() {
        val c = rebindCase()
        c.ref.beginTermination(); c.ref.completeTermination()
        unavailable(c.input, RequiredObligationsUnavailable.BODY_UNAVAILABLE, FixedInputRoot.COMMAND, null, FixedInputFacet.WHOLE)
    }

    @Test fun B02_bodyNotTheRefsBody_mismatch() {
        val c = rebindCase()
        unavailable(RequirementInput.Lifecycle(c.ref, ControlCommandBody.Lifecycle(c.plan.descriptor(op))),
            RequiredObligationsUnavailable.BODY_MISMATCH, FixedInputRoot.COMMAND, null, FixedInputFacet.WHOLE)
    }

    @Test fun B03_planMissing() {
        val c = rebindCase()
        unavailable(lifecycleInput(descriptor(c, plan = null)), RequiredObligationsUnavailable.PLAN_MISSING,
            FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
    }

    @Test fun B04_preparationFailure_eachTransition() {
        val whole = Triple(FixedInputRoot.LIFECYCLE_PLAN, null as Int?, FixedInputFacet.WHOLE)
        val orders = { LifecycleOrderSource(F.life, 21) }
        val plans = listOf(
            DemandAuthPlan.rebind(listOf(F.request()), F.binding, orders()), // already current scope → InvalidRebind
            DemandAuthPlan.settle(emptyList(), F.request(), null, F.binding, F.decision(), orders(), "g-new", "r-new"), // REQUEST as guard
            DemandAuthPlan.auth(F.guard(auth = null), null, F.binding,
                LifecycleAuthEvent.Caller(LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding,
                    LifecycleOrderSource(F.life, 21).issue(F.binding.startedOrder)!!, RefreshIntent.FORCE_PREMIUM, F.now)),
                orders(), "g-new", "r-new"), // Caller without AUTH
            DemandAuthPlan.end(F.request(), emptyList(), F.binding,
                LifecycleBindingClosure(F.auth, true, setOf(), setOf(), 5), null, orders())) // REQUEST as guard
        for (p in plans) unavailable(prepared(p), inconsistent, whole.first, whole.second, whole.third)
        // a failure after one valid row: the descriptor keeps a valid shape, so only the recorded failure rejects it
        val partial = DemandAuthPlan.rebind(listOf(F.request(id = "r0", binding = 2, origin = old, order = 500), F.request(id = "r1")),
            F.binding, orders())
        assertEquals(1, partial.targets.size)
        unavailable(prepared(partial), inconsistent, whole.first, whole.second, whole.third)
    }

    @Test fun B05_uninterpretableTargetSource_andUnchanged() {
        val bad = F.node("""{"kind":"REQUEST","id":"r0"}""")
        val c = rebindCase()
        val t0 = c.descriptor.targets[0]
        unavailable(lifecycleInput(descriptor(c, targets = listOf(t0.copy(before = bad)) + c.descriptor.targets.drop(1))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_TARGET, 0, FixedInputFacet.SOURCE)
        unavailable(lifecycleInput(descriptor(c, targets = listOf(t0.copy(after = bad)) + c.descriptor.targets.drop(1))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_TARGET, 0, FixedInputFacet.AFTER)
        val s = settleEmptyGuardCase()
        val badGuard = F.node("""{"kind":"SCHEDULE_GUARD","id":"g","floor":{}}""")
        val u0 = s.descriptor.requiredUnchanged[0]
        unavailable(lifecycleInput(descriptor(s, unchanged = listOf(u0.copy(before = badGuard, after = badGuard)))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_UNCHANGED, 0, FixedInputFacet.SOURCE)
        unavailable(lifecycleInput(descriptor(s, unchanged = listOf(u0.copy(after = badGuard)))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_UNCHANGED, 0, FixedInputFacet.AFTER)
    }

    @Test fun B06_invalidDescriptorShape() {
        val c = rebindCase()
        val t0 = c.descriptor.targets[0]
        unavailable(lifecycleInput(descriptor(c, targets = listOf(t0.copy(target = t0.target.copy(effect = LifecycleEffect.CREATE))) +
            c.descriptor.targets.drop(1))), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
    }

    @Test fun B07_descriptorDisagreesWithPlan() {
        val c = rebindCase()
        unavailable(lifecycleInput(descriptor(c, transition = LifecycleTransition.SETTLE_QUERY)),
            inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        unavailable(lifecycleInput(descriptor(c, executor = SettlementExecutor("A", 9, F.life))),
            inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.BINDING)
        val withNamespace = ControlLifecycleDescriptor(op, c.descriptor.transition, c.descriptor.targets, c.descriptor.executor,
            namespace = LifecycleNamespacePostcondition(F.fence, F.fence.copy(userAccessEpoch = "u2"), listOf()),
            requiredUnchanged = c.descriptor.requiredUnchanged, demandAuth = c.plan)
        unavailable(lifecycleInput(withNamespace), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        // another writer's plan bound to the same descriptor; reject the mixed writer descriptor before dispatch
        val twoPlans = ControlLifecycleDescriptor(op, c.descriptor.transition, c.descriptor.targets, c.descriptor.executor,
            requiredUnchanged = c.descriptor.requiredUnchanged, demandAuth = c.plan,
            removeEmptyGuard = RemoveEmptyGuardPlan.prepare(F.guard(auth = null)))
        unavailable(lifecycleInput(twoPlans), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        // the same shape under another transition name
        val kept = settleFloorOnlyKeptRetryCase()
        unavailable(lifecycleInput(descriptor(kept, transition = LifecycleTransition.UPDATE_AUTH)),
            inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        // same row count, different row content (target and requiredUnchanged)
        val t1 = c.descriptor.targets[1]
        val otherBefore = F.request(id = "r1", binding = 2, origin = old, intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 502)
        unavailable(lifecycleInput(descriptor(c, targets = c.descriptor.targets.take(1) + t1.copy(before = otherBefore))),
            inconsistent, FixedInputRoot.LIFECYCLE_TARGET, 1, FixedInputFacet.WHOLE)
        val k0 = kept.descriptor.requiredUnchanged[0]
        val otherKept = F.request(id = "reuse", order = 9)
        unavailable(lifecycleInput(descriptor(kept, unchanged = listOf(k0.copy(before = otherKept, after = otherKept)))),
            inconsistent, FixedInputRoot.LIFECYCLE_UNCHANGED, 0, FixedInputFacet.WHOLE)
        unavailable(lifecycleInput(descriptor(c, targets = c.descriptor.targets.take(1))),
            inconsistent, FixedInputRoot.LIFECYCLE_TARGET, 1, FixedInputFacet.WHOLE)
        val s = settleFullCase()
        val extra = fixed("extra-guard", LifecycleRole.GUARD, F.guard(auth = null, id = "extra-guard"), F.guard(auth = null, id = "extra-guard"))
        unavailable(lifecycleInput(descriptor(s, unchanged = listOf(extra))),
            inconsistent, FixedInputRoot.LIFECYCLE_UNCHANGED, 0, FixedInputFacet.WHOLE)
    }

    // ═══ Unavailable: U1 / U2 (the AUTH slot would silently vanish) ═════════════════════════════════════════════════════
    @Test fun B08_answerOnGuardWithoutAuth() {
        val p = F.plan(guard = F.guard(auth = null), retry = F.request())
        assertNull(p.preparationFailure)
        unavailable(lifecycleInput(p.descriptor(op)), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.EVENT)
    }

    @Test fun B09_initializeWithoutIdentity() {
        val p = DemandAuthPlan.auth(null, null, F.binding.copy(identity = null), LifecycleAuthEvent.Initialize,
            LifecycleOrderSource(F.life, 21), "g-new", "r-new")
        assertNull(p.preparationFailure)
        unavailable(lifecycleInput(p.descriptor(op)), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.BINDING)
    }

    // ═══ the other three transitions (6-4bA1c-2): without their named plan they end in PLAN_MISSING ═════════════════════════════════════════════════════════════
    @Test fun X03_removeEmptyGuardRecoverHoldRecoverIntent_withoutPlan_arePlanMissing() {
        for (tr in listOf(LifecycleTransition.REMOVE_EMPTY_GUARD, LifecycleTransition.RECOVER_HOLD, LifecycleTransition.RECOVER_INTENT)) {
            val body = ControlCommandBody.Lifecycle(ControlLifecycleDescriptor("op-x", tr, listOf()))
            unavailable(RequirementInput.Lifecycle(CommandRef("op-x", body, OwnerTrackingLifetimeId.issue()), body),
                RequiredObligationsUnavailable.PLAN_MISSING, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        }
    }
}
