package com.jay.fxi.data.entitlements.control

import com.jay.fxi.data.entitlements.PendingPurge
import com.jay.fxi.data.entitlements.PurgeScope
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-1C T8 contract, part a (API r2 §2·§2.2·§4 rows typed key/duplicate/missing/disposition/journal and
 * §4.2 N shared keys; revision 06 §4.1·§4.2·§4.2.1). Pure only: slot coverage is judged on the original lists
 * (duplicates found before any set conversion), caller N/A is never accepted, FLOOR cannot be CompletedAndConsumed,
 * journal keys compare exactly (null is not a wildcard), and the N settlement's same-axis NULL and companion share one
 * null-epoch physical key while keeping every source/branch slot. Owner/latest/G05 checks are 6-4b. The implementation
 * thread reads but does not edit this file.
 */
class ObligationSlotContractTest {
    private val action0 = ObligationRole.MutationAction(0)
    private fun key(i: Int, component: ObligationComponent, branch: LandingBranch, role: ObligationRole = action0) =
        RequiredObligationKey(role, ObligationSubject.NamedEffect("op", i), component, branch)
    private fun req(k: RequiredObligationKey) = ExpectedSlot(k, SlotNecessity.Required)
    private fun na(k: RequiredObligationKey) = ExpectedSlot(k, SlotNecessity.NotRequiredByContract)
    private fun owned(k: RequiredObligationKey) = SubmittedSlot(k, ObligationDisposition.DurablyOwned)
    private fun rejected(id: String, result: CoverageResult, vararg problems: CoverageProblem) {
        assertTrue("D2B6/T8a.$id: rejected $result", result is CoverageResult.Rejected)
        for (p in problems) assertTrue("D2B6/T8a.$id: $p in $result", p in (result as CoverageResult.Rejected).problems)
    }
    private val reqL = key(0, ObligationComponent.REQUEST, LandingBranch.L)
    private val reqN = key(0, ObligationComponent.REQUEST, LandingBranch.N)
    private val expected = listOf(req(reqL), req(reqN))

    // ── typed key ──
    @Test fun T8a_01_componentBranchAndActionIndexAreDistinctKeys() {
        val floorL = key(0, ObligationComponent.FLOOR, LandingBranch.L)
        assertNotEquals("D2B6/T8a.01: componentDistinct", reqL, floorL)
        assertNotEquals("D2B6/T8a.01: branchDistinct", reqL, reqN)
        assertNotEquals("D2B6/T8a.01: actionIndexDistinct", reqL,
            key(0, ObligationComponent.REQUEST, LandingBranch.L, ObligationRole.MutationAction(1)))
        val four = listOf(reqL, reqN, floorL, key(0, ObligationComponent.FLOOR, LandingBranch.N))
        assertEquals("D2B6/T8a.01: fourSlotsFromOneTarget", CoverageResult.Complete,
            checkSlotCoverage(four.map(::req), four.map(::owned)))
    }

    // ── coverage ──
    @Test fun T8a_02_completeCoverage() {
        assertEquals("D2B6/T8a.02: complete", CoverageResult.Complete, checkSlotCoverage(expected, listOf(owned(reqL), owned(reqN))))
    }
    @Test fun T8a_03_duplicateSubmittedFoundBeforeSetConversion() {
        rejected("03", checkSlotCoverage(expected, listOf(owned(reqL), owned(reqL), owned(reqN))), CoverageProblem.DuplicateSubmitted)
    }
    @Test fun T8a_04_duplicateExpectedFoundBeforeSetConversion() {
        rejected("04", checkSlotCoverage(expected + req(reqL), listOf(owned(reqL), owned(reqN))), CoverageProblem.DuplicateExpected)
    }
    @Test fun T8a_05_missingBranches() {
        rejected("05a", checkSlotCoverage(expected, listOf(owned(reqL))), CoverageProblem.MissingN)
        rejected("05b", checkSlotCoverage(expected, listOf(owned(reqN))), CoverageProblem.MissingL)
    }
    @Test fun T8a_06_extraSubjectOrBranch() {
        val other = key(1, ObligationComponent.REQUEST, LandingBranch.L)
        rejected("06", checkSlotCoverage(expected, listOf(owned(reqL), owned(reqN), owned(other))), CoverageProblem.Extra)
    }
    @Test fun T8a_07_producerNotRequiredNeedsNoSubmission() {
        assertEquals("D2B6/T8a.07: producerNa", CoverageResult.Complete,
            checkSlotCoverage(listOf(req(reqL), na(reqN)), listOf(owned(reqL))))
    }
    @Test fun T8a_08_callerNotRequiredIsAlwaysRejected() {
        rejected("08a", checkSlotCoverage(expected, listOf(owned(reqL), SubmittedSlot(reqN, ObligationDisposition.NotRequiredByContract))),
            CoverageProblem.CallerNotRequired)
        // Even where the producer itself fixed N/A, the caller may not submit that disposition.
        rejected("08b", checkSlotCoverage(listOf(req(reqL), na(reqN)),
            listOf(owned(reqL), SubmittedSlot(reqN, ObligationDisposition.NotRequiredByContract))), CoverageProblem.CallerNotRequired)
    }
    @Test fun T8a_09_floorCannotBeCompletedOtherComponentsPassStructurally() {
        val fL = key(0, ObligationComponent.FLOOR, LandingBranch.L); val fN = key(0, ObligationComponent.FLOOR, LandingBranch.N)
        rejected("09a", checkSlotCoverage(listOf(req(fL), req(fN)),
            listOf(SubmittedSlot(fL, ObligationDisposition.CompletedAndConsumed), owned(fN))), CoverageProblem.CompletedForbidden)
        // Non-floor Completed passes the pure structural check; latest contradiction is the 6-4b owner's job.
        assertEquals("D2B6/T8a.09b: nonFloorCompletedStructural", CoverageResult.Complete, checkSlotCoverage(expected,
            listOf(SubmittedSlot(reqL, ObligationDisposition.CompletedAndConsumed), SubmittedSlot(reqN, ObligationDisposition.CompletedAndConsumed))))
    }

    @Test fun T8a_06b_extraAxisEpochOrBranch() {
        val j = RequiredObligationKey(action0, ObligationSubject.Journal("s", JournalTargetV1("A", PurgeScope.USER, null)), ObligationComponent.JOURNAL, LandingBranch.L)
        val onlyL = listOf(req(j))
        fun with(k: RequiredObligationKey) = checkSlotCoverage(onlyL, listOf(owned(j), owned(k)))
        rejected("06b1: epoch", with(j.copy(subject = ObligationSubject.Journal("s", JournalTargetV1("A", PurgeScope.USER, "u2")))), CoverageProblem.Extra)
        rejected("06b2: axis", with(j.copy(subject = ObligationSubject.Journal("s", JournalTargetV1("A", PurgeScope.CAPABILITY, null)))), CoverageProblem.Extra)
        rejected("06b3: branch", with(j.copy(branch = LandingBranch.N)), CoverageProblem.Extra)
    }

    // ── journal exact comparison ──
    private val keyUserNull = JournalTargetV1("A", PurgeScope.USER, null)
    @Test fun T8a_10_journalExactMatch() {
        assertEquals("D2B6/T8a.10: exact", TypedComparison.Matches,
            compareJournal(keyUserNull, listOf(PendingPurge("A", null, null, setOf(PurgeScope.USER)))))
    }
    @Test fun T8a_11_journalFieldDifferencesAreAbsent() {
        val absent = TypedComparison.Mismatch(JournalField.ABSENT)
        assertEquals("D2B6/T8a.11a: owner", absent, compareJournal(keyUserNull, listOf(PendingPurge("B", null, null, setOf(PurgeScope.USER)))))
        assertEquals("D2B6/T8a.11b: axis", absent, compareJournal(keyUserNull, listOf(PendingPurge("A", null, null, setOf(PurgeScope.CAPABILITY)))))
        assertEquals("D2B6/T8a.11c: epoch", absent, compareJournal(keyUserNull, listOf(PendingPurge("A", "u2", null, setOf(PurgeScope.USER)))))
        // null is not a wildcard either way.
        assertEquals("D2B6/T8a.11d: nullOwnerNotWildcard", absent,
            compareJournal(keyUserNull, listOf(PendingPurge(null, null, null, setOf(PurgeScope.USER)))))
        assertEquals("D2B6/T8a.11e: nullKeyEpochNotWildcard", absent,
            compareJournal(JournalTargetV1("A", PurgeScope.USER, "u2"), listOf(PendingPurge("A", null, null, setOf(PurgeScope.USER)))))
    }
    @Test fun T8a_12_journalExpandsScopesPerAxis() {
        val both = listOf(PendingPurge("A", "u", "k", setOf(PurgeScope.USER, PurgeScope.CAPABILITY)))
        assertEquals("D2B6/T8a.12a: userAxis", TypedComparison.Matches, compareJournal(JournalTargetV1("A", PurgeScope.USER, "u"), both))
        assertEquals("D2B6/T8a.12b: capabilityAxis", TypedComparison.Matches, compareJournal(JournalTargetV1("A", PurgeScope.CAPABILITY, "k"), both))
        assertEquals("D2B6/T8a.12c: crossAxisEpoch", TypedComparison.Mismatch(JournalField.ABSENT),
            compareJournal(JournalTargetV1("A", PurgeScope.USER, "k"), both))
    }

    // ── N settlement shared null-epoch key: 3 rows ──
    private val nWithCompanion = CurrentNullFixtures.spec(companions = listOf(node(CurrentNullFixtures.companionUser)))
    private val sharedKey = JournalTargetV1("A", PurgeScope.USER, null)
    @Test fun T8a_20_sharedKeyPositive() {
        val r = nSettlementJournalKeys(nWithCompanion)
        assertTrue("D2B6/T8a.20: available $r", r is NJournalKeysResult.Available)
        r as NJournalKeysResult.Available
        assertEquals("D2B6/T8a.20: onePhysicalKey", listOf(sharedKey), r.physicalKeys)
        assertEquals("D2B6/T8a.20: fourSlots", 4, r.slots.size)
        assertEquals("D2B6/T8a.20: slotsBySourceAndBranch",
            setOf(NJournalSlot("s", LandingBranch.L, sharedKey), NJournalSlot("s", LandingBranch.N, sharedKey),
                NJournalSlot("us", LandingBranch.L, sharedKey), NJournalSlot("us", LandingBranch.N, sharedKey)), r.slots.toSet())
        assertEquals("D2B6/T8a.20: journalMatches", TypedComparison.Matches,
            compareJournal(sharedKey, listOf(PendingPurge("A", null, null, setOf(PurgeScope.USER)))))
        assertTrue("D2B6/T8a.20: noCompanionOldEpochKey", r.physicalKeys.none { it.epoch == "u2" })
    }
    @Test fun T8a_21_companionOldEpochCannotReplaceSharedKey() {
        assertEquals("D2B6/T8a.21: oldEpochOnly", TypedComparison.Mismatch(JournalField.ABSENT),
            compareJournal(sharedKey, listOf(PendingPurge("A", "u2", null, setOf(PurgeScope.USER)))))
    }
    @Test fun T8a_21b_independentlyFixedFourSlotsCover() {
        // Expected slots written from the fixed input (s = USER NULL, us = companion), not from the function's output.
        fun k(seal: String, b: LandingBranch) = RequiredObligationKey(ObligationRole.Settlement(HandoverSettlementTransition.CURRENT_NULL),
            ObligationSubject.Journal(seal, sharedKey), ObligationComponent.JOURNAL, b)
        val fixed = listOf(k("s", LandingBranch.L), k("s", LandingBranch.N), k("us", LandingBranch.L), k("us", LandingBranch.N))
        val produced = (nSettlementJournalKeys(nWithCompanion) as NJournalKeysResult.Available).slots.map { k(it.sealId, it.branch) }
        assertEquals("D2B6/T8a.21b: sameSlotsAsFixed", fixed.toSet(), produced.toSet())
        assertEquals("D2B6/T8a.21b: cover", CoverageResult.Complete, checkSlotCoverage(fixed.map(::req), produced.map(::owned)))
    }
    @Test fun T8a_22_sharedKeyDoesNotHideAMissingSlot() {
        val slots = (nSettlementJournalKeys(nWithCompanion) as NJournalKeysResult.Available).slots
        fun k(s: NJournalSlot) = RequiredObligationKey(ObligationRole.Settlement(HandoverSettlementTransition.CURRENT_NULL),
            ObligationSubject.Journal(s.sealId, s.key), ObligationComponent.JOURNAL, s.branch)
        val expectedSlots = slots.map { ExpectedSlot(k(it), SlotNecessity.Required) }
        val companionL = slots.single { it.sealId == "us" && it.branch == LandingBranch.L }
        val companionN = slots.single { it.sealId == "us" && it.branch == LandingBranch.N }
        rejected("22a", checkSlotCoverage(expectedSlots, slots.filter { it != companionL }.map { owned(k(it)) }), CoverageProblem.MissingL)
        rejected("22b", checkSlotCoverage(expectedSlots, slots.filter { it != companionN }.map { owned(k(it)) }), CoverageProblem.MissingN)
        assertEquals("D2B6/T8a.22c: allSlots", CoverageResult.Complete, checkSlotCoverage(expectedSlots, slots.map { owned(k(it)) }))
    }

    // ── r7 (6-1C measurement r1): rows the r6 inputs walked past ──
    @Test fun T8a_08c_anySubmissionToAProducerNaSlotIsCallerNotRequired() {
        // API r2 §2: "산출기가 N/A로 둔 키에 대한 어떤 제출도 CallerNotRequired" — a DurablyOwned submission too.
        rejected("08c", checkSlotCoverage(listOf(req(reqL), na(reqN)), listOf(owned(reqL), owned(reqN))), CoverageProblem.CallerNotRequired)
    }
    @Test fun T8a_13_uninterpretableJournalInputIsInvalid() {
        // An empty identity/epoch or scope-less canonical entry cannot be interpreted; it is never reported as ABSENT.
        val invalid = TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT)
        val good = listOf(PendingPurge("A", null, null, setOf(PurgeScope.USER)))
        assertEquals("D2B6/T8a.13a: emptyKeyOwner", invalid, compareJournal(JournalTargetV1("", PurgeScope.USER, null), good))
        assertEquals("D2B6/T8a.13b: emptyKeyEpoch", invalid, compareJournal(JournalTargetV1("A", PurgeScope.USER, ""), good))
        assertEquals("D2B6/T8a.13c: emptyScopes", invalid, compareJournal(keyUserNull, listOf(PendingPurge("A", null, null, emptySet()))))
        assertEquals("D2B6/T8a.13d: emptyEntryOwner", invalid, compareJournal(keyUserNull, listOf(PendingPurge("", null, null, setOf(PurgeScope.USER)))))
        assertEquals("D2B6/T8a.13e: emptyUserEpoch", invalid, compareJournal(keyUserNull, listOf(PendingPurge("A", "", null, setOf(PurgeScope.USER)))))
        assertEquals("D2B6/T8a.13f: emptyKrxEpoch", invalid, compareJournal(keyUserNull, listOf(PendingPurge("A", null, "", setOf(PurgeScope.USER)))))
    }

    // N settlement rejections, one broken shape each. Reason mapping fixed by this contract: a count mismatch
    // (unreadable node) or no NULL target → INCOMPLETE_INPUT; wrong kind or an already-settled seal → INVALID_TARGET;
    // a repeated axis/id → SLOT_COLLISION; owner/axis/epoch disagreeing with `before` → SUBJECT_MISMATCH.
    private fun nUnavailable(id: String, reason: NJournalUnavailableReason, input: CurrentNullSettlement) =
        assertEquals("D2B6/T8a.$id: $reason", NJournalKeysResult.Unavailable(reason), nSettlementJournalKeys(input))
    private fun edited(json: String, old: String, new: String) = node(json.replace(old, new).also { check(it != json) { "fixture edit $old" } })
    private val unreadable = node("""{"id":"x"}""")
    @Test fun T8a_23_nIncompleteInput() {
        nUnavailable("23a: noNullTarget", NJournalUnavailableReason.INCOMPLETE_INPUT, CurrentNullFixtures.spec(nulls = emptyList()))
        nUnavailable("23b: unreadableNull", NJournalUnavailableReason.INCOMPLETE_INPUT,
            CurrentNullFixtures.spec(nulls = listOf(node(CurrentNullFixtures.nullUser), unreadable)))
        nUnavailable("23c: unreadableCompanion", NJournalUnavailableReason.INCOMPLETE_INPUT, CurrentNullFixtures.spec(companions = listOf(unreadable)))
    }
    @Test fun T8a_24_nInvalidTarget() {
        val t = NJournalUnavailableReason.INVALID_TARGET
        nUnavailable("24a: namespaceAsNull", t, CurrentNullFixtures.spec(target = node(CurrentNullFixtures.companionUser)))
        val settledNull = CurrentNullFixtures.spec(target = node(ControlObligationFixtures.settledSeal)).also { check(it.nulls.size == 1) }
        nUnavailable("24b: settledNull", t, settledNull)
        nUnavailable("24c: nullAsCompanion", t, CurrentNullFixtures.spec(companions = listOf(edited(CurrentNullFixtures.nullUser, "\"id\":\"s\"", "\"id\":\"s2\""))))
        val prior = """{"operationId":"prior-op","originLifetimeId":"life","operation":"BEGIN_ROTATION","before":{"ownerUid":"A","userAccessEpoch":"u2","krxCapabilityEpoch":"k2"},"after":{"ownerUid":"A","userAccessEpoch":"u9","krxCapabilityEpoch":"k2"},"journal":{"ownerUid":"A","axis":"USER","epoch":"u2"}}"""
        val settledCompanion = CurrentNullFixtures.spec(companions = listOf(node(CurrentNullFixtures.companionUser.dropLast(1) + ",\"settlement\":" + prior + "}")))
            .also { check(it.accompanying.size == 1) { "fixture: settled companion must be schema-valid" } }
        nUnavailable("24d: settledCompanion", t, settledCompanion)
    }
    @Test fun T8a_25_nSlotCollision() {
        val c = NJournalUnavailableReason.SLOT_COLLISION
        nUnavailable("25a: sameNullAxis", c, CurrentNullFixtures.spec(nulls = listOf(node(CurrentNullFixtures.nullUser),
            edited(CurrentNullFixtures.nullUser, "\"id\":\"s\"", "\"id\":\"s2\""))))
        nUnavailable("25b: sameSealId", c, CurrentNullFixtures.spec(companions = listOf(edited(CurrentNullFixtures.companionUser, "\"id\":\"us\"", "\"id\":\"s\""))))
        nUnavailable("25c: sameCompanionAxis", c, CurrentNullFixtures.spec(companions = listOf(node(CurrentNullFixtures.companionUser),
            edited(CurrentNullFixtures.companionUser, "\"id\":\"us\"", "\"id\":\"us2\""))))
    }
    @Test fun T8a_26_nSubjectMismatch() {
        val m = NJournalUnavailableReason.SUBJECT_MISMATCH
        nUnavailable("26a: nullOwner", m, CurrentNullFixtures.spec(target = edited(CurrentNullFixtures.nullUser, "\"ownerUid\":\"A\"", "\"ownerUid\":\"B\"")))
        nUnavailable("26b: companionAxisNotRotated", m, CurrentNullFixtures.spec(companions = listOf(node(CurrentNullFixtures.companionKrx))))
        nUnavailable("26c: companionEpoch", m, CurrentNullFixtures.spec(companions = listOf(edited(CurrentNullFixtures.companionUser, "\"epoch\":\"u2\"", "\"epoch\":\"u3\""))))
    }
}
