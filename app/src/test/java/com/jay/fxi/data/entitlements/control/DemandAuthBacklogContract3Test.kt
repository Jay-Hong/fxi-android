package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.IndeterminateReason
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, third file: the remaining PLAN rows of the reviewed table whose mutants no existing test observes —
 * selector widenings (positive contracts, M4), planner and validator effects, classification (CLASSIFICATION_ONLY,
 * plain messages) and fixed copies. Expected values come from the cited design sentences.
 * Premise assertions use plain messages; their failure is never a target's kill.
 */
class DemandAuthBacklogContract3Test {
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val initial = AuthSnapshotV1("A", 2, 3, F.life, false, 0, 0)
    private val orders get() = LifecycleOrderSource(F.life, 21)

    private fun decideWith(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime = F.runtime())
        : Pair<CommandRef, RecordTransactionDecision<*>> {
        val c = F.command(plan)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        return c to ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(before), F.context(runtime), false, false)
    }
    private fun decide(plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime = F.runtime()) =
        decideWith(plan, before, runtime).second
    private fun candidate(d: RecordTransactionDecision<*>): Preferences = (d as RecordTransactionDecision.Confirm<*>).candidate
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun guardOf(raw: Preferences) = F.read(raw).arrays.getValue(ControlKind.DEMAND).entries
        .filterIsInstance<ControlEntryRead.Interpreted>().mapNotNull { it.value as? ScheduleGuardV1 }.single()
    private fun confirms(id: String, plan: DemandAuthPlan, before: Preferences, runtime: DemandAuthRuntime = F.runtime()): Preferences {
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        val result = decide(plan, before, runtime)
        assertTrue(F.atomic(id), result is RecordTransactionDecision.Confirm)
        return candidate(result)
    }
    private fun eligible(id: String, plan: DemandAuthPlan, runtime: DemandAuthRuntime, source: Preferences) {
        F.schema(source)
        assertNull("fixture plan must be prepared", plan.preparationFailure)
        assertEquals("truth vector", emptySet<String>(), V.falses(V.eligibility(plan, runtime, source)))
        assertNull(F.atomic(id), F.transition.eligibility(plan, runtime, F.read(source)))
    }
    private fun callerFact(intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM) = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER,
        F.binding, LifecycleOrderGrant(F.life, 20, 1, 0, 21), intent, F.now)
    private fun closure() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)

    // G.eligClass / S.requestClass — CLASSIFICATION_ONLY (§9.1 543; plain messages by design).

    @Test fun G_eligClass_conflict() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        assertTrue("classification: currentness change is a Conflict",
            negative(decide(p, F.raw(g, r), F.runtime(generation = 6))) is ControlStoreResult.Conflict)
    }
    @Test fun G_eligClass_rejected() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        assertTrue("classification: an ineligible query is a Rejected",
            negative(decide(p, F.raw(g, r), F.runtime(registrations = emptyList()))) is ControlStoreResult.Rejected)
    }
    @Test fun S_requestClass() {
        val g = F.guard(); val d = F.decision(q = F.query.copy(intent = RefreshIntent.FORCE_ENTITLEMENTS))
        val rt = F.runtime(registrations = listOf(d.registration))
        val strong = F.request()
        assertEquals("classification: intent", "DemandIntentNotSatisfied",
            F.transition.eligibility(F.plan(d, g, retry = null, settle = true, removes = listOf(strong)), rt, F.read(F.raw(g, strong))))
        val late = F.request(intent = RefreshIntent.IF_STALE, order = 30)
        assertEquals("classification: order", "DemandOrderNotSatisfied",
            F.transition.eligibility(F.plan(d, g, retry = null, settle = true, removes = listOf(late)), rt, F.read(F.raw(g, late))))
    }

    // G.encode / G.evidence success effects — §3.1 139, §3.2: the named writer writes both changed payloads.

    @Test fun G_encode_write() {
        val g = F.guard(); val r = F.request(); val before = F.raw(g, r)
        val after = confirms("G.encode.write", F.plan(guard = g, retry = r), before)
        assertNotEquals(F.atomic("G.encode.write"), before[ControlRecordKeys.payload(ControlKind.DEMAND)], after[ControlRecordKeys.payload(ControlKind.DEMAND)])
        assertEquals(F.atomic("G.encode.write"), F.auth.copy(authStopped = false, authStateOrder = 21), guardOf(after).auth)
    }
    @Test fun G_evidence_write() {
        val g = F.guard(); val r = F.request()
        val (c, result) = decideWith(F.plan(guard = g, retry = r), F.raw(g, r))
        assertTrue(F.atomic("G.evidence.write"), result is RecordTransactionDecision.Confirm)
        val rows = Json.parseToJsonElement(candidate(result)[ControlLifecycleEvidenceFixtures.evidenceKey]!!) as JsonArray
        assertEquals(F.atomic("G.evidence.write"), listOf(c.id), rows.map { ((it as JsonObject)["commandId"] as JsonPrimitive).content })
    }

    // S.zeroRemove widening — §5.1 327–329 routing: the zero-consumption AUTH rule is SETTLE-only and REMOVE-free-only.

    @Test fun S_zeroRemove_updateAllowed() {
        val g = F.guard(); val r = F.request()
        confirms("S.zeroRemove.updateAllowed", F.plan(guard = g, retry = r), F.raw(g, r))
    }
    @Test fun S_zeroRemove_removeAllowed() {
        val g = F.guard(); val r = F.request()
        confirms("S.zeroRemove.removeAllowed", F.plan(F.decision(), g, retry = null, settle = true, removes = listOf(r)), F.raw(g, r))
    }

    // H — §4.4 294, §4.5 308.

    /** Widening of DT:100: an eligible SETTLE retry strengthening is not a rebind. */
    @Test fun H_rebindSel_settleRetry() {
        val g = F.guard(F.auth.copy(authStopped = false, authStateOrder = 21)); val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak, settle = true)
        assertEquals("fixture: the retry is strengthened in place", listOf("r" to LifecycleEffect.REPLACE, "g" to LifecycleEffect.REPLACE),
            p.targets.map { it.target.id to it.target.effect })
        confirms("H.rebindSel.settleRetry", p, F.raw(g, weak))
    }
    /** §4.4 294 (Q11b is binding AND origin): same binding but another origin still needs a rebind. */
    @Test fun H_originTerm_otherOrigin() {
        val old = demand(F.request(origin = LifetimeId("old")))!!
        assertTrue("fixture: same binding, other origin", old.binding == F.binding.executor.binding && old.raisedAt.origin != F.life)
        assertTrue(F.atomic("H.originTerm.otherOrigin"), DemandAuthBoundary.rebind(old, F.binding, old.intent))
    }
    /** §4.3 282, §4.5 308: a weaker existing retry is strengthened with a fresh order. */
    @Test fun H_retryReuse_strengthen() {
        val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), weak, settle = true)
        val retry = demand(p.retryAfter)
        assertTrue(F.atomic("H.retryReuse.strengthen"), retry != null && retry.id == "r" &&
            retry.intent == RefreshIntent.FORCE_PREMIUM && retry.raisedAt.value > 21)
    }
    /** §4.3 282: a sufficient existing retry is preserved as it is, without a new order. */
    @Test fun H_retryReuse_reuse() {
        val strong = F.request()
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), strong, settle = true)
        assertEquals(F.atomic("H.retryReuse.reuse"), strong.toPayloadEntry(), p.retryAfter?.toPayloadEntry())
        assertFalse(F.atomic("H.retryReuse.reuse"), "r" in p.grants)
    }

    // U — §5.1 316–353.

    /** Widening of DT:87: a SETTLE on a record without a guard needs no AUTH. */
    @Test fun U_updateSel_settleNoGuard() {
        val r = F.request()
        val p = DemandAuthPlan.settle(listOf(r), null, null, F.binding, F.decision(), orders, "g-new", "r-new")
        eligible("U.updateSel.settleNoGuard", p, F.runtime(), F.raw(r))
    }
    @Test fun U_expected_end() {
        val g = F.guard(auth = oldAuth); val c = closure()
        val after = confirms("U.expected.end", DemandAuthPlan.end(g, emptyList(), F.binding, c, F.binding, orders), F.raw(g), F.runtime(closure = c))
        assertEquals(F.atomic("U.expected.end"), initial, guardOf(after).auth)
    }
    @Test fun U_expected_endRemove() {
        val g = F.guard(auth = oldAuth); val c = closure()
        val after = confirms("U.expected.endRemove", DemandAuthPlan.end(g, emptyList(), F.binding, c, null, orders), F.raw(g), F.runtime(closure = c))
        assertNull(F.atomic("U.expected.endRemove"), guardOf(after).auth)
    }
    @Test fun U_expected_initialize() {
        val after = confirms("U.expected.initialize",
            DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new"), F.raw())
        assertEquals(F.atomic("U.expected.initialize"), initial, guardOf(after).auth)
    }
    @Test fun U_expected_caller() {
        val g = F.guard(); val c = callerFact()
        val after = confirms("U.expected.caller",
            DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(c), orders, "g-new", "r-new"), F.raw(g), F.runtime(caller = c))
        assertEquals(F.atomic("U.expected.caller"), F.auth.copy(authStopped = false, authStateOrder = 21), guardOf(after).auth)
    }
    @Test fun U_expected_recovery() {
        val g = F.guard(); val rec = LifecycleRecovery("recovery", F.identity, F.life, 11, 21, 1)
        val after = confirms("U.expected.recovery",
            DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Recovery(rec), orders, "g-new", "r-new"), F.raw(g), F.runtime(recovery = rec))
        assertEquals(F.atomic("U.expected.recovery"), F.auth.copy(authStopped = false, authStateOrder = 21), guardOf(after).auth)
    }
    /** §5.1 non-AUTH answer: AUTH is kept unless the query is newer than the state. */
    @Test fun U_expected_keep() {
        val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
        val g = F.guard(auth = resumed); val r = F.request()
        val after = confirms("U.expected.keep", F.plan(F.decision(), g, retry = null, settle = true, removes = listOf(r)), F.raw(g, r))
        assertEquals(F.atomic("U.expected.keep"), resumed, guardOf(after).auth)
    }
    /** §5.3 377 at the requiredEffects boundary: a candidate whose AUTH differs from the event's required AUTH fails. */
    @Test fun U_expectedApply() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        val (_, landed) = F.apply(p, F.raw(g, r))
        assertTrue("positive twin", F.transition.requiredEffects(p, F.read(landed)))
        val tampered = F.patch(landed, "g") { F.mutateChild(it, "auth", "authStopAppliedOrder", JsonPrimitive(25)) }
        val guard = guardOf(tampered); val retry = F.read(tampered).locations("r").single().second as ControlEntryRead.Interpreted
        F.onlyFalse(guard.floor?.waitMillis == 30000L, retry.original.toPayloadEntry() == r.toPayloadEntry(),
            guard.auth == F.auth.copy(authStopped = false, authStateOrder = 21))
        assertFalse(F.atomic("U.expectedApply"), F.transition.requiredEffects(p, F.read(tampered)))
    }
    @Test fun U_planAnswer_stop() {
        val auth = guard(F.plan(F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION)), F.guard(), null).guardAfter)?.auth
        assertTrue(F.atomic("U.planAnswer.stop"), auth != null && auth.authStopped && auth.authStateOrder == 21L && auth.authStopAppliedOrder > 21)
    }
    @Test fun U_planAnswer_resume() = assertEquals(F.atomic("U.planAnswer.resume"),
        F.auth.copy(authStopped = false, authStateOrder = 21), guard(F.plan(guard = F.guard(), retry = F.request()).guardAfter)?.auth)
    @Test fun U_planAnswer_keep() {
        val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
        assertEquals(F.atomic("U.planAnswer.keep"), resumed, guard(F.plan(F.decision(), F.guard(auth = resumed), null, settle = true).guardAfter)?.auth)
    }
    /** §5.1 322: an AUTH answer to a query not newer than the state does not stop AUTH. */
    @Test fun U_planAnswer_staleAuth() {
        val newer = AuthSnapshotV1("A", 2, 3, F.life, true, 30, 40)
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION)), F.guard(auth = newer), null)
        assertEquals(F.atomic("U.planAnswer.staleAuth"), newer, guard(p.guardAfter)?.auth)
    }
    @Test fun U_planInit() = assertEquals(F.atomic("U.planInit"), initial,
        guard(DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new").guardAfter)?.auth)
    /** §5.1 330: an owner string is never invented without an identity. */
    @Test fun U_planInit_noIdentity() {
        val anonymous = F.binding.copy(identity = null)
        assertNull(F.atomic("U.planInit.noIdentity"),
            guard(DemandAuthPlan.auth(null, null, anonymous, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new").guardAfter)?.auth)
    }
    /** §5.2 361: END without a new binding leaves a guard without AUTH (the key is removed, not blanked). */
    @Test fun U_planGuardAuth_remove() {
        val p = DemandAuthPlan.end(F.guard(auth = oldAuth), emptyList(), F.binding, closure(), null, orders)
        val after = p.guardAfter
        assertTrue(F.atomic("U.planGuardAuth.remove"), after != null && "auth" !in after.toPayloadEntry().fields)
    }

    // N — §5.2 355–364.

    /** Widening of DT:136–137: a previous owner's scope at the same binding and origin is an old scope. */
    @Test fun N_oldScope_otherOwner() {
        val previous = AuthSnapshotV1("B", 2, 3, F.life, true, 10, 20)
        val g = F.guard(auth = previous); val c = LifecycleBindingClosure(previous, true, setOf("w"), setOf("w"), 5)
        eligible("N.oldScope.otherOwner", DemandAuthPlan.end(g, emptyList(), F.binding, c, null, orders), F.runtime(closure = c), F.raw(g))
    }
    @Test fun N_oldScope_otherBinding() {
        val g = F.guard(auth = oldAuth); val c = closure()
        eligible("N.oldScope.otherBinding", DemandAuthPlan.end(g, emptyList(), F.binding, c, null, orders), F.runtime(closure = c), F.raw(g))
    }
    /** §5.2 363: exactly the same-owner REQUESTs outside the current binding/origin are handed over. */
    @Test fun N_owed() {
        val oldBinding = F.request(id = "old-binding", binding = 2)
        val oldOrigin = F.request(id = "old-origin", origin = LifetimeId("old"))
        val otherOwner = F.request(id = "dormant", owner = "B", binding = 2)
        val current = F.request(id = "current")
        val g = F.guard(auth = oldAuth); val c = closure()
        val p = DemandAuthPlan.end(g, listOf(oldBinding, oldOrigin), F.binding, c, null, orders)
        eligible("N.owed", p, F.runtime(closure = c), F.raw(g, oldBinding, oldOrigin, otherOwner, current))
    }

    // F — §5.3 366–380, §5.1 325.

    @Test fun F_planGuard_floorOnly() {
        val p = DemandAuthPlan.settle(emptyList(), null, null, F.binding, F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), orders, "g-new", "r-new")
        val g = guard(p.guardAfter)
        assertTrue(F.atomic("F.planGuard.floorOnly"), g != null && g.id == "g-new" && g.auth == null && g.floor == FloorV1("boot", 10000, 30000, F.life))
    }
    @Test fun F_planGuard_none() {
        val r = F.request()
        assertNull(F.atomic("F.planGuard.none"), DemandAuthPlan.settle(listOf(r), null, null, F.binding, F.decision(), orders, "g-new", "r-new").guardAfter)
    }
    @Test fun F_planRetry_create() {
        val p = DemandAuthPlan.settle(emptyList(), F.guard(), null, F.binding, F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), orders, "g-new", "r-new")
        val retry = demand(p.retryAfter)
        assertTrue(F.atomic("F.planRetry.create"), retry != null && retry.id == "r-new" && retry.ownerUid == "A" && retry.binding == 3L &&
            retry.raisedAt.origin == F.life && retry.intent == RefreshIntent.FORCE_PREMIUM && retry.raisedAt.value > 21)
    }
    @Test fun F_planRetry_none() {
        val r = F.request()
        assertNull(F.atomic("F.planRetry.none"), F.plan(F.decision(), F.guard(), null, settle = true, removes = listOf(r)).retryAfter)
    }
    @Test fun F_planCaller_retry() {
        val c = callerFact()
        val p = DemandAuthPlan.auth(F.guard(wait = 30000), null, F.binding, LifecycleAuthEvent.Caller(c), orders, "g-new", "r-new")
        val retry = demand(p.retryAfter)
        assertTrue(F.atomic("F.planCaller.retry"), retry != null && retry.intent == c.intent && retry.raisedAt.value > c.order.value)
    }
    @Test fun F_planCaller_none() {
        val p = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Caller(callerFact()), orders, "g-new", "r-new")
        assertNull(F.atomic("F.planCaller.none"), p.retryAfter)
    }
    /** §5.3 371: TRANSIENT without a stated wait requires the default floor; a candidate without it fails. */
    @Test fun F_reqRequired_delay() {
        val g = F.guard(); val r = F.request()
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.TRANSIENT)), g, r)
        val (_, landed) = F.apply(p, F.raw(g, r))
        assertTrue("positive twin", F.transition.requiredEffects(p, F.read(landed)))
        val tampered = F.patch(landed, "g") { JsonObject(it - "floor") }
        val guard = guardOf(tampered)
        F.onlyFalse(guard.floor != null, guard.auth == F.auth.copy(authStopped = false, authStateOrder = 21),
            (F.read(tampered).locations("r").single().second as ControlEntryRead.Interpreted).original.toPayloadEntry() == r.toPayloadEntry())
        assertFalse(F.atomic("F.reqRequired.delay"), F.transition.requiredEffects(p, F.read(tampered)))
    }
    /** Widening of DT:194–195: a CALLER without a remaining floor owes no retry REQUEST. */
    @Test fun F_retrySel_callerNoFloor() {
        val g = F.guard(); val c = callerFact()
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(c), orders, "g-new", "r-new")
        val landed = confirms("F.retrySel.callerNoFloor", p, F.raw(g), F.runtime(caller = c))
        assertTrue(F.atomic("F.retrySel.callerNoFloor"), F.transition.requiredEffects(p, F.read(landed)))
    }

    // V — §3.1 135 guard filter, Q06/Q10 raw fence.

    /** Widening of DT:272–273: REQUEST rows are not guards. */
    @Test fun V_guards_requestsOnly() {
        val r = F.request()
        confirms("V.guards.requestsOnly", DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new"), F.raw(r))
    }
    private fun fenceRaw(key: Preferences.Key<String>, value: String) = F.raw(F.guard(), F.request()).toMutablePreferences().apply { this[key] = value }.toPreferences()
    @Test fun V_rawFence_exact() = assertNull(F.atomic("V.rawFence.exact"),
        F.transition.eligibility(F.plan(guard = F.guard(), retry = F.request()), F.runtime(), F.read(F.raw(F.guard(), F.request()))))
    private fun fenceNo(id: String, key: Preferences.Key<String>, value: String) {
        val p = F.plan(guard = F.guard(), retry = F.request()); val raw = fenceRaw(key, value)
        assertEquals("truth vector", setOf("after.fence"), V.falses(V.eligibility(p, F.runtime(), raw)))
        assertNotNull(F.eligible(id), F.transition.eligibility(p, F.runtime(), F.read(raw)))
    }
    @Test fun V_rawFence_owner() = fenceNo("V.rawFence.owner", DataStoreAccessEpochStore.OWNER_UID, "B")
    @Test fun V_rawFence_user() = fenceNo("V.rawFence.user", DataStoreAccessEpochStore.USER_EPOCH, "u2")
    @Test fun V_rawFence_krx() = fenceNo("V.rawFence.krx", DataStoreAccessEpochStore.KRX_EPOCH, "k2")

    // X — §2.3 A11: Edit.floor changes only the floor.

    @Test fun X_floorValues_floorOnly() =
        assertTrue(F.atomic("X.floorValues.floorOnly"), ControlMutation.Edit.floor(F.guard(), F.now, 30000, F.life).changed is ControlWriteResult.Written)
    /** A11a with literals (§2.3 A09d): an AUTH number respelled from -0 to 0 is a change. */
    @Test fun X_floorValues_literal() {
        val base = F.guard(auth = initial, wait = 30000).toPayloadEntry().fields.toString()
        val original = F.node(base.replace("\"authStateOrder\":0", "\"authStateOrder\":-0"))
        val respelled = F.node(base)
        assertEquals("fixture: typed AUTH is equal", guard(original)!!.auth, guard(respelled)!!.auth)
        val expected = guard(respelled)!!.floor!!
        assertFalse(F.eligible("X.floorValues.literal"), ControlObligations.validFloorResult(original, respelled, guard(respelled)!!, expected))
    }

    // P.addTargets — §3.2 "실제 변경/생성/제거 행만 targets에 둔다", target order.

    @Test fun P_addTargets_effects() {
        val p = F.plan(F.decision(followUp = RefreshIntent.FORCE_PREMIUM), F.guard(), null, settle = true, removes = listOf(F.request()))
        assertEquals(F.atomic("P.addTargets.effects"),
            listOf("r" to LifecycleEffect.REMOVE, "r-new" to LifecycleEffect.CREATE, "g" to LifecycleEffect.REPLACE),
            p.targets.map { it.target.id to it.target.effect })
    }
    @Test fun P_addTargets_unchanged() {
        val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
        val p = F.plan(F.decision(), F.guard(auth = resumed), null, settle = true, removes = listOf(F.request()))
        assertEquals(F.atomic("P.addTargets.unchanged"), listOf("r"), p.targets.map { it.target.id })
        assertEquals(F.atomic("P.addTargets.unchanged"), listOf("g"), p.unchanged.map { it.target.id })
    }
    @Test fun P_addTargets_guardId() {
        val created = DemandAuthPlan.settle(emptyList(), null, null, F.binding, F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), orders, "g-new", "r-new")
        assertEquals(F.atomic("P.addTargets.guardId"), "g-new", created.targets.single { it.role == LifecycleRole.GUARD }.target.id)
        val replaced = F.plan(guard = F.guard(), retry = F.request())
        assertEquals(F.atomic("P.addTargets.guardId"), "g", replaced.targets.single { it.role == LifecycleRole.GUARD }.target.id)
    }

    // Z.fixedCopies — §3.1 123–130: prepared facts are fixed once; later changes to caller collections do not reach them.

    @Test fun Z_fixedCopies_closure() {
        val captured = mutableSetOf("w"); val joined = mutableSetOf("w")
        val p = DemandAuthPlan.end(F.guard(auth = oldAuth), emptyList(), F.binding, LifecycleBindingClosure(oldAuth, true, captured, joined, 5), null, orders)
        captured += "late"; joined += "late"
        assertEquals(F.atomic("Z.fixedCopies.closure"), setOf("w"), p.closure?.capturedWork)
        assertEquals(F.atomic("Z.fixedCopies.closure"), setOf("w"), p.closure?.joinedWork)
    }
    @Test fun Z_fixedCopies_effects() {
        val effects = mutableListOf(LifecycleDurableEffect(ControlKind.DEMAND, F.request(), null))
        val d = F.decision(effects = effects)
        effects.clear()
        assertEquals(F.atomic("Z.fixedCopies.effects"), 1, d.effects.size)
    }
    @Test fun Z_fixedCopies_registrations() {
        val registrations = mutableListOf(F.registration)
        val rt = DemandAuthRuntime(F.binding, F.identity, 5, registrations)
        registrations.clear()
        assertEquals(F.atomic("Z.fixedCopies.registrations"), listOf(F.registration), rt.registrations)
    }
    // S.noop — §3.2 163·174, §4.3 280–282: no consumption, no floor change and no REQUEST change creates no Applied.

    private fun noChangePlan(): DemandAuthPlan {
        val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
        return F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 60)), F.guard(auth = resumed, wait = 90000), F.request(), settle = true)
    }
    @Test fun S_noop() {
        val p = noChangePlan()
        assertTrue(F.atomic("S.noop"), p.targets.isEmpty())
        assertEquals(F.atomic("S.noop"), listOf("r", "g"), p.unchanged.map { it.target.id })
        assertNotNull(F.atomic("S.noop"), p.guardBefore)
        assertNotNull(F.atomic("S.noop"), p.retryBefore)
        assertFalse(F.eligible("S.noop"), decide(p, F.raw(p.guardBefore!!, p.retryBefore!!)) is RecordTransactionDecision.Confirm)
    }

    // V.schema — the V boundary by direct injection (review r2): an interpretable-metadata failure alone rejects.
    // The writer domain cannot reach it (LC:181 gates the before record); this fixes the validator's own check.

    @Test fun V_schema() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r); val before = F.raw(g, r)
        val (c, positive) = decideWith(p, before)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        assertTrue("positive twin must reach Confirm", positive is RecordTransactionDecision.Confirm)
        val full = candidate(positive)
        assertTrue("positive twin must be a valid candidate", F.transition.validCandidate(c, input, F.read(before), full))
        val key = ControlLifecycleEvidenceFixtures.evidenceKey
        val opaque = buildJsonObject { put("version", 2); put("commandId", "opaque") }
        val own = (Json.parseToJsonElement(full[key]!!) as JsonArray).single()
        val opaqueBefore = before.toMutablePreferences().apply { this[key] = JsonArray(listOf(opaque)).toString() }.toPreferences()
        val opaqueCandidate = full.toMutablePreferences().apply { this[key] = JsonArray(listOf(opaque, own)).toString() }.toPreferences()
        assertTrue("fixture: the evidence log holds one uninterpretable row before and after",
            F.read(opaqueBefore).hasUninterpretableMetadata && F.read(opaqueCandidate).hasUninterpretableMetadata)
        assertFalse("fixture: the obligations stay interpretable", F.read(opaqueCandidate).hasUninterpretable)
        assertEquals("fixture: DEMAND is the positive candidate's", full[ControlRecordKeys.payload(ControlKind.DEMAND)],
            opaqueCandidate[ControlRecordKeys.payload(ControlKind.DEMAND)])
        assertFalse(F.atomic("V.schema"), F.transition.validCandidate(c, input, F.read(opaqueBefore), opaqueCandidate))
    }

    // Z.fixedCopies read-only views — §3.1 123–130. Two or more elements, because a one-element Kotlin copy is itself
    // immutable and would hide a removed wrapper (review r2).

    private fun readOnly(id: String, attempt: () -> Unit, intact: () -> Boolean) {
        runCatching(attempt).onFailure { if (it is Error) throw it }
        assertTrue(F.atomic(id), intact())
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun Z_fixedCopies_readOnlyTargets() {
        val p = DemandAuthPlan.rebind(listOf(F.request(id = "r1", binding = 2), F.request(id = "r2", binding = 2)), F.binding, orders)
        assertEquals("fixture: two targets", 2, p.targets.size)
        readOnly("Z.fixedCopies.readOnlyTargets", { (p.targets as MutableList<LifecycleFixedTarget>).clear() },
            { p.targets.map { it.target.id } == listOf("r1", "r2") })
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun Z_fixedCopies_readOnlyUnchanged() {
        val p = noChangePlan()
        assertEquals("fixture: two unchanged rows", 2, p.unchanged.size)
        readOnly("Z.fixedCopies.readOnlyUnchanged", { (p.unchanged as MutableList<LifecycleFixedTarget>).clear() },
            { p.unchanged.map { it.target.id } == listOf("r", "g") })
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun Z_fixedCopies_readOnlyGrants() {
        val p = DemandAuthPlan.rebind(listOf(F.request(id = "r1", binding = 2), F.request(id = "r2", binding = 2)), F.binding, orders)
        assertEquals("fixture: two grants", setOf("r1", "r2"), p.grants.keys)
        readOnly("Z.fixedCopies.readOnlyGrants", { (p.grants as MutableMap<String, LifecycleOrderGrant>).clear() },
            { p.grants.keys == setOf("r1", "r2") })
    }
}
