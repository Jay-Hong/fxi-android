package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Claude-owned 6-4bA1c-2 contract: REMOVE_EMPTY_GUARD, RECOVER_HOLD and RECOVER_INTENT of the pure required-obligation
 * derivation, and the removal of the temporary UNSUPPORTED_IN_THIS_UNIT reason. Fixed inputs: 6-4bA1c-2 consensus
 * (table 6-4bA1c2_table_codex.r1.md §1 + Unavailable 1-7 as revised), recipes 6-4bA1c2_recipes_codex.r1.md. Expected source
 * values are written with constructors field by field; expected lists are written here, independently of the deriver.
 *
 * Roles: row slots ObligationRole.Lifecycle(transition, row.role); journal, retirement and receipt use LifecycleCommand.
 * fixedSources: G = [(LIFECYCLE_PLAN, null, WHOLE): LifecyclePlan(descriptor)]; HOLD base h0 = G + BINDING(input.binding) +
 * CLOSURE(RecoveryClosure(input.closure)) + FLOOR(Boot(input.mergeNow)) + W; INTENT base i0 = G + BINDING + CLOSURE + W;
 * W = [(LIFECYCLE_NAMESPACE, null, WHOLE): Namespace, (…, BEFORE): Fence(before), (…, AFTER): Fence(after)].
 * T_i / U_j are suffixes after a base (no repeated G); the current-owner REQUEST appends (LIFECYCLE_PLAN, i, GRANT): requestOrder.
 * Order (HOLD): H, source floor F_h (if any), per source axis USER then CAPABILITY J_a then R_a, REQUEST Q (current owner) or
 * NamedRequest(null) N/A pair (departed owner), guard row F_g then A_g (or X= for a kept empty guard), receipt P.
 * The implementation thread reads but does not edit this file.
 */
class RequiredObligationsRecoveryContractTest {
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val E = AllowedSlotDisposition.EITHER
    private val C = AllowedSlotDisposition.COMPLETED_AND_CONSUMED_ONLY
    private val D = AllowedSlotDisposition.DURABLY_OWNED_ONLY
    private val H = HoldRecoveryFixtures
    private val oldLife = LifetimeId("life")
    private val newLife = LifetimeId("new-life")
    private val beforeA = FenceV1("A", "u", "k")

    // ── independent source values (recipes §3) ─────────────────────────────────────────────────────────────────────────
    private fun query(fence: FenceV1) = HoldProvenanceV1.Query(StartedQueryV1(fence, 5, IdentityV1("A", 2),
        EventOrderV1(oldLife, Long.MAX_VALUE), 17, RefreshIntent.FORCE_PREMIUM, 0), IdentityV1("A", 2))
    private val sourceFloor = FloorV1("boot", 10000, 30000, oldLife)
    private fun pendingHold(fence: FenceV1 = beforeA, floor: Boolean) = RestoredHold("h", oldLife, 17, setOf(PurgeScope.CAPABILITY),
        HoldOutcomeV1(HoldOutcomeKind.PENDING, false, if (floor) 30 else null), query(fence), if (floor) sourceFloor else null)
    private val bothHold = RestoredHold("h", oldLife, 17, setOf(PurgeScope.USER, PurgeScope.CAPABILITY),
        HoldOutcomeV1(HoldOutcomeKind.PREMIUM_REQUIRED, null, null), query(beforeA), null)

    // ── cases ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    private class Case(val ref: CommandRef) {
        val body = ref.body as ControlCommandBody.Lifecycle
        val descriptor get() = body.input
        val input get() = RequirementInput.Lifecycle(ref, body)
    }
    private val codec = ControlPayloadCodec()
    /** Fixture guard: the named writer's own pure descriptor check, which the deriver reuses. */
    private fun namedValid(d: ControlLifecycleDescriptor): Boolean = when (d.transition) {
        LifecycleTransition.RECOVER_HOLD -> RecoverHoldTransition(codec).validDescriptor(d.recoverHold!!, d)
        LifecycleTransition.RECOVER_INTENT -> RecoverIntentTransition(codec).validDescriptor(d.recoverIntent!!, d)
        else -> error("no named descriptor check")
    }
    private fun rows(xs: List<LifecycleFixedTarget>) = xs.map { "${it.role}/${it.target.effect}/${it.target.id}" }
    private fun holdCase(input: RecoverHoldInput, ids: RecoverHoldIds, targets: List<String>, unchanged: List<String> = emptyList(),
        orders: LifecycleOrderSource = LifecycleOrderSource(newLife, 21)): Pair<Case, RecoverHoldPlan> {
        val plan = RecoverHoldPlan.prepare(input, ids, orders)
        assertNull("recipe must prepare", plan.preparationProblem)
        val c = Case(CommandRef(ids.operationId, ControlCommandBody.Lifecycle(plan.descriptor()), OwnerTrackingLifetimeId.issue()))
        assertEquals(targets, rows(c.descriptor.targets)); assertEquals(unchanged, rows(c.descriptor.requiredUnchanged))
        assertTrue("named descriptor check accepts the recipe", namedValid(c.descriptor))
        return c to plan
    }
    private val intentOp = "00000000-0000-0000-0000-000000000201"
    private fun intentNode(owner: String, axis: String, target: String?) = ControlObligationFixtures.node(
        """{"id":"r","sessionId":"session","ownerUid":"$owner","axis":"$axis","targetEpoch":${target?.let { "\"$it\"" } ?: "null"}}""")
    private fun intentCase(source: ControlNode, epochs: RecoveryFreshEpochs, targets: List<String>,
        orders: LifecycleOrderSource = LifecycleOrderSource(newLife, 21)): Pair<Case, RecoverIntentPlan> {
        val input = RecoverIntentInput(source, beforeA, H.binding, HoldRecoveryClosure.AfterRestart(source, H.executor, "old-tracking", true, true))
        val plan = RecoverIntentPlan.prepare(input, RecoverIntentIds(intentOp, "intent-request", epochs), orders)
        assertNull("recipe must prepare", plan.preparationProblem)
        val c = Case(CommandRef(intentOp, ControlCommandBody.Lifecycle(plan.descriptor()), OwnerTrackingLifetimeId.issue()))
        assertEquals(targets, rows(c.descriptor.targets)); assertTrue(c.descriptor.requiredUnchanged.isEmpty())
        assertTrue("named descriptor check accepts the recipe", namedValid(c.descriptor))
        return c to plan
    }

    // ── fixed sources ──────────────────────────────────────────────────────────────────────────────────────────────────
    private fun ev(root: FixedInputRoot, index: Int?, facet: FixedInputFacet, f: FixedSourceFact) =
        FixedSourceEvidence(FixedInputLocation(root, index, facet), f)
    private fun g(c: Case) = listOf(ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE, FixedSourceFact.LifecyclePlan(c.descriptor)))
    private fun w(c: Case): List<FixedSourceEvidence> {
        val ns = c.descriptor.namespace!!
        return listOf(ev(FixedInputRoot.LIFECYCLE_NAMESPACE, null, FixedInputFacet.WHOLE, FixedSourceFact.Namespace(ns)),
            ev(FixedInputRoot.LIFECYCLE_NAMESPACE, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(ns.before)),
            ev(FixedInputRoot.LIFECYCLE_NAMESPACE, null, FixedInputFacet.AFTER, FixedSourceFact.Fence(ns.after)))
    }
    private fun recovery(c: Case, binding: LifecycleBinding, closure: HoldRecoveryClosure, mergeNow: BootReading?) = g(c) +
        ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.BINDING, FixedSourceFact.Binding(binding)) +
        ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.CLOSURE, FixedSourceFact.RecoveryClosure(closure)) +
        listOfNotNull(mergeNow?.let { ev(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.FLOOR, FixedSourceFact.Boot(it)) }) +
        w(c)
    private fun h0(c: Case, p: RecoverHoldPlan) = recovery(c, p.input.binding, p.input.closure, p.input.mergeNow)
    private fun i0(c: Case, p: RecoverIntentPlan) = recovery(c, p.input.binding, p.input.closure, null)
    private fun row(root: FixedInputRoot, i: Int, t: LifecycleFixedTarget): List<FixedSourceEvidence> = listOfNotNull(
        ev(root, i, FixedInputFacet.WHOLE, FixedSourceFact.LifecycleTarget(t)),
        (t.before ?: t.after)?.let { ev(root, i, FixedInputFacet.SOURCE, FixedSourceFact.Node(t.target.kind, it)) },
        t.after?.let { ev(root, i, FixedInputFacet.AFTER, FixedSourceFact.Node(t.target.kind, it)) })
    private fun t(c: Case, i: Int) = row(FixedInputRoot.LIFECYCLE_TARGET, i, c.descriptor.targets[i])
    private fun u(c: Case, j: Int) = row(FixedInputRoot.LIFECYCLE_UNCHANGED, j, c.descriptor.requiredUnchanged[j])
    private fun grant(i: Int, grant: LifecycleOrderGrant) = listOf(ev(FixedInputRoot.LIFECYCLE_PLAN, i, FixedInputFacet.GRANT, FixedSourceFact.OrderGrant(grant)))
    private fun everyRow(c: Case) = c.descriptor.targets.indices.flatMap { t(c, it) } + c.descriptor.requiredUnchanged.indices.flatMap { u(c, it) }

    // ── expected slots ─────────────────────────────────────────────────────────────────────────────────────────────────
    private fun req(role: ObligationRole, subject: ObligationSubject, component: ObligationComponent, b: LandingBranch,
        bound: RequiredLowerBound, allowed: AllowedSlotDisposition, f: List<FixedSourceEvidence>) =
        RequiredSlot(RequiredObligationKey(role, subject, component, b), SlotRequirement.Required(bound, allowed, f))
    private fun na(role: ObligationRole, subject: ObligationSubject, component: ObligationComponent, b: LandingBranch, f: List<FixedSourceEvidence>) =
        RequiredSlot(RequiredObligationKey(role, subject, component, b), SlotRequirement.NotRequiredByContract(f))
    private fun cmd(c: Case) = ObligationRole.LifecycleCommand(c.descriptor.transition)
    private fun rowRole(c: Case, r: LifecycleRole) = ObligationRole.Lifecycle(c.descriptor.transition, r)

    private fun holdSlots(c: Case, x: RestoredHold, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        req(rowRole(c, LifecycleRole.HOLD), ObligationSubject.Hold(x.id, x.originLifetimeId, x.binding, x.provenance, x.axes),
            ObligationComponent.SOURCE, it, RequiredLowerBound.Hold(x), E, f) }
    private fun holdFloor(c: Case, x: RestoredHold, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        req(rowRole(c, LifecycleRole.HOLD), ObligationSubject.Floor(ControlKind.HOLD, x.id, x.floor!!.originLifetimeId),
            ObligationComponent.FLOOR, it, RequiredLowerBound.Floor(ControlKind.HOLD, x.id, x.floor), D, f) }
    private fun intentSlots(c: Case, t: RecoveryIntentV1, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        req(rowRole(c, LifecycleRole.RECOVERY_INTENT), ObligationSubject.Intent(t.id, t.sessionId, t.ownerUid, t.axis, t.targetEpoch),
            ObligationComponent.SOURCE, it, RequiredLowerBound.Intent(t), E, f) }
    private fun journalAndRetirement(c: Case, sourceId: String, key: JournalTargetV1, f: List<FixedSourceEvidence>): List<RequiredSlot> {
        val ns = c.descriptor.namespace!!
        val scope = RetirementScope(sourceId, ns.before, ns.after, key)
        return listOf(L, N).map { req(cmd(c), ObligationSubject.Journal(sourceId, key), ObligationComponent.JOURNAL, it, RequiredLowerBound.Journal(key), E, f) } +
            listOf(L, N).map { req(cmd(c), scope, ObligationComponent.NAMESPACE_RETIREMENT, it, RequiredLowerBound.NamespaceRetirement(scope), E, f) }
    }
    private fun request(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>): List<RequiredSlot> {
        val a = demand(fixed.after)!!
        val bound = RequiredLowerBound.Request(a, a.id, a.ownerUid, a.binding, a.intent, a.raisedAt)
        return listOf(L, N).map { req(rowRole(c, LifecycleRole.REQUEST), ObligationSubject.Request(a.id, a.ownerUid, a.binding, a.raisedAt),
            ObligationComponent.REQUEST, it, bound, E, f) }
    }
    private fun noRequest(c: Case, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        na(rowRole(c, LifecycleRole.REQUEST), ObligationSubject.NamedRequest(null), ObligationComponent.REQUEST, it, f) }
    private fun guardFloor(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>): List<RequiredSlot> {
        val old = guard(fixed.before)?.floor; val need = guard(fixed.after)!!.floor!!
        return listOf(L, N).map { req(rowRole(c, LifecycleRole.GUARD), ObligationSubject.Floor(ControlKind.DEMAND, fixed.target.id,
            (old ?: need).originLifetimeId), ObligationComponent.FLOOR, it, RequiredLowerBound.Floor(ControlKind.DEMAND, fixed.target.id, need), D, f) }
    }
    private fun guardAuth(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>): List<RequiredSlot> {
        val old = guard(fixed.before)!!.auth!!; val after = guard(fixed.after)!!.auth!!
        val subject = ObligationSubject.Auth(old.ownerUid, old.binding, old.originLifetimeId, old.authGeneration)
        return listOf(req(rowRole(c, LifecycleRole.GUARD), subject, ObligationComponent.AUTH, L, RequiredLowerBound.Auth(after), E, f),
            req(rowRole(c, LifecycleRole.GUARD), subject, ObligationComponent.AUTH, N, RequiredLowerBound.Auth(old), E, f))
    }
    private fun keptEmpty(c: Case, fixed: LifecycleFixedTarget, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        req(rowRole(c, LifecycleRole.GUARD), ObligationSubject.ExactTarget(ControlKind.DEMAND, fixed.target.id), ObligationComponent.SOURCE, it,
            RequiredLowerBound.ExactSource(ControlKind.DEMAND, fixed.target.id, fixed.before!!), E, f) }
    private fun receipt(c: Case, base: List<FixedSourceEvidence>): List<RequiredSlot> {
        val scope = ReceiptScope(c.descriptor.operationId, c.descriptor.transition, null, null)
        val f = base + everyRow(c)
        return listOf(req(cmd(c), scope, ObligationComponent.RECEIPT, L, RequiredLowerBound.Receipt(scope), C, f),
            na(cmd(c), scope, ObligationComponent.RECEIPT, N, f))
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
            val drop = required.firstOrNull { it.key.branch == branch } ?: continue
            assertEquals(CoverageResult.Rejected(listOf(problem)), checkSlotCoverage(exp, required - drop))
        }
    }
    private fun unavailable(input: RequirementInput, reason: RequiredObligationsUnavailable, root: FixedInputRoot, index: Int?, facet: FixedInputFacet) =
        assertEquals(RequirementDerivation.Unavailable(reason, FixedInputLocation(root, index, facet)), deriveRequiredObligations(input))
    private val capKey = JournalTargetV1("A", PurgeScope.CAPABILITY, "k")
    private val userKey = JournalTargetV1("A", PurgeScope.USER, "u")

    // ═══ REMOVE_EMPTY_GUARD ═════════════════════════════════════════════════════════════════════════════════════════════
    private fun removeCase(guardNode: ControlNode = FloorGuardFixtures.empty, op: String = "remove-op"): Case {
        val d = RemoveEmptyGuardPlan.prepare(guardNode).descriptor(op)
        return Case(CommandRef(op, ControlCommandBody.Lifecycle(d), OwnerTrackingLifetimeId.issue()))
    }

    @Test fun C01_removeEmptyGuard_completionOnLanding_abandonAllowedOtherwise() {
        val c = removeCase()
        assertEquals(listOf("GUARD/REMOVE/g"), rows(c.descriptor.targets))
        val base = g(c)
        val fixed = c.descriptor.targets[0]
        val subject = ObligationSubject.ExactTarget(ControlKind.DEMAND, "g")
        val f = base + t(c, 0)
        check(c, listOf(req(rowRole(c, LifecycleRole.GUARD), subject, ObligationComponent.SOURCE, L,
                RequiredLowerBound.ExactSource(ControlKind.DEMAND, "g", fixed.before!!), C, f),
            na(rowRole(c, LifecycleRole.GUARD), subject, ObligationComponent.SOURCE, N, f)) + receipt(c, base))
    }

    // ═══ RECOVER_HOLD ═══════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun C02_hold_currentOwner_sourceFloor_guardReplaced() {
        val (c, p) = holdCase(H.input(H.hold(), H.guard()), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request", "GUARD/REPLACE/g"))
        val x = pendingHold(floor = true)
        val base = h0(c, p)
        val gd = c.descriptor.targets[2]
        assertEquals(LifecycleOrderGrant(newLife, 21, 1, 0, 22), p.requestOrder)
        check(c, holdSlots(c, x, base + t(c, 0)) + holdFloor(c, x, base + t(c, 0)) +
            journalAndRetirement(c, "h", capKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            guardFloor(c, gd, base + t(c, 0) + t(c, 2)) + guardAuth(c, gd, base + t(c, 0) + t(c, 2)) +
            receipt(c, base))
    }

    @Test fun C03_hold_noSourceFloor_noGuard() {
        val (c, p) = holdCase(H.input(H.hold(floor = false), null), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"))
        val x = pendingHold(floor = false)
        val base = h0(c, p)
        check(c, holdSlots(c, x, base + t(c, 0)) + journalAndRetirement(c, "h", capKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            receipt(c, base))
    }

    @Test fun C04_hold_departedOwner_requestNotRequired_newGuardCarriesSourceFloor() {
        val before = FenceV1("B", "u", "k")
        val binding = LifecycleBinding(SettlementExecutor("B", 3, newLife), IdentityV1("B", 2), 1, "binding-start")
        val (c, p) = holdCase(H.input(H.hold(owner = "A", krx = "k0", floor = true), null, before, binding),
            H.ids.copy(epochs = RecoveryFreshEpochs(null, null)), listOf("HOLD/REMOVE/h", "GUARD/CREATE/new-guard"),
            orders = LifecycleOrderSource(LifetimeId("unused-order-origin"), 21))
        assertNull(p.requestOrder)
        val x = pendingHold(FenceV1("A", "u", "k0"), floor = true)
        val base = h0(c, p)
        val gd = c.descriptor.targets[1]
        assertNull("a newly created guard has no AUTH", guard(gd.after)!!.auth)
        check(c, holdSlots(c, x, base + t(c, 0)) + holdFloor(c, x, base + t(c, 0)) +
            journalAndRetirement(c, "h", JournalTargetV1("A", PurgeScope.CAPABILITY, "k0"), base + t(c, 0)) +
            noRequest(c, base + t(c, 0)) +
            guardFloor(c, gd, base + t(c, 0) + t(c, 1)) +
            receipt(c, base))
    }

    @Test fun C05_hold_noSourceFloor_keptGuardWithFloorAndAuth() {
        val (c, p) = holdCase(H.input(H.hold(floor = false), H.guard()), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"),
            listOf("GUARD/REPLACE/g"))
        val x = pendingHold(floor = false)
        val base = h0(c, p)
        val kept = c.descriptor.requiredUnchanged[0]
        check(c, holdSlots(c, x, base + t(c, 0)) + journalAndRetirement(c, "h", capKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            guardFloor(c, kept, base + t(c, 0) + u(c, 0)) + guardAuth(c, kept, base + t(c, 0) + u(c, 0)) +
            receipt(c, base))
    }

    @Test fun C06_hold_noSourceFloor_keptEmptyGuard_isExactSource() {
        val (c, p) = holdCase(H.input(H.hold(floor = false), FloorGuardFixtures.empty), H.ids,
            listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"), listOf("GUARD/REPLACE/g"))
        val x = pendingHold(floor = false)
        val base = h0(c, p)
        check(c, holdSlots(c, x, base + t(c, 0)) + journalAndRetirement(c, "h", capKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            keptEmpty(c, c.descriptor.requiredUnchanged[0], base + t(c, 0) + u(c, 0)) +
            receipt(c, base))
    }

    @Test fun C06b_hold_noSourceFloor_keptGuardWithFloorOnly_isFloorOnly_notExactSource() {
        val floorOnly = FloorGuardFixtures.guard(FloorGuardFixtures.floor(30000), auth = false)
        val (c, p) = holdCase(H.input(H.hold(floor = false), floorOnly), H.ids,
            listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"), listOf("GUARD/REPLACE/g"))
        val kept = c.descriptor.requiredUnchanged[0]
        assertTrue(guard(kept.after)!!.floor != null); assertNull(guard(kept.after)!!.auth)
        val base = h0(c, p)
        check(c, holdSlots(c, pendingHold(floor = false), base + t(c, 0)) + journalAndRetirement(c, "h", capKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            guardFloor(c, kept, base + t(c, 0) + u(c, 0)) +
            receipt(c, base))
    }

    @Test fun C07_hold_sourceFloor_guardAlreadyAtExactMax_keptButBothFloorsStay() {
        val exact = FloorGuardFixtures.field(H.guard(), "floor", FloorGuardFixtures.floor(29000, "boot", 11000, "new-life"))
        assertEquals(FloorV1("boot", 11000, 29000, newLife), guard(exact)!!.floor)
        val (c, p) = holdCase(H.input(H.hold(), exact), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"), listOf("GUARD/REPLACE/g"))
        val x = pendingHold(floor = true)
        val base = h0(c, p)
        val kept = c.descriptor.requiredUnchanged[0]
        check(c, holdSlots(c, x, base + t(c, 0)) + holdFloor(c, x, base + t(c, 0)) +
            journalAndRetirement(c, "h", capKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            guardFloor(c, kept, base + t(c, 0) + u(c, 0)) + guardAuth(c, kept, base + t(c, 0) + u(c, 0)) +
            receipt(c, base))
    }

    @Test fun C08_hold_bothAxes_userBeforeCapability() {
        val (c, p) = holdCase(H.input(H.hold(both = true, floor = false), null),
            H.ids.copy(epochs = RecoveryFreshEpochs(H.userEpoch, H.krxEpoch)), listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"))
        val base = h0(c, p)
        check(c, holdSlots(c, bothHold, base + t(c, 0)) +
            journalAndRetirement(c, "h", userKey, base + t(c, 0)) + journalAndRetirement(c, "h", capKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            receipt(c, base))
    }

    // ═══ RECOVER_INTENT ═════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun C09_intent_currentOwnerCapability() {
        val (c, p) = intentCase(intentNode("A", "CAPABILITY", "k"), RecoveryFreshEpochs(null, H.krxEpoch),
            listOf("RECOVERY_INTENT/REMOVE/r", "REQUEST/CREATE/intent-request"))
        val base = i0(c, p)
        check(c, intentSlots(c, RecoveryIntentV1("r", "session", "A", PurgeScope.CAPABILITY, "k"), base + t(c, 0)) +
            journalAndRetirement(c, "r", capKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            receipt(c, base))
    }

    @Test fun C10_intent_currentOwnerUser() {
        val (c, p) = intentCase(intentNode("A", "USER", "u"), RecoveryFreshEpochs(H.userEpoch, null),
            listOf("RECOVERY_INTENT/REMOVE/r", "REQUEST/CREATE/intent-request"))
        val base = i0(c, p)
        check(c, intentSlots(c, RecoveryIntentV1("r", "session", "A", PurgeScope.USER, "u"), base + t(c, 0)) +
            journalAndRetirement(c, "r", userKey, base + t(c, 0)) +
            request(c, c.descriptor.targets[1], base + t(c, 0) + t(c, 1) + grant(1, p.requestOrder!!)) +
            receipt(c, base))
    }

    @Test fun C11_intent_departedOwner_requestNotRequired() {
        val (c, p) = intentCase(intentNode("B", "CAPABILITY", null), RecoveryFreshEpochs(null, null),
            listOf("RECOVERY_INTENT/REMOVE/r"), LifecycleOrderSource(LifetimeId("unused-order-origin"), 21))
        assertNull(p.requestOrder)
        val base = i0(c, p)
        check(c, intentSlots(c, RecoveryIntentV1("r", "session", "B", PurgeScope.CAPABILITY, null), base + t(c, 0)) +
            journalAndRetirement(c, "r", JournalTargetV1("B", PurgeScope.CAPABILITY, null), base + t(c, 0)) +
            noRequest(c, base + t(c, 0)) +
            receipt(c, base))
    }

    // ═══ Unavailable ════════════════════════════════════════════════════════════════════════════════════════════════════
    private val inconsistent = RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT
    private fun lifecycleInput(d: ControlLifecycleDescriptor): RequirementInput.Lifecycle {
        val body = ControlCommandBody.Lifecycle(d)
        return RequirementInput.Lifecycle(CommandRef(d.operationId, body, OwnerTrackingLifetimeId.issue()), body)
    }
    private fun copy(d: ControlLifecycleDescriptor, targets: List<LifecycleFixedTarget> = d.targets, unchanged: List<LifecycleFixedTarget> = d.requiredUnchanged,
        executor: SettlementExecutor? = d.executor, namespace: LifecycleNamespacePostcondition? = d.namespace,
        removeEmptyGuard: RemoveEmptyGuardPlan? = d.removeEmptyGuard, recoverHold: RecoverHoldPlan? = d.recoverHold,
        recoverIntent: RecoverIntentPlan? = d.recoverIntent, demandAuth: DemandAuthPlan? = d.demandAuth) =
        ControlLifecycleDescriptor(d.operationId, d.transition, targets, executor, namespace = namespace, requiredUnchanged = unchanged,
            demandAuth = demandAuth, removeEmptyGuard = removeEmptyGuard, recoverHold = recoverHold, recoverIntent = recoverIntent)

    @Test fun D01_bodyUnavailableAndMismatch() {
        val (c, _) = holdCase(H.input(H.hold(floor = false), null), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"))
        unavailable(RequirementInput.Lifecycle(c.ref, ControlCommandBody.Lifecycle(c.descriptor)),
            RequiredObligationsUnavailable.BODY_MISMATCH, FixedInputRoot.COMMAND, null, FixedInputFacet.WHOLE)
        c.ref.beginTermination(); c.ref.completeTermination()
        unavailable(c.input, RequiredObligationsUnavailable.BODY_UNAVAILABLE, FixedInputRoot.COMMAND, null, FixedInputFacet.WHOLE)
    }

    @Test fun D02_planMissing_andAnotherWritersPlan() {
        val (c, p) = holdCase(H.input(H.hold(floor = false), null), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"))
        unavailable(lifecycleInput(copy(c.descriptor, recoverHold = null)), RequiredObligationsUnavailable.PLAN_MISSING,
            FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        unavailable(lifecycleInput(copy(c.descriptor, removeEmptyGuard = RemoveEmptyGuardPlan.prepare(FloorGuardFixtures.empty))), inconsistent,
            FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        unavailable(lifecycleInput(copy(c.descriptor, demandAuth = DemandAuthFixtures.plan())), inconsistent,
            FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        val r = removeCase()
        unavailable(lifecycleInput(copy(r.descriptor, demandAuth = DemandAuthFixtures.plan())), inconsistent,
            FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        unavailable(lifecycleInput(copy(r.descriptor, removeEmptyGuard = null)), RequiredObligationsUnavailable.PLAN_MISSING,
            FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        val (ic, _) = intentCase(intentNode("A", "CAPABILITY", "k"), RecoveryFreshEpochs(null, H.krxEpoch),
            listOf("RECOVERY_INTENT/REMOVE/r", "REQUEST/CREATE/intent-request"))
        unavailable(lifecycleInput(copy(ic.descriptor, recoverIntent = null)), RequiredObligationsUnavailable.PLAN_MISSING,
            FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
    }

    @Test fun D03_preparationProblem() {
        // departed owner whose source epoch equals the current one: preparation records a problem
        val before = FenceV1("B", "u", "k")
        val binding = LifecycleBinding(SettlementExecutor("B", 3, newLife), IdentityV1("B", 2), 1, "binding-start")
        val hold = RecoverHoldPlan.prepare(H.input(H.hold(owner = "A", krx = "k", floor = true), null, before, binding),
            H.ids.copy(epochs = RecoveryFreshEpochs(null, null)), LifecycleOrderSource(newLife, 21))
        assertTrue("fixture must carry a preparation problem", hold.preparationProblem != null)
        unavailable(lifecycleInput(hold.descriptor()), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        // current-owner CAPABILITY intent without the fresh CAPABILITY epoch
        val src = intentNode("A", "CAPABILITY", "k")
        val intent = RecoverIntentPlan.prepare(RecoverIntentInput(src, beforeA, H.binding,
            HoldRecoveryClosure.AfterRestart(src, H.executor, "old-tracking", true, true)),
            RecoverIntentIds(intentOp, "intent-request", RecoveryFreshEpochs(null, null)), LifecycleOrderSource(newLife, 21))
        assertTrue("fixture must carry a preparation problem", intent.preparationProblem != null)
        unavailable(lifecycleInput(intent.descriptor()), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
    }

    @Test fun D03b_preparationProblemThatPassesBothDescriptorChecks() {
        // departed owner (no REQUEST row): a negative startedOrder records a preparation problem that neither the general nor the
        // named descriptor check notices — only the explicit preparationProblem gate rejects it (measurement r1, P.hold/intentProblem)
        val before = FenceV1("B", "u", "k")
        val binding = LifecycleBinding(SettlementExecutor("B", 3, newLife), IdentityV1("B", 2), 1, "binding-start")
        val (hc, hp) = holdCase(H.input(H.hold(owner = "A", krx = "k0", floor = true), null, before, binding),
            H.ids.copy(epochs = RecoveryFreshEpochs(null, null)), listOf("HOLD/REMOVE/h", "GUARD/CREATE/new-guard"),
            orders = LifecycleOrderSource(LifetimeId("unused-order-origin"), 21))
        val badHold = RecoverHoldPlan.prepare(hp.input.copy(binding = hp.input.binding.copy(startedOrder = -1)), hp.ids, LifecycleOrderSource(newLife, 21))
        assertTrue("fixture must carry a preparation problem", badHold.preparationProblem != null)
        val hd = copy(hc.descriptor, recoverHold = badHold)
        assertTrue(ControlLifecycleConfirmation(codec).validDescriptor(hd)); assertTrue(namedValid(hd))
        unavailable(lifecycleInput(hd), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        val (ic, ip) = intentCase(intentNode("B", "CAPABILITY", null), RecoveryFreshEpochs(null, null),
            listOf("RECOVERY_INTENT/REMOVE/r"), LifecycleOrderSource(LifetimeId("unused-order-origin"), 21))
        val badIntent = RecoverIntentPlan.prepare(ip.input.copy(binding = ip.input.binding.copy(startedOrder = -1)), ip.ids, LifecycleOrderSource(newLife, 21))
        assertTrue("fixture must carry a preparation problem", badIntent.preparationProblem != null)
        val id = copy(ic.descriptor, recoverIntent = badIntent)
        assertTrue(ControlLifecycleConfirmation(codec).validDescriptor(id)); assertTrue(namedValid(id))
        unavailable(lifecycleInput(id), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
    }

    @Test fun D04_uninterpretableSourceAndAfter() {
        val (c, _) = holdCase(H.input(H.hold(floor = false), null), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"))
        val bad = ControlObligationFixtures.node("""{"id":"h"}""")
        val t0 = c.descriptor.targets[0]; val t1 = c.descriptor.targets[1]
        unavailable(lifecycleInput(copy(c.descriptor, targets = listOf(t0.copy(before = bad), t1))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_TARGET, 0, FixedInputFacet.SOURCE)
        val badRequest = ControlObligationFixtures.node("""{"kind":"REQUEST","id":"new-request"}""")
        unavailable(lifecycleInput(copy(c.descriptor, targets = listOf(t0, t1.copy(after = badRequest)))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_TARGET, 1, FixedInputFacet.SOURCE)
        val badGuard = ControlObligationFixtures.node("""{"id":"g","kind":"SCHEDULE_GUARD","floor":{}}""")
        // a replaced GUARD target whose after node is uninterpretable
        val (r, _) = holdCase(H.input(H.hold(), H.guard()), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request", "GUARD/REPLACE/g"))
        val r2 = r.descriptor.targets[2]
        unavailable(lifecycleInput(copy(r.descriptor, targets = r.descriptor.targets.take(2) + r2.copy(after = badGuard))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_TARGET, 2, FixedInputFacet.AFTER)
        // a kept GUARD whose before, or only whose after, is uninterpretable
        val (k, _) = holdCase(H.input(H.hold(floor = false), H.guard()), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"),
            listOf("GUARD/REPLACE/g"))
        val k0 = k.descriptor.requiredUnchanged[0]
        unavailable(lifecycleInput(copy(k.descriptor, unchanged = listOf(k0.copy(before = badGuard, after = badGuard)))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_UNCHANGED, 0, FixedInputFacet.SOURCE)
        unavailable(lifecycleInput(copy(k.descriptor, unchanged = listOf(k0.copy(after = badGuard)))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, FixedInputRoot.LIFECYCLE_UNCHANGED, 0, FixedInputFacet.AFTER)
    }

    @Test fun D05_invalidDescriptor_generalAndNamed() {
        val (c, _) = holdCase(H.input(H.hold(floor = false), null), H.ids, listOf("HOLD/REMOVE/h", "REQUEST/CREATE/new-request"))
        // general shape: the REQUEST row claims REPLACE without a before node
        val t1 = c.descriptor.targets[1]
        unavailable(lifecycleInput(copy(c.descriptor, targets = listOf(c.descriptor.targets[0], t1.copy(target = t1.target.copy(effect = LifecycleEffect.REPLACE))))),
            inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        // named: the REQUEST row dropped (the plan requires it)
        assertTrue(!namedValid(copy(c.descriptor, targets = c.descriptor.targets.take(1))))
        unavailable(lifecycleInput(copy(c.descriptor, targets = c.descriptor.targets.take(1))),
            inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        // named: another namespace postcondition
        val ns = c.descriptor.namespace!!
        assertTrue(!namedValid(copy(c.descriptor, namespace = LifecycleNamespacePostcondition(ns.before, ns.before, ns.journal))))
        unavailable(lifecycleInput(copy(c.descriptor, namespace = LifecycleNamespacePostcondition(ns.before, ns.before, ns.journal))),
            inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        // intent: an injected requiredUnchanged row
        val (ic, _) = intentCase(intentNode("A", "CAPABILITY", "k"), RecoveryFreshEpochs(null, H.krxEpoch),
            listOf("RECOVERY_INTENT/REMOVE/r", "REQUEST/CREATE/intent-request"))
        val extra = fixed("x-guard", LifecycleRole.GUARD, FloorGuardFixtures.field(FloorGuardFixtures.empty, "id", kotlinx.serialization.json.JsonPrimitive("x-guard")),
            FloorGuardFixtures.field(FloorGuardFixtures.empty, "id", kotlinx.serialization.json.JsonPrimitive("x-guard")))
        assertTrue(!namedValid(copy(ic.descriptor, unchanged = listOf(extra))))
        unavailable(lifecycleInput(copy(ic.descriptor, unchanged = listOf(extra))),
            inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
    }

    @Test fun D06_removeEmptyGuard_descriptorMustMatchItsPlan_andGuardMustBeEmpty() {
        val c = removeCase()
        unavailable(lifecycleInput(copy(c.descriptor, executor = H.executor)), inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        unavailable(lifecycleInput(copy(c.descriptor, namespace = LifecycleNamespacePostcondition(beforeA, beforeA, listOf()))),
            inconsistent, FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE)
        val otherEmpty = FloorGuardFixtures.field(FloorGuardFixtures.empty, "id", kotlinx.serialization.json.JsonPrimitive("g2"))
        unavailable(lifecycleInput(copy(c.descriptor, unchanged = listOf(fixed("g2", LifecycleRole.GUARD, otherEmpty, otherEmpty)))),
            inconsistent, FixedInputRoot.LIFECYCLE_UNCHANGED, 0, FixedInputFacet.WHOLE)
        // the REMOVE row is another empty guard than the plan's
        unavailable(lifecycleInput(copy(c.descriptor, targets = listOf(fixed("g2", LifecycleRole.GUARD, otherEmpty, null)))),
            inconsistent, FixedInputRoot.LIFECYCLE_TARGET, 0, FixedInputFacet.WHOLE)
        // plans prepared for guards that are not empty: floor and AUTH, a floor only (even with zero wait), AUTH only
        for (notEmptyGuard in listOf(H.guard(), FloorGuardFixtures.guard(FloorGuardFixtures.floor(0)), FloorGuardFixtures.guard(floor = null, auth = true)))
            unavailable(removeCase(notEmptyGuard).input, inconsistent, FixedInputRoot.LIFECYCLE_TARGET, 0, FixedInputFacet.SOURCE)
    }

    // ═══ the temporary reason is gone ═══════════════════════════════════════════════════════════════════════════════════
    @Test fun Z01_noUnsupportedInThisUnitReason() {
        assertTrue(RequiredObligationsUnavailable.entries.none { it.name == "UNSUPPORTED_IN_THIS_UNIT" })
    }
}
