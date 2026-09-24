package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.*
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixty-third file — units STEP3ZK r9 judged not closed by the connection proposed in r9 (builder outputs
 * and literal floor values), each with its own condition and its own role:
 *  - P.answerDelay.class (DemandAuthPlan.kt:156): a negative decisionMinDelayMillis refuses preparation (not the A15a
 *    seconds overflow). Role = prepared plan refused; classification separate.
 *  - P.retryExhausted.noRequest (DP:120–123 via retry): an exhausted order source leaves no retry REQUEST — role on
 *    `retryAfter == null`, twin = the same decision with an issuable source.
 *  - P.authExhausted.noFakeGrant (DP:170–172): an exhausted AUTH stop bound leaves no stop grant — role on
 *    `authStopGrant == null`, twin = a stop bound below MAX.
 *  - P.rebindRefused.targets with Q11b (DemandAuthFacts.kt:117): a REQUEST already on the current binding and origin needs
 *    no rebind; the refused plan has no targets (C53 used Q11a owner instead).
 *  - X.floorValues.literal (ControlObligations.kt:151–155): the AUTH literal `-0` respelled `0` is equal as a typed value but
 *    not as serialization; validFloorResult refuses the respelled candidate.
 */
class DemandAuthBacklogContract63Test {
    private val orders get() = LifecycleOrderSource(F.life, 21)

    // P.answerDelay — answer() preparation terms up to DP:156, in production order
    private fun delayVec(d: AcceptedQueryDecision) = listOf(
        "seconds" to ((d.outcome as EntitlementsOutcome.Pending).retryAfterSeconds.let { it == null || (it >= 0 && it <= Long.MAX_VALUE / 1000) }),
        "floorOrigin" to (d.floorOrigin == d.query.order.origin),
        "delayNonNegative" to (d.decisionMinDelayMillis >= 0))
    private val pending30 = EntitlementsOutcome.Pending(false, 30)
    @Test fun P_answerDelay() {
        val ok = F.decision(outcome = pending30); val bad = F.decision(outcome = pending30, minDelay = -1)
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(delayVec(ok)))
        assertNull("positive twin: the non-negative delay prepares", F.plan(ok, F.guard(), F.request()).preparationFailure)
        assertEquals("truth vector: only the delay differs", setOf("delayNonNegative"), V.falses(delayVec(bad)))
        assertNotNull(F.eligible("Z.bl.answerDelay"), F.plan(bad, F.guard(), F.request()).preparationFailure)
    }
    @Test fun P_answerDelay_classification() = assertEquals("classification: negative delay preparation failure", "InvalidBootReading",
        F.plan(F.decision(outcome = pending30, minDelay = -1), F.guard(), F.request()).preparationFailure)

    // P.retryExhausted — the retry order issue (lower = max(last, start, after) must not be MAX)
    @Test fun P_retryExhausted_output() {
        val d = F.decision(outcome = pending30)
        assertEquals("premise: a Pending answer retries at the query intent", RefreshIntent.FORCE_PREMIUM, d.query.intent)
        assertEquals("truth vector: twin issuable", emptySet<String>(), V.falses(listOf("issuable" to (maxOf(21L, F.binding.startedOrder, 21L) != Long.MAX_VALUE))))
        assertNotNull("positive twin: an issuable source yields the retry REQUEST", F.plan(d, F.guard(), null, orders = LifecycleOrderSource(F.life, 21)).retryAfter)
        assertEquals("truth vector: only exhaustion", setOf("issuable"), V.falses(listOf("issuable" to (maxOf(Long.MAX_VALUE, F.binding.startedOrder, 21L) != Long.MAX_VALUE))))
        assertNull(F.atomic("Z.bo.retryExhausted"), F.plan(d, F.guard(), null, orders = LifecycleOrderSource(F.life, Long.MAX_VALUE)).retryAfter)
    }

    // P.authExhausted — the AUTH stop issue after max(stopApplied, query order)
    private val authn = EntitlementsOutcome.Indeterminate(IndeterminateReason.AUTHENTICATION)
    @Test fun P_authExhausted_output() {
        val d = F.decision(outcome = authn)
        val twinGuard = F.guard(auth = F.auth.copy(authStopAppliedOrder = 20)); val badGuard = F.guard(auth = F.auth.copy(authStopAppliedOrder = Long.MAX_VALUE))
        fun vec(g: ControlNode) = listOf("stopIssuable" to (maxOf(guard(g)!!.auth!!.authStopAppliedOrder, d.query.order.value) != Long.MAX_VALUE))
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(vec(twinGuard)))
        assertNotNull("positive twin: an issuable stop bound yields the grant", F.plan(d, twinGuard, null).authStopGrant)
        assertEquals("truth vector: only the stop bound", setOf("stopIssuable"), V.falses(vec(badGuard)))
        assertNull(F.atomic("Z.bo.authExhausted"), F.plan(d, badGuard, null).authStopGrant)
    }

    // P.rebindRefused — Q11b: rebind needed only when binding or origin differs
    private fun reb(old: DemandV1) = listOf("owner" to (old.ownerUid == F.binding.executor.ownerUid),
        "needed" to !(old.binding == F.binding.executor.binding && old.raisedAt.origin == F.binding.executor.originLifetimeId))
    @Test fun P_rebindNeeded_targets() {
        val moved = F.request(binding = 2, order = 50); val current = F.request()
        assertEquals("truth vector: twin", emptySet<String>(), V.falses(reb(demand(moved)!!)))
        assertEquals("positive twin: the moved REQUEST gets one REPLACE target", listOf(LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)),
            DemandAuthPlan.rebind(listOf(moved), F.binding, orders).targets.map { it.target })
        assertEquals("truth vector: only need differs", setOf("needed"), V.falses(reb(demand(current)!!)))
        val refused = DemandAuthPlan.rebind(listOf(current), F.binding, orders)
        assertEquals("fixture: refused by the rebind predicate", "InvalidRebind", refused.preparationFailure)
        assertTrue(F.atomic("Z.bl.rebindNeeded.targets"), refused.targets.isEmpty())
    }

    // X.floorValues.literal — `-0` against `0`
    @Test fun X_floorLiteral() {
        val base = F.guard(auth = AuthSnapshotV1("A", 2, 3, F.life, false, 0, 0), wait = 30000).toPayloadEntry().fields.toString() // AUTH = C3 `initial`
        assertEquals("fixture: one authStateOrder literal", 1, Regex("\"authStateOrder\":0[,}]").findAll(base).count())
        val original = F.node(base.replace("\"authStateOrder\":0", "\"authStateOrder\":-0")); val respelled = F.node(base)
        assertEquals("fixture: typed AUTH is equal", guard(original)!!.auth, guard(respelled)!!.auth)
        val written = (ControlObligations.recordFloor(original, F.now, 1000, F.life) as ControlWriteResult.Written).node
        val expected = guard(written)!!.floor!!
        fun vec(candidate: ControlNode) = listOf(
            "untouched" to ((original.toPayloadEntry().fields - "floor").mapValues { it.value.toString() } == (candidate.toPayloadEntry().fields - "floor").mapValues { it.value.toString() }),
            "floor" to (guard(candidate)?.floor == expected))
        assertEquals("truth vector: twin (the written candidate keeps -0)", emptySet<String>(), V.falses(vec(written)))
        assertTrue("positive twin: the written candidate is valid", ControlObligations.validFloorResult(original, written, guard(written)!!, expected))
        val bad = F.node(JsonObjectOf(respelled, written))
        assertEquals("truth vector: only the literal differs", setOf("untouched"), V.falses(vec(bad)))
        assertFalse(F.eligible("Z.fl.literal"), ControlObligations.validFloorResult(original, bad, guard(bad)!!, expected))
    }
    /** the respelled node with the written floor: every field of `respelled`, floor from `written` */
    private fun JsonObjectOf(respelled: ControlNode, written: ControlNode): String =
        kotlinx.serialization.json.JsonObject(respelled.toPayloadEntry().fields + ("floor" to written.toPayloadEntry().fields["floor"]!!)).toString()
}
