package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.Preferences
import com.jay.fxi.data.entitlements.DataStoreAccessEpochStore
import com.jay.fxi.data.entitlements.RecordTransactionDecision
import com.jay.fxi.data.entitlements.control.ControlObligationFixtures.literal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude-owned 6-4bC1d-1(a) contract (6-4bC1d consensus r1, D3 and D5): an old Mutations HOLD command left OnceConfirm·U hands
 * over its FLOOR L/N to the guard written by a real RECOVER_HOLD successor, through HandoffCoordinator's new entry
 * recordConfirmedMutationFloorTransfer.
 *  - The new B1 entry accepts only a non-empty HoldFloor → GuardFloor… chain for an old Mutations HOLD FLOOR slot, judged by the
 *    named-chain structure and FLOOR owner checks that G05 also applies (the first link's originalHold equals the slot's fixed
 *    floor row, the chain is continuous, the FLOOR ownerKey rule) and requires priorWrite to be the last link's confirmation and
 *    the end locator to be the destination. It is
 *    re-checked at issuance against an independently derived slot. The existing recordConfirmedTransfer keeps refusing another
 *    command's LifecycleOutput and an empty chain: a successor's success is never attributed to the old slot by itself.
 *  - The same chain may serve both L and N (A3d2, canonical §4.2). G05 is unchanged.
 *  - When the output guard equals the existing guard, RECOVER_HOLD lists the guard in requiredUnchanged, not in its targets; the new
 *    issuer confirmRecoverHoldUnchangedGuardOutput confirms that guard so the link can be issued.
 *  - SOURCE L/N are completed and consumed only after the real RECOVER_HOLD is Confirmed and the HOLD is gone. A floor is never
 *    declared completed.
 * Floors: the old HOLD floor (anchor 10000, wait 30000) has 29000 left at mergeNow 11000; the existing guard's (wait 10000) 9000;
 * the output floor is (boot, 11000, 29000, new-life).
 * The implementation thread reads but does not edit this file.
 */
class MutationsHoldFloorSuccessorContractTest {
    @get:Rule val folder = TemporaryFolder()
    private val fx by lazy { TerminationFixture(folder.root, 0) }
    @After fun close() = runReleaseTest { controlTestTimeout("C1d-1a cleanup", 30_000) { fx.storage.close() } }
    private val H = HoldRecoveryFixtures
    private val L = LandingBranch.L
    private val N = LandingBranch.N
    private val reads = AtomicInteger(0)
    private val now = BootReading("boot", 20_000)
    private fun store() = ControlRecordStore(fx.storage.owner, bootReadingSource = BootReadingSource { reads.incrementAndGet(); now })
    private val evidenceKey = ControlRecordKeys.payload(ControlPayloadKey.COMMAND_EVIDENCE)
    private fun evidence(p: Preferences): JsonArray = Json.parseToJsonElement(p[evidenceKey] ?: "[]") as JsonArray
    private fun ownIn(p: Preferences, c: CommandRef) = evidence(p).count { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }
    private fun holdJson(anchor: Long = 10_000) = H.field(H.hold(), "floor", FloorGuardFixtures.floor(30_000, "boot", anchor, "life"))
        .toPayloadEntry().fields.toString()
    /** The existing guard whose floor already equals the output floor: RECOVER_HOLD leaves it unchanged. */
    private val exactGuard get() = FloorGuardFixtures.guard(FloorGuardFixtures.floor(29_000, "boot", 11_000, "new-life"), auth = true)

    // ── old command and successor ───────────────────────────────────────────────────────────────────────────────────
    private class Recover(val c: CommandRef, val confirmed: ControlStoreResult.Confirmed, val row: ControlNode, val oldGuard: ControlNode?) {
        val fixed get() = (c.body as ControlCommandBody.Lifecycle).input
        val guardFixed get() = (fixed.targets + fixed.requiredUnchanged).single { it.role == LifecycleRole.GUARD }
        val unchanged get() = guardFixed in fixed.requiredUnchanged
        val guardAfter get() = checkNotNull(guardFixed.after)
        val destination get() = DestinationLocator.Guard(checkNotNull(guard(guardAfter)).id, GuardPart.FLOOR)
    }
    private suspend fun seed(guard: ControlNode?, holds: String = "[]") = controlTestTimeout("seed") {
        fx.storage.data.updateData { H.before(H.input(g = guard), siblings = false).toMutablePreferences().apply {
            this[ControlRecordKeys.payload(ControlKind.HOLD)] = holds }.toPreferences() }
    }
    /** Executes the old command; its business Confirm lands and returns failed. own = 0 arms a read-back for a no-op. */
    private suspend fun oldU(vararg actions: ControlMutation, own: Int): CommandRef {
        val c = fx.store.prepare(*actions)
        if (own == 0) fx.armReadBack()
        fx.storage.storage.afterScope = true
        val r = controlTestTimeout("old execute") { fx.store.execute(c) }
        assertTrue("fixture: old Unconfirmed, got $r", r is ControlStoreResult.Unconfirmed)
        assertTrue("fixture: old in U", c in fx.tracker.snapshot())
        assertEquals("fixture: old own Applied", own, ownIn(fx.disk(), c))
        return c
    }
    private fun holdAdd(anchor: Long = 10_000) = fx.store.addition(ControlKind.HOLD) { id -> literal(holdJson(anchor)); set("id", ControlScalar.Text(id)) }
    private fun holdId(a: ControlMutation) = (a as ControlMutation.Add).proposedId
    private suspend fun storedRow(kind: ControlKind, id: String) = H.row(fx.disk(), kind, id)
    /** A real RECOVER_HOLD of the stored HOLD [holdId], merging into the stored guard [guardId] (or none). */
    private suspend fun recover(holdId: String, guardId: String?, krx: String = "k", order: Long = 21): Recover {
        val row = storedRow(ControlKind.HOLD, holdId)
        val oldGuard = guardId?.let { storedRow(ControlKind.DEMAND, it) }
        val input = RecoverHoldInput(row, oldGuard, FenceV1("A", "u", krx), H.binding, H.restart(row, H.executor), H.now)
        val c = fx.store.prepareRecoverHold(input, LifecycleOrderSource(H.life, order))
        val r = controlTestTimeout("recover") { fx.store.execute(c, H.context(input)) }
        assertTrue("fixture: RECOVER_HOLD Confirmed, got $r", r is ControlStoreResult.Confirmed)
        assertTrue("fixture: HOLD $holdId gone", ControlRecordReader().read(fx.disk()).let {
            (it as ControlRecordRead.Supported).locations(holdId).isEmpty() })
        return Recover(c, r as ControlStoreResult.Confirmed, row, oldGuard)
    }

    // ── tokens and links ─────────────────────────────────────────────────────────────────────────────────────────────
    private fun token(r: Recover): PriorStorageConfirmation {
        val t = if (r.unchanged) PriorStorageConfirmation.confirmRecoverHoldUnchangedGuardOutput(r.c, r.fixed, r.confirmed, r.guardFixed.target)
        else PriorStorageConfirmation.confirmLifecycleOutput(r.c, r.fixed, r.confirmed, r.guardFixed.target)
        assertTrue("fixture: guard output token, got $t", t is LifecycleOutputConfirmationResult.Issued)
        return (t as LifecycleOutputConfirmationResult.Issued).value
    }
    private fun owner(c: CommandRef) = ResponsibilityOwner(c.ownerTrackingLifetimeId, "owner-1")
    private fun parsedHold(row: ControlNode) = (ControlObligations.read(ControlKind.HOLD, row) as ControlEntryRead.Interpreted).value as RestoredHold
    private fun output(r: Recover) = TypedDestinationTuple.GuardFloor(r.destination, r.guardAfter, checkNotNull(guard(r.guardAfter)))
    private fun holdSource(r: Recover, oldGuard: ControlNode? = r.oldGuard, mergeNow: BootReading = H.now, origin: LifetimeId = H.life) =
        TypedSourceTuple.HoldFloor(owner(r.c), FloorSource(parsedHold(r.row).id, r.row, parsedHold(r.row), checkNotNull(parsedHold(r.row).floor),
            oldGuard), oldGuard, mergeNow, origin)
    private fun linked(result: NamedTransferResult): NamedTransferLink {
        assertTrue("fixture: link issued, got $result", result is NamedTransferResult.Issued)
        return (result as NamedTransferResult.Issued).value
    }
    private fun holdLink(r: Recover, t: PriorStorageConfirmation = token(r)) =
        linked(NamedTransferLink.linkNamedTransfer(holdSource(r), output(r), t))
    private fun guardLink(r: Recover, preimage: ControlNode, t: PriorStorageConfirmation) = linked(NamedTransferLink.linkNamedTransfer(
        TypedSourceTuple.GuardFloor(owner(r.c), preimage, checkNotNull(guard(preimage))), output(r), t))

    // ── old slots and B1 ─────────────────────────────────────────────────────────────────────────────────────────────
    private class Slots(val input: RequirementInput.Mutations, val required: List<RequiredSlot>) {
        fun of(index: Int, component: ObligationComponent, branch: LandingBranch) = required.single {
            it.key.role == ObligationRole.MutationAction(index) && it.key.component == component && it.key.branch == branch }
    }
    private fun slots(c: CommandRef): Slots {
        val input = RequirementInput.Mutations(c, c.body as ControlCommandBody.Mutations,
            MutationAdoption.Current(checkNotNull(fx.tracker.findPrepared(c)).targets.get()))
        val a = deriveRequiredObligations(input) as RequirementDerivation.Available
        return Slots(input, a.orderedSlots.filter { it.requirement is SlotRequirement.Required })
    }
    private inner class B1(val c: CommandRef) {
        val owner = owner(c)
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300)
        val b = HandoffEventBinding(c, owner)
        val s = slots(c)
        fun completeSources() = s.required.filter { it.key.component == ObligationComponent.SOURCE }.forEach { complete(it) }
        fun complete(slot: RequiredSlot) {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
        }
        fun floor(slot: RequiredSlot, d: DestinationLocator, links: List<NamedTransferLink>, prior: PriorStorageConfirmation) =
            h.recordConfirmedMutationFloorTransfer(b, slot, HandoffDisposition.DurablyOwned(d, links, prior))
        suspend fun issue() = h.closeJoinAndIssueHandoff(s.input)
    }
    private fun recorded(r: RecordResult) = assertEquals(RecordResult.Recorded, r)
    private fun invalid(r: RecordResult) = assertEquals(RecordResult.Rejected(RecordRefusal.INVALID_CONFIRMATION), r)
    private fun issued(r: HandoffIssueResult): HandoffIssueResult.Issued {
        assertTrue("expected B1 Issued, got $r", r is HandoffIssueResult.Issued)
        return r as HandoffIssueResult.Issued
    }
    private fun refusedIssue(r: HandoffIssueResult, reason: HandoffIssueRefusal) =
        assertTrue("expected Refused($reason), got $r", r is HandoffIssueResult.Refused && r.reason == reason)

    /** Success: only the old command's own Applied row removed, terminal, body released, out of U, P and commands. */
    private suspend fun transferred(c: CommandRef, i: HandoffIssueResult.Issued, ownBefore: Int) {
        val before = fx.disk()
        assertEquals("own before", ownBefore, ownIn(before, c))
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, i.closure, i.handoff) }
        assertTrue("expected Completed(ResponsibilityTransferred), got $r",
            r is ControlCompletionResult.Completed && r.mode == CompletionMode.ResponsibilityTransferred)
        val after = fx.disk()
        fun rest(p: Preferences) = p.toMutablePreferences().apply { remove(DataStoreAccessEpochStore.READ_BARRIER); remove(evidenceKey) }.toPreferences()
        assertEquals("every other value unchanged", rest(before), rest(after))
        assertEquals("only the old command's Applied removed",
            JsonArray(evidence(before).filterNot { (it as JsonObject)["commandId"] == JsonPrimitive(c.id) }), evidence(after))
        assertEquals(ControlCommandLifecycle.TERMINATED, c.lifecycleState)
        assertNull("body released", c.captureStateAndBody().body)
        val w = fx.tracker.recoverySnapshot()
        assertTrue("left U and P", c !in w.unresolvedCommands && c !in w.pendingReleases)
    }

    // ── single HOLD runs ─────────────────────────────────────────────────────────────────────────────────────────────
    private class Single(val c: CommandRef, val r: Recover, val own: Int)
    private suspend fun singleAdd(existing: ControlNode?): Single {
        seed(existing)
        val a = holdAdd(); val c = oldU(a, own = 1)
        return Single(c, recover(holdId(a), existing?.let { checkNotNull(guard(it)).id }), 1)
    }
    private suspend fun singleNoopEdit(): Single {
        val hold = H.hold()
        seed(H.guard(), holds = "[${hold.toPayloadEntry().fields}]")
        val c = oldU(fx.store.edit(ControlKind.HOLD, hold) {}, own = 0)
        return Single(c, recover("h", "g"), 0)
    }
    /** SOURCE L/N completed; FLOOR L/N each recorded through the new entry with the same single link. */
    private fun declaredSingle(run: Single): B1 {
        val b1 = B1(run.c); b1.completeSources()
        val t = token(run.r); val link = holdLink(run.r, t)
        for (branch in listOf(L, N)) recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), run.r.destination, listOf(link), t))
        return b1
    }

    // ═══ positive ══════════════════════════════════════════════════════════════════════════════════════════════════════
    @Test fun P1_holdAdd_existingGuardReplaced_floorLNLinked_transferred() = runReleaseTest {
        val run = singleAdd(H.guard())
        assertEquals("fixture: guard REPLACE", LifecycleEffect.REPLACE, run.r.guardFixed.target.effect)
        assertEquals("fixture: output floor", FloorV1("boot", 11_000, 29_000, LifetimeId("new-life")), guard(run.r.guardAfter)?.floor)
        transferred(run.c, issued(declaredSingle(run).issue()), 1)
    }

    @Test fun P2_holdAdd_noGuard_guardCreated_floorLNLinked_transferred() = runReleaseTest {
        val run = singleAdd(null)
        assertEquals("fixture: guard CREATE", LifecycleEffect.CREATE, run.r.guardFixed.target.effect)
        transferred(run.c, issued(declaredSingle(run).issue()), 1)
    }

    @Test fun P3_holdNoopEdit_floorLNLinked_transferred() = runReleaseTest {
        val run = singleNoopEdit()
        transferred(run.c, issued(declaredSingle(run).issue()), 0)
    }

    /** P4: the existing guard already carries the exact output floor: requiredUnchanged, confirmed by the new issuer. */
    @Test fun P4_existingGuardAlreadyExact_requiredUnchanged_newTokenLinked_transferred() = runReleaseTest {
        val run = singleAdd(exactGuard)
        assertTrue("fixture: guard in requiredUnchanged", run.r.unchanged)
        transferred(run.c, issued(declaredSingle(run).issue()), 1)
    }

    /** Two HOLD Adds in one old command; A recovered first (guard CREATE), B then joins A's guard (A3d2 chain). */
    private inner class Pair2(val c: CommandRef, val a: Recover, val b: Recover)
    private suspend fun two(): Pair2 {
        seed(null)
        val addA = holdAdd(10_000); val addB = holdAdd(10_300)
        val c = oldU(addA, addB, own = 1)
        val a = recover(holdId(addA), null)
        assertEquals("fixture: A's guard CREATE", LifecycleEffect.CREATE, a.guardFixed.target.effect)
        val b = recover(holdId(addB), a.destination.id, checkNotNull(fx.disk()[DataStoreAccessEpochStore.KRX_EPOCH]), 22)
        assertEquals("fixture: B joins A's guard", a.destination, b.destination)
        assertEquals("fixture: exact max", FloorV1("boot", 11_000, 29_300, LifetimeId("new-life")), guard(b.guardAfter)?.floor)
        return Pair2(c, a, b)
    }
    @Test fun P5_twoHolds_chainHoldFloorAThenGuardFloorB_andBsOwnLink_transferred() = runReleaseTest {
        val p = two(); val b1 = B1(p.c); b1.completeSources()
        val tB = token(p.b)
        val chainA = listOf(holdLink(p.a), guardLink(p.b, p.a.guardAfter, tB)); val linkB = holdLink(p.b, tB)
        for (branch in listOf(L, N)) {
            recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), p.b.destination, chainA, tB))
            recorded(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, branch), p.b.destination, listOf(linkB), tB))
        }
        transferred(p.c, issued(b1.issue()), 1)
    }

    // ═══ negative: the new B1 entry ════════════════════════════════════════════════════════════════════════════════════
    @Test fun N1_linkOfAnotherHoldActionOnThisSlot_invalidConfirmation() = runReleaseTest {
        val p = two(); val b1 = B1(p.c)
        val tB = token(p.b); val linkB = holdLink(p.b, tB)
        invalid(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), p.b.destination, listOf(linkB), tB))
    }

    @Test fun N2_brokenChain_holdLinkRepeated_invalidConfirmation() = runReleaseTest {
        val p = two(); val b1 = B1(p.c)
        val tA = token(p.a); val linkA = holdLink(p.a, tA)
        invalid(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), p.a.destination, listOf(linkA, linkA), tA))
    }

    @Test fun N3_priorWriteIsAnotherSuccessorsToken_invalidConfirmation() = runReleaseTest {
        val p = two(); val b1 = B1(p.c)
        val tA = token(p.a); val linkB = holdLink(p.b)
        invalid(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, L), p.b.destination, listOf(linkB), tA))
    }

    @Test fun N7_floorNNotRecorded_b1IncompleteSlots() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c); b1.completeSources()
        val t = token(run.r)
        recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), run.r.destination, listOf(holdLink(run.r, t)), t))
        refusedIssue(b1.issue(), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    @Test fun N8_floorDeclaredCompleted_b1IncompleteSlots() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c); b1.completeSources()
        token(run.r) // the successor's guard is confirmable; the slots are declared completed instead
        for (branch in listOf(L, N)) b1.complete(b1.s.of(0, ObligationComponent.FLOOR, branch))
        refusedIssue(b1.issue(), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    /** N9: the existing entry still refuses the successor's LifecycleOutput without a slot chain. */
    @Test fun N9_successorOutputWithoutAChain_existingEntryInvalidConfirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c)
        val t = token(run.r)
        holdLink(run.r, t) // reach the new-entry era: a valid chain exists but is not submitted
        invalid(b1.h.recordConfirmedTransfer(b1.b, b1.s.of(0, ObligationComponent.FLOOR, L).key,
            HandoffDisposition.DurablyOwned(run.r.destination, emptyList(), t)))
    }

    /** N13 (contract r2): the new entry also refuses an empty chain with the successor's token. */
    @Test fun N13_newEntryEmptyChain_invalidConfirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c)
        val t = token(run.r)
        invalid(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), run.r.destination, emptyList(), t))
    }

    /**
     * N14 (contract r2): a slot forged to carry B's HOLD row and FLOOR lower bound as action 0's accepts B's link at recording; issuance
     * re-derives action 0's real slot, whose fixed row is A's, and refuses with TRANSFER_NOT_CONFIRMED.
     */
    @Test fun N14_recordedOnAForgedSlot_issuanceRederivesAndRefuses() = runReleaseTest {
        val p = two(); val b1 = B1(p.c); b1.completeSources()
        val tB = token(p.b); val linkB = holdLink(p.b, tB)
        val chainA = listOf(holdLink(p.a), guardLink(p.b, p.a.guardAfter, tB))
        val real = b1.s.of(0, ObligationComponent.FLOOR, L); val req = real.requirement as SlotRequirement.Required
        val afterA = FixedInputLocation(FixedInputRoot.MUTATION_ACTION, 0, FixedInputFacet.AFTER)
        val bReq = b1.s.of(1, ObligationComponent.FLOOR, L).requirement as SlotRequirement.Required
        val bAfter = bReq.fixedSources
            .single { it.location == FixedInputLocation(FixedInputRoot.MUTATION_ACTION, 1, FixedInputFacet.AFTER) }.fact
        val forged = real.copy(requirement = req.copy(lowerBound = bReq.lowerBound, fixedSources = req.fixedSources.map {
            if (it.location == afterA) FixedSourceEvidence(afterA, bAfter) else it }))
        recorded(b1.floor(forged, p.b.destination, listOf(linkB), tB))
        recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, N), p.b.destination, chainA, tB))
        for (branch in listOf(L, N)) recorded(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, branch), p.b.destination, listOf(linkB), tB))
        refusedIssue(b1.issue(), HandoffIssueRefusal.TRANSFER_NOT_CONFIRMED)
    }

    // ── contract r4: battery r1 rows (6-4bC1d1a_battery_r1_codex.md) ──────────────────────────────────────────────────
    /** N15 (H01): the same FLOOR slot recorded twice through the new entry: the second is a duplicate. */
    @Test fun N15_sameFloorSlotRecordedTwice_duplicate() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c)
        val t = token(run.r); val link = holdLink(run.r, t); val slot = b1.s.of(0, ObligationComponent.FLOOR, L)
        recorded(b1.floor(slot, run.r.destination, listOf(link), t))
        assertEquals(RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT), b1.floor(slot, run.r.destination, listOf(link), t))
    }

    /** N16 (H02): a FLOOR slot already recorded completed conflicts with a transfer through the new entry. */
    @Test fun N16_floorSlotCompletedThenTransferred_conflict() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = B1(run.c)
        val t = token(run.r); val slot = b1.s.of(0, ObligationComponent.FLOOR, L)
        assertEquals(RecordResult.Recorded, b1.h.recordComponentCompleted(b1.b, slot.key, ComponentCompletion(slot.key.subject)))
        assertEquals(RecordResult.Rejected(RecordRefusal.DUPLICATE_OR_CONFLICTING_EVENT),
            b1.floor(slot, run.r.destination, listOf(holdLink(run.r, t)), t))
    }

    /**
     * N17 (H03): the new entry is for an old Mutations command. A coordinator of A's own RECOVER_HOLD receiving the old command's
     * action 1 FLOOR slot with B's link and token (B is not A, the fixed row is B's, the ownerKey matches) refuses it.
     */
    @Test fun N17_nonMutationsCoordinator_invalidConfirmation() = runReleaseTest {
        val p = two(); val s = slots(p.c)
        val owner = owner(p.a.c)
        val h = HandoffCoordinator(p.a.c, owner, HandoffEntryCloser { true }, 300)
        val tB = token(p.b)
        invalid(h.recordConfirmedMutationFloorTransfer(HandoffEventBinding(p.a.c, owner), s.of(1, ObligationComponent.FLOOR, L),
            HandoffDisposition.DurablyOwned(p.b.destination, listOf(holdLink(p.b, tB)), tB)))
    }

    /**
     * N18 (H07): a Lifecycle command's own LifecycleOutput still confirms its slot at issuance. A real REBIND_REQUESTS hands over
     * its REQUEST L through its own named link and every other required slot completed and consumed: B1 issues.
     */
    @Test fun N18_lifecycleCommandsOwnOutput_b1Issues() = runReleaseTest {
        val q = DemandAuthFixtures
        val oldRebind = q.request(binding = 2, origin = LifetimeId("old"), order = Long.MAX_VALUE)
        controlTestTimeout("seed rebind") { fx.storage.data.updateData { q.raw(oldRebind, q.request(id = "dormant", owner = "B")) } }
        val c = fx.store.prepareRebindRequests(listOf(oldRebind), q.binding, LifecycleOrderSource(q.life, 21))
        val r = controlTestTimeout("rebind execute") { fx.store.execute(c, q.context(q.runtime())) }
        assertTrue("fixture: rebind Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val input = RequirementInput.Lifecycle(c, c.body as ControlCommandBody.Lifecycle)
        val required = (deriveRequiredObligations(input) as RequirementDerivation.Available).orderedSlots.filter { it.requirement is SlotRequirement.Required }
        val t = PriorStorageConfirmation.confirmLifecycleOutput(c, (c.body as ControlCommandBody.Lifecycle).input, r as ControlStoreResult.Confirmed,
            LifecycleTarget(ControlKind.DEMAND, "r", LifecycleEffect.REPLACE))
        assertTrue("fixture: own output token, got $t", t is LifecycleOutputConfirmationResult.Issued)
        val token = (t as LifecycleOutputConfirmationResult.Issued).value
        val requestL = required.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == L }
        val n = (required.single { it.key.component == ObligationComponent.REQUEST && it.key.branch == N }.requirement
            as SlotRequirement.Required).lowerBound as RequiredLowerBound.Request
        val owner = owner(c)
        val link = linked(NamedTransferLink.linkNamedTransfer(
            TypedSourceTuple.Request(owner, oldRebind, DemandV1("r", "A", 2, com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM,
                EventOrderV1(LifetimeId("old"), Long.MAX_VALUE)), n.minimumIntent, n.minimumOrder),
            TypedDestinationTuple.Request(DestinationLocator.Payload(ControlKind.DEMAND, "r"),
                q.request(id = "r", owner = "A", binding = 3, origin = q.life, intent = com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, order = 22),
                DemandV1("r", "A", 3, com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, EventOrderV1(LifetimeId("life"), 22))), token))
        val h = HandoffCoordinator(c, owner, HandoffEntryCloser { true }, 300); val b = HandoffEventBinding(c, owner)
        for (slot in required) if (slot === requestL) {
            recorded(h.recordConfirmedTransfer(b, slot.key, HandoffDisposition.DurablyOwned(
                (token.binding as ConfirmationBinding.LifecycleOutput).output.locator, listOf(link), token)))
        } else {
            assertEquals(RecordResult.Recorded, h.recordComponentCompleted(b, slot.key, ComponentCompletion(slot.key.subject)))
            assertEquals(RecordResult.Recorded, h.recordCompletionResultConsumed(b, slot.key))
        }
        issued(h.closeJoinAndIssueHandoff(input))
        Unit
    }

    // ═══ negative: the link issuer and re-anchor recomputation (fixture-level preconditions of the chain) ═══════════════
    @Test fun N4_existingGuardButOldGuardOmitted_linkSourceMismatch() = runReleaseTest {
        val run = singleAdd(H.guard()); val t = token(run.r)
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.SOURCE_MISMATCH)),
            NamedTransferLink.linkNamedTransfer(holdSource(run.r, oldGuard = null), output(run.r), t))
    }

    @Test fun N5_outputOneMillisecondShort_reanchorExactMax() = runReleaseTest {
        val run = singleAdd(H.guard())
        val short = FloorGuardFixtures.field(run.r.guardAfter, "floor", FloorGuardFixtures.floor(28_999, "boot", 11_000, "new-life"))
        val src = holdSource(run.r)
        assertEquals(ReanchorValidation.Invalid(ReanchorField.EXACT_MAX),
            validHoldReanchor(src.source, run.r.oldGuard, H.now, H.life, run.r.destination.id, short))
    }

    @Test fun N6_mergeNowOrOriginTampered_linkSourceMismatch() = runReleaseTest {
        val run = singleAdd(H.guard()); val t = token(run.r)
        val mismatch = NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.SOURCE_MISMATCH))
        assertEquals(mismatch, NamedTransferLink.linkNamedTransfer(holdSource(run.r, mergeNow = BootReading("boot", 11_001)), output(run.r), t))
        assertEquals(mismatch, NamedTransferLink.linkNamedTransfer(holdSource(run.r, origin = LifetimeId("other-life")), output(run.r), t))
    }

    // ═══ negative: latest record at the handoff (G05, unchanged) ═══════════════════════════════════════════════════════
    private fun floorFailures(i: HandoffIssueResult.Issued, s: Slots, id: G05Id, actual: G05Location?) = listOf(L, N).map { branch ->
        val slot = s.of(0, ObligationComponent.FLOOR, branch)
        G05Failure(id, slot.key, G05Location.Fixed((slot.requirement as SlotRequirement.Required).fixedSources.first().location),
            G05Location.Submitted(i.handoff.slots.indexOfFirst { it.key == slot.key }), actual)
    }
    private suspend fun g05Refused(c: CommandRef, i: HandoffIssueResult.Issued, expected: List<G05Failure>) {
        val before = fx.disk(); val writes = fx.storage.storage.writes
        val r = controlTestTimeout("handoff") { store().handoffAfterUncertainConfirm(c, i.closure, i.handoff) }
        assertTrue("expected Rejected(G05($expected)), got $r",
            r is ControlCompletionResult.Rejected && r.reason == CompletionRejectionReason.G05(expected))
        assertEquals("no write", writes, fx.storage.storage.writes)
        assertEquals("record unchanged", before, fx.disk())
        assertEquals("old own kept", 1, ownIn(fx.disk(), c))
        assertEquals(ControlCommandLifecycle.RETAINED, c.lifecycleState)
    }
    private suspend fun guardIndex(id: String): Int {
        val read = ControlRecordReader().read(fx.disk()) as ControlRecordRead.Supported
        val (kind, entry) = read.locations(id).single()
        check(kind == ControlKind.DEMAND)
        return read.arrays.getValue(kind).entries.indexOfFirst { it === entry }
    }

    @Test fun N10_latestGuardRemovedAfterIssuance_g05DestinationAndConfirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = declaredSingle(run); val i = issued(b1.issue())
        val gid = run.r.destination.id
        fx.edit { p -> val key = ControlRecordKeys.payload(ControlKind.DEMAND)
            p[key] = JsonArray((Json.parseToJsonElement(checkNotNull(p[key])) as JsonArray).filter {
                (it as JsonObject)["id"] != JsonPrimitive(gid) }).toString() }
        g05Refused(run.c, i, floorFailures(i, b1.s, G05Id.DESTINATION, null) + floorFailures(i, b1.s, G05Id.CONFIRMATION, null))
    }

    @Test fun N11_latestGuardFloorShortenedAfterIssuance_g05ConfirmationAndLowerBound() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = declaredSingle(run); val i = issued(b1.issue())
        val gid = run.r.destination.id
        fx.edit { p -> val key = ControlRecordKeys.payload(ControlKind.DEMAND)
            p[key] = checkNotNull(p[key]).replace("\"waitMillis\":29000", "\"waitMillis\":20000").also { check(it != p[key]) } }
        val at = G05Location.ActualPayload(ControlKind.DEMAND, guardIndex(gid))
        g05Refused(run.c, i, floorFailures(i, b1.s, G05Id.CONFIRMATION, at) + floorFailures(i, b1.s, G05Id.LOWER_BOUND, at))
    }

    /** N12: a HOLD field change is not a preparable Edit (regression); the valid no-op Edit then takes the P3 path. */
    @Test fun N12_holdChangeEditInvalid_thenNoopEditTransferred() = runReleaseTest {
        val changed = ControlMutation.Edit.prepare(ControlKind.HOLD, H.hold()) { set("binding", ControlScalar.Integer(18)) }
        assertEquals(ControlWriteResult.Rejected(ControlWriteFailure.INVALID_CHANGE), changed.changed)
        val run = singleNoopEdit()
        transferred(run.c, issued(declaredSingle(run).issue()), 0)
    }

    // ═══ contract r5 — 6-4bC1d-1(b): the guard rewritten after RECOVER_HOLD (consensus D3 (b)) ═══════════════════════════
    // Each rewrite continues the chain GuardFloor(previous output) → GuardFloor(new output): a Mutations recordFloor is
    // confirmed by the new MutationFloorOutput token and linked by linkRecordedFloorTransfer; an UPDATE_AUTH (auth changed,
    // floor unchanged or raised by its Answer) is a LifecycleOutput of its GUARD REPLACE linked by linkNamedTransfer. The old
    // HOLD FLOOR L/N submit the whole chain to the latest guard through recordConfirmedMutationFloorTransfer.
    // After RECOVER_HOLD the guard floor is (boot, 11000, 29000, new-life): 28000 left at 12000.
    private val at12 = BootReading("boot", 12_000)
    private class Step(val c: CommandRef, val token: PriorStorageConfirmation, val link: NamedTransferLink, val after: ControlNode)
    private fun issuedFloorToken(r: MutationFloorOutputConfirmationResult): PriorStorageConfirmation {
        assertTrue("fixture: floor output token, got $r", r is MutationFloorOutputConfirmationResult.Issued)
        return (r as MutationFloorOutputConfirmationResult.Issued).value
    }
    private fun guardSource(c: CommandRef, preimage: ControlNode) = TypedSourceTuple.GuardFloor(owner(c), preimage, checkNotNull(guard(preimage)))
    /** A real Mutations recordFloor of the stored guard [previous], waiting [wait] at 12000. */
    private suspend fun floorStep(previous: ControlNode, wait: Long = 31_000): Step {
        val m = fx.store.prepare(fx.store.recordFloor(previous, at12, wait, H.life))
        val r = controlTestTimeout("recordFloor") { fx.store.execute(m) }
        assertTrue("fixture: recordFloor Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val t = issuedFloorToken(PriorStorageConfirmation.confirmMutationFloorOutput(m, 0, r as ControlStoreResult.Confirmed))
        val out = (t.binding as ConfirmationBinding.MutationFloorOutput).output
        return Step(m, t, linked(NamedTransferLink.linkRecordedFloorTransfer(guardSource(m, previous), out, t)), out.row)
    }
    /** A real UPDATE_AUTH Answer (AUTHENTICATION, [seconds]) of the stored guard [previous]: auth changes; floor per the merge. */
    private suspend fun authStep(previous: ControlNode, seconds: Long): Step {
        val q = DemandAuthFixtures
        val krx = checkNotNull(fx.disk()[DataStoreAccessEpochStore.KRX_EPOCH])
        val fence = FenceV1("A", "u", krx) // RECOVER_HOLD rotated the KRX epoch; the answer is accepted and confirmed under it
        val started = q.query.copy(fence = fence)
        val d = q.decision(q = started, before = fence, after = fence, outcome = com.jay.fxi.data.entitlements.EntitlementsOutcome.Indeterminate(
            com.jay.fxi.data.entitlements.IndeterminateReason.AUTHENTICATION, seconds), capture = at12, merge = at12)
        val u = fx.store.prepareUpdateAuth(previous, null, q.binding, LifecycleAuthEvent.Answer(d), LifecycleOrderSource(q.life, 21))
        val r = controlTestTimeout("update auth") {
            fx.store.execute(u, q.context(q.runtime(registrations = listOf(LifecycleQueryRegistration("query-21", started))))) }
        assertTrue("fixture: UPDATE_AUTH Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val fixed = (u.body as ControlCommandBody.Lifecycle).input
        val target = fixed.targets.single { it.role == LifecycleRole.GUARD }
        val t = PriorStorageConfirmation.confirmLifecycleOutput(u, fixed, r as ControlStoreResult.Confirmed, target.target)
        assertTrue("fixture: UPDATE_AUTH guard token, got $t", t is LifecycleOutputConfirmationResult.Issued)
        val token = (t as LifecycleOutputConfirmationResult.Issued).value
        val out = (token.binding as ConfirmationBinding.LifecycleOutput).output as TypedDestinationTuple.GuardFloor
        return Step(u, token, linked(NamedTransferLink.linkNamedTransfer(guardSource(u, previous), out, token)), out.row)
    }
    /** A real UPDATE_AUTH from a Caller or Recovery event: auth changes (stop cleared, new order), the floor is kept. */
    private suspend fun eventStep(previous: ControlNode, event: LifecycleAuthEvent, runtime: DemandAuthRuntime): Step {
        val q = DemandAuthFixtures
        val u = fx.store.prepareUpdateAuth(previous, null, q.binding, event, LifecycleOrderSource(q.life, 21))
        val r = controlTestTimeout("update auth event") { fx.store.execute(u, q.context(runtime)) }
        assertTrue("fixture: UPDATE_AUTH Confirmed, got $r", r is ControlStoreResult.Confirmed)
        val fixed = (u.body as ControlCommandBody.Lifecycle).input
        assertEquals("fixture: only the guard is rewritten", listOf(LifecycleRole.GUARD), fixed.targets.map { it.role })
        val t = PriorStorageConfirmation.confirmLifecycleOutput(u, fixed, r as ControlStoreResult.Confirmed, fixed.targets.single().target)
        assertTrue("fixture: UPDATE_AUTH guard token, got $t", t is LifecycleOutputConfirmationResult.Issued)
        val token = (t as LifecycleOutputConfirmationResult.Issued).value
        val out = (token.binding as ConfirmationBinding.LifecycleOutput).output as TypedDestinationTuple.GuardFloor
        return Step(u, token, linked(NamedTransferLink.linkNamedTransfer(guardSource(u, previous), out, token)), out.row)
    }
    /** Caller at boot 60000, after the floor (29000 from 11000) expired: no retry REQUEST. */
    private suspend fun callerStep(previous: ControlNode): Step {
        val q = DemandAuthFixtures
        val caller = LifecycleCaller("later", LifecycleCallerOrigin.CALLER, q.binding, LifecycleOrderGrant(q.life, 20, 1, 0, 21),
            com.jay.fxi.data.entitlements.RefreshIntent.FORCE_PREMIUM, BootReading("boot", 60_000))
        return eventStep(previous, LifecycleAuthEvent.Caller(caller), q.runtime(caller = caller))
    }
    private suspend fun recoveryStep(previous: ControlNode): Step {
        val q = DemandAuthFixtures
        val rec = LifecycleRecovery("rec", q.identity, q.life, 11, 22, 1)
        return eventStep(previous, LifecycleAuthEvent.Recovery(rec), q.runtime(recovery = rec))
    }

    /** SOURCE L/N completed; FLOOR L/N each the chain [first, …rest] ending at [last]'s output with its token. */
    private suspend fun chainTransferred(run: Single, rest: List<Step>) {
        val b1 = B1(run.c); b1.completeSources()
        val chain = listOf(holdLink(run.r)) + rest.map { it.link }; val last = rest.last()
        for (branch in listOf(L, N)) recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), last.link.destination.locator, chain, last.token))
        transferred(run.c, issued(b1.issue()), run.own)
    }

    @Test fun R1_recordFloorRewrite_chainToTheLatestGuard_transferred() = runReleaseTest {
        val run = singleAdd(H.guard()); val m = floorStep(run.r.guardAfter)
        assertEquals("fixture: max(28000, 31000) at 12000", FloorV1("boot", 12_000, 31_000, H.life), guard(m.after)?.floor)
        chainTransferred(run, listOf(m))
    }

    @Test fun R2_updateAuthAnswerRaisesTheFloor_chainToTheLatestGuard_transferred() = runReleaseTest {
        val run = singleAdd(H.guard()); val u = authStep(run.r.guardAfter, 60)
        assertEquals("fixture: raised to 60000 at 12000", 60_000L, guard(u.after)?.floor?.waitMillis)
        assertTrue("fixture: auth changed", guard(u.after)?.auth != guard(run.r.guardAfter)?.auth)
        chainTransferred(run, listOf(u))
    }

    @Test fun R3_updateAuthAnswerKeepsTheFloor_chainToTheLatestGuard_transferred() = runReleaseTest {
        val run = singleAdd(H.guard()); val u = authStep(run.r.guardAfter, 10)
        assertEquals("fixture: floor unchanged", guard(run.r.guardAfter)?.floor, guard(u.after)?.floor)
        assertTrue("fixture: auth changed", guard(u.after)?.auth != guard(run.r.guardAfter)?.auth)
        chainTransferred(run, listOf(u))
    }

    @Test fun R4_recoverThenRecordFloorThenUpdateAuth_threeLinkChain_transferred() = runReleaseTest {
        val run = singleAdd(H.guard()); val m = floorStep(run.r.guardAfter); val u = authStep(m.after, 10)
        chainTransferred(run, listOf(m, u))
    }

    @Test fun R5_rewriteNotLinked_staleChainAtHandoff_g05Confirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val b1 = declaredSingle(run); val i = issued(b1.issue())
        floorStep(run.r.guardAfter) // the guard is rewritten after issuance; the chain still ends at RECOVER_HOLD's output
        val at = G05Location.ActualPayload(ControlKind.DEMAND, guardIndex(run.r.destination.id))
        g05Refused(run.c, i, floorFailures(i, b1.s, G05Id.CONFIRMATION, at))
    }

    @Test fun R6_rewritesLinkedOutOfOrder_invalidConfirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val m = floorStep(run.r.guardAfter); val u = authStep(m.after, 10)
        val b1 = B1(run.c)
        invalid(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), u.link.destination.locator, listOf(holdLink(run.r), u.link, m.link), m.token))
    }

    @Test fun R7_priorWriteNotTheLastLinksToken_invalidConfirmation() = runReleaseTest {
        val run = singleAdd(H.guard()); val m = floorStep(run.r.guardAfter)
        val b1 = B1(run.c); val tr = token(run.r)
        invalid(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, L), m.link.destination.locator, listOf(holdLink(run.r, tr), m.link), tr))
    }

    @Test fun R8_recordedFloorLinkFromAnotherPreimage_sourceMismatch() = runReleaseTest {
        val run = singleAdd(H.guard()); val m = floorStep(run.r.guardAfter)
        val out = (m.token.binding as ConfirmationBinding.MutationFloorOutput).output
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.SOURCE_MISMATCH)),
            NamedTransferLink.linkRecordedFloorTransfer(guardSource(m.c, H.guard()), out, m.token))
    }

    @Test fun R9_recordedFloorDestinationWeakerThanTheSource_destinationMismatch() = runReleaseTest {
        val run = singleAdd(H.guard()); val m = floorStep(run.r.guardAfter)
        val weak = FloorGuardFixtures.field(m.after, "floor", FloorGuardFixtures.floor(20_000, "boot", 12_000, "life"))
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.DESTINATION_MISMATCH)),
            NamedTransferLink.linkRecordedFloorTransfer(guardSource(m.c, run.r.guardAfter),
                TypedDestinationTuple.GuardFloor(m.link.destination.locator as DestinationLocator.Guard, weak, checkNotNull(guard(weak))), m.token))
    }

    @Test fun R10_recordedFloorDestinationNotTheConfirmedOutput_confirmationMismatch() = runReleaseTest {
        val run = singleAdd(H.guard()); val m = floorStep(run.r.guardAfter)
        val other = FloorGuardFixtures.field(m.after, "floor", FloorGuardFixtures.floor(40_000, "boot", 12_000, "life"))
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)),
            NamedTransferLink.linkRecordedFloorTransfer(guardSource(m.c, run.r.guardAfter),
                TypedDestinationTuple.GuardFloor(m.link.destination.locator as DestinationLocator.Guard, other, checkNotNull(guard(other))), m.token))
    }

    @Test fun R11_floorOutputTokenOfAnotherActionOrCommandOrKind_rejected() = runReleaseTest {
        val run = singleAdd(H.guard())
        val m = fx.store.prepare(fx.store.recordFloor(run.r.guardAfter, at12, 31_000, H.life))
        val cm = controlTestTimeout("recordFloor") { fx.store.execute(m) } as ControlStoreResult.Confirmed
        assertEquals(MutationFloorOutputConfirmationResult.Rejected(MutationFloorConfirmationFailure.REF_OR_ACTION_MISMATCH),
            PriorStorageConfirmation.confirmMutationFloorOutput(m, 1, cm))
        val other = fx.store.prepare(fx.store.addition(ControlKind.RECOVERY_INTENT) { id ->
            literal(ControlObligationFixtures.recovery); set("id", ControlScalar.Text(id)) })
        val co = controlTestTimeout("other") { fx.store.execute(other) } as ControlStoreResult.Confirmed
        assertEquals(MutationFloorOutputConfirmationResult.Rejected(MutationFloorConfirmationFailure.REF_OR_ACTION_MISMATCH),
            PriorStorageConfirmation.confirmMutationFloorOutput(m, 0, co))
        assertEquals(MutationFloorOutputConfirmationResult.Rejected(MutationFloorConfirmationFailure.UNSUPPORTED_ACTION),
            PriorStorageConfirmation.confirmMutationFloorOutput(other, 0, co))
    }

    @Test fun R12_floorOutputTokenOutsideItsEntries_rejected() = runReleaseTest {
        val run = singleAdd(H.guard()); val m = floorStep(run.r.guardAfter)
        val b1 = B1(run.c); val chain = listOf(holdLink(run.r), m.link)
        invalid(b1.h.recordConfirmedTransfer(b1.b, b1.s.of(0, ObligationComponent.FLOOR, L).key,
            HandoffDisposition.DurablyOwned(m.link.destination.locator, chain, m.token)))
        val out = (m.token.binding as ConfirmationBinding.MutationFloorOutput).output
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)),
            NamedTransferLink.linkNamedTransfer(guardSource(m.c, run.r.guardAfter), out, m.token))
        val u = authStep(m.after, 10)
        assertEquals(NamedTransferResult.Rejected(NamedTransferFailure.Invalid(NamedTransferFailureKind.CONFIRMATION_MISMATCH)),
            NamedTransferLink.linkRecordedFloorTransfer(guardSource(u.c, m.after), u.link.destination as TypedDestinationTuple.GuardFloor, u.token))
    }

    // ── contract r6: UPDATE_AUTH from Caller and Recovery events (6-4bC1d1b_candidates_codex.r1.md: both may REPLACE the guard) ──
    @Test fun R13_updateAuthCallerRewrite_chainToTheLatestGuard_transferred() = runReleaseTest {
        val run = singleAdd(H.guard()); val u = callerStep(run.r.guardAfter)
        assertEquals("fixture: floor unchanged", guard(run.r.guardAfter)?.floor, guard(u.after)?.floor)
        assertTrue("fixture: auth changed", guard(u.after)?.auth != guard(run.r.guardAfter)?.auth)
        chainTransferred(run, listOf(u))
    }

    /** R14: Recovery applies to a stopped AUTH, so the existing guard carries the stopped fixture auth. */
    @Test fun R14_updateAuthRecoveryRewrite_chainToTheLatestGuard_transferred() = runReleaseTest {
        val stopped = DemandAuthFixtures.guard(auth = DemandAuthFixtures.auth, wait = 10_000)
        assertTrue("fixture: stopped auth", guard(stopped)?.auth?.authStopped == true)
        val run = singleAdd(stopped); val u = recoveryStep(run.r.guardAfter)
        assertEquals("fixture: floor unchanged", guard(run.r.guardAfter)?.floor, guard(u.after)?.floor)
        assertTrue("fixture: auth changed", guard(u.after)?.auth != guard(run.r.guardAfter)?.auth)
        chainTransferred(run, listOf(u))
    }

    // ═══ contract r7 — 6-4bC1d-3a: the old command also edits the guard RECOVER_HOLD rewrites (consensus D6) ════════════
    // Action 0 adds the HOLD, action 1 is a no-op Edit of the existing guard "g" (floor and AUTH). After the real 5d the guard
    // FLOOR slots of action 1 submit their own chain GuardFloor(old g) → GuardFloor(5d output); the AUTH slots, preserved by
    // 5d, are retained on the guard's AUTH part.
    private inner class WithGuard(val c: CommandRef, val r: Recover, val oldGuard: ControlNode)
    private suspend fun withGuardEdit(): WithGuard {
        val g = H.guard(); seed(g)
        val a = holdAdd(); val c = oldU(a, fx.store.edit(ControlKind.DEMAND, g) {}, own = 1)
        val r = recover(holdId(a), "g")
        assertEquals("fixture: guard REPLACE", LifecycleEffect.REPLACE, r.guardFixed.target.effect)
        assertEquals("fixture: AUTH preserved by 5d", guard(g)?.auth, guard(r.guardAfter)?.auth)
        return WithGuard(c, r, g)
    }
    private fun guardFirstLink(w: WithGuard, t: PriorStorageConfirmation) =
        linked(NamedTransferLink.linkNamedTransfer(guardSource(w.r.c, w.oldGuard), output(w.r), t))
    private suspend fun retainedAuth(b1: B1, branch: LandingBranch) {
        val slot = b1.s.of(1, ObligationComponent.AUTH, branch); val d = DestinationLocator.Guard("g", GuardPart.AUTH)
        val t = PriorStorageConfirmation.confirmRetainedSource(slot, d, lockedRead())
        assertTrue("fixture: AUTH retained token, got $t", t is RetainedSourceConfirmationResult.Issued)
        recorded(b1.h.recordConfirmedTransfer(b1.b, slot.key,
            HandoffDisposition.DurablyOwned(d, emptyList(), (t as RetainedSourceConfirmationResult.Issued).value)))
    }
    private suspend fun lockedRead() = controlTestTimeout("locked read") {
        fx.storage.owner.transactRecord { raw -> RecordTransactionDecision.Observe(ControlRecordReader().read(raw) as ControlRecordRead.Supported) }
    }

    @Test fun M1_holdAndSameGuardEdit_bothFloorChainsAndRetainedAuth_transferred() = runReleaseTest {
        val w = withGuardEdit(); val b1 = B1(w.c); b1.completeSources()
        val t = token(w.r); val holdChain = listOf(holdLink(w.r, t)); val guardChain = listOf(guardFirstLink(w, t))
        for (branch in listOf(L, N)) {
            recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), w.r.destination, holdChain, t))
            recorded(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, branch), w.r.destination, guardChain, t))
            retainedAuth(b1, branch)
        }
        transferred(w.c, issued(b1.issue()), 1)
    }

    @Test fun M2_guardFloorSlotWithTheHoldsLinkOrAnEmptyChain_invalidConfirmation() = runReleaseTest {
        val w = withGuardEdit(); val b1 = B1(w.c); val t = token(w.r)
        val slot = b1.s.of(1, ObligationComponent.FLOOR, L)
        invalid(b1.floor(slot, w.r.destination, listOf(holdLink(w.r, t)), t))
        invalid(b1.floor(slot, w.r.destination, emptyList(), t))
    }

    @Test fun M3_guardFloorNNotRecorded_b1IncompleteSlots() = runReleaseTest {
        val w = withGuardEdit(); val b1 = B1(w.c); b1.completeSources()
        val t = token(w.r); val holdChain = listOf(holdLink(w.r, t)); val guardChain = listOf(guardFirstLink(w, t))
        for (branch in listOf(L, N)) {
            recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), w.r.destination, holdChain, t))
            retainedAuth(b1, branch)
        }
        recorded(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, L), w.r.destination, guardChain, t))
        refusedIssue(b1.issue(), HandoffIssueRefusal.INCOMPLETE_OR_INVALID_SLOTS)
    }

    private fun failuresAt(i: HandoffIssueResult.Issued, s: Slots, index: Int, component: ObligationComponent, id: G05Id, actual: G05Location?) =
        listOf(L, N).map { branch -> val slot = s.of(index, component, branch)
            G05Failure(id, slot.key, G05Location.Fixed((slot.requirement as SlotRequirement.Required).fixedSources.first().location),
                G05Location.Submitted(i.handoff.slots.indexOfFirst { it.key == slot.key }), actual) }

    /** M4: a later recordFloor extends only the HOLD chain; the guard chain stops at the 5d output: G05 CONFIRMATION on it. */
    @Test fun M4_laterRecordFloorLinkedOnlyForTheHold_guardFloorStale_g05Confirmation() = runReleaseTest {
        val w = withGuardEdit(); val t = token(w.r)
        val m = floorStep(w.r.guardAfter)
        val b1 = B1(w.c); b1.completeSources()
        val holdChain = listOf(holdLink(w.r, t), m.link); val guardChain = listOf(guardFirstLink(w, t))
        for (branch in listOf(L, N)) {
            retainedAuth(b1, branch)
            recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), m.link.destination.locator, holdChain, m.token))
            recorded(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, branch), w.r.destination, guardChain, t))
        }
        val i = issued(b1.issue())
        val at = G05Location.ActualPayload(ControlKind.DEMAND, guardIndex("g"))
        g05Refused(w.c, i, failuresAt(i, b1.s, 1, ObligationComponent.FLOOR, G05Id.CONFIRMATION, at))
    }

    /**
     * M5 (scope boundary, consensus D6): a later UPDATE_AUTH changes the same guard's AUTH. Both FLOOR chains reach the latest
     * guard, but the old AUTH retained tokens no longer match: G05 CONFIRMATION and LOWER_BOUND on AUTH L/N; a new retained
     * token cannot be issued. The AUTH successor handover is C1d-4.
     */
    @Test fun M5_laterUpdateAuthChangesTheAuth_oldAuthRefused() = runReleaseTest {
        val w = withGuardEdit(); val t = token(w.r)
        val b1 = B1(w.c); b1.completeSources()
        for (branch in listOf(L, N)) retainedAuth(b1, branch)
        val u = callerStep(w.r.guardAfter)
        val holdChain = listOf(holdLink(w.r, t), u.link); val guardChain = listOf(guardFirstLink(w, t), u.link)
        for (branch in listOf(L, N)) {
            recorded(b1.floor(b1.s.of(0, ObligationComponent.FLOOR, branch), u.link.destination.locator, holdChain, u.token))
            recorded(b1.floor(b1.s.of(1, ObligationComponent.FLOOR, branch), u.link.destination.locator, guardChain, u.token))
        }
        assertEquals(RetainedSourceConfirmationResult.Rejected(RetainedConfirmationFailure.NO_EXACT_RETAINED_ROW),
            PriorStorageConfirmation.confirmRetainedSource(b1.s.of(1, ObligationComponent.AUTH, L), DestinationLocator.Guard("g", GuardPart.AUTH), lockedRead()))
        val i = issued(b1.issue())
        val at = G05Location.ActualPayload(ControlKind.DEMAND, guardIndex("g"))
        g05Refused(w.c, i, failuresAt(i, b1.s, 1, ObligationComponent.AUTH, G05Id.CONFIRMATION, at) +
            failuresAt(i, b1.s, 1, ObligationComponent.AUTH, G05Id.LOWER_BOUND, at))
    }
}
