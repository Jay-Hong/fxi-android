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
}
