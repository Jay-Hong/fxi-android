package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, fifteenth file: no false success. A rejection boundary in DemandAuthTransition.decide must return
 * neither Confirm nor an Observe carrying Outcome.Positive, because the store publishes a positive observation as a
 * Confirmed result without writing the candidate (ControlRecordStore.kt:472–491). Each test keeps an existing fixture
 * — C14 W_journal_strict, C7 RC_conflict_expectedIds, C2 V_envelopes_demand, C2 V_envelopes_evidence — with its
 * independent premises and positive twin, and asserts the success boundary before any cast or reason comparison.
 * Rejection kinds and reasons are not compared here; they are CLASSIFICATION_ONLY.
 * Envelope limits and byte counts are fixed constants of the fixture wire; the production candidate is an observed
 * value only and never computes the limit.
 */
class DemandAuthBacklogContract15Test {
    private val demandKey = ControlRecordKeys.payload(ControlKind.DEMAND)
    private val evidenceKey = ControlLifecycleEvidenceFixtures.evidenceKey
    private val orders get() = LifecycleOrderSource(F.life, 21)
    // Preferences values may be ByteArray, which Map equality compares by reference; compare contents instead.
    private fun others(p: Preferences, except: Preferences.Key<*>) =
        p.asMap().filterKeys { it != except }.mapValues { (_, v) -> if (v is ByteArray) v.toList() else v }
    private fun decide(plan: DemandAuthPlan, before: Preferences, codec: ControlPayloadCodec = F.codec): RecordTransactionDecision<*> {
        val c = F.command(plan)
        val input = (c.body as ControlCommandBody.Lifecycle).input
        val read = ControlRecordReader(codec).read(before) as ControlRecordRead.Supported
        return ControlLifecycleConfirmation(codec).decide(c, input, read, F.context(), false, false)
    }
    private fun bytes(raw: Preferences, key: Preferences.Key<String>) = raw[key]!!.toByteArray(Charsets.UTF_8).size
    private fun initializePlan() =
        DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")

    // W.journal — DemandAuthTransition.kt:33–34. The C14 W_journal_strict fixture: only the journal differs.

    @Test fun W_journal_noFalseSuccess() {
        val g = F.guard(auth = null)
        val normal = F.raw(g)
        val broken = normal.toMutablePreferences().apply { this[DataStoreAccessEpochStore.PURGE_JOURNAL] = "unknown-format" }.toPreferences()
        F.schema(normal); F.schema(broken)
        val p = DemandAuthPlan.auth(g, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life), "g-new", "r-new")
        val c = F.command(p); val input = (c.body as ControlCommandBody.Lifecycle).input
        assertNull("fixture: plan is prepared", p.preparationFailure)
        assertEquals("fixture: writer gates", emptySet<String>(),
            V.falses(V.decideGates(p, F.context(), broken)))
        assertEquals("fixture: eligibility", emptySet<String>(),
            V.falses(V.eligibility(p, F.runtime(), broken)))
        assertEquals("fixture: only journal absence differs", setOf("dt.journalAbsent"),
            V.falses(V.commonPremises(p, broken, c.id)))
        assertNull("fixture: normal journal is absent", normal[DataStoreAccessEpochStore.PURGE_JOURNAL])
        assertEquals("fixture: malformed journal", "unknown-format",
            broken[DataStoreAccessEpochStore.PURGE_JOURNAL])
        assertTrue("positive twin: the canonical journal confirms",
            ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(normal), F.context(), false, false) is RecordTransactionDecision.Confirm)
        val key = DataStoreAccessEpochStore.PURGE_JOURNAL
        assertEquals("fixture: every other key is unchanged", others(normal, key), others(broken, key))
        assertNotEquals("fixture: the journal changed", normal[key], broken[key])
        val actual = ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(broken), F.context(), false, false)
        assertFalse(F.eligible("W.journal.noFalseSuccess"),
            actual is RecordTransactionDecision.Confirm || actual.value is ControlRecordStore.Outcome.Positive)
    }

    // W.conflict — DemandAuthTransition.kt:20–22 via DT:27. The C7 RC_conflict_expectedIds fixture: the attempt
    // context still matches the executor, and among the writer gates only the runtime binding differs.

    @Test fun W_conflict_noFalseSuccess() {
        val g = F.guard(); val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak)
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val raw = F.raw(g, weak)
        F.schema(raw)
        val c = F.command(p); val input = (c.body as ControlCommandBody.Lifecycle).input
        assertTrue("positive twin: the current binding confirms",
            ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(raw), F.context(), false, false) is RecordTransactionDecision.Confirm)
        val moved = F.runtime(binding = F.binding.copy(identity = IdentityV1("A", 3)))
        val context = F.context(moved)
        assertEquals("fixture: the attempt context still matches the executor", F.context().ownerUid, context.ownerUid)
        assertEquals("fixture: only the runtime binding differs among the writer gates", setOf("dt.runtimeBinding"),
            V.falses(V.decideGates(p, context, raw)))
        assertEquals("fixture: common premises", emptySet<String>(), V.falses(V.commonPremises(p, raw, c.id)))
        val actual = ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(raw), context, false, false)
        assertFalse(F.eligible("W.conflict.noFalseSuccess"),
            actual is RecordTransactionDecision.Confirm || actual.value is ControlRecordStore.Outcome.Positive)
    }

    // W.encode — DemandAuthTransition.kt:38–40. The C2 V_envelopes_demand fixture: DEMAND crosses the limit only
    // after the change, evidence stays within it, and the same plan confirms under the default envelope.

    @Test fun W_encode_noFalseSuccess() {
        val pads = (1..12).map { F.request(id = "pad-$it", owner = "B", binding = 1) }
        val before = F.raw(*pads.toTypedArray())
        F.schema(before)
        val p = initializePlan()
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val twin = decide(p, before)
        assertTrue("positive twin: the default envelope confirms", twin is RecordTransactionDecision.Confirm)
        val reference = (twin as RecordTransactionDecision.Confirm<*>).candidate
        val limit = 1600
        assertEquals("fixture: original DEMAND bytes", 1480, bytes(before, demandKey))
        assertEquals("fixture: changed DEMAND bytes", 1664, bytes(reference, demandKey))
        assertEquals("fixture: changed evidence bytes", 217, bytes(reference, evidenceKey))
        assertTrue("fixture: DEMAND crosses the limit only after the change", bytes(before, demandKey) <= limit && bytes(reference, demandKey) > limit)
        assertTrue("fixture: the evidence payload stays within the limit", bytes(reference, evidenceKey) <= limit)
        val actual = decide(p, before, codec = ControlPayloadCodec(maxPayloadBytes = limit))
        assertFalse(F.eligible("W.encode.noFalseSuccess"),
            actual is RecordTransactionDecision.Confirm || actual.value is ControlRecordStore.Outcome.Positive)
    }

    // W.evidence — DemandAuthTransition.kt:43–44. The C2 V_envelopes_evidence fixture: evidence crosses the limit
    // only after the self Applied row is added, DEMAND stays within it, and the same plan confirms by default.

    @Test fun W_evidence_noFalseSuccess() {
        val rows = (1..10).map {
            ControlAppliedEvidence.node(AppliedEvidence.Lifecycle("old-$it", "00000000-0000-0000-0000-%012d".format(it),
                LifecycleTransition.REMOVE_EMPTY_GUARD, listOf(LifecycleTarget(ControlKind.DEMAND, "gone-$it", LifecycleEffect.REMOVE))))
        }
        val before = F.raw().toMutablePreferences().apply { this[evidenceKey] = JsonArray(rows).toString() }.toPreferences()
        F.schema(before)
        val p = initializePlan()
        assertNull("fixture: plan is prepared", p.preparationFailure)
        val twin = decide(p, before)
        assertTrue("positive twin: the default envelope confirms", twin is RecordTransactionDecision.Confirm)
        val reference = (twin as RecordTransactionDecision.Confirm<*>).candidate
        val limit = 2300
        assertEquals("fixture: original evidence bytes", 2223, bytes(before, evidenceKey))
        assertEquals("fixture: changed evidence bytes", 2439, bytes(reference, evidenceKey))
        assertEquals("fixture: changed DEMAND bytes", 185, bytes(reference, demandKey))
        assertTrue("fixture: evidence crosses the limit only after the change", bytes(before, evidenceKey) <= limit && bytes(reference, evidenceKey) > limit)
        assertTrue("fixture: the DEMAND payload stays within the limit", bytes(reference, demandKey) <= limit)
        val actual = decide(p, before, codec = ControlPayloadCodec(maxPayloadBytes = limit))
        assertFalse(F.eligible("W.evidence.noFalseSuccess"),
            actual is RecordTransactionDecision.Confirm || actual.value is ControlRecordStore.Outcome.Positive)
    }
}
