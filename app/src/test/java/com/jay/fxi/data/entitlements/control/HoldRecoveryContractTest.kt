package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.PurgeScope.CAPABILITY
import com.jay.fxi.data.entitlements.PurgeScope.USER
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.RefreshIntent.FORCE_ENTITLEMENTS
import com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM
import com.jay.fxi.data.entitlements.RefreshIntent.IF_STALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 5d contract (decision D2). Business expectations come from the design text
 * `d2b5_demand_auth_design_r3_codex.md` (line numbers in comments), not production outputs.
 * Source/executor/lifetime pins and USER-before-CAPABILITY order are agreed interface contracts.
 * The implementation thread reads this file but does not edit it; a change needs a reviewed
 * diff and a new SHA-256. Each negative fixture first states, with literals, that only its target
 * condition differs from the positive case.
 */
class HoldRecoveryContractTest {
    private fun eligible(id: String) = ControlLifecycleEvidenceFixtures.eligible(id)
    private fun atomic(id: String) = ControlLifecycleEvidenceFixtures.atomic(id)

    private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    private fun fence(owner: String?, user: String?, krx: String?) =
        """{"ownerUid":${q(owner)},"userAccessEpoch":${q(user)},"krxCapabilityEpoch":${q(krx)}}"""

    /**
     * PENDING (CAPABILITY only) or PREMIUM_REQUIRED (both axes); schema ties axes to the outcome.
     * [answered]=false drops answeredAs; then the schema no longer ties [boundOwner] to the fence owner (L279–282).
     */
    private fun queryHold(owner: String?, user: String?, krx: String?, intent: RefreshIntent, bothAxes: Boolean = false,
                          id: String = "h", answered: Boolean = true, boundOwner: String? = owner): ControlNode {
        fun identity(o: String?) = if (o == null) "null" else """{"ownerUid":"$o","authGeneration":2}"""
        val answeredAs = if (answered) identity(owner) else "null"
        val provenance = """{"kind":"QUERY","started":{"fence":${fence(owner, user, krx)},"generation":5,""" +
            """"boundIdentity":${identity(boundOwner)},"order":7,"binding":3,"intent":"${intent.name}","userInvalidations":0},"answeredAs":$answeredAs}"""
        val axes = if (bothAxes) """["USER","CAPABILITY"]""" else """["CAPABILITY"]"""
        val outcome = if (bothAxes) """{"kind":"PREMIUM_REQUIRED"}""" else """{"kind":"PENDING","krxVisible":false,"retryAfterSeconds":null}"""
        return ControlObligationFixtures.node(
            """{"id":"$id","originLifetimeId":"life","binding":3,"axes":$axes,"outcome":$outcome,"provenance":$provenance}""")
    }

    /** PREMIUM_REQUIRED (both axes) or KRX_ENTITLEMENT_REQUIRED (CAPABILITY only). Topic owner is never null. */
    private fun topicHold(owner: String, user: String?, krx: String?, bothAxes: Boolean): ControlNode {
        val provenance = """{"kind":"TOPIC","grant":9,"context":{"identity":{"ownerUid":"$owner","authGeneration":2},""" +
            """"access":${fence(owner, user, krx)},"generation":5}}"""
        val axes = if (bothAxes) """["USER","CAPABILITY"]""" else """["CAPABILITY"]"""
        val outcome = if (bothAxes) """{"kind":"PREMIUM_REQUIRED"}""" else """{"kind":"KRX_ENTITLEMENT_REQUIRED"}"""
        return ControlObligationFixtures.node(
            """{"id":"t","originLifetimeId":"life","binding":3,"axes":$axes,"outcome":$outcome,"provenance":$provenance}""")
    }

    private fun restored(node: ControlNode): RestoredHold {
        val hold = ControlSchema.read(ControlKind.HOLD, node)
        assertNotNull("fixture must be a schema-valid HOLD", hold)
        return hold as RestoredHold
    }
    private fun source(node: ControlNode): HoldRecoverySource {
        restored(node)
        return checkNotNull(HoldRecoverySource.from(node))
    }

    private val freshUser = "00000000-0000-0000-0000-000000000001"
    private val freshKrx = "00000000-0000-0000-0000-000000000002"
    private val fresh = RecoveryFreshEpochs(freshUser, freshKrx)

    private fun planned(id: String, result: RecoveryRetirementResult): RecoveryRetirementPlan {
        assertTrue(atomic(id), result is RecoveryRetirementResult.Planned)
        return (result as RecoveryRetirementResult.Planned).plan
    }
    private fun journal(owner: String?, axis: PurgeScope, epoch: String?) = PendingPurge(owner,
        epoch.takeIf { axis == USER }, epoch.takeIf { axis == CAPABILITY }, setOf(axis))
    private fun assertPlan(id: String, plan: RecoveryRetirementPlan, before: FenceV1, after: FenceV1,
                           axes: List<RecoveryAxisRetirement>, journal: List<PendingPurge>, request: RecoveryRequestRequirement) {
        assertEquals(atomic(id), before, plan.before)
        assertEquals(atomic(id), after, plan.after)
        assertEquals(atomic(id), axes, plan.axes)
        assertEquals(atomic(id), journal, plan.journal)
        assertEquals(atomic(id), request, plan.requestRequirement)
    }

    // ---- H01 / L402, L435: subject is the archived fence, never the current one ----

    /** answeredAs=null and a bound identity of another owner: only started.fence yields owner A. */
    @Test fun H01_querySubjectIsStartedFence() {
        val node = queryHold("A", "u0", "k0", FORCE_PREMIUM, answered = false, boundOwner = "Z")
        val query = restored(node).provenance as HoldProvenanceV1.Query
        assertEquals(FenceV1("A", "u0", "k0"), query.started.fence)
        assertNull(query.answeredAs)
        assertEquals("Z", query.started.boundIdentity?.ownerUid)
        val extracted = HoldRecoverySource.from(node)
        assertNotNull(eligible("H01_query_source"), extracted)
        val s = checkNotNull(extracted)
        assertEquals(eligible("H01_query"), FenceV1("A", "u0", "k0"), s.subject)
        assertEquals(eligible("H01_query"), setOf(CAPABILITY), s.axes)
    }

    @Test fun H01_topicSubjectIsContextAccess() {
        val node = topicHold("A", "u0", "k0", bothAxes = true)
        val hold = restored(node)
        assertEquals(FenceV1("A", "u0", "k0"), (hold.provenance as HoldProvenanceV1.Topic).context.access)
        val s = checkNotNull(HoldRecoverySource.from(node))
        assertEquals(eligible("H01_topic"), FenceV1("A", "u0", "k0"), s.subject)
        assertEquals(eligible("H01_topic"), setOf(USER, CAPABILITY), s.axes)
    }

    @Test fun H01_nullsAreExactValues() {
        val node = queryHold(null, null, "k0", IF_STALE)
        assertEquals(FenceV1(null, null, "k0"), (restored(node).provenance as HoldProvenanceV1.Query).started.fence)
        assertEquals(eligible("H01_nulls"), FenceV1(null, null, "k0"), checkNotNull(HoldRecoverySource.from(node)).subject)
    }

    @Test fun H01_nonHoldIsNotASource() {
        assertNull(eligible("H01_request"), HoldRecoverySource.from(ControlObligationFixtures.node(ControlObligationFixtures.request)))
        assertNull(eligible("H01_intent"), HoldRecoverySource.from(ControlObligationFixtures.node(ControlObligationFixtures.recovery)))
    }

    @Test fun H01_subjectMatchesOnlyTheArchivedTuple() {
        val hold = restored(queryHold("A", "u0", "k0", FORCE_PREMIUM, bothAxes = true))
        val exact = FenceV1("A", "u0", "k0"); val axes = setOf(USER, CAPABILITY)
        assertTrue(eligible("H01_positive"), HoldRecoveryBoundary.subjectMatches(hold, exact, axes))
        // Each negative changes exactly one element to a "current" value.
        assertFalse(eligible("H01a"), HoldRecoveryBoundary.subjectMatches(hold, exact.copy(ownerUid = "B"), axes))
        assertFalse(eligible("H01b"), HoldRecoveryBoundary.subjectMatches(hold, exact.copy(krxCapabilityEpoch = "k1"), axes))
        assertFalse(eligible("H01b_user"), HoldRecoveryBoundary.subjectMatches(hold, exact.copy(userAccessEpoch = "u1"), axes))
        assertFalse(eligible("H01c"), HoldRecoveryBoundary.subjectMatches(hold, exact, setOf(CAPABILITY)))
    }

    // ---- H07 / L421–431: REQUEST strength lower bound ----

    @Test fun H07b_userAxisNeedsForcePremium() {
        val hold = restored(queryHold("A", "u0", "k0", IF_STALE, bothAxes = true))
        assertEquals(setOf(USER, CAPABILITY), hold.axes)
        assertEquals(IF_STALE, (hold.provenance as HoldProvenanceV1.Query).started.intent)
        assertEquals(atomic("H07b"), FORCE_PREMIUM, HoldRecoveryBoundary.minimumIntent(hold))
        assertFalse(atomic("H07b"), HoldRecoveryBoundary.intentSatisfies(hold, FORCE_ENTITLEMENTS))
        assertTrue(atomic("H07b_positive"), HoldRecoveryBoundary.intentSatisfies(hold, FORCE_PREMIUM))
    }

    @Test fun H07c_capabilityQueryRejectsIfStale() {
        val hold = restored(queryHold("A", "u0", "k0", IF_STALE))
        assertEquals(setOf(CAPABILITY), hold.axes)
        assertEquals(atomic("H07c"), FORCE_ENTITLEMENTS, HoldRecoveryBoundary.minimumIntent(hold))
        assertFalse(atomic("H07c"), HoldRecoveryBoundary.intentSatisfies(hold, IF_STALE))
    }

    /** L431 A2: CAPABILITY-only Pending whose original query was FORCE_PREMIUM must stay FORCE_PREMIUM. */
    @Test fun H07e_pendingCapabilityKeepsOriginalForcePremium() {
        val hold = restored(queryHold("A", "u0", "k0", FORCE_PREMIUM))
        assertEquals(setOf(CAPABILITY), hold.axes)
        assertEquals(HoldOutcomeKind.PENDING, hold.outcome.kind)
        assertEquals(FORCE_PREMIUM, (hold.provenance as HoldProvenanceV1.Query).started.intent)
        assertEquals(atomic("H07e"), FORCE_PREMIUM, HoldRecoveryBoundary.minimumIntent(hold))
        assertFalse(atomic("H07e"), HoldRecoveryBoundary.intentSatisfies(hold, FORCE_ENTITLEMENTS))
        assertTrue(atomic("H07e_positive"), HoldRecoveryBoundary.intentSatisfies(hold, FORCE_PREMIUM))
    }

    @Test fun H07f_topicCapabilityUsesAxisRuleOnly() {
        val hold = restored(topicHold("A", "u0", "k0", bothAxes = false))
        assertEquals(setOf(CAPABILITY), hold.axes)
        assertEquals(atomic("H07f"), FORCE_ENTITLEMENTS, HoldRecoveryBoundary.minimumIntent(hold))
        assertFalse(atomic("H07f"), HoldRecoveryBoundary.intentSatisfies(hold, IF_STALE))
        assertTrue(atomic("H07f_positive"), HoldRecoveryBoundary.intentSatisfies(hold, FORCE_ENTITLEMENTS))
    }

    @Test fun topicUserAxisNeedsForcePremium() {
        val hold = restored(topicHold("A", "u0", "k0", bothAxes = true))
        assertEquals(setOf(USER, CAPABILITY), hold.axes)
        assertEquals(atomic("H07_topicUser"), FORCE_PREMIUM, HoldRecoveryBoundary.minimumIntent(hold))
        assertFalse(atomic("H07_topicUser"), HoldRecoveryBoundary.intentSatisfies(hold, FORCE_ENTITLEMENTS))
    }

    /** L625 h: original IF_STALE or FORCE_ENTITLEMENTS on a CAPABILITY-only query lifts to FORCE_ENTITLEMENTS. */
    @Test fun H07h_capabilityQueryFloorIsForceEntitlements() {
        for (original in listOf(IF_STALE, FORCE_ENTITLEMENTS)) {
            val hold = restored(queryHold("A", "u0", "k0", original))
            assertEquals(original, (hold.provenance as HoldProvenanceV1.Query).started.intent)
            assertEquals(atomic("H07h_$original"), FORCE_ENTITLEMENTS, HoldRecoveryBoundary.minimumIntent(hold))
            assertTrue(atomic("H07h_$original"), HoldRecoveryBoundary.intentSatisfies(hold, FORCE_ENTITLEMENTS))
        }
    }

    /** L427, L685: pure lower bound only; 5d has no intent writer. */
    @Test fun H07g_recoveryIntentLowerBound() {
        val cap = RecoveryIntentV1("r", "session", "A", CAPABILITY, null)
        val user = RecoveryIntentV1("r", "session", "A", USER, null)
        assertEquals(atomic("H07g"), FORCE_ENTITLEMENTS, RecoveryIntentLowerBound.minimumIntent(cap))
        assertEquals(atomic("H07g_user"), FORCE_PREMIUM, RecoveryIntentLowerBound.minimumIntent(user))
        assertFalse(atomic("H07g"), RecoveryIntentLowerBound.intentSatisfies(cap, IF_STALE))
        assertTrue(atomic("H07g_positive"), RecoveryIntentLowerBound.intentSatisfies(cap, FORCE_ENTITLEMENTS))
        assertFalse(atomic("H07g_user"), RecoveryIntentLowerBound.intentSatisfies(user, FORCE_ENTITLEMENTS))
    }

    // ---- H04 / L412–417: six source-to-current relations (CAPABILITY-only source unless stated) ----

    @Test fun H04a_currentOwnerNonNullTargetRotates() {
        val s = source(queryHold("A", "u0", "k0", FORCE_PREMIUM))
        val before = FenceV1("A", "uX", "k0") // owner equal, CAPABILITY target == current
        assertPlan("H04a", planned("H04a", planRecoveryRetirement(s, before, fresh)), before,
            after = FenceV1("A", "uX", freshKrx),
            axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", CAPABILITY, "k0"), RecoveryAxisAction.Rotate(freshKrx))),
            journal = listOf(journal("A", CAPABILITY, "k0")), request = RecoveryRequestRequirement.RequiredCurrentOwner)
    }

    @Test fun H04b_currentOwnerNullTargetRotatesWhateverTheCurrentEpoch() {
        val s = source(queryHold("A", "u0", null, FORCE_PREMIUM))
        for (current in listOf("kNow", null)) {
            val before = FenceV1("A", "uX", current)
            assertPlan("H04b_$current", planned("H04b_$current", planRecoveryRetirement(s, before, fresh)), before,
                after = FenceV1("A", "uX", freshKrx),
                axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", CAPABILITY, null), RecoveryAxisAction.Rotate(freshKrx))),
                journal = listOf(journal("A", CAPABILITY, null)), request = RecoveryRequestRequirement.RequiredCurrentOwner)
        }
    }

    @Test fun H04c_retiredTargetPreservesNamespace() {
        val s = source(queryHold("A", "u0", "k0", FORCE_PREMIUM))
        val same = FenceV1("A", "uX", "k1") // both nonnull and different
        assertPlan("H04c", planned("H04c", planRecoveryRetirement(s, same, fresh)), same, after = same,
            axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", CAPABILITY, "k0"), RecoveryAxisAction.Preserve)),
            journal = listOf(journal("A", CAPABILITY, "k0")), request = RecoveryRequestRequirement.RequiredCurrentOwner)
        val departed = FenceV1("B", "uX", "k1") // L414: REQUEST only when subjectOwner = raw.owner
        assertPlan("H04c_departed", planned("H04c_departed", planRecoveryRetirement(s, departed, fresh)), departed, after = departed,
            axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", CAPABILITY, "k0"), RecoveryAxisAction.Preserve)),
            journal = listOf(journal("A", CAPABILITY, "k0")), request = RecoveryRequestRequirement.NotRequiredDepartedOwner)
    }

    @Test fun H04d_departedOwnerNullTargetPreservesAndAddsNoRequest() {
        val s = source(queryHold("A", "u0", null, FORCE_PREMIUM))
        val before = FenceV1("B", "uX", "kNow")
        assertPlan("H04d", planned("H04d", planRecoveryRetirement(s, before, fresh)), before, after = before,
            axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", CAPABILITY, null), RecoveryAxisAction.Preserve)),
            journal = listOf(journal("A", CAPABILITY, null)), request = RecoveryRequestRequirement.NotRequiredDepartedOwner)
    }

    /** L416: owner difference is not retirement evidence. */
    @Test fun H04e_departedOwnerSameNonNullEpochIsRejected() {
        val s = source(queryHold("A", "u0", "k0", FORCE_PREMIUM))
        val result = planRecoveryRetirement(s, FenceV1("B", "uX", "k0"), fresh)
        assertTrue(eligible("H04e"), result is RecoveryRetirementResult.Failed)
        assertFalse("CLASSIFICATION_ONLY/H04e: unexpected recovery-required result",
            (result as RecoveryRetirementResult.Failed).problem is HoldRecoveryProblem.RecoveryRequired)
    }

    /** L417: absence is not retirement evidence. */
    @Test fun H04f_nonNullTargetWithNullCurrentNeedsRecovery() {
        val s = source(queryHold("A", "u0", "k0", FORCE_PREMIUM))
        for (owner in listOf("A", "B")) {
            val result = planRecoveryRetirement(s, FenceV1(owner, "uX", null), fresh)
            assertTrue(eligible("H04f_$owner"), result is RecoveryRetirementResult.Failed)
            assertEquals("CLASSIFICATION_ONLY/H04f_$owner: expected UnreadableEpochState",
                RecoveryRetirementResult.Failed(HoldRecoveryProblem.RecoveryRequired(RecoveryReason.UnreadableEpochState)),
                result)
        }
    }

    /** L439: a null owner is an exact value, equal only to a null current owner. */
    @Test fun nullOwnerIsExact() {
        val s = source(queryHold(null, "u0", "k0", IF_STALE))
        val before = FenceV1(null, "uX", "k0")
        assertPlan("H04a_nullOwner", planned("H04a_nullOwner", planRecoveryRetirement(s, before, fresh)), before,
            after = FenceV1(null, "uX", freshKrx),
            axes = listOf(RecoveryAxisRetirement(JournalTargetV1(null, CAPABILITY, "k0"), RecoveryAxisAction.Rotate(freshKrx))),
            journal = listOf(journal(null, CAPABILITY, "k0")), request = RecoveryRequestRequirement.RequiredCurrentOwner)
        assertTrue(eligible("H04e_nullOwner"), planRecoveryRetirement(s, FenceV1("A", "uX", "k0"), fresh) is RecoveryRetirementResult.Failed)
    }

    /** L443, L685: every source axis is handed over in one plan; USER then CAPABILITY. */
    @Test fun bothAxesAreRetiredTogether() {
        val s = source(queryHold("A", "u0", "k0", IF_STALE, bothAxes = true))
        val before = FenceV1("A", "u0", "k1") // USER: relation 1 (rotate); CAPABILITY: relation 3 (preserve)
        assertPlan("bothAxes", planned("bothAxes", planRecoveryRetirement(s, before, fresh)), before,
            after = FenceV1("A", freshUser, "k1"),
            axes = listOf(
                RecoveryAxisRetirement(JournalTargetV1("A", USER, "u0"), RecoveryAxisAction.Rotate(freshUser)),
                RecoveryAxisRetirement(JournalTargetV1("A", CAPABILITY, "k0"), RecoveryAxisAction.Preserve)),
            journal = listOf(journal("A", USER, "u0"), journal("A", CAPABILITY, "k0")),
            request = RecoveryRequestRequirement.RequiredCurrentOwner)
    }

    /** L408, L421: an axis outside the source is neither planned nor changed. */
    @Test fun axisOutsideSourceIsUntouched() {
        val s = source(queryHold("A", "u0", "k0", FORCE_PREMIUM))
        val before = FenceV1("A", "u0", "k0")
        val plan = planned("outsideAxis", planRecoveryRetirement(s, before, fresh))
        assertEquals(atomic("outsideAxis"), "u0", plan.after.userAccessEpoch)
        assertEquals(atomic("outsideAxis"), listOf(CAPABILITY), plan.axes.map { it.target.axis })
    }

    /** L412–417: the same table applies to a TOPIC source (context.access). */
    @Test fun topicSourceUsesTheSameTable() {
        val s = source(topicHold("A", "u0", "k0", bothAxes = false))
        val before = FenceV1("A", "uX", "k0")
        assertPlan("H04a_topic", planned("H04a_topic", planRecoveryRetirement(s, before, fresh)), before,
            after = FenceV1("A", "uX", freshKrx),
            axes = listOf(RecoveryAxisRetirement(JournalTargetV1("A", CAPABILITY, "k0"), RecoveryAxisAction.Rotate(freshKrx))),
            journal = listOf(journal("A", CAPABILITY, "k0")), request = RecoveryRequestRequirement.RequiredCurrentOwner)
    }

    // ---- H06 / L438: journal field representability ----

    @Test fun H06_journalFieldRepresentable() {
        assertTrue(eligible("H06_null"), RecoveryRetirementBoundary.journalFieldRepresentable(null))
        assertTrue(eligible("H06_positive"), RecoveryRetirementBoundary.journalFieldRepresentable(freshKrx))
        assertFalse(eligible("H06a"), RecoveryRetirementBoundary.journalFieldRepresentable(""))
        assertFalse(eligible("H06b"), RecoveryRetirementBoundary.journalFieldRepresentable("a|b"))
        assertFalse(eligible("H06c"), RecoveryRetirementBoundary.journalFieldRepresentable("a\nb"))
        assertFalse(eligible("H06d"), RecoveryRetirementBoundary.journalFieldRepresentable("a\uD800b"))
    }

    // ---- L443, L634: the plan validator re-derives from source/before/UUIDs, not from the plan ----

    @Test fun validPlanRederivesEveryField() {
        val s = source(queryHold("A", "u0", "k0", FORCE_PREMIUM))
        val before = FenceV1("A", "uX", "k0")
        val rotate = RecoveryAxisRetirement(JournalTargetV1("A", CAPABILITY, "k0"), RecoveryAxisAction.Rotate(freshKrx))
        val good = RecoveryRetirementPlan(before, FenceV1("A", "uX", freshKrx), listOf(rotate),
            listOf(journal("A", CAPABILITY, "k0")), RecoveryRequestRequirement.RequiredCurrentOwner)
        assertTrue(atomic("validPlan_positive"), RecoveryRetirementBoundary.validPlan(s, before, fresh, good))
        fun bad(id: String, plan: RecoveryRetirementPlan) =
            assertFalse(atomic(id), RecoveryRetirementBoundary.validPlan(s, before, fresh, plan))
        bad("validPlan_noRotation", RecoveryRetirementPlan(before, before, listOf(rotate),
            listOf(journal("A", CAPABILITY, "k0")), RecoveryRequestRequirement.RequiredCurrentOwner))
        bad("validPlan_preserveAction", RecoveryRetirementPlan(before, FenceV1("A", "uX", freshKrx),
            listOf(rotate.copy(action = RecoveryAxisAction.Preserve)), listOf(journal("A", CAPABILITY, "k0")),
            RecoveryRequestRequirement.RequiredCurrentOwner))
        bad("validPlan_noJournal", RecoveryRetirementPlan(before, FenceV1("A", "uX", freshKrx), listOf(rotate),
            emptyList(), RecoveryRequestRequirement.RequiredCurrentOwner))
        bad("validPlan_broaderJournal", RecoveryRetirementPlan(before, FenceV1("A", "uX", freshKrx), listOf(rotate),
            listOf(PendingPurge("A", null, null, setOf(CAPABILITY))), RecoveryRequestRequirement.RequiredCurrentOwner))
        bad("validPlan_noRequest", RecoveryRetirementPlan(before, FenceV1("A", "uX", freshKrx), listOf(rotate),
            listOf(journal("A", CAPABILITY, "k0")), RecoveryRequestRequirement.NotRequiredDepartedOwner))
        bad("validPlan_extraAxis", RecoveryRetirementPlan(before, FenceV1("A", freshUser, freshKrx),
            listOf(RecoveryAxisRetirement(JournalTargetV1("A", USER, "u0"), RecoveryAxisAction.Rotate(freshUser)), rotate),
            listOf(journal("A", USER, "u0"), journal("A", CAPABILITY, "k0")), RecoveryRequestRequirement.RequiredCurrentOwner))
        bad("validPlan_ownerChanged", RecoveryRetirementPlan(before, FenceV1("B", "uX", freshKrx), listOf(rotate),
            listOf(journal("A", CAPABILITY, "k0")), RecoveryRequestRequirement.RequiredCurrentOwner))
    }

    // ---- H02 / L404, L436: closure — entries closed, work joined, no new registration ----

    private val executor = SettlementExecutor("A", 3, LifetimeId("new-life"))
    private val binding = LifecycleBinding(executor, IdentityV1("A", 2), startedOrder = 1, startEventId = "start")

    private fun sameProcess(node: ControlNode, entriesClosed: Boolean = true, captured: Set<String> = setOf("w1"),
                            joined: Set<String> = setOf("w1"), exec: SettlementExecutor = executor) =
        HoldRecoveryClosure.SameProcess(node, exec, generationAtCapture = 5, entriesClosed = entriesClosed,
            capturedWork = captured, joinedWork = joined)
    private fun runtime(closure: HoldRecoveryClosure, generation: Long = 5, entriesClosed: Boolean = true,
                        registered: Set<String> = setOf("w1")) =
        HoldRecoveryRuntime(binding, generation, entriesClosed, registered, closure)

    @Test fun H02_sameProcessClosure() {
        val node = queryHold("A", "u0", "k0", FORCE_PREMIUM)
        restored(node)
        fun problem(fixed: HoldRecoveryClosure, rt: HoldRecoveryRuntime) =
            HoldRecoveryBoundary.closureProblem(node, fixed, rt, "track-2")
        val fixed = sameProcess(node)
        assertNull(eligible("H02_positive"), problem(fixed, runtime(fixed)))
        assertNotNull(eligible("H02a_capture"), problem(sameProcess(node, entriesClosed = false), runtime(sameProcess(node, entriesClosed = false))))
        assertNotNull(eligible("H02a_runtime"), problem(fixed, runtime(fixed, entriesClosed = false)))
        assertNotNull(eligible("H02b"), problem(sameProcess(node, joined = emptySet()), runtime(sameProcess(node, joined = emptySet()))))
        assertNotNull(eligible("H02b_newWork"), problem(fixed, runtime(fixed, registered = setOf("w1", "w2"))))
        assertNotNull(eligible("H02_generation"), problem(fixed, runtime(fixed, generation = 6)))
        val other = queryHold("A", "u0", "k0", FORCE_PREMIUM, id = "other")
        restored(other)
        assertNotNull(eligible("H02_sourcePin"), problem(sameProcess(other), runtime(sameProcess(other))))
        val otherExec = SettlementExecutor("A", 4, LifetimeId("new-life"))
        assertNotNull(eligible("H02_executorPin"), problem(sameProcess(node, exec = otherExec), runtime(sameProcess(node, exec = otherExec))))
    }

    @Test fun H02_afterRestartClosure() {
        val node = queryHold("A", "u0", "k0", FORCE_PREMIUM)
        restored(node)
        fun restart(caller: Boolean = true, storage: Boolean = true, previous: String = "track-1") =
            HoldRecoveryClosure.AfterRestart(node, executor, previous, caller, storage)
        fun problem(fixed: HoldRecoveryClosure, entriesClosed: Boolean = true) =
            HoldRecoveryBoundary.closureProblem(node, fixed, runtime(fixed, entriesClosed = entriesClosed, registered = emptySet()), "track-2")
        assertNull(eligible("H02_restart_positive"), problem(restart()))
        assertNotNull(eligible("H02_restart_caller"), problem(restart(caller = false)))
        assertNotNull(eligible("H02_restart_storage"), problem(restart(storage = false)))
        assertNotNull(eligible("H02_restart_sameLifetime"), problem(restart(previous = "track-2")))
        assertNotNull(eligible("H02_restart_entries"), problem(restart(), entriesClosed = false))
    }
}
