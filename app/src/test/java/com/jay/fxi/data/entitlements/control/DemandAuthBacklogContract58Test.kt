package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5e-1 contract, fifty-eighth file — the non-NEGATIVE units of the STEP3ZK r8 OPEN list, each on its own basis (r8 §기준):
 *  - Caller POSITIVE_CONTROL ×6 (DemandAuthCallerTest A04 callerSame · callerStronger · callerMaxIntent order/intent ·
 *    callerAfterExisting · callerAfterLastIssue): the positive conditions are the real issuance and intent rules —
 *    order = max(source last, binding start, caller order, existing raisedAt) + 1 (DemandAuthFacts.kt:15–18 via
 *    DemandAuthPlan.kt:72, 144–146) and intent = max(existing, caller) (DP:146). Each method states those premises
 *    independently (fixture literals, the formula written here), then asserts the literal expected value as the role.
 *  - CRS.end.arguments (POSITIVE_CONTROL): the END facade lands the replacement AUTH — compared with a literal AUTH
 *    snapshot, not with production initialAuth.
 *  - A04.answerRetryKeepsExistingOrder (PRESERVATION, DP:143): a sufficient existing REQUEST is kept verbatim — original,
 *    order and the unchanged list — under a role message.
 *  - T.guardCreate.class(kind) (CLASSIFICATION, DT:32): separated from the C56 role method; same premises (only
 *    dt.guardCreate false), the literal classification Conflict/TargetChanged.
 */
class DemandAuthBacklogContract58Test {
    // Caller positives — premises written here, literal expectations
    private val callerOrders get() = LifecycleOrderSource(F.life, 21)
    private class Fixture(val g: ControlNode, val r: ControlNode, val caller: LifecycleCaller, val orders: LifecycleOrderSource) {
        fun plan() = DemandAuthPlan.auth(g, r, F.binding, LifecycleAuthEvent.Caller(caller), orders, "g", "unused-new-id")
    }
    private fun fixture(oldIntent: RefreshIntent = RefreshIntent.FORCE_ENTITLEMENTS, raisedAt: Long = 4): Fixture {
        val orders = callerOrders
        val caller = LifecycleCaller("caller-22", LifecycleCallerOrigin.CALLER, F.binding, orders.issue(F.binding.startedOrder)!!,
            RefreshIntent.FORCE_ENTITLEMENTS, F.now)
        return Fixture(F.guard(F.auth.copy(authStopped = false), 60000), F.request(intent = oldIntent, order = raisedAt), caller, orders)
    }
    /** Premises independent of the builder: the floor still delays the caller, the existing REQUEST is in scope. */
    private fun premises(f: Fixture) {
        assertEquals("premise: the caller was issued 22 from the source at 21", 22L, f.caller.order.value)
        assertTrue("premise: the floor still delays the caller", guard(f.g)!!.floor!!.remainingAt(F.now)!! > 0)
        val old = demand(f.r)!!
        assertEquals("premise: owner in scope", F.binding.executor.ownerUid, old.ownerUid)
        assertEquals("premise: binding in scope", F.binding.executor.binding, old.binding)
        assertEquals("premise: origin in scope", F.life, old.raisedAt.origin)
    }
    /** `last` = the source position after the caller issue (22, or 41 after one more issue). */
    private fun order(last: Long, raisedAt: Long) = maxOf(last, F.binding.startedOrder, 22L, raisedAt) + 1
    private fun orderYes(id: String, f: Fixture, last: Long, expected: Long) {
        premises(f)
        assertEquals("premise: the written issuance rule gives the literal", expected, order(last, demand(f.r)!!.raisedAt.value))
        val p = f.plan(); assertNull("prepared", p.preparationFailure)
        val next = demand(p.retryAfter)
        assertEquals(F.atomic(id), "r" to expected, next?.id to next?.raisedAt?.value)
    }
    @Test fun P_callerSame_order() = orderYes("Z.pc.A04.callerSame", fixture(), 22, 23)
    @Test fun P_callerStronger_order() = orderYes("Z.pc.A04.callerStronger", fixture(oldIntent = RefreshIntent.FORCE_PREMIUM), 22, 23)
    @Test fun P_callerMaxIntent_order() = orderYes("Z.pc.A04.callerMaxIntent.order", fixture(oldIntent = RefreshIntent.FORCE_PREMIUM), 22, 23)
    @Test fun P_callerAfterExisting() = orderYes("Z.pc.A04.callerAfterExisting", fixture(raisedAt = 40), 22, 41)
    @Test fun P_callerAfterLastIssue() {
        val f = fixture()
        assertEquals("premise: one more order was issued after 40", 41L, f.orders.issue(F.binding.startedOrder, 40)!!.value)
        orderYes("Z.pc.A04.callerAfterLastIssue", f, 41, 42)
    }
    @Test fun P_callerMaxIntent_intent() {
        val f = fixture(oldIntent = RefreshIntent.FORCE_PREMIUM); premises(f)
        assertTrue("premise: the existing REQUEST is stronger than the caller", RefreshIntent.FORCE_PREMIUM > f.caller.intent)
        val p = f.plan(); assertNull("prepared", p.preparationFailure)
        assertEquals(F.atomic("Z.pc.A04.callerMaxIntent.intent"), RefreshIntent.FORCE_PREMIUM, demand(p.retryAfter)!!.intent)
    }

    // CRS.end.arguments — literal AUTH
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    @After fun cleanup() = runBlocking { controlTestTimeout("c58 cleanup", 30000) { opened.forEach { it.close() } } }
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private fun closure() = LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5)
    private class Ids { var issued = 0; val generator = ControlIdGenerator { UUID(0, (++issued).toLong()) } }
    @Test fun CRS_end_literal() {
        val g = F.guard(auth = oldAuth); val old = F.request(binding = 2)
        val store = ControlStoreTestStorage(folder.newFile()).also { opened += it }
        runBlocking { controlTestTimeout("c58 seed") { store.data.updateData { F.raw(g, old) } } }
        val facade = ControlRecordStore(store.owner, Ids().generator)
        val c = facade.prepareEndAuthBinding(g, listOf(old), F.binding, closure(), F.binding, LifecycleOrderSource(F.life, 21))
        val result = runBlocking { controlTestTimeout("c58 end") { facade.execute(c, F.context(F.runtime(closure = closure()))) } }
        assertTrue("fixture: the END facade confirms, got $result", result is ControlStoreResult.Confirmed)
        val landed = F.read(runBlocking { store.raw() }).arrays.getValue(ControlKind.DEMAND).entries.filterIsInstance<ControlEntryRead.Interpreted>().map { it.value }
        // replacement binding identity ("A", generation 2) at executor binding 3, origin life, resumed with zero orders
        assertEquals(F.atomic("Z.pc.CRS.end.auth"), AuthSnapshotV1("A", 2, 3, F.life, false, 0, 0), landed.filterIsInstance<ScheduleGuardV1>().single().auth)
    }

    // A04.answerRetryKeepsExistingOrder — preservation
    @Test fun K_answerRetryKeepsExistingOrder() {
        val request = F.request(intent = RefreshIntent.FORCE_PREMIUM)
        val d = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
        // premises: the answer's required intent is the query intent (Pending, no follow-up, no re-approval) and the existing
        // REQUEST already meets it; an Answer never asks for a fresh order
        assertEquals("premise: query intent", RefreshIntent.FORCE_PREMIUM, d.query.intent)
        assertNull("premise: no follow-up", d.followUp)
        assertEquals("premise: no re-approval", LifecycleReapproval.NOT_REQUIRED, d.reapproval)
        assertTrue("premise: the existing REQUEST is sufficient", demand(request)!!.intent >= d.query.intent)
        val p = F.plan(d, retry = request)
        assertNull("prepared", p.preparationFailure)
        assertEquals(F.atomic("Z.pv.A04.keepOrder"), listOf(request.toPayloadEntry(), 4L, true, false),
            listOf(p.retryAfter?.toPayloadEntry(), demand(p.retryAfter)?.raisedAt?.value,
                p.unchanged.any { it.target.id == "r" }, p.targets.any { it.target.id == "r" }))
    }

    // T.guardCreate.class(kind) — classification only
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    @Test fun C_guardCreate_classification() {
        val plan = { DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life, 21), "g-new", "r-new") }
        val raw = F.raw(F.guard(auth = null, id = "x")); F.schema(raw)
        val p = plan(); assertNull("prepared", p.preparationFailure)
        assertEquals("premise: only guard creation is unavailable", setOf("dt.guardCreate"), V.falses(V.decideGates(p, F.context(), raw)) +
            V.falses(V.eligibility(p, F.runtime(), raw)) + V.falses(V.commonPremises(p, raw, "command")))
        val c = F.command(p)
        val d = ControlLifecycleConfirmation(F.codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, F.read(raw), F.context(), false, false)
        assertTrue("fixture: refused", d is RecordTransactionDecision.Observe<*>)
        val result = negative(d)
        assertTrue("classification: guard creation conflicts", result is ControlStoreResult.Conflict &&
            result.reason == ConflictReason.TargetChanged)
    }
}
