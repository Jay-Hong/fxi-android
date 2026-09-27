package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.KRX_EPOCH
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.OWNER_UID
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.PURGE_JOURNAL
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore.Companion.USER_EPOCH
import com.jay.fxi.data.entitlements.EntitlementsOutcome
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude-owned 6-4bA2 contract: G05 of the completion handoff (T3), pure over (exact command, A1 derivation, handoff,
 * termination closure, latest record read, boot reading). Fixed inputs: 6-4bA2 declaration r2 (6-4bA2_decl_codex.r2.md §1),
 * 6-4bA2 consensus (fail-closed completedConflict rules 0–11, 6-4bA2_consensus.md), 6-4bA2 recipes r1 (failure fill table,
 * latest fixtures (a)–(j), command recipes, non-isolable cases). Expected failure lists are literal constructors here.
 *
 * Failure order: REF → REQUIREMENTS → DUPLICATE → COVERAGE_L → COVERAGE_N → EXTRA → SUBJECT → DESTINATION → CONFIRMATION →
 * LOWER_BOUND → COMPLETED_CONFLICT → OWNER; within one id A1 slot order, then submitted index; within one slot of
 * COMPLETED_CONFLICT the allowed-rule violation (actualAt=null) precedes the actual contradiction.
 * F(k) = Fixed(first fixedSources location of the A1 slot k). A1 output is used as given (A1 contracts pin it).
 *
 * Decisions this contract fixes beyond the recipe text (flagged for review):
 *  - G00: when latest is not Supported (Unreadable / MigrationOrRecoveryRequired) absence cannot be shown, so every submitted
 *    CompletedAndConsumed slot is COMPLETED_CONFLICT with actualAt=ActualMetadata (rule 0); a handoff with no slot is Accepted.
 *  - G02b: REQUIREMENTS does not suppress OWNER (OWNER does not depend on the derivation).
 *  - G01c: REF suppresses OWNER (owner binding cannot be judged for an unidentified command).
 *  - G09b–G09f: a DurablyOwned destination is the slot's own row (no link chain in A2); a wrong row found by the locator is
 *    DESTINATION with actualAt at that row, an absent row has actualAt=null.
 *  - G10b: LOWER_BOUND (compare* not Matches, actualAt at the destination row) is judged only when DESTINATION passes;
 *    a matching destination (G09, G09d–f first cases) records no LOWER_BOUND.
 *  - G00b/G11b (rule 0): an opaque row whose id is unreadable in a slot's kind array is an actual contradiction for every
 *    completed slot of that kind, including FLOOR after its allowed-rule violation; one entry per (slot, actual location).
 * A2 accepts no DurablyOwned (no PriorStorageConfirmation instance exists). The implementation thread reads but does not edit
 * this file.
 */
class RequiredObligationsCompletionHandoffContractTest {
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val now = BootReading("boot", 20_000)
    private val F = DemandAuthFixtures
    private val H = HoldRecoveryFixtures
    private var seed = 0L
    private fun uuid() = UUID(0L, ++seed)

    // ── case: exact command + A1 derivation ────────────────────────────────────────────────────────────────────────────
    private class Case(val ref: CommandRef, input: RequirementInput) {
        val a1: RequirementDerivation.Available = deriveRequiredObligations(input).let {
            assertTrue("fixture: A1 must be Available, got $it", it is RequirementDerivation.Available)
            it as RequirementDerivation.Available
        }
        val required = a1.orderedSlots.filter { it.requirement is SlotRequirement.Required }
        fun key(p: (RequiredObligationKey) -> Boolean) = a1.orderedSlots.single { p(it.key) }.key
        fun at(k: RequiredObligationKey) = required.indexOfFirst { it.key == k }.also { check(it >= 0) { "fixture: $k" } }
    }
    private fun lifecycle(ref: CommandRef) = Case(ref, RequirementInput.Lifecycle(ref, ref.body as ControlCommandBody.Lifecycle))

    // ── handoff / assess ───────────────────────────────────────────────────────────────────────────────────────────────
    private fun completed(k: RequiredObligationKey, subject: ObligationSubject = k.subject) =
        SlotHandoff(k, HandoffDisposition.CompletedAndConsumed(ComponentCompletion(subject), emptyList()))
    private fun owned(k: RequiredObligationKey, d: DestinationLocator) =
        SlotHandoff(k, HandoffDisposition.DurablyOwned(d, emptyList(), null))
    private fun all(c: Case) = c.required.map { completed(it.key) }
    private fun handoff(c: Case, slots: List<SlotHandoff> = all(c),
        owner: ResponsibilityOwner = ResponsibilityOwner(c.ref.ownerTrackingLifetimeId, "owner-1"),
        command: ExactCommandBinding = c.a1.commandBinding) = CompletionHandoff(command, owner, slots)
    private fun assess(c: Case, latest: ControlRecordRead, h: CompletionHandoff = handoff(c),
        closure: TerminationClosure = TerminationClosures.of(c.ref), exact: CommandRef = c.ref,
        derivation: RequirementDerivation = c.a1) = assessG05(exact, derivation, h, closure, latest, now)

    private fun accepted(r: G05Result) = assertEquals(G05Result.Accepted, r)
    /** Positive-row precondition: every required slot admits completion (a DURABLY_OWNED_ONLY slot would reject). */
    private fun completable(c: Case) = assertTrue("fixture: a required slot forbids completion",
        c.required.none { (it.requirement as SlotRequirement.Required).allowed == AllowedSlotDisposition.DURABLY_OWNED_ONLY })
    private fun rejected(r: G05Result, expected: List<G05Failure>) {
        assertTrue("expected Rejected, got $r", r is G05Result.Rejected)
        r as G05Result.Rejected
        assertEquals(expected, r.failures)
        assertTrue(runCatching { (r.failures as MutableList<G05Failure>).clear() }.isFailure)
    }

    // ── expected failures (recipe §1 fill table) ───────────────────────────────────────────────────────────────────────
    private fun fixed(c: Case, k: RequiredObligationKey) = G05Location.Fixed(
        (c.a1.orderedSlots.single { it.key == k }.requirement as SlotRequirement.Required).fixedSources.first().location)
    private fun sub(i: Int) = G05Location.Submitted(i)
    private fun payload(kind: ControlKind, i: Int = 0) = G05Location.ActualPayload(kind, i)
    private val meta = G05Location.ActualMetadata
    private fun slotFailure(id: G05Id, c: Case, k: RequiredObligationKey, i: Int?, actual: G05Location? = null) =
        G05Failure(id, k, fixed(c, k), i?.let(::sub), actual)
    private fun cc(c: Case, k: RequiredObligationKey, actual: G05Location?, i: Int = c.at(k)) =
        slotFailure(G05Id.COMPLETED_CONFLICT, c, k, i, actual)

    // ── latest fixtures ────────────────────────────────────────────────────────────────────────────────────────────────
    private fun empty() = ControlLifecycleEvidenceFixtures.read()
    private fun record(demand: String = "[]", hold: String = "[]", edit: androidx.datastore.preferences.core.MutablePreferences.() -> Unit = {}) =
        ControlLifecycleEvidenceFixtures.read(ControlLifecycleEvidenceFixtures.raw(demand = demand, hold = hold)
            .toMutablePreferences().apply(edit).toPreferences())
    /** A row-rule fixture must not be decidable by rule 0 alone: every control row in it is interpretable. */
    private fun <T : ControlRecordRead.Supported> interpretable(latest: T): T =
        latest.also { assertFalse("fixture: rows must be interpretable (else rule 0 decides)", it.hasUninterpretable) }
    private fun rows(vararg nodes: ControlNode) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }

    // ═══ R: RETIRED_NAMESPACE settlement — S^L S^N J^L J^N Q^L Q^N (Q named, source=null, requiredId "r-demand") ════════
    private val R = RetiredNamespaceFixtures.spec()
    private fun rCase(s: RetiredNamespaceSettlement = R): Case {
        val ref = RetiredNamespaceFixtures.command(s)
        return Case(ref, RequirementInput.Settlement(ref, ref.body as ControlCommandBody.Handover))
    }
    private fun settled(s: RetiredNamespaceSettlement = R, w: SettlementEvidenceV1 = RetiredNamespaceFixtures.witness(s)) =
        NamespaceSettlementFixtures.withWitness(NamespaceSettlementFixtures.user, w)
    private val active get() = ControlObligationFixtures.node(NamespaceSettlementFixtures.user)
    private fun rRecord(seal: ControlNode?, demand: String = "[]", journal: String? = null,
        s: RetiredNamespaceSettlement = R): ControlRecordRead.Supported = RetiredNamespaceFixtures.read(
        RetiredNamespaceFixtures.raw(s).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)] = if (seal == null) "[]" else NamespaceSettlementFixtures.jsonArray(seal)
            this[ControlRecordKeys.payload(ControlKind.DEMAND)] = demand
            if (journal != null) this[PURGE_JOURNAL] = journal
        }.toPreferences())
    private fun Case.sealKey(b: LandingBranch) = key { it.component == ObligationComponent.SEAL && it.branch == b }
    private fun Case.journalKey(b: LandingBranch) = key { it.component == ObligationComponent.JOURNAL && it.branch == b }
    private fun Case.requestKey(b: LandingBranch) = key { it.component == ObligationComponent.REQUEST && it.branch == b }

    // ═══ positives ═════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P01_R_allCompleted_exactSettledSeal_noJournal_noRequest_accepted() {
        val c = rCase()
        assertEquals("fixture: six required slots", 6, c.required.size)
        completable(c)
        accepted(assess(c, rRecord(settled())))
    }

    @Test fun P02_R_allCompleted_sealAbsent_accepted() {
        val c = rCase()
        completable(c)
        accepted(assess(c, rRecord(null)))
    }

    @Test fun P03_emptyGuardAdd_noRequiredSlot_emptyHandoff_accepted() {
        val c = addCase(add(ControlKind.DEMAND, ControlObligationFixtures.emptyGuard))
        assertTrue("fixture: no slot", c.a1.orderedSlots.isEmpty())
        accepted(assess(c, empty(), handoff(c, emptyList())))
    }

    @Test fun P04_requestAdd_allCompleted_emptyRecord_accepted() {
        val c = addCase(add(ControlKind.DEMAND, F.request().json()))
        completable(c)
        accepted(assess(c, empty()))
    }

    @Test fun P05_removeEmptyGuard_emptyGuardRemains_or_absent_accepted() {
        val c = lifecycle(FloorGuardFixtures.command())
        completable(c)
        accepted(assess(c, F.read(F.raw(F.guard(auth = null)))))
        accepted(assess(c, empty()))
    }

    @Test fun P06_initializeAuth_otherScopeAuthRemains_accepted() {
        val c = initCase()
        val required = c.requiredAuth()
        completable(c)
        accepted(assess(c, empty()))
        accepted(assess(c, F.read(F.raw(F.guard(auth = required.copy(authGeneration = required.authGeneration + 1), id = "other-guard")))))
    }

    @Test fun P07_answerWithEffectAndFence_effectAbsent_fenceAtAfter_accepted() {
        val c = effectCase()
        completable(c)
        assertTrue("fixture: effect slot", c.required.any { it.key.subject is DecisionEffectScope })
        assertTrue("fixture: namespace slot", c.required.any { it.key.subject is DecisionNamespaceScope })
        accepted(assess(c, empty()))
    }

    @Test fun P08_recoverIntent_sourceAndJournalAbsent_fenceMovedToAfter_accepted() {
        val c = intentCase()
        completable(c)
        accepted(assess(c, record { this[KRX_EPOCH] = H.krxEpoch }))
    }

    // ═══ G00: latest not Supported (decision flagged in header) ════════════════════════════════════════════════════════
    @Test fun G00_latestNotSupported_everyCompletedSlotConflictsAtMetadata_emptyHandoffAccepted() {
        val unreadable = ControlRecordReader().read(ControlLifecycleEvidenceFixtures.raw(demand = "{not-an-array"))
        assertTrue("fixture", unreadable is ControlRecordRead.Unreadable)
        val migration = ControlRecordReader().read(androidx.datastore.preferences.core.emptyPreferences())
        assertTrue("fixture", migration is ControlRecordRead.MigrationOrRecoveryRequired)
        val c = rCase()
        for (latest in listOf(unreadable, migration)) {
            rejected(assess(c, latest), c.required.map { cc(c, it.key, meta) })
            val g = addCase(add(ControlKind.DEMAND, ControlObligationFixtures.emptyGuard))
            accepted(assess(g, latest, handoff(g, emptyList())))
        }
    }

    // ═══ REF / REQUIREMENTS / DUPLICATE ════════════════════════════════════════════════════════════════════════════════
    @Test fun G01_ref_exactCommandIsAnotherObject_sameIdBodyLifetime_onlyRef() {
        val c = rCase()
        val twin = CommandRef(c.ref.id, c.ref.body, c.ref.ownerTrackingLifetimeId)
        rejected(assess(c, rRecord(settled()), exact = twin),
            listOf(G05Failure(G05Id.REF, null, G05Location.Command, null, null)))
    }

    @Test fun G01b_ref_handoffCommandBindsAnotherRef_onlyRef() {
        val c = rCase()
        val other = rCase()
        rejected(assess(c, rRecord(settled()), handoff(c, command = other.a1.commandBinding)),
            listOf(G05Failure(G05Id.REF, null, G05Location.Command, null, null)))
    }

    @Test fun G02_requirements_unavailableKeepsReasonLocation() {
        val c = rCase()
        val loc = FixedInputLocation(FixedInputRoot.SETTLEMENT_INPUT, null, FixedInputFacet.WHOLE)
        rejected(assess(c, rRecord(settled()), derivation = RequirementDerivation.Unavailable(
            RequiredObligationsUnavailable.FIXED_INPUT_INCONSISTENT, loc)),
            listOf(G05Failure(G05Id.REQUIREMENTS, null, G05Location.Fixed(loc), null, null)))
    }

    @Test fun G02b_requirements_doesNotSuppressOwner() {
        val c = rCase()
        val loc = FixedInputLocation(FixedInputRoot.COMMAND, null, FixedInputFacet.WHOLE)
        rejected(assess(c, rRecord(settled()), closure = TerminationClosures.of(c.ref, owner = "owner-2"),
            derivation = RequirementDerivation.Unavailable(RequiredObligationsUnavailable.BODY_MISMATCH, loc)),
            listOf(G05Failure(G05Id.REQUIREMENTS, null, G05Location.Fixed(loc), null, null),
                G05Failure(G05Id.OWNER, null, null, null, G05Location.Closure)))
    }

    @Test fun G03_duplicate_a1DuplicateRequiredKey_isDuplicateNotRequirements() {
        val c = rCase()
        val loc = FixedInputLocation(FixedInputRoot.SETTLEMENT_TARGET, 0, FixedInputFacet.SOURCE)
        rejected(assess(c, rRecord(settled()), derivation = RequirementDerivation.Unavailable(
            RequiredObligationsUnavailable.DUPLICATE_REQUIRED_KEY, loc)),
            listOf(G05Failure(G05Id.DUPLICATE, null, G05Location.Fixed(loc), null, null)))
    }

    @Test fun G03b_duplicate_submittedTwice_secondIndex() {
        val c = rCase()
        val jL = c.journalKey(L)
        rejected(assess(c, rRecord(settled()), handoff(c, all(c) + completed(jL))),
            listOf(slotFailure(G05Id.DUPLICATE, c, jL, 6)))
    }

    // ═══ COVERAGE / EXTRA / SUBJECT ════════════════════════════════════════════════════════════════════════════════════
    @Test fun G04_coverageL_missingSealL() {
        val c = rCase()
        val sL = c.sealKey(L)
        rejected(assess(c, rRecord(settled()), handoff(c, all(c).filter { it.key != sL })),
            listOf(slotFailure(G05Id.COVERAGE_L, c, sL, null)))
    }

    @Test fun G05_coverageN_missingRequestN() {
        val c = rCase()
        val qN = c.requestKey(N)
        rejected(assess(c, rRecord(settled()), handoff(c, all(c).filter { it.key != qN })),
            listOf(slotFailure(G05Id.COVERAGE_N, c, qN, null)))
    }

    @Test fun G06_extra_foreignKey() {
        val c = rCase()
        val foreign = c.journalKey(L).copy(role = ObligationRole.Rotation)
        rejected(assess(c, rRecord(settled()), handoff(c, all(c) + completed(foreign))),
            listOf(G05Failure(G05Id.EXTRA, foreign, null, sub(6), null)))
    }

    @Test fun G06b_extra_notRequiredByContractKey() {
        val d = RetiredNamespaceFixtures.departed()
        val c = rCase(d)
        val qL = c.requestKey(L)
        assertTrue("fixture: departed Q is N/A", c.a1.orderedSlots.single { it.key == qL }.requirement is SlotRequirement.NotRequiredByContract)
        assertEquals("fixture", 4, c.required.size)
        rejected(assess(c, rRecord(settled(d), s = d), handoff(c, all(c) + completed(qL))),
            listOf(G05Failure(G05Id.EXTRA, qL, null, sub(4), null)))
    }

    @Test fun G07_changedKeySubject_isCoverageAndExtra_notSubject() {
        val c = rCase()
        val sL = c.sealKey(L)
        val changed = sL.copy(subject = ObligationSubject.NamedRequest("other"))
        val slots = all(c).map { if (it.key == sL) completed(changed) else it }
        rejected(assess(c, rRecord(settled()), handoff(c, slots)),
            listOf(slotFailure(G05Id.COVERAGE_L, c, sL, null), G05Failure(G05Id.EXTRA, changed, null, sub(0), null)))
    }

    @Test fun G08_subject_onlySourceSubjectChanged() {
        val c = rCase()
        val sL = c.sealKey(L)
        val slots = all(c).map { if (it.key == sL) completed(sL, ObligationSubject.NamedRequest("other")) else it }
        rejected(assess(c, rRecord(settled()), handoff(c, slots)), listOf(slotFailure(G05Id.SUBJECT, c, sL, 0)))
    }

    // ═══ DESTINATION / CONFIRMATION (no PriorStorageConfirmation in A2) ════════════════════════════════════════════════
    @Test fun G09_confirmation_alone_locatorOnExactSettledSeal() {
        val c = rCase()
        val sL = c.sealKey(L)
        val slots = all(c).map { if (it.key == sL) owned(sL, DestinationLocator.Payload(ControlKind.SEAL, "s")) else it }
        rejected(assess(c, rRecord(settled()), handoff(c, slots)), listOf(slotFailure(G05Id.CONFIRMATION, c, sL, 0)))
    }

    @Test fun G10_destinationAndConfirmation_locatorAbsent() {
        val c = rCase()
        val sL = c.sealKey(L)
        val slots = all(c).map { if (it.key == sL) owned(sL, DestinationLocator.Payload(ControlKind.SEAL, "missing")) else it }
        rejected(assess(c, rRecord(settled()), handoff(c, slots)),
            listOf(slotFailure(G05Id.DESTINATION, c, sL, 0), slotFailure(G05Id.CONFIRMATION, c, sL, 0)))
    }

    @Test fun G10b_lowerBound_sameGuardAuthScopeButWrongState() {
        val c = initCase()
        val required = c.requiredAuth()
        val changed = required.copy(authStateOrder = required.authStateOrder + 1)
        assertEquals(TypedComparison.Mismatch(AuthField.STATE), compareAuth(required, changed))
        val latest = interpretable(F.read(F.raw(F.guard(auth = changed, id = "g-init"))))
        val aL = c.key { it.component == ObligationComponent.AUTH && it.branch == L }
        val aN = c.key { it.component == ObligationComponent.AUTH && it.branch == N }
        val slots = all(c).map {
            if (it.key == aL || it.key == aN) owned(it.key, DestinationLocator.Guard("g-init", GuardPart.AUTH)) else it
        }
        rejected(assess(c, latest, handoff(c, slots)), listOf(
            slotFailure(G05Id.CONFIRMATION, c, aL, c.at(aL)),
            slotFailure(G05Id.CONFIRMATION, c, aN, c.at(aN)),
            slotFailure(G05Id.LOWER_BOUND, c, aL, c.at(aL), payload(ControlKind.DEMAND)),
            slotFailure(G05Id.LOWER_BOUND, c, aN, c.at(aN), payload(ControlKind.DEMAND))))
    }

    // ═══ measurement r2 additions: LOWER_BOUND per type (destination found, lower bound not Matches) ══════════════════
    private fun ownAll(c: Case, keys: List<RequiredObligationKey>, d: DestinationLocator) =
        all(c).map { if (it.key in keys) owned(it.key, d) else it }
    private fun conf(c: Case, k: RequiredObligationKey) = slotFailure(G05Id.CONFIRMATION, c, k, c.at(k))
    private fun lower(c: Case, k: RequiredObligationKey, at: G05Location) = slotFailure(G05Id.LOWER_BOUND, c, k, c.at(k), at)
    private fun dest(c: Case, k: RequiredObligationKey, at: G05Location?) = slotFailure(G05Id.DESTINATION, c, k, c.at(k), at)

    /** S^L requires R's witness, S^N requires only the source: an active seal meets S^N and misses S^L. */
    @Test fun G10c_lowerBound_seal_activeRowMissesLandingWitnessOnly() {
        val c = rCase()
        val sL = c.sealKey(L); val sN = c.sealKey(N)
        rejected(assess(c, interpretable(rRecord(active)), handoff(c, ownAll(c, listOf(sL, sN), DestinationLocator.Payload(ControlKind.SEAL, "s")))),
            listOf(conf(c, sL), conf(c, sN), lower(c, sL, payload(ControlKind.SEAL))))
    }

    @Test fun G10d_lowerBound_request_weakerIntent() {
        val m = add(ControlKind.DEMAND, F.request().json())
        val c = addCase(m)
        val qL = c.requestKey(L); val qN = c.requestKey(N)
        val weaker = F.request(id = m.proposedId, intent = com.jay.fxi.data.entitlements.RefreshIntent.IF_STALE)
        rejected(assess(c, interpretable(record(demand = rows(weaker))), handoff(c, ownAll(c, listOf(qL, qN), DestinationLocator.Payload(ControlKind.DEMAND, m.proposedId)))),
            listOf(conf(c, qL), conf(c, qN), lower(c, qL, payload(ControlKind.DEMAND)), lower(c, qN, payload(ControlKind.DEMAND))))
    }

    @Test fun G10e_lowerBound_intent_otherSession() {
        val m = add(ControlKind.RECOVERY_INTENT, ControlObligationFixtures.recovery)
        val c = addCase(m)
        val iL = c.intentKey(L); val iN = c.intentKey(N)
        val other = ControlObligationFixtures.node(
            """{"id":"${m.proposedId}","sessionId":"other","ownerUid":null,"axis":"CAPABILITY","targetEpoch":null}""")
        val latest = interpretable(record { this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = rows(other) })
        val at = payload(ControlKind.RECOVERY_INTENT)
        rejected(assess(c, latest, handoff(c, ownAll(c, listOf(iL, iN), DestinationLocator.Payload(ControlKind.RECOVERY_INTENT, m.proposedId)))),
            listOf(conf(c, iL), conf(c, iN), lower(c, iL, at), lower(c, iN, at)))
    }

    /** Same wait, earlier anchor (the hold's wait is tied to its outcome): SOURCE misses by field, FLOOR by remaining time at [now]. */
    @Test fun G10f_lowerBound_holdSourceAndHoldFloor_shorterFloor() {
        val m = add(ControlKind.HOLD, ControlObligationFixtures.hold)
        val c = addCase(m)
        val keys = c.required.map { it.key }
        assertEquals("fixture: hold SOURCE pair + FLOOR pair",
            listOf(ObligationComponent.SOURCE, ObligationComponent.SOURCE, ObligationComponent.FLOOR, ObligationComponent.FLOOR),
            keys.map { it.component })
        val shorter = FloorGuardFixtures.field(after(m), "floor", FloorGuardFixtures.floor(elapsed = 0))
        val at = payload(ControlKind.HOLD)
        rejected(assess(c, interpretable(record(hold = rows(shorter))), handoff(c, ownAll(c, keys, DestinationLocator.Payload(ControlKind.HOLD, m.proposedId)))),
            keys.map { conf(c, it) } + keys.map { lower(c, it, at) })
    }

    @Test fun G10g_lowerBound_guardFloor_shorterRemaining_and_otherGuardIsNotTheDestination() {
        val guard = """{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}"""
        val m = add(ControlKind.DEMAND, guard)
        val c = addCase(m)
        val fL = c.key { it.component == ObligationComponent.FLOOR && it.branch == L }
        val fN = c.key { it.component == ObligationComponent.FLOOR && it.branch == N }
        val shorter = FloorGuardFixtures.field(after(m), "floor", FloorGuardFixtures.floor(wait = 12_000))
        val at = payload(ControlKind.DEMAND)
        rejected(assess(c, interpretable(record(demand = rows(shorter))), handoff(c, ownAll(c, listOf(fL, fN), DestinationLocator.Guard(m.proposedId, GuardPart.FLOOR)))),
            listOf(conf(c, fL), conf(c, fN), lower(c, fL, at), lower(c, fN, at)))
        val other = FloorGuardFixtures.field(F.guard(auth = null, id = "other-guard"), "floor", FloorGuardFixtures.floor())
        rejected(assess(c, interpretable(record(demand = rows(other))), handoff(c, ownAll(c, listOf(fL, fN), DestinationLocator.Guard("other-guard", GuardPart.FLOOR)))),
            listOf(dest(c, fL, at), dest(c, fN, at), conf(c, fL), conf(c, fN)))
    }

    @Test fun G10h_lowerBound_decisionEffectRowDiffersFromEffectNode() {
        val effect = ControlObligationFixtures.node(ControlObligationFixtures.topicHold)
        val proof = ConfirmedControlSnapshot(interpretable(record(hold = rows(effect))))
        val decision = F.decision(outcome = EntitlementsOutcome.StableInactive(false),
            effects = listOf(LifecycleDurableEffect(ControlKind.HOLD, effect, proof)), namespace = proof)
        val plan = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(decision),
            LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        assertNull("recipe must prepare", plan.preparationFailure)
        val c = lifecycle(F.command(plan))
        val eL = c.key { it.subject is DecisionEffectScope && it.branch == L }
        val eN = c.key { it.subject is DecisionEffectScope && it.branch == N }
        val changed = ControlObligationFixtures.node(ControlObligationFixtures.topicHold.replace("\"binding\":3", "\"binding\":7"))
        assertTrue("fixture: row actually changed", changed.toPayloadEntry() != effect.toPayloadEntry())
        val at = payload(ControlKind.HOLD)
        rejected(assess(c, interpretable(record(hold = rows(changed))), handoff(c, ownAll(c, listOf(eL, eN), DestinationLocator.Payload(ControlKind.HOLD, "t")))),
            listOf(conf(c, eL), conf(c, eN), lower(c, eL, at), lower(c, eN, at)))
    }

    /** DemandAuth A03 settle: the unchanged empty guard is an EITHER ExactSource pair; the same id now carries a floor. */
    @Test fun G10j_lowerBound_exactSource_sameIdGuardNodeDiffers() {
        val p = DemandAuthPlan.settle(listOf(F.request(id = "removed")), F.guard(auth = null), null, F.binding, F.decision(),
            LifecycleOrderSource(F.life, 21), "unused-guard-id", "unused-retry-id")
        assertNull("recipe must prepare", p.preparationFailure)
        val c = lifecycle(F.command(p))
        val xL = c.key { it.subject == ObligationSubject.ExactTarget(ControlKind.DEMAND, "g") && it.branch == L }
        val xN = c.key { it.subject == ObligationSubject.ExactTarget(ControlKind.DEMAND, "g") && it.branch == N }
        assertEquals("fixture: EITHER pair", listOf(AllowedSlotDisposition.EITHER, AllowedSlotDisposition.EITHER),
            listOf(xL, xN).map { k -> (c.required.single { it.key == k }.requirement as SlotRequirement.Required).allowed })
        val at = payload(ControlKind.DEMAND)
        rejected(assess(c, interpretable(F.read(F.raw(FloorGuardFixtures.guard()))),
            handoff(c, ownAll(c, listOf(xL, xN), DestinationLocator.Payload(ControlKind.DEMAND, "g")))),
            listOf(conf(c, xL), conf(c, xN), lower(c, xL, at), lower(c, xN, at)))
    }

    @Test fun G10i_destination_journalLocatorOnUnparsableJournal() {
        val c = rCase()
        val jL = c.journalKey(L); val jN = c.journalKey(N)
        val key = (c.a1.orderedSlots.single { it.key == jL }.requirement as SlotRequirement.Required).lowerBound.let { (it as RequiredLowerBound.Journal).key }
        rejected(assess(c, rRecord(settled(), journal = "broken"), handoff(c, ownAll(c, listOf(jL, jN), DestinationLocator.Journal(key)))),
            listOf(dest(c, jL, null), dest(c, jN, null), conf(c, jL), conf(c, jN)))
    }

    // ═══ COMPLETED_CONFLICT — rule 6 (floor: allowed-rule violation) ═══════════════════════════════════════════════════
    @Test fun G11_floorAdd_completedOnBothBranches_allowedViolation_actualNull() {
        val guard = """{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}"""
        val c = addCase(add(ControlKind.DEMAND, guard))
        val fL = c.key { it.component == ObligationComponent.FLOOR && it.branch == L }
        val fN = c.key { it.component == ObligationComponent.FLOOR && it.branch == N }
        rejected(assess(c, empty()), listOf(cc(c, fL, null), cc(c, fN, null)))
    }

    // ═══ rule 1 (REQUEST: same id in any form) ═════════════════════════════════════════════════════════════════════════
    @Test fun G12_R_namedRequest_sameIdRequestRemains_bothBranches() {
        val c = rCase()
        rejected(assess(c, interpretable(rRecord(settled(), demand = "[${RetiredNamespaceFixtures.demand(R)}]"))),
            listOf(cc(c, c.requestKey(L), payload(ControlKind.DEMAND)), cc(c, c.requestKey(N), payload(ControlKind.DEMAND))))
    }

    @Test fun G12b_R_namedRequest_opaqueSameIdElement_bothBranches() {
        val c = rCase()
        val latest = rRecord(settled(), demand = """[{"id":"r-demand","kind":"REQUEST"}]""")
        assertTrue("fixture: opaque element in a readable envelope", latest.hasUninterpretable)
        rejected(assess(c, latest),
            listOf(cc(c, c.requestKey(L), payload(ControlKind.DEMAND)), cc(c, c.requestKey(N), payload(ControlKind.DEMAND))))
    }

    @Test fun G12c_requestAdd_sameIssuedIdRemains_atItsArrayIndex() {
        val m = add(ControlKind.DEMAND, F.request().json())
        val c = addCase(m)
        val latest = interpretable(record(demand = rows(F.request(id = "unrelated"), after(m))))
        rejected(assess(c, latest), listOf(cc(c, c.requestKey(L), payload(ControlKind.DEMAND, 1)),
            cc(c, c.requestKey(N), payload(ControlKind.DEMAND, 1))))
    }

    // ═══ rule 0 / 2 (journal) ══════════════════════════════════════════════════════════════════════════════════════════
    @Test fun G13_R_journalKeyRemains_sharedPhysicalRow_bothBranches() {
        val c = rCase()
        rejected(assess(c, rRecord(settled(), journal = "A|u||USER")),
            listOf(cc(c, c.journalKey(L), G05Location.ActualJournal(0)), cc(c, c.journalKey(N), G05Location.ActualJournal(0))))
    }

    @Test fun G13b_R_journalUnparsable_bothBranchesAtMetadata() {
        val c = rCase()
        rejected(assess(c, rRecord(settled(), journal = "broken")),
            listOf(cc(c, c.journalKey(L), meta), cc(c, c.journalKey(N), meta)))
    }

    @Test fun G13c_R_otherKeyJournal_isNoContradiction() {
        val c = rCase()
        completable(c)
        accepted(assess(c, rRecord(settled(), journal = "A||k|CAPABILITY")))
    }

    // ═══ rule 3 (HOLD / RECOVERY_INTENT: same id in any form) ══════════════════════════════════════════════════════════
    @Test fun G14_holdAdd_sameIssuedIdRemains() {
        val m = add(ControlKind.HOLD, ControlObligationFixtures.topicHold)
        val c = addCase(m)
        val hL = c.key { it.component == ObligationComponent.SOURCE && it.branch == L }
        val hN = c.key { it.component == ObligationComponent.SOURCE && it.branch == N }
        rejected(assess(c, interpretable(record(hold = rows(after(m))))), listOf(cc(c, hL, payload(ControlKind.HOLD)), cc(c, hN, payload(ControlKind.HOLD))))
    }

    @Test fun G14b_intentAdd_opaqueSameIdRemains() {
        val m = add(ControlKind.RECOVERY_INTENT, ControlObligationFixtures.recovery)
        val c = addCase(m)
        val iL = c.key { it.component == ObligationComponent.SOURCE && it.branch == L }
        val iN = c.key { it.component == ObligationComponent.SOURCE && it.branch == N }
        val latest = record { this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = """[{"id":"${m.proposedId}"}]""" }
        assertTrue("fixture: opaque", latest.hasUninterpretable)
        rejected(assess(c, latest), listOf(cc(c, iL, payload(ControlKind.RECOVERY_INTENT)), cc(c, iN, payload(ControlKind.RECOVERY_INTENT))))
    }

    // ═══ rule 4 (SEAL) ═════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun G15_R_activeSealRemains_bothBranches() {
        val c = rCase()
        rejected(assess(c, interpretable(rRecord(active))),
            listOf(cc(c, c.sealKey(L), payload(ControlKind.SEAL)), cc(c, c.sealKey(N), payload(ControlKind.SEAL))))
    }

    @Test fun G15b_R_settledWithAnotherWitness_conflictsLandingOnly() {
        val c = rCase()
        val wrong = RetiredNamespaceFixtures.witness(R).copy(operationId = "other-operation")
        rejected(assess(c, interpretable(rRecord(settled(w = wrong)))), listOf(cc(c, c.sealKey(L), payload(ControlKind.SEAL))))
    }

    // ═══ rule 5 (REMOVE_EMPTY_GUARD exact source: floor or AUTH on the same id) ════════════════════════════════════════
    @Test fun G16_removeEmptyGuard_sameIdGuardGainedFloor_or_auth() {
        val c = lifecycle(FloorGuardFixtures.command())
        val xL = c.key { it.component == ObligationComponent.SOURCE && it.branch == L }
        for (g in listOf(FloorGuardFixtures.guard(), F.guard()))
            rejected(assess(c, interpretable(F.read(F.raw(g)))), listOf(cc(c, xL, payload(ControlKind.DEMAND))))
    }

    // ═══ rule 7 (AUTH: same scope in any guard, guard id may differ) ═══════════════════════════════════════════════════
    @Test fun G17_initializeAuth_sameScopeAuthRemainsInOtherGuard_bothBranches() {
        val c = initCase()
        val aL = c.key { it.component == ObligationComponent.AUTH && it.branch == L }
        val aN = c.key { it.component == ObligationComponent.AUTH && it.branch == N }
        val latest = interpretable(F.read(F.raw(F.request(id = "unrelated"), F.guard(auth = c.requiredAuth(), id = "other-guard"))))
        rejected(assess(c, latest), listOf(cc(c, aL, payload(ControlKind.DEMAND, 1)), cc(c, aN, payload(ControlKind.DEMAND, 1))))
    }

    // ═══ rules 8 / 9 (decision effect / decision namespace) ════════════════════════════════════════════════════════════
    @Test fun G18_decisionEffectRequestRemains_bothBranches() {
        val c = effectCase()
        val eL = c.key { it.subject is DecisionEffectScope && it.branch == L }
        val eN = c.key { it.subject is DecisionEffectScope && it.branch == N }
        rejected(assess(c, interpretable(F.read(F.raw(F.request(id = "effect", order = 7))))),
            listOf(cc(c, eL, payload(ControlKind.DEMAND)), cc(c, eN, payload(ControlKind.DEMAND))))
    }

    @Test fun G19_decisionNamespace_fenceStillBefore_or_uninterpretable_atMetadata() {
        val c = effectCase()
        val nL = c.key { it.subject is DecisionNamespaceScope && it.branch == L }
        val nN = c.key { it.subject is DecisionNamespaceScope && it.branch == N }
        val stillBefore = record { this[USER_EPOCH] = "u-old" }
        val wrongType = record { remove(USER_EPOCH); this[intPreferencesKey(USER_EPOCH.name)] = 7 }
        for (latest in listOf(stillBefore, wrongType)) rejected(assess(c, latest), listOf(cc(c, nL, meta), cc(c, nN, meta)))
    }

    // ═══ rules 3 / 2 / 10 (RECOVER_INTENT: source, journal, retirement fence) ══════════════════════════════════════════
    @Test fun G20_recoverIntent_sourceRemains_intentAndRetirementBothBranches() {
        val c = intentCase()
        val latest = interpretable(record {
            this[KRX_EPOCH] = H.krxEpoch
            this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = rows(intentNode())
        })
        val at = payload(ControlKind.RECOVERY_INTENT)
        rejected(assess(c, latest), listOf(cc(c, c.intentKey(L), at), cc(c, c.intentKey(N), at),
            cc(c, c.retirementKey(L), at), cc(c, c.retirementKey(N), at)))
    }

    @Test fun G20b_recoverIntent_journalRemains_journalAndRetirementBothBranches() {
        val c = intentCase()
        val latest = record { this[KRX_EPOCH] = H.krxEpoch; this[PURGE_JOURNAL] = "A||k|CAPABILITY" }
        val at = G05Location.ActualJournal(0)
        rejected(assess(c, latest), listOf(cc(c, c.journalKey(L), at), cc(c, c.journalKey(N), at),
            cc(c, c.retirementKey(L), at), cc(c, c.retirementKey(N), at)))
    }

    @Test fun G20c_recoverIntent_fenceStillBefore_retirementBothBranchesAtMetadata() {
        val c = intentCase()
        rejected(assess(c, empty()), listOf(cc(c, c.retirementKey(L), meta), cc(c, c.retirementKey(N), meta)))
    }

    // ═══ OWNER ═════════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun G21_owner_closureOwnerDiffers() {
        val c = rCase()
        rejected(assess(c, rRecord(settled()), closure = TerminationClosures.of(c.ref, owner = "owner-2")),
            listOf(G05Failure(G05Id.OWNER, null, null, null, G05Location.Closure)))
    }

    @Test fun G21b_owner_trackingLifetimeDiffersFromCommand() {
        val c = rCase()
        rejected(assess(c, rRecord(settled()), handoff(c, owner = ResponsibilityOwner(OwnerTrackingLifetimeId.issue(), "owner-1"))),
            listOf(G05Failure(G05Id.OWNER, null, G05Location.Command, null, null)))
    }

    // ═══ ordering across ids ═══════════════════════════════════════════════════════════════════════════════════════════
    @Test fun G22_multiDefect_orderedById_thenSlot_thenIndex() {
        val c = rCase()
        val sL = c.sealKey(L); val sN = c.sealKey(N); val jL = c.journalKey(L); val jN = c.journalKey(N)
        val qL = c.requestKey(L); val qN = c.requestKey(N)
        val foreign = jL.copy(role = ObligationRole.Rotation)
        val slots = listOf(owned(sL, DestinationLocator.Payload(ControlKind.SEAL, "s")), completed(sN),
            completed(jL, ObligationSubject.NamedRequest("other")), completed(jN), completed(qL), completed(foreign), completed(sN))
        val latest = interpretable(rRecord(settled(), demand = "[${RetiredNamespaceFixtures.demand(R)}]"))
        rejected(assess(c, latest, handoff(c, slots),
            closure = TerminationClosures.of(c.ref, owner = "owner-2")), listOf(
            slotFailure(G05Id.DUPLICATE, c, sN, 6),
            slotFailure(G05Id.COVERAGE_N, c, qN, null),
            G05Failure(G05Id.EXTRA, foreign, null, sub(5), null),
            slotFailure(G05Id.SUBJECT, c, jL, 2),
            slotFailure(G05Id.CONFIRMATION, c, sL, 0),
            cc(c, qL, payload(ControlKind.DEMAND), 4),
            G05Failure(G05Id.OWNER, null, null, null, G05Location.Closure)))
    }

    @Test fun G23_declarations_copyTheirLists() {
        val c = rCase()
        val source = all(c).toMutableList()
        val h = handoff(c, source)
        source.clear()
        assertEquals(6, h.slots.size)
        assertTrue(runCatching { (h.slots as MutableList<SlotHandoff>).clear() }.isFailure)
        val failures = mutableListOf(G05Failure(G05Id.REF, null, G05Location.Command, null, null))
        val r = G05Result.Rejected(failures)
        failures.clear()
        assertEquals(1, r.failures.size)
        assertEquals("G05.completedConflict", G05Id.COMPLETED_CONFLICT.wire)
    }

    // ═══ review r1 additions (Codex 6-4bA2_contract_review_codex.r1.md) ═══════════════════════════════════════════════
    @Test fun G01c_refFailureSuppressesDependentOwner() {
        val c = rCase()
        val other = rCase()
        rejected(assess(c, rRecord(settled()), handoff(c, command = other.a1.commandBinding),
            closure = TerminationClosures.of(c.ref, owner = "owner-2")),
            listOf(G05Failure(G05Id.REF, null, G05Location.Command, null, null)))
    }

    @Test fun G00b_supportedOpaqueDemandWithoutId_conflictsRequestSlots() {
        val c = rCase()
        val latest = rRecord(settled(), demand = """[{"kind":"REQUEST"}]""")
        assertTrue("fixture: readable envelope, opaque row", latest.hasUninterpretable)
        rejected(assess(c, latest), listOf(cc(c, c.requestKey(L), payload(ControlKind.DEMAND)),
            cc(c, c.requestKey(N), payload(ControlKind.DEMAND))))
    }

    @Test fun G11b_floorAllowedViolationPrecedesOpaqueActualWithinEachSlot() {
        val guard = """{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}"""
        val c = addCase(add(ControlKind.DEMAND, guard))
        val fL = c.key { it.component == ObligationComponent.FLOOR && it.branch == L }
        val fN = c.key { it.component == ObligationComponent.FLOOR && it.branch == N }
        val latest = record(demand = """[{"kind":"REQUEST"}]""")
        assertTrue("fixture: readable envelope, opaque row", latest.hasUninterpretable)
        rejected(assess(c, latest), listOf(cc(c, fL, null), cc(c, fL, payload(ControlKind.DEMAND)),
            cc(c, fN, null), cc(c, fN, payload(ControlKind.DEMAND))))
    }

    @Test fun G12d_namedRequestSourceNull_sameIdChangedScopeStillConflicts() {
        val c = rCase()
        val qL = c.requestKey(L)
        val qN = c.requestKey(N)
        val bound = (c.a1.orderedSlots.single { it.key == qL }.requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.Request
        assertNull("fixture: named request has no source row", bound.source)
        val original = RetiredNamespaceFixtures.demand(R)
        val changed = original.replace("\"ownerUid\":\"A\"", "\"ownerUid\":\"B\"")
        assertTrue("fixture: owner actually changed", changed != original)
        val latest = rRecord(settled(), demand = "[$changed]")
        assertFalse("fixture: changed-scope request is interpretable", latest.hasUninterpretable)
        rejected(assess(c, latest),
            listOf(cc(c, qL, payload(ControlKind.DEMAND)), cc(c, qN, payload(ControlKind.DEMAND))))
    }

    @Test fun G17b_authScopeChecksAllFourFields_andIgnoresStateForIdentity() {
        val c = initCase()
        completable(c)
        val required = c.requiredAuth()
        val otherScopes = listOf(required.copy(ownerUid = "B"), required.copy(binding = required.binding + 1),
            required.copy(originLifetimeId = LifetimeId("other-life")), required.copy(authGeneration = required.authGeneration + 1))
        for (auth in otherScopes) accepted(assess(c, F.read(F.raw(F.guard(auth = auth, id = "other-guard")))))
        val stateOnly = required.copy(authStateOrder = required.authStateOrder + 1)
        val latest = F.read(F.raw(F.guard(auth = stateOnly, id = "other-guard")))
        assertFalse("fixture: the state-only AUTH is an interpretable row (else rule 0 would decide)", latest.hasUninterpretable)
        val aL = c.key { it.component == ObligationComponent.AUTH && it.branch == L }
        val aN = c.key { it.component == ObligationComponent.AUTH && it.branch == N }
        rejected(assess(c, latest), listOf(cc(c, aL, payload(ControlKind.DEMAND)), cc(c, aN, payload(ControlKind.DEMAND))))
    }

    @Test fun G18b_nonDemandEffects_applyHoldAndSealRules() {
        fun verify(kind: ControlKind, node: ControlNode, latest: ControlRecordRead.Supported) {
            assertFalse("fixture: effect row is interpretable", latest.hasUninterpretable)
            val proof = ConfirmedControlSnapshot(latest)
            val decision = F.decision(outcome = EntitlementsOutcome.StableInactive(false),
                effects = listOf(LifecycleDurableEffect(kind, node, proof)), namespace = proof)
            val plan = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(decision),
                LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
            assertNull("recipe must prepare", plan.preparationFailure)
            val c = lifecycle(F.command(plan))
            completable(c)
            val eL = c.key { it.subject is DecisionEffectScope && it.branch == L }
            val eN = c.key { it.subject is DecisionEffectScope && it.branch == N }
            rejected(assess(c, latest), listOf(cc(c, eL, payload(kind)), cc(c, eN, payload(kind))))
        }
        val hold = ControlObligationFixtures.node(ControlObligationFixtures.topicHold)
        verify(ControlKind.HOLD, hold, record(hold = rows(hold)))
        val seal = ControlObligationFixtures.node(NamespaceSettlementFixtures.user)
        verify(ControlKind.SEAL, seal, record { this[ControlRecordKeys.payload(ControlKind.SEAL)] = rows(seal) })
    }

    @Test fun G19b_decisionNamespace_capabilityFenceStillBefore() {
        val before = F.fence.copy(krxCapabilityEpoch = "k-old")
        val proof = ConfirmedControlSnapshot(empty())
        val decision = F.decision(q = F.query.copy(fence = before), before = before,
            outcome = EntitlementsOutcome.StableInactive(false), namespace = proof)
        val plan = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(decision),
            LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        assertNull("recipe must prepare", plan.preparationFailure)
        val c = lifecycle(F.command(plan))
        completable(c)
        val nL = c.key { it.subject is DecisionNamespaceScope && it.branch == L }
        val nN = c.key { it.subject is DecisionNamespaceScope && it.branch == N }
        rejected(assess(c, record { this[KRX_EPOCH] = "k-old" }), listOf(cc(c, nL, meta), cc(c, nN, meta)))
    }

    @Test fun G20d_recoverIntent_uninterpretableRetirementFence() {
        val c = intentCase()
        val latest = record { remove(KRX_EPOCH); this[intPreferencesKey(KRX_EPOCH.name)] = 7 }
        rejected(assess(c, latest), listOf(cc(c, c.retirementKey(L), meta), cc(c, c.retirementKey(N), meta)))
    }

    // ═══ measurement r1 additions (measure-6-4bA2/sensitivity.r1.json survivors that are contract gaps) ═════════════════
    @Test fun G01d_ref_unavailableDerivation_handoffBindsAnotherRef() {
        val c = rCase()
        val other = rCase()
        val loc = FixedInputLocation(FixedInputRoot.COMMAND, null, FixedInputFacet.WHOLE)
        rejected(assess(c, rRecord(settled()), handoff(c, command = other.a1.commandBinding),
            derivation = RequirementDerivation.Unavailable(RequiredObligationsUnavailable.BODY_MISMATCH, loc)),
            listOf(G05Failure(G05Id.REF, null, G05Location.Command, null, null)))
    }

    @Test fun G01e_ref_derivationBindsAnotherRef() {
        val c = rCase()
        val other = rCase()
        rejected(assess(c, rRecord(settled()), derivation = other.a1),
            listOf(G05Failure(G05Id.REF, null, G05Location.Command, null, null)))
    }

    @Test fun G01f_ref_exactCommandTerminatedAfterDerivation_bodyUnavailable() {
        val c = rCase()
        val h = handoff(c)
        c.ref.beginTermination(); c.ref.completeTermination()
        rejected(assess(c, rRecord(settled()), h), listOf(G05Failure(G05Id.REF, null, G05Location.Command, null, null)))
    }

    @Test fun G03c_duplicate_twoKeysInReverseA1Order_sortedByA1Slot() {
        val c = rCase()
        val sL = c.sealKey(L); val jL = c.journalKey(L)
        rejected(assess(c, rRecord(settled()), handoff(c, all(c) + completed(jL) + completed(sL))),
            listOf(slotFailure(G05Id.DUPLICATE, c, sL, 7), slotFailure(G05Id.DUPLICATE, c, jL, 6)))
    }

    @Test fun G09b_destination_payloadLocatorWrongKind_otherIdRow_uninterpretableRow() {
        val c = rCase()
        val sL = c.sealKey(L); val sN = c.sealKey(N)
        fun owning(d: DestinationLocator) = handoff(c, all(c).map { if (it.key == sL) owned(sL, d) else it })
        rejected(assess(c, rRecord(settled()), owning(DestinationLocator.Payload(ControlKind.DEMAND, "s"))),
            listOf(slotFailure(G05Id.DESTINATION, c, sL, 0, payload(ControlKind.SEAL)), slotFailure(G05Id.CONFIRMATION, c, sL, 0)))
        val otherSeal = ControlObligationFixtures.node(NamespaceSettlementFixtures.user.replace("\"id\":\"s\"", "\"id\":\"other\""))
        val two = interpretable(RetiredNamespaceFixtures.read(RetiredNamespaceFixtures.raw(R).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)] = NamespaceSettlementFixtures.jsonArray(settled(), otherSeal)
        }.toPreferences()))
        rejected(assess(c, two, owning(DestinationLocator.Payload(ControlKind.SEAL, "other"))),
            listOf(slotFailure(G05Id.DESTINATION, c, sL, 0, payload(ControlKind.SEAL, 1)), slotFailure(G05Id.CONFIRMATION, c, sL, 0)))
        val opaque = rRecord(ControlObligationFixtures.node("""{"id":"s"}"""))
        assertTrue("fixture: opaque seal row", opaque.hasUninterpretable)
        rejected(assess(c, opaque, owning(DestinationLocator.Payload(ControlKind.SEAL, "s"))),
            listOf(slotFailure(G05Id.DESTINATION, c, sL, 0, payload(ControlKind.SEAL)), slotFailure(G05Id.CONFIRMATION, c, sL, 0),
                cc(c, sN, payload(ControlKind.SEAL))))
    }

    @Test fun G09c_destination_latestNotSupported() {
        val c = rCase()
        val sL = c.sealKey(L)
        val unreadable = ControlRecordReader().read(ControlLifecycleEvidenceFixtures.raw(demand = "{not-an-array"))
        val slots = all(c).map { if (it.key == sL) owned(sL, DestinationLocator.Payload(ControlKind.SEAL, "s")) else it }
        rejected(assess(c, unreadable, handoff(c, slots)), listOf(slotFailure(G05Id.DESTINATION, c, sL, 0),
            slotFailure(G05Id.CONFIRMATION, c, sL, 0)) + c.required.drop(1).map { cc(c, it.key, meta) })
    }

    @Test fun G09d_destination_guardFloorLocator() {
        val guard = """{"id":"g","kind":"SCHEDULE_GUARD","floor":${ControlObligationFixtures.floor}}"""
        val m = add(ControlKind.DEMAND, guard)
        val c = addCase(m)
        val fL = c.key { it.component == ObligationComponent.FLOOR && it.branch == L }
        val fN = c.key { it.component == ObligationComponent.FLOOR && it.branch == N }
        val h = handoff(c, listOf(fL, fN).map { owned(it, DestinationLocator.Guard(m.proposedId, GuardPart.FLOOR)) })
        rejected(assess(c, interpretable(record(demand = rows(after(m)))), h),
            listOf(slotFailure(G05Id.CONFIRMATION, c, fL, 0), slotFailure(G05Id.CONFIRMATION, c, fN, 1)))
        val withoutFloor = F.guard(auth = null, id = m.proposedId)
        rejected(assess(c, interpretable(record(demand = rows(withoutFloor))), h),
            listOf(slotFailure(G05Id.DESTINATION, c, fL, 0, payload(ControlKind.DEMAND)), slotFailure(G05Id.DESTINATION, c, fN, 1, payload(ControlKind.DEMAND)),
                slotFailure(G05Id.CONFIRMATION, c, fL, 0), slotFailure(G05Id.CONFIRMATION, c, fN, 1)))
    }

    @Test fun G09e_destination_guardAuthLocator_sameScopeOnly() {
        val c = initCase()
        val required = c.requiredAuth()
        val aL = c.key { it.component == ObligationComponent.AUTH && it.branch == L }
        val aN = c.key { it.component == ObligationComponent.AUTH && it.branch == N }
        val slots = all(c).map { if (it.key == aL || it.key == aN) owned(it.key, DestinationLocator.Guard("g-init", GuardPart.AUTH)) else it }
        rejected(assess(c, interpretable(F.read(F.raw(F.guard(auth = required, id = "g-init")))), handoff(c, slots)),
            listOf(slotFailure(G05Id.CONFIRMATION, c, aL, c.at(aL)), slotFailure(G05Id.CONFIRMATION, c, aN, c.at(aN))))
        val otherScope = interpretable(F.read(F.raw(F.guard(auth = required.copy(authGeneration = required.authGeneration + 1), id = "g-init"))))
        rejected(assess(c, otherScope, handoff(c, slots)),
            listOf(slotFailure(G05Id.DESTINATION, c, aL, c.at(aL), payload(ControlKind.DEMAND)),
                slotFailure(G05Id.DESTINATION, c, aN, c.at(aN), payload(ControlKind.DEMAND)),
                slotFailure(G05Id.CONFIRMATION, c, aL, c.at(aL)), slotFailure(G05Id.CONFIRMATION, c, aN, c.at(aN))))
        // Without a link chain the destination is the slot's own guard: a same-scope AUTH in another guard is not it.
        val moved = otherGuardSlots(c, aL, aN)
        rejected(assess(c, interpretable(F.read(F.raw(F.guard(auth = required, id = "other-guard")))), handoff(c, moved)),
            listOf(slotFailure(G05Id.DESTINATION, c, aL, c.at(aL), payload(ControlKind.DEMAND)),
                slotFailure(G05Id.DESTINATION, c, aN, c.at(aN), payload(ControlKind.DEMAND)),
                slotFailure(G05Id.CONFIRMATION, c, aL, c.at(aL)), slotFailure(G05Id.CONFIRMATION, c, aN, c.at(aN))))
    }
    private fun otherGuardSlots(c: Case, aL: RequiredObligationKey, aN: RequiredObligationKey) =
        all(c).map { if (it.key == aL || it.key == aN) owned(it.key, DestinationLocator.Guard("other-guard", GuardPart.AUTH)) else it }

    @Test fun G09f_destination_journalLocator() {
        val c = rCase()
        val jL = c.journalKey(L); val jN = c.journalKey(N)
        val key = (c.a1.orderedSlots.single { it.key == jL }.requirement as SlotRequirement.Required).lowerBound.let { (it as RequiredLowerBound.Journal).key }
        fun h(k: JournalTargetV1) = handoff(c, all(c).map { if (it.key == jL || it.key == jN) owned(it.key, DestinationLocator.Journal(k)) else it })
        rejected(assess(c, rRecord(settled(), journal = "A|u||USER"), h(key)),
            listOf(slotFailure(G05Id.CONFIRMATION, c, jL, 2), slotFailure(G05Id.CONFIRMATION, c, jN, 3)))
        val absent = listOf(slotFailure(G05Id.DESTINATION, c, jL, 2), slotFailure(G05Id.DESTINATION, c, jN, 3),
            slotFailure(G05Id.CONFIRMATION, c, jL, 2), slotFailure(G05Id.CONFIRMATION, c, jN, 3))
        rejected(assess(c, rRecord(settled()), h(key)), absent)
        val other = JournalTargetV1("A", key.axis, "u9")
        val at = G05Location.ActualJournal(0)
        rejected(assess(c, rRecord(settled(), journal = "A|u9||USER"), h(other)),
            listOf(slotFailure(G05Id.DESTINATION, c, jL, 2, at), slotFailure(G05Id.DESTINATION, c, jN, 3, at),
                slotFailure(G05Id.CONFIRMATION, c, jL, 2), slotFailure(G05Id.CONFIRMATION, c, jN, 3)))
    }

    @Test fun G13d_journal_actualIndexIsTheMatchingLine() {
        val c = rCase()
        rejected(assess(c, rRecord(settled(), journal = "A||k|CAPABILITY\nA|u||USER")),
            listOf(cc(c, c.journalKey(L), G05Location.ActualJournal(1)), cc(c, c.journalKey(N), G05Location.ActualJournal(1))))
    }

    /** S^N has no required witness, so only the kind/key check can reject it; S^L also differs by witness. */
    @Test fun G15c_R_settledSameIdWithAnotherKey_conflictsBothBranches() {
        val c = rCase()
        val movedKey = JournalTargetV1("A", com.jay.fxi.data.entitlements.PurgeScope.USER, "u9")
        val moved = NamespaceSettlementFixtures.withWitness(
            NamespaceSettlementFixtures.user.replace("\"epoch\":\"u\"", "\"epoch\":\"u9\""),
            RetiredNamespaceFixtures.witness(R).copy(journal = movedKey))
        rejected(assess(c, interpretable(rRecord(moved))),
            listOf(cc(c, c.sealKey(L), payload(ControlKind.SEAL)), cc(c, c.sealKey(N), payload(ControlKind.SEAL))))
    }

    @Test fun G16b_removeEmptyGuard_sameIdRowIsARequest() {
        val c = lifecycle(FloorGuardFixtures.command())
        val xL = c.key { it.component == ObligationComponent.SOURCE && it.branch == L }
        rejected(assess(c, interpretable(F.read(F.raw(F.request(id = "g"))))), listOf(cc(c, xL, payload(ControlKind.DEMAND))))
    }

    @Test fun G18c_guardAndIntentEffects_applyGuardAndIntentRules() {
        fun verify(kind: ControlKind, node: ControlNode, latest: ControlRecordRead.Supported) {
            assertFalse("fixture: effect row is interpretable", latest.hasUninterpretable)
            val proof = ConfirmedControlSnapshot(latest)
            val decision = F.decision(outcome = EntitlementsOutcome.StableInactive(false),
                effects = listOf(LifecycleDurableEffect(kind, node, proof)), namespace = proof)
            val plan = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(decision),
                LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
            assertNull("recipe must prepare", plan.preparationFailure)
            val c = lifecycle(F.command(plan))
            completable(c)
            val eL = c.key { it.subject is DecisionEffectScope && it.branch == L }
            val eN = c.key { it.subject is DecisionEffectScope && it.branch == N }
            rejected(assess(c, latest), listOf(cc(c, eL, payload(kind)), cc(c, eN, payload(kind))))
        }
        val guard = F.guard(auth = null, wait = 60_000, id = "effect-guard")
        verify(ControlKind.DEMAND, guard, record(demand = rows(guard)))
        val intent = ControlObligationFixtures.node(ControlObligationFixtures.recovery)
        verify(ControlKind.RECOVERY_INTENT, intent, record { this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = rows(intent) })
    }

    /** Review r5: A1 emits the effect slot without interpreting the effect node, so an opaque effect is reachable. */
    @Test fun G18d_opaqueEffectNode_uninterpretableSameKindRowConflicts() {
        val node = ControlObligationFixtures.node("""{"id":"effect"}""")
        val latest = record(hold = rows(node))
        assertTrue("fixture: opaque hold row", latest.hasUninterpretable)
        val proof = ConfirmedControlSnapshot(latest)
        val decision = F.decision(outcome = EntitlementsOutcome.StableInactive(false),
            effects = listOf(LifecycleDurableEffect(ControlKind.HOLD, node, proof)), namespace = proof)
        val plan = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(decision),
            LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        assertNull("recipe must prepare", plan.preparationFailure)
        val c = lifecycle(F.command(plan))
        completable(c)
        val eL = c.key { it.subject is DecisionEffectScope && it.branch == L }
        val eN = c.key { it.subject is DecisionEffectScope && it.branch == N }
        rejected(assess(c, latest), listOf(cc(c, eL, payload(ControlKind.HOLD)), cc(c, eN, payload(ControlKind.HOLD))))
    }

    @Test fun G19c_decisionNamespace_ownerAxisStillBefore_or_ownerWrongType() {
        val before = F.fence.copy(ownerUid = "B")
        val proof = ConfirmedControlSnapshot(empty())
        val decision = F.decision(q = F.query.copy(fence = before), before = before,
            outcome = EntitlementsOutcome.StableInactive(false), namespace = proof)
        val plan = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(decision),
            LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        assertNull("recipe must prepare", plan.preparationFailure)
        val c = lifecycle(F.command(plan))
        completable(c)
        val nL = c.key { it.subject is DecisionNamespaceScope && it.branch == L }
        val nN = c.key { it.subject is DecisionNamespaceScope && it.branch == N }
        accepted(assess(c, empty()))
        rejected(assess(c, record { this[OWNER_UID] = "B" }),
            listOf(cc(c, nL, meta), cc(c, nN, meta)))
        rejected(assess(c, record { remove(OWNER_UID); this[intPreferencesKey(OWNER_UID.name)] = 7 }),
            listOf(cc(c, nL, meta), cc(c, nN, meta)))
    }

    @Test fun G21c_owner_blankOwnerKey() {
        val c = rCase()
        rejected(assess(c, rRecord(settled()), handoff(c, owner = ResponsibilityOwner(c.ref.ownerTrackingLifetimeId, "")),
            closure = TerminationClosures.of(c.ref, owner = "")),
            listOf(G05Failure(G05Id.OWNER, null, null, null, null)))
    }

    @Test fun G21d_owner_closureBindsAnotherCommand_or_anotherLifetime() {
        val c = rCase()
        val other = rCase()
        for (closure in listOf(TerminationClosures.of(c.ref, command = other.ref),
            TerminationClosures.of(c.ref, lifetime = OwnerTrackingLifetimeId.issue())))
            rejected(assess(c, rRecord(settled()), closure = closure), listOf(G05Failure(G05Id.OWNER, null, null, null, G05Location.Closure)))
    }

    // ── mutation / lifecycle recipes ───────────────────────────────────────────────────────────────────────────────────
    private fun add(kind: ControlKind, json: String): ControlMutation.Add = ControlMutation.Add.prepare(kind, uuid()) { id ->
        literal(json); set("id", ControlScalar.Text(id))
    }
    private fun after(m: ControlMutation.Add) = (m.built as ControlWriteResult.Written).node
    private fun addCase(m: ControlMutation.Add): Case {
        val ref = CommandRef("cmd-${uuid()}", listOf(m), OwnerTrackingLifetimeId.issue())
        return Case(ref, RequirementInput.Mutations(ref, ref.body as ControlCommandBody.Mutations,
            MutationAdoption.Current(listOf(ControlCommandTarget(m.proposedId, after(m), false)))))
    }
    private fun ControlNode.json() = toPayloadEntry().fields.toString()

    private fun initCase(): Case {
        val p = DemandAuthPlan.auth(null, null, F.binding, LifecycleAuthEvent.Initialize, LifecycleOrderSource(F.life, 21), "g-init", "unused-r")
        assertNull("recipe must prepare", p.preparationFailure)
        return lifecycle(F.command(p))
    }
    private fun Case.requiredAuth() = (a1.orderedSlots.single { it.key.component == ObligationComponent.AUTH && it.key.branch == LandingBranch.L }
        .requirement as SlotRequirement.Required).lowerBound.let { (it as RequiredLowerBound.Auth).required }

    /** Floor-free effect/fence case (A04b shape with StableInactive): REQUEST effect "effect", USER epoch "u-old" → "u". */
    private fun effectCase(): Case {
        val effect = F.request(id = "effect", order = 7)
        val proof = ConfirmedControlSnapshot(F.read(F.raw(effect)))
        val oldFence = F.fence.copy(userAccessEpoch = "u-old")
        val dcs = F.decision(q = F.query.copy(fence = oldFence), before = oldFence,
            outcome = EntitlementsOutcome.StableInactive(false),
            effects = listOf(LifecycleDurableEffect(ControlKind.DEMAND, effect, proof)), namespace = proof)
        val p = DemandAuthPlan.auth(F.guard(), null, F.binding, LifecycleAuthEvent.Answer(dcs), LifecycleOrderSource(F.life, 21), "unused-g", "unused-r")
        assertNull("recipe must prepare", p.preparationFailure)
        return lifecycle(F.command(p))
    }

    /** Recovery C09 recipe: current-owner CAPABILITY intent "r", before (A,u,k), fresh capability epoch H.krxEpoch. */
    private fun intentNode() = ControlObligationFixtures.node(
        """{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k"}""")
    private fun intentCase(): Case {
        val source = intentNode()
        val op = "00000000-0000-0000-0000-000000000201"
        val input = RecoverIntentInput(source, FenceV1("A", "u", "k"), H.binding,
            HoldRecoveryClosure.AfterRestart(source, H.executor, "old-tracking", true, true))
        val plan = RecoverIntentPlan.prepare(input, RecoverIntentIds(op, "intent-request", RecoveryFreshEpochs(null, H.krxEpoch)),
            LifecycleOrderSource(LifetimeId("new-life"), 21))
        assertNull("recipe must prepare", plan.preparationProblem)
        val c = lifecycle(CommandRef(op, ControlCommandBody.Lifecycle(plan.descriptor()), OwnerTrackingLifetimeId.issue()))
        val scope = c.retirementKey(L).subject as RetirementScope
        assertEquals("fixture: capability axis moves k → fresh", "k", scope.before.krxCapabilityEpoch)
        assertEquals("fixture: capability axis moves k → fresh", H.krxEpoch, scope.after.krxCapabilityEpoch)
        return c
    }
    private fun Case.intentKey(b: LandingBranch) = key { it.component == ObligationComponent.SOURCE && it.branch == b }
    private fun Case.retirementKey(b: LandingBranch) = key { it.component == ObligationComponent.NAMESPACE_RETIREMENT && it.branch == b }
}
