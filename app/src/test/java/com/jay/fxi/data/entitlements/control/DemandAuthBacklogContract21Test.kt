package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, twenty-first file: CLASSIFICATION_ONLY observations for preparation failures and for the kind and
 * reason of DemandAuth rejections (§9.1 543–545). Each test first asserts the kept boundary with an eligible role —
 * the plan is not prepared, or the writer neither confirms nor observes a positive outcome — and then compares the
 * failure string, result kind or reason with labelled "classification:" messages.
 * Expected strings, kinds and reasons are fixed constants; the envelope limit and byte count are those of the C15
 * W_evidence fixture wire (2300 / 2439).
 */
class DemandAuthBacklogContract21Test {
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private val oldAuth = AuthSnapshotV1("A", 2, 2, F.life, true, 10, 20)
    private val pending get() = F.decision(outcome = EntitlementsOutcome.Pending(false, 30))
    private fun decide(p: DemandAuthPlan, raw: Preferences, context: AttemptContext? = F.context(),
        codec: ControlPayloadCodec = F.codec): RecordTransactionDecision<*> {
        val c = F.command(p)
        val read = ControlRecordReader(codec).read(raw) as ControlRecordRead.Supported
        return ControlLifecycleConfirmation(codec).decide(c, (c.body as ControlCommandBody.Lifecycle).input, read, context, false, false)
    }
    private fun negative(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun noFalseSuccess(d: RecordTransactionDecision<*>) =
        d is RecordTransactionDecision.Confirm || d.value is ControlRecordStore.Outcome.Positive

    // P — preparation failure strings (DemandAuthPlan.kt). fail() keeps the first failure (DP:119); each fixture reaches
    // exactly one failing check.

    @Test fun P_initGuard_class() {
        val notGuard = F.request()
        assertNull("fixture: the node is not a guard", guard(notGuard))
        val p = DemandAuthPlan.auth(notGuard, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        assertNotNull(F.eligible("P.initGuard.class"), p.preparationFailure)
        assertEquals("classification: P.initGuard failure", "InvalidGuard", p.preparationFailure)
    }

    @Test fun P_callerBoot_class() {
        val g = F.guard(wait = 30000)
        assertNotNull("fixture: the guard has a floor", guard(g)!!.floor)
        val c = LifecycleCaller("caller", LifecycleCallerOrigin.CALLER, F.binding, LifecycleOrderGrant(F.life, 20, 1, 0, 21),
            RefreshIntent.FORCE_PREMIUM, BootReading("", 0))
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Caller(c), orders, "g-new", "r-new")
        assertNotNull(F.eligible("P.callerBoot.class"), p.preparationFailure)
        assertEquals("classification: P.callerBoot failure", "InvalidBootReading", p.preparationFailure)
    }

    @Test fun P_endReplacement_class() {
        val g = F.guard(auth = oldAuth)
        val other = F.binding.copy(startEventId = "other-start")
        assertNotEquals("fixture: a different replacement binding", F.binding, other)
        val p = DemandAuthPlan.end(g, emptyList(), F.binding, LifecycleBindingClosure(oldAuth, true, setOf("w"), setOf("w"), 5), other, orders)
        assertNotNull(F.eligible("P.endReplacement.class"), p.preparationFailure)
        assertEquals("classification: P.endReplacement failure", "AuthInitializationIneligible", p.preparationFailure)
    }

    @Test fun P_rebindTarget_class() {
        val p = DemandAuthPlan.rebind(listOf(F.guard()), F.binding, orders)
        assertNotNull(F.eligible("P.rebindTarget.class"), p.preparationFailure)
        assertEquals("classification: P.rebindTarget failure", "InvalidRequestTarget", p.preparationFailure)
    }

    @Test fun P_retryNode_class() {
        val p = F.plan(pending, F.guard(), F.guard(id = "x"))
        assertNotNull(F.eligible("P.retryNode.class"), p.preparationFailure)
        assertEquals("classification: P.retryNode failure", "InvalidRetryRequest", p.preparationFailure)
    }

    @Test fun P_successorNew_class() {
        val p = F.plan(pending, F.guard(), F.request(id = "r2"), settle = true, removes = listOf(F.request()))
        assertNotNull(F.eligible("P.successorNew.class"), p.preparationFailure)
        assertEquals("classification: P.successorNew failure", "SuccessorMustBeNew", p.preparationFailure)
    }

    @Test fun P_retryScope_class() {
        val p = F.plan(pending, F.guard(), F.request(owner = "B"))
        assertNotNull(F.eligible("P.retryScope.class"), p.preparationFailure)
        assertEquals("classification: P.retryScope failure", "RetryScopeMismatch", p.preparationFailure)
    }

    @Test fun P_answerGuard_class() {
        val notGuard = F.request(id = "x")
        assertNull("fixture: the node is not a guard", guard(notGuard))
        val p = F.plan(F.decision(), notGuard, null, settle = true, removes = emptyList())
        assertNotNull(F.eligible("P.answerGuard.class"), p.preparationFailure)
        assertEquals("classification: P.answerGuard failure", "InvalidGuard", p.preparationFailure)
    }

    @Test fun P_answerSeconds_class() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, -1)), F.guard(), F.request())
        assertNotNull(F.eligible("P.answerSeconds.class"), p.preparationFailure)
        assertEquals("classification: P.answerSeconds failure", "InvalidBootReading", p.preparationFailure)
    }

    @Test fun P_answerOrigin_class() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30), origin = LifetimeId("other")), F.guard(), F.request())
        assertNotNull(F.eligible("P.answerOrigin.class"), p.preparationFailure)
        assertEquals("classification: P.answerOrigin failure", "RequiredDecisionEffectMissing", p.preparationFailure)
    }

    @Test fun P_answerDelay_class() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30), minDelay = -1), F.guard(), F.request())
        assertNotNull(F.eligible("P.answerDelay.class"), p.preparationFailure)
        assertEquals("classification: P.answerDelay failure", "InvalidBootReading", p.preparationFailure)
    }

    @Test fun P_answerCapture_class() {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30), capture = BootReading("", 0)), F.guard(), F.request())
        assertNotNull(F.eligible("P.answerCapture.class"), p.preparationFailure)
        assertEquals("classification: P.answerCapture failure", "InvalidBootReading", p.preparationFailure)
    }

    @Test fun P_answerMerge_class() {
        val g = F.guard()
        assertNull("fixture: the guard has no floor", guard(g)!!.floor)
        val p = F.plan(F.decision(merge = BootReading("", 0)), g, null)
        assertNotNull(F.eligible("P.answerMerge.class"), p.preparationFailure)
        assertEquals("classification: P.answerMerge failure", "InvalidBootReading", p.preparationFailure)
    }

    // T — kinds and reasons of lifecycle and DemandAuth rejections (ControlLifecycle.kt:221, DemandAuthTransition.kt:18–44).

    @Test fun T_contextMissing_class() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        val raw = F.raw(g, r); F.schema(raw)
        assertTrue("positive twin: the attempt context confirms", decide(p, raw) is RecordTransactionDecision.Confirm)
        val d = decide(p, raw, null)
        assertFalse(F.eligible("T.contextMissing.class"), noFalseSuccess(d))
        val result = negative(d)
        assertTrue("classification: T.contextMissing kind", result is ControlStoreResult.Rejected)
        assertEquals("classification: T.contextMissing reason", RejectionReason.InvalidRequest("AttemptContextRequired"),
            (result as ControlStoreResult.Rejected).reason)
    }

    @Test fun T_runtimeMissing_class() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        val raw = F.raw(g, r); F.schema(raw)
        val context = F.context().copy(demandAuth = null)
        assertEquals("fixture: the attempt context matches the executor", F.context().ownerUid, context.ownerUid)
        val d = decide(p, raw, context)
        assertFalse(F.eligible("T.runtimeMissing.class"), noFalseSuccess(d))
        val result = negative(d)
        assertTrue("classification: T.runtimeMissing kind", result is ControlStoreResult.Rejected)
        assertEquals("classification: T.runtimeMissing reason", RejectionReason.InvalidRequest("AttemptContextRequired"),
            (result as ControlStoreResult.Rejected).reason)
    }

    @Test fun T_eligibilityDetail_class() {
        val g = F.guard(); val r = F.request(); val p = F.plan(guard = g, retry = r)
        val raw = F.raw(g, r); F.schema(raw)
        val rt = F.runtime(registrations = emptyList())
        assertEquals("fixture: only the registration is missing", setOf("q.registered"), V.falses(V.eligibility(p, rt, raw)))
        val d = decide(p, raw, F.context(rt))
        assertFalse(F.eligible("T.eligibilityDetail.class"), noFalseSuccess(d))
        val result = negative(d)
        assertTrue("classification: T.eligibilityDetail kind", result is ControlStoreResult.Rejected)
        assertEquals("classification: T.eligibilityDetail reason", RejectionReason.InvalidRequest("FreshQueryRequired"),
            (result as ControlStoreResult.Rejected).reason)
    }

    @Test fun T_conflictReason_class() {
        val g = F.guard(); val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
        val p = F.plan(pending, g, weak); val raw = F.raw(g, weak); F.schema(raw)
        val moved = F.runtime(binding = F.binding.copy(identity = IdentityV1("A", 3)))
        assertEquals("fixture: only the runtime binding differs among the writer gates", setOf("dt.runtimeBinding"),
            V.falses(V.decideGates(p, F.context(moved), raw)))
        val d = decide(p, raw, F.context(moved))
        assertFalse(F.eligible("T.conflictReason.class"), noFalseSuccess(d))
        val result = negative(d)
        assertTrue("classification: T.conflictReason kind", result is ControlStoreResult.Conflict)
        assertEquals("classification: T.conflictReason reason", ConflictReason.TargetChanged, (result as ControlStoreResult.Conflict).reason)
    }

    @Test fun T_unchangedConflict_class() {
        val g = F.guard(); val strong = F.request()
        val p = F.plan(pending, g, strong)
        assertEquals("fixture: the REQUEST is a required unchanged row", listOf("r"), p.unchanged.map { it.target.id })
        assertEquals("fixture: the guard is the only target", listOf("g"), p.targets.map { it.target.id })
        assertTrue("positive twin: the unchanged REQUEST confirms", decide(p, F.raw(g, strong)) is RecordTransactionDecision.Confirm)
        val raw = F.raw(g, F.request(order = 5)); F.schema(raw)
        val d = decide(p, raw)
        assertFalse(F.eligible("T.unchangedConflict.class"), noFalseSuccess(d))
        assertTrue("classification: T.unchangedConflict kind", negative(d) is ControlStoreResult.Conflict)
    }

    @Test fun T_guardCreate_class() {
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertTrue("positive twin: an empty record confirms", decide(p, F.raw()) is RecordTransactionDecision.Confirm)
        val before = F.raw(F.guard(auth = null, wait = 30000, id = "g-other")); F.schema(before)
        val d = decide(p, before)
        assertFalse(F.eligible("T.guardCreate.class"), noFalseSuccess(d))
        assertTrue("classification: T.guardCreate kind", negative(d) is ControlStoreResult.Conflict)
    }

    @Test fun T_evidenceReason_class() {
        val rows = (1..10).map {
            ControlAppliedEvidence.node(AppliedEvidence.Lifecycle("old-$it", "00000000-0000-0000-0000-%012d".format(it),
                LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "gone-$it", LifecycleEffect.REMOVE))))
        }
        val before = F.raw().toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = JsonArray(rows).toString() }.toPreferences()
        F.schema(before)
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: original evidence bytes", 2223, before[ControlLifecycleEvidenceFixtures.evidenceKey]!!.toByteArray(Charsets.UTF_8).size)
        val d = decide(p, before, codec = ControlPayloadCodec(maxPayloadBytes = 2300))
        assertFalse(F.eligible("T.evidenceReason.class"), noFalseSuccess(d))
        val result = negative(d)
        assertTrue("classification: T.evidenceReason kind", result is ControlStoreResult.Rejected)
        assertEquals("classification: T.evidenceReason reason", RejectionReason.TooLarge(ControlPayloadKey.COMMAND_EVIDENCE, 2439, 2300),
            (result as ControlStoreResult.Rejected).reason)
    }

    // L — ControlLifecycle.kt:89–96 preimage boundary: an uninterpretable target row is its own conflict reason.

    @Test fun L_preimageUninterpretable_class() {
        val bad = F.node(F.guard().toPayloadEntry().fields.toString().dropLast(1) + ",\"future\":1}")
        assertNull("fixture: the target row is uninterpretable", guard(bad))
        val read = F.read(F.raw(bad))
        assertTrue("fixture: the reader keeps the row under its id", read.locations("g").single().second is ControlEntryRead.Uninterpretable)
        val fixed = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE), LifecycleRole.GUARD, F.guard(), F.guard(auth = null))
        val reason = ControlLifecycleBoundary.preimage(read, fixed)
        assertNotNull(F.eligible("L.preimageUninterpretable.class"), reason)
        assertEquals("classification: L.preimageUninterpretable reason", ConflictReason.UninterpretableTarget, reason)
    }
}
