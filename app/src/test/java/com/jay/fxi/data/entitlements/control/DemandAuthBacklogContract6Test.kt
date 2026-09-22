package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.MAY_CONTAIN_PREMIUM
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.TEARDOWN_OWED_FOR
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.DemandAuthFixtures as F
import com.jay.fxi.data.entitlements.control.DemandAuthTruthVector as V
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 5e-1 contract, sixth file (scope group B): the shared lifecycle gates reached by a normal DemandAuth factory
 * descriptor. Each negative fixture starts from a positive twin that reaches Confirm and violates one gate condition;
 * the TV vectors name that condition. The first fixed assertion forbids Confirm. Result kind and reason follow with
 * plain messages (classification). Confirm-only fixtures use the landed candidate of the same fixed ref.
 *
 * Design basis: §3.1 new-application common gates (gates 1, 2, 4, 5), §3.3 postcondition confirmation, §8.1–8.2
 * (history and uninterpretable records hold both application and confirmation).
 *
 * Several new-application fixtures also fail a later DemandAuth defence when their LC gate is bypassed; a bypass of that
 * gate alone then keeps the outcome negative and is recorded as DEFENSE, not as a kill of this fixture. Probe evidence
 * (`contract-c6-draft/mask_probe.*`): record obligation at LC:181 → DT:46 candidate re-check; command id in the evidence
 * log (LC:133) → DT:46; taken CREATE id (LC:140/214) → DT:46; duplicate ids and empty targets (LC:190/232/234) → DT:46;
 * absent REPLACE target (LC:91) → LC:92 in the same helper (reason only) and, at LC:215, DT:46; empty epoch (LC:127) →
 * DT:35 currentness. On new application only, bypassing LC:83 with schema 1 reaches the cast exception at LC:132;
 * that observation does not close the confirm-only path with previouslyConfirmed=true. Confirm-only fixtures carry
 * the probes for LC:181, LC:191 and each LC:123–127 term; schema-1 confirmation has its own retry contract.
 */
class DemandAuthBacklogContract6Test {
    private val g = F.guard()
    private val weak = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS)
    private val orders get() = LifecycleOrderSource(F.life, 21)
    private fun plan() = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, weak)
    private fun demand(vararg nodes: ControlNode) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }
    private fun ctx(owner: String? = "A", binding: Long = 3, origin: LifetimeId = F.life, signOut: Boolean = false,
        pending: Boolean = false) = AttemptContext(owner, binding, origin, signOut, pending, F.runtime())
    private fun decide(p: DemandAuthPlan, raw: Preferences, context: AttemptContext? = F.context(),
        confirmOnly: Boolean = false, previouslyConfirmed: Boolean = false, c: CommandRef = F.command(p)): RecordTransactionDecision<*> {
        val input = (c.body as ControlCommandBody.Lifecycle).input
        return ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(raw), context, confirmOnly, previouslyConfirmed)
    }
    private fun result(d: RecordTransactionDecision<*>) =
        ((d as RecordTransactionDecision.Observe<*>).value as ControlRecordStore.Outcome.Negative).result
    private fun positiveTwin(p: DemandAuthPlan, raw: Preferences, context: AttemptContext = F.context()) {
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertTrue("positive twin must reach Confirm", decide(p, raw, context) is RecordTransactionDecision.Confirm)
    }
    private fun vectors(p: DemandAuthPlan, raw: Preferences, context: AttemptContext, common: Set<String>, gates: Set<String>) {
        assertEquals("truth vector: common premises", common, V.falses(V.commonPremises(p, raw, "command")))
        assertEquals("truth vector: writer gates", gates, V.falses(V.decideGates(p, context, raw)))
    }
    private fun ineligible(id: String, p: DemandAuthPlan, raw: Preferences, context: AttemptContext = F.context()): ControlStoreResult {
        val d = decide(p, raw, context)
        assertFalse(F.eligible(id), d is RecordTransactionDecision.Confirm)
        return result(d)
    }

    // Gate 1 — record problems (LC:181, LC:83–84). §8.2 1: uninterpretable obligations/metadata and schema 1 hold
    // new lifecycle application.

    @Test fun LC_record_schema() {
        val raw = ControlLifecycleEvidenceFixtures.raw(demand = demand(g, weak), schema = 1)
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), setOf("lc.schema2"), emptySet())
        val r = ineligible("LC.record.schema", plan(), raw)
        assertEquals("classification", RecoveryReason.ControlSchemaMigrationRequired, (r as ControlStoreResult.RecoveryRequired).reason)
    }
    @Test fun LC_record_obligation() {
        val opaque = F.node("""{"id":"x","kind":"NOT_A_KIND"}""")
        val raw = F.raw(g, weak, opaque)
        assertTrue("fixture: one uninterpretable obligation", F.read(raw).hasUninterpretable)
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), setOf("lc.obligationsInterpretable"), emptySet())
        val r = ineligible("LC.record.obligation", plan(), raw)
        assertEquals("classification", RecoveryReason.UninterpretableObligations, (r as ControlStoreResult.RecoveryRequired).reason)
    }

    // Gate 2 — raw types and epoch domains (LC:191, LC:123–127). §3.1 gate 2 / TV.commonPremises.

    @Test fun LC_raw_type() {
        val raw = F.raw(g, weak).toMutablePreferences().apply { this[stringPreferencesKey(MAY_CONTAIN_PREMIUM.name)] = "true" }.toPreferences()
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), setOf("raw[${MAY_CONTAIN_PREMIUM.name}].boolean"), emptySet())
        val r = ineligible("LC.raw.type", plan(), raw)
        assertEquals("classification", RecoveryReason.UnreadableEpochState, (r as ControlStoreResult.RecoveryRequired).reason)
    }
    @Test fun LC_raw_epoch() {
        val raw = F.raw(g, weak).toMutablePreferences().apply { this[USER_EPOCH] = "" }.toPreferences()
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), setOf("lc.userEpoch"), emptySet())
        val r = ineligible("LC.raw.epoch", plan(), raw)
        assertEquals("classification", RecoveryReason.UnreadableEpochState, (r as ControlStoreResult.RecoveryRequired).reason)
    }

    // Gate 4 — command id available (LC:211, LC:132–135). §3.1: a command id already used by another tracker's
    // Applied row or by a SEAL settlement is never reused.

    @Test fun LC_commandId_evidence() {
        val other = ControlLifecycleEvidenceFixtures.wire("UPDATE_AUTH",
            ControlLifecycleEvidenceFixtures.target("DEMAND", "g", "REPLACE"), "command", ReclamationFixtures.oldLife)
        val raw = F.raw(g, weak).toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[$other]" }.toPreferences()
        F.schema(raw)
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), setOf("lc.commandIdFree"), emptySet())
        val r = ineligible("LC.commandId.evidence", plan(), raw)
        assertEquals("classification", ConflictReason.OperationIdCollision, (r as ControlStoreResult.Conflict).reason)
    }
    @Test fun LC_commandId_seal() {
        val seal = HandoverFormatFixtures.lSeal.replace("\"operationId\":\"lop\"", "\"operationId\":\"command\"")
        val raw = F.raw(g, weak).toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)] = "[$seal]" }.toPreferences()
        F.schema(raw)
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), setOf("lc.noSeals"), emptySet())
        val r = ineligible("LC.commandId.seal", plan(), raw)
        assertEquals("classification", ConflictReason.OperationIdCollision, (r as ControlStoreResult.Conflict).reason)
    }

    // Gate 5 — fixed targets (LC:212–215, LC:90–96, LC:140). §3.1: exact preimage re-check; a CREATE id must be free.

    @Test fun LC_target_absent() {
        val raw = F.raw(g)
        F.schema(raw)
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), emptySet(), setOf("target[r].preimage"))
        val r = ineligible("LC.target.absent", plan(), raw)
        assertEquals("classification", ConflictReason.TargetMissing, (r as ControlStoreResult.Conflict).reason)
    }
    @Test fun LC_target_changed() {
        val moved = F.request(intent = RefreshIntent.FORCE_ENTITLEMENTS, order = 5)
        val raw = F.raw(g, moved)
        F.schema(raw)
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), emptySet(), setOf("target[r].preimage"))
        val r = ineligible("LC.target.changed", plan(), raw)
        assertEquals("classification", ConflictReason.TargetChanged, (r as ControlStoreResult.Conflict).reason)
        assertEquals(
            "classification: LC.conflict.expectedIds",
            listOf("g", "r"),
            (r as ControlStoreResult.Conflict).expected.effectiveIds
        )
    }
    @Test fun LC_target_createTaken() {
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        val taken = F.request(id = "g-new")
        val raw = F.raw(taken)
        F.schema(raw)
        positiveTwin(p, F.raw())
        vectors(p, raw, F.context(), emptySet(), setOf("target[g-new].absent"))
        val r = ineligible("LC.target.createTaken", p, raw)
        assertEquals("classification", ConflictReason.IdCollision, (r as ControlStoreResult.Conflict).reason)
    }

    /**
     * One row with the target id sits in another obligation array (review r1: the Reader rejects only repeated ids).
     * LC:94 rejects the kind; if bypassed, LC:96 still compares the payload, so the expected writer result stays negative.
     */
    @Test fun LC_target_wrongKind() {
        val seal = F.node(HandoverFormatFixtures.lSeal.replace("\"id\":\"ls\"", "\"id\":\"r\""))
        val raw = F.raw(g).toMutablePreferences().apply { this[ControlRecordKeys.payload(ControlKind.SEAL)] = demand(seal) }.toPreferences()
        F.schema(raw)
        assertEquals("fixture: the id has exactly one location, in SEAL", listOf(ControlKind.SEAL), F.read(raw).locations("r").map { it.first })
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, F.context(), setOf("lc.noSeals"), setOf("target[r].preimage"))
        val r = ineligible("LC.target.wrongKind", plan(), raw)
        assertEquals("classification", ConflictReason.IdCollision, (r as ControlStoreResult.Conflict).reason)
    }

    // Factory-built descriptors that fail validDescriptor facts although preparation succeeds (review r1 counterexamples).

    /** An empty new guard id: DP:60/216 pass it into the node; the guard parser rejects it (ControlSchema.kt:235). */
    @Test fun LC_descriptor_emptyGuardId() {
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "", "r-new")
        val twin = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        val raw = F.raw()
        F.schema(raw)
        positiveTwin(twin, raw)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals("truth vector: common premises", setOf("descriptor[].shape"), V.falses(V.commonPremises(p, raw, "command")))
        val r = ineligible("LC.descriptor.emptyGuardId", p, raw)
        assertEquals("classification", RejectionReason.InvalidRequest("InvalidLifecycleDescriptor"), (r as ControlStoreResult.Rejected).reason)
    }
    /** A negative identity generation reaches the new AUTH of a REPLACE guard (DP:204–205); the after node does not parse. */
    @Test fun LC_descriptor_invalidAuthAfter() {
        val g0 = F.guard(auth = null, wait = 1000)
        val bad = F.binding.copy(identity = IdentityV1("A", -1))
        val p = DemandAuthPlan.auth(g0, null, bad, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        val twin = DemandAuthPlan.auth(g0, null, F.binding, LifecycleAuthEvent.Initialize, orders, "g-new", "r-new")
        val raw = F.raw(g0)
        F.schema(raw)
        positiveTwin(twin, raw)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals("truth vector: common premises", setOf("descriptor[g].shape"), V.falses(V.commonPremises(p, raw, "command")))
        val rt = F.runtime(binding = bad, live = bad.identity)
        val r = ineligible("LC.descriptor.invalidAuthAfter", p, raw, F.context(rt))
        assertEquals("classification", RejectionReason.InvalidRequest("InvalidLifecycleDescriptor"), (r as ControlStoreResult.Rejected).reason)
    }
    /** An empty command id is accepted by CommandRef (ControlStoreResult.kt:32 compares only id == operationId). */
    @Test fun LC_descriptor_emptyCommandId() {
        val raw = F.raw(g, weak)
        F.schema(raw)
        positiveTwin(plan(), raw)
        val p = plan()
        assertEquals("truth vector: common premises", setOf("lc.commandIdNonempty"), V.falses(V.commonPremises(p, raw, "")))
        val input = p.descriptor("")
        val c = CommandRef("", ControlCommandBody.Lifecycle(input), OwnerTrackingLifetimeId.issue())
        val d = ControlLifecycleConfirmation(F.codec).decide(c, input, F.read(raw), F.context(), false, false)
        assertFalse(F.eligible("LC.descriptor.emptyCommandId"), d is RecordTransactionDecision.Confirm)
        assertEquals("classification", RejectionReason.InvalidRequest("InvalidLifecycleDescriptor"), (result(d) as ControlStoreResult.Rejected).reason)
    }

    // Descriptor read-only lists (LC:52, LC:54) — §3.1 123–130 fixed facts. The descriptor copies the plan's lists; the
    // copy itself must stay read-only. Two or more elements, because Kotlin's toList() of one element is immutable anyway
    // and would hide a removed wrapper (review r2).

    private fun readOnly(id: String, attempt: () -> Unit, intact: () -> Boolean) {
        runCatching(attempt).onFailure { if (it is Error) throw it }
        assertTrue(F.atomic(id), intact())
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun LC_descriptor_readOnlyTargets() {
        val p = DemandAuthPlan.rebind(listOf(F.request(id = "r1", binding = 2), F.request(id = "r2", binding = 2)), F.binding, orders)
        val d = p.descriptor("command")
        assertEquals("fixture: two descriptor targets", listOf("r1", "r2"), d.targets.map { it.target.id })
        readOnly("LC.descriptor.readOnlyTargets", { (d.targets as MutableList<LifecycleFixedTarget>).clear() },
            { d.targets.map { it.target.id } == listOf("r1", "r2") })
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun LC_descriptor_readOnlyUnchanged() {
        val resumed = F.auth.copy(authStopped = false, authStateOrder = 21)
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 60)), F.guard(auth = resumed, wait = 90000), F.request(), settle = true)
        val d = p.descriptor("command")
        assertEquals("fixture: two required-unchanged rows", listOf("r", "g"), d.requiredUnchanged.map { it.target.id })
        readOnly("LC.descriptor.readOnlyUnchanged", { (d.requiredUnchanged as MutableList<LifecycleFixedTarget>).clear() },
            { d.requiredUnchanged.map { it.target.id } == listOf("r", "g") })
    }

    // DT candidate checks by direct descriptor injection (review of implementation r4, I21). The plan requires a 30 s
    // floor; the injected descriptor keeps every fixed fact except the guard's after row, which drops that floor. This is
    // a direct boundary, not a claim about every factory output.

    private fun floorPlan(): Triple<DemandAuthPlan, ControlLifecycleDescriptor, ControlNode> {
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), F.guard(), F.request())
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals("fixture: one guard target, the strong REQUEST unchanged", listOf("GUARD/REPLACE"),
            p.targets.map { "${it.role}/${it.target.effect}" })
        assertNotNull("fixture: the plan requires a floor", guard(p.guardAfter)?.floor)
        val noFloor = F.guard(auth = F.auth.copy(authStopped = false, authStateOrder = 21))
        val t = p.targets.single()
        val injected = ControlLifecycleDescriptor("command", p.transition, listOf(t.copy(after = noFloor)), p.binding.executor,
            requiredUnchanged = p.unchanged, demandAuth = p)
        assertTrue("fixture: the injected descriptor is structurally valid", ControlLifecycleConfirmation(F.codec).validDescriptor(injected))
        return Triple(p, injected, noFloor)
    }
    /** §4.3 / DT:46: a candidate missing the decision's required floor is never confirmed. */
    @Test fun G_validApply() {
        val (p, injected, _) = floorPlan()
        val raw = F.raw(F.guard(), F.request())
        positiveTwin(p, raw)
        val c = CommandRef("command", ControlCommandBody.Lifecycle(injected), OwnerTrackingLifetimeId.issue())
        val d = ControlLifecycleConfirmation(F.codec).decide(c, injected, F.read(raw), F.context(), false, false)
        assertFalse(F.eligible("G.validApply"), d is RecordTransactionDecision.Confirm)
    }
    /** DT:250: with rows, evidence and external keys matching the injected descriptor, only the missing floor fails. */
    @Test fun V_effectsApply() {
        val (p, injected, noFloor) = floorPlan()
        val raw = F.raw(F.guard(), F.request())
        val c0 = F.command(p)
        val d0 = ControlLifecycleConfirmation(F.codec).decide(c0, (c0.body as ControlCommandBody.Lifecycle).input, F.read(raw), F.context(), false, false)
        assertTrue("positive twin must reach Confirm", d0 is RecordTransactionDecision.Confirm)
        val full = (d0 as RecordTransactionDecision.Confirm<*>).candidate
        val candidate = F.patch(full, "g") { noFloor.toPayloadEntry().fields.let(::JsonObject) }
        val c = CommandRef(c0.id, ControlCommandBody.Lifecycle(injected), c0.ownerTrackingLifetimeId)
        assertTrue("positive twin must be a valid candidate", F.transition.validCandidate(c0, (c0.body as ControlCommandBody.Lifecycle).input, F.read(raw), full))
        assertFalse(F.atomic("V.effectsApply"), F.transition.validCandidate(c, injected, F.read(raw), candidate))
    }

    // Executor currentness (LC:220–222, LC:106–111). §3.1 gate: fresh context owner/binding/origin, no open sign-out,
    // no pending identity persistence, no owed teardown. The DemandAuth runtime stays correct, so DT:27 still passes.

    private fun stale(id: String, context: AttemptContext, raw: Preferences, gate: String, reason: ConflictReason) {
        F.schema(raw)
        positiveTwin(plan(), F.raw(g, weak))
        vectors(plan(), raw, context, emptySet(), setOf(gate))
        val r = ineligible(id, plan(), raw, context)
        assertEquals("classification", reason, (r as ControlStoreResult.Conflict).reason)
    }
    @Test fun LC_current_owner() = stale("LC.current.owner", ctx(owner = "B"), F.raw(g, weak), "lc.contextOwner", ConflictReason.TargetChanged)
    @Test fun LC_current_binding() = stale("LC.current.binding", ctx(binding = 9), F.raw(g, weak), "lc.contextBinding", ConflictReason.TargetChanged)
    @Test fun LC_current_origin() = stale("LC.current.origin", ctx(origin = LifetimeId("other")), F.raw(g, weak), "lc.contextOrigin", ConflictReason.TargetChanged)
    @Test fun LC_current_signOut() = stale("LC.current.signOut", ctx(signOut = true), F.raw(g, weak), "lc.signOut", ConflictReason.IdentityTransitionPending)
    @Test fun LC_current_pending() = stale("LC.current.pending", ctx(pending = true), F.raw(g, weak), "lc.identityPending", ConflictReason.IdentityTransitionPending)
    @Test fun LC_current_teardown() = stale("LC.current.teardown", F.context(),
        F.raw(g, weak).toMutablePreferences().apply { this[TEARDOWN_OWED_FOR] = "A" }.toPreferences(), "lc.teardown", ConflictReason.IdentityTransitionPending)

    // Descriptor ids (LC:190, LC:233–234). §3.2 156: target ids are unique; the facade issues fresh ids, but a caller
    // passing one id for both new rows builds a factory descriptor with a duplicate.

    @Test fun LC_descriptor_duplicateIds() {
        val r = F.request()
        val d = F.decision(followUp = RefreshIntent.FORCE_PREMIUM, minDelay = 1000)
        val p = DemandAuthPlan.settle(listOf(r), null, null, F.binding, d, orders, "dup", "dup")
        val twin = DemandAuthPlan.settle(listOf(r), null, null, F.binding, d, orders, "g-new", "r-new")
        val raw = F.raw(r)
        F.schema(raw)
        positiveTwin(twin, raw)
        assertNull("fixture plan must be prepared", p.preparationFailure)
        assertEquals("truth vector: common premises", setOf("lc.descriptorIds"), V.falses(V.commonPremises(p, raw, "command")))
        val res = ineligible("LC.descriptor.duplicateIds", p, raw)
        assertEquals("classification", RejectionReason.InvalidRequest("InvalidLifecycleDescriptor"), (res as ControlStoreResult.Rejected).reason)
    }

    // Confirm-only (LC:192–208). §8.2 3–4: without own Applied and without earlier confirmation history the ref is
    // unconfirmed; §3.3: each fixed target's current postcondition must hold; §8.2 1: an uninterpretable record holds
    // confirmation too.

    private fun landed(): Triple<CommandRef, DemandAuthPlan, Preferences> {
        val p = plan(); val (c, after) = F.apply(p, F.raw(g, weak))
        assertTrue("fixture: the landed ref confirms", decide(p, after, null, true, false, c) is RecordTransactionDecision.Confirm)
        return Triple(c, p, after)
    }
    private fun withoutEvidence(raw: Preferences) =
        raw.toMutablePreferences().apply { this[ControlLifecycleEvidenceFixtures.evidenceKey] = "[]" }.toPreferences()

    @Test fun LC_confirm_schema() {
        val (c, p, after) = landed()
        val retained = withoutEvidence(after)
        assertTrue("fixture: retained history confirms the same postconditions",
            decide(p, retained, null, true, true, c) is RecordTransactionDecision.Confirm)
        val schema1 = retained.toMutablePreferences().apply {
            this[intPreferencesKey(ControlRecordKeys.SCHEMA)] = 1
            remove(ControlLifecycleEvidenceFixtures.evidenceKey)
            remove(ControlRecordKeys.payload(ControlPayloadKey.SCOPE_FENCE))
        }.toPreferences()
        assertEquals("truth vector: schema 1 is the only false common premise",
            setOf("lc.schema2"), V.falses(V.commonPremises(p, schema1, "command")))
        val d = decide(p, schema1, null, true, true, c)
        assertFalse(F.retry("LC.confirm.schema"), d is RecordTransactionDecision.Confirm)
        assertEquals("classification", RecoveryReason.ControlSchemaMigrationRequired,
            (result(d) as ControlStoreResult.RecoveryRequired).reason)
    }

    @Test fun LC_confirm_history() {
        val (c, p, after) = landed()
        val stripped = withoutEvidence(after)
        assertNull("fixture: no own Applied", ControlAppliedEvidence.own(F.read(stripped), c))
        val d = decide(p, stripped, null, true, false, c)
        assertFalse(F.retry("LC.confirm.history"), d is RecordTransactionDecision.Confirm)
        val r = result(d)
        assertTrue("classification", r is ControlStoreResult.Unconfirmed && r.reason == UnconfirmedReason.HistoryUnavailable)
        assertTrue(F.retry("LC.confirm.history.previouslyConfirmed"),
            decide(p, stripped, null, true, true, c) is RecordTransactionDecision.Confirm)
    }
    /** §3.3: requiredUnchanged is part of the confirmed postcondition set (LC:196); only that row changes here. */
    @Test fun LC_confirm_unchanged() {
        val strong = F.request()
        val p = F.plan(F.decision(outcome = EntitlementsOutcome.Pending(false, 30)), g, strong)
        assertEquals("fixture: the strong REQUEST is required unchanged", listOf("r"), p.unchanged.map { it.target.id })
        val (c, after) = F.apply(p, F.raw(g, strong))
        val moved = F.patch(after, "r") { JsonObject(it + ("raisedAt" to JsonPrimitive(5))) }
        F.schema(moved)
        val d = decide(p, moved, null, true, false, c)
        assertFalse(F.retry("LC.confirm.unchanged"), d is RecordTransactionDecision.Confirm)
        assertEquals("classification", ConflictReason.TargetChanged, (result(d) as ControlStoreResult.Conflict).reason)
    }
    /** The target row now lives in another array; LC:150 reports Changed (bypassed, LC:151–153 still report Changed). */
    @Test fun LC_confirm_wrongKind() {
        val p = plan(); val (c, after) = F.apply(p, F.raw(g, weak))
        val seal = F.node(HandoverFormatFixtures.lSeal.replace("\"id\":\"ls\"", "\"id\":\"r\""))
        val swapped = F.patch(after, "r") { null }.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)] = demand(seal)
        }.toPreferences()
        F.schema(swapped)
        assertEquals("fixture: the id has exactly one location, in SEAL", listOf(ControlKind.SEAL), F.read(swapped).locations("r").map { it.first })
        val d = decide(p, swapped, null, true, false, c)
        assertFalse(F.retry("LC.confirm.wrongKind"), d is RecordTransactionDecision.Confirm)
        assertEquals("classification", ConflictReason.TargetChanged, (result(d) as ControlStoreResult.Conflict).reason)
    }
    @Test fun LC_confirm_removedTarget() {
        val r = F.request()
        val p = DemandAuthPlan.settle(listOf(r), null, null, F.binding, F.decision(), orders, "g-new", "r-new")
        val (c, after) = F.apply(p, F.raw(r))
        assertTrue("fixture: the landed ref confirms", decide(p, after, null, true, false, c) is RecordTransactionDecision.Confirm)
        val back = after.toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = demand(r)
        }.toPreferences()
        F.schema(back)
        val d = decide(p, back, null, true, false, c)
        assertFalse(F.retry("LC.confirm.removedTarget"), d is RecordTransactionDecision.Confirm)
        assertEquals("classification", ConflictReason.TargetChanged, (result(d) as ControlStoreResult.Conflict).reason)
    }
    @Test fun LC_confirm_obligation() {
        val (c, p, after) = landed()
        val opaque = after.toMutablePreferences().apply {
            val key = ControlRecordKeys.payload(ControlKind.DEMAND)
            this[key] = JsonArray((Json.parseToJsonElement(this[key]!!) as JsonArray) + Json.parseToJsonElement("""{"id":"x","kind":"NOT_A_KIND"}""")).toString()
        }.toPreferences()
        assertTrue("fixture: one uninterpretable obligation", F.read(opaque).hasUninterpretable)
        val d = decide(p, opaque, null, true, false, c)
        assertFalse(F.retry("LC.confirm.obligation"), d is RecordTransactionDecision.Confirm)
        assertEquals("classification", RecoveryReason.UninterpretableObligations, (result(d) as ControlStoreResult.RecoveryRequired).reason)
    }
    // Gate 2 per term on the confirm-only path (§8.2 1: unreadable raw state holds confirmation). Each fixture stores one
    // key with the wrong Preferences type, or one empty epoch, in the landed record of the same fixed ref.

    private fun confirmRaw(id: String, premise: String, restriction: Set<String> = emptySet(), mutate: MutablePreferences.() -> Unit) {
        val (c, p, after) = landed()
        val bad = after.toMutablePreferences().apply(mutate).toPreferences()
        assertEquals("truth vector: common premises (own Applied occupies the command id)", setOf(premise, "lc.commandIdFree") + restriction,
            V.falses(V.commonPremises(p, bad, "command")))
        val d = decide(p, bad, null, true, false, c)
        assertFalse(F.retry(id), d is RecordTransactionDecision.Confirm)
        assertEquals("classification", RecoveryReason.UnreadableEpochState, (result(d) as ControlStoreResult.RecoveryRequired).reason)
    }
    @Test fun LC_confirm_raw_owner() = confirmRaw("LC.confirm.raw.owner", "raw[owner_uid].string") { this[booleanPreferencesKey("owner_uid")] = true }
    @Test fun LC_confirm_raw_userEpochType() = confirmRaw("LC.confirm.raw.userEpochType", "raw[user_access_epoch].string") { this[booleanPreferencesKey("user_access_epoch")] = true }
    @Test fun LC_confirm_raw_krxEpochType() = confirmRaw("LC.confirm.raw.krxEpochType", "raw[krx_capability_epoch].string") { this[booleanPreferencesKey("krx_capability_epoch")] = true }
    @Test fun LC_confirm_raw_teardown() = confirmRaw("LC.confirm.raw.teardown", "raw[teardown_owed_for].string") { this[booleanPreferencesKey("teardown_owed_for")] = true }
    // TV's absent-journal fixture restriction is also false once the key exists, whatever its type.
    @Test fun LC_confirm_raw_journal() = confirmRaw("LC.confirm.raw.journal", "raw[pending_purge_journal].string",
        setOf("dt.journalAbsent")) { this[booleanPreferencesKey("pending_purge_journal")] = true }
    @Test fun LC_confirm_raw_premium() = confirmRaw("LC.confirm.raw.premium", "raw[may_contain_premium_data].boolean") { this[stringPreferencesKey("may_contain_premium_data")] = "true" }
    @Test fun LC_confirm_raw_krx() = confirmRaw("LC.confirm.raw.krx", "raw[may_contain_krx_data].boolean") { this[stringPreferencesKey("may_contain_krx_data")] = "true" }
    @Test fun LC_confirm_raw_userEpoch() = confirmRaw("LC.confirm.raw.userEpoch", "lc.userEpoch") { this[USER_EPOCH] = "" }
    @Test fun LC_confirm_raw_krxEpoch() = confirmRaw("LC.confirm.raw.krxEpoch", "lc.krxEpoch") { this[stringPreferencesKey("krx_capability_epoch")] = "" }

    /** §3.3 / LC:207–208: a satisfied confirmation returns the unchanged snapshot, PostconditionConfirmed and the target ids. */
    @Test fun LC_confirm_output() {
        val p = plan()
        val (c, after) = F.apply(p, F.raw(g, weak))
        val d = decide(p, after, null, true, false, c)
        val confirmed = d as? RecordTransactionDecision.Confirm<*>
        val o = confirmed?.value as? ControlRecordStore.Outcome.Positive
        assertTrue(F.retry("LC.confirm.output"),
            confirmed != null && confirmed.candidate == after && o != null)
        val positive = checkNotNull(o)
        assertEquals(F.retry("LC.confirm.output"), ConfirmedEffect.PostconditionConfirmed, positive.effect)
        assertEquals(F.retry("LC.confirm.output"), listOf("g", "r"), positive.ids)
        assertTrue(F.retry("LC.confirm.output"), positive.receipt is ControlLifecycleReceipt)
        val receipt = positive.receipt as ControlLifecycleReceipt
        assertEquals(F.retry("LC.confirm.output"), listOf(
            LifecycleObservedTarget(
                LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE),
                LifecycleTargetObservation.PresentExact),
            LifecycleObservedTarget(
                LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE),
                LifecycleTargetObservation.PresentExact)
        ), receipt.targets)
    }
}
