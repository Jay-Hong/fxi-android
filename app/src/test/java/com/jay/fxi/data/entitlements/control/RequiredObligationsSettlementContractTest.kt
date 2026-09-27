package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.StoreOp
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Claude-owned 6-4bA1b contract: the Rotation and Settlement (R / N / L) branches of the pure required-obligation derivation
 * (T2 rows for ROTATION and R/N/L). Fixed inputs: 6-4bA1b table consensus (table r2 + Unavailable r3 minus the two residual
 * premises). Expected lists are written from literals and constructors, independently of the deriver; each landing witness is
 * additionally cross-checked against the writer's own witness function, so a formula shared by deriver and contract cannot
 * silently diverge from the writer.
 *
 * Declarations this slice adds: ObligationSubject.NamedRequest(demandId: String?), FixedSourceFact.RotationInput(value),
 * FixedSourceFact.SettlementInput(value). Every key uses the command role (Rotation / Settlement(transition)).
 * Order: per source S, J (L then N each); per axis T after that axis' sources (Rotation: its only seal; N: NULL then companion);
 * then the command REQUEST pair (Required, or NotRequiredByContract for R with another owner and for every L).
 * Unavailable: body/ref → uninterpretable original target (raw index; N counts nullTargets then companions) → the writer's
 * invalidInput() → FIXED_INPUT_INCONSISTENT at the input WHOLE. DUPLICATE_REQUIRED_KEY stays a defensive row with no direct test:
 * the writers already reject duplicate target ids and axes (NamespaceSettlement.kt:66-67, CurrentNullSettlementTransition.kt:116-118,
 * RetiredNullSettlementTransition.kt:113-114), so a duplicate key is unreachable through a valid input.
 * The implementation thread reads but does not edit this file.
 */
class RequiredObligationsSettlementContractTest {
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val E = AllowedSlotDisposition.EITHER
    private val codec = ControlPayloadCodec()

    // ── fixed facts ────────────────────────────────────────────────────────────────────────────────────────────────────
    private val life = LifetimeId("life")
    private val op = "00000000-0000-0000-0000-000000000001"
    private val did = "00000000-0000-0000-0000-000000000002"
    private val newU = "00000000-0000-0000-0000-000000000003"
    private val newK = "00000000-0000-0000-0000-000000000004"
    private val demand = SettlementDemand("A", 3, EventOrderV1(life, 7), RefreshIntent.FORCE_PREMIUM)
    private val executor = SettlementExecutor("A", 3, life)

    private fun seal(id: String, kind: SealTargetKind, owner: String?, axis: PurgeScope, epoch: String?) =
        SealV1(id, kind, SealKey(owner, axis, epoch), null)
    private fun json(z: SealV1): String {
        fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"
        val epoch = if (z.kind == SealTargetKind.NAMESPACE) ",\"epoch\":${q(z.key.epoch)}" else ""
        return """{"id":"${z.id}","kind":"${z.kind.name}","ownerUid":${q(z.key.ownerUid)},"axis":"${z.key.axis.name}"$epoch}"""
    }
    private fun j(owner: String?, axis: PurgeScope, epoch: String?) = JournalTargetV1(owner, axis, epoch)

    // ── locations and facts ────────────────────────────────────────────────────────────────────────────────────────────
    private fun loc(root: FixedInputRoot, index: Int?, facet: FixedInputFacet) = FixedInputLocation(root, index, facet)
    private fun ev(root: FixedInputRoot, index: Int?, facet: FixedInputFacet, f: FixedSourceFact) = FixedSourceEvidence(loc(root, index, facet), f)
    private fun target(root: FixedInputRoot, i: Int, n: ControlNode) = ev(root, i, FixedInputFacet.SOURCE, FixedSourceFact.Node(ControlKind.SEAL, n))

    // ── expected slots ─────────────────────────────────────────────────────────────────────────────────────────────────
    private fun req(role: ObligationRole, subject: ObligationSubject, c: ObligationComponent, b: LandingBranch,
        bound: RequiredLowerBound, f: List<FixedSourceEvidence>) =
        RequiredSlot(RequiredObligationKey(role, subject, c, b), SlotRequirement.Required(bound, E, f))
    private fun sPair(role: ObligationRole, z: SealV1, w: SettlementEvidence, f: List<FixedSourceEvidence>) = listOf(
        req(role, ObligationSubject.Seal(z.id, z.kind, z.key), ObligationComponent.SEAL, L, RequiredLowerBound.Seal(z, w), f),
        req(role, ObligationSubject.Seal(z.id, z.kind, z.key), ObligationComponent.SEAL, N, RequiredLowerBound.Seal(z, null), f))
    private fun jPair(role: ObligationRole, z: SealV1, k: JournalTargetV1, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        req(role, ObligationSubject.Journal(z.id, k), ObligationComponent.JOURNAL, it, RequiredLowerBound.Journal(k), f) }
    private fun tPair(role: ObligationRole, scope: RetirementScope, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        req(role, scope, ObligationComponent.NAMESPACE_RETIREMENT, it, RequiredLowerBound.NamespaceRetirement(scope), f) }
    private fun qPair(role: ObligationRole, id: String, d: SettlementDemand, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        req(role, ObligationSubject.NamedRequest(id), ObligationComponent.REQUEST, it,
            RequiredLowerBound.Request(null, id, d.ownerUid, d.binding, d.intent, d.raisedAt), f) }
    private fun qNone(role: ObligationRole, f: List<FixedSourceEvidence>) = listOf(L, N).map {
        RequiredSlot(RequiredObligationKey(role, ObligationSubject.NamedRequest(null), ObligationComponent.REQUEST, it),
            SlotRequirement.NotRequiredByContract(f)) }

    // ── runners ────────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun check(input: RequirementInput, kind: FixedCommandKind, expected: List<RequiredSlot>) {
        val r = deriveRequiredObligations(input)
        if (r !is RequirementDerivation.Available) fail("expected Available, got $r")
        r as RequirementDerivation.Available
        assertSame(input.exactCommand, r.commandBinding.ref)
        assertSame(input.exactCommand.body, r.commandBinding.body)
        assertEquals(kind, r.commandBinding.kind)
        assertEquals(expected, r.orderedSlots)
        assertTrue("orderedSlots must be unmodifiable",
            runCatching { (r.orderedSlots as MutableList<RequiredSlot>).add(r.orderedSlots.first()) }.isFailure)
        coverageDetectsMissingJournal(r.orderedSlots)
    }

    /** Table r2: dropping any one J^L or J^N is MissingL / MissingN even though the physical journal key is shared. */
    private fun coverageDetectsMissingJournal(slots: List<RequiredSlot>) {
        val expected = slots.map { ExpectedSlot(it.key, it.necessity) }
        val required = slots.filter { it.necessity == SlotNecessity.Required }.map { SubmittedSlot(it.key, ObligationDisposition.DurablyOwned) }
        assertEquals(CoverageResult.Complete, checkSlotCoverage(expected, required))
        for ((branch, problem) in listOf(L to CoverageProblem.MissingL, N to CoverageProblem.MissingN)) {
            val journals = required.filter { it.key.component == ObligationComponent.JOURNAL && it.key.branch == branch }
            assertTrue("fixture has a journal $branch slot", journals.isNotEmpty())
            for (drop in journals) assertEquals(CoverageResult.Rejected(listOf(problem)), checkSlotCoverage(expected, required - drop))
        }
    }
    private fun unavailable(input: RequirementInput, reason: RequiredObligationsUnavailable, l: FixedInputLocation) =
        assertEquals(RequirementDerivation.Unavailable(reason, l), deriveRequiredObligations(input))

    private fun rotationInput(targets: List<ControlNode>, before: FenceV1 = FenceV1("A", "u", "k"), u: String? = newU, k: String? = newK) =
        RotateAndSettleNamespaces(targets, before, life, demand, op, did, u, k)
    private fun rotation(input: RotateAndSettleNamespaces): RequirementInput.Rotation {
        val body = ControlCommandBody.RotateAndSettle(input)
        return RequirementInput.Rotation(CommandRef(op, body, OwnerTrackingLifetimeId.issue()), body)
    }
    private fun settlement(body: ControlCommandBody.Handover) =
        RequirementInput.Settlement(CommandRef(op, body, OwnerTrackingLifetimeId.issue()), body)

    // ═══ ROTATION ═══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun R01_rotationTwoAxes_perSourceSealJournal_perAxisRetirement_thenRequest() {
        val before = FenceV1("A", "u", "k")
        val after = FenceV1("A", newU, newK)
        val su = seal("s", SealTargetKind.NAMESPACE, "A", PurgeScope.USER, "u")
        val sc = seal("c", SealTargetKind.NAMESPACE, "A", PurgeScope.CAPABILITY, "k")
        val nu = node(json(su)); val nc = node(json(sc))
        val input = rotationInput(listOf(nu, nc))
        assertNull("fixture must be a valid rotation", input.invalidInput())
        val role = ObligationRole.Rotation
        val ku = j("A", PurgeScope.USER, "u"); val kc = j("A", PurgeScope.CAPABILITY, "k")
        val wu = SettlementEvidenceV1(op, life, StoreOp.BEGIN_ROTATION, before, after, ku)
        val wc = SettlementEvidenceV1(op, life, StoreOp.BEGIN_ROTATION, before, after, kc)
        assertEquals("writer witness (NamespaceSettlement.witness)", wu, input.witness(su))
        assertEquals("writer witness (NamespaceSettlement.witness)", wc, input.witness(sc))
        val g = listOf(
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.WHOLE, FixedSourceFact.RotationInput(input)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(before)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.AFTER, FixedSourceFact.Fence(after)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.DEMAND, FixedSourceFact.Demand(demand)))
        val t0 = target(FixedInputRoot.ROTATION_TARGET, 0, nu); val t1 = target(FixedInputRoot.ROTATION_TARGET, 1, nc)
        check(rotation(input), FixedCommandKind.Rotation,
            sPair(role, su, wu, g + t0) + jPair(role, su, ku, g + t0) + tPair(role, RetirementScope("s", before, after, ku), g + t0) +
            sPair(role, sc, wc, g + t1) + jPair(role, sc, kc, g + t1) + tPair(role, RetirementScope("c", before, after, kc), g + t1) +
            qPair(role, did, demand, g + t0 + t1))
    }

    @Test fun R02_rotationSingleAxis() {
        val before = FenceV1("A", "u", "k")
        val after = FenceV1("A", "u", newK)
        val sc = seal("c", SealTargetKind.NAMESPACE, "A", PurgeScope.CAPABILITY, "k")
        val nc = node(json(sc))
        val input = rotationInput(listOf(nc), u = null)
        assertNull(input.invalidInput())
        val role = ObligationRole.Rotation
        val kc = j("A", PurgeScope.CAPABILITY, "k")
        val wc = SettlementEvidenceV1(op, life, StoreOp.BEGIN_ROTATION, before, after, kc)
        assertEquals(wc, input.witness(sc))
        val g = listOf(
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.WHOLE, FixedSourceFact.RotationInput(input)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(before)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.AFTER, FixedSourceFact.Fence(after)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.DEMAND, FixedSourceFact.Demand(demand)))
        val t0 = target(FixedInputRoot.ROTATION_TARGET, 0, nc)
        check(rotation(input), FixedCommandKind.Rotation,
            sPair(role, sc, wc, g + t0) + jPair(role, sc, kc, g + t0) + tPair(role, RetirementScope("c", before, after, kc), g + t0) +
            qPair(role, did, demand, g + t0))
    }

    @Test fun R03_rotationFollowsRawTargetOrder_capabilityFirst() {
        val before = FenceV1("A", "u", "k")
        val after = FenceV1("A", newU, newK)
        val su = seal("s", SealTargetKind.NAMESPACE, "A", PurgeScope.USER, "u")
        val sc = seal("c", SealTargetKind.NAMESPACE, "A", PurgeScope.CAPABILITY, "k")
        val nu = node(json(su)); val nc = node(json(sc))
        val input = rotationInput(listOf(nc, nu))
        assertNull(input.invalidInput())
        val role = ObligationRole.Rotation
        val ku = j("A", PurgeScope.USER, "u"); val kc = j("A", PurgeScope.CAPABILITY, "k")
        val wu = SettlementEvidenceV1(op, life, StoreOp.BEGIN_ROTATION, before, after, ku)
        val wc = SettlementEvidenceV1(op, life, StoreOp.BEGIN_ROTATION, before, after, kc)
        assertEquals(wu, input.witness(su)); assertEquals(wc, input.witness(sc))
        val g = listOf(
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.WHOLE, FixedSourceFact.RotationInput(input)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(before)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.AFTER, FixedSourceFact.Fence(after)),
            ev(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.DEMAND, FixedSourceFact.Demand(demand)))
        val t0 = target(FixedInputRoot.ROTATION_TARGET, 0, nc); val t1 = target(FixedInputRoot.ROTATION_TARGET, 1, nu)
        check(rotation(input), FixedCommandKind.Rotation,
            sPair(role, sc, wc, g + t0) + jPair(role, sc, kc, g + t0) + tPair(role, RetirementScope("c", before, after, kc), g + t0) +
            sPair(role, su, wu, g + t1) + jPair(role, su, ku, g + t1) + tPair(role, RetirementScope("s", before, after, ku), g + t1) +
            qPair(role, did, demand, g + t0 + t1))
    }

    // ═══ R: RETIRED_NAMESPACE ═══════════════════════════════════════════════════════════════════════════════════════════
    private val rRole = ObligationRole.Settlement(HandoverSettlementTransition.RETIRED_NAMESPACE)
    private val rBefore = FenceV1("A", "u2", "k")
    private fun rGroup(input: RetiredNamespaceSettlement, withDemand: Boolean) = listOfNotNull(
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE, FixedSourceFact.SettlementInput(input)),
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(rBefore)),
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.SOURCE, FixedSourceFact.Executor(executor)),
        if (withDemand) ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.DEMAND, FixedSourceFact.Demand(demand)) else null)

    @Test fun S01_retiredNamespace_sameOwner_requiresRequest() {
        val z = seal("s", SealTargetKind.NAMESPACE, "A", PurgeScope.USER, "u")
        val n = node(json(z))
        val input = RetiredNamespaceSettlement(n, rBefore, executor, op, did, demand)
        val writer = RetiredNamespaceSettlementTransition(codec)
        assertNull(writer.invalidInput(input))
        val k = j("A", PurgeScope.USER, "u")
        val w = SettlementEvidenceV1(op, life, StoreOp.JOURNAL_RETIRED, rBefore, rBefore, k)
        assertEquals("writer witness (RetiredNamespaceSettlementTransition.witness)", w, writer.witness(input))
        val g = rGroup(input, true)
        val t0 = target(FixedInputRoot.SETTLEMENT_TARGET, 0, n)
        check(settlement(ControlCommandBody.SettleRetiredNamespace(input)), FixedCommandKind.Settlement(HandoverSettlementTransition.RETIRED_NAMESPACE),
            sPair(rRole, z, w, g + t0) + jPair(rRole, z, k, g + t0) + qPair(rRole, did, demand, g + t0))
    }

    @Test fun S02_retiredNamespace_otherOwner_requestNotRequiredBothBranches() {
        val z = seal("s", SealTargetKind.NAMESPACE, "B", PurgeScope.USER, "x")
        val n = node(json(z))
        val input = RetiredNamespaceSettlement(n, rBefore, executor, op, null, null)
        val writer = RetiredNamespaceSettlementTransition(codec)
        assertNull(writer.invalidInput(input))
        val k = j("B", PurgeScope.USER, "x")
        val w = SettlementEvidenceV1(op, life, StoreOp.JOURNAL_RETIRED, rBefore, rBefore, k)
        assertEquals(w, writer.witness(input))
        val g = rGroup(input, false)
        val t0 = target(FixedInputRoot.SETTLEMENT_TARGET, 0, n)
        check(settlement(ControlCommandBody.SettleRetiredNamespace(input)), FixedCommandKind.Settlement(HandoverSettlementTransition.RETIRED_NAMESPACE),
            sPair(rRole, z, w, g + t0) + jPair(rRole, z, k, g + t0) + qNone(rRole, g + t0))
    }

    @Test fun S06_retiredNamespace_nullOwnerCapability_requestRequired() {
        val before = FenceV1(null, "u", "k")
        val exec = SettlementExecutor(null, 3, life)
        val d = SettlementDemand(null, 3, EventOrderV1(life, 7), RefreshIntent.FORCE_ENTITLEMENTS)
        val z = seal("s", SealTargetKind.NAMESPACE, null, PurgeScope.CAPABILITY, "k0")
        val n = node(json(z))
        val input = RetiredNamespaceSettlement(n, before, exec, op, did, d)
        val writer = RetiredNamespaceSettlementTransition(codec)
        assertNull(writer.invalidInput(input))
        val k = j(null, PurgeScope.CAPABILITY, "k0")
        val w = SettlementEvidenceV1(op, life, StoreOp.JOURNAL_RETIRED, before, before, k)
        assertEquals(w, writer.witness(input))
        val g = listOf(
            ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE, FixedSourceFact.SettlementInput(input)),
            ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(before)),
            ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.SOURCE, FixedSourceFact.Executor(exec)),
            ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.DEMAND, FixedSourceFact.Demand(d)))
        val t0 = target(FixedInputRoot.SETTLEMENT_TARGET, 0, n)
        check(settlement(ControlCommandBody.SettleRetiredNamespace(input)), FixedCommandKind.Settlement(HandoverSettlementTransition.RETIRED_NAMESPACE),
            sPair(rRole, z, w, g + t0) + jPair(rRole, z, k, g + t0) + qPair(rRole, did, d, g + t0))
    }

    // ═══ N: CURRENT_NULL ════════════════════════════════════════════════════════════════════════════════════════════════
    private val nRole = ObligationRole.Settlement(HandoverSettlementTransition.CURRENT_NULL)
    private val nLife = LifetimeId("n-origin")
    private val nBefore = FenceV1("A", "u2", "k2")
    private val nExecutor = SettlementExecutor("A", 9, nLife)
    private val nDemand = SettlementDemand("A", 9, EventOrderV1(nLife, 23), RefreshIntent.FORCE_PREMIUM)
    private val uuidU = "11111111-1111-4111-8111-111111111111"
    private val uuidK = "22222222-2222-4222-8222-222222222222"
    private fun nGroup(input: CurrentNullSettlement, after: FenceV1) = listOf(
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE, FixedSourceFact.SettlementInput(input)),
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(nBefore)),
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.AFTER, FixedSourceFact.Fence(after)),
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.SOURCE, FixedSourceFact.Executor(nExecutor)),
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.DEMAND, FixedSourceFact.Demand(nDemand)))

    @Test fun S03_currentNull_nullAndCompanionShareTheJournalKey_twelveSlots() {
        val nu = seal("s", SealTargetKind.NULL_NAMESPACE, "A", PurgeScope.USER, null)
        val cu = seal("us", SealTargetKind.NAMESPACE, "A", PurgeScope.USER, "u2")
        val nnu = node(json(nu)); val ncu = node(json(cu))
        val input = CurrentNullSettlement(listOf(nnu), listOf(ncu), nBefore, nExecutor, op, did, nDemand, uuidU, null)
        val writer = CurrentNullSettlementTransition(codec)
        assertNull(writer.invalidInput(input))
        val after = FenceV1("A", uuidU, "k2")
        val k = j("A", PurgeScope.USER, null) // shared: the companion's old non-null epoch journal is not required
        val w = SettlementEvidenceV1(op, nLife, StoreOp.BEGIN_ROTATION, nBefore, after, k)
        assertEquals("writer witness (CurrentNullSettlementTransition.witness)", w, writer.witness(input, nu))
        assertEquals(w, writer.witness(input, cu))
        val g = nGroup(input, after)
        val t0 = target(FixedInputRoot.SETTLEMENT_TARGET, 0, nnu); val t1 = target(FixedInputRoot.SETTLEMENT_TARGET, 1, ncu)
        val expected = sPair(nRole, nu, w, g + t0) + jPair(nRole, nu, k, g + t0) +
            sPair(nRole, cu, w, g + t1) + jPair(nRole, cu, k, g + t1) +
            tPair(nRole, RetirementScope("s", nBefore, after, k), g + t0 + t1) +
            qPair(nRole, did, nDemand, g + t0 + t1)
        assertEquals(12, expected.size)
        check(settlement(ControlCommandBody.RotateAndSettleCurrentNull(input)), FixedCommandKind.Settlement(HandoverSettlementTransition.CURRENT_NULL), expected)
    }

    @Test fun S04_currentNull_twoAxes_sortedTargetOrder_notRawOrder() {
        val nu = seal("s", SealTargetKind.NULL_NAMESPACE, "A", PurgeScope.USER, null)
        val nc = seal("c", SealTargetKind.NULL_NAMESPACE, "A", PurgeScope.CAPABILITY, null)
        val cu = seal("us", SealTargetKind.NAMESPACE, "A", PurgeScope.USER, "u2")
        val cc = seal("ks", SealTargetKind.NAMESPACE, "A", PurgeScope.CAPABILITY, "k2")
        val nnu = node(json(nu)); val nnc = node(json(nc)); val ncu = node(json(cu)); val ncc = node(json(cc))
        // raw order is CAPABILITY first; the sorted target order is USER NULL, USER companion, CAPABILITY NULL, CAPABILITY companion
        val input = CurrentNullSettlement(listOf(nnc, nnu), listOf(ncc, ncu), nBefore, nExecutor, op, did, nDemand, uuidU, uuidK)
        val writer = CurrentNullSettlementTransition(codec)
        assertNull(writer.invalidInput(input))
        val after = FenceV1("A", uuidU, uuidK)
        val ku = j("A", PurgeScope.USER, null); val kc = j("A", PurgeScope.CAPABILITY, null)
        val wu = SettlementEvidenceV1(op, nLife, StoreOp.BEGIN_ROTATION, nBefore, after, ku)
        val wc = SettlementEvidenceV1(op, nLife, StoreOp.BEGIN_ROTATION, nBefore, after, kc)
        for ((z, w) in listOf(nu to wu, cu to wu, nc to wc, cc to wc)) assertEquals(w, writer.witness(input, z))
        val g = nGroup(input, after)
        val t = listOf(nnu, ncu, nnc, ncc).mapIndexed { i, n -> target(FixedInputRoot.SETTLEMENT_TARGET, i, n) }
        check(settlement(ControlCommandBody.RotateAndSettleCurrentNull(input)), FixedCommandKind.Settlement(HandoverSettlementTransition.CURRENT_NULL),
            sPair(nRole, nu, wu, g + t[0]) + jPair(nRole, nu, ku, g + t[0]) +
            sPair(nRole, cu, wu, g + t[1]) + jPair(nRole, cu, ku, g + t[1]) +
            tPair(nRole, RetirementScope("s", nBefore, after, ku), g + t[0] + t[1]) +
            sPair(nRole, nc, wc, g + t[2]) + jPair(nRole, nc, kc, g + t[2]) +
            sPair(nRole, cc, wc, g + t[3]) + jPair(nRole, cc, kc, g + t[3]) +
            tPair(nRole, RetirementScope("c", nBefore, after, kc), g + t[2] + t[3]) +
            qPair(nRole, did, nDemand, g + t))
    }

    @Test fun S07_currentNull_singleNullNoCompanion_eightSlots() {
        val nu = seal("s", SealTargetKind.NULL_NAMESPACE, "A", PurgeScope.USER, null)
        val nnu = node(json(nu))
        val input = CurrentNullSettlement(listOf(nnu), listOf(), nBefore, nExecutor, op, did, nDemand, uuidU, null)
        val writer = CurrentNullSettlementTransition(codec)
        assertNull(writer.invalidInput(input))
        val after = FenceV1("A", uuidU, "k2")
        val k = j("A", PurgeScope.USER, null)
        val w = SettlementEvidenceV1(op, nLife, StoreOp.BEGIN_ROTATION, nBefore, after, k)
        assertEquals(w, writer.witness(input, nu))
        val g = nGroup(input, after)
        val t0 = target(FixedInputRoot.SETTLEMENT_TARGET, 0, nnu)
        val expected = sPair(nRole, nu, w, g + t0) + jPair(nRole, nu, k, g + t0) +
            tPair(nRole, RetirementScope("s", nBefore, after, k), g + t0) + qPair(nRole, did, nDemand, g + t0)
        assertEquals(8, expected.size)
        check(settlement(ControlCommandBody.RotateAndSettleCurrentNull(input)), FixedCommandKind.Settlement(HandoverSettlementTransition.CURRENT_NULL), expected)
    }

    // ═══ L: RETIRED_NULL ════════════════════════════════════════════════════════════════════════════════════════════════
    private val lRole = ObligationRole.Settlement(HandoverSettlementTransition.RETIRED_NULL)
    private val lBefore = FenceV1("A", "u", "k")
    private fun lGroup(input: RetiredNullSettlement) = listOf(
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE, FixedSourceFact.SettlementInput(input)),
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.BEFORE, FixedSourceFact.Fence(lBefore)),
        ev(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.SOURCE, FixedSourceFact.Executor(executor)))

    @Test fun S05_retiredNull_twoAxes_orderedUserFirst_requestNotRequired_noRetirement() {
        val su = seal("s", SealTargetKind.NULL_NAMESPACE, "B", PurgeScope.USER, null)
        val sc = seal("c", SealTargetKind.NULL_NAMESPACE, "B", PurgeScope.CAPABILITY, null)
        val nsu = node(json(su)); val nsc = node(json(sc))
        val input = RetiredNullSettlement(listOf(nsc, nsu), lBefore, executor, op) // raw CAPABILITY first
        val writer = RetiredNullSettlementTransition(codec)
        assertNull(writer.invalidInput(input))
        val ku = j("B", PurgeScope.USER, null); val kc = j("B", PurgeScope.CAPABILITY, null)
        val wu = RetiredNullSettlementEvidenceV2(op, life, lBefore, lBefore, ku)
        val wc = RetiredNullSettlementEvidenceV2(op, life, lBefore, lBefore, kc)
        assertEquals("writer witness (RetiredNullSettlementTransition.witness)", wu, writer.witness(input, su))
        assertEquals(wc, writer.witness(input, sc))
        val g = lGroup(input)
        val t0 = target(FixedInputRoot.SETTLEMENT_TARGET, 0, nsu); val t1 = target(FixedInputRoot.SETTLEMENT_TARGET, 1, nsc)
        check(settlement(ControlCommandBody.SettleRetiredNull(input)), FixedCommandKind.Settlement(HandoverSettlementTransition.RETIRED_NULL),
            sPair(lRole, su, wu, g + t0) + jPair(lRole, su, ku, g + t0) +
            sPair(lRole, sc, wc, g + t1) + jPair(lRole, sc, kc, g + t1) +
            qNone(lRole, g + t0 + t1))
    }

    @Test fun S08_retiredNull_singleTarget_sixSlots() {
        val su = seal("s", SealTargetKind.NULL_NAMESPACE, "B", PurgeScope.USER, null)
        val nsu = node(json(su))
        val input = RetiredNullSettlement(listOf(nsu), lBefore, executor, op)
        val writer = RetiredNullSettlementTransition(codec)
        assertNull(writer.invalidInput(input))
        val k = j("B", PurgeScope.USER, null)
        val w = RetiredNullSettlementEvidenceV2(op, life, lBefore, lBefore, k)
        assertEquals(w, writer.witness(input, su))
        val g = lGroup(input)
        val t0 = target(FixedInputRoot.SETTLEMENT_TARGET, 0, nsu)
        val expected = sPair(lRole, su, w, g + t0) + jPair(lRole, su, k, g + t0) + qNone(lRole, g + t0)
        assertEquals(6, expected.size)
        check(settlement(ControlCommandBody.SettleRetiredNull(input)), FixedCommandKind.Settlement(HandoverSettlementTransition.RETIRED_NULL), expected)
    }

    // ═══ Unavailable ════════════════════════════════════════════════════════════════════════════════════════════════════
    private val command = loc(FixedInputRoot.COMMAND, null, FixedInputFacet.WHOLE)
    private val validRotation get() = rotationInput(listOf(node(json(seal("s", SealTargetKind.NAMESPACE, "A", PurgeScope.USER, "u")))), u = newU, k = null)
    private val validR get() = RetiredNamespaceSettlement(node(json(seal("s", SealTargetKind.NAMESPACE, "B", PurgeScope.USER, "x"))),
        rBefore, executor, op, null, null)
    private val validN get() = CurrentNullSettlement(listOf(node(json(seal("s", SealTargetKind.NULL_NAMESPACE, "A", PurgeScope.USER, null)))),
        listOf(), nBefore, nExecutor, op, did, nDemand, uuidU, null)
    private val validL get() = RetiredNullSettlement(listOf(node(json(seal("s", SealTargetKind.NULL_NAMESPACE, "B", PurgeScope.USER, null)))),
        lBefore, executor, op)

    @Test fun U01_terminatedRef_bodyUnavailable() {
        val inputs = listOf(rotation(validRotation), settlement(ControlCommandBody.SettleRetiredNamespace(validR)),
            settlement(ControlCommandBody.RotateAndSettleCurrentNull(validN)), settlement(ControlCommandBody.SettleRetiredNull(validL)))
        for (input in inputs) {
            input.exactCommand.beginTermination(); input.exactCommand.completeTermination()
            unavailable(input, RequiredObligationsUnavailable.BODY_UNAVAILABLE, command)
        }
    }

    @Test fun U02_bodyNotTheRefsBody_mismatch() {
        val r = rotation(validRotation)
        unavailable(RequirementInput.Rotation(r.exactCommand, ControlCommandBody.RotateAndSettle(r.body.input)),
            RequiredObligationsUnavailable.BODY_MISMATCH, command)
        val s = settlement(ControlCommandBody.SettleRetiredNull(validL))
        unavailable(RequirementInput.Settlement(s.exactCommand, ControlCommandBody.SettleRetiredNull(validL)),
            RequiredObligationsUnavailable.BODY_MISMATCH, command)
        // a Settlement input whose ref carries another handover kind
        unavailable(RequirementInput.Settlement(s.exactCommand, ControlCommandBody.SettleRetiredNamespace(validR)),
            RequiredObligationsUnavailable.BODY_MISMATCH, command)
    }

    @Test fun U03_uninterpretableOriginalTarget_atRawIndex() {
        val bad = node("""{"id":"x","kind":"NAMESPACE"}""")
        val good = node(json(seal("s", SealTargetKind.NAMESPACE, "A", PurgeScope.USER, "u")))
        unavailable(rotation(rotationInput(listOf(good, bad), u = newU, k = null)),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, loc(FixedInputRoot.ROTATION_TARGET, 1, FixedInputFacet.SOURCE))
        unavailable(settlement(ControlCommandBody.SettleRetiredNamespace(RetiredNamespaceSettlement(bad, rBefore, executor, op, null, null))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, loc(FixedInputRoot.SETTLEMENT_TARGET, 0, FixedInputFacet.SOURCE))
        val nullU = node(json(seal("s", SealTargetKind.NULL_NAMESPACE, "A", PurgeScope.USER, null)))
        unavailable(settlement(ControlCommandBody.RotateAndSettleCurrentNull(CurrentNullSettlement(listOf(nullU), listOf(bad),
            nBefore, nExecutor, op, did, nDemand, uuidU, null))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, loc(FixedInputRoot.SETTLEMENT_TARGET, 1, FixedInputFacet.SOURCE))
        val retiredB = node(json(seal("s", SealTargetKind.NULL_NAMESPACE, "B", PurgeScope.USER, null)))
        unavailable(settlement(ControlCommandBody.SettleRetiredNull(RetiredNullSettlement(listOf(retiredB, bad), lBefore, executor, op))),
            RequiredObligationsUnavailable.NAMED_SOURCE_UNINTERPRETABLE, loc(FixedInputRoot.SETTLEMENT_TARGET, 1, FixedInputFacet.SOURCE))
    }

    @Test fun U04_writerInvalidInput_isInconsistentAtInputWhole() {
        val rotationWhole = loc(FixedInputRoot.ROTATION_INPUT, null, FixedInputFacet.WHOLE)
        val settlementWhole = loc(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE)
        val inconsistent = RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT
        // Rotation: the seal epoch is not the fixed before epoch (TargetFenceMismatch)
        val stale = rotationInput(listOf(node(json(seal("s", SealTargetKind.NAMESPACE, "A", PurgeScope.USER, "old")))), u = newU, k = null)
        assertEquals("TargetFenceMismatch", stale.invalidInput())
        unavailable(rotation(stale), inconsistent, rotationWhole)
        // R: the executor is not the fixed owner (ExecutorFenceMismatch)
        val r = RetiredNamespaceSettlement(node(json(seal("s", SealTargetKind.NAMESPACE, "B", PurgeScope.USER, "x"))),
            rBefore, SettlementExecutor("Z", 3, life), op, null, null)
        assertEquals("ExecutorFenceMismatch", RetiredNamespaceSettlementTransition(codec).invalidInput(r))
        unavailable(settlement(ControlCommandBody.SettleRetiredNamespace(r)), inconsistent, settlementWhole)
        // N: a companion on an axis without a NULL target (CompanionAxisMismatch)
        val n = CurrentNullSettlement(listOf(node(json(seal("s", SealTargetKind.NULL_NAMESPACE, "A", PurgeScope.USER, null)))),
            listOf(node(json(seal("ks", SealTargetKind.NAMESPACE, "A", PurgeScope.CAPABILITY, "k2")))), nBefore, nExecutor, op, did, nDemand, uuidU, null)
        assertEquals("CompanionAxisMismatch", CurrentNullSettlementTransition(codec).invalidInput(n))
        unavailable(settlement(ControlCommandBody.RotateAndSettleCurrentNull(n)), inconsistent, settlementWhole)
        // L: the subject owner is the current owner (SubjectOwnerIsCurrent)
        val l = RetiredNullSettlement(listOf(node(json(seal("s", SealTargetKind.NULL_NAMESPACE, "A", PurgeScope.USER, null)))),
            lBefore, executor, op)
        assertEquals("SubjectOwnerIsCurrent", RetiredNullSettlementTransition(codec).invalidInput(l))
        unavailable(settlement(ControlCommandBody.SettleRetiredNull(l)), inconsistent, settlementWhole)
    }

    @Test fun X02_lifecycleWithoutPlan_isPlanMissing() {
        val body = ControlCommandBody.Lifecycle(ControlLifecycleDescriptor("op-l", LifecycleTransition.REMOVE_EMPTY_GUARD, listOf()))
        unavailable(RequirementInput.Lifecycle(CommandRef("op-l", body, OwnerTrackingLifetimeId.issue()), body),
            RequiredObligationsUnavailable.PLAN_MISSING, loc(FixedInputRoot.LIFECYCLE_PLAN, null, FixedInputFacet.WHOLE))
    }
}
