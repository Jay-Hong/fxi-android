package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixty-fourth file — direct boundaries STEP3ZK r9 kept OPEN:
 *  - A04.candidateRechecksBuilder (DemandAuthTransition.kt:205, 215–224): the caller's issuance recheck in requiredEffects
 *    refuses a candidate REQUEST whose raisedAt is not after the grant's previous order (A04.callerPrevious) or not after
 *    the caller's order (A04.callerEvent), even when a defective builder row agrees with the candidate (reflection patch of
 *    retryAfter, as the original). The caller is issued from its own source so the two thresholds differ and each can be
 *    the only false term.
 *  - U.stopGrant.missing (DT:147): the complete AUTH stop plan with only authStopGrant removed (reflection, as C13) is
 *    refused by eligibility called directly.
 *  - W.guardUnique.strict (DT:61–63): guardCreationAvailable is false when a guard row exists.
 *  - Q10namespace / Q10fence (DT:154–158) with a twin where the fence change applies (C51's twins had no fence change, so
 *    the namespace items were vacuous there).
 */
class DemandAuthBacklogContract64Test {
    private fun row(raw: Preferences, id: String) = (F.read(raw).locations(id).single().second as ControlEntryRead.Interpreted).original

    // A04 — requiredEffects → callerRetry order terms
    private fun recheckVec(p: DemandAuthPlan, raw: Preferences, caller: LifecycleCaller): List<Pair<String, Boolean>> {
        val request = demand(row(raw, "r"))!!; val expected = demand(p.retryAfter)!!; val before = demand(p.retryBefore)
        val grant = p.grants[request.id]; val x = p.binding.executor
        return listOf("A14b" to (request.intent >= caller.intent), "owner" to (request.ownerUid == x.ownerUid),
            "binding" to (request.binding == x.binding), "origin" to (request.raisedAt.origin == x.originLifetimeId),
            "order" to (request.raisedAt.value == expected.raisedAt.value), "grant" to (grant != null),
            "bindingStart" to (request.raisedAt.value > p.binding.startedOrder), "previous" to (grant == null || request.raisedAt.value > grant.previous),
            "event" to (request.raisedAt.value > caller.order.value), "raisedAt" to (before == null || request.raisedAt.value > before.raisedAt.value),
            "intent" to (before == null || request.intent >= before.intent))
    }
    private fun recheck(id: String, only: String, callerOrder: Long, sourceAt: Long, staleRaisedAt: Long) {
        val orders = LifecycleOrderSource(F.life, sourceAt)
        val caller = LifecycleCaller("caller-$callerOrder", LifecycleCallerOrigin.CALLER, F.binding,
            LifecycleOrderGrant(F.life, callerOrder - 1, F.binding.startedOrder, 0, callerOrder), RefreshIntent.FORCE_ENTITLEMENTS, F.now)
        val g = F.guard(F.auth.copy(authStopped = false), 60000); val r = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 4)
        val p = DemandAuthPlan.auth(g, r, F.binding, LifecycleAuthEvent.Caller(caller), orders, "g", "unused-new-id")
        assertNull("fixture: plan prepared", p.preparationFailure)
        assertEquals("fixture: the retry is issued after max(source, caller)", maxOf(sourceAt, callerOrder) + 1, demand(p.retryAfter)!!.raisedAt.value)
        val (_, full) = F.apply(p, F.raw(g, r), F.runtime(caller = caller))
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(recheckVec(p, full, caller)))
        assertTrue("positive twin: the candidate satisfies requiredEffects", F.transition.requiredEffects(p, F.read(full)))
        val stale = F.patch(full, "r") { JsonObject(it + ("raisedAt" to JsonPrimitive(staleRaisedAt))) }
        F.schema(stale)
        DemandAuthPlan::class.java.getDeclaredField("retryAfter").apply { isAccessible = true }.set(p, row(stale, "r"))
        assertEquals("fixture: the builder row agrees with the candidate", row(stale, "r").toPayloadEntry(), p.retryAfter!!.toPayloadEntry())
        assertEquals("fixture: the guard row is unchanged", row(full, "g").toPayloadEntry(), row(stale, "g").toPayloadEntry())
        assertEquals("truth vector: only the target order term", setOf(only), V.falses(recheckVec(p, stale, caller)))
        assertFalse(F.atomic(id), F.transition.requiredEffects(p, F.read(stale)))
    }
    // source 21, caller 30: retry 31 with previous 21; candidate 25 is after previous, not after the caller
    @Test fun A04_recheckEvent() = recheck("Z.cr.A04.event", "event", 30, 21, 25)
    // source 25, caller 20: retry 26 with previous 25; candidate 23 is after the caller, not after previous
    @Test fun A04_recheckPrevious() = recheck("Z.cr.A04.previous", "previous", 20, 25, 23)

    // U.stopGrant.missing — direct eligibility
    private fun authStopPlan(): DemandAuthPlan = F.plan(
        F.decision(outcome = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION), followUp = RefreshIntent.FORCE_PREMIUM),
        F.guard(), F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS))
    private fun stopVec(p: DemandAuthPlan, raw: Preferences) = V.falses(V.eligibility(p, F.runtime(), raw))
    @Test fun U_stopGrant_direct() {
        val raw = F.raw(F.guard(), F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS))
        val twin = authStopPlan()
        assertNull("fixture: prepared", twin.preparationFailure)
        assertEquals("fixture: the plan stops AUTH", true, guard(twin.guardAfter)?.auth?.authStopped)
        assertEquals("truth vector: twin", emptySet<String>(), stopVec(twin, raw))
        assertNull("positive twin: the complete AUTH stop plan is eligible", F.transition.eligibility(twin, F.runtime(), F.read(raw)))
        val missing = authStopPlan()
        DemandAuthPlan::class.java.getDeclaredField("authStopGrant").apply { isAccessible = true }.set(missing, null)
        assertEquals("fixture: guardAfter unchanged", twin.guardAfter?.toPayloadEntry(), missing.guardAfter?.toPayloadEntry())
        assertEquals("fixture: grants unchanged", twin.grants, missing.grants)
        assertEquals("truth vector: only the stop grant is missing", setOf("stop.grant"), stopVec(missing, raw))
        assertNotNull(F.eligible("Z.el.stopGrant"), F.transition.eligibility(missing, F.runtime(), F.read(raw)))
    }

    // W.guardUnique.strict — direct guardCreationAvailable
    private fun guardsVec(read: ControlRecordRead.Supported) = listOf("noGuard" to read.arrays.getValue(ControlKind.DEMAND).entries
        .none { (it as? ControlEntryRead.Interpreted)?.value is ScheduleGuardV1 })
    @Test fun W_guardUnique_direct() {
        val free = F.read(F.raw(F.request())); val taken = F.read(F.raw(F.guard(auth = null)))
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(guardsVec(free)))
        assertTrue("positive twin: no guard row, a guard may be created", F.transition.guardCreationAvailable(free))
        assertEquals("truth vector: only the guard row differs", setOf("noGuard"), V.falses(guardsVec(taken)))
        assertFalse(F.eligible("Z.dt.guardUnique"), F.transition.guardCreationAvailable(taken))
    }

    // Q10namespace / Q10fence with a fence-changing twin
    private val e = F.request(id = "e", order = 7)
    private val current get() = F.read(F.raw(e))
    private fun proofOf(raw: Preferences) = ConfirmedControlSnapshot(F.read(raw))
    private val fenceU2 = F.fence.copy(userAccessEpoch = "u2")
    private fun rawU2() = F.raw(e).toMutablePreferences().apply { this[USER_EPOCH] = "u2" }.toPreferences()
    private fun eff() = LifecycleDurableEffect(ControlKind.DEMAND, e, proofOf(F.raw(e)))
    private fun nsVec(d: AcceptedQueryDecision): List<Pair<String, Boolean>> {
        val needed = d.confirmedAfterFence != d.acceptedBeforeFence; val ns = d.namespaceConfirmation
        return listOf("needed" to needed, "namespaceProof" to (!needed || ns != null),
            "namespaceFence" to (!needed || ns == null || FenceV1(ns.record.original[OWNER_UID], ns.record.original[USER_EPOCH], ns.record.original[KRX_EPOCH]) == d.confirmedAfterFence))
    }
    private fun nsNo(id: String, only: String, bad: AcceptedQueryDecision) {
        val twin = F.decision(after = fenceU2, effects = listOf(eff()), namespace = proofOf(rawU2()))
        assertEquals("truth vector: twin (the fence changes, so the namespace items apply)", emptySet<String>(), V.falses(nsVec(twin)))
        assertTrue("positive twin: a matching namespace proof makes the fence change durable", F.transition.durableEffects(twin, current))
        assertEquals("truth vector: only the target differs", setOf(only), V.falses(nsVec(bad)))
        assertFalse(F.eligible(id), F.transition.durableEffects(bad, current))
    }
    @Test fun Q10namespace_applied() = nsNo("Z.de.Q10namespace.applied", "namespaceProof", F.decision(after = fenceU2, effects = listOf(eff()), namespace = null))
    @Test fun Q10fence_applied() = nsNo("Z.de.Q10fence.applied", "namespaceFence", F.decision(after = fenceU2, effects = listOf(eff()), namespace = proofOf(F.raw(e))))
}
