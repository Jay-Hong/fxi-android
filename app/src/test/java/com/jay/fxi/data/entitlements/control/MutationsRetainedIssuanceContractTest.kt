package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.RecordTransactionResult
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.hold
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.recovery
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.request
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-4bC1b-0 contract (6-4bC1b consensus r1, C1b-D2/D3): retained confirmations for Mutations slots issued from the
 * stored row itself, so a landed Add or a changed Edit can be handed over.
 *  - Only the same action index's fixed inputs are used. Add L and N take AFTER as their source and the fixed adoption row as the
 *    destination (a joined SEAL Add uses the adopted id, not the proposed one). Edit L takes AFTER. Edit N keeps BEFORE as its
 *    source but the current row must meet the bound derived from AFTER: an unchanged no-op row qualifies, a changed result row
 *    qualifies for both L and N, a weaker BEFORE row alone does not.
 *  - Issued only from a unique, interpretable current row of the observed record; a missing destination is never directly owned.
 *  - A token issued this way is accepted by G05 when every required slot is handed over with it.
 * The record is set directly to the landed (AFTER) or unlanded (BEFORE) state; adoptions are built from the actions as the A1
 * Mutations contract does. No command is executed.
 * The implementation thread reads but does not edit this file.
 */
class MutationsRetainedIssuanceContractTest : ReleaseOwnerTestBase() {
    private var seed = 0L
    private fun uuid() = UUID(0L, 0x100 + ++seed)
    private fun add(kind: ControlKind, json: String) = ControlMutation.Add.prepare(kind, uuid()) { id -> literal(json); set("id", ControlScalar.Text(id)) }
    private fun afterOf(m: ControlMutation) = when (m) {
        is ControlMutation.Add -> (m.built as ControlWriteResult.Written).node
        is ControlMutation.Edit -> (m.changed as ControlWriteResult.Written).node
    }
    private fun idOf(m: ControlMutation): String = (m as? ControlMutation.Add)?.proposedId
        ?: ((ControlObligations.read(m.kind, afterOf(m)) as ControlEntryRead.Interpreted).value as ControlObligationV1).id

    private class Built(val ref: CommandRef, val input: RequirementInput.Mutations, val a: RequirementDerivation.Available) {
        val required = a.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        fun slots(index: Int, component: ObligationComponent) = required.filter {
            (it.key.role as ObligationRole.MutationAction).index == index && it.key.component == component }
    }
    private fun build(vararg actions: ControlMutation, target: (Int, ControlMutation) -> ControlCommandTarget? = { _, m ->
        ControlCommandTarget(idOf(m), afterOf(m), false) }): Built {
        val ref = CommandRef("cmd-${uuid()}", actions.toList(), OwnerTrackingLifetimeId.issue())
        val input = RequirementInput.Mutations(ref, ref.body as ControlCommandBody.Mutations, MutationAdoption.Current(actions.mapIndexed(target)))
        return Built(ref, input, deriveRequiredObligations(input) as RequirementDerivation.Available)
    }
    private suspend fun locked(): RecordTransactionResult<ControlRecordRead.Supported> = controlTestTimeout("locked read") {
        o.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
    }
    private fun issued(r: RetainedSourceConfirmationResult): PriorStorageConfirmation {
        assertTrue("expected Issued, got $r", r is RetainedSourceConfirmationResult.Issued)
        return (r as RetainedSourceConfirmationResult.Issued).value
    }
    private fun rejected(r: RetainedSourceConfirmationResult, reason: RetainedConfirmationFailure) =
        assertEquals(RetainedSourceConfirmationResult.Rejected(reason), r)
    private fun observedRow(t: PriorStorageConfirmation) =
        ((t.binding as ConfirmationBinding.RetainedSource).observed as RetainedDestinationTuple.Payload).row
    private fun text(vararg nodes: ControlNode) = (ControlPayloadCodec().encode(nodes.map { it.toPayloadEntry() }) as PayloadWrite.Encoded).text
    private val now = BootReading("boot", 20_000)
    /** G05 over every required slot handed over DurablyOwned with its given token. */
    private fun g05(b: Built, tokens: Map<RequiredObligationKey, Pair<DestinationLocator, PriorStorageConfirmation>>, latest: ControlRecordRead) =
        assessG05(b.ref, b.a, CompletionHandoff(b.a.commandBinding, ResponsibilityOwner(b.ref.ownerTrackingLifetimeId, "owner-1"),
            b.required.map { val (d, t) = tokens.getValue(it.key); SlotHandoff(it.key, HandoffDisposition.DurablyOwned(d, emptyList(), t)) }),
            TerminationClosures.of(b.ref), latest, now)

    // ═══ REQUEST ═══════════════════════════════════════════════════════════════════════════════════════════════════════
    /** A1: a landed REQUEST Add: both slots issued on the added row; G05 accepts the handoff. */
    @Test fun A1_landedRequestAdd_bothSlotsIssuedOnTheAddedRow_g05Accepts() = runReleaseTest {
        val a = add(ControlKind.DEMAND, request); val b = build(a)
        o.seed(demand = text(afterOf(a)))
        val slots = b.slots(0, ObligationComponent.REQUEST)
        assertEquals("fixture: REQUEST L and N", 2, slots.size)
        assertEquals("fixture: only REQUEST slots", slots, b.required)
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.DEMAND, a.proposedId)
        val tokens = slots.associate { s -> s.key to (d to issued(PriorStorageConfirmation.confirmRetainedSource(s, d, read))) }
        for ((_, t) in tokens.values) assertEquals(afterOf(a).toPayloadEntry(), observedRow(t).toPayloadEntry())
        assertEquals(G05Result.Accepted, g05(b, tokens, read.value))
    }

    /** A2: a landed changed REQUEST Edit: the changed row serves both L and N. */
    @Test fun A2_landedChangedRequestEdit_changedRowServesLAndN() = runReleaseTest {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) }
        val b = build(e); o.seed(demand = text(afterOf(e)))
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val tokens = b.slots(0, ObligationComponent.REQUEST).associate { s -> s.key to (d to issued(PriorStorageConfirmation.confirmRetainedSource(s, d, read))) }
        assertEquals("fixture: L and N", 2, tokens.size)
        assertEquals(G05Result.Accepted, g05(b, tokens, read.value))
    }

    /** A3: a no-op REQUEST Edit keeps the existing path: the unchanged row serves both slots. */
    @Test fun A3_noopRequestEdit_unchangedRowServesBoth() = runReleaseTest {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(request)) {}
        val b = build(e); o.seed(demand = "[$request]")
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        for (s in b.slots(0, ObligationComponent.REQUEST)) issued(PriorStorageConfirmation.confirmRetainedSource(s, d, read))
    }

    /** N1: the changed Edit did not land (only the weaker BEFORE row): neither L nor N is issued. */
    @Test fun N1_unlandedChangedRequestEdit_weakerBeforeRow_neitherIssued() = runReleaseTest {
        // raisedAt 4 → 5: the N bound derived from AFTER requires order 5; the BEFORE row still has 4.
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) }
        val b = build(e); o.seed(demand = "[$request]")
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.DEMAND, "d")
        val slots = b.slots(0, ObligationComponent.REQUEST)
        assertEquals("fixture: L and N", 2, slots.size)
        for (s in slots) rejected(PriorStorageConfirmation.confirmRetainedSource(s, d, read), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    /** N2: the Add did not land (destination absent): never directly owned. */
    @Test fun N2_unlandedRequestAdd_destinationAbsent_notIssued() = runReleaseTest {
        val a = add(ControlKind.DEMAND, request); val b = build(a)
        o.seed()
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.DEMAND, a.proposedId)
        for (s in b.slots(0, ObligationComponent.REQUEST))
            rejected(PriorStorageConfirmation.confirmRetainedSource(s, d, read), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    /** N3: two REQUEST Adds; action 0's slots with action 1's row as destination: the other index is not this slot's. */
    @Test fun N3_otherActionIndexDestination_subjectOrBoundMismatch() = runReleaseTest {
        val a0 = add(ControlKind.DEMAND, request); val a1 = add(ControlKind.DEMAND, request); val b = build(a0, a1)
        o.seed(demand = text(afterOf(a0), afterOf(a1)))
        val read = locked(); val wrong = DestinationLocator.Payload(ControlKind.DEMAND, a1.proposedId)
        for (s in b.slots(0, ObligationComponent.REQUEST))
            rejected(PriorStorageConfirmation.confirmRetainedSource(s, wrong, read), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    // ═══ SEAL ══════════════════════════════════════════════════════════════════════════════════════════════════════════
    private val newSeal = """{"id":"x","kind":"NAMESPACE","ownerUid":"A","axis":"USER","epoch":"e1"}"""

    /** A4: a landed SEAL Add: both SEAL slots issued on the added seal. */
    @Test fun A4_landedSealAdd_bothSlotsIssued() = runReleaseTest {
        val a = add(ControlKind.SEAL, newSeal); val b = build(a)
        o.seed(seal = text(afterOf(a)))
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.SEAL, a.proposedId)
        val slots = b.slots(0, ObligationComponent.SEAL)
        assertEquals("fixture: SEAL L and N", 2, slots.size)
        for (s in slots) assertEquals(afterOf(a).toPayloadEntry(), observedRow(issued(PriorStorageConfirmation.confirmRetainedSource(s, d, read))).toPayloadEntry())
    }

    /** A5/N4: a joined SEAL Add adopted the existing seal "s" of the same key: issued on "s", never on the proposed id. */
    @Test fun A5_joinedSealAdd_issuedOnTheAdoptedId_notTheProposedOne() = runReleaseTest {
        val existing = newSeal.replace("\"id\":\"x\"", "\"id\":\"s\"")
        val a = add(ControlKind.SEAL, newSeal)
        val b = build(a) { _, _ -> ControlCommandTarget("s", node(existing), true) }
        o.seed(seal = "[$existing]")
        val read = locked()
        val slots = b.slots(0, ObligationComponent.SEAL)
        assertEquals("fixture: SEAL L and N", 2, slots.size)
        for (s in slots) {
            issued(PriorStorageConfirmation.confirmRetainedSource(s, DestinationLocator.Payload(ControlKind.SEAL, "s"), read))
            rejected(PriorStorageConfirmation.confirmRetainedSource(s, DestinationLocator.Payload(ControlKind.SEAL, a.proposedId), read),
                RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        }
    }

    /** N5: the added seal's row is present but opaque (unreadable key): not issued. */
    @Test fun N5_addedSealRowOpaque_notIssued() = runReleaseTest {
        val a = add(ControlKind.SEAL, newSeal); val b = build(a)
        o.seed(seal = "[{\"id\":\"${a.proposedId}\",\"kind\":\"NAMESPACE\"}]")
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.SEAL, a.proposedId)
        for (s in b.slots(0, ObligationComponent.SEAL))
            rejected(PriorStorageConfirmation.confirmRetainedSource(s, d, read), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    // ═══ HOLD and RECOVERY_INTENT sources ══════════════════════════════════════════════════════════════════════════════
    /** A6: a landed HOLD Add: both SOURCE slots issued on the added hold. */
    @Test fun A6_landedHoldAdd_sourceSlotsIssued() = runReleaseTest {
        val a = add(ControlKind.HOLD, hold); val b = build(a)
        o.seed(hold = text(afterOf(a)))
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.HOLD, a.proposedId)
        val slots = b.slots(0, ObligationComponent.SOURCE)
        assertEquals("fixture: SOURCE L and N", 2, slots.size)
        for (s in slots) issued(PriorStorageConfirmation.confirmRetainedSource(s, d, read))
    }

    /** A7: a landed RECOVERY_INTENT Add: both SOURCE slots issued on the added intent. */
    @Test fun A7_landedIntentAdd_sourceSlotsIssued() = runReleaseTest {
        val a = add(ControlKind.RECOVERY_INTENT, recovery); val b = build(a)
        o.seed(recovery = text(afterOf(a)))
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.RECOVERY_INTENT, a.proposedId)
        val slots = b.slots(0, ObligationComponent.SOURCE)
        assertEquals("fixture: SOURCE L and N", 2, slots.size)
        for (s in slots) issued(PriorStorageConfirmation.confirmRetainedSource(s, d, read))
    }

    /** N6: one REQUEST L token only (N missing): G05 coverage. */
    @Test fun N6_onlyOneSlotHandedOver_g05Coverage() = runReleaseTest {
        val a = add(ControlKind.DEMAND, request); val b = build(a)
        o.seed(demand = text(afterOf(a)))
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.DEMAND, a.proposedId)
        val slots = b.slots(0, ObligationComponent.REQUEST)
        val l = slots.single { it.key.branch == LandingBranch.L }; val n = slots.single { it.key.branch == LandingBranch.N }
        val t = issued(PriorStorageConfirmation.confirmRetainedSource(l, d, read))
        val r = assessG05(b.ref, b.a, CompletionHandoff(b.a.commandBinding, ResponsibilityOwner(b.ref.ownerTrackingLifetimeId, "owner-1"),
            listOf(SlotHandoff(l.key, HandoffDisposition.DurablyOwned(d, emptyList(), t)))), TerminationClosures.of(b.ref), read.value, now)
        assertTrue("expected Rejected, got $r", r is G05Result.Rejected)
        assertEquals(listOf(G05Failure(G05Id.COVERAGE_N, n.key,
            G05Location.Fixed((n.requirement as SlotRequirement.Required).fixedSources.first().location), null, null)), (r as G05Result.Rejected).failures)
    }

    /** N7: the added row stored twice (duplicate id, opaque to the reader): not issued. */
    @Test fun N7_addedRowDuplicated_notIssued() = runReleaseTest {
        val a = add(ControlKind.DEMAND, request); val b = build(a)
        o.seed(demand = text(afterOf(a), afterOf(a)))
        val read = locked(); val d = DestinationLocator.Payload(ControlKind.DEMAND, a.proposedId)
        assertTrue("fixture: duplicates are opaque", read.value.hasUninterpretable)
        for (s in b.slots(0, ObligationComponent.REQUEST))
            rejected(PriorStorageConfirmation.confirmRetainedSource(s, d, read), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    // ═══ contract r3: tampered slots and records (6-4bC1b0_battery_r1_codex.md, option (a)) ════════════════════════════
    private fun req(s: RequiredSlot) = s.requirement as SlotRequirement.Required
    private fun withSources(s: RequiredSlot, f: (List<FixedSourceEvidence>) -> List<FixedSourceEvidence>) =
        s.copy(requirement = req(s).copy(fixedSources = f(req(s).fixedSources)))
    private fun withBound(s: RequiredSlot, bound: RequiredLowerBound) = s.copy(requirement = req(s).copy(lowerBound = bound))
    private fun loc(root: FixedInputRoot, index: Int, facet: FixedInputFacet) = FixedInputLocation(root, index, facet)
    private fun replaced(s: RequiredSlot, at: FixedInputLocation, fact: FixedSourceFact) = withSources(s) { list ->
        assertTrue("fixture: $at present", list.any { it.location == at }); list.map { if (it.location == at) it.copy(fact = fact) else it } }
    private val actionWhole = loc(FixedInputRoot.MUTATION_ACTION, 0, FixedInputFacet.WHOLE)
    private val actionAfter = loc(FixedInputRoot.MUTATION_ACTION, 0, FixedInputFacet.AFTER)
    private val actionBefore = loc(FixedInputRoot.MUTATION_ACTION, 0, FixedInputFacet.BEFORE)
    private val adoptionAt = loc(FixedInputRoot.MUTATION_ADOPTION, 0, FixedInputFacet.WHOLE)
    private fun l(slots: List<RequiredSlot>) = slots.single { it.key.branch == LandingBranch.L }
    private fun n(slots: List<RequiredSlot>) = slots.single { it.key.branch == LandingBranch.N }
    private suspend fun expect(slot: RequiredSlot, d: DestinationLocator, reason: RetainedConfirmationFailure) =
        rejected(PriorStorageConfirmation.confirmRetainedSource(slot, d, locked()), reason)
    private fun <T> parsed(kind: ControlKind, json: String): T {
        @Suppress("UNCHECKED_CAST")
        return (ControlObligations.read(kind, node(json)) as ControlEntryRead.Interpreted).value as T
    }
    private fun withId(json: String, old: String, new: String) = json.replace("\"id\":\"$old\"", "\"id\":\"$new\"")

    private class Landed(val a: ControlMutation.Add, val b: Built, val d: DestinationLocator)
    private suspend fun landedAdd(kind: ControlKind, json: String): Landed {
        val a = add(kind, json); val b = build(a)
        when (kind) {
            ControlKind.DEMAND -> o.seed(demand = text(afterOf(a)))
            ControlKind.SEAL -> o.seed(seal = text(afterOf(a)))
            ControlKind.HOLD -> o.seed(hold = text(afterOf(a)))
            ControlKind.RECOVERY_INTENT -> o.seed(recovery = text(afterOf(a)))
        }
        return Landed(a, b, DestinationLocator.Payload(kind, a.proposedId))
    }
    private val S = RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH
    private val E0 = RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW
    private val NR = RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE

    /** T01 (Q.*): a Mutations slot whose component is not its bound's is not a required source. */
    @Test fun T01_componentNotTheBoundsOwn_notARequiredSource() = runReleaseTest {
        val r = landedAdd(ControlKind.DEMAND, request)
        expect(l(r.b.slots(0, ObligationComponent.REQUEST)).let { it.copy(key = it.key.copy(component = ObligationComponent.SOURCE)) }, r.d, NR)
        val s = landedAdd(ControlKind.SEAL, newSeal)
        expect(l(s.b.slots(0, ObligationComponent.SEAL)).let { it.copy(key = it.key.copy(component = ObligationComponent.SOURCE)) }, s.d, NR)
        val h = landedAdd(ControlKind.HOLD, hold)
        expect(l(h.b.slots(0, ObligationComponent.SOURCE)).let { it.copy(key = it.key.copy(component = ObligationComponent.REQUEST)) }, h.d, NR)
        expect(l(h.b.slots(0, ObligationComponent.FLOOR)).let { it.copy(key = it.key.copy(component = ObligationComponent.REQUEST)) }, h.d, NR)
    }

    /** T02 (R.action/R.nodeKind/R.addBefore/R.adoptSingle/R.targetId): the fixed action inputs tampered one at a time. */
    @Test fun T02_fixedActionInputsTampered_subjectOrBoundMismatch() = runReleaseTest {
        val r = landedAdd(ControlKind.DEMAND, request)
        val slot = l(r.b.slots(0, ObligationComponent.REQUEST))
        val whole = req(slot).fixedSources.single { it.location == actionWhole }
        val adoption = req(slot).fixedSources.single { it.location == adoptionAt }
        val variants = listOf(
            withSources(slot) { list -> list.filterNot { it.location == actionWhole } + whole.copy(location = loc(FixedInputRoot.MUTATION_ACTION, 1, FixedInputFacet.WHOLE)) },
            replaced(slot, actionAfter, FixedSourceFact.Node(ControlKind.SEAL, afterOf(r.a))),
            withSources(slot) { it + FixedSourceEvidence(actionBefore, FixedSourceFact.Node(ControlKind.DEMAND, afterOf(r.a))) },
            withSources(slot) { it + adoption.copy(location = loc(FixedInputRoot.PREVIOUS_CHECKPOINT, 0, FixedInputFacet.AFTER)) },
            replaced(slot, adoptionAt, FixedSourceFact.Adoption(ControlCommandTarget("zz", afterOf(r.a), false))))
        for (v in variants) expect(v, r.d, S)
    }

    /** T03 (R.afterEq): joined SEAL Add whose fixed AFTER names another id (same key): mismatch. */
    @Test fun T03_joinedSealAfterWithAnotherId_subjectOrBoundMismatch() = runReleaseTest {
        val existing = withId(newSeal, "x", "s")
        val a = add(ControlKind.SEAL, newSeal)
        val b = build(a) { _, _ -> ControlCommandTarget("s", node(existing), true) }
        o.seed(seal = "[$existing]")
        val slot = replaced(l(b.slots(0, ObligationComponent.SEAL)), actionAfter, FixedSourceFact.Node(ControlKind.SEAL, node(withId(newSeal, "x", "y"))))
        expect(slot, DestinationLocator.Payload(ControlKind.SEAL, "s"), S)
    }

    /** T04 (R.editBefore): a changed Edit whose fixed BEFORE is not the action's before: mismatch. */
    @Test fun T04_editFixedBeforeNotTheActionsBefore_subjectOrBoundMismatch() = runReleaseTest {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) }
        val b = build(e); o.seed(demand = text(afterOf(e)))
        val slot = replaced(l(b.slots(0, ObligationComponent.REQUEST)), actionBefore,
            FixedSourceFact.Node(ControlKind.DEMAND, node(request.replace("IF_STALE", "FORCE_PREMIUM"))))
        expect(slot, DestinationLocator.Payload(ControlKind.DEMAND, "d"), S)
    }

    /** T05 (R.joinedAdd): a no-op SEAL Edit whose fixed adoption claims joined: only an Add can join. */
    @Test fun T05_editAdoptionClaimsJoined_subjectOrBoundMismatch() = runReleaseTest {
        val e = ControlMutation.Edit.prepare(ControlKind.SEAL, node(ControlObligationFixtures.seal)) {}
        val b = build(e); o.seed(seal = "[${ControlObligationFixtures.seal}]")
        val slot = replaced(l(b.slots(0, ObligationComponent.SEAL)), adoptionAt,
            FixedSourceFact.Adoption(ControlCommandTarget("s", node(ControlObligationFixtures.seal), true)))
        expect(slot, DestinationLocator.Payload(ControlKind.SEAL, "s"), S)
    }

    /** T06 (R.joinedSettled/Z.adopted): an active NULL SEAL Add's slot rewritten to a settled seal of the same id and key. */
    @Test fun T06_activeSealSlotRewrittenToSettled_subjectOrBoundMismatch() = runReleaseTest {
        val nullSeal = ControlObligationFixtures.nullSeal.replace("\"id\":\"s\"", "\"id\":\"x\"")
        val a = add(ControlKind.SEAL, nullSeal); val b = build(a)
        val settledJson = withId(ControlObligationFixtures.settledSeal, "s", a.proposedId)
        val settled = parsed<SealV1>(ControlKind.SEAL, settledJson)
        o.seed(seal = "[$settledJson]")
        val base = l(b.slots(0, ObligationComponent.SEAL))
        val bound = RequiredLowerBound.Seal(settled, settled.settlement)
        val d = DestinationLocator.Payload(ControlKind.SEAL, a.proposedId)
        // Joined adoption to the settled seal as well (R.joinedSettled), and only the bound and stored row (Z.adopted).
        expect(withBound(replaced(base, adoptionAt, FixedSourceFact.Adoption(ControlCommandTarget(a.proposedId, node(settledJson), true))), bound), d, S)
        expect(withBound(base, bound), d, S)
    }

    /**
     * T07 (R.plainPost/H.src/S.floorAuth): HOLD Add. SOURCE side: the adoption, or the bound and stored row, carry a longer wait
     * (floor waitMillis 31000 with retryAfterSeconds 31, kept consistent; not part of the subject). FLOOR side: the stored row has
     * another binding with the floor unchanged (binding is not part of the floor subject).
     */
    @Test fun T07_holdAdoptionBoundOrRowDiffers() = runReleaseTest {
        val a = add(ControlKind.HOLD, hold); val b = build(a)
        fun variant(from: String, to: String) = withId(hold, "h", a.proposedId).replace(from, to)
        val longerWait = variant("\"waitMillis\":30000", "\"waitMillis\":31000").replace("\"retryAfterSeconds\":30", "\"retryAfterSeconds\":31")
        assertTrue("fixture: interpretable longer wait", ControlObligations.read(ControlKind.HOLD, node(longerWait)) is ControlEntryRead.Interpreted)
        val otherBinding = variant("\"binding\":3", "\"binding\":4") // the hold's and its provenance's binding together
        assertTrue("fixture: interpretable other binding", ControlObligations.read(ControlKind.HOLD, node(otherBinding)) is ControlEntryRead.Interpreted)
        val d = DestinationLocator.Payload(ControlKind.HOLD, a.proposedId)
        o.seed(hold = text(afterOf(a)))
        val source = l(b.slots(0, ObligationComponent.SOURCE))
        expect(replaced(source, adoptionAt, FixedSourceFact.Adoption(ControlCommandTarget(a.proposedId, node(longerWait), false))), d, S)
        o.seed(hold = "[$longerWait]")
        expect(withBound(source, RequiredLowerBound.Hold(parsed(ControlKind.HOLD, longerWait))), d, S)
        o.seed(hold = "[$otherBinding]")
        expect(l(b.slots(0, ObligationComponent.FLOOR)), d, E0)
    }

    /** T08 (I.src/P.intentCmp): RECOVERY_INTENT Add with the bound and subject, or only the stored row, naming targetEpoch "k". */
    @Test fun T08_intentBoundOrRowDiffers() = runReleaseTest {
        val a = add(ControlKind.RECOVERY_INTENT, recovery); val b = build(a)
        val changedJson = withId(recovery, "r", a.proposedId).replace("\"targetEpoch\":null", "\"targetEpoch\":\"k\"")
        val changed = parsed<RecoveryIntentV1>(ControlKind.RECOVERY_INTENT, changedJson)
        val d = DestinationLocator.Payload(ControlKind.RECOVERY_INTENT, a.proposedId)
        o.seed(recovery = "[$changedJson]")
        val source = l(b.slots(0, ObligationComponent.SOURCE))
        val subject = ObligationSubject.Intent(changed.id, changed.sessionId, changed.ownerUid, changed.axis, changed.targetEpoch)
        expect(withBound(source.copy(key = source.key.copy(subject = subject)), RequiredLowerBound.Intent(changed)), d, S)
        expect(source, d, E0)
    }

    /** T09 (D.src): a REQUEST Add (FORCE_PREMIUM) slot whose bound source says IF_STALE: mismatch. */
    @Test fun T09_requestAddBoundSourceWeaker_subjectOrBoundMismatch() = runReleaseTest {
        val r = landedAdd(ControlKind.DEMAND, request.replace("IF_STALE", "FORCE_PREMIUM"))
        val slot = l(r.b.slots(0, ObligationComponent.REQUEST))
        val bound = req(slot).lowerBound as RequiredLowerBound.Request
        expect(withBound(slot, bound.copy(source = checkNotNull(bound.source).copy(intent = RefreshIntent.IF_STALE))), r.d, S)
    }

    /** T10 (D.intent): Edit IF_STALE/4 → FORCE_PREMIUM/5; the N slot's minimum intent lowered, the stored row IF_STALE/5. */
    @Test fun T10_editNMinimumIntentLowered_subjectOrBoundMismatch() = runReleaseTest {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(request)) {
            set("intent", ControlScalar.Text("FORCE_PREMIUM")); set("raisedAt", ControlScalar.Integer(5)) }
        val b = build(e); o.seed(demand = "[${request.replace("\"raisedAt\":4", "\"raisedAt\":5")}]")
        val slot = n(b.slots(0, ObligationComponent.REQUEST))
        val bound = req(slot).lowerBound as RequiredLowerBound.Request
        expect(withBound(slot, bound.copy(minimumIntent = RefreshIntent.IF_STALE)), DestinationLocator.Payload(ControlKind.DEMAND, "d"), S)
    }

    /** T11 (D.order): Edit order 4 → 5; the N slot's minimum order lowered to 4, the stored row BEFORE. */
    @Test fun T11_editNMinimumOrderLowered_subjectOrBoundMismatch() = runReleaseTest {
        val e = ControlMutation.Edit.prepare(ControlKind.DEMAND, node(request)) { set("raisedAt", ControlScalar.Integer(5)) }
        val b = build(e); o.seed(demand = "[$request]")
        val slot = n(b.slots(0, ObligationComponent.REQUEST))
        val bound = req(slot).lowerBound as RequiredLowerBound.Request
        expect(withBound(slot, bound.copy(minimumOrder = bound.minimumOrder.copy(value = 4))), DestinationLocator.Payload(ControlKind.DEMAND, "d"), S)
    }
}
