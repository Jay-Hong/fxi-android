package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Claude-owned 6-4bA1a contract: the Mutations branch of the pure required-obligation derivation (T2 Mutations rows only).
 * Fixed inputs: 6-4bA API consensus r2, 6-4bA1 declaration consensus r2 (declaration r2 + UNSUPPORTED_IN_THIS_UNIT), 6-4bA1a
 * table consensus (table r2). Expected lists are written here from literals and constructors, independently of the deriver.
 *
 * Order: action index ascending → component SOURCE, REQUEST, SEAL, FLOOR, AUTH → L before N. Every produced slot is Required.
 * Subjects are the fixed source (before, else after) for both branches; lower bounds carry the after requirement.
 * fixedSources are the exact ordered AC/AP/EC/EP lists, holding the very action, node, target and checkpoint instances.
 *
 * Unavailable decisions pinned here (table r2 plus two tie-breaks this contract fixes):
 *  - Current adoption inconsistent with the prepared action (joined on a non-SEAL Add or on any Edit, another id, a joined
 *    SEAL of another kind/key) → FIXED_INPUT_INCONSISTENT at (MUTATION_ADOPTION, i, WHOLE).
 *  - Previous checkpoint target inconsistent with the prepared action → CHECKPOINT_MISMATCH at (PREVIOUS_CHECKPOINT, i, AFTER)
 *    (the specific checkpoint row wins over the generic inconsistency row).
 *  - An Edit whose before node is uninterpretable reports BEFORE, not AFTER (fixedSources order WHOLE → BEFORE → AFTER).
 *  - A joined target is valid only for a SEAL Add whose adopted seal has the target's id, the prepared key, and no
 *    settlement. An empty Mutations body → FIXED_INPUT_INCONSISTENT at (COMMAND, null, WHOLE) (6-4bA1a measurement r1).
 * Rotation, Settlement and Lifecycle inputs → UNSUPPORTED_IN_THIS_UNIT at (COMMAND, null, WHOLE) until 6-4bA1c.
 * The implementation thread reads but does not edit this file.
 */
class RequiredObligationsMutationsContractTest {
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val E = AllowedSlotDisposition.EITHER
    private val D = AllowedSlotDisposition.DURABLY_OWNED_ONLY
    private val life = LifetimeId("life")
    private val life2 = LifetimeId("life2")
    private var uuidSeed = 0L
    private fun uuid() = UUID(0L, ++uuidSeed)

    // ── independent facts from ControlObligationFixtures literals ──────────────────────────────────────────────────────
    private val floorF = FloorV1("boot", 10_000, 30_000, life)
    private val authA = AuthSnapshotV1("A", 2, 3, life, true, 8, 10)
    private val guardFloorOnly = """{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}"""

    private val authOnlyGuard = """{"id":"g","kind":"SCHEDULE_GUARD","auth":${ControlObligationFixtures.auth}}"""
    private val fenceF = FenceV1("A", "u", "k")
    private val identityI = IdentityV1("A", 2)

    private fun demand(id: String, intent: RefreshIntent = RefreshIntent.IF_STALE, raisedAt: Long = 4) =
        DemandV1(id, "A", 3, intent, EventOrderV1(life, raisedAt))
    /** ControlObligationFixtures.hold, written field by field (never through the production parser). */
    private fun queryHold(id: String) = RestoredHold(id, life, 3, setOf(PurgeScope.CAPABILITY),
        HoldOutcomeV1(HoldOutcomeKind.PENDING, false, 30),
        HoldProvenanceV1.Query(StartedQueryV1(fenceF, 5, identityI, EventOrderV1(life, 7), 3, RefreshIntent.FORCE_PREMIUM, 0), identityI),
        floorF)
    /** ControlObligationFixtures.topicHold, field by field. */
    private fun topicHold(id: String) = RestoredHold(id, life, 3, setOf(PurgeScope.USER, PurgeScope.CAPABILITY),
        HoldOutcomeV1(HoldOutcomeKind.PREMIUM_REQUIRED, null, null),
        HoldProvenanceV1.Topic(9, TopicContextV1(identityI, fenceF, 5)), null)
    /** ControlObligationFixtures.settledSeal, field by field. */
    private val settledSealZ = SealV1("s", SealTargetKind.NULL_NAMESPACE, SealKey("A", PurgeScope.USER, null),
        SettlementEvidenceV1("op", life, com.jay.fxi.data.entitlements.StoreOp.BEGIN_ROTATION, FenceV1("A", null, "k"), fenceF,
            JournalTargetV1("A", PurgeScope.USER, null)))
    /** Reads only the issued id of a prepared node (the id is issued by the fixture, not a fact the deriver interprets). */
    private fun <T : ControlObligationV1> value(kind: ControlKind, n: ControlNode): T {
        @Suppress("UNCHECKED_CAST")
        return (ControlObligations.read(kind, n) as ControlEntryRead.Interpreted).value as T
    }

    // ── command construction ───────────────────────────────────────────────────────────────────────────────────────────
    private fun add(kind: ControlKind, json: String): ControlMutation.Add = ControlMutation.Add.prepare(kind, uuid()) { id ->
        literal(json); set("id", ControlScalar.Text(id))
    }
    private fun ControlMutation.Add.after() = (built as ControlWriteResult.Written).node
    private fun ControlMutation.Edit.after() = (changed as ControlWriteResult.Written).node
    private fun afterOf(m: ControlMutation) = when (m) { is ControlMutation.Add -> m.after(); is ControlMutation.Edit -> m.after() }
    private fun idOf(m: ControlMutation): String = (m as? ControlMutation.Add)?.proposedId ?: value<ControlObligationV1>(m.kind, afterOf(m)).id

    private class Built(val ref: CommandRef, val body: ControlCommandBody.Mutations, val targets: List<ControlCommandTarget?>)
    private fun build(vararg actions: ControlMutation, target: (Int, ControlMutation) -> ControlCommandTarget? = { _, m ->
        ControlCommandTarget(idOf(m), afterOf(m), false)
    }): Built {
        val ref = CommandRef("cmd-${uuid()}", actions.toList(), OwnerTrackingLifetimeId.issue())
        return Built(ref, ref.body as ControlCommandBody.Mutations, actions.mapIndexed(target))
    }
    private fun current(b: Built, targets: List<ControlCommandTarget?>? = b.targets) =
        RequirementInput.Mutations(b.ref, b.body, MutationAdoption.Current(targets))
    private fun checkpoint(b: Built, targets: List<ControlCommandTarget?> = b.targets, command: CommandRef = b.ref,
        requested: Boolean = true) = ControlCommandCheckpoint(command, targets, requested)
    private fun previous(b: Built, c: ControlCommandCheckpoint? = checkpoint(b)) =
        RequirementInput.Mutations(b.ref, b.body, MutationAdoption.Previous(c))

    // ── expected fixedSources (AC / AP / EC / EP) ──────────────────────────────────────────────────────────────────────
    private fun loc(root: FixedInputRoot, index: Int?, facet: FixedInputFacet) = FixedInputLocation(root, index, facet)
    private fun ev(l: FixedInputLocation, f: FixedSourceFact) = FixedSourceEvidence(l, f)

    /** Sources of action [i] under the given adoption mode; [c] is the checkpoint for Previous, null for Current. */
    private fun sources(b: Built, i: Int, c: ControlCommandCheckpoint?): List<FixedSourceEvidence> {
        val m = b.body.actions[i]
        val out = mutableListOf(ev(loc(FixedInputRoot.MUTATION_ACTION, i, FixedInputFacet.WHOLE), FixedSourceFact.Mutation(m)))
        if (m is ControlMutation.Edit)
            out += ev(loc(FixedInputRoot.MUTATION_ACTION, i, FixedInputFacet.BEFORE), FixedSourceFact.Node(m.kind, m.before))
        out += ev(loc(FixedInputRoot.MUTATION_ACTION, i, FixedInputFacet.AFTER), FixedSourceFact.Node(m.kind, afterOf(m)))
        if (c == null) {
            out += ev(loc(FixedInputRoot.MUTATION_ADOPTION, i, FixedInputFacet.WHOLE), FixedSourceFact.Adoption(b.targets[i]!!))
        } else {
            out += ev(loc(FixedInputRoot.PREVIOUS_CHECKPOINT, null, FixedInputFacet.WHOLE), FixedSourceFact.Checkpoint(c))
            out += ev(loc(FixedInputRoot.PREVIOUS_CHECKPOINT, i, FixedInputFacet.AFTER), FixedSourceFact.Adoption(c.targets[i]!!))
        }
        return out
    }

    // ── expected slots (table r2 legend) ───────────────────────────────────────────────────────────────────────────────
    private fun slot(i: Int, subject: ObligationSubject, component: ObligationComponent, branch: LandingBranch,
        bound: RequiredLowerBound, allowed: AllowedSlotDisposition, fixed: List<FixedSourceEvidence>) =
        RequiredSlot(RequiredObligationKey(ObligationRole.MutationAction(i), subject, component, branch),
            SlotRequirement.Required(bound, allowed, fixed))

    private fun q(i: Int, b: LandingBranch, s: DemandV1, x: DemandV1?, m: DemandV1, f: List<FixedSourceEvidence>) =
        slot(i, ObligationSubject.Request(s.id, s.ownerUid, s.binding, s.raisedAt), ObligationComponent.REQUEST, b,
            RequiredLowerBound.Request(x, s.id, s.ownerUid, s.binding, m.intent, m.raisedAt), E, f)
    private fun s(i: Int, b: LandingBranch, z: SealV1, f: List<FixedSourceEvidence>) =
        slot(i, ObligationSubject.Seal(z.id, z.kind, z.key), ObligationComponent.SEAL, b, RequiredLowerBound.Seal(z, z.settlement), E, f)
    private fun h(i: Int, b: LandingBranch, x: RestoredHold, f: List<FixedSourceEvidence>) =
        slot(i, ObligationSubject.Hold(x.id, x.originLifetimeId, x.binding, x.provenance, x.axes), ObligationComponent.SOURCE, b,
            RequiredLowerBound.Hold(x), E, f)
    private fun intent(i: Int, b: LandingBranch, t: RecoveryIntentV1, f: List<FixedSourceEvidence>) =
        slot(i, ObligationSubject.Intent(t.id, t.sessionId, t.ownerUid, t.axis, t.targetEpoch), ObligationComponent.SOURCE, b,
            RequiredLowerBound.Intent(t), E, f)
    private fun fl(i: Int, b: LandingBranch, k: ControlKind, id: String, o: LifetimeId, captured: FloorV1, f: List<FixedSourceEvidence>) =
        slot(i, ObligationSubject.Floor(k, id, o), ObligationComponent.FLOOR, b, RequiredLowerBound.Floor(k, id, captured), D, f)
    private fun u(i: Int, b: LandingBranch, a: AuthSnapshotV1, f: List<FixedSourceEvidence>) =
        slot(i, ObligationSubject.Auth(a.ownerUid, a.binding, a.originLifetimeId, a.authGeneration), ObligationComponent.AUTH, b,
            RequiredLowerBound.Auth(a), E, f)

    // ── runners ────────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun available(input: RequirementInput): RequirementDerivation.Available {
        val r = deriveRequiredObligations(input)
        if (r !is RequirementDerivation.Available) fail("expected Available, got $r")
        return r as RequirementDerivation.Available
    }
    private fun unavailable(input: RequirementInput, reason: RequiredObligationsUnavailable, l: FixedInputLocation) =
        assertEquals(RequirementDerivation.Unavailable(reason, l), deriveRequiredObligations(input))

    /**
     * Runs Current and Previous for [b]: both yield the same ordered obligations and differ only in fixedSources.
     * [expected] receives the per-index sources for the mode under test. Also checks the command binding, that the list
     * cannot be mutated, and (for a non-empty list) that dropping one L or one N slot fails structural coverage.
     */
    private fun both(b: Built, expected: ((Int) -> List<FixedSourceEvidence>) -> List<RequiredSlot>) {
        val c = checkpoint(b)
        for ((input, src) in listOf(current(b) to { i: Int -> sources(b, i, null) }, previous(b, c) to { i: Int -> sources(b, i, c) })) {
            val r = available(input)
            assertSame(b.ref, r.commandBinding.ref)
            assertSame(b.body, r.commandBinding.body)
            assertEquals(FixedCommandKind.Mutations, r.commandBinding.kind)
            assertEquals(expected(src), r.orderedSlots)
            if (r.orderedSlots.isNotEmpty()) assertTrue("orderedSlots must be unmodifiable",
                runCatching { (r.orderedSlots as MutableList<RequiredSlot>).add(r.orderedSlots.first()) }.isFailure)
            coverageDetectsOneMissing(r.orderedSlots)
        }
    }

    private fun coverageDetectsOneMissing(slots: List<RequiredSlot>) {
        if (slots.isEmpty()) return
        val expected = slots.map { ExpectedSlot(it.key, it.necessity) }
        val all = slots.map { SubmittedSlot(it.key, ObligationDisposition.DurablyOwned) }
        assertEquals(CoverageResult.Complete, checkSlotCoverage(expected, all))
        for ((branch, problem) in listOf(L to CoverageProblem.MissingL, N to CoverageProblem.MissingN)) {
            val drop = slots.indexOfFirst { it.key.branch == branch }
            assertTrue("fixture has a $branch slot", drop >= 0)
            assertEquals(CoverageResult.Rejected(listOf(problem)), checkSlotCoverage(expected, all.filterIndexed { k, _ -> k != drop }))
        }
    }

    // ═══ REQUEST ════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun M01_requestAdd() {
        val a = add(ControlKind.DEMAND, ControlObligationFixtures.request)
        val b = build(a)
        val d = demand(idOf(a))
        both(b) { src -> listOf(q(0, L, d, d, d, src(0)), q(0, N, d, d, d, src(0))) }
    }

    @Test fun M02_requestEditChange_subjectBeforeBothBranches_boundAfter() {
        val before = node(ControlObligationFixtures.request)
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, before) {
            set("intent", ControlScalar.Text("FORCE_PREMIUM")); set("raisedAt", ControlScalar.Integer(9))
        } as ControlMutation.Edit
        val b = build(e)
        val dm = demand("d")
        val dp = demand("d", RefreshIntent.FORCE_PREMIUM, 9)
        both(b) { src -> listOf(q(0, L, dm, dp, dp, src(0)), q(0, N, dm, dm, dp, src(0))) }
    }

    @Test fun M03_requestEditNoOp() {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {} as ControlMutation.Edit
        val b = build(e)
        val d = demand("d")
        both(b) { src -> listOf(q(0, L, d, d, d, src(0)), q(0, N, d, d, d, src(0))) }
    }

    // ═══ SEAL ═══════════════════════════════════════════════════════════════════════════════════════════════════════════
    private fun sealOf(id: String) = SealV1(id, SealTargetKind.NAMESPACE, SealKey(null, PurgeScope.USER, "old"), null)

    @Test fun M04_sealAddNotJoined() {
        val a = add(ControlKind.SEAL, ControlObligationFixtures.seal)
        val b = build(a)
        val z = sealOf(idOf(a))
        both(b) { src -> listOf(s(0, L, z, src(0)), s(0, N, z, src(0))) }
    }

    @Test fun M05_sealAddJoined_usesAdoptedSealForBothBranches() {
        val a = add(ControlKind.SEAL, ControlObligationFixtures.seal)
        val existing = node(ControlObligationFixtures.seal.replace("\"id\":\"s\"", "\"id\":\"s-existing\""))
        val b = build(a) { _, _ -> ControlCommandTarget("s-existing", existing, true) }
        val z = sealOf("s-existing")
        both(b) { src -> listOf(s(0, L, z, src(0)), s(0, N, z, src(0))) }
    }

    @Test fun M06_settledSealEditNoOp_noJournalSlot() {
        val n = node(ControlObligationFixtures.settledSeal)
        val e = ControlMutation.Edit.prepare(ControlKind.SEAL, n) {} as ControlMutation.Edit
        val b = build(e)
        both(b) { src -> listOf(s(0, L, settledSealZ, src(0)), s(0, N, settledSealZ, src(0))) }
    }

    @Test fun M06b_unsettledSealEditNoOp() {
        val e = ControlMutation.Edit.prepare(ControlKind.SEAL, node(ControlObligationFixtures.seal)) {} as ControlMutation.Edit
        val b = build(e)
        val z = sealOf("s")
        both(b) { src -> listOf(s(0, L, z, src(0)), s(0, N, z, src(0))) }
    }

    // ═══ GUARD ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun M07_emptyGuardAdd_isCompletelyInterpretedNoObligation() {
        val b = build(add(ControlKind.DEMAND, ControlObligationFixtures.emptyGuard))
        both(b) { emptyList() }
    }

    @Test fun M08_guardAddFloorOnly_contrastsWithEmpty() {
        val a = add(ControlKind.DEMAND, guardFloorOnly)
        val b = build(a)
        val id = idOf(a)
        both(b) { src -> listOf(fl(0, L, ControlKind.DEMAND, id, life, floorF, src(0)), fl(0, N, ControlKind.DEMAND, id, life, floorF, src(0))) }
    }

    @Test fun M09_guardFloorRecapture_originFromBefore_boundAfter_thenAuth() {
        val e = ControlMutation.Edit.floor(node(ControlObligationFixtures.guard), BootReading("boot", 20_000), 50_000, life2)
        val b = build(e)
        val after = FloorV1("boot", 20_000, 50_000, life2) // max(30_000 − 10_000, 50_000) anchored at now
        both(b) { src -> listOf(
            fl(0, L, ControlKind.DEMAND, "g", life, after, src(0)), fl(0, N, ControlKind.DEMAND, "g", life, after, src(0)),
            u(0, L, authA, src(0)), u(0, N, authA, src(0))) }
    }

    @Test fun M10_guardFloorCreate_originFromAfterWhenNoBeforeFloor() {
        val e = ControlMutation.Edit.floor(node(ControlObligationFixtures.emptyGuard), BootReading("boot", 20_000), 5_000, life2)
        val b = build(e)
        val after = FloorV1("boot", 20_000, 5_000, life2)
        both(b) { src -> listOf(fl(0, L, ControlKind.DEMAND, "g", life2, after, src(0)), fl(0, N, ControlKind.DEMAND, "g", life2, after, src(0))) }
    }

    @Test fun M11_guardEditNoOp_floorThenAuth() {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(ControlObligationFixtures.guard)) {} as ControlMutation.Edit
        val b = build(e)
        both(b) { src -> listOf(
            fl(0, L, ControlKind.DEMAND, "g", life, floorF, src(0)), fl(0, N, ControlKind.DEMAND, "g", life, floorF, src(0)),
            u(0, L, authA, src(0)), u(0, N, authA, src(0))) }
    }

    @Test fun M11b_authOnlyGuardEditNoOp() {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(authOnlyGuard)) {} as ControlMutation.Edit
        val b = build(e)
        both(b) { src -> listOf(u(0, L, authA, src(0)), u(0, N, authA, src(0))) }
    }

    @Test fun M11c_emptyGuardEditNoOp() {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(ControlObligationFixtures.emptyGuard)) {} as ControlMutation.Edit
        both(build(e)) { emptyList() }
    }

    // ═══ HOLD ═══════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun M12_holdAddWithFloor_sourceThenFloor() {
        val a = add(ControlKind.HOLD, ControlObligationFixtures.hold)
        val b = build(a)
        val x = queryHold(idOf(a))
        both(b) { src -> listOf(h(0, L, x, src(0)), h(0, N, x, src(0)),
            fl(0, L, ControlKind.HOLD, x.id, life, floorF, src(0)), fl(0, N, ControlKind.HOLD, x.id, life, floorF, src(0))) }
    }

    @Test fun M13_holdAddWithoutFloor_sourceOnly() {
        val a = add(ControlKind.HOLD, ControlObligationFixtures.topicHold)
        val b = build(a)
        val x = topicHold(idOf(a))
        both(b) { src -> listOf(h(0, L, x, src(0)), h(0, N, x, src(0))) }
    }

    @Test fun M14_holdEditNoOp() {
        val n = node(ControlObligationFixtures.hold)
        val e = ControlMutation.Edit.prepare(ControlKind.HOLD, n) {} as ControlMutation.Edit
        val b = build(e)
        val x = queryHold("h")
        both(b) { src -> listOf(h(0, L, x, src(0)), h(0, N, x, src(0)),
            fl(0, L, ControlKind.HOLD, "h", life, floorF, src(0)), fl(0, N, ControlKind.HOLD, "h", life, floorF, src(0))) }
    }

    @Test fun M14b_floorlessHoldEditNoOp() {
        val e = ControlMutation.Edit.prepare(ControlKind.HOLD, node(ControlObligationFixtures.topicHold)) {} as ControlMutation.Edit
        val b = build(e)
        val x = topicHold("t")
        both(b) { src -> listOf(h(0, L, x, src(0)), h(0, N, x, src(0))) }
    }

    // ═══ RECOVERY_INTENT ════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun M15_intentAdd() {
        val a = add(ControlKind.RECOVERY_INTENT, ControlObligationFixtures.recovery)
        val b = build(a)
        val t = RecoveryIntentV1(idOf(a), "session", null, PurgeScope.CAPABILITY, null)
        both(b) { src -> listOf(intent(0, L, t, src(0)), intent(0, N, t, src(0))) }
    }

    @Test fun M16_intentEditNoOp() {
        val e = ControlMutation.Edit.prepare(ControlKind.RECOVERY_INTENT, node(ControlObligationFixtures.recovery)) {} as ControlMutation.Edit
        val b = build(e)
        val t = RecoveryIntentV1("r", "session", null, PurgeScope.CAPABILITY, null)
        both(b) { src -> listOf(intent(0, L, t, src(0)), intent(0, N, t, src(0))) }
    }

    // ═══ several actions ════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun M17_sameSubjectTwoActions_keptByIndex() {
        val n = node(ControlObligationFixtures.request)
        val e0 = ControlMutation.Edit.prepare(ControlKind.DEMAND, n) {} as ControlMutation.Edit
        val e1 = ControlMutation.Edit.prepare(ControlKind.DEMAND, n) {} as ControlMutation.Edit
        val b = build(e0, e1)
        val d = demand("d")
        both(b) { src -> listOf(q(0, L, d, d, d, src(0)), q(0, N, d, d, d, src(0)), q(1, L, d, d, d, src(1)), q(1, N, d, d, d, src(1))) }
    }

    @Test fun M18_indexOrderBeforeComponentOrder() {
        val a = add(ControlKind.HOLD, ControlObligationFixtures.hold)
        val e = ControlMutation.Edit.floor(node(ControlObligationFixtures.guard), BootReading("boot", 20_000), 50_000, life2)
        val b = build(a, e)
        val x = queryHold(idOf(a))
        val after = FloorV1("boot", 20_000, 50_000, life2)
        both(b) { src -> listOf(
            h(0, L, x, src(0)), h(0, N, x, src(0)),
            fl(0, L, ControlKind.HOLD, x.id, life, floorF, src(0)), fl(0, N, ControlKind.HOLD, x.id, life, floorF, src(0)),
            fl(1, L, ControlKind.DEMAND, "g", life, after, src(1)), fl(1, N, ControlKind.DEMAND, "g", life, after, src(1)),
            u(1, L, authA, src(1)), u(1, N, authA, src(1))) }
    }

    // ═══ Unavailable: command and body ══════════════════════════════════════════════════════════════════════════════════
    private val command = loc(FixedInputRoot.COMMAND, null, FixedInputFacet.WHOLE)

    @Test fun U01_terminatedRefBody_unavailable() {
        val b = build(add(ControlKind.DEMAND, ControlObligationFixtures.request))
        b.ref.beginTermination(); b.ref.completeTermination()
        unavailable(current(b), RequiredObligationsUnavailable.BODY_UNAVAILABLE, command)
        unavailable(previous(b), RequiredObligationsUnavailable.BODY_UNAVAILABLE, command)
    }

    @Test fun U02_bodyNotTheRefsBody_mismatch() {
        val b = build(add(ControlKind.DEMAND, ControlObligationFixtures.request))
        val copy = ControlCommandBody.Mutations(b.body.actions)
        unavailable(RequirementInput.Mutations(b.ref, copy, MutationAdoption.Current(b.targets)),
            RequiredObligationsUnavailable.BODY_MISMATCH, command)
        unavailable(RequirementInput.Mutations(b.ref, copy, MutationAdoption.Previous(checkpoint(b))),
            RequiredObligationsUnavailable.BODY_MISMATCH, command)
    }

    @Test fun U03_refOfAnotherBranch_mismatch() {
        val b = build(add(ControlKind.DEMAND, ControlObligationFixtures.request))
        val lifecycleRef = CommandRef("op-1", ControlCommandBody.Lifecycle(ControlLifecycleDescriptor("op-1",
            LifecycleTransition.REMOVE_EMPTY_GUARD, listOf())), OwnerTrackingLifetimeId.issue())
        unavailable(RequirementInput.Mutations(lifecycleRef, b.body, MutationAdoption.Current(b.targets)),
            RequiredObligationsUnavailable.BODY_MISMATCH, command)
    }

    // ═══ Unavailable: Current adoption ══════════════════════════════════════════════════════════════════════════════════
    private fun adoption(i: Int?) = loc(FixedInputRoot.MUTATION_ADOPTION, i, FixedInputFacet.WHOLE)

    @Test fun U04_currentVector() {
        val a0 = add(ControlKind.DEMAND, ControlObligationFixtures.request)
        val a1 = add(ControlKind.RECOVERY_INTENT, ControlObligationFixtures.recovery)
        val b = build(a0, a1)
        unavailable(current(b, null), RequiredObligationsUnavailable.ADOPTION_VECTOR_MISSING, adoption(null))
        unavailable(current(b, b.targets.take(1)), RequiredObligationsUnavailable.ADOPTION_VECTOR_SIZE_MISMATCH, adoption(null))
        unavailable(current(b, b.targets + b.targets[0]), RequiredObligationsUnavailable.ADOPTION_VECTOR_SIZE_MISMATCH, adoption(null))
        unavailable(current(b, listOf(b.targets[0], null)), RequiredObligationsUnavailable.ADOPTION_VECTOR_MISSING, adoption(1))
    }

    @Test fun U05_currentTargetInconsistentWithAction() {
        val req = add(ControlKind.DEMAND, ControlObligationFixtures.request)
        val inconsistent = RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT
        // joined on a non-SEAL Add
        build(req).let { b -> unavailable(current(b, listOf(ControlCommandTarget(idOf(req), req.after(), true))), inconsistent, adoption(0)) }
        // another id
        build(req).let { b -> unavailable(current(b, listOf(ControlCommandTarget("other", req.after(), false))), inconsistent, adoption(0)) }
        // same id and joined flag, different postcondition node
        val otherNode = node(ControlObligationFixtures.request.replace("\"raisedAt\":4", "\"raisedAt\":5").replace("\"id\":\"d\"", "\"id\":\"${idOf(req)}\""))
        build(req).let { b -> unavailable(current(b, listOf(ControlCommandTarget(idOf(req), otherNode, false))), inconsistent, adoption(0)) }
        // joined on an Edit
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(ControlObligationFixtures.request)) {} as ControlMutation.Edit
        build(e).let { b -> unavailable(current(b, listOf(ControlCommandTarget("d", e.after(), true))), inconsistent, adoption(0)) }
        // joined SEAL whose key differs from the prepared seal
        val seal = add(ControlKind.SEAL, ControlObligationFixtures.seal)
        val otherKey = node(ControlObligationFixtures.seal.replace("\"id\":\"s\"", "\"id\":\"s-existing\"").replace("\"old\"", "\"new\""))
        build(seal).let { b -> unavailable(current(b, listOf(ControlCommandTarget("s-existing", otherKey, true))), inconsistent, adoption(0)) }
        // joined SEAL of the other kind
        val nullKind = node(ControlObligationFixtures.nullSeal.replace("\"id\":\"s\"", "\"id\":\"s-existing\""))
        build(seal).let { b -> unavailable(current(b, listOf(ControlCommandTarget("s-existing", nullKind, true))), inconsistent, adoption(0)) }
    }

    /** Fixture guard: a node the deriver must be able to interpret, so a rejection cannot come from an unreadable node. */
    private fun interpretable(kind: ControlKind, json: String): ControlNode = node(json).also {
        assertTrue("fixture node must be interpretable: $json", ControlObligations.read(kind, it) is ControlEntryRead.Interpreted)
    }

    @Test fun U05b_joinedSealAdoptionRules() {
        val inconsistent = RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT
        val nullSealAdd = add(ControlKind.SEAL, ControlObligationFixtures.nullSeal) // NULL_NAMESPACE, key (A, USER, null)
        val existing = ControlObligationFixtures.nullSeal.replace("\"id\":\"s\"", "\"id\":\"s-existing\"")
        // control: the same-kind, same-key, unsettled existing seal is a valid join
        build(nullSealAdd) { _, _ -> ControlCommandTarget("s-existing", interpretable(ControlKind.SEAL, existing), true) }.let { b ->
            val z = SealV1("s-existing", SealTargetKind.NULL_NAMESPACE, SealKey("A", PurgeScope.USER, null), null)
            both(b) { src -> listOf(s(0, L, z, src(0)), s(0, N, z, src(0))) }
        }
        // the target id is not the adopted seal's id
        build(nullSealAdd).let { b -> unavailable(current(b, listOf(ControlCommandTarget("x", interpretable(ControlKind.SEAL, existing), true))),
            inconsistent, adoption(0)) }
        // (same key, other kind is not constructible: NAMESPACE requires an epoch and NULL_NAMESPACE forbids it, so the key
        // already separates the kinds — ControlSchema.kt:49, 140.)
        // same kind and key, but already settled: a join adopts only a still-unsettled seal
        val settledExisting = ControlObligationFixtures.settledSeal.replace("\"id\":\"s\"", "\"id\":\"s-existing\"")
        build(nullSealAdd).let { b -> unavailable(current(b, listOf(ControlCommandTarget("s-existing",
            interpretable(ControlKind.SEAL, settledExisting), true))), inconsistent, adoption(0)) }
        // a joined SEAL Edit (joined exists only for a SEAL Add)
        val e = ControlMutation.Edit.prepare(ControlKind.SEAL, node(ControlObligationFixtures.seal)) {} as ControlMutation.Edit
        build(e).let { b -> unavailable(current(b, listOf(ControlCommandTarget("s", e.after(), true))), inconsistent, adoption(0)) }
        // Previous: the same id rule at the checkpoint position
        build(nullSealAdd).let { b -> unavailable(previous(b, checkpoint(b, listOf(ControlCommandTarget("x",
            interpretable(ControlKind.SEAL, existing), true)))), RequiredObligationsUnavailable.CHECKPOINT_MISMATCH, cpAt(0)) }
    }

    @Test fun U09_emptyMutationsBody_isInconsistentNotEmptyObligations() {
        val ref = CommandRef("cmd-empty", listOf(), OwnerTrackingLifetimeId.issue())
        val body = ref.body as ControlCommandBody.Mutations
        unavailable(RequirementInput.Mutations(ref, body, MutationAdoption.Current(listOf())),
            RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, command)
        unavailable(RequirementInput.Mutations(ref, body, MutationAdoption.Previous(ControlCommandCheckpoint(ref, listOf(), true))),
            RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, command)
    }

    // ═══ Unavailable: Previous checkpoint ═══════════════════════════════════════════════════════════════════════════════
    private val cpWhole = loc(FixedInputRoot.PREVIOUS_CHECKPOINT, null, FixedInputFacet.WHOLE)
    private fun cpAt(i: Int) = loc(FixedInputRoot.PREVIOUS_CHECKPOINT, i, FixedInputFacet.AFTER)

    @Test fun U06_previousCheckpoint() {
        val a0 = add(ControlKind.DEMAND, ControlObligationFixtures.request)
        val a1 = add(ControlKind.RECOVERY_INTENT, ControlObligationFixtures.recovery)
        val b = build(a0, a1)
        val other = build(a0, a1)
        unavailable(previous(b, null), RequiredObligationsUnavailable.CHECKPOINT_MISSING, cpWhole)
        unavailable(previous(b, checkpoint(b, command = other.ref)), RequiredObligationsUnavailable.CHECKPOINT_MISMATCH, cpWhole)
        unavailable(previous(b, checkpoint(b, requested = false)), RequiredObligationsUnavailable.CHECKPOINT_INCOMPLETE, cpWhole)
        unavailable(previous(b, checkpoint(b, b.targets.take(1))), RequiredObligationsUnavailable.CHECKPOINT_INCOMPLETE, cpWhole)
        unavailable(previous(b, checkpoint(b, listOf(b.targets[0], null))), RequiredObligationsUnavailable.CHECKPOINT_INCOMPLETE, cpAt(1))
        unavailable(previous(b, checkpoint(b, listOf(b.targets[0], ControlCommandTarget("other", a1.after(), false)))),
            RequiredObligationsUnavailable.CHECKPOINT_MISMATCH, cpAt(1))
        unavailable(previous(b, checkpoint(b, listOf(ControlCommandTarget(idOf(a0), a0.after(), true), b.targets[1]))),
            RequiredObligationsUnavailable.CHECKPOINT_MISMATCH, cpAt(0))
        // same id and joined flag, different postcondition node
        val otherNode = node(ControlObligationFixtures.request.replace("\"raisedAt\":4", "\"raisedAt\":5").replace("\"id\":\"d\"", "\"id\":\"${idOf(a0)}\""))
        unavailable(previous(b, checkpoint(b, listOf(ControlCommandTarget(idOf(a0), otherNode, false), b.targets[1]))),
            RequiredObligationsUnavailable.CHECKPOINT_MISMATCH, cpAt(0))
    }

    @Test fun U06b_previousJoinedSealOfAnotherKey() {
        val seal = add(ControlKind.SEAL, ControlObligationFixtures.seal)
        val b = build(seal)
        val otherKey = node(ControlObligationFixtures.seal.replace("\"id\":\"s\"", "\"id\":\"s-existing\"").replace("\"old\"", "\"new\""))
        unavailable(previous(b, checkpoint(b, listOf(ControlCommandTarget("s-existing", otherKey, true)))),
            RequiredObligationsUnavailable.CHECKPOINT_MISMATCH, cpAt(0))
    }

    // ═══ Unavailable: prepared actions ══════════════════════════════════════════════════════════════════════════════════
    @Test fun U07_rejectedPreparedAction() {
        val authGuardAdd = add(ControlKind.DEMAND, ControlObligationFixtures.guard) // A10: generic Add cannot carry AUTH
        assertTrue(authGuardAdd.built is ControlWriteResult.Rejected)
        val weakened = ControlMutation.Edit.prepare(ControlKind.DEMAND,
            node(ControlObligationFixtures.request.replace("IF_STALE", "FORCE_PREMIUM"))) {
            set("intent", ControlScalar.Text("IF_STALE"))
        } as ControlMutation.Edit
        assertTrue(weakened.changed is ControlWriteResult.Rejected)
        val ok = add(ControlKind.RECOVERY_INTENT, ControlObligationFixtures.recovery)
        for (bad in listOf<ControlMutation>(authGuardAdd, weakened)) {
            val b = build(ok, bad) { i, m -> if (i == 0) ControlCommandTarget(idOf(m), afterOf(m), false) else ControlCommandTarget("x", node(ControlObligationFixtures.emptyGuard), false) }
            val at = loc(FixedInputRoot.MUTATION_ACTION, 1, FixedInputFacet.AFTER)
            unavailable(current(b), RequiredObligationsUnavailable.PREPARED_ACTION_UNINTERPRETABLE, at)
            unavailable(previous(b), RequiredObligationsUnavailable.PREPARED_ACTION_UNINTERPRETABLE, at)
        }
    }

    @Test fun U08_uninterpretableEditBefore_reportsBefore() {
        val opaque = node("""{"id":"d","kind":"REQUEST"}""")
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, opaque) {} as ControlMutation.Edit
        val b = build(e) { _, _ -> ControlCommandTarget("d", opaque, false) }
        val at = loc(FixedInputRoot.MUTATION_ACTION, 0, FixedInputFacet.BEFORE)
        unavailable(current(b), RequiredObligationsUnavailable.PREPARED_ACTION_UNINTERPRETABLE, at)
        unavailable(previous(b), RequiredObligationsUnavailable.PREPARED_ACTION_UNINTERPRETABLE, at)
    }

    // ═══ other branches are outside 6-4bA1a ═════════════════════════════════════════════════════════════════════════════
    @Test fun X01_otherBranchesUnsupportedInThisUnit() {
        val fence = FenceV1("A", "u", "k")
        val lifecycle = ControlCommandBody.Lifecycle(ControlLifecycleDescriptor("op-l", LifecycleTransition.REMOVE_EMPTY_GUARD, listOf()))
        val rotation = ControlCommandBody.RotateAndSettle(RotateAndSettleNamespaces(listOf(), fence, life,
            SettlementDemand("A", 3, EventOrderV1(life, 4), RefreshIntent.FORCE_PREMIUM), "op-r", "d", "u2", null))
        val handover = ControlCommandBody.SettleRetiredNull(RetiredNullSettlement(listOf(), fence, SettlementExecutor("A", 3, life), "op-h"))
        val inputs = listOf(
            RequirementInput.Lifecycle(CommandRef("op-l", lifecycle, OwnerTrackingLifetimeId.issue()), lifecycle),
            RequirementInput.Rotation(CommandRef("op-r", rotation, OwnerTrackingLifetimeId.issue()), rotation),
            RequirementInput.Settlement(CommandRef("op-h", handover, OwnerTrackingLifetimeId.issue()), handover))
        for (input in inputs) unavailable(input, RequiredObligationsUnavailable.UNSUPPORTED_IN_THIS_UNIT, command)
    }
}
