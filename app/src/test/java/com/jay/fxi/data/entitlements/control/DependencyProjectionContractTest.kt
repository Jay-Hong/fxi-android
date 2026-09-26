package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.control.ControlLifecycleEvidenceFixtures as F
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-1C T7 contract (API r2 §3; skeleton v12 §8 T7; revision 06 §4.3 G26). projectDependency is a pure
 * projection of ONE other ref from its single captured (state, body) view plus immutable copies of its adopted targets
 * and pending descriptors — it never receives the CommandRef, so no body/actions/state re-read exists. Open views union
 * the body's explicit targets (including lifecycle requiredUnchanged), the proposed and adopted target IDs, and the
 * descriptor's fixed evidence; closed views (RELEASED/TERMINATED) contribute nothing and never touch the stale body;
 * partial or inconsistent inputs are Unknown (conservative keep), never an empty "no dependency". Traversal of all refs,
 * closure and owner removal refusal are later entries. The implementation thread reads but does not edit this file.
 */
class DependencyProjectionContractTest {
    private val tracked = ControlReleaseFixtures.fixture() // Mutations: Edit DEMAND "d", Edit RECOVERY_INTENT "r"
    private val command = tracked.command
    private val body = command.body
    private val life = command.ownerTrackingLifetimeId.value
    private fun input(state: ControlCommandLifecycle = ControlCommandLifecycle.RETAINED, b: ControlCommandBody? = body,
        adopted: List<ControlCommandTarget?>? = listOf(null, null), release: ReleasePendingDescriptor? = null,
        termination: TerminationPendingDescriptor? = null, id: String = command.id) =
        DependencyProjectionInput(id, life, RefView(state, b), adopted, release, termination)
    private fun known(id: String, p: DependencyProjection): Set<DependencyAtom> {
        assertTrue("D2B6/T7.$id: known $p", p is DependencyProjection.Known)
        return (p as DependencyProjection.Known).dependencies
    }
    // The fixture's Edit sets raisedAt 4 → 5; an adopted target's postcondition is the edited value (RECOVERY_INTENT edit is a no-op).
    private fun demandTarget(id: String) = ControlCommandTarget(id, node(ControlObligationFixtures.request
        .replace("\"id\":\"d\"", "\"id\":\"$id\"").replace("\"raisedAt\":4", "\"raisedAt\":5").also { check(it.contains("\"raisedAt\":5")) }), false)
    private val recoveryTarget = ControlCommandTarget("r", node(ControlObligationFixtures.recovery), false)
    private val binding = TerminationClosureBinding(command, command.ownerTrackingLifetimeId, command.id, 1L, true,
        emptySet(), emptySet(), emptySet(), true, "owner-1")
    private val evidenceAbsent = TerminationPendingDescriptor.EvidenceAbsent(CompletionMode.NeverSubmitted, TerminationEntry.AbandonBeforeFirstConfirm, binding)
    private val d = DependencyAtom.ControlRow(ControlKind.DEMAND, "d")
    private val r = DependencyAtom.ControlRow(ControlKind.RECOVERY_INTENT, "r")

    @Test fun T7_01_neverAdoptedMutationsUseProposedTargets() {
        val deps = known("01", projectDependency(input()))
        assertTrue("D2B6/T7.01: proposedTargets", deps.containsAll(setOf(d, r)))
    }
    @Test fun T7_02_joinedSealAdoptionAddsTheExistingIdAndKeepsTheProposed() {
        // The real different-ID adoption: an Add SEAL with a proposed id joined the existing same-key seal "existing".
        val sealJson = ControlObligationFixtures.seal
        val proposed = java.util.UUID(0, 77)
        val add = ControlMutation.Add.prepare(ControlKind.SEAL, proposed) { id -> literal(sealJson); set("id", ControlScalar.Text(id)) }
        val sealBody = ControlCommandBody.Mutations(listOf(add))
        val joined = ControlCommandTarget("existing", node(sealJson.replace("\"id\":\"s\"", "\"id\":\"existing\"")), true)
        val deps = known("02", projectDependency(input(b = sealBody, adopted = listOf(joined), id = "seal-cmd")))
        assertTrue("D2B6/T7.02: adoptedIncluded", DependencyAtom.ControlRow(ControlKind.SEAL, "existing") in deps)
        assertTrue("D2B6/T7.02: proposedKeptConservatively", DependencyAtom.ControlRow(ControlKind.SEAL, proposed.toString()) in deps)
    }
    @Test fun T7_03_lifecycleTargetsAndRequiredUnchanged() {
        val keepNode = node(ControlObligationFixtures.request.replace("\"id\":\"d\"", "\"id\":\"keep\""))
        val keep = LifecycleFixedTarget(LifecycleTarget(ControlKind.DEMAND, "keep", LifecycleEffect.REPLACE), LifecycleRole.REQUEST, keepNode, keepNode)
        val lifecycle = ControlCommandBody.Lifecycle(ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD,
            listOf(F.removeGuard), requiredUnchanged = listOf(keep)))
        val deps = known("03", projectDependency(input(b = lifecycle, adopted = emptyList(), id = "lc")))
        assertTrue("D2B6/T7.03: wireTarget", DependencyAtom.ControlRow(ControlKind.DEMAND, "g") in deps)
        assertTrue("D2B6/T7.03: bodyOnlyRequiredUnchanged", DependencyAtom.ControlRow(ControlKind.DEMAND, "keep") in deps)
    }
    @Test fun T7_04_releaseDescriptorExactEvidenceIsADependency() {
        val row = ControlReleaseFixtures.row(command)
        val deps = known("04", projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING,
            adopted = listOf(demandTarget("d"), recoveryTarget), release = ReleasePendingDescriptor.ExactMutations(row))))
        assertTrue("D2B6/T7.04: appliedRow", DependencyAtom.AppliedRow(command.id, life) in deps)
        assertTrue("D2B6/T7.04: bodyKept", deps.containsAll(setOf(d, r)))
    }
    @Test fun T7_04b_descriptorContradictingAdoptionIsUnknownAndKeepsKnown() {
        // An Edit adopts its target id; a fixed descriptor naming another id contradicts the command → conservative Unknown.
        val row = ControlReleaseFixtures.row(command, targets = listOf(AppliedTarget(0, ControlKind.DEMAND, "desc-only", false, true),
            AppliedTarget(1, ControlKind.RECOVERY_INTENT, "r", false, false)))
        val p = projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING,
            adopted = listOf(demandTarget("d"), recoveryTarget), release = ReleasePendingDescriptor.ExactMutations(row)))
        assertTrue("D2B6/T7.04b: unknown $p", p is DependencyProjection.Unknown)
        assertTrue("D2B6/T7.04b: knownKept", (p as DependencyProjection.Unknown).knownDependencies.containsAll(setOf(d, r)))
    }
    @Test fun T7_05_confirmedWithoutAppliedAndEvidenceAbsentInventNoAppliedRow() {
        val released = known("05a", projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING,
            release = ReleasePendingDescriptor.ConfirmedWithoutApplied)))
        assertTrue("D2B6/T7.05a: noFakeApplied", released.none { it is DependencyAtom.AppliedRow })
        val terminating = known("05b", projectDependency(input(state = ControlCommandLifecycle.TERMINATION_PENDING, termination = evidenceAbsent)))
        assertTrue("D2B6/T7.05b: noFakeApplied", terminating.none { it is DependencyAtom.AppliedRow })
        assertTrue("D2B6/T7.05b: capturedBodyKept", terminating.containsAll(setOf(d, r)))
    }
    @Test fun T7_06_closedViewsContributeNothingAndIgnoreStaleInputs() {
        val row = ControlReleaseFixtures.row(command)
        for ((id, state) in listOf("06a" to ControlCommandLifecycle.TERMINATED, "06b" to ControlCommandLifecycle.RELEASED)) {
            val stale = input(state = state, b = if (state == ControlCommandLifecycle.TERMINATED) null else body,
                adopted = listOf(demandTarget("d"), recoveryTarget), release = ReleasePendingDescriptor.ExactMutations(row))
            assertEquals("D2B6/T7.$id: closed", DependencyProjection.Known(emptySet()), projectDependency(stale))
        }
    }
    @Test fun T7_07_capturedPendingViewKeepsItsDependenciesAfterTerminal() {
        // A pending view captured before the terminal publication stays the projection's only input for that call.
        val captured = input(state = ControlCommandLifecycle.TERMINATION_PENDING, termination = evidenceAbsent)
        val before = known("07a", projectDependency(captured))
        assertTrue("D2B6/T7.07a: capturedDeps", before.containsAll(setOf(d, r)))
        val afterTerminal = input(state = ControlCommandLifecycle.TERMINATED, b = null, termination = evidenceAbsent)
        assertEquals("D2B6/T7.07b: newCaptureClosed", DependencyProjection.Known(emptySet()), projectDependency(afterTerminal))
        assertEquals("D2B6/T7.07c: capturedInputUnchanged", before, known("07c", projectDependency(captured)))
    }
    @Test fun T7_08_partialOrInconsistentInputsAreUnknown() {
        fun unknown(id: String, p: DependencyProjection) = assertTrue("D2B6/T7.$id: unknown $p", p is DependencyProjection.Unknown)
        unknown("08a: partialAdoption", projectDependency(input(adopted = listOf(demandTarget("d"), null))))
        unknown("08b: adoptionUnavailable", projectDependency(input(adopted = null)))
        unknown("08c: openStateWithoutBody", projectDependency(input(b = null)))
        unknown("08d: wrongTargetCount", projectDependency(input(adopted = listOf(null))))
    }
    @Test fun T7_09_unknownKeepsWhatIsKnown() {
        val p = projectDependency(input(adopted = listOf(demandTarget("d"), null)))
        assertTrue("D2B6/T7.09: knownKept", p is DependencyProjection.Unknown && (p as DependencyProjection.Unknown).knownDependencies.containsAll(setOf(d, r)))
    }

    // ── other body kinds (implementation review r1): decision effects and settlement inputs ──
    @Test fun T7_10_lifecycleDecisionEffectIsADependency() {
        // revision 06 §4.3: lifecycle targets/requiredUnchanged AND decision.effects are explicit semantic obligations.
        val effect = LifecycleDurableEffect(ControlKind.DEMAND, DemandAuthFixtures.request("eff-1"), null)
        val plan = DemandAuthFixtures.plan(DemandAuthFixtures.decision(outcome = EntitlementsOutcome.Pending(false, 30), effects = listOf(effect)))
        val lifecycle = ControlCommandBody.Lifecycle(plan.descriptor("command"))
        val deps = known("10", projectDependency(input(b = lifecycle, adopted = emptyList(), id = "command")))
        assertTrue("D2B6/T7.10: effectRow", DependencyAtom.ControlRow(ControlKind.DEMAND, "eff-1") in deps)
    }
    @Test fun T7_11_rotationProjectsSealWitnessJournalAndRequest() {
        val body = ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input())
        val deps = known("11", projectDependency(input(b = body, adopted = emptyList(), id = NamespaceSettlementFixtures.operation)))
        assertTrue("D2B6/T7.11: seal", DependencyAtom.ControlRow(ControlKind.SEAL, "s") in deps)
        assertTrue("D2B6/T7.11: witness", DependencyAtom.SealWitness("s", NamespaceSettlementFixtures.operation) in deps)
        assertTrue("D2B6/T7.11: journal", DependencyAtom.Journal(JournalTargetV1("A", PurgeScope.USER, "u")) in deps)
        assertTrue("D2B6/T7.11: request", DependencyAtom.ControlRow(ControlKind.DEMAND, NamespaceSettlementFixtures.demandId) in deps)
    }
    @Test fun T7_12_rotationSealDisagreeingWithBeforeIsUnknownAndKeepsTheSealKey() {
        // A parsable but inconsistent fixed input: the seal's epoch "old" is not before's "u".
        val mismatched = NamespaceSettlementFixtures.user.replace("\"epoch\":\"u\"", "\"epoch\":\"old\"")
        val body = ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input(targets = listOf(node(mismatched))))
        val p = projectDependency(input(b = body, adopted = emptyList(), id = NamespaceSettlementFixtures.operation))
        assertTrue("D2B6/T7.12: unknown $p", p is DependencyProjection.Unknown)
        assertTrue("D2B6/T7.12: originalSealKeyKept",
            DependencyAtom.Journal(JournalTargetV1("A", PurgeScope.USER, "old")) in (p as DependencyProjection.Unknown).knownDependencies)
    }
    @Test fun T7_13_currentNullWithWrongTargetKindIsUnknown() {
        // A NAMESPACE seal submitted as a NULL target is inconsistent with the CURRENT_NULL input.
        val body = ControlCommandBody.RotateAndSettleCurrentNull(CurrentNullFixtures.spec(target = node(CurrentNullFixtures.companionUser)))
        val p = projectDependency(input(b = body, adopted = emptyList(), id = "n-operation"))
        assertTrue("D2B6/T7.13: unknown $p", p is DependencyProjection.Unknown)
    }

    // ── r7 (6-1C measurement r1): each Unknown alone, and each body's own atoms ──
    private fun unknownKeeping(id: String, p: DependencyProjection, vararg atoms: DependencyAtom) {
        assertTrue("D2B6/T7.$id: unknown $p", p is DependencyProjection.Unknown)
        for (a in atoms) assertTrue("D2B6/T7.$id: keeps $a", a in (p as DependencyProjection.Unknown).knownDependencies)
    }
    @Test fun T7_14_stateAndDescriptorMustAgree() {
        val row = ControlReleaseFixtures.row(command)
        val exact = ReleasePendingDescriptor.ExactMutations(row)
        unknownKeeping("14a: releasePendingWithoutDescriptor", projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING)), d, r)
        unknownKeeping("14b: releaseDescriptorOnRetained", projectDependency(input(release = exact)), d, r)
        unknownKeeping("14c: terminationPendingWithoutDescriptor", projectDependency(input(state = ControlCommandLifecycle.TERMINATION_PENDING)), d, r)
        unknownKeeping("14d: terminationDescriptorOnRetained", projectDependency(input(termination = evidenceAbsent)), d, r)
        unknownKeeping("14e: descriptorOfAnotherCommand",
            projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING, release = exact, id = "other-command")), d, r)
        val short = ControlReleaseFixtures.row(command, targets = listOf(AppliedTarget(0, ControlKind.DEMAND, "d", false, true)))
        unknownKeeping("14f: descriptorShape", projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING,
            release = ReleasePendingDescriptor.ExactMutations(short))), d, r)
    }
    @Test fun T7_15_descriptorTargetAbsentFromBodyAndAdoptionIsADependency() {
        // API r2 T7 (3): the descriptor's own fixed target joins even when no adopted target contradicts it.
        val row = ControlReleaseFixtures.row(command, targets = listOf(AppliedTarget(0, ControlKind.DEMAND, "desc-only", false, true),
            AppliedTarget(1, ControlKind.RECOVERY_INTENT, "r", false, false)))
        val deps = known("15", projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING, release = ReleasePendingDescriptor.ExactMutations(row))))
        assertTrue("D2B6/T7.15: descriptorTarget", DependencyAtom.ControlRow(ControlKind.DEMAND, "desc-only") in deps)
    }
    @Test fun T7_16_emptyAdoptedIdIsUnknown() {
        unknownKeeping("16", projectDependency(input(adopted = listOf(ControlCommandTarget("", demandTarget("d").postcondition, false), recoveryTarget))), d, r)
    }
    @Test fun T7_17_editBeforeIsTheTargetEvenWhenTheChangeIsRejected() {
        val rejectedEdit = ControlMutation.Edit.prepare(ControlKind.DEMAND,
            node(ControlObligationFixtures.request.replace("\"id\":\"d\"", "\"id\":\"e1\""))) { set("id", ControlScalar.Text("e2")) }
        check(rejectedEdit.changed !is ControlWriteResult.Written) { "fixture: an id change must be rejected" }
        val deps = known("17a", projectDependency(input(b = ControlCommandBody.Mutations(listOf(rejectedEdit)), adopted = listOf(null), id = "edit-cmd")))
        assertTrue("D2B6/T7.17a: beforeRow", DependencyAtom.ControlRow(ControlKind.DEMAND, "e1") in deps)
        val unreadableBefore = ControlMutation.Edit.prepare(ControlKind.DEMAND, node("""{"id":"x","kind":"REQUEST"}""")) {}
        unknownKeeping("17b: unreadableBefore",
            projectDependency(input(b = ControlCommandBody.Mutations(listOf(unreadableBefore)), adopted = listOf(null), id = "edit-cmd")))
    }
    @Test fun T7_18_unreadableEffectKeepsItsRawIdAndNamespaceJournalExpands() {
        val broken = LifecycleDurableEffect(ControlKind.DEMAND, node("""{"id":"raw-1","kind":"REQUEST"}"""), null)
        val plan = DemandAuthFixtures.plan(DemandAuthFixtures.decision(outcome = EntitlementsOutcome.Pending(false, 30), effects = listOf(broken)))
        unknownKeeping("18a", projectDependency(input(b = ControlCommandBody.Lifecycle(plan.descriptor("command")), adopted = emptyList(), id = "command")),
            DependencyAtom.ControlRow(ControlKind.DEMAND, "raw-1"))
        val ns = LifecycleNamespacePostcondition(FenceV1("A", "u", "k"), FenceV1("A", "u9", "k9"),
            listOf(PendingPurge("A", "u", "k", setOf(PurgeScope.USER, PurgeScope.CAPABILITY))))
        val lifecycle = ControlCommandBody.Lifecycle(ControlLifecycleDescriptor("lc", LifecycleTransition.REMOVE_EMPTY_GUARD,
            listOf(F.removeGuard), namespace = ns))
        val deps = known("18b", projectDependency(input(b = lifecycle, adopted = emptyList(), id = "lc")))
        assertTrue("D2B6/T7.18b: userAxis", DependencyAtom.Journal(JournalTargetV1("A", PurgeScope.USER, "u")) in deps)
        assertTrue("D2B6/T7.18b: capabilityAxis", DependencyAtom.Journal(JournalTargetV1("A", PurgeScope.CAPABILITY, "k")) in deps)
    }
    @Test fun T7_19_settlementBodiesProjectTheirOwnDependencies() {
        // Rotation: an already-settled seal's witness contributes its own operation and journal even while Unknown.
        val prior = """{"operationId":"prior-op","originLifetimeId":"life","operation":"BEGIN_ROTATION","before":{"ownerUid":"A","userAccessEpoch":"u","krxCapabilityEpoch":"k"},"after":{"ownerUid":"A","userAccessEpoch":"u9","krxCapabilityEpoch":"k"},"journal":{"ownerUid":null,"axis":"USER","epoch":null}}"""
        val settled = node(NamespaceSettlementFixtures.user.dropLast(1) + ",\"settlement\":" + prior + "}")
        val rotation = NamespaceSettlementFixtures.input(targets = listOf(settled)).also { check(it.sealTargets.size == 1) { "fixture: settled seal must be schema-valid" } }
        unknownKeeping("19a", projectDependency(input(b = ControlCommandBody.RotateAndSettle(rotation), adopted = emptyList(), id = NamespaceSettlementFixtures.operation)),
            DependencyAtom.SealWitness("s", "prior-op"), DependencyAtom.Journal(JournalTargetV1(null, PurgeScope.USER, null)))
        // Rotation: a seal disagreeing with before still keeps before's own journal key.
        val mismatched = NamespaceSettlementFixtures.user.replace("\"epoch\":\"u\"", "\"epoch\":\"old\"")
        unknownKeeping("19b", projectDependency(input(b = ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input(targets = listOf(node(mismatched)))),
            adopted = emptyList(), id = NamespaceSettlementFixtures.operation)), DependencyAtom.Journal(JournalTargetV1("A", PurgeScope.USER, "u")))
        // Retired namespace: valid input projects its witness and request; an invalid one is Unknown.
        val retired = RetiredNamespaceFixtures.spec()
        val retiredDeps = known("19c", projectDependency(input(b = ControlCommandBody.SettleRetiredNamespace(retired), adopted = emptyList(), id = retired.operationId)))
        assertTrue("D2B6/T7.19c: witness", DependencyAtom.SealWitness("s", retired.operationId) in retiredDeps)
        assertTrue("D2B6/T7.19c: request", DependencyAtom.ControlRow(ControlKind.DEMAND, "r-demand") in retiredDeps)
        val retiredBad = RetiredNamespaceFixtures.spec(exec = RetiredNamespaceFixtures.executor.copy(ownerUid = "B"))
        unknownKeeping("19d", projectDependency(input(b = ControlCommandBody.SettleRetiredNamespace(retiredBad), adopted = emptyList(), id = retiredBad.operationId)))
        // Current NULL: valid input projects its request; a foreign-owner target keeps before's null-epoch journal.
        val current = CurrentNullFixtures.spec()
        val currentDeps = known("19e", projectDependency(input(b = ControlCommandBody.RotateAndSettleCurrentNull(current), adopted = emptyList(), id = current.operationId)))
        assertTrue("D2B6/T7.19e: request", DependencyAtom.ControlRow(ControlKind.DEMAND, current.demandId) in currentDeps)
        val foreign = CurrentNullFixtures.spec(target = node(CurrentNullFixtures.nullUser.replace("\"ownerUid\":\"A\"", "\"ownerUid\":\"B\"")))
        unknownKeeping("19f", projectDependency(input(b = ControlCommandBody.RotateAndSettleCurrentNull(foreign), adopted = emptyList(), id = foreign.operationId)),
            DependencyAtom.Journal(JournalTargetV1("A", PurgeScope.USER, null)))
        // Retired NULL: a NAMESPACE target is Unknown and still keeps the null-epoch key it would settle.
        val retiredNull = RetiredNullFixtures.spec(target = node(NamespaceSettlementFixtures.user)).also { check(it.ordered.size == 1) }
        unknownKeeping("19g", projectDependency(input(b = ControlCommandBody.SettleRetiredNull(retiredNull), adopted = emptyList(), id = retiredNull.operationId)),
            DependencyAtom.Journal(JournalTargetV1("A", PurgeScope.USER, null)))
    }

    // ── 6-2C C1 (API consensus 6-2C_api_consensus.md): typed gaps, the ExactEvidenceAndSeals projection, classification ──
    private fun gaps(id: String, p: DependencyProjection): Set<DependencyGap> {
        assertTrue("D2B6/6-2C.$id: unknown $p", p is DependencyProjection.Unknown)
        p as DependencyProjection.Unknown
        assertTrue("D2B6/6-2C.$id: gapsNonEmpty", p.gaps.isNotEmpty())
        assertTrue("D2B6/6-2C.$id: everyFootprintNonEmpty", p.gaps.all { it.possibleAtoms.isNotEmpty() })
        return p.gaps
    }
    private fun causes(id: String, p: DependencyProjection) = gaps(id, p).map { it.cause }.toSet()
    private fun gap(id: String, p: DependencyProjection, cause: DependencyGapCause) = gaps(id, p).single { it.cause == cause }
    @Test fun T7_20_eachUnknownRecordsItsTypedCauseAndSource() {
        val pa = gap("20a", projectDependency(input(adopted = listOf(demandTarget("d"), null))), DependencyGapCause.PartialAdoption)
        assertEquals("D2B6/6-2C.20a: nullIndex", DependencyGapSource.Adoption(1), pa.source)
        assertEquals("D2B6/6-2C.20b", DependencyGapSource.Adoption(null),
            gap("20b", projectDependency(input(adopted = null)), DependencyGapCause.AdoptionUnavailable).source)
        assertEquals("D2B6/6-2C.20c", DependencyGapSource.Adoption(null),
            gap("20c", projectDependency(input(adopted = listOf(null))), DependencyGapCause.AdoptionUnavailable).source)
        assertEquals("D2B6/6-2C.20d", DependencyGapSource.Body,
            gap("20d", projectDependency(input(b = null)), DependencyGapCause.OpenViewWithoutBody).source)
        val row = ControlReleaseFixtures.row(command)
        assertEquals(setOf(DependencyGapCause.ReleaseDescriptorMissing), causes("20e", projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING))))
        assertEquals(setOf(DependencyGapCause.UnexpectedReleaseDescriptor), causes("20f", projectDependency(input(release = ReleasePendingDescriptor.ExactMutations(row)))))
        assertEquals(setOf(DependencyGapCause.TerminationDescriptorMissing), causes("20g", projectDependency(input(state = ControlCommandLifecycle.TERMINATION_PENDING))))
        assertEquals(setOf(DependencyGapCause.UnexpectedTerminationDescriptor), causes("20h", projectDependency(input(termination = evidenceAbsent))))
        assertEquals(setOf(DependencyGapCause.ReleaseIdentityMismatch), causes("20i",
            projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING, release = ReleasePendingDescriptor.ExactMutations(row), id = "other-command"))))
        val mismatch = ControlReleaseFixtures.row(command, targets = listOf(AppliedTarget(0, ControlKind.DEMAND, "desc-only", false, true),
            AppliedTarget(1, ControlKind.RECOVERY_INTENT, "r", false, false)))
        assertEquals("D2B6/6-2C.20j", DependencyGapSource.ReleaseDescriptor(0), gap("20j", projectDependency(input(state = ControlCommandLifecycle.RELEASE_PENDING,
            adopted = listOf(demandTarget("d"), recoveryTarget), release = ReleasePendingDescriptor.ExactMutations(mismatch))), DependencyGapCause.ReleaseTargetMismatch).source)
        assertEquals("D2B6/6-2C.20k", DependencyGapSource.Adoption(0), gap("20k", projectDependency(input(adopted = listOf(
            ControlCommandTarget("", demandTarget("d").postcondition, false), recoveryTarget))), DependencyGapCause.EmptyTargetId).source)
        val unreadableBefore = ControlMutation.Edit.prepare(ControlKind.DEMAND, node("""{"id":"x","kind":"REQUEST"}""")) {}
        assertEquals("D2B6/6-2C.20l", DependencyGapSource.Mutation(0, MutationSlot.Before), gap("20l",
            projectDependency(input(b = ControlCommandBody.Mutations(listOf(unreadableBefore)), adopted = listOf(null), id = "edit-cmd")),
            DependencyGapCause.UnreadableMutationBefore).source)
        val mismatched = NamespaceSettlementFixtures.user.replace("\"epoch\":\"u\"", "\"epoch\":\"old\"")
        assertEquals("D2B6/6-2C.20m", DependencyGapSource.FixedInput(FixedBodyKind.Rotation), gap("20m", projectDependency(input(
            b = ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input(targets = listOf(node(mismatched)))), adopted = emptyList(),
            id = NamespaceSettlementFixtures.operation)), DependencyGapCause.InvalidRotationInput).source)
        val retiredBad = RetiredNamespaceFixtures.spec(exec = RetiredNamespaceFixtures.executor.copy(ownerUid = "B"))
        assertEquals(setOf(DependencyGapCause.InvalidRetiredNamespaceInput), causes("20n",
            projectDependency(input(b = ControlCommandBody.SettleRetiredNamespace(retiredBad), adopted = emptyList(), id = retiredBad.operationId))))
        val cnBad = CurrentNullFixtures.spec(target = node(CurrentNullFixtures.companionUser))
        assertEquals(setOf(DependencyGapCause.InvalidCurrentNullInput), causes("20o",
            projectDependency(input(b = ControlCommandBody.RotateAndSettleCurrentNull(cnBad), adopted = emptyList(), id = cnBad.operationId))))
        val rnBad = RetiredNullFixtures.spec(target = node(NamespaceSettlementFixtures.user))
        assertEquals(setOf(DependencyGapCause.InvalidRetiredNullInput), causes("20p",
            projectDependency(input(b = ControlCommandBody.SettleRetiredNull(rnBad), adopted = emptyList(), id = rnBad.operationId))))
    }
    @Test fun T7_21_firstReasonIsKeptAndEveryGapAccumulates() {
        // Two independent problems in one ref: a partial adoption (first mark) and an unexpected termination descriptor.
        val p = projectDependency(input(adopted = listOf(demandTarget("d"), null), termination = evidenceAbsent))
        assertEquals("D2B6/6-2C.21: firstReason", "PartialAdoption", (p as DependencyProjection.Unknown).reason)
        assertEquals("D2B6/6-2C.21: bothGaps", setOf(DependencyGapCause.PartialAdoption, DependencyGapCause.UnexpectedTerminationDescriptor), causes("21", p))
    }

    private val sealS = DependencyAtom.ControlRow(ControlKind.SEAL, "s")
    private val protectedS = setOf(DependencyAtom.AppliedRow(NamespaceSettlementFixtures.operation, "rot-life"), sealS,
        DependencyAtom.SealWitness("s", NamespaceSettlementFixtures.operation))
    @Test fun T7_22_possibleIntersectionHonorsKindAndWildcard() {
        fun g(vararg atoms: DependencyAtomFootprint) = DependencyGap(DependencyGapCause.Unclassified, DependencyGapSource.Unclassified, atoms.toSet())
        assertTrue("D2B6/6-2C.22a: sealWildcard", possibleIntersection(g(DependencyAtomFootprint.ControlRow(setOf(ControlKind.SEAL), GapValue.Wildcard)), protectedS))
        assertTrue("D2B6/6-2C.22b: exactSame", possibleIntersection(g(DependencyAtomFootprint.ControlRow(setOf(ControlKind.SEAL), GapValue.Exact("s"))), protectedS))
        assertTrue("D2B6/6-2C.22c: exactOther", !possibleIntersection(g(DependencyAtomFootprint.ControlRow(setOf(ControlKind.SEAL), GapValue.Exact("x"))), protectedS))
        assertTrue("D2B6/6-2C.22d: otherKind", !possibleIntersection(g(DependencyAtomFootprint.ControlRow(setOf(ControlKind.DEMAND), GapValue.Wildcard)), protectedS))
        assertTrue("D2B6/6-2C.22e: witnessWildcard", possibleIntersection(g(DependencyAtomFootprint.SealWitness(GapValue.Wildcard, GapValue.Wildcard)), protectedS))
        assertTrue("D2B6/6-2C.22f: appliedWildcard", possibleIntersection(g(DependencyAtomFootprint.AppliedRow(GapValue.Wildcard, GapValue.Wildcard)), protectedS))
        assertTrue("D2B6/6-2C.22g: journalNotProtected", !possibleIntersection(g(DependencyAtomFootprint.Journal(GapValue.Wildcard)), protectedS))
    }
    @Test fun T7_23_classifyDependencyKnownFirstThenEveryGap() {
        // Known ∩ P → Present; Known disjoint → Clear.
        val known = DependencyProjection.Known(setOf(sealS))
        assertEquals("D2B6/6-2C.23a", DependencyIntersection.Present(sealS), classifyDependency(known, protectedS))
        assertEquals("D2B6/6-2C.23b", DependencyIntersection.Clear, classifyDependency(DependencyProjection.Known(setOf(d)), protectedS))
        // U-only SEAL Add: adoption unknown, the proposed id is unrelated, yet an existing-seal join could be "s".
        val proposed = java.util.UUID(0, 88)
        val add = ControlMutation.Add.prepare(ControlKind.SEAL, proposed) { id -> literal(ControlObligationFixtures.seal); set("id", ControlScalar.Text(id)) }
        val sealAdd = projectDependency(input(b = ControlCommandBody.Mutations(listOf(add)), adopted = null, id = "seal-add"))
        assertTrue("D2B6/6-2C.23c: sealAddUnknown", classifyDependency(sealAdd, protectedS) is DependencyIntersection.Unknown)
        // The DEMAND/RECOVERY_INTENT fixture in the same adoption-unknown state stays clear of a rotation's seal/witness/Applied.
        assertEquals("D2B6/6-2C.23d: nonSealClear", DependencyIntersection.Clear, classifyDependency(projectDependency(input(adopted = null)), protectedS))
        // First gap is non-SEAL, a later gap is broad: every gap is checked, not only the first reason.
        val multi = projectDependency(input(adopted = listOf(demandTarget("d"), null), termination = evidenceAbsent))
        assertTrue("D2B6/6-2C.23e: laterGapCounts", classifyDependency(multi, protectedS) is DependencyIntersection.Unknown)
        // Unknown whose known atoms already meet P is Present before any gap.
        val knownMeets = projectDependency(input(b = ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input(targets = listOf(node(
            NamespaceSettlementFixtures.user.replace("\"epoch\":\"u\"", "\"epoch\":\"old\""))))), adopted = emptyList(), id = NamespaceSettlementFixtures.operation))
        assertEquals("D2B6/6-2C.23f", DependencyIntersection.Present(sealS), classifyDependency(knownMeets, setOf(sealS)))
    }
    @Test fun T7_24_exactEvidenceAndSealsDescriptorProjectsItsFixedAtoms() {
        val rotLife = OwnerTrackingLifetimeId.issue()
        val input = NamespaceSettlementFixtures.input()
        val rot = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), rotLife)
        fun exact(ordered: List<String>) = TerminationPendingDescriptor.ExactEvidenceAndSeals(CompletionMode.Consumed, TerminationEntry.ConsumedRotation,
            TerminationClosures.of(rot).binding(), AppliedEvidence.Rotation(rot.id, rotLife.value, listOf("s"), input.demandId), rot.id, ordered)
        fun project(d: TerminationPendingDescriptor) = projectDependency(DependencyProjectionInput(rot.id, rotLife.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, rot.body), emptyList(), null, d))
        val deps = known("24a", project(exact(listOf("s"))))
        assertTrue("D2B6/6-2C.24a: appliedFromDescriptor", DependencyAtom.AppliedRow(rot.id, rotLife.value) in deps)
        assertTrue("D2B6/6-2C.24a: sealFromDescriptor", DependencyAtom.ControlRow(ControlKind.SEAL, "s") in deps)
        assertTrue("D2B6/6-2C.24a: witnessFromDescriptor", DependencyAtom.SealWitness("s", rot.id) in deps)
        // Descriptor ordered ids disagreeing with its expected Rotation: both read ids kept, conservative gap.
        val bad = project(exact(listOf("s", "x")))
        assertEquals(setOf(DependencyGapCause.TerminationDescriptorMismatch), causes("24b", bad))
        assertTrue("D2B6/6-2C.24b: extraIdKept", DependencyAtom.ControlRow(ControlKind.SEAL, "x") in (bad as DependencyProjection.Unknown).knownDependencies)
    }

    // ── r2 (measurement 6-2C1 r1): each rule alone ────────────────────────────────────────────────────────────
    @Test fun T7_25_eachExactDescriptorInconsistencyAloneIsAGap() {
        val rotLife = OwnerTrackingLifetimeId.issue()
        fun rotRef(input: RotateAndSettleNamespaces) = CommandRef(input.operationId, ControlCommandBody.RotateAndSettle(input), rotLife)
        val one = rotRef(NamespaceSettlementFixtures.input())
        val two = rotRef(NamespaceSettlementFixtures.input(targets = listOf(node(NamespaceSettlementFixtures.user), node(NamespaceSettlementFixtures.krx))))
        val demand = NamespaceSettlementFixtures.demandId
        fun project(body: CommandRef, d: TerminationPendingDescriptor) = projectDependency(DependencyProjectionInput(body.id, rotLife.value,
            RefView(ControlCommandLifecycle.TERMINATION_PENDING, body.body), emptyList(), null, d))
        fun exact(mode: CompletionMode = CompletionMode.Consumed, binding: TerminationClosureBinding = TerminationClosures.of(one).binding(),
            expectedIds: List<String> = listOf("s"), ordered: List<String> = listOf("s")) =
            TerminationPendingDescriptor.ExactEvidenceAndSeals(mode, TerminationEntry.ConsumedRotation, binding,
                AppliedEvidence.Rotation(one.id, rotLife.value, expectedIds, demand), one.id, ordered)
        known("25 consistent", project(one, exact()))
        val mismatch = setOf(DependencyGapCause.TerminationDescriptorMismatch)
        // ordered == body, expected differs: also keeps the expected-only id "y".
        val expectedOnly = project(one, exact(expectedIds = listOf("s", "y")))
        assertEquals(mismatch, causes("25a expectedVsOrdered", expectedOnly))
        assertTrue("D2B6/6-2C.25a: expectedIdKept", DependencyAtom.ControlRow(ControlKind.SEAL, "y") in (expectedOnly as DependencyProjection.Unknown).knownDependencies)
        // ordered == expected, body has another seal.
        assertEquals(mismatch, causes("25b bodyIds", project(two, exact())))
        assertEquals(mismatch, causes("25c mode", project(one, exact(mode = CompletionMode.NeverSubmitted))))
        // Binding of another command with this command's related scope: only the binding identity is wrong.
        val other = rotRef(NamespaceSettlementFixtures.input(op = "00000000-0000-0000-0000-000000000055", did = "00000000-0000-0000-0000-000000000056"))
        assertEquals(mismatch, causes("25d binding", project(one, exact(binding = TerminationClosures.of(one, command = other).binding()))))
    }
    @Test fun T7_26_classificationOfBroadAndSealGaps() {
        // No body on an open view: broad footprint reaches any protected seal/witness/Applied.
        assertTrue("D2B6/6-2C.26a: noBody", classifyDependency(projectDependency(input(b = null)), protectedS) is DependencyIntersection.Unknown)
        // An invalid rotation of seal "s" still may concern another seal "x": the SEAL wildcard, not only its known ids.
        val invalidRotation = projectDependency(input(b = ControlCommandBody.RotateAndSettle(NamespaceSettlementFixtures.input(targets = listOf(node(
            NamespaceSettlementFixtures.user.replace("\"epoch\":\"u\"", "\"epoch\":\"old\""))))), adopted = emptyList(), id = NamespaceSettlementFixtures.operation))
        val otherSeal = setOf(DependencyAtom.ControlRow(ControlKind.SEAL, "x"))
        assertTrue("D2B6/6-2C.26b: sealWildcard", classifyDependency(invalidRotation, otherSeal) is DependencyIntersection.Unknown)
        // A Mutations body with no actions and no adoption list: the footprint cannot be derived → Unclassified → Unknown(null).
        val empty = projectDependency(input(b = ControlCommandBody.Mutations(emptyList()), adopted = null, id = "empty-mutations"))
        assertEquals("D2B6/6-2C.26c: unclassifiedFailsClosed", DependencyIntersection.Unknown(null), classifyDependency(empty, protectedS))
    }
    @Test fun T7_27_journalFootprintsCompareExactly() {
        val k1 = JournalTargetV1("A", PurgeScope.USER, "u"); val k2 = JournalTargetV1("A", PurgeScope.USER, "u2")
        fun g(key: GapValue<JournalTargetV1>) = DependencyGap(DependencyGapCause.Unclassified, DependencyGapSource.Unclassified, setOf(DependencyAtomFootprint.Journal(key)))
        val p = setOf<DependencyAtom>(DependencyAtom.Journal(k2))
        assertTrue("D2B6/6-2C.27a: otherKey", !possibleIntersection(g(GapValue.Exact(k1)), p))
        assertTrue("D2B6/6-2C.27b: sameKey", possibleIntersection(g(GapValue.Exact(k2)), p))
    }

    // ── 6-3B2 (contracts-6-3B2): the Settlement pending descriptor, symmetric to T7_24/25 ────────────────────────
    private fun settlementRef(body: ControlCommandBody.Handover, opId: String, life: OwnerTrackingLifetimeId) = CommandRef(opId, body, life)
    private fun projectSettlement(ref: CommandRef, life: OwnerTrackingLifetimeId, d: TerminationPendingDescriptor) =
        projectDependency(DependencyProjectionInput(ref.id, life.value, RefView(ControlCommandLifecycle.TERMINATION_PENDING, ref.body), emptyList(), null, d))
    @Test fun T7_28_exactSettlementDescriptorProjectsItsFixedAtoms() {
        val life = OwnerTrackingLifetimeId.issue()
        val s = CurrentNullFixtures.both()
        val ref = settlementRef(ControlCommandBody.RotateAndSettleCurrentNull(s), s.operationId, life)
        val ids = s.targets.map { it.seal.id }
        val d = TerminationPendingDescriptor.ExactSettlementEvidenceAndSeals(CompletionMode.Consumed, TerminationEntry.ConsumedSettlement,
            TerminationClosures.of(ref).binding(), AppliedEvidence.Settlement(ref.id, life.value, HandoverSettlementTransition.CURRENT_NULL, ids, s.demandId),
            HandoverSettlementTransition.CURRENT_NULL, ref.id, ids)
        val deps = known("28", projectSettlement(ref, life, d))
        assertTrue("D2B6/6-3B2.28: appliedFromDescriptor", DependencyAtom.AppliedRow(ref.id, life.value) in deps)
        for (id in ids) {
            assertTrue("D2B6/6-3B2.28: seal $id", DependencyAtom.ControlRow(ControlKind.SEAL, id) in deps)
            assertTrue("D2B6/6-3B2.28: witness $id", DependencyAtom.SealWitness(id, ref.id) in deps)
        }
    }
    @Test fun T7_29_eachExactSettlementDescriptorInconsistencyAloneIsAGap() {
        val life = OwnerTrackingLifetimeId.issue()
        val one = RetiredNamespaceFixtures.spec()
        val ref = settlementRef(ControlCommandBody.SettleRetiredNamespace(one), one.operationId, life)
        val ids = listOf("s")
        fun exact(mode: CompletionMode = CompletionMode.Consumed, binding: TerminationClosureBinding = TerminationClosures.of(ref).binding(),
            expectedIds: List<String> = ids, ordered: List<String> = ids,
            transition: HandoverSettlementTransition = HandoverSettlementTransition.RETIRED_NAMESPACE,
            expectedTransition: HandoverSettlementTransition = HandoverSettlementTransition.RETIRED_NAMESPACE) =
            TerminationPendingDescriptor.ExactSettlementEvidenceAndSeals(mode, TerminationEntry.ConsumedSettlement, binding,
                AppliedEvidence.Settlement(ref.id, life.value, expectedTransition, expectedIds, one.demandId), transition, ref.id, ordered)
        known("29 consistent", projectSettlement(ref, life, exact()))
        val mismatch = setOf(DependencyGapCause.TerminationDescriptorMismatch)
        val expectedOnly = projectSettlement(ref, life, exact(expectedIds = listOf("s", "y")))
        assertEquals(mismatch, causes("29a expectedVsOrdered", expectedOnly))
        assertTrue("D2B6/6-3B2.29a: expectedIdKept", DependencyAtom.ControlRow(ControlKind.SEAL, "y") in (expectedOnly as DependencyProjection.Unknown).knownDependencies)
        val bodyTwo = settlementRef(ControlCommandBody.SettleRetiredNamespace(RetiredNamespaceFixtures.spec(target = node(NamespaceSettlementFixtures.krx))), one.operationId, life)
        assertEquals(mismatch, causes("29b bodyIds", projectSettlement(bodyTwo, life, exact())))
        assertEquals(mismatch, causes("29c mode", projectSettlement(ref, life, exact(mode = CompletionMode.NeverSubmitted))))
        // r2: each transition alone (descriptor only / expected only), with the typed source asserted.
        for ((name, d) in listOf("29d descriptorTransition" to exact(transition = HandoverSettlementTransition.RETIRED_NULL),
            "29d expectedTransition" to exact(expectedTransition = HandoverSettlementTransition.RETIRED_NULL),
            // measurement 6-3B2 r1: descriptor and expected agree with each other but not with the body.
            "29d bodyTransition" to exact(transition = HandoverSettlementTransition.RETIRED_NULL,
                expectedTransition = HandoverSettlementTransition.RETIRED_NULL))) {
            val p = projectSettlement(ref, life, d)
            assertEquals(mismatch, causes(name, p))
            assertEquals("D2B6/6-3B2.$name: source", DependencyGapSource.TerminationDescriptor, gap(name, p, DependencyGapCause.TerminationDescriptorMismatch).source)
        }
        // measurement 6-3B2 r1: the expected demand alone disagrees with the body; an ordered-only id is kept.
        val demandExpected = TerminationPendingDescriptor.ExactSettlementEvidenceAndSeals(CompletionMode.Consumed, TerminationEntry.ConsumedSettlement,
            TerminationClosures.of(ref).binding(), AppliedEvidence.Settlement(ref.id, life.value, HandoverSettlementTransition.RETIRED_NAMESPACE, ids,
                "00000000-0000-0000-0000-0000000000dd"), HandoverSettlementTransition.RETIRED_NAMESPACE, ref.id, ids)
        assertEquals(mismatch, causes("29f demand", projectSettlement(ref, life, demandExpected)))
        val orderedOnly = projectSettlement(ref, life, exact(ordered = listOf("s", "x")))
        assertEquals(mismatch, causes("29g orderedVsExpected", orderedOnly))
        assertTrue("D2B6/6-3B2.29g: orderedIdKept", DependencyAtom.ControlRow(ControlKind.SEAL, "x") in (orderedOnly as DependencyProjection.Unknown).knownDependencies)
        val other = settlementRef(ControlCommandBody.SettleRetiredNamespace(RetiredNamespaceFixtures.spec(op = "r-other-op")), "r-other-op", life)
        assertEquals(mismatch, causes("29e binding", projectSettlement(ref, life, exact(binding = TerminationClosures.of(ref, command = other).binding()))))
    }
}
