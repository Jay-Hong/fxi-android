package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.RecordTransactionEvidence
import com.jay.fxi.data.entitlements.RecordTransactionResult
import com.jay.fxi.data.entitlements.RefreshIntent
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bA3b1 contract: named transfer link issuance, the named REQUEST comparator, and the retained-source
 * extension to Intent / Seal / Request (T4). Fixed inputs: 6-4bA3 declaration r2 §1, 6-4bA3 consensus r1–r2, 6-4bA3b design
 * consensus (6-4bA3b_consensus.md), 6-4bA3b1 recipes r1 (6-4bA3b1_recipes_codex.r1.md). Link inputs start from the real
 * store's own Confirmed (seed → prepare → execute) and the token confirmLifecycleOutput issues for it; expected link fields
 * and destination tuples are literals from the writer tests' independent landing facts (DemandAuthWriterTest.kt:65–72,
 * HoldRecoveryFixtures.candidate), never the issuer's output.
 *
 * Decisions this contract fixes (flagged for review):
 *  - N6: NamedTransferFailureKind.RELATED_EFFECT_MISMATCH is removed from the declaration. A token only exists if
 *    confirmLifecycleOutput checked the related effects (NAMED_EFFECT_MISMATCH) and its constructor is private, so no input
 *    to linkNamedTransfer can reach it (recipes r1 §1 C1). L00 pins the remaining six kinds.
 *  - N7: GuardFloor keeps (owner, preimage, parsed) with no previous-link field; continuity across links is judged by the
 *    A3b2 chain check (DurablyOwned.linkChain already orders the links). Here a GuardFloor source is the fixed guard
 *    preimage of the same RECOVER_HOLD (the guard's own floor responsibility).
 *  - Link reason order: a non-LifecycleOutput token → CONFIRMATION_MISMATCH first; otherwise UNSUPPORTED_TRANSITION_OR_REPLACEMENT
 *    → SOURCE_MISMATCH (source tuple vs the token's fixed descriptor: before/preimage text, HOLD text, old guard, mergeNow,
 *    executor origin) → DESTINATION_MISMATCH (destination locator/row/parsed internally inconsistent) → OWNER_MISMATCH →
 *    CONFIRMATION_MISMATCH (a consistent destination that is not the token's output) → REQUEST_INTENT_OR_GRANT →
 *    Reanchor(field) (validHoldReanchor over the tuple itself: parsedHold vs originalHold, floor, guardBefore vs oldGuard, …).
 *  - compareNamedRequest (ObligationComparators.kt, next to compareRequest which is unchanged) returns the table in C01–C09.
 *  - Retained Request/Intent/Seal: the slot's bound source must equal the fixed SOURCE/BEFORE row (else SUBJECT_OR_BOUND_MISMATCH);
 *    the observed row must equal it and meet the branch's minimums (else NO_EXACT_RETAINED_ROW). A REQUEST slot without a
 *    fixed SOURCE/BEFORE row (named source=null, Add) is NOT_A_REQUIRED_SOURCE. The retained reason order of A3a is unchanged.
 * assessG05 is untouched in A3b1 (A3b2). The implementation thread reads but does not edit this file.
 */
class NamedTransferLinkContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val opened = mutableListOf<ControlStoreTestStorage>()
    private fun open(file: File = folder.newFile()) = ControlStoreTestStorage(file).also { opened += it }
    @After fun close() = runReleaseTest { controlTestTimeout("A3b1 cleanup", 30_000) { opened.reversed().forEach { it.close() } } }
    private val F = DemandAuthFixtures
    private val H = HoldRecoveryFixtures

    // ── real store returns (the A3a recipes) ───────────────────────────────────────────────────────────────────────────
    private class Run(val store: ControlStoreTestStorage, val c: CommandRef, val confirmed: ControlStoreResult.Confirmed) {
        val fixed get() = (c.body as ControlCommandBody.Lifecycle).input
    }
    private suspend fun execute(store: ControlStoreTestStorage, c: CommandRef, context: AttemptContext): ControlStoreResult.Confirmed {
        val result = controlTestTimeout("A3b1 execute") { store.control.execute(c, context) }
        assertTrue("fixture: the store must return Confirmed, got $result", result is ControlStoreResult.Confirmed)
        return result as ControlStoreResult.Confirmed
    }
    private val oldRebind get() = F.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
    private suspend fun rebind(orderStart: Long = 21, old: ControlNode = oldRebind): Run {
        val s = open()
        controlTestTimeout("seed rebind") { s.data.updateData { F.raw(old, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareRebindRequests(listOf(old), F.binding, LifecycleOrderSource(F.life, orderStart))
        return Run(s, c, execute(s, c, F.context(F.runtime())))
    }
    private val endGuard get() = F.guard(F.auth.copy(binding = 2, originLifetimeId = LifetimeId("old")), 90000)
    private val oldEnd get() = F.request(binding = 2, origin = LifetimeId("old"))
    private val endClosure get() = LifecycleBindingClosure(guard(endGuard)!!.auth!!, true, setOf("query", "observer"), setOf("query", "observer"), 5)
    private suspend fun end(): Run {
        val s = open()
        controlTestTimeout("seed end") { s.data.updateData { F.raw(endGuard, oldEnd, F.request(id = "dormant", owner = "B")) } }
        val c = s.control.prepareEndAuthBinding(endGuard, listOf(oldEnd), F.binding, endClosure, F.binding, LifecycleOrderSource(F.life, 21))
        return Run(s, c, execute(s, c, F.context(F.runtime(closure = endClosure))))
    }
    private fun holdInput(now: BootReading = H.now): RecoverHoldInput {
        val base = H.input()
        return RecoverHoldInput(base.source, base.guard, base.before, base.binding, base.closure, now)
    }
    private suspend fun recoverHold(now: BootReading = H.now): Run {
        val s = open()
        val input = holdInput(now)
        controlTestTimeout("seed hold") { s.data.updateData { H.before(input) } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
        return Run(s, c, execute(s, c, H.context(input)))
    }
    private val requestTarget = LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE)
    private val guardTarget = LifecycleTarget(ControlKind.DEMAND, "g", LifecycleEffect.REPLACE)
    private fun token(run: Run, target: LifecycleTarget): PriorStorageConfirmation {
        val r = PriorStorageConfirmation.confirmLifecycleOutput(run.c, run.fixed, run.confirmed, target)
        assertTrue("fixture: the A3a issuer must issue, got $r", r is LifecycleOutputConfirmationResult.Issued)
        return (r as LifecycleOutputConfirmationResult.Issued).value
    }

    // ── independent expected values ────────────────────────────────────────────────────────────────────────────────────
    private fun rebound(order: Long = 22, intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM) =
        F.request(id = "r", owner = "A", binding = 3, origin = F.life, intent = intent, order = order)
    private fun reboundValue(order: Long = 22, intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM) =
        DemandV1("r", "A", 3, intent, EventOrderV1(LifetimeId("life"), order))
    private val oldRebindValue = DemandV1("r", "A", 2, RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("old"), Long.MAX_VALUE))
    private val oldEndValue = DemandV1("r", "A", 2, RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("old"), 4))
    private fun recoveredGuard(anchor: Long = 11000, wait: Long = 29000) =
        H.field(H.guard(), "floor", FloorGuardFixtures.floor(wait, "boot", anchor, "new-life"))
    private fun recoveredGuardValue(anchor: Long = 11000, wait: Long = 29000) = ScheduleGuardV1("g",
        FloorV1("boot", anchor, wait, LifetimeId("new-life")), AuthSnapshotV1("A", 2, 3, LifetimeId("life"), false, 10, 20))
    private val oldGuardValue = ScheduleGuardV1("g", FloorV1("boot", 10000, 10000, LifetimeId("life")),
        AuthSnapshotV1("A", 2, 3, LifetimeId("life"), false, 10, 20))
    private fun parsedHold(node: ControlNode = H.hold()) = (ControlObligations.read(ControlKind.HOLD, node) as ControlEntryRead.Interpreted).value as RestoredHold
    private val holdFloor = FloorV1("boot", 10000, 30000, LifetimeId("life"))

    // ── tuples ─────────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun owner(run: Run) = ResponsibilityOwner(run.c.ownerTrackingLifetimeId, "owner-1")
    private fun derivation(c: CommandRef) = deriveRequiredObligations(RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle))
        as RequirementDerivation.Available
    private fun requestN(run: Run) = (derivation(run.c).orderedSlots.single {
        it.key.component == ObligationComponent.REQUEST && it.key.branch == LandingBranch.N
    }.requirement as SlotRequirement.Required).lowerBound as RequiredLowerBound.Request
    private fun requestSource(run: Run, preimage: ControlNode, parsed: DemandV1, minIntent: RefreshIntent = requestN(run).minimumIntent,
        minOrder: EventOrderV1 = requestN(run).minimumOrder, who: ResponsibilityOwner = owner(run)) =
        TypedSourceTuple.Request(who, preimage, parsed, minIntent, minOrder)
    private fun requestDestination(order: Long = 22, parsed: DemandV1 = reboundValue(order), id: String = "r",
        intent: RefreshIntent = RefreshIntent.FORCE_PREMIUM) =
        TypedDestinationTuple.Request(DestinationLocator.Payload(ControlKind.DEMAND, id), rebound(order, intent),
            if (intent == RefreshIntent.FORCE_PREMIUM) parsed else reboundValue(order, intent))
    private fun holdSource(run: Run, originalHold: ControlNode = H.hold(), parsed: RestoredHold = parsedHold(),
        floor: FloorV1 = holdFloor, guardBefore: ControlNode? = H.guard(), oldGuard: ControlNode? = H.guard(),
        mergeNow: BootReading = H.now, origin: LifetimeId = H.life, who: ResponsibilityOwner = owner(run)) =
        TypedSourceTuple.HoldFloor(who, FloorSource("h", originalHold, parsed, floor, guardBefore), oldGuard, mergeNow, origin)
    private fun guardDestination(anchor: Long = 11000, wait: Long = 29000, parsed: ScheduleGuardV1 = recoveredGuardValue(anchor, wait)) =
        TypedDestinationTuple.GuardFloor(DestinationLocator.Guard("g", GuardPart.FLOOR), recoveredGuard(anchor, wait), parsed)

    private fun issued(r: NamedTransferResult): NamedTransferLink {
        assertTrue("expected Issued, got $r", r is NamedTransferResult.Issued)
        return (r as NamedTransferResult.Issued).value
    }
    private fun invalid(r: NamedTransferResult, kind: NamedTransferFailureKind) =
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(kind)), r)
    private fun reanchor(r: NamedTransferResult, field: ReanchorField) =
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Reanchor(field)), r)
    private fun link(source: TypedSourceTuple, destination: TypedDestinationTuple, token: PriorStorageConfirmation) =
        NamedTransferLink.linkNamedTransfer(source, destination, token)
    private fun assertLink(l: NamedTransferLink, transition: LifecycleTransition, source: TypedSourceTuple,
        destination: TypedDestinationTuple, token: PriorStorageConfirmation) {
        assertEquals(transition, l.transition)
        assertEquals(source, l.source)
        assertEquals(destination, l.destination)
        assertEquals(source.responsibilityOwner, l.responsibilityOwner)
        assertSame(token, l.confirmation)
    }

    // ═══ enum pin ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun L00_failureKinds_withoutRelatedEffect() = assertEquals(listOf("UNSUPPORTED_TRANSITION_OR_REPLACEMENT", "SOURCE_MISMATCH",
        "DESTINATION_MISMATCH", "OWNER_MISMATCH", "CONFIRMATION_MISMATCH", "REQUEST_INTENT_OR_GRANT"),
        NamedTransferFailureKind.entries.map { it.name })

    // ═══ link positives ════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun L01_rebind_sameIdRequestLink() = runReleaseTest {
        val run = rebind(); val t = token(run, requestTarget)
        val source = requestSource(run, oldRebind, oldRebindValue); val dest = requestDestination()
        assertLink(issued(link(source, dest, t)), LifecycleTransition.REBIND_REQUESTS, source, dest, t)
    }

    @Test fun L02_endAuthBinding_sameIdRequestLink() = runReleaseTest {
        val run = end(); val t = token(run, requestTarget)
        val source = requestSource(run, oldEnd, oldEndValue); val dest = requestDestination()
        assertLink(issued(link(source, dest, t)), LifecycleTransition.END_AUTH_BINDING, source, dest, t)
    }

    @Test fun L03_recoverHold_holdFloorLink() = runReleaseTest {
        val run = recoverHold(); val t = token(run, guardTarget)
        val source = holdSource(run); val dest = guardDestination()
        assertLink(issued(link(source, dest, t)), LifecycleTransition.RECOVER_HOLD, source, dest, t)
    }

    @Test fun L04_recoverHold_guardFloorSource_isTheFixedOldGuard() = runReleaseTest {
        val run = recoverHold(); val t = token(run, guardTarget)
        val source = TypedSourceTuple.GuardFloor(owner(run), H.guard(), oldGuardValue); val dest = guardDestination()
        assertLink(issued(link(source, dest, t)), LifecycleTransition.RECOVER_HOLD, source, dest, t)
        invalid(link(TypedSourceTuple.GuardFloor(owner(run), recoveredGuard(), recoveredGuardValue()), dest, t),
            NamedTransferFailureKind.SOURCE_MISMATCH)
    }

    // ═══ link negatives: REQUEST transitions ══════════════════════════════════════════════════════════════════════════
    @Test fun N01_nonLifecycleOutputToken_isConfirmationMismatchFirst() = runReleaseTest {
        val s = open()
        controlTestTimeout("seed held") { s.data.updateData { H.before(H.input()) } }
        val c = s.control.prepareRecoverHold(H.input(), LifecycleOrderSource(H.life, 21))
        val slot = derivation(c).orderedSlots.single { it.key.component == ObligationComponent.SOURCE && it.key.subject is ObligationSubject.Hold && it.key.branch == LandingBranch.N }
        val read = controlTestTimeout("owner locked read") {
            s.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        val retained = (PriorStorageConfirmation.confirmRetainedSource(slot, DestinationLocator.Payload(ControlKind.HOLD, "h"), read)
            as RetainedSourceConfirmationResult.Issued).value
        val run = rebind()
        // Even with an unsupported destination type, the wrong token kind wins.
        invalid(link(requestSource(run, oldRebind, oldRebindValue), guardDestination(), retained), NamedTransferFailureKind.CONFIRMATION_MISMATCH)
    }

    @Test fun N02_unsupported_wrongDestinationType_forEachTransition() = runReleaseTest {
        val run = rebind()
        invalid(link(requestSource(run, oldRebind, oldRebindValue), guardDestination(), token(run, requestTarget)),
            NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)
        val hold = recoverHold()
        invalid(link(holdSource(hold), requestDestination(), token(hold, guardTarget)), NamedTransferFailureKind.UNSUPPORTED_TRANSITION_OR_REPLACEMENT)
    }

    @Test fun N03_sourceMismatch_beforeTextOrder() = runReleaseTest {
        val run = rebind(); val t = token(run, requestTarget)
        val other = F.request(binding = 2, origin = LifetimeId("old"), order = 5)
        invalid(link(requestSource(run, other, oldRebindValue.copy(raisedAt = EventOrderV1(LifetimeId("old"), 5))), requestDestination(), t),
            NamedTransferFailureKind.SOURCE_MISMATCH)
    }

    @Test fun N03b_sourceMismatch_parsedOnly_preimageKept() = runReleaseTest {
        val run = rebind(); val t = token(run, requestTarget)
        invalid(link(requestSource(run, oldRebind, oldRebindValue.copy(raisedAt = EventOrderV1(LifetimeId("old"), 5))), requestDestination(), t),
            NamedTransferFailureKind.SOURCE_MISMATCH)
    }

    @Test fun N04b_destinationMismatch_locatorIdOnly() = runReleaseTest {
        val run = rebind(); val t = token(run, requestTarget)
        invalid(link(requestSource(run, oldRebind, oldRebindValue), requestDestination(id = "other"), t), NamedTransferFailureKind.DESTINATION_MISMATCH)
    }

    @Test fun N04_destinationMismatch_parsedDisagreesWithRow() = runReleaseTest {
        val run = rebind(); val t = token(run, requestTarget)
        invalid(link(requestSource(run, oldRebind, oldRebindValue), requestDestination(parsed = reboundValue(23)), t),
            NamedTransferFailureKind.DESTINATION_MISMATCH)
    }

    @Test fun N05_ownerMismatch_otherTrackingLifetime() = runReleaseTest {
        val run = rebind(); val t = token(run, requestTarget)
        invalid(link(requestSource(run, oldRebind, oldRebindValue, who = ResponsibilityOwner(OwnerTrackingLifetimeId.issue(), "owner-1")),
            requestDestination(), t), NamedTransferFailureKind.OWNER_MISMATCH)
    }

    @Test fun N06_confirmationMismatch_consistentDestinationOfAnotherRun() = runReleaseTest {
        val first = rebind()
        val second = rebind(orderStart = 22)
        val t2 = token(second, requestTarget)
        assertEquals("fixture: the second run lands order 23",
            rebound(23).toPayloadEntry(), (t2.binding as ConfirmationBinding.LifecycleOutput).output.row.toPayloadEntry())
        // The source minimum stays at the destination's order 22, so only the token binding is broken.
        invalid(link(requestSource(second, oldRebind, oldRebindValue, minOrder = EventOrderV1(F.life, 22)), requestDestination(22), t2),
            NamedTransferFailureKind.CONFIRMATION_MISMATCH)
        assertTrue(first.c !== second.c)
    }

    @Test fun N07_requestIntentOrGrant_newScopeMinimumAboveOutput() = runReleaseTest {
        val run = rebind(); val t = token(run, requestTarget)
        invalid(link(requestSource(run, oldRebind, oldRebindValue, minOrder = EventOrderV1(F.life, 23)), requestDestination(), t),
            NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
    }

    @Test fun N07b_requestIntentOrGrant_intentMinimumAboveOutput() = runReleaseTest {
        val weak = F.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE, intent = RefreshIntent.IF_STALE)
        val run = rebind(old = weak); val t = token(run, requestTarget)
        assertEquals("fixture: REBIND keeps the weak intent",
            rebound(intent = RefreshIntent.IF_STALE).toPayloadEntry(), (t.binding as ConfirmationBinding.LifecycleOutput).output.row.toPayloadEntry())
        val weakValue = oldRebindValue.copy(intent = RefreshIntent.IF_STALE)
        invalid(link(requestSource(run, weak, weakValue, minIntent = RefreshIntent.FORCE_PREMIUM), requestDestination(intent = RefreshIntent.IF_STALE), t),
            NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
    }

    @Test fun N08_multipleDefects_firstReasonWins() = runReleaseTest {
        val run = rebind(); val t = token(run, requestTarget)
        val stranger = ResponsibilityOwner(OwnerTrackingLifetimeId.issue(), "owner-1")
        // SOURCE before OWNER.
        invalid(link(requestSource(run, F.request(binding = 2, origin = LifetimeId("old"), order = 5),
            oldRebindValue.copy(raisedAt = EventOrderV1(LifetimeId("old"), 5)), who = stranger), requestDestination(), t),
            NamedTransferFailureKind.SOURCE_MISMATCH)
        // DESTINATION before OWNER.
        invalid(link(requestSource(run, oldRebind, oldRebindValue, who = stranger), requestDestination(parsed = reboundValue(23)), t),
            NamedTransferFailureKind.DESTINATION_MISMATCH)
        // OWNER before REQUEST_INTENT_OR_GRANT.
        invalid(link(requestSource(run, oldRebind, oldRebindValue, minOrder = EventOrderV1(F.life, 23), who = stranger), requestDestination(), t),
            NamedTransferFailureKind.OWNER_MISMATCH)
    }

    // ═══ link negatives: RECOVER_HOLD ══════════════════════════════════════════════════════════════════════════════════
    @Test fun H01_sourceMismatch_holdText_oldGuard_mergeNow_origin() = runReleaseTest {
        val run = recoverHold(); val t = token(run, guardTarget); val dest = guardDestination()
        val longerHold = H.field(H.hold(), "floor", FloorGuardFixtures.floor(30000, "boot", 9000, "life"))
        invalid(link(holdSource(run, originalHold = longerHold), dest, t), NamedTransferFailureKind.SOURCE_MISMATCH)
        invalid(link(holdSource(run, guardBefore = H.guard(9000), oldGuard = H.guard(9000)), dest, t), NamedTransferFailureKind.SOURCE_MISMATCH)
        invalid(link(holdSource(run, mergeNow = BootReading("boot", 12000)), dest, t), NamedTransferFailureKind.SOURCE_MISMATCH)
        invalid(link(holdSource(run, origin = LifetimeId("other-life")), dest, t), NamedTransferFailureKind.SOURCE_MISMATCH)
    }

    @Test fun H02_destinationMismatch_parsedFloorDisagreesWithRow() = runReleaseTest {
        val run = recoverHold(); val t = token(run, guardTarget)
        invalid(link(holdSource(run), guardDestination(parsed = recoveredGuardValue(wait = 28000)), t), NamedTransferFailureKind.DESTINATION_MISMATCH)
    }

    @Test fun H03_ownerMismatch() = runReleaseTest {
        val run = recoverHold(); val t = token(run, guardTarget)
        invalid(link(holdSource(run, who = ResponsibilityOwner(OwnerTrackingLifetimeId.issue(), "owner-1")), guardDestination(), t),
            NamedTransferFailureKind.OWNER_MISMATCH)
    }

    /** Precedence: the other recovery's guard is not the token's output AND its anchor breaks the reanchor; CONFIRMATION wins. */
    @Test fun H04_precedence_confirmationBeforeReanchor_guardOfAnotherRecovery() = runReleaseTest {
        val later = recoverHold(BootReading("boot", 12000)); val t = token(later, guardTarget)
        assertEquals("fixture: the later recovery anchors at 12000 with 28000 left",
            recoveredGuard(12000, 28000).toPayloadEntry(), (t.binding as ConfirmationBinding.LifecycleOutput).output.row.toPayloadEntry())
        invalid(link(holdSource(later, mergeNow = BootReading("boot", 12000)), guardDestination(), t), NamedTransferFailureKind.CONFIRMATION_MISMATCH)
    }

    @Test fun H05_reanchor_tupleInternal_provenance_sourceFloor_guardPreimage() = runReleaseTest {
        val run = recoverHold(); val t = token(run, guardTarget); val dest = guardDestination()
        val parsed = parsedHold()
        val query = parsed.provenance as HoldProvenanceV1.Query
        reanchor(link(holdSource(run, parsed = parsed.copy(provenance = query.copy(answeredAs = null))), dest, t), ReanchorField.PROVENANCE)
        reanchor(link(holdSource(run, floor = holdFloor.copy(waitMillis = 20000)), dest, t), ReanchorField.SOURCE_FLOOR)
        reanchor(link(holdSource(run, guardBefore = H.guard(9000)), dest, t), ReanchorField.GUARD_PREIMAGE)
    }

    // ═══ compareNamedRequest ═══════════════════════════════════════════════════════════════════════════════════════════
    private suspend fun requestLink(end: Boolean): Pair<Run, NamedTransferLink> {
        val run = if (end) end() else rebind(); val t = token(run, requestTarget)
        val source = if (end) requestSource(run, oldEnd, oldEndValue) else requestSource(run, oldRebind, oldRebindValue)
        return run to issued(link(source, requestDestination(), t))
    }
    private fun need(source: DemandV1, minIntent: RefreshIntent = RefreshIntent.FORCE_PREMIUM, minOrder: EventOrderV1) =
        RequestNeed(source, minIntent, minOrder)

    @Test fun C01_matches_rebind_and_end_withTheirNSlotNeed() = runReleaseTest {
        for (isEnd in listOf(false, true)) {
            val (run, l) = requestLink(isEnd)
            val n = requestN(run)
            val source = if (isEnd) oldEndValue else oldRebindValue
            assertEquals(TypedComparison.Matches, compareNamedRequest(need(source, n.minimumIntent, n.minimumOrder), l, reboundValue()))
        }
    }

    @Test fun C02_originalScopeMinimumAboveOriginalOrder() = runReleaseTest {
        val (_, l) = requestLink(true)
        assertEquals(TypedComparison.Mismatch(RequestField.ORDER),
            compareNamedRequest(need(oldEndValue, minOrder = EventOrderV1(LifetimeId("old"), 5)), l, reboundValue()))
    }

    @Test fun C03_newScopeMinimumAboveOutputOrder() = runReleaseTest {
        val (_, l) = requestLink(false)
        assertEquals(TypedComparison.Mismatch(RequestField.ORDER),
            compareNamedRequest(need(oldRebindValue, minOrder = EventOrderV1(F.life, 23)), l, reboundValue()))
    }

    @Test fun C04_actualOrderNotTheGrant() = runReleaseTest {
        val (run, l) = requestLink(false); val n = requestN(run)
        for (order in listOf(21L, 23L)) assertEquals(TypedComparison.Mismatch(RequestField.ORDER),
            compareNamedRequest(need(oldRebindValue, n.minimumIntent, n.minimumOrder), l, reboundValue(order)))
    }

    @Test fun C05_actualIntentBelowMinimum() = runReleaseTest {
        val (run, l) = requestLink(false); val n = requestN(run)
        assertEquals(TypedComparison.Mismatch(RequestField.INTENT),
            compareNamedRequest(need(oldRebindValue, n.minimumIntent, n.minimumOrder), l, reboundValue().copy(intent = RefreshIntent.IF_STALE)))
    }

    @Test fun C06_actualOwner_or_id() = runReleaseTest {
        val (run, l) = requestLink(false); val n = requestN(run); val need = need(oldRebindValue, n.minimumIntent, n.minimumOrder)
        assertEquals(TypedComparison.Mismatch(RequestField.OWNER), compareNamedRequest(need, l, reboundValue().copy(ownerUid = "B")))
        assertEquals(TypedComparison.Mismatch(RequestField.SUBJECT), compareNamedRequest(need, l, reboundValue().copy(id = "other")))
    }

    @Test fun C07_actualScopeTheLinkDidNotProve() = runReleaseTest {
        val (run, l) = requestLink(false); val n = requestN(run)
        assertEquals(TypedComparison.Unavailable(TypedUnavailableReason.REBIND_UNSUPPORTED),
            compareNamedRequest(need(oldRebindValue, n.minimumIntent, n.minimumOrder), l, reboundValue().copy(binding = 4)))
    }

    @Test fun C08_namedLinkRequired_holdLink_or_foreignSource() = runReleaseTest {
        val hold = recoverHold(); val t = token(hold, guardTarget)
        val holdLink = issued(link(holdSource(hold), guardDestination(), t))
        val (run, l) = requestLink(false); val n = requestN(run)
        assertEquals(TypedComparison.Unavailable(TypedUnavailableReason.NAMED_LINK_REQUIRED),
            compareNamedRequest(need(oldRebindValue, n.minimumIntent, n.minimumOrder), holdLink, reboundValue()))
        assertEquals(TypedComparison.Unavailable(TypedUnavailableReason.NAMED_LINK_REQUIRED),
            compareNamedRequest(need(oldEndValue, n.minimumIntent, n.minimumOrder), l, reboundValue()))
    }

    @Test fun C09_invalidInput_foreignScope_or_negativeOrder() = runReleaseTest {
        val (_, l) = requestLink(false)
        assertEquals(TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT),
            compareNamedRequest(need(oldRebindValue, minOrder = EventOrderV1(LifetimeId("foreign"), 1)), l, reboundValue()))
        assertEquals(TypedComparison.Unavailable(TypedUnavailableReason.INVALID_INPUT),
            compareNamedRequest(need(oldRebindValue, minOrder = EventOrderV1(F.life, -1)), l, reboundValue()))
    }

    // ═══ retained extension ════════════════════════════════════════════════════════════════════════════════════════════
    private suspend fun lockedRead(s: ControlStoreTestStorage): RecordTransactionResult<ControlRecordRead.Supported> {
        val locked = controlTestTimeout("owner locked read") {
            s.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
        }
        assertEquals(RecordTransactionEvidence.LockedFileRead, locked.evidence)
        assertEquals(locked.snapshot, locked.value.original)
        return locked
    }
    private suspend fun seeded(raw: Preferences) = open().also { s -> controlTestTimeout("seed") { s.data.updateData { raw } } }
    private fun issuedRetained(r: RetainedSourceConfirmationResult): ConfirmationBinding.RetainedSource {
        assertTrue("expected Issued, got $r", r is RetainedSourceConfirmationResult.Issued)
        return (r as RetainedSourceConfirmationResult.Issued).value.binding as ConfirmationBinding.RetainedSource
    }
    private fun rejectedRetained(r: RetainedSourceConfirmationResult, reason: RetainedConfirmationFailure) =
        assertEquals(RetainedSourceConfirmationResult.Rejected(reason), r)
    private fun rows(nodes: List<ControlNode>) = nodes.joinToString(",", "[", "]") { it.toPayloadEntry().fields.toString() }

    /** Recovery C09 recipe: current-owner CAPABILITY intent "r", prepared and never executed. */
    private val intentRow get() = ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k"}""")
    private fun intentCommand(): CommandRef {
        val source = intentRow
        val input = RecoverIntentInput(source, FenceV1("A", "u", "k"), H.binding, HoldRecoveryClosure.AfterRestart(source, H.executor, "old-tracking", true, true))
        val plan = RecoverIntentPlan.prepare(input, RecoverIntentIds("00000000-0000-0000-0000-000000000201", "intent-request",
            RecoveryFreshEpochs(null, H.krxEpoch)), LifecycleOrderSource(H.life, 21))
        return CommandRef("00000000-0000-0000-0000-000000000201", ControlCommandBody.Lifecycle(plan.descriptor()), OwnerTrackingLifetimeId.issue())
    }
    private fun intentRecord(row: ControlNode?) = ControlLifecycleEvidenceFixtures.raw().toMutablePreferences().apply {
        this[ControlRecordKeys.payload(ControlKind.RECOVERY_INTENT)] = if (row == null) "[]" else rows(listOf(row))
    }.toPreferences()

    @Test fun T01_retainedIntent_andChangedSlot_changedRow_absentRow() = runReleaseTest {
        val slot = derivation(intentCommand()).orderedSlots.single {
            it.key.component == ObligationComponent.SOURCE && it.key.subject is ObligationSubject.Intent && it.key.branch == LandingBranch.N
        }
        val locator = DestinationLocator.Payload(ControlKind.RECOVERY_INTENT, "r")
        val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, locator, lockedRead(seeded(intentRecord(intentRow)))))
        assertEquals(slot, b.slot)
        assertEquals(intentRow.toPayloadEntry(), (b.observed as RetainedDestinationTuple.Payload).row.toPayloadEntry())
        val subject = slot.key.subject as ObligationSubject.Intent
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot.copy(key = slot.key.copy(subject = subject.copy(sessionId = "other"))),
            locator, lockedRead(seeded(intentRecord(intentRow)))), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        val changed = ControlObligationFixtures.node("""{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k2"}""")
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, locator, lockedRead(seeded(intentRecord(changed)))),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(slot, locator, lockedRead(seeded(intentRecord(null)))),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    private val R = RetiredNamespaceFixtures.spec()
    private fun rSlot(branch: LandingBranch): RequiredSlot {
        val ref = RetiredNamespaceFixtures.command(R)
        return (deriveRequiredObligations(RequirementInput.Settlement(ref, ref.body as ControlCommandBody.Handover)) as RequirementDerivation.Available)
            .orderedSlots.single { it.key.component == ObligationComponent.SEAL && it.key.branch == branch }
    }

    @Test fun T02_retainedSeal_activeMeetsN_missesL_changedSlot_changedRow() = runReleaseTest {
        val locator = DestinationLocator.Payload(ControlKind.SEAL, "s")
        val n = rSlot(LandingBranch.N)
        val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(n, locator, lockedRead(seeded(RetiredNamespaceFixtures.raw(R)))))
        assertEquals(ControlObligationFixtures.node(NamespaceSettlementFixtures.user).toPayloadEntry(),
            (b.observed as RetainedDestinationTuple.Payload).row.toPayloadEntry())
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(rSlot(LandingBranch.L), locator, lockedRead(seeded(RetiredNamespaceFixtures.raw(R)))),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
        val subject = n.key.subject as ObligationSubject.Seal
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(n.copy(key = n.key.copy(subject = subject.copy(key = subject.key.copy(epoch = "u9")))),
            locator, lockedRead(seeded(RetiredNamespaceFixtures.raw(R)))), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        val moved = RetiredNamespaceFixtures.raw(R).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.SEAL)] = NamespaceSettlementFixtures.jsonArray(ControlObligationFixtures.node(
                NamespaceSettlementFixtures.user.replace("\"epoch\":\"u\"", "\"epoch\":\"u9\"")))
        }.toPreferences()
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(n, locator, lockedRead(seeded(moved))), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    /** DemandAuthWriterTest SETTLE_QUERY: REQUEST REMOVE of r, prepared on a seeded store and never executed. */
    @Test fun T03_retainedRequest_settleRemoveN_rebindNMissesNewScope_andSlotsWithoutFixedSource() = runReleaseTest {
        val r = F.request(); val g = F.guard(F.auth.copy(authStopped = false), 90000)
        val raw = F.raw(r, g, F.request(id = "dormant", owner = "B"))
        val s = seeded(raw)
        val c = s.control.prepareSettleQuery(listOf(r), g, null, F.binding, F.decision(followUp = RefreshIntent.FORCE_PREMIUM), LifecycleOrderSource(F.life, 21))
        val n = derivation(c).orderedSlots.single {
            it.key.component == ObligationComponent.REQUEST && it.key.branch == LandingBranch.N && (it.key.subject as? ObligationSubject.Request)?.id == "r"
        }
        val locator = DestinationLocator.Payload(ControlKind.DEMAND, "r")
        val b = issuedRetained(PriorStorageConfirmation.confirmRetainedSource(n, locator, lockedRead(s)))
        assertEquals(r.toPayloadEntry(), (b.observed as RetainedDestinationTuple.Payload).row.toPayloadEntry())
        // Only the bound's source changes (subject, fixed sources, observed row and minimums untouched): slot-side mismatch.
        val req = n.requirement as SlotRequirement.Required
        val bound = req.lowerBound as RequiredLowerBound.Request
        val forged = n.copy(requirement = req.copy(lowerBound = bound.copy(source = checkNotNull(bound.source).copy(intent = RefreshIntent.IF_STALE))))
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(forged, locator, lockedRead(s)), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        // REBIND's N minimum lives in the new scope: the old REQUEST, still present, does not meet it.
        val rebindStore = seeded(F.raw(oldRebind, F.request(id = "dormant", owner = "B")))
        val rc = rebindStore.control.prepareRebindRequests(listOf(oldRebind), F.binding, LifecycleOrderSource(F.life, 21))
        val rn = derivation(rc).orderedSlots.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == LandingBranch.N }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(rn, locator, lockedRead(rebindStore)), RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
        // Named REQUEST with source=null (R) has no fixed SOURCE/BEFORE row. An Add REQUEST is a retained source from its AFTER
        // row since 6-4bC1b-0 (consensus C1b-D2); here its row is absent from the record, so it has no exact retained row.
        val ref = RetiredNamespaceFixtures.command(R)
        val named = (deriveRequiredObligations(RequirementInput.Settlement(ref, ref.body as ControlCommandBody.Handover)) as RequirementDerivation.Available)
            .orderedSlots.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == LandingBranch.N }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(named, DestinationLocator.Payload(ControlKind.DEMAND, "r-demand"),
            lockedRead(seeded(RetiredNamespaceFixtures.raw(R)))), RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
        val add = ControlMutation.Add.prepare(ControlKind.DEMAND, java.util.UUID(0L, 7L)) { id ->
            literal(r.toPayloadEntry().fields.toString()); set("id", ControlScalar.Text(id))
        }
        val addRef = CommandRef("cmd-add", listOf(add), OwnerTrackingLifetimeId.issue())
        val addN = (deriveRequiredObligations(RequirementInput.Mutations(addRef, addRef.body as ControlCommandBody.Mutations,
            MutationAdoption.Current(listOf(ControlCommandTarget(add.proposedId, (add.built as ControlWriteResult.Written).node, false)))))
            as RequirementDerivation.Available).orderedSlots.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == LandingBranch.N }
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(addN, DestinationLocator.Payload(ControlKind.DEMAND, add.proposedId), lockedRead(s)),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }

    // ═══ measurement r1 additions (Codex 6-4bA3b1_survivors_codex.r1.md; forged tokens use the real Confirmed.copy path) ═══
    private fun descriptorWith(
        run: Run,
        targets: List<LifecycleFixedTarget> = run.fixed.targets,
        executor: SettlementExecutor? = run.fixed.executor,
        demandAuth: DemandAuthPlan? = run.fixed.demandAuth,
        recoverHold: RecoverHoldPlan? = run.fixed.recoverHold
    ) = ControlLifecycleDescriptor(
        run.c.id, run.fixed.transition, targets, executor, run.fixed.namespace,
        run.fixed.requiredUnchanged, demandAuth, run.fixed.removeEmptyGuard,
        recoverHold, run.fixed.recoverIntent
    )

    private fun forgedToken(
        run: Run, fixed: ControlLifecycleDescriptor, target: LifecycleTarget
    ): PriorStorageConfirmation {
        val ref = CommandRef(run.c.id, ControlCommandBody.Lifecycle(fixed),
            run.c.ownerTrackingLifetimeId)
        val copied = run.confirmed.copy(command = ref)
        val result = PriorStorageConfirmation.confirmLifecycleOutput(ref, fixed, copied, target)
        assertTrue("fixture: copied Confirmed must pass issuer, got $result",
            result is LifecycleOutputConfirmationResult.Issued)
        return (result as LifecycleOutputConfirmationResult.Issued).value
    }

    private fun alternateRecovery(run: Run, input: RecoverHoldInput) =
        RecoverHoldPlan.prepare(input, checkNotNull(run.fixed.recoverHold).ids,
            LifecycleOrderSource(H.life, 21))

    private suspend fun recoverWith(input: RecoverHoldInput): Run {
        val s = open()
        controlTestTimeout("seed alternate hold") { s.data.updateData { H.before(input) } }
        val c = s.control.prepareRecoverHold(input, LifecycleOrderSource(H.life, 21))
        return Run(s, c, execute(s, c, H.context(input)))
    }

    @Test fun S01_recoveryDescriptorBindings_areSourceFirst() = runReleaseTest {
        val run = recoverHold()
        val source = holdSource(run)
        val dest = guardDestination()

        // K.recHoldBefore
        val otherHoldPlan = alternateRecovery(run, H.input(h = H.hold(id = "other")))
        invalid(link(source, dest,
            forgedToken(run, descriptorWith(run, recoverHold = otherHoldPlan), guardTarget)),
            NamedTransferFailureKind.SOURCE_MISMATCH)

        // K.recHoldAfter
        val nonRemovedAfter = run.fixed.targets.map {
            if (it.role == LifecycleRole.HOLD) it.copy(after = H.hold()) else it
        }
        invalid(link(source, dest,
            forgedToken(run, descriptorWith(run, targets = nonRemovedAfter), guardTarget)),
            NamedTransferFailureKind.SOURCE_MISMATCH)

        // K.recGuard
        val otherGuardPlan = alternateRecovery(run, H.input(g = H.guard(9000)))
        invalid(link(source, dest,
            forgedToken(run, descriptorWith(run, recoverHold = otherGuardPlan), guardTarget)),
            NamedTransferFailureKind.SOURCE_MISMATCH)

        // K.recExecutor
        invalid(link(source, dest,
            forgedToken(run,
                descriptorWith(run, executor = H.executor.copy(binding = 4)), guardTarget)),
            NamedTransferFailureKind.SOURCE_MISMATCH)
    }

    @Test fun S02_sourceTuple_checksAndReasonOrder() = runReleaseTest {
        val request = rebind()
        val requestToken = token(request, requestTarget)
        val requestDest = requestDestination()

        // K.srcReqAfter: id는 같고 before owner만 다르다.
        val beforeB = F.request(owner = "B", binding = 2,
            origin = LifetimeId("old"), order = Long.MAX_VALUE)
        val changedBefore = request.fixed.targets.map {
            if (it.target == requestTarget) it.copy(before = beforeB) else it
        }
        invalid(link(requestSource(request, beforeB, checkNotNull(demand(beforeB))),
            requestDest, forgedToken(request,
                descriptorWith(request, targets = changedBefore), requestTarget)),
            NamedTransferFailureKind.SOURCE_MISMATCH)

        // K.ownerBlank
        invalid(link(requestSource(request, oldRebind, oldRebindValue,
            who = owner(request).copy(ownerKey = "")), requestDest, requestToken),
            NamedTransferFailureKind.OWNER_MISMATCH)

        val hold = recoverHold()
        val holdToken = token(hold, guardTarget)
        val holdSource = holdSource(hold)

        // K.srcHoldId: 뒤의 Reanchor(SOURCE_ID)보다 SOURCE가 먼저다.
        invalid(link(holdSource.copy(source =
            holdSource.source.copy(sourceId = "other")), guardDestination(), holdToken),
            NamedTransferFailureKind.SOURCE_MISMATCH)

        // K.srcGuardParsed
        invalid(link(TypedSourceTuple.GuardFloor(owner(hold), H.guard(),
            oldGuardValue.copy(auth = null)), guardDestination(), holdToken),
            NamedTransferFailureKind.SOURCE_MISMATCH)

        // K.srcGuardFloor: 이전 guard 자체에는 floor가 없어도 recovery 출력은 유효하다.
        val withoutFloor = FloorGuardFixtures.guard(floor = null, auth = true)
        val noFloorRun = recoverWith(H.input(g = withoutFloor))
        invalid(link(TypedSourceTuple.GuardFloor(owner(noFloorRun), withoutFloor,
            checkNotNull(guard(withoutFloor))), guardDestination(),
            token(noFloorRun, guardTarget)), NamedTransferFailureKind.SOURCE_MISMATCH)
    }

    @Test fun S03_destinationFailure_precedesConfirmation() = runReleaseTest {
        val request = rebind()
        val requestSource = requestSource(request, oldRebind, oldRebindValue)
        val requestToken = token(request, requestTarget)

        // K.dstReqKind
        invalid(link(requestSource, TypedDestinationTuple.Request(
            DestinationLocator.Payload(ControlKind.HOLD, "r"),
            rebound(), reboundValue()), requestToken),
            NamedTransferFailureKind.DESTINATION_MISMATCH)

        val hold = recoverHold()
        val holdSource = holdSource(hold)
        val holdToken = token(hold, guardTarget)

        // K.dstGuardPart
        invalid(link(holdSource, guardDestination().copy(
            locator = DestinationLocator.Guard("g", GuardPart.AUTH)), holdToken),
            NamedTransferFailureKind.DESTINATION_MISMATCH)

        // K.dstGuardFloor
        val withoutFloor = FloorGuardFixtures.guard(floor = null, auth = true)
        invalid(link(holdSource, TypedDestinationTuple.GuardFloor(
            DestinationLocator.Guard("g", GuardPart.FLOOR),
            withoutFloor, checkNotNull(guard(withoutFloor))), holdToken),
            NamedTransferFailureKind.DESTINATION_MISMATCH)
    }

    @Test fun S04_requestExecutorGrantAndMinimums() = runReleaseTest {
        val run = rebind()
        val source = requestSource(run, oldRebind, oldRebindValue)
        val dest = requestDestination()
        val t = token(run, requestTarget)

        // K.reqExecutorNull / K.reqGrantNull
        invalid(link(source, dest,
            forgedToken(run, descriptorWith(run, executor = null), requestTarget)),
            NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
        invalid(link(source, dest,
            forgedToken(run, descriptorWith(run, demandAuth = null), requestTarget)),
            NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)

        // K.reqMinElse / K.reqNeg
        invalid(link(source.copy(minimumOrder = EventOrderV1(LifetimeId("foreign"), 1)),
            dest, t), NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
        invalid(link(source.copy(minimumOrder = EventOrderV1(F.life, -1)),
            dest, t), NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)

        // K.reqOwner / K.reqBinding / K.reqOrigin
        invalid(link(source, dest, forgedToken(run, descriptorWith(run,
            executor = F.binding.executor.copy(ownerUid = "B")), requestTarget)),
            NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
        invalid(link(source, dest, forgedToken(run, descriptorWith(run,
            executor = F.binding.executor.copy(binding = 4)), requestTarget)),
            NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
        invalid(link(source, dest, forgedToken(run, descriptorWith(run,
            executor = F.binding.executor.copy(originLifetimeId = LifetimeId("foreign"))),
            requestTarget)), NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)

        // K.reqGrant: target 출력은 order 22, 별도 plan의 grant는 23.
        val otherGrant = DemandAuthPlan.rebind(listOf(oldRebind), F.binding,
            LifecycleOrderSource(F.life, 22))
        invalid(link(source, dest, forgedToken(run,
            descriptorWith(run, demandAuth = otherGrant), requestTarget)),
            NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)

        // K.reqMinSource: 원 scope의 4 < 요구 5.
        val ending = end()
        invalid(link(requestSource(ending, oldEnd, oldEndValue,
            minOrder = EventOrderV1(LifetimeId("old"), 5)),
            requestDestination(), token(ending, requestTarget)),
            NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)

        // K.reqIntentSrc: 출력과 minimum은 IF_STALE, 고정 before는 FORCE_PREMIUM.
        val weak = F.request(binding = 2, origin = LifetimeId("old"),
            order = Long.MAX_VALUE, intent = RefreshIntent.IF_STALE)
        val weakRun = rebind(old = weak)
        val strongBefore = weakRun.fixed.targets.map {
            if (it.target == requestTarget) it.copy(before = oldRebind) else it
        }
        invalid(link(requestSource(weakRun, oldRebind, oldRebindValue,
            minIntent = RefreshIntent.IF_STALE),
            requestDestination(intent = RefreshIntent.IF_STALE),
            forgedToken(weakRun, descriptorWith(weakRun, targets = strongBefore),
                requestTarget)), NamedTransferFailureKind.REQUEST_INTENT_OR_GRANT)
    }

    @Test fun S05_guardReanchorAndMissingHoldFloor() = runReleaseTest {
        val run = recoverHold()
        val guardSource = TypedSourceTuple.GuardFloor(owner(run), H.guard(), oldGuardValue)
        val dest = guardDestination()

        // K.reanchorGuard: plan의 고정 mergeNow만 12000으로 바꾼다.
        val laterPlan = alternateRecovery(run,
            H.input().copy(mergeNow = BootReading("boot", 12000)))
        reanchor(link(guardSource, dest, forgedToken(run,
            descriptorWith(run, recoverHold = laterPlan), guardTarget)),
            ReanchorField.ANCHOR_ELAPSED)

        // K.guardHoldNull: HOLD before와 plan source를 함께 floor 없는 같은 원문으로 둔다.
        val floorless = H.hold(floor = false)
        val floorlessPlan = alternateRecovery(run, H.input(h = floorless))
        val floorlessTargets = run.fixed.targets.map {
            if (it.role == LifecycleRole.HOLD) it.copy(before = floorless) else it
        }
        invalid(link(guardSource, dest, forgedToken(run, descriptorWith(run,
            targets = floorlessTargets, recoverHold = floorlessPlan), guardTarget)),
            NamedTransferFailureKind.SOURCE_MISMATCH)
    }

    private fun sourceRow(
        slot: RequiredSlot, kind: ControlKind, id: String, replacement: ControlNode?
    ): RequiredSlot {
        val required = slot.requirement as SlotRequirement.Required
        val changed = required.fixedSources.mapNotNull { fixed ->
            val fact = fixed.fact as? FixedSourceFact.Node
            val matches = fact != null && fact.kind == kind &&
                (fixed.location.facet == FixedInputFacet.SOURCE ||
                    fixed.location.facet == FixedInputFacet.BEFORE) &&
                (ControlObligations.read(kind, fact.value)
                    as? ControlEntryRead.Interpreted)?.value?.id == id
            if (!matches) fixed
            else replacement?.let {
                fixed.copy(fact = checkNotNull(fact).copy(value = it))
            }
        }
        return slot.copy(requirement = required.copy(fixedSources = changed))
    }

    private suspend fun retainedRequestFixture(): Pair<RequiredSlot, ControlStoreTestStorage> {
        val row = F.request()
        val g = F.guard(F.auth.copy(authStopped = false), 90000)
        val store = seeded(F.raw(row, g, F.request(id = "dormant", owner = "B")))
        val c = store.control.prepareSettleQuery(listOf(row), g, null, F.binding,
            F.decision(followUp = RefreshIntent.FORCE_PREMIUM),
            LifecycleOrderSource(F.life, 21))
        val slot = derivation(c).orderedSlots.single {
            it.key.component == ObligationComponent.REQUEST &&
                it.key.branch == LandingBranch.N &&
                (it.key.subject as? ObligationSubject.Request)?.id == "r"
        }
        return slot to store
    }

    @Test fun S06_retainedEligibilityAndFixedIntentSealSource() = runReleaseTest {
        val intent = derivation(intentCommand()).orderedSlots.single {
            it.key.component == ObligationComponent.SOURCE &&
                it.key.subject is ObligationSubject.Intent &&
                it.key.branch == LandingBranch.N
        }
        val intentLocator =
            DestinationLocator.Payload(ControlKind.RECOVERY_INTENT, "r")
        val intentRead = lockedRead(seeded(intentRecord(intentRow)))

        // T.intentReq
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            sourceRow(intent, ControlKind.RECOVERY_INTENT, "r", null),
            intentLocator, intentRead),
            RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)

        // T.subjIntentOrig
        val movedIntent = ControlObligationFixtures.node(
            """{"id":"r","sessionId":"session","ownerUid":"A","axis":"CAPABILITY","targetEpoch":"k2"}""")
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            sourceRow(intent, ControlKind.RECOVERY_INTENT, "r", movedIntent),
            intentLocator, intentRead),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)

        // T.subjSealOrig
        val seal = rSlot(LandingBranch.N)
        val movedSeal = ControlObligationFixtures.node(
            NamespaceSettlementFixtures.user.replace(
                "\"epoch\":\"u\"", "\"epoch\":\"u9\""))
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            sourceRow(seal, ControlKind.SEAL, "s", movedSeal),
            DestinationLocator.Payload(ControlKind.SEAL, "s"),
            lockedRead(seeded(RetiredNamespaceFixtures.raw(R)))),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)

        // T.requestSourceNull: 고정 원문은 남겨 두고 bound.source만 없앤다.
        val (request, store) = retainedRequestFixture()
        val required = request.requirement as SlotRequirement.Required
        val bound = required.lowerBound as RequiredLowerBound.Request
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            request.copy(requirement = required.copy(
                lowerBound = bound.copy(source = null))),
            DestinationLocator.Payload(ControlKind.DEMAND, "r"), lockedRead(store)),
            RetainedConfirmationFailure.NOT_A_REQUIRED_SOURCE)
    }

    @Test fun S07_retainedRequestSubjectAndFirstReason() = runReleaseTest {
        val (slot, store) = retainedRequestFixture()
        val read = lockedRead(store)
        val locator = DestinationLocator.Payload(ControlKind.DEMAND, "r")
        val subject = slot.key.subject as ObligationSubject.Request
        val required = slot.requirement as SlotRequirement.Required
        val bound = required.lowerBound as RequiredLowerBound.Request

        // T.subjReq
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            slot.copy(key = slot.key.copy(subject = subject.copy(id = "other"))),
            locator, read), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)

        // T.subjReqIds: 두 독립적인 bound 불일치를 각각 SUBJECT에서 멈춘다.
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            slot.copy(requirement = required.copy(
                lowerBound = bound.copy(requiredId = "other"))),
            DestinationLocator.Payload(ControlKind.DEMAND, "other"), read),
            RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            slot.copy(requirement = required.copy(
                lowerBound = bound.copy(ownerUid = "B"))),
            locator, read), RetainedConfirmationFailure.SUBJECT_OR_BOUND_MISMATCH)
    }

    @Test fun S08_retainedRequestExactTextAndMinimums() = runReleaseTest {
        val (slot, store) = retainedRequestFixture()
        val required = slot.requirement as SlotRequirement.Required
        val bound = required.lowerBound as RequiredLowerBound.Request
        val locator = DestinationLocator.Payload(ControlKind.DEMAND, "r")

        // T.matchReqBinding
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            slot.copy(requirement = required.copy(
                lowerBound = bound.copy(binding = bound.binding + 1))),
            locator, lockedRead(store)),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)

        // T.matchReqCompare
        val source = checkNotNull(bound.source)
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            slot.copy(requirement = required.copy(lowerBound = bound.copy(
                minimumOrder = EventOrderV1(source.raisedAt.origin,
                    source.raisedAt.value + 1)))),
            locator, lockedRead(store)),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)

        // T.rowOrigNew: 0과 -0은 typed binding이 같지만 원문은 다르다.
        val zero = F.request(binding = 0)
        val minusZero = F.node(zero.toPayloadEntry().fields.toString()
            .replace("\"binding\":0", "\"binding\":-0"))
        assertEquals(demand(zero), demand(minusZero))
        assertTrue(zero.toPayloadEntry() != minusZero.toPayloadEntry())
        val zeroSource = checkNotNull(demand(zero))
        val fixedZero = sourceRow(slot, ControlKind.DEMAND, "r", zero)
        val zeroRequired = fixedZero.requirement as SlotRequirement.Required
        val zeroBound = (zeroRequired.lowerBound as RequiredLowerBound.Request)
            .copy(source = zeroSource, binding = 0)
        val zeroSlot = fixedZero.copy(
            key = fixedZero.key.copy(subject = ObligationSubject.Request(
                zeroSource.id, zeroSource.ownerUid, zeroSource.binding,
                zeroSource.raisedAt)),
            requirement = zeroRequired.copy(lowerBound = zeroBound))
        val observed = seeded(F.raw(minusZero,
            F.guard(F.auth.copy(authStopped = false), 90000),
            F.request(id = "dormant", owner = "B")))
        rejectedRetained(PriorStorageConfirmation.confirmRetainedSource(
            zeroSlot, locator, lockedRead(observed)),
            RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW)
    }
}
